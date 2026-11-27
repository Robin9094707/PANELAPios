package eu.simplexsmp.adminsuite;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * 10.24.0: Länder-Zentrale – full land management for the app and the web panel on top of the public Lands API
 * (me.angeschossen.lands.api, checked against LandsAPI as of Lands 9.3). Lands stays an optional soft dependency:
 * every call goes through reflection, unknown methods only hide single fields, and every write runs on the main
 * thread through Lands' own API so Lands saves, fires its events and keeps its caches consistent.
 *
 * <p>Not possible through the API and therefore not offered: creating lands. Everything else that the API exposes
 * is: members and their roles per land and sub-area, role rights (action and management flags), natural flags,
 * bans, owner change, rename, enter message, taxes, bank balance, role names/priority/deletion, sub-area rename and
 * deletion (when the installed Lands build offers it) and land deletion.</p>
 *
 * <p>Safety: the owner role is never handed out here (only through "Besitzer ändern"), the owner cannot be removed or
 * banned, deletions need an explicit confirmation including the land name.</p>
 */
final class LandsAdmin {
    static final String FEATURE = "lands-center-v1";
    static final String VIEW = "data.lands";
    static final String MANAGE = "lands.manage";
    static final String DELETE = "lands.delete";

    private final SimplexAdminSuite plugin;
    private final LandsBridge bridge;
    private Object integration;
    private Plugin integrationOwner;

    LandsAdmin(SimplexAdminSuite plugin, LandsBridge bridge) { this.plugin = plugin; this.bridge = bridge; }

    // ------------------------------------------------------------------------------------------------ reflection

    private static final Map<String, Method> METHODS = new ConcurrentHashMap<>();

    /** Calls a public API method by name, matching the arguments' runtime types (null matches any reference type). */
    static Object call(Object target, String name, Object... args) throws Exception {
        if (target == null) throw new NoSuchMethodException(name);
        StringBuilder key = new StringBuilder(target.getClass().getName()).append('#').append(name);
        for (Object a : args) key.append('/').append(a == null ? "null" : a.getClass().getName());
        Method method = METHODS.get(key.toString());
        if (method == null) {
            method = find(target.getClass(), name, args, null);
            if (method == null) throw new NoSuchMethodException(target.getClass().getSimpleName() + "." + name);
            method.trySetAccessible();
            METHODS.put(key.toString(), method);
        }
        return invoke(method, target, args);
    }

    /** Like {@link #call} but picks the overload by the simple names of its parameter types (for null arguments). */
    static Object typed(Object target, String name, String[] types, Object... args) throws Exception {
        if (target == null) throw new NoSuchMethodException(name);
        Method method = find(target.getClass(), name, args, types);
        if (method == null) throw new NoSuchMethodException(target.getClass().getSimpleName() + "." + name + Arrays.toString(types));
        method.trySetAccessible();
        return invoke(method, target, args);
    }

    static Object optional(Object target, String name, Object... args) {
        if (target == null) return null;
        try { return call(target, name, args); } catch (Throwable e) { return null; }
    }

    static boolean has(Object target, String name, int parameters) {
        if (target == null) return false;
        for (Method m : target.getClass().getMethods()) if (m.getName().equals(name) && m.getParameterCount() == parameters) return true;
        return false;
    }

