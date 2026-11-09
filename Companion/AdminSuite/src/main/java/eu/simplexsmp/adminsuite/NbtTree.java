package eu.simplexsmp.adminsuite;

import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 10.15.0: a typed, lossless NBT tree. Every tag keeps its exact type, so a player file that is read and written
 * again without changes is byte-for-byte equivalent in content (all item components, shulker contents, books …).
 */
final class NbtTree {
    static final int END = 0, BYTE = 1, SHORT = 2, INT = 3, LONG = 4, FLOAT = 5, DOUBLE = 6, BYTE_ARRAY = 7, STRING = 8, LIST = 9, COMPOUND = 10, INT_ARRAY = 11, LONG_ARRAY = 12;
    private static final int LIMIT = 16 * 1024 * 1024, MAX_DEPTH = 512;

    record Tag(int type, Object value) {
        static Tag of(byte value) { return new Tag(BYTE, value); }
        static Tag of(int value) { return new Tag(INT, value); }
        static Tag of(String value) { return new Tag(STRING, value); }
        static Tag compound(Map<String, Tag> value) { return new Tag(COMPOUND, value); }
        @SuppressWarnings("unchecked") Map<String, Tag> compound() { return type == COMPOUND ? (Map<String, Tag>) value : null; }
        ListTag list() { return type == LIST ? (ListTag) value : null; }
        int number() { return value instanceof Number n ? n.intValue() : 0; }
        String text() { return value instanceof String s ? s : ""; }
    }
    record ListTag(int elementType, List<Tag> items) { }
    record Root(String name, Map<String, Tag> compound, boolean gzip) { }

    static Root read(byte[] input) throws IOException {
        boolean gzip = input.length > 1 && input[0] == 0x1f && (input[1] & 0xff) == 0x8b;
        byte[] raw;
        try (InputStream decoded = gzip ? new GZIPInputStream(new ByteArrayInputStream(input)) : new ByteArrayInputStream(input)) {
            raw = decoded.readNBytes(LIMIT + 1);
        }
        if (raw.length > LIMIT) throw new IOException("NBT zu groß");
        try (DataInputStream data = new DataInputStream(new ByteArrayInputStream(raw))) {
            if (data.readUnsignedByte() != COMPOUND) throw new IOException("Ungültiges NBT: Wurzel ist kein Compound");
            String name = data.readUTF();
            int[] budget = {500_000};
            @SuppressWarnings("unchecked") Map<String, Tag> compound = (Map<String, Tag>) payload(data, COMPOUND, 0, budget);
            if (data.read() != -1) throw new IOException("Unerwartete Daten hinter NBT");
            return new Root(name, compound, gzip);
        }
    }

    private static Object payload(DataInputStream in, int type, int depth, int[] budget) throws IOException {
        if (--budget[0] < 0) throw new IOException("Zu viele NBT-Tags");
        if (depth > MAX_DEPTH) throw new IOException("NBT zu tief verschachtelt");
        switch (type) {
            case BYTE: return in.readByte();
            case SHORT: return in.readShort();
            case INT: return in.readInt();
            case LONG: return in.readLong();
            case FLOAT: return in.readFloat();
            case DOUBLE: return in.readDouble();
            case BYTE_ARRAY: { byte[] v = new byte[length(in.readInt())]; in.readFully(v); return v; }
            case STRING: return in.readUTF();
            case LIST: {
                int element = in.readUnsignedByte(), size = length(in.readInt());
                if (size > 0 && (element < 1 || element > 12)) throw new IOException("Ungültiger NBT-Listentyp");
                List<Tag> items = new ArrayList<>(Math.min(size, 4096));
                for (int i = 0; i < size; i++) items.add(new Tag(element, payload(in, element, depth + 1, budget)));
                return new ListTag(element, items);
            }
            case COMPOUND: {
                Map<String, Tag> values = new LinkedHashMap<>();
                for (int child; (child = in.readUnsignedByte()) != END;) {
                    if (child > 12) throw new IOException("Unbekannter NBT-Typ " + child);
                    String name = in.readUTF();
                    values.put(name, new Tag(child, payload(in, child, depth + 1, budget)));
                }
                return values;
            }
            case INT_ARRAY: { int[] v = new int[length(in.readInt())]; for (int i = 0; i < v.length; i++) v[i] = in.readInt(); return v; }
            case LONG_ARRAY: { long[] v = new long[length(in.readInt())]; for (int i = 0; i < v.length; i++) v[i] = in.readLong(); return v; }
            default: throw new IOException("Unbekannter NBT-Typ " + type);
        }
    }

    private static int length(int value) throws IOException {
        if (value < 0 || value > LIMIT) throw new IOException("Ungültige NBT-Länge");
        return value;
    }

    static byte[] write(Root root) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (OutputStream sink = root.gzip ? new GZIPOutputStream(bytes) : bytes; DataOutputStream out = new DataOutputStream(sink)) {
            out.writeByte(COMPOUND);
            out.writeUTF(root.name);
            write(out, new Tag(COMPOUND, root.compound));
        }
        return bytes.toByteArray();
    }

    private static void write(DataOutputStream out, Tag tag) throws IOException {
        switch (tag.type) {
            case BYTE -> out.writeByte((Byte) tag.value);
            case SHORT -> out.writeShort((Short) tag.value);
            case INT -> out.writeInt((Integer) tag.value);
            case LONG -> out.writeLong((Long) tag.value);
            case FLOAT -> out.writeFloat((Float) tag.value);
            case DOUBLE -> out.writeDouble((Double) tag.value);
            case BYTE_ARRAY -> { byte[] v = (byte[]) tag.value; out.writeInt(v.length); out.write(v); }
            case STRING -> out.writeUTF((String) tag.value);
            case LIST -> {
                ListTag list = (ListTag) tag.value;
                out.writeByte(list.items.isEmpty() ? (list.elementType == 0 ? END : list.elementType) : list.elementType);
                out.writeInt(list.items.size());
                for (Tag item : list.items) write(out, item);
            }
            case COMPOUND -> {
                for (var entry : tag.compound().entrySet()) {
                    out.writeByte(entry.getValue().type);
                    out.writeUTF(entry.getKey());
                    write(out, entry.getValue());
                }
                out.writeByte(END);
            }
            case INT_ARRAY -> { int[] v = (int[]) tag.value; out.writeInt(v.length); for (int x : v) out.writeInt(x); }
            case LONG_ARRAY -> { long[] v = (long[]) tag.value; out.writeInt(v.length); for (long x : v) out.writeLong(x); }
            default -> throw new IOException("Unbekannter NBT-Typ " + tag.type);
        }
    }

    /** Deep copy, so edits on one side never leak into another snapshot. */
    static Tag copy(Tag tag) {
        return switch (tag.type) {
            case COMPOUND -> { Map<String, Tag> next = new LinkedHashMap<>(); tag.compound().forEach((k, v) -> next.put(k, copy(v))); yield new Tag(COMPOUND, next); }
            case LIST -> { ListTag list = tag.list(); List<Tag> items = new ArrayList<>(); for (Tag item : list.items) items.add(copy(item)); yield new Tag(LIST, new ListTag(list.elementType, items)); }
            case BYTE_ARRAY -> new Tag(BYTE_ARRAY, ((byte[]) tag.value).clone());
            case INT_ARRAY -> new Tag(INT_ARRAY, ((int[]) tag.value).clone());
            case LONG_ARRAY -> new Tag(LONG_ARRAY, ((long[]) tag.value).clone());
            default -> tag;
        };
    }
}
