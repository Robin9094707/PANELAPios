package eu.simplexsmp.adminsuite;

import java.util.*;

/**
 * 10.2.0: pure disconnect classification and incident evaluation (no Bukkit access, unit-tested).
 * <p>
 * The server only knows what Paper reports: the quit reason, the kick cause/message and – through the console – the
 * "lost connection: …" line. From that every disconnect gets a category; groups of disconnects that happen close
 * together are evaluated with the server state at that moment (TPS, longest tick, memory, platforms, locations) and
 * receive a verdict that says whether it was a normal event or a real problem, with concrete next steps.
 */
final class DisconnectAnalyzer {
    private DisconnectAnalyzer() {}

    /** One player leaving. Coordinates are rounded to chunks; no IP address is ever stored. */
    record Record(String id, long at, String uuid, String name, String platform, String quitReason, String kickCause, String message, String category,
                  long sessionMs, int ping, String world, int chunkX, int chunkZ, double tps, double msptMax, int online, Map<String,Object> packetTrace,
                  String reported, long rejoinMs, Map<String, Object> client) {
        Record(String id,long at,String uuid,String name,String platform,String quitReason,String kickCause,String message,String category,
               long sessionMs,int ping,String world,int chunkX,int chunkZ,double tps,double msptMax,int online){
            this(id,at,uuid,name,platform,quitReason,kickCause,message,category,sessionMs,ping,world,chunkX,chunkZ,tps,msptMax,online,Map.of());
        }
        Record(String id,long at,String uuid,String name,String platform,String quitReason,String kickCause,String message,String category,
               long sessionMs,int ping,String world,int chunkX,int chunkZ,double tps,double msptMax,int online,Map<String,Object> packetTrace){
            this(id,at,uuid,name,platform,quitReason,kickCause,message,category,sessionMs,ping,world,chunkX,chunkZ,tps,msptMax,online,packetTrace,category,-1,Map.of());
        }
        Record {
            packetTrace = packetTrace == null ? Map.of() : packetTrace;
            client = client == null ? Map.of() : client;
            reported = reported == null || reported.isBlank() ? category : reported;
        }
        Map<String, Object> json() {
            Map<String, Object> out = SimplexAdminSuite.appMap("id", id, "at", at, "uuid", uuid, "name", name, "platform", platform, "quitReason", quitReason, "kickCause", kickCause, "message", message,
                "category", category, "categoryTitle", title(category), "involuntary", involuntary(category), "sessionMs", sessionMs, "ping", ping, "world", world, "chunkX", chunkX, "chunkZ", chunkZ,
                "blockX", chunkX * 16 + 8, "blockZ", chunkZ * 16 + 8, "tps", SparkProfile.round(tps, 2), "msptMax", SparkProfile.round(msptMax, 1), "online", online, "packetTrace",packetTrace);
            out.put("reported", reported);
            out.put("reportedTitle", title(reported));
            out.put("refined", !reported.equals(category));
            out.put("rejoinMs", rejoinMs);
            out.put("client", client);
            out.put("signals", signals(this));
            return out;
        }
        static Record of(Map<?, ?> m) {
            long rejoin = m.containsKey("rejoinMs") ? l(m.get("rejoinMs")) : -1;
            return new Record(s(m.get("id")), l(m.get("at")), s(m.get("uuid")), s(m.get("name")), s(m.get("platform")), s(m.get("quitReason")), s(m.get("kickCause")), s(m.get("message")), s(m.get("category")),
                l(m.get("sessionMs")), (int) l(m.get("ping")), s(m.get("world")), (int) l(m.get("chunkX")), (int) l(m.get("chunkZ")), d(m.get("tps")), d(m.get("msptMax")), (int) l(m.get("online")),AiAdmin.object(m.get("packetTrace")),
                s(m.get("reported")), rejoin, AiAdmin.object(m.get("client")));
        }
        Record withMessage(String text) {
            String clean = clip(text, 300);
            String base = DisconnectAnalyzer.category(quitReason, kickCause, clean);
            Record next = new Record(id, at, uuid, name, platform, quitReason, kickCause, clean, base, sessionMs, ping, world, chunkX, chunkZ, tps, msptMax, online, packetTrace, base, rejoinMs, client);
            return next.refined();
        }
        /** 10.16.0: the player came back after {@code ms}; a quick return turns a "Disconnected" into a silent drop. */
        Record withRejoin(long ms) {
            return new Record(id, at, uuid, name, platform, quitReason, kickCause, message, category, sessionMs, ping, world, chunkX, chunkZ, tps, msptMax, online, packetTrace, reported, ms, client).refined();
        }
        Record withClient(Map<String, Object> details) {
            return new Record(id, at, uuid, name, platform, quitReason, kickCause, message, category, sessionMs, ping, world, chunkX, chunkZ, tps, msptMax, online, packetTrace, reported, rejoinMs, details);
        }
        /** Same record with the category refined from packet trace and rejoin evidence; the reported category is kept. */
        Record refined() {
            String next = refine(this);
            return next.equals(category) ? this : new Record(id, at, uuid, name, platform, quitReason, kickCause, message, next, sessionMs, ping, world, chunkX, chunkZ, tps, msptMax, online, packetTrace, reported, rejoinMs, client);
        }
    }

