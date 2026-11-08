package eu.simplexsmp.adminsuite;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Rewrites only vanilla inventory list slices, preserving all unrelated NBT and item components byte for byte. */
final class OfflineInventory {
    private static final int LIMIT = 16 * 1024 * 1024;
    private record Stack(int slot, int start, int end, int slotPosition, int slotType, Map<String, Object> tags) {
        byte[] moved(byte[] source, int target) {
            byte[] moved = Arrays.copyOfRange(source, start, end);
            if (slotType == 1) moved[slotPosition - start] = (byte) target;
            else ByteBuffer.wrap(moved).putInt(slotPosition - start, target);
            return moved;
        }
    }
    private record Container(int offset, int start, int end, List<Stack> stacks) {}
    private final byte[] raw;
    private Container inventory, ender;
    private Map<?, ?> equipment = Map.of();
    private final Map<Integer, Stack> indexed = new LinkedHashMap<>();

    OfflineInventory(byte[] input) throws IOException {
        boolean gzip = input.length > 1 && input[0] == 0x1f && (input[1] & 0xff) == 0x8b;
        try (InputStream decoded = gzip ? new GZIPInputStream(new ByteArrayInputStream(input)) : new ByteArrayInputStream(input)) {
            raw = decoded.readNBytes(LIMIT + 1);
        }
        if (raw.length > LIMIT) throw new IOException("Spielerdaten zu groß");
        ByteArrayInputStream stream = new ByteArrayInputStream(raw);
        try (DataInputStream data = new DataInputStream(stream)) {
            if (data.readUnsignedByte() != 10) throw new IOException("Ungültiges NBT");
            data.readUTF();
            int[] budget = {100_000};
            for (int type; (type = data.readUnsignedByte()) != 0;) {
                String name = data.readUTF();
                if (name.equals("Inventory") || name.equals("inventory")) {
                    if (inventory != null || type != 9) throw new IOException("Mehrdeutiges Spielerinventar");
                    inventory = container(data, stream, budget, false);
                } else if (name.equals("EnderItems") || name.equals("ender_items")) {
                    if (ender != null || type != 9) throw new IOException("Mehrdeutige Endertruhe");
                    ender = container(data, stream, budget, true);
                } else {
                    Object value = NbtPlayerData.readPayload(data, type, 1, budget);
                    if (name.equals("equipment") && value instanceof Map<?, ?> map) equipment = map;
                }
            }
            if (data.read() != -1) throw new IOException("Unerwartete Daten hinter NBT");
        }
        if (inventory == null && ender == null && equipment.isEmpty()) throw new IOException("Keine Inventardaten vorhanden");
    }

    private Container container(DataInputStream data, ByteArrayInputStream stream, int[] budget, boolean chest) throws IOException {
        int offset = raw.length - stream.available();
        int elementType = data.readUnsignedByte();
        int count = data.readInt();
        if (count < 0 || count > 70 || !(elementType == 10 || count == 0 && elementType == 0)) throw new IOException("Ungültige Inventarliste");
        int start = raw.length - stream.available();
        List<Stack> stacks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int entryStart = raw.length - stream.available(), slot = 0, slotPosition = -1, slotType = 0;
            Map<String, Object> tags = new LinkedHashMap<>();
            for (int type; (type = data.readUnsignedByte()) != 0;) {
                String name = data.readUTF();
                int position = raw.length - stream.available();
                Object value = NbtPlayerData.readPayload(data, type, 2, budget);
                if (name.equals("Slot") || name.equals("slot")) {
                    if (slotPosition >= 0 || !(type == 1 || type == 3) || !(value instanceof Number number)) throw new IOException("Ungültiger Inventarplatz");
                    slot = number.intValue();
                    slotPosition = position;
                    slotType = type;
                } else if (Set.of("id", "Count", "count").contains(name)) tags.put(name, value);
            }
            if (slotPosition < 0) throw new IOException("Inventarplatz fehlt");
            int visual = chest ? slot >= 0 && slot < 27 ? 41 + slot : -1
                : slot >= 0 && slot < 36 ? slot : slot >= 100 && slot <= 103 ? 36 + slot - 100 : slot == -106 || slot == 150 ? 40 : -1;
            Stack item = new Stack(visual, entryStart, raw.length - stream.available(), slotPosition, slotType, tags);
            if (visual >= 0 && indexed.putIfAbsent(visual, item) != null) throw new IOException("Inventarplatz doppelt belegt");
            stacks.add(item);
        }
        return new Container(offset, start, raw.length - stream.available(), stacks);
    }

    List<Map<String, Object>> rows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        indexed.forEach((slot, item) -> add(rows, slot, item.tags));
        List<String> keys = List.of("feet", "legs", "chest", "head", "offhand");
        for (int i = 0; i < keys.size(); i++) if (!indexed.containsKey(36 + i) && equipment.get(keys.get(i)) instanceof Map<?, ?> item)
            add(rows, 36 + i, item);
        return rows;
    }

    private static void add(List<Map<String, Object>> rows, int slot, Map<?, ?> tags) {
        if (!(tags.get("id") instanceof String id) || !id.matches("minecraft:[a-z0-9_]{1,100}") || id.equals("minecraft:air")) return;
        String name = id.substring("minecraft:".length());
        Object count = tags.containsKey("count") ? tags.get("count") : tags.get("Count");
        int amount = count instanceof Number number ? number.intValue() : 1;
        if (amount > 0) rows.add(SimplexAdminSuite.appMap("slot", slot, "material", name, "name", name.replace('_', ' '), "amount", amount));
    }

    boolean editable() { return inventory != null && ender != null; }

    byte[] swap(int from, int to) throws IOException {
        if (!editable()) throw new IllegalArgumentException("Vollständige Inventardaten fehlen");
        if (!editableSlot(from) || !editableSlot(to) || from == to || !indexed.containsKey(from)) throw new IllegalArgumentException("Ungültige Slots");
        Stack first = indexed.get(from), second = indexed.get(to);
        if (!minecraft(first) || second != null && !minecraft(second)) throw new IllegalArgumentException("Unbekannte Items sind schreibgeschützt.");
        try (ByteArrayOutputStream compressed = new ByteArrayOutputStream()) {
            try (DataOutputStream output = new DataOutputStream(new GZIPOutputStream(compressed))) {
                List<Container> containers = new ArrayList<>(List.of(inventory, ender));
                containers.sort(Comparator.comparingInt(Container::offset));
                int cursor = 0;
                for (Container container : containers) {
                    output.write(raw, cursor, container.offset - cursor);
                    List<byte[]> payload = new ArrayList<>();
                    for (Stack stack : container.stacks) if (stack != first && stack != second)
                        payload.add(Arrays.copyOfRange(raw, stack.start, stack.end));
                    if ((to >= 41) == (container == ender)) payload.add(first.moved(raw, to >= 41 ? to - 41 : to));
                    if (second != null && (from >= 41) == (container == ender)) payload.add(second.moved(raw, from >= 41 ? from - 41 : from));
                    output.writeByte(10);
                    output.writeInt(payload.size());
                    for (byte[] item : payload) output.write(item);
                    cursor = container.end;
                }
                output.write(raw, cursor, raw.length - cursor);
            }
            return compressed.toByteArray();
        }
    }

    private static boolean minecraft(Stack stack) {
        return stack.tags.get("id") instanceof String id && id.matches("minecraft:[a-z0-9_]{1,100}") && !id.equals("minecraft:air");
    }

    private static boolean editableSlot(int slot) { return slot >= 0 && slot < 68 && (slot < 36 || slot >= 41); }
}
