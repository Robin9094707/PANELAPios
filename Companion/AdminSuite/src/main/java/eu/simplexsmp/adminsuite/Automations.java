package eu.simplexsmp.adminsuite;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * 10.14.0: "Wenn … dann …" rules. Triggers: in-game day (once or repeating), daily clock time, interval, player
 * count above/below a limit and first joins. Actions: broadcast (DE/EN), staff notification, event status,
 * lockdown, validated console commands and a regular restart countdown. Main thread only; every run is stored
 * before its actions execute, so a crash never repeats side effects.
 */
final class Automations {
    static final String FEATURE = "automations-v1";
    private static final String VIEW = "data.server-events", MANAGE = "server-events.manage", COMMANDS = "server-events.commands";
    static final List<String> TRIGGERS = List.of("game-day", "daily", "interval", "players-above", "players-below", "first-join");
    static final List<String> ACTIONS = List.of("broadcast", "notify", "event", "lockdown", "command", "restart");
    private static final Set<String> PRIVILEGED = Set.of("command", "restart");
    private static final long MINUTE = 60_000L;
    private final SimplexAdminSuite plugin;
    private final Path file;
    private final LinkedHashMap<String, Map<String, Object>> rules = new LinkedHashMap<>();
    private final Map<String, Boolean> lastCondition = new HashMap<>();

