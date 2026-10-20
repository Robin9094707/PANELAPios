package eu.simplexsmp.adminsuite;

import java.net.URI;
import java.util.List;
import java.util.Map;

/** Shared, side-effect-free boundaries for the two chat formats. */
final class RelayText {
    private RelayText() { }

    static String limit(String value, int maximum) {
        if (value == null) return "";
        if (value.length() <= maximum) return value;
        int end = maximum;
        if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end);
    }

    static String plain(String value, int maximum) {
        if (value == null) return "";
        return limit(value.replaceAll("(?i)[§&][0-9a-fk-orx]", "")
                .replaceAll("[\\p{Cc}\\p{Cf}]", " ").replaceAll("\\s+", " ").trim(), maximum);
    }

    static String discord(String value, int maximum) {
        String text = plain(value, maximum).replace("\\", "\\\\");
        for (String token : List.of("*", "_", "~", "`", "|", ">", "[", "]")) text = text.replace(token, "\\" + token);
        return limit(text.replace("@", "@\u200b"), maximum);
    }

    static String username(String value) {
        String name = plain(value, 70).replace("@", "");
        // Discord reserves these substrings even in webhook display names.
        name = name.replaceAll("(?i)discord", "Disc\u200bord").replaceAll("(?i)clyde", "Cly\u200bde");
        return name.isBlank() ? "Minecraft player" : name;
    }

    static String httpsImage(String value) {
        if (value == null || value.length() > 2048) return "";
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getFragment() != null) return "";
            return uri.toASCIIString();
        } catch (IllegalArgumentException ignored) { return ""; }
    }

    static Map<?, ?> object(Object value) { return value instanceof Map<?, ?> map ? map : Map.of(); }
    static String string(Object value) { return value instanceof String text ? text : ""; }

    static String fromDiscord(Map<String, Object> message, boolean attachments) {
        String content = string(message.get("content"));
        Object mentions = message.get("mentions");
        if (mentions instanceof List<?> list) for (Object item : list) {
            Map<?, ?> user = object(item);
            String id = string(user.get("id"));
            String name = string(object(user.get("member")).get("nick"));
            if (name.isBlank()) name = string(user.get("global_name"));
            if (name.isBlank()) name = string(user.get("username"));
            if (!id.matches("[0-9]+")) continue;
            name = plain(name, 64);
            if (name.isBlank()) name = "user";
            content = content.replace("<@" + id + ">", "@" + name).replace("<@!" + id + ">", "@" + name);
        }
        content = content.replaceAll("<@&[0-9]+>", "@role")
                .replaceAll("<@!?[0-9]+>", "@user").replaceAll("<#[0-9]+>", "#channel")
                .replaceAll("<a?:([A-Za-z0-9_]+):[0-9]+>", ":$1:")
                .replaceAll("<t:[0-9]+(?::[A-Za-z])?>", "[timestamp]");
        if (attachments && message.get("attachments") instanceof List<?> list) {
            int count = 0;
            for (Object item : list) {
                if (count++ >= 3) break;
                String name = plain(string(object(item).get("filename")), 80);
                content += " [Attachment: " + (name.isBlank() ? "file" : name) + "]";
            }
        }
        return plain(content, 1000);
    }
}
