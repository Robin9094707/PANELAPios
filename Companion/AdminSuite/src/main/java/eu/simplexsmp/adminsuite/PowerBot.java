package eu.simplexsmp.adminsuite;

import com.sun.net.httpserver.HttpExchange;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/** Chat decisions, separate reversible sanctions and the staff handoff queue. */
final class PowerBot implements AutoCloseable {
    static final String FEATURE = "powerbot-v1", VIEW = "data.powerbot", MANAGE = "powerbot.manage", SETTINGS = "powerbot.settings";
    private static final String ROOT = "powerbot.";
    private static final List<String> FLAGS = List.of("enabled", "moderation", "whispers", "advertising", "auto-mute", "linked-mutes",
        "public-chat", "proactive", "discord", "notify-staff", "security-hints", "staff-device-context", "critical-alerts");
    private static final Set<String> CASES = Set.of("mute", "conflict", "help", "security", "outage");
    private static final long MINUTE = 60_000L, WINDOW = 20 * MINUTE;
    private static final Pattern PROMOTION = Pattern.compile("(?iu)\\b(?:join (?:my|our) (?:minecraft )?server|(?:kom[mmt]+|joint|kommt|spiel(?:t)?) (?:auf |bei )?(?:meinen|unseren|unserem) (?:minecraft[- ]?)?server|(?:my|our|mein|unser) (?:minecraft[- ]?)?server\\s*(?:ip|address|adresse))\\b");
    private static final Pattern DESTINATION = Pattern.compile("(?i)(?<![a-z0-9-])(?:[a-z0-9](?:[a-z0-9-]{0,60}[a-z0-9])?\\.)+[a-z]{2,24}\\b|\\b(?:[0-9]{1,3}\\.){3}[0-9]{1,3}\\b");
    private final SimplexAdminSuite plugin;
    private final Path file;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
    private final ThreadPoolExecutor reviews = new ThreadPoolExecutor(3, 3, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(12), task -> {
        Thread thread = new Thread(task, "Simplex-PowerBot");
        thread.setDaemon(true);
        return thread;
    });
    private volatile Map<String, Boolean> flags;
    private volatile String policy;
    private volatile long bypassUntil;
    private long lastFailure;
    private boolean bypassAnnounced;

    private record Entry(String id, long at, String kind, String player, String name, String source, String detail, long until,
                         List<String> targets, String assignee, boolean resolved, long revokedAt) {
        Map<String, Object> row() {
            return SimplexAdminSuite.appMap("id", id, "at", at, "kind", kind, "player", player, "name", name, "source", source,
                "detail", detail, "until", until, "targets", targets, "assignee", assignee, "resolved", resolved, "revokedAt", revokedAt,
                "active", active(System.currentTimeMillis()), "case", CASES.contains(kind));
        }
        boolean active(long now) { return kind.equals("mute") && until > now && revokedAt == 0; }
        Entry update(String owner, boolean closed, long revoked) {
            return new Entry(id, at, kind, player, name, source, detail, until, targets, owner, closed, revoked);
        }
    }

