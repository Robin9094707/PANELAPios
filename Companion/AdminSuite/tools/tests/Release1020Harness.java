package eu.simplexsmp.adminsuite;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javax.net.ssl.*;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.*;

/**
 * 10.2.0: diagnostics centre – disconnect categories and verdicts, bursts/waves/flapping, console attachment of
 * "lost connection" reasons, crash detection from heartbeat and live log, spark protobuf parsing with idle removal and
 * deepest-plugin attribution, IP redaction, diagnostics API with permissions – and the fixed "Pflichtfeld fehlt: grant"
 * for command switches through the real action validator over HTTPS.
 */
public final class Release1020Harness {
    static int checks;
    static void check(boolean value, String reason) { checks++; if (!value) throw new AssertionError(reason); }
    static void fails(Callable<?> work, String reason) { try { work.call(); } catch (Exception | AssertionError e) { checks++; return; } throw new AssertionError("Expected failure: " + reason); }
    @SuppressWarnings("unchecked") static Map<String, Object> obj(String json) { return (Map<String, Object>) DiscordBotManager.Json.parse(json); }

    public static void main(String[] args) throws Exception {
        System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
        categories();
        verdicts();
        helpers();
        spark();
        center();
        System.out.println("PASS " + checks + " 10.2.0 diagnostics, disconnect, crash, spark and command-switch checks");
    }

    static void categories() {
        check(DisconnectAnalyzer.category("TIMED_OUT", "", "").equals("TIMEOUT"), "quit reason timed out");
        check(DisconnectAnalyzer.category("DISCONNECTED", "", "Timed out").equals("TIMEOUT"), "console timed out");
        check(DisconnectAnalyzer.category("DISCONNECTED", "", "Internal Exception: java.net.SocketException: Connection reset").equals("RESET"), "connection reset");
        check(DisconnectAnalyzer.category("DISCONNECTED", "", "Internal Exception: io.netty.handler.codec.DecoderException: Badly compressed packet").equals("PACKET"), "packet error");
        check(DisconnectAnalyzer.category("DISCONNECTED", "", "Disconnected").equals("CLIENT_QUIT"), "client quit");
        check(DisconnectAnalyzer.category("DISCONNECTED", "", "").equals("CLIENT_QUIT"), "no message = own quit");
        check(DisconnectAnalyzer.category("KICKED", "FLYING_PLAYER", "Flying is not enabled").equals("GUARD_KICK"), "anticheat kick");
        check(DisconnectAnalyzer.category("KICKED", "PLUGIN", "Du wurdest gekickt").equals("KICK"), "plugin kick");
        check(DisconnectAnalyzer.category("KICKED", "BANNED", "Banned").equals("ACCESS"), "ban");
        check(DisconnectAnalyzer.category("KICKED", "PLUGIN", "Server closed").equals("SERVER_STOP"), "server stop");
        check(DisconnectAnalyzer.category("KICKED", "IDLING", "").equals("IDLE"), "afk");
        check(DisconnectAnalyzer.category("ERRONEOUS_STATE", "", "").equals("ERROR"), "erroneous state");
        check(DisconnectAnalyzer.involuntary("TIMEOUT") && DisconnectAnalyzer.involuntary("PACKET") && !DisconnectAnalyzer.involuntary("CLIENT_QUIT") && !DisconnectAnalyzer.involuntary("KICK"), "involuntary set");
        check(DisconnectAnalyzer.ignored("SERVER_STOP") && DisconnectAnalyzer.ignored("KICK") && !DisconnectAnalyzer.ignored("TIMEOUT") && !DisconnectAnalyzer.ignored("CLIENT_QUIT"), "ignored set");
    }

    static DisconnectAnalyzer.Record rec(String name, long at, String platform, String category, String world, int cx, int cz, double tps, double max) {
        return new DisconnectAnalyzer.Record(UUID.randomUUID().toString().substring(0, 13), at, UUID.nameUUIDFromBytes(name.getBytes()).toString(), name, platform, "DISCONNECTED", "", category.toLowerCase(), category, 600_000, 40, world, cx, cz, tps, max, 10);
    }
    static DisconnectAnalyzer.Context ctx(int java, int bedrock, double max, boolean watchdog) { return new DisconnectAnalyzer.Context(java, bedrock, 20, max, 0.5, 200, watchdog, false, List.of()); }

