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
    enum Role { INHERIT, FULL, MODERATOR, SUPPORT, READ_ONLY, JUNIOR_ADMIN, TEMP_MODERATOR, CUSTOM, NONE }
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
        if(!"3".equals(state.getProperty("schema.version"))){
            try {if(Files.exists(file)&&!Files.exists(file.resolveSibling("admin-access-before-9.2.0.properties")))AtomicState.save(file.resolveSibling("admin-access-before-9.2.0.properties"),state,"Preserved pre-9.2.0 permissions");}
            catch(IOException e){throw new IllegalStateException("Rechtebackup fehlgeschlagen; Migration abgebrochen",e);}
            state.setProperty("schema.version","3");save();
        }
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
        if (node.equals("simplexadminsuite.admin")) return hasAccess(p.getUniqueId(), p, Map.of());
        Role role = role(p.getUniqueId());
        if(role==Role.NONE&&!PUBLIC.contains(node))return false;
        Boolean explicit = override(p.getUniqueId(), node);
        if (explicit != null) return explicit;
        if(PUBLIC.contains(node))return inherited;
        if (role == Role.CUSTOM) return customAllows(p.getUniqueId(), node);
        if (role == Role.NONE) return PUBLIC.contains(node) && inherited;
        if (node.startsWith("simplexadminsuite.menu.")) {
            if (role != Role.READ_ONLY) return true;
            String menu = node.substring(node.lastIndexOf('.') + 1);
            return Set.of("openmain", "openadminmenu", "openstatus", "openonline", "openplayers", "openplayer", "openhistory", "opendiagnostics", "openprofile", "openstored").contains(menu);
        }
        if (node.startsWith("simplexadminsuite.action.")) {
            if (role == Role.INHERIT) return inherited;
            String action = node.substring(node.lastIndexOf('.') + 1);
            return roleAllows(role, node);
        }
        if (role == Role.INHERIT) return inherited;
        if (!nodes.contains(node)) return inherited;
        // Public gameplay permissions remain owned by Bukkit/LuckPerms.
        if (PUBLIC.contains(node)) return inherited;
        return roleAllows(role, node);
    }
    static final Set<String> PUBLIC = Set.of("simplexadminsuite.shop.use", "simplexadminsuite.report", "simplexadminsuite.warps.use", "simplexadminsuite.rules.use", "simplexadminsuite.vote", "simplexadminsuite.points.use", "simplexadminsuite.ask", "simplexadminsuite.scoreboard.use", "simplexadminsuite.nickname.use", "simplexadminsuite.fart.use", "simplexadminsuite.burp.use", "simplexadminsuite.ai.ask", "simplexadminsuite.points", "simplexadminsuite.polls.vote", "simplexadminsuite.polls.use");
    static boolean roleAllows(Role role, String node) {
        if (role == Role.NONE || role == Role.CUSTOM || role == Role.INHERIT) return false;
        if (role == Role.FULL) return true;
        if (node.equals("simplexadminsuite.admin") || node.equals("simplexadminsuite.history")) return true;
        if (node.startsWith("simplexadminsuite.action.")) {
            String action=node.substring(node.lastIndexOf('.')+1);
            if(role==Role.JUNIOR_ADMIN)return action.equals("kick");
            if(role==Role.TEMP_MODERATOR)return Set.of("kick","tempban","tempmute","unmute").contains(action);
            return role==Role.MODERATOR&&Set.of("kick","mute","tempmute","unmute","warn").contains(action);
        }
        if(role==Role.JUNIOR_ADMIN||role==Role.TEMP_MODERATOR)return node.equals("simplexadminsuite.bans") || role==Role.TEMP_MODERATOR&&node.equals("simplexadminsuite.mutes");
        if (role == Role.READ_ONLY) return false;
        return Set.of("simplexadminsuite.reports","simplexadminsuite.notes","simplexadminsuite.staffchat","simplexadminsuite.watch").contains(node)
            || role==Role.MODERATOR&&Set.of("simplexadminsuite.bans","simplexadminsuite.mutes","simplexadminsuite.warns","simplexadminsuite.freeze","simplexadminsuite.teleport","simplexadminsuite.vanish").contains(node);
    }
    static boolean administrative(String node) {
        return node.startsWith("simplexadminsuite.") && !PUBLIC.contains(node) && !node.contains(".menu.") && !node.endsWith(".*")
            && !node.endsWith(".use") && !node.endsWith(".bypass") && !node.endsWith(".alerts");
    }
    synchronized String customRole(UUID id) { return state.getProperty(id+".custom-role", ""); }
    synchronized boolean customAllows(UUID id,String node) {
        if(node.startsWith("simplexadminsuite.menu."))return true;
        if(node.equals("simplexadminsuite.admin"))return !customRole(id).isEmpty();
        return Boolean.parseBoolean(state.getProperty("custom."+customRole(id)+".permission."+node,"false"));
    }
    synchronized boolean effective(UUID id,String node,Player online,Map<String,Boolean> inherited) {
        if(online!=null && master(online)||Bukkit.getOfflinePlayer(id).isOp()||id.equals(owner))return true;
        if(role(id)==Role.NONE&&!PUBLIC.contains(node))return false;
        Boolean local=override(id,node); if(local!=null)return local;
        Role role=role(id);
        if(role==Role.CUSTOM)return customAllows(id,node);
        if(role!=Role.INHERIT)return roleAllows(role,node);
        return online!=null?online.hasPermission(node)||online.hasPermission("simplexadminsuite.*")||online.hasPermission("*"):inherited.getOrDefault(node,false);
    }
    boolean hasAccess(UUID id,Player online,Map<String,Boolean> inherited) {
        if(id.equals(owner)||Bukkit.getOfflinePlayer(id).isOp())return true;
        if(role(id)==Role.NONE)return false;
        for(String node:nodes)if(administrative(node)&&effective(id,node,online,inherited))return true;
        return false;
    }
    synchronized List<Object> roleCatalog() {
        List<Object> out=new ArrayList<>();
        for(Role role:Role.values())if(role!=Role.CUSTOM)out.add(SimplexAdminSuite.appMap("id",role.name(),"name",roleName(role),"maxBanSeconds",defaultLimit(role),"altBans",role==Role.FULL||role==Role.INHERIT));
        for(String key:new TreeSet<>(state.stringPropertyNames()))if(key.startsWith("custom.")&&key.endsWith(".name")) {
            String id=key.substring(7,key.length()-5); out.add(SimplexAdminSuite.appMap("id","CUSTOM:"+id,"name",state.getProperty(key),"maxBanSeconds",Long.parseLong(state.getProperty("custom."+id+".max-ban-seconds","259200")),"altBans",Boolean.parseBoolean(state.getProperty("custom."+id+".alt-bans","false")),"permissions",customDefinition(id).get("permissions")));
        } return out;
    }
    static String roleName(Role role) { return switch(role) {
        case INHERIT->"LuckPerms / Bukkit übernehmen"; case FULL->"Volladministrator"; case MODERATOR->"Moderator"; case SUPPORT->"Support"; case READ_ONLY->"Nur lesen";
        case JUNIOR_ADMIN->"Junior-Admin · nur kicken"; case TEMP_MODERATOR->"Zeitbann-Moderator · maximal 3 Tage"; case CUSTOM->"Eigene Rolle"; case NONE->"Kein Admin-Zugang"; }; }
    synchronized String roleLabel(UUID id){return role(id)==Role.CUSTOM?state.getProperty("custom."+customRole(id)+".name","Eigene Rolle"):roleName(role(id));}
    static long defaultLimit(Role role) { return switch(role) {case FULL,INHERIT->0;case JUNIOR_ADMIN,SUPPORT,READ_ONLY,NONE->-1;default->259200;}; }
    synchronized long maxBanSeconds(UUID id) {
        String prefix=role(id)==Role.CUSTOM?"custom."+customRole(id):id.toString();
        try{return Long.parseLong(state.getProperty(prefix+".max-ban-seconds",Long.toString(defaultLimit(role(id)))));}catch(NumberFormatException e){return -1;}
    }
    synchronized Map<String,Object> customDefinition(String id){
        String prefix="custom."+id+".";if(!state.containsKey(prefix+"name"))throw new IllegalArgumentException("Rolle fehlt");Set<String> selected=new TreeSet<>();
        for(String node:nodes)if(Boolean.parseBoolean(state.getProperty(prefix+"permission."+node,"false")))selected.add(node);
        return SimplexAdminSuite.appMap("name",state.getProperty(prefix+"name"),"permissions",selected,"maxBanSeconds",Long.parseLong(state.getProperty(prefix+"max-ban-seconds","259200")),"altBans",Boolean.parseBoolean(state.getProperty(prefix+"alt-bans","false")));
    }
    synchronized boolean altBansFor(UUID id){String prefix=role(id)==Role.CUSTOM?"custom."+customRole(id):id.toString();return Boolean.parseBoolean(state.getProperty(prefix+".alt-bans",Boolean.toString(role(id)==Role.FULL||role(id)==Role.INHERIT)));}
    synchronized boolean altBans(CommandSender actor) {
        if(master(actor)||!(actor instanceof Player p))return true;
        String prefix=role(p.getUniqueId())==Role.CUSTOM?"custom."+customRole(p.getUniqueId()):p.getUniqueId().toString();
        return Boolean.parseBoolean(state.getProperty(prefix+".alt-bans",Boolean.toString(role(p.getUniqueId())==Role.FULL||role(p.getUniqueId())==Role.INHERIT)))
            && plugin.has(actor,"simplexadminsuite.alts.cascade");
    }
    String punishmentError(CommandSender actor,String action,String duration) {
        if(master(actor)||!(actor instanceof Player p))return "";
        if(!plugin.has(actor,"simplexadminsuite.action."+action))return "Diese Strafaktion ist für deine Rolle gesperrt.";
        if(!Set.of("ban","tempban").contains(action))return "";
        long max=maxBanSeconds(p.getUniqueId());
        if(max<0)return "Diese Rolle darf keine Banns aussprechen.";
        if(max==0)return "";
        SimplexAdminSuite.DurationValue parsed=SimplexAdminSuite.DurationValue.parse(duration);
        if(action.equals("ban")||parsed==null||parsed.toMillis()>max*1000L)return "Maximale Banndauer: "+max+" Sekunden. Dauerhafte Banns sind gesperrt.";
        return "";
    }
    synchronized void assign(String actor,UUID id,String selected) {
        if(selected.startsWith("CUSTOM:")) {
            String custom=selected.substring(7); if(!state.containsKey("custom."+custom+".name"))throw new IllegalArgumentException("Rolle fehlt");
            // Persist assignment and role in one atomic transaction; preserve existing explicit overrides.
            Properties before=new Properties();before.putAll(state);
            try { state.setProperty(id+".custom-role",custom); changeAuthorized(actor,id,null,null,Role.CUSTOM); }
            catch(RuntimeException e){state.clear();state.putAll(before);throw e;}
        } else changeAuthorized(actor,id,null,null,Role.valueOf(selected));
    }
    synchronized void limits(String actor,UUID id,long max,boolean alt) {
        if(role(id)==Role.CUSTOM)throw new IllegalArgumentException("Bannschutz bitte in der eigenen Rolle ändern; er gilt für alle Mitglieder.");
        if(id.equals(owner)||Bukkit.getOfflinePlayer(id).isOp())throw new IllegalArgumentException("Hauptoperator ist geschützt");
        if(max < -1 || max>315360000)throw new IllegalArgumentException("Banndauer außerhalb des Bereichs");
        Properties before=new Properties();before.putAll(state);
        state.setProperty(id+".max-ban-seconds",Long.toString(max));state.setProperty(id+".alt-bans",Boolean.toString(alt));
        try{save();}catch(RuntimeException e){state.clear();state.putAll(before);throw e;}
        revisions.merge(id,1L,Long::sum);plugin.invalidateAdminSession(id);plugin.expansionAudit(actor,id,"Bannlimits geändert",max+" Sekunden; Alt-Banns="+alt);
    }
    synchronized void saveCustom(String actor,String id,String name,String permissionList,long max,boolean alt) {
        if(!id.matches("[a-z0-9_-]{1,40}")||name.isBlank()||name.length()>60||max < -1 ||max>315360000)throw new IllegalArgumentException("Rollenname, Kennung oder Limit ungültig");
        Set<String> selected=new HashSet<>(Arrays.asList(permissionList.split(",")));
        for(String node:selected)if(!node.isBlank()&&(!nodes.contains(node)||!administrative(node)))throw new IllegalArgumentException("Unbekanntes Adminrecht: "+node);
        Properties before=new Properties();before.putAll(state);String prefix="custom."+id+".";
        state.keySet().removeIf(k->k.toString().startsWith(prefix));
        state.setProperty(prefix+"name",name);state.setProperty(prefix+"max-ban-seconds",Long.toString(max));state.setProperty(prefix+"alt-bans",Boolean.toString(alt));
        for(String node:selected)if(!node.isBlank())state.setProperty(prefix+"permission."+node,"true");
        try{save();}catch(RuntimeException e){state.clear();state.putAll(before);throw e;}
        for(Player p:Bukkit.getOnlinePlayers())if(customRole(p.getUniqueId()).equals(id)){revisions.merge(p.getUniqueId(),1L,Long::sum);plugin.invalidateAdminSession(p.getUniqueId());applyAttachment(p);p.closeInventory();}
        plugin.expansionAudit(actor,null,"Eigene Adminrolle gespeichert",id+" · "+name);
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
            if(role(p.getUniqueId())==Role.NONE&&!PUBLIC.contains(node))attachment.setPermission(node,false);
            else if (explicit != null) attachment.setPermission(node, explicit);
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
