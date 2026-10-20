package eu.simplexsmp.adminsuite;

import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/** Translation/keybind probes adapted from MIT CheckHacks; all blocks are client-only. */
final class ClientProbeManager {
    enum Result { DETECTED, NOT_DETECTED, PROTECTED, TIMEOUT, SKIPPED, ERROR }
    record Definition(String id,String name,String key,String mode,String fallback,List<String> keys) {
        Definition(String id,String name,String key,String mode,String fallback){this(id,name,key,mode,fallback,List.of(key));}
        Definition { keys=List.copyOf(keys); }
    }
    private final SimplexAdminSuite plugin;
    final ClientProbeAccess access;
    final ClientProbeRules rules;
    final ClientProbeHold hold;
    final ClientProbeMenus menus;
    final ClientProbeDiagnostics diagnostics;
    final ClientProbeHistory history;
    private record Closed(Session session,long until){ }
    private final Map<UUID,Closed> recent=new ConcurrentHashMap<>();
    private final Map<UUID,String> connections=new ConcurrentHashMap<>();
    private final SuitePacketGuard packets;
    private volatile boolean closing;
    private final Map<UUID,Session> active=new ConcurrentHashMap<>();
    private final Map<UUID,Long> cooldown=new HashMap<>();
    private final Map<UUID,Report> reports=new HashMap<>();
    private final Set<UUID> alertsDisabled=new HashSet<>();
    private final Path reportFile;
    private List<Definition> definitions=List.of();
    private Set<String> diagnosticOnly=Set.of();
    private static final String CONTROL="key.forward";
    private record Report(long at,String status,Map<String,Result> results,String brand,String locale,String connection){ }
    record Verdict(Result result,String reason){ }
    private record Challenge(Location location,List<Definition> batch,AtomicBoolean claim,long deadline,boolean confirm,boolean quiet){ }
    private static final class Session {
        final Player target;final CommandSender actor;final List<Definition> all;
        final Map<String,Result> results=new LinkedHashMap<>();final Map<String,Integer> confirmations=new HashMap<>();
        List<Definition> batch=List.of();int index;volatile Location location;volatile long deadline;volatile AtomicBoolean consumed=new AtomicBoolean();
        BukkitTask timeout,notice,limit;boolean relocated;boolean checkingAgain;boolean quiet;int sequence;final Set<String> alerted=new HashSet<>();final String scan=UUID.randomUUID().toString();String connection="";boolean observeOnly,automatic;final Map<String,String> matchedKeys=new LinkedHashMap<>();long diagnosticGeneration;volatile Challenge issued;final Map<String,Challenge> challenges=new ConcurrentHashMap<>();final java.util.concurrent.atomic.AtomicInteger diagnosticPackets=new java.util.concurrent.atomic.AtomicInteger();
        Session(Player p,CommandSender a,List<Definition> defs,boolean quiet){target=p;actor=a;all=defs;this.quiet=quiet;}
    }
    ClientProbeManager(SimplexAdminSuite p,SuitePacketGuard guard){plugin=p;packets=guard;reportFile=p.getDataFolder().toPath().resolve("client-probe-results.yml");access=new ClientProbeAccess(p);rules=new ClientProbeRules(p,this);hold=new ClientProbeHold(this);menus=new ClientProbeMenus(p,this);diagnostics=new ClientProbeDiagnostics(p);history=new ClientProbeHistory(p);reload();load();}
    void reload(){stopActive();access.refreshInvite();
        File file=new File(plugin.getDataFolder(),"client-probes.yml");if(!file.exists())plugin.saveResource("client-probes.yml",false);
        YamlConfiguration yaml=YamlConfiguration.loadConfiguration(file);migrateDefinitions(yaml,file);ConfigurationSection hacks=yaml.getConfigurationSection("hacks");List<Definition> list=new ArrayList<>();
        if(hacks!=null)for(String id:hacks.getKeys(false))try{String key=hacks.getString(id+".key",""),mode=hacks.getString(id+".mode","TRANSLATE").toUpperCase(Locale.ROOT);if(id.matches("[a-z0-9_-]{1,64}")&&!key.isBlank()&&key.length()<200&&Set.of("METEOR","TRANSLATE","KEYBIND").contains(mode))list.add(new Definition(id,hacks.getString(id+".display-name",id),key,mode,hacks.getString(id+".fallback","sas:"+id),definitionKeys(key,hacks.getStringList(id+".keys"))));}catch(IllegalArgumentException invalid){plugin.getLogger().warning("Invalid client signature skipped: "+id);}
        definitions=List.copyOf(list);Set<String> diagnostic=new HashSet<>();if(hacks!=null)for(String id:hacks.getKeys(false))if(hacks.getBoolean(id+".diagnostic-only",false))diagnostic.add(id);diagnosticOnly=Set.copyOf(diagnostic);
    }
    private String t(CommandSender s,String de,String en){return s instanceof Player p?plugin.playerText(p,de,en):de;}
    private boolean permitted(CommandSender s,String node){return plugin.has(s,node)&&(!(s instanceof Player p)||plugin.adminSessionUsable(p));}
    boolean command(CommandSender s,String root,String[] args){
        String permission=root.equals("chreload")?"simplexadminsuite.clientsecurity.admin":root.equals("chalerts")?"simplexadminsuite.clientcheck.alerts":"simplexadminsuite.clientcheck";
        if(!permitted(s,permission)){s.sendMessage(t(s,"Keine Berechtigung: ","Missing permission: ")+permission);return true;}
        if(root.equals("clientcheck")&&args.length>0&&args[0].equalsIgnoreCase("diagnose"))return diagnose(s,args);
        if(root.equals("clientcheck")&&args.length>0&&args[0].equalsIgnoreCase("testkey"))return testKeyCommand(s,args);
        if(root.equals("clientcheck")&&args.length>0&&Set.of("channel","rule","approve","queue","settings").contains(args[0].toLowerCase(Locale.ROOT)))return manage(s,args);
        if(root.equals("chreload")){reload();s.sendMessage(t(s,"Client-Definitionen neu geladen.","Client definitions reloaded."));return true;}
        if(root.equals("chalerts")){if(s instanceof Player p){if(!alertsDisabled.remove(p.getUniqueId()))alertsDisabled.add(p.getUniqueId());s.sendMessage(t(s,"Prüfalarme: ","Probe alerts: ")+!alertsDisabled.contains(p.getUniqueId()));}return true;}
        if(args.length==0){if(s instanceof Player p)menu(p);else s.sendMessage("/checkhacks <player> [all|mod1,mod2|status|stop] • /checklang <player>");return true;}
        UUID uuid=plugin.discordAdminResolvePlayer(args[0]);if(uuid==null){s.sendMessage(t(s,"Spielerprofil nicht gefunden.","Player profile not found."));return true;}
        Player target=Bukkit.getPlayer(uuid);
        if(root.equals("checklang")){s.sendMessage(target==null?t(s,"Spieler offline.","Player offline."):target.getName()+" • "+t(s,"Gemeldete Locale: ","Reported locale: ")+target.getLocale()+t(s," (Client-Angabe, kein Beweis)"," (client-supplied, not proof)"));return true;}
        String option=args.length>1?args[1].toLowerCase(Locale.ROOT):"selected";
        if(option.equals("status")){s.sendMessage(summary(s,uuid));return true;}
        if(option.equals("stop")){Session check=active.get(uuid);if(check!=null)finish(check,"STOPPED");s.sendMessage(t(s,"Prüfung beendet.","Probe stopped."));return true;}
        if(target==null){s.sendMessage(t(s,"Spieler offline.","Player offline."));return true;}
        Set<String> selected=new HashSet<>(Arrays.asList(option.split(",")));List<Definition> defs=option.equals("all")?selectedAll(definitions):option.equals("selected")?joinDefinitions():definitions.stream().filter(d->selected.contains(d.id)).toList();
        if(defs.isEmpty()||!Set.of("all","selected").contains(option)&&defs.size()!=selected.size()){s.sendMessage(t(s,"Unbekannte Definition. IDs: ","Unknown definition. IDs: ")+definitions.stream().map(Definition::id).reduce((a,b)->a+", "+b).orElse("—"));return true;}
        start(s,target,defs,option.equals("all")||allOnJoin(),false);return true;
    }
    boolean managedTeleport(Player p){return plugin.managedProbeTeleport(p);}
    Map<String,Object> reportData(UUID id){Report r=reports.get(id);if(r==null)return Map.of();Map<String,String> values=new LinkedHashMap<>();r.results.forEach((k,v)->values.put(k,v.name()));return Map.of("at",r.at,"status",r.status,"connection",r.connection,"brand",r.brand,"locale",r.locale,"results",values);}
    List<String> definitionIds(){return definitions.stream().map(Definition::id).toList();}
    String definitionName(String id){return definitions.stream().filter(d->d.id.equals(id)).map(Definition::name).findFirst().orElse(id);}
    boolean alertsEnabled(UUID id){return !alertsDisabled.contains(id);}
    List<Definition> definitions(){return definitions;}
    boolean diagnosticOnly(String id){return diagnosticOnly!=null&&diagnosticOnly.contains(id);}
    boolean enabled(String id){return plugin.getConfig().getBoolean(ClientProbeRules.ROOT+"rules."+id+".enabled",true);}
    List<Definition> selected(List<Definition> input){return input.stream().filter(d->enabled(d.id)).distinct().toList();}
    List<Definition> selectedAll(List<Definition> input){return input.stream().filter(d->enabled(d.id)).distinct().toList();}
    boolean allOnJoin(){return plugin.getConfig().getBoolean(ClientProbeRules.ROOT+"all-on-join",false);}
    void setJoinSelected(String id,boolean selected){
        if(!definitionIds().contains(id))return;
        // Convert 'all' into an explicit selection before excluding a single check.
        Set<String> ids=new LinkedHashSet<>();for(Definition d:joinDefinitions())ids.add(d.id());
        if(selected){ids.add(id);plugin.getConfig().set(ClientProbeRules.ROOT+"rules."+id+".enabled",true);}else ids.remove(id);
        plugin.getConfig().set(ClientProbeRules.ROOT+"all-on-join",false);
        plugin.getConfig().set(ClientProbeRules.ROOT+"join-clients",new ArrayList<>(ids));
        plugin.saveConfig();stopActive();
    }
    void allJoin(boolean on){plugin.getConfig().set(ClientProbeRules.ROOT+"all-on-join",on);if(on){plugin.getConfig().set(ClientProbeRules.ROOT+"enabled",true);plugin.getConfig().set(ClientProbeRules.ROOT+"auto-on-join",true);}if(on)for(Definition d:definitions)plugin.getConfig().set(ClientProbeRules.ROOT+"rules."+d.id+".enabled",true);plugin.saveConfig();stopActive();}
    void clearJoin(){plugin.getConfig().set(ClientProbeRules.ROOT+"all-on-join",false);plugin.getConfig().set(ClientProbeRules.ROOT+"join-clients",List.of());plugin.saveConfig();stopActive();}
    List<String> joinIds(){if(allOnJoin())return selectedAll(definitions).stream().map(Definition::id).toList();List<String> configured=plugin.getConfig().getStringList(ClientProbeRules.ROOT+"join-clients");return (configured.contains("all")?definitions.stream().map(Definition::id):configured.stream()).filter(definitionIds()::contains).distinct().toList();}
    List<Definition> joinDefinitions(){Set<String> ids=new HashSet<>(joinIds());return allOnJoin()?selectedAll(definitions):selected(definitions.stream().filter(d->ids.contains(d.id)).toList());}
    boolean toggleJoin(String id){if(allOnJoin()){toggleEnabled(id);return true;}List<String> ids=new ArrayList<>(joinIds());if(ids.contains(id)&&!enabled(id)){plugin.getConfig().set(ClientProbeRules.ROOT+"rules."+id+".enabled",true);plugin.saveConfig();return true;}if(!ids.remove(id)){ids.add(id);}plugin.getConfig().set(ClientProbeRules.ROOT+"join-clients",ids);plugin.saveConfig();return true;}
    void toggleEnabled(String id){boolean enable=!enabled(id);plugin.getConfig().set(ClientProbeRules.ROOT+"rules."+id+".enabled",enable);if(!enable&&!allOnJoin()){List<String> ids=new ArrayList<>(joinIds());ids.remove(id);plugin.getConfig().set(ClientProbeRules.ROOT+"join-clients",ids);}plugin.saveConfig();stopActive();}
    static String validKey(String key){String clean=Objects.requireNonNull(key).strip();if(!clean.matches("[a-zA-Z0-9_.:/-]{1,160}"))throw new IllegalArgumentException("Invalid keybind key");return clean;}
    static String validName(String name){String clean=MenuStyle.plain(name).strip();if(clean.isBlank()||clean.length()>48||clean.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid name");return clean;}
    String addCustom(String actor,String name,String key)throws IOException{String label=validName(name),bind=validKey(key);if(definitions.stream().anyMatch(d->d.key.equals(bind)))throw new IllegalArgumentException("Key already exists");String id="custom-"+UUID.randomUUID().toString().substring(0,8);File file=new File(plugin.getDataFolder(),"client-probes.yml");YamlConfiguration y=YamlConfiguration.loadConfiguration(file);String path="hacks."+id;y.set(path+".display-name",label);y.set(path+".key",bind);y.set(path+".mode","KEYBIND");y.set(path+".custom",true);saveDefinitions(y,file);reload();plugin.expansionAudit(actor,null,"Custom probe created",id+" • "+label);return id;}
    void deleteCustom(String actor,String id)throws IOException{if(!id.startsWith("custom-"))throw new IllegalArgumentException("Built-in definition");File file=new File(plugin.getDataFolder(),"client-probes.yml");YamlConfiguration y=YamlConfiguration.loadConfiguration(file);y.set("hacks."+id,null);saveDefinitions(y,file);List<String> ids=new ArrayList<>(joinIds());ids.remove(id);plugin.getConfig().set(ClientProbeRules.ROOT+"join-clients",ids);plugin.getConfig().set(ClientProbeRules.ROOT+"rules."+id,null);plugin.saveConfig();reload();plugin.expansionAudit(actor,null,"Custom probe removed",id);}
    private void saveDefinitions(YamlConfiguration y,File file)throws IOException{Path temp=Files.createTempFile(file.toPath().getParent(),"client-definitions-",".tmp");try{y.save(temp.toFile());try{Files.move(temp,file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException unsupported){Files.move(temp,file.toPath(),StandardCopyOption.REPLACE_EXISTING);}}finally{Files.deleteIfExists(temp);}}
    void auto(Player target){if(plugin.emergency(target.getUniqueId()))return;if(plugin.getConfig().getBoolean(ClientProbeRules.ROOT+"auto-on-join",true)){List<Definition> selected=joinDefinitions();if(!selected.isEmpty())start(Bukkit.getConsoleSender(),target,selected,allOnJoin(),false,true);}}
    void interrupt(Player player){Session s=active.get(player.getUniqueId());if(s!=null)finish(s,"SESSION_CHANGED");else hold.end(player);}
    void joined(Player player){if(plugin.isBedrockClient(player))return;String connection=UUID.randomUUID().toString();if(history!=null)history.join(player,connection);recent.remove(player.getUniqueId());connections.put(player.getUniqueId(),connection);diagnostics.record(player.getUniqueId(),new ClientProbeDiagnostics.Entry(System.currentTimeMillis(),"",connection,player.getName(),ClientProbeDiagnostics.brand(player),player.getLocale(),"JOIN",List.of(),List.of(),"Java join; reported metadata, not client identity proof"));}
    void start(CommandSender actor,Player target,List<Definition> defs){start(actor,target,defs,allOnJoin(),false);}
    void start(CommandSender actor,Player target,List<Definition> defs,boolean full,boolean observeOnly){start(actor,target,defs,full,observeOnly,false);}
    private void start(CommandSender actor,Player target,List<Definition> defs,boolean full,boolean observeOnly,boolean automatic){
        defs=observeOnly?defs:full?selectedAll(defs):selected(defs);if(defs.isEmpty()){actor.sendMessage(t(actor,"Keine aktiven Prüfungen ausgewählt.","No enabled probes selected."));return;}
        if(!plugin.getConfig().getBoolean("client-security.probes.enabled",true)){actor.sendMessage(t(actor,"Client-Prüfungen deaktiviert.","Client probes disabled."));return;}
        if(plugin.isBedrockClient(target)){actor.sendMessage(t(actor,"Java-Übersetzungsprüfungen gelten nicht für Bedrock; Bedrock-Gerätedaten bleiben im Profil.","Java translation probes do not apply to Bedrock; device metadata remains in the profile."));return;}
        if(plugin.adminRuntime.blocked(target)||!target.isOnline()||active.containsKey(target.getUniqueId())){actor.sendMessage(t(actor,"Spieler noch gesperrt oder Prüfung läuft bereits.","Player is not admitted or already being checked."));return;}
        if(!packets.available(target.getUniqueId())){packets.join(target);actor.sendMessage(t(actor,"Paketadapter noch nicht verfügbar. Erneut versuchen; es wurde keine Prüfung gestartet.","Packet adapter not available yet. Retry; no probe was started."));return;}
        long now=System.currentTimeMillis(),minimum=Math.max(5,plugin.getConfig().getInt("client-security.probes.cooldown-seconds",30))*1000L;
        if(automatic&&!observeOnly&&now-cooldown.getOrDefault(target.getUniqueId(),0L)<minimum){actor.sendMessage(t(actor,"Prüf-Cooldown aktiv.","Probe cooldown active."));return;}
        if(automatic&&!observeOnly)cooldown.put(target.getUniqueId(),now);Session s=new Session(target,actor,expand(defs),quietMode(plugin.getConfig().getString("client-security.probes.mode","quiet")));s.connection=connections.computeIfAbsent(target.getUniqueId(),id->UUID.randomUUID().toString());s.observeOnly=observeOnly;s.automatic=automatic;s.diagnosticGeneration=diagnostics.generation(target.getUniqueId());Closed previous=recent.remove(target.getUniqueId());if(previous!=null&&previous.until>System.nanoTime()&&s.connection.equals(previous.session.connection)){s.sequence=previous.session.sequence;long nowNano=System.nanoTime();previous.session.challenges.forEach((pos,c)->{if(c.deadline+10_000_000_000L>nowNano)s.challenges.put(pos,c);});}active.put(target.getUniqueId(),s);log(s,"START",defs,List.of(),"",Map.of(),"Selected="+defs.size()+"; diagnosticOnly="+observeOnly);
        int maximum=sessionBudget(s.all.size(),plugin.getConfig().getInt(ClientProbeRules.ROOT+"max-session-seconds",15),plugin.getConfig().getInt(ClientProbeRules.ROOT+"quiet-timeout-seconds",3),plugin.getConfig().getInt(ClientProbeRules.ROOT+"timeout-seconds",10));
        s.limit=Bukkit.getScheduler().runTaskLater(plugin,()->{if(active.get(target.getUniqueId())==s){if(s.issued!=null)for(Definition d:s.batch)s.results.putIfAbsent(d.id,Result.TIMEOUT);log(s,"SESSION_TIMEOUT",s.batch,List.of(),"",Map.of(),"Total time limit; unissued probes skipped");finish(s,"COMPLETE");}},maximum*20L);
        try{if(plugin.getConfig().getBoolean(ClientProbeRules.ROOT+"hold-player",true))hold.begin(target,plugin.getConfig().getBoolean(ClientProbeRules.ROOT+"darken-screen",true),maximum);}
        catch(RuntimeException failed){plugin.getLogger().warning("Probe hold unavailable: "+failed.getMessage());finish(s,"ADAPTER_ERROR");return;}
        plugin.expansionAudit(actor.getName(),target.getUniqueId(),"Client probe started",defs.stream().map(Definition::id).toList().toString());
        if(!automatic&&!(actor instanceof org.bukkit.command.ConsoleCommandSender))actor.sendMessage(target.getName()+": "+t(actor,"Client-Prüfung gestartet; Ergebnis ist ein Hinweis, keine Garantie.","Client probe started; results are signals, not guarantees."));
        if(plugin.getConfig().getBoolean("client-security.probes.show-progress",true))s.notice=Bukkit.getScheduler().runTaskTimer(plugin,()->{if(active.get(target.getUniqueId())==s&&target.isOnline())target.sendActionBar(plugin.playerText(target,"§eKurze Überprüfung, bitte warten … ","§eQuick verification, please wait … ")+Math.min(100,s.index*100/Math.max(1,s.all.size()))+"%");},0L,10L);
        next(s);
    }
    private void next(Session s){
        if(active.get(s.target.getUniqueId())!=s)return;if(!s.target.isOnline()){finish(s,"DISCONNECTED");return;}
        if(s.index>=s.all.size()) {finish(s,"COMPLETE");return;}
        if(!s.checkingAgain)s.batch=s.all.subList(s.index,Math.min(s.index+3,s.all.size()));
        Location loc=ProbeRelocation.find(s.target,s.quiet,++s.sequence,s.challenges.keySet());
        if(loc==null&&!s.relocated&&plugin.securityHub!=null){s.relocated=true;if(plugin.securityHub.relocation.begin(s.target)){Bukkit.getScheduler().runTaskLater(plugin,()->next(s),3L);return;}}
        if(loc==null){log(s,"SKIPPED",s.batch,List.of(),"",Map.of(),"No safe loaded-chunk sign position; sky fallback unavailable/disabled");for(Definition d:s.batch)mergeResult(s.results,d.id,Result.SKIPPED);finish(s,"NO_SPACE");return;}
        s.location=loc;int timeout=timeoutSeconds(s.quiet,plugin.getConfig().getInt("client-security.probes.quiet-timeout-seconds",3),plugin.getConfig().getInt("client-security.probes.timeout-seconds",10));s.deadline=System.nanoTime()+timeout*1_000_000_000L;s.consumed=new AtomicBoolean();AtomicBoolean claim=s.consumed;Location issued=loc;Challenge challenge=new Challenge(loc,List.copyOf(s.batch),claim,s.deadline,s.checkingAgain,s.quiet);s.issued=challenge;s.challenges.put(positionKey(loc.getBlockX(),loc.getBlockY(),loc.getBlockZ()),challenge);log(s,"ISSUED",s.batch,List.of(),"",Map.of(),"mode="+(s.quiet?"quiet":"legacy")+"; confirmation="+s.checkingAgain+"; position="+positionKey(loc.getBlockX(),loc.getBlockY(),loc.getBlockZ()));
        try{
            Sign sign=(Sign)Material.OAK_SIGN.createBlockData().createBlockState();
            for(int i=0;i<3;i++){Definition d=i<s.batch.size()?s.batch.get(i):null;sign.getSide(Side.FRONT).line(i,d==null?Component.empty():d.mode.equals("KEYBIND")?Component.keybind(d.key):Component.translatable(d.key,d.fallback));}
            sign.getSide(Side.FRONT).line(3,Component.keybind(CONTROL));
            Object editorPacket=SignProbeAdapter.open(loc.getBlockX(),loc.getBlockY(),loc.getBlockZ());
            transmit(s.quiet,()->{s.target.sendBlockChange(issued,Material.OAK_SIGN.createBlockData());s.target.sendBlockUpdate(issued,sign);},()->SuitePacketGuard.send(s.target,editorPacket),()->{if(active.get(s.target.getUniqueId())==s&&s.location==issued&&s.consumed==claim)restore(s);},action->Bukkit.getScheduler().runTaskLater(plugin,action,1L));
            s.timeout=Bukkit.getScheduler().runTaskLater(plugin,()->{if(active.get(s.target.getUniqueId())!=s||s.consumed!=claim||!claim.compareAndSet(false,true))return;restore(s);log(s,"TIMEOUT",s.batch,List.of(),"",Map.of(),"No response; legacyRetry="+(s.quiet&&plugin.getConfig().getBoolean("client-security.probes.quiet-fallback",true)));if(s.quiet&&plugin.getConfig().getBoolean("client-security.probes.quiet-fallback",true)){s.quiet=false;schedule(s);return;}for(Definition d:s.batch)mergeResult(s.results,d.id,Result.TIMEOUT);advance(s);},timeout*20L);
        }catch(ReflectiveOperationException|RuntimeException|LinkageError e){restore(s);log(s,"ADAPTER_ERROR",s.batch,List.of(),"",Map.of(),e.getClass().getSimpleName()+": "+e.getMessage());for(Definition d:s.batch)mergeResult(s.results,d.id,Result.ERROR);plugin.getLogger().warning("Client probe adapter: "+e.getClass().getSimpleName()+" "+e.getMessage());finish(s,"ADAPTER_ERROR");}
    }
    /** Runs in the connection's Netty event loop. Claim only this player's exact active fake sign. */
    private static boolean clientAir(Material type){return type==Material.AIR||type==Material.CAVE_AIR||type==Material.VOID_AIR;}
    private static String positionKey(int x,int y,int z){return x+","+y+","+z;}
    void purgeClosed(){long now=System.nanoTime();recent.entrySet().removeIf(e->e.getValue().until<now);}
    private Session replySession(UUID id){Session s=active.get(id);if(s!=null)return s;Closed previous=recent.get(id);if(previous==null)return null;if(previous.until<System.nanoTime()){recent.remove(id,previous);return null;}return previous.session;}
    boolean receive(UUID id,Object packet){
        if(closing)return false;Session s=replySession(id);if(s==null)return false;
        try{
            Object pos=SuitePacketGuard.call(packet,"getPos","pos");int x=((Number)SuitePacketGuard.call(pos,"getX")).intValue(),y=((Number)SuitePacketGuard.call(pos,"getY")).intValue(),z=((Number)SuitePacketGuard.call(pos,"getZ")).intValue();
            Challenge c=s.challenges.get(positionKey(x,y,z));if(c==null)return false;
            String[] lines=SignProbeAdapter.lines(packet);boolean front=SignProbeAdapter.front(packet);
            if(active.get(id)!=s){if(s.diagnosticPackets.incrementAndGet()<=512){List<String> late=ClientProbeDiagnostics.safeLines(lines);Bukkit.getScheduler().runTask(plugin,()->{if(s.target.isOnline()&&s.target.getWorld().equals(c.location.getWorld())&&c.location.getWorld().isChunkLoaded(x>>4,z>>4)&&clientAir(c.location.getBlock().getType()))log(s,"LATE_REPLY_AFTER_FINISH",c.batch,late,"",Map.of(),"position="+x+","+y+","+z+"; front="+front+"; lines="+lines.length);});}return false;}
            String issue=!front?"WRONG_SIDE":lines.length!=4?"INVALID_LINE_COUNT":s.issued!=c?"STALE_CHALLENGE":System.nanoTime()>c.deadline?"LATE_REPLY":c.claim.get()?"DUPLICATE_REPLY":"";
            if(!issue.isEmpty()){ignored(s,c,lines,issue);return true;}
            if(active.get(id)!=s||s.issued!=c){ignored(s,c,lines,"SESSION_CHANGED");return true;}
            if(!c.claim.compareAndSet(false,true)){ignored(s,c,lines,"DUPLICATE_REPLY");return true;}
            Bukkit.getScheduler().runTask(plugin,()->{if(active.get(id)==s&&s.issued==c)response(s,lines);else log(s,"IGNORED_REPLY",c.batch,ClientProbeDiagnostics.safeLines(lines),"",Map.of(),"Session ended before reply dispatch");});return true;
        }catch(ReflectiveOperationException|RuntimeException e){if(!closing&&s.diagnosticPackets.incrementAndGet()<=512)try{Bukkit.getScheduler().runTask(plugin,()->log(s,"REPLY_ADAPTER_ERROR",s.batch,List.of(),"",Map.of(),e.getClass().getSimpleName()+": "+e.getMessage()));}catch(RuntimeException disabled){/* Connection shutdown; no further scheduling. */}return false;}
    }
    private void ignored(Session s,Challenge c,String[] raw,String reason){if(s.diagnosticPackets.incrementAndGet()>512)return;List<String> lines=ClientProbeDiagnostics.safeLines(raw);Bukkit.getScheduler().runTask(plugin,()->log(s,"IGNORED_REPLY",c.batch,lines,lines.size()>3?lines.get(3):"",Map.of(),reason+"; position="+positionKey(c.location.getBlockX(),c.location.getBlockY(),c.location.getBlockZ())+"; lineCount="+raw.length));}
    private void response(Session s,String[] lines){
        if(active.get(s.target.getUniqueId())!=s)return;if(s.timeout!=null)s.timeout.cancel();if(!s.target.isOnline()||s.location==null||!s.target.getWorld().equals(s.location.getWorld())){finish(s,"SESSION_CHANGED");return;}restore(s);
        boolean validControl=!lines[3].isBlank()&&!lines[3].equalsIgnoreCase(CONTROL);boolean suspected=false;List<ClientProbeDiagnostics.Row> diagnosticRows=new ArrayList<>();
        for(int i=0;i<s.batch.size();i++){
            Definition d=s.batch.get(i);Verdict verdict=evaluateDetailed(d,lines[i],validControl);Result r=verdict.result;String stage="FINAL";
            if(s.checkingAgain){boolean confirmed=r==Result.DETECTED&&s.confirmations.getOrDefault(signature(d),0)==1;mergeResult(s.results,d.id,confirmed?Result.DETECTED:Result.PROTECTED);if(confirmed)s.matchedKeys.put(d.id,d.key);stage=confirmed?"CONFIRMED":"CONFIRMATION_FAILED";}
            else if(r==Result.DETECTED&&(plugin.getConfig().getBoolean("client-security.probes.confirm-positive",true)||Set.of("kick","tempban").contains(rules.rule(d.id).action())||rules.rule(d.id).reviewAfter()>0)){s.confirmations.put(signature(d),1);suspected=true;stage="PENDING_CONFIRMATION";}else{mergeResult(s.results,d.id,r);if(r==Result.DETECTED)s.matchedKeys.put(d.id,d.key);stage=r==Result.DETECTED?"SINGLE_SIGNAL":"FINAL";}
            diagnosticRows.add(new ClientProbeDiagnostics.Row(d.id,d.name,d.key,d.mode,d.fallback,lines[i],r.name(),verdict.reason,stage));
        }
        diagnostics.recordCurrent(s.target.getUniqueId(),s.diagnosticGeneration,entry(s,s.checkingAgain?"CONFIRMATION_REPLY":"REPLY",ClientProbeDiagnostics.safeLines(lines),diagnosticRows,"controlValid="+validControl+"; front=true; position="+positionKey(s.location.getBlockX(),s.location.getBlockY(),s.location.getBlockZ())));
        for(Definition d:s.batch)if(!s.observeOnly&&s.results.get(d.id)==Result.DETECTED&&s.alerted.add(d.id))rules.confirmed(s.target,d.id,s.actor.getName(),s.checkingAgain);
        if(suspected&&!s.checkingAgain){s.checkingAgain=true;s.batch=s.batch.stream().filter(d->s.confirmations.containsKey(signature(d))).toList();schedule(s);}
        else advance(s);
    }
    static Result evaluate(Definition d,String response,boolean validControl){return evaluateDetailed(d,response,validControl).result;}
    static Verdict evaluateDetailed(Definition d,String response,boolean validControl){
        if(!validControl)return new Verdict(Result.PROTECTED,"CONTROL_UNTRANSLATED_OR_EMPTY");
        String value=response==null?"":response.strip();if(value.isEmpty())return new Verdict(Result.PROTECTED,"EMPTY_RESPONSE");if(value.length()>384)return new Verdict(Result.PROTECTED,"OVERSIZE_RESPONSE");
        if(value.length()==d.key.length()+1&&value.regionMatches(true,0,d.key,0,d.key.length())&&Character.isLetter(value.charAt(value.length()-1)))return new Verdict(Result.PROTECTED,"KEY_WITH_ADDED_LETTER");
        if(d.mode.equals("METEOR")){if(d.fallback.isBlank())return new Verdict(Result.PROTECTED,"INVALID_FALLBACK");if(value.equalsIgnoreCase(d.key))return new Verdict(Result.DETECTED,"METEOR_RAW_KEY_SIGNAL");if(value.regionMatches(true,0,d.fallback,0,d.fallback.length()))return new Verdict(Result.NOT_DETECTED,"METEOR_FALLBACK");return new Verdict(Result.DETECTED,"METEOR_TRANSLATED_SIGNAL");}
        if(d.mode.equals("KEYBIND"))return new Verdict(value.equalsIgnoreCase(d.key)?Result.NOT_DETECTED:Result.DETECTED,value.equalsIgnoreCase(d.key)?"KEYBIND_NOT_REGISTERED":"KEYBIND_RESOLVED");
        return new Verdict(value.equals(d.fallback)?Result.NOT_DETECTED:value.equalsIgnoreCase(d.key)?Result.PROTECTED:Result.DETECTED,value.equals(d.fallback)?"TRANSLATION_FALLBACK":value.equalsIgnoreCase(d.key)?"TRANSLATION_RAW_KEY":"TRANSLATION_RESOLVED");
    }
    private ClientProbeDiagnostics.Entry entry(Session s,String phase,List<String> lines,List<ClientProbeDiagnostics.Row> rows,String detail){return new ClientProbeDiagnostics.Entry(System.currentTimeMillis(),s.scan,s.connection,s.target.getName(),ClientProbeDiagnostics.brand(s.target),s.target.getLocale(),phase,List.copyOf(lines),List.copyOf(rows),detail);}
    private void log(Session s,String phase,List<Definition> defs,List<String> lines,String control,Map<String,Result> results,String detail){List<ClientProbeDiagnostics.Row> rows=new ArrayList<>();for(int i=0;i<defs.size();i++){Definition d=defs.get(i);rows.add(new ClientProbeDiagnostics.Row(d.id,d.name,d.key,d.mode,d.fallback,i<lines.size()?lines.get(i):"",results.containsKey(d.id)?results.get(d.id).name():phase,phase,"EVENT"));}diagnostics.recordCurrent(s.target.getUniqueId(),s.diagnosticGeneration,entry(s,phase,lines,rows,detail));}
    private void advance(Session s){s.index+=3;s.checkingAgain=false;s.location=null;schedule(s);}
    private void schedule(Session s){Bukkit.getScheduler().runTaskLater(plugin,()->next(s),s.quiet||plugin.getConfig().getBoolean(ClientProbeRules.ROOT+"experimental-fast",false)?1L:Math.max(2,Math.min(40,plugin.getConfig().getInt("client-security.probes.between-sign-ticks",5))));}
    private void restore(Session s){Location loc=s.location;if(loc!=null&&s.target.isOnline()&&s.target.getWorld().equals(loc.getWorld())&&loc.getWorld().isChunkLoaded(loc.getBlockX()>>4,loc.getBlockZ()>>4))s.target.sendBlockChange(loc,loc.getBlock().getBlockData());}
    private void finish(Session s,String status){
        if(!active.remove(s.target.getUniqueId(),s))return;if(s.timeout!=null)s.timeout.cancel();if(s.notice!=null)s.notice.cancel();if(s.limit!=null)s.limit.cancel();hold.end(s.target);if(plugin.securityHub!=null&&!plugin.securityHub.relocation.restore(s.target))plugin.getLogger().warning("Original probe location remains queued for recovery: "+s.target.getUniqueId());if(s.target.isOnline())recent.put(s.target.getUniqueId(),new Closed(s,System.nanoTime()+10_000_000_000L));restore(s);if(s.target.isOnline()&&plugin.getConfig().getBoolean("client-security.probes.show-progress",true))s.target.sendActionBar(plugin.playerText(s.target,"§aÜberprüfung beendet.","§aVerification finished."));
        for(Definition d:s.all)s.results.putIfAbsent(d.id,status.equals("ADAPTER_ERROR")?Result.ERROR:Result.SKIPPED);
        Report previous=reports.get(s.target.getUniqueId());Map<String,Result> observations=new LinkedHashMap<>();if(previous!=null&&previous.connection.equals(s.connection))observations.putAll(previous.results);for(var result:s.results.entrySet())if(result.getValue()!=Result.SKIPPED||!observations.containsKey(result.getKey()))observations.put(result.getKey(),result.getValue());
        reports.put(s.target.getUniqueId(),new Report(System.currentTimeMillis(),status,Collections.unmodifiableMap(observations),ClientProbeDiagnostics.brand(s.target),s.target.getLocale(),s.connection));save();
        log(s,"FINISHED",s.all,List.of(),"",s.results,status);
        ClientProbeRules.ActionReport action=ClientProbeRules.ActionReport.none();
        if(!s.observeOnly&&status.equals("COMPLETE")&&s.target.isOnline())action=rules.completed(s.target,s.results,s.actor.getName());
        if(history!=null)history.record(s.target,s.scan,s.connection,s.actor.getName(),s.automatic,s.observeOnly,status,s.results,s.matchedKeys,action);
        String report=summary(s.actor,s.target.getUniqueId());if(s.actor instanceof Player player&&!player.getUniqueId().equals(s.target.getUniqueId()))s.actor.sendMessage(report);
        consoleSummary(s);
        discordSummary(s,status,action);
        notifySummary(s);
        plugin.expansionAudit(s.actor.getName(),s.target.getUniqueId(),"Client probe summary",status+" • "+s.results+" • "+action.detail());

    }
    static boolean quietMode(String mode){return !"legacy".equalsIgnoreCase(mode);}
    static int timeoutSeconds(boolean quiet,int fast,int legacy){return Math.max(2,Math.min(30,quiet?fast:legacy));}
    @FunctionalInterface interface PacketAction{void run()throws ReflectiveOperationException;}
    static void transmit(boolean quiet,PacketAction signData,PacketAction editor,Runnable restore,java.util.function.Consumer<Runnable> later)throws ReflectiveOperationException{
        signData.run();editor.run();if(quiet)restore.run();else later.accept(restore);
    }

    static String statusText(String status,boolean de){return switch(status){
        case "COMPLETE" -> de?"Prüfung abgeschlossen":"Scan completed";
        case "ADAPTER_ERROR" -> de?"Technischer Fehler im Schildadapter; kein Client-Nachweis":"Sign adapter error; no client evidence";
        case "NO_SPACE" -> de?"Kein freier Schildplatz in geladenen Chunks":"No free sign position in loaded chunks";
        case "STOPPED" -> de?"Vom Team beendet":"Stopped by staff";
        case "SESSION_CHANGED" -> de?"Verbindung oder Spielerzustand geändert":"Connection or player state changed";
        case "DISCONNECTED" -> de?"Spieler hat den Server verlassen":"Player disconnected";
        default -> status;
    };}
    static String resultText(Result result,boolean de){if(!de)return resultText(result);return switch(result){
        case DETECTED->"§c● ERKANNT";case NOT_DETECTED->"§a● NICHT ERKANNT";
        case PROTECTED->"§e● NICHT AUSWERTBAR";case TIMEOUT->"§e● KEINE ANTWORT";
        case ERROR->"§c● TECHNISCHER FEHLER";case SKIPPED->"§7● NICHT AUSGEFÜHRT";
    };}
    static String resultText(Result result){return switch(result){case DETECTED->"§c● DETECTED";case NOT_DETECTED->"§a● NOT_DETECTED";case PROTECTED,TIMEOUT->"§e● "+result;case ERROR->"§c● ERROR";default->"§7● SKIPPED";};}
    private void notifySummary(Session s){if(!plugin.getConfig().getBoolean(ClientProbeRules.ROOT+"admin-result-summary",true))return;for(Player admin:Bukkit.getOnlinePlayers()){
        if(admin.getUniqueId().equals(s.target.getUniqueId())||s.actor instanceof Player actor&&admin.getUniqueId().equals(actor.getUniqueId())||!permitted(admin,"simplexadminsuite.clientcheck.alerts")||!alertsEnabled(admin.getUniqueId()))continue;
        List<String> groups=new ArrayList<>();for(Result result:Result.values()){List<String> names=s.results.entrySet().stream().filter(e->e.getValue()==result&&rules.rule(e.getKey()).staffAlert()).map(e->definitionName(e.getKey())).toList();if(!names.isEmpty())groups.add(resultText(result,t(admin,"de","en").equals("de"))+" §7"+String.join(", ",names.stream().limit(5).toList())+(names.size()>5?" +"+(names.size()-5):""));}
        if(!groups.isEmpty())admin.sendMessage(plugin.prefix()+"§f"+s.target.getName()+" §8• "+t(admin,s.observeOnly?"Keybind-Diagnose (ohne Maßnahmen)":"Client-Prüfung",s.observeOnly?"Keybind diagnostics (no actions)":"Client scan")+"\n"+String.join("\n",groups));
    }}
    String summary(CommandSender sender,UUID id){Report r=reports.get(id);Player online=Bukkit.getPlayer(id);String currentBrand=online==null?plugin.knownClientBrand(id):ClientProbeDiagnostics.brand(online);StringBuilder out=new StringBuilder("§f"+plugin.discordAdminPlayerName(id)+" §8• "+(online==null?"§7Offline":"§aOnline")+"\n");
        out.append("§7").append(t(sender,"Gemeldeter Brand: ","Reported brand: ")).append(ClientProbeDiagnostics.escaped(currentBrand)).append("\n");
        if(active.containsKey(id))out.append(t(sender,"§ePrüfung läuft.\n","§eScan in progress.\n"));
        if(r==null)return out+t(sender,"§7Noch keine Client-Prüfung.","§7No client probe recorded.");
        boolean thisConnection=online!=null&&r.connection.equals(connections.get(id));out.append(t(sender,thisConnection?"§fLetzte Beobachtung in dieser Verbindung: ":"§7Letzte bekannte Prüfung: ",thisConnection?"§fLast observation in this connection: ":"§7Last known scan: ")).append(new Date(r.at)).append(" • ").append(statusText(r.status,t(sender,"de","en").equals("de"))).append("\n");
        if(history!=null)out.append(history.connectionSummary(id,this::definitionName,t(sender,"de","en").equals("de"))).append("\n");
        out.append("§7").append(t(sender,"Brand/Locale damals: ","Brand/locale then: ")).append(ClientProbeDiagnostics.escaped(r.brand)).append(" / ").append(ClientProbeDiagnostics.escaped(r.locale)).append("\n");
        out.append(t(sender,"§7Kein Treffer beweist keinen sauberen Client.\n","§7No detection is not proof of a clean client.\n"));
        var state=access.state(id);out.append("§7Access: ").append(state.review()?"§cTEAM_REVIEW":state.banUntil()>System.currentTimeMillis()?"§cTEMPBAN":"§aOPEN").append(" §7• strikes: ").append(state.strikes()).append("\n");
        for(var e:r.results.entrySet())out.append(resultText(e.getValue(),t(sender,"de","en").equals("de"))).append(" §f").append(definitionName(e.getKey())).append(" §8(").append(e.getKey()).append(")\n");return out.toString();
    }
    boolean diagnose(CommandSender sender,String[] args){if(args.length<2){sender.sendMessage("/sas clientcheck diagnose <player> [console|menu|clear]");return true;}UUID id=plugin.discordAdminResolvePlayer(args[1]);if(id==null){sender.sendMessage(t(sender,"Spielerprofil nicht gefunden.","Player profile not found."));return true;}String option=args.length>2?args[2].toLowerCase(Locale.ROOT):"console";
        if(option.equals("clear")){if(!permitted(sender,"simplexadminsuite.clientsecurity.admin")){sender.sendMessage(t(sender,"Keine Einstellungsrechte.","Missing settings permission."));return true;}if(active.containsKey(id)){sender.sendMessage(t(sender,"Prüfung zuerst stoppen.","Stop the active scan first."));return true;}try{diagnostics.clear(id);if(history!=null)history.clear(id);Report previous=reports.remove(id);if(!save()){if(previous!=null)reports.put(id,previous);throw new IOException("Could not clear summary");}plugin.expansionAudit(sender.getName(),id,"Client diagnostic history cleared","Probe bans/review untouched");sender.sendMessage(t(sender,"§aDiagnose und Prüfergebnisse geleert. Sperren bleiben erhalten.","§aDiagnostics and probe results cleared. Restrictions remain in place."));}catch(IOException error){sender.sendMessage(t(sender,"§cProtokoll konnte nicht sicher geleert werden.","§cCould not durably clear diagnostics."));}return true;}
        if(option.equals("menu")&&sender instanceof Player p){diagnosticMenu(p,id);return true;}if(!option.equals("console")){sender.sendMessage("console | menu | clear");return true;}diagnostics.dump(id);plugin.getLogger().info(MenuStyle.plain(summary(sender,id)));sender.sendMessage(summary(sender,id));sender.sendMessage(t(sender,"§7Rohdiagnose in der Konsole: ","§7Raw diagnostics printed to console: ")+diagnostics.entries(id).size());return true;
    }
    boolean testKeyCommand(CommandSender sender,String[] args){if(!permitted(sender,"simplexadminsuite.clientsecurity.admin")){sender.sendMessage(t(sender,"Keine Einstellungsrechte.","Missing settings permission."));return true;}if(args.length!=3){sender.sendMessage("/sas clientcheck testkey <player> <registered-key>");return true;}UUID id=plugin.discordAdminResolvePlayer(args[1]);Player target=id==null?null:Bukkit.getPlayer(id);if(target==null){sender.sendMessage(t(sender,"Spieler offline.","Player offline."));return true;}try{testKey(sender,target,args[2]);}catch(IllegalArgumentException invalid){sender.sendMessage(t(sender,"§cUngültiger Keybind-Schlüssel.","§cInvalid keybind key."));}return true;}
    void testKey(CommandSender actor,Player target,String key){String valid=validKey(key);start(actor,target,List.of(new Definition("custom-test","Custom: "+valid,valid,"KEYBIND","sas:custom-test")),false,true);}
    void testSaved(CommandSender actor,Player target,String id){definitions.stream().filter(d->d.id.equals(id)).findFirst().ifPresent(d->start(actor,target,List.of(d),false,true));}
    private String menuText(Player p,String de,String en){return de;}
    void diagnosticMenu(Player p,UUID id){if(!permitted(p,"simplexadminsuite.clientcheck"))return;List<AdminUpdateMenu.Button> b=new ArrayList<>();b.add(new AdminUpdateMenu.Button(menuText(p,"Client / letzte Verbindung","Client / last connection"),menuText(p,"Bericht mit Zeitstempel","Timestamped report"),Material.BOOK,()->{if(permitted(p,"simplexadminsuite.clientcheck"))plugin.adminUpdate.detail(p,menuText(p,"Client-Bericht","Client report"),summary(p,id),()->diagnosticMenu(p,id));}));
        List<ClientProbeDiagnostics.Entry> entries=new ArrayList<>(diagnostics.entries(id));Collections.reverse(entries);for(var entry:entries){boolean positive=entry.rows().stream().anyMatch(row->row.result().equals("DETECTED")&&row.stage().equals("CONFIRMED"));b.add(new AdminUpdateMenu.Button(entry.phase()+" • "+new java.text.SimpleDateFormat("HH:mm:ss").format(new Date(entry.at())),entry.rows().stream().map(row->row.name()+": "+row.result()+" / "+row.stage()).reduce((x,y)->x+" • "+y).orElse(entry.detail()),positive?Material.REDSTONE_BLOCK:Material.WRITABLE_BOOK,()->{if(permitted(p,"simplexadminsuite.clientcheck"))plugin.adminUpdate.detail(p,menuText(p,"Rohantwort / Diagnose","Raw reply / diagnostics"),formatEntry(p,entry),()->diagnosticMenu(p,id));}));}
        b.add(new AdminUpdateMenu.Button(menuText(p,"In Konsole ausgeben","Print to console"),Integer.toString(entries.size()),Material.COMPARATOR,()->{if(permitted(p,"simplexadminsuite.clientcheck")){diagnostics.dump(id);p.sendMessage(menuText(p,"§aDiagnose ausgegeben.","§aDiagnostics printed."));}diagnosticMenu(p,id);}));
        if(permitted(p,"simplexadminsuite.clientsecurity.admin"))b.add(new AdminUpdateMenu.Button(menuText(p,"Spielerprotokoll leeren","Clear player diagnostics"),menuText(p,"Bestätigung folgt; Sperren bleiben erhalten","Confirmation required; keeps restrictions"),Material.BARRIER,()->clearMenu(p,id)));
        b.add(new AdminUpdateMenu.Button(menuText(p,"Zurück","Back"),"",Material.OAK_DOOR,()->playerMenu(p,id)));plugin.adminUpdate.show(p,menuText(p,"Client-Diagnose","Client diagnostics"),b,0);
    }
    private void clearMenu(Player p,UUID id){if(!permitted(p,"simplexadminsuite.clientsecurity.admin"))return;plugin.adminUpdate.show(p,menuText(p,"Protokoll leeren?","Clear diagnostics?"),List.of(new AdminUpdateMenu.Button(menuText(p,"Jetzt leeren","Clear now"),menuText(p,"Nur Diagnose/Ergebnisse dieses Spielers","Only this player's diagnostics/results"),Material.BARRIER,()->{if(permitted(p,"simplexadminsuite.clientsecurity.admin"))diagnose(p,new String[]{"diagnose",id.toString(),"clear"});diagnosticMenu(p,id);}),new AdminUpdateMenu.Button(menuText(p,"Zurück","Back"),"",Material.OAK_DOOR,()->diagnosticMenu(p,id))),0);}
    private String formatEntry(CommandSender viewer,ClientProbeDiagnostics.Entry e){StringBuilder text=new StringBuilder(new Date(e.at())+" • "+e.phase()+"\nScan: "+e.scan()+"\nConnection: "+e.connection()+"\nBrand: "+ClientProbeDiagnostics.escaped(e.brand())+" • Locale: "+ClientProbeDiagnostics.escaped(e.locale())+"\n"+e.detail()+"\n");for(int i=0;i<e.lines().size();i++)text.append("\n").append(t(viewer,i==3?"Kontrolle: ":"Zeile "+i+": ",i==3?"Control: ":"Line "+i+": ")).append(ClientProbeDiagnostics.escaped(e.lines().get(i)));for(var row:e.rows()){text.append("\n\n§f").append(row.name()).append(" • ");try{text.append(resultText(Result.valueOf(row.result())));}catch(IllegalArgumentException event){text.append("§7").append(row.result());}text.append(t(viewer,"\n§7Stufe: ","\n§7Stage: ")).append(row.stage()).append(t(viewer,"\nGrund: ","\nReason: ")).append(row.reason()).append(t(viewer,"\nModus: ","\nMode: ")).append(row.mode()).append(t(viewer,"\nSchlüssel: ","\nKey: ")).append(ClientProbeDiagnostics.escaped(row.key())).append("\nFallback: ").append(ClientProbeDiagnostics.escaped(row.fallback())).append(t(viewer,"\nAntwort: ","\nReply: ")).append(ClientProbeDiagnostics.escaped(row.response()));}return text.toString();}
    void menu(Player p){if(!permitted(p,"simplexadminsuite.clientcheck"))return;List<AdminUpdateMenu.Button> buttons=new ArrayList<>();
        buttons.add(AdminUpdateMenu.info(menuText(p,"Client-Prüfung","Client probes"),menuText(p,"Nur Java. Übersetzungs-/Tastenhinweise können blockiert oder gefälscht sein. Kein Treffer bedeutet keine Entwarnung.","Java only. Translation/keybind signals can be blocked or forged. No detection does not mean a clean client."),Material.SHIELD));
        if(permitted(p,"simplexadminsuite.clientsecurity.admin"))buttons.add(new AdminUpdateMenu.Button(menuText(p,"Einstellungen","Settings"),menuText(p,"Sperren sind standardmäßig aus","Blocking is off by default"),Material.COMPARATOR,()->settings(p)));
        for(SimplexAdminSuite.PlayerProfile profile:plugin.expansionProfiles())buttons.add(new AdminUpdateMenu.Button(profile.displayName(),Bukkit.getPlayer(profile.uniqueId())==null?menuText(p,"Offline • Letzte Ergebnisse anzeigen","Offline • View last results"):menuText(p,"Online • Prüfen oder Ergebnisse anzeigen","Online • Scan or view results"),Material.PLAYER_HEAD,()->playerMenu(p,profile.uniqueId())));

        buttons.add(new AdminUpdateMenu.Button(menuText(p,"Zurück","Back"),"",Material.OAK_DOOR,()->plugin.unifiedMenu.open(p)));plugin.adminUpdate.show(p,menuText(p,"Client-Prüfung","Client probes"),buttons,0);
    }
    void playerMenu(Player p,UUID id){if(!permitted(p,"simplexadminsuite.clientcheck"))return;List<AdminUpdateMenu.Button> b=new ArrayList<>();Player target=Bukkit.getPlayer(id);
        b.add(AdminUpdateMenu.info(plugin.discordAdminPlayerName(id),summary(p,id),Material.BOOK));
        if(permitted(p,"simplexadminsuite.clientsecurity.admin"))b.add(new AdminUpdateMenu.Button(menuText(p,"Fingerprint & Verhaltenshinweise","Fingerprint & behavior hints"),menuText(p,"Optional; Verlauf separat gespeichert","Optional; separate persistent history"),Material.NAME_TAG,()->plugin.detectors.player(p,id)));
        b.add(new AdminUpdateMenu.Button(menuText(p,"Vollständiges Ergebnis","Full report"),menuText(p,"Letzte Prüfung mit Zeitstempel","Last probe with timestamp"),Material.WRITABLE_BOOK,()->{if(permitted(p,"simplexadminsuite.clientcheck"))plugin.adminUpdate.detail(p,menuText(p,"Prüfergebnis","Probe result"),summary(p,id),()->playerMenu(p,id));}));
        b.add(new AdminUpdateMenu.Button(menuText(p,"Prüfverlauf / Verbindungen","Scan history / connections"),menuText(p,"Gespeicherte Prüfungen, Treffer und Maßnahmen","Saved scans, detections and actions"),Material.CLOCK,()->historyMenu(p,id)));
        b.add(new AdminUpdateMenu.Button(menuText(p,"Rohantworten / Diagnose","Raw replies / diagnostics"),menuText(p,"Alle protokollierten Prüfantworten","All retained probe replies"),Material.WRITABLE_BOOK,()->diagnosticMenu(p,id)));
        if(target!=null&&!plugin.isBedrockClient(target))b.add(new AdminUpdateMenu.Button(menuText(p,"Alle aktiven Checks prüfen","Scan all enabled checks"),Integer.toString(selectedAll(definitions).size()),Material.SHIELD,()->{if(permitted(p,"simplexadminsuite.clientcheck")&&target!=null)start(p,target,definitions,true,false);playerMenu(p,id);}));
        if(target!=null&&!plugin.isBedrockClient(target)&&permitted(p,"simplexadminsuite.clientsecurity.admin"))b.add(new AdminUpdateMenu.Button(menuText(p,"Eigenen Keybind direkt testen","Test a custom keybind now"),menuText(p,"Einmaliger Test ohne Bann/Kick","One-time test without bans/kicks"),Material.TRIPWIRE_HOOK,()->plugin.adminUpdate.prompt(p,menuText(p,"Registrierter Keybind-Schlüssel","Registered keybind key"),"key.example.toggle",key->{if(permitted(p,"simplexadminsuite.clientsecurity.admin")&&target!=null)try{testKey(p,target,key);}catch(IllegalArgumentException invalid){p.sendMessage(menuText(p,"§cUngültiger Schlüssel.","§cInvalid key."));}playerMenu(p,id);})));
        if(target!=null&&!plugin.isBedrockClient(target))b.add(new AdminUpdateMenu.Button(menuText(p,"Ausgewählte Prüfungen starten","Start selected probes"),target==null?"Offline":packets.available(id)?"Paketadapter aktiv":"Paketadapter wird verbunden",Material.SPYGLASS,()->{if(permitted(p,"simplexadminsuite.clientcheck")&&target!=null)start(p,target,joinDefinitions());playerMenu(p,id);}));
        for(Definition d:definitions)if(target!=null&&!plugin.isBedrockClient(target)&&enabled(d.id))b.add(new AdminUpdateMenu.Button(d.name,(reports.containsKey(id)?resultText(reports.get(id).results.getOrDefault(d.id,Result.SKIPPED),true):"—")+" • "+d.key,ClientProbeMenus.icon(d.id),()->{if(permitted(p,"simplexadminsuite.clientcheck")&&target!=null)start(p,target,List.of(d));playerMenu(p,id);}));
        b.add(new AdminUpdateMenu.Button(menuText(p,"Prüfung stoppen","Stop probe"),"",Material.BARRIER,()->{if(permitted(p,"simplexadminsuite.clientcheck")){Session s=active.get(id);if(s!=null)finish(s,"STOPPED");}playerMenu(p,id);}));
        b.add(new AdminUpdateMenu.Button(menuText(p,"Zurück","Back"),"",Material.OAK_DOOR,()->menu(p)));plugin.adminUpdate.show(p,plugin.discordAdminPlayerName(id),b,0);
    }
    void settings(Player p){menus.home(p);}
    private boolean manage(CommandSender s,String[] args){
        if(!permitted(s,"simplexadminsuite.clientsecurity.admin")){s.sendMessage(t(s,"Keine Einstellungsrechte.","Missing settings permission."));return true;}
        switch(args[0].toLowerCase(Locale.ROOT)){
            case "settings"->{if(s instanceof Player p)settings(p);}
            case "queue"->s.sendMessage(queue());
            case "channel"->s.sendMessage(channel(s.getName(),args.length>1?args[1]:"status"));
            case "approve"->{if(args.length<2)s.sendMessage("/sas clientcheck approve <player>");else s.sendMessage(approve(s.getName(),args[1],!(s instanceof Player)));}
            case "rule"->{if(args.length<3)s.sendMessage("/sas clientcheck rule <client-id> <none|notify|kick|tempban> [15m] [review-after]");else try{s.sendMessage(rules.configure(s.getName(),args[1],args[2],args.length>3?args[3]:"15m",args.length>4?Integer.parseInt(args[4]):0));}catch(NumberFormatException invalid){s.sendMessage("review-after: 0–100");}}
        }return true;
    }
    String channel(String actor,String value){if(plugin.discordRelay()==null)return "Discord-Bot nicht initialisiert.";
        String path="discord-bot.moderation-log.client-probes-channel-id";if(value.equals("status"))return "Client-Kanal: "+plugin.getConfig().getString(path,"");
        if(value.equals("create"))return plugin.discordRelay().createModerationChannel("client-probes",actor);
        if(!value.equals("off")&&!value.matches("[0-9]{15,22}"))return "Kanal-ID, create oder off.";
        if(value.equals("off")){plugin.getConfig().set(path,"");plugin.saveConfig();return "Client-Kanal deaktiviert.";}
        return plugin.discordRelay().setClientProbeChannel(actor,value);
    }
    String approve(String actor,String query,boolean discord){
        if(!discord&&plugin.getConfig().getBoolean("client-security.probes.review-discord-only",true))return "Freigabe in Discord: /sasadmin client approve player:"+query;
        UUID id=plugin.discordAdminResolvePlayer(query);if(id==null)return "Spielerprofil nicht gefunden.";
        try{access.release(id);plugin.expansionAudit(actor,id,"Client access approved","Only client-probe bans/review cleared; other moderation remains effective.");if(plugin.discordRelay()!=null)plugin.discordRelay().clientProbeAlert(plugin.discordAdminPlayerName(id),id,"APPROVED",List.of(),actor);return "Client-Freigabe gespeichert: "+plugin.discordAdminPlayerName(id)+". Künftige Prüfungen bleiben aktiv.";}catch(IOException e){return "Freigabe konnte nicht gespeichert werden.";}
    }
    String queue(){return access.reviewQueue().stream().map(id->plugin.discordAdminPlayerName(id)+" • "+id+" • "+access.state(id).clients()).reduce((a,b)->a+"\n"+b).orElse("Keine ausstehenden Client-Freigaben.");}
    void reviewMenu(Player p){if(!permitted(p,"simplexadminsuite.clientsecurity.admin"))return;List<AdminUpdateMenu.Button> b=new ArrayList<>();for(UUID id:access.reviewQueue())b.add(new AdminUpdateMenu.Button(plugin.discordAdminPlayerName(id),String.join(", ",access.state(id).clients()),Material.PLAYER_HEAD,()->{if(permitted(p,"simplexadminsuite.clientsecurity.admin"))p.sendMessage(t(p,approve(p.getName(),id.toString(),false),plugin.getConfig().getBoolean(ClientProbeRules.ROOT+"review-discord-only",true)?"Approval in Discord: /sasadmin client approve player:"+id:!ClientProbeAccess.restricted(access.state(id),System.currentTimeMillis())?"Probe access approved.":"Could not save approval."));reviewMenu(p);}));b.add(new AdminUpdateMenu.Button(t(p,"Zurück","Back"),"",Material.OAK_DOOR,()->settings(p)));plugin.adminUpdate.show(p,t(p,"Team-Freigaben","Staff approvals"),b,0);}
    String discord(String actor,String action,String player,String clients,String client,String ruleAction,String duration,int review){
        if(action.equals("queue"))return queue();if(action.equals("approve"))return approve(actor,player,true);if(action.equals("channel"))return channel(actor,clients);if(action.equals("rule"))return rules.configure(actor,client,ruleAction,duration.isBlank()?"15m":duration,review);
        UUID id=plugin.discordAdminResolvePlayer(player);if(id==null)return "Spielerprofil nicht gefunden.";if(action.equals("status"))return MenuStyle.plain(summary(Bukkit.getConsoleSender(),id));
        if(!action.equals("scan"))return "Unbekannte Client-Aktion.";
        StringBuilder output=new StringBuilder();CommandSender sender=(CommandSender)java.lang.reflect.Proxy.newProxyInstance(CommandSender.class.getClassLoader(),new Class<?>[]{CommandSender.class},(proxy,method,args)->switch(method.getName()){
            case "getName"->"Discord/"+actor;case "hasPermission","isPermissionSet","isOp"->true;case "sendMessage"->{if(args!=null)for(Object value:args)if(value instanceof String text)output.append(text).append('\n');else if(value instanceof String[] lines)for(String line:lines)output.append(line).append('\n');yield null;}case "getServer"->Bukkit.getServer();case "hashCode"->System.identityHashCode(proxy);case "equals"->proxy==args[0];default->null;});
        command(sender,"checkhacks",new String[]{player,clients.isBlank()?"all":clients});return output.toString();
    }
    void quit(Player p){Session s=active.get(p.getUniqueId());if(s!=null)finish(s,"DISCONNECTED");cooldown.remove(p.getUniqueId());alertsDisabled.remove(p.getUniqueId());connections.remove(p.getUniqueId());recent.remove(p.getUniqueId());}
    void stopActive(){for(Session s:new ArrayList<>(active.values()))finish(s,"STOPPED");}
    void stop(){closing=true;try{stopActive();save();}finally{recent.clear();diagnostics.close();if(history!=null)history.close();}}
    static List<String> definitionKeys(String primary,List<String> extra){LinkedHashSet<String> keys=new LinkedHashSet<>();keys.add(validKey(primary));for(String key:extra)keys.add(validKey(key));return List.copyOf(keys);}
    static String signature(Definition d){return d.id+"|"+d.mode+"|"+d.key;}
    static List<Definition> expand(List<Definition> input){List<Definition> expanded=new ArrayList<>();for(Definition d:input)for(String key:d.keys)expanded.add(new Definition(d.id,d.name,key,d.mode,d.fallback));return List.copyOf(expanded);}
    static void mergeResult(Map<String,Result> results,String id,Result next){results.merge(id,next,(previous,current)->rank(previous)>=rank(current)?previous:current);}
    private static int rank(Result r){return switch(r){case DETECTED->6;case PROTECTED->5;case ERROR->4;case TIMEOUT->3;case NOT_DETECTED->2;case SKIPPED->1;};}
    static int sessionBudget(int signatures,int configured,int quiet,int legacy){long batches=(signatures+2L)/3;long perBatch=2L*(timeoutSeconds(true,quiet,legacy)+timeoutSeconds(false,quiet,legacy))+4;return (int)Math.min(3600,Math.max(Math.max(5,configured),batches*perBatch+5));}
    private void migrateDefinitions(YamlConfiguration yaml,File file){
        if(yaml.getBoolean("catalog-migrated-719",false))return;
        try(java.io.InputStream in=plugin.getResource("client-probes.yml")){
            if(in==null)return;YamlConfiguration builtins=YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(in,java.nio.charset.StandardCharsets.UTF_8));ConfigurationSection source=builtins.getConfigurationSection("hacks");
            if(source!=null)for(String id:source.getKeys(false)){String path="hacks."+id;if(!yaml.contains(path)){yaml.createSection(path,source.getConfigurationSection(id).getValues(false));for(String setting:List.of("action","alert-admins","alert-discord")){String rulePath=ClientProbeRules.ROOT+"rules."+id+"."+setting;if(!plugin.getConfig().isSet(rulePath))plugin.getConfig().set(rulePath,setting.equals("action")?"none":true);}}else if(id.equals("litematica")&&"litematica.hotkey.name.openmainmenuscreen".equals(yaml.getString(path+".key"))){LinkedHashSet<String> keys=new LinkedHashSet<>(yaml.getStringList(path+".keys"));keys.addAll(source.getStringList(id+".keys"));yaml.set(path+".keys",new ArrayList<>(keys));}}
            yaml.set("catalog-migrated-719",true);saveDefinitions(yaml,file);plugin.saveConfig();
        }catch(IOException error){plugin.getLogger().warning("Client catalog migration failed: "+error.getMessage());}
    }
    String sourceInfo(String id){YamlConfiguration y=YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(),"client-probes.yml"));return y.getString("hacks."+id+".verification","User-defined / version dependent");}
    String saveCustom(String actor,String id,String name,String mode,String fallback,List<String> keys)throws IOException{
        String label=validName(name);if(!Set.of("TRANSLATE","KEYBIND","METEOR").contains(mode))throw new IllegalArgumentException("Invalid mode");if(keys.isEmpty())throw new IllegalArgumentException("Keys required");List<String> validated=definitionKeys(keys.get(0),keys);if(validated.contains(CONTROL))throw new IllegalArgumentException("Control key is not a mod signature");String backup=validKey(fallback);if(validated.contains(backup)||backup.equals(CONTROL))throw new IllegalArgumentException("Fallback must differ from keys/control");
        if(id==null)id="custom-"+UUID.randomUUID().toString().substring(0,8);if(!id.matches("custom-[a-z0-9_-]{1,48}"))throw new IllegalArgumentException("Custom definition required");
        File file=new File(plugin.getDataFolder(),"client-probes.yml");YamlConfiguration y=YamlConfiguration.loadConfiguration(file);String path="hacks."+id;y.set(path+".display-name",label);y.set(path+".key",validated.get(0));y.set(path+".keys",validated);y.set(path+".mode",mode);y.set(path+".fallback",backup);y.set(path+".custom",true);saveDefinitions(y,file);
        if(!plugin.getConfig().isSet(ClientProbeRules.ROOT+"rules."+id+".action")){for(String setting:List.of("action","alert-admins","alert-discord")){String rulePath=ClientProbeRules.ROOT+"rules."+id+"."+setting;if(!plugin.getConfig().isSet(rulePath))plugin.getConfig().set(rulePath,setting.equals("action")?"none":true);}plugin.saveConfig();}reload();plugin.expansionAudit(actor,null,"Custom probe saved",id+" • "+mode+" • keys="+validated.size());return id;
    }
    void consoleSummary(Session s){if(!plugin.getConfig().getBoolean(ClientProbeRules.ROOT+"console-summary",true))return;List<String> lines=new ArrayList<>();lines.add("§f[ClientCheck] "+MenuStyle.plain(s.target.getName())+" • "+s.results.size()+" checks"+(s.observeOnly?" • diagnostic only":""));for(var e:s.results.entrySet())lines.add(resultText(e.getValue())+" §f"+MenuStyle.plain(definitionName(e.getKey())));CommandSender console=Bukkit.getConsoleSender();if(console!=null)console.sendMessage(String.join("\n",lines));}
    void discordSummary(Session s,String status,ClientProbeRules.ActionReport action){if(s.observeOnly||!plugin.getConfig().getBoolean(ClientProbeRules.ROOT+"discord-alerts",true)||plugin.discordRelay()==null)return;boolean flagged=s.results.entrySet().stream().anyMatch(e->e.getValue()==Result.DETECTED&&rules.rule(e.getKey()).discordAlert());if(!flagged&&action.action().equals("NONE")&&!s.actor.getName().startsWith("Discord/"))return;List<String> lines=s.results.entrySet().stream().sorted(Comparator.comparingInt(e->e.getValue()==Result.DETECTED?0:e.getValue()==Result.NOT_DETECTED?2:1)).map(e->(e.getValue()==Result.DETECTED?"🔴 ":e.getValue()==Result.NOT_DETECTED?"🟢 ":"🟡 ")+definitionName(e.getKey())+": "+e.getValue()).toList();plugin.discordRelay().clientProbeAlert(s.target.getName(),s.target.getUniqueId(),action.action().equals("NONE")?status:action.action(),lines,"Prüfer: "+s.actor.getName()+" • "+(s.automatic?"Join":"Manuell")+" • Brand: "+ClientProbeDiagnostics.brand(s.target)+" • "+action.detail());}
    void historyMenu(Player p,UUID id){if(!permitted(p,"simplexadminsuite.clientcheck"))return;List<AdminUpdateMenu.Button>b=new ArrayList<>();if(history!=null)for(var scan:history.entries(id)){b.add(new AdminUpdateMenu.Button(ClientProbeHistory.timestamp(scan.at())+" • "+statusText(scan.status(),true),scan.detected(this::definitionName)+" • "+scan.action(),scan.results().containsValue("DETECTED")?Material.REDSTONE_BLOCK:Material.BOOK,()->{if(permitted(p,"simplexadminsuite.clientcheck"))plugin.adminUpdate.detail(p,menuText(p,"Gespeicherte Client-Prüfung","Saved client scan"),scan.format(this::definitionName,true),()->historyMenu(p,id));}));}if(permitted(p,"simplexadminsuite.clientsecurity.admin"))b.add(new AdminUpdateMenu.Button(menuText(p,"Verlauf leeren","Clear history"),menuText(p,"Löscht Verlauf und Rohdiagnose; Sperren bleiben","Clears scans and diagnostics; keeps restrictions"),Material.BARRIER,()->clearMenu(p,id)));b.add(new AdminUpdateMenu.Button(menuText(p,"Zurück","Back"),"",Material.OAK_DOOR,()->playerMenu(p,id)));plugin.adminUpdate.show(p,menuText(p,"Client-Prüfverlauf","Client scan history"),b,0);}
    private void load(){if(!Files.exists(reportFile))return;YamlConfiguration y=YamlConfiguration.loadConfiguration(reportFile.toFile());ConfigurationSection section=y.getConfigurationSection("players");if(section==null)return;
        for(String raw:section.getKeys(false))try{String path="players."+raw;Map<String,Result> found=new LinkedHashMap<>();ConfigurationSection result=y.getConfigurationSection(path+".results");if(result!=null)for(String id:result.getKeys(false))try{found.put(id,Result.valueOf(result.getString(id)));}catch(IllegalArgumentException ignored){}reports.put(UUID.fromString(raw),new Report(y.getLong(path+".at"),y.getString(path+".status","UNKNOWN"),Map.copyOf(found),y.getString(path+".brand",""),y.getString(path+".locale",""),y.getString(path+".connection","")));}catch(IllegalArgumentException ignored){}
    }
    private boolean save(){try{YamlConfiguration y=new YamlConfiguration();y.set("schema",1);for(var e:reports.entrySet()){String path="players."+e.getKey();y.set(path+".at",e.getValue().at);y.set(path+".status",e.getValue().status);y.set(path+".brand",e.getValue().brand);y.set(path+".locale",e.getValue().locale);y.set(path+".connection",e.getValue().connection);for(var result:e.getValue().results.entrySet())y.set(path+".results."+result.getKey(),result.getValue().name());}Path temp=reportFile.resolveSibling(reportFile.getFileName()+".tmp");y.save(temp.toFile());try{Files.move(temp,reportFile,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temp,reportFile,StandardCopyOption.REPLACE_EXISTING);}return true;}catch(IOException e){plugin.getLogger().warning("Client probe results could not be saved: "+e.getMessage());return false;}}
}