    PowerBot(SimplexAdminSuite plugin) throws IOException {
        this.plugin = plugin;
        file = plugin.getDataFolder().toPath().resolve("powerbot-actions.json");
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
                        targets, AiAdmin.text(row, "assignee"), Boolean.TRUE.equals(row.get("resolved")), AiAdmin.number(row.get("revokedAt"))));
                }
            } catch (RuntimeException e) { throw new IOException("AutoMod-Protokoll konnte nicht gelesen werden", e); }
        }
        reload();
    }

    void reload() {
        Map<String, Boolean> next = new LinkedHashMap<>();
        for (String key : FLAGS) next.put(key, plugin.getConfig().getBoolean(ROOT + key, !key.equals("critical-alerts")));
        flags = Map.copyOf(next);
        policy = clean(plugin.getConfig().getString(ROOT + "guidelines", ""), 500);
    }

    boolean enabled() { return on("enabled"); }
    boolean on(String key) { return flags.getOrDefault(key, false); }
    boolean managed(String source) { return enabled() && (!source.equals("Discord") || on("discord")); }
    boolean canStaffDeviceContext() { return on("staff-device-context"); }
    boolean chatReady() { return configured() && System.currentTimeMillis() >= bypassUntil; }
    private boolean configured() { return plugin.openAiIntegration() != null && plugin.openAiIntegration().configured(); }

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
        Entry row = new Entry(UUID.randomUUID().toString(), System.currentTimeMillis(), kind, player, clean(name, 64), source,
            clean(detail, 500), until, List.copyOf(targets), "", false, 0);
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

    boolean bypass(Player player) {
        return plugin.has(player, "simplexadminsuite.admin") || plugin.has(player, "simplexadminsuite.powerbot.bypass");
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
            applyReview(identity, name, source, "advertising");
            return OpenAiIntegration.ChatDecision.block(german ? "Eigenwerbung für fremde Server ist hier nicht erlaubt."
                : "Advertising another server is not allowed here.", "AutoMod: advertising");
        }
        return OpenAiIntegration.ChatDecision.allow();
    }

    OpenAiIntegration.ChatDecision reviewModel(String identity, String name, String source, String text, boolean german, boolean onMain) {
        if (!managed(source) || text.isBlank() || !on("moderation") || !chatReady()) return OpenAiIntegration.ChatDecision.allow();
        if (source.equals("Whisper") && !on("whispers")) return OpenAiIntegration.ChatDecision.allow();
        boolean whisper = source.equals("Whisper");
        String sample = clean(text, 450);
        String instructions = policy;
        if (onMain) {
            // Commands run on the server tick. Review in the background and apply future mutes without replaying commands.
            try {
                reviews.execute(() -> {
                    try {
                        var decision = plugin.openAiIntegration().powerBotReview(sample, instructions, whisper);
                        if (chatReady()) recovered();
                        if (!decision.allowed && managed(source) && on("moderation")) {
                            applyReview(identity, name, source, "reviewed");
                            plugin.runAdminTask(() -> {
                                Player target = Bukkit.getPlayer(UUID.fromString(identity));
                                if (target != null) target.sendMessage(plugin.playerText(target,
                                    "§e[PowerBot] Bitte bleib respektvoll. Weitere bestätigte Verstöße können zu einem Chatmute führen.",
                                    "§e[PowerBot] Please stay respectful. Further confirmed violations may lead to a chat mute."));
                            });
                        }
                    } catch (Exception error) { failed(); }
                });
            } catch (RejectedExecutionException busy) { failed(); }
            return OpenAiIntegration.ChatDecision.allow();
        }
        Future<OpenAiIntegration.ChatDecision> work = null;
        try {
            work = reviews.submit(() -> plugin.openAiIntegration().powerBotReview(sample, instructions, false));
            var decision = work.get(6500, TimeUnit.MILLISECONDS);
            if (chatReady()) recovered();
            if (!managed(source) || !on("moderation")) return OpenAiIntegration.ChatDecision.allow();
            if (!decision.allowed) {
                applyReview(identity, name, source, "abuse");
                return OpenAiIntegration.ChatDecision.block(german ? "Deine Nachricht wurde wegen eines bestätigten Chatverstoßes zurückgehalten."
                    : "Your message was held for a confirmed chat violation.", "AutoMod: confirmed violation");
            }
            if (decision.warning) {
                record("warning", identity, name, source, "Kontextprüfung: Hinweis an Spieler", 0, List.of());
                return OpenAiIntegration.ChatDecision.warn(german ? "Bitte bleib im Chat respektvoll." : "Please keep chat respectful.", "AutoMod warning");
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

    private void applyReview(String identity, String name, String source, String category) {
        try {
            record(category, identity, name, source, switch (category) {
                case "advertising" -> "Eigenwerbung zurückgehalten und Hinweis ausgegeben.";
                case "reviewed" -> "Nachricht im Hintergrund geprüft. Spielerhinweis ausgelöst.";
                default -> "Nachricht nach Moderationssignal und Kontextprüfung zurückgehalten.";
            }, 0, List.of());
            if (on("auto-mute") && !identity.isBlank()) plugin.runAdminTask(() -> evaluateMute(identity, name, source, category));
        } catch (RuntimeException error) { plugin.getLogger().warning("PowerBot: Aktionsprotokoll konnte nicht gespeichert werden."); }
    }

    private void evaluateMute(String identity, String name, String source, String category) {
        UUID id;
        try { id = identity.startsWith("discord:") ? null : UUID.fromString(identity); } catch (IllegalArgumentException invalid) { return; }
        Set<String> targets = new LinkedHashSet<>();
        targets.add(identity);
        if (id != null && on("linked-mutes")) for (UUID alt : plugin.confirmedAlts(id)) {
            var profile = plugin.expansionProfile(alt);
            if (profile != null && !plugin.isStaffAccount(alt)) targets.add(alt.toString());
        }
        Entry mute;
        synchronized (this) {
            if (!enabled() || !on("auto-mute") || muted(identity) || plugin.isStaffAccount(id)) return;
            int hits = score(identity, category.equals("advertising"), System.currentTimeMillis());
            if (hits < 3) return;
            try { mute = record("mute", identity, name, source, "Drei bestätigte Versuche in 20 Minuten · " +
                (category.equals("advertising") ? "Eigenwerbung" : "Chatverstoß"), System.currentTimeMillis() + 10 * MINUTE, List.copyOf(targets)); }
            catch (IllegalStateException failure) { plugin.getLogger().warning("PowerBot: Chatmute ohne gesicherten Verlauf abgebrochen."); return; }
        }
        for (String target : mute.targets) {
            Player player = online(target);
            if (player != null) player.sendMessage(plugin.playerText(player, "§c[PowerBot] Chatmute für 10 Minuten wegen wiederholter Verstöße.",
                "§c[PowerBot] Chat muted for 10 minutes after repeated violations."));
        }
        notifyStaff(mute, "AutoMod-Mute", on("critical-alerts"));
    }

    private static Player online(String identity) {
        return identity.startsWith("discord:") ? null : Bukkit.getPlayer(UUID.fromString(identity));
    }

    private int score(String identity, boolean ads, long now) {
        int hits = 0;
        long last = 0;
        long cutoff = now - WINDOW;
        for (Entry row : entries.values()) if (row.player.equals(identity) && row.kind.equals("mute")) cutoff = Math.max(cutoff, row.at);
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
            important("outage", "", "", "System", "OpenAI antwortete nicht rechtzeitig. Nachrichten werden ohne KI-Prüfung zugestellt.", false);
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
        try {
            Entry entry = record(kind, player, name, source, detail, 0, List.of());
            if (alert) notifyStaff(entry, kind.equals("help") ? "Hilferuf im Chat" : "Chat-Konflikt erkannt", on("critical-alerts"));
        } catch (IllegalStateException failure) { plugin.getLogger().warning("PowerBot: Hinweis konnte nicht protokolliert werden."); }
    }

    private void notifyStaff(Entry row, String title, boolean critical) {
        if (on("notify-staff") && plugin.appEvents != null) {
            plugin.appEvents.powerBotAlert(title, row.name.isBlank() ? "Fallzentrale öffnen" : row.name + " · Fallzentrale öffnen", critical);
        }
    }

    private synchronized Map<String, Object> data(boolean manage, boolean settings, long after, String filter) {
        var rows = new ArrayList<>(entries.values());
        Collections.reverse(rows);
        long now = System.currentTimeMillis(), cutoff = Math.max(now - 30L * 24 * 60 * MINUTE, after);
        var recent = rows.stream().filter(row -> row.at > cutoff).toList();
        var visible = recent.stream().filter(row -> switch (filter) {
            case "open" -> CASES.contains(row.kind) && !row.resolved;
            case "mutes" -> row.kind.equals("mute");
            case "all" -> true;
            default -> CASES.contains(row.kind) || Set.of("recovery", "settings", "ai-action", "deescalation", "server-event").contains(row.kind);
        }).limit(150).map(row -> {
            var view = row.row();
            if (row.player.startsWith("discord:")) view.put("player", "");
            view.remove("targets");
            view.put("targetCount", row.targets.size());
            view.put("score", row.player.isBlank() ? 0 : score(row.player, true, now) + score(row.player, false, now));
            return view;
        }).toList();
        return SimplexAdminSuite.appMap("items", visible, "at", now, "open", rows.stream().filter(row -> CASES.contains(row.kind) && !row.resolved).count(),
            "recent", recent.stream().filter(row -> CASES.contains(row.kind) || Set.of("recovery", "ai-action", "deescalation", "server-event").contains(row.kind)).count(), "mutes", recent.stream().filter(row -> row.kind.equals("mute")).count(),
            "status", !enabled() ? "off" : !configured() ? "unconfigured" : now < bypassUntil ? "bypass" : "active",
            "manage", manage, "canSettings", settings, "flags", flags, "guidelines", settings ? policy : "");
    }

    private synchronized Map<String, Object> action(String actor, Map<String, Object> input, boolean canSettings) {
        String op = AiAdmin.text(input, "operation");
        if (op.equals("settings")) {
            if (!canSettings) throw new AppApiManager.ApiError(403, "PowerBot-Einstellungen benötigen Vollzugriff und Einstellungsrecht.");
            var next = AppApiManager.object(input.get("flags"));
            for (String key : FLAGS) if (next.get(key) instanceof Boolean value) plugin.getConfig().set(ROOT + key, value);
            if (input.get("guidelines") instanceof String text) plugin.getConfig().set(ROOT + "guidelines", clean(text, 500));
            plugin.saveConfig();
            reload();
            record("settings", "", actor, "Staff", "PowerBot-Einstellungen gespeichert", 0, List.of());
        } else {
            Entry row = entries.get(AiAdmin.text(input, "id"));
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
                case "revoke" -> {
                    if (!row.active(System.currentTimeMillis())) throw new AppApiManager.ApiError(409, "AutoMod-Mute läuft nicht mehr.");
                    yield row.update(row.assignee, row.resolved, System.currentTimeMillis());
                }
                default -> throw new IllegalArgumentException("Unbekannte Fallaktion");
            };
            persist(updated);
            if (op.equals("revoke")) for (String target : row.targets) {
                Player player = online(target);
                if (player != null && !muted(target)) player.sendMessage(plugin.playerText(player,
                    "§a[PowerBot] Dein AutoMod-Mute wurde aufgehoben.", "§a[PowerBot] Your AutoMod mute has been lifted."));
            }
            plugin.suiteAudit(actor, "PowerBot · " + op, row.id);
        }
        return Map.of("ok", true);
    }

    boolean route(AppApiManager api, HttpExchange exchange, AppApiSecurity.Key key, String path, String method, long revision) throws Exception {
        if (!path.equals("/v1/powerbot")) return false;
        api.requirePermission(key, VIEW);
        boolean manage = api.allows(key, MANAGE) && AppApiSecurity.permitted(key, "manage");
        boolean settings = manage && key.scope().equals("full") && api.allows(key, SETTINGS);
        if (method.equals("GET")) {
            var query = AppApiManager.query(exchange.getRequestURI());
            long after = Long.parseLong(query.getOrDefault("after", "0"));
            AppApiManager.send(exchange, 200, data(manage, settings, after, query.getOrDefault("filter", "important")));
        } else if (method.equals("POST")) {
            if (!manage) throw new AppApiManager.ApiError(403, "PowerBot-Verwaltung benötigt manage-Zugriff");
            var body = AppApiManager.body(exchange, 8192);
            if (!Boolean.TRUE.equals(body.get("confirmed"))) throw new IllegalArgumentException("Bestätigung fehlt");
            AppApiManager.send(exchange, 200, api.onMain(revision, () -> {
                api.requirePermission(key, VIEW);
                api.requirePermission(key, MANAGE);
                return action(key.label(), body, settings && api.allows(key, SETTINGS));
            }));
        } else throw new AppApiManager.ApiError(405, "Methode nicht erlaubt");
        return true;
    }

    boolean command(CommandSender sender, String[] args) {
        if (sender instanceof Player player && (!plugin.adminSessionUsable(player) || !plugin.has(player, "simplexadminsuite.powerbot.manage"))) {
            sender.sendMessage("PowerBot: Zugriff fehlt.");
            return true;
        }
        try {
            if (args.length == 3 && Set.of("claim", "resolve", "revoke").contains(args[1])) {
                action(sender.getName(), Map.of("operation", args[1], "id", args[2]), false);
                sender.sendMessage("§aPowerBot-Fall aktualisiert.");
                return true;
            }
            var data = data(false, false, 0, "important");
            sender.sendMessage("§bPowerBot §7· " + data.get("status") + " · " + data.get("open") + " offene Fälle");
            @SuppressWarnings("unchecked") var rows = (List<Map<String, Object>>) data.get("items");
            rows.stream().limit(6).forEach(row -> sender.sendMessage(row.get("kind") + " · " + row.get("name") + " · " + row.get("id")));
            sender.sendMessage("/sas powerbot <claim|resolve|revoke> <Fall-ID>");
        } catch (IllegalArgumentException | IllegalStateException | AppApiManager.ApiError error) { sender.sendMessage("PowerBot: " + error.getMessage()); }
        return true;
    }

    @Override public void close() { reviews.shutdownNow(); }
}
