package eu.simplexsmp.adminsuite;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Discord-facing administration model for 7.2.0. This class deliberately contains no HTTP or
 * Bukkit scheduling code: DiscordBotManager owns transport/deadlines and invokes execute() on the
 * server thread. Every mutation therefore reaches the same checked plugin services as the Java and
 * Bedrock administration surfaces.
 */
final class DiscordAdminController {
    static final String COMMAND = "sasadmin";
    private static final int STRING = 3;
    private static final int INTEGER = 4;
    private static final int BOOLEAN = 5;
    private static final Set<String> SAFE_SUITE_ROOTS = Set.of(
            "status", "diagnostics", "deviceinfo", "check", "channels", "clientsecurity", "alts", "warnings", "warnungen", "notes", "notizen",
            "suche", "search", "watchlist", "announcement", "discordstatus", "points", "punkte", "vpn");
    private static final Set<String> DASHBOARD_PAGES = Set.of(
            "home", "players", "moderation", "server", "worlds", "security", "communication",
            "community", "analytics", "qol", "integrations", "operations");
    private static final Set<String> ALLOWED_SUITE_ROOTS = Set.of(
            "status", "diagnostics", "deviceinfo", "devicediagnostics", "check", "channels", "clientsecurity", "alts", "warnings", "warnungen", "notes", "notizen",
            "suche", "search", "watchlist", "announcement", "staffmsg", "slowmode", "commandlock",
            "chat", "maintenance", "wartung", "freeze", "unfreeze", "heal", "feed", "gamemode",
            "ban", "tempban", "unban", "mute", "tempmute", "unmute", "kick", "warn",
            "clearwarnings", "recht", "rechte", "altlink", "altignore", "altban", "ultraban",
            "ultraunban", "ultrareset", "reload", "discordstatus", "export", "rules", "regeln",
            "polls", "umfragen", "points", "punkte", "chatfilter", "vpn", "moderationplus", "banmode", "restrict", "deviceblock", "chatstyle", "modlog", "checkwhitelist", "geoinfo", "suiteconfig", "osblock", "adminlog", "antispam", "operations", "leitstelle", "duty", "afk", "startnachrichten", "homes", "homeadmin", "power", "health", "incidents", "makros", "bulk", "timeline", "planer", "aufgaben", "laginspector", "statusdesign", "fun", "qol");
    private static final List<String> SUITE_SUGGESTIONS = List.of(
            "status", "diagnostics", "check <player>", "alts <player>", "warnings <player>",
            "notes <player> list", "watchlist list", "slowmode 0", "commandlock off", "chat on",
            "maintenance off", "freeze <player>", "unfreeze <player>", "heal <player>",
            "feed <player>", "gamemode <player> survival", "ban <player> <reason>",
            "tempban <player> 1d <reason>", "unban <player>", "mute <player> <reason>",
            "tempmute <player> 30m <reason>", "unmute <player>", "kick <player> <reason>",
            "warn <player> <reason>", "recht <player> pvp aus", "discordstatus sync", "vpn status",
            "vpn whitelist anzeigen", "vpn protokoll", "banmode <player> ghost <reason>", "banmode <player> none", "restrict <player> text 1h true <reason>", "deviceblock list", "chatstyle colors off", "modlog ai create");

    static final class Actor {
        final String id;
        final String name;
        final boolean discordAdministrator;
        final String channelId;

        Actor(String id, String name, boolean discordAdministrator, String channelId) {
            this.id = id == null ? "" : id;
            this.name = name == null || name.isBlank() ? "Discord Admin" : name;
            this.discordAdministrator = discordAdministrator;
            this.channelId = channelId == null ? "" : channelId;
        }

        String auditName() { return (id.startsWith("app:") ? "" : "Discord/") + name + " (" + id + ")"; }
    }

    static final class Request {
        final String path;
        final Map<String, Object> values;

        Request(String path, Map<String, Object> values) {
            this.path = path == null ? "dashboard" : path.toLowerCase(Locale.ROOT);
            this.values = values == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }

        String text(String key) {
            Object value = values.get(key);
            return value == null ? "" : String.valueOf(value).trim();
        }

        int integer(String key, int fallback) {
            Object value = values.get(key);
            if (value instanceof Number) return ((Number) value).intValue();
            try { return Integer.parseInt(text(key)); } catch (Exception ignored) { return fallback; }
        }

        boolean bool(String key, boolean fallback) {
            Object value = values.get(key);
            return value instanceof Boolean ? (Boolean) value : value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
        }
    }

    static final class Reply {
        final String title;
        final String description;
        final int color;
        final List<Map<String, Object>> components;
        final String activity;

        Reply(String title, String description, int color, List<Map<String, Object>> components, String activity) {
            this.title = title == null ? "Simplex Admin" : title;
            this.description = description == null ? "" : description;
            this.color = color;
            this.components = components == null ? List.of() : List.copyOf(components);
            this.activity = activity == null ? "Managing the Minecraft server" : activity;
        }

        static Reply ok(String title, String description, String activity) {
            return new Reply(title, description, 0x57F287, List.of(), activity);
        }

        static Reply error(String description) {
            return new Reply("Aktion nicht ausgeführt", description, 0xED4245, List.of(), "Protecting the Minecraft server");
        }
    }

    static final class Choice {
        final String name;
        final String value;
        Choice(String name, String value) {
            this.name = limit(name, 100);
            this.value = limit(value, 100);
        }
    }

    static final class AltView {
        final UUID uuid;
        final String name;
        final int score;
        final String level;
        AltView(UUID uuid, String name, int score, String level) {
            this.uuid = uuid; this.name = name; this.score = score; this.level = level;
        }
    }

    private final SimplexAdminSuite plugin;
    private final DiscordBotManager bot;

    DiscordAdminController(SimplexAdminSuite plugin, DiscordBotManager bot) {
        this.plugin = plugin;
        this.bot = bot;
    }

