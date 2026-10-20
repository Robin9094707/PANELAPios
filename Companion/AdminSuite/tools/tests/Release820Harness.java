package eu.simplexsmp.adminsuite;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Exercises production state restoration, reveal ordering, durable inbox and sign protocol adapters. */
public final class Release820Harness {
    static int checks;
    static void check(boolean ok,String reason){checks++;if(!ok)throw new AssertionError(reason);}
    static Object allocate(Class<?> type)throws Exception{Class<?> u=Class.forName("sun.misc.Unsafe");Field f=u.getDeclaredField("theUnsafe");f.setAccessible(true);return u.getMethod("allocateInstance",Class.class).invoke(f.get(null),type);}
    static Object field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    static void set(Object o,String name,Object value)throws Exception{Moderation790Harness.set(o,o.getClass(),name,value);}
    static void invoke(Object o,String name,Player p)throws Exception{Method m=o.getClass().getDeclaredMethod(name,Player.class);m.setAccessible(true);m.invoke(o,p);}
    public record LegacyOpen(String pos,boolean front){}
    public record LegacyReply(String[] getLines,boolean isFrontText){}
    enum Slot{BACK,FRONT}
    public record ModernReply(List<String> lines,Slot slot){}
    static Object empty(Class<?> type){if(!type.isPrimitive())return type==List.class?List.of():type==Set.class?Set.of():null;if(type==boolean.class)return false;if(type==int.class)return 0;if(type==long.class)return 0L;if(type==float.class)return 0F;if(type==double.class)return 0D;return null;}
    public static void main(String[] args)throws Exception{
        check(((LegacyOpen)SignProbeAdapter.open(LegacyOpen.class,String.class,"pos")).front(),"Legacy boolean constructor remains supported");
        String[] original={"a","b","c","W"};String[] copy=SignProbeAdapter.lines(new LegacyReply(original,true));copy[0]="changed";
        check(original[0].equals("a")&&SignProbeAdapter.front(new LegacyReply(original,true)),"Legacy reply copied and front validated");
        check(Arrays.equals(SignProbeAdapter.lines(new ModernReply(List.of(original),Slot.FRONT)),original),"Modern list preserved");
        check(SignProbeAdapter.front(new ModernReply(List.of(original),Slot.FRONT))&&!SignProbeAdapter.front(new ModernReply(List.of(original),Slot.BACK)),"Modern side cannot be confused");
        try{SignProbeAdapter.lines(new ModernReply(List.of("1","2","3","4","5"),Slot.FRONT));throw new AssertionError("Oversized reply accepted");}catch(IllegalArgumentException expected){checks++;}
        if(Arrays.asList(args).contains("--minecraft"))minecraft();
        Path dir=Files.createTempDirectory("sas820-");
        try{vanish(dir);inbox(dir);}finally{try(var paths=Files.walk(dir)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
        System.out.println("PASS: "+checks+" release 8.2 checks; no live Java/Bedrock session claimed.");
    }
    static void minecraft()throws Exception{
        Object open=SignProbeAdapter.open(12,64,-8),pos=SuitePacketGuard.call(open,"pos"),front=SuitePacketGuard.call(open,"slot");
        check(((Enum<?>)front).name().equals("FRONT"),"Real Mojang 26.3 open packet uses FRONT slot");
        Class<?> packet=Class.forName("net.minecraft.network.protocol.game.ServerboundSignUpdatePacket");
        Object reply=packet.getConstructor(pos.getClass(),List.class,front.getClass()).newInstance(pos,List.of("mod-name","sas:absent","key.unknown","W"),front);
        check(SignProbeAdapter.front(reply)&&SignProbeAdapter.lines(reply)[0].equals("mod-name"),"Actual 26.3 inbound packet parsed");
        @SuppressWarnings({"unchecked","rawtypes"}) Object back=Enum.valueOf((Class)front.getClass(),"BACK");
        Object wrong=packet.getConstructor(pos.getClass(),List.class,front.getClass()).newInstance(pos,List.of("","","","W"),back);
        check(!SignProbeAdapter.front(wrong),"Actual 26.3 back-side reply rejected");
    }
    @SuppressWarnings("unchecked") static void vanish(Path dir)throws Exception{
        var plugin=Moderation790Harness.plugin(dir);AdminRuntime runtime=new AdminRuntime(plugin);plugin.adminRuntime=runtime;
        SuitePacketGuard guard=new SuitePacketGuard(plugin);
        QualityOfLifeManager qol=(QualityOfLifeManager)allocate(QualityOfLifeManager.class);set(qol,"plugin",plugin);set(qol,"packets",guard);set(qol,"hiddenNames",new java.util.concurrent.ConcurrentHashMap<>());plugin.qualityOfLife=qol;
        Class<?> spyType=Class.forName("eu.simplexsmp.adminsuite.SimplexAdminSuite$AdminSpyManager");Object spy=allocate(spyType);set(spy,"plugin",plugin);set(spy,"hiddenTab",new HashSet<UUID>());Moderation790Harness.set(plugin,SimplexAdminSuite.class,"adminSpy",spy);
        UUID id=UUID.randomUUID(),viewerId=UUID.randomUUID(),other=UUID.randomUUID();AtomicInteger reveals=new AtomicInteger();AtomicBoolean allow=new AtomicBoolean(true),flying=new AtomicBoolean(true),collision=new AtomicBoolean(false),sleep=new AtomicBoolean(true),silent=new AtomicBoolean(true),failCollision=new AtomicBoolean();AtomicReference<Player> target=new AtomicReference<>();
        Map<UUID,SuitePacketGuard.Snapshot> snapshots=(Map<UUID,SuitePacketGuard.Snapshot>)field(guard,"snapshots");
        Player viewer=(Player)Proxy.newProxyInstance(Player.class.getClassLoader(),new Class<?>[]{Player.class},(o,m,a)->switch(m.getName()){
            case "getUniqueId"->viewerId;case "getName"->"Viewer";case "isOp"->true;
            case "showPlayer"->{if(a[1]==target.get()){check(!snapshots.get(viewerId).uuids().contains(id),"Reveal packets released before showPlayer");reveals.incrementAndGet();}yield null;}
            case "equals"->o==a[0];default->empty(m.getReturnType());});
        Player player=(Player)Proxy.newProxyInstance(Player.class.getClassLoader(),new Class<?>[]{Player.class},(o,m,a)->switch(m.getName()){
            case "getUniqueId"->id;case "getEntityId"->42;case "getName"->"HiddenAdmin";case "getLocale"->"en_us";case "isOnline"->true;case "getGameMode"->GameMode.SURVIVAL;case "getLocation"->new Location(null,0,64,0);
            case "setAllowFlight"->{allow.set((boolean)a[0]);yield null;}case "setFlying"->{flying.set((boolean)a[0]);yield null;}
            case "setCollidable"->{if(failCollision.get())throw new IllegalStateException("Simulated setter failure");collision.set((boolean)a[0]);yield null;}
            case "setSleepingIgnored"->{sleep.set((boolean)a[0]);yield null;}case "setSilent"->{silent.set((boolean)a[0]);yield null;}
            case "equals"->o==a[0];default->empty(m.getReturnType());});target.set(player);
        Field serverField=Bukkit.class.getDeclaredField("server");serverField.setAccessible(true);Object old=serverField.get(null);
        Server server=(Server)Proxy.newProxyInstance(Server.class.getClassLoader(),new Class<?>[]{Server.class},(o,m,a)->switch(m.getName()){case "getOnlinePlayers"->List.of(player,viewer);case "getPlayer"->player;default->empty(m.getReturnType());});serverField.set(null,server);
        try{
            snapshots.put(viewerId,new SuitePacketGuard.Snapshot(Set.of(id,other),Set.of(42,99),Set.of("hiddenadmin","other")));
            Class<?> flightType=Class.forName("eu.simplexsmp.adminsuite.AdminRuntime$Flight");Constructor<?> ctor=flightType.getDeclaredConstructors()[0];ctor.setAccessible(true);
            ((Map<UUID,Object>)field(runtime,"vanished")).put(id,ctor.newInstance(false,false,true,false,false));
            ((Properties)field(runtime,"recovery")).setProperty(id.toString(),"false,false,true,false,false");
            invoke(runtime,"disable",player);
            check(!runtime.vanished(player)&&!allow.get()&&!flying.get()&&collision.get()&&!sleep.get()&&!silent.get(),"Exit restores all five original player properties");
            check(reveals.get()==1,"Viewer is actually shown departing vanished player");
            check(snapshots.get(viewerId).uuids().contains(other)&&snapshots.get(viewerId).entityIds().contains(99)&&snapshots.get(viewerId).names().contains("other"),"Other vanished targets remain protected");
            check(!((Properties)field(runtime,"recovery")).containsKey(id.toString()),"Successful restore clears durable marker");
            Properties journal=(Properties)field(runtime,"recovery");journal.setProperty(id.toString(),"false,false,true,false,false");failCollision.set(true);silent.set(true);sleep.set(true);invoke(runtime,"recover",player);
            check(!silent.get()&&!sleep.get()&&journal.containsKey(id.toString()),"One setter failure still restores remaining fields and retains journal");
            failCollision.set(false);invoke(runtime,"recover",player);check(!journal.containsKey(id.toString()),"Reconnect recovery can retry and complete");
            invoke(runtime,"disable",player);check(!runtime.vanished(player),"Repeated cleanup is idempotent");
        }finally{serverField.set(null,old);}
    }
    static void inbox(Path dir)throws Exception{
        UUID owner=UUID.randomUUID(),other=UUID.randomUUID();Path file=dir.resolve("notes.sqlite");
        var bilingual=new EconomyStore.Note("auto",owner,"staff","Deutsch","English","DE Inhalt","EN body",2,true,1,0,0,"auto");
        check(QuickDelivery.noteTitle(bilingual,true).equals("Deutsch")&&QuickDelivery.noteBody(bilingual,false).equals("EN body"),"Both bilingual versions retained");
        var fixed=new EconomyStore.Note("fixed",owner,"staff","Deutsch","English","DE Inhalt","EN body",1,true,2,0,0,"de");
        check(QuickDelivery.noteBody(fixed,false).equals("DE Inhalt"),"Locale change preserves readable original message instead of blocking it");
        try(var store=new EconomyStore(file)){store.note(bilingual);store.note(fixed);check(store.notes(owner,true).size()==2,"Offline recipient has durable pending messages");check(!store.ack("auto",owner),"Cannot acknowledge before delivery");store.sent("auto",owner);check(!store.ack("auto",other),"Other account cannot acknowledge");check(store.ack("auto",owner)&&!store.ack("auto",owner),"Acknowledgement is owner scoped and idempotent");}
        try(var reopened=new EconomyStore(file)){check(reopened.notes(owner,true).size()==1&&reopened.notes(owner,false).size()==2,"Restart retains pending and acknowledged notes");check(reopened.notes(owner,false).get(0).en().equals("EN body"),"Bilingual content survives restart unchanged");}
        var plugin=Moderation790Harness.plugin(dir);QuickDelivery delivery=(QuickDelivery)allocate(QuickDelivery.class);set(delivery,"plugin",plugin);check(delivery.delaySeconds()==30,"Existing default wait retained");plugin.getConfig().set("quick-delivery.delay-seconds",-1);check(delivery.delaySeconds()==1,"Lower delay bound enforced");plugin.getConfig().set("quick-delivery.delay-seconds",500);check(delivery.delaySeconds()==300,"Upper delay bound enforced");
    }
}
