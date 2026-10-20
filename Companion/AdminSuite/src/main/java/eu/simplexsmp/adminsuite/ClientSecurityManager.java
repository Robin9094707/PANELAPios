package eu.simplexsmp.adminsuite;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Java plugin-channel inventory and conservative mod-signal similarity.
 *
 * <p>There is intentionally no client-specific detection or punishment code here. A server only
 * sees registered plugin channels, not a canonical installed-mod list. Derived names and overlap
 * values are therefore labelled as unverified hints and are structurally excluded from Alt
 * correlation, Deep-Link graphs and every enforcement decision.</p>
 */
final class ClientSecurityManager implements Listener {
    private static final int DEFAULT_MAX_CHANNELS = 128;
    private static final String CHANNELS_TITLE = "§0Java-Kanäle • ";
    private static final String SETTINGS_TITLE = "§8Client- und Gerätediagnose";
    private static final Set<String> COMMON_SIGNALS = Set.of("fabric", "forge", "neoforge", "quilt", "minecraft", "brand");

    private final SimplexAdminSuite plugin;
    private final Set<UUID> scheduledCaptures = ConcurrentHashMap.newKeySet();
    private final Map<UUID, LinkedHashSet<String>> liveChannels = new ConcurrentHashMap<>();
    private final Map<UUID, ChannelView> channelViews = new ConcurrentHashMap<>();
    private BukkitTask scanTask;

    ClientSecurityManager(SimplexAdminSuite plugin) {
        this.plugin = plugin;
    }

