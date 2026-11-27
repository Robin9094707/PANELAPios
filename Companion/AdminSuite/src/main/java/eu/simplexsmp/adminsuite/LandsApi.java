package eu.simplexsmp.adminsuite;

import com.sun.net.httpserver.HttpExchange;

import java.util.Map;
import java.util.UUID;

/**
 * 10.24.0: App/web routes of the Länder-Zentrale.
 *
 * <pre>
 * GET  /v1/lands?q=&amp;sort=chunks|members|online|balance|newest|name   dashboard with all lands and totals
 * GET  /v1/lands/land?id=ULID                                  one land: members, roles + rights, natural flags, areas, inbox
 * GET  /v1/lands/area?land=ULID&amp;area=ULID                      one sub-area: members, roles, natural flags
 * GET  /v1/lands/player?player=UUID|Name                       owned lands, memberships (role, sub-areas) and land bans
 * POST /v1/lands                                               {operation, land, area?, player?, role?, flag?, value?, …, confirmed?}
 * </pre>
 * Viewing needs data.lands, changes need lands.manage (manage scope), deleting a land additionally lands.delete and
 * full scope. Every change is written to the admin log.
 */
final class LandsApi {
    private LandsApi() { }

    static boolean route(SimplexAdminSuite plugin, AppApiManager api, LandsAdmin lands, HttpExchange x, AppApiSecurity.Key key, String path, String method, long rev) throws Exception {
        if (!path.equals("/v1/lands") && !path.startsWith("/v1/lands/")) return false;
        if (lands == null) throw new AppApiManager.ApiError(503, "Länder-Zentrale nicht geladen");
        api.requirePermission(key, LandsAdmin.VIEW);
        Map<String, String> query = AppApiManager.query(x.getRequestURI());
        boolean manage = api.allows(key, LandsAdmin.MANAGE) && AppApiSecurity.permitted(key, "manage");
        boolean delete = manage && api.allows(key, LandsAdmin.DELETE) && AppApiSecurity.permitted(key, "full");
        if (method.equals("GET")) {
            Map<String, Object> data = switch (path) {
                case "/v1/lands" -> api.onMain(rev, () -> lands.dashboard(query.getOrDefault("q", ""), query.getOrDefault("sort", "chunks")));
                case "/v1/lands/land" -> api.onMain(rev, () -> lands.detail(query.getOrDefault("id", "")));
                case "/v1/lands/area" -> api.onMain(rev, () -> lands.areaDetail(query.getOrDefault("land", ""), query.getOrDefault("area", "")));
                case "/v1/lands/player" -> {
                    String raw = query.getOrDefault("player", "");
                    UUID uuid = api.onMain(rev, () -> plugin.accessLocks.resolve(raw));
                    yield api.onMain(rev, () -> lands.player(uuid));
                }
                default -> throw new AppApiManager.ApiError(404, "Unbekannte Länder-Route");
            };
            data = new java.util.LinkedHashMap<>(data);
            data.put("manage", manage);
            data.put("mayDelete", delete);
            AppApiManager.send(x, 200, data);
            return true;
        }
        if (!method.equals("POST") || !path.equals("/v1/lands")) throw new AppApiManager.ApiError(405, "Methode nicht erlaubt");
        if (!manage) throw new AppApiManager.ApiError(403, "Länder verwalten benötigt das Recht " + LandsAdmin.MANAGE);
        Map<String, Object> body = AppApiManager.body(x, 16384);
        String actor = api.actorName(key);
        AppApiManager.send(x, 200, api.onMain(rev, () -> lands.apply(actor, body, delete)));
        return true;
    }
}
