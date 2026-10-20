package eu.simplexsmp.adminsuite;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight, local activity classifier for AFK display and Simplex Point eligibility.
 *
 * <p>This is deliberately not a black-box punishment model. It combines several bounded,
 * explainable signals over a sliding time window. No single channel, movement or click can
 * punish a player. Suspicious repetition merely pauses point progress until varied, credible
 * play resumes.</p>
 */
final class ActivityIntelligence implements Listener {
    private static final long WINDOW_MILLIS = 5L * 60_000L;
    private static final long RECENT_MILLIS = 90_000L;
    private static final int MAX_EVENTS = 420;
    private static final Set<String> PRODUCTIVE_ACTIONS=Set.of("break","place","combat","fish","bucket","entity");
    private static final String MENU_TITLE = "§0Simplex Aktivitätsanalyse";

    /** One notice per phase; brief score changes must not produce repeated pause/resume messages. */
    static final class WarningGate {
        record Notice(boolean start,boolean warn,boolean resume) { }
        private boolean suspiciousLatched,phaseWarned;
        private int consecutiveSuspicious,recovery;
        private long lastPlayerWarningAt=Long.MIN_VALUE;
        Notice advance(boolean suspicious,boolean eligible,int required,long now,long cooldown){
            if(suspicious){
                recovery=0;consecutiveSuspicious=Math.min(required,consecutiveSuspicious+1);
                if(consecutiveSuspicious>=required&&!suspiciousLatched){
                    suspiciousLatched=true;
                    phaseWarned=lastPlayerWarningAt==Long.MIN_VALUE||now-lastPlayerWarningAt>=cooldown;
                    if(phaseWarned)lastPlayerWarningAt=now;
                    return new Notice(true,phaseWarned,false);
                }
            }else{
                consecutiveSuspicious=0;
                recovery=eligible?Math.min(3,recovery+1):0;
                if(suspiciousLatched&&recovery>=3){
                    suspiciousLatched=false;boolean notify=phaseWarned;phaseWarned=false;
                    return new Notice(false,false,notify);
                }
            }
            return new Notice(false,false,false);
        }
    }

    private final SimplexAdminSuite plugin;
    private final Map<UUID, State> states = new ConcurrentHashMap<>();
    private final Map<UUID, List<UUID>> menuRows = new ConcurrentHashMap<>();
    private final File diagnosticsFile;
    private BukkitTask task;

    ActivityIntelligence(SimplexAdminSuite plugin) {
        this.plugin = plugin;
        this.diagnosticsFile = new File(plugin.getDataFolder(), "activity-intelligence.yml");
    }

