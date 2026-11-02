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
    private final Path file, readFile;
    private final ArrayDeque<Map<String, Object>> rows = new ArrayDeque<>();
    private final Properties read = new Properties();
    private final ExecutorService writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(512), r -> { Thread t = new Thread(r, "Simplex-Admin-Log"); t.setDaemon(true); return t; }, new ThreadPoolExecutor.DiscardOldestPolicy());
    private long sequence;

    AdminActivityLog(SimplexAdminSuite plugin) {
        this.plugin = plugin;
        Path folder = plugin.getDataFolder().toPath().resolve("admin-log");
        file = folder.resolve("activity.jsonl");
        readFile = folder.resolve("read.properties");
        load();
    }

    private synchronized void load() {
        try {
            if (Files.exists(readFile)) try (Reader in = Files.newBufferedReader(readFile, StandardCharsets.UTF_8)) { read.load(in); }
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
        synchronized (this) {
            row.put("id", ++sequence);
            rows.addLast(row);
            while (rows.size() > LIMIT) rows.removeFirst();
        }
        String line = DiscordBotManager.Json.stringify(row);
        try { writer.execute(() -> append(line)); } catch (RejectedExecutionException stopped) { /* plugin disabling */ }
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
        int unreadTotal = 0;
        for (Map<String, Object> row : newestFirst) {
            if (!actionable(row, includeViews)) continue;
            String admin = String.valueOf(row.get("admin"));
            Map<String, Object> summary = admins.computeIfAbsent(admin, k -> SimplexAdminSuite.appMap("admin", k, "name", row.get("adminName"), "source", row.get("source"), "total", 0, "unread", 0, "last", row, "self", k.equals(self), "online", false, "sources", new TreeSet<String>()));
            summary.put("total", ((Integer) summary.get("total")) + 1);
            @SuppressWarnings("unchecked") Set<String> sources = (Set<String>) summary.get("sources"); sources.add(String.valueOf(row.get("source")));
            if (!admin.equals(self) && number(row.get("id")) > marker(viewer, admin)) { summary.put("unread", ((Integer) summary.get("unread")) + 1); unreadTotal++; }
        }
        for (Map<String, Object> summary : admins.values()) {
            String name = String.valueOf(summary.get("name"));
            Player online = Bukkit.getPlayerExact(name);
            summary.put("online", online != null);
            if (online != null) summary.put("uuid", online.getUniqueId().toString());
            else if (summary.get("last") instanceof Map<?, ?> last && last.get("adminUuid") != null) summary.put("uuid", last.get("adminUuid"));
            summary.put("sources", new ArrayList<>((Set<?>) summary.get("sources")));
        }
        List<Map<String, Object>> list = new ArrayList<>(admins.values());
        list.sort(Comparator.comparing((Map<String, Object> m) -> -((Integer) m.get("unread"))).thenComparing(m -> -number(((Map<?, ?>) m.get("last")).get("time"))));
        return SimplexAdminSuite.appMap("admins", list, "unread", unreadTotal, "total", rows.size(), "limit", LIMIT);
    }

    synchronized Object entries(String viewer, String self, String admin, String query, String category, int offset, boolean includeViews) {
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
        List<Map<String, Object>> out = new ArrayList<>();
        List<Map<String, Object>> newestFirst = new ArrayList<>(rows); Collections.reverse(newestFirst);
        for (Map<String, Object> row : newestFirst) {
            if (!admin.isBlank() && !admin.equals(row.get("admin"))) continue;
            if (!actionable(row, includeViews)) continue;
            if (!category.isBlank() && !category.equals(row.get("category"))) continue;
            if (!q.isBlank() && !DiscordBotManager.Json.stringify(row).toLowerCase(Locale.ROOT).contains(q)) continue;
            Map<String, Object> copy = new LinkedHashMap<>(row);
            String owner = String.valueOf(row.get("admin"));
            copy.put("unread", !owner.equals(self) && number(row.get("id")) > marker(viewer, owner));
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
