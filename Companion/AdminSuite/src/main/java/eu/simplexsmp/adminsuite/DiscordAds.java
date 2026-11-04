package eu.simplexsmp.adminsuite;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 10.4.0 Discord-Werbeplaner: posts saved advertisement texts with a linked Discord user account into channels the
 * owner picked, each with its own interval. Everything runs server-side; the app and the web panel only edit.
 *
 * <ul>
 *   <li>Timers are absolute timestamps in {@code discord-ads/state.json}, so restarts never reset them.</li>
 *   <li>Every next post comes a little <b>after</b> the configured interval (never earlier), and never before the
 *       channel's slowmode allows it.</li>
 *   <li>Before posting the channel is checked for an own recent post; slowmode replies are rescheduled instead of
 *       counted as failures; posts removed shortly after sending (moderation/timer bots) are detected.</li>
 *   <li>Failures can notify via Bark and pause a channel after N failures in a row.</li>
 *   <li>Tokens are stored AES-GCM encrypted and are never returned to any client.</li>
 * </ul>
 * Automating a user account is against Discord's terms; the app and web panel say so before an account is linked.
 */
final class DiscordAds {
    /** Side effects outside this class; the plugin implements them, the harness records them. */
    interface Hooks {
        void notify(String title, String body, boolean pause);
        void audit(String actor, String action, String detail);
        Map<String, String> placeholders();
        void warn(String message);
    }

