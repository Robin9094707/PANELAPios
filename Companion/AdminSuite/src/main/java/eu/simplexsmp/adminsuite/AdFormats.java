package eu.simplexsmp.adminsuite;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 10.5.0 Werbe-Zentrale: text formats of the advertising platforms and conversion between them.
 * Discord markdown is the hub format: every format can be turned into Discord markdown and back into any other.
 * <ul>
 *   <li>{@code discord}  – Discord markdown (headings, -# subtext, quotes, lists, **bold** …)</li>
 *   <li>{@code markdown} – Reddit markdown (no underline, spoilers as >!x!<, hard line breaks)</li>
 *   <li>{@code bbcode}   – BBCode for Planet Minecraft, forums and server lists</li>
 *   <li>{@code whatsapp} – WhatsApp/Telegram style (*bold* _italic_ ~strike~)</li>
 *   <li>{@code unicode}  – plain text with 𝗯𝗼𝗹𝗱/𝘪𝘵𝘢𝘭𝘪𝘤 Unicode letters (Facebook, Instagram, X)</li>
 *   <li>{@code plain}    – plain text without any markers</li>
 * </ul>
 */
final class AdFormats {
    private AdFormats() { }

    static final List<String> FORMATS = List.of("discord", "markdown", "bbcode", "whatsapp", "unicode", "plain");
    static final Map<String, String> LABELS = Map.of("discord", "Discord-Markdown", "markdown", "Reddit-Markdown", "bbcode", "BBCode (Planet Minecraft, Foren)",
        "whatsapp", "WhatsApp / Telegram", "unicode", "Facebook (Unicode-Fett)", "plain", "Reiner Text");

    static String normalize(String format) { String f = format == null ? "" : format.trim().toLowerCase(Locale.ROOT); return FORMATS.contains(f) ? f : "plain"; }

    /** Converts {@code content} written in {@code from} into {@code to}. */
    static String convert(String content, String from, String to) {
        String f = normalize(from), t = normalize(to), text = content == null ? "" : content.replace("\r\n", "\n");
        if (f.equals(t)) return text;
        String hub = toDiscord(text, f);
        return switch (t) {
            case "discord" -> hub;
            case "markdown" -> discordToReddit(hub);
            case "bbcode" -> discordToBBCode(hub);
            case "whatsapp" -> discordToWhatsApp(hub);
            case "unicode" -> discordToPlain(hub, true);
            default -> discordToPlain(hub, false);
        };
    }

    // ================================================================== → Discord markdown

    static String toDiscord(String text, String format) {
        return switch (normalize(format)) {
            case "discord" -> text;
            case "markdown" -> text.replaceAll(">!(.+?)!<", "||$1||").replaceAll("\\^\\(([^)\\n]*)\\)", "$1").replaceAll(" {2,}\\n", "\n");
            case "bbcode" -> bbcodeToDiscord(text);
            case "whatsapp" -> whatsappToDiscord(text);
            default -> escapeDiscord(text);
        };
    }

    private static String escapeDiscord(String text) {
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            String l = line.replaceAll("([*_~`|\\\\])", "\\\\$1");
            if (l.matches("^(#{1,3} |-# |> |>>> |- |\\* ).*")) l = "\\" + l;
            out.append(l).append('\n');
        }
        return out.substring(0, out.length() - 1);
    }

    private static String whatsappToDiscord(String text) {
        List<String> code = new ArrayList<>();
        String s = protect(text, Pattern.compile("```[\\s\\S]*?```|`[^`\\n]+`"), code);
        s = s.replaceAll("(?<![\\w*])\\*(?=\\S)([^*\\n]*?\\S)\\*(?![\\w*])", "**$1**");
        s = s.replaceAll("(?<![\\w_])_(?=\\S)([^_\\n]*?\\S)_(?![\\w_])", "*$1*");
        s = s.replaceAll("(?<![\\w~])~(?=\\S)([^~\\n]*?\\S)~(?![\\w~])", "~~$1~~");
        return restore(s, code);
    }

    private static final Pattern BB_TAG = Pattern.compile("\\[(/?)([a-zA-Z*]+)(?:=([^\\]]*))?\\]");

    private static String bbcodeToDiscord(String text) {
        String s = text;
        s = s.replaceAll("(?is)\\[code\\]\\n?(.*?)\\n?\\[/code\\]", "```\n$1\n```");
        s = s.replaceAll("(?is)\\[url=([^\\]]+)\\](.*?)\\[/url\\]", "[$2]($1)");
        s = s.replaceAll("(?is)\\[url\\](.*?)\\[/url\\]", "$1");
        s = s.replaceAll("(?is)\\[img\\](.*?)\\[/img\\]", "$1");
        // Headings: a big size around a line becomes # / ## / ###; small sizes become -# subtext.
        Matcher size = Pattern.compile("(?is)\\[size=\"?(\\d+)(px|pt|%)?\"?\\](.*?)\\[/size\\]").matcher(s);
        StringBuilder sb = new StringBuilder();
        while (size.find()) {
            int n = Integer.parseInt(size.group(1)); String unit = size.group(2) == null ? "" : size.group(2).toLowerCase(Locale.ROOT), inner = size.group(3);
            int level = unit.equals("%") ? (n >= 180 ? 1 : n >= 140 ? 2 : n >= 115 ? 3 : n <= 85 ? 9 : 0) : unit.isEmpty() && n <= 7 ? (n >= 6 ? 1 : n == 5 ? 2 : n == 4 ? 3 : n <= 2 ? 9 : 0) : (n >= 24 ? 1 : n >= 20 ? 2 : n >= 17 ? 3 : n <= 11 ? 9 : 0);
            String plain = inner.replaceAll("(?i)\\[/?b\\]", "");
            String rep = level == 9 ? "-# " + inner : level > 0 && !inner.contains("\n") ? "#".repeat(level) + " " + plain : inner;
            size.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        size.appendTail(sb); s = sb.toString();
        // Quotes and lists are line based.
        Matcher quote = Pattern.compile("(?is)\\[quote(?:=[^\\]]*)?\\]\\n?(.*?)\\n?\\[/quote\\]").matcher(s);
        sb = new StringBuilder();
        while (quote.find()) { String q = String.join("\n", quote.group(1).lines().map(l -> "> " + l).toList()); quote.appendReplacement(sb, Matcher.quoteReplacement(q)); }
        quote.appendTail(sb); s = sb.toString();
        s = s.replaceAll("(?i)\\[list(=[^\\]]*)?\\]\\n?", "").replaceAll("(?i)\\n?\\[/list\\]", "").replaceAll("(?i)\\[\\*\\]\\s*", "- ");
        s = s.replaceAll("(?i)\\[b\\]\\[i\\](.*?)\\[/i\\]\\[/b\\]", "***$1***");
        s = s.replaceAll("(?is)\\[b\\](.*?)\\[/b\\]", "**$1**").replaceAll("(?is)\\[i\\](.*?)\\[/i\\]", "*$1*").replaceAll("(?is)\\[u\\](.*?)\\[/u\\]", "__$1__")
            .replaceAll("(?is)\\[s\\](.*?)\\[/s\\]", "~~$1~~").replaceAll("(?is)\\[strike\\](.*?)\\[/strike\\]", "~~$1~~").replaceAll("(?is)\\[spoiler(?:=[^\\]]*)?\\](.*?)\\[/spoiler\\]", "||$1||");
        // Anything else (center, color, font, hr …) is dropped, its text kept.
        s = s.replaceAll("(?i)\\[hr\\]", "").replaceAll("(?i)\\[/?(center|left|right|justify|color|font|indent|align|h[1-6])(=[^\\]]*)?\\]", "");
        return s;
    }

    // ================================================================== Discord markdown → others

    private static final Pattern CODE = Pattern.compile("```[\\s\\S]*?```|`[^`\\n]+`");

    private static String protect(String s, Pattern p, List<String> store) {
        Matcher m = p.matcher(s); StringBuilder sb = new StringBuilder();
        while (m.find()) { store.add(m.group()); m.appendReplacement(sb, "\u0000" + (store.size() - 1) + "\u0001"); }
        m.appendTail(sb); return sb.toString();
    }
    private static String restore(String s, List<String> store) {
        for (int i = store.size() - 1; i >= 0; i--) s = s.replace("\u0000" + i + "\u0001", store.get(i));
        return s;
    }

    /** Inline Discord markers in a fixed order; {@code wrap[i]} = {open, close} for bi, b, u, i, s, spoiler. */
    private static String inline(String s, String[][] wrap, boolean linkAsText, String[] link) {
        String out = s.replaceAll("<a?:(\\w{2,32}):\\d{15,25}>", ":$1:").replaceAll("<@!?\\d{15,25}>", "@Benutzer").replaceAll("<@&\\d{15,25}>", "@Rolle").replaceAll("<#\\d{15,25}>", "#Kanal");
        out = out.replaceAll("<(https?://[^>\\s]+)>", "$1");
        // Markers first, real wrappers last – so "**x**" turned into "*x*" is never read as italic again.
        String[][] mk = new String[6][];
        for (int i = 0; i < 6; i++) mk[i] = new String[]{"\uE000" + (char) ('a' + i), "\uE001" + (char) ('a' + i)};
        out = replaceInline(out, "\\*\\*\\*(.+?)\\*\\*\\*", mk[0]);
        out = replaceInline(out, "\\*\\*(.+?)\\*\\*", mk[1]);
        out = replaceInline(out, "__(.+?)__", mk[2]);
        out = replaceInline(out, "(?<![\\w*])\\*(?=\\S)(.*?\\S)\\*(?![\\w*])", mk[3]);
        out = replaceInline(out, "(?<![\\w_])_(?=\\S)(.*?\\S)_(?![\\w_])", mk[3]);
        out = replaceInline(out, "~~(.+?)~~", mk[4]);
        out = replaceInline(out, "\\|\\|(.+?)\\|\\|", mk[5]);
        for (int i = 0; i < 6; i++) out = out.replace(mk[i][0], wrap[i][0]).replace(mk[i][1], wrap[i][1]);
        Matcher m = Pattern.compile("\\[([^\\]\\n]+)\\]\\((https?://[^)\\s]+)\\)").matcher(out); StringBuilder sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(linkAsText ? m.group(1) + ": " + m.group(2) : link[0].replace("$URL", m.group(2)) + m.group(1) + link[1].replace("$URL", m.group(2))));
        m.appendTail(sb);
        return sb.toString().replaceAll("\\\\([*_~`|\\\\#>\\-])", "$1");
    }
    private static String replaceInline(String s, String regex, String[] w) {
        Matcher m = Pattern.compile(regex).matcher(s); StringBuilder sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(w[0] + m.group(1) + w[1]));
        m.appendTail(sb); return sb.toString();
    }

    private interface LineStyle { String apply(String inner); }

    /** Shared line walker: code fences pass through {@code fence}, headings/subtext/quotes/lists are styled per format. */
    private static String lines(String hub, LineStyle inline, String[] fence, LineStyle h1, LineStyle h2, LineStyle h3, LineStyle sub, String quoteOpen, String quoteLine, String quoteClose, String bullet, String listOpen, String listClose) {
        String[] rows = hub.split("\n", -1); StringBuilder out = new StringBuilder(); boolean inQuote = false, inList = false, rest = false;
        for (int i = 0; i < rows.length; i++) {
            String line = rows[i];
            if (line.trim().startsWith("```")) {
                StringBuilder code = new StringBuilder(); String first = line.trim().substring(3);
                if (first.contains("```")) code.append(first, 0, first.indexOf("```"));
                else { if (!first.isBlank() && !first.trim().matches("[A-Za-z0-9+#-]{1,20}")) code.append(first).append('\n');
                    for (i++; i < rows.length; i++) { if (rows[i].contains("```")) { code.append(rows[i], 0, rows[i].indexOf("```")); break; } code.append(rows[i]).append('\n'); } }
                if (inList) { out.append(listClose); inList = false; }
                out.append(fence[0]).append(code.toString().replaceAll("\\n$", "")).append(fence[1]).append('\n');
                continue;
            }
            boolean quoted = rest || line.startsWith(">");
            if (!rest && line.startsWith(">>> ")) { rest = true; line = line.substring(4); }
            else if (!rest && line.startsWith(">>>")) { rest = true; line = line.substring(3); }
            else if (!rest && line.startsWith("> ")) line = line.substring(2);
            else if (!rest && line.equals(">")) line = "";
            if (quoted && !inQuote) { out.append(quoteOpen); inQuote = true; }
            if (!quoted && inQuote) { out.append(quoteClose); inQuote = false; }
            Matcher li = Pattern.compile("^(\\s*)[-*] (.+)$").matcher(line);
            boolean isList = li.matches();
            if (isList && !inList) { out.append(listOpen); inList = true; }
            if (!isList && inList) { out.append(listClose); inList = false; }
            String rendered;
            if (line.startsWith("### ")) rendered = h3.apply(inline.apply(line.substring(4)));
            else if (line.startsWith("## ")) rendered = h2.apply(inline.apply(line.substring(3)));
            else if (line.startsWith("# ")) rendered = h1.apply(inline.apply(line.substring(2)));
            else if (line.startsWith("-# ")) rendered = sub.apply(inline.apply(line.substring(3)));
            else if (isList) rendered = li.group(1) + bullet + inline.apply(li.group(2));
            else rendered = inline.apply(line);
            out.append(quoted ? quoteLine : "").append(rendered).append('\n');
        }
        if (inList) out.append(listClose);
        if (inQuote) out.append(quoteClose);
        return out.toString().replaceAll("\\n+$", "");
    }

    static String discordToBBCode(String hub) {
        String[][] w = {{"[b][i]", "[/i][/b]"}, {"[b]", "[/b]"}, {"[u]", "[/u]"}, {"[i]", "[/i]"}, {"[s]", "[/s]"}, {"[spoiler]", "[/spoiler]"}};
        LineStyle in = t -> {
            List<String> spans = new ArrayList<>(); String p = protect(t, Pattern.compile("`[^`\\n]+`"), spans);
            for (int i = 0; i < spans.size(); i++) spans.set(i, "[b]" + spans.get(i).substring(1, spans.get(i).length() - 1) + "[/b]");
            return restore(inline(p, w, false, new String[]{"[url=$URL]", "[/url]"}), spans);
        };
        LineStyle nb = t -> t.replace("[b]", "").replace("[/b]", "");
        String out = lines(hub, in, new String[]{"[code]", "[/code]"}, t -> "[size=6][b]" + nb.apply(t) + "[/b][/size]", t -> "[size=5][b]" + nb.apply(t) + "[/b][/size]", t -> "[size=4][b]" + nb.apply(t) + "[/b][/size]",
            t -> "[size=2]" + t + "[/size]", "[quote]\n", "", "[/quote]\n", "[*]", "[list]\n", "[/list]\n");
        return out;
    }

    static String discordToReddit(String hub) {
        String[][] w = {{"***", "***"}, {"**", "**"}, {"", ""}, {"*", "*"}, {"~~", "~~"}, {">!", "!<"}};
        LineStyle in = t -> {
            List<String> spans = new ArrayList<>(); String p = protect(t, Pattern.compile("`[^`\\n]+`"), spans);
            return restore(inline(p, w, false, new String[]{"[", "]($URL)"}), spans);
        };
        String body = lines(hub, in, new String[]{"```\n", "\n```"}, t -> "# " + t, t -> "## " + t, t -> "### " + t, t -> "^(" + t + ")", "", "> ", "\n", "- ", "", "\n");
        // Single line breaks collapse on Reddit: two trailing spaces force them.
        String[] rows = body.split("\n", -1); StringBuilder out = new StringBuilder(); boolean fence = false;
        for (int i = 0; i < rows.length; i++) {
            String r = rows[i]; if (r.startsWith("```")) fence = !fence;
            boolean next = i + 1 < rows.length && !rows[i + 1].isBlank();
            out.append(r).append(!fence && next && !r.isBlank() && !r.startsWith("#") && !r.startsWith("```") ? "  " : "").append('\n');
        }
        return out.toString().replaceAll("\\n+$", "");
    }

    static String discordToWhatsApp(String hub) {
        String[][] w = {{"*_", "_*"}, {"*", "*"}, {"", ""}, {"_", "_"}, {"~", "~"}, {"", ""}};
        LineStyle in = t -> inline(t, w, true, null);
        return lines(hub, in, new String[]{"```", "```"}, t -> "*" + t.toUpperCase(Locale.ROOT).replace("*", "") + "*", t -> "*" + t.replace("*", "") + "*", t -> "*" + t.replace("*", "") + "*",
            t -> "_" + t + "_", "", "> ", "", "• ", "", "");
    }

    static String discordToPlain(String hub, boolean unicode) {
        String[][] w = unicode ? new String[][]{{"\u0010", "\u0011"}, {"\u0012", "\u0013"}, {"", ""}, {"\u0014", "\u0015"}, {"", ""}, {"", ""}} : new String[][]{{"", ""}, {"", ""}, {"", ""}, {"", ""}, {"", ""}, {"", ""}};
        LineStyle in = t -> inline(t.replaceAll("`([^`\\n]+)`", "$1"), w, true, null);
        LineStyle head = t -> "\u0012" + t.replaceAll("[\u0010-\u0015]", "") + "\u0013";
        String out = lines(hub, in, new String[]{"", ""}, t -> unicode ? head.apply(t) : t.toUpperCase(Locale.ROOT), t -> unicode ? head.apply(t) : t, t -> unicode ? head.apply(t) : t,
            t -> t, "", unicode ? "▎ " : "  ", "", "• ", "", "");
        if (!unicode) return out;
        StringBuilder sb = new StringBuilder(); int mode = 0;
        for (int i = 0; i < out.length(); ) {
            int cp = out.codePointAt(i); i += Character.charCount(cp);
            if (cp == 0x10) { mode = 3; continue; } if (cp == 0x11) { mode = 0; continue; }
            if (cp == 0x12) { mode = mode == 3 ? 3 : 1; continue; } if (cp == 0x13) { mode = 0; continue; }
            if (cp == 0x14) { mode = mode == 1 ? 3 : 2; continue; } if (cp == 0x15) { mode = 0; continue; }
            sb.appendCodePoint(fancy(cp, mode));
        }
        return sb.toString();
    }

    /** Mathematical sans-serif bold (1), italic (2) and bold italic (3) letters; digits only in bold. */
    static int fancy(int cp, int mode) {
        if (mode == 0) return cp;
        boolean upper = cp >= 'A' && cp <= 'Z', lower = cp >= 'a' && cp <= 'z', digit = cp >= '0' && cp <= '9';
        int base = switch (mode) { case 1 -> 0x1D5D4; case 2 -> 0x1D608; default -> 0x1D63C; };
        if (upper) return base + (cp - 'A');
        if (lower) return base + 26 + (cp - 'a');
        if (digit && mode != 2) return 0x1D7EC + (cp - '0');
        return cp;
    }
}
