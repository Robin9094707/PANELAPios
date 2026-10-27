package eu.simplexsmp.adminsuite;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Local restrictions are evaluated before LuckPerms/wildcards. No username-only owner trust. */
final class AdminAccessManager {
    enum Role { INHERIT, FULL, MODERATOR, SUPPORT, READ_ONLY }
    private final SimplexAdminSuite plugin;
    private final Path file;
    private final Properties state = new Properties();
    private final Map<UUID, Long> revisions = new ConcurrentHashMap<>();
    private final SortedSet<String> nodes = new TreeSet<>();
    private volatile UUID owner;
    private final Map<UUID, org.bukkit.permissions.PermissionAttachment> attachments = new HashMap<>();
    AdminAccessManager(SimplexAdminSuite plugin) {
        this.plugin = plugin;
        file = plugin.getDataFolder().toPath().resolve("admin-access.properties");
        if (Files.exists(file)) try (Reader in = Files.newBufferedReader(file)) { state.load(in); }
        catch (IOException e) { throw new IllegalStateException("Adminrechte konnten nicht sicher geladen werden", e); }
        try { owner = UUID.fromString(state.getProperty("owner.uuid", "")); } catch (IllegalArgumentException ignored) { }
        try (InputStream in = plugin.getClass().getResourceAsStream("/admin-permissions.txt")) {
            if (in != null) new BufferedReader(new InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)).lines()
                    .filter(s -> s.startsWith("simplexadminsuite.")).forEach(nodes::add);
        } catch (IOException e) { throw new IllegalStateException(e); }
    }
    void join(Player p) {
        // Initial binding requires a real server OP; a spoofed offline-mode name alone cannot claim ownership.
        if (owner == null && p.isOp() && p.getName().equalsIgnoreCase("Robin9094707")) {
            owner = p.getUniqueId(); state.setProperty("owner.uuid", owner.toString()); save();
        }
        applyAttachment(p);
    }
    boolean master(CommandSender sender) {
        if(plugin.appApi!=null && plugin.appApi.remote(sender)) return true;
        if (sender == Bukkit.getConsoleSender() && sender != null) return true;
        return sender instanceof Player p && Bukkit.getPlayer(p.getUniqueId()) == p
                && (p.isOp() || p.getUniqueId().equals(owner));
    }
    synchronized Role role(UUID id) {
        try { return Role.valueOf(state.getProperty(id + ".role", "INHERIT")); }
        catch (IllegalArgumentException e) { return Role.READ_ONLY; }
    }
    synchronized Boolean override(UUID id, String node) {
        String v = state.getProperty(id + ".permission." + node);
        return v == null ? null : Boolean.valueOf(v);
    }
    boolean allows(CommandSender sender, String node, boolean inherited) {
        if (!(sender instanceof Player p) || !node.startsWith("simplexadminsuite.")) return inherited;
        if (master(p)) return true;
        if(node.equals("simplexadminsuite.discord.design")||node.equals("simplexadminsuite.discordstatus")||node.contains(".opendiscord"))return false;
        Boolean explicit = override(p.getUniqueId(), node);
        if (explicit != null) return explicit;
        Role role = role(p.getUniqueId());
        if (node.startsWith("simplexadminsuite.menu.")) {
            if (role != Role.READ_ONLY) return true;
            String menu = node.substring(node.lastIndexOf('.') + 1);
            return Set.of("openmain", "openadminmenu", "openstatus", "openonline", "openplayers", "openplayer", "openhistory", "opendiagnostics", "openprofile", "openstored").contains(menu);
        }
        if (node.startsWith("simplexadminsuite.action.")) {
            if (role == Role.INHERIT) return inherited;
            String action = node.substring(node.lastIndexOf('.') + 1);
            return role == Role.FULL || role == Role.MODERATOR && Set.of("kick", "mute", "tempmute", "unmute", "warn").contains(action);
        }
        if (role == Role.INHERIT) return inherited;
        if (!nodes.contains(node)) return inherited;
        // Public gameplay permissions remain owned by Bukkit/LuckPerms.
        if (PUBLIC.contains(node)) return inherited;
        return roleAllows(role, node);
    }
    static final Set<String> PUBLIC = Set.of("simplexadminsuite.shop.use", "simplexadminsuite.report", "simplexadminsuite.warps.use", "simplexadminsuite.rules.use", "simplexadminsuite.vote", "simplexadminsuite.points.use", "simplexadminsuite.ask", "simplexadminsuite.scoreboard.use", "simplexadminsuite.nickname.use", "simplexadminsuite.fart.use", "simplexadminsuite.burp.use", "simplexadminsuite.ai.ask", "simplexadminsuite.points", "simplexadminsuite.polls.vote");
    static boolean roleAllows(Role role, String node) {
        if (role == Role.FULL) return true;
        if (node.equals("simplexadminsuite.admin") || node.equals("simplexadminsuite.history")) return true;
        if (role == Role.READ_ONLY) return false;
        if (node.equals("simplexadminsuite.reports")) return true;
        if (Set.of("simplexadminsuite.notes", "simplexadminsuite.staffchat", "simplexadminsuite.watch").contains(node)) return true;
        return role == Role.MODERATOR && Set.of("simplexadminsuite.bans", "simplexadminsuite.mutes", "simplexadminsuite.warns", "simplexadminsuite.freeze", "simplexadminsuite.teleport", "simplexadminsuite.vanish").contains(node);
    }
    List<String> nodes() { return new ArrayList<>(nodes); }
    long revision(UUID id) { return revisions.getOrDefault(id, 0L); }
    synchronized void change(Player actor, UUID target, String node, Boolean value, Role role) {
        if (!master(actor)) throw new SecurityException("Nur echte Server-Operatoren und der gebundene Hauptadmin dürfen Rechte ändern.");
        changeAuthorized(actor.getName(),target,node,value,role);
    }
    synchronized void changeAuthorized(String actor,UUID target,String node,Boolean value,Role role) {
        Player online = Bukkit.getPlayer(target);
        if (target.equals(owner) || Bukkit.getOfflinePlayer(target).isOp() || online != null && master(online)) throw new IllegalArgumentException("Server-Operatoren und Hauptadmin können hier nicht eingeschränkt werden.");
        if (node != null && !nodes.contains(node)) throw new IllegalArgumentException("Unbekanntes Recht");
        Properties before = new Properties(); before.putAll(state);
        if (role != null) state.setProperty(target + ".role", role.name());
        if (node != null) {
            String key = target + ".permission." + node;
            if (value == null) state.remove(key); else state.setProperty(key, value.toString());
        }
        try { save(); } catch (RuntimeException error) { state.clear(); state.putAll(before); throw error; }
        revisions.merge(target, 1L, Long::sum);
        plugin.invalidateAdminSession(target);
        if (online != null) { applyAttachment(online); online.closeInventory(); }
        plugin.expansionAudit(actor, target, "Adminrechte geändert", role == null ? node + "=" + value : "Rolle=" + role);
    }
    private void applyAttachment(Player p) {
        org.bukkit.permissions.PermissionAttachment old = attachments.remove(p.getUniqueId());
        if (old != null) p.removeAttachment(old);
        if (role(p.getUniqueId()) == Role.INHERIT && state.stringPropertyNames().stream().noneMatch(k -> k.startsWith(p.getUniqueId() + ".permission.")) && !p.getUniqueId().equals(owner)) return;
        org.bukkit.permissions.PermissionAttachment attachment = p.addAttachment(plugin);
        if (attachment == null) return;
        attachments.put(p.getUniqueId(), attachment);
        for (String node : nodes) {
            if(node.startsWith("simplexadminsuite.menu.") || node.endsWith(".*")) continue;
            Boolean explicit = override(p.getUniqueId(), node);
            if (explicit != null) attachment.setPermission(node, explicit);
            else if (p.getUniqueId().equals(owner) && !PUBLIC.contains(node)) attachment.setPermission(node, true);
            else if (role(p.getUniqueId()) != Role.INHERIT && !PUBLIC.contains(node)) attachment.setPermission(node, allows(p,node,false));
        }
    }
    void quit(Player p) { org.bukkit.permissions.PermissionAttachment a=attachments.remove(p.getUniqueId()); if(a!=null)p.removeAttachment(a); }
    void stop() { for(Player p:Bukkit.getOnlinePlayers())quit(p); }

    private synchronized void save() {
        try { AtomicState.save(file, state, "SimplexAdminSuite 7.4 - UUID-bound administrative access"); }
        catch (IOException e) { throw new IllegalStateException("Adminrechte konnten nicht gespeichert werden", e); }
    }
}
