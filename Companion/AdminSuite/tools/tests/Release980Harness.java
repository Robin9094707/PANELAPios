package eu.simplexsmp.adminsuite;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javax.net.ssl.*;
import org.bukkit.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.*;

/**
 * 9.8.0: accounts, main admin migration, TOTP (RFC 6238 vectors), recovery codes, passkeys with real signatures,
 * browser cookie sessions, same-origin protection, device lock/rename/logout and the console-only emergency reset.
 * Runs the production HTTPS listener with synthetic Bukkit services (same fixture as AppApiIntegrationHarness).
 */
public final class Release980Harness {
    static int checks;
    static void check(boolean value, String reason) { checks++; if (!value) throw new AssertionError(reason); }
    static void fails(Callable<?> work, String reason) { try { work.call(); } catch (Exception | AssertionError e) { checks++; return; } throw new AssertionError("Expected failure: " + reason); }
    @SuppressWarnings("unchecked") static Map<String, Object> obj(String json) { return (Map<String, Object>) DiscordBotManager.Json.parse(json); }
    static String json(Object v) { return DiscordBotManager.Json.stringify(v); }
    static HttpClient client; static URI base; static String host;

    public static void main(String[] args) throws Exception {
        units();
        webauthn();
        http();
        System.out.println("PASS " + checks + " 9.8.0 account, 2FA, passkey, web panel and device checks");
    }

    // ------------------------------------------------------------------ pure units

