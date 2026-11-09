package eu.simplexsmp.adminsuite;

import java.text.Normalizer;
import java.util.*;

/**
 * 10.15.0: finds players by approximate names, e.g. "Miss Caitlin" → MissCaitlyn or "alpha craft" → AlphacraftMC.
 * Names are compared without spaces, case, separators and accents; look-alike letters (y/i, 0/o, 1/l, 3/e, 4/a, 5/s,
 * 7/t, ph/f, ck/k) count as equal, and a few typos are tolerated relative to the name length.
 */
final class PlayerNameMatcher {
    record Match(String name, int score) { }

    private PlayerNameMatcher() { }

    static String normalize(String value) {
        String text = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        text = text.replaceAll("[\\s_.\\-]+", "");
        text = text.replace("ph", "f").replace("ck", "k").replace("ie", "i").replace("ey", "i");
        StringBuilder out = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            out.append(switch (c) {
                case 'y' -> 'i'; case '0' -> 'o'; case '1' -> 'l'; case '3' -> 'e'; case '4' -> 'a'; case '5' -> 's'; case '7' -> 't';
                case 'z' -> 's'; case 'v' -> 'w';
                default -> c;
            });
        }
        // Repeated letters ("misscaitlynn") count once.
        return out.toString().replaceAll("(.)\\1+", "$1");
    }

    static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1], current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous; previous = current; current = swap;
        }
        return previous[b.length()];
    }

    /** Score 0–100; 100 = same after normalisation. Prefix and substring matches rank high, typos lower. */
    static int score(String query, String name) {
        String q = normalize(query), n = normalize(name);
        if (q.isEmpty() || n.isEmpty()) return 0;
        if (q.equals(n)) return 100;
        if (n.startsWith(q) && q.length() >= 3) return 90 - Math.min(20, n.length() - q.length());
        if (n.contains(q) && q.length() >= 4) return 80 - Math.min(20, n.length() - q.length());
        int d = distance(q, n), allowed = Math.max(1, Math.min(3, n.length() / 4));
        if (d <= allowed) return 75 - d * 10;
        return 0;
    }

    static List<Match> rank(String query, Collection<String> names, int limit) {
        Map<String, Integer> best = new LinkedHashMap<>();
        for (String name : names) {
            int score = score(query, name);
            if (score > 0) best.merge(name, score, Math::max);
        }
        return best.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
            .limit(limit).map(e -> new Match(e.getKey(), e.getValue())).toList();
    }

    /** A single clear winner: high score and ahead of the next candidate. */
    static Optional<Match> unique(List<Match> matches) {
        if (matches.isEmpty() || matches.get(0).score < 55) return Optional.empty();
        if (matches.size() > 1 && matches.get(1).score >= matches.get(0).score - 5) return Optional.empty();
        return Optional.of(matches.get(0));
    }
}
