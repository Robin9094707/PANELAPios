package eu.simplexsmp.adminsuite;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.bukkit.Bukkit;

/**
 * 9.9.0 routes shared by the app and the web panel: browser push, personal web-panel appearance (stored on the
 * server per account), admin-log unread filters, username change, public staff name, LuckPerms and Lands.
 * Every route authenticates first (it runs after the regular /v1 authentication) and checks its own permission.
 */
final class PanelHub {
    static final Map<String, String> EVENT_TITLES = linked(
        "ticket", "Neue Tickets", "ticket-high", "Dringende Tickets", "ticket-urgent", "Notfall-Tickets", "ticket-low", "Tickets mit niedriger Priorität",
        "staff-chat", "Staff-Chat-Nachrichten", "admin-log", "Admin-Aktionen (nach Admin-Log-Filter)", "emergency-restart", "Notfallneustart",
        "ban-evasion", "Bann-Umgehung", "vpn-kick", "VPN-Kicks", "report", "Spieler-Reports", "overload", "Server überlastet", "client-brand", "Verdächtige Clients",
        "client-check", "Client-Prüfungen", "first-join", "Erster Beitritt", "join", "Jeder Beitritt", "ban", "Banns", "guard", "Schutzsystem", "admin", "Admin-Hinweise");
    private final AppApiManager api;
    private final SimplexAdminSuite plugin;
    private final AppApiSecurity security;
    final LuckPermsAdmin luckPerms;
    final LandsBridge lands;
    private final Path prefsFile;
    private final Properties prefs = new Properties();

    PanelHub(AppApiManager api, SimplexAdminSuite plugin, AppApiSecurity security) throws IOException {
        this.api = api; this.plugin = plugin; this.security = security;
        luckPerms = new LuckPermsAdmin(plugin); lands = new LandsBridge(plugin);
        prefsFile = security.directory.resolve("web-prefs.properties");
        if (Files.exists(prefsFile)) try (var in = Files.newBufferedReader(prefsFile, StandardCharsets.UTF_8)) { prefs.load(in); }
    }

    private static Map<String, String> linked(String... pairs) { Map<String, String> m = new LinkedHashMap<>(); for (int i = 0; i < pairs.length; i += 2) m.put(pairs[i], pairs[i + 1]); return m; }