    /** Server state at evaluation time. */
    record Context(int onlineJava, int onlineBedrock, double tps, double msptMaxRecent, double heapShare, double gcMsPerMinute, boolean watchdogRecently, boolean stopping, List<String> recentErrors,
                   List<Long> previousWaves) {
        Context(int onlineJava, int onlineBedrock, double tps, double msptMaxRecent, double heapShare, double gcMsPerMinute, boolean watchdogRecently, boolean stopping, List<String> recentErrors) {
            this(onlineJava, onlineBedrock, tps, msptMaxRecent, heapShare, gcMsPerMinute, watchdogRecently, stopping, recentErrors, List.of());
        }
        Context { previousWaves = previousWaves == null ? List.of() : List.copyOf(previousWaves); recentErrors = recentErrors == null ? List.of() : recentErrors; }
    }

    static final Map<String, String> TITLES = new LinkedHashMap<>();
    static {
        String[][] rows = {{"TIMEOUT", "Zeitüberschreitung (Timeout)"}, {"RESET", "Verbindung abgebrochen"}, {"PACKET", "Paket- oder Protokollfehler"}, {"CLIENT_QUIT", "Selbst getrennt"},
            {"GUARD_KICK", "Schutzsystem-Kick"}, {"KICK", "Gekickt (Team/Plugin)"}, {"IDLE", "AFK-Kick"}, {"RESOURCE_PACK", "Ressourcenpaket abgelehnt"}, {"ACCESS", "Bann/Whitelist"},
            {"DUPLICATE", "Doppelt angemeldet"}, {"SERVER_STOP", "Neustart/Stopp"}, {"ERROR", "Serverfehler"}, {"PROXY", "Proxy/Netzwerk-Weiterleitung"}, {"OTHER", "Sonstiges"},
            {"SILENT_DROP", "Verdeckter Abbruch (als „Disconnected“ gemeldet)"}, {"CLIENT_FREEZE", "Client eingefroren/abgestürzt"}, {"SERVER_CLOSE", "Vom Server/Plugin getrennt (ohne Kick)"}};
        for (String[] r : rows) TITLES.put(r[0], r[1]);
    }
    static String title(String category) { return TITLES.getOrDefault(category, category); }

    /** Categories that point to a problem rather than a player's own decision. */
    static boolean involuntary(String category) { return Set.of("TIMEOUT", "RESET", "PACKET", "ERROR", "PROXY", "GUARD_KICK", "SILENT_DROP", "CLIENT_FREEZE", "SERVER_CLOSE").contains(category); }
    /** Categories whose reported text looks like a normal quit – the ones 10.16.0 looks behind. */
    static boolean quietClose(String category) { return Set.of("CLIENT_QUIT", "SILENT_DROP", "CLIENT_FREEZE", "SERVER_CLOSE").contains(category); }
    /** Categories that never count for bursts (planned or staff-driven). */
    static boolean ignored(String category) { return Set.of("SERVER_STOP", "ACCESS", "DUPLICATE", "KICK", "IDLE").contains(category); }

    /** Category from Paper's quit reason, kick cause and the disconnect message (kick text or console "lost connection"). */
    static String category(String quitReason, String kickCause, String message) {
        String q = quitReason == null ? "" : quitReason, k = kickCause == null ? "" : kickCause, m = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (k.equals("RESTART_COMMAND") || m.contains("server closed") || m.contains("server is restarting") || m.contains("server wird neu gestartet") || m.contains("neustart") || m.contains("server stopping")) return "SERVER_STOP";
        if (Set.of("BANNED", "IP_BANNED", "WHITELIST").contains(k)) return "ACCESS";
        if (k.equals("DUPLICATE_LOGIN") || m.contains("logged in from another location") || m.contains("duplicate login")) return "DUPLICATE";
        if (k.equals("IDLING") || m.contains("idle for too long") || m.contains("afk")) return "IDLE";
        if (k.equals("RESOURCE_PACK_REJECTION") || m.contains("resource pack")) return "RESOURCE_PACK";
        if (k.equals("TIMEOUT") || q.equals("TIMED_OUT") || m.contains("timed out") || m.contains("timeout") || m.contains("readtimeoutexception") || m.contains("keepalive") || m.contains("keep-alive") || m.contains("zeitüberschreitung")) return "TIMEOUT";
        if (m.contains("decoderexception") || m.contains("encoderexception") || m.contains("badly compressed") || m.contains("packet too big") || m.contains("too big") || m.contains("error while decoding")
            || m.contains("invalid packet") || m.contains("packet") && m.contains("exception") || m.contains("network protocol error") || m.contains("received string length") || k.equals("INVALID_PAYLOAD")) return "PACKET";
        if (Set.of("FLYING_PLAYER", "FLYING_VEHICLE", "INVALID_VEHICLE_MOVEMENT", "INVALID_PLAYER_MOVEMENT", "INVALID_ENTITY_ATTACKED", "SPAM", "ILLEGAL_ACTION", "ILLEGAL_CHARACTERS", "OUT_OF_ORDER_CHAT",
            "UNSIGNED_CHAT", "CHAT_VALIDATION_FAILED", "EXPIRED_PROFILE_PUBLIC_KEY", "INVALID_PUBLIC_KEY_SIGNATURE", "TOO_MANY_PENDING_CHATS", "SELF_INTERACTION", "INVALID_COOKIE").contains(k)) return "GUARD_KICK";
        if (m.contains("connection reset") || m.contains("broken pipe") || m.contains("socketexception") || m.contains("forcibly closed") || m.contains("connection refused") || m.contains("eine vorhandene verbindung")
            || m.contains("ioexception") || m.contains("connection aborted") || m.contains("end of stream")) return "RESET";
        if (m.contains("velocity") || m.contains("bungeecord") || m.contains("proxy") || m.contains("could not connect to a default or fallback server")) return "PROXY";
        if (q.equals("ERRONEOUS_STATE") || m.contains("internal exception") || m.contains("internal server error")) return "ERROR";
        if (q.equals("KICKED") || !k.isEmpty() && !k.equals("UNKNOWN")) return "KICK";
        if (q.equals("DISCONNECTED") || m.equals("disconnected") || m.contains("client disconnected") || m.contains("disconnect.quitting") || m.contains("quitting") || m.isBlank()) return "CLIENT_QUIT";
        return "OTHER";
    }

