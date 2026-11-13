package eu.simplexsmp.adminsuite;

import java.lang.reflect.Proxy;
import java.util.*;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

/**
 * 10.19.0: nametag and player-list tags must never be shared between players.
 * Up to 10.18.0 every Floodgate/Bedrock player ended up in the same team (first ten UUID hex digits are zero),
 * so prefixes and suffixes swapped above heads and in the player list.
 */
public final class Release1190Harness {
    static int checks;
    static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }

    /** Minimal in-memory scoreboard, enough for the sweep. */
    static final class Board {
        final Map<String, Set<String>> teams = new LinkedHashMap<>();
        final Map<String, Team> handles = new HashMap<>();
        Team team(String name) {
            return handles.computeIfAbsent(name, n -> (Team) Proxy.newProxyInstance(Team.class.getClassLoader(), new Class<?>[]{Team.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getName" -> n;
                case "getEntries" -> Set.copyOf(teams.getOrDefault(n, Set.of()));
                case "removeEntry" -> teams.getOrDefault(n, new HashSet<>()).remove((String) args[0]);
                case "addEntry" -> { teams.computeIfAbsent(n, x -> new LinkedHashSet<>()).add((String) args[0]); yield null; }
                case "unregister" -> { teams.remove(n); handles.remove(n); yield null; }
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            }));
        }
        Scoreboard scoreboard() {
            return (Scoreboard) Proxy.newProxyInstance(Scoreboard.class.getClassLoader(), new Class<?>[]{Scoreboard.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getTeams" -> { Set<Team> out = new LinkedHashSet<>(); for (String name : List.copyOf(teams.keySet())) out.add(team(name)); yield out; }
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            });
        }
        void join(String team, String entry) { teams.computeIfAbsent(team, x -> new LinkedHashSet<>()).add(entry); }
    }

    public static void main(String[] args) {
        // Floodgate builds Bedrock UUIDs as 00000000-0000-0000-0009-01xxxxxxxxxx.
        UUID bedrockA = UUID.fromString("00000000-0000-0000-0009-01f5a3c2b1d4");
        UUID bedrockB = UUID.fromString("00000000-0000-0000-0009-01a7b9c0e2f3");
        UUID java = UUID.fromString("5d2f1c3e-7a8b-4c9d-8e0f-1a2b3c4d5e6f");
        check(VisualIdentityManager.legacyTeamName(bedrockA).equals(VisualIdentityManager.legacyTeamName(bedrockB)), "reproduces the old shared Bedrock team");
        check(!VisualIdentityManager.teamName(bedrockA).equals(VisualIdentityManager.teamName(bedrockB)), "Bedrock players get separate teams");
        check(VisualIdentityManager.teamName(java).equals(VisualIdentityManager.teamName(UUID.fromString(java.toString()))), "team name is stable");
        for (UUID id : List.of(bedrockA, bedrockB, java)) {
            String name = VisualIdentityManager.teamName(id);
            check(name.length() <= 16, "fits the 16-character legacy team limit: " + name);
            check(name.startsWith(VisualIdentityManager.TEAM_PREFIX) && VisualIdentityManager.isSuiteName(name), "keeps the suite prefix");
        }
        // 2.000 Floodgate players: no collisions.
        Set<String> names = new HashSet<>();
        Random random = new Random(1190);
        for (int i = 0; i < 2000; i++) names.add(VisualIdentityManager.teamName(new UUID(0L, 0x0009010000000000L | (random.nextLong() & 0xFFFFFFFFFFL))));
        check(names.size() == 2000, "no collisions among 2.000 Bedrock UUIDs");

        // Sweep: the old shared team loses everyone, own teams stay, foreign teams are untouched.
        Board board = new Board();
        String legacy = VisualIdentityManager.legacyTeamName(bedrockA);
        board.join(legacy, ".BedrockA"); board.join(legacy, ".BedrockB");
        board.join(VisualIdentityManager.teamName(java), "JavaPlayer");
        board.join(VisualIdentityManager.teamName(java), "Stranger");
        board.join("tab_admin", "SomeoneElse");
        Map<String, String> owners = Map.of(
                ".BedrockA", VisualIdentityManager.teamName(bedrockA),
                ".BedrockB", VisualIdentityManager.teamName(bedrockB),
                "JavaPlayer", VisualIdentityManager.teamName(java));
        VisualIdentityManager.sweep(board.scoreboard(), owners);
        check(!board.teams.containsKey(legacy), "shared legacy team removed");
        check(board.teams.get(VisualIdentityManager.teamName(java)).equals(Set.of("JavaPlayer")), "own team keeps only its player");
        check(board.teams.get("tab_admin").equals(Set.of("SomeoneElse")), "foreign teams are never touched");

        // Player list: Paper may re-encode colours; that is not a foreign plugin.
        check(VisualIdentityManager.sameVisible("§x§f§f§0§0§0§0[Admin] §fRobin", "§x§F§F§0§0§0§0[Admin] §r§fRobin"), "re-encoded list name is recognised");
        check(!VisualIdentityManager.sameVisible("§c[Admin] Robin", "§a[Owner] Robin"), "a really different list name is still detected");
        check(!VisualIdentityManager.sameVisible(null, "x"), "null safe");
        String checklist = String.join("\n", AppPairing.checklist(true, true, "tls", "https://<Server-IP>:12000", 12000, "ab".repeat(32), ""));
        check(checklist.contains("UltraServers") && checklist.contains("HTTP-"), "pairing checklist explains the UltraServers allocation checkbox");
        System.out.println("PASS " + checks + " 10.19.0 nametag checks");
    }
}
