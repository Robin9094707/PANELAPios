package eu.simplexsmp.adminsuite;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * 10.3.0: read-only integrity check of Anvil region files and an optional transactional safety copy for the world
 * trimmer. Nothing here touches the server tick thread or loaded worlds; it only reads/copies .mca/.mcc files of the
 * dimensions selected for a trim run.
 */
final class RegionSafety {
    private RegionSafety() { }

    static final List<String> FOLDERS = List.of("region", "poi", "entities");

    /** Result of checking region files: number of files read and the first problems found (bounded). */
    record Report(int files, int chunks, List<String> problems) {
        boolean ok() { return problems.isEmpty(); }
    }

    /**
     * Checks every .mca file below the given dimension folders that was modified at or after {@code since} (0 = all).
     * A file is valid when its 8 KiB header is complete and each chunk entry points inside the file with a plausible
     * length and a known compression type (GZip, Zlib, none, LZ4, custom; external .mcc chunks allowed).
     */
    static Report check(List<Path> dimensions, long since, int maxProblems) throws IOException {
        int files = 0, chunks = 0;
        List<String> problems = new ArrayList<>();
        for (Path dimension : dimensions) for (String child : FOLDERS) {
            Path directory = dimension.resolve(child);
            if (!Files.isDirectory(directory)) continue;
            List<Path> regionFiles;
            try (Stream<Path> stream = Files.list(directory)) {
                regionFiles = stream.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".mca")).sorted().toList();
            }
            for (Path file : regionFiles) {
                if (since > 0L && Files.getLastModifiedTime(file).toMillis() < since) continue;
                files++;
                String problem = checkFile(file);
                if (problem.startsWith("ok:")) { chunks += Integer.parseInt(problem.substring(3)); continue; }
                if (problems.size() < Math.max(1, maxProblems)) problems.add(dimension.getFileName() + "/" + child + "/" + file.getFileName() + ": " + problem);
            }
        }
        return new Report(files, chunks, List.copyOf(problems));
    }

    /** Returns "ok:&lt;chunks&gt;" or a short German reason. */
    static String checkFile(Path file) throws IOException {
        long length = Files.size(file);
        if (length == 0L) return "ok:0";
        if (length < 8192L) return "Header unvollständig (" + length + " Bytes)";
        long sectors = (length + 4095L) / 4096L;
        int present = 0;
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer header = ByteBuffer.allocate(4096);
            readFully(channel, header, 0L);
            header.flip();
            ByteBuffer chunk = ByteBuffer.allocate(5);
            for (int index = 0; index < 1024; index++) {
                int entry = header.getInt(index * 4);
                int offset = entry >>> 8, count = entry & 0xFF;
                if (offset == 0 && count == 0) continue;
                if (offset < 2 || count == 0) return "Ungültiger Header-Eintrag " + index;
                if (offset + (long) count > sectors) return "Chunk " + index + " zeigt hinter das Dateiende";
                chunk.clear();
                if (!readFully(channel, chunk, offset * 4096L)) return "Chunk " + index + " nicht lesbar";
                chunk.flip();
                int size = chunk.getInt();
                int compression = chunk.get() & 0xFF;
                boolean external = (compression & 0x80) != 0;
                int type = compression & 0x7F;
                if (type != 1 && type != 2 && type != 3 && type != 4 && type != 127) return "Chunk " + index + " unbekannte Komprimierung " + compression;
                if (size < 1 || (!external && size > count * 4096L - 4L)) return "Chunk " + index + " ungültige Länge " + size;
                present++;
            }
        }
        return "ok:" + present;
    }

    private static boolean readFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        long at = position;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, at);
            if (read < 0) return false;
            at += read;
        }
        return true;
    }

    /** Copies region/poi/entities of each dimension into {@code target/<index>/...}. Returns the copied bytes. */
    static long snapshot(List<Path> dimensions, Path target) throws IOException {
        long bytes = 0L;
        Files.createDirectories(target);
        for (int index = 0; index < dimensions.size(); index++) {
            Path dimension = dimensions.get(index);
            Files.writeString(target.resolve(index + ".path"), dimension.toAbsolutePath().normalize().toString());
            for (String child : FOLDERS) {
                Path source = dimension.resolve(child);
                if (!Files.isDirectory(source)) continue;
                Path copy = target.resolve(Integer.toString(index)).resolve(child);
                Files.createDirectories(copy);
                List<Path> files;
                try (Stream<Path> stream = Files.list(source)) { files = stream.filter(Files::isRegularFile).filter(RegionSafety::regionFile).toList(); }
                for (Path file : files) {
                    Files.copy(file, copy.resolve(file.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                    bytes += Files.size(file);
                }
            }
        }
        Files.writeString(target.resolve("complete"), Long.toString(System.currentTimeMillis()));
        return bytes;
    }

    /** Restores a complete snapshot over the dimension folders (files deleted by the trim come back, others are replaced). */
    static int restore(List<Path> dimensions, Path target) throws IOException {
        if (!Files.isRegularFile(target.resolve("complete"))) throw new IOException("Sicherheitskopie unvollständig – nicht wiederhergestellt");
        int restored = 0;
        for (int index = 0; index < dimensions.size(); index++) {
            Path dimension = dimensions.get(index);
            Path recorded = target.resolve(index + ".path");
            if (!Files.isRegularFile(recorded) || !Files.readString(recorded).trim().equals(dimension.toAbsolutePath().normalize().toString()))
                throw new IOException("Sicherheitskopie gehört nicht zu dieser Dimension");
            for (String child : FOLDERS) {
                Path copy = target.resolve(Integer.toString(index)).resolve(child);
                if (!Files.isDirectory(copy)) continue;
                Path destination = dimension.resolve(child);
                Files.createDirectories(destination);
                List<Path> files;
                try (Stream<Path> stream = Files.list(copy)) { files = stream.filter(Files::isRegularFile).toList(); }
                for (Path file : files) {
                    Path temp = destination.resolve(file.getFileName() + ".sas-restore");
                    Files.copy(file, temp, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                    try { Files.move(temp, destination.resolve(file.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
                    catch (IOException atomic) { Files.move(temp, destination.resolve(file.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING); }
                    restored++;
                }
            }
        }
        return restored;
    }

    static void delete(Path target) {
        if (target == null || !Files.exists(target)) return;
        try (Stream<Path> walk = Files.walk(target)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException ignored) { }
    }

    private static boolean regionFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".mca") || name.endsWith(".mcc");
    }

    /** Only plain heap sizes like 3G or 2048M reach the child JVM; anything else falls back to 3G. */
    static String heap(String configured) {
        String value = configured == null ? "" : configured.trim();
        return value.matches("[1-9][0-9]{0,5}[MmGg]") ? value.toUpperCase(Locale.ROOT) : "3G";
    }
}
