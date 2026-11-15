package eu.simplexsmp.adminsuite;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.function.Function;

/**
 * 10.20.0: LiteBans is switched off and its data is moved into the Suite's own ban system.
 *
 * <p>Step 1 (snapshot): every row of LiteBans' bans, mutes and warnings plus the name history is copied into
 * {@code litebans-migration/snapshot.yml}. Sources, in this order: the running LiteBans plugin (its own connection
 * pool), LiteBans' MySQL/MariaDB settings, or the H2 file {@code plugins/LiteBans/litebans.mv.db} (driver from the
 * LiteBans folder or a hash-pinned download from Maven Central). LiteBans' data is only ever read.
 *
 * <p>Step 2 (selection): active rows are grouped into cases (one LiteBans command = one case: main account plus the
 * accounts LiteBans banned with it). Every case gets a recommended decision; admins can change it in app or web panel.
 * Cases that depend on an excluded IP (default 82.165.45.159) are never imported automatically and their IP part is
 * never taken over.
 *
 * <p>Step 3 (apply): cases are applied in small batches on the server thread. The result of every case is stored
 * immediately, so an interrupted run (restart, crash, error) simply continues with the remaining cases.
 */
final class LiteBansMigration {
    static final String FEATURE = "litebans-migration-v1";
    static final String H2_V1 = "https://repo1.maven.org/maven2/com/h2database/h2/1.4.200/h2-1.4.200.jar";
    static final String H2_V1_SHA = "3ad9ac4b6aae9cd9d3ac1c447465e1ed06019b851b893dd6a8d76ddb6d85bca6";
    static final String H2_V2 = "https://repo1.maven.org/maven2/com/h2database/h2/2.2.224/h2-2.2.224.jar";
    static final String H2_V2_SHA = "b9d8f19358ada82a4f6eb5b174c6cfe320a375b5a9cb5a4fe456d623e6e55497";
    static final Set<String> BAN_DECISIONS = Set.of("account", "account+ip", "main", "ultra", "skip");
    static final Set<String> MUTE_DECISIONS = Set.of("mute", "skip");
    static final Set<String> WARN_DECISIONS = Set.of("warn", "skip");

    /** One LiteBans row (ban, mute or warning). */
    record Row(String table, long id, String uuid, String ip, String reason, String actor, long time, long until,
               boolean ipban, boolean silent, boolean active, String removedBy, String removedReason, long removedAt) {
        boolean permanent() { return until <= 0; }
        boolean expired(long now) { return until > 0 && until <= now; }
        boolean enforced(long now) { return active && !expired(now); }
    }

    /** Active LiteBans command: main account plus the accounts that were banned in the same command. */
    static final class Case {
        final String key, type;
        final Row main;
        final List<Row> alts = new ArrayList<>();
        Case(String key, String type, Row main) { this.key = key; this.type = type; this.main = main; }
        List<Row> rows() { List<Row> all = new ArrayList<>(); all.add(main); all.addAll(alts); return all; }
        long until() { return main.until; }
    }

    /** What the Suite does with one case; implemented by the plugin so this class stays free of profile internals. */
    interface Host {
        boolean ipExcluded(String ip);
        String knownName(UUID uuid);
        /** Applies one case; returns a short German result text. Throws on errors (the case is marked as failed). */
        String apply(Case c, String decision, List<Row> alts, Function<String, String> names, String actor) throws Exception;
        /** Stores inactive LiteBans rows as read-only history. Returns the number of new entries. */
        int archive(List<Row> rows, Function<String, String> names);
        void audit(String actor, String title, String detail);
    }

    private final Plugin plugin;
    private final Host host;
    private final Path folder, snapshotFile, stateFile;
    private final Object lock = new Object();
    private volatile Snapshot snapshot;
    private final YamlConfiguration state = new YamlConfiguration();
    private volatile boolean snapshotRunning, applyRunning;
    private volatile String lastError = "";
    private volatile int runDone, runTotal;
    private volatile boolean litebansDisabledByUs;

    static final class Snapshot {
        final String source; final long takenAt;
        final List<Row> bans, mutes, warnings;
        final Map<String, String> names;
        final Map<String, Integer> ipAccounts;
        Snapshot(String source, long takenAt, List<Row> bans, List<Row> mutes, List<Row> warnings, Map<String, String> names, Map<String, Integer> ipAccounts) {
            this.source = source; this.takenAt = takenAt; this.bans = bans; this.mutes = mutes; this.warnings = warnings; this.names = names; this.ipAccounts = ipAccounts;
        }
    }

