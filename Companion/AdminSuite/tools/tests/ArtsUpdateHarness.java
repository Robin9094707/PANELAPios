package eu.simplexsmp.adminsuite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

final class ArtsUpdateHarness {
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("simplex-arts");
        long now = System.currentTimeMillis();
        String id;
        try {
            try (ConsoleArchive archive = new ConsoleArchive(directory, 30)) {
                archive.offer(new DiagnosticsCenter.Line(now - 20 * 86_400_000L, "INFO", "Paper", "old line"));
                archive.offer(new DiagnosticsCenter.Line(now - 100, "ERROR", "Example", "Timeout at 192.0.2.19 with token=private-secret"));
                archive.offer(new DiagnosticsCenter.Line(now - 10, "WARN", "Example", "timeout again"));
                var filtered = archive.read(Map.of("q", "TIMEOUT", "level", "ERROR"), "connected", true);
                var entries = items(filtered);
                assert entries.size() == 1;
                var row = AppApiManager.object(entries.get(0));
                id = row.get("id").toString();
                assert !row.get("text").toString().contains("192.0.2.19");
                assert !row.get("text").toString().contains("private-secret");
                assert items(archive.read(Map.of("to", Long.toString(now - 1000)), "connected", false)).size() == 1;
            }
            try (ConsoleArchive archive = new ConsoleArchive(directory, 30)) {
                assert items(archive.read(Map.of(), "", true)).size() == 3;
                assert archive.change("delete", id, 0) == 1;
                assert archive.change("retention", "", 7) == 1;
                assert archive.change("clear", "", 0) == 1;
                archive.offer(new DiagnosticsCenter.Line(now, "INFO", "Paper", "still recording"));
                assert items(archive.read(Map.of(), "", false)).size() == 1;
                for (int index = 0; index < 150; index++)
                    archive.offer(new DiagnosticsCenter.Line(now, "INFO", "Paper", "line " + index + " " + "x".repeat(8000)));
            }
            Path file = directory.resolve("console.sqlite");
            long used = Files.size(file);
            try (ConsoleArchive archive = new ConsoleArchive(directory, 30)) {
                assert archive.change("clear", "", 0) == 151;
                assert Files.size(file) < used / 2 : "Deleting the archive should release disk pages";
            }
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path file : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
        System.out.println("PASS Arts Update console persistence, masking, filters, deletion and retention");
    }

    private static List<?> items(Map<String, Object> envelope) { return (List<?>) envelope.get("items"); }
}
