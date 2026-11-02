package eu.simplexsmp.adminsuite;

import java.io.*;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * 9.7.0 staff chat: one conversation shared by the private Discord staff channel, the app and the
 * existing in-game staff messages. Files, images and voice messages are copied locally, so the app
 * can still show them after Discord CDN links expire. Discord posts use a channel webhook with the
 * sender's name when the bot may create one, otherwise a clean embed in the sender's name.
 */
final class StaffChat {
    static final int LIMIT = 400, MAX_FILE = 8 * 1024 * 1024, MAX_TEXT = 1800;
    private final SimplexAdminSuite plugin;
    private final Path folder, file, files, secrets;
    private final ArrayDeque<Map<String, Object>> rows = new ArrayDeque<>();
    private final Map<String, String> members = new LinkedHashMap<>();
    private final Properties hook = new Properties();
    private long sequence;
    volatile String lastError = "";

    StaffChat(SimplexAdminSuite plugin) {
        this.plugin = plugin;
        folder = plugin.getDataFolder().toPath().resolve("staff-chat");
        file = folder.resolve("messages.properties"); files = folder.resolve("files"); secrets = folder.resolve("webhook.properties");
        load();
    }

    private synchronized void load() {
        try {
            Files.createDirectories(files); AppApiSecurity.protect(folder);
            if (Files.exists(secrets)) try (Reader in = Files.newBufferedReader(secrets)) { hook.load(in); }
            if (!Files.exists(file)) return;
            Properties p = new Properties(); try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { p.load(in); }
            sequence = AdminActivityLog.number(p.getProperty("sequence", "0"));
            for (int i = 0; i < LIMIT; i++) {
                String raw = p.getProperty("row." + i); if (raw == null) break;
                if (DiscordBotManager.Json.parse(raw) instanceof Map<?, ?> m) { Map<String, Object> row = new LinkedHashMap<>(); m.forEach((k, v) -> row.put(String.valueOf(k), v)); rows.addLast(row); }
            }
            for (int i = 0; i < 200; i++) { String raw = p.getProperty("member." + i); if (raw == null) break; String[] a = raw.split("\\|", 2); if (a.length == 2) members.put(a[0], a[1]); }
        } catch (Exception e) { plugin.getLogger().warning("Staff-Chat konnte nicht geladen werden; Datei bleibt erhalten: " + e.getMessage()); }
    }

    private synchronized void persist() {
        Properties p = new Properties(); p.setProperty("schema", "1"); p.setProperty("sequence", Long.toString(sequence));
        int i = 0; for (Map<String, Object> row : rows) p.setProperty("row." + (i++), DiscordBotManager.Json.stringify(row));
        i = 0; for (var e : members.entrySet()) { if (i >= 200) break; p.setProperty("member." + (i++), e.getKey() + "|" + e.getValue()); }
        try { AtomicState.save(file, p, "Simplex staff chat (Discord staff channel, app and in-game staff messages)"); AppApiSecurity.protect(file); }
        catch (IOException e) { plugin.getLogger().warning("Staff-Chat konnte nicht gespeichert werden: " + e.getMessage()); }
    }

    String channel() { return plugin.getConfig().getString("staff-chat.channel-id", "").trim(); }
    boolean discordEnabled() { return channel().matches("[0-9]{15,22}"); }

    private synchronized Map<String, Object> add(String author, String authorId, String source, String text, List<Map<String, Object>> attachments, List<String> mentions) {
        Map<String, Object> row = SimplexAdminSuite.appMap("id", ++sequence, "at", System.currentTimeMillis(), "author", clip(author, 64), "authorId", authorId == null ? "" : authorId, "source", source, "text", clip(text, MAX_TEXT), "attachments", attachments == null ? List.of() : attachments, "mentions", mentions == null ? List.of() : mentions);
        rows.addLast(row);
        while (rows.size() > LIMIT) { Map<String, Object> removed = rows.removeFirst(); deleteFiles(removed); }
        persist();
        return row;
    }

    private void deleteFiles(Map<String, Object> row) {
        if (row.get("attachments") instanceof List<?> list) for (Object o : list) if (o instanceof Map<?, ?> a) try { Files.deleteIfExists(files.resolve(String.valueOf(a.get("id")))); } catch (IOException ignored) { }
    }

