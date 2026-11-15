package eu.simplexsmp.adminsuite;

import com.sun.net.httpserver.HttpExchange;

import java.util.Map;
import java.util.UUID;

/**
 * 10.20.0: App/web routes of the Bann-Zentrale and the LiteBans migration.
 *
 * <pre>
 * GET  /v1/ban-center?filter=&amp;q=          overview (cases, KPIs, IP blocks, excluded IPs, history)
 * GET  /v1/ban-center/player?player=UUID   ban status for one profile (main ban / alt account / case)
 * POST /v1/ban-center                      {operation: lift|ip-preview|ip-lift|ip-unblock, …, confirmed: true}
 * GET  /v1/ban-migration                   snapshot, cases with decisions and progress
 * POST /v1/ban-migration                   {operation: snapshot|decide|run|disable|rename-jar, …, confirmed: true}
 * </pre>
 */
final class BanCenterApi {
    static final String VIEW = "data.ban-center";
    static final String MANAGE = "ban-center.manage";
    static final String MIGRATE = "ban-center.migrate";

    private BanCenterApi() { }

    static boolean route(SimplexAdminSuite plugin, AppApiManager api, HttpExchange x, AppApiSecurity.Key key, String path, String method, long rev) throws Exception {
        if (!path.equals("/v1/ban-center") && !path.equals("/v1/ban-center/player") && !path.equals("/v1/ban-migration")) return false;
        if (plugin.banCenter == null) throw new AppApiManager.ApiError(503, "Bann-Zentrale nicht geladen");
        api.requirePermission(key, VIEW);
        Map<String, String> query = AppApiManager.query(x.getRequestURI());
        boolean manage = api.allows(key, MANAGE) && AppApiSecurity.permitted(key, "manage");
        boolean migrate = api.allows(key, MIGRATE) && AppApiSecurity.permitted(key, "full");
        if (path.equals("/v1/ban-center/player")) {
            if (!method.equals("GET")) throw new AppApiManager.ApiError(405, "Methode nicht erlaubt");
            UUID player;
            try { player = UUID.fromString(query.getOrDefault("player", "")); } catch (IllegalArgumentException e) { throw new IllegalArgumentException("Spieler-UUID fehlt"); }
            Map<String, Object> data = api.onMain(rev, () -> plugin.banCenter.player(player));
            data.put("manage", manage);
            AppApiManager.send(x, 200, data);
            return true;
        }
        if (path.equals("/v1/ban-center")) {
            if (method.equals("GET")) {
                Map<String, Object> data = api.onMain(rev, () -> plugin.banCenter.overview(query.getOrDefault("filter", "all"), query.getOrDefault("q", "")));
                data.put("manage", manage); data.put("migrate", migrate);
                AppApiManager.send(x, 200, data);
            } else if (method.equals("POST")) {
                Map<String, Object> body = AppApiManager.body(x, 8192);
                String operation = String.valueOf(body.getOrDefault("operation", ""));
                if (!operation.equals("ip-preview")) {
                    if (!manage) throw new AppApiManager.ApiError(403, "Aufheben benötigt das Recht " + MANAGE);
                    if (!Boolean.TRUE.equals(body.get("confirmed"))) throw new IllegalArgumentException("Bestätigung fehlt");
                    if (operation.equals("ip-lift") && "true".equals(String.valueOf(body.get("exclude"))) && !AppApiSecurity.permitted(key, "full")) throw new AppApiManager.ApiError(403, "IP-Ausschlüsse ändern benötigt Vollzugriff");
                }
                String actor = api.actorName(key);
                AppApiManager.send(x, 200, api.onMain(rev, () -> plugin.banCenter.apply(actor, body)));
            } else throw new AppApiManager.ApiError(405, "Methode nicht erlaubt");
            return true;
        }
        if (plugin.liteBansMigration == null) throw new AppApiManager.ApiError(503, "LiteBans-Migration nicht geladen");
        if (method.equals("GET")) {
            Map<String, Object> data = api.onMain(rev, () -> plugin.liteBansMigration.data());
            data.put("migrate", migrate);
            AppApiManager.send(x, 200, data);
        } else if (method.equals("POST")) {
            if (!migrate) throw new AppApiManager.ApiError(403, "Die Migration benötigt Vollzugriff und das Recht " + MIGRATE);
            Map<String, Object> body = AppApiManager.body(x, 65536);
            if (!Boolean.TRUE.equals(body.get("confirmed"))) throw new IllegalArgumentException("Bestätigung fehlt");
            String actor = api.actorName(key);
            AppApiManager.send(x, 200, api.onMain(rev, () -> plugin.liteBansMigration.apply(actor, body)));
        } else throw new AppApiManager.ApiError(405, "Methode nicht erlaubt");
        return true;
    }
}
