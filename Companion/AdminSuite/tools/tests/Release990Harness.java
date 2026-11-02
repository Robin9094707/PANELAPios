package eu.simplexsmp.adminsuite;

import java.lang.reflect.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.*;
import org.bukkit.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.*;

/**
 * 9.9.0: browser push (RFC 8291 encryption decrypted by an independent receiver, VAPID JWT signature, push-service
 * allow-list), admin-log unread filters, username change with every session kept, public staff name, console-only
 * recovery commands, LuckPerms command validation, Lands access mapping, web-panel appearance and the service worker –
 * against the production HTTPS listener with synthetic Bukkit services (same fixture as Release980Harness).
 */
public final class Release990Harness {
    static int checks;
    static void check(boolean value, String reason) { checks++; if (!value) throw new AssertionError(reason); }
    static void fails(Callable<?> work, String reason) { try { work.call(); } catch (Exception | AssertionError e) { checks++; return; } throw new AssertionError("Expected failure: " + reason); }
    @SuppressWarnings("unchecked") static Map<String, Object> obj(String json) { return (Map<String, Object>) DiscordBotManager.Json.parse(json); }
    static String json(Object v) { return DiscordBotManager.Json.stringify(v); }
    static HttpClient client; static URI base; static String host;

    public static void main(String[] args) throws Exception {
        push();
        luckPerms();
        filters();
        accounts();
        http();
        System.out.println("PASS " + checks + " 9.9.0 push, admin-log filter, rename, recovery, LuckPerms, Lands and web panel checks");
    }

    // ------------------------------------------------------------------ browser push

