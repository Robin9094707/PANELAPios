package eu.simplexsmp.adminsuite;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class VpnSettings {
    private static final String ROOT = "vpn-protection.";
    private final FileConfiguration config;
    private final boolean enabled;
    private final String targetPlatform;
    private final boolean blockVpn;
    private final boolean blockHosting;
    private final boolean blockOnSingleHighConfidence;
    private final int minimumSignals;
    private final int minimumRisk;
    private final boolean failOpen;
    private final int timeoutMillis;
    private final int cacheMinutes;
    private final int reconnectCooldownSeconds;
    private final boolean logAllJoins;
    private final int maxLogEntries;
    private final List<String> bedrockPrefixes;

    VpnSettings(FileConfiguration config) {
        this.config = config;
        targetPlatform = platform(config.getString(ROOT + "target-platform", "both"));
        enabled = config.getBoolean(ROOT + "enabled", true);
        blockVpn = config.getBoolean(ROOT + "settings.block-vpn", true);
        blockHosting = config.getBoolean(ROOT + "settings.block-hosting-datacenter", true);
        blockOnSingleHighConfidence = config.getBoolean(ROOT + "settings.block-on-single-high-confidence", true);
        minimumSignals = Math.max(1, Math.min(4, config.getInt(ROOT + "settings.min-positive-signals", 1)));
        minimumRisk = Math.max(1, Math.min(100, config.getInt(ROOT + "settings.min-risk-score", 70)));
        failOpen = config.getBoolean(ROOT + "settings.fail-open-if-all-apis-fail", true);
        timeoutMillis = Math.max(500, Math.min(8000, config.getInt(ROOT + "settings.api-timeout-ms", 2500)));
        cacheMinutes = Math.max(1, Math.min(10080, config.getInt(ROOT + "settings.cache-minutes", 720)));
        reconnectCooldownSeconds = Math.max(0, Math.min(300, config.getInt(ROOT + "settings.reconnect-cooldown-seconds", 10)));
        logAllJoins = config.getBoolean(ROOT + "logging.log-all-joins", false);
        maxLogEntries = Math.max(100, Math.min(100000, config.getInt(ROOT + "logging.max-entries", 5000)));
        List<String> configuredPrefixes = new ArrayList<>(config.getStringList(ROOT + "bedrock.name-prefixes"));
        if (configuredPrefixes.isEmpty()) configuredPrefixes.addAll(List.of(".", "*"));
        bedrockPrefixes = Collections.unmodifiableList(configuredPrefixes);
    }

    String targetPlatform() { return targetPlatform; }
    static String platform(String value) { return SetHolder.VALUES.contains(value) ? value : "both"; }
    private static final class SetHolder { static final java.util.Set<String> VALUES=java.util.Set.of("both","java","bedrock"); }
    boolean checksPlatform(boolean bedrock) { return targetPlatform().equals("both") || targetPlatform().equals(bedrock ? "bedrock" : "java"); }
    String platformLabel() { return switch(targetPlatform()) { case "java" -> "Nur Java"; case "bedrock" -> "Nur Bedrock"; default -> "Java und Bedrock"; }; }
    boolean enabled() { return enabled; }
    boolean blockVpn() { return blockVpn; }
    boolean blockHosting() { return blockHosting; }
    boolean blockOnSingleHighConfidence() { return blockOnSingleHighConfidence; }
    int minimumSignals() { return minimumSignals; }
    int minimumRisk() { return minimumRisk; }
    boolean failOpen() { return failOpen; }
    int timeoutMillis() { return timeoutMillis; }
    int cacheMinutes() { return cacheMinutes; }
    int reconnectCooldownSeconds() { return reconnectCooldownSeconds; }
    boolean logAllJoins() { return logAllJoins; }
    int maxLogEntries() { return maxLogEntries; }
    List<String> bedrockPrefixes() { return bedrockPrefixes; }

    boolean providerEnabled(String id) { return config.getBoolean(ROOT + "providers." + id + ".enabled", true); }
    String providerUrl(String id, String fallback) { return config.getString(ROOT + "providers." + id + ".url", fallback); }
    String providerKey(String id) { return config.getString(ROOT + "providers." + id + ".api-key", ""); }

    String kickMessage(boolean bedrock, boolean cooldown) {
        String key = ROOT + "messages." + (bedrock ? "bedrock-" : "java-") + (cooldown ? "cooldown" : "vpn");
        String fallback;
        if (cooldown) {
            fallback = bedrock
                    ? "Simplex SMP - Please wait a moment before joining again. You were just blocked for VPN/proxy usage."
                    : "§cPlease wait a moment before joining again. You were just blocked for VPN/proxy usage.";
        } else {
            fallback = bedrock
                    ? "Simplex SMP - VPN detected\n\nVPNs, proxies, Tor or hosting networks are not allowed here. Please disable your VPN and try again. If this is a mistake, contact Discord support."
                    : "§8§m--------------------------------\n§b§lSimplexSMP §8» §cVPN detected\n\n§fVPNs, proxies, Tor or hosting networks are not allowed on SimplexSMP.\n§7Please disable your VPN and try again.\n\n§eIf this is a mistake, contact Discord support.\n§8§m--------------------------------";
        }
        return config.getString(key, fallback).replace("\\n", "\n");
    }
}
