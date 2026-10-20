package eu.simplexsmp.adminsuite;

import org.bukkit.Bukkit;
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
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Admin quality-of-life center. All destructive operations are main-thread, permission checked and
 * explicitly confirmed. Its persistent files remain in the established admin-6 directory.
 */
final class AdminQualityManager implements Listener {
    enum Priority { LOW, NORMAL, HIGH, URGENT }
    enum TaskStatus { OPEN, CLAIMED, DONE }
    enum JobStatus { PENDING, EXECUTED, CANCELLED, MISSED, FAILED }
    enum JobAction {
        ANNOUNCEMENT, RESTART, MAINTENANCE_ON, MAINTENANCE_OFF,
        CHAT_ON, CHAT_OFF, LOCKDOWN_ON, LOCKDOWN_OFF, SAVE_ALL
    }
    enum IncidentProfile { CHAT_CONTROL, CHAT_FREEZE, SECURITY_LOCKDOWN, MAINTENANCE, RESTORE }
    enum HealthLevel { HEALTHY, WARNING, CRITICAL }
    enum InputType { TASK, MACRO, SCHEDULE_ANNOUNCEMENT, BULK_BROADCAST, BULK_KICK }

    static final class StaffTask {
        final long id;
        String title;
        String details;
        Priority priority;
        TaskStatus status;
        final String createdBy;
        final long createdAt;
        long dueAt;
        UUID assignedUuid;
        String assignedName;
        String completedBy;
        long completedAt;

        StaffTask(long id, String title, String details, Priority priority, TaskStatus status,
                  String createdBy, long createdAt, long dueAt, UUID assignedUuid,
                  String assignedName, String completedBy, long completedAt) {
            this.id = id;
            this.title = title == null ? "Aufgabe" : title;
            this.details = details == null ? "" : details;
            this.priority = priority == null ? Priority.NORMAL : priority;
            this.status = status == null ? TaskStatus.OPEN : status;
            this.createdBy = createdBy == null ? "System" : createdBy;
            this.createdAt = Math.max(0L, createdAt);
            this.dueAt = Math.max(0L, dueAt);
            this.assignedUuid = assignedUuid;
            this.assignedName = assignedName == null ? "" : assignedName;
            this.completedBy = completedBy == null ? "" : completedBy;
            this.completedAt = Math.max(0L, completedAt);
        }

        boolean overdue(long now) { return status != TaskStatus.DONE && dueAt > 0L && dueAt < now; }
    }

    static final class ScheduledJob {
        final long id;
        final JobAction action;
        JobStatus status;
        final long executeAt;
        final long createdAt;
        final String createdBy;
        final String payload;
        long finishedAt;
        String result;

        ScheduledJob(long id, JobAction action, JobStatus status, long executeAt, long createdAt,
                     String createdBy, String payload, long finishedAt, String result) {
            this.id = id;
            this.action = action == null ? JobAction.ANNOUNCEMENT : action;
            this.status = status == null ? JobStatus.PENDING : status;
            this.executeAt = Math.max(0L, executeAt);
            this.createdAt = Math.max(0L, createdAt);
            this.createdBy = createdBy == null ? "System" : createdBy;
            this.payload = payload == null ? "" : payload;
            this.finishedAt = Math.max(0L, finishedAt);
            this.result = result == null ? "" : result;
        }
    }

    static final class AdminMacro {
        final long id;
        String name;
        String command;
        boolean enabled;
        boolean confirmation;
        final String createdBy;
        final long createdAt;
        long lastRunAt;
        String lastRunBy;

        AdminMacro(long id, String name, String command, boolean enabled, boolean confirmation,
                   String createdBy, long createdAt, long lastRunAt, String lastRunBy) {
            this.id = id;
            this.name = name == null ? "Makro" : name;
            this.command = command == null ? "" : command;
            this.enabled = enabled;
            this.confirmation = confirmation;
            this.createdBy = createdBy == null ? "System" : createdBy;
            this.createdAt = Math.max(0L, createdAt);
            this.lastRunAt = Math.max(0L, lastRunAt);
            this.lastRunBy = lastRunBy == null ? "" : lastRunBy;
        }
    }

    static final class TimelineEntry {
        final long at;
        final String actor;
        final String category;
        final String action;
        final String details;
        final boolean success;

        TimelineEntry(long at, String actor, String category, String action, String details, boolean success) {
            this.at = Math.max(0L, at);
            this.actor = actor == null ? "System" : actor;
            this.category = category == null ? "SYSTEM" : category;
            this.action = action == null ? "Aktion" : action;
            this.details = details == null ? "" : details;
            this.success = success;
        }
    }

    static final class HealthSnapshot {
        final double tps;
        final double mspt;
        final int memoryPercent;
        final long loadedChunks;
        final int onlinePlayers;
        final HealthLevel level;
        final long sampledAt;

        HealthSnapshot(double tps, double mspt, int memoryPercent, long loadedChunks,
                       int onlinePlayers, HealthLevel level, long sampledAt) {
            this.tps = tps;
            this.mspt = mspt;
            this.memoryPercent = Math.max(0, Math.min(100, memoryPercent));
            this.loadedChunks = Math.max(0L, loadedChunks);
            this.onlinePlayers = Math.max(0, onlinePlayers);
            this.level = level == null ? HealthLevel.HEALTHY : level;
            this.sampledAt = Math.max(0L, sampledAt);
        }
    }

    private static final class IncidentSnapshot {
        final boolean maintenance;
        final String maintenanceReason;
        final boolean chat;
        final int slowmode;
        final boolean commandLockdown;

        IncidentSnapshot(boolean maintenance, String maintenanceReason, boolean chat,
                         int slowmode, boolean commandLockdown) {
            this.maintenance = maintenance;
            this.maintenanceReason = maintenanceReason == null ? "" : maintenanceReason;
            this.chat = chat;
            this.slowmode = Math.max(0, Math.min(120, slowmode));
            this.commandLockdown = commandLockdown;
        }
    }

    private static final class TaskDraft {
        Priority priority = Priority.NORMAL;
        long dueDelayMillis = 24L * 60L * 60_000L;
    }

    private static final class ScheduleDraft {
        long delayMillis = 5L * 60_000L;
    }

    private static final class PendingInput {
        final InputType type;
        final long value;
        PendingInput(InputType type, long value) { this.type = type; this.value = value; }
    }

    private static final class PendingConfirmation {
        final String action;
        final long id;
        final String value;
        PendingConfirmation(String action, long id, String value) {
            this.action = action;
            this.id = id;
            this.value = value == null ? "" : value;
        }
    }

    private static final class QolMenu implements InventoryHolder {
        final String type;
        final int page;
        final long id;
        QolMenu(String type, int page, long id) { this.type = type; this.page = page; this.id = id; }
        public Inventory getInventory() { return null; }
    }

    private final SimplexAdminSuite plugin;
    private final File folder;
    private final File tasksFile;
    private final File schedulesFile;
    private final File macrosFile;
    private final File timelineFile;
    private final File healthFile;
    private final File incidentFile;
    private final Map<Long, StaffTask> tasks = new LinkedHashMap<>();
    private final Map<Long, ScheduledJob> jobs = new LinkedHashMap<>();
    private final Map<Long, AdminMacro> macros = new LinkedHashMap<>();
    private final List<TimelineEntry> timeline = new ArrayList<>();
    private final List<TimelineEntry> healthEvents = new ArrayList<>();
    private final Map<UUID, List<Long>> taskViews = new ConcurrentHashMap<>();
    private final Map<UUID, List<Long>> jobViews = new ConcurrentHashMap<>();
    private final Map<UUID, List<Long>> macroViews = new ConcurrentHashMap<>();
    private final Map<UUID, TaskDraft> taskDrafts = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduleDraft> scheduleDrafts = new ConcurrentHashMap<>();
    private final Map<UUID, PendingInput> pendingInputs = new ConcurrentHashMap<>();
    private final Map<UUID, PendingConfirmation> confirmations = new ConcurrentHashMap<>();
    private volatile HealthSnapshot health = new HealthSnapshot(-1D, -1D, 0, 0L, 0, HealthLevel.HEALTHY, 0L);
    private volatile IncidentProfile activeIncident;
    private volatile IncidentSnapshot incidentSnapshot;
    private volatile String incidentActor = "";
    private volatile long incidentAt;
    private volatile long lastHealthAlertAt;
    private volatile HealthLevel lastAlertLevel = HealthLevel.HEALTHY;
    private volatile int consecutiveHealthProblems;
    private volatile long nextHealthSampleAt;
    private long nextTaskId = 1L;
    private long nextJobId = 1L;
    private long nextMacroId = 1L;
    private BukkitTask tickTask;

    AdminQualityManager(SimplexAdminSuite plugin) {
        this.plugin = plugin;
        this.folder = new File(plugin.getDataFolder(), "admin-qol");
        this.tasksFile = new File(folder, "staff-tasks.yml");
        this.schedulesFile = new File(folder, "scheduled-actions.yml");
        this.macrosFile = new File(folder, "admin-macros.yml");
        this.timelineFile = new File(folder, "timeline.yml");
        this.healthFile = new File(folder, "health-events.yml");
        this.incidentFile = new File(folder, "incident-state.yml");
        if (!folder.exists() && !folder.mkdirs()) plugin.getLogger().warning("Admin-QoL-Ordner konnte nicht erstellt werden.");
        loadAll();
    }

    synchronized void start() {
        if (tickTask != null) return;
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    synchronized void stop() {
        BukkitTask task = tickTask;
        tickTask = null;
        if (task != null) try { task.cancel(); } catch (Throwable ignored) { }
        saveAll();
        pendingInputs.clear();
        confirmations.clear();
    }

    synchronized void reload() {
        loadAll();
        nextHealthSampleAt = 0L;
    }

    private void tick() {
        executeDueJobs();
        long now = System.currentTimeMillis();
        if (now >= nextHealthSampleAt) {
            int seconds = Math.max(10, Math.min(300,
                    plugin.getConfig().getInt("admin-qol.health-guard.interval-seconds", 30)));
            nextHealthSampleAt = now + seconds * 1000L;
            sampleHealth(false);
        }
    }

    boolean isInventory(Inventory inventory) {
        return inventory != null && LiveMenus.holder(inventory) instanceof QolMenu;
    }

    boolean handleInventoryClick(InventoryClickEvent event) {
        if (event == null || !isInventory(event.getInventory())) return false;
        event.setCancelled(true);
        HumanEntity human = event.getWhoClicked();
        if (!(human instanceof Player)) return true;
        Player admin = (Player) human;
        QolMenu menu = (QolMenu) LiveMenus.holder(event.getInventory());
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getInventory().getSize()) return true;
        if (menu.type.equals("dashboard")) handleDashboard(admin, slot);
        else if (menu.type.equals("tasks")) handleTasks(admin, menu.page, slot);
        else if (menu.type.equals("task")) handleTask(admin, menu.id, slot);
        else if (menu.type.equals("task-create")) handleTaskCreate(admin, slot);
        else if (menu.type.equals("schedules")) handleSchedules(admin, menu.page, slot);
        else if (menu.type.equals("schedule")) handleSchedule(admin, menu.id, slot);
        else if (menu.type.equals("schedule-create")) handleScheduleCreate(admin, slot);
        else if (menu.type.equals("incidents")) handleIncidents(admin, slot);
        else if (menu.type.equals("health")) handleHealth(admin, slot);
        else if (menu.type.equals("health-history")) handleHealthHistory(admin, menu.page, slot);
        else if (menu.type.equals("macros")) handleMacros(admin, menu.page, slot);
        else if (menu.type.equals("macro")) handleMacro(admin, menu.id, slot);
        else if (menu.type.equals("bulk")) handleBulk(admin, slot);
        else if (menu.type.equals("timeline")) handleTimeline(admin, menu.page, slot);
        else if (menu.type.equals("confirm")) handleConfirmation(admin, slot);
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
        taskDrafts.remove(uuid);
        scheduleDrafts.remove(uuid);
    }

    void openDashboardJava(Player admin) {
        if (!plugin.adminMenuAllowed(admin, "adminqualitymanager.opendashboardjava")) return;
        if (!require(admin, "simplexadminsuite.qol")) return;
        Inventory inventory = LiveMenus.create(new QolMenu("dashboard", 0, 0L), 54,
                plugin.color("&8Team-Dashboard"));
        plugin.fill(inventory);
        HealthSnapshot current = health;
        inventory.setItem(4, plugin.item(levelMaterial(current.level), "&bLive-Übersicht",
                "&7Offene Aufgaben: &f" + openTaskCount(),
                "&7Geplante Aktionen: &f" + pendingJobCount(),
                "&7Incident-Profil: &f" + incidentLabel(activeIncident),
                "&7Health: " + levelColor(current.level) + healthShort(current)));
        inventory.setItem(10, plugin.item(Material.WRITABLE_BOOK, "&aStaff-Aufgaben & Übergabe",
                "&7Persistente Aufgaben, Prioritäten, Fälligkeit,", "&7Selbstzuweisung und Abschluss.",
                "&7Offen: &f" + openTaskCount() + " &8• &7Überfällig: &c" + overdueTaskCount()));
        inventory.setItem(12, plugin.item(Material.CLOCK, "&eAktionsplaner",
                "&7Ankündigung, Restart, Wartung, Chat,", "&7Lockdown und Welt-Save zeitgesteuert.",
                "&7Ausstehend: &f" + pendingJobCount() + " &8• &7Nächste: &f" + nextJobText()));
        inventory.setItem(14, plugin.item(Material.REDSTONE_BLOCK, "&cIncident-Profile",
                "&7Chat-Kontrolle, Chat-Freeze, Security-", "&7Lockdown oder Wartung mit Restore.",
                "&7Aktiv: &f" + incidentLabel(activeIncident)));
        inventory.setItem(16, plugin.item(levelMaterial(current.level), "&bHealth-Guard",
                "&7TPS, MSPT, RAM und geladene Chunks", "&7mit Cooldown, Verlauf und Discord-Alert.",
                "&7" + healthShort(current)));
        inventory.setItem(28, plugin.item(Material.COMMAND_BLOCK, "&dSichere OP-Makros",
                "&7Wiederkehrende Konsolenbefehle mit", "&7Platzhaltern, Sperrliste und Bestätigung.",
                "&7Aktiv: &f" + enabledMacroCount() + "/" + macroCount()));
        inventory.setItem(30, plugin.item(Material.NETHER_STAR, "&6Massenaktionen",
                "&7Welten speichern, Spieler versorgen,", "&7Broadcast, Sammel-Teleport und Kick.",
                "&cAuswirkungsstarke Aktionen fragen nach."));
        inventory.setItem(32, plugin.item(Material.BOOK, "&9QoL-Aktivitätschronik",
                "&7Gemeinsamer Verlauf für Aufgaben, Jobs,", "&7Incidents, Health, Makros und Bulk.",
                "&7Einträge: &f" + timelineSize() + "/200"));
        inventory.setItem(49, plugin.item(Material.BARRIER, "&cZurück", "&7Zurück zur Simplex Admin Suite."));
        LiveMenus.show(admin,inventory);
    }

