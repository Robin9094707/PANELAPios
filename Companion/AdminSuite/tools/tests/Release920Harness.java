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
            UUID user=UUID.randomUUID(),normal=UUID.randomUUID(),op=UUID.randomUUID();Map<UUID,Player> players=new HashMap<>();Set<String> external=new HashSet<>();
            Server server=AppApiIntegrationHarness.proxy(Server.class,(p,m,v)->switch(m.getName()){
                case "getPlayer"->players.get(v[0]);case "getOnlinePlayers"->players.values();
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
            AdminAccessManager reload=new AdminAccessManager(plugin);check(reload.customRole(normal).equals("custom-junior")&&reload.customAllows(normal,"simplexadminsuite.action.kick"),"custom roles and assignments survive reload");
            denied(()->access.saveCustom("Test","bad","X","simplexadminsuite.not-real",1,false),"unknown permission must fail");denied(()->access.changeAuthorized("Test",op,null,null,AdminAccessManager.Role.NONE),"operator must remain protected");
            external.add("simplexadminsuite.action.kick");access.changeAuthorized("Test",user,null,null,AdminAccessManager.Role.INHERIT);
            check(plugin.has(junior,"simplexadminsuite.admin")&&plugin.has(junior,"simplexadminsuite.action.kick"),"single inherited action grants /sas and kick without broad bans node");external.clear();
            // Dimension block must run before the usual admin/managed-teleport bypasses.
            var dbCtor=SimplexAdminSuite.PlayerDatabase.class.getDeclaredConstructor(SimplexAdminSuite.class);dbCtor.setAccessible(true);Object db=dbCtor.newInstance(plugin);Moderation790Harness.set(plugin,SimplexAdminSuite.class,"database",db);
            var profileCtor=SimplexAdminSuite.PlayerProfile.class.getDeclaredConstructor(UUID.class,String.class);profileCtor.setAccessible(true);var profile=(SimplexAdminSuite.PlayerProfile)profileCtor.newInstance(user,"Junior");profile.setRightBlocked("nether",true);profile.setRightBlocked("end",true);
            ((Map<UUID,SimplexAdminSuite.PlayerProfile>)Moderation790Harness.get(db,"profiles")).put(user,profile);
            World overworld=AppApiIntegrationHarness.proxy(World.class,(x,m,v)->m.getName().equals("getEnvironment")?World.Environment.NORMAL:AppApiIntegrationHarness.empty(m.getReturnType()));
            World nether=AppApiIntegrationHarness.proxy(World.class,(x,m,v)->m.getName().equals("getEnvironment")?World.Environment.NETHER:AppApiIntegrationHarness.empty(m.getReturnType()));
            World end=AppApiIntegrationHarness.proxy(World.class,(x,m,v)->m.getName().equals("getEnvironment")?World.Environment.THE_END:AppApiIntegrationHarness.empty(m.getReturnType()));
            for(World destination:List.of(nether,end))for(var cause:List.of(org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.COMMAND,org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN)){
                var event=new org.bukkit.event.player.PlayerTeleportEvent(junior,new Location(overworld,0,70,0),new Location(destination,0,70,0),cause);plugin.onTeleport(event);check(event.isCancelled(),"forbidden dimension blocks home/plugin teleport: "+cause);
            }
            check(!plugin.dimensionBlocked(junior,new Location(overworld,0,70,0)),"overworld remains accessible");
            check(AppApiManager.scope("restart.apply").equals("full"),"restart requires full key");
            for(String node:access.nodes())if(AdminAccessManager.administrative(node)){check(!AdminPermissionText.name(node).isBlank()&&!AdminPermissionText.description(node).isBlank(),"German permission copy: "+node);}
            AppChat chat=new AppChat();for(int i=0;i<30;i++)chat.record("","Player","Message "+i,"player");Map<?,?> page=(Map<?,?>)chat.read(0);check(((List<?>)page.get("items")).size()==20,"chat bound to 20");check(((List<?>)((Map<?,?>)chat.read(30)).get("items")).isEmpty(),"foreground baseline has no old messages");
            denied(()->AppChat.validateName("@a"),"selector name rejected");denied(()->AppChat.validateMessage("hi\nstop"),"newline rejected");check(AppChat.validateMessage("/say hello <click:run_command:'/op x'>").startsWith("/say"),"commands remain literal message text");
            System.out.println("Release920Harness: "+checks+" production checks passed");
        }finally{f.set(null,old);try(var paths=Files.walk(folder)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
}
