package eu.simplexsmp.adminsuite;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * 9.9.0 LuckPerms management for the app and the web panel. Reading uses the LuckPerms API through reflection
 * (no compile-time dependency, works with every LuckPerms 5.x). Every change is a single, strictly validated
 * LuckPerms console command, so LuckPerms itself applies, stores and syncs it (including messaging services);
 * arguments are never concatenated from free text without validation.
 */
final class LuckPermsAdmin {
    static final String GROUP = "[a-z0-9_\\-]{1,36}";
    static final String NODE = "[A-Za-z0-9_.*\\-]{1,120}(\\.<[A-Za-z0-9_.\\-]{1,40}>)?";
    static final String PLAYER = "[A-Za-z0-9_.]{1,17}|[0-9a-fA-F-]{36}";
    static final String DURATION = "([0-9]{1,4}(mo|[smhdwy])){1,4}";
    static final String CONTEXT = "(server|world)=[A-Za-z0-9_.\\-]{1,40}";
    private final SimplexAdminSuite plugin;

    LuckPermsAdmin(SimplexAdminSuite plugin) { this.plugin = plugin; }

    boolean installed() { return Bukkit.getPluginManager().getPlugin("LuckPerms") != null; }

    private Object api() throws Exception {
        Plugin lp = Bukkit.getPluginManager().getPlugin("LuckPerms");
        if (lp == null) throw new IllegalArgumentException("LuckPerms ist nicht installiert.");
        Class<?> provider = Class.forName("net.luckperms.api.LuckPermsProvider", true, lp.getClass().getClassLoader());
        return provider.getMethod("get").invoke(null);
    }

    // ---------------------------------------------------------------- reading (HTTP worker thread)

    /** Groups with weight, display name, prefix/suffix, parents and node count; tracks; staff members with their groups. */
    Map<String, Object> overview() throws Exception {
        if (!installed()) return SimplexAdminSuite.appMap("installed", false, "groups", List.of(), "tracks", List.of());
        Object api = api();
        Object groupManager = call(api, "getGroupManager");
        List<Map<String, Object>> groups = new ArrayList<>();
        for (Object group : (Collection<?>) call(groupManager, "getLoadedGroups")) groups.add(groupSummary(group));
        groups.sort(Comparator.comparingInt((Map<String, Object> g) -> -((Number) g.get("weight")).intValue()).thenComparing(g -> String.valueOf(g.get("name"))));
        List<Map<String, Object>> tracks = new ArrayList<>();
        Object trackManager = optional(api, "getTrackManager");
        if (trackManager != null) for (Object track : (Collection<?>) call(trackManager, "getLoadedTracks"))
            tracks.add(SimplexAdminSuite.appMap("name", call(track, "getName"), "groups", new ArrayList<>((List<?>) call(track, "getGroups"))));
        tracks.sort(Comparator.comparing(t -> String.valueOf(t.get("name"))));
        Object platform = optional(api, "getPluginMetadata");
        return SimplexAdminSuite.appMap("installed", true, "version", platform == null ? "" : String.valueOf(optional(platform, "getVersion")), "groups", groups, "tracks", tracks, "templates", templates());
    }

    // ---------------------------------------------------------------- 10.1.0 group templates

