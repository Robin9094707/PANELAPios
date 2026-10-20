package eu.simplexsmp.adminsuite;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Native Java administration center introduced in 6.0.0. Bedrock forms call the same checked
 * operations through SimplexAdminSuite, so both editions share persistence, confirmations and
 * audit records. The lag inspector never loads chunks and performs all Bukkit access on the main
 * thread with a strict per-tick time budget.
 */
final class AdminPowerToolsManager implements Listener {
    enum ModerationPreset { BUILD_LOCK, CHAT_CONTROL, ADVENTURE_LOCK }

    static final class AltCandidate {
        final UUID uuid;
        final String name;
        final int score;
        final boolean safe;
        final List<String> evidence;

        AltCandidate(UUID uuid, String name, int score, boolean safe, List<String> evidence) {
            this.uuid = uuid;
            this.name = name == null ? "Unknown" : name;
            this.score = Math.max(0, score);
            this.safe = safe;
            this.evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }
    }

    static final class DiscordDesignInput {
        String serverName = "Simplex SMP";
        String onlineTitle = "🟢 {server} is online";
        String warningTitle = "🟡 {server} stability warning";
        String criticalTitle = "🔴 {server} is under heavy load";
        String restartingTitle = "🟠 {server} is restarting";
        String offlineTitle = "🔴 {server} is offline";
        String onlineDescription = "The server is online and accepting players.";
        String warningDescription = "Server performance is currently degraded. Gameplay may feel less responsive.";
        String criticalDescription = "Server performance is currently unstable. Staff have been notified.";
        String restartingDescription = "The server is restarting. Players can reconnect shortly.";
        String offlineDescription = "The server is currently shut down.";
        String footer = "Persistent status • Java and Bedrock";
        String healthyColor = "#57F287";
        String warningColor = "#FEE75C";
        String criticalColor = "#ED4245";
        String restartingColor = "#FEE75C";
        String offlineColor = "#ED4245";
        double warningTps = 18.5D;
        double criticalTps = 16.0D;
        boolean showPlayerCount = true;
        boolean showPlayerList = true;
        boolean showPlatform = true;
        boolean showTps = true;
        boolean showStability = true;
        boolean showLeaderboard;
    }

    static final class Hotspot {
        final String world;
        final int chunkX;
        final int chunkZ;
        final int score;
        final int entities;
        final int blockEntities;
        final int hoppers;
        final int minecarts;
        final int items;
        final String reason;

        Hotspot(String world, int chunkX, int chunkZ, int score, int entities, int blockEntities,
                int hoppers, int minecarts, int items, String reason) {
            this.world = world == null ? "unknown" : world;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.score = Math.max(0, score);
            this.entities = Math.max(0, entities);
            this.blockEntities = Math.max(0, blockEntities);
            this.hoppers = Math.max(0, hoppers);
            this.minecarts = Math.max(0, minecarts);
            this.items = Math.max(0, items);
            this.reason = reason == null ? "No dominant source" : reason;
        }

        String coordinates() { return world + " " + (chunkX * 16 + 8) + " / " + (chunkZ * 16 + 8); }
    }

    static final class LagHistoryEntry {
        final long at;
        final String actor;
        final int chunks;
        final long durationMillis;
        final int hotspots;
        final int highestScore;
        final boolean cancelled;

        LagHistoryEntry(long at, String actor, int chunks, long durationMillis, int hotspots,
                        int highestScore, boolean cancelled) {
            this.at = Math.max(0L, at);
            this.actor = actor == null ? "System" : actor;
            this.chunks = Math.max(0, chunks);
            this.durationMillis = Math.max(0L, durationMillis);
            this.hotspots = Math.max(0, hotspots);
            this.highestScore = Math.max(0, highestScore);
            this.cancelled = cancelled;
        }
    }

    private static final class PlayerControlState {
        final UUID uuid;
        int personalSlowmodeSeconds;
        boolean quarantine;
        Set<String> quarantinePrevious = new LinkedHashSet<>();
        String lastPreset = "";
        Set<String> presetPrevious = new LinkedHashSet<>();

        PlayerControlState(UUID uuid) { this.uuid = uuid; }
    }

    private static final class AltRightsPolicy {
        final UUID source;
        String sourceName = "Unknown";
        boolean enabled;
        boolean future = true;
        boolean includeIpOnly;
        int minimumScore = 70;
        long lastSyncAt;
        String lastSyncBy = "";
        int lastSyncCount;
        final Set<UUID> linkedAccounts = new LinkedHashSet<>();

        AltRightsPolicy(UUID source) { this.source = source; }
    }

    private static final class ActionEntry {
        final long at;
        final String actor;
        final UUID target;
        final String targetName;
        final String category;
        final String action;
        final String details;
        final boolean success;

        ActionEntry(long at, String actor, UUID target, String targetName, String category,
                    String action, String details, boolean success) {
            this.at = Math.max(0L, at);
            this.actor = actor == null ? "System" : actor;
            this.target = target;
            this.targetName = targetName == null ? "" : targetName;
            this.category = category == null ? "SYSTEM" : category;
            this.action = action == null ? "Action" : action;
            this.details = details == null ? "" : details;
            this.success = success;
        }
    }

    private static final class PowerMenu implements InventoryHolder {
        final String type;
        final UUID target;
        final int page;
        final int index;

        PowerMenu(String type, UUID target, int page, int index) {
            this.type = type;
            this.target = target;
            this.page = page;
            this.index = index;
        }

        public Inventory getInventory() { return null; }
    }

    private static final class PendingInput {
        final String action;
        final UUID target;
        PendingInput(String action, UUID target) { this.action = action; this.target = target; }
    }

    private static final class PendingConfirmation {
        final String action;
        final UUID target;
        final String value;
        PendingConfirmation(String action, UUID target, String value) {
            this.action = action;
            this.target = target;
            this.value = value == null ? "" : value;
        }
    }

    private static final class LagChunk {
        final Object chunk;
        final String world;
        final int x;
        final int z;
        LagChunk(Object chunk, String world, int x, int z) {
            this.chunk = chunk;
            this.world = world;
            this.x = x;
            this.z = z;
        }
    }

    private static final class ActiveLagScan {
        final String actor;
        final long startedAt;
        final List<LagChunk> chunks;
        final List<Hotspot> hotspots = new ArrayList<>();
        int cursor;
        boolean cancelled;

        ActiveLagScan(String actor, List<LagChunk> chunks) {
            this.actor = actor;
            this.startedAt = System.currentTimeMillis();
            this.chunks = chunks;
        }
    }

