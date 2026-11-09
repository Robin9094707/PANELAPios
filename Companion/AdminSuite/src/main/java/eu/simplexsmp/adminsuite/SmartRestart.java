package eu.simplexsmp.adminsuite;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.bukkit.Bukkit;

/**
 * 10.14.0: "Smart-Neustart". Restarts at most every N hours and only while few players are online. A restart is
 * prepared with the regular DE/EN countdown, cancelled again if the server fills up during the countdown, and the
 * time of the restart is stored before it is dispatched, so two automatic restarts can never follow each other.
 */
final class SmartRestart {
    static final String FEATURE = "smart-restart-v1", VIEW = "data.operations", MANAGE = "action.restart.apply";
    private static final long MINUTE = 60_000L, HOUR = 60 * MINUTE;
    /** A freshly started server is never restarted automatically within this time. */
    private static final long BOOT_GRACE = 30 * MINUTE;
    private final SimplexAdminSuite plugin;
    private final Path file;
    private final long serverStart = System.currentTimeMillis();
    private Properties state = new Properties();
    private long conditionSince, armedAt, previousLast;
    private boolean armed;
    private String phase = "Aus";

    SmartRestart(SimplexAdminSuite plugin) throws IOException {
        this.plugin = plugin;
        file = plugin.getDataFolder().toPath().resolve("smart-restart.properties");
        if (Files.exists(file)) try (var reader = Files.newBufferedReader(file)) { state.load(reader); }
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 200, 100);
    }

    record Settings(boolean enabled, double intervalHours, int maxPlayers, int countdownSeconds, int stableMinutes, double forceAfterHours, boolean abortOnJoin) {
        static Settings of(Properties p) {
            return new Settings(Boolean.parseBoolean(p.getProperty("enabled", "false")), clamp(dbl(p, "interval-hours", 6), 1, 168),
                (int) clamp(dbl(p, "max-players", 1), 0, 500), (int) clamp(dbl(p, "countdown-seconds", 60), 10, 300),
                (int) clamp(dbl(p, "stable-minutes", 2), 0, 60), clamp(dbl(p, "force-after-hours", 0), 0, 336),
                Boolean.parseBoolean(p.getProperty("abort-on-join", "true")));
        }
        Map<String, Object> json() {
            return SimplexAdminSuite.appMap("enabled", enabled, "intervalHours", intervalHours, "maxPlayers", maxPlayers,
                "countdownSeconds", countdownSeconds, "stableMinutes", stableMinutes, "forceAfterHours", forceAfterHours, "abortOnJoin", abortOnJoin);
        }
    }
    private static double dbl(Properties p, String key, double fallback) {
        try { return Double.parseDouble(p.getProperty(key, String.valueOf(fallback))); } catch (NumberFormatException invalid) { return fallback; }
    }
    static double clamp(double value, double min, double max) { return Double.isFinite(value) ? Math.max(min, Math.min(max, value)) : min; }

    Settings settings() { return Settings.of(state); }
    private long lastRestart() { return (long) dbl(state, "last-restart", 0); }

    /** Earliest moment for the next automatic restart: interval after the later of server start and last automatic restart. */
    static long dueAt(long serverStart, long lastRestart, double intervalHours) {
        return Math.max(serverStart + BOOT_GRACE, Math.max(serverStart, lastRestart) + (long) (intervalHours * HOUR));
    }

    /** Pure decision used by the tick and the tests. */
    static String decide(Settings s, long now, long due, int online, long conditionSince, long serverStart) {
        if (!s.enabled) return "off";
        if (now < due) return "interval";
        boolean forced = s.forceAfterHours > 0 && now >= due + (long) (s.forceAfterHours * HOUR);
        if (online > s.maxPlayers && !forced) return "players";
        if (!forced && s.stableMinutes > 0 && (conditionSince == 0 || now - conditionSince < s.stableMinutes * MINUTE)) return "stable";
        return forced ? "force" : "restart";
    }

    private void save(Properties next) throws IOException {
        AtomicState.save(file, next, "Simplex smart restart");
        state = next;
    }

    private void tick() {
        Settings s = settings();
        long now = System.currentTimeMillis();
        int online = Bukkit.getOnlinePlayers().size();
        if (armed) {
            if (!plugin.restartCountdownActive()) { armed = false; phase = "Neustart läuft"; return; }
            if (s.abortOnJoin && online > s.maxPlayers && now - armedAt > 3000 && !"force".equals(state.getProperty("last-mode"))) {
                plugin.cancelCalendarRestart("Smart-Neustart");
                armed = false;
                conditionSince = 0;
                Properties next = (Properties) state.clone();
                next.setProperty("last-restart", Long.toString(previousLast));
                try { save(next); } catch (IOException ignored) { }
                history("Abgebrochen: " + online + " Spieler online", online);
                phase = "Abgebrochen, wartet auf weniger Spieler";
            }
            return;
        }
        long due = dueAt(serverStart, lastRestart(), s.intervalHours);
        if (online <= s.maxPlayers) { if (conditionSince == 0) conditionSince = now; } else conditionSince = 0;
        String decision = decide(s, now, due, online, conditionSince, serverStart);
        phase = switch (decision) {
            case "off" -> "Aus";
            case "interval" -> "Wartet auf Intervall";
            case "players" -> "Wartet auf höchstens " + s.maxPlayers + " Spieler";
            case "stable" -> "Bedingung erfüllt, kurze Wartezeit";
            default -> "Neustart wird vorbereitet";
        };
        if (!decision.equals("restart") && !decision.equals("force")) return;
        if (plugin.restartCountdownActive()) { phase = "Anderer Countdown läuft"; return; }
        Properties next = (Properties) state.clone();
        previousLast = lastRestart();
        // Persist before dispatching: a crash or reload can never trigger the same restart twice.
        next.setProperty("last-restart", Long.toString(now));
        next.setProperty("last-mode", decision);
        try { save(next); } catch (IOException error) { plugin.getLogger().warning("Smart-Neustart: Zustand nicht gespeichert, kein Neustart."); return; }
        armed = true;
        armedAt = now;
        plugin.calendarRestart("Smart-Neustart", s.countdownSeconds);
        history((decision.equals("force") ? "Erzwungen" : "Geplant") + " bei " + online + " Spielern online", online);
        if (plugin.appEvents != null) plugin.appEvents.record("game-day", null, "Smart-Neustart eingeleitet",
            online + " Spieler online · Countdown " + s.countdownSeconds + " s", false, "active");
    }

    private void history(String text, int online) {
        List<Object> rows = new ArrayList<>(history());
        rows.add(0, SimplexAdminSuite.appMap("at", System.currentTimeMillis(), "text", text, "online", online));
        Properties next = (Properties) state.clone();
        next.setProperty("history", DiscordBotManager.Json.stringify(rows.subList(0, Math.min(20, rows.size()))));
        try { save(next); } catch (IOException ignored) { }
    }

    private List<?> history() {
        try { return DiscordBotManager.Json.parse(state.getProperty("history", "[]")) instanceof List<?> list ? list : List.of(); }
        catch (RuntimeException broken) { return List.of(); }
    }

    Map<String, Object> data(boolean manage) {
        Settings s = settings();
        long due = dueAt(serverStart, lastRestart(), s.intervalHours);
        return SimplexAdminSuite.appMap("settings", s.json(), "manage", manage, "phase", phase, "online", Bukkit.getOnlinePlayers().size(),
            "dueAt", due, "serverStart", serverStart, "lastRestart", lastRestart(), "conditionSince", conditionSince,
            "countdown", plugin.restartCountdownActive(), "armed", armed, "history", history(), "at", System.currentTimeMillis());
    }

    private Object apply(String actor, Map<String, Object> input) throws IOException {
        Properties next = (Properties) state.clone();
        if (input.get("enabled") instanceof Boolean value) next.setProperty("enabled", value.toString());
        if (input.get("abortOnJoin") instanceof Boolean value) next.setProperty("abort-on-join", value.toString());
        Map<String, String> numbers = Map.of("intervalHours", "interval-hours", "maxPlayers", "max-players", "countdownSeconds", "countdown-seconds",
            "stableMinutes", "stable-minutes", "forceAfterHours", "force-after-hours");
        for (var entry : numbers.entrySet()) if (input.get(entry.getKey()) instanceof Number number) next.setProperty(entry.getValue(), String.valueOf(number.doubleValue()));
        Settings checked = Settings.of(next);
        if (checked.forceAfterHours > 0 && checked.forceAfterHours < 1) throw new IllegalArgumentException("Erzwingen frühestens nach 1 Stunde");
        save(next);
        plugin.suiteAudit(actor, "Smart-Neustart", checked.enabled ? "an · alle " + checked.intervalHours + " Std · max " + checked.maxPlayers + " Spieler" : "aus");
        return data(true);
    }

    boolean route(AppApiManager api, HttpExchange x, AppApiSecurity.Key key, String path, String method, long rev) throws Exception {
        if (!path.equals("/v1/smart-restart")) return false;
        api.requirePermission(key, VIEW);
        boolean manage = api.allows(key, MANAGE) && AppApiSecurity.permitted(key, "manage");
        if (method.equals("GET")) AppApiManager.send(x, 200, api.onMain(rev, () -> data(manage)));
        else if (method.equals("POST")) {
            if (!manage) throw new AppApiManager.ApiError(403, "Neustart-Planung benötigt action.restart.apply");
            var body = AppApiManager.body(x, 4096);
            if (!Boolean.TRUE.equals(body.get("confirmed"))) throw new IllegalArgumentException("Bestätigung fehlt");
            AppApiManager.send(x, 200, api.onMain(rev, () -> apply(key.label(), body)));
        } else throw new AppApiManager.ApiError(405, "Methode nicht erlaubt");
        return true;
    }
}
