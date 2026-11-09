package eu.simplexsmp.adminsuite;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 10.13.0: one chronological list of everything a player did, read from the existing profile histories
 * ("dd.MM.yyyy HH:mm:ss text"). Nothing is migrated; old rows without a date keep their position at the end.
 */
final class PlayerTimeline {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd.MM.uuuu HH:mm:ss", Locale.GERMANY);
    private static final Pattern WHISPER = Pattern.compile("^(OUT →|IN ←) (.*?) \\[([^]]+)]: (.*)$", Pattern.DOTALL);
    private static final Pattern FLAGGED_ACTION = Pattern.compile("(?i)^(PowerBot|KI-Chatmoderation|Blocked|Anti-spam|Verdächtiges)");
    private static final long MATCH_WINDOW = 10 * 60_000L;

    private record Row(long at, String category, String kind, String from, String to, String text, int order) {}

    static List<Map<String, Object>> rows(String player, List<String> chat, List<String> whispers, List<String> actions,
                                          List<String> combat, List<String> items, List<Map<String, Object>> powerBot, int limit) {
        List<Row> rows = new ArrayList<>();
        int order = 0;
        for (String raw : chat) rows.add(chatRow(raw, player, order++));
        for (String raw : whispers) rows.add(whisperRow(raw, player, order++));
        for (String raw : actions) rows.add(plain(raw, "action", order++));
        for (String raw : combat) rows.add(plain(raw, "combat", order++));
        for (String raw : items) rows.add(plain(raw, "item", order++));
        List<Map<String, Object>> output = new ArrayList<>();
        Set<String> usedFlags = new HashSet<>();
        rows.sort(Comparator.comparingLong(Row::at).reversed().thenComparing(Comparator.comparingInt(Row::order).reversed()));
        for (Row row : rows) {
            if (output.size() >= limit) break;
            var map = SimplexAdminSuite.appMap("id", row.category + ":" + row.order + ":" + row.at, "at", row.at, "category", row.category,
                "kind", row.kind, "from", row.from, "to", row.to, "text", row.text, "flagged", false, "flagReason", "", "case", "");
            if (row.category.equals("action") && FLAGGED_ACTION.matcher(row.text).find()) {
                map.put("flagged", true);
                map.put("flagReason", row.text.startsWith("PowerBot") ? "Von PowerBot behandelt" : "Vom Server blockiert");
            }
            if (row.category.equals("chat") || row.category.equals("whisper")) {
                for (var flag : powerBot) {
                    String id = String.valueOf(flag.get("id"));
                    String message = String.valueOf(flag.getOrDefault("message", ""));
                    long at = AiAdmin.number(flag.get("at"));
                    if (message.isBlank() || usedFlags.contains(id) || !same(message, row.text) || row.at > 0 && Math.abs(at - row.at) > MATCH_WINDOW) continue;
                    usedFlags.add(id);
                    map.put("flagged", true);
                    String reason = String.valueOf(flag.getOrDefault("reason", ""));
                    map.put("flagReason", reason.isBlank() ? String.valueOf(flag.getOrDefault("detail", "PowerBot-Hinweis")) : reason);
                    map.put("case", id);
                    break;
                }
            }
            output.add(map);
        }
        // PowerBot decisions without a delivered message (blocked chat, conflicts) appear as their own rows.
        for (var flag : powerBot) {
            String id = String.valueOf(flag.get("id"));
            if (usedFlags.contains(id)) continue;
            String reason = String.valueOf(flag.getOrDefault("reason", ""));
            String message = String.valueOf(flag.getOrDefault("message", ""));
            output.add(SimplexAdminSuite.appMap("id", "powerbot:" + id, "at", AiAdmin.number(flag.get("at")), "category", "powerbot",
                "kind", String.valueOf(flag.get("kind")), "from", "PowerBot", "to", "", "text", message.isBlank() ? String.valueOf(flag.getOrDefault("detail", "")) : message,
                "flagged", true, "flagReason", reason.isBlank() ? String.valueOf(flag.getOrDefault("detail", "")) : reason, "case", id));
        }
        output.sort(Comparator.comparingLong((Map<String, Object> row) -> AiAdmin.number(row.get("at"))).reversed());
        return output.size() > limit ? new ArrayList<>(output.subList(0, limit)) : output;
    }

    static boolean same(String a, String b) {
        return normalize(a).equals(normalize(b));
    }

    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}]+", " ").strip();
    }

    static long time(String raw) {
        if (raw.length() > 20 && raw.charAt(19) == ' ') {
            try { return LocalDateTime.parse(raw.substring(0, 19), STAMP).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(); }
            catch (DateTimeParseException ignored) { /* Legacy row without a readable date. */ }
        }
        return 0;
    }

    private static String body(String raw) { return time(raw) > 0 ? raw.substring(20) : raw; }

    private static Row chatRow(String raw, String player, int order) {
        String text = body(raw);
        int separator = text.indexOf(": ");
        if (separator > 0) return new Row(time(raw), "chat", "public", text.substring(0, separator), "", text.substring(separator + 2), order);
        return new Row(time(raw), "chat", "public", player, "", text, order);
    }

    private static Row whisperRow(String raw, String player, int order) {
        String text = body(raw);
        var match = WHISPER.matcher(text);
        if (match.matches()) {
            boolean outgoing = match.group(1).startsWith("OUT");
            return new Row(time(raw), "whisper", outgoing ? "out" : "in", outgoing ? player : match.group(2), outgoing ? match.group(2) : player, match.group(4), order);
        }
        return new Row(time(raw), "whisper", "out", player, "", text, order);
    }

    private static Row plain(String raw, String category, int order) {
        String text = body(raw);
        String kind = category;
        if (category.equals("item")) kind = text.startsWith("Abgelegt") ? "drop" : text.startsWith("Aufgehoben") ? "pickup" : text.startsWith("Weitergegeben") ? "given" : text.startsWith("Gestorben") ? "death" : "item";
        if (category.equals("action")) kind = text.startsWith("Befehl genutzt") || text.startsWith("Used /") ? "command"
            : text.startsWith("Server betreten") || text.startsWith("Server verlassen") ? "session" : "action";
        return new Row(time(raw), category, kind, "", "", text, order);
    }
}