    /** RFC 8291 receiver: what Chrome, Firefox and Safari do with the body the server sends. */
    static byte[] decrypt(byte[] body, KeyPair receiver, byte[] auth) throws Exception {
        ByteBuffer in = ByteBuffer.wrap(body);
        byte[] salt = new byte[16]; in.get(salt); int rs = in.getInt(); int idlen = in.get() & 0xff; byte[] senderPublic = new byte[idlen]; in.get(senderPublic);
        byte[] record = new byte[in.remaining()]; in.get(record);
        check(rs == 4096 && idlen == 65, "aes128gcm header (rs 4096, 65-byte key id)");
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH"); agreement.init(receiver.getPrivate()); agreement.doPhase(WebPush.decodePoint(senderPublic), true);
        byte[] uaPublic = WebPush.point((ECPublicKey) receiver.getPublic());
        byte[] ikm = WebPush.expand(WebPush.hmac(auth, agreement.generateSecret()), WebPush.concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), uaPublic, senderPublic), 32);
        byte[] prk = WebPush.hmac(salt, ikm);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(WebPush.expand(prk, "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), 16), "AES"),
            new GCMParameterSpec(128, WebPush.expand(prk, "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), 12)));
        byte[] plain = cipher.doFinal(record);
        check(plain[plain.length - 1] == 2, "last-record padding delimiter");
        return Arrays.copyOf(plain, plain.length - 1);
    }

    static void push() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC"); generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair browser = generator.generateKeyPair(); byte[] auth = new byte[16]; new SecureRandom().nextBytes(auth);
        byte[] payload = "{\"title\":\"Neues Ticket #13 · Ä 🚀\",\"body\":\"Test\"}".getBytes(StandardCharsets.UTF_8);
        byte[] body = WebPush.encrypt(payload, WebPush.point((ECPublicKey) browser.getPublic()), auth, new SecureRandom());
        check(Arrays.equals(decrypt(body, browser, auth), payload), "RFC 8291 payload decrypts at the browser");
        byte[] other = WebPush.encrypt(payload, WebPush.point((ECPublicKey) browser.getPublic()), auth, new SecureRandom());
        check(!Arrays.equals(body, other), "fresh salt and key per message");
        byte[] wrongAuth = auth.clone(); wrongAuth[0] ^= 1;
        fails(() -> decrypt(body, browser, wrongAuth), "wrong auth secret cannot decrypt");
        fails(() -> WebPush.encrypt(new byte[4000], WebPush.point((ECPublicKey) browser.getPublic()), auth, new SecureRandom()), "oversize payload refused");
        fails(() -> WebPush.decodePoint(new byte[64]), "malformed browser key refused");

        for (String ok : List.of("https://fcm.googleapis.com/fcm/send/abc", "https://updates.push.services.mozilla.com/wpush/v2/x", "https://web.push.apple.com/QF8", "https://wns2-am3p.notify.windows.com/w/?token=1"))
            WebPush.checkEndpoint(ok);
        checks++;
        for (String bad : List.of("http://fcm.googleapis.com/x", "https://example.com/push", "https://evil.com/.googleapis.com", "https://user@fcm.googleapis.com/x", "https://fcm.googleapis.com:8443/x", "https://169.254.169.254/latest", "https://localhost/x"))
            fails(() -> { WebPush.checkEndpoint(bad); return null; }, "push endpoint rejected: " + bad);

        Path file = Files.createTempDirectory("suite990-push-").resolve("web-push.properties");
        WebPush push = new WebPush(file, () -> "https://admin.example.test", (o, t) -> true, null);
        String publicKey = push.publicKey();
        check(WebAuthn.unb64(publicKey).length == 65 && WebAuthn.unb64(publicKey)[0] == 4, "VAPID key is an uncompressed P-256 point");
        String jwt = push.jwt("https://fcm.googleapis.com");
        String[] parts = jwt.split("\\.");
        check(parts.length == 3, "VAPID JWT has three parts");
        Map<String, Object> claims = obj(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
        check("https://fcm.googleapis.com".equals(claims.get("aud")) && "https://admin.example.test".equals(claims.get("sub")) && ((Number) claims.get("exp")).longValue() > System.currentTimeMillis() / 1000, "VAPID claims");
        Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format"); verifier.initVerify(WebPush.decodePoint(WebAuthn.unb64(publicKey)));
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        check(verifier.verify(Base64.getUrlDecoder().decode(parts[2])), "VAPID JWT signed with the published key (ES256)");
        check(new WebPush(file, () -> "x", (o, t) -> true, null).publicKey().equals(publicKey), "VAPID key survives restarts");

        String p256 = WebAuthn.b64(WebPush.point((ECPublicKey) browser.getPublic())), secret = WebAuthn.b64(auth);
        Map<String, Object> sub = new HashMap<>(Map.of("endpoint", "https://fcm.googleapis.com/fcm/send/device-1", "keys", Map.of("p256dh", p256, "auth", secret)));
        var saved = push.subscribe("user:cm9iaW4", sub, Map.of("browser", "Safari", "os", "iOS"));
        check(Boolean.TRUE.equals(saved.get("ok")) && ((List<?>) saved.get("events")).containsAll(List.of("ticket", "admin-log")), "subscription stored with default events");
        var listed = push.list("user:cm9iaW4");
        check(listed.size() == 1 && !listed.get(0).containsKey("endpoint") && !listed.get(0).containsKey("auth") && !listed.get(0).containsKey("p256dh") && "Google (Chrome/Edge/Android)".equals(listed.get(0).get("service")), "device list hides push secrets");
        check(push.list("user:b3RoZXI").isEmpty(), "subscriptions are per account");
        fails(() -> push.update("user:b3RoZXI", String.valueOf(listed.get(0).get("id")), Map.of("events", List.of("ticket")), false), "other accounts cannot change a device");
        push.update("user:cm9iaW4", String.valueOf(listed.get(0).get("id")), Map.of("events", List.of("staff-chat")), false);
        check(push.list("user:cm9iaW4").get(0).get("events").equals(List.of("staff-chat")), "event selection saved");
        fails(() -> push.update("user:cm9iaW4", String.valueOf(listed.get(0).get("id")), Map.of("events", List.of("nope")), false), "unknown event refused");
        fails(() -> push.subscribe("user:cm9iaW4", Map.of("endpoint", "https://fcm.googleapis.com/x", "keys", Map.of("p256dh", "AAAA", "auth", secret)), Map.of()), "invalid browser key refused");
        push.renameOwner("user:cm9iaW4", "user:bmV3");
        check(push.list("user:cm9iaW4").isEmpty() && push.list("user:bmV3").size() == 1, "push devices follow a username change");
        push.unsubscribe("user:bmV3", "https://fcm.googleapis.com/fcm/send/device-1");
        check(push.count() == 0, "unsubscribe removes the device");
        check(WebPush.url("ticket-urgent").equals("/#/tickets") && WebPush.url("admin-log").equals("/#/admin-log"), "notification click targets");
    }

    // ------------------------------------------------------------------ LuckPerms & Lands

    static Map<String, Object> m(Object... pairs) { Map<String, Object> out = new LinkedHashMap<>(); for (int i = 0; i < pairs.length; i += 2) out.put(String.valueOf(pairs[i]), pairs[i + 1]); return out; }

    static void luckPerms() {
        check(LuckPermsAdmin.command(m("operation", "group-permission-set", "group", "Admin", "node", "essentials.fly", "value", "true")).equals("lp group admin permission set essentials.fly true"), "group permission command");
        check(LuckPermsAdmin.command(m("operation", "group-permission-temp", "group", "vip", "node", "kits.vip", "value", "false", "duration", "7d", "context", "world=farm")).equals("lp group vip permission settemp kits.vip false 7d world=farm"), "temporary permission with context");
        check(LuckPermsAdmin.command(m("operation", "user-parent-add", "player", "Alpha_Kraft", "group", "mod")).equals("lp user Alpha_Kraft parent add mod"), "user parent command");
        check(LuckPermsAdmin.command(m("operation", "group-prefix", "group", "admin", "value", "&c[Admin] ", "priority", "90")).equals("lp group admin meta setprefix 90 \"&c[Admin] \""), "prefix is quoted");
        check(LuckPermsAdmin.command(m("operation", "group-prefix", "group", "admin", "value", "")).equals("lp group admin meta removeprefix 100"), "empty prefix removes it");
        check(LuckPermsAdmin.command(m("operation", "user-promote", "player", "0b5f9a6e-4c3a-4f7a-9a59-2f0f7f2a8c11", "track", "staff")).equals("lp user 0b5f9a6e-4c3a-4f7a-9a59-2f0f7f2a8c11 promote staff"), "promotion by UUID");
        for (Map<String, Object> bad : List.of(
            m("operation", "group-permission-set", "group", "admin", "node", "a b", "value", "true"),
            m("operation", "group-permission-set", "group", "admin", "node", "x;op Alpha", "value", "true"),
            m("operation", "group-permission-set", "group", "ad min", "node", "x", "value", "true"),
            m("operation", "group-prefix", "group", "admin", "value", "\" ; op me"),
            m("operation", "group-prefix", "group", "admin", "value", "a\\b"),
            m("operation", "user-parent-add", "player", "bad name", "group", "admin"),
            m("operation", "group-delete", "group", "default"),
            m("operation", "group-permission-temp", "group", "admin", "node", "x", "duration", "forever"),
            m("operation", "group-permission-set", "group", "admin", "node", "x", "value", "maybe"),
            m("operation", "group-permission-set", "group", "admin", "node", "x", "context", "server=a b"),
            m("operation", "console", "group", "admin")))
            fails(() -> LuckPermsAdmin.command(bad), "invalid LuckPerms change refused: " + bad);
        check(LuckPermsAdmin.summary(m("operation", "sync")).contains("`/lp sync`"), "summary shows the exact command");
        var grant = AppApiManager.landsAccess(m("player", "Alpha_Kraft", "node", "lands.bypass.*", "operation", "grant"));
        check(LuckPermsAdmin.command(grant).equals("lp user Alpha_Kraft permission set lands.bypass.* true"), "Lands bypass grant");
        var revoke = AppApiManager.landsAccess(m("player", "Alpha_Kraft", "node", "lands.admin.*", "operation", "revoke"));
        check(LuckPermsAdmin.command(revoke).equals("lp user Alpha_Kraft permission unset lands.admin.*"), "Lands admin revoke");
        fails(() -> AppApiManager.landsAccess(m("player", "x", "node", "essentials.*", "operation", "grant")), "only Lands nodes via lands.access");
    }

    // ------------------------------------------------------------------ admin-log unread filter

    static Map<String, Object> row(long id, String admin, String source, String category, Boolean staff) { Map<String, Object> r = m("id", id, "admin", admin, "adminName", admin, "source", source, "category", category); if (staff != null) r.put("staff", staff); return r; }

    static void filters() {
        var app = row(1, "robin", "app", "bans", null); var console = row(2, "console", "console", "admin", null);
        var staffIngame = row(3, "alpha_kraft", "ingame", "vanish", true); var player = row(4, "steve", "ingame", "other", false); var view = row(5, "mia", "app", "view", null);
        var staff = AdminActivityLog.class; Set<String> names = Set.of();
        AdminActivityLog.Filter teamOnly = new AdminActivityLog.Filter("staff", Set.of(), Set.of(), false);
        check(AdminActivityLog.counts(app, "deputy", teamOnly, names) && AdminActivityLog.counts(console, "deputy", teamOnly, names) && AdminActivityLog.counts(staffIngame, "deputy", teamOnly, names), "team actions count by default");
        check(!AdminActivityLog.counts(player, "deputy", teamOnly, names), "player actions stay silent by default");
        check(AdminActivityLog.counts(player, "deputy", teamOnly, Set.of("steve")), "players recognised as team (LuckPerms/OP) count");
        check(!AdminActivityLog.counts(app, "robin", teamOnly, names), "own actions never count");
        check(!AdminActivityLog.counts(view, "deputy", teamOnly, names) && AdminActivityLog.counts(view, "deputy", new AdminActivityLog.Filter("staff", Set.of(), Set.of(), true), names), "views only with the views option");
        AdminActivityLog.Filter all = new AdminActivityLog.Filter("all", Set.of(), Set.of(), false);
        check(AdminActivityLog.counts(player, "deputy", all, names), "mode all counts players");
        AdminActivityLog.Filter selected = new AdminActivityLog.Filter("selected", Set.of("alpha_kraft"), Set.of(), false);
        check(AdminActivityLog.counts(staffIngame, "deputy", selected, names) && !AdminActivityLog.counts(app, "deputy", selected, names), "mode selected counts only chosen admins");
        AdminActivityLog.Filter none = new AdminActivityLog.Filter("none", Set.of(), Set.of(), true);
        check(!AdminActivityLog.counts(app, "deputy", none, names), "mode none counts nothing");
        AdminActivityLog.Filter bansOnly = new AdminActivityLog.Filter("all", Set.of(), Set.of("bans"), false);
        check(AdminActivityLog.counts(app, "deputy", bansOnly, names) && !AdminActivityLog.counts(console, "deputy", bansOnly, names), "category filter");
        check(AdminActivityLog.staff(new AdminActivityLog.Identity("x", "x", "discord"), names) && !AdminActivityLog.staff(new AdminActivityLog.Identity("system", "System", "system"), names), "identity sources");
        check(staff.getName().endsWith("AdminActivityLog") && AdminActivityLog.MODES.equals(List.of("staff", "all", "selected", "none")), "filter modes");

        check(TicketStore.scrubText("Simplex Panel/juhas (app:user:juhas:a80ec6cc57bbade9)", "Team").equals("Team"), "legacy app identity removed from ticket author");
        check(TicketStore.scrubText("Ticket geschlossen von Simplex Panel/robin (app:key_1)", "Team").equals("Ticket geschlossen von Team"), "legacy identity removed from system notes");
        check(TicketStore.scrubText("Alpha_Kraft", "Team").equals("Alpha_Kraft"), "normal names untouched");
        check(PanelHub.ownerKey("robin").equals("user:" + Base64.getUrlEncoder().withoutPadding().encodeToString("robin".getBytes(StandardCharsets.UTF_8))), "owner key matches notification owner format");
    }

    // ------------------------------------------------------------------ accounts: rename, public name, console recovery

    static void accounts() throws Exception {
        Path file = Files.createTempDirectory("suite990-users-").resolve("users.properties");
        AppUsers users = new AppUsers(file);
        users.createOwner("juhas", "owner-password-990");
        users.save("deputy", "deputy-password-990", Set.of("data.tickets"), true);
        String token = users.createSession("juhas", System.currentTimeMillis(), AppUsers.APP_SESSION, Map.of("kind", "app"));
        String setup = users.setupCode("deputy", 60, System.currentTimeMillis());
        users.enableTotp("juhas", AccountCrypto.newTotpSecret()); users.newRecovery("juhas");
        fails(() -> { users.rename("juhas", "deputy"); return null; }, "rename to an existing user refused");
        fails(() -> { users.rename("juhas", "Bad Name"); return null; }, "invalid new name refused");
        users.rename("juhas", "alpha_admin");
        check(!users.exists("juhas") && users.exists("alpha_admin") && users.owner("alpha_admin") && users.mfa("alpha_admin") && users.recoveryLeft("alpha_admin") == 10, "owner flag, 2FA and recovery codes moved");
        var key = users.authenticate(token);
        check(key != null && key.label().equals("alpha_admin") && users.valid(key), "signed-in devices stay signed in with the new name");
        check(users.verifyPassword("alpha_admin", "owner-password-990") != null && users.verifyPassword("juhas", "owner-password-990") == null, "password follows the new name");
        check(users.ownerName().equals("alpha_admin"), "main admin resolved by new name");
        users.rename("deputy", "helper");
        check(users.redeem(setup, System.currentTimeMillis()) == null, "pending setup links are cancelled by a rename");

        users.setPublicName("helper", "Support");
        check(users.publicName("helper").equals("Support") && users.list().stream().anyMatch(r -> "Support".equals(r.get("publicName"))), "public name stored and listed");
        fails(() -> { users.setPublicName("helper", "<script>"); return null; }, "public name validated");
        users.setPublicName("helper", ""); check(users.publicName("helper").isEmpty(), "public name cleared");

        String once = users.resetUser("helper");
        check(once.length() == 23 && users.mustChange("helper") && users.verifyPassword("helper", once) != null && users.sessions("helper") == 0, "user-reset sets a one-time password and signs out");
        fails(() -> { users.setEnabled("alpha_admin", false); return null; }, "main admin cannot be disabled");
        String previous = users.transferOwner("helper");
        check(previous.equals("alpha_admin") && users.owner("helper") && !users.owner("alpha_admin") && users.permissions("helper").contains("*"), "owner-transfer moves the main admin");
        users.setEnabled("alpha_admin", false); check(!users.enabled("alpha_admin") && users.authenticate(token) == null, "disabled user loses every session");
        users.setEnabled("alpha_admin", true); check(users.enabled("alpha_admin"), "user re-enabled");
    }

    // ------------------------------------------------------------------ HTTPS listener

    static Object empty(Class<?> t) { return AppApiIntegrationHarness.empty(t); }
    static <T> T proxy(Class<T> type, InvocationHandler h) { return AppApiIntegrationHarness.proxy(type, h); }
    static HttpResponse<String> send(HttpRequest.Builder b) throws Exception { return client.send(b.timeout(java.time.Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString()); }
    static HttpRequest.Builder req(String path) { return HttpRequest.newBuilder(base.resolve(path)); }
    static HttpResponse<String> app(String path, String token, Object body) throws Exception {
        var b = req(path).header("X-Simplex-Device", "model=iPhone18%2C1;os=iOS%2026.1;app=4.2.0");
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (body != null) b.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json(body)));
        return send(b);
    }

    @SuppressWarnings("unchecked")
    static void http() throws Exception {
        System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
        Path dir = Files.createTempDirectory("suite990-http-"); Field serverField = Bukkit.class.getDeclaredField("server"); serverField.setAccessible(true); Object old = serverField.get(null); AppApiManager api = null;
        List<String> consoleOutput = new CopyOnWriteArrayList<>();
        try {
            BukkitTask task = proxy(BukkitTask.class, (p, m, a) -> empty(m.getReturnType()));
            BukkitScheduler scheduler = proxy(BukkitScheduler.class, (p, m, a) -> { if (a != null) for (Object item : a) { if (item instanceof Runnable work) work.run(); else if (item instanceof Consumer<?> consumer) ((Consumer<BukkitTask>) consumer).accept(task); } return m.getReturnType() == BukkitTask.class ? task : empty(m.getReturnType()); });
            var console = proxy(org.bukkit.command.ConsoleCommandSender.class, (p, m, a) -> switch (m.getName()) { case "getName" -> "Console"; case "hasPermission", "isOp", "isPermissionSet" -> true; case "sendMessage" -> { if (a != null && a[0] instanceof String s) consoleOutput.add(s); yield null; } default -> empty(m.getReturnType()); });
            var player = proxy(org.bukkit.entity.Player.class, (p, m, a) -> switch (m.getName()) { case "getName" -> "OpPlayer"; case "hasPermission", "isOp", "isPermissionSet" -> true; case "sendMessage" -> { if (a != null && a[0] instanceof String s) consoleOutput.add("PLAYER:" + s); yield null; } default -> empty(m.getReturnType()); });
            org.bukkit.plugin.PluginManager plugins = proxy(org.bukkit.plugin.PluginManager.class, (p, m, a) -> m.getName().equals("getPlugins") ? new org.bukkit.plugin.Plugin[0] : empty(m.getReturnType()));
            Server server = proxy(Server.class, (p, m, a) -> switch (m.getName()) { case "getName", "getVersion", "getBukkitVersion", "getMinecraftVersion" -> "API Fixture"; case "getWorlds" -> List.of(); case "getPluginManager" -> plugins; case "getScheduler" -> scheduler; case "getConsoleSender" -> console; case "getLogger" -> java.util.logging.Logger.getLogger("Harness990"); case "isPrimaryThread" -> true; case "getOnlinePlayers" -> List.of(); case "getOperators" -> Set.of(); default -> empty(m.getReturnType()); });
            serverField.set(null, server);
            SimplexAdminSuite plugin = Moderation790Harness.plugin(dir); Moderation790Harness.set(plugin, JavaPlugin.class, "isEnabled", true);
            Moderation790Harness.set(plugin, SimplexAdminSuite.class, "adminActor", new ThreadLocal<String>()); Moderation790Harness.set(plugin, SimplexAdminSuite.class, "pendingInputs", new ConcurrentHashMap<>());
            Moderation790Harness.set(plugin, SimplexAdminSuite.class, "auditedConfig", new HashMap<String, String>()); plugin.moderationControl = new ModerationControl(plugin);
            plugin.adminAccess = new AdminAccessManager(plugin); plugin.adminUpdate = new AdminUpdateMenu(plugin);
            plugin.adminLog = new AdminActivityLog(plugin);
            int port; try (var socket = new java.net.ServerSocket(0)) { port = socket.getLocalPort(); }
            plugin.getConfig().set("app-api.enabled", true); plugin.getConfig().set("app-api.bind", "127.0.0.1"); plugin.getConfig().set("app-api.port", port); plugin.getConfig().set("app-api.transport", "tls");
            api = new AppApiManager(plugin); plugin.appApi = api; api.reload();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30); while (!api.active() && System.nanoTime() < deadline) Thread.sleep(50); check(api.active(), "TLS listener started");
            SSLContext ssl = SSLContext.getInstance("TLS"); ssl.init(null, new TrustManager[]{new X509TrustManager() { public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; } public void checkClientTrusted(X509Certificate[] c, String a) { } public void checkServerTrusted(X509Certificate[] c, String a) { } }}, new SecureRandom());
            client = HttpClient.newBuilder().sslContext(ssl).connectTimeout(java.time.Duration.ofSeconds(5)).build(); host = "127.0.0.1:" + port; base = URI.create("https://" + host);

            // Web panel: service worker, install icons and manifest.
            var worker = send(req("/sw.js"));
            check(worker.statusCode() == 200 && worker.body().contains("addEventListener(\"push\"") && !worker.body().contains("{{VERSION}}"), "service worker served with version");
            check(worker.headers().firstValue("Cache-Control").orElse("").equals("no-cache") && worker.headers().firstValue("Service-Worker-Allowed").orElse("").equals("/"), "service worker revalidated on every start");
            check(send(req("/")).headers().firstValue("Content-Security-Policy").orElse("").contains("worker-src 'self'"), "CSP allows the own service worker only");
            for (String asset : List.of("/assets/extras.js", "/assets/icon-192.png", "/assets/icon-maskable-512.png", "/assets/badge-96.png"))
                check(send(req(asset)).statusCode() == 200, "asset served: " + asset);
            var manifest = obj(send(req("/manifest.webmanifest")).body());
            check("standalone".equals(manifest.get("display")) && ((List<Map<String, Object>>) manifest.get("icons")).stream().anyMatch(i -> "maskable".equals(i.get("purpose"))), "manifest ready for Android and iOS home screens");

            // Accounts over HTTPS.
            api.security.users.createOwner("juhas", "owner-password-990");
            api.security.users.save("deputy", "deputy-password-990", Set.of("data.admin-log", "data.tickets"), true);
            String owner = String.valueOf(obj(app("/v1/login", null, Map.of("username", "juhas", "password", "owner-password-990")).body()).get("token"));
            String deputy = String.valueOf(obj(app("/v1/login", null, Map.of("username", "deputy", "password", "deputy-password-990")).body()).get("token"));
            var features = (List<?>) obj(app("/v1/health", owner, null).body()).get("features");
            check(features.containsAll(List.of("admin-log-v2", "web-push-v1", "web-prefs-v1", "account-rename-v1", "public-name-v1", "luckperms-v1", "lands-v1", "nameplates-v2")), "health advertises 9.9.0 features");

            // Appearance per account.
            var prefs = obj(app("/v1/web/prefs", owner, null).body());
            check(((Map<String, Object>) prefs.get("prefs")).get("accent").equals("cyan"), "default appearance");
            check(app("/v1/web/prefs", owner, Map.of("accent", "violet", "density", "compact", "font", 115, "tabs", List.of("dashboard", "tickets"))).statusCode() == 200, "appearance saved");
            check(app("/v1/web/prefs", owner, Map.of("accent", "neon")).statusCode() == 400 && app("/v1/web/prefs", owner, Map.of("font", 300)).statusCode() == 400 && app("/v1/web/prefs", owner, Map.of("evil", 1)).statusCode() == 400, "appearance validated");
            var after = (Map<String, Object>) obj(app("/v1/web/prefs", owner, null).body()).get("prefs");
            check("violet".equals(after.get("accent")) && "compact".equals(after.get("density")) && ((Number) after.get("font")).intValue() == 115, "appearance stored on the server");
            check("cyan".equals(((Map<String, Object>) obj(app("/v1/web/prefs", deputy, null).body()).get("prefs")).get("accent")), "appearance is per account");

            // Browser push routes.
            var pushInfo = obj(app("/v1/push", owner, null).body());
            check(WebAuthn.unb64(String.valueOf(pushInfo.get("publicKey"))).length == 65, "VAPID key published");
            check(app("/v1/push/subscribe", owner, Map.of("endpoint", "https://attacker.example/push", "keys", Map.of("p256dh", "x", "auth", "y"))).statusCode() == 400, "foreign push endpoint refused");
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC"); generator.initialize(new ECGenParameterSpec("secp256r1"));
            String p256 = WebAuthn.b64(WebPush.point((ECPublicKey) generator.generateKeyPair().getPublic())), secret = WebAuthn.b64(new byte[16]);
            check(app("/v1/push/subscribe", deputy, Map.of("endpoint", "https://fcm.googleapis.com/fcm/send/abc", "keys", Map.of("p256dh", p256, "auth", secret), "events", List.of("admin-log"))).statusCode() == 200, "browser subscribes");
            var devices = (List<Map<String, Object>>) obj(app("/v1/push", deputy, null).body()).get("devices");
            check(devices.size() == 1 && !devices.get(0).containsKey("endpoint"), "own devices listed without secrets");
            check(((List<?>) obj(app("/v1/push", owner, null).body()).get("devices")).isEmpty(), "other accounts' devices hidden");
            var events = (List<Map<String, Object>>) obj(app("/v1/push", deputy, null).body()).get("events");
            check(events.stream().anyMatch(e -> "admin-log".equals(e.get("id")) && Boolean.TRUE.equals(e.get("allowed"))) && events.stream().anyMatch(e -> "staff-chat".equals(e.get("id")) && Boolean.FALSE.equals(e.get("allowed"))), "push types follow the account's rights");

            // Admin-log unread filter via API.
            int before = ((Number) obj(app("/v1/data/admin-log", deputy, null).body()).get("unread")).intValue();
            plugin.adminLog.record("Simplex Panel/juhas (app:user:juhas)", null, "Spieler gebannt", "Griefing");
            plugin.adminLog.record("Steve", null, "Home gesetzt", "");
            var overview = obj(app("/v1/data/admin-log", deputy, null).body());
            int team = ((Number) overview.get("unread")).intValue();
            check(team == before + 1 && ((Map<String, Object>) overview.get("settings")).get("mode").equals("staff"), "default: only team actions are unread (player action silent)");
            check(((List<Map<String, Object>>) overview.get("admins")).stream().anyMatch(a -> "steve".equals(a.get("admin")) && Boolean.FALSE.equals(a.get("staff")) && Boolean.FALSE.equals(a.get("counted"))), "players marked as not counted");
            check(app("/v1/admin-log/settings", deputy, Map.of("mode", "all")).statusCode() == 200 && ((Number) obj(app("/v1/data/admin-log", deputy, null).body()).get("unread")).intValue() == team + 1, "mode all counts player actions");
            check(app("/v1/admin-log/settings", deputy, Map.of("mode", "selected", "admins", List.of("steve"))).statusCode() == 200 && ((Number) obj(app("/v1/data/admin-log", deputy, null).body()).get("unread")).intValue() == 1, "mode selected");
            check(app("/v1/admin-log/settings", deputy, Map.of("mode", "none")).statusCode() == 200 && ((Number) obj(app("/v1/data/admin-log", deputy, null).body()).get("unread")).intValue() == 0, "mode none");
            check(app("/v1/admin-log/settings", deputy, Map.of("mode", "loud")).statusCode() == 400, "unknown mode refused");
            var ownView = (List<Map<String, Object>>) obj(app("/v1/data/admin-log", owner, null).body()).get("admins");
            check(ownView.stream().anyMatch(a -> "juhas".equals(a.get("admin")) && Boolean.TRUE.equals(a.get("self")) && ((Number) a.get("unread")).intValue() == 0), "own actions never unread; filters are per viewer");

            // Public name and username change.
            check(app("/v1/account/public-name", owner, Map.of("name", "Robin")).statusCode() == 200 && "Robin".equals(obj(app("/v1/account", owner, null).body()).get("publicName")), "public name saved");
            check(app("/v1/account/public-name", owner, Map.of("name", "<b>x</b>")).statusCode() == 400, "public name validated");
            check(app("/v1/account/rename", owner, Map.of("username", "alpha", "password", "wrong-password")).statusCode() == 401, "rename needs the password");
            check(app("/v1/account/rename", deputy, Map.of("username", "boss", "password", "deputy-password-990", "target", "juhas")).statusCode() == 403, "only the main admin renames others");
            var renamed = app("/v1/account/rename", owner, Map.of("username", "Alpha.Admin", "password", "owner-password-990"));
            check(renamed.statusCode() == 200 && "alpha.admin".equals(obj(renamed.body()).get("username")), "username changed");
            var account = obj(app("/v1/account", owner, null).body());
            check("alpha.admin".equals(account.get("username")) && Boolean.TRUE.equals(account.get("owner")) && "Robin".equals(account.get("publicName")), "this device stays signed in; owner and public name kept");
            check(obj(app("/v1/login", null, Map.of("username", "alpha.admin", "password", "owner-password-990")).body()).get("token") != null && app("/v1/login", null, Map.of("username", "juhas", "password", "owner-password-990")).statusCode() == 401, "login with the new name only");
            check("violet".equals(((Map<String, Object>) obj(app("/v1/web/prefs", owner, null).body()).get("prefs")).get("accent")), "appearance follows the new name");
            var log = obj(app("/v1/data/admin-log", deputy, null).body());
            check(((List<Map<String, Object>>) log.get("admins")).stream().anyMatch(a -> "alpha.admin".equals(a.get("admin"))) && ((List<Map<String, Object>>) log.get("admins")).stream().noneMatch(a -> "juhas".equals(a.get("admin"))), "admin-log entries follow the new name");

            // LuckPerms / Lands without the plugins installed.
            check(Boolean.FALSE.equals(obj(app("/v1/data/luckperms", owner, null).body()).get("installed")) && Boolean.FALSE.equals(obj(app("/v1/data/lands", owner, null).body()).get("installed")), "LuckPerms/Lands optional");
            check(app("/v1/data/luckperms", deputy, null).statusCode() == 403, "LuckPerms data needs data.luckperms");
            var plan = obj(app("/v1/prepare", owner, Map.of("path", "luckperms.edit", "values", Map.of("operation", "group-weight", "group", "admin", "value", "90"))).body());
            check(String.valueOf(plan.get("summary")).contains("/lp group admin setweight 90"), "LuckPerms change confirmed with exact command");
            check(app("/v1/prepare", owner, Map.of("path", "luckperms.edit", "values", Map.of("operation", "group-permission-set", "group", "admin", "node", "x y"))).statusCode() == 400, "invalid LuckPerms change refused before confirmation");
            check(app("/v1/prepare", deputy, Map.of("path", "luckperms.edit", "values", Map.of("operation", "sync"))).statusCode() == 403, "LuckPerms changes need action.luckperms.edit");

            // Console-only recovery.
            consoleOutput.clear(); api.command(player, new String[]{"user-reset", "deputy"});
            check(consoleOutput.stream().anyMatch(s -> s.startsWith("PLAYER:") && s.contains("Serverkonsole")) && api.security.users.verifyPassword("deputy", "deputy-password-990") != null, "in-game operator cannot reset users");
            consoleOutput.clear(); api.command(console, new String[]{"recovery"});
            check(consoleOutput.stream().anyMatch(s -> s.contains("owner-transfer")) && consoleOutput.stream().anyMatch(s -> s.contains("user-reset")), "recovery overview in the console");
            consoleOutput.clear(); api.command(console, new String[]{"user-reset", "deputy"});
            String once = consoleOutput.stream().filter(s -> s.contains("Einmal-Passwort")).map(s -> s.substring(s.lastIndexOf('§') + 2).trim()).findFirst().orElse("");
            check(once.length() == 23 && app("/v1/health", deputy, null).statusCode() == 401, "console user-reset shows a one-time password and signs out");
            check(Boolean.TRUE.equals(obj(app("/v1/login", null, Map.of("username", "deputy", "password", once)).body()).get("passwordChange")), "one-time password requires a new password");
            api.command(console, new String[]{"rename", "deputy", "helper"});
            check(api.security.users.exists("helper") && !api.security.users.exists("deputy"), "console rename");
            api.command(console, new String[]{"owner-transfer", "helper"});
            check(api.security.users.owner("helper") && !api.security.users.owner("alpha.admin"), "console owner-transfer");
            plugin.getConfig().set("discord-bot.admin.console.enabled", true); plugin.getConfig().set("discord-bot.admin.console.full-access", true);
            check(plugin.discordAdminConsole("1", "Discord/x (1)", "sas app user-reset helper", true).contains("Serverkonsole") && plugin.discordAdminConsole("1", "Discord/x (1)", "simplexadmin app owner-transfer x", true).contains("Serverkonsole"), "recovery never relayed from Discord/app consoles");
            plugin.getConfig().set("discord-bot.admin.console.enabled", false);

            api.stop(); check(!api.active(), "listener stops");
        } finally {
            if (api != null) api.stop(); serverField.set(null, old);
            try (var stream = Files.walk(dir)) { for (Path p : stream.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); }
        }
    }
}
