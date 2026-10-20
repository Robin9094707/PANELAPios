package eu.simplexsmp.adminsuite;

import java.util.*;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import io.papermc.paper.datacomponent.DataComponentTypes;

/** Optional observational checks. No packet injection, world changes, kicks or bans. */
final class SecurityDetectors implements Listener {
    static final String ROOT="security-detectors.";
    enum Detector {
        FINGERPRINT("channel-fingerprint","Channel-Fingerprint 2.0",Material.NAME_TAG),
        CONCEALMENT("anti-detection","Anti-Detection-Fingerprint",Material.SHIELD),
        AUTOCLICKER("autoclicker","AutoClicker-Muster",Material.IRON_SWORD),
        FREECAM("freecam","Freecam-Verhalten",Material.SPYGLASS);
        final String key,title;final Material icon;
        Detector(String key,String title,Material icon){this.key=key;this.title=title;this.icon=icon;}
    }
    private static final class State {
        final Player player;final long joined=System.currentTimeMillis();
        String brand="",version="";Set<String> channels=Set.of();final Set<String> loaderHistory=new HashSet<>();
        final Deque<Long> changes=new ArrayDeque<>(),removals=new ArrayDeque<>(),interactions=new ArrayDeque<>();
        final Map<Detector,Long> alerts=new EnumMap<>(Detector.class);
        final Set<String> seen=new HashSet<>();
        final List<Double> clicks=new ArrayList<>();
        long lastSwing,miningUntil,combatUntil,graceUntil;int clickWindows;
        State(Player player){this.player=player;}
        void resetClicks(){clicks.clear();lastSwing=0;clickWindows=0;}
    }
    private final SimplexAdminSuite plugin;
    private final Map<UUID,State> states=new HashMap<>();
    private SecurityEvidenceStore store;
    private BukkitTask timer;
    private long previousTick,lagUntil,lastWriteError;
    private double tickMillis=50,tps=20;
    private int ticks;
    SecurityDetectors(SimplexAdminSuite plugin){this.plugin=plugin;try{
        store=new SecurityEvidenceStore(plugin.getDataFolder().toPath().resolve("security-observations.db"),plugin.getConfig().getInt(ROOT+"history-per-player",100),message->{long now=System.currentTimeMillis();if(now-lastWriteError>60000){lastWriteError=now;plugin.getLogger().warning(message);}});
    }catch(Exception e){plugin.getLogger().warning("Optional security detectors unavailable: "+e.getClass().getSimpleName()+". Existing data is untouched.");}}
    void start(){if(timer!=null)return;for(Player p:Bukkit.getOnlinePlayers())join(p);previousTick=System.nanoTime();timer=Bukkit.getScheduler().runTaskTimer(plugin,this::tick,1L,1L);}
    void reload(){states.clear();for(Player p:Bukkit.getOnlinePlayers())join(p);lagUntil=System.currentTimeMillis()+5000;}
    void stop(){if(timer!=null)timer.cancel();timer=null;states.clear();if(store!=null){store.close();store=null;}}
    private void tick(){long nano=System.nanoTime(),now=System.currentTimeMillis();tickMillis=(nano-previousTick)/1_000_000d;previousTick=nano;
        if(tickMillis>85||tickMillis<20){lagUntil=now+5000;for(State s:states.values()){s.resetClicks();s.interactions.clear();}}
        if(++ticks%20==0){double[] values=Bukkit.getTPS();if(values.length>0)tps=values[0];}
        if(ticks%100==0&&(enabled(Detector.FINGERPRINT)||enabled(Detector.CONCEALMENT)))for(State s:List.copyOf(states.values()))network(s);
    }
    boolean enabled(Detector d){return store!=null&&plugin.getConfig().getBoolean(ROOT+d.key+".enabled",false);}
    private boolean allowed(Player p){return p!=null&&p.isOnline()&&!plugin.isBedrockClient(p)&&!p.hasPermission("simplexadminsuite.detectors.bypass")&&!(plugin.adminRuntime!=null&&plugin.adminRuntime.passive(p));}
    private State state(Player p){if(!allowed(p))return null;State s=states.get(p.getUniqueId());return s!=null&&s.player==p?s:null;}
    private boolean healthy(State s){long now=System.currentTimeMillis();return now>=lagUntil&&now>=s.graceUntil&&now-s.joined>=Math.max(10,plugin.getConfig().getInt(ROOT+"join-grace-seconds",15))*1000L&&SecuritySignals.healthy(tps,s.player.getPing(),tickMillis);}
    @EventHandler(priority=EventPriority.MONITOR)public void join(PlayerJoinEvent e){join(e.getPlayer());}
    private void join(Player p){if(!plugin.isBedrockClient(p))states.put(p.getUniqueId(),new State(p));}
    @EventHandler(priority=EventPriority.MONITOR)public void quit(PlayerQuitEvent e){states.remove(e.getPlayer().getUniqueId());}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)public void teleport(PlayerTeleportEvent e){State s=states.get(e.getPlayer().getUniqueId());if(s!=null){s.graceUntil=System.currentTimeMillis()+5000;s.resetClicks();s.interactions.clear();}}
    @EventHandler(priority=EventPriority.MONITOR)public void respawn(PlayerRespawnEvent e){State s=states.get(e.getPlayer().getUniqueId());if(s!=null){s.graceUntil=System.currentTimeMillis()+5000;s.resetClicks();s.interactions.clear();}}
    @EventHandler(priority=EventPriority.MONITOR)public void register(PlayerRegisterChannelEvent e){channelChange(e.getPlayer(),false,e.getChannel());}
    @EventHandler(priority=EventPriority.MONITOR)public void unregister(PlayerUnregisterChannelEvent e){channelChange(e.getPlayer(),true,e.getChannel());}
    private void channelChange(Player p,boolean removal,String channel){State s=state(p);if(s==null||!enabled(Detector.CONCEALMENT))return;long now=System.currentTimeMillis();prune(s.changes,now,10000);prune(s.removals,now,10000);if(!removal&&s.loaderHistory.size()<64&&!SecuritySignals.loaders(List.of(channel)).isEmpty())s.loaderHistory.add(SecuritySignals.safe(channel));if(s.changes.size()<256)s.changes.addLast(now);if(removal&&s.removals.size()<256)s.removals.addLast(now);}
    private static void prune(Deque<Long> entries,long now,long window){while(!entries.isEmpty()&&now-entries.getFirst()>window)entries.removeFirst();}
    private void network(State s){if(!(enabled(Detector.FINGERPRINT)||enabled(Detector.CONCEALMENT))||state(s.player)!=s||!healthy(s))return;long now=System.currentTimeMillis();
        Set<String> channels=new TreeSet<>();for(String raw:s.player.getListeningPluginChannels())if(channels.size()<512)channels.add(SecuritySignals.safe(raw));
        String brand=SecuritySignals.safe(ClientProbeDiagnostics.brand(s.player)),version=version(s.player);
        prune(s.changes,now,10000);prune(s.removals,now,10000);
        if(enabled(Detector.FINGERPRINT))for(var finding:SecuritySignals.fingerprint(brand,channels))networkFinding(s,Detector.FINGERPRINT,finding);
        if(enabled(Detector.CONCEALMENT)){for(String channel:channels)if(s.loaderHistory.size()<64&&!SecuritySignals.loaders(List.of(channel)).isEmpty())s.loaderHistory.add(channel);for(var finding:SecuritySignals.concealment(brand,s.brand,channels,s.loaderHistory,s.removals.size(),s.changes.size(),plugin.getConfig().getStringList(ROOT+"anti-detection.privacy-namespaces")))networkFinding(s,Detector.CONCEALMENT,finding);}
        if(enabled(Detector.FINGERPRINT)&&s.version.startsWith("protocol=")&&version.startsWith("protocol=")&&!s.version.equals(version))networkFinding(s,Detector.FINGERPRINT,new SecuritySignals.Finding("LIVE_PROTOCOL_CHANGE",s.version+" -> "+version+" in one connection; protocol/proxy diagnostics can change. This is not proof of cheating."));
        s.brand=brand;s.version=version;s.channels=Set.copyOf(channels);
        store.snapshot(s.player.getUniqueId(),new SecurityEvidenceStore.Snapshot(now,brand,version,List.copyOf(channels)));
    }
    private String version(Player p){
        // Optional Paper/ViaVersion diagnostics; unknown versions are never suspicious.
        try{Object value=p.getClass().getMethod("getProtocolVersion").invoke(p);if(value instanceof Number number&&number.intValue()>0)return "protocol="+number.intValue();}catch(ReflectiveOperationException ignored){}
        if(Bukkit.getPluginManager().isPluginEnabled("ViaVersion"))try{Class<?> via=Class.forName("com.viaversion.viaversion.api.Via");Object api=via.getMethod("getAPI").invoke(null);Object value=api.getClass().getMethod("getPlayerVersion",UUID.class).invoke(api,p.getUniqueId());if(value instanceof Number number&&number.intValue()>0)return "protocol="+number.intValue();}catch(ReflectiveOperationException|LinkageError ignored){}
        return "client protocol unavailable; server="+Bukkit.getMinecraftVersion();
    }
    private void networkFinding(State s,Detector d,SecuritySignals.Finding value){String identity=d.key+":"+value.code()+":"+value.detail();if(s.seen.size()<256&&!s.seen.contains(identity)&&record(s,d,value,true))s.seen.add(identity);}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)public void mining(BlockDamageEvent e){State s=state(e.getPlayer());if(s!=null){s.miningUntil=System.currentTimeMillis()+4000;s.resetClicks();}}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)public void attack(EntityDamageByEntityEvent e){if(!(e.getDamager() instanceof Player p))return;State s=state(p);if(s==null)return;s.combatUntil=System.currentTimeMillis()+5000;if(enabled(Detector.FREECAM))entityInteraction(s,e.getEntity(),true);}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)public void swing(PlayerAnimationEvent e){if(e.getAnimationType()!=PlayerAnimationType.ARM_SWING||!enabled(Detector.AUTOCLICKER))return;State s=state(e.getPlayer());if(s==null)return;
        long now=System.currentTimeMillis(),nano=System.nanoTime();
        if(!healthy(s)||now<s.miningUntil||s.player.getGameMode()==GameMode.CREATIVE||s.player.getGameMode()==GameMode.SPECTATOR||(plugin.getConfig().getBoolean(ROOT+"autoclicker.combat-only",true)&&now>s.combatUntil)){s.resetClicks();return;}
        if(s.lastSwing!=0){double delta=(nano-s.lastSwing)/1_000_000d;if(delta<5||delta>500){s.resetClicks();s.lastSwing=nano;return;}s.clicks.add(delta);}s.lastSwing=nano;
        int count=Math.max(64,Math.min(256,plugin.getConfig().getInt(ROOT+"autoclicker.sample-size",96)));if(s.clicks.size()<count)return;
        var pattern=SecuritySignals.clicks(s.clicks,count,Math.max(6,plugin.getConfig().getDouble(ROOT+"autoclicker.minimum-cps",8)),Math.max(.005,Math.min(.10,plugin.getConfig().getDouble(ROOT+"autoclicker.maximum-variation",.04))));
        s.clickWindows=pattern.suspicious()?s.clickWindows+1:0;s.clicks.clear();s.lastSwing=0;
        if(s.clickWindows>=Math.max(3,plugin.getConfig().getInt(ROOT+"autoclicker.confirmation-windows",3))){
            record(s,Detector.AUTOCLICKER,new SecuritySignals.Finding("REPEATED_CLICK_PATTERN",String.format(Locale.ROOT,"%d disjoint server swing windows; %d intervals/window; CPS=%.2f; CV=%.4f; repeating period=%d. Tick quantization/manual clicking can resemble this pattern.",s.clickWindows,pattern.samples(),pattern.cps(),pattern.variation(),pattern.period())),true);s.clickWindows=0;
        }
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)public void blockInteraction(PlayerInteractEvent e){if(!enabled(Detector.FREECAM)||e.getHand()!=org.bukkit.inventory.EquipmentSlot.HAND||e.getClickedBlock()==null||e.getAction()!=org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK)return;State s=state(e.getPlayer());if(s!=null)blockInteraction(s,e.getClickedBlock());}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)public void breakBlock(BlockBreakEvent e){State s=state(e.getPlayer());if(s!=null){s.miningUntil=System.currentTimeMillis()+4000;s.resetClicks();if(enabled(Detector.FREECAM))blockInteraction(s,e.getBlock());}}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)public void interactEntity(PlayerInteractEntityEvent e){if(!enabled(Detector.FREECAM)||e.getHand()!=org.bukkit.inventory.EquipmentSlot.HAND)return;State s=state(e.getPlayer());if(s!=null)entityInteraction(s,e.getRightClicked(),false);}
    private void blockInteraction(State s,org.bukkit.block.Block target){if(!eligibleInteraction(s)||!target.getWorld().equals(s.player.getWorld()))return;
        Location eye=s.player.getEyeLocation();double distance=SecuritySignals.distanceToBox(eye.getX(),eye.getY(),eye.getZ(),target.getX(),target.getY(),target.getZ(),target.getX()+1,target.getY()+1,target.getZ()+1);
        double reach=reach(s.player,Attribute.BLOCK_INTERACTION_RANGE,4.5),tolerance=tolerance(s.player);
        boolean outside=SecuritySignals.outsideReach(distance,reach,tolerance),blocked=false;
        if(!outside&&plugin.getConfig().getBoolean(ROOT+"freecam.line-of-sight",true)&&distance<16)blocked=occluded(eye,target);
        interaction(s,outside||blocked,"BLOCK "+target.getType()+" distance="+round(distance)+" range="+round(reach)+" tolerance="+round(tolerance)+" occluded="+blocked);
    }
    private void entityInteraction(State s,Entity target,boolean attack){if(!eligibleInteraction(s)||target.isDead()||!target.getWorld().equals(s.player.getWorld())||target instanceof Player p&&plugin.discordAdminHidden(p))return;
        Location eye=s.player.getEyeLocation();BoundingBox box=target.getBoundingBox();double distance=SecuritySignals.distanceToBox(eye.getX(),eye.getY(),eye.getZ(),box.getMinX(),box.getMinY(),box.getMinZ(),box.getMaxX(),box.getMaxY(),box.getMaxZ());
        double range=reach(s.player,Attribute.ENTITY_INTERACTION_RANGE,3),tolerance=tolerance(s.player);
        if(attack){var item=s.player.getInventory().getItemInMainHand();var custom=item.getData(DataComponentTypes.ATTACK_RANGE);if(custom!=null){
            // 26.x weapons can add motion-dependent reach. Leave these attacks to Grim/other predictive checks.
            return;
        }}
        interaction(s,SecuritySignals.outsideReach(distance,range,tolerance),"ENTITY "+target.getType()+" distance="+round(distance)+" range="+round(range)+" tolerance="+round(tolerance));
    }
    private boolean eligibleInteraction(State s){return healthy(s)&&s.player.getGameMode()!=GameMode.CREATIVE&&s.player.getGameMode()!=GameMode.SPECTATOR&&!s.player.isInsideVehicle()&&!s.player.isGliding();}
    private double tolerance(Player p){return Math.max(.25,Math.min(2,plugin.getConfig().getDouble(ROOT+"freecam.range-tolerance",.75)))+Math.min(.75,p.getPing()/400d);}
    private static double reach(Player p,Attribute attribute,double fallback){var instance=p.getAttribute(attribute);return instance==null?fallback:instance.getValue();}
    private static String round(double value){return String.format(Locale.ROOT,"%.2f",value);}
    private static boolean occluded(Location eye,org.bukkit.block.Block target){
        // A visible edge is enough. A center ray alone falsely marks partially visible blocks.
        List<Vector> points=new ArrayList<>();points.add(new Vector(.5,.5,.5));
        for(double x:new double[]{.05,.95})for(double y:new double[]{.05,.95})for(double z:new double[]{.05,.95})points.add(new Vector(x,y,z));
        for(Vector point:points){Location endpoint=target.getLocation().add(point);
            if(!loadedPath(eye,endpoint))return false; // Never load chunks to obtain evidence.
            Vector delta=endpoint.toVector().subtract(eye.toVector());double length=delta.length();if(length<.01)return false;
            RayTraceResult hit=target.getWorld().rayTraceBlocks(eye,delta.normalize(),length,FluidCollisionMode.NEVER,true);
            if(hit==null||hit.getHitBlock()==null||hit.getHitBlock().equals(target))return false;
        }
        return true;
    }
    private static boolean loadedPath(Location from,Location to){Vector step=to.toVector().subtract(from.toVector());int count=Math.max(1,(int)Math.ceil(step.length()));step.multiply(1d/count);Location cursor=from.clone();for(int n=0;n<=count;n++,cursor.add(step))if(!from.getWorld().isChunkLoaded(cursor.getBlockX()>>4,cursor.getBlockZ()>>4))return false;return true;}
    private void interaction(State s,boolean suspicious,String detail){long now=System.currentTimeMillis();prune(s.interactions,now,10000);if(!suspicious){s.interactions.clear();return;}
        // One interaction can produce several Bukkit events; count at most once per 250 ms.
        if(s.interactions.isEmpty()||now-s.interactions.getLast()>=250)s.interactions.addLast(now);
        if(s.interactions.size()>=Math.max(4,Math.min(20,plugin.getConfig().getInt(ROOT+"freecam.minimum-interactions",4)))){
            record(s,Detector.FREECAM,new SecuritySignals.Finding("REPEATED_UNUSUAL_INTERACTIONS",s.interactions.size()+" distinct interactions/10 seconds; "+detail+". Freecam itself and the client's camera are not observable."),true);s.interactions.clear();
        }
    }
    private boolean record(State s,Detector d,SecuritySignals.Finding finding,boolean notify){if(!enabled(d))return false;long now=System.currentTimeMillis(),last=s.alerts.getOrDefault(d,0L);long cooldown=Math.max(10,plugin.getConfig().getInt(ROOT+"alert-cooldown-seconds",120))*1000L;
        if(now-last<cooldown)return false;s.alerts.put(d,now);
        store.append(s.player.getUniqueId(),new SecurityEvidenceStore.Entry(now,d.key,finding.code(),finding.detail()+" | ping="+s.player.getPing()+"ms; TPS="+round(tps)));
        if(plugin.getConfig().getBoolean(ROOT+"console-summary",false))plugin.getLogger().info("[Security hint] "+s.player.getName()+" • "+d.title+" • "+finding.code());
        if(notify&&plugin.getConfig().getBoolean(ROOT+"staff-alerts",true))for(Player staff:Bukkit.getOnlinePlayers())if(plugin.has(staff,"simplexadminsuite.clientsecurity.admin")&&plugin.adminSessionUsable(staff))staff.sendMessage(plugin.playerText(staff,"§e[Client-Hinweis] §f","§e[Client hint] §f")+s.player.getName()+" • "+d.title+" • "+finding.code()+plugin.playerText(staff," §7(ungeprüfter Verdacht; /sas detectors)"," §7(unverified hint; /sas detectors)"));
        if(plugin.securityHub!=null)plugin.securityHub.hint(s.player,d.key,d.title,finding.code()+": "+finding.detail(),d==Detector.AUTOCLICKER||d==Detector.FREECAM);
        return true;
    }
    private boolean admin(Player p){return plugin.has(p,"simplexadminsuite.clientsecurity.admin")&&plugin.adminSessionUsable(p);}
    private String t(Player p,String de,String en){return de;}
    private AdminUpdateMenu.Button button(Player p,String title,String detail,Material icon,Runnable action){return new AdminUpdateMenu.Button(title,detail,icon,()->{if(admin(p))action.run();});}
    void home(Player p){if(!admin(p))return;List<AdminUpdateMenu.Button>b=new ArrayList<>();
        b.add(AdminUpdateMenu.info(t(p,"Optionale Detektoren","Optional detectors"),t(p,"Alle neuen Checks sind zunächst aus. Hinweise sind kein Cheat-Beweis und führen standardmäßig zu keiner Sanktion. Java-Ziele; Java- und Bedrock-Administration.","New checks start disabled. Hints are not proof of cheating and do not sanction by default. Java targets; Java and Bedrock administration."),Material.SHIELD));
        if(store==null)b.add(AdminUpdateMenu.info(t(p,"Speicher nicht verfügbar","Storage unavailable"),t(p,"Detektoren sind deaktiviert; Serverprotokoll prüfen.","Detectors are disabled; check the server log."),Material.BARRIER));
        for(Detector d:Detector.values())b.add(button(p,d.title,(enabled(d)?"✓ ":"○ ")+t(p,enabled(d)?"An • Klick: ausschalten":"Aus • Klick: einschalten",enabled(d)?"On • Click: disable":"Off • Click: enable"),enabled(d)?Material.LIME_DYE:Material.GRAY_DYE,()->{plugin.getConfig().set(ROOT+d.key+".enabled",!enabled(d));changed(p);home(p);}));
        for(Detector d:Detector.values()){boolean kick=plugin.getConfig().getBoolean(ROOT+d.key+".kick",false);b.add(button(p,d.title+" • Kick",kick?"An • ausschalten":"Nur protokollieren • einschalten",kick?Material.RED_DYE:Material.GRAY_DYE,()->{plugin.getConfig().set(ROOT+d.key+".kick",!kick);changed(p);home(p);}));}
        if(plugin.securityHub!=null)b.add(button(p,"Protokolle & Ausnahmen","15-Minuten-Sammlung, Whitelists und private Daten",Material.WRITABLE_BOOK,()->plugin.securityHub.menu(p)));
        b.add(button(p,t(p,"Alle Detektoren ausschalten","Disable all detectors"),"",Material.GRAY_DYE,()->{for(Detector d:Detector.values())plugin.getConfig().set(ROOT+d.key+".enabled",false);changed(p);home(p);}));
        b.add(button(p,t(p,"Empfindlichkeit & Meldungen","Sensitivity & alerts"),t(p,"Grenzen, Bestätigungen und Protokollierung","Thresholds, confirmation and logging"),Material.COMPARATOR,()->settings(p)));
        b.add(button(p,t(p,"Spieler & Verlauf","Players & history"),t(p,"Letzter Fingerprint und gespeicherte Hinweise","Last fingerprint and saved hints"),Material.WRITABLE_BOOK,()->players(p)));
        if(plugin.has(p,"simplexadminsuite.clientcheck"))b.add(button(p,t(p,"Mod-Prüfungen & Join-Regeln","Mod probes & join rules"),t(p,"Schildprüfungen, einzelne Mods und Aktionen","Sign probes, individual mods and actions"),Material.SPYGLASS,()->plugin.qualityOfLife.probes.settings(p)));
        b.add(button(p,t(p,"Zurück","Back"),"",Material.OAK_DOOR,()->plugin.unifiedMenu.category(p,"Sicherheit")));plugin.adminUpdate.show(p,t(p,"Sicherheit • Detektoren","Security • Detectors"),b,0);
    }
    private void changed(Player p){plugin.saveConfig();for(State s:states.values()){s.resetClicks();s.interactions.clear();s.changes.clear();s.removals.clear();s.loaderHistory.clear();s.seen.clear();s.graceUntil=System.currentTimeMillis()+5000;}plugin.expansionAudit(p.getName(),null,"Optional detector settings","changed");}
    private void toggle(Player p,List<AdminUpdateMenu.Button>b,String key,String de,String en){boolean value=plugin.getConfig().getBoolean(ROOT+key);b.add(button(p,t(p,de,en),value?"✓":"○",value?Material.LIME_DYE:Material.GRAY_DYE,()->{plugin.getConfig().set(ROOT+key,!value);changed(p);settings(p);}));}
    private void choices(Player p,List<AdminUpdateMenu.Button>b,String key,String de,String en,List<? extends Number> choices){b.add(button(p,t(p,de,en),plugin.getConfig().getString(ROOT+key),Material.CLOCK,()->{List<AdminUpdateMenu.Button>options=new ArrayList<>();for(Number value:choices)options.add(button(p,value.toString(),"",Material.CLOCK,()->{plugin.getConfig().set(ROOT+key,value);changed(p);settings(p);}));options.add(button(p,t(p,"Zurück","Back"),"",Material.OAK_DOOR,()->settings(p)));plugin.adminUpdate.show(p,t(p,de,en),options,0);}));}
    private void settings(Player p){List<AdminUpdateMenu.Button>b=new ArrayList<>();toggle(p,b,"staff-alerts","Hinweise im Admin-Chat","Staff chat hints");toggle(p,b,"console-summary","Kurze Konsolenhinweise","Short console hints");toggle(p,b,"autoclicker.combat-only","Klickmuster nur im Kampf","Click patterns during combat only");toggle(p,b,"freecam.line-of-sight","Sichtlinie bei Blockinteraktionen","Block interaction line of sight");
        choices(p,b,"autoclicker.sample-size","Intervalle je Messfenster","Intervals per sample window",List.of(64,96,128,192,256));choices(p,b,"autoclicker.minimum-cps","Minimale Klickrate","Minimum click rate",List.of(8,10,12,15));choices(p,b,"autoclicker.maximum-variation","Maximale Intervallstreuung","Maximum interval variation",List.of(.01,.02,.04,.06,.08));choices(p,b,"autoclicker.confirmation-windows","Bestätigungsfenster","Confirmation windows",List.of(3,4,5));choices(p,b,"freecam.minimum-interactions","Auffällige Interaktionen bis Hinweis","Unusual interactions before a hint",List.of(4,6,8,12));choices(p,b,"freecam.range-tolerance","Zusätzliche Reichweitentoleranz","Extra reach tolerance",List.of(.5,.75,1d,1.5,2d));choices(p,b,"alert-cooldown-seconds","Hinweisabstand (Sekunden)","Hint cooldown (seconds)",List.of(30,60,120,300));choices(p,b,"join-grace-seconds","Startabstand nach Beitritt (Sekunden)","Join grace (seconds)",List.of(10,15,30,60));
        b.add(button(p,t(p,"Privacy-Namensräume bearbeiten","Edit privacy namespaces"),String.join(", ",plugin.getConfig().getStringList(ROOT+"anti-detection.privacy-namespaces")),Material.WRITABLE_BOOK,()->plugin.adminUpdate.prompt(p,t(p,"Namensräume mit Komma trennen","Comma-separated namespaces"),String.join(", ",plugin.getConfig().getStringList(ROOT+"anti-detection.privacy-namespaces")),value->{if(!admin(p))return;List<String> names=Arrays.stream(value.split(",")).map(String::strip).filter(v->!v.isEmpty()).map(v->v.toLowerCase(Locale.ROOT)).distinct().toList();if(names.size()>64||names.stream().anyMatch(v->!v.matches("[a-z0-9_.-]{1,64}"))){plugin.menuError(p,t(p,"§cUngültige Namensräume.","§cInvalid namespaces."));settings(p);return;}plugin.getConfig().set(ROOT+"anti-detection.privacy-namespaces",names);changed(p);settings(p);})));
        b.add(button(p,t(p,"Zurück","Back"),"",Material.OAK_DOOR,()->home(p)));plugin.adminUpdate.show(p,t(p,"Detektor-Einstellungen","Detector settings"),b,0);
    }
    void players(Player p){if(!admin(p))return;Set<UUID> ids=new LinkedHashSet<>();for(Player target:Bukkit.getOnlinePlayers())if(!plugin.isBedrockClient(target))ids.add(target.getUniqueId());if(store!=null)ids.addAll(store.players());List<AdminUpdateMenu.Button>b=new ArrayList<>();ids.stream().sorted(Comparator.comparing(plugin::discordAdminPlayerName,String.CASE_INSENSITIVE_ORDER)).forEach(id->b.add(button(p,plugin.discordAdminPlayerName(id),(store==null?0:store.entries(id).size())+t(p," Hinweise"," hints"),Material.PLAYER_HEAD,()->player(p,id))));b.add(button(p,t(p,"Zurück","Back"),"",Material.OAK_DOOR,()->home(p)));plugin.adminUpdate.show(p,t(p,"Client-Hinweise","Client hints"),b,0);}
    String summary(UUID id){return summary(id,true);}
    String summary(UUID id,boolean german){if(store==null)return german?"Sicherheitsverlauf nicht verfügbar":"Security history unavailable";var snapshot=store.snapshot(id);return (snapshot==null?(german?"Kein gespeicherter Fingerprint":"No saved fingerprint"):((german?"Letzter beobachteter Fingerprint: ":"Last observed fingerprint: ")+ClientProbeHistory.timestamp(snapshot.at())+"\nBrand: "+snapshot.brand()+(german?"\nVersionshinweis: ":"\nVersion hint: ")+snapshot.version()+(german?"\nKanäle (ungeprüft): ":"\nChannels (unverified): ")+String.join(", ",snapshot.channels())))+(german?"\nGespeicherte ungeprüfte Hinweise: ":"\nSaved unverified hints: ")+store.entries(id).size();}
    void player(Player p,UUID id){if(!admin(p))return;List<AdminUpdateMenu.Button>b=new ArrayList<>();b.add(AdminUpdateMenu.info(plugin.discordAdminPlayerName(id),summary(id,t(p,"de","en").equals("de")),Material.NAME_TAG));
        Player target=Bukkit.getPlayer(id);if(target!=null&&!plugin.isBedrockClient(target)){b.add(button(p,t(p,"Fingerprint jetzt aktualisieren","Refresh fingerprint now"),t(p,"Aktive Detektoren auswerten","Evaluate enabled detectors"),Material.CLOCK,()->{State s=state(target);if(s!=null)network(s);player(p,id);}));if(plugin.has(p,"simplexadminsuite.clientcheck"))b.add(button(p,t(p,"Client oder einzelne Mod prüfen","Check client or individual mod"),"",Material.SHIELD,()->plugin.openClientProbe(p,id)));}
        if(store!=null)for(var entry:store.entries(id))b.add(button(p,entry.code(),ClientProbeHistory.timestamp(entry.at())+" • "+entry.detector(),Material.WRITABLE_BOOK,()->plugin.adminUpdate.detail(p,t(p,"Ungeprüfter Hinweis","Unverified hint"),entry.detail(),()->player(p,id))));
        b.add(button(p,t(p,"Diesen Verlauf löschen","Delete this history"),t(p,"Nur neue Detektor-Hinweise und Fingerprint; Bestätigung folgt","Only new detector hints and fingerprint; confirmation follows"),Material.BARRIER,()->clearMenu(p,id)));b.add(button(p,t(p,"Zurück","Back"),"",Material.OAK_DOOR,()->players(p)));plugin.adminUpdate.show(p,t(p,"Sicherheitsverlauf","Security history"),b,0);
    }
    private void clearMenu(Player p,UUID id){List<AdminUpdateMenu.Button>b=new ArrayList<>();b.add(button(p,t(p,"Verlauf jetzt löschen","Delete history now"),plugin.discordAdminPlayerName(id),Material.BARRIER,()->{try{if(store!=null)store.clear(id);State s=states.get(id);if(s!=null){s.seen.clear();s.resetClicks();s.interactions.clear();s.graceUntil=System.currentTimeMillis()+5000;}plugin.expansionAudit(p.getName(),id,"Security detector history cleared","Existing probes, bans and player data retained");}catch(Exception e){plugin.menuError(p,t(p,"§cVerlauf konnte nicht sicher gelöscht werden.","§cHistory could not be safely deleted."));}player(p,id);}));b.add(button(p,t(p,"Abbrechen","Cancel"),"",Material.OAK_DOOR,()->player(p,id)));plugin.adminUpdate.show(p,t(p,"Löschen bestätigen","Confirm deletion"),b,0);}
}
