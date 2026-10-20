package eu.simplexsmp.adminsuite;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Persistent, visual-only nicknames. Authentication and moderation always keep the real UUID/name. */
final class NicknameManager implements Listener {
    private final SimplexAdminSuite plugin;
    private final File file;
    private final Map<UUID, String> nicknames = new ConcurrentHashMap<>();

    NicknameManager(SimplexAdminSuite plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "nicknames.yml");
        load();
    }

    void reload() {
        load();
        for (Player player : Bukkit.getOnlinePlayers()) applyDisplayName(player);
    }

    void stop() { save(); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            applyDisplayName(event.getPlayer());
            plugin.refreshVisualIdentity(event.getPlayer());
        }, 2L);
    }

    boolean handleCommand(CommandSender sender, String[] args) {
        if (!plugin.has(sender, "simplexadminsuite.nickname")) {
            sender.sendMessage(plugin.prefix() + "Dafür fehlt dir simplexadminsuite.nickname.");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(plugin.prefix() + "Verwendung: /nickname <Spieler> <Nickname|reset>");
            return true;
        }
        UUID target = plugin.discordAdminResolvePlayer(args[0]);
        if (target == null) {
            plugin.menuNotice(sender,plugin.prefix() + "Spielerprofil nicht gefunden.");
            return true;
        }
        String requested = join(args, 1);
        Result result = set(sender.getName(), target, requested);
        sender.sendMessage(plugin.prefix() + result.message);
        return true;
    }

    Result set(String actor, UUID target, String requested) {
        if (target == null) return Result.error("Spielerprofil nicht gefunden.");
        String realName = plugin.discordAdminPlayerName(target);
        String raw = requested == null ? "" : requested.trim();
        if (raw.equalsIgnoreCase("reset") || raw.equalsIgnoreCase("remove") || raw.equalsIgnoreCase("off") || raw.equals("-")) {
            String removed = nicknames.remove(target);
            save();
            Player online = Bukkit.getPlayer(target);
            if (online != null) {
                applyDisplayName(online);
                plugin.refreshVisualIdentity(online);
                online.sendMessage(plugin.prefix()+plugin.playerText(online,"Dein visueller Nickname wurde vom Team zurückgesetzt.","Your display nickname was reset by staff."));
            }
            plugin.nicknameAudit(actor, target, "Nickname zurückgesetzt", removed == null ? "war nicht gesetzt" : removed);
            return Result.ok("Der visuelle Nickname von " + realName + " wurde zurückgesetzt.");
        }

        int max = Math.max(3, Math.min(48, plugin.getConfig().getInt("nicknames.max-length", 24)));
        String clean = plugin.sanitizeText(raw, max).replace('\n', ' ').replace('\r', ' ').trim();
        if (!plugin.getConfig().getBoolean("nicknames.allow-colors", true)) clean = ChatColor.stripColor(plugin.color(clean));
        String plain = ChatColor.stripColor(plugin.color(clean));
        if (plain == null || plain.trim().length() < 2) return Result.error("Der Nickname muss mindestens zwei sichtbare Zeichen haben.");
        if (plain.length() > max) return Result.error("Der Nickname ist zu lang. Maximal " + max + " sichtbare Zeichen.");
        nicknames.put(target, clean);
        save();
        Player online = Bukkit.getPlayer(target);
        if (online != null) {
            applyDisplayName(online);
            plugin.refreshVisualIdentity(online);
            online.sendMessage(plugin.prefix()+plugin.color(plugin.playerText(online,"Dein visueller Nickname lautet jetzt ","Your display nickname is now ")+clean+"&7."));
        }
        plugin.nicknameAudit(actor, target, "Nickname gesetzt", clean);
        return Result.ok(plugin.color("Der visuelle Nickname von " + realName + " lautet jetzt " + clean + "&7."));
    }

    String visualName(Player player) {
        if (player == null) return "Unbekannt";
        return visualName(player.getUniqueId(), player.getName());
    }

    String visualName(UUID uuid, String realName) {
        String nickname = uuid == null ? null : nicknames.get(uuid);
        return nickname == null || nickname.isBlank() ? (realName == null ? "Unbekannt" : realName) : plugin.color(nickname);
    }

    String rawNickname(UUID uuid) {
        String value = uuid == null ? null : nicknames.get(uuid);
        return value == null ? "" : value;
    }

    List<String> tabComplete(String[] args) {
        if (args.length == 1) return filter(plugin.expansionProfiles().stream().map(SimplexAdminSuite.PlayerProfile::displayName).toList(), args[0]);
        if (args.length == 2) return filter(List.of("reset"), args[1]);
        return List.of();
    }

    private void applyDisplayName(Player player) {
        if (player == null) return;
        if(plugin.qualityOfLife!=null){plugin.qualityOfLife.names.apply(player);return;}
        // Chat display names are intentionally not modified, including during startup.

    }

    private synchronized void load() {
        nicknames.clear();
        if (!file.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String key : yaml.getKeys(false)) {
            try {
                String value = yaml.getString(key, "").trim();
                if (!value.isBlank()) nicknames.put(UUID.fromString(key), value);
            } catch (Exception ignored) { }
        }
    }

    private synchronized void save() {
        try {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            YamlConfiguration yaml = new YamlConfiguration();
            for (Map.Entry<UUID, String> entry : nicknames.entrySet()) yaml.set(entry.getKey().toString(), entry.getValue());
            File temporary = new File(file.getParentFile(), file.getName() + ".tmp");
            yaml.save(temporary);
            try { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (Exception unavailable) { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING); }
        } catch (Exception error) {
            plugin.getLogger().warning("Nicknames konnten nicht gespeichert werden: " + error.getMessage());
        }
    }

    private static String join(String[] values, int start) {
        StringBuilder out = new StringBuilder();
        for (int i = start; i < values.length; i++) {
            if (out.length() > 0) out.append(' ');
            out.append(values[i]);
        }
        return out.toString();
    }

    private static List<String> filter(List<String> values, String prefix) {
        String query = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String value : values) if (value.toLowerCase(Locale.ROOT).startsWith(query)) out.add(value);
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out.size() > 80 ? out.subList(0, 80) : out;
    }

    static final class Result {
        final boolean success;
        final String message;
        private Result(boolean success, String message) { this.success = success; this.message = message; }
        static Result ok(String message) { return new Result(true, message); }
        static Result error(String message) { return new Result(false, message); }
    }
}
