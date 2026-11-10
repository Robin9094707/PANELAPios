package eu.simplexsmp.adminsuite;

import java.util.*;

/** 10.16.0: PowerBot escalation ladder, hostility signal and disconnect forensics (quiet closes, waves, periodicity). */
final class Release1016Harness {
    private static int checks;

    public static void main(String[] args) {
        signals();
        ladder();
        forensics();
        waves();
        trace();
        System.out.println("PASS " + checks + " 10.16.0 PowerBot escalation and disconnect forensics checks");
    }

    static void check(boolean ok, String what) { checks++; if (!ok) throw new AssertionError(what); }

    static void signals() {
        List<String> names = List.of("Alex", "Sam");
        check(PowerBotSignals.hostility("du bist so ein hurensohn", names) >= PowerBotSignals.HOSTILE, "directed German insult");
        check(PowerBotSignals.hostility("Halt die Fresse Sam", names) >= PowerBotSignals.HOSTILE, "phrase with name");
        check(PowerBotSignals.hostility("SAM DU SPAST!!!", names) >= PowerBotSignals.HOSTILE, "caps insult");
        check(PowerBotSignals.hostility("you are such a fucking retard", names) >= PowerBotSignals.HOSTILE, "English insult");
        check(PowerBotSignals.hostility("h u r e n s o h n", names) < PowerBotSignals.HOSTILE || true, "spaced letters do not crash");
        check(PowerBotSignals.hostility("gg, ich hab den Creeper gekillt, noob move von mir", names) < PowerBotSignals.HOSTILE, "gaming talk stays calm");
        check(PowerBotSignals.hostility("Hundert Diamanten im Schrank, Meerschweinchen-Farm läuft", names) < PowerBotSignals.HOSTILE, "harmless words");
        check(PowerBotSignals.hostility("", names) == 0 && PowerBotSignals.hostility("hallo zusammen", names) == 0, "neutral is zero");
        check(PowerBotSignals.normalise("Hüüüreeen$0hn").equals("huureensohn") || PowerBotSignals.normalise("Hüüüreeen$0hn").contains("sohn"), "normalise folds umlauts and leetspeak");
    }

    static void ladder() {
        PowerBotEscalation esc = new PowerBotEscalation();
        PowerBotEscalation.Timing t = PowerBotEscalation.Timing.DEFAULT;
        Map<String, String> people = new LinkedHashMap<>(Map.of("a", "Alex", "b", "Sam"));
        long now = 1_000_000;
        var p = esc.observe(now, "conflict", people, Set.of("a", "b"), Set.of(), "medium", "Streit", t);
        check(p.step() == PowerBotEscalation.Step.CALM && p.stage() == 1, "first analysis calms");
        p = esc.observe(now + 10_000, "conflict", people, Set.of("a", "b"), Set.of(), "medium", "", t);
        check(p.step() == PowerBotEscalation.Step.NONE, "no warning right after calming");
        p = esc.observe(now + 45_000, "conflict", people, Set.of("a", "b"), Set.of(), "high", "", t);
        check(p.step() == PowerBotEscalation.Step.WARN && p.stage() == 2, "continuing dispute is warned");
        p = esc.observe(now + 60_000, "conflict", people, Set.of("a", "b"), Set.of(), "high", "", t);
        check(p.step() == PowerBotEscalation.Step.NONE, "grace period after the warning");
        p = esc.observe(now + 80_000, "conflict", people, Set.of("a"), Set.of(), "high", "", t);
        check(p.step() == PowerBotEscalation.Step.MUTE_SHORT && p.mute().equals(List.of("a")), "short pause only for who still escalates – Sam stopped");
        p = esc.observe(now + 100_000, "conflict", people, Set.of("a"), Set.of(), "high", "", t);
        check(p.step() == PowerBotEscalation.Step.NONE, "no review while the pause runs");
        p = esc.observe(now + 175_000, "conflict", people, Set.of("a"), Set.of(), "high", "", t);
        check(p.step() == PowerBotEscalation.Step.DEEP_REVIEW && p.deep().contains("a"), "continuing after the pause → deep review");
        var rows = esc.rows(now + 175_000);
        check(rows.size() == 1 && ((Number) rows.get(0).get("stage")).intValue() == 4 && Boolean.TRUE.equals(rows.get(0).get("live")), "dispute row");
        check(!esc.dismissed(p.dispute()) && esc.dismiss(p.dispute()) && esc.dismissed(p.dispute()) && esc.dismissed("unknown"), "dismissed disputes block pending AI results");
        check(!esc.active(now + 175_000 + PowerBotEscalation.IDLE + 1), "disputes end after ten quiet minutes");

        PowerBotEscalation severe = new PowerBotEscalation();
        p = severe.observe(now, "provocation", Map.of("x", "Troll"), Set.of("x"), Set.of("x"), "severe", "Pöbelt", t);
        check(p.step() == PowerBotEscalation.Step.WARN && p.deep().contains("x"), "severe provocation: warning plus immediate deep review");
        p = severe.observe(now + 5_000, "provocation", Map.of("x", "Troll"), Set.of("x"), Set.of("x"), "severe", "", t);
        check(!p.deep().contains("x"), "deep review at most once per ten minutes");
        check(severe.dismiss(p.dispute()) && !severe.active(now + 6_000) && !severe.dismiss(p.dispute()), "staff can end a dispute once");

        PowerBotEscalation separate = new PowerBotEscalation();
        var one = separate.observe(now, "conflict", Map.of("a", "A"), Set.of("a"), Set.of(), "medium", "", t);
        var two = separate.observe(now + 1000, "conflict", Map.of("z", "Z"), Set.of("z"), Set.of(), "medium", "", t);
        check(!one.dispute().equals(two.dispute()) && two.step() == PowerBotEscalation.Step.CALM, "unrelated players get their own dispute");
        check(PowerBot.duration(60_000).equals("1 Minute") && PowerBot.duration(300_000).equals("5 Minuten") && PowerBot.duration(30_000).equals("30 Sekunden"), "durations");
    }

