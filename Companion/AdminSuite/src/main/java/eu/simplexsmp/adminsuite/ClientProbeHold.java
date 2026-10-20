package eu.simplexsmp.adminsuite;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.potion.*;

/** Short probe hold; visual effects are client-only and never change stored effects. */
final class ClientProbeHold implements Listener {
    private final ClientProbeManager manager;
    private final Map<UUID,Set<PotionEffectType>> held=new ConcurrentHashMap<>();
    ClientProbeHold(ClientProbeManager manager){this.manager=manager;}
    boolean locked(Player p){return p!=null&&held.containsKey(p.getUniqueId());}
    void begin(Player p,boolean darken,int seconds){
        Set<PotionEffectType> changed=new HashSet<>();held.put(p.getUniqueId(),changed);
        p.closeInventory();p.setVelocity(new org.bukkit.util.Vector());
        if(!darken)return;
        for(PotionEffectType type:List.of(PotionEffectType.BLINDNESS,PotionEffectType.DARKNESS,PotionEffectType.INVISIBILITY)){
            changed.add(type);p.sendPotionEffectChange(p,new PotionEffect(type,(seconds+2)*20,0,false,false,false));
        }
        changed.add(PotionEffectType.NIGHT_VISION);p.sendPotionEffectChangeRemove(p,PotionEffectType.NIGHT_VISION);
    }
    void end(Player p){
        Set<PotionEffectType> changed=held.remove(p.getUniqueId());if(changed==null||!p.isOnline())return;
        for(PotionEffectType type:changed)try{
            p.sendPotionEffectChangeRemove(p,type);
            PotionEffect actual=p.getPotionEffect(type);if(actual!=null)p.sendPotionEffectChange(p,actual);
        }catch(RuntimeException ignored){/* Hold is already released; a disconnect resets client visuals. */}
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void move(PlayerMoveEvent e){if(locked(e.getPlayer())&&!manager.managedTeleport(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void command(PlayerCommandPreprocessEvent e){if(locked(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void chat(io.papermc.paper.event.player.AsyncChatEvent e){if(locked(e.getPlayer()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.MONITOR) public void world(PlayerChangedWorldEvent e){if(locked(e.getPlayer()))manager.interrupt(e.getPlayer());}
    @EventHandler(priority=EventPriority.MONITOR) public void death(PlayerDeathEvent e){if(locked(e.getEntity()))manager.interrupt(e.getEntity());}
}
