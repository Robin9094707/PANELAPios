package eu.simplexsmp.adminsuite;

import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.server.ServerListPingEvent;
import org.bukkit.event.server.TabCompleteEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.scheduler.BukkitTask;

/** Optional integrations share one owner and preserve existing Suite stores. */
final class QualityOfLifeManager implements Listener {
    final VisualIdentityManager names;
    final ClientProbeManager probes;
    final SuitePacketGuard packets;
    private final SimplexAdminSuite plugin;
    private final Properties modes=new Properties();
    private final Path modeFile;
    private final Map<UUID,Boolean> mapOriginal=new HashMap<>();
    private final Set<UUID> metadataOwned=new HashSet<>();
    private final Map<UUID,Set<String>> hiddenNames=new ConcurrentHashMap<>();
    private Object mapApi;
    private Class<?> webApiType;
    private BukkitTask timer;
    private String mapStatus="BlueMap not loaded";
    private long lastError;
    private int ticks;
    private boolean running;
    QualityOfLifeManager(SimplexAdminSuite p){
        plugin=p;modeFile=p.getDataFolder().toPath().resolve("bluemap-visibility.properties");
        if(Files.exists(modeFile))try(Reader in=Files.newBufferedReader(modeFile)){modes.load(in);}catch(IOException e){throw new IllegalStateException("Cannot read BlueMap visibility preferences",e);}
        names=new VisualIdentityManager(p);packets=new SuitePacketGuard(p);probes=new ClientProbeManager(p,packets);packets.probes=probes;
    }
    void start(){Bukkit.getPluginManager().registerEvents(probes.access,plugin);Bukkit.getPluginManager().registerEvents(probes.hold,plugin);running=true;timer=Bukkit.getScheduler().runTaskTimer(plugin,this::tick,1L,20L);}
    void stop(){running=false;if(timer!=null)timer.cancel();probes.stop();packets.stop();names.stop();restoreMap();for(Player p:Bukkit.getOnlinePlayers())clearMetadata(p);hiddenNames.clear();}
    void reload(){probes.reload();restoreMap();mapApi=null;webApiType=null;names.tick();tick();}
    private void tick(){
        if(!running)return;
        ticks++;plugin.refreshDiscordInvites();probes.purgeClosed();
        List<Player> online=new ArrayList<>(Bukkit.getOnlinePlayers());
        for(Player p:online){boolean hidden=plugin.adminRuntime.vanished(p)||plugin.completeStealth(p);if(hidden){p.setMetadata("vanished",new FixedMetadataValue(plugin,true));metadataOwned.add(p.getUniqueId());}else clearMetadata(p);}
        for(Player viewer:online){Set<String> hidden=new HashSet<>();Set<UUID> ids=new HashSet<>();Set<Integer> entities=new HashSet<>();
            for(Player target:online)if(!target.equals(viewer)&&(plugin.adminRuntime.vanished(target)||!viewer.canSee(target)||plugin.discordAdminHidden(target)&&!plugin.has(viewer,"simplexadminsuite.admin"))){hidden.add(target.getName().toLowerCase(Locale.ROOT));ids.add(target.getUniqueId());entities.add(target.getEntityId());}
            hiddenNames.put(viewer.getUniqueId(),Set.copyOf(hidden));packets.snapshot(viewer,ids,entities,hidden);
        }
        if(ticks%Math.max(1,Math.min(30,plugin.getConfig().getInt("nameplates.refresh-seconds",2)))==0)names.tick();
        syncMap();
    }
    void visibilityChanged(Player player){if(player==null||!running)return;if(plugin.adminRuntime.vanished(player)||plugin.completeStealth(player)){player.setMetadata("vanished",new FixedMetadataValue(plugin,true));metadataOwned.add(player.getUniqueId());}else clearMetadata(player);tick();}
    void prepareReveal(Player player){
        packets.reveal(player.getUniqueId(),player.getEntityId(),player.getName());
        String name=player.getName().toLowerCase(Locale.ROOT);
        hiddenNames.replaceAll((viewer,old)->{Set<String> copy=new HashSet<>(old);copy.remove(name);return Set.copyOf(copy);});
    }
    private void clearMetadata(Player p){if(metadataOwned.remove(p.getUniqueId()))p.removeMetadata("vanished",plugin);}
    private boolean mapHidden(Player p){return "hidden".equals(modes.getProperty(p.getUniqueId().toString(),"vanish"))||p.hasPermission("simplexadminsuite.bluemap.hidden")||plugin.discordAdminHidden(p);}
    private void syncMap(){
        if(!plugin.getConfig().getBoolean("integrations.bluemap.enabled",true)){restoreMap();mapStatus="BlueMap integration disabled";return;}
        org.bukkit.plugin.Plugin dep=Bukkit.getPluginManager().getPlugin("BlueMap");
        if(dep==null||!dep.isEnabled()){mapApi=null;webApiType=null;mapOriginal.clear();mapStatus="BlueMap not installed / disabled";return;}
        try{
            ClassLoader loader=dep.getClass().getClassLoader();Class<?> api=Class.forName("de.bluecolored.bluemap.api.BlueMapAPI",true,loader);
            Object instance=((Optional<?>)api.getMethod("getInstance").invoke(null)).orElse(null);
            if(instance==null){mapStatus="BlueMap loading";return;}
            Object web=api.getMethod("getWebApp").invoke(instance);
            if(mapApi!=web){mapApi=web;mapOriginal.clear();webApiType=Class.forName("de.bluecolored.bluemap.api.WebApp",true,loader);}
            mapStatus="BlueMap API connected";
            for(Player p:Bukkit.getOnlinePlayers()){
                UUID id=p.getUniqueId();boolean hidden=mapHidden(p);
                if(hidden){if(!mapOriginal.containsKey(id))mapOriginal.put(id,(Boolean)webApiType.getMethod("getPlayerVisibility",UUID.class).invoke(mapApi,id));webApiType.getMethod("setPlayerVisibility",UUID.class,boolean.class).invoke(mapApi,id,false);}
                else restoreMap(id);
            }
        }catch(Exception e){mapStatus="BlueMap API error: "+e.getClass().getSimpleName();if(System.currentTimeMillis()-lastError>60000){lastError=System.currentTimeMillis();plugin.getLogger().warning(mapStatus+" • "+e.getMessage());}}
    }
    private void restoreMap(UUID id){Boolean old=mapOriginal.remove(id);if(old!=null&&mapApi!=null&&webApiType!=null)try{webApiType.getMethod("setPlayerVisibility",UUID.class,boolean.class).invoke(mapApi,id,old);}catch(Exception e){plugin.getLogger().warning("BlueMap visibility restore failed for "+id);}}
    private void restoreMap(){for(UUID id:new ArrayList<>(mapOriginal.keySet()))restoreMap(id);}
    private boolean access(CommandSender s,String node){if(!plugin.has(s,node)){s.sendMessage(plugin.prefix()+text(s,"Keine Berechtigung: ","Missing permission: ")+node);return false;}return !(s instanceof Player p)||plugin.adminSessionUsable(p);}
    String text(CommandSender p,String de,String en){return p instanceof Player player?plugin.playerText(player,de,en):de;}
    boolean command(CommandSender s,String feature,String[] args){
        if(feature.equals("checkhacks")||feature.equals("clientcheck")||feature.equals("chalerts")||feature.equals("chreload")||feature.equals("checklang"))return probes.command(s,feature,args);
        if(feature.equals("names")){
            if(!access(s,"simplexadminsuite.nickname"))return true;
            if(args.length==0&&s instanceof Player p){menu(p);return true;}
            Player target=args.length==0?null:Bukkit.getPlayerExact(args[0]);
            s.sendMessage(plugin.prefix()+(target==null?text(s,"Nutze /sas names <Spieler> oder das Menü.","Use /sas names <player> or the menu."):names.diagnostic(target)));return true;
        }
        if(!access(s,"simplexadminsuite.bluemap"))return true;
        if(args.length==0){if(s instanceof Player p)mapMenu(p);else s.sendMessage(mapStatus+" • /sas bluemap <player> <hidden|vanish>");return true;}
        UUID id=plugin.discordAdminResolvePlayer(args[0]);
        if(id==null){s.sendMessage(text(s,"Spielerprofil nicht gefunden.","Player profile not found."));return true;}
        if(args.length<2){s.sendMessage(modes.getProperty(id.toString(),"vanish")+" • "+mapStatus);return true;}
        String mode=args[1].toLowerCase(Locale.ROOT);if(!Set.of("hidden","vanish").contains(mode)){s.sendMessage("/sas bluemap <player> <hidden|vanish>");return true;}
        setMapMode(s,id,mode);return true;
    }
    private void setMapMode(CommandSender s,UUID id,String mode){
        if(!access(s,"simplexadminsuite.bluemap"))return;
        Properties next=new Properties();next.putAll(modes);next.setProperty(id.toString(),mode);
        try{AtomicState.save(modeFile,next,"BlueMap: hidden permanently / vanish while publicly hidden");modes.clear();modes.putAll(next);plugin.expansionAudit(s.getName(),id,"BlueMap visibility",mode);syncMap();s.sendMessage(text(s,"BlueMap-Modus gespeichert: ","BlueMap mode saved: ")+mode);}
        catch(IOException e){s.sendMessage(text(s,"Speichern fehlgeschlagen.","Saving failed."));}
    }
    void menu(Player p){
        if(!access(p,"simplexadminsuite.nickname"))return;List<AdminUpdateMenu.Button> b=new ArrayList<>();
        b.add(AdminUpdateMenu.info(text(p,"Namensverwaltung","Name management"),text(p,"Nur sichtbare Namen; UUIDs und echte Kontonamen bleiben erhalten. Nicknames ändern die Spielerliste. Der Chat wird nicht verändert. Über Köpfen bleiben technisch echte Kontonamen.","Visual names only; UUIDs and account names stay unchanged. Nicknames change player-list names. Chat is left unchanged. Overhead tags retain the account name."),Material.NAME_TAG));
        for(Player target:Bukkit.getOnlinePlayers())b.add(new AdminUpdateMenu.Button(target.getName(),names.diagnostic(target),Material.PLAYER_HEAD,()->nameMenu(p,target)));
        b.add(new AdminUpdateMenu.Button(text(p,"Zurück","Back"),"",Material.OAK_DOOR,()->plugin.unifiedMenu.open(p)));plugin.adminUpdate.show(p,text(p,"Spielernamen","Player names"),b,0);
    }
    private void nameMenu(Player p,Player target){if(!access(p,"simplexadminsuite.nickname"))return;List<AdminUpdateMenu.Button> b=new ArrayList<>();
        b.add(new AdminUpdateMenu.Button(text(p,"Vollständige Diagnose","Full diagnostics"),target.getName(),Material.NAME_TAG,()->{if(access(p,"simplexadminsuite.nickname"))plugin.adminUpdate.detail(p,text(p,"Namensdiagnose","Name diagnostics"),names.diagnostic(target),()->nameMenu(p,target));}));
        b.add(new AdminUpdateMenu.Button(text(p,"Nickname setzen","Set nickname"),text(p,"Spielerliste ändern; Chat bleibt unverändert","Change player-list name; chat stays unchanged"),Material.WRITABLE_BOOK,()->plugin.adminUpdate.prompt(p,text(p,"Nickname","Nickname"),plugin.visualName(target),value->{if(access(p,"simplexadminsuite.nickname"))plugin.setVisualNickname(p,target.getUniqueId(),value);menu(p);} )));
        b.add(new AdminUpdateMenu.Button(text(p,"Nickname zurücksetzen","Reset nickname"),"",Material.BARRIER,()->{if(access(p,"simplexadminsuite.nickname"))plugin.setVisualNickname(p,target.getUniqueId(),"reset");menu(p);}));
        b.add(new AdminUpdateMenu.Button(text(p,"Anzeige aktualisieren","Refresh names"),"",Material.CLOCK,()->{if(access(p,"simplexadminsuite.nickname"))names.apply(target);nameMenu(p,target);}));
        b.add(new AdminUpdateMenu.Button(text(p,"Zurück","Back"),"",Material.OAK_DOOR,()->menu(p)));plugin.adminUpdate.show(p,target.getName(),b,0);
    }
    void mapMenu(Player p){if(!access(p,"simplexadminsuite.bluemap"))return;List<AdminUpdateMenu.Button> b=new ArrayList<>();b.add(AdminUpdateMenu.info("BlueMap",mapStatus+"\n"+text(p,"Verbirgt öffentliche Marker auch bei externem Vanish. Andere BlueMap-Filter bleiben wirksam.","Hides public markers, including external vanish. Other BlueMap filters remain effective."),Material.MAP));
        b.add(new AdminUpdateMenu.Button(text(p,"Integration umschalten","Toggle integration"),Boolean.toString(plugin.getConfig().getBoolean("integrations.bluemap.enabled",true)),Material.COMPARATOR,()->{if(access(p,"simplexadminsuite.bluemap")){plugin.getConfig().set("integrations.bluemap.enabled",!plugin.getConfig().getBoolean("integrations.bluemap.enabled",true));plugin.saveConfig();syncMap();mapMenu(p);}}));
        for(SimplexAdminSuite.PlayerProfile profile:plugin.expansionProfiles())b.add(new AdminUpdateMenu.Button(profile.displayName(),modes.getProperty(profile.uniqueId().toString(),"vanish"),Material.PLAYER_HEAD,()->{setMapMode(p,profile.uniqueId(),"hidden".equals(modes.getProperty(profile.uniqueId().toString(),"vanish"))?"vanish":"hidden");mapMenu(p);}));
        b.add(new AdminUpdateMenu.Button(text(p,"Zurück","Back"),"",Material.OAK_DOOR,()->plugin.unifiedMenu.open(p)));plugin.adminUpdate.show(p,"BlueMap",b,0);
    }
    @EventHandler(priority=EventPriority.MONITOR) public void join(PlayerJoinEvent e){packets.join(e.getPlayer());Bukkit.getScheduler().runTaskLater(plugin,()->{if(e.getPlayer().isOnline()){tick();probes.joined(e.getPlayer());}},1L);scheduleAuto(e.getPlayer(),0);}
    private void scheduleAuto(Player p,int tries){Bukkit.getScheduler().runTaskLater(plugin,()->{if(!p.isOnline()||plugin.isBedrockClient(p)||!plugin.getConfig().getBoolean("client-security.probes.auto-on-join",true))return;if(plugin.adminRuntime.blocked(p)||!packets.available(p.getUniqueId())){if(tries<200)scheduleAuto(p,tries+1);}else probes.auto(p);},tries==0?2L:10L);}
    @EventHandler(priority=EventPriority.MONITOR) public void quit(PlayerQuitEvent e){names.clear(e.getPlayer());probes.quit(e.getPlayer());packets.quit(e.getPlayer());clearMetadata(e.getPlayer());hiddenNames.remove(e.getPlayer().getUniqueId());restoreMap(e.getPlayer().getUniqueId());}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void target(EntityTargetLivingEntityEvent e){if(e.getTarget() instanceof Player p&&(plugin.adminRuntime.vanished(p)||plugin.completeStealth(p)))e.setCancelled(true);}
    List<String> complete(CommandSender sender,String feature,String[] args){
        String permission=feature.equals("bluemap")?"simplexadminsuite.bluemap":feature.equals("names")?"simplexadminsuite.nickname":"simplexadminsuite.clientcheck";
        if(!plugin.has(sender,permission))return List.of();
        List<String> choices=args.length==1?plugin.expansionProfiles().stream().map(SimplexAdminSuite.PlayerProfile::displayName).toList():args.length==2?feature.equals("bluemap")?List.of("hidden","vanish"):feature.equals("names")||feature.equals("checklang")?List.of():List.of("all","selected","status","stop","meteor-client","freecam","xray-fabric"):List.of();
        if(feature.equals("clientcheck")&&plugin.has(sender,"simplexadminsuite.clientsecurity.admin")){
            if(args.length==1){List<String> all=new ArrayList<>(choices);all.addAll(List.of("settings","channel","rule","approve","queue","diagnose","testkey"));choices=all;}
            else if(args.length==2&&args[0].equalsIgnoreCase("channel"))choices=List.of("create","off","status");
            else if(args.length==2&&args[0].equalsIgnoreCase("rule"))choices=probes.definitionIds();
            else if(args.length==2&&Set.of("approve","diagnose","testkey").contains(args[0].toLowerCase(Locale.ROOT)))choices=plugin.expansionProfiles().stream().map(SimplexAdminSuite.PlayerProfile::displayName).toList();
            else if(args.length==3&&args[0].equalsIgnoreCase("diagnose"))choices=List.of("console","menu","clear");
            else if(args.length==3&&args[0].equalsIgnoreCase("rule"))choices=List.of("none","notify","kick","tempban");
            else if(args.length==4&&args[0].equalsIgnoreCase("rule"))choices=List.of("15m","1h","1d");
            else if(args.length==5&&args[0].equalsIgnoreCase("rule"))choices=List.of("0","3","5");
        }
        String prefix=args.length==0?"":args[args.length-1].toLowerCase(Locale.ROOT);return choices.stream().filter(n->n.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted(String.CASE_INSENSITIVE_ORDER).limit(80).toList();
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void tab(TabCompleteEvent e){if(e.getSender() instanceof Player p){Set<String> hidden=hiddenNames.getOrDefault(p.getUniqueId(),Set.of());e.getCompletions().removeIf(n->hidden.contains(n.toLowerCase(Locale.ROOT)));}}
    @EventHandler(priority=EventPriority.HIGHEST) public void asyncTab(com.destroystokyo.paper.event.server.AsyncTabCompleteEvent e){if(e.getSender() instanceof Player p){Set<String> hidden=hiddenNames.getOrDefault(p.getUniqueId(),Set.of());e.completions().removeIf(n->hidden.contains(n.suggestion().toLowerCase(Locale.ROOT)));}}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void publicCommand(PlayerCommandPreprocessEvent e){
        Player p=e.getPlayer();if(plugin.has(p,"simplexadminsuite.admin"))return;String[] parts=e.getMessage().substring(1).split("\\s+");if(parts.length<2)return;
        String root=parts[0].toLowerCase(Locale.ROOT);if(root.contains(":"))root=root.substring(root.indexOf(':')+1);
        if(!plugin.getConfig().getStringList("admin-update.vanish.hidden-target-commands").contains(root))return;
        Set<String> hidden=hiddenNames.getOrDefault(p.getUniqueId(),Set.of());
        for(int i=1;i<parts.length;i++)if(hidden.contains(parts[i].toLowerCase(Locale.ROOT))){e.setCancelled(true);p.sendMessage(plugin.playerText(p,"Spieler nicht gefunden.","Player not found."));return;}
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void ping(ServerListPingEvent e){Iterator<Player> it=e.iterator();while(it.hasNext())if(plugin.discordAdminHidden(it.next()))it.remove();}
}
