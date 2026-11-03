package eu.simplexsmp.adminsuite;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.*;

/**
 * 10.1.0: command rights (validation, plans, kinds, per-player block/allow/reset/clear, global blocks with
 * per-player exceptions, persistence across restarts, expiry, enforcement on execution and in the tab list) and
 * LuckPerms group templates – with synthetic Bukkit services and a real command map.
 */
public final class Release1010Harness {
    static int checks;
    static void check(boolean value, String reason) { checks++; if (!value) throw new AssertionError(reason); }
    static void fails(Callable<?> work, String reason) { try { work.call(); } catch (Exception | AssertionError e) { checks++; return; } throw new AssertionError("Expected failure: " + reason); }

    public static void main(String[] args) throws Exception {
        rules();
        templates();
        state();
        System.out.println("PASS " + checks + " 10.1.0 command rights, live switches and LuckPerms template checks");
    }

    static void rules() {
        check(CommandRights.commandList("/tpa, home;EssentialS:spawn").equals(List.of("tpa", "home", "spawn")), "names normalised: slash, case, namespace");
        check(CommandRights.commandList("tpa tpa").size() == 1, "duplicates removed");
        fails(() -> CommandRights.commandList(""), "empty list");
        for (String bad : List.of("x\"y", "op&x", "a/b", "ä", "say$(id)")) fails(() -> CommandRights.commandList(bad), "invalid command " + bad);
        String plan = CommandRights.summary(Map.of("operation", "block", "commands", "tpa,home", "duration", "2h", "reason", "Spam", "alts", "true"), "Steve");
        check(plan.contains("/tpa") && plan.contains("/home") && plan.contains("2 Std.") && plan.contains("erben") && plan.contains("Spam") && plan.contains("Steve"), "block plan explains commands, duration, alts and reason");
        check(CommandRights.summary(Map.of("operation", "block", "commands", "tpa", "alts", "false"), "Steve").contains("nicht betroffen"), "alts can be excluded");
        check(CommandRights.summary(Map.of("operation", "allow", "commands", "fly", "grant", "true"), "Steve").contains("LuckPerms"), "allow plan mentions LuckPerms grant");
        check(CommandRights.summary(Map.of("operation", "global-block", "commands", "pay"), "").contains("alle Spieler") && CommandRights.summary(Map.of("operation", "global-block", "commands", "pay"), "").contains("bypass"), "global plan names everyone and the bypass");
        fails(() -> CommandRights.summary(Map.of("operation", "nuke", "commands", "tpa"), "Steve"), "unknown operation");
        fails(() -> CommandRights.summary(Map.of("operation", "block", "commands", "tpa", "duration", "5x"), "Steve"), "invalid duration");
        StringBuilder many = new StringBuilder(); for (int i = 0; i < 401; i++) many.append("c").append(i).append(',');
        fails(() -> CommandRights.commandList(many.toString()), "at most 400 commands at once");
        check(CommandRights.kindOf("tpa", "Essentials").equals("Teleport") && CommandRights.kindOf("ban", "LiteBans").equals("Moderation") && CommandRights.kindOf("lp", "LuckPerms").equals("Ränge & Rechte"), "known kinds");
        check(CommandRights.kindOf("claimstuff", "Lands").equals("Grundstücke") && CommandRights.kindOf("whatever", "Minecraft").equals("Welt & Server") && CommandRights.kindOf("x", "Foo").equals("Weitere"), "kinds from the plugin");
        check(CommandRights.KIND_ORDER.get(CommandRights.KIND_ORDER.size() - 1).equals("Weitere"), "'Weitere' sorts last");
    }

