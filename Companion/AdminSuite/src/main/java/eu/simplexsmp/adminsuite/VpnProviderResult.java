package eu.simplexsmp.adminsuite;

final class VpnProviderResult {
    private final String provider;
    private final boolean success;
    private final String countryCode;
    private final boolean vpn;
    private final boolean proxy;
    private final boolean tor;
    private final boolean hosting;
    private final int score;
    private final boolean highConfidence;
    private final String note;

    VpnProviderResult(String provider, boolean success, String countryCode, boolean vpn, boolean proxy,
                      boolean tor, boolean hosting, int score, boolean highConfidence, String note) {
        this.provider = provider == null ? "unknown" : provider;
        this.success = success;
        this.countryCode = normalizeCountry(countryCode);
        this.vpn = vpn;
        this.proxy = proxy;
        this.tor = tor;
        this.hosting = hosting;
        this.score = Math.max(0, Math.min(100, score));
        this.highConfidence = highConfidence;
        this.note = note == null ? "" : note;
    }

    static VpnProviderResult detected(String provider, String countryCode, boolean vpn, boolean proxy,
                                      boolean tor, boolean hosting, String note) {
        int score = vpn || proxy || tor ? 100 : hosting ? 70 : 0;
        return new VpnProviderResult(provider, true, countryCode, vpn, proxy, tor, hosting, score,
                vpn || proxy || tor, note);
    }

    static VpnProviderResult failure(String provider, String note) {
        return new VpnProviderResult(provider, false, "", false, false, false, false, 0, false, note);
    }

    String provider() { return provider; }
    boolean success() { return success; }
    String countryCode() { return countryCode; }
    boolean suspicious() { return vpn || proxy || tor || hosting; }
    boolean highConfidence() { return highConfidence; }
    int score() { return score; }

    String summary() {
        if (!success) return provider + "=fehlgeschlagen" + (note.isBlank() ? "" : "(" + note + ")");
        StringBuilder flags = new StringBuilder();
        if (vpn) flags.append("vpn,");
        if (proxy) flags.append("proxy,");
        if (tor) flags.append("tor,");
        if (hosting) flags.append("hosting,");
        if (flags.length() > 0) flags.setLength(flags.length() - 1); else flags.append("sauber");
        return provider + "=" + flags + "/Risiko=" + score + (countryCode.isBlank() ? "" : "/" + countryCode);
    }

    private static String normalizeCountry(String value) {
        if (value == null) return "";
        String clean = value.trim();
        return clean.length() >= 2 ? clean.substring(0, 2).toUpperCase(java.util.Locale.ROOT)
                : clean.toUpperCase(java.util.Locale.ROOT);
    }
}