    private static Object invoke(Method method, Object target, Object[] args) throws Exception {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause() == null ? wrapped : wrapped.getCause();
            if (cause instanceof Exception e) throw e;
            throw new IllegalStateException(cause.getClass().getSimpleName() + ": " + cause.getMessage());
        }
    }

    private static Method find(Class<?> type, String name, Object[] args, String[] simpleTypes) {
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
            Class<?>[] parameters = method.getParameterTypes();
            boolean ok = true;
            for (int i = 0; i < parameters.length && ok; i++) {
                if (simpleTypes != null && !parameters[i].getSimpleName().equals(simpleTypes[i])) ok = false;
                else if (args[i] != null) {
                    Class<?> p = parameters[i].isPrimitive() ? box(parameters[i]) : parameters[i];
                    if (!p.isAssignableFrom(args[i].getClass())) ok = false;
                } else if (parameters[i].isPrimitive()) ok = false;
            }
            if (ok) return method;
        }
        return null;
    }

    private static Class<?> box(Class<?> p) {
        return p == boolean.class ? Boolean.class : p == int.class ? Integer.class : p == long.class ? Long.class : p == double.class ? Double.class
            : p == float.class ? Float.class : p == short.class ? Short.class : p == byte.class ? Byte.class : Character.class;
    }

    // ------------------------------------------------------------------------------------------------ Lands access

    Plugin lands() { return bridge.lands(); }

    /** The LandsIntegration of this plugin (LandsIntegration.of(plugin)); recreated after a Lands reload. Main thread. */
    synchronized Object api() throws Exception {
        Plugin lands = lands();
        if (lands == null) throw new IllegalArgumentException("Lands ist nicht installiert oder nicht aktiv.");
        if (integration != null && integrationOwner == lands) return integration;
        ClassLoader loader = lands.getClass().getClassLoader();
        for (String name : List.of("me.angeschossen.lands.api.LandsIntegration", "me.angeschossen.lands.api.integration.LandsIntegration")) {
            try {
                Class<?> type = Class.forName(name, true, loader);
                Object created;
                try { created = type.getMethod("of", Plugin.class).invoke(null, plugin); }
                catch (NoSuchMethodException old) { created = type.getConstructor(Plugin.class).newInstance(plugin); }
                if (created != null) { integration = created; integrationOwner = lands; return created; }
            } catch (ClassNotFoundException next) { /* try the older package */ }
        }
        throw new IllegalArgumentException("Lands-API wurde nicht gefunden (Lands-Version prüfen).");
    }

    Collection<?> allLands() throws Exception {
        Object all = call(api(), "getLands");
        return all instanceof Collection<?> c ? c : List.of();
    }

    Object land(String id) throws Exception {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Land fehlt");
        String wanted = id.trim();
        for (Object land : allLands()) {
            if (wanted.equals(String.valueOf(optional(land, "getULID")))) return land;
        }
        for (Object land : allLands()) {
            if (wanted.equalsIgnoreCase(String.valueOf(optional(land, "getName")))) return land;
        }
        throw new IllegalArgumentException("Land nicht gefunden – wurde es gerade gelöscht oder umbenannt?");
    }

    Object area(Object land, String id) throws Exception {
        Object fallback = call(land, "getDefaultArea");
        if (id == null || id.isBlank() || id.equals("default")) return fallback;
        Object all = optional(land, "getAllAreas");
        if (all instanceof Collection<?> areas) {
            for (Object area : areas) if (id.equals(String.valueOf(optional(area, "getULID")))) return area;
            for (Object area : areas) if (id.equalsIgnoreCase(String.valueOf(optional(area, "getName")))) return area;
        }
        throw new IllegalArgumentException("Gebiet nicht gefunden");
    }

    Object role(Object area, String id) throws Exception {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Rolle fehlt");
        Object roles = call(area, "getRoles");
        if (roles instanceof Collection<?> all) {
            for (Object role : all) if (id.equals(String.valueOf(optional(role, "getULID")))) return role;
            for (Object role : all) if (id.equalsIgnoreCase(String.valueOf(optional(role, "getName")))) return role;
        }
        Object visitor = optional(area, "getVisitorRole");
        if (visitor != null && (id.equals(String.valueOf(optional(visitor, "getULID"))) || id.equalsIgnoreCase("visitor"))) return visitor;
        throw new IllegalArgumentException("Rolle nicht gefunden");
    }

    Object roleFlag(String name) throws Exception {
        Object registry = call(api(), "getFlagRegistry");
        Object flag = optional(registry, "getRole", name);
        if (flag == null) throw new IllegalArgumentException("Unbekanntes Rollenrecht: " + name);
        return flag;
    }

    Object naturalFlag(String name) throws Exception {
        Object registry = call(api(), "getFlagRegistry");
        Object flag = optional(registry, "getNatural", name);
        if (flag == null) throw new IllegalArgumentException("Unbekannte Natur-Regel: " + name);
        return flag;
    }

    // ------------------------------------------------------------------------------------------------ text helpers

    static String clean(Object raw) {
        if (raw == null) return "";
        String text = String.valueOf(raw).replaceAll("<[^>]{1,40}>", "").replaceAll("(?i)[§&][0-9a-fk-orx]", "").replaceAll("(?i)[§&]#[0-9a-f]{6}", "");
        return text.trim();
    }

    String name(UUID uuid) {
        if (uuid == null) return "";
        try {
            var profile = plugin.expansionProfile(uuid);
            String known = profile == null ? null : profile.displayName();
            if (known != null && !known.isBlank()) return known;
        } catch (RuntimeException ignored) { }
        OfflinePlayer p = Bukkit.getOfflinePlayer(uuid);
        return p.getName() == null ? uuid.toString().substring(0, 8) : p.getName();
    }

    private static String id(Object holder) {
        Object ulid = optional(holder, "getULID");
        if (ulid == null) ulid = optional(holder, "getId");
        return ulid == null ? "" : String.valueOf(ulid);
    }

    private static int number(Object value) { return value instanceof Number n ? n.intValue() : 0; }

    private static int limit(Object value) {
        int n = number(value);
        return n <= 0 || n >= Integer.MAX_VALUE - 1 ? -1 : n;
    }

    private static List<UUID> uuids(Object raw) {
        List<UUID> out = new ArrayList<>();
        if (raw instanceof Collection<?> c) for (Object o : c) if (o instanceof UUID u) out.add(u);
        return out;
    }

    private static Map<String, Object> position(Object location) {
        if (location instanceof Location l && l.getWorld() != null)
            return SimplexAdminSuite.appMap("world", l.getWorld().getName(), "x", l.getBlockX(), "y", l.getBlockY(), "z", l.getBlockZ());
        if (location != null) {
            Object world = optional(location, "getWorldName");
            if (world == null) world = optional(location, "getWorld");
            Object x = optional(location, "getBlockX"), y = optional(location, "getBlockY"), z = optional(location, "getBlockZ");
            if (x == null) { x = optional(location, "getX"); y = optional(location, "getY"); z = optional(location, "getZ"); }
            if (x instanceof Number) {
                String w = world instanceof org.bukkit.World bw ? bw.getName() : world == null ? "" : String.valueOf(world);
                return SimplexAdminSuite.appMap("world", w, "x", number(x), "y", number(y), "z", number(z));
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------------ summaries

    /** One land as a compact row (list views, player profile). Main thread. */
    Map<String, Object> summary(Object land) {
        UUID owner = optional(land, "getOwnerUID") instanceof UUID u ? u : null;
        List<UUID> trusted = uuids(optional(land, "getTrustedPlayers"));
        Object level = optional(land, "getLevel");
        Object nation = optional(land, "getNation");
        Object online = optional(land, "getOnlinePlayers");
        Object areas = optional(land, "getAllAreas");
        Object created = optional(land, "getCreationTime");
        Object type = optional(land, "getLandType");
        Object balance = optional(land, "getBalance");
        Object war = optional(land, "getWar");
        Object chunks = optional(land, "getChunksAmount");
        if (!(chunks instanceof Number)) chunks = optional(land, "getSize");
        String name = clean(optional(land, "getName"));
        return SimplexAdminSuite.appMap(
            "id", id(land), "name", name, "display", clean(optional(land, "getColorName")).isBlank() ? name : clean(optional(land, "getColorName")),
            "owner", owner == null ? "" : owner.toString(), "ownerName", owner == null ? "" : name(owner),
            "members", trusted.size(), "chunks", number(chunks), "maxChunks", limit(optional(land, "getMaxChunks")), "maxMembers", limit(optional(land, "getMaxMembers")),
            "balance", balance instanceof Number b ? b.doubleValue() : 0D, "level", level == null ? "" : clean(optional(level, "getName")),
            "levelPosition", level == null ? 0 : number(optional(level, "getPosition")),
            "nation", nation == null ? "" : clean(optional(nation, "getName")), "online", online instanceof Collection<?> c ? c.size() : 0,
            "areas", areas instanceof Collection<?> a ? Math.max(0, a.size() - 1) : 0, "created", created instanceof Number n ? n.longValue() : 0L,
            "type", type == null ? "LAND" : String.valueOf(type), "inWar", war != null, "tag", clean(optional(land, "getTag")));
    }

    /** Dashboard: every land with totals and top lists. Main thread. */
    Map<String, Object> dashboard(String query, String sort) {
        Plugin lands = lands();
        if (lands == null) return SimplexAdminSuite.appMap("installed", false, "lands", List.of(), "stats", Map.of(), "feature", FEATURE);
        List<Map<String, Object>> rows = new ArrayList<>();
        String error = "";
        int chunks = 0, memberships = 0, online = 0, areas = 0; double balance = 0; Set<String> owners = new HashSet<>(), nations = new TreeSet<>();
        Set<UUID> players = new HashSet<>();
        try {
            String q = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
            for (Object land : allLands()) {
                Map<String, Object> row = summary(land);
                chunks += number(row.get("chunks")); memberships += number(row.get("members")); online += number(row.get("online")); areas += number(row.get("areas"));
                balance += row.get("balance") instanceof Number b ? b.doubleValue() : 0;
                if (!String.valueOf(row.get("owner")).isBlank()) owners.add(String.valueOf(row.get("owner")));
                if (!String.valueOf(row.get("nation")).isBlank()) nations.add(String.valueOf(row.get("nation")));
                players.addAll(uuids(optional(land, "getTrustedPlayers")));
                if (!q.isEmpty() && !(row.get("name") + " " + row.get("ownerName") + " " + row.get("nation")).toLowerCase(Locale.ROOT).contains(q)) continue;
                rows.add(row);
            }
        } catch (Throwable e) { error = e instanceof IllegalArgumentException ? e.getMessage() : "Lands-Daten nicht lesbar: " + e.getClass().getSimpleName(); }
        Comparator<Map<String, Object>> order = switch (sort == null ? "" : sort) {
            case "members" -> Comparator.comparingInt((Map<String, Object> r) -> -number(r.get("members")));
            case "online" -> Comparator.comparingInt((Map<String, Object> r) -> -number(r.get("online")));
            case "balance" -> Comparator.comparingDouble((Map<String, Object> r) -> -((Number) r.get("balance")).doubleValue());
            case "newest" -> Comparator.comparingLong((Map<String, Object> r) -> -((Number) r.get("created")).longValue());
            case "name" -> Comparator.comparing((Map<String, Object> r) -> String.valueOf(r.get("name")).toLowerCase(Locale.ROOT));
            default -> Comparator.comparingInt((Map<String, Object> r) -> -number(r.get("chunks")));
        };
        rows.sort(order.thenComparing(r -> String.valueOf(r.get("name")).toLowerCase(Locale.ROOT)));
        int total = rows.size();
        if (rows.size() > 500) rows = new ArrayList<>(rows.subList(0, 500));
        Map<String, Object> stats = SimplexAdminSuite.appMap("lands", total, "chunks", chunks, "memberships", memberships, "players", players.size(), "owners", owners.size(),
            "online", online, "areas", areas, "balance", balance, "nations", nations.size());
        return SimplexAdminSuite.appMap("installed", true, "version", lands.getDescription().getVersion(), "feature", FEATURE, "lands", rows, "total", total,
            "stats", stats, "nations", new ArrayList<>(nations), "error", error);
    }

    /** Full detail of one land: members per area, roles with rights, natural flags, areas, relations, inbox. Main thread. */
    Map<String, Object> detail(String landId) throws Exception {
        Object land = land(landId);
        Map<String, Object> out = new LinkedHashMap<>(summary(land));
        UUID owner = optional(land, "getOwnerUID") instanceof UUID u ? u : null;
        Object defaultArea = call(land, "getDefaultArea");
        out.put("title", clean(optional(land, "getTitleMessage", (Object) null)));
        out.put("spawn", position(optional(land, "getSpawn")));
        out.put("upkeep", optional(land, "getUpkeepCosts") instanceof Number n ? n.doubleValue() : 0D);
        out.put("tax", optional(defaultArea, "getTax") instanceof Number n ? n.doubleValue() : 0D);
        out.put("balanceEditable", has(land, "setBalance", 1));
        out.put("category", clean(optional(optional(land, "getCategory"), "getName")));
        Object stats = optional(land, "getStats");
        if (stats != null) out.put("war", SimplexAdminSuite.appMap("wins", number(optional(stats, "getWon")), "defeats", number(optional(stats, "getLost")),
            "kills", number(optional(stats, "getKills")), "deaths", number(optional(stats, "getDeaths"))));
        out.put("allies", holders(optional(land, "getAllies")));
        out.put("enemies", holders(optional(land, "getEnemies")));
        out.put("onlinePlayers", onlineNames(optional(land, "getOnlinePlayers")));
        // members of the whole land
        out.put("members", members(land, defaultArea, owner));
        out.put("roles", roles(defaultArea));
        out.put("natural", naturalFlags(defaultArea));
        out.put("flags", catalog());
        List<Map<String, Object>> areaRows = new ArrayList<>();
        Object all = optional(land, "getAllAreas");
        if (all instanceof Collection<?> areas) for (Object area : areas) areaRows.add(areaSummary(area, owner));
        areaRows.sort(Comparator.comparing((Map<String, Object> r) -> !Boolean.TRUE.equals(r.get("default"))).thenComparing(r -> String.valueOf(r.get("name")).toLowerCase(Locale.ROOT)));
        out.put("areaList", areaRows);
        out.put("inbox", inbox(land));
        out.put("bans", bans(defaultArea));
        out.put("areaDeleteSupported", all instanceof Collection<?> areas && areas.stream().anyMatch(a -> has(a, "delete", 0) || has(a, "delete", 1)));
        return out;
    }

    /** Detail of one sub-area (members, roles, natural flags). Main thread. */
    Map<String, Object> areaDetail(String landId, String areaId) throws Exception {
        Object land = land(landId);
        Object area = area(land, areaId);
        UUID owner = optional(land, "getOwnerUID") instanceof UUID u ? u : null;
        Map<String, Object> out = new LinkedHashMap<>(areaSummary(area, owner));
        out.put("land", id(land)); out.put("landName", clean(optional(land, "getName")));
        out.put("members", members(land, area, owner));
        out.put("roles", roles(area));
        out.put("natural", naturalFlags(area));
        out.put("flags", catalog());
        out.put("bans", bans(area));
        return out;
    }

    private List<String> holders(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof Collection<?> c) for (Object h : c) out.add(clean(optional(h, "getName")));
        Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    private static List<String> onlineNames(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof Collection<?> c) for (Object o : c) if (o instanceof Player p) out.add(p.getName());
        return out;
    }

    private List<Map<String, Object>> members(Object land, Object area, UUID owner) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (UUID uuid : uuids(optional(area, "getTrustedPlayers"))) {
            Object role = optional(area, "getRole", uuid);
            Object trusted = optional(land, "getTrustedPlayer", uuid);
            Object whole = optional(trusted, "isTrustedWholeLand");
            rows.add(SimplexAdminSuite.appMap("uuid", uuid.toString(), "name", name(uuid), "owner", uuid.equals(owner),
                "role", role == null ? "" : clean(optional(role, "getName")), "roleId", role == null ? "" : id(role),
                "online", Bukkit.getPlayer(uuid) != null, "wholeLand", !Boolean.FALSE.equals(whole),
                "areas", trusted == null ? 0 : number(optional(trusted, "getTrustedSize"))));
        }
        rows.sort(Comparator.comparing((Map<String, Object> r) -> !Boolean.TRUE.equals(r.get("owner")))
            .thenComparing(r -> !Boolean.TRUE.equals(r.get("online")))
            .thenComparing(r -> String.valueOf(r.get("name")).toLowerCase(Locale.ROOT)));
        return rows;
    }

    private List<Map<String, Object>> roles(Object area) {
        List<Map<String, Object>> rows = new ArrayList<>();
        Set<Object> seen = new LinkedHashSet<>();
        Object raw = optional(area, "getRoles");
        if (raw instanceof Collection<?> c) seen.addAll(c);
        Object visitor = optional(area, "getVisitorRole");
        if (visitor != null) seen.add(visitor);
        Map<String, Integer> counts = new HashMap<>();
        for (UUID uuid : uuids(optional(area, "getTrustedPlayers"))) {
            Object role = optional(area, "getRole", uuid);
            if (role != null) counts.merge(id(role), 1, Integer::sum);
        }
        for (Object role : seen) {
            Object type = optional(role, "getType");
            List<String> flags = new ArrayList<>();
            for (String getter : List.of("getActionFlags", "getManagementFlags")) {
                Object set = optional(role, getter);
                if (set instanceof Collection<?> fs) for (Object f : fs) flags.add(String.valueOf(optional(f, "getName")));
            }
            Collections.sort(flags);
            String typeName = type == null ? "NORMAL" : String.valueOf(type);
            rows.add(SimplexAdminSuite.appMap("id", id(role), "name", clean(optional(role, "getName")), "priority", number(optional(role, "getPriority")),
                "type", typeName, "visitor", Boolean.TRUE.equals(optional(role, "isVisitorRole")) || typeName.equals("VISITOR"),
                "owner", typeName.equals("OWNER"), "deletable", Boolean.TRUE.equals(optional(role, "isDeleteable")) && !typeName.equals("OWNER"),
                "assignable", !typeName.equals("OWNER") && !typeName.equals("VISITOR") && !typeName.equals("ALLY") && !typeName.equals("NATION")
                    && !Boolean.TRUE.equals(optional(role, "isVisitorRole")),
                "members", counts.getOrDefault(id(role), 0), "flags", flags));
        }
        rows.sort(Comparator.comparingInt((Map<String, Object> r) -> -number(r.get("priority"))));
        return rows;
    }

    private List<String> naturalFlags(Object area) {
        List<String> on = new ArrayList<>();
        Object set = optional(area, "getNaturalFlags");
        if (set instanceof Collection<?> c) for (Object f : c) on.add(String.valueOf(optional(f, "getName")));
        else {
            try {
                Object registry = call(api(), "getFlagRegistry");
                if (call(registry, "getNaturalFlags") instanceof Collection<?> all)
                    for (Object f : all) if (Boolean.TRUE.equals(optional(area, "hasNaturalFlag", f))) on.add(String.valueOf(optional(f, "getName")));
            } catch (Throwable ignored) { }
        }
        Collections.sort(on);
        return on;
    }

    /** All role and natural flags known to Lands (names, readable titles, category). Main thread. */
    Map<String, Object> catalog() {
        List<Map<String, Object>> role = new ArrayList<>(), natural = new ArrayList<>();
        try {
            Object registry = call(api(), "getFlagRegistry");
            if (call(registry, "getRoleFlags") instanceof Collection<?> all) for (Object f : all) {
                if (Boolean.FALSE.equals(optional(f, "isDisplay"))) continue;
                role.add(flagRow(f, String.valueOf(optional(f, "getCategory"))));
            }
            if (call(registry, "getNaturalFlags") instanceof Collection<?> all) for (Object f : all) {
                if (Boolean.FALSE.equals(optional(f, "isDisplay"))) continue;
                natural.add(flagRow(f, "NATURAL"));
            }
        } catch (Throwable ignored) { }
        Comparator<Map<String, Object>> byTitle = Comparator.comparing((Map<String, Object> r) -> String.valueOf(r.get("category"))).thenComparing(r -> String.valueOf(r.get("title")).toLowerCase(Locale.ROOT));
        role.sort(byTitle); natural.sort(byTitle);
        return SimplexAdminSuite.appMap("role", role, "natural", natural);
    }

    private static Map<String, Object> flagRow(Object flag, String category) {
        String name = String.valueOf(optional(flag, "getName"));
        String title = clean(optional(flag, "getDisplayName"));
        Object description = optional(flag, "getDescription");
        String text = description instanceof Collection<?> lines ? clean(String.join(" ", lines.stream().map(String::valueOf).toList())) : "";
        return SimplexAdminSuite.appMap("name", name, "title", title.isBlank() ? LandsFlagTitles.title(name) : title, "category", category == null || category.equals("null") ? "ACTION" : category,
            "description", text.length() > 200 ? text.substring(0, 200) + "…" : text);
    }

    private Map<String, Object> areaSummary(Object area, UUID owner) {
        boolean isDefault = Boolean.TRUE.equals(optional(area, "isDefault"));
        Object world = optional(area, "getWorld");
        Object box = optional(area, "getBoundingBox");
        Map<String, Object> bounds = null;
        if (box instanceof org.bukkit.util.BoundingBox b) bounds = SimplexAdminSuite.appMap("minX", (int) b.getMinX(), "minY", (int) b.getMinY(), "minZ", (int) b.getMinZ(),
            "maxX", (int) b.getMaxX(), "maxY", (int) b.getMaxY(), "maxZ", (int) b.getMaxZ());
        Object tenant = optional(area, "getTenant");
        Object rental = optional(area, "getRentalOffer");
        return SimplexAdminSuite.appMap("id", id(area), "name", isDefault ? "Hauptgebiet" : clean(optional(area, "getName")), "default", isDefault,
            "world", world instanceof org.bukkit.World w ? w.getName() : "", "bounds", bounds,
            "members", uuids(optional(area, "getTrustedPlayers")).size(), "tax", optional(area, "getTax") instanceof Number n ? n.doubleValue() : 0D,
            "tenant", tenant instanceof UUID t ? name(t) : "", "rental", rental == null ? "" : clean(optional(rental, "getType")),
            "spawn", position(optional(area, "getSpawn")), "natural", naturalFlags(area).size());
    }

    private List<Map<String, Object>> inbox(Object land) {
        List<Map<String, Object>> out = new ArrayList<>();
        Object raw = optional(land, "getInbox");
        if (raw instanceof Collection<?> c) for (Object message : c) {
            Object time = optional(message, "getTime");
            Object text = optional(message, "getText");
            out.add(SimplexAdminSuite.appMap("time", time instanceof Number n ? n.longValue() : 0L, "text", clean(text),
                "category", String.valueOf(optional(message, "getCategory"))));
        }
        out.sort(Comparator.comparingLong((Map<String, Object> r) -> -((Number) r.get("time")).longValue()));
        return out.size() > 40 ? new ArrayList<>(out.subList(0, 40)) : out;
    }

    /** Banned players among the suite's known profiles (Lands has no ban list in its API). Main thread. */
    private List<Map<String, Object>> bans(Object area) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!has(area, "isBanned", 1)) return out;
        try {
            int checked = 0;
            for (var profile : plugin.expansionProfiles()) {
                if (++checked > 20000) break;
                if (Boolean.TRUE.equals(optional(area, "isBanned", profile.uniqueId()))) out.add(SimplexAdminSuite.appMap("uuid", profile.uniqueId().toString(), "name", profile.displayName()));
            }
        } catch (RuntimeException ignored) { }
        return out;
    }

    /** Lands of one player: owned, member (with role) and banned. Main thread. */
    Map<String, Object> player(UUID player) {
        if (lands() == null) return SimplexAdminSuite.appMap("installed", false, "player", player.toString(), "owned", List.of(), "member", List.of(), "banned", List.of());
        List<Map<String, Object>> owned = new ArrayList<>(), member = new ArrayList<>(), banned = new ArrayList<>();
        String error = "";
        try {
            for (Object land : allLands()) {
                UUID owner = optional(land, "getOwnerUID") instanceof UUID u ? u : null;
                Object defaultArea = optional(land, "getDefaultArea");
                List<UUID> trusted = uuids(optional(land, "getTrustedPlayers"));
                List<String> areaNames = new ArrayList<>();
                Object all = optional(land, "getAllAreas");
                if (all instanceof Collection<?> areas) for (Object area : areas) {
                    if (Boolean.TRUE.equals(optional(area, "isDefault"))) continue;
                    if (uuids(optional(area, "getTrustedPlayers")).contains(player)) areaNames.add(clean(optional(area, "getName")));
                }
                if (player.equals(owner)) { owned.add(summary(land)); continue; }
                if (trusted.contains(player) || !areaNames.isEmpty()) {
                    Map<String, Object> row = new LinkedHashMap<>(summary(land));
                    Object role = optional(defaultArea, "getRole", player);
                    row.put("role", role == null ? "" : clean(optional(role, "getName")));
                    row.put("roleId", role == null ? "" : id(role));
                    row.put("wholeLand", trusted.contains(player));
                    row.put("subAreas", areaNames);
                    member.add(row);
                } else if (Boolean.TRUE.equals(optional(defaultArea, "isBanned", player))) banned.add(summary(land));
            }
        } catch (Throwable e) { error = e instanceof IllegalArgumentException ? e.getMessage() : "Lands-Daten nicht lesbar: " + e.getClass().getSimpleName(); }
        Comparator<Map<String, Object>> byName = Comparator.comparing(r -> String.valueOf(r.get("name")).toLowerCase(Locale.ROOT));
        owned.sort(byName); member.sort(byName); banned.sort(byName);
        Object editLand = null;
        try {
            Object lp = call(api(), "getLandPlayer", player);
            Object edit = lp == null ? null : optional(lp, "getEditLand", false);
            editLand = edit == null ? null : clean(optional(edit, "getName"));
        } catch (Throwable ignored) { }
        return SimplexAdminSuite.appMap("installed", true, "player", player.toString(), "name", name(player), "owned", owned, "member", member, "banned", banned,
            "editLand", editLand == null ? "" : editLand, "error", error);
    }

    // ------------------------------------------------------------------------------------------------ actions

    /** Applies one management operation. Main thread. Returns {ok, message, …}. */
    Map<String, Object> apply(String actor, Map<String, Object> body, boolean mayDelete) throws Exception {
        String operation = text(body, "operation");
        Object land = land(text(body, "land"));
        String landName = clean(optional(land, "getName"));
        UUID owner = optional(land, "getOwnerUID") instanceof UUID u ? u : null;
        String areaId = text(body, "area");
        Object area = area(land, areaId);
        boolean subArea = !Boolean.TRUE.equals(optional(area, "isDefault"));
        String where = subArea ? landName + " / " + clean(optional(area, "getName")) : landName;
        String message;
        UUID target = null;
        switch (operation) {
            case "trust" -> {
                target = player(body);
                if (subArea) call(area, "trustPlayer", target); else call(land, "trustPlayer", target);
                String roleId = text(body, "role");
                if (!roleId.isBlank()) setRole(area, target, roleId, owner);
                message = name(target) + " wurde zu " + where + " hinzugefügt.";
            }
            case "untrust" -> {
                target = player(body);
                if (target.equals(owner)) throw new IllegalArgumentException("Der Besitzer kann nicht entfernt werden – zuerst den Besitz übertragen.");
                Object done = subArea ? call(area, "untrustPlayer", target) : call(land, "untrustPlayer", target);
                if (Boolean.FALSE.equals(done)) throw new IllegalArgumentException(name(target) + " ist dort kein Mitglied.");
                message = name(target) + " wurde aus " + where + " entfernt.";
            }
            case "remove-everywhere" -> {
                target = player(body);
                requireConfirmed(body);
                int removed = 0;
                for (Object other : allLands()) {
                    if (target.equals(optional(other, "getOwnerUID"))) continue;
                    if (uuids(optional(other, "getTrustedPlayers")).contains(target) && !Boolean.FALSE.equals(call(other, "untrustPlayer", target))) removed++;
                }
                message = name(target) + " wurde aus " + removed + " Ländern entfernt (eigene Länder bleiben).";
                where = "alle Länder";
            }
            case "set-role" -> {
                target = player(body);
                setRole(area, target, text(body, "role"), owner);
                message = name(target) + " hat in " + where + " jetzt die Rolle „" + clean(optional(role(area, text(body, "role")), "getName")) + "“.";
            }
            case "ban" -> {
                target = player(body);
                if (target.equals(owner)) throw new IllegalArgumentException("Der Besitzer kann nicht gebannt werden.");
                if (subArea) call(area, "banPlayer", target); else call(land, "banPlayer", target);
                message = name(target) + " ist in " + where + " gebannt.";
            }
            case "unban" -> {
                target = player(body);
                if (subArea) call(area, "unbanPlayer", target); else call(land, "unbanPlayer", target);
                message = name(target) + " ist in " + where + " entbannt.";
            }
            case "set-owner" -> {
                target = player(body);
                requireConfirmed(body);
                if (!uuids(optional(land, "getTrustedPlayers")).contains(target)) call(land, "trustPlayer", target);
                call(land, "setOwner", target);
                message = name(target) + " ist jetzt Besitzer von " + landName + ".";
            }
            case "rename" -> {
                String name = text(body, "name");
                if (name.isBlank() || name.length() > 48) throw new IllegalArgumentException("Name fehlt oder ist zu lang.");
                if (subArea) {
                    if (Boolean.FALSE.equals(call(area, "setName", name))) throw new IllegalArgumentException("Lands hat den Gebietsnamen abgelehnt.");
                    message = "Gebiet heißt jetzt „" + name + "“.";
                } else {
                    Object ok = typed(land, "setName", new String[]{"LandPlayer", "String"}, null, name);
                    if (Boolean.FALSE.equals(ok)) throw new IllegalArgumentException("Lands hat den Namen abgelehnt (vergeben oder ungültig).");
                    message = landName + " heißt jetzt „" + name + "“.";
                }
            }
            case "title" -> {
                String title = text(body, "title");
                if (title.length() > 256) throw new IllegalArgumentException("Begrüßung ist zu lang (max. 256 Zeichen).");
                call(land, "setTitleMessage", title.isBlank() ? null : title);
                message = "Begrüßungstext von " + landName + " gespeichert.";
            }
            case "role-flag" -> {
                Object role = role(area, text(body, "role"));
                Object flag = roleFlag(text(body, "flag"));
                boolean wanted = bool(body, "value");
                boolean current = Boolean.TRUE.equals(call(role, "hasFlag", flag));
                if (current != wanted) call(role, "toggleFlag", flag);
                message = "Recht „" + LandsFlagTitles.title(text(body, "flag")) + "“ für „" + clean(optional(role, "getName")) + "“ " + (wanted ? "erlaubt" : "verboten") + ".";
            }
            case "role-flags" -> {
                Object role = role(area, text(body, "role"));
                if (!(body.get("flags") instanceof Map<?, ?> changes)) throw new IllegalArgumentException("Rechte fehlen");
                int changed = 0;
                for (var entry : changes.entrySet()) {
                    Object flag = roleFlag(String.valueOf(entry.getKey()));
                    boolean wanted = Boolean.TRUE.equals(entry.getValue()) || "true".equals(String.valueOf(entry.getValue()));
                    if (Boolean.TRUE.equals(call(role, "hasFlag", flag)) != wanted) { call(role, "toggleFlag", flag); changed++; }
                }
                message = changed + " Rechte für „" + clean(optional(role, "getName")) + "“ geändert.";
            }
            case "natural-flag" -> {
                Object flag = naturalFlag(text(body, "flag"));
                boolean wanted = bool(body, "value");
                boolean current = Boolean.TRUE.equals(call(area, "hasNaturalFlag", flag));
                if (current != wanted) call(area, "toggleNaturalFlag", flag);
                message = "Natur-Regel „" + LandsFlagTitles.title(text(body, "flag")) + "“ in " + where + " " + (wanted ? "an" : "aus") + ".";
            }
            case "role-rename" -> {
                Object role = role(area, text(body, "role"));
                String name = text(body, "name");
                if (name.isBlank() || name.length() > 32) throw new IllegalArgumentException("Rollenname fehlt oder ist zu lang.");
                if ("OWNER".equals(String.valueOf(optional(role, "getType")))) throw new IllegalArgumentException("Die Besitzer-Rolle wird nicht umbenannt.");
                for (Object other : (Collection<?>) call(area, "getRoles"))
                    if (other != role && name.equalsIgnoreCase(clean(optional(other, "getName")))) throw new IllegalArgumentException("Eine Rolle mit diesem Namen gibt es schon.");
                call(role, "setName", name);
                message = "Rolle heißt jetzt „" + name + "“.";
            }
            case "role-priority" -> {
                Object role = role(area, text(body, "role"));
                boolean up = text(body, "direction").equals("up");
                Object other = typed(role, up ? "getHigherPriorityRole" : "getLowerPriorityRole", new String[]{"boolean", "boolean"}, false, true);
                if (other == null || "OWNER".equals(String.valueOf(optional(other, "getType")))) throw new IllegalArgumentException("Weiter " + (up ? "nach oben" : "nach unten") + " geht es nicht.");
                call(role, "swapPriority", other);
                message = "Rang von „" + clean(optional(role, "getName")) + "“ " + (up ? "erhöht" : "gesenkt") + ".";
            }
            case "role-delete" -> {
                requireConfirmed(body);
                Object role = role(area, text(body, "role"));
                if (!Boolean.TRUE.equals(optional(role, "isDeleteable")) || "OWNER".equals(String.valueOf(optional(role, "getType"))))
                    throw new IllegalArgumentException("Diese Rolle kann nicht gelöscht werden.");
                call(role, "delete");
                message = "Rolle „" + clean(optional(role, "getName")) + "“ gelöscht – Mitglieder bekommen die Standardrolle.";
            }
            case "tax" -> {
                double tax = decimal(body, "tax");
                if (tax < 0 || tax > 1_000_000_000D) throw new IllegalArgumentException("Steuer außerhalb des erlaubten Bereichs.");
                call(area, "setTax", tax);
                message = "Steuer in " + where + " auf " + tax + " gesetzt.";
            }
            case "balance" -> {
                double amount = decimal(body, "amount");
                String mode = text(body, "mode");
                Object ok;
                if (mode.equals("add")) ok = call(land, "modifyBalance", amount);
                else { if (amount < 0) throw new IllegalArgumentException("Kontostand darf nicht negativ sein."); ok = call(land, "setBalance", amount); }
                if (Boolean.FALSE.equals(ok)) throw new IllegalArgumentException("Lands hat die Änderung abgelehnt (Bank deaktiviert oder zu wenig Guthaben).");
                message = "Landbank von " + landName + ": " + clean(optional(land, "getBalanceDisplay"));
            }
            case "area-delete" -> {
                requireConfirmed(body);
                if (!subArea) throw new IllegalArgumentException("Das Hauptgebiet gehört zum Land – zum Entfernen das Land löschen.");
                String areaName = clean(optional(area, "getName"));
                if (has(area, "delete", 0)) call(area, "delete");
                else if (has(area, "delete", 1)) call(area, "delete", (Object) null);
                else throw new IllegalArgumentException("Diese Lands-Version kann Gebiete nicht über die API löschen – bitte ingame über /lands.");
                message = "Gebiet „" + areaName + "“ gelöscht.";
            }
            case "delete" -> {
                if (!mayDelete) throw new AppApiManager.ApiError(403, "Länder löschen benötigt Vollzugriff und das Recht " + DELETE);
                requireConfirmed(body);
                if (!landName.equalsIgnoreCase(text(body, "confirmName"))) throw new IllegalArgumentException("Zur Bestätigung den Landnamen exakt eingeben.");
                try { typed(land, "delete", new String[]{"LandPlayer"}, (Object) null); }
                catch (NoSuchMethodException older) { call(land, "delete", (Object) null); }
                message = "Land „" + landName + "“ wird gelöscht.";
            }
            default -> throw new IllegalArgumentException("Unbekannte Land-Aktion: " + operation);
        }
        if (plugin.adminLog != null) {
            try { plugin.adminLog.record(actor, target, "lands." + operation, where + (target == null ? "" : " · " + name(target))); } catch (RuntimeException ignored) { }
        }
        plugin.getLogger().info("[Länder] " + actor + ": " + message);
        return SimplexAdminSuite.appMap("ok", true, "message", message, "operation", operation, "land", operation.equals("delete") ? "" : id(land));
    }

    private void setRole(Object area, UUID target, String roleId, UUID owner) throws Exception {
        Object role = role(area, roleId);
        String type = String.valueOf(optional(role, "getType"));
        if (type.equals("OWNER")) throw new IllegalArgumentException("Die Besitzer-Rolle gibt es nur über „Besitzer ändern“.");
        if (type.equals("VISITOR") || Boolean.TRUE.equals(optional(role, "isVisitorRole"))) throw new IllegalArgumentException("Die Besucher-Rolle kann man nicht vergeben – Spieler stattdessen entfernen.");
        if (target.equals(owner)) throw new IllegalArgumentException("Der Besitzer behält immer die Besitzer-Rolle.");
        if (!uuids(optional(area, "getTrustedPlayers")).contains(target)) throw new IllegalArgumentException(name(target) + " ist dort kein Mitglied – zuerst hinzufügen.");
        call(area, "setRole", target, role);
    }

    private UUID player(Map<String, Object> body) {
        String raw = text(body, "player");
        if (raw.isBlank()) throw new IllegalArgumentException("Spieler fehlt");
        try { return UUID.fromString(raw); } catch (IllegalArgumentException notUuid) { /* name */ }
        return plugin.accessLocks.resolve(raw);
    }

    private static void requireConfirmed(Map<String, Object> body) {
        if (!Boolean.TRUE.equals(body.get("confirmed"))) throw new IllegalArgumentException("Bestätigung fehlt");
    }

    private static String text(Map<String, Object> body, String key) { Object v = body.get(key); return v == null ? "" : String.valueOf(v).trim(); }
    private static boolean bool(Map<String, Object> body, String key) { Object v = body.get(key); return Boolean.TRUE.equals(v) || "true".equals(String.valueOf(v)); }
    private static double decimal(Map<String, Object> body, String key) {
        Object v = body.get(key);
        try { return v instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(v).replace(',', '.')); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Zahl erwartet: " + key); }
    }
}
