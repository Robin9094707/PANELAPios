package eu.simplexsmp.adminsuite;

import io.netty.channel.*;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Main thread creates visibility snapshots; Netty never reads Bukkit world/player state. */
final class SuitePacketGuard {
    private static final String HANDLER="simplex_suite_visibility",BYTES="simplex_suite_bytes";
    /** 10.16.1: lives with the connection, so a re-injected handler keeps knowing which team entries this client never received. */
    private static final AttributeKey<TeamLedger> TEAMS=AttributeKey.valueOf("simplex_suite_teams");
    private final SimplexAdminSuite plugin;
    private final Map<UUID,Snapshot> snapshots=new ConcurrentHashMap<>();
    private final Map<UUID,Channel> channels=new ConcurrentHashMap<>();
    private final Map<UUID,PacketTrace> diagnostics=new ConcurrentHashMap<>();
    private final Set<String> warned=ConcurrentHashMap.newKeySet();
    /** Players whose quit already ran: a late visibility tick during the same quit must not re-inject their closing connection. */
    private final Set<UUID> departed=ConcurrentHashMap.newKeySet();
    private volatile boolean guardEnabled=true;
    ClientProbeManager probes;
    /** 10.16.0: per-player chat break filter; null while the module is not loaded. */
    volatile ChatSilence chat;
    record Snapshot(Set<UUID> uuids,Set<Integer> entityIds,Set<String> names){static final Snapshot EMPTY=new Snapshot(Set.of(),Set.of(),Set.of());}
    SuitePacketGuard(SimplexAdminSuite p){plugin=p;}
    void snapshot(Player p,Set<UUID> ids,Set<Integer> entities,Set<String> names){guardEnabled=plugin.getConfig().getBoolean("admin-update.vanish.packet-guard",true);if(departed.contains(p.getUniqueId()))return;snapshots.put(p.getUniqueId(),new Snapshot(Set.copyOf(ids),Set.copyOf(entities),Set.copyOf(names)));if(!channels.containsKey(p.getUniqueId()))attach(p);}
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
    /** 10.17.0: a player who left (or is joining again) must never stay in another viewer's hidden set. */
    void forget(UUID target,String name){
        String lower=name==null?"":name.toLowerCase(Locale.ROOT);
        snapshots.replaceAll((viewer,s)->s.uuids().contains(target)||s.names().contains(lower)?forgotten(s,target,lower):s);
    }
    static Snapshot forgotten(Snapshot snapshot,UUID target,String lower){
        Set<UUID> ids=new HashSet<>(snapshot.uuids());ids.remove(target);
        Set<String> names=new HashSet<>(snapshot.names());names.remove(lower);
        return new Snapshot(Set.copyOf(ids),snapshot.entityIds(),Set.copyOf(names));
    }
    void join(Player p){departed.remove(p.getUniqueId());attach(p);}
    private void attach(Player p){
        if(channels.containsKey(p.getUniqueId()))return;
        try{
            Object handle=p.getClass().getMethod("getHandle").invoke(p);Channel channel=findChannel(handle,0,Collections.newSetFromMap(new IdentityHashMap<>()));
            if(channel==null)throw new IllegalStateException("NMS connection channel unavailable");
            UUID id=p.getUniqueId();channels.put(id,channel);
            PacketTrace trace=plugin.getConfig().getBoolean("diagnostics.packet-metadata",true)?new PacketTrace():null;
            if(trace!=null){diagnostics.put(id,trace);trace.meta(DisconnectForensics.transport(channel),DisconnectForensics.networkGroup(plugin,channel.remoteAddress()));}
            channel.eventLoop().execute(()->{
                try{
                    if(!channel.isActive())return;
                    if(channel.pipeline().get(HANDLER)!=null)channel.pipeline().remove(HANDLER);
                    if(channel.pipeline().get(BYTES)!=null)channel.pipeline().remove(BYTES);
                    if(channel.pipeline().get("packet_handler")==null)throw new IllegalStateException("packet_handler unavailable");
                    channel.attr(TEAMS).setIfAbsent(new TeamLedger());
                    // 10.16.0: wire-level byte counter at the very head (sees compressed/encrypted frames, never decodes them).
                    if(trace!=null)channel.pipeline().addFirst(BYTES,new ChannelDuplexHandler(){
                        @Override public void channelRead(ChannelHandlerContext ctx,Object message)throws Exception{
                            if(message instanceof io.netty.buffer.ByteBuf buf)trace.bytes(true,buf.readableBytes(),System.currentTimeMillis());
                            super.channelRead(ctx,message);
                        }
                        @Override public void write(ChannelHandlerContext ctx,Object message,ChannelPromise promise)throws Exception{
                            if(message instanceof io.netty.buffer.ByteBuf buf)trace.bytes(false,buf.readableBytes(),System.currentTimeMillis());
                            super.write(ctx,message,promise);
                        }
                    });
                    channel.pipeline().addBefore("packet_handler",HANDLER,new ChannelDuplexHandler(){
                        @Override public void channelRead(ChannelHandlerContext ctx,Object message)throws Exception{
                            if(trace!=null)trace.packet(true,message.getClass().getSimpleName(),System.currentTimeMillis());
                            if(probes!=null&&message.getClass().getSimpleName().equals("ServerboundSignUpdatePacket")&&probes.receive(id,message)){ReferenceCountUtil.release(message);return;}
                            super.channelRead(ctx,message);
                        }
                        @Override public void write(ChannelHandlerContext ctx,Object message,ChannelPromise promise)throws Exception{
                            ChatSilence silence=chat;
                            if(silence!=null){boolean allowed=true;try{allowed=silence.allowOutgoing(id,message);}catch(RuntimeException e){warn("Chat break "+message.getClass().getSimpleName()+": "+e.getClass().getSimpleName());}
                                if(!allowed){ReferenceCountUtil.release(message);promise.trySuccess();return;}}
                            Object filtered=message;Snapshot snapshot=guardEnabled?snapshots.getOrDefault(id,Snapshot.EMPTY):Snapshot.EMPTY;TeamLedger teams=ctx.channel().attr(TEAMS).get();
                            // 10.16.1: hand team memberships to the client as soon as the player is visible again (e.g. after the join check).
                            if(teams!=null&&teams.pending())try{for(Object join:teams.release(snapshot.names()))ctx.write(join);}catch(RuntimeException e){warn("Team release: "+e.getClass().getSimpleName());}
                            try{filtered=filter(message,snapshot,teams);}catch(ReflectiveOperationException|RuntimeException e){warn("Filter "+message.getClass().getSimpleName()+": "+e.getClass().getSimpleName());}
                            if(filtered==null){ReferenceCountUtil.release(message);promise.trySuccess();return;}
                            if(trace!=null)trace.packet(false,filtered.getClass().getSimpleName(),System.currentTimeMillis());
                            super.write(ctx,filtered,promise);
                        }
                        @Override public void exceptionCaught(ChannelHandlerContext ctx,Throwable cause)throws Exception{
                            if(trace!=null)trace.failure(cause.getClass().getSimpleName(),DisconnectForensics.describe(cause));
                            super.exceptionCaught(ctx,cause);
                        }
                        @Override public void close(ChannelHandlerContext ctx,ChannelPromise promise)throws Exception{
                            if(trace!=null)trace.closed("server",System.currentTimeMillis(),DisconnectForensics.callers(Thread.currentThread().getStackTrace()));
                            super.close(ctx,promise);
                        }
                        @Override public void channelInactive(ChannelHandlerContext ctx)throws Exception{
                            if(trace!=null)trace.closed("remote",System.currentTimeMillis(),List.of());
                            super.channelInactive(ctx);
                        }
                        @Override public void channelWritabilityChanged(ChannelHandlerContext ctx)throws Exception{
                            if(trace!=null)trace.writability(ctx.channel().isWritable(),ctx.channel().bytesBeforeWritable());
                            super.channelWritabilityChanged(ctx);
                        }
                        @Override public void userEventTriggered(ChannelHandlerContext ctx,Object event)throws Exception{
                            String type=event==null?"":event.getClass().getSimpleName();
                            if(trace!=null&&(type.contains("Idle")||type.contains("Timeout")))trace.failure(type,"Netty-Ereignis");
                            super.userEventTriggered(ctx,event);
                        }
                    });
                }catch(RuntimeException e){channels.remove(id,channel);if(trace!=null)diagnostics.remove(id,trace);warn("Injection: "+e.getMessage());}
            });
        }catch(ReflectiveOperationException|RuntimeException e){warn("Connection: "+e.getClass().getSimpleName()+" "+e.getMessage());}
    }
    Map<String,Object> trace(UUID id,long now){PacketTrace trace=diagnostics.get(id);return trace==null?Map.of():trace.snapshot(now);}
    /** 10.16.0: kernel TCP statistics for every open connection (epoll only), sampled on each channel's event loop. */
    void sampleTcp(){
        for(var entry:channels.entrySet()){
            PacketTrace trace=diagnostics.get(entry.getKey());Channel c=entry.getValue();
            if(trace==null||!c.isActive())continue;
            try{c.eventLoop().execute(()->{if(c.isActive()){trace.tcp(DisconnectForensics.tcpInfo(c));trace.writability(c.isWritable(),c.bytesBeforeWritable());}});}catch(RuntimeException ignored){}
        }
    }
    void quit(Player p){UUID id=p.getUniqueId();departed.add(id);snapshots.remove(id);
        if(plugin.isEnabled())Bukkit.getScheduler().runTaskLater(plugin,()->departed.remove(id),20L);
        PacketTrace trace=diagnostics.get(id);
        if(trace!=null&&plugin.isEnabled())Bukkit.getScheduler().runTaskLater(plugin,()->diagnostics.remove(id,trace),40L);
Channel c=channels.remove(id);if(c!=null)c.eventLoop().execute(()->{if(c.pipeline().get(HANDLER)!=null)c.pipeline().remove(HANDLER);if(c.pipeline().get(BYTES)!=null)c.pipeline().remove(BYTES);});}
    void stop(){for(Channel c:channels.values())c.eventLoop().execute(()->{
            // Without the guard, later "leave team" packets reach the client unfiltered: hand it every withheld membership first.
            TeamLedger teams=c.attr(TEAMS).getAndSet(null);ChannelHandlerContext ctx=c.pipeline().context(HANDLER);
            if(teams!=null&&ctx!=null&&c.isActive())try{for(Object join:teams.release(Set.of()))ctx.write(join);ctx.flush();}catch(RuntimeException e){warn("Team release: "+e.getClass().getSimpleName());}
            if(c.pipeline().get(HANDLER)!=null)c.pipeline().remove(HANDLER);if(c.pipeline().get(BYTES)!=null)c.pipeline().remove(BYTES);});channels.clear();snapshots.clear();diagnostics.clear();departed.clear();}
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
    static Object filter(Object packet,Snapshot s)throws ReflectiveOperationException{return filter(packet,s,new TeamLedger());}
    static Object filter(Object packet,Snapshot s,TeamLedger teams)throws ReflectiveOperationException{
        Class<?> type=packet.getClass();String name=type.getSimpleName();
        // 10.16.1: team packets go through the per-connection ledger, also while nobody is hidden – a "leave" for an entry this
        // client never received must be dropped, or the Java client throws and disconnects (Geyser/Bedrock ignores it).
        if(name.equals("ClientboundSetPlayerTeamPacket"))return teams==null?packet:teams.apply(packet,s.names());
        if(s.uuids.isEmpty()&&(teams==null||!teams.pending()))return packet;
        if(name.equals("ClientboundBundlePacket")){
            Iterable<?> packets=(Iterable<?>)call(packet,"subPackets");List<Object> kept=new ArrayList<>();boolean changed=false;
            for(Object child:packets){Object clean=filter(child,s,teams);if(clean!=child)changed=true;if(clean!=null)kept.add(clean);}
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
        if(name.equals("ClientboundSetScorePacket")||name.equals("ClientboundResetScorePacket")){if(s.names.contains(String.valueOf(call(packet,"owner")).toLowerCase(Locale.ROOT)))return null;}
        // Removal packets must pass through to clear entities/list entries already known to a client.
        if(name.matches("Clientbound(SetEntityData|SetEquipment|UpdateAttributes|UpdateMobEffect|RemoveMobEffect|EntityEvent|Animate|RotateHead|TeleportEntity|EntityPositionSync|MoveEntity.*|SetEntityMotion|DamageEvent|SoundEntity|AddEntity)Packet")||type.getName().contains("ClientboundMoveEntityPacket$")){
            Object id=property(packet,"entityId","getId","getEntityId","id","entityId");if(id instanceof Number n&&s.entityIds.contains(n.intValue()))return null;
        }
        return packet;
    }
    /**
     * 10.16.1 – the "one player leaves, every Java player is disconnected" fix.
     * <p>
     * The guard withholds team entries of hidden players (vanish, stealth, and every Java player during the client check
     * after joining). The old filter decided each packet only by who is hidden <em>now</em>: the "join team" went missing
     * while the player was hidden, but the later "leave team" on quit passed because the player was visible again. Java
     * clients then throw {@code IllegalStateException: Player is either on another team or not on any team} and close the
     * connection ("Network Protocol Error", logged as a plain "Disconnected") – all Java players at the same moment.
     * <p>
     * This ledger remembers exactly which entries this one connection never received: their "leave" is dropped, and once
     * the player is visible again the missing "join" is sent. Only used on the connection's event loop.
     */
    static final class TeamLedger{
        static final int ADD=0,REMOVE=1,JOIN=3,LEAVE=4;
        private final Map<String,LinkedHashSet<String>> withheld=new LinkedHashMap<>();
        private Class<?> type;
        boolean pending(){return !withheld.isEmpty();}
        boolean withheld(String team,String entry){Set<String> set=withheld.get(team);return set!=null&&set.contains(entry);}
        Object apply(Object packet,Set<String> hidden)throws ReflectiveOperationException{
            String team=String.valueOf(call(packet,"getName"));int method=((Number)property(packet,"method","getMethod")).intValue();
            type=packet.getClass();
            if(method==REMOVE){withheld.remove(team);return packet;}
            if(method!=ADD&&method!=JOIN&&method!=LEAVE)return packet;
            Collection<?> players=(Collection<?>)call(packet,"getPlayers");List<String> keep=new ArrayList<>(),held=new ArrayList<>();
            for(Object raw:players){String entry=String.valueOf(raw);
                boolean drop=method==LEAVE?withheld(team,entry):hidden.contains(entry.toLowerCase(Locale.ROOT));
                (drop?held:keep).add(entry);}
            Object result=packet;
            if(!held.isEmpty()){
                if(method==LEAVE){try{result=keep.isEmpty()?null:teamPacket(type,team,LEAVE,Optional.empty(),keep);}catch(ReflectiveOperationException|RuntimeException e){result=null;}}
                else result=teamPacket(type,team,method,method==ADD?(Optional<?>)call(packet,"getParameters"):Optional.empty(),keep);
            }
            // Commit only after the replacement packet exists, so a failed rebuild leaves the ledger matching the client.
            if(method==LEAVE){Set<String> set=withheld.get(team);if(set!=null){set.removeAll(held);if(set.isEmpty())withheld.remove(team);}}
            else{for(Object raw:players)forget(String.valueOf(raw));if(!held.isEmpty())withheld.computeIfAbsent(team,t->new LinkedHashSet<>()).addAll(held);}
            return result;
        }
        /** "Join team" packets for withheld entries that are no longer hidden; they leave the ledger once built. */
        List<Object> release(Set<String> hidden){
            if(withheld.isEmpty()||type==null)return List.of();List<Object> out=new ArrayList<>();
            for(Iterator<Map.Entry<String,LinkedHashSet<String>>> it=withheld.entrySet().iterator();it.hasNext();){
                Map.Entry<String,LinkedHashSet<String>> e=it.next();List<String> visible=e.getValue().stream().filter(n->!hidden.contains(n.toLowerCase(Locale.ROOT))).toList();
                if(visible.isEmpty())continue;
                try{out.add(teamPacket(type,e.getKey(),JOIN,Optional.empty(),visible));}catch(ReflectiveOperationException ex){continue;}
                visible.forEach(e.getValue()::remove);if(e.getValue().isEmpty())it.remove();
            }
            return out;
        }
        private void forget(String entry){withheld.values().removeIf(set->set.remove(entry)&&set.isEmpty());}
    }
    static Object teamPacket(Class<?> type,String team,int method,Optional<?> parameters,Collection<String> players)throws ReflectiveOperationException{
        for(Constructor<?> c:type.getDeclaredConstructors())if(c.getParameterCount()==4&&c.getParameterTypes()[0]==String.class&&c.getParameterTypes()[1]==int.class&&c.getParameterTypes()[2]==Optional.class&&Collection.class.isAssignableFrom(c.getParameterTypes()[3])){c.setAccessible(true);return c.newInstance(team,method,parameters,List.copyOf(players));}
        throw new NoSuchMethodException("Team packet constructor");
    }
    static void send(Player player,Object packet)throws ReflectiveOperationException{
        Object handle=player.getClass().getMethod("getHandle").invoke(player),connection=null;
        for(Class<?> type=handle.getClass();type!=null&&connection==null;type=type.getSuperclass())for(Field f:type.getDeclaredFields())if(f.getName().equals("connection")){f.setAccessible(true);connection=f.get(handle);break;}
        if(connection==null)throw new IllegalStateException("player connection unavailable");
        for(Method m:connection.getClass().getMethods())if(m.getName().equals("send")&&m.getParameterCount()==1&&m.getParameterTypes()[0].isInstance(packet)){m.invoke(connection,packet);return;}
        throw new NoSuchMethodException("connection.send(Packet)");
    }
}