    @SuppressWarnings("unchecked")
    static void verdicts() {
        long now = System.currentTimeMillis();
        List<DisconnectAnalyzer.Record> quits = List.of(rec("A", now, "java", "CLIENT_QUIT", "world", 0, 0, 20, 40), rec("B", now, "java", "CLIENT_QUIT", "world", 50, 50, 20, 40), rec("C", now, "bedrock", "CLIENT_QUIT", "world", 90, 90, 20, 40));
        Map<String, Object> v = DisconnectAnalyzer.evaluate(quits, ctx(5, 2, 50, false));
        check("legit".equals(v.get("verdict")) && "info".equals(v.get("severity")), "own quits are legit: " + v);

        List<DisconnectAnalyzer.Record> javaWave = new ArrayList<>();
        for (int i = 0; i < 6; i++) javaWave.add(rec("J" + i, now - i * 500, "java", i % 2 == 0 ? "TIMEOUT" : "RESET", "world", i * 30, i * 30, 20, 45));
        v = DisconnectAnalyzer.evaluate(javaWave, ctx(6, 4, 60, false));
        check("problem".equals(v.get("verdict")) && "critical".equals(v.get("severity")) && String.valueOf(v.get("cause")).contains("Nur Java"), "java-only wave with healthy server = network path: " + v.get("cause"));
        check(((Number) v.get("java")).intValue() == 6 && ((Number) v.get("bedrock")).intValue() == 0 && ((Number) v.get("javaShare")).doubleValue() == 0.5, "counts and java share");

        List<DisconnectAnalyzer.Record> lag = List.of(rec("L1", now, "java", "TIMEOUT", "world", 0, 0, 6, 9000), rec("L2", now, "bedrock", "TIMEOUT", "world", 9, 9, 6, 9000), rec("L3", now, "java", "TIMEOUT", "nether", 1, 1, 6, 9000));
        v = DisconnectAnalyzer.evaluate(lag, ctx(3, 1, 9000, false));
        check("problem".equals(v.get("verdict")) && String.valueOf(v.get("cause")).contains("Server-Lag") && ((List<String>) v.get("steps")).stream().anyMatch(s -> s.contains("Spark")), "timeouts during a long tick = server lag");
        v = DisconnectAnalyzer.evaluate(lag, ctx(3, 1, 100, true));
        check(String.valueOf(v.get("cause")).contains("Watchdog"), "watchdog named");

        List<DisconnectAnalyzer.Record> packets = List.of(rec("P1", now, "java", "PACKET", "world", 100, 100, 20, 40), rec("P2", now, "java", "PACKET", "world", 101, 100, 20, 40), rec("P3", now, "java", "PACKET", "world", 100, 102, 20, 40));
        v = DisconnectAnalyzer.evaluate(packets, ctx(8, 0, 40, false));
        check("problem".equals(v.get("verdict")) && String.valueOf(v.get("cause")).contains("selben Ort") && ((Number) v.get("clustered")).intValue() == 3, "packet errors at one place = broken item/chunk");

        DisconnectAnalyzer.Context stopping = new DisconnectAnalyzer.Context(0, 0, 20, 40, 0.3, 0, false, true, List.of());
        check("legit".equals(DisconnectAnalyzer.evaluate(javaWave, stopping).get("verdict")), "shutdown = legit");

        List<DisconnectAnalyzer.Record> burst = DisconnectAnalyzer.burst(javaWave, now, 10_000, 3);
        check(burst.size() == 6, "burst of six within 10 s");
        check(DisconnectAnalyzer.burst(List.of(rec("K", now, "java", "KICK", "w", 0, 0, 20, 0), rec("K2", now, "java", "SERVER_STOP", "w", 0, 0, 20, 0), rec("K3", now, "java", "KICK", "w", 0, 0, 20, 0)), now, 10_000, 3).isEmpty(), "kicks and restarts never form a burst");
        check(DisconnectAnalyzer.burst(List.of(rec("O", now - 60_000, "java", "TIMEOUT", "w", 0, 0, 20, 0), rec("O2", now, "java", "TIMEOUT", "w", 0, 0, 20, 0), rec("O3", now, "java", "TIMEOUT", "w", 0, 0, 20, 0)), now, 10_000, 3).isEmpty(), "old records outside the window");
        String uuid = UUID.nameUUIDFromBytes("F".getBytes()).toString();
        List<DisconnectAnalyzer.Record> flap = List.of(rec("F", now - 1000, "java", "TIMEOUT", "w", 0, 0, 20, 0), rec("F", now - 2000, "java", "RESET", "w", 0, 0, 20, 0), rec("F", now - 3000, "java", "TIMEOUT", "w", 0, 0, 20, 0), rec("F", now - 4000, "java", "CLIENT_QUIT", "w", 0, 0, 20, 0));
        check(DisconnectAnalyzer.flapping(flap, uuid, now, 600_000) == 3, "flapping counts involuntary only");

        DisconnectAnalyzer.Record r = rec("Round", now, "bedrock", "TIMEOUT", "world_nether", -3, 7, 19.5, 120);
        DisconnectAnalyzer.Record back = DisconnectAnalyzer.Record.of(obj(DiscordBotManager.Json.stringify(r.json())));
        check(back.equals(r), "record JSON round trip");
        check(r.withMessage("Internal Exception: java.net.SocketException: Connection reset").category().equals("RESET"), "withMessage reclassifies");
        check(((Number) r.json().get("blockX")).intValue() == -40 && ((Number) r.json().get("blockZ")).intValue() == 120, "chunk to block coordinates");
    }