    Automations(SimplexAdminSuite plugin) throws IOException {
        this.plugin = plugin;
        file = plugin.getDataFolder().toPath().resolve("automations.json");
        if (Files.isRegularFile(file) && DiscordBotManager.Json.parse(Files.readString(file)) instanceof List<?> list)
            for (Object row : list) { var rule = AppApiManager.object(row); if (!AiAdmin.text(rule, "id").isBlank()) rules.put(AiAdmin.text(rule, "id"), new LinkedHashMap<>(rule)); }
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 100, 100);
    }

    private void persist() {
        try {
            Path temp = Files.createTempFile(file.getParent(), "automations-", ".json");
            try {
                Files.writeString(temp, DiscordBotManager.Json.stringify(new ArrayList<>(rules.values())));
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temp); }
        } catch (IOException error) { throw new IllegalStateException("Automationen konnten nicht gespeichert werden", error); }
    }

    private static Map<String, Object> trigger(Map<String, Object> rule) { return AppApiManager.object(rule.get("trigger")); }
    private static List<Map<String, Object>> actions(Map<String, Object> rule) { return AiAdmin.objects(rule.get("actions"), 8); }

    /** Pure trigger check (also used by tests). `previous` is the last player-count condition for edge detection. */
    static boolean due(Map<String, Object> trigger, Map<String, Object> rule, long now, ZonedDateTime local, Map<String, Long> worldDays, int online, Boolean previous) {
        long last = AiAdmin.number(rule.get("lastRun"));
        long cooldown = Math.max(1, AiAdmin.number(rule.getOrDefault("cooldownMinutes", 30))) * MINUTE;
        String type = AiAdmin.text(trigger, "type");
        return switch (type) {
            case "game-day" -> {
                Long day = worldDays.get(AiAdmin.text(trigger, "world"));
                long start = AiAdmin.number(trigger.get("day")), every = AiAdmin.number(trigger.get("everyDays"));
                long done = AiAdmin.number(rule.get("lastDay"));
                if (day == null || day < start) yield false;
                if (every <= 0) yield done == 0 && last == 0;
                long current = start + (day - start) / every * every;
                yield current > done || done == 0 && last == 0;
            }
            case "daily" -> {
                String time = AiAdmin.text(trigger, "time");
                if (!time.matches("[0-2][0-9]:[0-5][0-9]")) yield false;
                LocalTime target = LocalTime.parse(time);
                String today = local.toLocalDate().toString();
                yield !today.equals(AiAdmin.text(rule, "lastDate")) && !local.toLocalTime().isBefore(target)
                    && local.toLocalTime().isBefore(target.plusMinutes(10));
            }
            case "interval" -> now - last >= Math.max(15, AiAdmin.number(trigger.get("minutes"))) * MINUTE;
            case "players-above", "players-below" -> {
                long limit = AiAdmin.number(trigger.get("count"));
                boolean now0 = type.equals("players-above") ? online >= limit : online <= limit;
                yield now0 && Boolean.FALSE.equals(previous) && now - last >= cooldown;
            }
            default -> false;
        };
    }

    private void tick() {
        if (rules.isEmpty()) return;
        long now = System.currentTimeMillis();
        ZonedDateTime local = ZonedDateTime.now(ZoneId.of("Europe/Berlin"));
        Map<String, Long> days = new HashMap<>();
        for (World world : Bukkit.getWorlds()) days.put(world.getName(), Math.floorDiv(world.getFullTime(), 24_000));
        int online = Bukkit.getOnlinePlayers().size();
        for (var rule : new ArrayList<>(rules.values())) {
            if (!Boolean.TRUE.equals(rule.get("enabled"))) continue;
            var trigger = trigger(rule);
            String type = AiAdmin.text(trigger, "type");
            Boolean previous = lastCondition.get(AiAdmin.text(rule, "id"));
            boolean fire = due(trigger, rule, now, local, days, online, previous);
            if (type.startsWith("players-")) {
                long limit = AiAdmin.number(trigger.get("count"));
                lastCondition.put(AiAdmin.text(rule, "id"), type.equals("players-above") ? online >= limit : online <= limit);
            }
            if (fire) run(rule, "", days.get(AiAdmin.text(trigger, "world")));
        }
    }

    void firstJoin(Player player) {
        for (var rule : new ArrayList<>(rules.values()))
            if (Boolean.TRUE.equals(rule.get("enabled")) && AiAdmin.text(trigger(rule), "type").equals("first-join")) run(rule, player.getName(), null);
    }

    private void run(Map<String, Object> rule, String playerName, Long worldDay) {
        String id = AiAdmin.text(rule, "id");
        var next = new LinkedHashMap<>(rule);
        next.put("lastRun", System.currentTimeMillis());
        next.put("runs", AiAdmin.number(rule.get("runs")) + 1);
        next.put("lastDate", LocalDate.now(ZoneId.of("Europe/Berlin")).toString());
        if (worldDay != null) next.put("lastDay", worldDay);
        var trigger = trigger(rule);
        if (AiAdmin.text(trigger, "type").equals("game-day") && AiAdmin.number(trigger.get("everyDays")) <= 0) next.put("enabled", false);
        rules.put(id, next);
        try { persist(); } catch (IllegalStateException failure) { plugin.getLogger().warning("Automation nicht ausgeführt: " + failure.getMessage()); rules.put(id, rule); return; }
        int failures = 0;
        for (var action : actions(rule)) {
            try { execute(action, playerName); } catch (Exception error) { failures++; plugin.getLogger().warning("Automation " + rule.get("name") + ": " + error.getMessage()); }
        }
        next.put("lastResult", failures == 0 ? "OK" : failures + " Aktion(en) fehlgeschlagen");
        try { persist(); } catch (IllegalStateException ignored) { }
        plugin.suiteAudit("Automation", AiAdmin.text(rule, "name"), failures == 0 ? "ausgeführt" : failures + " Fehler");
    }

    private void execute(Map<String, Object> action, String playerName) throws IOException {
        String value = AiAdmin.text(action, "value").replace("{player}", playerName), en = AiAdmin.text(action, "en").replace("{player}", playerName);
        switch (AiAdmin.text(action, "type")) {
            case "broadcast" -> {
                for (Player p : Bukkit.getOnlinePlayers()) p.sendMessage(plugin.prefix() + plugin.playerText(p, value, en.isBlank() ? value : en));
            }
            case "notify" -> { if (plugin.appEvents != null) plugin.appEvents.record("game-day", null, "Automation", value, false, "active"); }
            case "event" -> { if (plugin.serverEvents != null) plugin.serverEvents.automation("status", value); }
            case "lockdown" -> { if (plugin.serverEvents != null) plugin.serverEvents.automation("lockdown", value); }
            case "command" -> {
                String command = ServerEvents.validateCommand(value);
                if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)) throw new IllegalStateException("Befehl fehlgeschlagen");
            }
            case "restart" -> plugin.calendarRestart("Automation", (int) SmartRestart.clamp(AiAdmin.number(action.get("value")), 10, 300));
            default -> throw new IllegalArgumentException("Unbekannte Aktion");
        }
    }

    private static String text(Object value, int limit) {
        String text = Objects.toString(value, "").strip();
        if (text.length() > limit || text.chars().anyMatch(c -> Character.isISOControl(c))) throw new IllegalArgumentException("Text zu lang oder ungültig");
        return text;
    }

    /** Validates and normalises a rule from the app or web panel. */
    static Map<String, Object> normalize(Map<String, Object> input, boolean privileged, Set<String> worlds) {
        String name = text(input.get("name"), 80);
        if (name.isBlank()) throw new IllegalArgumentException("Name fehlt");
        var raw = AppApiManager.object(input.get("trigger"));
        String type = AiAdmin.text(raw, "type");
        if (!TRIGGERS.contains(type)) throw new IllegalArgumentException("Unbekannter Auslöser");
        Map<String, Object> trigger = new LinkedHashMap<>(Map.of("type", type));
        switch (type) {
            case "game-day" -> {
                String world = AiAdmin.text(raw, "world");
                if (!worlds.contains(world)) throw new IllegalArgumentException("Welt ist nicht geladen");
                long day = AiAdmin.number(raw.get("day")), every = AiAdmin.number(raw.get("everyDays"));
                if (day < 0 || day > 100_000_000 || every < 0 || every > 10_000) throw new IllegalArgumentException("Ingame-Tag ungültig");
                trigger.put("world", world); trigger.put("day", day); trigger.put("everyDays", every);
            }
            case "daily" -> {
                String time = AiAdmin.text(raw, "time");
                if (!time.matches("[0-2][0-9]:[0-5][0-9]")) throw new IllegalArgumentException("Uhrzeit als HH:mm");
                LocalTime.parse(time);
                trigger.put("time", time);
            }
            case "interval" -> trigger.put("minutes", (long) SmartRestart.clamp(AiAdmin.number(raw.get("minutes")), 15, 10_080));
            case "players-above", "players-below" -> trigger.put("count", (long) SmartRestart.clamp(AiAdmin.number(raw.get("count")), 0, 1000));
            default -> { }
        }
        List<Map<String, Object>> actions = new ArrayList<>();
        for (var action : AiAdmin.objects(input.get("actions"), 9)) {
            String kind = AiAdmin.text(action, "type");
            if (!ACTIONS.contains(kind)) throw new IllegalArgumentException("Unbekannte Aktion");
            if (PRIVILEGED.contains(kind) && !privileged) throw new AppApiManager.ApiError(403, "Befehle und Neustarts benötigen das Befehlsrecht des Event-Planers");
            String value = text(action.get("value"), 350), en = text(action.get("en"), 350);
            if (kind.equals("command")) value = ServerEvents.validateCommand(value);
            if (kind.equals("lockdown") && !Set.of("off", "known", "one-connection", "closed").contains(value)) throw new IllegalArgumentException("Unbekannte Lockdown-Stufe");
            if (kind.equals("restart")) value = String.valueOf((long) SmartRestart.clamp(AiAdmin.number(value), 10, 300));
            if (Set.of("broadcast", "notify").contains(kind) && value.isBlank()) throw new IllegalArgumentException("Nachricht fehlt");
            actions.add(SimplexAdminSuite.appMap("type", kind, "value", value, "en", en));
        }
        if (actions.isEmpty()) throw new IllegalArgumentException("Mindestens eine Aktion festlegen");
        if (actions.size() > 8) throw new IllegalArgumentException("Maximal 8 Aktionen");
        return SimplexAdminSuite.appMap("name", name, "trigger", trigger, "actions", actions, "enabled", !Boolean.FALSE.equals(input.get("enabled")),
            "cooldownMinutes", (long) SmartRestart.clamp(AiAdmin.number(input.getOrDefault("cooldownMinutes", 30)), 1, 10_080));
    }

    private Map<String, Object> data(boolean manage, boolean commands) {
        var worlds = Bukkit.getWorlds().stream().map(world -> SimplexAdminSuite.appMap("name", world.getName(),
            "day", Math.floorDiv(world.getFullTime(), 24_000))).toList();
        return SimplexAdminSuite.appMap("items", new ArrayList<>(rules.values()), "worlds", worlds, "manage", manage, "commands", commands,
            "triggers", TRIGGERS, "actions", ACTIONS, "online", Bukkit.getOnlinePlayers().size());
    }

    private Object apply(String actor, Map<String, Object> input, boolean commands) {
        String operation = AiAdmin.text(input, "operation"), id = AiAdmin.text(input, "id");
        var existing = id.isBlank() ? null : rules.get(id);
        if (!id.isBlank() && existing == null) throw new AppApiManager.ApiError(404, "Automation nicht gefunden");
        if (existing != null && !commands && actions(existing).stream().anyMatch(a -> PRIVILEGED.contains(AiAdmin.text(a, "type"))))
            throw new AppApiManager.ApiError(403, "Diese Automation enthält Befehle; Befehlsrecht fehlt");
        var before = new LinkedHashMap<>(rules);
        switch (operation) {
            case "save" -> {
                Set<String> worlds = new HashSet<>();
                Bukkit.getWorlds().forEach(world -> worlds.add(world.getName()));
                var rule = normalize(AppApiManager.object(input.get("rule")), commands, worlds);
                if (existing == null && rules.size() >= 60) throw new IllegalArgumentException("Maximal 60 Automationen");
                String key = existing == null ? UUID.randomUUID().toString() : id;
                rule.put("id", key);
                rule.put("runs", existing == null ? 0 : AiAdmin.number(existing.get("runs")));
                boolean gameDay = AiAdmin.text(trigger(rule), "type").equals("game-day");
                // Editing an in-game-day rule arms it again; other triggers keep their history and cooldown.
                rule.put("lastRun", existing == null || gameDay ? 0 : AiAdmin.number(existing.get("lastRun")));
                rule.put("lastDay", 0);
                rule.put("lastDate", existing == null ? "" : AiAdmin.text(existing, "lastDate"));
                rule.put("createdBy", existing == null ? PowerBot.clean(actor, 64) : AiAdmin.text(existing, "createdBy"));
                rules.put(key, rule);
            }
            case "toggle" -> { if (existing == null) throw new IllegalArgumentException("ID fehlt"); var copy = new LinkedHashMap<>(existing); copy.put("enabled", !Boolean.TRUE.equals(existing.get("enabled"))); rules.put(id, copy); }
            case "delete" -> { if (existing == null) throw new IllegalArgumentException("ID fehlt"); rules.remove(id); }
            case "run" -> { if (existing == null) throw new IllegalArgumentException("ID fehlt"); run(existing, "", null); return data(true, commands); }
            default -> throw new IllegalArgumentException("Unbekannte Aktion");
        }
        try { persist(); } catch (IllegalStateException failure) { rules.clear(); rules.putAll(before); throw failure; }
        plugin.suiteAudit(actor, "Automation · " + operation, AiAdmin.text(input, "id"));
        return data(true, commands);
    }

    boolean route(AppApiManager api, HttpExchange x, AppApiSecurity.Key key, String path, String method, long rev) throws Exception {
        if (!path.equals("/v1/automations")) return false;
        api.requirePermission(key, VIEW);
        boolean manage = api.allows(key, MANAGE) && AppApiSecurity.permitted(key, "manage");
        boolean commands = manage && key.scope().equals("full") && api.allows(key, COMMANDS);
        if (method.equals("GET")) AppApiManager.send(x, 200, api.onMain(rev, () -> data(manage, commands)));
        else if (method.equals("POST")) {
            if (!manage) throw new AppApiManager.ApiError(403, "Automationen benötigen manage-Zugriff");
            var body = AppApiManager.body(x, 16384);
            if (!Boolean.TRUE.equals(body.get("confirmed"))) throw new IllegalArgumentException("Bestätigung fehlt");
            AppApiManager.send(x, 200, api.onMain(rev, () -> apply(key.label(), body, commands && api.allows(key, COMMANDS))));
        } else throw new AppApiManager.ApiError(405, "Methode nicht erlaubt");
        return true;
    }
}
