package eu.simplexsmp.adminsuite;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 10.16.0: fast local signal for hostile, directed chat ("Pöbeln"). Never punishes on its own – a high score only makes
 * PowerBot look at the message and the surrounding chat with the AI right away instead of waiting for the next
 * regular analysis. Pure and unit-tested.
 */
final class PowerBotSignals {
    private PowerBotSignals() {}

    static final int HOSTILE = 45;

    /** Stem → weight. Stems are matched on normalised words (lower case, umlauts folded, repeated letters collapsed). */
    private static final Map<String, Integer> STEMS = new LinkedHashMap<>();
    static {
        String[][] rows = {
            {"hurensohn", "70"}, {"hure", "45"}, {"wichser", "60"}, {"wixer", "60"}, {"wixxer", "60"}, {"fotze", "70"}, {"missgeburt", "70"},
            {"spast", "50"}, {"spasti", "50"}, {"behindert", "40"}, {"mongo", "50"}, {"arschloch", "50"}, {"arschl", "45"}, {"bastard", "50"},
            {"schlampe", "55"}, {"penner", "35"}, {"vollidiot", "40"}, {"idiot", "30"}, {"depp", "25"}, {"trottel", "25"}, {"opfer", "30"},
            {"lappen", "25"}, {"kacke", "15"}, {"scheis", "15"}, {"wixx", "50"}, {"pisser", "45"}, {"drecks", "35"}, {"huso", "60"}, {"hs", "0"},
            {"fick", "45"}, {"ficken", "50"}, {"fickdich", "60"}, {"verpiss", "50"}, {"kys", "80"}, {"stfu", "40"}, {"retard", "50"},
            {"fuck", "35"}, {"fucker", "50"}, {"motherfucker", "60"}, {"bitch", "45"}, {"cunt", "70"}, {"whore", "55"}, {"slut", "55"},
            {"asshole", "50"}, {"dumbass", "40"}, {"loser", "25"}, {"trash", "20"}, {"dogwater", "20"}, {"clown", "15"}, {"noob", "8"},
            {"bot", "5"}, {"kiddie", "20"}, {"kiddi", "20"}, {"schwein", "30"}};
        for (String[] row : rows) STEMS.put(row[0], Integer.parseInt(row[1]));
    }
    private static final List<Pattern> PHRASES = List.of(
        Pattern.compile("halt (?:die|dein|deine) (?:fresse|maul|klappe|schnauze)"), Pattern.compile("(?:fresse|maul|schnauze) halten"),
        Pattern.compile("deine (?:mutter|mudda|mum|mom)"), Pattern.compile("your (?:mom|mum|mother)"), Pattern.compile("kill (?:yo)?urself"),
        Pattern.compile("geh (?:sterben|dich erh[aä]ngen)"), Pattern.compile("shut (?:the fuck )?up"), Pattern.compile("du bist (?:so )?(?:dumm|behindert|scheisse|schlecht|nutzlos|ein witz)"),
        Pattern.compile("(?:ich|wir) (?:finde|finden|kriege|krieg) dich"), Pattern.compile("hau ab"), Pattern.compile("verpiss dich"));
    private static final Set<String> DIRECTED = Set.of("du", "dich", "dir", "dein", "deine", "deinen", "deiner", "ihr", "euch", "eure", "you", "your",
        "youre", "ur", "u", "yo");

    static String normalise(String text) {
        String folded = Normalizer.normalize(text == null ? "" : text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "")
            .replace('ß', 's').replace('0', 'o').replace('1', 'i').replace('3', 'e').replace('4', 'a').replace('5', 's').replace('@', 'a').replace('$', 's');
        return folded.replaceAll("[^a-z\\s]", " ").replaceAll("(\\p{L})\\1{2,}", "$1$1").replaceAll("\\s+", " ").strip();
    }

    /**
     * Hostility score 0–100.
     *
     * @param names other players currently in the chat; mentioning one counts as directed
     */
    static int hostility(String text, Collection<String> names) {
        if (text == null || text.isBlank()) return 0;
        String clean = normalise(text);
        if (clean.isBlank()) return 0;
        int score = 0, hits = 0;
        String[] words = clean.split(" ");
        for (String word : words) {
            if (word.length() < 2) continue;
            int best = 0;
            for (var stem : STEMS.entrySet()) {
                String key = stem.getKey();
                if (stem.getValue() == 0) continue;
                boolean match = key.length() <= 4 ? word.equals(key) || word.equals(key + "s") : word.startsWith(key) || word.endsWith(key) && word.length() - key.length() <= 4;
                if (match) best = Math.max(best, stem.getValue());
            }
            if (best > 0) { score += hits == 0 ? best : best / 2; hits++; }
        }
        for (Pattern phrase : PHRASES) if (phrase.matcher(clean).find()) { score += 35; hits++; }
        boolean directed = Arrays.stream(words).anyMatch(DIRECTED::contains);
        if (!directed && names != null) for (String name : names) {
            String n = normalise(name);
            if (n.length() >= 3 && (" " + clean + " ").contains(" " + n + " ")) { directed = true; break; }
        }
        if (hits > 0 && directed) score += 15;
        long letters = text.chars().filter(Character::isLetter).count(), upper = text.chars().filter(Character::isUpperCase).count();
        if (hits > 0 && letters >= 8 && upper * 10 >= letters * 7) score += 10;
        if (hits > 0 && text.chars().filter(c -> c == '!').count() >= 3) score += 5;
        if (hits == 0) return 0;
        return Math.min(100, score);
    }

    static boolean hostile(String text, Collection<String> names) { return hostility(text, names) >= HOSTILE; }
}
