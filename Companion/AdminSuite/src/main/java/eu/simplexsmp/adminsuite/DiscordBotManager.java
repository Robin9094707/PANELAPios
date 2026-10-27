package eu.simplexsmp.adminsuite;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

final class DiscordBotManager {
    enum StatusSeverity { HEALTHY, WARNING, CRITICAL }
    private enum Access { NONE, READ_ONLY, FULL }

    private static final class PendingAdminAction {
        final DiscordAdminController.Request request;
        final DiscordAdminController.Actor actor;
        final long expiresAt;

        PendingAdminAction(DiscordAdminController.Request request, DiscordAdminController.Actor actor, long expiresAt) {
            this.request = request;
            this.actor = actor;
            this.expiresAt = expiresAt;
        }
    }

    static StatusSeverity statusSeverity(double tps, double warningTps, double criticalTps) {
        double warning = Math.max(10D, Math.min(20D, warningTps));
        double critical = Math.max(5D, Math.min(warning - 0.1D, criticalTps));
        if (tps < critical) return StatusSeverity.CRITICAL;
        if (tps < warning) return StatusSeverity.WARNING;
        return StatusSeverity.HEALTHY;
    }

    static int parseColor(String value, int fallback) {
        if (value == null) return fallback;
        String clean = value.trim().replace("#", "");
        if (!clean.matches("(?i)[0-9a-f]{6}")) return fallback;
        try { return Integer.parseInt(clean, 16); }
        catch (NumberFormatException ignored) { return fallback; }
    }
    static final class PlayerSnapshot {
        final String name;
        final String platform;
        final String emoji;
        final boolean afk;

        PlayerSnapshot(String name, String platform, String emoji, boolean afk) {
            this.name = name;
            this.platform = platform;
            this.emoji = emoji;
            this.afk = afk;
        }
    }

    private static final String API = "https://discord.com/api/v10";
    private static final URI GATEWAY = URI.create("wss://gateway.discord.gg/?v=10&encoding=json");
    private static final Pattern MINECRAFT_FORMATTING = Pattern.compile("(?i)§[0-9A-FK-ORX]|&[0-9A-FK-ORX]");

    private final SimplexAdminSuite plugin;
    private final HttpClient http;
    private final DiscordDiagnostics diagnostics;
    private volatile DiscordPlayerRelay playerRelay;
    private final Map<String, RelayAvatarService.Identity> relayIdentities = new ConcurrentHashMap<>();
    private final ExecutorService executor;
    private final ExecutorService gatewayExecutor;
    private final ExecutorService interactionExecutor;
    private final ScheduledExecutorService heartbeatExecutor;
    private final File stateFile;
    private final DiscordAdminController adminController;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean(false);
    private final AtomicBoolean statusCaptureScheduled = new AtomicBoolean(false);
    private final AtomicBoolean vpnChannelCreateRunning = new AtomicBoolean(false);
    private final AtomicLong connectionGeneration = new AtomicLong();
    private final AtomicLong interactionsReceived = new AtomicLong();
    private final AtomicLong interactionResponses = new AtomicLong();
    private final AtomicLong interactionFailures = new AtomicLong();
    private final Map<String, Long> recentDiscordMessages = new ConcurrentHashMap<>();
    private final Map<String, PendingAdminAction> pendingAdminActions = new ConcurrentHashMap<>();
    private final Map<String, Long> adminCooldowns = new ConcurrentHashMap<>();
    private final Map<String, BigInteger> guildRolePermissions = new ConcurrentHashMap<>();
    private volatile WebSocket gateway;
    private volatile ScheduledFuture<?> heartbeatTask;
    private final AtomicBoolean commandSyncPending = new AtomicBoolean();
    private volatile long lastCommandSyncAttempt;
    private volatile BukkitTask statusTask;
    private volatile long sequence = -1L;
    private volatile String sessionId = "";
    private volatile String applicationId = "";
    private volatile String statusMessageId = "";
    private volatile boolean connected;
    private volatile String lastError = "not configured";
    private volatile int restartSeconds = -1;
    private volatile String guildOwnerId = "";
    private volatile String temporaryPresence = "";
    private volatile long temporaryPresenceUntil;
    private volatile long lastCommandSyncAt;
    private volatile long lastInteractionAt;
    private volatile String lastInteractionError = "none";