    boolean route(HttpExchange x, AppApiSecurity.Key key, String path, String method, String ip, long rev) throws Exception {
        String owner = security.notificationOwner(key);
        boolean user = key.id().startsWith("user:");
        String name = user ? key.label() : "";
        if (method.equals("GET")) {
            Map<String, String> query = AppApiManager.query(x.getRequestURI());
            switch (path) {
                case "/v1/web/prefs" -> AppApiManager.send(x, 200, prefs(owner));
                case "/v1/push" -> {
                    WebPush push = api.webPush;
                    List<Map<String, Object>> events = new ArrayList<>();
                    for (var e : EVENT_TITLES.entrySet()) events.add(SimplexAdminSuite.appMap("id", e.getKey(), "title", e.getValue(), "allowed", push != null && allowed(owner, e.getKey())));
                    String current = query.getOrDefault("endpoint", "");
                    AppApiManager.send(x, 200, SimplexAdminSuite.appMap("available", push != null, "publicKey", push == null ? "" : push.publicKey(), "status", push == null ? "" : push.status(),
                        "devices", push == null ? List.of() : push.list(owner), "current", current.isBlank() ? "" : WebPush.idFor(current), "events", events, "defaults", WebPush.DEFAULT_EVENTS,
                        "domain", api.accounts.domain(), "quietHours", plugin.appEvents != null));
                }
                case "/v1/data/luckperms" -> {
                    api.requirePermission(key, "data.luckperms");
                    String group = query.getOrDefault("group", ""), player = query.getOrDefault("player", "");
                    Object result;
                    if (!group.isBlank()) result = luckPerms.group(group);
                    else if (!player.isBlank()) { UUID uuid = api.onMain(rev, () -> luckPerms.resolve(player)); result = luckPerms.user(uuid.toString()); }
                    else {
                        Map<String, Object> overview = new LinkedHashMap<>(luckPerms.overview());
                        List<Map<String, Object>> staff = new ArrayList<>();
                        Map<UUID, String> members = api.onMain(rev, plugin::panelStaffMembers);
                        if (Boolean.TRUE.equals(overview.get("installed"))) for (var e : members.entrySet()) {
                            if (staff.size() >= 60) break;
                            try { Map<String, Object> u = luckPerms.user(e.getKey().toString()); staff.add(SimplexAdminSuite.appMap("uuid", e.getKey().toString(), "name", e.getValue(), "primary", u.get("primary"), "groups", ((List<?>) u.get("groups")).stream().map(g -> ((Map<?, ?>) g).get("group")).toList(), "online", u.get("online"))); }
                            catch (Exception skipped) { /* not loaded */ }
                        }
                        overview.put("staff", staff);
                        result = overview;
                    }
                    AppApiManager.send(x, 200, result);
                }
                case "/v1/data/luckperms-permissions" -> {
                    api.requirePermission(key, "data.luckperms");
                    AppApiManager.send(x, 200, SimplexAdminSuite.appMap("items", api.onMain(rev, luckPerms::knownPermissions), "installed", luckPerms.installed()));
                }
                case "/v1/data/access-locks" -> {
                    api.requirePermission(key, "data.access-locks");
                    if (plugin.accessLocks == null) throw new AppApiManager.ApiError(503, "Rechte-Sperren nicht geladen");
                    String player = query.getOrDefault("player", "");
                    if (!player.isBlank()) {
                        Map<String, Object> out = api.onMain(rev, () -> { Map<String, Object> row = new LinkedHashMap<>(plugin.accessLocks.catalog()); row.put("player", plugin.accessLocks.player(plugin.accessLocks.resolve(player))); return row; });
                        AppApiManager.send(x, 200, out);
                    } else AppApiManager.send(x, 200, api.onMain(rev, () -> plugin.accessLocks.data(query.getOrDefault("q", ""))));
                }
                case "/v1/changelog" -> AppApiManager.send(x, 200, changelog(owner));
                case "/v1/data/lands" -> {
                    api.requirePermission(key, "data.lands");
                    String player = query.getOrDefault("player", "");
                    if (!player.isBlank()) {
                        UUID uuid = api.onMain(rev, () -> luckPerms.resolve(player));
                        AppApiManager.send(x, 200, SimplexAdminSuite.appMap("player", uuid.toString(), "lands", api.onMain(rev, () -> lands.playerLands(uuid))));
                        return true;
                    }
                    Map<String, Object> data = new LinkedHashMap<>(api.onMain(rev, () -> lands.overview(query.getOrDefault("q", ""), 300)));
                    if (Boolean.TRUE.equals(data.get("installed"))) {
                        Map<UUID, String> members = api.onMain(rev, plugin::panelStaffMembers);
                        data.put("admins", lands.admins(members, luckPerms));
                    } else data.put("admins", List.of());
                    data.put("luckperms", luckPerms.installed());
                    AppApiManager.send(x, 200, data);
                }
                default -> { return false; }
            }
            return true;
        }
        if (!method.equals("POST")) return false;
        switch (path) {
            case "/v1/web/prefs" -> {
                if (!api.rate("prefs:" + owner, 30)) throw new AppApiManager.ApiError(429, "Bitte kurz warten.");
                AppApiManager.send(x, 200, savePrefs(owner, AppApiManager.body(x, 4096)));
            }
            case "/v1/push/subscribe" -> {
                WebPush push = requirePush();
                if (!api.rate("push:" + owner, 20)) throw new AppApiManager.ApiError(429, "Bitte kurz warten.");
                Map<String, Object> body = AppApiManager.body(x, 4096);
                Map<String, String> device = new LinkedHashMap<>(WebPanel.browser(x.getRequestHeaders().getFirst("User-Agent")));
                if (WebPanel.cookie(x) == null) device.putAll(AccountApi.appDevice(x));
                Map<String, Object> result = push.subscribe(owner, body, device);
                audit(key, "Browser-Push aktiviert", device.getOrDefault("browser", "") + " · " + device.getOrDefault("os", ""));
                AppApiManager.send(x, 200, result);
            }
            case "/v1/push/update" -> {
                WebPush push = requirePush();
                Map<String, Object> body = AppApiManager.body(x, 4096);
                AppApiManager.send(x, 200, push.update(owner, String.valueOf(body.getOrDefault("id", "")), body, false));
            }
            case "/v1/push/unsubscribe" -> {
                WebPush push = requirePush();
                Map<String, Object> body = AppApiManager.body(x, 2048);
                AppApiManager.send(x, 200, push.unsubscribe(owner, String.valueOf(body.getOrDefault("endpoint", ""))));
            }
            case "/v1/push/test" -> {
                WebPush push = requirePush();
                if (!api.rate("push-test:" + owner, 5)) throw new AppApiManager.ApiError(429, "Höchstens fünf Tests pro Minute.");
                AppApiManager.send(x, 200, push.test(owner));
            }
            case "/v1/changelog/seen" -> {
                Map<String, Object> body = AppApiManager.body(x, 512);
                String version = String.valueOf(body.getOrDefault("version", "")).trim();
                if (!version.matches("[0-9]{1,3}(\\.[0-9]{1,3}){1,3}")) throw new IllegalArgumentException("Version ungültig");
                markSeen(owner, version);
                AppApiManager.send(x, 200, SimplexAdminSuite.appMap("ok", true, "seen", version));
            }
            case "/v1/admin-log/settings" -> {
                api.requirePermission(key, "data.admin-log");
                if (plugin.adminLog == null) throw new AppApiManager.ApiError(503, "Admin-Log nicht geladen");
                AppApiManager.send(x, 200, plugin.adminLog.saveFilter(owner, AppApiManager.body(x, 8192)));
            }
            case "/v1/account/public-name" -> {
                Map<String, Object> body = AppApiManager.body(x, 1024);
                String value = String.valueOf(body.getOrDefault("name", "")).trim();
                if (!value.isEmpty()) AppChat.validateName(value);
                if (user) security.users.setPublicName(name, value);
                else { if (!key.scope().equals("full")) throw new AppApiManager.ApiError(403, "Nur mit Vollzugriff."); api.onMain(rev, () -> { plugin.getConfig().set("app-api.owner-name", value); plugin.saveConfig(); return null; }); }
                audit(key, "Öffentlicher Name geändert", value.isEmpty() ? "Standard" : value);
                AppApiManager.send(x, 200, SimplexAdminSuite.appMap("ok", true, "publicName", value, "title", "Öffentlicher Name gespeichert",
                    "description", "Spieler sehen bei Ticketantworten jetzt „" + (value.isEmpty() ? plugin.getConfig().getString("app-api.public-staff-name", "Team") : value) + "“."));
            }
            case "/v1/account/rename" -> {
                if (!user) throw new AppApiManager.ApiError(409, "Dieser Zugang nutzt noch einen API-Schlüssel. Zuerst zu Benutzername und Passwort wechseln.");
                if (!api.rate("rename:" + name, 5)) throw new AppApiManager.ApiError(429, "Bitte kurz warten.");
                Map<String, Object> body = AppApiManager.body(x, 2048);
                String target = String.valueOf(body.getOrDefault("target", "")).isBlank() ? name : AppUsers.username(String.valueOf(body.get("target")));
                if (!target.equals(name) && !security.users.owner(name)) throw new AppApiManager.ApiError(403, "Andere Benutzer kann nur der Hauptadmin umbenennen.");
                if (security.users.verifyPassword(name, String.valueOf(body.getOrDefault("password", ""))) == null) throw new AppApiManager.ApiError(401, "Passwort ist falsch");
                String next = AppUsers.username(String.valueOf(body.getOrDefault("username", "")));
                rename(target, next, name);
                AppApiManager.send(x, 200, SimplexAdminSuite.appMap("ok", true, "username", next, "self", target.equals(name), "title", "Benutzername geändert",
                    "description", "Ab jetzt mit „" + next + "“ anmelden. Passwort, 2FA, Passkeys, Rechte und alle angemeldeten Geräte bleiben unverändert."));
            }
            default -> { return false; }
        }
        return true;
    }

