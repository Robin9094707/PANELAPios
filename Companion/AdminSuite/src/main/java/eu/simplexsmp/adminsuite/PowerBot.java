package eu.simplexsmp.adminsuite;

import com.sun.net.httpserver.HttpExchange;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/** Chat decisions, separate reversible sanctions and the staff handoff queue. */
final class PowerBot implements AutoCloseable {
    static final String FEATURE = "powerbot-v1", VIEW = "data.powerbot", MANAGE = "powerbot.manage", SETTINGS = "powerbot.settings";
    static final String KEYWORDS_FEATURE = "powerbot-keywords-v1", HOME_FEATURE = "suite-home-events-v1";
    /** 10.13.0: case evidence, player management, editable system prompts and the briefing. */
    static final String CASES_FEATURE = "powerbot-cases-v2", BRIEFING_FEATURE = "briefing-v1", TIMELINE_FEATURE = "player-timeline-v1";
    static final String DEFAULT_MODERATION_PROMPT = "Allow ordinary banter, gameplay violence, friendly teasing, quotations for reporting and ambiguous cases. "
        + "Do not punish small disagreements or a single rude word. BLOCK clear directed harassment, serious targeted threats, "
        + "repeated abusive targeting evident in context, or discriminatory abuse. Use WARN for mild escalation when a reminder would help.";
    static final String DEFAULT_ASSISTANT_PROMPT = "Stay friendly, neutral and short. Only intervene in a real, sustained hostile dispute or when players ask for staff. "
        + "Never name or blame players publicly. Calm the situation down, remind everyone of respectful chat and offer /report or a ticket for help.";
    private static final String ROOT = "powerbot.";
    private static final List<String> FLAGS = List.of("enabled", "moderation", "whispers", "advertising", "auto-mute", "linked-mutes",
        "public-chat", "proactive", "conflict-actions", "discord", "notify-staff", "security-hints", "staff-device-context", "critical-alerts");
    private static final List<String> STRICTNESS = List.of("relaxed", "balanced", "strict");
    private static final List<String> SEVERITY = List.of("low", "medium", "high", "severe");
    private static final Set<String> CASES = Set.of("mute", "conflict", "help", "security", "outage");
    /** Entries that describe something the bot did or found about one player. */
    private static final Set<String> PLAYER_KINDS = Set.of("mute", "conflict", "help", "security", "abuse", "advertising", "keyword-warning",
        "keyword-block", "warning", "reviewed", "deescalation", "whisper-block", "pardon", "staff-mute");
    private static final long MINUTE = 60_000L, WINDOW = 20 * MINUTE;
    private static final Pattern PROMOTION = Pattern.compile("(?iu)\\b(?:join (?:my|our) (?:minecraft )?server|(?:kom[mmt]+|joint|kommt|spiel(?:t)?) (?:auf |bei )?(?:meinen|unseren|unserem) (?:minecraft[- ]?)?server|(?:my|our|mein|unser) (?:minecraft[- ]?)?server\\s*(?:ip|address|adresse))\\b");
    private static final Pattern DESTINATION = Pattern.compile("(?i)(?<![a-z0-9-])(?:[a-z0-9](?:[a-z0-9-]{0,60}[a-z0-9])?\\.)+[a-z]{2,24}\\b|\\b(?:[0-9]{1,3}\\.){3}[0-9]{1,3}\\b");
    private final SimplexAdminSuite plugin;
    private final Path file;
    private final PowerBotKeywords keywords;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
    private final ThreadPoolExecutor reviews = new ThreadPoolExecutor(3, 3, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(12), task -> {
        Thread thread = new Thread(task, "Simplex-PowerBot");
        thread.setDaemon(true);
        return thread;
    });
    private volatile Map<String, Boolean> flags;
    private volatile String policy, moderationPrompt, assistantPrompt, strictness;
    private volatile long bypassUntil;
    private long lastFailure;
    private boolean bypassAnnounced;

    /** What the bot saw and answered. Every field is optional so 10.12 logs stay readable. */
    record Evidence(String message, String reason, String reply, String severity, List<Map<String, Object>> context, boolean whisper) {
        static final Evidence EMPTY = new Evidence("", "", "", "", List.of(), false);
        Evidence {
            message = clean(message, 500);
            reason = clean(reason, 300);
            reply = clean(reply, 300);
            severity = SEVERITY.contains(severity) ? severity : "";
            context = context == null ? List.of() : context.stream().limit(16).map(Evidence::line).toList();
        }
        private static Map<String, Object> line(Map<String, Object> raw) {
            return SimplexAdminSuite.appMap("name", clean(String.valueOf(raw.getOrDefault("name", "")), 64),
                "player", clean(String.valueOf(raw.getOrDefault("player", "")), 64),
                "text", clean(String.valueOf(raw.getOrDefault("text", "")), 300),
                "source", clean(String.valueOf(raw.getOrDefault("source", "")), 24),
                "at", AiAdmin.number(raw.get("at")), "highlight", Boolean.TRUE.equals(raw.get("highlight")),
                "bot", Boolean.TRUE.equals(raw.get("bot")));
        }
        boolean empty() { return message.isBlank() && reason.isBlank() && reply.isBlank() && context.isEmpty() && severity.isBlank(); }
        static Evidence parse(Object value) {
            if (!(value instanceof Map<?, ?>)) return EMPTY;
            var row = AppApiManager.object(value);
            List<Map<String, Object>> lines = row.get("context") instanceof List<?> list
                ? list.stream().filter(Map.class::isInstance).map(AppApiManager::object).toList() : List.of();
            return new Evidence(AiAdmin.text(row, "message"), AiAdmin.text(row, "reason"), AiAdmin.text(row, "reply"),
                AiAdmin.text(row, "severity"), lines, Boolean.TRUE.equals(row.get("whisper")));
        }
        Map<String, Object> map() {
            return SimplexAdminSuite.appMap("message", message, "reason", reason, "reply", reply, "severity", severity,
                "context", context, "whisper", whisper);
        }
    }

    private record Entry(String id, long at, String kind, String player, String name, String source, String detail, long until,
                         List<String> targets, String assignee, boolean resolved, long revokedAt, Evidence evidence, String note) {
        Map<String, Object> row() {
            var row = SimplexAdminSuite.appMap("id", id, "at", at, "kind", kind, "player", player, "name", name, "source", source,
                "detail", detail, "until", until, "targets", targets, "assignee", assignee, "resolved", resolved, "revokedAt", revokedAt,
                "active", active(System.currentTimeMillis()), "case", CASES.contains(kind), "note", note);
            if (!evidence.empty()) row.put("evidence", evidence.map());
            return row;
        }
        boolean active(long now) { return kind.equals("mute") && until > now && revokedAt == 0; }
        Entry update(String owner, boolean closed, long revoked) {
            return new Entry(id, at, kind, player, name, source, detail, until, targets, owner, closed, revoked, evidence, note);
        }
        Entry noted(String text) {
            return new Entry(id, at, kind, player, name, source, detail, until, targets, assignee, resolved, revokedAt, evidence, text);
        }
    }

