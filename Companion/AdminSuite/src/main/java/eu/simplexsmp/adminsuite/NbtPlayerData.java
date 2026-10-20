package eu.simplexsmp.adminsuite;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** Bounded, dependency-free reader for Java Edition player .dat files. */
final class NbtPlayerData {
    private static final int MAX_DEPTH = 64;
    private static final int MAX_COLLECTION = 1_000_000;
    private static final long MAX_UNCOMPRESSED = 32L * 1024L * 1024L;

    private NbtPlayerData() { }

    static Snapshot read(File file) throws IOException {
        if (file == null || !file.isFile()) return Snapshot.empty("Keine playerdata-Datei gefunden");
        try (InputStream raw = new BufferedInputStream(new FileInputStream(file))) {
            raw.mark(4);
            int first = raw.read();
            int second = raw.read();
            raw.reset();
            InputStream decoded = first == 0x1f && second == 0x8b ? new GZIPInputStream(raw) : raw;
            try (DataInputStream input = new DataInputStream(new LimitedInputStream(decoded, MAX_UNCOMPRESSED))) {
                int type = input.readUnsignedByte();
                if (type != 10) throw new IOException("NBT root is not a compound (type " + type + ")");
                readString(input);
                Object payload = readPayload(input, type, 0, new int[]{100_000});
                if (!(payload instanceof Map<?, ?>)) throw new IOException("NBT root payload is invalid");
                @SuppressWarnings("unchecked") Map<String, Object> root = (Map<String, Object>) payload;
                return extract(file, root);
            }
        }
    }

    private static Object readPayload(DataInputStream input, int type, int depth, int[] budget) throws IOException {
        if(--budget[0]<0)throw new IOException("Zu viele NBT-Tags (Maximum 100.000)");
        if (depth > MAX_DEPTH) throw new IOException("NBT nesting exceeds " + MAX_DEPTH);
        switch (type) {
            case 0: return null;
            case 1: return input.readByte();
            case 2: return input.readShort();
            case 3: return input.readInt();
            case 4: return input.readLong();
            case 5: return input.readFloat();
            case 6: return input.readDouble();
            case 7: {
                int length = checkedLength(input.readInt());
                byte[] value = new byte[length]; input.readFully(value); return value;
            }
            case 8: return readString(input);
            case 9: {
                int elementType = input.readUnsignedByte();
                int length = checkedLength(input.readInt());
                if(length>0&&(elementType<1||elementType>12))throw new IOException("Ungültiger NBT-Listentyp");
                if(length>budget[0])throw new IOException("NBT-Liste überschreitet Tag-Limit");
                List<Object> values = new ArrayList<>(Math.min(length, 8192));
                for (int index = 0; index < length; index++) values.add(readPayload(input, elementType, depth + 1, budget));
                return values;
            }
            case 10: {
                Map<String, Object> values = new LinkedHashMap<>();
                while (true) {
                    int childType;
                    try { childType = input.readUnsignedByte(); } catch (EOFException error) { throw new IOException("Unexpected end of NBT compound", error); }
                    if (childType == 0) break;
                    String name = readString(input);
                    values.put(name, readPayload(input, childType, depth + 1, budget));
                    if (values.size() > MAX_COLLECTION) throw new IOException("NBT compound is too large");
                }
                return values;
            }
            case 11: {
                int length = checkedLength(input.readInt());
                int[] value = new int[length]; for (int index = 0; index < length; index++) value[index] = input.readInt(); return value;
            }
            case 12: {
                int length = checkedLength(input.readInt());
                long[] value = new long[length]; for (int index = 0; index < length; index++) value[index] = input.readLong(); return value;
            }
            default: throw new IOException("Unsupported NBT tag type " + type);
        }
    }

    private static int checkedLength(int length) throws IOException {
        if (length < 0 || length > MAX_COLLECTION) throw new IOException("Invalid NBT collection length " + length);
        return length;
    }

    private static String readString(DataInputStream input) throws IOException {
        // Java Edition NBT uses DataInput modified UTF-8 (including surrogate pairs).
        return input.readUTF();
    }

