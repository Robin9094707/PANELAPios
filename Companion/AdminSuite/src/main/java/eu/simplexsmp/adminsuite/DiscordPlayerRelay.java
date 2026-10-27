package eu.simplexsmp.adminsuite;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Serial, bounded delivery queue; isolated from gateway heartbeats and admin interactions. */
final class DiscordPlayerRelay {
    record Settings(String token, String channel, boolean webhook, int capacity, int maxAgeSeconds) { }
    record Reply(int status, String body, String retryAfter) { }
    interface Transport { Reply send(String method, String path, String body, boolean botAuth) throws Exception; }
    private final Settings settings;
    private final RelayAvatarService avatars;
    private final Transport transport;
    private final Consumer<String> logger;
    private final DiscordDiagnostics diagnostics;
    private final ThreadPoolExecutor queue;
    private final AtomicLong sent = new AtomicLong(), failed = new AtomicLong(), dropped = new AtomicLong(), retried = new AtomicLong();
    private volatile String lastError = "keiner";
    private volatile String webhookPath = "";
    private long nextWebhookAttempt;
    private long blockedUntil;
    private long warnedAt;
    private String botId = "";

    DiscordPlayerRelay(Settings settings, RelayAvatarService avatars, Consumer<String> logger) {
        this(settings, avatars, logger, httpTransport(settings.token()));
    }

