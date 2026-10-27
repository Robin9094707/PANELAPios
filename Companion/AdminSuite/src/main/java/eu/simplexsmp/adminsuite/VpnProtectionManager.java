package eu.simplexsmp.adminsuite;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.inventory.Inventory;

import java.io.File;
import java.net.InetAddress;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

final class VpnProtectionManager implements Listener {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss", Locale.GERMANY)
            .withZone(ZoneId.systemDefault());
    private final SimplexAdminSuite plugin;
    private final File folder;
    private final VpnWhitelistStore whitelist;
    private final VpnDetectionLog detectionLog;
    private final Map<String, Cooldown> reconnectCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, List<SimplexAdminSuite.VpnPlayerRef>> playerViews = new ConcurrentHashMap<>();
    private final Map<UUID, List<VpnWhitelistStore.Entry>> whitelistViews = new ConcurrentHashMap<>();
    private final Map<UUID, List<VpnDetectionLog.Detection>> detectionViews = new ConcurrentHashMap<>();
    private final AtomicLong checked = new AtomicLong();
    private final AtomicLong blocked = new AtomicLong();
    private final AtomicLong bypassed = new AtomicLong();
    private final AtomicLong suspiciousAllowed = new AtomicLong();
    private volatile String discordInvite="";
    private volatile VpnSettings settings;
    private volatile VpnIpCheckService checks;

    VpnProtectionManager(SimplexAdminSuite plugin) {
        this.plugin = plugin;
        this.folder = new File(plugin.getDataFolder(), "vpn");
        if (!folder.exists()) folder.mkdirs();
        this.whitelist = new VpnWhitelistStore(new File(folder, "whitelist.txt"));
        this.whitelist.load();
        this.detectionLog = new VpnDetectionLog(new File(folder, "detections.jsonl"), 5000);
        importLegacyWhitelist();
        reload();
    }

    synchronized void reload() {
        whitelist.load();
        importLegacyWhitelist();
        normalizeLegacyWhitelist();
        settings = new VpnSettings(plugin.getConfig());
        refreshInvite();
        detectionLog.setMaximumEntries(settings.maxLogEntries());
        VpnIpCheckService previous = checks;
        checks = new VpnIpCheckService(settings, plugin.getLogger());
        if (previous != null) previous.close();
        reconnectCooldowns.clear();
    }

    synchronized void stop() {
        VpnIpCheckService current = checks;
        checks = null;
        if (current != null) current.close();
        reconnectCooldowns.clear();
        playerViews.clear();
        whitelistViews.clear();
        detectionViews.clear();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        VpnSettings currentSettings = settings;
        VpnIpCheckService currentChecks = checks;
        if (event == null || event.getLoginResult()!=AsyncPlayerPreLoginEvent.Result.ALLOWED || currentSettings == null || currentChecks == null || !currentSettings.enabled()) return;
        String name = event.getName() == null ? "unknown" : event.getName();
        UUID uuid = event.getUniqueId();
        if(plugin.emergency(uuid))return;
        InetAddress address = event.getAddress();
        String ip = address == null ? "unknown" : address.getHostAddress();
        boolean bedrock = isBedrock(uuid, name, currentSettings);

        if (whitelist.isWhitelisted(uuid, name)) {
            bypassed.incrementAndGet();
            if (currentSettings.logAllJoins()) record(new VpnDetectionLog.Detection(System.currentTimeMillis(), name, uuid, ip,
                    bedrock, false, "WHITELIST", "", 0, 0, "Whitelist-Bypass vor jeder externen API-Prüfung.", "keine"), false);
            return;
        }

        String cooldownKey = uuid == null ? name.toLowerCase(Locale.ROOT) : uuid.toString();
        Cooldown cooldown = reconnectCooldowns.get(cooldownKey);
        long now = System.currentTimeMillis();
        if (cooldown != null && cooldown.until > now) {
            VpnDetectionLog.Detection detection = new VpnDetectionLog.Detection(now, name, uuid, ip, bedrock, true,
                    "COOLDOWN", "", 0, 0, "Wiederverbindungs-Cooldown nach einer VPN-/Proxy-Blockierung.", "gespeicherte Blockierung");
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, disconnectMessage(bedrock,plugin.profileUiText(event.getUniqueId(),currentSettings.kickMessage(bedrock, true)),discordInvite));
            blocked.incrementAndGet();
            record(detection, true);
            return;
        }
        if (cooldown != null) reconnectCooldowns.remove(cooldownKey, cooldown);

