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
    private AppPluginFiles pluginFiles;
    private synchronized AppPluginFiles pluginFiles() throws Exception { if(pluginFiles==null)pluginFiles=new AppPluginFiles(plugin.getDataFolder().toPath().toAbsolutePath().getParent());return pluginFiles; }
    final AppApiMenus menus;
    private final Map<String,Map<String,Object>> actions=new LinkedHashMap<>();
    private final Map<String,long[]> rates=new HashMap<>();
    private final PlayerNbtAccess playerNbt;
    private final DatabaseInspector databaseInspector=new DatabaseInspector();
    private final AtomicLong generation=new AtomicLong();
    private volatile HttpServer server;
    private volatile ExecutorService requests;
    private volatile String status="Ausgeschaltet",fingerprint="";
    private final ExecutorService setup=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"SimplexAppSetup");t.setDaemon(true);return t;});
    AppApiManager(SimplexAdminSuite plugin)throws Exception{
        this.plugin=plugin;playerNbt=new PlayerNbtAccess(plugin);serverName=Bukkit.getServer().getName();security=new AppApiSecurity(plugin.getDataFolder().toPath().resolve("app-api"));controller=new DiscordAdminController(plugin,plugin.discordRelay());menus=new AppApiMenus(plugin,security);
        for(Object command:controller.guildCommands())if(command instanceof Map<?,?> c&&"sasadmin".equals(c.get("name")))flatten("",c.get("options"));
        extra("restart.apply","Serverneustart planen oder abbrechen","Server",List.of(choice("operation","Aktion","schedule","cancel"),field("seconds","Sekunden (1–60)",4,false),field("reasonDE","Grund Deutsch",3,false),field("reasonEN","Grund Englisch",3,false)));
        extra("management.apply","Suite-Verwaltung ändern","Verwaltung",List.of(field("operation","Aktion",3,true),field("player","Spieler / Gruppe",3,false),field("node","Recht / Brand",3,false),field("value","Neuer Wert",3,false),field("reason","Grund",3,false),field("maxBanSeconds","Maximale Banndauer (Sekunden)",3,false),field("altBans","Alt-Account-Banns erlauben",3,false)));
        extra("vpn.whitelist","VPN-Ausnahme ändern","Sicherheit",List.of(field("player","Spielername oder UUID",3,true),choice("allowed","VPN-Ausnahme","true","false")));
        extra("bans.remove","Ausgewählte Sperre aufheben","Moderation",List.of(choice("source","Bannquelle","internal","client","minecraft-profile","minecraft-ip","litebans"),field("id","Eintrag / Spieler-UUID",3,true),field("expected","Geladener Stand",3,false)));
        extra("reports.update","Report bearbeiten","Moderation",List.of(field("id","Report-ID",3,true),choice("status","Status","OPEN","IN_REVIEW","RESOLVED","REJECTED"),field("note","Interne Notiz",3,false),field("expected","Geladener Stand",3,true)));
        extra("events.ack","Alarm bestätigen","Benachrichtigungen",List.of(field("id","Ereignis-ID",3,true)));
        extra("events.delete","Ereignis löschen","Protokolle",List.of(field("id","Ereignis-ID",3,true)));
        extra("events.note","Ereignisnotiz bearbeiten","Protokolle",List.of(field("id","Ereignis-ID",3,true),field("note","Notiz",3,false)));
        extra("events.clear","Ereignisprotokoll leeren","Protokolle",List.of());
        extra("notifications.update","Bark-Benachrichtigungen speichern","Benachrichtigungen",List.of(field("key","Bark-Schlüssel (leer = behalten)",3,false),field("events","Ereignisarten",3,false),choice("enabled","Aktiv","true","false"),choice("level","Normale Hinweise","active","passive","timeSensitive","critical"),choice("critical","Kritische Ereignisse","active","passive","timeSensitive","critical"),field("sound","Normaler Klang",3,true),field("criticalSound","Alarmklang",3,true),field("rules","Ereignisregeln",3,false),field("quietMode","Ruhezeitmodus",3,false),field("quietStart","Von",3,false),field("quietEnd","Bis",3,false),field("timezone","Zeitzone",3,false),field("quietKinds","Ausnahmen",3,false)));
        extra("menu.action","Suite-Menü bedienen","Alle Suite-Menüs",List.of(field("session","Menüsitzung",3,true),field("revision","Menüstand",3,true),field("option","Menüpunkt",3,true),field("click","Mausaktion",3,false),field("input","Eingabe",3,false)));
        extra("alts.ip","Alt-IP-Regel ändern und Verknüpfungen reparieren","Sicherheit",List.of(field("ip","IP-Adresse",3,true),choice("mode","Aktion","exclude","restore","purge","repair"),field("expected","Geladener Regelstand",3,true)));
        extra("settings.update","Suite-Einstellung speichern","Einstellungen",List.of(field("key","Einstellung",3,true),field("value","Neuer Wert; leer = leeren",3,false),field("expected","Geladener Stand",3,true)));
        extra("player.vitals","Spielerwerte live ändern","Spieler",List.of(field("player","Spielername oder UUID",3,true),choice("field","Wert","health","food","saturation","level","exp","gamemode","invulnerable","allowFlight"),field("value","Neuer Wert",3,true)));
        extra("world.update","Welteinstellung ändern","Welten",List.of(field("world","Weltname oder UUID",3,true),choice("field","Einstellung","time","weather","difficulty","pvp","autosave"),field("value","Neuer Wert",3,true)));
        extra("plugins.stage","Plugin-Update vormerken","Plugins",List.of(field("filename","Installierte JAR",3,true),field("expected","SHA-256 der installierten JAR",3,true),field("sha256","SHA-256 des Updates",3,true),field("name","Plugin-Name",3,true),field("main","Hauptklasse",3,true),field("version","Neue Version",3,true)));
        extra("logs.retry","Private Discord-Protokolle erneut versuchen","Server",List.of());
        extra("player.warnings","Warnungen eines Spielers verwerfen","Spieler",List.of(field("player","Spielername oder UUID",3,true),choice("operation","Aktion","remove","clear"),field("index","Position",3,false),field("expected","Geladener Text",3,false)));
        extra("crossplay.update","Geyser & Floodgate aktualisieren","Plugins",List.of(choice("restart","Danach neu starten","false","true")));
        extra("crossplay.auto","Automatische Crossplay-Prüfung","Plugins",List.of(choice("enabled","Automatisch prüfen","true","false")));
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
    static int countdown(Object value){
        double seconds=Double.parseDouble(String.valueOf(value));
        if(!Double.isFinite(seconds)||seconds<1||seconds>60||seconds!=(int)seconds)throw new IllegalArgumentException("Countdown muss eine ganze Zahl von 1 bis 60 Sekunden sein");
        return (int)seconds;
    }
    static String scope(String path){return Set.of("restart.apply","alts.ip","management.apply","events.ack","events.delete","events.note","events.clear","notifications.update","plugins.stage","world.update","logs.retry","settings.update","menu.action","crossplay.update","crossplay.auto","server.console","server.suite","suite.command","server.reload","server.trim","security.identity-reset","settings.set","moderation.config","moderation.logchannel","moderation.log").contains(path)?"full":"manage";}
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
            if(method.equals("GET")&&path.equals("/v1/ticket-file")){
                // Ticket attachments are served as bytes; only images keep their type, everything else downloads as a file.
                if(!AppApiSecurity.permitted(key,"manage"))throw new ApiError(403,"Ticketanhänge benötigen manage- oder full-Zugriff");
                long ticket;try{ticket=Long.parseLong(query.getOrDefault("ticket",""));}catch(NumberFormatException e){throw new ApiError(400,"Ticket-ID fehlt");}
                TicketManager manager=tickets();String id=query.getOrDefault("id","");
                Map<String,Object> meta=onMain(rev,()->{TicketStore.Ticket t=manager.store.get(ticket);if(t==null)throw new ApiError(404,"Ticket nicht gefunden");synchronized(manager.store){for(TicketStore.Message m:t.messages)for(Map<String,Object> a:m.attachments())if(id.equals(a.get("id")))return a;}throw new ApiError(404,"Anhang nicht gefunden");});
                Path file=manager.store.attachmentFile(ticket,id);if(!Files.isRegularFile(file))throw new ApiError(404,"Anhangdatei fehlt");
                byte[] bytes=Files.readAllBytes(file);String type=String.valueOf(meta.get("type"));
                x.getResponseHeaders().set("Content-Type",type.startsWith("image/")?type:"application/octet-stream");x.getResponseHeaders().set("Cache-Control","private, max-age=86400");x.getResponseHeaders().set("X-Content-Type-Options","nosniff");
                x.getResponseHeaders().set("Content-Disposition","attachment; filename=\""+String.valueOf(meta.get("name")).replaceAll("[^A-Za-z0-9._-]","_")+"\"");
                x.sendResponseHeaders(200,bytes.length);try(var out=x.getResponseBody()){out.write(bytes);}return;
            }
            if(method.equals("GET")){
                result=switch(path){
                    case "/v1/health"->SimplexAdminSuite.appMap("serverId",security.serverId(),"name",serverName,"version",plugin.getDescription().getVersion(),"features",List.of("overview","statistics","worlds","plugins","log-status","database-inspector","plugin-files-v1","moderation-center-v1","events-v1","offline-player-nbt-v1","management-v2","players-paged-v2","profile-v2","discord-settings-v1","admin-roles-v3","public-chat-v1","restart-v1","admin-dashboard-v1","players-filters-v3","profile-v3","public-chat-v2","crossplay-v1","tickets-v1","tickets-v2","statistics-v2","player-warnings-v1"),"apiVersion",1,"scope",key.scope(),"keyLabel",key.label(),"time",System.currentTimeMillis());
                    case "/v1/data/chat"->{long after=Long.parseLong(query.getOrDefault("after","0"));yield plugin.appChat.read(after);}
                    case "/v1/data/crossplay"->{Object installed=onMain(rev,plugin::appCrossplayInstalled);yield plugin.appCrossplayStatus(installed);}
                    case "/v1/data/tickets"->onMain(rev,()->tickets().appList(query));
                    case "/v1/data/ticket"->onMain(rev,()->{long id;try{id=Long.parseLong(query.getOrDefault("id",""));}catch(NumberFormatException e){throw new IllegalArgumentException("Ticket-ID fehlt");}return tickets().appTicket(id);});
                    case "/v1/data/ticket-settings"->{if(!key.scope().equals("full"))throw new ApiError(403,"Ticketeinstellungen benötigen full-Zugriff");Map<String,Object> settings=onMain(rev,()->tickets().settings());DiscordBotManager bot=onMain(rev,()->plugin.discordRelay());yield bot==null?settings:bot.tickets.appSettings(settings);}
                    case "/v1/data/operations"->onMain(rev,plugin::appOperations);
                    case "/v1/data/admin-dashboard"->onMain(rev,()->plugin.appAdminDashboard(key.scope().equals("full")));
                    case "/v1/data/management"->onMain(rev,()->{if(!key.scope().equals("full")&&Set.of("admins","admin-rights","roles").contains(query.getOrDefault("section","status")))throw new ApiError(403,"Adminrechte benötigen full-Zugriff");return plugin.app250Data(query);});
                    case "/v1/player-nbt"->onMain(rev,()->playerNbt.read(query.getOrDefault("player",""),query.getOrDefault("path",""),Boolean.parseBoolean(query.getOrDefault("status","false"))));
                    case "/v1/notifications"->onMain(rev,()->{if(!key.scope().equals("full"))throw new ApiError(403,"Bark-Einrichtung benötigt full-Zugriff");if(plugin.appEvents==null)throw new ApiError(503,"Ereignismodul nicht verfügbar");return plugin.appEvents.subscription(key.id());});
                    case "/v1/data/discord-channels"->{DiscordBotManager bot=onMain(rev,()->plugin.discordRelay());if(bot==null)throw new ApiError(503,"Discord-Bot ist nicht geladen");yield bot.appChannels();}
                    case "/v1/data/litebans"->{org.bukkit.plugin.Plugin lite=onMain(rev,()->Bukkit.getPluginManager().getPlugin("LiteBans"));int offset;try{offset=Math.max(0,Integer.parseInt(query.getOrDefault("offset","0")));}catch(Exception e){offset=0;}yield plugin.moderationOverview.appLite(lite,query.getOrDefault("q",""),offset);}
                    case "/v1/plugin-files"->pluginFiles().list(Boolean.parseBoolean(query.getOrDefault("pending","false")));
                    case "/v1/plugin-files/inspect"->pluginFiles().inspect(query.getOrDefault("filename",""),Boolean.parseBoolean(query.getOrDefault("pending","false")));
                    case "/v1/plugin-files/status"->pluginFiles().status(query.getOrDefault("filename",""),query.getOrDefault("sha256",""));
                    case "/v1/menu"->onMain(rev,()->{if(!key.scope().equals("full"))throw new ApiError(403,"Suite-Menüs benötigen einen full-Schlüssel.");return menus.current(key);});
                    case "/v1/schema"->SimplexAdminSuite.appMap("actions",actions.values().stream().filter(row->!"plugins.stage".equals(row.get("id"))).toList());
                    case "/v1/view"->onMain(rev,()->{
                        DiscordAdminController.Request request=request(query.getOrDefault("path","dashboard"),new LinkedHashMap<>(query));
                        if(isCenterAction(request.path)||controller.mutation(request)||Set.of("plugins.stage","ai.ask","ai.propose","server.suite","world.update","logs.retry").contains(request.path))throw new ApiError(405,"Diese Aktion benötigt Vorschau und Bestätigung.");
                        return reply(controller.execute(request,actor(key)));
                    });
                    case "/v1/choices"->onMain(rev,()->controller.autocomplete(new DiscordAdminController.Request(query.getOrDefault("path",""),new LinkedHashMap<>(query)),query.getOrDefault("field",""),query.getOrDefault("q","")).stream().map(c->SimplexAdminSuite.appMap("name",c.name,"value",c.value)).toList());
                    case "/v1/data/alt-dashboard","/v1/data/alt-ips","/v1/data/events","/v1/data/vpn-whitelist","/v1/data/vpn-kicks","/v1/data/bans","/v1/data/reports","/v1/data/overview","/v1/data/statistics","/v1/data/statistics-rows","/v1/data/worlds","/v1/data/plugins","/v1/data/log-status","/v1/data/players","/v1/data/profile","/v1/data/alts","/v1/data/devices","/v1/data/ultrabans","/v1/data/snapshots","/v1/data/nbt-paths","/v1/data/settings","/v1/data/inventory","/v1/data/snapshot-content"->onMain(rev,()->{Object data=plugin.appApiData(path.substring("/v1/data/".length()),query);if(path.endsWith("/settings")){Map<String,Object> envelope=object(data);for(Object entry:(List<?>)envelope.get("items")){Map<String,Object> item=object(entry);item.put("revision",AppApiSecurity.hash(DiscordBotManager.Json.stringify(item.get("value"))));}}return data;});
                    default->throw new ApiError(404,"API-Endpunkt nicht gefunden.");
                };
            }else if(method.equals("POST")&&path.equals("/v1/chat")){
                if(!key.scope().equals("full"))throw new ApiError(403,"Chatversand benötigt full-Zugriff");
                if(!rate("chat:"+key.id(),20))throw new ApiError(429,"Maximal 20 Chatnachrichten pro Minute");
                if(!"application/json".equals(x.getRequestHeaders().getFirst("Content-Type")))throw new ApiError(415,"application/json erforderlich");
                byte[] bytes=x.getRequestBody().readNBytes(4097);if(bytes.length>4096)throw new ApiError(413,"Nachricht zu groß");String text=new String(bytes,StandardCharsets.UTF_8);AppApiSecurity.checkJson(text);Map<String,Object> payload=object(DiscordBotManager.Json.parse(text));
                result=onMain(rev,()->{if(!security.keys().contains(key))throw new ApiError(401,"Schlüssel widerrufen");return plugin.appChat.send(plugin,actor(key).auditName(),String.valueOf(payload.getOrDefault("name","Server")),String.valueOf(payload.getOrDefault("message","")));});
            }else if(method.equals("POST")&&path.equals("/v1/chat/clear")){
                if(!key.scope().equals("full"))throw new ApiError(403,"Chatverlauf leeren benötigt full-Zugriff");
                if(!rate("chat-clear:"+key.id(),6))throw new ApiError(429,"Bitte kurz warten");
                result=onMain(rev,()->{if(!security.keys().contains(key))throw new ApiError(401,"Schlüssel widerrufen");return plugin.appChat.clear(plugin,actor(key).auditName());});
            }else if(method.equals("POST")&&path.equals("/v1/tickets/upload")){
                if(!AppApiSecurity.permitted(key,"manage"))throw new ApiError(403,"Anhänge senden benötigt manage- oder full-Zugriff");
                if(!rate("ticket-upload:"+key.id(),12))throw new ApiError(429,"Maximal 12 Anhänge pro Minute");
                long ticket;try{ticket=Long.parseLong(query.getOrDefault("id",""));}catch(NumberFormatException e){throw new ApiError(400,"Ticket-ID fehlt");}
                String type=String.valueOf(x.getRequestHeaders().getFirst("Content-Type")).split(";")[0].trim().toLowerCase(Locale.ROOT);
                byte[] bytes=x.getRequestBody().readNBytes(TicketStore.MAX_ATTACHMENT+1);if(bytes.length>TicketStore.MAX_ATTACHMENT)throw new ApiError(413,"Datei größer als 8 MiB");
                String text=query.getOrDefault("text","");if(text.length()>TicketStore.MAX_TEXT)throw new ApiError(413,"Text zu lang");
                if(generation.get()!=rev||!security.keys().contains(key))throw new ApiError(401,"API-Zugang wurde geändert");
                TicketManager manager=tickets();String actor=actor(key).auditName();
                result=manager.appUpload(ticket,query.getOrDefault("name","datei"),type,bytes,text,Boolean.parseBoolean(query.getOrDefault("mention","false")),actor);
            }else if(method.equals("POST")&&(path.equals("/v1/tickets")||path.equals("/v1/tickets/settings"))){
                boolean settings=path.endsWith("/settings");
                if(settings?!key.scope().equals("full"):!AppApiSecurity.permitted(key,"manage"))throw new ApiError(403,settings?"Ticketeinstellungen benötigen full-Zugriff":"Tickets bearbeiten benötigt manage- oder full-Zugriff");
                if(!rate("tickets:"+key.id(),40))throw new ApiError(429,"Zu viele Ticketaktionen. Bitte kurz warten.");
                String type=x.getRequestHeaders().getFirst("Content-Type");if(type==null||!type.toLowerCase(Locale.ROOT).startsWith("application/json"))throw new ApiError(415,"application/json erforderlich");
                byte[] bytes=x.getRequestBody().readNBytes(16385);if(bytes.length>16384)throw new ApiError(413,"Anfrage zu groß");
                String text=new String(bytes,StandardCharsets.UTF_8);AppApiSecurity.checkJson(text);Map<String,Object> input=new LinkedHashMap<>(object(DiscordBotManager.Json.parse(text)));
                boolean panel=settings&&Boolean.TRUE.equals(input.remove("postPanel"));
                Object saved=onMain(rev,()->{if(!security.keys().contains(key))throw new ApiError(401,"Schlüssel widerrufen");return settings?(input.isEmpty()?tickets().settings():tickets().saveSettings(input,actor(key).auditName())):tickets().appAction(input,actor(key).auditName(),key.scope().equals("full"));});
                if(panel){DiscordBotManager bot=onMain(rev,()->plugin.discordRelay());if(bot==null)throw new ApiError(503,"Discord-Bot ist nicht geladen");String message=bot.tickets.postPanel(bot.tickets.channelId());
                    result=SimplexAdminSuite.appMap("ok",!message.isBlank(),"title",message.isBlank()?"Panel nicht gepostet":"Ticket-Panel gepostet","description",message.isBlank()?bot.tickets.lastError():"Das Panel im Ticketkanal ist aktuell.");}
                else result=saved;
            }else if(method.equals("POST")&&path.equals("/v1/player-nbt")){
                if(!key.scope().equals("full"))throw new ApiError(403,"Offline-NBT speichern benötigt full-Zugriff");
                if(!rate("nbt:"+key.id(),6))throw new ApiError(429,"Maximal sechs NBT-Änderungen pro Minute");
                String type=x.getRequestHeaders().getFirst("Content-Type");if(type==null||!type.startsWith("application/json"))throw new ApiError(415,"application/json erforderlich");
                byte[] bytes=x.getRequestBody().readNBytes(2_810_001);if(bytes.length>2_810_000)throw new ApiError(413,"NBT-Anfrage zu groß");
                String text=new String(bytes,StandardCharsets.UTF_8);AppApiSecurity.checkJson(text);Map<String,Object> input=object(DiscordBotManager.Json.parse(text));
                result=onMain(rev,()->{if(!security.keys().contains(key))throw new ApiError(401,"API-Zugang widerrufen");return playerNbt.write(input,actor(key).auditName());});
            }else if(method.equals("POST")&&path.equals("/v1/database/inspect")){
                if(!key.scope().equals("full"))throw new ApiError(403,"Der Datenbankscanner benötigt einen full-Schlüssel.");
                if(!rate("db:"+key.id(),6))throw new ApiError(429,"Maximal sechs Datenbankscans pro Minute.");
                String type=x.getRequestHeaders().getFirst("Content-Type");if(type==null||!type.toLowerCase(Locale.ROOT).startsWith("application/json"))throw new ApiError(415,"application/json erforderlich");
                byte[] bytes=x.getRequestBody().readNBytes(16385);if(bytes.length>16384)throw new ApiError(413,"Anfrage zu groß");
                String text=new String(bytes,StandardCharsets.UTF_8);AppApiSecurity.checkJson(text);
                // Remains on the bounded HTTP worker pool, never Minecraft's main thread.
                result=databaseInspector.scan(object(DiscordBotManager.Json.parse(text)));
            }else if(method.equals("POST")&&path.equals("/v1/plugin-files/upload")){
                if(!AppApiSecurity.permitted(key,"full"))throw new ApiError(403,"Plugin-Updates benötigen einen full-Schlüssel.");
                if(!"application/java-archive".equals(x.getRequestHeaders().getFirst("Content-Type")))throw new ApiError(415,"application/java-archive erforderlich");
                String stored=security.consume(key,query.getOrDefault("ticket",""),System.currentTimeMillis());Map<String,Object> plan=object(DiscordBotManager.Json.parse(stored));
                if(!"plugins.stage".equals(plan.get("path")))throw new ApiError(400,"Bestätigung gehört nicht zu einem Plugin-Update");
                if(generation.get()!=rev || !security.keys().contains(key))throw new ApiError(401,"API-Zugang wurde geändert");
                result=pluginFiles().stage(object(plan.get("values")),x.getRequestBody(),()->{if(generation.get()!=rev || !security.keys().contains(key))throw new ApiError(401,"API-Zugang wurde geändert");});
                String auditName=actor(key).auditName();String detail=String.valueOf(object(plan.get("values")).get("filename"));
                onMain(rev,()->{plugin.expansionAudit(auditName,null,"Plugin-Update vorgemerkt",detail);return null;});
            }else if(method.equals("POST")&&(path.equals("/v1/prepare")||path.equals("/v1/execute"))){
                String contentType=x.getRequestHeaders().getFirst("Content-Type");if(contentType==null||!contentType.toLowerCase(Locale.ROOT).startsWith("application/json"))throw new ApiError(415,"application/json erforderlich");
                byte[] body=x.getRequestBody().readNBytes(65537);if(body.length>65536)throw new ApiError(413,"Anfrage zu groß");String text=new String(body,StandardCharsets.UTF_8);AppApiSecurity.checkJson(text);Map<String,Object> payload=object(DiscordBotManager.Json.parse(text));
                if(path.equals("/v1/prepare")){
                    String action=String.valueOf(payload.getOrDefault("path",""));Map<String,Object> values=object(payload.get("values"));
                    DiscordAdminController.Request req=request(action,values);authorize(key,req);
                    String encoded=DiscordBotManager.Json.stringify(SimplexAdminSuite.appMap("path",action,"values",values));
                    String summary;
                    if(req.path.equals("vpn.whitelist"))summary=onMain(rev,()->plugin.appVpnWhitelistPlan(req.text("player"),req.text("allowed")));
                    else if(isCenterAction(req.path))summary="Aktion: "+actions.get(req.path).get("description")+". Änderungen werden beim Ausführen erneut geprüft.";
                    else if(req.path.equals("plugins.stage")){pluginFiles().prepare(values);summary="Plugin: "+req.text("name")+" · "+req.text("version")+". Update wird geprüft und für den nächsten Serverstart vorgemerkt.";}
                    else summary=onMain(rev,()->{if(req.path.equals("alts.ip"))return plugin.altIpPlan(req.values);if(req.path.equals("world.update"))return "Welt: "+req.text("world")+"\nEinstellung: "+req.text("field")+"\nNeuer Wert: "+req.text("value");if(req.path.equals("logs.retry"))return "Gespeicherte private Discord-Protokolle erneut versuchen. Keine Einträge werden gelöscht; die Prüfung privater Kanalrechte bleibt aktiv.";if(req.path.equals("settings.update")){checkSetting(req);return "**Einstellung:** "+req.text("key")+"\n**Neuer Wert:** "+(req.text("value").isEmpty()?"(leer)":req.text("value"))+"\nDer geladene Stand wird vor dem Speichern erneut geprüft.";}return controller.confirmationSummary(req);});
                    validateDiscordChannel(req,rev);
                    result=SimplexAdminSuite.appMap("ticket",security.prepare(key,encoded,System.currentTimeMillis()),"summary",summary,"expiresIn",60);
                }else{
                    String stored=security.consume(key,String.valueOf(payload.getOrDefault("ticket","")),System.currentTimeMillis());Map<String,Object> plan=object(DiscordBotManager.Json.parse(stored));DiscordAdminController.Request req=request(String.valueOf(plan.get("path")),object(plan.get("values")));authorize(key,req);
                    validateDiscordChannel(req,rev);
                    if(req.path.equals("plugins.stage"))throw new ApiError(405,"Bestätigung mit der JAR an plugin-files/upload senden.");
                    if(req.path.equals("ai.ask")){
                        CompletableFuture<String> future=onMain(rev,()->plugin.discordAdminAiAsk(req.text("question")));String response=future.get(90,TimeUnit.SECONDS);result=SimplexAdminSuite.appMap("ok",true,"title","Simplex KI","description",response);
                    }else if(req.path.equals("ai.propose")){
                        CompletableFuture<DiscordAdminController.Request> future=onMain(rev,()->plugin.discordAdminAiProposalAsync(actor(key).auditName(),req.text("request")));var proposed=future.get(90,TimeUnit.SECONDS);
                        result=SimplexAdminSuite.appMap("ok",true,"title","KI-Vorschlag · noch nicht angewendet","description",onMain(rev,()->controller.confirmationSummary(proposed)),"proposal",SimplexAdminSuite.appMap("path",proposed.path,"values",proposed.values));
                    }else result=onMain(rev,()->{
                        authorize(key,req);
                        if(isCenterAction(req.path)) {
                            if(req.path.equals("events.ack"))return plugin.appEvents.acknowledge(key.id(),req.text("id"));
                            if(req.path.equals("notifications.update")){if(plugin.appEvents==null)throw new ApiError(503,"Ereignismodul nicht verfügbar");return plugin.appEvents.configure(key.id(),req.values);}
                            if(req.path.equals("restart.apply"))return plugin.appRestart(req.values,actor(key).auditName());
                            if(req.path.startsWith("crossplay."))return plugin.appCrossplayAction(req.path,req.values,actor(key).auditName());
                            if(req.path.equals("player.warnings"))return plugin.appWarnings(req.values,actor(key).auditName());
                            return plugin.appModerationMutation(req.path,req.values,actor(key).auditName());
                        }
                        if(req.path.equals("alts.ip"))return plugin.altIpApply(req.values,actor(key).auditName());
                        if(req.path.equals("settings.update")){checkSetting(req);plugin.asAdmin(actor(key).auditName(),()->plugin.moderationControl.appConfigSet(Bukkit.getConsoleSender(),req.text("key"),req.text("value")));plugin.expansionAudit(actor(key).auditName(),null,"App-Einstellung",req.text("key"));return SimplexAdminSuite.appMap("ok",true,"title","Einstellung gespeichert","description",req.text("key")+" gespeichert. Moduländerungen bei Bedarf mit Suite-Reload anwenden.");}
                        if(req.path.equals("menu.action"))return menus.perform(key,req.values);
                        if(Set.of("suite.command","player.vitals","world.update","logs.retry").contains(req.path)){Object[] out={null};plugin.asAdmin(actor(key).auditName(),()->out[0]=plugin.appApiMutation(req.path,req.values,actor(key).auditName()));return out[0];}
                        plugin.expansionAudit(actor(key).auditName(),null,"App-API-Aktion",req.path);return reply(controller.execute(req,actor(key)));
                    });
                }
            }else throw new ApiError(405,"Methode nicht erlaubt.");
            send(x,200,result);
        }catch(Throwable error){Throwable e=error;while(e instanceof ExecutionException&&e.getCause()!=null)e=e.getCause();int code=e instanceof ApiError a?a.code:e instanceof IllegalArgumentException||e instanceof java.io.IOException?400:e instanceof TimeoutException?504:503;
            String message=e instanceof ApiError||e instanceof IllegalArgumentException||e instanceof java.io.IOException?e.getMessage():e instanceof TimeoutException?"Serverantwort dauert zu lange. Aktion nicht automatisch wiederholen; aktuellen Zustand prüfen.":"App-API momentan nicht verfügbar. Serverprotokoll und API-Status prüfen.";
            try{send(x,code,SimplexAdminSuite.appMap("message",message));}catch(Exception ignored){}
        }finally{x.close();}
    }
    private TicketManager tickets(){if(plugin.tickets==null)throw new ApiError(503,"Ticketsystem nicht geladen");return plugin.tickets;}
    private static boolean isCenterAction(String path){return Set.of("player.warnings","crossplay.update","crossplay.auto","restart.apply","management.apply","vpn.whitelist","bans.remove","reports.update","events.ack","events.delete","events.note","events.clear","notifications.update").contains(path);}
    private void validateDiscordChannel(DiscordAdminController.Request req,long rev)throws Exception {
        if(!req.path.equals("settings.update")||!req.text("key").startsWith("discord-bot.")||!req.text("key").endsWith("channel-id")||req.text("value").isBlank())return;
        DiscordBotManager bot=onMain(rev,()->{checkSetting(req);return plugin.discordRelay();});
        if(bot==null)throw new IllegalArgumentException("Discord-Bot nicht geladen");bot.appValidateChannel(req.text("value"));
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
            String value=String.valueOf(entry.getValue());if(value.length()>(path.equals("management.apply")&&entry.getKey().equals("node")?24000:4000)||value.chars().anyMatch(c->c<32&&c!='\t'))throw new IllegalArgumentException("Feld enthält ungültige Zeichen oder ist zu lang");
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
