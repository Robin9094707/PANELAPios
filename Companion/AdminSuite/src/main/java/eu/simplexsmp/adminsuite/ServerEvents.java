package eu.simplexsmp.adminsuite;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerLoginEvent;

/** One-shot, world-specific day schedules and independent, reversible join restrictions. Main thread only. */
final class ServerEvents {
    private static final String VIEW = "data.server-events", MANAGE = "server-events.manage", COMMANDS = "server-events.commands";
    private static final Set<String> MODES = Set.of("off", "known", "one-connection", "closed");
    private final SimplexAdminSuite plugin;
    private final Path file;
    private Properties state = new Properties();
    // One primary account per shared address during this lockdown session, even after it disconnects.
    private final Map<String, UUID> admitted = new LinkedHashMap<>();

    ServerEvents(SimplexAdminSuite plugin) throws IOException {
        this.plugin = plugin;
        file = plugin.getDataFolder().toPath().resolve("server-events.properties");
        if (Files.exists(file)) try (var reader = Files.newBufferedReader(file)) { state.load(reader); }
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40, 20);
    }

    private void commit(Properties next) throws IOException {
        AtomicState.save(file, next, "Simplex game-day schedules and lockdown");
        state = next;
    }

    private record Schedule(String id, String world, long day, String name, boolean activate, List<String> commands) {
        Map<String, Object> json() { return SimplexAdminSuite.appMap("id", id, "world", world, "day", day, "name", name, "activate", activate, "commands", commands); }
    }

    private static Schedule decode(String id, String raw) {
        var row = AppApiManager.object(DiscordBotManager.Json.parse(raw));
        return new Schedule(id, AiAdmin.text(row, "world"), AiAdmin.number(row.get("day")), AiAdmin.text(row, "name"),
            Boolean.TRUE.equals(row.get("activate")), ((List<?>) row.get("commands")).stream().map(String::valueOf).toList());
    }

    private List<Schedule> schedules() {
        return state.stringPropertyNames().stream().filter(key -> key.startsWith("event."))
            .map(key -> decode(key.substring(6), state.getProperty(key)))
            .sorted(Comparator.comparingLong(Schedule::day).thenComparing(Schedule::id)).toList();
    }

    String active() { return state.getProperty("active", ""); }
    String mode() { return state.getProperty("lockdown", "off"); }

    void login(PlayerLoginEvent event, boolean known) {
        if (event.getResult() != PlayerLoginEvent.Result.ALLOWED || mode().equals("off")) return;
        Player player = event.getPlayer();
        if (player.isOp() || plugin.has(player, "simplexadminsuite.lockdown.bypass")) return;
        String mode = mode();
        boolean denied = switch (mode) {
            case "closed" -> true;
            case "known" -> !known;
            case "one-connection" -> !known || conflictingAccount(player, event.getAddress());
            default -> false;
        };
        if (denied) event.disallow(PlayerLoginEvent.Result.KICK_OTHER, plugin.playerUiText(player,
            "Staff have temporarily restricted server joins. Please try again later."));
    }

    private boolean conflictingAccount(Player player, java.net.InetAddress address) {
        String ip = plugin.lockdownIpExcluded(address) ? "" : address.getHostAddress();
        UUID current = player.getUniqueId();
        UUID reserved = admitted.get(ip);
        if (reserved != null && !reserved.equals(current)) return true;
        if (admitted.values().stream().filter(id -> !id.equals(current))
            .anyMatch(id -> plugin.powerAltRelation(current, id, 85, false) != null)) return true;
        return Bukkit.getOnlinePlayers().stream().anyMatch(online -> !online.getUniqueId().equals(current)
            && (!ip.isEmpty() && online.getAddress() != null && address.equals(online.getAddress().getAddress())
                || plugin.powerAltRelation(current, online.getUniqueId(), 85, false) != null));
    }

    void joined(Player player) {
        if (!mode().equals("one-connection") || player.isOp() || plugin.has(player, "simplexadminsuite.lockdown.bypass")
            || player.getAddress() == null || admitted.size() >= 1024) return;
        java.net.InetAddress address = player.getAddress().getAddress();
        if (!plugin.lockdownIpExcluded(address)) admitted.putIfAbsent(address.getHostAddress(), player.getUniqueId());
    }

    private void tick() {
        if (plugin.appEvents == null) return;
        for (Schedule task : schedules()) {
            World world = Bukkit.getWorld(task.world);
            if (world == null || Math.floorDiv(world.getFullTime(), 24000) < task.day) continue;
            Properties next = (Properties) state.clone();
            next.remove("event." + task.id);
            if (task.activate) next.setProperty("active", task.name);
            try { commit(next); }
            catch (IOException error) { plugin.getLogger().warning("Event-Termin konnte nicht gespeichert werden"); return; }
            // Persist consumption before invoking commands. Retrying after a crash could duplicate side effects.
            int failures = 0;
            for (String command : task.commands) {
                try {
                    validateCommand(command);
                    if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)) failures++;
                } catch (RuntimeException failure) { failures++; }
            }
            String detail = task.world + " · Tag " + task.day + " · Befehle: " + (task.commands.size() - failures)
                + "/" + task.commands.size() + (failures == 0 ? "" : " ausgeführt; Fehler in der Konsole prüfen");
            plugin.appEvents.record("game-day", null, task.name, detail, true, "critical");
            plugin.suiteAudit("Event-Planer", task.name, detail);
        }
    }

    private static String clean(Object value, int limit) {
        String text = Objects.toString(value, "").strip();
        if (text.length() > limit || text.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Text ungültig");
        return text;
    }

    private static String validateCommand(String input) {
        String command = clean(input, 350).replaceFirst("^/", "");
        String root = command.split(" ", 2)[0].toLowerCase(Locale.ROOT);
        var registered = Bukkit.getCommandMap().getCommand(root);
        if (!root.matches("[a-z0-9_:-]{1,80}") || registered == null
            || registered instanceof org.bukkit.command.PluginIdentifiableCommand owned && !owned.getPlugin().isEnabled()
            || Set.of("sas", "simplexadmin", "op", "deop", "stop", "reload", "restart").contains(root.substring(root.lastIndexOf(':') + 1)))
            throw new IllegalArgumentException("Befehl nicht erkannt oder für den Event-Planer gesperrt: " + root);
        return command;
    }

    private Map<String, Object> data(boolean manage, boolean commands) {
        var worlds = Bukkit.getWorlds().stream().map(world -> SimplexAdminSuite.appMap("name", world.getName(),
            "day", Math.floorDiv(world.getFullTime(), 24_000))).toList();
        var items = schedules().stream().map(task -> commands ? task.json() : new Schedule(task.id, task.world, task.day,
            task.name, task.activate, List.of()).json()).toList();
        List<String> known = commands ? Bukkit.getCommandMap().getKnownCommands().keySet().stream()
            .filter(name -> name.contains(":")).filter(name -> {
                try { validateCommand(name); return true; } catch (IllegalArgumentException e) { return false; }
            }).sorted().limit(1500).toList() : List.of();
        return SimplexAdminSuite.appMap("active", active(), "lockdown", mode(), "worlds", worlds,
            "items", items, "manage", manage, "commands", commands, "known", known);
    }

    private Object apply(String actor, Map<String, Object> input, boolean canRunCommands) throws IOException {
        String operation = AiAdmin.text(input, "operation");
        Properties next = (Properties) state.clone();
        switch (operation) {
            case "status" -> next.setProperty("active", clean(input.get("name"), 80));
            case "lockdown" -> {
                String value = AiAdmin.text(input, "mode");
                if (!MODES.contains(value)) throw new IllegalArgumentException("Unbekannte Lockdown-Stufe");
                next.setProperty("lockdown", value);
            }
            case "delete" -> {
                String id = AiAdmin.text(input, "id");
                if (!next.containsKey("event." + id)) throw new AppApiManager.ApiError(404, "Termin nicht gefunden");
                var schedule = decode(id, next.getProperty("event." + id));
                if (!canRunCommands && !schedule.commands.isEmpty()) throw new AppApiManager.ApiError(403, "Befehlsrecht fehlt");
                next.remove("event." + id);
            }
            case "schedule" -> {
                String name = clean(input.get("name"), 80);
                String world = clean(input.get("world"), 120);
                World selected = Bukkit.getWorld(world);
                long day = AiAdmin.number(input.get("day"));
                if (name.isBlank() || selected == null || day < 0 || day > 100_000_000 || day <= Math.floorDiv(selected.getFullTime(), 24_000))
                    throw new IllegalArgumentException("Name, geladene Welt und ein zukünftiger Ingame-Tag sind erforderlich");
                String id = AiAdmin.text(input, "id");
                Schedule previous = id.isEmpty() ? null : schedules().stream().filter(task -> task.id.equals(id)).findFirst()
                    .orElseThrow(() -> new AppApiManager.ApiError(404, "Termin nicht gefunden"));
                if (previous == null && schedules().size() >= 80) throw new IllegalArgumentException("Maximal 80 aktive Termine");
                List<String> commands = new ArrayList<>();
                if (input.get("commands") instanceof List<?> list) {
                    if (list.size() > 8) throw new IllegalArgumentException("Maximal 8 Befehle pro Termin");
                    if (!list.isEmpty() && !canRunCommands) throw new AppApiManager.ApiError(403, "Befehlsrecht fehlt");
                    for (Object raw : list) commands.add(validateCommand(String.valueOf(raw)));
                }
                if (previous != null && !canRunCommands && !previous.commands.isEmpty()) throw new AppApiManager.ApiError(403, "Befehlsrecht fehlt");
                var schedule = new Schedule(previous == null ? UUID.randomUUID().toString() : id, world, day, name,
                    Boolean.TRUE.equals(input.get("activate")), List.copyOf(commands));
                next.setProperty("event." + schedule.id, DiscordBotManager.Json.stringify(schedule.json()));
            }
            default -> throw new IllegalArgumentException("Unbekannte Event-Aktion");
        }
        String previousMode = mode();
        commit(next);
        if (!previousMode.equals(mode())) {
            admitted.clear();
            if (mode().equals("one-connection")) Bukkit.getOnlinePlayers().stream()
                .sorted(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER)).forEach(this::joined);
        }
        plugin.suiteAudit(actor, "Event-Modus", operation + " " + AiAdmin.text(input, "name") + AiAdmin.text(input, "mode"));
        return Map.of("ok", true);
    }

    boolean route(AppApiManager api, HttpExchange x, AppApiSecurity.Key key, String path, String method, long rev) throws Exception {
        if (!path.equals("/v1/server-events")) return false;
        api.requirePermission(key, VIEW);
        boolean manage = api.allows(key, MANAGE) && AppApiSecurity.permitted(key, "manage");
        boolean commands = manage && key.scope().equals("full") && api.allows(key, COMMANDS);
        if (method.equals("GET")) AppApiManager.send(x, 200, api.onMain(rev, () -> data(manage, commands)));
        else if (method.equals("POST")) {
            if (!manage) throw new AppApiManager.ApiError(403, "Event-Verwaltung benötigt manage-Zugriff");
            var body = AppApiManager.body(x, 8192);
            if (!Boolean.TRUE.equals(body.get("confirmed"))) throw new IllegalArgumentException("Bestätigung fehlt");
            AppApiManager.send(x, 200, api.onMain(rev, () -> {
                api.requirePermission(key, MANAGE);
                return apply(key.label(), body, commands && api.allows(key, COMMANDS));
            }));
        } else throw new AppApiManager.ApiError(405, "Methode nicht erlaubt");
        return true;
    }

    boolean command(CommandSender sender, String[] args) {
        boolean console = sender instanceof ConsoleCommandSender;
        if (!console && (!(sender instanceof Player player) || !plugin.adminSessionUsable(player)
            || !plugin.has(player, "simplexadminsuite.events.manage"))) { sender.sendMessage("Keine Berechtigung."); return true; }
        boolean commands = console || sender instanceof Player player && plugin.adminAccess.master(player);
        try {
            if (args.length < 2 || args[1].equalsIgnoreCase("list")) {
                sender.sendMessage("Event: " + active() + " · Lockdown: " + mode());
                schedules().forEach(task -> sender.sendMessage(task.id + " · " + task.world + " · Tag " + task.day + " · " + task.name));
                sender.sendMessage("/sas event status [Name] | lockdown off/known/one-connection/closed | delete ID | schedule Welt Tag alarm/start Name [| Befehl]");
                return true;
            }
            String operation = args[1].toLowerCase(Locale.ROOT);
            Map<String, Object> body = new LinkedHashMap<>(Map.of("operation", operation));
            switch (operation) {
                case "status" -> body.put("name", String.join(" ", Arrays.copyOfRange(args, 2, args.length)));
                case "lockdown", "delete" -> body.put(operation.equals("delete") ? "id" : "mode", args.length >= 3 ? args[2] : "");
                case "schedule" -> {
                    if (args.length < 6 || !Set.of("alarm", "start").contains(args[4])) throw new IllegalArgumentException("schedule Welt Tag alarm/start Name [| Befehl]");
                    String[] fields = String.join(" ", Arrays.copyOfRange(args, 5, args.length)).split("\\|");
                    body.put("world", args[2]); body.put("day", Long.parseLong(args[3])); body.put("activate", args[4].equals("start"));
                    body.put("name", fields[0]); body.put("commands", Arrays.asList(fields).subList(1, fields.length));
                }
                default -> throw new IllegalArgumentException("Unbekannter Event-Befehl");
            }
            apply(sender.getName(), body, commands);
            sender.sendMessage("Event-Einstellung gespeichert.");
        } catch (Exception error) { sender.sendMessage("Event: " + error.getMessage()); }
        return true;
    }
}
