package eu.simplexsmp.adminsuite;

import java.io.BufferedWriter;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class VpnDetectionLog {
    static final class Detection {
        private final long timestamp;
        private final String playerName;
        private final UUID uuid;
        private final String ip;
        private final boolean bedrock;
        private final boolean blocked;
        private final String decision;
        private final String country;
        private final int signals;
        private final int riskScore;
        private final String reason;
        private final String providers;

        Detection(long timestamp, String playerName, UUID uuid, String ip, boolean bedrock, boolean blocked,
                  String decision, String country, int signals, int riskScore, String reason, String providers) {
            this.timestamp = timestamp;
            this.playerName = playerName == null ? "unknown" : playerName;
            this.uuid = uuid;
            this.ip = ip == null ? "unknown" : ip;
            this.bedrock = bedrock;
            this.blocked = blocked;
            this.decision = decision == null ? "UNKNOWN" : decision;
            this.country = country == null ? "" : country;
            this.signals = Math.max(0, signals);
            this.riskScore = Math.max(0, Math.min(100, riskScore));
            this.reason = reason == null ? "" : reason;
            this.providers = providers == null ? "none" : providers;
        }

        long timestamp() { return timestamp; }
        String playerName() { return playerName; }
        UUID uuid() { return uuid; }
        String ip() { return ip; }
        boolean bedrock() { return bedrock; }
        boolean blocked() { return blocked; }
        String decision() { return decision; }
        String country() { return country; }
        int signals() { return signals; }
        int riskScore() { return riskScore; }
        String reason() { return reason; }
        String providers() { return providers; }

        String compact() {
            return Instant.ofEpochMilli(timestamp) + " | " + playerName + " | " + ip + " | "
                    + (blocked ? "BLOCKIERT" : decisionLabel(decision)) + " | " + (country.isBlank() ? "--" : country)
                    + " | Signale=" + signals + " | Risiko=" + riskScore;
        }

        private static String decisionLabel(String decision) {
            if (decision == null) return "UNBEKANNT";
            return switch (decision.toUpperCase(Locale.ROOT)) {
                case "SUSPICIOUS_ALLOWED" -> "AUFFÄLLIG ZUGELASSEN";
                case "ALLOWED" -> "ZUGELASSEN";
                case "WHITELIST" -> "WHITELIST-BYPASS";
                case "COOLDOWN" -> "COOLDOWN";
                default -> decision;
            };
        }
    }

    private static final Pattern STRING_FIELD = Pattern.compile("\\\"%s\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"");
    private static final Pattern NUMBER_FIELD = Pattern.compile("\\\"%s\\\"\\s*:\\s*(-?\\d+)");
    private static final Pattern BOOLEAN_FIELD = Pattern.compile("\\\"%s\\\"\\s*:\\s*(true|false)", Pattern.CASE_INSENSITIVE);
    private final File file;
    private volatile int maximumEntries;
    private int appendedSinceRotationCheck;

    VpnDetectionLog(File file, int maximumEntries) {
        this.file = file;
        this.maximumEntries = maximumEntries;
    }

    synchronized void setMaximumEntries(int maximumEntries) {
        this.maximumEntries = Math.max(100, maximumEntries);
        try { if (file.isFile()) rotateIfNeeded(); }
        catch (Exception ignored) { }
    }

    synchronized void append(Detection detection) {
        if (detection == null) return;
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (BufferedWriter writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8,
                    Files.exists(file.toPath()) ? java.nio.file.StandardOpenOption.APPEND : java.nio.file.StandardOpenOption.CREATE)) {
                writer.write(toJson(detection));
                writer.write('\n');
            }
            appendedSinceRotationCheck++;
            if (appendedSinceRotationCheck >= 100) {
                rotateIfNeeded();
                appendedSinceRotationCheck = 0;
            }
        } catch (Exception ignored) { }
    }

    synchronized List<Detection> recent(String query, int limit) {
        if (!file.isFile()) return List.of();
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        int maximum = Math.max(1, Math.min(200, limit));
        List<Detection> matches = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            for (int index = lines.size() - 1; index >= 0 && matches.size() < maximum; index--) {
                String line = lines.get(index);
                if (!needle.isBlank() && !line.toLowerCase(Locale.ROOT).contains(needle)) continue;
                Detection parsed = parse(line);
                if (parsed != null) matches.add(parsed);
            }
        } catch (Exception ignored) { }
        return Collections.unmodifiableList(matches);
    }

    synchronized int size() {
        if (!file.isFile()) return 0;
        try (java.util.stream.Stream<String> lines = Files.lines(file.toPath(), StandardCharsets.UTF_8)) {
            return (int) Math.min(Integer.MAX_VALUE, lines.count());
        } catch (Exception ignored) { return 0; }
    }

    private void rotateIfNeeded() throws Exception {
        List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        if (lines.size() <= maximumEntries) return;
        List<String> keep = lines.subList(Math.max(0, lines.size() - maximumEntries), lines.size());
        File temporary = new File(file.getParentFile(), file.getName() + ".tmp");
        Files.write(temporary.toPath(), keep, StandardCharsets.UTF_8);
        try { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (Exception atomicUnavailable) { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING); }
    }

    private static String toJson(Detection detection) {
        return "{"
                + "\"time\":" + detection.timestamp() + ","
                + "\"timeIso\":\"" + json(Instant.ofEpochMilli(detection.timestamp()).toString()) + "\","
                + "\"player\":\"" + json(detection.playerName()) + "\","
                + "\"uuid\":\"" + json(detection.uuid() == null ? "" : detection.uuid().toString()) + "\","
                + "\"ip\":\"" + json(detection.ip()) + "\","
                + "\"platform\":\"" + (detection.bedrock() ? "Bedrock" : "Java") + "\","
                + "\"blocked\":" + detection.blocked() + ","
                + "\"decision\":\"" + json(detection.decision()) + "\","
                + "\"country\":\"" + json(detection.country()) + "\","
                + "\"signals\":" + detection.signals() + ","
                + "\"riskScore\":" + detection.riskScore() + ","
                + "\"reason\":\"" + json(detection.reason()) + "\","
                + "\"providers\":\"" + json(detection.providers()) + "\""
                + "}";
    }

    private static Detection parse(String json) {
        if (json == null || json.isBlank()) return null;
        long time = number(json, "time", 0L);
        if (time <= 0L) return null;
        UUID uuid = null;
        try { uuid = UUID.fromString(string(json, "uuid")); } catch (Exception ignored) { }
        return new Detection(time, string(json, "player"), uuid, string(json, "ip"),
                string(json, "platform").equalsIgnoreCase("Bedrock"), bool(json, "blocked"),
                string(json, "decision"), string(json, "country"), (int) number(json, "signals", 0),
                (int) number(json, "riskScore", 0), string(json, "reason"), string(json, "providers"));
    }

    private static String string(String json, String key) {
        Matcher matcher = Pattern.compile(String.format(STRING_FIELD.pattern(), Pattern.quote(key))).matcher(json);
        return matcher.find() ? unjson(matcher.group(1)) : "";
    }

    private static long number(String json, String key, long fallback) {
        Matcher matcher = Pattern.compile(String.format(NUMBER_FIELD.pattern(), Pattern.quote(key))).matcher(json);
        if (!matcher.find()) return fallback;
        try { return Long.parseLong(matcher.group(1)); } catch (Exception ignored) { return fallback; }
    }

    private static boolean bool(String json, String key) {
        Matcher matcher = Pattern.compile(String.format(BOOLEAN_FIELD.pattern(), Pattern.quote(key)), Pattern.CASE_INSENSITIVE).matcher(json);
        return matcher.find() && Boolean.parseBoolean(matcher.group(1));
    }

    private static String json(String value) {
        return (value == null ? "" : value).replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "");
    }

    private static String unjson(String value) {
        if (value == null || value.isBlank()) return value == null ? "" : value;
        StringBuilder result = new StringBuilder();
        boolean escaped = false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (escaped) {
                result.append(current == 'n' ? '\n' : current);
                escaped = false;
            } else if (current == '\\') escaped = true;
            else result.append(current);
        }
        if (escaped) result.append('\\');
        return result.toString();
    }
}
