package eu.simplexsmp.adminsuite;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import javax.net.ssl.*;
import org.bukkit.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.*;

/**
 * 10.5.0 Werbe-Zentrale: timing (never below the interval, 6 h ≈ 6.1 h), formats and conversion, migration of the
 * 10.4.0 Discord planner without tokens, Bark reminders with repeats and snooze, the HMAC-bound Bark link (open,
 * repeated tap, expiry, wrong signature), persistence across restarts and the app/web routes with their rights.
 */
public final class Release1050Harness {
    static int checks;
    static void check(boolean value, String reason) { checks++; if (!value) throw new AssertionError(reason); }
    static void fails(Callable<?> work, String text, String reason) {
        try { work.call(); } catch (Exception | AssertionError e) { checks++; if (text != null && (e.getMessage() == null || !e.getMessage().contains(text))) throw new AssertionError(reason + " – unexpected message: " + e.getMessage()); return; }
        throw new AssertionError("Expected failure: " + reason);
    }
    @SuppressWarnings("unchecked") static Map<String, Object> obj(String json) { return (Map<String, Object>) DiscordBotManager.Json.parse(json); }
    @SuppressWarnings("unchecked") static Map<String, Object> m(Object o) { return (Map<String, Object>) o; }
    @SuppressWarnings("unchecked") static List<Map<String, Object>> l(Object o) { return (List<Map<String, Object>>) o; }

    static final class Recorder implements AdHub.Hooks {
        final List<String[]> reminders = Collections.synchronizedList(new ArrayList<>());
        final List<String> audits = Collections.synchronizedList(new ArrayList<>()), warnings = Collections.synchronizedList(new ArrayList<>());
        public void remind(String owner, String title, String body, String appUrl, String webUrl, String copy) { reminders.add(new String[]{owner, title, body, appUrl, webUrl, copy}); }
        public void audit(String actor, String action, String detail) { audits.add(action + " | " + detail); }
        public Map<String, String> placeholders() { return Map.of("{online}", "7", "{max}", "60"); }
        public void warn(String message) { warnings.add(message); }
        public String serverId() { return "srv-1050"; }
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
        timing();
        formats();
        migration();
        reminders();
        api();
        System.out.println("PASS " + checks + " 10.5.0 Werbe-Zentrale checks");
    }

    static void timing() {
        long six = AdHub.nextDue(0, 360, 1.5);
        check(six > 6 * 3_600_000L && six >= 6.05 * 3_600_000L && six <= 6.15 * 3_600_000L, "6 h becomes about 6.1 h: " + six / 3_600_000.0);
        check(AdHub.nextDue(0, 1440, 1.5) > 24 * 3_600_000L && AdHub.nextDue(0, 1440, 1.5) <= 24.5 * 3_600_000L, "24 h slightly above 24 h");
        check(AdHub.nextDue(0, 5, 0) == 6 * 60_000L, "at least one extra minute, even without a safety margin");
        check(AdHub.nextDue(0, 43200, 10) == 43200 * 60_000L + 3_600_000L, "at most one extra hour");
        check(AdHub.human(1440).equals("1 Tag") && AdHub.human(360).equals("6 Std") && AdHub.human(15).equals("15 Min") && AdHub.human(2880).equals("2 Tage"), "human durations");
        String[] dc = AdHub.discordChannel("https://discord.com/channels/900000000000000001/700000000000000003");
        check(dc != null && dc[0].equals("900000000000000001") && dc[1].equals("700000000000000003"), "Discord channel links are recognised");
        check(AdHub.discordChannel("https://ptb.discord.com/channels/900000000000000001/700000000000000003/") != null && AdHub.discordChannel("https://discord.gg/2gAsymnXfd") == null, "ptb links yes, invites no");
        check(AdHub.guessPlatform("https://www.reddit.com/r/mcservers/submit").equals("reddit") && AdHub.guessPlatform("https://www.planetminecraft.com/server/simplex/").equals("planetminecraft")
            && AdHub.guessPlatform("https://www.facebook.com/groups/123").equals("facebook") && AdHub.guessPlatform("https://discord.gg/x").equals("discord") && AdHub.guessPlatform("https://example.org").equals("other"), "platform from the link");
        fails(() -> AdHub.cleanLink("javascript:alert(1)"), "https", "only web links");
        fails(() -> AdHub.cleanLink(""), "Link", "empty link");
        check(AdHub.cleanLink("  https://t.me/simplex  ").equals("https://t.me/simplex"), "trimmed");
    }