    List<Object> guildCommands() {
        List<Object> commands = new ArrayList<>();
        commands.add(legacyCommand());
        Map<String, Object> command = mapOf(
                "name", COMMAND,
                "description", "Simplex Admin Suite verwalten",
                "dm_permission", false,
                "options", List.of(
                        sub("dashboard", "Adminübersicht öffnen"),
                        group("player", "Spieler verwalten", List.of(
                                sub("info", "Gespeicherte Spielerakte", playerOption("player", true)),
                                sub("alts", "Alt-Konten und Beweisscores", playerOption("player", true)),
                                sub("action", "Spieleraktion ausführen",
                                        playerOption("player", true),
                                        choices("action", "Aktion", true,
                                                "heal-feed", "Heilen & sättigen", "clear-effects", "Effekte entfernen",
                                                "extinguish", "Feuer/Frost entfernen", "reset-movement", "Bewegung zurücksetzen",
                                                "sync-inventory", "Inventar synchronisieren", "spawn", "Zum Weltspawn senden",
                                                "freeze-toggle", "Freeze umschalten", "disconnect", "Spieler trennen",
                                                "gamemode", "Spielmodus setzen", "personal-slowmode", "Persönlicher Slowmode",
                                                "quarantine-toggle", "Quarantäne umschalten", "build-lock", "Bau-Lock",
                                                "chat-lock", "Chat-Lock", "adventure-lock", "Adventure-Lock",
                                                "restore-lock", "Rechtezustand restaurieren", "snapshot", "Snapshot erstellen",
                                                "clear-inventory", "Inventar sicher leeren", "clear-ender", "Enderchest sicher leeren",
                                                "reset-xp", "Erfahrung zurücksetzen", "alt-policy-toggle", "Alt-Rechte umschalten",
                                                "alt-future-toggle", "Zukünftige Alt-Rechte umschalten", "alt-ip-toggle", "IP-Opt-in umschalten",
                                                "alt-minimum-score", "Alt-Mindestscore setzen", "alt-sync", "Alt-Rechte jetzt synchronisieren"),
                                        string("value", "Wert, z. B. survival oder 10", false, true)),
                                sub("punish", "Strafen und Warnungen",
                                        playerOption("player", true),
                                        choices("type", "Strafaktion", true,
                                                "ban", "Permanent bannen", "tempban", "Temporär bannen", "unban", "Entbannen",
                                                "mute", "Permanent muten", "tempmute", "Temporär muten", "unmute", "Entmuten",
                                                "kick", "Kicken", "warn", "Verwarnen", "clearwarnings", "Warnungen löschen"),
                                        string("duration", "Dauer wie 30m, 6h oder 7d", false, true),
                                        string("reason", "Öffentlicher Grund (Englisch)", false, false)),
                                sub("right", "Spielerrechte ändern",
                                        playerOption("player", true), string("right", "Suite-Recht", true, true),
                                        choices("mode", "Neuer Zustand", true, "block", "Sperren", "allow", "Erlauben"),
                                        string("duration", "Sperrdauer, z. B. 30m oder 7d", false, true)),
                                sub("notes", "Teamnotizen verwalten",
                                        playerOption("player", true),
                                        choices("operation", "Aktion", true, "list", "Anzeigen", "add", "Hinzufügen", "clear", "Löschen"),
                                        string("text", "Neue interne Notiz", false, false)),
                                sub("watchlist", "Watchlist verwalten",
                                        playerOption("player", true),
                                        choices("operation", "Aktion", true, "add", "Hinzufügen", "remove", "Entfernen"),
                                        string("reason", "Interner Grund", false, false)),
                                sub("live", "Sitzung und Position prüfen", playerOption("player", true)),
                                sub("history", "Spielerverlauf anzeigen",
                                        playerOption("player", true),
                                        choices("category", "Verlauf", true, "all", "Gesamtübersicht", "chat", "Chat",
                                                "actions", "Aktionen", "combat", "Kampf", "private", "Private Nachrichten"),
                                        integer("limit", "Maximale Einträge (1-40)", false, 1, 40)),
                                sub("rights", "Alle Spielerrechte anzeigen", playerOption("player", true)),
                                sub("points", "Simplex Points verwalten",
                                        playerOption("player", true),
                                        choices("operation", "Aktion", true, "status", "Anzeigen", "set", "Setzen",
                                                "add", "Hinzufügen", "remove", "Abziehen"),
                                        integer("amount", "Punktebetrag", false, 0, 2000000000)),
                                sub("snapshots", "Inventar-Snapshots verwalten",
                                        playerOption("player", true),
                                        choices("operation", "Aktion", true, "list", "Anzeigen", "create", "Erstellen",
                                                "restore", "Wiederherstellen", "delete", "Löschen"),
                                        string("file", "Datei; leer = neuester Snapshot", false, true)),
                                sub("identity", "Identitäts- und Ultrabannakte", playerOption("player", true)),
                                sub("channels", "Java-Modkanäle und Hinweise", playerOption("player", true)),
                                sub("devices", "Geräte verwalten",
                                        playerOption("player", true),
                                        choices("operation", "Aktion", true, "list", "Anzeigen", "info", "Zusatzfelder", "compare", "Manueller Vergleich", "history", "Profilverlauf", "block", "Global sperren",
                                                "unblock", "Global entsperren", "delete", "Datensatz löschen", "clear", "Alle löschen"),
                                        string("identifier", "Geräte-ID; leer = neuestes", false, true), integer("page", "Detailseite (ab 1)", false, 1, 100)),
                                sub("message", "Private Teamnachricht senden",
                                        playerOption("player", true), string("message", "Nachricht (Englisch)", true, false)),
                                sub("nickname", "Server-Nickname ändern",
                                        playerOption("player", true), string("nickname", "Nickname oder reset", true, false))
                        )),
                        group("server", "Server und Discord steuern", List.of(
                                sub("status", "Serverstatus und Spieler"),
                                sub("maintenance", "Wartungsmodus verwalten",
                                        choices("mode", "Zustand", true, "on", "Aktivieren", "off", "Deaktivieren"),
                                        string("reason", "Wartungsgrund (Englisch)", false, false)),
                                sub("chat", "Serverchat schalten", choices("mode", "Zustand", true, "on", "Aktivieren", "off", "Deaktivieren")),
                                sub("slowmode", "Chat-Slowmode setzen", integer("seconds", "Chatabstand (0-120 Sekunden)", true, 0, 120)),
                                sub("command-lock", "Spielerbefehle schalten", choices("mode", "Zustand", true, "on", "Aktivieren", "off", "Deaktivieren")),
                                sub("announce", "Ankündigung senden", string("message", "Nachricht (Englisch)", true, false)),
                                sub("restart", "Neustart verwalten",
                                        choices("action", "Aktion", true, "status", "Status", "schedule", "Planen", "cancel", "Abbrechen"),
                                        integer("seconds", "Countdown (5-300 Sekunden)", false, 5, 300)),
                                sub("lag", "Lag-Inspector verwalten",
                                        choices("action", "Aktion", true, "status", "Status", "start", "Starten", "cancel", "Abbrechen", "results", "Hotspots")),
                                sub("trim", "Welten-Trimmer steuern",
                                        choices("action", "Aktion", true, "status", "Status", "scan", "Welten neu scannen", "start", "Starten",
                                                "cancel", "Countdown abbrechen", "continue", "Chunk-Bypass bestätigen", "abort", "Chunk-Bypass ablehnen", "recovery", "Recovery freigeben"),
                                        string("targets", "Dimensions-IDs oder all", false, true),
                                        integer("threshold", "Besuchsgrenze in Ticks", false, 600, 2000000000),
                                        integer("countdown", "Countdown: 0, 60, 180, 300", false, 0, 300),
                                        bool("backup-confirmed", "Externes Backup geprüft", false)),
                                sub("save", "Alle Welten sicher mit save-all flush speichern"),
                                sub("relay", "Chat-Relay schalten", choices("mode", "Zustand", true, "on", "Aktivieren", "off", "Deaktivieren")),
                                sub("fun", "Furz-/Rülpssystem verwalten",
                                        choices("feature", "System", true, "fart", "Furzbefehl", "burp", "Rülpsbefehl", "shared", "Gemeinsame Einstellungen"),
                                        choices("setting", "Einstellung", true,
                                                "status", "Status anzeigen", "enabled", "System aktiv", "minecraft-chat", "Minecraft-Chat",
                                                "discord-relay", "Discord-Relay", "sounds", "Sounds", "global-sounds", "Globale Soundreichweite",
                                                "particles", "Partikel", "variants", "Variantenwahl", "cooldown", "Cooldown", "clear-cooldowns", "Alle Cooldowns löschen"),
                                        string("value", "on/off oder Cooldown in Sekunden", false, true)),
                                sub("discord", "Discord-Bot verwalten", choices("action", "Aktion", true,
                                        "status", "Status", "refresh", "Status aktualisieren", "reset", "Statusnachricht neu setzen",
                                        "reconnect", "Neu verbinden", "sync", "Guild-Befehle synchronisieren")),
                                sub("suite", "/sas-Unterbefehl ausführen", string("command", "Unterbefehl ohne /sas", true, true)),
                                sub("console", "Konsolenbefehl ausführen", string("command", "Befehl gemäß Freigabe", true, false)),
                                sub("reload", "Suite neu laden")
                        )),
                        group("security", "Alt- und Identitätssicherheit", List.of(
                                sub("alt-link", "Zwei Konten manuell bestätigen", playerOption("player-one", true), playerOption("player-two", true)),
                                sub("alt-ignore", "Alt-Verknüpfung ignorieren", playerOption("player-one", true), playerOption("player-two", true)),
                                sub("alt-ban", "Alt-Konten bestrafen", playerOption("player", true),
                                        string("duration", "Dauer oder - für permanent", false, true), string("reason", "Englischer Grund", false, false)),
                                sub("ultraban", "Ultrabann ausführen", playerOption("player", true), string("reason", "Englischer Grund", false, false)),
                                sub("ultraunban", "Ultrabann-Gruppe freigeben", string("target", "Spieler oder Gruppen-ID", true, true)),
                                sub("identity-reset", "Identitätsdaten löschen", playerOption("player", true)),
                                sub("diagnostics", "Zusatzdiagnosen verwalten",
                                    choices("setting", "Einstellung", true, "status", "Status", "enabled", "Bedrock-Zusatzdaten", "cosmetics", "Skin/Profil-Metadaten", "party", "Party-Metadaten", "misc", "Editor/Filter-Metadaten", "session-refresh", "Sitzungsaktualisierung", "java-options", "Java-Einstellungen", "comparisons", "Manuelle Vergleiche"),
                                    choices("value", "Neuer Zustand", false, "on", "Aktivieren", "off", "Deaktivieren")),
                                sub("client", "Java-Clientprüfung verwalten",
                                        choices("operation", "Aktion", true,
                                                "status", "Status anzeigen", "on", "Kanalerfassung aktivieren", "off", "Kanalerfassung deaktivieren",
                                                "scan", "Java-Spieler neu erfassen"),
                                        playerOption("player", false)),
                                sub("vpn", "VPN-/Proxy-Schutz verwalten",
                                        choices("action", "Aktion", true,
                                                "status", "Status anzeigen", "enable", "Aktivieren", "disable", "Deaktivieren",
                                                "reload", "Neu laden", "recent", "Protokoll anzeigen",
                                                "whitelist-list", "Whitelist anzeigen", "whitelist-add", "Spieler hinzufügen",
                                                "whitelist-remove", "Spieler entfernen", "check", "IP prüfen",
                                                "cache-clear", "Cache leeren", "log-here", "Diesen Kanal nutzen",
                                                "log-create", "Privaten Protokollkanal erstellen",
                                                "log-disable", "Discord-Protokoll aus"),
                                        playerOption("player", false),
                                        string("ip", "IP für Provider-Prüfung", false, false))
                        )),
                        group("moderation", "Banns und Schutzregeln", List.of(
                            sub("status", "Banns und Einschränkungen"),
                            sub("banmode", "Bannart/Ultra-Gruppe ändern",
                                playerOption("player", true), choices("mode", "Bannart", true,
                                    "account", "Nur Account", "ultra", "Ultra-Bann", "silent", "Leiser Ultra-Bann", "ghost", "Ghost-Bann", "none", "Entbannen"),
                                string("reason", "Englischer Grund", false, false)),
                            sub("restrict", "Einschränkung anwenden",
                                playerOption("player", true), choices("preset", "Vorlage", true,
                                    "text", "Text sperren", "peaceful", "PvP/friedliche Ziele sperren", "text-peaceful", "Text und PvP sperren", "interactions", "Interaktionen sperren", "building", "Bauen sperren", "inventory", "Inventar sperren", "none", "Einschränkungen aufheben"),
                                string("duration", "30m, 2h, 7d oder permanent", false, false), bool("inherit", "Aktuelle/künftige Alts einbeziehen", false),
                                string("reason", "Englischer Grund", false, false)),
                            sub("devices", "Gerätemodelle verwalten",
                                choices("action", "Aktion", true, "list", "Anzeigen", "block", "Modell sperren", "unblock", "Modell freigeben", "info", "Betroffene Spieler"),
                                string("model", "Gerätemodell", false, true)),
                            sub("os", "Bedrock-Betriebssysteme",
                                choices("action","Aktion",true,"list","Anzeigen","block","Sperren","unblock","Freigeben","info","Betroffene Spieler"),string("os","Betriebssystemtyp",false,true)),
                            sub("audit", "Admin-Protokoll anzeigen",choices("category","Kategorie",false,"all","Alle","admin","Einstellungen","punishments","Bans und Mutes","bans","Bans","mutes","Stummschaltungen","vanish","Vanish","devices","Geräte")),
                            sub("logchannel", "Protokollkanal auswählen",
                                choices("type","Protokoll",true,"admin","Admin-Aktionen","punishments","Bans und Mutes","bans","Bans","mutes","Stummschaltungen","vanish","Vanish","devices","Gerätesperren","ai","KI-Moderation","ghost","Ghost-Bann","client-probes","Client-Prüfungen","alt-accounts","Neue Alt-Account-Hinweise","join-details","Private Beitrittsdaten"),
                                mapOf("type",7,"name","channel","description","Zielkanal im Server","required",true,"channel_types",List.of(0,5))),
                            sub("chatstyle", "Chatformatierung verwalten",
                                choices("kind", "Eigenschaft", true, "colors", "Farben", "formatting", "Formatierungen"), bool("allow", "Erlauben; false = sperren", true)),
                            sub("log", "Protokollkanal verwalten",
                                choices("type", "Protokoll", true, "admin", "Admin-Aktionen", "punishments", "Bans und Mutes", "bans", "Bans", "mutes", "Stummschaltungen", "vanish", "Vanish", "devices", "Gerätesperren", "ai", "KI-Moderation", "ghost", "Ghost-Bann", "client-probes", "Client-Prüfungen", "alt-accounts", "Neue Alt-Account-Hinweise", "join-details", "Private Beitrittsdaten"), string("channel", "Kanal-ID, off oder create", true, false)),
                            sub("config", "Suite-Einstellungen verwalten",
                                choices("action", "Aktion", true, "get", "Anzeigen", "set", "Ändern"), string("key", "Konfigurationspfad", true, true), string("value", "Wert; Listen mit | trennen", false, false))
                        )),
                        group("client", "Client-Prüfungen und Freigaben", List.of(
                            sub("scan","Online-Spieler mit Schildmethoden prüfen",playerOption("player",true),string("clients","all oder Client-IDs mit Komma",false,false)),
                            sub("status","Letzte Ergebnisse und Sperrstatus",playerOption("player",true)),
                            sub("approve","Client-Sperre und Team-Prüfung freigeben",playerOption("player",true)),
                            sub("queue","Ausstehende Team-Freigaben anzeigen"),
                            sub("channel","Client-Alarmkanal einrichten",string("channel","Kanal-ID, create oder off",true,false)),
                            sub("whitelist","Sanktionsausnahme je Spieler",playerOption("player",true),string("check","all oder eine Prüf-ID",true,true),choices("mode","Ausnahme",true,"on","Aktivieren","off","Entfernen")),
                            sub("rule","Client-Regel einstellen",string("client","Client aus der Liste auswählen",true,true),choices("action","Maßnahme",true,"none","Keine","notify","Alarm","kick","Kick","tempban","Zeitbann"),string("duration","Dauer, z. B. 15m",false,false),integer("review","Team-Freigabe nach Treffern (0 = aus)",false,0,100))
                        )),
                        group("settings", "Admin-Update: Einstellungen", List.of(
                                sub("list", "Einstellungen anzeigen"),
                                sub("set", "Admin-Einstellung ändern", string("key", "Konfigurationspfad", true, true), string("value", "Neuer Wert", true, false))
                        )),
                        group("ai", "Optionale Simplex-KI", List.of(
                                sub("status", "KI-Status anzeigen"),
                                sub("ask", "Serverassistenten fragen", string("question", "Frage mit Live-Serverkontext", true, false)),
                                sub("propose", "Adminaktion vorschlagen", string("request", "Gewünschte Adminaktion", true, false))
                        )),
                        sub("help", "Bedienung und Sicherheit")
                ));
        DiscordCommandSchema.compactDescriptions(command);
        commands.add(command);
        return commands;
    }

