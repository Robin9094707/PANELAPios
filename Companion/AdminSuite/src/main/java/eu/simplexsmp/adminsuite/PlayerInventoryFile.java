package eu.simplexsmp.adminsuite;

import java.io.IOException;
import java.util.*;

/**
 * 10.15.0: every slot of an offline player file, like a real invsee. Visual slots: 0–35 inventory (0–8 hotbar),
 * 36 feet, 37 legs, 38 chest, 39 head, 40 offhand, 41–67 ender chest. Armor and offhand live in the
 * "equipment" compound since 1.21.5 (data version 4325) and in Inventory slots 100–103 / -106 before.
 * Only the touched slots change; every other tag and item component stays exactly as stored.
 */
final class PlayerInventoryFile {
    static final int EQUIPMENT_VERSION = 4325;
    private static final String[] EQUIPMENT = {"feet", "legs", "chest", "head", "offhand"};
    private final NbtTree.Root root;
    final int dataVersion;

    PlayerInventoryFile(byte[] bytes) throws IOException {
        root = NbtTree.read(bytes);
        NbtTree.Tag version = root.compound().get("DataVersion");
        dataVersion = version == null ? 0 : version.number();
    }

    boolean modern() { return dataVersion >= EQUIPMENT_VERSION || root.compound().containsKey("equipment"); }

    byte[] bytes() throws IOException { return NbtTree.write(root); }

    private String key(String... names) {
        for (String name : names) if (root.compound().containsKey(name)) return name;
        return names[0];
    }

    private List<NbtTree.Tag> list(String name, boolean create) {
        NbtTree.Tag tag = root.compound().get(name);
        if (tag != null && tag.list() != null) {
            if (tag.list().elementType() != NbtTree.COMPOUND && !tag.list().items().isEmpty()) throw new IllegalStateException("Unerwartete Inventarliste");
            if (tag.list().elementType() != NbtTree.COMPOUND) {
                if (!create) return List.of();
                tag = new NbtTree.Tag(NbtTree.LIST, new NbtTree.ListTag(NbtTree.COMPOUND, new ArrayList<>()));
                root.compound().put(name, tag);
            }
            return tag.list().items();
        }
        if (!create) return List.of();
        NbtTree.Tag created = new NbtTree.Tag(NbtTree.LIST, new NbtTree.ListTag(NbtTree.COMPOUND, new ArrayList<>()));
        root.compound().put(name, created);
        return created.list().items();
    }

    private static int slotOf(NbtTree.Tag entry) {
        var map = entry.compound();
        NbtTree.Tag slot = map.containsKey("Slot") ? map.get("Slot") : map.get("slot");
        return slot == null ? Integer.MIN_VALUE : slot.number();
    }

    /** Where a visual slot is stored: list name and raw slot number, or the equipment key. */
    private record Place(String list, int raw, String equipment) { }

    private Place place(int visual) {
        if (visual >= 0 && visual < 36) return new Place(key("Inventory", "inventory"), visual, null);
        if (visual >= 41 && visual < 68) return new Place(key("EnderItems", "ender_items"), visual - 41, null);
        if (visual >= 36 && visual <= 40) {
            if (modern()) return new Place(null, 0, EQUIPMENT[visual - 36]);
            return new Place(key("Inventory", "inventory"), visual == 40 ? -106 : 100 + visual - 36, null);
        }
        throw new IllegalArgumentException("Ungültiger Platz");
    }

    /** Item compound without slot number, or null for an empty slot. */
    Map<String, NbtTree.Tag> get(int visual) {
        Place place = place(visual);
        if (place.equipment != null) {
            NbtTree.Tag equipment = root.compound().get("equipment");
            NbtTree.Tag item = equipment == null || equipment.compound() == null ? null : equipment.compound().get(place.equipment);
            if (item != null && item.compound() != null && !empty(item.compound())) return withoutSlot(item.compound());
            // Files converted from older versions may still carry armor in the inventory list.
            int legacy = visual == 40 ? -106 : 100 + visual - 36;
            for (NbtTree.Tag entry : list(key("Inventory", "inventory"), false)) if (slotOf(entry) == legacy && !empty(entry.compound())) return withoutSlot(entry.compound());
            return null;
        }
        for (NbtTree.Tag entry : list(place.list, false)) if (slotOf(entry) == place.raw && !empty(entry.compound())) return withoutSlot(entry.compound());
        return null;
    }

    void set(int visual, Map<String, NbtTree.Tag> item) {
        Place place = place(visual);
        boolean clear = item == null || empty(item);
        if (place.equipment != null) {
            NbtTree.Tag equipment = root.compound().get("equipment");
            if (equipment == null || equipment.compound() == null) {
                if (clear) { removeLegacy(visual); return; }
                equipment = NbtTree.Tag.compound(new LinkedHashMap<>());
                root.compound().put("equipment", equipment);
            }
            if (clear) equipment.compound().remove(place.equipment);
            else equipment.compound().put(place.equipment, NbtTree.Tag.compound(withoutSlot(item)));
            removeLegacy(visual);
            return;
        }
        List<NbtTree.Tag> entries = list(place.list, !clear);
        int slotType = NbtTree.BYTE;
        for (Iterator<NbtTree.Tag> it = entries.iterator(); it.hasNext();) {
            NbtTree.Tag entry = it.next();
            if (slotOf(entry) == place.raw) { NbtTree.Tag old = entry.compound().get("Slot"); if (old != null) slotType = old.type(); it.remove(); }
        }
        if (clear) return;
        Map<String, NbtTree.Tag> stored = new LinkedHashMap<>();
        stored.put("Slot", slotType == NbtTree.INT ? NbtTree.Tag.of(place.raw) : NbtTree.Tag.of((byte) place.raw));
        stored.putAll(withoutSlot(item));
        entries.add(NbtTree.Tag.compound(stored));
    }

    private void removeLegacy(int visual) {
        int legacy = visual == 40 ? -106 : 100 + visual - 36;
        NbtTree.Tag tag = root.compound().get(key("Inventory", "inventory"));
        if (tag != null && tag.list() != null && tag.list().elementType() == NbtTree.COMPOUND) tag.list().items().removeIf(entry -> slotOf(entry) == legacy);
    }

    static boolean empty(Map<String, NbtTree.Tag> item) {
        NbtTree.Tag id = item.get("id");
        if (id == null || id.text().isBlank() || id.text().equals("minecraft:air")) return true;
        NbtTree.Tag count = item.containsKey("count") ? item.get("count") : item.get("Count");
        return count != null && count.number() <= 0;
    }

    static Map<String, NbtTree.Tag> withoutSlot(Map<String, NbtTree.Tag> item) {
        Map<String, NbtTree.Tag> copy = new LinkedHashMap<>();
        item.forEach((k, v) -> { if (!k.equals("Slot") && !k.equals("slot")) copy.put(k, NbtTree.copy(v)); });
        return copy;
    }

    /** All non-empty visual slots. */
    Map<Integer, Map<String, NbtTree.Tag>> items() {
        Map<Integer, Map<String, NbtTree.Tag>> result = new TreeMap<>();
        for (int slot = 0; slot < 68; slot++) { var item = get(slot); if (item != null) result.put(slot, item); }
        return result;
    }

    int firstEmpty() {
        for (int slot = 0; slot < 36; slot++) if (get(slot) == null) return slot;
        return -1;
    }
}