    static void helpers() {
        check(DiagnosticsCenter.redact("Steve[/203.0.113.7:51234] logged in").equals("Steve[/x.x.x.x] logged in"), "IPv4 with port masked");
        check(DiagnosticsCenter.redact("from [2001:db8:85a3::8a2e:370:7334]:25565").contains("x:x:x:x") && !DiagnosticsCenter.redact("from [2001:db8:85a3:0:0:8a2e:370:7334]").contains("2001"), "IPv6 masked");
        check(DiagnosticsCenter.redact("TPS 19.98 at 12:30:15").equals("TPS 19.98 at 12:30:15"), "times and decimals untouched");
        check(DiagnosticsCenter.dedupe(List.of("Can't keep up 2001ms", "Can't keep up 3500ms", "other")).equals(List.of("Can't keep up 2001ms ×2", "other")), "dedupe ignores numbers");
        check(SparkProfile.code("https://spark.lucko.me/eCdSnJqhqI").equals("eCdSnJqhqI") && SparkProfile.code("eCdSnJqhqI").equals("eCdSnJqhqI") && SparkProfile.code("https://spark.lucko.me/abcd1234?hl=1#x").equals("abcd1234"), "spark codes");
        fails(() -> SparkProfile.code("https://evil/../x y"), "invalid code");
        check(SparkProfile.shortFrame("net.minecraft.world.level.Level.tickBlockEntities").equals("n.m.w.l.Level.tickBlockEntities"), "short frame");
        check(DiagnosticsCenter.AI_INSTRUCTIONS.contains("nicht vertrauenswürdige Daten") && DiagnosticsCenter.AI_INSTRUCTIONS.contains("140 Wörter"), "AI prompt bounded and injection-safe");
    }

    // ---------------------------------------------------------------- spark
    static final class Pb {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        Pb varint(long v) { while ((v & ~0x7fL) != 0) { out.write((int) ((v & 0x7f) | 0x80)); v >>>= 7; } out.write((int) v); return this; }
        Pb key(int field, int wire) { return varint((long) field << 3 | wire); }
        Pb u(int field, long v) { return key(field, 0).varint(v); }
        Pb d(int field, double v) { key(field, 1); long bits = Double.doubleToLongBits(v); for (int i = 0; i < 8; i++) out.write((int) (bits >>> (8 * i)) & 0xff); return this; }
        Pb bytes(int field, byte[] b) { key(field, 2).varint(b.length); out.write(b, 0, b.length); return this; }
        Pb s(int field, String v) { return bytes(field, v.getBytes(StandardCharsets.UTF_8)); }
        Pb m(int field, Pb sub) { return bytes(field, sub.out.toByteArray()); }
        Pb doubles(int field, double... v) { Pb p = new Pb(); for (double x : v) { long bits = Double.doubleToLongBits(x); for (int i = 0; i < 8; i++) p.out.write((int) (bits >>> (8 * i)) & 0xff); } return bytes(field, p.out.toByteArray()); }
        Pb ints(int field, int... v) { Pb p = new Pb(); for (int x : v) p.varint(x); return bytes(field, p.out.toByteArray()); }
    }
    static Pb node(String cls, String method, double[] times, int... children) { Pb n = new Pb().s(3, cls).s(4, method).doubles(8, times); if (children.length > 0) n.ints(9, children); return n; }

