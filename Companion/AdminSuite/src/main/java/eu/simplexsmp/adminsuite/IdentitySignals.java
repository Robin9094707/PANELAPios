package eu.simplexsmp.adminsuite;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * 10.24.0: experimental identity signals for the alt analysis – Java and Bedrock (via Geyser/Floodgate).
 *
 * <ul>
 *   <li><b>Skin fingerprint</b>: Mojang stores skins by the SHA-256 of the image, so the texture id is equal only when
 *   two accounts uploaded the identical picture. Geyser uploads Bedrock skins the same way, so this works for both
 *   editions. Only rare skins count (shared by at most {@code max-shared-accounts}); default and popular skins never do.</li>
 *   <li><b>Account switch</b>: account B joins from the same IP within a short window after account A left – repeatedly.
 *   Counted per pair; a single switch (siblings, friends) never counts.</li>
 *   <li><b>Network prefix</b> (derived in the engine from the existing IP history, nothing new is stored): same IPv6 /64
 *   (one household line) or IPv4 /24 (only as a supplement to another signal).</li>
 *   <li><b>Bedrock installation id</b> (ClientRandomId, already collected since 9.x) – now also as a rare-gated hint.</li>
 * </ul>
 *
 * <p>All of them are hints only: they never make a relation "safe for cascade", never trigger automatic punishment and
 * can be switched off individually under {@code alt-detection.experimental}. Only data the client sends anyway is used –
 * no resource-pack cache tricks or other client exploits.</p>
 */
final class IdentitySignals implements Listener {
    static final String FEATURE = "identity-signals-v1";
    private static final String ROOT = "alt-detection.experimental.";

    private final SimplexAdminSuite plugin;
    private final File file;
    private final Map<UUID, Map<String, Long>> skins = new ConcurrentHashMap<>();
    private final Map<String, long[]> switches = new ConcurrentHashMap<>(); // "a|b" (sorted) -> {count, last}
    private final Map<String, Departure> departures = new ConcurrentHashMap<>();
    private volatile boolean dirty;
    private int saveTask = -1;

    private record Departure(UUID uuid, long at) { }