    /** Stores an uploaded or downloaded file and returns its public metadata. */
    Map<String, Object> store(String name, String type, byte[] data) throws IOException {
        if (data.length == 0 || data.length > MAX_FILE) throw new IllegalArgumentException("Datei leer oder größer als 8 MiB");
        String id = UUID.randomUUID().toString().replace("-", "");
        String clean = (name == null || name.isBlank() ? "datei" : name).replaceAll("[^A-Za-z0-9._ -]", "_");
        if (clean.length() > 80) clean = clean.substring(clean.length() - 80);
        String mime = type == null || !type.matches("[a-z]+/[a-z0-9.+-]{1,80}") ? "application/octet-stream" : type;
        Files.write(files.resolve(id), data, StandardOpenOption.CREATE_NEW); AppApiSecurity.protect(files.resolve(id));
        return SimplexAdminSuite.appMap("id", id, "name", clean, "type", mime, "size", data.length, "image", mime.startsWith("image/"), "audio", mime.startsWith("audio/") || clean.toLowerCase(Locale.ROOT).matches(".*\\.(m4a|aac|mp3|wav|ogg|opus|caf)$"));
    }

    synchronized Map<String, Object> attachment(String id) {
        for (Map<String, Object> row : rows) if (row.get("attachments") instanceof List<?> list) for (Object o : list) if (o instanceof Map<?, ?> a && id.equals(a.get("id"))) { Map<String, Object> copy = new LinkedHashMap<>(); a.forEach((k, v) -> copy.put(String.valueOf(k), v)); return copy; }
        return null;
    }
    Path attachmentFile(String id) { if (!id.matches("[a-f0-9]{32}")) throw new IllegalArgumentException("Anhang-ID ungültig"); return files.resolve(id); }

    synchronized Object read(long after) {
        List<Map<String, Object>> items = rows.stream().filter(r -> AdminActivityLog.number(r.get("id")) > after).toList();
        List<Map<String, Object>> people = new ArrayList<>();
        for (var e : members.entrySet()) people.add(SimplexAdminSuite.appMap("id", e.getKey(), "name", e.getValue()));
        people.sort(Comparator.comparing(m -> String.valueOf(m.get("name")), String.CASE_INSENSITIVE_ORDER));
        return SimplexAdminSuite.appMap("items", items, "cursor", sequence, "members", people, "channel", channel(), "discord", discordEnabled(), "limit", LIMIT, "maxFile", MAX_FILE, "lastError", lastError, "ownerName", plugin.getConfig().getString("app-api.owner-name", ""));
    }

    /** Known Discord staff: authors seen in the staff channel plus linked accounts of in-game staff. */
    private synchronized void member(String discordId, String name) { if (discordId != null && discordId.matches("[0-9]{5,25}") && name != null && !name.isBlank()) members.put(discordId, clip(name, 64)); }

    // ------------------------------------------------------------------ inbound

    /** In-game /staffmsg (StaffWorkspace.post) mirrored to Discord and the app. */
    void fromIngame(String author, UUID uuid, String text) {
        Map<String, Object> row = add(author, uuid == null ? "" : uuid.toString(), "ingame", text, List.of(), List.of());
        forward(row, uuid == null ? null : "https://mc-heads.net/avatar/" + uuid + "/128", List.of());
    }

    /** Message in the configured Discord staff channel. Returns true when handled. */
    @SuppressWarnings("unchecked")
    boolean fromDiscord(Map<String, Object> message, String userId, String name, String channelId) {
        if (!discordEnabled() || !channel().equals(channelId)) return false;
        DiscordBotManager bot = plugin.discordRelay(); if (bot == null) return true;
        member(userId, name);
        String text = RelayText.fromDiscord(message, false);
        List<String> mentioned = new ArrayList<>();
        if (message.get("mentions") instanceof List<?> list) for (Object o : list) if (o instanceof Map<?, ?> u) { String n = String.valueOf(u.get("global_name") == null ? u.get("username") : u.get("global_name")); mentioned.add(n); }
        List<?> files = message.get("attachments") instanceof List<?> l ? l : List.of();
        bot.ticketJob(() -> {
            List<Map<String, Object>> stored = new ArrayList<>();
            for (Object raw : files) {
                if (stored.size() >= 6 || !(raw instanceof Map<?, ?> a)) continue;
                try {
                    long size = a.get("size") instanceof Number n ? n.longValue() : 0; if (size <= 0 || size > MAX_FILE) continue;
                    URI uri = URI.create(String.valueOf(a.get("url"))); String host = uri.getHost() == null ? "" : uri.getHost();
                    if (!"https".equals(uri.getScheme()) || !(host.equals("cdn.discordapp.com") || host.equals("media.discordapp.net"))) continue;
                    String type = String.valueOf(a.get("content_type") == null ? "" : a.get("content_type")).split(";")[0].trim().toLowerCase(Locale.ROOT);
                    stored.add(store(String.valueOf(a.get("filename")), type, bot.ticketDownload(uri, MAX_FILE)));
                } catch (Exception e) { lastError = "Discord-Anhang: " + e.getClass().getSimpleName(); }
            }
            if (text.isBlank() && stored.isEmpty()) return;
            add(name, userId, "discord", text, stored, mentioned);
            Bukkit.getScheduler().runTask(plugin, () -> ingame(name, "Discord", text, stored));
        });
        return true;
    }

