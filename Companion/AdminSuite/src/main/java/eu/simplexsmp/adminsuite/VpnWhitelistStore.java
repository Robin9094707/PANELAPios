package eu.simplexsmp.adminsuite;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

final class VpnWhitelistStore {
    static final class Entry {
        private final UUID uuid;
        private final String name;
        private final long addedAt;
        private final String addedBy;

        Entry(UUID uuid, String name, long addedAt, String addedBy) {
            this.uuid = uuid;
            this.name = name == null ? "" : name.trim();
            this.addedAt = addedAt;
            this.addedBy = addedBy == null ? "Unbekannt" : addedBy;
        }

        UUID uuid() { return uuid; }
        String name() { return name; }
        long addedAt() { return addedAt; }
        String addedBy() { return addedBy; }
        String display() { return name.isBlank() ? (uuid == null ? "Unbekannt" : uuid.toString()) : name; }
        String key() { return uuid == null ? "name:" + name.toLowerCase(Locale.ROOT) : "uuid:" + uuid.toString(); }
    }

    private final File file;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    VpnWhitelistStore(File file) {
        this.file = file;
    }

    synchronized void load() {
        entries.clear();
        if (!file.exists()) {
            save();
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isBlank() || line.startsWith("#")) continue;
                Entry entry = decode(line);
                if (entry != null && (!entry.name().isBlank() || entry.uuid() != null)) entries.put(entry.key(), entry);
            }
        } catch (Exception ignored) { }
    }

    synchronized boolean isWhitelisted(UUID uuid, String name) {
        if (uuid != null && entries.containsKey("uuid:" + uuid)) return true;
        return name != null && entries.containsKey("name:" + name.trim().toLowerCase(Locale.ROOT));
    }

    synchronized boolean add(UUID uuid, String name, String actor) {
        if (uuid == null && (name == null || name.isBlank())) return false;
        Entry entry = new Entry(uuid, name, System.currentTimeMillis(), actor);
        Entry previous = entries.put(entry.key(), entry);
        if (previous == null || !previous.name().equals(entry.name()) || !previous.addedBy().equals(entry.addedBy())) save();
        return previous == null;
    }

    synchronized boolean remove(UUID uuid, String name) {
        boolean removed = false;
        if (uuid != null) removed |= entries.remove("uuid:" + uuid) != null;
        if (name != null && !name.isBlank()) removed |= entries.remove("name:" + name.trim().toLowerCase(Locale.ROOT)) != null;
        if (removed) save();
        return removed;
    }

    synchronized boolean removeQuery(String query) {
        if (query == null || query.isBlank()) return false;
        String clean = query.trim();
        UUID uuid = null;
        try { uuid = UUID.fromString(clean); } catch (Exception ignored) { }
        if (uuid != null && remove(uuid, null)) return true;
        String low = clean.toLowerCase(Locale.ROOT);
        String matched = null;
        for (Map.Entry<String, Entry> candidate : entries.entrySet()) {
            Entry value = candidate.getValue();
            if (value.display().equalsIgnoreCase(clean) || value.display().toLowerCase(Locale.ROOT).startsWith(low)) {
                matched = candidate.getKey();
                break;
            }
        }
        if (matched == null) return false;
        entries.remove(matched);
        save();
        return true;
    }

    synchronized List<Entry> entries() {
        List<Entry> result = new ArrayList<>(entries.values());
        result.sort(Comparator.comparing(Entry::display, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    synchronized int importLegacy(File legacyWhitelist) {
        if (legacyWhitelist == null || !legacyWhitelist.isFile()) return 0;
        int imported = 0;
        try {
            for (String raw : Files.readAllLines(legacyWhitelist.toPath(), StandardCharsets.UTF_8)) {
                String value = raw.trim();
                if (value.isBlank() || value.startsWith("#")) continue;
                UUID uuid = null;
                try { uuid = UUID.fromString(value); } catch (Exception ignored) { }
                Entry entry = new Entry(uuid, uuid == null ? value : "", System.currentTimeMillis(), "Legacy-Import SimplexVPNBlocker 1.0.1");
                if (!entries.containsKey(entry.key())) {
                    entries.put(entry.key(), entry);
                    imported++;
                }
            }
            if (imported > 0) save();
        } catch (Exception ignored) { return -1; }
        return imported;
    }

    private Entry decode(String line) {
        if (!line.startsWith("v1\t")) {
            UUID uuid = null;
            try { uuid = UUID.fromString(line); } catch (Exception ignored) { }
            return new Entry(uuid, uuid == null ? line : "", 0L, "Legacy");
        }
        String[] fields = line.split("\t", -1);
        if (fields.length < 5) return null;
        UUID uuid = null;
        if (!fields[1].isBlank() && !fields[1].equals("-")) {
            try { uuid = UUID.fromString(fields[1]); } catch (Exception ignored) { }
        }
        String name = decodeText(fields[2]);
        long addedAt;
        try { addedAt = Long.parseLong(fields[3]); } catch (Exception ignored) { addedAt = 0L; }
        return new Entry(uuid, name, addedAt, decodeText(fields[4]));
    }

    private synchronized void save() {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            File temporary = new File(parent, file.getName() + ".tmp");
            try (BufferedWriter writer = Files.newBufferedWriter(temporary.toPath(), StandardCharsets.UTF_8)) {
                writer.write("# SimplexAdminSuite VPN whitelist v1. Managed through /sas vpn and Discord.\n");
                for (Entry entry : entries.values()) {
                    writer.write("v1\t");
                    writer.write(entry.uuid() == null ? "-" : entry.uuid().toString());
                    writer.write('\t');
                    writer.write(encodeText(entry.name()));
                    writer.write('\t');
                    writer.write(Long.toString(entry.addedAt()));
                    writer.write('\t');
                    writer.write(encodeText(entry.addedBy()));
                    writer.write('\n');
                }
            }
            try { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (Exception atomicUnavailable) { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING); }
        } catch (Exception ignored) { }
    }

    private static String encodeText(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeText(String value) {
        try { return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8); }
        catch (Exception ignored) { return ""; }
    }
}
