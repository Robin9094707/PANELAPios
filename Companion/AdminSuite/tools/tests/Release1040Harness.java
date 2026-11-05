package eu.simplexsmp.adminsuite;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import javax.net.ssl.*;
import org.bukkit.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.*;

/**
 * 10.4.0 Discord advertising planner against a local fake Discord API: timing (never below the interval), channel
 * permissions and hints, encrypted token storage, persistence across restarts, own-post check, slowmode
 * rescheduling, auto-pause with notifications, deletion detection, account problems, relink/unlink and the app/web
 * routes with their rights (not part of any role template).
 */
public final class Release1040Harness {
    static int checks;
    static void check(boolean value, String reason) { checks++; if (!value) throw new AssertionError(reason); }
    static void fails(Callable<?> work, String text, String reason) {
        try { work.call(); } catch (Exception | AssertionError e) { checks++; if (text != null && (e.getMessage() == null || !e.getMessage().contains(text))) throw new AssertionError(reason + " – unexpected message: " + e.getMessage()); return; }
        throw new AssertionError("Expected failure: " + reason);
    }
    @SuppressWarnings("unchecked") static Map<String, Object> obj(String json) { return (Map<String, Object>) DiscordBotManager.Json.parse(json); }
    @SuppressWarnings("unchecked") static Map<String, Object> m(Object o) { return (Map<String, Object>) o; }

    static final String TOKEN = "MTExMTExMTExMTExMTExMTEx." + "a".repeat(40) + ".signature_part-1";
    static final String OTHER = "MjIyMjIyMjIyMjIyMjIyMjIy." + "b".repeat(40) + ".signature_part-2";
    static final String BOT = "Qk9UQk9UQk9UQk9UQk9UQk9U." + "c".repeat(40) + ".signature_part-3";
    static final String USER = "111111111111111111", GUILD = "900000000000000001", ROLE = "800000000000000001";
    static final long BASE = (1L << 10) | (1L << 11) | (1L << 14) | (1L << 16);