    /** App message (text and optional attachment) in the sender's name. */
    Object fromApp(String sender, String text, List<Map<String, Object>> attachments, List<String> pings, String auditActor) {
        String clean = text == null ? "" : text.replace("\r", "").trim();
        if (clean.length() > MAX_TEXT || clean.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n')) throw new IllegalArgumentException("Nachricht: maximal " + MAX_TEXT + " Zeichen ohne Steuerzeichen");
        if (clean.isBlank() && (attachments == null || attachments.isEmpty())) throw new IllegalArgumentException("Nachricht oder Datei fehlt");
        List<String> ids = new ArrayList<>(); List<String> names = new ArrayList<>();
        synchronized (this) {
            for (String ping : pings == null ? List.<String>of() : pings) {
                if (ping.equals("all")) { ids.addAll(members.keySet()); names.add("alle"); }
                else if (members.containsKey(ping)) { ids.add(ping); names.add(members.get(ping)); }
            }
        }
        List<String> unique = new ArrayList<>(new LinkedHashSet<>(ids));
        Map<String, Object> row = add(sender, "", "app", clean, attachments, names);
        forward(row, avatar(sender), unique.size() > 50 ? unique.subList(0, 50) : unique);
        Bukkit.getScheduler().runTask(plugin, () -> { ingame(sender, "App", clean, attachments == null ? List.of() : attachments); plugin.suiteAudit(auditActor, "Staff-Chat", clip(clean, 200)); });
        return SimplexAdminSuite.appMap("ok", true, "id", row.get("id"));
    }

    private String avatar(String name) {
        try { for (var p : plugin.expansionProfiles()) if (p.displayName().equalsIgnoreCase(name)) return "https://mc-heads.net/avatar/" + p.uniqueId() + "/128"; } catch (RuntimeException ignored) { }
        return null;
    }

    /** Admins online see app/Discord staff messages like in-game staff messages; the staff menu keeps a copy. */
    private void ingame(String author, String via, String text, List<Map<String, Object>> attachments) {
        StringBuilder line = new StringBuilder(text == null ? "" : SimplexAdminSuite.stripFormatting(text));
        for (Map<String, Object> a : attachments) line.append(line.length() == 0 ? "" : " ").append(Boolean.TRUE.equals(a.get("audio")) ? "[Sprachnachricht]" : Boolean.TRUE.equals(a.get("image")) ? "[Bild]" : "[Datei " + a.get("name") + "]");
        String formatted = plugin.prefix() + plugin.color("&6[Staff] &e" + author + " &8(" + via + ")&7: &f") + line;
        for (Player player : Bukkit.getOnlinePlayers()) if (plugin.has(player, "simplexadminsuite.admin")) player.sendMessage(plugin.playerUiText(player, formatted));
        plugin.staffMirror(author, line.toString());
    }

    // ------------------------------------------------------------------ outbound

    private void forward(Map<String, Object> row, String avatar, List<String> pings) {
        DiscordBotManager bot = plugin.discordRelay();
        if (!discordEnabled() || bot == null) return;
        bot.ticketJob(() -> {
            try {
                String author = String.valueOf(row.get("author"));
                String text = String.valueOf(row.get("text")).replace("@everyone", "@​everyone").replace("@here", "@​here");
                String content = (pings.isEmpty() ? "" : String.join(" ", pings.stream().map(id -> "<@" + id + ">").toList()) + (text.isBlank() ? "" : "\n")) + text;
                Map<String, Object> allowed = SimplexAdminSuite.appMap("parse", List.of(), "users", pings);
                List<Object[]> upload = new ArrayList<>(); List<Object> meta = new ArrayList<>();
                if (row.get("attachments") instanceof List<?> list) for (Object o : list) if (o instanceof Map<?, ?> a) {
                    byte[] data = Files.readAllBytes(attachmentFile(String.valueOf(a.get("id"))));
                    String name = String.valueOf(a.get("name")).replaceAll("[^A-Za-z0-9._-]", "_");
                    meta.add(SimplexAdminSuite.appMap("id", upload.size(), "filename", name)); upload.add(new Object[]{name, String.valueOf(a.get("type")), data});
                }
                String[] webhook = webhook(bot);
                HttpResponse<String> response;
                if (webhook != null) {
                    String via = "ingame".equals(row.get("source")) ? " · Minecraft" : "";
                    Map<String, Object> payload = SimplexAdminSuite.appMap("username", clip(author + via, 80), "content", clip(content, 1990), "allowed_mentions", allowed);
                    if (avatar != null) payload.put("avatar_url", avatar);
                    if (!meta.isEmpty()) payload.put("attachments", meta);
                    String path = "/webhooks/" + webhook[0] + "/" + webhook[1] + "?wait=true";
                    response = upload.isEmpty() ? bot.ticketHttp("POST", path, DiscordBotManager.Json.stringify(payload)) : bot.ticketMultipart(path, DiscordBotManager.Json.stringify(payload), upload);
                    if (response.statusCode() == 404 || response.statusCode() == 401) { synchronized (this) { hook.clear(); saveHook(); } webhook = null; }
                } else response = null;
                if (webhook == null) {
                    Map<String, Object> embed = SimplexAdminSuite.appMap("author", SimplexAdminSuite.appMap("name", clip(author, 250)), "description", clip(text.isBlank() ? " " : text, 3900), "color", 0x14B8A6, "timestamp", java.time.Instant.ofEpochMilli(AdminActivityLog.number(row.get("at"))).toString());
                    if (avatar != null) { @SuppressWarnings("unchecked") Map<String, Object> head = (Map<String, Object>) embed.get("author"); head.put("icon_url", avatar); }
                    Map<String, Object> payload = SimplexAdminSuite.appMap("embeds", List.of(embed), "allowed_mentions", allowed);
                    if (!pings.isEmpty()) payload.put("content", String.join(" ", pings.stream().map(id -> "<@" + id + ">").toList()));
                    if (!meta.isEmpty()) payload.put("attachments", meta);
                    String path = "/channels/" + channel() + "/messages";
                    response = upload.isEmpty() ? bot.ticketHttp("POST", path, DiscordBotManager.Json.stringify(payload)) : bot.ticketMultipart(path, DiscordBotManager.Json.stringify(payload), upload);
                }
                lastError = response.statusCode() / 100 == 2 ? "" : "Discord: HTTP " + response.statusCode() + " " + DiscordDiagnostics.detail(response.body(), "");
            } catch (Exception e) { lastError = "Discord: " + e.getClass().getSimpleName(); }
        });
    }

    /** Lazily created channel webhook (needs „Webhooks verwalten“). Returns {id, token} or null for the embed fallback. */
    private synchronized String[] webhook(DiscordBotManager bot) {
        if (!plugin.getConfig().getBoolean("staff-chat.use-webhook", true)) return null;
        String ch = channel();
        if (ch.equals(hook.getProperty("channel")) && hook.getProperty("id", "").matches("[0-9]{15,22}")) return new String[]{hook.getProperty("id"), hook.getProperty("token")};
        if (System.currentTimeMillis() - AdminActivityLog.number(hook.getProperty("failed", "0")) < 3_600_000L && ch.equals(hook.getProperty("failedChannel"))) return null;
        try {
            HttpResponse<String> r = bot.ticketHttp("POST", "/channels/" + ch + "/webhooks", DiscordBotManager.Json.stringify(SimplexAdminSuite.appMap("name", "Simplex Staff-Chat")));
            if (r.statusCode() / 100 == 2 && DiscordBotManager.Json.parse(r.body()) instanceof Map<?, ?> m && m.get("token") != null) {
                hook.clear(); hook.setProperty("channel", ch); hook.setProperty("id", String.valueOf(m.get("id"))); hook.setProperty("token", String.valueOf(m.get("token"))); saveHook();
                return new String[]{hook.getProperty("id"), hook.getProperty("token")};
            }
        } catch (Exception ignored) { }
        hook.clear(); hook.setProperty("failed", Long.toString(System.currentTimeMillis())); hook.setProperty("failedChannel", ch); saveHook();
        return null;
    }
    private void saveHook() { try { AtomicState.save(secrets, hook, "Private Discord webhook for the staff chat. Do not share."); AppApiSecurity.protect(secrets); } catch (IOException ignored) { } }

    /** Linked Discord accounts of in-game staff become mention targets as well. */
    void refreshMembers(Collection<UUID> staff) {
        if (plugin.accountLinks == null) return;
        for (UUID id : staff) { var link = plugin.accountLinks.store.byMinecraft(id); if (link != null) member(link.discord(), link.name()); }
        persist();
    }

    static String clip(String value, int max) { String s = value == null ? "" : value; return s.length() > max ? s.substring(0, max) : s; }
}
