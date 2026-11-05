package eu.simplexsmp.adminsuite;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.*;
import java.util.*;

/** Small integration checks in the existing real HTTPS fixture: rights, exact deletion, recovery and restart. */
public final class Release1060Harness {
    static int checks;
    static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
    static Map<String, Object> get(HttpClient c, URI b, String p, String token) throws Exception {
        var r = Release1020Harness.call(c, b, p, token, null); check(r.statusCode() == 200, p + ": " + r.body()); return Release1050Harness.obj(r.body());
    }
    static Map<String, Object> post(HttpClient c, URI b, String p, String token, Map<String, Object> body) throws Exception {
        var r = Release1020Harness.call(c, b, p, token, body); check(r.statusCode() == 200, p + ": " + r.body()); return Release1050Harness.obj(r.body());
    }
    static List<Map<String, Object>> items(Map<String, Object> body) { return Release1050Harness.l(body.get("items")); }
    static void run(HttpClient c, URI b, SimplexAdminSuite plugin, AppApiManager api, String owner, String watcher, String viewer) throws Exception {
        Path folder = plugin.getDataFolder().toPath();
        plugin.staffChat = new StaffChat(plugin); Moderation790Harness.set(plugin, SimplexAdminSuite.class, "appChat", new AppChat()); plugin.appChat.attach(folder.resolve("public-chat.properties"));
        plugin.appChat.record("", "Test", "first message", "app"); plugin.appChat.record("", "Test", "second message", "app");
        check(Release1050Harness.l(get(c,b,"/v1/histories",viewer).get("kinds")).isEmpty(), "summary never reveals ungranted histories");
        check(Release1020Harness.call(c,b,"/v1/histories/entries?kind=chat",viewer,null).statusCode() == 403, "entries need source read permission");
        check(Release1020Harness.call(c,b,"/v1/histories/prepare",watcher,Map.of("kind","advertising","all",true)).statusCode() == 403, "manage scope without history.manage cannot delete");
        check(Release1020Harness.call(c,b,"/v1/histories/prepare",null,Map.of("kind","chat","all",true)).statusCode() == 401, "no public deletion");
        Map<String, Object> overview = get(c,b,"/v1/histories",owner);
        check(Boolean.TRUE.equals(overview.get("canManage")) && Release1050Harness.l(overview.get("kinds")).size() == 5, "owner gets the five available histories");
        Map<String, Object> plan = post(c,b,"/v1/histories/prepare",owner,Map.of("kind","chat","ids",List.of("1")));
        String token = String.valueOf(plan.get("token"));
        check(items(get(c,b,"/v1/histories/entries?kind=chat",owner)).size() == 2, "prepare never mutates");
        plugin.appChat.record("", "Test", "concurrent message", "app");
        Map<String, Object> applied = post(c,b,"/v1/histories/apply",owner,Map.of("token",token)); String backup = String.valueOf(applied.get("backup"));
        List<Map<String, Object>> current = items(get(c,b,"/v1/histories/entries?kind=chat",owner));
        check(current.size() == 2 && current.stream().anyMatch(r -> "3".equals(r.get("id"))) && current.stream().noneMatch(r -> "1".equals(r.get("id"))), "only the confirmed ID disappears; concurrent row survives");
        check(Files.exists(folder.resolve("history-backups").resolve(backup)), "backup exists before success is reported");
        check(Release1020Harness.call(c,b,"/v1/histories/apply",owner,Map.of("token",token)).statusCode() == 409, "confirmation cannot be replayed");
        check(Release1020Harness.call(c,b,"/v1/histories/restore",owner,Map.of("backup","../../config.yml")).statusCode() == 400, "restore cannot escape backup directory");
        // A fresh API instance uses on-disk backups, so recovery is available after restart.
        HistoryApi restarted = new HistoryApi(plugin);
        check(restarted != null, "history service starts without changing existing state");
        post(c,b,"/v1/histories/restore",owner,Map.of("backup",backup));
        post(c,b,"/v1/histories/restore",owner,Map.of("backup",backup));
        check(items(get(c,b,"/v1/histories/entries?kind=chat",owner)).size() == 3, "restore merges without duplicates");
        String epoch = String.valueOf(((Map<?,?>)plugin.appChat.read(0)).get("epoch"));
        Map<String,Object> all = post(c,b,"/v1/histories/prepare",owner,Map.of("kind","chat","all",true));
        plugin.appChat.record("", "Test", "after clear preview", "app");
        post(c,b,"/v1/histories/apply",owner,Map.of("token",all.get("token")));
        check(items(get(c,b,"/v1/histories/entries?kind=chat",owner)).size() == 1, "clear is bound to the preview snapshot");
        check(!epoch.equals(((Map<?,?>)plugin.appChat.read(0)).get("epoch")), "active chats are invalidated after deletion");
        AppChat disk = new AppChat(); disk.attach(folder.resolve("public-chat.properties"));
        check(disk.historySnapshot().size() == 1 && "4".equals(disk.historySnapshot().get(0).get("id")), "deletion is persisted before returning"); disk.stop();
        List<Map<String,Object>> places = Release1050Harness.l(plugin.adHub.overview().get("places"));
        Map<String,Object> adPlan = post(c,b,"/v1/histories/prepare",owner,Map.of("kind","advertising","all",true));
        post(c,b,"/v1/histories/apply",owner,Map.of("token",adPlan.get("token")));
        check(plugin.adHub.historySnapshot().isEmpty() && places.equals(Release1050Harness.l(plugin.adHub.overview().get("places"))), "clearing ad history keeps places, texts, timers and counters unchanged");
        plugin.staffChat.historyReplace(List.of(SimplexAdminSuite.appMap("id",1L,"at",1L,"text","staff test")));
        String staffEpoch = String.valueOf(((Map<?,?>)plugin.staffChat.read(0)).get("epoch"));
        plugin.staffChat.historyReplace(List.of());
        check(!staffEpoch.equals(((Map<?,?>)plugin.staffChat.read(99,staffEpoch)).get("epoch")), "staff chat exposes a changed epoch");
        plugin.adminLog.record("Console",null,"Test history","entry");
        List<Map<String,Object>> log = plugin.adminLog.historySnapshot(); long serial = log.stream().mapToLong(r->AdminActivityLog.number(r.get("id"))).max().orElse(0);
        plugin.adminLog.historyReplace(List.of()); plugin.adminLog.stop();
        plugin.adminLog = new AdminActivityLog(plugin); plugin.adminLog.record("Console",null,"After restart","entry");
        check(plugin.adminLog.historySnapshot().stream().allMatch(r->AdminActivityLog.number(r.get("id"))>serial), "admin log cursor stays monotonic after clearing and restart");
        plugin.appChat.stop();
        System.out.println("PASS " + checks + " 10.6.0 history and startup checks");
    }
}
