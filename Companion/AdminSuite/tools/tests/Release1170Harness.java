package eu.simplexsmp.adminsuite;

import java.util.*;

/** 10.17.0: chat break parsing, mention rules and the visibility snapshot fix (no Bukkit server needed). */
public final class Release1170Harness {
    static int checks;
    static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }

    public static void main(String[] args) {
        check(ChatSilence.parseMinutes("30m") == 30, "30m");
        check(ChatSilence.parseMinutes("3h") == 180, "3h");
        check(ChatSilence.parseMinutes("3") == 180, "plain number = hours");
        check(ChatSilence.parseMinutes("24std") == 1440, "german hours");
        check(ChatSilence.parseMinutes("1d") == 1440 && ChatSilence.parseMinutes("2tage") == 2880, "days");
        check(ChatSilence.parseMinutes("dauerhaft") == 0 && ChatSilence.parseMinutes("permanent") == 0, "permanent");
        check(ChatSilence.parseMinutes("abc") == -1 && ChatSilence.parseMinutes("0h") == -1 && ChatSilence.parseMinutes("") == -1, "invalid durations");

        check(ChatSilence.fullMention("hey Steve123 komm mal", "Steve123"), "full mention");
        check(ChatSilence.fullMention("@steve123 ?", "Steve123"), "@ mention, case-insensitive");
        check(!ChatSilence.fullMention("Steve1234 hi", "Steve123"), "longer name is not a full mention");
        check(!ChatSilence.fullMention("Steve hi", "Steve123"), "partial is not full");

        check(ChatSilence.partialMention("wo ist alphacr?", "AlphacraftMC"), "six letters of the name");
        check(!ChatSilence.partialMention("alpha hat geschrieben", "AlphacraftMC"), "five letters are not enough");
        check(ChatSilence.partialMention("hi bob", "Bob"), "short names need the whole name");
        check(!ChatSilence.partialMention("hallo zusammen", "MissCaitlyn"), "unrelated text");
        check(ChatSilence.partialMention("caitlyn bist du da", "MissCaitlyn"), "suffix with seven letters");

        check(ChatSilence.normalize("§aHallo  §lWelt ").equals("hallo welt"), "normalize strips colours and whitespace");

        UUID target = UUID.randomUUID(), other = UUID.randomUUID();
        var snapshot = new SuitePacketGuard.Snapshot(Set.of(target, other), Set.of(7, 8), Set.of("steve", "alex"));
        var cleaned = SuitePacketGuard.forgotten(snapshot, target, "steve");
        check(!cleaned.uuids().contains(target) && cleaned.uuids().contains(other), "a joining player leaves every hidden set");
        check(!cleaned.names().contains("steve") && cleaned.names().contains("alex"), "names are forgotten too");

        var state = ChatSilence.State.defaults("Steve");
        check(!state.worthKeeping() && !state.active(System.currentTimeMillis()), "defaults are not stored");
        var muted = state.muted(System.currentTimeMillis() + 60_000, "self", System.currentTimeMillis(), "Steve");
        check(muted.active(System.currentTimeMillis()) && !muted.active(System.currentTimeMillis() + 120_000), "timed break expires");
        check(state.muted(0, "self", 1, "Steve").active(Long.MAX_VALUE - 1), "permanent break never expires");
        var options = muted.options(true, false).unmuted("self", 2, "Steve");
        check(options.whispers() && !options.chat() && options.worthKeeping(), "whisper preference survives unmuting");
        System.out.println("PASS " + checks + " 10.17.0 chat break and visibility checks");
    }
}