    private void handleDashboard(Player admin, int slot) {
        if (slot == 10) openTasksJava(admin, 0);
        else if (slot == 12) openSchedulesJava(admin, 0);
        else if (slot == 14) openIncidentsJava(admin);
        else if (slot == 16) openHealthJava(admin);
        else if (slot == 28) openMacrosJava(admin, 0);
        else if (slot == 30) openBulkJava(admin);
        else if (slot == 32) openTimelineJava(admin, 0);
        else if (slot == 49) plugin.openJavaAdminMain(admin);
    }

    void openTasksJava(Player admin, int requestedPage) {
        if (!plugin.adminMenuAllowed(admin, "adminqualitymanager.opentasksjava")) return;
        if (!require(admin, "simplexadminsuite.qol.tasks")) return;
        List<StaffTask> sorted = tasksSnapshot();
        List<Long> ids = new ArrayList<>();
        for (StaffTask task : sorted) ids.add(task.id);
        taskViews.put(admin.getUniqueId(), ids);
        int maxPage = Math.max(0, (ids.size() - 1) / 45);
        int page = Math.max(0, Math.min(maxPage, requestedPage));
        Inventory inventory = LiveMenus.create(new QolMenu("tasks", page, 0L), 54,
                plugin.color("&8Staff-Aufgaben &7" + (page + 1) + "/" + (maxPage + 1)));
        plugin.fill(inventory);
        long now = System.currentTimeMillis();
        for (int i = 0; i < 45; i++) {
            int index = page * 45 + i;
            if (index >= sorted.size()) break;
            StaffTask task = sorted.get(index);
            inventory.setItem(i, plugin.item(taskMaterial(task), taskColor(task) + "#" + task.id + " " + task.title,
                    "&7Status: &f" + taskStatusLabel(task.status),
                    "&7Priorität: " + priorityColor(task.priority) + priorityLabel(task.priority),
                    "&7Zugewiesen: &f" + (task.assignedName.isBlank() ? "Niemand" : task.assignedName),
                    "&7Fällig: " + (task.overdue(now) ? "&c" : "&f") + dueText(task.dueAt),
                    "&8" + shorten(task.details, 70)));
        }
        inventory.setItem(45, plugin.item(Material.ARROW, "&eVorherige Seite"));
        inventory.setItem(48, plugin.item(Material.LIME_DYE, "&aNeue Staff-Aufgabe", "&7Priorität und Fälligkeit auswählen."));
        inventory.setItem(49, plugin.item(Material.BARRIER, "&cZurück", "&7Zurück zur QoL-Zentrale."));
        inventory.setItem(50, plugin.item(Material.MAP, "&eÜbersicht", "&7Offen: &f" + openTaskCount(), "&7Überfällig: &c" + overdueTaskCount(), "&7Erledigt: &a" + doneTaskCount()));
        inventory.setItem(53, plugin.item(Material.ARROW, "&eNächste Seite"));
        LiveMenus.show(admin,inventory);
    }

    private void handleTasks(Player admin, int page, int slot) {
        if (slot == 45 && page > 0) openTasksJava(admin, page - 1);
        else if (slot == 48) openTaskCreateJava(admin);
        else if (slot == 49) openDashboardJava(admin);
        else if (slot == 53) openTasksJava(admin, page + 1);
        else if (slot >= 0 && slot < 45) {
            List<Long> view = taskViews.getOrDefault(admin.getUniqueId(), List.of());
            int index = page * 45 + slot;
            if (index < view.size()) openTaskJava(admin, view.get(index));
        }
    }

    private void openTaskCreateJava(Player admin) {
        if (!plugin.adminMenuAllowed(admin, "adminqualitymanager.opentaskcreatejava")) return;
        if (!require(admin, "simplexadminsuite.qol.tasks")) return;
        TaskDraft draft = taskDrafts.computeIfAbsent(admin.getUniqueId(), ignored -> new TaskDraft());
        Inventory inventory = LiveMenus.create(new QolMenu("task-create", 0, 0L), 27,
                plugin.color("&8Neue Staff-Aufgabe"));
        plugin.fill(inventory);
        inventory.setItem(10, plugin.item(Material.COMPARATOR, "&ePriorität: " + priorityColor(draft.priority) + priorityLabel(draft.priority),
                "&7Klick wechselt Niedrig, Normal, Hoch, Dringend."));
        inventory.setItem(12, plugin.item(Material.CLOCK, "&eFälligkeit: &f" + delayLabel(draft.dueDelayMillis),
                "&7Klick wechselt keine, 1h, 6h, 24h, 3d, 7d."));
        inventory.setItem(14, plugin.item(Material.WRITABLE_BOOK, "&aTitel und Details eingeben",
                "&7Danach im Chat: &fTitel | Details", "&7Die Eingabe bleibt intern und wird nicht gesendet."));
        inventory.setItem(22, plugin.item(Material.BARRIER, "&cAbbrechen"));
        LiveMenus.show(admin,inventory);
    }

    private void handleTaskCreate(Player admin, int slot) {
        TaskDraft draft = taskDrafts.computeIfAbsent(admin.getUniqueId(), ignored -> new TaskDraft());
        if (slot == 10) { draft.priority = nextPriority(draft.priority); openTaskCreateJava(admin); }
        else if (slot == 12) { draft.dueDelayMillis = nextDueDelay(draft.dueDelayMillis); openTaskCreateJava(admin); }
        else if (slot == 14) {
            pendingInputs.put(admin.getUniqueId(), new PendingInput(InputType.TASK, 0L));
            admin.closeInventory();
            admin.sendMessage(plugin.prefix() + "Schreibe intern: Titel | Details. 'abbrechen' beendet die Eingabe.");
        } else if (slot == 22) { taskDrafts.remove(admin.getUniqueId()); openTasksJava(admin, 0); }
    }

    private void openTaskJava(Player admin, long id) {
        if (!plugin.adminMenuAllowed(admin, "adminqualitymanager.opentaskjava")) return;
        StaffTask task = task(id);
        if (task == null || !require(admin, "simplexadminsuite.qol.tasks")) { openTasksJava(admin, 0); return; }
        Inventory inventory = LiveMenus.create(new QolMenu("task", 0, id), 36,
                plugin.color("&8Aufgabe #" + id));
        plugin.fill(inventory);
        inventory.setItem(4, plugin.item(taskMaterial(task), taskColor(task) + task.title,
                "&7" + shorten(task.details, 100), "&7Erstellt von: &f" + task.createdBy,
                "&7Erstellt: &f" + formatDate(task.createdAt), "&7Fällig: &f" + dueText(task.dueAt)));
        boolean mine = admin.getUniqueId().equals(task.assignedUuid);
        inventory.setItem(10, plugin.item(mine ? Material.GRAY_DYE : Material.NAME_TAG,
                mine ? "&eZuweisung lösen" : "&aMir zuweisen",
                "&7Aktuell: &f" + (task.assignedName.isBlank() ? "Niemand" : task.assignedName)));
        inventory.setItem(12, plugin.item(task.status == TaskStatus.DONE ? Material.ORANGE_DYE : Material.LIME_DYE,
                task.status == TaskStatus.DONE ? "&eWieder öffnen" : "&aAls erledigt markieren"));
        inventory.setItem(14, plugin.item(Material.COMPARATOR, "&ePriorität wechseln",
                "&7Aktuell: " + priorityColor(task.priority) + priorityLabel(task.priority)));
        inventory.setItem(16, plugin.item(Material.RED_DYE, "&cAufgabe löschen", "&7Benötigt eine Bestätigung."));
        inventory.setItem(22, plugin.item(Material.BARRIER, "&cZurück"));
        LiveMenus.show(admin,inventory);
    }

    private void handleTask(Player admin, long id, int slot) {
        if (slot == 10) { claimTask(admin, id); openTaskJava(admin, id); }
        else if (slot == 12) { toggleTaskDone(admin, id); openTaskJava(admin, id); }
        else if (slot == 14) { cycleTaskPriority(admin, id); openTaskJava(admin, id); }
        else if (slot == 16) openConfirmationJava(admin, "task-delete", id, "", "Staff-Aufgabe #" + id + " endgültig löschen?");
        else if (slot == 22) openTasksJava(admin, 0);
    }

    void openSchedulesJava(Player admin, int requestedPage) {
        if (!plugin.adminMenuAllowed(admin, "adminqualitymanager.openschedulesjava")) return;
        if (!require(admin, "simplexadminsuite.qol.scheduler")) return;
        List<ScheduledJob> sorted = jobsSnapshot();
        List<Long> ids = new ArrayList<>();
        for (ScheduledJob job : sorted) ids.add(job.id);
        jobViews.put(admin.getUniqueId(), ids);
        int maxPage = Math.max(0, (ids.size() - 1) / 45);
        int page = Math.max(0, Math.min(maxPage, requestedPage));
        Inventory inventory = LiveMenus.create(new QolMenu("schedules", page, 0L), 54,
                plugin.color("&8Aktionsplaner &7" + (page + 1) + "/" + (maxPage + 1)));
        plugin.fill(inventory);
        for (int i = 0; i < 45; i++) {
            int index = page * 45 + i;
            if (index >= sorted.size()) break;
            ScheduledJob job = sorted.get(index);
            inventory.setItem(i, plugin.item(jobMaterial(job.action), jobColor(job.status) + "#" + job.id + " " + jobActionLabel(job.action),
                    "&7Status: &f" + jobStatusLabel(job.status), "&7Ausführung: &f" + formatDate(job.executeAt),
                    "&7Erstellt von: &f" + job.createdBy,
                    job.payload.isBlank() ? "&8Kein Zusatztext" : "&8" + shorten(job.payload, 70)));
        }
        inventory.setItem(45, plugin.item(Material.ARROW, "&eVorherige Seite"));
        inventory.setItem(48, plugin.item(Material.LIME_DYE, "&aNeue geplante Aktion"));
        inventory.setItem(49, plugin.item(Material.BARRIER, "&cZurück"));
        inventory.setItem(50, plugin.item(Material.CLOCK, "&ePlaner-Status", "&7Ausstehend: &f" + pendingJobCount(), "&7Nächste: &f" + nextJobText()));
        inventory.setItem(53, plugin.item(Material.ARROW, "&eNächste Seite"));
        LiveMenus.show(admin,inventory);
    }

    private void handleSchedules(Player admin, int page, int slot) {
        if (slot == 45 && page > 0) openSchedulesJava(admin, page - 1);
        else if (slot == 48) openScheduleCreateJava(admin);
        else if (slot == 49) openDashboardJava(admin);
        else if (slot == 53) openSchedulesJava(admin, page + 1);
        else if (slot >= 0 && slot < 45) {
            List<Long> view = jobViews.getOrDefault(admin.getUniqueId(), List.of());
            int index = page * 45 + slot;
            if (index < view.size()) openScheduleJava(admin, view.get(index));
        }
    }

    private void openScheduleCreateJava(Player admin) {
        if (!plugin.adminMenuAllowed(admin, "adminqualitymanager.openschedulecreatejava")) return;
        if (!requireOperator(admin, "simplexadminsuite.qol.scheduler")) return;
        ScheduleDraft draft = scheduleDrafts.computeIfAbsent(admin.getUniqueId(), ignored -> new ScheduleDraft());
        Inventory inventory = LiveMenus.create(new QolMenu("schedule-create", 0, 0L), 36,
                plugin.color("&8Neue geplante Aktion"));
        plugin.fill(inventory);
        inventory.setItem(4, plugin.item(Material.CLOCK, "&eVerzögerung: &f" + delayLabel(draft.delayMillis), "&7Klick zum Wechseln."));
        JobAction[] actions = JobAction.values();
        int[] slots = new int[]{10,11,12,13,14,15,16,17,22};
        for (int i = 0; i < actions.length; i++) inventory.setItem(slots[i], plugin.item(jobMaterial(actions[i]),
                "&a" + jobActionLabel(actions[i]), "&7Ausführung in: &f" + delayLabel(draft.delayMillis)));
        inventory.setItem(31, plugin.item(Material.BARRIER, "&cAbbrechen"));
        LiveMenus.show(admin,inventory);
    }

    private void handleScheduleCreate(Player admin, int slot) {
        ScheduleDraft draft = scheduleDrafts.computeIfAbsent(admin.getUniqueId(), ignored -> new ScheduleDraft());
        if (slot == 4) { draft.delayMillis = nextScheduleDelay(draft.delayMillis); openScheduleCreateJava(admin); return; }
        if (slot == 31) { scheduleDrafts.remove(admin.getUniqueId()); openSchedulesJava(admin, 0); return; }
        int[] slots = new int[]{10,11,12,13,14,15,16,17,22};
        JobAction[] actions = JobAction.values();
        for (int i = 0; i < slots.length; i++) if (slot == slots[i]) {
            if (actions[i] == JobAction.ANNOUNCEMENT) {
                pendingInputs.put(admin.getUniqueId(), new PendingInput(InputType.SCHEDULE_ANNOUNCEMENT, draft.delayMillis));
                admin.closeInventory();
                admin.sendMessage(plugin.prefix() + "Schreibe jetzt den öffentlichen englischen Ankündigungstext. 'abbrechen' beendet die Eingabe.");
            } else openConfirmationJava(admin, "schedule-create", actions[i].ordinal(), Long.toString(draft.delayMillis),
                    jobActionLabel(actions[i]) + " in " + delayLabel(draft.delayMillis) + " einplanen?");
            return;
        }
    }

