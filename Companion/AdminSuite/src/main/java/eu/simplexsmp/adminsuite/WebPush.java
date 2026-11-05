package eu.simplexsmp.adminsuite;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Predicate;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 9.9.0 browser push for the web panel (Chrome/Edge/Firefox on desktop and Android, Safari on macOS and
 * iOS/iPadOS 16.4+ when the panel was added to the home screen). Pure JDK: VAPID (RFC 8292, ES256) and
 * aes128gcm payload encryption (RFC 8291 / RFC 8188). Subscriptions are stored per account and device; only the
 * endpoint, the browser's public key and auth secret are kept. Push services are restricted to the well-known
 * providers, so a subscription can never make the server call an arbitrary address.
 */
final class WebPush {
    static final List<String> DEFAULT_EVENTS = List.of("ticket", "ticket-high", "ticket-urgent", "emergency-restart", "ban-evasion", "staff-chat", "admin-log");
    private static final List<String> HOSTS = List.of(".googleapis.com", ".push.services.mozilla.com", ".push.apple.com", ".notify.windows.com");
    private final Path file;
    private final Properties state = new Properties();
    private final SecureRandom random = new SecureRandom();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final java.util.function.Supplier<String> contact;
    private final java.util.function.BiFunction<String, String, Boolean> allowed;
    private final QuietHours quiet;
    private KeyPair vapid;
    private volatile String status = "Noch kein Versand";

    /** Quiet hours shared with Bark: returns the level to use or null when the owner is in a silent window. */
    interface QuietHours { String level(String owner, String type, String level); }

    WebPush(Path file, java.util.function.Supplier<String> contact, java.util.function.BiFunction<String, String, Boolean> allowed, QuietHours quiet) throws IOException {
        this.file = file; this.contact = contact; this.allowed = allowed; this.quiet = quiet;
        if (Files.exists(file)) try (var in = Files.newInputStream(file)) { state.load(in); }
        AppApiSecurity.protect(file);
    }

    // ---------------------------------------------------------------- VAPID key