    static final String API = "https://discord.com/api/v9";
    static final int MAX_ACCOUNTS = 5, MAX_TEMPLATES = 50, MAX_CAMPAIGNS = 60, MAX_CONTENT = 4000, DISCORD_LIMIT = 2000, HISTORY = 30, LOG = 200;
    static final long MIN_INTERVAL_MINUTES = 5, MAX_INTERVAL_MINUTES = 7L * 24 * 60;
    static final long ADMINISTRATOR = 1L << 3, VIEW_CHANNEL = 1L << 10, SEND_MESSAGES = 1L << 11, EMBED_LINKS = 1L << 14, READ_HISTORY = 1L << 16;
    private static final long DISCORD_EPOCH = 1420070400000L;
    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("dd.MM. HH:mm", Locale.GERMANY).withZone(ZONE);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMANY).withZone(ZONE);
    private static final String USER_AGENT = "Mozilla/5.0 (compatible; SimplexAdminSuite/10.4.0)";

    private final Path folder, stateFile, keyFile;
    private final Hooks hooks;
    private final String apiBase;
    private final LongSupplier clock;
    private final Random random;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final SecretKeySpec key;
    private final Map<String, Account> accounts = new LinkedHashMap<>();
    private final Map<String, Template> templates = new LinkedHashMap<>();
    private final Map<String, Campaign> campaigns = new LinkedHashMap<>();
    private final Deque<Map<String, Object>> log = new ArrayDeque<>();
    private final Settings settings = new Settings();
    private final Map<String, Object[]> guildCache = new ConcurrentHashMap<>();
    private ScheduledExecutorService worker;
    private volatile String lastTickProblem = "";
    private long lastHealthCheck;

    DiscordAds(Path folder, Hooks hooks, String apiBase, LongSupplier clock, Random random) throws IOException {
        this.folder = folder; this.hooks = hooks; this.apiBase = apiBase.replaceAll("/+$", ""); this.clock = clock; this.random = random;
        stateFile = folder.resolve("state.json"); keyFile = folder.resolve("secret.key");
        Files.createDirectories(folder);
        key = new SecretKeySpec(loadKey(), "AES");
        load();
    }

    // ================================================================== model

    static final class Account {
        String id = "", userId = "", username = "", globalName = "", avatar = "", tokenCipher = "", token = "", linkedBy = "", status = "ok", statusText = "";
        long linkedAt, checkedAt, lastPostAt, nextAllowedAt, blockedUntil; int sent;
        String name() { return globalName.isBlank() ? username : globalName; }
        boolean usable(long now) { return status.equals("ok") && !token.isEmpty() && blockedUntil <= now; }
    }

    static final class Template {
        String id = "", name = "", content = "", updatedBy = ""; boolean suppressEmbeds, preset; long createdAt, updatedAt;
    }

    static final class Campaign {
        String id = "", accountId = "", accountUserId = "", guildId = "", guildName = "", guildIcon = "", channelId = "", channelName = "", category = "", templateId = "";
        String pausedReason = "", lastResult = "", lastError = "", lastMessageId = "", verifyMessageId = "", createdBy = "", confirmedBy = "";
        long intervalMinutes = 360, nextRunAt, lastSentAt, lastAttemptAt, verifyAt, createdAt, confirmedAt, updatedAt;
        boolean enabled = true, notify = true;
        int pauseAfter = 3, consecutiveFailures, transientFailures, slowmodeStreak, sentCount, failedCount, deletedCount, slowmodeSeconds, slowmodeCorrections;
        final List<Map<String, Object>> history = new ArrayList<>();
        long effectiveMillis() { return Math.max(intervalMinutes * 60_000L, slowmodeSeconds * 1000L); }
    }

    static final class Settings {
        boolean notify = true, verifyDelete = true, checkOwnPosts = true;
        int pauseAfter = 3, gapSeconds = 60, maxPerGuild = 4;
    }

    // ================================================================== timing (pure, unit-tested)

    /**
     * Delay until the next post: always the full interval (or the channel slowmode when that is longer) plus a small
     * random extra – 0.4–2 % of the interval, at least ~½–1½ minutes (at most a fifth of the interval) and at most one
     * hour. 24 h therefore becomes 24.1–24.5 h, 6 h becomes about 6.03–6.12 h. Never shorter than the interval.
     */
    static long delayMillis(long intervalMinutes, int slowmodeSeconds, Random random) {
        long base = Math.max(intervalMinutes * 60_000L, slowmodeSeconds * 1000L);
        double pct = 0.004 + random.nextDouble() * 0.016;
        long floor = Math.min(base / 5, 30_000L + random.nextInt(60_001));
        long extra = Math.min(3_600_000L, Math.max(floor, Math.round(base * pct)));
        return base + Math.max(1_000L, extra);
    }

    /** Smallest and largest delay {@link #delayMillis} can produce, for the "next post between … and …" display. */
    static long[] window(long intervalMinutes, int slowmodeSeconds) {
        long base = Math.max(intervalMinutes * 60_000L, slowmodeSeconds * 1000L);
        long low = Math.min(3_600_000L, Math.max(Math.min(base / 5, 30_000L), Math.round(base * 0.004)));
        long high = Math.min(3_600_000L, Math.max(Math.min(base / 5, 90_000L), Math.round(base * 0.02)));
        return new long[]{base + Math.max(1_000L, low), base + Math.max(1_000L, high)};
    }

    private static final Pattern DURATION = Pattern.compile("(?<![0-9a-z])(\\d{1,3})\\s*-?\\s*(minuten|minutes|minute|mins|min|m|stunden|stunde|std|hours|hour|hrs|hr|h|tage|tag|days|day|d)(?![a-z])", Pattern.CASE_INSENSITIVE);
    private static final Pattern TOPIC_RULE = Pattern.compile("(every|per|alle|jede|cooldown|once|1x|einmal|slowmode|wait|warte|interval|intervall|timer)", Pattern.CASE_INSENSITIVE);

    /** Interval suggested by a channel name ("6h", "15m", "ads-24h") or its topic ("1 post every 12 hours"). */
    static Long suggestMinutes(String name, String topic) {
        Long fromName = firstDuration(name == null ? "" : name.replace('_', '-'));
        if (fromName != null) return fromName;
        String t = topic == null ? "" : topic;
        if (!TOPIC_RULE.matcher(t).find()) return null;
        return firstDuration(t);
    }

    private static Long firstDuration(String text) {
        Matcher m = DURATION.matcher(text);
        while (m.find()) {
            long n = Long.parseLong(m.group(1)); if (n <= 0) continue;
            String u = m.group(2).toLowerCase(Locale.ROOT);
            long minutes = u.startsWith("m") ? n : u.startsWith("d") || u.startsWith("t") ? n * 1440 : n * 60;
            return Math.max(MIN_INTERVAL_MINUTES, Math.min(MAX_INTERVAL_MINUTES, minutes));
        }
        return null;
    }

    private static final Pattern AD_WORDS = Pattern.compile("(werbung|advert|advertis|(^|[^a-z])ads?([^a-z]|$)|promo|partner|showcase|serverlist|server-?list|(^|[^a-z])servers?([^a-z]|$)|realms?|content|bump|instant|timed|plug|(^|[^a-z0-9])\\d{1,3}\\s*[mhd]([^a-z]|$))", Pattern.CASE_INSENSITIVE);
    private static final Pattern WARN_WORDS = Pattern.compile("(general|allgemein|(^|[^a-z])chat|talk|lounge|off-?topic|rules|regeln|welcome|willkommen|announce|ankündig|news|support|help|hilfe|ticket|staff|(^|[^a-z])mod|logs?([^a-z]|$)|vorstell|introduc|memes?|media|giveaway|verify|verifiz)", Pattern.CASE_INSENSITIVE);

    /** "ad" when the name looks like an advertising channel, "warn" when it looks like a normal chat/info channel. */
    static String channelHint(String name) {
        String n = name == null ? "" : name;
        if (WARN_WORDS.matcher(n).find()) return "warn";
        if (AD_WORDS.matcher(n).find()) return "ad";
        return "neutral";
    }

    /** Discord's channel permission algorithm (guild base permissions → @everyone → roles → member overwrites). */
    static long channelPermissions(long base, boolean owner, String guildId, String userId, Set<String> roles, List<?> overwrites) {
        if (owner || (base & ADMINISTRATOR) != 0) return -1L;
        long perms = base;
        Map<String, Object> everyone = null, member = null;
        long allow = 0, deny = 0;
        for (Object o : overwrites == null ? List.of() : overwrites) {
            if (!(o instanceof Map<?, ?> raw)) continue;
            @SuppressWarnings("unchecked") Map<String, Object> ow = (Map<String, Object>) raw;
            String id = str(ow.get("id"));
            if (id.equals(guildId)) everyone = ow;
            else if (id.equals(userId) && "1".equals(str(ow.get("type")))) member = ow;
            else if (roles.contains(id)) { allow |= bits(ow.get("allow")); deny |= bits(ow.get("deny")); }
        }
        if (everyone != null) { perms &= ~bits(everyone.get("deny")); perms |= bits(everyone.get("allow")); }
        perms &= ~deny; perms |= allow;
        if (member != null) { perms &= ~bits(member.get("deny")); perms |= bits(member.get("allow")); }
        return perms;
    }

    private static long bits(Object v) { try { return Long.parseLong(str(v).isBlank() ? "0" : str(v)); } catch (NumberFormatException e) { return 0; } }
    static String str(Object v) { return v == null ? "" : v instanceof Double d && d == Math.rint(d) ? Long.toString(d.longValue()) : String.valueOf(v); }

    /** Fills {online}, {max}, {date} and any extra placeholder the plugin provides. */
    String render(String content) {
        String out = content;
        Map<String, String> values = new HashMap<>();
        try { values.putAll(hooks.placeholders()); } catch (RuntimeException ignored) { }
        values.putIfAbsent("{date}", DATE.format(Instant.ofEpochMilli(clock.getAsLong())));
        for (var e : values.entrySet()) out = out.replace(e.getKey(), e.getValue());
        return out;
    }

    // ================================================================== persistence

    private byte[] loadKey() throws IOException {
        if (Files.exists(keyFile)) {
            byte[] raw = Base64.getDecoder().decode(Files.readString(keyFile, StandardCharsets.UTF_8).trim());
            if (raw.length != 32) throw new IOException("discord-ads/secret.key beschädigt");
            return raw;
        }
        byte[] raw = new byte[32]; new SecureRandom().nextBytes(raw);
        Files.writeString(keyFile, Base64.getEncoder().encodeToString(raw), StandardCharsets.UTF_8);
        try { Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------")); } catch (UnsupportedOperationException | IOException ignored) { }
        return raw;
    }

    String encrypt(String token) {
        try {
            byte[] iv = new byte[12]; new SecureRandom().nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv)); c.updateAAD("simplex-discord-ads".getBytes(StandardCharsets.UTF_8));
            byte[] ct = c.doFinal(token.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length]; System.arraycopy(iv, 0, out, 0, 12); System.arraycopy(ct, 0, out, 12, ct.length);
            return "v1:" + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) { throw new IllegalStateException("Verschlüsselung nicht verfügbar", e); }
    }

    String decrypt(String value) {
        try {
            if (!value.startsWith("v1:")) return "";
            byte[] raw = Base64.getDecoder().decode(value.substring(3));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, raw, 0, 12)); c.updateAAD("simplex-discord-ads".getBytes(StandardCharsets.UTF_8));
            return new String(c.doFinal(raw, 12, raw.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) { return ""; }
    }

    @SuppressWarnings("unchecked")
    private void load() throws IOException {
        if (!Files.exists(stateFile)) { seedTemplates(); persist(); return; }
        Map<String, Object> root;
        try { root = (Map<String, Object>) DiscordBotManager.Json.parse(Files.readString(stateFile, StandardCharsets.UTF_8)); }
        catch (RuntimeException e) {
            Path aside = folder.resolve("state.json.unreadable-" + clock.getAsLong());
            Files.copy(stateFile, aside, StandardCopyOption.REPLACE_EXISTING);
            hooks.warn("Discord-Werbung: state.json war nicht lesbar und wurde als " + aside.getFileName() + " gesichert; es wird neu begonnen.");
            seedTemplates(); persist(); return;
        }
        Map<String, Object> s = map(root.get("settings"));
        settings.notify = bool(s.get("notify"), true); settings.verifyDelete = bool(s.get("verifyDelete"), true); settings.checkOwnPosts = bool(s.get("checkOwnPosts"), true);
        settings.pauseAfter = clampInt(s.get("pauseAfter"), 3, 0, 10); settings.gapSeconds = clampInt(s.get("gapSeconds"), 60, 20, 900); settings.maxPerGuild = clampInt(s.get("maxPerGuild"), 4, 1, 10);
        for (Object o : list(root.get("accounts"))) {
            Map<String, Object> m = map(o); Account a = new Account();
            a.id = str(m.get("id")); a.userId = str(m.get("userId")); a.username = str(m.get("username")); a.globalName = str(m.get("globalName")); a.avatar = str(m.get("avatar"));
            a.tokenCipher = str(m.get("token")); a.linkedBy = str(m.get("linkedBy")); a.status = str(m.getOrDefault("status", "ok")); a.statusText = str(m.get("statusText"));
            a.linkedAt = lng(m.get("linkedAt")); a.checkedAt = lng(m.get("checkedAt")); a.lastPostAt = lng(m.get("lastPostAt")); a.blockedUntil = lng(m.get("blockedUntil")); a.sent = (int) lng(m.get("sent"));
            a.token = decrypt(a.tokenCipher);
            if (a.token.isEmpty()) { a.status = "locked"; a.statusText = "Token nicht entschlüsselbar (secret.key fehlt oder wurde ersetzt) – bitte Account neu verknüpfen."; }
            if (!a.id.isEmpty()) accounts.put(a.id, a);
        }
        for (Object o : list(root.get("templates"))) {
            Map<String, Object> m = map(o); Template t = new Template();
            t.id = str(m.get("id")); t.name = str(m.get("name")); t.content = str(m.get("content")); t.updatedBy = str(m.get("updatedBy"));
            t.suppressEmbeds = bool(m.get("suppressEmbeds"), false); t.preset = bool(m.get("preset"), false); t.createdAt = lng(m.get("createdAt")); t.updatedAt = lng(m.get("updatedAt"));
            if (!t.id.isEmpty()) templates.put(t.id, t);
        }
        for (Object o : list(root.get("campaigns"))) {
            Map<String, Object> m = map(o); Campaign c = new Campaign();
            c.id = str(m.get("id")); c.accountId = str(m.get("accountId")); c.accountUserId = str(m.get("accountUserId")); c.guildId = str(m.get("guildId")); c.guildName = str(m.get("guildName"));
            c.guildIcon = str(m.get("guildIcon")); c.channelId = str(m.get("channelId")); c.channelName = str(m.get("channelName")); c.category = str(m.get("category")); c.templateId = str(m.get("templateId"));
            c.pausedReason = str(m.get("pausedReason")); c.lastResult = str(m.get("lastResult")); c.lastError = str(m.get("lastError")); c.lastMessageId = str(m.get("lastMessageId"));
            c.verifyMessageId = str(m.get("verifyMessageId")); c.createdBy = str(m.get("createdBy")); c.confirmedBy = str(m.get("confirmedBy"));
            c.intervalMinutes = Math.max(MIN_INTERVAL_MINUTES, Math.min(MAX_INTERVAL_MINUTES, lng(m.getOrDefault("intervalMinutes", 360L))));
            c.nextRunAt = lng(m.get("nextRunAt")); c.lastSentAt = lng(m.get("lastSentAt")); c.lastAttemptAt = lng(m.get("lastAttemptAt")); c.verifyAt = lng(m.get("verifyAt"));
            c.createdAt = lng(m.get("createdAt")); c.confirmedAt = lng(m.get("confirmedAt")); c.updatedAt = lng(m.get("updatedAt"));
            c.enabled = bool(m.get("enabled"), true); c.notify = bool(m.get("notify"), true); c.pauseAfter = clampInt(m.get("pauseAfter"), 3, 0, 10);
            c.consecutiveFailures = (int) lng(m.get("consecutiveFailures")); c.transientFailures = (int) lng(m.get("transientFailures")); c.slowmodeStreak = (int) lng(m.get("slowmodeStreak"));
            c.sentCount = (int) lng(m.get("sentCount")); c.failedCount = (int) lng(m.get("failedCount")); c.deletedCount = (int) lng(m.get("deletedCount"));
            c.slowmodeSeconds = (int) lng(m.get("slowmodeSeconds")); c.slowmodeCorrections = (int) lng(m.get("slowmodeCorrections"));
            for (Object h : list(m.get("history"))) if (h instanceof Map<?, ?>) c.history.add(map(h));
            if (!c.id.isEmpty()) campaigns.put(c.id, c);
        }
        for (Object o : list(root.get("log"))) if (o instanceof Map<?, ?>) log.addLast(map(o));
        if (root.get("templates") == null) seedTemplates();
    }

    private synchronized void persist() throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", 1);
        root.put("settings", SimplexAdminSuite.appMap("notify", settings.notify, "verifyDelete", settings.verifyDelete, "checkOwnPosts", settings.checkOwnPosts, "pauseAfter", settings.pauseAfter, "gapSeconds", settings.gapSeconds, "maxPerGuild", settings.maxPerGuild));
        List<Object> a = new ArrayList<>();
        for (Account x : accounts.values()) a.add(SimplexAdminSuite.appMap("id", x.id, "userId", x.userId, "username", x.username, "globalName", x.globalName, "avatar", x.avatar, "token", x.tokenCipher,
            "linkedBy", x.linkedBy, "status", x.status, "statusText", x.statusText, "linkedAt", x.linkedAt, "checkedAt", x.checkedAt, "lastPostAt", x.lastPostAt, "blockedUntil", x.blockedUntil, "sent", x.sent));
        root.put("accounts", a);
        List<Object> t = new ArrayList<>();
        for (Template x : templates.values()) t.add(SimplexAdminSuite.appMap("id", x.id, "name", x.name, "content", x.content, "suppressEmbeds", x.suppressEmbeds, "preset", x.preset, "createdAt", x.createdAt, "updatedAt", x.updatedAt, "updatedBy", x.updatedBy));
        root.put("templates", t);
        List<Object> c = new ArrayList<>();
        for (Campaign x : campaigns.values()) {
            Map<String, Object> m = SimplexAdminSuite.appMap("id", x.id, "accountId", x.accountId, "accountUserId", x.accountUserId, "guildId", x.guildId, "guildName", x.guildName, "guildIcon", x.guildIcon,
                "channelId", x.channelId, "channelName", x.channelName, "category", x.category, "templateId", x.templateId, "intervalMinutes", x.intervalMinutes, "enabled", x.enabled, "notify", x.notify,
                "pauseAfter", x.pauseAfter, "pausedReason", x.pausedReason, "nextRunAt", x.nextRunAt, "lastSentAt", x.lastSentAt, "lastAttemptAt", x.lastAttemptAt, "lastResult", x.lastResult, "lastError", x.lastError,
                "lastMessageId", x.lastMessageId, "verifyAt", x.verifyAt, "verifyMessageId", x.verifyMessageId, "consecutiveFailures", x.consecutiveFailures, "transientFailures", x.transientFailures,
                "slowmodeStreak", x.slowmodeStreak, "sentCount", x.sentCount, "failedCount", x.failedCount, "deletedCount", x.deletedCount, "slowmodeSeconds", x.slowmodeSeconds,
                "slowmodeCorrections", x.slowmodeCorrections, "createdAt", x.createdAt, "createdBy", x.createdBy, "confirmedAt", x.confirmedAt, "confirmedBy", x.confirmedBy, "updatedAt", x.updatedAt);
            m.put("history", new ArrayList<>(x.history));
            c.add(m);
        }
        root.put("campaigns", c);
        root.put("log", new ArrayList<>(log));
        Path temp = Files.createTempFile(folder, "state", ".tmp");
        try {
            Files.writeString(temp, DiscordBotManager.Json.stringify(root), StandardCharsets.UTF_8);
            try { Files.move(temp, stateFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, stateFile, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }

    private void save() { try { persist(); } catch (IOException e) { hooks.warn("Discord-Werbung: Speichern fehlgeschlagen (" + e.getClass().getSimpleName() + ")"); } }

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

    private void seedTemplates() {
        long now = clock.getAsLong();
        String[][] rows = {{"Simplex SMP – Discord", SIMPLEX_AD}, {"Simplex SMP – Kurz", SIMPLEX_AD_SHORT}, {"Simplex SMP – Deutsch", SIMPLEX_AD_DE}};
        for (String[] r : rows) { Template t = new Template(); t.id = newId(); t.name = r[0]; t.content = r[1]; t.preset = true; t.createdAt = now; t.updatedAt = now; t.updatedBy = "Vorlage"; templates.put(t.id, t); }
    }

    // ================================================================== lifecycle

    void start() {
        resumeAfterStart();
        worker = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "SimplexDiscordAds"); t.setDaemon(true); return t; });
        worker.scheduleWithFixedDelay(() -> { try { tick(); lastTickProblem = ""; } catch (Throwable e) { lastTickProblem = e.getClass().getSimpleName(); hooks.warn("Discord-Werbung: Planerfehler " + e.getClass().getSimpleName()); } }, 5, 10, TimeUnit.SECONDS);
    }

    /** Overdue timers (server was offline) are staggered a little; future timers stay exactly as stored. */
    void resumeAfterStart() {
        long now = clock.getAsLong();
        synchronized (this) {
            int index = 0; boolean changed = false;
            List<Campaign> list = new ArrayList<>(campaigns.values()); list.sort(Comparator.comparingLong(c -> c.nextRunAt));
            for (Campaign c : list) {
                if (!c.enabled) continue;
                if (c.nextRunAt < now + 60_000L) {
                    boolean overdue = c.nextRunAt > 0 && c.nextRunAt < now - 120_000L;
                    c.nextRunAt = now + 60_000L + index++ * (30_000L + random.nextInt(60_001));
                    if (c.lastSentAt > 0) c.nextRunAt = Math.max(c.nextRunAt, c.lastSentAt + c.effectiveMillis());
                    if (overdue) history(c, "info", "Server war offline – der fällige Post wird jetzt nachgeholt (" + clock(c.nextRunAt) + ").", null);
                    changed = true;
                }
            }
            if (changed) save();
        }
    }

    void stop() {
        if (worker != null) { worker.shutdownNow(); try { worker.awaitTermination(3, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
        synchronized (this) { save(); }
    }

    // ================================================================== Discord HTTP

    record Resp(int status, Object json, Map<String, List<String>> headers) {
        Map<String, Object> obj() { return json instanceof Map<?, ?> ? map(json) : Map.of(); }
        int code() { return (int) lng(obj().get("code")); }
        String message() { return str(obj().get("message")); }
        double retryAfter() { Object v = obj().get("retry_after"); if (v instanceof Number n) return n.doubleValue(); List<String> h = headers.get("retry-after"); try { return h == null || h.isEmpty() ? 5 : Double.parseDouble(h.get(0)); } catch (NumberFormatException e) { return 5; } }
    }

    Resp request(String token, String method, String path, Object body) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(apiBase + path)).timeout(Duration.ofSeconds(20))
            .header("Authorization", token).header("Accept", "application/json").header("User-Agent", USER_AGENT);
        if (body == null) b.method(method, HttpRequest.BodyPublishers.noBody());
        else b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(DiscordBotManager.Json.stringify(body), StandardCharsets.UTF_8));
        HttpResponse<InputStream> r = http.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
        byte[] bytes;
        try (InputStream in = r.body()) { bytes = in.readNBytes(2 * 1024 * 1024 + 1); }
        if (bytes.length > 2 * 1024 * 1024) throw new IOException("Discord-Antwort zu groß");
        Object json = null;
        String text = new String(bytes, StandardCharsets.UTF_8).trim();
        if (!text.isEmpty()) try { json = DiscordBotManager.Json.parse(text); } catch (RuntimeException e) { json = null; }
        Map<String, List<String>> headers = new HashMap<>();
        r.headers().map().forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), v));
        return new Resp(r.statusCode(), json, headers);
    }

    /** API-thread helper: turns Discord errors into readable German messages. */
    private Resp call(Account a, String method, String path) {
        Resp r;
        try { r = request(a.token, method, path, null); }
        catch (IOException e) { throw new IllegalArgumentException("Discord nicht erreichbar: " + e.getClass().getSimpleName()); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalArgumentException("Abgebrochen"); }
        if (r.status == 401) { synchronized (this) { markAccount(a, "invalid", "Discord hat den Token abgelehnt (abgemeldet oder Passwort geändert). Bitte neu verknüpfen."); save(); } throw new IllegalArgumentException("Discord-Token ungültig – bitte Account neu verknüpfen."); }
        if (r.status == 429) throw new IllegalArgumentException("Discord bremst gerade – in " + Math.max(1, Math.round(r.retryAfter())) + " s erneut versuchen.");
        return r;
    }

    // ================================================================== accounts

    static String cleanToken(String raw) {
        String t = raw == null ? "" : raw.trim();
        if (t.length() >= 2 && (t.startsWith("\"") && t.endsWith("\"") || t.startsWith("'") && t.endsWith("'"))) t = t.substring(1, t.length() - 1).trim();
        if (t.regionMatches(true, 0, "Bearer ", 0, 7)) t = t.substring(7).trim();
        if (t.regionMatches(true, 0, "Bot ", 0, 4)) throw new IllegalArgumentException("Das ist ein Bot-Token. Hier wird der Token eines normalen (Zweit-)Accounts benötigt.");
        if (t.length() < 50 || t.length() > 200 || !t.matches("[A-Za-z0-9_\\-.]+")) throw new IllegalArgumentException("Das sieht nicht wie ein Discord-Token aus.");
        return t;
    }

    private static String avatarUrl(String userId, String avatar) {
        if (!avatar.isBlank() && avatar.matches("[a-z0-9_]{1,64}")) return "https://cdn.discordapp.com/avatars/" + userId + "/" + avatar + ".png?size=128";
        long index; try { index = (Long.parseLong(userId) >> 22) % 6; } catch (NumberFormatException e) { index = 0; }
        return "https://cdn.discordapp.com/embed/avatars/" + index + ".png";
    }

    private static String iconUrl(String guildId, String icon) {
        return icon == null || icon.isBlank() || !icon.matches("[a-z0-9_]{1,64}") ? "" : "https://cdn.discordapp.com/icons/" + guildId + "/" + icon + ".png?size=128";
    }

    /** Links (or re-links) an account. Throws IllegalArgumentException with a readable message on any problem. */
    Map<String, Object> link(String rawToken, String accountId, boolean accepted, String actor) {
        if (!accepted) throw new IllegalArgumentException("Bitte bestätige den Hinweis zu den Discord-Nutzungsbedingungen.");
        String token = cleanToken(rawToken);
        Resp r;
        try { r = request(token, "GET", "/users/@me", null); }
        catch (IOException e) { throw new IllegalArgumentException("Discord nicht erreichbar: " + e.getClass().getSimpleName()); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalArgumentException("Abgebrochen"); }
        if (r.status == 401 || r.status == 403) throw new IllegalArgumentException("Discord hat den Token abgelehnt. Prüfe, ob er vollständig kopiert wurde.");
        if (r.status != 200) throw new IllegalArgumentException("Discord antwortet mit " + r.status + (r.message().isBlank() ? "" : ": " + r.message()));
        Map<String, Object> me = r.obj();
        if (bool(me.get("bot"), false)) throw new IllegalArgumentException("Bot-Accounts können nicht verwendet werden.");
        String userId = str(me.get("id"));
        if (userId.isBlank()) throw new IllegalArgumentException("Discord hat keinen Account geliefert.");
        long now = clock.getAsLong();
        synchronized (this) {
            Account a;
            if (accountId != null && !accountId.isBlank()) {
                a = accounts.get(accountId);
                if (a == null) throw new IllegalArgumentException("Account nicht gefunden");
                if (!a.userId.equals(userId)) throw new IllegalArgumentException("Dieser Token gehört zu „" + str(me.get("username")) + "“, nicht zu „" + a.name() + "“. Zum Wechseln den alten Account abmelden und den neuen verknüpfen.");
            } else {
                a = accounts.values().stream().filter(x -> x.userId.equals(userId)).findFirst().orElse(null);
                if (a == null) {
                    if (accounts.size() >= MAX_ACCOUNTS) throw new IllegalArgumentException("Höchstens " + MAX_ACCOUNTS + " Accounts");
                    a = new Account(); a.id = newId(); a.userId = userId; a.linkedAt = now; accounts.put(a.id, a);
                }
            }
            a.username = clip(str(me.get("username")), 64); a.globalName = clip(str(me.get("global_name")).equals("null") ? "" : str(me.get("global_name")), 64);
            a.avatar = str(me.get("avatar")).equals("null") ? "" : str(me.get("avatar"));
            a.token = token; a.tokenCipher = encrypt(token); a.linkedBy = actor; a.checkedAt = now; a.blockedUntil = 0;
            markAccount(a, "ok", "");
            int reattached = 0;
            for (Campaign c : campaigns.values()) if (c.accountUserId.equals(userId) && !c.accountId.equals(a.id) && !accounts.containsKey(c.accountId)) { c.accountId = a.id; reattached++; history(c, "info", "Wieder mit " + a.name() + " verknüpft.", null); }
            guildCache.remove(a.id);
            addLog(null, "account", "Account " + a.name() + " verknüpft" + (reattached > 0 ? " – " + reattached + " Kanäle wieder zugeordnet" : ""));
            save();
            hooks.audit(actor, "Discord-Werbung: Account verknüpft", a.name() + " (" + a.userId + ")");
            Map<String, Object> out = accountView(a);
            out.put("reattached", reattached);
            return out;
        }
    }

    Map<String, Object> checkAccount(String accountId, String actor) {
        Account a; synchronized (this) { a = accounts.get(accountId); }
        if (a == null) throw new IllegalArgumentException("Account nicht gefunden");
        if (a.token.isEmpty()) throw new IllegalArgumentException("Kein Token gespeichert – bitte neu verknüpfen.");
        Resp r = call(a, "GET", "/users/@me");
        synchronized (this) {
            a.checkedAt = clock.getAsLong();
            if (r.status == 200) { Map<String, Object> me = r.obj(); a.username = clip(str(me.get("username")), 64); a.globalName = str(me.get("global_name")).equals("null") ? "" : clip(str(me.get("global_name")), 64); a.avatar = str(me.get("avatar")).equals("null") ? "" : str(me.get("avatar")); a.blockedUntil = 0; markAccount(a, "ok", ""); }
            else markAccount(a, "error", "Discord antwortet mit " + r.status + (r.message().isBlank() ? "" : ": " + r.message()));
            guildCache.remove(a.id);
            save();
            return accountView(a);
        }
    }

    /** Removes a linked account. Its channels stay (paused) unless deleteCampaigns is set; relinking the same Discord user resumes them. */
    Map<String, Object> unlink(String accountId, boolean logout, boolean deleteCampaigns, String actor) {
        Account a; synchronized (this) { a = accounts.get(accountId); }
        if (a == null) throw new IllegalArgumentException("Account nicht gefunden");
        String loggedOut = "";
        if (logout && !a.token.isEmpty()) {
            try { Resp r = request(a.token, "POST", "/auth/logout", SimplexAdminSuite.appMap("provider", null, "voip_provider", null)); loggedOut = r.status / 100 == 2 ? " Discord-Sitzung beendet." : " Abmelden bei Discord nicht bestätigt (" + r.status + ") – zur Sicherheit das Passwort ändern."; }
            catch (Exception e) { loggedOut = " Discord nicht erreichbar – zur Sicherheit das Passwort ändern."; }
        }
        synchronized (this) {
            accounts.remove(accountId); guildCache.remove(accountId);
            int removed = 0, paused = 0;
            for (var it = campaigns.values().iterator(); it.hasNext(); ) {
                Campaign c = it.next(); if (!c.accountId.equals(accountId)) continue;
                if (deleteCampaigns) { it.remove(); removed++; }
                else { if (c.enabled) paused++; c.enabled = false; c.pausedReason = "Account abgemeldet – zum Fortsetzen denselben Account wieder verknüpfen."; history(c, "paused", c.pausedReason, null); }
            }
            addLog(null, "account", "Account " + a.name() + " abgemeldet");
            save();
            hooks.audit(actor, "Discord-Werbung: Account abgemeldet", a.name() + (deleteCampaigns ? " • " + removed + " Kanäle gelöscht" : " • " + paused + " Kanäle pausiert"));
            return SimplexAdminSuite.appMap("notice", "„" + a.name() + "“ abgemeldet. " + (deleteCampaigns ? removed + " Kanäle gelöscht." : paused + " Kanäle pausiert – sie laufen weiter, sobald du denselben Account wieder verknüpfst.") + loggedOut);
        }
    }

    private void markAccount(Account a, String status, String text) {
        boolean changed = !a.status.equals(status);
        a.status = status; a.statusText = text;
        if (changed && !status.equals("ok")) {
            addLog(null, "account", a.name() + ": " + text);
            if (settings.notify) hooks.notify("Discord-Werbung: Account braucht Aufmerksamkeit", a.name() + " – " + text, true);
        }
    }

    // ================================================================== guilds & channels

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> guilds(String accountId, boolean refresh) {
        Account a = usableAccount(accountId);
        Object[] cached = guildCache.get(a.id);
        long now = clock.getAsLong();
        List<Map<String, Object>> raw;
        if (!refresh && cached != null && now - (long) cached[0] < 120_000L) raw = (List<Map<String, Object>>) cached[1];
        else {
            Resp r = call(a, "GET", "/users/@me/guilds?with_counts=true");
            if (r.status != 200 || !(r.json instanceof List<?> list)) throw new IllegalArgumentException("Serverliste nicht verfügbar (" + r.status + ")");
            raw = new ArrayList<>();
            for (Object o : list) raw.add(map(o));
            guildCache.put(a.id, new Object[]{now, raw});
        }
        List<Map<String, Object>> out = new ArrayList<>();
        synchronized (this) {
            for (Map<String, Object> g : raw) {
                String id = str(g.get("id"));
                long used = campaigns.values().stream().filter(c -> c.guildId.equals(id) && c.accountId.equals(a.id)).count();
                out.add(SimplexAdminSuite.appMap("id", id, "name", str(g.get("name")), "icon", iconUrl(id, str(g.get("icon"))), "owner", bool(g.get("owner"), false),
                    "members", lng(g.get("approximate_member_count")), "online", lng(g.get("approximate_presence_count")), "campaigns", used));
            }
        }
        out.sort(Comparator.comparing((Map<String, Object> m) -> -((Number) m.get("campaigns")).longValue()).thenComparing(m -> str(m.get("name")).toLowerCase(Locale.ROOT)));
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> guildEntry(Account a, String guildId) {
        Object[] cached = guildCache.get(a.id);
        if (cached == null) { guilds(a.id, true); cached = guildCache.get(a.id); }
        for (Map<String, Object> g : (List<Map<String, Object>>) cached[1]) if (str(g.get("id")).equals(guildId)) return g;
        guilds(a.id, true);
        for (Map<String, Object> g : (List<Map<String, Object>>) guildCache.get(a.id)[1]) if (str(g.get("id")).equals(guildId)) return g;
        throw new IllegalArgumentException("Du bist auf diesem Discord-Server nicht (mehr) Mitglied.");
    }

    Map<String, Object> channels(String accountId, String guildId) {
        if (!guildId.matches("\\d{5,25}")) throw new IllegalArgumentException("Ungültige Server-ID");
        Account a = usableAccount(accountId);
        Map<String, Object> guild = guildEntry(a, guildId);
        Resp r = call(a, "GET", "/guilds/" + guildId + "/channels");
        if (r.status == 403) throw new IllegalArgumentException("Discord gibt die Kanalliste dieses Servers nicht frei.");
        if (r.status != 200 || !(r.json instanceof List<?> list)) throw new IllegalArgumentException("Kanalliste nicht verfügbar (" + r.status + ")");
        Set<String> roles = new HashSet<>(); boolean certain = true;
        Resp member = call(a, "GET", "/users/@me/guilds/" + guildId + "/member");
        if (member.status == 200) for (Object o : list(member.obj().get("roles"))) roles.add(str(o)); else certain = false;
        long base = bits(guild.get("permissions")); boolean owner = bool(guild.get("owner"), false);
        Map<String, Map<String, Object>> categories = new HashMap<>();
        for (Object o : list) { Map<String, Object> ch = map(o); if (lng(ch.get("type")) == 4) categories.put(str(ch.get("id")), ch); }
        List<Map<String, Object>> out = new ArrayList<>();
        synchronized (this) {
            for (Object o : list) {
                Map<String, Object> ch = map(o); long type = lng(ch.get("type"));
                if (type != 0 && type != 5 && type != 15) continue;
                String id = str(ch.get("id")), name = str(ch.get("name")), topic = clip(str(ch.get("topic")).equals("null") ? "" : str(ch.get("topic")), 400);
                long perms = channelPermissions(base, owner, guildId, a.userId, roles, list(ch.get("permission_overwrites")));
                boolean view = (perms & VIEW_CHANNEL) != 0, send = view && (perms & SEND_MESSAGES) != 0;
                Map<String, Object> parent = categories.get(str(ch.get("parent_id")));
                int slowmode = (int) lng(ch.get("rate_limit_per_user"));
                Long suggested = suggestMinutes(name, topic);
                if (suggested != null && slowmode > 0) suggested = Math.max(suggested, (slowmode + 59) / 60L);
                Campaign existing = campaigns.values().stream().filter(c -> c.channelId.equals(id)).findFirst().orElse(null);
                out.add(SimplexAdminSuite.appMap("id", id, "name", name, "type", type == 5 ? "announcement" : type == 15 ? "forum" : "text", "supported", type != 15,
                    "category", parent == null ? "" : str(parent.get("name")), "categoryPosition", parent == null ? -1L : lng(parent.get("position")), "position", lng(ch.get("position")),
                    "topic", topic, "slowmode", slowmode, "nsfw", bool(ch.get("nsfw"), false), "canView", view, "canSend", send && type != 15, "canReadHistory", view && (perms & READ_HISTORY) != 0,
                    "canEmbed", (perms & EMBED_LINKS) != 0, "hint", channelHint(name), "suggestedMinutes", suggested, "campaign", existing == null ? null : existing.id));
            }
            out.sort(Comparator.comparingLong((Map<String, Object> m) -> lng(m.get("categoryPosition"))).thenComparingLong(m -> lng(m.get("position"))));
            long used = campaigns.values().stream().filter(c -> c.guildId.equals(guildId) && c.accountId.equals(a.id)).count();
            return SimplexAdminSuite.appMap("guild", SimplexAdminSuite.appMap("id", guildId, "name", str(guild.get("name")), "icon", iconUrl(guildId, str(guild.get("icon")))),
                "channels", out, "limit", settings.maxPerGuild, "used", used, "permissionsCertain", certain);
        }
    }

    private Account usableAccount(String accountId) {
        Account a; synchronized (this) { a = accounts.get(accountId == null ? "" : accountId); }
        if (a == null) throw new IllegalArgumentException("Bitte zuerst einen Discord-Account verknüpfen.");
        if (a.token.isEmpty() || a.status.equals("invalid") || a.status.equals("locked")) throw new IllegalArgumentException("„" + a.name() + "“ muss neu verknüpft werden: " + a.statusText);
        return a;
    }

    // ================================================================== templates

    synchronized Map<String, Object> saveTemplate(Map<String, Object> body, String actor) {
        String id = str(body.get("id")), name = clip(str(body.get("name")).trim(), 60), content = str(body.get("content")).replace("\r\n", "\n");
        if (name.isBlank()) throw new IllegalArgumentException("Bitte einen Namen vergeben");
        if (content.isBlank()) throw new IllegalArgumentException("Der Werbetext ist leer");
        if (content.length() > MAX_CONTENT) throw new IllegalArgumentException("Höchstens " + MAX_CONTENT + " Zeichen");
        Template t;
        if (id.isBlank()) { if (templates.size() >= MAX_TEMPLATES) throw new IllegalArgumentException("Höchstens " + MAX_TEMPLATES + " Werbetexte"); t = new Template(); t.id = newId(); t.createdAt = clock.getAsLong(); templates.put(t.id, t); }
        else { t = templates.get(id); if (t == null) throw new IllegalArgumentException("Werbetext nicht gefunden"); }
        t.name = name; t.content = content; t.suppressEmbeds = bool(body.get("suppressEmbeds"), false); t.updatedAt = clock.getAsLong(); t.updatedBy = actor; t.preset = false;
        save();
        hooks.audit(actor, "Discord-Werbung: Werbetext gespeichert", name + " • " + content.length() + " Zeichen");
        return SimplexAdminSuite.appMap("template", templateView(t), "notice", "„" + name + "“ gespeichert.");
    }

    synchronized Map<String, Object> deleteTemplate(String id, String actor) {
        Template t = templates.get(id);
        if (t == null) throw new IllegalArgumentException("Werbetext nicht gefunden");
        List<String> used = campaigns.values().stream().filter(c -> c.templateId.equals(id)).map(c -> "#" + c.channelName).toList();
        if (!used.isEmpty()) throw new IllegalArgumentException("Wird noch verwendet in " + String.join(", ", used) + ". Dort zuerst einen anderen Werbetext wählen.");
        templates.remove(id); save();
        hooks.audit(actor, "Discord-Werbung: Werbetext gelöscht", t.name);
        return SimplexAdminSuite.appMap("notice", "„" + t.name + "“ gelöscht.");
    }

    // ================================================================== campaigns

    Map<String, Object> saveCampaign(Map<String, Object> body, String actor) {
        String id = str(body.get("id"));
        String accountId = str(body.get("account")), guildId = str(body.get("guild")), channelId = str(body.get("channel")), templateId = str(body.get("template"));
        long interval;
        try { interval = Long.parseLong(str(body.get("intervalMinutes")).replaceAll("\\.0$", "")); } catch (NumberFormatException e) { throw new IllegalArgumentException("Intervall in Minuten angeben"); }
        if (interval < MIN_INTERVAL_MINUTES || interval > MAX_INTERVAL_MINUTES) throw new IllegalArgumentException("Intervall: 5 Minuten bis 7 Tage");
        Campaign current; synchronized (this) { current = id.isBlank() ? null : campaigns.get(id); }
        if (!id.isBlank() && current == null) throw new IllegalArgumentException("Kanal-Planung nicht gefunden");
        if (current != null) { if (accountId.isBlank()) accountId = current.accountId; if (guildId.isBlank()) guildId = current.guildId; if (channelId.isBlank()) channelId = current.channelId; }
        boolean channelChanged = current == null || !current.channelId.equals(channelId) || !current.accountId.equals(accountId);
        if (channelChanged && !Boolean.TRUE.equals(body.get("confirm"))) throw new IllegalArgumentException("Bitte bestätige, dass Werbung in diesem Kanal erlaubt ist – sonst kannst du vom Server ausgeschlossen werden.");
        synchronized (this) { if (!templates.containsKey(templateId)) throw new IllegalArgumentException("Bitte einen Werbetext wählen"); }
        Map<String, Object> channel = null, guild = null;
        if (channelChanged) {
            Map<String, Object> data = channels(accountId, guildId);
            guild = map(data.get("guild"));
            for (Object o : list(data.get("channels"))) if (str(map(o).get("id")).equals(channelId)) channel = map(o);
            if (channel == null) throw new IllegalArgumentException("Kanal nicht gefunden oder nicht sichtbar");
            if (!bool(channel.get("supported"), false)) throw new IllegalArgumentException("Forum-Kanäle werden nicht unterstützt");
            if (!bool(channel.get("canSend"), false)) throw new IllegalArgumentException("Du darfst in #" + str(channel.get("name")) + " nicht schreiben.");
        }
        long now = clock.getAsLong();
        synchronized (this) {
            String acc = accountId, gid = guildId, cid = channelId;
            Account a = accounts.get(acc);
            if (a == null) throw new IllegalArgumentException("Account nicht gefunden");
            if (campaigns.values().stream().anyMatch(c -> c.channelId.equals(cid) && (current == null || !c.id.equals(current.id)))) throw new IllegalArgumentException("Für diesen Kanal gibt es schon eine Planung – bearbeite sie dort.");
            long inGuild = campaigns.values().stream().filter(c -> c.guildId.equals(gid) && c.accountId.equals(acc) && (current == null || !c.id.equals(current.id))).count();
            if (inGuild >= settings.maxPerGuild) throw new IllegalArgumentException("Höchstens " + settings.maxPerGuild + " Kanäle pro Discord-Server (änderbar in den Einstellungen).");
            if (current == null && campaigns.size() >= MAX_CAMPAIGNS) throw new IllegalArgumentException("Höchstens " + MAX_CAMPAIGNS + " Kanäle insgesamt");
            Campaign c = current == null ? new Campaign() : current;
            if (current == null) { c.id = newId(); c.createdAt = now; c.createdBy = actor; campaigns.put(c.id, c); }
            if (channelChanged) {
                c.accountId = acc; c.accountUserId = a.userId; c.guildId = gid; c.guildName = clip(str(guild.get("name")), 100); c.guildIcon = str(guild.get("icon"));
                c.channelId = cid; c.channelName = clip(str(channel.get("name")), 100); c.category = clip(str(channel.get("category")), 100); c.slowmodeSeconds = (int) lng(channel.get("slowmode"));
                c.confirmedAt = now; c.confirmedBy = actor; c.lastMessageId = ""; c.verifyAt = 0; c.verifyMessageId = "";
            }
            boolean intervalChanged = c.intervalMinutes != interval;
            c.templateId = templateId; c.intervalMinutes = interval; c.notify = bool(body.get("notify"), true);
            c.pauseAfter = clampInt(body.get("pauseAfter"), settings.pauseAfter, 0, 10);
            boolean enable = bool(body.get("enabled"), true);
            if (enable && (!c.enabled || current == null)) { c.consecutiveFailures = 0; c.transientFailures = 0; c.slowmodeStreak = 0; c.pausedReason = ""; }
            c.enabled = enable;
            if (!enable && c.pausedReason.isBlank()) c.pausedReason = "Von dir pausiert";
            String first = str(body.getOrDefault("firstPost", "soon"));
            if (current == null || channelChanged) c.nextRunAt = first.equals("interval") ? now + delayMillis(interval, c.slowmodeSeconds, random) : now + 30_000L + random.nextInt(90_001);
            else if (intervalChanged && c.lastSentAt > 0) c.nextRunAt = Math.max(now + 30_000L, c.lastSentAt + delayMillis(interval, c.slowmodeSeconds, random));
            c.updatedAt = now;
            history(c, current == null ? "created" : "edited", current == null ? "Geplant: alle " + human(interval) + " mit „" + templates.get(templateId).name + "“" : "Geändert: alle " + human(interval) + " mit „" + templates.get(templateId).name + "“", null);
            addLog(c, current == null ? "created" : "edited", "#" + c.channelName + " auf " + c.guildName + ": alle " + human(interval));
            save();
            hooks.audit(actor, current == null ? "Discord-Werbung: Kanal geplant" : "Discord-Werbung: Planung geändert", c.guildName + " #" + c.channelName + " • alle " + human(interval) + (channelChanged ? " • Kanal bestätigt" : ""));
            return SimplexAdminSuite.appMap("campaign", campaignView(c, now), "notice", c.enabled ? "Gespeichert – nächster Post " + clock(c.nextRunAt) + "." : "Gespeichert (pausiert).");
        }
    }

    synchronized Map<String, Object> campaignAction(String id, String op, String actor) {
        Campaign c = campaigns.get(id);
        if (c == null) throw new IllegalArgumentException("Kanal-Planung nicht gefunden");
        long now = clock.getAsLong();
        String notice;
        switch (op) {
            case "pause" -> { c.enabled = false; c.pausedReason = "Von dir pausiert"; history(c, "paused", "Pausiert von " + actor, null); notice = "#" + c.channelName + " pausiert."; }
            case "resume" -> {
                if (!accounts.containsKey(c.accountId)) throw new IllegalArgumentException("Der zugehörige Account ist abgemeldet – bitte zuerst wieder verknüpfen.");
                if (!templates.containsKey(c.templateId)) throw new IllegalArgumentException("Bitte zuerst einen Werbetext wählen.");
                c.enabled = true; c.pausedReason = ""; c.consecutiveFailures = 0; c.transientFailures = 0; c.slowmodeStreak = 0;
                long earliest = c.lastSentAt > 0 ? c.lastSentAt + delayMillis(c.intervalMinutes, c.slowmodeSeconds, random) : now + 30_000L;
                c.nextRunAt = Math.max(now + 30_000L + random.nextInt(60_001), earliest);
                history(c, "resumed", "Fortgesetzt – nächster Post " + clock(c.nextRunAt), null); notice = "#" + c.channelName + " läuft wieder – nächster Post " + clock(c.nextRunAt) + ".";
            }
            case "send-now" -> {
                if (!c.enabled) throw new IllegalArgumentException("Erst fortsetzen, dann senden.");
                long earliest = c.lastSentAt > 0 ? c.lastSentAt + c.effectiveMillis() : 0;
                if (earliest > now) throw new IllegalArgumentException("Frühestens " + clock(earliest) + " – sonst unterschreitest du das Intervall bzw. den Slowmode des Kanals.");
                c.nextRunAt = now; notice = "Wird in wenigen Sekunden gesendet.";
                history(c, "info", "Sofort senden angefordert von " + actor, null);
            }
            case "delete" -> { campaigns.remove(id); addLog(c, "deleted", "#" + c.channelName + " auf " + c.guildName + " gelöscht"); notice = "Planung für #" + c.channelName + " gelöscht."; }
            default -> throw new IllegalArgumentException("Aktion: pause, resume, send-now oder delete");
        }
        save();
        String verb = switch (op) { case "pause" -> "pausiert"; case "resume" -> "fortgesetzt"; case "send-now" -> "sofort senden"; default -> "gelöscht"; };
        hooks.audit(actor, "Discord-Werbung: " + verb, c.guildName + " #" + c.channelName);
        Map<String, Object> out = SimplexAdminSuite.appMap("notice", notice);
        if (!op.equals("delete")) out.put("campaign", campaignView(c, now));
        return out;
    }

    synchronized Map<String, Object> saveSettings(Map<String, Object> body, String actor) {
        settings.notify = bool(body.get("notify"), settings.notify); settings.verifyDelete = bool(body.get("verifyDelete"), settings.verifyDelete);
        settings.checkOwnPosts = bool(body.get("checkOwnPosts"), settings.checkOwnPosts);
        settings.pauseAfter = clampInt(body.get("pauseAfter"), settings.pauseAfter, 0, 10); settings.gapSeconds = clampInt(body.get("gapSeconds"), settings.gapSeconds, 20, 900);
        settings.maxPerGuild = clampInt(body.get("maxPerGuild"), settings.maxPerGuild, 1, 10);
        save();
        hooks.audit(actor, "Discord-Werbung: Einstellungen", "Bark " + (settings.notify ? "an" : "aus") + " • Pause nach " + settings.pauseAfter + " • max. " + settings.maxPerGuild + " Kanäle/Server");
        return SimplexAdminSuite.appMap("settings", settingsView(), "notice", "Einstellungen gespeichert.");
    }

    // ================================================================== scheduler

    /** One scheduler pass: deletion checks, at most one post per account, periodic account checks. */
    void tick() {
        long now = clock.getAsLong();
        List<Campaign> verify = new ArrayList<>(), due = new ArrayList<>();
        synchronized (this) {
            for (Campaign c : campaigns.values()) {
                if (c.verifyAt > 0 && c.verifyAt <= now && !c.verifyMessageId.isBlank()) verify.add(c);
                if (c.enabled && c.nextRunAt <= now) due.add(c);
            }
        }
        for (Campaign c : verify) verify(c);
        due.sort(Comparator.comparingLong(c -> c.nextRunAt));
        Set<String> busy = new HashSet<>();
        for (Campaign c : due) {
            Account a; synchronized (this) { a = accounts.get(c.accountId); }
            if (a == null) { synchronized (this) { if (c.enabled) pause(c, "Account abgemeldet – zum Fortsetzen denselben Account wieder verknüpfen.", false); save(); } continue; }
            if (!a.usable(now) || a.nextAllowedAt > now || !busy.add(a.id)) continue;
            send(c, a);
        }
        if (now - lastHealthCheck > 6 * 3_600_000L) {
            lastHealthCheck = now;
            List<Account> all; synchronized (this) { all = new ArrayList<>(accounts.values()); }
            for (Account a : all) if (!a.token.isEmpty() && !a.status.equals("invalid") && !a.status.equals("locked")) {
                try { Resp r = request(a.token, "GET", "/users/@me", null); synchronized (this) { a.checkedAt = now; if (r.status == 401) markAccount(a, "invalid", "Discord hat den Token abgelehnt (abgemeldet oder Passwort geändert). Bitte neu verknüpfen."); else if (r.status == 200 && !a.status.equals("ok") && a.blockedUntil <= now && a.status.equals("error")) markAccount(a, "ok", ""); save(); } }
                catch (IOException ignored) { }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
        }
    }

    private void verify(Campaign c) {
        Account a; String messageId;
        synchronized (this) { a = accounts.get(c.accountId); messageId = c.verifyMessageId; c.verifyAt = 0; }
        if (a == null || !a.usable(clock.getAsLong())) { synchronized (this) { c.verifyMessageId = ""; c.consecutiveFailures = 0; save(); } return; }
        Resp r;
        try { r = request(a.token, "GET", "/channels/" + c.channelId + "/messages?around=" + messageId + "&limit=1", null); }
        catch (IOException e) { synchronized (this) { c.verifyMessageId = ""; c.consecutiveFailures = 0; save(); } return; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        synchronized (this) {
            c.verifyMessageId = "";
            if (r.status == 200 && r.json instanceof List<?> list) {
                boolean present = list.stream().anyMatch(o -> str(map(o).get("id")).equals(messageId));
                if (!present && campaigns.containsKey(c.id)) {
                    c.deletedCount++;
                    // A timer bot usually counts from the last accepted post: wait a quarter of the interval (15 min – 6 h) before trying again.
                    long retry = Math.max(15 * 60_000L, Math.min(6 * 3_600_000L, c.effectiveMillis() / 4));
                    failure(c, "deleted", "Der Post wurde kurz nach dem Senden entfernt – vermutlich von einem Moderations- oder Timer-Bot (zu früh, falscher Kanal oder nicht erlaubt). Bitte Kanalregeln und Intervall prüfen.", retry);
                } else c.consecutiveFailures = 0; // the post stayed: only now the failure streak ends
            } else c.consecutiveFailures = 0; // not verifiable (no history access): count the successful send
            save();
        }
    }

    private static String nonce(long now) { return Long.toString((now - DISCORD_EPOCH) << 22 | (now & 0x3FFFFF)); }

    private void send(Campaign c, Account a) {
        long now = clock.getAsLong();
        String content, channel = c.channelId; boolean suppress;
        synchronized (this) {
            Template t = templates.get(c.templateId);
            if (t == null) { pause(c, "Der gewählte Werbetext wurde gelöscht – bitte einen anderen wählen.", true); save(); return; }
            if (!c.enabled || !campaigns.containsKey(c.id)) return;
            content = render(t.content); suppress = t.suppressEmbeds;
            c.lastAttemptAt = now;
        }
        try {
            if (settings.checkOwnPosts) {
                Resp recent = request(a.token, "GET", "/channels/" + channel + "/messages?limit=50", null);
                if (recent.status == 401) { synchronized (this) { markAccount(a, "invalid", "Discord hat den Token abgelehnt (abgemeldet oder Passwort geändert). Bitte neu verknüpfen."); save(); } return; }
                if (recent.status == 404) { synchronized (this) { failure(c, "gone", "Kanal existiert nicht mehr oder du bist nicht mehr auf dem Server.", 0); pause(c, "Kanal nicht mehr erreichbar", false); save(); } return; }
                if (recent.status == 200 && recent.json instanceof List<?> list) {
                    long latest = 0;
                    for (Object o : list) { Map<String, Object> m = map(o); if (str(map(m.get("author")).get("id")).equals(a.userId)) latest = Math.max(latest, timestamp(str(m.get("timestamp")))); }
                    synchronized (this) {
                        if (latest > 0 && now - latest < c.effectiveMillis()) {
                            c.nextRunAt = latest + delayMillis(c.intervalMinutes, c.slowmodeSeconds, random);
                            history(c, "postponed", "Dein letzter Post hier ist erst " + human(Math.max(1, (now - latest) / 60_000L)) + " alt – nächster Post " + clock(c.nextRunAt) + ".", null);
                            if (c.lastSentAt < latest) c.lastSentAt = latest;
                            save(); return;
                        }
                    }
                }
            }
            Map<String, Object> body = SimplexAdminSuite.appMap("content", content, "nonce", nonce(now), "enforce_nonce", true, "tts", false, "flags", suppress ? 4 : 0, "allowed_mentions", SimplexAdminSuite.appMap("parse", List.of()));
            Resp r;
            try { r = request(a.token, "POST", "/channels/" + channel + "/messages", body); }
            catch (IOException first) { r = request(a.token, "POST", "/channels/" + channel + "/messages", body); } // same nonce: Discord never posts twice
            handle(c, a, r, now);
        } catch (IOException e) {
            synchronized (this) { transientFailure(c, "Discord nicht erreichbar (" + e.getClass().getSimpleName() + ")"); save(); }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private void handle(Campaign c, Account a, Resp r, long now) {
        int code = r.code();
        String discord = r.message().isBlank() ? "" : " (Discord: " + clip(r.message(), 160) + ")";
        synchronized (this) {
            if (!campaigns.containsKey(c.id)) return;
            if (r.status / 100 == 2) {
                String messageId = str(r.obj().get("id"));
                c.lastSentAt = now; c.lastMessageId = messageId; c.lastResult = "sent"; c.lastError = ""; c.sentCount++;
                c.transientFailures = 0; c.slowmodeStreak = 0;
                c.nextRunAt = now + delayMillis(c.intervalMinutes, c.slowmodeSeconds, random);
                // The failure streak only ends once the post survived the deletion check; otherwise a timer bot that
                // removes every advertisement would reset the streak on each send and the channel would never pause.
                if (settings.verifyDelete && !messageId.isBlank()) { c.verifyAt = now + 180_000L; c.verifyMessageId = messageId; }
                else c.consecutiveFailures = 0;
                a.lastPostAt = now; a.sent++; a.nextAllowedAt = now + settings.gapSeconds * 1000L + random.nextInt(settings.gapSeconds * 500 + 1);
                history(c, "sent", "Gesendet – nächster Post " + clock(c.nextRunAt) + ".", messageId);
                addLog(c, "sent", "#" + c.channelName + " auf " + c.guildName + " gesendet");
            } else if (r.status == 429 || code == 20016) {
                double retry = Math.max(1, r.retryAfter());
                if (code == 20016 || !bool(r.obj().get("global"), false) && retry > 30) {
                    c.slowmodeCorrections++; c.slowmodeStreak++;
                    int slowmode = fetchSlowmode(a, c.channelId);
                    if (slowmode > 0) c.slowmodeSeconds = slowmode;
                    c.nextRunAt = now + Math.round(retry * 1000) + 20_000L + random.nextInt(70_001);
                    c.lastResult = "slowmode";
                    String longer = c.slowmodeSeconds > c.intervalMinutes * 60 ? " Der Kanal-Slowmode (" + human((c.slowmodeSeconds + 59) / 60L) + ") ist länger als dein Intervall – er wird ab jetzt automatisch eingehalten." : "";
                    history(c, "slowmode", "Slowmode aktiv: noch " + human(Math.max(1, Math.round(retry / 60))) + " – neuer Versuch " + clock(c.nextRunAt) + "." + longer, null);
                    if (c.slowmodeStreak >= 4) failure(c, "slowmode", "Der Kanal lehnt Posts wegen Slowmode immer wieder ab – vermutlich gilt ein anderer Timer als eingestellt." + discord, c.nextRunAt - now);
                } else {
                    c.nextRunAt = now + Math.round(retry * 1000) + 5_000L + random.nextInt(15_001);
                    if (bool(r.obj().get("global"), false)) a.blockedUntil = c.nextRunAt;
                    c.lastResult = "ratelimit";
                    history(c, "ratelimit", "Discord bremst kurz – neuer Versuch " + clock(c.nextRunAt) + ".", null);
                }
            } else if (r.status == 401) {
                markAccount(a, "invalid", "Discord hat den Token abgelehnt (abgemeldet oder Passwort geändert). Bitte neu verknüpfen.");
                c.nextRunAt = now + 600_000L; c.lastResult = "account"; c.lastError = a.statusText;
            } else if (r.obj().containsKey("captcha_key") || r.obj().containsKey("captcha_sitekey")) {
                markAccount(a, "captcha", "Discord verlangt ein Captcha. Melde dich mit dem Account im Discord-Client an, schreib einmal selbst etwas und tippe dann „Erneut prüfen“.");
                c.nextRunAt = now + 1_800_000L; c.lastResult = "account"; c.lastError = a.statusText;
            } else if (code == 40002) {
                markAccount(a, "verify", "Discord verlangt eine Bestätigung des Accounts (E-Mail/Telefon). Danach „Erneut prüfen“.");
                c.nextRunAt = now + 1_800_000L; c.lastResult = "account"; c.lastError = a.statusText;
            } else if (r.status == 404 || code == 10003 || code == 10004) {
                failure(c, "gone", "Kanal oder Server nicht mehr erreichbar – vielleicht wurdest du entfernt oder der Kanal gelöscht." + discord, 0);
                pause(c, "Kanal nicht mehr erreichbar", false);
            } else if (code == 50013 || code == 50001 || r.status == 403) {
                failure(c, "permission", (code == 50001 ? "Kein Zugriff auf den Kanal." : "Keine Berechtigung, hier zu schreiben (Kanal gesperrt, Rolle fehlt oder Verifizierung nötig).") + discord, -1);
            } else if (code == 50035 || code == 50006 || r.status == 400) {
                failure(c, "invalid", "Discord lehnt den Werbetext ab – zu lang (über " + DISCORD_LIMIT + " Zeichen ohne Nitro) oder ungültig." + discord, 0);
                pause(c, "Werbetext abgelehnt – bitte kürzen oder prüfen", false);
            } else if (r.status >= 500) {
                transientFailure(c, "Discord-Störung (" + r.status + ")");
            } else failure(c, "failed", "Unerwartete Antwort " + r.status + discord, -1);
            save();
        }
    }

    private int fetchSlowmode(Account a, String channelId) {
        try { Resp r = request(a.token, "GET", "/channels/" + channelId, null); return r.status == 200 ? (int) lng(r.obj().get("rate_limit_per_user")) : -1; }
        catch (IOException e) { return -1; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return -1; }
    }

    /** Caller holds the lock. retryIn: -1 = backoff, 0 = keep schedule, >0 = explicit. */
    private void failure(Campaign c, String kind, String text, long retryIn) {
        long now = clock.getAsLong();
        c.consecutiveFailures++; c.failedCount++; c.lastResult = kind; c.lastError = text;
        if (retryIn < 0) {
            long backoff = Math.min(6 * 3_600_000L, 15 * 60_000L * (1L << Math.min(4, c.consecutiveFailures - 1)));
            c.nextRunAt = now + Math.max(10 * 60_000L, Math.min(backoff, c.effectiveMillis())) + random.nextInt(60_001);
        } else if (retryIn > 0) c.nextRunAt = now + retryIn;
        history(c, kind, text, null);
        addLog(c, "failed", "#" + c.channelName + " auf " + c.guildName + ": " + text);
        if (c.pauseAfter > 0 && c.consecutiveFailures >= c.pauseAfter && c.enabled) pause(c, "Automatisch pausiert nach " + c.consecutiveFailures + " Fehlern in Folge: " + text, true);
        else if (c.enabled && c.notify && settings.notify && !kind.equals("gone") && !kind.equals("invalid"))
            hooks.notify("Discord-Werbung fehlgeschlagen", c.guildName + " #" + c.channelName + ": " + text + (c.enabled ? " Nächster Versuch " + clock(c.nextRunAt) + "." : ""), false);
    }

    private void transientFailure(Campaign c, String text) {
        c.transientFailures++;
        long[] steps = {60_000L, 180_000L, 600_000L, 1_800_000L};
        c.nextRunAt = clock.getAsLong() + steps[Math.min(steps.length - 1, c.transientFailures - 1)];
        c.lastResult = "retry"; c.lastError = text;
        history(c, "retry", text + " – neuer Versuch " + clock(c.nextRunAt) + ".", null);
        if (c.transientFailures >= 4) { c.transientFailures = 0; failure(c, "failed", text + " (mehrfach)", 0); }
    }

    /** Caller holds the lock. */
    private void pause(Campaign c, String reason, boolean notifyAlways) {
        boolean was = c.enabled;
        c.enabled = false; c.pausedReason = reason;
        history(c, "paused", reason, null);
        if (was && (notifyAlways || c.notify) && settings.notify) hooks.notify("Discord-Werbung pausiert", c.guildName + " #" + c.channelName + ": " + reason, true);
    }

    private void history(Campaign c, String kind, String text, String messageId) {
        Map<String, Object> row = SimplexAdminSuite.appMap("time", clock.getAsLong(), "kind", kind, "text", clip(text, 500));
        if (messageId != null && !messageId.isBlank()) row.put("message", messageId);
        c.history.add(0, row);
        while (c.history.size() > HISTORY) c.history.remove(c.history.size() - 1);
    }

    private void addLog(Campaign c, String kind, String text) {
        Map<String, Object> row = SimplexAdminSuite.appMap("time", clock.getAsLong(), "kind", kind, "text", clip(text, 500));
        if (c != null) { row.put("campaign", c.id); row.put("guild", c.guildName); row.put("channel", c.channelName); }
        log.addFirst(row);
        while (log.size() > LOG) log.removeLast();
    }

    // ================================================================== views

    synchronized Map<String, Object> overview() {
        long now = clock.getAsLong();
        List<Object> acc = new ArrayList<>(); for (Account a : accounts.values()) acc.add(accountView(a));
        List<Object> tpl = new ArrayList<>(); for (Template t : templates.values()) tpl.add(templateView(t));
        List<Campaign> sorted = new ArrayList<>(campaigns.values());
        sorted.sort(Comparator.comparing((Campaign c) -> c.guildName.toLowerCase(Locale.ROOT)).thenComparing(c -> c.channelName));
        List<Object> camp = new ArrayList<>(); for (Campaign c : sorted) camp.add(campaignView(c, now));
        long day = now - 86_400_000L;
        long sent = log.stream().filter(r -> "sent".equals(r.get("kind")) && lng(r.get("time")) >= day).count(), failed = log.stream().filter(r -> "failed".equals(r.get("kind")) && lng(r.get("time")) >= day).count();
        long next = campaigns.values().stream().filter(c -> c.enabled).mapToLong(c -> c.nextRunAt).min().orElse(0);
        return SimplexAdminSuite.appMap("accounts", acc, "templates", tpl, "campaigns", camp, "settings", settingsView(), "log", new ArrayList<>(log).subList(0, Math.min(60, log.size())),
            "stats", SimplexAdminSuite.appMap("sent24h", sent, "failed24h", failed, "active", campaigns.values().stream().filter(c -> c.enabled).count(), "paused", campaigns.values().stream().filter(c -> !c.enabled).count(), "nextRunAt", next),
            "limits", SimplexAdminSuite.appMap("accounts", MAX_ACCOUNTS, "templates", MAX_TEMPLATES, "campaigns", MAX_CAMPAIGNS, "content", MAX_CONTENT, "discord", DISCORD_LIMIT, "minInterval", MIN_INTERVAL_MINUTES, "maxInterval", MAX_INTERVAL_MINUTES),
            "placeholders", List.of(List.of("{online}", "Spieler online"), List.of("{max}", "Max. Spieler"), List.of("{date}", "Heutiges Datum")),
            "now", now, "problem", lastTickProblem);
    }

    private Map<String, Object> accountView(Account a) {
        long campaignsCount = campaigns.values().stream().filter(c -> c.accountId.equals(a.id)).count();
        return SimplexAdminSuite.appMap("id", a.id, "userId", a.userId, "name", a.name(), "username", a.username, "avatar", avatarUrl(a.userId, a.avatar), "status", a.status, "statusText", a.statusText,
            "linkedAt", a.linkedAt, "linkedBy", a.linkedBy, "checkedAt", a.checkedAt, "lastPostAt", a.lastPostAt, "sent", a.sent, "campaigns", campaignsCount,
            "tokenHint", a.token.length() > 6 ? "••••" + a.token.substring(a.token.length() - 4) : "–");
    }

    private Map<String, Object> templateView(Template t) {
        List<String> used = campaigns.values().stream().filter(c -> c.templateId.equals(t.id)).map(c -> c.channelName).toList();
        return SimplexAdminSuite.appMap("id", t.id, "name", t.name, "content", t.content, "length", t.content.length(), "suppressEmbeds", t.suppressEmbeds, "preset", t.preset,
            "createdAt", t.createdAt, "updatedAt", t.updatedAt, "updatedBy", t.updatedBy, "usedBy", used);
    }

    private Map<String, Object> campaignView(Campaign c, long now) {
        Account a = accounts.get(c.accountId); Template t = templates.get(c.templateId);
        long[] w = window(c.intervalMinutes, c.slowmodeSeconds);
        String state = !c.enabled ? "paused" : a == null ? "account" : !a.status.equals("ok") ? "account" : "active";
        return SimplexAdminSuite.appMap("id", c.id, "account", c.accountId, "accountName", a == null ? "(abgemeldet)" : a.name(), "accountStatus", a == null ? "missing" : a.status,
            "guild", c.guildId, "guildName", c.guildName, "guildIcon", c.guildIcon, "channel", c.channelId, "channelName", c.channelName, "category", c.category,
            "template", c.templateId, "templateName", t == null ? "(gelöscht)" : t.name, "intervalMinutes", c.intervalMinutes, "slowmodeSeconds", c.slowmodeSeconds,
            "effectiveMinutes", (c.effectiveMillis() + 59_999L) / 60_000L, "windowFromMinutes", w[0] / 60_000.0, "windowToMinutes", w[1] / 60_000.0,
            "enabled", c.enabled, "state", state, "pausedReason", c.pausedReason, "notify", c.notify, "pauseAfter", c.pauseAfter, "nextRunAt", c.enabled ? c.nextRunAt : 0,
            "lastSentAt", c.lastSentAt, "lastResult", c.lastResult, "lastError", c.lastError, "lastMessageId", c.lastMessageId, "consecutiveFailures", c.consecutiveFailures,
            "sentCount", c.sentCount, "failedCount", c.failedCount, "deletedCount", c.deletedCount, "slowmodeCorrections", c.slowmodeCorrections,
            "earliestManual", c.lastSentAt > 0 ? c.lastSentAt + c.effectiveMillis() : 0, "createdAt", c.createdAt, "createdBy", c.createdBy, "confirmedAt", c.confirmedAt, "confirmedBy", c.confirmedBy,
            "link", "https://discord.com/channels/" + c.guildId + "/" + c.channelId + (c.lastMessageId.isBlank() ? "" : "/" + c.lastMessageId), "history", new ArrayList<>(c.history));
    }

    private Map<String, Object> settingsView() {
        return SimplexAdminSuite.appMap("notify", settings.notify, "verifyDelete", settings.verifyDelete, "checkOwnPosts", settings.checkOwnPosts, "pauseAfter", settings.pauseAfter, "gapSeconds", settings.gapSeconds, "maxPerGuild", settings.maxPerGuild);
    }

    /** Short status for diagnostics and the hub. */
    synchronized Map<String, Object> summary() {
        return SimplexAdminSuite.appMap("accounts", accounts.size(), "active", campaigns.values().stream().filter(c -> c.enabled).count(), "campaigns", campaigns.size());
    }

    // ================================================================== small helpers

    private static String newId() { byte[] b = new byte[6]; new SecureRandom().nextBytes(b); StringBuilder s = new StringBuilder(); for (byte x : b) s.append(String.format("%02x", x)); return s.toString(); }
    static String clock(long millis) { return millis <= 0 ? "–" : CLOCK.format(Instant.ofEpochMilli(millis)); }
    static String human(long minutes) {
        if (minutes % 1440 == 0) return minutes / 1440 + (minutes == 1440 ? " Tag" : " Tage");
        if (minutes >= 60 && minutes % 60 == 0) return minutes / 60 + " Std";
        if (minutes > 60) return String.format(Locale.GERMANY, "%.1f Std", minutes / 60.0);
        return minutes + " Min";
    }
    private static long timestamp(String iso) { try { return java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli(); } catch (RuntimeException e) { return 0; } }
    private static String clip(String v, int n) { return v == null ? "" : v.length() <= n ? v : v.substring(0, n); }
    @SuppressWarnings("unchecked") static Map<String, Object> map(Object o) { return o instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>(); }
    static List<?> list(Object o) { return o instanceof List<?> l ? l : List.of(); }
    static long lng(Object o) { if (o instanceof Number n) return n.longValue(); try { return o == null ? 0 : Long.parseLong(String.valueOf(o).trim()); } catch (NumberFormatException e) { return 0; } }
    static boolean bool(Object o, boolean fallback) { return o instanceof Boolean b ? b : o == null ? fallback : "true".equalsIgnoreCase(String.valueOf(o)); }
    private static int clampInt(Object o, int fallback, int min, int max) { if (o == null || String.valueOf(o).isBlank()) return fallback; long v = lng(o); return (int) Math.max(min, Math.min(max, v)); }
    static String query(String v) { return URLEncoder.encode(v, StandardCharsets.UTF_8); }
}