    IdentitySignals(SimplexAdminSuite plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "identity-signals.yml");
        load();
    }

    void start() { saveTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::saveIfDirty, 1200L, 1200L).getTaskId(); }

    void stop() {
        if (saveTask >= 0) Bukkit.getScheduler().cancelTask(saveTask);
        saveIfDirty();
    }

    boolean enabled() { return plugin.getConfig().getBoolean(ROOT + "enabled", true); }
    boolean enabled(String signal) { return enabled() && plugin.getConfig().getBoolean(ROOT + signal, true); }
    int maxShared() { return Math.max(2, plugin.getConfig().getInt(ROOT + "max-shared-accounts", 3)); }
    int switchMinimum() { return Math.max(2, plugin.getConfig().getInt(ROOT + "switch-minimum", 2)); }

    // -------------------------------------------------------------------------------------------- capture

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!enabled()) return;
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String ip = ip(player);
        long now = System.currentTimeMillis();
        if (enabled("account-switch") && !ip.isEmpty()) {
            Departure left = departures.get(ip);
            long window = Math.max(10, plugin.getConfig().getLong(ROOT + "switch-window-seconds", 120)) * 1000L;
            if (left != null && !left.uuid().equals(uuid) && now - left.at() <= window) recordSwitch(left.uuid(), uuid, now);
        }
        if (enabled("skin-fingerprint")) {
            // Java skins are known at join; Geyser uploads Bedrock skins and Floodgate applies them a little later.
            for (long delay : new long[]{40L, 600L}) Bukkit.getScheduler().runTaskLater(plugin, () -> { if (player.isOnline()) captureSkin(player); }, delay);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (!enabled("account-switch")) return;
        String ip = ip(event.getPlayer());
        if (!ip.isEmpty()) departures.put(ip, new Departure(event.getPlayer().getUniqueId(), System.currentTimeMillis()));
        if (departures.size() > 4096) {
            long cutoff = System.currentTimeMillis() - 3_600_000L;
            departures.values().removeIf(d -> d.at() < cutoff);
        }
    }

    private static String ip(Player player) {
        var address = player.getAddress();
        return address == null || address.getAddress() == null ? "" : address.getAddress().getHostAddress();
    }

    void captureSkin(Player player) {
        String texture = skinTexture(player);
        if (texture.isEmpty()) return;
        Map<String, Long> history = skins.computeIfAbsent(player.getUniqueId(), k -> Collections.synchronizedMap(new LinkedHashMap<>()));
        synchronized (history) {
            Long before = history.put(texture, System.currentTimeMillis());
            while (history.size() > 12) history.remove(history.keySet().iterator().next());
            if (before == null || System.currentTimeMillis() - before > 3_600_000L) dirty = true;
        }
    }

    /** Texture id of the current skin (last path segment of the Mojang texture URL), or "". */
    static String skinTexture(Player player) {
        try {
            URL skin = player.getPlayerProfile().getTextures().getSkin();
            return textureId(skin == null ? null : skin.toString());
        } catch (Throwable unavailable) { return ""; }
    }

    static String textureId(String url) {
        if (url == null || url.isBlank()) return "";
        String id = url.substring(url.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        return id.matches("[0-9a-f]{32,80}") ? id : "";
    }

    void recordSwitch(UUID left, UUID joined, long now) {
        String key = pair(left, joined);
        switches.compute(key, (k, v) -> v == null ? new long[]{1, now} : new long[]{now - v[1] < 30_000L ? v[0] : v[0] + 1, now});
        dirty = true;
    }

    static String pair(UUID a, UUID b) {
        String x = a.toString(), y = b.toString();
        return x.compareTo(y) < 0 ? x + "|" + y : y + "|" + x;
    }

    // -------------------------------------------------------------------------------------------- read side

    Map<String, Long> skins(UUID uuid) {
        if (!enabled("skin-fingerprint")) return Map.of();
        Map<String, Long> history = skins.get(uuid);
        if (history == null) return Map.of();
        synchronized (history) { return new HashMap<>(history); }
    }

    /** Switch partners of one account with their counts (only pairs at or above the minimum). */
    Map<String, Integer> switchPartners(UUID uuid) {
        if (!enabled("account-switch")) return Map.of();
        Map<String, Integer> out = new HashMap<>();
        String me = uuid.toString();
        int minimum = switchMinimum();
        switches.forEach((key, value) -> {
            if (value[0] < minimum) return;
            int split = key.indexOf('|');
            String a = key.substring(0, split), b = key.substring(split + 1);
            if (a.equals(me)) out.put(b, (int) value[0]);
            else if (b.equals(me)) out.put(a, (int) value[0]);
        });
        return out;
    }

    /** Small status block for the alt dashboard / diagnostics. */
    Map<String, Object> status() {
        int pairs = 0;
        for (long[] v : switches.values()) if (v[0] >= switchMinimum()) pairs++;
        return SimplexAdminSuite.appMap("enabled", enabled(), "skinFingerprint", enabled("skin-fingerprint"), "accountSwitch", enabled("account-switch"),
            "networkPrefix", enabled("network-prefix"), "clientRandomId", enabled("client-random-id"), "skinProfiles", skins.size(),
            "switchPairs", pairs, "maxSharedAccounts", maxShared());
    }

    /** Removes everything about one player (GDPR deletion / profile reset). */
    void forget(UUID uuid) {
        skins.remove(uuid);
        String me = uuid.toString();
        switches.keySet().removeIf(k -> k.startsWith(me + "|") || k.endsWith("|" + me));
        dirty = true;
    }

    // -------------------------------------------------------------------------------------------- storage

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection skinSection = yaml.getConfigurationSection("skins");
        if (skinSection != null) for (String key : skinSection.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                ConfigurationSection entries = skinSection.getConfigurationSection(key);
                if (entries == null) continue;
                Map<String, Long> history = Collections.synchronizedMap(new LinkedHashMap<>());
                for (String texture : entries.getKeys(false)) history.put(texture, entries.getLong(texture));
                skins.put(uuid, history);
            } catch (IllegalArgumentException ignored) { }
        }
        ConfigurationSection switchSection = yaml.getConfigurationSection("switches");
        if (switchSection != null) for (String key : switchSection.getKeys(false)) {
            String raw = switchSection.getString(key, "");
            String[] parts = raw.split(";");
            try { switches.put(key.replace('_', '|'), new long[]{Long.parseLong(parts[0]), parts.length > 1 ? Long.parseLong(parts[1]) : 0L}); }
            catch (NumberFormatException ignored) { }
        }
    }

    private synchronized void saveIfDirty() {
        if (!dirty) return;
        dirty = false;
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of("Simplex Admin Suite – experimentelle Identitätssignale (10.24.0).",
            "skins: Mojang-Textur-IDs je Spieler (öffentlich, kein Bild). switches: Kontowechsel-Paare (Anzahl;zuletzt).",
            "Abschalten: alt-detection.experimental in config.yml. Datei darf gelöscht werden."));
        skins.forEach((uuid, history) -> { synchronized (history) { history.forEach((texture, at) -> yaml.set("skins." + uuid + "." + texture, at)); } });
        switches.forEach((key, value) -> yaml.set("switches." + key.replace('|', '_'), value[0] + ";" + value[1]));
        try { yaml.save(file); }
        catch (IOException error) { dirty = true; plugin.getLogger().warning("Identitätssignale konnten nicht gespeichert werden: " + error.getMessage()); }
    }
}
