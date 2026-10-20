package eu.simplexsmp.adminsuite;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable, Bukkit-free correlation engine used by the admin menus.
 *
 * <p>Besides the bounded list of strongest direct matches, the engine builds two compact graphs:
 * a forensic graph containing every meaningful network/Bedrock-identity edge and a strong graph containing
 * only edges that are safe for automatic cascade actions. Connected-component membership is stored
 * once and shared by all profiles, so a transitive "deep link" does not require an O(n²) object cache.</p>
 *
 * <p>Java brand, protocol, locale, registered plugin channels and inferred mod names are deliberately
 * absent from this model. They are useful client diagnostics, but are shared by unrelated players and
 * must never create, score or expand an alternative-account relation.</p>
 */
final class AltMatchCacheEngine {
    private static final long DAY_MILLIS = 86_400_000L;

    private AltMatchCacheEngine() { }

    static Result build(List<Profile> profiles, int minimumScore, int maxResultsPerProfile, int cascadeMinimumScore, long now) {
        long started = System.nanoTime();
        int threshold = Math.max(1, minimumScore);
        int resultLimit = Math.max(100, maxResultsPerProfile);
        int cascadeThreshold = Math.max(70, cascadeMinimumScore);
        Map<UUID, Accumulator> accumulators = new LinkedHashMap<>();
        for (Profile profile : profiles) accumulators.put(profile.uuid, new Accumulator(resultLimit));

        UnionFind forensicGraph = new UnionFind(profiles.size());
        UnionFind strongGraph = new UnionFind(profiles.size());
        long evaluatedPairs = 0L;
        long acceptedPairs = 0L;
        long linkedPairs = 0L;
        for (int first = 0; first < profiles.size(); first++) {
            Profile one = profiles.get(first);
            for (int second = first + 1; second < profiles.size(); second++) {
                Profile two = profiles.get(second);
                if (one.ignoredLinks.contains(two.uuid.toString()) || two.ignoredLinks.contains(one.uuid.toString())) continue;
                evaluatedPairs++;

                if (!hasMeaningfulSharedSignal(one, two)) continue;
                Score score = score(one, two, cascadeThreshold, now);
                if (score.points <= 0) continue;

                forensicGraph.union(first, second);
                linkedPairs++;
                if (score.safeForCascade) strongGraph.union(first, second);

                if (score.points < threshold) continue;
                acceptedPairs++;
                accumulators.get(one.uuid).add(new Match(two.uuid, two.name, score.points, score.level, score.evidence, score.safeForCascade));
                accumulators.get(two.uuid).add(new Match(one.uuid, one.name, score.points, score.level, score.evidence, score.safeForCascade));
            }
        }

        Map<UUID, List<Match>> matches = new HashMap<>();
        for (Map.Entry<UUID, Accumulator> entry : accumulators.entrySet()) {
            List<Match> ordered = new ArrayList<>(entry.getValue().top);
            ordered.sort(Match.BEST_FIRST);
            matches.put(entry.getKey(), Collections.unmodifiableList(ordered));
        }

        Map<Integer, List<UUID>> forensicGroups = groupMembers(profiles, forensicGraph);
        Map<UUID, List<UUID>> componentMembers = new HashMap<>();
        Map<UUID, Integer> counts = new HashMap<>();
        for (List<UUID> group : forensicGroups.values()) {
            List<UUID> immutable = Collections.unmodifiableList(new ArrayList<>(group));
            int linked = Math.max(0, immutable.size() - 1);
            for (UUID uuid : immutable) {
                componentMembers.put(uuid, immutable);
                counts.put(uuid, linked);
            }
        }

        Map<UUID, UUID> strongComponentRoots = new HashMap<>();
        for (int index = 0; index < profiles.size(); index++) {
            int rootIndex = strongGraph.find(index);
            strongComponentRoots.put(profiles.get(index).uuid, profiles.get(rootIndex).uuid);
        }

        List<Profile> overviewProfiles = new ArrayList<>();
        for (Profile profile : profiles) if (counts.getOrDefault(profile.uuid, 0) > 0) overviewProfiles.add(profile);
        overviewProfiles.sort(Comparator
                .comparingInt((Profile profile) -> strongestScore(matches.get(profile.uuid))).reversed()
                .thenComparing(Comparator.comparingInt((Profile profile) -> counts.getOrDefault(profile.uuid, 0)).reversed())
                .thenComparing(Comparator.comparingLong((Profile profile) -> profile.lastSeen).reversed())
                .thenComparing(profile -> profile.name, String.CASE_INSENSITIVE_ORDER));
        List<UUID> overview = new ArrayList<>(overviewProfiles.size());
        for (Profile profile : overviewProfiles) overview.add(profile.uuid);

        long buildMillis = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
        return new Result(Collections.unmodifiableMap(matches), Collections.unmodifiableMap(counts),
                Collections.unmodifiableMap(componentMembers), Collections.unmodifiableMap(strongComponentRoots),
                Collections.unmodifiableList(overview), profiles.size(), evaluatedPairs, acceptedPairs, linkedPairs,
                buildMillis, now);
    }

