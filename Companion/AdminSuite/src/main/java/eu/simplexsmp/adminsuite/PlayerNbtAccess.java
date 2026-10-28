package eu.simplexsmp.adminsuite;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.bukkit.*;

/** Called only on the Minecraft main thread: login and save cannot interleave. */
final class PlayerNbtAccess {
    private final SimplexAdminSuite plugin;
    PlayerNbtAccess(SimplexAdminSuite plugin) { this.plugin=plugin; }
    private Path path(UUID player,String requested) throws IOException {
        for(PlayerNbtLocator.Entry entry:PlayerNbtLocator.find(Path.of("."),Bukkit.getWorlds().stream().map(World::getWorldFolder).toList(),player))
            if(entry.id().equals(requested))return entry.file();
        throw new IllegalArgumentException("Nur die gespeicherte NBT-Datei dieses Spielers in geladenen Welten ist erlaubt.");
    }
    private static byte[] bytes(Path path) throws IOException {
        if(Files.size(path)>2*1024*1024)throw new IllegalArgumentException("Spielerdatei überschreitet 2 MiB");
        byte[] data=Files.readAllBytes(path);if(data.length>2*1024*1024)throw new IllegalArgumentException("Spielerdatei zu groß");return data;
    }
    static String hash(byte[] bytes) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    Object read(String player,String requested) throws IOException { return read(player,requested,false); }
    Object read(String player,String requested,boolean statusOnly) throws IOException {
        UUID id=plugin.discordAdminResolvePlayer(player);if(id==null)throw new IllegalArgumentException("Spielerprofil fehlt");
        Path path=path(id,requested);if(statusOnly)return SimplexAdminSuite.appMap("online",Bukkit.getPlayer(id)!=null);byte[] data=bytes(path);
        return SimplexAdminSuite.appMap("data",Base64.getEncoder().encodeToString(data),"sha256",hash(data),"online",Bukkit.getPlayer(id)!=null,"path",requested);
    }
    Object write(Map<String,Object> input,String actor) throws IOException {
        UUID id=plugin.discordAdminResolvePlayer(String.valueOf(input.getOrDefault("player","")));if(id==null)throw new IllegalArgumentException("Spielerprofil fehlt");
        if(Bukkit.getPlayer(id)!=null)throw new IllegalArgumentException("Spieler ist online. Änderung abgebrochen.");
        Path path=path(id,String.valueOf(input.getOrDefault("path","")));
        byte[] before=bytes(path);String expected=String.valueOf(input.getOrDefault("expected",""));
        if(!hash(before).equals(expected))throw new IllegalArgumentException("Dateikonflikt. Spielerdatei neu laden; Entwurf bleibt lokal.");
        String encoded=String.valueOf(input.getOrDefault("data",""));if(encoded.length()>2_800_000)throw new IllegalArgumentException("NBT zu groß");
        byte[] data=Base64.getDecoder().decode(encoded);if(data.length>2*1024*1024||data.length==0)throw new IllegalArgumentException("Ungültige NBT-Größe");
        Path temp=Files.createTempFile(path.getParent(),"panel-nbt-",".tmp");
        try {
            Files.write(temp,data);NbtPlayerData.read(temp.toFile());
            if(Bukkit.getPlayer(id)!=null||!hash(bytes(path)).equals(expected))throw new IllegalArgumentException("Spielerstatus oder Datei hat sich geändert. Änderung abgebrochen.");
            Path backup=path.resolveSibling(path.getFileName()+".panel-"+System.currentTimeMillis()+"-"+UUID.randomUUID()+".bak");
            Files.copy(path,backup);if(!hash(bytes(backup)).equals(expected))throw new IOException("Sicherung konnte nicht bestätigt werden");
            // No non-atomic fallback: leave the original intact if the filesystem cannot atomically replace it.
            Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            plugin.expansionAudit(actor,id,"Offline-NBT gespeichert",backup.getFileName().toString());
            return SimplexAdminSuite.appMap("ok",true,"sha256",hash(data),"backup",backup.getFileName().toString());
        } finally {Files.deleteIfExists(temp);}
    }
}