    static void formats() {
        String ad = AdHub.SIMPLEX_AD;
        String bb = AdFormats.convert(ad, "discord", "bbcode");
        check(bb.startsWith("[size=6][b]🌍 SIMPLEX SMP[/b][/size]") && bb.contains("[quote]") && bb.contains("[/quote]") && bb.contains("[b]Java & Bedrock[/b]") && bb.contains("[u]CONNECTION SETTINGS[/u]") && !bb.contains("**"), "Discord → BBCode for Planet Minecraft:\n" + bb);
        String back = AdFormats.convert(bb, "bbcode", "discord");
        check(back.startsWith("# 🌍 SIMPLEX SMP") && back.contains("> **📡 __CONNECTION SETTINGS__**") && back.contains("**Java & Bedrock**"), "BBCode → Discord:\n" + back);
        String reddit = AdFormats.convert(ad, "discord", "markdown");
        check(!reddit.contains("__") && reddit.contains("**Java & Bedrock**:  \n") && reddit.contains("`simplexsmp.eu`"), "Reddit: no underline, hard line breaks:\n" + reddit);
        check(AdFormats.convert("||geheim||", "discord", "markdown").equals(">!geheim!<") && AdFormats.convert(">!geheim!<", "markdown", "discord").equals("||geheim||"), "spoilers both ways");
        String wa = AdFormats.convert("**fett** *kursiv* ~~weg~~", "discord", "whatsapp");
        check(wa.equals("*fett* _kursiv_ ~weg~"), "WhatsApp markers: " + wa);
        check(AdFormats.convert("*fett* _kursiv_ ~weg~", "whatsapp", "discord").equals("**fett** *kursiv* ~~weg~~"), "WhatsApp → Discord");
        String fb = AdFormats.convert("**Simplex** 60", "discord", "unicode");
        check(fb.equals("𝗦𝗶𝗺𝗽𝗹𝗲𝘅 60"), "Facebook: Unicode bold letters: " + fb);
        check(AdFormats.convert("[Discord](https://discord.gg/x) `ip`", "discord", "plain").equals("Discord: https://discord.gg/x ip"), "plain text keeps the link");
        check(AdFormats.convert("[Discord](https://discord.gg/x)", "discord", "bbcode").equals("[url=https://discord.gg/x]Discord[/url]") && AdFormats.convert("[Discord](https://discord.gg/x)", "discord", "markdown").equals("[Discord](https://discord.gg/x)"), "links per format");
        check(AdFormats.convert("a_b_c https://x.com/a_b", "discord", "whatsapp").equals("a_b_c https://x.com/a_b"), "underscores inside words stay");
        check(AdFormats.convert("**x**", "plain", "discord").equals("\\*\\*x\\*\\*"), "plain text is escaped for Discord");
        check(AdFormats.normalize("BBCODE").equals("bbcode") && AdFormats.normalize("??").equals("plain"), "format ids");
    }

    static final String TOKEN_CIPHER = "v1:QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=";

    static Path legacy(Path root, long now) throws Exception {
        Path old = root.resolve("discord-ads"); Files.createDirectories(old);
        Files.writeString(old.resolve("secret.key"), Base64.getEncoder().encodeToString(new byte[32]));
        Map<String, Object> state = SimplexAdminSuite.appMap("version", 1,
            "accounts", List.of(SimplexAdminSuite.appMap("id", "acc1", "userId", "111111111111111111", "username", "neon_alt", "token", TOKEN_CIPHER, "status", "ok")),
            "templates", List.of(SimplexAdminSuite.appMap("id", "tplA", "name", "Simplex SMP – Discord", "content", AdHub.SIMPLEX_AD, "preset", true),
                SimplexAdminSuite.appMap("id", "tplB", "name", "Mein Text", "content", "**Hallo** {online}/{max}", "preset", false)),
            "campaigns", List.of(SimplexAdminSuite.appMap("id", "c1", "accountId", "acc1", "guildId", "900000000000000001", "guildName", "MCAdverts", "channelId", "700000000000000003", "channelName", "6h-ads",
                    "templateId", "tplB", "intervalMinutes", 360, "enabled", true, "lastSentAt", now - 3_600_000L, "sentCount", 12, "category", "ADS"),
                SimplexAdminSuite.appMap("id", "c2", "accountId", "acc1", "guildId", "900000000000000001", "guildName", "MCAdverts", "channelId", "700000000000000004", "channelName", "24h", "templateId", "tplA", "intervalMinutes", 60,
                    "slowmodeSeconds", 86400, "enabled", false, "lastSentAt", 0)));
        Files.writeString(old.resolve("state.json"), DiscordBotManager.Json.stringify(state));
        return old;
    }