    // ================================================================ 10.16.0 forensics

    static long trace(Record r, String key) { return r.packetTrace.containsKey(key) ? l(r.packetTrace.get(key)) : -1; }
    static String traceText(Record r, String key) { return s(r.packetTrace.get(key)); }
    static long tcp(Record r, String key) { return r.packetTrace.get("tcp") instanceof Map<?, ?> m && m.containsKey(key) ? l(m.get(key)) : -1; }
    static long tcpDelta(Record r, String key) { return r.packetTrace.get("tcpDelta") instanceof Map<?, ?> m && m.containsKey(key) ? l(m.get(key)) : 0; }

    /**
     * Looks behind a quiet close. Paper reports a remote close as a plain "Disconnected" – just like a player leaving
     * through the menu. The packet trace and the rejoin time tell them apart:
     * <ul>
     * <li>SERVER_CLOSE – the server side closed the channel without a kick event or disconnect packet,</li>
     * <li>CLIENT_FREEZE – the client had stopped sending for 8 s or more before the connection ended,</li>
     * <li>SILENT_DROP – the player was back within three minutes, or TCP retransmitted/lost segments right before.</li>
     * </ul>
     */
    static String refine(Record r) {
        String base = r.reported == null || r.reported.isBlank() ? r.category : r.reported;
        if (!Set.of("CLIENT_QUIT", "OTHER").contains(base)) return r.category;
        String side = traceText(r, "closeSide");
        if (side.equals("server") && r.kickCause.isBlank() && !Boolean.TRUE.equals(r.packetTrace.get("disconnectPacketSent"))) return "SERVER_CLOSE";
        long gap = trace(r, "lastInboundAgeMs");
        if (gap >= 8_000 && r.tps >= 15) return "CLIENT_FREEZE";
        if (r.rejoinMs >= 0 && r.rejoinMs <= 180_000) return "SILENT_DROP";
        if (tcpDelta(r, "totalRetrans") >= 5 || tcpDelta(r, "lost") > 0 || traceText(r, "exception").contains("IOException")) return "SILENT_DROP";
        return base;
    }

    /** Human readable evidence for one disconnect (German). */
    static List<String> signals(Record r) {
        List<String> out = new ArrayList<>();
        if (!r.reported.equals(r.category)) out.add("Gemeldet als „" + title(r.reported) + "“, eingeordnet als „" + title(r.category) + "“");
        if (r.rejoinMs >= 0) out.add("Nach " + duration(r.rejoinMs) + " wieder verbunden" + (r.rejoinMs <= 180_000 ? " – spricht gegen freiwilliges Verlassen" : ""));
        String side = traceText(r, "closeSide");
        switch (side) {
            case "remote" -> out.add("Verbindung von der Gegenseite geschlossen (Client, Router oder Filter dazwischen)");
            case "server" -> out.add(Boolean.TRUE.equals(r.packetTrace.get("disconnectPacketSent")) ? "Server hat mit Trennungsnachricht geschlossen" : "Server hat die Verbindung ohne Kick-Nachricht geschlossen");
            case "exception" -> out.add("Netzwerkfehler: " + (traceText(r, "exceptionDetail").isBlank() ? traceText(r, "exception") : traceText(r, "exceptionDetail")));
            default -> { }
        }
        long gap = trace(r, "lastInboundAgeMs");
        if (gap >= 0) out.add(gap >= 5_000 ? "Client sendete " + duration(gap) + " lang nichts vor der Trennung – eingefroren oder Leitung weg"
            : "Letztes Paket vom Client " + duration(gap) + " vor der Trennung – Client war bis zuletzt aktiv");
        long rtt = tcp(r, "rtt");
        if (rtt >= 0) out.add("TCP: RTT " + rtt + " ms" + (tcpDelta(r, "totalRetrans") > 0 ? ", " + tcpDelta(r, "totalRetrans") + " Neuübertragungen" : "")
            + (tcpDelta(r, "lost") > 0 ? ", " + tcpDelta(r, "lost") + " verlorene Segmente" : "") + (tcp(r, "unacked") > 0 ? ", " + tcp(r, "unacked") + " unbestätigt" : ""));
        if (trace(r, "unwritableEvents") > 0) out.add("Sendepuffer lief " + trace(r, "unwritableEvents") + "× voll – der Client nahm keine Daten mehr an");
        if (r.packetTrace.get("largestOutbound") instanceof List<?> list && !list.isEmpty() && list.get(list.size() - 1) instanceof Map<?, ?> big)
            out.add("Großes Paket kurz davor: " + big.get("type") + " (" + Math.round(l(big.get("bytes")) / 1024.0) + " KB)");
        if (r.packetTrace.get("closeStack") instanceof List<?> stack && !stack.isEmpty()) out.add("Ausgelöst von: " + stack.get(0));
        String version = s(r.client.get("version")), brand = s(r.client.get("brandLabel"));
        if (!version.isBlank() || !brand.isBlank()) out.add("Client: " + (brand.isBlank() ? "" : brand) + (version.isBlank() ? "" : (brand.isBlank() ? "" : " · ") + version));
        return out;
    }

