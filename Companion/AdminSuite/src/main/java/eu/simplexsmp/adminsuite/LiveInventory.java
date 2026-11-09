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
            var meta = item.getItemMeta();
            var title = meta == null ? null : meta.displayName();
            String material = item.getType().getKey().getKey();
            String name = title == null ? material.replace('_', ' ') : PlainTextComponentSerializer.plainText().serialize(title);
            rows.add(SimplexAdminSuite.appMap("slot", slot, "material", material, "name", SparkProfile.clip(name, 120), "amount", item.getAmount()));
        }
        return rows;
    }

    boolean route(AppApiManager api, HttpExchange exchange, AppApiSecurity.Key key, String path, String method, long rev) throws Exception {
        if (path.equals("/v1/invsee/transfer") || path.equals("/v1/locker")) return transferRoute(api, exchange, key, path, method, rev);
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
                if (Boolean.parseBoolean(query.getOrDefault("offline", "false"))) {
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
                        Player target = online(AiAdmin.text(body, "target"));
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
