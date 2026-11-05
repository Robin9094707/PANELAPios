package eu.simplexsmp.adminsuite;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 10.5.0 Werbe-Zentrale ("copy centre"): advertising places (Discord channels, Reddit, Planet Minecraft, Facebook
 * groups, …) with a link, a formatted ad text and the interval in which posting there is allowed. Nothing is posted
 * automatically: when a place is due, its owner gets a Bark reminder. Tapping it opens the app, which copies the text
 * to the clipboard and opens the place; the timer then restarts slightly above the interval (6 h → about 6.1 h).
 *
 * <ul>
 *   <li>State lives in {@code ad-hub/state.json} (atomic writes). Timers are absolute timestamps, restarts never
 *       reset them.</li>
 *   <li>Bark links carry a short HMAC ({@code ad-hub/link.key}) bound to one place and one reminder cycle; it only
 *       allows "copy & mark posted", "peek" and "snooze" for that place – nothing else.</li>
 *   <li>The 10.4.0 Discord self-bot planner is migrated on first start: texts and channels (as channel links with
 *       their timers) are kept, linked account tokens are deleted from disk.</li>
 * </ul>
 */
final class AdHub {
    /** Side effects outside this class; the plugin implements them, the harness records them. */
    interface Hooks {
        /** Bark/browser reminder. {@code owner} empty = every account with the view right. */
        void remind(String owner, String title, String body, String appUrl, String webUrl, String copy);
        void audit(String actor, String action, String detail);
        Map<String, String> placeholders();
        void warn(String message);
        String serverId();
    }

    record Platform(String id, String label, String format, int limit, String hint, String example) { }

    static final List<Platform> PLATFORMS = List.of(
        new Platform("discord", "Discord", "discord", 2000, "Kanal-Link: Rechtsklick/lange drücken auf den Kanal → „Link kopieren“", "https://discord.com/channels/123456789012345678/123456789012345678"),
        new Platform("reddit", "Reddit", "markdown", 40000, "Link zum Subreddit oder direkt zu „Beitrag erstellen“", "https://www.reddit.com/r/mcservers/submit"),
        new Platform("planetminecraft", "Planet Minecraft", "bbcode", 50000, "Link zu deiner Serverseite oder zum Bearbeiten (BBCode)", "https://www.planetminecraft.com/account/manage/servers/"),
        new Platform("facebook", "Facebook-Gruppe", "unicode", 63206, "Link zur Gruppe (facebook.com/groups/…)", "https://www.facebook.com/groups/minecraftserver"),
        new Platform("telegram", "Telegram", "whatsapp", 4096, "Link zur Gruppe oder zum Kanal (t.me/…)", "https://t.me/minecraftservers"),
        new Platform("whatsapp", "WhatsApp", "whatsapp", 65536, "Link zur Gruppe oder zum Kanal (chat.whatsapp.com/…)", "https://chat.whatsapp.com/…"),
        new Platform("x", "X / Twitter", "unicode", 280, "Link zum Profil oder Post-Fenster", "https://x.com/compose/post"),
        new Platform("serverlist", "Serverliste / Forum", "bbcode", 0, "Link zur Bump- oder Bearbeiten-Seite", "https://minecraft-server-list.com/…"),
        new Platform("other", "Andere Website", "plain", 0, "Beliebiger https-Link", "https://…"));

