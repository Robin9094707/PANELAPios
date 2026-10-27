package eu.simplexsmp.adminsuite;

import java.util.*;

/** Display allowlist, never an authentication or cheat verdict. Exact matches only. */
final class ClientBrands {
    private static final Map<String,String> KNOWN = Map.ofEntries(
        Map.entry("vanilla", "Vanilla"), Map.entry("fabric", "Fabric"),
        Map.entry("forge", "Forge"), Map.entry("fml,forge", "Forge"),
        Map.entry("neoforge", "NeoForge"), Map.entry("quilt", "Quilt"),
        Map.entry("lunarclient", "Lunar Client"), Map.entry("lunar", "Lunar Client"),
        Map.entry("badlion", "Badlion Client"), Map.entry("badlion client", "Badlion Client"),
        Map.entry("labymod", "LabyMod"), Map.entry("feather", "Feather Client"),
        Map.entry("noriskclient", "NoRisk Client"), Map.entry("norisk", "NoRisk Client"),
        Map.entry("optifine", "OptiFine"), Map.entry("liteloader", "LiteLoader"),
        Map.entry("rift", "Rift"), Map.entry("salwyrr", "Salwyrr"),
        Map.entry("essential", "Essential"), Map.entry("cheatbreaker", "CheatBreaker"));
    static String known(String value) {
        if(value == null || value.length() > 128 || value.chars().anyMatch(c -> c < 32 || c == 127 || c == 167)) return "";
        return KNOWN.getOrDefault(value.strip().toLowerCase(Locale.ROOT), "");
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