    static void migration() throws Exception {
        Path dir = Files.createTempDirectory("adhub-mig-"); AtomicLong clock = new AtomicLong(1_800_000_000_000L); Recorder rec = new Recorder();
        Path old = legacy(dir, clock.get());
        AdHub hub = new AdHub(dir.resolve("ad-hub"), old, rec, clock::get);
        Map<String, Object> view = hub.overview();
        List<Map<String, Object>> places = l(view.get("places"));
        check(places.size() == 2, "both channels become places");
        Map<String, Object> c1 = places.stream().filter(p -> p.get("id").equals("c1")).findFirst().orElseThrow(), c2 = places.stream().filter(p -> p.get("id").equals("c2")).findFirst().orElseThrow();
        check(c1.get("link").equals("https://discord.com/channels/900000000000000001/700000000000000003") && c1.get("name").equals("#6h-ads · MCAdverts") && c1.get("textId").equals("tplB") && ((Number) c1.get("intervalMinutes")).longValue() == 360, "channel link, name, text and interval kept: " + c1);
        check(((Number) c1.get("nextDueAt")).longValue() == AdHub.nextDue(clock.get() - 3_600_000L, 360, 1.5) && "waiting".equals(c1.get("state")) && ((Number) c1.get("postCount")).intValue() == 12, "the running timer continues from the last post");
        check(((Number) c2.get("intervalMinutes")).longValue() == 1440 && Boolean.FALSE.equals(c2.get("enabled")), "a longer slowmode became the interval; paused stays paused");
        List<Map<String, Object>> texts = l(view.get("texts"));
        check(texts.stream().anyMatch(t -> t.get("id").equals("tplA")) && texts.stream().anyMatch(t -> t.get("id").equals("tplB") && t.get("format").equals("discord")), "old texts keep their ids");
        check(texts.stream().filter(t -> t.get("name").equals("Simplex SMP – Discord")).count() == 1, "no duplicate preset");
        check(texts.stream().anyMatch(t -> t.get("format").equals("bbcode")) && texts.stream().anyMatch(t -> t.get("format").equals("markdown")) && texts.stream().anyMatch(t -> t.get("format").equals("unicode")), "ready-made Reddit, Planet Minecraft and Facebook texts added");
        check(m(view.get("migration")).get("places").equals(2) || ((Number) m(view.get("migration")).get("places")).intValue() == 2, "migration notice");
        check(!Files.exists(old.resolve("state.json")) && !Files.exists(old.resolve("secret.key")), "old token file and key are deleted");
        String copy = Files.readString(old.resolve("state-before-10.5.0-without-tokens.json"));
        check(!copy.contains(TOKEN_CIPHER) && !copy.contains("\"token\"") && copy.contains("neon_alt") && copy.contains("6h-ads"), "the kept copy has no token");
        String stored = Files.readString(dir.resolve("ad-hub/state.json"));
        check(!stored.contains(TOKEN_CIPHER) && !stored.contains("neon_alt"), "the new state knows nothing about the account");
        hub.acknowledgeMigration();
        AdHub again = new AdHub(dir.resolve("ad-hub"), old, rec, clock::get);
        check(again.overview().get("migration") == null && l(again.overview().get("places")).size() == 2, "after a restart: nothing migrated twice, notice gone");
        check(Files.isRegularFile(dir.resolve("ad-hub/link.key")), "link key stored");
        // Fresh install: presets only.
        Path fresh = Files.createTempDirectory("adhub-fresh-");
        AdHub empty = new AdHub(fresh.resolve("ad-hub"), fresh.resolve("discord-ads"), rec, clock::get);
        check(l(empty.overview().get("texts")).size() == 6 && l(empty.overview().get("places")).isEmpty() && empty.overview().get("migration") == null, "fresh: six Simplex texts, no places");
    }

