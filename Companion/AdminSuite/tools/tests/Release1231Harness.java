package eu.simplexsmp.adminsuite;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** 10.23.1: web texts are never cut off, the player profile has no duplicate entries, one /profil entry for chat break. */
public final class Release1231Harness {
    static int checks;
    static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }

    public static void main(String[] args) throws Exception {
        String css = read("src/main/resources/web/app.css"), pages = read("src/main/resources/web/pages.js");
        String java = read("src/main/java/eu/simplexsmp/adminsuite/PlayerCommunityManager.java");
        int meta = css.indexOf(".list .item .meta b {");
        check(meta > 0 && !css.substring(meta, css.indexOf('}', meta)).contains("nowrap"), "list titles wrap");
        int small = css.indexOf(".list .item .meta small {");
        check(small > 0 && !css.substring(small, css.indexOf('}', small)).contains("ellipsis"), "list details are not cut off");
        check(pages.contains("S.has(\"profile-v4\") ? null : actionMenu(p)"), "no second actions menu next to the tool grid");
        check(!pages.contains("glassTool(\"Bann-Zentrale\""), "Bann-Zentrale only from the ban status card");
        check(pages.contains("a.group || \"Spieleraktion\""), "tiles show a readable hint instead of the technical id");
        check(java.split("chatSilence.open\\(p", -1).length - 1 == 1, "exactly one chat break entry in /profil");
        System.out.println("PASS " + checks + " 10.23.1 menu and text checks");
    }

    static String read(String path) throws Exception { return Files.readString(Path.of(path), StandardCharsets.UTF_8); }
}
