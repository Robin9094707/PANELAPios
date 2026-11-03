package eu.simplexsmp.adminsuite;

import java.lang.reflect.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javax.net.ssl.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.*;

/**
 * 10.0.0: access locks (flags, areas, temporary/permanent, persistence, expiry, listeners for using, breaking, supports
 * and hoppers), the HTTPS routes and confirmed action used by app and web panel, the Discord /rechtesperre schema,
 * LuckPerms player prefixes/suffixes and the permission list, the explained rights catalog with role presets, the
 * changelog shown once per account, and the new web-panel assets – with synthetic Bukkit services.
 */
public final class Release1000Harness {
    static int checks;
    static void check(boolean value, String reason) { checks++; if (!value) throw new AssertionError(reason); }
    static void fails(Callable<?> work, String reason) { try { work.call(); } catch (Exception | AssertionError e) { checks++; return; } throw new AssertionError("Expected failure: " + reason); }
    @SuppressWarnings("unchecked") static Map<String, Object> obj(String json) { return (Map<String, Object>) DiscordBotManager.Json.parse(json); }
    static String json(Object v) { return DiscordBotManager.Json.stringify(v); }
    static HttpClient client; static URI base;

    public static void main(String[] args) throws Exception {
        System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
        rules();
        luckPerms();
        discord();
        http();
        System.out.println("PASS " + checks + " 10.0.0/10.0.1 access-lock, unified rights, LuckPerms, rights catalog, changelog, Discord and web panel checks");
    }

    // ------------------------------------------------------------------ pure rules

    static void rules() {
        check(AccessLocks.duration("") == 0 && AccessLocks.duration("dauerhaft") == 0 && AccessLocks.duration("permanent") == 0, "empty/permanent = no expiry");
        check(AccessLocks.duration("30m") == 1_800_000L && AccessLocks.duration("2h") == 7_200_000L && AccessLocks.duration("7d") == 604_800_000L && AccessLocks.duration("1d12h") == 129_600_000L && AccessLocks.duration("2w") == 1_209_600_000L, "durations parsed");
        for (String bad : List.of("abc", "5x", "30s", "1d x", "-5m", "99999d")) fails(() -> AccessLocks.duration(bad), "invalid duration " + bad);
        check(AccessLocks.flagList("chest, shulker;doors").equals(List.of("chest", "shulker", "doors")) && AccessLocks.flagList("all").size() == AccessLocks.FLAGS.size(), "flag lists");
        fails(() -> AccessLocks.flagList("chest,nuke"), "unknown flag");
        check(AccessLocks.FLAGS.keySet().containsAll(List.of("chest", "shulker", "container", "enderchest", "doors", "redstone", "utility", "decor", "animals", "build", "pickup", "pickupall")), "all switches exist");
        check(AccessLocks.SCOPES.keySet().equals(new LinkedHashSet<>(List.of("everywhere", "own", "own-wild"))), "three areas");
        check(AccessLocks.PRESETS.get("thief").flags().get("chest").equals("own-wild") && AccessLocks.PRESETS.get("thief").flags().get("pickup").equals("own"), "thief: containers own land + wild, valuables only own land");
        check(AccessLocks.PRESETS.get("containers").flags().values().stream().allMatch("everywhere"::equals), "containers preset blocks everywhere");
        check(AccessLocks.remaining(90 * 60_000L, true).equals("1 Std. 30 Min.") && AccessLocks.remaining(3 * 86_400_000L + 3_600_000L, true).equals("3 T. 1 Std.") && AccessLocks.remaining(1, false).equals("1m"), "remaining time text");
        String summary = AccessLocks.summary(Map.of("operation", "set", "flags", "chest,shulker", "scope", "own", "duration", "7d", "reason", "Diebstahl"), "Steve");
        check(summary.contains("Truhen") && summary.contains("Shulkerkisten") && summary.contains("Nur im eigenen Land erlaubt") && summary.contains("7 T.") && summary.contains("Diebstahl"), "confirmation summary explains the change");
        fails(() -> AccessLocks.summary(Map.of("operation", "set", "flags", ""), "Steve"), "set without flags");
        fails(() -> AccessLocks.summary(Map.of("operation", "set", "flags", "chest", "scope", "moon"), "Steve"), "unknown scope");
        fails(() -> AccessLocks.summary(Map.of("operation", "preset", "preset", "evil"), "Steve"), "unknown preset");
        fails(() -> AccessLocks.summary(Map.of("operation", "drop"), "Steve"), "unknown operation");
        // 10.0.1: one rights model – every classic right is either replaced by an area or listed with a duration.
        Set<String> all = new HashSet<>(SimplexAdminSuite.rightIds()), sorted = new HashSet<>(AccessLocks.COVERED.keySet());
        sorted.addAll(AccessLocks.RIGHT_GROUPS.keySet());
        check(all.equals(sorted) && Collections.disjoint(AccessLocks.COVERED.keySet(), AccessLocks.RIGHT_GROUPS.keySet()), "every classic right appears exactly once (area or own row)");
        check(AccessLocks.COVERED.values().stream().allMatch(AccessLocks.FLAGS::containsKey), "covered rights map to real areas");
        check(AccessLocks.COVERED.get("chest").equals("chest") && AccessLocks.COVERED.get("shulkers").equals("shulker") && AccessLocks.COVERED.get("doors").equals("doors") && !AccessLocks.COVERED.containsKey("pvp") && !AccessLocks.COVERED.containsKey("nether"), "chests/doors are areas, PvP/Nether stay rights");
        String rightPlan = AccessLocks.summary(Map.of("operation", "right-block", "right", "pvp", "duration", "2h"), "Steve");
        check(rightPlan.contains("PvP") && rightPlan.contains("2 Std."), "classic right summary with duration");
        check(AccessLocks.summary(Map.of("operation", "right-allow", "right", "nether"), "Steve").contains("Nether betreten"), "allow summary");
        fails(() -> AccessLocks.summary(Map.of("operation", "right-block", "right", "fly-hack"), "Steve"), "unknown classic right");
    }

