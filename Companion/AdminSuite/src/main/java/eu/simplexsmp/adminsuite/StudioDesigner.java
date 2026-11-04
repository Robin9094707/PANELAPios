package eu.simplexsmp.adminsuite;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 10.3.0 "Studio": field catalogue, presets and validation for the Discord status designer and the scoreboard
 * designer in app and web panel. The shipped defaults are exactly the previous built-in values, so nothing changes
 * until an admin saves something.
 */
final class StudioDesigner {
    private StudioDesigner() { }

    /** type: text, multiline, url, color, number, int, bool. group is used for the editor sections. */
    record Field(String key, String label, String group, String type, Object fallback, double min, double max) {
        String path() {
            if (key.startsWith("presence.")) return "discord-bot." + key;
            if (key.equals("max-player-lines")) return "discord-bot.status.max-player-lines";
            return "discord-bot.status.design." + key;
        }
    }

    static final List<Field> STATUS_FIELDS = List.of(
        new Field("server-name", "Servername", "Allgemein", "text", "Simplex SMP", 0, 80),
        new Field("author-name", "Autorzeile über dem Titel (optional)", "Allgemein", "text", "", 0, 256),
        new Field("footer", "Fußzeile ({server}, {interval})", "Allgemein", "text", "Persistent status • Java and Bedrock", 0, 400),
        new Field("thumbnail-url", "Kleines Bild rechts (https, optional)", "Allgemein", "url", "", 0, 500),
        new Field("image-url", "Großes Bild unten (https, optional)", "Allgemein", "url", "", 0, 500),
        new Field("online-title", "Titel: online", "Online", "text", "🟢 {server} is online", 0, 256),
        new Field("online-description", "Beschreibung: online", "Online", "multiline", "The server is online and accepting players.", 0, 1000),
        new Field("healthy-color", "Farbe: online", "Online", "color", "#57F287", 0, 0),
        new Field("warning-title", "Titel: instabil", "Instabil", "text", "🟡 {server} stability warning", 0, 256),
        new Field("warning-description", "Beschreibung: instabil", "Instabil", "multiline", "Server performance is currently degraded. Gameplay may feel less responsive.", 0, 1000),
        new Field("warning-color", "Farbe: instabil", "Instabil", "color", "#FEE75C", 0, 0),
        new Field("warning-tps", "Instabil ab TPS unter", "Instabil", "number", 18.5D, 10, 20),
        new Field("critical-title", "Titel: überlastet", "Überlastet", "text", "🔴 {server} is under heavy load", 0, 256),
        new Field("critical-description", "Beschreibung: überlastet", "Überlastet", "multiline", "Server performance is currently unstable. Staff have been notified.", 0, 1000),
        new Field("critical-color", "Farbe: überlastet", "Überlastet", "color", "#ED4245", 0, 0),
        new Field("critical-tps", "Überlastet ab TPS unter", "Überlastet", "number", 16.0D, 5, 19.9),
        new Field("restarting-title", "Titel: Neustart", "Neustart & offline", "text", "🟠 {server} is restarting", 0, 256),
        new Field("restarting-description", "Beschreibung: Neustart ({seconds})", "Neustart & offline", "multiline", "The server is restarting. Players can reconnect shortly.", 0, 1000),
        new Field("restarting-color", "Farbe: Neustart", "Neustart & offline", "color", "#FEE75C", 0, 0),
        new Field("offline-title", "Titel: offline", "Neustart & offline", "text", "🔴 {server} is offline", 0, 256),
        new Field("offline-description", "Beschreibung: offline", "Neustart & offline", "multiline", "The server is currently shut down.", 0, 1000),
        new Field("offline-color", "Farbe: offline", "Neustart & offline", "color", "#ED4245", 0, 0),
        new Field("show-player-count", "Spielerzahl", "Felder", "bool", true, 0, 0),
        new Field("show-tps", "TPS", "Felder", "bool", true, 0, 0),
        new Field("show-stability", "Stabilität", "Felder", "bool", true, 0, 0),
        new Field("show-player-list", "Spielerliste", "Felder", "bool", true, 0, 0),
        new Field("show-platform", "Plattform je Spieler", "Felder", "bool", true, 0, 0),
        new Field("max-player-lines", "Höchstens Spieler in der Liste", "Felder", "int", 30, 5, 50),
        new Field("show-world-info", "Spieltage & Uhrzeit der Hauptwelt", "Felder", "bool", true, 0, 0),
        new Field("show-server-load", "CPU- & RAM-Auslastung", "Felder", "bool", true, 0, 0),
        new Field("show-leaderboard", "Simplex-Points-Top-10", "Felder", "bool", false, 0, 0),
        new Field("presence.healthy", "Bot-Aktivität: gesund ({players}, {max}, {tps})", "Bot-Aktivität", "text", "Managing {players} players • {tps} TPS", 0, 128),
        new Field("presence.warning", "Bot-Aktivität: instabil", "Bot-Aktivität", "text", "Watching server stability • {tps} TPS", 0, 128),
        new Field("presence.critical", "Bot-Aktivität: überlastet", "Bot-Aktivität", "text", "Responding to server load • {tps} TPS", 0, 128),
        new Field("presence.maintenance", "Bot-Aktivität: Wartung", "Bot-Aktivität", "text", "Server maintenance • {players} online", 0, 128),
        new Field("presence.restarting", "Bot-Aktivität: Neustart ({seconds})", "Bot-Aktivität", "text", "Restarting server • {seconds}s", 0, 128),
        new Field("presence.trimming", "Bot-Aktivität: Welten-Trimmer ({progress})", "Bot-Aktivität", "text", "Trimming worlds • {progress}%", 0, 128));