    LiteBansMigration(Plugin plugin, Host host) {
        this.plugin = plugin; this.host = host;
        this.folder = plugin.getDataFolder().toPath().resolve("litebans-migration");
        this.snapshotFile = folder.resolve("snapshot.yml");
        this.stateFile = folder.resolve("state.yml");
        try { Files.createDirectories(folder); } catch (IOException ignored) { }
        loadState();
        try { snapshot = readSnapshotFile(); } catch (Exception error) { lastError = "Gespeicherter Snapshot unlesbar: " + error.getMessage(); }
    }

    // ------------------------------------------------------------------ LiteBans on/off

    static Plugin liteBans() { return Bukkit.getPluginManager().getPlugin("LiteBans"); }
    boolean disablePolicy() { return plugin.getConfig().getBoolean("litebans-migration.disable-litebans", true); }
    boolean liteBansDisabledByUs() { return litebansDisabledByUs; }

    /**
     * Called one tick after start: snapshot through the running LiteBans plugin (off the server thread), then switch
     * LiteBans off. LiteBans is switched off even if the snapshot fails; the snapshot can then be taken from the file.
     */
    void startup(Runnable afterDisable) {
        Plugin lite = liteBans();
        if (!disablePolicy()) return;
        if (lite == null || !lite.isEnabled()) { litebansDisabledByUs = lite != null; afterDisable.run(); return; }
        snapshotRunning = true;
        Thread worker = new Thread(() -> {
            try { Snapshot taken = readViaApi(lite); store(taken); }
            catch (Throwable error) { lastError = "Snapshot über LiteBans fehlgeschlagen: " + describe(error); plugin.getLogger().warning(lastError); }
            finally {
                snapshotRunning = false;
                Bukkit.getScheduler().runTask(plugin, () -> { disableLiteBans(); afterDisable.run(); });
            }
        }, "Simplex-LiteBans-Snapshot");
        worker.setDaemon(true); worker.start();
    }

    void disableLiteBans() {
        Plugin lite = liteBans();
        if (lite == null || !disablePolicy()) return;
        if (lite.isEnabled()) {
            try { Bukkit.getPluginManager().disablePlugin(lite); plugin.getLogger().info("LiteBans wurde deaktiviert. Banns, Mutes und Warnungen laufen jetzt über die Simplex Admin Suite (Migration: App → Bann-Zentrale)."); }
            catch (Throwable error) { plugin.getLogger().warning("LiteBans konnte nicht deaktiviert werden: " + describe(error)); return; }
        }
        litebansDisabledByUs = true;
    }

    /** Optional, explicit: rename the LiteBans JAR so it does not even load after the next restart. */
    String renameJar(String actor) throws IOException {
        Path plugins = plugin.getDataFolder().toPath().getParent();
        if (plugins == null) throw new IOException("Plugin-Ordner nicht gefunden");
        Path jar = null;
        try (var files = Files.list(plugins)) {
            for (Path p : files.toList()) {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (n.endsWith(".jar") && n.startsWith("litebans")) { jar = p; break; }
            }
        }
        if (jar == null) return "Keine LiteBans-JAR im Plugin-Ordner gefunden – nichts zu tun.";
        if (snapshot == null) throw new IllegalArgumentException("Zuerst einen Snapshot einlesen, damit keine Daten verloren gehen.");
        Path target = jar.resolveSibling(jar.getFileName() + ".disabled-by-simplex");
        Files.move(jar, target, StandardCopyOption.REPLACE_EXISTING);
        host.audit(actor, "LiteBans-JAR deaktiviert", jar.getFileName() + " → " + target.getFileName());
        return jar.getFileName() + " wurde in " + target.getFileName() + " umbenannt. Die LiteBans-Datenbank bleibt unverändert erhalten.";
    }

    // ------------------------------------------------------------------ snapshot reading

    /** Re-reads LiteBans data (app button). Runs off the server thread. */
    void refresh(String actor) {
        synchronized (lock) { if (snapshotRunning) throw new IllegalArgumentException("Snapshot läuft bereits."); snapshotRunning = true; }
        Thread worker = new Thread(() -> {
            try {
                Plugin lite = liteBans();
                Snapshot taken = lite != null && lite.isEnabled() ? readViaApi(lite) : readFromStorage();
                store(taken); lastError = "";
                host.audit(actor, "LiteBans-Snapshot eingelesen", taken.source + " · " + taken.bans.size() + " Bans, " + taken.mutes.size() + " Mutes, " + taken.warnings.size() + " Warnungen");
            } catch (Throwable error) { lastError = "Snapshot fehlgeschlagen: " + describe(error); plugin.getLogger().warning(lastError); }
            finally { snapshotRunning = false; }
        }, "Simplex-LiteBans-Snapshot");
        worker.setDaemon(true); worker.start();
    }