        checked.incrementAndGet();
        VpnCheckResult result = currentChecks.check(ip, false);
        boolean apiFailureBlock = !result.localAddress() && result.allProvidersFailed() && !currentSettings.failOpen();
        boolean vpnBlock = !result.localAddress() && currentSettings.blockVpn() && result.positive();
        boolean shouldBlock = apiFailureBlock || vpnBlock;
        String decision = shouldBlock ? (apiFailureBlock ? "API_FAILURE_BLOCK" : "VPN_BLOCK")
                : result.positive() ? "SUSPICIOUS_ALLOWED" : "ALLOWED";
        VpnDetectionLog.Detection detection = new VpnDetectionLog.Detection(now, name, uuid, ip, bedrock, shouldBlock,
                decision, result.countryCode(), result.signals(), result.riskScore(), result.reason(), result.providerSummary());
        if (shouldBlock) {
            int cooldownSeconds = currentSettings.reconnectCooldownSeconds();
            if (cooldownSeconds > 0) reconnectCooldowns.put(cooldownKey, new Cooldown(now + cooldownSeconds * 1000L));
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, disconnectMessage(bedrock,plugin.profileUiText(event.getUniqueId(),currentSettings.kickMessage(bedrock, false)),discordInvite));
            blocked.incrementAndGet();
            record(detection, true);
        } else {
            if (result.positive()) suspiciousAllowed.incrementAndGet();
            if (currentSettings.logAllJoins() || result.positive() || result.allProvidersFailed()) record(detection, result.positive());
        }
    }

    void refreshInvite(){try{discordInvite="https://discord.gg/"+DiscordInvite.parseCode(plugin.getConfig().getString("community.discord-invite-code","2gAsymnXfd"));}catch(IllegalArgumentException invalid){discordInvite="";}}
    static net.kyori.adventure.text.Component disconnectMessage(boolean bedrock,String reason,String invite){
        if(invite==null||invite.isBlank())return net.kyori.adventure.text.Component.text(reason);
        if(bedrock)return net.kyori.adventure.text.Component.text(invite);
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(reason+"\n\n§bDiscord: ")
            .append(net.kyori.adventure.text.Component.text(invite).clickEvent(net.kyori.adventure.text.event.ClickEvent.openUrl(invite)));
    }

    boolean handleCommand(CommandSender sender, String label, String[] args) {
        String[] values = args == null ? new String[0] : args;
        if (!allowed(sender, "simplexadminsuite.vpn.view", "simplexvpn.status")) return true;
        if (values.length == 0 || is(values[0], "menu", "menü", "gui")) {
            if (sender instanceof Player) open((Player) sender);
            else sendStatus(sender);
            return true;
        }
        String action = values[0].toLowerCase(Locale.ROOT);
        if (is(action, "status", "zustand")) { sendStatus(sender); return true; }
        if (is(action, "help", "hilfe", "?")) { sendHelp(sender, label); return true; }
        if (is(action, "reload", "neu-laden", "neuladen")) {
            if (!allowed(sender, "simplexadminsuite.vpn.manage", "simplexvpn.reload")) return true;
            reload();
            plugin.menuNotice(sender,plugin.prefix() + "VPN-Konfiguration, Whitelist und Provider wurden neu geladen.");
            plugin.vpnAudit(sender.getName(), null, "VPN-Modul neu geladen", "Provider- und Whitelist-Cache aktualisiert");
            return true;
        }
        if (is(action, "on", "an", "enable", "aktivieren", "off", "aus", "disable", "deaktivieren")) {
            if (!allowed(sender, "simplexadminsuite.vpn.manage", "simplexvpn.admin")) return true;
            boolean enabled = is(action, "on", "an", "enable", "aktivieren");
            set("vpn-protection.enabled", enabled);
            plugin.menuNotice(sender,plugin.prefix() + "VPN-/Proxy-Schutz wurde " + (enabled ? "aktiviert" : "deaktiviert") + ".");
            plugin.vpnAudit(sender.getName(), null, "VPN-Schutz " + (enabled ? "aktiviert" : "deaktiviert"), "Minecraft-Befehl");
            return true;
        }
        if (is(action, "whitelist", "wl", "ausnahmen")) return handleWhitelistCommand(sender, label, values);
        if (is(action, "players", "spieler", "datenbank")) {
            if (!(sender instanceof Player)) { sender.sendMessage(plugin.prefix() + "Das Datenbankmenü kann nur im Spiel geöffnet werden."); return true; }
            if (!allowed(sender, "simplexadminsuite.vpn.whitelist", "simplexvpn.whitelist")) return true;
            openPlayers((Player) sender, 0);
            return true;
        }
        if (is(action, "check", "prüfen", "pruefen")) {
            if (!allowed(sender, "simplexadminsuite.vpn.check", "simplexvpn.check")) return true;
            if (values.length < 2) { sender.sendMessage(plugin.prefix() + "Verwendung: /" + label + " prüfen <IP>"); return true; }
            manualCheck(sender, values[1]);
            return true;
        }
        if (is(action, "recent", "protokoll", "log", "logs")) {
            if (!allowed(sender, "simplexadminsuite.vpn.logs", "simplexvpn.status")) return true;
            String query = values.length >= 2 ? values[1] : "";
            sendRecent(sender, query, 12);
            return true;
        }
        if (action.equals("cache")) {
            if (!allowed(sender, "simplexadminsuite.vpn.manage", "simplexvpn.admin")) return true;
            if (values.length >= 2 && is(values[1], "clear", "leeren")) {
                clearCache();
                plugin.vpnAudit(sender.getName(), null, "VPN-Cache geleert", "Minecraft-Befehl");
                plugin.menuNotice(sender,plugin.prefix() + "VPN-IP-Cache und Wiederverbindungs-Cooldowns wurden geleert.");
            } else sender.sendMessage(plugin.prefix() + "Verwendung: /" + label + " cache leeren");
            return true;
        }
        if (is(action, "discord", "logchannel", "protokollkanal")) {
            if (!allowed(sender, "simplexadminsuite.vpn.manage", "simplexvpn.admin")) return true;
            if (values.length < 2) {
                sender.sendMessage(plugin.prefix() + "VPN-Protokollkanal: " + configuredChannel());
                return true;
            }
            String channel = is(values[1], "off", "aus", "disable") ? "" : values[1].replaceAll("[^0-9]", "");
            plugin.getConfig().set("discord-bot.vpn-log.channel-id", channel);
            plugin.getConfig().set("discord-bot.vpn-log.enabled", !channel.isBlank());
            plugin.saveConfig();
            plugin.vpnAudit(sender.getName(), null, "VPN-Protokollkanal geändert", channel.isBlank() ? "deaktiviert" : channel);
            plugin.menuNotice(sender,plugin.prefix() + (channel.isBlank() ? "VPN-Protokollkanal wurde deaktiviert." : "VPN-Protokollkanal wurde auf " + channel + " gesetzt."));
            return true;
        }
        sendHelp(sender, label);
        return true;
    }

    List<String> tabComplete(String[] args) {
        String[] values = args == null ? new String[0] : args;
        if (values.length == 1) return filter(values[0], List.of("menu", "status", "neu-laden", "an", "aus", "whitelist", "spieler", "prüfen", "protokoll", "cache", "protokollkanal", "hilfe"));
        if (values.length == 2 && is(values[0], "whitelist", "wl", "ausnahmen"))
            return filter(values[1], List.of("hinzufügen", "entfernen", "anzeigen"));
        if (values.length == 3 && is(values[0], "whitelist", "wl", "ausnahmen")) {
            if (is(values[1], "add", "hinzufügen", "hinzufuegen")) {
                List<String> names = plugin.vpnPlayers().stream().filter(player -> !whitelist.isWhitelisted(player.uuid(), player.name()))
                        .map(SimplexAdminSuite.VpnPlayerRef::name).collect(Collectors.toList());
                return filter(values[2], names);
            }
            if (is(values[1], "remove", "delete", "entfernen", "löschen", "loeschen"))
                return filter(values[2], whitelist.entries().stream().map(VpnWhitelistStore.Entry::display).collect(Collectors.toList()));
        }
        if (values.length == 2 && is(values[0], "recent", "protokoll", "log", "logs"))
            return filter(values[1], plugin.vpnPlayers().stream().map(SimplexAdminSuite.VpnPlayerRef::name).collect(Collectors.toList()));
        if (values.length == 2 && values[0].equalsIgnoreCase("cache")) return filter(values[1], List.of("leeren"));
        if (values.length == 2 && is(values[0], "protokollkanal", "logchannel", "discord")) return filter(values[1], List.of("aus"));
        return List.of();
    }

    void open(Player admin) {
        if (admin == null || !allowed(admin, "simplexadminsuite.vpn.view", "simplexvpn.status")) return;
        if (plugin.isBedrockAdmin(admin) && openBedrock(admin)) return;
        openJava(admin);
    }

    void openJava(Player admin) {
        if (!plugin.adminMenuAllowed(admin, "vpnprotectionmanager.openjava")) return;
        VpnSettings current = settings;
        Inventory inventory = LiveMenus.create(new SimplexAdminSuite.SuiteMenu("vpn-main", null, 0, null, null), 54,
                plugin.color("&8VPN-/Proxy-Schutz"));
        plugin.fill(inventory);
        inventory.setItem(4, plugin.item(current.enabled() ? Material.SHIELD : Material.BARRIER,
                current.enabled() ? "&aVPN-Schutz aktiv" : "&cVPN-Schutz deaktiviert",
                "&7Geprüfte Verbindungen: &f" + checked.get(), "&7Blockiert: &c" + blocked.get(),
                "&7Whitelist-Bypässe: &a" + bypassed.get(), "&7Verdächtig erlaubt: &e" + suspiciousAllowed.get()));
        inventory.setItem(10, plugin.item(current.enabled() ? Material.LIME_DYE : Material.RED_DYE,
                current.enabled() ? "&aSchutz deaktivieren" : "&aSchutz aktivieren", "&7Schaltet nur das integrierte VPN-Modul."));
        inventory.setItem(11, plugin.item(Material.PLAYER_HEAD, "&bSpieler-Datenbank",
                "&7Gespeicherte Spieler auswählen und", "&7UUID-sicher zur Whitelist hinzufügen.", "&7Profile: &f" + plugin.vpnPlayers().size()));
        inventory.setItem(12, plugin.item(Material.BOOK, "&aVPN-Whitelist",
                "&7Einträge sauber einsehen und entfernen.", "&7Einträge: &f" + whitelist.entries().size()));
        inventory.setItem(13, plugin.item(Material.WRITABLE_BOOK, "&cVPN-Protokoll",
                "&7Blockierte und auffällige Versuche.", "&7Gespeicherte Einträge: &f" + detectionLog.size()));
        inventory.setItem(14, plugin.item(Material.BUCKET, "&eIP-Cache leeren", "&7Cache: &f" + cacheSize(), "&7Auch Join-Cooldowns werden geleert."));
        inventory.setItem(15, plugin.item(Material.COMPARATOR, "&6VPN-Modul neu laden", "&7Config, Whitelist und Provider neu laden."));
        inventory.setItem(19, toggleItem(current.blockHosting(), "Hosting-/Datacenter-Erkennung", "Erfasst typische VPN-Rechenzentren."));
        inventory.setItem(20, toggleItem(current.blockOnSingleHighConfidence(), "Einzelsignal mit hoher Sicherheit", "Blockiert klare VPN-/Proxy-/Tor-Signale sofort."));
        inventory.setItem(21, toggleItem(current.failOpen(), "Fail-open bei API-Ausfall", "Verhindert Fehlkicks, wenn alle APIs ausfallen."));
        inventory.setItem(22, toggleItem(current.logAllJoins(), "Alle geprüften Joins protokollieren", "Standardmäßig werden nur Auffälligkeiten gespeichert."));
        boolean discordLog = plugin.getConfig().getBoolean("discord-bot.vpn-log.enabled", true);
        inventory.setItem(23, toggleItem(discordLog, "Discord-VPN-Protokoll", "Kanal: " + configuredChannel()));
        inventory.setItem(28, providerItem("proxycheck", "proxycheck.io"));
        inventory.setItem(29, providerItem("ipapi-is", "ipapi.is"));
        inventory.setItem(30, providerItem("ipwhois", "ipwho.is"));
        inventory.setItem(31, providerItem("ip-api", "ip-api.com"));
        inventory.setItem(40, plugin.item(Material.MAP, "&bErkennungsregeln",
                "&7Mindest-Signale: &f" + current.minimumSignals(), "&7Mindest-Risiko: &f" + current.minimumRisk(),
                "&7Cache-Dauer: &f" + current.cacheMinutes() + " Minuten", "&7API-Timeout: &f" + current.timeoutMillis() + " ms"));
        inventory.setItem(49, plugin.item(Material.BARRIER, "&cZurück", "&7Zurück zur Admin Suite."));
        LiveMenus.show(admin,inventory);
    }

    boolean openBedrock(Player admin) {
        if (!plugin.adminMenuAllowed(admin, "vpnprotectionmanager.openbedrock")) return true;
        if (admin == null || !plugin.isBedrockAdmin(admin)) return false;
        VpnSettings current = settings;
        SimplexAdminSuite.SimpleForm.Builder form = SimplexAdminSuite.SimpleForm.builder().title("§l§bVPN-/PROXY-SCHUTZ")
                .content("§fStatus: " + (current.enabled() ? "§aAktiv" : "§cInaktiv")
                        + "\n§fGeprüft: §e" + checked.get() + " §8• §fBlockiert: §c" + blocked.get()
                        + "\n§fWhitelist: §a" + whitelist.entries().size() + " §8• §fProtokoll: §e" + detectionLog.size()
                        + "\n§fDiscord-Kanal: §e" + configuredChannel()
                        + "\n\n§7Whitelist-Spieler werden vor jedem externen IP-Check direkt durchgelassen. Öffentliche Kickmeldungen bleiben Englisch.");
        if (can(admin, "simplexadminsuite.vpn.manage", "simplexvpn.admin"))
            form.button(current.enabled() ? "§cVPN-Schutz deaktivieren" : "§aVPN-Schutz aktivieren", response -> sync(() -> {
                set("vpn-protection.enabled", !current.enabled(), admin.getName()); openBedrock(admin);
            }));
        if (can(admin, "simplexadminsuite.vpn.whitelist", "simplexvpn.whitelist")) {
            form.button("§bSpieler aus Datenbank\n§fWhitelist hinzufügen/entfernen", response -> sync(() -> openBedrockPlayers(admin, 0)));
            form.button("§aWhitelist anzeigen\n§f" + whitelist.entries().size() + " Einträge", response -> sync(() -> openBedrockWhitelist(admin, 0)));
        }
        if (can(admin, "simplexadminsuite.vpn.logs", "simplexvpn.status"))
            form.button("§cVPN-Protokoll\n§f" + detectionLog.size() + " Einträge", response -> sync(() -> openBedrockLog(admin, 0)));
        if (can(admin, "simplexadminsuite.vpn.manage", "simplexvpn.admin")) {
            form.button("§eIP-Cache leeren", response -> sync(() -> { clearCache(); plugin.vpnAudit(admin.getName(), null, "VPN-Cache geleert", "Bedrock-Menü"); openBedrock(admin); }));
            form.button("§6Erweiterte Einstellungen", response -> sync(() -> openBedrockSettings(admin)));
        }
        form.button("§cZurück zum Hauptmenü", response -> sync(() -> plugin.openBedrockMainMenu(admin)));
        return plugin.sendBedrockForm(admin, form);
    }

    void handleInventoryClick(Player admin, SimplexAdminSuite.SuiteMenu menu, int slot) {
        if (admin == null || menu == null || !allowed(admin, "simplexadminsuite.vpn.view", "simplexvpn.status")) return;
        if (menu.type.equals("vpn-main")) {
            if (slot == 49) { plugin.openJavaMainMenu(admin); return; }
            if (slot == 11 || slot == 12) {
                if (!allowed(admin, "simplexadminsuite.vpn.whitelist", "simplexvpn.whitelist")) return;
                if (slot == 11) openPlayers(admin, 0); else openWhitelist(admin, 0);
                return;
            }
            if (slot == 13) {
                if (!allowed(admin, "simplexadminsuite.vpn.logs", "simplexvpn.status")) return;
                openLog(admin, 0);
                return;
            }
            if (!List.of(10, 14, 15, 19, 20, 21, 22, 23, 28, 29, 30, 31).contains(slot)) return;
            if (!allowed(admin, "simplexadminsuite.vpn.manage", "simplexvpn.admin")) return;
            if (slot == 10) set("vpn-protection.enabled", !settings.enabled(), admin.getName());
            else if (slot == 14) { clearCache(); plugin.vpnAudit(admin.getName(), null, "VPN-Cache geleert", "Java-Menü"); }
            else if (slot == 15) { reload(); plugin.vpnAudit(admin.getName(), null, "VPN-Modul neu geladen", "Java-Menü"); }
            else if (slot == 19) set("vpn-protection.settings.block-hosting-datacenter", !settings.blockHosting(), admin.getName());
            else if (slot == 20) set("vpn-protection.settings.block-on-single-high-confidence", !settings.blockOnSingleHighConfidence(), admin.getName());
            else if (slot == 21) set("vpn-protection.settings.fail-open-if-all-apis-fail", !settings.failOpen(), admin.getName());
            else if (slot == 22) set("vpn-protection.logging.log-all-joins", !settings.logAllJoins(), admin.getName());
            else if (slot == 23) set("discord-bot.vpn-log.enabled", !plugin.getConfig().getBoolean("discord-bot.vpn-log.enabled", true), admin.getName());
            else if (slot == 28) toggleProvider("proxycheck", admin.getName());
            else if (slot == 29) toggleProvider("ipapi-is", admin.getName());
            else if (slot == 30) toggleProvider("ipwhois", admin.getName());
            else if (slot == 31) toggleProvider("ip-api", admin.getName());
            openJava(admin);
            return;
        }
        if (menu.type.equals("vpn-players")) {
            if (!allowed(admin, "simplexadminsuite.vpn.whitelist", "simplexvpn.whitelist")) return;
            if (slot == 45 && menu.page > 0) { openPlayers(admin, menu.page - 1); return; }
            if (slot == 53) { openPlayers(admin, menu.page + 1); return; }
            if (slot == 49) { openJava(admin); return; }
            List<SimplexAdminSuite.VpnPlayerRef> view = playerViews.getOrDefault(admin.getUniqueId(), List.of());
            int index = menu.page * 45 + slot;
            if (slot >= 0 && slot < 45 && index < view.size()) {
                SimplexAdminSuite.VpnPlayerRef target = view.get(index);
                toggleWhitelist(admin.getName(), target.uuid(), target.name());
                openPlayers(admin, menu.page);
            }
            return;
        }
        if (menu.type.equals("vpn-whitelist")) {
            if (!allowed(admin, "simplexadminsuite.vpn.whitelist", "simplexvpn.whitelist")) return;
            if (slot == 45 && menu.page > 0) { openWhitelist(admin, menu.page - 1); return; }
            if (slot == 53) { openWhitelist(admin, menu.page + 1); return; }
            if (slot == 49) { openJava(admin); return; }
            List<VpnWhitelistStore.Entry> view = whitelistViews.getOrDefault(admin.getUniqueId(), List.of());
            int index = menu.page * 45 + slot;
            if (slot >= 0 && slot < 45 && index < view.size()) {
                VpnWhitelistStore.Entry entry = view.get(index);
                removeWhitelist(admin.getName(), entry.uuid(), entry.name(), entry.display());
                openWhitelist(admin, Math.max(0, menu.page));
            }
            return;
        }
        if (menu.type.equals("vpn-log")) {
            if (!allowed(admin, "simplexadminsuite.vpn.logs", "simplexvpn.status")) return;
            if (slot == 45 && menu.page > 0) { openLog(admin, menu.page - 1); return; }
            if (slot == 53) { openLog(admin, menu.page + 1); return; }
            if (slot == 49) openJava(admin);
        }
    }

    boolean isWhitelisted(UUID uuid, String name) { return whitelist.isWhitelisted(uuid, name); }

    void toggleWhitelist(String actor, UUID uuid, String name) {
        if (whitelist.isWhitelisted(uuid, name)) removeWhitelist(actor, uuid, name, name);
        else addWhitelist(actor, uuid, name);
    }

    String discordCommand(String actor, String action, String playerQuery, String ip, String channelId) {
        String normalized = action == null || action.isBlank() ? "status" : action.toLowerCase(Locale.ROOT);
        if (normalized.equals("status")) return statusMarkdown();
        if (normalized.equals("enable") || normalized.equals("on")) {
            set("vpn-protection.enabled", true); plugin.vpnAudit(actor, null, "VPN-Schutz aktiviert", "Discord"); return "Der integrierte VPN-/Proxy-Schutz wurde **aktiviert**.";
        }
        if (normalized.equals("disable") || normalized.equals("off")) {
            set("vpn-protection.enabled", false); plugin.vpnAudit(actor, null, "VPN-Schutz deaktiviert", "Discord"); return "Der integrierte VPN-/Proxy-Schutz wurde **deaktiviert**.";
        }
        if (normalized.equals("reload")) { reload(); plugin.vpnAudit(actor, null, "VPN-Modul neu geladen", "Discord"); return "VPN-Konfiguration, Whitelist und Provider wurden neu geladen."; }
        if (normalized.equals("cache-clear")) { clearCache(); plugin.vpnAudit(actor, null, "VPN-Cache geleert", "Discord"); return "VPN-IP-Cache und Wiederverbindungs-Cooldowns wurden geleert."; }
        if (normalized.equals("whitelist-list")) return whitelistMarkdown();
        if (normalized.equals("recent")) return recentMarkdown(playerQuery, 20);
        if (normalized.equals("whitelist-add") || normalized.equals("whitelist-remove")) {
            SimplexAdminSuite.VpnPlayerRef target = plugin.vpnPlayer(playerQuery);
            if (normalized.endsWith("add")) {
                if (target == null) return "Spielerprofil wurde in der Suite-Datenbank nicht gefunden. Bitte nutze das Autocomplete-Feld.";
                boolean added = addWhitelist(actor, target.uuid(), target.name());
                return added ? "**" + target.name() + "** wurde UUID-sicher zur VPN-Whitelist hinzugefügt."
                        : "**" + target.name() + "** steht bereits auf der VPN-Whitelist.";
            }
            String display = target == null ? playerQuery : target.name();
            boolean removed = target == null ? removeWhitelist(actor, null, playerQuery, display)
                    : removeWhitelist(actor, target.uuid(), target.name(), display);
            return removed ? "**" + discordSafe(display) + "** wurde von der VPN-Whitelist entfernt."
                    : "Für **" + discordSafe(display) + "** wurde kein Whitelist-Eintrag gefunden.";
        }
        if (normalized.equals("check")) {
            if (ip == null || ip.isBlank()) return "Für die manuelle Prüfung fehlt eine IP-Adresse.";
            if (!VpnIpCheckService.isIpLiteral(ip)) return "Die Eingabe ist keine gültige IPv4- oder IPv6-Adresse.";
            if (!plugin.getConfig().getBoolean("discord-bot.vpn-log.enabled", true)
                    || plugin.getConfig().getString("discord-bot.vpn-log.channel-id", "").trim().isBlank())
                return "Der aktive VPN-Protokollkanal fehlt. Nutze zuerst „Diesen Kanal nutzen“ oder „Privaten Protokollkanal erstellen“.";
            plugin.vpnAudit(actor, null, "VPN-IP manuell geprüft", ip.trim());
            scheduleDiscordCheck(actor, ip.trim());
            return "Die IP-Prüfung für `" + ip.trim() + "` wurde außerhalb des Serverthreads gestartet. Das Ergebnis wird in den VPN-Protokollkanal gesendet.";
        }
        if (normalized.equals("log-here")) {
            String clean = channelId == null ? "" : channelId.replaceAll("[^0-9]", "");
            if (clean.isBlank()) return "Der aktuelle Discord-Kanal konnte nicht bestimmt werden.";
            plugin.getConfig().set("discord-bot.vpn-log.channel-id", clean);
            plugin.getConfig().set("discord-bot.vpn-log.enabled", true);
            plugin.saveConfig();
            plugin.vpnAudit(actor, null, "VPN-Protokollkanal geändert", clean);
            return "Dieser Kanal (`" + clean + "`) ist jetzt der dedizierte VPN-Protokollkanal.";
        }
        if (normalized.equals("log-create")) return plugin.vpnCreateDiscordLogChannel(actor, channelId);
        if (normalized.equals("log-disable")) {
            plugin.getConfig().set("discord-bot.vpn-log.enabled", false);
            plugin.saveConfig();
            plugin.vpnAudit(actor, null, "VPN-Protokollkanal deaktiviert", "Discord");
            return "Der Discord-VPN-Protokollkanal wurde deaktiviert; die lokale JSONL-Protokollierung bleibt erhalten.";
        }
        return "Unbekannte VPN-Aktion: `" + normalized + "`.";
    }

    String statusSummary() {
        return (settings.enabled() ? "aktiv" : "inaktiv") + " • Whitelist " + whitelist.entries().size()
                + " • Blockiert " + blocked.get() + " • Cache " + cacheSize();
    }

    List<DiscordAdminController.Choice> discordWhitelistChoices(String query, int requestedLimit) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        int limit = Math.max(1, Math.min(25, requestedLimit));
        List<DiscordAdminController.Choice> result = new ArrayList<>();
        for (VpnWhitelistStore.Entry entry : whitelist.entries()) {
            String display = entry.display();
            String value = entry.uuid() == null ? display : entry.uuid().toString();
            if (!needle.isBlank() && !display.toLowerCase(Locale.ROOT).contains(needle)
                    && !value.toLowerCase(Locale.ROOT).startsWith(needle)) continue;
            result.add(new DiscordAdminController.Choice(display + (entry.uuid() == null ? " • Legacy-Name" : " • UUID-sicher"), value));
            if (result.size() >= limit) break;
        }
        return result;
    }

    private boolean handleWhitelistCommand(CommandSender sender, String label, String[] args) {
        if (!allowed(sender, "simplexadminsuite.vpn.whitelist", "simplexvpn.whitelist")) return true;
        String operation = args.length < 2 ? "anzeigen" : args[1].toLowerCase(Locale.ROOT);
        if (is(operation, "list", "anzeigen", "liste")) {
            sendWhitelist(sender);
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage(plugin.prefix() + "Verwendung: /" + label + " whitelist <hinzufügen|entfernen|anzeigen> <Spieler>");
            return true;
        }
        SimplexAdminSuite.VpnPlayerRef player = plugin.vpnPlayer(args[2]);
        if (is(operation, "add", "hinzufügen", "hinzufuegen")) {
            if (player == null) { plugin.menuNotice(sender,plugin.prefix() + "Spieler wurde nicht in der Suite-Datenbank gefunden."); return true; }
            boolean added = addWhitelist(sender.getName(), player.uuid(), player.name());
            plugin.menuNotice(sender,plugin.prefix() + (added ? player.name() + " wurde UUID-sicher zur VPN-Whitelist hinzugefügt."
                    : player.name() + " steht bereits auf der VPN-Whitelist."));
            return true;
        }
        if (is(operation, "remove", "delete", "entfernen", "löschen", "loeschen")) {
            boolean removed = player != null ? removeWhitelist(sender.getName(), player.uuid(), player.name(), player.name())
                    : removeWhitelist(sender.getName(), null, args[2], args[2]);
            plugin.menuNotice(sender,plugin.prefix() + (removed ? "Whitelist-Eintrag wurde entfernt." : "Whitelist-Eintrag wurde nicht gefunden."));
            return true;
        }
        sender.sendMessage(plugin.prefix() + "Verwendung: /" + label + " whitelist <hinzufügen|entfernen|anzeigen> <Spieler>");
        return true;
    }

    private boolean addWhitelist(String actor, UUID uuid, String name) {
        boolean added = whitelist.add(uuid, name, actor);
        if (added) {
            plugin.vpnAudit(actor, uuid, "VPN-Whitelist hinzugefügt", name);
            plugin.vpnRelayWhitelistChange(actor, "hinzugefügt", name, uuid);
        }
        return added;
    }

    private boolean removeWhitelist(String actor, UUID uuid, String name, String display) {
        boolean removed = whitelist.remove(uuid, name);
        if (!removed && uuid == null) removed = whitelist.removeQuery(name);
        if (removed) {
            plugin.vpnAudit(actor, uuid, "VPN-Whitelist entfernt", display);
            plugin.vpnRelayWhitelistChange(actor, "entfernt", display, uuid);
        }
        return removed;
    }

    Object appData(String route,String query,int offset) {
        List<Map<String,Object>> rows=new ArrayList<>();
        if(route.equals("vpn-whitelist"))for(VpnWhitelistStore.Entry e:whitelist.entries()) {
            if(!query.isBlank()&&!(e.display()+" "+e.uuid()).toLowerCase(Locale.ROOT).contains(query))continue;
            rows.add(SimplexAdminSuite.appMap("id",e.key(),"name",e.display(),"player",e.uuid()==null?e.name():e.uuid().toString(),"created",e.addedAt(),"actor",e.addedBy()));
        } else for(VpnDetectionLog.Detection d:detectionLog.recent(query,200))if(d.blocked())rows.add(SimplexAdminSuite.appMap("id",d.timestamp()+":"+d.uuid(),"name",d.playerName(),"player",d.uuid()==null?"":d.uuid().toString(),"time",d.timestamp(),"reason",d.reason(),"country",d.country(),"risk",d.riskScore(),"decision",d.decision()));
        int start=Math.min(Math.max(0,offset),rows.size()),end=Math.min(start+100,rows.size());return SimplexAdminSuite.appMap("items",rows.subList(start,end),"next",end<rows.size()?end:-1,"total",rows.size(),"window",route.equals("vpn-kicks")?200:0);
    }
    Object appWhitelist(String actor,String player,boolean allowed) {
        UUID id=plugin.discordAdminResolvePlayer(player);
        SimplexAdminSuite.PlayerProfile p=id==null?null:plugin.clientSecurityProfile(id);
        if(allowed&&p==null)throw new IllegalArgumentException("Gespeicherten Spieler auswählen (Name oder UUID).");
        if(allowed)addWhitelist(actor,id,p.displayName());else {
            String name=p==null?player:p.displayName();
            // Exact identity only. Never use removeQuery's prefix matching from a remote request.
            boolean removed=whitelist.remove(id,name);
            if(!removed)throw new IllegalArgumentException("Dieser genaue Whitelist-Eintrag ist nicht mehr vorhanden.");
            plugin.vpnAudit(actor,id,"VPN-Whitelist entfernt",name);
        }
        return SimplexAdminSuite.appMap("ok",true,"title","VPN-Whitelist gespeichert","description",allowed?"Spieler freigegeben.":"Ausnahme entfernt.");
    }
    private void record(VpnDetectionLog.Detection detection, boolean notifyDiscord) {
        detectionLog.append(detection);
        if(detection.blocked()&&plugin.appEvents!=null)plugin.appEvents.record("vpn-kick",detection.uuid(),"VPN-Verbindung blockiert",detection.playerName()+" · "+detection.reason(),false);
        if (notifyDiscord) plugin.vpnRelayDetection(detection);
    }

    private void manualCheck(CommandSender sender, String ip) {
        if (!VpnIpCheckService.isIpLiteral(ip)) {
            sender.sendMessage(plugin.prefix() + "Bitte gib eine gültige IPv4- oder IPv6-Adresse an.");
            return;
        }
        plugin.vpnAudit(sender.getName(), null, "VPN-IP manuell geprüft", ip);
        sender.sendMessage(plugin.prefix() + "IP `" + ip + "` wird außerhalb des Serverthreads geprüft …");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            VpnIpCheckService current = checks;
            VpnCheckResult result = current == null ? null : current.check(ip, true);
            plugin.runAdminTask( () -> {
                if (result == null) { sender.sendMessage(plugin.prefix() + "VPN-Prüfung ist derzeit nicht verfügbar."); return; }
                sender.sendMessage(plugin.color("&8&m----------&r &bVPN-Prüfung &8&m----------"));
                sender.sendMessage(plugin.color("&7IP: &f" + ip + " &8| &7Land: &f" + (result.countryCode().isBlank() ? "Unbekannt" : result.countryCode())));
                sender.sendMessage(plugin.color("&7Signale: &f" + result.signals() + " &8| &7Risiko: &f" + result.riskScore() + " &8| &7Ergebnis: "
                        + (result.allProvidersFailed() ? "&eProvider-Ausfall" : result.positive() ? "&cBlockschwelle erreicht" : "&aKeine Blockschwelle")));
                sender.sendMessage(plugin.color("&7Provider: &f" + result.providerSummary()));
                sender.sendMessage(plugin.color("&7Grund: &f" + result.reason()));
            });
        });
    }

    private void scheduleDiscordCheck(String actor, String ip) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            VpnIpCheckService current = checks;
            VpnCheckResult result = current == null ? null : current.check(ip, true);
            if (result != null) plugin.vpnRelayManualCheck(actor, ip, result);
        });
    }

    private void openPlayers(Player admin, int requestedPage) {
        if (!plugin.adminMenuAllowed(admin, "vpnprotectionmanager.openplayers")) return;
        List<SimplexAdminSuite.VpnPlayerRef> players = plugin.vpnPlayers();
        playerViews.put(admin.getUniqueId(), players);
        int maxPage = Math.max(0, (players.size() - 1) / 45);
        int page = Math.max(0, Math.min(maxPage, requestedPage));
        Inventory inventory = LiveMenus.create(new SimplexAdminSuite.SuiteMenu("vpn-players", null, page, null, null), 54,
                plugin.color("&8VPN-Spieler " + (page + 1) + "/" + (maxPage + 1)));
        plugin.fill(inventory);
        for (int slot = 0; slot < 45; slot++) {
            int index = page * 45 + slot;
            if (index >= players.size()) break;
            SimplexAdminSuite.VpnPlayerRef player = players.get(index);
            boolean allowed = whitelist.isWhitelisted(player.uuid(), player.name());
            inventory.setItem(slot, plugin.item(allowed ? Material.LIME_DYE : Material.PLAYER_HEAD,
                    (allowed ? "&a" : "&f") + player.name(), "&7UUID: &f" + player.uuid(),
                    "&7Zuletzt gesehen: &f" + formatTime(player.lastSeen()),
                    allowed ? "&aAuf VPN-Whitelist" : "&7Wird beim Join geprüft",
                    "&eKlick zum " + (allowed ? "Entfernen" : "Hinzufügen")));
        }
        if (page > 0) inventory.setItem(45, plugin.item(Material.ARROW, "&eVorherige Seite"));
        inventory.setItem(49, plugin.item(Material.BARRIER, "&cZurück"));
        if (page < maxPage) inventory.setItem(53, plugin.item(Material.ARROW, "&eNächste Seite"));
        LiveMenus.show(admin,inventory);
    }

    private void openWhitelist(Player admin, int requestedPage) {
        if (!plugin.adminMenuAllowed(admin, "vpnprotectionmanager.openwhitelist")) return;
        List<VpnWhitelistStore.Entry> entries = whitelist.entries();
        whitelistViews.put(admin.getUniqueId(), entries);
        int maxPage = Math.max(0, (entries.size() - 1) / 45);
        int page = Math.max(0, Math.min(maxPage, requestedPage));
        Inventory inventory = LiveMenus.create(new SimplexAdminSuite.SuiteMenu("vpn-whitelist", null, page, null, null), 54,
                plugin.color("&8VPN-Whitelist " + (page + 1) + "/" + (maxPage + 1)));
        plugin.fill(inventory);
        for (int slot = 0; slot < 45; slot++) {
            int index = page * 45 + slot;
            if (index >= entries.size()) break;
            VpnWhitelistStore.Entry entry = entries.get(index);
            inventory.setItem(slot, plugin.item(Material.LIME_DYE, "&a" + entry.display(),
                    "&7UUID: &f" + (entry.uuid() == null ? "Legacy-Name" : entry.uuid()),
                    "&7Hinzugefügt: &f" + formatTime(entry.addedAt()), "&7Von: &f" + entry.addedBy(),
                    "&cKlick zum Entfernen"));
        }
        if (entries.isEmpty()) inventory.setItem(22, plugin.item(Material.GRAY_DYE, "&7Whitelist ist leer", "&7Spieler werden regulär geprüft."));
        if (page > 0) inventory.setItem(45, plugin.item(Material.ARROW, "&eVorherige Seite"));
        inventory.setItem(49, plugin.item(Material.BARRIER, "&cZurück"));
        if (page < maxPage) inventory.setItem(53, plugin.item(Material.ARROW, "&eNächste Seite"));
        LiveMenus.show(admin,inventory);
    }

    private void openLog(Player admin, int requestedPage) {
        if (!plugin.adminMenuAllowed(admin, "vpnprotectionmanager.openlog")) return;
        List<VpnDetectionLog.Detection> entries = detectionLog.recent("", 200);
        detectionViews.put(admin.getUniqueId(), entries);
        int maxPage = Math.max(0, (entries.size() - 1) / 45);
        int page = Math.max(0, Math.min(maxPage, requestedPage));
        Inventory inventory = LiveMenus.create(new SimplexAdminSuite.SuiteMenu("vpn-log", null, page, null, null), 54,
                plugin.color("&8VPN-Protokoll " + (page + 1) + "/" + (maxPage + 1)));
        plugin.fill(inventory);
        for (int slot = 0; slot < 45; slot++) {
            int index = page * 45 + slot;
            if (index >= entries.size()) break;
            VpnDetectionLog.Detection entry = entries.get(index);
            inventory.setItem(slot, plugin.item(entry.blocked() ? Material.RED_DYE : Material.YELLOW_DYE,
                    (entry.blocked() ? "&c" : "&e") + entry.playerName(),
                    "&7Zeit: &f" + formatTime(entry.timestamp()), "&7Plattform: &f" + (entry.bedrock() ? "Bedrock" : "Java"),
                    "&7IP: &f" + entry.ip(), "&7Entscheidung: &f" + decisionGerman(entry.decision()),
                    "&7Land: &f" + (entry.country().isBlank() ? "Unbekannt" : entry.country()),
                    "&7Signale: &f" + entry.signals() + " &8| &7Risiko: &f" + entry.riskScore(),
                    "&7Provider: &f" + shorten(entry.providers(), 90)));
        }
        if (entries.isEmpty()) inventory.setItem(22, plugin.item(Material.LIME_DYE, "&aKeine VPN-Erkennungen", "&7Noch keine protokollierten Auffälligkeiten."));
        if (page > 0) inventory.setItem(45, plugin.item(Material.ARROW, "&eVorherige Seite"));
        inventory.setItem(49, plugin.item(Material.BARRIER, "&cZurück"));
        if (page < maxPage) inventory.setItem(53, plugin.item(Material.ARROW, "&eNächste Seite"));
        LiveMenus.show(admin,inventory);
    }

    private void openBedrockPlayers(Player admin, int requestedPage) {
        if (!plugin.adminMenuAllowed(admin, "vpnprotectionmanager.openbedrockplayers")) return;
        List<SimplexAdminSuite.VpnPlayerRef> players = plugin.vpnPlayers();
        int pageSize = 15;
        int maxPage = Math.max(0, (players.size() - 1) / pageSize);
        int page = Math.max(0, Math.min(maxPage, requestedPage));
        SimplexAdminSuite.SimpleForm.Builder form = SimplexAdminSuite.SimpleForm.builder()
                .title("§l§bVPN-SPIELER §f" + (page + 1) + "/" + (maxPage + 1))
                .content("§fWähle einen gespeicherten Spieler. Grün bedeutet: direkter Whitelist-Bypass ohne externen IP-Check.");
        int start = page * pageSize;
        for (int index = start; index < Math.min(players.size(), start + pageSize); index++) {
            SimplexAdminSuite.VpnPlayerRef target = players.get(index);
            boolean allowed = whitelist.isWhitelisted(target.uuid(), target.name());
            form.button((allowed ? "§a✓ " : "§7○ ") + target.name() + "\n§f" + (allowed ? "Whitelist entfernen" : "Whitelist hinzufügen"),
                    response -> sync(() -> { toggleWhitelist(admin.getName(), target.uuid(), target.name()); openBedrockPlayers(admin, page); }));
        }
        if (page > 0) form.button("§e◀ Vorherige Seite", response -> sync(() -> openBedrockPlayers(admin, page - 1)));
        form.button("§cZurück zum VPN-Menü", response -> sync(() -> openBedrock(admin)));
        if (page < maxPage) form.button("§eNächste Seite ▶", response -> sync(() -> openBedrockPlayers(admin, page + 1)));
        plugin.sendBedrockForm(admin, form);
    }

    private void openBedrockWhitelist(Player admin, int requestedPage) {
        if (!plugin.adminMenuAllowed(admin, "vpnprotectionmanager.openbedrockwhitelist")) return;
        List<VpnWhitelistStore.Entry> entries = whitelist.entries();
        int pageSize = 15;
        int maxPage = Math.max(0, (entries.size() - 1) / pageSize);
        int page = Math.max(0, Math.min(maxPage, requestedPage));
        SimplexAdminSuite.SimpleForm.Builder form = SimplexAdminSuite.SimpleForm.builder()
                .title("§l§aVPN-WHITELIST §f" + (page + 1) + "/" + (maxPage + 1))
                .content(entries.isEmpty() ? "§fKeine Whitelist-Einträge vorhanden." : "§fKlick auf einen Eintrag, um ihn sauber zu entfernen.");
        int start = page * pageSize;
        for (int index = start; index < Math.min(entries.size(), start + pageSize); index++) {
            VpnWhitelistStore.Entry entry = entries.get(index);
            form.button("§a" + entry.display() + "\n§cKlick zum Entfernen", response -> sync(() -> {
                removeWhitelist(admin.getName(), entry.uuid(), entry.name(), entry.display()); openBedrockWhitelist(admin, page);
            }));
        }
        if (page > 0) form.button("§e◀ Vorherige Seite", response -> sync(() -> openBedrockWhitelist(admin, page - 1)));
        form.button("§cZurück zum VPN-Menü", response -> sync(() -> openBedrock(admin)));
        if (page < maxPage) form.button("§eNächste Seite ▶", response -> sync(() -> openBedrockWhitelist(admin, page + 1)));
        plugin.sendBedrockForm(admin, form);
    }

    private void openBedrockLog(Player admin, int requestedPage) {
        if (!plugin.adminMenuAllowed(admin, "vpnprotectionmanager.openbedrocklog")) return;
        List<VpnDetectionLog.Detection> entries = detectionLog.recent("", 200);
        int pageSize = 12;
        int maxPage = Math.max(0, (entries.size() - 1) / pageSize);
        int page = Math.max(0, Math.min(maxPage, requestedPage));
        SimplexAdminSuite.SimpleForm.Builder form = SimplexAdminSuite.SimpleForm.builder()
                .title("§l§cVPN-PROTOKOLL §f" + (page + 1) + "/" + (maxPage + 1))
                .content(entries.isEmpty() ? "§fNoch keine VPN-/Proxy-Erkennungen gespeichert." : "§fDie neuesten Erkennungen stehen zuerst.");
        int start = page * pageSize;
        for (int index = start; index < Math.min(entries.size(), start + pageSize); index++) {
            VpnDetectionLog.Detection entry = entries.get(index);
            form.button((entry.blocked() ? "§c⛔ " : "§e⚠ ") + entry.playerName() + "\n§f" + formatTime(entry.timestamp())
                    + " • Risiko " + entry.riskScore(), response -> sync(() -> openBedrockDetection(admin, entry, page)));
        }
        if (page > 0) form.button("§e◀ Vorherige Seite", response -> sync(() -> openBedrockLog(admin, page - 1)));
        form.button("§cZurück zum VPN-Menü", response -> sync(() -> openBedrock(admin)));
        if (page < maxPage) form.button("§eNächste Seite ▶", response -> sync(() -> openBedrockLog(admin, page + 1)));
        plugin.sendBedrockForm(admin, form);
    }

    private void openBedrockDetection(Player admin, VpnDetectionLog.Detection entry, int returnPage) {
        if (!plugin.adminMenuAllowed(admin, "vpnprotectionmanager.openbedrockdetection")) return;
        String content = "§fSpieler: §e" + entry.playerName() + "\n§fUUID: §e" + (entry.uuid() == null ? "Unbekannt" : entry.uuid())
                + "\n§fZeit: §e" + formatTime(entry.timestamp()) + "\n§fPlattform: §e" + (entry.bedrock() ? "Bedrock" : "Java")
                + "\n§fIP: §e" + entry.ip() + "\n§fEntscheidung: §e" + decisionGerman(entry.decision())
                + "\n§fLand: §e" + (entry.country().isBlank() ? "Unbekannt" : entry.country())
                + "\n§fSignale/Risiko: §e" + entry.signals() + " / " + entry.riskScore()
                + "\n§fGrund: §e" + shorten(entry.reason(), 500) + "\n§fProvider: §e" + shorten(entry.providers(), 900);
        SimplexAdminSuite.SimpleForm.Builder form = SimplexAdminSuite.SimpleForm.builder().title("§l§cVPN-ERKENNUNG").content(content);
        form.button("§cZurück zum Protokoll", response -> sync(() -> openBedrockLog(admin, returnPage)));
        plugin.sendBedrockForm(admin, form);
    }

    private void openBedrockSettings(Player admin) {
        if (!plugin.adminMenuAllowed(admin, "vpnprotectionmanager.openbedrocksettings")) return;
        VpnSettings current = settings;
        SimplexAdminSuite.SimpleForm.Builder form = SimplexAdminSuite.SimpleForm.builder().title("§l§6VPN-EINSTELLUNGEN")
                .content("§fKonservative Mehranbieter-Erkennung ohne Geo-Blocking. Änderungen werden sofort gespeichert.");
        form.button(state(current.blockHosting()) + " Hosting-/Datacenter-Erkennung", response -> sync(() -> { set("vpn-protection.settings.block-hosting-datacenter", !current.blockHosting(), admin.getName()); openBedrockSettings(admin); }));
        form.button(state(current.blockOnSingleHighConfidence()) + " Klares Einzelsignal", response -> sync(() -> { set("vpn-protection.settings.block-on-single-high-confidence", !current.blockOnSingleHighConfidence(), admin.getName()); openBedrockSettings(admin); }));
        form.button(state(current.failOpen()) + " Fail-open bei Komplettausfall", response -> sync(() -> { set("vpn-protection.settings.fail-open-if-all-apis-fail", !current.failOpen(), admin.getName()); openBedrockSettings(admin); }));
        form.button(state(current.logAllJoins()) + " Alle Joins protokollieren", response -> sync(() -> { set("vpn-protection.logging.log-all-joins", !current.logAllJoins(), admin.getName()); openBedrockSettings(admin); }));
        for (String provider : List.of("proxycheck", "ipapi-is", "ipwhois", "ip-api")) {
            boolean enabled = current.providerEnabled(provider);
            form.button(state(enabled) + " Provider: " + provider, response -> sync(() -> { toggleProvider(provider, admin.getName()); openBedrockSettings(admin); }));
        }
        form.button("§eIP- und Cooldown-Cache leeren", response -> sync(() -> { clearCache(); plugin.vpnAudit(admin.getName(), null, "VPN-Cache geleert", "Bedrock-Menü"); openBedrockSettings(admin); }));
        form.button("§cZurück zum VPN-Menü", response -> sync(() -> openBedrock(admin)));
        plugin.sendBedrockForm(admin, form);
    }

    private void sendStatus(CommandSender sender) {
        sender.sendMessage(plugin.color("&8&m----------&r &bVPN-/Proxy-Schutz &8&m----------"));
        sender.sendMessage(plugin.color("&7Status: " + (settings.enabled() ? "&aAktiv" : "&cInaktiv") + " &8| &7Block VPN: &f" + settings.blockVpn()
                + " &8| &7Hosting: &f" + settings.blockHosting()));
        sender.sendMessage(plugin.color("&7Signale: &f" + settings.minimumSignals() + " &8| &7Risiko: &f" + settings.minimumRisk()
                + " &8| &7Fail-open: &f" + settings.failOpen()));
        sender.sendMessage(plugin.color("&7Whitelist: &f" + whitelist.entries().size() + " &8| &7Cache: &f" + cacheSize()
                + " &8| &7Protokoll: &f" + detectionLog.size()));
        sender.sendMessage(plugin.color("&7Geprüft: &f" + checked.get() + " &8| &7Blockiert: &c" + blocked.get()
                + " &8| &7Whitelist-Bypass: &a" + bypassed.get()));
        sender.sendMessage(plugin.color("&7Discord-Protokollkanal: &f" + configuredChannel()));
    }

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage(plugin.color("&8&m----------&r &bVPN-Verwaltung &8&m----------"));
        sender.sendMessage(plugin.color("&b/" + label + " menu &8- &7Java-/Bedrock-Menü öffnen"));
        sender.sendMessage(plugin.color("&b/" + label + " status|an|aus|neu-laden"));
        sender.sendMessage(plugin.color("&b/" + label + " whitelist hinzufügen <Spieler>"));
        sender.sendMessage(plugin.color("&b/" + label + " whitelist entfernen <Spieler>"));
        sender.sendMessage(plugin.color("&b/" + label + " whitelist anzeigen"));
        sender.sendMessage(plugin.color("&b/" + label + " prüfen <IP> &8- &7Manuelle Provider-Prüfung"));
        sender.sendMessage(plugin.color("&b/" + label + " protokoll [Spieler|UUID|IP]"));
        sender.sendMessage(plugin.color("&b/" + label + " cache leeren"));
    }

    private void sendWhitelist(CommandSender sender) {
        List<VpnWhitelistStore.Entry> entries = whitelist.entries();
        sender.sendMessage(plugin.color("&8&m----------&r &aVPN-Whitelist &8&m----------"));
        sender.sendMessage(plugin.color("&7Einträge: &f" + entries.size()));
        for (int index = 0; index < Math.min(40, entries.size()); index++) {
            VpnWhitelistStore.Entry entry = entries.get(index);
            sender.sendMessage(plugin.color("&7- &a" + entry.display() + " &8• &f" + (entry.uuid() == null ? "Legacy-Name" : entry.uuid())));
        }
        if (entries.size() > 40) sender.sendMessage(plugin.color("&8… und " + (entries.size() - 40) + " weitere."));
    }

    private void sendRecent(CommandSender sender, String query, int limit) {
        List<VpnDetectionLog.Detection> entries = detectionLog.recent(query, limit);
        sender.sendMessage(plugin.color("&8&m----------&r &cVPN-Protokoll &8&m----------"));
        if (entries.isEmpty()) { sender.sendMessage(plugin.color("&7Keine passenden Einträge gefunden.")); return; }
        for (VpnDetectionLog.Detection entry : entries) sender.sendMessage(plugin.color("&7- " + (entry.blocked() ? "&c" : "&e") + entry.compact()));
    }

    private String statusMarkdown() {
        return "**Status:** " + (settings.enabled() ? "🟢 Aktiv" : "🔴 Inaktiv")
                + "\n**VPN-/Proxy-Blocking:** " + yesNo(settings.blockVpn())
                + "\n**Hosting-/Datacenter-Erkennung:** " + yesNo(settings.blockHosting())
                + "\n**Fail-open bei API-Komplettausfall:** " + yesNo(settings.failOpen())
                + "\n**Whitelist:** " + whitelist.entries().size()
                + "\n**Cache:** " + cacheSize()
                + "\n**Erkennungsprotokoll:** " + detectionLog.size()
                + "\n**Seit Pluginstart:** " + checked.get() + " geprüft • " + blocked.get() + " blockiert • " + bypassed.get() + " Whitelist-Bypässe"
                + "\n**VPN-Protokollkanal:** " + configuredChannel();
    }

    private String whitelistMarkdown() {
        List<VpnWhitelistStore.Entry> entries = whitelist.entries();
        if (entries.isEmpty()) return "Die VPN-Whitelist ist leer.";
        StringBuilder text = new StringBuilder("**VPN-Whitelist • ").append(entries.size()).append(" Einträge**");
        for (int index = 0; index < Math.min(35, entries.size()); index++) {
            VpnWhitelistStore.Entry entry = entries.get(index);
            text.append("\n• **").append(discordSafe(entry.display())).append("** • `")
                    .append(entry.uuid() == null ? "Legacy-Name" : entry.uuid()).append("`");
        }
        if (entries.size() > 35) text.append("\n… und ").append(entries.size() - 35).append(" weitere.");
        return text.toString();
    }

    private String recentMarkdown(String query, int limit) {
        List<VpnDetectionLog.Detection> entries = detectionLog.recent(query, limit);
        if (entries.isEmpty()) return "Keine passenden VPN-/Proxy-Erkennungen gefunden.";
        StringBuilder text = new StringBuilder("**Neueste VPN-Erkennungen**");
        boolean includeIp = plugin.getConfig().getBoolean("discord-bot.vpn-log.include-ip", true);
        for (VpnDetectionLog.Detection entry : entries) {
            text.append("\n").append(entry.blocked() ? "⛔" : "⚠️").append(" **")
                    .append(discordSafe(entry.playerName())).append("** • ");
            if (includeIp) text.append('`').append(discordSafe(entry.ip())).append("` • ");
            else text.append("IP ausgeblendet • ");
            text
                    .append(decisionGerman(entry.decision())).append(" • Risiko ").append(entry.riskScore()).append(" • ")
                    .append(formatTime(entry.timestamp()));
        }
        return text.length() <= 3800 ? text.toString() : text.substring(0, 3799) + "…";
    }

    private void set(String path, boolean value) {
        plugin.getConfig().set(path, value);
        plugin.saveConfig();
        reload();
    }

    private void set(String path, boolean value, String actor) {
        set(path, value);
        plugin.vpnAudit(actor, null, "VPN-Einstellung geändert", path + "=" + value);
    }

    private void toggleProvider(String provider, String actor) {
        set("vpn-protection.providers." + provider + ".enabled", !settings.providerEnabled(provider), actor);
    }

    private void clearCache() {
        VpnIpCheckService current = checks;
        if (current != null) current.clearCache();
        reconnectCooldowns.clear();
    }

    private int cacheSize() {
        VpnIpCheckService current = checks;
        return current == null ? 0 : current.cacheSize();
    }

    private org.bukkit.inventory.ItemStack toggleItem(boolean active, String title, String description) {
        return plugin.item(active ? Material.LIME_DYE : Material.GRAY_DYE, (active ? "&a" : "&c") + title,
                "&7Status: " + (active ? "&aAktiv" : "&cInaktiv"), "&7" + description, "&eKlick zum Umschalten");
    }

    private org.bukkit.inventory.ItemStack providerItem(String id, String title) {
        boolean active = settings.providerEnabled(id);
        return plugin.item(active ? Material.LIME_DYE : Material.GRAY_DYE, (active ? "&a" : "&c") + title,
                "&7Provider-ID: &f" + id, "&7Status: " + (active ? "&aAktiv" : "&cInaktiv"), "&eKlick zum Umschalten");
    }

    private boolean can(CommandSender sender, String permission, String legacy) {
        if (sender == null) return false;
        boolean inherited = plugin.has(sender, permission) || plugin.has(sender, "simplexadminsuite.vpn.admin")
                || sender.hasPermission(legacy) || sender.hasPermission("simplexvpn.admin");
        return plugin.adminAccess == null ? inherited : plugin.adminAccess.allows(sender, permission, inherited);
    }

    private boolean allowed(CommandSender sender, String permission, String legacy) {
        if (can(sender, permission, legacy)) return true;
        if (sender != null) sender.sendMessage(plugin.prefix() + "Dafür fehlt dir die Berechtigung " + permission + ".");
        return false;
    }

    private boolean isBedrock(UUID uuid, String name, VpnSettings current) {
        try {
            Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object api = apiClass.getMethod("getInstance").invoke(null);
            Object result = apiClass.getMethod("isFloodgatePlayer", UUID.class).invoke(api, uuid);
            if (result instanceof Boolean) return (Boolean) result;
        } catch (Throwable ignored) { }
        for (String prefix : current.bedrockPrefixes()) if (!prefix.isBlank() && name != null && name.startsWith(prefix)) return true;
        return false;
    }

    private void importLegacyWhitelist() {
        File marker = new File(folder, "legacy-simplexvpnblocker-1.0.1-imported.marker");
        if (marker.isFile()) return;
        File parent = plugin.getDataFolder().getParentFile();
        File legacy = parent == null ? null : new File(new File(parent, "SimplexVPNBlocker"), "whitelist.txt");
        if (legacy == null || !legacy.isFile()) return;
        int imported = whitelist.importLegacy(legacy);
        if (imported < 0) {
            plugin.getLogger().warning("Die alte SimplexVPNBlocker-Whitelist konnte nicht gelesen werden; der Import wird beim nächsten Reload erneut versucht.");
            return;
        }
        if (imported > 0) plugin.getLogger().info(imported + " VPN-Whitelist-Einträge wurden nicht-destruktiv aus SimplexVPNBlocker 1.0.1 übernommen.");
        try {
            java.nio.file.Files.writeString(marker.toPath(), "Import abgeschlossen: " + Instant.now() + "\nQuelle: " + legacy.getAbsolutePath() + "\nEinträge: " + imported + "\n",
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception error) {
            plugin.getLogger().warning("Der VPN-Legacy-Importmarker konnte nicht geschrieben werden: " + error.getMessage());
        }
    }

    private void normalizeLegacyWhitelist() {
        int upgraded = 0;
        for (VpnWhitelistStore.Entry entry : new ArrayList<>(whitelist.entries())) {
            SimplexAdminSuite.VpnPlayerRef profile = entry.uuid() == null ? plugin.vpnPlayer(entry.name()) : plugin.vpnPlayer(entry.uuid().toString());
            if (profile == null) continue;
            if (entry.uuid() == null) whitelist.remove(null, entry.name());
            if (entry.uuid() == null || entry.name().isBlank() || !entry.name().equals(profile.name())) {
                whitelist.add(profile.uuid(), profile.name(), entry.addedBy());
                upgraded++;
            }
        }
        if (upgraded > 0) plugin.getLogger().info(upgraded + " Legacy-VPN-Whitelist-Einträge wurden mit vorhandenen Suite-Profilen UUID-sicher verknüpft.");
    }

    private String configuredChannel() {
        if (!plugin.getConfig().getBoolean("discord-bot.vpn-log.enabled", true)) return "deaktiviert";
        String channel = plugin.getConfig().getString("discord-bot.vpn-log.channel-id", "").trim();
        return channel.isBlank() ? "nicht gesetzt" : channel;
    }

    private static List<String> filter(String prefix, List<String> values) {
        String needle = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String value : values) if (value != null && value.toLowerCase(Locale.ROOT).startsWith(needle)) result.add(value);
        result.sort(String.CASE_INSENSITIVE_ORDER);
        return result.size() <= 80 ? result : new ArrayList<>(result.subList(0, 80));
    }

    private static boolean is(String value, String... options) {
        if (value == null) return false;
        for (String option : options) if (value.equalsIgnoreCase(option)) return true;
        return false;
    }

    private static String formatTime(long millis) { return millis <= 0 ? "Unbekannt" : TIME.format(Instant.ofEpochMilli(millis)); }
    private static String shorten(String value, int maximum) { return value == null ? "" : value.length() <= maximum ? value : value.substring(0, Math.max(0, maximum - 1)) + "…"; }
    private static String discordSafe(String value) { return (value == null ? "" : value).replace("`", "'").replace("*", ""); }
    private static String yesNo(boolean value) { return value ? "Ja" : "Nein"; }
    private static String decisionGerman(String decision) {
        if (decision == null) return "Unbekannt";
        return switch (decision.toUpperCase(Locale.ROOT)) {
            case "VPN_BLOCK" -> "VPN-/Proxy-Schwelle blockiert";
            case "API_FAILURE_BLOCK" -> "API-Ausfall sicherheitshalber blockiert";
            case "SUSPICIOUS_ALLOWED" -> "Auffällig, aber zugelassen";
            case "ALLOWED" -> "Zugelassen";
            case "WHITELIST" -> "Whitelist-Bypass";
            case "COOLDOWN" -> "Wiederverbindungs-Cooldown";
            default -> decision;
        };
    }
    private static String state(boolean active) { return active ? "§a✓" : "§c✗"; }
    private void sync(Runnable action) { plugin.runAdminTask( action); }

    private static final class Cooldown {
        private final long until;
        private Cooldown(long until) { this.until = until; }
    }
}
