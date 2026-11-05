package eu.simplexsmp.adminsuite;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Bounded, explicit history management shared by app and web. Operational state is never a history. */
final class HistoryApi {
    static final String MANAGE = "history.manage";
    record Kind(String id, String title, String permission, int limit) { }
    static final List<Kind> KINDS = List.of(
        new Kind("admin-log", "Admin-Log", "data.admin-log", 5000),
        new Kind("events", "Serverereignisse", "data.events", 2000),
        new Kind("advertising", "Werbe-Verlauf", "data.discord-ads", 200),
        new Kind("staff-chat", "Staff-Chat", "data.staff-chat", 400),
        new Kind("chat", "Serverchat", "data.chat", 50));
    interface Store {
        List<Map<String, Object>> snapshot();
        void replace(List<Map<String, Object>> items) throws IOException;
    }
    record Plan(String key, String kind, Set<String> ids, long expires) { }
    private final Map<String, Plan> plans = new LinkedHashMap<>();
    private final SimplexAdminSuite plugin;
    private final Path backups;
    HistoryApi(SimplexAdminSuite plugin) { this.plugin = plugin; backups = plugin.getDataFolder().toPath().resolve("history-backups"); }

    boolean route(AppApiManager api, HttpExchange x, AppApiSecurity.Key key, String path, String method) throws Exception {
        if (!path.equals("/v1/histories") && !path.startsWith("/v1/histories/")) return false;
        if (method.equals("GET") && path.equals("/v1/histories")) {
            List<Object> kinds = new ArrayList<>();
            for (Kind kind : KINDS) if (allowed(api, key, kind.permission()) && available(kind.id())) {
                Object lock = lock(kind.id());
                synchronized (lock) { kinds.add(SimplexAdminSuite.appMap("id", kind.id(), "title", kind.title(), "count", store(kind.id()).snapshot().size())); }
            }
            AppApiManager.send(x, 200, SimplexAdminSuite.appMap("kinds", kinds, "canManage", canManage(api, key), "backups", canManage(api, key) ? backupList(api, key) : List.of()));
            return true;
        }
        if (method.equals("GET") && path.equals("/v1/histories/entries")) {
            Map<String, String> query = AppApiManager.query(x.getRequestURI());
            Kind kind = kind(query.getOrDefault("kind", "")); api.requirePermission(key, kind.permission());
            String q = query.getOrDefault("q", "").strip().toLowerCase(Locale.ROOT);
            List<Map<String, Object>> rows;
            synchronized (lock(kind.id())) { rows = store(kind.id()).snapshot(); }
            rows.sort(Comparator.comparingLong(HistoryApi::time).reversed());
            rows.removeIf(r -> !q.isEmpty() && !DiscordBotManager.Json.stringify(r).toLowerCase(Locale.ROOT).contains(q));
            int offset; try { offset = Math.max(0, Integer.parseInt(query.getOrDefault("offset", "0"))); } catch (NumberFormatException e) { offset = 0; }
            int start = Math.min(offset, rows.size()), end = Math.min(start + 100, rows.size());
            AppApiManager.send(x, 200, SimplexAdminSuite.appMap("items", rows.subList(start, end), "total", rows.size(), "next", end < rows.size() ? end : -1));
            return true;
        }
        if (!method.equals("POST")) throw new AppApiManager.ApiError(405, "GET oder POST erforderlich");
        if (!canManage(api, key)) throw new AppApiManager.ApiError(403, "Benötigt full-Zugriff und das Recht „Verläufe verwalten“");
        if (!api.rate("histories:" + key.id(), 20)) throw new AppApiManager.ApiError(429, "Bitte kurz warten.");
        Map<String, Object> body = AppApiManager.body(x, 64 * 1024);
        Object result;
        switch (path) {
            case "/v1/histories/prepare" -> {
                Kind kind = kind(String.valueOf(body.getOrDefault("kind", ""))); api.requirePermission(key, kind.permission());
                Set<String> requested = new HashSet<>();
                if (body.get("ids") instanceof List<?> ids) {
                    if (ids.size() > kind.limit()) throw new AppApiManager.ApiError(400, "Zu viele Einträge");
                    for (Object id : ids) requested.add(String.valueOf(id));
                }
                Set<String> selected = new LinkedHashSet<>();
                synchronized (lock(kind.id())) {
                    for (Map<String, Object> row : store(kind.id()).snapshot()) if (Boolean.TRUE.equals(body.get("all")) || requested.contains(id(row))) selected.add(id(row));
                }
                if (selected.isEmpty()) throw new AppApiManager.ApiError(409, "Keine passenden Einträge mehr vorhanden. Bitte neu laden.");
                String token = UUID.randomUUID().toString();
                synchronized (plans) {
                    plans.entrySet().removeIf(e -> e.getValue().expires() < System.currentTimeMillis());
                    if (plans.size() >= 100) throw new AppApiManager.ApiError(429, "Zu viele offene Bestätigungen");
                    plans.put(token, new Plan(key.id(), kind.id(), Set.copyOf(selected), System.currentTimeMillis() + 120_000));
                }
                result = SimplexAdminSuite.appMap("token", token, "count", selected.size(), "title", kind.title(), "expiresIn", 120);
            }
            case "/v1/histories/apply" -> {
                String token = String.valueOf(body.getOrDefault("token", "")); Plan plan;
                synchronized (plans) {
                    plan = plans.get(token);
                    if (plan == null || !plan.key().equals(key.id()) || plan.expires() < System.currentTimeMillis()) throw new AppApiManager.ApiError(409, "Bestätigung abgelaufen oder ungültig. Bitte neu laden.");
                    plans.remove(token);
                }
                Kind kind = kind(plan.kind()); api.requirePermission(key, kind.permission());
                List<Map<String, Object>> removed = new ArrayList<>(); String backup;
                synchronized (lock(kind.id())) {
                    Store store = store(kind.id()); List<Map<String, Object>> keep = new ArrayList<>();
                    for (Map<String, Object> row : store.snapshot()) { if (plan.ids().contains(id(row))) removed.add(row); else keep.add(row); }
                    if (removed.isEmpty()) throw new AppApiManager.ApiError(409, "Einträge wurden bereits entfernt.");
                    // A failed backup prevents deletion. The exact confirmed IDs preserve concurrent new entries.
                    try { backup = archive(kind.id(), removed, key.label()); }
                    catch (IOException e) { throw new AppApiManager.ApiError(503, "Sicherung konnte nicht erstellt werden. Es wurde nichts gelöscht. Bitte Speicherplatz und Serverordner prüfen."); }
                    try { store.replace(keep); }
                    catch (IOException e) { throw new AppApiManager.ApiError(503, "Verlauf konnte nicht gespeichert werden. Sicherung: " + backup + ". Bitte neu laden und Serverordner prüfen."); }
                }
                audit(api, key, "Verlauf bereinigt", kind.title() + ": " + removed.size() + " · Sicherung " + backup);
                result = SimplexAdminSuite.appMap("ok", true, "removed", removed.size(), "backup", backup, "title", "Verlauf bereinigt – Sicherung erstellt");
            }
            case "/v1/histories/restore" -> {
                String name = String.valueOf(body.getOrDefault("backup", ""));
                Map<String, Object> archive = readArchive(name); Kind kind = kind(String.valueOf(archive.get("kind"))); api.requirePermission(key, kind.permission());
                int added = 0;
                synchronized (lock(kind.id())) {
                    Store store = store(kind.id()); List<Map<String, Object>> rows = store.snapshot(); Set<String> ids = new HashSet<>(); rows.forEach(r -> ids.add(id(r)));
                    List<Map<String, Object>> restore = copies(archive.get("items"));
                    for (Map<String, Object> row : restore) if (ids.add(id(row))) { rows.add(row); added++; }
                    if (rows.size() > kind.limit()) throw new AppApiManager.ApiError(409, "Zu viele Einträge für eine verlustfreie Wiederherstellung. Zuerst ältere Einträge sichern und entfernen.");
                    rows.sort(Comparator.comparingLong(HistoryApi::time));
                    store.replace(rows);
                }
                audit(api, key, "Verlauf wiederhergestellt", kind.title() + ": " + added + " · " + name);
                result = SimplexAdminSuite.appMap("ok", true, "restored", added, "title", "Sicherung wiederhergestellt");
            }
            default -> throw new AppApiManager.ApiError(404, "Verlaufsaktion nicht gefunden");
        }
        AppApiManager.send(x, 200, result); return true;
    }

