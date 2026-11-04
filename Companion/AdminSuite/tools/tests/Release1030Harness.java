package eu.simplexsmp.adminsuite;

import java.lang.reflect.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javax.net.ssl.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.*;

/**
 * 10.3.0: world trimmer safety (region integrity, safety copy + restore, heap validation) and app/web control with
 * new rights, Discord status designer (presets, validation, exact preview), scoreboard designer, player dossier,
 * Bedrock bridge identity fallback (no more HTTP 422) and new ClientData claims. New rights must not appear in any
 * role template.
 */
public final class Release1030Harness {
    static int checks;
    static void check(boolean value, String reason) { checks++; if (!value) throw new AssertionError(reason); }
    static void fails(Callable<?> work, String reason) { try { work.call(); } catch (Exception | AssertionError e) { checks++; return; } throw new AssertionError("Expected failure: " + reason); }
    @SuppressWarnings("unchecked") static Map<String, Object> obj(String json) { return (Map<String, Object>) DiscordBotManager.Json.parse(json); }

    public static void main(String[] args) throws Exception {
        System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
        regions();
        designers();
        bedrock();
        api();
        System.out.println("PASS " + checks + " 10.3.0 world trimmer safety, studio designers, dossier, bridge identity and rights checks");
    }

    /** Builds a minimal Anvil region file with the given chunk entries (sector offset, sector count, length, compression). */
    static byte[] region(int[][] chunks, int sectors) {
        ByteBuffer b = ByteBuffer.allocate(sectors * 4096);
        int index = 0;
        for (int[] c : chunks) {
            b.putInt(index * 4, (c[0] << 8) | c[1]);
            if ((c[0] + 1) * 4096 <= b.capacity()) { b.putInt(c[0] * 4096, c[2]); b.put(c[0] * 4096 + 4, (byte) c[3]); }
            index++;
        }
        return b.array();
    }