    static void luckPerms() {
        check(LuckPermsAdmin.command(Map.of("operation", "user-prefix", "player", "Steve", "value", "&c&l[Dieb] &r", "priority", "1000")).equals("lp user Steve meta setprefix 1000 \"&c&l[Dieb] &r\""), "player prefix keeps colours and spaces");
        check(LuckPermsAdmin.command(Map.of("operation", "user-suffix", "player", "Steve", "value", "", "priority", "1000")).equals("lp user Steve meta removesuffix 1000"), "empty player suffix removes it");
        check(LuckPermsAdmin.command(Map.of("operation", "group-suffix", "group", "vip", "value", " &6✦", "priority", "50")).equals("lp group vip meta setsuffix 50 \" &6✦\""), "group suffix");
        check(LuckPermsAdmin.command(Map.of("operation", "user-prefix", "player", "Steve", "value", "&#55FFFF[Hex] ")).contains("&#55FFFF[Hex] "), "hex colours allowed");
        for (String evil : List.of("x\" ; op Steve", "a\\b", "line\nbreak")) fails(() -> LuckPermsAdmin.command(Map.of("operation", "user-prefix", "player", "Steve", "value", evil)), "prefix injection " + evil.replace("\n", "\\n"));
        fails(() -> LuckPermsAdmin.command(Map.of("operation", "user-prefix", "player", "Steve; op x", "value", "&a")), "player injection");
    }

    @SuppressWarnings("unchecked")
    static void discord() {
        Map<String, Object> command = DiscordBotManager.accessLockCommand();
        DiscordCommandSchema.validate(new ArrayList<>(List.of(command)));
        check("rechtesperre".equals(command.get("name")) && "0".equals(command.get("default_member_permissions")), "Discord /rechtesperre is admin-only by default");
        List<String> subs = ((List<Map<String, Object>>) command.get("options")).stream().map(o -> String.valueOf(o.get("name"))).toList();
        check(subs.equals(List.of("anzeigen", "liste", "sperren", "vorlage", "aufheben")), "Discord subcommands");
        check(DiscordCommandSchema.size(command) < DiscordCommandSchema.LIMIT, "within Discord's character budget");
    }

    // ------------------------------------------------------------------ fixture

