package eu.simplexsmp.adminsuite;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * 10.14.0: Einbehaltungsablage. Items that staff took away from players (e.g. stolen goods) are kept here with
 * origin, reason and staff member until they are returned, handed to someone else or deleted. Main thread only.
 * Each item is stored exactly as Paper serialises it, so enchantments, names and contents survive unchanged.
 */
final class ItemLocker implements Listener {
    static final String PERMISSION = "simplexadminsuite.locker";
    private static final long KEEP_RESOLVED = 30L * 24 * 60 * 60 * 1000;
    private final SimplexAdminSuite plugin;
    private final Path file;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();

    record Entry(String id, long at, String material, String name, int amount, String data, String fromId, String fromName,
                 String reason, String by, String status, long resolvedAt, String resolvedBy, String toName, String note) {
        Map<String, Object> row(boolean withData) {
            var row = SimplexAdminSuite.appMap("id", id, "at", at, "material", material, "name", name, "amount", amount,
                "fromId", fromId, "fromName", fromName, "reason", reason, "by", by, "status", status, "resolvedAt", resolvedAt,
                "resolvedBy", resolvedBy, "toName", toName, "note", note);
            if (withData) row.put("data", data);
            return row;
        }
        boolean held() { return status.equals("held"); }
        Entry resolve(String next, String actor, String target, int remaining, String remainingData) {
            boolean partial = remaining > 0;
            return new Entry(id, at, material, name, partial ? remaining : amount, partial ? remainingData : data, fromId, fromName, reason, by,
                partial ? "held" : next, partial ? 0 : System.currentTimeMillis(), partial ? "" : actor, partial ? "" : target, note);
        }
        Entry noted(String text) { return new Entry(id, at, material, name, amount, data, fromId, fromName, reason, by, status, resolvedAt, resolvedBy, toName, text); }
    }

    ItemLocker(SimplexAdminSuite plugin) throws IOException {
        this.plugin = plugin;
        file = plugin.getDataFolder().toPath().resolve("item-locker.json");
        if (Files.isRegularFile(file)) {
            Object data = DiscordBotManager.Json.parse(Files.readString(file));
            if (!(data instanceof List<?> rows)) throw new IOException("Einbehaltungsablage: ungültiges Format");
            for (Object value : rows) {
                var row = AppApiManager.object(value);
                String id = AiAdmin.text(row, "id");
                if (id.isBlank()) continue;
                entries.put(id, new Entry(id, AiAdmin.number(row.get("at")), AiAdmin.text(row, "material"), AiAdmin.text(row, "name"),
                    (int) AiAdmin.number(row.get("amount")), AiAdmin.text(row, "data"), AiAdmin.text(row, "fromId"), AiAdmin.text(row, "fromName"),
                    AiAdmin.text(row, "reason"), AiAdmin.text(row, "by"), AiAdmin.text(row, "status").isBlank() ? "held" : AiAdmin.text(row, "status"),
                    AiAdmin.number(row.get("resolvedAt")), AiAdmin.text(row, "resolvedBy"), AiAdmin.text(row, "toName"), AiAdmin.text(row, "note")));
            }
        }
    }