    @SuppressWarnings("unchecked")
    static void spark() throws Exception {
        Pb thread = new Pb().s(1, "Server thread")
            .m(3, node("net.minecraft.Foo", "bar", new double[]{4, 6}))
            .m(3, node("com.plugin.Heavy", "work", new double[]{10, 20}, 0))
            .m(3, node("jdk.internal.misc.Unsafe", "park", new double[]{25, 25}))
            .m(3, node("net.minecraft.server.MinecraftServer", "tick", new double[]{50, 50}, 1, 2))
            .ints(5, 3);
        Pb meta = new Pb().m(7, new Pb().s(2, "Paper").s(3, "26.3-test").s(4, "26.3")).u(2, 1000).u(11, 61_000).u(12, 1200)
            .m(10, new Pb().s(1, "server.properties").s(2, "{\"view-distance\":\"12\",\"simulation-distance\":\"10\"}"))
            .m(13, new Pb().s(1, "HeavyPlugin").m(2, new Pb().s(1, "HeavyPlugin").s(2, "1.2.3")));
        Pb window = new Pb().u(1, 5).m(2, new Pb().u(1, 1200).d(4, 17.5).d(5, 42.0).d(6, 1500.0).u(7, 5).u(8, 900));
        Pb root = new Pb().m(1, meta).m(2, thread).m(3, new Pb().s(1, "com.plugin.Heavy").s(2, "HeavyPlugin")).m(7, window);
        Path file = Files.createTempFile("spark1020-", ".bin"); Files.write(file, root.out.toByteArray());
        Map<String, Object> a = SparkProfile.analyze(file);
        check(((Number) a.get("totalMs")).longValue() == 100 && ((Number) a.get("idleMs")).longValue() == 50 && ((Number) a.get("busyMs")).longValue() == 50, "idle (park) removed from 100 ms: " + a.get("totalMs") + "/" + a.get("idleMs"));
        List<Map<String, Object>> plugins = (List<Map<String, Object>>) a.get("plugins");
        check(plugins.size() == 1 && "HeavyPlugin".equals(plugins.get(0).get("name")) && ((Number) plugins.get(0).get("share")).doubleValue() == 60.0 && "1.2.3".equals(plugins.get(0).get("version")), "deepest plugin frame owns its callees: " + plugins);
        check(((Number) a.get("vanillaShare")).doubleValue() == 40.0, "remaining share is Paper/vanilla");
        List<Map<String, Object>> methods = (List<Map<String, Object>>) a.get("methods");
        check(methods.stream().anyMatch(m -> String.valueOf(m.get("frame")).endsWith("Heavy.work") && ((Number) m.get("ms")).longValue() == 20 && "HeavyPlugin".equals(m.get("source"))), "self time of the plugin frame");
        check(methods.stream().noneMatch(m -> String.valueOf(m.get("frame")).contains("park")), "idle frames not listed");
        List<Map<String, Object>> frames = (List<Map<String, Object>>) a.get("pluginFrames");
        check(frames.size() == 1 && ((Number) frames.get(0).get("ms")).longValue() == 30, "plugin frame includes the vanilla work it called");
        Map<String, Object> w = (Map<String, Object>) a.get("windows");
        check(((Number) w.get("count")).intValue() == 1 && ((Number) w.get("tpsAvg")).doubleValue() == 17.5 && ((Number) w.get("msptPeak")).doubleValue() == 1500.0 && ((Number) w.get("entitiesAvg")).longValue() == 900, "window statistics");
        Map<String, Object> m = (Map<String, Object>) a.get("metadata");
        check(((Map<String, Object>) m.get("platform")).get("version").equals("26.3-test") && ((Number) m.get("durationSeconds")).longValue() == 60 && "10".equals(((Map<String, Object>) m.get("config")).get("simulation-distance")), "metadata, duration and config");
        List<Map<String, Object>> findings = (List<Map<String, Object>>) a.get("findings");
        check(findings.stream().anyMatch(f -> "crit".equals(f.get("level")) && String.valueOf(f.get("title")).contains("TPS unter 18")), "low TPS finding");
        check(findings.stream().anyMatch(f -> String.valueOf(f.get("title")).contains("simulation-distance 10")), "simulation distance finding");
        check(findings.stream().anyMatch(f -> String.valueOf(f.get("title")).contains("HeavyPlugin")), "heavy plugin finding");
        Files.write(file, new byte[]{0x12, 0x7f, 0x01});
        fails(() -> SparkProfile.analyze(file), "truncated profile rejected");
        String real = System.getenv("SPARK_PROFILE");
        if (real != null && Files.isRegularFile(Path.of(real))) {
            long t = System.nanoTime(); Map<String, Object> big = SparkProfile.analyze(Path.of(real));
            check(((Number) big.get("nodes")).intValue() > 1000 && !((List<?>) big.get("plugins")).isEmpty(), "real profile analysed in " + (System.nanoTime() - t) / 1_000_000 + " ms");
        }
        Files.deleteIfExists(file);
    }

    // ---------------------------------------------------------------- diagnostics centre + HTTPS
    static final class TestCommand extends Command { TestCommand(String name, String permission) { super(name); setPermission(permission.isEmpty() ? null : permission); } public boolean execute(org.bukkit.command.CommandSender s, String l, String[] a) { return true; } }

