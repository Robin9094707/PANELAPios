package eu.simplexsmp.adminsuite;

import java.util.*;

/** Per-connection rolling metadata. Packet bodies, addresses and chat contents never enter this trace. */
final class PacketTrace {
    private record Sample(long at, String direction, String type) {}
    private final ArrayDeque<Sample> recent = new ArrayDeque<>();
    private long in, out, keepaliveIn, keepaliveOut, lastInSample, lastOutSample;
    private String error = "";
    synchronized void packet(boolean inbound,String type,long now){
        if(type.contains("KeepAlive")){if(inbound)keepaliveIn=now;else keepaliveOut=now;}
        if(inbound)in++;else out++;
        long sampled=inbound?lastInSample:lastOutSample;
        if(now-sampled<500&&!type.contains("KeepAlive")&&!type.contains("Disconnect"))return;
        if(inbound)lastInSample=now;else lastOutSample=now;
        recent.addLast(new Sample(now,inbound?"IN":"OUT",safeType(type)));
        while(recent.size()>14)recent.removeFirst();
    }
    synchronized void failure(String type){error=safeType(type);}
    synchronized Map<String,Object> snapshot(long now){
        return SimplexAdminSuite.appMap("source","Paper / Netty","inbound",in,"outbound",out,"exception",error,
            "keepaliveInAgeMs",keepaliveIn==0?-1:Math.max(0,now-keepaliveIn),"keepaliveOutAgeMs",keepaliveOut==0?-1:Math.max(0,now-keepaliveOut),
            "recent",recent.stream().filter(x->now-x.at<=30_000).map(x->Map.of("ageMs",Math.max(0,now-x.at),"direction",x.direction,"type",x.type)).toList());
    }
    private static String safeType(String type){return type.matches("[A-Za-z0-9_$]{1,100}")?type:"Unbekannter Typ";}
}
