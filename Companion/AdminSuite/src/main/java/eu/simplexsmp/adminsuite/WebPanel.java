package eu.simplexsmp.adminsuite;

import com.sun.net.httpserver.HttpExchange;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 9.8.0: the browser panel, served from the JAR on the same HTTPS port as the app API.
 * Static files only; every piece of data still goes through the authenticated /v1 API. Browser sessions use an
 * HttpOnly, Secure, SameSite=Strict cookie plus a same-origin check and a custom header on every API call.
 */
final class WebPanel {
    static final String COOKIE = "__Host-sas_session";
    static final String HEADER = "X-Simplex-Web";
    private static final Map<String, String> TYPES = Map.of(
        "html", "text/html; charset=utf-8", "css", "text/css; charset=utf-8", "js", "text/javascript; charset=utf-8",
        "svg", "image/svg+xml", "png", "image/png", "webmanifest", "application/manifest+json; charset=utf-8", "json", "application/json; charset=utf-8");
    private static final String CSP = "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data: blob:; media-src 'self' blob:; "
        + "connect-src 'self'; font-src 'self'; manifest-src 'self'; worker-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'";
    private final Map<String, byte[]> cache = new ConcurrentHashMap<>();
    private final String version;

    WebPanel(String version) { this.version = version; }

    boolean serves(String path) {
        return path.equals("/") || path.equals("/index.html") || path.equals("/manifest.webmanifest") || path.equals("/favicon.ico") || path.equals("/sw.js")
            || path.equals("/apple-touch-icon.png") || path.matches("/assets/[a-z0-9-]{1,40}\\.(css|js|svg|png)");
    }

    void serve(HttpExchange x, String path) throws Exception {
        String name = switch (path) {
            case "/", "/index.html" -> "index.html";
            case "/favicon.ico" -> "icon.svg";
            case "/apple-touch-icon.png" -> "icon-180.png";
            case "/manifest.webmanifest" -> "manifest.webmanifest";
            case "/sw.js" -> "sw.js";
            default -> path.substring("/assets/".length());
        };
        byte[] body = load(name);
        if (body == null) { error(x, 404, "Nicht gefunden"); return; }
        String extension = name.substring(name.lastIndexOf('.') + 1);
        var h = x.getResponseHeaders();
        h.set("Content-Type", TYPES.getOrDefault(extension, "application/octet-stream"));
        securityHeaders(x);
        // index.html references assets with ?v=<suite version>, so assets may be cached; the shell never is.
        // 9.9.0: the service worker must be revalidated on every start so updates of the JAR reach installed home-screen apps.
        boolean shell = name.equals("index.html") || name.endsWith(".webmanifest") || name.equals("sw.js");
        h.set("Cache-Control", shell ? "no-cache" : name.endsWith(".png") && !path.contains("?") ? "public, max-age=86400" : "public, max-age=604800, immutable");
        if (name.equals("sw.js")) h.set("Service-Worker-Allowed", "/");
        String etag = "\"" + AppApiSecurity.hash(version + "|" + name).substring(0, 20) + "\"";
        h.set("ETag", etag);
        if (etag.equals(x.getRequestHeaders().getFirst("If-None-Match"))) { x.sendResponseHeaders(304, -1); return; }
        if (x.getRequestMethod().equals("HEAD")) { x.sendResponseHeaders(200, -1); return; }
        x.sendResponseHeaders(200, body.length);
        try (var out = x.getResponseBody()) { out.write(body); }
    }

    private byte[] load(String name) throws Exception {
        byte[] cached = cache.get(name);
        if (cached != null) return cached;
        try (InputStream in = WebPanel.class.getResourceAsStream("/web/" + name)) {
            if (in == null) return null;
            byte[] data = in.readAllBytes();
            if (name.equals("index.html") || name.equals("sw.js")) data = new String(data, StandardCharsets.UTF_8).replace("{{VERSION}}", version).getBytes(StandardCharsets.UTF_8);
            cache.put(name, data);
            return data;
        }
    }

    static void securityHeaders(HttpExchange x) {
        var h = x.getResponseHeaders();
        h.set("Content-Security-Policy", CSP);
        h.set("X-Content-Type-Options", "nosniff");
        h.set("X-Frame-Options", "DENY");
        h.set("Referrer-Policy", "no-referrer");
        h.set("Cross-Origin-Opener-Policy", "same-origin");
        h.set("Cross-Origin-Resource-Policy", "same-origin");
        h.set("Permissions-Policy", "camera=(), geolocation=(), microphone=(self), payment=(), usb=()");
    }

    private static void error(HttpExchange x, int code, String text) throws Exception {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        securityHeaders(x);
        x.sendResponseHeaders(code, body.length);
        try (var out = x.getResponseBody()) { out.write(body); }
    }

    /** Session cookie value, or null. Only the exact __Host- cookie is accepted. */
    static String cookie(HttpExchange x) {
        List<String> headers = x.getRequestHeaders().get("Cookie");
        if (headers == null) return null;
        for (String header : headers) for (String part : header.split(";")) {
            String p = part.trim();
            if (p.startsWith(COOKIE + "=")) { String v = p.substring(COOKIE.length() + 1); return v.startsWith("sasu_") && v.length() <= 160 ? v : null; }
        }
        return null;
    }

    static void setCookie(HttpExchange x, String token, long maxAgeSeconds) {
        x.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + token + "; Path=/; Secure; HttpOnly; SameSite=Strict" + (maxAgeSeconds > 0 ? "; Max-Age=" + maxAgeSeconds : ""));
    }

    static void clearCookie(HttpExchange x) { x.getResponseHeaders().add("Set-Cookie", COOKIE + "=; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age=0"); }

    /** Readable browser/OS from a User-Agent; only for the device list, never for decisions. */
    static Map<String, String> browser(String ua) {
        String agent = ua == null ? "" : ua;
        String os = agent.contains("iPhone") ? "iOS" : agent.contains("iPad") ? "iPadOS" : agent.contains("Android") ? "Android"
            : agent.contains("Mac OS X") || agent.contains("Macintosh") ? "macOS" : agent.contains("Windows") ? "Windows" : agent.contains("CrOS") ? "ChromeOS" : agent.contains("Linux") ? "Linux" : "";
        String browser = agent.contains("Edg/") ? "Edge" : agent.contains("OPR/") ? "Opera" : agent.contains("Firefox/") ? "Firefox" : agent.contains("SamsungBrowser") ? "Samsung Internet"
            : agent.contains("Chrome/") || agent.contains("CriOS/") ? "Chrome" : agent.contains("Safari/") ? "Safari" : "Browser";
        String model = agent.contains("iPhone") ? "iPhone" : agent.contains("iPad") ? "iPad" : agent.contains("Android") ? "Android-Gerät" : agent.contains("Macintosh") ? "Mac" : agent.contains("Windows") ? "PC" : "";
        return Map.of("browser", browser, "os", os, "model", model);
    }
}
