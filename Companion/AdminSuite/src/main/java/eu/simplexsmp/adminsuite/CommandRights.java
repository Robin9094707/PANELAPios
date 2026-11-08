package eu.simplexsmp.adminsuite;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * 10.1.0 "Befehlsrechte": every command the server knows, per player switchable between allowed and blocked –
 * live, optionally temporary, with a reason, and (by default) inherited by confirmed alt accounts. An explicit
 * "allow" of a player beats an inherited or global block; whether the player may use a command at all still
 * comes from the permission plugin (LuckPerms), which the app and web panel can grant in the same step.
 *
 * Players without any entry cost one map lookup per command. State lives in {@code command-rights.properties}
 * (one JSON object per player plus {@code global}) and is written atomically; nothing else changes.
 */
final class CommandRights implements Listener {
    record Entry(String mode, long until, String reason, String actor, long since, boolean alts) {
        boolean active(long now) { return until == 0L || until > now; }
        boolean blocks() { return mode.equals("block"); }
    }
    /** One known command (snapshot of the command map, main thread). */
    record Info(String name, List<String> aliases, String plugin, String kind, String permission, String description, String usage, Command command) { }

    static final String NAME = "[a-z0-9_.\\-]{1,64}";
    static final List<String> KIND_ORDER = List.of("Teleport", "Chat & Nachrichten", "Moderation", "Spieler & Spielmodus", "Wirtschaft & Handel", "Grundstücke",
        "Ränge & Rechte", "Welt & Server", "Weitere");
    private static final Map<String, String> KINDS = new LinkedHashMap<>();
    static {
        kind("Teleport", "tp", "tpa", "tpahere", "tpaccept", "tpdeny", "tpyes", "tpno", "tphere", "tpall", "tppos", "tpo", "tpoffline", "teleport", "home", "homes", "sethome", "delhome", "warp", "warps",
            "setwarp", "delwarp", "spawn", "setspawn", "back", "rtp", "wild", "randomtp", "top", "jump", "spreadplayers");
        kind("Chat & Nachrichten", "msg", "tell", "w", "whisper", "r", "reply", "mail", "me", "say", "broadcast", "bc", "chat", "ignore", "unignore", "nick", "nickname", "helpop", "socialspy",
            "msgtoggle", "teammsg", "tm", "emote", "realname", "list", "who");
        kind("Moderation", "ban", "tempban", "unban", "pardon", "ban-ip", "pardon-ip", "banlist", "kick", "mute", "tempmute", "unmute", "warn", "warnings", "unwarn", "jail", "unjail", "freeze",
            "vanish", "v", "invsee", "endersee", "enderchest", "history", "report", "reports", "checkban", "checkmute", "staffchat", "sc", "spectate", "litebans", "ipban", "alts", "dupeip",
            "seen", "whois", "lookup", "rechtesperre", "sas", "simplexadmin");
        kind("Spieler & Spielmodus", "gamemode", "gm", "gmc", "gms", "gma", "gmsp", "fly", "god", "heal", "feed", "speed", "hat", "repair", "fix", "enchant", "xp", "experience", "give", "i",
            "item", "kit", "kits", "clear", "clearinventory", "ci", "effect", "workbench", "wb", "craft", "anvil", "afk", "suicide", "kill", "skull", "more", "condense", "nightvision", "playtime");
        kind("Wirtschaft & Handel", "pay", "balance", "bal", "money", "eco", "economy", "baltop", "shop", "sell", "sellall", "worth", "auction", "ah", "auctionhouse", "trade", "market",
            "points", "spcurrency", "bank", "jobs", "quests");
        kind("Grundstücke", "lands", "land", "claim", "unclaim", "trust", "untrust", "plot", "plots", "p", "residence", "res", "gp", "griefprevention", "town", "nation");
        kind("Ränge & Rechte", "lp", "luckperms", "perm", "perms", "permissions", "pex", "rank", "ranks", "op", "deop", "promote", "demote");
        kind("Welt & Server", "time", "weather", "difficulty", "gamerule", "worldborder", "setblock", "fill", "clone", "summon", "seed", "reload", "rl", "stop", "restart", "save-all", "save-on",
            "save-off", "whitelist", "plugins", "pl", "version", "ver", "about", "tps", "mspt", "timings", "spark", "world", "worlds", "mv", "multiverse", "datapack", "function", "schedule",
            "execute", "forceload", "locate", "place", "worldedit", "we", "/wand", "tick", "debug", "paper", "spigot", "bukkit", "minecraft", "setworldspawn", "defaultgamemode");
    }
    private static void kind(String kind, String... names) { for (String n : names) KINDS.putIfAbsent(n, kind); }
    static String kindOf(String name, String plugin) {
        String k = KINDS.get(name);
        if (k != null) return k;
        String p = plugin.toLowerCase(Locale.ROOT);
        if (p.contains("lands") || p.contains("griefprevention") || p.contains("towny")) return "Grundstücke";
        if (p.contains("luckperms")) return "Ränge & Rechte";
        if (p.contains("litebans") || p.contains("advancedban")) return "Moderation";
        if (p.contains("economy") || p.contains("shop") || p.contains("auction")) return "Wirtschaft & Handel";
        if (p.equals("minecraft") || p.equals("bukkit") || p.equals("paper") || p.equals("spigot") || p.contains("worldedit") || p.contains("multiverse")) return "Welt & Server";
        return "Weitere";
    }

