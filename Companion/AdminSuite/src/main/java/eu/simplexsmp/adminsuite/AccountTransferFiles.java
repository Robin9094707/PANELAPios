package eu.simplexsmp.adminsuite;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Stream;
import org.bukkit.configuration.file.YamlConfiguration;

/** Exact player paths, lossless gameplay NBT and a recoverable file journal. */
final class AccountTransferFiles {
    private static final int MAX_FILE = 8 * 1024 * 1024;
    private static final int MAX_TOTAL = 32 * 1024 * 1024;
    private static final int MAX_CHANGES = 160;
    // These roots belong to the login identity or to unrelated plugin services.
    private static final Set<String> IDENTITY = Set.of("bukkit", "BukkitValues", "Paper", "Spigot",
        "UUID", "UUIDMost", "UUIDLeast");

    record Account(UUID id, String name) {}
    record Change(Path path, byte[] before, byte[] after) {}
    record Plan(List<Change> changes, List<Map<String,Object>> rows, String revision) {}

    private final Path root;
    private final Path history;

    AccountTransferFiles(Path root, Path history) {
        this.root = root.toAbsolutePath().normalize();
        this.history = history.toAbsolutePath().normalize();
    }

    Plan plan(Collection<File> worlds, Account old, Account current, List<Path> homeFolders, Path simplexHomes, Path multiverse) throws Exception {
        Path server = root.toRealPath();
        var source = PlayerNbtLocator.find(server, worlds, old.id);
        var target = PlayerNbtLocator.find(server, worlds, current.id);
        if (source.isEmpty() || target.isEmpty())
            throw new IllegalArgumentException("Beide Accounts brauchen einen gespeicherten Minecraft-Spielstand. Einmal beitreten und wieder ausloggen.");
        List<Change> changes = new ArrayList<>();
        List<Map<String,Object>> rows = new ArrayList<>();
        LinkedHashSet<Path> worldFolders = new LinkedHashSet<>();
        worlds.stream().map(File::toPath).map(path -> path.toAbsolutePath().normalize()).forEach(worldFolders::add);
        LinkedHashSet<Path> playerDirectories = new LinkedHashSet<>();
        for (var entry : Stream.concat(source.stream(), target.stream()).toList()) {
            Path path = checked(server, server.resolve(entry.id().substring(1)));
            Path dir = path.getParent();
            playerDirectories.add(dir);
            worldFolders.add(dir.getParent().getFileName().toString().equals("players") ? dir.getParent().getParent() : dir.getParent());
        }
        for (Path dir : playerDirectories) {
            for (String suffix : List.of(".dat", ".dat_old")) {
                Path a = dir.resolve(old.id + suffix), b = dir.resolve(current.id + suffix);
                byte[] left = read(server, a), right = read(server, b);
                if (left == null && right == null) continue;
                if (suffix.equals(".dat")) {
                    if (left == null || right == null)
                        throw new IllegalArgumentException("Uneinheitliche Paper-Spielerdatenpfade. Beide Accounts müssen in denselben Spielerdatei-Ordnern liegen.");
                    var oldData = NbtTree.read(left).compound();
                    var newData = NbtTree.read(right).compound();
                    rows.add(SimplexAdminSuite.appMap("area", "Spielstand", "path", server.relativize(dir).toString(),
                        "old", inventorySummary(oldData), "new", inventorySummary(newData),
                        "oldItems", itemSummary(oldData), "newItems", itemSummary(newData)));
                }
                add(changes, a, left, gameplay(right, left, old.id), b, right, gameplay(left, right, current.id));
                checkSize(changes);
            }
        }
        List<File> roots = worldFolders.stream().map(Path::toFile).toList();
        for (String folder : List.of("advancements", "stats")) {
            for (File file : PlayerDataPaths.candidates(roots, old.id, folder, ".json")) {
                Path a = file.toPath().toAbsolutePath().normalize();
                if (!a.startsWith(server)) continue;
                Path b = a.resolveSibling(current.id + ".json");
                swapRaw(server, changes, rows, a, b, folder.equals("stats") ? "Statistiken" : "Fortschritte");
            }
        }
        for (Path dir : homeFolders) {
            Path a = dir.resolve(old.id + ".yml"), b = dir.resolve(current.id + ".yml");
            byte[] left = read(server, a), right = read(server, b);
            var oldYaml = yaml(left);
            var newYaml = yaml(right);
            var oldHomes = oldYaml.getConfigurationSection("homes");
            var newHomes = newYaml.getConfigurationSection("homes");
            if (oldHomes == null && newHomes == null) continue;
            var oldMap = homeMap(oldHomes);
            var newMap = homeMap(newHomes);
            // Only the homes subtree moves, never an entire plugin user profile.
            oldYaml.set("homes", newMap);
            newYaml.set("homes", oldMap);
            rows.add(SimplexAdminSuite.appMap("area", "Homes", "path", server.relativize(dir).toString(),
                "old", oldMap == null ? "Keine Homes" : oldMap.size() + " Homes",
                "new", newMap == null ? "Keine Homes" : newMap.size() + " Homes"));
            add(changes, a, left, oldYaml.saveToString().getBytes(StandardCharsets.UTF_8),
                b, right, newYaml.saveToString().getBytes(StandardCharsets.UTF_8));
            checkSize(changes);
        }
        if (simplexHomes != null) swapSimplexHomes(server, simplexHomes, old, current, changes, rows);
        if (multiverse != null) {
            if (!safeName(old.name) || !safeName(current.name))
                throw new IllegalArgumentException("Multiverse unterstützt für diesen Transfer nur sichere Spielerkontonamen.");
            for (String kind : List.of("groups", "worlds")) {
                Path parent = checked(server, multiverse.resolve(kind));
                if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) continue;
                try (Stream<Path> dirs = Files.list(parent)) {
                    for (Path dir : dirs.sorted().filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)).toList()) {
                        swapRaw(server, changes, rows, dir.resolve(old.name + ".json"),
                            dir.resolve(current.name + ".json"), "Multiverse-Inventories");
                    }
                }
            }
        }
        if (changes.isEmpty()) throw new IllegalArgumentException("Keine übertragbaren Änderungen gefunden.");
        MessageDigest md = sha();
        changes.stream().sorted(Comparator.comparing(c -> c.path.toString())).forEach(change -> {
            String state = server.relativize(change.path).toString() + "\0" + hash(change.before) + "\0" + hash(change.after) + "\n";
            md.update(state.getBytes(StandardCharsets.UTF_8));
        });
        return new Plan(List.copyOf(changes), List.copyOf(rows), HexFormat.of().formatHex(md.digest()));
    }

    private static void swapRaw(Path root, List<Change> changes, List<Map<String,Object>> rows, Path a, Path b, String area) throws IOException {
        byte[] left = read(root, a), right = read(root, b);
        if (left == null && right == null) return;
        add(changes, a, left, right, b, right, left);
        rows.add(SimplexAdminSuite.appMap("area", area, "path", root.relativize(a.getParent()).toString(),
            "old", left == null ? "Keine Datei" : "Vorhanden", "new", right == null ? "Keine Datei" : "Vorhanden"));
        checkSize(changes);
    }

    private static boolean safeName(String name) { return name.matches("[.A-Za-z0-9_]{1,32}") && !name.equals(".") && !name.equals(".."); }

    private static YamlConfiguration yaml(byte[] input) throws Exception {
        var yaml = new YamlConfiguration();
        if (input != null) yaml.loadFromString(new String(input, StandardCharsets.UTF_8));
        return yaml;
    }

    private static Map<String,Object> homeMap(org.bukkit.configuration.ConfigurationSection section) {
        if (section == null) return null;
        Map<String,Object> values = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            var nested = section.getConfigurationSection(key);
            values.put(key, nested == null ? section.get(key) : homeMap(nested));
        }
        return values;
    }

    private static void swapSimplexHomes(Path root, Path folder, Account old, Account current,
                                        List<Change> changes, List<Map<String,Object>> rows) throws Exception {
        Path indexPath = folder.resolve("player-homes-index.yml");
        byte[] indexBytes = read(root, indexPath);
        var index = yaml(indexBytes);
        Path oldPath = simplexHomesPath(root, folder, index, old);
        Path newPath = simplexHomesPath(root, folder, index, current);
        byte[] a = read(root, oldPath), b = read(root, newPath);
        var left = yaml(a);
        var right = yaml(b);
        var oldHomes = homesFor(left.getConfigurationSection("homes"), current);
        var newHomes = homesFor(right.getConfigurationSection("homes"), old);
        var oldFavorites = left.getStringList("meta.favorites");
        var oldOrder = left.getStringList("meta.order");
        left.set("homes", newHomes);
        left.set("meta.favorites", right.getStringList("meta.favorites"));
        left.set("meta.order", right.getStringList("meta.order"));
        right.set("homes", oldHomes);
        right.set("meta.favorites", oldFavorites);
        right.set("meta.order", oldOrder);

        Path legacyPath = folder.resolve("homes.yml");
        byte[] legacyBytes = read(root, legacyPath);
        var legacy = yaml(legacyBytes);
        var oldLegacy = homesFor(legacy.getConfigurationSection(old.id.toString()), current);
        var newLegacy = homesFor(legacy.getConfigurationSection(current.id.toString()), old);
        int oldCount = count(oldHomes) + count(oldLegacy), newCount = count(newHomes) + count(newLegacy);
        rows.add(SimplexAdminSuite.appMap("area", "SimplexHomes", "path", root.relativize(folder).toString(),
            "old", oldCount + " Homes", "new", newCount + " Homes"));
        if (oldCount == 0 && newCount == 0) return;
        if (oldHomes != null || newHomes != null) {
            add(changes, oldPath, a, yamlBytes(left), newPath, b, yamlBytes(right));
        }
        if (oldLegacy != null || newLegacy != null) {
            legacy.set(old.id.toString(), newLegacy);
            legacy.set(current.id.toString(), oldLegacy);
            changes.add(new Change(legacyPath, legacyBytes, yamlBytes(legacy)));
        }
        // The plugin uses this index as a cache for home counts. The account and name indexes stay tied to their UUID.
        index.set("players." + old.id + ".home-count", newCount);
        index.set("players." + current.id + ".home-count", oldCount);
        changes.add(new Change(indexPath, indexBytes, yamlBytes(index)));
        checkSize(changes);
    }

    private static Path simplexHomesPath(Path root, Path folder, YamlConfiguration index, Account account) throws Exception {
        String idPath = "players." + account.id;
        String name = index.getString(idPath + ".folder", "");
        if (!account.id.toString().equals(index.getString(idPath + ".uuid")) || name.isBlank()
            || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\"))
            throw new IllegalArgumentException("SimplexHomes-Index kennt den Spielerordner von " + account.name + " nicht.");
        Path path = checked(root, folder.resolve("player-homes").resolve(name));
        var profile = yaml(read(root, path.resolve("profile.yml")));
        if (!account.id.toString().equals(profile.getString("uuid")))
            throw new IllegalArgumentException("SimplexHomes-Profil und UUID stimmen für " + account.name + " nicht überein.");
        return path.resolve("homes.yml");
    }

    private static Map<String,Object> homesFor(org.bukkit.configuration.ConfigurationSection homes, Account target) {
        var values = homeMap(homes);
        if (values != null) values.replaceAll((name, value) -> {
            if (!(value instanceof Map<?,?> fields)) throw new IllegalArgumentException("Unsupported SimplexHomes entry: " + name);
            Map<Object,Object> copy = new LinkedHashMap<>(fields);
            if (copy.containsKey("owner-uuid")) copy.put("owner-uuid", target.id.toString());
            if (copy.containsKey("owner-name")) copy.put("owner-name", target.name);
            return copy;
        });
        return values;
    }

    private static int count(Map<String,Object> map) { return map == null ? 0 : map.size(); }

    private static byte[] yamlBytes(YamlConfiguration yaml) { return yaml.saveToString().getBytes(StandardCharsets.UTF_8); }

    private static void add(List<Change> changes, Path a, byte[] left, byte[] nextLeft, Path b, byte[] right, byte[] nextRight) {
        if (a.equals(b)) throw new IllegalArgumentException("Beide Accounts würden dieselbe Speicherdatei nutzen.");
        if (!Arrays.equals(left, nextLeft)) changes.add(new Change(a, left, nextLeft));
        if (!Arrays.equals(right, nextRight)) changes.add(new Change(b, right, nextRight));
    }

    static byte[] gameplay(byte[] from, byte[] to, UUID identity) throws IOException {
        if (from == null) return null;
        var incoming = NbtTree.read(from);
        var prior = to == null ? null : NbtTree.read(to);
        var tags = new LinkedHashMap<>(incoming.compound());
        for (String field : IDENTITY) {
            tags.remove(field);
            if (prior != null && prior.compound().containsKey(field)) tags.put(field, prior.compound().get(field));
        }
        long high = identity.getMostSignificantBits(), low = identity.getLeastSignificantBits();
        if (incoming.compound().containsKey("UUID") || tags.containsKey("UUID"))
            tags.put("UUID", new NbtTree.Tag(NbtTree.INT_ARRAY, new int[]{(int)(high >>> 32), (int) high, (int)(low >>> 32), (int) low}));
        if (incoming.compound().containsKey("UUIDMost") || tags.containsKey("UUIDMost")) {
            tags.put("UUIDMost", new NbtTree.Tag(NbtTree.LONG, high));
            tags.put("UUIDLeast", new NbtTree.Tag(NbtTree.LONG, low));
        }
        return NbtTree.write(new NbtTree.Root(incoming.name(), tags, incoming.gzip()));
    }

    private static String inventorySummary(Map<String,NbtTree.Tag> tags) {
        return stackCount(tags.get("Inventory")) + " belegte Inventarslots · "
            + stackCount(tags.get("EnderItems")) + " Endertruhenslots · XP-Level "
            + (tags.containsKey("XpLevel") ? tags.get("XpLevel").number() : 0);
    }

    private static int stackCount(NbtTree.Tag tag) { return tag == null || tag.list() == null ? 0 : tag.list().items().size(); }

    private static List<String> itemSummary(Map<String,NbtTree.Tag> tags) {
        List<String> rows = new ArrayList<>();
        for (var group : List.of("Inventory", "EnderItems")) {
            var list = tags.get(group);
            if (list == null || list.list() == null) continue;
            var amounts = new TreeMap<String,Long>();
            for (var element : list.list().items()) {
                var item = element.compound();
                if (item == null || !item.containsKey("id")) continue;
                String id = item.get("id").text();
                if (id.length() > 100 || id.isBlank()) continue;
                var count = item.getOrDefault("count", item.get("Count"));
                amounts.merge(id, (long) Math.max(1, count == null ? 1 : count.number()), Long::sum);
            }
            String where = group.equals("Inventory") ? "Inventar" : "Endertruhe";
            amounts.forEach((name, count) -> rows.add(where + " · " + count + " × " + name));
        }
        return rows;
    }

    private static void checkSize(List<Change> changes) throws IOException {
        long total = 0;
        Set<Path> seen = new HashSet<>();
        for (Change change : changes) {
            if (!seen.add(change.path)) throw new IOException("Ein Dateipfad käme doppelt im Transfer vor.");
            total += (change.before == null ? 0 : change.before.length) + (change.after == null ? 0 : change.after.length);
        }
        if (changes.size() > MAX_CHANGES || total > MAX_TOTAL) throw new IOException("Spielstand überschreitet das Transferlimit (160 Dateien / 32 MiB).");
    }

    private static Path checked(Path root, Path path) throws IOException {
        Path logical = path.toAbsolutePath().normalize();
        if (!logical.startsWith(root)) throw new IOException("Spielerdatei liegt außerhalb des Serverordners.");
        Path walk = root;
        for (Path part : root.relativize(logical)) {
            walk = walk.resolve(part);
            if (Files.isSymbolicLink(walk)) throw new IOException("Symbolischer Link im Datenpfad: " + root.relativize(walk));
        }
        return logical;
    }

    private static byte[] read(Path root, Path path) throws IOException {
        Path safe = checked(root, path);
        if (!Files.exists(safe, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.isRegularFile(safe, LinkOption.NOFOLLOW_LINKS) || Files.size(safe) > MAX_FILE)
            throw new IOException("Ungültige oder zu große Spielerdatei: " + root.relativize(safe));
        try (var file = Files.newInputStream(safe, LinkOption.NOFOLLOW_LINKS)) {
            byte[] contents = file.readNBytes(MAX_FILE + 1);
            if (contents.length > MAX_FILE) throw new IOException("Spielerdatei wurde während des Lesens zu groß.");
            return contents;
        }
    }

    Path backup(UUID id, Account old, Account current, Plan plan) throws IOException {
        Path directory = history.resolve(id.toString());
        Files.createDirectories(history);
        if (Files.getFileStore(history).supportsFileAttributeView("posix"))
            Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        else Files.createDirectory(directory);
        syncDirectory(history);
        var index = new Properties();
        index.setProperty("old", old.id.toString());
        index.setProperty("new", current.id.toString());
        index.setProperty("files", Integer.toString(plan.changes.size()));
        for (int i = 0; i < plan.changes.size(); i++) {
            Change file = plan.changes.get(i);
            index.setProperty("path." + i, root.toRealPath().relativize(file.path).toString());
            index.setProperty("before." + i, hash(file.before));
            index.setProperty("after." + i, hash(file.after));
            if (file.before != null) write(directory.resolve(i + ".before"), file.before);
        }
        var bytes = new java.io.ByteArrayOutputStream();
        index.store(bytes, "Simplex account transfer");
        write(directory.resolve("index.properties"), bytes.toByteArray());
        return directory;
    }

    void commit(Path directory, Plan plan) throws IOException {
        Path canonical = root.toRealPath();
        for (Change change : plan.changes) {
            if (!Arrays.equals(read(canonical, change.path), change.before))
                throw new IOException("Spielerdaten wurden geändert. Vorschau neu laden.");
        }
        write(directory.resolve("files.pending"), new byte[0]);
        try {
            for (Change change : plan.changes) update(canonical, change.path, change.after);
            write(directory.resolve("files.completed"), new byte[0]);
        } catch (IOException failure) {
            try { restore(directory); } catch (IOException recovery) { failure.addSuppressed(recovery); }
            throw failure;
        }
    }

    // Recovery is idempotent and refuses to overwrite a third, unrecognised version.
    void restore(Path directory) throws IOException {
        Path canonical = root.toRealPath();
        Properties index = index(directory);
        int count = Integer.parseInt(index.getProperty("files"));
        if (count < 0 || count > MAX_CHANGES) throw new IOException("Ungültiges Dateijournal.");
        List<Change> undo = new ArrayList<>();
        for (int i = count - 1; i >= 0; i--) {
            Path path = checked(canonical, canonical.resolve(index.getProperty("path." + i)));
            byte[] current = read(canonical, path);
            String have = hash(current), before = index.getProperty("before." + i);
            if (Objects.equals(have, before)) continue;
            if (!Objects.equals(have, index.getProperty("after." + i))) throw new IOException("Fremde Änderung an " + canonical.relativize(path));
            byte[] saved = before.equals("-") ? null : read(directory, directory.resolve(i + ".before"));
            if (!Objects.equals(hash(saved), before)) throw new IOException("Sicherung stimmt nicht mit Journal überein.");
            undo.add(new Change(path, current, saved));
        }
        for (Change change : undo) update(canonical, change.path, change.after);
        write(directory.resolve("files.restored"), new byte[0]);
    }

    List<Path> pending() throws IOException {
        if (!Files.isDirectory(history)) return List.of();
        try (var dirs = Files.list(history)) {
            return dirs.filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
                .filter(p -> needsRecovery(p, "files") || needsRecovery(p, "rights")).toList();
        }
    }

    static boolean needsRecovery(Path directory, String stage) {
        return directory != null && Files.exists(directory.resolve(stage + ".pending"))
            && !Files.exists(directory.resolve(stage + ".completed")) && !Files.exists(directory.resolve(stage + ".restored"));
    }

    static Properties index(Path directory) throws IOException {
        var index = new Properties();
        try (var reader = Files.newBufferedReader(directory.resolve("index.properties"), StandardCharsets.ISO_8859_1)) { index.load(reader); }
        return index;
    }

    static void write(Path target, byte[] data) throws IOException {
        Path dir = target.getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, ".account-transfer-", ".tmp");
        try {
            try (FileChannel out = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(data);
                while (buffer.hasRemaining()) out.write(buffer);
                out.force(true);
            }
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            syncDirectory(dir);
        } finally { Files.deleteIfExists(tmp); }
    }

    private static void update(Path root, Path path, byte[] data) throws IOException {
        checked(root, path);
        if (data == null) {
            if (Files.deleteIfExists(path)) syncDirectory(path.getParent());
        } else write(path, data);
    }

    private static void syncDirectory(Path directory) throws IOException {
        if (!Files.getFileStore(directory).supportsFileAttributeView("posix")) return;
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
    }

    static String hash(byte[] bytes) { return bytes == null ? "-" : HexFormat.of().formatHex(sha().digest(bytes)); }

    private static MessageDigest sha() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
