package eu.simplexsmp.adminsuite;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class ReconnectGuard {
    private final SimplexAdminSuite plugin;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> blockedUntil = new ConcurrentHashMap<>();
    private final File stateFile;

    ReconnectGuard(SimplexAdminSuite plugin) {
        this.plugin = plugin;
        this.stateFile = new File(plugin.getDataFolder(), "reconnect-protection.yml");
        load();
    }

    String denial(Player player) {
        if(player!=null&&plugin.emergency(player.getUniqueId()))return null;
        if (player == null || !plugin.getConfig().getBoolean("reconnect-protection.enabled", true)) return null;
        long now = System.currentTimeMillis();
        Long until = blockedUntil.get(player.getUniqueId());
        if (until == null || until <= now) {
            blockedUntil.remove(player.getUniqueId());
            return null;
        }
        long seconds = Math.max(1L, (until - now + 999L) / 1000L);
        return plugin.publicMessage("reconnect-protection.message",
                "Simplex SMP\nYou can reconnect in {seconds} seconds.", "seconds", Long.toString(seconds));
    }

    void joined(Player player) {
        if (player == null || !plugin.getConfig().getBoolean("reconnect-protection.enabled", true)) return;
        long now = System.currentTimeMillis();
        sessions.put(player.getUniqueId(), new Session(now));
        blockedUntil.put(player.getUniqueId(), now + cooldownMillis());
        save();
    }

    void quit(Player player, boolean rejectedJoin) {
        if (player == null || !plugin.getConfig().getBoolean("reconnect-protection.enabled", true)) return;
        UUID uuid = player.getUniqueId();
        Session session = sessions.remove(uuid);
        long now = System.currentTimeMillis();
        if (rejectedJoin) return;
        long minimumPlay = Math.max(1L, plugin.getConfig().getLong("reconnect-protection.minimum-play-seconds", 15L)) * 1000L;
        if (session != null && now - session.joinedAt >= minimumPlay) blockedUntil.remove(uuid);
        else {
            long originalWindow = session == null ? now + cooldownMillis() : session.joinedAt + cooldownMillis();
            blockedUntil.put(uuid, Math.max(blockedUntil.getOrDefault(uuid, 0L), originalWindow));
        }
        save();
    }

    void cleanup() {
        long now = System.currentTimeMillis();
        blockedUntil.entrySet().removeIf(entry -> entry.getValue() <= now);
    }

    void reload() {
        sessions.clear();
        load();
    }

    void stop() {
        save();
        sessions.clear();
    }

    private long cooldownMillis() {
        return Math.max(1L, plugin.getConfig().getLong("reconnect-protection.cooldown-seconds", 15L)) * 1000L;
    }

    private synchronized void load() {
        blockedUntil.clear();
        if (!stateFile.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(stateFile);
        long now = System.currentTimeMillis();
        for (String key : yaml.getStringList("entries")) {
            String[] split = key.split("\\|", 2);
            if (split.length != 2) continue;
            try {
                UUID uuid = UUID.fromString(split[0]);
                long until = Long.parseLong(split[1]);
                if (until > now) blockedUntil.put(uuid, until);
            } catch (Exception ignored) { }
        }
    }

    private synchronized void save() {
        try {
            cleanup();
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("entries", blockedUntil.entrySet().stream()
                    .map(entry -> entry.getKey() + "|" + entry.getValue())
                    .toList());
            yaml.save(stateFile);
        } catch (Exception error) {
            plugin.getLogger().warning("Reconnect-Schutz konnte nicht gespeichert werden: " + error.getMessage());
        }
    }

    private record Session(long joinedAt) { }
}