    private void openScheduleJava(Player admin, long id) {
        if (!plugin.adminMenuAllowed(admin, "adminqualitymanager.openschedulejava")) return;
        ScheduledJob job = job(id);
        if (job == null || !require(admin, "simplexadminsuite.qol.scheduler")) { openSchedulesJava(admin, 0); return; }
        Inventory inventory = LiveMenus.create(new QolMenu("schedule", 0, id), 27,
                plugin.color("&8Geplante Aktion #" + id));
        plugin.fill(inventory);
        inventory.setItem(4, plugin.item(jobMaterial(job.action), jobColor(job.status) + jobActionLabel(job.action),
                "&7Status: &f" + jobStatusLabel(job.status), "&7Zeitpunkt: &f" + formatDate(job.executeAt),
                "&7Erstellt von: &f" + job.createdBy, "&7Ergebnis: &f" + (job.result.isBlank() ? "Noch keines" : shorten(job.result, 80))));
        if (job.status == JobStatus.PENDING) {
            inventory.setItem(10, plugin.item(Material.NETHER_STAR, "&eJetzt ausführen", "&7Überspringt die verbleibende Wartezeit."));
            inventory.setItem(14, plugin.item(Material.RED_DYE, "&cGeplante Aktion abbrechen"));
        } else inventory.setItem(12, plugin.item(Material.RED_DYE, "&cVerlaufseintrag löschen"));
        inventory.setItem(22, plugin.item(Material.BARRIER, "&cZurück"));
        LiveMenus.show(admin,inventory);
    }

    private void handleSchedule(Player admin, long id, int slot) {
        ScheduledJob job = job(id);
        if (job == null) return;
        if (slot == 10 && job.status == JobStatus.PENDING) openConfirmationJava(admin, "schedule-now", id, "", "Aktion #" + id + " jetzt ausführen?");
        else if (slot == 14 && job.status == JobStatus.PENDING) openConfirmationJava(admin, "schedule-cancel", id, "", "Aktion #" + id + " abbrechen?");
        else if (slot == 12 && job.status != JobStatus.PENDING) openConfirmationJava(admin, "schedule-delete", id, "", "Verlaufseintrag #" + id + " löschen?");
        else if (slot == 22) openSchedulesJava(admin, 0);
    }

    void openIncidentsJava(Player admin) {
        if (!plugin.adminMenuAllowed(admin, "adminqualitymanager.openincidentsjava")) return;
        if (!requireOperator(admin, "simplexadminsuite.qol.incidents")) return;
        Inventory inventory = LiveMenus.create(new QolMenu("incidents", 0, 0L), 45,
                plugin.color("&8Incident-Profile"));
        plugin.fill(inventory);
        inventory.setItem(4, plugin.item(activeIncident == null ? Material.LIME_DYE : Material.ORANGE_DYE,
                "&eAktiver Zustand: &f" + incidentLabel(activeIncident),
                "&7Ursprünglicher Zustand gespeichert: &f" + (incidentSnapshot == null ? "Nein" : "Ja"),
                "&7Aktiviert von: &f" + (incidentActor.isBlank() ? "Niemand" : incidentActor)));
        inventory.setItem(10, plugin.item(Material.CLOCK, "&eChat-Kontrolle",
                "&7Wartung aus, Chat an, Slowmode 10s,", "&7Befehls-Lockdown aus."));
        inventory.setItem(12, plugin.item(Material.BARRIER, "&cChat-Freeze",
                "&7Wartung aus, öffentlicher Chat aus,", "&7Slowmode und Lockdown aus."));
        inventory.setItem(14, plugin.item(Material.REDSTONE_BLOCK, "&4Security-Lockdown",
                "&7Wartung an, Chat aus, Slowmode 10s,", "&7Befehls-Lockdown an; kickt Non-Staff."));
        inventory.setItem(16, plugin.item(Material.IRON_DOOR, "&6Geplante Wartung",
                "&7Wartung an, Chat an, Slowmode aus,", "&7Befehls-Lockdown aus; kickt Non-Staff."));
        inventory.setItem(22, plugin.item(incidentSnapshot == null ? Material.GRAY_DYE : Material.RECOVERY_COMPASS,
                "&aUrsprünglichen Zustand wiederherstellen",
                "&7Stellt Wartung, Chat, Slowmode und", "&7Befehls-Lockdown exakt wieder her."));
        inventory.setItem(31, plugin.item(Material.BARRIER, "&cZurück"));
        LiveMenus.show(admin,inventory);
    }

    private void handleIncidents(Player admin, int slot) {
        IncidentProfile profile = slot == 10 ? IncidentProfile.CHAT_CONTROL : slot == 12 ? IncidentProfile.CHAT_FREEZE
                : slot == 14 ? IncidentProfile.SECURITY_LOCKDOWN : slot == 16 ? IncidentProfile.MAINTENANCE
                : slot == 22 ? IncidentProfile.RESTORE : null;
        if (profile != null) openConfirmationJava(admin, "incident", profile.ordinal(), "",
                incidentConfirmation(profile));
        else if (slot == 31) openDashboardJava(admin);
    }

    void openHealthJava(Player admin) {
        if (!plugin.adminMenuAllowed(admin, "adminqualitymanager.openhealthjava")) return;
        if (!require(admin, "simplexadminsuite.qol.health")) return;
        HealthSnapshot current = sampleHealth(true);
        Inventory inventory = LiveMenus.create(new QolMenu("health", 0, 0L), 45,
                plugin.color("&8Health-Guard"));
        plugin.fill(inventory);
        inventory.setItem(4, plugin.item(levelMaterial(current.level), levelColor(current.level) + "Live: " + healthShort(current),
                "&7TPS: &f" + decimal(current.tps) + " &8• &7MSPT: &f" + decimal(current.mspt),
                "&7RAM: &f" + current.memoryPercent + "% &8• &7Chunks: &f" + current.loadedChunks,
                "&7Spieler online: &f" + current.onlinePlayers));
        boolean enabled = plugin.getConfig().getBoolean("admin-qol.health-guard.enabled", true);
        boolean discord = plugin.getConfig().getBoolean("admin-qol.health-guard.discord-alerts", true);
        inventory.setItem(10, plugin.item(enabled ? Material.LIME_DYE : Material.GRAY_DYE,
                "&eHealth-Guard: " + (enabled ? "&aAktiv" : "&cInaktiv")));
        inventory.setItem(11, plugin.item(discord ? Material.LIME_DYE : Material.GRAY_DYE,
                "&9Discord-Alerts: " + (discord ? "&aAktiv" : "&cInaktiv")));
        inventory.setItem(12, plugin.item(Material.COMPARATOR, "&eTPS-Grenzen",
                "&7Warnung: &f" + plugin.getConfig().getDouble("admin-qol.health-guard.tps-warning", 18D),
                "&7Kritisch: &f" + plugin.getConfig().getDouble("admin-qol.health-guard.tps-critical", 15D), "&7Klick wechselt das Profil."));
        inventory.setItem(13, plugin.item(Material.COMPARATOR, "&eRAM-Grenzen",
                "&7Warnung: &f" + plugin.getConfig().getInt("admin-qol.health-guard.memory-warning-percent", 85) + "%",
                "&7Kritisch: &f" + plugin.getConfig().getInt("admin-qol.health-guard.memory-critical-percent", 95) + "%", "&7Klick wechselt das Profil."));
        inventory.setItem(14, plugin.item(Material.COMPARATOR, "&eChunk-Warnung",
                "&7Ab: &f" + plugin.getConfig().getLong("admin-qol.health-guard.loaded-chunks-warning", 10000L), "&7Klick wechselt den Grenzwert."));
        inventory.setItem(15, plugin.item(Material.CLOCK, "&eAlert-Cooldown",
                "&7Aktuell: &f" + plugin.getConfig().getInt("admin-qol.health-guard.alert-cooldown-seconds", 300) + " Sekunden", "&7Klick wechselt den Wert."));
        inventory.setItem(16, plugin.item(Material.MAP, "&bJetzt neu messen"));
        inventory.setItem(22, plugin.item(Material.WRITABLE_BOOK, "&dHealth-Verlauf", "&7Einträge: &f" + healthEventCount() + "/100"));
        inventory.setItem(31, plugin.item(Material.BARRIER, "&cZurück"));
        LiveMenus.show(admin,inventory);
    }

    private void handleHealth(Player admin, int slot) {
        if (slot == 10) { toggleHealthSetting(admin, "enabled"); openHealthJava(admin); }
        else if (slot == 11) { toggleHealthSetting(admin, "discord-alerts"); openHealthJava(admin); }
        else if (slot == 12) { cycleHealthThreshold(admin, "tps"); openHealthJava(admin); }
        else if (slot == 13) { cycleHealthThreshold(admin, "memory"); openHealthJava(admin); }
        else if (slot == 14) { cycleHealthThreshold(admin, "chunks"); openHealthJava(admin); }
        else if (slot == 15) { cycleHealthThreshold(admin, "cooldown"); openHealthJava(admin); }
        else if (slot == 16) { sampleHealth(true); openHealthJava(admin); }
        else if (slot == 22) openHealthHistoryJava(admin, 0);
        else if (slot == 31) openDashboardJava(admin);
    }

    private void openHealthHistoryJava(Player admin, int requestedPage) {
        if (!plugin.adminMenuAllowed(admin, "adminqualitymanager.openhealthhistoryjava")) return;
        if (!require(admin, "simplexadminsuite.qol.health")) return;
        List<TimelineEntry> entries = healthEventsSnapshot();
        int maxPage = Math.max(0, (entries.size() - 1) / 45);
        int page = Math.max(0, Math.min(maxPage, requestedPage));
        Inventory inventory = LiveMenus.create(new QolMenu("health-history", page, 0L), 54,
                plugin.color("&8Health-Verlauf &7" + (page + 1) + "/" + (maxPage + 1)));
        plugin.fill(inventory);
        for (int i = 0; i < 45; i++) {
            int index = page * 45 + i;
            if (index >= entries.size()) break;
            TimelineEntry entry = entries.get(index);
            inventory.setItem(i, plugin.item(entry.success ? Material.ORANGE_DYE : Material.RED_DYE,
                    (entry.success ? "&6" : "&c") + formatDate(entry.at), "&7" + entry.action, "&f" + shorten(entry.details, 120)));
        }
        inventory.setItem(45, plugin.item(Material.ARROW, "&eVorherige Seite"));
        inventory.setItem(49, plugin.item(Material.BARRIER, "&cZurück"));
        inventory.setItem(53, plugin.item(Material.ARROW, "&eNächste Seite"));
        LiveMenus.show(admin,inventory);
    }

    private void handleHealthHistory(Player admin, int page, int slot) {
        if (slot == 45 && page > 0) openHealthHistoryJava(admin, page - 1);
        else if (slot == 49) openHealthJava(admin);
        else if (slot == 53) openHealthHistoryJava(admin, page + 1);
    }

    void openMacrosJava(Player admin, int requestedPage) {
        if (!plugin.adminMenuAllowed(admin, "adminqualitymanager.openmacrosjava")) return;
        if (!requireOperator(admin, "simplexadminsuite.qol.macros")) return;
        List<AdminMacro> sorted = macrosSnapshot();
        List<Long> ids = new ArrayList<>();
        for (AdminMacro macro : sorted) ids.add(macro.id);
        macroViews.put(admin.getUniqueId(), ids);
        int maxPage = Math.max(0, (ids.size() - 1) / 45);
        int page = Math.max(0, Math.min(maxPage, requestedPage));
        Inventory inventory = LiveMenus.create(new QolMenu("macros", page, 0L), 54,
                plugin.color("&8Sichere OP-Makros &7" + (page + 1) + "/" + (maxPage + 1)));
        plugin.fill(inventory);
        for (int i = 0; i < 45; i++) {
            int index = page * 45 + i;
            if (index >= sorted.size()) break;
            AdminMacro macro = sorted.get(index);
            inventory.setItem(i, plugin.item(macro.enabled ? Material.COMMAND_BLOCK : Material.GRAY_DYE,
                    (macro.enabled ? "&d" : "&7") + "#" + macro.id + " " + macro.name,
                    "&7Status: " + (macro.enabled ? "&aAktiv" : "&cInaktiv"),
                    "&7Bestätigung: &f" + (macro.confirmation ? "Ja" : "Nein"),
                    "&8/" + shorten(macro.command, 75)));
        }
        inventory.setItem(45, plugin.item(Material.ARROW, "&eVorherige Seite"));
        inventory.setItem(48, plugin.item(Material.LIME_DYE, "&aNeues OP-Makro", "&7Eingabe: Name | Konsolenbefehl"));
        inventory.setItem(49, plugin.item(Material.BARRIER, "&cZurück"));
        inventory.setItem(50, plugin.item(Material.BOOK, "&ePlatzhalter",
                "&f{player} {world} {x} {y} {z} {online}", "&7Kritische Lifecycle-/OP-Befehle sind gesperrt."));
        inventory.setItem(53, plugin.item(Material.ARROW, "&eNächste Seite"));
        LiveMenus.show(admin,inventory);
    }

    private void handleMacros(Player admin, int page, int slot) {
        if (slot == 45 && page > 0) openMacrosJava(admin, page - 1);
        else if (slot == 48) {
            pendingInputs.put(admin.getUniqueId(), new PendingInput(InputType.MACRO, 0L));
            admin.closeInventory();
            admin.sendMessage(plugin.prefix() + "Schreibe intern: Name | Konsolenbefehl. 'abbrechen' beendet die Eingabe.");
        } else if (slot == 49) openDashboardJava(admin);
        else if (slot == 53) openMacrosJava(admin, page + 1);
        else if (slot >= 0 && slot < 45) {
            List<Long> view = macroViews.getOrDefault(admin.getUniqueId(), List.of());
            int index = page * 45 + slot;
            if (index < view.size()) openMacroJava(admin, view.get(index));
        }
    }

