package eu.simplexsmp.adminsuite;

import com.sun.net.httpserver.HttpExchange;
import java.util.Map;

/**
 * 10.4.0 app/web routes for the Discord advertising planner. Viewing needs {@code data.discord-ads}; every change
 * (linking accounts, texts, channels, settings) needs {@code discord-ads.manage} plus manage or full access.
 * Neither right is part of a role template. Tokens never leave the server.
 */
final class DiscordAdsApi {
    static final String VIEW = "data.discord-ads", MANAGE = "discord-ads.manage";
    private final SimplexAdminSuite plugin;

    DiscordAdsApi(SimplexAdminSuite plugin) { this.plugin = plugin; }

    boolean route(AppApiManager api, HttpExchange x, AppApiSecurity.Key key, String path, String method) throws Exception {
        if (!path.equals("/v1/discord-ads") && !path.startsWith("/v1/discord-ads/")) return false;
        api.requirePermission(key, VIEW);
        DiscordAds ads = plugin.discordAds;
        if (ads == null) throw new AppApiManager.ApiError(503, "Discord-Werbeplaner nicht geladen (discord-ads.enabled oder Serverprotokoll prüfen).");
        boolean canManage = allows(api, key, MANAGE) && AppApiSecurity.permitted(key, "manage");
        if (method.equals("GET")) {
            Map<String, String> q = AppApiManager.query(x.getRequestURI());
            Object out = switch (path) {
                case "/v1/discord-ads" -> { Map<String, Object> o = ads.overview(); o.put("canManage", canManage); yield o; }
                case "/v1/discord-ads/guilds" -> { limit(api, key, "guilds", 30); yield SimplexAdminSuite.appMap("guilds", ads.guilds(q.getOrDefault("account", ""), Boolean.parseBoolean(q.getOrDefault("refresh", "false")))); }
                case "/v1/discord-ads/channels" -> { limit(api, key, "channels", 30); yield ads.channels(q.getOrDefault("account", ""), q.getOrDefault("guild", "")); }
                default -> throw new AppApiManager.ApiError(404, "Unbekannter Pfad");
            };
            AppApiManager.send(x, 200, out);
            return true;
        }
        if (!method.equals("POST") || !path.equals("/v1/discord-ads")) throw new AppApiManager.ApiError(405, "GET oder POST /v1/discord-ads");
        if (!canManage) throw new AppApiManager.ApiError(403, "Benötigt das Recht „Discord-Werbung verwalten“ und manage- oder full-Zugriff");
        limit(api, key, "change", 40);
        Map<String, Object> body = AppApiManager.body(x, 32 * 1024);
        String op = String.valueOf(body.getOrDefault("operation", "")), who = key.id().startsWith("user:") ? key.label() : "Schlüssel " + key.label();
        String actor = "Simplex Panel/" + who;
        Map<String, Object> out = switch (op) {
            case "link" -> { limit(api, key, "link", 6); yield ads.link(String.valueOf(body.getOrDefault("token", "")), null, Boolean.TRUE.equals(body.get("accept")), actor); }
            case "relink" -> { limit(api, key, "link", 6); yield ads.link(String.valueOf(body.getOrDefault("token", "")), String.valueOf(body.getOrDefault("account", "")), true, actor); }
            case "check" -> ads.checkAccount(String.valueOf(body.getOrDefault("account", "")), actor);
            case "unlink" -> ads.unlink(String.valueOf(body.getOrDefault("account", "")), Boolean.TRUE.equals(body.get("logout")), Boolean.TRUE.equals(body.get("deleteCampaigns")), actor);
            case "template.save" -> ads.saveTemplate(body, actor);
            case "template.delete" -> ads.deleteTemplate(String.valueOf(body.getOrDefault("id", "")), actor);
            case "campaign.save" -> ads.saveCampaign(body, actor);
            case "campaign.pause", "campaign.resume", "campaign.send-now", "campaign.delete" -> ads.campaignAction(String.valueOf(body.getOrDefault("id", "")), op.substring("campaign.".length()), actor);
            case "settings.save" -> ads.saveSettings(body, actor);
            default -> throw new IllegalArgumentException("Unbekannte Aktion");
        };
        // Every answer carries the fresh overview so app and web panel never show stale timers.
        Map<String, Object> overview = ads.overview(); overview.put("canManage", true);
        out.put("overview", overview);
        AppApiManager.send(x, 200, out);
        return true;
    }

    private static boolean allows(AppApiManager api, AppApiSecurity.Key key, String perm) { try { api.requirePermission(key, perm); return true; } catch (AppApiManager.ApiError e) { return false; } }
    private static void limit(AppApiManager api, AppApiSecurity.Key key, String what, int max) { if (!api.rate("ads-" + what + ":" + key.id(), max)) throw new AppApiManager.ApiError(429, "Bitte kurz warten."); }
}
