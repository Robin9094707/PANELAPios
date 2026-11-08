package eu.simplexsmp.adminsuite;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

final class PowerBotHarness {
    public static void main(String[] args) throws Exception {
        assert PowerBot.advertising("Join my server! play.example.net", "simplexsmp.eu");
        assert PowerBot.advertising("Kommt auf unseren Server 192.0.2.7:25565", "simplexsmp.eu");
        assert !PowerBot.advertising("Join my server simplexsmp.eu", "simplexsmp.eu");
        assert !PowerBot.advertising("Join my server play.simplexsmp.eu", "simplexsmp.eu");
        assert !PowerBot.advertising("I got timed out when I tried to join the server on play.example.net", "simplexsmp.eu");
        assert !PowerBot.advertising("Example.net posted an article", "simplexsmp.eu");

        keywords();
        moderationViews();

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
        System.out.println("PASS PowerBot literal keywords, durable deletion, targeted self-promotion and offline cross-container NBT edits");
    }

    private static void moderationViews() {
        var publicChat = List.of("08.11.2026 20:00:00 Alex: Hello");
        var whispers = List.of("08.11.2026 20:01:00 OUT → Sam [msg]: Hi!", "08.11.2026 20:02:00 IN ← Jo [r]: Ready?");
        var timeline = PlayerChatTimeline.rows("Alex", publicChat, whispers);
        assert timeline.size() == 3;
        assert timeline.get(0).get("from").equals("Jo") && timeline.get(0).get("to").equals("Alex");
        assert timeline.get(1).get("from").equals("Alex") && timeline.get(1).get("to").equals("Sam");
        assert timeline.get(1).get("text").equals("Hi!");
        assert timeline.get(2).get("kind").equals("public");
        assert (long) timeline.get(0).get("at") - (long) timeline.get(2).get("at") == 120_000L;
        assert PlayerChatTimeline.rows("Alex", publicChat, List.of()).size() == 1;
        var staff = Set.of("alex");
        assert AdminActivityLog.playerAdminRow(Map.of("source", "ingame", "admin", "alex"), staff);
        assert AdminActivityLog.playerAdminRow(Map.of("source", "ingame", "admin", "sam", "playerAdmin", true), staff);
        assert !AdminActivityLog.playerAdminRow(Map.of("source", "ingame", "admin", "alex", "playerAdmin", false), staff);
        for (String source : List.of("system", "console", "app", "discord"))
            assert !AdminActivityLog.playerAdminRow(Map.of("source", source, "admin", "alex", "playerAdmin", true), staff);
    }

    private static void keywords() throws Exception {
        Path dir = Files.createTempDirectory("powerbot-keyword-test-");
        Path file = dir.resolve("rules.properties");
        try {
            var rules = new PowerBotKeywords(file);
            assert rules.matches("Wer meinem sMp beitreten will, kann mich privat anschreiben!").size() == 1;
            assert rules.matches("Willkommen auf SimplexSMP").isEmpty();
            assert rules.matches("Was ist SMP?").size() == 1;
            assert rules.matches("ＳＭＰ!").size() == 1;
            rules.change("save", "", "mein Server", "Prüfe auf Eigenwerbung.");
            assert rules.matches("Komm auf MEIN   SERVER, ein SMP.").size() == 2;
            try { rules.change("save", "", "smp", "Doppelt"); throw new AssertionError("Doppeltes Stichwort erlaubt"); }
            catch (AppApiManager.ApiError expected) { assert expected.code == 409; }
            rules.change("save", "mein server", "Hallo", "Warne nur bei einer Beleidigung.");
            assert rules.matches("Mein Server").isEmpty();
            assert new PowerBotKeywords(file).matches("HALLO!").size() == 1;
            rules.change("delete", "smp", "", "");
            rules.change("delete", "hallo", "", "");
            assert new PowerBotKeywords(file).rows().isEmpty();
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
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