    private void openMacroJava(Player admin, long id) {
        if (!plugin.adminMenuAllowed(admin, "adminqualitymanager.openmacrojava")) return;
        AdminMacro macro = macro(id);
        if (macro == null || !requireOperator(admin, "simplexadminsuite.qol.macros")) { openMacrosJava(admin, 0); return; }
        Inventory inventory = LiveMenus.create(new QolMenu("macro", 0, id), 36,
                plugin.color("&8OP-Makro #" + id));
        plugin.fill(inventory);
        inventory.setItem(4, plugin.item(Material.COMMAND_BLOCK, "&d" + macro.name,
                "&7Befehl: &f/" + shorten(macro.command, 120), "&7Erstellt von: &f" + macro.createdBy,
                "&7Letzte Ausführung: &f" + (macro.lastRunAt <= 0L ? "Noch nie" : formatDate(macro.lastRunAt) + " von " + macro.lastRunBy)));
        inventory.setItem(10, plugin.item(Material.NETHER_STAR, "&aMakro ausführen", "&7Platzhalter werden mit deinem aktuellen Ort ersetzt."));
        inventory.setItem(12, plugin.item(macro.enabled ? Material.LIME_DYE : Material.GRAY_DYE,
                "&eStatus umschalten", "&7Aktuell: " + (macro.enabled ? "&aAktiv" : "&cInaktiv")));
        inventory.setItem(14, plugin.item(macro.confirmation ? Material.LIME_DYE : Material.GRAY_DYE,
                "&eBestätigung umschalten", "&7Aktuell: &f" + (macro.confirmation ? "Pflicht" : "Direkt")));
        inventory.setItem(16, plugin.item(Material.RED_DYE, "&cMakro löschen"));
        inventory.setItem(31, plugin.item(Material.BARRIER, "&cZurück"));
        LiveMenus.show(admin,inventory);
    }

    private void handleMacro(Player admin, long id, int slot) {
        AdminMacro macro = macro(id);
        if (macro == null) return;
        if (slot == 10) {
            if (macro.confirmation) openConfirmationJava(admin, "macro-run", id, "", "Makro '" + macro.name + "' als Konsole ausführen?");
            else { executeMacro(admin, id); openMacroJava(admin, id); }
        } else if (slot == 12) { toggleMacro(admin, id); openMacroJava(admin, id); }
        else if (slot == 14) { toggleMacroConfirmation(admin, id); openMacroJava(admin, id); }
        else if (slot == 16) openConfirmationJava(admin, "macro-delete", id, "", "Makro '" + macro.name + "' endgültig löschen?");
        else if (slot == 31) openMacrosJava(admin, 0);
    }

    void openBulkJava(Player admin) {
        if (!plugin.adminMenuAllowed(admin, "adminqualitymanager.openbulkjava")) return;
        if (!requireOperator(admin, "simplexadminsuite.qol.bulk")) return;
        Inventory inventory = LiveMenus.create(new QolMenu("bulk", 0, 0L), 45,
                plugin.color("&8Geprüfte Massenaktionen"));
        plugin.fill(inventory);
        inventory.setItem(10, plugin.item(Material.CHEST, "&aAlle Welten sicher speichern", "&7Führt save-all flush und World#save aus."));
        inventory.setItem(11, plugin.item(Material.GOLDEN_APPLE, "&aAlle Spieler heilen & sättigen", "&7Wirkt auf aktuell verbundene Spieler."));
        inventory.setItem(12, plugin.item(Material.PAPER, "&bÖffentlichen Chat leeren", "&7Mit englischer Systemmeldung und Bestätigung."));
        inventory.setItem(13, plugin.item(Material.WRITABLE_BOOK, "&eServer-Broadcast senden", "&7Öffentlichen englischen Text im Chat eingeben."));
        inventory.setItem(14, plugin.item(Material.CLOCK, "&eAlle Welten: Tag & klares Wetter", "&7Setzt Zeit 1000, Sturm und Gewitter aus."));
        inventory.setItem(15, plugin.item(Material.ENDER_PEARL, "&6Alle Non-Staff zu mir holen", "&cBenötigt eine Bestätigung."));
        inventory.setItem(16, plugin.item(Material.IRON_DOOR, "&cAlle Non-Staff entfernen", "&7Kick-Grund im Chat eingeben.", "&cOperatoren und Admin-Menüberechtigte bleiben."));
        inventory.setItem(31, plugin.item(Material.BARRIER, "&cZurück"));
        LiveMenus.show(admin,inventory);
    }

    private void handleBulk(Player admin, int slot) {
        if (slot == 10) { bulkSaveWorlds(admin); openBulkJava(admin); }
        else if (slot == 11) { bulkHeal(admin); openBulkJava(admin); }
        else if (slot == 12) openConfirmationJava(admin, "bulk-clear-chat", 0L, "", "Öffentlichen Chat für alle Spieler leeren?");
        else if (slot == 13) {
            pendingInputs.put(admin.getUniqueId(), new PendingInput(InputType.BULK_BROADCAST, 0L));
            admin.closeInventory();
            admin.sendMessage(plugin.prefix() + "Schreibe jetzt den öffentlichen englischen Broadcast. 'abbrechen' beendet die Eingabe.");
        } else if (slot == 14) openConfirmationJava(admin, "bulk-day", 0L, "", "Alle geladenen Welten auf Tag und klares Wetter setzen?");
        else if (slot == 15) openConfirmationJava(admin, "bulk-bring", 0L, "", "Alle Non-Staff-Spieler zu deiner Position teleportieren?");
        else if (slot == 16) {
            pendingInputs.put(admin.getUniqueId(), new PendingInput(InputType.BULK_KICK, 0L));
            admin.closeInventory();
            admin.sendMessage(plugin.prefix() + "Schreibe den öffentlichen englischen Kick-Grund. 'abbrechen' beendet die Eingabe.");
        } else if (slot == 31) openDashboardJava(admin);
    }

    void openTimelineJava(Player admin, int requestedPage) {
        if (!plugin.adminMenuAllowed(admin, "adminqualitymanager.opentimelinejava")) return;
        if (!require(admin, "simplexadminsuite.qol.timeline")) return;
        List<TimelineEntry> entries = timelineSnapshot();
        int maxPage = Math.max(0, (entries.size() - 1) / 45);
        int page = Math.max(0, Math.min(maxPage, requestedPage));
        Inventory inventory = LiveMenus.create(new QolMenu("timeline", page, 0L), 54,
                plugin.color("&8QoL-Chronik &7" + (page + 1) + "/" + (maxPage + 1)));
        plugin.fill(inventory);
        for (int i = 0; i < 45; i++) {
            int index = page * 45 + i;
            if (index >= entries.size()) break;
            TimelineEntry entry = entries.get(index);
            inventory.setItem(i, plugin.item(entry.success ? Material.LIME_DYE : Material.RED_DYE,
                    (entry.success ? "&a" : "&c") + categoryLabel(entry.category) + " &8• &f" + entry.action,
                    "&7Zeit: &f" + formatDate(entry.at), "&7Akteur: &f" + entry.actor,
                    "&7" + shorten(entry.details, 120)));
        }
        inventory.setItem(45, plugin.item(Material.ARROW, "&eVorherige Seite"));
        inventory.setItem(49, plugin.item(Material.BARRIER, "&cZurück"));
        inventory.setItem(53, plugin.item(Material.ARROW, "&eNächste Seite"));
        LiveMenus.show(admin,inventory);
    }

    private void handleTimeline(Player admin, int page, int slot) {
        if (slot == 45 && page > 0) openTimelineJava(admin, page - 1);
        else if (slot == 49) openDashboardJava(admin);
        else if (slot == 53) openTimelineJava(admin, page + 1);
    }

    private void openConfirmationJava(Player admin, String action, long id, String value, String question) {
        if (!plugin.adminMenuAllowed(admin, "adminqualitymanager.openconfirmationjava")) return;
        confirmations.put(admin.getUniqueId(), new PendingConfirmation(action, id, value));
        Inventory inventory = LiveMenus.create(new QolMenu("confirm", 0, id), 27,
                plugin.color("&8QoL-Bestätigung"));
        plugin.fill(inventory);
        inventory.setItem(11, plugin.item(Material.LIME_DYE, "&aVerbindlich ausführen", "&7" + shorten(question, 120)));
        inventory.setItem(15, plugin.item(Material.RED_DYE, "&cAbbrechen", "&7Es wird nichts verändert."));
        LiveMenus.show(admin,inventory);
    }

    private void handleConfirmation(Player admin, int slot) {
        PendingConfirmation pending = confirmations.remove(admin.getUniqueId());
        if (pending == null) { openDashboardJava(admin); return; }
        if (slot == 15) { openDashboardJava(admin); return; }
        if (slot != 11) { confirmations.put(admin.getUniqueId(), pending); return; }
        executeConfirmation(admin, pending);
    }

    private void executeConfirmation(Player admin, PendingConfirmation pending) {
        switch (pending.action) {
            case "task-delete" -> { deleteTask(admin, pending.id); openTasksJava(admin, 0); }
            case "schedule-create" -> {
                JobAction action = safeJobAction((int) pending.id);
                long delay = parseLong(pending.value, 5L * 60_000L);
                createJob(admin, action, delay, "");
                scheduleDrafts.remove(admin.getUniqueId());
                openSchedulesJava(admin, 0);
            }
            case "schedule-now" -> { executeJobNow(admin, pending.id); openSchedulesJava(admin, 0); }
            case "schedule-cancel" -> { cancelJob(admin, pending.id); openSchedulesJava(admin, 0); }
            case "schedule-delete" -> { deleteJob(admin, pending.id); openSchedulesJava(admin, 0); }
            case "incident" -> { applyIncident(admin, safeIncident((int) pending.id)); openIncidentsJava(admin); }
            case "macro-run" -> { executeMacro(admin, pending.id); openMacroJava(admin, pending.id); }
            case "macro-delete" -> { deleteMacro(admin, pending.id); openMacrosJava(admin, 0); }
            case "bulk-clear-chat" -> { bulkClearChat(admin); openBulkJava(admin); }
            case "bulk-day" -> { bulkDayWeather(admin); openBulkJava(admin); }
            case "bulk-bring" -> { bulkBring(admin); openBulkJava(admin); }
            case "bulk-kick" -> { bulkKick(admin, pending.value); openBulkJava(admin); }
            default -> openDashboardJava(admin);
        }
    }

    private void acceptInput(Player admin, PendingInput pending, String input) {
        if (input.equalsIgnoreCase("abbrechen") || input.equalsIgnoreCase("cancel")) {
            plugin.menuNotice(admin,plugin.prefix() + "Interne Eingabe wurde abgebrochen.");
            openDashboardJava(admin);
            return;
        }
        if (pending.type == InputType.TASK) {
            String[] parts = input.split("\\|", 2);
            String title = clean(parts.length > 0 ? parts[0] : "", 80);
            String details = clean(parts.length > 1 ? parts[1] : "", 300);
            TaskDraft draft = taskDrafts.remove(admin.getUniqueId());
            if (draft == null) draft = new TaskDraft();
            if (title.isBlank()) plugin.menuNotice(admin,plugin.prefix() + "Aufgabentitel fehlt. Es wurde nichts gespeichert.");
            else createTask(admin, title, details, draft.priority,
                    draft.dueDelayMillis <= 0L ? 0L : System.currentTimeMillis() + draft.dueDelayMillis);
            openTasksJava(admin, 0);
        } else if (pending.type == InputType.MACRO) {
            String[] parts = input.split("\\|", 2);
            if (parts.length < 2) plugin.menuNotice(admin,plugin.prefix() + "Format ungültig. Nutze: Name | Konsolenbefehl");
            else createMacro(admin, clean(parts[0], 60), clean(parts[1], 300), true);
            openMacrosJava(admin, 0);
        } else if (pending.type == InputType.SCHEDULE_ANNOUNCEMENT) {
            createJob(admin, JobAction.ANNOUNCEMENT, pending.value, clean(input, 500));
            scheduleDrafts.remove(admin.getUniqueId());
            openSchedulesJava(admin, 0);
        } else if (pending.type == InputType.BULK_BROADCAST) {
            bulkBroadcast(admin, clean(input, 500));
            openBulkJava(admin);
        } else if (pending.type == InputType.BULK_KICK) {
            openConfirmationJava(admin, "bulk-kick", 0L, clean(input, 300), "Alle Non-Staff-Spieler mit diesem Grund entfernen?");
        }
    }

    synchronized StaffTask createTask(Player admin, String title, String details, Priority priority, long dueAt) {
        if (!require(admin, "simplexadminsuite.qol.tasks")) return null;
        StaffTask task = new StaffTask(nextTaskId++, clean(title, 80), clean(details, 300), priority,
                TaskStatus.OPEN, admin.getName(), System.currentTimeMillis(), dueAt, null, "", "", 0L);
        if (task.title.isBlank()) return null;
        tasks.put(task.id, task);
        trimTasks();
        saveTasks();
        record(admin.getName(), "TASK", "Aufgabe erstellt", "#" + task.id + " " + task.title, true);
        plugin.qolAudit(admin.getName(), "Staff-Aufgabe erstellt", "#" + task.id + " " + task.title);
        return task;
    }

    synchronized boolean claimTask(Player admin, long id) {
        if (!require(admin, "simplexadminsuite.qol.tasks")) return false;
        StaffTask task = tasks.get(id);
        if (task == null || task.status == TaskStatus.DONE) return false;
        if (admin.getUniqueId().equals(task.assignedUuid)) {
            task.assignedUuid = null; task.assignedName = ""; task.status = TaskStatus.OPEN;
            record(admin.getName(), "TASK", "Zuweisung gelöst", "#" + id, true);
        } else {
            task.assignedUuid = admin.getUniqueId(); task.assignedName = admin.getName(); task.status = TaskStatus.CLAIMED;
            record(admin.getName(), "TASK", "Aufgabe übernommen", "#" + id, true);
        }
        saveTasks();
        return true;
    }

    synchronized boolean toggleTaskDone(Player admin, long id) {
        if (!require(admin, "simplexadminsuite.qol.tasks")) return false;
        StaffTask task = tasks.get(id);
        if (task == null) return false;
        if (task.status == TaskStatus.DONE) {
            task.status = task.assignedUuid == null ? TaskStatus.OPEN : TaskStatus.CLAIMED;
            task.completedAt = 0L; task.completedBy = "";
            record(admin.getName(), "TASK", "Aufgabe wieder geöffnet", "#" + id, true);
        } else {
            task.status = TaskStatus.DONE; task.completedAt = System.currentTimeMillis(); task.completedBy = admin.getName();
            record(admin.getName(), "TASK", "Aufgabe abgeschlossen", "#" + id + " " + task.title, true);
        }
        saveTasks();
        return true;
    }

