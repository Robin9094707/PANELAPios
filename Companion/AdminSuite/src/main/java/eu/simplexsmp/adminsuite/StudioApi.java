package eu.simplexsmp.adminsuite;

import com.sun.net.httpserver.HttpExchange;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 10.3.0 app/web routes: world trimmer, Discord status designer, scoreboard designer and the player dossier.
 * Every right used here is new and deliberately not part of the support/moderator/admin role templates; only
 * full access ("*") or an explicit grant enables it.
 */
final class StudioApi {
    static final String TRIM_VIEW = "data.world-trim", TRIM_RUN = "world-trim.run";
    static final String STATUS_VIEW = "data.discord-status", STATUS_EDIT = "discord-status.edit";
    static final String BOARD_VIEW = "data.scoreboard", BOARD_EDIT = "scoreboard.edit";

    private final SimplexAdminSuite plugin;

    StudioApi(SimplexAdminSuite plugin) { this.plugin = plugin; }

    boolean route(AppApiManager api, HttpExchange x, AppApiSecurity.Key key, String path, String method, long rev) throws Exception {
        if (!path.equals("/v1/world-trim") && !path.startsWith("/v1/studio/") && !path.equals("/v1/player/dossier")) return false;
        String who = key.id().startsWith("user:") ? key.label() : "Schlüssel " + key.label();
        Object out;
        if (method.equals("GET")) {
            Map<String, String> q = AppApiManager.query(x.getRequestURI());
            out = switch (path) {
                case "/v1/world-trim" -> { api.requirePermission(key, TRIM_VIEW); yield trimState(api, key, rev); }
                case "/v1/studio/discord-status" -> { api.requirePermission(key, STATUS_VIEW); yield statusData(api, key, rev); }
                case "/v1/studio/scoreboard" -> { api.requirePermission(key, BOARD_VIEW); yield api.onMain(rev, () -> boardData(allows(api, key, BOARD_EDIT) && AppApiSecurity.permitted(key, "manage"))); }
                case "/v1/player/dossier" -> { api.requirePermission(key, "data.profile"); String player = q.getOrDefault("player", "");
                    yield api.onMain(rev, () -> plugin.appPlayerDossier(player, perm -> allows(api, key, perm))); }
                default -> throw new AppApiManager.ApiError(404, "Unbekannter Pfad");
            };
            AppApiManager.send(x, 200, out);
            return true;
        }
        if (!method.equals("POST")) throw new AppApiManager.ApiError(405, "GET oder POST erforderlich");
        Map<String, Object> body = AppApiManager.body(x, 64 * 1024);
        String op = String.valueOf(body.getOrDefault("operation", ""));
        out = switch (path) {
            case "/v1/world-trim" -> trimAction(api, key, rev, who, op, body);
            case "/v1/studio/discord-status" -> statusAction(api, key, rev, who, op, body);
            case "/v1/studio/scoreboard" -> boardAction(api, key, rev, who, op, body);
            default -> throw new AppApiManager.ApiError(404, "Unbekannter Pfad");
        };
        AppApiManager.send(x, 200, out);
        return true;
    }

    private static boolean allows(AppApiManager api, AppApiSecurity.Key key, String perm) { try { api.requirePermission(key, perm); return true; } catch (AppApiManager.ApiError e) { return false; } }
    private static void requireChange(AppApiManager api, AppApiSecurity.Key key, String perm) {
        api.requirePermission(key, perm);
        if (!AppApiSecurity.permitted(key, "manage")) throw new AppApiManager.ApiError(403, "Benötigt manage- oder full-Zugriff");
    }
    private void audit(String who, String action, String detail) { Bukkit.getScheduler().runTask(plugin, () -> plugin.expansionAudit("Simplex Panel/" + who, null, action, detail)); }

    // ------------------------------------------------------------------ World trimmer

    private static String actorName(String who) { return "App: " + who; }
    private UUID viewer(AppApiSecurity.Key key) { return plugin.appOperator(key.id(), "", new ArrayList<>()).getUniqueId(); }