    private Map<String, Object> legacyCommand() {
        return mapOf("name", "ssmp", "description", "Manage the Simplex SMP Discord integration", "dm_permission", false,
                "options", List.of(sub("status", "Show the current server status"), sub("refresh", "Refresh the persistent status message"),
                        sub("reset", "Create a new persistent status message"), sub("relay-on", "Enable the Discord chat relay"),
                        sub("relay-off", "Disable the Discord chat relay"), sub("status-here", "Move the status message to this channel"),
                        sub("relay-here", "Use this channel for the Minecraft chat relay"), sub("sync", "Synchronize all guild commands")));
    }

    @SuppressWarnings("unchecked")
    static Request parseInteraction(Map<String, Object> data) {
        Object raw = data.get("options");
        if (!(raw instanceof List) || ((List<?>) raw).isEmpty() || !(((List<?>) raw).get(0) instanceof Map)) {
            return new Request("dashboard", Map.of());
        }
        Map<String, Object> first = (Map<String, Object>) ((List<?>) raw).get(0);
        String firstName = String.valueOf(first.getOrDefault("name", "dashboard"));
        int type = number(first.get("type"), 1);
        if (type == 2) {
            Object nested = first.get("options");
            if (!(nested instanceof List) || ((List<?>) nested).isEmpty() || !(((List<?>) nested).get(0) instanceof Map)) {
                return new Request(firstName, Map.of());
            }
            Map<String, Object> sub = (Map<String, Object>) ((List<?>) nested).get(0);
            return new Request(firstName + "." + sub.getOrDefault("name", "status"), optionValues(sub.get("options")));
        }
        return new Request(firstName, optionValues(first.get("options")));
    }

    static Request parseInline(String content, String configuredPrefix) {
        List<String> tokens = tokenize(content);
        if (tokens.isEmpty()) return null;
        String prefix = configuredPrefix == null || configuredPrefix.isBlank() ? "!sas" : configuredPrefix.trim();
        if (!tokens.get(0).equalsIgnoreCase(prefix)) return null;
        if (tokens.size() == 1 || tokens.get(1).equalsIgnoreCase("dashboard")) return new Request("dashboard", Map.of());
        String command = tokens.get(1).toLowerCase(Locale.ROOT);
        if (command.equals("status")) return new Request("server.status", Map.of());
        if (command.equals("player") && tokens.size() >= 3) return new Request("player.info", mapOf("player", tokens.get(2)));
        if (command.equals("alts") && tokens.size() >= 3) return new Request("player.alts", mapOf("player", tokens.get(2)));
        if (command.equals("live") && tokens.size() >= 3) return new Request("player.live", mapOf("player", tokens.get(2)));
        if (command.equals("history") && tokens.size() >= 3) return new Request("player.history", mapOf("player", tokens.get(2),
                "category", tokens.size() >= 4 ? tokens.get(3) : "all", "limit", 20));
        if (command.equals("rights") && tokens.size() >= 3) return new Request("player.rights", mapOf("player", tokens.get(2)));
        if (command.equals("identity") && tokens.size() >= 3) return new Request("player.identity", mapOf("player", tokens.get(2)));
        if (command.equals("devicediagnostics")) return new Request("security.diagnostics", mapOf("setting", tokens.size()>=3?tokens.get(2):"status", "value", tokens.size()>=4?tokens.get(3):""));
        if (command.equals("deviceinfo") && tokens.size()>=3) return new Request("player.devices", mapOf("player",tokens.get(2),"operation","info","identifier",tokens.size()>=4?tokens.get(3):"", "page", tokens.size()>=5?tokens.get(4):"1"));
        if (command.equals("devices") && tokens.size() >= 3) return new Request("player.devices", mapOf("player", tokens.get(2),
                "operation", tokens.size() >= 4 ? tokens.get(3) : "list", "identifier", tokens.size() >= 5 ? tokens.get(4) : "", "page", tokens.size()>=6?tokens.get(5):"1"));
        if (command.equals("snapshots") && tokens.size() >= 3) return new Request("player.snapshots", mapOf("player", tokens.get(2),
                "operation", tokens.size() >= 4 ? tokens.get(3) : "list", "file", tokens.size() >= 5 ? tokens.get(4) : ""));
        if (command.equals("points") && tokens.size() >= 3) return new Request("player.points", mapOf("player", tokens.get(2),
                "operation", tokens.size() >= 4 ? tokens.get(3) : "status", "amount", tokens.size() >= 5 ? number(tokens.get(4), 0) : 0));
        if (command.equals("message") && tokens.size() >= 4) return new Request("player.message", mapOf("player", tokens.get(2), "message", join(tokens, 3)));
        if ((command.equals("nickname") || command.equals("nick")) && tokens.size() >= 4) return new Request("player.nickname", mapOf("player", tokens.get(2), "nickname", join(tokens, 3)));
        if (command.equals("lag")) return new Request("server.lag", mapOf("action", tokens.size() >= 3 ? tokens.get(2) : "status"));
        if (command.equals("trim")) return new Request("server.trim", mapOf("action", tokens.size() >= 3 ? tokens.get(2) : "status"));
        if (command.equals("fun")) return new Request("server.fun", mapOf(
                "feature", tokens.size() >= 3 ? tokens.get(2) : "shared",
                "setting", tokens.size() >= 4 ? tokens.get(3) : "status",
                "value", tokens.size() >= 5 ? tokens.get(4) : ""));
        if (command.equals("vpn")) {
            String action = tokens.size() >= 3 ? tokens.get(2).toLowerCase(Locale.ROOT) : "status";
            String argument = tokens.size() >= 4 ? tokens.get(3) : "";
            return new Request("security.vpn", mapOf("action", action,
                    "player", action.equals("check") ? "" : argument,
                    "ip", action.equals("check") ? argument : ""));
        }
        if (command.equals("ai")) {
            String operation = tokens.size() >= 3 ? tokens.get(2).toLowerCase(Locale.ROOT) : "status";
            if (operation.equals("ask")) return new Request("ai.ask", mapOf("question", join(tokens, 3)));
            if (operation.equals("propose")) return new Request("ai.propose", mapOf("request", join(tokens, 3)));
            return new Request("ai.status", Map.of());
        }
        if (command.equals("console")) return new Request("server.console", mapOf("command", join(tokens, 2)));
        if (command.equals("run")) return new Request("server.suite", mapOf("command", join(tokens, 2)));
        return new Request("server.suite", mapOf("command", join(tokens, 1)));
    }

    boolean mutation(Request request) {
        if (request == null) return false;
        if (request.path.equals("client.status") || request.path.equals("client.queue")) return false;
        if (request.path.equals("settings.list") || request.path.equals("moderation.status")) return false;
        if ((request.path.equals("moderation.devices")||request.path.equals("moderation.os")) && Set.of("list","info").contains(request.text("action"))) return false;
        if(request.path.equals("moderation.audit"))return false;
        if (request.path.equals("moderation.config") && request.text("action").equals("get")) return false;
        if (request.path.startsWith("menu.")) return false;
        if (Set.of("dashboard", "help", "player.info", "player.alts", "player.live", "player.history",
                "player.rights", "player.identity", "server.status", "ai.status", "ai.ask", "ai.propose").contains(request.path)) return false;
        if (request.path.equals("player.notes") && request.text("operation").equals("list")) return false;
        if (request.path.equals("player.points") && request.text("operation").equals("status")) return false;
        if (request.path.equals("player.snapshots") && request.text("operation").equals("list")) return false;
        if (request.path.equals("player.devices") && Set.of("list","info","compare","history").contains(request.text("operation"))) return false;
        if (request.path.equals("security.diagnostics") && request.text("setting").equals("status")) return false;
        if (request.path.equals("server.restart") && request.text("action").equals("status")) return false;
        if (request.path.equals("server.lag") && Set.of("status", "results").contains(request.text("action"))) return false;
        if (request.path.equals("server.trim") && request.text("action").equals("status")) return false;
        if (request.path.equals("server.discord") && request.text("action").equals("status")) return false;
        if (request.path.equals("security.vpn") && Set.of("status", "recent", "whitelist-list", "check").contains(request.text("action"))) return false;
        if (request.path.equals("server.suite")) return !safeSuiteCommand(request.text("command"));
        return true;
    }

    boolean requiresConfirmation(Request request) {
        if (!mutation(request)) return false;
        if (request.path.equals("client.scan")) return false;
        if (request.path.equals("server.save") || request.path.equals("server.relay")) return false;
        if (request.path.equals("server.discord") && Set.of("refresh", "reconnect", "sync").contains(request.text("action"))) return false;
        if (request.path.equals("server.lag")) return false;
        if (request.path.equals("player.message")) return false;
        if (request.path.equals("player.snapshots") && request.text("operation").equals("create")) return false;
        if (request.path.equals("player.action") && Set.of("heal-feed", "clear-effects", "extinguish", "reset-movement", "sync-inventory", "snapshot")
                .contains(request.text("action"))) return false;
        return true;
    }