    synchronized boolean cycleTaskPriority(Player admin, long id) {
        if (!require(admin, "simplexadminsuite.qol.tasks")) return false;
        StaffTask task = tasks.get(id);
        if (task == null) return false;
        task.priority = nextPriority(task.priority);
        saveTasks();
        record(admin.getName(), "TASK", "Priorität geändert", "#" + id + " = " + task.priority, true);
        return true;
    }

    synchronized boolean deleteTask(Player admin, long id) {
        if (!require(admin, "simplexadminsuite.qol.tasks")) return false;
        StaffTask removed = tasks.remove(id);
        if (removed == null) return false;
        saveTasks();
        record(admin.getName(), "TASK", "Aufgabe gelöscht", "#" + id + " " + removed.title, true);
        return true;
    }

    synchronized ScheduledJob createJob(Player admin, JobAction action, long delayMillis, String payload) {
        if (!requireOperator(admin, "simplexadminsuite.qol.scheduler")) return null;
        if (action == null) return null;
        long delay = Math.max(10_000L, Math.min(7L * 24L * 60L * 60_000L, delayMillis));
        String cleanPayload = clean(payload, 500);
        if (action == JobAction.ANNOUNCEMENT && cleanPayload.isBlank()) return null;
        ScheduledJob job = new ScheduledJob(nextJobId++, action, JobStatus.PENDING,
                System.currentTimeMillis() + delay, System.currentTimeMillis(), admin.getName(), cleanPayload, 0L, "");
        jobs.put(job.id, job);
        trimJobs();
        saveJobs();
        record(admin.getName(), "SCHEDULER", "Aktion geplant", "#" + job.id + " " + jobActionLabel(action) + " in " + delayLabel(delay), true);
        plugin.qolDiscordEvent("Admin action scheduled", "**" + jobActionEnglish(action) + "** will run in **" + delayLabelEnglish(delay) + "**. Scheduled by **" + admin.getName() + "**.", 0x5865F2);
        return job;
    }

    synchronized boolean cancelJob(Player admin, long id) {
        if (!requireOperator(admin, "simplexadminsuite.qol.scheduler")) return false;
        ScheduledJob job = jobs.get(id);
        if (job == null || job.status != JobStatus.PENDING) return false;
        job.status = JobStatus.CANCELLED; job.finishedAt = System.currentTimeMillis(); job.result = "Von " + admin.getName() + " abgebrochen";
        saveJobs();
        record(admin.getName(), "SCHEDULER", "Aktion abgebrochen", "#" + id, true);
        return true;
    }

    synchronized boolean deleteJob(Player admin, long id) {
        if (!requireOperator(admin, "simplexadminsuite.qol.scheduler")) return false;
        ScheduledJob job = jobs.get(id);
        if (job == null || job.status == JobStatus.PENDING) return false;
        jobs.remove(id); saveJobs();
        record(admin.getName(), "SCHEDULER", "Verlauf gelöscht", "#" + id, true);
        return true;
    }

    synchronized boolean executeJobNow(Player admin, long id) {
        if (!requireOperator(admin, "simplexadminsuite.qol.scheduler")) return false;
        ScheduledJob job = jobs.get(id);
        if (job == null || job.status != JobStatus.PENDING) return false;
        return executeJob(job, admin.getName());
    }

    private synchronized void executeDueJobs() {
        long now = System.currentTimeMillis();
        long grace = Math.max(60_000L, Math.min(60L * 60_000L,
                plugin.getConfig().getLong("admin-qol.scheduler.overdue-grace-minutes", 10L) * 60_000L));
        boolean changed = false;
        for (ScheduledJob job : new ArrayList<>(jobs.values())) {
            if (job.status != JobStatus.PENDING || job.executeAt > now) continue;
            if (!shouldExecuteOverdue(job.executeAt, now, grace)) {
                job.status = JobStatus.MISSED; job.finishedAt = now;
                job.result = "Nach Serverstillstand außerhalb des Nachholfensters übersprungen";
                record("System", "SCHEDULER", "Überfällige Aktion übersprungen", "#" + job.id, false);
                changed = true;
            } else {
                executeJob(job, "System Scheduler");
                changed = true;
            }
        }
        if (changed) saveJobs();
    }

    private boolean executeJob(ScheduledJob job, String executor) {
        if (job == null || job.status != JobStatus.PENDING) return false;
        boolean success = false;
        String result;
        try {
            switch (job.action) {
                case ANNOUNCEMENT -> plugin.qolBroadcast(job.payload);
                case RESTART -> success = plugin.qolScheduleRestart(job.createdBy);
                case MAINTENANCE_ON -> { plugin.qolSetMaintenance(job.createdBy, true, "Scheduled maintenance"); success = true; }
                case MAINTENANCE_OFF -> { plugin.qolSetMaintenance(job.createdBy, false, ""); success = true; }
                case CHAT_ON -> { plugin.qolSetGlobalChat(job.createdBy, true); success = true; }
                case CHAT_OFF -> { plugin.qolSetGlobalChat(job.createdBy, false); success = true; }
                case LOCKDOWN_ON -> { plugin.qolSetCommandLockdown(job.createdBy, true); success = true; }
                case LOCKDOWN_OFF -> { plugin.qolSetCommandLockdown(job.createdBy, false); success = true; }
                case SAVE_ALL -> success = plugin.qolSaveAllWorlds();
            }
            if (job.action == JobAction.ANNOUNCEMENT) success = true;
            result = success ? "Erfolgreich ausgeführt" : "Aktion wurde vom Zielsystem abgelehnt";
        } catch (Throwable error) {
            result = error.getClass().getSimpleName() + ": " + clean(error.getMessage(), 200);
        }
        job.status = success ? JobStatus.EXECUTED : JobStatus.FAILED;
        job.finishedAt = System.currentTimeMillis();
        job.result = result;
        saveJobs();
        record(executor, "SCHEDULER", success ? "Aktion ausgeführt" : "Aktion fehlgeschlagen", "#" + job.id + " " + result, success);
        plugin.qolDiscordEvent(success ? "Scheduled admin action completed" : "Scheduled admin action failed",
                "Action: **" + jobActionEnglish(job.action) + "**\nResult: **" + clean(result, 500) + "**\nOriginally scheduled by **" + job.createdBy + "**.",
                success ? 0x57F287 : 0xED4245);
        return success;
    }

    synchronized boolean applyIncident(Player admin, IncidentProfile profile) {
        if (!requireOperator(admin, "simplexadminsuite.qol.incidents") || profile == null) return false;
        if (profile == IncidentProfile.RESTORE) {
            if (incidentSnapshot == null) {
                plugin.menuNotice(admin,plugin.prefix() + "Es ist kein ursprünglicher Incident-Zustand gespeichert.");
                return false;
            }
            IncidentSnapshot restore = incidentSnapshot;
            plugin.qolSetMaintenance(admin.getName(), restore.maintenance, restore.maintenanceReason);
            plugin.qolSetGlobalChat(admin.getName(), restore.chat);
            plugin.qolSetSlowmode(admin.getName(), restore.slowmode);
            plugin.qolSetCommandLockdown(admin.getName(), restore.commandLockdown);
            activeIncident = null; incidentSnapshot = null; incidentActor = admin.getName(); incidentAt = System.currentTimeMillis();
            saveIncident();
            record(admin.getName(), "INCIDENT", "Ursprünglicher Zustand wiederhergestellt", "Wartung/Chat/Slowmode/Lockdown", true);
            plugin.qolDiscordEvent("Incident profile restored", "The original server-control state was restored by **" + admin.getName() + "**.", 0x57F287);
            return true;
        }
        if (incidentSnapshot == null) incidentSnapshot = new IncidentSnapshot(plugin.qolMaintenanceActive(), plugin.qolMaintenanceReason(),
                plugin.qolGlobalChatEnabled(), plugin.qolSlowmodeSeconds(), plugin.qolCommandLockdownEnabled());
        switch (profile) {
            case CHAT_CONTROL -> {
                plugin.qolSetMaintenance(admin.getName(), false, "");
                plugin.qolSetGlobalChat(admin.getName(), true);
                plugin.qolSetSlowmode(admin.getName(), 10);
                plugin.qolSetCommandLockdown(admin.getName(), false);
            }
            case CHAT_FREEZE -> {
                plugin.qolSetMaintenance(admin.getName(), false, "");
                plugin.qolSetGlobalChat(admin.getName(), false);
                plugin.qolSetSlowmode(admin.getName(), 0);
                plugin.qolSetCommandLockdown(admin.getName(), false);
            }
            case SECURITY_LOCKDOWN -> {
                plugin.qolSetGlobalChat(admin.getName(), false);
                plugin.qolSetSlowmode(admin.getName(), 10);
                plugin.qolSetCommandLockdown(admin.getName(), true);
                plugin.qolSetMaintenance(admin.getName(), true, "Security incident in progress");
            }
            case MAINTENANCE -> {
                plugin.qolSetGlobalChat(admin.getName(), true);
                plugin.qolSetSlowmode(admin.getName(), 0);
                plugin.qolSetCommandLockdown(admin.getName(), false);
                plugin.qolSetMaintenance(admin.getName(), true, "Scheduled server maintenance");
            }
            default -> { return false; }
        }
        activeIncident = profile; incidentActor = admin.getName(); incidentAt = System.currentTimeMillis();
        saveIncident();
        record(admin.getName(), "INCIDENT", "Profil aktiviert", incidentLabel(profile), true);
        plugin.qolDiscordEvent("Admin incident profile activated", "Profile: **" + incidentEnglish(profile) + "**\nActivated by **" + admin.getName() + "**.", 0xFEE75C);
        return true;
    }

    HealthSnapshot sampleHealth(boolean manual) {
        HealthSnapshot snapshot = captureHealth();
        health = snapshot;
        if (!plugin.getConfig().getBoolean("admin-qol.health-guard.enabled", true)) return snapshot;
        // A manual refresh is informational. Opening the health menu must not create alerts or
        // bypass the configured consecutive-sample and cooldown protections.
        if (manual) return snapshot;
        if (snapshot.level == HealthLevel.HEALTHY) {
            consecutiveHealthProblems = 0;
            lastAlertLevel = HealthLevel.HEALTHY;
            return snapshot;
        }
        consecutiveHealthProblems++;
        int required = Math.max(1, Math.min(10,
                plugin.getConfig().getInt("admin-qol.health-guard.consecutive-samples", 3)));
        if (consecutiveHealthProblems >= required) maybeAlertHealth(snapshot, false);
        return snapshot;
    }

    private void maybeAlertHealth(HealthSnapshot snapshot, boolean manual) {
        long now = System.currentTimeMillis();
        long cooldown = Math.max(30L, Math.min(3600L,
                plugin.getConfig().getLong("admin-qol.health-guard.alert-cooldown-seconds", 300L))) * 1000L;
        if (!manual && snapshot.level == lastAlertLevel && now - lastHealthAlertAt < cooldown) return;
        lastHealthAlertAt = now;
        lastAlertLevel = snapshot.level;
        String details = healthDetails(snapshot);
        TimelineEntry event = new TimelineEntry(now, "Health-Guard", "HEALTH",
                snapshot.level == HealthLevel.CRITICAL ? "Kritischer Serverzustand" : "Serverwarnung", details,
                snapshot.level != HealthLevel.CRITICAL);
        synchronized (this) {
            healthEvents.add(0, event);
            while (healthEvents.size() > 100) healthEvents.remove(healthEvents.size() - 1);
            saveHealthEvents();
        }
        record("Health-Guard", "HEALTH", event.action, details, event.success);
        for (Player online : Bukkit.getOnlinePlayers()) if (plugin.has(online, "simplexadminsuite.qol.health")) {
            online.sendMessage(plugin.prefix() + (snapshot.level == HealthLevel.CRITICAL ? plugin.color("&cKritischer Health-Guard-Alarm: &f") : plugin.color("&6Health-Guard-Warnung: &f")) + details);
        }
        if (plugin.getConfig().getBoolean("admin-qol.health-guard.discord-alerts", true)) {
            plugin.qolDiscordEvent(snapshot.level == HealthLevel.CRITICAL ? "Critical server health alert" : "Server health warning",
                    "**TPS:** " + decimal(snapshot.tps) + "\n**MSPT:** " + decimal(snapshot.mspt)
                            + "\n**Memory:** " + snapshot.memoryPercent + "%\n**Loaded chunks:** " + snapshot.loadedChunks,
                    snapshot.level == HealthLevel.CRITICAL ? 0xED4245 : 0xFEE75C);
        }
    }

    synchronized boolean toggleHealthSetting(Player admin, String setting) {
        if (!require(admin, "simplexadminsuite.qol.health")) return false;
        String path = "admin-qol.health-guard." + setting;
        boolean value = !plugin.getConfig().getBoolean(path, true);
        plugin.getConfig().set(path, value); plugin.saveConfig();
        record(admin.getName(), "HEALTH", "Einstellung geändert", setting + " = " + value, true);
        return value;
    }

    synchronized void cycleHealthThreshold(Player admin, String type) {
        if (!require(admin, "simplexadminsuite.qol.health")) return;
        if (type.equals("tps")) {
            double current = plugin.getConfig().getDouble("admin-qol.health-guard.tps-warning", 18D);
            double[][] values = new double[][]{{19D,17D},{18D,15D},{17D,14D},{16D,12D}};
            int next = 0; for (int i = 0; i < values.length; i++) if (values[i][0] == current) next = (i + 1) % values.length;
            plugin.getConfig().set("admin-qol.health-guard.tps-warning", values[next][0]);
            plugin.getConfig().set("admin-qol.health-guard.tps-critical", values[next][1]);
        } else if (type.equals("memory")) {
            int current = plugin.getConfig().getInt("admin-qol.health-guard.memory-warning-percent", 85);
            int[][] values = new int[][]{{80,90},{85,95},{90,97}};
            int next = 0; for (int i = 0; i < values.length; i++) if (values[i][0] == current) next = (i + 1) % values.length;
            plugin.getConfig().set("admin-qol.health-guard.memory-warning-percent", values[next][0]);
            plugin.getConfig().set("admin-qol.health-guard.memory-critical-percent", values[next][1]);
        } else if (type.equals("chunks")) {
            long current = plugin.getConfig().getLong("admin-qol.health-guard.loaded-chunks-warning", 10000L);
            long[] values = new long[]{5000L,10000L,20000L,50000L}; int next = 0;
            for (int i = 0; i < values.length; i++) if (values[i] == current) next = (i + 1) % values.length;
            plugin.getConfig().set("admin-qol.health-guard.loaded-chunks-warning", values[next]);
        } else if (type.equals("cooldown")) {
            int current = plugin.getConfig().getInt("admin-qol.health-guard.alert-cooldown-seconds", 300);
            int[] values = new int[]{60,180,300,600,1800}; int next = 0;
            for (int i = 0; i < values.length; i++) if (values[i] == current) next = (i + 1) % values.length;
            plugin.getConfig().set("admin-qol.health-guard.alert-cooldown-seconds", values[next]);
        }
        plugin.saveConfig();
        record(admin.getName(), "HEALTH", "Grenzwert geändert", type, true);
    }

