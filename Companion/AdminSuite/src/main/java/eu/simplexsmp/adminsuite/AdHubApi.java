package eu.simplexsmp.adminsuite;

import com.sun.net.httpserver.HttpExchange;
import java.util.Map;

/**
 * 10.5.0 app/web routes of the Werbe-Zentrale. Viewing needs {@code data.discord-ads}; marking a post, snoozing and
 * undoing additionally need manage or full access; texts, places and settings need {@code discord-ads.manage}.
 * (The right ids stay from 10.4.0 so existing grants keep working.) {@code POST /v1/ad-hub/link} is the only route
 * without login: it is bound by HMAC to one place and one reminder cycle (the Bark link).
 */
final class AdHubApi {
    static final String VIEW = "data.discord-ads", MANAGE = "discord-ads.manage";
    private final SimplexAdminSuite plugin;

    AdHubApi(SimplexAdminSuite plugin) { this.plugin = plugin; }

    /** Bark link: copy & mark posted, peek or snooze one place – before authentication. */
    boolean publicRoute(AppApiManager api, HttpExchange x, String path, String method, String ip) throws Exception {
        if (!path.equals("/v1/ad-hub/link")) return false;
        if (!method.equals("POST")) throw new AppApiManager.ApiError(405, "POST /v1/ad-hub/link");
        if (!api.rate("ad-link:" + ip, 30)) throw new AppApiManager.ApiError(429, "Bitte kurz warten.");
        AdHub hub = require();
        Map<String, Object> body = AppApiManager.body(x, 2048);
        int cycle; try { cycle = Integer.parseInt(String.valueOf(body.getOrDefault("c", "0")).replaceAll("\\.0$", "")); } catch (NumberFormatException e) { throw new AppApiManager.ApiError(400, "Link unvollständig"); }
        String op = String.valueOf(body.getOrDefault("op", "open"));
        if (!op.equals("open") && !op.equals("peek") && !op.equals("snooze")) throw new AppApiManager.ApiError(400, "Unbekannte Aktion");
        AppApiManager.send(x, 200, hub.link(String.valueOf(body.getOrDefault("p", "")), cycle, String.valueOf(body.getOrDefault("k", "")), op, AdHub.lng(body.get("minutes"))));
        return true;
    }

    boolean route(AppApiManager api, HttpExchange x, AppApiSecurity.Key key, String path, String method) throws Exception {
        if (path.equals("/v1/discord-ads") || path.startsWith("/v1/discord-ads/"))
            throw new AppApiManager.ApiError(410, "Der Discord-Werbeplaner (Self-Bot) wurde durch die Werbe-Zentrale ersetzt. Bitte die App aktualisieren.");
        if (!path.equals("/v1/ad-hub")) return false;
        api.requirePermission(key, VIEW);
        AdHub hub = require();
        boolean write = AppApiSecurity.permitted(key, "manage"), canManage = write && allows(api, key, MANAGE);
        if (method.equals("GET")) {
            Map<String, Object> o = hub.overview(); o.put("canManage", canManage); o.put("canPost", write);
            AppApiManager.send(x, 200, o);
            return true;
        }
        if (!method.equals("POST")) throw new AppApiManager.ApiError(405, "GET oder POST /v1/ad-hub");
        if (!write) throw new AppApiManager.ApiError(403, "Benötigt manage- oder full-Zugriff");
        if (!api.rate("adhub:" + key.id(), 60)) throw new AppApiManager.ApiError(429, "Bitte kurz warten.");
        Map<String, Object> body = AppApiManager.body(x, 64 * 1024);
        String op = String.valueOf(body.getOrDefault("operation", "")), who = key.id().startsWith("user:") ? key.label() : "Schlüssel " + key.label();
        String id = String.valueOf(body.getOrDefault("id", ""));
        boolean posting = op.equals("place.post") || op.equals("place.snooze") || op.equals("place.undo") || op.equals("text.convert") && !Boolean.TRUE.equals(body.get("save")) || op.equals("migration.ack");
        if (!posting && !canManage) throw new AppApiManager.ApiError(403, "Benötigt das Recht „Werbe-Zentrale verwalten“");
        Map<String, Object> out = switch (op) {
            case "text.save" -> hub.saveText(body, who);
            case "text.delete" -> hub.deleteText(id, who);
            case "text.convert" -> hub.convertText(body, who);
            case "place.save" -> hub.savePlace(body, api.security.notificationOwner(key), who, who);
            case "place.post" -> hub.post(id, Boolean.TRUE.equals(body.get("force")), who);
            case "place.snooze" -> hub.snooze(id, AdHub.lng(body.get("minutes")), who);
            case "place.undo" -> hub.placeAction(id, "undo", who);
            case "place.pause", "place.resume", "place.delete", "place.due-now" -> hub.placeAction(id, op.substring("place.".length()), who);
            case "settings.save" -> hub.saveSettings(body, who);
            case "migration.ack" -> hub.acknowledgeMigration();
            default -> throw new IllegalArgumentException("Unbekannte Aktion");
        };
        // Every answer carries the fresh overview so app and web panel never show stale timers.
        Map<String, Object> overview = hub.overview(); overview.put("canManage", canManage); overview.put("canPost", true);
        out.put("overview", overview);
        AppApiManager.send(x, 200, out);
        return true;
    }

    private AdHub require() {
        AdHub hub = plugin.adHub;
        if (hub == null) throw new AppApiManager.ApiError(503, "Werbe-Zentrale nicht geladen (ad-hub.enabled oder Serverprotokoll prüfen).");
        return hub;
    }
    private static boolean allows(AppApiManager api, AppApiSecurity.Key key, String perm) { try { api.requirePermission(key, perm); return true; } catch (AppApiManager.ApiError e) { return false; } }
}