    String confirmationSummary(Request request) {
        if (request == null) return "Unbekannte Aktion";
        StringBuilder text = new StringBuilder("**Aktion:** `").append(request.path).append("`");
        for (Map.Entry<String, Object> entry : request.values.entrySet()) {
            if (entry.getKey().startsWith("_")) continue;
            String value = String.valueOf(entry.getValue());
            if (entry.getKey().equals("player") || entry.getKey().equals("player-one") || entry.getKey().equals("player-two")) {
                UUID resolved = plugin.discordAdminResolvePlayer(value);
                if (resolved != null) value = plugin.discordAdminPlayerName(resolved) + " (`" + resolved + "`)";
            }
            if (entry.getKey().contains("command")) value = limit(value, 300);
            text.append("\n**").append(entry.getKey()).append(":** ").append(value.isBlank() ? "-" : value);
        }
        return text.toString();
    }

    Reply execute(Request request, Actor actor) {
        if(request==null||actor==null)return Reply.error("Ungültige Discord-Anfrage.");
        Reply[] result=new Reply[1];plugin.asAdmin(actor.auditName(),()->result[0]=executeAction(request,actor));return result[0];
    }
    private Reply executeAction(Request request, Actor actor) {
        if (request == null || actor == null) return Reply.error("Ungültige Discord-Anfrage.");
        try {
            if (request.path.startsWith("menu.")) return dashboardSection(request.path.substring("menu.".length()));
            return switch (request.path) {
                case "client.whitelist" -> result("Prüf-Whitelist",plugin.discordAdminRunSuite(actor.id,actor.auditName(),words("checkwhitelist",request.text("player"),request.text("check"),request.text("mode"))),"Managing sanction exemptions");
                case "client.scan", "client.status", "client.approve", "client.queue", "client.channel", "client.rule" -> result("Client-Prüfung",plugin.qualityOfLife.probes.discord(actor.auditName(),request.path.substring(7),request.text("player"),request.path.equals("client.channel")?request.text("channel"):request.text("clients"),request.text("client"),request.text("action"),request.text("duration"),request.integer("review",0)),"Managing client verification");
                case "dashboard" -> dashboard();
                case "help" -> help();
                case "player.info" -> playerInfo(request.text("player"), request.bool("_private", false));
                case "player.alts" -> playerAlts(request.text("player"));
                case "player.action" -> result("Spieleraktion", plugin.discordAdminPower(actor.id, actor.auditName(), request.text("player"), request.text("action"), request.text("value")), "Moderating a Minecraft player");
                case "player.punish" -> executePunishment(request, actor);
                case "player.right" -> result("Spielerrecht aktualisiert", plugin.discordAdminRunSuite(actor.id, actor.auditName(),
                        words("recht", request.text("player"), request.text("right"),
                                request.text("mode").equals("block") ? "aus" : "an",
                                request.text("mode").equals("block") ? request.text("duration") : "")), "Updating player permissions");
                case "player.notes" -> executeNotes(request, actor);
                case "player.watchlist" -> result("Beobachtungsliste aktualisiert", plugin.discordAdminRunSuite(actor.id, actor.auditName(),
                        words("watchlist", request.text("operation"), request.text("player"), request.text("reason"))), "Reviewing the watchlist");
                case "player.live" -> playerView("Live-Sitzung", request.text("player"), plugin::discordAdminLiveSession, "Watching a Minecraft player");
                case "player.history" -> playerHistory(request);
                case "player.rights" -> playerView("Rechtematrix", request.text("player"), plugin::discordAdminRights, "Reviewing player permissions");
                case "player.points" -> result("Simplex Points", plugin.discordAdminPoints(actor.auditName(), request.text("player"),
                        request.text("operation"), request.integer("amount", 0)), "Managing Simplex Points");
                case "player.snapshots" -> result("Inventar-Snapshots", plugin.discordAdminSnapshots(actor.id, actor.auditName(),
                        request.text("player"), request.text("operation"), request.text("file")), "Managing inventory snapshots");
                case "player.identity" -> playerView("Identitätsakte", request.text("player"), plugin::discordAdminIdentity, "Reviewing identity security");
                case "player.channels" -> playerView("Java-Modkanäle", request.text("player"), plugin::discordAdminClientChannels, "Inspecting Java client channels");
                case "player.devices" -> result("Geräteverwaltung", plugin.discordAdminDevices(actor.id, actor.auditName(),
                        request.text("player"), request.text("operation"), request.text("identifier"), Math.max(0, request.integer("page",1)-1)), "Reviewing player devices");
                case "player.message" -> result("Teamnachricht", plugin.discordAdminPlayerMessage(actor.auditName(),
                        request.text("player"), request.text("message")), "Messaging a Minecraft player");
                case "player.nickname" -> result("Visueller Nickname", plugin.discordAdminNickname(actor.auditName(),
                        request.text("player"), request.text("nickname")), "Changing a visual player nickname");
                case "server.status" -> serverStatus();
                case "server.maintenance" -> result("Wartungsmodus", plugin.discordAdminRunSuite(actor.id, actor.auditName(),
                        words("maintenance", request.text("mode"), request.text("reason"))), "Managing server maintenance");
                case "server.chat" -> result("Globaler Chat", plugin.discordAdminRunSuite(actor.id, actor.auditName(), List.of("chat", request.text("mode"))), "Managing Minecraft chat");
                case "server.slowmode" -> result("Chat-Slowmode", plugin.discordAdminRunSuite(actor.id, actor.auditName(), List.of("slowmode", Integer.toString(request.integer("seconds", 0)))), "Managing Minecraft chat");
                case "server.command-lock" -> result("Befehls-Lockdown", plugin.discordAdminRunSuite(actor.id, actor.auditName(), List.of("commandlock", request.text("mode"))), "Protecting server commands");
                case "server.announce" -> result("Ankündigung gesendet", plugin.discordAdminBroadcast(actor.auditName(), request.text("message")), "Broadcasting to Minecraft");
                case "server.restart" -> result("Server-Neustart", plugin.discordAdminRestart(actor.auditName(), request.text("action"), request.integer("seconds", 30)), "Managing a server restart");
                case "server.lag" -> lag(request, actor);
                case "server.trim" -> trim(request, actor);
                case "server.save" -> result("Welten gespeichert", plugin.discordAdminSave(actor.auditName()), "Saving Minecraft worlds");
                case "server.relay" -> relay(request, actor);
                case "server.fun" -> result("Fun-System", plugin.discordAdminFun(actor.auditName(), request.text("feature"), request.text("setting"), request.text("value")), "Managing community fun features");
                case "server.discord" -> discord(request, actor);
                case "server.suite" -> suite(request, actor);
                case "moderation.status" -> result("Moderationsgruppen", plugin.controlModerationSummary(), "Reviewing server moderation");
                case "moderation.banmode" -> result("Bannart", plugin.discordAdminRunSuite(actor.id, actor.auditName(),
                    words("banmode", request.text("player"), request.text("mode"), defaultText(request.text("reason"), "Violation of server rules"))), "Managing ban groups");
                case "moderation.restrict" -> result("Einschränkungsvorlage", plugin.discordAdminRunSuite(actor.id, actor.auditName(),
                    words("restrict", request.text("player"), request.text("preset"), defaultText(request.text("duration"), "permanent"), Boolean.toString(request.bool("inherit", false)), defaultText(request.text("reason"), "Violation of server rules"))), "Managing player restrictions");
                case "moderation.devices" -> request.text("action").equals("info") ? result("Geräte & betroffene Spieler",plugin.moderationControl.deviceSummary(request.text("model"),false),"Inspecting device rules") : result("Bedrock-Gerätemodelle", plugin.discordAdminRunSuite(actor.id, actor.auditName(),
                    words("deviceblock", request.text("action"), request.text("model"))), "Managing device rules");
                case "moderation.os" -> request.text("action").equals("info") ? result("Betriebssystem & betroffene Spieler",plugin.moderationControl.deviceSummary(request.text("os"),true),"Inspecting device rules") : result("Betriebssystem",plugin.discordAdminRunSuite(actor.id,actor.auditName(),words("osblock",request.text("action"),request.text("os"))),"Managing device rules");
                case "moderation.audit" -> result("Admin-Aktionen",plugin.moderationControl.auditSummary(defaultText(request.text("category"),"all")),"Reviewing admin actions");
                case "moderation.logchannel" -> result("Protokollkanal",plugin.discordAdminRunSuite(actor.id,actor.auditName(),words("modlog",request.text("type"),request.text("channel"))),"Managing moderation logs");
                case "moderation.chatstyle" -> result("Globale Chatformatierung", plugin.discordAdminRunSuite(actor.id, actor.auditName(),
                    words("chatstyle", request.text("kind"), request.bool("allow", false) ? "on" : "off")), "Managing chat formatting");
                case "moderation.log" -> result("Moderationsprotokoll", plugin.discordAdminRunSuite(actor.id, actor.auditName(),
                    words("modlog", request.text("type"), request.text("channel"))), "Managing moderation logs");
                case "moderation.config" -> result("Suite-Einstellung", plugin.discordAdminRunSuite(actor.id, actor.auditName(),
                    words("suiteconfig", request.text("action"), request.text("key"), request.text("value"))), "Managing suite settings");
                case "settings.list" -> adminSettings(null, actor);
                case "settings.set" -> adminSettings(request, actor);
                case "server.console" -> result("Konsolenbefehl", plugin.discordAdminConsole(actor.id, actor.auditName(), request.text("command"), actor.discordAdministrator), "Running an approved server command");
                case "server.reload" -> result("Suite-Reload", plugin.discordAdminRunSuite(actor.id, actor.auditName(), List.of("reload")), "Reloading Simplex Admin Suite");
                case "security.alt-link" -> result("Alt-Verknüpfung bestätigt", plugin.discordAdminRunSuite(actor.id, actor.auditName(),
                        List.of("altlink", request.text("player-one"), request.text("player-two"))), "Linking verified accounts");
                case "security.alt-ignore" -> result("Alt-Verknüpfung ignoriert", plugin.discordAdminRunSuite(actor.id, actor.auditName(),
                        List.of("altignore", request.text("player-one"), request.text("player-two"))), "Reviewing account links");
                case "security.alt-ban" -> result("Alt-Kaskadenstrafe", plugin.discordAdminRunSuite(actor.id, actor.auditName(),
                        words("altban", request.text("player"), defaultText(request.text("duration"), "-"), defaultText(request.text("reason"), "Ban evasion / linked account"))), "Protecting against ban evasion");
                case "security.ultraban" -> result("Ultrabann", plugin.discordAdminRunSuite(actor.id, actor.auditName(),
                        words("ultraban", request.text("player"), defaultText(request.text("reason"), "Ban evasion / identity-wide ban"))), "Protecting against ban evasion");
                case "security.ultraunban" -> result("Ultrabann aufgehoben", plugin.discordAdminRunSuite(actor.id, actor.auditName(),
                        List.of("ultraunban", request.text("target"))), "Reviewing identity security");
                case "security.identity-reset" -> result("Identitätsdaten zurückgesetzt", plugin.discordAdminRunSuite(actor.id, actor.auditName(),
                        List.of("ultrareset", request.text("player"))), "Reviewing identity security");
                case "security.diagnostics" -> result("Gerätediagnose", plugin.setDiagnosticSetting(actor.auditName(),request.text("setting"),request.text("value")), "Geräteinformationen konfigurieren");
                case "security.client" -> result("Java-Clientschutz", plugin.discordAdminClientSecurity(actor.auditName(), request.text("operation"), request.text("player")), "Protecting against prohibited Java clients");
                case "security.vpn" -> result("VPN-/Proxy-Schutz", plugin.discordAdminVpn(actor.auditName(), request.text("action"),
                        request.text("player"), request.text("ip"), actor.channelId), "VPN-/Proxy-Schutz verwalten");
                case "ai.status" -> result("Simplex KI", plugin.discordAdminAiStatus(), "Reviewing optional AI safeguards");
                case "ai.ask" -> Reply.error("Diese KI-Anfrage muss über die asynchrone Discord-Pipeline verarbeitet werden.");
                case "ai.propose" -> result("KI-Adminvorschlag", plugin.discordAdminAiProposal(actor.auditName(), request.text("request")), "Preparing a confirmation-only AI proposal");
                default -> Reply.error("Diese Discord-Adminaktion ist nicht bekannt: `" + request.path + "`.");
            };
        } catch (Throwable error) {
            return Reply.error("Die Aktion wurde sicher abgebrochen: " + error.getClass().getSimpleName() + ": " + safe(error.getMessage()));
        }
    }

