package eu.simplexsmp.adminsuite;

import com.sun.net.httpserver.*;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import javax.net.ssl.*;
import java.io.File;
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
    final AccountApi accounts;
    /** 10.3.0: world trimmer, Discord status and scoreboard designers, player dossier. */
    final StudioApi studio;
    final HistoryApi histories;
    final AdHubApi ads;
    final WebPanel web;
    /** 9.9.0: push, appearance, rename, public name, admin-log filters, LuckPerms and Lands. */
    final PanelHub hub;
    final WebPush webPush;
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
        this.plugin=plugin;histories=new HistoryApi(plugin);studio=new StudioApi(plugin);ads=new AdHubApi(plugin);playerNbt=new PlayerNbtAccess(plugin);serverName=Bukkit.getServer().getName();security=new AppApiSecurity(plugin.getDataFolder().toPath().resolve("app-api"));controller=new DiscordAdminController(plugin,plugin.discordRelay());menus=new AppApiMenus(plugin,security);accounts=new AccountApi(this,plugin,security);web=new WebPanel(plugin.getDescription().getVersion());
        hub=new PanelHub(this,plugin,security);
        webPush=new WebPush(security.directory.resolve("web-push.properties"),this::pushContact,hub::allowed,(owner,type,level)->plugin.appEvents==null?level:plugin.appEvents.quietLevel(owner,type,level));
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
        extra("accounts.update","App-Benutzer und Rechte verwalten","Zugänge",List.of(field("username","Benutzername",3,true),field("password","Neues Passwort (leer = behalten)",3,false),field("permissions","Rechte (kommagetrennt)",3,false),choice("enabled","Aktiv","true","false"),choice("operation","Aktion","save","delete","reset-mfa","logout-all")));
        extra("links.update","Minecraft-/Discord-Verifizierung verwalten","Discord",List.of(field("player","Minecraft-UUID",3,true),field("discord","Neue Discord-ID",3,false),field("expected","Bisherige Discord-ID (leer = neu)",3,false),choice("operation","Aktion","save","delete")));
        extra("memory.update","RAM-Notfallneustart einstellen","Server",List.of(choice("enabled","Aktiv","true","false"),field("thresholdBytes","Grenzwert in Bytes",3,true),field("holdSeconds","Dauer in Sekunden",3,true),field("countdownSeconds","Countdown in Sekunden",3,true)));
        extra("online.update","Discord-Beitrittshinweise einstellen","Discord",List.of(field("channel","Discord-Kanal-ID",3,true)));
        extra("quiet.update","Ruhezeiten & Notfallneustart-Hinweis","Benachrichtigungen",List.of(choice("sleepMode","Schlafenszeit","off","all","critical","selected"),field("sleepStart","Schlafenszeit von",3,false),field("sleepEnd","Schlafenszeit bis",3,false),field("timezone","Zeitzone",3,false),field("quietKinds","Ausnahmen in der Schlafenszeit",3,false),choice("workEnabled","Arbeitszeit aktiv","true","false"),field("workStart","Arbeitszeit von",3,false),field("workEnd","Arbeitszeit bis",3,false),field("workDays","Arbeitstage 1–7",3,false),choice("emergencyEnabled","Hinweis nach Notfallneustart","true","false"),choice("emergencyOverride","Notfall darf durchdringen","none","work","sleep","both")));
        extra("setup.create","Einmaligen Setup-Link erstellen","Zugänge",List.of(field("username","Benutzername",3,true),field("minutes","Gültigkeit in Minuten (5–10080)",3,false),field("endpoint","API-Adresse für den Link",3,false),choice("operation","Aktion","create","cancel")));
        extra("tasks.update","Staff-Aufgabe bearbeiten","Team",List.of(choice("operation","Aktion","create","claim","done","priority","delete"),field("id","Aufgaben-ID",3,false),field("title","Titel",3,false),field("details","Details",3,false),choice("priority","Priorität","LOW","NORMAL","HIGH","URGENT"),field("dueAt","Fällig (ms, 0 = ohne)",3,false)));
        extra("app.settings","App-Einstellung speichern","Einstellungen",List.of(field("key","Einstellung",3,true),field("value","Neuer Wert",3,false)));
        extra("staffchat.update","Staff-Chat-Kanal festlegen","Discord",List.of(field("channel","Discord-Kanal-ID (leer = aus)",3,false)));
        extra("luckperms.edit","LuckPerms-Gruppen, Rechte und Ränge ändern","Ränge & Rechte",List.of(field("operation","Aktion",3,true),field("group","Gruppe",3,false),field("player","Spieler",3,false),field("node","Recht",3,false),field("value","Wert",3,false),field("duration","Dauer (z. B. 7d)",3,false),field("track","Track",3,false),field("context","Kontext (server=… / world=…)",3,false),field("priority","Priorität für Präfix/Suffix",3,false)));
        extra("lands.access","Lands-Adminzugriff vergeben oder entziehen","Ränge & Rechte",List.of(field("player","Spieler",3,true),choice("node","Zugriff","lands.bypass.*","lands.admin.*"),choice("operation","Aktion","grant","revoke")));
        Map<String,Object> lockScope=choice("scope","Gilt (leer = empfohlen je Bereich)","default","everywhere","own","own-wild");lockScope.put("required",false);
        extra("locks.update","Spielerrechte & Sperren ändern","Ränge & Rechte",List.of(field("player","Spielername oder UUID",3,true),choice("operation","Aktion","set","preset","remove","clear","right-block","right-allow"),field("right","Einzelrecht (bei right-block/right-allow)",3,false),field("flags","Bereiche (z. B. chest,shulker)",3,false),lockScope,field("duration","Dauer (leer = dauerhaft, z. B. 7d)",3,false),field("reason","Grund (sieht der Spieler)",3,false),field("preset","Vorlage (thief, containers, griefer, lockdown)",3,false)));
        extra("commands.update","Befehle eines Spielers sperren oder erlauben","Ränge & Rechte",List.of(field("player","Spielername oder UUID",3,true),choice("operation","Aktion","block","allow","reset","clear"),field("commands","Befehle (kommagetrennt, ohne /)",3,false),field("duration","Dauer (leer = dauerhaft, z. B. 7d)",3,false),field("reason","Grund (sieht der Spieler)",3,false),optionalChoice("alts","Alt-Accounts erben die Sperre (leer = ja)","true","false"),optionalChoice("grant","Fehlende Rechte in LuckPerms setzen (leer = nein)","false","true")));
        extra("commands.global","Befehle für alle Spieler sperren","Ränge & Rechte",List.of(choice("operation","Aktion","global-block","global-allow"),field("commands","Befehle (kommagetrennt, ohne /)",3,true),field("duration","Dauer (leer = dauerhaft)",3,false),field("reason","Grund",3,false)));
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
    /** 10.2: a choice the client may omit (the action then uses its documented default). */
    private static Map<String,Object> optionalChoice(String id,String label,String... values){var f=choice(id,label,values);f.put("required",false);return f;}
    private static Map<String,Object> choice(String id,String label,String... values){var f=field(id,label,3,true);f.put("choices",Arrays.stream(values).map(v->SimplexAdminSuite.appMap("name",switch(v){case "health"->"Gesundheit";case "food"->"Hunger";case "saturation"->"Sättigung";case "level"->"Erfahrungslevel";case "exp"->"XP-Anteil";case "gamemode"->"Spielmodus";case "invulnerable"->"Unverwundbarkeit";case "allowFlight"->"Flug erlauben";default->v;},"value",v)).toList());return f;}
    static int countdown(Object value){
        double seconds=Double.parseDouble(String.valueOf(value));
        if(!Double.isFinite(seconds)||seconds<1||seconds>60||seconds!=(int)seconds)throw new IllegalArgumentException("Countdown muss eine ganze Zahl von 1 bis 60 Sekunden sein");
        return (int)seconds;
    }
    static String scope(String path){return Set.of("commands.global","luckperms.edit","lands.access","quiet.update","setup.create","app.settings","staffchat.update","accounts.update","links.update","memory.update","online.update","restart.apply","alts.ip","management.apply","events.ack","events.delete","events.note","events.clear","notifications.update","plugins.stage","world.update","logs.retry","settings.update","menu.action","crossplay.update","crossplay.auto","server.console","server.suite","suite.command","server.reload","server.trim","security.identity-reset","settings.set","moderation.config","moderation.logchannel","moderation.log").contains(path)?"full":"manage";}
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
            plugin.getLogger().info("Optionale Simplex-Panel-API aktiv: "+status+". Webpanel: "+webAddress()+" · Schlüssel nur mit /sas app create erzeugen.");
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
    boolean rate(String id,int max){synchronized(rates){long now=System.currentTimeMillis();rates.values().removeIf(v->now-v[0]>60_000);if(rates.size()>1024&&!rates.containsKey(id))return false;long[] v=rates.computeIfAbsent(id,k->new long[]{now,0});return ++v[1]<=max;}}
    /** Client address; behind the loopback-only proxy transport the proxy's X-Forwarded-For / X-Real-IP is used. */
    String clientIp(HttpExchange x){
        InetAddress remote=x.getRemoteAddress().getAddress();String ip=remote.getHostAddress();
        if(remote.isLoopbackAddress()&&"proxy".equals(plugin.getConfig().getString("app-api.transport","tls"))){
            String forwarded=x.getRequestHeaders().getFirst("X-Forwarded-For");if(forwarded==null)forwarded=x.getRequestHeaders().getFirst("X-Real-IP");
            if(forwarded!=null){String[] hops=forwarded.split(",");String last=hops[hops.length-1].trim();if(last.matches("[0-9A-Fa-f:.]{2,45}"))ip=last;}
        }
        return ip;
    }
    /** Same-origin check for browsers: the Origin must match the Host the browser used, or the configured domain. */
    private boolean sameOrigin(HttpExchange x){
        String origin=x.getRequestHeaders().getFirst("Origin");if(origin==null)return true;
        String host=x.getRequestHeaders().getFirst("Host");
        if(host!=null&&origin.equalsIgnoreCase("https://"+host))return true;
        String forwarded=x.getRequestHeaders().getFirst("X-Forwarded-Host");
        if(forwarded!=null&&"proxy".equals(plugin.getConfig().getString("app-api.transport","tls"))&&x.getRemoteAddress().getAddress().isLoopbackAddress()&&origin.equalsIgnoreCase("https://"+forwarded.split(",")[0].trim()))return true;
        String domain=accounts.domain();return !domain.isEmpty()&&origin.equalsIgnoreCase("https://"+domain);
    }
    private void handle(HttpExchange x,long rev){
        try{
            String ip=clientIp(x),requestPath=x.getRequestURI().getPath(),requestMethod=x.getRequestMethod();
            if(!rate("ip:"+ip,240))throw new ApiError(429,"Zu viele Anfragen. Bitte kurz warten.");
            // 9.8.0: the browser panel shares this port. Static files only; data stays behind /v1 authentication.
            if((requestMethod.equals("GET")||requestMethod.equals("HEAD"))&&web.serves(requestPath)){web.serve(x,requestPath);return;}
            if(!sameOrigin(x))throw new ApiError(403,"Browserzugriff ist nicht freigegeben.");
            boolean browser=x.getRequestHeaders().containsKey(WebPanel.HEADER);
            if(x.getRequestHeaders().containsKey("Origin")&&!browser)throw new ApiError(403,"Browserzugriff ist nicht freigegeben.");
            if(!requestMethod.equals("GET")&&browser&&!x.getRequestHeaders().containsKey("Origin"))throw new ApiError(403,"Browseranfrage ohne Herkunft abgelehnt.");
            if(accounts.publicRoute(x,requestPath,requestMethod,ip))return;
            if(ads.publicRoute(this,x,requestPath,requestMethod,ip))return;
            if(x.getRequestMethod().equals("POST")&&x.getRequestURI().getPath().equals("/v1/setup")){
                // One-time setup links (9.7.0): the code replaces username/password exactly once.
                if(!rate("setup-ip:"+ip,10))throw new ApiError(429,"Zu viele Versuche. Eine Minute warten.");
                Map<String,Object> setupBody=body(x,1024);Map<String,Object> device=accounts.device(x,ip,false);device.put("via","Setup-Link");
                String[] redeemed=security.users.redeem(String.valueOf(setupBody.getOrDefault("code","")),System.currentTimeMillis(),device);
                if(redeemed==null)throw new ApiError(401,"Setup-Link ungültig, abgelaufen oder bereits verwendet. Bitte einen neuen Link anfordern.");
                String who=redeemed[0];Bukkit.getScheduler().runTask(plugin,()->plugin.suiteAudit("Simplex Panel/"+who+" (app:user:"+who+")","Setup-Link eingelöst",who));
                send(x,200,SimplexAdminSuite.appMap("token",redeemed[1],"username",who,"expiresIn",2592000,"serverId",security.serverId(),"name",serverName));return;
            }
            String header=x.getRequestHeaders().getFirst("Authorization");String bearer=header!=null&&header.startsWith("Bearer ")?header.substring(7):null;
            String cookie=bearer==null&&browser?WebPanel.cookie(x):null;
            AppApiSecurity.Key key=security.authenticate(bearer!=null?bearer:cookie);
            if(key==null){if(cookie!=null)WebPanel.clearCookie(x);throw new ApiError(401,bearer==null&&browser?"Bitte anmelden.":"API-Schlüssel ungültig oder widerrufen.");}
            if(security.locked(key))throw new ApiError(423,"Dieses Gerät wurde vom Hauptadmin vorübergehend gesperrt. Bitte beim Hauptadmin melden.");
            security.touch(key,ip,cookie!=null?Map.of():AccountApi.appDevice(x));
            if(!rate("key:"+key.id(),180))throw new ApiError(429,"Schlüssel-Anfragelimit erreicht.");
            if(accounts.privateRoute(x,key,requestPath,requestMethod,ip,rev))return;
            if(hub.route(x,key,requestPath,requestMethod,ip,rev))return;
            if(plugin.diagnostics!=null&&plugin.diagnostics.route(this,x,key,requestPath,requestMethod,rev))return;
            if(plugin.diagnostics==null&&requestPath.startsWith("/v1/diagnostics"))throw new ApiError(503,"Diagnosezentrale nicht geladen");
            if(studio.route(this,x,key,requestPath,requestMethod,rev))return;
            if(ads.route(this,x,key,requestPath,requestMethod))return;
            if(histories.route(this,x,key,requestPath,requestMethod))return;
            String path=x.getRequestURI().getPath(),method=x.getRequestMethod();Map<String,String> query=query(x.getRequestURI());Object result;
            authorizeRoute(key,path,method,query);
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
            if(method.equals("GET")&&path.equals("/v1/staff-chat-file")){
                if(plugin.staffChat==null)throw new ApiError(503,"Staff-Chat nicht geladen");String id=query.getOrDefault("id","");
                Map<String,Object> meta=plugin.staffChat.attachment(id);if(meta==null)throw new ApiError(404,"Anhang nicht gefunden");
                Path file=plugin.staffChat.attachmentFile(id);if(!Files.isRegularFile(file))throw new ApiError(404,"Anhangdatei fehlt");
                byte[] bytes=Files.readAllBytes(file);String type=String.valueOf(meta.get("type"));
                x.getResponseHeaders().set("Content-Type",type.startsWith("image/")||type.startsWith("audio/")?type:"application/octet-stream");x.getResponseHeaders().set("Cache-Control","private, max-age=86400");x.getResponseHeaders().set("X-Content-Type-Options","nosniff");
                x.getResponseHeaders().set("Content-Disposition","attachment; filename=\""+String.valueOf(meta.get("name")).replaceAll("[^A-Za-z0-9._-]","_")+"\"");
                x.sendResponseHeaders(200,bytes.length);try(var out=x.getResponseBody()){out.write(bytes);}return;
            }
            if(method.equals("GET")){
                result=switch(path){
                    case "/v1/data/admin-log"->onMain(rev,()->{if(plugin.adminLog==null)throw new ApiError(503,"Admin-Log nicht geladen");String viewer=security.notificationOwner(key),self=plugin.adminLog.viewerAdmin(key);boolean views=Boolean.parseBoolean(query.getOrDefault("views","false"));
                        if(!query.containsKey("admin"))return plugin.adminLog.overview(viewer,self,views);int offset;try{offset=Math.max(0,Integer.parseInt(query.getOrDefault("offset","0")));}catch(NumberFormatException e){offset=0;}
                        return plugin.adminLog.entries(viewer,self,query.getOrDefault("admin",""),query.getOrDefault("q",""),query.getOrDefault("category",""),offset,views);});
                    case "/v1/data/staff-chat"->{if(plugin.staffChat==null)throw new ApiError(503,"Staff-Chat nicht geladen");long after;try{after=Long.parseLong(query.getOrDefault("after","0"));}catch(NumberFormatException e){after=0;}Map<String,Object> chat=object(plugin.staffChat.read(after, query.getOrDefault("epoch", "")));chat.put("you",onMain(rev,()->senderName(key)));yield chat;}
                    case "/v1/data/nameplates"->onMain(rev,()->SimplexAdminSuite.appMap("items",plugin.appNameplates(),"luckperms",Bukkit.getPluginManager().getPlugin("LuckPerms")!=null,"geyser",Bukkit.getPluginManager().getPlugin("Geyser-Spigot")!=null,
                        "tabPlugin",VisualIdentityManager.tabPlugin(),"aboveHead",plugin.getConfig().getBoolean("nameplates.rank-above-head",true)&&plugin.getConfig().getBoolean("nameplates.manage-nametags",true),"inPlayerList",plugin.getConfig().getBoolean("nameplates.rank-in-player-list",true),
                        "playerListManaged",plugin.getConfig().getBoolean("nameplates.manage-player-list",true)&&!(plugin.getConfig().getBoolean("nameplates.yield-to-tab-plugin",true)&&!VisualIdentityManager.tabPlugin().isEmpty())));
                    case "/v1/data/app-settings"->onMain(rev,()->AppSettings.data(plugin));
                    case "/v1/data/staff-tasks"->onMain(rev,()->plugin.appStaffTasks());
                    case "/v1/data/setup-info"->onMain(rev,()->SimplexAdminSuite.appMap("fingerprint",fingerprint,"publicAddress",plugin.getConfig().getString("app-api.public-address",""),"port",plugin.getConfig().getInt("app-api.port",39093),"name",serverName));
                    case "/v1/health"->SimplexAdminSuite.appMap("serverId",security.serverId(),"name",serverName,"version",plugin.getDescription().getVersion(),"features",List.of("overview","statistics","worlds","plugins","log-status","database-inspector","plugin-files-v1","moderation-center-v1","events-v1","offline-player-nbt-v1","management-v2","players-paged-v2","profile-v2","discord-settings-v1","admin-roles-v3","public-chat-v1","restart-v1","admin-dashboard-v1","players-filters-v3","profile-v3","public-chat-v2","crossplay-v1","tickets-v1","tickets-v2","statistics-v2","player-warnings-v1","accounts-v1","links-v1","online-notifications-v1","memory-restart-v1","admin-log-v1","staff-chat-v1","setup-links-v1","quiet-hours-v1","nameplates-v1","app-settings-v1","staff-tasks-v1","accounts-v2","sessions-v1","mfa-v1","passkeys-v1","web-panel-v1","admin-log-v2","web-push-v1","web-prefs-v1","account-rename-v1","public-name-v1","luckperms-v1","lands-v1","nameplates-v2","access-locks-v1","player-rights-v1","luckperms-v2","changelog-v1","bans-sources-v2","permission-catalog-v2","command-rights-v1","luckperms-v3","live-switches-v1","diagnostics-v1","lag-map-v1","spark-v1","crash-manager-v1","player-connection-v1","command-rights-v2","world-trim-v1","discord-status-designer-v1","scoreboard-designer-v1","player-dossier-v1","bridge-v3","bark-test-v1","ad-hub-v1","history-management-v1","player-nbt-summary-v1","shortcut-preferences-v1","plugin-stage-v2"),"apiVersion",1,"scope",key.scope(),"keyLabel",key.label(),"limited",key.id().startsWith("user:"),"permissions",key.id().startsWith("user:")?security.users.permissions(key.label()):List.of("*"),"time",System.currentTimeMillis(),
                        "account",SimplexAdminSuite.appMap("kind",key.id().startsWith("user:")?"user":"key","username",key.id().startsWith("user:")?key.label():"","owner",key.id().startsWith("user:")&&security.users.owner(key.label()),"manager",accounts.manager(key),"mfa",key.id().startsWith("user:")&&security.users.mfa(key.label()),"canMigrate",!key.id().startsWith("user:")&&key.scope().equals("full")&&security.users.ownerName().isEmpty()),"web",SimplexAdminSuite.appMap("domain",accounts.domain(),"url",accounts.webUrl(),"passkeys",!accounts.rpId().isEmpty()));
                    case "/v1/data/access"->{if(!key.scope().equals("full"))throw new ApiError(403,"Benutzerverwaltung benötigt full-Zugriff");yield SimplexAdminSuite.appMap("items",security.users.list(),"catalog",permissionCatalog(),"presets",rolePresets());}
                    case "/v1/data/links"->SimplexAdminSuite.appMap("items",plugin.accountLinks.store.all().stream().map(l->SimplexAdminSuite.appMap("player",l.minecraft().toString(),"name",l.name(),"discord",l.discord(),"linkedAt",l.linkedAt())).toList());
                    case "/v1/data/memory"->onMain(rev,()->plugin.memoryGuard.data());
                    case "/v1/data/online-settings"->onMain(rev,()->SimplexAdminSuite.appMap("channel",plugin.getConfig().getString("online-notifications.channel-id",""),"limit",10,"consentDays",3));
                    case "/v1/data/chat"->{long after=Long.parseLong(query.getOrDefault("after","0"));yield plugin.appChat.read(after);}
                    case "/v1/data/crossplay"->{Object installed=onMain(rev,plugin::appCrossplayInstalled);yield plugin.appCrossplayStatus(installed);}
                    case "/v1/data/tickets"->onMain(rev,()->tickets().appList(query));
                    case "/v1/data/ticket"->onMain(rev,()->{long id;try{id=Long.parseLong(query.getOrDefault("id",""));}catch(NumberFormatException e){throw new IllegalArgumentException("Ticket-ID fehlt");}return tickets().appTicket(id);});
                    case "/v1/data/ticket-settings"->{if(!key.scope().equals("full"))throw new ApiError(403,"Ticketeinstellungen benötigen full-Zugriff");Map<String,Object> settings=onMain(rev,()->tickets().settings());DiscordBotManager bot=onMain(rev,()->plugin.discordRelay());yield bot==null?settings:bot.tickets.appSettings(settings);}
                    case "/v1/data/operations"->onMain(rev,plugin::appOperations);
                    case "/v1/data/admin-dashboard"->onMain(rev,()->plugin.appAdminDashboard(key.scope().equals("full")));
                    case "/v1/data/management"->onMain(rev,()->{if(!key.scope().equals("full")&&Set.of("admins","admin-rights","roles").contains(query.getOrDefault("section","status")))throw new ApiError(403,"Adminrechte benötigen full-Zugriff");return plugin.app250Data(query);});
                    case "/v1/player-nbt/summary"->{
                        requirePermission(key,"data.profile");requirePermission(key,"data.nbt-paths");
                        UUID player=onMain(rev,()->plugin.discordAdminResolvePlayer(query.getOrDefault("player","")));
                        if(player==null)throw new ApiError(404,"Spielerprofil fehlt");
                        List<File> worlds=onMain(rev,()->Bukkit.getWorlds().stream().map(World::getWorldFolder).toList());
                        yield PlayerNbtAccess.summary(Path.of("."),worlds,player);
                    }
                    case "/v1/player-nbt"->onMain(rev,()->playerNbt.read(query.getOrDefault("player",""),query.getOrDefault("path",""),Boolean.parseBoolean(query.getOrDefault("status","false"))));
                    case "/v1/notifications"->onMain(rev,()->{if(!key.scope().equals("full"))throw new ApiError(403,"Bark-Einrichtung benötigt full-Zugriff");if(plugin.appEvents==null)throw new ApiError(503,"Ereignismodul nicht verfügbar");return plugin.appEvents.subscription(security.notificationOwner(key));});
                    case "/v1/data/discord-channels"->{DiscordBotManager bot=onMain(rev,()->plugin.discordRelay());if(bot==null)throw new ApiError(503,"Discord-Bot ist nicht geladen");yield bot.appChannels();}
                    case "/v1/data/litebans"->{org.bukkit.plugin.Plugin lite=onMain(rev,()->Bukkit.getPluginManager().getPlugin("LiteBans"));int offset;try{offset=Math.max(0,Integer.parseInt(query.getOrDefault("offset","0")));}catch(Exception e){offset=0;}yield plugin.moderationOverview.appLite(lite,query.getOrDefault("q",""),offset);}
                    case "/v1/plugin-files"->pluginFiles().list(Boolean.parseBoolean(query.getOrDefault("pending","false")));
                    case "/v1/plugin-files/identity"->pluginFiles().identity(query.getOrDefault("filename",""));
                    case "/v1/plugin-files/inspect"->pluginFiles().inspect(query.getOrDefault("filename",""),Boolean.parseBoolean(query.getOrDefault("pending","false")));
                    case "/v1/plugin-files/status"->pluginFiles().status(query.getOrDefault("filename",""),query.getOrDefault("sha256",""));
                    case "/v1/menu"->onMain(rev,()->{if(!key.scope().equals("full"))throw new ApiError(403,"Suite-Menüs benötigen einen full-Schlüssel.");return menus.current(key);});
                    case "/v1/schema"->SimplexAdminSuite.appMap("actions",actions.values().stream().filter(row->!"plugins.stage".equals(row.get("id"))).map(row->{Map<String,Object> copy=new LinkedHashMap<>(row);copy.put("allowed",security.users.allows(key,"action."+row.get("id"))&&AppApiSecurity.permitted(key,scope(String.valueOf(row.get("id")))));return copy;}).toList());
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
                result=onMain(rev,()->{if(!security.valid(key))throw new ApiError(401,"Schlüssel widerrufen");return plugin.appChat.send(plugin,actor(key).auditName(),String.valueOf(payload.getOrDefault("name","Server")),String.valueOf(payload.getOrDefault("message","")));});
            }else if(method.equals("POST")&&path.equals("/v1/admin-log/read")){
                if(plugin.adminLog==null)throw new ApiError(503,"Admin-Log nicht geladen");
                if(!"application/json".equals(String.valueOf(x.getRequestHeaders().getFirst("Content-Type")).split(";")[0].trim()))throw new ApiError(415,"application/json erforderlich");
                byte[] bytes=x.getRequestBody().readNBytes(1025);if(bytes.length>1024)throw new ApiError(413,"Anfrage zu groß");String text=new String(bytes,StandardCharsets.UTF_8);AppApiSecurity.checkJson(text);
                String admin=String.valueOf(object(DiscordBotManager.Json.parse(text)).getOrDefault("admin",""));if(admin.length()>80)throw new IllegalArgumentException("Admin ungültig");
                result=plugin.adminLog.markRead(security.notificationOwner(key),admin);
            }else if(method.equals("POST")&&(path.equals("/v1/staff-chat")||path.equals("/v1/staff-chat/upload"))){
                if(plugin.staffChat==null)throw new ApiError(503,"Staff-Chat nicht geladen");
                if(!rate("staff-chat:"+key.id(),30))throw new ApiError(429,"Maximal 30 Staff-Nachrichten pro Minute");
                String text;List<Map<String,Object>> files=new ArrayList<>();List<String> pings;
                if(path.endsWith("/upload")){
                    String type=String.valueOf(x.getRequestHeaders().getFirst("Content-Type")).split(";")[0].trim().toLowerCase(Locale.ROOT);
                    byte[] bytes=x.getRequestBody().readNBytes(StaffChat.MAX_FILE+1);if(bytes.length>StaffChat.MAX_FILE)throw new ApiError(413,"Datei größer als 8 MiB");
                    text=query.getOrDefault("text","");files.add(plugin.staffChat.store(query.getOrDefault("name","datei"),type,bytes));
                    pings=Arrays.stream(query.getOrDefault("pings","").split(",")).map(String::trim).filter(v->!v.isBlank()).toList();
                }else{
                    if(!"application/json".equals(String.valueOf(x.getRequestHeaders().getFirst("Content-Type")).split(";")[0].trim()))throw new ApiError(415,"application/json erforderlich");
                    byte[] bytes=x.getRequestBody().readNBytes(8193);if(bytes.length>8192)throw new ApiError(413,"Nachricht zu groß");String body=new String(bytes,StandardCharsets.UTF_8);AppApiSecurity.checkJson(body);Map<String,Object> payload=object(DiscordBotManager.Json.parse(body));
                    text=String.valueOf(payload.getOrDefault("text",""));pings=payload.get("pings") instanceof List<?> l?l.stream().map(String::valueOf).toList():List.of();
                }
                if(pings.size()>60)throw new IllegalArgumentException("Zu viele Erwähnungen");
                if(generation.get()!=rev||!security.valid(key))throw new ApiError(401,"API-Zugang wurde geändert");
                String sender=onMain(rev,()->senderName(key));
                result=plugin.staffChat.fromApp(sender,text,files,pings,actor(key).auditName());
            }else if(method.equals("POST")&&path.equals("/v1/chat/clear")){
                if(!key.scope().equals("full"))throw new ApiError(403,"Chatverlauf leeren benötigt full-Zugriff");
                if(!rate("chat-clear:"+key.id(),6))throw new ApiError(429,"Bitte kurz warten");
                result=onMain(rev,()->{if(!security.valid(key))throw new ApiError(401,"Schlüssel widerrufen");return plugin.appChat.clear(plugin,actor(key).auditName());});
            }else if(method.equals("POST")&&path.equals("/v1/tickets/upload")){
                if(!AppApiSecurity.permitted(key,"manage"))throw new ApiError(403,"Anhänge senden benötigt manage- oder full-Zugriff");
                if(!rate("ticket-upload:"+key.id(),12))throw new ApiError(429,"Maximal 12 Anhänge pro Minute");
                long ticket;try{ticket=Long.parseLong(query.getOrDefault("id",""));}catch(NumberFormatException e){throw new ApiError(400,"Ticket-ID fehlt");}
                String type=String.valueOf(x.getRequestHeaders().getFirst("Content-Type")).split(";")[0].trim().toLowerCase(Locale.ROOT);
                byte[] bytes=x.getRequestBody().readNBytes(TicketStore.MAX_ATTACHMENT+1);if(bytes.length>TicketStore.MAX_ATTACHMENT)throw new ApiError(413,"Datei größer als 8 MiB");
                String text=query.getOrDefault("text","");if(text.length()>TicketStore.MAX_TEXT)throw new ApiError(413,"Text zu lang");
                if(generation.get()!=rev||!security.valid(key))throw new ApiError(401,"API-Zugang wurde geändert");
                TicketManager manager=tickets();String actor=actor(key).auditName();
                result=manager.appUpload(ticket,query.getOrDefault("name","datei"),type,bytes,text,Boolean.parseBoolean(query.getOrDefault("mention","false")),actor);
            }else if(method.equals("POST")&&(path.equals("/v1/tickets")||path.equals("/v1/tickets/settings"))){
                boolean settings=path.endsWith("/settings");
                if(settings?!key.scope().equals("full"):!AppApiSecurity.permitted(key,"manage"))throw new ApiError(403,settings?"Ticketeinstellungen benötigen full-Zugriff":"Tickets bearbeiten benötigt manage- oder full-Zugriff");
                if(!rate("tickets:"+key.id(),40))throw new ApiError(429,"Zu viele Ticketaktionen. Bitte kurz warten.");
                String type=x.getRequestHeaders().getFirst("Content-Type");if(type==null||!type.toLowerCase(Locale.ROOT).startsWith("application/json"))throw new ApiError(415,"application/json erforderlich");
                byte[] bytes=x.getRequestBody().readNBytes(16385);if(bytes.length>16384)throw new ApiError(413,"Anfrage zu groß");
                String text=new String(bytes,StandardCharsets.UTF_8);AppApiSecurity.checkJson(text);Map<String,Object> input=new LinkedHashMap<>(object(DiscordBotManager.Json.parse(text)));
                if(!settings)requirePermission(key,"ticket."+String.valueOf(input.getOrDefault("action","")));
                boolean panel=settings&&Boolean.TRUE.equals(input.remove("postPanel"));
                Object saved=onMain(rev,()->{if(!security.valid(key))throw new ApiError(401,"Schlüssel widerrufen");return settings?(input.isEmpty()?tickets().settings():tickets().saveSettings(input,actor(key).auditName())):tickets().appAction(input,actor(key).auditName(),key.scope().equals("full"));});
                if(panel){DiscordBotManager bot=onMain(rev,()->plugin.discordRelay());if(bot==null)throw new ApiError(503,"Discord-Bot ist nicht geladen");String message=bot.tickets.postPanel(bot.tickets.channelId());
                    result=SimplexAdminSuite.appMap("ok",!message.isBlank(),"title",message.isBlank()?"Panel nicht gepostet":"Ticket-Panel gepostet","description",message.isBlank()?bot.tickets.lastError():"Das Panel im Ticketkanal ist aktuell.");}
                else result=saved;
            }else if(method.equals("POST")&&path.equals("/v1/player-nbt")){
                if(!key.scope().equals("full"))throw new ApiError(403,"Offline-NBT speichern benötigt full-Zugriff");
                if(!rate("nbt:"+key.id(),6))throw new ApiError(429,"Maximal sechs NBT-Änderungen pro Minute");
                String type=x.getRequestHeaders().getFirst("Content-Type");if(type==null||!type.startsWith("application/json"))throw new ApiError(415,"application/json erforderlich");
                byte[] bytes=x.getRequestBody().readNBytes(2_810_001);if(bytes.length>2_810_000)throw new ApiError(413,"NBT-Anfrage zu groß");
                String text=new String(bytes,StandardCharsets.UTF_8);AppApiSecurity.checkJson(text);Map<String,Object> input=object(DiscordBotManager.Json.parse(text));
                result=onMain(rev,()->{if(!security.valid(key))throw new ApiError(401,"API-Zugang widerrufen");return playerNbt.write(input,actor(key).auditName());});
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
                if(generation.get()!=rev || !security.valid(key))throw new ApiError(401,"API-Zugang wurde geändert");
                result=pluginFiles().stage(object(plan.get("values")),x.getRequestBody(),()->{if(generation.get()!=rev || !security.valid(key))throw new ApiError(401,"API-Zugang wurde geändert");});
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
                    else if(Set.of("accounts.update","links.update","memory.update","online.update","quiet.update","setup.create","app.settings","staffchat.update","tasks.update").contains(req.path))summary="Änderung: "+actions.get(req.path).get("description")+". Bestehende Daten bleiben erhalten. Passwort und Sitzungen werden nie angezeigt.";
                    else if(req.path.equals("luckperms.edit"))summary=LuckPermsAdmin.summary(req.values);
                    else if(req.path.equals("lands.access"))summary=LuckPermsAdmin.summary(landsAccess(req.values));
                    else if(req.path.equals("commands.update")||req.path.equals("commands.global"))summary=onMain(rev,()->{CommandRights c=commandRights();boolean global=req.path.equals("commands.global");if(global!=req.text("operation").startsWith("global"))throw new IllegalArgumentException("Aktion passt nicht zu "+req.path);return CommandRights.summary(req.values,global?"":c.name(plugin.accessLocks.resolve(req.text("player"))));});
                    else if(req.path.equals("locks.update"))summary=onMain(rev,()->{if(plugin.accessLocks==null)throw new ApiError(503,"Rechte-Sperren nicht geladen");return AccessLocks.summary(req.values,plugin.accessLocks.name(plugin.accessLocks.resolve(req.text("player"))));});
                    else if(isCenterAction(req.path))summary="Aktion: "+actions.get(req.path).get("description")+". Änderungen werden beim Ausführen erneut geprüft.";
                    else if(req.path.equals("plugins.stage")){pluginFiles().prepare(values);summary="Plugin: "+req.text("name")+" · "+req.text("version")+". Update wird geprüft und für den nächsten Serverstart vorgemerkt.";}
                    else summary=onMain(rev,()->{if(req.path.equals("alts.ip"))return plugin.altIpPlan(req.values);if(req.path.equals("world.update"))return "Welt: "+req.text("world")+"\nEinstellung: "+req.text("field")+"\nNeuer Wert: "+req.text("value");if(req.path.equals("logs.retry"))return "Gespeicherte private Discord-Protokolle erneut versuchen. Keine Einträge werden gelöscht; die Prüfung privater Kanalrechte bleibt aktiv.";if(req.path.equals("settings.update")){checkSetting(req);return "**Einstellung:** "+req.text("key")+"\n**Neuer Wert:** "+(req.text("value").isEmpty()?"(leer)":req.text("value"))+"\nDer geladene Stand wird vor dem Speichern erneut geprüft.";}return controller.confirmationSummary(req);});
                    validateDiscordChannel(req,rev);
                    long expires=req.path.equals("plugins.stage")?30*60:60;
                    result=SimplexAdminSuite.appMap("ticket",security.prepare(key,encoded,System.currentTimeMillis(),expires*1000),"summary",summary,"expiresIn",expires);
                }else{
                    String stored=security.consume(key,String.valueOf(payload.getOrDefault("ticket","")),System.currentTimeMillis());Map<String,Object> plan=object(DiscordBotManager.Json.parse(stored));DiscordAdminController.Request req=request(String.valueOf(plan.get("path")),object(plan.get("values")));authorize(key,req);
                    validateDiscordChannel(req,rev);
                    if(req.path.equals("plugins.stage"))throw new ApiError(405,"Bestätigung mit der JAR an plugin-files/upload senden.");
                    if(req.path.equals("accounts.update")){
                        authorize(key,req);String username=AppUsers.username(req.text("username"));String operation=req.text("operation");
                        // 9.8.0: the main admin account is protected; only the main admin (or a classic full key) may change it.
                        boolean targetOwner=security.users.owner(username),actorOwner=!key.id().startsWith("user:")||security.users.owner(key.label());
                        if(targetOwner&&!actorOwner)throw new ApiError(403,"Den Hauptadmin-Zugang kann nur der Hauptadmin selbst ändern.");
                        if(targetOwner&&(operation.equals("delete")||operation.equals("save")&&!Boolean.parseBoolean(req.text("enabled"))))throw new ApiError(409,"Der Hauptadmin-Zugang kann nicht gelöscht oder deaktiviert werden. Notfalls per Serverkonsole zurücksetzen: /sas app owner-reset");
                        String title,description;
                        switch(operation){
                            case "delete"->{security.users.delete(username);String gone=PanelHub.ownerKey(username);try{webPush.removeOwner(gone);hub.removeOwner(gone);}catch(java.io.IOException ignored){}title="App-Benutzer gelöscht";description="Alle Geräte, Passkeys und Sitzungen dieses Benutzers wurden entfernt.";}
                            case "reset-mfa"->{if(!security.users.exists(username))throw new IllegalArgumentException("Benutzer nicht gefunden");security.users.disableMfa(username);title="Zwei-Faktor-Schutz zurückgesetzt";description=username+" meldet sich wieder nur mit Passwort an und kann 2FA neu einrichten.";}
                            case "logout-all"->{security.users.logoutAll(username);title="Alle Geräte abgemeldet";description="Alle App- und Browser-Sitzungen von "+username+" wurden beendet.";}
                            default->{Set<String> rights=new HashSet<>(Arrays.stream(req.text("permissions").split(",")).map(String::trim).filter(v->!v.isBlank()).toList());Set<String> known=new HashSet<>(permissionCatalog().stream().map(r->String.valueOf(r.get("id"))).toList());if(!known.containsAll(rights))throw new IllegalArgumentException("Unbekanntes Zugriffsrecht");
                                if(targetOwner)rights=Set.of("*");else if(rights.contains("*")&&!actorOwner)throw new ApiError(403,"Vollzugriff (*) kann nur der Hauptadmin vergeben.");
                                security.users.save(username,req.text("password"),rights,Boolean.parseBoolean(req.text("enabled")));title="App-Benutzer gespeichert";description="Bisherige Sitzungen dieses Benutzers wurden abgemeldet.";}
                        }
                        String auditDetail=username+" · "+operation;onMain(rev,()->{plugin.suiteAudit(actor(key).auditName(),"App-Benutzer geändert",auditDetail);return null;});result=SimplexAdminSuite.appMap("ok",true,"title",title,"description",description);
                    }else if(req.path.equals("links.update")){
                        UUID uuid=UUID.fromString(req.text("player"));if(req.text("operation").equals("delete")){if(!plugin.accountLinks.store.unlink(uuid,req.text("expected")))throw new IllegalArgumentException("Verifizierung geändert. Neu laden.");}
                        else {plugin.discordRelay().validateLinkTarget(req.text("discord"));authorize(key,req);String name=onMain(rev,()->plugin.expansionProfiles().stream().filter(p->p.uniqueId().equals(uuid)).map(p->p.displayName()).findFirst().orElseThrow(()->new IllegalArgumentException("Spieler nicht gefunden")));var linked=plugin.accountLinks.store.replace(uuid,req.text("expected"),req.text("discord"),name);plugin.discordRelay().syncLinkedNickname(linked,name);}
                        onMain(rev,()->{plugin.suiteAudit(actor(key).auditName(),"Discord-Verifizierung geändert",uuid.toString());return null;});result=SimplexAdminSuite.appMap("ok",true,"title","Verifizierung gespeichert","description","Bestehende Discord-/Chat-Verknüpfungen werden direkt weiterverwendet.");
                    }else if(req.path.equals("quiet.update")){if(plugin.appEvents==null)throw new ApiError(503,"Ereignismodul nicht verfügbar");result=plugin.appEvents.configureQuiet(security.notificationOwner(key),req.values);
                    }else if(req.path.equals("setup.create")){
                        String username=AppUsers.username(req.text("username"));
                        if(req.text("operation").equals("cancel")){security.users.cancelSetup(username);result=SimplexAdminSuite.appMap("ok",true,"title","Setup-Link widerrufen","description","Ein noch nicht eingelöster Link für "+username+" ist ungültig.");}
                        else{int minutes;try{minutes=req.text("minutes").isBlank()?60:Integer.parseInt(req.text("minutes"));}catch(NumberFormatException e){throw new IllegalArgumentException("Gültigkeit in Minuten angeben");}
                            String code=security.users.setupCode(username,minutes,System.currentTimeMillis());String endpoint=req.text("endpoint").isBlank()?plugin.getConfig().getString("app-api.public-address",""):req.text("endpoint");
                            String link=setupLink(endpoint,code,username);onMain(rev,()->{plugin.suiteAudit(actor(key).auditName(),"Setup-Link erstellt",username+" · "+minutes+" min");return null;});
                            result=SimplexAdminSuite.appMap("ok",true,"title","Setup-Link erstellt","description","Einmalig gültig für "+minutes+" Minuten. Nur an "+username+" weitergeben.","link",link,"code",code,"fingerprint",fingerprint,"expiresAt",System.currentTimeMillis()+minutes*60_000L,"username",username);}
                    }else if(req.path.equals("luckperms.edit")){result=onMain(rev,()->hub.luckPerms.apply(req.values,actor(key).auditName()));
                    }else if(req.path.equals("lands.access")){Map<String,Object> lp=landsAccess(req.values);result=onMain(rev,()->hub.luckPerms.apply(lp,actor(key).auditName()));
                    }else if(req.path.equals("commands.update")||req.path.equals("commands.global")){
                        boolean global=req.path.equals("commands.global");if(global!=req.text("operation").startsWith("global"))throw new IllegalArgumentException("Aktion passt nicht zu "+req.path);
                        // Granting a missing permission is a LuckPerms change: only for keys that may edit LuckPerms.
                        boolean grant=Boolean.parseBoolean(req.text("grant"));
                        if(grant&&(!AppApiSecurity.permitted(key,"full")||!security.users.allows(key,"action.luckperms.edit")))throw new ApiError(403,"Fehlende Rechte in LuckPerms setzen benötigt Vollzugriff und action.luckperms.edit.");
                        String who=actor(key).auditName();result=onMain(rev,()->commandRights().apply(req.values,who,grant?change->hub.luckPerms.apply(change,who):null));
                    }else if(req.path.equals("locks.update")){result=onMain(rev,()->{if(plugin.accessLocks==null)throw new ApiError(503,"Rechte-Sperren nicht geladen");return plugin.accessLocks.apply(req.values,actor(key).auditName());});
                    }else if(req.path.equals("tasks.update")){String who=onMain(rev,()->senderName(key));result=onMain(rev,()->plugin.appStaffTask(who,req.values));
                    }else if(req.path.equals("app.settings")){result=onMain(rev,()->AppSettings.apply(plugin,req.text("key"),req.text("value"),actor(key).auditName()));
                    }else if(req.path.equals("staffchat.update")){String channel=req.text("channel").trim();if(!channel.isEmpty()){DiscordBotManager bot=onMain(rev,()->plugin.discordRelay());if(bot==null)throw new ApiError(503,"Discord-Bot ist nicht geladen");bot.appValidateChannel(channel);}result=onMain(rev,()->{plugin.getConfig().set("staff-chat.channel-id",channel);plugin.saveConfig();plugin.suiteAudit(actor(key).auditName(),"Staff-Chat-Kanal",channel.isEmpty()?"aus":channel);return SimplexAdminSuite.appMap("ok",true,"title",channel.isEmpty()?"Staff-Chat-Sync aus":"Staff-Chat-Kanal gespeichert","description",channel.isEmpty()?"App und Minecraft teilen weiter den Staff-Chat.":"Nachrichten, Bilder, Dateien und Sprachnachrichten werden mit Discord synchronisiert.");});
                    }else if(req.path.equals("memory.update")){result=onMain(rev,()->plugin.memoryGuard.configure(req.values));
                    }else if(req.path.equals("online.update")){plugin.discordRelay().appValidateChannel(req.text("channel"));result=onMain(rev,()->{plugin.getConfig().set("online-notifications.channel-id",req.text("channel"));plugin.saveConfig();return SimplexAdminSuite.appMap("ok",true,"title","Benachrichtigungskanal gespeichert");});
                    }else if(req.path.equals("ai.ask")){
                        CompletableFuture<String> future=onMain(rev,()->plugin.discordAdminAiAsk(req.text("question")));String response=future.get(90,TimeUnit.SECONDS);result=SimplexAdminSuite.appMap("ok",true,"title","Simplex KI","description",response);
                    }else if(req.path.equals("ai.propose")){
                        CompletableFuture<DiscordAdminController.Request> future=onMain(rev,()->plugin.discordAdminAiProposalAsync(actor(key).auditName(),req.text("request")));var proposed=future.get(90,TimeUnit.SECONDS);
                        result=SimplexAdminSuite.appMap("ok",true,"title","KI-Vorschlag · noch nicht angewendet","description",onMain(rev,()->controller.confirmationSummary(proposed)),"proposal",SimplexAdminSuite.appMap("path",proposed.path,"values",proposed.values));
                    }else result=onMain(rev,()->{
                        authorize(key,req);
                        if(isCenterAction(req.path)) {
                            if(req.path.equals("events.ack"))return plugin.appEvents.acknowledge(security.notificationOwner(key),req.text("id"));
                            if(req.path.equals("notifications.update")){if(plugin.appEvents==null)throw new ApiError(503,"Ereignismodul nicht verfügbar");return plugin.appEvents.configure(security.notificationOwner(key),req.values);}
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
    /** Staff-chat sender: the configured owner name for full keys, the exact Minecraft spelling for app users. */
    private String senderName(AppApiSecurity.Key key){
        String owner=plugin.getConfig().getString("app-api.owner-name","").trim();
        if(!key.id().startsWith("user:"))return owner.isBlank()?key.label():owner;
        for(var p:plugin.expansionProfiles())if(p.displayName().equalsIgnoreCase(key.label()))return p.displayName();
        return key.label();
    }
    /** panel://setup link understood by Simplex Panel 4.0; the fingerprint pins the self-signed certificate. */
    String setupLink(String endpoint,String code,String username){
        String host=endpoint==null?"":endpoint.trim().replaceAll("/+$","");if(host.isBlank())return "";
        if(!host.startsWith("https://"))host="https://"+host;
        String enc=URLEncoder.encode(host,StandardCharsets.UTF_8);
        return "panel://setup?e="+enc+"&f="+fingerprint+"&c="+URLEncoder.encode(code,StandardCharsets.UTF_8)+"&u="+URLEncoder.encode(username,StandardCharsets.UTF_8)+"&n="+URLEncoder.encode(serverName,StandardCharsets.UTF_8);
    }
    boolean diagnosticsNotifiable(String owner,String type){return hub!=null&&hub.allowed(owner,type);}
    void requirePermission(AppApiSecurity.Key key,String permission){if(!security.users.allows(key,permission))throw new ApiError(403,"Keine Berechtigung: "+permission);}
    private void authorizeRoute(AppApiSecurity.Key key,String path,String method,Map<String,String> query){
        if(!key.id().startsWith("user:"))return;
        if(Set.of("/v1/health","/v1/schema","/v1/prepare","/v1/execute").contains(path))return;
        String permission;
        if(path.equals("/v1/view")||path.equals("/v1/choices"))permission="action."+query.getOrDefault("path","dashboard");
        else if(path.equals("/v1/tickets"))return; // individual operation is checked after decoding its bounded JSON body
        else if(path.equals("/v1/tickets/settings"))permission="ticket.settings";
        else if(path.equals("/v1/tickets/upload"))permission="ticket.reply";
        else if(path.equals("/v1/ticket-file"))permission="data.ticket";
        else if(path.equals("/v1/notifications"))permission="action.notifications.update";
        else if(path.equals("/v1/menu"))permission="action.menu.action";
        else if(path.startsWith("/v1/data/"))permission="data."+path.substring(9);
        else if(path.startsWith("/v1/plugin-files"))permission=method.equals("POST")?"action.plugins.stage":"data.plugins";
        else if(path.equals("/v1/player-nbt/summary"))permission="data.nbt-paths";
        else if(path.equals("/v1/player-nbt"))permission=method.equals("POST")?"player-nbt.write":"data.nbt-paths";
        else if(path.equals("/v1/database/inspect"))permission="database.inspect";
        else if(path.startsWith("/v1/chat"))permission=path.endsWith("clear")?"chat.clear":"chat.send";
        else if(path.equals("/v1/admin-log/read"))permission="data.admin-log";
        else if(path.startsWith("/v1/staff-chat-file"))permission="data.staff-chat";
        else if(path.startsWith("/v1/staff-chat"))permission="staff-chat.send";
        else throw new ApiError(403,"Endpunkt für diesen Benutzer nicht freigegeben");
        requirePermission(key,permission);
    }
    /** 10.0.0: plain-language titles, groups and explanations so admins understand every right at a glance. */
    private static final Map<String,String[]> DATA_RIGHTS=new LinkedHashMap<>();
    static {
        String[][] rows={{"overview","Serverübersicht","Server"},{"statistics","Statistiken","Server"},{"statistics-rows","Statistik-Details je Spieler","Server"},{"worlds","Welten","Server"},{"plugins","Plugins","Server"},{"log-status","Protokollstatus","Server"},
            {"operations","Einsatzleitstelle","Server"},{"memory","RAM-Notfallneustart","Server"},{"crossplay","Geyser/Floodgate-Status","Server"},{"settings","Suite-Einstellungen","Einstellungen"},{"app-settings","App-Einstellungen","Einstellungen"},{"management","Suite-Verwaltung","Einstellungen"},
            {"players","Spielerliste","Spieler"},{"profile","Spielerprofile","Spieler"},{"inventory","Live-Inventare","Spieler"},{"snapshots","Inventar-Snapshots","Spieler"},{"snapshot-content","Inhalt von Snapshots","Spieler"},{"nbt-paths","Spielerdaten (NBT)","Spieler"},{"devices","Geräte von Spielern","Spieler"},
            {"bans","Banns & Sperren","Moderation"},{"reports","Reports","Moderation"},{"litebans","LiteBans-Einträge","Moderation"},{"ultrabans","Ultrabanns","Moderation"},{"access-locks","Spielerrechte & Sperren","Moderation"},
            {"alts","Alt-Konten eines Spielers","Sicherheit"},{"alt-dashboard","Alt-Account-Übersicht","Sicherheit"},{"alt-ips","Alt-IP-Regeln","Sicherheit"},{"vpn-whitelist","VPN-Ausnahmen","Sicherheit"},{"vpn-kicks","VPN-Kicks","Sicherheit"},{"events","Ereignisse & Alarme","Sicherheit"},
            {"tickets","Ticketliste","Tickets & Chat"},{"ticket","Einzelne Tickets mit Verlauf","Tickets & Chat"},{"ticket-settings","Ticketeinstellungen","Tickets & Chat"},{"chat","Serverchat","Tickets & Chat"},{"staff-chat","Staff-Chat","Tickets & Chat"},{"staff-tasks","Staff-Aufgaben","Tickets & Chat"},
            {"luckperms","Ränge & Rechte (LuckPerms)","Ränge & Rechte"},{"commands","Befehle & Befehlsrechte","Ränge & Rechte"},{"lands","Lands","Ränge & Rechte"},{"nameplates","Ränge & Namen über dem Kopf","Ränge & Rechte"},
            {"access","App-Benutzer & ihre Rechte","Zugänge"},{"links","Discord-Verknüpfungen","Zugänge"},{"discord-channels","Discord-Kanäle","Zugänge"},{"online-settings","Beitrittshinweise","Zugänge"},{"setup-info","Einrichtungsdaten","Zugänge"},
            {"admin-dashboard","Admin-Übersicht","Übersicht"},{"admin-log","Admin-Log","Übersicht"},{"diagnostics","Diagnose: Live-Werte, Disconnects, Abstürze, Lag-Karte, Konsole","Diagnose"},
            {"world-trim","Welten-Trimmer: Status, Dimensionen und Verlauf","Welten-Trimmer"},{"discord-status","Discord-Status-Designer","Studio"},{"scoreboard","Scoreboard-Designer","Studio"},{"discord-ads","Werbe-Zentrale: Werbe-Orte, Texte, Timer und Verlauf ansehen, Werbung kopieren und Erinnerungen erhalten","Werbe-Zentrale"}};
        for(String[] r:rows)DATA_RIGHTS.put(r[0],new String[]{r[1],r[2]});
    }
    private static final Map<String,String[]> OTHER_RIGHTS=new LinkedHashMap<>();
    static {
        String[][] rows={{"ticket.reply","Tickets beantworten","Tickets & Chat","Antworten an Spieler senden (mit öffentlichem Namen)."},{"ticket.claim","Tickets übernehmen","Tickets & Chat","Ticket sich selbst zuweisen."},
            {"ticket.priority","Ticket-Priorität ändern","Tickets & Chat","Niedrig bis Notfall."},{"ticket.close","Tickets schließen","Tickets & Chat","Ticket als erledigt schließen."},{"ticket.reopen","Tickets wieder öffnen","Tickets & Chat","Geschlossene Tickets erneut öffnen."},
            {"ticket.delete","Tickets löschen","Tickets & Chat","Ticket dauerhaft entfernen."},{"ticket.discard","Ticketentwürfe verwerfen","Tickets & Chat","Nicht abgeschickte Tickets entfernen."},{"ticket.settings","Ticketeinstellungen ändern","Tickets & Chat","Kategorien, Kanäle und Grenzen."},
            {"chat.send","Im Serverchat schreiben","Tickets & Chat","Nachrichten an alle Spieler senden."},{"chat.clear","Serverchat leeren","Tickets & Chat","Chat für alle Spieler leeren."},{"staff-chat.send","Im Staff-Chat schreiben","Tickets & Chat","Nachrichten, Bilder und Dateien ans Team."},
            {"player-nbt.write","Spielerdaten (NBT) ändern","Spieler","Offline-Spielerdaten direkt bearbeiten – nur für erfahrene Admins."},{"database.inspect","Datenbank ansehen","Server","Tabellen der Plugin-Datenbanken lesen."},
            {"diagnostics.run","Diagnosen ausführen","Diagnose","Volldiagnose, Lag-Karte, Spark-Import und KI-Analysen starten (KI nutzt den hinterlegten OpenAI-Schlüssel)."},{"diagnostics.manage","Diagnose steuern","Diagnose","Spark-Profiler starten/stoppen, Vorfälle erledigen oder löschen; mit Vollzugriff auch die Schwellenwerte."},{"world-trim.run","Welten-Trimmer ausführen","Welten-Trimmer","Kritisch: Trimmt Welten (löscht kaum besuchte Chunks), trennt alle Spieler und startet den Server neu. Nur nach vollständigem Backup – nicht in den Rollenvorlagen enthalten."},
            {"discord-status.edit","Discord-Status gestalten","Studio","Texte, Farben, Felder und Vorlagen der Discord-Statusnachricht speichern."},{"scoreboard.edit","Scoreboard gestalten","Studio","Titel, Zeilen und Vorlagen des Scoreboards für alle Spieler speichern."},
            {"discord-ads.manage","Werbe-Zentrale verwalten","Werbe-Zentrale","Werbe-Orte (Discord-Kanäle, Reddit, Planet Minecraft, Facebook-Gruppen …), Werbetexte und Timer anlegen, ändern, pausieren und löschen. Gepostet wird nie automatisch – nicht in den Rollenvorlagen enthalten."},
            {"action.dashboard","Dashboard","Übersicht","Startseite mit Kennzahlen."},{"action.help","Hilfe","Übersicht","Bedienungshilfe anzeigen."},{"action.settings.list","Einstellungsliste","Einstellungen","Alle Einstellungen auflisten."}};
        for(String[] r:rows)OTHER_RIGHTS.put(r[0],new String[]{r[1],r[2],r[3]});
    }
    private List<Map<String,Object>> permissionCatalog(){
        List<Map<String,Object>> out=new ArrayList<>();
        out.add(SimplexAdminSuite.appMap("id","*","title","Vollzugriff (einschließlich Benutzerverwaltung)","group","Vollzugriff","explain","Darf alles – wie der Hauptadmin. Nur an Personen vergeben, denen du voll vertraust.","level","critical"));
        for(var row:actions.values()){String id=String.valueOf(row.get("id"));boolean critical=scope(id).equals("full");
            out.add(SimplexAdminSuite.appMap("id","action."+id,"title",row.get("description"),"group",String.valueOf(row.getOrDefault("group","Weitere")),"explain",(critical?"Kritisch: ":"")+"Darf „"+row.get("description")+"“ ausführen. Vor jeder Änderung erscheint eine Bestätigung.","level",critical?"critical":"change"));}
        out.add(SimplexAdminSuite.appMap("id",HistoryApi.MANAGE,"title","Verläufe löschen und wiederherstellen","group","Verläufe","explain","Benötigt full-Zugriff. Löscht nur bestätigte Einträge, mit Sicherung vor jeder Änderung.","level","critical"));
        for(String id:List.of("access","links","memory","online-settings","chat","crossplay","tickets","ticket","ticket-settings","operations","admin-dashboard","management","discord-channels","litebans","alt-dashboard","alt-ips","events","vpn-whitelist","vpn-kicks","bans","reports","overview","statistics","statistics-rows","worlds","plugins","log-status","players","profile","alts","devices","ultrabans","snapshots","nbt-paths","settings","inventory","snapshot-content","admin-log","staff-chat","nameplates","app-settings","setup-info","staff-tasks","luckperms","lands","access-locks","commands","diagnostics","world-trim","discord-status","scoreboard","discord-ads")){
            String[] meta=DATA_RIGHTS.getOrDefault(id,new String[]{id,"Weitere"});
            out.add(SimplexAdminSuite.appMap("id","data."+id,"title",meta[0]+" ansehen","group",meta[1],"explain","Nur lesen – ändert nichts.","level","read"));}
        for(var e:OTHER_RIGHTS.entrySet())out.add(SimplexAdminSuite.appMap("id",e.getKey(),"title",e.getValue()[0],"group",e.getValue()[1],"explain",e.getValue()[2],"level",e.getKey().equals("world-trim.run")?"critical":e.getKey().startsWith("action.")?"read":"change"));
        out.add(SimplexAdminSuite.appMap("id","devices.manage","title","Alle Geräte & Sitzungen sehen, umbenennen, sperren und abmelden","group","Zugänge","explain","Kann andere Admins abmelden oder Geräte sperren.","level","critical"));
        return out;
    }
    /** 10.0.0: ready-made role bundles for the user editor in app and web panel (only known rights are used). */
    private List<Map<String,Object>> rolePresets(){
        Set<String> known=new HashSet<>(permissionCatalog().stream().map(r->String.valueOf(r.get("id"))).toList());
        List<String> support=List.of("action.dashboard","data.overview","data.players","data.profile","data.tickets","data.ticket","ticket.reply","ticket.claim","ticket.priority","ticket.close","ticket.reopen","data.reports","action.reports.update","data.chat","data.staff-chat","staff-chat.send","data.staff-tasks","action.tasks.update");
        List<String> moderator=new ArrayList<>(support);moderator.addAll(List.of("data.bans","action.bans.remove","data.litebans","action.player.punish","action.player.info","action.player.history","action.player.notes","action.player.watchlist","data.alts","data.events","action.events.ack","data.access-locks","action.locks.update","data.commands","action.commands.update","data.admin-log","chat.send","data.diagnostics"));
        List<String> admin=new ArrayList<>(moderator);admin.addAll(List.of("data.statistics","data.statistics-rows","data.worlds","data.plugins","data.luckperms","action.luckperms.edit","action.commands.global","data.lands","action.lands.access","data.nameplates","data.inventory","data.snapshots","action.player.action","action.player.right","action.player.rights","data.vpn-whitelist","data.vpn-kicks","data.alt-dashboard","data.devices","data.operations","data.admin-dashboard","diagnostics.run","diagnostics.manage"));
        List<Map<String,Object>> out=new ArrayList<>();
        out.add(SimplexAdminSuite.appMap("id","support","title","Supporter","explain","Tickets, Reports und Chat – keine Strafen.","permissions",support.stream().filter(known::contains).toList()));
        out.add(SimplexAdminSuite.appMap("id","moderator","title","Moderator","explain","Wie Supporter, dazu Banns, Strafen, Rechte-Sperren und Admin-Log.","permissions",moderator.stream().filter(known::contains).toList()));
        out.add(SimplexAdminSuite.appMap("id","admin","title","Admin","explain","Wie Moderator, dazu Ränge (LuckPerms), Lands, Statistiken, Diagnose und Spielerwerkzeuge – ohne Benutzerverwaltung.","permissions",admin.stream().filter(known::contains).toList()));
        out.add(SimplexAdminSuite.appMap("id","full","title","Vollzugriff","explain","Alles, auch App-Benutzer verwalten.","permissions",List.of("*")));
        return out;
    }
    private CommandRights commandRights(){if(plugin.commandRights==null||plugin.accessLocks==null)throw new ApiError(503,"Befehlsrechte nicht geladen");return plugin.commandRights;}
    private TicketManager tickets(){if(plugin.tickets==null)throw new ApiError(503,"Ticketsystem nicht geladen");return plugin.tickets;}
    /** VAPID contact (RFC 8292): configured address, otherwise the panel domain. */
    String pushContact(){
        String configured=plugin.getConfig().getString("app-api.push-contact","").trim();
        if(configured.startsWith("mailto:")||configured.startsWith("https://"))return configured;
        String rp=accounts.rpId();return rp.isEmpty()?"mailto:admin@localhost":"https://"+rp;
    }
    /** lands.access → the matching LuckPerms user permission change. */
    static Map<String,Object> landsAccess(Map<String,Object> values){
        String node=String.valueOf(values.getOrDefault("node","lands.bypass.*")),operation=String.valueOf(values.getOrDefault("operation","grant"));
        if(!LandsBridge.ADMIN_NODES.contains(node))throw new IllegalArgumentException("Lands-Zugriff: lands.bypass.* oder lands.admin.*");
        if(!Set.of("grant","revoke").contains(operation))throw new IllegalArgumentException("Aktion: grant oder revoke");
        return SimplexAdminSuite.appMap("operation",operation.equals("grant")?"user-permission-set":"user-permission-unset","player",String.valueOf(values.getOrDefault("player","")),"node",node,"value","true");
    }
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
        if(!security.valid(key))throw new ApiError(401,"API-Schlüssel wurde widerrufen.");
        requirePermission(key,"action."+request.path);
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
    <T>T onMain(long rev,Callable<T> work)throws Exception{
        CompletableFuture<T> future=new CompletableFuture<>();AtomicInteger state=new AtomicInteger(0);
        Bukkit.getScheduler().runTask(plugin,()->{if(!state.compareAndSet(0,1))return;try{if(generation.get()!=rev||!plugin.isEnabled())throw new ApiError(503,"API wurde neu geladen");future.complete(work.call());}catch(Throwable e){future.completeExceptionally(e);}finally{state.set(2);}});
        try{return future.get(15,TimeUnit.SECONDS);}catch(TimeoutException e){state.compareAndSet(0,3);throw e;}
    }
    /** Bounded JSON object body (application/json only). */
    static Map<String,Object> body(HttpExchange x,int limit)throws Exception{
        String ct=x.getRequestHeaders().getFirst("Content-Type");if(ct==null||!ct.toLowerCase(Locale.ROOT).startsWith("application/json"))throw new ApiError(415,"application/json erforderlich");
        byte[] bytes=x.getRequestBody().readNBytes(limit+1);if(bytes.length>limit)throw new ApiError(413,"Anfrage zu groß");String text=new String(bytes,StandardCharsets.UTF_8);AppApiSecurity.checkJson(text);return object(DiscordBotManager.Json.parse(text));
    }
    @SuppressWarnings("unchecked") static Map<String,Object> object(Object value){if(!(value instanceof Map<?,?>))throw new IllegalArgumentException("JSON-Objekt erforderlich");return (Map<String,Object>)value;}
    private static Map<String,Object> reply(DiscordAdminController.Reply r){return SimplexAdminSuite.appMap("ok",r.color!=0xED4245,"title",r.title,"description",r.description,"components",r.components);}
    static Map<String,String> query(URI uri){Map<String,String> m=new LinkedHashMap<>();if(uri.getRawQuery()!=null){if(uri.getRawQuery().length()>8192)throw new IllegalArgumentException("Suchanfrage zu lang");for(String pair:uri.getRawQuery().split("&")){String[] parts=pair.split("=",2);m.put(URLDecoder.decode(parts[0],StandardCharsets.UTF_8),parts.length>1?URLDecoder.decode(parts[1],StandardCharsets.UTF_8):"");}}return m;}
    static void send(HttpExchange x,int code,Object value)throws Exception{byte[] body=DiscordBotManager.Json.stringify(value).getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");x.getResponseHeaders().set("Cache-Control","no-store");x.getResponseHeaders().set("X-Content-Type-Options","nosniff");x.sendResponseHeaders(code,body.length);try(var out=x.getResponseBody()){out.write(body);}}
    static final class ApiError extends RuntimeException{final int code;ApiError(int code,String message){super(message);this.code=code;}}
    boolean command(CommandSender sender,String[] args){
        if(args.length>0&&args[0].equalsIgnoreCase("owner-reset")){ownerReset(sender,args);return true;}
        if(args.length>0&&CONSOLE_ONLY.contains(args[0].toLowerCase(Locale.ROOT))){recovery(sender,args);return true;}
        if(remote(sender)||!plugin.adminAccess.master(sender)){sender.sendMessage("§cApp-API-Zugänge dürfen nur echte Server-Operatoren/Hauptadmins oder die Serverkonsole verwalten.");return true;}
        if(args.length==0&&sender instanceof Player p){menu(p);return true;}
        try{String action=args.length==0?"status":args[0].toLowerCase(Locale.ROOT);switch(action){
            case "enable"->{int port=args.length>1?Integer.parseInt(args[1]):39093;if(port<1024||port>65535)throw new IllegalArgumentException("Port 1024–65535 erforderlich");plugin.getConfig().set("app-api.enabled",true);plugin.getConfig().set("app-api.port",port);plugin.saveConfig();reload();sender.sendMessage("§aAPI wird vorbereitet. /sas app status danach prüfen. Eigenen TCP-Port beim Hoster zuweisen/freigeben.");}
            case "disable"->{plugin.getConfig().set("app-api.enabled",false);plugin.saveConfig();stopListener();menus.clear();status="Ausgeschaltet";sender.sendMessage("§aApp-API ausgeschaltet. Bestehende Suite-Daten bleiben erhalten.");}
            case "reload"->{plugin.reloadConfig();reload();sender.sendMessage("§aApp-API wird neu geladen. Kein Serverneustart nötig.");}
            case "create"->{if(args.length<2)throw new IllegalArgumentException("/sas app create <Name-ohne-Leerzeichen> [read|manage|full]");var issued=security.issue(args[1],args.length>2?args[2]:"read");sender.sendMessage("§eEinmaliger API-Schlüssel für "+issued.key().label()+" ("+issued.key().scope()+"). Nicht teilen:");sender.sendMessage(issued.token());sender.sendMessage("§7Widerrufen: /sas app revoke "+issued.key().id());plugin.expansionAudit(sender.getName(),null,"App-Schlüssel erzeugt",issued.key().id()+" · "+issued.key().scope());}
            case "user"->{if(args.length<3)throw new IllegalArgumentException("/sas app user <Minecraft-Name> <Passwort (12+ Zeichen)> [Recht1,Recht2 oder *]");security.users.save(args[1],args[2],new HashSet<>(Arrays.asList((args.length>3?args[3]:"data.overview,data.players,data.profile").split(","))),true);sender.sendMessage("§aApp-Benutzer gespeichert. Passwort wird nur gehasht gespeichert; bestehende API-Schlüssel bleiben gültig.");}
            case "setup"->{if(args.length<2)throw new IllegalArgumentException("/sas app setup <Benutzer> [Minuten, Standard 60]");int minutes=args.length>2?Integer.parseInt(args[2]):60;String user=AppUsers.username(args[1]);String code=security.users.setupCode(user,minutes,System.currentTimeMillis());String link=setupLink(plugin.getConfig().getString("app-api.public-address",""),code,user);
                // Shown only to the executing operator/console, never broadcast.
                sender.sendMessage("§aEinmaliger Setup-Link für "+user+" ("+minutes+" min). Nicht öffentlich teilen:");if(!link.isBlank())sender.sendMessage(link);else{sender.sendMessage("§7Code: §f"+code);sender.sendMessage("§7Fingerabdruck: §f"+fingerprint);sender.sendMessage("§7Tipp: app-api.public-address setzen, dann entsteht ein vollständiger Link.");}
                plugin.expansionAudit(sender.getName(),null,"Setup-Link erstellt",user+" · "+minutes+" min");}
            case "keys"->{for(var key:security.keys())sender.sendMessage(key.id()+" · "+key.label()+" · "+key.scope());}
            case "domain"->{String domain=AccountApi.validateDomain(args.length>1?args[1]:"");plugin.getConfig().set("app-api.public-domain",domain);plugin.saveConfig();sender.sendMessage(domain.isEmpty()?"§aDomain entfernt. Webpanel bleibt unter https://Server-IP:Port erreichbar; Passkeys sind ohne Domain aus.":"§aDomain gespeichert: https://"+domain+"/ · DNS und ein gültiges HTTPS-Zertifikat (z. B. Reverse-Proxy) einrichten, dann funktionieren Passkeys.");plugin.expansionAudit(sender.getName(),null,"Webpanel-Domain",domain.isEmpty()?"entfernt":domain);}
            case "rename"->{if(args.length<3)throw new IllegalArgumentException("/sas app rename <alter Name> <neuer Name>");hub.rename(AppUsers.username(args[1]),AppUsers.username(args[2]),sender instanceof Player?sender.getName():"Serverkonsole");sender.sendMessage("§aBenutzername geändert: §f"+AppUsers.username(args[1])+" §7→ §f"+AppUsers.username(args[2])+"§a. Passwort, 2FA, Passkeys, Rechte und Geräte bleiben erhalten.");}
            case "user-enable","user-disable"->{if(args.length<2)throw new IllegalArgumentException("/sas app "+action+" <Benutzer>");security.users.setEnabled(args[1],action.equals("user-enable"));sender.sendMessage("§aBenutzer "+AppUsers.username(args[1])+(action.equals("user-enable")?" aktiviert.":" deaktiviert und abgemeldet."));plugin.expansionAudit(sender.getName(),null,"App-Benutzer "+(action.equals("user-enable")?"aktiviert":"deaktiviert"),AppUsers.username(args[1]));}
            case "user-logout"->{if(args.length<2)throw new IllegalArgumentException("/sas app user-logout <Benutzer>");security.users.logoutAll(AppUsers.username(args[1]));sender.sendMessage("§aAlle Geräte von "+AppUsers.username(args[1])+" abgemeldet.");plugin.expansionAudit(sender.getName(),null,"Alle Geräte abgemeldet",AppUsers.username(args[1]));}
            case "public-name"->{if(args.length<2)throw new IllegalArgumentException("/sas app public-name <Benutzer> [Name, leer = Standard]");String value=args.length>2?String.join(" ",Arrays.copyOfRange(args,2,args.length)):"";security.users.setPublicName(AppUsers.username(args[1]),value);sender.sendMessage("§aÖffentlicher Name von "+AppUsers.username(args[1])+": §f"+(value.isBlank()?plugin.getConfig().getString("app-api.public-staff-name","Team")+" (Standard)":value));}
            case "users"->{for(var row:security.users.list())sender.sendMessage("§f"+row.get("username")+(Boolean.TRUE.equals(row.get("owner"))?" §6[Hauptadmin]":"")+" §7· "+(Boolean.TRUE.equals(row.get("enabled"))?"aktiv":"deaktiviert")+" · 2FA "+(Boolean.TRUE.equals(row.get("mfa"))?"an":"aus")+" · "+row.get("sessions")+" Geräte");}
            case "revoke"->{if(args.length<2)throw new IllegalArgumentException("/sas app revoke <Schlüssel-ID>");security.revoke(args[1]);sender.sendMessage("§aSchlüssel widerrufen.");plugin.expansionAudit(sender.getName(),null,"App-Schlüssel widerrufen",args[1]);}
            case "fingerprint"->sender.sendMessage("SHA-256: "+(fingerprint.isBlank()?"Noch kein aktives TLS-Zertifikat":fingerprint));
            default->{sender.sendMessage("§bSimplex Panel API · "+status);sender.sendMessage("§7/sas app enable <Port> | disable | create <Name> [read|manage|full] | user <Name> <Passwort> [Rechte] | setup <Benutzer> [Minuten] | users | keys | revoke <ID> | domain [host] | fingerprint | reload");sender.sendMessage("§7Benutzer: rename <alt> <neu> | user-enable <Name> | user-disable <Name> | user-logout <Name> | public-name <Name> [Anzeigename]");sender.sendMessage("§7Notfall (nur Serverkonsole): owner-reset [Name] | user-reset <Name> | owner-transfer <Name> | unlock <Name> | recovery");sender.sendMessage("§7Webpanel: "+webAddress());if(!fingerprint.isBlank())sender.sendMessage("SHA-256: "+fingerprint);}
        }}catch(Exception e){sender.sendMessage("§cApp-API: "+e.getMessage());}return true;
    }
    /** Browser address of the panel: the configured domain, otherwise the public address or bind/port. */
    String webAddress(){
        if(!accounts.webUrl().isEmpty())return accounts.webUrl();
        String pub=plugin.getConfig().getString("app-api.public-address","").trim().replaceFirst("^https://","").replaceAll("/+$","");
        if(!pub.isEmpty())return "https://"+pub+"/";
        String bind=plugin.getConfig().getString("app-api.bind","0.0.0.0");return "https://"+(bind.equals("0.0.0.0")||bind.equals("::")?"<Server-IP>":bind)+":"+plugin.getConfig().getInt("app-api.port",39093)+"/";
    }
    /**
     * 9.8.0 emergency reset of the main admin. Console only: shows the user name again and a one-time password that
     * must be replaced at the next login. Two-factor, recovery codes and passkeys are removed; all devices signed out.
     */
    private void ownerReset(CommandSender sender,String[] args){
        if(!(sender instanceof org.bukkit.command.ConsoleCommandSender)){sender.sendMessage("§cDer Notfall-Reset ist nur direkt in der Serverkonsole möglich – nicht im Spiel, per RCON oder über die App.");return;}
        try{
            String[] reset=security.users.emergencyReset(args.length>1?args[1]:null);
            sender.sendMessage("§6════════ Simplex Panel · Notfall-Reset Hauptadmin ════════");
            if(Boolean.parseBoolean(reset[2]))sender.sendMessage("§aNeuer Hauptadmin-Zugang angelegt.");
            sender.sendMessage("§7Benutzername:   §f"+reset[0]);
            sender.sendMessage("§7Einmal-Passwort: §f"+reset[1]);
            sender.sendMessage("§7Anmelden in der App oder unter "+webAddress()+" – danach sofort ein neues Passwort festlegen.");
            sender.sendMessage("§7Zwei-Faktor-Schutz, Wiederherstellungscodes und Passkeys wurden entfernt, alle Geräte des Hauptadmins abgemeldet. Andere Benutzer und API-Schlüssel bleiben unverändert.");
            sender.sendMessage("§6══════════════════════════════════════════════════════");
            plugin.expansionAudit("Serverkonsole",null,"Notfall-Reset Hauptadmin",reset[0]);
        }catch(Exception e){sender.sendMessage("§cNotfall-Reset: "+e.getMessage());}
    }
    /** 9.9.0 recovery commands that only the real server console may run (never in-game, RCON relays, Discord or the app). */
    static final Set<String> CONSOLE_ONLY=Set.of("user-reset","owner-transfer","unlock","recovery");
    private void recovery(CommandSender sender,String[] args){
        if(!(sender instanceof org.bukkit.command.ConsoleCommandSender)){sender.sendMessage("§cDieser Wiederherstellungsbefehl ist nur direkt in der Serverkonsole möglich.");return;}
        String action=args[0].toLowerCase(Locale.ROOT);
        try{switch(action){
            case "user-reset"->{if(args.length<2)throw new IllegalArgumentException("/sas app user-reset <Benutzer>");String user=AppUsers.username(args[1]);String password=security.users.resetUser(user);
                sender.sendMessage("§6════════ Simplex Panel · Zugang wiederherstellen ════════");sender.sendMessage("§7Benutzername:   §f"+user+(security.users.owner(user)?" §6[Hauptadmin]":""));sender.sendMessage("§7Einmal-Passwort: §f"+password);
                sender.sendMessage("§7Beim nächsten Login muss ein neues Passwort gewählt werden. 2FA, Wiederherstellungscodes und Passkeys wurden entfernt, alle Geräte abgemeldet.");sender.sendMessage("§6══════════════════════════════════════════════════════");
                plugin.expansionAudit("Serverkonsole",null,"App-Zugang wiederhergestellt",user);}
            case "owner-transfer"->{if(args.length<2)throw new IllegalArgumentException("/sas app owner-transfer <Benutzer>");String user=AppUsers.username(args[1]);String previous=security.users.transferOwner(user);
                sender.sendMessage("§a"+user+" ist jetzt Hauptadmin (Vollzugriff)."+(previous.isEmpty()||previous.equals(user)?"":" §7Bisher: "+previous+" – behält ein normales Konto mit seinen Rechten."));plugin.expansionAudit("Serverkonsole",null,"Hauptadmin übertragen",(previous.isEmpty()?"-":previous)+" → "+user);}
            case "unlock"->{if(args.length<2)throw new IllegalArgumentException("/sas app unlock <Benutzer>");int n=security.users.unlockAll(args[1]);sender.sendMessage("§a"+n+" gesperrte Geräte von "+AppUsers.username(args[1])+" freigegeben.");}
            default->{
                sender.sendMessage("§6════════ Simplex Panel · Wiederherstellung (nur Konsole) ════════");
                sender.sendMessage("§fsas app users §7– alle App-Benutzer, Hauptadmin, 2FA, Geräte");
                sender.sendMessage("§fsas app owner-reset [Name] §7– Hauptadmin: Einmal-Passwort, 2FA/Passkeys entfernen");
                sender.sendMessage("§fsas app user-reset <Name> §7– beliebiger Benutzer: Einmal-Passwort, 2FA/Passkeys entfernen");
                sender.sendMessage("§fsas app owner-transfer <Name> §7– anderen Benutzer zum Hauptadmin machen");
                sender.sendMessage("§fsas app rename <alt> <neu> §7– Benutzernamen ändern (alles bleibt erhalten)");
                sender.sendMessage("§fsas app unlock <Name> §7– gesperrte Geräte freigeben");
                sender.sendMessage("§fsas app user-enable|user-disable|user-logout <Name>");
                sender.sendMessage("§fsas app user <Name> <Passwort> [Rechte] §7– Benutzer anlegen oder Passwort setzen");
                sender.sendMessage("§7Webpanel: "+webAddress());
            }
        }}catch(Exception e){sender.sendMessage("§cWiederherstellung: "+e.getMessage());}
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