    private final SimplexAdminSuite plugin;
    private final Path file;
    private final Map<UUID, Map<String, Entry>> rights = new ConcurrentHashMap<>();
    private final Map<String, Entry> global = new ConcurrentHashMap<>();
    private final Map<UUID, String> names = new ConcurrentHashMap<>();
    private volatile Map<UUID, Map<String, Entry>> inherited = Map.of();
    private volatile Map<UUID, Map<String, UUID>> inheritedFrom = Map.of();
    private volatile Map<UUID, Set<UUID>> heirs = Map.of();
    private final Map<String, Long> notified = new ConcurrentHashMap<>();
    private int ticks;

    CommandRights(SimplexAdminSuite plugin) {
        this.plugin = plugin;
        this.file = plugin.getDataFolder().toPath().resolve("command-rights.properties");
        load();
    }

    // ------------------------------------------------------------------ storage

    private void load() {
        if (!Files.exists(file)) return;
        Properties stored = new Properties();
        try (var in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { stored.load(in); }
        catch (IOException e) { plugin.getLogger().warning("Befehlsrechte konnten nicht gelesen werden; Datei bleibt unverändert: " + e.getMessage()); return; }
        for (String key : stored.stringPropertyNames()) {
            try {
                if (!(DiscordBotManager.Json.parse(stored.getProperty(key)) instanceof Map<?, ?> row)) continue;
                Map<String, Entry> entries = new ConcurrentHashMap<>();
                if (row.get("commands") instanceof Map<?, ?> commands) for (var e : commands.entrySet()) {
                    String name = String.valueOf(e.getKey());
                    if (!name.matches(NAME) || !(e.getValue() instanceof Map<?, ?> v)) continue;
                    String mode = String.valueOf(v.get("mode"));
                    entries.put(name, new Entry(mode.equals("allow") ? "allow" : "block", number(v.get("until")), str(v.get("reason")), str(v.get("by")), number(v.get("since")), !Boolean.FALSE.equals(v.get("alts"))));
                }
                if (key.equals("global")) { global.putAll(entries); continue; }
                UUID id = UUID.fromString(key);
                if (row.get("name") != null) names.put(id, String.valueOf(row.get("name")));
                if (!entries.isEmpty()) rights.put(id, entries);
            } catch (RuntimeException broken) { plugin.getLogger().warning("Befehlsrecht " + key + " übersprungen (ungültig)."); }
        }
    }

    private synchronized void save() {
        Properties out = new Properties();
        for (var e : rights.entrySet()) if (!e.getValue().isEmpty()) out.setProperty(e.getKey().toString(), DiscordBotManager.Json.stringify(SimplexAdminSuite.appMap("name", names.getOrDefault(e.getKey(), ""), "commands", encode(e.getValue()))));
        if (!global.isEmpty()) out.setProperty("global", DiscordBotManager.Json.stringify(SimplexAdminSuite.appMap("commands", encode(global))));
        try { AtomicState.save(file, out, "Simplex Admin Suite command rights (10.1.0)"); }
        catch (IOException e) { plugin.getLogger().warning("Befehlsrechte konnten nicht gespeichert werden: " + e.getMessage()); }
    }
    private static Map<String, Object> encode(Map<String, Entry> map) {
        Map<String, Object> out = new TreeMap<>();
        map.forEach((name, e) -> out.put(name, SimplexAdminSuite.appMap("mode", e.mode(), "until", e.until(), "reason", e.reason(), "by", e.actor(), "since", e.since(), "alts", e.alts())));
        return out;
    }

    /** Expires entries and recomputes alt inheritance (every five seconds from the plugin scheduler). */
    void tick() {
        long now = System.currentTimeMillis(); boolean changed = false;
        for (var it = rights.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            List<String> expired = e.getValue().entrySet().stream().filter(x -> !x.getValue().active(now)).map(Map.Entry::getKey).toList();
            for (String name : expired) {
                Entry old = e.getValue().remove(name); changed = true;
                Player online = Bukkit.getPlayer(e.getKey());
                if (online != null && old != null && old.blocks()) online.sendMessage(plugin.prefix() + plugin.playerText(online, "§aDu darfst §f/" + name + " §awieder benutzen.", "§aYou may use §f/" + name + " §aagain."));
            }
            if (e.getValue().isEmpty()) it.remove();
        }
        if (global.values().removeIf(x -> !x.active(now))) changed = true;
        notified.entrySet().removeIf(x -> now - x.getValue() > 10_000L);
        if (changed) { save(); refreshInheritance(); updateCommands(null); }
        else if (++ticks >= 6) { ticks = 0; refreshInheritance(); }
    }

    // ------------------------------------------------------------------ alt inheritance (same rules as access locks)

    synchronized void refreshInheritance() {
        long now = System.currentTimeMillis();
        Map<UUID, Map<String, Entry>> next = new HashMap<>();
        Map<UUID, Map<String, UUID>> origin = new HashMap<>();
        Map<UUID, Set<UUID>> family = new HashMap<>();
        for (var source : rights.entrySet()) {
            boolean any = source.getValue().values().stream().anyMatch(e -> e.blocks() && e.alts() && e.active(now));
            if (!any || !inherits(source.getKey())) continue;
            Set<UUID> alts = plugin.confirmedAlts(source.getKey());
            String sourceName = name(source.getKey());
            for (UUID target : alts) {
                if (!inherits(target)) continue;
                family.computeIfAbsent(source.getKey(), k -> new LinkedHashSet<>()).add(target);
                for (var c : source.getValue().entrySet()) {
                    Entry e = c.getValue();
                    if (!e.blocks() || !e.alts() || !e.active(now)) continue;
                    Map<String, Entry> forTarget = next.computeIfAbsent(target, k -> new HashMap<>());
                    Entry old = forTarget.get(c.getKey());
                    if (old == null || old.until() != 0 && (e.until() == 0 || e.until() > old.until())) {
                        forTarget.put(c.getKey(), new Entry("block", e.until(), "Vererbt von " + sourceName + (e.reason().isBlank() ? "" : ": " + e.reason()), "Alt-Vererbung (" + sourceName + ")", e.since(), false));
                        origin.computeIfAbsent(target, k -> new HashMap<>()).put(c.getKey(), source.getKey());
                    }
                }
            }
        }
        inherited = next; inheritedFrom = origin; heirs = family;
    }
    private boolean inherits(UUID id) { try { return plugin.altInherit(id); } catch (RuntimeException e) { return false; } }

    @EventHandler(priority = EventPriority.MONITOR)
    public void joinRefresh(PlayerJoinEvent e) { if (!rights.isEmpty()) Bukkit.getScheduler().runTaskLater(plugin, this::refreshInheritance, 200L); }

    // ------------------------------------------------------------------ effective state

    /** The command's primary name (namespace and alias resolved) or the bare root when unknown. */
    static String canonical(String root) {
        String bare = root == null ? "" : root.toLowerCase(Locale.ROOT).replaceFirst("^/", "");
        Command c = bare.isEmpty() ? null : Bukkit.getCommandMap().getCommand(bare);
        if (bare.contains(":")) bare = bare.substring(bare.indexOf(':') + 1);
        return c == null ? bare : c.getName().toLowerCase(Locale.ROOT);
    }
    /** Reason why the command is blocked for this player, or null when the Suite does not block it. */
    Entry blocking(Player p, String name) {
        long now = System.currentTimeMillis();
        Map<String, Entry> own = rights.get(p.getUniqueId());
        Entry e = own == null ? null : own.get(name);
        if (e != null && e.active(now)) return e.blocks() ? e : null;
        Map<String, Entry> inh = inherited.get(p.getUniqueId());
        Entry i = inh == null ? null : inh.get(name);
        if (i != null && i.active(now)) return i;
        Entry g = global.get(name);
        if (g != null && g.active(now) && !globalExempt(p)) return g;
        return null;
    }
    private boolean globalExempt(Player p) { return p.isOp() || plugin.has(p, "simplexadminsuite.commands.bypass"); }
    private boolean idle() { return rights.isEmpty() && global.isEmpty() && inherited.isEmpty(); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void execute(PlayerCommandPreprocessEvent event) {
        if (idle()) return;
        String root = PlayerCommandManager.messageRoot(event.getMessage());
        if (root.isEmpty()) return;
        Player p = event.getPlayer();
        String name = canonical(root);
        Entry e = blocking(p, name);
        if (e == null) return;
        event.setCancelled(true);
        String rest = e.until() == 0 ? plugin.playerText(p, "dauerhaft", "permanent") : AccessLocks.remaining(e.until() - System.currentTimeMillis(), plugin.playerText(p, "de", "en").equals("de"));
        p.sendMessage(plugin.prefix() + plugin.playerText(p, "§cDu darfst §f/" + name + " §cnicht benutzen §7(" + rest + (e.reason().isBlank() ? "" : " · " + e.reason()) + ")",
            "§cYou may not use §f/" + name + " §7(" + rest + (e.reason().isBlank() ? "" : " · " + e.reason()) + ")"));
        String key = p.getUniqueId() + ":" + name;
        if (notified.putIfAbsent(key, System.currentTimeMillis()) == null) plugin.suiteAudit("System", "Gesperrter Befehl versucht", p.getName() + " · /" + name);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void tabList(PlayerCommandSendEvent event) {
        if (idle()) return;
        Player p = event.getPlayer();
        Map<String, Boolean> cache = new HashMap<>();
        event.getCommands().removeIf(root -> cache.computeIfAbsent(canonical(root), n -> blocking(p, n) != null));
    }

    /** Re-sends the command tree so tab completion matches the new state (one player and its heirs, or everyone). */
    void updateCommands(UUID id) {
        Collection<UUID> targets = new LinkedHashSet<>();
        if (id == null) Bukkit.getOnlinePlayers().forEach(p -> targets.add(p.getUniqueId()));
        else { targets.add(id); targets.addAll(heirs.getOrDefault(id, Set.of())); }
        for (UUID t : targets) { Player p = Bukkit.getPlayer(t); if (p != null) p.updateCommands(); }
    }

    // ------------------------------------------------------------------ catalog (main thread)

    /** Every distinct command of the server (aliases folded in), sorted by name. */
    List<Info> catalog() {
        Map<String, Command> known = Bukkit.getCommandMap().getKnownCommands();
        Map<Command, Set<String>> namespaces = new IdentityHashMap<>();
        for (var e : known.entrySet()) {
            int colon = e.getKey().indexOf(':');
            if (colon > 0 && e.getValue() != null) namespaces.computeIfAbsent(e.getValue(), k -> new TreeSet<>()).add(e.getKey().substring(0, colon));
        }
        Map<String, Info> out = new TreeMap<>();
        Set<Command> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Command> candidates = new ArrayList<>(known.values());
        // Show the command reached by its plain name when multiple plugins register that name.
        candidates.sort(Comparator.nullsLast(Comparator.comparing((Command c) -> known.get(c.getName().toLowerCase(Locale.ROOT)) != c)));
        for (Command c : candidates) {
            if (c == null || !seen.add(c)) continue;
            String name = c.getName().toLowerCase(Locale.ROOT);
            if (!name.matches(NAME) || out.containsKey(name)) continue;
            String owner;
            if (c instanceof PluginIdentifiableCommand pic) owner = pic.getPlugin().getName();
            else {
                Set<String> ns = namespaces.getOrDefault(c, Set.of());
                String n = ns.contains("minecraft") ? "minecraft" : ns.isEmpty() ? "server" : ns.iterator().next();
                owner = switch (n) { case "minecraft" -> "Minecraft"; case "bukkit" -> "Bukkit"; case "paper" -> "Paper"; case "spigot" -> "Spigot"; case "server" -> "Server"; default -> Character.toUpperCase(n.charAt(0)) + n.substring(1); };
            }
            List<String> aliases = c.getAliases().stream().map(a -> a.toLowerCase(Locale.ROOT)).filter(a -> !a.equals(name) && known.get(a) == c).distinct().sorted().toList();
            String permission = c.getPermission() == null ? "" : c.getPermission();
            if (permission.isEmpty() && owner.equals("Minecraft")) permission = "minecraft.command." + name;
            out.put(name, new Info(name, aliases, owner, kindOf(name, owner), permission, Objects.requireNonNullElse(c.getDescription(), "").trim(), Objects.requireNonNullElse(c.getUsage(), "").trim(), c));
        }
        return new ArrayList<>(out.values());
    }

    /** Whether the player may use the command by permission (online: Bukkit; offline: LuckPerms data, null = unknown). */
    static Boolean permitted(Info info, Player online, Object lpUser) {
        if (online != null) return info.command().testPermissionSilent(online);
        if (info.permission().isEmpty()) return true;
        if (lpUser == null) return null;
        try {
            Object data = LuckPermsAdmin.call(LuckPermsAdmin.call(lpUser, "getCachedData"), "getPermissionData");
            for (String node : info.permission().split(";")) if (Boolean.TRUE.equals(LuckPermsAdmin.call(LuckPermsAdmin.call(data, "checkPermission", node.trim()), "asBoolean"))) return true;
            return false;
        } catch (Throwable e) { return null; }
    }

    /** Overview without a player: catalog sizes, global blocks and players with own command rights. */
    Map<String, Object> overview() {
        List<Info> all = catalog();
        long now = System.currentTimeMillis();
        List<Map<String, Object>> globals = new ArrayList<>();
        new TreeMap<>(global).forEach((n, e) -> { if (e.active(now)) globals.add(entryMap(n, e, now)); });
        List<Map<String, Object>> players = new ArrayList<>();
        Set<UUID> ids = new LinkedHashSet<>(rights.keySet()); ids.addAll(inherited.keySet());
        for (UUID id : ids) {
            List<String> blocked = new ArrayList<>(), allowed = new ArrayList<>(), fromAlts = new ArrayList<>();
            rights.getOrDefault(id, Map.of()).forEach((n, e) -> { if (e.active(now)) (e.blocks() ? blocked : allowed).add(n); });
            inherited.getOrDefault(id, Map.of()).forEach((n, e) -> { if (e.active(now) && !rights.getOrDefault(id, Map.of()).containsKey(n)) fromAlts.add(n); });
            if (blocked.isEmpty() && allowed.isEmpty() && fromAlts.isEmpty()) continue;
            Collections.sort(blocked); Collections.sort(allowed); Collections.sort(fromAlts);
            players.add(SimplexAdminSuite.appMap("uuid", id.toString(), "name", name(id), "online", Bukkit.getPlayer(id) != null, "blocked", blocked, "allowed", allowed, "inherited", fromAlts));
        }
        players.sort(Comparator.comparing((Map<String, Object> r) -> !Boolean.TRUE.equals(r.get("online"))).thenComparing(r -> String.valueOf(r.get("name")).toLowerCase(Locale.ROOT)));
        List<Map<String, Object>> items = new ArrayList<>();
        for (Info i : all) {
            Entry g = global.get(i.name());
            items.add(SimplexAdminSuite.appMap("name", i.name(), "aliases", i.aliases(), "plugin", i.plugin(), "kind", i.kind(), "permission", i.permission(), "description", i.description(),
                "global", g != null && g.active(now), "globalRemaining", g != null && g.active(now) ? remaining(g, now) : ""));
        }
        return SimplexAdminSuite.appMap("total", all.size(), "items", items, "global", globals, "players", players, "kinds", KIND_ORDER, "durations", List.of("30m", "2h", "12h", "1d", "3d", "7d", "14d", "30d", ""),
            "luckperms", Bukkit.getPluginManager().getPlugin("LuckPerms") != null,
            "help", "Schalter an = Befehl erlaubt, aus = gesperrt. Sperren der Suite gelten sofort (auch für OPs) und werden – wenn eingeschaltet – an bestätigte Alt-Accounts vererbt. Ob ein Spieler einen Befehl überhaupt nutzen darf, entscheidet zusätzlich sein Rang (LuckPerms).");
    }

    /** Full per-player view: every command with its effective state. {@code lpUser} is a preloaded LuckPerms user (or null). */
    Map<String, Object> player(UUID id, Object lpUser) {
        long now = System.currentTimeMillis();
        Player online = Bukkit.getPlayer(id);
        Map<String, Entry> own = rights.getOrDefault(id, Map.of()), inh = inherited.getOrDefault(id, Map.of());
        Map<String, UUID> inhFrom = inheritedFrom.getOrDefault(id, Map.of());
        boolean exempt = online != null && globalExempt(online);
        List<Map<String, Object>> items = new ArrayList<>();
        int allowedCount = 0, blockedCount = 0;
        for (Info i : catalog()) {
            Entry o = own.get(i.name()); if (o != null && !o.active(now)) o = null;
            Entry h = inh.get(i.name()); if (h != null && !h.active(now)) h = null;
            Entry g = global.get(i.name()); if (g != null && (!g.active(now) || exempt)) g = null;
            Boolean perm = permitted(i, online, lpUser);
            String state;
            if (o != null && o.blocks()) state = "blocked";
            else if (o == null && h != null) state = "inherited";
            else if (o == null && g != null) state = "global";
            else if (Boolean.FALSE.equals(perm)) state = "noperm";
            else state = "allowed";
            boolean allowed = state.equals("allowed");
            if (allowed) allowedCount++; else blockedCount++;
            Map<String, Object> row = SimplexAdminSuite.appMap("name", i.name(), "aliases", i.aliases(), "plugin", i.plugin(), "kind", i.kind(), "permission", i.permission(),
                "description", i.description(), "permitted", perm, "state", state, "allowed", allowed, "override", o != null && !o.blocks());
            if (o != null) row.put("own", entryMap(i.name(), o, now));
            if (o == null && h != null) { UUID src = inhFrom.get(i.name()); row.put("inheritedFrom", src == null ? "" : name(src)); row.put("remaining", remaining(h, now)); }
            if (o == null && h == null && g != null) { row.put("remaining", remaining(g, now)); row.put("reason", g.reason()); }
            items.add(row);
        }
        List<Map<String, Object>> alts = plugin.confirmedAlts(id).stream().map(a -> SimplexAdminSuite.appMap("uuid", a.toString(), "name", name(a), "online", Bukkit.getPlayer(a) != null, "inherit", inherits(a))).toList();
        return SimplexAdminSuite.appMap("uuid", id.toString(), "name", name(id), "online", online != null, "op", online != null && online.isOp(), "inherit", inherits(id),
            "items", items, "allowedCount", allowedCount, "blockedCount", blockedCount, "alts", alts,
            "heirs", heirs.getOrDefault(id, Set.of()).stream().map(a -> SimplexAdminSuite.appMap("uuid", a.toString(), "name", name(a))).toList(),
            "permissionsKnown", online != null || lpUser != null, "kinds", KIND_ORDER, "durations", List.of("30m", "2h", "12h", "1d", "3d", "7d", "14d", "30d", ""),
            "luckperms", Bukkit.getPluginManager().getPlugin("LuckPerms") != null);
    }
    private static Map<String, Object> entryMap(String name, Entry e, long now) {
        return SimplexAdminSuite.appMap("command", name, "mode", e.mode(), "until", e.until(), "permanent", e.until() == 0, "remaining", remaining(e, now), "reason", e.reason(), "by", e.actor(), "since", e.since(), "alts", e.alts());
    }
    private static String remaining(Entry e, long now) { return e.until() == 0 ? "dauerhaft" : AccessLocks.remaining(e.until() - now, true); }

    /** Short line for menus: how many commands a player has blocked or explicitly allowed. */
    String describe(UUID id) {
        long now = System.currentTimeMillis();
        long b = rights.getOrDefault(id, Map.of()).values().stream().filter(e -> e.active(now) && e.blocks()).count();
        long a = rights.getOrDefault(id, Map.of()).values().stream().filter(e -> e.active(now) && !e.blocks()).count();
        long i = inherited.getOrDefault(id, Map.of()).keySet().stream().filter(n -> !rights.getOrDefault(id, Map.of()).containsKey(n)).count();
        if (b + a + i == 0) return "Keine eigenen Befehlsrechte";
        return (b > 0 ? b + " gesperrt" : "") + (a > 0 ? (b > 0 ? " · " : "") + a + " freigegeben" : "") + (i > 0 ? (b + a > 0 ? " · " : "") + i + " geerbt" : "");
    }

    // ------------------------------------------------------------------ changes (main thread)

    static List<String> commandList(String raw) {
        List<String> out = new ArrayList<>();
        for (String part : String.valueOf(raw == null ? "" : raw).split("[,; ]+")) {
            String n = part.trim().toLowerCase(Locale.ROOT).replaceFirst("^/", "");
            if (n.isEmpty()) continue;
            if (n.contains(":")) n = n.substring(n.indexOf(':') + 1);
            if (!n.matches(NAME)) throw new IllegalArgumentException("Befehl ungültig: " + part.trim());
            if (!out.contains(n)) out.add(n);
        }
        if (out.isEmpty()) throw new IllegalArgumentException("Mindestens einen Befehl angeben");
        if (out.size() > 400) throw new IllegalArgumentException("Höchstens 400 Befehle auf einmal");
        return out;
    }
    /** Validated names resolved to the real command (aliases and namespaces map to the primary name). Main thread. */
    static List<String> resolved(String raw) {
        List<String> out = new ArrayList<>();
        for (String n : commandList(raw)) { String c = canonical(n); if (!c.matches(NAME)) c = n; if (!out.contains(c)) out.add(c); }
        return out;
    }
    private static String text(Map<String, Object> values, String key) { Object v = values.get(key); return v == null ? "" : String.valueOf(v).trim(); }
    private static String list(List<String> names) { return names.size() <= 6 ? "/" + String.join(", /", names) : "/" + String.join(", /", names.subList(0, 6)) + " und " + (names.size() - 6) + " weitere"; }

    /** Plan shown before the change. Operations: block, allow, reset, clear (player) · global-block, global-allow (everyone). */
    static String summary(Map<String, Object> values, String playerName) {
        String op = text(values, "operation");
        StringBuilder b = new StringBuilder("**Befehlsrechte** · ").append(op.startsWith("global") ? "alle Spieler" : playerName).append('\n');
        switch (op) {
            case "block", "global-block" -> {
                List<String> names = commandList(text(values, "commands"));
                long d = AccessLocks.duration(text(values, "duration"));
                b.append("Sperren: ").append(list(names)).append('\n').append("Dauer: ").append(d == 0 ? "dauerhaft" : AccessLocks.remaining(d, true));
                if (op.equals("block")) b.append('\n').append("Alt-Accounts: ").append(Boolean.parseBoolean(text(values, "alts").isEmpty() ? "true" : text(values, "alts")) ? "erben die Sperre" : "nicht betroffen");
                else b.append('\n').append("OPs und simplexadminsuite.commands.bypass sind ausgenommen.");
            }
            case "allow" -> {
                b.append("Erlauben: ").append(list(commandList(text(values, "commands"))));
                if (Boolean.parseBoolean(text(values, "grant"))) b.append("\nFehlende Rechte werden in LuckPerms für den Spieler gesetzt.");
            }
            case "reset" -> b.append("Zurück auf Standard (Rang entscheidet): ").append(list(commandList(text(values, "commands"))));
            case "global-allow" -> b.append("Globale Sperre aufheben: ").append(list(commandList(text(values, "commands"))));
            case "clear" -> b.append("Alle eigenen Befehlsrechte dieses Spielers entfernen.");
            default -> throw new IllegalArgumentException("Aktion: block, allow, reset, clear, global-block oder global-allow");
        }
        String reason = text(values, "reason");
        if (!reason.isEmpty()) b.append("\nGrund: ").append(reason);
        return b.toString();
    }

    /**
     * Applies a validated change. {@code grant} receives LuckPerms changes (user-permission-set) when the caller may
     * edit LuckPerms; null means missing permissions stay missing.
     */
    Map<String, Object> apply(Map<String, Object> values, String actor, java.util.function.Function<Map<String, Object>, Map<String, Object>> grant) {
        String op = text(values, "operation"), reason = text(values, "reason");
        if (reason.length() > 200 || reason.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Grund: höchstens 200 Zeichen");
        boolean globalOp = op.startsWith("global");
        UUID id = globalOp ? null : plugin.accessLocks.resolve(text(values, "player"));
        String who = id == null ? "alle Spieler" : name(id);
        summary(values, who);
        long now = System.currentTimeMillis();
        String title, detail;
        List<String> granted = new ArrayList<>();
        if (globalOp) {
            List<String> cmds = resolved(text(values, "commands"));
            if (op.equals("global-block")) {
                long d = AccessLocks.duration(text(values, "duration"));
                for (String n : cmds) { if (n.equals("sas") || n.equals("simplexadmin")) throw new IllegalArgumentException("/" + n + " kann nicht global gesperrt werden."); global.put(n, new Entry("block", d == 0 ? 0 : now + d, reason, actor, now, false)); }
                title = "Befehle für alle gesperrt"; detail = list(cmds) + " · " + (d == 0 ? "dauerhaft" : AccessLocks.remaining(d, true));
            } else { cmds.forEach(global::remove); title = "Globale Sperre aufgehoben"; detail = list(cmds); }
            save(); updateCommands(null);
        } else {
            Map<String, Entry> map = rights.computeIfAbsent(id, k -> new ConcurrentHashMap<>());
            names.put(id, who);
            switch (op) {
                case "block" -> {
                    List<String> cmds = resolved(text(values, "commands"));
                    long d = AccessLocks.duration(text(values, "duration"));
                    boolean alts = text(values, "alts").isEmpty() || Boolean.parseBoolean(text(values, "alts"));
                    for (String n : cmds) map.put(n, new Entry("block", d == 0 ? 0 : now + d, reason, actor, now, alts));
                    title = "Befehle gesperrt"; detail = list(cmds) + " · " + (d == 0 ? "dauerhaft" : AccessLocks.remaining(d, true)) + (alts ? " · inkl. Alt-Accounts" : "");
                }
                case "allow" -> {
                    List<String> cmds = resolved(text(values, "commands"));
                    Map<String, Info> byName = new HashMap<>(); for (Info i : catalog()) byName.put(i.name(), i);
                    Map<String, Entry> inh = inherited.getOrDefault(id, Map.of());
                    for (String n : cmds) {
                        Entry g = global.get(n);
                        boolean override = inh.containsKey(n) || g != null && g.active(now);
                        if (override) map.put(n, new Entry("allow", 0, reason, actor, now, false)); else map.remove(n);
                        Info info = byName.get(n);
                        if (grant != null && Boolean.parseBoolean(text(values, "grant")) && info != null && !info.permission().isEmpty()) {
                            Player online = Bukkit.getPlayer(id);
                            if (online == null || !info.command().testPermissionSilent(online)) {
                                String node = info.permission().split(";")[0].trim();
                                if (node.matches("[A-Za-z0-9_.*\\-]{1,120}")) { grant.apply(SimplexAdminSuite.appMap("operation", "user-permission-set", "player", id.toString(), "node", node, "value", "true")); granted.add(node); }
                            }
                        }
                    }
                    title = "Befehle erlaubt"; detail = list(cmds) + (granted.isEmpty() ? "" : " · LuckPerms: " + String.join(", ", granted));
                }
                case "reset" -> { List<String> cmds = resolved(text(values, "commands")); cmds.forEach(map::remove); title = "Befehlsrechte zurückgesetzt"; detail = list(cmds); }
                default -> { map.clear(); title = "Alle Befehlsrechte entfernt"; detail = "Nur noch der Rang entscheidet"; }
            }
            if (map.isEmpty()) rights.remove(id);
            save(); refreshInheritance(); updateCommands(id);
            Player online = Bukkit.getPlayer(id);
            if (online != null && op.equals("block")) online.sendMessage(plugin.prefix() + plugin.playerText(online, "§cFür dich gesperrte Befehle: §f" + detail + (reason.isEmpty() ? "" : " §7Grund: " + reason), "§cCommands blocked for you." + (reason.isEmpty() ? "" : " §7Reason: " + reason)));
        }
        plugin.suiteAudit(actor, title, who + " · " + detail);
        Map<String, Object> result = SimplexAdminSuite.appMap("ok", true, "title", title, "description", who + ": " + detail);
        if (!granted.isEmpty()) result.put("granted", granted);
        return result;
    }

    String name(UUID id) {
        String stored = names.get(id);
        if (Bukkit.getPlayer(id) == null && stored != null && !stored.isBlank()) return stored;
        return plugin.accessLocks == null ? id.toString().substring(0, 8) : plugin.accessLocks.name(id);
    }

    // ------------------------------------------------------------------ in-game menu (Java inventory + Bedrock forms)

    private AdminUpdateMenu.Button button(String title, String detail, Material icon, Runnable action) { return new AdminUpdateMenu.Button(title, detail, icon, action); }

    /** Command kinds of one player; each opens the alphabetical list with one-click switches. */
    void menu(Player admin, UUID id) {
        if (!plugin.has(admin, "simplexadminsuite.accesslocks") && !plugin.has(admin, "simplexadminsuite.rights")) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Object lpUser = LuckPermsAdmin.loadUserQuietly(id);
            Bukkit.getScheduler().runTask(plugin, () -> showKinds(admin, id, player(id, lpUser)));
        });
    }
    private void showKinds(Player admin, UUID id, Map<String, Object> data) {
        if (!admin.isOnline()) return;
        List<?> items = (List<?>) data.get("items");
        Map<String, int[]> counts = new LinkedHashMap<>(); KIND_ORDER.forEach(k -> counts.put(k, new int[2]));
        for (Object o : items) { Map<?, ?> r = (Map<?, ?>) o; int[] c = counts.computeIfAbsent(String.valueOf(r.get("kind")), k -> new int[2]); c[Boolean.TRUE.equals(r.get("allowed")) ? 0 : 1]++; }
        List<AdminUpdateMenu.Button> buttons = new ArrayList<>();
        buttons.add(AdminUpdateMenu.info("Befehle von " + data.get("name"), data.get("allowedCount") + " erlaubt · " + data.get("blockedCount") + " gesperrt\n" + describe(id) + "\n§7Grün = erlaubt, Rot = gesperrt. Klick schaltet um.", Material.COMMAND_BLOCK));
        counts.forEach((kind, c) -> { if (c[0] + c[1] > 0) buttons.add(button(kind, "§a" + c[0] + " erlaubt §7· §c" + c[1] + " gesperrt", c[1] > 0 ? Material.RED_STAINED_GLASS : Material.LIME_STAINED_GLASS, () -> list(admin, id, kind, 0))); });
        buttons.add(button("Alle Befehle A–Z", items.size() + " Befehle", Material.BOOK, () -> list(admin, id, "", 0)));
        buttons.add(button("Befehl suchen", "Teil des Namens eingeben", Material.NAME_TAG, () -> plugin.adminUpdate.prompt(admin, "Befehl (auch Teil)", "", q -> list(admin, id, "?" + q.toLowerCase(Locale.ROOT).replaceFirst("^/", ""), 0))));
        if (rights.containsKey(id)) buttons.add(button("§cEigene Befehlsrechte entfernen", "Nur noch der Rang entscheidet", Material.MILK_BUCKET, () -> run(admin, id, SimplexAdminSuite.appMap("operation", "clear"), () -> menu(admin, id))));
        buttons.add(button("Zurück", "Rechte & Sperren", Material.OAK_DOOR, () -> plugin.accessLocks.playerMenu(admin, id)));
        plugin.adminUpdate.show(admin, "Befehle · " + data.get("name"), buttons, 0);
    }
    private void list(Player admin, UUID id, String filter, int page) {
        Map<String, Object> data = player(id, null);
        List<AdminUpdateMenu.Button> buttons = new ArrayList<>();
        for (Object o : (List<?>) data.get("items")) {
            Map<?, ?> r = (Map<?, ?>) o;
            String n = String.valueOf(r.get("name")), state = String.valueOf(r.get("state"));
            if (filter.startsWith("?") ? !n.contains(filter.substring(1)) : !filter.isEmpty() && !filter.equals(r.get("kind"))) continue;
            boolean allowed = Boolean.TRUE.equals(r.get("allowed"));
            String line = switch (state) {
                case "blocked" -> "§cGesperrt · " + ((Map<?, ?>) r.get("own")).get("remaining");
                case "inherited" -> "§dGeerbt von " + r.get("inheritedFrom") + " · " + r.get("remaining");
                case "global" -> "§6Für alle gesperrt · " + r.get("remaining");
                case "noperm" -> "§7Kein Recht (Rang)";
                default -> Boolean.TRUE.equals(r.get("override")) ? "§aErlaubt (Ausnahme)" : "§aErlaubt";
            };
            buttons.add(button((allowed ? "§a/" : "§c/") + n, line + "\n§7" + r.get("plugin") + (String.valueOf(r.get("permission")).isEmpty() ? "" : " · " + r.get("permission")) + "\n§7Klick: " + (allowed ? "sperren (Dauer wählen)" : "erlauben"),
                allowed ? Material.LIME_DYE : state.equals("noperm") ? Material.GRAY_DYE : Material.RED_DYE,
                () -> { if (allowed) duration(admin, id, n, filter, page); else run(admin, id, SimplexAdminSuite.appMap("operation", "allow", "commands", n, "grant", String.valueOf(admin.isOp() || plugin.adminAccess.master(admin))), () -> list(admin, id, filter, page)); }));
        }
        if (buttons.isEmpty()) buttons.add(AdminUpdateMenu.info("Keine Befehle", "Nichts passt zu dieser Auswahl.", Material.BARRIER));
        buttons.add(button("Zurück", "Befehlsarten", Material.OAK_DOOR, () -> menu(admin, id)));
        plugin.adminUpdate.show(admin, (filter.isEmpty() ? "A–Z" : filter.startsWith("?") ? "Suche " + filter.substring(1) : filter) + " · " + data.get("name"), buttons, page);
    }
    private void duration(Player admin, UUID id, String command, String filter, int page) {
        List<AdminUpdateMenu.Button> buttons = new ArrayList<>();
        for (String d : List.of("30m", "2h", "1d", "7d", "30d", ""))
            buttons.add(button(d.isEmpty() ? "Dauerhaft" : AccessLocks.remaining(AccessLocks.duration(d), true), "/" + command + " sperren · Alt-Accounts erben", d.isEmpty() ? Material.OBSIDIAN : Material.CLOCK,
                () -> run(admin, id, SimplexAdminSuite.appMap("operation", "block", "commands", command, "duration", d, "alts", "true"), () -> list(admin, id, filter, page))));
        buttons.add(button("Nur dieses Konto (dauerhaft)", "Alt-Accounts erben diese Sperre nicht", Material.ENDER_EYE,
            () -> run(admin, id, SimplexAdminSuite.appMap("operation", "block", "commands", command, "duration", "", "alts", "false"), () -> list(admin, id, filter, page))));
        buttons.add(button("Zurück", "Befehle", Material.OAK_DOOR, () -> list(admin, id, filter, page)));
        plugin.adminUpdate.show(admin, "/" + command + " · Dauer", buttons, 0);
    }
    private void run(Player admin, UUID id, Map<String, Object> values, Runnable then) {
        Map<String, Object> v = new LinkedHashMap<>(values); v.put("player", id.toString());
        try {
            boolean lp = admin.isOp() || plugin.adminAccess.master(admin);
            Map<String, Object> r = apply(v, admin.getName(), lp ? change -> new LuckPermsAdmin(plugin).apply(change, admin.getName()) : null);
            admin.sendMessage(plugin.prefix() + "§a" + r.get("title") + " §7· " + r.get("description"));
        } catch (IllegalArgumentException e) { admin.sendMessage(plugin.prefix() + "§c" + e.getMessage()); }
        Bukkit.getScheduler().runTaskLater(plugin, then, r(values) ? 20L : 1L);
    }
    private static boolean r(Map<String, Object> values) { return Boolean.parseBoolean(String.valueOf(values.get("grant"))); }

    private static long number(Object v) { if (v instanceof Number n) return n.longValue(); try { return Long.parseLong(String.valueOf(v)); } catch (NumberFormatException e) { return 0L; } }
    private static String str(Object v) { return v == null ? "" : String.valueOf(v); }
}