    Request requestFromComponent(String customId) {
        if ("sas:view:adminupdate".equals(customId)) return new Request("settings.list", Map.of());
        if (customId == null) return null;
        String[] split = customId.split(":", 5);
        if (split.length < 3 || !split[0].equals("sas")) return null;
        if (split[1].equals("menu") && DASHBOARD_PAGES.contains(split[2])) {
            return split[2].equals("home") ? new Request("dashboard", Map.of()) : new Request("menu." + split[2], Map.of());
        }
        if (split[1].equals("view")) {
            if (split[2].equals("dashboard")) return new Request("dashboard", Map.of());
            if (split[2].equals("modgroups")) return new Request("moderation.status", Map.of());
            if (split[2].equals("status")) return new Request("server.status", Map.of());
            if (split[2].equals("lag")) return new Request("server.lag", mapOf("action", "results"));
            if (split[2].equals("trim")) return new Request("server.trim", mapOf("action", "status"));
            if (split[2].equals("vpn")) return new Request("security.vpn", mapOf("action", "status"));
            if (split[2].equals("discord")) return new Request("server.discord", mapOf("action", "status"));
            if (split[2].equals("ai")) return new Request("ai.status", Map.of());
            if (split[2].equals("help")) return new Request("help", Map.of());
            if (split[2].equals("operations")) return new Request("menu.operations", Map.of());
            if (split.length >= 4 && split[2].equals("player")) return new Request("player.info", mapOf("player", split[3]));
            if (split.length >= 4 && split[2].equals("alts")) return new Request("player.alts", mapOf("player", split[3]));
            if (split.length >= 4 && split[2].equals("live")) return new Request("player.live", mapOf("player", split[3]));
            if (split.length >= 4 && split[2].equals("history")) return new Request("player.history", mapOf("player", split[3], "category", "all", "limit", 20));
            if (split.length >= 4 && split[2].equals("rights")) return new Request("player.rights", mapOf("player", split[3]));
            if (split.length >= 4 && split[2].equals("identity")) return new Request("player.identity", mapOf("player", split[3]));
            if (split.length >= 4 && split[2].equals("snapshots")) return new Request("player.snapshots", mapOf("player", split[3], "operation", "list"));
            if (split.length >= 4 && split[2].equals("devices")) return new Request("player.devices", mapOf("player", split[3], "operation", "list"));
        }
        if (split[1].equals("quick") && split.length >= 4) {
            if (split[2].startsWith("lag-")) return new Request("server.lag", mapOf("action", split[2].substring(4)));
            if (split[2].startsWith("trim-")) return new Request("server.trim", mapOf("action", split[2].substring(5)));
            if (split[2].equals("maintenance-toggle")) return new Request("server.maintenance",
                    mapOf("mode", plugin.qolMaintenanceActive() ? "off" : "on", "reason", "Maintenance from Discord dashboard"));
            if (split[2].equals("chat-toggle")) return new Request("server.chat",
                    mapOf("mode", plugin.qolGlobalChatEnabled() ? "off" : "on"));
            if (split[2].equals("commandlock-toggle")) return new Request("server.command-lock",
                    mapOf("mode", plugin.qolCommandLockdownEnabled() ? "off" : "on"));
            if (split[2].equals("relay-toggle")) return new Request("server.relay",
                    mapOf("mode", bot.relayEnabled() ? "off" : "on"));
            if (split[2].equals("save-worlds")) return new Request("server.save", Map.of());
            if (split[2].equals("restart-status")) return new Request("server.restart", mapOf("action", "status"));
            if (split[2].equals("discord-refresh")) return new Request("server.discord", mapOf("action", "refresh"));
            if (split[2].equals("suite-reload")) return new Request("server.reload", Map.of());
            return new Request("player.action", mapOf("action", split[2], "player", split[3]));
        }
        return null;
    }

    List<Choice> autocomplete(Request request, String focusedName, String query) {
        if (focusedName == null) return List.of();
        if (focusedName.equals("player") || focusedName.equals("player-one") || focusedName.equals("player-two") || focusedName.equals("target")) {
            if (focusedName.equals("player") && request != null && request.path.equals("security.vpn")
                    && request.text("action").equals("whitelist-remove"))
                return plugin.discordAdminVpnWhitelistChoices(query, 25);
            return plugin.discordAdminPlayerChoices(query, 25);
        }
        if(focusedName.equals("check")&&request!=null&&request.path.equals("client.whitelist")&&plugin.qualityOfLife!=null){List<String> keys=new ArrayList<>(List.of("all","channel-fingerprint","anti-detection","autoclicker","freecam"));keys.addAll(plugin.qualityOfLife.probes.definitionIds());return keys.stream().filter(k->k.contains(lower(query))).limit(25).map(k->new Choice(k,k)).toList();}
        if(focusedName.equals("client")&&request!=null&&request.path.equals("client.rule")&&plugin.qualityOfLife!=null)return plugin.qualityOfLife.probes.definitions().stream().filter(d->(d.name()+" "+d.id()).toLowerCase(Locale.ROOT).contains(lower(query))).limit(25).map(d->new Choice(d.name(),d.id())).toList();
        if (focusedName.equals("os") && plugin.moderationControl != null) return plugin.moderationControl.osChoices(query);
        if (focusedName.equals("model") && plugin.moderationControl != null) return plugin.moderationControl.modelChoices(query);
        if (focusedName.equals("key") && request != null && request.path.equals("moderation.config") && plugin.moderationControl != null)
            return plugin.moderationControl.configKeys().stream().filter(k -> k.length()<=100 && k.toLowerCase(Locale.ROOT).contains(lower(query))).limit(25).map(k -> new Choice(k,k)).toList();
        if (focusedName.equals("key")) {
            List<Choice> choices = new ArrayList<>();
            for (AdminUpdateMenu.Setting setting : AdminUpdateMenu.SETTINGS)
                if (!setting.group().startsWith("Konsole") && setting.path().toLowerCase(Locale.ROOT).contains(lower(query))) choices.add(new Choice(setting.title(),setting.path()));
            return limitChoices(choices);
        }
        if (focusedName.equals("right")) {
            List<Choice> choices = new ArrayList<>();
            String needle = lower(query);
            for (String right : plugin.powerRights()) if (right.contains(needle)) choices.add(new Choice(right, right));
            return limitChoices(choices);
        }
        if (focusedName.equals("duration")) {
            return List.of(new Choice("30 minutes", "30m"), new Choice("6 hours", "6h"), new Choice("1 day", "1d"),
                    new Choice("7 days", "7d"), new Choice("2 weeks", "2w"), new Choice("Permanent", "-"));
        }
        if (focusedName.equals("targets")) return plugin.discordAdminTrimChoices(query, 25);
        if (focusedName.equals("file") && request != null) return plugin.discordAdminSnapshotChoices(request.text("player"), query, 25);
        if (focusedName.equals("identifier") && request != null) return plugin.discordAdminDeviceChoices(request.text("player"), query, 25);
        if (focusedName.equals("value") && request != null) {
            String action = request.text("action");
            if (action.equals("gamemode")) return List.of(new Choice("Survival", "survival"), new Choice("Creative", "creative"),
                    new Choice("Adventure", "adventure"), new Choice("Spectator", "spectator"));
            if (action.equals("personal-slowmode")) return List.of(new Choice("Disabled", "0"), new Choice("2 seconds", "2"),
                    new Choice("5 seconds", "5"), new Choice("10 seconds", "10"), new Choice("30 seconds", "30"), new Choice("60 seconds", "60"));
            if (action.equals("alt-minimum-score")) return List.of(new Choice("Balanced • 70", "70"), new Choice("Strict • 85", "85"),
                    new Choice("Very strict • 95", "95"), new Choice("Exact • 100", "100"));
        }
        if (focusedName.equals("command")) {
            List<Choice> choices = new ArrayList<>();
            String needle = lower(query);
            for (String suggestion : suiteSuggestions(request,query)) if (lower(suggestion).contains(needle)) choices.add(new Choice(suggestion, suggestion));
            return limitChoices(choices);
        }
        return List.of();
    }

    private List<String> suiteSuggestions(Request request,String query){
        List<String> result=new ArrayList<>(SUITE_SUGGESTIONS);
        for(String root:ALLOWED_SUITE_ROOTS.stream().sorted().toList())if(!result.contains(root))result.add(root);
        if(plugin!=null&&plugin.moderationControl!=null){
            String[] words=query.trim().split("\\s+",3);
            if(words.length>=2&&Set.of("deviceblock","osblock").contains(words[0])){
                String search=words.length==3?words[2]:"";
                var matches=words[0].equals("deviceblock")?plugin.moderationControl.modelChoices(search):plugin.moderationControl.osChoices(search);
                result.clear();for(Choice c:matches){String command=words[0]+" "+words[1]+" "+c.value;if(command.length()<=100)result.add(command);}
            }
        }
        return result;
    }
    private Reply dashboard() {
        String description = plugin.discordAdminDashboard();
        List<Map<String, Object>> rows = List.of(
                navigationRow("home"),
                row(button(1, "Spieler suchen", "sas:modal:player", "👥"), button(1, "Moderation", "sas:menu:moderation", "🔨"),
                        button(1, "Server-Steuerung", "sas:menu:server", "🖥️"), button(1, "Sicherheit", "sas:menu:security", "🛡️")),
                row(button(2, "Live-Status", "sas:view:status", "📊"), button(2, "Suite-Einstellungen", "sas:view:adminupdate", "⚙️"),
                        button(2, "Serverkonsole", "sas:modal:console", "⌨️"), button(2, "Hilfe", "sas:view:help", "❓")));
        return new Reply("Simplex Admin • Discord-Kommandozentrale", description, 0x5865F2, rows, "Managing the Minecraft server");
    }