    private Snapshot readViaApi(Plugin lite) throws Exception {
        Class<?> type = Class.forName("litebans.api.Database", true, lite.getClass().getClassLoader());
        Object db = type.getMethod("get").invoke(null);
        return read("LiteBans-Plugin (API)", sql -> (PreparedStatement) type.getMethod("prepareStatement", String.class).invoke(db, sql), "{bans}", "{mutes}", "{warnings}", "{history}");
    }

    private Snapshot readFromStorage() throws Exception {
        File liteFolder = new File(plugin.getDataFolder().getParentFile(), "LiteBans");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new File(liteFolder, "config.yml"));
        String driver = config.getString("sql.driver", "H2").trim().toUpperCase(Locale.ROOT);
        String prefix = config.getString("sql.table_prefix", "litebans_").replaceAll("[^A-Za-z0-9_]", "");
        String user = config.getString("sql.username", ""), password = config.getString("sql.password", "");
        if (driver.equals("MYSQL") || driver.equals("MARIADB")) {
            String address = config.getString("sql.address", "localhost:3306"), database = config.getString("sql.database", "litebans");
            // The Suite ships the MariaDB client (also speaks MySQL); fall back to a server-provided MySQL driver.
            Properties props = new Properties(); props.setProperty("user", user); props.setProperty("password", password);
            String url = "jdbc:mariadb://" + address + "/" + database + "?useSsl=false&allowPublicKeyRetrieval=true";
            try {
                Driver mariadb = (Driver) Class.forName("org.mariadb.jdbc.Driver", true, LiteBansMigration.class.getClassLoader()).getDeclaredConstructor().newInstance();
                try (Connection c = mariadb.connect(url, props)) { return readConnection("LiteBans-" + driver + " (" + address + ")", c, prefix); }
            } catch (Exception mariadbError) {
                try (Connection c = DriverManager.getConnection("jdbc:mysql://" + address + "/" + database + "?useSSL=false&allowPublicKeyRetrieval=true", user, password)) { return readConnection("LiteBans-" + driver + " (" + address + ")", c, prefix); }
                catch (Exception mysqlError) { throw new IOException("LiteBans-Datenbank " + address + " nicht erreichbar: " + describe(mariadbError)); }
            }
        }
        String configured = plugin.getConfig().getString("litebans-migration.h2-file", "");
        File file = configured.isBlank() ? new File(liteFolder, "litebans.mv.db") : new File(configured);
        if (!file.isFile()) throw new IOException("Keine LiteBans-Datenbank gefunden (" + file.getPath() + "). LiteBans einmal starten oder den Pfad in litebans-migration.h2-file setzen.");
        boolean modern = h2Format(file.toPath()) >= 2;
        Driver h2 = h2Driver(liteFolder.toPath(), modern, folder);
        String base = file.getAbsolutePath().replaceFirst("\\.mv\\.db$", "");
        String url = "jdbc:h2:" + base + ";ACCESS_MODE_DATA=r;IFEXISTS=TRUE" + (modern ? "" : ";MV_STORE=TRUE");
        Exception last = null;
        for (String[] login : List.of(new String[]{user, password}, new String[]{"", ""}, new String[]{"sa", ""})) {
            Properties props = new Properties(); props.setProperty("user", login[0]); props.setProperty("password", login[1]);
            try (Connection c = h2.connect(url, props)) {
                if (c == null) throw new IOException("H2-Treiber lehnt die Adresse ab");
                return readConnection("LiteBans-H2-Datei (" + file.getName() + ")", c, prefix);
            } catch (Exception error) { last = error; String m = String.valueOf(error.getMessage()); if (m.contains("Locked by another process") || m.contains("already in use")) throw new IOException("Die LiteBans-Datei ist gesperrt. LiteBans läuft noch – Server neu starten, dann erneut versuchen.", error); }
        }
        throw last == null ? new IOException("H2-Anmeldung fehlgeschlagen") : last;
    }

    /** Reads the MVStore header ("format:1" = H2 1.4.x, "format:2/3" = H2 2.x). */
    static int h2Format(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] head = in.readNBytes(256);
            String text = new String(head, StandardCharsets.ISO_8859_1);
            int at = text.indexOf("format:");
            if (at < 0) return 1;
            int end = at + 7; while (end < text.length() && Character.isDigit(text.charAt(end))) end++;
            return end > at + 7 ? Integer.parseInt(text.substring(at + 7, end)) : 1;
        } catch (Exception e) { return 1; }
    }

    static Driver h2Driver(Path liteFolder, boolean modern, Path downloads) throws Exception {
        List<Path> candidates = new ArrayList<>();
        if (Files.isDirectory(liteFolder)) try (var walk = Files.walk(liteFolder, 3)) {
            walk.filter(p -> { String n = p.getFileName().toString().toLowerCase(Locale.ROOT); return n.startsWith("h2") && n.endsWith(".jar"); }).forEach(candidates::add);
        }
        candidates.removeIf(p -> { String n = p.getFileName().toString(); return modern ? n.contains("1.4") : !n.contains("1.4"); });
        Path jar = candidates.isEmpty() ? downloadH2(modern, downloads) : candidates.get(0);
        URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, LiteBansMigration.class.getClassLoader().getParent());
        return (Driver) Class.forName("org.h2.Driver", true, loader).getDeclaredConstructor().newInstance();
    }

    static Path downloadH2(boolean modern, Path folder) throws Exception {
        String url = modern ? H2_V2 : H2_V1, sha = modern ? H2_V2_SHA : H2_V1_SHA;
        Path target = folder.resolve(modern ? "h2-2.2.224.jar" : "h2-1.4.200.jar");
        if (Files.isRegularFile(target) && sha.equals(sha256(Files.readAllBytes(target)))) return target;
        byte[] data;
        try (InputStream in = URI.create(url).toURL().openStream()) { data = in.readNBytes(8 * 1024 * 1024); }
        if (!sha.equals(sha256(data))) throw new IOException("H2-Treiber hat eine falsche Prüfsumme – Download verworfen.");
        Files.createDirectories(folder); Path temp = Files.createTempFile(folder, "h2", ".tmp"); Files.write(temp, data);
        try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); } catch (AtomicMoveNotSupportedException e) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
        return target;
    }

    static String sha256(byte[] data) throws Exception {
        StringBuilder out = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(data)) out.append(String.format(Locale.ROOT, "%02x", b));
        return out.toString();
    }

    interface Statements { PreparedStatement prepare(String sql) throws Exception; }

    static Snapshot readConnection(String source, Connection c, String prefix) throws Exception {
        return read(source, c::prepareStatement, prefix + "bans", prefix + "mutes", prefix + "warnings", prefix + "history");
    }

    static Snapshot read(String source, Statements db, String bans, String mutes, String warnings, String history) throws Exception {
        List<Row> b = rows(db, bans, "ban"), m = rows(db, mutes, "mute"), w = rows(db, warnings, "warning");
        Map<String, String> names = new HashMap<>(); Map<String, Long> nameAt = new HashMap<>();
        Map<String, Set<String>> ipUsers = new HashMap<>();
        try (PreparedStatement s = db.prepare("SELECT uuid,name,ip,date FROM " + history)) {
            s.setQueryTimeout(30);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    String uuid = clean(r.getString("uuid"), 40).toLowerCase(Locale.ROOT), name = clean(r.getString("name"), 40), ip = clean(r.getString("ip"), 64);
                    long at = 0; try { java.sql.Timestamp t = r.getTimestamp("date"); if (t != null) at = t.getTime(); } catch (Exception ignored) { }
                    if (!uuid.isEmpty() && !name.isEmpty() && at >= nameAt.getOrDefault(uuid, Long.MIN_VALUE)) { names.put(uuid, name); nameAt.put(uuid, at); }
                    if (!uuid.isEmpty() && !ip.isEmpty() && !ip.equals("#")) ipUsers.computeIfAbsent(ip, k -> new HashSet<>()).add(uuid);
                }
            }
        }
        Map<String, Integer> ipAccounts = new HashMap<>();
        Set<String> relevant = new HashSet<>(); for (Row r : b) relevant.add(r.ip()); for (Row r : m) relevant.add(r.ip());
        for (String ip : relevant) if (ipUsers.containsKey(ip)) ipAccounts.put(ip, ipUsers.get(ip).size());
        return new Snapshot(source, System.currentTimeMillis(), b, m, w, names, ipAccounts);
    }

    private static List<Row> rows(Statements db, String table, String type) throws Exception {
        List<Row> out = new ArrayList<>();
        try (PreparedStatement s = db.prepare("SELECT * FROM " + table + " ORDER BY id")) {
            s.setQueryTimeout(30);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    long removedAt = 0; try { java.sql.Timestamp t = r.getTimestamp("removed_by_date"); if (t != null) removedAt = t.getTime(); } catch (Exception ignored) { }
                    out.add(new Row(type, r.getLong("id"), clean(r.getString("uuid"), 40).toLowerCase(Locale.ROOT), clean(r.getString("ip"), 64), clean(r.getString("reason"), 240),
                            clean(r.getString("banned_by_name"), 40), r.getLong("time"), r.getLong("until"), bool(r, "ipban"), bool(r, "silent"), bool(r, "active"),
                            clean(r.getString("removed_by_name"), 40), clean(r.getString("removed_by_reason"), 240), removedAt));
                    if (out.size() >= 50_000) break;
                }
            }
        }
        return out;
    }

    private static boolean bool(ResultSet r, String column) { try { return r.getBoolean(column); } catch (Exception e) { return false; } }
    static String clean(String value, int max) {
        if (value == null) return "";
        String v = value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim();
        if (v.equalsIgnoreCase("null")) return "";
        return v.length() > max ? v.substring(0, max) : v;
    }

    // ------------------------------------------------------------------ snapshot persistence

    private void store(Snapshot s) throws IOException {
        YamlConfiguration y = new YamlConfiguration();
        y.set("schema", 1); y.set("source", s.source); y.set("taken-at", s.takenAt);
        y.set("bans", encode(s.bans)); y.set("mutes", encode(s.mutes)); y.set("warnings", encode(s.warnings));
        y.createSection("names", new TreeMap<>(s.names)); y.createSection("ip-accounts", new TreeMap<>(s.ipAccounts));
        write(snapshotFile, y.saveToString());
        snapshot = s;
    }

    static List<String> encode(List<Row> rows) {
        List<String> out = new ArrayList<>();
        for (Row r : rows) out.add(String.join("\t", Long.toString(r.id()), r.uuid(), r.ip(), r.reason(), r.actor(), Long.toString(r.time()), Long.toString(r.until()),
                Boolean.toString(r.ipban()), Boolean.toString(r.silent()), Boolean.toString(r.active()), r.removedBy(), r.removedReason(), Long.toString(r.removedAt())));
        return out;
    }

    /** Fields are tab separated (clean() removes tabs from every value); older test files used U+001F stored as YAML binary. */
    static List<Row> decode(List<?> lines, String type) {
        List<Row> out = new ArrayList<>();
        for (Object raw : lines) {
            String line = raw instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(raw);
            String[] f = line.split(line.indexOf('\t') >= 0 ? "\t" : "\u001f", -1); if (f.length < 13) continue;
            try { out.add(new Row(type, Long.parseLong(f[0]), f[1], f[2], f[3], f[4], Long.parseLong(f[5]), Long.parseLong(f[6]), Boolean.parseBoolean(f[7]), Boolean.parseBoolean(f[8]), Boolean.parseBoolean(f[9]), f[10], f[11], Long.parseLong(f[12]))); }
            catch (NumberFormatException ignored) { }
        }
        return out;
    }

    private Snapshot readSnapshotFile() throws Exception {
        if (!Files.isRegularFile(snapshotFile)) return null;
        YamlConfiguration y = new YamlConfiguration(); y.load(snapshotFile.toFile());
        Map<String, String> names = new HashMap<>(); var n = y.getConfigurationSection("names"); if (n != null) for (String k : n.getKeys(false)) names.put(k, n.getString(k, ""));
        Map<String, Integer> ips = new HashMap<>(); var i = y.getConfigurationSection("ip-accounts"); if (i != null) for (String k : i.getKeys(false)) ips.put(k, i.getInt(k));
        return new Snapshot(y.getString("source", "?"), y.getLong("taken-at"), decode(y.getList("bans", List.of()), "ban"), decode(y.getList("mutes", List.of()), "mute"), decode(y.getList("warnings", List.of()), "warning"), names, ips);
    }

    private void loadState() {
        if (!Files.isRegularFile(stateFile)) return;
        try { state.load(stateFile.toFile()); } catch (Exception error) { lastError = "Migrationsstatus unlesbar (Datei bleibt unverändert): " + error.getMessage(); }
    }

    private void saveState() {
        try { write(stateFile, state.saveToString()); } catch (IOException error) { plugin.getLogger().warning("Migrationsstatus konnte nicht gespeichert werden: " + error.getMessage()); }
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temp, text);
            try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }

    // ------------------------------------------------------------------ grouping (pure, unit-tested)

    /** Groups enforced rows into cases: same table, time, actor and reason = one LiteBans command. */
    static List<Case> cases(List<Row> rows, long now) {
        Map<String, List<Row>> groups = new LinkedHashMap<>();
        for (Row r : rows) {
            if (!r.enforced(now) || r.uuid().isEmpty() || r.uuid().equals("#")) continue;
            String key = r.table() + "-" + r.time() + "-" + Integer.toHexString((r.actor().toLowerCase(Locale.ROOT) + "|" + r.reason().toLowerCase(Locale.ROOT)).hashCode());
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }
        List<Case> out = new ArrayList<>();
        for (var e : groups.entrySet()) {
            List<Row> list = new ArrayList<>(e.getValue());
            list.sort(Comparator.comparing((Row r) -> !r.ipban()).thenComparingLong(Row::id));
            Row main = list.get(0);
            Case c = new Case(e.getKey() + "-" + main.id(), main.table(), main);
            Set<String> seen = new HashSet<>(); seen.add(main.uuid());
            for (Row r : list.subList(1, list.size())) if (seen.add(r.uuid())) c.alts.add(r);
            out.add(c);
        }
        out.sort(Comparator.comparingLong((Case c) -> c.main.time()).reversed());
        return out;
    }

    /** Recommended decision: never anything that depends on an excluded IP; otherwise accounts (no IP blocks). */
    static String recommended(Case c, java.util.function.Predicate<String> excluded) {
        if (c.type.equals("mute")) return excluded.test(c.main.ip()) && c.main.ipban() ? "skip" : "mute";
        if (c.type.equals("warning")) return "warn";
        if (excludedCase(c, excluded)) return "skip";
        return "account";
    }

    /**
     * A case is "excluded" when its main account was banned on an excluded (shared) IP. Linked accounts on an excluded
     * IP are never imported as alts (see {@link #importedAlts}) – LiteBans only linked them through the shared IP.
     */
    static boolean excludedCase(Case c, java.util.function.Predicate<String> excluded) { return excluded.test(c.main.ip()); }

    static List<Row> importedAlts(Case c, java.util.function.Predicate<String> excluded) {
        List<Row> out = new ArrayList<>(); for (Row r : c.alts) if (!excluded.test(r.ip())) out.add(r); return out;
    }

    static Set<String> allowed(Case c) { return c.type.equals("mute") ? MUTE_DECISIONS : c.type.equals("warning") ? WARN_DECISIONS : BAN_DECISIONS; }

    // ------------------------------------------------------------------ app data

    String name(String uuid) {
        Snapshot s = snapshot;
        try { String known = host.knownName(UUID.fromString(uuid)); if (known != null && !known.isBlank()) return known; } catch (IllegalArgumentException ignored) { }
        String n = s == null ? null : s.names.get(uuid.toLowerCase(Locale.ROOT));
        return n == null || n.isBlank() ? uuid.substring(0, Math.min(8, uuid.length())) + "…" : n;
    }

    List<Case> currentCases() {
        Snapshot s = snapshot; if (s == null) return List.of();
        long now = System.currentTimeMillis();
        List<Row> all = new ArrayList<>(s.bans); all.addAll(s.mutes); all.addAll(s.warnings);
        return cases(all, now);
    }

    /** Per-account choice inside a case: default = take over, except accounts on an excluded IP. */
    boolean accountIncluded(Case c, Row r) {
        String chosen = state.getString("cases." + c.key + ".accounts." + r.uuid(), "");
        if (chosen.equals("on")) return !host.ipExcluded(r.ip()) || r != c.main;
        if (chosen.equals("off")) return false;
        return r == c.main || !host.ipExcluded(r.ip());
    }
    List<Row> chosenAlts(Case c) { List<Row> out = new ArrayList<>(); for (Row r : c.alts) if (accountIncluded(c, r)) out.add(r); return out; }

    String decision(Case c) {
        String chosen = state.getString("cases." + c.key + ".decision", "");
        return allowed(c).contains(chosen) ? chosen : recommended(c, host::ipExcluded);
    }

    Map<String, Object> data() {
        Snapshot s = snapshot;
        Plugin lite = liteBans();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("litebansInstalled", lite != null);
        out.put("litebansEnabled", lite != null && lite.isEnabled());
        out.put("disabledBySuite", litebansDisabledByUs);
        out.put("disablePolicy", disablePolicy());
        out.put("snapshotRunning", snapshotRunning);
        out.put("running", applyRunning);
        out.put("progress", SimplexAdminSuite.appMap("done", runDone, "total", runTotal));
        out.put("error", lastError);
        out.put("historyImported", state.getBoolean("history-imported", false));
        out.put("lastRun", SimplexAdminSuite.appMap("started", state.getLong("run.started", 0L), "finished", state.getLong("run.finished", 0L), "by", state.getString("run.by", ""), "interrupted", state.getBoolean("run.interrupted", false)));
        if (s == null) { out.put("snapshot", null); out.put("cases", List.of()); out.put("counts", Map.of()); return out; }
        out.put("snapshot", SimplexAdminSuite.appMap("source", s.source, "takenAt", s.takenAt, "bans", s.bans.size(), "mutes", s.mutes.size(), "warnings", s.warnings.size(), "names", s.names.size()));
        List<Map<String, Object>> rows = new ArrayList<>();
        int pending = 0, done = 0, failed = 0, skipped = 0, excludedCount = 0;
        for (Case c : currentCases()) {
            String base = "cases." + c.key + ".";
            String status = state.getString(base + "status", "pending");
            String decision = decision(c);
            boolean excluded = excludedCase(c, host::ipExcluded);
            if (excluded) excludedCount++;
            switch (status) { case "done" -> done++; case "failed" -> failed++; case "skipped" -> skipped++; default -> pending++; }
            List<Map<String, Object>> accounts = new ArrayList<>();
            for (Row r : c.rows()) accounts.add(SimplexAdminSuite.appMap("uuid", r.uuid(), "name", name(r.uuid()), "role", r == c.main ? "main" : "alt", "ip", r.ip(),
                    "ipExcluded", host.ipExcluded(r.ip()), "ipAccounts", s.ipAccounts.getOrDefault(r.ip(), 0), "ipban", r.ipban(), "id", r.id(),
                    "imported", r == c.main ? !decision.equals("skip") : !decision.equals("skip") && !decision.equals("main") && accountIncluded(c, r),
                    "choice", state.getString(base + "accounts." + r.uuid(), "")));
            rows.add(SimplexAdminSuite.appMap("key", c.key, "type", c.type, "id", c.main.id(), "reason", c.main.reason(), "actor", c.main.actor(), "time", c.main.time(),
                    "until", c.main.until() <= 0 ? 0L : c.main.until(), "permanent", c.main.permanent(), "ipban", c.main.ipban(), "silent", c.main.silent(),
                    "ip", c.main.ip(), "ipExcluded", host.ipExcluded(c.main.ip()), "ipAccounts", s.ipAccounts.getOrDefault(c.main.ip(), 0), "excluded", excluded,
                    "decision", decision, "recommended", recommended(c, host::ipExcluded), "choices", new ArrayList<>(new TreeSet<>(allowed(c))), "status", status,
                    "message", state.getString(base + "message", ""), "appliedAt", state.getLong(base + "at", 0L), "appliedBy", state.getString(base + "by", ""),
                    "accounts", accounts));
        }
        out.put("cases", rows);
        long now = System.currentTimeMillis();
        int expired = 0, inactive = 0; for (Row r : s.bans) { if (!r.active()) inactive++; else if (r.expired(now)) expired++; }
        out.put("counts", SimplexAdminSuite.appMap("cases", rows.size(), "pending", pending, "done", done, "failed", failed, "skipped", skipped, "excluded", excludedCount,
                "historyRows", inactive + expired, "activeBans", s.bans.stream().filter(r -> r.enforced(now)).count(), "activeMutes", s.mutes.stream().filter(r -> r.enforced(now)).count()));
        return out;
    }

    // ------------------------------------------------------------------ app actions

    Map<String, Object> apply(String actor, Map<String, Object> body) throws Exception {
        String op = String.valueOf(body.getOrDefault("operation", ""));
        switch (op) {
            case "snapshot" -> { refresh(actor); return result("Snapshot wird eingelesen", "LiteBans-Daten werden gelesen. Die Liste aktualisiert sich gleich."); }
            case "decide" -> {
                String raw = String.valueOf(body.getOrDefault("decisions", ""));
                Map<String, Case> byKey = new HashMap<>(); for (Case c : currentCases()) byKey.put(c.key, c);
                int changed = 0;
                for (String part : raw.split("[,;\\n]")) {
                    int at = part.lastIndexOf('='); if (at <= 0) continue;
                    String key = part.substring(0, at).trim(), value = part.substring(at + 1).trim();
                    int bar = key.indexOf('|');
                    if (bar > 0) {
                        // "caseKey|uuid=on|off|auto": take over or leave out one linked account of a case.
                        Case c = byKey.get(key.substring(0, bar)); String uuid = key.substring(bar + 1).toLowerCase(Locale.ROOT);
                        if (c == null || c.alts.stream().noneMatch(r -> r.uuid().equals(uuid))) continue;
                        if (!Set.of("on", "off", "auto").contains(value)) throw new IllegalArgumentException("Ungültige Kontoauswahl: " + value);
                        state.set("cases." + c.key + ".accounts." + uuid, value.equals("auto") ? null : value);
                        if (!"done".equals(state.getString("cases." + c.key + ".status"))) state.set("cases." + c.key + ".status", "pending");
                        changed++; continue;
                    }
                    Case c = byKey.get(key); if (c == null) continue;
                    if (!allowed(c).contains(value)) throw new IllegalArgumentException("Ungültige Auswahl für " + key + ": " + value);
                    if (value.equals("account+ip") && excludedCase(c, host::ipExcluded)) throw new IllegalArgumentException("Die IP " + c.main.ip() + " ist ausgeschlossen und wird nie gesperrt.");
                    state.set("cases." + key + ".decision", value);
                    if (body.containsKey("reset") && Boolean.parseBoolean(String.valueOf(body.get("reset"))) || "failed".equals(state.getString("cases." + key + ".status")) || "skipped".equals(state.getString("cases." + key + ".status")))
                        state.set("cases." + key + ".status", "pending");
                    changed++;
                }
                saveState();
                return result("Auswahl gespeichert", changed + " Fall/Fälle geändert. Mit „Migration starten“ übernehmen.");
            }
            case "run" -> { start(actor, Boolean.parseBoolean(String.valueOf(body.getOrDefault("retryFailed", "true")))); return result("Migration gestartet", "Die Fälle werden nacheinander übernommen. Bricht etwas ab, einfach erneut starten – erledigte Fälle bleiben erledigt."); }
            case "disable" -> { disableLiteBans(); return result("LiteBans deaktiviert", "LiteBans ist aus; alle Sperren laufen über die Suite."); }
            case "rename-jar" -> { return result("LiteBans-JAR", renameJar(actor)); }
            default -> throw new IllegalArgumentException("Unbekannte Migrationsaktion");
        }
    }

    private static Map<String, Object> result(String title, String description) { return SimplexAdminSuite.appMap("ok", true, "title", title, "description", description); }

    /** Applies pending cases in batches of five per tick; every result is saved immediately. */
    void start(String actor, boolean retryFailed) {
        synchronized (lock) {
            if (applyRunning) throw new IllegalArgumentException("Die Migration läuft bereits.");
            if (snapshot == null) throw new IllegalArgumentException("Noch kein LiteBans-Snapshot vorhanden.");
            applyRunning = true;
        }
        List<Case> queue = new ArrayList<>();
        for (Case c : currentCases()) {
            String status = state.getString("cases." + c.key + ".status", "pending");
            if (status.equals("done")) continue;
            if (status.equals("failed") && !retryFailed) continue;
            queue.add(c);
        }
        // Main accounts of bans first, then mutes, then warnings: an alt row never overwrites a main ban.
        queue.sort(Comparator.comparingInt((Case c) -> c.type.equals("ban") ? 0 : c.type.equals("mute") ? 1 : 2));
        runDone = 0; runTotal = queue.size();
        state.set("run.started", System.currentTimeMillis()); state.set("run.by", actor); state.set("run.interrupted", true); state.set("run.finished", 0L); saveState();
        Function<String, String> names = this::name;
        if (!state.getBoolean("history-imported", false)) {
            Snapshot s = snapshot; List<Row> history = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (Row r : s.bans) if (!r.enforced(now)) history.add(r);
            for (Row r : s.mutes) if (!r.enforced(now)) history.add(r);
            for (Row r : s.warnings) if (!r.enforced(now)) history.add(r);
            try { int added = host.archive(history, names); state.set("history-imported", true); state.set("history-count", added); saveState(); }
            catch (Exception error) { plugin.getLogger().warning("LiteBans-Verlauf konnte nicht übernommen werden: " + describe(error)); }
        }
        Iterator<Case> it = queue.iterator();
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            try {
                for (int i = 0; i < 5 && it.hasNext(); i++) {
                    Case c = it.next(); String base = "cases." + c.key + ".";
                    String decision = decision(c);
                    try {
                        if (decision.equals("skip")) { state.set(base + "status", "skipped"); state.set(base + "message", excludedCase(c, host::ipExcluded) ? "Nicht übernommen – Sperre hing an einer ausgeschlossenen IP." : "Nicht übernommen (Auswahl)."); }
                        else { String msg = host.apply(c, decision, decision.equals("main") ? List.of() : chosenAlts(c), names, actor); state.set(base + "status", "done"); state.set(base + "message", msg); }
                    } catch (Throwable error) { state.set(base + "status", "failed"); state.set(base + "message", describe(error)); }
                    state.set(base + "at", System.currentTimeMillis()); state.set(base + "by", actor); state.set(base + "decision-used", decision);
                    runDone++;
                    saveState();
                }
                if (!it.hasNext()) {
                    task.cancel(); applyRunning = false;
                    state.set("run.finished", System.currentTimeMillis()); state.set("run.interrupted", false); saveState();
                    host.audit(actor, "LiteBans-Migration abgeschlossen", runDone + " Fälle verarbeitet");
                }
            } catch (Throwable fatal) { task.cancel(); applyRunning = false; lastError = "Migration unterbrochen: " + describe(fatal); saveState(); }
        }, 1L, 2L);
    }

    void stop() { applyRunning = false; }
    boolean hasSnapshot() { return snapshot != null; }
    int pendingCount() {
        int n = 0;
        for (Case c : currentCases()) { String st = state.getString("cases." + c.key + ".status", "pending"); if (st.equals("pending") || st.equals("failed")) n++; }
        return n;
    }

    static String describe(Throwable error) {
        Throwable e = error;
        while ((e instanceof java.lang.reflect.InvocationTargetException || e instanceof java.util.concurrent.ExecutionException) && e.getCause() != null) e = e.getCause();
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : clean(m, 300);
    }
}
