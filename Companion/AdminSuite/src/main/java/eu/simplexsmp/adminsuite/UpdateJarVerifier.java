package eu.simplexsmp.adminsuite;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import java.util.jar.JarEntry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Validate downloaded plugin identity and main-class Java compatibility before staging. */
final class UpdateJarVerifier {
    private UpdateJarVerifier() { }
    static void copyLimited(InputStream input, Path target, long maximum) throws IOException {
        java.util.concurrent.ScheduledExecutorService timeout = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "Simplex-Update-Deadline"); thread.setDaemon(true); return thread;
        });
        java.util.concurrent.atomic.AtomicBoolean expired = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.ScheduledFuture<?> deadline = timeout.schedule(() -> {
            expired.set(true);
            try { input.close(); } catch (IOException ignored) { }
        }, 90, java.util.concurrent.TimeUnit.SECONDS);
        try (OutputStream output = Files.newOutputStream(target)) {
            byte[] buffer = new byte[16_384]; long total = 0L; int read;
            while ((read = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Download abgebrochen");
                total += read;
                if (total > maximum) throw new IOException("Update überschreitet das Größenlimit");
                output.write(buffer, 0, read);
            }
            if (expired.get()) throw new java.io.InterruptedIOException("Update-Download hat das Zeitlimit überschritten");
        } finally { deadline.cancel(false); timeout.shutdownNow(); }
    }

    static String verify(Path path, String expectedName, int javaFeature) throws IOException {
        try (JarFile jar = new JarFile(path.toFile())) {
            JarEntry descriptor = jar.getJarEntry("plugin.yml");
            if (descriptor == null) throw new IOException("plugin.yml fehlt");
            String yaml;
            try (InputStream input = jar.getInputStream(descriptor)) {
                byte[] data = input.readNBytes(65_537);
                if (data.length > 65_536) throw new IOException("plugin.yml ist zu groß");
                yaml = new String(data, StandardCharsets.UTF_8);
            }
            String name = field(yaml, "name"), version = field(yaml, "version"), main = field(yaml, "main");
            if (!expectedName.equalsIgnoreCase(name)) throw new IOException("Falsche Plugin-Identität im Update");
            if (version.isBlank() || !main.matches("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)+")) throw new IOException("Plugin-Metadaten unvollständig");
            JarEntry mainClass = jar.getJarEntry(main.replace('.', '/') + ".class");
            if (mainClass == null) throw new IOException("Plugin-Hauptklasse fehlt");
            try (DataInputStream input = new DataInputStream(jar.getInputStream(mainClass))) {
                if (input.readInt() != 0xCAFEBABE) throw new IOException("Ungültige Java-Hauptklasse");
                int minor = input.readUnsignedShort();
                int major = input.readUnsignedShort();
                if (major > javaFeature + 44 || minor == 65_535) throw new IOException("Update benötigt eine neuere Java-Version oder Preview-Funktionen");
            }
            return version;
        }
    }

    private static String field(String yaml, String key) {
        Matcher matcher = Pattern.compile("(?m)^" + key + ":[ \\t]*['\"]?([^'\"\\r\\n#]+)").matcher(yaml);
        return matcher.find() ? matcher.group(1).trim() : "";
    }
}
