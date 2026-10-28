package eu.simplexsmp.adminsuite;
import java.io.*;import java.nio.file.*;import java.util.*;
import org.bukkit.*;import org.bukkit.entity.Player;import org.bukkit.event.*;import org.bukkit.event.player.*;
/** Additive 9.0.5 state. No legacy profile/config migration. */
final class SuitePolicies implements Listener {
 final SimplexAdminSuite plugin;final Path file;final Properties state=new Properties();
 SuitePolicies(SimplexAdminSuite p)throws IOException{plugin=p;file=p.getDataFolder().toPath().resolve("suite-policies.properties");if(Files.exists(file))try(Reader r=Files.newBufferedReader(file)){state.load(r);}Bukkit.getPluginManager().registerEvents(this,p);}
 synchronized boolean ownsRight(UUID id,String right){return state.containsKey("right."+id+"."+right);}
 synchronized boolean inherit(UUID id){return Boolean.parseBoolean(state.getProperty("inherit."+id,"true"));}
 synchronized void set(String key,String value)throws IOException{Properties next=new Properties();next.putAll(state);next.setProperty(key,value);AtomicState.save(file,next,"Suite policies v1");state.clear();state.putAll(next);}
 synchronized void changeInheritance(UUID id,boolean enabled)throws IOException{set("inherit."+id,Boolean.toString(enabled));Set<UUID> targets=new HashSet<>(plugin.controlLinked(id));targets.add(id);for(UUID target:targets)reconcileAll(target);}
 synchronized void recordRight(UUID source,String right,boolean blocked){try{set("right."+source+"."+right,String.valueOf(blocked));for(UUID id:plugin.controlLinked(source))if(!id.equals(source))reconcile(id,right,new HashMap<>());}catch(IOException e){throw new IllegalStateException("Rechtevererbung konnte nicht gespeichert werden",e);}}
 synchronized void reconcileAll(UUID target){Map<UUID,Set<UUID>> links=new HashMap<>();Set<String> rights=new HashSet<>();for(String key:state.stringPropertyNames())if(key.startsWith("right.")){String[] a=key.split("\\.",3);if(a.length==3)rights.add(a[2]);}for(String right:rights)try{reconcile(target,right,links);}catch(IOException e){throw new IllegalStateException(e);}}
 private void reconcile(UUID target,String right,Map<UUID,Set<UUID>> links)throws IOException {
  if(state.containsKey("right."+target+"."+right))return;
  boolean denied=false;if(inherit(target))for(String key:state.stringPropertyNames())if(key.startsWith("right.")&&key.endsWith("."+right)&&Boolean.parseBoolean(state.getProperty(key))){String[] a=key.split("\\.",3);UUID source=UUID.fromString(a[1]);if(inherit(source)&&links.computeIfAbsent(source,plugin::controlLinked).contains(target)){denied=true;break;}}
  String marker="derived."+target+"."+right;
  if(denied){if(!state.containsKey(marker))set(marker,plugin.appRightSnapshot(target,right));plugin.appRightDirect(target,right,true,"Alt-Vererbung");}
  else if(state.containsKey(marker)){plugin.appRightRestore(target,right,state.getProperty(marker));Properties next=new Properties();next.putAll(state);next.remove(marker);AtomicState.save(file,next,"Suite policies v1");state.clear();state.putAll(next);}
 }
 @EventHandler(priority=EventPriority.MONITOR)public void join(PlayerJoinEvent e){Bukkit.getScheduler().runTaskLater(plugin,()->apply(e.getPlayer()),100L);}
 void apply(Player player){if(!player.isOnline())return;String raw=ClientProbeDiagnostics.brand(player);if(Boolean.parseBoolean(state.getProperty("brand."+raw.toLowerCase(Locale.ROOT),"false"))&&!player.isOp()){plugin.suiteKick(player,plugin.playerText(player,"Dieser Java-Client-Brand ist auf diesem Server gesperrt. Bitte melde dich beim Team.","This Java client brand is blocked. Please contact staff.")+"\nhttps://discord.gg/"+plugin.getConfig().getString("community.discord-invite-code",""));return;}reconcileAll(player.getUniqueId());}
}
