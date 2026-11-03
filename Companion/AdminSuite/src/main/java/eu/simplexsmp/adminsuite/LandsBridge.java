package eu.simplexsmp.adminsuite;

import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

/**
 * 9.9.0 optional integration for the Lands plugin (IncrediblePlugins/LandsAPI). Everything is read through the
 * public LandsIntegration API via reflection, so Lands stays an optional soft dependency and API changes only
 * hide single fields. Admin access is shown from the effective permissions lands.bypass.* and lands.admin.*;
 * granting or removing it is a LuckPerms change (see {@link LuckPermsAdmin}).
 */
final class LandsBridge {
    static final List<String> ADMIN_NODES = List.of("lands.bypass.*", "lands.admin.*", "lands.admin");
    private final SimplexAdminSuite plugin;
    private Object integration;

    LandsBridge(SimplexAdminSuite plugin) { this.plugin = plugin; }

    Plugin lands() { Plugin p = Bukkit.getPluginManager().getPlugin("Lands"); return p != null && p.isEnabled() ? p : null; }

    /** LandsIntegration.of(plugin) (Lands 6.28+/7) with fallback to the older constructor. Main thread. */
    private synchronized Object integration() throws Exception {
        if (integration != null) return integration;
        Plugin lands = lands();
        if (lands == null) throw new IllegalArgumentException("Lands ist nicht installiert.");
        ClassLoader loader = lands.getClass().getClassLoader();
        for (String name : List.of("me.angeschossen.lands.api.LandsIntegration", "me.angeschossen.lands.api.integration.LandsIntegration")) {
            try {
                Class<?> type = Class.forName(name, true, loader);
                try { integration = type.getMethod("of", org.bukkit.plugin.Plugin.class).invoke(null, plugin); }
                catch (NoSuchMethodException old) { integration = type.getConstructor(org.bukkit.plugin.Plugin.class).newInstance(plugin); }
                if (integration != null) return integration;
            } catch (ClassNotFoundException next) { /* try the other package */ }
        }
        throw new IllegalArgumentException("Lands-API wurde nicht gefunden (Lands-Version prüfen).");
    }

    // ---------------------------------------------------------------- 10.0.0: fast standing check for access locks (main thread)

    private final Map<String, java.lang.reflect.Method> methods = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile long retryAt;

    private Object invoke(Object target, String name, Object... args) throws Exception {
        if (target == null) return null;
        String key = target.getClass().getName() + "#" + name + "/" + args.length;
        java.lang.reflect.Method m = methods.get(key);
        if (m == null) {
            for (java.lang.reflect.Method candidate : target.getClass().getMethods()) {
                if (!candidate.getName().equals(name) || candidate.getParameterCount() != args.length) continue;
                Class<?>[] types = candidate.getParameterTypes(); boolean ok = true;
                for (int i = 0; i < types.length; i++) { Class<?> t = types[i] == int.class ? Integer.class : types[i]; if (args[i] != null && !t.isAssignableFrom(args[i].getClass())) { ok = false; break; } }
                if (ok) { m = candidate; break; }
            }
            if (m == null) throw new NoSuchMethodException(key);
            m.trySetAccessible();
            methods.put(key, m);
        }
        return m.invoke(target, args);
    }

    /**
     * Where a location lies for a player: wilderness (no claim, or Lands missing), own land (player is the owner),
     * a land the player is trusted in, or a foreign land. Errors count as foreign so locks fail closed.
     */
    AccessLocks.Standing standing(org.bukkit.Location location, UUID player) {
        if (location == null || location.getWorld() == null) return AccessLocks.Standing.FOREIGN;
        if (lands() == null) return AccessLocks.Standing.WILD;
        if (System.currentTimeMillis() < retryAt) return AccessLocks.Standing.FOREIGN;
        try {
            Object api = integration();
            Object area, land;
            try { area = invoke(api, "getArea", location); land = area == null ? null : invoke(area, "getLand"); }
            catch (NoSuchMethodException older) { area = null; land = invoke(api, "getLandByChunk", location.getWorld(), location.getBlockX() >> 4, location.getBlockZ() >> 4); }
            if (land == null) return AccessLocks.Standing.WILD;
            Object owner = invoke(land, "getOwnerUID");
            if (player.equals(owner)) return AccessLocks.Standing.OWNER;
            Object trusted;
            try { trusted = invoke(area != null ? area : land, "isTrusted", player); } catch (NoSuchMethodException old) { trusted = invoke(land, "isTrusted", player); }
            return Boolean.TRUE.equals(trusted) ? AccessLocks.Standing.TRUSTED : AccessLocks.Standing.FOREIGN;
        } catch (Throwable broken) {
            retryAt = System.currentTimeMillis() + 30_000L;
            plugin.getLogger().warning("Lands-Abfrage für Rechte-Sperren fehlgeschlagen (" + broken.getClass().getSimpleName() + "); Sperren gelten 30 s überall.");
            return AccessLocks.Standing.FOREIGN;
        }
    }

