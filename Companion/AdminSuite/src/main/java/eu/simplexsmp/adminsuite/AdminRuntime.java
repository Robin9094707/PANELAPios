package eu.simplexsmp.adminsuite;

import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.scheduler.BukkitTask;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Join verification, native vanish and observable-action monitoring. All mutation is on the server thread. */
final class AdminRuntime implements Listener {
    private final SimplexAdminSuite plugin;
    private final Map<UUID,Gate> gates=new ConcurrentHashMap<>();
    private final Map<UUID,org.bukkit.permissions.PermissionAttachment> flightPermissions=new HashMap<>();
    private final Map<UUID,Boolean> flightIntent=new HashMap<>();
    private final Set<UUID> flightManaged=new HashSet<>();
    private final Map<UUID,String> watchBars=new HashMap<>();
    private final Map<UUID,String> sentBars=new HashMap<>();
    private final Map<UUID,Flight> vanished=new ConcurrentHashMap<>();
    private final Map<UUID,Long> vanishSince=new HashMap<>();
    private final Map<UUID,String> vanishOrigin=new HashMap<>();
    private final Map<UUID,UUID> watchers=new HashMap<>();
    private final Map<UUID,String> lastAction=new HashMap<>(), inputState=new HashMap<>();
    private final Map<UUID,Long> actionAt=new HashMap<>(), sleepAt=new HashMap<>();
    private final Map<String,Long> alerts=new HashMap<>();
    private final Properties recovery=new Properties();
    private final Properties firstPending=new Properties();
    private final Map<UUID,Long> flightGrace=new HashMap<>();
    private final Path file;
    private BukkitTask task;
    private boolean closing;
    private long tick;
    private record Flight(boolean allow,boolean flying,boolean collidable,boolean sleepingIgnored,boolean silent) { }
    private static final class Gate {
        final Player player;final Runnable completed;final long deadline;final int slot;boolean showing,failed;
        Gate(Player p,Runnable done,int seconds,int slot){player=p;completed=done;deadline=System.nanoTime()+seconds*1_000_000_000L;this.slot=slot;}
    }
    AdminRuntime(SimplexAdminSuite p){plugin=p;file=p.getDataFolder().toPath().resolve("vanish-recovery.properties");
        if(Files.exists(file))try(Reader in=Files.newBufferedReader(file)){recovery.load(in);}catch(IOException e){throw new IllegalStateException("Vanish-Flugzustände konnten nicht geladen werden",e);}
    }
    boolean firstJoin(Player p,boolean first){
        if(first){firstPending.setProperty(p.getUniqueId().toString(),"pending");saveWelcome();}
        return firstPending.containsKey(p.getUniqueId().toString());
    }
    private void saveWelcome(){try{AtomicState.save(file.resolveSibling("first-join-pending.properties"),firstPending,"First joins awaiting successful verification");}catch(IOException e){plugin.getLogger().warning("Willkommensstatus konnte nicht gespeichert werden: "+e.getMessage());}}
    void start(){
        Path welcome=file.resolveSibling("first-join-pending.properties");
        if(Files.exists(welcome))try(Reader in=Files.newBufferedReader(welcome)){firstPending.load(in);}catch(IOException e){plugin.getLogger().warning("Willkommensstatus konnte nicht geladen werden: "+e.getMessage());}
        registerInputListener();
        for(Player p:Bukkit.getOnlinePlayers()){recover(p);plugin.adminAccess.join(p);}
        task=Bukkit.getScheduler().runTaskTimer(plugin,this::tick,5L,5L);
    }
    void emergencyRelease(Player p){Gate g=gates.remove(p.getUniqueId());if(g!=null&&!g.failed){p.closeInventory();g.completed.run();}if(plugin.qualityOfLife!=null)plugin.qualityOfLife.probes.interrupt(p);}
    void releaseGhost(Player p){ Gate g=gates.remove(p.getUniqueId()); if(g!=null)g.player.closeInventory(); }
    boolean blocked(Player p){return p!=null&&!plugin.emergency(p.getUniqueId())&&(gates.containsKey(p.getUniqueId())||plugin.qualityOfLife!=null&&plugin.qualityOfLife.probes.hold.locked(p));}
    boolean allowsMenu(Player p,InventoryHolder holder){return !blocked(p)||holder instanceof VerifyHolder;}
    boolean[] probeReturnFlight(Player p){Flight f=vanished.get(p.getUniqueId());return f==null?new boolean[]{p.getAllowFlight(),p.isFlying()}:new boolean[]{f.allow,f.flying};}
    boolean vanished(Player p){return p!=null&&vanished.containsKey(p.getUniqueId());}
    boolean passive(Player p){return blocked(p)||(vanished(p)&&plugin.getConfig().getBoolean("admin-update.vanish.silent-interactions",true));}
    boolean begin(Player p,Runnable done){
        plugin.adminAccess.join(p);recover(p);refreshVisibility();
        if(plugin.emergency(p.getUniqueId())||!plugin.getConfig().getBoolean("admin-update.verification.enabled",true))return false;
        int seconds=clamp(plugin.getConfig().getInt("admin-update.verification.timeout-seconds",30),5,300);
        int slot=plugin.getConfig().getBoolean("admin-update.verification.random-slot",true)?10+java.util.concurrent.ThreadLocalRandom.current().nextInt(7):13;
        Gate g=new Gate(p,done,seconds,slot);gates.put(p.getUniqueId(),g);
        p.sendMessage(plugin.playerUiText(p, "§eWelcome! Please confirm that you are not a robot to start playing."));
        Bukkit.getScheduler().runTaskLater(plugin,()->show(g),clamp(plugin.getConfig().getInt("admin-update.verification.delay-ticks",20),1,100));return true;
    }
    private void show(Gate g){
        Player p=g.player;if(closing||g.failed||gates.get(p.getUniqueId())!=g||Bukkit.getPlayer(p.getUniqueId())!=p||g.showing)return;
        g.showing=true;
        if(plugin.isBedrockClient(p)){
            SimplexAdminSuite.SimpleForm.Builder form=SimplexAdminSuite.SimpleForm.builder().title("Welcome • Player verification")
                .content("Please confirm that you are a real player.\nSelect 'I am not a robot' to unlock movement and chat.")
                .verification().closedOrInvalidResultHandler(()->reopen(g));
            if(g.slot%2==0)form.button("Not yet",r->reopen(g));
            form.button("§aI am not a robot",r->plugin.runAdminTask(()->complete(g)));
            if(g.slot%2!=0)form.button("Not yet",r->reopen(g));
            if(plugin.sendBedrockForm(p,form))return;
        }
        VerifyHolder h=new VerifyHolder(g);Inventory inv=LiveMenus.create(h,27,plugin.playerUiText(p,"Player verification"));h.inventory=inv;
        for(int i=0;i<27;i++)inv.setItem(i,plugin.item(Material.GRAY_STAINED_GLASS_PANE,"&7Player verification","&7Select the green button to continue."));
        inv.setItem(g.slot,plugin.item(Material.LIME_DYE,"&aI am not a robot","&fClick here to start playing!"));plugin.localizePublicInventory(p,inv);LiveMenus.show(p,inv);
    }
    private void reopen(Gate g){Bukkit.getScheduler().runTaskLater(plugin,()->{g.showing=false;show(g);},5L);}
    private void complete(Gate g){
        if(gates.get(g.player.getUniqueId())!=g||System.nanoTime()>=g.deadline||Bukkit.getPlayer(g.player.getUniqueId())!=g.player)return;
        gates.remove(g.player.getUniqueId());g.player.closeInventory();g.player.sendMessage(plugin.playerUiText(g.player, "§aVerified! Enjoy your time on the server."));g.completed.run();
    }
    void settingsChanged(){if(!plugin.getConfig().getBoolean("admin-update.verification.enabled",true))for(Gate g:List.copyOf(gates.values())){
        gates.remove(g.player.getUniqueId());g.player.closeInventory();if(!g.failed && Bukkit.getPlayer(g.player.getUniqueId())==g.player)g.completed.run();
    }}
    void welcome(Player p,boolean first){
        if(firstPending.remove(p.getUniqueId().toString())!=null)saveWelcome();
        if(!first||plugin.discordAdminHidden(p)||!plugin.getConfig().getBoolean("admin-update.welcome.enabled",true))return;
        String msg=plugin.color(plugin.getConfig().getString("admin-update.welcome.message","&8[&aWelcome&8] &a@{player} is joining for the first time. Welcome to the server!").replace("{player}",p.getName()));
        String sound=plugin.getConfig().getString("admin-update.welcome.sound","entity.player.levelup");
        for(Player viewer:Bukkit.getOnlinePlayers()){viewer.sendMessage(plugin.community!=null&&plugin.community.german(viewer)?plugin.color(plugin.getConfig().getString("player-languages.de.welcome", "&8[&aWillkommen&8] &a@{player} spielt zum ersten Mal hier. Willkommen auf dem Server!").replace("{player}",p.getName())):msg);if(!sound.equalsIgnoreCase("none"))try{viewer.playSound(viewer.getLocation(),sound,clamp(plugin.getConfig().getInt("admin-update.welcome.volume-percent",50),0,100)/100f,clamp(plugin.getConfig().getInt("admin-update.welcome.pitch-percent",125),50,200)/100f);}catch(IllegalArgumentException ignored){}}
    }
    void toggle(Player p){
        if(!plugin.adminSessionUsable(p)||!plugin.has(p,"simplexadminsuite.admin")||!plugin.has(p,"simplexadminsuite.vanish")){plugin.menuError(p,"§cKeine Vanish-Berechtigung.");return;}
        if(vanished(p)){disable(p);p.sendMessage(plugin.playerText(p,"§eVanish deaktiviert.","§eVanish disabled."));}
        else{
            Flight f=new Flight(p.getAllowFlight(),p.isFlying(),p.isCollidable(),p.isSleepingIgnored(),p.isSilent());
            recovery.setProperty(p.getUniqueId().toString(),f.allow+","+f.flying+","+f.collidable+","+f.sleepingIgnored+","+f.silent);
            if(!saveRecovery()){recovery.remove(p.getUniqueId().toString());plugin.menuNotice(p,"§cVanish konnte nicht sicher gespeichert werden.");return;}
            vanishSince.put(p.getUniqueId(),System.currentTimeMillis());vanishOrigin.put(p.getUniqueId(),position(p));
            plugin.expansionAudit(p.getName(),p.getUniqueId(),"Vanish aktiviert","Start: "+position(p));
            vanished.put(p.getUniqueId(),f);
            try{publishMetadata(p);p.setCollidable(false);p.setSleepingIgnored(true);p.setSilent(true);
                flightIntent.put(p.getUniqueId(),true);maintainFlight(p);renderBar(p);
            }catch(RuntimeException failure){disable(p);plugin.menuError(p,"§cVanish konnte nicht vollständig aktiviert werden.");return;}
            p.sendMessage(plugin.playerText(p,"§aVanish aktiviert: Für andere Spieler verborgen; /vanish beendet den Modus.","§aVanish enabled: hidden from other players; /vanish exits this mode."));
        }
        refreshVisibility();
        if(plugin.qualityOfLife!=null)plugin.qualityOfLife.visibilityChanged(p);
        alert("vanish",p.getName()+": Vanish "+(vanished(p)?"aktiv":"inaktiv"));
    }
    private void disable(Player p){
        Flight f=vanished.remove(p.getUniqueId());
        p.removeMetadata("vanished",plugin);
        removeFlightPermissions(p);flightIntent.remove(p.getUniqueId());flightManaged.remove(p.getUniqueId());
        // Release packet snapshots BEFORE showPlayer sends tab/spawn packets.
        if(plugin.qualityOfLife!=null)plugin.qualityOfLife.prepareReveal(p);
        if(f!=null){
            long since=vanishSince.getOrDefault(p.getUniqueId(),System.currentTimeMillis());
            plugin.expansionAudit(p.getName(),p.getUniqueId(),"Vanish beendet","Start: "+vanishOrigin.getOrDefault(p.getUniqueId(),"—")+" • Ende: "+position(p)+" • Dauer: "+Math.max(0,(System.currentTimeMillis()-since)/1000)+" Sekunden"+(closing?" • Server beendet":(!p.isOnline()?" • Verbindung beendet":"")));
            vanishSince.remove(p.getUniqueId());vanishOrigin.remove(p.getUniqueId());restore(p,f);renderBar(p);
        }
        refreshVisibility();
        if(plugin.qualityOfLife!=null)plugin.qualityOfLife.visibilityChanged(p);
    }
    private static String position(Player p){return locationText(p.getLocation());}
    private static String locationText(Location l){return (l.getWorld()==null?"—":l.getWorld().getName())+" • "+l.getBlockX()+" "+l.getBlockY()+" "+l.getBlockZ();}
    private void removeFlightPermissions(Player p){
        org.bukkit.permissions.PermissionAttachment attachment=flightPermissions.remove(p.getUniqueId());
        if(attachment!=null)try{p.removeAttachment(attachment);}catch(IllegalArgumentException ignored){}
    }
    private void publishMetadata(Player p){
        boolean publish=vanished(p)&&plugin.getConfig().getBoolean("admin-update.vanish.publish-metadata",true);
        if(!publish){p.removeMetadata("vanished",plugin);return;}
        // Own metadata only; leave Essentials/other plugins' entries intact.
        if(p.getMetadata("vanished").stream().noneMatch(value->value.getOwningPlugin()==plugin&&value.asBoolean()))
            p.setMetadata("vanished",new org.bukkit.metadata.FixedMetadataValue(plugin,true));
    }
    private void maintainFlight(Player p){
        if(vanished(p)){publishMetadata(p);if(p.isCollidable())p.setCollidable(false);if(!p.isSleepingIgnored())p.setSleepingIgnored(true);if(!p.isSilent())p.setSilent(true);}
        UUID id=p.getUniqueId();
        boolean enabled=vanished(p)&&plugin.getConfig().getBoolean("admin-update.vanish.flight",true);
        boolean lands=enabled&&plugin.getConfig().getBoolean("admin-update.vanish.lands-flight-bypass",true);
        if(lands&&!flightPermissions.containsKey(id)){
            org.bukkit.permissions.PermissionAttachment a=p.addAttachment(plugin);
            a.setPermission("lands.bypass.fly",true);
            a.setPermission("lands.bypass.wilderness.fly",true);
            flightPermissions.put(id,a);
        }else if(!lands)removeFlightPermissions(p);
        if(!enabled){
            if(flightManaged.remove(id)){
                Flight f=vanished.get(id);boolean mode=p.getGameMode()==GameMode.CREATIVE||p.getGameMode()==GameMode.SPECTATOR;
                if(f!=null){p.setAllowFlight(f.allow||mode);p.setFlying(f.flying&&(f.allow||mode));p.setFallDistance(0f);flightGrace.put(id,System.currentTimeMillis()+15000L);}
            }
            return;
        }
        flightManaged.add(id);
        if(!p.getAllowFlight())p.setAllowFlight(true);
        // Respect deliberate landing/double-tap; repair externally revoked flight only.
        if(flightIntent.getOrDefault(id,false)&&!p.isFlying()&&!p.isDead())p.setFlying(true);
    }
    String decorateBar(Player p,String text){return (vanished(p)?plugin.playerText(p,"§aVanish aktiv §8| ","§aVanish active §8| "):"")+text;}
    private void renderBar(Player p){
        String value=vanished(p)?plugin.playerText(p,"§aVanish aktiv §7• Für Spieler unsichtbar","§aVanish active §7• Hidden from players"):"";
        String watch=watchBars.getOrDefault(p.getUniqueId(),"");
        if(!watch.isBlank())value+=(value.isEmpty()?"":" §8| ")+watch;
        if(plugin.xrayBait!=null){String bait=plugin.xrayBait.status(p);if(!bait.isBlank())value+=(value.isEmpty()?"":" §8| ")+bait;}
        if(!value.isEmpty()||sentBars.containsKey(p.getUniqueId()))p.sendActionBar(value);
        if(value.isEmpty())sentBars.remove(p.getUniqueId());else sentBars.put(p.getUniqueId(),value);
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void flightToggle(PlayerToggleFlightEvent e){if(vanished(e.getPlayer()))flightIntent.put(e.getPlayer().getUniqueId(),e.isFlying());}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void flightTeleport(PlayerTeleportEvent e){if(vanished(e.getPlayer())){if(e.getTo()!=null)plugin.expansionAudit(e.getPlayer().getName(),e.getPlayer().getUniqueId(),"Vanish teleportiert","Von: "+locationText(e.getFrom())+" • Nach: "+locationText(e.getTo()));plugin.runAdminTask(()->{if(e.getPlayer().isOnline())maintainFlight(e.getPlayer());});}}
    @EventHandler(priority=EventPriority.MONITOR)
    public void flightRespawn(PlayerRespawnEvent e){if(vanished(e.getPlayer()))plugin.runAdminTask(()->{if(e.getPlayer().isOnline())maintainFlight(e.getPlayer());});}

    private void restore(Player p,Flight f){
        boolean modeFlight=p.getGameMode()==GameMode.CREATIVE||p.getGameMode()==GameMode.SPECTATOR;
        boolean success=true;
        // One unsupported setter must not prevent all remaining state restoration.
        for(Runnable step:List.<Runnable>of(()->p.setAllowFlight(f.allow||modeFlight),()->p.setFlying(f.flying&&(f.allow||modeFlight)),()->p.setCollidable(f.collidable),()->p.setSleepingIgnored(f.sleepingIgnored),()->p.setSilent(f.silent))){
            try{step.run();}catch(RuntimeException failure){success=false;plugin.getLogger().warning("Vanish recovery for "+p.getUniqueId()+": "+failure.getMessage());}
        }
        // Avoid fall damage when survival flight ends in mid-air.
        p.setFallDistance(0f);flightGrace.put(p.getUniqueId(),System.currentTimeMillis()+15000L);
        if(success){String previous=(String)recovery.remove(p.getUniqueId().toString());if(!saveRecovery()&&previous!=null)recovery.setProperty(p.getUniqueId().toString(),previous);}
    }
    private void recover(Player p){String value=recovery.getProperty(p.getUniqueId().toString());if(value==null)return;
        String[]v=value.split(",");if((v.length!=4&&v.length!=5)||Arrays.stream(v).anyMatch(flag->!flag.equals("true")&&!flag.equals("false"))){plugin.getLogger().warning("Invalid Vanish recovery record retained for "+p.getUniqueId());return;}restore(p,new Flight(Boolean.parseBoolean(v[0]),Boolean.parseBoolean(v[1]),Boolean.parseBoolean(v[2]),Boolean.parseBoolean(v[3]),v.length==5?Boolean.parseBoolean(v[4]):p.isSilent()));
    }
    private boolean saveRecovery(){try{AtomicState.save(file,recovery,"Original flight / collision states; recovered after restart");return true;}catch(IOException e){plugin.getLogger().warning("Vanish-Wiederherstellung konnte nicht gespeichert werden: "+e.getMessage());return false;}}
    private void refreshVisibility(){for(Player target:Bukkit.getOnlinePlayers())plugin.refreshAdminVisibility(target);}
    void watch(Player admin,Player target){
        if(!plugin.adminSessionUsable(admin)||!plugin.has(admin,"simplexadminsuite.admin")||!plugin.has(admin,"simplexadminsuite.watch"))return;
        if(target==null){watchers.remove(admin.getUniqueId());watchBars.remove(admin.getUniqueId());renderBar(admin);admin.sendMessage("§eLive-Beobachtung beendet.");return;}
        if(!plugin.getConfig().getBoolean("admin-update.watch.enabled",true)){plugin.menuError(admin,"§cLive-Watch ist deaktiviert.");return;}
        watchers.put(admin.getUniqueId(),target.getUniqueId());admin.closeInventory();admin.sendMessage("§aLive-Beobachtung: "+target.getName()+". Beenden: /sas watch off");
        plugin.expansionAudit(admin.getName(),target.getUniqueId(),"Live-Watch","gestartet");
    }
    private void action(Player p,String action){if(watchers.containsValue(p.getUniqueId())){lastAction.put(p.getUniqueId(),action);actionAt.put(p.getUniqueId(),System.currentTimeMillis());}}
    private void tick(){
        tick++;
        flightGrace.entrySet().removeIf(e->e.getValue()<System.currentTimeMillis());
        for(Gate g:List.copyOf(gates.values()))if(!g.failed&&System.nanoTime()>=g.deadline){
            g.failed=true;plugin.suiteKick(g.player,plugin.playerUiText(g.player, "Verification timed out. Please reconnect and confirm that you are not a robot."));
            alert("verification-timeout",g.player.getName()+": Beitrittsprüfung nicht rechtzeitig bestätigt.");
        }
        for(UUID id:List.copyOf(vanished.keySet())){Player p=Bukkit.getPlayer(id);if(p!=null){if(!plugin.adminSessionUsable(p)||!plugin.has(p,"simplexadminsuite.admin")||!plugin.has(p,"simplexadminsuite.vanish")){disable(p);refreshVisibility();}else maintainFlight(p);}}
        if(tick%4==0)for(UUID id:vanished.keySet()){Player p=Bukkit.getPlayer(id);if(p!=null)plugin.refreshAdminVisibility(p);}
        for(var entry:List.copyOf(watchers.entrySet())){
            Player admin=Bukkit.getPlayer(entry.getKey()),target=Bukkit.getPlayer(entry.getValue());
            if(admin==null||target==null||!plugin.adminSessionUsable(admin)||!plugin.has(admin,"simplexadminsuite.watch")||!plugin.getConfig().getBoolean("admin-update.watch.enabled",true)){
                watchers.remove(entry.getKey());watchBars.remove(entry.getKey());if(admin!=null)renderBar(admin);continue;
            }
            String recent=System.currentTimeMillis()-actionAt.getOrDefault(target.getUniqueId(),0L)<3000?lastAction.getOrDefault(target.getUniqueId(),"—"):"—";
            Location l=target.getLocation();
            watchBars.put(admin.getUniqueId(),"§b"+target.getName()+" §7| "+(target.isSneaking()?"Schleichen ":"")+(target.isSprinting()?"Sprinten ":"")+(target.isFlying()?"Fliegen ":"")+"§f"+recent+(inputState.containsKey(target.getUniqueId())?" §7| Eingabe: "+inputState.get(target.getUniqueId()):"")+" §7| "+l.getBlockX()+" "+l.getBlockY()+" "+l.getBlockZ());
        }
        updateBars();
    }
    private void updateBars(){
        Set<UUID> ids=new HashSet<>(sentBars.keySet());ids.addAll(vanished.keySet());ids.addAll(watchBars.keySet());
        if(plugin.xrayBait!=null)ids.addAll(plugin.xrayBait.observers());
        for(UUID id:ids){Player p=Bukkit.getPlayer(id);if(p!=null)renderBar(p);else sentBars.remove(id);}
    }
    private void alert(String type,String message){
        if(!plugin.getConfig().getBoolean("admin-update.alerts."+type,false))return;
        long now=System.currentTimeMillis(),delay=clamp(plugin.getConfig().getInt("admin-update.alerts.cooldown-seconds",30),5,600)*1000L;
        if(now-alerts.getOrDefault(type,0L)<delay)return;alerts.put(type,now);
        if(plugin.discordRelay()!=null)plugin.discordRelay().adminUpdateAlert(message);
    }
    @EventHandler(priority=EventPriority.LOWEST) public void move(PlayerMoveEvent e){
        if(plugin.managedProbeTeleport(e.getPlayer()))return;
        if(blocked(e.getPlayer())){e.setCancelled(true);return;}
        Location a=e.getFrom(),b=e.getTo();if(b!=null&&(a.getX()!=b.getX()||a.getY()!=b.getY()||a.getZ()!=b.getZ()))action(e.getPlayer(),b.getY()>a.getY()+0.15?"Aufwärtsbewegung":"Bewegen");
    }
    @EventHandler(priority=EventPriority.LOWEST) public void command(PlayerCommandPreprocessEvent e){if(blocked(e.getPlayer())){e.setCancelled(true);if(plugin.qualityOfLife==null||!plugin.qualityOfLife.probes.hold.locked(e.getPlayer()))e.getPlayer().sendMessage(plugin.playerUiText(e.getPlayer(), "§ePlease complete player verification first."));}}
    @EventHandler(priority=EventPriority.HIGHEST) public void interact(PlayerInteractEvent e){if(passive(e.getPlayer()))e.setCancelled(true);else action(e.getPlayer(),"Interaktion");}
    @EventHandler(priority=EventPriority.HIGHEST) public void preciseInteract(PlayerInteractAtEntityEvent e){if(passive(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void vibration(org.bukkit.event.world.GenericGameEvent e){if(e.getEntity() instanceof Player p&&vanished(p))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void entityInteract(PlayerInteractEntityEvent e){if(passive(e.getPlayer()))e.setCancelled(true);else action(e.getPlayer(),"Entität anklicken");}
    @EventHandler(priority=EventPriority.HIGHEST) public void drop(PlayerDropItemEvent e){if(passive(e.getPlayer()))e.setCancelled(true);else action(e.getPlayer(),"Item ablegen");}
    @EventHandler(priority=EventPriority.HIGHEST) public void pickup(EntityPickupItemEvent e){if(e.getEntity() instanceof Player p&&passive(p))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void pickupExperience(com.destroystokyo.paper.event.player.PlayerPickupExperienceEvent e){if(passive(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void projectileCollision(com.destroystokyo.paper.event.entity.ProjectileCollideEvent e){if(e.getCollidedWith() instanceof Player p&&vanished(p))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void damage(EntityDamageEvent e){if(e.getEntity() instanceof Player p&&(blocked(p)||vanished(p)||(e.getCause()==EntityDamageEvent.DamageCause.FALL&&flightGrace.getOrDefault(p.getUniqueId(),0L)>System.currentTimeMillis())))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void hit(EntityDamageByEntityEvent e){if(e.getDamager() instanceof Player p){if(passive(p))e.setCancelled(true);else action(p,"Angriff");}else if(e.getDamager() instanceof Projectile shot&&shot.getShooter() instanceof Player p&&passive(p))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void breakBlock(BlockBreakEvent e){if(passive(e.getPlayer()))e.setCancelled(true);else action(e.getPlayer(),"Block abbauen");}
    @EventHandler(priority=EventPriority.HIGHEST) public void place(BlockPlaceEvent e){if(passive(e.getPlayer()))e.setCancelled(true);else action(e.getPlayer(),"Block setzen");}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void silentChat(AsyncPlayerChatEvent e){if(vanished(e.getPlayer())&&plugin.getConfig().getBoolean("admin-update.vanish.silent-interactions",true)){e.setCancelled(true);e.getPlayer().sendMessage(plugin.playerText(e.getPlayer(),"Im Vanish ist der öffentliche Chat deaktiviert.","Public chat is disabled while vanished."));}}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void exhaustion(EntityExhaustionEvent e){if(e.getEntity() instanceof Player p&&vanished(p))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void arrow(org.bukkit.event.player.PlayerPickupArrowEvent e){if(passive(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void consume(PlayerItemConsumeEvent e){if(passive(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void swap(PlayerSwapHandItemsEvent e){if(blocked(e.getPlayer()))e.setCancelled(true);else action(e.getPlayer(),"Handwechsel");}
    @EventHandler(priority=EventPriority.HIGHEST) public void teleport(PlayerTeleportEvent e){if(blocked(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void open(InventoryOpenEvent e){if(e.getPlayer() instanceof Player p&&blocked(p)&&!(LiveMenus.holder(e.getInventory()) instanceof VerifyHolder))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void click(InventoryClickEvent e){
        if(!(e.getWhoClicked() instanceof Player p)||!blocked(p))return;e.setCancelled(true);
        if(LiveMenus.holder(e.getInventory()) instanceof VerifyHolder h&&h.gate==gates.get(p.getUniqueId())&&e.getRawSlot()==h.gate.slot)
            plugin.runAdminTask(()->complete(h.gate));
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void drag(InventoryDragEvent e){if(e.getWhoClicked() instanceof Player p&&blocked(p))e.setCancelled(true);}
    @EventHandler public void close(InventoryCloseEvent e){if(LiveMenus.holder(e.getInventory()) instanceof VerifyHolder h)reopen(h.gate);}
    @EventHandler(priority=EventPriority.HIGHEST) public void bedGate(PlayerBedEnterEvent e){if(passive(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void bed(PlayerBedEnterEvent e){
        Player p=e.getPlayer();Bukkit.getScheduler().runTaskLater(plugin,()->{
            if(!p.isSleeping()||plugin.discordAdminHidden(p)||!plugin.getConfig().getBoolean("discord-bot.relay.sleep",true))return;
            long now=System.currentTimeMillis();if(now-sleepAt.getOrDefault(p.getUniqueId(),0L)<clamp(plugin.getConfig().getInt("discord-bot.relay.sleep-cooldown-seconds",30),5,300)*1000L)return;
            sleepAt.put(p.getUniqueId(),now);int eligible=0,sleeping=0;
            for(Player other:Bukkit.getOnlinePlayers())if(other.getWorld().equals(p.getWorld())&&other.getGameMode()!=GameMode.SPECTATOR&&!other.isSleepingIgnored()&&!blocked(other)&&!plugin.discordAdminHidden(other)){eligible++;if(other.isSleeping())sleeping++;}
            int percent=100;try{percent=Integer.parseInt(WorldSettings.value(p.getWorld(),"playersSleepingPercentage","100"));}catch(RuntimeException ignored){}
            int required=requiredSleepers(eligible,percent);
            if(plugin.discordRelay()!=null)plugin.discordRelay().relaySleep(p.getName(),sleeping,required,eligible,p.getWorld().getName());
        },2L);
    }
    static int requiredSleepers(int eligible,int percent){return Math.max(1,(int)Math.ceil(Math.max(0,eligible)*Math.max(0,percent)/100.0));}
    @EventHandler(priority=EventPriority.LOWEST) public void resetOnJoin(PlayerJoinEvent e){
        Player p=e.getPlayer();disable(p);recover(p);
        for(long delay:new long[]{1L,20L})Bukkit.getScheduler().runTaskLater(plugin,()->{
            if(Bukkit.getPlayer(p.getUniqueId())!=p)return;
            if(!vanished(p)&&!blocked(p))recover(p);
            if(plugin.qualityOfLife!=null)plugin.qualityOfLife.prepareReveal(p);
            refreshVisibility();
            if(plugin.qualityOfLife!=null)plugin.qualityOfLife.visibilityChanged(p);
        },delay);
    }
    @EventHandler(priority=EventPriority.MONITOR) public void worldVisibility(PlayerChangedWorldEvent e){
        Player p=e.getPlayer();if(plugin.qualityOfLife!=null)plugin.qualityOfLife.prepareReveal(p);
        if(vanished(p))maintainFlight(p);refreshVisibility();
        if(plugin.qualityOfLife!=null)plugin.qualityOfLife.visibilityChanged(p);
    }
    @EventHandler(priority=EventPriority.MONITOR) public void quit(PlayerQuitEvent e){Player p=e.getPlayer();gates.remove(p.getUniqueId());disable(p);plugin.adminAccess.quit(p);watchers.remove(p.getUniqueId());watchBars.remove(p.getUniqueId());sentBars.remove(p.getUniqueId());lastAction.remove(p.getUniqueId());inputState.remove(p.getUniqueId());actionAt.remove(p.getUniqueId());sleepAt.remove(p.getUniqueId());flightGrace.remove(p.getUniqueId());}
    void stop(){closing=true;if(task!=null)task.cancel();for(Player p:Bukkit.getOnlinePlayers()){disable(p);if(blocked(p))p.closeInventory();}gates.clear();watchers.clear();watchBars.clear();for(Player p:Bukkit.getOnlinePlayers())if(sentBars.containsKey(p.getUniqueId()))p.sendActionBar("");sentBars.clear();refreshVisibility();}
    private void registerInputListener(){
        try {
            Class<?> eventType=Class.forName("org.bukkit.event.player.PlayerInputEvent");
            Class<?> executorType=Class.forName("org.bukkit.plugin.EventExecutor");
            Object executor=java.lang.reflect.Proxy.newProxyInstance(executorType.getClassLoader(),new Class<?>[]{executorType},(proxy,method,args)->{
                if(!method.getName().equals("execute")||args==null||args.length!=2)return null;
                Object event=args[1];Object player=eventType.getMethod("getPlayer").invoke(event);
                if(player instanceof Player p&&watchers.containsValue(p.getUniqueId())){
                    Object input=eventType.getMethod("getInput").invoke(event);List<String> keys=new ArrayList<>();
                    String[][] flags={{"isForward","Vorwärts"},{"isBackward","Rückwärts"},{"isLeft","Links"},{"isRight","Rechts"},{"isJump","Springen"},{"isSneak","Schleichen"},{"isSprint","Sprinten"}};
                    for(String[] flag:flags)if(Boolean.TRUE.equals(SimplexAdminSuite.relayReflect(input,flag[0])))keys.add(flag[1]);
                    inputState.put(p.getUniqueId(),keys.isEmpty()?"—":String.join(" + ",keys));
                }
                return null;
            });
            org.bukkit.plugin.PluginManager.class.getMethod("registerEvent",Class.class,Listener.class,EventPriority.class,executorType,org.bukkit.plugin.Plugin.class,boolean.class)
                .invoke(Bukkit.getPluginManager(),eventType,this,EventPriority.MONITOR,executor,plugin,true);
        }catch(ReflectiveOperationException ignored){/* Older versions still provide actions and movement events. */}
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void chatGate(AsyncPlayerChatEvent e){if(blocked(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void food(FoodLevelChangeEvent e){if(e.getEntity() instanceof Player p&&blocked(p))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void target(EntityTargetLivingEntityEvent e){if(e.getTarget() instanceof Player p&&(blocked(p)||vanished(p)))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void bucketEmpty(PlayerBucketEmptyEvent e){if(passive(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void bucketFill(PlayerBucketFillEvent e){if(passive(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void vehicle(org.bukkit.event.vehicle.VehicleEnterEvent e){if(e.getEntered() instanceof Player p&&passive(p))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void ping(org.bukkit.event.server.ServerListPingEvent e){
        int hidden=0;for(Player p:Bukkit.getOnlinePlayers())if(plugin.discordAdminHidden(p))hidden++;
        if(hidden>0){try { Class.forName("com.destroystokyo.paper.event.server.PaperServerListPingEvent").getMethod("setNumPlayers",int.class).invoke(e,Math.max(0,e.getNumPlayers()-hidden)); } catch (ReflectiveOperationException | IllegalArgumentException ignored) { /* Bukkit-only server: iterator still filters sample. */ }try{Iterator<Player> it=e.iterator();while(it.hasNext())if(plugin.discordAdminHidden(it.next()))it.remove();}catch(UnsupportedOperationException ignored){}}
    }
    private static int clamp(int n,int min,int max){return Math.max(min,Math.min(max,n));}
    private static final class VerifyHolder implements InventoryHolder{final Gate gate;Inventory inventory;VerifyHolder(Gate g){gate=g;}public Inventory getInventory(){return inventory;}}
}
