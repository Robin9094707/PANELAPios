package eu.simplexsmp.adminsuite;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;

/** Read existing per-player histories without migrating or duplicating the profile data. */
final class PlayerChatTimeline {
    static final String PRIVATE_PERMISSION = "data.player-whispers";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd.MM.uuuu HH:mm:ss", Locale.GERMANY);
    private static final Pattern WHISPER = Pattern.compile("^(OUT →|IN ←) (.*?) \\[([^]]+)]: (.*)$", Pattern.DOTALL);

    private record Line(long at, String kind, String from, String to, String text) {}

    static List<Map<String, Object>> rows(String player, List<String> publicChat, List<String> privateChat) {
        List<Line> entries = new ArrayList<>();
        synchronized (publicChat) {
            for (String raw : publicChat) add(entries, raw, player, false);
        }
        synchronized (privateChat) {
            for (String raw : privateChat) add(entries, raw, player, true);
        }
        Collections.reverse(entries);
        entries.sort(Comparator.comparingLong(Line::at).reversed());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < Math.min(100, entries.size()); i++) {
            Line line = entries.get(i);
            rows.add(SimplexAdminSuite.appMap("id", i, "at", line.at, "kind", line.kind,
                "from", line.from, "to", line.to, "text", line.text));
        }
        return rows;
    }

    private static void add(List<Line> entries, String raw, String player, boolean whisper) {
        long at = 0;
        String text = raw;
        if (raw.length() > 20 && raw.charAt(19) == ' ') {
            try {
                at = LocalDateTime.parse(raw.substring(0, 19), STAMP).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                text = raw.substring(20);
            } catch (DateTimeParseException ignored) { /* Legacy row without a readable date. */ }
        }
        if (whisper) {
            var match = WHISPER.matcher(text);
            if (match.matches()) {
                boolean outgoing = match.group(1).startsWith("OUT");
                String other = match.group(2);
                entries.add(new Line(at, "whisper", outgoing ? player : other, outgoing ? other : player, match.group(4)));
                return;
            }
        } else {
            int separator = text.indexOf(": ");
            if (separator > 0) {
                entries.add(new Line(at, "public", text.substring(0, separator), "", text.substring(separator + 2)));
                return;
            }
        }
        entries.add(new Line(at, whisper ? "whisper" : "public", player, "", text));
    }
}
