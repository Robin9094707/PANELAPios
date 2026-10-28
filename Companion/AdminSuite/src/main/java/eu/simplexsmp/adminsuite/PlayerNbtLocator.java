package eu.simplexsmp.adminsuite;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Exact UUID probes only, shared by discovery and read/write authorization. */
final class PlayerNbtLocator {
    record Entry(String id, Path file) { }
    static List<Entry> find(Path serverRoot, Collection<File> worlds, UUID player) throws IOException {
        Path root=serverRoot.toRealPath();
        LinkedHashSet<File> roots=new LinkedHashSet<>(worlds);
        roots.add(root.resolve("world").toFile());
        Properties properties=new Properties();Path config=root.resolve("server.properties");
        if(Files.isRegularFile(config))try(Reader reader=Files.newBufferedReader(config)){properties.load(reader);}
        String level=properties.getProperty("level-name","world");
        Path configured=root.resolve(level).normalize();if(configured.startsWith(root))roots.add(configured.toFile());
        List<Entry> result=new ArrayList<>();Set<Path> seen=new HashSet<>();
        // PlayerDataPaths also resolves the main world from modern dimensions roots.
        for(File candidate:PlayerDataPaths.candidates(roots,player,"playerdata",".dat")) {
            Path logical=candidate.toPath().toAbsolutePath().normalize();
            if(!logical.startsWith(root)||!logical.getFileName().toString().equals(player+".dat")||!Files.isRegularFile(logical,LinkOption.NOFOLLOW_LINKS))continue;
            Path real=logical.toRealPath();if(!real.startsWith(root)||!seen.add(real))continue;
            result.add(new Entry("/"+root.relativize(logical).toString().replace(File.separatorChar,'/'),real));
        }
        // Keep modern data ahead of legacy files. Never silently select .dat_old.
        return result;
    }
}
