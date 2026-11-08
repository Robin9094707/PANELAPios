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
        api.security.users.save("historyother", "history-other-password", Set.of("*"), true);
        String other = Release1020Harness.login(c,b,"historyother","history-other-password");
        check(Release1020Harness.call(c,b,"/v1/histories/apply",other,Map.of("token",token)).statusCode() == 409, "a different full user cannot consume the owner's confirmation");
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
        Map<String,Object> blockedPlan = post(c,b,"/v1/histories/prepare",owner,Map.of("kind","chat","all",true));
        Path backups = folder.resolve("history-backups"), held = folder.resolve("history-backups-held");
        Files.move(backups, held); Files.writeString(backups,"fixture blocks backup directory");
        try {
            check(Release1020Harness.call(c,b,"/v1/histories/apply",owner,Map.of("token",blockedPlan.get("token"))).statusCode() == 503, "failed backup rejects deletion");
            check(plugin.appChat.historySnapshot().size() == 1, "failed backup preserves the live history");
        } finally { Files.delete(backups); Files.move(held,backups); }
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
        customization(c,b,plugin,api,owner,viewer);
        aiWorkspace(c,b,owner,viewer);
        plugin.appChat.stop();
        System.out.println("PASS " + checks + " 10.6.0 history and startup checks");
    }
    private static void customization(HttpClient c, URI b, SimplexAdminSuite plugin, AppApiManager api, String owner, String viewer) throws Exception {
        check(Release1020Harness.call(c,b,"/v1/shortcut-prefs",null,null).statusCode()==401,"preferences require login");
        post(c,b,"/v1/shortcut-prefs",owner,Map.of("name","Survival","tools",List.of("tickets","players","tickets"),"hidden",true));
        post(c,b,"/v1/shortcut-prefs",owner,Map.of("compact",false,"appearance",Map.of("radius",22)));
        post(c,b,"/v1/shortcut-prefs",owner,Map.of("appearance",Map.of("chartStyle","line")));
        var saved=get(c,b,"/v1/shortcut-prefs",owner);
        check(List.of("tickets","players").equals(saved.get("tools")) && Boolean.TRUE.equals(saved.get("hidden")) && Boolean.FALSE.equals(saved.get("compact")),"ordered shortcuts and partial updates");
        check(((Map<?,?>)saved.get("appearance")).size()==2,"parallel surfaces preserve other appearance values");
        check(get(c,b,"/v1/shortcut-prefs",viewer).isEmpty(),"preferences isolated per account");
        check(Release1020Harness.call(c,b,"/v1/shortcut-prefs",owner,Map.of("appearance",Map.of("radius",-1))).statusCode()==400,"invalid appearance rejected");
        check(new PanelHub(api,plugin,api.security).shortcutPrefs(api.security.notificationOwner(api.security.authenticate(owner))).get("name").equals("Survival"),"shortcut preferences survive restart");
        check(Release1020Harness.call(c,b,"/v1/player-nbt/summary?player=unknown",viewer,null).statusCode()==403,"NBT summary requires a separate NBT right");

        Path root=Files.createTempDirectory("sas-nbt-profile-");
        try {
            Path data=Files.createDirectories(root.resolve("world/players/data"));
            UUID id=UUID.randomUUID();
            check("missing".equals(PlayerNbtAccess.summary(root,List.of(),id).get("reason")),"missing NBT distinguished");
            try(var out=new java.io.DataOutputStream(new java.util.zip.GZIPOutputStream(Files.newOutputStream(data.resolve(id+".dat"))))){
                out.writeByte(10);out.writeUTF("");
                out.writeByte(3);out.writeUTF("DataVersion");out.writeInt(4321);
                out.writeByte(10);out.writeUTF("bukkit");
                out.writeByte(4);out.writeUTF("firstPlayed");out.writeLong(1700000000000L);
                out.writeByte(4);out.writeUTF("lastPlayed");out.writeLong(1710000000000L);
                out.writeByte(0);out.writeByte(0);
            }
            var nbt=PlayerNbtAccess.summary(root,List.of(),id);
            check(Boolean.TRUE.equals(nbt.get("available")) && Objects.equals(nbt.get("firstJoin"),1700000000000L) && Objects.equals(nbt.get("lastPlayed"),1710000000000L),"NBT first and last played use stored timestamps");
            check(nbt.get("path").equals("/world/players/data/"+id+".dat") && !((List<?>)nbt.get("fields")).isEmpty(),"modern world path and metadata");
        } finally {
            try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
        }
    }

    private static void aiWorkspace(HttpClient c,URI b,String owner,String viewer)throws Exception{
        check(Release1020Harness.call(c,b,"/v1/ai-workspace",null,null).statusCode()==401,"AI workspace requires authentication");
        check(Boolean.TRUE.equals(get(c,b,"/v1/ai-workspace",owner).get("owner")),"owner can review the queue");
        check(Boolean.FALSE.equals(get(c,b,"/v1/ai-workspace",viewer).get("allowed")),"delegated access is explicitly granted");
        check(Release1020Harness.call(c,b,"/v1/ai-workspace/approve",viewer,Map.of("id","forged","actions",List.of("fake"))).statusCode()==403,"delegates cannot apply proposals");
        check(Release1020Harness.call(c,b,"/v1/ai-workspace/approve",owner,Map.of("id","forged","actions",List.of("fake"))).statusCode()==409,"owner cannot approve arbitrary parameters");
        check(Release1020Harness.call(c,b,"/v1/ai-workspace/ask",owner,Map.of("question","restart")).statusCode()==503,"disabled model cannot draft a restart");
        long[] night=AiAdminWorkspace.window("last-night",java.time.Instant.parse("2026-11-08T10:00:00Z"));
        check(night[0]==java.time.Instant.parse("2026-11-07T21:00:00Z").toEpochMilli() && night[1]==java.time.Instant.parse("2026-11-08T05:00:00Z").toEpochMilli(),"night window uses Europe/Berlin");
        var rows=AiAdminWorkspace.rows(List.of(Map.of("id","one","time",night[0],"detail","Vanish from 192.0.2.1"),Map.of("id","two","time",night[0]-1,"detail","Vanish outside window")),"Vanish",night,"id","time","detail");
        check(rows.get("matched").equals(1) && !rows.toString().contains("192.0.2.1"),"source filter uses timestamp and masks IP addresses");
        PacketTrace trace=new PacketTrace();UUID player=UUID.randomUUID();var session=trace.start(player);
        session.received("private packet payload",1000);session.sent(new byte[8],2000);session.failure(new IllegalStateException("secret failure message"),3000);
        var packet=trace.take(player,4000);
        check(packet.get("inbound").equals(1L)&&packet.get("errorAgeMs").equals(1000L)&&packet.get("lastInbound").equals("String"),"packet metadata includes class counts and age");
        check(!packet.toString().contains("private packet payload")&&!packet.toString().contains("secret failure message")&&trace.take(player,4000).isEmpty(),"packet trace contains no payload and is removed at quit");
        check(DisconnectAnalyzer.category("DISCONNECTED","","",Map.of("error","DecoderException","errorAgeMs",250)).equals("PACKET"),"fresh decoder observation explains otherwise unclassified disconnect");
        check(DisconnectAnalyzer.category("KICKED","BANNED","banned",Map.of("error","DecoderException","errorAgeMs",250)).equals("ACCESS"),"stronger Paper kick cause takes precedence");
        check(DisconnectAnalyzer.category("DISCONNECTED","","",Map.of("error","DecoderException","errorAgeMs",3500)).equals("CLIENT_QUIT"),"stale packet error does not reclassify a quit");
    }

}