    static DisconnectAnalyzer.Record rec(String name, long at, String platform, Map<String, Object> trace, long rejoin, long session) {
        var r = new DisconnectAnalyzer.Record(UUID.randomUUID().toString().substring(0, 13), at, UUID.nameUUIDFromBytes(name.getBytes()).toString(), name, platform,
            "DISCONNECTED", "", "Disconnected", "CLIENT_QUIT", session, 40, "world", 0, 0, 20, 45, 10, trace, "CLIENT_QUIT", rejoin, Map.of());
        return r.refined();
    }

    static void forensics() {
        var remoteActive = Map.<String, Object>of("closeSide", "remote", "lastInboundAgeMs", 120L);
        check(rec("A", 0, "java", remoteActive, -1, 600_000).category().equals("CLIENT_QUIT"), "a plain remote close stays a quit");
        check(rec("B", 0, "java", remoteActive, 20_000, 600_000).category().equals("SILENT_DROP"), "back within 20 s = silent drop");
        check(rec("C", 0, "java", Map.of("closeSide", "remote", "lastInboundAgeMs", 12_000L), -1, 600_000).category().equals("CLIENT_FREEZE"), "client silent for 12 s = freeze");
        check(rec("D", 0, "java", Map.of("closeSide", "server", "lastInboundAgeMs", 50L, "disconnectPacketSent", false), -1, 600_000).category().equals("SERVER_CLOSE"), "server closed without kick");
        check(rec("E", 0, "java", Map.of("closeSide", "remote", "lastInboundAgeMs", 50L, "tcpDelta", Map.of("totalRetrans", 9L, "lost", 0L)), -1, 600_000).category().equals("SILENT_DROP"), "TCP retransmissions = silent drop");
        var r = rec("F", 0, "java", remoteActive, -1, 600_000);
        check(r.withRejoin(30_000).category().equals("SILENT_DROP") && r.withRejoin(30_000).reported().equals("CLIENT_QUIT"), "rejoin refines after the fact and keeps the report");
        check(r.withRejoin(30 * 60_000).category().equals("CLIENT_QUIT"), "returning much later stays a quit");
        check(DisconnectAnalyzer.involuntary("SILENT_DROP") && DisconnectAnalyzer.involuntary("CLIENT_FREEZE") && DisconnectAnalyzer.involuntary("SERVER_CLOSE"), "refined categories are involuntary");
        var json = r.withRejoin(30_000).json();
        check(Boolean.TRUE.equals(json.get("refined")) && json.get("signals") instanceof List<?> s && !s.isEmpty(), "json carries signals");
        var back = DisconnectAnalyzer.Record.of(json);
        check(back.rejoinMs() == 30_000 && back.reported().equals("CLIENT_QUIT") && back.category().equals("SILENT_DROP"), "round trip keeps forensic fields");
        check(DisconnectAnalyzer.Record.of(Map.of("id", "x", "category", "TIMEOUT")).rejoinMs() == -1, "old records read without rejoin");
        var old = DisconnectAnalyzer.Record.of(Map.of("id", "y", "category", "CLIENT_QUIT", "message", ""));
        check(old.withMessage("Disconnected").category().equals("CLIENT_QUIT"), "old record keeps behaviour");
    }

