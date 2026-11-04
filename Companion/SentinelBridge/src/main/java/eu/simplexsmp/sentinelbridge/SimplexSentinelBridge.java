package eu.simplexsmp.sentinelbridge;

import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.connection.GeyserConnection;
import org.geysermc.geyser.api.event.bedrock.SessionDisconnectEvent;
import org.geysermc.geyser.api.event.bedrock.SessionLoginEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserShutdownEvent;
import org.geysermc.geyser.api.extension.Extension;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Optional Geyser-side data collector for SimplexAdminSuite (10.3.0 recommended; older suites stay compatible).
 * It does not punish players and does not maintain a second player database. It only forwards
 * Bedrock session metadata to the local Paper plugin through an HMAC-authenticated loopback call.
 */
public final class SimplexSentinelBridge implements Extension {
    private static final String VERSION = "1.6.0";
    private final SecureRandom random = new SecureRandom();
    private Properties config;
    private ScheduledExecutorService executor;
    private volatile long lastHeartbeatWarning;
    private volatile long serverClockOffsetMillis;
    private Path configFile;
    private Path outbox;
    private volatile Properties remoteSettings = new Properties();
    private volatile boolean capabilityReported;
    private volatile long nextSessionRefresh;
    private final java.util.Map<String, Long> warnedAt = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile int skippedWithoutIdentity;

