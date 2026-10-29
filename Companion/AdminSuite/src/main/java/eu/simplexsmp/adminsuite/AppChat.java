package eu.simplexsmp.adminsuite;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.Bukkit;
import net.kyori.adventure.text.Component;
/** Public messages only: the last 50 rows survive app and server restarts until staff clear them. */
final class AppChat {
    static final int LIMIT=50;
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
            for(int i=0;i<LIMIT;i++){String raw=p.getProperty("row."+i);if(raw==null)break;if(DiscordBotManager.Json.parse(raw) instanceof Map<?,?> m){Map<String,Object> row=new LinkedHashMap<>();m.forEach((k,v)->row.put(String.valueOf(k),v));rows.addLast(row);}}
        }catch(Exception damaged){
            try{Files.move(target,target.resolveSibling(target.getFileName()+".damaged-"+System.currentTimeMillis()));}catch(IOException ignored){}
            rows.clear();
        }
    }
    synchronized void stop(){if(writer==null)return;writer.shutdownNow();writer=null;writePending=false;persistNow();}
    synchronized void record(String uuid,String name,String text,String source){
        rows.addLast(SimplexAdminSuite.appMap("id",Long.toString(++sequence),"uuid",uuid,"name",name,"text",text,"source",source,"at",System.currentTimeMillis()));
        while(rows.size()>LIMIT)rows.removeFirst();
        schedule();
    }
    synchronized Object read(long after){return SimplexAdminSuite.appMap("items",rows.stream().filter(row->Long.parseLong((String)row.get("id"))>after).toList(),"cursor",Long.toString(sequence),"epoch",epoch,"limit",LIMIT);}
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
        int i=0;for(Map<String,Object> row:rows)p.setProperty("row."+(i++),DiscordBotManager.Json.stringify(row));
        try{AtomicState.save(file,p,"Simplex public app chat history (last "+LIMIT+" public messages)");}catch(IOException e){Bukkit.getLogger().warning("App-Chatverlauf konnte nicht gespeichert werden.");}
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
