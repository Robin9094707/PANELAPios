package eu.simplexsmp.adminsuite;

import java.util.*;

/**
 * 10.16.1: one Java player leaving must not disconnect every other Java player.
 * <p>
 * The fake client below behaves like the vanilla Java client: a "leave team" for an entry that is not in that team
 * throws IllegalStateException, which disconnects the client ("Network Protocol Error").
 */
final class Release10161Harness {
    private static int checks;

    public static void main(String[] args) throws Exception {
        clientModel();
        hiddenDuringJoinCheckThenQuit();
        quitWhileStillHidden();
        mixedLeave();
        moveBetweenTeams();
        disabledGuardReleasesEverything();
        System.out.println("PASS " + checks + " 10.16.1 team packet consistency checks");
    }

    static void check(boolean ok, String what) { checks++; if (!ok) throw new AssertionError(what); }

    /** Same shape as net.minecraft's packet: private (String,int,Optional,Collection) constructor, no getMethod(). */
    public static final class ClientboundSetPlayerTeamPacket {
        private final String name; private final int method; private final Optional<?> parameters; private final List<String> players;
        private ClientboundSetPlayerTeamPacket(String name, int method, Optional<?> parameters, Collection<String> players) {
            this.name = name; this.method = method; this.parameters = parameters; this.players = List.copyOf(players);
        }
        public String getName() { return name; }
        public Optional<?> getParameters() { return parameters; }
        public Collection<String> getPlayers() { return players; }
    }

    static ClientboundSetPlayerTeamPacket packet(String team, int method, String... players) {
        return new ClientboundSetPlayerTeamPacket(team, method, method == 0 ? Optional.of("params") : Optional.empty(), List.of(players));
    }

    /** Minimal Java client scoreboard. */
    static final class Client {
        final Set<String> teams = new HashSet<>();
        final Map<String, String> entryTeam = new HashMap<>();
        void handle(Object raw) {
            if (!(raw instanceof ClientboundSetPlayerTeamPacket)) return;
            ClientboundSetPlayerTeamPacket p = (ClientboundSetPlayerTeamPacket) raw;
            if (p.method == 0) teams.add(p.name);
            else if (!teams.contains(p.name)) return; // unknown team: client only logs a warning
            if (p.method == 0 || p.method == 3) for (String e : p.players) entryTeam.put(e, p.name);
            if (p.method == 4) for (String e : p.players) {
                if (!p.name.equals(entryTeam.get(e))) throw new IllegalStateException("Player is either on another team or not on any team. Cannot remove from team '" + p.name + "'.");
                entryTeam.remove(e);
            }
            if (p.method == 1) { teams.remove(p.name); entryTeam.values().removeIf(p.name::equals); }
        }
    }

    /** What the guard's write() does for one outbound packet. */
    static void send(Client client, SuitePacketGuard.TeamLedger teams, SuitePacketGuard.Snapshot snapshot, Object packet) throws Exception {
        if (teams.pending()) for (Object join : teams.release(snapshot.names())) client.handle(join);
        client.handle(SuitePacketGuard.filter(packet, snapshot, teams));
    }

    static SuitePacketGuard.Snapshot hidden(String... names) {
        Set<UUID> ids = new HashSet<>(); Set<String> lower = new HashSet<>();
        for (String n : names) { ids.add(UUID.nameUUIDFromBytes(n.getBytes())); lower.add(n.toLowerCase(Locale.ROOT)); }
        return new SuitePacketGuard.Snapshot(ids, Set.of(), lower);
    }

    static void clientModel() {
        Client client = new Client();
        client.handle(packet("sasafk1", 0));
        boolean threw = false;
        try { client.handle(packet("sasafk1", 4, "Newbie")); } catch (IllegalStateException e) { threw = true; }
        check(threw, "fake client reproduces the Java disconnect for a leave without join");
    }