    private Map<String, Object> navigationRow(String currentPage) {
        List<Map<String, Object>> options = List.of(
                navigationOption("Dashboard", "home", "Gesamte Kommandozentrale", "🧭", currentPage.equals("home")),
                navigationOption("Spieler & Akten", "players", "Online, Datenbank, Akten und Spieleraktionen", "👥", currentPage.equals("players")),
                navigationOption("Moderation", "moderation", "Reports, Strafen, Rechte und Watchlist", "🔨", currentPage.equals("moderation")),
                navigationOption("Server", "server", "Chat, Wartung, Neustart und Steuerung", "🖥️", currentPage.equals("server")),
                navigationOption("Welten", "worlds", "Welten, Gamerules, Warps, Scoreboard und Trimmer", "🌍", currentPage.equals("worlds")),
                navigationOption("Sicherheit", "security", "Alt-Erkennung, Identitäten, Clients und VPN", "🛡️", currentPage.equals("security")),
                navigationOption("Kommunikation", "communication", "Discord, Relay, Staff und Ankündigungen", "💬", currentPage.equals("communication")),
                navigationOption("Community", "community", "Regeln, Umfragen, Points und Fun", "🎉", currentPage.equals("community")),
                navigationOption("Analyse", "analytics", "Aktivität, Spielerdaten, Lag und Diagnosen", "📈", currentPage.equals("analytics")),
                navigationOption("Admin-QoL", "qol", "Aufgaben, Planer, Incidents, Health und Makros", "🧰", currentPage.equals("qol")),
                navigationOption("Integrationen", "integrations", "Floodgate, Sentinel, Discord, KI und Plugins", "🔌", currentPage.equals("integrations")),
                navigationOption("Einsatzleitstelle", "operations", "Zentrale Live-Steuerung und Notfallaktionen", "🚨", currentPage.equals("operations")));
        Map<String, Object> menu = mapOf("type", 3, "custom_id", "sas:navigate",
                "placeholder", "Admin-Bereich auswählen …", "min_values", 1, "max_values", 1, "options", options);
        return row(menu);
    }

    private static Map<String, Object> navigationOption(String label, String value, String description, String emoji, boolean selected) {
        Map<String, Object> option = mapOf("label", limit(label, 100), "value", value, "description", limit(description, 100));
        if (emoji != null && !emoji.isBlank()) option.put("emoji", mapOf("name", emoji));
        if (selected) option.put("default", true);
        return option;
    }

    private static String dashboardSectionTitle(String page) {
        return switch (page) {
            case "players" -> "Discord-Menü • Spieler & Akten";
            case "moderation" -> "Discord-Menü • Moderation";
            case "server" -> "Discord-Menü • Server-Steuerung";
            case "worlds" -> "Discord-Menü • Weltenverwaltung";
            case "security" -> "Discord-Menü • Sicherheit";
            case "communication" -> "Discord-Menü • Kommunikation";
            case "community" -> "Discord-Menü • Community";
            case "analytics" -> "Discord-Menü • Analyse & Daten";
            case "qol" -> "Discord-Menü • Admin-QoL";
            case "integrations" -> "Discord-Menü • Integrationen";
            default -> "Discord-Menü • Einsatzleitstelle";
        };
    }

    private static int dashboardSectionColor(String page) {
        return switch (page) {
            case "moderation" -> 0xED4245;
            case "security" -> 0xE67E22;
            case "worlds", "community" -> 0x57F287;
            case "analytics", "qol" -> 0xFEE75C;
            case "communication", "integrations" -> 0x5865F2;
            default -> 0x3498DB;
        };
    }

    private Reply dashboardSection(String requestedPage) {
        String page = DASHBOARD_PAGES.contains(requestedPage) && !requestedPage.equals("home") ? requestedPage : "operations";
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(navigationRow(page));
        switch (page) {
            case "players" -> {
                rows.add(row(button(1, "Spielerakte öffnen", "sas:modal:player", "🔍"), button(1, "Online-Spieler", "sas:view:status", "👥"),
                        button(2, "Moderation", "sas:menu:moderation", "🔨"), button(2, "Alt-Sicherheit", "sas:menu:security", "🧬"),
                        button(2, "Analyse", "sas:menu:analytics", "📈")));
                rows.add(row(button(2, "Spielerbefehl", "sas:modal:suite:players", "⌨️"), button(2, "Einsatzleitstelle", "sas:menu:operations", "🚨"),
                        button(2, "Community", "sas:menu:community", "🎉"), button(2, "Dashboard", "sas:menu:home", "🧭")));
            }
            case "moderation" -> {
                rows.add(row(button(1, "Spieler auswählen", "sas:modal:player", "👤"), button(1, "Moderationsbefehl", "sas:modal:suite:moderation", "⌨️"),
                        button(2, "Sicherheit", "sas:menu:security", "🛡️"), button(2, "Einsatzleitstelle", "sas:menu:operations", "🚨"),
                        button(2, "Kommunikation", "sas:menu:communication", "💬")));
                rows.add(row(button(1, "Live-Status", "sas:view:status", "📊"), button(1, "Bannarten & Gruppen", "sas:view:modgroups", "👻"), button(2, "Dashboard", "sas:menu:home", "🧭")));
            }
            case "server" -> {
                rows.add(row(button(1, "Live-Status", "sas:view:status", "📊"), button(3, "Wartung umschalten", "sas:quick:maintenance-toggle:server", "🚧"),
                        button(3, "Chat umschalten", "sas:quick:chat-toggle:server", "💬"), button(3, "Command-Lock", "sas:quick:commandlock-toggle:server", "🔒"),
                        button(1, "Welten speichern", "sas:quick:save-worlds:server", "💾")));
                rows.add(row(button(2, "Neustartstatus", "sas:quick:restart-status:server", "🔄"), button(2, "Lag-Inspector", "sas:view:lag", "🔎"),
                        button(2, "Trimmer", "sas:view:trim", "🛠️"), button(2, "Discord-Bot", "sas:view:discord", "🤖"),
                        button(2, "Serverbefehl", "sas:modal:suite:server", "⌨️")));
                rows.add(row(button(2, "Einsatzleitstelle", "sas:menu:operations", "🚨"), button(2, "Dashboard", "sas:menu:home", "🧭")));
            }
            case "worlds" -> {
                rows.add(row(button(1, "Serverstatus", "sas:view:status", "📊"), button(2, "Welten-Trimmer", "sas:view:trim", "🛠️"),
                        button(1, "Welten speichern", "sas:quick:save-worlds:server", "💾"), button(2, "Weltenbefehl", "sas:modal:suite:worlds", "⌨️"),
                        button(2, "Analyse", "sas:menu:analytics", "📈")));
                rows.add(row(button(2, "Integrationen", "sas:menu:integrations", "🔌"), button(2, "Server", "sas:menu:server", "🖥️"),
                        button(2, "Dashboard", "sas:menu:home", "🧭")));
            }
            case "security" -> {
                rows.add(row(button(1, "VPN-/Proxy-Schutz", "sas:view:vpn", "🛡️"), button(1, "Spieleridentität", "sas:modal:player", "🧬"),
                        button(2, "Sicherheitsbefehl", "sas:modal:suite:security", "⌨️"), button(2, "Integrationen", "sas:menu:integrations", "🔌"),
                        button(2, "Einsatzleitstelle", "sas:menu:operations", "🚨")));
                rows.add(row(button(2, "Moderation", "sas:menu:moderation", "🔨"), button(2, "Dashboard", "sas:menu:home", "🧭")));
            }
            case "communication" -> {
                rows.add(row(button(1, "Discord-Bot", "sas:view:discord", "🤖"), button(3, "Relay umschalten", "sas:quick:relay-toggle:server", "🔁"),
                        button(2, "Kommunikationsbefehl", "sas:modal:suite:communication", "⌨️"), button(2, "Community", "sas:menu:community", "🎉"),
                        button(2, "Einsatzleitstelle", "sas:menu:operations", "🚨")));
                rows.add(row(button(1, "Live-Status", "sas:view:status", "📊"), button(2, "Dashboard", "sas:menu:home", "🧭")));
            }
            case "community" -> {
                rows.add(row(button(2, "Community-Befehl", "sas:modal:suite:community", "⌨️"), button(1, "Spielerakte", "sas:modal:player", "👤"),
                        button(2, "Kommunikation", "sas:menu:communication", "💬"), button(2, "Analyse", "sas:menu:analytics", "📈"),
                        button(2, "Dashboard", "sas:menu:home", "🧭")));
            }
            case "analytics" -> {
                rows.add(row(button(1, "Live-Status", "sas:view:status", "📊"), button(2, "Lag-Inspector", "sas:view:lag", "🔎"),
                        button(1, "Spieler analysieren", "sas:modal:player", "👤"), button(2, "Einsatzleitstelle", "sas:menu:operations", "🚨"),
                        button(2, "Admin-QoL", "sas:menu:qol", "🧰")));
                rows.add(row(button(2, "Welten", "sas:menu:worlds", "🌍"), button(2, "Sicherheit", "sas:menu:security", "🛡️"),
                        button(2, "Dashboard", "sas:menu:home", "🧭")));
            }
            case "qol" -> {
                rows.add(row(button(1, "Live-Status", "sas:view:status", "📊"), button(2, "Einsatzleitstelle", "sas:menu:operations", "🚨"),
                        button(2, "Analyse", "sas:menu:analytics", "📈"), button(2, "QoL-Befehl", "sas:modal:suite:qol", "⌨️"),
                        button(2, "Dashboard", "sas:menu:home", "🧭")));
            }
            case "integrations" -> {
                rows.add(row(button(1, "Discord-Bot", "sas:view:discord", "🤖"), button(1, "VPN-Schutz", "sas:view:vpn", "🛡️"),
                        button(1, "Simplex KI", "sas:view:ai", "🤖"), button(2, "Sicherheit", "sas:menu:security", "🔐"),
                        button(2, "Integrationsbefehl", "sas:modal:suite:integrations", "⌨️")));
                rows.add(row(button(1, "Live-Status", "sas:view:status", "📊"), button(2, "Welten", "sas:menu:worlds", "🌍"),
                        button(2, "Dashboard", "sas:menu:home", "🧭")));
            }
            default -> {
                rows.add(row(button(1, "Live-Status", "sas:view:status", "📊"), button(2, "Lag-Inspector", "sas:view:lag", "🔎"),
                        button(2, "Trimmer", "sas:view:trim", "🛠️"), button(1, "Welten speichern", "sas:quick:save-worlds:server", "💾"),
                        button(1, "Discord aktualisieren", "sas:quick:discord-refresh:server", "🔄")));
                rows.add(row(button(3, "Wartung umschalten", "sas:quick:maintenance-toggle:server", "🚧"),
                        button(3, "Command-Lock", "sas:quick:commandlock-toggle:server", "🔒"), button(3, "Relay umschalten", "sas:quick:relay-toggle:server", "🔁"),
                        button(2, "Leitstellenbefehl", "sas:modal:suite:operations", "⌨️"), button(2, "Dashboard", "sas:menu:home", "🧭")));
            }
        }
        return new Reply(dashboardSectionTitle(page), plugin.discordAdminDashboardSection(page), dashboardSectionColor(page), rows,
                "Navigating the Simplex Admin dashboard");
    }

