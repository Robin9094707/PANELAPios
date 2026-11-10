package eu.simplexsmp.adminsuite;

import java.util.*;

/**
 * 10.16.0: escalation ladder for chat disputes. Pure state machine (no Bukkit access, unit-tested).
 * <p>
 * Every context analysis that finds a real dispute (or one player provoking others) is fed in here. The ladder makes
 * sure the bot never jumps straight to punishment:
 * <ol>
 * <li>calm – a neutral calming message in chat,</li>
 * <li>warn – a public warning plus a personal note to everyone involved,</li>
 * <li>short mute – a chat pause (default one minute) for the participants the AI marked as actively involved,</li>
 * <li>deep review – the bot gathers more context (longer chat, player history) before deciding on a longer pause
 *     (default five minutes) for whoever keeps escalating unnecessarily.</li>
 * </ol>
 * Players the AI marks as "excessive" (heavy, unprovoked abuse) go to the deep review immediately, at most once every
 * ten minutes per player. Victims and bystanders are never part of a mute step.
 */
final class PowerBotEscalation {
    static final long MINUTE = 60_000L, IDLE = 10 * MINUTE;
    static final List<String> SEVERITY = List.of("low", "medium", "high", "severe");

    enum Step { NONE, CALM, WARN, MUTE_SHORT, DEEP_REVIEW }

    /** What the caller should do now. {@code mute} and {@code deep} contain player ids. */
    record Plan(String dispute, Step step, int stage, List<String> mute, List<String> deep) {
        static final Plan NONE = new Plan("", Step.NONE, 0, List.of(), List.of());
        boolean acts() { return step != Step.NONE || !deep.isEmpty(); }
    }

    /** Timing of the ladder; all values in milliseconds. */
    record Timing(long calmToWarn, long warnToMute, long shortMute, long afterMute) {
        static final Timing DEFAULT = new Timing(40_000L, 30_000L, 60_000L, 30_000L);
    }

    static final class Dispute {
        final String id;
        final long started;
        final LinkedHashMap<String, String> players = new LinkedHashMap<>();
        /** Players the latest analysis marked as actively escalating – only they are eligible for a pause. */
        final Set<String> involved = new LinkedHashSet<>();
        /** Everyone who was marked at some point (shown to staff, never used for a pause). */
        final Set<String> everInvolved = new LinkedHashSet<>();
        long lastSeen, calmedAt, warnedAt, mutedAt, reviewedAt;
        int stage, analyses;
        String severity = "", reason = "", kind = "conflict";
        boolean dismissed;
        Dispute(String id, long now) { this.id = id; started = now; lastSeen = now; }
        boolean live(long now) { return !dismissed && now - lastSeen < IDLE; }
    }

    private final LinkedHashMap<String, Dispute> disputes = new LinkedHashMap<>();
    private final Map<String, Long> reviewed = new HashMap<>();

    /**
     * Feeds one analysis result into the ladder.
     *
     * @param participants all players in the dispute (id → name)
     * @param involved     ids the AI marked as actively driving it (eligible for a mute)
     * @param excessive    ids the AI marked as clearly over the top (deep review now)
     */
    synchronized Plan observe(long now, String kind, Map<String, String> participants, Set<String> involved, Set<String> excessive,
                              String severity, String reason, Timing timing) {
        expire(now);
        if (participants.isEmpty()) return Plan.NONE;
        Dispute dispute = null;
        for (Dispute candidate : disputes.values())
            if (candidate.live(now) && !Collections.disjoint(candidate.players.keySet(), participants.keySet())) { dispute = candidate; break; }
        if (dispute == null) {
            dispute = new Dispute(UUID.randomUUID().toString().substring(0, 8), now);
            disputes.put(dispute.id, dispute);
        }
        dispute.players.putAll(participants);
        // The latest analysis decides who is eligible: someone who stopped or now calms things down drops out.
        dispute.involved.clear();
        dispute.involved.addAll(involved);
        dispute.involved.retainAll(dispute.players.keySet());
        dispute.everInvolved.addAll(dispute.involved);
        dispute.lastSeen = now;
        dispute.analyses++;
        dispute.kind = kind == null || kind.isBlank() ? dispute.kind : kind;
        if (rank(severity) >= rank(dispute.severity)) dispute.severity = severity;
        if (reason != null && !reason.isBlank()) dispute.reason = reason;

        List<String> deep = new ArrayList<>();
        for (String id : excessive) if (dispute.players.containsKey(id) && now - reviewed.getOrDefault(id, 0L) >= IDLE) { deep.add(id); reviewed.put(id, now); }

        Step step = Step.NONE;
        List<String> mute = List.of();
        boolean severe = "severe".equals(severity);
        switch (dispute.stage) {
            case 0 -> {
                // A severe start skips the calm step: the public warning already calms and announces the consequence.
                if (severe) { step = Step.WARN; dispute.stage = 2; dispute.warnedAt = now; dispute.calmedAt = now; }
                else { step = Step.CALM; dispute.stage = 1; dispute.calmedAt = now; }
            }
            case 1 -> {
                if (now - dispute.calmedAt >= timing.calmToWarn() || severe) { step = Step.WARN; dispute.stage = 2; dispute.warnedAt = now; }
            }
            case 2 -> {
                if (now - dispute.warnedAt >= timing.warnToMute() && !dispute.involved.isEmpty()) {
                    step = Step.MUTE_SHORT; dispute.stage = 3; dispute.mutedAt = now; mute = List.copyOf(dispute.involved);
                }
            }
            case 3 -> {
                if (now - dispute.mutedAt >= timing.shortMute() + timing.afterMute()) {
                    for (String id : dispute.involved) if (!deep.contains(id) && now - reviewed.getOrDefault(id, 0L) >= IDLE) { deep.add(id); reviewed.put(id, now); }
                    if (!deep.isEmpty()) { step = Step.DEEP_REVIEW; dispute.stage = 4; dispute.reviewedAt = now; }
                }
            }
            default -> { }
        }
        if (step == Step.NONE && !deep.isEmpty()) dispute.reviewedAt = now;
        return new Plan(dispute.id, step, dispute.stage, mute, List.copyOf(deep));
    }