    record Template(String id, String title, String explain, int weight, String prefix, List<String> permissions) { }
    static final Map<String, Template> TEMPLATES = new LinkedHashMap<>();
    static {
        template("spieler", "Spieler", "Grundrechte: Home, Spawn, TPA, Nachrichten, Bezahlen.", 1, "&7",
            "essentials.home", "essentials.sethome", "essentials.delhome", "essentials.spawn", "essentials.tpa", "essentials.tpaccept", "essentials.tpdeny", "essentials.msg", "essentials.r", "essentials.pay", "essentials.balance", "essentials.list", "lands.lands");
        template("vip", "VIP", "Wie Spieler, dazu Hut, Werkbank, Spitzname und Chatfarben.", 20, "&6&l✦ VIP &r",
            "essentials.hat", "essentials.workbench", "essentials.nick", "essentials.chat.color", "essentials.enderchest", "essentials.sethome.multiple");
        template("builder", "Builder", "Bauen mit WorldEdit und Kreativmodus.", 30, "&a[Builder] &r",
            "worldedit.*", "minecraft.command.gamemode", "essentials.gamemode", "essentials.fly", "essentials.speed");
        template("supporter", "Supporter", "Tickets, Verlauf ansehen, Kick und Mute.", 50, "&8[&bSupport&8] &r",
            "minecraft.command.kick", "litebans.history", "litebans.mute", "litebans.kick", "litebans.warn", "essentials.mute", "essentials.kick", "simplexadminsuite.tickets");
        template("moderator", "Moderator", "Wie Supporter, dazu Banns, Vanish, Inventare und Rechte-Sperren.", 70, "&9[Mod] &r",
            "minecraft.command.kick", "minecraft.command.ban", "litebans.ban", "litebans.tempban", "litebans.mute", "litebans.tempmute", "litebans.kick", "litebans.warn", "litebans.history",
            "essentials.vanish", "essentials.invsee", "essentials.tp", "simplexadminsuite.accesslocks", "simplexadminsuite.tickets");
        template("admin", "Admin", "Verwaltung: Suite-Admin, Spielmodus, Teleport, Items, WorldEdit und Essentials.", 90, "&c&l[Admin] &r",
            "simplexadminsuite.admin", "minecraft.command.gamemode", "minecraft.command.tp", "minecraft.command.give", "essentials.*", "worldedit.*", "litebans.*");
    }
    private static void template(String id, String title, String explain, int weight, String prefix, String... permissions) { TEMPLATES.put(id, new Template(id, title, explain, weight, prefix, List.of(permissions))); }
    static List<Map<String, Object>> templates() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Template t : TEMPLATES.values()) out.add(SimplexAdminSuite.appMap("id", t.id(), "title", t.title(), "explain", t.explain(), "weight", t.weight(), "prefix", t.prefix(), "permissions", t.permissions()));
        return out;
    }

    /** Preloads a LuckPerms user (offline players from storage) off the main thread; null without LuckPerms. */
    static Object loadUserQuietly(UUID uuid) {
        try {
            Plugin lp = Bukkit.getPluginManager().getPlugin("LuckPerms");
            if (lp == null) return null;
            Object api = Class.forName("net.luckperms.api.LuckPermsProvider", true, lp.getClass().getClassLoader()).getMethod("get").invoke(null);
            Object manager = call(api, "getUserManager");
            Object user = call(manager, "getUser", uuid);
            if (user == null) user = ((Future<?>) call(manager, "loadUser", uuid)).get(5, TimeUnit.SECONDS);
            return user;
        } catch (Throwable unavailable) { return null; }
    }

    private Map<String, Object> groupSummary(Object group) throws Exception {
        Object weight = optional(group, "getWeight");
        int w = weight instanceof OptionalInt o && o.isPresent() ? o.getAsInt() : 0;
        Collection<?> nodes = (Collection<?>) call(group, "getNodes");
        List<String> parents = new ArrayList<>(); int permissions = 0;
        for (Object node : nodes) { String key = String.valueOf(call(node, "getKey")); if (key.startsWith("group.")) parents.add(key.substring(6)); else if (!key.startsWith("prefix.") && !key.startsWith("suffix.") && !key.startsWith("weight.") && !key.startsWith("displayname.") && !key.startsWith("meta.")) permissions++; }
        Object meta = optional(optional(group, "getCachedData"), "getMetaData");
        return SimplexAdminSuite.appMap("name", call(group, "getName"), "displayName", String.valueOf(Objects.requireNonNullElse(optional(group, "getDisplayName"), "")), "weight", w,
            "prefix", String.valueOf(Objects.requireNonNullElse(optional(meta, "getPrefix"), "")), "suffix", String.valueOf(Objects.requireNonNullElse(optional(meta, "getSuffix"), "")),
            "parents", parents, "permissions", permissions);
    }

    /** One group with all of its nodes and (up to 300) direct members. */
    Map<String, Object> group(String raw) throws Exception {
        String name = groupName(raw);
        Object api = api(), group = call(call(api, "getGroupManager"), "getGroup", name);
        if (group == null) throw new IllegalArgumentException("Gruppe nicht gefunden");
        Map<String, Object> out = groupSummary(group);
        out.put("nodes", nodes((Collection<?>) call(group, "getNodes")));
        List<Map<String, Object>> members = new ArrayList<>();
        try {
            Class<?> matcher = Class.forName("net.luckperms.api.node.matcher.NodeMatcher", true, api.getClass().getClassLoader());
            Object filter = matcher.getMethod("key", String.class).invoke(null, "group." + name);
            Object future = call(call(api, "getUserManager"), "searchAll", filter);
            Map<?, ?> found = (Map<?, ?>) ((Future<?>) future).get(8, TimeUnit.SECONDS);
            for (Object id : found.keySet()) { if (members.size() >= 300) break; UUID uuid = (UUID) id; OfflinePlayer p = Bukkit.getOfflinePlayer(uuid); members.add(SimplexAdminSuite.appMap("uuid", uuid.toString(), "name", p.getName() == null ? uuid.toString().substring(0, 8) : p.getName(), "online", p.isOnline())); }
            members.sort(Comparator.comparing(m -> String.valueOf(m.get("name")).toLowerCase(Locale.ROOT)));
            out.put("memberCount", found.size());
        } catch (Throwable searchUnavailable) { out.put("memberCount", -1); }
        out.put("members", members);
        return out;
    }

    /** Groups, primary group and own nodes of a player (loaded from storage when offline). */
    Map<String, Object> user(String raw) throws Exception {
        UUID uuid = resolve(raw);
        Object api = api(), manager = call(api, "getUserManager");
        Object user = call(manager, "getUser", uuid);
        if (user == null) user = ((Future<?>) call(manager, "loadUser", uuid)).get(8, TimeUnit.SECONDS);
        if (user == null) throw new IllegalArgumentException("LuckPerms kennt diesen Spieler nicht");
        Collection<?> nodes = (Collection<?>) call(user, "getNodes");
        List<Map<String, Object>> groups = new ArrayList<>();
        for (Map<String, Object> node : nodes(nodes)) if (String.valueOf(node.get("key")).startsWith("group.")) { Map<String, Object> g = new LinkedHashMap<>(node); g.put("group", String.valueOf(node.get("key")).substring(6)); groups.add(g); }
        Object meta = optional(optional(user, "getCachedData"), "getMetaData");
        OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
        return SimplexAdminSuite.appMap("uuid", uuid.toString(), "name", Objects.requireNonNullElse(optional(user, "getUsername"), player.getName() == null ? raw : player.getName()), "online", player.isOnline(),
            "primary", String.valueOf(optional(user, "getPrimaryGroup")), "groups", groups, "nodes", nodes(nodes).stream().filter(n -> !String.valueOf(n.get("key")).startsWith("group.")).toList(),
            "prefix", String.valueOf(Objects.requireNonNullElse(optional(meta, "getPrefix"), "")), "suffix", String.valueOf(Objects.requireNonNullElse(optional(meta, "getSuffix"), "")));
    }

    /** Effective permission check for many players (online: Bukkit, offline: LuckPerms cached data). */
    Map<UUID, Boolean> check(Collection<UUID> players, String node) {
        Map<UUID, Boolean> out = new LinkedHashMap<>();
        Object manager = null;
        try { manager = call(api(), "getUserManager"); } catch (Throwable unavailable) { /* no LuckPerms */ }
        for (UUID uuid : players) {
            Player online = Bukkit.getPlayer(uuid);
            if (online != null) { out.put(uuid, online.hasPermission(node)); continue; }
            if (manager == null) { out.put(uuid, false); continue; }
            try {
                Object user = call(manager, "getUser", uuid);
                if (user == null) user = ((Future<?>) call(manager, "loadUser", uuid)).get(5, TimeUnit.SECONDS);
                Object data = call(call(user, "getCachedData"), "getPermissionData");
                out.put(uuid, Boolean.TRUE.equals(call(call(data, "checkPermission", node), "asBoolean")));
            } catch (Throwable e) { out.put(uuid, false); }
        }
        return out;
    }

    private List<Map<String, Object>> nodes(Collection<?> nodes) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object node : nodes) {
            Object expiry = optional(node, "getExpiry");
            Object contexts = optional(node, "getContexts");
            String ctx = contexts == null ? "" : String.valueOf(optional(contexts, "toString"));
            if (ctx.equals("ImmutableContextSet{}") || ctx.equals("{}") || ctx.equals("[]")) ctx = "";
            out.add(SimplexAdminSuite.appMap("key", call(node, "getKey"), "value", call(node, "getValue"), "expiry", expiry instanceof java.time.Instant i ? i.toEpochMilli() : 0L, "context", contextText(contexts, ctx)));
        }
        out.sort(Comparator.comparing(n -> String.valueOf(n.get("key"))));
        return out;
    }
    private static String contextText(Object contexts, String fallback) {
        try {
            Object set = optional(contexts, "toSet");
            if (set instanceof Collection<?> c) { List<String> parts = new ArrayList<>(); for (Object ctx : c) parts.add(optional(ctx, "getKey") + "=" + optional(ctx, "getValue")); Collections.sort(parts); return String.join(", ", parts); }
        } catch (Throwable ignored) { }
        return fallback;
    }

    // ---------------------------------------------------------------- changes (main thread)

    /** 10.1.0: all commands of an operation – a template expands to several, everything else is one. */
    static List<String> commands(Map<String, Object> values) {
        if (!text(values, "operation").equals("group-template")) return List.of(command(values));
        Template t = TEMPLATES.get(text(values, "template"));
        if (t == null) throw new IllegalArgumentException("Unbekannte Vorlage (" + String.join(", ", TEMPLATES.keySet()) + ")");
        String group = check(text(values, "group").isEmpty() ? t.id() : text(values, "group").toLowerCase(Locale.ROOT), GROUP, "Gruppenname: a–z, 0–9, _ oder -");
        List<String> out = new ArrayList<>();
        out.add("lp creategroup " + group);
        out.add("lp group " + group + " setweight " + t.weight());
        out.add("lp group " + group + " meta setprefix " + t.weight() + " \"" + affix(t.prefix()) + "\"");
        if (!group.equals("default")) out.add("lp group " + group + " parent add default");
        for (String node : t.permissions()) out.add("lp group " + group + " permission set " + check(node, NODE, "Recht ungültig") + " true");
        return out;
    }

    /** Builds the exact LuckPerms command for an operation, or throws on any invalid argument. */
    static String command(Map<String, Object> values) {
        String op = text(values, "operation"), group = text(values, "group").toLowerCase(Locale.ROOT), player = text(values, "player"), node = text(values, "node"),
            value = text(values, "value"), duration = text(values, "duration"), track = text(values, "track").toLowerCase(Locale.ROOT), context = text(values, "context");
        String ctx = context.isEmpty() ? "" : " " + check(context, CONTEXT, "Kontext: server=… oder world=…");
        // Prefixes and suffixes keep their spaces ("&c[Admin] " separates rank and name).
        String affix = values.get("value") == null ? "" : String.valueOf(values.get("value"));
        return switch (op) {
            case "group-create" -> "lp creategroup " + check(group, GROUP, "Gruppenname: a–z, 0–9, _ oder -");
            case "group-delete" -> { check(group, GROUP, "Gruppe ungültig"); if (group.equals("default")) throw new IllegalArgumentException("Die Gruppe default kann nicht gelöscht werden."); yield "lp deletegroup " + group; }
            case "group-permission-set" -> "lp group " + check(group, GROUP, "Gruppe ungültig") + " permission set " + check(node, NODE, "Recht ungültig") + " " + bool(value) + ctx;
            case "group-permission-temp" -> "lp group " + check(group, GROUP, "Gruppe ungültig") + " permission settemp " + check(node, NODE, "Recht ungültig") + " " + bool(value) + " " + check(duration, DURATION, "Dauer z. B. 1d, 12h, 30m") + ctx;
            case "group-permission-unset" -> "lp group " + check(group, GROUP, "Gruppe ungültig") + " permission unset " + check(node, NODE, "Recht ungültig") + ctx;
            case "group-parent-add" -> "lp group " + check(group, GROUP, "Gruppe ungültig") + " parent add " + check(value.toLowerCase(Locale.ROOT), GROUP, "Elterngruppe ungültig") + ctx;
            case "group-parent-remove" -> "lp group " + check(group, GROUP, "Gruppe ungültig") + " parent remove " + check(value.toLowerCase(Locale.ROOT), GROUP, "Elterngruppe ungültig") + ctx;
            case "group-weight" -> "lp group " + check(group, GROUP, "Gruppe ungültig") + " setweight " + check(value, "[0-9]{1,6}", "Gewicht: 0–999999");
            case "group-displayname" -> "lp group " + check(group, GROUP, "Gruppe ungültig") + " setdisplayname " + check(value, "[\\p{L}\\p{N} _.\\-]{1,32}", "Anzeigename: 1–32 Zeichen");
            case "group-prefix" -> { check(group, GROUP, "Gruppe ungültig"); yield affix.isBlank() ? "lp group " + group + " meta removeprefix " + priority(values) : "lp group " + group + " meta setprefix " + priority(values) + " \"" + affix(affix) + "\""; }
            case "group-suffix" -> { check(group, GROUP, "Gruppe ungültig"); yield affix.isBlank() ? "lp group " + group + " meta removesuffix " + priority(values) : "lp group " + group + " meta setsuffix " + priority(values) + " \"" + affix(affix) + "\""; }
            case "user-parent-add" -> "lp user " + check(player, PLAYER, "Spieler ungültig") + " parent add " + check(group, GROUP, "Gruppe ungültig") + ctx;
            case "user-parent-temp" -> "lp user " + check(player, PLAYER, "Spieler ungültig") + " parent addtemp " + check(group, GROUP, "Gruppe ungültig") + " " + check(duration, DURATION, "Dauer z. B. 7d") + ctx;
            case "user-parent-remove" -> "lp user " + check(player, PLAYER, "Spieler ungültig") + " parent remove " + check(group, GROUP, "Gruppe ungültig") + ctx;
            case "user-parent-set" -> "lp user " + check(player, PLAYER, "Spieler ungültig") + " parent set " + check(group, GROUP, "Gruppe ungültig") + ctx;
            case "user-permission-set" -> "lp user " + check(player, PLAYER, "Spieler ungültig") + " permission set " + check(node, NODE, "Recht ungültig") + " " + bool(value) + ctx;
            case "user-permission-temp" -> "lp user " + check(player, PLAYER, "Spieler ungültig") + " permission settemp " + check(node, NODE, "Recht ungültig") + " " + bool(value) + " " + check(duration, DURATION, "Dauer z. B. 1d") + ctx;
            case "user-permission-unset" -> "lp user " + check(player, PLAYER, "Spieler ungültig") + " permission unset " + check(node, NODE, "Recht ungültig") + ctx;
            case "user-prefix" -> { check(player, PLAYER, "Spieler ungültig"); yield affix.isBlank() ? "lp user " + player + " meta removeprefix " + priority(values) : "lp user " + player + " meta setprefix " + priority(values) + " \"" + affix(affix) + "\""; }
            case "user-suffix" -> { check(player, PLAYER, "Spieler ungültig"); yield affix.isBlank() ? "lp user " + player + " meta removesuffix " + priority(values) : "lp user " + player + " meta setsuffix " + priority(values) + " \"" + affix(affix) + "\""; }
            case "user-promote" -> "lp user " + check(player, PLAYER, "Spieler ungültig") + " promote " + check(track, GROUP, "Track ungültig");
            case "user-demote" -> "lp user " + check(player, PLAYER, "Spieler ungültig") + " demote " + check(track, GROUP, "Track ungültig");
            case "sync" -> "lp sync";
            default -> throw new IllegalArgumentException("Unbekannte LuckPerms-Aktion");
        };
    }

    /**
     * 10.0.0: every permission the server knows, for autocomplete in app and web panel – registered Bukkit
     * permissions (with description), LuckPerms' known permissions and all nodes already used by groups.
     * Main thread (Bukkit's permission registry).
     */
    List<Map<String, Object>> knownPermissions() {
        Map<String, String[]> all = new TreeMap<>();
        Map<String, String> plugins = new HashMap<>();
        for (Plugin p : Bukkit.getPluginManager().getPlugins()) plugins.put(p.getName().toLowerCase(Locale.ROOT).replace(" ", ""), p.getName());
        for (org.bukkit.permissions.Permission perm : Bukkit.getPluginManager().getPermissions()) {
            String name = perm.getName();
            if (name == null || name.length() > 120 || !name.matches("[A-Za-z0-9_.*\\-]+")) continue;
            all.put(name.toLowerCase(Locale.ROOT), new String[]{name, perm.getDescription() == null ? "" : perm.getDescription().trim(), "server"});
        }
        if (installed()) {
            try {
                Object platform = call(api(), "getPlatform");
                Object known = optional(platform, "getKnownPermissions");
                if (known instanceof Collection<?> c) for (Object o : c) { String n = String.valueOf(o); if (n.length() <= 120 && n.matches("[A-Za-z0-9_.*\\-]+")) all.putIfAbsent(n.toLowerCase(Locale.ROOT), new String[]{n, "", "luckperms"}); }
                for (Object group : (Collection<?>) call(call(api(), "getGroupManager"), "getLoadedGroups"))
                    for (Object node : (Collection<?>) call(group, "getNodes")) {
                        String key = String.valueOf(call(node, "getKey"));
                        if (key.matches("(group|prefix|suffix|weight|displayname|meta)\\..*") || key.length() > 120) continue;
                        all.putIfAbsent(key.toLowerCase(Locale.ROOT), new String[]{key, "", "group"});
                    }
            } catch (Throwable ignored) { /* LuckPerms API changed: Bukkit list stays */ }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (String[] v : all.values()) {
            if (out.size() >= 6000) break;
            String root = v[0].contains(".") ? v[0].substring(0, v[0].indexOf('.')).toLowerCase(Locale.ROOT) : v[0].toLowerCase(Locale.ROOT);
            out.add(SimplexAdminSuite.appMap("node", v[0], "description", v[1], "plugin", plugins.getOrDefault(root, root), "source", v[2]));
        }
        return out;
    }

    /** Human-readable confirmation text shown before the change is executed. */
    static String summary(Map<String, Object> values) {
        List<String> cmds = commands(values);
        if (cmds.size() == 1) return "**LuckPerms-Änderung**\n`/" + cmds.get(0) + "`\nLuckPerms speichert und synchronisiert die Änderung selbst. Rechte wirken sofort für online Spieler.";
        Template t = TEMPLATES.get(text(values, "template"));
        return "**LuckPerms-Vorlage „" + t.title() + "“**\n" + t.explain() + "\n" + cmds.size() + " Befehle: Gruppe anlegen, Gewicht " + t.weight() + ", Präfix, erbt default und "
            + t.permissions().size() + " Rechte (" + String.join(", ", t.permissions()) + "). Bestehende Rechte der Gruppe bleiben erhalten.";
    }

    Map<String, Object> apply(Map<String, Object> values, String actor) {
        if (!installed()) throw new IllegalArgumentException("LuckPerms ist nicht installiert.");
        List<String> cmds = commands(values);
        boolean ok = true;
        for (String cmd : cmds) ok &= Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        String cmd = cmds.size() == 1 ? cmds.get(0) : cmds.get(0) + " … (" + cmds.size() + " Befehle)";
        plugin.suiteAudit(actor, "LuckPerms geändert", cmd);
        return SimplexAdminSuite.appMap("ok", ok, "title", ok ? (cmds.size() == 1 ? "An LuckPerms übergeben" : "Vorlage an LuckPerms übergeben") : "LuckPerms hat den Befehl abgelehnt", "description", "/" + cmd + (ok ? "\nDie Ansicht aktualisiert sich in wenigen Sekunden." : ""), "command", cmd);
    }

    private static String text(Map<String, Object> values, String key) { Object v = values.get(key); return v == null ? "" : String.valueOf(v).trim(); }
    private static String check(String value, String pattern, String message) { if (value == null || !value.matches(pattern)) throw new IllegalArgumentException(message); return value; }
    private static String bool(String value) { if (value.isEmpty() || value.equals("true")) return "true"; if (value.equals("false")) return "false"; throw new IllegalArgumentException("Wert: true oder false"); }
    private static String priority(Map<String, Object> values) { String p = text(values, "priority"); return p.isEmpty() ? "100" : check(p, "[0-9]{1,6}", "Priorität: Zahl"); }
    private static String affix(String value) {
        if (value.length() > 64 || value.contains("\"") || value.contains("\\") || value.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Präfix/Suffix: höchstens 64 Zeichen, ohne Anführungszeichen und Backslash");
        return value;
    }
    static String groupName(String raw) { String v = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT); return check(v, GROUP, "Gruppe ungültig"); }
    UUID resolve(String raw) {
        String v = raw == null ? "" : raw.trim();
        try { return UUID.fromString(v); } catch (IllegalArgumentException notUuid) { /* name */ }
        if (!v.matches("[A-Za-z0-9_.]{1,17}")) throw new IllegalArgumentException("Spielername oder UUID angeben");
        Player online = Bukkit.getPlayerExact(v); if (online != null) return online.getUniqueId();
        for (var profile : plugin.expansionProfiles()) if (profile.displayName().equalsIgnoreCase(v)) return profile.uniqueId();
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(v);
        if (cached != null) return cached.getUniqueId();
        throw new IllegalArgumentException("Spieler war noch nie auf dem Server");
    }

    // ---------------------------------------------------------------- reflection

    static Object call(Object target, String name, Object... args) throws Exception {
        if (target == null) throw new NoSuchMethodException(name);
        Method method = find(target.getClass(), name, args);
        if (method == null) throw new NoSuchMethodException(target.getClass().getName() + "." + name);
        method.trySetAccessible();
        return method.invoke(target, args);
    }
    static Object optional(Object target, String name, Object... args) { if (target == null) return null; try { return call(target, name, args); } catch (Throwable e) { return null; } }
    private static Method find(Class<?> type, String name, Object[] args) {
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
            Class<?>[] parameters = method.getParameterTypes(); boolean ok = true;
            for (int i = 0; i < parameters.length; i++) { if (args[i] == null) continue; Class<?> p = parameters[i].isPrimitive() ? box(parameters[i]) : parameters[i]; if (!p.isAssignableFrom(args[i].getClass())) { ok = false; break; } }
            if (ok) return method;
        }
        return null;
    }
    private static Class<?> box(Class<?> p) { return p == boolean.class ? Boolean.class : p == int.class ? Integer.class : p == long.class ? Long.class : p == double.class ? Double.class : p == float.class ? Float.class : p == short.class ? Short.class : p == byte.class ? Byte.class : Character.class; }
}