    static final int MAX_TEXTS = 80, MAX_PLACES = 150, MAX_CONTENT = 20000, HISTORY = 25, LOG = 200;
    static final long MIN_INTERVAL = 5, MAX_INTERVAL = 30L * 24 * 60;
    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("dd.MM. HH:mm", Locale.GERMANY).withZone(ZONE);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMANY).withZone(ZONE);
    static final Pattern DISCORD_CHANNEL = Pattern.compile("^https://(?:(?:www|ptb|canary)\\.)?discord(?:app)?\\.com/channels/(\\d{15,25}|@me)/(\\d{15,25})(?:/(\\d{15,25}))?/?$");

    private final Path folder, stateFile, keyFile;
    private final Hooks hooks;
    private final LongSupplier clock;
    private final SecureRandom random = new SecureRandom();
    private final byte[] linkKey;
    private final Map<String, Text> texts = new LinkedHashMap<>();
    private final Map<String, Place> places = new LinkedHashMap<>();
    private final Deque<Map<String, Object>> log = new ArrayDeque<>();
    private final Settings settings = new Settings();
    private Map<String, Object> migration = new LinkedHashMap<>();
    private ScheduledExecutorService worker;
    private volatile String lastTickProblem = "";

    AdHub(Path folder, Path legacyFolder, Hooks hooks, LongSupplier clock) throws IOException {
        this.folder = folder; this.hooks = hooks; this.clock = clock;
        stateFile = folder.resolve("state.json"); keyFile = folder.resolve("link.key");
        Files.createDirectories(folder);
        linkKey = loadKey();
        load(legacyFolder);
    }

    // ================================================================== model

    static final class Text {
        String id = "", name = "", format = "discord", content = "", updatedBy = ""; boolean preset; long createdAt, updatedAt;
    }

    static final class Place {
        String id = "", name = "", platform = "discord", link = "", textId = "", note = "", owner = "", ownerLabel = "", createdBy = "", lastBy = "";
        long intervalMinutes = 360, nextDueAt, lastPostedAt, snoozedUntil, lastRemindAt, createdAt, updatedAt, previousDueAt, previousPostedAt;
        boolean enabled = true, notifyAll, undoable;
        int cycle = 1, remindCount, postCount, snoozeCount;
        final List<Map<String, Object>> history = new ArrayList<>();
        long dueAt() { return Math.max(nextDueAt, snoozedUntil); }
    }

    static final class Settings {
        double safetyPercent = 1.5; int repeatMinutes = 60, maxRepeats = 3; boolean remind = true;
    }

    // ================================================================== pure helpers (unit-tested)

    /** Next due time after a post: the full interval plus a small safety margin (1.5 % → 6 h ≈ 6.1 h), never shorter. */
    static long nextDue(long postedAt, long intervalMinutes, double safetyPercent) {
        long base = intervalMinutes * 60_000L;
        long extra = Math.round(base * Math.max(0, safetyPercent) / 100.0);
        extra = Math.max(60_000L, Math.min(3_600_000L, extra));
        return postedAt + base + extra;
    }

    static String human(long minutes) {
        if (minutes >= 1440 && minutes % 1440 == 0) return minutes == 1440 ? "1 Tag" : minutes / 1440 + " Tage";
        if (minutes >= 60 && minutes % 60 == 0) return minutes / 60 + " Std";
        if (minutes > 60) return String.format(Locale.GERMANY, "%.1f Std", minutes / 60.0);
        return minutes + " Min";
    }

    /** Guild and channel id of a Discord channel link, or null. */
    static String[] discordChannel(String link) {
        Matcher m = DISCORD_CHANNEL.matcher(link == null ? "" : link.trim());
        return m.matches() ? new String[]{m.group(1), m.group(2)} : null;
    }

    /** Only https (and http for odd server lists) links; returns the cleaned link or throws. */
    static String cleanLink(String raw) {
        String link = raw == null ? "" : raw.trim();
        if (link.isEmpty()) throw new IllegalArgumentException("Bitte den Link zum Kanal, zur Gruppe oder Seite einfügen");
        if (link.length() > 600) throw new IllegalArgumentException("Link zu lang");
        if (!link.matches("(?i)^https?://[^\\s<>\"]+$")) throw new IllegalArgumentException("Der Link muss mit https:// beginnen");
        try { URI uri = URI.create(link); if (uri.getHost() == null || uri.getHost().isBlank()) throw new IllegalArgumentException("Link ohne Adresse"); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Ungültiger Link"); }
        return link;
    }

    static Platform platform(String id) { for (Platform p : PLATFORMS) if (p.id().equals(id)) return p; return PLATFORMS.get(PLATFORMS.size() - 1); }

    /** Platform guessed from a pasted link. */
    static String guessPlatform(String link) {
        String l = link == null ? "" : link.toLowerCase(Locale.ROOT);
        if (l.matches("^https?://([a-z]+\\.)?discord(app)?\\.(com|gg)/.*")) return "discord";
        if (l.contains("reddit.com/") || l.contains("redd.it/")) return "reddit";
        if (l.contains("planetminecraft.com")) return "planetminecraft";
        if (l.contains("facebook.com/") || l.contains("fb.com/")) return "facebook";
        if (l.contains("t.me/") || l.contains("telegram.me/")) return "telegram";
        if (l.contains("whatsapp.com/") || l.contains("wa.me/")) return "whatsapp";
        if (l.contains("x.com/") || l.contains("twitter.com/")) return "x";
        if (l.contains("minecraft-server") || l.contains("minecraftservers") || l.contains("topg.") || l.contains("minecraft-mp") || l.contains("serverlist")) return "serverlist";
        return "other";
    }

    /** Fills {online}, {max}, {date} and any extra placeholder the plugin provides. */
    String render(String content) {
        String out = content;
        Map<String, String> values = new HashMap<>();
        try { values.putAll(hooks.placeholders()); } catch (RuntimeException ignored) { }
        values.putIfAbsent("{date}", DATE.format(Instant.ofEpochMilli(clock.getAsLong())));
        for (var e : values.entrySet()) out = out.replace(e.getKey(), e.getValue());
        return out;
    }

    // ================================================================== Bark link signature

    String signature(String placeId, int cycle) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(linkKey, "HmacSHA256"));
            byte[] raw = mac.doFinal(("ad-hub|" + hooks.serverId() + "|" + placeId + "|" + cycle).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw).substring(0, 24);
        } catch (Exception e) { throw new IllegalStateException("HMAC nicht verfügbar", e); }
    }

    String appLink(Place p) {
        return "panel://ad-post?s=" + enc(hooks.serverId()) + "&p=" + enc(p.id) + "&c=" + p.cycle + "&k=" + signature(p.id, p.cycle);
    }
    private static String enc(String v) { return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8); }

    // ================================================================== persistence

    private byte[] loadKey() throws IOException {
        if (Files.exists(keyFile)) {
            byte[] raw = Base64.getDecoder().decode(Files.readString(keyFile, StandardCharsets.UTF_8).trim());
            if (raw.length == 32) return raw;
        }
        byte[] raw = new byte[32]; random.nextBytes(raw);
        Files.writeString(keyFile, Base64.getEncoder().encodeToString(raw), StandardCharsets.UTF_8);
        try { Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------")); } catch (UnsupportedOperationException | IOException ignored) { }
        return raw;
    }

    @SuppressWarnings("unchecked")
    private void load(Path legacy) throws IOException {
        if (!Files.exists(stateFile)) {
            seedTexts(); seedExtraTexts();
            if (legacy != null) migrateLegacy(legacy);
            persist();
            if (legacy != null) cleanLegacy(legacy);
            return;
        }
        Map<String, Object> root;
        try { root = (Map<String, Object>) DiscordBotManager.Json.parse(Files.readString(stateFile, StandardCharsets.UTF_8)); }
        catch (RuntimeException e) {
            Path aside = folder.resolve("state.json.unreadable-" + clock.getAsLong());
            Files.copy(stateFile, aside, StandardCopyOption.REPLACE_EXISTING);
            hooks.warn("Werbe-Zentrale: state.json war nicht lesbar und wurde als " + aside.getFileName() + " gesichert; es wird neu begonnen.");
            seedTexts(); seedExtraTexts(); persist(); return;
        }
        Map<String, Object> s = map(root.get("settings"));
        settings.safetyPercent = Math.max(0, Math.min(10, dbl(s.get("safetyPercent"), 1.5))); settings.repeatMinutes = clampInt(s.get("repeatMinutes"), 60, 0, 720);
        settings.maxRepeats = clampInt(s.get("maxRepeats"), 3, 0, 10); settings.remind = bool(s.get("remind"), true);
        for (Object o : list(root.get("texts"))) {
            Map<String, Object> m = map(o); Text t = new Text();
            t.id = str(m.get("id")); t.name = str(m.get("name")); t.format = AdFormats.normalize(str(m.get("format"))); t.content = str(m.get("content")); t.updatedBy = str(m.get("updatedBy"));
            t.preset = bool(m.get("preset"), false); t.createdAt = lng(m.get("createdAt")); t.updatedAt = lng(m.get("updatedAt"));
            if (!t.id.isEmpty()) texts.put(t.id, t);
        }
        for (Object o : list(root.get("places"))) {
            Map<String, Object> m = map(o); Place p = new Place();
            p.id = str(m.get("id")); p.name = str(m.get("name")); p.platform = platform(str(m.get("platform"))).id(); p.link = str(m.get("link")); p.textId = str(m.get("textId"));
            p.note = str(m.get("note")); p.owner = str(m.get("owner")); p.ownerLabel = str(m.get("ownerLabel")); p.createdBy = str(m.get("createdBy")); p.lastBy = str(m.get("lastBy"));
            p.intervalMinutes = Math.max(MIN_INTERVAL, Math.min(MAX_INTERVAL, lng(m.getOrDefault("intervalMinutes", 360L))));
            p.nextDueAt = lng(m.get("nextDueAt")); p.lastPostedAt = lng(m.get("lastPostedAt")); p.snoozedUntil = lng(m.get("snoozedUntil")); p.lastRemindAt = lng(m.get("lastRemindAt"));
            p.createdAt = lng(m.get("createdAt")); p.updatedAt = lng(m.get("updatedAt")); p.previousDueAt = lng(m.get("previousDueAt")); p.previousPostedAt = lng(m.get("previousPostedAt"));
            p.enabled = bool(m.get("enabled"), true); p.notifyAll = bool(m.get("notifyAll"), false); p.undoable = bool(m.get("undoable"), false);
            p.cycle = Math.max(1, (int) lng(m.getOrDefault("cycle", 1L))); p.remindCount = (int) lng(m.get("remindCount")); p.postCount = (int) lng(m.get("postCount")); p.snoozeCount = (int) lng(m.get("snoozeCount"));
            for (Object h : list(m.get("history"))) if (h instanceof Map<?, ?>) p.history.add(map(h));
            if (!p.id.isEmpty()) places.put(p.id, p);
        }
        for (Object o : list(root.get("log"))) if (o instanceof Map<?, ?>) log.addLast(map(o));
        migration = map(root.get("migration"));
        // A crash between migration and cleanup would leave the old token file behind: finish the cleanup now.
        if (legacy != null) cleanLegacy(legacy);
    }

    private synchronized void persist() throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", 1);
        root.put("settings", SimplexAdminSuite.appMap("safetyPercent", settings.safetyPercent, "repeatMinutes", settings.repeatMinutes, "maxRepeats", settings.maxRepeats, "remind", settings.remind));
        List<Object> t = new ArrayList<>();
        for (Text x : texts.values()) t.add(SimplexAdminSuite.appMap("id", x.id, "name", x.name, "format", x.format, "content", x.content, "preset", x.preset, "createdAt", x.createdAt, "updatedAt", x.updatedAt, "updatedBy", x.updatedBy));
        root.put("texts", t);
        List<Object> p = new ArrayList<>();
        for (Place x : places.values()) {
            Map<String, Object> m = SimplexAdminSuite.appMap("id", x.id, "name", x.name, "platform", x.platform, "link", x.link, "textId", x.textId, "note", x.note, "owner", x.owner, "ownerLabel", x.ownerLabel,
                "createdBy", x.createdBy, "lastBy", x.lastBy, "intervalMinutes", x.intervalMinutes, "nextDueAt", x.nextDueAt, "lastPostedAt", x.lastPostedAt, "snoozedUntil", x.snoozedUntil,
                "lastRemindAt", x.lastRemindAt, "createdAt", x.createdAt, "updatedAt", x.updatedAt, "previousDueAt", x.previousDueAt, "previousPostedAt", x.previousPostedAt, "enabled", x.enabled,
                "notifyAll", x.notifyAll, "undoable", x.undoable, "cycle", x.cycle, "remindCount", x.remindCount, "postCount", x.postCount, "snoozeCount", x.snoozeCount);
            m.put("history", new ArrayList<>(x.history));
            p.add(m);
        }
        root.put("places", p);
        root.put("log", new ArrayList<>(log));
        root.put("migration", migration);
        Path temp = Files.createTempFile(folder, "state", ".tmp");
        try {
            Files.writeString(temp, DiscordBotManager.Json.stringify(root), StandardCharsets.UTF_8);
            try { Files.move(temp, stateFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, stateFile, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }

    private void save() { try { persist(); } catch (IOException e) { hooks.warn("Werbe-Zentrale: Speichern fehlgeschlagen (" + e.getClass().getSimpleName() + ")"); } }

    // ================================================================== migration from the 10.4.0 Discord planner

    /** Texts and channels of the old planner become texts and Discord places; tokens are not carried over. */
    @SuppressWarnings("unchecked")
    private void migrateLegacy(Path legacy) {
        Path old = legacy.resolve("state.json");
        if (!Files.isRegularFile(old)) return;
        Map<String, Object> root;
        try { root = (Map<String, Object>) DiscordBotManager.Json.parse(Files.readString(old, StandardCharsets.UTF_8)); }
        catch (Exception e) { hooks.warn("Werbe-Zentrale: alte discord-ads/state.json nicht lesbar – sie bleibt unverändert liegen."); migration = SimplexAdminSuite.appMap("from", "10.4.0", "failed", true); return; }
        long now = clock.getAsLong(); int textCount = 0, placeCount = 0;
        for (Object o : list(root.get("templates"))) {
            Map<String, Object> m = map(o); String id = str(m.get("id")); if (id.isEmpty()) continue;
            Text existing = texts.values().stream().filter(x -> x.preset && x.name.equals(str(m.get("name"))) && x.format.equals("discord")).findFirst().orElse(null);
            if (existing != null) texts.remove(existing.id);
            Text t = new Text(); t.id = id; t.name = clip(str(m.get("name")), 60); t.format = "discord"; t.content = clip(str(m.get("content")), MAX_CONTENT);
            t.preset = bool(m.get("preset"), false); t.createdAt = lng(m.get("createdAt")); t.updatedAt = lng(m.get("updatedAt")); t.updatedBy = str(m.get("updatedBy"));
            texts.put(t.id, t); textCount++;
        }
        for (Object o : list(root.get("campaigns"))) {
            Map<String, Object> m = map(o); String id = str(m.get("id")), guild = str(m.get("guildId")), channel = str(m.get("channelId"));
            if (id.isEmpty() || guild.isEmpty() || channel.isEmpty() || places.size() >= MAX_PLACES) continue;
            Place p = new Place(); p.id = id; p.platform = "discord";
            String gname = str(m.get("guildName")), cname = str(m.get("channelName"));
            p.name = clip(("#" + cname + (gname.isBlank() ? "" : " · " + gname)).trim(), 80);
            p.link = "https://discord.com/channels/" + guild + "/" + channel;
            p.textId = texts.containsKey(str(m.get("templateId"))) ? str(m.get("templateId")) : texts.keySet().stream().findFirst().orElse("");
            p.intervalMinutes = Math.max(MIN_INTERVAL, Math.min(MAX_INTERVAL, lng(m.getOrDefault("intervalMinutes", 360L))));
            long slow = lng(m.get("slowmodeSeconds")) / 60; if (slow > p.intervalMinutes) p.intervalMinutes = Math.min(MAX_INTERVAL, slow);
            p.lastPostedAt = lng(m.get("lastSentAt"));
            p.nextDueAt = p.lastPostedAt > 0 ? nextDue(p.lastPostedAt, p.intervalMinutes, settings.safetyPercent) : now;
            p.enabled = bool(m.get("enabled"), true); p.notifyAll = true; p.postCount = (int) lng(m.get("sentCount"));
            p.createdAt = lng(m.get("createdAt")) > 0 ? lng(m.get("createdAt")) : now; p.updatedAt = now; p.createdBy = str(m.get("createdBy")); p.ownerLabel = "alle mit Zugriff";
            p.note = clip(str(m.get("category")), 200);
            history(p, "info", "Aus dem Discord-Werbeplaner übernommen – ab jetzt postest du selbst (Erinnerung per Bark).");
            places.put(p.id, p); placeCount++;
        }
        int accounts = list(root.get("accounts")).size();
        migration = SimplexAdminSuite.appMap("from", "10.4.0", "at", now, "texts", textCount, "places", placeCount, "accounts", accounts, "acknowledged", false);
        log("info", "Discord-Werbeplaner übernommen: " + textCount + " Texte, " + placeCount + " Kanäle. " + (accounts > 0 ? accounts + " Discord-Token gelöscht – automatisches Posten gibt es nicht mehr." : ""));
    }

    /** Removes the old token file and key; keeps a token-free copy of the old state for reference. */
    @SuppressWarnings("unchecked")
    private void cleanLegacy(Path legacy) {
        Path old = legacy.resolve("state.json"), key = legacy.resolve("secret.key");
        try {
            if (Files.isRegularFile(old) && !Boolean.TRUE.equals(migration.get("failed"))) {
                Map<String, Object> root = (Map<String, Object>) DiscordBotManager.Json.parse(Files.readString(old, StandardCharsets.UTF_8));
                List<Object> stripped = new ArrayList<>();
                for (Object o : list(root.get("accounts"))) { Map<String, Object> a = new LinkedHashMap<>(map(o)); a.remove("token"); a.put("tokenRemoved", true); stripped.add(a); }
                root.put("accounts", stripped);
                Path copy = legacy.resolve("state-before-10.5.0-without-tokens.json");
                Files.writeString(copy, DiscordBotManager.Json.stringify(root), StandardCharsets.UTF_8);
                Files.delete(old);
            }
            if (Files.isRegularFile(key) && !Files.exists(old)) {
                Files.delete(key);
                hooks.warn("Werbe-Zentrale: alter Discord-Token-Schlüssel gelöscht. Tipp: Passwort des Discord-Zweitaccounts ändern, dann ist der alte Token auch bei Discord ungültig.");
            }
        } catch (Exception e) { hooks.warn("Werbe-Zentrale: alte Discord-Daten konnten nicht bereinigt werden (" + e.getClass().getSimpleName() + ")"); }
    }

    // ================================================================== seeds

    static final String SIMPLEX_AD = """
        # 🌍 **SIMPLEX SMP**
        ### *Survival • Crossplay • No Resets • 60 Slots*

        > **📡 __CONNECTION SETTINGS__**
        > Connect on **Java & Bedrock**:
        >
        > 🖥️ **IP Address:** `simplexsmp.eu`
        > 📱 **Bedrock Port:** `19132`
        >
        > **🎮 __CONSOLE SHORTCUT__**
        > *If the IP doesn't work, add us as a friend:*
        > 👤 **Add Friend:** `SimplexSMP`
        >
        > **💎 __SERVER HIGHLIGHTS__**
        > 🛡️ **Secure Claims** • Protect your builds easily
        > ♾️ **No Resets** • A permanent world forever
        > ⚡ **Performance** • Zero Lag • High TPS
        > 🤝 **Community** • Active 24/7 • Fair Play

        **🔗 JOIN THE DISCORD**
        https://discord.gg/2gAsymnXfd""";

    static final String SIMPLEX_AD_SHORT = """
        **🌍 SIMPLEX SMP** • *Survival • Crossplay • No Resets*
        🖥️ Java & Bedrock: `simplexsmp.eu` • 📱 Port `19132`
        🛡️ Claims • ♾️ No Resets • ⚡ High TPS • 🤝 Active community
        🔗 https://discord.gg/2gAsymnXfd""";

    static final String SIMPLEX_AD_DE = """
        # 🌍 **SIMPLEX SMP**
        ### *Survival • Crossplay • Kein Reset • 60 Slots*

        > **📡 __VERBINDUNG__**
        > Spielbar mit **Java & Bedrock**:
        >
        > 🖥️ **IP-Adresse:** `simplexsmp.eu`
        > 📱 **Bedrock-Port:** `19132`
        >
        > **🎮 __KONSOLE__**
        > *Klappt die IP nicht? Füge uns als Freund hinzu:*
        > 👤 **Freund:** `SimplexSMP`
        >
        > **💎 __HIGHLIGHTS__**
        > 🛡️ **Sichere Grundstücke** • Bauten einfach schützen
        > ♾️ **Kein Reset** • Eine Welt für immer
        > ⚡ **Performance** • Kein Lag • Hohe TPS
        > 🤝 **Community** • Aktiv • Fair Play

        **🔗 DISCORD BEITRETEN**
        https://discord.gg/2gAsymnXfd""";

    private void seedTexts() {
        String[][] rows = {{"Simplex SMP – Discord", SIMPLEX_AD}, {"Simplex SMP – Kurz", SIMPLEX_AD_SHORT}, {"Simplex SMP – Deutsch", SIMPLEX_AD_DE}};
        for (String[] r : rows) addPreset(r[0], "discord", r[1]);
    }

    /** Ready-made versions of the Simplex ad for the other platforms. */
    private void seedExtraTexts() {
        addPreset("Simplex SMP – Reddit", "markdown", AdFormats.convert(SIMPLEX_AD, "discord", "markdown"));
        addPreset("Simplex SMP – Planet Minecraft (BBCode)", "bbcode", AdFormats.convert(SIMPLEX_AD, "discord", "bbcode"));
        addPreset("Simplex SMP – Facebook", "unicode", AdFormats.convert(SIMPLEX_AD_DE, "discord", "unicode"));
    }

    private void addPreset(String name, String format, String content) {
        if (texts.values().stream().anyMatch(t -> t.name.equals(name))) return;
        long now = clock.getAsLong();
        Text t = new Text(); t.id = newId(); t.name = name; t.format = format; t.content = content; t.preset = true; t.createdAt = now; t.updatedAt = now; t.updatedBy = "Vorlage";
        texts.put(t.id, t);
    }

    // ================================================================== lifecycle

    void start() {
        worker = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "SimplexAdHub"); t.setDaemon(true); return t; });
        worker.scheduleWithFixedDelay(() -> { try { tick(); lastTickProblem = ""; } catch (Throwable e) { lastTickProblem = e.getClass().getSimpleName(); hooks.warn("Werbe-Zentrale: Erinnerungsfehler " + e.getClass().getSimpleName()); } }, 20, 15, TimeUnit.SECONDS);
    }

    void stop() {
        if (worker != null) worker.shutdownNow();
        synchronized (this) { save(); }
    }

    /** Sends due reminders (at most three per run) and repeats unanswered ones. */
    void tick() {
        long now = clock.getAsLong();
        List<Object[]> send = new ArrayList<>();
        synchronized (this) {
            if (!settings.remind) return;
            List<Place> due = new ArrayList<>(places.values()); due.sort(Comparator.comparingLong(Place::dueAt));
            boolean changed = false;
            for (Place p : due) {
                if (send.size() >= 3) break;
                if (!p.enabled || p.dueAt() > now) continue;
                boolean first = p.remindCount == 0;
                boolean repeat = !first && settings.repeatMinutes > 0 && p.remindCount <= settings.maxRepeats && now - p.lastRemindAt >= settings.repeatMinutes * 60_000L;
                if (!first && !repeat) continue;
                p.remindCount++; p.lastRemindAt = now; changed = true;
                Text t = texts.get(p.textId);
                String rendered = t == null ? "" : render(t.content);
                String title = (repeat ? "⏰ Noch offen: " : "📣 Werbung fällig: ") + p.name;
                String body = platform(p.platform).label() + " · alle " + human(p.intervalMinutes) + (t == null ? "" : " · „" + t.name + "“") + "\nTippen = Text kopieren & " + (p.platform.equals("discord") ? "Discord" : platform(p.platform).label()) + " öffnen";
                send.add(new Object[]{p.notifyAll ? "" : p.owner, title, body, appLink(p), "/#/ad-hub?post=" + p.id, rendered});
            }
            if (changed) save();
        }
        for (Object[] s : send) {
            try { hooks.remind((String) s[0], (String) s[1], (String) s[2], (String) s[3], (String) s[4], (String) s[5]); }
            catch (RuntimeException e) { hooks.warn("Werbe-Zentrale: Erinnerung konnte nicht gesendet werden (" + e.getClass().getSimpleName() + ")"); }
        }
    }

    // ================================================================== views

    synchronized Map<String, Object> overview() {
        long now = clock.getAsLong();
        List<Object> pl = new ArrayList<>();
        int due = 0, active = 0, posts24 = 0, posts7 = 0; long next = 0;
        List<Place> sorted = new ArrayList<>(places.values());
        sorted.sort(Comparator.comparing((Place p) -> !p.enabled).thenComparingLong(Place::dueAt));
        for (Place p : sorted) {
            pl.add(placeView(p, now));
            if (p.enabled) { active++; if (p.dueAt() <= now) due++; else if (next == 0 || p.dueAt() < next) next = p.dueAt(); }
            for (Map<String, Object> h : p.history) if ("posted".equals(h.get("kind"))) { long t = lng(h.get("time")); if (now - t < 86_400_000L) posts24++; if (now - t < 7 * 86_400_000L) posts7++; }
        }
        List<Object> tx = new ArrayList<>();
        for (Text t : texts.values()) {
            List<String> used = places.values().stream().filter(p -> p.textId.equals(t.id)).map(p -> p.name).toList();
            tx.add(SimplexAdminSuite.appMap("id", t.id, "name", t.name, "format", t.format, "formatLabel", AdFormats.LABELS.get(t.format), "content", t.content, "length", t.content.codePointCount(0, t.content.length()),
                "preset", t.preset, "updatedAt", t.updatedAt, "updatedBy", t.updatedBy, "usedBy", used));
        }
        List<Object> platforms = new ArrayList<>();
        for (Platform p : PLATFORMS) platforms.add(SimplexAdminSuite.appMap("id", p.id(), "label", p.label(), "format", p.format(), "limit", p.limit(), "hint", p.hint(), "example", p.example()));
        List<Object> formats = new ArrayList<>();
        for (String f : AdFormats.FORMATS) formats.add(SimplexAdminSuite.appMap("id", f, "label", AdFormats.LABELS.get(f)));
        Map<String, Object> out = SimplexAdminSuite.appMap("places", pl, "texts", tx, "platforms", platforms, "formats", formats,
            "settings", SimplexAdminSuite.appMap("safetyPercent", settings.safetyPercent, "repeatMinutes", settings.repeatMinutes, "maxRepeats", settings.maxRepeats, "remind", settings.remind),
            "stats", SimplexAdminSuite.appMap("places", places.size(), "active", active, "due", due, "posts24h", posts24, "posts7d", posts7, "nextDueAt", next),
            "limits", SimplexAdminSuite.appMap("places", MAX_PLACES, "texts", MAX_TEXTS, "content", MAX_CONTENT, "minInterval", MIN_INTERVAL, "maxInterval", MAX_INTERVAL),
            "log", new ArrayList<>(log).subList(0, Math.min(40, log.size())), "serverId", hooks.serverId(), "time", now, "problem", lastTickProblem);
        if (!migration.isEmpty() && !Boolean.TRUE.equals(migration.get("acknowledged"))) out.put("migration", migration);
        return out;
    }

    private Map<String, Object> placeView(Place p, long now) {
        Platform pf = platform(p.platform); Text t = texts.get(p.textId); String[] dc = p.platform.equals("discord") ? discordChannel(p.link) : null;
        String state = !p.enabled ? "paused" : p.dueAt() <= now ? "due" : p.snoozedUntil > now && p.snoozedUntil >= p.nextDueAt ? "snoozed" : "waiting";
        Map<String, Object> v = SimplexAdminSuite.appMap("id", p.id, "name", p.name, "platform", p.platform, "platformLabel", pf.label(), "link", p.link, "textId", p.textId,
            "textName", t == null ? "" : t.name, "format", t == null ? pf.format() : t.format, "length", t == null ? 0 : t.content.codePointCount(0, t.content.length()), "limit", pf.limit(),
            "intervalMinutes", p.intervalMinutes, "intervalText", human(p.intervalMinutes), "enabled", p.enabled, "state", state, "nextDueAt", p.nextDueAt, "dueAt", p.dueAt(),
            "snoozedUntil", p.snoozedUntil, "lastPostedAt", p.lastPostedAt, "lastBy", p.lastBy, "postCount", p.postCount, "remindCount", p.remindCount, "lastRemindAt", p.lastRemindAt,
            "note", p.note, "notifyAll", p.notifyAll, "ownerLabel", p.ownerLabel, "createdAt", p.createdAt, "undoable", p.undoable, "history", new ArrayList<>(p.history));
        if (dc != null) v.put("discord", SimplexAdminSuite.appMap("guild", dc[0], "channel", dc[1]));
        return v;
    }

    // ================================================================== texts

    synchronized Map<String, Object> saveText(Map<String, Object> body, String actor) {
        String id = str(body.get("id")), name = clip(str(body.get("name")).trim(), 60), content = str(body.get("content")).replace("\r\n", "\n"), format = AdFormats.normalize(str(body.get("format")));
        if (name.isEmpty()) throw new IllegalArgumentException("Bitte einen Namen eingeben");
        if (content.isBlank()) throw new IllegalArgumentException("Der Werbetext ist leer");
        if (content.length() > MAX_CONTENT) throw new IllegalArgumentException("Höchstens " + MAX_CONTENT + " Zeichen");
        Text t = id.isEmpty() ? null : texts.get(id);
        if (t == null) { if (texts.size() >= MAX_TEXTS) throw new IllegalArgumentException("Höchstens " + MAX_TEXTS + " Werbetexte"); t = new Text(); t.id = newId(); t.createdAt = clock.getAsLong(); texts.put(t.id, t); }
        t.name = name; t.content = content; t.format = format; t.updatedAt = clock.getAsLong(); t.updatedBy = actor; t.preset = false;
        save(); hooks.audit(actor, "Werbe-Zentrale: Text gespeichert", name);
        return SimplexAdminSuite.appMap("id", t.id, "notice", "„" + name + "“ gespeichert");
    }

    synchronized Map<String, Object> deleteText(String id, String actor) {
        Text t = texts.get(id); if (t == null) throw new IllegalArgumentException("Werbetext nicht gefunden");
        List<String> used = places.values().stream().filter(p -> p.textId.equals(id)).map(p -> p.name).toList();
        if (!used.isEmpty()) throw new IllegalArgumentException("Wird noch verwendet: " + String.join(", ", used));
        texts.remove(id); save(); hooks.audit(actor, "Werbe-Zentrale: Text gelöscht", t.name);
        return SimplexAdminSuite.appMap("notice", "„" + t.name + "“ gelöscht");
    }

    /** Converts text into another format; with {@code save} a new text "… (Format)" is created. */
    synchronized Map<String, Object> convertText(Map<String, Object> body, String actor) {
        String to = AdFormats.normalize(str(body.get("to")));
        String from, content, name;
        Text src = texts.get(str(body.get("id")));
        if (src != null) { from = src.format; content = src.content; name = src.name; }
        else { from = AdFormats.normalize(str(body.get("from"))); content = str(body.get("content")); name = clip(str(body.get("name")), 40); }
        if (content.length() > MAX_CONTENT) throw new IllegalArgumentException("Text zu lang");
        String converted = AdFormats.convert(content, from, to);
        Map<String, Object> out = SimplexAdminSuite.appMap("content", converted, "format", to);
        if (bool(body.get("save"), false)) {
            String base = name.replaceAll("\\s*\\((Discord|Reddit|BBCode|WhatsApp|Facebook|Text)[^)]*\\)$", "");
            String label = switch (to) { case "discord" -> "Discord"; case "markdown" -> "Reddit"; case "bbcode" -> "BBCode"; case "whatsapp" -> "WhatsApp"; case "unicode" -> "Facebook"; default -> "Text"; };
            Map<String, Object> saved = saveText(SimplexAdminSuite.appMap("name", clip(base + " (" + label + ")", 60), "content", converted, "format", to), actor);
            out.put("id", saved.get("id")); out.put("notice", "Umgewandelt und als „" + clip(base + " (" + label + ")", 60) + "“ gespeichert");
        }
        return out;
    }

    // ================================================================== places

    synchronized Map<String, Object> savePlace(Map<String, Object> body, String owner, String ownerLabel, String actor) {
        String id = str(body.get("id"));
        Place p = id.isEmpty() ? null : places.get(id);
        if (!id.isEmpty() && p == null) throw new IllegalArgumentException("Eintrag nicht gefunden");
        String link = cleanLink(str(body.get("link")));
        String platform = str(body.get("platform")).isBlank() ? guessPlatform(link) : platform(str(body.get("platform"))).id();
        String name = clip(str(body.get("name")).trim(), 80);
        if (name.isEmpty()) {
            String[] dc = discordChannel(link);
            name = dc != null ? "Discord-Kanal " + dc[1].substring(dc[1].length() - 4) : platform(platform).label();
        }
        String textId = str(body.get("textId"));
        if (!texts.containsKey(textId)) throw new IllegalArgumentException("Bitte einen Werbetext wählen");
        long interval;
        try { interval = Long.parseLong(str(body.getOrDefault("intervalMinutes", "360"))); } catch (NumberFormatException e) { throw new IllegalArgumentException("Intervall ungültig"); }
        if (interval < MIN_INTERVAL || interval > MAX_INTERVAL) throw new IllegalArgumentException("Intervall zwischen 5 Minuten und 30 Tagen");
        long now = clock.getAsLong();
        boolean created = p == null;
        if (created) {
            if (places.size() >= MAX_PLACES) throw new IllegalArgumentException("Höchstens " + MAX_PLACES + " Einträge");
            p = new Place(); p.id = newId(); p.createdAt = now; p.createdBy = actor; p.owner = owner; p.ownerLabel = ownerLabel;
            // First post: right now, or after a given time when the last post was just made elsewhere.
            long lastAgo = lng(body.get("lastPostedMinutesAgo"));
            if (lastAgo > 0) { p.lastPostedAt = now - lastAgo * 60_000L; p.nextDueAt = nextDue(p.lastPostedAt, interval, settings.safetyPercent); }
            else p.nextDueAt = now;
            places.put(p.id, p);
        }
        long oldInterval = p.intervalMinutes;
        p.name = name; p.platform = platform; p.link = link; p.textId = textId; p.intervalMinutes = interval;
        p.note = clip(str(body.get("note")), 300); p.notifyAll = bool(body.get("notifyAll"), p.notifyAll); p.updatedAt = now;
        if (!created && oldInterval != interval && p.lastPostedAt > 0) { p.nextDueAt = nextDue(p.lastPostedAt, interval, settings.safetyPercent); p.remindCount = 0; }
        history(p, created ? "created" : "edited", created ? "Angelegt – alle " + human(interval) : "Geändert – alle " + human(interval));
        log(created ? "created" : "edited", p.name + (created ? " angelegt" : " geändert"));
        save(); hooks.audit(actor, created ? "Werbe-Zentrale: Eintrag angelegt" : "Werbe-Zentrale: Eintrag geändert", p.name + " · " + p.link);
        return SimplexAdminSuite.appMap("id", p.id, "notice", p.dueAt() <= now ? "„" + p.name + "“ ist jetzt fällig" : "„" + p.name + "“ gespeichert – fällig " + clock(p.dueAt()));
    }

    /** The text to paste and the place to open, then the timer restarts. {@code force} allows posting before it is due. */
    synchronized Map<String, Object> post(String id, boolean force, String actor) {
        Place p = place(id); long now = clock.getAsLong();
        if (!force && p.enabled && p.nextDueAt > now + 60_000L) throw new AppApiManager.ApiError(409, "Noch nicht fällig – erst " + clock(p.nextDueAt) + ". Trotzdem werben?");
        Map<String, Object> out = payload(p);
        markPosted(p, now, actor);
        out.put("notice", "Kopiert – nächste Erinnerung " + clock(p.nextDueAt));
        out.put("nextDueAt", p.nextDueAt);
        return out;
    }

    private Map<String, Object> payload(Place p) {
        Text t = texts.get(p.textId);
        String[] dc = p.platform.equals("discord") ? discordChannel(p.link) : null;
        Map<String, Object> out = SimplexAdminSuite.appMap("id", p.id, "name", p.name, "platform", p.platform, "platformLabel", platform(p.platform).label(), "link", p.link,
            "text", t == null ? "" : render(t.content), "format", t == null ? "plain" : t.format, "textName", t == null ? "" : t.name, "intervalMinutes", p.intervalMinutes);
        if (dc != null) out.put("discord", SimplexAdminSuite.appMap("guild", dc[0], "channel", dc[1]));
        return out;
    }

    private void markPosted(Place p, long now, String actor) {
        p.previousDueAt = p.nextDueAt; p.previousPostedAt = p.lastPostedAt; p.undoable = true;
        p.lastPostedAt = now; p.nextDueAt = nextDue(now, p.intervalMinutes, settings.safetyPercent); p.snoozedUntil = 0; p.remindCount = 0; p.lastRemindAt = 0;
        p.cycle++; p.postCount++; p.lastBy = actor; p.updatedAt = now;
        history(p, "posted", "Kopiert & geöffnet von " + actor + " – nächste Erinnerung " + clock(p.nextDueAt));
        log("posted", p.name + " – Werbung kopiert (" + actor + ")");
        save();
    }

    synchronized Map<String, Object> snooze(String id, long minutes, String actor) {
        Place p = place(id); long now = clock.getAsLong();
        if (minutes < 5 || minutes > 7 * 1440) throw new IllegalArgumentException("Snooze zwischen 5 Minuten und 7 Tagen");
        // Due already: from now on. Not due yet: the snooze is added on top of the due time.
        p.snoozedUntil = Math.max(now, p.nextDueAt) + minutes * 60_000L;
        p.remindCount = 0; p.lastRemindAt = 0; p.snoozeCount++; p.updatedAt = now;
        history(p, "snoozed", "Später erinnern (" + human(minutes) + ") – " + clock(p.snoozedUntil) + " · " + actor);
        save();
        return SimplexAdminSuite.appMap("notice", "Erinnerung " + clock(p.snoozedUntil), "snoozedUntil", p.snoozedUntil);
    }

    synchronized Map<String, Object> placeAction(String id, String op, String actor) {
        Place p = place(id); long now = clock.getAsLong(); String notice;
        switch (op) {
            case "pause" -> { p.enabled = false; history(p, "paused", "Pausiert von " + actor); notice = "„" + p.name + "“ pausiert"; }
            case "resume" -> { p.enabled = true; p.remindCount = 0; if (p.nextDueAt < now) p.nextDueAt = now; history(p, "resumed", "Fortgesetzt von " + actor); notice = "„" + p.name + "“ läuft wieder – fällig " + clock(p.dueAt()); }
            case "due-now" -> { p.nextDueAt = now; p.snoozedUntil = 0; p.remindCount = 0; history(p, "info", "Als fällig markiert von " + actor); notice = "„" + p.name + "“ ist jetzt fällig"; }
            case "undo" -> {
                if (!p.undoable) throw new IllegalArgumentException("Nichts zum Rückgängigmachen");
                p.nextDueAt = p.previousDueAt; p.lastPostedAt = p.previousPostedAt; p.postCount = Math.max(0, p.postCount - 1); p.undoable = false; p.remindCount = 0;
                history(p, "undo", "Letztes „Geworben“ zurückgenommen von " + actor); notice = "Zurückgenommen – fällig " + clock(p.dueAt());
            }
            case "delete" -> { places.remove(id); notice = "„" + p.name + "“ gelöscht"; }
            default -> throw new IllegalArgumentException("Unbekannte Aktion");
        }
        p.updatedAt = now;
        log(op.equals("delete") ? "deleted" : op, p.name + ": " + notice + " (" + actor + ")");
        save(); hooks.audit(actor, "Werbe-Zentrale: " + op, p.name);
        return SimplexAdminSuite.appMap("notice", notice);
    }

    synchronized Map<String, Object> saveSettings(Map<String, Object> body, String actor) {
        if (body.containsKey("safetyPercent")) settings.safetyPercent = Math.max(0, Math.min(10, dbl(body.get("safetyPercent"), 1.5)));
        if (body.containsKey("repeatMinutes")) settings.repeatMinutes = clampInt(body.get("repeatMinutes"), 60, 0, 720);
        if (body.containsKey("maxRepeats")) settings.maxRepeats = clampInt(body.get("maxRepeats"), 3, 0, 10);
        if (body.containsKey("remind")) settings.remind = bool(body.get("remind"), true);
        save(); hooks.audit(actor, "Werbe-Zentrale: Einstellungen", "");
        return SimplexAdminSuite.appMap("notice", "Einstellungen gespeichert");
    }

    synchronized Map<String, Object> acknowledgeMigration() {
        if (!migration.isEmpty()) { migration.put("acknowledged", true); save(); }
        return SimplexAdminSuite.appMap("notice", "OK");
    }

    // ================================================================== Bark link (no login: HMAC-bound to one place and cycle)

    /**
     * {@code open}: text + link, marks posted. A repeated tap on the same (now previous) link within 30 minutes returns
     * the text again without restarting the timer twice. {@code peek}: text + link only. {@code snooze}: remind later.
     */
    synchronized Map<String, Object> link(String placeId, int cycle, String sig, String op, long minutes) {
        Place p = places.get(placeId);
        if (p == null) throw new AppApiManager.ApiError(404, "Dieser Werbe-Eintrag existiert nicht mehr.");
        boolean current = cycle == p.cycle && MessageDigest.isEqual(signature(p.id, cycle).getBytes(StandardCharsets.UTF_8), String.valueOf(sig).getBytes(StandardCharsets.UTF_8));
        boolean previous = !current && cycle == p.cycle - 1 && MessageDigest.isEqual(signature(p.id, cycle).getBytes(StandardCharsets.UTF_8), String.valueOf(sig).getBytes(StandardCharsets.UTF_8));
        long now = clock.getAsLong();
        if (!current && !previous) throw new AppApiManager.ApiError(403, "Dieser Erinnerungslink ist abgelaufen – bitte die Werbe-Zentrale öffnen.");
        if (previous && (op.equals("snooze") || now - p.lastPostedAt > 30 * 60_000L)) throw new AppApiManager.ApiError(410, "Hier wurde bereits geworben (" + clock(p.lastPostedAt) + "). Nächste Erinnerung " + clock(p.dueAt()) + ".");
        Map<String, Object> out = payload(p);
        switch (op) {
            case "open" -> {
                if (current) { markPosted(p, now, "Bark-Link"); out.put("notice", "Kopiert – nächste Erinnerung " + clock(p.nextDueAt)); }
                else out.put("notice", "Bereits kopiert – nächste Erinnerung " + clock(p.nextDueAt));
            }
            case "peek" -> out.put("notice", "");
            case "snooze" -> out.putAll(snooze(p.id, minutes, "Bark-Link"));
            default -> throw new IllegalArgumentException("Unbekannte Aktion");
        }
        out.put("nextDueAt", p.nextDueAt); out.put("dueAt", p.dueAt());
        if (op.equals("peek") && current) { out.put("cycle", p.cycle); out.put("k", signature(p.id, p.cycle)); }
        return out;
    }

    /** Account rename: reminders keep reaching the renamed account. */
    synchronized void renameOwner(String from, String to, String label) {
        boolean changed = false;
        for (Place p : places.values()) if (p.owner.equals(from)) { p.owner = to; p.ownerLabel = label; changed = true; }
        if (changed) save();
    }

    // ================================================================== internals

    private Place place(String id) { Place p = places.get(id); if (p == null) throw new IllegalArgumentException("Eintrag nicht gefunden"); return p; }

    private void history(Place p, String kind, String text) {
        p.history.add(0, SimplexAdminSuite.appMap("time", clock.getAsLong(), "kind", kind, "text", clip(text, 300)));
        while (p.history.size() > HISTORY) p.history.remove(p.history.size() - 1);
    }

    private void log(String kind, String text) {
        log.addFirst(SimplexAdminSuite.appMap("time", clock.getAsLong(), "kind", kind, "text", clip(text, 300)));
        while (log.size() > LOG) log.removeLast();
    }

    String clock(long millis) { return millis <= 0 ? "–" : CLOCK.format(Instant.ofEpochMilli(millis)); }
    private String newId() { byte[] b = new byte[9]; random.nextBytes(b); return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    static String str(Object v) { return v == null ? "" : v instanceof Double d && d == Math.rint(d) ? Long.toString(d.longValue()) : String.valueOf(v); }
    static String clip(String v, int max) { return v.length() <= max ? v : v.substring(0, max); }
    static long lng(Object v) { if (v instanceof Number n) return n.longValue(); try { return v == null || str(v).isBlank() ? 0 : Long.parseLong(str(v).trim()); } catch (NumberFormatException e) { try { return (long) Double.parseDouble(str(v)); } catch (NumberFormatException e2) { return 0; } } }
    static double dbl(Object v, double fallback) { if (v instanceof Number n) return n.doubleValue(); try { return v == null || str(v).isBlank() ? fallback : Double.parseDouble(str(v).replace(',', '.')); } catch (NumberFormatException e) { return fallback; } }
    static boolean bool(Object v, boolean fallback) { return v == null ? fallback : v instanceof Boolean b ? b : Boolean.parseBoolean(str(v)); }
    static int clampInt(Object v, int fallback, int min, int max) { long n = v == null || str(v).isBlank() ? fallback : lng(v); return (int) Math.max(min, Math.min(max, n)); }
    @SuppressWarnings("unchecked") static Map<String, Object> map(Object v) { if (v instanceof Map<?, ?> m) { Map<String, Object> out = new LinkedHashMap<>(); m.forEach((k, x) -> out.put(String.valueOf(k), x)); return out; } return new LinkedHashMap<>(); }
    static List<?> list(Object v) { return v instanceof List<?> l ? l : List.of(); }
}
