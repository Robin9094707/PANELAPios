package eu.simplexsmp.adminsuite;

import io.netty.channel.*;
import io.netty.util.ReferenceCountUtil;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Main thread creates visibility snapshots; Netty never reads Bukkit world/player state. */
final class SuitePacketGuard {
    private static final String HANDLER="simplex_suite_visibility";
    private final SimplexAdminSuite plugin;
    private final Map<UUID,Snapshot> snapshots=new ConcurrentHashMap<>();
    private final Map<UUID,Channel> channels=new ConcurrentHashMap<>();
    private final Set<String> warned=ConcurrentHashMap.newKeySet();
    private volatile boolean guardEnabled=true;
    ClientProbeManager probes;
    record Snapshot(Set<UUID> uuids,Set<Integer> entityIds,Set<String> names){static final Snapshot EMPTY=new Snapshot(Set.of(),Set.of(),Set.of());}
    SuitePacketGuard(SimplexAdminSuite p){plugin=p;}
    void snapshot(Player p,Set<UUID> ids,Set<Integer> entities,Set<String> names){guardEnabled=plugin.getConfig().getBoolean("admin-update.vanish.packet-guard",true);snapshots.put(p.getUniqueId(),new Snapshot(Set.copyOf(ids),Set.copyOf(entities),Set.copyOf(names)));if(!channels.containsKey(p.getUniqueId()))join(p);}
    boolean available(UUID id){Channel c=channels.get(id);return c!=null&&c.isActive()&&c.pipeline().get(HANDLER)!=null;}
    String status(UUID id){return available(id)?"packet adapter active":"packet adapter unavailable / pending";}
    /** Must happen before showPlayer: otherwise the reveal packets themselves are discarded. */
    void reveal(UUID target,int entityId,String name){
        String lower=name.toLowerCase(Locale.ROOT);
        snapshots.replaceAll((viewer,snapshot)->without(snapshot,target,entityId,lower));
    }
    static Snapshot without(Snapshot snapshot,UUID target,int entityId,String lower){
        Set<UUID> ids=new HashSet<>(snapshot.uuids());ids.remove(target);
        Set<Integer> entities=new HashSet<>(snapshot.entityIds());entities.remove(entityId);
        Set<String> names=new HashSet<>(snapshot.names());names.remove(lower);
        return new Snapshot(Set.copyOf(ids),Set.copyOf(entities),Set.copyOf(names));
    }
    void join(Player p){
        if(channels.containsKey(p.getUniqueId()))return;
        try{
            Object handle=p.getClass().getMethod("getHandle").invoke(p);Channel channel=findChannel(handle,0,Collections.newSetFromMap(new IdentityHashMap<>()));
            if(channel==null)throw new IllegalStateException("NMS connection channel unavailable");
            UUID id=p.getUniqueId();channels.put(id,channel);
            channel.eventLoop().execute(()->{
                try{
                    if(!channel.isActive())return;
                    if(channel.pipeline().get(HANDLER)!=null)channel.pipeline().remove(HANDLER);
                    if(channel.pipeline().get("packet_handler")==null)throw new IllegalStateException("packet_handler unavailable");
                    channel.pipeline().addBefore("packet_handler",HANDLER,new ChannelDuplexHandler(){
                        @Override public void channelRead(ChannelHandlerContext ctx,Object message)throws Exception{
                            if(probes!=null&&message.getClass().getSimpleName().equals("ServerboundSignUpdatePacket")&&probes.receive(id,message)){ReferenceCountUtil.release(message);return;}
                            super.channelRead(ctx,message);
                        }
                        @Override public void write(ChannelHandlerContext ctx,Object message,ChannelPromise promise)throws Exception{
                            Object filtered=message;
                            if(guardEnabled)try{filtered=filter(message,snapshots.getOrDefault(id,Snapshot.EMPTY));}catch(ReflectiveOperationException|RuntimeException e){warn("Filter "+message.getClass().getSimpleName()+": "+e.getClass().getSimpleName());}
                            if(filtered==null){ReferenceCountUtil.release(message);promise.trySuccess();return;}
                            super.write(ctx,filtered,promise);
                        }
                    });
                }catch(RuntimeException e){channels.remove(id,channel);warn("Injection: "+e.getMessage());}
            });
        }catch(ReflectiveOperationException|RuntimeException e){warn("Connection: "+e.getClass().getSimpleName()+" "+e.getMessage());}
    }
    void quit(Player p){UUID id=p.getUniqueId();snapshots.remove(id);Channel c=channels.remove(id);if(c!=null)c.eventLoop().execute(()->{if(c.pipeline().get(HANDLER)!=null)c.pipeline().remove(HANDLER);});}
    void stop(){for(Channel c:channels.values())c.eventLoop().execute(()->{if(c.pipeline().get(HANDLER)!=null)c.pipeline().remove(HANDLER);});channels.clear();snapshots.clear();}
    private void warn(String message){if(warned.add(message))plugin.getLogger().warning("[Packet guard] "+message+". Native Paper hidePlayer remains active; unsupported packet filtering is not claimed.");}
    private static Channel findChannel(Object value,int depth,Set<Object> seen)throws IllegalAccessException{
        if(value instanceof Channel c)return c;if(value==null||depth>5||!seen.add(value))return null;
        for(Class<?> t=value.getClass();t!=null&&t!=Object.class;t=t.getSuperclass())for(Field f:t.getDeclaredFields()){
            if(Modifier.isStatic(f.getModifiers()))continue;String name=f.getName().toLowerCase(Locale.ROOT),type=f.getType().getName();
            if(!Channel.class.isAssignableFrom(f.getType())&&!name.equals("connection")&&!name.equals("networkmanager")&&!name.equals("playerconnection")&&!type.equals("net.minecraft.network.Connection"))continue;
            f.setAccessible(true);Channel c=findChannel(f.get(value),depth+1,seen);if(c!=null)return c;
        }return null;
    }
    static Object call(Object obj,String... names)throws ReflectiveOperationException{
        for(String name:names)try{Method m=obj.getClass().getMethod(name);m.trySetAccessible();return m.invoke(obj);}catch(NoSuchMethodException ignored){}
        throw new NoSuchMethodException(obj.getClass().getSimpleName()+"."+String.join("/",names));
    }
    private static Object property(Object obj,String field,String... getters)throws ReflectiveOperationException{
        try{return call(obj,getters);}catch(NoSuchMethodException noGetter){for(Class<?> type=obj.getClass();type!=null;type=type.getSuperclass())try{Field f=type.getDeclaredField(field);f.setAccessible(true);return f.get(obj);}catch(NoSuchFieldException ignored){}throw noGetter;}
    }
    static Object filter(Object packet,Snapshot s)throws ReflectiveOperationException{
        if(s.uuids.isEmpty())return packet;
        Class<?> type=packet.getClass();String name=type.getSimpleName();
        if(name.equals("ClientboundBundlePacket")){
            Iterable<?> packets=(Iterable<?>)call(packet,"subPackets");List<Object> kept=new ArrayList<>();boolean changed=false;
            for(Object child:packets){Object clean=filter(child,s);if(clean!=child)changed=true;if(clean!=null)kept.add(clean);}
            return !changed?packet:kept.isEmpty()?null:type.getConstructor(Iterable.class).newInstance(kept);
        }
        if(name.equals("ClientboundPlayerInfoUpdatePacket")){
            List<?> entries=(List<?>)call(packet,"entries","getEntries");List<Object> keep=new ArrayList<>();
            for(Object entry:entries)if(!s.uuids.contains((UUID)call(entry,"profileId","getProfileId")))keep.add(entry);
            if(keep.size()==entries.size())return packet;if(keep.isEmpty())return null;
            Object actions=call(packet,"actions","getActions");
            for(Constructor<?> c:type.getDeclaredConstructors())if(c.getParameterCount()==2&&c.getParameterTypes()[0].isInstance(actions)&&List.class.isAssignableFrom(c.getParameterTypes()[1])){c.setAccessible(true);return c.newInstance(actions,keep);}
            throw new NoSuchMethodException("PlayerInfo packet constructor");
        }
        if(name.equals("ClientboundAddEntityPacket")&&s.uuids.contains((UUID)call(packet,"getUUID","getUuid","uuid")))return null;
        if(name.equals("ClientboundSetPassengersPacket")){
            int vehicle=((Number)call(packet,"getVehicle","vehicle")).intValue();if(s.entityIds.contains(vehicle))return null;
            int[] passengers=(int[])call(packet,"getPassengers","passengers");int[] visible=Arrays.stream(passengers).filter(id->!s.entityIds.contains(id)).toArray();
            if(passengers.length==visible.length)return packet;
            for(Constructor<?> c:type.getDeclaredConstructors())if(Arrays.equals(c.getParameterTypes(),new Class<?>[]{int.class,int[].class})){c.setAccessible(true);return c.newInstance(vehicle,visible);}
            return null;
        }
        if(name.equals("ClientboundSetEntityLinkPacket")){if(s.entityIds.contains(((Number)call(packet,"getSourceId")).intValue())||s.entityIds.contains(((Number)call(packet,"getDestId")).intValue()))return null;}
        if(name.equals("ClientboundSetPlayerTeamPacket")){
            Collection<?> players=(Collection<?>)call(packet,"getPlayers");List<Object> keep=new ArrayList<>();for(Object entry:players)if(!s.names.contains(String.valueOf(entry).toLowerCase(Locale.ROOT)))keep.add(entry);
            if(keep.size()==players.size())return packet;
            for(Constructor<?> c:type.getDeclaredConstructors())if(c.getParameterCount()==4&&c.getParameterTypes()[0]==String.class&&c.getParameterTypes()[1]==int.class&&c.getParameterTypes()[2]==Optional.class&&Collection.class.isAssignableFrom(c.getParameterTypes()[3])){c.setAccessible(true);return c.newInstance(call(packet,"getName"),property(packet,"method","getMethod"),call(packet,"getParameters"),keep);}
            throw new NoSuchMethodException("Team packet constructor");
        }
        if(name.equals("ClientboundSetScorePacket")||name.equals("ClientboundResetScorePacket")){if(s.names.contains(String.valueOf(call(packet,"owner")).toLowerCase(Locale.ROOT)))return null;}
        // Removal packets must pass through to clear entities/list entries already known to a client.
        if(name.matches("Clientbound(SetEntityData|SetEquipment|UpdateAttributes|UpdateMobEffect|RemoveMobEffect|EntityEvent|Animate|RotateHead|TeleportEntity|EntityPositionSync|MoveEntity.*|SetEntityMotion|DamageEvent|SoundEntity|AddEntity)Packet")||type.getName().contains("ClientboundMoveEntityPacket$")){
            Object id=property(packet,"entityId","getId","getEntityId","id","entityId");if(id instanceof Number n&&s.entityIds.contains(n.intValue()))return null;
        }
        return packet;
    }
    static void send(Player player,Object packet)throws ReflectiveOperationException{
        Object handle=player.getClass().getMethod("getHandle").invoke(player),connection=null;
        for(Class<?> type=handle.getClass();type!=null&&connection==null;type=type.getSuperclass())for(Field f:type.getDeclaredFields())if(f.getName().equals("connection")){f.setAccessible(true);connection=f.get(handle);break;}
        if(connection==null)throw new IllegalStateException("player connection unavailable");
        for(Method m:connection.getClass().getMethods())if(m.getName().equals("send")&&m.getParameterCount()==1&&m.getParameterTypes()[0].isInstance(packet)){m.invoke(connection,packet);return;}
        throw new NoSuchMethodException("connection.send(Packet)");
    }
}