    PowerBot(SimplexAdminSuite plugin) throws IOException {
        this.plugin = plugin;
        file = plugin.getDataFolder().toPath().resolve("powerbot-actions.json");
        keywords = new PowerBotKeywords(file.resolveSibling("powerbot-keywords.properties"));
        if (Files.isRegularFile(file)) {
            try {
                Object data = DiscordBotManager.Json.parse(Files.readString(file));
                if (!(data instanceof List<?> rows)) throw new IllegalArgumentException("Ungültiges Format");
                for (Object item : rows) {
                    Map<String,Object> row = AppApiManager.object(item);
                    String id = AiAdmin.text(row, "id");
                    if (id.isBlank()) throw new IllegalArgumentException("Ungültiger Eintrag");
                    List<String> targets = row.get("targets") instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
                    entries.put(id, new Entry(id, AiAdmin.number(row.get("at")), AiAdmin.text(row, "kind"), AiAdmin.text(row, "player"),
                        AiAdmin.text(row, "name"), AiAdmin.text(row, "source"), AiAdmin.text(row, "detail"), AiAdmin.number(row.get("until")),
                        targets, AiAdmin.text(row, "assignee"), Boolean.TRUE.equals(row.get("resolved")), AiAdmin.number(row.get("revokedAt")),
                        Evidence.parse(row.get("evidence")), AiAdmin.text(row, "note")));
                }
            } catch (RuntimeException e) { throw new IOException("AutoMod-Protokoll konnte nicht gelesen werden", e); }
        }
        reload();
    }

    void reload() {
        Map<String, Boolean> next = new LinkedHashMap<>();
        for (String key : FLAGS) next.put(key, plugin.getConfig().getBoolean(ROOT + key, !key.equals("critical-alerts")));
        flags = Map.copyOf(next);
        policy = clean(plugin.getConfig().getString(ROOT + "guidelines", ""), 2000);
        moderationPrompt = prompt(plugin.getConfig().getString(ROOT + "system-prompt", ""), DEFAULT_MODERATION_PROMPT);
        assistantPrompt = prompt(plugin.getConfig().getString(ROOT + "assistant-prompt", ""), DEFAULT_ASSISTANT_PROMPT);
        String level = plugin.getConfig().getString(ROOT + "strictness", "balanced");
        strictness = STRICTNESS.contains(level) ? level : "balanced";
    }

    private static String prompt(String value, String fallback) {
        String text = clean(value, 4000);
        return text.isBlank() ? fallback : text;
    }

    boolean enabled() { return on("enabled"); }
    boolean on(String key) { return flags.getOrDefault(key, false); }
    boolean managed(String source) { return enabled() && (!source.equals("Discord") || on("discord")); }
    boolean canStaffDeviceContext() { return on("staff-device-context"); }
    boolean chatReady() { return configured() && System.currentTimeMillis() >= bypassUntil; }
    String strictness() { return strictness; }
    String assistantPrompt() { return assistantPrompt; }
    private boolean configured() { return plugin.openAiIntegration() != null && plugin.openAiIntegration().configured(); }
    /** Confirmed attempts within 20 minutes before an automatic mute. */
    int muteThreshold() { return switch (strictness) { case "relaxed" -> 4; case "strict" -> 2; default -> 3; }; }
    /** Lowest conflict severity that becomes a staff case. */
    String caseSeverity() { return strictness.equals("relaxed") ? "high" : strictness.equals("strict") ? "low" : "medium"; }
    static boolean atLeast(String severity, String minimum) { return SEVERITY.indexOf(severity) >= Math.max(0, SEVERITY.indexOf(minimum)); }

