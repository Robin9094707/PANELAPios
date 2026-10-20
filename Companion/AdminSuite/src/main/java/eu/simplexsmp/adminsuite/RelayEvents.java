package eu.simplexsmp.adminsuite;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;

/** Public-only additions. All Bukkit state is read on the server thread. */
final class RelayEvents implements Listener {
    private final SimplexAdminSuite plugin;
    private final Map<UUID, Long> advancementAt = new HashMap<>(), worldAt = new HashMap<>();
    RelayEvents(SimplexAdminSuite plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.MONITOR)
    public void advancement(PlayerAdvancementDoneEvent event) {
        Player player = event.getPlayer();
        DiscordBotManager bot = plugin.discordRelay();
        if (bot == null || plugin.discordAdminHidden(player)
                || !plugin.getConfig().getBoolean("discord-bot.relay.advancements", true)) return;
        Object advancement = event.getAdvancement();
        String key = String.valueOf(SimplexAdminSuite.relayReflect(advancement, "getKey"));
        if (key.contains(":recipes/") || key.endsWith("/root")) return;
        Object display = SimplexAdminSuite.relayReflect(advancement, "getDisplay");
        if (display == null) return;
        Object announces = SimplexAdminSuite.relayReflect(display, "doesAnnounceToChat");
        if (announces == null) announces = SimplexAdminSuite.relayReflect(display, "shouldAnnounceChat");
        if (!Boolean.TRUE.equals(announces)) return;
        boolean all = "all".equalsIgnoreCase(plugin.getConfig().getString("discord-bot.relay.advancement-mode", "selected"));
        if (!all && !plugin.getConfig().getStringList("discord-bot.relay.advancement-keys").contains(key)) return;
        if (!cooldown(advancementAt, player.getUniqueId(), plugin.getConfig().getInt("discord-bot.relay.advancement-cooldown-seconds", 10))) return;
        bot.relayAdvancement(player.getName(), title(key));
    }

    static String title(String key) {
        return switch (key) {
            case "minecraft:end/kill_dragon" -> "Free the End";
            case "minecraft:nether/summon_wither" -> "Withering Heights";
            case "minecraft:nether/create_beacon" -> "Bring Home the Beacon";
            case "minecraft:adventure/adventuring_time" -> "Adventuring Time";
            case "minecraft:husbandry/bred_all_animals" -> "Two by Two";
            case "minecraft:adventure/kill_all_mobs" -> "Monsters Hunted";
            default -> key.substring(Math.max(key.lastIndexOf('/'), key.lastIndexOf(':')) + 1).replace('_', ' ');
        };
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void world(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        DiscordBotManager bot = plugin.discordRelay();
        if (bot == null || plugin.discordAdminHidden(player)
                || !plugin.getConfig().getBoolean("discord-bot.relay.world-changes", false)) return;
        if (event.getFrom().getEnvironment() == player.getWorld().getEnvironment()) return;
        if (!cooldown(worldAt, player.getUniqueId(), plugin.getConfig().getInt("discord-bot.relay.world-change-cooldown-seconds", 60))) return;
        String dimension = switch (player.getWorld().getEnvironment().name()) {
            case "NORMAL" -> "the Overworld";
            case "NETHER" -> "the Nether";
            case "THE_END" -> "the End";
            default -> "another dimension";
        };
        bot.relayWorldChange(player.getName(), dimension);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) {
        advancementAt.remove(event.getPlayer().getUniqueId());
        worldAt.remove(event.getPlayer().getUniqueId());
        if (plugin.discordRelay() != null) plugin.discordRelay().forgetPlayer(event.getPlayer().getName());
    }

    private boolean cooldown(Map<UUID, Long> map, UUID uuid, int seconds) {
        long now = System.currentTimeMillis();
        if (now - map.getOrDefault(uuid, 0L) < Math.max(1, Math.min(3600, seconds)) * 1000L) return false;
        map.put(uuid, now); return true;
    }
}
