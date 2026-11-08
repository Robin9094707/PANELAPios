package eu.simplexsmp.adminsuite;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Server-side, literal keyword rules shared by all chat entry points. */
final class PowerBotKeywords {
    private static final Pattern SPACE = Pattern.compile("(?U)\\s+");
    private static final Pattern WORD_CHAR = Pattern.compile("[\\p{L}\\p{M}\\p{N}_]");
    private static final int MAX_RULES = 200;
    private static final int MAX_INSTRUCTIONS = 24_000;
    private static final String SMP_RULE = "Prüfe, ob die Nachricht ausdrücklich für einen fremden Minecraft-Server wirbt "
        + "oder zum Beitritt bzw. zur privaten Kontaktaufnahme dafür einlädt. Das kann auch ohne Serveradresse "
        + "Eigenwerbung sein. Lass neutrale SMP-Erwähnungen, normale Gespräche über Simplex und Fragen zu SMPs durch. "
        + "Warne bei mehrdeutiger Werbung und blockiere eindeutige fremde Eigenwerbung.";

    record Rule(String keyword, String instructions, Pattern trigger) {
        Map<String, String> row() { return Map.of("id", key(keyword), "keyword", keyword, "instructions", instructions); }
    }

    private final Path file;
    private volatile List<Rule> rules;

    PowerBotKeywords(Path file) throws IOException {
        this.file = file;
        if (!Files.exists(file)) {
            rules = List.of(rule("SMP", SMP_RULE));
            return;
        }
        Properties stored = new Properties();
        try (var reader = Files.newBufferedReader(file)) { stored.load(reader); }
        var loaded = new TreeMap<String, Rule>();
        for (String keyword : stored.stringPropertyNames()) {
            Rule rule = rule(keyword, stored.getProperty(keyword));
            if (loaded.putIfAbsent(key(keyword), rule) != null) throw new IOException("Doppeltes PowerBot-Stichwort: " + keyword);
        }
        validateLimits(loaded.values());
        rules = List.copyOf(loaded.values());
    }

    List<Map<String, String>> rows() { return rules.stream().map(Rule::row).toList(); }

    List<Map<String, String>> matches(String message) {
        if (rules.isEmpty() || message.isBlank()) return List.of();
        String normalized = key(message);
        return rules.stream().filter(rule -> rule.trigger.matcher(normalized).find())
            .map(rule -> Map.of("keyword", rule.keyword, "instructions", rule.instructions)).toList();
    }

    synchronized String change(String operation, String id, String keyword, String instructions) throws IOException {
        var updated = new TreeMap<String, Rule>();
        for (Rule rule : rules) updated.put(key(rule.keyword), rule);
        if (!id.isEmpty() && !updated.containsKey(id)) throw new AppApiManager.ApiError(404, "Stichwort existiert nicht mehr.");
        if (operation.equals("delete")) {
            if (id.isEmpty()) throw new IllegalArgumentException("Stichwort zum Löschen fehlt.");
            keyword = updated.remove(id).keyword;
        } else if (operation.equals("save")) {
            Rule rule = rule(keyword, instructions);
            String target = key(rule.keyword);
            if (!target.equals(id) && updated.containsKey(target))
                throw new AppApiManager.ApiError(409, "Dieses Stichwort ist bereits vorhanden.");
            if (!id.isEmpty()) updated.remove(id);
            updated.put(target, rule);
            keyword = rule.keyword;
        } else throw new IllegalArgumentException("Ungültige Stichwort-Aktion.");
        validateLimits(updated.values());
        Properties stored = new Properties();
        for (Rule rule : updated.values()) stored.setProperty(rule.keyword, rule.instructions);
        AtomicState.save(file, stored, "PowerBot keyword rules. An empty list disables all keyword checks.");
        rules = List.copyOf(updated.values());
        return keyword;
    }

    private static void validateLimits(Collection<Rule> values) {
        if (values.size() > MAX_RULES) throw new IllegalArgumentException("Maximal " + MAX_RULES + " Stichwörter.");
        if (values.stream().mapToInt(rule -> rule.instructions.length()).sum() > MAX_INSTRUCTIONS)
            throw new IllegalArgumentException("Die Stichwort-Regeln dürfen insgesamt maximal 24.000 Zeichen enthalten.");
    }

    private static Rule rule(String keyword, String instructions) {
        String word = SPACE.matcher(keyword).replaceAll(" ").strip();
        if (word.length() > 64 || !WORD_CHAR.matcher(word).find() || word.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Stichwort oder Phrase mit 1 bis 64 Zeichen eingeben.");
        if (instructions.length() > 500) throw new IllegalArgumentException("Eine Stichwort-Regel darf maximal 500 Zeichen lang sein.");
        String expression = "(?<![\\p{L}\\p{M}\\p{N}_])" + Pattern.quote(key(word)) + "(?![\\p{L}\\p{M}\\p{N}_])";
        return new Rule(word, PowerBot.clean(instructions, 500), Pattern.compile(expression));
    }

    private static String key(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return SPACE.matcher(normalized).replaceAll(" ").strip();
    }
}
