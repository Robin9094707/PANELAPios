package eu.simplexsmp.adminsuite;

import java.nio.file.*;
import java.util.*;

/**
 * 10.20.0: LiteBans is replaced by the Suite's own ban system. Checks the case grouping of the migration
 * (one LiteBans command = main account + linked accounts), the shared-IP protection (82.165.45.159 is never
 * taken over), H2 format detection, the command takeover rules and the web assets of the Bann-Zentrale.
 */
public final class Release1200Harness {
    static int checks;
    static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }

    static LiteBansMigration.Row ban(long id, String uuid, String ip, String reason, long time, long until, boolean ipban, boolean active) {
        return new LiteBansMigration.Row("ban", id, uuid, ip, reason, "robin", time, until, ipban, false, active, "", "", 0L);
    }

    public static void main(String[] args) throws Exception {
        long now = 1_794_787_200_000L; // 2026-11-15
        String shared = "82.165.45.159";
        List<LiteBansMigration.Row> rows = List.of(
                // One command: main on its own IP, a linked account that only shared the proxy IP.
                ban(290, "00000000-0000-0000-0009-01f5a9b1fdd1", "108.65.209.84", "bad language", 1794759366474L, 1794932166474L, true, true),
                ban(341, "00000000-0000-0000-0009-01fce359b437", shared, "bad language", 1794759366474L, 1794932166474L, true, true),
                // Classic alt pair, permanent.
                ban(14, "00000000-0000-0000-0009-01fec934c091", "107.211.17.41", "BOT", 1767870539507L, 0L, true, true),
                ban(15, "00000000-0000-0000-0009-01faec39aac4", "107.211.17.41", "BOT", 1767870539507L, 0L, false, true),
                // Someone banned only through the shared IP.
                ban(400, "11111111-2222-3333-4444-555555555555", shared, "spam", 1794000000000L, 0L, true, true),
                // Inactive and expired rows never become cases.
                ban(500, "aaaaaaaa-2222-3333-4444-555555555555", "1.2.3.4", "old", 1700000000000L, 0L, true, false),
                ban(501, "bbbbbbbb-2222-3333-4444-555555555555", "1.2.3.5", "temp", 1700000000000L, 1700000100000L, true, true));
        List<LiteBansMigration.Case> cases = LiteBansMigration.cases(rows, now);
        check(cases.size() == 3, "three active cases expected, got " + cases.size());
        java.util.function.Predicate<String> excluded = ip -> ip.equals(shared);

        LiteBansMigration.Case language = cases.stream().filter(c -> c.main.id() == 290).findFirst().orElseThrow();
        check(language.alts.size() == 1 && language.alts.get(0).id() == 341, "same command groups main and linked account");
        check(!LiteBansMigration.excludedCase(language, excluded), "main on its own IP is not excluded");
        check(LiteBansMigration.importedAlts(language, excluded).isEmpty(), "account linked only via the shared IP is never imported");
        check(LiteBansMigration.recommended(language, excluded).equals("account"), "own-IP ban is recommended as account ban");

        LiteBansMigration.Case bot = cases.stream().filter(c -> c.main.id() == 14).findFirst().orElseThrow();
        check(bot.alts.size() == 1 && LiteBansMigration.importedAlts(bot, excluded).size() == 1, "classic alt is kept");
        check(bot.main.permanent(), "until 0 = permanent");

        LiteBansMigration.Case spam = cases.stream().filter(c -> c.main.id() == 400).findFirst().orElseThrow();
        check(LiteBansMigration.excludedCase(spam, excluded), "ban on the shared IP is flagged");
        check(LiteBansMigration.recommended(spam, excluded).equals("skip"), "shared-IP ban is not taken over by default");
        check(LiteBansMigration.allowed(spam).contains("account") && LiteBansMigration.allowed(spam).contains("skip"), "admin may still choose");

        LiteBansMigration.Row mute = new LiteBansMigration.Row("mute", 52, "00000000-0000-0000-0009-01f5a9b1fdd1", shared, "bad language", "Console", now - 1000, 0L, true, false, true, "", "", 0L);
        LiteBansMigration.Case muteCase = LiteBansMigration.cases(List.of(mute), now).get(0);
        check(LiteBansMigration.recommended(muteCase, excluded).equals("skip"), "IP mute on the shared IP is not taken over");
        check(LiteBansMigration.allowed(muteCase).equals(LiteBansMigration.MUTE_DECISIONS), "mute choices");

        // H2 header: format 1 = H2 1.4.x (LiteBans default), format 2/3 = H2 2.x.
        Path dir = Files.createTempDirectory("lb");
        Path v1 = dir.resolve("a.mv.db"); Files.writeString(v1, "H:2,block:f,blockSize:1000,chunk:1ac80,created:19b1c6ec232,format:1,version:1ac80,fletcher:bf56cfd\n");
        Path v2 = dir.resolve("b.mv.db"); Files.writeString(v2, "H:2,block:2,blockSize:1000,chunk:1,created:18,format:3,version:1\n");
        check(LiteBansMigration.h2Format(v1) == 1 && LiteBansMigration.h2Format(v2) == 3, "H2 format detection");
        check(LiteBansMigration.sha256("abc".getBytes()).equals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"), "sha256");
        check(LiteBansMigration.H2_V1.endsWith("h2-1.4.200.jar") && LiteBansMigration.H2_V1_SHA.length() == 64, "pinned H2 1.4 driver");

        // Snapshot rows survive a YAML round trip (restart) unchanged.
        org.bukkit.configuration.file.YamlConfiguration y = new org.bukkit.configuration.file.YamlConfiguration();
        y.set("bans", LiteBansMigration.encode(rows));
        org.bukkit.configuration.file.YamlConfiguration back = new org.bukkit.configuration.file.YamlConfiguration(); back.loadFromString(y.saveToString());
        List<LiteBansMigration.Row> decoded = LiteBansMigration.decode(back.getList("bans"), "ban");
        check(decoded.equals(rows), "snapshot YAML round trip");
        check(!y.saveToString().contains("!!binary"), "snapshot stays readable text");

        // Command takeover never steals another plugin's command, only LiteBans/vanilla/free labels.
        check(BanCommands.replaceable(null), "free label");
        check(BanCommands.LABELS.get("ban").equals("ban") && BanCommands.LABELS.get("ipban").equals("ban") && BanCommands.LABELS.get("unban").equals("unban"), "label mapping");

        // Case IDs are short and readable.
        String id = SimplexAdminSuite.BanCenterCore.newCaseId(now);
        check(id.matches("B-[A-Z2-9]{6}"), "case id format: " + id);

        // Web assets: new page is loaded and cached; profile card is wired.
        String index = Files.readString(Path.of("src/main/resources/web/index.html"));
        String sw = Files.readString(Path.of("src/main/resources/web/sw.js"));
        String pages = Files.readString(Path.of("src/main/resources/web/pages.js"));
        String bans = Files.readString(Path.of("src/main/resources/web/bans.js"));
        check(index.contains("/assets/bans.js") && sw.contains("/assets/bans.js"), "bans.js served and cached");
        check(pages.contains("S.banStatusCard") && bans.contains("P[\"ban-center\"]") && bans.contains("P[\"ban-migration\"]"), "web pages wired");
        String yml = Files.readString(Path.of("src/main/resources/config.yml"));
        check(yml.contains("disable-litebans: true") && yml.contains("internal-alt-ban-login"), "config defaults");
        System.out.println("Release1200Harness OK (" + checks + " checks)");
    }
}
