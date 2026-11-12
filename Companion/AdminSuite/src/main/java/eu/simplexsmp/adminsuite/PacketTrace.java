package eu.simplexsmp.adminsuite;

import java.util.*;

/**
 * Per-connection rolling metadata. Packet bodies, addresses and chat contents never enter this trace.
 * <p>
 * 10.16.0 (Disconnect-Forensik): wire bytes and rates, the last packet in each direction, the largest recent
 * outbound packets, who closed the connection (server, remote side or an exception), a full send buffer, whether a
 * disconnect packet was sent and periodic TCP statistics from the kernel (RTT, retransmissions, loss) where the
 * native transport exposes them.
 */
final class PacketTrace {
    private record Sample(long at, String direction, String type) {}
    private record Big(long at, String type, int bytes) {}
    private final ArrayDeque<Sample> recent = new ArrayDeque<>();
    private final ArrayDeque<Big> big = new ArrayDeque<>();
    private final long created;
    private long in, out, keepaliveIn, keepaliveOut, lastInSample, lastOutSample;
    private long lastIn, lastOut, bytesIn, bytesOut, closedAt, pendingBytes, second;
    private final long[] secondIn = new long[10], secondOut = new long[10];
    private int unwritable;
    private boolean disconnectSent, writable = true;
    private String error = "", errorDetail = "", lastInType = "", lastOutType = "", closeSide = "", transport = "", group = "";
    private List<String> closeStack = List.of();
    private Map<String, Object> tcp = Map.of(), tcpFirst = Map.of();

    PacketTrace() { this(System.currentTimeMillis()); }
    PacketTrace(long created) { this.created = created; }

    synchronized void packet(boolean inbound,String type,long now){
        if(type.contains("KeepAlive")){if(inbound)keepaliveIn=now;else keepaliveOut=now;}
        if(inbound){in++;lastIn=now;lastInType=safeType(type);}else{out++;lastOut=now;lastOutType=safeType(type);if(type.contains("Disconnect"))disconnectSent=true;}
        long sampled=inbound?lastInSample:lastOutSample;
        if(now-sampled<500&&!type.contains("KeepAlive")&&!type.contains("Disconnect"))return;
        if(inbound)lastInSample=now;else lastOutSample=now;
        recent.addLast(new Sample(now,inbound?"IN":"OUT",safeType(type)));
        while(recent.size()>14)recent.removeFirst();
    }

    /** Wire bytes (after compression and encryption) seen at the head of the pipeline. */
    synchronized void bytes(boolean inbound, int count, long now) {
        if (count <= 0) return;
        roll(now);
        if (inbound) { bytesIn += count; secondIn[(int) (second % 10)] += count; }
        else {
            bytesOut += count; secondOut[(int) (second % 10)] += count;
            if (count >= 32_768) {
                big.addLast(new Big(now, lastOutType.isBlank() ? "Unbekannt" : lastOutType, count));
                while (big.size() > 8) big.removeFirst();
            }
        }
    }

    private void roll(long now) {
        long current = now / 1000;
        if (second == 0) { second = current; return; }
        for (long s = second + 1; s <= current && s - second <= 10; s++) { secondIn[(int) (s % 10)] = 0; secondOut[(int) (s % 10)] = 0; }
        second = Math.max(second, current);
    }

    synchronized void failure(String type){error=safeType(type);}
    synchronized void failure(String type, String message) {
        error = safeType(type);
        errorDetail = message == null ? "" : SparkProfile.clip(redact(message), 200);
        if (closeSide.isBlank()) closeSide = "exception";
    }

    /** First close wins; an exception before the close stays the more specific cause. */
    synchronized void closed(String side, long now, List<String> stack) {
        if (closedAt != 0) return;
        closedAt = now;
        if (closeSide.isBlank()) closeSide = side;
        if (stack != null && !stack.isEmpty()) closeStack = List.copyOf(stack.subList(0, Math.min(8, stack.size())));
    }

    synchronized void writability(boolean nowWritable, long pending) {
        if (!nowWritable && writable) unwritable++;
        writable = nowWritable;
        pendingBytes = Math.max(0, pending);
    }

    synchronized void meta(String transport, String group) {
        this.transport = transport == null ? "" : transport;
        this.group = group == null ? "" : group;
    }

    synchronized void tcp(Map<String, Object> info) {
        if (info == null || info.isEmpty()) return;
        if (tcpFirst.isEmpty()) tcpFirst = info;
        tcp = info;
    }

    synchronized Map<String,Object> snapshot(long now){
        roll(now);
        long rateIn = 0, rateOut = 0;
        for (int i = 0; i < 10; i++) { rateIn += secondIn[i]; rateOut += secondOut[i]; }
        long reference = closedAt == 0 ? now : closedAt;
        Map<String, Object> delta = new LinkedHashMap<>();
        if (!tcp.isEmpty() && !tcpFirst.isEmpty()) for (String key : List.of("totalRetrans", "lost"))
            delta.put(key, Math.max(0, AiAdmin.number(tcp.get(key)) - AiAdmin.number(tcpFirst.get(key))));
        return SimplexAdminSuite.appMap("source","Paper / Netty","inbound",in,"outbound",out,"exception",error,
            "keepaliveInAgeMs",keepaliveIn==0?-1:Math.max(0,now-keepaliveIn),"keepaliveOutAgeMs",keepaliveOut==0?-1:Math.max(0,now-keepaliveOut),
            "recent",recent.stream().filter(x->now-x.at<=30_000).map(x->Map.of("ageMs",Math.max(0,now-x.at),"direction",x.direction,"type",x.type)).toList(),
            "exceptionDetail", errorDetail, "bytesIn", bytesIn, "bytesOut", bytesOut, "rateInBps", rateIn / 10, "rateOutBps", rateOut / 10,
            "lastInboundAgeMs", lastIn == 0 ? -1 : Math.max(0, reference - lastIn), "lastOutboundAgeMs", lastOut == 0 ? -1 : Math.max(0, reference - lastOut),
            "lastInboundType", lastInType, "lastOutboundType", lastOutType, "closeSide", closeSide, "closedAgoMs", closedAt == 0 ? -1 : Math.max(0, now - closedAt),
            "closeStack", closeStack, "disconnectPacketSent", disconnectSent, "unwritableEvents", unwritable, "pendingWriteBytes", pendingBytes,
            "writable", writable, "largestOutbound", big.stream().filter(b -> now - b.at <= 30_000).map(b -> Map.of("type", b.type, "bytes", b.bytes, "ageMs", Math.max(0, now - b.at))).toList(),
            "tcp", tcp, "tcpDelta", delta, "transport", transport, "networkGroup", group, "connectionAgeMs", Math.max(0, reference - created));
    }

    private static String safeType(String type){return type.matches("[A-Za-z0-9_$]{1,100}")?type:"Unbekannter Typ";}

    static String redact(String text) {
        return text.replaceAll("(?<![0-9.])(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d{1,5})?(?![0-9.])", "<ip>")
            .replaceAll("\\[?[0-9a-fA-F]{1,4}(?::[0-9a-fA-F]{0,4}){2,7}\\]?(?::\\d{1,5})?", "<ip>")
            .replaceAll("[\\p{Cntrl}§]", " ");
    }
}