    static void templates() {
        check(LuckPermsAdmin.TEMPLATES.keySet().equals(new LinkedHashSet<>(List.of("spieler", "vip", "builder", "supporter", "moderator", "admin"))), "six templates");
        List<String> cmds = LuckPermsAdmin.commands(Map.of("operation", "group-template", "template", "moderator", "group", "Mod"));
        check(cmds.get(0).equals("lp creategroup mod") && cmds.get(1).equals("lp group mod setweight 70") && cmds.get(2).equals("lp group mod meta setprefix 70 \"&9[Mod] &r\"") && cmds.get(3).equals("lp group mod parent add default"), "template creates, weighs, prefixes and inherits default");
        check(cmds.stream().skip(4).allMatch(c -> c.startsWith("lp group mod permission set ") && c.endsWith(" true")) && cmds.size() == 4 + LuckPermsAdmin.TEMPLATES.get("moderator").permissions().size(), "one command per permission");
        check(LuckPermsAdmin.commands(Map.of("operation", "group-template", "template", "spieler", "group", "default")).stream().noneMatch(c -> c.contains("parent add")), "default does not inherit itself");
        check(LuckPermsAdmin.commands(Map.of("operation", "group-template", "template", "vip")).get(0).equals("lp creategroup vip"), "group name defaults to the template id");
        fails(() -> LuckPermsAdmin.commands(Map.of("operation", "group-template", "template", "god")), "unknown template");
        fails(() -> LuckPermsAdmin.commands(Map.of("operation", "group-template", "template", "vip", "group", "vip; op x")), "group name injection");
        check(LuckPermsAdmin.commands(Map.of("operation", "group-weight", "group", "vip", "value", "5")).equals(List.of("lp group vip setweight 5")), "single operations stay single");
        check(LuckPermsAdmin.summary(Map.of("operation", "group-template", "template", "admin", "group", "admin")).contains("Vorlage „Admin“"), "template plan");
        check(LuckPermsAdmin.templates().size() == 6 && LuckPermsAdmin.templates().get(0).containsKey("permissions"), "templates exported for app and web");
    }

    static final class TestCommand extends Command {
        TestCommand(String name, String permission, String... aliases) { super(name, "Test " + name, "/" + name, List.of(aliases)); setPermission(permission); }
        @Override public boolean execute(CommandSender sender, String label, String[] args) { return true; }
    }