    private static Snapshot extract(File file, Map<String, Object> root) {
        root = normalize(root);
        Map<String, String> values = new LinkedHashMap<>();
        put(values, "Data version", root.get("DataVersion"));
        put(values, "Dimension", root.get("Dimension"));
        put(values, "Position", vector(root.get("Pos"), 3));
        put(values, "Rotation", vector(root.get("Rotation"), 2));
        put(values, "Health", root.get("Health"));
        put(values, "Food", root.get("foodLevel"));
        put(values, "Saturation", root.get("foodSaturationLevel"));
        put(values, "Exhaustion", root.get("foodExhaustionLevel"));
        put(values, "XP level", root.get("XpLevel"));
        put(values, "XP total", root.get("XpTotal"));
        put(values, "XP progress", root.get("XpP"));
        put(values, "Score", root.get("Score"));
        put(values, "Game mode", gameMode(root.get("playerGameType")));
        put(values, "Fire ticks", root.get("Fire"));
        put(values, "Air", root.get("Air"));
        put(values, "Fall distance", root.get("FallDistance"));
        put(values, "On ground", booleanValue(root.get("OnGround")));
        put(values, "Invulnerable", booleanValue(root.get("Invulnerable")));
        put(values, "Portal cooldown", root.get("PortalCooldown"));
        put(values, "Sleep timer", root.get("SleepTimer"));
        put(values, "Selected slot", root.get("SelectedItemSlot"));
        put(values, "Inventory stacks", listSize(root.get("Inventory")));
        put(values, "Ender chest stacks", listSize(root.get("EnderItems")));
        put(values, "Known recipes", nestedListSize(root, "recipeBook", "recipes"));
        put(values, "Spawn dimension", root.get("SpawnDimension"));
        put(values, "Spawn", spawn(root));
        put(values, "Last death", lastDeath(root));
        put(values, "Bukkit first played", bukkit(root,"firstPlayed"));
        put(values, "Bukkit last played", bukkit(root,"lastPlayed"));
        put(values, "Bukkit last known name", bukkit(root,"lastKnownName"));
        Object abilities = root.get("abilities");
        if (abilities instanceof Map<?, ?>) {
            Map<?, ?> map = (Map<?, ?>) abilities;
            put(values, "May fly", booleanValue(map.get("mayfly")));
            put(values, "Flying", booleanValue(map.get("flying")));
            put(values, "Invulnerable ability", booleanValue(map.get("invulnerable")));
            put(values, "Walk speed", map.get("walkSpeed"));
            put(values, "Fly speed", map.get("flySpeed"));
        }
        Map<String, Integer> inventory = summarizeItems(root.get("Inventory"));
        return new Snapshot(file, Collections.unmodifiableMap(values), Collections.unmodifiableMap(inventory), "");
    }

    private static Object bukkit(Map<String,Object> root,String key){
        for(String compound:List.of("bukkit","Bukkit")){
            Object nested=root.get(compound);
            if(nested instanceof Map<?,?> map&&map.containsKey(key))return map.get(key);
        }
        Object value=root.get("bukkit."+key);return value==null?root.get("Bukkit."+key):value;
    }
    private static Map<String,Object> normalize(Map<String,Object> root){
        Map<String,Object> out=new LinkedHashMap<>(root);
        for(String key:List.of("DataVersion","Dimension","Pos","Rotation","Health","foodLevel","foodSaturationLevel","foodExhaustionLevel","XpLevel","XpTotal","XpP","Score","playerGameType","Fire","Air","FallDistance","OnGround","Invulnerable","PortalCooldown","SleepTimer","SelectedItemSlot","Inventory","EnderItems","recipeBook","SpawnDimension","SpawnX","SpawnY","SpawnZ","LastDeathLocation")){
            String snake=key.replaceAll("([a-z0-9])([A-Z])","$1_$2").toLowerCase(Locale.ROOT);
            if(!out.containsKey(key)&&root.containsKey(snake))out.put(key,root.get(snake));
        }
        return out;
    }
    static String label(String key){
        return switch(key){
            case "Data version"->"Datenversion";case "Position"->"Position";case "Rotation"->"Blickrichtung";
            case "Health"->"Gesundheit";case "Food"->"Hunger";case "Saturation"->"Sättigung";case "Exhaustion"->"Erschöpfung";
            case "XP level"->"XP-Level";case "XP total"->"XP gesamt";case "XP progress"->"XP-Fortschritt";case "Score"->"Punktestand";
            case "Game mode"->"Spielmodus";case "Fire ticks"->"Feuerticks";case "Air"->"Luft";case "Fall distance"->"Fallhöhe";
            case "On ground"->"Am Boden";case "Invulnerable"->"Unverwundbar";case "Portal cooldown"->"Portalwartezeit";
            case "Sleep timer"->"Schlaftimer";case "Selected slot"->"Aktiver Slot";case "Inventory stacks"->"Inventarstapel";
            case "Ender chest stacks"->"Endertruhenstapel";case "Known recipes"->"Bekannte Rezepte";case "Spawn dimension"->"Spawn-Dimension";
            case "Last death"->"Letzter Tod";case "Bukkit first played"->"Datei-Erstjoin";case "Bukkit last played"->"Datei: zuletzt gespielt";
            case "Bukkit last known name"->"Letzter gespeicherter Name";case "May fly"->"Darf fliegen";case "Flying"->"Fliegt";
            case "Invulnerable ability"->"Unverwundbarkeit";case "Walk speed"->"Laufgeschwindigkeit";case "Fly speed"->"Fluggeschwindigkeit";
            default->key;
        };
    }