    private HealthSnapshot captureHealth() {
        double tps = -1D;
        double mspt = -1D;
        try {
            Method method = Bukkit.class.getMethod("getTPS");
            Object result = method.invoke(null);
            if (result instanceof double[] && ((double[]) result).length > 0) tps = ((double[]) result)[0];
        } catch (Throwable ignored) {
            try {
                Object server = Bukkit.class.getMethod("getServer").invoke(null);
                Object result = server.getClass().getMethod("getTPS").invoke(server);
                if (result instanceof double[] && ((double[]) result).length > 0) tps = ((double[]) result)[0];
            } catch (Throwable ignoredAgain) { }
        }
        try {
            Method method = Bukkit.class.getMethod("getAverageTickTime");
            Object result = method.invoke(null);
            if (result instanceof Number) mspt = ((Number) result).doubleValue();
        } catch (Throwable ignored) { }
        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();
        long max = Math.max(1L, runtime.maxMemory());
        int memory = (int) Math.max(0L, Math.min(100L, Math.round(used * 100D / max)));
        long chunks = 0L;
        for (World world : Bukkit.getWorlds()) {
            try {
                Object array = world.getClass().getMethod("getLoadedChunks").invoke(world);
                if (array != null && array.getClass().isArray()) chunks += Array.getLength(array);
            } catch (Throwable ignored) { }
        }
        double warningTps = plugin.getConfig().getDouble("admin-qol.health-guard.tps-warning", 18D);
        double criticalTps = plugin.getConfig().getDouble("admin-qol.health-guard.tps-critical", 15D);
        int warningMemory = plugin.getConfig().getInt("admin-qol.health-guard.memory-warning-percent", 85);
        int criticalMemory = plugin.getConfig().getInt("admin-qol.health-guard.memory-critical-percent", 95);
        long warningChunks = plugin.getConfig().getLong("admin-qol.health-guard.loaded-chunks-warning", 10000L);
        HealthLevel level = evaluateHealth(tps, memory, chunks, warningTps, criticalTps, warningMemory, criticalMemory, warningChunks);
        return new HealthSnapshot(tps, mspt, memory, chunks, Bukkit.getOnlinePlayers().size(), level, System.currentTimeMillis());
    }

    static HealthLevel evaluateHealth(double tps, int memoryPercent, long chunks, double warningTps,
                                      double criticalTps, int warningMemory, int criticalMemory,
                                      long warningChunks) {
        boolean critical = tps >= 0D && tps < criticalTps || memoryPercent >= criticalMemory
                || warningChunks > 0L && chunks >= warningChunks * 2L;
        if (critical) return HealthLevel.CRITICAL;
        boolean warning = tps >= 0D && tps < warningTps || memoryPercent >= warningMemory
                || warningChunks > 0L && chunks >= warningChunks;
        return warning ? HealthLevel.WARNING : HealthLevel.HEALTHY;
    }

    synchronized AdminMacro createMacro(Player admin, String name, String command, boolean confirmation) {
        if (!requireOperator(admin, "simplexadminsuite.qol.macros")) return null;
        String cleanName = clean(name, 60);
        String cleanCommand = normalizeCommand(command);
        String validation = validateMacroCommand(cleanCommand);
        if (!validation.isBlank()) {
            plugin.menuNotice(admin,plugin.prefix() + "Makro wurde abgelehnt: " + validation);
            record(admin.getName(), "MACRO", "Makro abgelehnt", validation, false);
            return null;
        }
        long id = nextMacroId++;
        AdminMacro macro = new AdminMacro(id, cleanName.isBlank() ? "Makro " + id : cleanName,
                cleanCommand, true, confirmation, admin.getName(), System.currentTimeMillis(), 0L, "");
        macros.put(macro.id, macro); trimMacros(); saveMacros();
        record(admin.getName(), "MACRO", "Makro erstellt", "#" + macro.id + " " + macro.name, true);
        return macro;
    }

    synchronized boolean toggleMacro(Player admin, long id) {
        if (!requireOperator(admin, "simplexadminsuite.qol.macros")) return false;
        AdminMacro macro = macros.get(id); if (macro == null) return false;
        macro.enabled = !macro.enabled; saveMacros();
        record(admin.getName(), "MACRO", "Status geändert", "#" + id + " = " + macro.enabled, true);
        return true;
    }

    synchronized boolean toggleMacroConfirmation(Player admin, long id) {
        if (!requireOperator(admin, "simplexadminsuite.qol.macros")) return false;
        AdminMacro macro = macros.get(id); if (macro == null) return false;
        macro.confirmation = !macro.confirmation; saveMacros();
        record(admin.getName(), "MACRO", "Bestätigung geändert", "#" + id + " = " + macro.confirmation, true);
        return true;
    }

    synchronized boolean deleteMacro(Player admin, long id) {
        if (!requireOperator(admin, "simplexadminsuite.qol.macros")) return false;
        AdminMacro macro = macros.remove(id); if (macro == null) return false;
        saveMacros(); record(admin.getName(), "MACRO", "Makro gelöscht", "#" + id + " " + macro.name, true);
        return true;
    }

    synchronized boolean executeMacro(Player admin, long id) {
        if (!requireOperator(admin, "simplexadminsuite.qol.macros")) return false;
        AdminMacro macro = macros.get(id);
        if (macro == null || !macro.enabled) return false;
        String validation = validateMacroCommand(macro.command);
        if (!validation.isBlank()) {
            macro.enabled = false; saveMacros();
            plugin.menuNotice(admin,plugin.prefix() + "Makro wurde aus Sicherheitsgründen deaktiviert: " + validation);
            record(admin.getName(), "MACRO", "Ausführung blockiert", "#" + id + " " + validation, false);
            return false;
        }
        String command = expandMacro(macro.command, admin, Bukkit.getOnlinePlayers().size());
        boolean result = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        macro.lastRunAt = System.currentTimeMillis(); macro.lastRunBy = admin.getName(); saveMacros();
        record(admin.getName(), "MACRO", result ? "Makro ausgeführt" : "Makro abgelehnt", "#" + id + " /" + command, result);
        plugin.qolAudit(admin.getName(), "OP-Makro ausgeführt", "#" + id + " " + macro.name + " • Erfolg=" + result);
        plugin.qolDiscordEvent(result ? "Admin macro completed" : "Admin macro failed",
                "Macro: **" + macro.name + "**\nExecuted by **" + admin.getName() + "**.", result ? 0x57F287 : 0xED4245);
        plugin.menuNotice(admin,plugin.prefix() + "Makro '" + macro.name + "' wurde " + (result ? "ausgeführt." : "vom Server abgelehnt."));
        return result;
    }

    static String validateMacroCommand(String command) {
        String normalized = normalizeCommand(command);
        if (normalized.isBlank()) return "Befehl fehlt";
        if (normalized.length() > 300) return "Befehl ist länger als 300 Zeichen";
        if (normalized.indexOf('\n') >= 0 || normalized.indexOf('\r') >= 0 || normalized.indexOf(';') >= 0) return "Befehlsketten oder Zeilenumbrüche sind nicht erlaubt";
        String root = normalized.toLowerCase(Locale.ROOT).split("\\s+", 2)[0];
        int colon = root.indexOf(':');
        String plain = colon >= 0 ? root.substring(colon + 1) : root;
        if (List.of("stop","restart","reload","rl","op","deop","plugman","plugmanx","execute","function","sudo",
                "lp","luckperms","pex","permissions").contains(plain)) return "Lifecycle-, Plugin-, Berechtigungs-, Verkettungs- oder OP-Befehle sind gesperrt";
        if (plain.equals("sas") || plain.equals("simplexadmin") || plain.equals("simplexadminsuite")) return "Rekursive Suite-Befehle sind gesperrt";
        return "";
    }

    static String expandMacro(String command, String player, String world, int x, int y, int z, int online) {
        return normalizeCommand(command).replace("{player}", safePlaceholder(player)).replace("{world}", safePlaceholder(world))
                .replace("{x}", Integer.toString(x)).replace("{y}", Integer.toString(y)).replace("{z}", Integer.toString(z))
                .replace("{online}", Integer.toString(Math.max(0, online)));
    }

    private static String expandMacro(String command, Player admin, int online) {
        int x = 0, y = 0, z = 0;
        String world = admin.getWorld() == null ? "world" : admin.getWorld().getName();
        try { x = (int) Math.floor(admin.getLocation().getX()); y = (int) Math.floor(admin.getLocation().getY()); z = (int) Math.floor(admin.getLocation().getZ()); }
        catch (Throwable ignored) { }
        return expandMacro(command, admin.getName(), world, x, y, z, online);
    }

    void bulkSaveWorlds(Player admin) {
        if (!requireOperator(admin, "simplexadminsuite.qol.bulk")) return;
        boolean saved = plugin.qolSaveAllWorlds();
        record(admin.getName(), "BULK", saved ? "Alle Welten gespeichert" : "Welt-Save fehlgeschlagen", Bukkit.getWorlds().size() + " Welten", saved);
        plugin.qolDiscordEvent(saved ? "All worlds saved" : "World save failed",
                "A bulk world save was requested by **" + admin.getName() + "** for **" + Bukkit.getWorlds().size() + " loaded worlds**.", saved ? 0x57F287 : 0xED4245);
        plugin.menuNotice(admin,plugin.prefix() + (saved ? "Alle geladenen Welten wurden sicher gespeichert." : "Der Paper-Save-Befehl wurde abgelehnt; prüfe das Serverlog."));
    }