    static Object empty(Class<?> t) { return AppApiIntegrationHarness.empty(t); }
    static <T> T proxy(Class<T> type, InvocationHandler h) { return AppApiIntegrationHarness.proxy(type, h); }
    static HttpResponse<String> send(HttpRequest.Builder b) throws Exception { return client.send(b.timeout(java.time.Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString()); }
    static HttpRequest.Builder req(String path) { return HttpRequest.newBuilder(base.resolve(path)); }
    static HttpResponse<String> app(String path, String token, Object body) throws Exception {
        var b = req(path).header("X-Simplex-Device", "model=iPhone18%2C1;os=iOS%2026.1;app=4.3.0");
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (body != null) b.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json(body)));
        return send(b);
    }
    static Map<String, Object> run(String token, String path, Map<String, Object> values) throws Exception {
        var plan = app("/v1/prepare", token, Map.of("path", path, "values", values));
        check(plan.statusCode() == 200, "prepare " + path + ": " + plan.body());
        var result = app("/v1/execute", token, Map.of("ticket", obj(plan.body()).get("ticket")));
        check(result.statusCode() == 200, "execute " + path + ": " + result.body());
        return obj(result.body());
    }

    /** A block in a synthetic world: type is mutable, neighbours are created on demand. */
    static final class FakeBlock {
        final World world; final int x, y, z; Material type = Material.AIR; final Map<String, FakeBlock> grid;
        FakeBlock(World world, Map<String, FakeBlock> grid, int x, int y, int z) { this.world = world; this.grid = grid; this.x = x; this.y = y; this.z = z; }
        Block block() {
            return proxy(Block.class, (p, m, a) -> switch (m.getName()) {
                case "getType" -> type;
                case "getLocation" -> new Location(world, x, y, z);
                case "getWorld" -> world;
                case "getX" -> x; case "getY" -> y; case "getZ" -> z;
                case "getRelative" -> { BlockFace f = (BlockFace) a[0]; yield at(world, grid, x + f.getModX(), y + f.getModY(), z + f.getModZ()).block(); }
                case "getBlockData" -> null;
                case "equals" -> a[0] instanceof Block other && other.getX() == x && other.getY() == y && other.getZ() == z;
                case "hashCode" -> Objects.hash(x, y, z);
                default -> empty(m.getReturnType());
            });
        }
        static FakeBlock at(World w, Map<String, FakeBlock> grid, int x, int y, int z) { return grid.computeIfAbsent(x + ":" + y + ":" + z, k -> new FakeBlock(w, grid, x, y, z)); }
    }

    @SuppressWarnings("unchecked")
    static void http() throws Exception {
        Path dir = Files.createTempDirectory("suite1000-"); Field serverField = Bukkit.class.getDeclaredField("server"); serverField.setAccessible(true); Object old = serverField.get(null); AppApiManager api = null;
        List<String> messages = new CopyOnWriteArrayList<>();
        Map<UUID, Player> online = new ConcurrentHashMap<>();
        try {
            BukkitTask task = proxy(BukkitTask.class, (p, m, a) -> empty(m.getReturnType()));
            BukkitScheduler scheduler = proxy(BukkitScheduler.class, (p, m, a) -> { if (a != null) for (Object item : a) { if (item instanceof Runnable work) work.run(); else if (item instanceof Consumer<?> consumer) ((Consumer<BukkitTask>) consumer).accept(task); } return m.getReturnType() == BukkitTask.class ? task : empty(m.getReturnType()); });
            var console = proxy(org.bukkit.command.ConsoleCommandSender.class, (p, m, a) -> switch (m.getName()) { case "getName" -> "Console"; case "hasPermission", "isOp", "isPermissionSet" -> true; default -> empty(m.getReturnType()); });
            org.bukkit.plugin.PluginManager plugins = proxy(org.bukkit.plugin.PluginManager.class, (p, m, a) -> switch (m.getName()) { case "getPlugins" -> new org.bukkit.plugin.Plugin[0]; case "getPermissions" -> Set.of(new org.bukkit.permissions.Permission("essentials.fly", "Fliegen erlauben"), new org.bukkit.permissions.Permission("lands.bypass.*", "Lands überall")); default -> empty(m.getReturnType()); });
            World world = proxy(World.class, (p, m, a) -> switch (m.getName()) { case "getName" -> "world"; case "getUID" -> new UUID(1, 1); default -> empty(m.getReturnType()); });
            Server server = proxy(Server.class, (p, m, a) -> switch (m.getName()) { case "getName", "getVersion", "getBukkitVersion", "getMinecraftVersion" -> "API Fixture"; case "getWorlds" -> List.of(world); case "getPluginManager" -> plugins; case "getScheduler" -> scheduler; case "getConsoleSender" -> console; case "getLogger" -> java.util.logging.Logger.getLogger("Harness1000"); case "isPrimaryThread" -> true; case "getOnlinePlayers" -> List.of(); case "getOperators" -> Set.of(); case "getPlayer" -> a[0] instanceof UUID u ? online.get(u) : null; default -> empty(m.getReturnType()); });
            serverField.set(null, server);
            SimplexAdminSuite plugin = Moderation790Harness.plugin(dir); Moderation790Harness.set(plugin, JavaPlugin.class, "isEnabled", true);
            Moderation790Harness.set(plugin, SimplexAdminSuite.class, "adminActor", new ThreadLocal<String>()); Moderation790Harness.set(plugin, SimplexAdminSuite.class, "pendingInputs", new ConcurrentHashMap<>()); Moderation790Harness.set(plugin, SimplexAdminSuite.class, "pendingActions", new ConcurrentHashMap<>());
            Moderation790Harness.set(plugin, SimplexAdminSuite.class, "auditedConfig", new HashMap<String, String>()); plugin.moderationControl = new ModerationControl(plugin);
            plugin.adminAccess = new AdminAccessManager(plugin); plugin.adminUpdate = new AdminUpdateMenu(plugin); plugin.adminLog = new AdminActivityLog(plugin);
            Moderation790Harness.set(plugin, JavaPlugin.class, "description", new org.bukkit.plugin.PluginDescriptionFile("SimplexAdminSuite", "10.0.0", SimplexAdminSuite.class.getName()));
            plugin.accessLocks = new AccessLocks(plugin);
            AccessLocks locks = plugin.accessLocks;
            check(AccessLocks.classify(Material.CHEST).equals("chest") && AccessLocks.classify(Material.TRAPPED_CHEST).equals("chest") && AccessLocks.classify(Material.ENDER_CHEST).equals("enderchest"), "chests classified");
            check(AccessLocks.classify(Material.SHULKER_BOX).equals("shulker") && AccessLocks.classify(Material.RED_SHULKER_BOX).equals("shulker"), "shulkers classified");
            check(AccessLocks.classify(Material.BARREL).equals("container") && AccessLocks.classify(Material.HOPPER).equals("container") && AccessLocks.classify(Material.FURNACE).equals("container"), "other containers classified");
            check(AccessLocks.classify(Material.OAK_DOOR).equals("doors") && AccessLocks.classify(Material.IRON_TRAPDOOR).equals("doors") && AccessLocks.classify(Material.OAK_FENCE_GATE).equals("doors"), "doors classified");
            check(AccessLocks.classify(Material.LEVER).equals("redstone") && AccessLocks.classify(Material.STONE_BUTTON).equals("redstone") && AccessLocks.classify(Material.STONE_PRESSURE_PLATE).equals("redstone"), "switches classified");
            check(AccessLocks.classify(Material.ANVIL).equals("utility") && AccessLocks.classify(Material.RED_BED).equals("utility") && AccessLocks.classify(Material.STONE) == null && AccessLocks.classify(Material.DIRT) == null, "utility / plain blocks");


            // ---------------- listeners against synthetic blocks and players
            UUID thief = UUID.randomUUID(), friend = UUID.randomUUID();
            Player thiefPlayer = proxy(Player.class, (p, m, a) -> switch (m.getName()) { case "getUniqueId" -> thief; case "getName" -> "Klauer"; case "getLocale" -> "de_de"; case "sendMessage" -> { if (a != null && a[0] instanceof String s) messages.add(s); yield null; } case "hashCode" -> thief.hashCode(); case "equals" -> p == a[0]; default -> empty(m.getReturnType()); });
            Player friendPlayer = proxy(Player.class, (p, m, a) -> switch (m.getName()) { case "getUniqueId" -> friend; case "getName" -> "Freund"; case "hashCode" -> friend.hashCode(); case "equals" -> p == a[0]; default -> empty(m.getReturnType()); });
            online.put(thief, thiefPlayer);
            Map<String, FakeBlock> grid = new HashMap<>();
            FakeBlock chest = FakeBlock.at(world, grid, 0, 64, 0); chest.type = Material.CHEST;
            FakeBlock door = FakeBlock.at(world, grid, 4, 65, 0); door.type = Material.OAK_DOOR;
            FakeBlock below = FakeBlock.at(world, grid, 4, 64, 0); below.type = Material.STONE;
            FakeBlock stone = FakeBlock.at(world, grid, 8, 64, 0); stone.type = Material.STONE;

            BlockBreakEvent breakChest = new BlockBreakEvent(chest.block(), thiefPlayer); locks.breakBlock(breakChest);
            check(!breakChest.isCancelled(), "no lock: breaking allowed");
            locks.apply(Map.of("operation", "set", "player", thief.toString(), "flags", "chest,doors", "scope", "everywhere", "duration", "2h", "reason", "Truhen geplündert"), "Tester");
            check(locks.locked(thief) && !locks.locked(friend), "lock applies only to the target");
            check(messages.stream().anyMatch(s -> s.contains("Rechte-Sperren") && s.contains("Truhen geplündert")), "player is told about the new lock");
            breakChest = new BlockBreakEvent(chest.block(), thiefPlayer); locks.breakBlock(breakChest);
            check(breakChest.isCancelled(), "locked: chest cannot be broken");
            BlockBreakEvent friendBreak = new BlockBreakEvent(chest.block(), friendPlayer); locks.breakBlock(friendBreak);
            check(!friendBreak.isCancelled(), "other players unaffected");
            check(messages.stream().anyMatch(s -> s.contains("Rechte-Sperre") && s.contains("Truhen") && s.contains("noch")), "denied attempt explains lock and remaining time");
            BlockBreakEvent breakDoor = new BlockBreakEvent(door.block(), thiefPlayer); locks.breakBlock(breakDoor);
            check(breakDoor.isCancelled(), "locked: door cannot be broken");
            BlockBreakEvent breakStone = new BlockBreakEvent(stone.block(), thiefPlayer); locks.breakBlock(breakStone);
            check(!breakStone.isCancelled(), "ordinary blocks stay breakable without the build lock");

            // Hopper below a locked chest.
            FakeBlock hopper = FakeBlock.at(world, grid, 0, 63, 0); hopper.type = Material.HOPPER;
            plugin.getConfig().set("access-locks.block-hoppers", true);
            BlockPlaceEvent placeHopper = new BlockPlaceEvent(hopper.block(), proxy(org.bukkit.block.BlockState.class, (p, m, a) -> empty(m.getReturnType())), FakeBlock.at(world, grid, 0, 62, 0).block(), null, thiefPlayer, true, org.bukkit.inventory.EquipmentSlot.HAND);
            locks.place(placeHopper);
            check(placeHopper.isCancelled(), "hopper under a locked chest refused (no draining)");
            FakeBlock hopper2 = FakeBlock.at(world, grid, 8, 63, 0); hopper2.type = Material.HOPPER;
            BlockPlaceEvent okHopper = new BlockPlaceEvent(hopper2.block(), proxy(org.bukkit.block.BlockState.class, (p, m, a) -> empty(m.getReturnType())), FakeBlock.at(world, grid, 8, 62, 0).block(), null, thiefPlayer, true, org.bukkit.inventory.EquipmentSlot.HAND);
            locks.place(okHopper);
            check(!okHopper.isCancelled(), "hopper elsewhere allowed");

            // Areas: without Lands everything is wilderness.
            Location there = new Location(world, 0, 64, 0);
            locks.apply(Map.of("operation", "set", "player", thief.toString(), "flags", "shulker", "scope", "own-wild"), "Tester");
            locks.apply(Map.of("operation", "set", "player", thief.toString(), "flags", "pickup", "scope", "own"), "Tester");
            check(!locks.blocked(thief, "shulker", there), "own land + wilderness allowed: wilderness is free");
            check(locks.blocked(thief, "pickup", there), "own land only: wilderness is locked");
            check(locks.blocked(thief, "chest", there), "everywhere: locked");
            check(locks.standing(thief, there) == AccessLocks.Standing.WILD, "no Lands = wilderness");
            check(locks.entry(thief, "shulker").until() == 0 && locks.entry(thief, "chest").until() > System.currentTimeMillis(), "permanent and temporary entries");

            // Valuables.
            check(locks.valuableType(Material.DIAMOND) && locks.valuableType(Material.NETHERITE_INGOT) && locks.valuableType(Material.DEEPSLATE_DIAMOND_ORE) && locks.valuableType(Material.SHULKER_BOX) && locks.valuableType(Material.ELYTRA) && !locks.valuableType(Material.COBBLESTONE) && !locks.valuableType(Material.DIRT), "valuable items");

            // Persistence and expiry.
            AccessLocks reloaded = new AccessLocks(plugin);
            check(reloaded.locked(thief) && reloaded.entry(thief, "chest") != null && reloaded.entry(thief, "chest").reason().equals("Truhen geplündert") && reloaded.entry(thief, "shulker").scope().equals("own-wild"), "locks survive a restart");
            Field field = AccessLocks.class.getDeclaredField("locks"); field.setAccessible(true);
            Map<UUID, Map<String, AccessLocks.Entry>> raw = (Map<UUID, Map<String, AccessLocks.Entry>>) field.get(locks);
            raw.get(thief).put("doors", new AccessLocks.Entry("everywhere", System.currentTimeMillis() - 1000, "", "Tester", 0));
            check(locks.entry(thief, "doors") == null, "expired lock no longer applies");
            locks.tick();
            check(!raw.get(thief).containsKey("doors") && new AccessLocks(plugin).entry(thief, "doors") == null, "expired lock removed and saved");
            check(messages.stream().anyMatch(s -> s.contains("abgelaufen")), "player is told when a lock expires");
            check(Files.readString(dir.resolve("access-locks.properties")).contains(thief.toString()), "stored in access-locks.properties");

            // 10.0.2 alt inheritance: an alt without own locks is locked through the effective (inherited) view.
            UUID alt = UUID.randomUUID();
            Field inheritedField = AccessLocks.class.getDeclaredField("inherited"); inheritedField.setAccessible(true);
            Field fromField = AccessLocks.class.getDeclaredField("inheritedFrom"); fromField.setAccessible(true);
            inheritedField.set(locks, Map.of(alt, Map.of("chest", new AccessLocks.Entry("everywhere", 0, "Vererbt von Klauer", "Alt-Vererbung (Klauer)", 0))));
            fromField.set(locks, Map.of(alt, Map.of("chest", thief)));
            check(locks.locked(alt) && locks.blocked(alt, "chest", there) && locks.entry(alt, "chest").reason().startsWith("Vererbt"), "alt is locked through inheritance");
            check(locks.ownEntry(alt, "chest") == null, "nothing copied to the alt");
            var altView = locks.player(alt);
            check(((List<Map<String, Object>>) altView.get("inheritedLocks")).stream().anyMatch(l -> "chest".equals(l.get("flag")) && thief.toString().equals(l.get("from"))) && ((List<?>) altView.get("locks")).isEmpty(), "inherited locks listed with their source, separate from own locks");
            check(locks.describe(alt).contains("vererbt von"), "description names the inheritance");
            locks.refreshInheritance();
            check(!locks.locked(alt), "without confirmed alt links the inheritance disappears again (recomputed, never stored)");

            // ---------------- HTTPS
            int port; try (var socket = new java.net.ServerSocket(0)) { port = socket.getLocalPort(); }
            plugin.getConfig().set("app-api.enabled", true); plugin.getConfig().set("app-api.bind", "127.0.0.1"); plugin.getConfig().set("app-api.port", port); plugin.getConfig().set("app-api.transport", "tls");
            api = new AppApiManager(plugin); plugin.appApi = api; api.reload();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30); while (!api.active() && System.nanoTime() < deadline) Thread.sleep(50); check(api.active(), "TLS listener started");
            SSLContext ssl = SSLContext.getInstance("TLS"); ssl.init(null, new TrustManager[]{new X509TrustManager() { public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; } public void checkClientTrusted(X509Certificate[] c, String a) { } public void checkServerTrusted(X509Certificate[] c, String a) { } }}, new java.security.SecureRandom());
            client = HttpClient.newBuilder().sslContext(ssl).connectTimeout(java.time.Duration.ofSeconds(5)).build(); base = URI.create("https://127.0.0.1:" + port);

            for (String asset : List.of("/assets/rights.js", "/assets/extras.js", "/assets/app.css")) check(send(req(asset)).statusCode() == 200, "asset served: " + asset);
            check(send(req("/")).body().contains("/assets/rights.js"), "index loads rights.js");
            check(send(req("/sw.js")).body().contains("/assets/rights.js"), "service worker caches rights.js");

            api.security.users.createOwner("juhas", "owner-password-1000");
            api.security.users.save("helper", "helper-password-1000", Set.of("data.tickets"), true);
            api.security.users.save("mod", "mod-password-1000", Set.of("data.access-locks", "action.locks.update"), true);
            String owner = String.valueOf(obj(app("/v1/login", null, Map.of("username", "juhas", "password", "owner-password-1000")).body()).get("token"));
            String helper = String.valueOf(obj(app("/v1/login", null, Map.of("username", "helper", "password", "helper-password-1000")).body()).get("token"));
            String mod = String.valueOf(obj(app("/v1/login", null, Map.of("username", "mod", "password", "mod-password-1000")).body()).get("token"));
            var features = (List<?>) obj(app("/v1/health", owner, null).body()).get("features");
            check(features.containsAll(List.of("access-locks-v1", "luckperms-v2", "changelog-v1", "bans-sources-v2", "permission-catalog-v2", "luckperms-v1", "lands-v1")), "health advertises 10.0.0 features (and keeps 9.9.0 ones)");

            // Access locks over HTTPS.
            var overview = obj(app("/v1/data/access-locks", owner, null).body());
            check(((List<?>) overview.get("flags")).size() == AccessLocks.FLAGS.size() && ((List<?>) overview.get("scopes")).size() == 3 && ((List<?>) overview.get("presets")).size() == 4, "catalog for app and web panel");
            check(((List<Map<String, Object>>) overview.get("items")).stream().anyMatch(r -> thief.toString().equals(r.get("uuid"))), "locked players listed");
            check(app("/v1/data/access-locks", helper, null).statusCode() == 403, "reading needs data.access-locks");
            var denied = app("/v1/prepare", helper, Map.of("path", "locks.update", "values", Map.of("operation", "clear", "player", thief.toString())));
            check(denied.statusCode() == 403, "changing needs action.locks.update: " + denied.statusCode() + " " + denied.body());
            var plan = obj(app("/v1/prepare", mod, Map.of("path", "locks.update", "values", Map.of("operation", "preset", "preset", "thief", "player", friend.toString(), "duration", "3d"))).body());
            check(String.valueOf(plan.get("summary")).contains("Klauer") && String.valueOf(plan.get("summary")).contains("3 T."), "preset confirmation");
            check(app("/v1/prepare", mod, Map.of("path", "locks.update", "values", Map.of("operation", "set", "player", friend.toString(), "flags", "chest", "duration", "forever"))).statusCode() == 400, "invalid duration refused before confirmation");
            var result = obj(app("/v1/execute", mod, Map.of("ticket", plan.get("ticket"))).body());
            check(Boolean.TRUE.equals(result.get("ok")) && locks.entry(friend, "chest").scope().equals("own-wild") && locks.entry(friend, "pickup").scope().equals("own"), "preset applied via app/web");
            var detail = obj(app("/v1/data/access-locks?player=" + friend, mod, null).body());
            check(((List<?>) ((Map<String, Object>) detail.get("player")).get("locks")).size() == AccessLocks.PRESETS.get("thief").flags().size(), "player detail lists every active lock");
            run(mod, "locks.update", Map.of("operation", "remove", "player", friend.toString(), "flags", "pickup"));
            check(locks.entry(friend, "pickup") == null && locks.entry(friend, "chest") != null, "single lock lifted");
            run(owner, "locks.update", Map.of("operation", "clear", "player", friend.toString()));
            check(!locks.locked(friend), "all locks lifted");

            // 10.0.1 unified view: classic rights live in the same response and action.
            check(features.contains("player-rights-v1"), "unified rights advertised");
            var unified = (Map<String, Object>) obj(app("/v1/data/access-locks?player=" + friend, owner, null).body()).get("player");
            check(unified.containsKey("rights") && unified.containsKey("legacy") && Boolean.FALSE.equals(unified.get("profile")), "player view carries classic rights (none without a profile)");
            var covered = app("/v1/prepare", owner, Map.of("path", "locks.update", "values", Map.of("operation", "right-block", "player", friend.toString(), "right", "chest", "duration", "1d")));
            var coveredRun = app("/v1/execute", owner, Map.of("ticket", obj(covered.body()).get("ticket")));
            check(coveredRun.statusCode() == 400 && coveredRun.body().contains("Truhen"), "chest right is managed by the chest area only (no double management)");
            var noProfile = app("/v1/execute", owner, Map.of("ticket", obj(app("/v1/prepare", owner, Map.of("path", "locks.update", "values", Map.of("operation", "right-block", "player", friend.toString(), "right", "pvp", "duration", "1d"))).body()).get("ticket")));
            check(noProfile.statusCode() == 400 && noProfile.body().contains("Profil"), "classic rights need a stored profile, explained clearly");

            // Rights catalog with explanations and role presets.
            var access = obj(app("/v1/data/access", owner, null).body());
            var catalog = (List<Map<String, Object>>) access.get("catalog");
            check(catalog.stream().allMatch(c -> c.get("group") != null && c.get("explain") != null && c.get("level") != null), "every right has a group, explanation and level");
            check(catalog.stream().anyMatch(c -> "data.bans".equals(c.get("id")) && "Moderation".equals(c.get("group")) && String.valueOf(c.get("title")).startsWith("Banns")), "plain-language titles");
            check(catalog.stream().anyMatch(c -> "action.locks.update".equals(c.get("id"))) && catalog.stream().anyMatch(c -> "data.access-locks".equals(c.get("id"))), "new rights in the catalog");
            check(catalog.stream().anyMatch(c -> "action.accounts.update".equals(c.get("id")) && "critical".equals(c.get("level"))), "full-scope actions marked critical");
            var presets = (List<Map<String, Object>>) access.get("presets");
            Set<String> known = new HashSet<>(catalog.stream().map(c -> String.valueOf(c.get("id"))).toList());
            check(presets.size() == 4 && presets.stream().allMatch(p -> ((List<?>) p.get("permissions")).stream().allMatch(x -> known.contains(String.valueOf(x)))), "role presets only use known rights");
            check(presets.stream().anyMatch(p -> "moderator".equals(p.get("id")) && ((List<?>) p.get("permissions")).contains("action.locks.update") && !((List<?>) p.get("permissions")).contains("*")), "moderator preset includes access locks, no full access");
            check(app("/v1/data/access", helper, null).statusCode() == 403, "rights catalog still needs full access");

            // LuckPerms permission list and player prefixes.
            var perms = obj(app("/v1/data/luckperms-permissions", owner, null).body());
            check(((List<Map<String, Object>>) perms.get("items")).stream().anyMatch(p -> "essentials.fly".equals(p.get("node")) && "Fliegen erlauben".equals(p.get("description"))), "known permissions with description for autocomplete");
            check(app("/v1/data/luckperms-permissions", helper, null).statusCode() == 403, "permission list needs data.luckperms");
            var prefix = obj(app("/v1/prepare", owner, Map.of("path", "luckperms.edit", "values", Map.of("operation", "user-prefix", "player", "Steve", "value", "&c[Dieb] ", "priority", "1000"))).body());
            check(String.valueOf(prefix.get("summary")).contains("lp user Steve meta setprefix 1000 \"&c[Dieb] \""), "player prefix confirmed with the exact LuckPerms command");

            // Changelog: once per account and version.
            var cl = obj(app("/v1/changelog", owner, null).body());
            check(Boolean.TRUE.equals(cl.get("unseen")) && "10.0.0".equals(cl.get("version")) && !((List<?>) cl.get("entries")).isEmpty(), "changelog unseen after the update");
            check(app("/v1/changelog/seen", owner, Map.of("version", "10.0.0")).statusCode() == 200 && Boolean.FALSE.equals(obj(app("/v1/changelog", owner, null).body()).get("unseen")), "marked as seen");
            String again = String.valueOf(obj(app("/v1/login", null, Map.of("username", "juhas", "password", "owner-password-1000")).body()).get("token"));
            check(Boolean.FALSE.equals(obj(app("/v1/changelog", again, null).body()).get("unseen")), "stays seen after a new login (stored on the server)");
            check(Boolean.TRUE.equals(obj(app("/v1/changelog", helper, null).body()).get("unseen")), "per account");
            check(app("/v1/changelog/seen", owner, Map.of("version", "<script>")).statusCode() == 400, "version validated");
            check(Files.readString(api.security.directory.resolve("web-prefs.properties")).contains("changelog."), "seen marker stored next to the appearance settings");
        } finally {
            if (api != null) api.stop();
            serverField.set(null, old);
        }
    }
}
