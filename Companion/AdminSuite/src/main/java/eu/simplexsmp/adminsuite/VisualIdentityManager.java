package eu.simplexsmp.adminsuite;

import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

/** Owns player-list names and nametags only. Chat display names belong to the chat plugin. */
final class VisualIdentityManager {
    private final SimplexAdminSuite plugin;
    private final Map<UUID,String> originals=new HashMap<>(), expected=new HashMap<>();
    private final Set<UUID> externalListOwners=new HashSet<>();
    private final Map<Scoreboard,Map<UUID,OriginalTeam>> boards=new IdentityHashMap<>();
    private final Map<Scoreboard,Map<UUID,List<String>>> appliedTeams=new IdentityHashMap<>();
    private final Map<String,Long> warnings=new HashMap<>();
    private final Map<UUID,String> diagnostics=new HashMap<>();
    private record OriginalTeam(String name,String prefix,String suffix,boolean foreign) { }
    VisualIdentityManager(SimplexAdminSuite p){plugin=p;}

    void apply(Player target){
        if(target==null)return;
        UUID id=target.getUniqueId();
        String prefix=plugin.getConfig().getBoolean("nameplates.show-luckperms",true)?plugin.expansionLuckPermsPrefix(target):"";
        String suffix=plugin.getConfig().getBoolean("nameplates.show-luckperms",true)?plugin.expansionLuckPermsSuffix(target):"";
        String nick=plugin.getConfig().getBoolean("nicknames.show-in-player-list",true)?plugin.visualName(target):target.getName();
        String tag=plugin.expansionIsAfk(target)&&plugin.getConfig().getBoolean("afk.show-in-player-list",true)?plugin.getConfig().getString("afk.player-list-suffix"," &7[AFK]"):"";
        String name=plugin.color(spaced(prefix)+nick+spacedSuffix(suffix)+tag);
        try{
            if(plugin.getConfig().getBoolean("nameplates.manage-player-list",true)){
                originals.putIfAbsent(id,target.getPlayerListName());
                String previous=expected.get(id);
                if(previous!=null&&!previous.equals(target.getPlayerListName())){externalListOwners.add(id);expected.remove(id);originals.remove(id);diagnostics.put(id,"External player-list owner observed; Suite yields until reconnect.");}
                if(!externalListOwners.contains(id)){if(!name.equals(target.getPlayerListName()))target.setPlayerListName(name);expected.put(id,name);}
            }else restoreList(target);
            // Never decorate Player.displayName: chat formatters already add prefix/suffix.
            Set<Scoreboard> visited=Collections.newSetFromMap(new IdentityHashMap<>());
            for(Player viewer:Bukkit.getOnlinePlayers())if(visited.add(viewer.getScoreboard()))applyTeam(target,viewer.getScoreboard(),prefix,suffix);
        }catch(RuntimeException e){conflict(target,"error",e.getClass().getSimpleName()+": "+e.getMessage());}
    }