    /** The reported bug: hidden during the join check, visible afterwards, then quits. */
    static void hiddenDuringJoinCheckThenQuit() throws Exception {
        Client client = new Client(); SuitePacketGuard.TeamLedger teams = new SuitePacketGuard.TeamLedger();
        send(client, teams, hidden("Newbie"), packet("sasafk1", 0, "Newbie"));
        check(client.teams.contains("sasafk1") && !client.entryTeam.containsKey("Newbie"), "hidden entry withheld while the team itself is created");
        check(teams.pending(), "ledger remembers the withheld entry");
        send(client, teams, SuitePacketGuard.Snapshot.EMPTY, "KeepAlive");
        check("sasafk1".equals(client.entryTeam.get("Newbie")) && !teams.pending(), "entry handed to the client once visible again");
        send(client, teams, SuitePacketGuard.Snapshot.EMPTY, packet("sasafk1", 4, "Newbie"));
        send(client, teams, SuitePacketGuard.Snapshot.EMPTY, packet("sasafk1", 1));
        check(!client.teams.contains("sasafk1") && client.entryTeam.isEmpty(), "quit after the join check no longer disconnects the viewer");
    }

    static void quitWhileStillHidden() throws Exception {
        Client client = new Client(); SuitePacketGuard.TeamLedger teams = new SuitePacketGuard.TeamLedger();
        send(client, teams, hidden("Admin"), packet("sasafk2", 0, "Admin"));
        // reveal() during quit removes the name from the snapshot before the leave is sent
        send(client, teams, SuitePacketGuard.Snapshot.EMPTY, packet("sasafk2", 4, "Admin"));
        check(!teams.pending() && client.entryTeam.isEmpty(), "leave for a never-received entry is dropped, not crashing");
        send(client, teams, hidden("Admin"), packet("sasafk3", 0, "Admin"));
        send(client, teams, hidden("Admin"), packet("sasafk3", 4, "Admin"));
        send(client, teams, hidden("Admin"), packet("sasafk3", 1));
        check(!teams.pending() && !client.teams.contains("sasafk3"), "quit while still hidden stays consistent");
    }

    static void mixedLeave() throws Exception {
        Client client = new Client(); SuitePacketGuard.TeamLedger teams = new SuitePacketGuard.TeamLedger();
        send(client, teams, hidden("Ghost"), packet("collideRule", 0, "Ghost", "Visible"));
        check("collideRule".equals(client.entryTeam.get("Visible")) && !client.entryTeam.containsKey("Ghost"), "only the hidden entry is withheld");
        send(client, teams, hidden("Ghost"), packet("collideRule", 4, "Ghost", "Visible"));
        check(client.entryTeam.isEmpty() && !teams.pending(), "mixed leave keeps the known entry and drops the withheld one");
    }

    static void moveBetweenTeams() throws Exception {
        Client client = new Client(); SuitePacketGuard.TeamLedger teams = new SuitePacketGuard.TeamLedger();
        send(client, teams, hidden("Mover"), packet("old", 0, "Mover"));
        send(client, teams, hidden("Mover"), packet("new", 0));
        send(client, teams, hidden("Mover"), packet("old", 4, "Mover"));
        send(client, teams, hidden("Mover"), packet("new", 3, "Mover"));
        send(client, teams, SuitePacketGuard.Snapshot.EMPTY, "KeepAlive");
        check("new".equals(client.entryTeam.get("Mover")) && !teams.pending(), "move while hidden ends in the right team after reveal");
        send(client, teams, SuitePacketGuard.Snapshot.EMPTY, packet("new", 4, "Mover"));
        check(client.entryTeam.isEmpty(), "later leave is consistent");
    }

    static void disabledGuardReleasesEverything() throws Exception {
        Client client = new Client(); SuitePacketGuard.TeamLedger teams = new SuitePacketGuard.TeamLedger();
        send(client, teams, hidden("Vanished"), packet("t", 0, "Vanished"));
        for (Object join : teams.release(Set.of())) client.handle(join);
        check("t".equals(client.entryTeam.get("Vanished")) && !teams.pending(), "plugin stop hands every withheld entry to the client");
        client.handle(packet("t", 4, "Vanished"));
        check(client.entryTeam.isEmpty(), "unfiltered leave after stop is safe");
        check(SuitePacketGuard.filter("Other", SuitePacketGuard.Snapshot.EMPTY, teams).equals("Other"), "other packets untouched");
    }
}
