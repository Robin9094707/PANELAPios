import com.sun.net.httpserver.HttpServer;
import eu.simplexsmp.sentinelbridge.SimplexSentinelBridge;
import org.geysermc.geyser.api.connection.GeyserConnection;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class BridgeHarness {
    private static final String SECRET = "xtAEGOoXE6VqcD2fPyXu_o04rTPKcrrwGiImbVVnYYs2TgyCiazcHbXGiNHNX6_Q";

    public static final class ClientData {
        public String getDeviceId() { return "device-abc-123"; }
        public String getDeviceModel() { return "Test Console"; }
        public String getDeviceOs() { return "PLAYSTATION"; }
        public String getGameVersion() { return "1.21.99"; }
        public String getLanguageCode() { return "de_DE"; }
        public String getUiProfile() { return "CLASSIC"; }
        public String getCurrentInputMode() { return "CONTROLLER"; }
        public String getDefaultInputMode() { return "CONTROLLER"; }
        public String getSelfSignedId() { return "self-xyz"; }
        public long getClientRandomId() { return 123456789L; }
        public String getServerAddress() { return "play.example.net"; }
        public String getPlatformOnlineId() { return "platform-online-raw"; }
        public String getPlatformOfflineId() { return "platform-offline-raw"; }
        public String getOriginalString() {
            String json="{\"MemoryTier\":4,\"MaxViewDistance\":76,\"GraphicsMode\":1,\"PlatformType\":0,\"ProfileHash\":\"profile-test\",\"PlatformUserId\":\"experimental-user\",\"CompatibleWithClientSideChunkGen\":true,\"PremiumSkin\":false,\"PartyId\":\"secret-party\",\"SkinData\":\"DO_NOT_SEND\",\"nested\":{\"MemoryTier\":0}}";
            return "header."+java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8))+".signature";
        }
        public String getArmSize() { return "WIDE"; }
        public boolean isThirdPartyNameOnly() { return true; }
    }

    public static final class FakeConnection implements GeyserConnection {
        public String xuid() { return "2533274799999999"; }
        public String name() { return "BridgeTester"; }
        public String javaUsername() { return "BridgeTester"; }
        public String bedrockUsername() { return "Bridge Tester"; }
        public UUID javaUuid() { return UUID.fromString("11111111-2222-3333-4444-555555555555"); }
        public InetSocketAddress socketAddress() { return new InetSocketAddress("127.0.0.1", 19132); }
        public ClientData getClientData() { return new ClientData(); }
        public int getClientRenderDistance() { return 24; }
        public int getPing() { return 37; }
    }

    public static void main(String[] args) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Boolean> heartbeatSeen = new AtomicReference<>(false);
        AtomicInteger sessionRequests = new AtomicInteger();
        AtomicInteger heartbeats = new AtomicInteger();
        AtomicReference<String> firstNonce = new AtomicReference<>();
        long simulatedServerOffset = 300_000L;
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 39123), 1);
        server.createContext("/v1/session", exchange -> {
            try {
                byte[] body = exchange.getRequestBody().readAllBytes();
                String signature = exchange.getRequestHeaders().getFirst("X-Simplex-Signature");
                String text = new String(body, StandardCharsets.ISO_8859_1);
                Properties received = new Properties();
                received.load(new ByteArrayInputStream(body));
                String[] required = {"xuid=2533274799999999", "name=BridgeTester", "deviceId=device-abc-123",
                        "deviceModel=Test Console", "deviceOs=PLAYSTATION", "selfSignedId=self-xyz",
                        "clientRandomId=123456789", "ip=127.0.0.1", "serverAddress=play.example.net",
                        "platformOnlineId=platform-online-raw", "viewDistance=24", "ping=37", "schemaVersion=3", "memoryTier=4", "maxViewDistance=76", "loginGraphicsMode=1", "profileHash=profile-test", "premiumSkin=false", "snapshotPhase=SESSION"};
                if (text.contains("DO_NOT_SEND") || text.contains("signature") || text.contains("secret-party")) throw new AssertionError("Unallowlisted raw data left collector");
                if (!hmac(body).equals(signature)) throw new AssertionError("Invalid HMAC");
                for (String value : required) if (!text.contains(value)) throw new AssertionError("Missing " + value);
                if (sessionRequests.incrementAndGet() == 1) {
                    firstNonce.set(received.getProperty("nonce"));
                    exchange.getResponseHeaders().set("X-Simplex-Server-Time", Long.toString(System.currentTimeMillis() + simulatedServerOffset));
                    byte[] conflict = "stale timestamp".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(409, conflict.length);
                    exchange.getResponseBody().write(conflict);
                    exchange.getResponseBody().close();
                    return;
                }
                long timestamp = Long.parseLong(received.getProperty("timestamp", "0"));
                if (Math.abs(timestamp - (System.currentTimeMillis() + simulatedServerOffset)) > 5_000L) {
                    throw new AssertionError("Bridge did not adopt the server clock");
                }
                if (received.getProperty("nonce", "").equals(firstNonce.get())) throw new AssertionError("Nonce was not renewed after 409");
                exchange.sendResponseHeaders(202, 0);
                exchange.getResponseBody().close();
            } catch (Throwable error) {
                failure.set(error);
                try { exchange.sendResponseHeaders(400, 0); exchange.getResponseBody().close(); } catch (Exception ignored) { }
            }
        });
        server.createContext("/v1/heartbeat", exchange -> {
            try {
                byte[] body = exchange.getRequestBody().readAllBytes();
                String signature = exchange.getRequestHeaders().getFirst("X-Simplex-Signature");
                String text = new String(body, StandardCharsets.ISO_8859_1);
                if (text.contains("DO_NOT_SEND") || text.contains("signature") || text.contains("secret-party")) throw new AssertionError("Unallowlisted raw data left collector");
                if (!hmac(body).equals(signature)) throw new AssertionError("Invalid heartbeat HMAC");
                if (!text.contains("bridgeVersion=1.4.1") || !text.contains("protocol=2")) throw new AssertionError("Heartbeat metadata missing");
                heartbeatSeen.set(true);
                int request = heartbeats.incrementAndGet();
                byte[] settings = (request == 1 ? "enabled=false\ncosmetics=false\nparty=false\nmisc=false\nsession-refresh=false\n" : "enabled=true\n").getBytes(StandardCharsets.ISO_8859_1);
                exchange.getResponseHeaders().set("X-Simplex-Response-Signature",request == 1 ? hmac(settings) : "invalid");
                exchange.sendResponseHeaders(200, settings.length);
                exchange.getResponseBody().write(settings); exchange.getResponseBody().close();
            } catch (Throwable error) {
                failure.set(error);
                try { exchange.sendResponseHeaders(400, 0); exchange.getResponseBody().close(); } catch (Exception ignored) { }
            } finally { exchange.close(); }
        });
        server.start();
        try {
            SimplexSentinelBridge bridge = new SimplexSentinelBridge();
            Field configField = SimplexSentinelBridge.class.getDeclaredField("config");
            configField.setAccessible(true);
            Properties config = new Properties();
            config.setProperty("host", "127.0.0.1");
            config.setProperty("port", "39123");
            config.setProperty("path", "/v1/session");
            config.setProperty("shared-secret", SECRET);
            config.setProperty("connect-timeout-ms", "1000");
            config.setProperty("read-timeout-ms", "1000");
            config.setProperty("retry-count", "3");
            configField.set(bridge, config);
            Method collect = SimplexSentinelBridge.class.getDeclaredMethod("collect", GeyserConnection.class, boolean.class);
            collect.setAccessible(true);
            Properties early = (Properties) collect.invoke(bridge, new FakeConnection(), false);
            if (early.containsKey("viewDistance") || !early.getProperty("snapshotPhase").equals("LOGIN")) throw new AssertionError("Early render distance claimed as measured");
            Properties payload = (Properties) collect.invoke(bridge, new FakeConnection(), true);
            Method sendWithRetry = SimplexSentinelBridge.class.getDeclaredMethod("sendWithRetry", Properties.class, boolean.class);
            sendWithRetry.setAccessible(true);
            sendWithRetry.invoke(bridge, payload, false);
            if (sessionRequests.get() != 2) throw new AssertionError("Expected one corrected retry, got " + sessionRequests.get());

            Properties heartbeat = new Properties();
            heartbeat.setProperty("timestamp", String.valueOf(System.currentTimeMillis()));
            heartbeat.setProperty("nonce", UUID.randomUUID().toString());
            heartbeat.setProperty("bridgeVersion", "1.4.1");
            heartbeat.setProperty("protocol", "2");
            Method encode = SimplexSentinelBridge.class.getDeclaredMethod("encode", Properties.class);
            encode.setAccessible(true);
            byte[] heartbeatBody = (byte[]) encode.invoke(bridge, heartbeat);
            Method sendTo = SimplexSentinelBridge.class.getDeclaredMethod("send", byte[].class, String.class);
            sendTo.setAccessible(true);
            int heartbeatStatus = (Integer) sendTo.invoke(bridge, heartbeatBody, "http://127.0.0.1:39123/v1/heartbeat");
            if (heartbeatStatus != 200 || !heartbeatSeen.get()) throw new AssertionError("Heartbeat failed: " + heartbeatStatus);
            Properties disabled = (Properties)collect.invoke(bridge,new FakeConnection(),true);
            if(disabled.containsKey("memoryTier")) throw new AssertionError("Signed remote settings were ignored");
            sendTo.invoke(bridge,heartbeatBody,"http://127.0.0.1:39123/v1/heartbeat");
            disabled = (Properties)collect.invoke(bridge,new FakeConnection(),true);
            if(disabled.containsKey("memoryTier")) throw new AssertionError("Forged settings changed collection");
            Field remote=SimplexSentinelBridge.class.getDeclaredField("remoteSettings"); remote.setAccessible(true);remote.set(bridge,new Properties());
            config.setProperty("diagnostics.cosmetics","false");
            Properties restricted=(Properties)collect.invoke(bridge,new FakeConnection(),true);
            if(restricted.containsKey("profileHash")||!restricted.containsKey("memoryTier")) throw new AssertionError("Local collection restrictions lost");
            java.nio.file.Path box=java.nio.file.Files.createTempDirectory("bridge-outbox-test-");
            try {
                Field outbox=SimplexSentinelBridge.class.getDeclaredField("outbox");outbox.setAccessible(true);outbox.set(bridge,box);
                config.setProperty("outbox-max-files","1");
                Method spool=SimplexSentinelBridge.class.getDeclaredMethod("spool",byte[].class);spool.setAccessible(true);
                spool.invoke(bridge,(Object)heartbeatBody);spool.invoke(bridge,(Object)heartbeatBody);
                try(var files=java.nio.file.Files.list(box)){ if(files.count()!=1)throw new AssertionError("Outbox exceeded bound"); }
            } finally {try(var files=java.nio.file.Files.list(box)){for(var file:files.toList())java.nio.file.Files.delete(file);}java.nio.file.Files.delete(box);}
            if (failure.get() != null) throw new AssertionError(failure.get());
            System.out.println("Bridge harness OK");
        } finally {
            server.stop(0);
        }
    }

    private static String hmac(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        StringBuilder result = new StringBuilder();
        for (byte value : mac.doFinal(body)) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }
}
