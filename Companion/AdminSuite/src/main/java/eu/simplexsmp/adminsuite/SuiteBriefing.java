package eu.simplexsmp.adminsuite;

import java.util.*;

/**
 * 10.13.0: "What happened while you were away" for the app's start screen. Every section follows the
 * viewer's rights; a section the viewer may not see is left out instead of being shown empty.
 */
final class SuiteBriefing {
    private SuiteBriefing() { }

    /** Event types grouped for the summary tiles. */
    static String group(String type) {
        if (type.startsWith("ticket")) return "tickets";
        return switch (type) {
            case "report" -> "reports";
            case "ban", "ban-evasion", "vpn-kick" -> "security";
            case "crash", "disconnect-wave", "lag-spike", "overload", "emergency-restart" -> "server";
            case "first-join", "join" -> "joins";
            case "powerbot-case", "ai-chat-alert" -> "moderation";
            default -> "other";
        };
    }

    static Map<String, Object> summarizeEvents(List<Map<String, Object>> events, long since) {
        Map<String, Long> groups = new TreeMap<>();
        List<Map<String, Object>> highlights = new ArrayList<>();
        long critical = 0;
        for (var row : events) {
            if (AiAdmin.number(row.get("time")) <= since) continue;
            String type = AiAdmin.text(row, "type");
            groups.merge(group(type), 1L, Long::sum);
            boolean important = Boolean.TRUE.equals(row.get("critical"));
            if (important) critical++;
            if (highlights.size() < 8 && !type.equals("join"))
                highlights.add(SimplexAdminSuite.appMap("id", row.get("id"), "type", type, "group", group(type), "title", row.get("title"),
                    "at", row.get("time"), "critical", important));
        }
        return SimplexAdminSuite.appMap("groups", groups, "critical", critical, "highlights", highlights,
            "total", groups.values().stream().mapToLong(Long::longValue).sum());
    }

    static Map<String, Object> build(SimplexAdminSuite plugin, AppApiManager api, AppApiSecurity.Key key, long since) {
        var result = SimplexAdminSuite.appMap("since", since, "at", System.currentTimeMillis());
        if (api.allows(key, "data.players")) result.put("players", plugin.briefingPlayers(since));
        if (plugin.powerBot != null && api.allows(key, PowerBot.VIEW)) result.put("powerbot", plugin.powerBot.briefing(since));
        if (plugin.appEvents != null && api.allows(key, "data.events")) {
            var log = AppApiManager.object(plugin.appEvents.data("", 0));
            List<Map<String, Object>> rows = log.get("items") instanceof List<?> list ? list.stream().map(AppApiManager::object).toList() : List.of();
            result.put("events", summarizeEvents(rows, since));
        }
        return result;
    }
}