    static void units() throws Exception {
        byte[] rfc = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
        check(AccountCrypto.totp(rfc, 59 / 30) == 287082, "RFC 6238 vector T=59");
        check(AccountCrypto.totp(rfc, 1111111109L / 30) == 81804, "RFC 6238 vector T=1111111109");
        check(AccountCrypto.totp(rfc, 2000000000L / 30) == 279037, "RFC 6238 vector T=2000000000");
        check(Arrays.equals(AccountCrypto.unbase32(AccountCrypto.base32(rfc)), rfc), "Base32 round trip");
        check(AccountCrypto.base32("foobar".getBytes(StandardCharsets.US_ASCII)).equals("MZXW6YTBOI"), "RFC 4648 Base32 vector");
        String secret = AccountCrypto.newTotpSecret(); long now = 1_800_000_000_000L;
        String code = AccountCrypto.code(AccountCrypto.totp(AccountCrypto.unbase32(secret), now / 30_000));
        check(AccountCrypto.verifyTotp(secret, code, now) == now / 30_000, "current code accepted");
        check(AccountCrypto.verifyTotp(secret, code, now + 30_000) >= 0, "one step clock drift tolerated");
        check(AccountCrypto.verifyTotp(secret, code, now + 95_000) < 0, "old code rejected");
        check(AccountCrypto.verifyTotp(secret, "12a456", now) < 0, "non-digit code rejected");
        check(AccountCrypto.otpauth("Simplex Panel", "robin", secret).startsWith("otpauth://totp/Simplex%20Panel:robin?secret=" + secret), "otpauth URI");
        check(AccountCrypto.password().matches("[A-Za-z0-9]{5}(-[A-Za-z0-9]{5}){3}"), "emergency password format");
        check(AccountCrypto.recoveryCodes().size() == 10 && AccountCrypto.recoveryCodes().get(0).matches("[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}"), "recovery code format");
        check(AccountApi.validateDomain("https://Admin.Example.EU/").equals("admin.example.eu") && AccountApi.validateDomain("admin.example.eu:39093").equals("admin.example.eu:39093"), "domain normalised");
        fails(() -> AccountApi.validateDomain("192.168.1.10"), "IP rejected as passkey domain");
        fails(() -> AccountApi.validateDomain("bad domain"), "invalid domain rejected");
        check(WebAuthn.originAllowed("https://admin.example.eu", "admin.example.eu") && WebAuthn.originAllowed("https://admin.example.eu:8443", "admin.example.eu"), "origin with and without port");
        check(!WebAuthn.originAllowed("http://admin.example.eu", "admin.example.eu") && !WebAuthn.originAllowed("https://evil.example.eu", "admin.example.eu") && !WebAuthn.originAllowed("https://admin.example.eu.evil.com", "admin.example.eu"), "foreign origins rejected");

        Path dir = Files.createTempDirectory("suite980-units-");
        AppApiSecurity security = new AppApiSecurity(dir.resolve("api"));
        var key = security.issue("Robin iPhone", "full");
        security.users.save("helper", "helper-password-980", Set.of("data.players"), true);
        security.users.createOwner("Robin", "owner-password-980");
        check(security.users.owner("robin") && security.users.permissions("robin").contains("*"), "owner created with full rights");
        fails(() -> { security.users.createOwner("other", "another-password-1"); return null; }, "only one main admin");
        check(security.authenticate(key.token()) != null, "existing key stays valid after migration");
        check(security.users.list().get(0).get("username").equals("robin"), "owner listed first");
        String session = security.users.createSession("robin", System.currentTimeMillis(), AppUsers.APP_SESSION, Map.of("kind", "app", "model", "iPhone 17 Pro"));
        var user = security.authenticate(session);
        check(user != null && !security.locked(user), "session authenticates");
        String id = user.hash().substring(0, 24);
        security.users.updateSession(security.users.digestFor(id), "lock", "");
        check(security.locked(user) && security.authenticate(session) != null, "locked session still identified but locked");
        security.users.updateSession(security.users.digestFor(id), "unlock", "");
        security.users.updateSession(security.users.digestFor(id), "rename", "Robins Handy");
        check(!security.locked(user) && "Robins Handy".equals(security.users.sessionRows("robin").get(0).get("label")), "unlock and rename");
        security.touch(user, "203.0.113.7", Map.of("app", "4.1.0"));
        check("203.0.113.7".equals(security.users.sessionRows("robin").get(0).get("ip")), "IP recorded in memory");
        AppApiSecurity reloaded = new AppApiSecurity(dir.resolve("api"));
        check(reloaded.authenticate(session) != null && reloaded.users.owner("robin"), "owner and sessions survive restart");

        security.users.enableTotp("robin", secret);
        check(security.users.login("robin", "owner-password-980", System.currentTimeMillis()) == null, "legacy one-step login refused with 2FA");
        long t = System.currentTimeMillis(); String current = AccountCrypto.code(AccountCrypto.totp(AccountCrypto.unbase32(secret), t / 30_000));
        check(security.users.checkTotp("robin", current, t), "TOTP accepted once");
        check(!security.users.checkTotp("robin", current, t), "TOTP replay rejected");
        List<String> recovery = security.users.newRecovery("robin");
        check(security.users.recoveryLeft("robin") == 10 && security.users.useRecovery("robin", recovery.get(3).toLowerCase(Locale.ROOT)), "recovery code accepted (case-insensitive)");
        check(!security.users.useRecovery("robin", recovery.get(3)) && security.users.recoveryLeft("robin") == 9, "recovery code single use");
        check(!Files.readString(dir.resolve("api/users.properties")).contains(recovery.get(4)), "recovery codes stored hashed");
        fails(() -> security.users.setupCode("robin", 60, System.currentTimeMillis()), "setup links cannot bypass 2FA");

        String[] reset = security.users.emergencyReset(null);
        check(reset[0].equals("robin") && reset[2].equals("false"), "emergency reset shows main admin name");
        check(!security.users.mfa("robin") && security.users.recoveryLeft("robin") == 0 && security.users.mustChange("robin"), "reset removes 2FA and forces new password");
        check(security.authenticate(session) == null, "reset signs out the main admin");
        check(security.authenticate(key.token()) != null && security.users.exists("helper"), "reset leaves keys and other users untouched");
        check(security.users.verifyPassword("robin", reset[1]) != null && security.users.login("robin", reset[1], System.currentTimeMillis()) == null, "one-time password valid but needs change");
        security.users.replacePassword("robin", "brand-new-password-980", null);
        check(!security.users.mustChange("robin") && security.users.verifyPassword("robin", "brand-new-password-980") != null, "new password set after reset");
        fails(() -> { security.users.replacePassword("robin", "short", null); return null; }, "short password rejected");

        Path empty = Files.createTempDirectory("suite980-empty-");
        AppApiSecurity fresh = new AppApiSecurity(empty.resolve("api"));
        fails(() -> fresh.users.emergencyReset(null), "reset without owner needs a name");
        String[] created = fresh.users.emergencyReset("Notfall");
        check(created[0].equals("notfall") && created[2].equals("true") && fresh.users.owner("notfall"), "console can create the first main admin");
    }

    // ------------------------------------------------------------------ WebAuthn with real keys

