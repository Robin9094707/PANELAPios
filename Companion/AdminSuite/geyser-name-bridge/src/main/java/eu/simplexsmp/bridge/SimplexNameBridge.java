package eu.simplexsmp.bridge;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.geysermc.geyser.api.extension.Extension;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserShutdownEvent;
import org.geysermc.event.subscribe.Subscribe;

/** Optional Geyser-Spigot extension, independent of any Sentinel extension/channel. */
public final class SimplexNameBridge implements Extension {
    private ScheduledExecutorService timer;
    private final Map<Object,Map<UUID,String>> previous=new ConcurrentHashMap<>();
    private final Set<String> warned=ConcurrentHashMap.newKeySet();
    private final AtomicBoolean queued=new AtomicBoolean();
    private volatile boolean running;
    private long pulse;

    @Subscribe public void initialize(GeyserPostInitializeEvent event){
        if(timer!=null)timer.shutdownNow();running=true;
        timer=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"Simplex-Name-Bridge");t.setDaemon(true);return t;});
        timer.scheduleWithFixedDelay(this::poll,1,1,TimeUnit.SECONDS);
        logger().info("Simplex Name Bridge 1.0.0 ready. Geyser-Spigot + AdminSuite 7.17+ required. Sentinel remains independent.");
    }
    @Subscribe public void shutdown(GeyserShutdownEvent event){running=false;if(timer!=null)timer.shutdownNow();timer=null;restore();previous.clear();}
    private void poll(){
        if(!running||!queued.compareAndSet(false,true))return;
        try{
            Class<?> bukkit=Class.forName("org.bukkit.Bukkit");Object manager=bukkit.getMethod("getPluginManager").invoke(null);
            Object suite=invoke(manager,"getPlugin","SimplexAdminSuite");
            if(suite==null||!Boolean.TRUE.equals(invoke(suite,"isEnabled"))){queued.set(false);restore();warn("AdminSuite unavailable; no names modified.");return;}
            Object scheduler=bukkit.getMethod("getScheduler").invoke(null);Object owner=invoke(manager,"getPlugin","Geyser-Spigot");
            if(owner==null){queued.set(false);warn("This bridge supports Geyser-Spigot in the same server JVM. Standalone/proxy needs a separate authenticated transport and is not silently assumed.");return;}
            invoke(scheduler,"runTask",owner,(Runnable)()->{
                try{
                    if(!running)return;long now=++pulse;
                    List<?> sessions=new ArrayList<>(geyserApi().onlineConnections());previous.keySet().retainAll(sessions);
                    Method snapshot=suite.getClass().getMethod("bedrockPlayerListSnapshot",UUID.class);
                    for(Object session:sessions){UUID viewer=(UUID)invoke(session,"javaUuid");if(viewer==null)continue;
                        @SuppressWarnings("unchecked") Map<UUID,String> names=(Map<UUID,String>)snapshot.invoke(suite,viewer);
                        Map<UUID,String> stable=Map.copyOf(names);
                        invoke(session,"executeInEventLoop",(Runnable)()->update(session,stable,now));
                    }
                }catch(ReflectiveOperationException|RuntimeException e){warn("Snapshot adapter unavailable: "+root(e));}
                finally{queued.set(false);}
            });
        }catch(ReflectiveOperationException|RuntimeException e){queued.set(false);warn("Bridge adapter unavailable: "+root(e));}
    }
    private void update(Object session,Map<UUID,String> names,long now){
        if(!running)return;
        try{
            Map<UUID,String> old=previous.computeIfAbsent(session,s->new ConcurrentHashMap<>());
            Object cache=invoke(session,"getEntityCache");
            for(var e:names.entrySet()){
                // Never create an entity or resurrect a player absent from Geyser's authoritative list.
                Object entity=invoke(cache,"getPlayerEntity",e.getKey());
                if(entity==null||!Boolean.TRUE.equals(invoke(entity,"isListed"))){old.remove(e.getKey());continue;}
                if(e.getValue().equals(old.get(e.getKey()))&&now%5!=0)continue;
                sendName(session,entity,e.getValue());old.put(e.getKey(),e.getValue());
            }
            // Integration disabled: restore still-listed entries from the original cached profile.
            for(UUID uuid:new ArrayList<>(old.keySet()))if(!names.containsKey(uuid)){
                Object entity=invoke(cache,"getPlayerEntity",uuid);if(entity!=null&&Boolean.TRUE.equals(invoke(entity,"isListed")))sendName(session,entity,null);old.remove(uuid);
            }
        }catch(ReflectiveOperationException|RuntimeException e){warn("Geyser player-list adapter unavailable: "+root(e)+". Account/profile names and entity usernames remain untouched.");}
    }
    private void sendName(Object session,Object entity,String name)throws ReflectiveOperationException{
        ClassLoader loader=session.getClass().getClassLoader();Class<?> skin=Class.forName("org.geysermc.geyser.skin.SkinManager",true,loader);
        Object entry=null;for(Method m:skin.getMethods())if(m.getName().equals("buildEntryFromCachedSkin")&&m.getParameterCount()==2&&m.getParameterTypes()[0].isInstance(session)&&m.getParameterTypes()[1].isInstance(entity)){entry=m.invoke(null,session,entity);break;}
        if(entry==null)throw new NoSuchMethodException("SkinManager.buildEntryFromCachedSkin");
        if(name!=null)invoke(entry,"setName",name);
        Class<?> packetType=Class.forName("org.cloudburstmc.protocol.bedrock.packet.PlayerListPacket",true,loader);
        Class<?> actionType=Class.forName("org.cloudburstmc.protocol.bedrock.packet.PlayerListPacket$Action",true,loader);
        @SuppressWarnings({"unchecked","rawtypes"}) Object add=Enum.valueOf((Class)actionType,"ADD");
        // Recent Bedrock codecs also carry the action on each entry.
        try{invoke(entry,"setAction",add);}catch(NoSuchMethodException olderCodec){/* packet action is enough on older versions */}
        Object packet=packetType.getConstructor().newInstance();invoke(packet,"setAction",add);
        @SuppressWarnings("unchecked") Collection<Object> entries=(Collection<Object>)invoke(packet,"getEntries");entries.add(entry);
        invoke(session,"sendUpstreamPacket",packet);
    }
    private void restore(){for(var e:previous.entrySet())try{Object session=e.getKey();Set<UUID> ids=Set.copyOf(e.getValue().keySet());invoke(session,"executeInEventLoop",(Runnable)()->{try{Object cache=invoke(session,"getEntityCache");for(UUID id:ids){Object entity=invoke(cache,"getPlayerEntity",id);if(entity!=null&&Boolean.TRUE.equals(invoke(entity,"isListed")))sendName(session,entity,null);}}catch(ReflectiveOperationException|RuntimeException error){warn("Name restoration: "+root(error));}});}catch(ReflectiveOperationException|RuntimeException error){warn("Name restoration: "+root(error));}previous.clear();}
    static Object invoke(Object target,String name,Object... args)throws ReflectiveOperationException{
        for(Method m:target.getClass().getMethods()){
            if(!m.getName().equals(name)||m.getParameterCount()!=args.length)continue;boolean match=true;
            for(int i=0;i<args.length;i++)if(args[i]!=null&&!m.getParameterTypes()[i].isInstance(args[i])){match=false;break;}
            if(match){m.trySetAccessible();return m.invoke(target,args);}
        }throw new NoSuchMethodException(target.getClass().getSimpleName()+"."+name);
    }
    private static String root(Throwable e){while(e.getCause()!=null)e=e.getCause();return e.getClass().getSimpleName()+": "+e.getMessage();}
    private void warn(String message){if(warned.add(message))logger().warning(message);}
}
