package eu.simplexsmp.adminsuite;

import java.util.*;
import java.util.function.Consumer;

/** Log API failures without request bodies, bot/webhook tokens or verification codes. */
final class DiscordDiagnostics {
    private record Notice(long at, int suppressed) { }
    private final Map<String,Notice> notices = new LinkedHashMap<>();
    private final Consumer<String> logger;
    DiscordDiagnostics(Consumer<String> logger) { this.logger = logger; }
    static String route(String path) {
        return path.split("\\?",2)[0].replaceAll("(/(?:webhooks|interactions)/[^/]+/)[^/]+", "$1[redacted]");
    }
    static String safe(String text, String secret) {
        String clean = text == null ? "" : text;
        if (secret != null && !secret.isBlank()) clean = clean.replace(secret, "[redacted]");
        clean = clean.replaceAll("(?i)/link\\s+[0-9]{6}", "/link [redacted]")
                .replaceAll("(?<![0-9])[0-9]{6}(?![0-9])", "[redacted]")
                .replaceAll("[\\r\\n\\p{Cntrl}]", " ");
        return clean.substring(0, Math.min(1600, clean.length()));
    }
    static String detail(String body, String secret) {
        try {
            Object raw = DiscordBotManager.Json.parse(body);
            if (!(raw instanceof Map<?,?> data)) return "No structured Discord error returned";
            StringBuilder out = new StringBuilder();
            if (data.get("code") instanceof Number code) out.append("code=").append(code).append(" ");
            if (data.get("message") instanceof String message) out.append(message);
            fields(data.get("errors"), "", out);
            return safe(out.toString(), secret);
        } catch (RuntimeException ignored) { return "No structured Discord error returned"; }
    }
    private static void fields(Object raw, String path, StringBuilder out) {
        if (out.length() > 1400 || !(raw instanceof Map<?,?> data)) return;
        if (data.get("_errors") instanceof List<?> errors) for (Object error : errors)
            if (error instanceof Map<?,?> e && e.get("message") instanceof String message)
                out.append("; ").append(path).append(": ").append(message);
        for (var entry : data.entrySet()) if (!entry.getKey().equals("_errors"))
            fields(entry.getValue(), path.isBlank() ? entry.getKey().toString() : path + "." + entry.getKey(), out);
    }
    synchronized void failure(String operation, String detail) {
        long now = System.currentTimeMillis(); String key = operation + " " + detail;
        notices.entrySet().removeIf(e -> now - e.getValue().at > 300_000);
        Notice old = notices.get(key);
        if (old != null && now - old.at < 30_000) { notices.put(key, new Notice(old.at, old.suppressed + 1)); return; }
        if (notices.size() >= 256) notices.remove(notices.keySet().iterator().next());
        notices.put(key, new Notice(now,0));
        logger.accept("Discord " + operation + " failed: " + detail + (old != null && old.suppressed > 0 ? " (" + old.suppressed + " repeated failures suppressed)" : ""));
    }
}
