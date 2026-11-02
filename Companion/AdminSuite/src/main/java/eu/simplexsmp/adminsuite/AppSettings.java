package eu.simplexsmp.adminsuite;

import java.util.*;

/**
 * 9.7.0: curated settings that were previously only reachable through Java/Bedrock menus. Each key has
 * a type and range; unknown keys are rejected, so the app can never write protected configuration.
 */
final class AppSettings {
    record Setting(String key, String group, String title, String type, int min, int max, Object fallback, String help) { }
    static final List<Setting> ALL = List.of(
        bool("nameplates.show-luckperms", "Ränge & Namen", "LuckPerms-Ränge anzeigen", true, "Hauptschalter für Präfix und Suffix aus LuckPerms, auch für Bedrock über Geyser."),
        bool("nameplates.rank-above-head", "Ränge & Namen", "Rang über dem Kopf (Namensschild)", true, "Nur das Namensschild über dem Spieler."),
        bool("nameplates.rank-in-player-list", "Ränge & Namen", "Rang in der Tabliste", true, "Aus = die Tabliste zeigt nur den Namen (und [AFK])."),
        bool("nameplates.yield-to-tab-plugin", "Ränge & Namen", "Tabliste einem TAB-Plugin überlassen", true, "Ist TAB/TabList installiert, ändert die Suite die Tabliste gar nicht – Ränge nur über dem Kopf."),
        bool("nameplates.color-names", "Ränge & Namen", "Spielername in Rangfarbe", true, "Der Name übernimmt die letzte Farbe des Präfixes."),
        bool("nameplates.manage-nametags", "Ränge & Namen", "Namensschilder über dem Kopf verwalten", true, "Aus = andere Plugins (z. B. TAB) übernehmen die Nametags."),
        bool("nameplates.manage-player-list", "Ränge & Namen", "Tabliste verwalten", true, ""),
        bool("nameplates.decorate-foreign-teams", "Ränge & Namen", "Fremde Einzelteams mit Rang ergänzen", false, "Nur aktivieren, wenn ein anderes Plugin Teams ohne Präfix anlegt."),
        integer("nameplates.refresh-seconds", "Ränge & Namen", "Aktualisierung in Sekunden", 1, 30, 2, ""),
        bool("afk.enabled", "AFK", "AFK-Erkennung", true, ""),
        integer("afk.timeout-minutes", "AFK", "AFK nach Minuten", 1, 120, 5, ""),
        bool("afk.show-above-name", "AFK", "[AFK] über dem Kopf", true, ""),
        bool("afk.show-in-player-list", "AFK", "[AFK] in der Tabliste", true, ""),
        bool("afk.broadcast-away", "AFK", "AFK im Chat ankündigen", true, ""),
        bool("afk.kick-enabled", "AFK", "AFK-Spieler entfernen", false, ""),
        integer("afk.kick-after-minutes", "AFK", "Entfernen nach Minuten", 5, 600, 30, ""),
        bool("scoreboard.enabled", "Scoreboard", "Seitenleiste anzeigen", true, "Wirkt nach Suite-Reload vollständig."),
        bool("scoreboard.hide-java-score-numbers", "Scoreboard", "Rote Zahlen auf Java ausblenden", true, ""),
        bool("tickets.enabled", "Tickets", "Ticketsystem aktiv", true, ""),
        bool("tickets.join-hint", "Tickets", "Wöchentlicher /ticket-Hinweis beim Beitritt", true, ""),
        bool("tickets.bark", "Tickets", "Neue Tickets an Bark", true, ""),
        integer("tickets.delivery-delay-seconds", "Tickets", "Antwortzustellung nach Online-Sekunden", 0, 600, 20, ""),
        integer("tickets.max-open-per-player", "Tickets", "Offene Tickets pro Spieler", 1, 20, 3, ""),
        bool("tickets.discord.ping-staff", "Tickets", "Team in Discord pingen", true, ""),
        bool("punishments.broadcast-actions", "Moderation", "Strafen öffentlich ankündigen", true, ""),
        bool("punishments.kick-player-on-ban", "Moderation", "Beim Bann sofort kicken", true, ""),
        bool("reports.enabled", "Moderation", "Spieler-Reports aktiv", true, ""),
        integer("reports.cooldown-seconds", "Moderation", "Report-Abklingzeit (Sekunden)", 0, 3600, 120, ""),
        bool("staff-workspace.join-summary-enabled", "Team", "Team-Zusammenfassung beim Beitritt", true, ""),
        text("app-api.owner-name", "Team", "Dein Name als Hauptadmin (Staff-Chat & Admin-Log)", "", "Erscheint statt „Simplex Panel“ – z. B. Robin."),
        text("app-api.public-staff-name", "Team", "Öffentlicher Teamname bei Ticketantworten", "Team", "Spieler sehen diesen Namen, wenn ein App-Konto ohne eigenen öffentlichen Namen antwortet. Login-Namen werden nie gezeigt."),
        bool("staff-chat.use-webhook", "Team", "Staff-Chat mit Absendername (Webhook)", true, "Benötigt „Webhooks verwalten“; sonst sauberes Embed im Namen des Absenders."),
        text("app-api.public-address", "App-Zugang", "Öffentliche API-Adresse für Setup-Links", "", "Host:Port, z. B. play.example.com:39093. Leer = Adresse der App verwenden."),
        bool("emergency-restart.notify-crash", "Server", "Hinweis nach unerwartetem Stopp", true, "Kritischer Bark-Hinweis, wenn der letzte Lauf abgestürzt ist."),
        bool("startup.console-animation", "Server", "Startanimation in der Konsole", true, ""),
        bool("fun.fart.enabled", "Spaß", "/fart erlauben", true, ""),
        bool("fun.burp.enabled", "Spaß", "/burp erlauben", true, "")
    );
    private static Setting bool(String key, String group, String title, boolean fallback, String help) { return new Setting(key, group, title, "bool", 0, 1, fallback, help); }
    private static Setting integer(String key, String group, String title, int min, int max, int fallback, String help) { return new Setting(key, group, title, "int", min, max, fallback, help); }
    private static Setting text(String key, String group, String title, String fallback, String help) { return new Setting(key, group, title, "text", 0, 120, fallback, help); }
    static Setting find(String key) { for (Setting s : ALL) if (s.key().equals(key)) return s; throw new IllegalArgumentException("Einstellung nicht freigegeben: " + key); }

