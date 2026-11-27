package eu.simplexsmp.adminsuite;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * 10.24.0: Länder-Zentrale (Lands API via reflection, routes, web, rights) and experimental identity signals
 * (network prefixes, rare skins, account switches – hints only, never cascade-safe).
 */
public final class Release1240Harness {
    static int checks;
    static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }

    /** Stand-in for a Lands object with overloads like Land#delete(LandPlayer) / delete(Player). */
    public static final class FakeLandPlayer { }
    public static final class FakeLand {
        public String deleted = "";
        public String getName() { return "§aRobin<bold>ia"; }
        public void delete(FakeLandPlayer player) { deleted = "landplayer"; }
        public void delete(String other) { deleted = "string"; }
        public boolean setBalance(double value) { return value >= 0; }
        public int getSize() { return 7; }
        public void boom() { throw new IllegalStateException("Lands sagt nein"); }
    }

    public static void main(String[] args) throws Exception {
        networkPrefixes();
        engine();
        reflection();
        texts();
        wiring();
        System.out.println("PASS " + checks + " 10.24.0 Länder-Zentrale and identity signal checks");
    }

    static void networkPrefixes() {
        check(AltMatchCacheEngine.networkPrefix("93.184.216.34").equals("v4:93.184.216"), "IPv4 /24");
        check(AltMatchCacheEngine.networkPrefix("192.168.0.4").isEmpty() && AltMatchCacheEngine.networkPrefix("10.1.2.3").isEmpty(), "private IPv4 never links");
        check(AltMatchCacheEngine.networkPrefix("2a02:810d:4b3f:e100::1").equals("v6:2a02:810d:4b3f:e100"), "IPv6 /64 compressed");
        check(AltMatchCacheEngine.networkPrefix("2a02:0810:004b:0000:1:2:3:4").equals("v6:2a02:810:4b:0"), "IPv6 /64 leading zeros");
        check(AltMatchCacheEngine.networkPrefix("fe80::1").isEmpty() && AltMatchCacheEngine.networkPrefix("::1").isEmpty(), "link-local/loopback never links");
        check(AltMatchCacheEngine.networkPrefix("example.org").isEmpty(), "host names are never resolved");
        check(IdentitySignals.textureId("http://textures.minecraft.net/texture/ABCDEF0123456789abcdef0123456789abcdef0123456789abcdef0123456789").length() == 64, "texture id from URL");
        check(IdentitySignals.textureId("http://example.org/x.png").isEmpty(), "foreign URLs ignored");
        UUID a = UUID.fromString("00000000-0000-0000-0000-000000000001"), b = UUID.fromString("00000000-0000-0000-0000-000000000002");
        check(IdentitySignals.pair(a, b).equals(IdentitySignals.pair(b, a)), "switch pairs are order independent");
    }

    static AltMatchCacheEngine.Profile profile(UUID id, String name, Map<String, Long> ips, AltMatchCacheEngine.Experimental extra) {
        return new AltMatchCacheEngine.Profile(id, name, 1L, ips, Map.of(), Map.of(), Map.of(), Set.of(), Set.of(), Set.of(), extra);
    }

    static AltMatchCacheEngine.Match only(AltMatchCacheEngine.Result result, UUID id) {
        List<AltMatchCacheEngine.Match> list = result.matches.getOrDefault(id, List.of());
        return list.isEmpty() ? null : list.get(0);
    }

    static void engine() {
        long now = System.currentTimeMillis();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID(), d = UUID.randomUUID();
        // rare skin + two account switches: a hint with evidence, never safe for cascade
        var skinA = new AltMatchCacheEngine.Experimental(Map.of("tex1", now), Map.of(), Map.of(), Map.of(b.toString(), 2));
        var skinB = new AltMatchCacheEngine.Experimental(Map.of("tex1", now), Map.of(), Map.of(), Map.of(a.toString(), 2));
        // only the same IPv4 /24: never a relation on its own
        var netC = new AltMatchCacheEngine.Experimental(Map.of(), Map.of("v4:93.184.216", now), Map.of(), Map.of());
        var netD = new AltMatchCacheEngine.Experimental(Map.of(), Map.of("v4:93.184.216", now), Map.of(), Map.of());
        var result = AltMatchCacheEngine.build(List.of(profile(a, "A", Map.of(), skinA), profile(b, "B", Map.of(), skinB), profile(c, "C", Map.of(), netC), profile(d, "D", Map.of(), netD)), 1, 100, 70, now);
        var match = only(result, a);
        check(match != null && match.targetUuid.equals(b), "skin + switch pair is reported");
        check(match.score == 50, "30 (skin) + 20 (2 switches) = 50, got " + match.score);
        check(match.evidence.stream().anyMatch(e -> e.contains("Skin")) && match.evidence.stream().anyMatch(e -> e.contains("Kontowechsel")), "evidence names the signals");
        check(!result.strongComponentRoots.get(a).equals(result.strongComponentRoots.get(b)), "experimental hints never join the cascade graph");
        check(only(result, c) == null, "IPv4 /24 alone is no relation");
        // IPv6 /64 is a household line: a weak hint of its own
        var v6a = new AltMatchCacheEngine.Experimental(Map.of(), Map.of("v6:2a02:810d:4b3f:e100", now), Map.of(), Map.of());
        var r6 = AltMatchCacheEngine.build(List.of(profile(a, "A", Map.of(), v6a), profile(b, "B", Map.of(), v6a)), 1, 100, 70, now);
        check(only(r6, a) != null && only(r6, a).score == 20, "IPv6 /64 hint");
        // old constructor (without experimental data) still works – existing callers and data stay valid
        var legacy = new AltMatchCacheEngine.Profile(c, "C", 1L, Map.of("1.2.3.4", now), Map.of(), Map.of(), Map.of(), Set.of(), Set.of(), Set.of());
        check(legacy.skinHistory.isEmpty() && legacy.switchPartners.isEmpty(), "legacy profiles have no experimental data");
    }

    static void reflection() throws Exception {
        FakeLand land = new FakeLand();
        LandsAdmin.typed(land, "delete", new String[]{"FakeLandPlayer"}, (Object) null);
        check(land.deleted.equals("landplayer"), "typed() picks the overload by parameter type even for null");
        check(Boolean.TRUE.equals(LandsAdmin.call(land, "setBalance", 12.5D)), "boxed double reaches primitive parameter");
        check(Integer.valueOf(7).equals(LandsAdmin.optional(land, "getSize")), "optional call");
        check(LandsAdmin.optional(land, "doesNotExist") == null, "unknown methods only hide a field");
        check(LandsAdmin.has(land, "delete", 1) && !LandsAdmin.has(land, "delete", 0), "has() counts parameters");
        try { LandsAdmin.call(land, "boom"); check(false, "exception expected"); }
        catch (IllegalStateException expected) { check(expected.getMessage().equals("Lands sagt nein"), "Lands' own message is shown, not the reflection wrapper"); }
        check(LandsAdmin.clean(land.getName()).equals("Robinia"), "color codes and MiniMessage tags are removed");
    }

    static void texts() {
        check(LandsFlagTitles.title("block_break").equals("Abbauen"), "German flag titles");
        check(LandsFlagTitles.title("PLAYER_TRUST").equals("Spieler hinzufügen"), "upper-case names");
        check(LandsFlagTitles.title("some_new_flag").equals("Some new flag"), "unknown flags stay readable");
    }

    static void wiring() throws Exception {
        String api = read("src/main/java/eu/simplexsmp/adminsuite/AppApiManager.java");
        String admin = read("src/main/java/eu/simplexsmp/adminsuite/LandsAdmin.java");
        String index = read("src/main/resources/web/index.html"), sw = read("src/main/resources/web/sw.js");
        String extras = read("src/main/resources/web/extras.js"), pages = read("src/main/resources/web/pages.js"), lands = read("src/main/resources/web/lands.js");
        String config = read("src/main/resources/config.yml"), plugin = read("src/main/resources/plugin.yml");
        check(api.contains("LandsApi.route(plugin,this,hub.landsAdmin"), "routes registered");
        check(api.contains("LandsAdmin.FEATURE,IdentitySignals.FEATURE"), "features announced in /v1/health");
        check(api.contains("{LandsAdmin.MANAGE,\"Länder verwalten\"") && api.contains("{LandsAdmin.DELETE,\"Länder löschen\""), "rights in the catalog");
        check(api.contains("\"action.lands.access\",LandsAdmin.MANAGE,"), "admin template may manage but not delete");
        check(admin.contains("Die Besitzer-Rolle gibt es nur über") && admin.contains("Der Besitzer kann nicht entfernt werden"), "owner protection");
        check(admin.contains("Zur Bestätigung den Landnamen exakt eingeben"), "delete needs the land name");
        check(index.contains("/assets/lands.js") && sw.contains("/assets/lands.js"), "web loads and caches lands.js");
        check(!extras.contains("P.lands = async"), "old Lands page removed (one entry)");
        check(lands.contains("P.lands = async") && lands.contains("P.land = async") && lands.contains("P[\"land-area\"]") && lands.contains("S.landsCard = function"), "new web pages");
        check(pages.contains("S.landsCard(p.id)"), "lands card in the web profile");
        check(config.contains("experimental:") && config.contains("max-shared-accounts: 3"), "experimental signals configurable");
        check(plugin.contains("version: 10.24.0"), "version 10.24.0");
    }

    static String read(String path) throws Exception { return Files.readString(Path.of(path), StandardCharsets.UTF_8); }
}