    private Reply adminSettings(Request request, Actor actor) {
        if (request == null) {
            StringBuilder text = new StringBuilder("Ändern: `/sasadmin settings set` • Lokales Menü: `/sas adminupdate`\n");
            for (AdminUpdateMenu.Setting s : AdminUpdateMenu.SETTINGS) if(!s.group().startsWith("Konsole"))
                text.append("\n**").append(s.title()).append(":** ").append(plugin.getConfig().get(s.path()));
            return Reply.ok("Suite-Einstellungen • Einstellungen",text.toString(),"Reviewing server settings");
        }
        if(!actor.discordAdministrator)return Reply.error("Nur Discord-Administratoren dürfen diese Einstellungen ändern.");
        if(request.text("key").equals("prefix")||request.text("key").startsWith("branding."))return Reply.error("Branding und Präfix bitte als Hauptadmin mit /sas branding im Spiel ändern.");
        for(AdminUpdateMenu.Setting s:AdminUpdateMenu.SETTINGS) if(s.path().equals(request.text("key"))&&!s.group().startsWith("Konsole")){
            Object value=request.text("value");
            if(s.kind().equals("bool")){
                if(!Set.of("true","false").contains(value))return Reply.error("Erlaubt: true oder false");value=Boolean.valueOf(value.toString());
            } else if(s.kind().equals("int")){
                int n;try{n=Integer.parseInt(value.toString());}catch(NumberFormatException e){return Reply.error("Ganzzahl erforderlich.");}
                if(n<s.min()||n>s.max())return Reply.error("Erlaubt: "+s.min()+" bis "+s.max());value=n;
            }else if(value.toString().length()>500)return Reply.error("Maximal 500 Zeichen.");
            if(s.path().endsWith("java-url")&&!value.toString().startsWith("https://"))return Reply.error("HTTPS-Adresse erforderlich.");
            plugin.getConfig().set(s.path(),value);plugin.saveConfig();plugin.adminRuntime.settingsChanged();
            if(s.path().contains("avatars.")||s.path().endsWith("player-webhooks"))bot.reloadPlayerRelay();
            plugin.expansionAudit(actor.auditName(),null,"Discord-Einstellung",s.path()+"="+value);
            return Reply.ok("Einstellung gespeichert",s.title()+": "+value,"Updating server settings");
        }
        return Reply.error("Unbekannte oder ausschließlich lokal verwaltbare Einstellung.");
    }

    private Reply help() {
        return new Reply("Discord-Adminhilfe",
                "Nutze `/sasadmin dashboard` für die vollständige navigierbare Kommandozentrale, `/sasadmin` für native Slash-Commands oder den konfigurierten Inline-Präfix `!sas`."
                        + "\n\n• Das Auswahlmenü führt durch Spieler, Moderation, Server, Welten, Sicherheit, Kommunikation, Community, Analyse, Admin-QoL, Integrationen und Einsatzleitstelle."
                        + "\n• Schnellaktionen arbeiten direkt mit denselben geprüften Suite-Diensten wie Java- und Bedrock-Menüs."
                        + "\n• Der Button `Suite-Befehl` öffnet eine geschützte Eingabe und akzeptiert ausschließlich freigegebene `/sas`-Unterbefehle."
                        + "\n\n• Spielerfelder verwenden Autocomplete aus der gesamten Suite-Datenbank."
                        + "\n• Änderungen laufen auf dem Minecraft-Serverthread."
                        + "\n• Kritische Aktionen sind an deinen Discord-Account gebunden und müssen bestätigt werden."
                        + "\n• Administratoren haben Vollzugriff; konfigurierte Rollen können Voll- oder Lesezugriff erhalten."
                        + "\n• Konsolenbefehle sind standardmäßig aus. Der Hauptadmin kann vollen Zugriff für Discord-Administratoren freigeben; /sasadmin server console liefert privates Live-Feedback.",
                0x5865F2, List.of(row(button(1, "Admin-Dashboard", "sas:view:dashboard", "🧭"))), "Helping server administrators");
    }

    private Reply serverStatus() {
        return new Reply("Live-Serverstatus", plugin.discordAdminServerStatus(), 0x3498DB,
                List.of(row(button(1, "Aktualisieren", "sas:view:status", "🔄"), button(1, "Lag-Hotspots", "sas:view:lag", "🔎"), button(1, "Trimmer", "sas:view:trim", "🛠️"),
                        button(2, "Admin-Dashboard", "sas:view:dashboard", "🧭"))),
                "Watching Minecraft server health");
    }

    private Reply playerInfo(String query, boolean privateResponse) {
        UUID uuid = plugin.discordAdminResolvePlayer(query);
        if (uuid == null) return Reply.error("Spielerprofil wurde nicht gefunden.");
        String description = plugin.discordAdminPlayerInfo(uuid, privateResponse
                && plugin.getConfig().getBoolean("discord-bot.admin.include-sensitive-identifiers", false));
        List<Map<String, Object>> rows = List.of(
                row(button(1, "Alternative Accounts", "sas:view:alts:" + uuid, "🧬"), button(1, "Live-Sitzung", "sas:view:live:" + uuid, "📡"),
                        button(1, "Verlauf", "sas:view:history:" + uuid, "📜"), button(1, "Rechte", "sas:view:rights:" + uuid, "🛡️")),
                row(button(2, "Identität", "sas:view:identity:" + uuid, "🔐"), button(2, "Snapshots", "sas:view:snapshots:" + uuid, "📦"),
                        button(2, "Geräte", "sas:view:devices:" + uuid, "💻"), button(2, "Aktualisieren", "sas:view:player:" + uuid, "🔄")),
                row(button(2, "Heilen", "sas:quick:heal-feed:" + uuid, "💚"), button(2, "Freeze", "sas:quick:freeze-toggle:" + uuid, "🧊"),
                        button(4, "Quarantäne", "sas:quick:quarantine-toggle:" + uuid, "🔒")));
        return new Reply("Spielerakte • " + plugin.discordAdminPlayerName(uuid), description, 0x5865F2, rows, "Reviewing a Minecraft player");
    }

    private Reply playerAlts(String query) {
        UUID uuid = plugin.discordAdminResolvePlayer(query);
        if (uuid == null) return Reply.error("Spielerprofil wurde nicht gefunden.");
        List<AltView> matches = plugin.discordAdminAltViews(uuid, 20);
        List<Map<String, Object>> rows = new ArrayList<>();
        List<Map<String, Object>> current = new ArrayList<>();
        for (int i = 0; i < Math.min(20, matches.size()); i++) {
            AltView match = matches.get(i);
            current.add(button(match.score >= 95 ? 4 : match.score >= 70 ? 3 : 2,
                    limit(match.name + " • " + match.score, 80), "sas:view:player:" + match.uuid, match.score >= 95 ? "🚨" : "🔎"));
            if (current.size() == 5) { rows.add(rowFrom(current)); current.clear(); }
        }
        if (!current.isEmpty()) rows.add(rowFrom(current));
        rows.add(row(button(2, "Zur Spielerakte", "sas:view:player:" + uuid, "↩️")));
        return new Reply("Alternative Accounts • " + plugin.discordAdminPlayerName(uuid), plugin.discordAdminAltInfo(uuid),
                matches.stream().anyMatch(match -> match.score >= 95) ? 0xED4245 : 0xFEE75C, rows, "Inspecting linked Minecraft accounts");
    }

    private Reply playerView(String title, String query, java.util.function.Function<UUID, String> content, String activity) {
        UUID uuid = plugin.discordAdminResolvePlayer(query);
        if (uuid == null) return Reply.error("Spielerprofil wurde nicht gefunden.");
        return new Reply(title + " • " + plugin.discordAdminPlayerName(uuid), content.apply(uuid), 0x5865F2,
                List.of(row(button(2, "Zur Spielerakte", "sas:view:player:" + uuid, "↩️"))), activity);
    }

    private Reply playerHistory(Request request) {
        UUID uuid = plugin.discordAdminResolvePlayer(request.text("player"));
        if (uuid == null) return Reply.error("Spielerprofil wurde nicht gefunden.");
        String category = defaultText(request.text("category"), "all");
        int limit = Math.max(1, Math.min(40, request.integer("limit", 20)));
        return new Reply("Spielerverlauf • " + plugin.discordAdminPlayerName(uuid),
                plugin.discordAdminHistory(uuid, category, limit), 0x3498DB,
                List.of(row(button(2, "Zur Spielerakte", "sas:view:player:" + uuid, "↩️"),
                        button(2, "Aktualisieren", "sas:view:history:" + uuid, "🔄"))), "Reviewing player history");
    }

    private Reply executePunishment(Request request, Actor actor) {
        String type = request.text("type");
        List<String> command = new ArrayList<>();
        command.add(type);
        command.add(request.text("player"));
        if (type.equals("tempban") || type.equals("tempmute")) command.add(defaultText(request.text("duration"), "1d"));
        String reason = defaultText(request.text("reason"), "No reason provided");
        if (!type.equals("unban") && !type.equals("unmute") && !type.equals("clearwarnings")) command.addAll(tokenizeArgument(reason));
        return result("Strafaktion • " + type, plugin.discordAdminRunSuite(actor.id, actor.auditName(), command), "Moderating a Minecraft player");
    }

    private Reply executeNotes(Request request, Actor actor) {
        List<String> command = words("notes", request.text("player"), request.text("operation"), request.text("text"));
        return result("Teamnotizen", plugin.discordAdminRunSuite(actor.id, actor.auditName(), command), "Reviewing staff notes");
    }

    private Reply lag(Request request, Actor actor) {
        String action = defaultText(request.text("action"), "status");
        String text = plugin.discordAdminLag(actor.id, actor.auditName(), action);
        List<Map<String, Object>> rows = List.of(row(button(1, "Aktualisieren", "sas:view:lag", "🔄"),
                button(3, "Scan starten", "sas:quick:lag-start:server", "▶️"), button(4, "Scan abbrechen", "sas:quick:lag-cancel:server", "⏹️")));
        return new Reply("Lag-Inspector", text, text.contains("Kritisch") ? 0xED4245 : 0xFEE75C, rows, action.equals("start") ? "Scanning loaded chunks for lag" : "Reviewing server lag hotspots");
    }