    synchronized KeyPair vapid() {
        if (vapid != null) return vapid;
        try {
            KeyFactory factory = KeyFactory.getInstance("EC");
            String pub = state.getProperty("vapid.public", ""), priv = state.getProperty("vapid.private", "");
            if (!pub.isEmpty() && !priv.isEmpty()) {
                vapid = new KeyPair(factory.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pub))), factory.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(priv))));
                return vapid;
            }
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC"); generator.initialize(new ECGenParameterSpec("secp256r1"), random);
            KeyPair pair = generator.generateKeyPair();
            Properties next = copy();
            next.setProperty("vapid.public", Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
            next.setProperty("vapid.private", Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
            next.setProperty("vapid.created", Long.toString(System.currentTimeMillis()));
            persist(next); vapid = pair; return pair;
        } catch (GeneralSecurityException | IOException e) { throw new IllegalStateException("VAPID-Schlüssel nicht verfügbar", e); }
    }

    /** Application server key for PushManager.subscribe (uncompressed P-256 point, base64url). */
    String publicKey() { return WebAuthn.b64(point((ECPublicKey) vapid().getPublic())); }

    static byte[] point(ECPublicKey key) {
        byte[] out = new byte[65]; out[0] = 4;
        copy32(key.getW().getAffineX(), out, 1); copy32(key.getW().getAffineY(), out, 33);
        return out;
    }
    private static void copy32(BigInteger value, byte[] out, int offset) {
        byte[] raw = value.toByteArray();
        int start = Math.max(0, raw.length - 32), length = raw.length - start;
        System.arraycopy(raw, start, out, offset + 32 - length, length);
    }
    static ECPublicKey decodePoint(byte[] point) throws GeneralSecurityException {
        if (point.length != 65 || point[0] != 4) throw new InvalidKeyException("Ungültiger Browser-Schlüssel");
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC"); parameters.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec spec = parameters.getParameterSpec(ECParameterSpec.class);
        ECPoint w = new ECPoint(new BigInteger(1, Arrays.copyOfRange(point, 1, 33)), new BigInteger(1, Arrays.copyOfRange(point, 33, 65)));
        return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(w, spec));
    }

    // ---------------------------------------------------------------- subscriptions

    static void checkEndpoint(String endpoint) {
        URI uri;
        try { uri = URI.create(endpoint); } catch (IllegalArgumentException e) { throw new IllegalArgumentException("Push-Adresse ungültig"); }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!"https".equals(uri.getScheme()) || endpoint.length() > 1024 || uri.getUserInfo() != null || uri.getPort() != -1 && uri.getPort() != 443 || HOSTS.stream().noneMatch(host::endsWith))
            throw new IllegalArgumentException("Dieser Push-Dienst wird nicht unterstützt (Chrome, Edge, Firefox, Safari).");
    }

    synchronized Map<String, Object> subscribe(String owner, Map<String, Object> input, Map<String, String> device) throws IOException {
        String endpoint = String.valueOf(input.getOrDefault("endpoint", ""));
        checkEndpoint(endpoint);
        Map<String, Object> keys = input.get("keys") instanceof Map<?, ?> m ? AppApiManager.object(m) : Map.of();
        String p256dh = String.valueOf(keys.getOrDefault("p256dh", "")), auth = String.valueOf(keys.getOrDefault("auth", ""));
        try { decodePoint(WebAuthn.unb64(p256dh)); } catch (Exception e) { throw new IllegalArgumentException("Browser-Schlüssel ungültig"); }
        if (WebAuthn.unb64(auth).length != 16) throw new IllegalArgumentException("Browser-Geheimnis ungültig");
        String id = AppApiSecurity.hash(endpoint).substring(0, 24);
        long count = state.stringPropertyNames().stream().filter(k -> k.startsWith("sub.") && owner.equals(json(state.getProperty(k)).get("owner"))).count();
        if (!state.containsKey("sub." + id) && count >= 12) throw new IllegalArgumentException("Maximal 12 Browser pro Konto. Bitte ein altes Gerät entfernen.");
        Map<String, Object> previous = json(state.getProperty("sub." + id, ""));
        List<String> events = events(input.containsKey("events") ? input.get("events") : previous.getOrDefault("events", DEFAULT_EVENTS));
        Map<String, Object> row = SimplexAdminSuite.appMap("owner", owner, "endpoint", endpoint, "p256dh", p256dh, "auth", auth, "events", events,
            "created", previous.getOrDefault("created", System.currentTimeMillis()), "label", AppUsers.clean(String.valueOf(input.getOrDefault("label", device.getOrDefault("label", ""))), 60),
            "browser", device.getOrDefault("browser", ""), "os", device.getOrDefault("os", ""), "model", device.getOrDefault("model", ""), "failures", 0);
        Properties next = copy(); next.setProperty("sub." + id, DiscordBotManager.Json.stringify(row)); persist(next);
        return SimplexAdminSuite.appMap("ok", true, "id", id, "events", events, "title", "Push aktiviert", "description", "Dieses Gerät erhält Hinweise auch bei geschlossenem Webpanel.");
    }

    static List<String> events(Object raw) {
        List<String> list = raw instanceof List<?> l ? l.stream().map(String::valueOf).toList() : Arrays.asList(String.valueOf(raw).split(","));
        List<String> out = new ArrayList<>();
        for (String v : list) { String t = v.trim(); if (t.isEmpty()) continue; if (!SuiteEventCenter.TYPES.contains(t)) throw new IllegalArgumentException("Unbekanntes Ereignis: " + t); if (!out.contains(t)) out.add(t); }
        return out;
    }

    synchronized Map<String, Object> update(String owner, String id, Map<String, Object> input, boolean manager) throws IOException {
        String key = "sub." + id; Map<String, Object> row = json(state.getProperty(key, ""));
        if (row.isEmpty() || !owner.equals(row.get("owner")) && !manager) throw new IllegalArgumentException("Gerät nicht gefunden");
        Properties next = copy();
        if ("delete".equals(input.get("operation"))) next.remove(key);
        else {
            if (input.containsKey("events")) row.put("events", events(input.get("events")));
            if (input.containsKey("label")) row.put("label", AppUsers.clean(String.valueOf(input.get("label")), 60));
            next.setProperty(key, DiscordBotManager.Json.stringify(row));
        }
        persist(next);
        return SimplexAdminSuite.appMap("ok", true, "title", "delete".equals(input.get("operation")) ? "Push entfernt" : "Gespeichert");
    }

    synchronized Map<String, Object> unsubscribe(String owner, String endpoint) throws IOException {
        String key = "sub." + AppApiSecurity.hash(endpoint).substring(0, 24);
        Map<String, Object> row = json(state.getProperty(key, ""));
        if (!row.isEmpty() && owner.equals(row.get("owner"))) { Properties next = copy(); next.remove(key); persist(next); }
        return SimplexAdminSuite.appMap("ok", true, "title", "Push ausgeschaltet");
    }

    synchronized List<Map<String, Object>> list(String owner) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String k : state.stringPropertyNames()) if (k.startsWith("sub.")) {
            Map<String, Object> row = json(state.getProperty(k));
            if (owner != null && !owner.equals(row.get("owner"))) continue;
            Map<String, Object> view = new LinkedHashMap<>(row); view.remove("endpoint"); view.remove("p256dh"); view.remove("auth"); view.put("id", k.substring(4));
            view.put("service", service(String.valueOf(row.get("endpoint"))));
            out.add(view);
        }
        out.sort(Comparator.comparingLong(r -> -(r.get("created") instanceof Number n ? n.longValue() : 0)));
        return out;
    }
    /** Endpoint hash for the current browser so the page can show "this device". */
    static String idFor(String endpoint) { return AppApiSecurity.hash(endpoint).substring(0, 24); }
    private static String service(String endpoint) {
        String host = URI.create(endpoint).getHost();
        return host == null ? "" : host.endsWith(".push.apple.com") ? "Apple" : host.endsWith(".googleapis.com") ? "Google (Chrome/Edge/Android)" : host.endsWith(".mozilla.com") ? "Mozilla (Firefox)" : host.endsWith(".notify.windows.com") ? "Windows" : host;
    }

    synchronized void renameOwner(String from, String to) throws IOException {
        Properties next = copy(); boolean changed = false;
        for (String k : state.stringPropertyNames()) if (k.startsWith("sub.")) { Map<String, Object> row = json(state.getProperty(k)); if (from.equals(row.get("owner"))) { row.put("owner", to); next.setProperty(k, DiscordBotManager.Json.stringify(row)); changed = true; } }
        if (changed) persist(next);
    }
    synchronized void removeOwner(String owner) throws IOException {
        Properties next = copy(); boolean changed = false;
        for (String k : state.stringPropertyNames()) if (k.startsWith("sub.") && owner.equals(json(state.getProperty(k)).get("owner"))) { next.remove(k); changed = true; }
        if (changed) persist(next);
    }

    String status() { return status; }
    synchronized int count() { return (int) state.stringPropertyNames().stream().filter(k -> k.startsWith("sub.")).count(); }

    // ---------------------------------------------------------------- delivery

    /** Sends an event to every subscribed browser that selected its type; {@code owners} narrows the audience. Worker thread. */
    void deliver(Map<String, Object> row, Predicate<String> owners) {
        String type = String.valueOf(row.get("type"));
        List<Map.Entry<String, Map<String, Object>>> targets = new ArrayList<>();
        synchronized (this) {
            for (String k : state.stringPropertyNames()) if (k.startsWith("sub.")) {
                Map<String, Object> sub = json(state.getProperty(k)); String owner = String.valueOf(sub.get("owner"));
                if (!(sub.get("events") instanceof List<?> ev) || !ev.contains(type)) continue;
                if (!Boolean.TRUE.equals(allowed.apply(owner, type))) continue;
                if (owners != null && !owners.test(owner)) continue;
                targets.add(Map.entry(k, sub));
            }
        }
        boolean critical = Boolean.TRUE.equals(row.get("critical"));
        for (var target : targets) {
            String owner = String.valueOf(target.getValue().get("owner"));
            String level = quiet == null ? "active" : quiet.level(owner, type, critical ? "critical" : "active");
            if (level == null) continue;
            Map<String, Object> payload = SimplexAdminSuite.appMap("title", row.get("title"), "body", row.get("body"), "type", type, "critical", critical || level.equals("critical"),
                "tag", type.startsWith("ticket") ? "ticket" : type, "url", row.get("webUrl") instanceof String w && w.startsWith("/#/") ? w : url(type), "time", row.get("time"), "id", row.get("id"));
            try {
                int code = send(target.getValue(), DiscordBotManager.Json.stringify(payload).getBytes(StandardCharsets.UTF_8), critical ? "high" : "normal");
                if (code == 404 || code == 410) { drop(target.getKey()); continue; }
                if (code >= 200 && code < 300) { status = "Letzter Versand erfolgreich"; failure(target.getKey(), false); }
                else { status = "Push-Dienst antwortete " + code; failure(target.getKey(), true); }
            } catch (Exception e) { status = "Versand fehlgeschlagen (" + e.getClass().getSimpleName() + ")"; }
        }
    }
    static String url(String type) { return type.startsWith("ticket") ? "/#/tickets" : type.equals("admin-log") ? "/#/admin-log" : type.equals("staff-chat") ? "/#/staff-chat" : "/#/events"; }

    /** Test message to every browser of one owner. */
    Map<String, Object> test(String owner) {
        List<Map<String, Object>> targets = new ArrayList<>();
        synchronized (this) { for (String k : state.stringPropertyNames()) if (k.startsWith("sub.")) { Map<String, Object> sub = json(state.getProperty(k)); if (owner.equals(sub.get("owner"))) targets.add(sub); } }
        if (targets.isEmpty()) throw new IllegalArgumentException("Auf diesem Konto ist noch kein Browser für Push angemeldet.");
        int ok = 0;
        for (Map<String, Object> sub : targets) {
            byte[] body = DiscordBotManager.Json.stringify(SimplexAdminSuite.appMap("title", "Simplex Panel", "body", "Test erfolgreich – Push-Benachrichtigungen funktionieren.", "tag", "test", "url", "/#/dashboard", "type", "test")).getBytes(StandardCharsets.UTF_8);
            try { int code = send(sub, body, "normal"); if (code >= 200 && code < 300) ok++; else if (code == 404 || code == 410) drop("sub." + idFor(String.valueOf(sub.get("endpoint")))); } catch (Exception e) { status = "Versand fehlgeschlagen (" + e.getClass().getSimpleName() + ")"; }
        }
        return SimplexAdminSuite.appMap("ok", ok > 0, "title", ok > 0 ? "Test gesendet" : "Test fehlgeschlagen", "description", ok + " von " + targets.size() + " Geräten erreicht.");
    }

    private synchronized void drop(String key) { try { Properties next = copy(); next.remove(key); persist(next); } catch (IOException ignored) { } }
    private synchronized void failure(String key, boolean failed) {
        Map<String, Object> row = json(state.getProperty(key, "")); if (row.isEmpty()) return;
        long before = row.get("failures") instanceof Number n ? n.longValue() : 0, after = failed ? before + 1 : 0;
        if (before == after && !failed) return;
        try { Properties next = copy(); if (after >= 20) next.remove(key); else { row.put("failures", after); if (!failed) row.put("delivered", System.currentTimeMillis()); next.setProperty(key, DiscordBotManager.Json.stringify(row)); } persist(next); } catch (IOException ignored) { }
    }

    private int send(Map<String, Object> sub, byte[] payload, String urgency) throws Exception {
        String endpoint = String.valueOf(sub.get("endpoint")); checkEndpoint(endpoint);
        byte[] body = encrypt(payload, WebAuthn.unb64(String.valueOf(sub.get("p256dh"))), WebAuthn.unb64(String.valueOf(sub.get("auth"))), random);
        URI uri = URI.create(endpoint);
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/octet-stream").header("Content-Encoding", "aes128gcm").header("TTL", "86400").header("Urgency", urgency)
            .header("Authorization", "vapid t=" + jwt(uri.getScheme() + "://" + uri.getHost()) + ", k=" + publicKey())
            .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
        return response.statusCode();
    }

    /** VAPID JWT (ES256, 12 h). */
    String jwt(String audience) throws GeneralSecurityException {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String header = b64.encodeToString("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
        String claims = b64.encodeToString(("{\"aud\":\"" + jsonText(audience) + "\",\"exp\":" + (System.currentTimeMillis() / 1000 + 12 * 3600) + ",\"sub\":\"" + jsonText(contact.get()) + "\"}").getBytes(StandardCharsets.UTF_8));
        Signature signer = Signature.getInstance("SHA256withECDSAinP1363Format"); signer.initSign(vapid().getPrivate());
        signer.update((header + "." + claims).getBytes(StandardCharsets.US_ASCII));
        return header + "." + claims + "." + b64.encodeToString(signer.sign());
    }

    private static String jsonText(String value) { StringBuilder out = new StringBuilder(); for (char c : String.valueOf(value).toCharArray()) { if (c == '"' || c == '\\') out.append('\\').append(c); else if (c < 32) out.append(String.format("\\u%04x", (int) c)); else out.append(c); } return out.toString(); }

    /** RFC 8291 message encryption with the aes128gcm content coding (single record). */
    static byte[] encrypt(byte[] plaintext, byte[] uaPublic, byte[] authSecret, SecureRandom random) throws GeneralSecurityException {
        if (plaintext.length > 3800) throw new IllegalArgumentException("Push-Inhalt zu groß");
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC"); generator.initialize(new ECGenParameterSpec("secp256r1"), random);
        KeyPair local = generator.generateKeyPair();
        byte[] asPublic = point((ECPublicKey) local.getPublic());
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH"); agreement.init(local.getPrivate()); agreement.doPhase(decodePoint(uaPublic), true);
        byte[] shared = agreement.generateSecret();
        byte[] salt = new byte[16]; random.nextBytes(salt);
        byte[] info = concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), uaPublic, asPublic);
        byte[] ikm = expand(hmac(authSecret, shared), info, 32);
        byte[] prk = hmac(salt, ikm);
        byte[] cek = expand(prk, "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), 16);
        byte[] nonce = expand(prk, "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), 12);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] record = cipher.doFinal(concat(plaintext, new byte[]{2}));
        return ByteBuffer.allocate(16 + 4 + 1 + asPublic.length + record.length).put(salt).putInt(4096).put((byte) asPublic.length).put(asPublic).put(record).array();
    }
    static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException { Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256")); return mac.doFinal(data); }
    /** HKDF-Expand for at most one block (all lengths used here are ≤ 32). */
    static byte[] expand(byte[] prk, byte[] info, int length) throws GeneralSecurityException { return Arrays.copyOf(hmac(prk, concat(info, new byte[]{1})), length); }
    static byte[] concat(byte[]... parts) { int n = 0; for (byte[] p : parts) n += p.length; byte[] out = new byte[n]; int o = 0; for (byte[] p : parts) { System.arraycopy(p, 0, out, o, p.length); o += p.length; } return out; }

    @SuppressWarnings("unchecked") private static Map<String, Object> json(String raw) { if (raw == null || raw.isBlank()) return new LinkedHashMap<>(); try { Object v = DiscordBotManager.Json.parse(raw); return v instanceof Map<?, ?> m ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>(); } catch (RuntimeException e) { return new LinkedHashMap<>(); } }
    private Properties copy() { Properties next = new Properties(); next.putAll(state); return next; }
    private void persist(Properties next) throws IOException { AtomicState.save(file, next, "Simplex web push: VAPID key pair and browser subscriptions. Private, do not share."); AppApiSecurity.protect(file); state.clear(); state.putAll(next); }
}
