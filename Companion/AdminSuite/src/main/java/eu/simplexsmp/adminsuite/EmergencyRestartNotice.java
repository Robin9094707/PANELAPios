package eu.simplexsmp.adminsuite;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * 9.7.0: critical Bark notice after an emergency restart. An emergency restart leaves a marker before
 * shutdown; a crash is recognised when the running marker of the previous start was never removed.
 * After the next successful start one "emergency-restart" event is recorded; subscribers decide in the app
 * whether it may pierce their sleep and/or working hours.
 */
final class EmergencyRestartNotice {
    private EmergencyRestartNotice() { }
    private static Path folder(SimplexAdminSuite plugin) { return plugin.getDataFolder().toPath().resolve("app-events"); }

    static void mark(SimplexAdminSuite plugin, String reason) {
        try { Files.createDirectories(folder(plugin)); Files.writeString(folder(plugin).resolve("emergency-restart.marker"), System.currentTimeMillis() + "\n" + reason, StandardCharsets.UTF_8); }
        catch (IOException e) { plugin.getLogger().warning("Notfallneustart-Markierung konnte nicht gespeichert werden: " + e.getMessage()); }
    }

    /** Called once after the event center started. */
    static void started(SimplexAdminSuite plugin) {
        Path dir = folder(plugin), marker = dir.resolve("emergency-restart.marker"), running = dir.resolve("running.marker");
        try {
            Files.createDirectories(dir);
            String title = null, body = null;
            if (Files.exists(marker)) {
                String[] parts = Files.readString(marker, StandardCharsets.UTF_8).split("\n", 2);
                long at = AdminActivityLog.number(parts[0]);
                title = "Server nach Notfallneustart wieder online";
                body = (parts.length > 1 ? parts[1] : "Notfallneustart") + (at > 0 ? " · Neustart vor " + Math.max(1, (System.currentTimeMillis() - at) / 1000) + " s ausgelöst" : "");
                Files.deleteIfExists(marker);
            } else if (Files.exists(running) && plugin.getConfig().getBoolean("emergency-restart.notify-crash", true)) {
                title = "Server nach unerwartetem Stopp wieder online";
                body = "Der letzte Serverlauf wurde nicht sauber beendet (Absturz, Watchdog oder hartes Beenden). Die Admin Suite ist wieder bereit.";
            }
            Files.writeString(running, Long.toString(System.currentTimeMillis()), StandardCharsets.UTF_8);
            if (title != null && plugin.appEvents != null) plugin.appEvents.record("emergency-restart", null, title, body, true, "critical");
        } catch (IOException e) { plugin.getLogger().warning("Notfallneustart-Status konnte nicht geprüft werden: " + e.getMessage()); }
    }

    /** Clean shutdown: the next start is not treated as a crash. */
    static void stopped(SimplexAdminSuite plugin) {
        try { Files.deleteIfExists(folder(plugin).resolve("running.marker")); } catch (IOException ignored) { }
    }
}
