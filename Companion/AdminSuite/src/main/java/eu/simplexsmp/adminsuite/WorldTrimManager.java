package eu.simplexsmp.adminsuite;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

/**
 * Integrated, update-safe world trimming coordinator. Bukkit calls are kept on the main thread;
 * directory scans, downloads and MCA Selector processes run away from the server tick thread.
 */
final class WorldTrimManager {
    enum Phase { IDLE, PREPARING, COUNTDOWN, RUNNING, AWAITING_OVERRIDE, RESTARTING, RECOVERY }
    enum QuiescenceDecision { PROCEED, RETRY, REQUEST_OVERRIDE, FAIL }

    static final class WorldTarget {
        final String id;
        final String label;
        final Path root;
        final Path folder;
        final Path region;
        final Path poi;
        final Path entities;
        final String layout;
        final long bytes;

        WorldTarget(String id, String label, Path root, Path folder, Path region, Path poi, Path entities,
                    String layout, long bytes) {
            this.id = id;
            this.label = label;
            this.root = root;
            this.folder = folder;
            this.region = region;
            this.poi = poi;
            this.entities = entities;
            this.layout = layout;
            this.bytes = Math.max(0L, bytes);
        }

        boolean hasPoi() { return Files.isDirectory(poi); }
        boolean hasEntities() { return Files.isDirectory(entities); }
    }

    static final class TrimStatus {
        final Phase phase;
        final int progress;
        final int countdownSeconds;
        final String target;
        final String eta;
        final String operationId;

        TrimStatus(Phase phase, int progress, int countdownSeconds, String target, String eta, String operationId) {
            this.phase = phase;
            this.progress = Math.max(0, Math.min(100, progress));
            this.countdownSeconds = Math.max(0, countdownSeconds);
            this.target = target == null ? "" : target;
            this.eta = eta == null ? "" : eta;
            this.operationId = operationId == null ? "" : operationId;
        }

        boolean active() { return phase != Phase.IDLE; }
        boolean blocksAllJoins() { return phase == Phase.RUNNING || phase == Phase.RESTARTING; }
    }

    static final class HistoryEntry {
        final String id;
        final String status;
        final String actor;
        final List<String> targets;
        final long thresholdTicks;
        final int countdownSeconds;
        final long startedAt;
        final long finishedAt;
        final long beforeBytes;
        final long afterBytes;
        final String toolVersion;
        final String error;

        HistoryEntry(String id, String status, String actor, List<String> targets, long thresholdTicks,
                     int countdownSeconds, long startedAt, long finishedAt, long beforeBytes,
                     long afterBytes, String toolVersion, String error) {
            this.id = id == null ? UUID.randomUUID().toString() : id;
            this.status = status == null ? "UNKNOWN" : status;
            this.actor = actor == null ? "System" : actor;
            this.targets = targets == null ? List.of() : List.copyOf(targets);
            this.thresholdTicks = Math.max(0L, thresholdTicks);
            this.countdownSeconds = Math.max(0, countdownSeconds);
            this.startedAt = Math.max(0L, startedAt);
            this.finishedAt = Math.max(0L, finishedAt);
            this.beforeBytes = Math.max(0L, beforeBytes);
            this.afterBytes = Math.max(0L, afterBytes);
            this.toolVersion = toolVersion == null ? "" : toolVersion;
            this.error = error == null ? "" : error;
        }

        long savedBytes() { return Math.max(0L, beforeBytes - afterBytes); }
    }

    private static final Pattern RELEASE_TAG = Pattern.compile("\\\"tag_name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern RELEASE_JAR = Pattern.compile("\\\"browser_download_url\\\"\\s*:\\s*\\\"([^\\\"]*mcaselector-[^\\\"]+\\.jar)\\\"");
    private static final Pattern PERCENT = Pattern.compile("(\\d{1,3})(?:\\.\\d+)?\\s*%");
    private static final long BACKUP_CONFIRMATION_TTL = 5L * 60_000L;