    static void reminders() throws Exception {
        Path dir = Files.createTempDirectory("adhub-rem-"); AtomicLong clock = new AtomicLong(1_800_000_000_000L); Recorder rec = new Recorder();
        AdHub hub = new AdHub(dir.resolve("ad-hub"), null, rec, clock::get);
        String text = String.valueOf(l(hub.overview().get("texts")).get(0).get("id"));
        String mine = String.valueOf(hub.savePlace(SimplexAdminSuite.appMap("link", "https://discord.com/channels/900000000000000001/700000000000000003", "name", "#ads-6h", "textId", text, "intervalMinutes", 360), "user:abc", "juhas", "juhas").get("id"));
        fails(() -> hub.savePlace(SimplexAdminSuite.appMap("link", "https://x.org", "textId", "nope", "intervalMinutes", 360), "user:abc", "juhas", "juhas"), "Werbetext", "text must exist");
        fails(() -> hub.savePlace(SimplexAdminSuite.appMap("link", "https://x.org", "textId", text, "intervalMinutes", 2), "user:abc", "juhas", "juhas"), "Intervall", "interval bounds");
        String later = String.valueOf(hub.savePlace(SimplexAdminSuite.appMap("link", "https://www.reddit.com/r/mcservers/submit", "textId", text, "intervalMinutes", 1440, "lastPostedMinutesAgo", 60, "notifyAll", true), "user:abc", "juhas", "juhas").get("id"));
        Map<String, Object> laterView = l(hub.overview().get("places")).stream().filter(p -> p.get("id").equals(later)).findFirst().orElseThrow();
        check("reddit".equals(laterView.get("platform")) && "waiting".equals(laterView.get("state")) && "Reddit".equals(laterView.get("name")), "platform guessed, posted an hour ago → waiting: " + laterView);
        hub.tick();
        check(rec.reminders.size() == 1, "one reminder for the due place");
        String[] r = rec.reminders.get(0);
        check(r[0].equals("user:abc") && r[1].contains("Werbung fällig: #ads-6h") && r[3].startsWith("panel://ad-post?s=srv-1050&p=" + mine + "&c=1&k=") && r[4].equals("/#/ad-hub?post=" + mine) && r[5].equals(AdHub.SIMPLEX_AD), "reminder to the owner with app link, web link and text: " + Arrays.toString(r));
        hub.tick(); check(rec.reminders.size() == 1, "no second reminder right away");
        clock.addAndGet(61 * 60_000L); hub.tick();
        check(rec.reminders.size() == 2 && rec.reminders.get(1)[1].startsWith("⏰ Noch offen"), "repeat after an hour");
        for (int i = 0; i < 6; i++) { clock.addAndGet(61 * 60_000L); hub.tick(); }
        check(rec.reminders.size() == 4, "first reminder + three repeats, then quiet: " + rec.reminders.size());
        // Bark link: wrong signature, open, repeated tap, expiry.
        String sig = r[3].substring(r[3].indexOf("&k=") + 3);
        fails(() -> hub.link(mine, 1, "AAAAAAAAAAAAAAAAAAAAAAAA", "open", 0), "abgelaufen", "wrong signature refused");
        fails(() -> hub.link("nope", 1, sig, "open", 0), "existiert", "unknown place");
        Map<String, Object> peek = hub.link(mine, 1, sig, "peek", 0);
        check(peek.get("text").equals(AdHub.SIMPLEX_AD) && "discord".equals(peek.get("platform")) && m(peek.get("discord")).get("channel").equals("700000000000000003"), "peek returns text and channel");
        long tapped = clock.get();
        Map<String, Object> open = hub.link(mine, 1, sig, "open", 0);
        check(open.get("text").equals(AdHub.SIMPLEX_AD) && ((Number) open.get("nextDueAt")).longValue() == AdHub.nextDue(tapped, 360, 1.5) && String.valueOf(open.get("notice")).startsWith("Kopiert"), "open copies and restarts the timer at ~6.1 h");
        clock.addAndGet(5 * 60_000L);
        Map<String, Object> again = hub.link(mine, 1, sig, "open", 0);
        check(String.valueOf(again.get("notice")).startsWith("Bereits") && ((Number) again.get("nextDueAt")).longValue() == AdHub.nextDue(tapped, 360, 1.5), "a second tap within 30 minutes returns the text without restarting twice");
        clock.addAndGet(30 * 60_000L);
        fails(() -> hub.link(mine, 1, sig, "open", 0), "bereits geworben", "old link expires after 30 minutes");
        fails(() -> hub.post(mine, false, "juhas"), "Noch nicht fällig", "posting early needs force");
        Map<String, Object> forced = hub.post(mine, true, "juhas");
        check(forced.get("text").equals(AdHub.SIMPLEX_AD) && forced.get("link").toString().contains("/channels/"), "force post works");
        hub.placeAction(mine, "undo", "juhas");
        Map<String, Object> afterUndo = l(hub.overview().get("places")).stream().filter(p -> p.get("id").equals(mine)).findFirst().orElseThrow();
        check(((Number) afterUndo.get("nextDueAt")).longValue() == AdHub.nextDue(tapped, 360, 1.5) && Boolean.FALSE.equals(afterUndo.get("undoable")), "undo restores the previous timer");
        // Snooze.
        hub.placeAction(mine, "due-now", "juhas"); rec.reminders.clear(); hub.tick(); check(rec.reminders.size() == 1, "due again → reminder");
        String sig2 = rec.reminders.get(0)[3].substring(rec.reminders.get(0)[3].indexOf("&k=") + 3); int cycle2 = Integer.parseInt(rec.reminders.get(0)[3].replaceAll(".*&c=(\\d+)&.*", "$1"));
        Map<String, Object> snoozed = hub.link(mine, cycle2, sig2, "snooze", 30);
        check(((Number) snoozed.get("snoozedUntil")).longValue() == clock.get() + 30 * 60_000L, "snooze from the Bark link");
        hub.tick(); check(rec.reminders.size() == 1, "quiet while snoozed");
        clock.addAndGet(31 * 60_000L); hub.tick(); check(rec.reminders.size() == 2, "reminds again after the snooze");
        fails(() -> hub.snooze(mine, 1, "juhas"), "Snooze", "snooze bounds");
        // Everyone-reminder and pause.
        hub.placeAction(later, "due-now", "juhas"); rec.reminders.clear(); hub.tick();
        check(rec.reminders.stream().anyMatch(x -> x[0].isEmpty() && x[1].contains("Reddit")), "notifyAll reminds everybody with access");
        hub.placeAction(later, "pause", "juhas"); rec.reminders.clear(); clock.addAndGet(5 * 3_600_000L); hub.tick();
        check(rec.reminders.stream().noneMatch(x -> x[1].contains("Reddit")), "paused places stay quiet");
        // Persistence: timers are absolute.
        long due = ((Number) l(hub.overview().get("places")).stream().filter(p -> p.get("id").equals(mine)).findFirst().orElseThrow().get("dueAt")).longValue();
        AdHub restarted = new AdHub(dir.resolve("ad-hub"), null, rec, clock::get);
        Map<String, Object> kept = l(restarted.overview().get("places")).stream().filter(p -> p.get("id").equals(mine)).findFirst().orElseThrow();
        check(((Number) kept.get("dueAt")).longValue() == due && ((Number) kept.get("postCount")).intValue() >= 1, "restart keeps every timer");
        // Texts.
        Map<String, Object> conv = restarted.convertText(SimplexAdminSuite.appMap("id", text, "to", "bbcode", "save", true), "juhas");
        check(String.valueOf(conv.get("content")).contains("[quote]") && l(restarted.overview().get("texts")).stream().anyMatch(t -> t.get("id").equals(conv.get("id")) && t.get("format").equals("bbcode") && String.valueOf(t.get("name")).endsWith("(BBCode)")), "convert & save as a new BBCode text");
        fails(() -> restarted.deleteText(text, "juhas"), "verwendet", "texts in use cannot be deleted");
        restarted.renameOwner("user:abc", "user:new", "neu");
        clock.addAndGet(24 * 3_600_000L); rec.reminders.clear(); restarted.tick();
        check(rec.reminders.stream().anyMatch(x -> x[0].equals("user:new")), "account rename moves the reminders");
        check(restarted.render("{online}/{max}").equals("7/60"), "placeholders");
    }