    private static Map<Integer, List<UUID>> groupMembers(List<Profile> profiles, UnionFind graph) {
        Map<Integer, List<UUID>> groups = new LinkedHashMap<>();
        for (int index = 0; index < profiles.size(); index++) {
            groups.computeIfAbsent(graph.find(index), ignored -> new ArrayList<>()).add(profiles.get(index).uuid);
        }
        return groups;
    }

    private static int strongestScore(List<Match> matches) {
        return matches == null || matches.isEmpty() ? 0 : matches.get(0).score;
    }

    private static boolean hasMeaningfulSharedSignal(Profile one, Profile two) {
        if (one.manualLinks.contains(two.uuid.toString()) || two.manualLinks.contains(one.uuid.toString())) return true;
        return one.verifiedLinks.contains(two.uuid.toString())||two.verifiedLinks.contains(one.uuid.toString())||intersects(one.deviceHistory, two.deviceHistory)
                || intersects(one.xuidHistory, two.xuidHistory)
                || intersects(one.selfSignedHistory, two.selfSignedHistory)
                || intersects(one.ipHistory, two.ipHistory);
    }

    private static Score score(Profile one, Profile two, int cascadeMinimumScore, long now) {
        List<String> evidence = new ArrayList<>(6);
        int points = 0;
        boolean safe = false;
        if (one.manualLinks.contains(two.uuid.toString()) || two.manualLinks.contains(one.uuid.toString())) {
            points = 1000;
            evidence.add("Manuell bestätigt");
            safe = true;
        } else {
            if(one.verifiedLinks.contains(two.uuid.toString())||two.verifiedLinks.contains(one.uuid.toString())){points+=100;evidence.add("Authentifizierte Floodgate-Verknüpfung");safe=true;}
            if (intersects(one.deviceHistory, two.deviceHistory)) {
                points += 100; evidence.add("Gleiche Bedrock-Geräte-ID"); safe = true;
            }
            if (intersects(one.xuidHistory, two.xuidHistory)) {
                points += 95; evidence.add("Gleiche XUID-Historie"); safe = true;
            }
            if (intersects(one.selfSignedHistory, two.selfSignedHistory)) {
                points += 85; evidence.add("Gleiche selbstsignierte Bedrock-ID"); safe = true;
            }
            long newestIp = newestIntersection(one.ipHistory, two.ipHistory);
            if (newestIp > 0L) {
                long ageDays = Math.max(0L, (now - newestIp) / DAY_MILLIS);
                points += ageDays <= 7 ? 35 : ageDays <= 30 ? 25 : 12;
                evidence.add("Gemeinsame IP-Historie (" + ageDays + " Tage alt)");
            }
            safe = safe || points >= cascadeMinimumScore && evidence.stream().anyMatch(text ->
                    text.contains("Geräte-ID") || text.contains("XUID") || text.contains("selbstsignierte"));
        }
        boolean confirmed = safe && points >= 95;
        String level = confirmed ? "BESTÄTIGT" : points >= 70 ? "STARK" : points >= 45 ? "WAHRSCHEINLICH"
                : points >= 25 ? "SCHWACH" : "HINWEIS";
        return new Score(points, level, Collections.unmodifiableList(evidence), safe);
    }

    private static boolean intersects(Map<String, Long> first, Map<String, Long> second) {
        if (first.isEmpty() || second.isEmpty()) return false;
        Map<String, Long> smaller = first.size() <= second.size() ? first : second;
        Map<String, Long> larger = smaller == first ? second : first;
        for (String key : smaller.keySet()) if (larger.containsKey(key)) return true;
        return false;
    }

    private static long newestIntersection(Map<String, Long> first, Map<String, Long> second) {
        if (first.isEmpty() || second.isEmpty()) return 0L;
        Map<String, Long> smaller = first.size() <= second.size() ? first : second;
        Map<String, Long> larger = smaller == first ? second : first;
        long newest = 0L;
        for (Map.Entry<String, Long> entry : smaller.entrySet()) {
            Long other = larger.get(entry.getKey());
            if (other != null) newest = Math.max(newest, Math.min(entry.getValue(), other));
        }
        return newest;
    }

    static final class Profile {
        final UUID uuid;
        final String name;
        final long lastSeen;
        final Map<String, Long> ipHistory;
        final Map<String, Long> deviceHistory;
        final Map<String, Long> selfSignedHistory;
        final Map<String, Long> xuidHistory;
        final Set<String> manualLinks;
        final Set<String> ignoredLinks;
        final Set<String> verifiedLinks;

