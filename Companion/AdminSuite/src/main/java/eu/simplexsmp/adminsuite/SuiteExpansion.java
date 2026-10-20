package eu.simplexsmp.adminsuite;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class SuiteExpansion {
    static final int CURRENT_CONFIG_VERSION = 56;
    static final int CURRENT_SOURCE_CONFIG_VERSION = 54;

    private final SimplexAdminSuite plugin;
    private final RulesManager rules;
    private final KeywordFilterManager filters;
    private final PollManager polls;
    private final PointsManager points;
    private final HistoricProfileCounter historicProfiles;
    private final Map<UUID, PendingText> pendingText = new ConcurrentHashMap<>();
    private final Map<UUID, Set<Integer>> voteDrafts = new ConcurrentHashMap<>();

    static void migrateBeforeLoad(SimplexAdminSuite plugin) {
        if (plugin == null) return;
        int previous = Math.max(0, plugin.getConfig().getInt("config-version", 0));
        if (!supportsMigrationFrom(previous)) {
            throw new IllegalStateException("Simplex Admin Suite 8.1 requires config-version 54 (7.18.3+), 55 or 56. Found: " + previous + ". Existing files are not modified.");
        }
        // 8.0 appends defaults to the 7.18.3 baseline. Historic player readers remain intact.
        // Removed in 7.18.3: do not retain the former automatic chat-name option.
        plugin.getConfig().set("nameplates.manage-display-name", null);
        if(!plugin.getConfig().getBoolean("qol-719.logging-migrated",false)){plugin.getConfig().set("nameplates.log-conflicts",false);plugin.getConfig().set("client-security.probes.diagnostics.console",false);plugin.getConfig().set("client-security.probes.console-summary",true);plugin.getConfig().set("qol-719.logging-migrated",true);}
        plugin.getConfig().options().copyDefaults(true);
        plugin.getConfig().set("config-version", CURRENT_CONFIG_VERSION);
        plugin.getConfig().set("storage.schema-version", 17);
        plugin.saveConfig();
    }

    static boolean supportsMigrationFrom(int configVersion) {
        return configVersion == CURRENT_SOURCE_CONFIG_VERSION || configVersion == 55 || configVersion == CURRENT_CONFIG_VERSION;
    }

    SuiteExpansion(SimplexAdminSuite plugin) {
        this.plugin = plugin;
        this.rules = new RulesManager(plugin);
        this.filters = new KeywordFilterManager(plugin);
        this.polls = new PollManager(plugin);
        this.points = new PointsManager(plugin);
        this.historicProfiles = new HistoricProfileCounter(plugin);
    }

    void start() {
        rules.start();
        polls.start();
        points.start();
        historicProfiles.scanAsync();
    }

    void stop() {
        polls.stop();
        points.stop();
        rules.stop();
        historicProfiles.stop();
        pendingText.clear();
        voteDrafts.clear();
    }

    void reload() {
        stop();
        rules.reload();
        filters.reload();
        polls.reload();
        points.reload();
    }

    void join(Player player) {
        if (player == null) return;
        historicProfiles.record(player.getUniqueId());
        points.ensure(player);
        rules.join(player);
    }

    void quit(Player player) {
        if (player == null) return;
        pendingText.remove(player.getUniqueId());
        voteDrafts.remove(player.getUniqueId());
        rules.quit(player);
    }

    boolean handlePublicCommand(String root, CommandSender sender, String[] args) {
        if (root.equals("rules")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(plugin.prefix() + "Server rules v" + rules.version() + ": " + String.join(" | ", rules.rules()));
                return true;
            }
            Player player = (Player) sender;
            if (!plugin.openBedrockRules(player, false)) openRulesJava(player);
            return true;
        }
        if (root.equals("vote")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(plugin.prefix() + (polls.activeView(null) == null ? "There is no active server vote." : polls.summaryEnglish()));
                return true;
            }
            Player player = (Player) sender;
            if (!plugin.openBedrockVote(player)) openVoteJava(player);
            return true;
        }
        if (root.equals("points")) {
            if (!plugin.has(sender, "simplexadminsuite.points.use")) {
                sender.sendMessage(plugin.publicUiText(sender,plugin.prefix() + "You do not have permission to view Simplex Points."));
                return true;
            }
            if (args.length > 0 && (args[0].equalsIgnoreCase("top") || args[0].equalsIgnoreCase("leaderboard"))) {
                sendPointsLeaderboard(sender);
                return true;
            }
            if (!(sender instanceof Player)) {
                sendPointsLeaderboard(sender);
                return true;
            }
            sendPoints((Player) sender);
            return true;
        }
        return false;
    }

    boolean handleAdminCommand(CommandSender sender, String sub, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(plugin.prefix() + adminConsoleSummary(sub));
            return true;
        }
        Player admin = (Player) sender;
        if (sub.equals("regeln") || sub.equals("rules")) {
            if (!plugin.has(admin, "simplexadminsuite.rules.admin")) return deny(admin, "simplexadminsuite.rules.admin");
            if (!plugin.openBedrockRulesAdmin(admin)) openRulesAdminJava(admin);
            return true;
        }
        if (sub.equals("umfragen") || sub.equals("polls") || sub.equals("votes")) {
            if (!plugin.has(admin, "simplexadminsuite.polls.admin")) return deny(admin, "simplexadminsuite.polls.admin");
            if (!plugin.openBedrockPollsAdmin(admin)) openPollsAdminJava(admin);
            return true;
        }
        if (sub.equals("punkte") || sub.equals("points") || sub.equals("simplexpoints")) {
            if (!plugin.has(admin, "simplexadminsuite.points.admin")) return deny(admin, "simplexadminsuite.points.admin");
            if (args.length >= 2 && (args[0].equalsIgnoreCase("set") || args[0].equalsIgnoreCase("reset"))) {
                SimplexAdminSuite.PlayerProfile target = plugin.clientSecurityFindProfile(args[1]);
                if (target == null) { plugin.menuNotice(admin,plugin.prefix() + "Spielerprofil nicht gefunden."); return true; }
                long value = 0L;
                if (args[0].equalsIgnoreCase("set")) {
                    if (args.length < 3) { admin.sendMessage(plugin.prefix() + "Nutze /sas points set <Spieler> <Punkte>."); return true; }
                    try { value = Long.parseLong(args[2]); } catch (Exception error) { value = -1L; }
                    if (value < 0L || value > 1_000_000_000L) { plugin.menuNotice(admin,plugin.prefix() + "Erlaubt sind 0 bis 1.000.000.000 Punkte."); return true; }
                }
                target.setSimplexPoints(value);
                plugin.saveExpansionProfile(target); plugin.refreshExpansionNameplate(Bukkit.getPlayer(target.uniqueId())); plugin.discordPointsChanged();
                plugin.expansionAudit(admin.getName(), target.uniqueId(), args[0].equalsIgnoreCase("set") ? "Simplex Points gesetzt" : "Simplex Points zurückgesetzt", Long.toString(value));
                admin.sendMessage(plugin.prefix() + "Kontostand von " + target.displayName() + " ist jetzt " + value + " SP.");
                return true;
            }
            if (!plugin.openBedrockPointsAdmin(admin)) openPointsAdminJava(admin);
            return true;
        }
        if (sub.equals("chatfilter") || sub.equals("keywords")) {
            if (!plugin.has(admin, "simplexadminsuite.antispam")) return deny(admin, "simplexadminsuite.antispam");
            if (!plugin.openBedrockKeywordAdmin(admin)) openKeywordAdminJava(admin);
            return true;
        }
        return true;
    }

    private String adminConsoleSummary(String sub) {
        if (sub.equals("regeln") || sub.equals("rules")) return "Regelversion " + rules.version() + ", " + rules.acceptanceCount() + " aktuelle Zustimmungen.";
        if (sub.equals("umfragen") || sub.equals("polls") || sub.equals("votes")) return polls.summaryGerman();
        if (sub.equals("punkte") || sub.equals("points") || sub.equals("simplexpoints")) return "Simplex Points: " + points.leaderboardPlain(10);
        return "Keyword-/Regex-Filter: " + filters.activeCount() + " aktiv.";
    }

    private boolean deny(Player player, String permission) {
        player.sendMessage(plugin.playerUiText(player, plugin.prefix() + "Dafür fehlt dir " + permission + "."));
        return true;
    }

    List<String> tabCompletePublic(String root, String[] args) {
        if (root.equals("points") && args.length == 1) return startsWith(List.of("top", "leaderboard"), args[0]);
        return Collections.emptyList();
    }

    List<String> tabCompleteAdmin(String sub, String[] args) { return Collections.emptyList(); }

    boolean promptRulesIfRequired(Player player) { return rules.promptIfRequired(player); }
    boolean rulesRequired(Player player) { return rules.needsAcceptance(player); }
    boolean rulesAcceptanceEnabled() { return rules.requireAcceptance(); }
    int rulesVersion() { return rules.version(); }
    int rulesAcceptanceCount() { return rules.acceptanceCount(); }
    List<String> rules() { return rules.rules(); }
    List<String> rulesFor(Player p) {List<String> de=plugin.getConfig().getStringList("player-languages.de.rules");return plugin.community!=null&&plugin.community.german(p)&&!de.isEmpty()?List.copyOf(de):rules.rules();}
    void remindRules(Player player) { rules.remind(player); }
    void acceptRules(Player player, int expectedVersion) { rules.accept(player, expectedVersion); }
    void setRulesAcceptanceRequired(String actor, boolean required) { rules.setRequireAcceptance(actor, required); }
    boolean updateRules(String actor, String input, boolean requireAcceptance) { return rules.update(actor, input, requireAcceptance); }
    void publishRulesVersion(String actor) { rules.publishNewVersion(actor); }

    boolean blockCommandUntilRules(Player player, String lower, SimplexAdminSuite.Canceller canceller) {
        if (!rules.needsAcceptance(player)) return false;
        String normalized = lower == null ? "" : lower.trim().toLowerCase(Locale.ROOT);
        String root = normalized.split("\\s+", 2)[0];
        int namespace = root.indexOf(':');
        if (namespace >= 0) root = "/" + root.substring(namespace + 1);
        if (Set.of("/rules", "/login", "/l", "/register", "/reg", "/changepassword", "/2fa", "/auth").contains(root)) return false;
        if (canceller != null) canceller.cancel(true);
        rules.remind(player);
        return true;
    }

    boolean blockInventoryUntilRules(Player player, Inventory inventory, SimplexAdminSuite.Canceller canceller) {
        if (!rules.needsAcceptance(player)) return false;
        if (isExpansionInventory(inventory) && ((ExpansionMenu) LiveMenus.holder(inventory)).type.startsWith("rules")) return false;
        if (canceller != null) canceller.cancel(true);
        rules.remind(player);
        return true;
    }

    boolean consumePendingChat(Player player, String message) {
        boolean[] handled={false};plugin.asAdmin(player==null?null:player.getName(),()->handled[0]=consumeAuditedPendingChat(player,message));return handled[0];
    }
    private boolean consumeAuditedPendingChat(Player player, String message) {
        if (player == null) return false;
        PendingText pending = pendingText.remove(player.getUniqueId());
        if (pending == null) return false;
        plugin.runAdminTask( () -> completePendingText(player, pending, message));
        return true;
    }

    private void completePendingText(Player admin, PendingText pending, String message) {
        String value = message == null ? "" : message.trim();
        boolean cancelled = value.equalsIgnoreCase("abbrechen") || value.equalsIgnoreCase("cancel");
        if (pending.type.equals("rules-edit")) {
            if (!cancelled) {
                boolean changed = rules.update(admin.getName(), value, rules.requireAcceptance());
                plugin.menuNotice(admin,plugin.prefix() + (changed ? "Die neue Regelversion wurde veröffentlicht." : "Die Regelliste wurde nicht geändert."));
            }
            openRulesAdminJava(admin);
            return;
        }
        if (pending.type.equals("poll-create")) {
            if (!cancelled) admin.sendMessage(plugin.prefix() + parsePollCreation(admin.getName(), value));
            openPollsAdminJava(admin);
            return;
        }
        if (pending.type.equals("filter-create")) {
            if (!cancelled) admin.sendMessage(plugin.prefix() + parseFilterCreation(admin.getName(), value));
            openKeywordAdminJava(admin);
        }
    }

    private String parsePollCreation(String actor, String value) {
        String[] parts = value.split("\\|\\|", -1);
        if (parts.length < 3) return "Ungültige Eingabe. Nutze: Frage || Option 1; Option 2 || 1m/1h/24h || Max-Auswahl || ändern ja/nein";
        long seconds = parseDurationSeconds(parts[2].trim());
        int max = parts.length > 3 ? parseInt(parts[3], 1) : 1;
        boolean change = parts.length > 4 && (parts[4].trim().equalsIgnoreCase("ja") || parts[4].trim().equalsIgnoreCase("yes") || parts[4].trim().equalsIgnoreCase("true"));
        return polls.create(actor, parts[0], parts[1].replace(';', '|'), seconds, max, change);
    }

    private String parseFilterCreation(String actor, String value) {
        String[] parts = value.split("\\|\\|", -1);
        if (parts.length < 2) return "Ungültige Eingabe. Nutze: Muster || keyword/regex || block/warn/mute/kick || englische Meldung";
        boolean regex = parts[1].trim().equalsIgnoreCase("regex");
        String action = parts.length > 2 ? normalizeAction(parts[2]) : "BLOCK";
        String message = parts.length > 3 ? parts[3] : "That message is not allowed on this server.";
        return filters.add(actor, parts[0], regex, action, message);
    }

    boolean filterChat(Player player, String message) {
        if (player == null || plugin.has(player, "simplexadminsuite.antispam.bypass")) return false;
        KeywordEntry match = filters.match(message);
        if (match == null) return false;
        plugin.runAdminTask( () -> {
            player.sendMessage(plugin.playerUiText(player, plugin.prefix() + match.publicMessage));
            plugin.applyKeywordPenalty(player, match.action, "Matched filter #" + match.id + " (" + match.type + ")");
        });
        return true;
    }

    int keywordFilterCount() { return filters.activeCount(); }
    List<KeywordView> keywordFilters() { return filters.views(); }
    KeywordView keywordFilter(long id) { return filters.view(id); }
    String addKeywordFilter(String actor, String pattern, boolean regex, String action, String message) { return filters.add(actor, pattern, regex, action, message); }
    void toggleKeywordFilter(String actor, long id) { filters.toggle(actor, id); }
    void deleteKeywordFilter(String actor, long id) { filters.delete(actor, id); }

    PollView activePoll() { return polls.activeView(null); }
    PollView activePoll(Player player) { return polls.activeView(player); }
    String latestPollResult() { return polls.latestResult(); }
    String latestPollDetails() { return polls.latestDetails(); }
    boolean castVote(Player player, String pollId, List<Integer> selections) { return polls.vote(player, pollId, selections); }
    String createPoll(String actor, String question, String options, long durationSeconds, int maxSelections, boolean allowChange) { return polls.create(actor, question, options, durationSeconds, maxSelections, allowChange); }
    void endPoll(String actor) { polls.finish(actor, false); }
    String pollSummaryGerman() { return polls.summaryGerman(); }

    long points(Player player) { return points.points(player); }
    String rankName(Player player) { return points.rankName(points(player)); }
    int pointsProfileCount() { return plugin.expansionProfiles().size(); }
    int knownProfileCount() { return historicProfiles.count(); }
    String discordLeaderboard() { return points.discordLeaderboard(10); }
    void updatePointsSettings(String actor, boolean enabled, int intervalMinutes, int pointsPerInterval, boolean showPoints, boolean showLuckPerms, boolean discordLeaderboard) {
        points.updateSettings(actor, enabled, intervalMinutes, pointsPerInterval, showPoints, showLuckPerms, discordLeaderboard);
    }

    boolean handleInventoryClick(InventoryClickEvent event) {
        if (event == null || !isExpansionInventory(event.getInventory()) || !(event.getWhoClicked() instanceof Player)) return false;
        event.setCancelled(true);
        Player player = (Player) event.getWhoClicked();
        ExpansionMenu menu = (ExpansionMenu) LiveMenus.holder(event.getInventory());
        if (rules.needsAcceptance(player) && !menu.type.startsWith("rules")) {
            rules.remind(player);
            return true;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getInventory().getSize()) return true;
        if (menu.type.equals("rules-public")) handleRulesPublicClick(player, menu, slot);
        else if (menu.type.equals("rules-confirm")) handleRulesConfirmClick(player, menu, slot);
        else if (menu.type.equals("rules-admin")) handleRulesAdminClick(player, slot);
        else if (menu.type.equals("poll-public")) handlePollPublicClick(player, menu, slot);
        else if (menu.type.equals("poll-admin")) handlePollAdminClick(player, slot);
        else if (menu.type.equals("points-admin")) handlePointsAdminClick(player, slot);
        else if (menu.type.equals("filter-list")) handleFilterListClick(player, menu, slot);
        else if (menu.type.equals("filter-entry")) handleFilterEntryClick(player, menu, slot);
        return true;
    }

    boolean isExpansionInventory(Inventory inventory) { return inventory != null && LiveMenus.holder(inventory) instanceof ExpansionMenu; }

    private void openRulesJava(Player player) {
        int shownVersion = rules.version();
        Inventory inv = LiveMenus.create(new ExpansionMenu("rules-public", 0, Integer.toString(shownVersion)), 54, plugin.color(plugin.playerUiText(player,"&8Server Rules")+" v" + shownVersion));
        plugin.fill(inv);
        List<String> list = rulesFor(player);
        for (int i = 0; i < Math.min(36, list.size()); i++) {
            inv.setItem(i, plugin.item(Material.BOOK, "&6Rule " + (i + 1), wrapLore("&f" + list.get(i), 42)));
        }
        inv.setItem(45, plugin.item(Material.WRITABLE_BOOK, "&eServer Rules", "&7Please read every rule carefully.", "&7Current version: &f" + rules.version()));
        if (rules.needsAcceptance(player)) inv.setItem(49, plugin.item(Material.LIME_DYE, "&aI have read all rules", "&7Continue to the final confirmation."));
        else inv.setItem(49, plugin.item(Material.BARRIER, "&aClose", "&7You accepted the current rules."));
        plugin.localizePublicInventory(player,inv);LiveMenus.show(player,inv);
    }

    private void openRulesConfirmJava(Player player, int shownVersion) {
        Inventory inv = LiveMenus.create(new ExpansionMenu("rules-confirm", 0, Integer.toString(shownVersion)), 27, plugin.color(plugin.playerUiText(player,"&8Confirm Server Rules")));
        plugin.fill(inv);
        inv.setItem(11, plugin.item(Material.LIME_DYE, "&aYes, I agree", "&7I have read and agree to", "&7server rules version &f" + shownVersion + "&7."));
        inv.setItem(15, plugin.item(Material.RED_DYE, "&cBack", "&7Read the rules again."));
        plugin.localizePublicInventory(player,inv);LiveMenus.show(player,inv);
    }

    private void handleRulesPublicClick(Player player, ExpansionMenu menu, int slot) {
        if (slot != 49) return;
        int shownVersion = parseInt(menu.data, 0);
        if (shownVersion != rules.version()) {
            player.sendMessage(plugin.playerUiText(player, plugin.prefix() + "The server rules changed while this menu was open. Please read the current version."));
            openRulesJava(player);
            return;
        }
        if (rules.needsAcceptance(player)) openRulesConfirmJava(player, shownVersion); else player.closeInventory();
    }

    private void handleRulesConfirmClick(Player player, ExpansionMenu menu, int slot) {
        int shownVersion = parseInt(menu.data, 0);
        if (slot == 11) rules.accept(player, shownVersion);
        else if (slot == 15) openRulesJava(player);
    }

    void openRulesAdminJava(Player admin) {
        if (!plugin.adminMenuAllowed(admin, "suiteexpansion.openrulesadminjava")) return;
        if (!plugin.has(admin, "simplexadminsuite.rules.admin")) { deny(admin, "simplexadminsuite.rules.admin"); return; }
        Inventory inv = LiveMenus.create(new ExpansionMenu("rules-admin", 0, ""), 45, plugin.color("&8Serverregeln"));
        plugin.fill(inv);
        inv.setItem(4, plugin.item(Material.BOOK, "&6Regelversion " + rules.version(), "&7Regeln: &f" + rules.rules().size(), "&7Aktuelle Zustimmungen: &f" + rules.acceptanceCount()));
        inv.setItem(10, plugin.item(Material.WRITABLE_BOOK, "&aRegeln bearbeiten", "&7Englische Regeln mit &f|| &7trennen.", "&7Veröffentlicht automatisch eine neue Version."));
        inv.setItem(12, plugin.item(rules.requireAcceptance() ? Material.LIME_DYE : Material.GRAY_DYE, "&eZustimmung erforderlich", "&7Status: " + (rules.requireAcceptance() ? "&aAktiv" : "&cInaktiv"), "&7Klick veröffentlicht eine neue Version."));
        inv.setItem(14, plugin.item(Material.REDSTONE_TORCH, "&cNeue Version erzwingen", "&7Unveränderte Regeln erneut bestätigen lassen."));
        inv.setItem(16, plugin.item(Material.MAP, "&bÖffentliche Vorschau", "&7Öffnet das englische Spielermenü."));
        inv.setItem(40, plugin.item(Material.BARRIER, "&cZurück", "&7Zurück zum Hauptmenü."));
        LiveMenus.show(admin,inv);
    }

    private void handleRulesAdminClick(Player admin, int slot) {
        if (!plugin.has(admin, "simplexadminsuite.rules.admin")) return;
        if (slot == 10) {
            pendingText.put(admin.getUniqueId(), new PendingText("rules-edit"));
            admin.closeInventory();
            admin.sendMessage(plugin.prefix() + "Schreibe die vollständigen englischen Regeln in eine Zeile und trenne sie mit ||. 'abbrechen' beendet die Eingabe.");
        } else if (slot == 12) { rules.setRequireAcceptance(admin.getName(), !rules.requireAcceptance()); openRulesAdminJava(admin); }
        else if (slot == 14) { rules.publishNewVersion(admin.getName()); openRulesAdminJava(admin); }
        else if (slot == 16) openRulesJava(admin);
        else if (slot == 40) plugin.openJavaAdminMain(admin);
    }

    private void openVoteJava(Player player) {
        PollView poll = polls.activeView(player);
        if (poll == null) {
            player.sendMessage(plugin.playerUiText(player, plugin.prefix() + "There is no active server vote right now. Latest result: " + polls.latestResult()));
            return;
        }
        Set<Integer> draft = voteDrafts.computeIfAbsent(player.getUniqueId(), ignored -> new LinkedHashSet<>(poll.selected));
        Inventory inv = LiveMenus.create(new ExpansionMenu("poll-public", 0, poll.id), 54, plugin.color(plugin.playerUiText(player,"&8Server Vote")));
        plugin.fill(inv);
        inv.setItem(4, plugin.item(Material.MAP, "&9" + poll.question, "&7Ends in: &f" + poll.remaining, "&7Players voted: &f" + poll.voterCount, "&7Choose up to &f" + poll.maxSelections + "&7 option(s)."));
        int[] slots = {10, 12, 14, 16, 28, 30, 32, 34};
        for (int i = 0; i < Math.min(slots.length, poll.options.size()); i++) {
            boolean selected = draft.contains(i);
            inv.setItem(slots[i], plugin.item(selected ? Material.LIME_DYE : Material.PAPER, (selected ? "&a✓ " : "&b") + poll.options.get(i), "&7Current votes: &f" + poll.counts.get(i), "&7Click to " + (selected ? "remove" : "select") + "."));
        }
        if (poll.maxSelections == 1) inv.setItem(40, plugin.item(Material.CLOCK, "&eChoose one option", "&7Click an answer above to submit immediately."));
        else inv.setItem(40, plugin.item(Material.LIME_DYE, "&aSubmit vote", "&7Selected: &f" + draft.size() + "/" + poll.maxSelections));
        inv.setItem(49, plugin.item(Material.BARRIER, "&cClose", "&7Close without submitting changes."));
        plugin.localizePublicInventory(player,inv);LiveMenus.show(player,inv);
    }

    private void handlePollPublicClick(Player player, ExpansionMenu menu, int slot) {
        PollView poll = polls.activeView(player);
        if (poll == null) { player.closeInventory(); return; }
        if (!poll.id.equals(menu.data)) {
            voteDrafts.remove(player.getUniqueId());
            player.sendMessage(plugin.playerUiText(player, plugin.prefix() + "The server vote changed while this menu was open. The current vote is now displayed."));
            openVoteJava(player);
            return;
        }
        if (slot == 49) { voteDrafts.remove(player.getUniqueId()); player.closeInventory(); return; }
        if (slot == 40) {
            if (poll.maxSelections == 1) return;
            Set<Integer> selected = voteDrafts.getOrDefault(player.getUniqueId(), Collections.emptySet());
            if (polls.vote(player, poll.id, new ArrayList<>(selected))) voteDrafts.remove(player.getUniqueId());
            openVoteJava(player);
            return;
        }
        int[] slots = {10, 12, 14, 16, 28, 30, 32, 34};
        for (int i = 0; i < Math.min(slots.length, poll.options.size()); i++) if (slot == slots[i]) {
            if (poll.maxSelections == 1) {
                polls.vote(player, poll.id, List.of(i));
                voteDrafts.remove(player.getUniqueId());
                openVoteJava(player);
                return;
            }
            Set<Integer> selected = voteDrafts.computeIfAbsent(player.getUniqueId(), ignored -> new LinkedHashSet<>());
            if (!selected.remove(i)) {
                if (selected.size() >= poll.maxSelections) { player.sendMessage(plugin.playerUiText(player, plugin.prefix() + "You can select up to " + poll.maxSelections + " option(s).")); return; }
                selected.add(i);
            }
            openVoteJava(player);
            return;
        }
    }

    void openPollsAdminJava(Player admin) {
        if (!plugin.adminMenuAllowed(admin, "suiteexpansion.openpollsadminjava")) return;
        if (!plugin.has(admin, "simplexadminsuite.polls.admin")) { deny(admin, "simplexadminsuite.polls.admin"); return; }
        PollView poll = polls.activeView(admin);
        Inventory inv = LiveMenus.create(new ExpansionMenu("poll-admin", 0, ""), 45, plugin.color("&8Umfragen & Votes"));
        plugin.fill(inv);
        inv.setItem(4, plugin.item(Material.MAP, poll == null ? "&7Keine aktive Umfrage" : "&9" + poll.question,
                poll == null ? "&7Letztes Ergebnis: &f" + polls.latestResult() : "&7Restzeit: &f" + poll.remaining,
                poll == null ? "&7Erstelle eine neue englische Umfrage." : "&7Teilnehmer: &f" + poll.voterCount));
        if (poll == null) inv.setItem(11, plugin.item(Material.LIME_DYE, "&aNeue Umfrage", "&7Eingabeformat wird im Chat erklärt."));
        else {
            inv.setItem(11, plugin.item(Material.BOOK, "&bÖffentliche Vote-Ansicht", "&7Testet das Spielermenü."));
            inv.setItem(15, plugin.item(Material.RED_DYE, "&cJetzt beenden", "&7Veröffentlicht Ergebnis und Discord-Embed."));
        }
        if (poll == null) inv.setItem(22, plugin.item(Material.NETHER_STAR, "&6Letztes Ergebnis", wrapLore("&f" + polls.latestDetails(), 42)));
        else {
            List<String> live = new ArrayList<>();
            for (int i = 0; i < poll.options.size(); i++) live.add("&7" + (i + 1) + ". &f" + poll.options.get(i) + "&8: &b" + poll.counts.get(i));
            inv.setItem(22, plugin.item(Material.NETHER_STAR, "&bLive-Ergebnis", live));
        }
        inv.setItem(40, plugin.item(Material.BARRIER, "&cZurück", "&7Zurück zum Hauptmenü."));
        LiveMenus.show(admin,inv);
    }

    private void handlePollAdminClick(Player admin, int slot) {
        if (!plugin.has(admin, "simplexadminsuite.polls.admin")) return;
        PollView poll = polls.activeView(admin);
        if (slot == 11 && poll == null) {
            pendingText.put(admin.getUniqueId(), new PendingText("poll-create"));
            admin.closeInventory();
            admin.sendMessage(plugin.prefix() + "Neue Umfrage: Frage || Option 1; Option 2 || 1m/5m/1h/24h || Max-Auswahl || ändern ja/nein");
            admin.sendMessage(plugin.prefix() + "Alle Texte, die Spieler sehen, müssen Englisch sein. 'abbrechen' beendet die Eingabe.");
        } else if (slot == 11) openVoteJava(admin);
        else if (slot == 15 && poll != null) { polls.finish(admin.getName(), false); openPollsAdminJava(admin); }
        else if (slot == 40) plugin.openJavaAdminMain(admin);
    }

    void openPointsAdminJava(Player admin) {
        if (!plugin.adminMenuAllowed(admin, "suiteexpansion.openpointsadminjava")) return;
        if (!plugin.has(admin, "simplexadminsuite.points.admin")) { deny(admin, "simplexadminsuite.points.admin"); return; }
        Inventory inv = LiveMenus.create(new ExpansionMenu("points-admin", 0, ""), 45, plugin.color("&8Simplex Points"));
        plugin.fill(inv);
        inv.setItem(4, plugin.item(Material.EXPERIENCE_BOTTLE, "&bSimplex Points", "&7Aktive Profile: &f" + plugin.expansionProfiles().size(), "&7Historisch erkannte Accounts: &f" + historicProfiles.count()));
        inv.setItem(10, plugin.item(points.enabled() ? Material.LIME_DYE : Material.GRAY_DYE, "&ePunktesystem", "&7Status: " + (points.enabled() ? "&aAktiv" : "&cInaktiv")));
        inv.setItem(11, plugin.item(Material.CLOCK, "&eBelohnungsintervall", "&7Aktuell: &f" + points.intervalMinutes() + " Minuten", "&7Klick: 5 / 10 / 15 / 30 / 60"));
        inv.setItem(12, plugin.item(Material.EXPERIENCE_BOTTLE, "&ePunkte pro Intervall", "&7Aktuell: &f" + points.perInterval(), "&7Klick: 1 / 2 / 5 / 10 / 25"));
        inv.setItem(13, plugin.item(Material.BARRIER, "&7Punkte über Namen: aus", "&7Dauerhaft deaktiviert: Namen zeigen nur Rang,", "&7Nickname und optional den AFK-Status."));
        inv.setItem(14, plugin.item(plugin.getConfig().getBoolean("nameplates.show-luckperms", true) ? Material.LIME_DYE : Material.GRAY_DYE, "&6LuckPerms Präfix/Suffix", "&7Klick zum Umschalten."));
        inv.setItem(15, plugin.item(plugin.getConfig().getBoolean("simplex-points.discord-leaderboard", false) ? Material.LIME_DYE : Material.GRAY_DYE, "&9Discord Top-10", "&7Im persistenten Status-Embed."));
        inv.setItem(16, plugin.item(Material.PLAYER_HEAD, "&eSpielerkonten setzen / zurücksetzen", "&7Öffnet die Online- und 24-Stunden-Profile", "&7mit Kontostand, NBT und Statistiken."));
        inv.setItem(22, plugin.item(Material.NETHER_STAR, "&6Aktuelle Top 10", wrapLore("&f" + points.leaderboardPlain(10), 42)));
        inv.setItem(40, plugin.item(Material.BARRIER, "&cZurück", "&7Zurück zum Hauptmenü."));
        LiveMenus.show(admin,inv);
    }

    private void handlePointsAdminClick(Player admin, int slot) {
        if (!plugin.has(admin, "simplexadminsuite.points.admin")) return;
        if (slot == 10) plugin.getConfig().set("simplex-points.enabled", !points.enabled());
        else if (slot == 11) plugin.getConfig().set("simplex-points.award-interval-minutes", next(points.intervalMinutes(), new int[]{5, 10, 15, 30, 60}));
        else if (slot == 12) plugin.getConfig().set("simplex-points.points-per-interval", next(points.perInterval(), new int[]{1, 2, 5, 10, 25}));
        else if (slot == 13) { plugin.getConfig().set("nameplates.show-points", false); admin.sendMessage(plugin.prefix() + "Punkte über Spielernamen bleiben dauerhaft deaktiviert."); }
        else if (slot == 14) plugin.getConfig().set("nameplates.show-luckperms", !plugin.getConfig().getBoolean("nameplates.show-luckperms", true));
        else if (slot == 15) plugin.getConfig().set("simplex-points.discord-leaderboard", !plugin.getConfig().getBoolean("simplex-points.discord-leaderboard", false));
        else if (slot == 16) { plugin.openPlayerData(admin); return; }
        else if (slot == 40) { plugin.openJavaAdminMain(admin); return; }
        else return;
        plugin.saveConfig();
        points.refreshDisplays();
        plugin.expansionAudit(admin.getName(), null, "Simplex-Points geändert", "Java-Adminmenü, Slot " + slot);
        openPointsAdminJava(admin);
    }

    void openKeywordAdminJava(Player admin) {
        if (!plugin.adminMenuAllowed(admin, "suiteexpansion.openkeywordadminjava")) return;
        if (!plugin.has(admin, "simplexadminsuite.antispam")) { deny(admin, "simplexadminsuite.antispam"); return; }
        openKeywordAdminJava(admin, 0);
    }

    private void openKeywordAdminJava(Player admin, int requestedPage) {
        if (!plugin.adminMenuAllowed(admin, "suiteexpansion.openkeywordadminjava")) return;
        List<KeywordView> entries = filters.views();
        int maxPage = Math.max(0, (entries.size() - 1) / 36);
        int page = Math.max(0, Math.min(requestedPage, maxPage));
        Inventory inv = LiveMenus.create(new ExpansionMenu("filter-list", page, ""), 54, plugin.color("&8Keyword- & Regex-Filter"));
        plugin.fill(inv);
        int start = page * 36;
        for (int i = start; i < Math.min(entries.size(), start + 36); i++) {
            KeywordView entry = entries.get(i);
            inv.setItem(i - start, plugin.item(entry.enabled ? Material.NAME_TAG : Material.GRAY_DYE, (entry.enabled ? "&a" : "&7") + "#" + entry.id + " " + entry.pattern,
                    "&7Typ: &f" + entry.type, "&7Aktion: &f" + entry.action, "&7Klick zum Verwalten."));
        }
        inv.setItem(45, plugin.item(Material.ARROW, "&eVorherige Seite"));
        inv.setItem(47, plugin.item(Material.LIME_DYE, "&aFilter hinzufügen", "&7Eingabeformat wird im Chat erklärt."));
        inv.setItem(49, plugin.item(Material.BARRIER, "&cZurück", "&7Zurück zu Anti-Spam."));
        inv.setItem(53, plugin.item(Material.ARROW, "&eNächste Seite"));
        LiveMenus.show(admin,inv);
    }

    private void handleFilterListClick(Player admin, ExpansionMenu menu, int slot) {
        if (slot == 45 && menu.page > 0) { openKeywordAdminJava(admin, menu.page - 1); return; }
        if (slot == 53) { openKeywordAdminJava(admin, menu.page + 1); return; }
        if (slot == 47) {
            pendingText.put(admin.getUniqueId(), new PendingText("filter-create"));
            admin.closeInventory();
            admin.sendMessage(plugin.prefix() + "Filter: Muster || keyword/regex || block/warn/mute/kick || englische Spielernachricht");
            admin.sendMessage(plugin.prefix() + "Beispiel: badword || keyword || warn || That word is not allowed. 'abbrechen' beendet die Eingabe.");
            return;
        }
        if (slot == 49) { plugin.openJavaAntiSpam(admin); return; }
        if (slot >= 0 && slot < 36) {
            List<KeywordView> entries = filters.views();
            int index = menu.page * 36 + slot;
            if (index < entries.size()) openKeywordEntryJava(admin, entries.get(index).id, menu.page);
        }
    }

    private void openKeywordEntryJava(Player admin, long id, int returnPage) {
        if (!plugin.adminMenuAllowed(admin, "suiteexpansion.openkeywordentryjava")) return;
        KeywordView entry = filters.view(id);
        if (entry == null) { openKeywordAdminJava(admin, returnPage); return; }
        Inventory inv = LiveMenus.create(new ExpansionMenu("filter-entry", returnPage, Long.toString(id)), 45, plugin.color("&8Chatfilter #" + id));
        plugin.fill(inv);
        inv.setItem(4, plugin.item(Material.NAME_TAG, "&6" + entry.pattern, "&7Typ: &f" + entry.type, "&7Aktion: &f" + entry.action, "&7Status: " + (entry.enabled ? "&aAktiv" : "&cInaktiv")));
        inv.setItem(11, plugin.item(entry.enabled ? Material.GRAY_DYE : Material.LIME_DYE, entry.enabled ? "&eDeaktivieren" : "&aAktivieren"));
        inv.setItem(15, plugin.item(Material.RED_DYE, "&cDauerhaft löschen", "&7Löscht diesen Filter sofort."));
        inv.setItem(40, plugin.item(Material.BARRIER, "&cZurück"));
        LiveMenus.show(admin,inv);
    }

    private void handleFilterEntryClick(Player admin, ExpansionMenu menu, int slot) {
        long id;
        try { id = Long.parseLong(menu.data); } catch (Exception ignored) { openKeywordAdminJava(admin, menu.page); return; }
        if (slot == 11) { filters.toggle(admin.getName(), id); openKeywordEntryJava(admin, id, menu.page); }
        else if (slot == 15) { filters.delete(admin.getName(), id); openKeywordAdminJava(admin, menu.page); }
        else if (slot == 40) openKeywordAdminJava(admin, menu.page);
    }

    private void sendPoints(Player player) {
        long balance = points.points(player);
        String rank = points.rankName(balance);
        player.sendMessage(plugin.playerUiText(player, plugin.color("&8&m----------------------------------------")));
        player.sendMessage(plugin.playerUiText(player, plugin.prefix() + plugin.color("&bSimplex Points")));
        player.sendMessage(plugin.playerUiText(player, plugin.color("&7Balance: &b" + balance + " SP")));
        player.sendMessage(plugin.playerUiText(player, plugin.color("&7Rank: &f" + rank)));
        player.sendMessage(plugin.playerUiText(player, plugin.color("&7Active play time: &f" + points.activeTime(player))));
        player.sendMessage(plugin.color(plugin.playerText(player,"&7Nutze &f/points top &7für die Rangliste und &f/shop &7zum Einkaufen.","&7Use &f/points top &7for the leaderboard and &f/shop &7to spend your points.")));
        player.sendMessage(plugin.playerUiText(player, plugin.color("&8&m----------------------------------------")));
    }

    private void sendPointsLeaderboard(CommandSender sender) {
        sender.sendMessage(plugin.color("&8&m----------------------------------------"));
        sender.sendMessage(plugin.publicUiText(sender,plugin.prefix() + plugin.color("&bSimplex Points — Top 10")));
        List<SimplexAdminSuite.PlayerProfile> top = points.top(10);
        if (top.isEmpty()) sender.sendMessage(plugin.publicUiText(sender,plugin.color("&7No points have been earned yet.")));
        for (int i = 0; i < top.size(); i++) sender.sendMessage(plugin.color("&b" + (i + 1) + ". &f" + top.get(i).displayName() + " &8— &b" + top.get(i).simplexPoints() + " SP &7(" + (sender instanceof Player p?plugin.playerUiText(p,points.rankName(top.get(i).simplexPoints())):points.rankName(top.get(i).simplexPoints())) + ")"));
        sender.sendMessage(plugin.color("&8&m----------------------------------------"));
    }

    private static final class ExpansionMenu implements InventoryHolder {
        private final String type;
        private final int page;
        private final String data;
        private ExpansionMenu(String type, int page, String data) { this.type = type; this.page = page; this.data = data == null ? "" : data; }
        public Inventory getInventory() { return null; }
    }

    private static final class PendingText {
        private final String type;
        private PendingText(String type) { this.type = type; }
    }

    static final class KeywordView {
        final long id;
        final String pattern;
        final String type;
        final String action;
        final boolean enabled;
        private KeywordView(long id, String pattern, String type, String action, boolean enabled) {
            this.id = id; this.pattern = pattern; this.type = type; this.action = action; this.enabled = enabled;
        }
    }

    static final class PollView {
        final String id;
        final String question;
        final List<String> options;
        final List<Integer> counts;
        final Set<Integer> selected;
        final int maxSelections;
        final boolean allowChange;
        final int voterCount;
        final String remaining;
        private PollView(String id, String question, List<String> options, List<Integer> counts, Set<Integer> selected,
                         int maxSelections, boolean allowChange, int voterCount, String remaining) {
            this.id = id; this.question = question; this.options = options; this.counts = counts; this.selected = selected;
            this.maxSelections = maxSelections; this.allowChange = allowChange; this.voterCount = voterCount; this.remaining = remaining;
        }
    }

    private static final class RulesManager {
        private final SimplexAdminSuite plugin;
        private final File folder;
        private final File rulesFile;
        private final File acceptanceFolder;
        private final Map<UUID, Long> reminders = new ConcurrentHashMap<>();
        private final Map<UUID, Acceptance> acceptanceCache = new ConcurrentHashMap<>();
        private List<String> values = new ArrayList<>();
        private int version = 1;
        private boolean requireAcceptance = true;
        private long updatedAt;
        private String updatedBy = "System";
        private String currentHash = "";
        private BukkitTask reminderTask;

        private RulesManager(SimplexAdminSuite plugin) {
            this.plugin = plugin;
            this.folder = new File(plugin.getDataFolder(), "rules");
            this.rulesFile = new File(folder, "rules.yml");
            this.acceptanceFolder = new File(folder, "accepted-players");
            if (!acceptanceFolder.exists()) acceptanceFolder.mkdirs();
            load();
        }

        private synchronized void load() {
            if (!acceptanceFolder.exists()) acceptanceFolder.mkdirs();
            acceptanceCache.clear();
            currentHash = "";
            if (!rulesFile.exists()) {
                values = defaultRules();
                requireAcceptance = plugin.getConfig().getBoolean("server-rules.require-acceptance", true);
                updatedAt = System.currentTimeMillis();
                save();
                return;
            }
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(rulesFile);
            version = Math.max(1, yaml.getInt("rules-version", 1));
            requireAcceptance = yaml.getBoolean("require-acceptance", true);
            values = sanitizeRules(yaml.getStringList("rules"));
            boolean repaired = values.isEmpty();
            if (repaired) values = defaultRules();
            updatedAt = yaml.getLong("updated-at", 0L);
            updatedBy = yaml.getString("updated-by", "System");
            if (repaired) save();
        }

        private void reload() { stop(); load(); }
        private void start() {
            stop();
            for (Player player : Bukkit.getOnlinePlayers()) acceptanceCache.put(player.getUniqueId(), readAcceptance(player.getUniqueId()));
            reminderTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                for (Player player : Bukkit.getOnlinePlayers()) if (needsAcceptance(player)) remind(player);
            }, 200L, 200L);
        }
        private void stop() { if (reminderTask != null) try { reminderTask.cancel(); } catch (Exception ignored) { } reminderTask = null; reminders.clear(); }
        private void join(Player player) { reminders.remove(player.getUniqueId()); acceptanceCache.put(player.getUniqueId(), readAcceptance(player.getUniqueId())); }
        private void quit(Player player) { reminders.remove(player.getUniqueId()); acceptanceCache.remove(player.getUniqueId()); }
        private synchronized int version() { return version; }
        private synchronized boolean requireAcceptance() { return requireAcceptance; }
        private synchronized List<String> rules() { return Collections.unmodifiableList(new ArrayList<>(values)); }

        private synchronized boolean needsAcceptance(Player player) {
            if (player == null || !requireAcceptance || plugin.has(player, "simplexadminsuite.rules.bypass")) return false;
            Acceptance acceptance = acceptanceCache.computeIfAbsent(player.getUniqueId(), this::readAcceptance);
            return acceptance.version != version || !hash().equals(acceptance.hash);
        }

        private boolean promptIfRequired(Player player) {
            if (!needsAcceptance(player)) return false;
            player.sendMessage(plugin.playerUiText(player, plugin.prefix() + "The server rules were updated. You must read and accept them before continuing."));
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (Bukkit.getPlayer(player.getUniqueId()) == null || !needsAcceptance(player)) return;
                if (!plugin.openBedrockRules(player, true)) plugin.expansion.openRulesJava(player);
            }, 20L);
            return true;
        }

        private void remind(Player player) {
            if (!needsAcceptance(player)) return;
            long now = System.currentTimeMillis();
            long last = reminders.getOrDefault(player.getUniqueId(), 0L);
            if (now - last < 8_000L) return;
            reminders.put(player.getUniqueId(), now);
            player.sendMessage(plugin.playerUiText(player, plugin.prefix() + "Please open /rules and accept rules version " + version + " before continuing."));
        }

        private synchronized void accept(Player player, int expectedVersion) {
            if (player == null) return;
            if (expectedVersion != version) {
                player.sendMessage(plugin.playerUiText(player, plugin.prefix() + "The server rules changed before your confirmation. Please read the current version."));
                if (!plugin.openBedrockRules(player, true)) plugin.expansion.openRulesJava(player);
                return;
            }
            try {
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.set("schema-version", 2);
                yaml.set("uuid", player.getUniqueId().toString());
                yaml.set("name", player.getName());
                yaml.set("accepted-rules-version", version);
                yaml.set("rules-hash", hash());
                yaml.set("accepted-at", System.currentTimeMillis());
                saveAtomic(yaml, new File(acceptanceFolder, player.getUniqueId() + ".yml"));
                acceptanceCache.put(player.getUniqueId(), new Acceptance(version, hash()));
                reminders.remove(player.getUniqueId());
                player.closeInventory();
                player.sendMessage(plugin.playerUiText(player, plugin.prefix() + "Thank you. You accepted server rules version " + version + ". You may now continue playing."));
                plugin.expansionAudit(player.getName(), player.getUniqueId(), "Serverregeln zugestimmt", "Version " + version);
                plugin.deliverAfterRulesAccepted(player);
            } catch (Exception error) {
                player.sendMessage(plugin.playerUiText(player, plugin.prefix() + "Your rules acceptance could not be saved. Please contact staff."));
                plugin.getLogger().warning("Regelzustimmung konnte nicht gespeichert werden: " + error.getMessage());
            }
        }

        private synchronized boolean update(String actor, String input, boolean require) {
            List<String> next = parseRules(input);
            if (next.isEmpty()) return false;
            if (next.equals(values) && require == requireAcceptance) return false;
            values = next;
            requireAcceptance = require;
            version++;
            currentHash = "";
            updatedAt = System.currentTimeMillis();
            updatedBy = cleanActor(actor);
            save();
            announceUpdate();
            plugin.expansionAudit(updatedBy, null, "Serverregeln veröffentlicht", "Version " + version + ", Regeln=" + values.size());
            return true;
        }

        private synchronized void setRequireAcceptance(String actor, boolean required) {
            if (requireAcceptance == required) return;
            requireAcceptance = required;
            version++;
            currentHash = "";
            updatedAt = System.currentTimeMillis();
            updatedBy = cleanActor(actor);
            save();
            announceUpdate();
            plugin.expansionAudit(updatedBy, null, "Regel-Zustimmungspflicht geändert", "Version " + version + ", erforderlich=" + required);
        }

        private synchronized void publishNewVersion(String actor) {
            version++;
            currentHash = "";
            updatedAt = System.currentTimeMillis();
            updatedBy = cleanActor(actor);
            save();
            announceUpdate();
            plugin.expansionAudit(updatedBy, null, "Neue Regelversion erzwungen", "Version " + version);
        }

        private void announceUpdate() {
            plugin.broadcastPublicText(plugin.prefix() + "The server rules have been updated to version " + version + ". Use /rules to read them.");
            for (Player online : Bukkit.getOnlinePlayers()) if (needsAcceptance(online)) Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (Bukkit.getPlayer(online.getUniqueId()) != null) {
                    if (!plugin.openBedrockRules(online, true)) plugin.expansion.openRulesJava(online);
                }
            }, 40L);
        }

        private synchronized int acceptanceCount() {
            File[] files = acceptanceFolder.listFiles((dir, name) -> name.endsWith(".yml"));
            if (files == null) return 0;
            String currentHash = hash();
            int count = 0;
            for (File file : files) {
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
                if (yaml.getInt("accepted-rules-version", 0) == version && currentHash.equals(yaml.getString("rules-hash", ""))) count++;
            }
            return count;
        }

        private synchronized String hash() {
            if (!currentHash.isEmpty()) return currentHash;
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] bytes = digest.digest((version + "\n" + String.join("\n", values)).getBytes(StandardCharsets.UTF_8));
                StringBuilder out = new StringBuilder();
                for (byte value : bytes) out.append(String.format(Locale.ROOT, "%02x", value & 0xff));
                currentHash = out.toString();
            } catch (Exception ignored) { currentHash = Integer.toHexString(values.hashCode()); }
            return currentHash;
        }

        private synchronized void save() {
            try {
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.set("schema-version", 2);
                yaml.set("rules-version", version);
                yaml.set("require-acceptance", requireAcceptance);
                yaml.set("updated-at", updatedAt);
                yaml.set("updated-by", updatedBy);
                yaml.set("rules", values);
                saveAtomic(yaml, rulesFile);
            } catch (Exception error) { plugin.getLogger().warning("Serverregeln konnten nicht gespeichert werden: " + error.getMessage()); }
        }

        private List<String> parseRules(String input) {
            if (input == null) return Collections.emptyList();
            String separator = input.contains("||") ? "\\|\\|" : input.contains("\n") ? "\\R" : "\\|";
            List<String> result = new ArrayList<>();
            for (String part : input.split(separator)) {
                String clean = plugin.sanitizeText(part, 240).trim();
                if (!clean.isEmpty() && !result.contains(clean)) result.add(clean);
                if (result.size() >= 30) break;
            }
            return result;
        }

        private List<String> sanitizeRules(Collection<String> source) {
            List<String> result = new ArrayList<>();
            if (source != null) for (String value : source) {
                String clean = plugin.sanitizeText(value, 240).trim();
                if (!clean.isEmpty() && !result.contains(clean)) result.add(clean);
                if (result.size() >= 30) break;
            }
            return result;
        }

        private List<String> defaultRules() {
            List<String> defaults = sanitizeRules(plugin.getConfig().getStringList("server-rules.default-rules"));
            if (!defaults.isEmpty()) return defaults;
            return new ArrayList<>(List.of(
                    "Be respectful to every player and staff member.",
                    "Do not grief, steal, scam, or intentionally destroy other players' work.",
                    "Do not use cheats, exploits, duplication bugs, or unauthorized modifications.",
                    "Do not spam, advertise, share prohibited links, or bypass the chat filter.",
                    "Follow staff instructions and report bugs instead of abusing them."
            ));
        }

        private Acceptance readAcceptance(UUID uuid) {
            File file = new File(acceptanceFolder, uuid + ".yml");
            if (!file.isFile()) return new Acceptance(0, "");
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            return new Acceptance(yaml.getInt("accepted-rules-version", 0), yaml.getString("rules-hash", ""));
        }

        private static final class Acceptance {
            private final int version;
            private final String hash;
            private Acceptance(int version, String hash) { this.version = version; this.hash = hash == null ? "" : hash; }
        }
    }

    private static final class KeywordEntry {
        private final long id;
        private final String pattern;
        private final String type;
        private final String action;
        private final String publicMessage;
        private boolean enabled;
        private final long createdAt;
        private final String createdBy;
        private transient Pattern compiled;
        private transient String normalized;

        private KeywordEntry(long id, String pattern, String type, String action, String publicMessage, boolean enabled, long createdAt, String createdBy) {
            this.id = id; this.pattern = pattern; this.type = type; this.action = action; this.publicMessage = publicMessage;
            this.enabled = enabled; this.createdAt = createdAt; this.createdBy = createdBy;
        }
    }

    private static final class KeywordFilterManager {
        private final SimplexAdminSuite plugin;
        private final File file;
        private final List<KeywordEntry> entries = new ArrayList<>();
        private final AtomicLong sequence = new AtomicLong(1L);

        private KeywordFilterManager(SimplexAdminSuite plugin) {
            this.plugin = plugin;
            this.file = new File(plugin.getDataFolder(), "chat-filter.yml");
            load();
        }

        private synchronized void reload() { load(); }

        private synchronized void load() {
            entries.clear();
            long next = 1L;
            if (file.exists()) {
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
                int count = Math.max(0, yaml.getInt("count", 0));
                for (int i = 0; i < count; i++) {
                    String path = "filters." + i;
                    long id = yaml.getLong(path + ".id", i + 1L);
                    KeywordEntry entry = new KeywordEntry(id, yaml.getString(path + ".pattern", ""), yaml.getString(path + ".type", "KEYWORD"),
                            normalizeAction(yaml.getString(path + ".action", "BLOCK")),
                            yaml.getString(path + ".public-message", "That message is not allowed on this server."),
                            yaml.getBoolean(path + ".enabled", true), yaml.getLong(path + ".created-at", 0L), yaml.getString(path + ".created-by", "Unknown"));
                    if (prepare(entry) != null) continue;
                    entries.add(entry);
                    next = Math.max(next, id + 1L);
                }
            }
            sequence.set(next);
        }

        private synchronized String add(String actor, String rawPattern, boolean regex, String rawAction, String rawMessage) {
            String pattern = plugin.sanitizeText(rawPattern, 160).trim();
            if (pattern.length() < 2) return "Das Muster muss mindestens zwei Zeichen enthalten.";
            String type = regex ? "REGEX" : "KEYWORD";
            String message = plugin.sanitizeText(rawMessage, 180).trim();
            if (message.isEmpty()) message = "That message is not allowed on this server.";
            for (KeywordEntry existing : entries) if (existing.pattern.equalsIgnoreCase(pattern) && existing.type.equals(type)) return "Ein identischer Filter existiert bereits.";
            KeywordEntry entry = new KeywordEntry(sequence.getAndIncrement(), pattern, type, normalizeAction(rawAction), message, true, System.currentTimeMillis(), cleanActor(actor));
            String error = prepare(entry);
            if (error != null) return error;
            entries.add(entry);
            save();
            plugin.expansionAudit(actor, null, "Chatfilter hinzugefügt", "#" + entry.id + " " + type + " " + entry.action + " " + pattern);
            return "Filter #" + entry.id + " wurde gespeichert.";
        }

        private synchronized String prepare(KeywordEntry entry) {
            try {
                if (entry.type.equals("REGEX")) {
                    String unsafe = regexSafetyProblem(entry.pattern);
                    if (unsafe != null) return unsafe;
                    entry.compiled = Pattern.compile(entry.pattern, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);
                } else {
                    entry.normalized = normalizeEvasion(entry.pattern);
                    if (entry.normalized.length() < 2) return "Das normalisierte Keyword ist zu kurz.";
                    StringBuilder flexible = new StringBuilder();
                    for (int i = 0; i < entry.normalized.length(); i++) {
                        if (i > 0) flexible.append("[\\p{P}\\p{S}\\s_]*");
                        flexible.append(Pattern.quote(String.valueOf(entry.normalized.charAt(i))));
                    }
                    entry.compiled = Pattern.compile(flexible.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);
                }
                return null;
            } catch (Exception error) { return "Regex konnte nicht kompiliert werden: " + error.getMessage(); }
        }

        private KeywordEntry match(String raw) {
            String message = raw == null ? "" : raw.substring(0, Math.min(raw.length(), 500));
            String normalized = normalizeEvasion(message);
            String collapsed = collapseRepeats(normalized);
            List<KeywordEntry> snapshot;
            synchronized (this) { snapshot = new ArrayList<>(entries); }
            for (KeywordEntry entry : snapshot) {
                if (!entry.enabled || entry.compiled == null) continue;
                if (entry.type.equals("REGEX")) {
                    if (entry.compiled.matcher(message).find()) return entry;
                } else {
                    if (normalized.contains(entry.normalized) || collapsed.contains(collapseRepeats(entry.normalized)) || entry.compiled.matcher(message).find()) return entry;
                }
            }
            return null;
        }

        private synchronized int activeCount() { return (int) entries.stream().filter(entry -> entry.enabled).count(); }
        private synchronized List<KeywordView> views() { return entries.stream().sorted(Comparator.comparingLong(entry -> entry.id)).map(this::viewOf).collect(Collectors.toList()); }
        private synchronized KeywordView view(long id) { return entries.stream().filter(entry -> entry.id == id).findFirst().map(this::viewOf).orElse(null); }
        private KeywordView viewOf(KeywordEntry entry) { return new KeywordView(entry.id, entry.pattern, entry.type, entry.action, entry.enabled); }
        private synchronized void toggle(String actor, long id) { for (KeywordEntry entry : entries) if (entry.id == id) { entry.enabled = !entry.enabled; save(); plugin.expansionAudit(actor, null, "Chatfilter umgeschaltet", "#" + id + " aktiv=" + entry.enabled); return; } }
        private synchronized void delete(String actor, long id) { if (entries.removeIf(entry -> entry.id == id)) { save(); plugin.expansionAudit(actor, null, "Chatfilter gelöscht", "#" + id); } }

        private synchronized void save() {
            try {
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.set("schema-version", 2);
                yaml.set("count", entries.size());
                for (int i = 0; i < entries.size(); i++) {
                    KeywordEntry entry = entries.get(i);
                    String path = "filters." + i;
                    yaml.set(path + ".id", entry.id); yaml.set(path + ".pattern", entry.pattern); yaml.set(path + ".type", entry.type);
                    yaml.set(path + ".action", entry.action); yaml.set(path + ".public-message", entry.publicMessage); yaml.set(path + ".enabled", entry.enabled);
                    yaml.set(path + ".created-at", entry.createdAt); yaml.set(path + ".created-by", entry.createdBy);
                }
                saveAtomic(yaml, file);
            } catch (Exception error) { plugin.getLogger().warning("Chatfilter konnte nicht gespeichert werden: " + error.getMessage()); }
        }

        private static String regexSafetyProblem(String pattern) {
            if (pattern == null || pattern.length() > 160) return "Regex ist zu lang (maximal 160 Zeichen).";
            if (pattern.contains("(?") || pattern.matches(".*\\\\[1-9].*")) return "Lookarounds, eingebettete Modi und Rückverweise sind aus Leistungsschutzgründen nicht erlaubt.";
            if (Pattern.compile("\\([^)]*[+*][^)]*\\)[+*{]").matcher(pattern).find()) return "Verschachtelte Wiederholungen sind aus Leistungsschutzgründen nicht erlaubt.";
            if (Pattern.compile("\\([^)]*\\|[^)]*\\)[+*{]").matcher(pattern).find()) return "Wiederholte Alternativen sind aus Leistungsschutzgründen nicht erlaubt.";
            if (Pattern.compile("(?:\\.\\*|\\.\\+).*(?:\\.\\*|\\.\\+)").matcher(pattern).find()) return "Mehrere unbeschränkte Wildcards sind aus Leistungsschutzgründen nicht erlaubt.";
            int unbounded = 0;
            for (int i = 0; i < pattern.length(); i++) if ((pattern.charAt(i) == '*' || pattern.charAt(i) == '+') && (i == 0 || pattern.charAt(i - 1) != '\\')) unbounded++;
            if (unbounded > 6) return "Regex enthält zu viele unbeschränkte Wiederholungen.";
            return null;
        }
    }

    private static final class Poll {
        private String id;
        private String question;
        private List<String> options;
        private long startedAt;
        private long endsAt;
        private int maxSelections;
        private boolean allowChange;
        private String createdBy;
        private final Map<UUID, LinkedHashSet<Integer>> votes = new LinkedHashMap<>();
        private final Map<UUID, String> names = new LinkedHashMap<>();
    }

    private static final class PollManager {
        private final SimplexAdminSuite plugin;
        private final File file;
        private Poll active;
        private String latestResult = "No completed vote yet.";
        private String latestDetails = "No completed vote yet.";
        private final List<String> history = new ArrayList<>();
        private BukkitTask task;

        private PollManager(SimplexAdminSuite plugin) { this.plugin = plugin; this.file = new File(plugin.getDataFolder(), "polls.yml"); load(); }
        private synchronized void reload() { stop(); load(); }
        private synchronized void load() {
            active = null; history.clear(); latestResult = "No completed vote yet."; latestDetails = "No completed vote yet.";
            if (!file.exists()) return;
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            latestResult = yaml.getString("latest-result", latestResult);
            latestDetails = yaml.getString("latest-details", latestResult);
            history.addAll(yaml.getStringList("history"));
            String id = yaml.getString("active.id", "");
            if (id.isBlank()) return;
            Poll poll = new Poll(); poll.id = id; poll.question = yaml.getString("active.question", "Server vote"); poll.options = cleanOptions(yaml.getStringList("active.options"));
            poll.startedAt = yaml.getLong("active.started-at", 0L); poll.endsAt = yaml.getLong("active.ends-at", 0L); poll.maxSelections = Math.max(1, yaml.getInt("active.max-selections", 1));
            poll.allowChange = yaml.getBoolean("active.allow-change", false); poll.createdBy = yaml.getString("active.created-by", "Unknown");
            for (String line : yaml.getStringList("active.votes")) {
                String[] parts = line.split("\\|", 3); if (parts.length < 3) continue;
                try {
                    UUID uuid = UUID.fromString(parts[0]); LinkedHashSet<Integer> selected = new LinkedHashSet<>();
                    for (String number : parts[2].split(",")) { int index = parseInt(number, -1); if (index >= 0 && index < poll.options.size()) selected.add(index); }
                    if (!selected.isEmpty()) { poll.votes.put(uuid, selected); poll.names.put(uuid, parts[1]); }
                } catch (Exception ignored) { }
            }
            if (poll.options.size() >= 2) active = poll;
        }

        private synchronized void start() {
            stop();
            if (active != null && active.endsAt <= System.currentTimeMillis()) finish("System", true);
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, 40L);
        }
        private synchronized void stop() { if (task != null) try { task.cancel(); } catch (Exception ignored) { } task = null; save(); }

        private void tick() {
            PollView view;
            synchronized (this) {
                if (active == null) return;
                if (active.endsAt <= System.currentTimeMillis()) { finish("System", true); return; }
                view = activeView(null);
            }
            if (!plugin.getConfig().getBoolean("polls.actionbar-enabled", true)) return;
            String actionBar = plugin.color("&9LIVE VOTE &8• &f" + shorten(view.question, 58) + " &8• &b" + view.voterCount + " voted &8• &f/vote &8• &7" + view.remaining);
            for (Player player : Bukkit.getOnlinePlayers()) sendActionBar(player, plugin.adminRuntime==null?actionBar:plugin.adminRuntime.decorateBar(player,actionBar));
        }

        private synchronized String create(String actor, String rawQuestion, String rawOptions, long durationSeconds, int requestedMax, boolean allowChange) {
            if (active != null) return "Es läuft bereits eine Umfrage.";
            String question = plugin.sanitizeText(rawQuestion, 300).trim();
            List<String> options = cleanOptions(splitOptions(rawOptions));
            if (question.length() < 5) return "Die Frage ist zu kurz.";
            if (options.size() < 2 || options.size() > 8) return "Es werden 2 bis 8 eindeutige Antwortmöglichkeiten benötigt.";
            if (durationSeconds <= 0L) durationSeconds = 3600L;
            durationSeconds = Math.max(30L, Math.min(604800L, durationSeconds));
            Poll poll = new Poll(); poll.id = Long.toString(System.currentTimeMillis(), 36); poll.question = question; poll.options = options;
            poll.startedAt = System.currentTimeMillis(); poll.endsAt = poll.startedAt + durationSeconds * 1000L;
            poll.maxSelections = Math.max(1, Math.min(options.size(), requestedMax)); poll.allowChange = allowChange; poll.createdBy = cleanActor(actor);
            active = poll; plugin.expansion.voteDrafts.clear(); save();
            plugin.broadcastPublicText(plugin.prefix() + "A new server vote has started: " + question + " Use /vote to participate.");
            plugin.discordPollStarted(question, options, durationSeconds, actor);
            plugin.expansionAudit(actor, null, "Umfrage gestartet", question + " | " + options);
            return "Die Umfrage wurde gestartet.";
        }

        private synchronized boolean vote(Player player, String expectedPollId, List<Integer> rawSelections) {
            if (player == null || active == null) { if (player != null) player.sendMessage(plugin.playerUiText(player, plugin.prefix() + "There is no active server vote.")); return false; }
            if (expectedPollId == null || !active.id.equals(expectedPollId)) { player.sendMessage(plugin.playerUiText(player, plugin.prefix() + "This server vote is no longer active. Open /vote to view the current vote.")); return false; }
            if (plugin.expansion != null && plugin.expansion.rulesRequired(player)) { player.sendMessage(plugin.playerUiText(player, plugin.prefix() + "Please accept the current server rules with /rules before voting.")); return false; }
            if (active.endsAt <= System.currentTimeMillis()) { finish("System", true); player.sendMessage(plugin.playerUiText(player, plugin.prefix() + "This server vote has already ended.")); return false; }
            LinkedHashSet<Integer> selected = new LinkedHashSet<>();
            if (rawSelections != null) for (Integer index : rawSelections) if (index != null && index >= 0 && index < active.options.size()) selected.add(index);
            if (selected.isEmpty()) { player.sendMessage(plugin.playerUiText(player, plugin.prefix() + "Please select at least one option.")); return false; }
            if (selected.size() > active.maxSelections) { player.sendMessage(plugin.playerUiText(player, plugin.prefix() + "You can select up to " + active.maxSelections + " option(s).")); return false; }
            if (active.votes.containsKey(player.getUniqueId()) && !active.allowChange) { player.sendMessage(plugin.playerUiText(player, plugin.prefix() + "You already voted and this vote does not allow changes.")); return false; }
            active.votes.put(player.getUniqueId(), selected); active.names.put(player.getUniqueId(), player.getName()); save();
            List<String> selectedNames = selected.stream().map(index -> active.options.get(index)).collect(Collectors.toList());
            player.sendMessage(plugin.playerUiText(player, plugin.prefix() + "Your vote was recorded: " + String.join(", ", selectedNames) + "."));
            plugin.discordPollVote(player.getName(), active.question, selectedNames);
            plugin.expansionAudit(player.getName(), player.getUniqueId(), "Umfrage abgestimmt", active.id + " | " + selectedNames);
            return true;
        }

        private synchronized void finish(String actor, boolean expired) {
            if (active == null) return;
            List<Integer> counts = counts(active);
            int totalSelections = counts.stream().mapToInt(Integer::intValue).sum();
            int highest = counts.stream().mapToInt(Integer::intValue).max().orElse(0);
            List<String> winners = new ArrayList<>();
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < active.options.size(); i++) {
                if (counts.get(i) == highest && highest > 0) winners.add(active.options.get(i));
                double percent = totalSelections <= 0 ? 0.0 : counts.get(i) * 100.0 / totalSelections;
                if (i > 0) result.append("\n");
                result.append(i + 1).append(". ").append(active.options.get(i)).append(": ").append(counts.get(i)).append(" (").append(String.format(Locale.ROOT, "%.1f", percent)).append("%)");
            }
            if (highest == 0) result.append("\nNo votes were submitted.");
            else result.append("\nWinner: ").append(String.join(" / ", winners));
            result.append("\nUnique voters: ").append(active.votes.size());
            latestResult = active.question + " — " + (highest == 0 ? "No votes" : String.join(" / ", winners) + " with " + highest + " vote(s)");
            latestDetails = result.toString();
            history.add(Instant.now() + " | " + latestResult);
            int maxHistory = Math.max(5, Math.min(500, plugin.getConfig().getInt("polls.max-history", 50)));
            while (history.size() > maxHistory) history.remove(0);
            String question = active.question;
            active = null; plugin.expansion.voteDrafts.clear(); save();
            plugin.broadcastPublicText(plugin.prefix() + "The server vote has ended. " + latestResult);
            plugin.discordPollEnded(question, result.toString());
            plugin.expansionAudit(actor, null, expired ? "Umfrage automatisch beendet" : "Umfrage beendet", latestResult);
        }

        private synchronized PollView activeView(Player player) {
            if (active == null) return null;
            List<Integer> counts = counts(active);
            Set<Integer> selected = player == null ? Collections.emptySet() : new LinkedHashSet<>(active.votes.getOrDefault(player.getUniqueId(), new LinkedHashSet<>()));
            return new PollView(active.id, active.question, Collections.unmodifiableList(new ArrayList<>(active.options)), Collections.unmodifiableList(counts),
                    Collections.unmodifiableSet(selected), active.maxSelections, active.allowChange, active.votes.size(), humanDuration(Math.max(0L, active.endsAt - System.currentTimeMillis())));
        }

        private synchronized String latestResult() { return latestResult; }
        private synchronized String latestDetails() { return latestDetails; }
        private synchronized String summaryEnglish() { return active == null ? "There is no active server vote." : active.question + " — " + active.votes.size() + " voter(s), ends in " + humanDuration(active.endsAt - System.currentTimeMillis()); }
        private synchronized String summaryGerman() { return active == null ? "Keine aktive Umfrage" : "Aktiv: " + active.question + " • " + active.votes.size() + " Teilnehmer • " + humanDuration(active.endsAt - System.currentTimeMillis()); }

        private static List<Integer> counts(Poll poll) {
            List<Integer> counts = new ArrayList<>(Collections.nCopies(poll.options.size(), 0));
            for (Set<Integer> selected : poll.votes.values()) for (Integer index : selected) if (index >= 0 && index < counts.size()) counts.set(index, counts.get(index) + 1);
            return counts;
        }

        private synchronized void save() {
            try {
                YamlConfiguration yaml = new YamlConfiguration(); yaml.set("schema-version", 2); yaml.set("latest-result", latestResult); yaml.set("latest-details", latestDetails); yaml.set("history", history);
                if (active != null) {
                    yaml.set("active.id", active.id); yaml.set("active.question", active.question); yaml.set("active.options", active.options);
                    yaml.set("active.started-at", active.startedAt); yaml.set("active.ends-at", active.endsAt); yaml.set("active.max-selections", active.maxSelections);
                    yaml.set("active.allow-change", active.allowChange); yaml.set("active.created-by", active.createdBy);
                    List<String> votes = new ArrayList<>();
                    for (Map.Entry<UUID, LinkedHashSet<Integer>> entry : active.votes.entrySet()) votes.add(entry.getKey() + "|" + active.names.getOrDefault(entry.getKey(), "Unknown") + "|" + entry.getValue().stream().map(String::valueOf).collect(Collectors.joining(",")));
                    yaml.set("active.votes", votes);
                }
                saveAtomic(yaml, file);
            } catch (Exception error) { plugin.getLogger().warning("Umfragen konnten nicht gespeichert werden: " + error.getMessage()); }
        }
    }

    private static final class Rank {
        private final long threshold;
        private final String name;
        private Rank(long threshold, String name) { this.threshold = threshold; this.name = name; }
    }

    private static final class PointsManager {
        private final SimplexAdminSuite plugin;
        private BukkitTask task;
        private long ticks;
        private PointsManager(SimplexAdminSuite plugin) { this.plugin = plugin; }
        private void reload() { stop(); }
        private void start() { stop(); task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1200L, 1200L); refreshDisplays(); }
        private void stop() { if (task != null) try { task.cancel(); } catch (Exception ignored) { } task = null; }
        private boolean enabled() { return plugin.getConfig().getBoolean("simplex-points.enabled", true); }
        private int intervalMinutes() { return Math.max(1, plugin.getConfig().getInt("simplex-points.award-interval-minutes", 10)); }
        private int perInterval() { return Math.max(1, plugin.getConfig().getInt("simplex-points.points-per-interval", 5)); }
        private void ensure(Player player) { plugin.expansionProfile(player); }

        private void tick() {
            if (!enabled()) return;
            ticks++;
            long interval = intervalMinutes() * 60_000L;
            List<SimplexAdminSuite.PlayerProfile> checkpoints = new ArrayList<>();
            boolean pointsChanged = false;
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (plugin.expansionIsAfk(player) || !plugin.expansionQualifiesForPoints(player)
                        || plugin.expansion != null && plugin.expansion.rulesRequired(player)) continue;
                SimplexAdminSuite.PlayerProfile profile = plugin.expansionProfile(player); if (profile == null) continue;
                long before = profile.pointsActiveMillis(); long after = before + 60_000L;
                long awards = after / interval - before / interval;
                long gained = Math.max(0L, awards) * perInterval();
                profile.addPointActivity(60_000L, gained);
                if (gained > 0L || ticks % 5L == 0L) checkpoints.add(profile);
                if (gained > 0L) {
                    pointsChanged = true;
                    player.sendMessage(plugin.playerUiText(player, plugin.prefix() + "You earned " + gained + " Simplex Points for active play time. Balance: " + profile.simplexPoints() + " SP."));
                    plugin.refreshExpansionNameplate(player);
                }
            }
            if (!checkpoints.isEmpty()) plugin.saveExpansionProfilesAsync(checkpoints);
            if (pointsChanged) plugin.discordPointsChanged();
        }

        private long points(Player player) { SimplexAdminSuite.PlayerProfile profile = plugin.expansionProfile(player); return profile == null ? 0L : profile.simplexPoints(); }
        private List<SimplexAdminSuite.PlayerProfile> top(int limit) { return plugin.expansionProfiles().stream().filter(profile -> profile.simplexPoints() > 0L).sorted(Comparator.comparingLong(SimplexAdminSuite.PlayerProfile::simplexPoints).reversed().thenComparing(SimplexAdminSuite.PlayerProfile::displayName, String.CASE_INSENSITIVE_ORDER)).limit(Math.max(1, limit)).collect(Collectors.toList()); }
        private String rankName(long points) { Rank selected = ranks().get(0); for (Rank rank : ranks()) if (points >= rank.threshold) selected = rank; return selected.name; }
        private List<Rank> ranks() {
            List<Rank> ranks = new ArrayList<>();
            for (String line : plugin.getConfig().getStringList("simplex-points.ranks")) {
                String[] split = line.split("\\|", 2); if (split.length < 2) continue;
                try { ranks.add(new Rank(Math.max(0L, Long.parseLong(split[0].trim())), plugin.sanitizeText(split[1], 40))); } catch (Exception ignored) { }
            }
            if (ranks.isEmpty()) ranks.addAll(List.of(new Rank(0, "Newcomer"), new Rank(50, "Explorer"), new Rank(250, "Regular"), new Rank(1000, "Veteran"), new Rank(5000, "Legend")));
            ranks.sort(Comparator.comparingLong(rank -> rank.threshold)); return ranks;
        }
        private String activeTime(Player player) { SimplexAdminSuite.PlayerProfile profile = plugin.expansionProfile(player); return profile == null ? "0m" : humanDuration(profile.pointsActiveMillis()); }
        private String leaderboardPlain(int limit) { List<SimplexAdminSuite.PlayerProfile> top = top(limit); if (top.isEmpty()) return "Noch keine Punkte"; List<String> lines = new ArrayList<>(); for (int i = 0; i < top.size(); i++) lines.add((i + 1) + ". " + top.get(i).displayName() + " " + top.get(i).simplexPoints() + " SP"); return String.join(" • ", lines); }
        private String discordLeaderboard(int limit) { List<SimplexAdminSuite.PlayerProfile> top = top(limit); if (top.isEmpty()) return "_No points have been earned yet_"; List<String> lines = new ArrayList<>(); for (int i = 0; i < top.size(); i++) lines.add("**" + (i + 1) + ".** " + escapeDiscord(top.get(i).displayName()) + " — **" + top.get(i).simplexPoints() + " SP** (" + escapeDiscord(rankName(top.get(i).simplexPoints())) + ")"); return String.join("\n", lines); }
        private void updateSettings(String actor, boolean enabled, int interval, int perInterval, boolean showPoints, boolean showLuckPerms, boolean discordLeaderboard) {
            plugin.getConfig().set("simplex-points.enabled", enabled); plugin.getConfig().set("simplex-points.award-interval-minutes", Math.max(1, Math.min(60, interval)));
            plugin.getConfig().set("simplex-points.points-per-interval", Math.max(1, Math.min(100, perInterval))); plugin.getConfig().set("nameplates.show-points", false);
            plugin.getConfig().set("nameplates.show-luckperms", showLuckPerms); plugin.getConfig().set("simplex-points.discord-leaderboard", discordLeaderboard); plugin.saveConfig();
            refreshDisplays(); plugin.discordPointsChanged(); plugin.expansionAudit(actor, null, "Simplex-Points geändert", "enabled=" + enabled + ", interval=" + interval + ", points=" + perInterval);
        }
        private void refreshDisplays() { for (Player player : Bukkit.getOnlinePlayers()) { plugin.refreshExpansionNameplate(player); plugin.refreshPlayerBoard(player); } }
    }

    private static final class HistoricProfileCounter {
        private static final Pattern UUID_PATTERN = Pattern.compile("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        private final SimplexAdminSuite plugin;
        private final File indexFile;
        private final Set<UUID> known = ConcurrentHashMap.newKeySet();
        private volatile int count;
        private HistoricProfileCounter(SimplexAdminSuite plugin) {
            this.plugin = plugin;
            this.indexFile = new File(plugin.getDataFolder(), "historic-player-index.yml");
            for (SimplexAdminSuite.PlayerProfile profile : plugin.expansionProfiles()) known.add(profile.uniqueId());
            if (indexFile.isFile()) for (String value : YamlConfiguration.loadConfiguration(indexFile).getStringList("known-uuids")) {
                try { known.add(UUID.fromString(value)); } catch (Exception ignored) { }
            }
            this.count = known.size();
        }
        private int count() { return Math.max(count, Math.max(known.size(), plugin.expansionProfiles().size())); }
        private void record(UUID uuid) {
            if (uuid == null || !known.add(uuid)) return;
            count = Math.max(count, known.size());
            Bukkit.getScheduler().runTaskAsynchronously(plugin, this::saveIndex);
        }
        private void stop() { saveIndex(); }
        private void scanAsync() {
            Set<UUID> knownProfiles = plugin.expansionProfiles().stream().map(SimplexAdminSuite.PlayerProfile::uniqueId).collect(Collectors.toSet());
            List<File> worldFolders = new ArrayList<>();
            for (World world : Bukkit.getWorlds()) {
                try {
                    Object value = world.getClass().getMethod("getWorldFolder").invoke(world);
                    if (value instanceof File) worldFolders.add((File) value);
                } catch (Throwable ignored) { }
            }
            File serverRoot = plugin.getDataFolder().getParentFile() == null ? null : plugin.getDataFolder().getParentFile().getParentFile();
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                Set<UUID> unique = new HashSet<>(known);
                unique.addAll(knownProfiles);
                try {
                    File userCache = serverRoot == null ? null : new File(serverRoot, "usercache.json");
                    if (userCache != null && userCache.isFile()) collectUuids(Files.readString(userCache.toPath(), StandardCharsets.UTF_8), unique);
                } catch (Exception ignored) { }
                for (File worldFolder : worldFolders) {
                    try {
                        File playerData = new File(worldFolder, "playerdata"); File[] files = playerData.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".dat"));
                        if (files == null) continue;
                        for (File file : files) try { unique.add(UUID.fromString(file.getName().substring(0, file.getName().length() - 4))); } catch (Exception ignored) { }
                    } catch (Throwable ignored) { }
                }
                known.addAll(unique);
                count = known.size();
                saveIndex();
            });
        }
        private synchronized void saveIndex() {
            try {
                List<String> values = known.stream().map(UUID::toString).sorted().collect(Collectors.toList());
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.set("schema-version", 1);
                yaml.set("count", values.size());
                yaml.set("updated-at", System.currentTimeMillis());
                yaml.set("known-uuids", values);
                saveAtomic(yaml, indexFile);
            } catch (Exception error) { plugin.getLogger().warning("Historischer Spielerindex konnte nicht gespeichert werden: " + error.getMessage()); }
        }
        private static void collectUuids(String text, Set<UUID> target) { Matcher matcher = UUID_PATTERN.matcher(text == null ? "" : text); while (matcher.find()) try { target.add(UUID.fromString(matcher.group())); } catch (Exception ignored) { } }
    }

    private static void saveAtomic(YamlConfiguration yaml, File file) throws Exception {
        File parent = file.getParentFile(); if (parent != null && !parent.exists()) parent.mkdirs();
        File temp = new File(parent == null ? new File(".") : parent, file.getName() + ".tmp"); yaml.save(temp);
        try { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (Exception ignored) { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING); }
    }

    private static String cleanActor(String actor) { String value = actor == null ? "System" : actor.trim(); return value.isEmpty() ? "System" : value.substring(0, Math.min(80, value.length())); }
    private static String normalizeAction(String action) {
        String value = action == null ? "BLOCK" : action.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        if (value.equals("MUTE") || value.equals("MUTE5") || value.equals("MUTE_5")) value = "MUTE_5M";
        return Set.of("BLOCK", "WARN", "MUTE_5M", "KICK").contains(value) ? value : "BLOCK";
    }
    private static String normalizeEvasion(String value) {
        String text = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKD).toLowerCase(Locale.ROOT).replaceAll("\\p{M}+", "");
        text = foldHomoglyphs(text);
        text = text.replace('0', 'o').replace('1', 'i').replace('3', 'e').replace('4', 'a').replace('5', 's').replace('7', 't').replace('8', 'b').replace('9', 'g').replace('@', 'a').replace('$', 's');
        return text.replaceAll("[^\\p{L}\\p{N}]", "");
    }
    private static String foldHomoglyphs(String value) {
        StringBuilder out = new StringBuilder(value == null ? 0 : value.length());
        if (value == null) return "";
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            out.append(switch (c) {
                case 'а', 'α', 'ɑ' -> 'a';
                case 'в', 'β' -> 'b';
                case 'с', 'ϲ' -> 'c';
                case 'е', 'ε' -> 'e';
                case 'һ', 'η' -> 'h';
                case 'і', 'ι', 'ı' -> 'i';
                case 'ј' -> 'j';
                case 'к', 'κ' -> 'k';
                case 'м', 'μ' -> 'm';
                case 'ո' -> 'n';
                case 'о', 'ο' -> 'o';
                case 'р', 'ρ' -> 'p';
                case 'ѕ' -> 's';
                case 'т', 'τ' -> 't';
                case 'х', 'χ' -> 'x';
                case 'у', 'γ' -> 'y';
                default -> c;
            });
        }
        return out.toString();
    }
    private static String collapseRepeats(String value) { return value == null ? "" : value.replaceAll("(.)\\1{2,}", "$1$1"); }
    private static List<String> splitOptions(String value) { if (value == null) return Collections.emptyList(); return List.of(value.split("\\s*\\|\\s*")); }
    private static List<String> cleanOptions(Collection<String> source) { List<String> out = new ArrayList<>(); if (source != null) for (String value : source) { String clean = value == null ? "" : value.trim().replace('\n', ' ').replace('\r', ' '); if (!clean.isEmpty() && !out.contains(clean)) out.add(clean.substring(0, Math.min(120, clean.length()))); if (out.size() >= 8) break; } return out; }
    private static long parseDurationSeconds(String value) { if (value == null) return 3600L; Matcher matcher = Pattern.compile("(?i)^(\\d+)(s|m|h|d)$").matcher(value.trim()); if (!matcher.matches()) return 3600L; long amount; try { amount = Long.parseLong(matcher.group(1)); } catch (Exception ignored) { return 3600L; } return amount * (matcher.group(2).equalsIgnoreCase("s") ? 1L : matcher.group(2).equalsIgnoreCase("m") ? 60L : matcher.group(2).equalsIgnoreCase("h") ? 3600L : 86400L); }
    private static int parseInt(String value, int fallback) { try { return Integer.parseInt(value.trim()); } catch (Exception ignored) { return fallback; } }
    private static int next(int current, int[] values) { for (int i = 0; i < values.length; i++) if (current == values[i]) return values[(i + 1) % values.length]; return values[0]; }
    private static String humanDuration(long millis) { long seconds = Math.max(0L, millis) / 1000L; long days = seconds / 86400L; long hours = seconds % 86400L / 3600L; long minutes = seconds % 3600L / 60L; long secs = seconds % 60L; if (days > 0) return days + "d " + hours + "h"; if (hours > 0) return hours + "h " + minutes + "m"; if (minutes > 0) return minutes + "m " + secs + "s"; return secs + "s"; }
    private static String shorten(String value, int max) { String text = value == null ? "" : value.replace('\n', ' ').replace('\r', ' '); return text.length() <= max ? text : text.substring(0, Math.max(1, max - 1)) + "…"; }
    private static List<String> wrapLore(String value, int width) { String text = value == null ? "" : value; List<String> lines = new ArrayList<>(); while (text.length() > width) { int split = text.lastIndexOf(' ', width); if (split < width / 2) split = width; lines.add(text.substring(0, split)); text = text.substring(split).trim(); } if (!text.isEmpty()) lines.add(text); return lines; }
    private static List<String> startsWith(Collection<String> values, String prefix) { String search = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT); return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(search)).collect(Collectors.toList()); }
    private static void sendActionBar(Player player, String text) { try { player.getClass().getMethod("sendActionBar", String.class).invoke(player, text); } catch (Throwable ignored) { } }
    private static String escapeDiscord(String value) { return (value == null ? "" : ChatColor.stripColor(value)).replace("\\", "\\\\").replace("*", "\\*").replace("_", "\\_").replace("`", "\\`"); }
}
