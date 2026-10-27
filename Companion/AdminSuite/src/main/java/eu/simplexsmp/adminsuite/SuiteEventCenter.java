package eu.simplexsmp.adminsuite;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.entity.Player;

/** Bounded independent event ledger. Existing punishment/report/profile formats are untouched. */
final class SuiteEventCenter {
    static final Set<String> TYPES = Set.of("first-join","join","client-brand","client-check","ban","ban-evasion","vpn-kick","report","overload","guard","admin");
    static final Set<String> LEVELS = Set.of("active","passive","timeSensitive","critical");
    private final SimplexAdminSuite plugin;
    private final Path eventsFile, subscriptionsFile;
    private final LinkedHashMap<String,Map<String,Object>> events = new LinkedHashMap<>();
    private final Properties subscriptions = new Properties();
    private final Map<String,Long> cooldown = new HashMap<>();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(64), r -> {Thread t=new Thread(r,"SimplexEvents");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    private volatile String deliveryStatus = "Noch kein Versand";
    private int overloadSamples;
    private long lastOverload;
    SuiteEventCenter(SimplexAdminSuite plugin) throws IOException {
        this.plugin=plugin;
        Path folder=plugin.getDataFolder().toPath().resolve("app-events");
        eventsFile=folder.resolve("events.properties"); subscriptionsFile=folder.resolve("subscriptions.properties");
        Properties loaded=load(eventsFile);
        for(String id:loaded.stringPropertyNames().stream().sorted().toList()) {
            Object value=DiscordBotManager.Json.parse(loaded.getProperty(id));
            if(!(value instanceof Map<?,?> m))throw new IOException("Invalid event ledger; file preserved");
            Map<String,Object> row=new LinkedHashMap<>();m.forEach((k,v)->row.put(String.valueOf(k),v));events.put(id,row);
        }
        subscriptions.putAll(load(subscriptionsFile));
    }
    private static Properties load(Path path) throws IOException {Properties p=new Properties();if(Files.exists(path))try(Reader r=Files.newBufferedReader(path)){p.load(r);}return p;}
    void start() { Bukkit.getScheduler().runTaskTimer(plugin, this::health, 200L, 200L); }
    private void health() {
        double[] tps=Bukkit.getTPS();
        if(tps.length>0 && tps[0]>0 && tps[0]<16)overloadSamples++; else overloadSamples=0;
        if(overloadSamples>=6 && System.currentTimeMillis()-lastOverload>600_000) {
            lastOverload=System.currentTimeMillis();
            record("overload",null,"Server länger überlastet",String.format(Locale.GERMANY,"TPS seit mindestens einer Minute unter 16 (aktuell %.1f).",tps[0]),true);
        }
    }
    void record(String type, UUID player, String title, String body, boolean critical) {
        Map<String,Object> row;
        synchronized(this) {
            String id=String.format(Locale.ROOT,"%013d-%s",System.currentTimeMillis(),UUID.randomUUID());
            row=SimplexAdminSuite.appMap("id",id,"time",System.currentTimeMillis(),"type",type,"player",player==null?"":player.toString(),"title",ClientBrands.evidence(title),"body",clip(body,1500),"critical",critical,"note","");
            events.put(id,row);while(events.size()>2000)events.remove(events.keySet().iterator().next());
        }
        try { worker.execute(() -> {try {persist();push(row);}catch(Exception e){deliveryStatus="Versand oder Speicherung fehlgeschlagen";plugin.getLogger().warning("App-Ereignisse: Speicherung/Versand fehlgeschlagen ("+e.getClass().getSimpleName()+")");}}); }
        catch(RejectedExecutionException busy) {deliveryStatus="Ereigniswarteschlange ausgelastet";}
    }
    private static String clip(String text,int length) {String s=text==null?"":text.replaceAll("[\\p{Cc}&&[^\\n\\t]]","");return s.substring(0,Math.min(length,s.length()));}
    private synchronized void persist() throws IOException {
        Properties out=new Properties();events.forEach((id,row)->out.setProperty(id,DiscordBotManager.Json.stringify(row)));AtomicState.save(eventsFile,out,"Simplex event ledger v1");
    }
    synchronized Object data(String query,int offset) {
        List<Map<String,Object>> rows=new ArrayList<>(events.values());Collections.reverse(rows);
        String q=query.toLowerCase(Locale.ROOT);rows.removeIf(r->!q.isBlank()&&!DiscordBotManager.Json.stringify(r).toLowerCase(Locale.ROOT).contains(q));
        int start=Math.min(Math.max(0,offset),rows.size()),end=Math.min(start+100,rows.size());
        return SimplexAdminSuite.appMap("items",new ArrayList<>(rows.subList(start,end)),"next",end<rows.size()?end:-1,"total",rows.size(),"deliveryStatus",deliveryStatus);
    }
    synchronized Object edit(String action,String id,String note) throws IOException {
        Map<String,Map<String,Object>> before=new LinkedHashMap<>(events);
        if(action.equals("events.clear"))events.clear();
        else {
            Map<String,Object> row=events.get(id);if(row==null)throw new IllegalArgumentException("Ereignis nicht mehr vorhanden. Bitte neu laden.");
            if(action.equals("events.delete"))events.remove(id);
            else {Map<String,Object> changed=new LinkedHashMap<>(row);changed.put("note",clip(note,1000));events.put(id,changed);}
        }
        try{persist();}catch(IOException e){events.clear();events.putAll(before);throw e;}
        return SimplexAdminSuite.appMap("ok",true,"title","Protokoll aktualisiert","description","Die Änderung wurde gespeichert. Ursprüngliche Strafen und Reports bleiben erhalten.");
    }
    synchronized Object subscription(String owner) {
        String prefix=owner+".";
        return SimplexAdminSuite.appMap("enabled",Boolean.parseBoolean(subscriptions.getProperty(prefix+"enabled","false")),"configured",!subscriptions.getProperty(prefix+"key","").isBlank(),"events",subscriptions.getProperty(prefix+"events","ban-evasion,vpn-kick,overload,client-brand"),"level",subscriptions.getProperty(prefix+"level","active"),"critical",subscriptions.getProperty(prefix+"critical","critical"),"sound",subscriptions.getProperty(prefix+"sound","minuet"),"criticalSound",subscriptions.getProperty(prefix+"criticalSound","alarm"),"deliveryStatus",deliveryStatus);
    }
    synchronized Object configure(String owner,Map<String,Object> values) throws IOException {
        String prefix=owner+".";Properties next=new Properties();next.putAll(subscriptions);
        String supplied=String.valueOf(values.getOrDefault("key",""));
        if(!supplied.isBlank()) {
            if(supplied.startsWith("https://api.day.app/"))supplied=supplied.substring("https://api.day.app/".length()).replaceAll("/+$","");
            if(!supplied.matches("[A-Za-z0-9_-]{8,160}"))throw new IllegalArgumentException("Bark-Geräteschlüssel oder https://api.day.app/<Schlüssel>/ eingeben.");
            next.setProperty(prefix+"key",supplied);
        }
        String selected=String.valueOf(values.getOrDefault("events",""));
        for(String type:selected.split(","))if(!type.isBlank()&&!TYPES.contains(type))throw new IllegalArgumentException("Unbekanntes Ereignis: "+type);
        for(String field:List.of("level","critical"))if(!LEVELS.contains(String.valueOf(values.getOrDefault(field,"active"))))throw new IllegalArgumentException("Ungültige Hinweisstufe");
        for(String field:List.of("sound","criticalSound"))if(!String.valueOf(values.getOrDefault(field,"minuet")).matches("[a-zA-Z0-9_.-]{1,64}"))throw new IllegalArgumentException("Ungültiger Klangname");
        for(String field:List.of("events","level","critical","sound","criticalSound","enabled"))next.setProperty(prefix+field,String.valueOf(values.getOrDefault(field,"")));
        if(Boolean.parseBoolean(next.getProperty(prefix+"enabled"))&&next.getProperty(prefix+"key","").isBlank())throw new IllegalArgumentException("Bark-Schlüssel fehlt");
        AtomicState.save(subscriptionsFile,next,"Private Bark subscriptions. Do not share.");AppApiSecurity.protect(subscriptionsFile);
        subscriptions.clear();subscriptions.putAll(next);
        return SimplexAdminSuite.appMap("ok",true,"title","Benachrichtigungen gespeichert","description","Ereignisse werden vom Minecraft-Plugin an Bark gesendet, auch wenn die Panel-App geschlossen ist.");
    }
    private void push(Map<String,Object> row) throws Exception {
        Properties copy=new Properties();synchronized(this){copy.putAll(subscriptions);}
        for(String field:copy.stringPropertyNames())if(field.endsWith(".enabled")&&Boolean.parseBoolean(copy.getProperty(field))) {
            String prefix=field.substring(0,field.length()-"enabled".length());String owner=prefix.substring(0,prefix.length()-1);
            if(plugin.appApi==null||plugin.appApi.security.keys().stream().noneMatch(k->k.id().equals(owner)&&k.scope().equals("full")))continue;
            String type=String.valueOf(row.get("type"));if(!Arrays.asList(copy.getProperty(prefix+"events","").split(",")).contains(type))continue;
            long now=System.currentTimeMillis();String rate=prefix+type;
            if(now-cooldown.getOrDefault(rate,0L)<(type.equals("first-join")?3000:30_000))continue;
            cooldown.put(rate,now);boolean critical=Boolean.TRUE.equals(row.get("critical"));
            Map<String,Object> payload=SimplexAdminSuite.appMap("device_key",copy.getProperty(prefix+"key",""),"title",row.get("title"),"body",row.get("body"),"group","Simplex Admin Suite","level",copy.getProperty(prefix+(critical?"critical":"level"),"active"),"sound",copy.getProperty(prefix+(critical?"criticalSound":"sound"),"minuet"),"isArchive",1,"url","panel://suite-events");
            HttpRequest request=HttpRequest.newBuilder(URI.create("https://api.day.app/push")).timeout(Duration.ofSeconds(8)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(DiscordBotManager.Json.stringify(payload))).build();
            HttpResponse<InputStream> response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());
            Object reply;try(InputStream input=response.body()){byte[] bytes=input.readNBytes(16385);if(bytes.length>16384)throw new IOException("Oversize Bark reply");reply=DiscordBotManager.Json.parse(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));}
            if(response.statusCode()!=200||!(reply instanceof Map<?,?> m)||!(m.get("code") instanceof Number n)||n.intValue()!=200)throw new IOException("Bark rejected push");
            deliveryStatus="Letzter Versand erfolgreich";
        }
    }
    void stop() {try{persist();}catch(IOException e){plugin.getLogger().warning("App event checkpoint failed");}worker.shutdownNow();}
}