    static byte[] cbor(Object value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); writeCbor(out, value); return out.toByteArray();
    }
    static void head(ByteArrayOutputStream out, int major, long n) {
        if (n < 24) out.write(major << 5 | (int) n);
        else if (n < 256) { out.write(major << 5 | 24); out.write((int) n); }
        else { out.write(major << 5 | 25); out.write((int) (n >> 8)); out.write((int) n & 0xff); }
    }
    @SuppressWarnings("unchecked")
    static void writeCbor(ByteArrayOutputStream out, Object v) {
        if (v instanceof Long n) { if (n >= 0) head(out, 0, n); else head(out, 1, -1 - n); }
        else if (v instanceof Integer n) writeCbor(out, (long) n);
        else if (v instanceof byte[] b) { head(out, 2, b.length); out.writeBytes(b); }
        else if (v instanceof String s) { byte[] b = s.getBytes(StandardCharsets.UTF_8); head(out, 3, b.length); out.writeBytes(b); }
        else if (v instanceof Map<?, ?> m) { head(out, 5, m.size()); for (var e : ((Map<Object, Object>) m).entrySet()) { writeCbor(out, e.getKey()); writeCbor(out, e.getValue()); } }
        else throw new IllegalArgumentException("unsupported");
    }
    static byte[] fixed(byte[] v) { byte[] out = new byte[32]; int copy = Math.min(32, v.length); System.arraycopy(v, v.length - copy, out, 32 - copy, copy); return out; }
    static byte[] authData(String rp, int flags, long count, byte[] credId, byte[] cose) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(WebAuthn.sha256(rp.getBytes(StandardCharsets.UTF_8))); out.write(flags);
        out.write((int) (count >> 24)); out.write((int) (count >> 16) & 0xff); out.write((int) (count >> 8) & 0xff); out.write((int) count & 0xff);
        if (credId != null) { out.writeBytes(new byte[16]); out.write(credId.length >> 8); out.write(credId.length & 0xff); out.writeBytes(credId); out.writeBytes(cose); }
        return out.toByteArray();
    }
    static byte[] clientData(String type, byte[] challenge, String origin) {
        return json(SimplexAdminSuite.appMap("type", type, "challenge", WebAuthn.b64(challenge), "origin", origin, "crossOrigin", false)).getBytes(StandardCharsets.UTF_8);
    }
    record Authenticator(KeyPair pair, byte[] id, String alg) {
        byte[] cose() {
            if (alg.equals("ES256")) {
                ECPublicKey k = (ECPublicKey) pair.getPublic();
                Map<Object, Object> m = new LinkedHashMap<>(); m.put(1L, 2L); m.put(3L, -7L); m.put(-1L, 1L);
                m.put(-2L, fixed(k.getW().getAffineX().toByteArray())); m.put(-3L, fixed(k.getW().getAffineY().toByteArray())); return cbor(m);
            }
            byte[] spki = pair.getPublic().getEncoded(); byte[] x = Arrays.copyOfRange(spki, spki.length - 32, spki.length);
            Map<Object, Object> m = new LinkedHashMap<>(); m.put(1L, 1L); m.put(3L, -8L); m.put(-1L, 6L); m.put(-2L, x); return cbor(m);
        }
        byte[] attestation(String rp, byte[] challenge) { Map<Object, Object> m = new LinkedHashMap<>(); m.put("fmt", "none"); m.put("attStmt", new LinkedHashMap<>()); m.put("authData", authData(rp, 0x45, 0, id, cose())); return cbor(m); }
        byte[] sign(byte[] auth, byte[] client) throws Exception {
            Signature s = Signature.getInstance(alg.equals("ES256") ? "SHA256withECDSA" : "Ed25519"); s.initSign(pair.getPrivate()); s.update(auth); s.update(WebAuthn.sha256(client)); return s.sign();
        }
    }
    static Authenticator authenticator(String alg) throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance(alg.equals("ES256") ? "EC" : "Ed25519");
        if (alg.equals("ES256")) g.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
        byte[] id = new byte[32]; new SecureRandom().nextBytes(id); return new Authenticator(g.generateKeyPair(), id, alg);
    }

    static void webauthn() throws Exception {
        String rp = "admin.example.test", origin = "https://admin.example.test";
        for (String alg : List.of("ES256", "EdDSA")) {
            Authenticator a = authenticator(alg); byte[] challenge = new byte[32]; new SecureRandom().nextBytes(challenge);
            WebAuthn.Credential c = WebAuthn.register(clientData("webauthn.create", challenge, origin), a.attestation(rp, challenge), challenge, rp);
            check(Arrays.equals(c.id(), a.id()) && c.alg() == (alg.equals("ES256") ? -7 : -8), alg + " registration parsed");
            fails(() -> WebAuthn.register(clientData("webauthn.create", challenge, "https://evil.test"), a.attestation(rp, challenge), challenge, rp), alg + " foreign origin");
            fails(() -> WebAuthn.register(clientData("webauthn.create", new byte[32], origin), a.attestation(rp, challenge), challenge, rp), alg + " wrong challenge");
            fails(() -> WebAuthn.register(clientData("webauthn.create", challenge, origin), a.attestation("other.test", challenge), challenge, rp), alg + " wrong rp id hash");
            byte[] login = new byte[32]; new SecureRandom().nextBytes(login);
            byte[] client = clientData("webauthn.get", login, origin), auth = authData(rp, 0x05, 7, null, null), sig = a.sign(auth, client);
            check(WebAuthn.verify(a.id(), client, auth, sig, null, login, rp, c.publicKey(), c.alg(), 0).signCount() == 7, alg + " assertion verified");
            fails(() -> WebAuthn.verify(a.id(), client, auth, sig, null, login, rp, c.publicKey(), c.alg(), 7), alg + " counter replay");
            byte[] tampered = auth.clone(); tampered[36] = 9;
            fails(() -> WebAuthn.verify(a.id(), client, tampered, sig, null, login, rp, c.publicKey(), c.alg(), 0), alg + " tampered data");
            byte[] noUv = authData(rp, 0x01, 8, null, null);
            fails(() -> WebAuthn.verify(a.id(), client, noUv, a.sign(noUv, client), null, login, rp, c.publicKey(), c.alg(), 0), alg + " user verification required");
        }
        fails(() -> WebAuthn.Cbor.decode(new byte[]{(byte) 0x5f}, 0), "indefinite CBOR rejected");
    }

    // ------------------------------------------------------------------ HTTPS integration

    static Object empty(Class<?> t) { return AppApiIntegrationHarness.empty(t); }
    static <T> T proxy(Class<T> type, InvocationHandler h) { return AppApiIntegrationHarness.proxy(type, h); }
    static HttpResponse<String> send(HttpRequest.Builder b) throws Exception { return client.send(b.timeout(java.time.Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString()); }
    static HttpRequest.Builder req(String path) { return HttpRequest.newBuilder(base.resolve(path)); }
    static HttpResponse<String> app(String path, String token, Object body) throws Exception {
        var b = req(path).header("X-Simplex-Device", "model=iPhone18%2C1;os=iOS%2026.1;app=4.1.0");
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (body != null) b.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json(body)));
        return send(b);
    }
    static HttpResponse<String> browser(String path, String cookie, Object body, String origin) throws Exception {
        var b = req(path).header("X-Simplex-Web", "1").header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15");
        if (cookie != null) b.header("Cookie", cookie);
        if (origin != null) b.header("Origin", origin);
        if (body != null) b.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json(body)));
        return send(b);
    }
    static String cookie(HttpResponse<String> r) {
        return r.headers().allValues("Set-Cookie").stream().filter(v -> v.startsWith(WebPanel.COOKIE + "=sasu_")).map(v -> v.substring(0, v.indexOf(';'))).findFirst().orElse(null);
    }
    static String code(String secret) {
        long step = System.currentTimeMillis() / 30_000; return AccountCrypto.code(AccountCrypto.totp(AccountCrypto.unbase32(secret), step));
    }

    @SuppressWarnings("unchecked")
    static void http() throws Exception {
        System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
        System.setProperty("jdk.httpclient.allowRestrictedHeaders", "host");
        Path dir = Files.createTempDirectory("suite980-http-"); Field serverField = Bukkit.class.getDeclaredField("server"); serverField.setAccessible(true); Object old = serverField.get(null); AppApiManager api = null;
        List<String> consoleOutput = new CopyOnWriteArrayList<>();
        try {
            BukkitTask task = proxy(BukkitTask.class, (p, m, a) -> empty(m.getReturnType()));
            BukkitScheduler scheduler = proxy(BukkitScheduler.class, (p, m, a) -> { if (a != null) for (Object item : a) { if (item instanceof Runnable work) work.run(); else if (item instanceof Consumer<?> consumer) ((Consumer<BukkitTask>) consumer).accept(task); } return m.getReturnType() == BukkitTask.class ? task : empty(m.getReturnType()); });
            var console = proxy(org.bukkit.command.ConsoleCommandSender.class, (p, m, a) -> switch (m.getName()) { case "getName" -> "Console"; case "hasPermission", "isOp", "isPermissionSet" -> true; case "sendMessage" -> { if (a != null && a[0] instanceof String s) consoleOutput.add(s); yield null; } default -> empty(m.getReturnType()); });
            var player = proxy(org.bukkit.entity.Player.class, (p, m, a) -> switch (m.getName()) { case "getName" -> "OpPlayer"; case "hasPermission", "isOp", "isPermissionSet" -> true; case "sendMessage" -> { if (a != null && a[0] instanceof String s) consoleOutput.add("PLAYER:" + s); yield null; } case "getUniqueId" -> UUID.nameUUIDFromBytes(new byte[]{1}); default -> empty(m.getReturnType()); });
            org.bukkit.plugin.PluginManager plugins = proxy(org.bukkit.plugin.PluginManager.class, (p, m, a) -> m.getName().equals("getPlugins") ? new org.bukkit.plugin.Plugin[0] : empty(m.getReturnType()));
            Server server = proxy(Server.class, (p, m, a) -> switch (m.getName()) { case "getName", "getVersion", "getBukkitVersion", "getMinecraftVersion" -> "API Fixture"; case "getWorlds" -> List.of(); case "getPluginManager" -> plugins; case "getScheduler" -> scheduler; case "getConsoleSender" -> console; case "getOnlinePlayers" -> List.of(); case "isPrimaryThread" -> true; case "getLogger" -> java.util.logging.Logger.getLogger("Harness980"); default -> empty(m.getReturnType()); });
            serverField.set(null, server);
            SimplexAdminSuite plugin = Moderation790Harness.plugin(dir); Moderation790Harness.set(plugin, JavaPlugin.class, "isEnabled", true);
            Moderation790Harness.set(plugin, SimplexAdminSuite.class, "adminActor", new ThreadLocal<String>()); Moderation790Harness.set(plugin, SimplexAdminSuite.class, "pendingInputs", new ConcurrentHashMap<>()); Moderation790Harness.set(plugin, SimplexAdminSuite.class, "pendingActions", new ConcurrentHashMap<>());
            Moderation790Harness.set(plugin, SimplexAdminSuite.class, "auditedConfig", new HashMap<String, String>()); plugin.moderationControl = new ModerationControl(plugin);
            plugin.adminAccess = new AdminAccessManager(plugin); plugin.adminUpdate = new AdminUpdateMenu(plugin);
            int port; try (var socket = new java.net.ServerSocket(0)) { port = socket.getLocalPort(); }
            plugin.getConfig().set("app-api.enabled", true); plugin.getConfig().set("app-api.bind", "127.0.0.1"); plugin.getConfig().set("app-api.port", port); plugin.getConfig().set("app-api.transport", "tls");
            api = new AppApiManager(plugin); plugin.appApi = api; api.reload();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30); while (!api.active() && System.nanoTime() < deadline) Thread.sleep(50); check(api.active(), "TLS listener started");
            String pin = (String) Moderation790Harness.get(api, "fingerprint");
            SSLContext ssl = SSLContext.getInstance("TLS"); ssl.init(null, new TrustManager[]{new X509TrustManager() { public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; } public void checkClientTrusted(X509Certificate[] c, String a) { } public void checkServerTrusted(X509Certificate[] c, String a) throws java.security.cert.CertificateException { try { if (!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(c[0].getEncoded())).equals(pin)) throw new java.security.cert.CertificateException("pin"); } catch (GeneralSecurityException e) { throw new java.security.cert.CertificateException(e); } } }}, new SecureRandom());
            client = HttpClient.newBuilder().sslContext(ssl).connectTimeout(java.time.Duration.ofSeconds(5)).build(); host = "127.0.0.1:" + port; base = URI.create("https://" + host);
            String self = "https://" + host;

            // Web panel shell, assets and headers on the same port.
            var index = send(req("/"));
            check(index.statusCode() == 200 && index.body().contains("<div id=\"app\"") && !index.body().contains("{{VERSION}}"), "web panel served on API port with versioned assets");
            String csp = index.headers().firstValue("Content-Security-Policy").orElse("");
            check(csp.contains("script-src 'self'") && csp.contains("frame-ancestors 'none'") && !csp.contains("unsafe-inline"), "strict CSP without inline scripts");
            check(index.headers().firstValue("X-Frame-Options").orElse("").equals("DENY"), "clickjacking protection");
            for (String asset : List.of("/assets/app.js", "/assets/app.css", "/assets/qr.js", "/assets/icon.svg", "/manifest.webmanifest", "/apple-touch-icon.png"))
                check(send(req(asset)).statusCode() == 200, "asset served: " + asset);
            check(send(req("/assets/../config.yml")).statusCode() != 200 && send(req("/assets/nope.js")).statusCode() == 404, "no path traversal, unknown assets 404");
            var config = obj(send(req("/v1/web/config")).body());
            check(Boolean.FALSE.equals(config.get("passkeys")) && !String.valueOf(config.get("version")).isBlank(), "public web config without domain");

            // Migration of the classic main key into the main admin account.
            var full = api.security.issue("Robin iPhone", "full"); var read = api.security.issue("Leser", "read");
            var health = obj(app("/v1/health", full.token(), null).body());
            check(((Map<String, Object>) health.get("account")).get("canMigrate").equals(true) && ((List<?>) health.get("features")).contains("web-panel-v1"), "health advertises migration and web panel");
            check(app("/v1/account/migrate", read.token(), Map.of("username", "robin", "password", "owner-password-980")).statusCode() == 403, "read key cannot migrate");
            check(app("/v1/account/migrate", full.token(), Map.of("username", "robin", "password", "short")).statusCode() == 400, "migration enforces password rules");
            var migrated = app("/v1/account/migrate", full.token(), Map.of("username", "Robin", "password", "owner-password-980"));
            var m = obj(migrated.body()); String ownerToken = String.valueOf(m.get("token"));
            check(migrated.statusCode() == 200 && ownerToken.startsWith("sasu_") && Boolean.TRUE.equals(m.get("owner")), "main key migrated to owner account");
            check(app("/v1/account/migrate", full.token(), Map.of("username", "second", "password", "owner-password-980")).statusCode() == 400, "second migration refused");
            check(app("/v1/health", full.token(), null).statusCode() == 200, "old key keeps working");
            var account = obj(app("/v1/account", ownerToken, null).body());
            check(account.get("kind").equals("user") && Boolean.TRUE.equals(account.get("owner")) && Boolean.TRUE.equals(account.get("manager")), "account reports main admin");
            var sessions = obj(app("/v1/sessions", ownerToken, null).body());
            var mine = ((List<Map<String, Object>>) sessions.get("items")).get(0);
            check("iPhone 17 Pro".equals(mine.get("model")) && "4.1.0".equals(mine.get("app")) && "127.0.0.1".equals(mine.get("ip")) && Boolean.TRUE.equals(mine.get("current")), "app device details recorded");
            check(((List<?>) sessions.get("keys")).size() == 2, "main admin sees API keys as devices");

            // 2FA via API, then two-step login.
            var begin = obj(app("/v1/account/totp/begin", ownerToken, Map.of()).body()); String secret = String.valueOf(begin.get("secret"));
            check(String.valueOf(begin.get("uri")).startsWith("otpauth://totp/"), "TOTP setup returns otpauth URI");
            check(app("/v1/account/totp/enable", ownerToken, Map.of("code", "000000")).statusCode() == 400 || !code(secret).equals("000000"), "wrong code rejected while enabling");
            var enabled = obj(app("/v1/account/totp/enable", ownerToken, Map.of("code", code(secret))).body());
            List<String> recovery = (List<String>) enabled.get("recovery");
            check(recovery != null && recovery.size() == 10, "recovery codes shown once");
            var first = obj(app("/v1/login", null, Map.of("username", "robin", "password", "owner-password-980")).body());
            check(Boolean.TRUE.equals(first.get("mfa")) && first.get("token") == null && ((List<?>) first.get("methods")).contains("totp"), "password alone no longer issues a token");
            check(app("/v1/login/verify", null, Map.of("challenge", first.get("challenge"), "code", "123456")).statusCode() == 401, "wrong second factor rejected");
            var second = app("/v1/login/verify", null, Map.of("challenge", first.get("challenge"), "code", code(secret)));
            check(second.statusCode() == 200 && String.valueOf(obj(second.body()).get("token")).startsWith("sasu_"), "TOTP completes login");
            var viaRecovery = obj(app("/v1/login", null, Map.of("username", "robin", "password", "owner-password-980")).body());
            check(app("/v1/login/verify", null, Map.of("challenge", viaRecovery.get("challenge"), "code", recovery.get(0))).statusCode() == 200, "recovery code completes login");
            var again = obj(app("/v1/login", null, Map.of("username", "robin", "password", "owner-password-980")).body());
            check(app("/v1/login/verify", null, Map.of("challenge", again.get("challenge"), "code", recovery.get(0))).statusCode() == 401, "used recovery code rejected");

            // Browser login with cookie, CSRF and same-origin protection.
            var webFirst = obj(browser("/v1/login", null, Map.of("username", "robin", "password", "owner-password-980", "web", true, "remember", true), self).body());
            Thread.sleep(Math.max(0, 30_100 - System.currentTimeMillis() % 30_000)); // next TOTP step; the previous code is spent
            var webLogin = browser("/v1/login/verify", null, Map.of("challenge", webFirst.get("challenge"), "code", code(secret)), self);
            String cookie = cookie(webLogin);
            String setCookie = webLogin.headers().allValues("Set-Cookie").toString();
            check(webLogin.statusCode() == 200 && cookie != null && !webLogin.body().contains("sasu_"), "browser receives cookie, never the token");
            check(setCookie.contains("HttpOnly") && setCookie.contains("Secure") && setCookie.contains("SameSite=Strict") && setCookie.contains("Max-Age="), "hardened persistent cookie");
            check(browser("/v1/account", cookie, null, null).statusCode() == 200, "cookie session reads account");
            check(send(req("/v1/account").header("Cookie", cookie)).statusCode() == 401, "cookie ignored without web header");
            check(browser("/v1/account", cookie, null, "https://evil.example").statusCode() == 403, "foreign origin rejected");
            check(browser("/v1/account/password", cookie, Map.of("current", "x", "next", "y"), null).statusCode() == 403, "state change without Origin rejected");
            check(send(req("/v1/health").header("Authorization", "Bearer " + full.token()).header("Origin", "https://browser.example")).statusCode() == 403, "bearer from foreign browser rejected");
            var webSessions = obj(browser("/v1/sessions", cookie, null, null).body());
            Map<String, Object> webRow = ((List<Map<String, Object>>) webSessions.get("items")).stream().filter(r -> "web".equals(r.get("kind"))).findFirst().orElseThrow();
            check("Safari".equals(webRow.get("browser")) && "macOS".equals(webRow.get("os")), "browser described from user agent");

            // Device management by the main admin.
            check(app("/v1/sessions/update", ownerToken, Map.of("id", webRow.get("id"), "operation", "lock")).statusCode() == 200, "owner locks browser session");
            var lockedReply = browser("/v1/account", cookie, null, null);
            check(lockedReply.statusCode() == 423 && lockedReply.body().contains("gesperrt"), "locked device receives 423");
            check(app("/v1/sessions/update", ownerToken, Map.of("id", webRow.get("id"), "operation", "unlock")).statusCode() == 200 && browser("/v1/account", cookie, null, null).statusCode() == 200, "unlock restores access");
            check(app("/v1/sessions/update", ownerToken, Map.of("id", webRow.get("id"), "operation", "rename", "name", "Büro-Mac")).statusCode() == 200, "rename device");
            check(app("/v1/sessions/update", ownerToken, Map.of("id", mine.get("id"), "operation", "lock")).statusCode() == 400, "current device cannot lock itself");
            check(app("/v1/sessions/update", ownerToken, Map.of("id", read.key().id(), "kind", "key", "operation", "lock")).statusCode() == 200 && app("/v1/health", read.token(), null).statusCode() == 423, "API key can be locked");
            check(app("/v1/sessions/update", ownerToken, Map.of("id", read.key().id(), "kind", "key", "operation", "unlock")).statusCode() == 200 && app("/v1/health", read.token(), null).statusCode() == 200, "API key unlocked");
            api.security.users.save("helper", "helper-password-980", Set.of("data.players"), true);
            String helper = String.valueOf(obj(app("/v1/login", null, Map.of("username", "helper", "password", "helper-password-980")).body()).get("token"));
            var helperSessions = obj(app("/v1/sessions", helper, null).body());
            check(Boolean.FALSE.equals(helperSessions.get("manage")) && ((List<?>) helperSessions.get("items")).size() == 1 && ((List<?>) helperSessions.get("keys")).isEmpty(), "regular user sees only own devices");
            check(app("/v1/sessions/update", helper, Map.of("id", webRow.get("id"), "operation", "logout")).statusCode() == 403, "regular user cannot sign out others");
            check(app("/v1/web/domain", helper, Map.of("domain", "evil.example")).statusCode() == 403, "only main admin sets the domain");
            check(app("/v1/sessions/update", ownerToken, Map.of("id", webRow.get("id"), "operation", "logout")).statusCode() == 200 && browser("/v1/account", cookie, null, null).statusCode() == 401, "owner signs out browser");

            // Passkeys on the configured domain.
            check(app("/v1/web/domain", ownerToken, Map.of("domain", "admin.example.test")).statusCode() == 200, "owner sets domain");
            String origin = "https://admin.example.test";
            var regBegin = obj(app("/v1/account/passkey/begin", ownerToken, Map.of()).body());
            Map<String, Object> pk = (Map<String, Object>) regBegin.get("publicKey");
            check(((Map<String, Object>) pk.get("rp")).get("id").equals("admin.example.test"), "registration options carry rp id");
            Authenticator device = authenticator("ES256"); byte[] regChallenge = WebAuthn.unb64(String.valueOf(pk.get("challenge")));
            var registered = app("/v1/account/passkey/finish", ownerToken, Map.of("id", regBegin.get("id"), "name", "iCloud-Schlüsselbund", "credential", Map.of("id", WebAuthn.b64(device.id()),
                "response", Map.of("clientDataJSON", WebAuthn.b64(clientData("webauthn.create", regChallenge, origin)), "attestationObject", WebAuthn.b64(device.attestation("admin.example.test", regChallenge))))));
            check(registered.statusCode() == 200, "passkey registered over HTTPS API: " + registered.body());
            var loginBegin = obj(browser("/v1/passkey/begin", null, Map.of(), origin).body());
            byte[] loginChallenge = WebAuthn.unb64(String.valueOf(((Map<String, Object>) loginBegin.get("publicKey")).get("challenge")));
            byte[] client = clientData("webauthn.get", loginChallenge, origin), auth = authData("admin.example.test", 0x05, 1, null, null);
            String handle = api.security.users.handle("robin");
            var passkeyLogin = browser("/v1/passkey/finish", null, Map.of("id", loginBegin.get("id"), "web", true, "credential", Map.of("id", WebAuthn.b64(device.id()), "response", Map.of(
                "clientDataJSON", WebAuthn.b64(client), "authenticatorData", WebAuthn.b64(auth), "signature", WebAuthn.b64(device.sign(auth, client)), "userHandle", handle))), origin);
            check(passkeyLogin.statusCode() == 200 && cookie(passkeyLogin) != null, "passwordless passkey login in browser: " + passkeyLogin.body());
            check(browser("/v1/passkey/finish", null, Map.of("id", loginBegin.get("id"), "web", true, "credential", Map.of("id", WebAuthn.b64(device.id()), "response", Map.of(
                "clientDataJSON", WebAuthn.b64(client), "authenticatorData", WebAuthn.b64(auth), "signature", WebAuthn.b64(device.sign(auth, client))))), origin).statusCode() == 401, "passkey challenge single use");
            check(((List<?>) obj(app("/v1/account", ownerToken, null).body()).get("passkeys")).size() == 1, "passkey listed");

            // App → browser hand-off (used by the app to add passkeys) and browser → app exchange.
            var handoff = obj(app("/v1/account/handoff", ownerToken, Map.of()).body());
            var opened = browser("/v1/login/exchange", null, Map.of("code", handoff.get("code"), "web", true), origin);
            check(opened.statusCode() == 200 && cookie(opened) != null && !opened.headers().allValues("Set-Cookie").toString().contains("Max-Age"), "hand-off opens short browser session");
            check(browser("/v1/login/exchange", null, Map.of("code", handoff.get("code"), "web", true), origin).statusCode() == 401, "hand-off code single use");

            // Main admin protection and console-only emergency reset.
            api.security.users.save("deputy", "deputy-password-980", Set.of("*"), true);
            String deputy = String.valueOf(obj(app("/v1/login", null, Map.of("username", "deputy", "password", "deputy-password-980")).body()).get("token"));
            var deletePlan = app("/v1/prepare", deputy, Map.of("path", "accounts.update", "values", Map.of("username", "robin", "operation", "delete", "enabled", "true", "permissions", "", "password", "")));
            String deleteTicket = String.valueOf(obj(deletePlan.body()).get("ticket"));
            check(app("/v1/execute", deputy, Map.of("ticket", deleteTicket)).statusCode() == 403 && api.security.users.owner("robin"), "other admins cannot delete the main admin");
            api.command(player, new String[]{"owner-reset"});
            check(consoleOutput.stream().anyMatch(s -> s.startsWith("PLAYER:") && s.contains("Serverkonsole")) && api.security.users.verifyPassword("robin", "owner-password-980") != null, "in-game operator cannot trigger the reset");
            consoleOutput.clear(); api.command(console, new String[]{"owner-reset"});
            String password = consoleOutput.stream().filter(s -> s.contains("Einmal-Passwort")).map(s -> s.substring(s.lastIndexOf('§') + 2).trim()).findFirst().orElse("");
            check(consoleOutput.stream().anyMatch(s -> s.contains("robin")) && password.length() == 23, "console shows user name and one-time password");
            check(app("/v1/health", ownerToken, null).statusCode() == 401 && app("/v1/health", full.token(), null).statusCode() == 200, "reset signs out main admin only");
            var resetLogin = obj(app("/v1/login", null, Map.of("username", "robin", "password", password)).body());
            check(Boolean.TRUE.equals(resetLogin.get("passwordChange")) && resetLogin.get("token") == null, "one-time password requires a new password");
            check(app("/v1/login/verify", null, Map.of("challenge", resetLogin.get("challenge"), "newPassword", "short")).statusCode() == 400, "weak new password rejected");
            var changed = obj(app("/v1/login/verify", null, Map.of("challenge", resetLogin.get("challenge"), "newPassword", "after-reset-password-980")).body());
            check(String.valueOf(changed.get("token")).startsWith("sasu_"), "new password completes login without old 2FA");
            check(((List<?>) obj(app("/v1/account", String.valueOf(changed.get("token")), null).body()).get("passkeys")).isEmpty(), "passkeys removed by reset");
            check(app("/v1/login", null, Map.of("username", "robin", "password", password)).statusCode() == 401, "one-time password no longer valid");

            api.stop(); check(!api.active(), "listener stops");
        } finally {
            if (api != null) api.stop(); serverField.set(null, old);
            try (var stream = Files.walk(dir)) { for (Path p : stream.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); }
        }
    }
}