    private Map<String, Object> trimState(AppApiManager api, AppApiSecurity.Key key, long rev) throws Exception {
        WorldTrimManager trimmer = plugin.worldTrimmerManager();
        if (trimmer == null) throw new AppApiManager.ApiError(503, "Welten-Trimmer nicht geladen");
        UUID viewer = viewer(key);
        Map<String, Object> state = api.onMain(rev, () -> trimmer.appState(viewer));
        state.put("canRun", allows(api, key, TRIM_RUN) && AppApiSecurity.permitted(key, "manage"));
        return state;
    }

    private Map<String, Object> trimAction(AppApiManager api, AppApiSecurity.Key key, long rev, String who, String op, Map<String, Object> body) throws Exception {
        requireChange(api, key, TRIM_RUN);
        if (!api.rate("trim:" + key.id(), 12)) throw new AppApiManager.ApiError(429, "Bitte kurz warten.");
        WorldTrimManager trimmer = plugin.worldTrimmerManager();
        if (trimmer == null) throw new AppApiManager.ApiError(503, "Welten-Trimmer nicht geladen");
        List<String> messages = new ArrayList<>();
        Player operator = plugin.appOperator(key.id(), actorName(who), messages);
        switch (op) {
            case "scan" -> api.onMain(rev, () -> { trimmer.scanAsync(operator, false); return null; });
            case "download" -> api.onMain(rev, () -> { trimmer.downloadLatest(operator); return null; });
            case "start" -> {
                if (!Boolean.TRUE.equals(body.get("backupConfirmed"))) throw new IllegalArgumentException("Bitte bestätigen, dass ein vollständiges Backup existiert.");
                if (!"TRIMMEN".equals(String.valueOf(body.getOrDefault("confirm", "")).trim().toUpperCase(Locale.ROOT))) throw new IllegalArgumentException("Zur Sicherheit TRIMMEN eintippen.");
                List<String> ids = new ArrayList<>();
                if (body.get("targets") instanceof List<?> list) for (Object o : list) { String v = String.valueOf(o); if (v.length() <= 300) ids.add(v); }
                if (ids.isEmpty() || ids.size() > 64) throw new IllegalArgumentException("1–64 Dimensionen auswählen");
                long threshold; int countdown;
                try { threshold = Long.parseLong(String.valueOf(body.getOrDefault("threshold", "1200"))); countdown = Integer.parseInt(String.valueOf(body.getOrDefault("countdown", "180"))); }
                catch (NumberFormatException e) { throw new IllegalArgumentException("Grenze und Countdown als Zahl"); }
                if (threshold < 20 || threshold > 1_728_000L) throw new IllegalArgumentException("Besuchsgrenze 20–1.728.000 Ticks");
                boolean snapshot = Boolean.TRUE.equals(body.get("snapshot"));
                api.onMain(rev, () -> { if (trimmer.configureAppRun(operator, ids, threshold, countdown, snapshot)) trimmer.requestStart(operator); return null; });
                audit(who, "Welten-Trimmer aus App gestartet", ids.size() + " Dimensionen • " + threshold + " Ticks • Countdown " + countdown + " s" + (snapshot ? " • Sicherheitskopie" : ""));
            }
            case "cancel" -> api.onMain(rev, () -> { trimmer.abortCountdown(operator); return null; });
            case "continue" -> api.onMain(rev, () -> { trimmer.continueWithLoadedChunks(operator); return null; });
            case "abort" -> api.onMain(rev, () -> { trimmer.cancelLoadedChunkOverride(operator); return null; });
            case "recovery" -> api.onMain(rev, () -> { trimmer.acknowledgeRecovery(operator); return null; });
            default -> throw new IllegalArgumentException("Aktion: scan, download, start, cancel, continue, abort oder recovery");
        }
        Map<String, Object> state = trimState(api, key, rev);
        state.put("messages", messages.stream().map(SimplexAdminSuite::stripFormatting).map(m -> m.replaceFirst("^\\[[^]]{1,40}]\\s*", "")).filter(m -> !m.isBlank()).limit(8).toList());
        return state;
    }

    // ------------------------------------------------------------------ Discord status designer

