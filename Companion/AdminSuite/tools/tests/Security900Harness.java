package eu.simplexsmp.adminsuite;

import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerGameModeChangeEvent;

/** Focused production checks: spoofed display, operator guard, private subscriptions, atomic NBT. */
public final class Security900Harness {
    static int checks;
    static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    interface Work {void run()throws Exception;}
    static void reject(Work work,String message)throws Exception{try{work.run();throw new AssertionError(message);}catch(IllegalArgumentException expected){checks++;}}
    public static void main(String[] args)throws Exception {
        for(String brand:List.of("vanilla","fabric","forge","neoforge","quilt","noriskclient"))check(!ClientBrands.known(brand).isEmpty(),"known brand: "+brand);
        for(String brand:List.of("Robins PC","fabric Robins PC","vanilla\nAdmin","§cvanilla","meteor","<red>Forge"))check(ClientBrands.known(brand).isEmpty()&&!ClientBrands.label(brand,true).contains(brand),"spoof never publicly echoed: "+brand);
        check(ClientBrands.label("Robins PC",true).contains("Unbekannter")&&ClientBrands.label("Robins PC",false).contains("Unknown"),"viewer locale");
        Properties policy=new Properties();policy.setProperty("u.quietMode","selected");policy.setProperty("u.quietStart","22:00");policy.setProperty("u.quietEnd","07:00");policy.setProperty("u.timezone","Europe/Berlin");policy.setProperty("u.quietKinds","report");
        var night=java.time.Instant.parse("2026-10-27T23:00:00Z");var day=java.time.Instant.parse("2026-10-27T12:00:00Z");
        check(BarkPolicy.quiet(policy,"u.",night)&&!BarkPolicy.quiet(policy,"u.",day),"overnight quiet interval in configured zone");
        check(BarkPolicy.allowed(policy,"u.","report","critical",night),"selected critical report passes quiet hours");
        check(!BarkPolicy.allowed(policy,"u.","ban","critical",night)&&!BarkPolicy.allowed(policy,"u.","report","active",night),"unselected critical and ordinary alerts stay quiet");
        policy.setProperty("u.quietEnd","22:00");check(BarkPolicy.quiet(policy,"u.",day),"equal quiet endpoints mean whole day");
        reject(()->BarkPolicy.rules("report:critical:alarm:1"),"unbounded short repeat rejected");
        check(BarkPolicy.rules("report:critical:alarm:120;join:off:minuet:0").size()==2,"per-event rules parsed independently");
        Path folder=Files.createTempDirectory("suite900-");SuiteEventCenter events=null;
        Field serverField=Bukkit.class.getDeclaredField("server");serverField.setAccessible(true);Object previous=serverField.get(null);
        Path worldFolder=Files.createTempDirectory(Path.of("target").toAbsolutePath(),"nbt900-");
        try {
            var plugin=Moderation790Harness.plugin(folder);
            events=new SuiteEventCenter(plugin);
            events.record("client-brand",null,"Test","Synthetic",false);
            Map<String,Object> data=AppApiIntegrationHarness.object(events.data("Synthetic",0));
            List<?> rows=(List<?>)data.get("items");check(rows.size()==1,"new event visible");
            String id=String.valueOf(AppApiIntegrationHarness.object(rows.get(0)).get("id"));
            events.edit("events.note",id,"Reviewed");events.stop();events=new SuiteEventCenter(plugin);
            check(events.data("Reviewed",0).toString().contains("Reviewed"),"event note durable");
            Map<String,Object> config=SimplexAdminSuite.appMap("key","synthetic-key-900","enabled","false","events","first-join,ban-evasion","level","passive","critical","critical","sound","minuet","criticalSound","alarm");
            events.configure("owner",config);check(!events.subscription("owner").toString().contains("synthetic-key-900"),"secret never returned");
            check(!events.subscription("other").toString().contains("first-join"),"subscription isolated per API key");
            config.put("key","https://attacker.invalid/key");SuiteEventCenter current=events;reject(()->current.configure("owner",config),"external Bark hosts rejected");
            Properties alarms=(Properties)Moderation790Harness.get(events,"subscriptions");alarms.setProperty("alarm.owner."+id+".row","{}");alarms.setProperty("alarm.owner."+id+".owner","owner");
            events.acknowledge("owner",id);check(!alarms.containsKey("alarm.owner."+id+".row")&&alarms.containsKey("ack.owner."+id),"ack ends pending alarm and protects against in-flight rescheduling");
            events.edit("events.delete",id,"");check(!alarms.containsKey("ack.owner."+id),"deleted event removes obsolete acknowledgement state");
            AtomicBoolean op=new AtomicBoolean(),invulnerable=new AtomicBoolean(true),online=new AtomicBoolean();AtomicReference<GameMode> mode=new AtomicReference<>(GameMode.CREATIVE);UUID uuid=UUID.randomUUID();
            Player player=AppApiIntegrationHarness.proxy(Player.class,(p,m,a)->switch(m.getName()) {
                case "isOp"->op.get();case "isOnline"->true;case "getUniqueId"->uuid;case "getName"->"Fixture";case "getGameMode"->mode.get();case "isInvulnerable"->invulnerable.get();
                case "setGameMode"->{mode.set((GameMode)a[0]);yield null;}case "setInvulnerable"->{invulnerable.set((boolean)a[0]);yield null;}default->AppApiIntegrationHarness.empty(m.getReturnType());
            });
            World world=AppApiIntegrationHarness.proxy(World.class,(p,m,a)->switch(m.getName()){case "getWorldFolder"->worldFolder.toFile();default->AppApiIntegrationHarness.empty(m.getReturnType());});
            Server server=AppApiIntegrationHarness.proxy(Server.class,(p,m,a)->switch(m.getName()){case "getPlayer"->online.get()?player:null;case "getWorlds"->List.of(world);default->AppApiIntegrationHarness.empty(m.getReturnType());});serverField.set(null,server);
            SurvivalGuard guard=new SurvivalGuard(plugin);guard.enforce(player);check(mode.get()==GameMode.SURVIVAL&&!invulnerable.get(),"non-op repaired");
            var change=new PlayerGameModeChangeEvent(player,GameMode.CREATIVE);guard.mode(change);check(change.isCancelled(),"non-op gamemode request rejected");
            op.set(true);mode.set(GameMode.CREATIVE);invulnerable.set(true);guard.enforce(player);check(mode.get()==GameMode.CREATIVE&&invulnerable.get(),"real operator exempt");
            var dbConstructor=SimplexAdminSuite.PlayerDatabase.class.getDeclaredConstructor(SimplexAdminSuite.class);dbConstructor.setAccessible(true);Object db=dbConstructor.newInstance(plugin);Moderation790Harness.set(plugin,SimplexAdminSuite.class,"database",db);
            var profileConstructor=SimplexAdminSuite.PlayerProfile.class.getDeclaredConstructor(UUID.class,String.class);profileConstructor.setAccessible(true);Object profile=profileConstructor.newInstance(uuid,"Fixture");
            @SuppressWarnings("unchecked") Map<UUID,Object> profiles=(Map<UUID,Object>)Moderation790Harness.get(db,"profiles");profiles.clear();profiles.put(uuid,profile);
            var typedProfile=(SimplexAdminSuite.PlayerProfile)profile;
            var temporary=SimplexAdminSuite.PlayerProfile.class.getDeclaredMethod("setTemporaryBlocked",String.class,long.class);temporary.setAccessible(true);
            var permanent=SimplexAdminSuite.PlayerProfile.class.getDeclaredMethod("setBlocked",String.class,boolean.class);permanent.setAccessible(true);
            long expiry=System.currentTimeMillis()+60000;temporary.invoke(profile,"chat",expiry);String oldRight=typedProfile.inheritedRightSnapshot("chat");
            permanent.invoke(profile,"chat",true);typedProfile.restoreInheritedRight("chat",oldRight);
            check(!typedProfile.blockedRightsSet().contains("chat")&&typedProfile.temporaryBlockedRightsMap().get("chat")==expiry,"removing inherited denial restores original temporary expiry");
            typedProfile.restoreInheritedRight("chat","false:"+(System.currentTimeMillis()-1));check(!typedProfile.blockedRightsSet().contains("chat")&&!typedProfile.temporaryBlockedRightsMap().containsKey("chat"),"expired original restriction is not made permanent");
            permanent.invoke(profile,"chat",true);String permanentRight=typedProfile.inheritedRightSnapshot("chat");permanent.invoke(profile,"chat",false);typedProfile.restoreInheritedRight("chat",permanentRight);check(typedProfile.blockedRightsSet().contains("chat"),"own permanent denial preserved");
            VpnProtectionManager vpn=new VpnProtectionManager(plugin);UUID firstBlocked=UUID.randomUUID();
            try {
                vpn.appWhitelist("Fixture",firstBlocked.toString(),true);check(vpn.isWhitelisted(firstBlocked,"NewPlayer"),"first-login VPN kick can be whitelisted by exact UUID");
                vpn.appWhitelist("Fixture",firstBlocked.toString(),false);check(!vpn.isWhitelisted(firstBlocked,"NewPlayer"),"same unknown UUID removed exactly");
                reject(()->vpn.appWhitelist("Fixture","Fix",true),"name prefix cannot modify another player's whitelist");
            } finally {vpn.stop();}
            Path file=Files.createDirectories(worldFolder.resolve("playerdata")).resolve(uuid+".dat");byte[] original={10,0,0,0};Files.write(file,original);
            ByteArrayOutputStream buffer=new ByteArrayOutputStream();try(DataOutputStream out=new DataOutputStream(buffer)){out.writeByte(10);out.writeUTF("");out.writeByte(8);out.writeUTF("note");out.writeUTF("edited");out.writeByte(0);}byte[] modified=buffer.toByteArray();
            String requested="/"+Path.of(".").toRealPath().relativize(file.toRealPath()).toString().replace(File.separatorChar,'/');
            PlayerNbtAccess nbt=new PlayerNbtAccess(plugin);Map<String,Object> input=SimplexAdminSuite.appMap("player",uuid.toString(),"path",requested,"expected",PlayerNbtAccess.hash(original),"data",Base64.getEncoder().encodeToString(modified));
            online.set(true);reject(()->nbt.write(input,"Fixture"),"online NBT write rejected");check(Arrays.equals(Files.readAllBytes(file),original),"online rejection preserves file");
            online.set(false);input.put("expected","wrong");reject(()->nbt.write(input,"Fixture"),"stale NBT rejected");input.put("expected",PlayerNbtAccess.hash(original));
            input.put("data",Base64.getEncoder().encodeToString(new byte[]{0}));try{nbt.write(input,"Fixture");throw new AssertionError("invalid NBT accepted");}catch(IOException expected){checks++;}check(Arrays.equals(Files.readAllBytes(file),original),"invalid NBT preserves file");
            input.put("data",Base64.getEncoder().encodeToString(modified));Map<String,Object> saved=AppApiIntegrationHarness.object(nbt.write(input,"Fixture"));check(Arrays.equals(Files.readAllBytes(file),modified),"offline file atomically replaced");check(Arrays.equals(Files.readAllBytes(file.resolveSibling(String.valueOf(saved.get("backup")))),original),"original backup exact");
            input.put("path","/../../other.dat");reject(()->nbt.write(input,"Fixture"),"arbitrary paths rejected");
        } finally {
            serverField.set(null,previous);if(events!=null)events.stop();
            for(Path root:List.of(folder,worldFolder))try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}
        }
        System.out.println("PASS: "+checks+" targeted 9.0 security/data checks; no live gameplay or push claim.");
    }
}