    private void applyTeam(Player target,Scoreboard board,String prefix,String suffix){
        UUID id=target.getUniqueId();String entry=target.getName();
        Map<UUID,OriginalTeam> managed=boards.computeIfAbsent(board,b->new HashMap<>());
        Team current=board.getEntryTeam(entry);OriginalTeam original=managed.get(id);
        boolean afk=plugin.expansionIsAfk(target)&&plugin.getConfig().getBoolean("afk.show-above-name",true);
        if(!plugin.getConfig().getBoolean("nameplates.manage-nametags",true)||(!afk&&prefix.isBlank()&&suffix.isBlank())){restoreTeam(board,target);return;}
        String own="sasafk"+id.toString().replace("-","").substring(0,10);
        if(current!=null&&!current.getName().equals(own)&&(original==null||!original.foreign||!original.name.equals(current.getName()))){
            if(!plugin.getConfig().getBoolean("nameplates.decorate-foreign-teams",false)){
                if(original!=null)restoreTeam(board,target);
                conflict(target,"scoreboard","Foreign team '"+current.getName()+"' owns the nametag. Candidates: "+candidates()+". Use that plugin's AFK/LP placeholders, or explicitly enable decorate-foreign-teams.");return;
            }
            if(current.getEntries().size()!=1){conflict(target,"shared-team","Foreign team '"+current.getName()+"' contains multiple entries; decoration skipped to protect other players.");return;}
            if(original!=null)restoreTeam(board,target);
            original=new OriginalTeam(current.getName(),current.getPrefix(),current.getSuffix(),true);managed.put(id,original);
        }
        Team team;
        if(original!=null&&original.foreign){team=board.getTeam(original.name);if(team==null||!team.hasEntry(entry)||team.getEntries().size()!=1){managed.remove(id);return;}}
        else{
            if(original==null){original=new OriginalTeam(current==null?"":current.getName(),"","",false);managed.put(id,original);}
            team=board.getTeam(own);if(team==null)team=board.registerNewTeam(own);
            if(!team.hasEntry(entry))team.addEntry(entry);
        }
        String pre=plugin.color((afk?"&7[AFK] &r":"")+spaced(prefix));String suf=plugin.color(spacedSuffix(suffix));
        if(!pre.equals(team.getPrefix()))team.setPrefix(pre);
        if(!suf.equals(team.getSuffix()))team.setSuffix(suf);
        appliedTeams.computeIfAbsent(board,b->new HashMap<>()).put(id,List.of(pre,suf));
    }
    static String spaced(String s){return s==null||s.isBlank()?"":s+(Character.isWhitespace(s.charAt(s.length()-1))?"":" ");}
    static String spacedSuffix(String s){return s==null||s.isBlank()?"":(Character.isWhitespace(s.charAt(0))?"":" ")+s;}
    private void restoreList(Player p){String old=originals.remove(p.getUniqueId()),last=expected.remove(p.getUniqueId());if(old!=null&&Objects.equals(last,p.getPlayerListName()))p.setPlayerListName(old);}
    private void restoreTeam(Scoreboard board,Player p){
        Map<UUID,OriginalTeam> m=boards.get(board);if(m==null)return;OriginalTeam old=m.remove(p.getUniqueId());if(old==null)return;
        Team current=board.getEntryTeam(p.getName());String own="sasafk"+p.getUniqueId().toString().replace("-","").substring(0,10);
        Map<UUID,List<String>> rendered=appliedTeams.get(board);List<String> last=rendered==null?null:rendered.remove(p.getUniqueId());
        if(old.foreign){if(current!=null&&current.getName().equals(old.name)&&current.getEntries().size()==1&&last!=null&&current.getPrefix().equals(last.get(0))&&current.getSuffix().equals(last.get(1))){current.setPrefix(old.prefix);current.setSuffix(old.suffix);}return;}
        if(current!=null&&current.getName().equals(own)){current.removeEntry(p.getName());if(current.getEntries().isEmpty())current.unregister();Team before=board.getTeam(old.name);if(before!=null)before.addEntry(p.getName());}
    }
    void clear(Player p){externalListOwners.remove(p.getUniqueId());restoreList(p);for(Scoreboard b:new ArrayList<>(boards.keySet()))restoreTeam(b,p);boards.entrySet().removeIf(e->e.getValue().isEmpty());appliedTeams.entrySet().removeIf(e->e.getValue().isEmpty());diagnostics.remove(p.getUniqueId());}
    void stop(){for(Player p:Bukkit.getOnlinePlayers())clear(p);boards.clear();appliedTeams.clear();warnings.clear();}
    void tick(){Set<Scoreboard> active=Collections.newSetFromMap(new IdentityHashMap<>());for(Player p:Bukkit.getOnlinePlayers()){active.add(p.getScoreboard());apply(p);}for(Scoreboard board:new ArrayList<>(boards.keySet()))if(!active.contains(board)){for(UUID id:new ArrayList<>(boards.get(board).keySet())){Player p=Bukkit.getPlayer(id);if(p!=null)restoreTeam(board,p);}boards.remove(board);appliedTeams.remove(board);}}
    String diagnostic(Player p){return "Chat display management: removed (owned by chat plugin)\nList: "+p.getPlayerListName()+"\nDisplay: "+p.getDisplayName()+"\nLP prefix: "+plugin.expansionLuckPermsPrefix(p)+"\nLP suffix: "+plugin.expansionLuckPermsSuffix(p)+"\n"+diagnostics.getOrDefault(p.getUniqueId(),"No observed overwrite.")+"\nCandidates (not proof): "+candidates();}
    private String candidates(){return Arrays.stream(Bukkit.getPluginManager().getPlugins()).filter(p->p.isEnabled()&&p!=plugin).map(p->p.getName()).filter(n->n.toLowerCase(Locale.ROOT).matches(".*(tab|score|name|chat|essentials).*" )).sorted().reduce((a,b)->a+", "+b).orElse("unknown");}
    private void conflict(Player p,String kind,String message){diagnostics.put(p.getUniqueId(),message);String key=p.getUniqueId()+"|"+kind;long now=System.currentTimeMillis();if(plugin.getConfig().getBoolean("nameplates.log-conflicts",false)&&now-warnings.getOrDefault(key,0L)>60000){warnings.put(key,now);plugin.getLogger().warning("[Names] "+p.getName()+": "+message);}}
}