    private WebPush requirePush() { if (api.webPush == null) throw new AppApiManager.ApiError(503, "Browser-Push ist nicht verfügbar."); return api.webPush; }
    private void audit(AppApiSecurity.Key key, String action, String detail) {
        String actor = key.id().startsWith("user:") ? "Simplex Panel/" + key.label() + " (app:user:" + key.label() + ")" : "Simplex Panel/" + key.label() + " (app:" + key.id() + ")";
        try { Bukkit.getScheduler().runTask(plugin, () -> plugin.suiteAudit(actor, action, detail)); } catch (RuntimeException ignored) { }
    }

    /** Whether a notification owner may still receive this event type (account enabled and right present). */
    boolean allowed(String owner, String type) {
        String permission = switch (type) { case "admin-log" -> "data.admin-log"; case "staff-chat" -> "data.staff-chat"; default -> type.startsWith("ticket") ? "data.tickets" : "data.events"; };
        if (owner.startsWith("user:")) {
            String user; try { user = new String(Base64.getUrlDecoder().decode(owner.substring(5)), StandardCharsets.UTF_8); } catch (IllegalArgumentException e) { return false; }
            if (!security.users.enabled(user)) return false;
            Set<String> rights = security.users.permissions(user);
            return rights.contains("*") || rights.contains(permission);
        }
        return security.keys().stream().anyMatch(k -> k.id().equals(owner) && k.scope().equals("full"));
    }