    private static Map<String, Integer> summarizeItems(Object value) {
        Map<String, Integer> items = new LinkedHashMap<>();
        if (!(value instanceof List<?>)) return items;
        for (Object entry : (List<?>) value) {
            if (!(entry instanceof Map<?, ?>)) continue;
            Map<?, ?> compound = (Map<?, ?>) entry;
            String id = text(compound.get("id"));
            if (id.isBlank()) id = text(compound.get("Id"));
            int count = number(compound.get("count"), number(compound.get("Count"), 1));
            if (!id.isBlank()) items.put(id, items.getOrDefault(id, 0) + Math.max(1, count));
        }
        return items;
    }

    private static String spawn(Map<String, Object> root) {
        Object x = root.get("SpawnX"), y = root.get("SpawnY"), z = root.get("SpawnZ");
        if (x == null || y == null || z == null) return "";
        return text(x) + " / " + text(y) + " / " + text(z);
    }

    private static String lastDeath(Map<String, Object> root) {
        Object value = root.get("LastDeathLocation");
        if (!(value instanceof Map<?, ?>)) return "";
        Map<?, ?> map = (Map<?, ?>) value;
        String dimension = text(map.get("dimension"));
        Object pos = map.get("pos");
        if (pos instanceof int[]) {
            int[] vector = (int[]) pos;
            return dimension + (vector.length >= 3 ? " @ " + vector[0] + " / " + vector[1] + " / " + vector[2] : "");
        }
        return dimension + (pos == null ? "" : " @ " + text(pos));
    }

    private static String nestedListSize(Map<String, Object> root, String compound, String list) {
        Object value = root.get(compound);
        if (!(value instanceof Map<?, ?>)) return "";
        return listSize(((Map<?, ?>) value).get(list));
    }

    private static String listSize(Object value) { return value instanceof List<?> ? Integer.toString(((List<?>) value).size()) : ""; }

    private static String vector(Object value, int expected) {
        if (!(value instanceof List<?>)) return "";
        List<?> list = (List<?>) value;
        List<String> parts = new ArrayList<>();
        for (int index = 0; index < Math.min(expected, list.size()); index++) parts.add(formatNumber(list.get(index)));
        return String.join(" / ", parts);
    }

    private static String gameMode(Object value) {
        int mode = number(value, -1);
        if (mode == 0) return "Überleben";
        if (mode == 1) return "Kreativ";
        if (mode == 2) return "Abenteuer";
        if (mode == 3) return "Zuschauer";
        return value == null ? "" : text(value);
    }

    private static String booleanValue(Object value) {
        if (!(value instanceof Number)) return value == null ? "" : text(value);
        return ((Number) value).intValue() == 0 ? "Nein" : "Ja";
    }

    private static int number(Object value, int fallback) { return value instanceof Number ? ((Number) value).intValue() : fallback; }

    private static String formatNumber(Object value) {
        if (!(value instanceof Number)) return text(value);
        double number = ((Number) value).doubleValue();
        if (Math.rint(number) == number) return Long.toString((long) number);
        return String.format(Locale.ROOT, "%.2f", number);
    }

    private static String text(Object value) {
        if (value == null) return "";
        if (value instanceof Number) return formatNumber(value);
        return String.valueOf(value);
    }

    private static void put(Map<String, String> values, String key, Object value) {
        String text = value == null ? "" : value instanceof String ? (String) value : NbtPlayerData.text(value);
        if (!text.isBlank()) values.put(key, text);
    }

    static final class Snapshot {
        final File source;
        final Map<String, String> values;
        final Map<String, Integer> inventory;
        final String error;
        Snapshot(File source, Map<String, String> values, Map<String, Integer> inventory, String error) {
            this.source = source; this.values = values; this.inventory = inventory; this.error = error == null ? "" : error;
        }
        static Snapshot empty(String error) { return new Snapshot(null, Collections.emptyMap(), Collections.emptyMap(), error); }
    }

    private static final class LimitedInputStream extends InputStream {
        private final InputStream delegate;
        private final long maximum;
        private long read;
        private LimitedInputStream(InputStream delegate, long maximum) { this.delegate = delegate; this.maximum = maximum; }
        public int read() throws IOException { int value = delegate.read(); if (value >= 0) counted(1); return value; }
        public int read(byte[] buffer, int offset, int length) throws IOException { int count = delegate.read(buffer, offset, length); if (count > 0) counted(count); return count; }
        private void counted(int count) throws IOException { read += count; if (read > maximum) throw new IOException("NBT payload exceeds " + maximum + " bytes"); }
        public void close() throws IOException { delegate.close(); }
    }
}
