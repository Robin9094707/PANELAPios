package eu.simplexsmp.adminsuite;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;

/** Vanilla registry sounds, with the same category for every Community variant. */
final class CommunityAudio {
    private CommunityAudio() { }
    static void play(Player listener,Location location,String key,float volume,float pitch) {
        Sound registered=null;
        // Paper's registry is available on a running server. The string fallback also
        // supports older compatible APIs and standalone regression harnesses.
        try { registered=Registry.SOUNDS.get(NamespacedKey.fromString(key)); }
        catch(LinkageError|RuntimeException unavailable) { }
        if(registered==null)try { registered=Sound.valueOf(key.replace("minecraft:","").replace('.','_').toUpperCase(java.util.Locale.ROOT)); }
        catch(LinkageError|RuntimeException unavailable) { }
        float safePitch=Math.max(0.5F,Math.min(2F,pitch));
        float safeVolume=Math.max(0.05F,Math.min(2F,volume));
        if(registered!=null)listener.playSound(location,registered,SoundCategory.MASTER,safeVolume,safePitch);
        else listener.playSound(location,key,SoundCategory.MASTER,safeVolume,safePitch);
    }
}
