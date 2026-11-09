package eu.simplexsmp.adminsuite;

import com.sun.net.httpserver.HttpExchange;
import java.security.MessageDigest;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

final class LiveInventory implements AutoCloseable {
    private static final String READ = "data.inventory", EDIT = "inventory.edit", PACK = "inventory.pack";
    private final SimplexAdminSuite plugin;
    private final InventoryTextures textures;
    private final PlayerNbtAccess playerNbt;
    final ItemLocker locker;

    LiveInventory(SimplexAdminSuite plugin) {
        this.plugin = plugin;
        playerNbt = new PlayerNbtAccess(plugin);
        textures = new InventoryTextures(plugin.getDataFolder().toPath());
        ItemLocker loaded = null;
        try { loaded = new ItemLocker(plugin); Bukkit.getPluginManager().registerEvents(loaded, plugin); }
        catch (java.io.IOException | RuntimeException error) { plugin.getLogger().warning("Einbehaltungsablage nicht geladen: " + error.getMessage()); }
        locker = loaded;
    }

    private record Snapshot(Player player, List<ItemStack> items, String revision) {
        ItemStack item(int slot) { return items.get(slot); }
        void set(int slot, ItemStack item) {
            if (slot < 36) player.getInventory().setItem(slot, item);
            else if (slot < 40) {
                // getArmorContents order: boots, leggings, chestplate, helmet.
                ItemStack[] armor = player.getInventory().getArmorContents();
                armor[slot - 36] = item;
                player.getInventory().setArmorContents(armor);
            } else if (slot == 40) player.getInventory().setItemInOffHand(item);
            else player.getEnderChest().setItem(slot - 41, item);
        }
    }