    /** True while a dispute with this player is still running (analyses then happen sooner). */
    synchronized boolean active(long now) {
        expire(now);
        return disputes.values().stream().anyMatch(d -> d.live(now));
    }

    synchronized boolean dismiss(String id) {
        Dispute dispute = disputes.get(id);
        if (dispute == null || dispute.dismissed) return false;
        dispute.dismissed = true;
        return true;
    }

    synchronized Set<String> players(String id) {
        Dispute dispute = disputes.get(id);
        return dispute == null ? Set.of() : Set.copyOf(dispute.players.keySet());
    }

    /** True when staff ended this dispute (or it is unknown) – pending AI results must not act on it any more. */
    synchronized boolean dismissed(String id) {
        Dispute dispute = disputes.get(id);
        return dispute == null || dispute.dismissed;
    }

    /** Live and recent disputes for app and web panel, newest first. */
    synchronized List<Map<String, Object>> rows(long now) {
        expire(now);
        List<Map<String, Object>> rows = new ArrayList<>();
        List<Dispute> list = new ArrayList<>(disputes.values());
        Collections.reverse(list);
        for (Dispute d : list) {
            List<Map<String, Object>> people = new ArrayList<>();
            d.players.forEach((id, name) -> people.add(SimplexAdminSuite.appMap("player", id, "name", name, "involved", d.involved.contains(id),
                "wasInvolved", d.everInvolved.contains(id))));
            rows.add(SimplexAdminSuite.appMap("id", d.id, "kind", d.kind, "started", d.started, "lastSeen", d.lastSeen, "stage", d.stage,
                "stageTitle", stageTitle(d.stage), "next", next(d.stage), "severity", d.severity, "reason", d.reason, "analyses", d.analyses,
                "live", d.live(now), "dismissed", d.dismissed, "players", people, "calmedAt", d.calmedAt, "warnedAt", d.warnedAt, "mutedAt", d.mutedAt,
                "reviewedAt", d.reviewedAt));
        }
        return rows;
    }

    static String stageTitle(int stage) {
        return switch (stage) { case 1 -> "Beruhigt"; case 2 -> "Verwarnt"; case 3 -> "Kurze Chatpause"; case 4 -> "Tiefenprüfung"; default -> "Beobachtet"; };
    }

    static String next(int stage) {
        return switch (stage) {
            case 1 -> "Geht der Streit weiter, warnt PowerBot öffentlich und persönlich.";
            case 2 -> "Geht der Streit weiter, folgt eine kurze Chatpause für die Beteiligten.";
            case 3 -> "Hört es danach nicht auf, prüft PowerBot mit mehr Kontext eine längere Chatpause.";
            case 4 -> "Längere Chatpause geprüft. PowerBot beobachtet weiter.";
            default -> "PowerBot beobachtet den Chat.";
        };
    }

    static int rank(String severity) { return SEVERITY.indexOf(severity == null ? "" : severity); }

    private void expire(long now) {
        disputes.values().removeIf(d -> now - d.lastSeen > 6 * 60 * MINUTE);
        while (disputes.size() > 40) disputes.remove(disputes.keySet().iterator().next());
        reviewed.values().removeIf(at -> now - at > IDLE);
    }
}
