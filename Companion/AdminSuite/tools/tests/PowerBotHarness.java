package eu.simplexsmp.adminsuite;

import java.io.*;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

final class PowerBotHarness {
    public static void main(String[] args) throws Exception {
        assert PowerBot.advertising("Join my server! play.example.net", "simplexsmp.eu");
        assert PowerBot.advertising("Kommt auf unseren Server 192.0.2.7:25565", "simplexsmp.eu");
        assert !PowerBot.advertising("Join my server simplexsmp.eu", "simplexsmp.eu");
        assert !PowerBot.advertising("Join my server play.simplexsmp.eu", "simplexsmp.eu");
        assert !PowerBot.advertising("I got timed out when I tried to join the server on play.example.net", "simplexsmp.eu");
        assert !PowerBot.advertising("Example.net posted an article", "simplexsmp.eu");

        OfflineInventory old = new OfflineInventory(inventory());
        assert old.editable();
        assert slot(old, 5).get("material").equals("diamond");
        assert slot(old, 42).get("material").equals("stick");
        OfflineInventory swapped = new OfflineInventory(old.swap(5, 42));
        assert slot(swapped, 5).get("material").equals("stick");
        assert slot(swapped, 42).get("amount").equals(7);
        OfflineInventory moved = new OfflineInventory(swapped.swap(42, 3));
        assert slot(moved, 3).get("amount").equals(7);
        assert moved.rows().stream().noneMatch(row -> row.get("slot").equals(42));
        assert slot(moved, 36).get("material").equals("diamond_boots");
        try { moved.swap(3, 36); throw new AssertionError("Cannot edit equipment"); }
        catch (IllegalArgumentException expected) { }
        try { new OfflineInventory(new byte[]{10, 0, 0, 9}); throw new AssertionError("Accepted truncated NBT"); }
        catch (IOException expected) { }
        System.out.println("PASS PowerBot targeted self-promotion and offline cross-container NBT edits");
    }

    private static Map<String, Object> slot(OfflineInventory inventory, int slot) {
        return inventory.rows().stream().filter(row -> row.get("slot").equals(slot)).findFirst().orElseThrow();
    }

    private static byte[] inventory() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(new GZIPOutputStream(bytes))) {
            out.writeByte(10); out.writeUTF("");
            list(out, "Inventory", 5, "diamond", 7);
            list(out, "EnderItems", 1, "stick", 2);
            out.writeByte(10); out.writeUTF("equipment");
            out.writeByte(10); out.writeUTF("feet");
            out.writeByte(8); out.writeUTF("id"); out.writeUTF("minecraft:diamond_boots");
            out.writeByte(0); out.writeByte(0);
            out.writeByte(0);
        }
        return bytes.toByteArray();
    }

    private static void list(DataOutputStream out, String name, int slot, String material, int amount) throws IOException {
        out.writeByte(9); out.writeUTF(name);
        out.writeByte(10); out.writeInt(1);
        out.writeByte(1); out.writeUTF("Slot"); out.writeByte(slot);
        out.writeByte(8); out.writeUTF("id"); out.writeUTF("minecraft:" + material);
        out.writeByte(3); out.writeUTF("count"); out.writeInt(amount);
        out.writeByte(10); out.writeUTF("components");
        out.writeByte(8); out.writeUTF("minecraft:custom_name"); out.writeUTF("Unchanged component");
        out.writeByte(0); out.writeByte(0);
    }
}