    private void persist(LinkedHashMap<String, Entry> next) {
        long now = System.currentTimeMillis();
        next.values().removeIf(entry -> !entry.held() && now - entry.resolvedAt > KEEP_RESOLVED);
        try {
            Path temp = Files.createTempFile(file.getParent(), "item-locker-", ".json");
            try {
                Files.writeString(temp, DiscordBotManager.Json.stringify(next.values().stream().map(entry -> entry.row(true)).toList()));
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temp); }
        } catch (IOException error) { throw new IllegalStateException("Einbehaltungsablage konnte nicht gespeichert werden", error); }
        entries.clear();
        entries.putAll(next);
    }

    static String label(ItemStack item) {
        var meta = item.getItemMeta();
        var title = meta == null ? null : meta.displayName();
        String material = item.getType().getKey().getKey();
        return SparkProfile.clip(title == null ? material.replace('_', ' ') : PlainTextComponentSerializer.plainText().serialize(title), 120);
    }

    /** Stores a copy of the stack. The caller removes it from the player only after this returned successfully. */
    Entry store(ItemStack item, UUID from, String fromName, String actor, String reason) {
        if (item == null || item.getType().isAir() || item.getAmount() <= 0) throw new IllegalArgumentException("Kein Item ausgewählt");
        if (entries.values().stream().filter(Entry::held).count() >= 2000) throw new IllegalStateException("Die Ablage ist voll (2000 Einträge).");
        Entry entry = new Entry(UUID.randomUUID().toString(), System.currentTimeMillis(), item.getType().getKey().getKey(), label(item), item.getAmount(),
            Base64.getEncoder().encodeToString(item.serializeAsBytes()), from == null ? "" : from.toString(), PowerBot.clean(fromName, 64),
            PowerBot.clean(reason, 300), PowerBot.clean(actor, 64), "held", 0, "", "", "");
        var next = new LinkedHashMap<>(entries);
        next.put(entry.id, entry);
        persist(next);
        return entry;
    }

    private Entry held(String id) {
        Entry entry = entries.get(id);
        if (entry == null) throw new AppApiManager.ApiError(404, "Eintrag nicht gefunden");
        if (!entry.held()) throw new AppApiManager.ApiError(409, "Dieses Item ist nicht mehr in der Ablage.");
        return entry;
    }

    /** Gives the item to an online player. Whatever does not fit stays in the locker. Returns the amount handed out. */
    int give(String id, Player target, String actor) {
        Entry entry = held(id);
        ItemStack item = ItemStack.deserializeBytes(Base64.getDecoder().decode(entry.data));
        var leftover = target.getInventory().addItem(item.clone());
        int remaining = leftover.values().stream().mapToInt(ItemStack::getAmount).sum();
        int moved = item.getAmount() - remaining;
        if (moved <= 0) throw new AppApiManager.ApiError(409, target.getName() + " hat keinen freien Platz im Inventar.");
        String rest = "";
        if (remaining > 0) { ItemStack copy = item.clone(); copy.setAmount(remaining); rest = Base64.getEncoder().encodeToString(copy.serializeAsBytes()); }
        boolean owner = target.getUniqueId().toString().equals(entry.fromId);
        var next = new LinkedHashMap<>(entries);
        next.put(id, entry.resolve(owner ? "returned" : "given", PowerBot.clean(actor, 64), target.getName(), remaining, rest));
        persist(next);
        plugin.suiteItemHistory(target.getUniqueId(), "Aus Einbehaltungsablage erhalten (" + PowerBot.clean(actor, 64) + "): " + moved + "× " + entry.name);
        plugin.suiteAudit(actor, "Einbehaltungsablage · ausgegeben", moved + "× " + entry.name + " → " + target.getName());
        return moved;
    }

    void delete(String id, String actor) {
        Entry entry = held(id);
        var next = new LinkedHashMap<>(entries);
        next.put(id, entry.resolve("deleted", PowerBot.clean(actor, 64), "", 0, ""));
        persist(next);
        plugin.suiteAudit(actor, "Einbehaltungsablage · gelöscht", entry.amount + "× " + entry.name + " von " + entry.fromName);
    }

    void note(String id, String note, String actor) {
        Entry entry = entries.get(id);
        if (entry == null) throw new AppApiManager.ApiError(404, "Eintrag nicht gefunden");
        var next = new LinkedHashMap<>(entries);
        next.put(id, entry.noted(PowerBot.clean(note, 500)));
        persist(next);
    }

    Map<String, Object> data(boolean manage) {
        var list = new ArrayList<>(entries.values());
        Collections.reverse(list);
        var held = list.stream().filter(Entry::held).map(entry -> entry.row(false)).toList();
        var history = list.stream().filter(entry -> !entry.held()).sorted(Comparator.comparingLong(Entry::resolvedAt).reversed())
            .limit(100).map(entry -> entry.row(false)).toList();
        return SimplexAdminSuite.appMap("items", held, "history", history, "manage", manage, "count", held.size());
    }

    // ------------------------------------------------------------------ in-game: /sas ablage

    private static final class Holder implements InventoryHolder {
        final List<String> ids = new ArrayList<>();
        Inventory inventory;
        @Override public Inventory getInventory() { return inventory; }
    }

    boolean command(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player) || !plugin.adminSessionUsable(player)
            || !plugin.has(player, PERMISSION) && !plugin.has(player, "simplexadminsuite.admin")) {
            sender.sendMessage("Einbehaltungsablage: Zugriff fehlt.");
            return true;
        }
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        try {
            if (Set.of("einlagern", "store", "put").contains(sub)) {
                ItemStack hand = player.getInventory().getItemInMainHand();
                String reason = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "Von Hand eingelagert";
                store(hand.clone(), player.getUniqueId(), player.getName(), player.getName(), reason);
                player.getInventory().setItemInMainHand(null);
                player.sendMessage("§aItem in der Einbehaltungsablage gesichert.");
                return true;
            }
            open(player);
        } catch (RuntimeException error) { player.sendMessage("§cEinbehaltungsablage: " + error.getMessage()); }
        return true;
    }

    void open(Player admin) {
        Holder holder = new Holder();
        Inventory inventory = Bukkit.createInventory(holder, 54, "Einbehaltungsablage");
        holder.inventory = inventory;
        var held = new ArrayList<>(entries.values().stream().filter(Entry::held).toList());
        Collections.reverse(held);
        for (Entry entry : held.stream().limit(45).toList()) {
            ItemStack item;
            try { item = ItemStack.deserializeBytes(Base64.getDecoder().decode(entry.data)); }
            catch (RuntimeException broken) { item = new ItemStack(Material.BARRIER); }
            var meta = item.getItemMeta();
            if (meta != null) {
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.GRAY + "Von: " + ChatColor.WHITE + entry.fromName);
                if (!entry.reason.isBlank()) lore.add(ChatColor.GRAY + "Grund: " + ChatColor.WHITE + SparkProfile.clip(entry.reason, 60));
                lore.add(ChatColor.GRAY + "Eingelagert von " + entry.by);
                lore.add(ChatColor.YELLOW + "Klick: in dein Inventar nehmen");
                meta.setLore(lore);
                item.setItemMeta(meta);
            }
            holder.ids.add(entry.id);
            inventory.setItem(holder.ids.size() - 1, item);
        }
        ItemStack info = new ItemStack(Material.BOOK);
        var meta = info.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.AQUA + "Einbehaltungsablage");
            meta.setLore(List.of(ChatColor.GRAY + "" + held.size() + " Items in der Ablage",
                ChatColor.GRAY + "/sas ablage einlagern [Grund] – Item in der Hand sichern",
                ChatColor.GRAY + "App & Webpanel: zurückgeben oder weitergeben"));
            info.setItemMeta(meta);
        }
        inventory.setItem(49, info);
        admin.openInventory(inventory);
    }

    @EventHandler public void click(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player admin) || event.getClickedInventory() != event.getInventory()) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= holder.ids.size()) return;
        if (!plugin.adminSessionUsable(admin) || !plugin.has(admin, PERMISSION) && !plugin.has(admin, "simplexadminsuite.admin")) { admin.closeInventory(); return; }
        try {
            int moved = give(holder.ids.get(slot), admin, admin.getName());
            admin.sendMessage("§a" + moved + " Item(s) aus der Ablage genommen.");
        } catch (RuntimeException error) { admin.sendMessage("§c" + error.getMessage()); }
        Bukkit.getScheduler().runTask(plugin, () -> open(admin));
    }

    @EventHandler public void drag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Holder) event.setCancelled(true);
    }
}