    void bulkHeal(Player admin) {
        if (!requireOperator(admin, "simplexadminsuite.qol.bulk")) return;
        int changed = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            try { player.setHealth(player.getMaxHealth()); player.setFoodLevel(20); changed++; }
            catch (Throwable ignored) { }
        }
        Bukkit.broadcastMessage(plugin.prefix() + "All online players have been healed and fed by staff.");
        record(admin.getName(), "BULK", "Spieler geheilt", changed + " Spieler", true);
        plugin.qolDiscordEvent("Bulk player recovery", "**" + changed + " online players** were healed and fed by **" + admin.getName() + "**.", 0x57F287);
    }

    void bulkClearChat(Player admin) {
        if (!requireOperator(admin, "simplexadminsuite.qol.bulk")) return;
        plugin.qolClearPublicChat(admin.getName());
        record(admin.getName(), "BULK", "Chat geleert", "Öffentlicher Chat", true);
        plugin.qolDiscordEvent("Public chat cleared", "Public chat was cleared by **" + admin.getName() + "**.", 0x5865F2);
    }

    void bulkBroadcast(Player admin, String text) {
        if (!requireOperator(admin, "simplexadminsuite.qol.bulk")) return;
        String clean = clean(text, 500); if (clean.isBlank()) return;
        plugin.qolBroadcast(clean);
        record(admin.getName(), "BULK", "Broadcast gesendet", clean, true);
        plugin.qolDiscordEvent("Server broadcast sent", "**" + admin.getName() + "** sent a server-wide announcement.\n" + clean, 0x5865F2);
    }

    void bulkDayWeather(Player admin) {
        if (!requireOperator(admin, "simplexadminsuite.qol.bulk")) return;
        int changed = 0;
        for (World world : Bukkit.getWorlds()) {
            invoke(world, "setTime", 1000L); invoke(world, "setStorm", false); invoke(world, "setThundering", false); changed++;
        }
        Bukkit.broadcastMessage(plugin.prefix() + "Daytime and clear weather were applied to all loaded worlds by staff.");
        record(admin.getName(), "BULK", "Tag und Wetter gesetzt", changed + " Welten", true);
        plugin.qolDiscordEvent("World conditions updated", "Daytime and clear weather were applied to **" + changed + " loaded worlds** by **" + admin.getName() + "**.", 0xFEE75C);
    }

    void bulkBring(Player admin) {
        if (!requireOperator(admin, "simplexadminsuite.qol.bulk")) return;
        int moved = 0;
        for (Player player : new ArrayList<>(Bukkit.getOnlinePlayers())) {
            if (player.getUniqueId().equals(admin.getUniqueId()) || plugin.qolIsStaff(player)) continue;
            try { if (player.teleport(admin.getLocation())) moved++; } catch (Throwable ignored) { }
        }
        Bukkit.broadcastMessage(plugin.prefix() + "All online players have been moved to the event location by staff.");
        record(admin.getName(), "BULK", "Non-Staff teleportiert", moved + " Spieler", true);
        plugin.qolDiscordEvent("Bulk player teleport", "**" + moved + " non-staff players** were moved to **" + admin.getName() + "**.", 0xFEE75C);
    }

    void bulkKick(Player admin, String reason) {
        if (!requireOperator(admin, "simplexadminsuite.qol.bulk")) return;
        String cleanReason = clean(reason, 300);
        if (cleanReason.isBlank()) cleanReason = "Server maintenance is starting.";
        int kicked = 0;
        for (Player player : new ArrayList<>(Bukkit.getOnlinePlayers())) {
            if (plugin.qolIsStaff(player)||plugin.emergency(player.getUniqueId())) continue;
            plugin.suiteKick(player,cleanReason); kicked++;
        }
        record(admin.getName(), "BULK", "Non-Staff entfernt", kicked + " Spieler • " + cleanReason, true);
        plugin.qolDiscordEvent("Bulk player removal", "**" + kicked + " players** were disconnected by **" + admin.getName() + "**.\nReason: " + cleanReason, 0xFEE75C);
    }

    synchronized List<StaffTask> tasksSnapshot() {
        List<StaffTask> result = new ArrayList<>(tasks.values());
        result.sort(Comparator.comparingInt((StaffTask task) -> task.status == TaskStatus.DONE ? 1 : 0)
                .thenComparingInt(task -> -task.priority.ordinal())
                .thenComparingLong(task -> task.dueAt <= 0L ? Long.MAX_VALUE : task.dueAt)
                .thenComparingLong(task -> -task.createdAt));
        return result;
    }

    synchronized StaffTask task(long id) { return tasks.get(id); }
    synchronized List<ScheduledJob> jobsSnapshot() {
        List<ScheduledJob> result = new ArrayList<>(jobs.values());
        result.sort(Comparator.comparingInt((ScheduledJob job) -> job.status == JobStatus.PENDING ? 0 : 1)
                .thenComparingLong(job -> job.status == JobStatus.PENDING ? job.executeAt : -job.finishedAt));
        return result;
    }
    synchronized ScheduledJob job(long id) { return jobs.get(id); }
    synchronized List<AdminMacro> macrosSnapshot() {
        List<AdminMacro> result = new ArrayList<>(macros.values());
        result.sort(Comparator.comparing((AdminMacro macro) -> !macro.enabled).thenComparingLong(macro -> macro.id));
        return result;
    }
    synchronized AdminMacro macro(long id) { return macros.get(id); }
    synchronized List<TimelineEntry> timelineSnapshot() { return new ArrayList<>(timeline); }
    synchronized List<TimelineEntry> healthEventsSnapshot() { return new ArrayList<>(healthEvents); }
    HealthSnapshot healthSnapshot() { return health; }
    IncidentProfile activeIncident() { return activeIncident; }

    synchronized int openTaskCount() { return (int) tasks.values().stream().filter(task -> task.status != TaskStatus.DONE).count(); }
    synchronized int overdueTaskCount() { long now = System.currentTimeMillis(); return (int) tasks.values().stream().filter(task -> task.overdue(now)).count(); }
    synchronized int doneTaskCount() { return (int) tasks.values().stream().filter(task -> task.status == TaskStatus.DONE).count(); }
    synchronized int pendingJobCount() { return (int) jobs.values().stream().filter(job -> job.status == JobStatus.PENDING).count(); }
    synchronized int macroCount() { return macros.size(); }
    synchronized int enabledMacroCount() { return (int) macros.values().stream().filter(macro -> macro.enabled).count(); }
    synchronized int timelineSize() { return timeline.size(); }
    synchronized int healthEventCount() { return healthEvents.size(); }

    String dashboardContent() {
        HealthSnapshot current = health;
        return "§fOffene Staff-Aufgaben: §e" + openTaskCount() + " §8• §c" + overdueTaskCount() + " überfällig\n"
                + "§fGeplante Aktionen: §e" + pendingJobCount() + " §8• §fNächste: §e" + nextJobText() + "\n"
                + "§fIncident-Profil: §e" + incidentLabel(activeIncident) + "\n"
                + "§fHealth-Guard: " + levelSection(current.level) + healthShort(current) + "\n"
                + "§fOP-Makros: §e" + enabledMacroCount() + "/" + macroCount() + " §8• §fChronik: §e" + timelineSize() + "/200\n\n"
                + "§7Alle Bereiche sind persistent, auditierbar und für Java sowie Bedrock gleichwertig verfügbar.";
    }

    String incidentContent() {
        return "§fAktives Profil: §e" + incidentLabel(activeIncident) + "\n"
                + "§fUrsprungszustand gespeichert: §e" + (incidentSnapshot == null ? "Nein" : "Ja") + "\n"
                + "§fLetzte Änderung: §e" + (incidentAt <= 0L ? "Noch nie" : formatDate(incidentAt)) + "\n"
                + "§fAkteur: §e" + (incidentActor.isBlank() ? "Niemand" : incidentActor) + "\n\n"
                + "§7Beim ersten Profil wird Wartung, Chat, Slowmode und Befehls-Lockdown gesichert. Wiederherstellen setzt exakt diese vier Werte zurück.";
    }

    String healthContent() {
        HealthSnapshot current = sampleHealth(true);
        return levelSection(current.level) + "§l" + healthLevelLabel(current.level) + "\n\n"
                + "§fTPS: §e" + decimal(current.tps) + " §8• §fMSPT: §e" + decimal(current.mspt) + "\n"
                + "§fRAM: §e" + current.memoryPercent + "% §8• §fGeladene Chunks: §e" + current.loadedChunks + "\n"
                + "§fSpieler online: §e" + current.onlinePlayers + "\n\n"
                + "§fGuard: §e" + (plugin.getConfig().getBoolean("admin-qol.health-guard.enabled", true) ? "Aktiv" : "Inaktiv")
                + " §8• §fDiscord: §e" + (plugin.getConfig().getBoolean("admin-qol.health-guard.discord-alerts", true) ? "Aktiv" : "Inaktiv")
                + "\n§fHealth-Ereignisse: §e" + healthEventCount() + "/100";
    }

    String timelineContent(int maximum) {
        StringBuilder text = new StringBuilder();
        for (TimelineEntry entry : timelineSnapshot().stream().limit(Math.max(1, maximum)).toList()) {
            text.append(entry.success ? "§a✔ " : "§c✖ ").append("§f").append(formatDate(entry.at))
                    .append(" §8• §e").append(categoryLabel(entry.category)).append(" §8• §f").append(entry.action)
                    .append("\n§7").append(shorten(entry.details, 150)).append("\n\n");
        }
        return text.length() == 0 ? "§7Noch keine QoL-Aktivitäten gespeichert." : text.toString();
    }

    String healthHistoryContent(int maximum) {
        StringBuilder text = new StringBuilder();
        for (TimelineEntry entry : healthEventsSnapshot().stream().limit(Math.max(1, maximum)).toList()) {
            text.append(entry.success ? "§6⚠ " : "§c✖ ").append("§f").append(formatDate(entry.at))
                    .append(" §8• §f").append(entry.action).append("\n§7").append(shorten(entry.details, 180)).append("\n\n");
        }
        return text.length() == 0 ? "§7Noch keine Health-Warnungen gespeichert." : text.toString();
    }

    private synchronized String nextJobText() {
        return jobs.values().stream().filter(job -> job.status == JobStatus.PENDING)
                .min(Comparator.comparingLong(job -> job.executeAt))
                .map(job -> "#" + job.id + " " + relativeTime(job.executeAt)).orElse("Keine");
    }

    private synchronized void record(String actor, String category, String action, String details, boolean success) {
        timeline.add(0, new TimelineEntry(System.currentTimeMillis(), clean(actor, 80), clean(category, 30),
                clean(action, 100), clean(details, 500), success));
        while (timeline.size() > 200) timeline.remove(timeline.size() - 1);
        saveTimeline();
    }

    private synchronized void loadAll() {
        loadTasks(); loadJobs(); loadMacros(); loadTimeline(); loadHealthEvents(); loadIncident();
        seedMacrosIfNeeded();
    }

    private synchronized void saveAll() {
        saveTasks(); saveJobs(); saveMacros(); saveTimeline(); saveHealthEvents(); saveIncident();
    }

    private void loadTasks() {
        tasks.clear(); nextTaskId = 1L;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(tasksFile);
        int count = Math.max(0, Math.min(500, y.getInt("count", 0)));
        for (int i = 0; i < count; i++) {
            String base = "entries." + i;
            long id = y.getLong(base + ".id", i + 1L);
            UUID assigned = parseUuid(y.getString(base + ".assigned-uuid", ""));
            StaffTask task = new StaffTask(id, y.getString(base + ".title", "Aufgabe"), y.getString(base + ".details", ""),
                    parseEnum(Priority.class, y.getString(base + ".priority", "NORMAL"), Priority.NORMAL),
                    parseEnum(TaskStatus.class, y.getString(base + ".status", "OPEN"), TaskStatus.OPEN),
                    y.getString(base + ".created-by", "System"), y.getLong(base + ".created-at", 0L),
                    y.getLong(base + ".due-at", 0L), assigned, y.getString(base + ".assigned-name", ""),
                    y.getString(base + ".completed-by", ""), y.getLong(base + ".completed-at", 0L));
            tasks.put(id, task); nextTaskId = Math.max(nextTaskId, id + 1L);
        }
    }

    private void saveTasks() {
        try {
            YamlConfiguration y = new YamlConfiguration(); y.set("schema-version", 1); y.set("count", tasks.size());
            int i = 0; for (StaffTask task : tasks.values()) {
                String b = "entries." + i++; y.set(b + ".id", task.id); y.set(b + ".title", task.title); y.set(b + ".details", task.details);
                y.set(b + ".priority", task.priority.name()); y.set(b + ".status", task.status.name()); y.set(b + ".created-by", task.createdBy);
                y.set(b + ".created-at", task.createdAt); y.set(b + ".due-at", task.dueAt); y.set(b + ".assigned-uuid", task.assignedUuid == null ? "" : task.assignedUuid.toString());
                y.set(b + ".assigned-name", task.assignedName); y.set(b + ".completed-by", task.completedBy); y.set(b + ".completed-at", task.completedAt);
            }
            saveAtomic(y, tasksFile);
        } catch (Exception error) { plugin.getLogger().warning("Staff-Aufgaben konnten nicht gespeichert werden: " + error.getMessage()); }
    }

    private void loadJobs() {
        jobs.clear(); nextJobId = 1L; YamlConfiguration y = YamlConfiguration.loadConfiguration(schedulesFile);
        int count = Math.max(0, Math.min(500, y.getInt("count", 0)));
        for (int i = 0; i < count; i++) {
            String b = "entries." + i; long id = y.getLong(b + ".id", i + 1L);
            ScheduledJob job = new ScheduledJob(id, parseEnum(JobAction.class, y.getString(b + ".action", "ANNOUNCEMENT"), JobAction.ANNOUNCEMENT),
                    parseEnum(JobStatus.class, y.getString(b + ".status", "PENDING"), JobStatus.PENDING),
                    y.getLong(b + ".execute-at", 0L), y.getLong(b + ".created-at", 0L), y.getString(b + ".created-by", "System"),
                    y.getString(b + ".payload", ""), y.getLong(b + ".finished-at", 0L), y.getString(b + ".result", ""));
            jobs.put(id, job); nextJobId = Math.max(nextJobId, id + 1L);
        }
    }

    private void saveJobs() {
        try {
            YamlConfiguration y = new YamlConfiguration(); y.set("schema-version", 1); y.set("count", jobs.size()); int i = 0;
            for (ScheduledJob job : jobs.values()) { String b = "entries." + i++; y.set(b + ".id", job.id); y.set(b + ".action", job.action.name());
                y.set(b + ".status", job.status.name()); y.set(b + ".execute-at", job.executeAt); y.set(b + ".created-at", job.createdAt);
                y.set(b + ".created-by", job.createdBy); y.set(b + ".payload", job.payload); y.set(b + ".finished-at", job.finishedAt); y.set(b + ".result", job.result); }
            saveAtomic(y, schedulesFile);
        } catch (Exception error) { plugin.getLogger().warning("Geplante Admin-Aktionen konnten nicht gespeichert werden: " + error.getMessage()); }
    }

    private void loadMacros() {
        macros.clear(); nextMacroId = 1L; YamlConfiguration y = YamlConfiguration.loadConfiguration(macrosFile);
        int count = Math.max(0, Math.min(200, y.getInt("count", 0)));
        for (int i = 0; i < count; i++) { String b = "entries." + i; long id = y.getLong(b + ".id", i + 1L);
            AdminMacro macro = new AdminMacro(id, y.getString(b + ".name", "Makro"), y.getString(b + ".command", "list"),
                    y.getBoolean(b + ".enabled", true), y.getBoolean(b + ".confirmation", true), y.getString(b + ".created-by", "System"),
                    y.getLong(b + ".created-at", 0L), y.getLong(b + ".last-run-at", 0L), y.getString(b + ".last-run-by", ""));
            macros.put(id, macro); nextMacroId = Math.max(nextMacroId, id + 1L); }
    }

    private void saveMacros() {
        try { YamlConfiguration y = new YamlConfiguration(); y.set("schema-version", 1); y.set("count", macros.size()); int i = 0;
            for (AdminMacro macro : macros.values()) { String b = "entries." + i++; y.set(b + ".id", macro.id); y.set(b + ".name", macro.name); y.set(b + ".command", macro.command);
                y.set(b + ".enabled", macro.enabled); y.set(b + ".confirmation", macro.confirmation); y.set(b + ".created-by", macro.createdBy); y.set(b + ".created-at", macro.createdAt);
                y.set(b + ".last-run-at", macro.lastRunAt); y.set(b + ".last-run-by", macro.lastRunBy); }
            saveAtomic(y, macrosFile);
        } catch (Exception error) { plugin.getLogger().warning("Admin-Makros konnten nicht gespeichert werden: " + error.getMessage()); }
    }

    private void seedMacrosIfNeeded() {
        if (!macros.isEmpty()) return;
        long now = System.currentTimeMillis();
        macros.put(nextMacroId, new AdminMacro(nextMacroId++, "Welten sicher speichern", "save-all flush", true, false, "6.0.0 Default", now, 0L, ""));
        macros.put(nextMacroId, new AdminMacro(nextMacroId++, "Online-Liste protokollieren", "list", true, false, "6.0.0 Default", now, 0L, ""));
        macros.put(nextMacroId, new AdminMacro(nextMacroId++, "Whitelist anzeigen", "whitelist list", true, true, "6.0.0 Default", now, 0L, ""));
        saveMacros();
    }

    private void loadTimeline() { timeline.clear(); loadTimelineFile(timelineFile, timeline, 200); }
    private void saveTimeline() { saveTimelineFile(timelineFile, timeline, 200); }
    private void loadHealthEvents() { healthEvents.clear(); loadTimelineFile(healthFile, healthEvents, 100); }
    private void saveHealthEvents() { saveTimelineFile(healthFile, healthEvents, 100); }

    private void loadTimelineFile(File file, List<TimelineEntry> target, int maximum) {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file); int count = Math.max(0, Math.min(maximum, y.getInt("count", 0)));
        for (int i = 0; i < count; i++) { String b = "entries." + i; target.add(new TimelineEntry(y.getLong(b + ".at", 0L), y.getString(b + ".actor", "System"),
                y.getString(b + ".category", "SYSTEM"), y.getString(b + ".action", "Aktion"), y.getString(b + ".details", ""), y.getBoolean(b + ".success", true))); }
    }

    private void saveTimelineFile(File file, List<TimelineEntry> source, int maximum) {
        try { YamlConfiguration y = new YamlConfiguration(); int count = Math.min(maximum, source.size()); y.set("schema-version", 1); y.set("count", count);
            for (int i = 0; i < count; i++) { TimelineEntry entry = source.get(i); String b = "entries." + i; y.set(b + ".at", entry.at); y.set(b + ".actor", entry.actor);
                y.set(b + ".category", entry.category); y.set(b + ".action", entry.action); y.set(b + ".details", entry.details); y.set(b + ".success", entry.success); }
            saveAtomic(y, file);
        } catch (Exception error) { plugin.getLogger().warning(file.getName() + " konnte nicht gespeichert werden: " + error.getMessage()); }
    }

    private void loadIncident() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(incidentFile);
        activeIncident = parseNullableEnum(IncidentProfile.class, y.getString("active-profile", ""));
        incidentActor = y.getString("actor", ""); incidentAt = y.getLong("activated-at", 0L);
        if (y.getBoolean("snapshot.present", false)) incidentSnapshot = new IncidentSnapshot(y.getBoolean("snapshot.maintenance", false),
                y.getString("snapshot.maintenance-reason", ""), y.getBoolean("snapshot.chat", true), y.getInt("snapshot.slowmode", 0),
                y.getBoolean("snapshot.command-lockdown", false)); else incidentSnapshot = null;
    }

    private void saveIncident() {
        try { YamlConfiguration y = new YamlConfiguration(); y.set("schema-version", 1); y.set("active-profile", activeIncident == null ? "" : activeIncident.name());
            y.set("actor", incidentActor); y.set("activated-at", incidentAt); y.set("snapshot.present", incidentSnapshot != null);
            if (incidentSnapshot != null) { y.set("snapshot.maintenance", incidentSnapshot.maintenance); y.set("snapshot.maintenance-reason", incidentSnapshot.maintenanceReason);
                y.set("snapshot.chat", incidentSnapshot.chat); y.set("snapshot.slowmode", incidentSnapshot.slowmode); y.set("snapshot.command-lockdown", incidentSnapshot.commandLockdown); }
            saveAtomic(y, incidentFile);
        } catch (Exception error) { plugin.getLogger().warning("Incident-Zustand konnte nicht gespeichert werden: " + error.getMessage()); }
    }

    private void trimTasks() { while (tasks.size() > 500) tasks.remove(tasks.keySet().iterator().next()); }
    private void trimJobs() { while (jobs.size() > 500) jobs.remove(jobs.keySet().iterator().next()); }
    private void trimMacros() { while (macros.size() > 200) macros.remove(macros.keySet().iterator().next()); }

    private boolean require(Player admin, String permission) {
        if (admin != null && plugin.has(admin, permission)) return true;
        if (admin != null) admin.sendMessage(plugin.prefix() + "Dafür fehlt dir " + permission + ".");
        return false;
    }

    private boolean requireOperator(Player admin, String permission) {
        if (!require(admin, permission)) return false;
        if (admin.isOp()) return true;
        admin.sendMessage(plugin.prefix() + "Diese auswirkungsstarke QoL-Funktion ist ausschließlich für echte Server-Operatoren verfügbar.");
        return false;
    }

    static boolean shouldExecuteOverdue(long executeAt, long now, long graceMillis) {
        return executeAt > 0L && executeAt <= now && now - executeAt <= Math.max(0L, graceMillis);
    }

    private static void saveAtomic(YamlConfiguration yaml, File file) throws Exception {
        File parent = file.getParentFile(); if (parent != null && !parent.exists()) parent.mkdirs();
        File temporary = new File(parent == null ? new File(".") : parent, file.getName() + ".tmp");
        yaml.save(temporary);
        try { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING); }
    }

    private static Object invoke(Object target, String method, Object... args) {
        if (target == null) return null;
        try {
            for (Method candidate : target.getClass().getMethods()) {
                if (!candidate.getName().equals(method) || candidate.getParameterCount() != args.length) continue;
                return candidate.invoke(target, args);
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, E fallback) {
        try { return Enum.valueOf(type, value == null ? "" : value.toUpperCase(Locale.ROOT)); }
        catch (Exception ignored) { return fallback; }
    }

    private static <E extends Enum<E>> E parseNullableEnum(Class<E> type, String value) {
        try { return value == null || value.isBlank() ? null : Enum.valueOf(type, value.toUpperCase(Locale.ROOT)); }
        catch (Exception ignored) { return null; }
    }

    private static UUID parseUuid(String value) { try { return value == null || value.isBlank() ? null : UUID.fromString(value); } catch (Exception ignored) { return null; } }
    private static long parseLong(String value, long fallback) { try { return Long.parseLong(value); } catch (Exception ignored) { return fallback; } }
    private static String clean(String value, int max) { String text = value == null ? "" : value.replace('\n',' ').replace('\r',' ').trim(); return text.length() <= max ? text : text.substring(0, max); }
    private static String shorten(String value, int max) { String text = value == null ? "" : value; return text.length() <= max ? text : text.substring(0, Math.max(0, max - 1)) + "…"; }
    private static String normalizeCommand(String command) { String value = command == null ? "" : command.trim(); while (value.startsWith("/")) value = value.substring(1); return value.trim(); }
    private static String safePlaceholder(String value) { return (value == null ? "" : value).replaceAll("[^A-Za-z0-9_.:-]", "_"); }
    private static String formatDate(long at) { return at <= 0L ? "Unbekannt" : new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY).format(new Date(at)); }
    private static String relativeTime(long at) { long seconds = Math.max(0L, (at - System.currentTimeMillis()) / 1000L); if (seconds < 60L) return seconds + "s"; if (seconds < 3600L) return seconds / 60L + "m"; if (seconds < 86400L) return seconds / 3600L + "h"; return seconds / 86400L + "d"; }
    private static String dueText(long at) { return at <= 0L ? "Keine" : formatDate(at); }
    private static String decimal(double value) { return value < 0D ? "N/A" : String.format(Locale.GERMANY, "%.2f", value); }

    private static Priority nextPriority(Priority priority) { Priority[] values = Priority.values(); return values[(priority.ordinal() + 1) % values.length]; }
    private static long nextDueDelay(long current) { long[] values = new long[]{0L,60L*60_000L,6L*60L*60_000L,24L*60L*60_000L,3L*24L*60L*60_000L,7L*24L*60L*60_000L}; int next=0; for(int i=0;i<values.length;i++)if(values[i]==current)next=(i+1)%values.length; return values[next]; }
    private static long nextScheduleDelay(long current) { long[] values = new long[]{30_000L,60_000L,5L*60_000L,15L*60_000L,30L*60_000L,60L*60_000L,3L*60L*60_000L,24L*60L*60_000L,3L*24L*60L*60_000L,7L*24L*60L*60_000L}; int next=0; for(int i=0;i<values.length;i++)if(values[i]==current)next=(i+1)%values.length; return values[next]; }
    static String delayLabel(long millis) { if(millis<=0L)return "Keine"; long minutes=millis/60_000L; if(minutes<1L)return Math.max(1L,millis/1000L)+" Sekunden"; if(minutes<60L)return minutes+" Minuten"; long hours=minutes/60L; if(hours<24L)return hours+" Stunden"; return hours/24L+" Tage"; }
    private static String delayLabelEnglish(long millis) { if(millis<60_000L)return Math.max(1L,millis/1000L)+" seconds"; if(millis<3_600_000L)return millis/60_000L+" minutes"; if(millis<86_400_000L)return millis/3_600_000L+" hours"; return millis/86_400_000L+" days"; }

    private static String priorityLabel(Priority priority) { return switch(priority){case LOW->"Niedrig";case NORMAL->"Normal";case HIGH->"Hoch";case URGENT->"Dringend";}; }
    private static String priorityColor(Priority priority) { return switch(priority){case LOW->"&7";case NORMAL->"&e";case HIGH->"&6";case URGENT->"&c";}; }
    private static String taskStatusLabel(TaskStatus status) { return switch(status){case OPEN->"Offen";case CLAIMED->"Übernommen";case DONE->"Erledigt";}; }
    private static Material taskMaterial(StaffTask task) { if(task.status==TaskStatus.DONE)return Material.LIME_DYE; return task.priority==Priority.URGENT?Material.REDSTONE_BLOCK:task.priority==Priority.HIGH?Material.ORANGE_DYE:Material.WRITABLE_BOOK; }
    private static String taskColor(StaffTask task) { return task.status==TaskStatus.DONE?"&a":task.priority==Priority.URGENT?"&c":task.priority==Priority.HIGH?"&6":"&e"; }

    static String jobActionLabel(JobAction action) { return switch(action){case ANNOUNCEMENT->"Server-Ankündigung";case RESTART->"Sicherer Server-Neustart";case MAINTENANCE_ON->"Wartung aktivieren";case MAINTENANCE_OFF->"Wartung deaktivieren";case CHAT_ON->"Globalen Chat aktivieren";case CHAT_OFF->"Globalen Chat deaktivieren";case LOCKDOWN_ON->"Befehls-Lockdown aktivieren";case LOCKDOWN_OFF->"Befehls-Lockdown deaktivieren";case SAVE_ALL->"Alle Welten speichern";}; }
    private static String jobActionEnglish(JobAction action) { return switch(action){case ANNOUNCEMENT->"Server announcement";case RESTART->"Safe server restart";case MAINTENANCE_ON->"Enable maintenance";case MAINTENANCE_OFF->"Disable maintenance";case CHAT_ON->"Enable global chat";case CHAT_OFF->"Disable global chat";case LOCKDOWN_ON->"Enable command lockdown";case LOCKDOWN_OFF->"Disable command lockdown";case SAVE_ALL->"Save all worlds";}; }
    private static String jobStatusLabel(JobStatus status) { return switch(status){case PENDING->"Ausstehend";case EXECUTED->"Ausgeführt";case CANCELLED->"Abgebrochen";case MISSED->"Übersprungen";case FAILED->"Fehlgeschlagen";}; }
    private static String jobColor(JobStatus status) { return status==JobStatus.PENDING?"&e":status==JobStatus.EXECUTED?"&a":status==JobStatus.FAILED?"&c":"&7"; }
    private static Material jobMaterial(JobAction action) { return switch(action){case ANNOUNCEMENT->Material.WRITABLE_BOOK;case RESTART->Material.REDSTONE_BLOCK;case MAINTENANCE_ON,MAINTENANCE_OFF->Material.IRON_DOOR;case CHAT_ON,CHAT_OFF->Material.PAPER;case LOCKDOWN_ON,LOCKDOWN_OFF->Material.IRON_BARS;case SAVE_ALL->Material.CHEST;}; }

    static String incidentLabel(IncidentProfile profile) { if(profile==null)return "Keines"; return switch(profile){case CHAT_CONTROL->"Chat-Kontrolle";case CHAT_FREEZE->"Chat-Freeze";case SECURITY_LOCKDOWN->"Sicherheits-Lockdown";case MAINTENANCE->"Geplante Wartung";case RESTORE->"Wiederherstellung";}; }
    private static String incidentEnglish(IncidentProfile profile) { return switch(profile){case CHAT_CONTROL->"Chat control";case CHAT_FREEZE->"Chat freeze";case SECURITY_LOCKDOWN->"Security lockdown";case MAINTENANCE->"Scheduled maintenance";case RESTORE->"Restore";}; }
    private static String incidentConfirmation(IncidentProfile profile) { return profile==IncidentProfile.RESTORE?"Ursprünglichen Wartungs-, Chat-, Slowmode- und Lockdown-Zustand exakt wiederherstellen?":"Incident-Profil '"+incidentLabel(profile)+"' aktivieren? Bestehende Zustände werden beim ersten Profil gesichert."; }
    private static JobAction safeJobAction(int ordinal) { JobAction[] values=JobAction.values(); return ordinal>=0&&ordinal<values.length?values[ordinal]:JobAction.SAVE_ALL; }
    private static IncidentProfile safeIncident(int ordinal) { IncidentProfile[] values=IncidentProfile.values(); return ordinal>=0&&ordinal<values.length?values[ordinal]:IncidentProfile.RESTORE; }

    static String healthLevelLabel(HealthLevel level) { return level==HealthLevel.CRITICAL?"Kritisch":level==HealthLevel.WARNING?"Warnung":"Gesund"; }
    private static Material levelMaterial(HealthLevel level) { return level==HealthLevel.CRITICAL?Material.RED_DYE:level==HealthLevel.WARNING?Material.ORANGE_DYE:Material.LIME_DYE; }
    private static String levelColor(HealthLevel level) { return level==HealthLevel.CRITICAL?"&c":level==HealthLevel.WARNING?"&6":"&a"; }
    private static String levelSection(HealthLevel level) { return level==HealthLevel.CRITICAL?"§c":level==HealthLevel.WARNING?"§6":"§a"; }
    private static String healthShort(HealthSnapshot snapshot) { return healthLevelLabel(snapshot.level)+" • "+decimal(snapshot.tps)+" TPS • "+snapshot.memoryPercent+"% RAM"; }
    private static String healthDetails(HealthSnapshot snapshot) { return decimal(snapshot.tps)+" TPS • "+decimal(snapshot.mspt)+" MSPT • "+snapshot.memoryPercent+"% RAM • "+snapshot.loadedChunks+" Chunks"; }
    private static String categoryLabel(String category) {
        if (category == null) return "System";
        return switch (category.toUpperCase(Locale.ROOT)) {
            case "TASK" -> "Aufgabe";
            case "SCHEDULER" -> "Planer";
            case "INCIDENT" -> "Incident";
            case "HEALTH" -> "Health";
            case "MACRO" -> "Makro";
            case "BULK" -> "Massenaktion";
            default -> "System";
        };
    }
}
