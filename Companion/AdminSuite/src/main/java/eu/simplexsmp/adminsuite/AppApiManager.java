package eu.simplexsmp.adminsuite;

import com.sun.net.httpserver.*;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import javax.net.ssl.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Optional isolated HTTPS transport. Credentials and existing integrations never pass through it. */
final class AppApiManager {
    private final SimplexAdminSuite plugin;
    private final String serverName;
    final AppApiSecurity security;
    private final DiscordAdminController controller;
    final AppApiMenus menus;
    private final Map<String,Map<String,Object>> actions=new LinkedHashMap<>();
    private final Map<String,long[]> rates=new HashMap<>();
    private final DatabaseInspector databaseInspector=new DatabaseInspector();
    private final AtomicLong generation=new AtomicLong();
    private volatile HttpServer server;
    private volatile ExecutorService requests;
    private volatile String status="Ausgeschaltet",fingerprint="";
    private final ExecutorService setup=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"SimplexAppSetup");t.setDaemon(true);return t;});
    AppApiManager(SimplexAdminSuite plugin)throws Exception{
        this.plugin=plugin;serverName=Bukkit.getServer().getName();security=new AppApiSecurity(plugin.getDataFolder().toPath().resolve("app-api"));controller=new DiscordAdminController(plugin,plugin.discordRelay());menus=new AppApiMenus(plugin,security);
        for(Object command:controller.guildCommands())if(command instanceof Map<?,?> c&&"sasadmin".equals(c.get("name")))flatten("",c.get("options"));
        extra("menu.action","Suite-Menü bedienen","Alle Suite-Menüs",List.of(field("session","Menüsitzung",3,true),field("revision","Menüstand",3,true),field("option","Menüpunkt",3,true),field("click","Mausaktion",3,false),field("input","Eingabe",3,false)));
        extra("settings.update","Suite-Einstellung speichern","Einstellungen",List.of(field("key","Einstellung",3,true),field("value","Neuer Wert; leer = leeren",3,false),field("expected","Geladener Stand",3,true)));
        extra("player.vitals","Spielerwerte live ändern","Spieler",List.of(field("player","Spielername oder UUID",3,true),choice("field","Wert","health","food","saturation","level","exp","gamemode","invulnerable","allowFlight"),field("value","Neuer Wert",3,true)));
        extra("world.update","Welteinstellung ändern","Welten",List.of(field("world","Weltname oder UUID",3,true),choice("field","Einstellung","time","weather","difficulty","pvp","autosave"),field("value","Neuer Wert",3,true)));
        extra("logs.retry","Private Discord-Protokolle erneut versuchen","Server",List.of());
        extra("suite.command","Weitere Suite-Befehle","Werkzeuge",List.of(choice("root","Suite-Befehl","sas","delivery","shop","spcurrency","connection"),field("command","Argumente ohne /sas",3,true)));
    }
    @SuppressWarnings("unchecked") private void flatten(String prefix,Object options){
        if(!(options instanceof List<?> list))return;
        for(Object entry:list)if(entry instanceof Map<?,?> raw){Map<String,Object> item=(Map<String,Object>)raw;String path=prefix+item.get("name");
            if(((Number)item.getOrDefault("type",0)).intValue()==2)flatten(path+".",item.get("options"));
            else if(((Number)item.getOrDefault("type",0)).intValue()==1){Map<String,Object> row=new LinkedHashMap<>(item);row.put("id",path);row.put("group",group(path));row.put("scope",scope(path));actions.put(path,row);}
        }
    }
    private static String group(String path){return switch(path.split("\\.")[0]){case "player"->"Spieler";case "server"->"Server";case "security","client"->"Sicherheit";case "moderation"->"Moderation";case "settings"->"Einstellungen";case "ai"->"KI & Integrationen";default->"Übersicht";};}
    private void extra(String id,String description,String group,List<?> options){actions.put(id,new LinkedHashMap<>(SimplexAdminSuite.appMap("id",id,"name",description,"description",description,"group",group,"scope",scope(id),"options",options)));}
    private static Map<String,Object> field(String id,String label,int type,boolean required){return SimplexAdminSuite.appMap("name",id,"description",label,"type",type,"required",required);}
    private static Map<String,Object> choice(String id,String label,String... values){var f=field(id,label,3,true);f.put("choices",Arrays.stream(values).map(v->SimplexAdminSuite.appMap("name",switch(v){case "health"->"Gesundheit";case "food"->"Hunger";case "saturation"->"Sättigung";case "level"->"Erfahrungslevel";case "exp"->"XP-Anteil";case "gamemode"->"Spielmodus";case "invulnerable"->"Unverwundbarkeit";case "allowFlight"->"Flug erlauben";default->v;},"value",v)).toList());return f;}
    static String scope(String path){return Set.of("world.update","logs.retry","settings.update","menu.action","server.console","server.suite","suite.command","server.reload","server.trim","security.identity-reset","settings.set","moderation.config","moderation.logchannel","moderation.log").contains(path)?"full":"manage";}
    void reload(){
        stopListener();menus.clear();long rev=generation.get();
        if(!plugin.getConfig().getBoolean("app-api.enabled",false)){status="Ausgeschaltet";return;}
        String bind=plugin.getConfig().getString("app-api.bind","0.0.0.0"),transport=plugin.getConfig().getString("app-api.transport","tls");int port=plugin.getConfig().getInt("app-api.port",39093);
        status="HTTPS wird vorbereitet …";
        setup.submit(()->{HttpServer candidate=null;ExecutorService pool=null;try{
            if(port<1024||port>65535)throw new IllegalArgumentException("Port muss zwischen 1024 und 65535 liegen");
            InetAddress address=InetAddress.getByName(bind);
            if(transport.equals("proxy")){
                if(!address.isLoopbackAddress())throw new IllegalArgumentException("Unverschlüsseltes Proxy-Backend muss an 127.0.0.1 oder ::1 gebunden sein");
                candidate=HttpServer.create(new InetSocketAddress(address,port),16);fingerprint="";
            }else if(transport.equals("tls")){
                HttpsServer tls=HttpsServer.create(new InetSocketAddress(address,port),16);tls.setHttpsConfigurator(new HttpsConfigurator(tlsContext()));candidate=tls;
            }else throw new IllegalArgumentException("Transport muss tls oder proxy sein");
            pool=new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),r->{Thread t=new Thread(r,"SimplexAppHTTP");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
            candidate.setExecutor(pool);candidate.createContext("/",x->handle(x,rev));
            synchronized(this){if(generation.get()!=rev){candidate.stop(0);pool.shutdownNow();return;}server=candidate;requests=pool;candidate.start();status=(transport.equals("tls")?"HTTPS":"HTTPS-Proxy-Backend")+" · "+bind+":"+port;}
            plugin.getLogger().info("Optionale Simplex-Panel-API aktiv: "+status+". Schlüssel nur mit /sas app create erzeugen.");
        }catch(Exception e){if(candidate!=null)candidate.stop(0);if(pool!=null)pool.shutdownNow();if(generation.get()==rev){status="Nicht aktiv: "+e.getMessage();plugin.getLogger().warning("App-API: "+status);}}});
    }
    private SSLContext tlsContext()throws Exception{
        Path dir=security.directory,store=dir.resolve("server.p12"),passwordFile=dir.resolve("tls-password.txt");String password;
        if(Files.exists(store)){if(!Files.exists(passwordFile))throw new IllegalArgumentException("TLS-Passwort fehlt; vorhandenes Zertifikat bleibt erhalten");password=Files.readString(passwordFile).trim();}
        else{
            if(Files.exists(passwordFile))password=Files.readString(passwordFile).trim();else{password=UUID.randomUUID()+UUID.randomUUID().toString();Files.writeString(passwordFile,password,StandardOpenOption.CREATE_NEW);AppApiSecurity.protect(passwordFile);}
            Path executable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")?"keytool.exe":"keytool");
            if(!Files.isExecutable(executable))throw new IllegalArgumentException("Java-keytool fehlt. HTTPS-Proxy-Modus oder vollständiges JDK verwenden");
            ProcessBuilder pb=new ProcessBuilder(executable.toString(),"-genkeypair","-alias","simplex-panel","-keyalg","EC","-groupname","secp256r1","-validity","3650","-dname","CN=Simplex Panel API","-storetype","PKCS12","-keystore",store.toString(),"-storepass:env","SIMPLEX_TLS_PASSWORD","-keypass:env","SIMPLEX_TLS_PASSWORD");
            pb.environment().put("SIMPLEX_TLS_PASSWORD",password);pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);pb.redirectError(ProcessBuilder.Redirect.DISCARD);Process process=pb.start();
            if(!process.waitFor(25,TimeUnit.SECONDS)){process.destroyForcibly();throw new IllegalArgumentException("Zertifikaterstellung hat zu lange gedauert");}
            if(process.exitValue()!=0)throw new IllegalArgumentException("TLS-Zertifikat konnte nicht erzeugt werden");AppApiSecurity.protect(store);
        }
        KeyStore keyStore=KeyStore.getInstance("PKCS12");try(var input=Files.newInputStream(store)){keyStore.load(input,password.toCharArray());}
        fingerprint=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(keyStore.getCertificate("simplex-panel").getEncoded()));
        KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(keyStore,password.toCharArray());SSLContext ssl=SSLContext.getInstance("TLS");ssl.init(km.getKeyManagers(),null,new SecureRandom());return ssl;
    }
    synchronized void stopListener(){generation.incrementAndGet();security.clearTickets();HttpServer old=server;server=null;if(old!=null)old.stop(0);if(requests!=null)requests.shutdownNow();requests=null;}
    void stop(){stopListener();menus.clear();setup.shutdownNow();}
    boolean active(){return server!=null && plugin.isEnabled();}
    boolean remote(Object player){return active()&&menus.remote(player);}
    private boolean rate(String id,int max){synchronized(rates){long now=System.currentTimeMillis();rates.values().removeIf(v->now-v[0]>60_000);if(rates.size()>1024&&!rates.containsKey(id))return false;long[] v=rates.computeIfAbsent(id,k->new long[]{now,0});return ++v[1]<=max;}}
    private void handle(HttpExchange x,long rev){
        try{
            if(!rate("ip:"+x.getRemoteAddress().getAddress().getHostAddress(),240))throw new ApiError(429,"Zu viele Anfragen. Bitte kurz warten.");
            if(x.getRequestHeaders().containsKey("Origin"))throw new ApiError(403,"Browserzugriff ist nicht freigegeben.");
            String header=x.getRequestHeaders().getFirst("Authorization");AppApiSecurity.Key key=security.authenticate(header!=null&&header.startsWith("Bearer ")?header.substring(7):null);
            if(key==null)throw new ApiError(401,"API-Schlüssel ungültig oder widerrufen.");
            if(!rate("key:"+key.id(),180))throw new ApiError(429,"Schlüssel-Anfragelimit erreicht.");
            String path=x.getRequestURI().getPath(),method=x.getRequestMethod();Map<String,String> query=query(x.getRequestURI());Object result;
            if(method.equals("GET")){
                result=switch(path){
                    case "/v1/health"->SimplexAdminSuite.appMap("serverId",security.serverId(),"name",serverName,"version",plugin.getDescription().getVersion(),"features",List.of("overview","statistics","worlds","plugins","log-status","database-inspector"),"apiVersion",1,"scope",key.scope(),"keyLabel",key.label(),"time",System.currentTimeMillis());
                    case "/v1/menu"->onMain(rev,()->{if(!key.scope().equals("full"))throw new ApiError(403,"Suite-Menüs benötigen einen full-Schlüssel.");return menus.current(key);});
                    case "/v1/schema"->SimplexAdminSuite.appMap("actions",new ArrayList<>(actions.values()));
                    case "/v1/view"->onMain(rev,()->{
                        DiscordAdminController.Request request=request(query.getOrDefault("path","dashboard"),new LinkedHashMap<>(query));
                        if(controller.mutation(request)||Set.of("ai.ask","ai.propose","server.suite","world.update","logs.retry").contains(request.path))throw new ApiError(405,"Diese Aktion benötigt Vorschau und Bestätigung.");
                        return reply(controller.execute(request,actor(key)));
                    });
                    case "/v1/choices"->onMain(rev,()->controller.autocomplete(new DiscordAdminController.Request(query.getOrDefault("path",""),new LinkedHashMap<>(query)),query.getOrDefault("field",""),query.getOrDefault("q","")).stream().map(c->SimplexAdminSuite.appMap("name",c.name,"value",c.value)).toList());
                    case "/v1/data/overview","/v1/data/statistics","/v1/data/worlds","/v1/data/plugins","/v1/data/log-status","/v1/data/players","/v1/data/profile","/v1/data/alts","/v1/data/devices","/v1/data/ultrabans","/v1/data/snapshots","/v1/data/nbt-paths","/v1/data/settings","/v1/data/inventory","/v1/data/snapshot-content"->onMain(rev,()->{Object data=plugin.appApiData(path.substring("/v1/data/".length()),query);if(path.endsWith("/settings")){Map<String,Object> envelope=object(data);for(Object entry:(List<?>)envelope.get("items")){Map<String,Object> item=object(entry);item.put("revision",AppApiSecurity.hash(DiscordBotManager.Json.stringify(item.get("value"))));}}return data;});
                    default->throw new ApiError(404,"API-Endpunkt nicht gefunden.");
                };
            }else if(method.equals("POST")&&path.equals("/v1/database/inspect")){
                if(!key.scope().equals("full"))throw new ApiError(403,"Der Datenbankscanner benötigt einen full-Schlüssel.");
                if(!rate("db:"+key.id(),6))throw new ApiError(429,"Maximal sechs Datenbankscans pro Minute.");
                String type=x.getRequestHeaders().getFirst("Content-Type");if(type==null||!type.toLowerCase(Locale.ROOT).startsWith("application/json"))throw new ApiError(415,"application/json erforderlich");
                byte[] bytes=x.getRequestBody().readNBytes(16385);if(bytes.length>16384)throw new ApiError(413,"Anfrage zu groß");
                String text=new String(bytes,StandardCharsets.UTF_8);AppApiSecurity.checkJson(text);
                // Remains on the bounded HTTP worker pool, never Minecraft's main thread.
                result=databaseInspector.scan(object(DiscordBotManager.Json.parse(text)));
            }else if(method.equals("POST")&&(path.equals("/v1/prepare")||path.equals("/v1/execute"))){
                String contentType=x.getRequestHeaders().getFirst("Content-Type");if(contentType==null||!contentType.toLowerCase(Locale.ROOT).startsWith("application/json"))throw new ApiError(415,"application/json erforderlich");
                byte[] body=x.getRequestBody().readNBytes(65537);if(body.length>65536)throw new ApiError(413,"Anfrage zu groß");String text=new String(body,StandardCharsets.UTF_8);AppApiSecurity.checkJson(text);Map<String,Object> payload=object(DiscordBotManager.Json.parse(text));
                if(path.equals("/v1/prepare")){
                    String action=String.valueOf(payload.getOrDefault("path",""));Map<String,Object> values=object(payload.get("values"));
                    DiscordAdminController.Request req=request(action,values);authorize(key,req);
                    String encoded=DiscordBotManager.Json.stringify(SimplexAdminSuite.appMap("path",action,"values",values));
                    String summary=onMain(rev,()->{if(req.path.equals("world.update"))return "Welt: "+req.text("world")+"\nEinstellung: "+req.text("field")+"\nNeuer Wert: "+req.text("value");if(req.path.equals("logs.retry"))return "Gespeicherte private Discord-Protokolle erneut versuchen. Keine Einträge werden gelöscht; die Prüfung privater Kanalrechte bleibt aktiv.";if(req.path.equals("settings.update")){checkSetting(req);return "**Einstellung:** "+req.text("key")+"\n**Neuer Wert:** "+(req.text("value").isEmpty()?"(leer)":req.text("value"))+"\nDer geladene Stand wird vor dem Speichern erneut geprüft.";}return controller.confirmationSummary(req);});
                    result=SimplexAdminSuite.appMap("ticket",security.prepare(key,encoded,System.currentTimeMillis()),"summary",summary,"expiresIn",60);
                }else{
                    String stored=security.consume(key,String.valueOf(payload.getOrDefault("ticket","")),System.currentTimeMillis());Map<String,Object> plan=object(DiscordBotManager.Json.parse(stored));DiscordAdminController.Request req=request(String.valueOf(plan.get("path")),object(plan.get("values")));authorize(key,req);
                    if(req.path.equals("ai.ask")){
                        CompletableFuture<String> future=onMain(rev,()->plugin.discordAdminAiAsk(req.text("question")));String response=future.get(90,TimeUnit.SECONDS);result=SimplexAdminSuite.appMap("ok",true,"title","Simplex KI","description",response);
                    }else if(req.path.equals("ai.propose")){
                        CompletableFuture<DiscordAdminController.Request> future=onMain(rev,()->plugin.discordAdminAiProposalAsync(actor(key).auditName(),req.text("request")));var proposed=future.get(90,TimeUnit.SECONDS);
                        result=SimplexAdminSuite.appMap("ok",true,"title","KI-Vorschlag · noch nicht angewendet","description",onMain(rev,()->controller.confirmationSummary(proposed)),"proposal",SimplexAdminSuite.appMap("path",proposed.path,"values",proposed.values));
                    }else result=onMain(rev,()->{
                        authorize(key,req);
                        if(req.path.equals("settings.update")){checkSetting(req);plugin.asAdmin(actor(key).auditName(),()->plugin.moderationControl.appConfigSet(Bukkit.getConsoleSender(),req.text("key"),req.text("value")));plugin.expansionAudit(actor(key).auditName(),null,"App-Einstellung",req.text("key"));return SimplexAdminSuite.appMap("ok",true,"title","Einstellung gespeichert","description",req.text("key")+" gespeichert. Moduländerungen bei Bedarf mit Suite-Reload anwenden.");}
                        if(req.path.equals("menu.action"))return menus.perform(key,req.values);
                        if(Set.of("suite.command","player.vitals","world.update","logs.retry").contains(req.path)){Object[] out={null};plugin.asAdmin(actor(key).auditName(),()->out[0]=plugin.appApiMutation(req.path,req.values,actor(key).auditName()));return out[0];}
                        plugin.expansionAudit(actor(key).auditName(),null,"App-API-Aktion",req.path);return reply(controller.execute(req,actor(key)));
                    });
                }
            }else throw new ApiError(405,"Methode nicht erlaubt.");
            send(x,200,result);
        }catch(Throwable error){Throwable e=error;while(e instanceof ExecutionException&&e.getCause()!=null)e=e.getCause();int code=e instanceof ApiError a?a.code:e instanceof IllegalArgumentException?400:e instanceof TimeoutException?504:503;
            String message=e instanceof ApiError||e instanceof IllegalArgumentException?e.getMessage():e instanceof TimeoutException?"Serverantwort dauert zu lange. Aktion nicht automatisch wiederholen; aktuellen Zustand prüfen.":"App-API momentan nicht verfügbar. Serverprotokoll und API-Status prüfen.";
            try{send(x,code,SimplexAdminSuite.appMap("message",message));}catch(Exception ignored){}
        }finally{x.close();}
    }
    private void checkSetting(DiscordAdminController.Request req){
        String key=req.text("key");
        if(key.startsWith("app-api.")||key.startsWith("admin-security.")||key.startsWith("console.")||ModerationControl.protectedKey(key)||plugin.moderationControl==null||!plugin.moderationControl.configKeys().contains(key))throw new ApiError(403,"Geschützte oder unbekannte Einstellung");
        String current=AppApiSecurity.hash(DiscordBotManager.Json.stringify(plugin.getConfig().get(key)));
        if(!current.equals(req.text("expected")))throw new ApiError(409,"Einstellung wurde zwischenzeitlich geändert. Neu laden und den Wert prüfen.");
    }
    private DiscordAdminController.Actor actor(AppApiSecurity.Key key){return new DiscordAdminController.Actor("app:"+key.id(),"Simplex Panel/"+key.label(),key.scope().equals("full"),"");}
    private void authorize(AppApiSecurity.Key key,DiscordAdminController.Request request){
        if(!security.keys().contains(key))throw new ApiError(401,"API-Schlüssel wurde widerrufen.");
        String required=scope(request.path);if(!AppApiSecurity.permitted(key,required))throw new ApiError(403,"Keine Berechtigung. Benötigtes Schlüsselprofil: "+required);
        if(request.path.equals("moderation.config")){String field=request.text("key");if(field.startsWith("app-api.")||field.startsWith("admin-security.")||ModerationControl.protectedKey(field))throw new ApiError(403,"Geschützte Zugangseinstellung; bitte lokal bearbeiten.");}
    }
    @SuppressWarnings("unchecked") private DiscordAdminController.Request request(String path,Map<String,Object> values){
        if(!actions.containsKey(path)&&!Set.of("dashboard","help","settings.list").contains(path)&&!path.matches("menu\\.(players|moderation|server|worlds|security|communication|community|analytics|qol|integrations|operations)"))throw new ApiError(404,"Aktion wird von dieser Suite-Version nicht unterstützt.");
        if(values.size()>32)throw new IllegalArgumentException("Zu viele Felder");
        for(var entry:values.entrySet()){
            if(entry.getKey().startsWith("_")||entry.getValue() instanceof Map<?,?>||entry.getValue() instanceof List<?>)throw new IllegalArgumentException("Ungültiges Aktionsfeld");
            String value=String.valueOf(entry.getValue());if(value.length()>4000||value.chars().anyMatch(c->c<32&&c!='\t'))throw new IllegalArgumentException("Feld enthält ungültige Zeichen oder ist zu lang");
        }
        Map<String,Object> schema=actions.get(path);if(schema!=null&&schema.get("options") instanceof List<?> fields)for(Object raw:fields){Map<String,Object> f=(Map<String,Object>)raw;String name=String.valueOf(f.get("name"));Object val=values.get(name);
            if(Boolean.TRUE.equals(f.get("required"))&&(val==null||String.valueOf(val).isBlank()))throw new IllegalArgumentException("Pflichtfeld fehlt: "+name);
            if(val!=null&&!String.valueOf(val).isBlank()){
                if(f.get("choices") instanceof List<?> choices&&choices.stream().noneMatch(c->c instanceof Map<?,?> m&&String.valueOf(m.get("value")).equals(String.valueOf(val))))throw new IllegalArgumentException("Ungültige Auswahl: "+name);
                if(f.get("type") instanceof Number type&&type.intValue()==4){double n=Double.parseDouble(String.valueOf(val));if(!Double.isFinite(n)||n!=(long)n||f.get("min_value") instanceof Number min&&n<min.doubleValue()||f.get("max_value") instanceof Number max&&n>max.doubleValue())throw new IllegalArgumentException("Zahl außerhalb des erlaubten Bereichs: "+name);}
            }
        }
        return new DiscordAdminController.Request(path,values);
    }
    private <T>T onMain(long rev,Callable<T> work)throws Exception{
        CompletableFuture<T> future=new CompletableFuture<>();AtomicInteger state=new AtomicInteger(0);
        Bukkit.getScheduler().runTask(plugin,()->{if(!state.compareAndSet(0,1))return;try{if(generation.get()!=rev||!plugin.isEnabled())throw new ApiError(503,"API wurde neu geladen");future.complete(work.call());}catch(Throwable e){future.completeExceptionally(e);}finally{state.set(2);}});
        try{return future.get(15,TimeUnit.SECONDS);}catch(TimeoutException e){state.compareAndSet(0,3);throw e;}
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value){if(!(value instanceof Map<?,?>))throw new IllegalArgumentException("JSON-Objekt erforderlich");return (Map<String,Object>)value;}
    private static Map<String,Object> reply(DiscordAdminController.Reply r){return SimplexAdminSuite.appMap("ok",r.color!=0xED4245,"title",r.title,"description",r.description,"components",r.components);}
    private static Map<String,String> query(URI uri){Map<String,String> m=new LinkedHashMap<>();if(uri.getRawQuery()!=null){if(uri.getRawQuery().length()>8192)throw new IllegalArgumentException("Suchanfrage zu lang");for(String pair:uri.getRawQuery().split("&")){String[] parts=pair.split("=",2);m.put(URLDecoder.decode(parts[0],StandardCharsets.UTF_8),parts.length>1?URLDecoder.decode(parts[1],StandardCharsets.UTF_8):"");}}return m;}
    private static void send(HttpExchange x,int code,Object value)throws Exception{byte[] body=DiscordBotManager.Json.stringify(value).getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");x.getResponseHeaders().set("Cache-Control","no-store");x.getResponseHeaders().set("X-Content-Type-Options","nosniff");x.sendResponseHeaders(code,body.length);try(var out=x.getResponseBody()){out.write(body);}}
    private static final class ApiError extends RuntimeException{final int code;ApiError(int code,String message){super(message);this.code=code;}}
    boolean command(CommandSender sender,String[] args){
        if(remote(sender)||!plugin.adminAccess.master(sender)){sender.sendMessage("§cApp-API-Zugänge dürfen nur echte Server-Operatoren/Hauptadmins oder die Serverkonsole verwalten.");return true;}
        if(args.length==0&&sender instanceof Player p){menu(p);return true;}
        try{String action=args.length==0?"status":args[0].toLowerCase(Locale.ROOT);switch(action){
            case "enable"->{int port=args.length>1?Integer.parseInt(args[1]):39093;if(port<1024||port>65535)throw new IllegalArgumentException("Port 1024–65535 erforderlich");plugin.getConfig().set("app-api.enabled",true);plugin.getConfig().set("app-api.port",port);plugin.saveConfig();reload();sender.sendMessage("§aAPI wird vorbereitet. /sas app status danach prüfen. Eigenen TCP-Port beim Hoster zuweisen/freigeben.");}
            case "disable"->{plugin.getConfig().set("app-api.enabled",false);plugin.saveConfig();stopListener();menus.clear();status="Ausgeschaltet";sender.sendMessage("§aApp-API ausgeschaltet. Bestehende Suite-Daten bleiben erhalten.");}
            case "reload"->{plugin.reloadConfig();reload();sender.sendMessage("§aApp-API wird neu geladen. Kein Serverneustart nötig.");}
            case "create"->{if(args.length<2)throw new IllegalArgumentException("/sas app create <Name-ohne-Leerzeichen> [read|manage|full]");var issued=security.issue(args[1],args.length>2?args[2]:"read");sender.sendMessage("§eEinmaliger API-Schlüssel für "+issued.key().label()+" ("+issued.key().scope()+"). Nicht teilen:");sender.sendMessage(issued.token());sender.sendMessage("§7Widerrufen: /sas app revoke "+issued.key().id());plugin.expansionAudit(sender.getName(),null,"App-Schlüssel erzeugt",issued.key().id()+" · "+issued.key().scope());}
            case "keys"->{for(var key:security.keys())sender.sendMessage(key.id()+" · "+key.label()+" · "+key.scope());}
            case "revoke"->{if(args.length<2)throw new IllegalArgumentException("/sas app revoke <Schlüssel-ID>");security.revoke(args[1]);sender.sendMessage("§aSchlüssel widerrufen.");plugin.expansionAudit(sender.getName(),null,"App-Schlüssel widerrufen",args[1]);}
            case "fingerprint"->sender.sendMessage("SHA-256: "+(fingerprint.isBlank()?"Noch kein aktives TLS-Zertifikat":fingerprint));
            default->{sender.sendMessage("§bSimplex Panel API · "+status);sender.sendMessage("§7/sas app enable <Port> | disable | create <Name> [read|manage|full] | keys | revoke <ID> | fingerprint | reload");if(!fingerprint.isBlank())sender.sendMessage("SHA-256: "+fingerprint);}
        }}catch(Exception e){sender.sendMessage("§cApp-API: "+e.getMessage());}return true;
    }
    void menu(Player p){
        if(remote(p)||!plugin.adminAccess.master(p)){plugin.menuError(p,"§cNur Hauptadmins/Operatoren.");return;}
        List<AdminUpdateMenu.Button> rows=new ArrayList<>();rows.add(AdminUpdateMenu.info("App-API",status,Material.COMPARATOR));
        rows.add(new AdminUpdateMenu.Button("Aktivieren / Port ändern","Optionaler zusätzlicher TCP-Port; HTTPS-Zertifikat wird automatisch erstellt",Material.TRIPWIRE_HOOK,()->plugin.adminUpdate.prompt(p,"TCP-Port für Simplex Panel",Integer.toString(plugin.getConfig().getInt("app-api.port",39093)),v->{command(p,new String[]{"enable",v});menu(p);})));
        for(String scope:List.of("read","manage","full"))rows.add(new AdminUpdateMenu.Button("Schlüssel erstellen · "+scope,scope.equals("read")?"Nur lesen":scope.equals("manage")?"Spieler und Moderation verwalten":"Voller Adminzugriff inklusive Suite-Konfiguration",Material.PAPER,()->plugin.adminUpdate.prompt(p,"Name für den App-Zugang","Mein iPhone",name->{command(p,new String[]{"create",name,scope});})));
        rows.add(new AdminUpdateMenu.Button("Zertifikatsfingerabdruck anzeigen","SHA-256 in die App kopieren",Material.NAME_TAG,()->command(p,new String[]{"fingerprint"})));
        for(var key:security.keys())rows.add(new AdminUpdateMenu.Button("Widerrufen: "+key.label(),key.scope()+" · "+key.id()+" · mit Eingabe bestätigen",Material.BARRIER,()->plugin.adminUpdate.prompt(p,"Zum Widerrufen JA eingeben","",v->{if(v.equals("JA"))command(p,new String[]{"revoke",key.id()});menu(p);})));
        rows.add(new AdminUpdateMenu.Button("API ausschalten","Kopplung pausieren; Daten bleiben erhalten",Material.REDSTONE_BLOCK,()->{command(p,new String[]{"disable"});menu(p);}));
        rows.add(new AdminUpdateMenu.Button("Zurück","Admin-Zentrale",Material.OAK_DOOR,()->plugin.adminUpdate.open(p)));plugin.adminUpdate.show(p,"Simplex Panel verbinden",rows,0);
    }
}
