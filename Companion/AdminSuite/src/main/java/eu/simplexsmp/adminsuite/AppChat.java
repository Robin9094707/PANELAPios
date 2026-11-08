package eu.simplexsmp.adminsuite;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.Bukkit;
import net.kyori.adventure.text.Component;
/** Public messages only. The chat client receives 50 recent rows; staff can search a bounded archive. */
final class AppChat {
    static final int LIMIT=50, HISTORY_LIMIT=1500;
    private static final long HISTORY_AGE=3*24*60*60_000L;
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
            loadRows(p,"context.",HISTORY_LIMIT-LIMIT);loadRows(p,"row.",LIMIT);trim();
        }catch(Exception damaged){
            try{Files.move(target,target.resolveSibling(target.getFileName()+".damaged-"+System.currentTimeMillis()));}catch(IOException ignored){}
            rows.clear();
        }
    }
    private void loadRows(Properties source,String key,int limit){
        for(int i=0;i<limit;i++){
            String raw=source.getProperty(key+i);if(raw==null)break;
            if(DiscordBotManager.Json.parse(raw) instanceof Map<?,?> row)rows.addLast(new LinkedHashMap<>(AppApiManager.object(row)));
        }
    }
    private void trim(){
        long cutoff=System.currentTimeMillis()-HISTORY_AGE;
        while(rows.size()>LIMIT){
            Object at=rows.getFirst().get("at");
            if(rows.size()<=HISTORY_LIMIT && (!(at instanceof Number n)||n.longValue()>=cutoff))break;
            rows.removeFirst();
        }
    }
    private static Properties serialize(Collection<Map<String,Object>> items,long sequence,String epoch){
        Properties p=new Properties();p.setProperty("schema","1");p.setProperty("sequence",Long.toString(sequence));p.setProperty("epoch",epoch);
        int archive=Math.max(0,items.size()-LIMIT), index=0;
        for(var row:items){
            p.setProperty(index<archive?"context."+index:"row."+(index-archive),DiscordBotManager.Json.stringify(row));
            index++;
        }
        return p;
    }
    synchronized void stop(){if(writer==null)return;writer.shutdownNow();writer=null;writePending=false;persistNow();}
    synchronized void record(String uuid,String name,String text,String source){
        rows.addLast(SimplexAdminSuite.appMap("id",Long.toString(++sequence),"uuid",uuid,"name",name,"text",text,"source",source,"at",System.currentTimeMillis()));
        trim();
        schedule();
    }
    synchronized Object read(long after){return SimplexAdminSuite.appMap("items",rows.stream().skip(Math.max(0,rows.size()-LIMIT)).filter(row->Long.parseLong((String)row.get("id"))>after).toList(),"cursor",Long.toString(sequence),"epoch",epoch,"limit",LIMIT);}
    /** Removes the shared history; the cursor keeps counting so no client sees reused IDs. */
    synchronized Object clear(SimplexAdminSuite plugin,String actor){
        int removed=rows.size();rows.clear();epoch=UUID.randomUUID().toString();schedule();
        if(plugin!=null)plugin.suiteAudit(actor,"App-Chatverlauf geleert",removed+" Nachrichten");
        return SimplexAdminSuite.appMap("ok",true,"title","Chatverlauf geleert","description",removed+" Nachrichten entfernt.","epoch",epoch);
    }
    private void schedule(){
        if(writer==null||writePending)return;writePending=true;
        try{writer.schedule(()->{synchronized(AppChat.this){writePending=false;persistNow();}},8,TimeUnit.SECONDS);}catch(RejectedExecutionException stopped){writePending=false;}
    }
    private void persistNow(){
        if(file==null)return;
        trim();
        try{AtomicState.save(file,serialize(rows,sequence,epoch),"Simplex public chat and bounded search history");}
        catch(IOException e){Bukkit.getLogger().warning("App-Chatverlauf konnte nicht gespeichert werden.");}
    }
    synchronized List<Map<String, Object>> historySnapshot() { trim(); return HistoryApi.copies(rows); }
    synchronized void historyReplace(List<Map<String, Object>> items) throws IOException {
        if (file == null) throw new IOException("Chat history not attached");
        String nextEpoch = UUID.randomUUID().toString();
        AtomicState.save(file,serialize(items,sequence,nextEpoch),"Simplex public chat and bounded search history");
        rows.clear(); rows.addAll(HistoryApi.copies(items)); trim(); epoch = nextEpoch;
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