    static void regions() throws Exception {
        Path dim = Files.createTempDirectory("trim1030-"); Path reg = dim.resolve("region"); Files.createDirectories(reg);
        Files.write(reg.resolve("r.0.0.mca"), region(new int[][]{{2, 1, 100, 2}, {3, 2, 5000, 1}}, 5));
        Files.write(reg.resolve("r.1.0.mca"), new byte[0]);
        Files.write(reg.resolve("r.2.0.mca"), region(new int[][]{{2, 1, 200, 130}}, 3));
        RegionSafety.Report ok = RegionSafety.check(List.of(dim), 0L, 10);
        check(ok.ok() && ok.files() == 3 && ok.chunks() == 3, "valid region files incl. empty file and external .mcc chunk: " + ok);
        Files.write(reg.resolve("r.3.0.mca"), Arrays.copyOf(region(new int[][]{{2, 1, 100, 2}}, 3), 5000));
        Files.write(reg.resolve("r.4.0.mca"), region(new int[][]{{9, 1, 100, 2}}, 3));
        Files.write(reg.resolve("r.5.0.mca"), region(new int[][]{{2, 1, 100, 9}}, 3));
        Files.write(reg.resolve("r.6.0.mca"), region(new int[][]{{2, 1, 9000, 2}}, 3));
        RegionSafety.Report bad = RegionSafety.check(List.of(dim), 0L, 10);
        check(bad.problems().size() == 4 && bad.problems().stream().anyMatch(p -> p.contains("Header unvollständig")) && bad.problems().stream().anyMatch(p -> p.contains("Dateiende"))
            && bad.problems().stream().anyMatch(p -> p.contains("Komprimierung")) && bad.problems().stream().anyMatch(p -> p.contains("Länge")), "damaged files reported: " + bad.problems());
        check(RegionSafety.check(List.of(dim), System.currentTimeMillis() + 60_000L, 10).files() == 0, "only files changed during the run are checked");
        for (String n : List.of("r.3.0.mca", "r.4.0.mca", "r.5.0.mca", "r.6.0.mca")) Files.delete(reg.resolve(n));
        Path poi = dim.resolve("poi"); Files.createDirectories(poi); Files.write(poi.resolve("r.0.0.mca"), region(new int[][]{{2, 1, 10, 2}}, 3));
        Path snap = Files.createTempDirectory("trim1030-snap-").resolve("op");
        long copied = RegionSafety.snapshot(List.of(dim), snap);
        check(copied > 0 && Files.isRegularFile(snap.resolve("complete")) && Files.isRegularFile(snap.resolve("0/region/r.0.0.mca")) && Files.isRegularFile(snap.resolve("0/poi/r.0.0.mca")), "safety copy of region and poi");
        byte[] original = Files.readAllBytes(reg.resolve("r.0.0.mca"));
        Files.delete(reg.resolve("r.0.0.mca")); Files.write(reg.resolve("r.2.0.mca"), new byte[]{1, 2, 3});
        int restored = RegionSafety.restore(List.of(dim), snap);
        check(restored == 4 && Arrays.equals(original, Files.readAllBytes(reg.resolve("r.0.0.mca"))) && Files.size(reg.resolve("r.2.0.mca")) == 3 * 4096, "restore brings back deleted and damaged files");
        check(!Files.exists(reg.resolve("r.0.0.mca.sas-restore")), "no temp files left");
        Path other = Files.createTempDirectory("trim1030-other-");
        fails(() -> RegionSafety.restore(List.of(other), snap), "snapshot refuses another dimension");
        Files.delete(snap.resolve("complete"));
        fails(() -> RegionSafety.restore(List.of(dim), snap), "incomplete snapshot is never restored");
        RegionSafety.delete(snap.getParent());
        check(!Files.exists(snap), "snapshot removed");
        check(RegionSafety.heap("4G").equals("4G") && RegionSafety.heap("2048m").equals("2048M") && RegionSafety.heap("3G -Dx=1").equals("3G") && RegionSafety.heap("").equals("3G") && RegionSafety.heap("0G").equals("3G"), "heap option validated");
        check(WorldTrimManager.quiescenceDecision(5, true, true, 3, 3) == WorldTrimManager.QuiescenceDecision.REQUEST_OVERRIDE, "existing barrier unchanged");
    }

