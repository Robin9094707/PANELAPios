package eu.simplexsmp.adminsuite;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Admin activity ledger for the app (9.7.0). Every AuditLogger entry is mirrored here with the
 * acting admin, target and – when the admin is online – the world position where it happened.
 * Read markers are stored per app viewer, so marking entries as read never affects other admins.
 * The existing daily audit files and Discord admin-log channel stay unchanged.
 */
final class AdminActivityLog {
    static final int LIMIT = 5000;
    private static final long MAX_FILE = 6L * 1024 * 1024;
    private final SimplexAdminSuite plugin;
    private final Path file, readFile, filterFile;
    private final ArrayDeque<Map<String, Object>> rows = new ArrayDeque<>();
    private final Properties read = new Properties();
    /** 9.9.0: per-viewer unread filter (which admins/actions raise the unread counter). */
    private final Properties filters = new Properties();
    static final List<String> MODES = List.of("staff", "all", "selected", "none");
    private volatile Set<String> staffCache = Set.of();
    private volatile long staffCachedAt;
    private final ExecutorService writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(512), r -> { Thread t = new Thread(r, "Simplex-Admin-Log"); t.setDaemon(true); return t; }, new ThreadPoolExecutor.DiscardOldestPolicy());
    private long sequence;

    AdminActivityLog(SimplexAdminSuite plugin) {
        this.plugin = plugin;
        Path folder = plugin.getDataFolder().toPath().resolve("admin-log");
        file = folder.resolve("activity.jsonl");
        readFile = folder.resolve("read.properties");
        filterFile = folder.resolve("filters.properties");
        load();
    }

    private synchronized void load() {
        try {
            if (Files.exists(readFile)) try (Reader in = Files.newBufferedReader(readFile, StandardCharsets.UTF_8)) { read.load(in); }
            if (Files.exists(filterFile)) try (Reader in = Files.newBufferedReader(filterFile, StandardCharsets.UTF_8)) { filters.load(in); }
            if (!Files.exists(file)) return;
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (String line : lines.subList(Math.max(0, lines.size() - LIMIT), lines.size())) {
                try {
                    if (DiscordBotManager.Json.parse(line) instanceof Map<?, ?> m) {
                        Map<String, Object> row = new LinkedHashMap<>(); m.forEach((k, v) -> row.put(String.valueOf(k), v));
                        rows.addLast(row); sequence = Math.max(sequence, number(row.get("id")));
                    }
                } catch (RuntimeException damaged) { /* single damaged line is skipped, file stays */ }
            }
        } catch (IOException e) { plugin.getLogger().warning("Admin-Log konnte nicht geladen werden; Datei bleibt erhalten: " + e.getMessage()); }
    }

    /** Identity of an audit actor: lower-case key, display name and source (ingame, discord, app, console). */
    record Identity(String key, String name, String source) { }

    Identity identity(String actor) {
        String raw = actor == null || actor.isBlank() ? "System" : actor.trim();
        java.util.regex.Matcher app = java.util.regex.Pattern.compile("^Simplex Panel/(.+) \\(app:(.+)\\)$").matcher(raw);
        if (app.matches()) {
            String keyId = app.group(2), label = app.group(1);
            if (keyId.startsWith("user:")) { String user = keyId.split(":")[1]; return new Identity(user.toLowerCase(Locale.ROOT), user, "app"); }
            String owner = ownerName();
            return new Identity(owner.toLowerCase(Locale.ROOT), owner.isBlank() ? label : owner, "app");
        }
        java.util.regex.Matcher discord = java.util.regex.Pattern.compile("^Discord/(.+) \\(([0-9]{5,25})\\)$").matcher(raw);
        if (discord.matches()) {
            String linked = "";
            try { var link = plugin.accountLinks == null ? null : plugin.accountLinks.store.byDiscord(discord.group(2)); if (link != null) linked = link.name(); } catch (RuntimeException ignored) { }
            String name = linked.isBlank() ? discord.group(1) : linked;
            return new Identity((linked.isBlank() ? "discord:" + discord.group(2) : linked).toLowerCase(Locale.ROOT), name, "discord");
        }
        if (raw.equalsIgnoreCase("CONSOLE") || raw.equalsIgnoreCase("Konsole") || raw.equalsIgnoreCase("Server")) return new Identity("console", "Konsole", "console");
        if (raw.equalsIgnoreCase("System") || raw.startsWith("System")) return new Identity("system", "System", "system");
        String name = raw.length() > 48 ? raw.substring(0, 48) : raw;
        return new Identity(name.toLowerCase(Locale.ROOT), name, "ingame");
    }

    String ownerName() { return plugin.getConfig().getString("app-api.owner-name", "").trim(); }

    void record(String actor, UUID target, String action, String detail) {
        if (action == null) return;
        Identity who = identity(actor);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("time", System.currentTimeMillis());
        row.put("admin", who.key()); row.put("adminName", who.name()); row.put("source", who.source());
        row.put("action", clip(action, 160));
        String safe = clip(SimplexAdminSuite.stripFormatting(detail == null ? "" : detail), 900);
        if (safe.toLowerCase(Locale.ROOT).matches(".*(password|passwort|token|api-key|api_key|secret|credential).*")) safe = "[Geschützte Details ausgeblendet]";
        row.put("detail", safe);
        String category = ModerationControl.auditCategory(action);
        row.put("category", category.isBlank() ? (viewOnly(action) ? "view" : "other") : category);
        if (target != null) { row.put("target", target.toString()); row.put("targetName", safeName(target)); }
        // Locations are read only on the main thread; asynchronous audit calls keep them empty.
        if (Bukkit.isPrimaryThread()) {
            Player admin = who.source().equals("ingame") ? Bukkit.getPlayerExact(who.name()) : null;
            if (admin != null) { row.put("adminUuid", admin.getUniqueId().toString()); row.put("location", location(admin.getLocation())); }
            Player victim = target == null ? null : Bukkit.getPlayer(target);
            if (victim != null) row.put("targetLocation", location(victim.getLocation()));
        }
        row.put("staff", staff(who, Bukkit.isPrimaryThread() ? staffNames() : staffCache));
        synchronized (this) {
            row.put("id", ++sequence);
            rows.addLast(row);
            while (rows.size() > LIMIT) rows.removeFirst();
        }
        String line = DiscordBotManager.Json.stringify(row);
        try { writer.execute(() -> append(line)); } catch (RejectedExecutionException stopped) { /* plugin disabling */ }
        notifyViewers(row);
    }

    // ---------------------------------------------------------------- 9.9.0: team detection and unread filters

    /**
     * Names (lower case) the suite recognises as team: staff workspace members (OP, online permission, LuckPerms
     * simplexadminsuite.admin), server operators, app users and the configured main-admin name. Cached for 60 s.
     */
    Set<String> staffNames() {
        if (!Bukkit.isPrimaryThread() || System.currentTimeMillis() - staffCachedAt < 60_000L) return staffCache;
        Set<String> out = new HashSet<>();
        try { out.addAll(plugin.panelStaffNames()); } catch (RuntimeException ignored) { }
        try { if (plugin.appApi != null) for (Map<String, Object> user : plugin.appApi.security.users.list()) out.add(String.valueOf(user.get("username")).toLowerCase(Locale.ROOT)); } catch (RuntimeException ignored) { }
        String owner = ownerName(); if (!owner.isBlank()) out.add(owner.toLowerCase(Locale.ROOT));
        staffCache = Set.copyOf(out); staffCachedAt = System.currentTimeMillis();
        return staffCache;
    }

    /** App, console and Discord admin commands are always team actions; in-game actions only for recognised team members. */
    static boolean staff(Identity who, Set<String> names) {
        return switch (who.source()) { case "app", "console", "discord" -> true; case "system" -> false; default -> names.contains(who.key()); };
    }
    private static boolean staffRow(Map<String, Object> row, Set<String> names) {
        String source = String.valueOf(row.get("source"));
        if (Set.of("app", "console", "discord").contains(source)) return true;
        if (source.equals("system")) return false;
        return Boolean.TRUE.equals(row.get("staff")) || names.contains(String.valueOf(row.get("admin")));
    }

    record Filter(String mode, Set<String> admins, Set<String> categories, boolean views) {
        Map<String, Object> json() { return SimplexAdminSuite.appMap("mode", mode, "admins", new ArrayList<>(new TreeSet<>(admins)), "categories", new ArrayList<>(new TreeSet<>(categories)), "views", views); }
    }

    /** Default for every viewer: only team actions count as unread (players' own actions stay visible but silent). */
    synchronized Filter filter(String viewer) {
        String mode = filters.getProperty(viewer + ".mode", "staff");
        if (!MODES.contains(mode)) mode = "staff";
        return new Filter(mode, split(filters.getProperty(viewer + ".admins", "")), split(filters.getProperty(viewer + ".categories", "")), Boolean.parseBoolean(filters.getProperty(viewer + ".views", "false")));
    }
    private static Set<String> split(String raw) { Set<String> out = new LinkedHashSet<>(); for (String v : raw.split(",")) if (!v.isBlank()) out.add(v.trim().toLowerCase(Locale.ROOT)); return out; }

    /** Whether this row raises the unread counter (and push notifications) for the viewer. */
    static boolean counts(Map<String, Object> row, String self, Filter f, Set<String> staff) {
        String admin = String.valueOf(row.get("admin"));
        if (admin.equals(self) || f.mode().equals("none")) return false;
        if (!f.views() && "view".equals(row.get("category"))) return false;
        if (!f.categories().isEmpty() && !f.categories().contains(String.valueOf(row.get("category")))) return false;
        return switch (f.mode()) { case "all" -> true; case "selected" -> f.admins().contains(admin); default -> staffRow(row, staff); };
    }

    synchronized Object saveFilter(String viewer, Map<String, Object> input) throws IOException {
        String mode = String.valueOf(input.getOrDefault("mode", "staff"));
        if (!MODES.contains(mode)) throw new IllegalArgumentException("Modus: staff, all, selected oder none");
        List<String> admins = list(input.get("admins")), categories = list(input.get("categories"));
        if (admins.size() > 200 || categories.size() > 30) throw new IllegalArgumentException("Zu viele Einträge");
        for (String a : admins) if (a.length() > 80 || a.contains(",")) throw new IllegalArgumentException("Admin ungültig");
        for (String c : categories) if (!c.matches("[a-z-]{1,24}")) throw new IllegalArgumentException("Kategorie ungültig");
        Properties next = new Properties(); next.putAll(filters);
        next.setProperty(viewer + ".mode", mode);
        next.setProperty(viewer + ".admins", String.join(",", admins.stream().map(v -> v.toLowerCase(Locale.ROOT)).toList()));
        next.setProperty(viewer + ".categories", String.join(",", categories));
        next.setProperty(viewer + ".views", Boolean.toString(Boolean.parseBoolean(String.valueOf(input.getOrDefault("views", "false")))));
        AtomicState.save(filterFile, next, "Simplex admin log unread filters per app viewer (9.9.0)");
        filters.clear(); filters.putAll(next);
        return SimplexAdminSuite.appMap("ok", true, "title", "Filter gespeichert", "description", switch (mode) {
            case "all" -> "Alle Aktionen erhöhen den Zähler.";
            case "selected" -> admins.size() + " ausgewählte Admins erhöhen den Zähler.";
            case "none" -> "Der Ungelesen-Zähler ist aus. Das Protokoll bleibt vollständig sichtbar.";
            default -> "Nur Aktionen des Teams erhöhen den Zähler.";
        }, "settings", filter(viewer).json());
    }
    private static List<String> list(Object value) {
        if (value instanceof List<?> l) return l.stream().map(String::valueOf).map(String::trim).filter(v -> !v.isBlank()).distinct().toList();
        String raw = value == null ? "" : String.valueOf(value);
        return Arrays.stream(raw.split(",")).map(String::trim).filter(v -> !v.isBlank()).distinct().toList();
    }

    /** Own admin key for a notification owner ("user:<base64>" or an API key id). */
    String selfOf(String viewer) {
        if (viewer.startsWith("user:")) { try { return new String(Base64.getUrlDecoder().decode(viewer.substring(5)), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT); } catch (IllegalArgumentException e) { return ""; } }
        String owner = ownerName(); return owner.isBlank() ? "" : owner.toLowerCase(Locale.ROOT);
    }

    /** Bark and browser push for team actions, filtered per subscriber exactly like the unread counter. */
    private void notifyViewers(Map<String, Object> row) {
        if (plugin.appEvents == null || "view".equals(row.get("category"))) return;
        Set<String> staff = staffCache;
        String title = "🛡️ " + row.get("adminName") + ": " + row.get("action");
        String body = (row.get("targetName") instanceof String t && !t.isBlank() ? "Ziel: " + t + "\n" : "") + String.valueOf(row.getOrDefault("detail", ""));
        try { plugin.appEvents.direct("admin-log", title, body, owner -> counts(row, selfOf(owner), filter(owner), staff)); } catch (RuntimeException ignored) { }
    }

    /** 9.9.0 username change: entries, read markers and filters move to the new name; nothing else changes. */
    synchronized void rename(String oldName, String newName, String oldViewer, String newViewer) throws IOException {
        String from = oldName.toLowerCase(Locale.ROOT), to = newName.toLowerCase(Locale.ROOT);
        boolean changed = false;
        for (Map<String, Object> row : rows) if ("app".equals(row.get("source")) && from.equals(row.get("admin"))) { row.put("admin", to); row.put("adminName", newName); changed = true; }
        Properties nextRead = new Properties();
        for (String k : read.stringPropertyNames()) {
            String key = k;
            if (key.startsWith(oldViewer + ".")) key = newViewer + key.substring(oldViewer.length());
            if (key.endsWith("." + from)) key = key.substring(0, key.length() - from.length()) + to;
            nextRead.setProperty(key, read.getProperty(k));
        }
        Properties nextFilters = new Properties();
        for (String k : filters.stringPropertyNames()) {
            String v = filters.getProperty(k);
            if (k.endsWith(".admins")) v = String.join(",", split(v).stream().map(a -> a.equals(from) ? to : a).toList());
            nextFilters.setProperty(k.startsWith(oldViewer + ".") ? newViewer + k.substring(oldViewer.length()) : k, v);
        }
        AtomicState.save(readFile, nextRead, "Simplex admin log read markers per app viewer");
        AtomicState.save(filterFile, nextFilters, "Simplex admin log unread filters per app viewer (9.9.0)");
        read.clear(); read.putAll(nextRead); filters.clear(); filters.putAll(nextFilters);
        if (changed) {
            List<String> keep = new ArrayList<>(); for (Map<String, Object> row : rows) keep.add(DiscordBotManager.Json.stringify(row));
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling("activity.jsonl.tmp");
            Files.write(tmp, keep, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
    }

    static boolean viewOnly(String action) {
        return action.toLowerCase(Locale.ROOT).matches(".*(geöffnet|öffnen|abgefragt|gesucht|angesehen|diagnose|geprüft|ansicht|liste).*");
    }

    private String safeName(UUID id) { try { String n = plugin.discordAdminPlayerName(id); return n == null ? "" : n; } catch (RuntimeException e) { return ""; } }

    private static Map<String, Object> location(Location l) {
        if (l == null || l.getWorld() == null) return null;
        return SimplexAdminSuite.appMap("world", l.getWorld().getName(), "x", l.getBlockX(), "y", l.getBlockY(), "z", l.getBlockZ());
    }

    private synchronized void append(String line) {
        try {
            Files.createDirectories(file.getParent());
            if (Files.exists(file) && Files.size(file) > MAX_FILE) {
                Path previous = file.resolveSibling("activity.previous.jsonl");
                Files.move(file, previous, StandardCopyOption.REPLACE_EXISTING);
                List<String> keep = new ArrayList<>(); for (Map<String, Object> row : rows) keep.add(DiscordBotManager.Json.stringify(row));
                Files.write(file, keep, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                return;
            }
            Files.writeString(file, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) { plugin.getLogger().warning("Admin-Log konnte nicht gespeichert werden: " + e.getMessage()); }
    }

    /** Highest entry id the viewer has read for this admin (global "all read" marker included). */
    private long marker(String viewer, String admin) {
        return Math.max(number(read.getProperty(viewer + ".*", "0")), number(read.getProperty(viewer + "." + admin, "0")));
    }

    private static boolean actionable(Map<String, Object> row, boolean includeViews) { return includeViews || !"view".equals(row.get("category")); }

    /** Viewer's own admin key: own actions are never counted as unread. */
    String viewerAdmin(AppApiSecurity.Key key) {
        if (key.id().startsWith("user:")) return key.label().toLowerCase(Locale.ROOT);
        String owner = ownerName();
        return owner.isBlank() ? "" : owner.toLowerCase(Locale.ROOT);
    }

    synchronized Object overview(String viewer, String self, boolean includeViews) {
        Map<String, Map<String, Object>> admins = new LinkedHashMap<>();
        List<Map<String, Object>> newestFirst = new ArrayList<>(rows); Collections.reverse(newestFirst);
        Filter f = filter(viewer); Set<String> staff = staffNames();
        int unreadTotal = 0;
        for (Map<String, Object> row : newestFirst) {
            if (!actionable(row, includeViews)) continue;
            String admin = String.valueOf(row.get("admin"));
            Map<String, Object> summary = admins.computeIfAbsent(admin, k -> SimplexAdminSuite.appMap("admin", k, "name", row.get("adminName"), "source", row.get("source"), "total", 0, "unread", 0, "last", row, "self", k.equals(self), "online", false, "sources", new TreeSet<String>(), "staff", staffRow(row, staff)));
            summary.put("total", ((Integer) summary.get("total")) + 1);
            @SuppressWarnings("unchecked") Set<String> sources = (Set<String>) summary.get("sources"); sources.add(String.valueOf(row.get("source")));
            if (staffRow(row, staff)) summary.put("staff", true);
            if (counts(row, self, f, staff) && number(row.get("id")) > marker(viewer, admin)) { summary.put("unread", ((Integer) summary.get("unread")) + 1); unreadTotal++; }
        }
        for (Map<String, Object> summary : admins.values()) {
            String name = String.valueOf(summary.get("name"));
            Player online = Bukkit.getPlayerExact(name);
            summary.put("online", online != null);
            if (online != null) summary.put("uuid", online.getUniqueId().toString());
            else if (summary.get("last") instanceof Map<?, ?> last && last.get("adminUuid") != null) summary.put("uuid", last.get("adminUuid"));
            summary.put("sources", new ArrayList<>((Set<?>) summary.get("sources")));
            String key = String.valueOf(summary.get("admin"));
            summary.put("counted", !key.equals(self) && switch (f.mode()) { case "all" -> true; case "selected" -> f.admins().contains(key); case "none" -> false; default -> Boolean.TRUE.equals(summary.get("staff")); });
        }
        List<Map<String, Object>> list = new ArrayList<>(admins.values());
        list.sort(Comparator.comparing((Map<String, Object> m) -> -((Integer) m.get("unread"))).thenComparing(m -> -number(((Map<?, ?>) m.get("last")).get("time"))));
        return SimplexAdminSuite.appMap("admins", list, "unread", unreadTotal, "total", rows.size(), "limit", LIMIT, "settings", f.json(), "modes", MODES);
    }

    synchronized Object entries(String viewer, String self, String admin, String query, String category, int offset, boolean includeViews) {
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
        Filter f = filter(viewer); Set<String> staff = staffNames();
        List<Map<String, Object>> out = new ArrayList<>();
        List<Map<String, Object>> newestFirst = new ArrayList<>(rows); Collections.reverse(newestFirst);
        for (Map<String, Object> row : newestFirst) {
            if (!admin.isBlank() && !admin.equals(row.get("admin"))) continue;
            if (!actionable(row, includeViews)) continue;
            if (!category.isBlank() && !category.equals(row.get("category"))) continue;
            if (!q.isBlank() && !DiscordBotManager.Json.stringify(row).toLowerCase(Locale.ROOT).contains(q)) continue;
            Map<String, Object> copy = new LinkedHashMap<>(row);
            String owner = String.valueOf(row.get("admin"));
            copy.put("unread", counts(row, self, f, staff) && number(row.get("id")) > marker(viewer, owner));
            copy.put("staff", staffRow(row, staff));
            out.add(copy);
        }
        int start = Math.min(Math.max(0, offset), out.size()), end = Math.min(start + 100, out.size());
        return SimplexAdminSuite.appMap("items", new ArrayList<>(out.subList(start, end)), "next", end < out.size() ? end : -1, "total", out.size());
    }

    /** Marks entries as read for this viewer only; an empty admin marks everything. */
    synchronized Object markRead(String viewer, String admin) throws IOException {
        Properties next = new Properties(); next.putAll(read);
        if (admin == null || admin.isBlank()) {
            next.setProperty(viewer + ".*", Long.toString(sequence));
            for (String k : new ArrayList<>(next.stringPropertyNames())) if (k.startsWith(viewer + ".") && !k.equals(viewer + ".*")) next.remove(k);
        } else next.setProperty(viewer + "." + admin, Long.toString(sequence));
        AtomicState.save(readFile, next, "Simplex admin log read markers per app viewer");
        read.clear(); read.putAll(next);
        return SimplexAdminSuite.appMap("ok", true, "title", "Als gelesen markiert", "description", admin == null || admin.isBlank() ? "Alle Admin-Protokolle sind gelesen." : "Protokoll gelesen.");
    }

    void stop() { writer.shutdown(); try { writer.awaitTermination(3, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }

    static long number(Object value) { if (value instanceof Number n) return n.longValue(); try { return Long.parseLong(String.valueOf(value)); } catch (NumberFormatException e) { return 0; } }
    private static String clip(String value, int max) { String s = value == null ? "" : value.replaceAll("[\\p{Cntrl}&&[^\\n]]", " "); return s.length() > max ? s.substring(0, max) : s; }
}
