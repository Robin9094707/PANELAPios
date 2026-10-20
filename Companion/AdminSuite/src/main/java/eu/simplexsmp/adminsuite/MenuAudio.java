package eu.simplexsmp.adminsuite;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.*;

/** Shared audio for all Suite-owned inventories. Native Bedrock navigation remains client-side. */
final class MenuAudio implements Listener {
  private final SimplexAdminSuite plugin;

  private record Hint(String kind, long at) {}

  private final Map<UUID, Long> outcomes = new ConcurrentHashMap<>();
  private final Map<String, Hint> hints = new ConcurrentHashMap<>();

  MenuAudio(SimplexAdminSuite plugin) {
    this.plugin = plugin;
  }

  static boolean discard(String text) {
    return MenuStyle.plain(text)
        .toLowerCase(Locale.ROOT)
        .matches(".*\\b(abbrechen|verwerfen|cancel|discard)\\b.*");
  }

  static boolean deletion(String text) {
    return MenuStyle.plain(text)
        .toLowerCase(Locale.ROOT)
        .matches(".*(gelöscht|entfernt|deleted|removed|zurückgesetzt|reset complete).*");
  }

  void hint(String actor, String message) {
    if (actor != null && deletion(message))
      hints.put(actor, new Hint("delete", System.currentTimeMillis()));
  }

  void outcome(Player player, boolean success) {
    Hint hint = hints.remove(player.getName());
    String kind =
        !success
            ? "error"
            : hint != null && System.currentTimeMillis() - hint.at < 1000 ? hint.kind : "success";
    outcomes.put(player.getUniqueId(), System.nanoTime());
    play(player, kind);
  }

  void discarded(Player player) {
    if (player == null) return;
    outcomes.put(player.getUniqueId(), System.nanoTime());
    play(player, "discard");
  }

  @EventHandler(priority = EventPriority.LOWEST)
  public void click(InventoryClickEvent event) {
    if (!(event.getWhoClicked() instanceof Player player) || plugin.isBedrockClient(player)) return;
    Inventory top = event.getView().getTopInventory();
    Object holder = LiveMenus.holder(top);
    if (holder == null || !holder.getClass().getName().startsWith("eu.simplexsmp.adminsuite."))
      return;
    if (event.getRawSlot() < 0 || event.getRawSlot() >= top.getSize()) return;
    ItemStack item = event.getCurrentItem();
    if (item == null || item.getType().isAir() || !item.hasItemMeta()) return;
    String text = item.getItemMeta().hasDisplayName() ? item.getItemMeta().getDisplayName() : "";
    if (MenuStyle.plain(text).isBlank()) return;
    long clicked = System.nanoTime();
    Bukkit.getScheduler()
        .runTaskLater(
            plugin,
            () -> {
              if (Bukkit.getPlayer(player.getUniqueId()) != player
                  || outcomes.getOrDefault(player.getUniqueId(), 0L) >= clicked) return;
              if (discard(text)) discarded(player);
              else play(player, "navigation");
            },
            2L);
  }

  @EventHandler
  public void quit(PlayerQuitEvent e) {
    outcomes.remove(e.getPlayer().getUniqueId());
    hints.remove(e.getPlayer().getName());
  }

  private void play(Player player, String kind) {
    if (!player.isOnline() || !plugin.getConfig().getBoolean("admin-update.feedback.enabled", true))
      return;
    String fallback =
        switch (kind) {
          case "error" -> "block.note_block.bass";
          case "delete" -> "block.fire.extinguish";
          case "discard" -> "block.note_block.hat";
          case "navigation" -> "ui.button.click";
          default -> "entity.experience_orb.pickup";
        };
    String key = kind.equals("navigation") ? "navigation-sound" : kind + "-sound";
    String sound = plugin.getConfig().getString("admin-update.feedback." + key, fallback);
    NamespacedKey id = sound == null ? null : NamespacedKey.fromString(sound);
    if (id == null || Registry.SOUNDS.get(id) == null) sound = fallback;
    double configured = plugin.getConfig().getDouble("admin-update.feedback.volume-percent", 65);
    float volume =
        Double.isFinite(configured) ? (float) Math.max(0, Math.min(1, configured / 100)) : 0.65f;
    if (kind.equals("navigation")) volume *= 0.55f;
    float pitch =
        switch (kind) {
          case "error" -> 0.7f;
          case "delete" -> 1.2f;
          case "discard" -> 0.8f;
          case "navigation" -> 1.05f;
          default -> 1.25f;
        };
    try {
      player.playSound(player.getLocation(), sound, SoundCategory.MASTER, volume, pitch);
    } catch (IllegalArgumentException error) {
      player.playSound(player.getLocation(), fallback, SoundCategory.MASTER, volume, pitch);
    }
  }
}
