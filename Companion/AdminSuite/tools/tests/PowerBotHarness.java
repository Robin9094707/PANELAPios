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
        release1013();
        release1014();
        release1015();

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

    /** 10.13.0: unified player timeline, flag matching, severity thresholds, briefing groups and old log compatibility. */
    private static void release1013() {
        var flag = Map.<String, Object>of("id", "case-1", "at", PlayerTimeline.time("08.11.2026 20:00:05 x"), "kind", "warning",
            "message", "Du bist SO schlecht!!", "reason", "Beleidigung", "detail", "Hinweis");
        var blocked = Map.<String, Object>of("id", "case-2", "at", PlayerTimeline.time("08.11.2026 20:03:00 x"), "kind", "abuse",
            "message", "", "reason", "", "detail", "Nachricht zurückgehalten");
        var rows = PlayerTimeline.rows("Alex", List.of("08.11.2026 20:00:00 Alex: du bist so schlecht"),
            List.of("08.11.2026 20:01:00 OUT → Sam [msg]: Hi"), List.of("08.11.2026 20:02:00 PowerBot · Nachricht zurückgehalten: x", "Legacy ohne Datum"),
            List.of("08.11.2026 19:59:00 Alex traf Sam für 2,00 Schaden"), List.of("08.11.2026 20:04:00 Abgelegt: 3× diamond · world 1 / 2 / 3"),
            List.of(flag, blocked), 50);
        assert rows.size() == 7 : rows;
        assert rows.get(0).get("category").equals("item") && rows.get(0).get("kind").equals("drop");
        assert rows.get(1).get("category").equals("powerbot") && rows.get(1).get("case").equals("case-2");
        var chat = rows.stream().filter(row -> row.get("category").equals("chat")).findFirst().orElseThrow();
        assert Boolean.TRUE.equals(chat.get("flagged")) && chat.get("flagReason").equals("Beleidigung") && chat.get("case").equals("case-1");
        assert rows.stream().anyMatch(row -> row.get("category").equals("action") && Boolean.TRUE.equals(row.get("flagged")));
        assert rows.get(rows.size() - 1).get("text").equals("Legacy ohne Datum");
        assert PlayerTimeline.rows("Alex", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), 10).isEmpty();
        assert PowerBot.atLeast("severe", "medium") && PowerBot.atLeast("medium", "medium") && !PowerBot.atLeast("low", "medium") && !PowerBot.atLeast("none", "low");
        assert PowerBot.Evidence.parse(null).empty();
        var proof = PowerBot.Evidence.parse(Map.of("message", "hi", "severity", "unknown", "context", List.of(Map.of("name", "Alex", "text", "hi", "highlight", true))));
        assert proof.severity().isEmpty() && proof.context().size() == 1 && Boolean.TRUE.equals(proof.context().get(0).get("highlight"));
        var summary = SuiteBriefing.summarizeEvents(List.of(Map.of("time", 10L, "type", "report", "title", "alt"),
            Map.of("time", 200L, "type", "ticket-high", "title", "Ticket", "critical", true), Map.of("time", 300L, "type", "first-join", "title", "Neu")), 100);
        assert summary.get("total").equals(2L) && summary.get("critical").equals(1L);
        @SuppressWarnings("unchecked") var groups = (Map<String, Long>) summary.get("groups");
        assert groups.get("tickets") == 1L && groups.get("joins") == 1L && !groups.containsKey("reports");
    }

    /** 10.14.0: clock times survive redaction, smart restart decisions and automation triggers. */
    private static void release1014() {
        assert AiAdmin.redact("Vanish um 09:36:12 Uhr bis 20:15").equals("Vanish um 09:36:12 Uhr bis 20:15");
        assert AiAdmin.redact("IP 2001:db8::1 und fe80:0:0:0:1:2 und 10.0.0.1").equals("IP [IP entfernt] und [IP entfernt] und [IP entfernt]");
        long hour = 3_600_000L, start = 1_000_000_000L;
        var on = new SmartRestart.Settings(true, 6, 1, 60, 2, 0, true);
        long due = SmartRestart.dueAt(start, 0, 6);
        assert due == start + 6 * hour;
        assert SmartRestart.dueAt(start, start + 5 * hour, 6) == start + 11 * hour;
        assert SmartRestart.decide(on, due - 1, due, 0, due - 600_000, start).equals("interval");
        assert SmartRestart.decide(on, due + 1, due, 3, 0, start).equals("players");
        assert SmartRestart.decide(on, due + 1, due, 1, due, start).equals("stable");
        assert SmartRestart.decide(on, due + 200_000, due, 1, due, start).equals("restart");
        var forced = new SmartRestart.Settings(true, 6, 1, 60, 2, 2, true);
        assert SmartRestart.decide(forced, due + 3 * hour, due, 20, 0, start).equals("force");
        assert SmartRestart.decide(new SmartRestart.Settings(false, 6, 1, 60, 2, 0, true), due + hour, due, 0, due, start).equals("off");
        var local = java.time.ZonedDateTime.of(2026, 11, 9, 20, 3, 0, 0, java.time.ZoneId.of("Europe/Berlin"));
        assert Automations.due(Map.of("type", "daily", "time", "20:00"), Map.of(), 0, local, Map.of(), 0, null);
        assert !Automations.due(Map.of("type", "daily", "time", "20:00"), Map.of("lastDate", "2026-11-09"), 0, local, Map.of(), 0, null);
        assert Automations.due(Map.of("type", "game-day", "world", "world", "day", 10L, "everyDays", 0L), Map.of(), 0, local, Map.of("world", 12L), 0, null);
        assert !Automations.due(Map.of("type", "game-day", "world", "world", "day", 10L, "everyDays", 0L), Map.of("lastRun", 5L, "lastDay", 12L), 0, local, Map.of("world", 13L), 0, null);
        assert Automations.due(Map.of("type", "game-day", "world", "world", "day", 10L, "everyDays", 7L), Map.of("lastRun", 5L, "lastDay", 12L), 0, local, Map.of("world", 17L), 0, null);
        assert !Automations.due(Map.of("type", "game-day", "world", "world", "day", 10L, "everyDays", 7L), Map.of("lastRun", 5L, "lastDay", 17L), 0, local, Map.of("world", 18L), 0, null);
        assert Automations.due(Map.of("type", "players-above", "count", 5L), Map.of(), 100 * 60_000L, local, Map.of(), 6, false);
        assert !Automations.due(Map.of("type", "players-above", "count", 5L), Map.of(), 100 * 60_000L, local, Map.of(), 6, true);
        var rule = Automations.normalize(Map.of("name", "Spieltag", "trigger", Map.of("type", "interval", "minutes", 5),
            "actions", List.of(Map.of("type", "broadcast", "value", "Hallo", "en", "Hello"))), false, Set.of("world"));
        assert ((Map<?, ?>) rule.get("trigger")).get("minutes").equals(15L);
        try { Automations.normalize(Map.of("name", "x", "trigger", Map.of("type", "interval", "minutes", 60),
            "actions", List.of(Map.of("type", "restart", "value", "60"))), false, Set.of()); throw new AssertionError("restart without rights"); }
        catch (AppApiManager.ApiError expected) { assert expected.code == 403; }
    }

    /** 10.15.0: lossless NBT round trip and every visual slot of an offline file, including armor and offhand. */
    private static void release1015() throws Exception {
        java.util.function.Function<String, Map<String, NbtTree.Tag>> item = id -> {
            Map<String, NbtTree.Tag> map = new java.util.LinkedHashMap<>();
            map.put("id", NbtTree.Tag.of("minecraft:" + id)); map.put("count", NbtTree.Tag.of(1));
            Map<String, NbtTree.Tag> inner = new java.util.LinkedHashMap<>();
            inner.put("id", NbtTree.Tag.of("minecraft:diamond"));
            inner.put("count", NbtTree.Tag.of(64));
            Map<String, NbtTree.Tag> entry = new java.util.LinkedHashMap<>();
            entry.put("slot", NbtTree.Tag.of(0));
            entry.put("item", NbtTree.Tag.compound(inner));
            List<NbtTree.Tag> stored = new java.util.ArrayList<>();
            stored.add(NbtTree.Tag.compound(entry));
            Map<String, NbtTree.Tag> components = new java.util.LinkedHashMap<>();
            components.put("minecraft:container", new NbtTree.Tag(NbtTree.LIST, new NbtTree.ListTag(NbtTree.COMPOUND, stored)));
            map.put("components", NbtTree.Tag.compound(components));
            return map;
        };
        Map<String, NbtTree.Tag> root = new java.util.LinkedHashMap<>();
        root.put("DataVersion", NbtTree.Tag.of(4600));
        Map<String, NbtTree.Tag> first = new java.util.LinkedHashMap<>(); first.put("Slot", NbtTree.Tag.of((byte) 3)); first.putAll(item.apply("shulker_box"));
        root.put("Inventory", new NbtTree.Tag(NbtTree.LIST, new NbtTree.ListTag(NbtTree.COMPOUND, new java.util.ArrayList<>(List.of(NbtTree.Tag.compound(first))))));
        root.put("EnderItems", new NbtTree.Tag(NbtTree.LIST, new NbtTree.ListTag(NbtTree.END, new java.util.ArrayList<>())));
        root.put("equipment", NbtTree.Tag.compound(new java.util.LinkedHashMap<>(Map.of("head", NbtTree.Tag.compound(item.apply("diamond_helmet"))))));
        List<NbtTree.Tag> pos = new java.util.ArrayList<>();
        pos.add(new NbtTree.Tag(NbtTree.DOUBLE, 1.5d));
        pos.add(new NbtTree.Tag(NbtTree.DOUBLE, 64d));
        pos.add(new NbtTree.Tag(NbtTree.DOUBLE, -3d));
        root.put("Pos", new NbtTree.Tag(NbtTree.LIST, new NbtTree.ListTag(NbtTree.DOUBLE, pos)));
        byte[] bytes = NbtTree.write(new NbtTree.Root("", root, true));
        assert java.util.Arrays.equals(bytes, NbtTree.write(NbtTree.read(bytes))) : "round trip changed bytes";
        assert PlayerNameMatcher.score("Miss Caitlin", "MissCaitlyn") == 100;
        assert PlayerNameMatcher.score("misscaitlyn", "MissCaitlyn") == 100;
        assert PlayerNameMatcher.score("alpha craft", "AlphacraftMC") >= 70;
        assert PlayerNameMatcher.score("MisCaitlym", "MissCaitlyn") > 0;
        assert PlayerNameMatcher.score("Bob", "MissCaitlyn") == 0;
        var ranked = PlayerNameMatcher.rank("Miss Caitlin", List.of("MissCaitlyn", "MissKitty", "Caitlin2"), 5);
        assert ranked.get(0).name().equals("MissCaitlyn") && PlayerNameMatcher.unique(ranked).isPresent();
        assert PlayerNameMatcher.unique(PlayerNameMatcher.rank("Steve", List.of("Steve1", "Steve2"), 5)).isEmpty();
        var file = new PlayerInventoryFile(bytes);
        assert file.modern() && file.get(39) != null && file.get(39).get("id").text().equals("minecraft:diamond_helmet");
        assert file.get(3).get("components").compound().containsKey("minecraft:container");
        var shulker = file.get(3);
        file.set(3, null);
        file.set(36, shulker);
        file.set(41, file.get(39));
        file.set(39, null);
        var after = new PlayerInventoryFile(file.bytes());
        assert after.get(3) == null && after.get(39) == null;
        assert after.get(36).get("components").compound().get("minecraft:container").list().items().size() == 1 : "shulker content lost";
        assert after.get(41).get("id").text().equals("minecraft:diamond_helmet");
        assert after.firstEmpty() == 0 && after.items().keySet().equals(java.util.Set.of(36, 41));
        var reread = NbtTree.read(after.bytes()).compound();
        assert reread.get("Pos").list().items().size() == 3 && reread.get("DataVersion").number() == 4600;
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