    static Field statusField(String key) { for (Field f : STATUS_FIELDS) if (f.key.equals(key)) return f; return null; }

    static Map<String, Object> statusDefaults() { Map<String, Object> out = new LinkedHashMap<>(); for (Field f : STATUS_FIELDS) out.put(f.key, f.fallback); return out; }

    private static Map<String, Object> preset(String id, String title, String explain, Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) values.put(String.valueOf(pairs[i]), pairs[i + 1]);
        return SimplexAdminSuite.appMap("id", id, "title", title, "explain", explain, "values", values);
    }

    /** Presets only list the keys they change; applying one starts from the shipped defaults. */
    static List<Map<String, Object>> statusPresets() {
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(preset("standard", "Simplex Standard", "Der bisherige Status – genau wie ausgeliefert."));
        out.add(preset("deutsch", "Deutsch", "Alle Texte auf Deutsch, sonst wie der Standard.",
            "online-title", "🟢 {server} ist online", "online-description", "Der Server läuft und nimmt Spieler auf – Java & Bedrock.",
            "warning-title", "🟡 {server} läuft etwas langsamer", "warning-description", "Die Serverleistung ist gerade eingeschränkt. Das Spielen kann sich träger anfühlen.",
            "critical-title", "🔴 {server} ist stark ausgelastet", "critical-description", "Die Leistung ist gerade instabil. Das Team ist informiert.",
            "restarting-title", "🟠 {server} startet neu", "restarting-description", "Der Server startet neu. In Kürze kannst du wieder beitreten.",
            "offline-title", "🔴 {server} ist offline", "offline-description", "Der Server ist gerade heruntergefahren.",
            "footer", "Live-Status • Java & Bedrock • alle {interval} s",
            "presence.healthy", "{players} Spieler • {tps} TPS", "presence.warning", "Beobachte die Stabilität • {tps} TPS", "presence.critical", "Server unter Last • {tps} TPS",
            "presence.maintenance", "Wartung • {players} online", "presence.restarting", "Neustart in {seconds} s", "presence.trimming", "Welten werden optimiert • {progress} %"));
        out.add(preset("minimal", "Minimal", "Nur Titel, Spielerzahl und TPS – ruhig und klar.",
            "online-title", "{server}", "online-description", "Online", "warning-title", "{server}", "warning-description", "Eingeschränkt",
            "critical-title", "{server}", "critical-description", "Stark ausgelastet", "restarting-title", "{server}", "restarting-description", "Neustart …",
            "offline-title", "{server}", "offline-description", "Offline", "footer", "{server}", "show-stability", false, "show-player-list", false,
            "show-world-info", false, "show-server-load", false));
        out.add(preset("kompakt", "Kompakt", "Spielerliste ohne Plattform, höchstens 10 Namen, ohne Auslastung.",
            "show-platform", false, "max-player-lines", 10, "show-server-load", false, "footer", "{server} • Live alle {interval} s"));
        out.add(preset("neon", "Neon", "Kräftige Neonfarben und auffällige Titel.",
            "online-title", "✦ {server} ✦ ONLINE", "warning-title", "✦ {server} ✦ LAG-WARNUNG", "critical-title", "✦ {server} ✦ ÜBERLASTET",
            "restarting-title", "✦ {server} ✦ NEUSTART", "offline-title", "✦ {server} ✦ OFFLINE",
            "healthy-color", "#00F5D4", "warning-color", "#FEE440", "critical-color", "#F15BB5", "restarting-color", "#9B5DE5", "offline-color", "#6C757D",
            "footer", "⚡ {server} • Java + Bedrock"));
        out.add(preset("nacht", "Mitternacht", "Dezentes Blurple, Mond-Emojis, ohne Auslastung.",
            "online-title", "🌙 {server} – online", "warning-title", "🌘 {server} – etwas langsam", "critical-title", "🌑 {server} – überlastet",
            "restarting-title", "🌗 {server} – Neustart", "offline-title", "🌑 {server} – offline", "healthy-color", "#5865F2", "warning-color", "#8A8FF7",
            "critical-color", "#EB459E", "restarting-color", "#8A8FF7", "offline-color", "#2B2D31", "show-server-load", false));
        out.add(preset("community", "Community", "Einladend, mit Simplex-Points-Bestenliste.",
            "online-title", "🎉 {server} ist online – komm vorbei!", "online-description", "Java & Bedrock willkommen. Schau, wer gerade spielt, und sammle Simplex Points!",
            "show-leaderboard", true, "show-server-load", false, "footer", "Bis gleich auf {server}!", "presence.healthy", "mit {players} Spielern auf {server}"));
        return out;
    }

    /** Validates one designer value and returns the value to store (String, Boolean, Double or Integer). */
    static Object validStatus(Field f, Object raw) {
        String text = raw == null ? "" : String.valueOf(raw);
        switch (f.type) {
            case "bool" -> { if (!text.equals("true") && !text.equals("false")) throw new IllegalArgumentException(f.label + ": true oder false"); return Boolean.parseBoolean(text); }
            case "color" -> { String v = text.trim().toUpperCase(Locale.ROOT); if (!v.matches("#[0-9A-F]{6}")) throw new IllegalArgumentException(f.label + ": Farbe als #RRGGBB"); return v; }
            case "number" -> { double v; try { v = Double.parseDouble(text.trim()); } catch (NumberFormatException e) { throw new IllegalArgumentException(f.label + ": Zahl erforderlich"); }
                if (!Double.isFinite(v) || v < f.min || v > f.max) throw new IllegalArgumentException(f.label + ": " + f.min + "–" + f.max); return Math.round(v * 10D) / 10D; }
            case "int" -> { int v; try { v = Integer.parseInt(text.trim()); } catch (NumberFormatException e) { throw new IllegalArgumentException(f.label + ": ganze Zahl erforderlich"); }
                if (v < f.min || v > f.max) throw new IllegalArgumentException(f.label + ": " + (int) f.min + "–" + (int) f.max); return v; }
            case "url" -> { String v = text.trim(); if (!v.isEmpty() && (v.length() > 500 || !v.matches("https://[^\\s\"<>]+"))) throw new IllegalArgumentException(f.label + ": nur https-Adressen"); return v; }
            default -> { if (text.length() > f.max) throw new IllegalArgumentException(f.label + ": höchstens " + (int) f.max + " Zeichen");
                if (text.chars().anyMatch(c -> c < 32 && (c != '\n' || !f.type.equals("multiline")))) throw new IllegalArgumentException(f.label + ": ungültige Steuerzeichen"); return text; }
        }
    }

    // ------------------------------------------------------------------ Scoreboard

    static final String SCOREBOARD_DEFAULT_TITLE = "&x&0&0&A&8&E&8&ls&x&0&0&B&5&E&2&li&x&0&0&C&1&D&C&lm&x&0&0&C&E&D&6&lp&x&0&0&D&A&D&0&ll&x&0&0&E&7&C&A&le&x&0&0&F&3&C&4&lx&x&1&5&E&A&C&D&ls&x&2&A&E&1&D&6&lm&x&4&0&D&8&E&0&lp&x&5&5&C&F&E&7&l.&x&6&A&C&6&F&0&le&x&8&0&B&D&F&9&lu";
    static final List<String> SCOREBOARD_DEFAULT_LINES = List.of("&8----------------", "&f ING-Days: &e{days}", "&f World: {world}",
            "&b Main: &f{main_players} &8• &2Vanilla: &f{second_players}", "&f Online: &b{online}&8/&7{max}", "&f Points: &b{points} SP",
            "&8----------------", "&9&lDC&8: &72gAsymnXfd");
    static final List<List<String>> PLACEHOLDERS = List.of(List.of("{days}", "Spieltage der aktuellen Welt"), List.of("{world}", "Weltname (Main/Vanilla …)"),
            List.of("{online}", "Spieler online"), List.of("{max}", "Maximale Spieler"), List.of("{main_players}", "Spieler in der Hauptwelt"),
            List.of("{second_players}", "Spieler in der Zweitwelt"), List.of("{points}", "Simplex Points des Spielers"), List.of("{player}", "Anzeigename des Spielers"));

    /** Hex gradient title like the shipped default, e.g. gradient("simplexsmp.eu", "00A8E8", "80BDF9", true). */
    static String gradient(String text, String from, String to, boolean bold) {
        int a = Integer.parseInt(from, 16), b = Integer.parseInt(to, 16);
        StringBuilder out = new StringBuilder();
        int n = Math.max(1, text.codePointCount(0, text.length()) - 1), i = 0;
        for (int cp : text.codePoints().toArray()) {
            double t = (double) i++ / n;
            int r = (int) Math.round(((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * t);
            int g = (int) Math.round(((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * t);
            int bl = (int) Math.round((a & 255) + ((b & 255) - (a & 255)) * t);
            String hex = String.format(Locale.ROOT, "%02X%02X%02X", r, g, bl);
            if (cp == ' ') { out.append(' '); continue; }
            out.append("&x"); for (char c : hex.toCharArray()) out.append('&').append(c);
            if (bold) out.append("&l");
            out.appendCodePoint(cp);
        }
        return out.toString();
    }

    private static Map<String, Object> board(String id, String title, String explain, String boardTitle, String... lines) {
        return SimplexAdminSuite.appMap("id", id, "title", title, "explain", explain, "boardTitle", boardTitle, "lines", List.of(lines));
    }

    static List<Map<String, Object>> scoreboardPresets() {
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(SimplexAdminSuite.appMap("id", "standard", "title", "Simplex Standard", "explain", "Das bisherige Scoreboard – genau wie ausgeliefert.", "boardTitle", SCOREBOARD_DEFAULT_TITLE, "lines", SCOREBOARD_DEFAULT_LINES));
        out.add(board("deutsch", "Deutsch", "Wie der Standard, mit deutschen Bezeichnungen.", SCOREBOARD_DEFAULT_TITLE,
            "&8----------------", "&f Spieltage: &e{days}", "&f Welt: {world}", "&b Haupt: &f{main_players} &8• &2Vanilla: &f{second_players}",
            "&f Online: &b{online}&8/&7{max}", "&f Punkte: &b{points} SP", "&8----------------", "&9&lDC&8: &72gAsymnXfd"));
        out.add(board("minimal", "Minimal", "Vier Zeilen, viel Luft.", "&b&lSimplex SMP",
            "&7", "&fOnline &b{online}&7/&8{max}", "&fPunkte &b{points}", "&7 "));
        out.add(board("neon", "Neon", "Farbverlauf in Türkis bis Pink.", gradient("SIMPLEX SMP", "00F5D4", "F15BB5", true),
            "&d&m              ", "&b▸ &fTag &d{days}", "&b▸ &fWelt &d{world}", "&b▸ &fOnline &d{online}&8/&7{max}", "&b▸ &fPunkte &d{points}", "&d&m              ", "&9discord &8» &72gAsymnXfd"));
        out.add(board("survival", "Survival", "Erdige Töne für den Survival-Alltag.", "&2&l⛏ &a&lSIMPLEX SURVIVAL",
            "&8&m                ", "&a☀ &fTag &e{days}", "&a⌂ &fWelt &7{world}", "&a☺ &fOnline &e{online}&7/&8{max}", "&a✦ &fPunkte &e{points}", "&8&m                ", "&7Viel Spaß, &f{player}"));
        out.add(board("event", "Event", "Goldener Event-Look.", "&6&l★ &e&lEVENT &6&l★",
            "&7", "&6» &fSpieler &e{online}", "&6» &fDeine Punkte &e{points}", "&7 ", "&e&lJetzt mitmachen!", "&9discord &8» &72gAsymnXfd"));
        out.add(board("kompakt", "Kompakt", "Alles Wichtige in fünf Zeilen.", "&b&lsimplexsmp.eu",
            "&fTag &e{days} &8• &f{world}", "&fOnline &b{online}&8/&7{max}", "&fMain &f{main_players} &8• &2Vanilla &f{second_players}", "&fPunkte &b{points}", "&9DC &72gAsymnXfd"));
        return out;
    }

    static String validTitle(Object raw) {
        String v = raw == null ? "" : String.valueOf(raw).replace('§', '&');
        if (v.isBlank() || v.length() > 600) throw new IllegalArgumentException("Titel: 1–600 Zeichen");
        if (v.chars().anyMatch(c -> c < 32)) throw new IllegalArgumentException("Titel: ungültige Steuerzeichen");
        return v;
    }

    static List<String> validLines(Object raw) {
        if (!(raw instanceof List<?> list)) throw new IllegalArgumentException("Zeilen als Liste erforderlich");
        if (list.isEmpty() || list.size() > 15) throw new IllegalArgumentException("1–15 Zeilen erforderlich");
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            String v = item == null ? "" : String.valueOf(item).replace('§', '&');
            if (v.length() > 180) throw new IllegalArgumentException("Jede Zeile höchstens 180 Zeichen");
            if (v.chars().anyMatch(c -> c < 32)) throw new IllegalArgumentException("Zeile enthält ungültige Steuerzeichen");
            out.add(v.isEmpty() ? " " : v);
        }
        return out;
    }
}