    private Reply trim(Request request, Actor actor) {
        String action = defaultText(request.text("action"), "status");
        if (action.equals("start") && !request.bool("backup-confirmed", false)) {
            return Reply.error("Der Trimmer wurde nicht gestartet: `backup-confirmed` muss nach Prüfung eines externen Backups ausdrücklich `true` sein.");
        }
        String text = plugin.discordAdminTrim(actor.id, actor.auditName(), action, request.text("targets"),
                request.integer("threshold", 1200), normalizeCountdown(request.integer("countdown", 180)));
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row(button(1, "Aktualisieren", "sas:view:trim", "🔄"), button(4, "Countdown abbrechen", "sas:quick:trim-cancel:server", "⏹️")));
        WorldTrimManager.TrimStatus status = plugin.worldTrimStatus();
        if (status.phase == WorldTrimManager.Phase.AWAITING_OVERRIDE) {
            rows.add(row(button(4, "Sicher abbrechen", "sas:quick:trim-abort:server", "🛑"), button(3, "Notfall-Bypass", "sas:quick:trim-continue:server", "⚠️")));
        } else if (status.phase == WorldTrimManager.Phase.RECOVERY) {
            rows.add(row(button(4, "Recovery nach Prüfung freigeben", "sas:quick:trim-recovery:server", "🧯")));
        }
        return new Reply("Welten-Trimmer", text, status.phase == WorldTrimManager.Phase.RUNNING ? 0x9B59B6 : 0xFEE75C, rows,
                status.phase == WorldTrimManager.Phase.RUNNING ? "Trimming Minecraft worlds" : "Managing world maintenance");
    }

    private Reply relay(Request request, Actor actor) {
        boolean enabled = request.text("mode").equals("on");
        bot.setRelayEnabled(actor.auditName(), enabled);
        return result("Discord-Relay", "Chat-Relay wurde " + (enabled ? "aktiviert" : "deaktiviert") + ".", "Managing the Discord relay");
    }

    private Reply discord(Request request, Actor actor) {
        String action = defaultText(request.text("action"), "status");
        String message;
        switch (action) {
            case "refresh" -> { bot.updateNow(); message = "Persistenter Status wird aktualisiert."; }
            case "reset" -> { bot.resetStatusMessage(actor.auditName()); message = "Persistente Statusnachricht wird neu erstellt."; }
            case "reconnect" -> { bot.reconnect(actor.auditName()); message = "Discord-Gateway wird neu verbunden."; }
            case "sync" -> message = bot.syncGuildCommands(actor.auditName());
            default -> message = bot.adminSummary();
        }
        return result("Discord-Bot", message, action.equals("sync") ? "Synchronizing Discord admin commands" : "Managing the Discord integration");
    }

    private Reply suite(Request request, Actor actor) {
        List<String> command = tokenize(request.text("command"));
        if (command.isEmpty()) return Reply.error("Kein `/sas`-Unterbefehl angegeben.");
        if (!allowedSuiteCommand(command)) return Reply.error("Dieser Unterbefehl ist über Discord nicht freigegeben. Nutze den nativen `/sasadmin`-Bereich oder passe keine Sicherheitsgrenzen durch freie Befehle an.");
        return result("Suite-Befehl", plugin.discordAdminRunSuite(actor.id, actor.auditName(), command), "Managing Simplex Admin Suite");
    }

    static boolean allowedSuiteCommand(Collection<String> command) {
        if (command == null || command.isEmpty()) return false;
        String root = lower(command.iterator().next()).replaceFirst("^/", "");
        return ALLOWED_SUITE_ROOTS.contains(root) && !root.equals("password") && !root.equals("updates");
    }

    static boolean safeSuiteCommand(String commandLine) {
        List<String> command = tokenize(commandLine);
        if (!allowedSuiteCommand(command)) return false;
        String root = lower(command.get(0)).replaceFirst("^/", "");
        if (!SAFE_SUITE_ROOTS.contains(root)) return false;
        if ((root.equals("notes") || root.equals("notizen")) && command.size() >= 3) return command.get(2).equalsIgnoreCase("list");
        if (root.equals("watchlist") || root.equals("announcement")) return command.size() < 2 || command.get(1).equalsIgnoreCase("list");
        if (root.equals("discordstatus")) return command.size() < 2;
        if (root.equals("vpn")) {
            if (command.size() < 2 || command.get(1).equalsIgnoreCase("status") || command.get(1).equalsIgnoreCase("protokoll")) return true;
            return command.get(1).equalsIgnoreCase("whitelist") && command.size() >= 3
                    && (command.get(2).equalsIgnoreCase("anzeigen") || command.get(2).equalsIgnoreCase("list"));
        }
        return true;
    }

    static boolean hardDeniedConsoleCommand(String command) {
        if (command == null || command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0) return true;
        List<String> tokens = tokenize(command);
        if (tokens.isEmpty()) return true;
        String normalized = lower(String.join(" ", tokens)).replaceFirst("^/+", "");
        String root = tokens.get(0).toLowerCase(Locale.ROOT).replaceFirst("^/+", "");
        String canonicalRoot = root.contains(":") ? root.substring(root.lastIndexOf(':') + 1) : root;
        if (normalized.contains("\n") || normalized.contains("\r") || normalized.contains(";") || normalized.contains("&&") || normalized.contains("||")) return true;
        Set<String> denied = Set.of("stop", "restart", "reload", "rl", "op", "deop", "plugins", "pl", "version", "ver", "about",
                "luckperms", "lp", "pex", "permissions", "execute", "function", "schedule", "datapack");
        return denied.contains(root) || denied.contains(canonicalRoot);
    }

    static List<String> tokenize(String value) {
        if (value == null || value.isBlank()) return List.of();
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        char quote = 0;
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (escaped) { current.append(c); escaped = false; continue; }
            if (c == '\\') { escaped = true; continue; }
            if (quoted) {
                if (c == quote) quoted = false; else current.append(c);
                continue;
            }
            if (c == '\'' || c == '"') { quoted = true; quote = c; continue; }
            if (Character.isWhitespace(c)) {
                if (current.length() > 0) { tokens.add(current.toString()); current.setLength(0); }
            } else current.append(c);
        }
        if (escaped) current.append('\\');
        if (current.length() > 0) tokens.add(current.toString());
        return tokens;
    }

    private Reply result(String title, String message, String activity) {
        String text = message == null || message.isBlank() ? "Aktion abgeschlossen." : message;
        boolean failed = lower(text).contains("abgebrochen") || lower(text).contains("fehl") || lower(text).contains("nicht gefunden") || lower(text).contains("nicht freigegeben");
        return new Reply(title, text, failed ? 0xED4245 : 0x57F287,
                List.of(row(button(2, "Admin-Dashboard", "sas:view:dashboard", "🧭"))), activity);
    }

    private static List<String> words(String... values) {
        List<String> result = new ArrayList<>();
        for (String value : values) if (value != null && !value.isBlank()) result.addAll(tokenizeArgument(value));
        return result;
    }

    private static List<String> tokenizeArgument(String value) {
        return value == null || value.isBlank() ? List.of() : Arrays.asList(value.trim().split("\\s+"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> optionValues(Object raw) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (!(raw instanceof List)) return values;
        for (Object item : (List<?>) raw) {
            if (!(item instanceof Map)) continue;
            Map<String, Object> option = (Map<String, Object>) item;
            if (option.containsKey("name") && option.containsKey("value")) values.put(String.valueOf(option.get("name")), option.get("value"));
        }
        return values;
    }

    private static Map<String, Object> group(String name, String description, List<Object> options) {
        return mapOf("type", 2, "name", name, "description", description, "options", options);
    }

    @SafeVarargs
    @SuppressWarnings("varargs")
    private static Map<String, Object> sub(String name, String description, Map<String, Object>... options) {
        Map<String, Object> result = mapOf("type", 1, "name", name, "description", description);
        if (options != null && options.length > 0) result.put("options", Arrays.asList(options));
        return result;
    }

    private static Map<String, Object> playerOption(String name, boolean required) {
        return string(name, "Spielername oder UUID", required, true);
    }

    private static Map<String, Object> string(String name, String description, boolean required, boolean autocomplete) {
        Map<String, Object> option = mapOf("type", STRING, "name", name, "description", description, "required", required);
        if (autocomplete) option.put("autocomplete", true);
        return option;
    }

    private static Map<String, Object> integer(String name, String description, boolean required, int minimum, int maximum) {
        return mapOf("type", INTEGER, "name", name, "description", description, "required", required, "min_value", minimum, "max_value", maximum);
    }

    private static Map<String, Object> bool(String name, String description, boolean required) {
        return mapOf("type", BOOLEAN, "name", name, "description", description, "required", required);
    }

    private static Map<String, Object> choices(String name, String description, boolean required, String... values) {
        List<Map<String, Object>> choices = new ArrayList<>();
        for (int i = 0; i + 1 < values.length; i += 2) choices.add(mapOf("value", values[i], "name", values[i + 1]));
        return mapOf("type", STRING, "name", name, "description", description, "required", required, "choices", choices);
    }

    private static Map<String, Object> button(int style, String label, String customId, String emoji) {
        Map<String, Object> result = mapOf("type", 2, "style", style, "label", limit(label, 80), "custom_id", limit(customId, 100));
        if (emoji != null && !emoji.isBlank()) result.put("emoji", mapOf("name", emoji));
        return result;
    }

    @SafeVarargs
    @SuppressWarnings("varargs")
    private static Map<String, Object> row(Map<String, Object>... buttons) {
        return mapOf("type", 1, "components", Arrays.asList(buttons));
    }

    private static Map<String, Object> rowFrom(Collection<Map<String, Object>> buttons) {
        return mapOf("type", 1, "components", new ArrayList<>(buttons));
    }

    private static Map<String, Object> mapOf(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]);
        return result;
    }

    private static List<Choice> limitChoices(List<Choice> values) {
        return values.size() <= 25 ? values : new ArrayList<>(values.subList(0, 25));
    }

    private static int normalizeCountdown(int value) {
        return value <= 0 ? 0 : value <= 60 ? 60 : value <= 180 ? 180 : 300;
    }

    private static String join(List<String> values, int start) {
        if (values == null || start >= values.size()) return "";
        return String.join(" ", values.subList(Math.max(0, start), values.size()));
    }

    private static String defaultText(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
    private static String lower(String value) { return value == null ? "" : value.toLowerCase(Locale.ROOT); }
    private static int number(Object value, int fallback) { return value instanceof Number ? ((Number) value).intValue() : fallback; }
    private static String safe(String value) { return value == null || value.isBlank() ? "unknown error" : limit(value.replace('\n', ' ').replace('\r', ' '), 500); }
    private static String limit(String value, int maximum) { return value == null ? "" : value.length() <= maximum ? value : value.substring(0, Math.max(0, maximum - 1)) + "…"; }
}