    @SuppressWarnings("unchecked")
    static void api() throws Exception {
        Path dir = Files.createTempDirectory("suite1050-");
        Field serverField = Bukkit.class.getDeclaredField("server"); serverField.setAccessible(true);
        AppApiManager api = null;
        try {
            BukkitTask task = AppApiIntegrationHarness.proxy(BukkitTask.class, (p, mm, a) -> AppApiIntegrationHarness.empty(mm.getReturnType()));
            BukkitScheduler scheduler = AppApiIntegrationHarness.proxy(BukkitScheduler.class, (p, mm, a) -> { if (a != null) for (Object item : a) { if (item instanceof Runnable work) work.run(); else if (item instanceof Consumer<?> c) ((Consumer<BukkitTask>) c).accept(task); } return mm.getReturnType() == BukkitTask.class ? task : AppApiIntegrationHarness.empty(mm.getReturnType()); });
            org.bukkit.plugin.PluginManager plugins = AppApiIntegrationHarness.proxy(org.bukkit.plugin.PluginManager.class, (p, mm, a) -> switch (mm.getName()) { case "getPlugins" -> new org.bukkit.plugin.Plugin[0]; case "getPermissions" -> Set.of(); default -> AppApiIntegrationHarness.empty(mm.getReturnType()); });
            var console = AppApiIntegrationHarness.proxy(org.bukkit.command.ConsoleCommandSender.class, (p, mm, a) -> switch (mm.getName()) { case "getName" -> "Console"; case "hasPermission", "isOp", "isPermissionSet" -> true; default -> AppApiIntegrationHarness.empty(mm.getReturnType()); });
            Server server = AppApiIntegrationHarness.proxy(Server.class, (p, mm, a) -> switch (mm.getName()) {
                case "getName", "getVersion", "getBukkitVersion", "getMinecraftVersion" -> "Harness1050";
                case "getPluginManager" -> plugins; case "getScheduler" -> scheduler; case "getConsoleSender" -> console;
                case "getLogger" -> java.util.logging.Logger.getLogger("Harness1050"); case "isPrimaryThread" -> true;
                case "getOnlinePlayers" -> List.of(); case "getWorlds" -> List.of(); case "getOperators" -> Set.of(); case "getTPS" -> new double[]{20, 20, 20}; case "getWorldContainer" -> dir.toFile(); case "getMaxPlayers" -> 60;
                default -> AppApiIntegrationHarness.empty(mm.getReturnType()); });
            serverField.set(null, server);
            SimplexAdminSuite plugin = Moderation790Harness.plugin(dir); Moderation790Harness.set(plugin, JavaPlugin.class, "isEnabled", true);
            Moderation790Harness.set(plugin, SimplexAdminSuite.class, "adminActor", new ThreadLocal<String>()); Moderation790Harness.set(plugin, SimplexAdminSuite.class, "pendingInputs", new ConcurrentHashMap<>()); Moderation790Harness.set(plugin, SimplexAdminSuite.class, "pendingActions", new ConcurrentHashMap<>());
            Moderation790Harness.set(plugin, SimplexAdminSuite.class, "auditedConfig", new HashMap<String, String>()); plugin.moderationControl = new ModerationControl(plugin);
            plugin.adminAccess = new AdminAccessManager(plugin); plugin.adminUpdate = new AdminUpdateMenu(plugin); plugin.adminLog = new AdminActivityLog(plugin);
            Moderation790Harness.set(plugin, JavaPlugin.class, "description", new org.bukkit.plugin.PluginDescriptionFile("SimplexAdminSuite", "10.5.0", SimplexAdminSuite.class.getName()));
            var dbConstructor = SimplexAdminSuite.PlayerDatabase.class.getDeclaredConstructor(SimplexAdminSuite.class); dbConstructor.setAccessible(true); Moderation790Harness.set(plugin, SimplexAdminSuite.class, "database", dbConstructor.newInstance(plugin));
            plugin.getConfig().setDefaults(org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(Release1050Harness.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8)));
            check(plugin.getConfig().getBoolean("ad-hub.enabled") && !plugin.getConfig().contains("discord-ads"), "Werbe-Zentrale on by default, old planner key gone from the defaults");
            plugin.appEvents = new SuiteEventCenter(plugin);
            check(SuiteEventCenter.TYPES.contains("ad-reminder") && PanelHub.EVENT_TITLES.containsKey("ad-reminder") && !PanelHub.EVENT_TITLES.containsKey("discord-ads"), "Bark/browser push know the reminder event");
            Recorder rec = new Recorder();
            AtomicLong clock = new AtomicLong(System.currentTimeMillis());

            int port; try (var socket = new java.net.ServerSocket(0)) { port = socket.getLocalPort(); }
            plugin.getConfig().set("app-api.enabled", true); plugin.getConfig().set("app-api.bind", "127.0.0.1"); plugin.getConfig().set("app-api.port", port); plugin.getConfig().set("app-api.transport", "tls");
            api = new AppApiManager(plugin); plugin.appApi = api; api.reload();
            AppApiManager live = api;
            plugin.adHub = new AdHub(dir.resolve("ad-hub"), dir.resolve("discord-ads"), new AdHub.Hooks() {
                public void remind(String owner, String title, String body, String appUrl, String webUrl, String copy) { rec.remind(owner, title, body, appUrl, webUrl, copy); }
                public void audit(String actor, String action, String detail) { rec.audit(actor, action, detail); }
                public Map<String, String> placeholders() { return rec.placeholders(); }
                public void warn(String message) { rec.warn(message); }
                public String serverId() { return live.security.serverId(); }
            }, clock::get);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30); while (!api.active() && System.nanoTime() < deadline) Thread.sleep(50); check(api.active(), "TLS listener started");
            SSLContext ssl = SSLContext.getInstance("TLS"); ssl.init(null, new TrustManager[]{new X509TrustManager() { public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; } public void checkClientTrusted(X509Certificate[] c, String a) { } public void checkServerTrusted(X509Certificate[] c, String a) { } }}, null);
            HttpClient client = HttpClient.newBuilder().sslContext(ssl).connectTimeout(java.time.Duration.ofSeconds(5)).build(); URI base = URI.create("https://127.0.0.1:" + port);
            api.security.users.createOwner("juhas", "owner-password-1050");
            api.security.users.save("watcher", "watcher-password-1050", Set.of("data.discord-ads"), true);
            api.security.users.save("viewer", "viewer-password-1050", Set.of("data.profile"), true);
            String owner = Release1020Harness.login(client, base, "juhas", "owner-password-1050"), watcher = Release1020Harness.login(client, base, "watcher", "watcher-password-1050"), viewer = Release1020Harness.login(client, base, "viewer", "viewer-password-1050");

            List<?> features = (List<?>) obj(Release1020Harness.call(client, base, "/v1/health", owner, null).body()).get("features");
            check(features.contains("ad-hub-v1") && !features.contains("discord-ads-v1") && features.contains("scoreboard-designer-v1"), "health advertises ad-hub-v1 instead of the self-bot planner");
            Map<String, Object> catalog = obj(Release1020Harness.call(client, base, "/v1/data/access", owner, null).body());
            check(l(catalog.get("catalog")).stream().anyMatch(r -> "discord-ads.manage".equals(r.get("id")) && "Werbe-Zentrale verwalten".equals(r.get("title")) && !"critical".equals(r.get("level"))), "manage right renamed, no longer critical (no token)");
            for (Map<String, Object> preset : l(catalog.get("presets"))) if (!"full".equals(preset.get("id")))
                check(((List<?>) preset.get("permissions")).stream().noneMatch(p -> String.valueOf(p).contains("discord-ads")), "role template " + preset.get("id") + " does not contain the Werbe-Zentrale rights");

            check(Release1020Harness.call(client, base, "/v1/discord-ads", owner, null).statusCode() == 410, "the old self-bot route is gone (410)");
            check(Release1020Harness.call(client, base, "/v1/ad-hub", viewer, null).statusCode() == 403, "viewing needs data.discord-ads");
            var watch = Release1020Harness.call(client, base, "/v1/ad-hub", watcher, null);
            check(watch.statusCode() == 200 && Boolean.FALSE.equals(obj(watch.body()).get("canManage")) && l(obj(watch.body()).get("texts")).size() == 6 && l(obj(watch.body()).get("platforms")).size() >= 8, "watcher reads the Werbe-Zentrale: " + watch.body());
            String text = String.valueOf(l(obj(watch.body()).get("texts")).get(0).get("id"));
            check(Release1020Harness.call(client, base, "/v1/ad-hub", watcher, Map.of("operation", "place.save", "link", "https://t.me/x", "textId", text, "intervalMinutes", 360)).statusCode() == 403, "creating places needs discord-ads.manage");
            var saved = Release1020Harness.call(client, base, "/v1/ad-hub", owner, Map.of("operation", "place.save", "link", "https://discord.com/channels/900000000000000001/700000000000000003", "name", "#ads", "textId", text, "intervalMinutes", 360));
            check(saved.statusCode() == 200 && l(m(obj(saved.body()).get("overview")).get("places")).size() == 1, "owner adds a place: " + saved.body());
            String place = String.valueOf(obj(saved.body()).get("id"));
            plugin.adHub.tick();
            check(rec.reminders.size() == 1 && rec.reminders.get(0)[3].contains("s=" + api.security.serverId()), "reminder carries the server id");
            String link = rec.reminders.get(0)[3]; String sig = link.substring(link.indexOf("&k=") + 3);
            var badSig = Release1020Harness.call(client, base, "/v1/ad-hub/link", null, Map.of("p", place, "c", 1, "k", "x".repeat(24), "op", "open"));
            check(badSig.statusCode() == 403, "Bark link with a wrong signature is refused without login: " + badSig.statusCode());
            var opened = Release1020Harness.call(client, base, "/v1/ad-hub/link", null, Map.of("p", place, "c", 1, "k", sig, "op", "open"));
            check(opened.statusCode() == 200 && String.valueOf(obj(opened.body()).get("text")).equals(AdHub.SIMPLEX_AD) && !opened.body().contains("\"overview\""), "Bark link without login: text, link – nothing else: " + opened.body());
            check(Release1020Harness.call(client, base, "/v1/ad-hub/link", null, Map.of("p", place, "c", 1, "k", sig, "op", "explode")).statusCode() == 400, "unknown link action");
            var early = Release1020Harness.call(client, base, "/v1/ad-hub", watcher, Map.of("operation", "place.post", "id", place));
            check(early.statusCode() == 409 && early.body().contains("Noch nicht fällig"), "posting early is refused without force");
            var forced = Release1020Harness.call(client, base, "/v1/ad-hub", watcher, Map.of("operation", "place.post", "id", place, "force", true));
            check(forced.statusCode() == 200 && String.valueOf(obj(forced.body()).get("text")).equals(AdHub.SIMPLEX_AD), "watchers with manage scope may post (copy) and restart the timer");
            check(Release1020Harness.call(client, base, "/v1/ad-hub", watcher, Map.of("operation", "place.snooze", "id", place, "minutes", 60)).statusCode() == 200, "and snooze");
            check(Release1020Harness.call(client, base, "/v1/ad-hub", watcher, Map.of("operation", "settings.save", "repeatMinutes", 0)).statusCode() == 403, "settings need the manage right");
            check(Release1020Harness.call(client, base, "/v1/ad-hub", owner, Map.of("operation", "explode")).statusCode() == 400, "unknown operation");
            Release1060Harness.run(client, base, plugin, api, owner, watcher, viewer);
            plugin.adHub.stop();
            plugin.adHub = null;
            check(Release1020Harness.call(client, base, "/v1/ad-hub", owner, null).statusCode() == 503, "clear 503 when the Werbe-Zentrale is off");
        } finally {
            if (api != null) api.stop();
        }
    }
}