    static String duration(long ms) {
        if (ms < 1000) return String.format(Locale.GERMAN, "%.1f s", ms / 1000.0);
        if (ms < 120_000) return (ms / 1000) + " s";
        return (ms / 60_000) + " min";
    }

    /** Regular waves point to a scheduled job. {@code times} are wave start times (any order). */
    static Map<String, Object> periodicity(List<Long> times) {
        List<Long> sorted = new ArrayList<>(times);
        Collections.sort(sorted);
        if (sorted.size() < 3) return Map.of("periodic", false);
        List<Long> gaps = new ArrayList<>();
        for (int i = 1; i < sorted.size(); i++) gaps.add(sorted.get(i) - sorted.get(i - 1));
        List<Long> ordered = new ArrayList<>(gaps);
        Collections.sort(ordered);
        long median = ordered.get(ordered.size() / 2);
        long deviation = 0;
        for (long gap : gaps) deviation += Math.abs(gap - median);
        deviation /= gaps.size();
        boolean periodic = median >= 20 * 60_000L && deviation <= Math.max(5 * 60_000L, median * 15 / 100);
        Map<Integer, Integer> minutes = new HashMap<>();
        for (long t : sorted) minutes.merge((int) ((t / 60_000L) % 60), 1, Integer::sum);
        int bestMinute = -1, bestCount = 0;
        for (var e : minutes.entrySet()) {
            int count = 0;
            for (var o : minutes.entrySet()) if (Math.min(Math.abs(o.getKey() - e.getKey()), 60 - Math.abs(o.getKey() - e.getKey())) <= 2) count += o.getValue();
            if (count > bestCount) { bestCount = count; bestMinute = e.getKey(); }
        }
        boolean sameMinute = bestCount >= 3 && bestCount * 10 >= sorted.size() * 7;
        return SimplexAdminSuite.appMap("periodic", periodic, "intervalMinutes", Math.round(median / 60_000.0), "deviationMinutes", Math.round(deviation / 60_000.0),
            "count", sorted.size(), "next", periodic ? sorted.get(sorted.size() - 1) + median : 0, "sameMinute", sameMinute, "minute", sameMinute ? bestMinute : -1);
    }

    /** Pattern facts across a group of disconnects. */
    static Map<String, Object> patterns(List<Record> group, Context ctx) {
        int n = group.size();
        List<Record> javaRows = group.stream().filter(r -> !"bedrock".equals(r.platform)).toList();
        int sync = 0;
        for (Record a : javaRows) { int c = 0; for (Record b : javaRows) if (b.at >= a.at && b.at - a.at <= 3_000) c++; sync = Math.max(sync, c); }
        int known = 0, rejoined = 0, remote = 0, server = 0, exception = 0, traced = 0, active = 0, frozen = 0, retrans = 0, congested = 0;
        Map<String, Integer> groups = new HashMap<>(), versions = new HashMap<>(), brands = new HashMap<>(), bigTypes = new HashMap<>(), culprits = new HashMap<>();
        List<Long> sessions = new ArrayList<>();
        for (Record r : group) {
            if (r.rejoinMs >= 0) { known++; if (r.rejoinMs <= 180_000) rejoined++; }
            if (!r.packetTrace.isEmpty()) {
                traced++;
                switch (traceText(r, "closeSide")) { case "remote" -> remote++; case "server" -> server++; case "exception" -> exception++; default -> { } }
                long gap = trace(r, "lastInboundAgeMs");
                if (gap >= 0 && gap < 2_000) active++;
                if (gap >= 8_000) frozen++;
                if (tcpDelta(r, "totalRetrans") >= 5 || tcpDelta(r, "lost") > 0) retrans++;
                if (trace(r, "unwritableEvents") > 0) congested++;
                String g = traceText(r, "networkGroup");
                if (!g.isBlank() && !g.equals("lokal/proxy")) groups.merge(g, 1, Integer::sum);
                if (r.packetTrace.get("largestOutbound") instanceof List<?> list && !list.isEmpty() && list.get(list.size() - 1) instanceof Map<?, ?> big)
                    bigTypes.merge(s(big.get("type")), 1, Integer::sum);
                if (r.packetTrace.get("closeStack") instanceof List<?> stack && !stack.isEmpty()) culprits.merge(s(stack.get(0)), 1, Integer::sum);
            }
            if (!s(r.client.get("version")).isBlank()) versions.merge(s(r.client.get("version")), 1, Integer::sum);
            if (!s(r.client.get("brandLabel")).isBlank()) brands.merge(s(r.client.get("brandLabel")), 1, Integer::sum);
            if (r.sessionMs > 0) sessions.add(r.sessionMs);
        }
        double cv = 1;
        if (sessions.size() >= 3) {
            double mean = sessions.stream().mapToLong(Long::longValue).average().orElse(0), var = 0;
            for (long v : sessions) var += (v - mean) * (v - mean);
            cv = mean <= 0 ? 1 : Math.sqrt(var / sessions.size()) / mean;
        }
        long spread = group.isEmpty() ? 0 : group.stream().mapToLong(Record::at).max().orElse(0) - group.stream().mapToLong(Record::at).min().orElse(0);
        int java = javaRows.size(), bedrock = n - java;
        boolean javaOnly = java > 0 && bedrock == 0 && ctx.onlineBedrock > 0;
        List<Long> waves = new ArrayList<>(ctx.previousWaves);
        if (!group.isEmpty()) waves.add(group.stream().mapToLong(Record::at).min().orElse(0));
        return SimplexAdminSuite.appMap("sync", sync, "spreadMs", spread, "javaOnly", javaOnly, "rejoinKnown", known, "rejoined", rejoined,
            "rejoinShare", known == 0 ? -1 : SparkProfile.round((double) rejoined / known, 2), "traced", traced, "remoteClose", remote, "serverClose", server,
            "exceptionClose", exception, "activeUntilClose", active, "frozen", frozen, "retrans", retrans, "congested", congested,
            "sessionVariation", SparkProfile.round(cv, 2), "sessionMedianMs", sessions.isEmpty() ? 0 : sessions.stream().sorted().toList().get(sessions.size() / 2),
            "topNetworkGroup", top(groups), "topNetworkGroupCount", groups.values().stream().max(Integer::compare).orElse(0), "networkGroups", groups.size(),
            "versions", versions, "brands", brands, "bigPackets", bigTypes, "closeCallers", culprits, "periodicity", periodicity(waves));
    }

