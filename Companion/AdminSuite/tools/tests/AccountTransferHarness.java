package eu.simplexsmp.adminsuite;

import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public final class AccountTransferHarness {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("simplex-transfer-");
        try {
            exerciseFiles(root);
            exercisePoints(root);
            exercisePermissions();
            System.out.println("PASS account transfer: previews, NBT identity, homes, rollback, SQLite balances, permission nodes.");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path file : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }

    static void exerciseFiles(Path root) throws Exception {
        var a = new AccountTransferFiles.Account(UUID.randomUUID(), "Veteran");
        var b = new AccountTransferFiles.Account(UUID.randomUUID(), "NewPlayer");
        Path world = Files.createDirectories(root.resolve("world"));
        Path saves = Files.createDirectories(world.resolve("players/data"));
        Path old = saves.resolve(a.id() + ".dat"), current = saves.resolve(b.id() + ".dat");
        byte[] originalA = player(a.id(), "diamond", "OldIdentity", 45);
        byte[] originalB = player(b.id(), "gold_ingot", "NewIdentity", 9);
        Files.write(old, originalA);
        Files.write(current, originalB);
        Files.write(saves.resolve(a.id() + ".dat_old"), originalA);
        Path stats = Files.createDirectories(world.resolve("stats"));
        Files.writeString(stats.resolve(a.id() + ".json"), "{\"old_stats\": true}");
        Path homes = Files.createDirectories(root.resolve("plugins/Essentials/userdata"));
        Path homeA = homes.resolve(a.id() + ".yml"), homeB = homes.resolve(b.id() + ".yml");
        Files.writeString(homeA, "identity: Veteran\nhomes:\n  mine:\n    x: 4\n");
        Files.writeString(homeB, "identity: NewPlayer\nhomes:\n  base:\n    x: 7\n");
        Path multiverse = Files.createDirectories(root.resolve("plugins/Multiverse-Inventories"));
        Path group = Files.createDirectories(multiverse.resolve("groups/main"));
        Files.writeString(group.resolve("Veteran.json"), "{\"old_group\": true}");
        Files.writeString(group.resolve("NewPlayer.json"), "{\"new_group\": true}");

        Path journal = root.resolve("backups");
        var service = new AccountTransferFiles(root, journal);
        var worldList = List.of(world.toFile());
        var plan = service.plan(worldList, a, b, List.of(homes), multiverse);
        assert plan.rows().get(0).get("oldItems").toString().contains("diamond");
        assert plan.rows().get(0).get("newItems").toString().contains("gold_ingot");
        assert Arrays.equals(originalA, Files.readAllBytes(old));
        assert plan.revision().equals(service.plan(worldList, a, b, List.of(homes), multiverse).revision());

        var first = service.backup(UUID.randomUUID(), a, b, plan);
        Files.write(old, originalB);
        expectIo(() -> service.commit(first, plan));
        assert Arrays.equals(originalB, Files.readAllBytes(current));
        Files.write(old, originalA);
        assert service.pending().isEmpty();

        var completed = service.backup(UUID.randomUUID(), a, b, plan);
        service.commit(completed, plan);
        assert requireItem(old).equals("minecraft:gold_ingot");
        assert requireItem(current).equals("minecraft:diamond");
        var left = NbtTree.read(Files.readAllBytes(old)).compound();
        var right = NbtTree.read(Files.readAllBytes(current)).compound();
        assert left.get("BukkitValues").text().equals("OldIdentity");
        assert right.get("BukkitValues").text().equals("NewIdentity");
        assert Arrays.equals((int[]) left.get("UUID").value(), uuidInts(a.id()));
        assert Arrays.equals((int[]) right.get("UUID").value(), uuidInts(b.id()));
        assert left.get("XpLevel").number() == 9 && right.get("XpLevel").number() == 45;
        assert !Files.exists(stats.resolve(a.id() + ".json"));
        assert Files.exists(stats.resolve(b.id() + ".json"));
        assert Files.readString(homeA).contains("identity: Veteran") && Files.readString(homeA).contains("base:");
        assert Files.readString(homeB).contains("identity: NewPlayer") && Files.readString(homeB).contains("mine:");
        assert Files.readString(group.resolve("Veteran.json")).contains("new_group");
        assert !Files.exists(saves.resolve(a.id() + ".dat_old")) && Files.exists(saves.resolve(b.id() + ".dat_old"));
        assert service.pending().isEmpty();

        Files.delete(completed.resolve("files.completed")); // crash after all writes but before completion marker
        assert service.pending().equals(List.of(completed));
        service.restore(completed);
        service.restore(completed);
        assert Arrays.equals(originalA, Files.readAllBytes(old));
        assert Arrays.equals(originalB, Files.readAllBytes(current));
        assert Files.exists(stats.resolve(a.id() + ".json")) && !Files.exists(stats.resolve(b.id() + ".json"));
        assert Files.readString(homeA).contains("mine:") && Files.readString(homeB).contains("base:");
        assert service.pending().isEmpty();
    }

    static void exercisePoints(Path directory) throws Exception {
        var old = UUID.randomUUID();
        var current = UUID.randomUUID();
        Path db = directory.resolve("points.db");
        try (var store = new EconomyStore(db)) {
            store.migrate(old, 500);
            store.migrate(current, 20);
            assert !store.swapBalances(old, current, 499, 20, "stale");
            assert store.balance(old) == 500 && store.balance(current) == 20;
            assert store.swapBalances(old, current, 500, 20, "transfer");
        }
        try (var store = new EconomyStore(db)) {
            assert store.balance(old) == 20 && store.balance(current) == 500;
        }
    }

    record FakeContext(String getKey, String getValue) {}
    record FakeContexts(Set<FakeContext> toSet) {}
    record FakeNode(String getKey, boolean getValue, java.time.Instant getExpiry, FakeContexts getContexts) {}
    static final class FakeMap {
        final List<FakeNode> nodes = new ArrayList<>();
        public Collection<FakeNode> toCollection() { return nodes; }
        public void clear() { nodes.clear(); }
        public void add(FakeNode node) { nodes.add(node); }
    }
    static final class FakeUser {
        String group;
        final FakeMap map = new FakeMap();
        FakeUser(String group, FakeNode... nodes) { this.group = group; map.nodes.addAll(List.of(nodes)); }
        public String getPrimaryGroup() { return group; }
        public void setPrimaryGroup(String group) { this.group = group; }
        public FakeMap data() { return map; }
    }
    static final class FakeManager {
        int saves;
        int failAt;
        public CompletableFuture<Void> saveUser(FakeUser user) {
            return ++saves == failAt ? CompletableFuture.failedFuture(new IOException("fixture")) : CompletableFuture.completedFuture(null);
        }
    }

    static void exercisePermissions() throws Exception {
        var deny = new FakeNode("essentials.home", false, java.time.Instant.ofEpochSecond(2_000_000_000),
            new FakeContexts(Set.of(new FakeContext("world", "survival"))));
        var group = new FakeNode("group.vip", true, null, new FakeContexts(Set.of()));
        var old = new FakeUser("vip", deny, group);
        var current = new FakeUser("default");
        var manager = new FakeManager();
        var pair = new AccountTransferLuckPerms.Pair(manager, AccountTransferLuckPerms.user(old), AccountTransferLuckPerms.user(current));
        assert pair.old().facts().get(0).get("key").equals("essentials.home");
        assert pair.old().facts().get(0).get("value").equals(false);
        manager.failAt = 2;
        try { AccountTransferLuckPerms.swap(pair); throw new AssertionError("second write is meant to fail"); }
        catch (java.util.concurrent.ExecutionException expected) { assert expected.getSuppressed().length == 0; }
        assert old.group.equals("vip") && old.map.nodes.equals(List.of(deny, group)) && current.map.nodes.isEmpty();
        AccountTransferLuckPerms.swap(pair);
        assert old.group.equals("default") && old.map.nodes.isEmpty();
        assert current.group.equals("vip") && current.map.nodes.equals(List.of(deny, group));
    }

    static byte[] player(UUID id, String name, String identity, int level) throws Exception {
        var item = NbtTree.Tag.compound(Map.of("id", NbtTree.Tag.of("minecraft:" + name), "count", NbtTree.Tag.of(2)));
        var list = new NbtTree.Tag(NbtTree.LIST, new NbtTree.ListTag(NbtTree.COMPOUND, List.of(item)));
        return NbtTree.write(new NbtTree.Root("", Map.of(
            "UUID", new NbtTree.Tag(NbtTree.INT_ARRAY, uuidInts(id)), "XpLevel", NbtTree.Tag.of(level),
            "BukkitValues", NbtTree.Tag.of(identity), "Inventory", list, "EnderItems", list), true));
    }

    static String requireItem(Path path) throws Exception {
        var tree = NbtTree.read(Files.readAllBytes(path));
        return tree.compound().get("Inventory").list().items().getFirst().compound().get("id").text();
    }

    static int[] uuidInts(UUID id) {
        long high = id.getMostSignificantBits(), low = id.getLeastSignificantBits();
        return new int[]{(int)(high >>> 32), (int) high, (int)(low >>> 32), (int) low};
    }

    interface Work { void run() throws Exception; }
    static void expectIo(Work work) throws Exception {
        try { work.run(); throw new AssertionError("expected stale file failure"); }
        catch (IOException expected) { assert expected.getMessage().contains("geändert"); }
    }
}
