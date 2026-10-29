package eu.simplexsmp.adminsuite;

import java.util.*;

/**
 * Display allowlist, never an authentication or cheat verdict. Brands are self-reported and often
 * carry versions or loaders, e.g. "lunarclient:v2.23.11-2644,fabric"; each part is matched separately.
 */
final class ClientBrands {
    private static final Map<String,String> KNOWN = Map.ofEntries(
        Map.entry("vanilla", "Vanilla"), Map.entry("fabric", "Fabric"),
        Map.entry("forge", "Forge"), Map.entry("fml", "Forge"),
        Map.entry("neoforge", "NeoForge"), Map.entry("quilt", "Quilt"),
        Map.entry("lunarclient", "Lunar Client"), Map.entry("lunar", "Lunar Client"),
        Map.entry("badlion", "Badlion Client"), Map.entry("badlionclient", "Badlion Client"),
        Map.entry("labymod", "LabyMod"), Map.entry("feather", "Feather Client"), Map.entry("featherclient", "Feather Client"), Map.entry("featherfabric", "Feather Client"), Map.entry("featherforge", "Feather Client"),
        Map.entry("noriskclient", "NoRisk Client"), Map.entry("norisk", "NoRisk Client"),
        Map.entry("optifine", "OptiFine"), Map.entry("liteloader", "LiteLoader"),
        Map.entry("rift", "Rift"), Map.entry("salwyrr", "Salwyrr"),
        Map.entry("essential", "Essential"), Map.entry("cheatbreaker", "CheatBreaker"),
        Map.entry("pvplounge", "PvPLounge"), Map.entry("vivecraft", "Vivecraft"), Map.entry("modrinthapp", "Modrinth App"),
        Map.entry("prismlauncher", "Prism Launcher"), Map.entry("multimc", "MultiMC"), Map.entry("tlauncher", "TLauncher"));
    /** Loaders are reported after the actual client ("lunarclient:…,fabric"); the client name wins. */
    private static final Set<String> LOADERS = Set.of("Vanilla", "Fabric", "Forge", "NeoForge", "Quilt");

    static String part(String token) {
        String value = token.strip().toLowerCase(Locale.ROOT);
        // Drop versions/builds: "lunarclient:v2.23.11-2644", "labymod 4", "optifine_hd_u_j5", "feather/1.2".
        value = value.replaceAll("[:/@].*$", "").replaceAll("[\\s_-]+v?\\d.*$", "").replaceAll("[\\s_.-]+", "");
        return KNOWN.getOrDefault(value, "");
    }
    static String known(String value) {
        if(value == null || value.isBlank() || value.length() > 128 || value.chars().anyMatch(c -> c < 32 || c == 127 || c == 167)) return "";
        String exact = KNOWN.getOrDefault(value.strip().toLowerCase(Locale.ROOT), "");
        if(!exact.isEmpty()) return exact;
        List<String> names = new ArrayList<>();
        // Every part must be recognised; "fabric,Robins PC" stays unusual.
        for(String token : value.split("[,;|+]")) { if(token.isBlank()) continue; String name = part(token); if(name.isEmpty()) return ""; if(!names.contains(name)) names.add(name); }
        if(names.isEmpty()) return "";
        names.sort(Comparator.comparing(LOADERS::contains));
        return String.join(" · ", names);
    }
    static String label(String raw, boolean german) {
        String name = known(raw);
        return "Java • " + (name.isEmpty() ? (german ? "Unbekannter Client" : "Unknown client") : name);
    }
    static String evidence(String raw) {
        if(raw == null) return "";
        return raw.replaceAll("[\\p{Cc}\\p{Cf}§]", "").substring(0, Math.min(128, raw.replaceAll("[\\p{Cc}\\p{Cf}§]", "").length()));
    }
}