    static Object data(SimplexAdminSuite plugin) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Setting s : ALL) {
            Object value = switch (s.type()) { case "bool" -> plugin.getConfig().getBoolean(s.key(), (Boolean) s.fallback()); case "int" -> plugin.getConfig().getInt(s.key(), (Integer) s.fallback()); default -> plugin.getConfig().getString(s.key(), String.valueOf(s.fallback())); };
            rows.add(SimplexAdminSuite.appMap("key", s.key(), "group", s.group(), "title", s.title(), "type", s.type(), "min", s.min(), "max", s.max(), "value", value, "help", s.help()));
        }
        return SimplexAdminSuite.appMap("items", rows);
    }

    static Object apply(SimplexAdminSuite plugin, String key, String raw, String actor) {
        Setting s = find(key); String value = raw == null ? "" : raw.trim(); Object stored;
        switch (s.type()) {
            case "bool" -> { if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException("Wert muss true oder false sein"); stored = Boolean.parseBoolean(value); }
            case "int" -> { int n; try { n = Integer.parseInt(value); } catch (NumberFormatException e) { throw new IllegalArgumentException("Ganze Zahl erforderlich"); } if (n < s.min() || n > s.max()) throw new IllegalArgumentException("Erlaubt: " + s.min() + "–" + s.max()); stored = n; }
            default -> {
                if (value.length() > s.max() || value.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Text zu lang oder ungültig");
                if (key.equals("app-api.public-address") && !value.isEmpty() && !value.matches("(https://)?[A-Za-z0-9.-]{1,253}(:[0-9]{2,5})?/?")) throw new IllegalArgumentException("Format: host:port");
                if ((key.equals("app-api.owner-name") || key.equals("app-api.public-staff-name")) && !value.isEmpty()) AppChat.validateName(value);
                stored = value;
            }
        }
        plugin.getConfig().set(key, stored); plugin.saveConfig();
        plugin.suiteAudit(actor, "App-Einstellung", key + " = " + stored);
        if (key.startsWith("nameplates.") && plugin.qualityOfLife != null) plugin.qualityOfLife.names.tick();
        return SimplexAdminSuite.appMap("ok", true, "title", "Gespeichert", "description", s.title() + ": " + stored);
    }
}
