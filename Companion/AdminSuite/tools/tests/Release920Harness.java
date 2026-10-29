package eu.simplexsmp.adminsuite;
import java.nio.file.*;
import java.util.*;
import java.lang.reflect.*;
import org.bukkit.*;
import org.bukkit.entity.Player;

/** Permission migration and authorization regression checks against production managers. */
public final class Release920Harness {
    static int checks;
    static void check(boolean v,String m){checks++;if(!v)throw new AssertionError(m);}
    static void denied(Runnable action,String message){try{action.run();throw new AssertionError(message);}catch(IllegalArgumentException expected){checks++;}}
    public static void main(String[] args)throws Exception{
        Path folder=Files.createTempDirectory("suite920-");Field f=Bukkit.class.getDeclaredField("server");f.setAccessible(true);Object old=f.get(null);
        try{
            UUID user=UUID.randomUUID(),normal=UUID.randomUUID(),op=UUID.randomUUID();Map<UUID,Player> players=new HashMap<>();Set<String> external=new HashSet<>();List<World> loadedWorlds=new ArrayList<>();
            org.bukkit.scheduler.BukkitTask task=AppApiIntegrationHarness.proxy(org.bukkit.scheduler.BukkitTask.class,(x,m,v)->AppApiIntegrationHarness.empty(m.getReturnType()));
            org.bukkit.scheduler.BukkitScheduler scheduler=AppApiIntegrationHarness.proxy(org.bukkit.scheduler.BukkitScheduler.class,(x,m,v)->m.getName().equals("runTaskTimer")?task:AppApiIntegrationHarness.empty(m.getReturnType()));
            Server server=AppApiIntegrationHarness.proxy(Server.class,(p,m,v)->switch(m.getName()){
                case "getPlayer"->players.get(v[0]);case "getOnlinePlayers"->players.values();case "getWorlds"->loadedWorlds;case "getScheduler"->scheduler;
                case "getOfflinePlayer"->{UUID id=(UUID)v[0];yield AppApiIntegrationHarness.proxy(OfflinePlayer.class,(x,n,a)->switch(n.getName()){case "isOp"->id.equals(op);case "getUniqueId"->id;default->AppApiIntegrationHarness.empty(n.getReturnType());});}
                default->AppApiIntegrationHarness.empty(m.getReturnType());});f.set(null,server);
            SimplexAdminSuite plugin=Moderation790Harness.plugin(folder);
            Moderation790Harness.set(plugin,SimplexAdminSuite.class,"pendingInputs",new HashMap<>());Moderation790Harness.set(plugin,SimplexAdminSuite.class,"pendingActions",new HashMap<>());
            Properties legacy=new Properties();legacy.setProperty(user+".role","SUPPORT");legacy.setProperty(user+".permission.simplexadminsuite.action.kick","true");legacy.setProperty("future.unknown.key","keep");
            try(var out=Files.newBufferedWriter(folder.resolve("admin-access.properties"))){legacy.store(out,"");}
            AdminAccessManager access=new AdminAccessManager(plugin);plugin.adminAccess=access;
            check(Files.exists(folder.resolve("admin-access-before-9.2.0.properties")),"migration creates backup before writing");check(access.role(user)==AdminAccessManager.Role.SUPPORT&&Boolean.TRUE.equals(access.override(user,"simplexadminsuite.action.kick")),"migration preserves legacy role/override");
            Properties migrated=new Properties();try(var in=Files.newBufferedReader(folder.resolve("admin-access.properties"))){migrated.load(in);}check(migrated.getProperty("future.unknown.key").equals("keep"),"migration preserves unknown keys");
            check(!access.hasAccess(normal,null,Map.of("simplexadminsuite.shop.use",true,"simplexadminsuite.report",true)),"ordinary player settings/gameplay excluded");check(access.hasAccess(normal,null,Map.of("simplexadminsuite.action.kick",true)),"single inherited kick grants admin entry");check(access.hasAccess(op,null,Map.of()),"offline operator included");
            access.changeAuthorized("Test",user,"simplexadminsuite.action.kick",null,AdminAccessManager.Role.JUNIOR_ADMIN);
            Player junior=AppApiIntegrationHarness.proxy(Player.class,(p,m,v)->switch(m.getName()){case "getUniqueId"->user;case "getName"->"Junior";case "isOp"->false;case "hasPermission"->external.contains(String.valueOf(v[0]));default->AppApiIntegrationHarness.empty(m.getReturnType());});
            check(plugin.has(junior,"simplexadminsuite.admin"),"Junior can use /sas");check(plugin.has(junior,"simplexadminsuite.action.kick"),"Junior may kick");check(!plugin.has(junior,"simplexadminsuite.action.ban"),"Junior cannot permanent ban");check(!access.punishmentError(junior,"tempban","1h").isEmpty(),"Junior cannot tempban");
            access.changeAuthorized("Test",user,null,null,AdminAccessManager.Role.TEMP_MODERATOR);
            check(access.punishmentError(junior,"tempban","3d").isEmpty(),"exact three-day limit accepted");check(!access.punishmentError(junior,"tempban","3d1s").isEmpty(),"three days plus one second denied");check(!access.punishmentError(junior,"ban","").isEmpty(),"permanent ban denied despite broad bans permission");check(!access.altBans(junior),"temporary role cannot identity/alt ban");
            access.saveCustom("Test","custom-junior","Mein Junior-Team","simplexadminsuite.action.kick",259200,false);access.assign("Test",normal,"CUSTOM:custom-junior");
            check(access.role(normal)==AdminAccessManager.Role.CUSTOM&&access.effective(normal,"simplexadminsuite.action.kick",null,Map.of()),"custom assignment effective");check(!access.effective(normal,"simplexadminsuite.action.ban",null,Map.of()),"custom role grants no unrelated permission");
            check(access.roleLabel(normal).equals("Mein Junior-Team"),"directory uses custom role display name");
            denied(()->access.limits("Test",normal,3600,false),"custom role limits must be changed on the role");
            AdminAccessManager reload=new AdminAccessManager(plugin);check(reload.customRole(normal).equals("custom-junior")&&reload.customAllows(normal,"simplexadminsuite.action.kick"),"custom roles and assignments survive reload");
            denied(()->access.saveCustom("Test","bad","X","simplexadminsuite.not-real",1,false),"unknown permission must fail");denied(()->access.changeAuthorized("Test",op,null,null,AdminAccessManager.Role.NONE),"operator must remain protected");
            external.add("simplexadminsuite.action.kick");access.changeAuthorized("Test",user,null,null,AdminAccessManager.Role.INHERIT);
            check(plugin.has(junior,"simplexadminsuite.admin")&&plugin.has(junior,"simplexadminsuite.action.kick"),"single inherited action grants /sas and kick without broad bans node");external.clear();
            access.changeAuthorized("Test",normal,"simplexadminsuite.action.kick",true,AdminAccessManager.Role.NONE);
            check(!access.hasAccess(normal,null,Map.of("simplexadminsuite.admin",true))&&!access.effective(normal,"simplexadminsuite.action.kick",null,Map.of()),"no-access role suspends retained explicit rules");
            check(Boolean.TRUE.equals(access.override(normal,"simplexadminsuite.action.kick")),"no-access role preserves rules for later restoration");
            // Dimension block must run before the usual admin/managed-teleport bypasses.
            var dbCtor=SimplexAdminSuite.PlayerDatabase.class.getDeclaredConstructor(SimplexAdminSuite.class);dbCtor.setAccessible(true);Object db=dbCtor.newInstance(plugin);Moderation790Harness.set(plugin,SimplexAdminSuite.class,"database",db);
            var profileCtor=SimplexAdminSuite.PlayerProfile.class.getDeclaredConstructor(UUID.class,String.class);profileCtor.setAccessible(true);var profile=(SimplexAdminSuite.PlayerProfile)profileCtor.newInstance(user,"Junior");profile.setRightBlocked("nether",true);profile.setRightBlocked("end",true);
            ((Map<UUID,SimplexAdminSuite.PlayerProfile>)Moderation790Harness.get(db,"profiles")).put(user,profile);
            World overworld=AppApiIntegrationHarness.proxy(World.class,(x,m,v)->switch(m.getName()){case "getEnvironment"->World.Environment.NORMAL;case "getSpawnLocation"->new Location((World)x,0,70,0);default->AppApiIntegrationHarness.empty(m.getReturnType());});
            World nether=AppApiIntegrationHarness.proxy(World.class,(x,m,v)->m.getName().equals("getEnvironment")?World.Environment.NETHER:AppApiIntegrationHarness.empty(m.getReturnType()));
            World end=AppApiIntegrationHarness.proxy(World.class,(x,m,v)->m.getName().equals("getEnvironment")?World.Environment.THE_END:AppApiIntegrationHarness.empty(m.getReturnType()));
            for(World destination:List.of(nether,end))for(var cause:List.of(org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.COMMAND,org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN)){
                var event=new org.bukkit.event.player.PlayerTeleportEvent(junior,new Location(overworld,0,70,0),new Location(destination,0,70,0),cause);plugin.onTeleport(event);check(event.isCancelled(),"forbidden dimension blocks home/plugin teleport: "+cause);
            }
            check(!plugin.dimensionBlocked(junior,new Location(overworld,0,70,0)),"overworld remains accessible");
            loadedWorlds.add(overworld);Moderation790Harness.set(plugin,SimplexAdminSuite.class,"dimensionEvacuations",new HashSet<UUID>());profile.setRightBlocked("teleportation",true);
            java.util.concurrent.atomic.AtomicReference<Location> evacuated=new java.util.concurrent.atomic.AtomicReference<>();
            Player traveler=AppApiIntegrationHarness.proxy(Player.class,(x,m,v)->switch(m.getName()){
                case "getUniqueId"->user;case "getLocation"->new Location(nether,0,70,0);
                case "teleport"->{var e=new org.bukkit.event.player.PlayerTeleportEvent((Player)x,new Location(nether,0,70,0),(Location)v[0],org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);plugin.onTeleport(e);if(!e.isCancelled())evacuated.set((Location)v[0]);yield !e.isCancelled();}
                default->AppApiIntegrationHarness.empty(m.getReturnType());});
            plugin.enforceDimension(traveler);check(evacuated.get()!=null&&evacuated.get().getWorld()==overworld,"revocation evacuates immediately even when ordinary teleports are disabled");
            var auditCtor=SimplexAdminSuite.AuditLogger.class.getDeclaredConstructor(SimplexAdminSuite.class);auditCtor.setAccessible(true);Moderation790Harness.set(plugin,SimplexAdminSuite.class,"audit",auditCtor.newInstance(plugin));
            Class<?> restartClass=Class.forName("eu.simplexsmp.adminsuite.SimplexAdminSuite$RestartManager");var restartCtor=restartClass.getDeclaredConstructor(SimplexAdminSuite.class);restartCtor.setAccessible(true);Moderation790Harness.set(plugin,SimplexAdminSuite.class,"restartManager",restartCtor.newInstance(plugin));
            plugin.appRestart(Map.of("operation","schedule","seconds",60,"reasonDE","Wartung","reasonEN","Maintenance"),"Fixture");Map<?,?> state=(Map<?,?>)plugin.appOperations();
            check(Boolean.TRUE.equals(state.get("active"))&&state.get("remaining").equals(60)&&state.get("reasonDE").equals("Wartung")&&state.get("reasonEN").equals("Maintenance"),"localized countdown state is visible through API");
            denied(()->plugin.appRestart(Map.of("operation","schedule","seconds",10),"Fixture"),"cannot replace active countdown silently");
            plugin.appRestart(Map.of("operation","cancel"),"Fixture");check(Boolean.FALSE.equals(((Map<?,?>)plugin.appOperations()).get("active")),"app can cancel a pending restart");
            denied(()->plugin.appRestart(Map.of("operation","schedule","seconds",61),"Fixture"),"server rejects out-of-range countdown");

            check(AppApiManager.countdown(1)==1&&AppApiManager.countdown(60)==60&&AppApiManager.countdown(30.0)==30,"restart accepts inclusive 1–60 second bounds");
            for(Object seconds:List.of(0,-1,61,30.5,Double.NaN,Double.POSITIVE_INFINITY,4294967326L))denied(()->AppApiManager.countdown(seconds),"invalid restart countdown must fail");
            check(AppApiManager.scope("restart.apply").equals("full"),"restart requires full key");
            for(String node:access.nodes())if(AdminAccessManager.administrative(node)){check(!AdminPermissionText.name(node).isBlank()&&!AdminPermissionText.description(node).isBlank(),"German permission copy: "+node);}
            AppChat chat=new AppChat();for(int i=0;i<60;i++)chat.record("","Player","Message "+i,"player");Map<?,?> page=(Map<?,?>)chat.read(0);check(((List<?>)page.get("items")).size()==AppChat.LIMIT,"chat bound to the app history limit");check(((List<?>)((Map<?,?>)chat.read(60)).get("items")).isEmpty(),"foreground baseline has no old messages");
            denied(()->AppChat.validateName("@a"),"selector name rejected");denied(()->AppChat.validateMessage("hi\nstop"),"newline rejected");check(AppChat.validateMessage("/say hello <click:run_command:'/op x'>").startsWith("/say"),"commands remain literal message text");
            System.out.println("Release920Harness: "+checks+" production checks passed");
        }finally{f.set(null,old);try(var paths=Files.walk(folder)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
}
