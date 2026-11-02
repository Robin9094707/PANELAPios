package eu.simplexsmp.adminsuite;

import com.sun.net.httpserver.HttpExchange;
import org.bukkit.Bukkit;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 9.8.0 account API: multi-step login (password → new password after an emergency reset → second factor),
 * passkeys, migration of the main API key to a main-admin account, own security settings and the device list.
 * Every response is JSON; secrets (passwords, TOTP seeds after setup, session tokens of other devices) never leave.
 */
final class AccountApi {
    private final AppApiManager api;
    private final SimplexAdminSuite plugin;
    private final AppApiSecurity security;
    private final AppUsers users;
    private record Flow(boolean web, boolean remember, boolean exchange, Map<String, Object> device) { }
    private record Pending(String user, String stage, Flow flow, long expires, int[] attempts) { }
    private record Passkey(byte[] challenge, String purpose, String user, long expires) { }
    private record Exchange(String user, long expires, boolean web) { }
    private final Map<String, Pending> pending = new HashMap<>();
    private final Map<String, Passkey> passkeys = new HashMap<>();
    private final Map<String, Exchange> exchanges = new HashMap<>();
    private final Map<String, String[]> totpSetup = new HashMap<>();

    AccountApi(AppApiManager api, SimplexAdminSuite plugin, AppApiSecurity security) {
        this.api = api; this.plugin = plugin; this.security = security; this.users = security.users;
    }

    // ---------------------------------------------------------------- configuration

    /** host[:port] for the web panel and passkeys, e.g. admin.example.eu; empty = passkeys unavailable. */
    String domain() {
        String raw = plugin.getConfig().getString("app-api.public-domain", "").trim().toLowerCase(Locale.ROOT);
        raw = raw.replaceFirst("^https://", "").replaceAll("/+$", "");
        return raw.matches("[a-z0-9.-]{1,253}(:[0-9]{2,5})?") && raw.contains(".") ? raw : "";
    }
    String rpId() { String d = domain(); int colon = d.indexOf(':'); return colon < 0 ? d : d.substring(0, colon); }
    String webUrl() { String d = domain(); return d.isEmpty() ? "" : "https://" + d + "/"; }
    static String validateDomain(String raw) {
        String v = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replaceFirst("^https://", "").replaceAll("/+$", "");
        if (v.isEmpty()) return "";
        if (!v.matches("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+(:[0-9]{2,5})?") || v.matches("[0-9.:]+"))
            throw new IllegalArgumentException("Domain z. B. admin.example.eu oder admin.example.eu:39093 – keine IP-Adresse");
        return v;
    }

    // ---------------------------------------------------------------- public routes

    /** Returns true when the request was handled. Called before authentication. */
    boolean publicRoute(HttpExchange x, String path, String method, String ip) throws Exception {
        if (method.equals("GET") && path.equals("/v1/web/config")) {
            AppApiManager.send(x, 200, SimplexAdminSuite.appMap("name", Bukkit.getServer().getName(), "version", plugin.getDescription().getVersion(),
                "passkeys", !rpId().isEmpty(), "rpId", rpId(), "domain", domain(), "apiVersion", 1));
            return true;
        }
        if (!method.equals("POST")) return false;
        switch (path) {
            case "/v1/login" -> login(x, ip);
            case "/v1/login/verify" -> verify(x, ip);
            case "/v1/login/exchange" -> exchange(x, ip);
            case "/v1/passkey/begin" -> passkeyBegin(x, ip);
            case "/v1/passkey/finish" -> passkeyFinish(x, ip);
            default -> { return false; }
        }
        return true;
    }

    private Flow flow(HttpExchange x, Map<String, Object> body, String ip) {
        boolean web = Boolean.TRUE.equals(body.get("web")) && x.getRequestHeaders().containsKey(WebPanel.HEADER);
        boolean exchange = Boolean.TRUE.equals(body.get("app")) && web;
        return new Flow(web && !exchange, Boolean.TRUE.equals(body.get("remember")), exchange, device(x, ip, web && !exchange));
    }

