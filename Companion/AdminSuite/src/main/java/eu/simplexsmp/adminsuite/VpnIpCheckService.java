package eu.simplexsmp.adminsuite;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class VpnIpCheckService implements AutoCloseable {
    private final VpnSettings settings;
    private final Logger logger;
    private final HttpClient client;
    private final ExecutorService providerExecutor;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    VpnIpCheckService(VpnSettings settings, Logger logger) {
        this.settings = settings;
        this.logger = logger;
        this.providerExecutor = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "Simplex-VPN-Provider");
            thread.setDaemon(true);
            return thread;
        });
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(settings.timeoutMillis()))
                .build();
    }

    VpnCheckResult check(String ip, boolean forceRefresh) {
        String normalized = ip == null ? "unknown" : ip.trim();
        if (!isIpLiteral(normalized)) return new VpnCheckResult(normalized, "", 0, 0, false, false,
                false, false, "Die Eingabe ist keine gültige IPv4- oder IPv6-Adresse.", List.of());
        if (isLocalAddress(normalized)) return VpnCheckResult.local(normalized);
        long now = System.currentTimeMillis();
        CacheEntry cached = cache.get(normalized);
        if (!forceRefresh && cached != null && cached.expiresAt > now) return cached.result;
        if (cached != null && cached.expiresAt <= now) cache.remove(normalized, cached);

        List<Supplier<VpnProviderResult>> providers = new ArrayList<>();
        if (settings.providerEnabled("proxycheck")) providers.add(() -> queryProxyCheck(normalized));
        if (settings.providerEnabled("ipapi-is")) providers.add(() -> queryIpapiIs(normalized));
        if (settings.providerEnabled("ip-api")) providers.add(() -> queryIpApi(normalized));
        if (settings.providerEnabled("ipwhois")) providers.add(() -> queryIpWhois(normalized));

        List<CompletableFuture<VpnProviderResult>> futures = new ArrayList<>();
        for (Supplier<VpnProviderResult> provider : providers) futures.add(CompletableFuture.supplyAsync(provider, providerExecutor));
        List<VpnProviderResult> results = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(settings.timeoutMillis() + 1000L);
        for (int index = 0; index < futures.size(); index++) {
            CompletableFuture<VpnProviderResult> future = futures.get(index);
            long remaining = Math.max(1L, deadline - System.nanoTime());
            try { results.add(future.get(remaining, TimeUnit.NANOSECONDS)); }
            catch (Exception error) {
                future.cancel(true);
                results.add(VpnProviderResult.failure("provider-" + (index + 1), error.getClass().getSimpleName()));
            }
        }
        VpnCheckResult result = evaluate(normalized, settings, results);
        cache.put(normalized, new CacheEntry(result, now + settings.cacheMinutes() * 60_000L));
        return result;
    }

    static VpnCheckResult evaluate(String ip, VpnSettings settings, List<VpnProviderResult> providers) {
        int successful = 0;
        int signals = 0;
        int risk = 0;
        boolean highConfidence = false;
        String country = "";
        for (VpnProviderResult provider : providers) {
            if (!provider.success()) continue;
            successful++;
            if (country.isBlank() && !provider.countryCode().isBlank()) country = provider.countryCode();
            if (provider.suspicious()) signals++;
            risk = Math.max(risk, provider.score());
            highConfidence |= provider.highConfidence();
        }
        boolean allFailed = successful == 0;
        boolean positive = !allFailed && ((settings.blockOnSingleHighConfidence() && highConfidence)
                || signals >= settings.minimumSignals() || risk >= settings.minimumRisk());
        String reason;
        if (allFailed) reason = "Alle aktivierten VPN-Provider sind fehlgeschlagen oder haben das Zeitlimit überschritten.";
        else if (positive) reason = "VPN-/Proxy-/Tor-/Hosting-Schwelle erreicht: Signale=" + signals + ", Risiko=" + risk + ", hohe Sicherheit=" + (highConfidence ? "ja" : "nein") + ".";
        else reason = "Keine VPN-/Proxy-Blockschwelle erreicht: Signale=" + signals + ", Risiko=" + risk + ", hohe Sicherheit=" + (highConfidence ? "ja" : "nein") + ".";
        return new VpnCheckResult(ip, country, signals, risk, highConfidence, positive, false, allFailed, reason, providers);
    }

    VpnCheckResult cached(String ip) { CacheEntry entry=cache.get(ip);return entry!=null&&entry.expiresAt>System.currentTimeMillis()?entry.result:null; }
    int cacheSize() { return cache.size(); }
    void clearCache() { cache.clear(); }

    @Override
    public void close() {
        providerExecutor.shutdownNow();
        cache.clear();
    }

    private VpnProviderResult queryIpApi(String ip) {
        String provider = "ip-api";
        try {
            String fallback = "http://ip-api.com/json/{ip}?fields=status,message,countryCode,query,proxy,hosting,mobile,as,isp,org";
            String body = get(settings.providerUrl("ip-api", fallback).replace("{ip}", encode(ip)));
            if (string(body, "status").equalsIgnoreCase("fail")) return VpnProviderResult.failure(provider, string(body, "message"));
            return VpnProviderResult.detected(provider, string(body, "countryCode"), false, bool(body, "proxy"), false,
                    settings.blockHosting() && bool(body, "hosting"), firstNonEmpty(string(body, "isp"), string(body, "org")));
        } catch (Exception error) { return failure(provider, error); }
    }

    private VpnProviderResult queryIpWhois(String ip) {
        String provider = "ipwho.is";
        try {
            String body = get(settings.providerUrl("ipwhois", "https://ipwho.is/{ip}?security=1").replace("{ip}", encode(ip)));
            if (!bool(body, "success")) return VpnProviderResult.failure(provider, string(body, "message"));
            return VpnProviderResult.detected(provider, firstNonEmpty(string(body, "country_code"), string(body, "countryCode")),
                    bool(body, "vpn"), bool(body, "proxy") || bool(body, "anonymous"), bool(body, "tor"),
                    settings.blockHosting() && (bool(body, "hosting") || bool(body, "relay")),
                    firstNonEmpty(string(body, "connection"), string(body, "isp")));
        } catch (Exception error) { return failure(provider, error); }
    }

    private VpnProviderResult queryIpapiIs(String ip) {
        String provider = "ipapi.is";
        try {
            String url = settings.providerUrl("ipapi-is", "https://api.ipapi.is/?q={ip}").replace("{ip}", encode(ip));
            String key = settings.providerKey("ipapi-is");
            if (!key.isBlank() && !url.contains("key=")) url += (url.contains("?") ? "&" : "?") + "key=" + encode(key);
            String body = get(url);
            if (!string(body, "error").isBlank()) return VpnProviderResult.failure(provider, string(body, "error"));
            return VpnProviderResult.detected(provider, firstNonEmpty(string(body, "country_code"), string(body, "country")),
                    bool(body, "is_vpn"), bool(body, "is_proxy"), bool(body, "is_tor"),
                    settings.blockHosting() && bool(body, "is_datacenter"),
                    firstNonEmpty(string(body, "service"), firstNonEmpty(string(body, "domain"), string(body, "type"))));
        } catch (Exception error) { return failure(provider, error); }
    }

    private VpnProviderResult queryProxyCheck(String ip) {
        String provider = "proxycheck";
        try {
            String fallback = "https://proxycheck.io/v2/{ip}?vpn=1&asn=1&risk=1&node=1&tag=SimplexAdminSuite";
            String url = settings.providerUrl("proxycheck", fallback).replace("{ip}", encode(ip));
            String key = settings.providerKey("proxycheck");
            if (!key.isBlank() && !url.contains("key=")) url += (url.contains("?") ? "&" : "?") + "key=" + encode(key);
            String body = get(url);
            if (!string(body, "status").equalsIgnoreCase("ok"))
                return VpnProviderResult.failure(provider, firstNonEmpty(string(body, "message"), "Status nicht OK"));
            String type = string(body, "type").toLowerCase(Locale.ROOT);
            boolean proxy = string(body, "proxy").equalsIgnoreCase("yes") || bool(body, "proxy");
            boolean vpn = type.contains("vpn");
            boolean tor = type.contains("tor");
            boolean hosting = settings.blockHosting() && (type.contains("hosting") || type.contains("datacenter") || type.contains("server"));
            int risk = integer(body, "risk", -1);
            int score = vpn || proxy || tor ? 100 : hosting ? 70 : risk >= 90 ? 60 : 0;
            return new VpnProviderResult(provider, true,
                    firstNonEmpty(string(body, "isocode"), firstNonEmpty(string(body, "countryCode"), string(body, "country_code"))),
                    vpn, proxy, tor, hosting, score, vpn || proxy || tor, type.isBlank() ? "risk=" + risk : type + ", risk=" + risk);
        } catch (Exception error) { return failure(provider, error); }
    }

    private VpnProviderResult failure(String provider, Exception error) {
        logger.log(Level.FINE, "VPN provider " + provider + " failed: " + error.getMessage());
        return VpnProviderResult.failure(provider, error.getClass().getSimpleName());
    }

    private String get(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(settings.timeoutMillis()))
                .header("User-Agent", "SimplexAdminSuite/7.2.0 VPN-Protection")
                .GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IOException("HTTP " + response.statusCode());
        return response.body();
    }

    private static boolean isLocalAddress(String ip) {
        try {
            InetAddress address = InetAddress.getByName(ip);
            return address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress() || address.isMulticastAddress()
                    || ip.startsWith("127.") || ip.startsWith("10.") || ip.startsWith("192.168.")
                    || ip.matches("172\\.(1[6-9]|2[0-9]|3[0-1])\\..*") || ip.equals("::1")
                    || ip.toLowerCase(Locale.ROOT).startsWith("fc") || ip.toLowerCase(Locale.ROOT).startsWith("fd");
        } catch (Exception ignored) { return false; }
    }

    static boolean isIpLiteral(String value) {
        if (value == null || value.isBlank()) return false;
        String ip = value.trim();
        if (ip.indexOf(':') >= 0) {
            if (!ip.matches("[0-9A-Fa-f:.]+")) return false;
            try { return InetAddress.getByName(ip) instanceof java.net.Inet6Address; }
            catch (Exception ignored) { return false; }
        }
        String[] parts = ip.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (part.isBlank() || !part.matches("\\d{1,3}")) return false;
            try { if (Integer.parseInt(part) > 255) return false; }
            catch (Exception ignored) { return false; }
        }
        return true;
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static boolean bool(String json, String key) {
        Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*(true|false)", Pattern.CASE_INSENSITIVE).matcher(json);
        return matcher.find() && Boolean.parseBoolean(matcher.group(1));
    }
    private static String string(String json, String key) {
        Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"", Pattern.CASE_INSENSITIVE).matcher(json);
        return matcher.find() ? matcher.group(1) : "";
    }
    private static int integer(String json, String key, int fallback) {
        Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*(-?\\d+)", Pattern.CASE_INSENSITIVE).matcher(json);
        if (!matcher.find()) return fallback;
        try { return Integer.parseInt(matcher.group(1)); } catch (Exception ignored) { return fallback; }
    }
    private static String firstNonEmpty(String first, String second) { return first != null && !first.isBlank() ? first : second == null ? "" : second; }

    private static final class CacheEntry {
        private final VpnCheckResult result;
        private final long expiresAt;
        private CacheEntry(VpnCheckResult result, long expiresAt) { this.result = result; this.expiresAt = expiresAt; }
    }
}