    /** Minimal fake of the Discord REST endpoints the planner uses. */
    static final class FakeDiscord {
        final AtomicLong clock;
        final Map<String, String> mode = new ConcurrentHashMap<>();
        final Map<String, List<Map<String, Object>>> messages = new ConcurrentHashMap<>();
        final Map<String, Integer> slowmode = new ConcurrentHashMap<>();
        final List<Map<String, Object>> posts = Collections.synchronizedList(new ArrayList<>());
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        long ids = 1_000_000_000_000_000_000L;
        boolean revoked;
        HttpServer server;
        ExecutorService pool;
        FakeDiscord(AtomicLong clock) { this.clock = clock; }
        String start() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/v9", this::handle);
            pool = Executors.newFixedThreadPool(4, r -> { Thread t = new Thread(r, "FakeDiscord"); t.setDaemon(true); return t; });
            server.setExecutor(pool);
            server.start();
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v9";
        }
        void stop() { server.stop(0); pool.shutdownNow(); }
        static Map<String, Object> ow(String id, int type, long allow, long deny) { return SimplexAdminSuite.appMap("id", id, "type", type, "allow", Long.toString(allow), "deny", Long.toString(deny)); }
        static Map<String, Object> channel(String id, String name, String parent, long slow, List<?> ows, String topic) { return SimplexAdminSuite.appMap("id", id, "type", 0, "name", name, "parent_id", parent, "position", Long.parseLong(id.substring(id.length() - 2)), "rate_limit_per_user", slow, "permission_overwrites", ows, "topic", topic); }
        void handle(HttpExchange x) throws java.io.IOException {
            String path = x.getRequestURI().getPath().substring("/api/v9".length()), query = x.getRequestURI().getQuery() == null ? "" : x.getRequestURI().getQuery();
            String auth = x.getRequestHeaders().getFirst("Authorization"), method = x.getRequestMethod();
            String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            calls.add(method + " " + path);
            int status = 200; Object out;
            String user = TOKEN.equals(auth) && !revoked ? USER : OTHER.equals(auth) ? "222222222222222222" : BOT.equals(auth) ? "333333333333333333" : null;
            if (user == null) { status = 401; out = SimplexAdminSuite.appMap("message", "401: Unauthorized", "code", 0); }
            else if (path.equals("/users/@me")) out = SimplexAdminSuite.appMap("id", user, "username", user.equals(USER) ? "neon_alt" : "someone", "global_name", user.equals(USER) ? "Neon Alt" : null, "avatar", null, "bot", BOT.equals(auth));
            else if (path.equals("/users/@me/guilds")) out = List.of(SimplexAdminSuite.appMap("id", GUILD, "name", "MCAdverts", "icon", "a1b2", "owner", false, "permissions", Long.toString(BASE), "approximate_member_count", 2909));
            else if (path.equals("/users/@me/guilds/" + GUILD + "/member")) out = SimplexAdminSuite.appMap("roles", List.of(ROLE));
            else if (path.equals("/guilds/" + GUILD + "/channels")) {
                List<Object> ch = new ArrayList<>();
                ch.add(SimplexAdminSuite.appMap("id", "700000000000000099", "type", 4, "name", "main", "position", 1, "permission_overwrites", List.of()));
                ch.add(channel("700000000000000001", "server", "700000000000000099", 0, List.of(ow(GUILD, 0, 0, 1L << 11), ow(ROLE, 0, 1L << 11, 0)), ""));
                ch.add(channel("700000000000000002", "content", "700000000000000099", 0, List.of(), "Share your content"));
                ch.add(channel("700000000000000003", "6h", "700000000000000099", slowmode.getOrDefault("700000000000000003", 21600), List.of(), ""));
                ch.add(channel("700000000000000004", "rules", null, 0, List.of(ow(GUILD, 0, 0, 1L << 11)), ""));
                ch.add(channel("700000000000000005", "15m", null, 0, List.of(), ""));
                ch.add(channel("700000000000000006", "1h", null, 0, List.of(), ""));
                ch.add(channel("700000000000000007", "realms", null, 0, List.of(), "One post every 12 hours please"));
                ch.add(channel("700000000000000008", "partners", null, 0, List.of(), ""));
                out = ch;
            } else if (path.equals("/auth/logout")) { status = 204; out = null; }
            else if (path.matches("/channels/\\d+")) { String id = path.substring(10); out = SimplexAdminSuite.appMap("id", id, "rate_limit_per_user", slowmode.getOrDefault(id, 0)); }
            else if (path.matches("/channels/\\d+/messages")) {
                String id = path.split("/")[2];
                List<Map<String, Object>> store = messages.computeIfAbsent(id, k -> Collections.synchronizedList(new ArrayList<>()));
                if (method.equals("GET")) {
                    if (query.startsWith("around=")) { String mid = query.substring(7, query.indexOf('&')); out = store.stream().filter(mm -> mid.equals(mm.get("id"))).toList(); }
                    else { List<Map<String, Object>> copy = new ArrayList<>(store); Collections.reverse(copy); out = copy; }
                } else {
                    String md = mode.getOrDefault(id, "ok");
                    Map<String, Object> payload = obj(body); payload.put("channel", id); payload.put("mode", md); posts.add(payload);
                    switch (md) {
                        case "slowmode" -> { status = 429; out = SimplexAdminSuite.appMap("message", "You are being rate limited.", "retry_after", 600.0, "global", false, "code", 20016); }
                        case "forbidden" -> { status = 403; out = SimplexAdminSuite.appMap("message", "Missing Permissions", "code", 50013); }
                        case "captcha" -> { status = 400; out = SimplexAdminSuite.appMap("captcha_key", List.of("captcha-required"), "captcha_sitekey", "x"); }
                        case "toolong" -> { status = 400; out = SimplexAdminSuite.appMap("message", "Invalid Form Body", "code", 50035); }
                        case "down" -> { status = 502; out = SimplexAdminSuite.appMap("message", "Bad Gateway"); }
                        default -> {
                            Map<String, Object> msg = SimplexAdminSuite.appMap("id", Long.toString(++ids), "author", SimplexAdminSuite.appMap("id", user), "timestamp", Instant.ofEpochMilli(clock.get()).toString(), "content", payload.get("content"));
                            store.add(msg); out = msg;
                        }
                    }
                }
            } else { status = 404; out = SimplexAdminSuite.appMap("message", "Unknown", "code", 0); }
            byte[] bytes = out == null ? new byte[0] : DiscordBotManager.Json.stringify(out).getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().set("Content-Type", "application/json");
            x.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) try (var o = x.getResponseBody()) { o.write(bytes); }
            x.close();
        }
    }

    static final class Recorder implements DiscordAds.Hooks {
        final List<String[]> notes = Collections.synchronizedList(new ArrayList<>());
        final List<String> audits = Collections.synchronizedList(new ArrayList<>());
        public void notify(String title, String body, boolean pause) { notes.add(new String[]{title, body, Boolean.toString(pause)}); }
        public void audit(String actor, String action, String detail) { audits.add(action + " | " + detail); }
        public Map<String, String> placeholders() { return Map.of("{online}", "7", "{max}", "60"); }
        public void warn(String message) { System.out.println("WARN " + message); }
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
        timing();
        hints();
        planner();
        api();
        System.out.println("PASS " + checks + " 10.4.0 Discord advertising planner checks");
    }

    static void timing() {
        Random r = new Random(7);
        long day = 1440, six = 360;
        long[] w = DiscordAds.window(day, 0);
        for (int i = 0; i < 20_000; i++) {
            long d = DiscordAds.delayMillis(day, 0, r);
            check(d > day * 60_000L && d >= w[0] && d <= w[1], "24 h: always a little more, never less: " + d);
            check(d >= 24.09 * 3_600_000L && d <= 24.49 * 3_600_000L, "24 h becomes about 24.1–24.5 h: " + d / 3_600_000.0);
            long s = DiscordAds.delayMillis(six, 0, r);
            check(s > six * 60_000L && s <= six * 60_000L + 8 * 60_000L, "6 h stays slightly above 6 h: " + s);
            long f = DiscordAds.delayMillis(5, 0, r);
            check(f > 5 * 60_000L && f <= 6 * 60_000L + 1, "5 min stays within one extra minute: " + f);
        }
        check(DiscordAds.delayMillis(60, 7200, new Random(1)) > 7_200_000L, "a longer channel slowmode wins over the interval");
        check(DiscordAds.window(60, 0)[0] > 3_600_000L, "window starts above the interval");
        check(DiscordAds.human(1440).equals("1 Tag") && DiscordAds.human(360).equals("6 Std") && DiscordAds.human(15).equals("15 Min"), "human durations");
    }

    static void hints() {
        check(DiscordAds.suggestMinutes("6h", "") == 360 && DiscordAds.suggestMinutes("15m", null) == 15 && DiscordAds.suggestMinutes("1h", "") == 60, "interval from channel names like the MCAdverts timed channels");
        check(DiscordAds.suggestMinutes("ads-24h", "") == 1440 && DiscordAds.suggestMinutes("5m", "") == 5 && DiscordAds.suggestMinutes("2d-ads", "") == 2880, "more name forms");
        check(DiscordAds.suggestMinutes("instant", "") == null && DiscordAds.suggestMinutes("server", "") == null, "no suggestion without a duration");
        check(DiscordAds.suggestMinutes("realms", "One post every 12 hours please") == 720 && DiscordAds.suggestMinutes("realms", "Alle 6 Stunden ein Post") == 360, "interval from the topic");
        check(DiscordAds.suggestMinutes("general", "We have 50 members") == null, "numbers without a rule word are ignored");
        check(DiscordAds.channelHint("server").equals("ad") && DiscordAds.channelHint("6h").equals("ad") && DiscordAds.channelHint("self-promo").equals("ad") && DiscordAds.channelHint("werbung").equals("ad"), "ad channels recognised");
        check(DiscordAds.channelHint("rules").equals("warn") && DiscordAds.channelHint("general-chat").equals("warn") && DiscordAds.channelHint("welcome").equals("warn") && DiscordAds.channelHint("mod-log").equals("warn"), "chat/info channels warned");
        check(DiscordAds.channelHint("random").equals("neutral"), "neutral channel");
        Set<String> roles = Set.of(ROLE);
        check((DiscordAds.channelPermissions(BASE, false, GUILD, USER, roles, List.of(FakeDiscord.ow(GUILD, 0, 0, 1L << 11), FakeDiscord.ow(ROLE, 0, 1L << 11, 0))) & (1L << 11)) != 0, "role allow beats @everyone deny");
        check((DiscordAds.channelPermissions(BASE, false, GUILD, USER, Set.of(), List.of(FakeDiscord.ow(GUILD, 0, 0, 1L << 11), FakeDiscord.ow(ROLE, 0, 1L << 11, 0))) & (1L << 11)) == 0, "role allow only for members with the role");
        check((DiscordAds.channelPermissions(BASE, false, GUILD, USER, roles, List.of(FakeDiscord.ow(ROLE, 0, 1L << 11, 0), FakeDiscord.ow(USER, 1, 0, 1L << 11))) & (1L << 11)) == 0, "member deny is final");
        check(DiscordAds.channelPermissions(1L << 3, false, GUILD, USER, roles, List.of(FakeDiscord.ow(GUILD, 0, 0, -1L))) == -1L && DiscordAds.channelPermissions(0, true, GUILD, USER, roles, List.of()) == -1L, "administrator and owner can do everything");
        fails(() -> DiscordAds.cleanToken("Bot " + TOKEN), "Bot-Token", "bot tokens refused");
        fails(() -> DiscordAds.cleanToken("hello"), "Discord-Token", "garbage refused");
        check(DiscordAds.cleanToken("  \"" + TOKEN + "\" ").equals(TOKEN), "quotes and spaces removed");
        check(DiscordAds.SIMPLEX_AD.startsWith("# 🌍 **SIMPLEX SMP**") && DiscordAds.SIMPLEX_AD.endsWith("https://discord.gg/2gAsymnXfd") && DiscordAds.SIMPLEX_AD.length() <= DiscordAds.DISCORD_LIMIT
            && DiscordAds.SIMPLEX_AD_DE.length() <= DiscordAds.DISCORD_LIMIT && DiscordAds.SIMPLEX_AD_SHORT.length() <= DiscordAds.DISCORD_LIMIT, "shipped ad texts fit Discord's limit");
    }

    static DiscordAds open(Path dir, Recorder rec, String api, AtomicLong clock) throws Exception { return new DiscordAds(dir, rec, api, clock::get, new Random(42)); }

    @SuppressWarnings("unchecked")
    static Map<String, Object> campaign(DiscordAds ads, String id) {
        for (Object o : (List<?>) ads.overview().get("campaigns")) if (id.equals(m(o).get("id"))) return m(o);
        throw new AssertionError("campaign " + id + " missing");
    }
    static long lng(Object o) { return ((Number) o).longValue(); }

    @SuppressWarnings("unchecked")
    static void planner() throws Exception {
        AtomicLong clock = new AtomicLong(Instant.parse("2026-11-04T12:00:00Z").toEpochMilli());
        FakeDiscord discord = new FakeDiscord(clock);
        String api = discord.start();
        Path dir = Files.createTempDirectory("ads1040-").resolve("discord-ads");
        Recorder rec = new Recorder();
        try {
            DiscordAds ads = open(dir, rec, api, clock);
            Map<String, Object> view = ads.overview();
            List<Map<String, Object>> templates = (List<Map<String, Object>>) view.get("templates");
            check(templates.size() == 3 && templates.get(0).get("content").equals(DiscordAds.SIMPLEX_AD) && Boolean.TRUE.equals(templates.get(0).get("preset")), "three named ad texts are shipped, the first is the given Simplex ad");
            check(((List<?>) view.get("accounts")).isEmpty() && ((List<?>) view.get("campaigns")).isEmpty() && Files.isRegularFile(dir.resolve("state.json")) && Files.isRegularFile(dir.resolve("secret.key")), "fresh state");
            String tplId = String.valueOf(templates.get(0).get("id"));

            // ---- accounts
            fails(() -> ads.link(TOKEN, null, false, "juhas"), "Nutzungsbedingungen", "linking needs the terms notice");
            fails(() -> ads.link("x".repeat(70), null, true, "juhas"), "abgelehnt", "invalid token refused");
            fails(() -> ads.link(BOT, null, true, "juhas"), "Bot-Accounts", "bot account refused");
            Map<String, Object> acc = ads.link(TOKEN, null, true, "juhas");
            String accId = String.valueOf(acc.get("id"));
            check("Neon Alt".equals(acc.get("name")) && "ok".equals(acc.get("status")) && String.valueOf(acc.get("tokenHint")).startsWith("••••") && String.valueOf(acc.get("avatar")).startsWith("https://cdn.discordapp.com/embed/avatars/"), "account linked: " + acc);
            String stored = Files.readString(dir.resolve("state.json"));
            check(!stored.contains(TOKEN) && !stored.contains("signature_part-1") && stored.contains("\"token\":\"v1:"), "token stored encrypted only");
            check(!DiscordBotManager.Json.stringify(ads.overview()).contains("signature_part-1"), "token never in the overview");
            check(ads.link(TOKEN, null, true, "juhas").get("id").equals(accId), "linking the same user again updates the existing account");
            fails(() -> ads.link(OTHER, accId, true, "juhas"), "gehört zu", "re-link with another user's token is refused");

            // ---- guilds & channels
            List<Map<String, Object>> guilds = ads.guilds(accId, false);
            check(guilds.size() == 1 && "MCAdverts".equals(guilds.get(0).get("name")) && String.valueOf(guilds.get(0).get("icon")).contains("/icons/" + GUILD + "/a1b2"), "joined servers listed: " + guilds);
            Map<String, Object> chData = ads.channels(accId, GUILD);
            Map<String, Map<String, Object>> chans = new HashMap<>();
            for (Object o : (List<?>) chData.get("channels")) chans.put(String.valueOf(m(o).get("name")), m(o));
            check(Boolean.TRUE.equals(chans.get("server").get("canSend")) && "main".equals(chans.get("server").get("category")), "role overwrite allows #server");
            check(Boolean.FALSE.equals(chans.get("rules").get("canSend")) && "warn".equals(chans.get("rules").get("hint")), "#rules is read-only and warned");
            check(lng(chans.get("6h").get("suggestedMinutes")) == 360 && lng(chans.get("6h").get("slowmode")) == 21600 && "ad".equals(chans.get("6h").get("hint")), "#6h: suggested interval and slowmode");
            check(lng(chans.get("realms").get("suggestedMinutes")) == 720, "topic rule suggests 12 h");
            check(lng(chData.get("limit")) == 4 && Boolean.TRUE.equals(chData.get("permissionsCertain")), "limit 4 per server");

            // ---- campaigns
            Map<String, Object> base = new HashMap<>(Map.of("account", accId, "guild", GUILD, "template", tplId, "intervalMinutes", 360, "firstPost", "soon"));
            Map<String, Object> noConfirm = new HashMap<>(base); noConfirm.put("channel", chans.get("6h").get("id"));
            fails(() -> ads.saveCampaign(noConfirm, "juhas"), "erlaubt", "the channel must be confirmed");
            Map<String, Object> rules = new HashMap<>(base); rules.put("channel", chans.get("rules").get("id")); rules.put("confirm", true);
            fails(() -> ads.saveCampaign(rules, "juhas"), "nicht schreiben", "no campaign where the account cannot write");
            Map<String, Object> tooFast = new HashMap<>(base); tooFast.put("channel", chans.get("15m").get("id")); tooFast.put("confirm", true); tooFast.put("intervalMinutes", 2);
            fails(() -> ads.saveCampaign(tooFast, "juhas"), "5 Minuten", "interval below 5 min refused");
            String[] names = {"6h", "server", "1h", "15m"};
            Map<String, String> ids = new HashMap<>();
            for (String n : names) {
                Map<String, Object> b = new HashMap<>(base); b.put("channel", chans.get(n).get("id")); b.put("confirm", true);
                b.put("intervalMinutes", n.equals("15m") ? 15 : n.equals("1h") ? 60 : 360);
                Map<String, Object> saved = ads.saveCampaign(b, "juhas");
                ids.put(n, String.valueOf(m(saved.get("campaign")).get("id")));
            }
            Map<String, Object> fifth = new HashMap<>(base); fifth.put("channel", chans.get("content").get("id")); fifth.put("confirm", true);
            fails(() -> ads.saveCampaign(fifth, "juhas"), "Höchstens 4", "fifth channel on one server refused");
            Map<String, Object> dup = new HashMap<>(base); dup.put("id", ids.get("1h")); dup.put("channel", chans.get("6h").get("id")); dup.put("confirm", true);
            fails(() -> ads.saveCampaign(dup, "juhas"), "schon eine Planung", "one plan per channel");
            check(rec.audits.stream().filter(a -> a.contains("Kanal geplant")).count() == 4, "every new channel audited");
            Map<String, Object> six = campaign(ads, ids.get("6h"));
            check(lng(six.get("effectiveMinutes")) == 360 && (double) six.get("windowFromMinutes") > 360 && lng(six.get("nextRunAt")) > clock.get() && lng(six.get("nextRunAt")) <= clock.get() + 120_001, "first post soon, window above the interval: " + six);
            fails(() -> ads.deleteTemplate(tplId, "juhas"), "verwendet", "a used ad text cannot be deleted");

            // ---- first posts
            // only #6h, #server, #1h are due first; one post per account and tick, with a gap in between
            discord.mode.put(chans.get("15m").get("id").toString(), "ok");
            clock.addAndGet(130_000L);
            ads.tick();
            check(discord.posts.size() == 1, "one post per account per tick: " + discord.posts.size());
            Map<String, Object> firstPost = discord.posts.get(0);
            check(Boolean.TRUE.equals(firstPost.get("enforce_nonce")) && String.valueOf(firstPost.get("nonce")).matches("\\d{15,20}") && ((List<?>) m(firstPost.get("allowed_mentions")).get("parse")).isEmpty(), "nonce for duplicate protection and no pings: " + firstPost);
            ads.tick();
            check(discord.posts.size() == 1, "the gap between two posts of the same account is kept");
            for (int i = 0; i < 12; i++) { clock.addAndGet(60_000L); ads.tick(); }
            check(discord.posts.size() == 4, "all four channels posted after the gaps: " + discord.posts.size());
            Map<String, Object> afterSix = campaign(ads, ids.get("6h"));
            long sentAt = lng(afterSix.get("lastSentAt")), next = lng(afterSix.get("nextRunAt"));
            check("sent".equals(afterSix.get("lastResult")) && lng(afterSix.get("sentCount")) == 1 && next > sentAt + 360 * 60_000L && next <= sentAt + 368 * 60_000L, "next post a little after 6 h: " + (next - sentAt) / 60_000.0);
            check(((List<?>) afterSix.get("history")).stream().anyMatch(h -> "sent".equals(m(h).get("kind"))), "history records the post");

            // ---- restart keeps timers
            ads.stop();
            DiscordAds again = open(dir, rec, api, clock);
            again.resumeAfterStart();
            check(lng(campaign(again, ids.get("6h")).get("nextRunAt")) == next, "a restart does not reset future timers");
            check("ok".equals(m(((List<?>) again.overview().get("accounts")).get(0)).get("status")), "token decrypts after restart");
            again.stop();
            clock.set(next + 3_600_000L); // server was offline for an hour past the due time
            DiscordAds late = open(dir, rec, api, clock);
            late.resumeAfterStart();
            Map<String, Object> resumed = campaign(late, ids.get("6h"));
            check(lng(resumed.get("nextRunAt")) >= clock.get() + 60_000L && lng(resumed.get("nextRunAt")) < clock.get() + 600_000L
                && String.valueOf(resumed.get("history")).contains("offline"), "overdue post is caught up shortly after the restart: " + resumed.get("nextRunAt"));
            DiscordAds ads2 = late;

            // ---- own post check: a manual post 1 h ago postpones
            String ch1h = String.valueOf(chans.get("1h").get("id"));
            List<Map<String, Object>> store1h = discord.messages.get(ch1h);
            store1h.add(SimplexAdminSuite.appMap("id", "1999999999999999999", "author", SimplexAdminSuite.appMap("id", USER), "timestamp", Instant.ofEpochMilli(clock.get() - 20 * 60_000L).toString()));
            int before = discord.posts.size();
            Map<String, Object> c1h = campaign(ads2, ids.get("1h"));
            check(lng(c1h.get("nextRunAt")) <= clock.get() + 600_000L, "1h channel due soon");
            clock.addAndGet(600_000L);
            for (int i = 0; i < 6; i++) { ads2.tick(); clock.addAndGet(90_000L); }
            Map<String, Object> postponed = campaign(ads2, ids.get("1h"));
            check(String.valueOf(postponed.get("history")).contains("erst") && lng(postponed.get("nextRunAt")) > clock.get() + 15 * 60_000L, "own recent post postpones the 1h channel: " + postponed.get("history"));
            check(discord.posts.stream().skip(before).noneMatch(p -> ch1h.equals(p.get("channel"))), "no post into the 1h channel while its own post is recent");

            // ---- slowmode: rescheduled, not a failure
            String ch15 = String.valueOf(chans.get("15m").get("id"));
            discord.mode.put(ch15, "slowmode"); discord.slowmode.put(ch15, 1800);
            clock.addAndGet(20 * 60_000L);
            for (int i = 0; i < 3; i++) { ads2.tick(); clock.addAndGet(70_000L); }
            Map<String, Object> slow = campaign(ads2, ids.get("15m"));
            check("slowmode".equals(slow.get("lastResult")) && lng(slow.get("consecutiveFailures")) == 0 && lng(slow.get("slowmodeSeconds")) == 1800 && lng(slow.get("slowmodeCorrections")) == 1, "slowmode detected and adopted: " + slow);
            check(lng(slow.get("effectiveMinutes")) == 30 && String.valueOf(slow.get("history")).contains("länger als dein Intervall"), "longer channel slowmode is kept automatically");
            check(rec.notes.isEmpty(), "slowmode does not notify");

            // ---- missing permission: notify, then auto-pause after 3
            String chServer = String.valueOf(chans.get("server").get("id"));
            discord.mode.put(chServer, "forbidden");
            for (int round = 0; round < 3; round++) {
                Map<String, Object> c = campaign(ads2, ids.get("server"));
                clock.set(Math.max(clock.get(), lng(c.get("nextRunAt"))) + 1_000L);
                long failedBefore = lng(c.get("failedCount"));
                for (int i = 0; i < 12 && lng(campaign(ads2, ids.get("server")).get("failedCount")) == failedBefore; i++) { ads2.tick(); clock.addAndGet(70_000L); }
            }
            Map<String, Object> paused = campaign(ads2, ids.get("server"));
            check(Boolean.FALSE.equals(paused.get("enabled")) && "paused".equals(paused.get("state")) && String.valueOf(paused.get("pausedReason")).contains("3 Fehlern"), "auto-paused after three failures: " + paused);
            List<String[]> serverNotes = rec.notes.stream().filter(n -> n[1].contains("#server")).toList();
            check(serverNotes.size() == 3 && serverNotes.get(0)[0].contains("fehlgeschlagen") && serverNotes.get(1)[0].contains("fehlgeschlagen") && serverNotes.get(2)[0].contains("pausiert") && serverNotes.get(2)[2].equals("true"),
                "two failure notices, then the pause notice: " + rec.notes.stream().map(n -> n[0] + " / " + n[1]).toList());
            int postsWhilePaused = discord.posts.size();
            clock.addAndGet(48 * 3_600_000L); ads2.tick();
            check(discord.posts.stream().skip(postsWhilePaused).noneMatch(p -> chServer.equals(p.get("channel"))), "paused channel stays silent");
            discord.mode.put(chServer, "ok");
            Map<String, Object> resumedServer = m(ads2.campaignAction(ids.get("server"), "resume", "juhas").get("campaign"));
            check(Boolean.TRUE.equals(resumedServer.get("enabled")) && lng(resumedServer.get("consecutiveFailures")) == 0, "resume clears the failure streak");

            // ---- deletion by a timer bot
            String ch6 = String.valueOf(chans.get("6h").get("id"));
            discord.slowmode.put(ch6, 0);
            Map<String, Object> s6 = campaign(ads2, ids.get("6h"));
            clock.set(Math.max(clock.get(), lng(s6.get("nextRunAt"))) + 1_000L);
            for (int i = 0; i < 15; i++) { ads2.tick(); if ("sent".equals(campaign(ads2, ids.get("6h")).get("lastResult")) && lng(campaign(ads2, ids.get("6h")).get("lastSentAt")) > lng(s6.get("lastSentAt"))) break; clock.addAndGet(70_000L); }
            Map<String, Object> sent6 = campaign(ads2, ids.get("6h"));
            String mid = String.valueOf(sent6.get("lastMessageId"));
            check(!mid.isBlank() && lng(sent6.get("lastSentAt")) > lng(s6.get("lastSentAt")), "6h channel posted again");
            discord.messages.get(ch6).removeIf(x -> mid.equals(x.get("id")));
            int notes = rec.notes.size();
            clock.addAndGet(200_000L); ads2.tick();
            Map<String, Object> deleted = campaign(ads2, ids.get("6h"));
            check(lng(deleted.get("deletedCount")) == 1 && "deleted".equals(deleted.get("lastResult")) && lng(deleted.get("consecutiveFailures")) == 1 && rec.notes.size() == notes + 1, "removed post detected and reported: " + deleted);
            check(lng(deleted.get("nextRunAt")) >= clock.get() + 80 * 60_000L, "retry after a quarter of the interval, not immediately");
            // Repeated deletions must keep the streak (a successful send alone does not end it) and pause after three.
            for (int cycle = 2; cycle <= 3; cycle++) {
                long sentBefore = lng(campaign(ads2, ids.get("6h")).get("sentCount"));
                clock.set(Math.max(clock.get(), lng(campaign(ads2, ids.get("6h")).get("nextRunAt"))) + 1_000L);
                for (int i = 0; i < 15 && lng(campaign(ads2, ids.get("6h")).get("sentCount")) == sentBefore; i++) { ads2.tick(); clock.addAndGet(70_000L); }
                Map<String, Object> again6 = campaign(ads2, ids.get("6h"));
                check(lng(again6.get("sentCount")) == sentBefore + 1 && lng(again6.get("consecutiveFailures")) == cycle - 1, "sending keeps the deletion streak until verified: " + again6.get("consecutiveFailures"));
                String gone = String.valueOf(again6.get("lastMessageId"));
                discord.messages.get(ch6).removeIf(x -> gone.equals(x.get("id")));
                clock.addAndGet(200_000L); ads2.tick();
            }
            Map<String, Object> pausedByBot = campaign(ads2, ids.get("6h"));
            check(Boolean.FALSE.equals(pausedByBot.get("enabled")) && lng(pausedByBot.get("deletedCount")) == 3 && String.valueOf(pausedByBot.get("pausedReason")).contains("3 Fehlern"), "three removed posts in a row pause the channel: " + pausedByBot);
            ads2.campaignAction(ids.get("6h"), "resume", "juhas");
            // A post that stays visible ends the streak.
            long sentBeforeOk = lng(campaign(ads2, ids.get("6h")).get("sentCount"));
            clock.set(Math.max(clock.get(), lng(campaign(ads2, ids.get("6h")).get("nextRunAt"))) + 1_000L);
            for (int i = 0; i < 15 && lng(campaign(ads2, ids.get("6h")).get("sentCount")) == sentBeforeOk; i++) { ads2.tick(); clock.addAndGet(70_000L); }
            clock.addAndGet(200_000L); ads2.tick();
            check(lng(campaign(ads2, ids.get("6h")).get("consecutiveFailures")) == 0 && lng(campaign(ads2, ids.get("6h")).get("deletedCount")) == 3, "a verified post ends the streak");

            // ---- send now respects the interval
            fails(() -> ads2.campaignAction(ids.get("1h"), "send-now", "juhas"), "Frühestens", "send-now never undercuts the interval");

            // ---- account problems
            discord.mode.put(ch6, "captcha");
            Map<String, Object> d6 = campaign(ads2, ids.get("6h"));
            clock.set(Math.max(clock.get(), lng(d6.get("nextRunAt"))) + 1_000L);
            for (int i = 0; i < 15 && "ok".equals(m(((List<?>) ads2.overview().get("accounts")).get(0)).get("status")); i++) { ads2.tick(); clock.addAndGet(70_000L); }
            Map<String, Object> accView = m(((List<?>) ads2.overview().get("accounts")).get(0));
            check("captcha".equals(accView.get("status")) && "account".equals(campaign(ads2, ids.get("6h")).get("state")), "captcha blocks the account: " + accView);
            discord.mode.put(ch6, "ok");
            check("ok".equals(ads2.checkAccount(accId, "juhas").get("status")), "check clears the captcha state");
            discord.revoked = true;
            fails(() -> ads2.checkAccount(accId, "juhas"), "ungültig", "revoked token detected");
            check("invalid".equals(m(((List<?>) ads2.overview().get("accounts")).get(0)).get("status")), "account marked invalid");
            fails(() -> ads2.guilds(accId, true), "neu verknüpft", "invalid account cannot list servers");
            discord.revoked = false;
            check("ok".equals(ads2.link(TOKEN, accId, true, "juhas").get("status")), "re-link restores the account");

            // ---- unlink keeps plans paused, linking the same user resumes them
            Map<String, Object> unlinked = ads2.unlink(accId, true, false, "juhas");
            check(String.valueOf(unlinked.get("notice")).contains("pausiert") && discord.calls.contains("POST /auth/logout"), "unlink pauses and logs out: " + unlinked);
            check(((List<?>) ads2.overview().get("accounts")).isEmpty() && ((List<?>) ads2.overview().get("campaigns")).size() == 4, "plans kept");
            Map<String, Object> relinked = ads2.link(TOKEN, null, true, "juhas");
            check(lng(relinked.get("reattached")) == 4 && campaign(ads2, ids.get("6h")).get("account").equals(relinked.get("id")), "same Discord user gets its plans back");
            check(Boolean.FALSE.equals(campaign(ads2, ids.get("6h")).get("enabled")), "re-attached plans stay paused until resumed");

            // ---- templates
            fails(() -> ads2.saveTemplate(new HashMap<>(Map.of("name", "", "content", "x")), "juhas"), "Namen", "name required");
            fails(() -> ads2.saveTemplate(new HashMap<>(Map.of("name", "Lang", "content", "x".repeat(4001))), "juhas"), "4000", "content limit");
            Map<String, Object> t = m(ads2.saveTemplate(new HashMap<>(Map.of("name", "Kurz & knapp", "content", "**Simplex** – {online}/{max} online", "suppressEmbeds", true)), "juhas").get("template"));
            check(Boolean.TRUE.equals(t.get("suppressEmbeds")) && ads2.render("{online}/{max}").equals("7/60"), "own template with placeholders");
            ads2.deleteTemplate(String.valueOf(t.get("id")), "juhas");
            check(((List<?>) ads2.overview().get("templates")).size() == 3, "unused template deleted");
            ads2.campaignAction(ids.get("15m"), "delete", "juhas");
            check(((List<?>) ads2.overview().get("campaigns")).size() == 3, "plan deleted");
            ads2.stop();
        } finally { discord.stop(); }
    }

    // ------------------------------------------------------------------ app/web routes and rights

    @SuppressWarnings("unchecked")
    static void api() throws Exception {
        Path dir = Files.createTempDirectory("suite1040-");
        Field serverField = Bukkit.class.getDeclaredField("server"); serverField.setAccessible(true);
        AppApiManager api = null;
        AtomicLong clock = new AtomicLong(System.currentTimeMillis());
        FakeDiscord discord = new FakeDiscord(clock);
        String fake = discord.start();
        try {
            BukkitTask task = AppApiIntegrationHarness.proxy(BukkitTask.class, (p, mm, a) -> AppApiIntegrationHarness.empty(mm.getReturnType()));
            BukkitScheduler scheduler = AppApiIntegrationHarness.proxy(BukkitScheduler.class, (p, mm, a) -> { if (a != null) for (Object item : a) { if (item instanceof Runnable work) work.run(); else if (item instanceof Consumer<?> c) ((Consumer<BukkitTask>) c).accept(task); } return mm.getReturnType() == BukkitTask.class ? task : AppApiIntegrationHarness.empty(mm.getReturnType()); });
            org.bukkit.plugin.PluginManager plugins = AppApiIntegrationHarness.proxy(org.bukkit.plugin.PluginManager.class, (p, mm, a) -> switch (mm.getName()) { case "getPlugins" -> new org.bukkit.plugin.Plugin[0]; case "getPermissions" -> Set.of(); default -> AppApiIntegrationHarness.empty(mm.getReturnType()); });
            var console = AppApiIntegrationHarness.proxy(org.bukkit.command.ConsoleCommandSender.class, (p, mm, a) -> switch (mm.getName()) { case "getName" -> "Console"; case "hasPermission", "isOp", "isPermissionSet" -> true; default -> AppApiIntegrationHarness.empty(mm.getReturnType()); });
            Server server = AppApiIntegrationHarness.proxy(Server.class, (p, mm, a) -> switch (mm.getName()) {
                case "getName", "getVersion", "getBukkitVersion", "getMinecraftVersion" -> "Harness1040";
                case "getPluginManager" -> plugins; case "getScheduler" -> scheduler; case "getConsoleSender" -> console;
                case "getLogger" -> java.util.logging.Logger.getLogger("Harness1040"); case "isPrimaryThread" -> true;
                case "getOnlinePlayers" -> List.of(); case "getWorlds" -> List.of(); case "getOperators" -> Set.of(); case "getTPS" -> new double[]{20, 20, 20}; case "getWorldContainer" -> dir.toFile(); case "getMaxPlayers" -> 60;
                default -> AppApiIntegrationHarness.empty(mm.getReturnType()); });
            serverField.set(null, server);
            SimplexAdminSuite plugin = Moderation790Harness.plugin(dir); Moderation790Harness.set(plugin, JavaPlugin.class, "isEnabled", true);
            Moderation790Harness.set(plugin, SimplexAdminSuite.class, "adminActor", new ThreadLocal<String>()); Moderation790Harness.set(plugin, SimplexAdminSuite.class, "pendingInputs", new ConcurrentHashMap<>()); Moderation790Harness.set(plugin, SimplexAdminSuite.class, "pendingActions", new ConcurrentHashMap<>());
            Moderation790Harness.set(plugin, SimplexAdminSuite.class, "auditedConfig", new HashMap<String, String>()); plugin.moderationControl = new ModerationControl(plugin);
            plugin.adminAccess = new AdminAccessManager(plugin); plugin.adminUpdate = new AdminUpdateMenu(plugin); plugin.adminLog = new AdminActivityLog(plugin);
            Moderation790Harness.set(plugin, JavaPlugin.class, "description", new org.bukkit.plugin.PluginDescriptionFile("SimplexAdminSuite", "10.4.0", SimplexAdminSuite.class.getName()));
            var dbConstructor = SimplexAdminSuite.PlayerDatabase.class.getDeclaredConstructor(SimplexAdminSuite.class); dbConstructor.setAccessible(true); Moderation790Harness.set(plugin, SimplexAdminSuite.class, "database", dbConstructor.newInstance(plugin));
            plugin.getConfig().setDefaults(org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(Release1040Harness.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8)));
            check(plugin.getConfig().getBoolean("discord-ads.enabled"), "planner enabled by default");
            plugin.appEvents = new SuiteEventCenter(plugin);
            check(SuiteEventCenter.TYPES.contains("discord-ads") && PanelHub.EVENT_TITLES.containsKey("discord-ads"), "Bark/browser push know the discord-ads event");
            Recorder rec = new Recorder();
            plugin.discordAds = new DiscordAds(dir.resolve("discord-ads"), rec, fake, clock::get, new Random(3));

            int port; try (var socket = new java.net.ServerSocket(0)) { port = socket.getLocalPort(); }
            plugin.getConfig().set("app-api.enabled", true); plugin.getConfig().set("app-api.bind", "127.0.0.1"); plugin.getConfig().set("app-api.port", port); plugin.getConfig().set("app-api.transport", "tls");
            api = new AppApiManager(plugin); plugin.appApi = api; api.reload();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30); while (!api.active() && System.nanoTime() < deadline) Thread.sleep(50); check(api.active(), "TLS listener started");
            SSLContext ssl = SSLContext.getInstance("TLS"); ssl.init(null, new TrustManager[]{new X509TrustManager() { public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; } public void checkClientTrusted(X509Certificate[] c, String a) { } public void checkServerTrusted(X509Certificate[] c, String a) { } }}, null);
            HttpClient client = HttpClient.newBuilder().sslContext(ssl).connectTimeout(java.time.Duration.ofSeconds(5)).build(); URI base = URI.create("https://127.0.0.1:" + port);
            api.security.users.createOwner("juhas", "owner-password-1040");
            api.security.users.save("watcher", "watcher-password-1040", Set.of("data.discord-ads"), true);
            api.security.users.save("viewer", "viewer-password-1040", Set.of("data.profile"), true);
            String owner = Release1020Harness.login(client, base, "juhas", "owner-password-1040"), watcher = Release1020Harness.login(client, base, "watcher", "watcher-password-1040"), viewer = Release1020Harness.login(client, base, "viewer", "viewer-password-1040");

            List<?> features = (List<?>) obj(Release1020Harness.call(client, base, "/v1/health", owner, null).body()).get("features");
            check(features.contains("discord-ads-v1") && features.contains("scoreboard-designer-v1"), "health advertises discord-ads-v1 next to the older features");
            Map<String, Object> catalog = obj(Release1020Harness.call(client, base, "/v1/data/access", owner, null).body());
            List<String> rightIds = ((List<Map<String, Object>>) catalog.get("catalog")).stream().map(r -> String.valueOf(r.get("id"))).toList();
            check(rightIds.containsAll(List.of("data.discord-ads", "discord-ads.manage")), "rights catalog lists the planner rights");
            check(((List<Map<String, Object>>) catalog.get("catalog")).stream().anyMatch(r -> "discord-ads.manage".equals(r.get("id")) && "critical".equals(r.get("level"))), "manage right marked critical");
            for (Map<String, Object> preset : (List<Map<String, Object>>) catalog.get("presets")) if (!"full".equals(preset.get("id")))
                check(((List<?>) preset.get("permissions")).stream().noneMatch(p -> String.valueOf(p).contains("discord-ads")), "role template " + preset.get("id") + " does not contain the planner rights");

            check(Release1020Harness.call(client, base, "/v1/discord-ads", viewer, null).statusCode() == 403, "viewing needs data.discord-ads");
            var watch = Release1020Harness.call(client, base, "/v1/discord-ads", watcher, null);
            check(watch.statusCode() == 200 && Boolean.FALSE.equals(obj(watch.body()).get("canManage")) && ((List<?>) obj(watch.body()).get("templates")).size() == 3, "watcher reads the planner: " + watch.body());
            check(Release1020Harness.call(client, base, "/v1/discord-ads", watcher, Map.of("operation", "template.save", "name", "X", "content", "Y")).statusCode() == 403, "changes need discord-ads.manage");
            var noAccept = Release1020Harness.call(client, base, "/v1/discord-ads", owner, Map.of("operation", "link", "token", TOKEN));
            check(noAccept.statusCode() == 400 && noAccept.body().contains("Nutzungsbedingungen"), "link needs the terms confirmation");
            var linked = Release1020Harness.call(client, base, "/v1/discord-ads", owner, Map.of("operation", "link", "token", TOKEN, "accept", true));
            check(linked.statusCode() == 200 && !linked.body().contains("signature_part-1") && ((List<?>) m(obj(linked.body()).get("overview")).get("accounts")).size() == 1, "link via API; the answer never contains the token: " + linked.body());
            String accId = String.valueOf(obj(linked.body()).get("id"));
            var guilds = Release1020Harness.call(client, base, "/v1/discord-ads/guilds?account=" + accId, watcher, null);
            check(guilds.statusCode() == 200 && guilds.body().contains("MCAdverts"), "server list for watchers");
            var chans = Release1020Harness.call(client, base, "/v1/discord-ads/channels?account=" + accId + "&guild=" + GUILD, owner, null);
            check(chans.statusCode() == 200 && chans.body().contains("\"suggestedMinutes\":360"), "channel list via API");
            String tpl = String.valueOf(m(((List<?>) obj(watch.body()).get("templates")).get(0)).get("id"));
            var plan = Release1020Harness.call(client, base, "/v1/discord-ads", owner, Map.of("operation", "campaign.save", "account", accId, "guild", GUILD, "channel", "700000000000000003", "template", tpl, "intervalMinutes", 360, "confirm", true));
            check(plan.statusCode() == 200 && String.valueOf(obj(plan.body()).get("notice")).contains("nächster Post"), "plan via API: " + plan.body());
            check(Release1020Harness.call(client, base, "/v1/discord-ads", owner, Map.of("operation", "explode")).statusCode() == 400, "unknown operation");
            plugin.discordAds.stop();
            plugin.discordAds = null;
            check(Release1020Harness.call(client, base, "/v1/discord-ads", owner, null).statusCode() == 503, "clear 503 when the planner is off");
        } finally {
            if (api != null) api.stop();
            discord.stop();
        }
    }
}