    @SuppressWarnings("unchecked")
    static void center() throws Exception {
        Path dir = Files.createTempDirectory("suite1020-");
        Field serverField = Bukkit.class.getDeclaredField("server"); serverField.setAccessible(true); Object old = serverField.get(null);
        Map<UUID, Player> online = new ConcurrentHashMap<>();
        AppApiManager api = null;
        try {
            BukkitTask task = AppApiIntegrationHarness.proxy(BukkitTask.class, (p, m, a) -> AppApiIntegrationHarness.empty(m.getReturnType()));
            BukkitScheduler scheduler = AppApiIntegrationHarness.proxy(BukkitScheduler.class, (p, m, a) -> { if (a != null) for (Object item : a) { if (item instanceof Runnable work) work.run(); else if (item instanceof Consumer<?> c) ((Consumer<BukkitTask>) c).accept(task); } return m.getReturnType() == BukkitTask.class ? task : AppApiIntegrationHarness.empty(m.getReturnType()); });
            org.bukkit.plugin.PluginManager plugins = AppApiIntegrationHarness.proxy(org.bukkit.plugin.PluginManager.class, (p, m, a) -> switch (m.getName()) { case "getPlugins" -> new org.bukkit.plugin.Plugin[0]; case "getPermissions" -> Set.of(); default -> AppApiIntegrationHarness.empty(m.getReturnType()); });
            Map<String, Command> known = new LinkedHashMap<>();
            org.bukkit.command.CommandMap commandMap = AppApiIntegrationHarness.proxy(org.bukkit.command.CommandMap.class, (p, m, a) -> switch (m.getName()) { case "getKnownCommands" -> known; case "getCommand" -> known.get(String.valueOf(a[0]).toLowerCase(Locale.ROOT)); default -> AppApiIntegrationHarness.empty(m.getReturnType()); });
            var console = AppApiIntegrationHarness.proxy(org.bukkit.command.ConsoleCommandSender.class, (p, m, a) -> switch (m.getName()) { case "getName" -> "Console"; case "hasPermission", "isOp", "isPermissionSet" -> true; default -> AppApiIntegrationHarness.empty(m.getReturnType()); });
            World world = AppApiIntegrationHarness.proxy(World.class, (p, m, a) -> switch (m.getName()) { case "getName" -> "world"; case "getUID" -> new UUID(1, 1); default -> AppApiIntegrationHarness.empty(m.getReturnType()); });
            Server server = AppApiIntegrationHarness.proxy(Server.class, (p, m, a) -> switch (m.getName()) {
                case "getName", "getVersion", "getBukkitVersion", "getMinecraftVersion" -> "Harness1020";
                case "getPluginManager" -> plugins; case "getScheduler" -> scheduler; case "getCommandMap" -> commandMap; case "getConsoleSender" -> console;
                case "getLogger" -> java.util.logging.Logger.getLogger("Harness1020"); case "isPrimaryThread" -> true;
                case "getOnlinePlayers" -> List.copyOf(online.values()); case "getPlayer" -> a[0] instanceof UUID u ? online.get(u) : null;
                case "getWorlds" -> List.of(world); case "getOperators" -> Set.of(); case "getTPS" -> new double[]{19.9, 19.8, 19.7}; case "getTickTimes" -> new long[100]; case "getWorldContainer" -> dir.toFile();
                default -> AppApiIntegrationHarness.empty(m.getReturnType()); });
            serverField.set(null, server);
            for (String[] c : new String[][]{{"essentials", "tpa", "essentials.tpa"}, {"essentials", "home", "essentials.home"}, {"litebans", "ban", "litebans.ban"}}) { Command cmd = new TestCommand(c[1], c[2]); known.put(c[1], cmd); known.put(c[0] + ":" + c[1], cmd); }

            SimplexAdminSuite plugin = Moderation790Harness.plugin(dir); Moderation790Harness.set(plugin, JavaPlugin.class, "isEnabled", true);
            Moderation790Harness.set(plugin, SimplexAdminSuite.class, "adminActor", new ThreadLocal<String>()); Moderation790Harness.set(plugin, SimplexAdminSuite.class, "pendingInputs", new ConcurrentHashMap<>()); Moderation790Harness.set(plugin, SimplexAdminSuite.class, "pendingActions", new ConcurrentHashMap<>());
            Moderation790Harness.set(plugin, SimplexAdminSuite.class, "auditedConfig", new HashMap<String, String>()); plugin.moderationControl = new ModerationControl(plugin);
            plugin.adminAccess = new AdminAccessManager(plugin); plugin.adminUpdate = new AdminUpdateMenu(plugin); plugin.adminLog = new AdminActivityLog(plugin);
            Moderation790Harness.set(plugin, JavaPlugin.class, "description", new org.bukkit.plugin.PluginDescriptionFile("SimplexAdminSuite", "10.2.0", SimplexAdminSuite.class.getName()));
            plugin.accessLocks = new AccessLocks(plugin);
            plugin.commandRights = new CommandRights(plugin);

            // ---- crash detection: previous run ended without onDisable and the live log shows an OutOfMemoryError
            Path diag = dir.resolve("diagnostics"); Files.createDirectories(diag);
            Properties beat = new Properties(); beat.setProperty("session-start", Long.toString(System.currentTimeMillis() - 3600_000)); beat.setProperty("last-beat", Long.toString(System.currentTimeMillis() - 120_000)); beat.setProperty("clean", "false"); beat.setProperty("heap-max", "4294967296");
            AtomicState.save(diag.resolve("heartbeat.properties"), beat, "test");
            Files.writeString(diag.resolve("live-log.txt"), "[2026-11-03 20:00:00 INFO] [Server] Steve[/198.51.100.4:5000] logged in\n[2026-11-03 20:01:00 ERROR] [Server] java.lang.OutOfMemoryError: Java heap space\n");
            DiagnosticsCenter first = new DiagnosticsCenter(plugin);
            plugin.diagnostics = first;
            Map<String, Object> crashes = invokeRoute(first, "crashes");
            check(crashes.size() == 1, "one crash report");
            Map<String, Object> crash = (Map<String, Object>) crashes.values().iterator().next();
            check("oom".equals(crash.get("kind")) && String.valueOf(crash.get("title")).contains("OutOfMemoryError") && ((Number) crash.get("downtimeSeconds")).longValue() >= 100, "OOM crash classified with downtime");
            check(((List<String>) crash.get("log")).stream().noneMatch(l -> l.contains("198.51.100.4")) && ((List<String>) crash.get("log")).stream().anyMatch(l -> l.contains("x.x.x.x")), "crash log redacted");
            check(Files.list(diag.resolve("crashes")).count() == 1, "crash report stored on disk");
            first.start();
            first.stop();
            Properties after = new Properties(); try (var r = Files.newBufferedReader(diag.resolve("heartbeat.properties"))) { after.load(r); }
            check("true".equals(after.getProperty("clean")), "clean shutdown flag written");
            DiagnosticsCenter second = new DiagnosticsCenter(plugin);
            check(invokeRoute(second, "crashes").size() == 1, "clean restart adds no crash (old report kept)");
            final DiagnosticsCenter center = second; plugin.diagnostics = center;

            // ---- quits, console reasons and a burst
            List<Player> players = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                UUID id = UUID.randomUUID(); String name = "Spieler" + i;
                Player pl = AppApiIntegrationHarness.proxy(Player.class, (p, m, a) -> switch (m.getName()) { case "getUniqueId" -> id; case "getName" -> name; case "getLocation" -> new Location(world, 100 + 3, 64, 200); case "getPing" -> 55; case "hashCode" -> id.hashCode(); case "equals" -> p == a[0]; default -> AppApiIntegrationHarness.empty(m.getReturnType()); });
                players.add(pl); online.put(id, pl);
            }
            center.onQuit(new PlayerQuitEvent(players.get(0), "x", PlayerQuitEvent.QuitReason.DISCONNECTED));
            check(center.recordCount() == 1 && center.recordsSnapshot().get(0).category().equals("CLIENT_QUIT"), "quit without reason = own quit");
            center.accept(System.currentTimeMillis(), "INFO", "net.minecraft.server.network.ServerGamePacketListenerImpl", "Spieler0 lost connection: Timed out");
            check(center.recordsSnapshot().get(0).category().equals("TIMEOUT") && center.recordsSnapshot().get(0).message().equals("Timed out"), "console reason attached afterwards");
            center.accept(System.currentTimeMillis(), "INFO", "net.minecraft.server.network.ServerGamePacketListenerImpl", "Spieler1 lost connection: Internal Exception: java.net.SocketException: Connection reset");
            online.remove(players.get(0).getUniqueId());
            center.onQuit(new PlayerQuitEvent(players.get(1), "x", PlayerQuitEvent.QuitReason.DISCONNECTED));
            check(center.recordsSnapshot().get(1).category().equals("RESET") && center.recordsSnapshot().get(1).chunkX() == 6, "console reason before the quit event is used; chunk stored");
            online.remove(players.get(1).getUniqueId());
            center.onQuit(new PlayerQuitEvent(players.get(2), "x", PlayerQuitEvent.QuitReason.TIMED_OUT));
            online.remove(players.get(2).getUniqueId());
            Map<String, Object> overview = route(center, "/v1/diagnostics/disconnects", Map.of());
            List<Map<String, Object>> incidents = (List<Map<String, Object>>) overview.get("incidents");
            check(incidents.size() == 1 && ((Number) incidents.get(0).get("count")).intValue() == 3 && "problem".equals(incidents.get(0).get("verdict")), "three involuntary quits within 10 s = incident: " + incidents);
            check(((List<?>) overview.get("items")).size() == 3 && ((List<Integer>) overview.get("hours")).get(23) == 3, "list and 24 h histogram");
            String incidentId = String.valueOf(incidents.get(0).get("id"));
            Map<String, Object> detail = route(center, "/v1/diagnostics/incident", Map.of("id", incidentId));
            check(((List<?>) detail.get("records")).size() == 3 && detail.get("state") instanceof Map<?, ?> && detail.get("logs") instanceof List<?> logs && logs.stream().anyMatch(l -> String.valueOf(((Map<?, ?>) l).get("text")).contains("lost connection")), "incident detail with records, state and relevant console lines");
            String input = center.aiInput("incident", incidentId);
            check(input.contains("VORFALL") && input.contains("TIMEOUT") && input.length() < 9000 && !input.contains("198.51.100"), "compact AI input");
            Map<String, Object> mine = center.player(players.get(1).getUniqueId().toString());
            check(((Number) mine.get("week")).intValue() == 1 && ((Number) mine.get("involuntary")).intValue() == 1 && "Spieler1".equals(mine.get("name")), "per-player connection history");
            fails(() -> center.startAi("incident", incidentId, "Test"), "AI refuses without a key");
            fails(() -> center.saveSettings(Map.of("burstMin", "1")), "settings validated (min)");
            fails(() -> center.saveSettings(Map.of("app-api.enabled", "true")), "unknown settings rejected");
            check(((Map<String, Object>) center.saveSettings(Map.of("burstMin", "4", "aiEnabled", "false")).get("settings")).get("burstMin").equals(4) && !plugin.getConfig().getBoolean("diagnostics.ai.enabled", true), "settings saved");
            plugin.getConfig().set("diagnostics.burst.min-players", 3);
            Map<String, Object> full = center.finishFull(center.fullSnapshot(), List.of(DiagnosticsCenter.check("network", "Test", "warn", "1", "x")));
            check(((Number) full.get("score")).intValue() <= 95 && ((List<?>) full.get("checks")).size() >= 15, "full diagnosis with score");

            // ---- HTTPS: diagnostics endpoints and the command-switch fix
            int port; try (var socket = new java.net.ServerSocket(0)) { port = socket.getLocalPort(); }
            plugin.getConfig().set("app-api.enabled", true); plugin.getConfig().set("app-api.bind", "127.0.0.1"); plugin.getConfig().set("app-api.port", port); plugin.getConfig().set("app-api.transport", "tls");
            api = new AppApiManager(plugin); plugin.appApi = api; api.reload();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30); while (!api.active() && System.nanoTime() < deadline) Thread.sleep(50); check(api.active(), "TLS listener started");
            SSLContext ssl = SSLContext.getInstance("TLS"); ssl.init(null, new TrustManager[]{new X509TrustManager() { public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; } public void checkClientTrusted(X509Certificate[] c, String a) { } public void checkServerTrusted(X509Certificate[] c, String a) { } }}, null);
            HttpClient client = HttpClient.newBuilder().sslContext(ssl).connectTimeout(java.time.Duration.ofSeconds(5)).build(); URI base = URI.create("https://127.0.0.1:" + port);
            api.security.users.createOwner("juhas", "owner-password-1020");
            api.security.users.save("mod", "mod-password-10200", Set.of("data.commands", "action.commands.update", "data.diagnostics"), true);
            api.security.users.save("viewer", "viewer-password-1020", Set.of("data.profile"), true);
            String owner = login(client, base, "juhas", "owner-password-1020"), mod = login(client, base, "mod", "mod-password-10200"), viewer = login(client, base, "viewer", "viewer-password-1020");
            List<?> features = (List<?>) obj(call(client, base, "/v1/health", owner, null).body()).get("features");
            check(features.containsAll(List.of("diagnostics-v1", "lag-map-v1", "spark-v1", "crash-manager-v1", "player-connection-v1", "command-rights-v2", "command-rights-v1")), "health advertises 10.2.0 features");
            var ov = call(client, base, "/v1/diagnostics/overview", mod, null);
            check(ov.statusCode() == 200 && obj(ov.body()).get("live") instanceof Map<?, ?> && obj(ov.body()).get("series") instanceof Map<?, ?> && Boolean.FALSE.equals(obj(ov.body()).get("canRun")), "overview for data.diagnostics (read-only rights): " + ov.body());
            check(call(client, base, "/v1/diagnostics/overview", viewer, null).statusCode() == 403, "overview needs data.diagnostics");
            check(call(client, base, "/v1/diagnostics/player?player=" + players.get(1).getUniqueId(), viewer, null).statusCode() == 200, "player connection view with data.profile");
            check(call(client, base, "/v1/diagnostics/full", mod, Map.of()).statusCode() == 403, "running a diagnosis needs diagnostics.run");
            check(call(client, base, "/v1/diagnostics/spark", mod, Map.of("operation", "start", "seconds", "60")).statusCode() == 403, "profiler control needs diagnostics.manage");
            var logs = obj(call(client, base, "/v1/diagnostics/logs?level=INFO&q=lost", owner, null).body());
            check(((List<?>) logs.get("items")).size() == 2, "console search");
            check(call(client, base, "/v1/diagnostics/settings", mod, Map.of("burstMin", "5")).statusCode() == 403, "settings need full access");
            check(call(client, base, "/v1/diagnostics/incident", owner, Map.of("id", incidentId, "operation", "resolve", "note", "Hoster-Störung")).statusCode() == 200
                && Boolean.TRUE.equals(obj(call(client, base, "/v1/diagnostics/incident?id=" + incidentId, owner, null).body()).get("resolved")), "incident resolved with note");
            var importBad = call(client, base, "/v1/diagnostics/spark", owner, Map.of("operation", "import", "code", "../../etc"));
            check(importBad.statusCode() == 400, "spark import validates the code");

            UUID steve = UUID.fromString("00000000-0000-0000-0000-00000000a020");
            Player stevePlayer = AppApiIntegrationHarness.proxy(Player.class, (p, m, a) -> switch (m.getName()) { case "getUniqueId" -> steve; case "getName" -> "Steve"; case "getServer" -> server; case "isOnline" -> true; case "hasPermission" -> true; case "hashCode" -> steve.hashCode(); case "equals" -> p == a[0]; default -> AppApiIntegrationHarness.empty(m.getReturnType()); });
            online.put(steve, stevePlayer);
            // 10.1.0 rejected every switch: block without "grant", allow without "alts".
            for (Map<String, Object> values : List.of(Map.<String, Object>of("operation", "block", "player", steve.toString(), "commands", "tpa", "duration", "", "reason", "", "alts", "true"),
                    Map.<String, Object>of("operation", "allow", "player", steve.toString(), "commands", "tpa", "grant", "false"),
                    Map.<String, Object>of("operation", "reset", "player", steve.toString(), "commands", "tpa"))) {
                var plan = call(client, base, "/v1/prepare", mod, Map.of("path", "commands.update", "values", values));
                check(plan.statusCode() == 200 && !plan.body().contains("Pflichtfeld"), "commands.update " + values.get("operation") + " without optional switches: " + plan.body());
                var done = call(client, base, "/v1/execute", mod, Map.of("ticket", obj(plan.body()).get("ticket")));
                check(done.statusCode() == 200, "executed " + values.get("operation") + ": " + done.body());
            }
            var bad = call(client, base, "/v1/prepare", mod, Map.of("path", "commands.update", "values", Map.of("operation", "block", "player", steve.toString(), "commands", "tpa", "alts", "maybe")));
            check(bad.statusCode() == 400 && bad.body().contains("Ungültige Auswahl"), "optional choices still validated");
            var appStyle = call(client, base, "/v1/prepare", mod, Map.of("path", "commands.update", "values", Map.of("operation", "block", "player", steve.toString(), "commands", "home", "duration", "1d", "reason", "Test", "alts", "false", "grant", "false")));
            check(appStyle.statusCode() == 200, "app 4.5 sends both fields");
            check(call(client, base, "/v1/execute", mod, Map.of("ticket", obj(appStyle.body()).get("ticket"))).statusCode() == 200 && "blocked".equals(((Map<String, Object>) ((List<?>) plugin.commandRights.player(steve, null).get("items")).stream().map(o -> (Map<String, Object>) o).filter(r -> "home".equals(r.get("name"))).findFirst().orElseThrow()).get("state")), "switch off really blocks /home");
            Map<String, Object> catalog = obj(call(client, base, "/v1/data/access", owner, null).body());
            check(((List<Map<String, Object>>) catalog.get("catalog")).stream().map(r -> r.get("id")).toList().containsAll(List.of("data.diagnostics", "diagnostics.run", "diagnostics.manage")), "rights catalog lists diagnostics rights");
            check(((List<Map<String, Object>>) catalog.get("presets")).stream().filter(r -> "admin".equals(r.get("id"))).findFirst().map(r -> ((List<?>) r.get("permissions")).contains("diagnostics.run")).orElse(false), "admin preset includes diagnostics");
            check(SuiteEventCenter.TYPES.containsAll(List.of("disconnect-wave", "crash", "lag-spike")) && PanelHub.EVENT_TITLES.containsKey("disconnect-wave"), "Bark and browser push know the new events");
            for (String asset : List.of("/assets/diagnostics.js")) check(client.send(HttpRequest.newBuilder(base.resolve(asset)).build(), HttpResponse.BodyHandlers.ofString()).statusCode() == 200, "asset served: " + asset);
            check(client.send(HttpRequest.newBuilder(base.resolve("/")).build(), HttpResponse.BodyHandlers.ofString()).body().contains("/assets/diagnostics.js"), "index loads diagnostics.js");
        } finally {
            if (api != null) try { Method stop = AppApiManager.class.getDeclaredMethod("stopListener"); stop.setAccessible(true); stop.invoke(api); } catch (Exception ignored) { }
            serverField.set(null, old);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> invokeRoute(DiagnosticsCenter center, String field) throws Exception { Field f = DiagnosticsCenter.class.getDeclaredField(field); f.setAccessible(true); return new LinkedHashMap<>((Map<String, Object>) f.get(center)); }
    @SuppressWarnings("unchecked")
    static Map<String, Object> route(DiagnosticsCenter center, String path, Map<String, String> query) throws Exception {
        return switch (path) {
            case "/v1/diagnostics/disconnects" -> { Method m = DiagnosticsCenter.class.getDeclaredMethod("disconnects", Map.class); m.setAccessible(true); yield (Map<String, Object>) m.invoke(center, query); }
            case "/v1/diagnostics/incident" -> { Field f = DiagnosticsCenter.class.getDeclaredField("incidents"); f.setAccessible(true); yield (Map<String, Object>) ((Map<String, Object>) f.get(center)).get(query.get("id")); }
            default -> throw new IllegalArgumentException(path);
        };
    }
    static String login(HttpClient client, URI base, String user, String password) throws Exception { return String.valueOf(obj(call(client, base, "/v1/login", null, Map.of("username", user, "password", password)).body()).get("token")); }
    static HttpResponse<String> call(HttpClient client, URI base, String path, String token, Object body) throws Exception {
        var b = HttpRequest.newBuilder(base.resolve(path)).timeout(java.time.Duration.ofSeconds(40));
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (body != null) b.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(DiscordBotManager.Json.stringify(body)));
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
