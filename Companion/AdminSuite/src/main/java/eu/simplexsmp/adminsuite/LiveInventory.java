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

    LiveInventory(SimplexAdminSuite plugin) {
        this.plugin = plugin;
        textures = new InventoryTextures(plugin.getDataFolder().toPath());
    }

    private record Snapshot(Player player, List<ItemStack> items, String revision) {
        ItemStack item(int slot) { return items.get(slot); }
        void set(int slot, ItemStack item) {
            if (slot < 36) player.getInventory().setItem(slot, item);
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
        if (!path.equals("/v1/invsee") && !path.equals("/v1/invsee/pack") && !path.equals("/v1/invsee/icons")) return false;
        api.requirePermission(key, READ);
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
                var snapshot = snapshot(query.getOrDefault("player", ""));
                String known = query.getOrDefault("revision", "");
                var base = SimplexAdminSuite.appMap("revision", snapshot.revision, "textures", textures.generation(),
                    "editable", edit, "pack", key.scope().equals("full") && api.allows(key, PACK), "unchanged", known.equals(snapshot.revision));
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
                var snapshot = snapshot(AiAdmin.text(body, "player"));
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

    private static int slot(Object value) {
        if (!(value instanceof Number n) || n.doubleValue() != n.intValue() || n.intValue() < 0 || n.intValue() >= 68
            || n.intValue() >= 36 && n.intValue() <= 40) throw new IllegalArgumentException("Platz ist nicht bearbeitbar");
        return n.intValue();
    }

    @Override public void close() { textures.close(); }
}