    @SuppressWarnings("unchecked")
    static void designers() throws Exception {
        Set<String> keys = new HashSet<>(); for (StudioDesigner.Field f : StudioDesigner.STATUS_FIELDS) check(keys.add(f.key()), "unique field " + f.key());
        for (Map<String, Object> preset : StudioDesigner.statusPresets()) for (var e : ((Map<String, Object>) preset.get("values")).entrySet()) {
            StudioDesigner.Field f = StudioDesigner.statusField(e.getKey());
            check(f != null, "preset " + preset.get("id") + " uses known key " + e.getKey());
            StudioDesigner.validStatus(f, e.getValue());
        }
        check(((Map<?, ?>) StudioDesigner.statusPresets().get(0).get("values")).isEmpty() && "standard".equals(StudioDesigner.statusPresets().get(0).get("id")), "standard preset = shipped defaults");
        org.bukkit.configuration.file.YamlConfiguration shipped = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(Release1030Harness.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
        for (StudioDesigner.Field f : StudioDesigner.STATUS_FIELDS) {
            Object value = shipped.get(f.path());
            check(value != null, "config.yml ships " + f.path());
            check(String.valueOf(value).equals(String.valueOf(f.fallback())), "default of " + f.key() + " equals config.yml: " + value + " vs " + f.fallback());
        }
        check(StudioDesigner.validStatus(StudioDesigner.statusField("healthy-color"), "#00f5d4").equals("#00F5D4"), "colour normalised");
        fails(() -> StudioDesigner.validStatus(StudioDesigner.statusField("healthy-color"), "red"), "colour validated");
        fails(() -> StudioDesigner.validStatus(StudioDesigner.statusField("thumbnail-url"), "http://example.com/a.png"), "only https images");
        fails(() -> StudioDesigner.validStatus(StudioDesigner.statusField("warning-tps"), "25"), "tps range");
        fails(() -> StudioDesigner.validStatus(StudioDesigner.statusField("online-title"), "x".repeat(300)), "title length");
        fails(() -> StudioDesigner.validStatus(StudioDesigner.statusField("online-title"), "a\nb"), "single-line title");
        check(StudioDesigner.validStatus(StudioDesigner.statusField("online-description"), "a\nb").equals("a\nb"), "multiline description");
        check(StudioDesigner.validStatus(StudioDesigner.statusField("max-player-lines"), "10").equals(10) && StudioDesigner.statusField("max-player-lines").path().equals("discord-bot.status.max-player-lines")
            && StudioDesigner.statusField("presence.healthy").path().equals("discord-bot.presence.healthy"), "special config paths");
        Map<String, Object> standardBoard = StudioDesigner.scoreboardPresets().get(0);
        check(standardBoard.get("boardTitle").equals(shipped.getString("scoreboard.title")) && standardBoard.get("lines").equals(shipped.getStringList("scoreboard.lines")), "standard scoreboard preset = shipped scoreboard");
        check(StudioDesigner.gradient("simplexsmp.eu", "00A8E8", "80BDF9", true).startsWith("&x&0&0&A&8&E&8&ls") && StudioDesigner.gradient("ab", "000000", "FFFFFF", false).equals("&x&0&0&0&0&0&0a&x&F&F&F&F&F&Fb"), "gradient title like the shipped one");
        for (Map<String, Object> preset : StudioDesigner.scoreboardPresets()) { StudioDesigner.validTitle(preset.get("boardTitle")); StudioDesigner.validLines(preset.get("lines")); }
        check(StudioDesigner.validLines(List.of("§aA", "")).equals(List.of("&aA", " ")), "lines normalised");
        fails(() -> StudioDesigner.validLines(Collections.nCopies(16, "x")), "max 15 lines");
        fails(() -> StudioDesigner.validLines(List.of("x".repeat(181))), "line length");
        fails(() -> StudioDesigner.validTitle(""), "title required");
    }

    static void bedrock() throws Exception {
        Method fg = Class.forName("eu.simplexsmp.adminsuite.SimplexAdminSuite$AdvancedAltDetection").getDeclaredMethod("floodgateUuid", String.class); fg.setAccessible(true);
        check(new UUID(0L, 2533274799999999L).equals(fg.invoke(null, "2533274799999999")), "Floodgate UUID from XUID");
        check(fg.invoke(null, "") == null && fg.invoke(null, "abc") == null && fg.invoke(null, "0") == null, "invalid XUIDs ignored");
        String json = "{\"ThirdPartyName\":\"Steve TP\",\"SkinGeometryDataEngineVersion\":\"" + Base64.getEncoder().encodeToString("1.21.80".getBytes(StandardCharsets.UTF_8)) + "\",\"IsEditorMode\":false,\"CapeImageWidth\":64,\"SkinData\":\"DO_NOT_KEEP\"}";
        Map<String, String> claims = BedrockMetadata.decodeClientData("h." + Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8)) + ".s");
        check("Steve TP".equals(claims.get("ThirdPartyName")) && !claims.containsKey("SkinData"), "new claims allowlisted, skin data dropped");
        check(BedrockMetadata.engineVersion(Base64.getEncoder().encodeToString("1.21.80".getBytes(StandardCharsets.UTF_8))).equals("1.21.80") && BedrockMetadata.engineVersion("bm9wZQ==").isEmpty() && BedrockMetadata.engineVersion("1.20.0").equals("1.20.0"), "geometry engine decoded safely");
        Properties p = new Properties(); p.setProperty("thirdPartyName", "Steve TP"); p.setProperty("lastDisconnectReason", "Client closed"); p.setProperty("virtualHost", "play.example.net:25565"); p.setProperty("floodgateLinked", "true"); p.setProperty("unknownKey", "x");
        Map<String, String> stored = DeviceDiagnostics.decode(DeviceDiagnostics.encode(DeviceDiagnostics.from(p)));
        check(stored.get("thirdPartyName").equals("Steve TP") && stored.get("lastDisconnectReason").equals("Client closed") && stored.get("virtualHost").equals("play.example.net:25565") && stored.get("floodgateLinked").equals("true") && !stored.containsKey("unknownKey"), "new diagnostic fields survive storage: " + stored);
    }

    @SuppressWarnings("unchecked")
    static void api() throws Exception {
        Path dir = Files.createTempDirectory("suite1030-");
        Field serverField = Bukkit.class.getDeclaredField("server"); serverField.setAccessible(true); Object old = serverField.get(null);
        AppApiManager api = null;
        try {
            BukkitTask task = AppApiIntegrationHarness.proxy(BukkitTask.class, (p, m, a) -> AppApiIntegrationHarness.empty(m.getReturnType()));
            BukkitScheduler scheduler = AppApiIntegrationHarness.proxy(BukkitScheduler.class, (p, m, a) -> { if (a != null) for (Object item : a) { if (item instanceof Runnable work) work.run(); else if (item instanceof Consumer<?> c) ((Consumer<BukkitTask>) c).accept(task); } return m.getReturnType() == BukkitTask.class ? task : AppApiIntegrationHarness.empty(m.getReturnType()); });
            org.bukkit.plugin.PluginManager plugins = AppApiIntegrationHarness.proxy(org.bukkit.plugin.PluginManager.class, (p, m, a) -> switch (m.getName()) { case "getPlugins" -> new org.bukkit.plugin.Plugin[0]; case "getPermissions" -> Set.of(); default -> AppApiIntegrationHarness.empty(m.getReturnType()); });
            var console = AppApiIntegrationHarness.proxy(org.bukkit.command.ConsoleCommandSender.class, (p, m, a) -> switch (m.getName()) { case "getName" -> "Console"; case "hasPermission", "isOp", "isPermissionSet" -> true; default -> AppApiIntegrationHarness.empty(m.getReturnType()); });
            World world = AppApiIntegrationHarness.proxy(World.class, (p, m, a) -> switch (m.getName()) { case "getName" -> "world"; case "getUID" -> new UUID(1, 1); case "getLoadedChunks" -> new Chunk[0]; default -> AppApiIntegrationHarness.empty(m.getReturnType()); });
            Server server = AppApiIntegrationHarness.proxy(Server.class, (p, m, a) -> switch (m.getName()) {
                case "getName", "getVersion", "getBukkitVersion", "getMinecraftVersion" -> "Harness1030";
                case "getPluginManager" -> plugins; case "getScheduler" -> scheduler; case "getConsoleSender" -> console;
                case "getLogger" -> java.util.logging.Logger.getLogger("Harness1030"); case "isPrimaryThread" -> true;
                case "getOnlinePlayers" -> List.of(); case "getWorlds" -> List.of(world); case "getOperators" -> Set.of(); case "getTPS" -> new double[]{19.9, 19.8, 19.7}; case "getWorldContainer" -> dir.toFile(); case "getMaxPlayers" -> 50;
                default -> AppApiIntegrationHarness.empty(m.getReturnType()); });
            serverField.set(null, server);
            SimplexAdminSuite plugin = Moderation790Harness.plugin(dir); Moderation790Harness.set(plugin, JavaPlugin.class, "isEnabled", true);
            Moderation790Harness.set(plugin, SimplexAdminSuite.class, "adminActor", new ThreadLocal<String>()); Moderation790Harness.set(plugin, SimplexAdminSuite.class, "pendingInputs", new ConcurrentHashMap<>()); Moderation790Harness.set(plugin, SimplexAdminSuite.class, "pendingActions", new ConcurrentHashMap<>());
            Moderation790Harness.set(plugin, SimplexAdminSuite.class, "auditedConfig", new HashMap<String, String>()); plugin.moderationControl = new ModerationControl(plugin);
            plugin.adminAccess = new AdminAccessManager(plugin); plugin.adminUpdate = new AdminUpdateMenu(plugin); plugin.adminLog = new AdminActivityLog(plugin);
            Moderation790Harness.set(plugin, JavaPlugin.class, "description", new org.bukkit.plugin.PluginDescriptionFile("SimplexAdminSuite", "10.3.0", SimplexAdminSuite.class.getName()));
            var dbConstructor = SimplexAdminSuite.PlayerDatabase.class.getDeclaredConstructor(SimplexAdminSuite.class); dbConstructor.setAccessible(true); Moderation790Harness.set(plugin, SimplexAdminSuite.class, "database", dbConstructor.newInstance(plugin));
            plugin.getConfig().setDefaults(org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(Release1030Harness.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8)));
            // A stored player with Bedrock diagnostics and a warning for the dossier.
            UUID steve = UUID.fromString("00000000-0000-0000-0000-00000000a030");
            Constructor<SimplexAdminSuite.PlayerProfile> pc = SimplexAdminSuite.PlayerProfile.class.getDeclaredConstructor(UUID.class, String.class); pc.setAccessible(true);
            SimplexAdminSuite.PlayerProfile profile = pc.newInstance(steve, "Steve");
            Field profiles = SimplexAdminSuite.PlayerDatabase.class.getDeclaredField("profiles"); profiles.setAccessible(true);
            Field dbField = SimplexAdminSuite.class.getDeclaredField("database"); dbField.setAccessible(true);
            ((Map<UUID, SimplexAdminSuite.PlayerProfile>) profiles.get(dbField.get(plugin))).put(steve, profile);
            Field bd = SimplexAdminSuite.PlayerProfile.class.getDeclaredField("bedrockDiagnostics"); bd.setAccessible(true); ((Map<String, String>) bd.get(profile)).put("lastDisconnectReason", "Timed out");
            Field warnings = SimplexAdminSuite.PlayerProfile.class.getDeclaredField("warnings"); warnings.setAccessible(true); ((List<String>) warnings.get(profile)).add("Spam");
            Field cp = SimplexAdminSuite.PlayerProfile.class.getDeclaredField("clientPlatform"); cp.setAccessible(true); cp.set(profile, "BEDROCK");
            plugin.appEvents = new SuiteEventCenter(plugin);
            WorldTrimManager trimmer = new WorldTrimManager(plugin);
            Moderation790Harness.set(plugin, SimplexAdminSuite.class, "worldTrimmer", trimmer);
            DiscordBotManager bot = null;
            try { bot = new DiscordBotManager(plugin); Moderation790Harness.set(plugin, SimplexAdminSuite.class, "discordBot", bot); } catch (Throwable error) { System.out.println("INFO: Discord bot not constructible in harness: " + error); }

            int port; try (var socket = new java.net.ServerSocket(0)) { port = socket.getLocalPort(); }
            plugin.getConfig().set("app-api.enabled", true); plugin.getConfig().set("app-api.bind", "127.0.0.1"); plugin.getConfig().set("app-api.port", port); plugin.getConfig().set("app-api.transport", "tls");
            api = new AppApiManager(plugin); plugin.appApi = api; api.reload();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30); while (!api.active() && System.nanoTime() < deadline) Thread.sleep(50); check(api.active(), "TLS listener started");
            SSLContext ssl = SSLContext.getInstance("TLS"); ssl.init(null, new TrustManager[]{new X509TrustManager() { public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; } public void checkClientTrusted(X509Certificate[] c, String a) { } public void checkServerTrusted(X509Certificate[] c, String a) { } }}, null);
            HttpClient client = HttpClient.newBuilder().sslContext(ssl).connectTimeout(java.time.Duration.ofSeconds(5)).build(); URI base = URI.create("https://127.0.0.1:" + port);
            api.security.users.createOwner("juhas", "owner-password-1030");
            api.security.users.save("watcher", "watcher-password-1030", Set.of("data.world-trim", "data.scoreboard", "data.discord-status", "data.profile"), true);
            api.security.users.save("viewer", "viewer-password-1030", Set.of("data.profile"), true);
            String owner = Release1020Harness.login(client, base, "juhas", "owner-password-1030"), watcher = Release1020Harness.login(client, base, "watcher", "watcher-password-1030"), viewer = Release1020Harness.login(client, base, "viewer", "viewer-password-1030");

            List<?> features = (List<?>) obj(Release1020Harness.call(client, base, "/v1/health", owner, null).body()).get("features");
            check(features.containsAll(List.of("world-trim-v1", "discord-status-designer-v1", "scoreboard-designer-v1", "player-dossier-v1", "bridge-v3", "diagnostics-v1")), "health advertises 10.3.0 features");
            Map<String, Object> catalog = obj(Release1020Harness.call(client, base, "/v1/data/access", owner, null).body());
            List<String> ids = ((List<Map<String, Object>>) catalog.get("catalog")).stream().map(r -> String.valueOf(r.get("id"))).toList();
            List<String> fresh = List.of("data.world-trim", "world-trim.run", "data.discord-status", "discord-status.edit", "data.scoreboard", "scoreboard.edit");
            check(ids.containsAll(fresh), "rights catalog lists the new rights");
            check(((List<Map<String, Object>>) catalog.get("catalog")).stream().anyMatch(r -> "world-trim.run".equals(r.get("id")) && "critical".equals(r.get("level"))), "trimmer right marked critical");
            for (Map<String, Object> preset : (List<Map<String, Object>>) catalog.get("presets")) if (!"full".equals(preset.get("id")))
                check(((List<?>) preset.get("permissions")).stream().noneMatch(fresh::contains), "role template " + preset.get("id") + " does not contain new rights");

            // ---- world trimmer
            check(Release1020Harness.call(client, base, "/v1/world-trim", viewer, null).statusCode() == 403, "trimmer status needs data.world-trim");
            var state = Release1020Harness.call(client, base, "/v1/world-trim", watcher, null);
            check(state.statusCode() == 200 && "IDLE".equals(obj(state.body()).get("phase")) && Boolean.FALSE.equals(obj(state.body()).get("canRun")) && obj(state.body()).get("safety") instanceof Map<?, ?>, "watcher sees the trimmer read-only: " + state.body());
            check(Release1020Harness.call(client, base, "/v1/world-trim", watcher, Map.of("operation", "scan")).statusCode() == 403, "trimmer actions need world-trim.run");
            var noBackup = Release1020Harness.call(client, base, "/v1/world-trim", owner, Map.of("operation", "start", "targets", List.of("x"), "confirm", "TRIMMEN"));
            check(noBackup.statusCode() == 400 && noBackup.body().contains("Backup"), "start needs the backup confirmation");
            var noWord = Release1020Harness.call(client, base, "/v1/world-trim", owner, Map.of("operation", "start", "targets", List.of("x"), "backupConfirmed", true, "confirm", "ja"));
            check(noWord.statusCode() == 400 && noWord.body().contains("TRIMMEN"), "start needs the typed confirmation");
            var unknown = Release1020Harness.call(client, base, "/v1/world-trim", owner, Map.of("operation", "start", "targets", List.of("nope"), "backupConfirmed", true, "confirm", "trimmen", "threshold", 1200, "countdown", 0));
            check(unknown.statusCode() == 200 && "IDLE".equals(obj(unknown.body()).get("phase")) && String.valueOf(obj(unknown.body()).get("messages")).contains("Dimension"), "unknown dimension is refused by the trimmer itself: " + unknown.body());
            check(Release1020Harness.call(client, base, "/v1/world-trim", owner, Map.of("operation", "explode")).statusCode() == 400, "unknown trimmer action");

            // ---- scoreboard designer
            var board = obj(Release1020Harness.call(client, base, "/v1/studio/scoreboard", watcher, null).body());
            check(board.get("title").equals(StudioDesigner.SCOREBOARD_DEFAULT_TITLE) && ((List<?>) board.get("presets")).size() >= 6 && Boolean.FALSE.equals(board.get("canEdit")) && ((Map<?, ?>) board.get("sample")).containsKey("{online}"), "scoreboard designer data");
            check(Release1020Harness.call(client, base, "/v1/studio/scoreboard", watcher, Map.of("operation", "save", "title", "&aX", "lines", List.of("a"))).statusCode() == 403, "saving needs scoreboard.edit");
            check(Release1020Harness.call(client, base, "/v1/studio/scoreboard", owner, Map.of("operation", "save", "title", "&aX", "lines", Collections.nCopies(16, "a"))).statusCode() == 400, "16 lines rejected");
            var saved = Release1020Harness.call(client, base, "/v1/studio/scoreboard", owner, Map.of("operation", "save", "title", "&b&lTest", "lines", List.of("&fOnline &b{online}", "")));
            check(saved.statusCode() == 200 && plugin.getConfig().getString("scoreboard.title").equals("&b&lTest") && plugin.getConfig().getStringList("scoreboard.lines").equals(List.of("&fOnline &b{online}", " ")), "scoreboard saved: " + saved.body());

            // ---- Discord status designer
            if (bot != null) {
                var status = Release1020Harness.call(client, base, "/v1/studio/discord-status", watcher, null);
                Map<String, Object> st = obj(status.body());
                check(status.statusCode() == 200 && ((List<?>) st.get("fields")).size() == StudioDesigner.STATUS_FIELDS.size() && Boolean.FALSE.equals(st.get("canEdit")) && st.get("preview") instanceof Map<?, ?>, "status designer data: " + status.body());
                var preview = obj(Release1020Harness.call(client, base, "/v1/studio/discord-status", watcher, Map.of("operation", "preview", "state", "online", "values", Map.of("healthy-color", "#00F5D4", "online-title", "✦ {server} ✦", "thumbnail-url", "https://example.com/i.png", "show-server-load", false))).body());
                Map<String, Object> embed = (Map<String, Object>) ((List<?>) ((Map<String, Object>) preview.get("message")).get("embeds")).get(0);
                check(((Number) embed.get("color")).intValue() == 0x00F5D4 && "✦ Simplex SMP ✦".equals(embed.get("title")) && ((Map<?, ?>) embed.get("thumbnail")).get("url").equals("https://example.com/i.png"), "preview uses the real builder with overrides: " + embed);
                check(plugin.getConfig().getString("discord-bot.status.design.healthy-color", "#57F287").equals("#57F287"), "preview never saves");
                var crit = obj(Release1020Harness.call(client, base, "/v1/studio/discord-status", watcher, Map.of("operation", "preview", "state", "critical", "values", Map.of())).body());
                check(String.valueOf(((Map<String, Object>) ((List<?>) ((Map<String, Object>) crit.get("message")).get("embeds")).get(0)).get("title")).contains("heavy load") && String.valueOf(crit.get("activity")).contains("TPS"), "critical state preview");
                check(Release1020Harness.call(client, base, "/v1/studio/discord-status", watcher, Map.of("operation", "save", "values", Map.of("online-title", "x"))).statusCode() == 403, "saving needs discord-status.edit");
                check(Release1020Harness.call(client, base, "/v1/studio/discord-status", owner, Map.of("operation", "save", "values", Map.of("warning-tps", "15", "critical-tps", "16"))).statusCode() == 400, "critical must stay below warning");
                check(Release1020Harness.call(client, base, "/v1/studio/discord-status", owner, Map.of("operation", "save", "values", Map.of("healthy-color", "blau"))).statusCode() == 400, "invalid colour rejected");
                var ok = Release1020Harness.call(client, base, "/v1/studio/discord-status", owner, Map.of("operation", "save", "preset", "neon", "values", Map.of("healthy-color", "#00F5D4", "presence.healthy", "{players} online")));
                check(ok.statusCode() == 200 && plugin.getConfig().getString("discord-bot.status.design.healthy-color").equals("#00F5D4") && plugin.getConfig().getString("discord-bot.presence.healthy").equals("{players} online"), "status design saved: " + ok.body());
            }

            // ---- Bark test (no network call: missing and invalid keys are answered locally)
            check(features.contains("bark-test-v1"), "health advertises the Bark test");
            check(SuiteEventCenter.barkKey("https://api.day.app/AbCdEf123456/Titel/Text").equals("AbCdEf123456") && SuiteEventCenter.barkKey(" AbCdEf123456 ").equals("AbCdEf123456"), "Bark key from push address");
            var noKey = Release1020Harness.call(client, base, "/v1/bark/test", viewer, Map.of());
            check(noKey.statusCode() == 200 && Boolean.FALSE.equals(obj(noKey.body()).get("ok")) && noKey.body().contains("Kein Bark-Schlüssel"), "Bark test works for every account and explains a missing key: " + noKey.body());
            var badKey = Release1020Harness.call(client, base, "/v1/bark/test", viewer, Map.of("key", "x y"));
            check(badKey.statusCode() == 200 && badKey.body().contains("Ungültiger Bark-Schlüssel"), "Bark test validates a typed key");
            check(Release1020Harness.call(client, base, "/v1/bark/test", null, Map.of()).statusCode() == 401, "Bark test needs sign-in");

            // ---- dossier
            check(Release1020Harness.call(client, base, "/v1/player/dossier?player=" + steve, null, null).statusCode() == 401, "dossier needs sign-in");
            var dossier = Release1020Harness.call(client, base, "/v1/player/dossier?player=" + steve, viewer, null);
            if (dossier.statusCode() != 200) { System.out.println("DOSSIER " + dossier.statusCode() + " " + dossier.body()); try { plugin.appPlayerDossier(steve.toString(), x -> true); } catch (Throwable t) { t.printStackTrace(System.out); } }
            Map<String, Object> d = obj(dossier.body());
            List<String> sections = ((List<Map<String, Object>>) d.get("sections")).stream().map(s -> String.valueOf(s.get("id"))).toList();
            check(dossier.statusCode() == 200 && sections.containsAll(List.of("identity", "activity", "client", "bedrock", "moderation")) && !sections.contains("network") && !sections.contains("devices"), "dossier sections follow the viewer's rights: " + sections);
            check(dossier.body().contains("Timed out") && dossier.body().contains("Spam"), "dossier shows Bedrock disconnect reason and warnings");
            check(obj(Release1020Harness.call(client, base, "/v1/player/dossier?player=" + steve, owner, null).body()).get("sections").toString().contains("Netzwerk"), "full access sees the network section");
            check(Release1020Harness.call(client, base, "/v1/player/dossier?player=Unbekannt", owner, null).statusCode() == 400, "unknown player");
            for (String asset : List.of("/assets/studio.js")) check(client.send(HttpRequest.newBuilder(base.resolve(asset)).build(), HttpResponse.BodyHandlers.ofString()).statusCode() == 200, "asset served: " + asset);
            check(client.send(HttpRequest.newBuilder(base.resolve("/")).build(), HttpResponse.BodyHandlers.ofString()).body().contains("/assets/studio.js"), "index loads studio.js");
        } finally {
            if (api != null) try { Method stop = AppApiManager.class.getDeclaredMethod("stopListener"); stop.setAccessible(true); stop.invoke(api); } catch (Exception ignored) { }
            serverField.set(null, old);
        }
    }
}