    private synchronized void persist(Entry row) {
        var next = new LinkedHashMap<>(entries);
        next.put(row.id, row);
        long now = System.currentTimeMillis();
        next.values().removeIf(entry -> entry.at < now - 30L * 24 * 60 * MINUTE && !entry.active(now));
        var iterator = next.values().iterator();
        while (next.size() > 2000 && iterator.hasNext()) if (!iterator.next().active(now)) iterator.remove();
        try {
            Path temp = Files.createTempFile(file.getParent(), "powerbot-", ".json");
            try {
                Files.writeString(temp, DiscordBotManager.Json.stringify(next.values().stream().map(Entry::row).toList()));
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temp); }
            entries.clear();
            entries.putAll(next);
        } catch (IOException error) {
            throw new IllegalStateException("AutoMod-Protokoll konnte nicht gespeichert werden", error);
        }
    }

    private synchronized Entry record(String kind, String player, String name, String source, String detail, long until, List<String> targets) {
        return record(kind, player, name, source, detail, until, targets, Evidence.EMPTY);
    }

    private synchronized Entry record(String kind, String player, String name, String source, String detail, long until, List<String> targets, Evidence evidence) {
        Entry row = new Entry(UUID.randomUUID().toString(), System.currentTimeMillis(), kind, player, clean(name, 64), source,
            clean(detail, 500), until, List.copyOf(targets), "", false, 0, evidence == null ? Evidence.EMPTY : evidence, "");
        persist(row);
        return row;
    }

    static String clean(String value, int length) {
        return SparkProfile.clip(ConsoleArchive.safeText(OpenAiIntegration.redactSecrets(Objects.toString(value, "")))
            .replaceAll("[\\p{Cntrl}§]", " ").strip(), length);
    }

    static boolean advertising(String text, String serverAddress) {
        if (!PROMOTION.matcher(text).find()) return false;
        var candidates = DESTINATION.matcher(text);
        while (candidates.find()) {
            String host = candidates.group().toLowerCase(Locale.ROOT);
            if (host.equals("simplexsmp.eu") || host.endsWith(".simplexsmp.eu") || host.equalsIgnoreCase(serverAddress)) continue;
            return true;
        }
        return false;
    }

    synchronized boolean muted(String player) {
        long now = System.currentTimeMillis();
        return !player.isBlank() && entries.values().stream().anyMatch(row -> row.active(now) && row.targets.contains(player));
    }

    synchronized long mutedUntil(String player) {
        long now = System.currentTimeMillis();
        return entries.values().stream().filter(row -> row.active(now) && row.targets.contains(player)).mapToLong(Entry::until).max().orElse(0);
    }

    boolean bypass(Player player) {
        return plugin.has(player, "simplexadminsuite.admin") || plugin.has(player, "simplexadminsuite.powerbot.bypass");
    }

    /** The public chat around a message, with the message itself appended and highlighted when it was not delivered. */
    private Evidence evidence(String identity, String name, String source, String text, String reason, String reply, boolean delivered) {
        boolean whisper = source.equals("Whisper");
        List<Map<String, Object>> lines = new ArrayList<>();
        if (!whisper && plugin.publicChatAssistant != null) lines.addAll(plugin.publicChatAssistant.evidence());
        String sample = clean(text, 300);
        boolean found = false;
        for (int i = lines.size() - 1; i >= 0 && delivered; i--) {
            var line = lines.get(i);
            if (identity.equals(line.get("player")) && sample.equals(line.get("text"))) {
                var copy = new LinkedHashMap<>(line);
                copy.put("highlight", true);
                lines.set(i, copy);
                found = true;
                break;
            }
        }
        if (!found && !whisper && !sample.isBlank()) lines.add(SimplexAdminSuite.appMap("name", name, "player", identity, "text", sample,
            "source", source, "at", System.currentTimeMillis(), "highlight", true, "bot", false));
        if (!reply.isBlank() && !whisper) lines.add(SimplexAdminSuite.appMap("name", "PowerBot", "player", "", "text", reply,
            "source", "PowerBot", "at", System.currentTimeMillis(), "highlight", false, "bot", true));
        return new Evidence(sample, reason, reply, "", lines.size() > 16 ? lines.subList(lines.size() - 16, lines.size()) : lines, whisper);
    }

    OpenAiIntegration.ChatDecision review(String identity, String name, String source, String text, boolean german, boolean onMain) {
        var local = preflight(identity, name, source, text, german);
        if (!local.allowed || !managed(source) || text.isBlank()) return local;
        return reviewModel(identity, name, source, text, german, onMain);
    }

    OpenAiIntegration.ChatDecision preflight(String identity, String name, String source, String text, boolean german) {
        if (!managed(source) || text.isBlank()) return OpenAiIntegration.ChatDecision.allow();
        if (muted(identity)) return OpenAiIntegration.ChatDecision.block(
            german ? "Dein AutoMod-Chatmute läuft noch." : "Your AutoMod chat mute is still active.", "AutoMod mute");
        if (source.equals("Whisper") && !on("whispers")) return OpenAiIntegration.ChatDecision.allow();
        if (on("advertising") && advertising(text, plugin.getConfig().getString("server-address", "simplexsmp.eu"))) {
            applyReview(identity, name, source, "advertising", evidence(identity, name, source, text,
                "Werbung für einen fremden Server erkannt.", "Eigenwerbung für fremde Server ist hier nicht erlaubt.", false));
            return OpenAiIntegration.ChatDecision.block(german ? "Eigenwerbung für fremde Server ist hier nicht erlaubt."
                : "Advertising another server is not allowed here.", "AutoMod: advertising");
        }
        return OpenAiIntegration.ChatDecision.allow();
    }

    void reviewWhisper(String identity, String name, String text, boolean german, boolean firstJoin,
                       java.util.function.Consumer<OpenAiIntegration.ChatDecision> completed) {
        try {
            reviews.execute(() -> {
                OpenAiIntegration.ChatDecision decision = null;
                try {
                    decision = preflight(identity, name, "Whisper", text, german);
                    if (decision.allowed && enabled() && on("whispers") && on("moderation")) {
                        if (!chatReady()) decision = null;
                        else {
                            String sample = clean(text, 450);
                            var result = plugin.openAiIntegration().powerBotReview(sample, policy, true,
                                keywords.matches(sample), firstJoin, moderationPrompt, strictness);
                            recovered();
                            decision = result.decision();
                            if (!decision.allowed) {
                                String reply = "Deine Flüsternachricht wurde zurückgehalten.";
                                var proof = evidence(identity, name, "Whisper", sample, decision.reason, reply, false);
                                if (result.moderationFlagged()) applyReview(identity, name, "Whisper", "abuse", proof);
                                else record("whisper-block", identity, name, "Whisper", firstJoin
                                    ? "Flüstern beim ersten Beitritt zurückgehalten."
                                    : "Flüstern nach Kontextprüfung zurückgehalten.", 0, List.of(), proof);
                                decision = OpenAiIntegration.ChatDecision.block(
                                    german ? reply : "Your whisper was held.", "AutoMod: whisper blocked");
                            } else if (decision.warning) {
                                String reply = "Bitte beachte die Chatregeln beim Flüstern.";
                                record("warning", identity, name, "Whisper", "Kontextprüfung: Hinweis an Spieler", 0, List.of(),
                                    evidence(identity, name, "Whisper", sample, decision.reason, reply, true));
                                decision = OpenAiIntegration.ChatDecision.warn(
                                    german ? reply : "Please follow the rules when whispering.", "AutoMod: whisper warning");
                            }
                        }
                    }
                } catch (Exception error) {
                    decision = null;
                    if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                    failed();
                }
                completed.accept(decision);
            });
        } catch (RejectedExecutionException busy) {
            completed.accept(null);
        }
    }

    OpenAiIntegration.ChatDecision reviewModel(String identity, String name, String source, String text, boolean german, boolean onMain) {
        if (!managed(source) || text.isBlank() || !on("moderation") || !chatReady()) return OpenAiIntegration.ChatDecision.allow();
        if (source.equals("Whisper") && !on("whispers")) return OpenAiIntegration.ChatDecision.allow();
        boolean whisper = source.equals("Whisper");
        String sample = clean(text, 450);
        String instructions = policy, base = moderationPrompt, level = strictness;
        var matches = keywords.matches(sample);
        String matched = matches.isEmpty() ? "" : "Stichwörter: " + String.join(", ", matches.stream().map(rule -> rule.get("keyword")).toList());
        if (onMain) {
            // Commands run on the server tick. Review in the background and apply future mutes without replaying commands.
            try {
                reviews.execute(() -> {
                    try {
                        var result = plugin.openAiIntegration().powerBotReview(sample, instructions, whisper, matches, false, base, level);
                        if (chatReady()) recovered();
                        var decision = result.decision();
                        if ((!decision.allowed || decision.warning && !matched.isEmpty()) && managed(source) && on("moderation")) {
                            String reply = "Deine Nachricht wurde nachträglich beanstandet. Bitte beachte die Chatregeln.";
                            var proof = evidence(identity, name, source, sample, decision.reason, reply, true);
                            if (!decision.allowed && result.moderationFlagged()) applyReview(identity, name, source, "reviewed", proof);
                            if (!matched.isEmpty()) important("keyword-warning", identity, name, source,
                                "Nachträglich geprüft · " + matched, false, proof);
                            plugin.runAdminTask(() -> {
                                Player target = Bukkit.getPlayer(UUID.fromString(identity));
                                if (target != null) target.sendMessage(plugin.playerText(target,
                                    "§e[PowerBot] " + reply,
                                    "§e[PowerBot] Your message was flagged after delivery. Please follow the chat rules."));
                            });
                        }
                    } catch (Exception error) { failed(); }
                });
            } catch (RejectedExecutionException busy) { failed(); }
            return OpenAiIntegration.ChatDecision.allow();
        }
        Future<OpenAiIntegration.PowerBotDecision> work = null;
        try {
            work = reviews.submit(() -> plugin.openAiIntegration().powerBotReview(sample, instructions, false, matches, false, base, level));
            var result = work.get(6500, TimeUnit.MILLISECONDS);
            var decision = result.decision();
            if (chatReady()) recovered();
            if (!managed(source) || !on("moderation")) return OpenAiIntegration.ChatDecision.allow();
            if (!decision.allowed) {
                String reply = "Deine Nachricht wurde wegen eines bestätigten Chatverstoßes zurückgehalten.";
                var proof = evidence(identity, name, source, sample, decision.reason, reply, false);
                if (result.moderationFlagged()) applyReview(identity, name, source, "abuse", proof);
                else important("keyword-block", identity, name, source, matched.isBlank() ? "Nachricht nach Kontextprüfung zurückgehalten." : matched, false, proof);
                return OpenAiIntegration.ChatDecision.block(german ? reply
                    : "Your message was held for a confirmed chat violation.", "AutoMod: confirmed violation");
            }
            if (decision.warning) {
                String reply = "Bitte beachte die Chatregeln.";
                var proof = evidence(identity, name, source, sample, decision.reason, reply, false);
                if (matched.isEmpty()) record("warning", identity, name, source, "Kontextprüfung: Hinweis an Spieler", 0, List.of(), proof);
                else important("keyword-warning", identity, name, source, matched, false, proof);
                return OpenAiIntegration.ChatDecision.warn(german ? reply : "Please follow the chat rules.", "AutoMod warning");
            }
        } catch (InterruptedException interrupted) {
            if (work != null) work.cancel(true);
            Thread.currentThread().interrupt();
        } catch (Exception error) {
            if (work != null) work.cancel(true);
            failed();
        }
        return OpenAiIntegration.ChatDecision.allow();
    }

    private void applyReview(String identity, String name, String source, String category, Evidence proof) {
        try {
            record(category, identity, name, source, switch (category) {
                case "advertising" -> "Eigenwerbung zurückgehalten und Hinweis ausgegeben.";
                case "reviewed" -> "Nachricht im Hintergrund geprüft. Spielerhinweis ausgelöst.";
                default -> "Nachricht nach Moderationssignal und Kontextprüfung zurückgehalten.";
            }, 0, List.of(), proof);
            if (on("auto-mute") && !identity.isBlank()) plugin.runAdminTask(() -> evaluateMute(identity, name, source, category, proof));
        } catch (RuntimeException error) { plugin.getLogger().warning("PowerBot: Aktionsprotokoll konnte nicht gespeichert werden."); }
    }

    private Set<String> muteTargets(String identity) {
        UUID id;
        try { id = identity.startsWith("discord:") ? null : UUID.fromString(identity); } catch (IllegalArgumentException invalid) { return Set.of(identity); }
        Set<String> targets = new LinkedHashSet<>();
        targets.add(identity);
        if (id != null && on("linked-mutes")) for (UUID alt : plugin.confirmedAlts(id)) {
            var profile = plugin.expansionProfile(alt);
            if (profile != null && !plugin.isStaffAccount(alt)) targets.add(alt.toString());
        }
        return targets;
    }

    private static UUID uuid(String identity) {
        try { return identity.startsWith("discord:") ? null : UUID.fromString(identity); } catch (IllegalArgumentException invalid) { return null; }
    }

    private void evaluateMute(String identity, String name, String source, String category, Evidence proof) {
        UUID id = uuid(identity);
        if (id == null && !identity.startsWith("discord:")) return;
        Set<String> targets = muteTargets(identity);
        Entry mute;
        synchronized (this) {
            if (!enabled() || !on("auto-mute") || muted(identity) || plugin.isStaffAccount(id)) return;
            int hits = score(identity, category.equals("advertising"), System.currentTimeMillis());
            if (hits < muteThreshold()) return;
            try { mute = record("mute", identity, name, source, hits + " bestätigte Versuche in 20 Minuten · " +
                (category.equals("advertising") ? "Eigenwerbung" : "Chatverstoß"), System.currentTimeMillis() + 10 * MINUTE, List.copyOf(targets),
                new Evidence(proof.message(), proof.reason(), "Chatmute für 10 Minuten wegen wiederholter Verstöße.", "high", proof.context(), proof.whisper())); }
            catch (IllegalStateException failure) { plugin.getLogger().warning("PowerBot: Chatmute ohne gesicherten Verlauf abgebrochen."); return; }
        }
        announceMute(mute, 10);
        notifyStaff(mute, "AutoMod-Mute", on("critical-alerts"));
    }

    private void announceMute(Entry mute, long minutes) {
        for (String target : mute.targets) {
            Player player = online(target);
            if (player != null) player.sendMessage(plugin.playerText(player, "§c[PowerBot] Chatmute für " + minutes + " Minuten. Grund: " + (mute.evidence.reason().isBlank() ? mute.detail : mute.evidence.reason()),
                "§c[PowerBot] Chat muted for " + minutes + " minutes."));
        }
    }

    /**
     * 10.13.0: a short chat pause for the participant driving a severe dispute. Only for real escalation
     * (severity "severe"), never for staff, never twice within 30 minutes for the same player.
     */
    boolean conflictMute(UUID player, String name, Evidence proof) {
        if (player == null || !enabled() || !on("conflict-actions") || plugin.isStaffAccount(player)) return false;
        Player online = Bukkit.getPlayer(player);
        if (online == null || bypass(online)) return false;
        String identity = player.toString();
        long now = System.currentTimeMillis();
        long minutes = Math.max(1, Math.min(30, plugin.getConfig().getLong(ROOT + "conflict-mute-minutes", 5)));
        Entry mute;
        synchronized (this) {
            if (muted(identity) || entries.values().stream().anyMatch(row -> row.player.equals(identity) && row.kind.equals("mute")
                && row.source.equals("Streit") && now - row.at < 30 * MINUTE)) return false;
            try {
                mute = record("mute", identity, name, "Streit", "Schwerer Streit · " + minutes + " Minuten Chatpause", now + minutes * MINUTE,
                    List.copyOf(muteTargets(identity)), new Evidence(proof.message(), proof.reason(),
                        "Kurze Chatpause, damit sich die Lage beruhigt.", "severe", proof.context(), false));
            } catch (IllegalStateException failure) { return false; }
        }
        announceMute(mute, minutes);
        notifyStaff(mute, "Schwerer Streit · Chatpause", on("critical-alerts"));
        return true;
    }

    private static Player online(String identity) {
        UUID id = uuid(identity);
        return id == null ? null : Bukkit.getPlayer(id);
    }

    private int score(String identity, boolean ads, long now) {
        int hits = 0;
        long last = 0;
        long cutoff = now - WINDOW;
        for (Entry row : entries.values()) if (row.player.equals(identity) && Set.of("mute", "pardon").contains(row.kind)) cutoff = Math.max(cutoff, row.at);
        for (Entry row : entries.values()) {
            if (!row.player.equals(identity) || row.at <= cutoff || row.at - last < 4000) continue;
            if (!(ads ? row.kind.equals("advertising") : Set.of("abuse", "reviewed").contains(row.kind))) continue;
            hits++;
            last = row.at;
        }
        return hits;
    }

    private synchronized void failed() {
        if (!enabled()) return;
        long now = System.currentTimeMillis();
        if (now < bypassUntil) return;
        if (now - lastFailure > MINUTE) { lastFailure = now; return; }
        bypassUntil = now + MINUTE;
        if (bypassAnnounced) return;
        bypassAnnounced = true;
        plugin.runAdminTask(() -> {
            statusMessage("KI-Moderation pausiert. Erweitertes Protokoll aktiv.", "AI moderation paused. Extended logging active.");
            important("outage", "", "", "System", "Die KI-Prüfung war wiederholt nicht erreichbar. Nachrichten werden ohne KI-Prüfung zugestellt.", false);
        });
    }

    private synchronized void recovered() {
        lastFailure = 0;
        if (!bypassAnnounced) return;
        bypassAnnounced = false;
        plugin.runAdminTask(() -> {
            statusMessage("KI-Moderation ist wieder aktiv.", "AI moderation has resumed.");
            important("recovery", "", "", "System", "Die KI-Verbindung ist wieder verfügbar.", false);
        });
    }

    private void statusMessage(String de, String en) {
        for (Player player : Bukkit.getOnlinePlayers()) player.sendMessage(plugin.playerText(player, "§b[PowerBot] §7" + de, "§b[PowerBot] §7" + en));
        plugin.appChat.record("", "PowerBot", en, "system");
        if (on("discord") && plugin.discordRelay() != null) plugin.discordRelay().relayPowerBot(de, en);
    }

    void important(String kind, String player, String name, String source, String detail, boolean alert) {
        important(kind, player, name, source, detail, alert, Evidence.EMPTY);
    }

    void important(String kind, String player, String name, String source, String detail, boolean alert, Evidence proof) {
        try {
            Entry entry = record(kind, player, name, source, detail, 0, List.of(), proof);
            if (alert) notifyStaff(entry, kind.equals("help") ? "Hilferuf im Chat" : "Chat-Konflikt erkannt", on("critical-alerts"));
        } catch (IllegalStateException failure) { plugin.getLogger().warning("PowerBot: Hinweis konnte nicht protokolliert werden."); }
    }

    private void notifyStaff(Entry row, String title, boolean critical) {
        if (on("notify-staff") && plugin.appEvents != null) {
            plugin.appEvents.powerBotAlert(title, row.name.isBlank() ? "Fallzentrale öffnen" : row.name + " · Fallzentrale öffnen", critical);
        }
    }

    /** Private whisper text and the whisper flag stay hidden from viewers without whisper access. */
    private static Map<String, Object> visible(Entry row, boolean whispers, boolean full) {
        var view = row.row();
        if (row.player.startsWith("discord:")) view.put("player", "");
        view.remove("targets");
        view.put("targetCount", row.targets.size());
        view.remove("evidence");
        Evidence proof = row.evidence;
        boolean hidden = proof.whisper() && !whispers;
        view.put("message", hidden ? "" : proof.message());
        view.put("messageHidden", hidden && !proof.message().isBlank());
        view.put("reason", proof.reason());
        view.put("reply", proof.reply());
        view.put("severity", proof.severity());
        view.put("whisper", proof.whisper());
        view.put("contextCount", hidden ? 0 : proof.context().size());
        Set<String> names = new LinkedHashSet<>();
        if (!row.name.isBlank()) names.add(row.name);
        if (!hidden) for (var line : proof.context()) if (!Boolean.TRUE.equals(line.get("bot")) && !String.valueOf(line.get("name")).isBlank()) names.add(String.valueOf(line.get("name")));
        view.put("participants", List.copyOf(names));
        if (full) view.put("context", hidden ? List.of() : proof.context());
        return view;
    }

    private static boolean matches(Entry row, String query) {
        if (query.isBlank()) return true;
        String haystack = (row.name + " " + row.detail + " " + row.kind + " " + row.evidence.message() + " " + row.evidence.reason() + " " + row.note).toLowerCase(Locale.ROOT);
        return Arrays.stream(query.toLowerCase(Locale.ROOT).split("\\s+")).allMatch(haystack::contains);
    }

    private synchronized Map<String, Object> data(boolean manage, boolean settings, boolean whispers, long after, String filter, String query, String player) {
        var rows = new ArrayList<>(entries.values());
        Collections.reverse(rows);
        long now = System.currentTimeMillis(), cutoff = Math.max(now - 30L * 24 * 60 * MINUTE, after);
        var recent = rows.stream().filter(row -> row.at > cutoff).toList();
        var visible = recent.stream().filter(row -> switch (filter) {
            case "open" -> CASES.contains(row.kind) && !row.resolved;
            case "mutes" -> row.kind.equals("mute");
            case "blocked" -> Set.of("abuse", "advertising", "keyword-block", "whisper-block", "reviewed").contains(row.kind);
            case "conflicts" -> Set.of("conflict", "help", "deescalation").contains(row.kind);
            case "warnings" -> Set.of("warning", "keyword-warning").contains(row.kind);
            case "system" -> Set.of("outage", "recovery", "settings", "ai-action", "server-event", "security").contains(row.kind);
            case "all" -> true;
            default -> CASES.contains(row.kind) || Set.of("recovery", "settings", "ai-action", "deescalation", "server-event", "keyword-block", "abuse", "advertising", "whisper-block").contains(row.kind);
        }).filter(row -> player.isBlank() || row.player.equals(player)).filter(row -> matches(row, query)).limit(200).map(row -> {
            var view = visible(row, whispers, false);
            view.put("score", row.player.isBlank() ? 0 : score(row.player, true, now) + score(row.player, false, now));
            return view;
        }).toList();
        long day = now - 24 * 60 * MINUTE;
        var last24 = rows.stream().filter(row -> row.at > day).toList();
        var stats = SimplexAdminSuite.appMap(
            "blocked", last24.stream().filter(row -> Set.of("abuse", "advertising", "keyword-block", "whisper-block").contains(row.kind)).count(),
            "warnings", last24.stream().filter(row -> Set.of("warning", "keyword-warning", "reviewed").contains(row.kind)).count(),
            "mutes", last24.stream().filter(row -> row.kind.equals("mute")).count(),
            "conflicts", last24.stream().filter(row -> Set.of("conflict", "help").contains(row.kind)).count(),
            "deescalations", last24.stream().filter(row -> row.kind.equals("deescalation")).count(),
            "activeMutes", rows.stream().filter(row -> row.active(now)).count(),
            "players", last24.stream().map(Entry::player).filter(id -> !id.isBlank()).distinct().count());
        var result = SimplexAdminSuite.appMap("items", visible, "at", now, "open", rows.stream().filter(row -> CASES.contains(row.kind) && !row.resolved).count(),
            "recent", recent.stream().filter(row -> CASES.contains(row.kind) || Set.of("recovery", "ai-action", "deescalation", "server-event", "keyword-block").contains(row.kind)).count(), "mutes", recent.stream().filter(row -> row.kind.equals("mute")).count(),
            "status", !enabled() ? "off" : !configured() ? "unconfigured" : now < bypassUntil ? "bypass" : "active",
            "manage", manage, "canSettings", settings, "flags", flags, "guidelines", settings ? policy : "", "stats", stats,
            "strictness", strictness, "whispersVisible", whispers);
        if (settings) {
            result.put("systemPrompt", moderationPrompt);
            result.put("defaultSystemPrompt", DEFAULT_MODERATION_PROMPT);
            result.put("assistantPrompt", assistantPrompt);
            result.put("defaultAssistantPrompt", DEFAULT_ASSISTANT_PROMPT);
            result.put("conflictMuteMinutes", Math.max(1, Math.min(30, plugin.getConfig().getLong(ROOT + "conflict-mute-minutes", 5))));
        }
        return result;
    }

    /** One case with its full conversation, the other entries for that player and the staff note. */
    private synchronized Map<String, Object> detail(String id, boolean whispers) {
        Entry row = entries.get(id);
        if (row == null) throw new AppApiManager.ApiError(404, "Fall nicht gefunden");
        var view = visible(row, whispers, true);
        long now = System.currentTimeMillis();
        view.put("score", row.player.isBlank() ? 0 : score(row.player, true, now) + score(row.player, false, now));
        List<Map<String, Object>> related = new ArrayList<>();
        if (!row.player.isBlank()) {
            var list = new ArrayList<>(entries.values());
            Collections.reverse(list);
            list.stream().filter(other -> other.player.equals(row.player) && !other.id.equals(row.id)).limit(12)
                .forEach(other -> related.add(visible(other, whispers, false)));
        }
        view.put("related", related);
        view.put("muted", !row.player.isBlank() && muted(row.player));
        view.put("mutedUntil", row.player.isBlank() ? 0 : mutedUntil(row.player));
        return view;
    }

    /** Everyone the bot dealt with recently, with strikes and an active mute. */
    private synchronized Map<String, Object> players(String query) {
        long now = System.currentTimeMillis();
        Map<String, List<Entry>> byPlayer = new LinkedHashMap<>();
        var list = new ArrayList<>(entries.values());
        Collections.reverse(list);
        for (Entry row : list) {
            if (row.player.isBlank() || row.player.startsWith("discord:") || !PLAYER_KINDS.contains(row.kind)) continue;
            byPlayer.computeIfAbsent(row.player, key -> new ArrayList<>()).add(row);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var item : byPlayer.entrySet()) {
            var latest = item.getValue().get(0);
            String name = item.getValue().stream().map(Entry::name).filter(n -> !n.isBlank()).findFirst().orElse("");
            if (!query.isBlank() && !name.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))) continue;
            Map<String, Long> kinds = new TreeMap<>();
            for (Entry row : item.getValue()) kinds.merge(row.kind, 1L, Long::sum);
            rows.add(SimplexAdminSuite.appMap("player", item.getKey(), "name", name, "total", item.getValue().size(), "last", latest.at,
                "lastKind", latest.kind, "lastDetail", latest.evidence.reason().isBlank() ? latest.detail : latest.evidence.reason(),
                "kinds", kinds, "strikes", score(item.getKey(), true, now) + score(item.getKey(), false, now),
                "muted", muted(item.getKey()), "mutedUntil", mutedUntil(item.getKey()), "threshold", muteThreshold(),
                "online", online(item.getKey()) != null));
        }
        rows.sort(Comparator.comparing((Map<String, Object> row) -> !Boolean.TRUE.equals(row.get("muted")))
            .thenComparing(row -> -AiAdmin.number(row.get("last"))));
        return Map.of("items", rows.stream().limit(200).toList());
    }

    /** Recent PowerBot entries of one player for the profile timeline (message text follows whisper access). */
    synchronized List<Map<String, Object>> playerFlags(String player, boolean whispers) {
        var list = new ArrayList<>(entries.values());
        Collections.reverse(list);
        return list.stream().filter(row -> row.player.equals(player) && PLAYER_KINDS.contains(row.kind)).limit(60)
            .map(row -> visible(row, whispers, false)).toList();
    }

    /** Counts for the morning briefing. */
    synchronized Map<String, Object> briefing(long since) {
        var recent = entries.values().stream().filter(row -> row.at > since).toList();
        var notable = new ArrayList<>(recent);
        Collections.reverse(notable);
        return SimplexAdminSuite.appMap(
            "status", !enabled() ? "off" : !configured() ? "unconfigured" : System.currentTimeMillis() < bypassUntil ? "bypass" : "active",
            "blocked", recent.stream().filter(row -> Set.of("abuse", "advertising", "keyword-block", "whisper-block").contains(row.kind)).count(),
            "warnings", recent.stream().filter(row -> Set.of("warning", "keyword-warning", "reviewed").contains(row.kind)).count(),
            "mutes", recent.stream().filter(row -> row.kind.equals("mute")).count(),
            "conflicts", recent.stream().filter(row -> Set.of("conflict", "help").contains(row.kind)).count(),
            "deescalations", recent.stream().filter(row -> row.kind.equals("deescalation")).count(),
            "open", entries.values().stream().filter(row -> CASES.contains(row.kind) && !row.resolved).count(),
            "highlights", notable.stream().filter(row -> CASES.contains(row.kind) || Set.of("abuse", "keyword-block", "deescalation", "advertising").contains(row.kind))
                .limit(6).map(row -> SimplexAdminSuite.appMap("id", row.id, "kind", row.kind, "name", row.name, "at", row.at,
                    "detail", row.evidence.reason().isBlank() ? row.detail : row.evidence.reason(), "severity", row.evidence.severity())).toList());
    }

    private Map<String, Object> action(String actor, Map<String, Object> input, boolean canSettings) {
        String op = AiAdmin.text(input, "operation");
        switch (op) {
            case "settings" -> {
                if (!canSettings) throw new AppApiManager.ApiError(403, "PowerBot-Einstellungen benötigen Vollzugriff und Einstellungsrecht.");
                var next = AppApiManager.object(input.get("flags"));
                for (String key : FLAGS) if (next.get(key) instanceof Boolean value) plugin.getConfig().set(ROOT + key, value);
                if (input.get("guidelines") instanceof String text) plugin.getConfig().set(ROOT + "guidelines", clean(text, 2000));
                if (input.get("strictness") instanceof String level) {
                    if (!STRICTNESS.contains(level)) throw new IllegalArgumentException("Unbekannte Strenge");
                    plugin.getConfig().set(ROOT + "strictness", level);
                }
                // Saving the default text again keeps the setting empty so future default improvements apply.
                if (input.get("systemPrompt") instanceof String text)
                    plugin.getConfig().set(ROOT + "system-prompt", text.strip().equals(DEFAULT_MODERATION_PROMPT) ? "" : clean(text, 4000));
                if (input.get("assistantPrompt") instanceof String text)
                    plugin.getConfig().set(ROOT + "assistant-prompt", text.strip().equals(DEFAULT_ASSISTANT_PROMPT) ? "" : clean(text, 4000));
                if (input.get("conflictMuteMinutes") instanceof Number minutes)
                    plugin.getConfig().set(ROOT + "conflict-mute-minutes", Math.max(1, Math.min(30, minutes.intValue())));
                plugin.saveConfig();
                reload();
                record("settings", "", actor, "Staff", "PowerBot-Einstellungen gespeichert", 0, List.of());
                return Map.of("ok", true);
            }
            case "note" -> {
                synchronized (this) {
                    Entry row = entries.get(AiAdmin.text(input, "id"));
                    if (row == null) throw new AppApiManager.ApiError(404, "Eintrag fehlt");
                    persist(row.noted(clean(AiAdmin.text(input, "note"), 1000)));
                }
                plugin.suiteAudit(actor, "PowerBot · Notiz", AiAdmin.text(input, "id"));
                return Map.of("ok", true);
            }
            case "player-mute", "player-unmute", "player-reset" -> { return playerAction(actor, op, input); }
            default -> { }
        }
        Entry row;
        synchronized (this) {
            row = entries.get(AiAdmin.text(input, "id"));
            if (row == null || !CASES.contains(row.kind)) throw new AppApiManager.ApiError(404, "Fall fehlt");
            Entry updated = switch (op) {
                case "claim" -> {
                    if (row.resolved || !row.assignee.isBlank()) throw new AppApiManager.ApiError(409, "Fall wurde bereits übernommen oder abgeschlossen.");
                    yield row.update(actor, false, row.revokedAt);
                }
                case "resolve" -> {
                    if (row.resolved) throw new AppApiManager.ApiError(409, "Fall ist bereits abgeschlossen.");
                    yield row.update(row.assignee.isBlank() ? actor : row.assignee, true, row.revokedAt);
                }
                case "reopen" -> {
                    if (!row.resolved) throw new AppApiManager.ApiError(409, "Fall ist noch offen.");
                    yield row.update(row.assignee, false, row.revokedAt);
                }
                case "revoke" -> {
                    if (!row.active(System.currentTimeMillis())) throw new AppApiManager.ApiError(409, "AutoMod-Mute läuft nicht mehr.");
                    yield row.update(row.assignee, row.resolved, System.currentTimeMillis());
                }
                default -> throw new IllegalArgumentException("Unbekannte Fallaktion");
            };
            persist(updated);
        }
        if (op.equals("revoke")) liftNotice(row.targets);
        plugin.suiteAudit(actor, "PowerBot · " + op, row.id);
        return Map.of("ok", true);
    }

    private void liftNotice(List<String> targets) {
        for (String target : targets) {
            Player player = online(target);
            if (player != null && !muted(target)) player.sendMessage(plugin.playerText(player,
                "§a[PowerBot] Dein AutoMod-Mute wurde aufgehoben.", "§a[PowerBot] Your AutoMod mute has been lifted."));
        }
    }

    private Map<String, Object> playerAction(String actor, String op, Map<String, Object> input) {
        String identity = AiAdmin.text(input, "player");
        UUID id = uuid(identity);
        if (id == null) throw new IllegalArgumentException("Spieler fehlt");
        String name = clean(AiAdmin.text(input, "name"), 64);
        long now = System.currentTimeMillis();
        switch (op) {
            case "player-mute" -> {
                long minutes = Math.max(1, Math.min(1440, (long) AiAdmin.number(input.get("minutes"))));
                String reason = clean(AiAdmin.text(input, "reason"), 300);
                if (plugin.isStaffAccount(id)) throw new AppApiManager.ApiError(409, "Teammitglieder werden nicht durch PowerBot stummgeschaltet.");
                Entry mute;
                synchronized (this) {
                    if (muted(identity)) throw new AppApiManager.ApiError(409, "Für diesen Spieler läuft bereits ein PowerBot-Mute.");
                    mute = record("mute", identity, name, "Staff", "Manuelle Chatpause durch " + clean(actor, 64) + " · " + minutes + " Minuten",
                        now + minutes * MINUTE, List.copyOf(muteTargets(identity)),
                        new Evidence("", reason.isBlank() ? "Vom Team gesetzt." : reason, "", "", List.of(), false));
                }
                announceMute(mute, minutes);
            }
            case "player-unmute" -> {
                List<String> lifted = new ArrayList<>();
                synchronized (this) {
                    for (Entry row : new ArrayList<>(entries.values()))
                        if (row.active(now) && row.targets.contains(identity)) { persist(row.update(row.assignee, row.resolved, now)); lifted.addAll(row.targets); }
                }
                if (lifted.isEmpty()) throw new AppApiManager.ApiError(409, "Kein aktiver PowerBot-Mute.");
                liftNotice(lifted);
            }
            default -> record("pardon", identity, name, "Staff", "Verwarnungszähler zurückgesetzt durch " + clean(actor, 64), 0, List.of());
        }
        plugin.suiteAudit(actor, "PowerBot · " + op, (name.isBlank() ? identity : name));
        return Map.of("ok", true);
    }

    private Map<String, Object> homeEvents(boolean general, boolean moderation) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (general && plugin.appEvents != null) {
            var log = AppApiManager.object(plugin.appEvents.data("", 0));
            if (log.get("items") instanceof List<?> rows) for (Object value : rows.stream().limit(5).toList()) {
                var row = AppApiManager.object(value);
                result.add(SimplexAdminSuite.appMap("id", "events:" + row.get("id"), "title", row.get("title"),
                    "at", row.get("time"), "critical", row.get("critical"), "source", "events"));
            }
        }
        if (moderation) synchronized (this) {
            entries.values().stream()
                .filter(row -> CASES.contains(row.kind) || Set.of("ai-action", "deescalation", "keyword-block").contains(row.kind))
                .sorted(Comparator.comparingLong(Entry::at).reversed()).limit(5)
                .forEach(row -> result.add(SimplexAdminSuite.appMap("id", "powerbot:" + row.id, "kind", row.kind, "at", row.at,
                    "title", row.name, "critical", Set.of("outage", "mute").contains(row.kind), "source", "powerbot", "case", row.id)));
        }
        result.sort(Comparator.comparingLong((Map<String, Object> row) -> AiAdmin.number(row.get("at"))).reversed());
        return Map.of("items", result.stream().limit(5).toList());
    }

    boolean route(AppApiManager api, HttpExchange exchange, AppApiSecurity.Key key, String path, String method, long revision) throws Exception {
        if (path.equals("/v1/home-events")) {
            if (!method.equals("GET")) throw new AppApiManager.ApiError(405, "Methode nicht erlaubt");
            boolean general = api.allows(key, "data.events"), moderation = api.allows(key, VIEW);
            if (!general && !moderation) throw new AppApiManager.ApiError(403, "Keine Berechtigung für Startseiten-Ereignisse.");
            AppApiManager.send(exchange, 200, homeEvents(general, moderation));
            return true;
        }
        if (path.equals("/v1/briefing")) {
            if (!method.equals("GET")) throw new AppApiManager.ApiError(405, "Methode nicht erlaubt");
            var query = AppApiManager.query(exchange.getRequestURI());
            long now = System.currentTimeMillis();
            long since = Math.max(now - 7L * 24 * 60 * MINUTE, Math.min(now, parseLong(query.getOrDefault("since", "0"))));
            AppApiManager.send(exchange, 200, api.onMain(revision, () -> SuiteBriefing.build(plugin, api, key, since)));
            return true;
        }
        if (path.equals("/v1/player/timeline")) {
            if (!method.equals("GET")) throw new AppApiManager.ApiError(405, "Methode nicht erlaubt");
            api.requirePermission(key, "data.profile");
            var query = AppApiManager.query(exchange.getRequestURI());
            boolean publicChat = api.allows(key, "data.chat"), whispers = api.allows(key, PlayerChatTimeline.PRIVATE_PERMISSION);
            boolean flags = api.allows(key, VIEW);
            AppApiManager.send(exchange, 200, api.onMain(revision, () -> plugin.appPlayerTimeline(query.getOrDefault("player", ""), publicChat, whispers, flags)));
            return true;
        }
        boolean keywordRoute = path.equals("/v1/powerbot/keywords");
        if (!keywordRoute && !path.equals("/v1/powerbot") && !path.equals("/v1/powerbot/case") && !path.equals("/v1/powerbot/players")) return false;
        api.requirePermission(key, VIEW);
        boolean manage = api.allows(key, MANAGE) && AppApiSecurity.permitted(key, "manage");
        boolean settings = manage && key.scope().equals("full") && api.allows(key, SETTINGS);
        boolean whispers = api.allows(key, PlayerChatTimeline.PRIVATE_PERMISSION);
        if (path.equals("/v1/powerbot/case") || path.equals("/v1/powerbot/players")) {
            if (!method.equals("GET")) throw new AppApiManager.ApiError(405, "Methode nicht erlaubt");
            var query = AppApiManager.query(exchange.getRequestURI());
            if (path.endsWith("/case")) {
                var view = detail(query.getOrDefault("id", ""), whispers);
                view.put("manage", manage);
                AppApiManager.send(exchange, 200, view);
            } else AppApiManager.send(exchange, 200, players(query.getOrDefault("q", "")));
            return true;
        }
        if (keywordRoute) {
            if (!settings) throw new AppApiManager.ApiError(403, "Stichwortregeln benötigen Vollzugriff und Einstellungsrecht.");
            if (method.equals("GET")) {
                AppApiManager.send(exchange, 200, Map.of("items", keywords.rows()));
            } else if (method.equals("POST")) {
                var body = AppApiManager.body(exchange, 8192);
                if (!Boolean.TRUE.equals(body.get("confirmed"))) throw new IllegalArgumentException("Bestätigung fehlt");
                AppApiManager.send(exchange, 200, api.onMain(revision, () -> {
                    api.requirePermission(key, VIEW);
                    api.requirePermission(key, MANAGE);
                    api.requirePermission(key, SETTINGS);
                    String operation = AiAdmin.text(body, "operation");
                    String word = keywords.change(operation, AiAdmin.text(body, "id"), AiAdmin.text(body, "keyword"), AiAdmin.text(body, "instructions"));
                    plugin.suiteAudit(key.label(), operation.equals("delete") ? "PowerBot-Stichwort gelöscht" : "PowerBot-Stichwort gespeichert", clean(word, 64));
                    return Map.of("items", keywords.rows());
                }));
            } else throw new AppApiManager.ApiError(405, "Methode nicht erlaubt");
            return true;
        }
        if (method.equals("GET")) {
            var query = AppApiManager.query(exchange.getRequestURI());
            long after = parseLong(query.getOrDefault("after", "0"));
            AppApiManager.send(exchange, 200, data(manage, settings, whispers, after, query.getOrDefault("filter", "important"),
                clean(query.getOrDefault("q", ""), 80), clean(query.getOrDefault("player", ""), 64)));
        } else if (method.equals("POST")) {
            if (!manage) throw new AppApiManager.ApiError(403, "PowerBot-Verwaltung benötigt manage-Zugriff");
            var body = AppApiManager.body(exchange, 32768);
            if (!Boolean.TRUE.equals(body.get("confirmed"))) throw new IllegalArgumentException("Bestätigung fehlt");
            AppApiManager.send(exchange, 200, api.onMain(revision, () -> {
                api.requirePermission(key, VIEW);
                api.requirePermission(key, MANAGE);
                return action(key.label(), body, settings && api.allows(key, SETTINGS));
            }));
        } else throw new AppApiManager.ApiError(405, "Methode nicht erlaubt");
        return true;
    }

    private static long parseLong(String value) {
        try { return Long.parseLong(value.strip()); } catch (NumberFormatException invalid) { return 0; }
    }

    boolean command(CommandSender sender, String[] args) {
        if (!(sender instanceof ConsoleCommandSender) && (!(sender instanceof Player player)
            || !plugin.adminSessionUsable(player) || !plugin.has(player, "simplexadminsuite.powerbot.manage"))) {
            sender.sendMessage("PowerBot: Zugriff fehlt.");
            return true;
        }
        try {
            if (args.length == 3 && Set.of("claim", "resolve", "revoke", "reopen").contains(args[1])) {
                action(sender.getName(), Map.of("operation", args[1], "id", args[2]), false);
                sender.sendMessage("§aPowerBot-Fall aktualisiert.");
                return true;
            }
            var data = data(false, false, false, 0, "important", "", "");
            sender.sendMessage("§bPowerBot §7· " + data.get("status") + " · " + data.get("open") + " offene Fälle · Strenge " + strictness);
            @SuppressWarnings("unchecked") var rows = (List<Map<String, Object>>) data.get("items");
            rows.stream().limit(6).forEach(row -> sender.sendMessage(row.get("kind") + " · " + row.get("name") + " · " + row.get("id")));
            sender.sendMessage("/sas powerbot <claim|resolve|reopen|revoke> <Fall-ID>");
        } catch (IllegalArgumentException | IllegalStateException | AppApiManager.ApiError error) { sender.sendMessage("PowerBot: " + error.getMessage()); }
        return true;
    }

    @Override public void close() { reviews.shutdownNow(); }
}