    private final SimplexAdminSuite plugin;
    private final Path folder;
    private final Path toolFolder;
    private final Path toolFile;
    private final File stateFile;
    private final File sessionsFile;
    private final File historyFile;
    private final File toolStateFile;
    private final File migrationFile;
    private final HttpClient http;
    private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.IDLE);
    private final AtomicBoolean scanRunning = new AtomicBoolean(false);
    private final AtomicBoolean downloadRunning = new AtomicBoolean(false);
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final List<HistoryEntry> history = new ArrayList<>();
    private volatile List<WorldTarget> targets = List.of();
    private volatile long lastScanAt;
    private volatile int progress;
    private volatile int countdownRemaining;
    private volatile String currentTarget = "";
    private volatile String operationId = "";
    private volatile String actor = "System";
    private volatile UUID actorUuid;
    private volatile long operationStartedAt;
    private volatile long operationBeforeBytes;
    private volatile long remainingLoadedChunks;
    private volatile String toolVersion = "";
    private volatile String toolSha256 = "";
    private volatile Process activeProcess;
    private volatile long activeProcessPid;
    private volatile BukkitTask countdownTask;
    private volatile RunPlan pendingPlan;
    private volatile boolean shuttingDown;
    private volatile long lastDiscordProgressAt;
    private volatile int lastDiscordProgress = -1;

    WorldTrimManager(SimplexAdminSuite plugin) {
        this.plugin = plugin;
        this.folder = plugin.getDataFolder().toPath().resolve("world-trimmer");
        this.toolFolder = folder.resolve("tools");
        this.toolFile = toolFolder.resolve("mcaselector.jar");
        this.stateFile = folder.resolve("state.yml").toFile();
        this.sessionsFile = folder.resolve("sessions.yml").toFile();
        this.historyFile = folder.resolve("history.yml").toFile();
        this.toolStateFile = folder.resolve("tool-state.yml").toFile();
        this.migrationFile = folder.resolve("migration.yml").toFile();
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        try { Files.createDirectories(toolFolder); }
        catch (IOException error) { plugin.getLogger().warning("Trimmer-Ordner konnte nicht angelegt werden: " + error.getMessage()); }
        loadHistory();
        loadToolState();
        importLegacyTrimmer();
        loadPersistentPhase();
    }

    void start() {
        shuttingDown = false;
        scanAsync(null, false);
    }

    void reload() {
        scanAsync(null, false);
    }

    void stop() {
        shuttingDown = true;
        BukkitTask task = countdownTask;
        countdownTask = null;
        if (task != null) try { task.cancel(); } catch (Throwable ignored) { }
        Process process = activeProcess;
        if (process != null && process.isAlive()) {
            process.destroy();
            try { if (!process.waitFor(5L, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); process.destroyForcibly(); }
        }
        activeProcessPid = 0L;
        if (phase.get() == Phase.COUNTDOWN || phase.get() == Phase.PREPARING) {
            phase.set(Phase.IDLE);
        } else if (phase.get() == Phase.RUNNING || phase.get() == Phase.AWAITING_OVERRIDE) {
            phase.set(Phase.RECOVERY);
            recordInterrupted("The server stopped while world trimming or its operator override was active.");
        }
        persistState();
    }

    boolean isInventory(Inventory inventory) {
        return inventory != null && LiveMenus.holder(inventory) instanceof TrimMenu;
    }

    boolean handleInventoryClick(InventoryClickEvent event) {
        if (event == null || !isInventory(event.getInventory())) return false;
        event.setCancelled(true);
        HumanEntity human = event.getWhoClicked();
        if (!(human instanceof Player)) return true;
        Player admin = (Player) human;
        if (!admin.isOp()) {
            admin.closeInventory();
            admin.sendMessage(plugin.prefix() + "Only server operators can use the world trimmer.");
            return true;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getInventory().getSize()) return true;
        TrimMenu menu = (TrimMenu) LiveMenus.holder(event.getInventory());
        if (menu.type.equals("main")) handleMainClick(admin, menu.page, slot);
        else if (menu.type.equals("confirm")) handleConfirmClick(admin, slot);
        else if (menu.type.equals("history")) handleHistoryClick(admin, menu.page, slot);
        else if (menu.type.equals("override")) handleOverrideClick(admin, slot);
        return true;
    }

    void openJava(Player admin) {
        if (!plugin.adminMenuAllowed(admin, "worldtrimmanager.openjava")) return; openJava(admin, 0); }

    void openJava(Player admin, int requestedPage) {
        if (!plugin.adminMenuAllowed(admin, "worldtrimmanager.openjava")) return;
        if (!requireOperator(admin)) return;
        if (phase.get() == Phase.AWAITING_OVERRIDE) {
            if (canAnswerLoadedChunkOverride(admin)) openLoadedChunkOverrideJava(admin);
            else admin.sendMessage(plugin.prefix() + "Die Ausnahmeentscheidung ist ausschließlich für den ausführenden Operator verfügbar.");
            return;
        }
        List<WorldTarget> snapshot = targetsSnapshot();
        int maxPage = Math.max(0, (snapshot.size() - 1) / 45);
        int page = Math.max(0, Math.min(maxPage, requestedPage));
        Inventory inventory = LiveMenus.create(new TrimMenu("main", page), 54, plugin.color("&8Welten-Trimmer"));
        plugin.fill(inventory);
        Session session = session(admin.getUniqueId());
        int start = page * 45;
        for (int i = start; i < Math.min(snapshot.size(), start + 45); i++) {
            WorldTarget target = snapshot.get(i);
            boolean selected = session.selected.contains(target.id);
            inventory.setItem(i - start, plugin.item(selected ? Material.LIME_DYE : Material.GRASS_BLOCK,
                    (selected ? "&a✔ " : "&7") + target.label,
                    "&7Schema: &f" + target.layout,
                    "&7Aktuelle Größe: &f" + formatBytes(target.bytes),
                    "&7Pfad: &8" + shorten(target.folder.toString(), 62),
                    selected ? "&aFür den nächsten Lauf ausgewählt." : "&eKlicken zum Auswählen."));
        }
        if (snapshot.isEmpty()) {
            inventory.setItem(22, plugin.item(Material.CLOCK, "&eWelten werden erkannt …",
                    "&7Legacy-, Bukkit- und moderne Namespaced-Schemata", "&7werden außerhalb des Tick-Threads geprüft."));
        }
        inventory.setItem(45, plugin.item(Material.COMPASS, "&bWelten neu erkennen",
                "&7Aktualisiert Pfade und aktuelle Größen.", "&7Letzter Scan: &f" + age(lastScanAt)));
        inventory.setItem(46, plugin.item(Material.CLOCK, "&eBesuchsgrenze",
                "&7Aktuell: &f" + session.thresholdTicks + " Ticks", "&7Entspricht: &f" + thresholdDuration(session.thresholdTicks),
                "&7Klick wechselt die sichere Vorauswahl."));
        inventory.setItem(47, plugin.item(Material.REDSTONE_TORCH, "&6Countdown",
                "&7Aktuell: &f" + countdownLabel(session.countdownSeconds), "&7Verfügbar: sofort, 1, 3 oder 5 Minuten."));
        inventory.setItem(48, plugin.item(toolReadyFast() ? Material.LIME_DYE : Material.ANVIL,
                "&bMCA Selector aktualisieren", "&7Offizielle Quelle: &fQuerz/mcaselector",
                "&7Installiert: &f" + toolLabel(), "&7Erforderlich: &f" + minimumToolVersion()));
        inventory.setItem(49, plugin.item(Material.BARRIER, "&cZurück", "&7Zurück zur Simplex Admin Suite."));
        inventory.setItem(50, plugin.item(Material.WRITABLE_BOOK, "&dVerlauf",
                "&7Gespeicherte Läufe: &f" + historySize(), "&7Insgesamt freigegeben: &a" + formatBytes(totalSavedBytes()),
                "&7Die letzten 100 Läufe bleiben erhalten."));
        inventory.setItem(51, plugin.item(Material.MAP, "&eStatus",
                "&7" + statusGerman(), "&7Ausgewählt: &f" + session.selected.size() + " Dimension(en)"));
        inventory.setItem(52, plugin.item(Material.CHEST, "&aAlle Dimensionen auswählen",
                "&7Wählt alle aktuell erkannten Ziele aus."));
        if (phase.get() == Phase.COUNTDOWN) {
            inventory.setItem(53, plugin.item(Material.RED_DYE, "&cCountdown abbrechen",
                    "&7Noch &f" + countdownRemaining + " Sekunden&7."));
        } else if (phase.get() == Phase.RECOVERY) {
            inventory.setItem(53, plugin.item(Material.ORANGE_DYE, "&6Recovery prüfen und freigeben",
                    "&7Der vorige Lauf wurde unterbrochen.", "&7Nur nach Prüfung des Backups freigeben."));
        } else {
            inventory.setItem(53, plugin.item(Material.NETHER_STAR, "&aSicherheitsprüfung starten",
                    "&7Auswahl, Werkzeug, Speicherplatz und Backup", "&7werden vor dem Countdown erneut geprüft."));
        }
        LiveMenus.show(admin,inventory);
    }

    void openHistoryJava(Player admin, int requestedPage) {
        if (!plugin.adminMenuAllowed(admin, "worldtrimmanager.openhistoryjava")) return;
        if (!requireOperator(admin)) return;
        List<HistoryEntry> entries = historySnapshot();
        int maxPage = Math.max(0, (entries.size() - 1) / 45);
        int page = Math.max(0, Math.min(maxPage, requestedPage));
        Inventory inventory = LiveMenus.create(new TrimMenu("history", page), 54, plugin.color("&8Trimmer-Verlauf"));
        plugin.fill(inventory);
        int start = page * 45;
        for (int i = start; i < Math.min(entries.size(), start + 45); i++) {
            HistoryEntry entry = entries.get(i);
            Material icon = entry.status.equals("SUCCESS") ? Material.LIME_DYE
                    : entry.status.equals("FAILED") ? Material.RED_DYE : Material.ORANGE_DYE;
            inventory.setItem(i - start, plugin.item(icon, statusColor(entry.status) + formatDate(entry.finishedAt),
                    "&7Status: &f" + statusLabel(entry.status), "&7Operator: &f" + entry.actor,
                    "&7Dimensionen: &f" + entry.targets.size(), "&7Vorher: &f" + formatBytes(entry.beforeBytes),
                    "&7Nachher: &f" + formatBytes(entry.afterBytes), "&7Freigegeben: &a" + formatBytes(entry.savedBytes()),
                    entry.error.isBlank() ? "&7MCA Selector: &f" + entry.toolVersion : "&c" + shorten(entry.error, 65)));
        }
        inventory.setItem(45, plugin.item(Material.ARROW, "&eVorherige Seite", "&7Seite &f" + (page + 1) + "&7/&f" + (maxPage + 1)));
        inventory.setItem(49, plugin.item(Material.BARRIER, "&cZurück", "&7Zurück zum Welten-Trimmer."));
        inventory.setItem(50, plugin.item(Material.CHEST, "&aGesamtersparnis", "&7" + formatBytes(totalSavedBytes())));
        inventory.setItem(53, plugin.item(Material.ARROW, "&eNächste Seite", "&7Seite &f" + (page + 1) + "&7/&f" + (maxPage + 1)));
        LiveMenus.show(admin,inventory);
    }

    private void openConfirmationJava(Player admin) {
        if (!plugin.adminMenuAllowed(admin, "worldtrimmanager.openconfirmationjava")) return;
        if (!requireOperator(admin)) return;
        Session session = session(admin.getUniqueId());
        Inventory inventory = LiveMenus.create(new TrimMenu("confirm", 0), 27, plugin.color("&8Trimmer-Sicherheitsfreigabe"));
        plugin.fill(inventory);
        boolean confirmed = backupConfirmationValid(session);
        inventory.setItem(11, plugin.item(confirmed ? Material.LIME_DYE : Material.ORANGE_DYE,
                confirmed ? "&aBackup bestätigt" : "&6Vollständiges Backup bestätigen",
                "&cChunk-Löschungen sind dauerhaft.", "&7Ein vollständiges, extern geprüftes Backup ist Pflicht.",
                confirmed ? "&aBestätigung ist fünf Minuten gültig." : "&eKlicken, wenn das Backup vorhanden ist."));
        inventory.setItem(13, plugin.item(Material.MAP, "&eZusammenfassung",
                "&7Dimensionen: &f" + session.selected.size(), "&7Besuchsgrenze: &f" + session.thresholdTicks + " Ticks",
                "&7Countdown: &f" + countdownLabel(session.countdownSeconds), "&7Werkzeug: &f" + toolLabel()));
        inventory.setItem(15, plugin.item(confirmed ? Material.NETHER_STAR : Material.GRAY_DYE,
                confirmed ? "&aVerbindlich starten" : "&7Noch gesperrt",
                confirmed ? "&7Startet die vollständige Vorabprüfung." : "&7Zuerst das Backup links bestätigen.",
                "&7Während des Laufs kann niemand beitreten."));
        inventory.setItem(22, plugin.item(Material.BARRIER, "&cAbbrechen", "&7Zurück ohne Änderungen."));
        LiveMenus.show(admin,inventory);
    }

    private void openLoadedChunkOverrideJava(Player admin) {
        if (!canAnswerLoadedChunkOverride(admin)) return;
        Inventory inventory = LiveMenus.create(new TrimMenu("override", 0), 27,
                plugin.color("&8Trimmer-Notfallentscheidung"));
        plugin.fill(inventory);
        inventory.setItem(11, plugin.item(Material.BARRIER, "&aSicher abbrechen",
                "&7Der Trimmer verändert keine Regionsdateien.",
                "&7Welten und Auto-Speichern werden freigegeben."));
        inventory.setItem(13, plugin.item(Material.ORANGE_DYE, "&6" + remainingLoadedChunks + " Chunks verbleiben",
                "&7Mehrere sichere Unload-Versuche waren erfolglos.",
                "&7Ursache können Spawn-, Plugin- oder Force-Load-Tickets sein.",
                "&cEin vollständiges Backup bleibt zwingend erforderlich."));
        inventory.setItem(15, plugin.item(Material.NETHER_STAR, "&cTrotzdem fortfahren",
                "&7Führt einen letzten Save-/Unload-Durchlauf aus",
                "&7und startet MCA Selector anschließend wie der alte Trimmer.",
                "&cNur verwenden, wenn das Backup geprüft wurde."));
        LiveMenus.show(admin,inventory);
    }

    private void handleMainClick(Player admin, int page, int slot) {
        List<WorldTarget> snapshot = targetsSnapshot();
        if (slot >= 0 && slot < 45) {
            int index = page * 45 + slot;
            if (index < snapshot.size() && phase.get() == Phase.IDLE) toggleSelection(admin, snapshot.get(index).id);
            openJava(admin, page);
            return;
        }
        if (slot == 45) scanAsync(admin, true);
        else if (slot == 46) { cycleThreshold(admin); openJava(admin, page); }
        else if (slot == 47) { cycleCountdown(admin); openJava(admin, page); }
        else if (slot == 48) downloadLatest(admin);
        else if (slot == 49) plugin.openJavaAdminMain(admin);
        else if (slot == 50) openHistoryJava(admin, 0);
        else if (slot == 52) { selectAll(admin); openJava(admin, page); }
        else if (slot == 53) {
            if (phase.get() == Phase.COUNTDOWN) abortCountdown(admin);
            else if (phase.get() == Phase.RECOVERY) acknowledgeRecovery(admin);
            else if (phase.get() == Phase.IDLE) openConfirmationJava(admin);
            else admin.sendMessage(plugin.prefix() + "Der Trimmer ist bereits beschäftigt.");
        }
    }

    private void handleConfirmClick(Player admin, int slot) {
        if (slot == 11) {
            markBackupConfirmed(admin);
            openConfirmationJava(admin);
        } else if (slot == 15) {
            if (!backupConfirmationValid(session(admin.getUniqueId()))) {
                admin.sendMessage(plugin.prefix() + "Bestätige zuerst das vollständige Backup.");
                openConfirmationJava(admin);
            } else requestStart(admin);
        } else if (slot == 22) openJava(admin);
    }

    private void handleHistoryClick(Player admin, int page, int slot) {
        if (slot == 45 && page > 0) openHistoryJava(admin, page - 1);
        else if (slot == 53) openHistoryJava(admin, page + 1);
        else if (slot == 49) openJava(admin);
    }

    private void handleOverrideClick(Player admin, int slot) {
        if (!canAnswerLoadedChunkOverride(admin)) {
            admin.closeInventory();
            return;
        }
        if (slot == 11) cancelLoadedChunkOverride(admin);
        else if (slot == 15) continueWithLoadedChunks(admin);
    }

    List<WorldTarget> targetsSnapshot() { return new ArrayList<>(targets); }
    List<HistoryEntry> historySnapshot() { synchronized (history) { return new ArrayList<>(history); } }
    boolean scanning() { return scanRunning.get(); }
    boolean downloading() { return downloadRunning.get(); }
    Phase phase() { return phase.get(); }
    int progress() { return progress; }
    int countdownRemaining() { return countdownRemaining; }
    long remainingLoadedChunks() { return remainingLoadedChunks; }

    boolean selected(Player admin, String targetId) {
        return admin != null && session(admin.getUniqueId()).selected.contains(targetId);
    }

    void toggleSelection(Player admin, String targetId) {
        if (!requireOperator(admin) || phase.get() != Phase.IDLE || targetId == null) return;
        Session session = session(admin.getUniqueId());
        if (!session.selected.add(targetId)) session.selected.remove(targetId);
        session.backupConfirmedAt = 0L;
        saveSession(admin.getUniqueId(), session);
    }

    void selectAll(Player admin) {
        if (!requireOperator(admin) || phase.get() != Phase.IDLE) return;
        Session session = session(admin.getUniqueId());
        session.selected.clear();
        for (WorldTarget target : targetsSnapshot()) session.selected.add(target.id);
        session.backupConfirmedAt = 0L;
        saveSession(admin.getUniqueId(), session);
    }

    void cycleThreshold(Player admin) {
        if (!requireOperator(admin) || phase.get() != Phase.IDLE) return;
        long[] choices = new long[]{600L, 1200L, 3600L, 6000L, 12000L};
        Session session = session(admin.getUniqueId());
        int next = 0;
        for (int i = 0; i < choices.length; i++) if (choices[i] == session.thresholdTicks) next = (i + 1) % choices.length;
        session.thresholdTicks = Math.max(minimumThreshold(), choices[next]);
        session.backupConfirmedAt = 0L;
        saveSession(admin.getUniqueId(), session);
    }

    void cycleCountdown(Player admin) {
        if (!requireOperator(admin) || phase.get() != Phase.IDLE) return;
        int[] choices = new int[]{0, 60, 180, 300};
        Session session = session(admin.getUniqueId());
        int next = 0;
        for (int i = 0; i < choices.length; i++) if (choices[i] == session.countdownSeconds) next = (i + 1) % choices.length;
        session.countdownSeconds = choices[next];
        session.backupConfirmedAt = 0L;
        saveSession(admin.getUniqueId(), session);
    }

    void markBackupConfirmed(Player admin) {
        if (!requireOperator(admin) || phase.get() != Phase.IDLE) return;
        Session session = session(admin.getUniqueId());
        session.backupConfirmedAt = System.currentTimeMillis();
        saveSession(admin.getUniqueId(), session);
        plugin.expansionAudit(admin.getName(), null, "Trimmer-Backup bestätigt", "Bestätigung gilt fünf Minuten");
    }

    boolean backupConfirmed(Player admin) {
        return admin != null && backupConfirmationValid(session(admin.getUniqueId()));
    }

    boolean configureDiscordRun(Player admin, String requestedTargets, long requestedThreshold, int requestedCountdown) {
        if (!requireOperator(admin) || phase.get() != Phase.IDLE) return false;
        List<WorldTarget> available = targetsSnapshot();
        if (available.isEmpty()) {
            plugin.menuNotice(admin,plugin.prefix() + "Es wurden noch keine trimmbaren Dimensionen erkannt. Starte zuerst einen Welten-Scan.");
            return false;
        }
        Set<String> selected = new LinkedHashSet<>();
        String raw = requestedTargets == null ? "" : requestedTargets.trim();
        if (raw.isBlank() || raw.equalsIgnoreCase("all") || raw.equals("*")) {
            for (WorldTarget target : available) selected.add(target.id);
        } else {
            for (String part : raw.split(",")) {
                String needle = part.trim();
                if (needle.isEmpty()) continue;
                WorldTarget match = available.stream().filter(target -> target.id.equalsIgnoreCase(needle)
                        || target.label.equalsIgnoreCase(needle)).findFirst().orElse(null);
                if (match == null) {
                    admin.sendMessage(plugin.prefix() + "Unbekannte Dimension: " + needle + ". Nutze Discord-Autocomplete oder 'all'.");
                    return false;
                }
                selected.add(match.id);
            }
        }
        if (selected.isEmpty()) {
            admin.sendMessage(plugin.prefix() + "Wähle mindestens eine Dimension aus.");
            return false;
        }
        int countdown = requestedCountdown <= 0 ? 0 : requestedCountdown <= 60 ? 60 : requestedCountdown <= 180 ? 180 : 300;
        Session session = session(admin.getUniqueId());
        session.selected.clear();
        session.selected.addAll(selected);
        session.thresholdTicks = Math.max(minimumThreshold(), requestedThreshold);
        session.countdownSeconds = countdown;
        session.backupConfirmedAt = System.currentTimeMillis();
        saveSession(admin.getUniqueId(), session);
        plugin.expansionAudit(admin.getName(), null, "Discord-Trimmer vorbereitet",
                selected.size() + " Dimensionen / Grenze " + session.thresholdTicks + " / Countdown " + countdown + " / Backup ausdrücklich bestätigt");
        admin.sendMessage(plugin.prefix() + "Discord-Trimmer vorbereitet: " + selected.size() + " Dimension(en), "
                + session.thresholdTicks + " Ticks, " + countdownLabel(countdown) + ".");
        return true;
    }

    void requestStart(Player admin) {
        if (!requireOperator(admin)) return;
        if (!plugin.getConfig().getBoolean("world-trimmer.enabled", true)) {
            admin.sendMessage(plugin.prefix() + "Der Welten-Trimmer ist in config.yml deaktiviert.");
            return;
        }
        Session session = session(admin.getUniqueId());
        if (!backupConfirmationValid(session)) {
            admin.sendMessage(plugin.prefix() + "Die Backup-Bestätigung fehlt oder ist abgelaufen.");
            return;
        }
        if (session.selected.isEmpty()) {
            admin.sendMessage(plugin.prefix() + "Wähle mindestens eine Dimension aus.");
            return;
        }
        if (!phase.compareAndSet(Phase.IDLE, Phase.PREPARING)) {
            admin.sendMessage(plugin.prefix() + "Ein Trimmer-Lauf oder Countdown ist bereits aktiv.");
            return;
        }
        if (legacyPluginActive()) {
            phase.set(Phase.IDLE);
            admin.sendMessage(plugin.prefix() + "SimplexTrimmer ist noch separat geladen. Entferne die alte JAR und starte den Server neu, damit niemals zwei Trimmer gleichzeitig arbeiten.");
            return;
        }
        admin.closeInventory();
        admin.sendMessage(plugin.prefix() + "Welten, Größen, Werkzeug und freier Speicher werden sicher geprüft …");
        Set<String> selectedIds = new LinkedHashSet<>(session.selected);
        long threshold = Math.max(minimumThreshold(), session.thresholdTicks);
        int countdown = session.countdownSeconds;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<WorldTarget> fresh = discoverCurrentTargets();
                targets = fresh;
                lastScanAt = System.currentTimeMillis();
                Map<String, WorldTarget> byId = new LinkedHashMap<>();
                for (WorldTarget target : fresh) byId.put(target.id, target);
                List<WorldTarget> selected = new ArrayList<>();
                for (String id : selectedIds) if (byId.containsKey(id)) selected.add(byId.get(id));
                if (selected.isEmpty()) throw new IllegalStateException("Keine der ausgewählten Dimensionen ist noch vorhanden.");
                verifyToolReady();
                Path container = worldContainer();
                FileStore store = Files.getFileStore(container);
                long minimumFree = Math.max(0L, plugin.getConfig().getLong("world-trimmer.preflight.minimum-free-space-gib", 2L)) * 1073741824L;
                if (store.getUsableSpace() < minimumFree) throw new IllegalStateException("Zu wenig freier Speicher für einen abgesicherten Lauf.");
                long before = selected.stream().mapToLong(target -> target.bytes).sum();
                RunPlan plan = new RunPlan(selected, threshold, countdown, admin.getUniqueId(), admin.getName(), before);
                plugin.runAdminTask( () -> acceptPreflight(admin, plan));
            } catch (Exception error) {
                plugin.runAdminTask( () -> failPreflight(admin, cleanReason(error)));
            }
        });
    }

    private void acceptPreflight(Player admin, RunPlan plan) {
        if (shuttingDown || phase.get() != Phase.PREPARING) return;
        pendingPlan = plan;
        operationId = UUID.randomUUID().toString();
        actor = plan.actor;
        actorUuid = plan.actorUuid;
        operationStartedAt = System.currentTimeMillis();
        operationBeforeBytes = plan.beforeBytes;
        remainingLoadedChunks = 0L;
        progress = 0;
        currentTarget = "Preparing";
        if (plan.countdownSeconds > 0) startCountdown(plan);
        else beginMaintenance(plan);
        plugin.menuNotice(admin,plugin.prefix() + (plan.countdownSeconds > 0
                ? "Der sichere Countdown wurde gestartet." : "Der Trimmer wechselt jetzt in den Wartungszustand."));
    }

    private void failPreflight(Player admin, String reason) {
        phase.set(Phase.IDLE);
        pendingPlan = null;
        persistState();
        if (admin != null) admin.sendMessage(plugin.prefix() + "Vorabprüfung abgebrochen: " + reason);
        plugin.getLogger().warning("Trimmer-Vorabprüfung abgebrochen: " + reason);
    }

    private void startCountdown(RunPlan plan) {
        phase.set(Phase.COUNTDOWN);
        countdownRemaining = plan.countdownSeconds;
        currentTarget = "Countdown";
        persistState();
        String publicMessage = publicMessage("world-trimmer.messages.scheduled",
                "World trimming is scheduled. All players will be disconnected in {seconds} seconds.")
                .replace("{seconds}", Integer.toString(countdownRemaining));
        plugin.broadcastPublicText(plugin.prefix() + publicMessage);
        plugin.discordTrimScheduled(labels(plan.targets), plan.countdownSeconds, plan.actor);
        plugin.refreshDiscordStatus();
        countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (phase.get() != Phase.COUNTDOWN) return;
            countdownRemaining--;
            if (countdownRemaining <= 0) {
                BukkitTask old = countdownTask;
                countdownTask = null;
                if (old != null) try { old.cancel(); } catch (Throwable ignored) { }
                beginMaintenance(plan);
                return;
            }
            if (countdownRemaining <= 10 || countdownRemaining == 30 || countdownRemaining == 60 || countdownRemaining % 60 == 0) {
                String text = publicMessage("world-trimmer.messages.countdown", "World trimming starts in {seconds} seconds.")
                        .replace("{seconds}", Integer.toString(countdownRemaining));
                plugin.broadcastPublicText(plugin.prefix() + text);
            }
            for (Player online : Bukkit.getOnlinePlayers()) showOverlay(online, "WORLD MAINTENANCE", "Trimming starts in " + countdownRemaining + " seconds");
            if (countdownRemaining % 10 == 0 || countdownRemaining <= 10) {
                persistState();
                plugin.refreshDiscordStatus();
            }
        }, 20L, 20L);
    }

    void abortCountdown(Player admin) {
        if (!requireOperator(admin)) return;
        if (!phase.compareAndSet(Phase.COUNTDOWN, Phase.IDLE)) {
            admin.sendMessage(plugin.prefix() + "Es läuft kein abbrechbarer Trimmer-Countdown.");
            return;
        }
        BukkitTask task = countdownTask;
        countdownTask = null;
        if (task != null) try { task.cancel(); } catch (Throwable ignored) { }
        pendingPlan = null;
        countdownRemaining = 0;
        actorUuid = null;
        remainingLoadedChunks = 0L;
        operationId = "";
        currentTarget = "";
        persistState();
        plugin.broadcastPublicText(plugin.prefix() + publicMessage("world-trimmer.messages.cancelled", "The scheduled world trim has been cancelled."));
        plugin.discordTrimCancelled(admin.getName());
        plugin.refreshDiscordStatus();
        openJava(admin);
    }

    private void beginMaintenance(RunPlan plan) {
        if (shuttingDown) return;
        phase.set(Phase.RUNNING);
        countdownRemaining = 0;
        operationStartedAt = System.currentTimeMillis();
        currentTarget = "Saving worlds";
        progress = 0;
        persistState();
        plugin.broadcastPublicText(plugin.prefix() + publicMessage("world-trimmer.messages.starting",
                "World trimming is starting now. The server will restart automatically when it is complete."));
        plugin.discordTrimStarted(labels(plan.targets), plan.actor);
        plugin.refreshDiscordStatus();
        for (Player online : new ArrayList<>(Bukkit.getOnlinePlayers())) {
            online.closeInventory();
            String message = maintenanceMessage();
            if (plan.actorUuid != null && plan.actorUuid.equals(online.getUniqueId())) {
                message += "\n\nInitiating operator: reconnect after a few seconds if the server requests a safety decision.";
            }
            online.kickPlayer(message);
        }
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "save-all flush");
        prepareWorldsForExternalWrite();
        Bukkit.getScheduler().runTaskLater(plugin, () -> validateWorldQuiescenceAndRun(plan, 0), 40L);
    }

    private void prepareWorldsForExternalWrite() {
        for (World world : new ArrayList<>(Bukkit.getWorlds())) {
            invokeOptional(world, "save");
            invokeOptional(world, "setAutoSave", false);
            invokeOptional(world, "setKeepSpawnInMemory", false);
            Object chunks = invokeOptional(world, "getLoadedChunks");
            if (chunks == null || !chunks.getClass().isArray()) continue;
            int length = Array.getLength(chunks);
            for (int i = 0; i < length; i++) {
                Object chunk = Array.get(chunks, i);
                Object chunkResult = invokeOptional(chunk, "unload", true);
                if (booleanSuccess(chunkResult)) continue;
                Object x = invokeOptional(chunk, "getX");
                Object z = invokeOptional(chunk, "getZ");
                if (x instanceof Number && z instanceof Number) {
                    Object worldResult = invokeOptional(world, "unloadChunk",
                            ((Number) x).intValue(), ((Number) z).intValue(), true);
                    if (!booleanSuccess(worldResult)) {
                        invokeOptional(world, "unloadChunkRequest", ((Number) x).intValue(), ((Number) z).intValue());
                    }
                }
            }
        }
    }

    private void validateWorldQuiescenceAndRun(RunPlan plan, int attempt) {
        if (phase.get() != Phase.RUNNING || shuttingDown) return;
        long loaded = countLoadedChunks();
        boolean refuse = plugin.getConfig().getBoolean("world-trimmer.preflight.refuse-loaded-chunks", true);
        boolean allowOverride = plugin.getConfig().getBoolean("world-trimmer.preflight.allow-operator-bypass", true);
        int retries = Math.max(0, Math.min(10,
                plugin.getConfig().getInt("world-trimmer.preflight.unload-retries", 3)));
        QuiescenceDecision decision = quiescenceDecision(loaded, refuse, allowOverride, attempt, retries);
        if (decision == QuiescenceDecision.RETRY) {
            plugin.getLogger().warning("Trimmer-Sicherheitsbarriere: " + loaded + " Chunks sind noch geladen; "
                    + "Unload-Versuch " + (attempt + 1) + "/" + retries + " wird ausgeführt.");
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "save-all flush");
            prepareWorldsForExternalWrite();
            long delay = Math.max(10L, Math.min(100L,
                    plugin.getConfig().getLong("world-trimmer.preflight.unload-retry-delay-ticks", 20L)));
            Bukkit.getScheduler().runTaskLater(plugin,
                    () -> validateWorldQuiescenceAndRun(plan, attempt + 1), delay);
            return;
        }
        if (decision == QuiescenceDecision.REQUEST_OVERRIDE) {
            awaitLoadedChunkOverride(plan, loaded);
            return;
        }
        if (decision == QuiescenceDecision.FAIL) {
            failRun(plan, "Safety stop: " + loaded + " chunks remained loaded after " + (attempt + 1)
                    + " save/unload barrier checks, and the operator bypass is disabled.");
            return;
        }
        startSelector(plan, false, loaded);
    }

    private void startSelector(RunPlan plan, boolean bypassed, long loaded) {
        if (phase.get() != Phase.RUNNING || shuttingDown) return;
        remainingLoadedChunks = Math.max(0L, loaded);
        if (bypassed && loaded > 0L) {
            plugin.getLogger().warning("OP-BYPASS AKTIV: MCA Selector startet trotz " + loaded
                    + " weiterhin geladener Chunks. Der ausführende Operator hat dies ausdrücklich bestätigt.");
        } else if (loaded > 0L) {
            plugin.getLogger().warning("Die geladene-Chunk-Sicherheitsbarriere ist in config.yml deaktiviert; "
                    + "MCA Selector startet trotz " + loaded + " geladener Chunks.");
        }
        currentTarget = "Starting MCA Selector";
        persistState();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> processPlan(plan));
    }

    private void awaitLoadedChunkOverride(RunPlan plan, long loaded) {
        pendingPlan = plan;
        remainingLoadedChunks = Math.max(1L, loaded);
        phase.set(Phase.AWAITING_OVERRIDE);
        currentTarget = "Waiting for operator safety decision";
        restoreWorlds();
        persistState();
        plugin.getLogger().warning("Trimmer wartet auf eine OP-Entscheidung: " + loaded
                + " Chunks blieben nach allen Save-/Unload-Versuchen geladen. Der ausführende Operator "
                + plan.actor + " kann sicher abbrechen oder den Notfall-Bypass bestätigen.");
        plugin.expansionAudit(plan.actor, null, "Trimmer-Notfallentscheidung erforderlich",
                loaded + " Chunks nach Save-/Unload-Barriere geladen");
        plugin.discordTrimOverrideRequired(loaded, plan.actor);
        plugin.refreshDiscordStatus();
        Player onlineActor = onlineActor(plan.actorUuid);
        if (onlineActor != null) Bukkit.getScheduler().runTaskLater(plugin,
                () -> showLoadedChunkOverride(onlineActor), 10L);
    }

    boolean handleLoadedChunkOverrideJoin(Player admin) {
        if (!canAnswerLoadedChunkOverride(admin)) return false;
        Bukkit.getScheduler().runTaskLater(plugin, () -> showLoadedChunkOverride(admin), 10L);
        return true;
    }

    boolean canAnswerLoadedChunkOverride(Player admin) {
        return admin != null && admin.isOp() && phase.get() == Phase.AWAITING_OVERRIDE
                && actorUuid != null && actorUuid.equals(admin.getUniqueId()) && pendingPlan != null;
    }

    private void showLoadedChunkOverride(Player admin) {
        if (!canAnswerLoadedChunkOverride(admin)) return;
        admin.sendMessage(plugin.prefix() + "Die Chunk-Sicherheitsbarriere benötigt deine Entscheidung. Verbleibend: "
                + remainingLoadedChunks + " Chunks.");
        if (!plugin.openBedrockWorldTrimOverride(admin, remainingLoadedChunks)) openLoadedChunkOverrideJava(admin);
    }

    void continueWithLoadedChunks(Player admin) {
        if (!canAnswerLoadedChunkOverride(admin)) return;
        if (!plugin.getConfig().getBoolean("world-trimmer.preflight.allow-operator-bypass", true)) {
            plugin.menuNotice(admin,plugin.prefix() + "Der Notfall-Bypass wurde inzwischen in config.yml deaktiviert.");
            return;
        }
        RunPlan plan = pendingPlan;
        long acknowledged = remainingLoadedChunks;
        phase.set(Phase.RUNNING);
        currentTarget = "Operator override: final save/unload pass";
        persistState();
        plugin.getLogger().warning("Der Operator " + admin.getName() + " hat den Trimmer-Notfall-Bypass für "
                + acknowledged + " geladene Chunks ausdrücklich bestätigt.");
        plugin.expansionAudit(admin.getName(), null, "Trimmer-Notfall-Bypass bestätigt",
                acknowledged + " geladene Chunks; letzter Save-/Unload-Durchlauf folgt");
        plugin.discordTrimOverrideAccepted(acknowledged, admin.getName());
        plugin.broadcastPublicText(plugin.prefix() + publicMessage("world-trimmer.messages.override-starting",
                "World maintenance is continuing after an operator safety override. The server remains locked."));
        for (Player online : new ArrayList<>(Bukkit.getOnlinePlayers())) {
            online.closeInventory();
            online.kickPlayer(maintenanceMessage());
        }
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "save-all flush");
        prepareWorldsForExternalWrite();
        plugin.refreshDiscordStatus();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (phase.get() != Phase.RUNNING || shuttingDown) return;
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "save-all flush");
            prepareWorldsForExternalWrite();
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                long stillLoaded = countLoadedChunks();
                startSelector(plan, true, stillLoaded);
            }, 20L);
        }, 20L);
    }

    void cancelLoadedChunkOverride(Player admin) {
        if (!canAnswerLoadedChunkOverride(admin)) return;
        RunPlan plan = pendingPlan;
        long loaded = remainingLoadedChunks;
        HistoryEntry entry = new HistoryEntry(operationId, "CANCELLED", plan.actor, labels(plan.targets),
                plan.thresholdTicks, plan.countdownSeconds, operationStartedAt, System.currentTimeMillis(),
                operationBeforeBytes, operationBeforeBytes, toolVersion,
                "Operator cancelled after " + loaded + " chunks remained loaded.");
        addHistory(entry);
        restoreWorlds();
        phase.set(Phase.IDLE);
        pendingPlan = null;
        remainingLoadedChunks = 0L;
        currentTarget = "";
        progress = 0;
        operationId = "";
        actorUuid = null;
        persistState();
        plugin.getLogger().info("Welten-Trimming wurde von " + admin.getName()
                + " an der Chunk-Sicherheitsbarriere sicher abgebrochen.");
        plugin.expansionAudit(admin.getName(), null, "Trimmer sicher abgebrochen",
                loaded + " Chunks blieben geladen; keine Regionsdateien verändert");
        plugin.discordTrimCancelled(admin.getName());
        plugin.refreshDiscordStatus();
        admin.closeInventory();
        admin.kickPlayer("World trimming was cancelled safely. No region files were modified. Please reconnect.");
    }

    private void processPlan(RunPlan plan) {
        long before = 0L;
        try {
            for (WorldTarget target : plan.targets) before += sizeOfTarget(target.folder);
            operationBeforeBytes = before;
            persistState();
            int count = plan.targets.size();
            for (int index = 0; index < count; index++) {
                WorldTarget target = plan.targets.get(index);
                currentTarget = target.label;
                int completed = index;
                progress = (completed * 100) / count;
                persistState();
                int exit = runMcaSelector(target, plan.thresholdTicks, line -> updateProgress(line, completed, count));
                if (exit != 0) throw new IllegalStateException("MCA Selector exited with code " + exit + " while processing " + target.label + ".");
                progress = ((index + 1) * 100) / count;
                notifyDiscordProgress(true);
            }
            long after = 0L;
            for (WorldTarget target : plan.targets) after += sizeOfTarget(target.folder);
            HistoryEntry entry = new HistoryEntry(operationId, "SUCCESS", plan.actor, labels(plan.targets), plan.thresholdTicks,
                    plan.countdownSeconds, operationStartedAt, System.currentTimeMillis(), before, after, toolVersion, "");
            addHistory(entry);
            phase.set(Phase.RESTARTING);
            pendingPlan = null;
            remainingLoadedChunks = 0L;
            progress = 100;
            currentTarget = "Completed";
            persistState();
            if (!shuttingDown) plugin.runAdminTask( () -> completeSuccessfully(entry));
        } catch (Exception error) {
            failRun(plan, cleanReason(error));
        } finally {
            activeProcess = null;
        }
    }

    private int runMcaSelector(WorldTarget target, long thresholdTicks, java.util.function.Consumer<String> output) throws Exception {
        verifyToolReady();
        String javaName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";
        Path javaBinary = Path.of(System.getProperty("java.home"), "bin", javaName);
        List<String> command = new ArrayList<>();
        command.add(javaBinary.toString());
        command.add("-Xmx" + plugin.getConfig().getString("world-trimmer.performance.heap", "3G"));
        command.add("-Djava.awt.headless=true");
        command.add("-jar");
        command.add(toolFile.toString());
        command.add("--mode"); command.add("delete");
        command.add("--world"); command.add(target.folder.toString());
        command.add("--region"); command.add(target.region.toString());
        if (target.hasPoi()) { command.add("--poi"); command.add(target.poi.toString()); }
        if (target.hasEntities()) { command.add("--entities"); command.add(target.entities.toString()); }
        command.add("--query"); command.add("InhabitedTime < " + thresholdTicks);
        command.add("--process-threads"); command.add(Integer.toString(Math.max(1, Math.min(4,
                plugin.getConfig().getInt("world-trimmer.performance.process-threads", 2)))));
        command.add("--write-threads"); command.add(Integer.toString(Math.max(1, Math.min(2,
                plugin.getConfig().getInt("world-trimmer.performance.write-threads", 1)))));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        activeProcess = process;
        activeProcessPid = process.pid();
        persistState();
        try {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) output.accept(line);
            }
            return process.waitFor();
        } finally {
            activeProcessPid = 0L;
        }
    }

    private void updateProgress(String line, int completedTargets, int totalTargets) {
        String clean = line == null ? "" : line.replace('\n', ' ').replace('\r', ' ');
        if (!clean.isBlank()) plugin.getLogger().info("[MCA Selector] " + clean);
        Matcher matcher = PERCENT.matcher(clean);
        if (!matcher.find()) return;
        int local = Math.max(0, Math.min(100, Integer.parseInt(matcher.group(1))));
        progress = Math.max(0, Math.min(100, (int) Math.floor(((completedTargets + local / 100D) / totalTargets) * 100D)));
        notifyDiscordProgress(false);
        if (progress % 5 == 0) persistState();
    }

    private void notifyDiscordProgress(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && progress < lastDiscordProgress + 10 && now - lastDiscordProgressAt < 30_000L) return;
        lastDiscordProgress = progress;
        lastDiscordProgressAt = now;
        plugin.discordTrimProgress(currentTarget, progress, eta());
        plugin.refreshDiscordStatus();
    }

    private void completeSuccessfully(HistoryEntry entry) {
        plugin.getLogger().info("World trimming completed. Space saved: " + formatBytes(entry.savedBytes()));
        plugin.expansionAudit(entry.actor, null, "Welten-Trimming abgeschlossen",
                entry.targets.size() + " Dimensionen, " + formatBytes(entry.savedBytes()) + " freigegeben");
        plugin.discordTrimFinished(entry.savedBytes(), entry.beforeBytes, entry.afterBytes, entry.targets);
        plugin.refreshDiscordStatus();
        plugin.restartImmediatelyAfterTrim(entry.actor);
    }

    private void failRun(RunPlan plan, String reason) {
        if (shuttingDown) {
            phase.set(Phase.RECOVERY);
            recordInterrupted("The server stopped while the active trim process was being terminated.");
            persistState();
            return;
        }
        long after = 0L;
        try { for (WorldTarget target : plan.targets) after += sizeOfTarget(target.folder); }
        catch (Exception ignored) { }
        HistoryEntry entry = new HistoryEntry(operationId, "FAILED", plan.actor, labels(plan.targets), plan.thresholdTicks,
                plan.countdownSeconds, operationStartedAt, System.currentTimeMillis(), operationBeforeBytes, after,
                toolVersion, reason);
        addHistory(entry);
        boolean restart = plugin.getConfig().getBoolean("world-trimmer.restart-after-failure", true);
        phase.set(restart ? Phase.RESTARTING : Phase.RECOVERY);
        pendingPlan = null;
        remainingLoadedChunks = 0L;
        currentTarget = "Failed";
        persistState();
        plugin.getLogger().severe("World trimming failed: " + reason);
        if (!shuttingDown) plugin.runAdminTask( () -> {
            plugin.discordTrimFailed(reason, entry.targets);
            plugin.expansionAudit(entry.actor, null, "Welten-Trimming fehlgeschlagen", reason);
            plugin.refreshDiscordStatus();
            if (restart) plugin.restartImmediatelyAfterTrim(entry.actor);
            else restoreWorlds();
        });
    }

    private void restoreWorlds() {
        for (World world : Bukkit.getWorlds()) {
            invokeOptional(world, "setAutoSave", true);
            invokeOptional(world, "setKeepSpawnInMemory", true);
            invokeOptional(world, "save");
        }
    }

    void acknowledgeRecovery(Player admin) {
        if (!requireOperator(admin) || phase.get() != Phase.RECOVERY) return;
        if (processAlive(activeProcessPid)) {
            admin.sendMessage(plugin.prefix() + "Recovery bleibt gesperrt: Der frühere MCA-Selector-Prozess läuft noch (PID " + activeProcessPid + ").");
            return;
        }
        phase.set(Phase.IDLE);
        operationId = "";
        currentTarget = "";
        progress = 0;
        pendingPlan = null;
        actorUuid = null;
        remainingLoadedChunks = 0L;
        persistState();
        plugin.expansionAudit(admin.getName(), null, "Trimmer-Recovery freigegeben", "Backup und Serverstart wurden durch OP geprüft");
        plugin.refreshDiscordStatus();
        plugin.menuNotice(admin,plugin.prefix() + "Der Recovery-Zustand wurde nach deiner Prüfung freigegeben.");
        openJava(admin);
    }

    String loginDenial(Player player) {
        Phase current = phase.get();
        if (current == Phase.RUNNING || current == Phase.RESTARTING) return maintenanceMessage();
        if (current == Phase.AWAITING_OVERRIDE) {
            if (player != null && player.isOp() && actorUuid != null
                    && actorUuid.equals(player.getUniqueId()) && pendingPlan != null) return null;
            return publicMessage("world-trimmer.messages.override-waiting",
                    "World maintenance is waiting for the initiating server operator to complete a safety decision.");
        }
        if (current == Phase.RECOVERY && (player == null || !player.isOp())) {
            return publicMessage("world-trimmer.messages.recovery",
                    "World maintenance recovery is active. Please try again after a server operator has completed the safety check.");
        }
        return null;
    }

    TrimStatus discordStatus() {
        return new TrimStatus(phase.get(), progress, countdownRemaining, currentTarget, eta(), operationId);
    }

    String statusGerman() {
        if (!plugin.getConfig().getBoolean("world-trimmer.enabled", true)) return "In config.yml deaktiviert";
        return switch (phase.get()) {
            case IDLE -> scanRunning.get() ? "Welterkennung läuft" : "Bereit";
            case PREPARING -> "Sicherheitsprüfung läuft";
            case COUNTDOWN -> "Countdown: " + countdownRemaining + " Sekunden";
            case RUNNING -> "Trimming: " + progress + "% • " + currentTarget + " • Restzeit " + eta();
            case AWAITING_OVERRIDE -> "Wartet auf Notfallentscheidung von " + actor + " • " + remainingLoadedChunks + " Chunks";
            case RESTARTING -> "Abgeschlossen; Server-Neustart läuft";
            case RECOVERY -> "Sicherheitsprüfung durch Operator erforderlich";
        };
    }

    String bedrockMainContent(Player admin) {
        Session session = session(admin.getUniqueId());
        return "§fStatus: §e" + statusGerman() + "\n"
                + "§fErkannte Dimensionen: §e" + targets.size() + "\n"
                + "§fAusgewählt: §e" + session.selected.size() + "\n"
                + "§fBesuchsgrenze: §e" + session.thresholdTicks + " Ticks §f(" + thresholdDuration(session.thresholdTicks) + ")\n"
                + "§fCountdown: §e" + countdownLabel(session.countdownSeconds) + "\n"
                + "§fMCA Selector: §e" + toolLabel() + "\n"
                + "§fVerlauf: §e" + historySize() + "/100 §8• §fGespart: §a" + formatBytes(totalSavedBytes()) + "\n\n"
                + "§cVor jedem Lauf ist ein vollständiges Backup erforderlich. Während des Trimmens kann niemand beitreten.";
    }

    String bedrockHistoryContent() {
        StringBuilder text = new StringBuilder("§fInsgesamt freigegeben: §a").append(formatBytes(totalSavedBytes())).append("\n\n");
        List<HistoryEntry> entries = historySnapshot();
        if (entries.isEmpty()) return text.append("§7Noch keine Läufe gespeichert.").toString();
        for (int i = 0; i < Math.min(20, entries.size()); i++) {
            HistoryEntry entry = entries.get(i);
            text.append(entry.status.equals("SUCCESS") ? "§a✔ " : entry.status.equals("FAILED") ? "§c✖ " : "§6⚠ ")
                    .append(formatDate(entry.finishedAt)).append(" §8• §f").append(entry.actor)
                    .append(" §8• §a").append(formatBytes(entry.savedBytes())).append("\n");
        }
        if (entries.size() > 20) text.append("\n§7Weitere Einträge sind im Java-Menü und in history.yml verfügbar.");
        return text.toString();
    }

    void scanAsync(Player reopenFor, boolean reopenMenu) {
        if (!scanRunning.compareAndSet(false, true)) {
            if (reopenFor != null) reopenFor.sendMessage(plugin.prefix() + "Die Welterkennung läuft bereits.");
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<WorldTarget> fresh = discoverCurrentTargets();
                migrateLegacySessionsIfPending(fresh);
                targets = fresh;
                lastScanAt = System.currentTimeMillis();
                if (reopenFor != null) plugin.runAdminTask( () -> {
                    reopenFor.sendMessage(plugin.prefix() + targets.size() + " Dimensionen wurden sicher erkannt und vermessen.");
                    if (reopenMenu) openJava(reopenFor);
                });
            } catch (Exception error) {
                plugin.getLogger().warning("Welterkennung fehlgeschlagen: " + cleanReason(error));
                if (reopenFor != null) plugin.runAdminTask(
                        () -> reopenFor.sendMessage(plugin.prefix() + "Welterkennung fehlgeschlagen: " + cleanReason(error)));
            } finally { scanRunning.set(false); }
        });
    }

    void downloadLatest(Player admin) {
        if (!requireOperator(admin)) return;
        if (!plugin.getConfig().getBoolean("world-trimmer.enabled", true)) {
            admin.sendMessage(plugin.prefix() + "Der Welten-Trimmer ist in config.yml deaktiviert.");
            return;
        }
        if (phase.get() != Phase.IDLE) {
            plugin.menuNotice(admin,plugin.prefix() + "Das Werkzeug kann nur im Bereitschaftszustand aktualisiert werden.");
            return;
        }
        if (!downloadRunning.compareAndSet(false, true)) {
            admin.sendMessage(plugin.prefix() + "Der offizielle Download läuft bereits.");
            return;
        }
        admin.closeInventory();
        admin.sendMessage(plugin.prefix() + "Die neueste offizielle MCA-Selector-Version wird mit fester Herkunft und Hash-Prüfung installiert …");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Release release = requestLatestRelease();
                installRelease(release);
                plugin.runAdminTask( () -> {
                    plugin.menuNotice(admin,plugin.prefix() + "MCA Selector " + release.version + " wurde geprüft und atomar installiert.");
                    plugin.expansionAudit(admin.getName(), null, "MCA Selector aktualisiert", release.version + " • " + toolSha256);
                    openJava(admin);
                });
            } catch (Exception error) {
                plugin.runAdminTask( () -> plugin.menuNotice(admin,plugin.prefix() + "Werkzeug-Update fehlgeschlagen: " + cleanReason(error)));
            } finally { downloadRunning.set(false); }
        });
    }

    private Release requestLatestRelease() throws Exception {
        String repository = plugin.getConfig().getString("world-trimmer.tool.repository", "Querz/mcaselector").trim();
        if (!repository.equals("Querz/mcaselector")) throw new IOException("Aus Sicherheitsgründen ist ausschließlich Querz/mcaselector zugelassen.");
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.github.com/repos/Querz/mcaselector/releases/latest"))
                .timeout(Duration.ofSeconds(30)).header("Accept", "application/vnd.github+json")
                .header("User-Agent", "SimplexAdminSuite/7.2.0").GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) throw new IOException("GitHub returned HTTP " + response.statusCode() + ".");
        Matcher tag = RELEASE_TAG.matcher(response.body());
        Matcher jar = RELEASE_JAR.matcher(response.body());
        if (!tag.find() || !jar.find()) throw new IOException("Kein offizielles Universal-JAR im neuesten Release gefunden.");
        String version = normalizeVersion(tag.group(1));
        if (compareVersions(version, minimumToolVersion()) < 0) throw new IOException("Das neueste Release ist älter als " + minimumToolVersion() + ".");
        String url = jar.group(1).replace("\\/", "/");
        if (!url.startsWith("https://github.com/Querz/mcaselector/releases/download/")) throw new IOException("Unerwartete Downloadquelle.");
        return new Release(version, URI.create(url));
    }

    private void installRelease(Release release) throws Exception {
        Files.createDirectories(toolFolder);
        Path temporary = toolFolder.resolve("mcaselector.jar.download");
        Files.deleteIfExists(temporary);
        HttpRequest request = HttpRequest.newBuilder(release.uri).timeout(Duration.ofMinutes(5))
                .header("User-Agent", "SimplexAdminSuite/7.2.0").GET().build();
        HttpResponse<Path> response = http.send(request, HttpResponse.BodyHandlers.ofFile(temporary));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            Files.deleteIfExists(temporary);
            throw new IOException("Download returned HTTP " + response.statusCode() + ".");
        }
        long size = Files.size(temporary);
        if (size < 500_000L || size > 200_000_000L) throw new IOException("Die JAR-Größe liegt außerhalb des Sicherheitsbereichs.");
        validateToolArchive(temporary);
        String hash = sha256(temporary);
        try { Files.move(temporary, toolFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, toolFile, StandardCopyOption.REPLACE_EXISTING); }
        toolVersion = release.version;
        toolSha256 = hash;
        saveToolState();
    }

    private void verifyToolReady() throws Exception {
        if (!Files.isRegularFile(toolFile)) throw new IOException("MCA Selector ist nicht installiert.");
        validateToolArchive(toolFile);
        if (toolVersion.isBlank()) toolVersion = detectToolVersion(toolFile);
        if (toolVersion.isBlank() || compareVersions(toolVersion, minimumToolVersion()) < 0) {
            throw new IOException("MCA Selector " + (toolVersion.isBlank() ? "unbekannt" : toolVersion)
                    + " ist zu alt; mindestens " + minimumToolVersion() + " ist erforderlich.");
        }
        if (!toolSha256.isBlank() && !toolSha256.equalsIgnoreCase(sha256(toolFile))) {
            throw new IOException("Die Werkzeug-JAR stimmt nicht mehr mit ihrem gespeicherten SHA-256 überein.");
        }
    }

    private boolean toolReadyFast() {
        return Files.isRegularFile(toolFile) && !toolVersion.isBlank()
                && compareVersions(toolVersion, minimumToolVersion()) >= 0;
    }

    private String toolLabel() {
        if (downloadRunning.get()) return "Download läuft";
        if (!Files.isRegularFile(toolFile)) return "nicht installiert";
        String version = toolVersion.isBlank() ? "Version unbekannt" : toolVersion;
        return toolReadyFast() ? version + " • installiert" : version + " • Update erforderlich";
    }

    private void validateToolArchive(Path path) throws Exception {
        boolean classes = false;
        try (ZipFile zip = new ZipFile(path.toFile())) {
            if (zip.getEntry("META-INF/MANIFEST.MF") == null) throw new IOException("Die Datei ist kein ausführbares JAR.");
            classes = zip.stream().anyMatch(entry -> entry.getName().startsWith("net/querz/mcaselector/") && entry.getName().endsWith(".class"));
        }
        if (!classes) throw new IOException("Die JAR enthält keine MCA-Selector-Klassen.");
    }

    private String detectToolVersion(Path path) {
        try (JarFile jar = new JarFile(path.toFile())) {
            Attributes attributes = jar.getManifest().getMainAttributes();
            for (String key : List.of("Implementation-Version", "Specification-Version", "Bundle-Version")) {
                String value = attributes.getValue(key);
                if (value != null && !normalizeVersion(value).isBlank()) return normalizeVersion(value);
            }
        } catch (Exception ignored) { }
        return "";
    }

    private List<WorldTarget> discoverCurrentTargets() throws IOException {
        List<Path> loaded = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            Object value = invokeOptional(world, "getWorldFolder");
            if (value instanceof File) loaded.add(((File) value).toPath());
            else if (value instanceof Path) loaded.add((Path) value);
        }
        return discoverTargets(worldContainer(), loaded);
    }

    private Path worldContainer() throws IOException {
        try {
            Method method = Bukkit.class.getMethod("getWorldContainer");
            Object value = method.invoke(null);
            if (value instanceof File) return ((File) value).toPath().toAbsolutePath().normalize();
            if (value instanceof Path) return ((Path) value).toAbsolutePath().normalize();
        } catch (Throwable ignored) { }
        for (World world : Bukkit.getWorlds()) {
            Object value = invokeOptional(world, "getWorldFolder");
            Path folder = value instanceof File ? ((File) value).toPath() : value instanceof Path ? (Path) value : null;
            if (folder != null && folder.toAbsolutePath().getParent() != null) return folder.toAbsolutePath().getParent().normalize();
        }
        return Path.of(".").toAbsolutePath().normalize();
    }

    static List<WorldTarget> discoverTargets(Path container, Collection<Path> loadedWorldRoots) throws IOException {
        Path normalizedContainer = safeRealPath(container);
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        if (Files.isDirectory(normalizedContainer)) {
            if (looksLikeWorldRoot(normalizedContainer)) roots.add(normalizedContainer);
            try (Stream<Path> children = Files.list(normalizedContainer)) {
                children.filter(Files::isDirectory).filter(WorldTrimManager::looksLikeWorldRoot).forEach(roots::add);
            }
        }
        if (loadedWorldRoots != null) {
            for (Path loaded : loadedWorldRoots) {
                if (loaded == null || !Files.isDirectory(loaded)) continue;
                roots.add(safeRealPath(loaded));
            }
        }
        Map<Path, WorldTarget> found = new LinkedHashMap<>();
        for (Path candidate : roots) {
            try {
                Path root = safeRealPath(candidate);
                addDimension(found, normalizedContainer, root, root, "Bukkit-/Legacy-Weltwurzel");
                addDimension(found, normalizedContainer, root, root.resolve("DIM-1"), "Legacy DIM-1 (Nether)");
                addDimension(found, normalizedContainer, root, root.resolve("DIM1"), "Legacy DIM1 (End)");
                Path dimensions = root.resolve("dimensions");
                if (Files.isDirectory(dimensions)) {
                    try (Stream<Path> stream = Files.walk(dimensions, 10)) {
                        List<Path> regions = stream.filter(Files::isDirectory)
                                .filter(path -> path.getFileName() != null && path.getFileName().toString().equals("region"))
                                .toList();
                        for (Path region : regions) {
                            Path dimension = region.getParent();
                            String relative = normalizePath(root.relativize(dimension));
                            String layout = relative.startsWith("dimensions/minecraft/")
                                    ? "Modern Namespaced (1.21/26.x)" : "Namespaced Custom Dimension";
                            addDimension(found, normalizedContainer, root, dimension, layout);
                        }
                    }
                }
            } catch (IOException ignored) { }
        }
        List<WorldTarget> result = new ArrayList<>(found.values());
        result.sort(Comparator.comparing(target -> target.label.toLowerCase(Locale.ROOT)));
        return result;
    }

    private static void addDimension(Map<Path, WorldTarget> found, Path container, Path root, Path folder, String layout) {
        try {
            if (!Files.isDirectory(folder.resolve("region"))) return;
            Path realRoot = safeRealPath(root);
            Path realFolder = safeRealPath(folder);
            if (!realFolder.startsWith(realRoot)) return;
            Path region = safeRealPath(folder.resolve("region"));
            if (!region.startsWith(realFolder)) return;
            String relativeRoot = realRoot.startsWith(container) ? normalizePath(container.relativize(realRoot)) : realRoot.getFileName().toString();
            String relativeDimension = normalizePath(realRoot.relativize(realFolder));
            String suffix = relativeDimension.isBlank() ? "Overworld" : dimensionLabel(relativeDimension);
            String label = (relativeRoot.isBlank() ? realRoot.getFileName().toString() : relativeRoot) + " • " + suffix;
            String stable = normalizePath(realFolder.startsWith(container) ? container.relativize(realFolder) : realFolder);
            String id = shortHash(stable.toLowerCase(Locale.ROOT));
            long size = sizeOfTarget(realFolder);
            WorldTarget target = new WorldTarget(id, label, realRoot, realFolder, region,
                    realFolder.resolve("poi"), realFolder.resolve("entities"), layout, size);
            found.putIfAbsent(region, target);
        } catch (Exception ignored) { }
    }

    private static boolean looksLikeWorldRoot(Path path) {
        return Files.isRegularFile(path.resolve("level.dat")) || Files.isRegularFile(path.resolve("uid.dat"))
                || Files.isRegularFile(path.resolve("session.lock")) || Files.isDirectory(path.resolve("region"))
                || Files.isDirectory(path.resolve("DIM-1/region")) || Files.isDirectory(path.resolve("DIM1/region"))
                || Files.isDirectory(path.resolve("dimensions"));
    }

    static long sizeOfTarget(Path folder) throws IOException {
        long total = 0L;
        for (String child : List.of("region", "poi", "entities")) {
            Path directory = folder.resolve(child);
            if (!Files.isDirectory(directory)) continue;
            try (Stream<Path> files = Files.walk(directory)) {
                total += files.filter(Files::isRegularFile).filter(path -> {
                    String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                    return name.endsWith(".mca") || name.endsWith(".mcc");
                }).mapToLong(path -> {
                    try { return Files.size(path); } catch (IOException ignored) { return 0L; }
                }).sum();
            }
        }
        return total;
    }

    private long countLoadedChunks() {
        long total = 0L;
        for (World world : Bukkit.getWorlds()) {
            Object chunks = invokeOptional(world, "getLoadedChunks");
            if (chunks != null && chunks.getClass().isArray()) total += Array.getLength(chunks);
        }
        return total;
    }

    static QuiescenceDecision quiescenceDecision(long loadedChunks, boolean refuseLoadedChunks,
                                                  boolean allowOperatorBypass, int attempt, int retries) {
        if (loadedChunks <= 0L || !refuseLoadedChunks) return QuiescenceDecision.PROCEED;
        if (Math.max(0, attempt) < Math.max(0, retries)) return QuiescenceDecision.RETRY;
        return allowOperatorBypass ? QuiescenceDecision.REQUEST_OVERRIDE : QuiescenceDecision.FAIL;
    }

    static boolean booleanSuccess(Object result) {
        return result instanceof Boolean && ((Boolean) result);
    }

    private Player onlineActor(UUID uuid) {
        if (uuid == null) return null;
        for (Player player : Bukkit.getOnlinePlayers()) if (uuid.equals(player.getUniqueId())) return player;
        return null;
    }

    private Session session(UUID uuid) {
        return sessions.computeIfAbsent(uuid, key -> {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(sessionsFile);
            String base = "sessions." + key;
            long threshold = yaml.getLong(base + ".threshold-ticks",
                    plugin.getConfig().getLong("world-trimmer.default-threshold-ticks", 1200L));
            int countdown = yaml.getInt(base + ".countdown-seconds", 180);
            long backup = yaml.getLong(base + ".backup-confirmed-at", 0L);
            return new Session(new LinkedHashSet<>(yaml.getStringList(base + ".targets")), threshold, countdown, backup);
        });
    }

    private synchronized void saveSession(UUID uuid, Session session) {
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(sessionsFile);
            String base = "sessions." + uuid;
            yaml.set(base + ".targets", new ArrayList<>(session.selected));
            yaml.set(base + ".threshold-ticks", session.thresholdTicks);
            yaml.set(base + ".countdown-seconds", session.countdownSeconds);
            yaml.set(base + ".backup-confirmed-at", session.backupConfirmedAt);
            saveAtomic(yaml, sessionsFile);
        } catch (Exception error) {
            plugin.getLogger().warning("Trimmer-Sitzung konnte nicht gespeichert werden: " + error.getMessage());
        }
    }

    private void loadPersistentPhase() {
        if (!stateFile.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(stateFile);
        Phase saved;
        try { saved = Phase.valueOf(yaml.getString("phase", "IDLE").toUpperCase(Locale.ROOT)); }
        catch (Exception ignored) { saved = Phase.RECOVERY; }
        operationId = yaml.getString("operation.id", "");
        actor = yaml.getString("operation.actor", "System");
        String savedActorUuid = yaml.getString("operation.actor-uuid", "");
        try { actorUuid = savedActorUuid.isBlank() ? null : UUID.fromString(savedActorUuid); }
        catch (IllegalArgumentException ignored) { actorUuid = null; }
        operationStartedAt = yaml.getLong("operation.started-at", 0L);
        operationBeforeBytes = yaml.getLong("operation.before-bytes", 0L);
        remainingLoadedChunks = yaml.getLong("operation.remaining-loaded-chunks", 0L);
        progress = yaml.getInt("operation.progress", 0);
        currentTarget = yaml.getString("operation.current-target", "");
        countdownRemaining = yaml.getInt("operation.countdown-seconds", 0);
        activeProcessPid = yaml.getLong("operation.child-pid", 0L);
        if (saved == Phase.RUNNING || saved == Phase.AWAITING_OVERRIDE) {
            phase.set(Phase.RECOVERY);
            recordInterrupted("The previous server process ended while world trimming or its operator override was active.");
        } else if (saved == Phase.RECOVERY) phase.set(Phase.RECOVERY);
        else phase.set(Phase.IDLE);
        persistState();
    }

    private synchronized void persistState() {
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("schema-version", 2);
            yaml.set("phase", phase.get().name());
            yaml.set("operation.id", operationId);
            yaml.set("operation.actor", actor);
            yaml.set("operation.actor-uuid", actorUuid == null ? "" : actorUuid.toString());
            yaml.set("operation.started-at", operationStartedAt);
            yaml.set("operation.before-bytes", operationBeforeBytes);
            yaml.set("operation.remaining-loaded-chunks", remainingLoadedChunks);
            yaml.set("operation.progress", progress);
            yaml.set("operation.current-target", currentTarget);
            yaml.set("operation.countdown-seconds", countdownRemaining);
            yaml.set("operation.child-pid", activeProcessPid);
            yaml.set("operation.last-heartbeat", System.currentTimeMillis());
            saveAtomic(yaml, stateFile);
        } catch (Exception error) {
            plugin.getLogger().severe("Trimmer-Sicherheitszustand konnte nicht gespeichert werden: " + error.getMessage());
        }
    }

    private void loadHistory() {
        synchronized (history) {
            history.clear();
            if (!historyFile.exists()) return;
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(historyFile);
            int count = Math.max(0, Math.min(100, yaml.getInt("count", 0)));
            for (int i = 0; i < count; i++) {
                String base = "entries." + i;
                history.add(new HistoryEntry(yaml.getString(base + ".id", UUID.randomUUID().toString()),
                        yaml.getString(base + ".status", "UNKNOWN"), yaml.getString(base + ".actor", "System"),
                        yaml.getStringList(base + ".targets"), yaml.getLong(base + ".threshold-ticks", 0L),
                        yaml.getInt(base + ".countdown-seconds", 0), yaml.getLong(base + ".started-at", 0L),
                        yaml.getLong(base + ".finished-at", 0L), yaml.getLong(base + ".before-bytes", 0L),
                        yaml.getLong(base + ".after-bytes", 0L), yaml.getString(base + ".tool-version", ""),
                        yaml.getString(base + ".error", "")));
            }
        }
    }

    private void addHistory(HistoryEntry entry) {
        synchronized (history) {
            history.removeIf(existing -> existing.id.equals(entry.id));
            history.add(0, entry);
            while (history.size() > Math.max(1, Math.min(100,
                    plugin.getConfig().getInt("world-trimmer.history-limit", 100)))) history.remove(history.size() - 1);
            saveHistory();
        }
    }

    private void saveHistory() {
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("schema-version", 1);
            yaml.set("count", history.size());
            for (int i = 0; i < history.size(); i++) {
                HistoryEntry entry = history.get(i);
                String base = "entries." + i;
                yaml.set(base + ".id", entry.id); yaml.set(base + ".status", entry.status);
                yaml.set(base + ".actor", entry.actor); yaml.set(base + ".targets", entry.targets);
                yaml.set(base + ".threshold-ticks", entry.thresholdTicks); yaml.set(base + ".countdown-seconds", entry.countdownSeconds);
                yaml.set(base + ".started-at", entry.startedAt); yaml.set(base + ".finished-at", entry.finishedAt);
                yaml.set(base + ".before-bytes", entry.beforeBytes); yaml.set(base + ".after-bytes", entry.afterBytes);
                yaml.set(base + ".saved-bytes", entry.savedBytes()); yaml.set(base + ".tool-version", entry.toolVersion);
                yaml.set(base + ".error", entry.error);
            }
            saveAtomic(yaml, historyFile);
        } catch (Exception error) {
            plugin.getLogger().warning("Trimmer-Verlauf konnte nicht gespeichert werden: " + error.getMessage());
        }
    }

    private void recordInterrupted(String reason) {
        if (operationId.isBlank()) operationId = UUID.randomUUID().toString();
        synchronized (history) {
            for (HistoryEntry entry : history) if (entry.id.equals(operationId)) return;
        }
        addHistory(new HistoryEntry(operationId, "INTERRUPTED", actor, List.of(currentTarget), 0L, 0,
                operationStartedAt, System.currentTimeMillis(), operationBeforeBytes, 0L, toolVersion, reason));
    }

    private void loadToolState() {
        if (!toolStateFile.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(toolStateFile);
        toolVersion = normalizeVersion(yaml.getString("version", ""));
        toolSha256 = yaml.getString("sha256", "").trim().toLowerCase(Locale.ROOT);
    }

    private void saveToolState() {
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("version", toolVersion); yaml.set("sha256", toolSha256);
            yaml.set("source", "https://github.com/Querz/mcaselector"); yaml.set("installed-at", System.currentTimeMillis());
            saveAtomic(yaml, toolStateFile);
        } catch (Exception error) { plugin.getLogger().warning("Trimmer-Werkzeugstatus konnte nicht gespeichert werden: " + error.getMessage()); }
    }

    private void importLegacyTrimmer() {
        try {
            YamlConfiguration migration = YamlConfiguration.loadConfiguration(migrationFile);
            if (migration.getBoolean("simplextrimmer-imported", false)) return;
            File pluginsFolder = plugin.getDataFolder().getParentFile();
            if (pluginsFolder == null) return;
            Path legacy = pluginsFolder.toPath().resolve("SimplexTrimmer");
            if (!Files.isDirectory(legacy)) {
                migration.set("simplextrimmer-imported", true);
                migration.set("legacy-sessions-migrated", true);
                migration.set("checked-at", System.currentTimeMillis());
                saveAtomic(migration, migrationFile);
                return;
            }
            Files.createDirectories(folder.resolve("migration-backups"));
            Path legacyState = legacy.resolve("state.yml");
            if (Files.isRegularFile(legacyState)) {
                Files.copy(legacyState, folder.resolve("migration-backups/SimplexTrimmer-state.yml"), StandardCopyOption.REPLACE_EXISTING);
                YamlConfiguration old = YamlConfiguration.loadConfiguration(legacyState.toFile());
                long before = old.getLong("report.before-bytes", 0L);
                long after = old.getLong("report.after-bytes", 0L);
                long finished = old.getLong("report.finished-at", 0L);
                if (before > 0L || finished > 0L) addHistory(new HistoryEntry("legacy-" + Math.max(1L, finished), "SUCCESS",
                        "SimplexTrimmer import", old.getStringList("report.targets"), 0L, 0,
                        0L, finished, before, after, "legacy", "Imported without modifying the original data."));
                if (!old.getStringList("active.targets").isEmpty()) {
                    long legacyStarted = old.getLong("active.started-at", 0L);
                    addHistory(new HistoryEntry("legacy-active-" + Math.max(1L, legacyStarted),
                        "INTERRUPTED", "SimplexTrimmer import", old.getStringList("active.targets"),
                        old.getLong("active.threshold-ticks", 0L), 0, legacyStarted,
                        System.currentTimeMillis(), old.getLong("active.before-bytes", 0L), 0L, "legacy",
                        "An unfinished legacy operation was detected during migration."));
                }
            }
            Path legacyTool = legacy.resolve("tools/mcaselector.jar");
            if (!Files.isRegularFile(toolFile) && Files.isRegularFile(legacyTool)) {
                Files.copy(legacyTool, toolFile, StandardCopyOption.REPLACE_EXISTING);
                toolVersion = detectToolVersion(toolFile);
                toolSha256 = sha256(toolFile);
                saveToolState();
            }
            migration.set("simplextrimmer-imported", true);
            migration.set("legacy-sessions-migrated", !Files.isRegularFile(legacyState));
            migration.set("source", legacy.toString());
            migration.set("imported-at", System.currentTimeMillis());
            saveAtomic(migration, migrationFile);
            plugin.getLogger().info("Vorhandene SimplexTrimmer-Daten wurden kopierend in die integrierte Migration übernommen.");
        } catch (Exception error) {
            plugin.getLogger().warning("SimplexTrimmer-Migration konnte nicht vollständig abgeschlossen werden: " + error.getMessage());
        }
    }

    private void migrateLegacySessionsIfPending(List<WorldTarget> discovered) {
        try {
            YamlConfiguration migration = YamlConfiguration.loadConfiguration(migrationFile);
            if (migration.getBoolean("legacy-sessions-migrated", false)) return;
            Path backup = folder.resolve("migration-backups/SimplexTrimmer-state.yml");
            if (!Files.isRegularFile(backup)) {
                migration.set("legacy-sessions-migrated", true);
                saveAtomic(migration, migrationFile);
                return;
            }
            YamlConfiguration legacy = YamlConfiguration.loadConfiguration(backup.toFile());
            Set<UUID> users = new LinkedHashSet<>();
            for (String key : legacy.getKeys(true)) {
                if (!key.startsWith("sessions.")) continue;
                String[] parts = key.split("\\.");
                if (parts.length < 3) continue;
                try { users.add(UUID.fromString(parts[1])); } catch (IllegalArgumentException ignored) { }
            }
            Map<Path, String> targetIds = new LinkedHashMap<>();
            for (WorldTarget target : discovered) targetIds.put(target.folder.toAbsolutePath().normalize(), target.id);
            int migrated = 0;
            for (UUID uuid : users) {
                String base = "sessions." + uuid;
                Session session = session(uuid);
                for (String oldId : legacy.getStringList(base + ".targets")) {
                    try {
                        String newId = targetIds.get(Path.of(oldId).toAbsolutePath().normalize());
                        if (newId != null) session.selected.add(newId);
                    } catch (Exception ignored) { }
                }
                long oldThreshold = legacy.getLong(base + ".threshold", session.thresholdTicks);
                session.thresholdTicks = Math.max(minimumThreshold(), oldThreshold);
                session.backupConfirmedAt = 0L;
                saveSession(uuid, session);
                migrated++;
            }
            migration.set("legacy-sessions-migrated", true);
            migration.set("legacy-session-count", migrated);
            migration.set("legacy-sessions-migrated-at", System.currentTimeMillis());
            saveAtomic(migration, migrationFile);
            if (migrated > 0) plugin.getLogger().info(migrated + " SimplexTrimmer-Operator-Sitzungen wurden in stabile Ziel-IDs migriert.");
        } catch (Exception error) {
            plugin.getLogger().warning("SimplexTrimmer-Sitzungen konnten noch nicht migriert werden: " + error.getMessage());
        }
    }

    private boolean legacyPluginActive() {
        Plugin legacy = Bukkit.getPluginManager().getPlugin("SimplexTrimmer");
        return legacy != null && legacy != plugin;
    }

    private boolean requireOperator(Player admin) {
        if (admin != null && admin.isOp()) return true;
        if (admin != null) admin.sendMessage(plugin.prefix() + "Only server operators can use the world trimmer.");
        return false;
    }

    private boolean backupConfirmationValid(Session session) {
        long age = System.currentTimeMillis() - session.backupConfirmedAt;
        return session.backupConfirmedAt > 0L && age >= 0L && age <= BACKUP_CONFIRMATION_TTL;
    }

    private long minimumThreshold() {
        return Math.max(20L, plugin.getConfig().getLong("world-trimmer.minimum-threshold-ticks", 20L));
    }

    private String minimumToolVersion() {
        return normalizeVersion(plugin.getConfig().getString("world-trimmer.tool.minimum-version", "2.8"));
    }

    private int historySize() { synchronized (history) { return history.size(); } }

    long totalSavedBytes() {
        synchronized (history) { return history.stream().filter(entry -> entry.status.equals("SUCCESS")).mapToLong(HistoryEntry::savedBytes).sum(); }
    }

    private String maintenanceMessage() {
        return publicMessage("world-trimmer.messages.maintenance", "World maintenance is in progress.\n\nTarget: {target}\nProgress: {progress}%\nEstimated time remaining: {eta}\n\nPlease reconnect after the automatic server restart.")
                .replace("{target}", currentTarget.isBlank() ? "Preparing" : currentTarget)
                .replace("{progress}", Integer.toString(progress)).replace("{eta}", eta());
    }

    private String publicMessage(String path, String fallback) {
        return SimplexAdminSuite.stripFormatting(plugin.getConfig().getString(path, fallback));
    }

    String eta() {
        if (phase.get() != Phase.RUNNING || progress <= 0 || operationStartedAt <= 0L) return "Calculating…";
        long remaining = estimateRemainingMillis(System.currentTimeMillis() - operationStartedAt, progress);
        return formatDuration(remaining);
    }

    static long estimateRemainingMillis(long elapsedMillis, int percent) {
        if (elapsedMillis <= 0L || percent <= 0 || percent >= 100) return percent >= 100 ? 0L : -1L;
        return Math.max(0L, Math.round(elapsedMillis * ((100D - percent) / percent)));
    }

    static int compareVersions(String left, String right) {
        int[] a = versionParts(left), b = versionParts(right);
        int max = Math.max(a.length, b.length);
        for (int i = 0; i < max; i++) {
            int av = i < a.length ? a[i] : 0;
            int bv = i < b.length ? b[i] : 0;
            if (av != bv) return Integer.compare(av, bv);
        }
        return 0;
    }

    private static int[] versionParts(String value) {
        String[] raw = (value == null ? "" : value).split("[^0-9]+");
        List<Integer> numbers = new ArrayList<>();
        for (String part : raw) if (!part.isBlank()) {
            try { numbers.add(Integer.parseInt(part)); } catch (NumberFormatException ignored) { numbers.add(0); }
        }
        int[] result = new int[numbers.size()];
        for (int i = 0; i < result.length; i++) result[i] = numbers.get(i);
        return result;
    }

    private static String normalizeVersion(String value) {
        if (value == null) return "";
        Matcher matcher = Pattern.compile("(\\d+(?:\\.\\d+){1,3})").matcher(value);
        return matcher.find() ? matcher.group(1) : "";
    }

    static String formatBytes(long bytes) {
        String[] units = new String[]{"B", "KiB", "MiB", "GiB", "TiB"};
        double amount = Math.max(0L, bytes);
        int unit = 0;
        while (amount >= 1024D && unit < units.length - 1) { amount /= 1024D; unit++; }
        return String.format(Locale.GERMANY, "%.2f %s", amount, units[unit]);
    }

    private static String formatDuration(long millis) {
        if (millis < 0L) return "Calculating…";
        long seconds = Math.max(0L, millis / 1000L);
        long hours = seconds / 3600L; seconds %= 3600L;
        long minutes = seconds / 60L; seconds %= 60L;
        if (hours > 0) return hours + "h " + minutes + "m";
        if (minutes > 0) return minutes + "m " + seconds + "s";
        return seconds + "s";
    }

    private static String thresholdDuration(long ticks) {
        long seconds = Math.max(1L, ticks / 20L);
        if (seconds < 60L) return seconds + " Sekunden";
        return (seconds / 60L) + " Min " + (seconds % 60L) + " Sek";
    }

    private static String countdownLabel(int seconds) {
        if (seconds <= 0) return "Sofort";
        return seconds % 60 == 0 ? (seconds / 60) + " Minute" + (seconds == 60 ? "" : "n") : seconds + " Sekunden";
    }

    private static String dimensionLabel(String relative) {
        if (relative.equals("DIM-1")) return "Nether";
        if (relative.equals("DIM1")) return "End";
        if (relative.endsWith("/overworld")) return "Overworld";
        if (relative.endsWith("/the_nether")) return "Nether";
        if (relative.endsWith("/the_end")) return "End";
        return relative.replace("dimensions/", "").replace("/", " • ");
    }

    private static String normalizePath(Path path) { return path == null ? "" : path.toString().replace(File.separatorChar, '/'); }
    private static Path safeRealPath(Path path) throws IOException { return path.toAbsolutePath().normalize().toRealPath(); }

    private static String shortHash(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(20);
        for (int i = 0; i < 10; i++) result.append(String.format(Locale.ROOT, "%02x", digest[i]));
        return result.toString();
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (java.io.InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[16384]; int read;
            while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
        }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }

    private static Object invokeOptional(Object target, String name, Object... args) {
        if (target == null) return null;
        try {
            Method method = findMethod(target.getClass(), name, args);
            return method == null ? null : method.invoke(target, args);
        } catch (Throwable ignored) { return null; }
    }

    private static boolean processAlive(long pid) {
        if (pid <= 0L) return false;
        try { return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false); }
        catch (Throwable ignored) { return true; }
    }

    private static Method findMethod(Class<?> type, String name, Object[] args) {
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
            Class<?>[] parameters = method.getParameterTypes();
            boolean compatible = true;
            for (int i = 0; i < parameters.length; i++) {
                if (args[i] == null) continue;
                Class<?> expected = parameters[i].isPrimitive() ? box(parameters[i]) : parameters[i];
                if (!expected.isAssignableFrom(args[i].getClass())) { compatible = false; break; }
            }
            if (compatible) return method;
        }
        return null;
    }

    private static Class<?> box(Class<?> type) {
        if (type == boolean.class) return Boolean.class; if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class; if (type == double.class) return Double.class;
        if (type == float.class) return Float.class; if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class; if (type == char.class) return Character.class;
        return type;
    }

    private static void saveAtomic(YamlConfiguration yaml, File file) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        File temporary = new File(parent == null ? new File(".") : parent, file.getName() + ".tmp");
        yaml.save(temporary);
        try { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING); }
    }

    private static List<String> labels(List<WorldTarget> targets) {
        List<String> labels = new ArrayList<>();
        for (WorldTarget target : targets) labels.add(target.label);
        return labels;
    }

    private static String shorten(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : "…" + value.substring(Math.max(0, value.length() - max + 1));
    }

    private static String formatDate(long millis) {
        if (millis <= 0L) return "Unbekannter Zeitpunkt";
        return new java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY).format(new Date(millis));
    }

    private static String age(long millis) {
        if (millis <= 0L) return "noch nie";
        long seconds = Math.max(0L, (System.currentTimeMillis() - millis) / 1000L);
        return seconds < 60L ? "vor " + seconds + " Sek." : "vor " + (seconds / 60L) + " Min.";
    }

    private static String statusColor(String status) {
        return status.equals("SUCCESS") ? "&a" : status.equals("FAILED") ? "&c" : "&6";
    }

    private static String statusLabel(String status) {
        return status.equals("SUCCESS") ? "Erfolgreich" : status.equals("FAILED") ? "Fehlgeschlagen"
                : status.equals("INTERRUPTED") ? "Unterbrochen" : status;
    }

    private static String cleanReason(Throwable error) {
        if (error == null) return "Unbekannter Fehler";
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message.replace('\n', ' ').replace('\r', ' ');
    }

    private static void showOverlay(Player player, String title, String subtitle) {
        try { player.getClass().getMethod("sendTitle", String.class, String.class, int.class, int.class, int.class)
                .invoke(player, title, subtitle, 0, 25, 5); return; } catch (Throwable ignored) { }
        try { player.getClass().getMethod("sendActionBar", String.class).invoke(player, title + " • " + subtitle); }
        catch (Throwable ignored) { }
    }

    private static final class Session {
        final LinkedHashSet<String> selected;
        long thresholdTicks;
        int countdownSeconds;
        long backupConfirmedAt;
        Session(LinkedHashSet<String> selected, long thresholdTicks, int countdownSeconds, long backupConfirmedAt) {
            this.selected = selected; this.thresholdTicks = thresholdTicks;
            this.countdownSeconds = countdownSeconds; this.backupConfirmedAt = backupConfirmedAt;
        }
    }

    private static final class RunPlan {
        final List<WorldTarget> targets;
        final long thresholdTicks;
        final int countdownSeconds;
        final UUID actorUuid;
        final String actor;
        final long beforeBytes;
        RunPlan(List<WorldTarget> targets, long thresholdTicks, int countdownSeconds, UUID actorUuid,
                String actor, long beforeBytes) {
            this.targets = List.copyOf(targets); this.thresholdTicks = thresholdTicks;
            this.countdownSeconds = countdownSeconds; this.actorUuid = actorUuid;
            this.actor = actor; this.beforeBytes = beforeBytes;
        }
    }

    private static final class Release {
        final String version; final URI uri;
        Release(String version, URI uri) { this.version = version; this.uri = uri; }
    }

    private static final class TrimMenu implements InventoryHolder {
        final String type; final int page;
        TrimMenu(String type, int page) { this.type = type; this.page = page; }
        public Inventory getInventory() { return null; }
    }
}