    void start() {
        stopTaskOnly();
        long seconds = Math.max(10L, plugin.getConfig().getLong("client-security.channels.refresh-seconds", 30L));
        scanTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshOnlinePlayers, 40L, seconds * 20L);
        for (Player player : Bukkit.getOnlinePlayers()) onPlayerJoin(player);
    }

    void stop() {
        stopTaskOnly();
        liveChannels.clear();
        scheduledCaptures.clear();
        channelViews.clear();
    }

    void reload() { stopTaskOnly(); start(); }

    private void stopTaskOnly() {
        if (scanTask != null) try { scanTask.cancel(); } catch (Exception ignored) { }
        scanTask = null;
    }

    void onPlayerJoin(Player player) {
        if (player == null) return;
        // PlayerProfile.save rewrites the profile without obsolete client-specific keys.
        SimplexAdminSuite.PlayerProfile profile = plugin.clientSecurityProfile(player.getUniqueId());
        if (profile != null) plugin.clientSecuritySaveProfile(profile);
        if (isBedrock(player) || !enabled()) return;
        long delay = Math.max(20L, plugin.getConfig().getLong("client-security.channels.initial-delay-ticks", 60L));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (online(player) && !isBedrock(player) && enabled()) capture(player, true);
        }, delay);
    }

    void onPlayerQuit(Player player) {
        if (player == null) return;
        scheduledCaptures.remove(player.getUniqueId());
        liveChannels.remove(player.getUniqueId());
        channelViews.remove(player.getUniqueId());
    }

    boolean handleChannelsCommand(CommandSender sender, String[] args) {
        if (!plugin.has(sender, "simplexadminsuite.client.channels")) {
            sender.sendMessage(plugin.prefix() + "Dafür fehlt dir simplexadminsuite.client.channels."); return true;
        }
        if (args.length == 0) { sender.sendMessage(plugin.prefix() + "Nutze /sas channels <Spieler> oder /sas channels scan <Spieler>."); return true; }
        boolean scan = args[0].equalsIgnoreCase("scan");
        String query = scan ? args.length > 1 ? args[1] : "" : args[0];
        SimplexAdminSuite.PlayerProfile profile = plugin.clientSecurityFindProfile(query);
        if (profile == null) { plugin.menuNotice(sender,plugin.prefix() + "Spielerprofil nicht gefunden."); return true; }
        Player online = Bukkit.getPlayer(profile.uniqueId());
        if (scan) {
            if (online == null || isBedrock(online)) { sender.sendMessage(plugin.prefix() + "Ein Live-Scan ist nur bei einem online befindlichen Java-Spieler möglich."); return true; }
            capture(online, true);
        }
        if (sender instanceof Player) openChannels((Player) sender, profile.uniqueId(), 0); else sendChannels(sender, profile);
        return true;
    }

    boolean handleSecurityCommand(CommandSender sender, String[] args) {
        if (!plugin.has(sender, "simplexadminsuite.clientsecurity.admin")) {
            sender.sendMessage(plugin.prefix() + "Dafür fehlt dir simplexadminsuite.clientsecurity.admin."); return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            if (sender instanceof Player) openSettings((Player) sender); else sender.sendMessage(plugin.prefix() + statusText());
            return true;
        }
        if (args[0].equalsIgnoreCase("on") || args[0].equalsIgnoreCase("off")) {
            setEnabled(sender.getName(), args[0].equalsIgnoreCase("on"));
            sender.sendMessage(plugin.prefix() + statusText());
        } else sender.sendMessage(plugin.prefix() + "Nutze /sas clientsecurity [status|on|off].");
        return true;
    }

    List<String> tabComplete(String[] args) {
        if (args.length == 2) return filter(List.of("status", "on", "off"), args[1]);
        if (args.length == 3) return filter(plugin.clientSecurityPlayerNames(), args[2]);
        return Collections.emptyList();
    }

    void capture(Player player, boolean persist) {
        if (player == null || isBedrock(player) || !enabled() || !plugin.getConfig().getBoolean("client-security.channels.enabled", true)) return;
        int maximum = Math.max(16, Math.min(512, plugin.getConfig().getInt("client-security.channels.max-stored", DEFAULT_MAX_CHANNELS)));
        LinkedHashSet<String> channels = new LinkedHashSet<>();
        try {
            Object value = player.getClass().getMethod("getListeningPluginChannels").invoke(player);
            if (value instanceof Collection<?>) {
                List<String> sorted = ((Collection<?>) value).stream().map(String::valueOf).map(this::normalizeChannel)
                        .filter(text -> !text.isBlank()).distinct().sorted(String.CASE_INSENSITIVE_ORDER).limit(maximum).collect(Collectors.toList());
                channels.addAll(sorted);
            }
        } catch (Throwable error) {
            if (plugin.getConfig().getBoolean("client-security.debug", false)) plugin.getLogger().info("Plugin-Kanäle für " + player.getName() + " nicht lesbar: " + error.getClass().getSimpleName());
        }
        liveChannels.put(player.getUniqueId(), channels);
        SimplexAdminSuite.PlayerProfile profile = plugin.clientSecurityProfile(player.getUniqueId());
        if (profile == null) return;
        List<String> mods = inferMods(profile.javaClientBrandValue(), channels);
        profile.updateJavaChannels(channels, mods, System.currentTimeMillis());
        plugin.clientSecurityUpdateDiagnostics(player, profile, channels);
        if (persist && plugin.getConfig().getBoolean("client-security.channels.persist", true)) plugin.clientSecuritySaveProfile(profile);
    }

    @EventHandler
    public void onClientOptions(com.destroystokyo.paper.event.player.PlayerClientOptionsChangeEvent event) { scheduleCapture(event.getPlayer()); }
    @EventHandler
    public void onRegister(org.bukkit.event.player.PlayerRegisterChannelEvent event) { scheduleCapture(event.getPlayer()); }
    @EventHandler
    public void onUnregister(org.bukkit.event.player.PlayerUnregisterChannelEvent event) { scheduleCapture(event.getPlayer()); }
    private void scheduleCapture(Player player) {
        if(player==null||isBedrock(player)||!enabled()||!scheduledCaptures.add(player.getUniqueId())) return;
        Bukkit.getScheduler().runTaskLater(plugin,()->{scheduledCaptures.remove(player.getUniqueId());if(online(player)&&enabled()) capture(player,true);},1L);
    }

    private void refreshOnlinePlayers() {
        if (!enabled()) return;
        for (Player player : Bukkit.getOnlinePlayers()) if (!isBedrock(player)) capture(player, true);
    }

    List<String> inferMods(String brand, Collection<String> channels) {
        Set<String> mods = new LinkedHashSet<>();
        String lowerBrand = brand == null ? "" : brand.toLowerCase(Locale.ROOT);
        if (lowerBrand.contains("fabric")) mods.add("Fabric Loader");
        if (lowerBrand.contains("quilt")) mods.add("Quilt Loader");
        if (lowerBrand.contains("neoforge")) mods.add("NeoForge");
        else if (lowerBrand.contains("forge")) mods.add("Forge");
        if (lowerBrand.contains("lunar")) mods.add("Lunar Client");
        if (lowerBrand.contains("labymod")) mods.add("LabyMod");
        if (lowerBrand.contains("badlion")) mods.add("Badlion Client");
        if (lowerBrand.contains("feather")) mods.add("Feather Client");
        for (String raw : channels == null ? Collections.<String>emptyList() : channels) {
            String channel = raw.toLowerCase(Locale.ROOT);
            match(channel, mods, "fabric", "Fabric Loader");
            match(channel, mods, "quilt", "Quilt Loader");
            match(channel, mods, "neoforge", "NeoForge");
            if (!channel.contains("neoforge")) match(channel, mods, "forge", "Forge");
            match(channel, mods, "lunar", "Lunar Client");
            match(channel, mods, "labymod", "LabyMod");
            match(channel, mods, "badlion", "Badlion Client");
            match(channel, mods, "feather", "Feather Client");
            match(channel, mods, "essential", "Essential");
            match(channel, mods, "voicechat", "Simple Voice Chat");
            match(channel, mods, "plasmovoice", "Plasmo Voice");
            match(channel, mods, "journeymap", "JourneyMap");
            match(channel, mods, "xaero", "Xaero's Maps");
            match(channel, mods, "jei", "Just Enough Items");
            match(channel, mods, "roughlyenoughitems", "Roughly Enough Items");
            match(channel, mods, "rei", "Roughly Enough Items");
            match(channel, mods, "emi", "EMI");
            match(channel, mods, "wdl", "World Downloader signal");
            match(channel, mods, "distanthorizons", "Distant Horizons");
            match(channel, mods, "vivecraft", "Vivecraft");
            match(channel, mods, "worldeditcui", "WorldEdit CUI");
            match(channel, mods, "litematica", "Litematica");
            match(channel, mods, "minihud", "MiniHUD");
            match(channel, mods, "carpet", "Carpet");
            match(channel, mods, "iris", "Iris");
            match(channel, mods, "sodium", "Sodium");
            match(channel, mods, "luckperms", "LuckPerms client channel");
        }
        List<String> ordered = new ArrayList<>(mods);
        ordered.sort(String.CASE_INSENSITIVE_ORDER);
        return ordered;
    }

    List<Similarity> similarities(SimplexAdminSuite.PlayerProfile source, int limit) {
        if (source == null || !plugin.getConfig().getBoolean("client-security.similarity.enabled", true)) return Collections.emptyList();
        int minimumSignals = Math.max(4, plugin.getConfig().getInt("client-security.similarity.minimum-signals", 6));
        int minimumShared = Math.max(3, plugin.getConfig().getInt("client-security.similarity.minimum-shared", 4));
        double minimumJaccard = Math.max(0.40D, Math.min(0.95D, plugin.getConfig().getDouble("client-security.similarity.minimum-jaccard", 0.68D)));
        Set<String> first = signature(source);
        if (first.size() < minimumSignals) return Collections.emptyList();
        List<Similarity> matches = new ArrayList<>();
        for (SimplexAdminSuite.PlayerProfile candidate : plugin.expansionProfiles()) {
            if (candidate.uniqueId().equals(source.uniqueId())) continue;
            Set<String> second = signature(candidate);
            if (second.size() < minimumSignals) continue;
            Set<String> intersection = new HashSet<>(first); intersection.retainAll(second);
            if (intersection.size() < minimumShared) continue;
            Set<String> union = new HashSet<>(first); union.addAll(second);
            double jaccard = union.isEmpty() ? 0.0D : intersection.size() / (double) union.size();
            if (jaccard < minimumJaccard) continue;
            matches.add(new Similarity(candidate.uniqueId(), candidate.displayName(), jaccard, intersection.size(), first.size(), second.size()));
        }
        matches.sort(Comparator.comparingDouble((Similarity value) -> value.jaccard).reversed().thenComparing(value -> value.name, String.CASE_INSENSITIVE_ORDER));
        return matches.stream().limit(Math.max(1, limit)).collect(Collectors.toList());
    }

    private Set<String> signature(SimplexAdminSuite.PlayerProfile profile) {
        Set<String> values = new LinkedHashSet<>();
        for (String mod : profile.javaDetectedModsSnapshot()) {
            String normalized = normalizeSignal(mod);
            if (!normalized.isBlank() && !COMMON_SIGNALS.contains(normalized)) values.add("mod:" + normalized);
        }
        for (String channel : profile.javaRegisteredChannelsSnapshot()) {
            String namespace = channel.contains(":") ? channel.substring(0, channel.indexOf(':')) : channel;
            String normalized = normalizeSignal(namespace);
            if (!normalized.isBlank() && !COMMON_SIGNALS.contains(normalized) && !normalized.equals("register") && !normalized.equals("unregister")) values.add("ns:" + normalized);
        }
        return values;
    }

    private String normalizeSignal(String value) { return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""); }
    private void match(String channel, Set<String> mods, String needle, String label) {
        String namespace=channel.contains(":")?channel.substring(0,channel.indexOf(':')):channel;
        boolean match=namespace.equals(needle)||namespace.startsWith(needle+"_")||namespace.startsWith(needle+"-");
        if(needle.equals("xaero")) match=match||namespace.equals("xaerominimap")||namespace.equals("xaeroworldmap");
        if(match) mods.add(label);
    }
    private String normalizeChannel(String raw) { String value = raw == null ? "" : raw.replace('\n', ' ').replace('\r', ' ').trim(); return value.length() <= 160 ? value : value.substring(0, 160); }

    void openChannels(Player admin, UUID targetUuid, int page) {
        if (!plugin.adminMenuAllowed(admin, "clientsecuritymanager.openchannels")) return;
        if (admin == null || !plugin.has(admin, "simplexadminsuite.client.channels")) return;
        SimplexAdminSuite.PlayerProfile profile = plugin.clientSecurityProfile(targetUuid);
        if (profile == null) { plugin.menuNotice(admin,plugin.prefix() + "Spielerprofil nicht gefunden."); return; }
        if (plugin.openBedrockClientChannels(admin, targetUuid)) return;
        List<String> channels = profile.javaRegisteredChannelsSnapshot();
        int pageSize = 36;
        int maxPage = Math.max(0, (channels.size() - 1) / pageSize);
        int selected = Math.max(0, Math.min(maxPage, page));
        Inventory inventory = LiveMenus.create(null, 54, CHANNELS_TITLE + trim(profile.displayName(), 18));
        plugin.fill(inventory);
        int start = selected * pageSize;
        for (int index = start; index < channels.size() && index < start + pageSize; index++) {
            String channel = channels.get(index);
            inventory.setItem(index - start, plugin.item(Material.PAPER, "&f" + trim(channel, 42), "&7Registrierter Plugin-Kanal", "&8Kein Beweis für eine installierte Mod."));
        }
        inventory.setItem(36, plugin.item(Material.NAME_TAG, "&dAbgeleitete Mod-Hinweise", profile.javaDetectedModsSnapshot().isEmpty() ? "&7Keine Hinweise" : "&f" + trim(String.join(", ", profile.javaDetectedModsSnapshot()), 180)));
        List<Similarity> similarities = similarities(profile, 5);
        List<String> lore = new ArrayList<>();
        lore.add("&7Nur bei mindestens vielen aussagekräftigen Signalen.");
        lore.add("&cVollständig von der Alt-Erkennung getrennt.");
        for (Similarity similarity : similarities) lore.add("&7• &f" + similarity.name + " &e" + Math.round(similarity.jaccard * 100.0D) + "% &8(" + similarity.shared + " gemeinsam)");
        if (similarities.isEmpty()) lore.add("&7Keine belastbare große Signaturüberschneidung.");
        inventory.setItem(37, plugin.item(Material.SPYGLASS, "&eSeparate Client-Ähnlichkeit", lore));
        inventory.setItem(38, plugin.item(Material.COMPARATOR, "&bJava-Client-Einstellungen", plugin.clientSecurityDiagnosticsText(profile).lines().map(v -> "&7"+v).collect(Collectors.toList())));
        Player online = Bukkit.getPlayer(targetUuid);
        if(plugin.javaProbeTarget(targetUuid)&&plugin.has(admin,"simplexadminsuite.clientcheck"))inventory.setItem(40,plugin.item(Material.SHIELD,"&bClient-Prüfungen & Verlauf","&7Gespeicherte Prüfungen und Mod-Signale."));
        inventory.setItem(45, plugin.item(Material.ARROW, "&eVorherige Seite"));
        inventory.setItem(47, plugin.item(Material.COMPARATOR, "&bKanaleinstellungen"));
        inventory.setItem(49, plugin.item(Material.BARRIER, "&cZurück"));
        if (online != null && !isBedrock(online)) inventory.setItem(51, plugin.item(Material.CLOCK, "&aLive neu scannen"));
        inventory.setItem(53, plugin.item(Material.ARROW, "&eNächste Seite"));
        channelViews.put(admin.getUniqueId(), new ChannelView(targetUuid, selected));
        LiveMenus.show(admin,inventory);
    }

    void openSettings(Player admin) {
        if (!plugin.adminMenuAllowed(admin, "clientsecuritymanager.opensettings")) return;
        if (admin == null || !plugin.has(admin, "simplexadminsuite.clientsecurity.admin")) return;
        if (plugin.openBedrockClientSecurity(admin)) return;
        Inventory inventory = LiveMenus.create(null, 27, SETTINGS_TITLE);
        plugin.fill(inventory);
        inventory.setItem(10, plugin.item(enabled() ? Material.LIME_DYE : Material.RED_DYE, enabled() ? "&aKanalerfassung aktiviert" : "&cKanalerfassung deaktiviert", "&7Klicken zum Umschalten"));
        inventory.setItem(12, plugin.item(plugin.getConfig().getBoolean("client-security.channels.persist", true) ? Material.LIME_DYE : Material.GRAY_DYE, "&eKanäle persistent speichern", "&7Status: " + (plugin.getConfig().getBoolean("client-security.channels.persist", true) ? "&aJa" : "&cNein")));
        inventory.setItem(14, plugin.item(plugin.getConfig().getBoolean("client-security.similarity.enabled", true) ? Material.LIME_DYE : Material.GRAY_DYE, "&eMod-Signaturdiagnose", "&7Status: " + (plugin.getConfig().getBoolean("client-security.similarity.enabled", true) ? "&aAktiv" : "&cInaktiv"), "&7Nur separate Client-Diagnose.", "&cNie Teil der Alt-Erkennung."));
        inventory.setItem(17, plugin.item(Material.SHIELD, "&bOptionale Sicherheitsdetektoren", "&7Fingerprint, Verschleierung, Klickmuster und Freecam"));
        inventory.setItem(16, plugin.item(Material.WRITABLE_BOOK, "&bTransparenz", "&7Paper liefert registrierte Plugin-Kanäle,", "&7keine vollständige Modliste.", "&7Alt-Verknüpfungen nutzen ausschließlich", "&7IP-Historie und Bedrock-Identitäten."));
        int[] diagnosticSlots = {18,19,20,21,23,24,25};
        for (int i=0;i<SimplexAdminSuite.DIAGNOSTIC_SETTINGS.size();i++) {
            String key=SimplexAdminSuite.DIAGNOSTIC_SETTINGS.get(i);
            inventory.setItem(diagnosticSlots[i],plugin.item(plugin.diagnosticSettingEnabled(key)?Material.LIME_DYE:Material.GRAY_DYE,
                "&b"+SimplexAdminSuite.diagnosticSettingLabel(key),"&7Status: "+(plugin.diagnosticSettingEnabled(key)?"&aAN":"&cAUS"),"&7Klick zum Umschalten.","&7Neue Bedrock-Felder sind reine Zusatzdiagnosen."));
        }
        inventory.setItem(22, plugin.item(Material.BARRIER, "&cZum Startmenü", "&7Zurück zur Simplex Admin Suite."));
        LiveMenus.show(admin,inventory);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        plugin.asAdmin(event.getWhoClicked().getName(),()->handleAuditedInventoryClick(event));
    }
    private void handleAuditedInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player admin = (Player) event.getWhoClicked();
        String title = title(event);
        if (SETTINGS_TITLE.equals(title)) {
            event.setCancelled(true);
            if(!plugin.has(admin,"simplexadminsuite.clientsecurity.admin")) return;
            int slot = event.getRawSlot();
            int[] diagnosticSlots = {18,19,20,21,23,24,25};
            for(int i=0;i<diagnosticSlots.length;i++) if(slot==diagnosticSlots[i]) {
                String key=SimplexAdminSuite.DIAGNOSTIC_SETTINGS.get(i);
                plugin.setDiagnosticSetting(admin.getName(),key,plugin.diagnosticSettingEnabled(key)?"off":"on"); openSettings(admin); return;
            }
            if (slot == 10) { setEnabled(admin.getName(), !enabled()); openSettings(admin); }
            else if (slot == 12) { toggle("client-security.channels.persist"); openSettings(admin); }
            else if (slot == 14) { toggle("client-security.similarity.enabled"); openSettings(admin); }
            else if (slot == 17) plugin.detectors.home(admin);
            else if (slot == 22) plugin.openMainFromModule(admin);
            return;
        }
        if (!title.startsWith(CHANNELS_TITLE)) return;
        event.setCancelled(true);
        ChannelView view = channelViews.get(admin.getUniqueId());
        if (view == null) return;
        if (event.getRawSlot() == 40) plugin.openClientProbe(admin,view.target);
        else if (event.getRawSlot() == 45) openChannels(admin, view.target, view.page - 1);
        else if (event.getRawSlot() == 47) openSettings(admin);
        else if (event.getRawSlot() == 49) plugin.clientSecurityOpenPlayerCheck(admin, view.target);
        else if (event.getRawSlot() == 51) { Player online = Bukkit.getPlayer(view.target); if (online != null && !isBedrock(online)) capture(online, true); openChannels(admin, view.target, view.page); }
        else if (event.getRawSlot() == 53) openChannels(admin, view.target, view.page + 1);
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        String title = event.getView() == null ? "" : LiveMenus.title(event.getView());
        if (SETTINGS_TITLE.equals(title) || title.startsWith(CHANNELS_TITLE)) event.setCancelled(true);
    }

    String discordChannels(String playerQuery) {
        SimplexAdminSuite.PlayerProfile profile = plugin.clientSecurityFindProfile(playerQuery);
        if (profile == null) return "Spielerprofil nicht gefunden.";
        StringBuilder text = new StringBuilder("**Java-Kanäle für ").append(profile.displayName()).append("**\n")
                .append("Registriert: **").append(profile.javaRegisteredChannelsSnapshot().size()).append("**\n")
                .append("Mod-Hinweise: ").append(profile.javaDetectedModsSnapshot().isEmpty() ? "_Keine_" : String.join(", ", profile.javaDetectedModsSnapshot())).append("\n")
                .append("Kanäle: ").append(profile.javaRegisteredChannelsSnapshot().isEmpty() ? "_Keine_" : profile.javaRegisteredChannelsSnapshot().stream().limit(40).collect(Collectors.joining(", ")))
                .append("\nClient-Einstellungen: ").append(plugin.clientSecurityDiagnosticsText(profile))
                .append("\n_Registrierte Kanäle/Mod-Hinweise sind unverifiziert und vollständig aus der Alt-Erkennung ausgeschlossen._");
        List<Similarity> similarities = similarities(profile, 5);
        if (!similarities.isEmpty()) text.append("\nGroße Signaturüberschneidungen: ").append(similarities.stream().map(value -> value.name + " " + Math.round(value.jaccard * 100.0D) + "%").collect(Collectors.joining(", ")));
        return text.toString();
    }

    String discordSecurity(String actor, String operation, String playerQuery) {
        String op = operation == null ? "status" : operation.toLowerCase(Locale.ROOT);
        if (op.equals("status")) return statusText();
        if (op.equals("on") || op.equals("off")) { setEnabled(actor, op.equals("on")); return statusText(); }
        if (op.equals("scan")) {
            SimplexAdminSuite.PlayerProfile profile = plugin.clientSecurityFindProfile(playerQuery);
            Player online = profile == null ? null : Bukkit.getPlayer(profile.uniqueId());
            if (online == null || isBedrock(online)) return "Live-Scan nur für online befindliche Java-Spieler.";
            capture(online, true); return discordChannels(profile.uniqueId().toString());
        }
        return "Mögliche Operationen: `status`, `on`, `off`, `scan`.";
    }

    String statusText() {
        long profiles = plugin.expansionProfiles().stream().filter(profile -> !profile.javaRegisteredChannelsSnapshot().isEmpty()).count();
        return "Java-Kanalerfassung " + (enabled() ? "aktiv" : "inaktiv") + " • " + profiles + " Profile mit Kanälen • separate Signaturdiagnose "
                + (plugin.getConfig().getBoolean("client-security.similarity.enabled", true) ? "aktiv" : "inaktiv") + " • vollständig von Alt-Erkennung getrennt";
    }

    String bedrockChannelsText(SimplexAdminSuite.PlayerProfile profile) {
        if (profile == null) return "§cSpielerprofil nicht gefunden.";
        String channels = profile.javaRegisteredChannelsSnapshot().isEmpty() ? "Keine" : String.join("\n§8• §f", profile.javaRegisteredChannelsSnapshot());
        String mods = profile.javaDetectedModsSnapshot().isEmpty() ? "Keine" : String.join(", ", profile.javaDetectedModsSnapshot());
        List<Similarity> similarities = similarities(profile, 5);
        String overlap = similarities.isEmpty() ? "Keine große Überschneidung" : similarities.stream().map(value -> value.name + " " + Math.round(value.jaccard * 100.0D) + "%").collect(Collectors.joining(", "));
        return "§bClient-Einstellungen§f\n" + plugin.clientSecurityDiagnosticsText(profile) + "\n\n§7Registrierte Kanäle: §f" + profile.javaRegisteredChannelsSnapshot().size() + "\n§7Mod-Hinweise: §f" + mods
                + "\n§7Große Signaturüberschneidungen: §f" + overlap + "\n\n§8• §f" + channels
                + "\n\n§7Hinweis: Das sind unverifizierte Client-Diagnosen. Sie erzeugen niemals einen Alt-Treffer oder Deep-Link.";
    }

    boolean enabled() { return plugin.getConfig().getBoolean("client-security.enabled", true); }

    void setEnabled(String actor, boolean enabled) {
        plugin.getConfig().set("client-security.enabled", enabled); plugin.saveConfig();
        plugin.clientSecurityAudit(actor, null, "Java-Kanalerfassung", enabled ? "aktiviert" : "deaktiviert");
        if (!enabled) liveChannels.clear(); else refreshOnlinePlayers();
    }

    private void sendChannels(CommandSender sender, SimplexAdminSuite.PlayerProfile profile) {
        sender.sendMessage(plugin.prefix() + "Java-Kanäle für " + profile.displayName() + ": " + profile.javaRegisteredChannelsSnapshot().size());
        sender.sendMessage("Mod-Hinweise: " + (profile.javaDetectedModsSnapshot().isEmpty() ? "Keine" : String.join(", ", profile.javaDetectedModsSnapshot())));
        for (String channel : profile.javaRegisteredChannelsSnapshot()) sender.sendMessage(" - " + channel);
        sender.sendMessage("Hinweis: unverifizierte Client-Diagnose; vollständig aus Alt-Erkennung und Deep-Links ausgeschlossen.");
    }

    private boolean isBedrock(Player player) { return plugin.isBedrockClient(player); }
    private boolean online(Player player) { return player != null && Bukkit.getPlayer(player.getUniqueId()) != null; }
    private void toggle(String path) { plugin.getConfig().set(path, !plugin.getConfig().getBoolean(path, true)); plugin.saveConfig(); }
    private static String trim(String value, int max) { String text = value == null ? "" : value; return text.length() <= max ? text : text.substring(0, max); }
    private static String title(InventoryClickEvent event) { return event.getView() == null ? "" : LiveMenus.title(event.getView()); }
    private static List<String> filter(Collection<String> values, String prefix) { String start = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT); return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(start)).sorted(String.CASE_INSENSITIVE_ORDER).limit(80).collect(Collectors.toList()); }

    static final class Similarity {
        final UUID uuid; final String name; final double jaccard; final int shared, firstSignals, secondSignals;
        Similarity(UUID uuid, String name, double jaccard, int shared, int firstSignals, int secondSignals) { this.uuid = uuid; this.name = name; this.jaccard = jaccard; this.shared = shared; this.firstSignals = firstSignals; this.secondSignals = secondSignals; }
    }
    private static final class ChannelView { final UUID target; final int page; ChannelView(UUID target, int page) { this.target = target; this.page = page; } }
}