    private boolean canManage(AppApiManager api, AppApiSecurity.Key key) { return key.scope().equals("full") && allowed(api, key, MANAGE); }
    private static boolean allowed(AppApiManager api, AppApiSecurity.Key key, String permission) { try { api.requirePermission(key, permission); return true; } catch (AppApiManager.ApiError denied) { return false; } }
    private static Kind kind(String id) { return KINDS.stream().filter(k -> k.id().equals(id)).findFirst().orElseThrow(() -> new AppApiManager.ApiError(400, "Unbekannter Verlauf")); }
    private boolean available(String kind) { return switch (kind) { case "admin-log" -> plugin.adminLog != null; case "events" -> plugin.appEvents != null; case "advertising" -> plugin.adHub != null; case "staff-chat" -> plugin.staffChat != null; case "chat" -> plugin.appChat != null; default -> false; }; }
    private Object lock(String kind) {
        if (!available(kind)) throw new AppApiManager.ApiError(503, "Verlauf noch nicht geladen");
        return switch (kind) { case "admin-log" -> plugin.adminLog; case "events" -> plugin.appEvents; case "advertising" -> plugin.adHub; case "staff-chat" -> plugin.staffChat; case "chat" -> plugin.appChat; default -> throw new IllegalArgumentException(); };
    }
    private Store store(String kind) {
        lock(kind);
        return switch (kind) {
            case "admin-log" -> new Store() { public List<Map<String, Object>> snapshot() { return plugin.adminLog.historySnapshot(); } public void replace(List<Map<String, Object>> rows) throws IOException { plugin.adminLog.historyReplace(rows); } };
            case "events" -> new Store() { public List<Map<String, Object>> snapshot() { return plugin.appEvents.historySnapshot(); } public void replace(List<Map<String, Object>> rows) throws IOException { plugin.appEvents.historyReplace(rows); } };
            case "advertising" -> new Store() { public List<Map<String, Object>> snapshot() { return plugin.adHub.historySnapshot(); } public void replace(List<Map<String, Object>> rows) throws IOException { plugin.adHub.historyReplace(rows); } };
            case "staff-chat" -> new Store() { public List<Map<String, Object>> snapshot() { return plugin.staffChat.historySnapshot(); } public void replace(List<Map<String, Object>> rows) throws IOException { plugin.staffChat.historyReplace(rows); } };
            case "chat" -> new Store() { public List<Map<String, Object>> snapshot() { return plugin.appChat.historySnapshot(); } public void replace(List<Map<String, Object>> rows) throws IOException { plugin.appChat.historyReplace(rows); } };
            default -> throw new IllegalArgumentException();
        };
    }
    private String archive(String kind, List<Map<String, Object>> rows, String actor) throws IOException {
        Files.createDirectories(backups); AppApiSecurity.protect(backups);
        try (var files = Files.list(backups)) { if (files.filter(p -> p.getFileName().toString().endsWith(".json")).count() >= 100) throw new AppApiManager.ApiError(409, "100 Verlaufssicherungen vorhanden. Bitte im Serverordner history-backups archivieren, bevor weitere Verläufe gelöscht werden."); }
        String name = UUID.randomUUID() + ".json";
        Path temp = Files.createTempFile(backups, "backup-", ".tmp");
        try {
            Files.writeString(temp, DiscordBotManager.Json.stringify(SimplexAdminSuite.appMap("version", 1, "kind", kind, "at", System.currentTimeMillis(), "actor", actor, "items", rows)), StandardCharsets.UTF_8);
            AppApiSecurity.protect(temp);
            try { Files.move(temp, backups.resolve(name), StandardCopyOption.ATOMIC_MOVE); } catch (AtomicMoveNotSupportedException e) { Files.move(temp, backups.resolve(name)); }
        } finally { Files.deleteIfExists(temp); }
        return name;
    }
    private Map<String, Object> readArchive(String name) throws IOException {
        if (!name.matches("[a-f0-9-]{36}\\.json")) throw new AppApiManager.ApiError(400, "Ungültige Sicherung");
        Path file = backups.resolve(name);
        if (!Files.isRegularFile(file) || Files.isSymbolicLink(file)) throw new AppApiManager.ApiError(404, "Sicherung nicht gefunden");
        if (Files.size(file) > 16L * 1024 * 1024) throw new AppApiManager.ApiError(413, "Sicherung zu groß");
        return AppApiManager.object(DiscordBotManager.Json.parse(Files.readString(file, StandardCharsets.UTF_8)));
    }
    private List<Object> backupList(AppApiManager api, AppApiSecurity.Key key) throws IOException {
        List<Object> result = new ArrayList<>(); if (!Files.isDirectory(backups)) return result;
        try (var files = Files.list(backups)) {
            for (Path file : files.filter(p -> p.getFileName().toString().matches("[a-f0-9-]{36}\\.json")).limit(100).toList()) {
                Map<String, Object> a = readArchive(file.getFileName().toString()); Kind kind = kind(String.valueOf(a.get("kind")));
                if (allowed(api, key, kind.permission())) result.add(SimplexAdminSuite.appMap("id", file.getFileName().toString(), "kind", kind.id(), "title", kind.title(), "at", a.get("at"), "count", copies(a.get("items")).size()));
            }
        }
        result.sort((a, b) -> Long.compare(AdminActivityLog.number(((Map<?, ?>) b).get("at")), AdminActivityLog.number(((Map<?, ?>) a).get("at")))); return result;
    }
    private void audit(AppApiManager api, AppApiSecurity.Key key, String action, String detail) {
        org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> plugin.suiteAudit("Simplex Panel/" + key.label() + " (app:" + key.id() + ")", action, detail));
    }
    static String id(Map<String, Object> row) { return String.valueOf(row.get("id")); }
    static long time(Map<String, Object> row) { return AdminActivityLog.number(row.getOrDefault("time", row.getOrDefault("at", 0))); }
    static List<Map<String, Object>> copies(Object value) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (value instanceof Collection<?> rows) for (Object row : rows) if (row instanceof Map<?, ?> m) { Map<String, Object> copy = new LinkedHashMap<>(); m.forEach((k, v) -> copy.put(String.valueOf(k), v)); result.add(copy); }
        return result;
    }
}