    DiscordPlayerRelay(Settings settings, RelayAvatarService avatars, Consumer<String> logger, Transport transport) {
        this.settings = settings; this.avatars = avatars; this.logger = logger; this.transport = transport;
        this.diagnostics = new DiscordDiagnostics(logger);
        queue = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(8, Math.min(2048, settings.capacity()))), r -> {
                    Thread t = new Thread(r, "Simplex-Player-Relay"); t.setDaemon(true); return t;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    static Transport httpTransport(String token) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        return (method, path, body, botAuth) -> {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("https://discord.com/api/v10" + path))
                    .timeout(Duration.ofSeconds(8)).header("User-Agent", "SimplexAdminSuite/7.12.1 (simplexsmp.eu)")
                    .header("Content-Type", "application/json; charset=utf-8");
            if (botAuth) builder.header("Authorization", "Bot " + token);
            builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new Reply(response.statusCode(), response.body(), response.headers().firstValue("Retry-After").orElse(""));
        };
    }

    void chat(RelayAvatarService.Identity identity, String name, String decoration, String text) {
        String clean = RelayText.discord(text, 1700);
        if (clean.isBlank()) return;
        String author = RelayText.username((decoration == null || decoration.isBlank() ? "" : decoration + " ") + name);
        String fallback = "**" + RelayText.discord(author, 150) + ":** " + clean;
        // Start a lookup now, without waiting on the delivery queue.
        if (settings.webhook()) avatars.message(identity);
        enqueue(() -> {
            if (settings.webhook() && ensureWebhook()) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("username", author);
                payload.put("content", clean);
                payload.put("allowed_mentions", Map.of("parse", List.of()));
                String image = avatars.avatar(identity);
                if (!image.isBlank()) payload.put("avatar_url", image);
                Reply response = send("POST", webhookPath + "?wait=true", json(payload), false);
                if (success(response)) { sent.incrementAndGet(); return; }
                // Only definite rejection permits fallback. Never duplicate an ambiguous timeout/5xx.
                if (response.status() == 401 || response.status() == 403 || response.status() == 404 || response.status() == 400) {
                    webhookPath = "";
                    nextWebhookAttempt = System.currentTimeMillis() + 300_000L;
                    warn("Player webhook HTTP " + response.status() + "; bot fallback active");
                } else { failure("Player webhook HTTP " + response.status()); return; }
            }
            deliverBot(Map.of("content", RelayText.limit(fallback, 2000), "allowed_mentions", Map.of("parse", List.of())));
        });
    }

    void bot(Map<String, Object> payload) {
        // Snapshot nested maps into JSON while still on the caller thread.
        String body = json(payload);
        enqueue(() -> record(send("POST", "/channels/" + settings.channel() + "/messages", body, true)));
    }

    private void deliverBot(Map<String, Object> payload) throws Exception {
        record(send("POST", "/channels/" + settings.channel() + "/messages", json(payload), true));
    }
    private void record(Reply reply) { if (success(reply)) sent.incrementAndGet(); else failure("Relay HTTP " + reply.status()); }
    private static boolean success(Reply reply) { return reply.status() >= 200 && reply.status() < 300; }
    private static String json(Object value) { return DiscordBotManager.Json.stringify(value); }

    private boolean ensureWebhook() throws Exception {
        if (!webhookPath.isBlank()) return true;
        if (System.currentTimeMillis() < nextWebhookAttempt) return false;
        nextWebhookAttempt = System.currentTimeMillis() + 300_000L;
        if (botId.isBlank()) {
            Reply me = send("GET", "/users/@me", null, true);
            if (!success(me)) { warn("Bot identity HTTP " + me.status()); return false; }
            botId = RelayText.string(RelayText.object(DiscordBotManager.Json.parse(me.body())).get("id"));
            if (!botId.matches("[0-9]+")) return false;
        }
        String route = "/channels/" + settings.channel() + "/webhooks";
        Reply list = send("GET", route, null, true);
        if (!success(list)) { warn("Webhook access HTTP " + list.status() + "; Recht 'Webhooks verwalten' prüfen. Bot-Fallback aktiv."); return false; }
        Object parsed = DiscordBotManager.Json.parse(list.body());
        if (!(parsed instanceof List<?> hooks)) return false;
        for (Object item : hooks) {
            Map<?, ?> hook = RelayText.object(item);
            if ("Simplex Chat Relay".equals(hook.get("name"))
                    && botId.equals(RelayText.object(hook.get("user")).get("id")) && acceptWebhook(hook)) return true;
        }
        Reply created = send("POST", route, json(Map.of("name", "Simplex Chat Relay")), true);
        if (!success(created)) { warn("Webhook creation HTTP " + created.status() + "; bot fallback active"); return false; }
        return acceptWebhook(RelayText.object(DiscordBotManager.Json.parse(created.body())));
    }

    private boolean acceptWebhook(Map<?, ?> hook) {
        String id = RelayText.string(hook.get("id")), token = RelayText.string(hook.get("token"));
        if (!settings.channel().equals(hook.get("channel_id")) || !id.matches("[0-9]+") || !token.matches("[A-Za-z0-9_.-]+")) return false;
        webhookPath = "/webhooks/" + id + "/" + token;
        lastError = "keiner";
        return true;
    }

    private Reply send(String method, String path, String body, boolean auth) throws Exception {
        for (int attempt = 0; ; attempt++) {
            long wait = blockedUntil - System.currentTimeMillis();
            if (wait > 15_000L) return new Reply(429, "", "");
            if (wait > 0) Thread.sleep(wait);
            Reply reply;
            try{reply=transport.send(method,path,body,auth);}
            catch(Exception error){diagnostics.failure(method+" "+DiscordDiagnostics.route(path),error.getClass().getSimpleName()+"; relay request could not be completed");throw error;}
            if(!success(reply))diagnostics.failure(method+" "+DiscordDiagnostics.route(path),"HTTP "+reply.status()+" • "+DiscordDiagnostics.detail(reply.body(),settings.token()));
            if (reply.status() != 429) return reply;
            long delay = retryMillis(reply.body(), reply.retryAfter());
            blockedUntil = System.currentTimeMillis() + delay;
            if (attempt >= 2 || delay > 15_000L) return reply;
            retried.incrementAndGet();
        }
    }

    static long retryMillis(String body, String header) {
        double seconds = 1D;
        try { seconds = Double.parseDouble(header); } catch (RuntimeException ignored) { }
        try {
            Object value = RelayText.object(DiscordBotManager.Json.parse(body)).get("retry_after");
            if (value instanceof Number n) seconds = Math.max(seconds, n.doubleValue());
        } catch (RuntimeException ignored) { }
        if (!Double.isFinite(seconds) || seconds < 0) seconds = 1D;
        return (long) Math.ceil(Math.min(86_400D, Math.max(0.1D, seconds)) * 1000D) + 50L;
    }

    private interface Work { void run() throws Exception; }
    private void enqueue(Work work) {
        if (!settings.channel().matches("[0-9]+")) return;
        long created = System.currentTimeMillis();
        try {
            queue.execute(() -> {
                if (System.currentTimeMillis() - created > Math.max(10, settings.maxAgeSeconds()) * 1000L) {
                    dropped.incrementAndGet(); warn("Expired relay message dropped"); return;
                }
                try { work.run(); }
                catch (InterruptedException ignored) { Thread.currentThread().interrupt(); failure("Relay stopped"); }
                catch (Exception error) {
                    // Exception messages can contain webhook tokens. Log only the exception type.
                    failure("Relay connection: " + error.getClass().getSimpleName());
                }
            });
        } catch (RejectedExecutionException ignored) { dropped.incrementAndGet(); warn("Relay queue full or stopped"); }
    }

    private void failure(String message) { failed.incrementAndGet(); warn(message); }
    private synchronized void warn(String message) {
        lastError = message;
        long now = System.currentTimeMillis();
        if (now - warnedAt > 60_000L) { warnedAt = now; logger.accept(message); }
    }

    String summary() {
        return "Spieler-Webhook: " + (!settings.webhook() ? "aus" : webhookPath.isBlank() ? "wartet / Bot-Fallback" : "bereit")
                + " | Warteschlange: " + queue.getQueue().size() + " | Gesendet: " + sent.get()
                + " | Fehler: " + failed.get() + " | Verworfen: " + dropped.get() + " | Wiederholt: " + retried.get()
                + " | Xbox-Bilder: " + avatars.cached() + " | Bildfehler: " + avatars.failures() + " | Letzter Fehler: " + lastError;
    }
    long sentCount() { return sent.get(); }
    long failedCount() { return failed.get(); }
    long droppedCount() { return dropped.get(); }
    String avatar(RelayAvatarService.Identity identity){return avatars.avatar(identity);}
    void clearAvatars() { avatars.clear(); }
    void warm(RelayAvatarService.Identity identity) { avatars.avatar(identity); }
    void stop(boolean drain) {
        queue.shutdown();
        if (drain) try { queue.awaitTermination(2, TimeUnit.SECONDS); }
        catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        dropped.addAndGet(queue.shutdownNow().size());
        avatars.close();
    }
}