    // 7.12 additions use their own bounded worker; gateway heartbeats never wait on HTTP or AI.
    private final java.util.concurrent.ThreadPoolExecutor featureExecutor = new java.util.concurrent.ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
            new java.util.concurrent.ArrayBlockingQueue<>(128),r->{Thread t=new Thread(r,"Simplex-Discord-Features");t.setDaemon(true);return t;});
    // Four bounded lanes preserve each sender's order while one flagged message cannot stall everyone.
    private final java.util.concurrent.ThreadPoolExecutor[] relayReviewExecutors = relayReviewWorkers();
    private static java.util.concurrent.ThreadPoolExecutor[] relayReviewWorkers(){
        java.util.concurrent.ThreadPoolExecutor[] lanes=new java.util.concurrent.ThreadPoolExecutor[4];
        for(int i=0;i<lanes.length;i++){final int lane=i;lanes[i]=new java.util.concurrent.ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
            new java.util.concurrent.ArrayBlockingQueue<>(16),r->{Thread t=new Thread(r,"Simplex-Discord-Relay-Review-"+lane);t.setDaemon(true);return t;});}
        return lanes;
    }
    private record ChatNotice(String message,long expires) { }
    private final Map<String,ChatNotice> chatNotices=new ConcurrentHashMap<>();
    private final Map<String,Long> chatNoticeFallbacks=new ConcurrentHashMap<>();
    private final Map<String,Long> linkNotices=new ConcurrentHashMap<>();
    private final Map<String,FeatureConfirmation> featureConfirmations=new ConcurrentHashMap<>();
    private record FeatureConfirmation(String owner,UUID minecraft,String expected,boolean admin,long expires) { }
    private final AtomicBoolean statisticsPending=new AtomicBoolean();
    private final AtomicBoolean normalStatusPending=new AtomicBoolean();
    private volatile ServerStatistics.Live statusLive;
    private final List<String> statisticsMessageIds=new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile String statisticsChannel="";

    private List<Object> featureCommands(){
        List<Object> commands=new ArrayList<>(adminController.guildCommands());
        commands.add(mapOf("name","link","description","Link your Minecraft account","type",1));
        commands.add(mapOf("name","chatnotice","description","Read your latest private chat moderation feedback","type",1));
        commands.add(mapOf("name","unlink","description","Unlink your Minecraft account","type",1));
        commands.add(mapOf("name","serverstats","description","Live-Serverstatistiken verwalten","type",1,"default_member_permissions","0","options",List.of(
            mapOf("type",1,"name","create","description","Statistik-Nachrichten hier oder im gewählten Kanal erstellen","options",List.of(mapOf("type",7,"name","channel","description","Statistikkanal","channel_types",List.of(0,5),"required",false))),
            mapOf("type",1,"name","refresh","description","Statistiken jetzt aktualisieren"),
            mapOf("type",1,"name","remove","description","Alle Statistik-Nachrichten entfernen (Kanal bleibt erhalten)"),
            mapOf("type",1,"name","status","description","Statistikkanal und Nachrichten anzeigen"))));
        commands.add(mapOf("name","accountadmin","description","Minecraft-/Discord-Konten verwalten","type",1,"default_member_permissions","0","options",List.of(
            mapOf("type",1,"name","list","description","Verknüpfte Konten mit Seiten anzeigen","options",List.of(mapOf("type",4,"name","page","description","Seite","min_value",1,"required",false))),
            mapOf("type",1,"name","link","description","Zwei noch freie Konten manuell verknüpfen","options",List.of(mapOf("type",6,"name","user","description","Discord-Benutzer","required",true),mapOf("type",3,"name","minecraft","description","Minecraft-Spieler","required",true,"autocomplete",true))),
            mapOf("type",1,"name","unlink","description","Verknüpfung nach Bestätigung entfernen","options",List.of(mapOf("type",6,"name","user","description","Discord-Benutzer","required",true))))));
        for(String root:List.of("language","devices"))commands.add(mapOf("name",root,"description",root.equals("language")?"Client language statistics":"Reported device model statistics","type",1,"options",List.of(mapOf("type",1,"name","top10","description","Show the community top 10"))));
        commands.add(mapOf("name","announcement","description","Ankündigungen mit Ablauf verwalten","type",1,"default_member_permissions","0","options",List.of(
            mapOf("type",1,"name","create","description","DE/EN-Ankündigung für spätere Beitritte erstellen","options",List.of(
                mapOf("type",3,"name","english","description","Englischer Text","required",true,"max_length",1000),
                mapOf("type",3,"name","german","description","Deutscher Text (optional)","required",false,"max_length",1000),
                mapOf("type",4,"name","hours","description","Gültigkeit in Stunden (Standard 24)","required",false,"min_value",1,"max_value",720),
                mapOf("type",4,"name","repeat","description","Wiederholung in Minuten, 0=einmal","required",false,"min_value",0,"max_value",10080))),
            mapOf("type",1,"name","list","description","Aktive Ankündigungen mit IDs anzeigen"),
            mapOf("type",1,"name","delete","description","Ankündigung vollständig entfernen","options",List.of(mapOf("type",3,"name","id","description","Ankündigungs-ID","required",true))),
            mapOf("type",1,"name","toggle","description","Ankündigung pausieren oder fortsetzen","options",List.of(mapOf("type",3,"name","id","description","Ankündigungs-ID","required",true))))));
        commands.add(mapOf("name","shop","description","Browse the Simplex Points shop","type",1,"options",List.of(mapOf("type",4,"name","page","description","Page","required",false,"min_value",1,"max_value",1000))));
        commands.add(mapOf("name","myaccount","description","Private details of your linked Minecraft account","type",1,"options",List.of(
            mapOf("type",1,"name","profile","description","Your profile, location and point balance"),
            mapOf("type",1,"name","points","description","Your Simplex Points balance"),
            mapOf("type",1,"name","inventory","description","Read your own inventory","options",List.of(mapOf("type",4,"name","page","description","Page","required",false,"min_value",1,"max_value",1000))))));
        commands.add(mapOf("name","quicknote","description","Persistente Spielernachrichten verwalten","type",1,"default_member_permissions","0","options",List.of(
            mapOf("type",1,"name","create","description","Nach 30 Sekunden Online-Zeit an einen Spieler zustellen","options",List.of(
                mapOf("type",3,"name","minecraft","description","Spielername oder UUID (auch offline)","required",true,"autocomplete",true),
                mapOf("type",3,"name","message","description","Eine Nachricht: bei deutscher Client-Locale Deutsch, sonst Englisch","required",true,"max_length",2000),
                mapOf("type",3,"name","title","description","Titel in derselben Empfängersprache","required",false,"max_length",100),
                mapOf("type",3,"name","mode","description","Zustellmodus","required",false,"choices",List.of(mapOf("name","Menü mit OK","value","menu"),mapOf("name","Chat mit Bestätigung","value","chat"))),
                mapOf("type",4,"name","priority","description","0 Normal / 1 Wichtig / 2 Dringend","required",false,"min_value",0,"max_value",2))),
            mapOf("type",1,"name","status","description","Zustellung und Bestätigung eines Spielers anzeigen","options",List.of(mapOf("type",3,"name","minecraft","description","Spielername oder UUID (auch offline)","required",true,"autocomplete",true),mapOf("type",4,"name","page","description","Seite","required",false,"min_value",1,"max_value",1000))),
            mapOf("type",1,"name","delete","description","Eine noch unbestätigte Nachricht löschen","options",List.of(mapOf("type",3,"name","id","description","Nachrichten-ID","required",true))))));
        DiscordCommandSchema.validate(commands);
        return commands;
    }
    private boolean featureJob(Runnable action){try{featureExecutor.execute(action);return true;}catch(java.util.concurrent.RejectedExecutionException e){lastError="Discord feature queue full";diagnostics.failure("feature queue", "Queue full or stopped; retry shortly");return false;}}
    private void featureReplyJob(String token,Runnable action){if(!featureJob(action))editOriginal(token,new DiscordAdminController.Reply("Service busy","Please try again in a moment.",0xFEE75C,List.of(),""));}
    private HttpResponse<String> featureRequest(String method,String path,String payload)throws Exception{
        HttpResponse<String> response=null;
        for(int attempt=0;attempt<3;attempt++){
            response=requestSync(method,path,payload);if(response.statusCode()!=429)return response;
            double seconds=1;try{Object parsed=Json.parse(response.body());if(parsed instanceof Map<?,?> m && m.get("retry_after") instanceof Number n)seconds=n.doubleValue();}catch(Exception ignored){}
            if(seconds>30)return response;Thread.sleep(Math.max(100L,(long)(seconds*1000)+100));
        }return response;
    }
    private static String optionText(Object options,String key){if(!(options instanceof List<?> list))return "";for(Object raw:list)if(raw instanceof Map<?,?> m){if(key.equals(string(m.get("name"))))return string(m.get("value"));String nested=optionText(m.get("options"),key);if(!nested.isBlank())return nested;}return "";}
    private static String subcommand(Object options){if(options instanceof List<?> l&&!l.isEmpty()&&l.get(0) instanceof Map<?,?> m)return string(m.get("name"));return "";}
    @SuppressWarnings("unchecked") private boolean featureInteraction(Map<String,Object> interaction,Map<String,Object> data,int type){
        String name=string(data.get("name")),custom=string(data.get("custom_id"));
        if(!Set.of("link","unlink","serverstats","accountadmin","language","devices","announcement","chatnotice","shop","myaccount","quicknote").contains(name)&&!custom.startsWith("saslink:")&&!custom.startsWith("saschatnotice:"))return false;
        String id=string(interaction.get("id")),token=string(interaction.get("token")),channel=string(interaction.get("channel_id"));
        Map<String,Object> member=interaction.get("member") instanceof Map?(Map<String,Object>)interaction.get("member"):Map.of();
        Map<String,Object> user=member.get("user") instanceof Map?(Map<String,Object>)member.get("user"):Map.of();String userId=string(user.get("id"));
        if(!guildId().equals(string(interaction.get("guild_id")))||userId.isBlank()){respondReply(id,token,DiscordAdminController.Reply.error("Use this command in the configured Discord server."),true);return true;}
        if ((type==2 && name.equals("chatnotice")) || (type==3 && custom.startsWith("saschatnotice:"))) {
            if(type==3 && !custom.equals("saschatnotice:"+userId)) {
                respondReply(id,token,DiscordAdminController.Reply.error("This private feedback belongs to another user."),true);return true;
            }
            ChatNotice notice=chatNotices.get(userId);
            String feedback=notice!=null&&notice.expires>System.currentTimeMillis()?notice.message:"No recent chat feedback is available. Feedback expires after 24 hours.";
            respondReply(id,token,new DiscordAdminController.Reply("Private chat feedback",feedback,0xFEE75C,List.of(),""),true);return true;
        }
        Access permission=access(member,userId,channel);
        if(type==2&&Set.of("shop","myaccount","quicknote").contains(name)) {
            if(name.equals("quicknote")&&permission!=Access.FULL){respondReply(id,token,DiscordAdminController.Reply.error("Voller Discord-Adminzugriff erforderlich."),true);return true;}
            if(!acceptCooldown(userId)){respondReply(id,token,DiscordAdminController.Reply.error("Please wait a moment."),true);return true;}
            deferInteraction(id,token,false);
            if(name.equals("quicknote")) {
                plugin.runAdminTask(()->{try {
                    String action=subcommand(data.get("options")),answer;
                    if(action.equals("delete")){String note=optionText(data.get("options"),"id");answer=plugin.pointsEconomy.store.deleteNote(note)?"Nachricht gelöscht.":"Keine offene Nachricht mit dieser ID.";plugin.suiteAudit("Discord/"+userId,"Quick Delivery gelöscht",note);}
                    else {UUID uuid=plugin.discordAdminResolvePlayer(optionText(data.get("options"),"minecraft"));if(uuid==null)throw new IllegalArgumentException("Spieler nicht eindeutig gefunden");
                        boolean german=plugin.deliveries.germanRecipient(uuid);
                        if(action.equals("create")){String title=optionText(data.get("options"),"title"),pr=optionText(data.get("options"),"priority");
                            answer="Nachricht gespeichert: "+plugin.deliveries.create("Discord/"+userId,uuid,title.isBlank()?(german?"Nachricht vom Serverteam":"Message from staff"):title,optionText(data.get("options"),"message"),pr.isBlank()?0:Integer.parseInt(pr),!optionText(data.get("options"),"mode").equals("chat"))+"\nSpielersprache: "+(german?"Deutsch":"Englisch");
                        }else answer="Spielersprache: "+(german?"Deutsch":"Englisch")+"\n"+plugin.deliveries.discordStatus(uuid,featurePage(data));
                    }
                    editOriginal(token,DiscordAdminController.Reply.ok("Quick Delivery",truncate(answer,3900),"Bestätigt bedeutet: Spieler hat OK oder /delivery ack verwendet."));
                }catch(Exception e){editOriginal(token,DiscordAdminController.Reply.error("Nicht ausgeführt: "+e.getMessage()));}});return true;
            }
            plugin.runAdminTask(()->{
                try {
                    if(name.equals("shop")){editOriginal(token,new DiscordAdminController.Reply("Simplex Shop",plugin.shop.discordList(featurePage(data)),0x8B5CF6,List.of(),"Buy in Minecraft using /shop."));return;}
                    AccountLinks.Link link=plugin.accountLinks.store.byDiscord(userId);
                    if(link==null){editOriginal(token,DiscordAdminController.Reply.error("Use /link to connect your own Minecraft account first. Never share your link code."));return;}
                    String action=subcommand(data.get("options"));boolean de=plugin.community.german(link.minecraft(),plugin.profileLocale(link.minecraft()));
                    if(action.equals("profile")){editOriginal(token,new DiscordAdminController.Reply(de?"Dein Minecraft-Profil":"Your Minecraft profile",plugin.ownDiscordDetails(link.minecraft()),0x8B5CF6,List.of(),"Private · linked account only"));return;}
                    if(action.equals("points")){editOriginal(token,new DiscordAdminController.Reply(de?"Deine Simplex-Punkte":"Your Simplex Points",plugin.getPointsApi().balance(link.minecraft())+" SP",0x57F287,List.of(),"Private · linked account only"));return;}
                    int page=featurePage(data);String live=plugin.ownLiveInventory(link.minecraft(),page);
                    if(live!=null){editOriginal(token,new DiscordAdminController.Reply(de?"Dein Inventar":"Your inventory",ServerStatistics.label(live),0x8B5CF6,List.of(),"Private · live snapshot"));return;}
                    List<java.io.File> files=plugin.ownInventoryFiles(link.minecraft());
                    featureReplyJob(token,()->{try {
                        AccountLinks.Link current=plugin.accountLinks.store.byDiscord(userId);if(current==null||!current.minecraft().equals(link.minecraft()))throw new IllegalArgumentException("Account link changed. Run the command again.");
                        java.io.File file=files.stream().filter(java.io.File::isFile).findFirst().orElse(null);NbtPlayerData.Snapshot nbt=NbtPlayerData.read(file);
                        List<String> items=nbt.inventory.entrySet().stream().map(e->e.getValue()+" × "+e.getKey()).sorted().toList();
                        String text=file==null?(de?"Keine gespeicherte Inventardatei verfügbar.":"No saved inventory file is available."):SimplexAdminSuite.inventoryPage(items,page,de);
                        if(file!=null&&!nbt.error.isBlank())throw new IllegalArgumentException("Saved inventory could not be read");
                        current=plugin.accountLinks.store.byDiscord(userId);if(current==null||!current.minecraft().equals(link.minecraft()))throw new IllegalArgumentException("Account link changed. Run the command again.");
                        editOriginal(token,new DiscordAdminController.Reply(de?"Dein gespeichertes Inventar":"Your saved inventory",ServerStatistics.label(text),0x8B5CF6,List.of(),"Private · last saved snapshot · item summary"));
                    }catch(Exception e){editOriginal(token,DiscordAdminController.Reply.error("Inventory unavailable: "+e.getMessage()));}});
                }catch(Exception e){editOriginal(token,DiscordAdminController.Reply.error("Service unavailable. Please contact staff."));}
            });return true;
        }
        if(type==4){
            if(!Set.of("accountadmin","quicknote").contains(name)||permission!=Access.FULL){respondAutocomplete(id,token,List.of());return true;}
            FocusedOption focused=findFocused(data.get("options"));String query=focused==null?"":focused.value.toLowerCase(Locale.ROOT);
            plugin.runAdminTask(()->respondAutocomplete(id,token,plugin.expansionProfiles().stream().filter(p->(name.equals("quicknote")||plugin.accountLinks.store.byMinecraft(p.uniqueId())==null)&&p.displayName().toLowerCase(Locale.ROOT).contains(query)).limit(25).map(p->new DiscordAdminController.Choice(name.equals("quicknote")?p.displayName()+" • "+plugin.deliveries.recipientLanguage(p.uniqueId())+" • "+(Bukkit.getPlayer(p.uniqueId())==null?"Offline":"Online"):p.displayName(),p.uniqueId().toString())).toList()));return true;
        }
        if(type==3&&custom.equals("saslink:request")){
            requestLinkCode(id,token,userId);return true;
        }
        if(type==3){
            String key=custom.substring("saslink:".length());FeatureConfirmation c=featureConfirmations.get(key);
            if(c==null||c.expires<System.currentTimeMillis()||!c.owner.equals(userId)||c.admin&&permission!=Access.FULL){respondReply(id,token,new DiscordAdminController.Reply("Confirmation unavailable","Confirmation expired or not authorized. Please run the command again.",0xED4245,List.of(),""),true);return true;}
            featureConfirmations.remove(key);deferInteraction(id,token,false);
            featureReplyJob(token,()->{try{boolean changed=plugin.accountLinks.store.unlink(c.minecraft,c.expected);editOriginal(token,new DiscordAdminController.Reply(c.admin?"Konto entknüpft":"Account unlinked",changed?(c.admin?"Discord-Chat-Zugriff für dieses Konto ist nun gesperrt.":"Discord → Minecraft chat is now disabled. Use /link to connect another account."):"The link changed already. No account was removed.",0x57F287,List.of(),""));plugin.runAdminTask(()->plugin.suiteAudit("Discord/"+userId,"Discord entknüpft",c.minecraft+" / "+c.expected));}catch(Exception e){editOriginal(token,DiscordAdminController.Reply.error("Could not save the unlink. Please contact staff."));}});return true;
        }
        if(type!=2)return true;
        if(name.equals("link")){requestLinkCode(id,token,userId);return true;}
        if(name.equals("unlink")){AccountLinks.Link l=plugin.accountLinks.store.byDiscord(userId);if(l==null)respondReply(id,token,new DiscordAdminController.Reply("No linked account","Use /link to connect your Minecraft account.",0xFEE75C,List.of(),""),true);else featureConfirm(id,token,userId,l,false);return true;}
        if(name.equals("language")||name.equals("devices")){
            if(!acceptCooldown(userId)){respondReply(id,token,DiscordAdminController.Reply.error("Please wait a moment before requesting more statistics."),true);return true;}
            deferInteraction(id,token,false);plugin.runAdminTask(()->editOriginal(token,new DiscordAdminController.Reply(name.equals("language")?"Top 10 client languages":"Top 10 reported devices",plugin.community.publicTop(name.equals("language")?"languages":"devices",false),0x8B5CF6,List.of(),"One latest reported model/locale per profile; client-reported metadata.")));return true;
        }
        if(name.equals("announcement")){
            if(permission!=Access.FULL){respondReply(id,token,DiscordAdminController.Reply.error("Voller Discord-Adminzugriff erforderlich."),true);return true;}
            if(!acceptCooldown(userId)){respondReply(id,token,DiscordAdminController.Reply.error("Bitte kurz warten."),true);return true;}
            deferInteraction(id,token,false);String action=subcommand(data.get("options"));
            plugin.runAdminTask(()->{try{String answer;
                if(action.equals("create")){String hours=optionText(data.get("options"),"hours"),repeat=optionText(data.get("options"),"repeat");answer="Ankündigung erstellt: "+plugin.community.createCampaign("Discord/"+userId,optionText(data.get("options"),"english"),optionText(data.get("options"),"german"),hours.isBlank()?24:Integer.parseInt(hours),repeat.isBlank()?0:Integer.parseInt(repeat));}
                else answer=plugin.community.discordCampaignAction("Discord/"+userId,action,optionText(data.get("options"),"id"));
                editOriginal(token,DiscordAdminController.Reply.ok("Ankündigungen",answer,""));
            }catch(IllegalArgumentException e){editOriginal(token,DiscordAdminController.Reply.error("Ungültige Ankündigung: "+e.getMessage()));}});return true;
        }
        if(permission==Access.NONE||(name.equals("accountadmin")||!subcommand(data.get("options")).equals("status"))&&permission!=Access.FULL){respondReply(id,token,DiscordAdminController.Reply.error("Für diese Aktion fehlt der volle Discord-Adminzugriff."),true);return true;}
        if(!acceptCooldown(userId)){respondReply(id,token,DiscordAdminController.Reply.error("Bitte kurz warten."),true);return true;}
        String action=subcommand(data.get("options"));
        if(name.equals("serverstats")){
            deferInteraction(id,token,false);
            if(action.equals("status")){editOriginal(token,DiscordAdminController.Reply.ok("Statistikstatus",statisticsChannel.isBlank()?"Kein Statistikkanal eingerichtet.":"Kanal: <#"+statisticsChannel+"> • Nachrichten: "+statisticsMessageIds.size(),""));return true;}
            if(action.equals("create")){String selected=optionText(data.get("options"),"channel");String target=selected.isBlank()?channel:selected;
                featureReplyJob(token,()->{try{HttpResponse<String> r=featureRequest("GET","/channels/"+target,null);Object parsed=Json.parse(r.body());if(r.statusCode()!=200||!(parsed instanceof Map<?,?> m)||!guildId().equals(string(m.get("guild_id")))||!Set.of(0,5).contains(intValue(m.get("type"),-1)))throw new IllegalArgumentException("Kanal nicht verfügbar oder nicht dieser Guild zugeordnet.");
                    removeStatisticsMessages();statisticsChannel=target;saveStatisticsState();plugin.runAdminTask(()->{plugin.getConfig().set("discord-bot.statistics.channel-id",target);plugin.saveConfig();plugin.serverStatistics.refresh();plugin.suiteAudit("Discord/"+userId,"Statistikkanal gesetzt",target);});refreshStatistics();editOriginal(token,DiscordAdminController.Reply.ok("Statistikkanal eingerichtet","Die englischen Statistik-Nachrichten werden hier jede Minute aktualisiert.",""));
                }catch(Exception e){editOriginal(token,DiscordAdminController.Reply.error("Statistikkanal konnte nicht gesetzt werden: "+e.getMessage()));}});
            }else if(action.equals("remove")){featureReplyJob(token,()->{try{removeStatisticsMessages();clearStatisticsChannel();editOriginal(token,DiscordAdminController.Reply.ok("Statistikstatus entfernt","Alle verwalteten Statistik-Nachrichten wurden entfernt. Der Discord-Kanal bleibt erhalten.",""));}catch(Exception e){editOriginal(token,DiscordAdminController.Reply.error("Entfernen nicht vollständig möglich. Bot-Rechte prüfen und erneut ausführen."));}});}
            else {plugin.runAdminTask(()->plugin.serverStatistics.refresh());refreshStatistics();editOriginal(token,DiscordAdminController.Reply.ok("Aktualisierung gestartet","Statistiken werden im Hintergrund neu ermittelt.",""));}return true;
        }
        if(action.equals("list")){List<AccountLinks.Link> links=plugin.accountLinks.store.all();int requested=1;try{requested=Integer.parseInt(optionText(data.get("options"),"page"));}catch(Exception ignored){}int max=Math.max(1,(links.size()+14)/15),page=Math.max(1,Math.min(max,requested));StringBuilder out=new StringBuilder("Verknüpfungen: "+links.size()+" • Seite "+page+"/"+max+"\n");for(int i=(page-1)*15;i<Math.min(page*15,links.size());i++){var l=links.get(i);out.append("**").append(ServerStatistics.label(l.name())).append("** ↔ <@").append(l.discord()).append(">\n");}respondReply(id,token,DiscordAdminController.Reply.ok("Verknüpfte Konten",out.toString(),""),true);return true;}
        String discord=optionText(data.get("options"),"user");
        if(action.equals("unlink")){var l=plugin.accountLinks.store.byDiscord(discord);if(l==null)respondReply(id,token,DiscordAdminController.Reply.error("Dieses Discord-Konto ist nicht verknüpft."),true);else featureConfirm(id,token,userId,l,true);return true;}
        String query=optionText(data.get("options"),"minecraft");deferInteraction(id,token,false);
        plugin.runAdminTask(()->{UUID uuid=plugin.discordAdminResolvePlayer(query);var profile=uuid==null?null:plugin.expansionProfile(uuid);if(profile==null){editOriginal(token,DiscordAdminController.Reply.error("Minecraft-Profil nicht eindeutig gefunden."));return;}
            featureReplyJob(token,()->{try{HttpResponse<String> memberResult=featureRequest("GET","/guilds/"+guildId()+"/members/"+discord,null);if(memberResult.statusCode()!=200)throw new IllegalArgumentException("Discord-Benutzer ist in dieser Guild nicht verfügbar.");Object memberData=Json.parse(memberResult.body());if(memberData instanceof Map<?,?> m&&m.get("user") instanceof Map<?,?> u&&Boolean.TRUE.equals(u.get("bot")))throw new IllegalArgumentException("Bot-Konten können nicht verknüpft werden.");var l=plugin.accountLinks.store.link(uuid,discord,profile.displayName());syncLinkedNickname(l,profile.displayName());editOriginal(token,DiscordAdminController.Reply.ok("Konten verknüpft",ServerStatistics.label(l.name())+" ↔ <@"+l.discord()+">", ""));plugin.runAdminTask(()->plugin.suiteAudit("Discord/"+userId,"Discord manuell verknüpft",l.minecraft()+" / "+l.discord()));}catch(Exception e){editOriginal(token,DiscordAdminController.Reply.error("Verknüpfung fehlgeschlagen: "+e.getMessage()));}});
        });return true;
    }
    private void featureConfirm(String id,String token,String owner,AccountLinks.Link link,boolean admin){
        long now=System.currentTimeMillis();featureConfirmations.entrySet().removeIf(e->e.getValue().expires<now);String key=UUID.randomUUID().toString();featureConfirmations.put(key,new FeatureConfirmation(owner,link.minecraft(),link.discord(),admin,now+120000));
        respondReply(id,token,new DiscordAdminController.Reply(admin?"Konto entknüpfen?":"Unlink your account?",admin?"Discord-Chat-Zugriff wird gesperrt. Ein neuer Account kann anschließend verknüpft werden.":"Discord → Minecraft chat will be disabled. You can link another account afterwards.",0xFEE75C,List.of(mapOf("type",1,"components",List.of(mapOf("type",2,"style",4,"label",admin?"Entknüpfen bestätigen":"Confirm unlink","custom_id","saslink:"+key)))),""),true);
    }
    private static int featurePage(Map<String,Object> data){String page=optionText(data.get("options"),"page");try{return page.isBlank()?1:Math.max(1,Math.min(1000,Integer.parseInt(page)));}catch(Exception e){return 1;}}
    private boolean acceptFeatureCooldown(String user,long ms){long now=System.currentTimeMillis();linkNotices.entrySet().removeIf(e->e.getValue()<now-300000);Long last=linkNotices.put(user,now);return last==null||now-last>=ms;}
    private void requestLinkCode(String id,String token,String userId){
        if(!acceptFeatureCooldown("code:"+userId,2000)){respondReply(id,token,new DiscordAdminController.Reply("Please wait","Try again in a few seconds.",0xFEE75C,List.of(),""),true);return;}
        deferInteraction(id,token,false);
        featureReplyJob(token,()->{
            AccountLinks.Link link=plugin.accountLinks.store.byDiscord(userId);
            if(link!=null){editOriginal(token,new DiscordAdminController.Reply("Account already linked",ServerStatistics.label(link.name())+" · Use /unlink to link another account.",0x57F287,List.of(),""));return;}
            try{String code=plugin.accountLinks.store.issue(userId,plugin.getConfig().getInt("discord-bot.linking.code-ttl-seconds",300));editOriginal(token,new DiscordAdminController.Reply("Link Minecraft ↔ Discord",linkInstructions(code),0x8B5CF6,List.of(),""));}
            catch(Exception e){diagnostics.failure("link code generation",e.getClass().getSimpleName());editOriginal(token,new DiscordAdminController.Reply("Service busy","Please try again later.",0xED4245,List.of(),""));}
        });
    }
    private String linkInstructions(String code){
        int seconds=Math.max(60,Math.min(900,plugin.getConfig().getInt("discord-bot.linking.code-ttl-seconds",300)));
        return "**Please verify your Minecraft account.** Copy this command and run it on the Minecraft server:\n```text\n/link "+code+"\n```\nExpires in up to "+seconds+" seconds. This connects your accounts and syncs your Discord nickname to your Minecraft name. **Never share this code or run a code received from another person. Link only your own Discord account to your own Minecraft account. The linked Discord account can access your private inventory, points and location.** Use /unlink to revoke access.";
    }
    private boolean directMessage(String user,String text){
        try{
            HttpResponse<String> opened=featureRequest("POST","/users/@me/channels",Json.stringify(mapOf("recipient_id",user)));
            if(opened.statusCode()/100!=2)return false;
            Object obj=Json.parse(opened.body());String channel=obj instanceof Map<?,?> m?string(m.get("id")):"";
            if(!channel.matches("[0-9]{5,25}")){diagnostics.failure("direct message","Discord returned no valid DM channel");return false;}
            HttpResponse<String> sent=featureRequest("POST","/channels/"+channel+"/messages",Json.stringify(mapOf("content",truncate(text,2000),"allowed_mentions",mapOf("parse",List.of()))));
            return sent.statusCode()/100==2;
        }catch(Exception e){return false;}
    }
    private static Map<String,Object> linkNotice(String user){
        return mapOf("content","<@"+user+"> **Please verify before chatting.** I could not send you a private message. Click **Get link code** below, or use `/link` in this Discord server. Your code will be visible only to you.",
                "allowed_mentions",mapOf("parse",List.of(),"users",List.of(user)),
                "components",List.of(mapOf("type",1,"components",List.of(mapOf("type",2,"style",1,"label","Get link code","custom_id","saslink:request")))));
    }
    private void sendLinkFallback(String channel,String user){
        try{
            var response=featureRequest("POST","/channels/"+channel+"/messages",Json.stringify(linkNotice(user)));
            if(response.statusCode()/100!=2)return;
            Object raw=Json.parse(response.body());String id=raw instanceof Map<?,?> m?string(m.get("id")):"";
            if(!id.matches("[0-9]{5,25}"))return;
            heartbeatExecutor.schedule(()->{if(running.get())request("DELETE","/channels/"+channel+"/messages/"+id,null);},60,TimeUnit.SECONDS);
        }catch(Exception e){diagnostics.failure("verification notice",e.getClass().getSimpleName()+"; use Discord /link");}
    }
    private void receiveLinkedChat(Map<String,Object> message,String user,String originalName){
        String channel=string(message.get("channel_id")),id=string(message.get("id"));AccountLinks.Link link=plugin.accountLinks.store.byDiscord(user);
        if(link==null&&plugin.getConfig().getBoolean("discord-bot.linking.required",true)){
            request("DELETE","/channels/"+channel+"/messages/"+id,null);
            if(acceptFeatureCooldown("notice:"+user,30000)){
                boolean queued=featureJob(()->{try{String code=plugin.accountLinks.store.issue(user,plugin.getConfig().getInt("discord-bot.linking.code-ttl-seconds",300));if(!directMessage(user,linkInstructions(code)))sendLinkFallback(channel,user);}catch(Exception e){diagnostics.failure("verification delivery",e.getClass().getSimpleName()+"; use Discord /link");sendLinkFallback(channel,user);}});
                if(!queued)request("POST","/channels/"+channel+"/messages",Json.stringify(linkNotice(user)));
            }return;
        }
        String text=RelayText.fromDiscord(message,plugin.getConfig().getBoolean("discord-bot.relay.attachments",true));
        String name=link==null?originalName:link.name();long receivedAt=System.currentTimeMillis();
        if(!plugin.discordChatModerationEnabled()){
            deliverLinkedChat(user,link,name,text,channel,receivedAt);
            return;
        }
        try{relayReviewExecutors[Math.floorMod(user.hashCode(),relayReviewExecutors.length)].execute(()->{
            if(System.currentTimeMillis()-receivedAt>30000){if(plugin.getConfig().getBoolean("discord-bot.relay-ai.fail-open",true)){deliverLinkedChat(user,link,name,text,channel,System.currentTimeMillis());return;}request("DELETE","/channels/"+channel+"/messages/"+id,null);notifyChatDecision(channel,user,OpenAiIntegration.ChatDecision.block("Your message expired while waiting for moderation. Please try again.","Moderation queue timeout"));return;}
            OpenAiIntegration.ChatDecision decision=plugin.reviewDiscordChat(name,text);
            if(!decision.allowed){try{HttpResponse<String> r=featureRequest("DELETE","/channels/"+channel+"/messages/"+id,null);if(r.statusCode()!=204&&r.statusCode()!=404)lastError="Blocked relay deletion HTTP "+r.statusCode();}catch(Exception e){lastError="Blocked relay deletion failed";}notifyChatDecision(channel,user,decision);plugin.runAdminTask(()->plugin.suiteAudit("Discord/"+user,"KI-Discord-Nachricht blockiert",decision.reason));return;}
            deliverLinkedChat(user,link,name,text,channel,receivedAt);
            if(decision.warning)notifyChatDecision(channel,user,decision);
        });}catch(java.util.concurrent.RejectedExecutionException e){if(plugin.getConfig().getBoolean("discord-bot.relay-ai.fail-open",true)){lastError="Discord AI queue unavailable; message forwarded according to fail-open policy";deliverLinkedChat(user,link,name,text,channel,receivedAt);return;}request("DELETE","/channels/"+channel+"/messages/"+id,null);lastError="Discord relay moderation queue full; message held";featureJob(()->notifyChatDecision(channel,user,OpenAiIntegration.ChatDecision.block("Chat moderation is busy. Please try again in a moment.","Moderation queue full")));}
    }
    /** Normal Discord messages cannot be ephemeral; use a DM plus an owner-bound private interaction. */
    private void notifyChatDecision(String channel,String user,OpenAiIntegration.ChatDecision decision){
        long now=System.currentTimeMillis();
        chatNotices.entrySet().removeIf(e->e.getValue().expires<now);
        chatNoticeFallbacks.entrySet().removeIf(e->now-e.getValue()>60000);
        if(chatNotices.size()>=1000&&!chatNotices.containsKey(user))chatNotices.keySet().stream().findFirst().ifPresent(chatNotices::remove);
        String guidance=decision.playerMessage.isBlank()?"Please keep the chat friendly and respectful.":decision.playerMessage;
        String feedback=(decision.allowed?"**Your message was delivered with a warning.**":"**Your message was not forwarded to Minecraft.**")+"\n"+guidance;
        chatNotices.put(user,new ChatNotice(feedback,now+86400000));
        if(directMessage(user,feedback+"\nYou can also use `/chatnotice` in the server; only you can see its reply."))return;
        if(now-chatNoticeFallbacks.getOrDefault(user,0L)<30000)return;
        chatNoticeFallbacks.put(user,now);
        try{
            Map<String,Object> payload=mapOf("content","<@"+user+"> Private chat feedback is available. Open it below or use `/chatnotice`.",
                "allowed_mentions",mapOf("parse",List.of(),"users",List.of(user)),
                "components",List.of(mapOf("type",1,"components",List.of(mapOf("type",2,"style",2,"label","View private feedback","custom_id","saschatnotice:"+user)))));
            HttpResponse<String> result=featureRequest("POST","/channels/"+channel+"/messages",Json.stringify(payload));
            if(result.statusCode()/100!=2){diagnostics.failure("private chat feedback","Could not post feedback button; user can use /chatnotice");return;}
            Object parsed=Json.parse(result.body());String messageId=parsed instanceof Map<?,?> m?string(m.get("id")):"";
            if(messageId.matches("[0-9]{5,25}"))heartbeatExecutor.schedule(()->{if(running.get())request("DELETE","/channels/"+channel+"/messages/"+messageId,null);},120,TimeUnit.SECONDS);
        }catch(Exception e){diagnostics.failure("private chat feedback","Private feedback retained for /chatnotice");}
    }
    private void deliverLinkedChat(String user,AccountLinks.Link link,String name,String text,String channel,long receivedAt){
        // Both paths recheck identity/channel immediately before forwarding.
        plugin.runAdminTask(()->{AccountLinks.Link current=plugin.accountLinks.store.byDiscord(user);if(System.currentTimeMillis()-receivedAt>60000||!running.get()||!relayChannelId().equals(channel)||!relayEnabled())return;if(plugin.getConfig().getBoolean("discord-bot.linking.required",true)&&(current==null||link!=null&&!current.minecraft().equals(link.minecraft())))return;
            var p=current==null?null:plugin.expansionProfile(current.minecraft());plugin.relayDiscordToMinecraft(p==null?name:p.displayName(),text);
        });
    }
    void validateLinkTarget(String discord)throws Exception {
        if(!discord.matches("[0-9]{5,25}")||!configured()||!enabled())throw new IllegalArgumentException("Discord-Bot oder Benutzer-ID nicht gültig.");
        HttpResponse<String> r=featureRequest("GET","/guilds/"+guildId()+"/members/"+discord,null);
        Object raw=Json.parse(r.body());
        if(r.statusCode()!=200||!(raw instanceof Map<?,?> m))throw new IllegalArgumentException("Discord-Mitglied nicht verfügbar.");
        if(m.get("user") instanceof Map<?,?> u&&Boolean.TRUE.equals(u.get("bot")))throw new IllegalArgumentException("Bot-Konten sind nicht erlaubt.");
    }
    void syncLinkedNickname(AccountLinks.Link link,String name){if(!enabled()||!configured()||!plugin.getConfig().getBoolean("discord-bot.linking.sync-nicknames",true))return;featureJob(()->{AccountLinks.Link current=plugin.accountLinks.store.byMinecraft(link.minecraft());if(current==null||!current.discord().equals(link.discord()))return;try{HttpResponse<String> r=featureRequest("PATCH","/guilds/"+guildId()+"/members/"+link.discord(),Json.stringify(mapOf("nick",truncate(name,32))));if(r.statusCode()/100!=2)lastError="Nickname sync HTTP "+r.statusCode()+"; check Manage Nicknames and role hierarchy";}catch(Exception e){lastError="Nickname sync unavailable";}});}
    void openStatisticsSettings(org.bukkit.entity.Player p){
        if(!plugin.adminMenuAllowed(p,"discord.statistics")||!plugin.has(p,"simplexadminsuite.discordstatus"))return;
        List<AdminUpdateMenu.Button> b=new ArrayList<>();
        b.add(new AdminUpdateMenu.Button("Statistikkanal setzen","Discord /serverstats create oder Kanal-ID hier eingeben",org.bukkit.Material.MAP,()->plugin.adminUpdate.prompt(p,"Discord-Statistikkanal-ID",statisticsChannel,v->{if(!plugin.has(p,"simplexadminsuite.discordstatus"))return;if(!v.matches("[0-9]{5,25}")){plugin.menuError(p,"§cUngültige Kanal-ID.");return;}featureJob(()->{try{HttpResponse<String> r=featureRequest("GET","/channels/"+v,null);Object raw=Json.parse(r.body());if(r.statusCode()!=200||!(raw instanceof Map<?,?> m)||!guildId().equals(string(m.get("guild_id")))||!Set.of(0,5).contains(intValue(m.get("type"),-1)))throw new IllegalArgumentException("Kanal nicht verfügbar");removeStatisticsMessages();statisticsChannel=v;saveStatisticsState();plugin.runAdminTask(()->{plugin.getConfig().set("discord-bot.statistics.channel-id",v);plugin.saveConfig();plugin.serverStatistics.refresh();openStatisticsSettings(p);});}catch(Exception e){plugin.runAdminTask(()->plugin.menuError(p,"§cStatistikkanal konnte nicht gesetzt werden."));}});}))); 
        b.add(new AdminUpdateMenu.Button("Jetzt aktualisieren","Statistik-Neuberechnung im Hintergrund",org.bukkit.Material.CLOCK,()->{plugin.serverStatistics.refresh();refreshStatistics();plugin.menuNotice(p,"§aAktualisierung gestartet.");}));
        b.add(new AdminUpdateMenu.Button("Statistikstatus entfernen","Entfernt nur die verwalteten Nachrichten",org.bukkit.Material.BARRIER,()->featureJob(()->{try{removeStatisticsMessages();clearStatisticsChannel();plugin.runAdminTask(()->plugin.menuNotice(p,"§aStatistikstatus entfernt."));}catch(Exception e){plugin.runAdminTask(()->plugin.menuError(p,"§cBot-Rechte prüfen; Nachrichten konnten nicht vollständig entfernt werden."));}})));
        b.add(new AdminUpdateMenu.Button("Discord-KI-Prüfung: "+(plugin.getConfig().getBoolean("discord-bot.relay-ai.enabled",false)?"AN":"AUS"),"Nur Discord → Minecraft im festgelegten Chatkanal",org.bukkit.Material.COMMAND_BLOCK,()->{if(!plugin.has(p,"simplexadminsuite.discordstatus"))return;plugin.getConfig().set("discord-bot.relay-ai.enabled",!plugin.getConfig().getBoolean("discord-bot.relay-ai.enabled",false));plugin.saveConfig();openStatisticsSettings(p);}));
        b.add(new AdminUpdateMenu.Button("Kontoverknüpfungen","Zuordnungen verwalten",org.bukkit.Material.TRIPWIRE_HOOK,()->plugin.accountLinks.openAdmin(p)));
        b.add(new AdminUpdateMenu.Button("Zurück","Admin-Zentrale",org.bukkit.Material.OAK_DOOR,()->plugin.openMainFromModule(p)));plugin.adminUpdate.show(p,"Discord-Serverstatistiken",b,0);
    }
    void refreshStatistics(){if(!running.get()||statisticsChannel.isBlank()||plugin.serverStatistics==null||!statisticsPending.compareAndSet(false,true))return;
        try{featureExecutor.execute(()->{try{List<Map<String,Object>> pages=plugin.serverStatistics.payloads();if(pages.isEmpty())return;String channel=statisticsChannel;
            for(int i=0;i<pages.size();i++){
                if(!channel.equals(statisticsChannel))return;String payload=Json.stringify(pages.get(i));HttpResponse<String> r=null;
                if(i<statisticsMessageIds.size()){r=featureRequest("PATCH","/channels/"+channel+"/messages/"+statisticsMessageIds.get(i),payload);if(r.statusCode()==404){if(unknownChannel(r)){clearStatisticsChannel();return;}statisticsMessageIds.set(i,"");}}
                if(i>=statisticsMessageIds.size()||statisticsMessageIds.get(i).isBlank()){r=featureRequest("POST","/channels/"+channel+"/messages",payload);if(r.statusCode()==404&&unknownChannel(r)){clearStatisticsChannel();return;}if(r.statusCode()/100==2){Object raw=Json.parse(r.body());String messageId=raw instanceof Map<?,?> m?string(m.get("id")):"";if(messageId.isBlank())throw new IllegalStateException("Discord returned no message ID");if(i>=statisticsMessageIds.size())statisticsMessageIds.add(messageId);else statisticsMessageIds.set(i,messageId);saveStatisticsState();}}
                if(r!=null&&r.statusCode()/100!=2){lastError="Statistics HTTP "+r.statusCode();return;}
            }
            while(statisticsMessageIds.size()>pages.size()){String old=statisticsMessageIds.get(statisticsMessageIds.size()-1);var r=featureRequest("DELETE","/channels/"+channel+"/messages/"+old,null);if(r.statusCode()!=204&&r.statusCode()!=404)return;statisticsMessageIds.remove(statisticsMessageIds.size()-1);}saveStatisticsState();
        }catch(Exception e){lastError="Statistics update: "+e.getClass().getSimpleName();}finally{statisticsPending.set(false);}});}catch(java.util.concurrent.RejectedExecutionException e){statisticsPending.set(false);}
    }
    private static boolean unknownChannel(HttpResponse<String> r){try{Object raw=Json.parse(r.body());return raw instanceof Map<?,?> m&&intValue(m.get("code"),0)==10003;}catch(Exception e){return false;}}
    private void removeStatisticsMessages()throws Exception {
        if(statisticsChannel.isBlank())return;
        while(!statisticsMessageIds.isEmpty()){
            String id=statisticsMessageIds.get(0);
            if(!id.isBlank()){
                var response=featureRequest("DELETE","/channels/"+statisticsChannel+"/messages/"+id,null);
                if(response.statusCode()!=204&&response.statusCode()!=404)throw new java.io.IOException("Discord HTTP "+response.statusCode());
            }
            statisticsMessageIds.remove(0);saveStatisticsState();
        }
    }
    private void clearStatisticsChannel(){statisticsChannel="";statisticsMessageIds.clear();saveStatisticsState();plugin.runAdminTask(()->{plugin.getConfig().set("discord-bot.statistics.channel-id","");plugin.saveConfig();});}
    private void loadStatisticsState(){
        statisticsChannel=plugin.getConfig().getString("discord-bot.statistics.channel-id","").trim();
        java.nio.file.Path path=plugin.getDataFolder().toPath().resolve("discord-statistics-state.properties");if(!java.nio.file.Files.exists(path))return;
        try{java.util.Properties p=new java.util.Properties();try(var in=java.nio.file.Files.newBufferedReader(path)){p.load(in);}statisticsChannel=p.getProperty("channel",statisticsChannel);for(String id:p.getProperty("messages","").split(","))if(id.matches("[0-9]{5,25}"))statisticsMessageIds.add(id);}catch(Exception e){plugin.getLogger().warning("Discord-Statistikstatus konnte nicht geladen werden.");}
    }
    private void saveStatisticsState(){try{java.util.Properties p=new java.util.Properties();p.setProperty("channel",statisticsChannel);p.setProperty("messages",String.join(",",statisticsMessageIds));AtomicState.save(plugin.getDataFolder().toPath().resolve("discord-statistics-state.properties"),p,"Persistent statistics message IDs");}catch(Exception e){plugin.getLogger().warning("Discord-Statistikstatus konnte nicht gespeichert werden.");}}

    DiscordBotManager(SimplexAdminSuite plugin) {
        this.plugin = plugin;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "Simplex-Discord-Bot");
            thread.setDaemon(true);
            return thread;
        });
        this.gatewayExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "Simplex-Discord-Gateway");
            thread.setDaemon(true);
            return thread;
        });
        this.interactionExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "Simplex-Discord-Interactions");
            thread.setDaemon(true);
            thread.setPriority(Math.min(Thread.MAX_PRIORITY, Thread.NORM_PRIORITY + 1));
            return thread;
        });
        this.heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "Simplex-Discord-Heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        this.diagnostics = new DiscordDiagnostics(plugin.getLogger()::warning);
        this.stateFile = new File(plugin.getDataFolder(), "discord-bot-state.yml");
        this.adminController = new DiscordAdminController(plugin, this);
        loadState();
        loadStatisticsState();
    }

    synchronized void start() {
        stopRuntime(false);
        if (!enabled()) {
            lastError = "disabled";
            return;
        }
        if (!configured()) {
            lastError = "token or guild ID missing";
            return;
        }
        running.set(true);
        startPlayerRelay();
        for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) rememberPlayer(player);
        restartSeconds = -1;
        guildOwnerId = "";
        guildRolePermissions.clear();
        moderationTask = moderationExecutor.scheduleWithFixedDelay(this::sendModerationQueue, 300, 300, TimeUnit.MILLISECONDS);
        connectGateway();
        int seconds = Math.max(10, Math.min(300, plugin.getConfig().getInt("discord-bot.status.update-interval-seconds", 10)));
        statusTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            updateStatus(false);
            long interval=Math.max(300,plugin.getConfig().getInt("discord-bot.admin.command-sync-interval-seconds",3600))*1000L;
            if(!applicationId.isBlank()&&System.currentTimeMillis()-lastCommandSyncAttempt>=interval)registerCommands("Regelmäßige Synchronisierung");
        }, 20L, seconds * 20L);
        updateStatus(false);
    }

    synchronized void reload() {
        stopRuntime(false);
        loadState();
        start();
    }

    synchronized void stop(boolean restarting) {
        if (enabled() && configured() && !statusChannelId().isBlank()) {
            StatusSnapshot snapshot = snapshot(restarting ? StatusMode.RESTARTING : StatusMode.OFFLINE);
            try {
                Future<?> update = executor.submit(() -> writeStatus(snapshot));
                update.get(4, TimeUnit.SECONDS);
            } catch (Exception ignored) { }
        }
        stopRuntime(true);
        saveState();
    }

    private synchronized void stopRuntime(boolean finalStop) {
        running.set(false);
        DiscordPlayerRelay previousRelay = playerRelay;
        playerRelay = null;
        if (previousRelay != null) previousRelay.stop(finalStop);
        relayIdentities.clear();
        connectionGeneration.incrementAndGet();
        connected = false;
        reconnectScheduled.set(false);
        if (moderationTask != null) { moderationTask.cancel(false); moderationTask = null; }
        synchronized (moderationQueue) { moderationQueue.clear(); }
        if (statusTask != null) {
            try { statusTask.cancel(); } catch (Exception ignored) { }
            statusTask = null;
        }
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
            heartbeatTask = null;
        }
        WebSocket socket = gateway;
        gateway = null;
        if (socket != null) {
            try { socket.sendClose(WebSocket.NORMAL_CLOSURE, "Plugin reload"); } catch (Exception ignored) { }
        }
        if (finalStop) {
            for(var lane:relayReviewExecutors)lane.shutdownNow();
            chatNotices.clear();chatNoticeFallbacks.clear();
            featureExecutor.shutdown();
            moderationExecutor.shutdownNow();
            heartbeatExecutor.shutdownNow();
            gatewayExecutor.shutdownNow();
            interactionExecutor.shutdownNow();
            executor.shutdown();
        }
    }

    boolean enabled() {
        return plugin.getConfig().getBoolean("discord-bot.enabled", false);
    }

    boolean configured() {
        return !token().isBlank() && !guildId().isBlank();
    }

    String summary() {
        if (!enabled()) return "Bot deaktiviert";
        if (!configured()) return "Bot unvollständig eingerichtet";
        if (!connected) return "Bot verbindet • " + lastError;
        return "Bot verbunden • Admin " + (adminEnabled() ? "aktiv" : "aus")
                + " • Relay " + (relayChannelId().isBlank() ? "nicht eingerichtet" : relayEnabled() ? "aktiv" : "aus")
                + " • Status " + (statusChannelId().isBlank() ? "nicht eingerichtet" : "aktiv");
    }

    String adminSummary() {
        long age = lastCommandSyncAt <= 0L ? -1L : Math.max(0L, (System.currentTimeMillis() - lastCommandSyncAt) / 1000L);
        long interactionAge = lastInteractionAt <= 0L ? -1L : Math.max(0L, (System.currentTimeMillis() - lastInteractionAt) / 1000L);
        return summary() + "\nGuild: `" + (guildId().isBlank() ? "not configured" : guildId()) + "`"
                + "\nSlash commands: `/sasadmin` + `/ssmp` • last sync " + (age < 0 ? "not completed" : age + "s ago")
                + "\nInline commands: " + (inlineEnabled() ? "enabled (`" + inlinePrefix() + "`)" : "disabled")
                + "\nConsole bridge: " + (plugin.getConfig().getBoolean("discord-bot.admin.console.enabled", false) ? "allowlist enabled" : "disabled")
                + "\nInteraction pipeline: " + interactionsReceived.get() + " received • " + interactionResponses.get()
                + " delivered • " + interactionFailures.get() + " failed"
                + "\nLast interaction: " + (interactionAge < 0 ? "none" : interactionAge + "s ago")
                + ("none".equals(lastInteractionError) ? "" : " • last error: " + truncate(lastInteractionError, 180))
                + "\nBotprofil: " + profileStatus + "\nKI-Protokoll: " + (plugin.getConfig().getString("discord-bot.moderation-log.ai-channel-id", "").isBlank() ? "nicht eingerichtet" : "eingerichtet")
                + " • Ghost-Protokoll: " + (plugin.getConfig().getString("discord-bot.moderation-log.ghost-channel-id", "").isBlank() ? "nicht eingerichtet" : "eingerichtet")
                + "\nModerationsprotokolle: " + droppedModerationLogs.get() + " nicht zugestellt (lokales Audit bleibt erhalten)"
                + "\nPending confirmations: " + pendingAdminActions.size() + "\n" + relayDiagnostics();
    }

    String tokenMasked() {
        String token = token();
        if (token.isBlank()) return "nicht gesetzt";
        return "••••••••" + token.substring(Math.max(0, token.length() - 4));
    }

    String messageIdMasked() {
        return statusMessageId.isBlank() ? "nicht gesetzt" : "…" + statusMessageId.substring(Math.max(0, statusMessageId.length() - 6));
    }

    void setEnabled(String actor, boolean value) {
        plugin.getConfig().set("discord-bot.enabled", value);
        plugin.saveConfig();
        plugin.getLogger().info("Discord-Bot wurde durch " + actor + " " + (value ? "aktiviert" : "deaktiviert") + ".");
        reload();
    }

    void setSecret(String path, String value) {
        plugin.getConfig().set(path, value == null ? "" : value.trim());
        plugin.saveConfig();
        reload();
    }

    void useStatusChannel(String actor, String channelId) {
        plugin.getConfig().set("discord-bot.status-channel-id", channelId == null ? "" : channelId.trim());
        plugin.saveConfig();
        statusMessageId = "";
        saveState();
        plugin.getLogger().info("Discord-Statuskanal wurde durch " + actor + " aktualisiert.");
        reload();
    }

    void useRelayChannel(String actor, String channelId) {
        plugin.getConfig().set("discord-bot.relay-channel-id", channelId == null ? "" : channelId.trim());
        plugin.saveConfig();
        plugin.getLogger().info("Discord-Relaykanal wurde durch " + actor + " aktualisiert.");
        reload();
    }

    String createVpnLogChannel(String actor, String sourceChannelId) {
        if (!enabled() || !configured()) return "Der Discord-Bot ist nicht vollständig eingerichtet.";
        if (applicationId.isBlank()) return "Die Discord-Gateway-Bereitschaft fehlt noch. Bitte warte kurz und versuche es erneut.";
        if (!vpnLogChannelId().isBlank()) return "Es ist bereits ein VPN-Protokollkanal konfiguriert: <#" + vpnLogChannelId() + ">.";
        if (!vpnChannelCreateRunning.compareAndSet(false, true)) return "Die Erstellung des VPN-Protokollkanals läuft bereits.";
        String requestedName = plugin.getConfig().getString("discord-bot.vpn-log.auto-channel-name", "vpn-protokoll");
        String channelName = requestedName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-").replaceAll("-+", "-");
        if (channelName.isBlank()) channelName = "vpn-protokoll";
        if (channelName.length() > 100) channelName = channelName.substring(0, 100);
        String finalChannelName = channelName;
        String cleanSource = sourceChannelId == null ? "" : sourceChannelId.replaceAll("[^0-9]", "");
        executor.execute(() -> {
            try {
                List<Map<String, Object>> overwrites = new ArrayList<>();
                overwrites.add(mapOf("id", guildId(), "type", 0, "allow", "0", "deny", "1024"));
                if (!applicationId.isBlank()) overwrites.add(mapOf("id", applicationId, "type", 1, "allow", "84992", "deny", "0"));
                Set<String> roles = new java.util.LinkedHashSet<>(plugin.getConfig().getStringList("discord-bot.admin.role-ids"));
                roles.addAll(plugin.getConfig().getStringList("discord-bot.admin.read-only-role-ids"));
                String alertRole = plugin.getConfig().getString("discord-bot.vpn-log.role-id", "").replaceAll("[^0-9]", "");
                if (!alertRole.isBlank()) roles.add(alertRole);
                for (String role : roles) {
                    String cleanRole = role == null ? "" : role.replaceAll("[^0-9]", "");
                    if (!cleanRole.isBlank() && !cleanRole.equals(guildId())) overwrites.add(mapOf(
                            "id", cleanRole, "type", 0, "allow", "84992", "deny", "0"));
                }
                String payload = Json.stringify(mapOf("name", finalChannelName, "type", 0,
                        "topic", "VPN-/Proxy-Erkennungen der Simplex Admin Suite " + plugin.getDescription().getVersion() + "",
                        "permission_overwrites", overwrites));
                HttpResponse<String> response = requestSync("POST", "/guilds/" + guildId() + "/channels", payload);
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalStateException("Discord HTTP " + response.statusCode() + ": " + truncate(response.body(), 300));
                }
                Object parsed = Json.parse(response.body());
                String createdId = parsed instanceof Map ? string(((Map<?, ?>) parsed).get("id")) : "";
                if (createdId.isBlank()) throw new IllegalStateException("Discord lieferte keine Kanal-ID.");
                plugin.runAdminTask( () -> {
                    plugin.getConfig().set("discord-bot.vpn-log.channel-id", createdId);
                    plugin.getConfig().set("discord-bot.vpn-log.enabled", true);
                    plugin.saveConfig();
                    plugin.vpnAudit(actor, null, "Discord-VPN-Protokollkanal erstellt", createdId);
                    sendEmbed(createdId, "🛡️ VPN-Protokoll eingerichtet",
                            "Dieser private Kanal empfängt ab jetzt VPN-/Proxy-Erkennungen, Whitelist-Änderungen und manuelle IP-Prüfungen.\n**Eingerichtet von:** "
                                    + escapeMarkdown(cleanText(actor, 120)), 0x5865F2);
                });
            } catch (Exception error) {
                lastError = "VPN channel create: " + error.getClass().getSimpleName() + ": " + safe(error.getMessage());
                if (!cleanSource.isBlank()) sendAdminChannelReply(cleanSource, DiscordAdminController.Reply.error(
                        "Der private VPN-Protokollkanal konnte nicht erstellt werden: " + cleanText(error.getMessage(), 500)));
            } finally {
                vpnChannelCreateRunning.set(false);
            }
        });
        return "Der private Discord-Kanal `#" + finalChannelName + "` wird erstellt und anschließend automatisch als VPN-Protokollkanal gespeichert.";
    }

    void setRelayEnabled(String actor, boolean value) {
        plugin.getConfig().set("discord-bot.relay.enabled", value);
        plugin.saveConfig();
        plugin.getLogger().info("Discord-Chat-Relay wurde durch " + actor + " " + (value ? "aktiviert" : "deaktiviert") + ".");
        reload();
    }

    boolean relayEnabled() {
        return plugin.getConfig().getBoolean("discord-bot.relay.enabled", true);
    }

    void resetStatusMessage(String actor) {
        statusMessageId = "";
        saveState();
        plugin.getLogger().info("Persistente Discord-Bot-Statusnachricht wurde durch " + actor + " zurückgesetzt.");
        updateStatus(false);
    }

    void reconnect(String actor) {
        plugin.getLogger().info("Discord-Bot-Neuverbindung durch " + actor + " angefordert.");
        reload();
    }

    void updateNow() {
        updateStatus(false);
    }

    void markRestarting(int seconds) {
        restartSeconds = Math.max(0, seconds);
        updateStatus(true);
    }

    void clearRestarting() {
        restartSeconds = -1;
        updateStatus(false);
    }

    void reloadPlayerRelay() {
        DiscordPlayerRelay old = playerRelay; playerRelay = null;
        if (old != null) old.stop(false);
        if (enabled() && configured()) startPlayerRelay();
    }
    void relaySleep(String name, int sleeping, int required, int eligible, String world) {
        if (!canRelay("sleep")) return;
        sendEmbed(relayChannelId(), "Time to rest", "🛏️ **" + RelayText.discord(name,100) + "** went to bed in **"
                + RelayText.discord(world,100) + "**.\nSleeping: **" + sleeping + "/" + required + "** needed • **" + eligible + "** eligible players", 0x5865F2);
    }
    void adminUpdateAlert(String message) {
        if (!enabled() || !configured() || !plugin.getConfig().getBoolean("discord-bot.alerts.enabled", true)) return;
        String channel = plugin.getConfig().getString("discord-bot.alerts.channel-id", "").trim();
        if (!channel.isBlank()) sendEmbed(channel, "Admin-Hinweis", RelayText.discord(message,1500), 0xFEE75C);
    }

    private void startPlayerRelay() {
        String base = "discord-bot.relay.";
        RelayAvatarService avatars = new RelayAvatarService(
                plugin.getConfig().getBoolean(base + "avatars.bedrock-enabled", true),
                plugin.getConfig().getInt(base + "avatars.cache-hours", 24),
                plugin.getConfig().getString(base + "avatars.java-url", RelayAvatarService.DEFAULT_JAVA_URL),
                plugin.getDataFolder().toPath().resolve("relay-avatars.properties"),
                plugin.getConfig().getInt(base + "avatars.refresh-messages", 20));
        playerRelay = new DiscordPlayerRelay(new DiscordPlayerRelay.Settings(token(), relayChannelId(),
                plugin.getConfig().getBoolean(base + "player-webhooks", true),
                plugin.getConfig().getInt(base + "queue-capacity", 256),
                plugin.getConfig().getInt(base + "max-message-age-seconds", 120)), avatars, plugin.getLogger()::warning);
    }

    void rememberPlayer(org.bukkit.entity.Player player) {
        RelayAvatarService.Identity identity = plugin.discordRelayIdentity(player);
        relayIdentities.put(player.getName(), identity);
        DiscordPlayerRelay relay = playerRelay;
        if (relay != null && canRelay("chat") && plugin.getConfig().getBoolean("discord-bot.relay.player-webhooks", true)) relay.warm(identity);
    }

    String appAvatar(UUID id){DiscordPlayerRelay relay=playerRelay;if(relay==null)return "";for(var identity:relayIdentities.values())if(id.equals(identity.uuid()))return relay.avatar(identity);return "";}
    void forgetPlayer(String name) { relayIdentities.remove(name); }

    String relayDiagnostics() {
        DiscordPlayerRelay relay = playerRelay;
        return relay == null ? "Spieler-Relay ist nicht gestartet. Bot-Token, Server-ID und Aktivierung prüfen." : relay.summary();
    }

    void clearRelayAvatars() { DiscordPlayerRelay relay = playerRelay; if (relay != null) relay.clearAvatars(); }

    void relayMinecraftChat(String playerName, String decoration, String message) {
        if (!canRelay("chat")) return;
        DiscordPlayerRelay relay = playerRelay;
        if (relay != null) relay.chat(relayIdentities.get(playerName), playerName, decoration, message);
        else sendMessage(relayChannelId(), "**" + RelayText.discord(playerName, 100) + ":** " + RelayText.discord(message, 1700));
    }

    void relayFirstJoin(String playerName) {
        if (!canRelay("first-joins")) return;
        sendEmbed(relayChannelId(), "Welcome to the server!", "👋 **" + RelayText.discord(playerName, 100)
                + "** joined for the first time. Welcome to the community!", 0xFEE75C);
    }

    void relayAdvancement(String playerName, String title) {
        if (!canRelay("advancements")) return;
        sendEmbed(relayChannelId(), "Advancement unlocked", "🏆 **" + RelayText.discord(playerName, 100)
                + "** completed **" + RelayText.discord(title, 180) + "**.", 0xFEE75C);
    }

    void relayWorldChange(String playerName, String world) {
        if (!canRelay("world-changes")) return;
        sendEmbed(relayChannelId(), "Dimension changed", "🌍 **" + RelayText.discord(playerName, 100)
                + "** entered **" + RelayText.discord(world, 100) + "**.", 0x5865F2);
    }

    void relayJoin(String playerName, String decoration, String platform, String emoji) {
        if (!canRelay("joins")) return;
        String decoratedName = decoratedName(playerName, decoration);
        String description = emoji + " **" + escapeMarkdown(decoratedName) + "** joined the SSMP server with **" + escapeMarkdown(platform) + "**.\n"
                + "Players online: **" + plugin.visibleOnlineCount() + "/" + plugin.serverMaxPlayers() + "**";
        sendEmbed(relayChannelId(), "Player joined", description, 0x57F287);
        updateStatus(false);
    }

    void relayQuit(String playerName, String decoration, String platform, String emoji) {
        if (!canRelay("quits")) return;
        int online = plugin.visibleOnlineCountExcluding(Bukkit.getPlayerExact(playerName) == null ? null : Bukkit.getPlayerExact(playerName).getUniqueId());
        String decoratedName = decoratedName(playerName, decoration);
        String description = emoji + " **" + escapeMarkdown(decoratedName) + "** left the SSMP server.\n"
                + "Players online: **" + online + "/" + plugin.serverMaxPlayers() + "**";
        sendEmbed(relayChannelId(), "Player left", description, 0xED4245);
        updateStatus(false);
    }

    void relayDeath(String deathMessage) {
        if (!canRelay("deaths")) return;
        sendEmbed(relayChannelId(), "Death message", "☠️ " + escapeMassMentions(cleanText(deathMessage, 1500)), 0x747F8D);
    }

    private record ModerationEnvelope(String channel, String body, int retries) { }
    private final java.util.Deque<ModerationEnvelope> moderationQueue = new java.util.ArrayDeque<>();
    private final java.util.concurrent.ScheduledExecutorService moderationExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Simplex-Discord-Moderation"); t.setDaemon(true); return t;
    });
    private volatile java.util.concurrent.ScheduledFuture<?> moderationTask;
    private volatile long moderationRetryAt;
    private final AtomicLong droppedModerationLogs = new AtomicLong();
    private void sendModerationQueue() {
        if (!running.get() || System.currentTimeMillis() < moderationRetryAt) return;
        ModerationEnvelope envelope;
        synchronized (moderationQueue) { envelope = moderationQueue.pollFirst(); }
        if (envelope == null) return;
        try {
            HttpResponse<String> response = requestSync("POST", "/channels/" + envelope.channel + "/messages", envelope.body, 8);
            if (response.statusCode() == 429 || response.statusCode() >= 500) {
                double seconds = 2.0;
                try { Object data = Json.parse(response.body()); if (data instanceof Map<?,?> m && m.get("retry_after") instanceof Number n) seconds = n.doubleValue(); } catch (RuntimeException ignored) { }
                moderationRetryAt = System.currentTimeMillis() + (long)(Math.max(0.3,Math.min(60,seconds))*1000);
                if (envelope.retries < 3) synchronized (moderationQueue) { moderationQueue.addFirst(new ModerationEnvelope(envelope.channel,envelope.body,envelope.retries+1)); }
                else droppedModerationLogs.incrementAndGet();
            } else if (response.statusCode() < 200 || response.statusCode() >= 300) {
                droppedModerationLogs.incrementAndGet(); lastError = "Moderationsprotokoll HTTP " + response.statusCode();
            }
        } catch (Exception error) {
            droppedModerationLogs.incrementAndGet(); lastError = "Moderationsprotokoll: " + error.getClass().getSimpleName();
        }
    }
    private volatile String profileStatus = "noch nicht synchronisiert";
    private volatile String lastProfileDescription = "";
    private volatile String profileApplicationId = "";
    private final AtomicBoolean moderationChannelCreating = new AtomicBoolean();
    private final DiscordPresentation presentation = new DiscordPresentation();
    private final AtomicBoolean profilePending = new AtomicBoolean();
    void refreshProfile() {
        if (!running.get() || !enabled() || !configured() || !plugin.getConfig().getBoolean("discord-bot.profile.update-enabled", true)) return;
        if (!presentation.profileDue(System.currentTimeMillis()) || !profilePending.compareAndSet(false,true)) return;
        plugin.runAdminTask(() -> {
            if (!running.get() || !plugin.isEnabled()) { profilePending.set(false); return; }
            String description = DiscordPresentation.profile(cleanMultiline(plugin.getConfig().getString("discord-bot.profile.description", "Simplex SMP • Java & Bedrock crossplay. Server status and community information."),400),statusLive,plugin.getConfig().getBoolean("discord-bot.profile.dynamic-statistics",true),plugin.discordPlayerSnapshots().stream().map(p->p.name).toList());
            long interval = Math.max(600,Math.min(86400,plugin.getConfig().getLong("discord-bot.profile.update-interval-seconds",900)))*1000L;
            presentation.profileAttempt(System.currentTimeMillis(),interval);
            if (description.equals(lastProfileDescription)) { profilePending.set(false); return; }
            long generation = connectionGeneration.get();
            try { executor.execute(() -> {
                try {
                    if (!running.get() || generation != connectionGeneration.get()) return;
                    HttpResponse<String> response=requestSync("PATCH","/applications/@me",Json.stringify(mapOf("description",description)));
                    if (generation != connectionGeneration.get()) return;
                    if (response.statusCode()/100==2) { lastProfileDescription=description; profileStatus="synchronisiert"; }
                    else { profileStatus="Discord HTTP "+response.statusCode(); }
                    if(response.statusCode()==429) {
                        double seconds=60;
                        try { Object parsed=Json.parse(response.body()); if(parsed instanceof Map<?,?> m && m.get("retry_after") instanceof Number n) seconds=n.doubleValue(); } catch(RuntimeException ignored) { }
                        try { seconds=Math.max(seconds,Double.parseDouble(response.headers().firstValue("Retry-After").orElse("0"))); } catch(NumberFormatException ignored) { }
                        presentation.profileBackoff(System.currentTimeMillis(),seconds);
                    } else if(response.headers().firstValue("X-RateLimit-Remaining").orElse("1").equals("0")) {
                        try { presentation.profileBackoff(System.currentTimeMillis(),Double.parseDouble(response.headers().firstValue("X-RateLimit-Reset-After").orElse("0"))); } catch(NumberFormatException ignored) { }
                    }
                } catch(Exception error) { profileStatus="Profiländerung fehlgeschlagen: "+error.getClass().getSimpleName(); }
                finally { profilePending.set(false); }
            }); } catch(java.util.concurrent.RejectedExecutionException error) { profilePending.set(false); }
        });
    }
    void welcomeNewPlayer(String name) {
        if(!plugin.getConfig().getBoolean("discord-bot.presence.welcome-new-players",true))return;
        temporaryPresence="Welcome new player • "+cleanText(name,80);
        temporaryPresenceUntil=System.currentTimeMillis()+Math.max(20,Math.min(300,plugin.getConfig().getInt("discord-bot.presence.welcome-duration-seconds",60)))*1000L;
        updateNow();
    }
    void relayModerationLog(String type, String player, String uuid, String reason, String message, long timestamp) {
        if (!enabled() || !configured()) return;
        String channel = plugin.getConfig().getString("discord-bot.moderation-log." + type + "-channel-id", "").trim();
        if (!channel.matches("[0-9]{15,22}")) return;
        long seconds = Math.max(0L, timestamp / 1000L);
        List<Map<String,Object>> fields = List.of(
            mapOf("name", "Spieler", "value", escapeMarkdown(cleanText(player, 80)) + "\n`" + cleanText(uuid, 40) + "`", "inline", false),
            mapOf("name", "Grund / Aktion", "value", escapeMassMentions(escapeMarkdown(cleanMultiline(reason, 900))), "inline", false),
            mapOf("name", "Nachricht / Versuch", "value", escapeMassMentions(escapeMarkdown(cleanMultiline(message.isBlank() ? "—" : message, 1000))), "inline", false),
            mapOf("name", "Zeitpunkt", "value", "<t:" + seconds + ":F> • <t:" + seconds + ":R>", "inline", false));
        Map<String,Object> embed = mapOf("title", type.equals("ai") ? "🤖 KI-Moderationsprotokoll" : "👻 Ghost-Bann-Protokoll", "fields", fields,
            "color", type.equals("ai") ? 0xFEE75C : 0x9B59B6, "timestamp", java.time.Instant.ofEpochMilli(timestamp).toString(),
            "footer", mapOf("text", plugin.brandName()+" " + plugin.getDescription().getVersion() + " • Privates Moderationsprotokoll"));
        if (!running.get()) return;
        synchronized (moderationQueue) {
            if (moderationQueue.size() >= 200) { droppedModerationLogs.incrementAndGet(); return; }
            moderationQueue.addLast(new ModerationEnvelope(channel, Json.stringify(mapOf("embeds", List.of(embed), "allowed_mentions", mapOf("parse", List.of()))), 0));
        }
    }
    void relayAdminAction(String type,String actor,String target,String action,String detail,long timestamp){
        if(!enabled()||!configured()||!running.get())return;
        String channel=plugin.getConfig().getString("discord-bot.moderation-log."+type+"-channel-id","").trim();
        if(channel.isBlank()&&Set.of("bans","mutes").contains(type))channel=plugin.getConfig().getString("discord-bot.moderation-log.punishments-channel-id","").trim();
        if(channel.isBlank())channel=plugin.getConfig().getString("discord-bot.moderation-log.admin-channel-id","").trim();
        if(channel.isBlank())channel=plugin.getConfig().getString("discord-bot.admin.audit-channel-id","").trim();
        if(!channel.matches("[0-9]{15,22}"))return;
        var fields=List.of(mapOf("name","Admin / Auslöser","value",escapeMassMentions(escapeMarkdown(cleanText(actor,100))),"inline",true),
            mapOf("name","Ziel","value",escapeMassMentions(cleanText(target,80)),"inline",true),
            mapOf("name","Aktion / Änderung","value",escapeMassMentions(escapeMarkdown(cleanMultiline(action+"\n"+detail,1000))),"inline",false),
            mapOf("name","Zeitpunkt","value","<t:"+(timestamp/1000)+":F> • <t:"+(timestamp/1000)+":R>","inline",false));
        var embed=mapOf("title",ModerationControl.logLabel(type),"fields",fields,"color",0x5865F2,"timestamp",java.time.Instant.ofEpochMilli(timestamp).toString());
        synchronized(moderationQueue){if(moderationQueue.size()>=200){droppedModerationLogs.incrementAndGet();return;}moderationQueue.addLast(new ModerationEnvelope(channel,Json.stringify(mapOf("embeds",List.of(embed),"allowed_mentions",mapOf("parse",List.of()))),0));}
    }
    void listLogChannels(java.util.function.Consumer<Map<String,String>> callback){
        executor.execute(()->{
            Map<String,String> channels=new LinkedHashMap<>();
            try{
                if(enabled()&&configured()){
                    var response=requestSync("GET","/guilds/"+guildId()+"/channels",null);
                    if(response.statusCode()==200 && Json.parse(response.body()) instanceof List<?> list)
                        for(Object item:list)if(item instanceof Map<?,?> channel){int type=intValue(channel.get("type"),-1);String id=string(channel.get("id"));if((type==0||type==5)&&id.matches("[0-9]{15,22}"))channels.put(id,string(channel.get("name")));}
                }
            }catch(Exception error){lastError="Kanalliste: "+error.getClass().getSimpleName();}
            if(running.get())plugin.runAdminTask(()->callback.accept(channels));
        });
    }
    String setClientProbeChannel(String actor,String id){
        if(!enabled()||!configured())return "Discord-Bot ist nicht bereit.";
        if(!featureJob(()->{try{HttpResponse<String> response=featureRequest("GET","/channels/"+id,null);Object data=Json.parse(response.body());if(response.statusCode()!=200||!(data instanceof Map<?,?> channel)||!guildId().equals(string(channel.get("guild_id")))||!Set.of(0,5).contains(intValue(channel.get("type"),-1)))throw new IllegalArgumentException("Kanal nicht im konfigurierten Server verfügbar");plugin.runAdminTask(()->{plugin.getConfig().set("discord-bot.moderation-log.client-probes-channel-id",id);plugin.saveConfig();plugin.suiteAudit(actor,"Client alarm channel",id);sendEmbed(id,"Client-Prüfungen","Dieser Kanal empfängt jetzt Client-Prüfungen.",0x57F287);});}catch(Exception e){plugin.getLogger().warning("Client channel setup failed: "+e.getMessage());}}))return "Discord-Warteschlange ist voll.";
        return "Client-Kanal wird geprüft und gespeichert.";
    }
    /** Dossiers only leave the server after checking explicit private channel overwrites. */
    private volatile long privateDossierBlockedUntil;
    private volatile String privateDossierState="Noch kein Versand";
    String privateDossierStatus(){return !enabled()?"Discord-Bot deaktiviert":!configured()?"Bot-Token oder Server-ID fehlt":!running.get()?"Discord-Bot nicht gestartet":privateDossierState;}
    boolean sendSecurityDossier(SecurityLogOutbox.Message message){
        if(System.currentTimeMillis()<privateDossierBlockedUntil){privateDossierState="Discord-Ratenlimit; automatischer Wiederholungsversuch";return false;}
        if(!enabled()||!configured()||!running.get()||!message.channel().matches("[0-9]{15,22}"))return false;
        try{
            HttpResponse<String> check=featureRequest("GET","/channels/"+message.channel(),null);
            Object parsed=Json.parse(check.body());if(check.statusCode()!=200||!(parsed instanceof Map<?,?> channel)||!guildId().equals(string(channel.get("guild_id")))||!Set.of(0,5).contains(intValue(channel.get("type"),-1))){privateDossierState="Protokollkanal nicht erreichbar (HTTP "+check.statusCode()+"); Kanal-ID und Bot-Rechte prüfen";return false;}
            Set<String> users=new HashSet<>();users.add(applicationId);if(guildOwnerId!=null)users.add(guildOwnerId);
            if(!PrivateLogChannel.allowed(channel,guildId(),new HashSet<>(plugin.getConfig().getStringList("discord-bot.admin.role-ids")),users)){privateDossierState=lastError="Privater Protokollkanal: @everyone sperren und nur konfigurierte Admin-Rollen freigeben";return false;}
            String boundary="SAS-"+UUID.randomUUID();String payload=Json.stringify(mapOf("allowed_mentions",mapOf("parse",List.of()),"embeds",List.of(mapOf("title",cleanText(message.title(),250),"description",RelayText.discord(message.summary(),3800),"color",0x5865F2,"timestamp",java.time.Instant.now().toString())),"attachments",List.of(mapOf("id",0,"filename","client-details.json","description","Vom Server beobachtete Client-Daten; keine bewiesene Geräteidentität"))));
            String body="--"+boundary+"\r\nContent-Disposition: form-data; name=\"payload_json\"\r\nContent-Type: application/json\r\n\r\n"+payload+"\r\n--"+boundary+"\r\nContent-Disposition: form-data; name=\"files[0]\"; filename=\"client-details.json\"\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n"+message.dossier()+"\r\n--"+boundary+"--\r\n";
            HttpRequest request=HttpRequest.newBuilder(URI.create(API+"/channels/"+message.channel()+"/messages")).timeout(java.time.Duration.ofSeconds(20)).header("Authorization","Bot "+token()).header("User-Agent","SimplexAdminSuite").header("Content-Type","multipart/form-data; boundary="+boundary).POST(HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8)).build();
            HttpResponse<String> response=http.send(request,HttpResponse.BodyHandlers.ofString());if(response.statusCode()==429){long seconds=60;try{seconds=Math.max(5,Math.min(86400,(long)Math.ceil(Double.parseDouble(response.headers().firstValue("Retry-After").orElse("60")))));}catch(NumberFormatException ignored){}privateDossierBlockedUntil=System.currentTimeMillis()+seconds*1000;}if(response.statusCode()/100!=2){privateDossierState="Discord-Versand HTTP "+response.statusCode()+"; Kanalrechte oder Ratenlimit prüfen";recordHttpFailure("POST","/channels/"+message.channel()+"/messages",response.statusCode(),response.body());return false;}privateDossierState="Privater Versand erfolgreich";return true;
        }catch(InterruptedException e){Thread.currentThread().interrupt();return false;}catch(Exception e){privateDossierState=lastError="Private dossier: "+e.getClass().getSimpleName();return false;}
    }

    void clientProbeAlert(String player,UUID uuid,String status,List<String> clients,String detail){
        if(plugin.securityHub!=null&&!status.equals("APPROVED")){plugin.securityHub.clientMessage(uuid,player,"Client-Prüfung • "+status,String.join("\n",clients)+"\n"+detail);return;}
        String channel=plugin.getConfig().getString("discord-bot.moderation-log.client-probes-channel-id","");if(!channel.matches("[0-9]{15,22}")||!enabled()||!configured())return;
        long detected=clients.stream().filter(line->line.contains(": DETECTED")).count(),negative=clients.stream().filter(line->line.contains(": NOT_DETECTED")).count();String resultLines=String.join("\n",clients);
        String body="**Spieler:** "+RelayText.discord(player,80)+" (`"+uuid+"`)\n**Status:** "+RelayText.discord(status,40)+"\n**Prüfungen:** "+clients.size()+" • 🔴 "+detected+" • 🟢 "+negative+" • 🟡 "+(clients.size()-detected-negative)+"\n\n**Ergebnisse:**\n"+RelayText.discord(resultLines,2500)+(resultLines.length()>2500?"\n… Vollständiger Verlauf im SAS-Menü.":"")+"\n\n"+RelayText.discord(detail,700)+"\n\n`/sasadmin client status player:"+RelayText.discord(player,80)+"`";
        featureJob(()->sendEmbed(channel,"Client-Prüfung • "+cleanText(status,40),body,status.equals("APPROVED")?0x57F287:clients.stream().anyMatch(line->line.contains(": DETECTED"))||Set.of("TEMPBAN","KICK","TEAM_REVIEW").contains(status)?0xED4245:status.equals("COMPLETE")?0x57F287:0xFEE75C));
    }
    String createModerationChannel(String type, String actor) {
        if (!ModerationControl.LOG_TYPES.contains(type)) return "Unbekannter Protokolltyp.";
        if (!enabled() || !configured() || applicationId.isBlank()) return "Discord-Bot ist noch nicht bereit.";
        String path = "discord-bot.moderation-log." + type + "-channel-id";
        if (!plugin.getConfig().getString(path, "").isBlank()) return "Es ist bereits ein Kanal konfiguriert.";
        if (!moderationChannelCreating.compareAndSet(false, true)) return "Eine Kanalerstellung läuft bereits.";
        executor.execute(() -> {
            try {
                List<Map<String,Object>> overwrites = new ArrayList<>();
                overwrites.add(mapOf("id", guildId(), "type", 0, "allow", "0", "deny", "1024"));
                overwrites.add(mapOf("id", applicationId, "type", 1, "allow", "84992", "deny", "0"));
                for (String role : plugin.getConfig().getStringList("discord-bot.admin.role-ids")) if (role.matches("[0-9]{15,22}"))
                    overwrites.add(mapOf("id", role, "type", 0, "allow", "84992", "deny", "0"));
                String name = switch(type){case "alt-accounts"->"alt-account-hinweise";case "join-details"->"private-beitrittsdaten";case "client-probes"->"client-pruefungen";case "ai"->"ki-moderation";case "ghost"->"ghost-protokoll";case "punishments"->"strafen-protokoll";case "bans"->"ban-protokoll";case "mutes"->"mute-protokoll";case "vanish"->"vanish-protokoll";case "devices"->"geraete-protokoll";default->"admin-protokoll";};
                HttpResponse<String> response = requestSync("POST", "/guilds/" + guildId() + "/channels", Json.stringify(mapOf("name", name, "type", 0,
                    "topic", "Privates " + name + " • Simplex Admin Suite " + plugin.getDescription().getVersion() + "", "permission_overwrites", overwrites)));
                if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalStateException("HTTP " + response.statusCode());
                Object parsed = Json.parse(response.body());
                String id = parsed instanceof Map ? string(((Map<?,?>)parsed).get("id")) : "";
                if (!id.matches("[0-9]{15,22}")) throw new IllegalStateException("Kanal-ID fehlt");
                plugin.runAdminTask( () -> {
                    plugin.asAdmin(actor,()->{plugin.getConfig().set(path, id); plugin.saveConfig(); plugin.suiteAudit(actor, "Discord-Protokollkanal erstellt", type + " • " + id);});
                    sendEmbed(id, "Protokollkanal eingerichtet", "Dieser private Kanal empfängt ab jetzt " + ModerationControl.logLabel(type) + ".", 0x57F287);
                });
            } catch (Exception error) { lastError = "Protokollkanal: " + error.getClass().getSimpleName(); plugin.getLogger().warning("Discord-Protokollkanal konnte nicht erstellt werden: " + error.getMessage()); }
            finally { moderationChannelCreating.set(false); }
        });
        return "Privater Protokollkanal wird erstellt und automatisch gespeichert. Der Bot benötigt Kanäle verwalten.";
    }

    void relayMaintenance(boolean active){
        if(!enabled()||!configured()||!relayEnabled()||relayChannelId().isBlank())return;
        sendEmbed(relayChannelId(),active?"Server maintenance":"Maintenance completed",active?"The server is now in maintenance mode. Please try again later.":"Maintenance mode has ended. The server is accepting players again.",active?0xFEE75C:0x57F287);
    }
    void relayServerStarted() {
        if (!canRelay("lifecycle")) return;
        sendEmbed(relayChannelId(), "Server has started", plugin.qolMaintenanceActive()?"🟠 **Simplex SMP has started in maintenance mode.**":"🟢 **Simplex SMP has started and is ready for players.**", 0x57F287);
    }

    void relayServerStopped(boolean restarting) {
        if (!canRelay("lifecycle")) return;
        sendEmbed(relayChannelId(), restarting ? "Server is restarting" : "Server has stopped",
                restarting ? "🟠 **Simplex SMP is restarting. Players can reconnect shortly.**" : "🔴 **Simplex SMP has stopped.**",
                restarting ? 0xFEE75C : 0xED4245);
    }

    void relayAfk(String playerName, boolean away) {
        if (!canRelay("afk")) return;
        sendEmbed(relayChannelId(), away ? "Player is now AFK" : "Player returned",
                (away ? "💤 **" : "✅ **") + escapeMarkdown(cleanText(playerName, 64)) + "** " + (away ? "is now AFK." : "is no longer AFK."),
                away ? 0x747F8D : 0x57F287);
    }

    void relayPollStarted(String question, List<String> options, long seconds, String actor) {
        if (!canRelay("polls")) return;
        StringBuilder description = new StringBuilder("📊 **").append(escapeMarkdown(cleanText(question, 500))).append("**\n\n");
        for (int i = 0; i < options.size(); i++) description.append("**").append(i + 1).append(".** ").append(escapeMarkdown(cleanText(options.get(i), 180))).append("\n");
        description.append("\nUse **/vote** in Minecraft. Duration: **").append(Math.max(1L, seconds)).append(" seconds**.");
        if (actor != null && !actor.isBlank()) description.append("\nStarted by **").append(escapeMarkdown(cleanText(actor, 64))).append("**.");
        sendEmbed(relayChannelId(), "New server vote", description.toString(), 0x5865F2);
    }

    void relayPollVote(String playerName, String question, List<String> options) {
        if (!canRelay("polls")) return;
        String description = "🗳️ **" + escapeMarkdown(cleanText(playerName, 64)) + "** voted in **"
                + escapeMarkdown(cleanText(question, 300)) + "**.\nSelection: **" + escapeMarkdown(cleanText(String.join(", ", options), 800)) + "**";
        sendEmbed(relayChannelId(), "Vote submitted", description, 0x5865F2);
    }

    void relayPollEnded(String question, String result) {
        if (!canRelay("polls")) return;
        sendEmbed(relayChannelId(), "Server vote ended", "📊 **" + escapeMarkdown(cleanText(question, 500)) + "**\n\n" + escapeMarkdown(cleanMultiline(result, 2500)), 0x5865F2);
    }

    void relayTrimScheduled(List<String> targets, int seconds, String actor) {
        if (!canRelay("trimming")) return;
        String description = "🛠️ World maintenance has been scheduled. All players will be disconnected in **" + Math.max(0, seconds) + " seconds**.\n"
                + "**Dimensions:** " + escapeMarkdown(cleanText(String.join(", ", targets), 1200))
                + "\n**Scheduled by:** " + escapeMarkdown(cleanText(actor, 80));
        sendEmbed(relayChannelId(), "World trim scheduled", description, 0xFEE75C);
    }

    void relayTrimStarted(List<String> targets, String actor) {
        if (!canRelay("trimming")) return;
        sendEmbed(relayChannelId(), "World trimming has started",
                "🟣 The server is locked while unused chunks are processed. It will restart automatically after completion.\n"
                        + "**Dimensions:** " + escapeMarkdown(cleanText(String.join(", ", targets), 1200))
                        + "\n**Started by:** " + escapeMarkdown(cleanText(actor, 80)), 0x9B59B6);
    }

    void relayTrimProgress(String target, int progress, String eta) {
        if (!canRelay("trimming")) return;
        sendEmbed(relayChannelId(), "World trim progress",
                "🛠️ **" + Math.max(0, Math.min(100, progress)) + "%** complete\n**Current dimension:** "
                        + escapeMarkdown(cleanText(target, 300)) + "\n**Estimated time remaining:** " + escapeMarkdown(cleanText(eta, 80)), 0x5865F2);
    }

    void relayTrimFinished(long saved, long before, long after, List<String> targets) {
        if (!canRelay("trimming")) return;
        sendEmbed(relayChannelId(), "World trimming completed",
                "✅ World maintenance completed safely. The server is restarting now.\n**Before:** " + formatBytes(before)
                        + "\n**After:** " + formatBytes(after) + "\n**Space released:** " + formatBytes(saved)
                        + "\n**Dimensions:** " + escapeMarkdown(cleanText(String.join(", ", targets), 1000)), 0x57F287);
    }

    void relayTrimFailed(String reason, List<String> targets) {
        if (!canRelay("trimming")) return;
        sendEmbed(relayChannelId(), "World trimming failed safely",
                "🔴 The safety lock remains active and the server will restart before players can reconnect.\n**Reason:** "
                        + escapeMarkdown(cleanText(reason, 1200)) + "\n**Dimensions:** "
                        + escapeMarkdown(cleanText(String.join(", ", targets), 1000)), 0xED4245);
    }

    void relayTrimCancelled(String actor) {
        if (!canRelay("trimming")) return;
        sendEmbed(relayChannelId(), "World trim cancelled",
                "⚪ The scheduled world trim was cancelled before any world files were modified.\n**Cancelled by:** "
                        + escapeMarkdown(cleanText(actor, 80)), 0x747F8D);
    }

    void relayTrimOverrideRequired(long chunks, String actor) {
        if (!canRelay("trimming")) return;
        sendEmbed(relayChannelId(), "World trim safety decision required",
                "🟠 **" + Math.max(1L, chunks) + " chunks** remained loaded after all automatic save/unload retries. "
                        + "No region files have been modified. The server is locked while the initiating operator decides whether to cancel safely or use the emergency override.\n"
                        + "**Initiating operator:** " + escapeMarkdown(cleanText(actor, 80)), 0xFEE75C);
    }

    void relayTrimOverrideAccepted(long chunks, String actor) {
        if (!canRelay("trimming")) return;
        sendEmbed(relayChannelId(), "World trim emergency override confirmed",
                "🟣 The initiating operator explicitly approved continuing after **" + Math.max(1L, chunks)
                        + " chunks** remained loaded. A final save/unload pass is running before MCA Selector starts.\n"
                        + "**Confirmed by:** " + escapeMarkdown(cleanText(actor, 80)), 0x9B59B6);
    }

    void playerStateChanged() { updateStatus(false); }

    void relaySecurityAlert(String title, String description) {
        if (!enabled() || !configured() || !plugin.getConfig().getBoolean("discord-bot.alerts.enabled", true)) return;
        String channel = plugin.getConfig().getString("discord-bot.alerts.channel-id", "").trim();
        if (channel.isBlank()) channel = relayChannelId();
        if (channel.isBlank()) return;
        String role = plugin.getConfig().getString("discord-bot.alerts.role-id", "").replaceAll("[^0-9]", "");
        String content = role.isBlank() ? "" : "<@&" + role + ">";
        Map<String, Object> embed = mapOf("title", "🚨 " + cleanText(title, 240), "description", truncate(escapeMassMentions(cleanMultiline(description, 3800)), 4000), "color", 0xED4245,
                "footer", mapOf("text", "Simplex SMP • Security alert"));
        Map<String, Object> allowed = role.isBlank() ? mapOf("parse", List.of()) : mapOf("parse", List.of(), "roles", List.of(role));
        Map<String, Object> body = mapOf("content", content, "embeds", List.of(embed), "allowed_mentions", allowed);
        request("POST", "/channels/" + channel + "/messages", Json.stringify(body));
    }

    void relayVpnDetection(VpnDetectionLog.Detection detection) {
        if (detection == null || !vpnLogReady()) return;
        if (detection.blocked() && !plugin.getConfig().getBoolean("discord-bot.vpn-log.notify-blocked", true)) return;
        if (!detection.blocked() && !plugin.getConfig().getBoolean("discord-bot.vpn-log.notify-suspicious-allowed", true)) return;
        String channel = vpnLogChannelId();
        boolean includeIp = plugin.getConfig().getBoolean("discord-bot.vpn-log.include-ip", true);
        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(mapOf("name", "Spieler", "value", "**" + escapeMarkdown(cleanText(detection.playerName(), 80)) + "**\n`"
                + (detection.uuid() == null ? "UUID unbekannt" : detection.uuid()) + "`", "inline", true));
        fields.add(mapOf("name", "Plattform", "value", detection.bedrock() ? "Bedrock / Floodgate" : "Java", "inline", true));
        fields.add(mapOf("name", "Ergebnis", "value", detection.blocked() ? "⛔ Blockiert" : "⚠️ Auffällig, zugelassen", "inline", true));
        if (includeIp) fields.add(mapOf("name", "IP-Adresse", "value", "`" + escapeMarkdown(cleanText(detection.ip(), 80)) + "`", "inline", true));
        fields.add(mapOf("name", "Land", "value", detection.country().isBlank() ? "Unbekannt" : escapeMarkdown(cleanText(detection.country(), 20)), "inline", true));
        fields.add(mapOf("name", "Bewertung", "value", detection.signals() + " Signal(e) • Risiko " + detection.riskScore() + "/100", "inline", true));
        fields.add(mapOf("name", "Provider", "value", "`" + escapeMarkdown(cleanText(detection.providers(), 900)) + "`", "inline", false));
        fields.add(mapOf("name", "Entscheidungsgrund", "value", escapeMassMentions(escapeMarkdown(cleanText(detection.reason(), 900))), "inline", false));
        Map<String, Object> embed = mapOf(
                "title", detection.blocked() ? "🛡️ VPN-/Proxy-Verbindung blockiert" : "⚠️ Auffällige VPN-Prüfung",
                "description", "**Entscheidung:** " + vpnDecisionGerman(detection.decision()),
                "color", detection.blocked() ? 0xED4245 : 0xFEE75C,
                "fields", fields,
                "timestamp", java.time.Instant.ofEpochMilli(detection.timestamp()).toString(),
                "footer", mapOf("text", plugin.brandName()+" " + plugin.getDescription().getVersion() + " • VPN-Protokoll"));
        String role = plugin.getConfig().getString("discord-bot.vpn-log.role-id", "").replaceAll("[^0-9]", "");
        String mention = detection.blocked() && !role.isBlank() ? "<@&" + role + ">" : "";
        Map<String, Object> allowed = mention.isBlank() ? mapOf("parse", List.of()) : mapOf("parse", List.of(), "roles", List.of(role));
        request("POST", "/channels/" + channel + "/messages", Json.stringify(mapOf(
                "content", mention, "embeds", List.of(embed), "allowed_mentions", allowed)));
    }

    void relayVpnWhitelistChange(String actor, String operation, String playerName, UUID uuid) {
        if (!vpnLogReady() || !plugin.getConfig().getBoolean("discord-bot.vpn-log.notify-whitelist-changes", true)) return;
        String description = "**Spieler:** " + escapeMarkdown(cleanText(playerName, 80))
                + "\n**UUID:** `" + (uuid == null ? "Legacy-Name" : uuid) + "`"
                + "\n**Aktion:** Whitelist-Eintrag " + escapeMarkdown(cleanText(operation, 40))
                + "\n**Admin:** " + escapeMarkdown(cleanText(actor, 120))
                + "\n\nDer Spieler wird bei aktivem Whitelist-Eintrag vor allen externen VPN-API-Abfragen direkt zugelassen.";
        sendEmbed(vpnLogChannelId(), "✅ VPN-Whitelist aktualisiert", description, 0x57F287);
    }

    void relayVpnManualCheck(String actor, String ip, VpnCheckResult result) {
        if (result == null || !vpnLogReady()) return;
        boolean includeIp = plugin.getConfig().getBoolean("discord-bot.vpn-log.include-ip", true);
        String assessment = result.allProvidersFailed() ? "⚠️ Alle aktivierten Provider sind ausgefallen"
                : result.positive() ? "⛔ VPN-/Proxy-Schwelle erreicht" : "✅ Keine Blockschwelle erreicht";
        String description = "**Ausgeführt von:** " + escapeMarkdown(cleanText(actor, 120))
                + (includeIp ? "\n**IP-Adresse:** `" + escapeMarkdown(cleanText(ip, 80)) + "`" : "")
                + "\n**Bewertung:** " + assessment
                + "\n**Land:** " + (result.countryCode().isBlank() ? "Unbekannt" : escapeMarkdown(result.countryCode()))
                + "\n**Signale / Risiko:** " + result.signals() + " / " + result.riskScore() + ""
                + "\n**Provider:** `" + escapeMarkdown(cleanText(result.providerSummary(), 1100)) + "`"
                + "\n**Grund:** " + escapeMassMentions(escapeMarkdown(cleanText(result.reason(), 900)));
        sendEmbed(vpnLogChannelId(), "🔎 Manuelle VPN-IP-Prüfung", description,
                result.allProvidersFailed() ? 0xFEE75C : result.positive() ? 0xED4245 : 0x57F287);
    }

    private boolean vpnLogReady() {
        return enabled() && configured() && plugin.getConfig().getBoolean("discord-bot.vpn-log.enabled", true)
                && !vpnLogChannelId().isBlank();
    }

    private String vpnLogChannelId() {
        return plugin.getConfig().getString("discord-bot.vpn-log.channel-id", "").replaceAll("[^0-9]", "");
    }

    private static String vpnDecisionGerman(String decision) {
        if (decision == null) return "Unbekannt";
        return switch (decision.toUpperCase(Locale.ROOT)) {
            case "VPN_BLOCK" -> "VPN-/Proxy-Schwelle blockiert";
            case "API_FAILURE_BLOCK" -> "API-Ausfall sicherheitshalber blockiert";
            case "SUSPICIOUS_ALLOWED" -> "Auffällig, aber zugelassen";
            case "ALLOWED" -> "Zugelassen";
            case "WHITELIST" -> "Whitelist-Bypass";
            case "COOLDOWN" -> "Wiederverbindungs-Cooldown";
            default -> escapeMarkdown(cleanText(decision, 80));
        };
    }

    void relayActivityAlert(String title, String description) {
        if (!enabled() || !configured()) return;
        String channel = plugin.getConfig().getString("discord-bot.admin.audit-channel-id", "").trim();
        if (channel.isBlank()) channel = plugin.getConfig().getString("discord-bot.alerts.channel-id", "").trim();
        if (channel.isBlank()) channel = relayChannelId();
        if (channel.isBlank()) return;
        sendEmbed(channel, "🕒 " + cleanText(title, 220), escapeMassMentions(cleanMultiline(description, 3800)), 0xFEE75C);
    }

    void relayAdminQolEvent(String title, String description, int color) {
        if (!canRelay("admin-qol")) return;
        String cleanTitle = cleanText(title, 240);
        String cleanDescription = cleanMultiline(description, 3800);
        if (cleanTitle.isBlank() || cleanDescription.isBlank()) return;
        sendEmbed(relayChannelId(), cleanTitle, escapeMassMentions(cleanDescription), color);
    }

    void relayFart(String playerName, String level, String message, int color) {
        if (!canRelay("farts")) return;
        String cleanPlayer = escapeMarkdown(cleanText(playerName, 64));
        String cleanLevel = escapeMarkdown(cleanText(level, 64));
        String cleanMessage = escapeMassMentions(cleanMultiline(message, 500));
        if (cleanPlayer.isBlank() || cleanMessage.isBlank()) return;
        sendEmbed(relayChannelId(), "Somebody farted 💨",
                cleanMessage + "\n**Player:** " + cleanPlayer + "\n**Variant:** " + cleanLevel, color);
    }

    void relayBurp(String playerName, String level, String message, int color) {
        if (!canRelay("burps")) return;
        String cleanPlayer = escapeMarkdown(cleanText(playerName, 64));
        String cleanLevel = escapeMarkdown(cleanText(level, 64));
        String cleanMessage = escapeMassMentions(cleanMultiline(message, 500));
        if (cleanPlayer.isBlank() || cleanMessage.isBlank()) return;
        sendEmbed(relayChannelId(), "Somebody burped 🥤",
                cleanMessage + "\n**Player:** " + cleanPlayer + "\n**Variant:** " + cleanLevel, color);
    }

    private boolean canRelay(String type) {
        return enabled() && configured() && !relayChannelId().isBlank() && relayEnabled()
                && plugin.getConfig().getBoolean("discord-bot.relay.minecraft-to-discord", true)
                && plugin.getConfig().getBoolean("discord-bot.relay." + type, true);
    }

    private void connectGateway() {
        if (!running.get()) return;
        long generation = connectionGeneration.get();
        http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(15)).buildAsync(GATEWAY, new GatewayListener(generation))
                .whenComplete((socket, error) -> {
                    if (generation != connectionGeneration.get() || !running.get()) {
                        if (socket != null) try { socket.sendClose(WebSocket.NORMAL_CLOSURE, "Stale connection"); } catch (Exception ignored) { }
                        return;
                    }
                    if (error != null) {
                        lastError = error.getClass().getSimpleName() + ": " + safe(error.getMessage());
                        scheduleReconnect();
                    } else gateway = socket;
                });
    }

    private void scheduleReconnect() {
        if (!running.get() || !reconnectScheduled.compareAndSet(false, true)) return;
        heartbeatExecutor.schedule(() -> {
            reconnectScheduled.set(false);
            if (running.get()) connectGateway();
        }, 5, TimeUnit.SECONDS);
    }

    private void identify() {
        WebSocket socket = gateway;
        if (socket == null || !running.get()) return;
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("os", System.getProperty("os.name", "linux"));
        properties.put("browser", "Simplex Admin Suite");
        properties.put("device", "Simplex Admin Suite");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", token());
        data.put("intents", 33281);
        data.put("properties", properties);
        sendGateway(mapOf("op", 2, "d", data));
    }

    private void startHeartbeat(long intervalMillis) {
        if (heartbeatTask != null) heartbeatTask.cancel(false);
        heartbeatTask = heartbeatExecutor.scheduleAtFixedRate(() -> {
            if (!running.get()) return;
            sendGateway(mapOf("op", 1, "d", sequence < 0 ? null : sequence));
        }, Math.max(250L, intervalMillis / 2L), Math.max(1000L, intervalMillis), TimeUnit.MILLISECONDS);
    }

    private void sendGateway(Map<String, Object> payload) {
        WebSocket socket = gateway;
        if (socket == null) return;
        try { socket.sendText(Json.stringify(payload), true); } catch (Exception ignored) { }
    }

    private final class GatewayListener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();
        private final long generation;

        private GatewayListener(long generation) { this.generation = generation; }

        public void onOpen(WebSocket webSocket) {
            if (generation != connectionGeneration.get() || !running.get()) {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Stale connection");
                return;
            }
            gateway = webSocket;
            WebSocket.Listener.super.onOpen(webSocket);
            webSocket.request(1);
        }

        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            if (generation != connectionGeneration.get() || webSocket != gateway) return null;
            buffer.append(data);
            if (last) {
                String payload = buffer.toString();
                buffer.setLength(0);
                try {
                    gatewayExecutor.execute(() -> handleGatewayPayload(payload));
                } catch (RuntimeException error) {
                    if (running.get()) plugin.getLogger().warning("Discord-Gateway-Ereignis konnte nicht eingeplant werden: " + error.getClass().getSimpleName());
                }
            }
            webSocket.request(1);
            return null;
        }

        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (generation == connectionGeneration.get() && webSocket == gateway) {
                connected = false;
                gateway = null;
                lastError = "gateway closed " + statusCode + " " + safe(reason);
                scheduleReconnect();
            }
            return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
        }

        public void onError(WebSocket webSocket, Throwable error) {
            if (generation != connectionGeneration.get() || webSocket != gateway) return;
            connected = false;
            gateway = null;
            lastError = error.getClass().getSimpleName() + ": " + safe(error.getMessage());
            scheduleReconnect();
        }
    }

    @SuppressWarnings("unchecked")
    private void handleGatewayPayload(String payload) {
        Object parsed;
        try { parsed = Json.parse(payload); } catch (Exception error) { return; }
        if (!(parsed instanceof Map)) return;
        Map<String, Object> root = (Map<String, Object>) parsed;
        int op = intValue(root.get("op"), -1);
        Object seq = root.get("s");
        if (seq instanceof Number) sequence = ((Number) seq).longValue();
        Map<String, Object> data = root.get("d") instanceof Map ? (Map<String, Object>) root.get("d") : Collections.emptyMap();
        if (op == 10) {
            startHeartbeat(longValue(data.get("heartbeat_interval"), 45000L));
            identify();
            return;
        }
        if (op == 7 || op == 9) {
            connected = false;
            scheduleReconnect();
            return;
        }
        if (op != 0) return;
        String type = string(root.get("t"));
        if (type.equals("READY")) {
            connected = true;
            presentation.reconnect();
            lastError = "connected";
            sessionId = string(data.get("session_id"));
            Map<String, Object> application = data.get("application") instanceof Map ? (Map<String, Object>) data.get("application") : Collections.emptyMap();
            Map<String, Object> user = data.get("user") instanceof Map ? (Map<String, Object>) data.get("user") : Collections.emptyMap();
            applicationId = string(application.get("id"));
            if (applicationId.isBlank()) applicationId = string(user.get("id"));
            if (!applicationId.equals(profileApplicationId)) { profileApplicationId=applicationId; lastProfileDescription=""; presentation.newApplication(); }
            registerCommands("Gateway READY");
            refreshProfile();
            updateStatus(false);
        } else if (type.equals("GUILD_CREATE") && guildId().equals(string(data.get("id")))) {
            guildOwnerId = string(data.get("owner_id"));
            cacheGuildRoles(data.get("roles"));
        } else if ((type.equals("GUILD_ROLE_CREATE") || type.equals("GUILD_ROLE_UPDATE"))
                && guildId().equals(string(data.get("guild_id")))) {
            Object role = data.get("role");
            if (role instanceof Map) cacheGuildRole((Map<String, Object>) role);
        } else if (type.equals("GUILD_ROLE_DELETE") && guildId().equals(string(data.get("guild_id")))) {
            guildRolePermissions.remove(string(data.get("role_id")));
        } else if (type.equals("MESSAGE_CREATE")) handleDiscordMessage(data);
        else if (type.equals("INTERACTION_CREATE")) handleInteraction(data);
        else if(type.equals("CHANNEL_DELETE")&&guildId().equals(string(data.get("guild_id")))){String deleted=string(data.get("id"));featureJob(()->{if(deleted.equals(statisticsChannel))clearStatisticsChannel();});}
    }

    @SuppressWarnings("unchecked")
    private void handleDiscordMessage(Map<String, Object> message) {
        if (message.containsKey("webhook_id")) return;
        Map<String, Object> author = message.get("author") instanceof Map ? (Map<String, Object>) message.get("author") : Collections.emptyMap();
        if (Boolean.TRUE.equals(author.get("bot"))) return;
        String messageGuild = string(message.get("guild_id"));
        if (!messageGuild.isBlank() && !guildId().equals(messageGuild)) return;
        String id = string(message.get("id"));
        long now = System.currentTimeMillis();
        recentDiscordMessages.entrySet().removeIf(entry -> entry.getValue() < now - 60_000L);
        if (!id.isBlank() && recentDiscordMessages.putIfAbsent(id, now) != null) return;
        String content = cleanText(string(message.get("content")), 1500);
        if (content.isBlank() && !(message.get("attachments") instanceof List<?> files && !files.isEmpty())) return;
        Map<String, Object> member = message.get("member") instanceof Map ? (Map<String, Object>) message.get("member") : Collections.emptyMap();
        String name = string(member.get("nick"));
        if (name.isBlank()) name = string(author.get("global_name"));
        if (name.isBlank()) name = string(author.get("username"));
        String userId = string(author.get("id"));
        String channelId = string(message.get("channel_id"));
        if(relayEnabled()&&relayChannelId().equals(channelId)&&plugin.getConfig().getBoolean("discord-bot.linking.required",true)&&plugin.accountLinks.store.byDiscord(userId)==null){receiveLinkedChat(message,userId,name);return;}
        DiscordAdminController.Request inline = inlineEnabled() ? DiscordAdminController.parseInline(content, inlinePrefix()) : null;
        if (inline != null) inline = responseContext(inline, false);
        if (inline != null) {
            Access access = access(member, userId, channelId);
            DiscordAdminController.Actor actor = actor(member, author, userId, name, channelId);
            if (access == Access.NONE) return;
            if (plugin.getConfig().getBoolean("discord-bot.admin.inline.require-allowed-channel", true)
                    && plugin.getConfig().getStringList("discord-bot.admin.allowed-channel-ids").isEmpty()) {
                sendAdminChannelReply(channelId, DiscordAdminController.Reply.error(
                        "Inline-Administration bleibt gesperrt, bis im Minecraft-Adminmenü mindestens ein privater Discord-Adminkanal hinterlegt wurde. Slash-Commands funktionieren weiterhin ephemer."));
                return;
            }
            if (adminController.mutation(inline) && access != Access.FULL) {
                sendAdminChannelReply(channelId, DiscordAdminController.Reply.error("Diese Rolle besitzt ausschließlich Lesezugriff."));
                return;
            }
            if (inline.path.equals("server.console") && plugin.getConfig().getBoolean("discord-bot.admin.console.full-access", false)) {
                sendAdminChannelReply(channelId, DiscordAdminController.Reply.error("Für volle Konsolenbefehle mit privatem Live-Feedback bitte /sasadmin server console verwenden.")); return;
            }
            if (!acceptCooldown(userId)) {
                sendAdminChannelReply(channelId, DiscordAdminController.Reply.error("Bitte warte kurz, bevor du den nächsten Admin-Befehl ausführst."));
                return;
            }
            if (adminController.requiresConfirmation(inline)) sendConfirmationMessage(channelId, inline, actor);
            else executeForChannel(channelId, inline, actor);
            return;
        }
        if (!relayEnabled() || !plugin.getConfig().getBoolean("discord-bot.relay.discord-to-minecraft", true)) return;
        if (!relayChannelId().equals(channelId)) return;
        receiveLinkedChat(message,userId,name);
    }

    String syncGuildCommands(String actor) {
        if (!enabled() || !configured()) return "Discord-Bot ist nicht vollständig eingerichtet.";
        if (applicationId.isBlank()) return "Discord-Gateway ist noch nicht READY; die Befehle werden beim Verbindungsaufbau automatisch synchronisiert.";
        registerCommands(actor);
        return "Synchronisierung von `/sasadmin`, `/ssmp`, `/link`, `/unlink`, `/serverstats`, `/accountadmin`, `/shop`, `/myaccount` und `/quicknote` mit der konfigurierten Guild wurde gestartet.";
    }

    private void registerCommands(String actor) {
        if (applicationId.isBlank() || guildId().isBlank() || !commandSyncPending.compareAndSet(false,true)) return;
        lastCommandSyncAttempt=System.currentTimeMillis();
        try { executor.execute(() -> {
            try {
                String payload = Json.stringify(featureCommands());
                HttpResponse<String> response = featureRequest("PUT", "/applications/" + applicationId + "/guilds/" + guildId() + "/commands", payload);
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    lastCommandSyncAt = System.currentTimeMillis();
                    lastError = "connected";
                    plugin.getLogger().info("Discord guild commands synchronized (" + cleanText(actor, 80) + ").");
                } else {
                    lastError = "Command sync HTTP " + response.statusCode() + ": " + DiscordDiagnostics.detail(response.body(),token());
                }
            } catch (Exception error) {
                lastError = "Command sync " + error.getClass().getSimpleName();
                diagnostics.failure("guild command synchronization",DiscordDiagnostics.safe(error.getMessage(),token()));
            } finally { commandSyncPending.set(false); }
        }); } catch (RuntimeException error) { commandSyncPending.set(false); diagnostics.failure("guild command queue",error.getClass().getSimpleName()); }
    }

    @SuppressWarnings("unchecked")
    private void handleInteraction(Map<String, Object> interaction) {
        interactionsReceived.incrementAndGet();
        lastInteractionAt = System.currentTimeMillis();
        int interactionType = intValue(interaction.get("type"), 0);
        Map<String, Object> data = interaction.get("data") instanceof Map ? (Map<String, Object>) interaction.get("data") : Collections.emptyMap();
        if(featureInteraction(interaction,data,interactionType))return;
        if (interactionType == 3) { handleComponent(interaction, data); return; }
        if (interactionType == 4) { handleAutocomplete(interaction, data); return; }
        if (interactionType == 5) { handleModalSubmit(interaction, data); return; }
        if (interactionType != 2) return;
        String commandName = string(data.get("name"));
        if (!commandName.equals("ssmp") && !commandName.equals(DiscordAdminController.COMMAND)) return;
        String id = string(interaction.get("id"));
        String interactionToken = string(interaction.get("token"));
        String interactionChannelId = string(interaction.get("channel_id"));
        Map<String, Object> member = interaction.get("member") instanceof Map ? (Map<String, Object>) interaction.get("member") : Collections.emptyMap();
        Map<String, Object> user = member.get("user") instanceof Map ? (Map<String, Object>) member.get("user")
                : interaction.get("user") instanceof Map ? (Map<String, Object>) interaction.get("user") : Collections.emptyMap();
        String userId = string(user.get("id"));
        Access access = access(member, userId, interactionChannelId);
        if (access == Access.NONE) {
            respondReply(id, interactionToken, DiscordAdminController.Reply.error("Du bist für die Discord-Administration nicht freigegeben."), true);
            return;
        }
        String displayName = string(member.get("nick"));
        if (displayName.isBlank()) displayName = string(user.get("global_name"));
        if (displayName.isBlank()) displayName = string(user.get("username"));
        DiscordAdminController.Actor actor = actor(member, user, userId, displayName, interactionChannelId);
        if (!acceptCooldown(userId)) {
            respondReply(id, interactionToken, DiscordAdminController.Reply.error("Bitte warte kurz, bevor du den nächsten Admin-Befehl ausführst."), true);
            return;
        }
        if (commandName.equals("ssmp")) {
            handleLegacyInteraction(interaction, data, actor, access);
            return;
        }
        DiscordAdminController.Request request = responseContext(DiscordAdminController.parseInteraction(data), true);
        if (adminController.mutation(request) && access != Access.FULL) {
            respondReply(id, interactionToken, DiscordAdminController.Reply.error("Diese Rolle besitzt ausschließlich Lesezugriff."), true);
            return;
        }
        if (adminController.requiresConfirmation(request)) {
            respondConfirmation(id, interactionToken, request, actor, false);
            return;
        }
        deferInteraction(id, interactionToken, false);
        executeForInteraction(interactionToken, request, actor);
    }

    @SuppressWarnings("unchecked")
    private void handleLegacyInteraction(Map<String, Object> interaction, Map<String, Object> data,
                                         DiscordAdminController.Actor actor, Access access) {
        String id = string(interaction.get("id")), token = string(interaction.get("token"));
        String channelId = string(interaction.get("channel_id"));
        String action = "status";
        Object options = data.get("options");
        if (options instanceof List && !((List<?>) options).isEmpty() && ((List<?>) options).get(0) instanceof Map)
            action = string(((Map<String, Object>) ((List<?>) options).get(0)).get("name"));
        boolean mutation = !action.equals("status");
        if (mutation && access != Access.FULL) {
            respondReply(id, token, DiscordAdminController.Reply.error("Diese Rolle besitzt ausschließlich Lesezugriff."), true);
            return;
        }
        if (action.equals("status-here") || action.equals("relay-here")) {
            String selected = action;
            deferInteraction(id, token, false);
            plugin.runAdminTask( () -> {
                if (selected.equals("status-here")) useStatusChannel(actor.auditName(), channelId);
                else useRelayChannel(actor.auditName(), channelId);
                editOriginal(token, DiscordAdminController.Reply.ok("Discord-Kanal aktualisiert",
                        selected.equals("status-here") ? "Dieser Kanal wird jetzt für den persistenten Serverstatus verwendet."
                                : "Dieser Kanal wird jetzt für das Minecraft-Chat-Relay verwendet.", "Managing Discord channels"));
            });
            return;
        }
        DiscordAdminController.Request request;
        if (action.equals("relay-on") || action.equals("relay-off"))
            request = new DiscordAdminController.Request("server.relay", mapOf("mode", action.endsWith("on") ? "on" : "off"));
        else request = new DiscordAdminController.Request("server.discord", mapOf("action", action.equals("sync") ? "sync" : action));
        if (adminController.requiresConfirmation(request)) respondConfirmation(id, token, request, actor, false);
        else { deferInteraction(id, token, false); executeForInteraction(token, request, actor); }
    }

    @SuppressWarnings("unchecked")
    private void handleAutocomplete(Map<String, Object> interaction, Map<String, Object> data) {
        if (!string(data.get("name")).equals(DiscordAdminController.COMMAND)) return;
        String id = string(interaction.get("id")), token = string(interaction.get("token"));
        String channel = string(interaction.get("channel_id"));
        Map<String, Object> member = interaction.get("member") instanceof Map ? (Map<String, Object>) interaction.get("member") : Collections.emptyMap();
        Map<String, Object> user = member.get("user") instanceof Map ? (Map<String, Object>) member.get("user") : Collections.emptyMap();
        if (access(member, string(user.get("id")), channel) == Access.NONE) { respondAutocomplete(id, token, List.of()); return; }
        FocusedOption focused = findFocused(data.get("options"));
        if (focused == null) { respondAutocomplete(id, token, List.of()); return; }
        DiscordAdminController.Request request = DiscordAdminController.parseInteraction(data);
        plugin.runAdminTask( () -> respondAutocomplete(id, token,
                adminController.autocomplete(request, focused.name, focused.value)));
    }

    @SuppressWarnings("unchecked")
    private FocusedOption findFocused(Object raw) {
        if (!(raw instanceof List)) return null;
        for (Object value : (List<?>) raw) {
            if (!(value instanceof Map)) continue;
            Map<String, Object> option = (Map<String, Object>) value;
            if (Boolean.TRUE.equals(option.get("focused"))) return new FocusedOption(string(option.get("name")), string(option.get("value")));
            FocusedOption nested = findFocused(option.get("options"));
            if (nested != null) return nested;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private void handleComponent(Map<String, Object> interaction, Map<String, Object> data) {
        String customId = string(data.get("custom_id"));
        if (!customId.startsWith("sas:")) return;
        String id = string(interaction.get("id")), token = string(interaction.get("token"));
        String channel = string(interaction.get("channel_id"));
        Map<String, Object> member = interaction.get("member") instanceof Map ? (Map<String, Object>) interaction.get("member") : Collections.emptyMap();
        Map<String, Object> user = member.get("user") instanceof Map ? (Map<String, Object>) member.get("user") : Collections.emptyMap();
        String userId = string(user.get("id"));
        Access access = access(member, userId, channel);
        String displayName = string(member.get("nick"));
        if (displayName.isBlank()) displayName = string(user.get("global_name"));
        if (displayName.isBlank()) displayName = string(user.get("username"));
        DiscordAdminController.Actor actor = actor(member, user, userId, displayName, channel);
        if (access == Access.NONE) {
            respondReply(id, token, DiscordAdminController.Reply.error("Du bist für die Discord-Administration nicht freigegeben."), true);
            return;
        }
        if (customId.equals("sas:modal:console")) {
            if (access != Access.FULL) { respondReply(id, token, DiscordAdminController.Reply.error("Nur Discord-Admins mit Schreibzugriff."), true); return; }
            Map<String, Object> input = mapOf("type", 4, "custom_id", "command", "label", "Serverbefehl", "style", 1,
                    "placeholder", "z. B. list oder time query daytime", "min_length", 1, "max_length", 500, "required", true);
            respondData(id, token, 9, mapOf("custom_id", "sas:modal-submit:console", "title", "Serverkonsole • Bestätigung folgt",
                    "components", List.of(mapOf("type", 1, "components", List.of(input)))));
            return;
        }
        if (customId.equals("sas:modal:player")) {
            respondPlayerLookupModal(id, token);
            return;
        }
        if (customId.startsWith("sas:modal:suite:")) {
            respondSuiteCommandModal(id, token, customId.substring("sas:modal:suite:".length()));
            return;
        }
        if (customId.startsWith("sas:confirm:") || customId.startsWith("sas:cancel:")) {
            String nonce = customId.substring(customId.lastIndexOf(':') + 1);
            PendingAdminAction pending = pendingAdminActions.get(nonce);
            if (pending == null || pending.expiresAt < System.currentTimeMillis()) {
                if (pending != null) pendingAdminActions.remove(nonce, pending);
                respondUpdate(id, token, DiscordAdminController.Reply.error("Diese Bestätigung ist abgelaufen oder wurde bereits verwendet."));
                return;
            }
            if (!pending.actor.id.equals(userId)) {
                respondReply(id, token, DiscordAdminController.Reply.error("Nur der auslösende Discord-Admin darf diese Aktion bestätigen."), true);
                return;
            }
            if (!pendingAdminActions.remove(nonce, pending)) {
                respondUpdate(id, token, DiscordAdminController.Reply.error("Diese Bestätigung wurde bereits verwendet."));
                return;
            }
            if (customId.startsWith("sas:cancel:")) {
                respondUpdate(id, token, DiscordAdminController.Reply.ok("Aktion abgebrochen", "Es wurden keine Änderungen vorgenommen.", "Protecting the Minecraft server"));
                return;
            }
            if (access != Access.FULL) {
                respondUpdate(id, token, DiscordAdminController.Reply.error("Dein Vollzugriff ist nicht mehr vorhanden; die Aktion wurde abgebrochen."));
                return;
            }
            deferComponent(id, token);
            executeForInteraction(token, pending.request, actor);
            return;
        }
        if (customId.equals("sas:navigate")) {
            Object rawValues = data.get("values");
            if (rawValues instanceof List && !((List<?>) rawValues).isEmpty()) {
                String selected = cleanText(string(((List<?>) rawValues).get(0)), 30).toLowerCase(Locale.ROOT);
                customId = "sas:menu:" + selected.replaceAll("[^a-z-]", "");
            }
        }
        DiscordAdminController.Request request = adminController.requestFromComponent(customId);
        Map<String, Object> sourceMessage = interaction.get("message") instanceof Map ? (Map<String, Object>) interaction.get("message") : Collections.emptyMap();
        boolean privateResponse = (intValue(sourceMessage.get("flags"), 0) & 64) != 0;
        request = responseContext(request, privateResponse);
        if (request == null) { respondReply(id, token, DiscordAdminController.Reply.error("Dieses Admin-Element ist nicht mehr gültig."), true); return; }
        if (adminController.mutation(request) && access != Access.FULL) {
            respondReply(id, token, DiscordAdminController.Reply.error("Diese Rolle besitzt ausschließlich Lesezugriff."), true);
            return;
        }
        if (!acceptCooldown(userId)) {
            respondReply(id, token, DiscordAdminController.Reply.error("Bitte warte kurz, bevor du das nächste Element verwendest."), true);
            return;
        }
        if (adminController.requiresConfirmation(request)) respondConfirmation(id, token, request, actor, true);
        else { deferComponent(id, token); executeForInteraction(token, request, actor); }
    }

    @SuppressWarnings("unchecked")
    private void handleModalSubmit(Map<String, Object> interaction, Map<String, Object> data) {
        String modalId = string(data.get("custom_id"));
        if (modalId.startsWith("sas:modal-submit:suite:") || modalId.equals("sas:modal-submit:console")) {
            handleSuiteCommandModalSubmit(interaction, data);
            return;
        }
        if (!modalId.equals("sas:modal-submit:player")) return;
        String id = string(interaction.get("id")), token = string(interaction.get("token"));
        String channel = string(interaction.get("channel_id"));
        Map<String, Object> member = interaction.get("member") instanceof Map ? (Map<String, Object>) interaction.get("member") : Collections.emptyMap();
        Map<String, Object> user = member.get("user") instanceof Map ? (Map<String, Object>) member.get("user")
                : interaction.get("user") instanceof Map ? (Map<String, Object>) interaction.get("user") : Collections.emptyMap();
        String userId = string(user.get("id"));
        Access access = access(member, userId, channel);
        if (access == Access.NONE) {
            respondReply(id, token, DiscordAdminController.Reply.error("Du bist für die Discord-Administration nicht freigegeben."), true);
            return;
        }
        String displayName = string(member.get("nick"));
        if (displayName.isBlank()) displayName = string(user.get("global_name"));
        if (displayName.isBlank()) displayName = string(user.get("username"));
        if (!acceptCooldown(userId)) {
            respondReply(id, token, DiscordAdminController.Reply.error("Bitte warte kurz, bevor du die nächste Spielerakte öffnest."), true);
            return;
        }
        String query = modalValue(data.get("components"), "player");
        if (query.isBlank()) {
            respondReply(id, token, DiscordAdminController.Reply.error("Bitte gib einen Spielernamen oder eine UUID ein."), true);
            return;
        }
        DiscordAdminController.Actor actor = actor(member, user, userId, displayName, channel);
        DiscordAdminController.Request request = responseContext(
                new DiscordAdminController.Request("player.info", mapOf("player", query)), true);
        deferInteraction(id, token, false);
        executeForInteraction(token, request, actor);
    }

    @SuppressWarnings("unchecked")
    private void handleSuiteCommandModalSubmit(Map<String, Object> interaction, Map<String, Object> data) {
        String id = string(interaction.get("id")), token = string(interaction.get("token"));
        String channel = string(interaction.get("channel_id"));
        Map<String, Object> member = interaction.get("member") instanceof Map ? (Map<String, Object>) interaction.get("member") : Collections.emptyMap();
        Map<String, Object> user = member.get("user") instanceof Map ? (Map<String, Object>) member.get("user")
                : interaction.get("user") instanceof Map ? (Map<String, Object>) interaction.get("user") : Collections.emptyMap();
        String userId = string(user.get("id"));
        Access access = access(member, userId, channel);
        if (access == Access.NONE) {
            respondReply(id, token, DiscordAdminController.Reply.error("Du bist für die Discord-Administration nicht freigegeben."), true);
            return;
        }
        boolean console = string(data.get("custom_id")).equals("sas:modal-submit:console");
        String command = modalValue(data.get("components"), "command");
        if (command.isBlank()) {
            respondReply(id, token, DiscordAdminController.Reply.error("Bitte gib einen freigegebenen `/sas`-Unterbefehl ein."), true);
            return;
        }
        DiscordAdminController.Request request = responseContext(
                new DiscordAdminController.Request(console ? "server.console" : "server.suite", mapOf("command", command)), true);
        if (!console && !DiscordAdminController.allowedSuiteCommand(DiscordAdminController.tokenize(command))) {
            respondReply(id, token, DiscordAdminController.Reply.error("Dieser Suite-Befehl ist über Discord nicht freigegeben."), true);
            return;
        }
        if (adminController.mutation(request) && access != Access.FULL) {
            respondReply(id, token, DiscordAdminController.Reply.error("Diese Rolle besitzt ausschließlich Lesezugriff."), true);
            return;
        }
        if (!acceptCooldown(userId)) {
            respondReply(id, token, DiscordAdminController.Reply.error("Bitte warte kurz, bevor du den nächsten Admin-Befehl ausführst."), true);
            return;
        }
        String displayName = string(member.get("nick"));
        if (displayName.isBlank()) displayName = string(user.get("global_name"));
        if (displayName.isBlank()) displayName = string(user.get("username"));
        DiscordAdminController.Actor actor = actor(member, user, userId, displayName, channel);
        if (adminController.requiresConfirmation(request)) respondConfirmation(id, token, request, actor, false);
        else {
            deferInteraction(id, token, false);
            executeForInteraction(token, request, actor);
        }
    }

    @SuppressWarnings("unchecked")
    private String modalValue(Object raw, String customId) {
        if (!(raw instanceof List)) return "";
        for (Object value : (List<?>) raw) {
            if (!(value instanceof Map)) continue;
            Map<String, Object> component = (Map<String, Object>) value;
            if (customId.equals(string(component.get("custom_id"))))
                return cleanText(string(component.get("value")), customId.equals("command") ? 500 : 100);
            String nested = modalValue(component.get("components"), customId);
            if (!nested.isBlank()) return nested;
        }
        return "";
    }

    private boolean hasAdministrator(Map<String, Object> member) {
        try {
            BigInteger permissions = new BigInteger(string(member.get("permissions")));
            if (permissions.and(BigInteger.valueOf(8L)).signum() != 0) return true;
        } catch (Exception ignored) { }
        BigInteger combined = guildRolePermissions.getOrDefault(guildId(), BigInteger.ZERO);
        for (String role : roleIds(member)) combined = combined.or(guildRolePermissions.getOrDefault(role, BigInteger.ZERO));
        return combined.and(BigInteger.valueOf(8L)).signum() != 0;
    }

    @SuppressWarnings("unchecked")
    private void cacheGuildRoles(Object raw) {
        guildRolePermissions.clear();
        if (!(raw instanceof List)) return;
        for (Object value : (List<?>) raw) if (value instanceof Map) cacheGuildRole((Map<String, Object>) value);
    }

    private void cacheGuildRole(Map<String, Object> role) {
        String id = string(role.get("id"));
        if (id.isBlank()) return;
        try { guildRolePermissions.put(id, new BigInteger(string(role.get("permissions")))); }
        catch (Exception ignored) { guildRolePermissions.put(id, BigInteger.ZERO); }
    }

    private DiscordAdminController.Request responseContext(DiscordAdminController.Request request, boolean privateResponse) {
        if (request == null) return null;
        Map<String, Object> values = new LinkedHashMap<>(request.values);
        values.put("_private", privateResponse);
        return new DiscordAdminController.Request(request.path, values);
    }

    private DiscordAdminController.Actor actor(Map<String, Object> member, Map<String, Object> user, String userId, String displayName, String channelId) {
        return new DiscordAdminController.Actor(userId == null ? "" : userId.replaceAll("[^0-9]", ""),
                cleanText(displayName, 80), hasAdministrator(member) || Objects.equals(userId, guildOwnerId),
                channelId == null ? "" : channelId.replaceAll("[^0-9]", ""));
    }

    private Access access(Map<String, Object> member, String userId, String channelId) {
        if (!adminEnabled()) return Access.NONE;
        List<String> allowedChannels = plugin.getConfig().getStringList("discord-bot.admin.allowed-channel-ids");
        if (!allowedChannels.isEmpty() && !allowedChannels.contains(channelId)) return Access.NONE;
        if (userId != null && userId.equals(guildOwnerId)) return Access.FULL;
        if (plugin.getConfig().getBoolean("discord-bot.admin.allow-discord-administrator", true) && hasAdministrator(member)) return Access.FULL;
        Set<String> roles = roleIds(member);
        if (intersects(roles, plugin.getConfig().getStringList("discord-bot.admin.role-ids"))) return Access.FULL;
        if (intersects(roles, plugin.getConfig().getStringList("discord-bot.admin.read-only-role-ids"))) return Access.READ_ONLY;
        return Access.NONE;
    }

    private Set<String> roleIds(Map<String, Object> member) {
        Set<String> result = new HashSet<>();
        Object raw = member.get("roles");
        if (raw instanceof List) for (Object value : (List<?>) raw) result.add(string(value));
        return result;
    }

    private boolean intersects(Set<String> roles, Collection<String> configured) {
        if (roles == null || configured == null) return false;
        for (String id : configured) if (id != null && !id.isBlank() && roles.contains(id.trim())) return true;
        return false;
    }

    private boolean acceptCooldown(String userId) {
        long now = System.currentTimeMillis();
        long cooldown = Math.max(250L, Math.min(10_000L, plugin.getConfig().getLong("discord-bot.admin.cooldown-millis", 750L)));
        Long previous = adminCooldowns.put(userId == null ? "" : userId, now);
        return previous == null || now - previous >= cooldown;
    }

    private void respondReply(String id, String token, DiscordAdminController.Reply reply, boolean ephemeral) {
        respondData(id, token, 4, replyData(reply, ephemeral));
    }

    private void respondUpdate(String id, String token, DiscordAdminController.Reply reply) {
        respondData(id, token, 7, replyData(reply, false));
    }

    private void deferInteraction(String id, String token, boolean publicReply) {
        respondData(id, token, 5, mapOf("flags", publicReply ? 0 : 64));
    }

    private void deferComponent(String id, String token) {
        respondData(id, token, 6, null);
    }

    private void respondAutocomplete(String id, String token, List<DiscordAdminController.Choice> choices) {
        List<Map<String, Object>> values = new ArrayList<>();
        for (DiscordAdminController.Choice choice : choices) values.add(mapOf("name", choice.name, "value", choice.value));
        respondData(id, token, 8, mapOf("choices", values));
    }

    private void respondPlayerLookupModal(String id, String token) {
        Map<String, Object> input = mapOf(
                "type", 4,
                "custom_id", "player",
                "label", "Spielername oder UUID",
                "style", 1,
                "placeholder", "Online- oder gespeichertes Spielerprofil",
                "min_length", 1,
                "max_length", 100,
                "required", true);
        Map<String, Object> modal = mapOf(
                "custom_id", "sas:modal-submit:player",
                "title", "Simplex Spielerakte öffnen",
                "components", List.of(mapOf("type", 1, "components", List.of(input))));
        respondData(id, token, 9, modal);
    }

    private void respondSuiteCommandModal(String id, String token, String requestedSection) {
        String section = cleanText(requestedSection, 30).toLowerCase(Locale.ROOT).replaceAll("[^a-z-]", "");
        String placeholder = switch (section) {
            case "players" -> "z. B. notes Spieler list oder points Spieler status";
            case "moderation" -> "z. B. warnings Spieler oder watchlist list";
            case "server" -> "z. B. status, slowmode 5 oder maintenance off";
            case "worlds" -> "z. B. diagnostics oder discordstatus";
            case "security" -> "z. B. alts Spieler oder vpn protokoll";
            case "communication" -> "z. B. announcement list oder discordstatus";
            case "community" -> "z. B. rules, polls oder points Spieler status";
            case "operations" -> "z. B. status, watchlist list oder diagnostics";
            default -> "Freigegebener /sas-Unterbefehl ohne führenden Slash";
        };
        Map<String, Object> input = mapOf(
                "type", 4,
                "custom_id", "command",
                "label", "Geprüfter /sas-Unterbefehl",
                "style", 1,
                "placeholder", truncate(placeholder, 100),
                "min_length", 1,
                "max_length", 500,
                "required", true);
        Map<String, Object> modal = mapOf(
                "custom_id", "sas:modal-submit:suite:" + (section.isBlank() ? "home" : section),
                "title", "Simplex Suite-Befehl",
                "components", List.of(mapOf("type", 1, "components", List.of(input))));
        respondData(id, token, 9, modal);
    }

    private void respondData(String id, String token, int type, Map<String, Object> data) {
        Map<String, Object> payload = mapOf("type", type);
        if (data != null) payload.put("data", data);
        requestInteraction("POST", "/interactions/" + id + "/" + token + "/callback", Json.stringify(payload), "interaction callback");
    }

    private void respondConfirmation(String id, String token, DiscordAdminController.Request adminRequest,
                                     DiscordAdminController.Actor actor, boolean updateMessage) {
        if (adminRequest.path.equals("server.console")) updateMessage = false;
        Map<String, Object> data = confirmationData(adminRequest, actor, !updateMessage);
        respondData(id, token, updateMessage ? 7 : 4, data);
    }

    private void sendConfirmationMessage(String channelId, DiscordAdminController.Request adminRequest, DiscordAdminController.Actor actor) {
        Map<String, Object> data = confirmationData(adminRequest, actor, false);
        request("POST", "/channels/" + channelId + "/messages", Json.stringify(data));
    }

    private Map<String, Object> confirmationData(DiscordAdminController.Request adminRequest,
                                                  DiscordAdminController.Actor actor, boolean ephemeral) {
        pendingAdminActions.entrySet().removeIf(entry -> entry.getValue().expiresAt < System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        long seconds = Math.max(30L, Math.min(300L, plugin.getConfig().getLong("discord-bot.admin.confirmation-seconds", 120L)));
        pendingAdminActions.put(nonce, new PendingAdminAction(adminRequest, actor, System.currentTimeMillis() + seconds * 1000L));
        Map<String, Object> embed = mapOf("title", "Kritische Admin-Aktion bestätigen",
                "description", truncate(adminController.confirmationSummary(adminRequest)
                        + "\n\nNur **" + cleanText(actor.name, 80) + "** kann diese Einmal-Bestätigung innerhalb von " + seconds + " Sekunden verwenden.", 4000),
                "color", 0xFEE75C, "footer", mapOf("text", plugin.brandName()+" " + plugin.getDescription().getVersion() + " • Account-bound confirmation"));
        List<Object> buttons = List.of(mapOf("type", 2, "style", 4, "label", "Verbindlich ausführen", "custom_id", "sas:confirm:" + nonce, "emoji", mapOf("name", "✅")),
                mapOf("type", 2, "style", 2, "label", "Abbrechen", "custom_id", "sas:cancel:" + nonce, "emoji", mapOf("name", "✖️")));
        Map<String, Object> data = mapOf("embeds", List.of(embed), "components", List.of(mapOf("type", 1, "components", buttons)),
                "allowed_mentions", mapOf("parse", List.of()));
        if (ephemeral) data.put("flags", 64);
        return data;
    }

    private void executeForInteraction(String token, DiscordAdminController.Request adminRequest, DiscordAdminController.Actor actor) {
        plugin.runAdminTask( () -> {
            if (adminRequest.path.equals("ai.propose")) {
                plugin.discordAdminAiProposalAsync(actor.auditName(), adminRequest.text("request")).whenComplete((planned, error) -> plugin.runAdminTask( () -> {
                    if (error != null || planned == null) {
                        editOriginal(token, DiscordAdminController.Reply.error("KI-Vorschlag fehlgeschlagen: " + cleanText(error == null ? "unbekannt" : error.getMessage(), 500)));
                        return;
                    }
                    plugin.expansionAudit(actor.auditName(), null, "Discord-KI-Vorschlag erstellt", planned.path);
                    editOriginalData(token, confirmationData(planned, actor, false));
                }));
                return;
            }
            if (adminRequest.path.equals("ai.ask")) {
                plugin.discordAdminAiAsk(adminRequest.text("question")).whenComplete((answer, error) -> plugin.runAdminTask( () -> {
                    DiscordAdminController.Reply reply = error == null
                            ? DiscordAdminController.Reply.ok("Simplex KI", answer, "Answering a live Minecraft server question")
                            : DiscordAdminController.Reply.error("OpenAI-Anfrage fehlgeschlagen: " + cleanText(error.getMessage(), 500));
                    recordAdminExecution(adminRequest, actor, reply);
                    editOriginal(token, reply);
                }));
                return;
            }
            DiscordAdminController.Reply reply;
            try {
                if (adminRequest.path.equals("server.console")) plugin.discordConsoleFeedback = output ->
                        editOriginal(token, DiscordAdminController.Reply.ok("Konsolen-Feedback", output, "Receiving server command feedback"));
                reply = adminController.execute(adminRequest, actor);
            } finally { plugin.discordConsoleFeedback = null; }
            recordAdminExecution(adminRequest, actor, reply);
            editOriginal(token, reply);
        });
    }

    private void executeForChannel(String channelId, DiscordAdminController.Request adminRequest, DiscordAdminController.Actor actor) {
        plugin.runAdminTask( () -> {
            if (adminRequest.path.equals("ai.propose")) {
                plugin.discordAdminAiProposalAsync(actor.auditName(), adminRequest.text("request")).whenComplete((planned, error) -> plugin.runAdminTask( () -> {
                    if (error != null || planned == null) {
                        sendAdminChannelReply(channelId, DiscordAdminController.Reply.error("KI-Vorschlag fehlgeschlagen: " + cleanText(error == null ? "unbekannt" : error.getMessage(), 500)));
                        return;
                    }
                    plugin.expansionAudit(actor.auditName(), null, "Discord-KI-Vorschlag erstellt", planned.path);
                    sendConfirmationMessage(channelId, planned, actor);
                }));
                return;
            }
            if (adminRequest.path.equals("ai.ask")) {
                plugin.discordAdminAiAsk(adminRequest.text("question")).whenComplete((answer, error) -> plugin.runAdminTask( () -> {
                    DiscordAdminController.Reply reply = error == null
                            ? DiscordAdminController.Reply.ok("Simplex KI", answer, "Answering a live Minecraft server question")
                            : DiscordAdminController.Reply.error("OpenAI-Anfrage fehlgeschlagen: " + cleanText(error.getMessage(), 500));
                    recordAdminExecution(adminRequest, actor, reply);
                    sendAdminChannelReply(channelId, reply);
                }));
                return;
            }
            DiscordAdminController.Reply reply = adminController.execute(adminRequest, actor);
            recordAdminExecution(adminRequest, actor, reply);
            sendAdminChannelReply(channelId, reply);
        });
    }

    private void recordAdminExecution(DiscordAdminController.Request adminRequest, DiscordAdminController.Actor actor,
                                      DiscordAdminController.Reply reply) {
        plugin.expansionAudit(actor.auditName(), null, "Discord-Adminaktion", adminRequest.path);
        temporaryPresence = cleanText(reply.activity, 120);
        temporaryPresenceUntil = System.currentTimeMillis() + 20_000L;
        updatePresence(snapshot(restartSeconds >= 0 ? StatusMode.RESTARTING : StatusMode.ONLINE));
        if (adminController.mutation(adminRequest) && !adminRequest.path.equals("server.console")) {
            String auditChannel = plugin.getConfig().getString("discord-bot.admin.audit-channel-id", "").trim();
            if (!auditChannel.isBlank()) sendEmbed(auditChannel, "Discord admin action",
                    "**Actor:** " + escapeMarkdown(actor.auditName()) + "\n**Action:** `" + escapeMarkdown(adminRequest.path)
                            + "`\n**Result:** " + escapeMassMentions(truncate(reply.description, 1500)), reply.color);
        }
        updateStatus(false);
    }

    private void editOriginal(String token, DiscordAdminController.Reply reply) {
        requestInteraction("PATCH", "/webhooks/" + applicationId + "/" + token + "/messages/@original",
                Json.stringify(replyData(reply, false)), "interaction response edit");
    }

    private void editOriginalData(String token, Map<String, Object> data) {
        requestInteraction("PATCH", "/webhooks/" + applicationId + "/" + token + "/messages/@original",
                Json.stringify(data), "interaction response edit");
    }

    private void sendAdminChannelReply(String channelId, DiscordAdminController.Reply reply) {
        request("POST", "/channels/" + channelId + "/messages", Json.stringify(replyData(reply, false)));
    }

    private Map<String, Object> replyData(DiscordAdminController.Reply reply, boolean ephemeral) {
        List<Map<String, Object>> components = sanitizeComponents(reply.components);
        Map<String, Object> embed = mapOf("title", truncate(plugin.brandText(reply.title), 256),
                "description", truncate(escapeMassMentions(reply.description), 4000), "color", reply.color,
                "footer", mapOf("text", plugin.brandName()+" " + plugin.getDescription().getVersion() + " • Native Discord administration"));
        Map<String, Object> data = mapOf("embeds", List.of(embed), "components", components,
                "allowed_mentions", mapOf("parse", List.of()));
        if (ephemeral) data.put("flags", 64);
        return data;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> sanitizeComponents(List<Map<String, Object>> rawRows) {
        if (rawRows == null || rawRows.isEmpty()) return List.of();
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean changed = rawRows.size() > 5;
        for (Map<String, Object> rawRow : rawRows) {
            if (rows.size() >= 5) break;
            if (rawRow == null || intValue(rawRow.get("type"), 0) != 1 || !(rawRow.get("components") instanceof List)) {
                changed = true;
                continue;
            }
            List<Object> valid = new ArrayList<>();
            List<?> rawComponents = (List<?>) rawRow.get("components");
            if (rawComponents.size() > 5) changed = true;
            for (Object value : rawComponents) {
                if (valid.size() >= 5) break;
                if (!(value instanceof Map)) { changed = true; continue; }
                Map<String, Object> component = new LinkedHashMap<>((Map<String, Object>) value);
                int type = intValue(component.get("type"), 0);
                if (type == 2) {
                    String label = string(component.get("label"));
                    String customId = string(component.get("custom_id"));
                    if (label.length() > 80) { component.put("label", truncate(label, 80)); changed = true; }
                    if (customId.isBlank() || customId.length() > 100) { changed = true; continue; }
                } else if (type != 3 && type != 5 && type != 6 && type != 7 && type != 8) {
                    changed = true;
                    continue;
                }
                valid.add(component);
            }
            if (valid.isEmpty()) { changed = true; continue; }
            rows.add(mapOf("type", 1, "components", valid));
        }
        if (changed) plugin.getLogger().warning("Discord-Komponenten wurden auf sichere API-Limits begrenzt; die Antwort bleibt verwendbar.");
        return rows;
    }

    private static final class FocusedOption {
        final String name;
        final String value;
        FocusedOption(String name, String value) { this.name = name; this.value = value; }
    }

    private void sendMessage(String channelId, String content) {
        if (channelId.isBlank()) return;
        Map<String, Object> body = mapOf("content", truncate(content, 2000), "allowed_mentions", mapOf("parse", List.of()));
        DiscordPlayerRelay relay = playerRelay;
        if (relay != null && channelId.equals(relayChannelId())) relay.bot(body);
        else request("POST", "/channels/" + channelId + "/messages", Json.stringify(body));
    }

    private void sendEmbed(String channelId, String title, String description, int color) {
        if (channelId.isBlank()) return;
        Map<String, Object> embed = mapOf("title", title, "description", truncate(description, 4000), "color", color,
                "timestamp", java.time.Instant.now().toString(),
                "footer", mapOf("text", "Simplex SMP • Simplex Admin Suite " + plugin.getDescription().getVersion() + ""));
        Map<String, Object> body = mapOf("embeds", List.of(embed), "allowed_mentions", mapOf("parse", List.of()));
        DiscordPlayerRelay relay = playerRelay;
        if (relay != null && channelId.equals(relayChannelId())) relay.bot(body);
        else request("POST", "/channels/" + channelId + "/messages", Json.stringify(body));
    }

    private void updateStatus(boolean restarting) {
        if (!enabled() || !configured()) return;
        if (restarting && restartSeconds < 0) restartSeconds = 0;
        if (!statusCaptureScheduled.compareAndSet(false, true)) return;
        plugin.runAdminTask( () -> {
            statusCaptureScheduled.set(false);
            if (!enabled() || !configured()) return;
            StatusMode mode = restartSeconds >= 0 ? StatusMode.RESTARTING : StatusMode.ONLINE;
            StatusSnapshot snapshot = snapshot(mode);
            updatePresence(snapshot);
            refreshProfile();
            if (!statusChannelId().isBlank() && normalStatusPending.compareAndSet(false,true)) executor.execute(() -> {try{writeStatus(snapshot);}finally{normalStatusPending.set(false);}});
        });
    }

    private StatusSnapshot snapshot(StatusMode mode) {
        List<PlayerSnapshot> players = mode == StatusMode.OFFLINE ? List.of() : plugin.discordPlayerSnapshots();
        double tps = readTps();
        WorldTrimManager.TrimStatus trim = plugin.worldTrimStatus();
        statusLive = plugin.statisticsLive();
        return new StatusSnapshot(mode, players, plugin.serverMaxPlayers(), tps, restartSeconds, trim, System.currentTimeMillis());
    }

    private java.util.concurrent.CompletableFuture<Void> writeStatus(StatusSnapshot snapshot) {
        java.util.concurrent.CompletableFuture<Void> done = new java.util.concurrent.CompletableFuture<>();
        try {
            String channel = statusChannelId();
            if (channel.isBlank()) { done.complete(null); return done; }
            String payload = statusPayload(snapshot);
            HttpResponse<String> response;
            if (!statusMessageId.isBlank()) {
                response = requestSync("PATCH", "/channels/" + channel + "/messages/" + statusMessageId, payload);
                if (response.statusCode() == 404) statusMessageId = "";
            } else response = null;
            if (statusMessageId.isBlank()) {
                response = requestSync("POST", "/channels/" + channel + "/messages", payload);
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    Object parsed = Json.parse(response.body());
                    if (parsed instanceof Map) statusMessageId = string(((Map<?, ?>) parsed).get("id"));
                    saveState();
                }
            }
            if (response != null && (response.statusCode() < 200 || response.statusCode() >= 300)) {
                lastError = "status HTTP " + response.statusCode();
            }
            done.complete(null);
        } catch (Exception error) {
            lastError = error.getClass().getSimpleName() + ": " + safe(error.getMessage());
            done.completeExceptionally(error);
        }
        return done;
    }

    private void updatePresence(StatusSnapshot snapshot) {
        if (!connected || !plugin.getConfig().getBoolean("discord-bot.presence.enabled", true)) return;
        String activity;
        if(snapshot.mode==StatusMode.ONLINE&&plugin.qolMaintenanceActive())activity="Server maintenance • Please try again later";
        else if(snapshot.mode==StatusMode.RESTARTING) activity=presenceTemplate("restarting", "Restarting server • {seconds}s", snapshot);
        else if(snapshot.mode==StatusMode.OFFLINE) activity="Server offline • See you soon";
        else if (temporaryPresenceUntil > System.currentTimeMillis() && !temporaryPresence.isBlank()) activity = temporaryPresence;
        else if (snapshot.trim.phase == WorldTrimManager.Phase.RUNNING) activity = presenceTemplate("trimming", "Trimming worlds • {progress}%", snapshot);
        else if (snapshot.trim.active()) activity = presenceTemplate("maintenance", "Preparing world maintenance", snapshot);
        else if (snapshot.mode == StatusMode.RESTARTING) activity = presenceTemplate("restarting", "Restarting server • {seconds}s", snapshot);
        else if (plugin.qolMaintenanceActive()) activity = presenceTemplate("maintenance", "Server maintenance • {players} online", snapshot);
        else {
            double warning = plugin.getConfig().getDouble("discord-bot.status.design.warning-tps", 18.5D);
            double critical = plugin.getConfig().getDouble("discord-bot.status.design.critical-tps", 16D);
            StatusSeverity severity = statusSeverity(snapshot.tps, warning, critical);
            activity = severity == StatusSeverity.CRITICAL
                    ? presenceTemplate("critical", "Responding to server load • {tps} TPS", snapshot)
                    : severity == StatusSeverity.WARNING
                    ? presenceTemplate("warning", "Watching server stability • {tps} TPS", snapshot)
                    : presenceTemplate("healthy", "Managing {players} players • {tps} TPS", snapshot);
        }
        String status = snapshot.mode == StatusMode.OFFLINE ? "invisible"
                : statusSeverity(snapshot.tps,
                plugin.getConfig().getDouble("discord-bot.status.design.warning-tps", 18.5D),
                plugin.getConfig().getDouble("discord-bot.status.design.critical-tps", 16D)) == StatusSeverity.CRITICAL ? "dnd" : "online";
        Map<String, Object> presence = mapOf("since", null,
                "activities", List.of(mapOf("name", truncate(activity, 128), "type", 3)),
                "status", status, "afk", false);
        if(presentation.presence(System.currentTimeMillis(),Json.stringify(presence))) sendGateway(mapOf("op", 3, "d", presence));
    }

    private String presenceTemplate(String key, String fallback, StatusSnapshot snapshot) {
        return cleanText(plugin.getConfig().getString("discord-bot.presence." + key, fallback), 128)
                .replace("{players}", Integer.toString(snapshot.players.size()))
                .replace("{max}", Integer.toString(snapshot.maxPlayers))
                .replace("{tps}", String.format(Locale.ROOT, "%.2f", snapshot.tps))
                .replace("{progress}", Integer.toString(snapshot.trim.progress))
                .replace("{seconds}", Integer.toString(Math.max(0, snapshot.restartSeconds)));
    }

    private String statusPayload(StatusSnapshot snapshot) {
        String title;
        String description;
        int color;
        String designBase = "discord-bot.status.design.";
        String serverName = cleanText(plugin.getConfig().getString(designBase + "server-name", "Simplex SMP"), 80);
        double warningTps = Math.max(10D, Math.min(20D, plugin.getConfig().getDouble(designBase + "warning-tps", 18.5D)));
        double criticalTps = Math.max(5D, Math.min(warningTps - 0.1D, plugin.getConfig().getDouble(designBase + "critical-tps", 16.0D)));
        StatusSeverity severity = statusSeverity(snapshot.tps, warningTps, criticalTps);
        WorldTrimManager.TrimStatus trim = snapshot.trim;
        if (snapshot.mode != StatusMode.OFFLINE && trim.phase == WorldTrimManager.Phase.RUNNING) {
            title = "🟣 World trimming in progress";
            description = "The server is locked while world files are being optimized.\n**Progress:** " + trim.progress
                    + "%\n**Current dimension:** " + escapeMarkdown(cleanText(trim.target, 300))
                    + "\n**Estimated time remaining:** " + escapeMarkdown(cleanText(trim.eta, 80));
            color = 0x9B59B6;
        } else if (snapshot.mode != StatusMode.OFFLINE && trim.phase == WorldTrimManager.Phase.COUNTDOWN) {
            title = "🟠 World maintenance scheduled";
            description = "World trimming starts in **" + trim.countdownSeconds + " seconds**. Players will then be disconnected.";
            color = 0xFEE75C;
        } else if (snapshot.mode != StatusMode.OFFLINE && trim.phase == WorldTrimManager.Phase.PREPARING) {
            title = "🟠 World trim safety check";
            description = "World paths, storage and the trimming tool are being verified before maintenance begins.";
            color = 0xFEE75C;
        } else if (snapshot.mode != StatusMode.OFFLINE && trim.phase == WorldTrimManager.Phase.AWAITING_OVERRIDE) {
            title = "🟠 World trim safety decision required";
            description = "No region files are being modified. The server is locked while the initiating operator reviews the loaded-chunk emergency override.";
            color = 0xFEE75C;
        } else if (snapshot.mode != StatusMode.OFFLINE && trim.phase == WorldTrimManager.Phase.RECOVERY) {
            title = "🔴 World trim recovery required";
            description = "Normal joins are restricted until a server operator completes the post-restart safety check.";
            color = 0xED4245;
        } else if(snapshot.mode==StatusMode.ONLINE&&plugin.qolMaintenanceActive()){
            title="🟠 "+serverName+" is under maintenance";description="The server is currently in maintenance mode. Please try again later.";color=0xFEE75C;
        } else if (snapshot.mode == StatusMode.ONLINE) {
            if (severity == StatusSeverity.CRITICAL) {
                title = plugin.getConfig().getString(designBase + "critical-title", "🔴 {server} is under heavy load");
                description = plugin.getConfig().getString(designBase + "critical-description", "Server performance is currently unstable. Staff have been notified.");
                color = parseColor(plugin.getConfig().getString(designBase + "critical-color", "#ED4245"), 0xED4245);
            } else if (severity == StatusSeverity.WARNING) {
                title = plugin.getConfig().getString(designBase + "warning-title", "🟡 {server} stability warning");
                description = plugin.getConfig().getString(designBase + "warning-description", "Server performance is currently degraded. Gameplay may feel less responsive.");
                color = parseColor(plugin.getConfig().getString(designBase + "warning-color", "#FEE75C"), 0xFEE75C);
            } else {
                title = plugin.getConfig().getString(designBase + "online-title", "🟢 {server} is online");
                description = plugin.getConfig().getString(designBase + "online-description", "The server is online and accepting players.");
                color = parseColor(plugin.getConfig().getString(designBase + "healthy-color", "#57F287"), 0x57F287);
            }
            title = title.replace("{server}", serverName);
            description = description.replace("{server}", serverName);
        } else if (snapshot.mode == StatusMode.RESTARTING) {
            title = plugin.getConfig().getString(designBase + "restarting-title", "🟠 {server} is restarting").replace("{server}", serverName);
            String restartDefault = snapshot.restartSeconds > 0
                    ? "A server restart was scheduled. Restarting in **{seconds} seconds**."
                    : "A shutdown or restart was detected. Players can reconnect shortly.";
            description = plugin.getConfig().getString(designBase + "restarting-description", restartDefault)
                    .replace("{server}", serverName).replace("{seconds}", Integer.toString(Math.max(0, snapshot.restartSeconds)));
            color = parseColor(plugin.getConfig().getString(designBase + "restarting-color", "#FEE75C"), 0xFEE75C);
        } else {
            title = plugin.getConfig().getString(designBase + "offline-title", "🔴 {server} is offline").replace("{server}", serverName);
            description = plugin.getConfig().getString(designBase + "offline-description", "The server is currently shut down.").replace("{server}", serverName);
            color = parseColor(plugin.getConfig().getString(designBase + "offline-color", "#ED4245"), 0xED4245);
        }
        String stability = snapshot.mode == StatusMode.OFFLINE ? "Offline"
                : severity == StatusSeverity.HEALTHY ? "Healthy" : severity == StatusSeverity.WARNING ? "Degraded" : "Critical";
        String tpsText = snapshot.mode == StatusMode.OFFLINE ? "**Offline**" : String.format(Locale.ROOT, "**%.2f**", snapshot.tps);
        List<Map<String, Object>> fields = new ArrayList<>();
        if (plugin.getConfig().getBoolean(designBase + "show-player-count", true)) fields.add(mapOf("name", "Players", "value", "**" + snapshot.players.size() + "/" + snapshot.maxPlayers + "**", "inline", true));
        if (plugin.getConfig().getBoolean(designBase + "show-tps", true)) fields.add(mapOf("name", "TPS", "value", tpsText, "inline", true));
        if (plugin.getConfig().getBoolean(designBase + "show-stability", true)) fields.add(mapOf("name", "Stability", "value", "**" + stability + "**", "inline", true));
        if (plugin.getConfig().getBoolean(designBase + "show-player-list", true)) fields.add(mapOf("name", "Online players", "value", playerList(snapshot.players), "inline", false));
        ServerStatistics.Live live=statusLive;
        if(live!=null){
            fields.add(mapOf("name","🌍 Main Overworld","value","**"+live.days()+" In-game days** • "+live.phase()+" • "+live.clock(),"inline",false));
            fields.add(mapOf("name","⚙️ Server load","value","CPU "+ServerStatistics.bar(live.cpu())+" • RAM "+ServerStatistics.bar(live.ramMax()>0?(double)live.ramUsed()/live.ramMax():-1)+"\n"+(live.ramUsed()/1048576)+" / "+(live.ramMax()/1048576)+" MiB JVM heap","inline",false));
        }
        if (trim.active()) {
            String trimValue = "**State:** " + trim.phase.name().toLowerCase(Locale.ROOT).replace('_', ' ')
                    + (trim.phase == WorldTrimManager.Phase.COUNTDOWN ? "\n**Starts in:** " + trim.countdownSeconds + " seconds" : "")
                    + (trim.phase == WorldTrimManager.Phase.RUNNING ? "\n**Progress:** " + trim.progress + "%\n**ETA:** " + escapeMarkdown(cleanText(trim.eta, 80)) : "");
            fields.add(mapOf("name", "🛠️ World maintenance", "value", truncate(trimValue, 1024), "inline", false));
        }
        if (plugin.getConfig().getBoolean(designBase + "show-leaderboard", plugin.getConfig().getBoolean("simplex-points.discord-leaderboard", false))) {
            fields.add(mapOf("name", "🏆 Simplex Points — Top 10", "value", truncate(plugin.discordPointsLeaderboard(), 1024), "inline", false));
        }
        String footer = plugin.getConfig().getString(designBase + "footer", "Persistent status • Java and Bedrock")
                .replace("{server}", serverName).replace("{interval}", Integer.toString(Math.max(10, plugin.getConfig().getInt("discord-bot.status.update-interval-seconds", 10))));
        int budget=Math.min(256,title.length())+Math.min(2400,description.length())+Math.min(400,footer.length());
        java.util.Iterator<Map<String,Object>> iterator=fields.iterator();
        while(iterator.hasNext()){
            Map<String,Object> field=iterator.next();String value=string(field.get("value"));
            int available=5900-budget-string(field.get("name")).length();
            if(available<1){iterator.remove();continue;}
            if(value.length()>available)field.put("value",truncate(value,available));
            budget+=string(field.get("name")).length()+string(field.get("value")).length();
        }
        Map<String, Object> embed = mapOf("title", truncate(title, 256), "description", truncate(description, 2400), "color", color, "fields", fields,
                "footer", mapOf("text", truncate(footer, 400)),
                "timestamp", java.time.Instant.ofEpochMilli(snapshot.timestamp).toString());
        return Json.stringify(mapOf("content", "", "embeds", List.of(embed), "allowed_mentions", mapOf("parse", List.of())));
    }

    private String playerList(List<PlayerSnapshot> players) {
        List<String> lines = new ArrayList<>();
        int maximum = Math.max(5, Math.min(50, plugin.getConfig().getInt("discord-bot.status.max-player-lines", 30)));
        for (int index = 0; index < Math.min(players.size(), maximum); index++) {
            PlayerSnapshot player = players.get(index);
            String afk = player.afk ? " 💤 **AFK**" : "";
            String platform = plugin.getConfig().getBoolean("discord-bot.status.design.show-platform", true) ? " — " + escapeMarkdown(player.platform) : "";
            lines.add(player.emoji + " **" + escapeMarkdown(player.name) + "**" + afk + platform);
        }
        if (lines.isEmpty()) return "_No players online_";
        if (players.size() > maximum) lines.add("_…and " + (players.size() - maximum) + " more online players_");
        String text = String.join("\n", lines);
        return truncate(text, 1024);
    }

    private double readTps() {
        try {
            Object value = Bukkit.class.getMethod("getTPS").invoke(null);
            if (value instanceof double[] && ((double[]) value).length > 0) return Math.min(20.0, ((double[]) value)[0]);
        } catch (Exception ignored) { }
        try {
            Object server = Bukkit.class.getMethod("getServer").invoke(null);
            Object value = server.getClass().getMethod("getTPS").invoke(server);
            if (value instanceof double[] && ((double[]) value).length > 0) return Math.min(20.0, ((double[]) value)[0]);
        } catch (Exception ignored) { }
        return 20.0;
    }

    private void request(String method, String path, String body) {
        if (!running.get() && !path.startsWith("/interactions/")) return;
        try { executor.execute(() -> {
            try {
                HttpResponse<String> response = requestSync(method, path, body);
                if (response.statusCode() < 200 || response.statusCode() >= 300) lastError = "Discord HTTP " + response.statusCode();
            } catch (Exception error) {
                lastError = error.getClass().getSimpleName() + ": " + safe(error.getMessage());
            }
        }); } catch (RuntimeException error) { diagnostics.failure(method+" "+DiscordDiagnostics.route(path)+" queue",error.getClass().getSimpleName()); }
    }

    private void requestInteraction(String method, String path, String body, String operation) {
        try {
            interactionExecutor.execute(() -> {
                try {
                    HttpResponse<String> response = requestSync(method, path, body, 5);
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        interactionResponses.incrementAndGet();
                        lastInteractionError = "none";
                        return;
                    }
                    interactionFailures.incrementAndGet();
                    lastInteractionError = "HTTP " + response.statusCode() + " • " + DiscordDiagnostics.detail(response.body(),token());
                    lastError = "Discord " + operation + " HTTP " + response.statusCode();

                } catch (Exception error) {
                    interactionFailures.incrementAndGet();
                    lastInteractionError = error.getClass().getSimpleName() + ": " + safe(error.getMessage());
                    lastError = "Discord " + operation + " " + lastInteractionError;

                }
            });
        } catch (RuntimeException error) {
            interactionFailures.incrementAndGet();
            lastInteractionError = "queue rejected: " + error.getClass().getSimpleName();
            if (running.get()) diagnostics.failure(operation+" queue",error.getClass().getSimpleName());
        }
    }

    private HttpResponse<String> requestSync(String method, String path, String body) throws Exception {
        return requestSync(method, path, body, 15);
    }

    private HttpResponse<String> requestSync(String method, String path, String body, int timeoutSeconds) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(API + path))
                .timeout(Duration.ofSeconds(Math.max(2, Math.min(30, timeoutSeconds))))
                .header("Authorization", "Bot " + token())
                .header("User-Agent", "SimplexAdminSuite/" + plugin.getDescription().getVersion())
                .header("Content-Type", "application/json; charset=utf-8");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        try {
            HttpResponse<String> response=http.send(builder.build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if(response.statusCode()/100!=2)recordHttpFailure(method,path,response.statusCode(),response.body());
            return response;
        }catch(Exception error){diagnostics.failure(method+" "+DiscordDiagnostics.route(path),error.getClass().getSimpleName()+"; request could not be completed");throw error;}

    }

    private void recordHttpFailure(String method,String path,int status,String responseBody){
        String detail="HTTP "+status+" • "+DiscordDiagnostics.detail(responseBody,token());
        String hint=switch(status){case 401->" Check the configured bot token.";case 403->" Check bot channel permissions and role hierarchy.";case 429->" Discord rate limit; retry later.";default->"";};
        if(responseBody!=null&&responseBody.contains("50007"))hint=" Direct messages are disabled or blocked; use the verification button or Discord /link.";
        lastError=detail+hint;diagnostics.failure(method+" "+DiscordDiagnostics.route(path),detail+hint);
    }

    private String token() { return plugin.getConfig().getString("discord-bot.token", "").trim(); }
    private String guildId() { return plugin.getConfig().getString("discord-bot.guild-id", "").trim(); }
    private String relayChannelId() { return plugin.getConfig().getString("discord-bot.relay-channel-id", "").trim(); }
    private String statusChannelId() { return plugin.getConfig().getString("discord-bot.status-channel-id", "").trim(); }
    private boolean adminEnabled() { return plugin.getConfig().getBoolean("discord-bot.admin.enabled", true); }
    private boolean inlineEnabled() { return adminEnabled() && plugin.getConfig().getBoolean("discord-bot.admin.inline.enabled", true); }
    private String inlinePrefix() {
        String value = plugin.getConfig().getString("discord-bot.admin.inline.prefix", "!sas").trim();
        return value.isBlank() || value.length() > 20 || value.chars().anyMatch(Character::isWhitespace) ? "!sas" : value;
    }

    private synchronized void loadState() {
        statusMessageId = "";
        if (!stateFile.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(stateFile);
        statusMessageId = yaml.getString("status-message-id", "").trim();
    }

    private synchronized void saveState() {
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("status-message-id", statusMessageId);
            yaml.set("session-id", sessionId);
            yaml.save(stateFile);
        } catch (Exception error) {
            plugin.getLogger().warning("Discord-Bot-Status konnte nicht gespeichert werden: " + error.getMessage());
        }
    }

    static Map<String, Object> mapOf(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) map.put(String.valueOf(values[i]), values[i + 1]);
        return map;
    }

    private static String decoratedName(String playerName, String decoration) {
        String cleanDecoration = cleanText(decoration, 120);
        return cleanDecoration.isBlank() ? playerName : cleanDecoration + " " + playerName;
    }

    private static String cleanText(String value, int max) {
        if (value == null) return "";
        String clean = MINECRAFT_FORMATTING.matcher(value).replaceAll("").replace('\n', ' ').replace('\r', ' ').trim();
        while (clean.contains("  ")) clean = clean.replace("  ", " ");
        return truncate(clean, max);
    }

    private static String cleanMultiline(String value, int max) {
        if (value == null) return "";
        String clean = MINECRAFT_FORMATTING.matcher(value).replaceAll("").replace('\r', ' ').trim();
        StringBuilder out = new StringBuilder();
        for (String line : clean.split("\\n", -1)) {
            String normalized = line.trim().replaceAll("[ \\t]{2,}", " ");
            if (out.length() > 0) out.append('\n');
            out.append(normalized);
        }
        return truncate(out.toString(), max);
    }

    private static String escapeMarkdown(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("*", "\\*").replace("_", "\\_").replace("`", "\\`").replace("~", "\\~").replace("|", "\\|");
    }

    private static String escapeMassMentions(String value) {
        return value == null ? "" : value.replace("@everyone", "@\u200beveryone").replace("@here", "@\u200bhere");
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, Math.max(0, max - 1)) + "…";
    }

    private static int intValue(Object value, int fallback) { return value instanceof Number ? ((Number) value).intValue() : fallback; }
    private static long longValue(Object value, long fallback) { return value instanceof Number ? ((Number) value).longValue() : fallback; }
    private static String string(Object value) { return value == null ? "" : String.valueOf(value); }
    private static String safe(String value) { return value == null ? "" : value.replace('\n', ' ').replace('\r', ' '); }

    private static String formatBytes(long bytes) {
        String[] units = new String[]{"B", "KiB", "MiB", "GiB", "TiB"};
        double value = Math.max(0L, bytes); int unit = 0;
        while (value >= 1024D && unit < units.length - 1) { value /= 1024D; unit++; }
        return String.format(Locale.ROOT, "%.2f %s", value, units[unit]);
    }

    private enum StatusMode { ONLINE, RESTARTING, OFFLINE }
    private record StatusSnapshot(StatusMode mode, List<PlayerSnapshot> players, int maxPlayers, double tps,
                                  int restartSeconds, WorldTrimManager.TrimStatus trim, long timestamp) { }

    static final class Json {
        private final String source;
        private int index;

        private Json(String source) { this.source = Objects.requireNonNullElse(source, ""); }

        static Object parse(String source) {
            Json parser = new Json(source);
            Object value = parser.readValue();
            parser.skipWhitespace();
            if (parser.index != parser.source.length()) throw new IllegalArgumentException("Trailing JSON");
            return value;
        }

        static String stringify(Object value) {
            StringBuilder out = new StringBuilder();
            write(value, out);
            return out.toString();
        }

        private Object readValue() {
            skipWhitespace();
            if (index >= source.length()) throw new IllegalArgumentException("Unexpected end of JSON");
            char c = source.charAt(index);
            if (c == '{') return readObject();
            if (c == '[') return readArray();
            if (c == '"') return readString();
            if (c == 't' && consume("true")) return Boolean.TRUE;
            if (c == 'f' && consume("false")) return Boolean.FALSE;
            if (c == 'n' && consume("null")) return null;
            return readNumber();
        }

        private Map<String, Object> readObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            index++;
            skipWhitespace();
            if (peek('}')) { index++; return map; }
            while (true) {
                skipWhitespace();
                String key = readString();
                skipWhitespace();
                expect(':');
                map.put(key, readValue());
                skipWhitespace();
                if (peek('}')) { index++; return map; }
                expect(',');
            }
        }

        private List<Object> readArray() {
            List<Object> list = new ArrayList<>();
            index++;
            skipWhitespace();
            if (peek(']')) { index++; return list; }
            while (true) {
                list.add(readValue());
                skipWhitespace();
                if (peek(']')) { index++; return list; }
                expect(',');
            }
        }

        private String readString() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (index < source.length()) {
                char c = source.charAt(index++);
                if (c == '"') return out.toString();
                if (c != '\\') { out.append(c); continue; }
                if (index >= source.length()) throw new IllegalArgumentException("Invalid escape");
                char escape = source.charAt(index++);
                switch (escape) {
                    case '"', '\\', '/' -> out.append(escape);
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (index + 4 > source.length()) throw new IllegalArgumentException("Invalid unicode escape");
                        out.append((char) Integer.parseInt(source.substring(index, index + 4), 16));
                        index += 4;
                    }
                    default -> throw new IllegalArgumentException("Unknown escape");
                }
            }
            throw new IllegalArgumentException("Unterminated string");
        }

        private Number readNumber() {
            int start = index;
            if (peek('-')) index++;
            while (index < source.length() && Character.isDigit(source.charAt(index))) index++;
            boolean decimal = false;
            if (peek('.')) {
                decimal = true; index++;
                while (index < source.length() && Character.isDigit(source.charAt(index))) index++;
            }
            if (peek('e') || peek('E')) {
                decimal = true; index++;
                if (peek('+') || peek('-')) index++;
                while (index < source.length() && Character.isDigit(source.charAt(index))) index++;
            }
            String value = source.substring(start, index);
            if (decimal) return Double.parseDouble(value);
            return Long.parseLong(value);
        }

        private boolean consume(String value) {
            if (!source.startsWith(value, index)) return false;
            index += value.length();
            return true;
        }

        private void expect(char expected) {
            skipWhitespace();
            if (index >= source.length() || source.charAt(index) != expected) throw new IllegalArgumentException("Expected " + expected);
            index++;
        }

        private boolean peek(char value) { return index < source.length() && source.charAt(index) == value; }
        private void skipWhitespace() { while (index < source.length() && Character.isWhitespace(source.charAt(index))) index++; }

        @SuppressWarnings("unchecked")
        private static void write(Object value, StringBuilder out) {
            if (value == null) { out.append("null"); return; }
            if (value instanceof String || value instanceof Character) { writeString(String.valueOf(value), out); return; }
            if (value instanceof Number || value instanceof Boolean) { out.append(value); return; }
            if (value instanceof Map) {
                out.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                    if (!first) out.append(',');
                    first = false;
                    writeString(String.valueOf(entry.getKey()), out);
                    out.append(':');
                    write(entry.getValue(), out);
                }
                out.append('}');
                return;
            }
            if (value instanceof Iterable) {
                out.append('[');
                boolean first = true;
                for (Object item : (Iterable<Object>) value) {
                    if (!first) out.append(',');
                    first = false;
                    write(item, out);
                }
                out.append(']');
                return;
            }
            writeString(String.valueOf(value), out);
        }

        private static void writeString(String value, StringBuilder out) {
            out.append('"');
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                switch (c) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\b' -> out.append("\\b");
                    case '\f' -> out.append("\\f");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> {
                        if (c < 0x20) out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                        else out.append(c);
                    }
                }
            }
            out.append('"');
        }
    }
}