    private Map<String, Object> currentStatusValues() {
        Map<String, Object> values = new LinkedHashMap<>();
        for (StudioDesigner.Field f : StudioDesigner.STATUS_FIELDS) {
            Object v = plugin.getConfig().get(f.path());
            values.put(f.key(), v == null ? f.fallback() : f.type().equals("bool") ? Boolean.parseBoolean(String.valueOf(v)) : v instanceof Number || f.type().equals("text") || f.type().equals("multiline") || f.type().equals("url") || f.type().equals("color") ? v : String.valueOf(v));
        }
        return values;
    }

    private Map<String, Object> statusData(AppApiManager api, AppApiSecurity.Key key, long rev) throws Exception {
        DiscordBotManager bot = api.onMain(rev, plugin::discordRelay);
        Map<String, Object> values = api.onMain(rev, this::currentStatusValues);
        List<Map<String, Object>> fields = new ArrayList<>();
        for (StudioDesigner.Field f : StudioDesigner.STATUS_FIELDS) fields.add(SimplexAdminSuite.appMap("key", f.key(), "label", f.label(), "group", f.group(), "type", f.type(), "min", f.min(), "max", f.max(), "default", f.fallback()));
        Map<String, Object> out = SimplexAdminSuite.appMap("fields", fields, "values", values, "defaults", StudioDesigner.statusDefaults(), "presets", StudioDesigner.statusPresets(),
            "states", List.of(List.of("live", "Live"), List.of("online", "Online"), List.of("warning", "Instabil"), List.of("critical", "Überlastet"), List.of("restarting", "Neustart"), List.of("offline", "Offline")),
            "canEdit", allows(api, key, STATUS_EDIT) && AppApiSecurity.permitted(key, "manage"));
        if (bot != null) out.put("preview", previewOnMain(api, rev, bot, Map.of(), "live"));
        return out;
    }

    private Map<String, Object> previewOnMain(AppApiManager api, long rev, DiscordBotManager bot, Map<String, Object> overrides, String state) throws Exception {
        return api.onMain(rev, () -> bot.designerPreview(overrides, state));
    }

    private Map<String, Object> statusOverrides(Map<String, Object> body) {
        Map<String, Object> overrides = new LinkedHashMap<>();
        if (!(body.get("values") instanceof Map<?, ?> raw)) return overrides;
        for (var e : raw.entrySet()) {
            StudioDesigner.Field f = StudioDesigner.statusField(String.valueOf(e.getKey()));
            if (f == null) continue;
            overrides.put(f.key(), StudioDesigner.validStatus(f, e.getValue()));
        }
        return overrides;
    }

    private Map<String, Object> statusAction(AppApiManager api, AppApiSecurity.Key key, long rev, String who, String op, Map<String, Object> body) throws Exception {
        DiscordBotManager bot = api.onMain(rev, plugin::discordRelay);
        if (bot == null) throw new AppApiManager.ApiError(503, "Discord-Bot nicht geladen");
        switch (op) {
            case "preview" -> {
                api.requirePermission(key, STATUS_VIEW);
                if (!api.rate("status-preview:" + key.id(), 90)) throw new AppApiManager.ApiError(429, "Vorschau kurz pausiert.");
                return previewOnMain(api, rev, bot, statusOverrides(body), String.valueOf(body.getOrDefault("state", "live")));
            }
            case "save" -> {
                requireChange(api, key, STATUS_EDIT);
                Map<String, Object> values = statusOverrides(body);
                if (values.isEmpty()) throw new IllegalArgumentException("Keine Änderungen übermittelt");
                double warning = Double.parseDouble(String.valueOf(values.getOrDefault("warning-tps", plugin.getConfig().getDouble("discord-bot.status.design.warning-tps", 18.5D))));
                double critical = Double.parseDouble(String.valueOf(values.getOrDefault("critical-tps", plugin.getConfig().getDouble("discord-bot.status.design.critical-tps", 16D))));
                if (critical >= warning) throw new IllegalArgumentException("„Überlastet“ muss unter „Instabil“ liegen");
                api.onMain(rev, () -> {
                    for (var e : values.entrySet()) plugin.getConfig().set(StudioDesigner.statusField(e.getKey()).path(), e.getValue());
                    plugin.saveConfig();
                    plugin.refreshDiscordStatus();
                    return null;
                });
                audit(who, "Discord-Status gestaltet", values.size() + " Felder" + (body.get("preset") == null ? "" : " • Vorlage " + String.valueOf(body.get("preset")).replaceAll("[^a-z-]", "")));
                Map<String, Object> out = statusData(api, key, rev);
                out.put("notice", "Discord-Status gespeichert – die Nachricht wird beim nächsten Intervall aktualisiert.");
                return out;
            }
            default -> throw new IllegalArgumentException("Aktion: preview oder save");
        }
    }