    /** Lands with owner, members, size, balance and level; staff with their Lands admin rights. Main thread. */
    Map<String, Object> overview(String query, int limit) {
        Plugin lands = lands();
        if (lands == null) return SimplexAdminSuite.appMap("installed", false, "lands", List.of(), "admins", List.of());
        List<Map<String, Object>> rows = new ArrayList<>(); String error = "";
        int total = 0;
        try {
            Collection<?> all = (Collection<?>) LuckPermsAdmin.call(integration(), "getLands");
            String q = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
            for (Object land : all) {
                Map<String, Object> row = land(land);
                if (!q.isEmpty() && !(String.valueOf(row.get("name")) + " " + row.get("ownerName")).toLowerCase(Locale.ROOT).contains(q)) continue;
                total++;
                if (rows.size() < limit) rows.add(row);
            }
            rows.sort(Comparator.comparingInt((Map<String, Object> r) -> -((Number) r.get("chunks")).intValue()).thenComparing(r -> String.valueOf(r.get("name")).toLowerCase(Locale.ROOT)));
        } catch (Throwable e) { error = e instanceof IllegalArgumentException ? e.getMessage() : "Lands-Daten nicht lesbar: " + e.getClass().getSimpleName(); }
        return SimplexAdminSuite.appMap("installed", true, "version", lands.getDescription().getVersion(), "lands", rows, "total", total, "error", error, "nodes", ADMIN_NODES);
    }

    /** Lands of one player (owner or trusted member). Main thread. */
    List<Map<String, Object>> playerLands(UUID player) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (lands() == null) return out;
        try {
            for (Object land : (Collection<?>) LuckPermsAdmin.call(integration(), "getLands")) {
                Object owner = LuckPermsAdmin.optional(land, "getOwnerUID");
                Object trusted = LuckPermsAdmin.optional(land, "getTrustedPlayers");
                if (player.equals(owner) || trusted instanceof Collection<?> c && c.contains(player)) out.add(land(land));
            }
        } catch (Throwable ignored) { }
        return out;
    }

    private Map<String, Object> land(Object land) {
        Object owner = LuckPermsAdmin.optional(land, "getOwnerUID");
        Object trusted = LuckPermsAdmin.optional(land, "getTrustedPlayers");
        Object chunks = LuckPermsAdmin.optional(land, "getChunksAmount");
        if (!(chunks instanceof Number)) chunks = LuckPermsAdmin.optional(land, "getSize");
        Object balance = LuckPermsAdmin.optional(land, "getBalance");
        Object level = LuckPermsAdmin.optional(land, "getLevel");
        Object levelName = level == null ? null : LuckPermsAdmin.optional(level, "getName");
        Object id = LuckPermsAdmin.optional(land, "getULID");
        if (id == null) id = LuckPermsAdmin.optional(land, "getId");
        String ownerName = "";
        if (owner instanceof UUID uuid) { OfflinePlayer p = Bukkit.getOfflinePlayer(uuid); ownerName = p.getName() == null ? uuid.toString().substring(0, 8) : p.getName(); }
        Object name = LuckPermsAdmin.optional(land, "getName");
        Object colored = LuckPermsAdmin.optional(land, "getColorName");
        return SimplexAdminSuite.appMap("id", String.valueOf(id), "name", String.valueOf(name), "display", colored == null ? String.valueOf(name) : SimplexAdminSuite.stripFormatting(String.valueOf(colored)),
            "owner", owner == null ? "" : owner.toString(), "ownerName", ownerName, "members", trusted instanceof Collection<?> c ? c.size() : 0,
            "chunks", chunks instanceof Number n ? n.intValue() : 0, "balance", balance instanceof Number b ? b.doubleValue() : 0D, "level", levelName == null ? "" : String.valueOf(levelName));
    }

    /** Effective Lands admin rights for every recognised staff member (online via Bukkit, offline via LuckPerms). Worker thread. */
    List<Map<String, Object>> admins(Map<UUID, String> staff, LuckPermsAdmin luckPerms) {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Map<UUID, Boolean>> checks = new LinkedHashMap<>();
        for (String node : ADMIN_NODES) checks.put(node, luckPerms.check(staff.keySet(), node));
        for (var entry : staff.entrySet()) {
            boolean bypass = checks.get("lands.bypass.*").getOrDefault(entry.getKey(), false), admin = checks.get("lands.admin.*").getOrDefault(entry.getKey(), false) || checks.get("lands.admin").getOrDefault(entry.getKey(), false);
            out.add(SimplexAdminSuite.appMap("uuid", entry.getKey().toString(), "name", entry.getValue(), "bypass", bypass, "admin", admin, "online", Bukkit.getPlayer(entry.getKey()) != null));
        }
        out.sort(Comparator.comparing((Map<String, Object> r) -> !(Boolean.TRUE.equals(r.get("bypass")) || Boolean.TRUE.equals(r.get("admin")))).thenComparing(r -> String.valueOf(r.get("name")).toLowerCase(Locale.ROOT)));
        return out;
    }
}