    private final SimplexAdminSuite plugin;
    private final File folder;
    private final File controlsFile;
    private final File policiesFile;
    private final File actionsFile;
    private final File lagHistoryFile;
    private final Map<UUID, PlayerControlState> controls = new LinkedHashMap<>();
    private final Map<UUID, AltRightsPolicy> altPolicies = new LinkedHashMap<>();
    private final List<ActionEntry> actions = new ArrayList<>();
    private final List<LagHistoryEntry> lagHistory = new ArrayList<>();
    private final Map<UUID, PendingInput> pendingInputs = new ConcurrentHashMap<>();
    private final Map<UUID, PendingConfirmation> confirmations = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastPersonalChat = new ConcurrentHashMap<>();
    private volatile List<Hotspot> latestHotspots = List.of();
    private volatile ActiveLagScan activeLagScan;
    private BukkitTask lagTask;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.GERMANY);

    AdminPowerToolsManager(SimplexAdminSuite plugin) {
        this.plugin = plugin;
        this.folder = new File(plugin.getDataFolder(), "admin-6");
        this.controlsFile = new File(folder, "player-controls.yml");
        this.policiesFile = new File(folder, "alt-right-policies.yml");
        this.actionsFile = new File(folder, "action-history.yml");
        this.lagHistoryFile = new File(folder, "lag-inspector-history.yml");
        if (!folder.exists() && !folder.mkdirs()) plugin.getLogger().warning("Admin-6-Datenordner konnte nicht erstellt werden.");
        loadAll();
    }

    synchronized void start() { }

    synchronized void stop() {
        stopLagTask();
        saveAll();
        pendingInputs.clear();
        confirmations.clear();
        lastPersonalChat.clear();
    }

    synchronized void reload() {
        stopLagTask();
        loadAll();
        pendingInputs.clear();
        confirmations.clear();
    }

    boolean isInventory(Inventory inventory) {
        return inventory != null && LiveMenus.holder(inventory) instanceof PowerMenu;
    }

    boolean handleInventoryClick(InventoryClickEvent event) {
        if (event == null || !isInventory(event.getInventory())) return false;
        event.setCancelled(true);
        HumanEntity human = event.getWhoClicked();
        if (!(human instanceof Player)) return true;
        Player admin = (Player) human;
        PowerMenu menu = (PowerMenu) LiveMenus.holder(event.getInventory());
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getInventory().getSize()) return true;
        switch (menu.type) {
            case "dashboard" -> handleDashboard(admin, slot);
            case "player" -> handlePlayer(admin, menu.target, slot);
            case "session" -> handleSession(admin, menu.target, slot);
            case "moderation" -> handleModeration(admin, menu.target, slot);
            case "inventory" -> handleInventoryTools(admin, menu.target, slot);
            case "alt-rights" -> handleAltRights(admin, menu.target, slot);
            case "lag" -> handleLag(admin, slot);
            case "lag-results" -> handleLagResults(admin, menu.page, slot);
            case "lag-history" -> handleLagHistory(admin, menu.page, slot);
            case "discord-design" -> handleDiscordDesign(admin, slot);
            case "actions" -> handleActions(admin, menu.page, slot);
            case "confirm" -> handleConfirmation(admin, slot);
            default -> { }
        }
        return true;
    }

    boolean consumePendingChat(Player player, String message) {
        boolean[] handled={false};plugin.asAdmin(player==null?null:player.getName(),()->handled[0]=consumeAuditedPendingChat(player,message));return handled[0];
    }
    private boolean consumeAuditedPendingChat(Player player, String message) {
        if (player == null) return false;
        PendingInput pending = pendingInputs.remove(player.getUniqueId());
        if (pending == null) return false;
        String input = message == null ? "" : message.trim();
        plugin.runAdminTask( () -> acceptInput(player, pending, input));
        return true;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        pendingInputs.remove(uuid);
        confirmations.remove(uuid);
        lastPersonalChat.remove(uuid);
    }

    void openDashboardJava(Player admin) {
        if (!plugin.adminMenuAllowed(admin, "adminpowertoolsmanager.opendashboardjava")) return;
        if (!require(admin, "simplexadminsuite.powertools")) return;
        Inventory inventory = LiveMenus.create(new PowerMenu("dashboard", null, 0, -1), 54,
                plugin.color("&8Erweiterte Werkzeuge"));
        plugin.fill(inventory);
        inventory.setItem(4, plugin.item(Material.NETHER_STAR, "&bSimplex Admin Suite",
                "&7Erweiterte Spielerkontrolle, Lag-Analyse,",
                "&7Alt-Rechte und Discord-Statusdesign.",
                "&7Gespeicherte Admin-Aktionen: &f" + actionCount()));
        inventory.setItem(10, plugin.item(Material.PLAYER_HEAD, "&aErweiterte Spielerverwaltung",
                "&7Öffne zuerst einen Spieler aus der Datenbank.",
                "&7Dort steht jetzt die erweiterte Spielerkontrolle bereit."));
        inventory.setItem(12, plugin.item(activeLagScan == null ? Material.SPYGLASS : Material.CLOCK,
                "&cLag-Inspector", "&7Budgetierte Analyse bereits geladener Chunks.",
                "&7Keine Chunks werden nachgeladen.", "&7" + lagStatusGerman()));
        inventory.setItem(14, plugin.item(Material.MAP, "&9Discord-Statusdesigner",
                "&7Texte, Felder, TPS-Grenzen und",
                "&7grün/gelb/rote Stabilitätsfarben verwalten."));
        inventory.setItem(16, plugin.item(Material.ENDER_EYE, "&dAlt-Rechtevererbung",
                "&7Pro Spieler in dessen Rechte-Menü aktivierbar.",
                "&7Aktive Richtlinien: &f" + enabledPolicyCount()));
        inventory.setItem(28, plugin.item(Material.BOOK, "&6Admin-Aktionsverlauf",
                "&7Spieleraktionen, Rechte-Sync und Lag-Scans.",
                "&7Maximal 300 vollständig auditierte Einträge."));
        inventory.setItem(30, plugin.item(Material.COMMAND_BLOCK, "&bTeam-Dashboard",
                "&7Aufgaben, Planer, Incidents, Health,",
                "&7Makros und Massenaktionen bleiben erhalten."));
        inventory.setItem(32, plugin.item(Material.COMPARATOR, "&aDiscord-Status jetzt aktualisieren",
                "&7Erstellt sofort eine Vorschau im Statuskanal."));
        inventory.setItem(49, plugin.item(Material.BARRIER, "&cZurück", "&7Zurück zur Hauptverwaltung."));
        LiveMenus.show(admin,inventory);
    }

    private void handleDashboard(Player admin, int slot) {
        if (slot == 10) plugin.powerOpenPlayers(admin);
        else if (slot == 12) openLagInspectorJava(admin);
        else if (slot == 14) openDiscordDesignerJava(admin);
        else if (slot == 16) {
            admin.sendMessage(plugin.prefix() + "Öffne einen Spieler und danach Rechte → Alt-Rechtevererbung.");
            plugin.powerOpenPlayers(admin);
        }
        else if (slot == 28) openActionsJava(admin, 0);
        else if (slot == 30) plugin.powerOpenQol(admin);
        else if (slot == 32) { plugin.powerRefreshDiscord(); plugin.menuNotice(admin,plugin.prefix() + "Discord-Statusaktualisierung wurde angestoßen."); openDashboardJava(admin); }
        else if (slot == 49) plugin.openJavaAdminMain(admin);
    }

    void openPlayerJava(Player admin, UUID targetUuid) {
        if (!plugin.adminMenuAllowed(admin, "adminpowertoolsmanager.openplayerjava")) return;
        if (!require(admin, "simplexadminsuite.powertools.player")) return;
        SimplexAdminSuite.PlayerProfile target = plugin.expansionProfile(targetUuid);
        if (target == null) { plugin.menuNotice(admin,plugin.prefix() + "Spielerprofil nicht gefunden."); return; }
        Player online = Bukkit.getPlayer(targetUuid);
        PlayerControlState state = state(targetUuid);
        AltRightsPolicy policy = policy(targetUuid, false);
        Inventory inventory = LiveMenus.create(new PowerMenu("player", targetUuid, 0, -1), 54,
                plugin.color("&8Spielerkontrolle 7.0: &f" + target.displayName()));
        plugin.fill(inventory);
        inventory.setItem(4, plugin.item(online == null ? Material.SKELETON_SKULL : Material.PLAYER_HEAD,
                (online == null ? "&7" : "&a") + target.displayName(),
                "&7Status: " + (online == null ? "&7Offline" : "&aOnline"),
                "&7Quarantäne: " + (state.quarantine ? "&cAktiv" : "&aInaktiv"),
                "&7Persönlicher Slowmode: &f" + state.personalSlowmodeSeconds + "s",
                "&7Alt-Rechte: " + (policy != null && policy.enabled ? "&aAktiv" : "&7Inaktiv")));
        inventory.setItem(10, plugin.item(Material.ENDER_PEARL, "&aLive-Sitzungswerkzeuge",
                "&7Teleport, Spawn, Heilung, Effekte,",
                "&7Bewegungsreset und Inventar-Sync."));
        inventory.setItem(12, plugin.item(Material.SHIELD, "&cModerationsprofile",
                "&7Quarantäne, Bau-/Chat-/Adventure-Lock",
                "&7mit exakter Wiederherstellung."));
        inventory.setItem(14, plugin.item(Material.ENDER_CHEST, "&dInventar-Sicherheit",
                "&7Snapshots, sichere Leerung, Enderchest,",
                "&7Erfahrung und Statusbereinigung."));
        inventory.setItem(16, plugin.item(Material.ENDER_EYE, "&eAlt-Rechtevererbung",
                "&7Aktuelle und zukünftige starke Alt-Konten",
                "&7übernehmen optional alle Rechte-Sperren."));
        inventory.setItem(19, plugin.item(Material.CLOCK, "&ePersönlicher Chat-Slowmode",
                "&7Aktuell: &f" + state.personalSlowmodeSeconds + " Sekunden",
                "&7Klick: 0 / 2 / 5 / 10 / 30 / 60"));
        inventory.setItem(21, plugin.item(Material.REDSTONE, "&6Klassische Rechte-Matrix",
                "&7Alle " + plugin.powerRights().length + " granularen Rechte öffnen."));
        inventory.setItem(23, plugin.item(Material.BOOK, "&bAdmin-Aktionsakte",
                "&7Gefilterte Verwaltungsaktionen anzeigen."));
        inventory.setItem(25, plugin.item(Material.RECOVERY_COMPASS, "&bKlassische Spielerakte",
                "&7Zurück zu Historien, Warnungen, Strafen",
                "&7und den bisherigen Verwaltungsfunktionen."));
        inventory.setItem(49, plugin.item(Material.BARRIER, "&cZurück", "&7Zurück zur klassischen Spielerverwaltung."));
        LiveMenus.show(admin,inventory);
    }

    private void handlePlayer(Player admin, UUID target, int slot) {
        if (slot == 10) openSessionJava(admin, target);
        else if (slot == 12) openModerationJava(admin, target);
        else if (slot == 14) openInventoryToolsJava(admin, target);
        else if (slot == 16) openAltRightsJava(admin, target);
        else if (slot == 19) { cyclePersonalSlowmode(admin, target); openPlayerJava(admin, target); }
        else if (slot == 21) plugin.powerOpenRights(admin, target);
        else if (slot == 23) openActionsJava(admin, 0);
        else if (slot == 25 || slot == 49) plugin.powerOpenPlayer(admin, target);
    }

    private void openSessionJava(Player admin, UUID targetUuid) {
        if (!plugin.adminMenuAllowed(admin, "adminpowertoolsmanager.opensessionjava")) return;
        if (!require(admin, "simplexadminsuite.powertools.player")) return;
        SimplexAdminSuite.PlayerProfile target = plugin.expansionProfile(targetUuid);
        if (target == null) return;
        boolean online = Bukkit.getPlayer(targetUuid) != null;
        Inventory inventory = LiveMenus.create(new PowerMenu("session", targetUuid, 0, -1), 54,
                plugin.color("&8Live-Werkzeuge: &f" + target.displayName()));
        plugin.fill(inventory);
        inventory.setItem(4, plugin.item(online ? Material.LIME_DYE : Material.GRAY_DYE,
                online ? "&aSpieler ist online" : "&7Spieler ist offline",
                "&7Live-Aktionen prüfen den Status erneut."));
        inventory.setItem(10, plugin.item(Material.ENDER_PEARL, "&aZu Spieler teleportieren"));
        inventory.setItem(11, plugin.item(Material.COMPASS, "&aSpieler herholen"));
        inventory.setItem(12, plugin.item(Material.GRASS_BLOCK, "&aZum Weltspawn senden"));
        inventory.setItem(13, plugin.item(Material.GOLDEN_APPLE, "&aHeilen & sättigen"));
        inventory.setItem(14, plugin.item(Material.MILK_BUCKET, "&bPotion-Effekte entfernen"));
        inventory.setItem(15, plugin.item(Material.WATER_BUCKET, "&bFeuer & Frost entfernen"));
        inventory.setItem(16, plugin.item(Material.FEATHER, "&bBewegungszustand zurücksetzen",
                "&7Fallstrecke und Geschwindigkeit sicher nullen."));
        inventory.setItem(19, plugin.item(Material.CHEST, "&eInventar neu synchronisieren"));
        inventory.setItem(20, plugin.item(Material.PACKED_ICE, "&bFreeze umschalten"));
        inventory.setItem(21, plugin.item(Material.NETHER_STAR, "&dSurvival erzwingen"));
        inventory.setItem(22, plugin.item(Material.SPYGLASS, "&dSpectator erzwingen"));
        inventory.setItem(49, plugin.item(Material.BARRIER, "&cZurück"));
        LiveMenus.show(admin,inventory);
    }

    private void handleSession(Player admin, UUID target, int slot) {
        if (slot == 10) plugin.powerTeleportTo(admin, target);
        else if (slot == 11) plugin.powerBring(admin, target);
        else if (slot == 12) sendToSpawn(admin, target);
        else if (slot == 13) healAndFeed(admin, target);
        else if (slot == 14) clearEffects(admin, target);
        else if (slot == 15) extinguish(admin, target);
        else if (slot == 16) resetMovement(admin, target);
        else if (slot == 19) synchronizeInventory(admin, target);
        else if (slot == 20) toggleFreeze(admin, target);
        else if (slot == 21) plugin.powerSetGameMode(admin, target, "survival");
        else if (slot == 22) plugin.powerSetGameMode(admin, target, "spectator");
        else if (slot == 49) { openPlayerJava(admin, target); return; }
        openSessionJava(admin, target);
    }

    private void openModerationJava(Player admin, UUID targetUuid) {
        if (!plugin.adminMenuAllowed(admin, "adminpowertoolsmanager.openmoderationjava")) return;
        if (!require(admin, "simplexadminsuite.powertools.moderation")) return;
        SimplexAdminSuite.PlayerProfile target = plugin.expansionProfile(targetUuid);
        if (target == null) return;
        PlayerControlState state = state(targetUuid);
        Inventory inventory = LiveMenus.create(new PowerMenu("moderation", targetUuid, 0, -1), 54,
                plugin.color("&8Moderationsprofile: &f" + target.displayName()));
        plugin.fill(inventory);
        inventory.setItem(4, plugin.item(state.quarantine ? Material.RED_DYE : Material.LIME_DYE,
                state.quarantine ? "&cQuarantäne aktiv" : "&aKeine Quarantäne",
                "&7Vorheriger Zustand wird vollständig gespeichert."));
        inventory.setItem(10, plugin.item(state.quarantine ? Material.LIME_DYE : Material.IRON_BARS,
                state.quarantine ? "&aQuarantäne exakt aufheben" : "&cSpieler quarantänisieren",
                "&7Sperrt Bewegung, Chat, Befehle, Interaktion,",
                "&7Items, Kampf und gefährliche Mechaniken.",
                "&7Beim Aufheben wird der Vorzustand restauriert."));
        inventory.setItem(12, plugin.item(Material.DIAMOND_PICKAXE, "&6Bau-Lock anwenden",
                "&7Bauen, Abbauen, Interaktion, Redstone,",
                "&7Feuer und Explosives sperren."));
        inventory.setItem(14, plugin.item(Material.WRITABLE_BOOK, "&6Chat-Kontrolle anwenden",
                "&7Chat, Privatnachrichten, Erwähnungen",
                "&7und normale Befehle sperren."));
        inventory.setItem(16, plugin.item(Material.DIAMOND_SWORD, "&6Adventure-Lock anwenden",
                "&7PvP, Tiere, Fahrzeuge, Projektile,",
                "&7Tränke, Elytra und Dimensionen sperren."));
        inventory.setItem(20, plugin.item(Material.TOTEM_OF_UNDYING, "&aLetztes Profil wiederherstellen",
                "&7Letztes Moderationsprofil: &f" + (state.lastPreset.isBlank() ? "Keines" : state.lastPreset),
                "&7Stellt die exakte Rechte-Matrix davor wieder her."));
        inventory.setItem(22, plugin.item(Material.CLOCK, "&ePersönlichen Slowmode wechseln",
                "&7Aktuell: &f" + state.personalSlowmodeSeconds + " Sekunden"));
        inventory.setItem(24, plugin.item(Material.IRON_DOOR, "&cSpieler sicher trennen",
                "&7Feste englische Nachricht und Bestätigung."));
        inventory.setItem(49, plugin.item(Material.BARRIER, "&cZurück"));
        LiveMenus.show(admin,inventory);
    }

    private void handleModeration(Player admin, UUID target, int slot) {
        if (slot == 10) confirm(admin, "quarantine", target, "", "Quarantäne umschalten",
                "Rechte-Zustand sicher speichern bzw. exakt wiederherstellen?");
        else if (slot == 12) confirm(admin, "preset", target, ModerationPreset.BUILD_LOCK.name(), "Bau-Lock anwenden", "Rechte-Matrix speichern und Bau-Lock aktivieren?");
        else if (slot == 14) confirm(admin, "preset", target, ModerationPreset.CHAT_CONTROL.name(), "Chat-Kontrolle anwenden", "Rechte-Matrix speichern und Chat-Kontrolle aktivieren?");
        else if (slot == 16) confirm(admin, "preset", target, ModerationPreset.ADVENTURE_LOCK.name(), "Adventure-Lock anwenden", "Rechte-Matrix speichern und Adventure-Lock aktivieren?");
        else if (slot == 20) confirm(admin, "restore-preset", target, "", "Moderationsprofil restaurieren", "Exakten Zustand vor dem letzten Profil wiederherstellen?");
        else if (slot == 22) { cyclePersonalSlowmode(admin, target); openModerationJava(admin, target); }
        else if (slot == 24) confirm(admin, "disconnect", target, "", "Spieler trennen", "Spieler jetzt mit englischer Staff-Nachricht entfernen?");
        else if (slot == 49) openPlayerJava(admin, target);
    }

    private void openInventoryToolsJava(Player admin, UUID targetUuid) {
        if (!plugin.adminMenuAllowed(admin, "adminpowertoolsmanager.openinventorytoolsjava")) return;
        if (!require(admin, "simplexadminsuite.powertools.inventory")) return;
        SimplexAdminSuite.PlayerProfile target = plugin.expansionProfile(targetUuid);
        if (target == null) return;
        boolean online = Bukkit.getPlayer(targetUuid) != null;
        Inventory inventory = LiveMenus.create(new PowerMenu("inventory", targetUuid, 0, -1), 54,
                plugin.color("&8Inventar-Sicherheit: &f" + target.displayName()));
        plugin.fill(inventory);
        inventory.setItem(4, plugin.item(online ? Material.LIME_DYE : Material.GRAY_DYE,
                online ? "&aLive-Aktionen verfügbar" : "&7Spieler ist offline",
                "&7Löschaktionen erstellen immer zuerst einen Snapshot."));
        inventory.setItem(10, plugin.item(Material.ENDER_CHEST, "&dSicherheits-Snapshot erstellen",
                "&7Inventar, Enderchest und Spielerstatus speichern."));
        inventory.setItem(12, plugin.item(Material.LAVA_BUCKET, "&cInventar sicher leeren",
                "&7Erstellt vorher automatisch einen Snapshot.", "&cMit Sicherheitsabfrage."));
        inventory.setItem(14, plugin.item(Material.LAVA_BUCKET, "&cEnderchest sicher leeren",
                "&7Erstellt vorher automatisch einen Snapshot.", "&cMit Sicherheitsabfrage."));
        inventory.setItem(16, plugin.item(Material.EXPERIENCE_BOTTLE, "&cErfahrung zurücksetzen",
                "&7Level, Fortschritt und Gesamt-XP auf null.", "&cMit Sicherheitsabfrage."));
        inventory.setItem(19, plugin.item(Material.MILK_BUCKET, "&bEffekte entfernen"));
        inventory.setItem(21, plugin.item(Material.WATER_BUCKET, "&bFeuer/Frost entfernen"));
        inventory.setItem(23, plugin.item(Material.CHEST, "&eClient-Inventar synchronisieren"));
        inventory.setItem(49, plugin.item(Material.BARRIER, "&cZurück"));
        LiveMenus.show(admin,inventory);
    }

    private void handleInventoryTools(Player admin, UUID target, int slot) {
        if (slot == 10) { plugin.powerCreateSnapshot(admin, target); openInventoryToolsJava(admin, target); }
        else if (slot == 12) confirm(admin, "clear-inventory", target, "", "Inventar sicher leeren", "Snapshot erstellen und gesamtes Spielerinventar leeren?");
        else if (slot == 14) confirm(admin, "clear-ender", target, "", "Enderchest sicher leeren", "Snapshot erstellen und gesamte Enderchest leeren?");
        else if (slot == 16) confirm(admin, "reset-xp", target, "", "Erfahrung zurücksetzen", "Level und gesamte Erfahrung unwiderruflich auf null setzen?");
        else if (slot == 19) { clearEffects(admin, target); openInventoryToolsJava(admin, target); }
        else if (slot == 21) { extinguish(admin, target); openInventoryToolsJava(admin, target); }
        else if (slot == 23) { synchronizeInventory(admin, target); openInventoryToolsJava(admin, target); }
        else if (slot == 49) openPlayerJava(admin, target);
    }

    void openAltRightsJava(Player admin, UUID targetUuid) {
        if (!plugin.adminMenuAllowed(admin, "adminpowertoolsmanager.openaltrightsjava")) return;
        if (!require(admin, "simplexadminsuite.alt-rights")) return;
        SimplexAdminSuite.PlayerProfile target = plugin.expansionProfile(targetUuid);
        if (target == null) return;
        AltRightsPolicy policy = policy(targetUuid, true);
        policy.sourceName = target.displayName();
        Inventory inventory = LiveMenus.create(new PowerMenu("alt-rights", targetUuid, 0, -1), 54,
                plugin.color("&8Alt-Rechte: &f" + target.displayName()));
        plugin.fill(inventory);
        inventory.setItem(4, plugin.item(policy.enabled ? Material.LIME_DYE : Material.GRAY_DYE,
                policy.enabled ? "&aRechtevererbung aktiv" : "&7Rechtevererbung inaktiv",
                "&7Letzter Sync: &f" + (policy.lastSyncAt <= 0L ? "Nie" : format(policy.lastSyncAt)),
                "&7Konten zuletzt synchronisiert: &f" + policy.lastSyncCount));
        inventory.setItem(10, plugin.item(policy.enabled ? Material.RED_DYE : Material.LIME_DYE,
                policy.enabled ? "&cRechtevererbung deaktivieren" : "&aRechtevererbung aktivieren",
                "&7Spiegelt alle " + plugin.powerRights().length + " Suite-Rechte inklusive Laufzeiten auf sichere Alt-Konten."));
        inventory.setItem(12, plugin.item(Material.CLOCK, "&eMindestscore wechseln",
                "&7Aktuell: &f" + policy.minimumScore,
                "&7Klick: 70 / 85 / 95 / 100"));
        inventory.setItem(14, plugin.item(policy.future ? Material.LIME_DYE : Material.GRAY_DYE,
                "&eZukünftige Konten: " + (policy.future ? "&aJa" : "&cNein"),
                "&7Wird bei neuen starken Identitätstreffern angewendet."));
        inventory.setItem(16, plugin.item(policy.includeIpOnly ? Material.ORANGE_DYE : Material.LIME_DYE,
                "&eNur-IP-Treffer: " + (policy.includeIpOnly ? "&6Erlaubt" : "&aAusgeschlossen"),
                "&7Standard: Nur Geräte-ID, XUID, Self-Signed",
                "&7oder manuell bestätigte Verknüpfungen.",
                "&cIP-Opt-in kann Haushaltskonten treffen."));
        inventory.setItem(20, plugin.item(Material.COMPARATOR, "&bJetzt vollständig synchronisieren",
                "&7Kopiert die aktuelle Rechte-Matrix sofort",
                "&7auf alle passenden bestehenden Konten."));
        inventory.setItem(22, plugin.item(Material.BOOK, "&6Verknüpfte Konten",
                "&7Bisher von dieser Richtlinie erfasst: &f" + policy.linkedAccounts.size(),
                "&7Letzter Akteur: &f" + empty(policy.lastSyncBy, "Niemand")));
        inventory.setItem(49, plugin.item(Material.BARRIER, "&cZurück"));
        LiveMenus.show(admin,inventory);
    }

    private void handleAltRights(Player admin, UUID target, int slot) {
        AltRightsPolicy policy = policy(target, true);
        if (slot == 10) { policy.enabled = !policy.enabled; savePolicies(); record(admin.getName(), target, name(target), "ALT_RIGHTS", policy.enabled ? "Rechtevererbung aktiviert" : "Rechtevererbung deaktiviert", "Score=" + policy.minimumScore, true); if (policy.enabled) syncAltRights(admin, target, true); openAltRightsJava(admin, target); }
        else if (slot == 12) { int[] values = {70,85,95,100}; policy.minimumScore = next(policy.minimumScore, values); savePolicies(); openAltRightsJava(admin, target); }
        else if (slot == 14) { policy.future = !policy.future; savePolicies(); openAltRightsJava(admin, target); }
        else if (slot == 16) { policy.includeIpOnly = !policy.includeIpOnly; savePolicies(); openAltRightsJava(admin, target); }
        else if (slot == 20) { syncAltRights(admin, target, true); openAltRightsJava(admin, target); }
        else if (slot == 22) sendLinkedAccounts(admin, policy);
        else if (slot == 49) openPlayerJava(admin, target);
    }

    void openLagInspectorJava(Player admin) {
        if (!plugin.adminMenuAllowed(admin, "adminpowertoolsmanager.openlaginspectorjava")) return;
        if (!requireOperator(admin, "simplexadminsuite.laginspector")) return;
        Inventory inventory = LiveMenus.create(new PowerMenu("lag", null, 0, -1), 54,
                plugin.color("&8Lag-Inspector 7.0"));
        plugin.fill(inventory);
        ActiveLagScan scan = activeLagScan;
        String progress = scan == null ? "Kein Scan aktiv" : scan.cursor + "/" + scan.chunks.size() + " Chunks (" + lagProgress() + "%)";
        inventory.setItem(4, plugin.item(scan == null ? Material.LIME_DYE : Material.CLOCK,
                scan == null ? "&aBereit" : "&eScan läuft", "&7" + progress,
                "&7Analyse nur geladener Chunks, ohne Nachladen."));
        inventory.setItem(10, plugin.item(Material.SPYGLASS, "&aNeuen Lag-Scan starten",
                "&7Alle geladenen Welten budgetiert prüfen.",
                "&7Pro Tick: &f" + chunksPerTick() + " Chunks / " + tickBudgetMillis() + " ms"));
        inventory.setItem(12, plugin.item(Material.BARRIER, "&cAktiven Scan abbrechen",
                "&7Bereits gefundene Resultate bleiben sichtbar."));
        inventory.setItem(14, plugin.item(Material.MAP, "&bAktuelle Hotspots",
                "&7Gespeichert: &f" + latestHotspots.size(),
                "&7Warnschwelle: &f" + warningLagScore(),
                "&7Kritisch: &f" + criticalLagScore()));
        inventory.setItem(16, plugin.item(Material.BOOK, "&6Scan-Verlauf",
                "&7Letzte Scans: &f" + lagHistory.size() + "/100"));
        inventory.setItem(28, plugin.item(Material.HOPPER, "&eErkannte Lastquellen",
                "&7Hopper, Minecarts, Item-Entitäten, Villager,",
                "&7Monster und Block-Entitäten pro Chunk.",
                "&7Keine teure Vollblocksuche."));
        inventory.setItem(30, plugin.item(Material.COMPARATOR, "&bStatus aktualisieren",
                "&7Fortschritt und Ergebnisse neu laden."));
        inventory.setItem(49, plugin.item(Material.BARRIER, "&cZurück"));
        LiveMenus.show(admin,inventory);
    }

    private void handleLag(Player admin, int slot) {
        if (slot == 10) { startLagScan(admin); openLagInspectorJava(admin); }
        else if (slot == 12) { cancelLagScan(admin); openLagInspectorJava(admin); }
        else if (slot == 14) openLagResultsJava(admin, 0);
        else if (slot == 16) openLagHistoryJava(admin, 0);
        else if (slot == 30) openLagInspectorJava(admin);
        else if (slot == 49) openDashboardJava(admin);
    }

    void openLagResultsJava(Player admin, int requestedPage) {
        if (!plugin.adminMenuAllowed(admin, "adminpowertoolsmanager.openlagresultsjava")) return;
        if (!requireOperator(admin, "simplexadminsuite.laginspector")) return;
        List<Hotspot> hotspots = latestHotspots;
        int maxPage = Math.max(0, (hotspots.size() - 1) / 45);
        int page = Math.max(0, Math.min(requestedPage, maxPage));
        Inventory inventory = LiveMenus.create(new PowerMenu("lag-results", null, page, -1), 54,
                plugin.color("&8Lag-Hotspots &7" + (page + 1) + "/" + (maxPage + 1)));
        plugin.fill(inventory);
        for (int i = 0; i < 45; i++) {
            int index = page * 45 + i;
            if (index >= hotspots.size()) break;
            Hotspot h = hotspots.get(index);
            inventory.setItem(i, plugin.item(lagMaterial(h.score), lagColor(h.score) + "#" + (index + 1) + " " + h.world + " " + h.chunkX + "/" + h.chunkZ,
                    "&7Score: &f" + h.score + " &8• &7Grund: &f" + h.reason,
                    "&7Entitäten: &f" + h.entities + " &8• &7Block-Entitäten: &f" + h.blockEntities,
                    "&7Hopper: &f" + h.hoppers + " &8• &7Minecarts: &f" + h.minecarts + " &8• &7Items: &f" + h.items,
                    "&eKlick teleportiert nur, wenn der Chunk noch geladen ist."));
        }
        inventory.setItem(45, plugin.item(Material.ARROW, "&eVorherige Seite"));
        inventory.setItem(49, plugin.item(Material.BARRIER, "&cZurück"));
        inventory.setItem(53, plugin.item(Material.ARROW, "&eNächste Seite"));
        LiveMenus.show(admin,inventory);
    }

    private void handleLagResults(Player admin, int page, int slot) {
        if (slot == 45 && page > 0) openLagResultsJava(admin, page - 1);
        else if (slot == 53) openLagResultsJava(admin, page + 1);
        else if (slot == 49) openLagInspectorJava(admin);
        else if (slot >= 0 && slot < 45) {
            int index = page * 45 + slot;
            teleportToHotspot(admin, index);
            openLagResultsJava(admin, page);
        }
    }

    private void openLagHistoryJava(Player admin, int requestedPage) {
        if (!plugin.adminMenuAllowed(admin, "adminpowertoolsmanager.openlaghistoryjava")) return;
        if (!requireOperator(admin, "simplexadminsuite.laginspector")) return;
        int maxPage = Math.max(0, (lagHistory.size() - 1) / 45);
        int page = Math.max(0, Math.min(requestedPage, maxPage));
        Inventory inventory = LiveMenus.create(new PowerMenu("lag-history", null, page, -1), 54,
                plugin.color("&8Lag-Scan-Verlauf &7" + (page + 1) + "/" + (maxPage + 1)));
        plugin.fill(inventory);
        synchronized (this) {
            for (int i = 0; i < 45; i++) {
                int index = page * 45 + i;
                if (index >= lagHistory.size()) break;
                LagHistoryEntry h = lagHistory.get(index);
                inventory.setItem(i, plugin.item(h.cancelled ? Material.GRAY_DYE : lagMaterial(h.highestScore),
                        (h.cancelled ? "&7" : lagColor(h.highestScore)) + format(h.at),
                        "&7Akteur: &f" + h.actor, "&7Chunks: &f" + h.chunks + " &8• &7Dauer: &f" + h.durationMillis + " ms",
                        "&7Hotspots: &f" + h.hotspots + " &8• &7Höchster Score: &f" + h.highestScore,
                        "&7Status: &f" + (h.cancelled ? "Abgebrochen" : "Abgeschlossen")));
            }
        }
        inventory.setItem(45, plugin.item(Material.ARROW, "&eVorherige Seite"));
        inventory.setItem(49, plugin.item(Material.BARRIER, "&cZurück"));
        inventory.setItem(53, plugin.item(Material.ARROW, "&eNächste Seite"));
        LiveMenus.show(admin,inventory);
    }

    private void handleLagHistory(Player admin, int page, int slot) {
        if (slot == 45 && page > 0) openLagHistoryJava(admin, page - 1);
        else if (slot == 53) openLagHistoryJava(admin, page + 1);
        else if (slot == 49) openLagInspectorJava(admin);
    }

    void openDiscordDesignerJava(Player admin) {
        if (!plugin.adminMenuAllowed(admin, "adminpowertoolsmanager.opendiscorddesignerjava")) return;
        if (!require(admin, "simplexadminsuite.discord.design")) return;
        DiscordDesignInput d = discordDesign();
        Inventory inventory = LiveMenus.create(new PowerMenu("discord-design", null, 0, -1), 54,
                plugin.color("&8Discord-Statusdesigner 7.0"));
        plugin.fill(inventory);
        inventory.setItem(4, plugin.item(Material.MAP, "&9Live-Statusdesign",
                "&7Schwellen: &eWarnung " + decimal(d.warningTps) + " TPS &8• &cKritisch " + decimal(d.criticalTps) + " TPS",
                "&7Farben: &a" + d.healthyColor + " &8/ &e" + d.warningColor + " &8/ &c" + d.criticalColor));
        inventory.setItem(10, plugin.item(Material.NAME_TAG, "&eServername setzen", "&7Aktuell: &f" + d.serverName));
        inventory.setItem(11, plugin.item(Material.LIME_DYE, "&aOnline-Titel setzen", "&7" + shorten(d.onlineTitle, 70)));
        inventory.setItem(12, plugin.item(Material.ORANGE_DYE, "&eWarnungs-Titel setzen", "&7" + shorten(d.warningTitle, 70)));
        inventory.setItem(13, plugin.item(Material.RED_DYE, "&cKritischen Titel setzen", "&7" + shorten(d.criticalTitle, 70)));
        inventory.setItem(14, plugin.item(Material.ORANGE_DYE, "&6Neustart-Titel setzen", "&7" + shorten(d.restartingTitle, 70)));
        inventory.setItem(15, plugin.item(Material.GRAY_DYE, "&7Offline-Titel setzen", "&7" + shorten(d.offlineTitle, 70)));
        inventory.setItem(19, plugin.item(Material.PAPER, "&aOnline-Beschreibung setzen", "&7" + shorten(d.onlineDescription, 80)));
        inventory.setItem(20, plugin.item(Material.PAPER, "&eWarnungs-Beschreibung setzen", "&7" + shorten(d.warningDescription, 80)));
        inventory.setItem(21, plugin.item(Material.PAPER, "&cKritische Beschreibung setzen", "&7" + shorten(d.criticalDescription, 80)));
        inventory.setItem(22, plugin.item(Material.BOOK, "&bFooter setzen", "&7" + shorten(d.footer, 80)));
        inventory.setItem(23, plugin.item(Material.PAPER, "&6Neustart-Beschreibung setzen", "&7" + shorten(d.restartingDescription, 80)));
        inventory.setItem(24, plugin.item(Material.PAPER, "&7Offline-Beschreibung setzen", "&7" + shorten(d.offlineDescription, 80)));
        inventory.setItem(28, plugin.item(Material.CLOCK, "&eTPS-Warnschwelle wechseln", "&7Aktuell: &f" + decimal(d.warningTps), "&7Klick: 19.5 / 19.0 / 18.5 / 18.0 / 17.0"));
        inventory.setItem(29, plugin.item(Material.CLOCK, "&cTPS-Kritischschwelle wechseln", "&7Aktuell: &f" + decimal(d.criticalTps), "&7Klick: 18.0 / 17.0 / 16.0 / 15.0 / 12.0"));
        inventory.setItem(30, plugin.item(Material.LIME_DYE, "&aGesund-Farbe wechseln", "&7Aktuell: &f" + d.healthyColor));
        inventory.setItem(31, plugin.item(Material.ORANGE_DYE, "&eWarnfarbe wechseln", "&7Aktuell: &f" + d.warningColor));
        inventory.setItem(32, plugin.item(Material.RED_DYE, "&cKritisch-Farbe wechseln", "&7Aktuell: &f" + d.criticalColor));
        inventory.setItem(33, plugin.item(Material.ORANGE_DYE, "&6Neustart-Farbe wechseln", "&7Aktuell: &f" + d.restartingColor));
        inventory.setItem(34, toggleItem(d.showPlayerCount, "Spielerzahl"));
        inventory.setItem(35, toggleItem(d.showPlayerList, "Spielerliste"));
        inventory.setItem(36, toggleItem(d.showPlatform, "Java-/Bedrock-Plattform"));
        inventory.setItem(37, toggleItem(d.showTps, "TPS-Feld"));
        inventory.setItem(38, toggleItem(d.showStability, "Stabilitätsfeld"));
        inventory.setItem(39, toggleItem(d.showLeaderboard, "Points-Top-10"));
        inventory.setItem(40, plugin.item(Material.GRAY_DYE, "&7Offline-Farbe wechseln", "&7Aktuell: &f" + d.offlineColor));
        inventory.setItem(41, plugin.item(Material.COMPARATOR, "&aJetzt aktualisieren", "&7Status sofort mit aktuellen Werten neu rendern."));
        inventory.setItem(42, plugin.item(Material.TOTEM_OF_UNDYING, "&6Standarddesign wiederherstellen", "&cMit Sicherheitsabfrage."));
        inventory.setItem(49, plugin.item(Material.BARRIER, "&cZurück"));
        LiveMenus.show(admin,inventory);
    }

    private void handleDiscordDesign(Player admin, int slot) {
        if (slot == 10) ask(admin, "status:server-name", "Gib den Servernamen ein. Platzhalter in Titeln: {server}. 'abbrechen' beendet die Eingabe.");
        else if (slot == 11) ask(admin, "status:online-title", "Gib den englischen Online-Titel ein. {server} ist erlaubt.");
        else if (slot == 12) ask(admin, "status:warning-title", "Gib den englischen Warnungs-Titel ein. {server} ist erlaubt.");
        else if (slot == 13) ask(admin, "status:critical-title", "Gib den englischen kritischen Titel ein. {server} ist erlaubt.");
        else if (slot == 14) ask(admin, "status:restarting-title", "Gib den englischen Neustart-Titel ein. {server} ist erlaubt.");
        else if (slot == 15) ask(admin, "status:offline-title", "Gib den englischen Offline-Titel ein. {server} ist erlaubt.");
        else if (slot == 19) ask(admin, "status:online-description", "Gib die englische Online-Beschreibung ein.");
        else if (slot == 20) ask(admin, "status:warning-description", "Gib die englische Warnungs-Beschreibung ein.");
        else if (slot == 21) ask(admin, "status:critical-description", "Gib die englische kritische Beschreibung ein.");
        else if (slot == 22) ask(admin, "status:footer", "Gib den englischen Footer ein.");
        else if (slot == 23) ask(admin, "status:restarting-description", "Gib die englische Neustart-Beschreibung ein. {seconds} ist erlaubt.");
        else if (slot == 24) ask(admin, "status:offline-description", "Gib die englische Offline-Beschreibung ein.");
        else if (slot == 28) { cycleDouble("discord-bot.status.design.warning-tps", new double[]{19.5,19.0,18.5,18.0,17.0}); normalizeThresholds(); openDiscordDesignerJava(admin); }
        else if (slot == 29) { cycleDouble("discord-bot.status.design.critical-tps", new double[]{18.0,17.0,16.0,15.0,12.0}); normalizeThresholds(); openDiscordDesignerJava(admin); }
        else if (slot == 30) { cycleColor("discord-bot.status.design.healthy-color", new String[]{"#57F287","#2ECC71","#1ABC9C","#3498DB"}); openDiscordDesignerJava(admin); }
        else if (slot == 31) { cycleColor("discord-bot.status.design.warning-color", new String[]{"#FEE75C","#F1C40F","#E67E22","#FAA61A"}); openDiscordDesignerJava(admin); }
        else if (slot == 32) { cycleColor("discord-bot.status.design.critical-color", new String[]{"#ED4245","#E74C3C","#C0392B","#992D22"}); openDiscordDesignerJava(admin); }
        else if (slot == 33) { cycleColor("discord-bot.status.design.restarting-color", new String[]{"#FEE75C","#FAA61A","#E67E22","#9B59B6"}); openDiscordDesignerJava(admin); }
        else if (slot >= 34 && slot <= 39) {
            String[] keys = {"show-player-count","show-player-list","show-platform","show-tps","show-stability","show-leaderboard"};
            String path = "discord-bot.status.design." + keys[slot - 34];
            boolean fallback = slot != 39;
            plugin.getConfig().set(path, !plugin.getConfig().getBoolean(path, fallback));
            plugin.saveConfig(); plugin.powerRefreshDiscord(); openDiscordDesignerJava(admin);
        }
        else if (slot == 40) { cycleColor("discord-bot.status.design.offline-color", new String[]{"#ED4245","#992D22","#747F8D","#2F3136"}); openDiscordDesignerJava(admin); }
        else if (slot == 41) { plugin.powerRefreshDiscord(); plugin.menuNotice(admin,plugin.prefix() + "Discord-Status wurde mit dem aktuellen Design aktualisiert."); openDiscordDesignerJava(admin); }
        else if (slot == 42) confirm(admin, "reset-discord-design", null, "", "Statusdesign zurücksetzen", "Alle Texte, Farben, Felder und TPS-Grenzen auf den 7.0-Standard setzen?");
        else if (slot == 49) openDashboardJava(admin);
    }

    private void openActionsJava(Player admin, int requestedPage) {
        if (!plugin.adminMenuAllowed(admin, "adminpowertoolsmanager.openactionsjava")) return;
        if (!require(admin, "simplexadminsuite.powertools.history")) return;
        List<ActionEntry> snapshot;
        synchronized (this) { snapshot = new ArrayList<>(actions); }
        int maxPage = Math.max(0, (snapshot.size() - 1) / 45);
        int page = Math.max(0, Math.min(requestedPage, maxPage));
        Inventory inventory = LiveMenus.create(new PowerMenu("actions", null, page, -1), 54,
                plugin.color("&8Admin-Chronik &7" + (page + 1) + "/" + (maxPage + 1)));
        plugin.fill(inventory);
        for (int i = 0; i < 45; i++) {
            int index = page * 45 + i;
            if (index >= snapshot.size()) break;
            ActionEntry entry = snapshot.get(index);
            inventory.setItem(i, plugin.item(entry.success ? Material.LIME_DYE : Material.RED_DYE,
                    (entry.success ? "&a" : "&c") + entry.action,
                    "&7Zeit: &f" + format(entry.at), "&7Akteur: &f" + entry.actor,
                    "&7Ziel: &f" + empty(entry.targetName, "Server"), "&7Kategorie: &f" + entry.category,
                    "&7" + shorten(entry.details, 100)));
        }
        inventory.setItem(45, plugin.item(Material.ARROW, "&eVorherige Seite"));
        inventory.setItem(49, plugin.item(Material.BARRIER, "&cZurück"));
        inventory.setItem(53, plugin.item(Material.ARROW, "&eNächste Seite"));
        LiveMenus.show(admin,inventory);
    }

    private void handleActions(Player admin, int page, int slot) {
        if (slot == 45 && page > 0) openActionsJava(admin, page - 1);
        else if (slot == 53) openActionsJava(admin, page + 1);
        else if (slot == 49) openDashboardJava(admin);
    }

    private void confirm(Player admin, String action, UUID target, String value, String title, String details) {
        confirmations.put(admin.getUniqueId(), new PendingConfirmation(action, target, value));
        Inventory inventory = LiveMenus.create(new PowerMenu("confirm", target, 0, -1), 27,
                plugin.color("&8Bestätigen: &f" + title));
        plugin.fill(inventory);
        inventory.setItem(4, plugin.item(Material.PAPER, "&e" + title, "&7" + shorten(details, 120)));
        inventory.setItem(11, plugin.item(Material.LIME_DYE, "&aVerbindlich ausführen"));
        inventory.setItem(15, plugin.item(Material.RED_DYE, "&cAbbrechen"));
        LiveMenus.show(admin,inventory);
    }

    private void handleConfirmation(Player admin, int slot) {
        PendingConfirmation pending = confirmations.remove(admin.getUniqueId());
        if (pending == null) { openDashboardJava(admin); return; }
        if (slot != 11) { returnAfterConfirmation(admin, pending); return; }
        switch (pending.action) {
            case "quarantine" -> toggleQuarantine(admin, pending.target);
            case "preset" -> applyPreset(admin, pending.target, parsePreset(pending.value));
            case "restore-preset" -> restorePreset(admin, pending.target);
            case "disconnect" -> disconnectPlayer(admin, pending.target);
            case "clear-inventory" -> clearInventorySafely(admin, pending.target, false);
            case "clear-ender" -> clearInventorySafely(admin, pending.target, true);
            case "reset-xp" -> resetExperience(admin, pending.target);
            case "reset-discord-design" -> resetDiscordDesign(admin);
            default -> { }
        }
        returnAfterConfirmation(admin, pending);
    }

    private void returnAfterConfirmation(Player admin, PendingConfirmation pending) {
        if (pending.action.startsWith("clear-") || pending.action.equals("reset-xp")) openInventoryToolsJava(admin, pending.target);
        else if (pending.action.contains("discord")) openDiscordDesignerJava(admin);
        else if (pending.target != null) openModerationJava(admin, pending.target);
        else openDashboardJava(admin);
    }

    void confirmBedrockAction(Player admin, String action, UUID target, String value) {
        switch (action) {
            case "quarantine" -> toggleQuarantine(admin, target);
            case "preset-build" -> applyPreset(admin, target, ModerationPreset.BUILD_LOCK);
            case "preset-chat" -> applyPreset(admin, target, ModerationPreset.CHAT_CONTROL);
            case "preset-adventure" -> applyPreset(admin, target, ModerationPreset.ADVENTURE_LOCK);
            case "restore-preset" -> restorePreset(admin, target);
            case "disconnect" -> disconnectPlayer(admin, target);
            case "clear-inventory" -> clearInventorySafely(admin, target, false);
            case "clear-ender" -> clearInventorySafely(admin, target, true);
            case "reset-xp" -> resetExperience(admin, target);
            case "reset-discord-design" -> resetDiscordDesign(admin);
            default -> plugin.menuNotice(admin,plugin.prefix() + "Unbekannte Admin-Aktion wurde sicher abgebrochen.");
        }
    }

    long personalSlowmodeRemaining(Player player) {
        if (player == null || plugin.has(player, "simplexadminsuite.slowmode.bypass")) return 0L;
        PlayerControlState state;
        synchronized (this) { state = controls.get(player.getUniqueId()); }
        if (state == null || state.personalSlowmodeSeconds <= 0) return 0L;
        Long last = lastPersonalChat.get(player.getUniqueId());
        if (last == null) return 0L;
        long remaining = state.personalSlowmodeSeconds * 1000L - (System.currentTimeMillis() - last);
        return remaining <= 0L ? 0L : (remaining + 999L) / 1000L;
    }

    void markPersonalChat(Player player) {
        if (player == null) return;
        PlayerControlState state;
        synchronized (this) { state = controls.get(player.getUniqueId()); }
        if (state != null && state.personalSlowmodeSeconds > 0) lastPersonalChat.put(player.getUniqueId(), System.currentTimeMillis());
    }

    synchronized int personalSlowmode(UUID uuid) {
        PlayerControlState state = controls.get(uuid);
        return state == null ? 0 : state.personalSlowmodeSeconds;
    }

    void cyclePersonalSlowmode(Player admin, UUID targetUuid) {
        if (!require(admin, "simplexadminsuite.powertools.moderation")) return;
        setPersonalSlowmode(admin, targetUuid, next(personalSlowmode(targetUuid), new int[]{0,2,5,10,30,60}));
    }

    void setPersonalSlowmode(Player admin, UUID targetUuid, int seconds) {
        if (!require(admin, "simplexadminsuite.powertools.moderation")) return;
        SimplexAdminSuite.PlayerProfile target = plugin.expansionProfile(targetUuid);
        if (target == null) return;
        int[] allowed = {0,2,5,10,30,60};
        boolean valid = false;
        for (int value : allowed) if (value == seconds) { valid = true; break; }
        if (!valid) {
            admin.sendMessage(plugin.prefix() + "Erlaubte persönliche Slowmodes: 0, 2, 5, 10, 30 oder 60 Sekunden.");
            return;
        }
        PlayerControlState state = state(targetUuid);
        state.personalSlowmodeSeconds = seconds;
        if (state.personalSlowmodeSeconds <= 0) lastPersonalChat.remove(targetUuid);
        saveControls();
        record(admin.getName(), targetUuid, target.displayName(), "CHAT", "Persönlicher Slowmode", state.personalSlowmodeSeconds + " Sekunden", true);
        plugin.powerAudit(admin.getName(), targetUuid, "Persönlicher Chat-Slowmode", state.personalSlowmodeSeconds + " Sekunden");
        Player online = Bukkit.getPlayer(targetUuid);
        if (online != null) online.sendMessage(plugin.prefix() + (state.personalSlowmodeSeconds <= 0
                ? "Your personal chat cooldown has been removed by staff."
                : "Your personal chat cooldown is now " + state.personalSlowmodeSeconds + " seconds."));
        admin.sendMessage(plugin.prefix() + "Persönlicher Slowmode für " + target.displayName() + ": " + state.personalSlowmodeSeconds + " Sekunden.");
    }

    void toggleQuarantine(Player admin, UUID targetUuid) {
        if (!require(admin, "simplexadminsuite.powertools.moderation")) return;
        SimplexAdminSuite.PlayerProfile target = plugin.expansionProfile(targetUuid);
        if (target == null) return;
        PlayerControlState state = state(targetUuid);
        if (!state.quarantine) {
            state.quarantinePrevious = target.blockedRightsSet();
            Set<String> blocked = new LinkedHashSet<>(state.quarantinePrevious);
            blocked.addAll(Arrays.asList("chat","private-messages","mentions","commands","move","build","break","interact","drop","pickup","bucket","pvp","animals","villagers","nether","end","elytra","food","potions","projectiles","vehicles","crafting","enchanting","anvils","brewing","explosives","fire"));
            target.replaceBlockedRights(blocked);
            state.quarantine = true;
            plugin.powerSaveProfile(target, admin.getName() + " aktivierte Quarantäne");
            notifyPlayer(targetUuid, "Your account has been placed in staff quarantine. Please follow staff instructions.");
            record(admin.getName(), targetUuid, target.displayName(), "MODERATION", "Quarantäne aktiviert", "Vorzustand mit " + state.quarantinePrevious.size() + " Sperren gespeichert", true);
        } else {
            target.replaceBlockedRights(state.quarantinePrevious);
            state.quarantine = false;
            state.quarantinePrevious = new LinkedHashSet<>();
            plugin.powerSaveProfile(target, admin.getName() + " hob Quarantäne exakt auf");
            notifyPlayer(targetUuid, "Your staff quarantine has been lifted. Your previous permissions were restored.");
            record(admin.getName(), targetUuid, target.displayName(), "MODERATION", "Quarantäne aufgehoben", "Exakter Vorzustand restauriert", true);
        }
        saveControls();
        plugin.powerAudit(admin.getName(), targetUuid, state.quarantine ? "Quarantäne aktiviert" : "Quarantäne aufgehoben", "Exakte Rechte-Sicherung");
        rightsChanged(admin.getName(), targetUuid);
    }

    void applyPreset(Player admin, UUID targetUuid, ModerationPreset preset) {
        if (!require(admin, "simplexadminsuite.powertools.moderation") || preset == null) return;
        SimplexAdminSuite.PlayerProfile target = plugin.expansionProfile(targetUuid);
        if (target == null) return;
        PlayerControlState state = state(targetUuid);
        state.presetPrevious = target.blockedRightsSet();
        state.lastPreset = preset.name();
        Set<String> blocked = new LinkedHashSet<>(state.presetPrevious);
        if (preset == ModerationPreset.BUILD_LOCK) blocked.addAll(Arrays.asList("build","break","interact","redstone","bucket","explosives","fire"));
        else if (preset == ModerationPreset.CHAT_CONTROL) blocked.addAll(Arrays.asList("chat","private-messages","mentions","commands"));
        else blocked.addAll(Arrays.asList("pvp","animals","vehicles","projectiles","potions","elytra","nether","end"));
        target.replaceBlockedRights(blocked);
        saveControls();
        plugin.powerSaveProfile(target, admin.getName() + " wendete Moderationsprofil " + preset.name() + " an");
        notifyPlayer(targetUuid, "A staff moderation profile has updated your server permissions.");
        record(admin.getName(), targetUuid, target.displayName(), "MODERATION", "Profil " + presetLabel(preset) + " angewendet", "Vorzustand exakt gespeichert", true);
        plugin.powerAudit(admin.getName(), targetUuid, "Moderationsprofil", preset.name());
        rightsChanged(admin.getName(), targetUuid);
    }

    void restorePreset(Player admin, UUID targetUuid) {
        if (!require(admin, "simplexadminsuite.powertools.moderation")) return;
        SimplexAdminSuite.PlayerProfile target = plugin.expansionProfile(targetUuid);
        if (target == null) return;
        PlayerControlState state = state(targetUuid);
        if (state.lastPreset.isBlank()) { plugin.menuNotice(admin,plugin.prefix() + "Für diesen Spieler ist kein restaurierbares Profil gespeichert."); return; }
        String previousPreset = state.lastPreset;
        target.replaceBlockedRights(state.presetPrevious);
        state.lastPreset = "";
        state.presetPrevious = new LinkedHashSet<>();
        saveControls();
        plugin.powerSaveProfile(target, admin.getName() + " restaurierte Rechte vor Moderationsprofil");
        notifyPlayer(targetUuid, "Your previous server permissions have been restored by staff.");
        record(admin.getName(), targetUuid, target.displayName(), "MODERATION", "Profilzustand restauriert", previousPreset, true);
        plugin.powerAudit(admin.getName(), targetUuid, "Moderationsprofil restauriert", previousPreset);
        rightsChanged(admin.getName(), targetUuid);
    }

    void rightsChanged(String actor, UUID sourceUuid) {
        AltRightsPolicy policy;
        synchronized (this) { policy = altPolicies.get(sourceUuid); }
        if (policy == null || !policy.enabled) return;
        Player actorPlayer = Bukkit.getPlayerExact(actor);
        syncAltRights(actorPlayer, sourceUuid, false, actor);
    }

    void identityUpdated(SimplexAdminSuite.PlayerProfile candidate) {
        if (candidate == null) return;
        List<AltRightsPolicy> snapshot;
        synchronized (this) { snapshot = new ArrayList<>(altPolicies.values()); }
        boolean changed = false;
        for (AltRightsPolicy policy : snapshot) {
            if (!policy.enabled || !policy.future || policy.source.equals(candidate.uniqueId())) continue;
            SimplexAdminSuite.PlayerProfile source = plugin.expansionProfile(policy.source);
            if (source == null) continue;
            if (plugin.powerAltLinkIgnored(source.uniqueId(), candidate.uniqueId())) continue;
            boolean known = policy.linkedAccounts.contains(candidate.uniqueId());
            AltCandidate relation = known ? null : plugin.powerAltRelation(source.uniqueId(), candidate.uniqueId(), policy.minimumScore, policy.includeIpOnly);
            if (!known && relation == null) continue;
            boolean rightsDifferent = !sameKnownRights(candidate, source);
            if (rightsDifferent) copyKnownRights(source, candidate);
            boolean newlyLinked = policy.linkedAccounts.add(candidate.uniqueId());
            if (newlyLinked || rightsDifferent) {
                policy.lastSyncAt = System.currentTimeMillis();
                policy.lastSyncBy = "Future account detection";
                changed = true;
            }
            if (rightsDifferent) {
                plugin.powerSaveProfile(candidate, "Zukünftige Alt-Rechte von " + source.displayName() + " übernommen");
                notifyPlayer(candidate.uniqueId(), "Your server permissions were synchronized with a linked account policy.");
                record("Alt-Rechte-System", candidate.uniqueId(), candidate.displayName(), "ALT_RIGHTS", "Zukünftiges Konto synchronisiert", "Quelle=" + source.displayName(), true);
            }
        }
        if (changed) savePolicies();
    }

    void syncAltRights(Player admin, UUID sourceUuid, boolean announce) {
        syncAltRights(admin, sourceUuid, announce, admin == null ? "System" : admin.getName());
    }

    private void syncAltRights(Player admin, UUID sourceUuid, boolean announce, String actor) {
        if (admin != null && !require(admin, "simplexadminsuite.alt-rights")) return;
        SimplexAdminSuite.PlayerProfile source = plugin.expansionProfile(sourceUuid);
        AltRightsPolicy policy = policy(sourceUuid, false);
        if (source == null || policy == null || !policy.enabled) {
            if (announce && admin != null) admin.sendMessage(plugin.prefix() + "Die Rechtevererbung ist für dieses Profil nicht aktiv.");
            return;
        }
        List<AltCandidate> matches = plugin.powerAltCandidates(sourceUuid, policy.minimumScore, policy.includeIpOnly);
        int changed = 0;
        for (AltCandidate match : matches) {
            SimplexAdminSuite.PlayerProfile target = plugin.expansionProfile(match.uuid);
            if (target == null) continue;
            boolean different = !sameKnownRights(target, source);
            if (different) copyKnownRights(source, target);
            policy.linkedAccounts.add(target.uniqueId());
            if (different) {
                plugin.powerSaveProfile(target, "Alt-Rechte von " + source.displayName() + " synchronisiert (Score " + match.score + ")");
                notifyPlayer(target.uniqueId(), "Your server permissions were synchronized with a linked account policy.");
                changed++;
            }
        }
        policy.sourceName = source.displayName();
        policy.lastSyncAt = System.currentTimeMillis();
        policy.lastSyncBy = actor;
        policy.lastSyncCount = matches.size();
        savePolicies();
        record(actor, sourceUuid, source.displayName(), "ALT_RIGHTS", "Alt-Rechte synchronisiert", matches.size() + " Konten, " + changed + " Änderungen", true);
        plugin.powerAudit(actor, sourceUuid, "Alt-Rechte synchronisiert", matches.size() + " Konten / Mindestscore " + policy.minimumScore + (policy.includeIpOnly ? " / IP erlaubt" : " / starke Anker"));
        if (announce && admin != null) admin.sendMessage(plugin.prefix() + matches.size() + " passende Alt-Konten geprüft; " + changed + " Rechte-Matrizen geändert.");
    }

    private boolean sameKnownRights(SimplexAdminSuite.PlayerProfile first, SimplexAdminSuite.PlayerProfile second) {
        return knownRights(first).equals(knownRights(second))
                && knownTemporaryRights(first).equals(knownTemporaryRights(second));
    }

    private void copyKnownRights(SimplexAdminSuite.PlayerProfile source, SimplexAdminSuite.PlayerProfile target) {
        Set<String> permanentResult = target.blockedRightsSet();
        Map<String, Long> temporaryResult = target.temporaryBlockedRightsMap();
        Set<String> sourcePermanent = knownRights(source);
        Map<String, Long> sourceTemporary = knownTemporaryRights(source);
        for (String right : plugin.powerRights()) {
            if (sourcePermanent.contains(right)) {
                permanentResult.add(right);
                temporaryResult.remove(right);
            } else if (sourceTemporary.containsKey(right)) {
                permanentResult.remove(right);
                temporaryResult.put(right, sourceTemporary.get(right));
            } else {
                permanentResult.remove(right);
                temporaryResult.remove(right);
            }
        }
        target.replaceBlockedRights(permanentResult);
        target.replaceTemporaryBlockedRights(temporaryResult);
    }

    private Set<String> knownRights(SimplexAdminSuite.PlayerProfile profile) {
        Set<String> result = new LinkedHashSet<>();
        if (profile == null) return result;
        Set<String> blocked = profile.blockedRightsSet();
        for (String right : plugin.powerRights()) if (blocked.contains(right)) result.add(right);
        return result;
    }

    private Map<String, Long> knownTemporaryRights(SimplexAdminSuite.PlayerProfile profile) {
        Map<String, Long> result = new LinkedHashMap<>();
        if (profile == null) return result;
        Map<String, Long> temporary = profile.temporaryBlockedRightsMap();
        for (String right : plugin.powerRights()) {
            Long until = temporary.get(right);
            if (until != null && until > System.currentTimeMillis()) result.put(right, until);
        }
        return result;
    }

    void startLagScan(Player admin) {
        if (!requireOperator(admin, "simplexadminsuite.laginspector")) return;
        if (activeLagScan != null) { admin.sendMessage(plugin.prefix() + "Es läuft bereits ein Lag-Scan."); return; }
        List<LagChunk> chunks = captureLoadedChunks();
        int maximum = Math.max(100, Math.min(50_000, plugin.getConfig().getInt("lag-inspector.max-loaded-chunks", 5000)));
        if (chunks.size() > maximum) chunks = new ArrayList<>(chunks.subList(0, maximum));
        ActiveLagScan scan = new ActiveLagScan(admin.getName(), chunks);
        activeLagScan = scan;
        latestHotspots = List.of();
        record(admin.getName(), null, "", "LAG", "Lag-Scan gestartet", chunks.size() + " geladene Chunks", true);
        plugin.powerAudit(admin.getName(), null, "Lag-Scan gestartet", chunks.size() + " geladene Chunks");
        plugin.powerDiscordEvent("Lag inspector started", "A budgeted scan of **" + chunks.size() + " loaded chunks** was started by **" + admin.getName() + "**. No chunks are being loaded.", 0x3498DB);
        if (chunks.isEmpty()) { finishLagScan(scan, false); admin.sendMessage(plugin.prefix() + "Keine geladenen Chunks konnten über die Server-API gelesen werden."); return; }
        stopLagTask();
        lagTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> tickLagScan(scan), 1L, 1L);
        admin.sendMessage(plugin.prefix() + "Lag-Scan gestartet: " + chunks.size() + " bereits geladene Chunks, maximal " + tickBudgetMillis() + " ms pro Tick.");
    }

    void cancelLagScan(Player admin) {
        if (!requireOperator(admin, "simplexadminsuite.laginspector")) return;
        ActiveLagScan scan = activeLagScan;
        if (scan == null) { admin.sendMessage(plugin.prefix() + "Es läuft kein Lag-Scan."); return; }
        scan.cancelled = true;
        finishLagScan(scan, true);
        plugin.menuNotice(admin,plugin.prefix() + "Lag-Scan wurde sicher abgebrochen. Teilresultate bleiben verfügbar.");
    }

    private void tickLagScan(ActiveLagScan scan) {
        if (scan != activeLagScan || scan.cancelled) return;
        long start = System.nanoTime();
        long budget = tickBudgetMillis() * 1_000_000L;
        int maximum = chunksPerTick();
        int processed = 0;
        while (scan.cursor < scan.chunks.size() && processed < maximum && System.nanoTime() - start < budget) {
            LagChunk chunk = scan.chunks.get(scan.cursor++);
            Hotspot hotspot = inspectChunk(chunk);
            if (hotspot != null && hotspot.score >= warningLagScore()) scan.hotspots.add(hotspot);
            processed++;
        }
        long timeout = Math.max(30L, Math.min(1800L, plugin.getConfig().getLong("lag-inspector.timeout-seconds", 300L))) * 1000L;
        if (scan.cursor >= scan.chunks.size() || System.currentTimeMillis() - scan.startedAt >= timeout) finishLagScan(scan, false);
    }

    private synchronized void finishLagScan(ActiveLagScan scan, boolean cancelled) {
        if (scan == null) return;
        stopLagTask();
        scan.hotspots.sort(Comparator.comparingInt((Hotspot h) -> h.score).reversed());
        int keep = Math.max(10, Math.min(250, plugin.getConfig().getInt("lag-inspector.max-hotspots", 100)));
        if (scan.hotspots.size() > keep) scan.hotspots.subList(keep, scan.hotspots.size()).clear();
        latestHotspots = List.copyOf(scan.hotspots);
        int highest = scan.hotspots.isEmpty() ? 0 : scan.hotspots.get(0).score;
        LagHistoryEntry history = new LagHistoryEntry(System.currentTimeMillis(), scan.actor, scan.cursor,
                System.currentTimeMillis() - scan.startedAt, scan.hotspots.size(), highest, cancelled);
        lagHistory.add(0, history);
        while (lagHistory.size() > 100) lagHistory.remove(lagHistory.size() - 1);
        saveLagHistory();
        activeLagScan = null;
        String details = scan.cursor + " Chunks, " + scan.hotspots.size() + " Hotspots, höchster Score " + highest + (cancelled ? ", abgebrochen" : "");
        record(scan.actor, null, "", "LAG", cancelled ? "Lag-Scan abgebrochen" : "Lag-Scan abgeschlossen", details, !cancelled);
        plugin.powerAudit(scan.actor, null, cancelled ? "Lag-Scan abgebrochen" : "Lag-Scan abgeschlossen", details);
        if (!cancelled) {
            int color = highest >= criticalLagScore() ? 0xED4245 : highest >= warningLagScore() ? 0xFEE75C : 0x57F287;
            plugin.powerDiscordEvent(highest >= criticalLagScore() ? "Critical lag hotspot detected" : "Lag inspection completed",
                    "**Chunks scanned:** " + scan.cursor + "\n**Hotspots:** " + scan.hotspots.size() + "\n**Highest score:** " + highest
                            + (scan.hotspots.isEmpty() ? "" : "\n**Top location:** " + scan.hotspots.get(0).coordinates()), color);
        }
    }

    private List<LagChunk> captureLoadedChunks() {
        List<LagChunk> result = new ArrayList<>();
        Set<String> disabled = new HashSet<>();
        for (String name : plugin.getConfig().getStringList("lag-inspector.disabled-worlds")) disabled.add(name.toLowerCase(Locale.ROOT));
        for (World world : Bukkit.getWorlds()) {
            if (world == null || disabled.contains(world.getName().toLowerCase(Locale.ROOT))) continue;
            try {
                Object raw = world.getClass().getMethod("getLoadedChunks").invoke(world);
                for (Object chunk : asObjects(raw)) {
                    int x = invokeInt(chunk, "getX", 0);
                    int z = invokeInt(chunk, "getZ", 0);
                    result.add(new LagChunk(chunk, world.getName(), x, z));
                }
            } catch (Throwable error) {
                plugin.getLogger().warning("Geladene Chunks von Welt " + world.getName() + " konnten nicht gelesen werden: " + error.getClass().getSimpleName());
            }
        }
        result.sort(Comparator.comparing((LagChunk c) -> c.world, String.CASE_INSENSITIVE_ORDER).thenComparingInt(c -> c.x).thenComparingInt(c -> c.z));
        return result;
    }

    private Hotspot inspectChunk(LagChunk ref) {
        try {
            int entities = 0, blockEntities = 0, hoppers = 0, minecarts = 0, items = 0;
            int villagers = 0, monsters = 0, animals = 0, spawners = 0, furnaces = 0, droppers = 0;
            Object rawEntities = invoke(ref.chunk, "getEntities");
            for (Object entity : asObjects(rawEntities)) {
                entities++;
                String type = typeName(entity);
                if (type.contains("MINECART")) minecarts++;
                if (type.equals("ITEM") || type.contains("DROPPED_ITEM")) items++;
                if (type.contains("VILLAGER") || type.contains("WANDERING_TRADER")) villagers++;
                if (containsAny(type, "ZOMBIE","SKELETON","CREEPER","SPIDER","ENDERMAN","PILLAGER","MONSTER","PHANTOM","SLIME")) monsters++;
                if (containsAny(type, "COW","PIG","SHEEP","CHICKEN","HORSE","GOAT","CAMEL","WOLF","CAT","BEE")) animals++;
            }
            Object rawTiles = invoke(ref.chunk, "getTileEntities");
            for (Object tile : asObjects(rawTiles)) {
                blockEntities++;
                String type = typeName(tile);
                if (type.contains("HOPPER")) hoppers++;
                if (containsAny(type, "SPAWNER","MOB_SPAWNER")) spawners++;
                if (containsAny(type, "FURNACE","SMOKER","BLAST_FURNACE")) furnaces++;
                if (containsAny(type, "DROPPER","DISPENSER")) droppers++;
            }
            int score = calculateLagScore(entities, blockEntities, hoppers, minecarts, items, villagers, monsters, animals, spawners, furnaces, droppers);
            String reason = dominantReason(hoppers, minecarts, items, villagers, monsters, animals, blockEntities, spawners, droppers);
            return new Hotspot(ref.world, ref.x, ref.z, score, entities, blockEntities, hoppers, minecarts, items, reason);
        } catch (Throwable error) {
            return null;
        }
    }

    static int calculateLagScore(int entities, int blockEntities, int hoppers, int minecarts, int items,
                                 int villagers, int monsters, int animals, int spawners, int furnaces, int droppers) {
        long score = Math.max(0, entities) + Math.max(0, blockEntities) * 2L + Math.max(0, hoppers) * 14L
                + Math.max(0, minecarts) * 8L + Math.max(0, items) * 3L + Math.max(0, villagers) * 5L
                + Math.max(0, monsters) * 2L + Math.max(0, animals) * 2L + Math.max(0, spawners) * 12L
                + Math.max(0, furnaces) * 3L + Math.max(0, droppers) * 7L;
        return (int) Math.min(Integer.MAX_VALUE, score);
    }

    private String dominantReason(int hoppers, int minecarts, int items, int villagers, int monsters,
                                  int animals, int blockEntities, int spawners, int droppers) {
        Map<String, Integer> weighted = new LinkedHashMap<>();
        weighted.put("Hopper density", hoppers * 14);
        weighted.put("Minecart density", minecarts * 8);
        weighted.put("Dropped items", items * 3);
        weighted.put("Villager density", villagers * 5);
        weighted.put("Hostile mob density", monsters * 2);
        weighted.put("Animal density", animals * 2);
        weighted.put("Block-entity density", blockEntities * 2);
        weighted.put("Spawner activity", spawners * 12);
        weighted.put("Dropper/dispenser density", droppers * 7);
        return weighted.entrySet().stream().max(Map.Entry.comparingByValue()).filter(e -> e.getValue() > 0).map(Map.Entry::getKey).orElse("Mixed chunk load");
    }

    int lagProgress() {
        ActiveLagScan scan = activeLagScan;
        if (scan == null || scan.chunks.isEmpty()) return scan == null ? 0 : 100;
        return Math.max(0, Math.min(100, (int) Math.round(scan.cursor * 100D / scan.chunks.size())));
    }

    String lagStatusGerman() {
        ActiveLagScan scan = activeLagScan;
        if (scan == null) return "Bereit • letzte Hotspots: " + latestHotspots.size();
        return "Läuft • " + lagProgress() + "% • " + scan.cursor + "/" + scan.chunks.size();
    }

    List<Hotspot> hotspotsSnapshot() { return latestHotspots; }
    synchronized List<LagHistoryEntry> lagHistorySnapshot() { return new ArrayList<>(lagHistory); }

    void teleportToHotspot(Player admin, int index) {
        if (!requireOperator(admin, "simplexadminsuite.laginspector")) return;
        List<Hotspot> current = latestHotspots;
        if (index < 0 || index >= current.size()) { admin.sendMessage(plugin.prefix() + "Hotspot ist nicht mehr verfügbar."); return; }
        Hotspot hotspot = current.get(index);
        World world = Bukkit.getWorld(hotspot.world);
        if (world == null) { admin.sendMessage(plugin.prefix() + "Welt ist nicht geladen."); return; }
        if (!isChunkLoaded(world, hotspot.chunkX, hotspot.chunkZ)) {
            admin.sendMessage(plugin.prefix() + "Der Hotspot-Chunk ist inzwischen entladen. Er wird aus Sicherheitsgründen nicht nachgeladen.");
            return;
        }
        int x = hotspot.chunkX * 16 + 8;
        int z = hotspot.chunkZ * 16 + 8;
        double y = Math.max(2D, admin.getLocation().getY());
        try { y = ((Number) world.getClass().getMethod("getHighestBlockYAt", int.class, int.class).invoke(world, x, z)).doubleValue() + 1D; }
        catch (Throwable ignored) { }
        boolean ok = admin.teleport(new Location(world, x + 0.5D, y, z + 0.5D, admin.getLocation().getYaw(), admin.getLocation().getPitch()));
        plugin.menuNotice(admin,plugin.prefix() + (ok ? "Zum geladenen Hotspot teleportiert: " : "Teleport fehlgeschlagen: ") + hotspot.coordinates() + " (Score " + hotspot.score + ").");
        record(admin.getName(), null, "", "LAG", "Zu Lag-Hotspot teleportiert", hotspot.coordinates() + " / Score " + hotspot.score, ok);
    }

    DiscordDesignInput discordDesign() {
        DiscordDesignInput d = new DiscordDesignInput();
        d.serverName = configText("server-name", d.serverName);
        d.onlineTitle = configText("online-title", d.onlineTitle);
        d.warningTitle = configText("warning-title", d.warningTitle);
        d.criticalTitle = configText("critical-title", d.criticalTitle);
        d.restartingTitle = configText("restarting-title", d.restartingTitle);
        d.offlineTitle = configText("offline-title", d.offlineTitle);
        d.onlineDescription = configText("online-description", d.onlineDescription);
        d.warningDescription = configText("warning-description", d.warningDescription);
        d.criticalDescription = configText("critical-description", d.criticalDescription);
        d.restartingDescription = configText("restarting-description", d.restartingDescription);
        d.offlineDescription = configText("offline-description", d.offlineDescription);
        d.footer = configText("footer", d.footer);
        d.healthyColor = configText("healthy-color", d.healthyColor);
        d.warningColor = configText("warning-color", d.warningColor);
        d.criticalColor = configText("critical-color", d.criticalColor);
        d.restartingColor = configText("restarting-color", d.restartingColor);
        d.offlineColor = configText("offline-color", d.offlineColor);
        d.warningTps = clamp(plugin.getConfig().getDouble("discord-bot.status.design.warning-tps", d.warningTps), 10D, 20D);
        d.criticalTps = clamp(plugin.getConfig().getDouble("discord-bot.status.design.critical-tps", d.criticalTps), 5D, 19.9D);
        if (d.criticalTps >= d.warningTps) d.criticalTps = Math.max(5D, d.warningTps - 1D);
        d.showPlayerCount = plugin.getConfig().getBoolean("discord-bot.status.design.show-player-count", true);
        d.showPlayerList = plugin.getConfig().getBoolean("discord-bot.status.design.show-player-list", true);
        d.showPlatform = plugin.getConfig().getBoolean("discord-bot.status.design.show-platform", true);
        d.showTps = plugin.getConfig().getBoolean("discord-bot.status.design.show-tps", true);
        d.showStability = plugin.getConfig().getBoolean("discord-bot.status.design.show-stability", true);
        d.showLeaderboard = plugin.getConfig().getBoolean("discord-bot.status.design.show-leaderboard", false);
        return d;
    }

    void applyDiscordDesign(Player admin, DiscordDesignInput input) {
        if (!require(admin, "simplexadminsuite.discord.design") || input == null) return;
        String base = "discord-bot.status.design.";
        plugin.getConfig().set(base + "server-name", clean(input.serverName, 80, "Simplex SMP"));
        plugin.getConfig().set(base + "online-title", clean(input.onlineTitle, 256, "🟢 {server} is online"));
        plugin.getConfig().set(base + "warning-title", clean(input.warningTitle, 256, "🟡 {server} stability warning"));
        plugin.getConfig().set(base + "critical-title", clean(input.criticalTitle, 256, "🔴 {server} is under heavy load"));
        plugin.getConfig().set(base + "restarting-title", clean(input.restartingTitle, 256, "🟠 {server} is restarting"));
        plugin.getConfig().set(base + "offline-title", clean(input.offlineTitle, 256, "🔴 {server} is offline"));
        plugin.getConfig().set(base + "online-description", clean(input.onlineDescription, 1000, "The server is online and accepting players."));
        plugin.getConfig().set(base + "warning-description", clean(input.warningDescription, 1000, "Server performance is currently degraded. Gameplay may feel less responsive."));
        plugin.getConfig().set(base + "critical-description", clean(input.criticalDescription, 1000, "Server performance is currently unstable. Staff have been notified."));
        plugin.getConfig().set(base + "restarting-description", clean(input.restartingDescription, 1000, "The server is restarting. Players can reconnect shortly."));
        plugin.getConfig().set(base + "offline-description", clean(input.offlineDescription, 1000, "The server is currently shut down."));
        plugin.getConfig().set(base + "footer", clean(input.footer, 512, "Persistent status • Java and Bedrock"));
        plugin.getConfig().set(base + "healthy-color", validColor(input.healthyColor, "#57F287"));
        plugin.getConfig().set(base + "warning-color", validColor(input.warningColor, "#FEE75C"));
        plugin.getConfig().set(base + "critical-color", validColor(input.criticalColor, "#ED4245"));
        plugin.getConfig().set(base + "restarting-color", validColor(input.restartingColor, "#FEE75C"));
        plugin.getConfig().set(base + "offline-color", validColor(input.offlineColor, "#ED4245"));
        double warning = clamp(input.warningTps, 10D, 20D);
        double critical = clamp(input.criticalTps, 5D, 19.9D);
        if (critical >= warning) critical = Math.max(5D, warning - 1D);
        plugin.getConfig().set(base + "warning-tps", warning);
        plugin.getConfig().set(base + "critical-tps", critical);
        plugin.getConfig().set(base + "show-player-count", input.showPlayerCount);
        plugin.getConfig().set(base + "show-player-list", input.showPlayerList);
        plugin.getConfig().set(base + "show-platform", input.showPlatform);
        plugin.getConfig().set(base + "show-tps", input.showTps);
        plugin.getConfig().set(base + "show-stability", input.showStability);
        plugin.getConfig().set(base + "show-leaderboard", input.showLeaderboard);
        plugin.saveConfig();
        plugin.powerRefreshDiscord();
        record(admin.getName(), null, "", "DISCORD", "Statusdesign aktualisiert", "TPS " + decimal(warning) + "/" + decimal(critical), true);
        plugin.powerAudit(admin.getName(), null, "Discord-Statusdesign aktualisiert", "Warnung=" + warning + " / Kritisch=" + critical);
        plugin.menuNotice(admin,plugin.prefix() + "Discord-Statusdesign wurde gespeichert und live aktualisiert.");
    }

    void resetDiscordDesign(Player admin) {
        if (!require(admin, "simplexadminsuite.discord.design")) return;
        DiscordDesignInput defaults = new DiscordDesignInput();
        applyDiscordDesign(admin, defaults);
        record(admin.getName(), null, "", "DISCORD", "Statusdesign zurückgesetzt", "7.0-Standard", true);
    }

    void sendLinkedAccounts(Player admin, AltRightsPolicy policy) {
        if (admin == null || policy == null) return;
        admin.closeInventory();
        admin.sendMessage(plugin.color("&8&m----------&r &dAlt-Rechte: &f" + policy.sourceName + " &8&m----------"));
        if (policy.linkedAccounts.isEmpty()) { admin.sendMessage(plugin.color("&7Noch keine Konten synchronisiert.")); return; }
        int shown = 0;
        for (UUID uuid : policy.linkedAccounts) {
            SimplexAdminSuite.PlayerProfile profile = plugin.expansionProfile(uuid);
            admin.sendMessage(plugin.color("&7- &f" + (profile == null ? uuid.toString() : profile.displayName() + " &8(" + uuid + ")")));
            if (++shown >= 50) { admin.sendMessage(plugin.color("&7…weitere Konten werden nur in der Datendatei geführt.")); break; }
        }
    }

    void healAndFeed(Player admin, UUID target) {
        if (!require(admin, "simplexadminsuite.powertools.player")) return;
        Player player = online(admin, target); if (player == null) return;
        try { player.setHealth(Math.max(1D, player.getMaxHealth())); player.setFoodLevel(20); invoke(player, "setSaturation", 20F); }
        catch (Throwable error) { failed(admin, target, "Heilen & sättigen", error); return; }
        success(admin, target, "SITZUNG", "Heilen & sättigen", "Leben, Hunger und Sättigung wiederhergestellt", "You have been healed and fed by staff.");
    }

    void clearEffects(Player admin, UUID target) {
        if (!require(admin, "simplexadminsuite.powertools.player")) return;
        Player player = online(admin, target); if (player == null) return;
        int removed = 0;
        try {
            Object effects = invoke(player, "getActivePotionEffects");
            for (Object effect : asObjects(effects)) {
                Object type = invoke(effect, "getType");
                if (type != null && invokeCompatible(player, "removePotionEffect", type)) removed++;
            }
        } catch (Throwable error) { failed(admin, target, "Effekte entfernen", error); return; }
        success(admin, target, "SITZUNG", "Potion-Effekte entfernt", removed + " Effekte", "Your active potion effects were cleared by staff.");
    }

    void extinguish(Player admin, UUID target) {
        if (!require(admin, "simplexadminsuite.powertools.player")) return;
        Player player = online(admin, target); if (player == null) return;
        boolean fire = invokeCompatible(player, "setFireTicks", 0);
        boolean freeze = invokeCompatible(player, "setFreezeTicks", 0);
        if (!fire && !freeze) { failed(admin, target, "Feuer/Frost entfernen", new IllegalStateException("Server-API nicht verfügbar")); return; }
        success(admin, target, "SITZUNG", "Feuer/Frost entfernt", "Fire=" + fire + ", Freeze=" + freeze, "Fire and freezing effects were cleared by staff.");
    }

    void resetMovement(Player admin, UUID target) {
        if (!require(admin, "simplexadminsuite.powertools.player")) return;
        Player player = online(admin, target); if (player == null) return;
        boolean fall = invokeCompatible(player, "setFallDistance", 0F);
        boolean velocity = false;
        try {
            Object vector = invoke(player, "getVelocity");
            if (vector != null) {
                if (invokeCompatible(vector, "zero")) velocity = invokeCompatible(player, "setVelocity", vector);
                else {
                    invokeCompatible(vector, "setX", 0D); invokeCompatible(vector, "setY", 0D); invokeCompatible(vector, "setZ", 0D);
                    velocity = invokeCompatible(player, "setVelocity", vector);
                }
            }
        } catch (Throwable ignored) { }
        if (!fall && !velocity) { failed(admin, target, "Bewegungsreset", new IllegalStateException("Server-API nicht verfügbar")); return; }
        success(admin, target, "SITZUNG", "Bewegungszustand zurückgesetzt", "Fall=" + fall + ", Velocity=" + velocity, "Your movement state was reset by staff.");
    }

    void synchronizeInventory(Player admin, UUID target) {
        if (!require(admin, "simplexadminsuite.powertools.player")) return;
        Player player = online(admin, target); if (player == null) return;
        try { player.updateInventory(); plugin.refreshExpansionNameplate(player); }
        catch (Throwable error) { failed(admin, target, "Inventar-Sync", error); return; }
        success(admin, target, "SITZUNG", "Inventar synchronisiert", "Client- und Nametag-Refresh", null);
    }

    void sendToSpawn(Player admin, UUID target) {
        if (!require(admin, "simplexadminsuite.powertools.player")) return;
        Player player = online(admin, target); if (player == null) return;
        try {
            Object spawn = invoke(player.getWorld(), "getSpawnLocation");
            if (!(spawn instanceof Location) || !player.teleport((Location) spawn)) throw new IllegalStateException("Teleport wurde abgelehnt");
        } catch (Throwable error) { failed(admin, target, "Zum Spawn senden", error); return; }
        success(admin, target, "SITZUNG", "Zum Weltspawn gesendet", player.getWorld().getName(), "You were moved to the world spawn by staff.");
    }

    void toggleFreeze(Player admin, UUID targetUuid) {
        if (!require(admin, "simplexadminsuite.freeze")) return;
        SimplexAdminSuite.PlayerProfile target = plugin.expansionProfile(targetUuid);
        if (target == null) return;
        boolean blocked = !target.isRightBlocked("move");
        target.setRightBlocked("move", blocked);
        plugin.powerSaveProfile(target, admin.getName() + " änderte Freeze auf " + blocked);
        notifyPlayer(targetUuid, blocked ? "You have been frozen by staff." : "You are no longer frozen.");
        record(admin.getName(), targetUuid, target.displayName(), "MODERATION", blocked ? "Freeze aktiviert" : "Freeze aufgehoben", "", true);
        plugin.powerAudit(admin.getName(), targetUuid, blocked ? "Freeze aktiviert" : "Freeze aufgehoben", "Sitzungswerkzeug");
        rightsChanged(admin.getName(), targetUuid);
    }

    void disconnectPlayer(Player admin, UUID target) {
        if (!require(admin, "simplexadminsuite.powertools.moderation")) return;
        Player player = online(admin, target); if (player == null) return;
        plugin.suiteKick(player,"You have been disconnected by staff. Please contact the server team if you need assistance.");
        success(admin, target, "MODERATION", "Spieler getrennt", "Bestätigte Staff-Trennung", null);
    }

    void clearInventorySafely(Player admin, UUID target, boolean enderChest) {
        if (!require(admin, "simplexadminsuite.powertools.inventory")) return;
        Player player = online(admin, target); if (player == null) return;
        if (!plugin.powerCreateSnapshot(admin, target)) {
            plugin.menuNotice(admin,plugin.prefix() + "Aktion wurde abgebrochen, weil der Sicherheits-Snapshot nicht erstellt werden konnte.");
            record(admin.getName(), target, name(target), "INVENTORY", "Sichere Leerung abgebrochen", "Snapshot fehlgeschlagen", false);
            return;
        }
        try {
            Inventory inventory = enderChest ? player.getEnderChest() : player.getInventory();
            inventory.setContents(new ItemStack[inventory.getSize()]);
            player.updateInventory();
        } catch (Throwable error) { failed(admin, target, enderChest ? "Enderchest leeren" : "Inventar leeren", error); return; }
        success(admin, target, "INVENTORY", enderChest ? "Enderchest sicher geleert" : "Inventar sicher geleert", "Snapshot vor Aktion erstellt", enderChest ? "Your ender chest was reset by staff after a safety snapshot." : "Your inventory was reset by staff after a safety snapshot.");
    }

    void resetExperience(Player admin, UUID target) {
        if (!require(admin, "simplexadminsuite.powertools.inventory")) return;
        Player player = online(admin, target); if (player == null) return;
        try {
            player.setLevel(0);
            invokeCompatible(player, "setExp", 0F);
            invokeCompatible(player, "setTotalExperience", 0);
        } catch (Throwable error) { failed(admin, target, "Erfahrung zurücksetzen", error); return; }
        success(admin, target, "INVENTORY", "Erfahrung zurückgesetzt", "Level/XP=0", "Your experience was reset by staff.");
    }

    private Player online(Player admin, UUID target) {
        Player player = target == null ? null : Bukkit.getPlayer(target);
        if (player == null && admin != null) admin.sendMessage(plugin.prefix() + "Diese Live-Aktion ist nur bei einem online befindlichen Spieler möglich.");
        return player;
    }

    private void success(Player admin, UUID target, String category, String action, String details, String publicMessage) {
        String name = name(target);
        record(admin.getName(), target, name, category, action, details, true);
        plugin.powerAudit(admin.getName(), target, action, details);
        if (publicMessage != null) notifyPlayer(target, publicMessage);
        plugin.menuNotice(admin,plugin.prefix() + action + " für " + name + " wurde ausgeführt.");
    }

    private void failed(Player admin, UUID target, String action, Throwable error) {
        String details = error == null ? "Unbekannter Fehler" : error.getClass().getSimpleName() + ": " + empty(error.getMessage(), "keine Details");
        record(admin.getName(), target, name(target), "ERROR", action, details, false);
        plugin.powerAudit(admin.getName(), target, action + " fehlgeschlagen", details);
        plugin.menuNotice(admin,plugin.prefix() + action + " konnte nicht ausgeführt werden: " + details + ".");
    }

    private void notifyPlayer(UUID uuid, String message) {
        Player player = uuid == null ? null : Bukkit.getPlayer(uuid);
        if (player != null && message != null && !message.isBlank()) player.sendMessage(plugin.prefix() + message);
    }

    private void ask(Player admin, String action, String prompt) {
        pendingInputs.put(admin.getUniqueId(), new PendingInput(action, null));
        admin.closeInventory();
        admin.sendMessage(plugin.prefix() + prompt);
    }

    private void acceptInput(Player admin, PendingInput pending, String raw) {
        if (raw.equalsIgnoreCase("abbrechen") || raw.equalsIgnoreCase("cancel")) {
            plugin.menuNotice(admin,plugin.prefix() + "Eingabe wurde abgebrochen.");
            openDiscordDesignerJava(admin);
            return;
        }
        String key = pending.action.startsWith("status:") ? pending.action.substring("status:".length()) : "";
        if (key.isBlank()) { plugin.menuNotice(admin,plugin.prefix() + "Unbekannte Eingabe wurde verworfen."); return; }
        int maximum = key.contains("description") ? 1000 : key.equals("footer") ? 512 : key.contains("title") ? 256 : 80;
        String clean = clean(raw, maximum, "");
        if (clean.isBlank()) { plugin.menuNotice(admin,plugin.prefix() + "Der Wert darf nicht leer sein."); openDiscordDesignerJava(admin); return; }
        plugin.getConfig().set("discord-bot.status.design." + key, clean);
        plugin.saveConfig();
        plugin.powerRefreshDiscord();
        record(admin.getName(), null, "", "DISCORD", "Statusfeld geändert", key, true);
        plugin.powerAudit(admin.getName(), null, "Discord-Statusfeld geändert", key);
        plugin.menuNotice(admin,plugin.prefix() + "Statusfeld " + key + " wurde gespeichert.");
        openDiscordDesignerJava(admin);
    }

    private ItemStack toggleItem(boolean enabled, String label) {
        return plugin.item(enabled ? Material.LIME_DYE : Material.GRAY_DYE,
                "&e" + label + ": " + (enabled ? "&aSichtbar" : "&cAusgeblendet"), "&7Klick schaltet die Anzeige um.");
    }

    private synchronized PlayerControlState state(UUID uuid) {
        return controls.computeIfAbsent(uuid, PlayerControlState::new);
    }

    private synchronized AltRightsPolicy policy(UUID uuid, boolean create) {
        AltRightsPolicy policy = altPolicies.get(uuid);
        if (policy == null && create) { policy = new AltRightsPolicy(uuid); altPolicies.put(uuid, policy); }
        return policy;
    }

    synchronized boolean altPolicyEnabled(UUID uuid) {
        AltRightsPolicy policy = altPolicies.get(uuid);
        return policy != null && policy.enabled;
    }

    synchronized String altRightsContent(UUID uuid) {
        AltRightsPolicy policy = policy(uuid, true);
        return "§fStatus: " + (policy.enabled ? "§aAktiv" : "§7Inaktiv")
                + "\n§fMindestscore: §e" + policy.minimumScore
                + "\n§fZukünftige Konten: " + (policy.future ? "§aJa" : "§cNein")
                + "\n§fNur-IP-Treffer: " + (policy.includeIpOnly ? "§6Erlaubt" : "§aAusgeschlossen")
                + "\n§fErfasste Konten: §e" + policy.linkedAccounts.size()
                + "\n§fLetzter Sync: §e" + (policy.lastSyncAt <= 0L ? "Nie" : format(policy.lastSyncAt))
                + "\n\n§7Standardmäßig werden nur manuell bestätigte oder starke Geräte-/XUID-/Self-Signed-Treffer synchronisiert.";
    }

    void toggleAltPolicy(Player admin, UUID uuid) {
        if (!require(admin, "simplexadminsuite.alt-rights")) return;
        AltRightsPolicy policy = policy(uuid, true);
        policy.enabled = !policy.enabled;
        SimplexAdminSuite.PlayerProfile profile = plugin.expansionProfile(uuid);
        if (profile != null) policy.sourceName = profile.displayName();
        savePolicies();
        record(admin.getName(), uuid, name(uuid), "ALT_RIGHTS", policy.enabled ? "Rechtevererbung aktiviert" : "Rechtevererbung deaktiviert", "Score=" + policy.minimumScore, true);
        if (policy.enabled) syncAltRights(admin, uuid, true);
    }

    void cycleAltThreshold(Player admin, UUID uuid) {
        if (!require(admin, "simplexadminsuite.alt-rights")) return;
        AltRightsPolicy policy = policy(uuid, true);
        policy.minimumScore = next(policy.minimumScore, new int[]{70,85,95,100});
        savePolicies();
    }

    void toggleAltFuture(Player admin, UUID uuid) {
        if (!require(admin, "simplexadminsuite.alt-rights")) return;
        AltRightsPolicy policy = policy(uuid, true); policy.future = !policy.future; savePolicies();
    }

    void setAltMinimumScore(Player admin, UUID uuid, int score) {
        if (!require(admin, "simplexadminsuite.alt-rights")) return;
        if (score != 70 && score != 85 && score != 95 && score != 100) {
            admin.sendMessage(plugin.prefix() + "Erlaubte Alt-Mindestscores: 70, 85, 95 oder 100.");
            return;
        }
        AltRightsPolicy policy = policy(uuid, true);
        if (policy == null) { plugin.menuNotice(admin,plugin.prefix() + "Spielerprofil nicht gefunden."); return; }
        policy.minimumScore = score;
        savePolicies();
        plugin.powerAudit(admin.getName(), uuid, "Alt-Rechte-Mindestscore", Integer.toString(score));
        plugin.menuNotice(admin,plugin.prefix() + "Alt-Rechte-Mindestscore wurde auf " + score + " gesetzt.");
    }

    void toggleAltIp(Player admin, UUID uuid) {
        if (!require(admin, "simplexadminsuite.alt-rights")) return;
        AltRightsPolicy policy = policy(uuid, true); policy.includeIpOnly = !policy.includeIpOnly; savePolicies();
    }

    boolean isQuarantined(UUID uuid) { synchronized (this) { PlayerControlState state = controls.get(uuid); return state != null && state.quarantine; } }

    synchronized AltRightsPolicy altPolicySnapshot(UUID uuid) { return altPolicies.get(uuid); }

    private synchronized int enabledPolicyCount() { return (int) altPolicies.values().stream().filter(p -> p.enabled).count(); }
    private synchronized int actionCount() { return actions.size(); }

    private synchronized void record(String actor, UUID target, String targetName, String category, String action, String details, boolean success) {
        actions.add(0, new ActionEntry(System.currentTimeMillis(), clean(actor, 80, "System"), target,
                clean(targetName, 80, ""), clean(category, 30, "SYSTEM"), clean(action, 120, "Action"), clean(details, 500, ""), success));
        while (actions.size() > 300) actions.remove(actions.size() - 1);
        saveActions();
    }

    private synchronized void loadAll() {
        loadControls();
        loadPolicies();
        loadActions();
        loadLagHistory();
    }

    private synchronized void saveAll() {
        saveControls(); savePolicies(); saveActions(); saveLagHistory();
    }

    private void loadControls() {
        controls.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(controlsFile);
        int count = clampInt(y.getInt("count", 0), 0, 100_000);
        for (int i = 0; i < count; i++) {
            String base = "entries." + i;
            UUID uuid = parseUuid(y.getString(base + ".uuid", ""));
            if (uuid == null) continue;
            PlayerControlState state = new PlayerControlState(uuid);
            state.personalSlowmodeSeconds = clampInt(y.getInt(base + ".personal-slowmode-seconds", 0), 0, 120);
            state.quarantine = y.getBoolean(base + ".quarantine.active", false);
            state.quarantinePrevious.addAll(y.getStringList(base + ".quarantine.previous-rights"));
            state.lastPreset = y.getString(base + ".preset.name", "");
            state.presetPrevious.addAll(y.getStringList(base + ".preset.previous-rights"));
            controls.put(uuid, state);
        }
    }

    private synchronized void saveControls() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("schema-version", 1);
        y.set("count", controls.size());
        int i = 0;
        for (PlayerControlState state : controls.values()) {
            String base = "entries." + i++;
            y.set(base + ".uuid", state.uuid.toString());
            y.set(base + ".personal-slowmode-seconds", state.personalSlowmodeSeconds);
            y.set(base + ".quarantine.active", state.quarantine);
            y.set(base + ".quarantine.previous-rights", new ArrayList<>(state.quarantinePrevious));
            y.set(base + ".preset.name", state.lastPreset);
            y.set(base + ".preset.previous-rights", new ArrayList<>(state.presetPrevious));
        }
        save(y, controlsFile);
    }

    private void loadPolicies() {
        altPolicies.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(policiesFile);
        int count = clampInt(y.getInt("count", 0), 0, 100_000);
        for (int i = 0; i < count; i++) {
            String base = "entries." + i;
            UUID uuid = parseUuid(y.getString(base + ".source-uuid", ""));
            if (uuid == null) continue;
            AltRightsPolicy policy = new AltRightsPolicy(uuid);
            policy.sourceName = y.getString(base + ".source-name", "Unknown");
            policy.enabled = y.getBoolean(base + ".enabled", false);
            policy.future = y.getBoolean(base + ".future", true);
            policy.includeIpOnly = y.getBoolean(base + ".include-ip-only", false);
            policy.minimumScore = clampInt(y.getInt(base + ".minimum-score", 70), 25, 1000);
            policy.lastSyncAt = y.getLong(base + ".last-sync-at", 0L);
            policy.lastSyncBy = y.getString(base + ".last-sync-by", "");
            policy.lastSyncCount = Math.max(0, y.getInt(base + ".last-sync-count", 0));
            for (String value : y.getStringList(base + ".linked-accounts")) { UUID linked = parseUuid(value); if (linked != null) policy.linkedAccounts.add(linked); }
            altPolicies.put(uuid, policy);
        }
    }

    private synchronized void savePolicies() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("schema-version", 1);
        y.set("count", altPolicies.size());
        int i = 0;
        for (AltRightsPolicy policy : altPolicies.values()) {
            String base = "entries." + i++;
            y.set(base + ".source-uuid", policy.source.toString());
            y.set(base + ".source-name", policy.sourceName);
            y.set(base + ".enabled", policy.enabled);
            y.set(base + ".future", policy.future);
            y.set(base + ".include-ip-only", policy.includeIpOnly);
            y.set(base + ".minimum-score", policy.minimumScore);
            y.set(base + ".last-sync-at", policy.lastSyncAt);
            y.set(base + ".last-sync-by", policy.lastSyncBy);
            y.set(base + ".last-sync-count", policy.lastSyncCount);
            y.set(base + ".linked-accounts", policy.linkedAccounts.stream().map(UUID::toString).toList());
        }
        save(y, policiesFile);
    }

    private void loadActions() {
        actions.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(actionsFile);
        int count = clampInt(y.getInt("count", 0), 0, 300);
        for (int i = 0; i < count; i++) {
            String base = "entries." + i;
            actions.add(new ActionEntry(y.getLong(base + ".at", 0L), y.getString(base + ".actor", "System"),
                    parseUuid(y.getString(base + ".target-uuid", "")), y.getString(base + ".target-name", ""),
                    y.getString(base + ".category", "SYSTEM"), y.getString(base + ".action", "Action"),
                    y.getString(base + ".details", ""), y.getBoolean(base + ".success", true)));
        }
    }

    private synchronized void saveActions() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("schema-version", 1); y.set("count", actions.size());
        for (int i = 0; i < actions.size(); i++) {
            ActionEntry e = actions.get(i); String base = "entries." + i;
            y.set(base + ".at", e.at); y.set(base + ".actor", e.actor);
            y.set(base + ".target-uuid", e.target == null ? "" : e.target.toString()); y.set(base + ".target-name", e.targetName);
            y.set(base + ".category", e.category); y.set(base + ".action", e.action); y.set(base + ".details", e.details); y.set(base + ".success", e.success);
        }
        save(y, actionsFile);
    }

    private void loadLagHistory() {
        lagHistory.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(lagHistoryFile);
        int count = clampInt(y.getInt("count", 0), 0, 100);
        for (int i = 0; i < count; i++) {
            String base = "entries." + i;
            lagHistory.add(new LagHistoryEntry(y.getLong(base + ".at", 0L), y.getString(base + ".actor", "System"),
                    y.getInt(base + ".chunks", 0), y.getLong(base + ".duration-millis", 0L),
                    y.getInt(base + ".hotspots", 0), y.getInt(base + ".highest-score", 0), y.getBoolean(base + ".cancelled", false)));
        }
    }

    private synchronized void saveLagHistory() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("schema-version", 1); y.set("count", lagHistory.size());
        for (int i = 0; i < lagHistory.size(); i++) {
            LagHistoryEntry h = lagHistory.get(i); String base = "entries." + i;
            y.set(base + ".at", h.at); y.set(base + ".actor", h.actor); y.set(base + ".chunks", h.chunks);
            y.set(base + ".duration-millis", h.durationMillis); y.set(base + ".hotspots", h.hotspots);
            y.set(base + ".highest-score", h.highestScore); y.set(base + ".cancelled", h.cancelled);
        }
        save(y, lagHistoryFile);
    }

    private void save(YamlConfiguration yaml, File file) {
        try {
            File parent = file.getParentFile(); if (parent != null && !parent.exists()) parent.mkdirs();
            File temporary = new File(parent == null ? new File(".") : parent, file.getName() + ".tmp");
            yaml.save(temporary);
            try { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING); }
        } catch (Exception error) {
            plugin.getLogger().warning("Admin-Datendatei konnte nicht atomar gespeichert werden: " + file.getName() + " – " + error.getMessage());
        }
    }

    private void stopLagTask() {
        BukkitTask task = lagTask; lagTask = null;
        if (task != null) try { task.cancel(); } catch (Throwable ignored) { }
    }

    private int chunksPerTick() { return clampInt(plugin.getConfig().getInt("lag-inspector.chunks-per-tick", 8), 1, 100); }
    private int tickBudgetMillis() { return clampInt(plugin.getConfig().getInt("lag-inspector.max-main-thread-millis-per-tick", 4), 1, 20); }
    private int warningLagScore() { return clampInt(plugin.getConfig().getInt("lag-inspector.warning-score", 80), 1, 100_000); }
    private int criticalLagScore() { return Math.max(warningLagScore() + 1, clampInt(plugin.getConfig().getInt("lag-inspector.critical-score", 180), 2, 200_000)); }

    private Material lagMaterial(int score) { return score >= criticalLagScore() ? Material.RED_DYE : score >= warningLagScore() ? Material.ORANGE_DYE : Material.LIME_DYE; }
    private String lagColor(int score) { return score >= criticalLagScore() ? "&c" : score >= warningLagScore() ? "&e" : "&a"; }

    private boolean require(Player admin, String permission) {
        if (admin != null && plugin.has(admin, permission)) return true;
        if (admin != null) admin.sendMessage(plugin.prefix() + "Dafür fehlt dir " + permission + ".");
        return false;
    }

    private boolean requireOperator(Player admin, String permission) {
        if (!require(admin, permission)) return false;
        if (admin.isOp()) return true;
        admin.sendMessage(plugin.prefix() + "Dieses Werkzeug ist zusätzlich nur für echte Server-Operatoren verfügbar.");
        return false;
    }

    private String name(UUID uuid) {
        SimplexAdminSuite.PlayerProfile profile = plugin.expansionProfile(uuid);
        return profile == null ? String.valueOf(uuid) : profile.displayName();
    }

    private String configText(String key, String fallback) {
        return plugin.getConfig().getString("discord-bot.status.design." + key, fallback);
    }

    private void cycleDouble(String path, double[] values) {
        double current = plugin.getConfig().getDouble(path, values[0]);
        int found = -1;
        for (int i = 0; i < values.length; i++) if (Math.abs(values[i] - current) < 0.01D) { found = i; break; }
        plugin.getConfig().set(path, values[(found + 1) % values.length]);
        normalizeThresholds(); plugin.saveConfig(); plugin.powerRefreshDiscord();
    }

    private void normalizeThresholds() {
        double warning = clamp(plugin.getConfig().getDouble("discord-bot.status.design.warning-tps", 18.5D), 10D, 20D);
        double critical = clamp(plugin.getConfig().getDouble("discord-bot.status.design.critical-tps", 16D), 5D, 19.9D);
        if (critical >= warning) critical = Math.max(5D, warning - 1D);
        plugin.getConfig().set("discord-bot.status.design.warning-tps", warning);
        plugin.getConfig().set("discord-bot.status.design.critical-tps", critical);
    }

    private void cycleColor(String path, String[] values) {
        String current = plugin.getConfig().getString(path, values[0]);
        int found = -1;
        for (int i = 0; i < values.length; i++) if (values[i].equalsIgnoreCase(current)) { found = i; break; }
        plugin.getConfig().set(path, values[(found + 1) % values.length]); plugin.saveConfig(); plugin.powerRefreshDiscord();
    }

    private static String validColor(String value, String fallback) {
        String clean = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!clean.startsWith("#")) clean = "#" + clean;
        return clean.matches("#[0-9A-F]{6}") ? clean : fallback;
    }

    private static int next(int current, int[] values) {
        for (int i = 0; i < values.length; i++) if (values[i] == current) return values[(i + 1) % values.length];
        return values[0];
    }

    private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
    private static int clampInt(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    private static UUID parseUuid(String value) { try { return value == null || value.isBlank() ? null : UUID.fromString(value.trim()); } catch (Exception ignored) { return null; } }
    private static String empty(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
    private String format(long at) { return at <= 0L ? "Nie" : dateFormat.format(new Date(at)); }
    private static String decimal(double value) { return String.format(Locale.GERMANY, "%.1f", value); }
    private static String shorten(String value, int maximum) { String text = value == null ? "" : value.replace('\n', ' ').replace('\r', ' '); return text.length() <= maximum ? text : text.substring(0, Math.max(1, maximum - 1)) + "…"; }
    private static String clean(String value, int maximum, String fallback) { String text = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim(); while (text.contains("  ")) text = text.replace("  ", " "); if (text.isBlank()) text = fallback == null ? "" : fallback; return text.length() <= maximum ? text : text.substring(0, maximum); }
    private static ModerationPreset parsePreset(String value) { try { return ModerationPreset.valueOf(value); } catch (Exception ignored) { return null; } }
    private static String presetLabel(ModerationPreset preset) { return preset == ModerationPreset.BUILD_LOCK ? "Bau-Lock" : preset == ModerationPreset.CHAT_CONTROL ? "Chat-Kontrolle" : "Adventure-Lock"; }
    private static boolean containsAny(String value, String... needles) { for (String needle : needles) if (value.contains(needle)) return true; return false; }

    private static Object invoke(Object target, String method, Object... arguments) {
        if (target == null) return null;
        for (Method candidate : target.getClass().getMethods()) {
            if (!candidate.getName().equals(method) || candidate.getParameterCount() != arguments.length) continue;
            try { return candidate.invoke(target, arguments); } catch (Throwable ignored) { }
        }
        return null;
    }

    private static boolean invokeCompatible(Object target, String method, Object... arguments) {
        if (target == null) return false;
        for (Method candidate : target.getClass().getMethods()) {
            if (!candidate.getName().equals(method) || candidate.getParameterCount() != arguments.length) continue;
            try { candidate.invoke(target, arguments); return true; } catch (Throwable ignored) { }
        }
        return false;
    }

    private static int invokeInt(Object target, String method, int fallback) {
        Object value = invoke(target, method);
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    private static List<Object> asObjects(Object value) {
        if (value == null) return List.of();
        if (value instanceof Collection<?>) return new ArrayList<>((Collection<?>) value);
        if (!value.getClass().isArray()) return List.of(value);
        int length = Array.getLength(value);
        List<Object> result = new ArrayList<>(length);
        for (int i = 0; i < length; i++) result.add(Array.get(value, i));
        return result;
    }

    private static String typeName(Object value) {
        if (value == null) return "UNKNOWN";
        Object type = invoke(value, "getType");
        String text = type == null ? value.getClass().getSimpleName() : String.valueOf(type);
        return text.toUpperCase(Locale.ROOT);
    }

    private static boolean isChunkLoaded(World world, int x, int z) {
        try {
            Object value = world.getClass().getMethod("isChunkLoaded", int.class, int.class).invoke(world, x, z);
            return Boolean.TRUE.equals(value);
        } catch (Throwable ignored) { return false; }
    }
}
