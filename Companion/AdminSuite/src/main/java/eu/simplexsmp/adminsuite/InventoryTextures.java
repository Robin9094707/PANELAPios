package eu.simplexsmp.adminsuite;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Private on-server cache of the latest stable Java client's item textures. */
final class InventoryTextures implements AutoCloseable {
    private static final int MAX_DOWNLOAD = 120 * 1024 * 1024;
    static final int MAX_IMPORT = 32 * 1024 * 1024;
    private static final int MAX_ICON = 64 * 1024;
    private static final String MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final Set<String> MOJANG = Set.of("piston-meta.mojang.com", "piston-data.mojang.com", "launcher.mojang.com");
    private final Path directory;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "Simplex-Inventory-Textures");
        thread.setDaemon(true);
        return thread;
    });
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Map<String, String> cache = new LinkedHashMap<>(256, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> eldest) { return size() > 256; }
    };
    private volatile String status = "Java-Ressourcen werden geprüft";

    InventoryTextures(Path dataFolder) {
        directory = dataFolder.resolve("inventory-resources");
        worker.scheduleWithFixedDelay(this::refresh, 1, 24 * 60 * 60, TimeUnit.SECONDS);
    }

    private void refresh() {
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            Map<String, Object> manifest = metadata(MANIFEST);
            String release = String.valueOf(AppApiManager.object(manifest.get("latest")).get("release"));
            if (!release.matches("[A-Za-z0-9. _-]{1,40}")) throw new IOException("Invalid release");
            Path archive = directory.resolve("vanilla.jar");
            Path version = directory.resolve("version.txt");
            if (!Files.isRegularFile(archive) || !Files.exists(version) || !release.equals(Files.readString(version))) {
                Map<String, Object> entry = ((List<?>) manifest.get("versions")).stream()
                    .map(AppApiManager::object).filter(value -> release.equals(value.get("id"))).findFirst().orElseThrow();
                Map<String, Object> metadata = metadata(String.valueOf(entry.get("url")));
                Map<String, Object> client = AppApiManager.object(AppApiManager.object(metadata.get("downloads")).get("client"));
                temporary = Files.createTempFile(directory, "vanilla-", ".tmp");
                try (InputStream input = download(String.valueOf(client.get("url"))); var output = Files.newOutputStream(temporary)) {
                    byte[] buffer = new byte[64 * 1024];
                    long written = 0;
                    for (int count; (count = input.read(buffer)) != -1; ) {
                        written += count;
                        if (written > MAX_DOWNLOAD) throw new IOException("Client download limit");
                        output.write(buffer, 0, count);
                    }
                }
                long length = Files.size(temporary);
                if (length > MAX_DOWNLOAD || length != ((Number) client.get("size")).longValue()) throw new IOException("Unexpected client length");
                var digest = MessageDigest.getInstance("SHA-1");
                try (var input = new java.security.DigestInputStream(Files.newInputStream(temporary), digest)) {
                    input.transferTo(OutputStream.nullOutputStream());
                }
                if (!HexFormat.of().formatHex(digest.digest()).equals(client.get("sha1"))) throw new IOException("Client checksum");
                replace(temporary, archive);
                Files.writeString(version, release);
            }
            status = "Minecraft Java " + release;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception error) {
            status = Files.isRegularFile(directory.resolve("vanilla.jar")) ? "Gespeicherte Java-Ressourcen" : "Java-Ressourcen gerade nicht verfügbar";
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
        }
    }

    private Map<String, Object> metadata(String url) throws IOException, InterruptedException {
        try (InputStream input = download(url)) {
            byte[] bytes = input.readNBytes(2_000_001);
            if (bytes.length > 2_000_000) throw new IOException("Metadata limit");
            return AppApiManager.object(DiscordBotManager.Json.parse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)));
        }
    }

    private InputStream download(String url) throws IOException, InterruptedException {
        URI uri = URI.create(url);
        if (!"https".equals(uri.getScheme()) || !MOJANG.contains(uri.getHost()) || uri.getUserInfo() != null
            || (uri.getPort() != -1 && uri.getPort() != 443)) throw new IOException("Invalid Mojang URL");
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60)).GET().build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() == 200) return response.body();
        response.body().close();
        throw new IOException("Mojang HTTP " + response.statusCode());
    }

    synchronized void importPack(InputStream input) throws IOException {
        Files.createDirectories(directory);
        Path temp = Files.createTempFile(directory, "custom-", ".zip");
        try {
            byte[] bytes = input.readNBytes(MAX_IMPORT + 1);
            if (bytes.length > MAX_IMPORT) throw new AppApiManager.ApiError(413, "Ressourcenpaket: maximal 32 MiB");
            Files.write(temp, bytes);
            try (ZipFile zip = new ZipFile(temp.toFile())) {
                if (zip.size() > 32_000 || zip.getEntry("pack.mcmeta") == null || zip.stream().noneMatch(
                    e -> e.getName().matches("assets/minecraft/textures/(item|block)/[a-z0-9_]+\\.png")))
                    throw new IllegalArgumentException("Java-Ressourcenpaket mit Item- oder Blocktexturen erforderlich");
            } catch (java.util.zip.ZipException error) {
                throw new IllegalArgumentException("Ungültige ZIP-Datei");
            }
            replace(temp, directory.resolve("custom.zip"));
        } finally { Files.deleteIfExists(temp); }
    }

    synchronized void reset() throws IOException {
        Files.deleteIfExists(directory.resolve("custom.zip"));
        cache.clear();
    }

    Map<String, Object> read(Set<String> items) {
        Map<String, String> icons = new LinkedHashMap<>();
        Path vanilla = directory.resolve("vanilla.jar"), custom = directory.resolve("custom.zip");
        synchronized (this) {
            try (ZipFile first = open(custom); ZipFile fallback = open(vanilla)) {
                String key = generation();
                for (String item : items) {
                    if (!item.matches("[a-z0-9_]{1,90}")) continue;
                    String value = cache.computeIfAbsent(key + "/" + item, ignored -> icon(first, fallback, item));
                    if (!value.isEmpty()) icons.put(item, value);
                }
            } catch (IOException ignored) { }
        }
        return SimplexAdminSuite.appMap("icons", icons, "status", status, "custom", Files.isRegularFile(custom));
    }

    String generation() {
        String version = stamp(directory.resolve("vanilla.jar"));
        return version + "/" + stamp(directory.resolve("custom.zip"));
    }

    private static String stamp(Path path) {
        try { return Files.getLastModifiedTime(path).toString(); } catch (IOException e) { return ""; }
    }

    private static ZipFile open(Path path) throws IOException { return Files.isRegularFile(path) ? new ZipFile(path.toFile()) : null; }

    /** 10.15.0: resource-pack aware rendering (model chain, entity items, first animation frame, foliage tint). */
    private static String icon(ZipFile custom, ZipFile vanilla, String item) {
        ItemIconRenderer renderer = new ItemIconRenderer(path -> {
            for (ZipFile source : new ZipFile[]{custom, vanilla}) {
                if (source == null) continue;
                ZipEntry entry = source.getEntry(path);
                if (entry == null || entry.getSize() > 1024 * 1024) continue;
                try (InputStream input = source.getInputStream(entry)) { return input.readNBytes(1024 * 1024); }
                catch (IOException ignored) { }
            }
            return null;
        });
        byte[] image = renderer.render(item);
        return image == null || image.length > MAX_ICON * 4 ? "" : Base64.getEncoder().encodeToString(image);
    }

    private static void replace(Path source, Path destination) throws IOException {
        Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    @Override public void close() { worker.shutdownNow(); }
}