    void start() {
        stopTask();
        for (Player player : Bukkit.getOnlinePlayers()) join(player);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::checkpoint, 200L, 200L);
    }

    void reload() {
        stopTask();
        start();
    }

    void stop() {
        stopTask();
        checkpoint();
        states.clear();
        menuRows.clear();
    }

    private void stopTask() {
        if (task != null) try { task.cancel(); } catch (Exception ignored) { }
        task = null;
    }

    void join(Player player) {
        if (player == null) return;
        long now = System.currentTimeMillis();
        State state = new State(now);
        state.lastLocation = player.getLocation();
        states.put(player.getUniqueId(), state);
    }

    void quit(Player player) {
        if (player == null) return;
        states.remove(player.getUniqueId());
        menuRows.remove(player.getUniqueId());
    }

    boolean credibleForAfk(Player player) {
        if (player == null || !enabled()) return true;
        State state = states.get(player.getUniqueId());
        long now = System.currentTimeMillis();
        if (state != null) synchronized (state) {
            long chatOverride = Math.max(5_000L, plugin.getConfig().getLong("activity-intelligence.chat-clears-afk-seconds", 30L) * 1000L);
            if (state.lastChatAt > 0L && now - state.lastChatAt <= chatOverride) return true;
        }
        Evaluation evaluation = evaluate(player.getUniqueId(), now);
        return evaluation.visualActive;
    }

    long lastCredibleAt(Player player) {
        State state = player == null ? null : states.get(player.getUniqueId());
        return state == null ? System.currentTimeMillis() : Math.max(state.joinedAt, state.lastCredibleAt);
    }

    boolean qualifiesForPoints(Player player) {
        if (player == null || !enabled()) return true;
        Evaluation evaluation = evaluate(player.getUniqueId(), System.currentTimeMillis());
        return evaluation.pointEligible;
    }

    String status(Player player) {
        if (player == null) return "Keine Daten";
        Evaluation e = evaluate(player.getUniqueId(), System.currentTimeMillis());
        return e.score + "/100 • " + (e.pointEligible ? "Punkte aktiv" : "Punkte pausiert") + " • " + e.reason;
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("activity-intelligence.enabled", true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) { join(event.getPlayer()); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) { quit(event.getPlayer()); }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from == null || to == null) return;
        recordMovement(event.getPlayer(), from, to);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        recordAction(event.getPlayer(), "break", blockFingerprint(event.getBlock()), 8);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        recordAction(event.getPlayer(), "place", blockFingerprint(event.getBlock()), 8);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        String block = blockFingerprint(event.getClickedBlock());
        String item = event.getItem() == null || event.getItem().getType() == null ? "hand" : event.getItem().getType().name();
        recordAction(event.getPlayer(), "interact", block + ":" + item, 4);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        String entity = event.getRightClicked() == null || event.getRightClicked().getType() == null ? "unknown" : event.getRightClicked().getType().name();
        recordAction(event.getPlayer(), "entity", entity, 6);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        recordAction(event.getPlayer(), "chat", normalizedFingerprint(event.getMessage()), 12);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String command = event.getMessage() == null ? "" : event.getMessage().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        recordAction(event.getPlayer(), "command", command, 3);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player) {
            String target = event.getEntity() == null || event.getEntity().getType() == null ? "unknown" : event.getEntity().getType().name();
            recordAction((Player) event.getDamager(), "combat", target, 10);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) { recordAction(event.getPlayer(), "drop", "item", 2); }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player) recordAction((Player) event.getEntity(), "pickup", "item", 2);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) { recordAction(event.getPlayer(), "fish", "cast", 6); }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) { recordAction(event.getPlayer(), "bucket", "empty", 6); }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) { recordAction(event.getPlayer(), "bucket", "fill", 6); }

    private void recordMovement(Player player, Location from, Location to) {
        if (player == null || from.getWorld() == null || to.getWorld() == null) return;
        if (!from.getWorld().getName().equals(to.getWorld().getName())) {
            State state = state(player);
            synchronized (state) { state.lastLocation = to; state.lastInputAt = System.currentTimeMillis(); }
            return;
        }
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (distance < 0.035D || distance > 24.0D) return;
        long now = System.currentTimeMillis();
        State state = state(player);
        synchronized (state) {
            prune(state, now);
            int direction = horizontal < 0.03D ? -1 : directionBin(dx, dz);
            state.events.addLast(ActivityEvent.movement(now, distance, horizontal, direction,
                    to.getWorld().getName(), to.getBlockX() >> 4, to.getBlockZ() >> 4,
                    to.getX(), to.getY(), to.getZ()));
            state.lastInputAt = now;
            state.lastLocation = to;
            if (state.events.size() > MAX_EVENTS) state.events.removeFirst();
            Evaluation evaluation = evaluateLocked(state, now);
            if (evaluation.visualActive) state.lastCredibleAt = now;
        }
    }

    private void recordAction(Player player, String category, String fingerprint, int weight) {
        if (player == null) return;
        long now = System.currentTimeMillis();
        State state = state(player);
        synchronized (state) {
            prune(state, now);
            state.events.addLast(ActivityEvent.action(now, category, fingerprint, weight));
            state.lastInputAt = now;
            if ("chat".equals(category)) {
                state.lastChatAt = now;
                state.lastCredibleAt = now;
            }
            if (state.events.size() > MAX_EVENTS) state.events.removeFirst();
            Evaluation evaluation = evaluateLocked(state, now);
            if (evaluation.visualActive) state.lastCredibleAt = now;
        }
    }

    private State state(Player player) {
        return states.computeIfAbsent(player.getUniqueId(), ignored -> new State(System.currentTimeMillis()));
    }

    private Evaluation evaluate(UUID uuid, long now) {
        State state = states.get(uuid);
        if (state == null) return Evaluation.inactive("Noch keine Aktivitätsdaten");
        synchronized (state) {
            prune(state, now);
            Evaluation evaluation = evaluateLocked(state, now);
            state.lastEvaluation = evaluation;
            return evaluation;
        }
    }

    private Evaluation evaluateLocked(State state, long now) {
        if (state.events.isEmpty() || now - state.lastInputAt > RECENT_MILLIS) return Evaluation.inactive("Keine aktuelle Eingabe");

        double path = 0.0D;
        double horizontalPath = 0.0D;
        int movementCount = 0;
        int directionChanges = 0;
        int previousDirection = -2;
        int[] directions = new int[8];
        Set<String> chunks = new HashSet<>();
        Set<String> categories = new HashSet<>();
        Set<String> fingerprints = new HashSet<>();
        Map<String, Integer> repetitions = new HashMap<>();
        int actionCount = 0;
        int weightedActions = 0;
        int productiveRecentActions = 0;
        ActivityEvent firstMove = null;
        ActivityEvent lastMove = null;

        for (ActivityEvent event : state.events) {
            if (event.movement) {
                movementCount++;
                path += event.distance;
                horizontalPath += event.horizontal;
                chunks.add(event.world + ":" + event.chunkX + ":" + event.chunkZ);
                if (event.direction >= 0) {
                    directions[event.direction]++;
                    if (previousDirection >= 0 && previousDirection != event.direction) directionChanges++;
                    previousDirection = event.direction;
                }
                if (firstMove == null) firstMove = event;
                lastMove = event;
            } else {
                actionCount++;
                weightedActions += event.weight;
                if(event.timestamp>=now-30_000L&&PRODUCTIVE_ACTIONS.contains(event.category))productiveRecentActions++;
                categories.add(event.category);
                String key = event.category + ":" + event.fingerprint;
                fingerprints.add(key);
                repetitions.put(key, repetitions.getOrDefault(key, 0) + 1);
            }
        }

        double displacement = 0.0D;
        if (firstMove != null && lastMove != null && firstMove.world.equals(lastMove.world)) {
            double dx = lastMove.x - firstMove.x;
            double dy = lastMove.y - firstMove.y;
            double dz = lastMove.z - firstMove.z;
            displacement = Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        int dominantDirection = 0;
        for (int count : directions) dominantDirection = Math.max(dominantDirection, count);
        double dominantRatio = movementCount == 0 ? 0.0D : dominantDirection / (double) movementCount;
        int maximumRepeat = 0;
        for (int count : repetitions.values()) maximumRepeat = Math.max(maximumRepeat, count);
        double repeatedRatio = actionCount == 0 ? 0.0D : maximumRepeat / (double) actionCount;
        double loopRatio = displacement < 1.0D ? path : path / Math.max(1.0D, displacement);
        long movementSpan = firstMove == null || lastMove == null ? 0L : Math.max(0L, lastMove.timestamp - firstMove.timestamp);

        int score = 0;
        score += (int) Math.min(30.0D, horizontalPath * 0.75D);
        score += (int) Math.min(15.0D, displacement * 0.8D);
        score += Math.min(12, Math.max(0, chunks.size() - 1) * 4);
        score += Math.min(12, directionChanges / 3);
        score += Math.min(12, categories.size() * 3);
        score += Math.min(18, fingerprints.size() * 2);
        score += Math.min(16, weightedActions / 5);

        List<String> concerns = new ArrayList<>();
        boolean productiveWork=productiveRecentActions>=3;
        if (movementCount >= 20 && horizontalPath < 4.0D && !productiveWork) {
            score -= 30;
            concerns.add("Eingaben ohne Wegstrecke");
        }
        if (movementCount >= 35 && dominantRatio >= 0.93D && displacement < 10.0D && actionCount < 4 && !productiveWork) {
            score -= 24;
            concerns.add("nahezu identische Bewegungsrichtung");
        }
        if (movementCount >= 100 && movementSpan >= 20_000L && dominantRatio >= 0.97D
                && directionChanges < 2 && actionCount < 2 && !productiveWork) {
            score -= 38;
            concerns.add("anhaltender einzelner Bewegungsinput");
        }
        if (path >= 18.0D && loopRatio >= 5.0D && categories.size() < 2 && !productiveWork) {
            score -= 22;
            concerns.add("wiederholte kleine Route");
        }
        // Repeatedly mining/building the same material and using menus/commands are
        // ordinary play. Their repetition alone is not evidence of an AFK machine.
        boolean repeatedIdleInput=categories.contains("interact")&&!productiveWork;
        if (actionCount >= 8 && repeatedRatio >= 0.86D && repeatedIdleInput) {
            score -= 28;
            concerns.add("fast identische Aktionen");
        }
        if (actionCount >= 12 && fingerprints.size() <= 2 && repeatedIdleInput) {
            score -= 16;
            concerns.add("zu geringe Aktionsvielfalt");
        }
        score = Math.max(0, Math.min(100, score));

        int visualThreshold = Math.max(15, Math.min(80, plugin.getConfig().getInt("activity-intelligence.visual-active-score", 25)));
        int pointThreshold = Math.max(25, Math.min(90, plugin.getConfig().getInt("activity-intelligence.points-active-score", 48)));
        boolean strongRecentAction = weightedActions >= 12 && (categories.size() >= 2 || fingerprints.size() >= 3);
        boolean meaningfulTravel = displacement >= 5.0D || horizontalPath >= 10.0D && loopRatio < 4.0D;
        boolean visual = score >= visualThreshold && (strongRecentAction || meaningfulTravel || categories.contains("chat"));
        boolean points = score >= pointThreshold && concerns.size() < 2
                && (displacement >= 8.0D || chunks.size() >= 2 || categories.size() >= 2 || fingerprints.size() >= 4);
        String reason;
        if (points) reason = "abwechslungsreiches, plausibles Spielen";
        else if (!concerns.isEmpty()) reason = String.join(", ", concerns);
        else if (score < pointThreshold) reason = "noch zu wenig belastbare Aktivität";
        else reason = "mehr unterschiedliche Aktionen oder Wegstrecke nötig";
        boolean suspicious = !concerns.isEmpty() && (movementCount >= 20 || actionCount >= 8);
        return new Evaluation(score, visual, points, suspicious, reason, path, displacement, chunks.size(), categories.size(), fingerprints.size());
    }

    private void prune(State state, long now) {
        long cutoff = now - Math.max(60_000L, plugin.getConfig().getLong("activity-intelligence.window-seconds", 300L) * 1000L);
        while (!state.events.isEmpty() && state.events.peekFirst().timestamp < cutoff) state.events.removeFirst();
    }

    private void checkpoint() {
        if (!enabled()) return;
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("schema-version", 1);
            yaml.set("updated-at", System.currentTimeMillis());
            long now = System.currentTimeMillis();
            for (Player player : Bukkit.getOnlinePlayers()) {
                Evaluation e = evaluate(player.getUniqueId(), now);
                State state = states.get(player.getUniqueId());
                if (state != null) synchronized (state) {
                    int required = Math.max(1, plugin.getConfig().getInt("activity-intelligence.suspicious-detection.minimum-consecutive-checkpoints", 3));
                    long cooldown = Math.max(60_000L, plugin.getConfig().getLong("activity-intelligence.suspicious-detection.player-warning-cooldown-minutes", 10L) * 60_000L);
                    WarningGate.Notice notice=state.warningGate.advance(
                            plugin.getConfig().getBoolean("activity-intelligence.suspicious-detection.enabled",true)&&e.suspicious&&!e.pointEligible,
                            e.pointEligible,required,now,cooldown);
                    if(notice.start())plugin.handleSuspiciousActivity(player,e,false,notice.warn());
                    if(notice.resume())player.sendMessage(plugin.prefix()+plugin.publicMessage(player,
                            "activity-intelligence.suspicious-detection.resumed-message",
                            "Active gameplay was detected again. Simplex Point progress has resumed."));
                }
                String base = "online." + player.getUniqueId();
                yaml.set(base + ".name", player.getName());
                yaml.set(base + ".score", e.score);
                yaml.set(base + ".point-eligible", e.pointEligible);
                yaml.set(base + ".suspicious", e.suspicious);
                yaml.set(base + ".reason", e.reason);
            }
            SimplexAdminSuite.saveAtomic(yaml, diagnosticsFile);
        } catch (Exception error) {
            plugin.getLogger().warning("Aktivitätsdiagnose konnte nicht gespeichert werden: " + error.getMessage());
        }
    }

    void openAdminMenu(Player admin) {
        if (!plugin.adminMenuAllowed(admin, "activityintelligence.openadminmenu")) return;
        if (admin == null) return;
        if (plugin.isBedrockClient(admin)) {
            if (!openBedrockAdmin(admin)) plugin.menuNotice(admin,plugin.prefix() + "Das native Bedrock-Aktivitätsmenü konnte nicht gesendet werden. Bitte prüfe /sas form.");
            return;
        }
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        players.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        List<UUID> rows = new ArrayList<>();
        Inventory inventory = LiveMenus.create(null, 54, MENU_TITLE);
        fill(inventory);
        int slot = 0;
        for (Player player : players) {
            if (slot >= 45) break;
            rows.add(player.getUniqueId());
            Evaluation e = evaluate(player.getUniqueId(), System.currentTimeMillis());
            Material icon = e.pointEligible ? Material.LIME_DYE : e.visualActive ? Material.YELLOW_DYE : Material.RED_DYE;
            inventory.setItem(slot++, item(icon, "&f" + player.getName(),
                    "&7Aktivitätswert: &e" + e.score + "/100",
                    "&7Punkte: " + (e.pointEligible ? "&aaktiv" : "&cpausiert"),
                    "&7Bewertung: &f" + e.reason,
                    "&7Weg/Versatz: &f" + oneDecimal(e.path) + " / " + oneDecimal(e.displacement) + " Blöcke",
                    "&7Chunks/Aktionsarten: &f" + e.chunks + " / " + e.categories));
        }
        inventory.setItem(45, item(enabled() ? Material.LIME_DYE : Material.RED_DYE,
                enabled() ? "&aAnalyse aktiviert" : "&cAnalyse deaktiviert", "&7Klicken zum Umschalten"));
        inventory.setItem(47, item(Material.COMPARATOR, "&eAFK-Schwelle", "&7Aktuell: &f" + plugin.getConfig().getInt("activity-intelligence.visual-active-score", 25), "&7Linksklick +5, Rechtsklick -5"));
        inventory.setItem(48, item(Material.EMERALD, "&bPunkte-Schwelle", "&7Aktuell: &f" + plugin.getConfig().getInt("activity-intelligence.points-active-score", 48), "&7Linksklick +5, Rechtsklick -5"));
        inventory.setItem(49, item(Material.BARRIER, "&cZum Startmenü", "&7Zurück zur Simplex Admin Suite."));
        inventory.setItem(53, item(Material.CLOCK, "&eNeu bewerten", "&7Alle Fenster live aktualisieren"));
        menuRows.put(admin.getUniqueId(), rows);
        LiveMenus.show(admin,inventory);
    }

    private boolean openBedrockAdmin(Player admin) {
        if (!plugin.adminMenuAllowed(admin, "activityintelligence.openbedrockadmin")) return true;
        try {
            if (!plugin.isBedrockClient(admin)) return false;
            List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
            players.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
            StringBuilder content = new StringBuilder("§7Lokale, erklärbare Mehrsignalwertung. Wiederholte Eingaben pausieren nur die Punkte; es erfolgt keine automatische Strafe.\n\n");
            for (Player player : players) {
                Evaluation e = evaluate(player.getUniqueId(), System.currentTimeMillis());
                content.append(e.pointEligible ? "§a" : e.visualActive ? "§e" : "§c").append(player.getName()).append(" §f")
                        .append(e.score).append("/100 §8• §7").append(e.reason).append('\n');
            }
            SimplexAdminSuite.SimpleForm.Builder form = SimplexAdminSuite.SimpleForm.builder().title("Aktivitätsintelligenz").content(content.toString());
            form.button("§eSchwellen & Status bearbeiten", response -> plugin.runAdminTask( () -> openBedrockSettings(admin)));
            form.button("§aLive neu bewerten", response -> plugin.runAdminTask( () -> openAdminMenu(admin)));
            form.button("§cZum Startmenü", response -> plugin.runAdminTask( () -> plugin.openMainFromModule(admin)));
            return plugin.sendBedrockForm(admin, form);
        } catch (Throwable error) {
            plugin.getLogger().warning("Bedrock-Aktivitätsmenü für " + admin.getName() + " fehlgeschlagen: " + error.getClass().getSimpleName() + ": " + error.getMessage());
            return false;
        }
    }

    private void openBedrockSettings(Player admin) {
        if (!plugin.adminMenuAllowed(admin, "activityintelligence.openbedrocksettings")) return;
        try {
            if (!plugin.isBedrockClient(admin)) return;
            SimplexAdminSuite.CustomForm.Builder form = SimplexAdminSuite.CustomForm.builder().title("Aktivitätsintelligenz • Einstellungen")
                    .toggle("Analyse aktiviert", enabled())
                    .slider("AFK-Aktivitätsschwelle", 15.0F, 80.0F, 5.0F, plugin.getConfig().getInt("activity-intelligence.visual-active-score", 25))
                    .slider("Simplex-Points-Schwelle", 25.0F, 90.0F, 5.0F, plugin.getConfig().getInt("activity-intelligence.points-active-score", 48))
                    .slider("Analysefenster (Sekunden)", 60.0F, 600.0F, 30.0F, plugin.getConfig().getInt("activity-intelligence.window-seconds", 300))
                    .validResultHandler(response -> plugin.runAdminTask( () -> {
                        plugin.getConfig().set("activity-intelligence.enabled", response.asToggle(0));
                        plugin.getConfig().set("activity-intelligence.visual-active-score", Math.round(response.asSlider(1)));
                        plugin.getConfig().set("activity-intelligence.points-active-score", Math.round(response.asSlider(2)));
                        plugin.getConfig().set("activity-intelligence.window-seconds", Math.round(response.asSlider(3)));
                        plugin.saveConfig(); openAdminMenu(admin);
                    }))
                    .closedOrInvalidResultHandler(() -> plugin.runAdminTask( () -> openAdminMenu(admin)));
            if (!plugin.sendBedrockForm(admin, form)) admin.sendMessage(plugin.prefix() + "Bedrock-Einstellungen konnten nicht geöffnet werden.");
        } catch (Throwable error) {
            plugin.getLogger().warning("Bedrock-Aktivitätseinstellungen für " + admin.getName() + " fehlgeschlagen: " + error.getClass().getSimpleName() + ": " + error.getMessage());
            admin.sendMessage(plugin.prefix() + "Bedrock-Einstellungen konnten nicht geöffnet werden.");
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        plugin.asAdmin(event.getWhoClicked().getName(),()->handleAuditedInventoryClick(event));
    }
    private void handleAuditedInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player admin = (Player) event.getWhoClicked();
        if (!menuRows.containsKey(admin.getUniqueId())) return;
        if (!MENU_TITLE.equals(inventoryTitle(event))) {
            menuRows.remove(admin.getUniqueId());
            return;
        }
        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot == 45) {
            plugin.getConfig().set("activity-intelligence.enabled", !enabled());
            plugin.saveConfig();
            openAdminMenu(admin);
        } else if (slot == 47 || slot == 48) {
            String path = slot == 47 ? "activity-intelligence.visual-active-score" : "activity-intelligence.points-active-score";
            int fallback = slot == 47 ? 25 : 48;
            int value = plugin.getConfig().getInt(path, fallback) + (event.isRightClick() ? -5 : 5);
            plugin.getConfig().set(path, Math.max(15, Math.min(90, value)));
            plugin.saveConfig();
            openAdminMenu(admin);
        } else if (slot == 49) {
            menuRows.remove(admin.getUniqueId());
            plugin.openMainFromModule(admin);
        } else if (slot == 53) openAdminMenu(admin);
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (MENU_TITLE.equals(inventoryTitle(event.getView()))) event.setCancelled(true);
    }

    private static String blockFingerprint(Block block) {
        return block == null || block.getType() == null ? "air" : block.getType().name().toLowerCase(Locale.ROOT);
    }

    private static String normalizedFingerprint(String value) {
        if (value == null) return "";
        String normalized = value.toLowerCase(Locale.ROOT).replaceAll("\\d+", "#").replaceAll("\\s+", " ").trim();
        return normalized.length() <= 64 ? normalized : normalized.substring(0, 64);
    }

    private static int directionBin(double dx, double dz) {
        double angle = Math.atan2(dz, dx);
        int bin = (int) Math.floor((angle + Math.PI) / (Math.PI / 4.0D));
        return Math.floorMod(bin, 8);
    }

    private static String oneDecimal(double value) { return String.format(Locale.GERMANY, "%.1f", value); }

    private static ItemStack item(Material material, String name, String... lore) {
        ItemStack stack = new ItemStack(MenuStyle.actionMaterial(name,material));
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(MenuStyle.javaText(name));
            List<String> lines = new ArrayList<>();
            for (String line : lore) lines.add(MenuStyle.javaText(line));
            meta.setLore(lines);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private static void fill(Inventory inventory) {
        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, filler);
    }

    private static String color(String value) { return ChatColor.translateAlternateColorCodes('&', value == null ? "" : value); }

    private static String inventoryTitle(InventoryClickEvent event) {
        try { return inventoryTitle(event.getView()); }
        catch (Throwable ignored) { return ""; }
    }

    private static String inventoryTitle(org.bukkit.inventory.InventoryView view) {
        try { return view == null ? "" : LiveMenus.title(view); }
        catch (Throwable ignored) { return ""; }
    }

    private static final class State {
        private final long joinedAt;
        private final Deque<ActivityEvent> events = new ArrayDeque<>();
        private long lastInputAt;
        private long lastCredibleAt;
        private long lastChatAt;
        private final WarningGate warningGate=new WarningGate();
        private Location lastLocation;
        private Evaluation lastEvaluation = Evaluation.inactive("Noch keine Aktivität");
        private State(long now) { joinedAt = now; lastInputAt = now; lastCredibleAt = now; }
    }

    private static final class ActivityEvent {
        private final long timestamp;
        private final boolean movement;
        private final double distance, horizontal, x, y, z;
        private final int direction, chunkX, chunkZ, weight;
        private final String world, category, fingerprint;

        private ActivityEvent(long timestamp, boolean movement, double distance, double horizontal, int direction,
                String world, int chunkX, int chunkZ, double x, double y, double z,
                String category, String fingerprint, int weight) {
            this.timestamp = timestamp; this.movement = movement; this.distance = distance; this.horizontal = horizontal;
            this.direction = direction; this.world = world == null ? "" : world; this.chunkX = chunkX; this.chunkZ = chunkZ;
            this.x = x; this.y = y; this.z = z; this.category = category == null ? "" : category;
            this.fingerprint = fingerprint == null ? "" : fingerprint; this.weight = weight;
        }

        private static ActivityEvent movement(long at, double distance, double horizontal, int direction,
                String world, int chunkX, int chunkZ, double x, double y, double z) {
            return new ActivityEvent(at, true, distance, horizontal, direction, world, chunkX, chunkZ, x, y, z, "", "", 0);
        }

        private static ActivityEvent action(long at, String category, String fingerprint, int weight) {
            return new ActivityEvent(at, false, 0.0D, 0.0D, -1, "", 0, 0, 0.0D, 0.0D, 0.0D, category, fingerprint, weight);
        }
    }

    static final class Evaluation {
        final int score;
        final boolean visualActive, pointEligible, suspicious;
        final String reason;
        final double path, displacement;
        final int chunks, categories, fingerprints;

        Evaluation(int score, boolean visualActive, boolean pointEligible, boolean suspicious, String reason,
                double path, double displacement, int chunks, int categories, int fingerprints) {
            this.score = score; this.visualActive = visualActive; this.pointEligible = pointEligible; this.suspicious = suspicious;
            this.reason = reason; this.path = path; this.displacement = displacement;
            this.chunks = chunks; this.categories = categories; this.fingerprints = fingerprints;
        }

        private static Evaluation inactive(String reason) { return new Evaluation(0, false, false, false, reason, 0.0D, 0.0D, 0, 0, 0); }
    }
}