        Profile(UUID uuid, String name, long lastSeen, Map<String, Long> ipHistory, Map<String, Long> deviceHistory,
                Map<String, Long> selfSignedHistory, Map<String, Long> xuidHistory,
                Set<String> manualLinks, Set<String> ignoredLinks) {
            this(uuid,name,lastSeen,ipHistory,deviceHistory,selfSignedHistory,xuidHistory,manualLinks,ignoredLinks,Set.of());
        }
        Profile(UUID uuid,String name,long lastSeen,Map<String,Long> ipHistory,Map<String,Long> deviceHistory,Map<String,Long> selfSignedHistory,Map<String,Long> xuidHistory,Set<String> manualLinks,Set<String> ignoredLinks,Set<String> verifiedLinks){
            this.verifiedLinks=immutableSet(verifiedLinks);
            this.uuid = uuid;
            this.name = name == null ? "Unknown" : name;
            this.lastSeen = lastSeen;
            this.ipHistory = immutableMap(ipHistory);
            this.deviceHistory = immutableMap(deviceHistory);
            this.selfSignedHistory = immutableMap(selfSignedHistory);
            this.xuidHistory = immutableMap(xuidHistory);
            this.manualLinks = immutableSet(manualLinks);
            this.ignoredLinks = immutableSet(ignoredLinks);
        }

        private static Map<String, Long> immutableMap(Map<String, Long> source) {
            return Collections.unmodifiableMap(new HashMap<>(source == null ? Collections.emptyMap() : source));
        }

        private static Set<String> immutableSet(Set<String> source) {
            return Collections.unmodifiableSet(new HashSet<>(source == null ? Collections.emptySet() : source));
        }
    }

    static final class Match {
        static final Comparator<Match> BEST_FIRST = Comparator
                .comparingInt((Match match) -> match.score).reversed()
                .thenComparing(match -> match.targetName, String.CASE_INSENSITIVE_ORDER);
        private static final Comparator<Match> WORST_FIRST = (left, right) -> {
            int score = Integer.compare(left.score, right.score);
            if (score != 0) return score;
            return String.CASE_INSENSITIVE_ORDER.compare(right.targetName, left.targetName);
        };

        final UUID targetUuid;
        final String targetName;
        final int score;
        final String level;
        final List<String> evidence;
        final boolean safeForCascade;

        Match(UUID targetUuid, String targetName, int score, String level, List<String> evidence, boolean safeForCascade) {
            this.targetUuid = targetUuid;
            this.targetName = targetName;
            this.score = score;
            this.level = level;
            this.evidence = evidence;
            this.safeForCascade = safeForCascade;
        }
    }

    static final class Result {
        final Map<UUID, List<Match>> matches;
        final Map<UUID, Integer> counts;
        final Map<UUID, List<UUID>> componentMembers;
        final Map<UUID, UUID> strongComponentRoots;
        final List<UUID> overview;
        final int profileCount;
        final long evaluatedPairs;
        final long acceptedPairs;
        final long linkedPairs;
        final long buildMillis;
        final long createdAt;

        Result(Map<UUID, List<Match>> matches, Map<UUID, Integer> counts,
                Map<UUID, List<UUID>> componentMembers, Map<UUID, UUID> strongComponentRoots,
                List<UUID> overview, int profileCount, long evaluatedPairs, long acceptedPairs,
                long linkedPairs, long buildMillis, long createdAt) {
            this.matches = matches;
            this.counts = counts;
            this.componentMembers = componentMembers;
            this.strongComponentRoots = strongComponentRoots;
            this.overview = overview;
            this.profileCount = profileCount;
            this.evaluatedPairs = evaluatedPairs;
            this.acceptedPairs = acceptedPairs;
            this.linkedPairs = linkedPairs;
            this.buildMillis = buildMillis;
            this.createdAt = createdAt;
        }

        boolean sameStrongComponent(UUID one, UUID two) {
            UUID first = strongComponentRoots.get(one);
            UUID second = strongComponentRoots.get(two);
            return first != null && first.equals(second);
        }
    }

    private static final class Score {
        private final int points;
        private final String level;
        private final List<String> evidence;
        private final boolean safeForCascade;

        private Score(int points, String level, List<String> evidence, boolean safeForCascade) {
            this.points = points;
            this.level = level;
            this.evidence = evidence;
            this.safeForCascade = safeForCascade;
        }
    }

    private static final class Accumulator {
        private final int limit;
        private final PriorityQueue<Match> top = new PriorityQueue<>(Match.WORST_FIRST);

        private Accumulator(int limit) { this.limit = limit; }

        private void add(Match match) {
            if (top.size() < limit) {
                top.add(match);
                return;
            }
            Match weakest = top.peek();
            if (weakest != null && Match.BEST_FIRST.compare(match, weakest) < 0) {
                top.poll();
                top.add(match);
            }
        }
    }

    private static final class UnionFind {
        private final int[] parent;
        private final byte[] rank;

        private UnionFind(int size) {
            this.parent = new int[size];
            this.rank = new byte[size];
            for (int index = 0; index < size; index++) parent[index] = index;
        }

        private int find(int value) {
            int root = value;
            while (root != parent[root]) root = parent[root];
            while (value != root) {
                int next = parent[value];
                parent[value] = root;
                value = next;
            }
            return root;
        }

        private void union(int one, int two) {
            int first = find(one);
            int second = find(two);
            if (first == second) return;
            if (rank[first] < rank[second]) parent[first] = second;
            else if (rank[first] > rank[second]) parent[second] = first;
            else { parent[second] = first; rank[first]++; }
        }
    }
}
