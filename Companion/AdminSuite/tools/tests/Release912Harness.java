package eu.simplexsmp.adminsuite;

import java.nio.file.*;
import java.util.*;
import java.lang.reflect.*;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;

/** Production evidence filtering, retroactive persisted ban repair and reversible IP state. */
public final class Release912Harness {
    static int checks;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static Object invoke(Object target,String name,Class<?>[] types,Object... args)throws Exception {Method method=target.getClass().getDeclaredMethod(name,types);method.setAccessible(true);try{return method.invoke(target,args);}catch(InvocationTargetException e){throw new RuntimeException(e.getCause());}}
    static AltMatchCacheEngine.Profile profile(UUID id,Map<String,Long> ips,Map<String,Long> devices){return new AltMatchCacheEngine.Profile(id,id.toString(),1,ips,devices,Map.of(),Map.of(),Set.of(),Set.of());}
    public static void main(String[] args)throws Exception {
        Path folder=Files.createTempDirectory("suite912-");Field serverField=Bukkit.class.getDeclaredField("server");serverField.setAccessible(true);Object oldServer=serverField.get(null);
        try {
            AltIpExclusions rules=new AltIpExclusions(folder.resolve("ip-policy.properties"));
            check(rules.contains("82.165.45.159"),"default infrastructure IP excluded");
            rules.change("2001:db8::1",true);check(rules.contains("2001:0db8:0:0:0:0:0:1"),"IPv6 canonical aliases excluded");
            rules.change(AltIpExclusions.DEFAULT_IP,false);check(!new AltIpExclusions(folder.resolve("ip-policy.properties")).contains(AltIpExclusions.DEFAULT_IP),"explicit default release survives restart");
            rules.change(AltIpExclusions.DEFAULT_IP,true);check(new AltIpExclusions(folder.resolve("ip-policy.properties")).values().size()==2,"multiple exclusions persist");
            try{rules.change("example.org",true);throw new AssertionError("hostname accepted");}catch(IllegalArgumentException expected){checks++;}
            UUID a=UUID.randomUUID(),b=UUID.randomUUID(),c=UUID.randomUUID();long now=System.currentTimeMillis();
            var shared=Map.of(AltIpExclusions.DEFAULT_IP,now);var clean=rules.evidence(shared);
            var graph=AltMatchCacheEngine.build(List.of(profile(a,clean,Map.of()),profile(b,clean,Map.of())),1,100,70,now);
            check(graph.counts.get(a)==0&&shared.size()==1,"excluded raw IP stays stored but cannot link accounts");
            var other=Map.of(AltIpExclusions.DEFAULT_IP,now,"8.8.8.8",now);
            graph=AltMatchCacheEngine.build(List.of(profile(a,rules.evidence(other),Map.of()),profile(b,rules.evidence(other),Map.of())),1,100,70,now);
            check(graph.counts.get(a)==1&&!graph.sameStrongComponent(a,b),"another shared IP preserves weak link");
            graph=AltMatchCacheEngine.build(List.of(profile(a,clean,Map.of("real-device",now)),profile(b,clean,Map.of("real-device",now))),1,100,70,now);
            check(graph.sameStrongComponent(a,b),"independent shared device retained");
            SimplexAdminSuite plugin=Moderation790Harness.plugin(folder);Server server=AppApiIntegrationHarness.proxy(Server.class,(p,m,v)->switch(m.getName()){case "getOnlinePlayers"->List.of();default->AppApiIntegrationHarness.empty(m.getReturnType());});serverField.set(null,server);
            var dbCtor=SimplexAdminSuite.PlayerDatabase.class.getDeclaredConstructor(SimplexAdminSuite.class);dbCtor.setAccessible(true);Object db=dbCtor.newInstance(plugin);Moderation790Harness.set(plugin,SimplexAdminSuite.class,"database",db);
            var pCtor=SimplexAdminSuite.PlayerProfile.class.getDeclaredConstructor(UUID.class,String.class);pCtor.setAccessible(true);
            Map<UUID,Object> profiles=(Map<UUID,Object>)Moderation790Harness.get(db,"profiles");
            for(UUID id:List.of(a,b,c)){Object p=pCtor.newInstance(id,id.equals(a)?"Source":id.equals(b)?"FalseAlt":"OtherIpAlt");profiles.put(id,p);Moderation790Harness.set(p,p.getClass(),"lastIpHash",AltIpExclusions.DEFAULT_IP);Moderation790Harness.set(p,p.getClass(),"lastIpMasked",AltIpExclusions.DEFAULT_IP);((Map<String,Long>)Moderation790Harness.get(p,"altIpHistory")).put(AltIpExclusions.DEFAULT_IP,now);}
            for(UUID id:List.of(a,c))((Map<String,Long>)Moderation790Harness.get(profiles.get(id),"altIpHistory")).put("8.8.8.8",now);
            Class<?> altClass=Class.forName("eu.simplexsmp.adminsuite.SimplexAdminSuite$AdvancedAltDetection");var ctor=altClass.getDeclaredConstructor(SimplexAdminSuite.class);ctor.setAccessible(true);Object alt=ctor.newInstance(plugin);Moderation790Harness.set(plugin,SimplexAdminSuite.class,"altDetection",alt);
            Class<?> groupClass=Class.forName(altClass.getName()+"$UltraBanGroup");var gc=groupClass.getDeclaredConstructors()[0];gc.setAccessible(true);Object group=gc.newInstance("UB-FIX",a,"Source","Fixture","Manual source",now,Long.MAX_VALUE,true);
            ((Map<String,Object>)Moderation790Harness.get(alt,"ultraGroups")).put("ub-fix",group);
            for(Object p:profiles.values())invoke(alt,"absorbProfile",new Class<?>[]{groupClass,SimplexAdminSuite.PlayerProfile.class,boolean.class,String.class},group,p,true,"legacy IP absorption");
            invoke(alt,"saveUltraBans",new Class<?>[]{});
            Object falseProfile=profiles.get(b);Moderation790Harness.set(falseProfile,falseProfile.getClass(),"internalBanUntil",Long.MAX_VALUE);
            Map<?,?> result=(Map<?,?>)invoke(alt,"repairIps",new Class<?>[]{String.class,Path.class},"Fixture",null);
            Map<UUID,Object> members=(Map<UUID,Object>)Moderation790Harness.get(group,"members");
            check(members.containsKey(a)&&!members.containsKey(b)&&members.containsKey(c),"retroactive repair removes only excluded-IP-only member and preserves another shared IP");
            Map<String,Object> identities=(Map<String,Object>)Moderation790Harness.get(group,"identities");
            check(!identities.containsKey("IP|"+AltIpExclusions.DEFAULT_IP)&&!identities.containsKey("UUID|"+b),"excluded and derived UUID anchors removed");
            check((long)Moderation790Harness.get(falseProfile,"internalBanUntil")==Long.MAX_VALUE,"independent manual account ban preserved");
            check(result.get("ok").equals(true)&&Files.isDirectory(folder.resolve("alt-detection/ip-repair-backups").resolve(String.valueOf(result.get("backup")))),"repair backup created");
            invoke(alt,"loadUltraBans",new Class<?>[]{});Object reloaded=((Map<?,?>)Moderation790Harness.get(alt,"ultraGroups")).get("ub-fix");check(!((Map<?,?>)Moderation790Harness.get(reloaded,"members")).containsKey(b),"corrected member data persists to disk");
            check(AppApiManager.scope("alts.ip").equals("full"),"IP policy mutations require full scope");
            System.out.println("Release912Harness: "+checks+" production checks passed");
        }finally{serverField.set(null,oldServer);try(var paths=Files.walk(folder)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
}