    /**
     * Renames an app account and moves every piece of state keyed by its name: sessions, passkeys and 2FA (AppUsers),
     * admin-log entries/read markers/filters, Bark settings and quiet hours, browser push devices and web appearance.
     */
    /** {@code by}: the acting account name, or "Serverkonsole". A self-rename is audited under the new name. */
    synchronized void rename(String from, String to, String by) throws IOException {
        String oldOwner = ownerKey(from), newOwner = ownerKey(AppUsers.username(to));
        security.users.rename(from, to);
        String next = AppUsers.username(to);
        if (plugin.adminLog != null) plugin.adminLog.rename(from, next, oldOwner, newOwner);
        if (plugin.appEvents != null) plugin.appEvents.renameOwner(oldOwner, newOwner);
        if (api.webPush != null) api.webPush.renameOwner(oldOwner, newOwner);
        renamePrefs(oldOwner, newOwner);
        String who = by.equals(from) ? next : by;
        String actor = who.equals("Serverkonsole") ? "Serverkonsole" : "Simplex Panel/" + who + " (app:user:" + who + ")";
        try { Bukkit.getScheduler().runTask(plugin, () -> plugin.suiteAudit(actor, "Benutzername geändert", from + " → " + next)); } catch (RuntimeException ignored) { }
    }
    static String ownerKey(String username) { return "user:" + Base64.getUrlEncoder().withoutPadding().encodeToString(username.getBytes(StandardCharsets.UTF_8)); }

    // ---------------------------------------------------------------- 10.0.0 changelog shown once per account (web panel and app)

    private static volatile Object changelogCache;
    static Object changelogEntries() {
        if (changelogCache != null) return changelogCache;
        try (var in = PanelHub.class.getResourceAsStream("/changelog.json")) {
            changelogCache = in == null ? List.of() : DiscordBotManager.Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) { changelogCache = List.of(); }
        return changelogCache;
    }
    synchronized Map<String, Object> changelog(String owner) {
        String version = plugin.getDescription().getVersion();
        String seen = prefs.getProperty("changelog." + owner, "");
        return SimplexAdminSuite.appMap("version", version, "seen", seen, "unseen", !seen.equals(version), "entries", changelogEntries());
    }
    synchronized void markSeen(String owner, String version) throws IOException {
        if (version.equals(prefs.getProperty("changelog." + owner))) return;
        Properties next = new Properties(); next.putAll(prefs); next.setProperty("changelog." + owner, version);
        AtomicState.save(prefsFile, next, "Simplex web panel appearance per account (9.9.0)");
        prefs.clear(); prefs.putAll(next);
    }

    // ---------------------------------------------------------------- web panel appearance (per account, on the server)

    static final Map<String, List<String>> CHOICES = Map.of(
        "theme", List.of("auto", "dark", "light"), "accent", List.of("cyan", "blue", "violet", "emerald", "amber", "rose", "red", "slate"),
        "density", List.of("comfortable", "compact"), "radius", List.of("rounded", "soft", "sharp"), "motion", List.of("full", "reduced"),
        "chatEnter", List.of("send", "newline"), "background", List.of("glow", "plain"));
    static final Map<String, Object> DEFAULTS = SimplexAdminSuite.appMap("theme", "auto", "accent", "cyan", "density", "comfortable", "radius", "rounded", "motion", "full",
        "chatEnter", "send", "background", "glow", "font", 100, "start", "dashboard", "tabs", List.of("dashboard", "players", "tickets", "staff-chat"), "hidden", List.of(), "pageNotifications", true, "sidebar", "expanded");

