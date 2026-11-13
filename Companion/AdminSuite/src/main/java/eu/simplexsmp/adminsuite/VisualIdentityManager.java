package eu.simplexsmp.adminsuite;

import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

/** Owns player-list names and nametags only. Chat display names belong to the chat plugin. */
final class VisualIdentityManager {
    private final SimplexAdminSuite plugin;
    private final Map<UUID,String> originals=new HashMap<>(), expected=new HashMap<>(), requestedNames=new HashMap<>();
    private final Set<UUID> externalListOwners=new HashSet<>();
    private final Map<Scoreboard,Map<UUID,OriginalTeam>> boards=new IdentityHashMap<>();
    private final Map<Scoreboard,Map<UUID,List<String>>> appliedTeams=new IdentityHashMap<>();
    private final Map<String,Long> warnings=new HashMap<>();
    private final Map<UUID,String> diagnostics=new HashMap<>();
    private record OriginalTeam(String name,String prefix,String suffix,boolean foreign) { }
    VisualIdentityManager(SimplexAdminSuite p){plugin=p;}

    /** Prefix of every nametag team the suite owns (kept for data/diagnostic compatibility). */
    static final String TEAM_PREFIX="sasafk";
    /**
     * 10.19.0: collision-free team name per player. Up to 10.18.0 the name used the first ten hex digits of the UUID.
     * Floodgate/Bedrock UUIDs all start with {@code 0000000000}, so every Bedrock player shared one team and the
     * nametags (and the player list where it falls back to team decoration) swapped prefixes and suffixes every tick.
     * The new name hashes the complete UUID (SHA-256, 40 bits) and stays within the 16-character legacy team limit.
     */
    static String teamName(UUID id){
        try{
            byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(id.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder out=new StringBuilder(TEAM_PREFIX);
            for(int i=0;i<5;i++)out.append(String.format(Locale.ROOT,"%02x",hash[i]&0xff));
            return out.toString();
        }catch(java.security.NoSuchAlgorithmException impossible){
            String hex=id.toString().replace("-","");
            return TEAM_PREFIX+hex.substring(hex.length()-10);
        }
    }
    /** Old (≤ 10.18.0) team name, only used to clean up stale shared teams after the update. */
    static String legacyTeamName(UUID id){return TEAM_PREFIX+id.toString().replace("-","").substring(0,10);}

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
                if(previous!=null&&!previous.equals(target.getPlayerListName())&&!sameVisible(previous,target.getPlayerListName())){externalListOwners.add(id);expected.remove(id);originals.remove(id);requestedNames.remove(id);diagnostics.put(id,"External player-list owner observed; Suite yields until reconnect.");}
                if(!externalListOwners.contains(id)){
                    // Paper re-serialises the list name (hex colours, resets); compare with the value it reads back,
                    // otherwise the suite mistakes its own update for a foreign plugin and gives up the list.
                    String requested=requestedNames.get(id);
                    if(!name.equals(requested)||!Objects.equals(previous,target.getPlayerListName())){target.setPlayerListName(name);requestedNames.put(id,name);}
                    expected.put(id,target.getPlayerListName());
                }
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
        String own=teamName(id);
        if(current!=null&&isSuiteTeam(current)&&!current.getName().equals(own)){leaveStaleTeam(current,entry);current=board.getEntryTeam(entry);}
        if(original!=null&&!original.foreign&&isSuiteName(original.name)){original=new OriginalTeam("","","",false);managed.put(id,original);}
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
            if(original==null){original=new OriginalTeam(current==null||isSuiteTeam(current)?"":current.getName(),"","",false);managed.put(id,original);}
            team=board.getTeam(own);if(team==null)team=board.registerNewTeam(own);
            if(!team.hasEntry(entry))team.addEntry(entry);
            // A suite team belongs to exactly one player. Anything else in it is a leftover and would share the tag.
            for(String other:new ArrayList<>(team.getEntries()))if(!other.equals(entry))team.removeEntry(other);
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
            out.add(SimplexAdminSuite.appMap("id",p.getUniqueId().toString(),"name",p.getName(),"prefix",SimplexAdminSuite.stripFormatting(plugin.color(prefix)),"suffix",SimplexAdminSuite.stripFormatting(plugin.color(suffix)),"rawPrefix",prefix,"rawSuffix",suffix,"color",c==null?"":c.name().toLowerCase(Locale.ROOT),"team",team==null?"":team.getName(),"managed",team!=null&&team.getName().equals(teamName(p.getUniqueId())),"sharedTeam",team!=null&&isSuiteTeam(team)&&team.getEntries().size()>1,"status",diagnostics.getOrDefault(p.getUniqueId(),"OK"),"bedrock",plugin.isBedrockClient(p)));}
        out.sort(Comparator.comparing(m->String.valueOf(m.get("name")),String.CASE_INSENSITIVE_ORDER));return out;
    }
    static String spaced(String s){return s==null||s.isBlank()?"":s+(Character.isWhitespace(s.charAt(s.length()-1))?"":" ");}
    static String spacedSuffix(String s){return s==null||s.isBlank()?"":(Character.isWhitespace(s.charAt(0))?"":" ")+s;}
    static boolean isSuiteName(String name){return name!=null&&name.startsWith(TEAM_PREFIX);}
    private static boolean isSuiteTeam(Team team){return team!=null&&isSuiteName(team.getName());}
    /** Same text once colour codes are removed – Paper may re-order or drop redundant codes when it stores a list name. */
    static boolean sameVisible(String a,String b){return a!=null&&b!=null&&SimplexAdminSuite.stripFormatting(a).equals(SimplexAdminSuite.stripFormatting(b));}
    private static void leaveStaleTeam(Team team,String entry){
        try{team.removeEntry(entry);if(team.getEntries().isEmpty())team.unregister();}catch(RuntimeException ignored){}
    }
    /**
     * Removes entries from suite teams that are not that player's current team (e.g. the old shared
     * {@code sasafk0000000000} Bedrock team) and drops empty leftovers. Foreign teams are never touched.
     */
    void sweep(Scoreboard board){
        Map<String,String> owners=new HashMap<>();
        for(Player p:Bukkit.getOnlinePlayers())owners.put(p.getName(),teamName(p.getUniqueId()));
        sweep(board,owners);
    }
    /** @param owners entry name → the suite team that entry must be in */
    static void sweep(Scoreboard board,Map<String,String> owners){
        for(Team team:new ArrayList<>(board.getTeams())){
            if(!isSuiteTeam(team))continue;
            try{
                for(String entry:new ArrayList<>(team.getEntries())){String owner=owners.get(entry);if(owner==null||!owner.equals(team.getName()))team.removeEntry(entry);}
                if(team.getEntries().isEmpty())team.unregister();
            }catch(RuntimeException ignored){}
        }
    }
    private void restoreList(Player p){requestedNames.remove(p.getUniqueId());String old=originals.remove(p.getUniqueId()),last=expected.remove(p.getUniqueId());if(old!=null&&Objects.equals(last,p.getPlayerListName()))p.setPlayerListName(old);}
    private void restoreTeam(Scoreboard board,Player p){
        Map<UUID,OriginalTeam> m=boards.get(board);if(m==null)return;OriginalTeam old=m.remove(p.getUniqueId());if(old==null)return;
        Team current=board.getEntryTeam(p.getName());String own=teamName(p.getUniqueId());
        Map<UUID,List<String>> rendered=appliedTeams.get(board);List<String> last=rendered==null?null:rendered.remove(p.getUniqueId());
        if(old.foreign){if(current!=null&&current.getName().equals(old.name)&&current.getEntries().size()==1&&last!=null&&current.getPrefix().equals(last.get(0))&&current.getSuffix().equals(last.get(1))){current.setPrefix(old.prefix);current.setSuffix(old.suffix);}return;}
        if(current!=null&&isSuiteTeam(current)){current.removeEntry(p.getName());if(current.getEntries().isEmpty())current.unregister();Team before=old.name.isEmpty()||isSuiteName(old.name)?null:board.getTeam(old.name);if(before!=null)before.addEntry(p.getName());}
    }
    void clear(Player p){externalListOwners.remove(p.getUniqueId());restoreList(p);for(Scoreboard b:new ArrayList<>(boards.keySet()))restoreTeam(b,p);boards.entrySet().removeIf(e->e.getValue().isEmpty());appliedTeams.entrySet().removeIf(e->e.getValue().isEmpty());diagnostics.remove(p.getUniqueId());}
    void stop(){for(Player p:Bukkit.getOnlinePlayers())clear(p);requestedNames.clear();boards.clear();appliedTeams.clear();warnings.clear();}
    void tick(){Set<Scoreboard> active=Collections.newSetFromMap(new IdentityHashMap<>());for(Player p:Bukkit.getOnlinePlayers())active.add(p.getScoreboard());for(Scoreboard board:active)sweep(board);for(Player p:Bukkit.getOnlinePlayers())apply(p);for(Scoreboard board:new ArrayList<>(boards.keySet()))if(!active.contains(board)){for(UUID id:new ArrayList<>(boards.get(board).keySet())){Player p=Bukkit.getPlayer(id);if(p!=null)restoreTeam(board,p);}boards.remove(board);appliedTeams.remove(board);}}
    String diagnostic(Player p){return "Chat display management: removed (owned by chat plugin)\nList: "+p.getPlayerListName()+"\nDisplay: "+p.getDisplayName()+"\nLP prefix: "+plugin.expansionLuckPermsPrefix(p)+"\nLP suffix: "+plugin.expansionLuckPermsSuffix(p)+"\n"+diagnostics.getOrDefault(p.getUniqueId(),"No observed overwrite.")+"\nCandidates (not proof): "+candidates();}
    private String candidates(){return Arrays.stream(Bukkit.getPluginManager().getPlugins()).filter(p->p.isEnabled()&&p!=plugin).map(p->p.getName()).filter(n->n.toLowerCase(Locale.ROOT).matches(".*(tab|score|name|chat|essentials).*" )).sorted().reduce((a,b)->a+", "+b).orElse("unknown");}
    private void conflict(Player p,String kind,String message){diagnostics.put(p.getUniqueId(),message);String key=p.getUniqueId()+"|"+kind;long now=System.currentTimeMillis();if(plugin.getConfig().getBoolean("nameplates.log-conflicts",false)&&now-warnings.getOrDefault(key,0L)>60000){warnings.put(key,now);plugin.getLogger().warning("[Names] "+p.getName()+": "+message);}}
}
