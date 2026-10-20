package eu.simplexsmp.adminsuite;
import java.io.*;
import java.nio.file.*;
import java.util.Properties;
final class AtomicState {
    private AtomicState() { }
    static void save(Path file, Properties values, String comment) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
        try {
            try (Writer out = Files.newBufferedWriter(temp)) { values.store(out, comment); }
            try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
}