    private static String top(Map<String, Integer> counts) { return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(""); }

    /** Ranked causes with a confidence from 0 to 100 and the facts behind each. */
    static List<Map<String, Object>> hypotheses(List<Record> group, Context ctx, Map<String, Object> p, double maxTick) {
        int n = Math.max(1, group.size());
        List<Map<String, Object>> out = new ArrayList<>();
        int sync = (int) l(p.get("sync")), traced = (int) l(p.get("traced")), remote = (int) l(p.get("remoteClose")), server = (int) l(p.get("serverClose"));
        int active = (int) l(p.get("activeUntilClose")), frozen = (int) l(p.get("frozen")), retrans = (int) l(p.get("retrans")), rejoined = (int) l(p.get("rejoined"));
        boolean javaOnly = Boolean.TRUE.equals(p.get("javaOnly"));
        double cv = d(p.get("sessionVariation"));
        boolean lag = ctx.watchdogRecently || maxTick >= 2500;
        Map<?, ?> period = p.get("periodicity") instanceof Map<?, ?> m ? m : Map.of();
        int quiet = (int) group.stream().filter(r -> quietClose(r.category)).count();
        if (quiet > 0 || javaOnly) {
            int c = 15 + (javaOnly ? 25 : 0) + (sync >= 3 ? 20 : 0) + (traced > 0 && remote * 2 >= traced ? 15 : 0) + (traced > 0 && active * 2 >= traced ? 10 : 0) + (rejoined * 2 >= n ? 10 : 0) - (lag ? 30 : 0);
            List<String> why = new ArrayList<>();
            if (javaOnly) why.add("nur Java betroffen, Bedrock (UDP) blieb verbunden");
            if (sync >= 3) why.add(sync + " Java-Verbindungen innerhalb von 3 s getrennt");
            if (traced > 0 && remote > 0) why.add(remote + " von " + traced + " von außen geschlossen, Server lief normal");
            if (active > 0) why.add(active + "× war der Client bis zuletzt aktiv – kein Einfrieren");
            out.add(hyp("network-filter", "DDoS-Schutz, Firewall oder Proxy beim Hoster trennt Java-TCP-Verbindungen", c, why,
                "Beim Hoster mit Uhrzeit und Anzahl nach DDoS-Mitigation, Filter-Updates oder Router-Neustarts fragen; bei TCPShield/Proxy dessen Log prüfen."));
        }
        if (cv < 0.2 && n >= 3) out.add(hyp("lifetime", "Verbindungen werden nach fester Dauer gekappt (NAT-/Conntrack- oder Proxy-Höchstdauer)", 35 + (cv < 0.1 ? 20 : 0),
            List.of("Sitzungsdauer der Betroffenen fast gleich (Streuung " + Math.round(cv * 100) + " %)"), "Hoster nach TCP-Idle-/Verbindungs-Timeouts von Firewall oder Proxy fragen; Keepalive-Einstellungen prüfen."));
        if (Boolean.TRUE.equals(period.get("periodic")) || Boolean.TRUE.equals(period.get("sameMinute"))) {
            List<String> why = new ArrayList<>();
            if (Boolean.TRUE.equals(period.get("periodic"))) why.add("Wellen etwa alle " + period.get("intervalMinutes") + " Minuten");
            if (Boolean.TRUE.equals(period.get("sameMinute"))) why.add("immer um Minute :" + String.format(Locale.ROOT, "%02d", l(period.get("minute"))));
            out.add(hyp("schedule", "Geplanter Vorgang (Backup, Filter-Update, Proxy-Neustart oder Plugin-Aufgabe)", 45 + (Boolean.TRUE.equals(period.get("periodic")) ? 20 : 0), why,
                "Zeitpläne beim Hoster (Backups, Snapshots), Cron-Jobs und Plugins mit Intervallen zur genannten Zeit prüfen."));
        }
        if (server > 0 && server * 2 >= Math.max(1, traced)) {
            Map<?, ?> callers = p.get("closeCallers") instanceof Map<?, ?> m ? m : Map.of();
            String caller = callers.isEmpty() ? "" : String.valueOf(callers.keySet().iterator().next());
            out.add(hyp("server-close", "Der Server oder ein Plugin schließt die Verbindungen ohne Kick-Meldung", 55, caller.isBlank() ? List.of(server + " Verbindungen serverseitig geschlossen") : List.of(server + " Verbindungen serverseitig geschlossen", "Aufrufer: " + caller),
                "Plugins mit Netzwerkschutz/Anticheat prüfen; Konsole kurz vor der Uhrzeit nach Warnungen ansehen."));
        }
        if (frozen > 0 && frozen * 2 >= Math.max(1, traced)) {
            Map<?, ?> big = p.get("bigPackets") instanceof Map<?, ?> m ? m : Map.of();
            List<String> why = new ArrayList<>(List.of(frozen + " Clients sendeten vor der Trennung mehrere Sekunden nichts"));
            if (!big.isEmpty()) why.add("großes Paket kurz davor: " + big.keySet().iterator().next());
            out.add(hyp("client-freeze", "Clients frieren ein oder stürzen ab", 40 + (big.isEmpty() ? 0 : 20) - (lag ? 20 : 0), why,
                "Betroffene nach Absturzmeldung fragen; Ort auf große Bücher, Karten, Schilder oder Chunks mit vielen Entities prüfen."));
        }
        if (retrans > 0 && retrans * 2 >= Math.max(1, traced))
            out.add(hyp("packet-loss", "Paketverlust auf dem Netzweg", 50, List.of(retrans + " Verbindungen mit TCP-Neuübertragungen/Verlust kurz vor dem Abbruch"),
                "Hoster nach Paketverlust/Überlastung fragen; mtr/traceroute von betroffenen Spielern zum Server."));
        int topGroup = (int) l(p.get("topNetworkGroupCount"));
        if (topGroup >= 3 && topGroup * 10 >= n * 6)
            out.add(hyp("provider", "Gemeinsamer Internetanbieter/Netzbereich der Betroffenen", 40, List.of(topGroup + " von " + n + " aus demselben Netzbereich"),
                "Betroffene nach Anbieter fragen; Störungsmeldungen dieses Anbieters prüfen."));
        if (p.get("versions") instanceof Map<?, ?> versions && !versions.isEmpty()) {
            var best = versions.entrySet().stream().max(Comparator.comparingLong(e -> l(e.getValue()))).orElse(null);
            if (best != null && l(best.getValue()) >= 3 && l(best.getValue()) * 10 >= n * 8 && versions.size() == 1 && ctx.onlineJava > 0)
                out.add(hyp("client-version", "Nur eine Client-Version betroffen", 30, List.of(best.getValue() + "× Version " + best.getKey()), "ViaVersion/ViaBackwards aktualisieren und die Version gezielt testen."));
        }
        if (lag) out.add(hyp("server-lag", "Server-Lag oder Hänger", 70, List.of("längster Tick " + Math.round(maxTick) + " ms" + (ctx.watchdogRecently ? ", Watchdog-Warnung" : "")),
            "Spark-Profil aufnehmen; Lag-Karte prüfen."));
        out.removeIf(h -> l(h.get("confidence")) < 20);
        out.sort((a, b) -> Long.compare(l(b.get("confidence")), l(a.get("confidence"))));
        return out.size() > 5 ? new ArrayList<>(out.subList(0, 5)) : out;
    }

