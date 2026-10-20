package eu.simplexsmp.adminsuite;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Persisted recent-player browser with safe Java NBT/statistics inspection. */
final class PlayerDataManager implements Listener {
    private static final String LIST_TITLE = "§0Spielerdaten • 24 Stunden";
    private static final String ONLINE_TITLE = "§0Spielerdaten • Online";
    private static final String DETAIL_PREFIX = "§0Spielerprofil • ";
    private static final long RECENT_WINDOW = 24L * 60L * 60L * 1000L;
    private static final int PAGE_SIZE = 45;

    private static final class DataMenu implements InventoryHolder {
        private final String type;
        private DataMenu(String type) { this.type = type; }
        @Override public Inventory getInventory() { return null; }
    }

    private final SimplexAdminSuite plugin;
    private final Map<UUID, View> views = new ConcurrentHashMap<>();
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final Map<UUID, CachedDiskData> diskCache = new ConcurrentHashMap<>();
    private final Map<UUID, Long> requests = new HashMap<>();
    private long requestSerial;
    private final Map<UUID, java.util.concurrent.CompletableFuture<DiskData>> loading = new HashMap<>();
    private volatile boolean stopped;
    private final SimpleDateFormat date = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.GERMANY);

    PlayerDataManager(SimplexAdminSuite plugin) { this.plugin = plugin; }

    record OwnData(Map<String,String> nbt,Map<String,String> stats,String error) {}
    private final java.util.Set<UUID> ownLoading=new java.util.HashSet<>();
    void readOwn(Player owner,java.util.function.Consumer<OwnData> callback) {
        UUID id=owner.getUniqueId();
        if(ownLoading.size()>=4||!ownLoading.add(id)){owner.sendMessage(plugin.playerText(owner,"§eDateien werden bereits geladen. Bitte kurz warten.","§eFiles are already loading. Please wait."));callback.accept(new OwnData(Collections.emptyMap(),Collections.emptyMap(),"Read busy"));return;}
        List<File> nbtPaths=worldFiles(id,"playerdata",".dat"),statPaths=worldFiles(id,"stats",".json");
        Bukkit.getScheduler().runTaskAsynchronously(plugin,()->{
            DiskData loaded;
            try{loaded=readDisk(nbtPaths,statPaths);}catch(RuntimeException error){loaded=new DiskData(NbtPlayerData.Snapshot.empty("Read failed"),Collections.emptyMap(),"Read failed",0L);}
            final DiskData disk=loaded;
            Map<String,String> nbt=new LinkedHashMap<>(disk.nbt.values),stats=new LinkedHashMap<>();
            disk.stats.forEach((key,value)->stats.put(key,String.valueOf(value)));
            String first=nbt.get("Bukkit first played");
            if(first!=null)try{long at=Long.parseLong(first);nbt.put("Bukkit first played",plugin.profileDate(at));nbt.put("Time since file first join",Math.max(0,System.currentTimeMillis()-at)/86400000+" d");}catch(NumberFormatException ignored){}
            String last=nbt.get("Bukkit last played");if(last!=null)try{nbt.put("Bukkit last played",plugin.profileDate(Long.parseLong(last)));}catch(NumberFormatException ignored){}
            if(plugin.isEnabled()&&!stopped)Bukkit.getScheduler().runTask(plugin,()->{ownLoading.remove(id);if(Bukkit.getPlayer(id)==owner)callback.accept(new OwnData(Collections.unmodifiableMap(nbt),Collections.unmodifiableMap(stats),disk.nbt.error+" "+disk.statsError));});
        });
    }
    void stop() { stopped=true;ownLoading.clear();requests.clear();loading.clear();views.clear(); pending.clear(); diskCache.clear(); }

    boolean handleCommand(org.bukkit.command.CommandSender sender, String[] args) {
        if (!plugin.has(sender, "simplexadminsuite.playerdata")) {
            sender.sendMessage(plugin.prefix() + "Dafür fehlt dir simplexadminsuite.playerdata.");
            return true;
        }
        if (!(sender instanceof Player)) {
            sender.sendMessage(plugin.prefix() + recentProfiles(false).size() + " Spieler waren in den letzten 24 Stunden online.");
            return true;
        }
        Player admin = (Player) sender;
        if (args.length > 0) {
            SimplexAdminSuite.PlayerProfile target = plugin.clientSecurityFindProfile(args[0]);
            if (target == null) { plugin.menuNotice(admin,plugin.prefix() + "Spielerprofil nicht gefunden."); return true; }
            openDetails(admin, target.uniqueId());
        } else openList(admin, 0, false);
        return true;
    }

    boolean consumePendingChat(Player admin, String message) {
        boolean[] handled={false};plugin.asAdmin(admin==null?null:admin.getName(),()->handled[0]=consumeAuditedPendingChat(admin,message));return handled[0];
    }
    private boolean consumeAuditedPendingChat(Player admin, String message) {
        Pending action = admin == null ? null : pending.remove(admin.getUniqueId());
        if (action == null) return false;
        if(!Bukkit.isPrimaryThread()){
            plugin.runAdminTask(()->{if(admin.isOnline()){pending.put(admin.getUniqueId(),action);consumePendingChat(admin,message);}});
            return true;
        }
        if(!dataAllowed(admin)||!plugin.has(admin,"simplexadminsuite.points.admin")){plugin.menuNotice(admin,"§cKeine Berechtigung zur Punkteverwaltung.");return true;}
        if (message.equalsIgnoreCase("cancel")) {
            admin.sendMessage(plugin.prefix() + "Eingabe abgebrochen.");
            plugin.runAdminTask( () -> openDetails(admin, action.target));
            return true;
        }
        SimplexAdminSuite.PlayerProfile profile = plugin.expansionProfile(action.target);
        if (profile == null) return true;
        if (action.type.equals("set-points")) {
            try {
                long value = Long.parseLong(message.trim());
                if (value < 0L || value > 1_000_000_000L) throw new NumberFormatException("range");
                profile.setSimplexPoints(value);
                plugin.saveExpansionProfile(profile);
                plugin.refreshExpansionNameplate(Bukkit.getPlayer(action.target));
                plugin.discordPointsChanged();
                plugin.suiteAudit(admin.getName(), "Simplex Points gesetzt", profile.displayName() + "=" + value);
                plugin.menuNotice(admin,plugin.prefix() + "Punkte von " + profile.displayName() + " wurden auf " + value + " gesetzt.");
            } catch (Exception error) { plugin.menuNotice(admin,plugin.prefix() + "Ungültiger Wert. Erlaubt sind 0 bis 1.000.000.000."); }
        } else if (action.type.equals("reset-points")) {
            if (message.equalsIgnoreCase("RESET")) {
                profile.setSimplexPoints(0L);
                plugin.saveExpansionProfile(profile);
                plugin.refreshExpansionNameplate(Bukkit.getPlayer(action.target));
                plugin.discordPointsChanged();
                plugin.suiteAudit(admin.getName(), "Simplex Points zurückgesetzt", profile.displayName());
                plugin.menuNotice(admin,plugin.prefix() + "Punkte von " + profile.displayName() + " wurden zurückgesetzt.");
            } else plugin.menuNotice(admin,plugin.prefix() + "Zurücksetzen wurde nicht bestätigt.");
        }
        plugin.runAdminTask( () -> openDetails(admin, action.target));
        return true;
    }

    void openList(Player admin, int page, boolean onlineOnly) {
        if (!dataAllowed(admin)) return;
        requests.remove(admin.getUniqueId());
        if (plugin.isBedrockClient(admin)) {
            if (!openBedrockList(admin, page, onlineOnly)) plugin.menuNotice(admin,plugin.prefix() + "Das native Bedrock-Spielerdatenmenü konnte nicht gesendet werden. Bitte prüfe /sas form.");
            return;
        }
        List<SimplexAdminSuite.PlayerProfile> profiles = recentProfiles(onlineOnly);
        int maxPage = Math.max(0, (profiles.size() - 1) / PAGE_SIZE);
        int selectedPage = Math.max(0, Math.min(maxPage, page));
        Inventory inventory = LiveMenus.create(new DataMenu("list"), 54, onlineOnly ? ONLINE_TITLE : LIST_TITLE);
        fill(inventory);
        List<UUID> rows = new ArrayList<>();
        int start = selectedPage * PAGE_SIZE;
        for (int index = start; index < profiles.size() && rows.size() < PAGE_SIZE; index++) {
            SimplexAdminSuite.PlayerProfile profile = profiles.get(index);
            Player online = Bukkit.getPlayer(profile.uniqueId());
            rows.add(profile.uniqueId());
            inventory.setItem(rows.size() - 1, item(online == null ? Material.PLAYER_HEAD : Material.LIME_DYE,
                    "&f" + profile.displayName(),
                    "&7Status: " + (online == null ? "&7offline" : "&aonline"),
                    "&7Zuletzt: &f" + ago(profile.lastSeenValue()),
                    "&7Gesamtspielzeit: &f" + duration(profile.totalPlayMillisValue()),
                    "&7Simplex Points: &b" + profile.simplexPoints(),
                    "&eKlicken für vollständiges Profil"));
        }
        inventory.setItem(45, item(Material.ARROW, "&eVorherige Seite", "&7Seite " + (selectedPage + 1) + "/" + (maxPage + 1)));
        inventory.setItem(47, item(Material.CLOCK, onlineOnly ? "&eLetzte 24 Stunden" : "&aNur online", "&7Ansicht umschalten"));
        inventory.setItem(49, item(Material.BARRIER, "&cZum Startmenü", "&7Zurück zur Simplex Admin Suite."));
        inventory.setItem(53, item(Material.ARROW, "&eNächste Seite", "&7Seite " + (selectedPage + 1) + "/" + (maxPage + 1)));
        views.put(admin.getUniqueId(), new View("list", rows, null, selectedPage, onlineOnly));
        LiveMenus.show(admin,inventory);
    }

    private boolean dataAllowed(Player admin){return admin!=null&&admin.isOnline()&&plugin.adminMenuAllowed(admin,"playerdatamanager.view")&&plugin.has(admin,"simplexadminsuite.playerdata");}
    void openDetails(Player admin, UUID targetUuid) {
        if(!dataAllowed(admin))return;
        long request=++requestSerial;requests.put(admin.getUniqueId(),request);
        CachedDiskData cached=diskCache.get(targetUuid);
        if(cached!=null&&System.currentTimeMillis()-cached.loadedAt<60_000L){renderDetails(admin,targetUuid,cached.data);return;}
        java.util.concurrent.CompletableFuture<DiskData> future=loading.get(targetUuid);
        if(future==null){
            if(loading.size()>=4){admin.sendMessage("§eVier Spielerdateien werden bereits geladen. Bitte gleich erneut versuchen.");return;}
            List<File> nbtPaths=worldFiles(targetUuid,"playerdata",".dat"),statsPaths=worldFiles(targetUuid,"stats",".json");
            future=new java.util.concurrent.CompletableFuture<>();loading.put(targetUuid,future);
            java.util.concurrent.CompletableFuture<DiskData> task=future;
            Bukkit.getScheduler().runTaskAsynchronously(plugin,()->{
                DiskData loaded;
                try{loaded=readDisk(nbtPaths,statsPaths);}catch(RuntimeException error){loaded=new DiskData(NbtPlayerData.Snapshot.empty("Dateizugriff fehlgeschlagen: "+error.getClass().getSimpleName()),Collections.emptyMap(),"Dateizugriff fehlgeschlagen",0L);}
                final DiskData data=loaded;
                if(plugin.isEnabled()&&!stopped)plugin.runAdminTask(()->{
                    loading.remove(targetUuid);
                    diskCache.entrySet().removeIf(e->System.currentTimeMillis()-e.getValue().loadedAt>=60_000L);
                    if(diskCache.size()>=128)diskCache.clear();
                    diskCache.put(targetUuid,new CachedDiskData(System.currentTimeMillis(),data));task.complete(data);
                });
            });
        }
        Inventory loadingInventory;
        org.bukkit.inventory.InventoryHolder loadingContext;
        if(plugin.isBedrockClient(admin)){
            loadingInventory=null;
            loadingContext=null;
            admin.sendMessage("§7Die Datei des ausgewählten Spielers wird geladen …");
        }else{
            loadingInventory=LiveMenus.create(new DataMenu("loading"),27,"Spielerdaten werden geladen");
            loadingContext=LiveMenus.holder(loadingInventory);
            loadingInventory.setItem(13,item(Material.CLOCK,"&eDatei wird gelesen …","&7Nur der ausgewählte Spieler.","&7Schließen bricht die Anzeige ab."));LiveMenus.show(admin,loadingInventory);
        }
        long revision=plugin.adminAccess.revision(admin.getUniqueId());
        long formGeneration=plugin.nativeFormGeneration(admin.getUniqueId());
        future.thenAccept(data->{
            if(stopped||!dataAllowed(admin)||revision!=plugin.adminAccess.revision(admin.getUniqueId())||!java.util.Objects.equals(requests.get(admin.getUniqueId()),request))return;
            if(loadingInventory!=null&&!LiveMenus.current(admin,loadingInventory,loadingContext))return;
            if(loadingInventory==null&&formGeneration!=plugin.nativeFormGeneration(admin.getUniqueId()))return;
            renderDetails(admin,targetUuid,data);
        });
    }
    private void renderDetails(Player admin,UUID targetUuid,DiskData disk){
        if(!dataAllowed(admin))return;
        requests.remove(admin.getUniqueId());
        SimplexAdminSuite.PlayerProfile profile = plugin.expansionProfile(targetUuid);
        if (profile == null) { plugin.menuNotice(admin,plugin.prefix() + "Spielerprofil nicht gefunden."); return; }
        if (plugin.isBedrockClient(admin)) {
            if (!openBedrockDetails(admin, profile, disk)) plugin.menuNotice(admin,plugin.prefix() + "Das native Bedrock-Spielerprofil konnte nicht gesendet werden. Bitte prüfe /sas form.");
            return;
        }
        Player online = Bukkit.getPlayer(targetUuid);
        Inventory inventory = LiveMenus.create(new DataMenu("detail"), 54, DETAIL_PREFIX + trim(profile.displayName(), 18));
        fill(inventory);
        inventory.setItem(10, item(Material.PLAYER_HEAD, "&b" + profile.displayName(),
                "&7UUID: &f" + profile.uniqueId(),
                "&7Status: " + (online == null ? "&7offline" : "&aonline"),
                "&7Plattform: &f" + profile.clientPlatformValue()));
        inventory.setItem(11, item(Material.CLOCK, "&eZeit & Sitzungen",
                "&7Erstmals von Simplex erfasst: &f" + formatted(profile.firstSeenValue()),
                "&7Datei-Erstjoin: &f" + formatted(number(disk.value("Bukkit first played"))),
                "&7Vor: &f" + ago(profile.firstSeenValue()),
                "&7Letzter Join: &f" + formatted(profile.lastJoinValue()),
                "&7Zuletzt gesehen: &f" + ago(profile.lastSeenValue()),
                "&7Sitzungen: &f" + profile.sessionCountValue(),
                "&7Gesamt: &f" + duration(profile.totalPlayMillisValue()),
                "&7AFK: &f" + duration(profile.totalAfkMillisValue())));
        inventory.setItem(12, item(Material.GOLDEN_APPLE, "&eLive-Zustand",
                "&7Gesundheit: &f" + live(online, "getHealth", disk.value("Health")),
                "&7Hunger: &f" + live(online, "getFoodLevel", disk.value("Food")),
                "&7Sättigung: &f" + live(online, "getSaturation", disk.value("Saturation")),
                "&7XP-Level: &f" + live(online, "getLevel", disk.value("XP level")),
                "&eKlick: Live-Werte bearbeiten (extra Recht)",
                "&7Spielmodus: &f" + (online == null ? disk.value("Game mode") : String.valueOf(online.getGameMode()))));
        inventory.setItem(13, item(Material.COMPASS, "&eOrt & Welt",
                "&7Dimension: &f" + disk.value("Dimension"),
                "&7Position: &f" + (online == null ? disk.value("Position") : position(online)),
                "&7Rotation: &f" + disk.value("Rotation"),
                "&7Spawn: &f" + disk.value("Spawn"),
                "&7Letzter Tod: &f" + disk.value("Last death")));
        inventory.setItem(14, item(Material.EMERALD, "&bSimplex Points",
                "&7Kontostand: &b" + profile.simplexPoints() + " SP",
                "&7Gewertete Aktivzeit: &f" + duration(profile.pointsActiveMillis()),
                "&eLinksklick: Kontostand setzen",
                "&cRechtsklick: auf 0 zurücksetzen"));
        inventory.setItem(15, item(Material.CHEST, "&eInventar & NBT",
                "&7Inventarstapel: &f" + disk.value("Inventory stacks"),
                "&7Endertruhenstapel: &f" + disk.value("Ender chest stacks"),
                "&7NBT DataVersion: &f" + disk.value("Data version"),
                "&7Quelle: &f" + (disk.nbt.source == null ? "nicht gefunden" : disk.nbt.source.getPath()),
                "&7Dateistand: &f"+formatted(disk.savedAt),
                disk.nbt.error.isBlank() ? "&aNBT erfolgreich gelesen" : "&c" + trim(disk.nbt.error, 80)));
        inventory.setItem(16, item(Material.WRITABLE_BOOK, "&eVanilla-Statistiken",
                "&7Spielzeit: &f" + ticksDuration(disk.stat("minecraft:play_time")),
                "&7Tode: &f" + disk.stat("minecraft:deaths"),
                "&7Sprünge: &f" + disk.stat("minecraft:jump"),
                "&7Spieler-Kills: &f" + disk.stat("minecraft:player_kills"),
                "&7Mob-Kills: &f" + disk.stat("minecraft:mob_kills"),
                "&7Gelaufen: &f" + centimeters(disk.stat("minecraft:walk_one_cm")),disk.statsError.isBlank()?"&aStatistikdatei gelesen":"&c"+disk.statsError));
        inventory.setItem(28, item(Material.BOOK, "&eWeitere NBT-Werte", diskLore(disk.nbt.values, 12)));
        inventory.setItem(30, item(Material.DIAMOND_PICKAXE, "&eHäufige Gegenstände", itemLore(disk.nbt.inventory, 12)));
        inventory.setItem(32, item(Material.RECOVERY_COMPASS, "&eAktivitätsanalyse", "&7" + plugin.activityStatus(online), "&7Offline-Werte werden beim nächsten Join neu aufgebaut."));
        inventory.setItem(34, item(Material.NAME_TAG, "&eClient & Mod-Kanäle",
                "&7Java-Brand: &f" + profile.javaClientBrandValue(),
                "&7Kanäle: &f" + profile.javaRegisteredChannelsSnapshot().size(),
                "&7Mod-Hinweise: &f" + (profile.javaDetectedModsSnapshot().isEmpty() ? "Keine" : String.join(", ", profile.javaDetectedModsSnapshot()))));
        inventory.setItem(45, item(Material.ARROW, "&eZurück", "&7Zur 24-Stunden-Liste"));
        inventory.setItem(49, item(Material.BARRIER, "&cZum Startmenü", "&7Zurück zur Simplex Admin Suite."));
        inventory.setItem(53, item(Material.CLOCK, "&eNeu von Disk lesen", "&7NBT- und Statistikcache verwerfen"));
        views.put(admin.getUniqueId(), new View("detail", Collections.emptyList(), targetUuid, 0, false));
        LiveMenus.show(admin,inventory);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        plugin.asAdmin(event.getWhoClicked().getName(),()->handleAuditedInventoryClick(event));
    }
    private void handleAuditedInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player admin = (Player) event.getWhoClicked();
        if (!(LiveMenus.holder(event.getInventory()) instanceof DataMenu)) return;
        event.setCancelled(true); // Protect every slot, including shift-clicks/hotbar swaps from the player inventory.
        View view = views.get(admin.getUniqueId());
        if (view == null || !dataAllowed(admin) || ((DataMenu)LiveMenus.holder(event.getInventory())).type.equals("loading")) return;
        int slot = event.getRawSlot();
        if (view.type.equals("list")) {
            if (slot >= 0 && slot < view.rows.size()) openDetails(admin, view.rows.get(slot));
            else if (slot == 45) openList(admin, view.page - 1, view.onlineOnly);
            else if (slot == 47) openList(admin, 0, !view.onlineOnly);
            else if (slot == 49) { views.remove(admin.getUniqueId()); plugin.openMainFromModule(admin); }
            else if (slot == 53) openList(admin, view.page + 1, view.onlineOnly);
            return;
        }
        if (view.target == null) return;
        if(!dataAllowed(admin))return;
        if(slot==12){editVitals(admin,view.target);return;}
        if(slot==28||slot==30||slot==16){CachedDiskData cached=diskCache.get(view.target);if(cached!=null)showDiskSection(admin,view.target,cached.data,slot==28?"nbt":slot==30?"items":"stats");else openDetails(admin,view.target);return;}
        if (slot == 14) {
            pending.put(admin.getUniqueId(), new Pending(event.isRightClick() ? "reset-points" : "set-points", view.target));
            admin.closeInventory();
            admin.sendMessage(plugin.prefix() + (event.isRightClick() ? "Schreibe RESET, um den Kontostand auf 0 zu setzen, oder cancel." : "Gib den neuen Simplex-Points-Kontostand ein oder cancel."));
        } else if (slot == 45) openList(admin, 0, false);
        else if (slot == 49) { views.remove(admin.getUniqueId()); plugin.openMainFromModule(admin); }
        else if (slot == 53) { diskCache.remove(view.target); openDetails(admin, view.target); }
    }

    @EventHandler public void quit(org.bukkit.event.player.PlayerQuitEvent event){UUID id=event.getPlayer().getUniqueId();requests.remove(id);pending.remove(id);views.remove(id);}
    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (LiveMenus.holder(event.getInventory()) instanceof DataMenu) event.setCancelled(true);
    }

    private static String inventoryTitle(InventoryClickEvent event) {
        try { return inventoryTitle(event.getView()); }
        catch (Throwable ignored) { return ""; }
    }

    private static String inventoryTitle(org.bukkit.inventory.InventoryView view) {
        try { return view == null ? "" : LiveMenus.title(view); }
        catch (Throwable ignored) { return ""; }
    }

    private boolean openBedrockList(Player admin, int page, boolean onlineOnly) {
        if (!plugin.adminMenuAllowed(admin, "playerdatamanager.openbedrocklist")) return true;
        try {
            if (!plugin.isBedrockClient(admin)) return false;
            List<SimplexAdminSuite.PlayerProfile> profiles = recentProfiles(onlineOnly);
            int maxPage = Math.max(0, (profiles.size() - 1) / 15);
            int selected = Math.max(0, Math.min(maxPage, page));
            SimplexAdminSuite.SimpleForm.Builder form = SimplexAdminSuite.SimpleForm.builder().title("Spielerdaten • " + (onlineOnly ? "Online" : "24 Stunden"))
                    .content("Persistente Spielerprofile, Live-Zustand, Java-NBT und Vanilla-Statistiken. Seite " + (selected + 1) + "/" + (maxPage + 1));
            int start = selected * 15;
            for (int index = start; index < profiles.size() && index < start + 15; index++) {
                SimplexAdminSuite.PlayerProfile profile = profiles.get(index);
                form.button((Bukkit.getPlayer(profile.uniqueId()) == null ? "§7" : "§a") + profile.displayName() + "\n§f" + ago(profile.lastSeenValue()) + " • " + profile.simplexPoints() + " SP",
                        response -> plugin.runAdminTask( () -> openDetails(admin, profile.uniqueId())));
            }
            form.button("§eVorherige Seite", response -> plugin.runAdminTask( () -> openList(admin, selected - 1, onlineOnly)));
            form.button(onlineOnly ? "§eLetzte 24 Stunden" : "§aNur online", response -> plugin.runAdminTask( () -> openList(admin, 0, !onlineOnly)));
            form.button("§eNächste Seite", response -> plugin.runAdminTask( () -> openList(admin, selected + 1, onlineOnly)));
            form.button("§cZum Startmenü", response -> plugin.runAdminTask( () -> plugin.openMainFromModule(admin)));
            return plugin.sendBedrockForm(admin, form);
        } catch (Throwable error) {
            plugin.getLogger().warning("Bedrock-Spielerdatenliste für " + admin.getName() + " fehlgeschlagen: " + error.getClass().getSimpleName() + ": " + error.getMessage());
            return false;
        }
    }

    private boolean openBedrockDetails(Player admin, SimplexAdminSuite.PlayerProfile profile, DiskData disk) {
        if (!plugin.adminMenuAllowed(admin, "playerdatamanager.openbedrockdetails")) return true;
        try {
            if (!plugin.isBedrockClient(admin)) return false;
            Player online = Bukkit.getPlayer(profile.uniqueId());
            String content = "§fUUID: §7" + profile.uniqueId()
                    + "\n§fStatus: " + (online == null ? "§7offline" : "§aonline")
                    + "\n§fSimplex-Erfassung: §e" + formatted(profile.firstSeenValue()) + " (" + ago(profile.firstSeenValue()) + ")"
                    + "\n§fDatei-Erstjoin: §e" + formatted(number(disk.value("Bukkit first played")))
                    + "\n§fGesamtspielzeit: §e" + duration(profile.totalPlayMillisValue())
                    + "\n§fSitzungen: §e" + profile.sessionCountValue()
                    + "\n§fHunger / Gesundheit: §e" + live(online, "getFoodLevel", disk.value("Food")) + " / " + live(online, "getHealth", disk.value("Health"))
                    + "\n§fXP-Level: §e" + live(online, "getLevel", disk.value("XP level"))
                    + "\n§fPosition: §e" + (online == null ? disk.value("Position") : position(online))
                    + "\n§fSimplex Points: §b" + profile.simplexPoints()
                    + "\n§fVanilla-Spielzeit: §e" + ticksDuration(disk.stat("minecraft:play_time"))
                    + "\n§fTode / Sprünge: §e" + disk.stat("minecraft:deaths") + " / " + disk.stat("minecraft:jump")
                    + "\n§fNBT: §e" + (disk.nbt.source == null ? "nicht gefunden" : disk.nbt.source.getPath())
                    + "\n§fDateistand: §e"+formatted(disk.savedAt)+"\n§7Live-Spieler können seitdem weitergespielt haben."
                    + (disk.nbt.error.isBlank()?"":"\n§cNBT: "+disk.nbt.error)
                    + (disk.statsError.isBlank()?"":"\n§cStatistik: "+disk.statsError);
            SimplexAdminSuite.SimpleForm.Builder form = SimplexAdminSuite.SimpleForm.builder().title("Spielerprofil • " + profile.displayName()).content(content);
            form.button("§eSimplex Points setzen", response -> plugin.runAdminTask( () -> {
                plugin.adminUpdate.prompt(admin,"Simplex Points setzen",String.valueOf(profile.simplexPoints()),v->{pending.put(admin.getUniqueId(),new Pending("set-points",profile.uniqueId()));consumePendingChat(admin,v);});
            }));
            form.button("§cSimplex Points zurücksetzen", response -> plugin.runAdminTask( () -> {
                plugin.adminUpdate.prompt(admin,"Mit RESET bestätigen","",v->{pending.put(admin.getUniqueId(),new Pending("reset-points",profile.uniqueId()));consumePendingChat(admin,v);});
            }));
            form.button("§aHunger / Gesundheit bearbeiten", response -> plugin.runAdminTask( () -> editVitals(admin,profile.uniqueId())));
            form.button("§bAlle NBT-Werte", response -> plugin.runAdminTask( () -> showDiskSection(admin,profile.uniqueId(),disk,"nbt")));
            form.button("§bInventarübersicht", response -> plugin.runAdminTask( () -> showDiskSection(admin,profile.uniqueId(),disk,"items")));
            form.button("§bAlle Statistiken", response -> plugin.runAdminTask( () -> showDiskSection(admin,profile.uniqueId(),disk,"stats")));
            form.button("§eDiskdaten neu laden", response -> plugin.runAdminTask( () -> { diskCache.remove(profile.uniqueId()); openDetails(admin, profile.uniqueId()); }));
            form.button("§eZurück zur 24-Stunden-Liste", response -> plugin.runAdminTask( () -> openList(admin, 0, false)));
            form.button("§cZum Startmenü", response -> plugin.runAdminTask( () -> plugin.openMainFromModule(admin)));
            return plugin.sendBedrockForm(admin, form);
        } catch (Throwable error) {
            plugin.getLogger().warning("Bedrock-Spielerprofil für " + admin.getName() + " fehlgeschlagen: " + error.getClass().getSimpleName() + ": " + error.getMessage());
            return false;
        }
    }

    private List<SimplexAdminSuite.PlayerProfile> recentProfiles(boolean onlineOnly) {
        long cutoff = System.currentTimeMillis() - RECENT_WINDOW;
        List<SimplexAdminSuite.PlayerProfile> profiles = new ArrayList<>();
        for (SimplexAdminSuite.PlayerProfile profile : plugin.expansionProfiles()) {
            if (onlineOnly) {
                if (Bukkit.getPlayer(profile.uniqueId()) != null) profiles.add(profile);
            } else if (profile.lastSeenValue() >= cutoff || Bukkit.getPlayer(profile.uniqueId()) != null) profiles.add(profile);
        }
        profiles.sort(Comparator.comparing((SimplexAdminSuite.PlayerProfile p) -> Bukkit.getPlayer(p.uniqueId()) == null).thenComparing(Comparator.comparingLong(SimplexAdminSuite.PlayerProfile::lastSeenValue).reversed()));
        return profiles;
    }

    private void showDiskSection(Player admin,UUID target,DiskData disk,String section){
        if(!dataAllowed(admin))return;
        List<AdminUpdateMenu.Button> rows=new ArrayList<>();
        if(section.equals("nbt"))disk.nbt.values.forEach((k,v)->rows.add(new AdminUpdateMenu.Button(NbtPlayerData.label(k),v,Material.PAPER,()->admin.sendMessage("§e"+NbtPlayerData.label(k)+": §f"+v),true)));
        else if(section.equals("items"))disk.nbt.inventory.entrySet().stream().sorted(Map.Entry.<String,Integer>comparingByValue().reversed()).forEach(e->rows.add(new AdminUpdateMenu.Button(e.getKey(),"Anzahl: "+e.getValue(),Material.CHEST,()->admin.sendMessage("§e"+e.getKey()+": "+e.getValue()),true)));
        else disk.stats.forEach((k,v)->rows.add(new AdminUpdateMenu.Button(statLabel(k),String.valueOf(v),Material.BOOK,()->admin.sendMessage("§e"+statLabel(k)+": "+v),true)));
        if(rows.isEmpty())rows.add(AdminUpdateMenu.info("Keine Daten",section.equals("stats")?disk.statsError:disk.nbt.error,Material.PAPER));
        rows.add(new AdminUpdateMenu.Button("Zurück","Spielerprofil",Material.ARROW,()->openDetails(admin,target)));
        plugin.adminUpdate.show(admin,section.equals("nbt")?"Gespeicherte NBT-Werte":section.equals("items")?"Gespeichertes Inventar":"Gespeicherte Statistiken",rows,0);
    }
    private static String statLabel(String key){return switch(key){
        case "minecraft:play_time"->"Spielzeit (Ticks)";case "minecraft:deaths"->"Tode";case "minecraft:jump"->"Sprünge";
        case "minecraft:player_kills"->"Spieler getötet";case "minecraft:mob_kills"->"Kreaturen getötet";
        case "minecraft:walk_one_cm"->"Gelaufen (cm)";case "minecraft:sprint_one_cm"->"Gesprintet (cm)";
        case "minecraft:fly_one_cm"->"Geflogen (cm)";case "minecraft:swim_one_cm"->"Geschwommen (cm)";
        case "minecraft:time_since_death"->"Seit letztem Tod (Ticks)";default->key;};}
    private DiskData readDisk(List<File> nbtPaths,List<File> statsPaths){
        File nbtFile=newest(nbtPaths),statsFile=newest(statsPaths);
        NbtPlayerData.Snapshot nbt;
        try{nbt=NbtPlayerData.read(nbtFile);}catch(Exception e){nbt=NbtPlayerData.Snapshot.empty(e.getClass().getSimpleName()+": "+e.getMessage());}
        Map<String,Long> stats=Collections.emptyMap();String error="";
        try{stats=readStats(statsFile);}catch(Exception e){error=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
        return new DiskData(nbt,stats,error,nbtFile==null?0L:nbtFile.lastModified());
    }
    private void editVitals(Player admin,UUID id){
        if(!dataAllowed(admin)||!plugin.has(admin,"simplexadminsuite.playerdata.edit")) {plugin.menuError(admin,"§cKeine Bearbeitungsrechte.");return;}
        List<AdminUpdateMenu.Button> rows=new ArrayList<>();
        for(String field:List.of("Hunger","Sättigung","Gesundheit","XP-Level"))rows.add(new AdminUpdateMenu.Button(field,"Online-Spieler: Wert sicher über die Server-API setzen",Material.GOLDEN_APPLE,()->
            plugin.adminUpdate.prompt(admin,field+" setzen","",value->{
                if(!dataAllowed(admin)||!plugin.has(admin,"simplexadminsuite.playerdata.edit"))return;
                Player target=Bukkit.getPlayer(id);
                if(target==null){plugin.menuError(admin,"§cSpieler muss online sein; Offline-Dateien werden nicht überschrieben.");return;}
                try{
                    double n=Double.parseDouble(value);if(!Double.isFinite(n))throw new IllegalArgumentException();
                    if(field.equals("Hunger")){if(n<0||n>20||n!=(int)n)throw new IllegalArgumentException();target.setFoodLevel((int)n);}
                    else if(field.equals("Sättigung")){if(n<0||n>20)throw new IllegalArgumentException();target.setSaturation((float)n);}
                    else if(field.equals("Gesundheit")){if(n<=0||n>target.getMaxHealth())throw new IllegalArgumentException();target.setHealth(n);}
                    else {if(n<0||n>10000||n!=(int)n)throw new IllegalArgumentException();target.setLevel((int)n);}
                    target.saveData();diskCache.remove(id);plugin.expansionAudit(admin.getName(),id,"Spielerdaten geändert",field+"="+n);
                    openDetails(admin,id);
                }catch(IllegalArgumentException ex){plugin.menuNotice(admin,"§cUngültiger Wert (Hunger/Sättigung 0–20, Gesundheit >0 bis Maximum, XP 0–10000).");}
            })));
        plugin.adminUpdate.show(admin,"Live-Werte bearbeiten",rows,0);
    }
    private static File newest(List<File> paths){return PlayerDataPaths.select(paths);}
    private List<File> worldFiles(UUID uuid, String folder, String suffix) {
        java.util.LinkedHashSet<File> roots = new java.util.LinkedHashSet<>();
        for (World world : Bukkit.getWorlds()) {
            File dir = worldFolder(world); if(dir != null) roots.add(dir);
        }
        File parent=plugin.getDataFolder().getAbsoluteFile().getParentFile();
        File server=parent==null?null:parent.getParentFile();
        if(server!=null){
            java.util.Properties props=new java.util.Properties();
            try(java.io.InputStream in=new java.io.FileInputStream(new File(server,"server.properties"))){props.load(in);}catch(java.io.IOException ignored){}
            roots.add(new File(server,props.getProperty("level-name","world")));
            for(String name:List.of(plugin.getConfig().getString("scoreboard.main-world","world"),plugin.getConfig().getString("scoreboard.second-world.name","survival2")))
                if(!name.isBlank())roots.add(new File(server,name));
        }
        for(String configured:plugin.getConfig().getStringList("player-data.additional-world-folders"))
            if(!configured.isBlank())roots.add(new File(configured));
        return PlayerDataPaths.candidates(roots,uuid,folder,suffix);
    }

    private File worldFolder(World world) {
        try { return world.getWorldFolder(); }
        catch (Throwable ignored) { return null; }
    }

    private Map<String,Long> readStats(File file) throws Exception { return PlayerStatistics.read(file); }

    private String live(Player player, String method, String fallback) {
        if (player == null) return empty(fallback);
        try {
            if (method.equals("getHealth")) return String.valueOf(player.getHealth());
            if (method.equals("getFoodLevel")) return String.valueOf(player.getFoodLevel());
            if (method.equals("getSaturation")) return String.valueOf(player.getSaturation());
            if (method.equals("getLevel")) return String.valueOf(player.getLevel());
            return empty(fallback);
        }
        catch (Throwable ignored) { return empty(fallback); }
    }

    private String position(Player player) { return player.getWorld().getName() + " @ " + player.getLocation().getBlockX() + " / " + player.getLocation().getBlockY() + " / " + player.getLocation().getBlockZ(); }
    private String formatted(long value) { return value <= 0L ? "Unbekannt" : date.format(new Date(value)); }
    private String ago(long value) { return value <= 0L ? "unbekannt" : duration(Math.max(0L, System.currentTimeMillis() - value)) + " her"; }

    private static String duration(long millis) {
        long seconds = Math.max(0L, millis / 1000L);
        long days = seconds / 86_400L; seconds %= 86_400L;
        long hours = seconds / 3600L; seconds %= 3600L;
        long minutes = seconds / 60L;
        long years = days / 365L; days %= 365L;
        long weeks = days / 7L; days %= 7L;
        if (years > 0L) return years + "y " + weeks + "w";
        if (weeks > 0L) return weeks + "w " + days + "d";
        if (days > 0L) return days + "d " + hours + "h";
        if (hours > 0L) return hours + "h " + minutes + "m";
        return minutes + "m";
    }

    private static String ticksDuration(long ticks) { return ticks <= 0L ? "0m" : duration(ticks * 50L); }
    private static long number(String value) { try { return Long.parseLong(value); } catch (Exception ignored) { return 0L; } }
    private static String centimeters(long value) { return String.format(Locale.GERMANY, "%.2f km", Math.max(0L, value) / 100_000.0D); }
    private static String empty(String value) { return value == null || value.isBlank() ? "Unbekannt" : value; }
    private static String trim(String value, int max) { String text = value == null ? "" : value; return text.length() <= max ? text : text.substring(0, max); }
    private static String color(String value) { return ChatColor.translateAlternateColorCodes('&', value == null ? "" : value); }

    private static String[] diskLore(Map<String, String> values, int limit) {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            lines.add("&7" + NbtPlayerData.label(entry.getKey()) + ": &f" + trim(entry.getValue(), 44));
            if (lines.size() >= limit) break;
        }
        if (lines.isEmpty()) lines.add("&7Keine zusätzlichen Werte");
        return lines.toArray(new String[0]);
    }

    private static String[] itemLore(Map<String, Integer> values, int limit) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(values.entrySet());
        entries.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : entries) {
            lines.add("&7" + trim(entry.getKey().replace("minecraft:", ""), 34) + ": &f" + entry.getValue());
            if (lines.size() >= limit) break;
        }
        if (lines.isEmpty()) lines.add("&7Keine Inventardaten");
        return lines.toArray(new String[0]);
    }

    private static ItemStack item(Material material, String name, String... lore) {
        ItemStack stack = new ItemStack(MenuStyle.actionMaterial(name,material));
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(MenuStyle.javaText(name));
            List<String> lines = new ArrayList<>(); for (String line : lore) lines.add(MenuStyle.javaText(line));
            meta.setLore(lines); stack.setItemMeta(meta);
        }
        return stack;
    }

    private static void fill(Inventory inventory) {
        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, filler);
    }

    private static final class View {
        private final String type; private final List<UUID> rows; private final UUID target; private final int page; private final boolean onlineOnly;
        private View(String type, List<UUID> rows, UUID target, int page, boolean onlineOnly) { this.type = type; this.rows = rows; this.target = target; this.page = page; this.onlineOnly = onlineOnly; }
    }
    private static final class Pending { private final String type; private final UUID target; private Pending(String type, UUID target) { this.type = type; this.target = target; } }
    private static final class CachedDiskData { private final long loadedAt; private final DiskData data; private CachedDiskData(long loadedAt, DiskData data) { this.loadedAt = loadedAt; this.data = data; } }
    private static final class DiskData {
        private final NbtPlayerData.Snapshot nbt; private final Map<String, Long> stats;private final String statsError;private final long savedAt;
        private DiskData(NbtPlayerData.Snapshot nbt, Map<String, Long> stats,String error,long savedAt) { this.nbt = nbt; this.stats = stats;this.statsError=error;this.savedAt=savedAt; }
        private String value(String key) { return empty(nbt.values.get(key)); }
        private long stat(String key) { return Math.max(0L, stats.getOrDefault(key, 0L)); }
    }
}
