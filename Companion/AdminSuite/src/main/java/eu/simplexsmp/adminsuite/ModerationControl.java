package eu.simplexsmp.adminsuite;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.scheduler.BukkitTask;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import java.io.*;
import java.nio.file.*;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Shared Java/Bedrock administration and constant-time action gates; additive persistent state. */
final class ModerationControl implements Listener {
    static final Set<String> ROOTS = Set.of("moderationplus", "banmode", "restrict", "deviceblock", "chatstyle", "modlog", "suiteconfig", "osblock", "adminlog");
    static final Set<String> MODES = Set.of("account", "ultra", "silent", "ghost", "none");
    static final Set<String> PRESETS = Set.of("text", "peaceful", "text-peaceful", "interactions", "building", "inventory", "none");
    private static final Set<String> TEXT_COMMANDS = Set.of("msg", "tell", "w", "whisper", "pm", "m", "message", "r", "reply", "mail", "email", "me", "say", "broadcast", "bc", "announce", "nickname", "nick", "rename", "itemname", "sign", "book", "discord", "report", "helpop", "staff", "staffmsg", "ask", "aiadmin", "aiconfirm", "teammsg", "tm", "party", "guild", "clan", "chat", "ec", "gc", "ac", "sethome", "home", "warp", "land", "lands");
    private final SimplexAdminSuite plugin;
    private final Path file, logFile;
    private final Map<UUID, Rule> rules = new ConcurrentHashMap<>();
    private final Map<UUID, Effective> effective = new ConcurrentHashMap<>();
    private final Set<UUID> ghosts = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Boolean> collisionBefore = new HashMap<>();
    private final Map<UUID, String> currentModels = new ConcurrentHashMap<>();
    private final Map<String, String> knownModels = new ConcurrentHashMap<>();
    private final Set<String> blockedModels = ConcurrentHashMap.newKeySet();
    static final List<String> LOG_TYPES = List.of("admin", "punishments", "bans", "mutes", "vanish", "devices", "ai", "ghost", "client-probes", "alt-accounts", "join-details");
    private final Map<UUID,String> currentOs = new ConcurrentHashMap<>();
    private final Map<String,String> knownOs = new ConcurrentHashMap<>();
    private final Set<String> blockedOs = ConcurrentHashMap.newKeySet();
    private final Deque<String> actionTail = new ArrayDeque<>();
    private final Map<UUID, Long> notices = new ConcurrentHashMap<>();
    private final Map<String, Long> logTimes = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastChat = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastChatAt = new ConcurrentHashMap<>();
    private final Deque<String> pendingLogs = new ArrayDeque<>();
    private final AtomicBoolean flushing = new AtomicBoolean();
    private BukkitTask ticker;
    private volatile boolean dirty;
    private static final class Rule {
        final UUID source; final String preset, reason, actor; final long until; final boolean inherit;
        final Set<UUID> accounts = new HashSet<>(); final Set<String> identities = new HashSet<>();
        Rule(UUID source, String preset, String reason, String actor, long until, boolean inherit) {
            this.source=source; this.preset=preset; this.reason=reason; this.actor=actor; this.until=until; this.inherit=inherit; accounts.add(source);
        }
    }
    private record Effective(Set<String> flags, String reason, long until) { }
    ModerationControl(SimplexAdminSuite plugin) {
        this.plugin=plugin; file=plugin.getDataFolder().toPath().resolve("moderation-control.properties");
        logFile=plugin.getDataFolder().toPath().resolve("logs/ghost-audit.log"); load();
    }
    void start() {
        for(String model:plugin.controlKnownModels()) observeModel(null,model);
        for(String os:List.of("ANDROID","IOS","OSX","AMAZON","GEAR_VR","HOLOLENS","WINDOWS","WIN32","DEDICATED","TVOS","PLAYSTATION","NINTENDO","XBOX","WINDOWS_PHONE","LINUX")) observeOs(null,os);
        for(var device:plugin.controlDevices())observeOs(null,device.os());
        refreshOnline();
        ticker=Bukkit.getScheduler().runTaskTimer(plugin,()->{
            long now=System.currentTimeMillis();
            boolean expired=rules.values().removeIf(r->r.until!=Long.MAX_VALUE&&r.until<=now);
            if(expired){dirty=true;refreshOnline();}
            notices.entrySet().removeIf(e->now-e.getValue()>60_000L);
            for(UUID id:new HashSet<>(lastChatAt.keySet()))if(now-lastChatAt.get(id)>1000L){lastChatAt.remove(id);lastChat.remove(id);}
            logTimes.entrySet().removeIf(e->now-e.getValue()>60_000L);
            if(dirty)save();
            flushLogs();
        },20L,20L);
    }
    void stop() {
        if(ticker!=null)ticker.cancel(); save();
        flushLogsSync();
        for(UUID id:new HashSet<>(collisionBefore.keySet())){Player p=Bukkit.getPlayer(id);if(p!=null)restoreGhost(p);}
        ghosts.clear();effective.clear();currentModels.clear();
    }
    static String modelKey(String value) {
        if(value==null)return "";
        return Normalizer.normalize(value,Normalizer.Form.NFKC).replaceAll("[\\p{Cntrl}\\p{Cf}]", "").trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
    static String normalizeMode(String mode) { return Set.of("ultra","silent","ghost").contains(mode)?mode:"ultra"; }
    static boolean chatTextCommand(String command) {
        if (command == null) return false;
        String label = command.replaceFirst("^/", "").split("\\s+",2)[0].toLowerCase(Locale.ROOT);
        if (label.contains(":")) label = label.substring(label.indexOf(':')+1);
        return Set.of("msg","tell","w","whisper","pm","m","message","r","reply","me","say","broadcast","bc","mail","chat","teammsg","tm").contains(label);
    }
    static boolean spawnCommand(String command) { return command != null && command.trim().equalsIgnoreCase("/spawn"); }
    static Set<String> flags(String preset) {
        return switch(preset){
            case "text" -> Set.of("text"); case "peaceful" -> Set.of("peaceful");
            case "text-peaceful" -> Set.of("text","peaceful");
            case "interactions" -> Set.of("interactions"); case "building" -> Set.of("building");
            case "inventory" -> Set.of("inventory"); default -> Set.of();
        };
    }
    boolean ghost(Player player){return player!=null&&!plugin.emergency(player.getUniqueId())&&ghosts.contains(player.getUniqueId());}
    void setGhost(UUID id,boolean enabled){if(enabled)ghosts.add(id);else ghosts.remove(id);}
    void observeModel(UUID id,String model) {
        String key=modelKey(model); if(key.isBlank()||key.length()>160)return;
        if(knownModels.putIfAbsent(key,model.trim())==null)dirty=true;
        if(id!=null)currentModels.put(id,key);
    }
    void observeOs(UUID id,String os) {
        String key=osKey(os);if(key.isBlank())return;
        if(knownOs.putIfAbsent(key,key)==null)dirty=true;
        if(id!=null)currentOs.put(id,key);
    }
    static String osKey(String os) {
        String key=modelKey(os).toUpperCase(Locale.ROOT).replace(" ","_").replace("-","_");
        return switch(key){case "IOS","I_OS"->"IOS";case "WINDOWS_10","UWP","WINDOWS_BEDROCK"->"WINDOWS";case "MACOS","MAC_OS","MAC_OS_X"->"OSX";case "PS4","PS5","PLAYSTATION_4","PLAYSTATION_5"->"PLAYSTATION";case "SWITCH","NINTENDO_SWITCH"->"NINTENDO";default->key;};
    }
    void clearModel(UUID id){currentModels.remove(id);currentOs.remove(id);}
    String deviceBlockMessage(UUID id){return blockedOs.contains(currentOs.getOrDefault(id,""))?"This device operating system is not permitted on this server. Please contact server staff.":"This device model is not permitted on this server. Please contact server staff.";}
    boolean modelBlocked(UUID id){if(plugin.emergency(id))return false;String key=currentModels.get(id);return (key!=null&&blockedModels.contains(key))||blockedOs.contains(currentOs.getOrDefault(id,""));}
    void identityUpdated(Player player) {
        if(player==null)return;
        UUID id=player.getUniqueId(); setGhost(id,plugin.controlGhost(id)); recompute(player);
        if(ghost(player)&&player.isOnline())joinGhost(player);
    }
    void refreshOnline() {
        for(Player p:Bukkit.getOnlinePlayers()){
            boolean before=ghost(p);setGhost(p.getUniqueId(),plugin.controlGhost(p.getUniqueId()));recompute(p);
            if(ghost(p))joinGhost(p);else if(before||collisionBefore.containsKey(p.getUniqueId()))restoreGhost(p);
        }
    }
    private void recompute(Player p) {
        UUID id=p.getUniqueId(); Set<String> keys=plugin.controlIdentity(id);long now=System.currentTimeMillis();
        Set<String> all=new HashSet<>();StringBuilder reasons=new StringBuilder();long until=0;
        for(Rule r:rules.values()){
            if(r.until<=now)continue;
            boolean known=r.accounts.contains(id);
            if(!known&&(!r.inherit||Collections.disjoint(r.identities,keys)))continue;
            if(!known){r.accounts.add(id);r.identities.addAll(keys);dirty=true;}
            all.addAll(flags(r.preset));if(reasons.length()>0)reasons.append("; ");reasons.append(r.reason);until=Math.max(until,r.until);
        }
        if(all.isEmpty())effective.remove(id);else effective.put(id,new Effective(Set.copyOf(all),reasons.toString(),until));
    }
    private boolean restricted(Player p,String flag){if(p!=null&&plugin.emergency(p.getUniqueId()))return false;Effective e=p==null?null:effective.get(p.getUniqueId());return e!=null&&e.until>System.currentTimeMillis()&&e.flags.contains(flag);}
    private void notifyDenied(Player p) {
        long now=System.currentTimeMillis();Long previous=notices.put(p.getUniqueId(),now);
        if(previous!=null&&now-previous<2000L)return;
        Effective e=effective.get(p.getUniqueId());
        if(e!=null)p.sendMessage("§cThis action is restricted by server staff. Reason: "+e.reason+". Restrictions: "+String.join(", ",e.flags)+". "+(e.until==Long.MAX_VALUE?"Permanent.":"Time remaining: "+Math.max(1,(e.until-now+59999)/60000)+" minutes."));
    }
    private boolean blocked(Player p,String flag,String attempt){
        if(ghost(p)){audit(p,attempt,"");return true;}
        if(restricted(p,flag)){notifyDenied(p);return true;}return false;
    }
    boolean gateChat(Player p,String message) {
        if(ghost(p)){
            long now=System.currentTimeMillis();
            String previous=lastChat.put(p.getUniqueId(),message);Long at=lastChatAt.put(p.getUniqueId(),now);
            if(message.equals(previous)&&at!=null&&now-at<250L)return true;
            audit(p,"CHAT",message);
            plugin.runAdminTask(()->{if(p.isOnline())p.sendMessage(Component.text("<"+p.getName()+"> "+message));});
            return true;
        }
        if(restricted(p,"text")){notifyDenied(p);return true;}return false;
    }
    boolean gateCommand(Player p,String command) {
        if(ghost(p)){
            if(spawnCommand(command))return false;
            audit(p,"COMMAND",command);plugin.menuError(p,"§cAn internal error occurred while attempting to perform this command.");return true;
        }
        if(restricted(p,"text")){
            // A strict allowlist closes aliases, namespaced commands, plugin messaging and /execute bypasses.
            String label=command.substring(command.startsWith("/")?1:0).split("\\s+",2)[0].toLowerCase(Locale.ROOT);
            List<String> allowed=plugin.getConfig().getStringList("moderation-control.text.allowed-commands");
            if(label.indexOf(':')>=0||TEXT_COMMANDS.contains(label)||!allowed.contains(label)){notifyDenied(p);return true;}
        }
        return false;
    }
    void joinGhost(Player p) {
        if(!p.isOnline()||!ghost(p))return;
        if(plugin.adminRuntime!=null)plugin.adminRuntime.releaseGhost(p);
        p.closeInventory();
        collisionBefore.putIfAbsent(p.getUniqueId(),p.isCollidable());p.setCollidable(false);
        for(Player viewer:Bukkit.getOnlinePlayers())if(!viewer.equals(p))viewer.hidePlayer(plugin,p);
    }
    private void restoreGhost(Player p){
        Boolean prior=collisionBefore.remove(p.getUniqueId());if(prior!=null)p.setCollidable(prior);
        for(Player viewer:Bukkit.getOnlinePlayers())if(!viewer.equals(p))viewer.showPlayer(plugin,p);
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void join(PlayerJoinEvent e){
        identityUpdated(e.getPlayer());
        if(ghost(e.getPlayer()))e.setJoinMessage(null);
        for(Player hidden:Bukkit.getOnlinePlayers())if(ghost(hidden)&&!hidden.equals(e.getPlayer()))e.getPlayer().hidePlayer(plugin,hidden);
        if(!plugin.emergency(e.getPlayer().getUniqueId())&&effective.containsKey(e.getPlayer().getUniqueId())&&!ghost(e.getPlayer()))notifyDenied(e.getPlayer());
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void quit(PlayerQuitEvent e){
        if(ghost(e.getPlayer()))e.setQuitMessage(null);
        clearModel(e.getPlayer().getUniqueId());effective.remove(e.getPlayer().getUniqueId());notices.remove(e.getPlayer().getUniqueId());
        Boolean prior=collisionBefore.remove(e.getPlayer().getUniqueId());if(prior!=null)e.getPlayer().setCollidable(prior);
        // Ghost membership survives quit until next identity check, preventing late relay work from exposing the player.
    }
    @EventHandler(priority=EventPriority.LOWEST) public void paperChat(AsyncChatEvent e){
        if(ghost(e.getPlayer())||restricted(e.getPlayer(),"text")){
            e.setCancelled(true);
            gateChat(e.getPlayer(),PlainTextComponentSerializer.plainText().serialize(e.message()));
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST) public void finalChat(AsyncPlayerChatEvent e){
        if(ghost(e.getPlayer())||restricted(e.getPlayer(),"text")){e.setCancelled(true);return;}
        boolean colors=plugin.getConfig().getBoolean("moderation-control.chat.block-colors",true),formatting=plugin.getConfig().getBoolean("moderation-control.chat.block-formatting",true);
        if(colors||formatting)e.setMessage(SimplexAdminSuite.sanitizePlayerChatFormatting(e.getMessage(),colors,formatting,plugin.isBedrockClient(e.getPlayer())));
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void finalPaperChat(AsyncChatEvent e){
        if(ghost(e.getPlayer())||restricted(e.getPlayer(),"text")){e.setCancelled(true);return;}
        boolean colors=plugin.getConfig().getBoolean("moderation-control.chat.block-colors",true);
        boolean formatting=plugin.getConfig().getBoolean("moderation-control.chat.block-formatting",true);
        if(colors||formatting){
            Component cleaned=cleanComponent(e.message(),colors,formatting);
            String plain=PlainTextComponentSerializer.plainText().serialize(cleaned);
            String text=SimplexAdminSuite.sanitizePlayerChatFormatting(plain,colors,formatting,plugin.isBedrockClient(e.getPlayer()));
            if(!plain.equals(text))cleaned=Component.text(text);
            if(text.isBlank()){e.setCancelled(true);e.getPlayer().sendMessage(Component.text("Your message contained only disabled chat formatting."));return;}
            e.message(cleaned);
        }
    }
    static Component cleanComponent(Component input,boolean colors,boolean formatting) {
        Component out=input;
        if(colors)out=out.color(null);
        if(formatting){
            for(net.kyori.adventure.text.format.TextDecoration d:net.kyori.adventure.text.format.TextDecoration.values())out=out.decoration(d,net.kyori.adventure.text.format.TextDecoration.State.NOT_SET);
            out=out.clickEvent(null).hoverEvent(null).insertion(null).font(null);
        }
        List<Component> children=new ArrayList<>();for(Component child:out.children())children.add(cleanComponent(child,colors,formatting));return out.children(children);
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void commandGate(PlayerCommandPreprocessEvent e){if(ghost(e.getPlayer())&&!spawnCommand(e.getMessage()))e.setCancelled(true);else if(restricted(e.getPlayer(),"text")&&gateCommand(e.getPlayer(),e.getMessage()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void breakBlock(BlockBreakEvent e){if(blocked(e.getPlayer(),"building","BREAK"))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void place(BlockPlaceEvent e){if(blocked(e.getPlayer(),"building","PLACE"))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void interact(PlayerInteractEvent e){if(blocked(e.getPlayer(),"interactions","INTERACT"))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void interactEntity(PlayerInteractEntityEvent e){if(blocked(e.getPlayer(),"interactions","ENTITY_INTERACT")||e.getRightClicked() instanceof Player p&&ghost(p))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void interactAt(PlayerInteractAtEntityEvent e){if(blocked(e.getPlayer(),"interactions","ENTITY_INTERACT")||e.getRightClicked() instanceof Player p&&ghost(p))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void sign(SignChangeEvent e){if(blocked(e.getPlayer(),"text","SIGN"))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void book(PlayerEditBookEvent e){if(blocked(e.getPlayer(),"text","BOOK"))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void anvil(PrepareAnvilEvent e){if(e.getView().getPlayer() instanceof Player p&&(ghost(p)||restricted(p,"text")))e.setResult(null);}
    @EventHandler(priority=EventPriority.HIGHEST) public void click(InventoryClickEvent e){if(e.getWhoClicked() instanceof Player p&&blocked(p,"inventory","INVENTORY_CLICK"))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void drag(InventoryDragEvent e){if(e.getWhoClicked() instanceof Player p&&blocked(p,"inventory","INVENTORY_DRAG"))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void openInventory(InventoryOpenEvent e){if(e.getPlayer() instanceof Player p&&blocked(p,"inventory","INVENTORY_OPEN"))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void drop(PlayerDropItemEvent e){if(blocked(e.getPlayer(),"inventory","DROP"))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void pickup(EntityPickupItemEvent e){if(e.getEntity() instanceof Player p&&blocked(p,"inventory","PICKUP"))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void empty(PlayerBucketEmptyEvent e){if(blocked(e.getPlayer(),"building","BUCKET"))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void fill(PlayerBucketFillEvent e){if(blocked(e.getPlayer(),"building","BUCKET"))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void fish(PlayerFishEvent e){if(blocked(e.getPlayer(),"interactions","FISH"))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void consume(PlayerItemConsumeEvent e){if(ghost(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void shoot(ProjectileLaunchEvent e){if(e.getEntity().getShooter() instanceof Player p&&ghost(p))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void damage(EntityDamageByEntityEvent e){
        Player source=e.getDamager() instanceof Player p?p:e.getDamager() instanceof Projectile projectile&&projectile.getShooter() instanceof Player p?p:null;
        if(source==null&&e.getDamager() instanceof TNTPrimed tnt&&tnt.getSource() instanceof Player p)source=p;
        if(source==null&&e.getDamager() instanceof AreaEffectCloud cloud&&cloud.getSource() instanceof Player p)source=p;
        if(source!=null&&(ghost(source)||restricted(source,"peaceful")&&!(e.getEntity() instanceof Enemy))) {
            e.setCancelled(true);if(!ghost(source))notifyDenied(source);else audit(source,"DAMAGE",e.getEntity().getType().name());
        }
        if(e.getEntity() instanceof Player p&&ghost(p))e.setCancelled(true);
        // Tamed companions must not bypass the peaceful preset.
        if(e.getDamager() instanceof Tameable pet&&pet.getOwner() instanceof Player p&&(ghost(p)||restricted(p,"peaceful")&&!(e.getEntity() instanceof Enemy)))e.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void target(EntityTargetLivingEntityEvent e){if(e.getTarget() instanceof Player p&&ghost(p))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void entityDamage(EntityDamageEvent e){if(e.getEntity() instanceof Player p&&ghost(p))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void vehicle(VehicleEnterEvent e){if(e.getEntered() instanceof Player p&&blocked(p,"interactions","VEHICLE"))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void bed(PlayerBedEnterEvent e){if(blocked(e.getPlayer(),"interactions","BED"))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void armor(PlayerArmorStandManipulateEvent e){if(blocked(e.getPlayer(),"interactions","ARMOR_STAND"))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void swap(PlayerSwapHandItemsEvent e){if(blocked(e.getPlayer(),"inventory","SWAP"))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void mount(EntityMountEvent e){if(e.getEntity() instanceof Player p&&blocked(p,"interactions","MOUNT"))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void xp(com.destroystokyo.paper.event.player.PlayerPickupExperienceEvent e){if(ghost(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void signOpen(PlayerSignOpenEvent e){if(blocked(e.getPlayer(),"text","SIGN_OPEN"))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void blockDamage(BlockDamageEvent e){if(blocked(e.getPlayer(),"building","BLOCK_DAMAGE"))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void gameEvent(org.bukkit.event.world.GenericGameEvent e){if(e.getEntity() instanceof Player p&&ghost(p))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void advancement(PlayerAdvancementDoneEvent e){if(ghost(e.getPlayer()))e.message(null);}
    @EventHandler(priority=EventPriority.HIGHEST) public void splash(PotionSplashEvent e){
        if(e.getPotion().getShooter() instanceof Player p){
            if(ghost(p))e.setCancelled(true);
            else if(restricted(p,"peaceful"))for(LivingEntity target:e.getAffectedEntities())if(!(target instanceof Enemy))e.setIntensity(target,0);
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void lingering(LingeringPotionSplashEvent e){if(e.getEntity().getShooter() instanceof Player p&&(ghost(p)||restricted(p,"peaceful")))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void bow(EntityShootBowEvent e){if(e.getEntity() instanceof Player p&&ghost(p))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void physical(EntityInteractEvent e){if(e.getEntity() instanceof Player p&&ghost(p))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void teleport(PlayerTeleportEvent e){if(ghost(e.getPlayer())&&e.getCause()!=PlayerTeleportEvent.TeleportCause.COMMAND&&e.getCause()!=PlayerTeleportEvent.TeleportCause.PLUGIN)e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void tab(PlayerCommandSendEvent e){if(ghost(e.getPlayer())){e.getCommands().clear();e.getCommands().add("spawn");}else if(restricted(e.getPlayer(),"text"))e.getCommands().retainAll(plugin.getConfig().getStringList("moderation-control.text.allowed-commands"));}

    private void audit(Player p,String action,String content) {
        String safe=action.equals("COMMAND")&&content.toLowerCase(Locale.ROOT).matches(".*(?:login|password|register|token|secret|api-key).*")?"[sensitive command redacted]":clean(content,1800), key=p.getUniqueId()+"|"+action;long now=System.currentTimeMillis();
        Long previous=logTimes.put(key,now);
        if(!Set.of("CHAT","COMMAND").contains(action)&&previous!=null&&now-previous<500L)return;
        String line=java.time.Instant.ofEpochMilli(now)+" | "+p.getName()+" | "+p.getUniqueId()+" | "+action+" | "+safe;
        synchronized(pendingLogs){if(pendingLogs.size()<2000)pendingLogs.addLast(line);}
        DiscordBotManager bot=plugin.discordRelay();if(bot!=null)bot.relayModerationLog("ghost",p.getName(),p.getUniqueId().toString(),action,safe,now);
    }
    private static String clean(String value,int max){if(value==null)return "";String s=value.replaceAll("[\\p{Cntrl}\\p{Cf}]"," ");return s.substring(0,Math.min(max,s.length()));}
    private void flushLogs(){if(!flushing.compareAndSet(false,true))return;Bukkit.getScheduler().runTaskAsynchronously(plugin,()->{try{flushLogsSync();}finally{flushing.set(false);}});}
    private synchronized void flushLogsSync(){
        List<String> batch=new ArrayList<>();synchronized(pendingLogs){while(!pendingLogs.isEmpty())batch.add(pendingLogs.removeFirst());}if(batch.isEmpty())return;
        try{Files.createDirectories(logFile.getParent());if(Files.exists(logFile)&&Files.size(logFile)>10*1024*1024)Files.move(logFile,logFile.resolveSibling("ghost-audit.previous.log"),StandardCopyOption.REPLACE_EXISTING);
            Files.write(logFile,batch,java.nio.charset.StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        }catch(IOException error){plugin.getLogger().warning("Ghost-Protokoll konnte nicht gespeichert werden: "+error.getMessage());}
    }
    private void load(){
        if(!Files.exists(file))return;Properties state=new Properties();
        try(Reader in=Files.newBufferedReader(file)){state.load(in);}catch(IOException error){throw new IllegalStateException("Moderationsdaten konnten nicht geladen werden",error);}
        for(String k:state.stringPropertyNames()){
            if(k.startsWith("os."))knownOs.put(k.substring(3),state.getProperty(k));
            if(k.startsWith("osblocked."))blockedOs.add(k.substring(10));
            if(k.startsWith("model."))knownModels.put(k.substring(6),state.getProperty(k));
            if(k.startsWith("blocked."))blockedModels.add(k.substring(8));
        }
        for(String id:state.getProperty("rules","").split(",")){
            if(id.isBlank())continue;
            try{UUID source=UUID.fromString(id);String b="rule."+id+".";
                String preset=state.getProperty(b+"preset");if(!PRESETS.contains(preset)||preset.equals("none"))throw new IllegalArgumentException("unknown preset");
                Rule r=new Rule(source,preset,state.getProperty(b+"reason","Staff restriction"),state.getProperty(b+"actor","Console"),Long.parseLong(state.getProperty(b+"until")),Boolean.parseBoolean(state.getProperty(b+"inherit")));
                for(String account:state.getProperty(b+"accounts",id).split(","))r.accounts.add(UUID.fromString(account));
                for(String identity:state.getProperty(b+"identities","").split(","))if(!identity.isBlank())r.identities.add(identity);
                rules.put(source,r);
            }catch(RuntimeException error){throw new IllegalStateException("Beschädigte Einschränkung für "+id,error);}
        }
    }
    private synchronized void save(){
        Properties state=new Properties();state.setProperty("schema","2");
        knownOs.forEach((k,v)->state.setProperty("os."+k,v));blockedOs.forEach(k->state.setProperty("osblocked."+k,"true"));
        knownModels.forEach((k,v)->state.setProperty("model."+k,v));blockedModels.forEach(k->state.setProperty("blocked."+k,"true"));
        state.setProperty("rules",String.join(",",rules.keySet().stream().map(UUID::toString).sorted().toList()));
        for(Rule r:rules.values()){
            String b="rule."+r.source+".";state.setProperty(b+"preset",r.preset);state.setProperty(b+"reason",r.reason);state.setProperty(b+"actor",r.actor);state.setProperty(b+"until",Long.toString(r.until));state.setProperty(b+"inherit",Boolean.toString(r.inherit));
            state.setProperty(b+"accounts",String.join(",",r.accounts.stream().map(UUID::toString).sorted().toList()));state.setProperty(b+"identities",String.join(",",new TreeSet<>(r.identities)));
        }
        try{AtomicState.save(file,state,"Simplex moderation 7.10");dirty=false;}catch(IOException error){plugin.getLogger().severe("Moderationsdaten konnten nicht gespeichert werden: "+error.getMessage());dirty=true;}
    }
    private boolean allowed(CommandSender sender,String node){
        if(sender instanceof Player p&&(!plugin.adminSessionUsable(p)||ghost(p)))return false;
        return plugin.has(sender,"simplexadminsuite."+node);
    }
    boolean command(CommandSender sender,String[] args){
        if(args.length==0||!ROOTS.contains(args[0].toLowerCase(Locale.ROOT)))return false;
        String root=args[0].toLowerCase(Locale.ROOT);
        String permission=switch(root){case "banmode"->"bans";case "restrict"->"restrictions";case "deviceblock","osblock"->"devices.models";case "suiteconfig"->"settings.advanced";default->"moderation.control";};
        if(!allowed(sender,permission)){sender.sendMessage("§cBerechtigung oder aktive Admin-Sitzung fehlt: simplexadminsuite."+permission);return true;}
        try{
            switch(root){
                case "moderationplus"->{if(sender instanceof Player p)open(p);else sender.sendMessage(summary());}
                case "banmode"->{
                    if(args.length<3)throw new IllegalArgumentException("/sas banmode <Spieler> <account|ultra|silent|ghost|none> [Grund]");
                    String mode=args[2].toLowerCase(Locale.ROOT);if(!MODES.contains(mode))throw new IllegalArgumentException("Unbekannte Bannart.");
                    sender.sendMessage(plugin.controlBan(sender,args[1],mode,join(args,3,"Violation of server rules")));
                }
                case "restrict"->{
                    if(args.length<3)throw new IllegalArgumentException("/sas restrict <Spieler> <text|peaceful|text-peaceful|interactions|building|inventory|none> [1h|permanent] [true|false] [Grund]");
                    sender.sendMessage(applyRule(sender,args[1],args[2],args.length>3?args[3]:"permanent",args.length>4?parseBoolean(args[4]):false,join(args,5,"Violation of server rules")));
                }
                case "deviceblock"->{
                    if(args.length<2||args[1].equalsIgnoreCase("list")){sender.sendMessage("Gerätemodelle: "+String.join(", ",knownModels.values().stream().sorted().map(m->m+(blockedModels.contains(modelKey(m))?" [GESPERRT]":"")).toList()));break;}
                    if(args.length>2&&args[1].equals("info")){sender.sendMessage(deviceSummary(join(args,2,""),false));break;}
                    if(args.length<3||!Set.of("block","unblock").contains(args[1]))throw new IllegalArgumentException("/sas deviceblock <list|block|unblock> [vollständiges Modell]");
                    String model=join(args,2,"");setModel(sender,model,args[1].equals("block"));sender.sendMessage("Gerätemodell "+model+": "+(args[1].equals("block")?"gesperrt":"freigegeben"));
                }
                case "osblock"->{
                    if(args.length<2||args[1].equals("list")){sender.sendMessage("Betriebssysteme: "+String.join(", ",knownOs.keySet().stream().sorted().map(os->os+(blockedOs.contains(os)?" [GESPERRT]":"")).toList()));break;}
                    if(args.length>2&&args[1].equals("info")){sender.sendMessage(deviceSummary(join(args,2,""),true));break;}
                    if(args.length<3||!Set.of("block","unblock").contains(args[1]))throw new IllegalArgumentException("/sas osblock <list|block|unblock> [Betriebssystem]");
                    setOs(sender,join(args,2,""),args[1].equals("block"));plugin.menuNotice(sender,"Betriebssystem-Regel gespeichert.");
                }
                case "adminlog"->{sender.sendMessage(auditSummary(args.length>1?args[1]:"all"));}
                case "chatstyle"->{
                    if(args.length<3||!Set.of("colors","formatting").contains(args[1]))throw new IllegalArgumentException("/sas chatstyle <colors|formatting> <on|off> (on = erlauben)");
                    boolean allow=parseBoolean(args[2]);plugin.getConfig().set("moderation-control.chat.block-"+args[1],!allow);plugin.saveConfig();plugin.suiteAudit(sender.getName(),"Globales Chatformat",args[1]+" → "+allow);sender.sendMessage("Globale Chat-"+args[1]+": "+(allow?"erlaubt":"gesperrt"));
                }
                case "modlog"->{
                    if(args.length<3||!LOG_TYPES.contains(args[1]))throw new IllegalArgumentException("/sas modlog <admin|punishments|bans|mutes|vanish|devices|ai|ghost|client-probes|alt-accounts|join-details> <Kanal-ID|off|create>");
                    if(args[2].equals("create")){sender.sendMessage(plugin.discordRelay()==null?"Bot nicht geladen":plugin.discordRelay().createModerationChannel(args[1],sender.getName()));break;}
                    if(!args[2].equals("off")&&!args[2].matches("[0-9]{15,22}"))throw new IllegalArgumentException("Discord-Kanal-ID erforderlich.");
                    plugin.getConfig().set("discord-bot.moderation-log."+args[1]+"-channel-id",args[2].equals("off")?"":args[2]);plugin.saveConfig();plugin.suiteAudit(sender.getName(),"Protokollkanal geändert",args[1]+" → "+args[2]);plugin.menuNotice(sender,"Protokollkanal gespeichert.");
                }
                case "suiteconfig"->{
                    if(args.length<3)throw new IllegalArgumentException("/sas suiteconfig <get|set> <Konfigurationspfad> [Wert]");
                    String key=args[2];Object current=configValue(key);
                    if(args[1].equals("get"))sender.sendMessage(key+" = "+current);
                    else if(args[1].equals("set")&&args.length>3){configSet(sender,key,join(args,3,""));plugin.menuNotice(sender,"Gespeichert: "+key+". Moduländerungen bei Bedarf mit /sas reload anwenden.");}
                    else throw new IllegalArgumentException("get oder set mit Wert verwenden.");
                }
            }

        }catch(IllegalArgumentException error){plugin.menuError(sender,"§c"+error.getMessage());}
        return true;
    }
    private static boolean parseBoolean(String input){if(Set.of("true","on","an").contains(input.toLowerCase(Locale.ROOT)))return true;if(Set.of("false","off","aus").contains(input.toLowerCase(Locale.ROOT)))return false;throw new IllegalArgumentException("on/off oder true/false erwartet.");}
    private static String join(String[] args,int from,String fallback){return from>=args.length?fallback:String.join(" ",Arrays.copyOfRange(args,from,args.length));}
    private String applyRule(CommandSender sender,String name,String preset,String duration,boolean inherit,String reason){
        if(!allowed(sender,"restrictions"))return "Berechtigung fehlt.";
        UUID id=plugin.controlResolve(name);if(id==null)throw new IllegalArgumentException("Gespeichertes Profil nicht gefunden.");
        preset=preset.toLowerCase(Locale.ROOT);if(!PRESETS.contains(preset))throw new IllegalArgumentException("Unbekannte Einschränkungsvorlage.");
        if(preset.equals("none")){
            rules.values().removeIf(r->r.source.equals(id)||r.accounts.contains(id));
        }else{
            long until=Long.MAX_VALUE;
            if(!duration.equalsIgnoreCase("permanent")){
                SimplexAdminSuite.DurationValue parsed=SimplexAdminSuite.DurationValue.parse(duration);if(parsed==null)throw new IllegalArgumentException("Ungültige Dauer; z. B. 30m, 2h, 7d oder permanent.");
                if(parsed.toMillis()>Long.MAX_VALUE-System.currentTimeMillis())throw new IllegalArgumentException("Dauer zu groß.");until=System.currentTimeMillis()+parsed.toMillis();
            }
            Rule r=new Rule(id,preset,clean(reason,240),sender.getName(),until,inherit);
            if(inherit){r.accounts.addAll(plugin.controlLinked(id));for(UUID account:r.accounts)r.identities.addAll(plugin.controlIdentity(account));}
            rules.put(id,r);
        }
        dirty=true;save();refreshOnline();
        for(Player p:Bukkit.getOnlinePlayers())if(effective.containsKey(p.getUniqueId())){notices.remove(p.getUniqueId());notifyDenied(p);}
        plugin.suiteAudit(sender.getName(),"Einschränkungsvorlage",name+" • "+preset+" • "+duration+" • Vererbung="+inherit);
        return preset.equals("none")?"Zugehörige Einschränkungsgruppe aufgehoben.":"Vorlage "+preset+" für "+name+" gespeichert. Dauer: "+duration+". Alt-Vererbung: "+inherit;
    }
    private void setModel(CommandSender sender,String model,boolean block){
        if(!allowed(sender,"devices.models"))throw new IllegalArgumentException("Geräteberechtigung fehlt.");
        String key=modelKey(model);if(key.isBlank()||key.length()>160)throw new IllegalArgumentException("Gerätemodell muss 1–160 Zeichen lang sein.");
        observeModel(null,model);if(block)blockedModels.add(key);else blockedModels.remove(key);dirty=true;save();
        plugin.suiteAudit(sender.getName(),"Gerätemodell",model+" • gesperrt="+block);
        if(block)for(Player p:Bukkit.getOnlinePlayers())if(modelBlocked(p.getUniqueId()))plugin.suiteKick(p,"This device model is not permitted on this server. Please contact server staff.");
    }
    List<DiscordAdminController.Choice> osChoices(String query){return knownOs.keySet().stream().filter(os->os.contains(osKey(query))).sorted().limit(25).map(os->new DiscordAdminController.Choice(os,os)).toList();}
    List<DiscordAdminController.Choice> modelChoices(String query) {
        return knownModels.values().stream().filter(m -> m.length() <= 100 && modelKey(m).contains(modelKey(query))).sorted().limit(25).map(m -> new DiscordAdminController.Choice(m,m)).toList();
    }
    List<String> restrictionSummary() {
        return rules.values().stream().map(r -> plugin.discordAdminPlayerName(r.source)+" • Vorlage "+r.preset+" • Konten "+r.accounts.size()+" • "+(r.until==Long.MAX_VALUE?"dauerhaft":"<t:"+(r.until/1000)+":R>")).toList();
    }
    String summary(){return "Moderation: "+ghosts.size()+" Ghost-Mitgliedschaften, "+rules.size()+" Einschränkungsgruppen, "+blockedModels.size()+" gesperrte Modelle / "+knownModels.size()+" bekannte Modelle, "+blockedOs.size()+" gesperrte Betriebssysteme.";}
    List<String> complete(CommandSender sender,String[] args){
        if(args.length<2||!ROOTS.contains(args[0].toLowerCase(Locale.ROOT)))return null;
        if(!allowed(sender,"moderation.control"))return List.of();
        List<String> values=new ArrayList<>();String root=args[0].toLowerCase(Locale.ROOT),typed=args[args.length-1].toLowerCase(Locale.ROOT);
        if(args.length==2){switch(root){case "banmode","restrict"->values.addAll(plugin.controlNames());case "deviceblock","osblock"->values.addAll(List.of("list","info","block","unblock"));case "chatstyle"->values.addAll(List.of("colors","formatting"));case "modlog"->values.addAll(LOG_TYPES);case "adminlog"->values.addAll(List.of("all","admin","punishments","vanish","devices"));case "suiteconfig"->values.addAll(List.of("get","set"));}}
        else if(root.equals("banmode")&&args.length==3)values.addAll(MODES);
        else if(root.equals("restrict")){if(args.length==3)values.addAll(PRESETS);else if(args.length==4)values.addAll(List.of("30m","1h","1d","7d","permanent"));else if(args.length==5)values.addAll(List.of("true","false"));}
        else if(root.equals("osblock")&&args.length>=3)values.addAll(knownOs.keySet().stream().map(String::toLowerCase).toList());
        else if(root.equals("chatstyle")&&args.length==3)values.addAll(List.of("on","off"));
        else if(root.equals("suiteconfig")&&args.length==3&&allowed(sender,"settings.advanced"))values.addAll(configKeys());
        else if(root.equals("deviceblock")&&args.length>=3){
            String search=join(args,2,"").toLowerCase(Locale.ROOT);String prefix=args.length>3?String.join(" ",Arrays.copyOfRange(args,2,args.length-1))+" ":"";
            for(String model:knownModels.values())if(model.toLowerCase(Locale.ROOT).startsWith(search))values.add(model.substring(Math.min(prefix.length(),model.length())));
        }
        return values.stream().filter(v->v.toLowerCase(Locale.ROOT).startsWith(typed)).sorted().limit(80).toList();
    }
    private AdminUpdateMenu.Button button(String title,String detail,Material icon,Runnable action){return new AdminUpdateMenu.Button(title,detail,icon,action);}
    private void show(Player p,String title,List<AdminUpdateMenu.Button> items){
        if(!plugin.adminSessionUsable(p))return;
        plugin.adminUpdate.show(p,title,items,0);
    }
    void open(Player p){
        if(!allowed(p,"moderation.control"))return;
        List<AdminUpdateMenu.Button> items=new ArrayList<>();
        if(allowed(p,"bans"))items.add(button("Bannarten & Gruppen","Account • Ultra • leiser Beitrittsbann • Ghost",Material.IRON_BARS,()->players(p,"",false)));
        if(allowed(p,"restrictions"))items.add(button("Einschränkungen","Text • friedliche Ziele • Bau • Inventar • Ablauf & Vererbung",Material.WRITABLE_BOOK,()->players(p,"",true)));
        if(allowed(p,"devices.models"))items.add(button("Bedrock-Gerätemodelle","Bekannte Modelle durchsuchen, sperren und freigeben",Material.COMPARATOR,()->devices(p,"")));
        items.add(button("Chat-Farben: "+(plugin.getConfig().getBoolean("moderation-control.chat.block-colors",true)?"GESPERRT":"ERLAUBT"),"Global für Nachrichtentext; Rangdarstellung bleibt farbig",Material.ORANGE_DYE,()->toggleChat(p,"colors")));
        items.add(button("Chat-Formatierung: "+(plugin.getConfig().getBoolean("moderation-control.chat.block-formatting",true)?"GESPERRT":"ERLAUBT"),"Fett, kursiv, Effekte und interaktive Tags im Nachrichtentext",Material.PAPER,()->toggleChat(p,"formatting")));
        items.add(button("Discord-Protokolle & Botprofil","KI-Blockierungen • Ghost-Aktionen • Bio • Informationen",Material.REPEATER,()->discord(p)));
        if(allowed(p,"settings.advanced"))items.add(button("Alle Suite-Einstellungen","Suche nach Konfigurationspfad • geschützte Geheimnisse",Material.COMMAND_BLOCK,()->settings(p,"")));
        items.add(button("Zurück","Admin-Zentrale",Material.OAK_DOOR,()->plugin.unifiedMenu.open(p)));
        show(p,"Moderationszentrale",items);
    }
    void banTarget(Player p,UUID id){plugin.controlProfiles().stream().filter(profile->profile.id().equals(id)).findFirst().ifPresent(profile->banPlayer(p,profile));}
    void restrictionTarget(Player p,UUID id){plugin.controlProfiles().stream().filter(profile->profile.id().equals(id)).findFirst().ifPresent(profile->restrictionPlayer(p,profile));}
    void chatMenu(Player p){
        if(!allowed(p,"moderation.control"))return;
        List<AdminUpdateMenu.Button> items=new ArrayList<>();
        for(String kind:List.of("colors","formatting"))items.add(button((kind.equals("colors")?"Farben":"Formatierungen")+": "+(plugin.getConfig().getBoolean("moderation-control.chat.block-"+kind,true)?"GESPERRT":"ERLAUBT"),"Nachrichtentext global umschalten; Ränge bleiben farbig",Material.PAPER,()->toggleChat(p,kind)));
        items.add(button("Zurück","Server-Steuerung",Material.OAK_DOOR,()->plugin.openUnifiedAction(p,15)));show(p,"Globale Chat-Formatierung",items);
    }
    private void toggleChat(Player p,String kind){if(!allowed(p,"moderation.control"))return;String key="moderation-control.chat.block-"+kind;plugin.getConfig().set(key,!plugin.getConfig().getBoolean(key,true));plugin.saveConfig();plugin.suiteAudit(p.getName(),"Globales Chatformat",kind+" • gesperrt="+plugin.getConfig().getBoolean(key));chatMenu(p);}
    void players(Player p,String filter,boolean restriction){
        if(!allowed(p,restriction?"restrictions":"bans"))return;
        List<AdminUpdateMenu.Button> items=new ArrayList<>();
        items.add(button("Spieler suchen",filter.isBlank()?"Name, Bannart oder Gruppe":filter,Material.SPYGLASS,()->plugin.adminUpdate.prompt(p,"Spielersuche (leer = alle)",filter,q->players(p,q,restriction))));
        for(SimplexAdminSuite.ControlProfile profile:plugin.controlProfiles()){
            Rule r=rules.values().stream().filter(rule->rule.accounts.contains(profile.id())).findFirst().orElse(null);
            String state=profile.banMode()+" • Gruppe "+profile.group()+(r==null?"":" • Einschränkung "+r.preset+" • "+(r.until==Long.MAX_VALUE?"dauerhaft":java.time.Instant.ofEpochMilli(r.until)));
            if(!(profile.name()+" "+state).toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT)))continue;
            items.add(button(profile.name(),state,Material.PLAYER_HEAD,()->{if(restriction)restrictionPlayer(p,profile);else banPlayer(p,profile);}));
        }
        items.add(button("Zurück","Admin-Verwaltung",Material.OAK_DOOR,()->plugin.unifiedMenu.category(p,"Server")));show(p,restriction?"Einschränkungen: Spieler":"Bannverwaltung: Spieler",items);
    }
    private void banPlayer(Player p,SimplexAdminSuite.ControlProfile profile){
        if(!allowed(p,"bans"))return;
        List<AdminUpdateMenu.Button> items=new ArrayList<>();
        items.add(AdminUpdateMenu.info("Aktuell: "+profile.banMode(),"Gruppe: "+profile.group()+" • Grund: "+profile.reason(),Material.MAP));
        for(String mode:List.of("account","ultra","silent","ghost","none")){
            String title=switch(mode){case "account"->"Klassischer Account-Bann";case "ultra"->"Ultra-Bann • sichtbarer Grund";case "silent"->"Ultra-Bann • leise Fehlermeldung";case "ghost"->"Ghost-Bann • verborgen & passiv";default->"Gruppe / internen Account entbannen";};
            String detail=switch(mode){case "account"->"Nur dieses Konto; dauerhaft";case "ghost"->"Verknüpfte Konten • nur laufen und /spawn • Chat unsichtbar";case "none"->"Gesamte zugehörige Ultra-/Ghost-Gruppe deaktivieren";default->"Bestehende und zukünftige erkennbare Konten • neue IPs nachführen";};
            items.add(button(title,detail,switch(mode){case "none"->Material.LIME_DYE;case "ghost"->Material.ENDER_EYE;case "silent"->Material.ENDER_PEARL;case "ultra"->Material.NETHERITE_AXE;default->Material.IRON_BARS;},()->{
                if(mode.equals("none"))confirm(p,"Entbannung",profile.name()+" • gesamte Ultra-/Ghost-Gruppe",()->{p.sendMessage(plugin.controlBan(p,profile.name(),mode,""));players(p,"",false);});
                else plugin.adminUpdate.prompt(p,"Englischer Grund",profile.reason().isBlank()?"Violation of server rules":profile.reason(),reason->confirm(p,title,profile.name()+" • "+reason,()->{p.sendMessage(plugin.controlBan(p,profile.name(),mode,reason));players(p,"",false);}));
            }));
        }
        items.add(button("Ghost-Protokoll lesen","Letzte lokale Aktionen und Chatversuche",Material.BOOK,()->ghostLog(p,profile)));
        items.add(button("Zurück","Spielerauswahl",Material.OAK_DOOR,()->players(p,"",false)));show(p,"Bann: "+profile.name(),items);
    }
    private void ghostLog(Player p,SimplexAdminSuite.ControlProfile profile){
        if(!allowed(p,"bans"))return;List<AdminUpdateMenu.Button> items=new ArrayList<>();Deque<String> tail=new ArrayDeque<>();
        try{if(Files.exists(logFile))try(var lines=Files.lines(logFile)){lines.filter(line->line.contains(profile.id().toString())).forEach(line->{tail.addLast(line);if(tail.size()>60)tail.removeFirst();});}}
        catch(IOException error){plugin.menuNotice(p,"§cProtokoll konnte nicht gelesen werden.");}
        List<String> reverse=new ArrayList<>(tail);Collections.reverse(reverse);
        for(String line:reverse)items.add(button(line.substring(0,Math.min(70,line.length())),line,Material.PAPER,()->p.sendMessage(line)));
        if(items.isEmpty())items.add(AdminUpdateMenu.info("Keine Einträge","Aktionen werden auch im konfigurierten Discord-Kanal protokolliert",Material.PAPER));
        items.add(button("Zurück",profile.name(),Material.OAK_DOOR,()->banPlayer(p,profile)));show(p,"Ghost-Protokoll",items);
    }
    private void restrictionPlayer(Player p,SimplexAdminSuite.ControlProfile profile){
        if(!allowed(p,"restrictions"))return;
        List<AdminUpdateMenu.Button> items=new ArrayList<>();
        for(String preset:List.of("text","peaceful","text-peaceful","interactions","building","inventory","none")){
            String title=switch(preset){case "text"->"Text vollständig sperren";case "peaceful"->"PvP & friedliche Ziele sperren";case "text-peaceful"->"Text + PvP/friedliche Ziele sperren";case "interactions"->"Interaktionen sperren";case "building"->"Bauen & Abbauen sperren";case "inventory"->"Inventar & Items sperren";default->"Einschränkungsgruppe aufheben";};
            items.add(button(title,preset.equals("text")?"Chat, Schilder, Bücher, Umbenennen, private Nachrichten und Textbefehle":"Dauerhaft oder mit Ablauf • Alt-Vererbung optional",switch(preset){case "text"->Material.WRITABLE_BOOK;case "peaceful"->Material.IRON_SWORD;case "building"->Material.BRICKS;case "inventory"->Material.CHEST;case "interactions"->Material.LEVER;case "none"->Material.LIME_DYE;default->Material.SHIELD;},()->{
                if(preset.equals("none"))confirm(p,"Einschränkungen aufheben",profile.name()+" • gesamte zugehörige Gruppe",()->{p.sendMessage(applyRule(p,profile.name(),preset,"permanent",false,""));players(p,"",true);});
                else plugin.adminUpdate.prompt(p,"Dauer (30m, 2h, 7d oder permanent)","1h",duration->inheritChoice(p,profile,preset,duration));
            }));
        }
        items.add(button("Zurück","Spielerauswahl",Material.OAK_DOOR,()->players(p,"",true)));show(p,"Vorlagen: "+profile.name(),items);
    }
    private void inheritChoice(Player p,SimplexAdminSuite.ControlProfile profile,String preset,String duration){
        List<AdminUpdateMenu.Button> items=new ArrayList<>();
        for(boolean inherit:List.of(false,true))items.add(button(inherit?"Erkennbare Alt-Konten einbeziehen":"Nur dieses Konto",inherit?"Bestehende Verknüpfungen + zukünftige starke Identitätstreffer; keine reine IP-Vererbung":"Keine Weitergabe an andere Konten",Material.PLAYER_HEAD,()->plugin.adminUpdate.prompt(p,"Englischer Einschränkungsgrund","Violation of server rules",reason->confirm(p,"Vorlage anwenden",profile.name()+" • "+preset+" • "+duration+" • Vererbung="+inherit,()->{
            try{p.sendMessage(applyRule(p,profile.name(),preset,duration,inherit,reason));players(p,"",true);}catch(IllegalArgumentException error){plugin.menuError(p,"§c"+error.getMessage());restrictionPlayer(p,profile);}
        }))));
        items.add(button("Zurück","Vorlagen",Material.OAK_DOOR,()->restrictionPlayer(p,profile)));show(p,"Alt-Vererbung",items);
    }
    private void confirm(Player p,String title,String detail,Runnable action){
        show(p,title,List.of(button("Bestätigen",detail,Material.LIME_DYE,action),button("Abbrechen","Ohne Änderung zurück",Material.RED_DYE,()->plugin.unifiedMenu.open(p))));
    }
    void devices(Player p,String filter){
        if(!allowed(p,"devices.models"))return;List<AdminUpdateMenu.Button> items=new ArrayList<>();
        items.add(button("Modell suchen",filter.isBlank()?"Alle bisher erkannten Modelle":filter,Material.SPYGLASS,()->plugin.adminUpdate.prompt(p,"Gerätemodell suchen",filter,q->devices(p,q))));
        items.add(button("Modell manuell hinzufügen","Exakten vom Client gemeldeten Modellnamen eingeben",Material.PAPER,()->plugin.adminUpdate.prompt(p,"Vollständiger Modellname","",q->confirm(p,"Gerätemodell sperren",q,()->{setModel(p,q,true);devices(p,filter);} ))));
        items.add(button("Betriebssysteme verwalten","Windows • iOS • Android • Konsolen • standardmäßig erlaubt",Material.COMPARATOR,()->operatingSystems(p)));
        var deviceSnapshot=plugin.controlDevices();
        Map<String,Set<UUID>> modelPlayers=new HashMap<>();for(var d:deviceSnapshot)modelPlayers.computeIfAbsent(modelKey(d.model()),k->new HashSet<>()).add(d.playerId());
        for(String model:knownModels.values().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList()){
            if(!modelKey(model).contains(modelKey(filter)))continue;
            long count=modelPlayers.getOrDefault(modelKey(model),Set.of()).size();
            items.add(button(model,(blockedModels.contains(modelKey(model))?"GESPERRT":"ERLAUBT")+" • "+count+" gespeicherte Spieler • Details öffnen",Material.COMPARATOR,()->deviceDetail(p,model,false)));
        }
        items.add(button("Zurück","Alternative Accounts",Material.OAK_DOOR,()->plugin.openUnifiedAction(p,26)));show(p,"Bedrock-Gerätemodelle",items);
    }
    void operatingSystems(Player p){
        if(!allowed(p,"devices.models"))return;List<AdminUpdateMenu.Button> items=new ArrayList<>();
        for(String os:knownOs.keySet().stream().sorted().toList())items.add(button(os,(blockedOs.contains(os)?"GESPERRT":"ERLAUBT")+" • Details und betroffene Spieler",Material.COMPARATOR,()->deviceDetail(p,os,true)));
        items.add(button("Weiteren Typ eingeben","Exakter vom Client gemeldeter Betriebssystemtyp",Material.PAPER,()->plugin.adminUpdate.prompt(p,"Betriebssystem","",q->{observeOs(null,q);operatingSystems(p);} )));
        items.add(button("Zurück","Geräteverwaltung",Material.OAK_DOOR,()->devices(p,"")));show(p,"Bedrock-Betriebssysteme",items);
    }
    private void setOs(CommandSender actor,String os,boolean block){
        if(!allowed(actor,"devices.models"))throw new IllegalArgumentException("Geräteberechtigung fehlt.");
        String key=osKey(os);if(key.isBlank()||key.length()>80)throw new IllegalArgumentException("Betriebssystem muss 1–80 Zeichen lang sein.");
        observeOs(null,os);if(block)blockedOs.add(key);else blockedOs.remove(key);dirty=true;save();
        plugin.suiteAudit(actor.getName(),"Betriebssystem",key+" • gesperrt="+block);
        if(block)for(Player p:Bukkit.getOnlinePlayers())if(key.equals(currentOs.get(p.getUniqueId())))plugin.suiteKick(p,"This device operating system is not permitted on this server. Please contact server staff.");
    }
    String deviceSummary(String name,boolean os){
        String key=os?osKey(name):modelKey(name);
        List<String> lines=new ArrayList<>();lines.add(name+" • "+((os?blockedOs:blockedModels).contains(key)?"GESPERRT":"ERLAUBT"));
        plugin.controlDevices().stream().filter(d->key.equals(os?osKey(d.os()):modelKey(d.model()))).limit(60).forEach(d->lines.add(d.player()+" • "+d.playerId()+" • Modell "+d.model()+" • OS "+d.os()+" • Geräte-ID "+d.deviceId()+" • zuletzt <t:"+(d.lastSeen()/1000)+":R>"));
        return String.join("\n",lines);
    }
    private void deviceDetail(Player p,String name,boolean os){
        if(!allowed(p,"devices.models"))return;
        String key=os?osKey(name):modelKey(name);boolean blocked=(os?blockedOs:blockedModels).contains(key);
        List<AdminUpdateMenu.Button> items=new ArrayList<>();
        var matching=plugin.controlDevices().stream().filter(d->key.equals(os?osKey(d.os()):modelKey(d.model()))).toList();
        long affected=matching.stream().map(SimplexAdminSuite.ControlDevice::playerId).distinct().count();
        items.add(button(blocked?"Freigeben":"Global sperren",name+" • "+affected+" gespeicherte Spieler • nur dieser "+(os?"Betriebssystemtyp":"Modellname"),blocked?Material.LIME_DYE:Material.RED_DYE,()->confirm(p,blocked?"Freigabe bestätigen":"Sperre bestätigen",name+" • "+affected+" gespeicherte Spieler; historische Einträge zeigen mögliche Betroffene, aktuelle Verbindungen entscheiden",()->{if(os)setOs(p,name,!blocked);else setModel(p,name,!blocked);deviceDetail(p,name,os);} )));
        for(var d:matching)items.add(button(d.player(),"Modell: "+d.model()+" • OS: "+d.os()+" • Gerätekennungen und Profildaten öffnen",Material.PLAYER_HEAD,()->plugin.adminUpdate.detail(p,d.player()+" • Gerät","Spieler: "+d.player()+"\nUUID: "+d.playerId()+"\nGeräte-ID (gespeicherter Hash): "+d.deviceId()+"\nModell: "+d.model()+"\nBetriebssystem: "+d.os()+"\nVersion: "+d.version()+"\nEingabe: "+d.input()+"\nZuletzt erkannt: "+java.time.Instant.ofEpochMilli(d.lastSeen()),()->deviceDetail(p,name,os))));
        items.add(button("Zurück",os?"Betriebssysteme":"Gerätemodelle",Material.OAK_DOOR,()->{if(os)operatingSystems(p);else devices(p,"");}));show(p,name+" • Geräte & Spieler",items);
    }
    void discord(Player p){
        List<AdminUpdateMenu.Button> items=new ArrayList<>();
        items.add(button("Verbindung & Grundeinstellungen","Token, Guild, Relay, Status und bestehende Bot-Funktionen",Material.REDSTONE_BLOCK,()->plugin.openDiscordConnectionSettings(p)));
        for(String type:LOG_TYPES)items.add(button(logLabel(type)+" • Protokollkanal",plugin.getConfig().getString("discord-bot.moderation-log."+type+"-channel-id","")+" • Spieler, Grund, Nachricht, relative Zeit",Material.PAPER,()->channelMenu(p,type)));
        for(String type:LOG_TYPES)items.add(button(logLabel(type)+" • privaten Kanal erstellen","Bot erstellt den Kanal mit eingeschränkter Sichtbarkeit",Material.HOPPER,()->{command(p,new String[]{"modlog",type,"create"});discord(p);}));
        if(allowed(p,"settings.advanced")){
            items.add(button("Bot-Bio bearbeiten","Englische Profilbeschreibung • maximal 400 Zeichen",Material.BOOK,()->plugin.adminUpdate.prompt(p,"Englische Bot-Bio",plugin.getConfig().getString("discord-bot.profile.description",""),q->{configSet(p,"discord-bot.profile.description",q);if(plugin.discordRelay()!=null)plugin.discordRelay().refreshProfile();discord(p);} )));
            items.add(button("Alle Discord-Einstellungen","Kanäle, Relay, Status, Sicherheit, Befehle, Profile und mehr",Material.REPEATER,()->settings(p,"discord-bot.")));
        }
        items.add(button("Bot-Verbindung / Übersicht",plugin.discordRelay()==null?"Nicht geladen":plugin.discordRelay().summary(),Material.MAP,()->p.sendMessage(plugin.discordRelay()==null?"Nicht geladen":plugin.discordRelay().adminSummary())));
        items.add(button("Zurück","Admin-Verwaltung",Material.OAK_DOOR,()->plugin.unifiedMenu.category(p,"Server")));show(p,"Discord-Verwaltung",items);
    }
    static String logLabel(String type){return switch(type){case "alt-accounts"->"Neue Alt-Account-Hinweise";case "join-details"->"Private Beitrittsdaten";case "client-probes"->"Client-Prüfungen";case "admin"->"Admin-Aktionen";case "punishments"->"Strafen (gemeinsamer Kanal)";case "bans"->"Bans";case "mutes"->"Stummschaltungen";case "vanish"->"Vanish";case "devices"->"Gerätesperren";case "ai"->"KI-Moderation";default->"Ghost-Bann";};}
    private void channelMenu(Player p,String type){
        if(!allowed(p,"moderation.control"))return;
        if(plugin.discordRelay()==null){plugin.menuError(p,"§cBot nicht geladen.");return;}
        long revision=plugin.adminAccess.revision(p.getUniqueId());
        plugin.discordRelay().listLogChannels(channels->{
            if(!p.isOnline()||!allowed(p,"moderation.control")||revision!=plugin.adminAccess.revision(p.getUniqueId()))return;
            List<AdminUpdateMenu.Button> items=new ArrayList<>();
            for(var channel:channels.entrySet())items.add(button("#"+channel.getValue(),channel.getKey(),Material.PAPER,()->{command(p,new String[]{"modlog",type,channel.getKey()});discord(p);}));
            items.add(button("Protokoll deaktivieren","Keine Discord-Nachrichten für diese Kategorie",Material.RED_DYE,()->{command(p,new String[]{"modlog",type,"off"});discord(p);}));
            items.add(button("Kanal-ID manuell eingeben","Alternativ zur Kanalliste",Material.PAPER,()->plugin.adminUpdate.prompt(p,"Discord-Kanal-ID","",q->{command(p,new String[]{"modlog",type,q.trim()});discord(p);} )));
            items.add(button("Zurück","Discord-Verwaltung",Material.OAK_DOOR,()->discord(p)));show(p,logLabel(type)+" • Kanal wählen",items);
        });
    }
    static String auditCategory(String action){
        String a=action==null?"":action.toLowerCase(Locale.ROOT);
        if(a.startsWith("client rule ")||a.startsWith("client probe "))return ""; // One dedicated scan message carries results and action.
        if(a.matches(".*(geöffnet|öffnen|menü geöffnet|abgefragt|gesucht|diagnose|geprüft|moderationsverwaltung|ki-moderation|ghost-chat|ghost-versuch).*"))return "";
        if(a.contains("vanish"))return "vanish";
        if(a.matches(".*(ban|bann).*"))return "bans";
        if(a.matches(".*(mute|stummschalt).*"))return "mutes";
        if(a.matches(".*(einschränkung|warnung).*"))return "punishments";
        if(a.matches(".*(gerätemodell|betriebssystem|gerät.*sperr|gerät.*freig).*"))return "devices";
        if(a.matches(".*(änder|geändert|einstellung|konfiguration|chatformat|wartung|reload|geladen|lösch|entfern|erstellt|aktiviert|deaktiviert|gesetzt|rechte|teleport|freeze|heal|spielmodus|staff-dienst|protokollkanal|verknüpf|ignoriert).*"))return "admin";
        return "";
    }
    void auditEvent(String actor,UUID target,String action,String detail){
        if(Set.of("Suite-Konfiguration","Admin-Einstellung","Globales Chatformat","Discord-Einstellung").contains(action))return;
        String category=auditCategory(action);if(category.isBlank())return;
        String safe=clean(detail==null?"":detail,1400);
        if(safe.toLowerCase(Locale.ROOT).matches(".*(password|token|api-key|api_key|secret|credential).*"))safe="[Geschützte Details ausgeblendet]";
        String entry=java.time.Instant.now()+" | "+category+" | "+actor+" | "+(target==null?"—":target)+" | "+action+" | "+safe;
        synchronized(actionTail){actionTail.addLast(entry);while(actionTail.size()>100)actionTail.removeFirst();}
        if(plugin.discordRelay()!=null)plugin.discordRelay().relayAdminAction(category,actor,target==null?"—":target.toString(),action,safe,System.currentTimeMillis());
    }
    private static List<String> recentAuditLines(Path path)throws IOException{
        try(var file=new RandomAccessFile(path.toFile(),"r")){
            long offset=Math.max(0,file.length()-262144L);file.seek(offset);
            byte[] bytes=new byte[(int)(file.length()-offset)];file.readFully(bytes);
            String text=new String(bytes,java.nio.charset.StandardCharsets.UTF_8);
            if(offset>0){int newline=text.indexOf('\n');text=newline<0?"":text.substring(newline+1);}
            return Arrays.asList(text.split("\\R"));
        }
    }
    String auditSummary(String category){
        List<String> lines=new ArrayList<>();
        Path folder=plugin.getDataFolder().toPath().resolve("audit");
        try(var files=Files.exists(folder)?Files.list(folder):java.util.stream.Stream.<Path>empty()){
            for(Path path:files.filter(f->f.getFileName().toString().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}\\.log")).sorted(Comparator.reverseOrder()).limit(3).toList()){
                Deque<String> recent=new ArrayDeque<>();try(var stream=recentAuditLines(path).stream()){stream.forEach(line->{String[] parts=line.split(" \\| ",5);if(parts.length==5){String kind=auditCategory(parts[3]);if(!kind.isBlank()&&(category.equals("all")||category.equals(kind)||(category.equals("punishments")&&Set.of("bans","mutes").contains(kind)))){recent.addLast(line.toLowerCase(Locale.ROOT).matches(".*(password|token|secret|api-key|api_key|credential).*")?String.join(" | ",Arrays.copyOf(parts,4))+" | [Geschützte Details ausgeblendet]":line);if(recent.size()>30)recent.removeFirst();}}});}
                var reverse=new ArrayList<>(recent);Collections.reverse(reverse);lines.addAll(reverse);if(lines.size()>=30)break;
            }
        }catch(IOException error){return "Aktionsprotokoll konnte nicht gelesen werden.";}
        return lines.isEmpty()?"Keine passenden Admin-Aktionen gespeichert.":String.join("\n",lines.subList(0,Math.min(30,lines.size())));
    }
    static boolean protectedKey(String key){
        return key==null||key.toLowerCase(Locale.ROOT).matches(".*(token|secret|password|api-key|api_key|apikey|webhook-url|webhook\\.url|credential|authorization|private-key|owner|admin-access).*")||key.equals("openai.api.key");
    }
    List<String> configKeys(){return plugin.getConfig().getKeys(true).stream().filter(k->!protectedKey(k)&&!(plugin.getConfig().get(k) instanceof ConfigurationSection)).sorted().toList();}
    private Object configValue(String key){
        if(protectedKey(key)||!configKeys().contains(key))throw new IllegalArgumentException("Unbekannter oder geschützter Konfigurationspfad.");return plugin.getConfig().get(key);
    }
    private void configSet(CommandSender sender,String key,String value){
        if(!allowed(sender,"settings.advanced"))throw new IllegalArgumentException("Erweiterte Einstellberechtigung fehlt.");
        if((key.equals("prefix")||key.startsWith("branding."))&&!plugin.adminAccess.master(sender))throw new IllegalArgumentException("Branding ist Hauptadmins vorbehalten.");
        if(key.equals("branding.display-name")&&(value.isBlank()||value.length()>60))throw new IllegalArgumentException("Anzeigename: 1–60 Zeichen.");
        if(key.equals("prefix")&&value.length()>120)throw new IllegalArgumentException("Präfix: maximal 120 Zeichen.");
        Object prior=configValue(key),next;
        if(value.length()>4000)throw new IllegalArgumentException("Wert ist zu lang.");
        try{
            if(prior instanceof Boolean)next=parseBoolean(value);
            else if(prior instanceof Integer){next=Integer.parseInt(value);}
            else if(prior instanceof Long){next=Long.parseLong(value);}
            else if(prior instanceof Double){double d=Double.parseDouble(value);if(!Double.isFinite(d))throw new IllegalArgumentException("Endlicher Zahlenwert erforderlich.");next=d;}
            else if(prior instanceof List<?>)next=value.isBlank()?List.of():Arrays.stream(value.split("\\|",-1)).map(String::trim).toList();
            else next=value;
        }catch(NumberFormatException error){throw new IllegalArgumentException("Ungültige Zahl.");}
        if(key.endsWith("channel-id")&&!value.isBlank()&&!value.matches("[0-9]{15,22}"))throw new IllegalArgumentException("Discord-Kanal-ID erforderlich.");
        if(key.equals("discord-bot.profile.description")&&value.length()>400)throw new IllegalArgumentException("Bot-Bio: maximal 400 Zeichen.");
        if(key.startsWith("admin-update.feedback.")&&key.endsWith("-sound"))next=SimplexAdminSuite.validatedMenuSound(value);
        if(key.equals("admin-update.feedback.volume-percent")&&(!(next instanceof Number n)||n.intValue()<0||n.intValue()>100))throw new IllegalArgumentException("Lautstärke: 0 bis 100 Prozent.");
        plugin.getConfig().set(key,next);plugin.saveConfig();plugin.suiteAudit(sender.getName(),"Suite-Konfiguration",key+" • "+String.valueOf(prior)+" → "+String.valueOf(next));
    }
    private static String settingLabel(String key){
        if(key.equals("prefix"))return "Nachrichtenpräfix";
        if(key.startsWith("discord-bot.moderation-log.")){String type=key.substring("discord-bot.moderation-log.".length()).replace("-channel-id","");return logLabel(type)+" • Discord-Kanal";}
        return switch(key){case "discord-bot.profile.description"->"Discord-Bot • Profilbeschreibung";case "discord-bot.profile.update-enabled"->"Discord-Bot • Profil synchronisieren";case "discord-bot.admin.command-sync-interval-seconds"->"Discord-Befehle • Synchronisierungsintervall (Sekunden)";case "moderation-control.chat.block-colors"->"Chat • Farben global sperren";case "moderation-control.chat.block-formatting"->"Chat • Formatierungen global sperren";case "moderation-control.silent-error"->"Leiser Bann • Englische Fehlermeldung";case "moderation-control.text.allowed-commands"->"Texteinschränkung • Erlaubte Befehle";default->key;};
    }
    void settings(Player p,String filter){
        if(!allowed(p,"settings.advanced"))return;List<AdminUpdateMenu.Button> items=new ArrayList<>();
        items.add(button("Einstellungen suchen",filter.isBlank()?"Pfad oder Suchwort":filter,Material.SPYGLASS,()->plugin.adminUpdate.prompt(p,"Einstellungssuche",filter,q->settings(p,q))));
        for(String key:configKeys())if(key.toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT))){
            Object prior=plugin.getConfig().get(key);String preview=String.valueOf(prior);if(preview.length()>240)preview=preview.substring(0,240)+"…";
            items.add(button(settingLabel(key),key+" • "+preview+" • Liste: Einträge mit | trennen",Material.COMPARATOR,()->{
                if(prior instanceof Boolean){configSet(p,key,Boolean.toString(!((Boolean)prior)));settings(p,filter);}
                else plugin.adminUpdate.prompt(p,key,prior instanceof List<?> list?String.join(" | ",list.stream().map(String::valueOf).toList()):String.valueOf(prior),q->{try{configSet(p,key,q);settings(p,filter);}catch(IllegalArgumentException error){plugin.menuError(p,"§c"+error.getMessage());settings(p,filter);}});
            }));
        }
        items.add(button("Änderungen anwenden / Suite neu laden","Gespeicherte Modulparameter übernehmen",Material.CLOCK,()->{commandReload(p);open(p);}));
        items.add(button("Zurück","Admin-Verwaltung",Material.OAK_DOOR,()->plugin.unifiedMenu.category(p,"Server")));show(p,"Suite-Einstellungen",items);
    }
    private void commandReload(Player p){if(allowed(p,"reload"))plugin.onCommand(p,plugin.getCommand("sas"),"sas",new String[]{"reload"});else plugin.menuNotice(p,"§cNeuladeberechtigung fehlt.");}
}
