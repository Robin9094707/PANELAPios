package eu.simplexsmp.adminsuite;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Metadata from Paper's decoded Netty pipeline. Packet payloads are never inspected or retained. */
final class PacketTrace {
    private static final int MAX_CONNECTIONS=4096;
    private final Map<UUID,Session> sessions=new ConcurrentHashMap<>();
    static final class Session {
        private long in,out,lastInAt,lastOutAt,errorAt;
        private String lastIn="",lastOut="",error="";
        synchronized void received(Object packet,long now){
            in++;lastIn=type(packet);lastInAt=now;
        }
        synchronized void sent(Object packet,long now){
            out++;lastOut=type(packet);lastOutAt=now;
        }
        synchronized void failure(Throwable cause,long now){
            Throwable root=cause;
            for(int depth=0;depth<6&&root.getCause()!=null&&root.getCause()!=root;depth++)root=root.getCause();
            error=type(cause)+(root==cause?"":" / "+type(root));errorAt=now;
        }
        synchronized Map<String,Object> snapshot(long now){
            return SimplexAdminSuite.appMap("source","paper-decoded","inbound",in,"outbound",out,"lastInbound",lastIn,
                "lastOutbound",lastOut,"lastInboundAgeMs",age(now,lastInAt),"lastOutboundAgeMs",age(now,lastOutAt),
                "error",error,"errorAgeMs",age(now,errorAt));
        }
        private static long age(long now,long timestamp){return timestamp==0?-1:Math.max(0,now-timestamp);}
        private static String type(Object value){
            String name=value.getClass().getSimpleName();
            return name.length()>100?name.substring(0,100):name;
        }
    }
    Session start(UUID player){
        if(sessions.size()>=MAX_CONNECTIONS&&!sessions.containsKey(player))return null;
        Session session=new Session();sessions.put(player,session);return session;
    }
    Map<String,Object> take(UUID player,long now){
        Session session=sessions.remove(player);
        return session==null?Map.of():session.snapshot(now);
    }
    void clear(){sessions.clear();}
}
