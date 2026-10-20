package eu.simplexsmp.adminsuite;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/** No Bukkit calls here. Xbox lookups never block chat or the server thread. */
final class RelayAvatarService implements AutoCloseable {
    record Identity(UUID uuid, String name, boolean bedrock, String xuid) { }
    private record Entry(String url, long expires, long refreshed, long nextRefresh) { }
    interface Lookup { String fetch(String xuid) throws Exception; }
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final java.util.Set<String> pending = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor worker;
    private final Lookup lookup;
    private final long ttl;
    private final boolean enabled;
    static final String LEGACY_JAVA_URL = "https://crafatar.com/avatars/{uuid}?size=128&overlay";
    static final String DEFAULT_JAVA_URL = "https://mc-heads.net/avatar/{identifier}/128.png";
    private final String javaTemplate;
    private java.nio.file.Path storage;
    private int refreshMessages;
    private final Map<String, Integer> messages = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicBoolean saving = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean closed;
    private volatile long lastPersistAt;
    private final AtomicLong generation = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();

    RelayAvatarService(boolean enabled, int hours, String javaTemplate) {
        this(enabled, hours, javaTemplate, defaultLookup());
    }

    RelayAvatarService(boolean enabled, int hours, String javaTemplate, Lookup lookup) {
        this.enabled = enabled;
        this.ttl = TimeUnit.HOURS.toMillis(Math.max(1, Math.min(168, hours)));
        this.javaTemplate = javaTemplate == null ? DEFAULT_JAVA_URL : javaTemplate;
        this.lookup = lookup;
        worker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(64), r -> {
            Thread thread = new Thread(r, "Simplex-Relay-Avatars"); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    RelayAvatarService(boolean enabled, int hours, String template, java.nio.file.Path storage, int refreshMessages) {
        this(enabled, hours, template, defaultLookup());
        this.storage = storage;
        this.refreshMessages = Math.max(0, Math.min(1000, refreshMessages));
        load();
    }

    RelayAvatarService(boolean enabled, int hours, String template, Lookup lookup, java.nio.file.Path storage, int refreshMessages) {
        this(enabled, hours, template, lookup);
        this.storage = storage; this.refreshMessages = Math.max(0, Math.min(1000, refreshMessages)); load();
    }

    static Lookup defaultLookup() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        return xuid -> {
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://playerdb.co/api/player/xbox/" + xuid))
                    .timeout(Duration.ofSeconds(5)).header("User-Agent", "SimplexAdminSuite/7.4.0 (simplexsmp.eu)")
                    .header("Accept", "application/json").GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new java.io.IOException("Avatar HTTP " + response.statusCode());
            return parseXboxAvatar(response.body(), xuid);
        };
    }

    static String parseXboxAvatar(String json, String expectedXuid) {
        Map<?, ?> root = RelayText.object(DiscordBotManager.Json.parse(json));
        if (!Boolean.TRUE.equals(root.get("success"))) return "";
        Map<?, ?> player = RelayText.object(RelayText.object(root.get("data")).get("player"));
        // Do not attach another account's image to this player.
        Object id = player.get("id");
        if (id == null || !expectedXuid.equals(String.valueOf(id))) return "";
        return RelayText.httpsImage(RelayText.string(player.get("avatar")));
    }

    private static String key(Identity i) {
        return i.bedrock() ? "bedrock:" + i.xuid() : "java:" + i.uuid();
    }

    /** Called exactly once per outgoing Minecraft chat message, never by cache warmups. */
    void message(Identity identity) {
        if (identity == null || closed) return;
        messages.compute(key(identity), (k, n) -> Math.min(1000, n == null ? 1 : n + 1));
        avatar(identity); persistLater();
    }

    String avatar(Identity identity) {
        if (identity == null || identity.uuid() == null || closed) return "";
        if (identity.bedrock() && (!enabled || identity.xuid() == null || !identity.xuid().matches("[0-9]{1,20}"))) return "";
        String key = key(identity);
        Entry entry = cache.get(key);
        long now = System.currentTimeMillis();
        boolean due = entry == null || entry.expires() <= now;
        // Respect API failure cooldowns, and never refresh more often than once per minute.
        if (entry != null && !entry.url().isBlank() && refreshMessages > 0
                && messages.getOrDefault(key, 0) >= refreshMessages && now >= entry.nextRefresh()) due = true;
        if (!identity.bedrock()) {
            String base = javaAvatar(identity, javaTemplate);
            boolean providerChanged = entry != null && !entry.url().equals(base) && !entry.url().startsWith(base + "?sas_avatar=");
            if (due || providerChanged) {
                // This is a URL cache. Discord downloads the image; no per-message Mojang request occurs here.
                String url = base;
                if (entry != null && storage != null && (DEFAULT_JAVA_URL.equals(javaTemplate) || LEGACY_JAVA_URL.equals(javaTemplate)))
                    url += "?sas_avatar=" + now / 1000L;
                if (cache.size() >= 2048 && !cache.containsKey(key)) evictOne();
                cache.put(key, entry = new Entry(url, now + ttl, now, now + 60_000L)); messages.put(key, 0); persistLater();
            }
            return entry.url();
        }
        if (due && pending.add(key)) {
            Entry previous = entry;
            long requestGeneration = generation.get();
            try {
                worker.execute(() -> {
                    try {
                        String image = RelayText.httpsImage(lookup.fetch(identity.xuid()));
                        if (image.isBlank()) throw new java.io.IOException("No Xbox avatar");
                        if (!closed && generation.get() == requestGeneration) {
                            if (cache.size() >= 2048 && !cache.containsKey(key)) evictOne();
                            cache.put(key, new Entry(image, System.currentTimeMillis() + ttl, System.currentTimeMillis(), System.currentTimeMillis() + 60_000L)); messages.put(key, 0);
                        }
                    } catch (Exception error) {
                        failures.incrementAndGet();
                        if (!closed && generation.get() == requestGeneration) {
                            if (cache.size() >= 2048 && !cache.containsKey(key)) evictOne();
                            cache.put(key, new Entry(previous == null ? "" : previous.url(), System.currentTimeMillis() + 600_000L, System.currentTimeMillis(), System.currentTimeMillis() + 600_000L));
                            messages.put(key, 0);
                        }
                        if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                    } finally { pending.remove(key); persistLater(); }
                });
            } catch (RejectedExecutionException ignored) { pending.remove(key); }
        }
        return entry == null ? "" : entry.url();
    }

    private void evictOne() {
        cache.entrySet().stream().min(java.util.Comparator.comparingLong(e -> e.getValue().refreshed()))
                .ifPresent(e -> { cache.remove(e.getKey()); messages.remove(e.getKey()); });
    }
    private void load() {
        if (storage == null || !java.nio.file.Files.isRegularFile(storage)) return;
        java.util.Properties props = new java.util.Properties();
        try (java.io.Reader in = java.nio.file.Files.newBufferedReader(storage)) {
            props.load(in);
            for (String name : props.stringPropertyNames()) if (name.endsWith(".url") && cache.size() < 2048) {
                String key = name.substring(0, name.length() - 4);
                if (!key.matches("(?:java:[0-9a-fA-F-]{36}|bedrock:[0-9]{1,20})")) continue;
                try {
                    String url = RelayText.httpsImage(props.getProperty(name));
                    if (url.isBlank() && !props.getProperty(name, "").isBlank()) continue;
                    long expires = Long.parseLong(props.getProperty(key + ".expires", "0"));
                    long refreshed = Long.parseLong(props.getProperty(key + ".refreshed", "0"));
                    cache.put(key, new Entry(url, Math.min(expires, System.currentTimeMillis() + ttl), Math.min(refreshed, System.currentTimeMillis()), Math.min(Long.parseLong(props.getProperty(key + ".next-refresh", "0")), System.currentTimeMillis() + 600_000L)));
                    messages.put(key, Math.max(0, Math.min(1000, Integer.parseInt(props.getProperty(key + ".messages", "0")))));
                } catch (IllegalArgumentException ignored) { }
            }
        } catch (java.io.IOException error) { failures.incrementAndGet(); }
    }
    private void persistLater() {
        if (storage == null || closed || System.currentTimeMillis() - lastPersistAt < 1000L || !saving.compareAndSet(false, true)) return;
        try { worker.execute(() -> { try { persist(); } finally { saving.set(false); } }); }
        catch (RejectedExecutionException ignored) { saving.set(false); }
    }
    private synchronized void persist() {
        if (storage == null) return;
        java.util.Properties props = new java.util.Properties();
        cache.forEach((key, entry) -> {
            props.setProperty(key + ".url", entry.url());
            props.setProperty(key + ".expires", Long.toString(entry.expires()));
            props.setProperty(key + ".refreshed", Long.toString(entry.refreshed()));
            props.setProperty(key + ".next-refresh", Long.toString(entry.nextRefresh()));
            props.setProperty(key + ".messages", Integer.toString(messages.getOrDefault(key, 0)));
        });
        try { AtomicState.save(storage, props, "Simplex relay avatar URLs and message counters"); lastPersistAt = System.currentTimeMillis(); }
        catch (java.io.IOException error) { failures.incrementAndGet(); }
    }

    static String javaAvatar(Identity identity, String template) {
        if (identity == null || identity.uuid() == null || identity.bedrock()) return "";
        String name = identity.name() == null ? "" : identity.name();
        boolean validName = name.matches("[A-Za-z0-9_]{1,16}");
        UUID offline = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String identifier = validName && offline.equals(identity.uuid()) ? name : identity.uuid().toString();
        String source = template == null || LEGACY_JAVA_URL.equals(template) ? DEFAULT_JAVA_URL : template;
        return RelayText.httpsImage(source.replace("{identifier}", identifier)
                .replace("{uuid}", identity.uuid().toString())
                .replace("{name}", validName ? name : identity.uuid().toString()));
    }

    int cached() { return cache.size(); }
    long failures() { return failures.get(); }
    void clear() { generation.incrementAndGet(); cache.clear(); messages.clear(); lastPersistAt = 0; persistLater(); }
    @Override public void close() {
        closed = true; worker.shutdownNow();
        try { worker.awaitTermination(2, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        persist(); pending.clear(); cache.clear(); messages.clear();
    }
}