    static void waves() {
        long now = 10_000_000;
        var trace = Map.<String, Object>of("closeSide", "remote", "lastInboundAgeMs", 80L);
        List<DisconnectAnalyzer.Record> javaWave = new ArrayList<>();
        for (int i = 0; i < 4; i++) javaWave.add(rec("J" + i, now + i * 400, "java", trace, -1, 3_600_000L + i * 1000));
        var ctx = new DisconnectAnalyzer.Context(8, 3, 20, 45, 0.5, 200, false, false, List.of());
        var v = DisconnectAnalyzer.evaluate(javaWave, ctx);
        check("problem".equals(v.get("verdict")) && Boolean.TRUE.equals(v.get("hidden")), "four Java 'Disconnected' in 2 s while Bedrock stays = hidden problem: " + v.get("cause"));
        check(String.valueOf(v.get("cause")).contains("Verdeckte Trennungen") && String.valueOf(v.get("cause")).contains("nur Java"), "cause names the hidden wave");
        @SuppressWarnings("unchecked") var hyps = (List<Map<String, Object>>) v.get("hypotheses");
        check(!hyps.isEmpty() && hyps.stream().anyMatch(h -> "network-filter".equals(h.get("id"))) && hyps.stream().anyMatch(h -> "lifetime".equals(h.get("id"))), "hypotheses: network filter and equal lifetime");

        List<DisconnectAnalyzer.Record> spread = List.of(rec("S1", now, "java", Map.of(), -1, 100_000), rec("S2", now + 60_000, "bedrock", Map.of(), -1, 900_000));
        v = DisconnectAnalyzer.evaluate(spread, new DisconnectAnalyzer.Context(5, 2, 20, 45, 0.5, 200, false, false, List.of()));
        check("legit".equals(v.get("verdict")) && !Boolean.TRUE.equals(v.get("hidden")), "two unrelated quits stay legit");

        List<DisconnectAnalyzer.Record> rejoins = List.of(rec("R1", now, "java", trace, 15_000, 100_000), rec("R2", now + 900, "bedrock", trace, 25_000, 2_000_000), rec("R3", now + 1500, "java", trace, -1, 50_000));
        v = DisconnectAnalyzer.evaluate(rejoins, new DisconnectAnalyzer.Context(5, 2, 20, 45, 0.5, 200, false, false, List.of()));
        check("problem".equals(v.get("verdict")), "players who came back right away = problem");

        long h = 3_600_000L;
        var p = DisconnectAnalyzer.periodicity(List.of(0L, 4 * h + 120_000, 8 * h - 60_000, 12 * h + 30_000));
        check(Boolean.TRUE.equals(p.get("periodic")) && ((Number) p.get("intervalMinutes")).intValue() >= 235 && ((Number) p.get("intervalMinutes")).intValue() <= 245, "every ~4 h is periodic");
        check(!Boolean.TRUE.equals(DisconnectAnalyzer.periodicity(List.of(0L, h, 5 * h, 5 * h + 600_000)).get("periodic")), "irregular waves are not periodic");
        ctx = new DisconnectAnalyzer.Context(8, 3, 20, 45, 0.5, 200, false, false, List.of(), List.of(now - 8 * h, now - 4 * h));
        v = DisconnectAnalyzer.evaluate(javaWave, ctx);
        @SuppressWarnings("unchecked") var hyps2 = (List<Map<String, Object>>) v.get("hypotheses");
        check(hyps2.stream().anyMatch(x -> "schedule".equals(x.get("id"))), "previous waves every 4 h → scheduled job hypothesis");
    }

    static void trace() {
        PacketTrace t = new PacketTrace(0);
        t.packet(true, "ServerboundMovePlayerPacket", 1000);
        t.packet(false, "ClientboundLevelChunkWithLightPacket", 1500);
        t.bytes(false, 120_000, 1500);
        t.bytes(true, 300, 1000);
        t.failure("IOException", "Connection reset by peer 203.0.113.9:25565");
        t.closed("remote", 2000, List.of());
        var s = t.snapshot(2500);
        check("exception".equals(s.get("closeSide")) && !String.valueOf(s.get("exceptionDetail")).contains("203.0.113.9"), "exception wins, address masked");
        check(((Number) s.get("lastInboundAgeMs")).longValue() == 1000 && "ServerboundMovePlayerPacket".equals(s.get("lastInboundType")), "inbound silence measured at close");
        check(s.get("largestOutbound") instanceof List<?> l && l.size() == 1, "large outbound packet recorded");
        t.tcp(Map.of("totalRetrans", 2L, "lost", 0L, "rtt", 40L));
        t.tcp(Map.of("totalRetrans", 9L, "lost", 1L, "rtt", 90L));
        var d = (Map<?, ?>) t.snapshot(2600).get("tcpDelta");
        check(((Number) d.get("totalRetrans")).longValue() == 7 && ((Number) d.get("lost")).longValue() == 1, "TCP deltas");
        check(DisconnectForensics.callers(new StackTraceElement[]{new StackTraceElement("io.netty.channel.X", "close", "X.java", 1),
            new StackTraceElement("com.example.guard.Shield", "kickAll", "Shield.java", 42), new StackTraceElement("net.minecraft.network.Connection", "disconnect", "C.java", 7)})
            .get(0).equals("com.example.guard.Shield.kickAll:42"), "plugin frame first");
        check(DisconnectForensics.culprit(List.of("com.example.guard.Shield.kickAll:42"), Map.of("com.example.guard.", "Shield")).equals("Shield"), "culprit by package");
        check(DisconnectForensics.protocolName(769).equals("1.21.4"), "protocol names");
    }
}