    private Snapshot snapshot(String target) throws Exception {
        Player player;
        try { player = Bukkit.getPlayer(UUID.fromString(target)); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("Spieler-UUID fehlt"); }
        if (player == null) throw new AppApiManager.ApiError(409, "Spieler ist gerade offline");
        List<ItemStack> items = new ArrayList<>(Arrays.asList(player.getInventory().getStorageContents()));
        items.addAll(Arrays.asList(player.getInventory().getArmorContents()));
        items.add(player.getInventory().getItemInOffHand());
        items.addAll(Arrays.asList(player.getEnderChest().getContents()));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(player.getUniqueId().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        for (ItemStack item : items) {
            byte[] bytes = empty(item) ? new byte[0] : item.serializeAsBytes();
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
            digest.update(bytes);
        }
        return new Snapshot(player, items, HexFormat.of().formatHex(digest.digest()));
    }

    private static boolean empty(ItemStack item) { return item == null || item.getType().isAir() || item.getAmount() <= 0; }

    private static List<Map<String, Object>> slots(Snapshot snapshot) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int slot = 0; slot < snapshot.items.size(); slot++) {
            ItemStack item = snapshot.item(slot);
            if (empty(item)) continue;
            rows.add(describe(item, slot));
        }
        return rows;
    }

    private static final String[] ROMAN = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};

    /** 10.15.0: name, amount, enchantments, durability, lore and shulker contents for the inventory views. */
    static Map<String, Object> describe(ItemStack item, int slot) {
        var meta = item.getItemMeta();
        var row = SimplexAdminSuite.appMap("slot", slot, "material", item.getType().getKey().getKey(), "name", ItemLocker.label(item),
            "amount", item.getAmount(), "max", item.getMaxStackSize(), "custom", meta != null && meta.hasDisplayName());
        if (meta == null) return row;
        Map<org.bukkit.enchantments.Enchantment, Integer> enchants = new LinkedHashMap<>(meta.getEnchants());
        if (meta instanceof org.bukkit.inventory.meta.EnchantmentStorageMeta stored) enchants.putAll(stored.getStoredEnchants());
        if (!enchants.isEmpty() || meta.hasEnchantmentGlintOverride() && Boolean.TRUE.equals(meta.getEnchantmentGlintOverride())) row.put("enchanted", true);
        if (!enchants.isEmpty()) row.put("enchantments", enchants.entrySet().stream().limit(12).map(e -> e.getKey().getKey().getKey().replace('_', ' ')
            + " " + (e.getValue() < ROMAN.length ? ROMAN[e.getValue()] : String.valueOf(e.getValue()))).toList());
        int max = item.getType().getMaxDurability();
        if (max > 0 && meta instanceof org.bukkit.inventory.meta.Damageable damage) { row.put("durability", Math.max(0, max - damage.getDamage())); row.put("maxDurability", max); }
        var lore = meta.lore();
        if (lore != null && !lore.isEmpty()) row.put("lore", lore.stream().limit(6).map(line -> SparkProfile.clip(PlainTextComponentSerializer.plainText().serialize(line), 120)).toList());
        if (meta instanceof org.bukkit.inventory.meta.BlockStateMeta block && block.hasBlockState() && block.getBlockState() instanceof org.bukkit.block.ShulkerBox box) {
            List<Map<String, Object>> contents = new ArrayList<>();
            ItemStack[] stored = box.getInventory().getContents();
            for (int i = 0; i < stored.length; i++) if (!empty(stored[i]))
                contents.add(SimplexAdminSuite.appMap("slot", i, "material", stored[i].getType().getKey().getKey(), "name", ItemLocker.label(stored[i]), "amount", stored[i].getAmount()));
            row.put("contents", contents);
        }
        return row;
    }

    // ------------------------------------------------------------------ 10.15.0 any player, online or offline

    /** One inventory taking part in a move: a live player or a saved player file. Changes are applied only on commit. */
    private abstract static class Side {
        final UUID id; final String name; final String revision;
        final Map<Integer, ItemStack> pending = new LinkedHashMap<>();
        Side(UUID id, String name, String revision) { this.id = id; this.name = name; this.revision = revision; }
        abstract ItemStack read(int slot);
        ItemStack get(int slot) { return pending.containsKey(slot) ? pending.get(slot) : read(slot); }
        void put(int slot, ItemStack item) { pending.put(slot, empty(item) ? null : item.clone()); }
        abstract boolean online();
    }

    private final class LiveSide extends Side {
        final Snapshot snapshot;
        LiveSide(Snapshot snapshot) { super(snapshot.player.getUniqueId(), snapshot.player.getName(), snapshot.revision); this.snapshot = snapshot; }
        ItemStack read(int slot) { ItemStack item = snapshot.item(slot); return empty(item) ? null : item.clone(); }
        boolean online() { return true; }
        void apply() { pending.forEach(snapshot::set); }
    }

    private final class FileSide extends Side {
        final FileRef data; final PlayerInventoryFile file; final byte[] before;
        FileSide(UUID id, String name, FileRef data, PlayerInventoryFile file) { super(id, name, "offline-" + data.sha); this.data = data; this.before = data.bytes; this.file = file; }
        ItemStack read(int slot) { var item = file.get(slot); return item == null ? null : toStack(item, file.dataVersion); }
        boolean online() { return false; }
        byte[] render() throws java.io.IOException {
            int server = Bukkit.getUnsafe().getDataVersion();
            if (file.dataVersion != server) throw new AppApiManager.ApiError(409, name + "s Spielerdatei stammt aus einer älteren Minecraft-Version. "
                + "Der Spieler muss einmal beitreten, bevor sie bearbeitet werden kann.");
            for (var change : pending.entrySet()) file.set(change.getKey(), change.getValue() == null ? null : toNbt(change.getValue()));
            return file.bytes();
        }
    }

    static ItemStack toStack(Map<String, NbtTree.Tag> item, int dataVersion) {
        try {
            Map<String, NbtTree.Tag> copy = PlayerInventoryFile.withoutSlot(item);
            copy.put("DataVersion", NbtTree.Tag.of(dataVersion));
            return ItemStack.deserializeBytes(NbtTree.write(new NbtTree.Root("", copy, true)));
        } catch (java.io.IOException | RuntimeException broken) {
            throw new AppApiManager.ApiError(422, "Ein Item in der Spielerdatei konnte nicht gelesen werden.");
        }
    }

    static Map<String, NbtTree.Tag> toNbt(ItemStack item) {
        try {
            Map<String, NbtTree.Tag> map = new LinkedHashMap<>(NbtTree.read(item.serializeAsBytes()).compound());
            map.remove("DataVersion");
            return map;
        } catch (java.io.IOException error) { throw new IllegalStateException("Item konnte nicht gespeichert werden", error); }
    }

    private Side open(String target) throws Exception {
        UUID id;
        try { id = UUID.fromString(target); } catch (IllegalArgumentException name) { id = plugin.discordAdminResolvePlayer(target); }
        if (id == null) throw new AppApiManager.ApiError(404, "Spieler nicht gefunden");
        Player online = Bukkit.getPlayer(id);
        if (online != null) return new LiveSide(snapshot(id.toString()));
        FileRef data = fileRef(id);
        String name = Optional.ofNullable(Bukkit.getOfflinePlayer(id).getName()).orElse(id.toString().substring(0, 8));
        try { return new FileSide(id, name, data, new PlayerInventoryFile(data.bytes)); }
        catch (java.io.IOException broken) { throw new AppApiManager.ApiError(422, "Spielerdatei von " + name + " konnte nicht gelesen werden: " + broken.getMessage()); }
    }

    private record FileRef(String path, String sha, byte[] bytes) { }

    private FileRef fileRef(UUID player) throws Exception {
        if (Bukkit.getPlayer(player) != null) throw new AppApiManager.ApiError(409, "Spieler ist online. Live-Inventar öffnen.");
        var files = PlayerNbtLocator.find(java.nio.file.Path.of("."), Bukkit.getWorlds().stream().map(org.bukkit.World::getWorldFolder).toList(), player);
        if (files.isEmpty()) throw new AppApiManager.ApiError(404, "Keine gespeicherte Spielerdatei vorhanden");
        if (files.size() != 1) throw new AppApiManager.ApiError(409, "Mehrere Spielerdateien vorhanden. Eindeutige Datei im NBT-Werkzeug wählen.");
        var saved = AppApiManager.object(playerNbt.read(player.toString(), files.get(0).id()));
        return new FileRef(files.get(0).id(), AiAdmin.text(saved, "sha256"), Base64.getDecoder().decode(AiAdmin.text(saved, "data")));
    }

    private Map<String, Object> offlineView(String player, String known, boolean write) throws Exception {
        Side side = open(player);
        if (side instanceof LiveSide) throw new AppApiManager.ApiError(409, "Spieler ist online. Live-Inventar öffnen.");
        FileSide file = (FileSide) side;
        boolean current = file.file.dataVersion == Bukkit.getUnsafe().getDataVersion();
        var view = SimplexAdminSuite.appMap("revision", file.revision, "textures", textures.generation(), "editable", write && current,
            "transfer", write && current && locker != null, "pack", false, "offline", true, "name", file.name, "unchanged", known.equals(file.revision),
            "outdated", !current);
        if (!known.equals(file.revision)) {
            List<Map<String, Object>> rows = new ArrayList<>();
            file.file.items().forEach((slot, item) -> { try { rows.add(describe(toStack(item, file.file.dataVersion), slot)); } catch (AppApiManager.ApiError unreadable) {
                rows.add(SimplexAdminSuite.appMap("slot", slot, "material", PlayerInventoryFile.empty(item) ? "barrier" : item.get("id").text().replace("minecraft:", ""), "name", "Unlesbares Item", "amount", 1)); } });
            view.put("slots", rows);
        }
        return view;
    }

    /**
     * Moves or swaps stacks between two inventories (the same player, two players, online or offline, or into the
     * Einbehaltungsablage). Both revisions must match; files are written with backup and verified, and a failure of the
     * second write restores the first file. Live inventories change last, so nothing can be duplicated or lost.
     */
    private Map<String, Object> move(Map<String, Object> body, String actor) throws Exception {
        var from = AppApiManager.object(body.get("from"));
        var to = AppApiManager.object(body.get("to"));
        String fromPlayer = AiAdmin.text(from, "player"), toPlayer = AiAdmin.text(to, "player");
        boolean toLocker = toPlayer.equals("locker");
        Side a = open(fromPlayer);
        Side b = toLocker ? null : a.id.toString().equals(resolve(toPlayer)) ? a : open(toPlayer);
        if (!a.revision.equals(AiAdmin.text(from, "revision"))) throw new AppApiManager.ApiError(409, a.name + "s Inventar hat sich geändert. Ansicht wurde aktualisiert – bitte erneut wählen.");
        boolean anySlot = !(to.get("slot") instanceof Number n0 && n0.intValue() >= 0) && AiAdmin.text(to, "revision").isBlank();
        if (b != null && b != a && !anySlot && !b.revision.equals(AiAdmin.text(to, "revision"))) throw new AppApiManager.ApiError(409, b.name + "s Inventar hat sich geändert. Ansicht wurde aktualisiert – bitte erneut wählen.");
        int fromSlot = anySlot(from.get("slot"));
        ItemStack source = a.get(fromSlot);
        if (empty(source)) throw new IllegalArgumentException("Der gewählte Platz ist leer");
        String mode = AiAdmin.text(body, "mode").equals("swap") ? "swap" : "move";
        int amount = body.get("amount") instanceof Number n ? Math.max(1, Math.min(source.getAmount(), n.intValue())) : source.getAmount();
        String reason = PowerBot.clean(AiAdmin.text(body, "reason"), 300);
        int moved;
        String targetLabel;
        if (toLocker) {
            ItemStack part = source.clone(); part.setAmount(amount);
            moved = amount; targetLabel = "Einbehaltungsablage";
            ItemStack rest = source.clone(); rest.setAmount(source.getAmount() - amount);
            a.put(fromSlot, rest.getAmount() > 0 ? rest : null);
            commit(List.of(a), () -> locker.store(part, a.id, a.name, actor, reason));
        } else {
            int toSlot = to.get("slot") instanceof Number n && n.intValue() >= 0 ? anySlot(n) : -1;
            if (toSlot < 0) toSlot = freeSlot(b, source, b == a ? fromSlot : -1);
            if (toSlot < 0) throw new AppApiManager.ApiError(409, b.name + " hat keinen freien Platz im Inventar.");
            if (b == a && toSlot == fromSlot) throw new IllegalArgumentException("Quelle und Ziel sind derselbe Platz");
            ItemStack target = b.get(toSlot);
            if (mode.equals("swap") || !empty(target) && !target.isSimilar(source)) {
                if (!mode.equals("swap")) throw new AppApiManager.ApiError(409, "Der Zielplatz ist belegt. „Tauschen“ wählen oder einen freien Platz nehmen.");
                a.put(fromSlot, target);
                b.put(toSlot, source);
                moved = source.getAmount();
            } else if (empty(target)) {
                ItemStack part = source.clone(); part.setAmount(amount);
                ItemStack rest = source.clone(); rest.setAmount(source.getAmount() - amount);
                b.put(toSlot, part);
                a.put(fromSlot, rest.getAmount() > 0 ? rest : null);
                moved = amount;
            } else {
                int space = target.getMaxStackSize() - target.getAmount();
                moved = Math.min(space, amount);
                if (moved <= 0) throw new AppApiManager.ApiError(409, "Der Zielstapel ist bereits voll.");
                ItemStack merged = target.clone(); merged.setAmount(target.getAmount() + moved);
                ItemStack rest = source.clone(); rest.setAmount(source.getAmount() - moved);
                b.put(toSlot, merged);
                a.put(fromSlot, rest.getAmount() > 0 ? rest : null);
            }
            targetLabel = b.name;
            commit(b == a ? List.of(a) : List.of(a, b), null);
        }
        String what = moved + "× " + ItemLocker.label(source);
        String suffix = reason.isBlank() ? "" : " · " + reason;
        plugin.suiteItemHistory(a.id, "Vom Team " + (mode.equals("swap") ? "getauscht" : "entnommen") + " (" + actor + ") → " + targetLabel + ": " + what + suffix);
        if (b != null && b != a) plugin.suiteItemHistory(b.id, "Vom Team erhalten (" + actor + ") von " + a.name + ": " + what + suffix);
        plugin.powerAudit(actor, a.id, "Item " + (mode.equals("swap") ? "getauscht" : "verschoben"), what + " → " + targetLabel + (a.online() ? "" : " (offline)") + suffix);
        return SimplexAdminSuite.appMap("ok", true, "moved", moved, "target", targetLabel);
    }

    private String resolve(String target) {
        try { return UUID.fromString(target).toString(); }
        catch (IllegalArgumentException name) { UUID id = plugin.discordAdminResolvePlayer(target); return id == null ? "" : id.toString(); }
    }

    private static int freeSlot(Side side, ItemStack like, int skip) {
        for (int slot = 0; slot < 36; slot++) {
            if (slot == skip) continue;
            ItemStack item = side.get(slot);
            if (!empty(item) && item.isSimilar(like) && item.getAmount() < item.getMaxStackSize()) return slot;
        }
        for (int slot = 0; slot < 36; slot++) if (slot != skip && empty(side.get(slot))) return slot;
        return -1;
    }

    /** Writes all files first (with rollback), then the locker callback, then live changes. */
    private void commit(List<Side> sides, Runnable afterFiles) throws Exception {
        List<FileSide> written = new ArrayList<>();
        Map<FileSide, String> shas = new HashMap<>();
        try {
            for (Side side : sides) if (side instanceof FileSide file && !file.pending.isEmpty()) {
                byte[] next = file.render();
                if (Bukkit.getPlayer(file.id) != null) throw new AppApiManager.ApiError(409, file.name + " ist gerade beigetreten. Bitte erneut versuchen.");
                var result = AppApiManager.object(playerNbt.write(SimplexAdminSuite.appMap("player", file.id.toString(), "path", file.data.path,
                    "expected", file.data.sha, "data", Base64.getEncoder().encodeToString(next)), "Simplex Panel"));
                shas.put(file, AiAdmin.text(result, "sha256"));
                written.add(file);
            }
            if (afterFiles != null) afterFiles.run();
        } catch (Exception failure) {
            for (FileSide file : written) {
                try { playerNbt.write(SimplexAdminSuite.appMap("player", file.id.toString(), "path", file.data.path, "expected", shas.get(file),
                    "data", Base64.getEncoder().encodeToString(file.before)), "Simplex Panel (Rückgängig)"); }
                catch (Exception restore) { plugin.getLogger().severe("Inventar-Rücksicherung fehlgeschlagen für " + file.name + ": " + restore.getMessage()); }
            }
            throw failure;
        }
        for (Side side : sides) if (side instanceof LiveSide live) live.apply();
    }

    boolean route(AppApiManager api, HttpExchange exchange, AppApiSecurity.Key key, String path, String method, long rev) throws Exception {
        if (path.equals("/v1/invsee/transfer") || path.equals("/v1/locker")) return transferRoute(api, exchange, key, path, method, rev);
        if (path.equals("/v1/invsee/move")) {
            api.requirePermission(key, READ);
            api.requirePermission(key, EDIT);
            if (!method.equals("POST")) throw new AppApiManager.ApiError(405, "Methode nicht erlaubt");
            if (!AppApiSecurity.permitted(key, "manage")) throw new AppApiManager.ApiError(403, "Verschieben benötigt manage-Zugriff");
            if (!api.rate("invsee-move:" + key.id(), 60)) throw new AppApiManager.ApiError(429, "Bitte kurz warten.");
            var body = AppApiManager.body(exchange, 4096);
            if (!Boolean.TRUE.equals(body.get("confirmed"))) throw new IllegalArgumentException("Bestätigung fehlt");
            boolean offlineRights = key.scope().equals("full") && api.allows(key, "player-nbt.write");
            AppApiManager.send(exchange, 200, api.onMain(rev, () -> {
                api.requirePermission(key, EDIT);
                for (String side : List.of("from", "to")) {
                    String target = AiAdmin.text(AppApiManager.object(body.get(side)), "player");
                    if (target.equals("locker")) { if (locker == null) throw new AppApiManager.ApiError(503, "Einbehaltungsablage nicht geladen"); continue; }
                    UUID id = null;
                    try { id = UUID.fromString(target); } catch (IllegalArgumentException name) { id = plugin.discordAdminResolvePlayer(target); }
                    if (id != null && Bukkit.getPlayer(id) == null && !offlineRights)
                        throw new AppApiManager.ApiError(403, "Offline-Inventare benötigen Vollzugriff und das Recht player-nbt.write.");
                }
                return move(body, key.label());
            }));
            return true;
        }
        if (!path.equals("/v1/invsee") && !path.equals("/v1/invsee/pack") && !path.equals("/v1/invsee/icons")) return false;
        if (!path.endsWith("/icons") || !api.allows(key, "data.snapshot-content")) api.requirePermission(key, READ);
        var query = AppApiManager.query(exchange.getRequestURI());
        Object out;
        if (path.endsWith("/pack") && method.equals("POST")) {
            api.requirePermission(key, PACK);
            if (!key.scope().equals("full")) throw new AppApiManager.ApiError(403, "Paketverwaltung benötigt Vollzugriff");
            if (!api.rate("invsee-pack:" + key.id(), 4)) throw new AppApiManager.ApiError(429, "Bitte kurz warten.");
            if ("reset".equals(query.get("mode"))) {
                if (!Boolean.TRUE.equals(AppApiManager.body(exchange, 256).get("confirmed"))) throw new IllegalArgumentException("Bestätigung fehlt");
                textures.reset();
            } else textures.importPack(exchange.getRequestBody());
            out = Map.of("ok", true);
        } else if (path.endsWith("/icons") && method.equals("GET")) {
            String[] names = query.getOrDefault("items", "").split(",");
            if (names.length > 70) throw new IllegalArgumentException("Zu viele Texturen");
            out = textures.read(new LinkedHashSet<>(Arrays.asList(names)));
        } else if (path.equals("/v1/invsee") && method.equals("GET")) {
            boolean edit = api.allows(key, EDIT) && AppApiSecurity.permitted(key, "manage");
            out = api.onMain(rev, () -> {
                String player = query.getOrDefault("player", "");
                String known = query.getOrDefault("revision", "");
                String mode = query.getOrDefault("offline", "false");
                if (mode.equals("auto")) {
                    UUID id = UUID.fromString(player);
                    mode = Bukkit.getPlayer(id) == null ? "true" : "false";
                }
                if (mode.equals("true") && query.containsKey("v2")) {
                    return offlineView(player, known, edit && key.scope().equals("full") && api.allows(key, "player-nbt.write"));
                }
                if (Boolean.parseBoolean(mode)) {
                    OfflineData data = offline(player);
                    boolean write = edit && key.scope().equals("full") && api.allows(key, "player-nbt.write") && data.inventory.editable();
                    var view = SimplexAdminSuite.appMap("revision", data.revision(), "textures", textures.generation(),
                        "editable", write, "pack", false, "offline", true, "unchanged", known.equals(data.revision()));
                    if (!known.equals(data.revision())) view.put("slots", data.inventory.rows());
                    return view;
                }
                var snapshot = snapshot(player);
                var base = SimplexAdminSuite.appMap("revision", snapshot.revision, "textures", textures.generation(),
                    "editable", edit, "pack", key.scope().equals("full") && api.allows(key, PACK), "unchanged", known.equals(snapshot.revision),
                    "name", snapshot.player.getName(), "transfer", edit && locker != null);
                if (!known.equals(snapshot.revision)) base.put("slots", slots(snapshot));
                return base;
            });
        } else if (path.equals("/v1/invsee") && method.equals("POST")) {
            api.requirePermission(key, EDIT);
            if (!AppApiSecurity.permitted(key, "manage")) throw new AppApiManager.ApiError(403, "Bearbeiten benötigt manage-Zugriff");
            var body = AppApiManager.body(exchange, 4096);
            if (!Boolean.TRUE.equals(body.get("confirmed"))) throw new IllegalArgumentException("Bestätigung fehlt");
            out = api.onMain(rev, () -> {
                api.requirePermission(key, READ);
                api.requirePermission(key, EDIT);
                String player = AiAdmin.text(body, "player");
                if (Boolean.TRUE.equals(body.get("offline"))) {
                    api.requirePermission(key, "player-nbt.write");
                    if (!key.scope().equals("full")) throw new AppApiManager.ApiError(403, "Offline-Bearbeitung benötigt Vollzugriff");
                    OfflineData data = offline(player);
                    if (!data.revision().equals(body.get("revision"))) throw new AppApiManager.ApiError(409, "Offline-Datei wurde geändert. Bitte neu laden.");
                    byte[] updated = data.inventory.swap(slot(body.get("from")), slot(body.get("to")));
                    return playerNbt.write(SimplexAdminSuite.appMap("player", player, "path", data.path, "expected", data.sha,
                        "data", Base64.getEncoder().encodeToString(updated)), key.label());
                }
                var snapshot = snapshot(player);
                if (!snapshot.revision.equals(body.get("revision"))) throw new AppApiManager.ApiError(409, "Inventar wurde inzwischen geändert. Bitte neu auswählen.");
                int from = slot(body.get("from")), to = slot(body.get("to"));
                if (from == to || empty(snapshot.item(from))) throw new IllegalArgumentException("Leerer oder identischer Quellplatz");
                ItemStack source = snapshot.item(from), target = snapshot.item(to);
                snapshot.set(from, empty(target) ? null : target.clone());
                try { snapshot.set(to, source.clone()); }
                catch (RuntimeException error) { snapshot.set(from, source.clone()); throw error; }
                plugin.powerAudit(key.label(), snapshot.player.getUniqueId(), "Live-Inventar geändert", "Tausch " + from + " ↔ " + to);
                return Map.of("ok", true);
            });
        } else throw new AppApiManager.ApiError(405, "Methode nicht erlaubt");
        AppApiManager.send(exchange, 200, out);
        return true;
    }

    /**
     * 10.14.0: move a stack (or part of it) from one online player to another online player or into the
     * Einbehaltungsablage, and manage the locker. The source revision must match, so nothing moves twice.
     */
    private boolean transferRoute(AppApiManager api, HttpExchange exchange, AppApiSecurity.Key key, String path, String method, long rev) throws Exception {
        api.requirePermission(key, READ);
        boolean manage = api.allows(key, EDIT) && AppApiSecurity.permitted(key, "manage");
        if (locker == null) throw new AppApiManager.ApiError(503, "Einbehaltungsablage nicht geladen");
        if (path.equals("/v1/locker") && method.equals("GET")) {
            AppApiManager.send(exchange, 200, api.onMain(rev, () -> locker.data(manage)));
            return true;
        }
        if (!method.equals("POST")) throw new AppApiManager.ApiError(405, "Methode nicht erlaubt");
        if (!manage) throw new AppApiManager.ApiError(403, "Items verschieben benötigt inventory.edit und manage-Zugriff");
        if (!api.rate("invsee-transfer:" + key.id(), 40)) throw new AppApiManager.ApiError(429, "Bitte kurz warten.");
        var body = AppApiManager.body(exchange, 4096);
        if (!Boolean.TRUE.equals(body.get("confirmed"))) throw new IllegalArgumentException("Bestätigung fehlt");
        Object out = api.onMain(rev, () -> {
            api.requirePermission(key, READ);
            api.requirePermission(key, EDIT);
            String actor = key.label();
            if (path.equals("/v1/locker")) {
                String operation = AiAdmin.text(body, "operation"), id = AiAdmin.text(body, "id");
                switch (operation) {
                    case "give" -> {
                        String wanted = AiAdmin.text(body, "target");
                        UUID targetId = UUID.fromString(resolve(wanted).isEmpty() ? "00000000-0000-0000-0000-000000000000" : resolve(wanted));
                        if (Bukkit.getPlayer(targetId) == null) {
                            // 10.15.0: offline players receive the item in the first free slot of their saved file.
                            if (!key.scope().equals("full") || !api.allows(key, "player-nbt.write"))
                                throw new AppApiManager.ApiError(403, "Offline-Spieler benötigen Vollzugriff und das Recht player-nbt.write.");
                            Side side = open(targetId.toString());
                            ItemStack item = locker.peek(id);
                            int slot = -1;
                            for (int i = 0; i < 36 && slot < 0; i++) if (empty(side.get(i))) slot = i;
                            if (slot < 0) throw new AppApiManager.ApiError(409, side.name + " hat keinen freien Platz im Inventar.");
                            side.put(slot, item);
                            commit(List.of(side), () -> locker.markGiven(id, side.id, side.name, actor));
                            return SimplexAdminSuite.appMap("ok", true, "moved", item.getAmount(), "target", side.name);
                        }
                        Player target = online(wanted);
                        return SimplexAdminSuite.appMap("ok", true, "moved", locker.give(id, target, actor), "target", target.getName());
                    }
                    case "delete" -> locker.delete(id, actor);
                    case "note" -> locker.note(id, AiAdmin.text(body, "note"), actor);
                    default -> throw new IllegalArgumentException("Unbekannte Ablage-Aktion");
                }
                return Map.of("ok", true);
            }
            var snapshot = snapshot(AiAdmin.text(body, "player"));
            if (!snapshot.revision.equals(body.get("revision"))) throw new AppApiManager.ApiError(409, "Inventar wurde inzwischen geändert. Bitte neu auswählen.");
            int slot = anySlot(body.get("slot"));
            ItemStack source = snapshot.item(slot);
            if (empty(source)) throw new IllegalArgumentException("Der gewählte Platz ist leer");
            int amount = body.get("amount") instanceof Number n ? Math.max(1, Math.min(source.getAmount(), n.intValue())) : source.getAmount();
            ItemStack moving = source.clone();
            moving.setAmount(amount);
            String reason = PowerBot.clean(AiAdmin.text(body, "reason"), 300);
            Player from = snapshot.player;
            String destination = AiAdmin.text(body, "destination");
            int moved;
            String targetName;
            if (destination.equals("locker")) {
                locker.store(moving, from.getUniqueId(), from.getName(), actor, reason);
                moved = amount;
                targetName = "Einbehaltungsablage";
            } else {
                Player target = online(AiAdmin.text(body, "target"));
                if (target.getUniqueId().equals(from.getUniqueId())) throw new IllegalArgumentException("Quelle und Ziel sind derselbe Spieler. Zum Umsortieren Slots tauschen.");
                var leftover = target.getInventory().addItem(moving.clone());
                moved = amount - leftover.values().stream().mapToInt(ItemStack::getAmount).sum();
                if (moved <= 0) throw new AppApiManager.ApiError(409, target.getName() + " hat keinen freien Platz im Inventar.");
                targetName = target.getName();
                plugin.suiteItemHistory(target.getUniqueId(), "Vom Team erhalten (" + actor + ") von " + from.getName() + ": " + moved + "× " + ItemLocker.label(moving)
                    + (reason.isBlank() ? "" : " · " + reason));
            }
            int left = source.getAmount() - moved;
            ItemStack rest = source.clone();
            rest.setAmount(left);
            snapshot.set(slot, left > 0 ? rest : null);
            plugin.suiteItemHistory(from.getUniqueId(), "Vom Team entnommen (" + actor + ") → " + targetName + ": " + moved + "× " + ItemLocker.label(moving)
                + (reason.isBlank() ? "" : " · " + reason));
            plugin.powerAudit(actor, from.getUniqueId(), "Item verschoben", moved + "× " + ItemLocker.label(moving) + " → " + targetName + (reason.isBlank() ? "" : " · " + reason));
            return SimplexAdminSuite.appMap("ok", true, "moved", moved, "target", targetName);
        });
        AppApiManager.send(exchange, 200, out);
        return true;
    }

    private static Player online(String target) {
        Player player = null;
        try { player = Bukkit.getPlayer(UUID.fromString(target)); }
        catch (IllegalArgumentException name) { player = Bukkit.getPlayerExact(target); }
        if (player == null) throw new AppApiManager.ApiError(409, "Zielspieler ist gerade nicht online");
        return player;
    }

    private static int anySlot(Object value) {
        if (!(value instanceof Number n) || n.doubleValue() != n.intValue() || n.intValue() < 0 || n.intValue() >= 68)
            throw new IllegalArgumentException("Ungültiger Platz");
        return n.intValue();
    }

    private record OfflineData(String path, String sha, OfflineInventory inventory) {
        String revision() { return "offline-" + sha; }
    }

    private OfflineData offline(String target) throws Exception {
        UUID player = UUID.fromString(target);
        if (Bukkit.getPlayer(player) != null) throw new AppApiManager.ApiError(409, "Spieler ist online. Live-Inventar öffnen.");
        var files = PlayerNbtLocator.find(java.nio.file.Path.of("."), Bukkit.getWorlds().stream().map(org.bukkit.World::getWorldFolder).toList(), player);
        if (files.isEmpty()) throw new AppApiManager.ApiError(404, "Keine gespeicherte Spielerdatei vorhanden");
        if (files.size() != 1) throw new AppApiManager.ApiError(409, "Mehrere Spielerdateien vorhanden. Eindeutige Datei im NBT-Werkzeug wählen.");
        var path = files.get(0);
        var saved = AppApiManager.object(playerNbt.read(target, path.id()));
        byte[] bytes = Base64.getDecoder().decode(AiAdmin.text(saved, "data"));
        return new OfflineData(path.id(), AiAdmin.text(saved, "sha256"), new OfflineInventory(bytes));
    }

    private static int slot(Object value) {
        if (!(value instanceof Number n) || n.doubleValue() != n.intValue() || n.intValue() < 0 || n.intValue() >= 68
            || n.intValue() >= 36 && n.intValue() <= 40) throw new IllegalArgumentException("Platz ist nicht bearbeitbar");
        return n.intValue();
    }

    @Override public void close() { textures.close(); }
}
