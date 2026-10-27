package eu.simplexsmp.adminsuite;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import java.util.*;

/** Real operator status is the only exemption; permission wildcards do not exempt players. */
final class SurvivalGuard implements Listener {
    private final SimplexAdminSuite plugin;
    private final Map<UUID,Long> warned = new HashMap<>();
    SurvivalGuard(SimplexAdminSuite plugin) { this.plugin = plugin; }
    void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for(Player p : Bukkit.getOnlinePlayers()) enforce(p);
            warned.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
        }, 1L, 20L);
    }
    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void mode(PlayerGameModeChangeEvent e) {
        if(!e.getPlayer().isOp() && e.getNewGameMode() != GameMode.SURVIVAL) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(plugin.playerText(e.getPlayer(), "§cNur Server-Operatoren dürfen den Überlebensmodus verlassen.", "§cOnly server operators may leave survival mode."));
        }
    }
    @EventHandler(priority=EventPriority.MONITOR) public void joined(PlayerJoinEvent e) {
        Bukkit.getScheduler().runTask(plugin, () -> enforce(e.getPlayer()));
    }
    void enforce(Player p) {
        if(!p.isOnline() || p.isOp()) return;
        // The relocation mechanism temporarily protects players from falling. Do not break it.
        if(plugin.securityHub != null && plugin.securityHub.relocation.pending(p.getUniqueId())) return;
        boolean changed = p.getGameMode() != GameMode.SURVIVAL || p.isInvulnerable();
        if(p.getGameMode() != GameMode.SURVIVAL) p.setGameMode(GameMode.SURVIVAL);
        if(p.isInvulnerable()) p.setInvulnerable(false);
        if(changed && System.currentTimeMillis()-warned.getOrDefault(p.getUniqueId(),0L)>60_000) {
            warned.put(p.getUniqueId(),System.currentTimeMillis());
            plugin.expansionAudit("SurvivalGuard", p.getUniqueId(), "Spielerschutz korrigiert", "Survival erzwungen / Invulnerable zurückgesetzt");
        }
    }
}
