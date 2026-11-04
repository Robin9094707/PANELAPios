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
                  long sessionMs, int ping, String world, int chunkX, int chunkZ, double tps, double msptMax, int online) {
        Map<String, Object> json() {
            return SimplexAdminSuite.appMap("id", id, "at", at, "uuid", uuid, "name", name, "platform", platform, "quitReason", quitReason, "kickCause", kickCause, "message", message,
                "category", category, "categoryTitle", title(category), "involuntary", involuntary(category), "sessionMs", sessionMs, "ping", ping, "world", world, "chunkX", chunkX, "chunkZ", chunkZ,
                "blockX", chunkX * 16 + 8, "blockZ", chunkZ * 16 + 8, "tps", SparkProfile.round(tps, 2), "msptMax", SparkProfile.round(msptMax, 1), "online", online);
        }
        static Record of(Map<?, ?> m) {
            return new Record(s(m.get("id")), l(m.get("at")), s(m.get("uuid")), s(m.get("name")), s(m.get("platform")), s(m.get("quitReason")), s(m.get("kickCause")), s(m.get("message")), s(m.get("category")),
                l(m.get("sessionMs")), (int) l(m.get("ping")), s(m.get("world")), (int) l(m.get("chunkX")), (int) l(m.get("chunkZ")), d(m.get("tps")), d(m.get("msptMax")), (int) l(m.get("online")));
        }
        Record withMessage(String text) {
            String clean = clip(text, 300);
            return new Record(id, at, uuid, name, platform, quitReason, kickCause, clean, DisconnectAnalyzer.category(quitReason, kickCause, clean), sessionMs, ping, world, chunkX, chunkZ, tps, msptMax, online);
        }
    }

    /** Server state at evaluation time. */
    record Context(int onlineJava, int onlineBedrock, double tps, double msptMaxRecent, double heapShare, double gcMsPerMinute, boolean watchdogRecently, boolean stopping, List<String> recentErrors) {}

    static final Map<String, String> TITLES = new LinkedHashMap<>();
    static {
        String[][] rows = {{"TIMEOUT", "Zeitüberschreitung (Timeout)"}, {"RESET", "Verbindung abgebrochen"}, {"PACKET", "Paket- oder Protokollfehler"}, {"CLIENT_QUIT", "Selbst getrennt"},
            {"GUARD_KICK", "Schutzsystem-Kick"}, {"KICK", "Gekickt (Team/Plugin)"}, {"IDLE", "AFK-Kick"}, {"RESOURCE_PACK", "Ressourcenpaket abgelehnt"}, {"ACCESS", "Bann/Whitelist"},
            {"DUPLICATE", "Doppelt angemeldet"}, {"SERVER_STOP", "Neustart/Stopp"}, {"ERROR", "Serverfehler"}, {"PROXY", "Proxy/Netzwerk-Weiterleitung"}, {"OTHER", "Sonstiges"}};
        for (String[] r : rows) TITLES.put(r[0], r[1]);
    }
    static String title(String category) { return TITLES.getOrDefault(category, category); }

    /** Categories that point to a problem rather than a player's own decision. */
    static boolean involuntary(String category) { return Set.of("TIMEOUT", "RESET", "PACKET", "ERROR", "PROXY", "GUARD_KICK").contains(category); }
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
        if (ctx.stopping) {
            verdict = "legit"; severity = "info"; cause = "Server fährt herunter oder startet neu – erwartete Trennungen.";
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
        return SimplexAdminSuite.appMap("verdict", verdict, "severity", severity, "cause", cause, "steps", steps, "evidence", evidence, "count", n, "java", java, "bedrock", bedrock,
            "share", SparkProfile.round(share, 3), "javaShare", SparkProfile.round(javaShare, 3), "dominant", dominant, "dominantTitle", title(dominant), "byCategory", byCategory, "maxTickMs", Math.round(maxTick),
            "minTps", SparkProfile.round(minTps, 2), "clustered", clustered);
    }

    static String verdictTitle(String verdict) { return switch (verdict) { case "legit" -> "Legitim"; case "problem" -> "Fehler erkannt"; default -> "Unklar"; }; }

    private static String s(Object o) { return o == null ? "" : String.valueOf(o); }
    private static long l(Object o) { if (o instanceof Number n) return n.longValue(); try { return Long.parseLong(String.valueOf(o)); } catch (RuntimeException e) { return 0; } }
    private static double d(Object o) { if (o instanceof Number n) return n.doubleValue(); try { return Double.parseDouble(String.valueOf(o)); } catch (RuntimeException e) { return 0; } }
    static String clip(String s, int n) { return SparkProfile.clip(s, n); }
}