    @SuppressWarnings("unchecked")
    static void state() throws Exception {
        Path dir = Files.createTempDirectory("suite1010-");
        Field serverField = Bukkit.class.getDeclaredField("server"); serverField.setAccessible(true); Object old = serverField.get(null);
        Map<UUID, Player> online = new ConcurrentHashMap<>();
        List<String> messages = new CopyOnWriteArrayList<>();
        try {
            BukkitTask task = AppApiIntegrationHarness.proxy(BukkitTask.class, (p, m, a) -> AppApiIntegrationHarness.empty(m.getReturnType()));
            BukkitScheduler scheduler = AppApiIntegrationHarness.proxy(BukkitScheduler.class, (p, m, a) -> { if (a != null) for (Object item : a) { if (item instanceof Runnable work) work.run(); else if (item instanceof Consumer<?> c) ((Consumer<BukkitTask>) c).accept(task); } return m.getReturnType() == BukkitTask.class ? task : AppApiIntegrationHarness.empty(m.getReturnType()); });
            org.bukkit.plugin.PluginManager plugins = AppApiIntegrationHarness.proxy(org.bukkit.plugin.PluginManager.class, (p, m, a) -> switch (m.getName()) { case "getPlugins" -> new org.bukkit.plugin.Plugin[0]; case "getPermissions" -> Set.of(); default -> AppApiIntegrationHarness.empty(m.getReturnType()); });
            Server[] holder = new Server[1];
            Map<String, Command> known = new LinkedHashMap<>();
            org.bukkit.command.CommandMap commandMap = AppApiIntegrationHarness.proxy(org.bukkit.command.CommandMap.class, (p, m, a) -> switch (m.getName()) {
                case "getKnownCommands" -> known;
                case "getCommand" -> known.get(String.valueOf(a[0]).toLowerCase(Locale.ROOT));
                default -> AppApiIntegrationHarness.empty(m.getReturnType()); });
            Server server = AppApiIntegrationHarness.proxy(Server.class, (p, m, a) -> switch (m.getName()) {
                case "getName", "getVersion", "getBukkitVersion", "getMinecraftVersion" -> "Harness1010";
                case "getPluginManager" -> plugins; case "getScheduler" -> scheduler; case "getCommandMap" -> commandMap;
                case "getLogger" -> java.util.logging.Logger.getLogger("Harness1010"); case "isPrimaryThread" -> true;
                case "getOnlinePlayers" -> List.copyOf(online.values()); case "getPlayer" -> a[0] instanceof UUID u ? online.get(u) : null;
                case "getWorlds" -> List.of(); case "getOperators" -> Set.of();
                default -> AppApiIntegrationHarness.empty(m.getReturnType()); });
            holder[0] = server; serverField.set(null, server);
            for (Object[] c : new Object[][]{{"essentials", new TestCommand("tpa", "essentials.tpa", "call")}, {"essentials", new TestCommand("home", "essentials.home")}, {"essentials", new TestCommand("spawn", "")}, {"litebans", new TestCommand("ban", "litebans.ban")}}) {
                Command cmd = (Command) c[1];
                for (String label : new ArrayList<>(List.of(cmd.getName()))) { known.put(label, cmd); known.put(c[0] + ":" + label, cmd); }
                for (String alias : cmd.getAliases()) { known.put(alias, cmd); known.put(c[0] + ":" + alias, cmd); }
            }

            SimplexAdminSuite plugin = Moderation790Harness.plugin(dir); Moderation790Harness.set(plugin, JavaPlugin.class, "isEnabled", true);
            plugin.accessLocks = new AccessLocks(plugin);
            CommandRights rights = new CommandRights(plugin);
            plugin.commandRights = rights;
            UUID steve = UUID.fromString("00000000-0000-0000-0000-00000000a001");
            Player player = AppApiIntegrationHarness.proxy(Player.class, (p, m, a) -> switch (m.getName()) {
                case "getUniqueId" -> steve; case "getServer" -> server; case "getName" -> "Steve"; case "getLocale" -> "de_de"; case "isOp" -> false;
                case "hasPermission" -> a[0] instanceof String s && (s.equals("essentials.tpa") || s.equals("essentials.home") || s.equals("essentials.spawn"));
                case "isPermissionSet" -> false; case "isOnline" -> true;
                case "sendMessage" -> { if (a[0] instanceof String s) messages.add(s); yield null; }
                case "updateCommands" -> null;
                default -> AppApiIntegrationHarness.empty(m.getReturnType()); });
            online.put(steve, player);

            check(CommandRights.canonical("call").equals("tpa") && CommandRights.canonical("/essentials:TPA").equals("tpa") && CommandRights.canonical("unknown").equals("unknown"), "aliases and namespaces resolve to the command");
            List<CommandRights.Info> catalog = rights.catalog();
            check(catalog.stream().map(CommandRights.Info::name).toList().containsAll(List.of("ban", "home", "spawn", "tpa")), "catalog lists every command once");
            check(catalog.stream().filter(i -> i.name().equals("tpa")).findFirst().orElseThrow().aliases().equals(List.of("call")), "aliases folded into the command");

            Map<String, Object> view = rights.player(steve, null);
            Map<String, Map<String, Object>> rows = byName(view);
            check("allowed".equals(rows.get("tpa").get("state")) && "noperm".equals(rows.get("ban").get("state")), "states from permissions: tpa allowed, ban not permitted by rank");
            check("Teleport".equals(rows.get("tpa").get("kind")) && "Moderation".equals(rows.get("ban").get("kind")), "kinds in the player view");

            var result = rights.apply(Map.of("operation", "block", "player", steve.toString(), "commands", "call", "duration", "1d", "reason", "Spam", "alts", "true"), "Tester", null);
            check(Boolean.TRUE.equals(result.get("ok")), "block accepted");
            rows = byName(rights.player(steve, null));
            check("blocked".equals(rows.get("tpa").get("state")) && Boolean.FALSE.equals(rows.get("tpa").get("allowed")), "alias blocked the real command");
            check(messages.stream().anyMatch(s -> s.contains("gesperrte Befehle")), "player is told");

            PlayerCommandPreprocessEvent run = new PlayerCommandPreprocessEvent(player, "/call Bob");
            rights.execute(run);
            check(run.isCancelled() && messages.stream().anyMatch(s -> s.contains("/tpa") && s.contains("Spam")), "blocked command cancelled with reason");
            PlayerCommandPreprocessEvent free = new PlayerCommandPreprocessEvent(player, "/home");
            rights.execute(free);
            check(!free.isCancelled(), "other commands still work");
            Collection<String> tab = new ArrayList<>(List.of("tpa", "call", "home", "essentials:tpa"));
            rights.tabList(new PlayerCommandSendEvent(player, tab));
            check(tab.equals(List.of("home")), "blocked command and its aliases disappear from the tab list");

            // Restart: everything is read back from command-rights.properties.
            check(Files.exists(dir.resolve("command-rights.properties")), "stored in its own file");
            CommandRights reloaded = new CommandRights(plugin);
            check("blocked".equals(byName(reloaded.player(steve, null)).get("tpa").get("state")), "block survives a restart");

            rights.apply(Map.of("operation", "global-block", "commands", "spawn"), "Tester", null);
            check("global".equals(byName(rights.player(steve, null)).get("spawn").get("state")), "global block applies to players");
            fails(() -> rights.apply(Map.of("operation", "global-block", "commands", "sas"), "Tester", null), "the suite command cannot be blocked globally");
            rights.apply(Map.of("operation", "allow", "player", steve.toString(), "commands", "spawn"), "Tester", null);
            rows = byName(rights.player(steve, null));
            check("allowed".equals(rows.get("spawn").get("state")) && Boolean.TRUE.equals(rows.get("spawn").get("override")), "allow beats a global block as an exception");
            rights.apply(Map.of("operation", "reset", "player", steve.toString(), "commands", "spawn"), "Tester", null);
            check("global".equals(byName(rights.player(steve, null)).get("spawn").get("state")), "reset returns to the global rule");
            rights.apply(Map.of("operation", "allow", "player", steve.toString(), "commands", "tpa"), "Tester", null);
            check("allowed".equals(byName(rights.player(steve, null)).get("tpa").get("state")) && Boolean.FALSE.equals(byName(rights.player(steve, null)).get("tpa").get("override")), "allow without another block simply removes the own block");

            List<Map<String, Object>> granted = new ArrayList<>();
            var grant = rights.apply(Map.of("operation", "allow", "player", steve.toString(), "commands", "ban", "grant", "true"), "Tester", change -> { granted.add(change); return Map.of("ok", true); });
            check(granted.size() == 1 && "user-permission-set".equals(granted.get(0).get("operation")) && "litebans.ban".equals(granted.get(0).get("node")) && ((List<?>) grant.get("granted")).contains("litebans.ban"), "missing rank permission granted through LuckPerms");
            granted.clear();
            rights.apply(Map.of("operation", "allow", "player", steve.toString(), "commands", "home", "grant", "true"), "Tester", change -> { granted.add(change); return Map.of(); });
            check(granted.isEmpty(), "nothing granted when the rank already allows it");

            rights.apply(Map.of("operation", "block", "player", steve.toString(), "commands", "home,tpa"), "Tester", null);
            check(rights.describe(steve).contains("2 gesperrt"), "short description for menus");
            Map<String, Object> overview = rights.overview();
            check(((List<?>) overview.get("players")).size() == 1 && ((List<?>) overview.get("global")).size() == 1 && ((Number) overview.get("total")).intValue() == catalog.size(), "overview: players, global blocks, total");
            rights.apply(Map.of("operation", "clear", "player", steve.toString()), "Tester", null);
            check("allowed".equals(byName(rights.player(steve, null)).get("tpa").get("state")) && rights.describe(steve).startsWith("Keine"), "clear removes every own entry");
            rights.apply(Map.of("operation", "global-allow", "commands", "spawn"), "Tester", null);
            check(((List<?>) rights.overview().get("global")).isEmpty(), "global block lifted");
            fails(() -> rights.apply(Map.of("operation", "block", "player", steve.toString(), "commands", "tpa", "reason", "x".repeat(201)), "Tester", null), "reason length bounded");
        } finally { serverField.set(null, old); }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Map<String, Object>> byName(Map<String, Object> view) {
        Map<String, Map<String, Object>> out = new HashMap<>();
        for (Object o : (List<?>) view.get("items")) { Map<String, Object> r = (Map<String, Object>) o; out.put(String.valueOf(r.get("name")), r); }
        return out;
    }
}