    /** Device details: the app sends X-Simplex-Device, browsers are described from their User-Agent. */
    Map<String, Object> device(HttpExchange x, String ip, boolean web) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (web) {
            Map<String, String> b = WebPanel.browser(x.getRequestHeaders().getFirst("User-Agent"));
            out.put("kind", "web"); out.put("browser", b.get("browser")); out.put("os", b.get("os")); out.put("model", b.get("model"));
        } else {
            out.put("kind", "app"); out.putAll(appDevice(x));
        }
        out.put("ip", ip);
        return out;
    }

    static Map<String, String> appDevice(HttpExchange x) {
        Map<String, String> out = new LinkedHashMap<>();
        String header = x.getRequestHeaders().getFirst("X-Simplex-Device");
        if (header == null || header.length() > 600) return out;
        for (String part : header.split(";")) {
            String[] kv = part.split("=", 2); if (kv.length != 2) continue;
            String k = kv[0].trim(), v;
            try { v = AppUsers.clean(URLDecoder.decode(kv[1].trim(), StandardCharsets.UTF_8), 60); } catch (IllegalArgumentException e) { continue; }
            switch (k) {
                case "model" -> { out.put("modelId", v); out.put("model", DeviceNames.display(v, v, true)); }
                case "os", "app", "name" -> out.put(k.equals("name") ? "deviceName" : k, v);
                default -> { }
            }
        }
        return out;
    }

    private void login(HttpExchange x, String ip) throws Exception {
        if (!api.rate("login-ip:" + ip, 8)) throw new AppApiManager.ApiError(429, "Loginlimit erreicht. Eine Minute warten.");
        Map<String, Object> body = AppApiManager.body(x, 2048);
        String username = String.valueOf(body.getOrDefault("username", "")).trim().toLowerCase(Locale.ROOT), password = String.valueOf(body.getOrDefault("password", ""));
        if (username.length() > 64 || password.length() > 256 || !api.rate("login-user:" + username, 12)) throw new AppApiManager.ApiError(429, "Loginlimit erreicht");
        String name = users.verifyPassword(username, password);
        if (name == null) throw new AppApiManager.ApiError(401, "Benutzername oder Passwort ungültig");
        next(x, name, flow(x, body, ip), "password");
    }

    /** Decides the next login step after a successful step. */
    private void next(HttpExchange x, String name, Flow flow, String done) throws Exception {
        if (!done.equals("new-password") && users.mustChange(name)) {
            AppApiManager.send(x, 200, SimplexAdminSuite.appMap("passwordChange", true, "challenge", challenge(name, "password", flow), "username", name,
                "message", "Notfall-Passwort erkannt. Bitte jetzt ein eigenes, neues Passwort festlegen."));
            return;
        }
        if (!done.equals("mfa") && !done.equals("passkey") && users.mfa(name)) {
            List<String> methods = new ArrayList<>(List.of("totp", "recovery"));
            if (!rpId().isEmpty() && !users.credentialIds(name).isEmpty() && flow.web()) methods.add("passkey");
            AppApiManager.send(x, 200, SimplexAdminSuite.appMap("mfa", true, "challenge", challenge(name, "mfa", flow), "methods", methods, "username", name));
            return;
        }
        complete(x, name, flow, done);
    }

    private synchronized String challenge(String name, String stage, Flow flow) {
        long now = System.currentTimeMillis();
        pending.values().removeIf(p -> p.expires() < now);
        if (pending.size() > 512) throw new AppApiManager.ApiError(429, "Zu viele offene Anmeldungen. Kurz warten.");
        String id = AccountCrypto.token(24);
        pending.put(id, new Pending(name, stage, flow, now + 5 * 60_000L, new int[]{0}));
        return id;
    }

    private void complete(HttpExchange x, String name, Flow flow, String method) throws Exception {
        long now = System.currentTimeMillis();
        String via = switch (method) { case "passkey" -> "Passkey"; case "mfa" -> "Passwort + 2FA"; case "exchange" -> "App-Übergabe"; default -> "Passwort"; };
        if (flow.exchange()) {
            String code;
            synchronized (this) { exchanges.values().removeIf(e -> e.expires() < now); code = "sasx_" + AccountCrypto.token(32); exchanges.put(AppApiSecurity.hash(code), new Exchange(name, now + 120_000L, false)); }
            AppApiManager.send(x, 200, SimplexAdminSuite.appMap("exchange", code, "username", name));
            return;
        }
        Map<String, Object> device = new LinkedHashMap<>(flow.device()); device.put("via", via);
        long ttl = flow.web() ? (flow.remember() ? AppUsers.WEB_SESSION : AppUsers.WEB_SHORT) : AppUsers.APP_SESSION;
        String token = users.createSession(name, now, ttl, device);
        audit(name, flow.web() ? "Webpanel-Anmeldung" : "App-Anmeldung", via + " · " + device.getOrDefault("ip", ""));
        if (flow.web()) {
            WebPanel.setCookie(x, token, flow.remember() ? ttl / 1000 : 0);
            AppApiManager.send(x, 200, SimplexAdminSuite.appMap("ok", true, "username", name, "owner", users.owner(name)));
        } else AppApiManager.send(x, 200, SimplexAdminSuite.appMap("token", token, "expiresIn", ttl / 1000, "username", name, "owner", users.owner(name)));
    }

    private void verify(HttpExchange x, String ip) throws Exception {
        if (!api.rate("verify-ip:" + ip, 20)) throw new AppApiManager.ApiError(429, "Zu viele Versuche. Eine Minute warten.");
        Map<String, Object> body = AppApiManager.body(x, 2048);
        Pending p;
        synchronized (this) { p = pending.get(String.valueOf(body.getOrDefault("challenge", ""))); }
        if (p == null || p.expires() < System.currentTimeMillis()) throw new AppApiManager.ApiError(401, "Anmeldung abgelaufen. Bitte erneut mit Passwort anmelden.");
        if (++p.attempts()[0] > 5) { synchronized (this) { pending.values().remove(p); } throw new AppApiManager.ApiError(429, "Zu viele Fehlversuche. Bitte neu anmelden."); }
        if (!users.enabled(p.user())) throw new AppApiManager.ApiError(401, "Benutzer deaktiviert");
        if (p.stage().equals("password")) {
            String fresh = String.valueOf(body.getOrDefault("newPassword", ""));
            users.replacePassword(p.user(), fresh, null);
            synchronized (this) { pending.values().remove(p); }
            audit(p.user(), "Passwort nach Notfall-Reset neu gesetzt", ip);
            next(x, p.user(), p.flow(), "new-password");
            return;
        }
        String code = String.valueOf(body.getOrDefault("code", "")).trim();
        boolean ok = code.replaceAll("[\\s-]", "").matches("[0-9]{6}") ? users.checkTotp(p.user(), code, System.currentTimeMillis()) : users.useRecovery(p.user(), code);
        if (!ok) throw new AppApiManager.ApiError(401, "Code ungültig oder bereits verwendet");
        synchronized (this) { pending.values().remove(p); }
        if (!code.replaceAll("[\\s-]", "").matches("[0-9]{6}")) audit(p.user(), "Wiederherstellungscode verwendet", users.recoveryLeft(p.user()) + " übrig");
        complete(x, p.user(), p.flow(), "mfa");
    }

    private void exchange(HttpExchange x, String ip) throws Exception {
        if (!api.rate("exchange-ip:" + ip, 10)) throw new AppApiManager.ApiError(429, "Zu viele Versuche.");
        Map<String, Object> body = AppApiManager.body(x, 1024);
        String code = String.valueOf(body.getOrDefault("code", ""));
        Exchange e;
        synchronized (this) { e = code.startsWith("sasx_") ? exchanges.remove(AppApiSecurity.hash(code)) : null; }
        if (e == null || e.expires() < System.currentTimeMillis() || !users.enabled(e.user())) throw new AppApiManager.ApiError(401, "Übergabe abgelaufen oder bereits verwendet");
        boolean web = Boolean.TRUE.equals(body.get("web")) && x.getRequestHeaders().containsKey(WebPanel.HEADER);
        Map<String, Object> device = device(x, ip, web);
        if (web) {
            // App → browser: short-lived browser session (e.g. to add a passkey); never remembered.
            device.put("label", "Aus der App geöffnet"); device.put("via", "App-Übergabe");
            String token = users.createSession(e.user(), System.currentTimeMillis(), AppUsers.HANDOFF_SESSION, device);
            WebPanel.setCookie(x, token, 0);
            AppApiManager.send(x, 200, SimplexAdminSuite.appMap("ok", true, "username", e.user()));
        } else complete(x, e.user(), new Flow(false, false, false, device), "exchange");
    }

    // ---------------------------------------------------------------- passkeys (login)

    private synchronized String newPasskeyChallenge(String purpose, String user, byte[] challenge) {
        long now = System.currentTimeMillis();
        passkeys.values().removeIf(p -> p.expires() < now);
        if (passkeys.size() > 256) throw new AppApiManager.ApiError(429, "Zu viele Passkey-Anfragen. Kurz warten.");
        String id = AccountCrypto.token(18);
        passkeys.put(id, new Passkey(challenge, purpose, user, now + 180_000L));
        return id;
    }
    private synchronized Passkey takePasskey(String id, String purpose) {
        Passkey p = passkeys.remove(id == null ? "" : id);
        if (p == null || p.expires() < System.currentTimeMillis() || !p.purpose().equals(purpose)) throw new AppApiManager.ApiError(401, "Passkey-Anfrage abgelaufen. Bitte erneut versuchen.");
        return p;
    }
    private void requireRp() { if (rpId().isEmpty()) throw new AppApiManager.ApiError(409, "Passkeys benötigen eine eigene Domain mit gültigem HTTPS-Zertifikat. Domain unter Webpanel & Domain eintragen."); }

    private void passkeyBegin(HttpExchange x, String ip) throws Exception {
        requireRp();
        if (!api.rate("passkey-ip:" + ip, 20)) throw new AppApiManager.ApiError(429, "Zu viele Versuche.");
        byte[] challenge = new byte[32]; new java.security.SecureRandom().nextBytes(challenge);
        String id = newPasskeyChallenge("login", "", challenge);
        AppApiManager.send(x, 200, SimplexAdminSuite.appMap("id", id, "publicKey", SimplexAdminSuite.appMap(
            "challenge", WebAuthn.b64(challenge), "rpId", rpId(), "timeout", 120000, "userVerification", "required", "allowCredentials", List.of())));
    }

    @SuppressWarnings("unchecked")
    private void passkeyFinish(HttpExchange x, String ip) throws Exception {
        requireRp();
        if (!api.rate("passkey-ip:" + ip, 20)) throw new AppApiManager.ApiError(429, "Zu viele Versuche.");
        Map<String, Object> body = AppApiManager.body(x, 16384);
        Passkey challenge = takePasskey(String.valueOf(body.get("id")), "login");
        Map<String, Object> credential = AppApiManager.object(body.get("credential")), response = AppApiManager.object(credential.get("response"));
        String credentialId = String.valueOf(credential.get("id"));
        Map<String, Object> stored = users.passkey(credentialId);
        if (stored == null) throw new AppApiManager.ApiError(401, "Dieser Passkey ist auf dem Server nicht (mehr) registriert.");
        if (!rpId().equals(stored.get("rp"))) throw new AppApiManager.ApiError(401, "Passkey wurde für eine andere Domain angelegt.");
        String name = String.valueOf(stored.get("user"));
        byte[] handle = response.get("userHandle") == null || String.valueOf(response.get("userHandle")).isEmpty() ? null : WebAuthn.unb64(String.valueOf(response.get("userHandle")));
        if (handle != null && !name.equals(users.userForHandle(WebAuthn.b64(handle)))) throw new AppApiManager.ApiError(401, "Passkey passt nicht zum Benutzer");
        WebAuthn.Assertion assertion = WebAuthn.verify(WebAuthn.unb64(credentialId), WebAuthn.unb64(String.valueOf(response.get("clientDataJSON"))),
            WebAuthn.unb64(String.valueOf(response.get("authenticatorData"))), WebAuthn.unb64(String.valueOf(response.get("signature"))), handle,
            challenge.challenge(), rpId(), String.valueOf(stored.get("key")), ((Number) stored.get("alg")).intValue(), ((Number) stored.getOrDefault("count", 0L)).longValue());
        if (!users.enabled(name)) throw new AppApiManager.ApiError(401, "Benutzer deaktiviert");
        users.passkeyUsed(credentialId, assertion.signCount());
        Flow flow = flow(x, body, ip);
        String login = String.valueOf(body.getOrDefault("challenge", ""));
        if (!login.isEmpty()) {
            Pending p; synchronized (this) { p = pending.get(login); }
            if (p == null || !p.user().equals(name) || !p.stage().equals("mfa")) throw new AppApiManager.ApiError(401, "Passkey gehört nicht zu dieser Anmeldung");
            synchronized (this) { pending.values().remove(p); }
            flow = p.flow();
        }
        // A passkey with user verification is phishing-resistant multi-factor on its own; a pending password change still applies.
        if (users.mustChange(name)) { next(x, name, flow, "passkey"); return; }
        complete(x, name, flow, "passkey");
    }

    // ---------------------------------------------------------------- authenticated routes

    boolean privateRoute(HttpExchange x, AppApiSecurity.Key key, String path, String method, String ip, long rev) throws Exception {
        boolean user = key.id().startsWith("user:");
        String name = user ? key.label() : "";
        if (method.equals("GET") && path.equals("/v1/account")) { AppApiManager.send(x, 200, account(key, x)); return true; }
        if (method.equals("GET") && path.equals("/v1/sessions")) { AppApiManager.send(x, 200, sessions(key)); return true; }
        if (!method.equals("POST")) return false;
        switch (path) {
            case "/v1/logout" -> {
                if (user) users.updateSession(key.hash(), "logout", "");
                WebPanel.clearCookie(x);
                AppApiManager.send(x, 200, SimplexAdminSuite.appMap("ok", true));
            }
            case "/v1/account/migrate" -> migrate(x, key, ip);
            case "/v1/account/password" -> {
                requireUser(user);
                if (!api.rate("password:" + name, 6)) throw new AppApiManager.ApiError(429, "Bitte kurz warten.");
                Map<String, Object> b = AppApiManager.body(x, 2048);
                users.changePassword(name, String.valueOf(b.getOrDefault("current", "")), String.valueOf(b.getOrDefault("next", "")), key.hash());
                audit(name, "Passwort geändert", "Andere Geräte abgemeldet");
                AppApiManager.send(x, 200, SimplexAdminSuite.appMap("ok", true, "title", "Passwort geändert", "description", "Alle anderen Geräte dieses Benutzers wurden abgemeldet."));
            }
            case "/v1/account/totp/begin" -> {
                requireUser(user);
                if (users.mfa(name)) throw new IllegalArgumentException("Zwei-Faktor-Schutz ist bereits aktiv.");
                String secret = AccountCrypto.newTotpSecret();
                synchronized (this) { totpSetup.put(name, new String[]{secret, Long.toString(System.currentTimeMillis() + 600_000L)}); }
                String issuer = "Simplex " + Bukkit.getServer().getName();
                AppApiManager.send(x, 200, SimplexAdminSuite.appMap("secret", secret, "uri", AccountCrypto.otpauth(issuer.length() > 40 ? "Simplex Panel" : issuer, name, secret), "digits", 6, "period", AccountCrypto.STEP_SECONDS));
            }
            case "/v1/account/totp/enable" -> {
                requireUser(user);
                Map<String, Object> b = AppApiManager.body(x, 1024);
                String[] setup; synchronized (this) { setup = totpSetup.get(name); }
                if (setup == null || Long.parseLong(setup[1]) < System.currentTimeMillis()) throw new IllegalArgumentException("Einrichtung abgelaufen. Bitte neu starten.");
                if (!api.rate("totp-enable:" + name, 10)) throw new AppApiManager.ApiError(429, "Bitte kurz warten.");
                if (AccountCrypto.verifyTotp(setup[0], String.valueOf(b.getOrDefault("code", "")), System.currentTimeMillis()) < 0) throw new IllegalArgumentException("Code passt nicht. Uhrzeit des Geräts prüfen und den aktuellen Code eingeben.");
                users.enableTotp(name, setup[0]);
                synchronized (this) { totpSetup.remove(name); }
                List<String> codes = users.newRecovery(name);
                audit(name, "Zwei-Faktor-Schutz aktiviert", "");
                AppApiManager.send(x, 200, SimplexAdminSuite.appMap("ok", true, "recovery", codes, "title", "2FA aktiv", "description", "Wiederherstellungscodes jetzt sicher aufbewahren – sie werden nur einmal angezeigt."));
            }
            case "/v1/account/totp/disable" -> {
                requireUser(user); reauth(name, x);
                users.disableMfa(name);
                audit(name, "Zwei-Faktor-Schutz deaktiviert", "");
                AppApiManager.send(x, 200, SimplexAdminSuite.appMap("ok", true, "title", "2FA deaktiviert"));
            }
            case "/v1/account/recovery" -> {
                requireUser(user); reauth(name, x);
                if (!users.mfa(name)) throw new IllegalArgumentException("Zuerst Zwei-Faktor-Schutz aktivieren.");
                List<String> codes = users.newRecovery(name);
                audit(name, "Wiederherstellungscodes erneuert", "");
                AppApiManager.send(x, 200, SimplexAdminSuite.appMap("ok", true, "recovery", codes));
            }
            case "/v1/account/passkey/begin" -> {
                requireUser(user); requireRp();
                byte[] challenge = new byte[32]; new java.security.SecureRandom().nextBytes(challenge);
                String id = newPasskeyChallenge("register", name, challenge);
                List<Object> exclude = users.credentialIds(name).stream().map(c -> (Object) SimplexAdminSuite.appMap("type", "public-key", "id", c)).toList();
                AppApiManager.send(x, 200, SimplexAdminSuite.appMap("id", id, "publicKey", SimplexAdminSuite.appMap(
                    "challenge", WebAuthn.b64(challenge), "rp", SimplexAdminSuite.appMap("id", rpId(), "name", "Simplex Panel"),
                    "user", SimplexAdminSuite.appMap("id", users.handle(name), "name", name, "displayName", name),
                    "pubKeyCredParams", List.of(SimplexAdminSuite.appMap("type", "public-key", "alg", -7), SimplexAdminSuite.appMap("type", "public-key", "alg", -8), SimplexAdminSuite.appMap("type", "public-key", "alg", -257)),
                    "timeout", 180000, "attestation", "none", "excludeCredentials", exclude,
                    "authenticatorSelection", SimplexAdminSuite.appMap("residentKey", "required", "requireResidentKey", true, "userVerification", "required"))));
            }
            case "/v1/account/passkey/finish" -> {
                requireUser(user); requireRp();
                Map<String, Object> b = AppApiManager.body(x, 16384);
                Passkey challenge = takePasskey(String.valueOf(b.get("id")), "register");
                if (!challenge.user().equals(name)) throw new AppApiManager.ApiError(403, "Passkey-Anfrage gehört zu einem anderen Benutzer");
                Map<String, Object> credential = AppApiManager.object(b.get("credential")), response = AppApiManager.object(credential.get("response"));
                WebAuthn.Credential c = WebAuthn.register(WebAuthn.unb64(String.valueOf(response.get("clientDataJSON"))), WebAuthn.unb64(String.valueOf(response.get("attestationObject"))), challenge.challenge(), rpId());
                if (!WebAuthn.b64(c.id()).equals(String.valueOf(credential.get("id")))) throw new IllegalArgumentException("Passkey-ID passt nicht");
                String label = String.valueOf(b.getOrDefault("name", "")).trim();
                if (label.isEmpty()) { Map<String, Object> me = users.meta(key.hash()); label = String.valueOf(me.getOrDefault("model", me.getOrDefault("browser", "Passkey"))); }
                users.addPasskey(name, c, label, rpId());
                audit(name, "Passkey hinzugefügt", label);
                AppApiManager.send(x, 200, SimplexAdminSuite.appMap("ok", true, "title", "Passkey gespeichert", "description", "Ab jetzt kannst du dich über " + webUrl() + " mit diesem Passkey anmelden."));
            }
            case "/v1/account/passkey/update" -> {
                requireUser(user);
                Map<String, Object> b = AppApiManager.body(x, 1024);
                boolean delete = "delete".equals(b.get("operation"));
                users.updatePasskey(name, String.valueOf(b.getOrDefault("id", "")), String.valueOf(b.getOrDefault("name", "")), delete);
                if (delete) audit(name, "Passkey entfernt", "");
                AppApiManager.send(x, 200, SimplexAdminSuite.appMap("ok", true));
            }
            case "/v1/account/handoff" -> {
                requireUser(user);
                if (!api.rate("handoff:" + name, 6)) throw new AppApiManager.ApiError(429, "Bitte kurz warten.");
                String code; long now = System.currentTimeMillis();
                synchronized (this) { exchanges.values().removeIf(e -> e.expires() < now); code = "sasx_" + AccountCrypto.token(32); exchanges.put(AppApiSecurity.hash(code), new Exchange(name, now + 120_000L, true)); }
                AppApiManager.send(x, 200, SimplexAdminSuite.appMap("code", code, "url", webUrl(), "expiresIn", 120));
            }
            case "/v1/sessions/update" -> updateSession(x, key);
            case "/v1/web/domain" -> {
                if (!manager(key) || user && !users.owner(name)) throw new AppApiManager.ApiError(403, "Nur der Hauptadmin kann die Domain ändern.");
                Map<String, Object> b = AppApiManager.body(x, 1024);
                String domain = validateDomain(String.valueOf(b.getOrDefault("domain", "")));
                String actor = user ? name : key.label();
                api.onMain(rev, () -> { plugin.getConfig().set("app-api.public-domain", domain); plugin.saveConfig(); plugin.suiteAudit("Simplex Panel/" + actor, "Webpanel-Domain", domain.isEmpty() ? "entfernt" : domain); return null; });
                AppApiManager.send(x, 200, SimplexAdminSuite.appMap("ok", true, "domain", domain, "url", webUrl(), "title", domain.isEmpty() ? "Domain entfernt" : "Domain gespeichert",
                    "description", domain.isEmpty() ? "Passkeys sind ohne Domain nicht verfügbar; 2FA bleibt aktiv." : "Richte DNS und HTTPS für " + domain + " ein. Danach funktionieren Passkeys im Browser und in der App."));
            }
            default -> { return false; }
        }
        return true;
    }

    private static void requireUser(boolean user) { if (!user) throw new AppApiManager.ApiError(409, "Dieser Zugang nutzt noch einen API-Schlüssel. Zuerst unter Konto & Sicherheit zu Benutzername und Passwort wechseln."); }
    private void reauth(String name, HttpExchange x) throws Exception {
        if (!api.rate("reauth:" + name, 6)) throw new AppApiManager.ApiError(429, "Bitte kurz warten.");
        Map<String, Object> b = AppApiManager.body(x, 1024);
        if (users.verifyPassword(name, String.valueOf(b.getOrDefault("password", ""))) == null) throw new AppApiManager.ApiError(401, "Passwort ist falsch");
    }
    /** Main admin (owner), a classic full API key, or a user with devices.manage / "*". */
    boolean manager(AppApiSecurity.Key key) { return key.id().startsWith("user:") ? users.owner(key.label()) || users.allows(key, "devices.manage") : key.scope().equals("full"); }

    private void migrate(HttpExchange x, AppApiSecurity.Key key, String ip) throws Exception {
        if (key.id().startsWith("user:") || !key.scope().equals("full")) throw new AppApiManager.ApiError(403, "Nur ein bestehender Hauptschlüssel (full) kann zum Hauptadmin-Konto wechseln.");
        if (!api.rate("migrate:" + key.id(), 5)) throw new AppApiManager.ApiError(429, "Bitte kurz warten.");
        Map<String, Object> b = AppApiManager.body(x, 2048);
        String name = AppUsers.username(String.valueOf(b.getOrDefault("username", "")));
        users.createOwner(name, String.valueOf(b.getOrDefault("password", "")));
        // Keys are only used by the app, so the new session describes this app installation.
        Map<String, Object> device = device(x, ip, false);
        device.put("via", "Migration");
        String token = users.createSession(name, System.currentTimeMillis(), AppUsers.APP_SESSION, device);
        boolean revoke = Boolean.TRUE.equals(b.get("revokeKey"));
        if (revoke) { security.revoke(key.id()); }
        audit(name, "Hauptadmin-Konto aus API-Schlüssel erstellt", key.label() + (revoke ? " · Schlüssel widerrufen" : " · Schlüssel bleibt gültig"));
        AppApiManager.send(x, 200, SimplexAdminSuite.appMap("ok", true, "token", token, "username", name, "owner", true, "expiresIn", AppUsers.APP_SESSION / 1000, "keyRevoked", revoke,
            "title", "Hauptadmin-Konto erstellt", "description", "Diese App nutzt jetzt " + name + ". Zwei-Faktor-Schutz und Passkeys kannst du jetzt einrichten."));
    }

    private Map<String, Object> account(AppApiSecurity.Key key, HttpExchange x) {
        boolean user = key.id().startsWith("user:");
        String name = user ? key.label() : "";
        Map<String, Object> out = SimplexAdminSuite.appMap("kind", user ? "user" : "key", "username", name, "label", key.label(), "scope", key.scope(),
            "owner", user ? users.owner(name) : key.scope().equals("full") && users.ownerName().isEmpty(), "ownerExists", !users.ownerName().isEmpty(),
            "manager", manager(key), "domain", domain(), "rpId", rpId(), "webUrl", webUrl(), "passkeysAvailable", !rpId().isEmpty(),
            "canMigrate", !user && key.scope().equals("full") && users.ownerName().isEmpty(), "browser", WebPanel.cookie(x) != null);
        if (user) {
            out.put("mfa", users.mfa(name)); out.put("recoveryLeft", users.recoveryLeft(name));
            out.put("passkeys", users.passkeys(name).stream().map(p -> { Map<String, Object> r = new LinkedHashMap<>(p); r.put("active", rpId().equals(p.get("rp"))); return (Object) r; }).toList());
            out.put("permissions", users.permissions(name).stream().filter(s -> !s.isBlank()).sorted().toList());
            out.put("session", key.hash().substring(0, 24));
        } else {
            out.put("mfa", false); out.put("passkeys", List.of()); out.put("permissions", List.of("*"));
        }
        return out;
    }

    private Map<String, Object> sessions(AppApiSecurity.Key key) {
        boolean user = key.id().startsWith("user:"), manage = manager(key);
        List<Map<String, Object>> rows = users.sessionRows(manage ? null : key.label());
        if (user) for (Map<String, Object> r : rows) r.put("current", key.hash().startsWith(String.valueOf(r.get("id"))));
        long now = System.currentTimeMillis();
        List<Object> keys = new ArrayList<>();
        if (manage) for (AppApiSecurity.Key k : security.keys()) {
            Map<String, Object> m = users.keyMeta(k.id()); Map<String, Object> r = new LinkedHashMap<>(m);
            r.put("id", k.id()); r.put("user", k.label()); r.put("kind", "key"); r.put("scope", k.scope()); r.put("created", k.created());
            r.putIfAbsent("seen", 0L); r.putIfAbsent("locked", false); r.put("current", !user && k.id().equals(key.id())); keys.add(r);
        }
        int active = 0; Map<String, Integer> byUser = new TreeMap<>();
        for (Map<String, Object> r : rows) if (r.get("seen") instanceof Number n && now - n.longValue() < 300_000L) { active++; byUser.merge(String.valueOf(r.get("user")), 1, Integer::sum); }
        for (Object o : keys) if (((Map<?, ?>) o).get("seen") instanceof Number n && now - n.longValue() < 300_000L) { active++; byUser.merge(String.valueOf(((Map<?, ?>) o).get("user")), 1, Integer::sum); }
        return SimplexAdminSuite.appMap("items", rows, "keys", keys, "manage", manage, "active", active,
            "activeUsers", byUser.entrySet().stream().map(e -> (Object) SimplexAdminSuite.appMap("user", e.getKey(), "devices", e.getValue())).toList(), "at", now);
    }

    private void updateSession(HttpExchange x, AppApiSecurity.Key key) throws Exception {
        Map<String, Object> b = AppApiManager.body(x, 1024);
        String id = String.valueOf(b.getOrDefault("id", "")), op = String.valueOf(b.getOrDefault("operation", "")), label = String.valueOf(b.getOrDefault("name", ""));
        boolean user = key.id().startsWith("user:"), manage = manager(key);
        String actor = key.label();
        if ("key".equals(b.get("kind"))) {
            if (!manage) throw new AppApiManager.ApiError(403, "Nur der Hauptadmin verwaltet API-Schlüssel.");
            if (security.keys().stream().noneMatch(k -> k.id().equals(id))) throw new IllegalArgumentException("Schlüssel nicht gefunden");
            if (!user && id.equals(key.id()) && !op.equals("rename")) throw new IllegalArgumentException("Den eigenen Schlüssel bitte nicht sperren oder widerrufen – sonst verliert diese App den Zugang.");
            if (op.equals("logout")) { security.revoke(id); } else users.updateKey(id, op, label);
            audit(actor, "API-Schlüssel " + verb(op), id);
            AppApiManager.send(x, 200, SimplexAdminSuite.appMap("ok", true, "title", "Schlüssel " + verb(op)));
            return;
        }
        String digest = users.digestFor(id);
        if (digest == null) throw new IllegalArgumentException("Gerät nicht gefunden oder bereits abgemeldet");
        String owner = users.sessionUser(digest);
        boolean own = user && owner.equals(key.label());
        if (!own && !manage) throw new AppApiManager.ApiError(403, "Nur eigene Geräte verwalten – der Hauptadmin verwaltet alle.");
        if (users.owner(owner) && !own && user && !users.owner(key.label())) throw new AppApiManager.ApiError(403, "Geräte des Hauptadmins kann nur der Hauptadmin verwalten.");
        if (op.equals("lock") || op.equals("unlock")) {
            if (!manage) throw new AppApiManager.ApiError(403, "Sperren kann nur der Hauptadmin.");
            if (user && digest.equals(key.hash())) throw new IllegalArgumentException("Das aktuell benutzte Gerät kann nicht gesperrt werden.");
        }
        users.updateSession(digest, op, label);
        audit(actor, "Gerät " + verb(op), owner + " · " + id);
        if (op.equals("logout") && user && digest.equals(key.hash())) WebPanel.clearCookie(x);
        AppApiManager.send(x, 200, SimplexAdminSuite.appMap("ok", true, "title", "Gerät " + verb(op)));
    }
    private static String verb(String op) { return switch (op) { case "rename" -> "umbenannt"; case "lock" -> "gesperrt"; case "unlock" -> "freigegeben"; case "logout" -> "abgemeldet"; default -> "geändert"; }; }

    private void audit(String user, String action, String detail) {
        if (!plugin.isEnabled()) return;
        try { Bukkit.getScheduler().runTask(plugin, () -> plugin.suiteAudit("Simplex Panel/" + user, action, detail)); } catch (RuntimeException ignored) { }
    }
}
