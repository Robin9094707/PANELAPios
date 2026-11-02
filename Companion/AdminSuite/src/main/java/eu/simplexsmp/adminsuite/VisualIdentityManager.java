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
        String prefix=plugin.getConfig().getBoolean("nameplates.show-luckperms",true)?normalize(plugin.expansionLuckPermsPrefix(target)):"";
        String suffix=plugin.getConfig().getBoolean("nameplates.show-luckperms",true)?normalize(plugin.expansionLuckPermsSuffix(target)):"";
        String nick=plugin.getConfig().getBoolean("nicknames.show-in-player-list",true)?plugin.visualName(target):target.getName();
        String tag=plugin.expansionIsAfk(target)&&plugin.getConfig().getBoolean("afk.show-in-player-list",true)?plugin.getConfig().getString("afk.player-list-suffix"," &7[AFK]"):"";
        // 9.9.0: rank placement is configured separately for the player list and above the head.
        boolean inList=plugin.getConfig().getBoolean("nameplates.rank-in-player-list",true),aboveHead=plugin.getConfig().getBoolean("nameplates.rank-above-head",true);
        String name=plugin.color(spaced(inList?prefix:"")+nick+spacedSuffix(inList?suffix:"")+tag);
        if(!aboveHead){prefix="";suffix="";}
        try{
            if(managesPlayerList()){
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

    /** Tab-list plugins the suite leaves the player list to when nameplates.yield-to-tab-plugin is on (default). */
    static final List<String> TAB_PLUGINS=List.of("TAB","TabList","AnimatedTab","TAB-Bridge");
    static String tabPlugin(){for(String name:TAB_PLUGINS){org.bukkit.plugin.Plugin p=Bukkit.getPluginManager().getPlugin(name);if(p!=null&&p.isEnabled())return p.getName();}return "";}
    boolean managesPlayerList(){
        if(!plugin.getConfig().getBoolean("nameplates.manage-player-list",true))return false;
        return !(plugin.getConfig().getBoolean("nameplates.yield-to-tab-plugin",true)&&!tabPlugin().isEmpty());
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
        // 9.7.0: the name itself takes the rank colour (Java and Geyser/Bedrock read the team colour).
        if(!original.foreign){org.bukkit.ChatColor colour=plugin.getConfig().getBoolean("nameplates.color-names",true)?lastColor(plugin.color(spaced(prefix))):null;if(colour==null)colour=org.bukkit.ChatColor.RESET;try{if(team.getColor()!=colour)team.setColor(colour);}catch(RuntimeException ignored){}}
        appliedTeams.computeIfAbsent(board,b->new HashMap<>()).put(id,List.of(pre,suf));
    }
    private static final java.util.regex.Pattern HEX=java.util.regex.Pattern.compile("(?:&#|\\{#|<#)([0-9a-fA-F]{6})(?:\\}|>)?");
    private static final Map<String,String> TAGS=Map.ofEntries(Map.entry("black","0"),Map.entry("dark_blue","1"),Map.entry("dark_green","2"),Map.entry("dark_aqua","3"),Map.entry("dark_red","4"),Map.entry("dark_purple","5"),Map.entry("gold","6"),Map.entry("gray","7"),Map.entry("grey","7"),Map.entry("dark_gray","8"),Map.entry("dark_grey","8"),Map.entry("blue","9"),Map.entry("green","a"),Map.entry("aqua","b"),Map.entry("red","c"),Map.entry("light_purple","d"),Map.entry("yellow","e"),Map.entry("white","f"),Map.entry("bold","l"),Map.entry("b","l"),Map.entry("italic","o"),Map.entry("i","o"),Map.entry("underlined","n"),Map.entry("u","n"),Map.entry("strikethrough","m"),Map.entry("st","m"),Map.entry("obfuscated","k"),Map.entry("reset","r"));
    /** LuckPerms prefixes in &-codes, §-codes, &#RRGGBB, {#RRGGBB}, <#RRGGBB> or simple MiniMessage colour tags → &-codes. */
    static String normalize(String raw){
        if(raw==null||raw.isBlank())return "";
        String s=raw.replace('§','&');
        java.util.regex.Matcher m=HEX.matcher(s);StringBuilder out=new StringBuilder();
        while(m.find()){StringBuilder code=new StringBuilder("&x");for(char c:m.group(1).toLowerCase(Locale.ROOT).toCharArray())code.append('&').append(c);m.appendReplacement(out,java.util.regex.Matcher.quoteReplacement(code.toString()));}
        m.appendTail(out);s=out.toString();
        java.util.regex.Matcher tag=java.util.regex.Pattern.compile("<(/?)([a-z_]+)>").matcher(s);out=new StringBuilder();
        while(tag.find()){String code=TAGS.get(tag.group(2));tag.appendReplacement(out,java.util.regex.Matcher.quoteReplacement(code==null?tag.group():tag.group(1).isEmpty()?"&"+code:"&r"));}
        tag.appendTail(out);return out.toString();
    }
    /** Last colour of an already translated (§) string; hex colours map to the nearest named colour. */
    static org.bukkit.ChatColor lastColor(String text){
        if(text==null)return null;org.bukkit.ChatColor found=null;
        for(int i=0;i<text.length()-1;i++){if(text.charAt(i)!='§')continue;char c=Character.toLowerCase(text.charAt(i+1));
            if(c=='x'&&i+13<text.length()){StringBuilder hex=new StringBuilder();for(int j=0;j<6;j++)hex.append(text.charAt(i+3+j*2));try{net.kyori.adventure.text.format.NamedTextColor near=net.kyori.adventure.text.format.NamedTextColor.nearestTo(net.kyori.adventure.text.format.TextColor.color(Integer.parseInt(hex.toString(),16)));found=org.bukkit.ChatColor.valueOf(near.toString().toUpperCase(Locale.ROOT));}catch(RuntimeException ignored){}i+=13;continue;}
            org.bukkit.ChatColor code=org.bukkit.ChatColor.getByChar(c);if(code!=null&&code.isColor())found=code;else if(code==org.bukkit.ChatColor.RESET)found=null;}
        return found;
    }
    /** App diagnostics (9.7.0): what each online player shows above the head and in the list. */
    List<Map<String,Object>> report(){
        List<Map<String,Object>> out=new ArrayList<>();
        for(Player p:Bukkit.getOnlinePlayers()){String prefix=normalize(plugin.expansionLuckPermsPrefix(p)),suffix=normalize(plugin.expansionLuckPermsSuffix(p));org.bukkit.ChatColor c=lastColor(plugin.color(prefix));
            Team team=p.getScoreboard().getEntryTeam(p.getName());
            out.add(SimplexAdminSuite.appMap("id",p.getUniqueId().toString(),"name",p.getName(),"prefix",SimplexAdminSuite.stripFormatting(plugin.color(prefix)),"suffix",SimplexAdminSuite.stripFormatting(plugin.color(suffix)),"rawPrefix",prefix,"rawSuffix",suffix,"color",c==null?"":c.name().toLowerCase(Locale.ROOT),"team",team==null?"":team.getName(),"managed",team!=null&&team.getName().startsWith("sasafk"),"status",diagnostics.getOrDefault(p.getUniqueId(),"OK"),"bedrock",plugin.isBedrockClient(p)));}
        out.sort(Comparator.comparing(m->String.valueOf(m.get("name")),String.CASE_INSENSITIVE_ORDER));return out;
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
