package eu.simplexsmp.adminsuite;
import java.nio.file.*;
import java.util.*;
import java.lang.reflect.*;
import org.bukkit.*;
import org.bukkit.entity.Player;

/** 9.3.0 admin center: additive player filters, aggregated dashboard and unchanged legacy filters. */
public final class Release930Harness {
    static int checks;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    @SuppressWarnings("unchecked") static List<Map<String,Object>> rows(Object page){return (List<Map<String,Object>>)((Map<?,?>)page).get("items");}
    static Set<Object> names(Object page){Set<Object> out=new HashSet<>();for(var row:rows(page))out.add(row.get("name"));return out;}
    @SuppressWarnings("unchecked")
    public static void main(String[] args)throws Exception{
        Path folder=Files.createTempDirectory("suite930-");Field serverField=Bukkit.class.getDeclaredField("server");serverField.setAccessible(true);Object old=serverField.get(null);
        try{
            SimplexAdminSuite plugin=Moderation790Harness.plugin(folder);Moderation790Harness.set(plugin,SimplexAdminSuite.class,"appPlayerPages",new LinkedHashMap<>());
            var dbCtor=SimplexAdminSuite.PlayerDatabase.class.getDeclaredConstructor(SimplexAdminSuite.class);dbCtor.setAccessible(true);Object db=dbCtor.newInstance(plugin);Moderation790Harness.set(plugin,SimplexAdminSuite.class,"database",db);
            var ctor=SimplexAdminSuite.PlayerProfile.class.getDeclaredConstructor(UUID.class,String.class);ctor.setAccessible(true);
            Map<UUID,SimplexAdminSuite.PlayerProfile> profiles=(Map<UUID,SimplexAdminSuite.PlayerProfile>)Moderation790Harness.get(db,"profiles");
            long now=System.currentTimeMillis();Class<?> type=SimplexAdminSuite.PlayerProfile.class;
            UUID veteran=new UUID(0,1),banned=new UUID(0,2),warned=new UUID(0,3),fresh=new UUID(0,4),muted=new UUID(0,5);
            Map<UUID,String> named=new LinkedHashMap<>();named.put(veteran,"Veteran");named.put(banned,"Banned");named.put(warned,"Warned");named.put(fresh,"Fresh");named.put(muted,"Muted");
            for(var entry:named.entrySet()){var p=ctor.newInstance(entry.getKey(),entry.getValue());Moderation790Harness.set(p,type,"firstSeen",now-30L*86_400_000L);Moderation790Harness.set(p,type,"lastSeen",now-3_600_000L);profiles.put(entry.getKey(),(SimplexAdminSuite.PlayerProfile)p);}
            Moderation790Harness.set(profiles.get(banned),type,"internalBanUntil",now+86_400_000L);
            Moderation790Harness.set(profiles.get(muted),type,"internalMuteUntil",now+86_400_000L);
            ((List<Object>)Moderation790Harness.get(profiles.get(warned),"warnings")).add(warningEntry());
            Moderation790Harness.set(profiles.get(fresh),type,"firstSeen",now-3_600_000L);
            Map<UUID,Player> online=new LinkedHashMap<>();online.put(veteran,AppApiIntegrationHarness.proxy(Player.class,(p,m,v)->switch(m.getName()){case "getUniqueId"->veteran;case "getName"->"Veteran";default->AppApiIntegrationHarness.empty(m.getReturnType());}));
            Server server=AppApiIntegrationHarness.proxy(Server.class,(p,m,v)->switch(m.getName()){case "getOnlinePlayers"->online.values();case "getPlayer"->v[0] instanceof UUID id?online.get(id):null;case "getMaxPlayers"->20;default->AppApiIntegrationHarness.empty(m.getReturnType());});serverField.set(null,server);
            // Existing filters keep their exact 9.2.0 results.
            check(names(plugin.appApiData("players",Map.of("filter","all"))).size()==5,"all filter unchanged");
            check(names(plugin.appApiData("players",Map.of("filter","online"))).equals(Set.of("Veteran")),"online filter unchanged");
            check(!names(plugin.appApiData("players",Map.of("filter","recent"))).contains("Veteran"),"recent filter unchanged");
            check(names(plugin.appApiData("players",Map.of("filter","flagged"))).equals(Set.of("Banned","Warned","Muted")),"flagged returns ban, mute and warning profiles only");
            check(names(plugin.appApiData("players",Map.of("filter","flagged","q","ban"))).equals(Set.of("Banned")),"flagged filter keeps server-side search");
            var fresh7=rows(plugin.appApiData("players",Map.of("filter","new")));check(fresh7.size()==1&&fresh7.get(0).get("name").equals("Fresh"),"new filter shows only recently joined profiles");
            try{plugin.appApiData("players",Map.of("filter","bogus"));throw new AssertionError("unknown filter accepted");}catch(IllegalArgumentException expected){checks++;}
            // Dashboard tolerates every optional module being absent (reports, events, restart, access...).
            Map<String,Object> dash=(Map<String,Object>)plugin.appAdminDashboard(false);
            check(((Number)dash.get("profiles")).intValue()==5&&((Number)dash.get("online")).intValue()==1,"dashboard counts profiles and online players");
            Map<String,Object> moderation=(Map<String,Object>)dash.get("moderation");
            check(((Number)moderation.get("internalBans")).intValue()==1&&((Number)moderation.get("mutes")).intValue()==1&&((Number)moderation.get("warnedPlayers")).intValue()==1,"dashboard moderation counters");
            check(((Number)dash.get("new24h")).intValue()==1&&((Number)dash.get("newWeek")).intValue()==1,"dashboard new player counters");
            check(rows(Map.of("items",dash.get("players"))).size()==1,"dashboard online player rows");
            check(dash.get("staff")==null,"staff list is withheld from non-full keys");
            check(dash.get("restart")==null&&dash.get("maintenance")==null&&((List<?>)dash.get("events")).isEmpty(),"missing optional modules report null/empty");
            Map<String,Object> detail=(Map<String,Object>)plugin.appApiData("profile",Map.of("player",warned.toString()));
            check(detail.get("activity") instanceof Map<?,?> activity&&activity.containsKey("playMillis")&&activity.containsKey("sessions"),"profile-v3 activity summary");
            check(detail.containsKey("warningEntries")&&detail.containsKey("notes")&&detail.containsKey("previousNames"),"existing detail keys remain");
            System.out.println("Release930Harness: "+checks+" production checks passed");
        }finally{serverField.set(null,old);try(var paths=Files.walk(folder)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
    static Object warningEntry()throws Exception{
        Field f=SimplexAdminSuite.PlayerProfile.class.getDeclaredField("warnings");Type generic=f.getGenericType();
        if(generic instanceof ParameterizedType parameterized&&parameterized.getActualTypeArguments()[0]==String.class)return "Synthetic warning";
        Class<?> element=(Class<?>)((ParameterizedType)generic).getActualTypeArguments()[0];return Release820Harness.allocate(element);
    }
}
