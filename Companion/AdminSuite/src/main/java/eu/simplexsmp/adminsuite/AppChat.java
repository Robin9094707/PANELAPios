package eu.simplexsmp.adminsuite;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.Bukkit;
import net.kyori.adventure.text.Component;
/** One public-chat store with a bounded archive and a separate 50-row live view. */
final class AppChat {
    static final int LIMIT=50;
    private static final int ARCHIVE_LIMIT=2000;
    private static final long ARCHIVE_AGE=48*3_600_000L;
    private final Deque<Map<String,Object>> rows=new ArrayDeque<>();private long sequence;private String epoch=UUID.randomUUID().toString();
    private Path file;private ScheduledExecutorService writer;private boolean writePending;
    /** Loads an existing history; a damaged file is kept aside and never blocks startup. */
    synchronized void attach(Path target){
        file=target;
        writer=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"Simplex-App-Chat");t.setDaemon(true);return t;});
        if(!Files.exists(target))return;
        try(Reader in=Files.newBufferedReader(target)){
            Properties p=new Properties();p.load(in);
            sequence=Long.parseLong(p.getProperty("sequence","0"));epoch=p.getProperty("epoch",epoch);
            loadRows(p,"archive.",ARCHIVE_LIMIT-LIMIT);
            loadRows(p,"row.",LIMIT);
            trim();
        }catch(Exception damaged){
            try{Files.move(target,target.resolveSibling(target.getFileName()+".damaged-"+System.currentTimeMillis()));}catch(IOException ignored){}
            rows.clear();
        }
    }
    private void loadRows(Properties p,String key,int max){
        for(int i=0;i<max;i++){
            String raw=p.getProperty(key+i);if(raw==null)break;
            if(DiscordBotManager.Json.parse(raw) instanceof Map<?,?> row)rows.addLast(new LinkedHashMap<>(AppApiManager.object(row)));
        }
    }
    private void trim(){
        long cutoff=System.currentTimeMillis()-ARCHIVE_AGE;
        while(rows.size()>ARCHIVE_LIMIT||rows.size()>LIMIT&&AiAdmin.number(rows.peekFirst().get("at"))<cutoff)rows.removeFirst();
    }
    private static void writeRows(Properties properties,Collection<Map<String,Object>> snapshot){
        int split=Math.max(0,snapshot.size()-LIMIT),index=0;
        for(Map<String,Object> row:snapshot){
            String key=index<split?"archive."+index:"row."+(index-split);
            properties.setProperty(key,DiscordBotManager.Json.stringify(row));index++;
        }
    }
    synchronized void stop(){if(writer==null)return;writer.shutdownNow();writer=null;writePending=false;persistNow();}
    synchronized void record(String uuid,String name,String text,String source){
        rows.addLast(SimplexAdminSuite.appMap("id",Long.toString(++sequence),"uuid",uuid,"name",name,"text",text,"source",source,"at",System.currentTimeMillis()));
        trim();
        schedule();
    }
    synchronized Object read(long after){return SimplexAdminSuite.appMap("items",rows.stream().skip(Math.max(0,rows.size()-LIMIT)).filter(row->AiAdmin.number(row.get("id"))>after).toList(),"cursor",Long.toString(sequence),"epoch",epoch,"limit",LIMIT);}
    /** Removes the shared history; the cursor keeps counting so no client sees reused IDs. */
    synchronized Object clear(SimplexAdminSuite plugin,String actor){
        int removed=rows.size();rows.clear();epoch=UUID.randomUUID().toString();schedule();
        if(plugin!=null)plugin.suiteAudit(actor,"App-Chatverlauf geleert",removed+" Nachrichten");
        return SimplexAdminSuite.appMap("ok",true,"title","Chatverlauf geleert","description",removed+" Nachrichten entfernt.","epoch",epoch);
    }
    private void schedule(){
        if(writer==null||writePending)return;writePending=true;
        try{writer.schedule(()->{synchronized(AppChat.this){writePending=false;persistNow();}},2,TimeUnit.SECONDS);}catch(RejectedExecutionException stopped){writePending=false;}
    }
    private void persistNow(){
        if(file==null)return;
        Properties p=new Properties();p.setProperty("schema","1");p.setProperty("sequence",Long.toString(sequence));p.setProperty("epoch",epoch);
        writeRows(p,rows);
        try{AtomicState.save(file,p,"Simplex public app chat history (last "+LIMIT+" public messages)");}catch(IOException e){Bukkit.getLogger().warning("App-Chatverlauf konnte nicht gespeichert werden.");}
    }
    synchronized List<Map<String, Object>> historySnapshot() { trim();return HistoryApi.copies(rows); }
    synchronized void historyReplace(List<Map<String, Object>> items) throws IOException {
        if (file == null) throw new IOException("Chat history not attached");
        String nextEpoch = UUID.randomUUID().toString();
        Properties p = new Properties(); p.setProperty("schema", "1"); p.setProperty("sequence", Long.toString(sequence)); p.setProperty("epoch", nextEpoch);
        List<Map<String,Object>> kept=items.subList(Math.max(0,items.size()-ARCHIVE_LIMIT),items.size());
        writeRows(p,kept);
        AtomicState.save(file, p, "Simplex public app chat history");
        rows.clear(); rows.addAll(HistoryApi.copies(kept));trim(); epoch = nextEpoch;
    }

    static String validateName(String name){if(!name.matches("[\\p{L}\\p{N} _.-]{1,32}"))throw new IllegalArgumentException("Anzeigename: 1–32 Buchstaben, Zahlen, Leerzeichen, Punkt, _ oder -");return name;}
    static String validateMessage(String text){if(text.isBlank()||text.length()>500||text.codePoints().anyMatch(c->Character.isISOControl(c)||c==0x00a7))throw new IllegalArgumentException("Nachricht: 1–500 Zeichen ohne Steuer- oder Formatcodes");return text;}
    Object send(SimplexAdminSuite plugin,String actor,String name,String message){
        String sender=validateName(name),text=validateMessage(message);String formatted=sender.equalsIgnoreCase("Server")?"[Server] "+text:"<"+sender+"> "+text;
        // Literal component, never a console command, tellraw JSON, MiniMessage or selector.
        Bukkit.broadcast(Component.text(formatted));record("",sender,text,"app");
        plugin.suiteAudit(actor,"App-Chat",sender+": "+text);
        if(plugin.discordRelay()!=null)plugin.discordRelay().relayMinecraftChat(sender,"App",text);
        return SimplexAdminSuite.appMap("ok",true);
    }
}
