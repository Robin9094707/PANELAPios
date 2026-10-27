package eu.simplexsmp.adminsuite;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;

/** Optional sky fallback: durable return point before moving; recovery also after disconnect/restart. */
final class ProbeRelocation implements Listener {
    record ReturnPoint(UUID world,double x,double y,double z,float yaw,float pitch,boolean allowFlight,boolean flying,boolean invulnerable){
        String encode(){return world+"|"+x+"|"+y+"|"+z+"|"+yaw+"|"+pitch+"|"+allowFlight+"|"+flying+"|"+invulnerable;}

    }
    private final SimplexAdminSuite plugin;private final Path file;
    private final Map<UUID,ReturnPoint> returns=new HashMap<>();private final Set<UUID> managed=new HashSet<>();
    ProbeRelocation(SimplexAdminSuite plugin)throws IOException{this.plugin=plugin;file=plugin.getDataFolder().toPath().resolve("probe-return-points.properties");Properties p=new Properties();if(Files.exists(file))try(Reader in=Files.newBufferedReader(file)){p.load(in);}for(String id:p.stringPropertyNames())try{returns.put(UUID.fromString(id),decode(p.getProperty(id)));}catch(RuntimeException invalid){throw new IOException("Invalid saved probe return point; preserving file",invalid);}}
    static ReturnPoint decode(String value){String[] a=value.split("\\|");if(a.length!=9)throw new IllegalArgumentException("Invalid return record");for(int i=6;i<9;i++)if(!Set.of("true","false").contains(a[i]))throw new IllegalArgumentException("Invalid saved flight flags");ReturnPoint p=new ReturnPoint(UUID.fromString(a[0]),Double.parseDouble(a[1]),Double.parseDouble(a[2]),Double.parseDouble(a[3]),Float.parseFloat(a[4]),Float.parseFloat(a[5]),Boolean.parseBoolean(a[6]),Boolean.parseBoolean(a[7]),Boolean.parseBoolean(a[8]));if(!Double.isFinite(p.x)||!Double.isFinite(p.y)||!Double.isFinite(p.z)||!Float.isFinite(p.yaw)||!Float.isFinite(p.pitch))throw new IllegalArgumentException("Invalid coordinates");return p;}
    boolean managed(Player p){return managed.contains(p.getUniqueId());}
    Location original(UUID id){ReturnPoint p=returns.get(id);if(p==null)return null;World world=Bukkit.getWorld(p.world);return world==null?null:new Location(world,p.x,p.y,p.z,p.yaw,p.pitch);}
    boolean pending(UUID id){return returns.containsKey(id);}
    boolean begin(Player p){if(!plugin.getConfig().getBoolean("client-security.probes.sky-fallback",false)||plugin.isBedrockClient(p)||pending(p.getUniqueId())||p.isInsideVehicle()||p.isDead())return false;
        Location from=p.getLocation();World world=from.getWorld();if(world==null||!world.isChunkLoaded(from.getBlockX()>>4,from.getBlockZ()>>4))return false;Location sky=null;
        for(int y=world.getMaxHeight()-24;y>=Math.max(world.getMinHeight()+24,world.getMaxHeight()-80);y--)if(air(world.getBlockAt(from.getBlockX(),y,from.getBlockZ()).getType())&&air(world.getBlockAt(from.getBlockX(),y+1,from.getBlockZ()).getType())&&air(world.getBlockAt(from.getBlockX(),y+8,from.getBlockZ()).getType())){sky=new Location(world,from.getX(),y,from.getZ(),from.getYaw(),from.getPitch());break;}
        if(sky==null)return false;
        boolean[] flight=plugin.adminRuntime==null?new boolean[]{p.getAllowFlight(),p.isFlying()}:plugin.adminRuntime.probeReturnFlight(p);
        ReturnPoint point=new ReturnPoint(world.getUID(),from.getX(),from.getY(),from.getZ(),from.getYaw(),from.getPitch(),flight[0],flight[1],p.isInvulnerable());
        Map<UUID,ReturnPoint> copy=new HashMap<>(returns);copy.put(p.getUniqueId(),point);try{save(copy);}catch(IOException e){plugin.getLogger().warning("Probe relocation skipped: return point not durable");return false;}returns.put(p.getUniqueId(),point);
        try{p.setInvulnerable(true);p.setAllowFlight(true);p.setFlying(true);p.setFallDistance(0);boolean success=teleport(p,sky);if(!success)restore(p);return success;}catch(RuntimeException failure){try{restore(p);}catch(RuntimeException ignored){}plugin.getLogger().warning("Probe sky fallback stopped; saved return point remains available if recovery is needed");return false;}
    }
    boolean restore(Player p){ReturnPoint point=returns.get(p.getUniqueId());if(point==null)return true;if(!p.isOnline())return false;Location location=original(p.getUniqueId());if(location==null)return false;if(!p.getWorld().equals(location.getWorld())||p.getLocation().distanceSquared(location)>.0001)if(!teleport(p,location))return false;
        boolean vanished=plugin.adminRuntime!=null&&plugin.adminRuntime.vanished(p);boolean mode=p.getGameMode()==GameMode.CREATIVE||p.getGameMode()==GameMode.SPECTATOR||vanished;p.setAllowFlight(point.allowFlight||mode);p.setFlying((point.flying||vanished)&&(point.allowFlight||mode));p.setInvulnerable(p.isOp()&&point.invulnerable);p.setFallDistance(0);
        Map<UUID,ReturnPoint> copy=new HashMap<>(returns);copy.remove(p.getUniqueId());try{save(copy);returns.remove(p.getUniqueId());return true;}catch(IOException e){plugin.getLogger().warning("Probe return succeeded; recovery marker could not be cleared. It will be retried.");return false;}
    }
    private boolean teleport(Player p,Location location){managed.add(p.getUniqueId());try{return p.teleport(location,PlayerTeleportEvent.TeleportCause.PLUGIN);}finally{managed.remove(p.getUniqueId());}}
    private void save(Map<UUID,ReturnPoint> values)throws IOException{Properties p=new Properties();values.forEach((id,point)->p.setProperty(id.toString(),point.encode()));AtomicState.save(file,p,"Probe relocation recovery; original location and flight state");}
    void restoreOnline(){for(Player p:Bukkit.getOnlinePlayers())restore(p);}
    @EventHandler(priority=EventPriority.LOWEST)public void join(PlayerJoinEvent e){if(pending(e.getPlayer().getUniqueId())&&!restore(e.getPlayer()))e.getPlayer().kickPlayer(plugin.playerText(e.getPlayer(),"Dein ursprünglicher Prüfstandort konnte noch nicht wiederhergestellt werden. Bitte melde dich beim Team.","Your original verification location could not be restored yet. Please contact staff."));}
    @EventHandler(priority=EventPriority.LOWEST)public void quit(PlayerQuitEvent e){restore(e.getPlayer());}
    static boolean air(Material m){return m==Material.AIR||m==Material.CAVE_AIR||m==Material.VOID_AIR;}
    static Location find(Player p,boolean quiet,int sequence,Set<String> excluded){Location base=p.getLocation();World world=base.getWorld();if(world==null)return null;
        int[] heights=quiet?new int[]{7,8,9,10,11,12,13,3,4,5,6,14,15,16,17,18,19,20,-3,-4,-5,-6,-7,-8,-9,-10}:new int[]{2,3,4,5,6,7,8,9,10,11,12,-3,-4,-5,-6,-7,-8};
        List<int[]> offsets=new ArrayList<>();offsets.add(new int[]{0,0});for(int radius=1;radius<=8;radius++)for(int[] offset:new int[][]{{radius,0},{-radius,0},{0,radius},{0,-radius},{radius,radius},{-radius,radius},{radius,-radius},{-radius,-radius}})offsets.add(offset);
        int checked=0;for(int n=0;n<offsets.size();n++){int[] offset=offsets.get(Math.floorMod(n+sequence,offsets.size()));for(int dy:heights){if(++checked>2048)return null;Location candidate=base.clone().add(offset[0],dy,offset[1]);String key=candidate.getBlockX()+","+candidate.getBlockY()+","+candidate.getBlockZ();
            if(candidate.getBlockY()<world.getMinHeight()||candidate.getBlockY()>=world.getMaxHeight()||!world.isChunkLoaded(candidate.getBlockX()>>4,candidate.getBlockZ()>>4)||excluded.contains(key))continue;
            if(air(candidate.getBlock().getType()))return candidate;
        }}return null;
    }
}