    // ------------------------------------------------------------------ Scoreboard designer

    private Map<String, Object> boardData(boolean canEdit) {
        NativeScoreboardManager board = plugin.scoreboardManager();
        String main = plugin.getConfig().getString("scoreboard.main-world", "world"), second = plugin.getConfig().getString("scoreboard.second-world.name", "survival2");
        int mainPlayers = 0, secondPlayers = 0;
        for (Player p : Bukkit.getOnlinePlayers()) { World w = p.getWorld(); String n = w == null ? "" : w.getName(); if (n.startsWith(second)) secondPlayers++; else if (n.startsWith(main)) mainPlayers++; }
        Player sample = Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
        Map<String, Object> values = SimplexAdminSuite.appMap("{days}", board == null ? "0" : Long.toString(board.mainDays()), "{world}", "Main", "{online}", Integer.toString(Bukkit.getOnlinePlayers().size()),
            "{max}", Integer.toString(plugin.serverMaxPlayers()), "{main_players}", Integer.toString(mainPlayers), "{second_players}", Integer.toString(secondPlayers),
            "{points}", sample == null ? "1250" : Long.toString(plugin.expansionPoints(sample)), "{player}", sample == null ? "Steve" : sample.getName());
        List<String> lines = plugin.getConfig().getStringList("scoreboard.lines");
        return SimplexAdminSuite.appMap("enabled", plugin.getConfig().getBoolean("scoreboard.enabled", true), "title", plugin.getConfig().getString("scoreboard.title", StudioDesigner.SCOREBOARD_DEFAULT_TITLE),
            "lines", lines.isEmpty() ? StudioDesigner.SCOREBOARD_DEFAULT_LINES : lines, "germanLines", plugin.getConfig().getStringList("scoreboard.lines-de"),
            "hideNumbers", plugin.getConfig().getBoolean("scoreboard.hide-java-score-numbers", true), "presets", StudioDesigner.scoreboardPresets(),
            "placeholders", StudioDesigner.PLACEHOLDERS.stream().map(r -> SimplexAdminSuite.appMap("key", r.get(0), "label", r.get(1))).toList(), "sample", values,
            "pointsEnabled", plugin.getConfig().getBoolean("simplex-points.enabled", true), "maxLines", 15, "canEdit", canEdit);
    }

    private Map<String, Object> boardAction(AppApiManager api, AppApiSecurity.Key key, long rev, String who, String op, Map<String, Object> body) throws Exception {
        requireChange(api, key, BOARD_EDIT);
        if (!op.equals("save")) throw new IllegalArgumentException("Aktion: save");
        String title = StudioDesigner.validTitle(body.get("title"));
        List<String> lines = StudioDesigner.validLines(body.get("lines"));
        Object enabledRaw = body.get("enabled");
        Map<String, Object> out = api.onMain(rev, () -> {
            plugin.getConfig().set("scoreboard.title", title);
            plugin.getConfig().set("scoreboard.lines", lines);
            if (enabledRaw instanceof Boolean enabled && enabled != plugin.getConfig().getBoolean("scoreboard.enabled", true)) {
                plugin.getConfig().set("scoreboard.enabled", enabled);
                plugin.saveConfig();
                NativeScoreboardManager board = plugin.scoreboardManager();
                if (board != null) board.reload();
            } else plugin.saveConfig();
            return boardData(true);
        });
        audit(who, "Scoreboard gestaltet", lines.size() + " Zeilen" + (body.get("preset") == null ? "" : " • Vorlage " + String.valueOf(body.get("preset")).replaceAll("[^a-z-]", "")));
        out.put("notice", "Scoreboard gespeichert – alle Spieler sehen es mit der nächsten Aktualisierung.");
        return out;
    }
}
