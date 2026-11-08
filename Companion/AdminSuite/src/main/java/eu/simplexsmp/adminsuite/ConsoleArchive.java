package eu.simplexsmp.adminsuite;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/** A separate archive, so deleting console history does not damage crash or player evidence. */
final class ConsoleArchive implements AutoCloseable {
    private static final long DAY = 86_400_000L;
    private static final Pattern CREDENTIAL = Pattern.compile(
        "(?i)(bearer\\s+|(?:api[-_ ]?key|token|password|passwort|secret)\\s*[:=]\\s*)[^\\s,;]+|\\b(?:sk-|ghp_|github_pat_)[A-Za-z0-9_-]{12,}");
    private final Connection db;
    private final ArrayBlockingQueue<DiagnosticsCenter.Line> pending = new ArrayBlockingQueue<>(8192);
    private final AtomicLong dropped = new AtomicLong();
    private volatile int days;
    private long lastPrune;

    ConsoleArchive(Path directory, int days) throws IOException, SQLException, ClassNotFoundException {
        Files.createDirectories(directory);
        Class.forName("org.sqlite.JDBC");
        this.days = Math.max(1, Math.min(days, 365));
        Path file = directory.resolve("console.sqlite");
        db = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try (Statement sql = db.createStatement()) {
            sql.execute("PRAGMA journal_mode=WAL");
            sql.execute("PRAGMA secure_delete=ON");
            sql.execute("CREATE TABLE IF NOT EXISTS console (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, level TEXT NOT NULL, rank INTEGER NOT NULL, source TEXT NOT NULL, text TEXT NOT NULL, search TEXT NOT NULL)");
            sql.execute("CREATE INDEX IF NOT EXISTS console_at ON console(at)");
        } catch (SQLException error) {
            db.close();
            throw error;
        }
        AppApiSecurity.protect(file);
    }

    void offer(DiagnosticsCenter.Line line) {
        if (!pending.offer(line)) dropped.incrementAndGet();
    }

    static String safeText(String text) {
        return CREDENTIAL.matcher(DiagnosticsCenter.redact(text)).replaceAll(match ->
            java.util.regex.Matcher.quoteReplacement(Objects.toString(match.group(1), "") + "[geschützt]"));
    }

    synchronized void flush() throws SQLException {
        List<DiagnosticsCenter.Line> batch = new ArrayList<>();
        pending.drainTo(batch);
        if (!batch.isEmpty()) {
            db.setAutoCommit(false);
            try (PreparedStatement insert = db.prepareStatement("INSERT INTO console(at,level,rank,source,text,search) VALUES(?,?,?,?,?,?)")) {
                for (DiagnosticsCenter.Line line : batch) {
                    String text = safeText(line.text());
                    insert.setLong(1, line.at());
                    insert.setString(2, line.level());
                    insert.setInt(3, DiagnosticsCenter.rank(line.level()));
                    insert.setString(4, line.source());
                    insert.setString(5, text);
                    insert.setString(6, (line.source() + " " + text).toLowerCase(Locale.ROOT));
                    insert.addBatch();
                }
                insert.executeBatch();
                db.commit();
            } catch (SQLException error) {
                db.rollback();
                for (DiagnosticsCenter.Line line : batch) offer(line);
                throw error;
            } finally {
                db.setAutoCommit(true);
            }
        }
        long now = System.currentTimeMillis();
        if (now - lastPrune >= 3_600_000L) {
            prune(now - DAY * days);
            lastPrune = now;
        }
    }

    synchronized Map<String, Object> read(Map<String, String> query, String tap, boolean manage) throws SQLException {
        flush();
        int limit = (int) Math.max(20, Math.min(integer(query.get("limit"), 200), 500));
        long now = System.currentTimeMillis();
        long since = Math.max(now - DAY * days, integer(query.get("from"), 0));
        long until = Math.min(now, integer(query.get("to"), now));
        long before = integer(query.get("before"), Long.MAX_VALUE);
        String term = SparkProfile.clip(query.getOrDefault("q", "").strip().toLowerCase(Locale.ROOT), 200);
        List<Map<String, Object>> items = new ArrayList<>();
        try (PreparedStatement sql = db.prepareStatement("SELECT id, at, level, source, text FROM console WHERE id < ? AND at BETWEEN ? AND ? AND rank >= ? AND instr(search, ?) > 0 ORDER BY id DESC LIMIT ?")) {
            sql.setLong(1, before);
            sql.setLong(2, since);
            sql.setLong(3, until);
            sql.setInt(4, DiagnosticsCenter.rank(query.get("level")));
            sql.setString(5, term);
            sql.setInt(6, limit + 1);
            try (ResultSet rows = sql.executeQuery()) {
                while (rows.next()) items.add(SimplexAdminSuite.appMap("id", Long.toString(rows.getLong(1)),
                    "at", rows.getLong(2), "level", rows.getString(3), "source", rows.getString(4), "text", rows.getString(5)));
            }
        }
        boolean more = items.size() > limit;
        if (more) items.remove(items.size() - 1);
        String next = more ? String.valueOf(items.get(items.size() - 1).get("id")) : "";
        Collections.reverse(items);
        return SimplexAdminSuite.appMap("items", items, "next", next, "days", days, "tap", tap, "manage", manage, "dropped", dropped.get());
    }

    synchronized int change(String operation, String id, int retention) throws SQLException {
        flush();
        int removed;
        switch (operation) {
            case "delete" -> {
                if (integer(id, 0) <= 0) throw new IllegalArgumentException("Konsolenzeile fehlt");
                try (PreparedStatement sql = db.prepareStatement("DELETE FROM console WHERE id = ?")) {
                    sql.setLong(1, Long.parseLong(id));
                    removed = sql.executeUpdate();
                }
            }
            case "clear" -> {
                try (Statement sql = db.createStatement()) { removed = sql.executeUpdate("DELETE FROM console"); }
            }
            case "retention" -> {
                days = Math.max(1, Math.min(retention, 365));
                removed = prune(System.currentTimeMillis() - days * DAY);
            }
            default -> throw new IllegalArgumentException("Unbekannte Archivaktion");
        }
        return removed;
    }

    private int prune(long before) throws SQLException {
        try (PreparedStatement sql = db.prepareStatement("DELETE FROM console WHERE at < ?")) {
            sql.setLong(1, before);
            return sql.executeUpdate();
        }
    }

    private static long integer(String value, long fallback) {
        try { return Long.parseLong(value); } catch (NumberFormatException e) { return fallback; }
    }

    @Override public synchronized void close() throws SQLException {
        try { flush(); } finally { db.close(); }
    }
}