    synchronized Map<String, Object> prefs(String owner) {
        Map<String, Object> out = new LinkedHashMap<>(DEFAULTS);
        String raw = prefs.getProperty(owner, "");
        if (!raw.isBlank()) try { if (DiscordBotManager.Json.parse(raw) instanceof Map<?, ?> m) m.forEach((k, v) -> { if (DEFAULTS.containsKey(String.valueOf(k))) out.put(String.valueOf(k), v); }); } catch (RuntimeException ignored) { }
        return SimplexAdminSuite.appMap("prefs", out, "defaults", DEFAULTS, "choices", CHOICES, "saved", !raw.isBlank());
    }

    synchronized Map<String, Object> savePrefs(String owner, Map<String, Object> input) throws IOException {
        Map<String, Object> current = new LinkedHashMap<>(AppApiManager.object(prefs(owner).get("prefs")));
        if (Boolean.TRUE.equals(input.get("reset"))) current = new LinkedHashMap<>(DEFAULTS);
        for (var e : input.entrySet()) {
            String k = e.getKey(); Object v = e.getValue();
            if (k.equals("reset")) continue;
            if (CHOICES.containsKey(k)) { if (!CHOICES.get(k).contains(String.valueOf(v))) throw new IllegalArgumentException("Ungültiger Wert für " + k); current.put(k, String.valueOf(v)); }
            else if (k.equals("font")) { int n; try { n = (int) Math.round(Double.parseDouble(String.valueOf(v))); } catch (NumberFormatException ex) { throw new IllegalArgumentException("Schriftgröße ungültig"); } if (n < 85 || n > 130) throw new IllegalArgumentException("Schriftgröße 85–130 %"); current.put(k, n); }
            else if (k.equals("start")) { String s = String.valueOf(v); if (!s.matches("[a-z-]{1,24}")) throw new IllegalArgumentException("Startseite ungültig"); current.put(k, s); }
            else if (k.equals("tabs") || k.equals("hidden")) {
                List<String> list = v instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
                if (list.size() > (k.equals("tabs") ? 4 : 30) || list.stream().anyMatch(s -> !s.matches("[a-z-]{1,24}"))) throw new IllegalArgumentException("Navigation ungültig");
                current.put(k, new ArrayList<>(new LinkedHashSet<>(list)));
            }
            else if (k.equals("pageNotifications")) current.put(k, Boolean.parseBoolean(String.valueOf(v)));
            else if (k.equals("sidebar")) { String s = String.valueOf(v); if (!Set.of("expanded", "collapsed").contains(s)) throw new IllegalArgumentException("Seitenleiste ungültig"); current.put(k, s); }
            else throw new IllegalArgumentException("Unbekannte Einstellung: " + k);
        }
        Properties next = new Properties(); next.putAll(prefs); next.setProperty(owner, DiscordBotManager.Json.stringify(current));
        AtomicState.save(prefsFile, next, "Simplex web panel appearance per account (9.9.0)");
        prefs.clear(); prefs.putAll(next);
        return SimplexAdminSuite.appMap("ok", true, "prefs", current, "title", "Darstellung gespeichert", "description", "Gilt auf allen Browsern, mit denen du dich anmeldest.");
    }

    private synchronized void renamePrefs(String from, String to) throws IOException {
        String value = prefs.getProperty(from), seen = prefs.getProperty("changelog." + from); if (value == null && seen == null) return;
        Properties next = new Properties(); next.putAll(prefs); next.remove(from); next.remove("changelog." + from);
        if (value != null) next.setProperty(to, value);
        if (seen != null) next.setProperty("changelog." + to, seen);
        AtomicState.save(prefsFile, next, "Simplex web panel appearance per account (9.9.0)"); prefs.clear(); prefs.putAll(next);
    }
    synchronized void removeOwner(String owner) throws IOException {
        if (!prefs.containsKey(owner) && !prefs.containsKey("changelog." + owner)) return;
        Properties next = new Properties(); next.putAll(prefs); next.remove(owner); next.remove("changelog." + owner);
        AtomicState.save(prefsFile, next, "Simplex web panel appearance per account (9.9.0)"); prefs.clear(); prefs.putAll(next);
    }
}
