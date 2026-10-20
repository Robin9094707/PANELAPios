package eu.simplexsmp.adminsuite;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class VpnCheckResult {
    private final String ip;
    private final String countryCode;
    private final int signals;
    private final int riskScore;
    private final boolean highConfidence;
    private final boolean positive;
    private final boolean localAddress;
    private final boolean allProvidersFailed;
    private final String reason;
    private final List<VpnProviderResult> providers;

    VpnCheckResult(String ip, String countryCode, int signals, int riskScore, boolean highConfidence,
                   boolean positive, boolean localAddress, boolean allProvidersFailed, String reason,
                   List<VpnProviderResult> providers) {
        this.ip = ip == null ? "unknown" : ip;
        this.countryCode = countryCode == null ? "" : countryCode;
        this.signals = Math.max(0, signals);
        this.riskScore = Math.max(0, Math.min(100, riskScore));
        this.highConfidence = highConfidence;
        this.positive = positive;
        this.localAddress = localAddress;
        this.allProvidersFailed = allProvidersFailed;
        this.reason = reason == null ? "" : reason;
        this.providers = Collections.unmodifiableList(new ArrayList<>(providers == null ? List.of() : providers));
    }

    static VpnCheckResult local(String ip) {
        return new VpnCheckResult(ip, "", 0, 0, false, false, true, false,
                "Private oder lokale Adresse wurde vor externen Prüfungen zugelassen.", List.of());
    }

    String ip() { return ip; }
    String countryCode() { return countryCode; }
    int signals() { return signals; }
    int riskScore() { return riskScore; }
    boolean highConfidence() { return highConfidence; }
    boolean positive() { return positive; }
    boolean localAddress() { return localAddress; }
    boolean allProvidersFailed() { return allProvidersFailed; }
    String reason() { return reason; }
    List<VpnProviderResult> providers() { return providers; }

    String providerSummary() {
        if (providers.isEmpty()) return "none";
        StringBuilder result = new StringBuilder();
        for (VpnProviderResult provider : providers) {
            if (result.length() > 0) result.append(", ");
            result.append(provider.summary());
        }
        return result.toString();
    }
}
