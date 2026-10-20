package eu.simplexsmp.adminsuite;

import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.event.*;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.configuration.file.YamlConfiguration;

/** Probe-only restrictions: separate from existing bans; immutable snapshots for async login. */
final class ClientProbeAccess implements Listener {
    record State(int strikes,long lastFailure,long banUntil,boolean review,String locale,List<String> clients,Map<String,Integer> counts){ }
    private final SimplexAdminSuite plugin;
    private final Path path;
    private final Map<UUID,State> states=new ConcurrentHashMap<>();
    private volatile String invite="";
    ClientProbeAccess(SimplexAdminSuite p){plugin=p;path=p.getDataFolder().toPath().resolve("client-probe-access.yml");load();refreshInvite();}
    State state(UUID id){return states.getOrDefault(id,new State(0,0,0,false,"en_us",List.of(),Map.of()));}
    static boolean restricted(State s,long now){return s.review||s.banUntil>now;}
    static State failure(State old,long now,String locale,List<String> detected,Map<String,Integer> thresholds,long duration,int threshold,long window){
        int count=now-old.lastFailure>window?1:Math.min(100000,old.strikes+1);
        Map<String,Integer> counts=new HashMap<>(now-old.lastFailure>window?Map.of():old.counts);boolean review=old.review||threshold>0&&count>=threshold;
        for(var e:thresholds.entrySet()){int n=Math.min(100000,counts.getOrDefault(e.getKey(),0)+1);counts.put(e.getKey(),n);if(e.getValue()>0&&n>=e.getValue())review=true;}
        long until=duration<=0?old.banUntil:Math.max(old.banUntil,now+duration);
        return new State(count,now,until,review,locale,List.copyOf(detected),Map.copyOf(counts));
    }
    synchronized State record(UUID id,long now,String locale,List<String> detected,Map<String,Integer> thresholds,long duration,int threshold,long window)throws IOException{
        State next=failure(state(id),now,locale,detected,thresholds,duration,threshold,window);Map<UUID,State> copy=new HashMap<>(states);copy.put(id,next);save(copy);states.put(id,next);return next;
    }
    synchronized void release(UUID id)throws IOException{Map<UUID,State> next=new HashMap<>(states);next.remove(id);save(next);states.remove(id);}
    List<UUID> reviewQueue(){return states.entrySet().stream().filter(e->e.getValue().review).map(Map.Entry::getKey).sorted().toList();}
    static boolean german(String locale){return locale!=null&&locale.toLowerCase(Locale.ROOT).startsWith("de");}
    static String message(State s,long now,String invite){
        boolean de=german(s.locale);StringBuilder text=new StringBuilder(de?"§cClient-Überprüfung\n\n":"§cClient verification\n\n");
        if(!s.clients.isEmpty())text.append(de?"Nicht erlaubte Clients/Mods: ":"Disallowed clients/mods: ").append(String.join(", ",s.clients.stream().limit(5).map(n->n.length()>48?n.substring(0,48):n).toList())).append(s.clients.size()>5?" +"+(s.clients.size()-5):"").append("\n");
        if(s.review)text.append(de?"Nach wiederholten Prüfproblemen ist eine Team-Freigabe erforderlich.\nBitte melde dich im Discord beim Team.":"Repeated verification issues require staff approval.\nPlease contact staff on Discord.");
        else text.append(de?"Bitte tritt ohne diese Clients/Mods erneut bei.":"Please rejoin without these clients/mods.");
        if(s.banUntil>now)text.append(de?"\nTemporäre Sperre: noch ":"\nTemporary ban: ").append(Math.max(1,(s.banUntil-now+59999)/60000)).append(de?" Minute(n).":" minute(s) remaining.");
        if(invite!=null&&!invite.isBlank())text.append("\n\n§bDiscord: ").append(invite);
        return text.toString();
    }
    void refreshInvite(){try{invite="https://discord.gg/"+DiscordInvite.parseCode(plugin.getConfig().getString("community.discord-invite-code","2gAsymnXfd"));}catch(IllegalArgumentException invalid){invite="";}}
    String invite(){return invite;}
    @EventHandler(priority=EventPriority.HIGHEST) public void login(AsyncPlayerPreLoginEvent e){
        if(e.getLoginResult()!=AsyncPlayerPreLoginEvent.Result.ALLOWED)return;
        State state=state(e.getUniqueId());if(plugin.emergency(e.getUniqueId())||plugin.clientExempt(e.getUniqueId(),"all")||!state.counts().isEmpty()&&state.counts().keySet().stream().allMatch(k->plugin.clientExempt(e.getUniqueId(),k)))return;if(restricted(state,System.currentTimeMillis()))e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED,message(state,System.currentTimeMillis(),invite()));
    }
    private void load(){if(!Files.exists(path))return;YamlConfiguration y=YamlConfiguration.loadConfiguration(path.toFile());var section=y.getConfigurationSection("players");if(section==null)return;
        for(String id:section.getKeys(false))try{String p="players."+id;Map<String,Integer> counts=new HashMap<>();var c=y.getConfigurationSection(p+".counts");if(c!=null)for(String key:c.getKeys(false))counts.put(key,Math.max(0,c.getInt(key)));states.put(UUID.fromString(id),new State(Math.max(0,y.getInt(p+".strikes")),y.getLong(p+".last-failure"),y.getLong(p+".ban-until"),y.getBoolean(p+".review"),y.getString(p+".locale","en_us"),List.copyOf(y.getStringList(p+".clients")),Map.copyOf(counts)));}catch(IllegalArgumentException ignored){}
    }
    private void save(Map<UUID,State> values)throws IOException{
        YamlConfiguration y=new YamlConfiguration();y.set("schema",1);for(var e:values.entrySet()){String p="players."+e.getKey();State s=e.getValue();y.set(p+".strikes",s.strikes);y.set(p+".last-failure",s.lastFailure);y.set(p+".ban-until",s.banUntil);y.set(p+".review",s.review);y.set(p+".locale",s.locale);y.set(p+".clients",s.clients);y.set(p+".counts",s.counts);}
        Files.createDirectories(path.getParent());Path temp=Files.createTempFile(path.getParent(),"probe-access-",".tmp");try{y.save(temp.toFile());try{Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING);}}finally{Files.deleteIfExists(temp);}
    }
}
