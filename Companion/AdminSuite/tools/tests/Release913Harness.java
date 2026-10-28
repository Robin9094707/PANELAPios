package eu.simplexsmp.adminsuite;
import java.nio.file.*;
import java.util.*;
import java.lang.reflect.*;
import org.bukkit.*;
import org.bukkit.entity.Player;

/** Exercises the real paged directory with 2,505 stored profiles and two online players. */
public final class Release913Harness {
    static int checks;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static Map<?,?> page(SimplexAdminSuite plugin,Map<String,String> q){return (Map<?,?>)plugin.appApiData("players",q);}
    static List<Map<String,Object>> rows(Map<?,?> page){return (List<Map<String,Object>>)page.get("items");}
    public static void main(String[] args)throws Exception{
        Path folder=Files.createTempDirectory("suite913-");Field serverField=Bukkit.class.getDeclaredField("server");serverField.setAccessible(true);Object old=serverField.get(null);
        try{
            SimplexAdminSuite plugin=Moderation790Harness.plugin(folder);Moderation790Harness.set(plugin,SimplexAdminSuite.class,"appPlayerPages",new LinkedHashMap<>());
            var dbCtor=SimplexAdminSuite.PlayerDatabase.class.getDeclaredConstructor(SimplexAdminSuite.class);dbCtor.setAccessible(true);Object db=dbCtor.newInstance(plugin);Moderation790Harness.set(plugin,SimplexAdminSuite.class,"database",db);
            var ctor=SimplexAdminSuite.PlayerProfile.class.getDeclaredConstructor(UUID.class,String.class);ctor.setAccessible(true);
            Map<UUID,SimplexAdminSuite.PlayerProfile> profiles=(Map<UUID,SimplexAdminSuite.PlayerProfile>)Moderation790Harness.get(db,"profiles");List<UUID> ids=new ArrayList<>();
            for(int i=0;i<2505;i++){UUID id=new UUID(0,i+1);ids.add(id);var p=ctor.newInstance(id,String.format(Locale.ROOT,"Player%04d",i));Moderation790Harness.set(p,p.getClass(),"lastSeen",(long)i+1000);profiles.put(id,(SimplexAdminSuite.PlayerProfile)p);}
            Map<UUID,Player> online=new LinkedHashMap<>();for(UUID id:ids.subList(0,2))online.put(id,AppApiIntegrationHarness.proxy(Player.class,(p,m,v)->switch(m.getName()){case "getUniqueId"->id;default->AppApiIntegrationHarness.empty(m.getReturnType());}));
            Server server=AppApiIntegrationHarness.proxy(Server.class,(p,m,v)->switch(m.getName()){case "getOnlinePlayers"->online.values();case "getPlayer"->online.get(v[0]);default->AppApiIntegrationHarness.empty(m.getReturnType());});serverField.set(null,server);
            Map<?,?> first=page(plugin,Map.of("filter","recent"));check(rows(first).size()==50&&((Number)first.get("total")).intValue()==2503,"recent maps only first 50 matching profiles");check(rows(first).get(0).get("name").equals("Player2504"),"recent is globally last-seen sorted on server");check(rows(first).stream().noneMatch(p->Boolean.TRUE.equals(p.get("online"))),"recent contains no online rows");
            String token=String.valueOf(first.get("snapshot"));Moderation790Harness.set(profiles.get(ids.get(2)),SimplexAdminSuite.PlayerProfile.class,"lastSeen",Long.MAX_VALUE);
            Map<?,?> second=page(plugin,Map.of("filter","recent","offset","50","snapshot",token));Set<Object> unique=new HashSet<>();for(var p:rows(first))unique.add(p.get("id"));for(var p:rows(second))check(unique.add(p.get("id")),"stable snapshot pagination has no duplicate");
            Map<?,?> refreshed=page(plugin,Map.of("filter","recent"));check(rows(refreshed).get(0).get("id").equals(ids.get(2).toString()),"explicit first-page refresh sees latest activity");
            Map<?,?> found=page(plugin,Map.of("q","player2499"));check(rows(found).size()==1&&rows(found).get(0).get("name").equals("Player2499"),"server search finds a profile outside first page");
            // A map that rejects full scans proves the online path never traverses offline profiles.
            Map<UUID,SimplexAdminSuite.PlayerProfile> guarded=new LinkedHashMap<>(profiles){@Override public Collection<SimplexAdminSuite.PlayerProfile> values(){throw new AssertionError("Online filter scanned offline database");}};
            Moderation790Harness.set(db,db.getClass(),"profiles",guarded);
            var live=page(plugin,Map.of("filter","online"));check(rows(live).size()==2&&rows(live).stream().allMatch(p->Boolean.TRUE.equals(p.get("online"))),"online visits only Bukkit online players");
            check(rows(page(plugin,Map.of("filter","online","q","2499"))).isEmpty(),"online search cannot return offline profile");Moderation790Harness.set(db,db.getClass(),"profiles",profiles);
            try{page(plugin,Map.of("filter","all","offset","50","snapshot",token));throw new AssertionError("mismatched snapshot accepted");}catch(IllegalArgumentException expected){checks++;}
            VpnWhitelistStore store=new VpnWhitelistStore(folder.resolve("whitelist.txt").toFile());store.load();store.add(ids.get(0),"Player0000","OriginalAdmin");long created=store.entries().get(0).addedAt();
            VpnProtectionManager vpn=(VpnProtectionManager)Release820Harness.allocate(VpnProtectionManager.class);Moderation790Harness.set(vpn,VpnProtectionManager.class,"plugin",plugin);Moderation790Harness.set(vpn,VpnProtectionManager.class,"whitelist",store);Moderation790Harness.set(plugin,SimplexAdminSuite.class,"vpnProtection",vpn);
            try{vpn.appWhitelistPlan(ids.get(0).toString(),"true");throw new AssertionError("duplicate preview accepted");}catch(IllegalArgumentException e){check(e.getMessage().contains("bereits"),"duplicate whitelist preview gives clear error");}
            try{vpn.appWhitelist("AnotherAdmin",ids.get(0).toString(),true);throw new AssertionError("duplicate execution accepted");}catch(IllegalArgumentException e){check(e.getMessage().contains("bereits"),"execution rechecks duplicate");}
            check(store.entries().size()==1&&store.entries().get(0).addedBy().equals("OriginalAdmin")&&store.entries().get(0).addedAt()==created,"duplicate does not rewrite original whitelist provenance");
            System.out.println("Release913Harness: "+checks+" production checks passed");
        }finally{serverField.set(null,old);try(var paths=Files.walk(folder)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
}