    @Subscribe
    public void onPostInitialize(GeyserPostInitializeEvent event) {
        try {
            Files.createDirectories(dataFolder());
            configFile = dataFolder().resolve("bridge.properties");
            outbox = dataFolder().resolve("outbox");
            loadConfig();
            executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "SimplexSentinelBridge");
                thread.setDaemon(true);
                return thread;
            });
            executor.execute(this::flushOutbox);
            executor.scheduleAtFixedRate(this::sendHeartbeat, 5L, 60L, TimeUnit.SECONDS);
            executor.scheduleWithFixedDelay(this::flushOutbox, 30L, 30L, TimeUnit.SECONDS);
            logger().info("Bedrock metadata capabilities: " + startupCapabilities());
            logger().info("SimplexSentinelBridge v" + VERSION + " active. Session target: " + endpoint());
        } catch (Exception error) {
            logger().error("SimplexSentinelBridge could not initialize: " + error.getMessage());
        }
    }

    @Subscribe
    public void onShutdown(GeyserShutdownEvent event) {
        if (executor != null) {
            executor.shutdown();
            try { executor.awaitTermination(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            executor.shutdownNow();
            executor = null;
        }
        logger().info("SimplexSentinelBridge stopped.");
    }

    @Subscribe
    public void onLogin(SessionLoginEvent event) {
        GeyserConnection connection = event.connection();
        if (connection == null || executor == null) return;
        Properties payload = collect(connection);
        executor.execute(() -> sendWithRetry(payload, true));
        executor.schedule(() -> {
            if (active(connection)) sendWithRetry(collect(connection, true), true);
        }, 5L, TimeUnit.SECONDS);
    }

    /** 1.6.0: Geyser's own disconnect reason (client closed, timeout, kicked …) for the suite's diagnostics. */
    @Subscribe
    public void onDisconnect(SessionDisconnectEvent event) {
        GeyserConnection connection = event.connection();
        if (connection == null || executor == null || executor.isShutdown()) return;
        Properties p = new Properties();
        refreshEnvelope(p);
        p.setProperty("bridgeVersion", VERSION);
        identity(connection, p);
        String reason = "";
        try { reason = event.disconnectReason(); } catch (RuntimeException ignored) { }
        put(p, "disconnectReason", reason == null ? "" : reason);
        p.setProperty("snapshotPhase", "DISCONNECT");
        p.setProperty("sessionObservedAt", Long.toString(System.currentTimeMillis()));
        if (!hasIdentity(p) || !p.containsKey("disconnectReason")) return;
        try { executor.execute(() -> sendWithRetry(p, false)); } catch (RuntimeException ignored) { }
    }

    /** Java name/UUID once Geyser knows them, the Bedrock gamertag and XUID always (the suite derives Floodgate UUIDs). */
    private void identity(GeyserConnection connection, Properties p) {
        put(p, "xuid", safeCall(connection::xuid));
        put(p, "name", firstNonBlank(safeCall(connection::javaUsername), read(connection, "getUsername", "username")));
        put(p, "bedrockName", safeCall(connection::bedrockUsername));
        UUID java = null;
        try { java = connection.javaUuid(); } catch (RuntimeException ignored) { }
        put(p, "uuid", java == null ? read(connection, "getJavaUuid", "uuid", "getUuid") : java.toString());
    }

    private static boolean hasIdentity(Properties p) {
        return !p.getProperty("name", "").isBlank() || !p.getProperty("uuid", "").isBlank() || p.getProperty("xuid", "").matches("[0-9]{1,19}");
    }

    private void warnThrottled(String key, String message) {
        long now = System.currentTimeMillis();
        Long last = warnedAt.get(key);
        if (last != null && now - last < TimeUnit.MINUTES.toMillis(5L)) return;
        warnedAt.put(key, now);
        logger().warning(message);
    }

    private String startupCapabilities() {
        try {
            Class<?> data=Class.forName("org.geysermc.geyser.session.auth.BedrockClientData");
            boolean jwt=data.getMethod("getOriginalString")!=null;
            return "ClientData accessor="+jwt+"; session/API fields are checked on first login; unavailable fields remain absent.";
        } catch(ReflectiveOperationException | LinkageError ignored) {
            return "runtime lookup deferred to first login (Geyser classloader compatibility).";
        }
    }

    private Properties collect(GeyserConnection connection) { return collect(connection, false); }

    private Properties collect(GeyserConnection connection, boolean session) {
        Properties p = new Properties();
        refreshEnvelope(p);
        p.setProperty("bridgeVersion", VERSION);
        identity(connection, p);
        // Geyser exposes the authenticated PlayFab identity on current connections. It is
        // forwarded as an additional diagnostic identifier, never as an autonomous ban signal.
        put(p, "playFabId", firstNonBlank(read(connection, "playFabId", "getPlayFabId"), read(connection, "playfabId", "getPlayfabId")));
        try { p.setProperty("floodgateLinked", Boolean.toString(connection.isLinked())); } catch (RuntimeException | LinkageError ignored) { }
        put(p, "ip", socketIp(connection));

        Object clientData = invoke(connection, "getClientData");
        if (clientData == null) clientData = invoke(connection, "clientData");
        Object source = clientData == null ? connection : clientData;
        put(p, "deviceId", read(source, "getDeviceId", "deviceId"));
        put(p, "deviceModel", read(source, "getDeviceModel", "deviceModel"));
        put(p, "deviceOs", firstNonBlank(read(source, "getDeviceOs", "deviceOs"), read(connection, "platform")));
        put(p, "gameVersion", firstNonBlank(read(source, "getGameVersion", "gameVersion"), read(connection, "version", "gameVersion", "getGameVersion")));
        put(p, "languageCode", read(source, "getLanguageCode", "languageCode"));
        put(p, "uiProfile", read(source, "getUiProfile", "uiProfile"));
        put(p, "guiScale", read(source, "getGuiScale", "guiScale"));
        put(p, "currentInputMode", firstNonBlank(read(source, "getCurrentInputMode", "currentInputMode"), read(connection, "inputMode", "getInputMode")));
        put(p, "defaultInputMode", read(source, "getDefaultInputMode", "defaultInputMode"));
        put(p, "selfSignedId", read(source, "getSelfSignedId", "selfSignedId"));
        put(p, "clientRandomId", read(source, "getClientRandomId", "clientRandomId"));
        put(p, "serverAddress", read(source, "getServerAddress", "serverAddress"));
        put(p, "platformOnlineId", read(source, "getPlatformOnlineId", "platformOnlineId"));
        put(p, "platformOfflineId", read(source, "getPlatformOfflineId", "platformOfflineId"));
        put(p, "armSize", read(source, "getArmSize", "armSize"));
        put(p, "thirdPartyNameOnly", firstNonBlank(read(source, "isThirdPartyNameOnly", "getThirdPartyNameOnly"), read(source, "thirdPartyNameOnly")));
        put(p, "viewDistance", firstNonBlank(read(connection, "getClientRenderDistance", "clientRenderDistance", "getRenderDistance", "renderDistance"), read(source, "getViewDistance", "viewDistance")));
        put(p, "ping", firstNonBlank(read(connection, "getPing", "ping", "getLatency", "latency"), read(source, "getPing", "ping")));
        p.setProperty("sessionObservedAt", Long.toString(System.currentTimeMillis()));
        if (diagnosticOption("enabled", true)) {
            BedrockMetadata.collect(connection, clientData, p, diagnosticOption("cosmetics", true), diagnosticOption("party", false), diagnosticOption("misc", true), session);
            put(p, "geyserVersion", connection.getClass().getPackage().getImplementationVersion());
            if (!capabilityReported) {
                capabilityReported = true;
                logger().info("Bedrock diagnostics source: " + p.getProperty("collectionSource", "unavailable")
                        + "; protocol API: " + p.containsKey("bedrockProtocolVersion") + "; available metadata fields: " + p.size()
                        + ". Missing fields remain unavailable; ClientData is not hardware attestation.");
            }
        } else { p.setProperty("snapshotPhase", session ? "SESSION" : "LOGIN"); if (!session) p.remove("viewDistance"); }
        return p;
    }

    private boolean diagnosticOption(String key, boolean fallback) {
        return boolConfig("diagnostics." + key, fallback) && Boolean.parseBoolean(remoteSettings.getProperty(key, Boolean.toString(fallback)));
    }

    private Object runtimeApi() {
        try { return Class.forName("org.geysermc.geyser.api.GeyserApi").getMethod("api").invoke(null); }
        catch (ReflectiveOperationException | LinkageError ignored) { return null; }
    }

    private boolean active(GeyserConnection connection) {
        if (Boolean.TRUE.equals(invoke(connection, "isClosed"))) return false;
        Object upstream = invoke(connection, "getUpstream");
        if (Boolean.TRUE.equals(invoke(upstream, "isClosed"))) return false;
        Object api = runtimeApi();
        if (api == null) return upstream != null;
        try { return api.getClass().getMethod("connectionByXuid", String.class).invoke(api, connection.xuid()) == connection; }
        catch (ReflectiveOperationException | RuntimeException ignored) { return false; }
    }

    private void refreshSessions() {
        if (!diagnosticOption("session-refresh", true) || System.currentTimeMillis() < nextSessionRefresh) return;
        nextSessionRefresh = System.currentTimeMillis() + Math.max(60, intConfig("session-refresh-seconds", 120)) * 1000L;
        Object connections = invoke(runtimeApi(), "onlineConnections");
        if (connections instanceof Iterable<?>) for (Object connection : (Iterable<?>)connections) {
            if (connection instanceof GeyserConnection && active((GeyserConnection) connection))
                sendWithRetry(collect((GeyserConnection)connection, true), false);
        }
    }

    private void sendWithRetry(Properties payload, boolean maySpool) {
        if (!hasIdentity(payload)) {
            // Too early in the Bedrock login: nothing to attach yet. The 5-second snapshot follows automatically.
            skippedWithoutIdentity++;
            return;
        }
        byte[] body = encode(payload);
        int attempts = Math.max(1, intConfig("retry-count", 3));
        Exception last = null;
        boolean permanent = false;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                int status = send(body);
                if (status >= 200 && status < 300) {
                    if (attempt > 1) logger().info("Alt session delivered after retry " + attempt + ".");
                    return;
                }
                last = new IllegalStateException(status == 409
                        ? "HTTP 409 (Zeitstempel abgelehnt; Serverzeit wird übernommen und Anfrage erneuert)"
                        : "HTTP " + status);
                if (status == 409 && attempt < attempts) {
                    refreshEnvelope(payload);
                    body = encode(payload);
                }
                // 400/401/403/413/422 do not change on retry; stop immediately instead of hammering the suite.
                if (status >= 400 && status < 500 && status != 409 && status != 429) { permanent = true; break; }
            } catch (Exception error) { last = error; }
            if (attempt < attempts) {
                try { Thread.sleep(Math.min(1500L, 250L * attempt)); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
            }
        }
        if (maySpool && !permanent && boolConfig("spool-failed-payloads", false)) spool(body);
        String detail = last == null ? "unknown error" : last.getMessage();
        warnThrottled("deliver:" + detail, "Could not deliver Bedrock identity metadata to SimplexAdminSuite: " + detail
                + (detail != null && detail.contains("401") ? " (shared-secret in bridge.properties and alt-detection.bridge differ)" : ""));
    }

    private int send(byte[] body) throws Exception {
        return send(body, endpoint());
    }

    private int send(byte[] body, String targetEndpoint) throws Exception {
        URL url = new URL(targetEndpoint);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(intConfig("connect-timeout-ms", 1500));
        connection.setReadTimeout(intConfig("read-timeout-ms", 2000));
        connection.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
        connection.setRequestProperty("X-Simplex-Signature", hmacHex(body, config.getProperty("shared-secret", "CHANGE-ME")));
        connection.setFixedLengthStreamingMode(body.length);
        try {
            connection.getOutputStream().write(body);
            int status = connection.getResponseCode();
            updateServerClock(connection.getHeaderField("X-Simplex-Server-Time"));
            InputStream response = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (response != null) try (response) {
                byte[] received = response.readNBytes(16385);
                String responseSignature = connection.getHeaderField("X-Simplex-Response-Signature");
                if (status == 200 && received.length <= 16384 && responseSignature != null
                        && java.security.MessageDigest.isEqual(hmacHex(received, config.getProperty("shared-secret")).getBytes(StandardCharsets.US_ASCII), responseSignature.getBytes(StandardCharsets.US_ASCII))) {
                    Properties settings = decode(received);
                    remoteSettings = settings;
                }
            }
            return status;
        } finally { connection.disconnect(); }
    }

    private void sendHeartbeat() {
        if (executor == null || executor.isShutdown()) return;
        Properties heartbeat = new Properties();
        refreshEnvelope(heartbeat);
        heartbeat.setProperty("bridgeVersion", VERSION);
        heartbeat.setProperty("protocol", "2");
        try {
            byte[] body = encode(heartbeat);
            int status = send(body, endpoint("heartbeat-path", "/v1/heartbeat"));
            if (status == 409) {
                refreshEnvelope(heartbeat);
                body = encode(heartbeat);
                status = send(body, endpoint("heartbeat-path", "/v1/heartbeat"));
            }
            if (status < 200 || status >= 300) throw new IllegalStateException("HTTP " + status);
            refreshSessions();
        } catch (Exception error) {
            long now = System.currentTimeMillis();
            if (now - lastHeartbeatWarning >= TimeUnit.MINUTES.toMillis(5L)) {
                lastHeartbeatWarning = now;
                logger().warning("SimplexAdminSuite bridge heartbeat failed: " + error.getMessage());
            }
        }
    }

    private byte[] encode(Properties properties) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            properties.store(output, "SimplexSentinelBridge session");
            return output.toByteArray();
        } catch (Exception error) { throw new IllegalStateException(error); }
    }

    private Properties decode(byte[] body) throws Exception {
        Properties properties = new Properties();
        try (ByteArrayInputStream input = new ByteArrayInputStream(body)) { properties.load(input); }
        return properties;
    }

    private void refreshEnvelope(Properties properties) {
        properties.setProperty("timestamp", String.valueOf(nowForServer()));
        properties.setProperty("nonce", UUID.randomUUID() + "-" + Long.toUnsignedString(random.nextLong()));
    }

    private long nowForServer() {
        return System.currentTimeMillis() + serverClockOffsetMillis;
    }

    private void updateServerClock(String rawServerTime) {
        if (rawServerTime == null || rawServerTime.isBlank()) return;
        try {
            long measured = Long.parseLong(rawServerTime.trim()) - System.currentTimeMillis();
            long maximum = TimeUnit.DAYS.toMillis(7L);
            serverClockOffsetMillis = Math.max(-maximum, Math.min(maximum, measured));
        } catch (NumberFormatException ignored) { }
    }

    private void spool(byte[] body) {
        try {
            Files.createDirectories(outbox);
            pruneOutbox();
            Path temp = outbox.resolve("." + System.currentTimeMillis() + ".tmp");
            Path target = outbox.resolve(System.currentTimeMillis() + "-" + UUID.randomUUID() + ".pending");
            Files.write(temp, body);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            pruneOutbox();
        } catch (Exception error) { logger().warning("Could not spool failed bridge payload: " + error.getMessage()); }
    }

    private void flushOutbox() {
        if (!boolConfig("spool-failed-payloads", false) || outbox == null || !Files.isDirectory(outbox)) return;
        pruneOutbox();
        try (var files = Files.list(outbox)) {
            for (Path path : files.filter(p -> p.getFileName().toString().endsWith(".pending")).sorted().limit(25).toList()) {
                try {
                    if (Files.size(path) > 32768L) { Files.deleteIfExists(path); continue; }
                    Properties payload = decode(Files.readAllBytes(path));
                    refreshEnvelope(payload);
                    byte[] body = encode(payload);
                    int status = send(body);
                    if (status == 409) { refreshEnvelope(payload); status = send(encode(payload)); }
                    if (status >= 200 && status < 300) Files.deleteIfExists(path);
                    else if (status >= 400 && status < 500 && status != 409 && status != 429) Files.deleteIfExists(path); // permanently rejected
                    else break; // A down receiver must not starve heartbeats and live sessions.
                } catch (Exception ignored) { break; }
            }
        } catch (Exception error) { logger().warning("Could not flush bridge outbox: " + error.getMessage()); }
    }

    private void pruneOutbox() {
        if (outbox == null || !Files.isDirectory(outbox)) return;
        long cutoff = System.currentTimeMillis() - Math.max(1, intConfig("outbox-max-age-hours", 24)) * 3600000L;
        int maximum = Math.max(1, Math.min(10000, intConfig("outbox-max-files", 500)));
        long maxBytes = Math.max(32768L, Math.min(100L, intConfig("outbox-max-mib", 8)) * 1024L * 1024L);
        try (var paths = Files.list(outbox)) {
            var files = paths.filter(path -> path.getFileName().toString().endsWith(".pending")).sorted(java.util.Comparator.reverseOrder()).toList();
            int kept = 0; long bytes = 0L;
            for (Path path : files) {
                long size = Files.size(path);
                if (Files.getLastModifiedTime(path).toMillis() < cutoff || size > 32768L || kept >= maximum || bytes + size > maxBytes) Files.deleteIfExists(path);
                else { kept++; bytes += size; }
            }
        } catch (Exception error) { logger().warning("Could not prune bridge outbox: " + error.getClass().getSimpleName()); }
    }

    private void loadConfig() throws Exception {
        if (!Files.exists(configFile)) {
            try (InputStream input = getClass().getClassLoader().getResourceAsStream("bridge.properties")) {
                if (input == null) throw new IllegalStateException("Missing bridge.properties resource");
                Files.copy(input, configFile);
            }
        }
        config = new Properties();
        try (InputStream input = Files.newInputStream(configFile)) { config.load(input); }
        if (config.getProperty("shared-secret", "").isBlank() || "CHANGE-ME".equals(config.getProperty("shared-secret"))) {
            throw new IllegalStateException("shared-secret is missing or insecure");
        }
    }

    private String endpoint() { return endpoint("path", "/v1/session"); }

    private String endpoint(String pathKey, String fallbackPath) {
        String host = config.getProperty("host", "127.0.0.1").trim();
        int port = intConfig("port", 39092);
        String path = config.getProperty(pathKey, fallbackPath).trim();
        if (!path.startsWith("/")) path = "/" + path;
        String scheme = config.getProperty("scheme", "http").trim().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) throw new IllegalArgumentException("scheme must be http or https");
        if (host.contains(":") && !host.startsWith("[")) host = "[" + host + "]";
        return scheme + "://" + host + ":" + port + path;
    }

    private String socketIp(Object connection) {
        Object address = firstNonNull(invoke(connection, "getSocketAddress"), invoke(connection, "bedrockAddress"), invoke(connection, "socketAddress"), invoke(connection, "remoteAddress"), invoke(connection, "getRemoteAddress"));
        if (address instanceof InetSocketAddress) {
            InetAddress inet = ((InetSocketAddress) address).getAddress();
            return inet == null ? ((InetSocketAddress) address).getHostString() : inet.getHostAddress();
        }
        if (address instanceof SocketAddress) return address.toString().replaceFirst("^/", "").replaceFirst(":\\d+$", "");
        return address == null ? "" : String.valueOf(address);
    }

    private String read(Object target, String... methods) {
        for (String method : methods) {
            Object value = invoke(target, method);
            if (value != null) {
                String text = String.valueOf(value).trim();
                if (!text.isEmpty() && !text.equalsIgnoreCase("null") && !text.equalsIgnoreCase("unknown")) return text;
            }
        }
        return "";
    }

    private Object invoke(Object target, String method) {
        if (target == null) return null;
        try {
            Method candidate = target.getClass().getMethod(method);
            candidate.setAccessible(true);
            return candidate.invoke(target);
        } catch (Exception ignored) { return null; }
    }

    private Object firstNonNull(Object... values) { for (Object value : values) if (value != null) return value; return null; }
    private String firstNonBlank(String... values) { for (String value : values) if (value != null && !value.isBlank()) return value; return ""; }
    private String safeCall(SupplierWithException supplier) { try { String value = supplier.get(); return value == null ? "" : value; } catch (Exception ignored) { return ""; } }
    private void put(Properties properties, String key, String value) { if (value != null && !value.isBlank()) properties.setProperty(key, sanitize(value, 512)); }
    private String sanitize(String value, int max) { String clean = value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim(); return clean.length() <= max ? clean : clean.substring(0, max); }
    private int intConfig(String key, int fallback) { try { return Integer.parseInt(config.getProperty(key, String.valueOf(fallback)).trim()); } catch (Exception ignored) { return fallback; } }
    private boolean boolConfig(String key, boolean fallback) { return Boolean.parseBoolean(config.getProperty(key, String.valueOf(fallback)).trim()); }

    private String hmacHex(byte[] input, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal(input);
        StringBuilder text = new StringBuilder(digest.length * 2);
        for (byte value : digest) text.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return text.toString();
    }

    @FunctionalInterface private interface SupplierWithException { String get() throws Exception; }
}