    private static Map<String, Object> hyp(String id, String title, int confidence, List<String> why, String step) {
        return SimplexAdminSuite.appMap("id", id, "title", title, "confidence", Math.max(0, Math.min(95, confidence)), "why", why, "step", step);
    }

    /** True when {@code count} relevant disconnects happened within {@code windowMs} ending at the newest record. */
    static List<Record> burst(List<Record> recent, long now, long windowMs, int minimum) {
        List<Record> hit = new ArrayList<>();
        for (Record r : recent) if (now - r.at <= windowMs && r.at <= now && !ignored(r.category)) hit.add(r);
        return hit.size() >= minimum ? hit : List.of();
    }

    /** A player who keeps dropping: involuntary disconnects of one UUID in the window. */
    static int flapping(List<Record> recent, String uuid, long now, long windowMs) {
        int n = 0; for (Record r : recent) if (r.uuid.equals(uuid) && now - r.at <= windowMs && involuntary(r.category)) n++;
        return n;
    }

    /** Verdict for a group of disconnects. Returned map is stored with the incident. */
    static Map<String, Object> evaluate(List<Record> group, Context ctx) {
        int n = group.size();
        Map<String, Integer> byCategory = new LinkedHashMap<>();
        int java = 0, bedrock = 0, involuntary = 0, shortSessions = 0;
        double minTps = 20, maxTick = 0;
        Map<String, Integer> places = new HashMap<>();
        for (Record r : group) {
            byCategory.merge(r.category, 1, Integer::sum);
            if ("bedrock".equals(r.platform)) bedrock++; else java++;
            if (involuntary(r.category)) involuntary++;
            if (r.sessionMs > 0 && r.sessionMs < 120_000) shortSessions++;
            if (r.tps > 0) minTps = Math.min(minTps, r.tps);
            maxTick = Math.max(maxTick, r.msptMax);
            if (!r.world.isBlank()) places.merge(r.world + ":" + (r.chunkX >> 3) + ":" + (r.chunkZ >> 3), 1, Integer::sum);
        }
        maxTick = Math.max(maxTick, ctx.msptMaxRecent);
        String dominant = byCategory.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("OTHER");
        int dominantCount = byCategory.getOrDefault(dominant, 0);
        int onlineBefore = Math.max(n, ctx.onlineJava + ctx.onlineBedrock + n);
        int javaBefore = ctx.onlineJava + java, bedrockBefore = ctx.onlineBedrock + bedrock;
        double share = (double) n / onlineBefore, javaShare = javaBefore > 0 ? (double) java / javaBefore : 0;
        int clustered = places.values().stream().max(Integer::compare).orElse(0);

        String verdict, cause, severity; List<String> steps = new ArrayList<>(); List<String> evidence = new ArrayList<>();
        evidence.add(n + " Verbindungen getrennt (" + java + " Java, " + bedrock + " Bedrock) – " + Math.round(share * 100) + " % der Spieler");
        byCategory.forEach((k, v) -> evidence.add(v + "× " + title(k)));
        if (maxTick > 0) evidence.add("Längster Tick: " + Math.round(maxTick) + " ms · TPS min " + SparkProfile.fmt(minTps));
        if (ctx.heapShare > 0) evidence.add("RAM belegt: " + Math.round(ctx.heapShare * 100) + " %" + (ctx.gcMsPerMinute > 0 ? " · GC " + Math.round(ctx.gcMsPerMinute) + " ms/min" : ""));

        boolean lag = ctx.watchdogRecently || maxTick >= 2500 || minTps < 10;
        // 10.16.0: look behind quiet "Disconnected" closes before trusting them as voluntary.
        Map<String, Object> pattern = patterns(group, ctx);
        List<Map<String, Object>> hyps = hypotheses(group, ctx, pattern, maxTick);
        int quiet = 0, hiddenRefined = 0;
        for (Record r : group) { if (quietClose(r.category)) quiet++; if (Set.of("SILENT_DROP", "CLIENT_FREEZE", "SERVER_CLOSE").contains(r.category)) hiddenRefined++; }
        int sync = (int) l(pattern.get("sync")), rejoined = (int) l(pattern.get("rejoined")), rejoinKnown = (int) l(pattern.get("rejoinKnown"));
        boolean javaOnly = Boolean.TRUE.equals(pattern.get("javaOnly"));
        boolean hidden = !ctx.stopping && !lag && quiet >= 2 && quiet * 2 >= n
            && (hiddenRefined * 2 >= quiet || javaOnly && sync >= 3 || sync >= 4 || rejoined >= 2 && rejoined * 2 >= Math.max(1, rejoinKnown));
        if (sync >= 2) evidence.add(sync + " Java-Trennungen innerhalb von 3 Sekunden");
        if (rejoinKnown > 0) evidence.add(rejoined + " von " + rejoinKnown + " innerhalb von 3 Minuten wieder verbunden");
        if (l(pattern.get("traced")) > 0) evidence.add("Geschlossen: " + l(pattern.get("remoteClose")) + "× von außen, " + l(pattern.get("serverClose")) + "× vom Server, " + l(pattern.get("exceptionClose")) + "× durch Netzwerkfehler");
        if (hiddenRefined > 0) evidence.add(hiddenRefined + "× als „Disconnected“ gemeldet, Spuren sprechen für einen unfreiwilligen Abbruch");
        if (ctx.stopping) {
            verdict = "legit"; severity = "info"; cause = "Server fährt herunter oder startet neu – erwartete Trennungen.";
        } else if (hidden) {
            verdict = "problem"; severity = share >= 0.4 || n >= 6 ? "critical" : "warning";
            String top = hyps.isEmpty() ? "" : String.valueOf(hyps.get(0).get("title"));
            cause = "Verdeckte Trennungen: " + quiet + " Spieler wurden als „Disconnected“ gemeldet, haben den Server aber sehr wahrscheinlich nicht selbst verlassen"
                + (javaOnly ? " – nur Java war betroffen, Bedrock blieb verbunden" : "") + (top.isBlank() ? "." : ". Wahrscheinlichste Ursache: " + top + ".");
            for (Map<String, Object> h : hyps) if (steps.size() < 3) steps.add(String.valueOf(h.get("step")));
            steps.add("Live-Netzwerk in der App geöffnet lassen: bei der nächsten Welle zeigt es, welche Verbindungen zuerst stocken.");
        } else if (involuntary == 0 && (dominant.equals("CLIENT_QUIT") || dominant.equals("KICK") || dominant.equals("IDLE"))) {
            verdict = "legit"; severity = "info";
            cause = "Spieler haben selbst getrennt (" + dominantCount + "× „" + title(dominant) + "“) – kein Hinweis auf einen Fehler, z. B. Ende eines Events oder gemeinsame Pause.";
            steps.add("Nichts zu tun. Bei Häufung zur gleichen Uhrzeit: Event-Ende oder Discord-Ankündigung prüfen.");
        } else if (lag && (dominant.equals("TIMEOUT") || dominant.equals("RESET"))) {
            verdict = "problem"; severity = share >= 0.4 || n >= 6 ? "critical" : "warning";
            cause = ctx.watchdogRecently ? "Der Server hing (Watchdog-Warnung): Ticks liefen nicht, Clients bekamen keine Keep-Alives und liefen in Timeouts."
                : "Server-Lag: der längste Tick dauerte " + Math.round(maxTick) + " ms – Clients verloren währenddessen die Verbindung.";
            steps.add("Lag-Karte öffnen: Chunks mit vielen Entities/Block-Entities räumen.");
            steps.add("Spark-Profil (60 s) aufnehmen und analysieren lassen – zeigt das verantwortliche Plugin.");
            if (ctx.heapShare > 0.9 || ctx.gcMsPerMinute > 3000) steps.add("RAM fast voll bzw. lange GC-Pausen: Xmx/Container-RAM erhöhen oder Speicherfresser suchen.");
        } else if (dominant.equals("PACKET") && dominantCount * 2 >= n) {
            verdict = "problem"; severity = n >= 4 ? "critical" : "warning";
            cause = clustered >= Math.max(2, n / 2) ? "Paketfehler bei Spielern am selben Ort – typisch für ein defektes Item, Buch, Schild oder einen zu großen Chunk/Entity in diesem Bereich."
                : "Paketfehler bei mehreren Spielern – meist ein Plugin, das fehlerhafte Pakete sendet (ProtocolLib, ViaVersion, Geyser) oder eine Versionsinkompatibilität.";
            if (clustered >= 2) steps.add("Ort der betroffenen Spieler prüfen (Liste unten) – dort nach Item-Rahmen, Büchern, Bannern, Schulkerkisten mit großem Inhalt suchen.");
            steps.add("Letzte Plugin-Updates (ProtocolLib, ViaVersion, Geyser) prüfen und die Fehlermeldung in der Konsole ansehen.");
        } else if (dominant.equals("GUARD_KICK") && dominantCount * 2 >= n) {
            verdict = "problem"; severity = "warning";
            cause = "Das Schutzsystem (Paper/Anticheat) hat mehrere Spieler gleichzeitig gekickt – meist ein Fehlalarm nach Lag oder ein Plugin-Konflikt.";
            steps.add("Kick-Gründe unten ansehen; nach Lag treten „Flying“/„Invalid movement“ oft gesammelt auf.");
        } else if ((dominant.equals("TIMEOUT") || dominant.equals("RESET") || dominant.equals("PROXY")) && involuntary * 2 >= n) {
            verdict = "problem"; severity = share >= 0.4 || n >= 6 ? "critical" : "warning";
            if (java > 0 && bedrock == 0 && bedrockBefore > 0) cause = "Nur Java-Spieler betroffen, Bedrock blieb verbunden – Netzwerkproblem auf dem TCP-Pfad (Hoster-Netz, DDoS-Schutz, Proxy/TCPShield) bei normaler Serverleistung.";
            else if (bedrock > 0 && java == 0 && javaBefore > 0) cause = "Nur Bedrock-Spieler betroffen – Geyser/UDP-Pfad (Geyser-Fehler, UDP-Port oder DDoS-Schutz) bei normaler Serverleistung.";
            else cause = "Viele Verbindungen brachen gleichzeitig ab, obwohl der Server flüssig lief – das deutet auf das Netzwerk des Hosters oder den DDoS-Schutz hin.";
            steps.add("Beim Hoster nach Netzwerkstörungen/DDoS-Mitigation zur genannten Uhrzeit fragen (Uhrzeit und Anzahl mitschicken).");
            steps.add("Volldiagnose ausführen: prüft Erreichbarkeit von Mojang, DNS und Latenz vom Server aus.");
            if (javaShare >= 0.5) steps.add("Mehr als die Hälfte der Java-Spieler betroffen – bei Proxy/Velocity dessen Log zur gleichen Zeit prüfen.");
        } else {
            verdict = involuntary * 2 >= n ? "problem" : "unclear"; severity = involuntary * 2 >= n ? "warning" : "info";
            cause = "Gemischte Gründe – kein eindeutiges Muster. Die Einzelheiten unten und die KI-Analyse helfen bei der Einordnung.";
            steps.add("Einzelne Meldungen ansehen und bei Wiederholung eine Volldiagnose starten.");
        }
        if (!ctx.recentErrors.isEmpty() && !"legit".equals(verdict)) evidence.add("Fehler in der Konsole kurz davor: " + ctx.recentErrors.size());
        Map<String, Object> out = SimplexAdminSuite.appMap("verdict", verdict, "severity", severity, "cause", cause, "steps", steps, "evidence", evidence, "count", n, "java", java, "bedrock", bedrock,
            "share", SparkProfile.round(share, 3), "javaShare", SparkProfile.round(javaShare, 3), "dominant", dominant, "dominantTitle", title(dominant), "byCategory", byCategory, "maxTickMs", Math.round(maxTick),
            "minTps", SparkProfile.round(minTps, 2), "clustered", clustered);
        out.put("hidden", hidden);
        out.put("patterns", pattern);
        out.put("hypotheses", "legit".equals(verdict) ? List.of() : hyps);
        return out;
    }

    static String verdictTitle(String verdict) { return switch (verdict) { case "legit" -> "Legitim"; case "problem" -> "Fehler erkannt"; default -> "Unklar"; }; }

    private static String s(Object o) { return o == null ? "" : String.valueOf(o); }
    private static long l(Object o) { if (o instanceof Number n) return n.longValue(); try { return Long.parseLong(String.valueOf(o)); } catch (RuntimeException e) { return 0; } }
    private static double d(Object o) { if (o instanceof Number n) return n.doubleValue(); try { return Double.parseDouble(String.valueOf(o)); } catch (RuntimeException e) { return 0; } }
    static String clip(String s, int n) { return SparkProfile.clip(s, n); }
}
