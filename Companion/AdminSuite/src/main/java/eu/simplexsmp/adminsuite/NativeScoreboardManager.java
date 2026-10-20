package eu.simplexsmp.adminsuite;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Native Paper sidebar and active-world day counter.
 *
 * The board uses Paper's modern blank NumberFormat on both the objective and every individual
 * score. Applying it per score mirrors FastBoard 2.2's blank-score behaviour while retaining the
 * existing Bukkit scoreboard so AFK/LuckPerms name-tag teams can continue to share it safely.
 */
final class NativeScoreboardManager implements Listener {
    private static final String MENU_TITLE = "§0Simplex Scoreboard";
    private static final String RESET_TITLE = "§0Scoreboard zurücksetzen";
    private static final String DEFAULT_TITLE = "&x&0&0&A&8&E&8&ls&x&0&0&B&5&E&2&li&x&0&0&C&1&D&C&lm&x&0&0&C&E&D&6&lp&x&0&0&D&A&D&0&ll&x&0&0&E&7&C&A&le&x&0&0&F&3&C&4&lx&x&1&5&E&A&C&D&ls&x&2&A&E&1&D&6&lm&x&4&0&D&8&E&0&lp&x&5&5&C&F&E&7&l.&x&6&A&C&6&F&0&le&x&8&0&B&D&F&9&lu";
    private static final List<String> DEFAULT_LINES = List.of(
            "&8----------------", "&f ING-Days: &e{days}", "&f World: {world}",
            "&b Main: &f{main_players} &8• &2Vanilla: &f{second_players}",
            "&f Online: &b{online}&8/&7{max}", "&f Points: &b{points} SP",
            "&8----------------", "&9&lDC&8: &72gAsymnXfd");

    private static final class SettingsHolder implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }
    private static final class ResetHolder implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }

    private final SimplexAdminSuite plugin;
    private final File stateFile;
    private final Map<UUID, Board> boards = new ConcurrentHashMap<>();
    private final Map<UUID, String> pendingText = new ConcurrentHashMap<>();
    private final Map<UUID, Long> attachRetryAt = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> attachWarnings = ConcurrentHashMap.newKeySet();
    private long mainDays;
    private long secondDays;
    private Long pausedMainTime;
    private Long pausedSecondTime;
    private long lastMainDay = Long.MIN_VALUE;
    private long lastSecondDay = Long.MIN_VALUE;
    private BukkitTask boardTask;
    private BukkitTask timeTask;
    private boolean numberFormatWarningLogged;
    private boolean numberFormatSuccessLogged;

    NativeScoreboardManager(SimplexAdminSuite plugin) {
        this.plugin = plugin;
        this.stateFile = new File(plugin.getDataFolder(), "scoreboard-days.yml");
        load();
    }

    void start() {
        stopTasks();
        if (!enabled()) return;
        for (Player player : Bukkit.getOnlinePlayers()) attach(player);
        long updateTicks = Math.max(10L, Math.min(1200L, plugin.getConfig().getLong("scoreboard.update-ticks", 20L)));
        boardTask = Bukkit.getScheduler().runTaskTimer(plugin, this::updateAll, 20L, updateTicks);
        timeTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickWorlds, 20L, 20L);
    }

    void reload() {
        detachAll();
        load();
        start();
    }

    void stop() {
        save();
        stopTasks();
        detachAll();
        pendingText.clear();
    }

    private void stopTasks() {
        if (boardTask != null) try { boardTask.cancel(); } catch (Exception ignored) { }
        if (timeTask != null) try { timeTask.cancel(); } catch (Exception ignored) { }
        boardTask = null;
        timeTask = null;
    }

    boolean enabled() { return plugin.getConfig().getBoolean("scoreboard.enabled", true); }
    void saveForBackup() { save(); }
    long mainDays() { return mainDays; }
    long secondDays() { return secondDays; }
    long daysFor(World world) { return isSecondGroup(world) ? secondDays : mainDays; }

    String worldDisplay(World world) {
        if (world == null) return color("&7Other");
        String name = world.getName();
        if (isMainGroup(world)) {
            if (name.endsWith("_nether")) return color("&cMain");
            if (name.endsWith("_the_end")) return color("&dMain");
            return color("&aMain");
        }
        if (isSecondGroup(world)) {
            if (name.endsWith("_nether")) return color("&4Vanilla");
            if (name.endsWith("_the_end")) return color("&5Vanilla");
            return color("&2Vanilla");
        }
        return color("&7Other");
    }

    void setDays(boolean second, long value, String actor) {
        if (second) secondDays = Math.max(0L, value); else mainDays = Math.max(0L, value);
        save();
        updateAll();
        plugin.suiteAudit(actor, "Scoreboard-Tage gesetzt", (second ? "Zweitwelt=" : "Hauptwelt=") + Math.max(0L, value));
    }

    void resetBoards() {
        detachAll();
        if (enabled()) for (Player player : Bukkit.getOnlinePlayers()) attach(player);
        updateAll();
    }

    private void resetDefaults(String actor) {
        plugin.getConfig().set("scoreboard.enabled", true);
        plugin.getConfig().set("scoreboard.title", DEFAULT_TITLE);
        plugin.getConfig().set("scoreboard.lines", new ArrayList<>(DEFAULT_LINES));
        plugin.getConfig().set("scoreboard.main-world", "world");
        plugin.getConfig().set("scoreboard.second-world.enabled", true);
        plugin.getConfig().set("scoreboard.second-world.name", "survival2");
        plugin.getConfig().set("scoreboard.update-ticks", 20L);
        plugin.getConfig().set("scoreboard.hide-java-score-numbers", true);
        plugin.saveConfig();
        reload();
        plugin.suiteAudit(actor, "Scoreboard auf Standard zurückgesetzt", "Tageszähler wurden beibehalten");
    }

    boolean handleCommand(org.bukkit.command.CommandSender sender, String command, String[] args) {
        String root = command.toLowerCase(Locale.ROOT);
        if (root.equals("scoreboard")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(plugin.prefix() + "Scoreboard: " + (enabled() ? "aktiv" : "inaktiv") + ", Hauptwelt " + mainDays + " Tage, Zweitwelt " + secondDays + " Tage.");
                return true;
            }
            Player player = (Player) sender;
            if (args.length == 0) {
                if (plugin.has(player, "simplexadminsuite.scoreboard.admin")) openSettings(player);
                else player.sendMessage(plugin.playerUiText(player,plugin.prefix() + "Server day " + daysFor(player.getWorld()) + " • " + plugin.expansionPoints(player) + " Simplex Points."));
                return true;
            }
            if (!plugin.has(player, "simplexadminsuite.scoreboard.admin")) {
                player.sendMessage(plugin.playerUiText(player,plugin.prefix() + "You do not have permission to change the scoreboard."));
                return true;
            }
            if (args[0].equalsIgnoreCase("toggle")) {
                plugin.getConfig().set("scoreboard.enabled", !enabled()); plugin.saveConfig(); reload(); openSettings(player); return true;
            }
            if (args[0].equalsIgnoreCase("reset")) { resetBoards(); plugin.menuNotice(player,plugin.prefix() + "Scoreboard wurde für alle Spieler neu aufgebaut."); return true; }
            if (args[0].equalsIgnoreCase("defaults")) {
                if (args.length > 1 && args[1].equalsIgnoreCase("confirm")) {
                    resetDefaults(player.getName());
                    plugin.menuNotice(player,plugin.prefix() + "Scoreboard wurde auf Standard zurückgesetzt. Die Tageszähler wurden nicht verändert.");
                } else openJavaResetConfirmation(player);
                return true;
            }
            if (args[0].equalsIgnoreCase("title") && args.length > 1) {
                String previous = plugin.getConfig().getString("scoreboard.title", DEFAULT_TITLE);
                plugin.getConfig().set("scoreboard.title", preserveFormatting(previous, String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length))));
                plugin.saveConfig(); updateAll(); return true;
            }
            return true;
        }
        if (!plugin.has(sender, "simplexadminsuite.scoreboard.admin")) { sender.sendMessage(plugin.prefix() + "Dafür fehlt dir simplexadminsuite.scoreboard.admin."); return true; }
        if (root.equals("sbreset")) { resetBoards(); plugin.menuNotice(sender,plugin.prefix() + "Scoreboard wurde für alle Spieler neu aufgebaut."); return true; }
        if (root.equals("setdays") || root.equals("setvanilladays")) {
            if (args.length != 1) { sender.sendMessage(plugin.prefix() + "Verwendung: /" + root + " <Tage>"); return true; }
            try {
                long value = Long.parseLong(args[0]);
                if (value < 0L) throw new NumberFormatException("negative");
                setDays(root.equals("setvanilladays"), value, sender.getName());
                plugin.menuNotice(sender,plugin.prefix() + (root.equals("setvanilladays") ? "Zweitwelt" : "Hauptwelt") + "-Tage wurden auf " + value + " gesetzt.");
            } catch (NumberFormatException error) { sender.sendMessage(plugin.prefix() + "Bitte gib eine nichtnegative ganze Zahl ein."); }
            return true;
        }
        return false;
    }

    boolean consumePendingChat(Player player, String message) {
        boolean[] handled={false};plugin.asAdmin(player==null?null:player.getName(),()->handled[0]=consumeAuditedPendingChat(player,message));return handled[0];
    }
    private boolean consumeAuditedPendingChat(Player player, String message) {
        String action = player == null ? null : pendingText.remove(player.getUniqueId());
        if (action == null) return false;
        if (message.equalsIgnoreCase("cancel")) { player.sendMessage(plugin.prefix() + "Eingabe abgebrochen."); openJavaSettings(player); return true; }
        if (action.equals("title")) {
            String previous = plugin.getConfig().getString("scoreboard.title", DEFAULT_TITLE);
            plugin.getConfig().set("scoreboard.title", preserveFormatting(previous, clean(message, 256)));
        } else if (action.equals("lines")) {
            List<String> oldLines = lines();
            List<String> configured = parseLinesPreservingFormatting(message, oldLines);
            if (!configured.isEmpty()) plugin.getConfig().set("scoreboard.lines", configured);
        } else if (action.equals("main-days") || action.equals("second-days")) {
            try { setDays(action.equals("second-days"), Long.parseLong(message.trim()), player.getName()); }
            catch (Exception error) { plugin.menuNotice(player,plugin.prefix() + "Ungültige Zahl. Die Tage wurden nicht verändert."); }
        }
        plugin.saveConfig();
        updateAll();
        plugin.runAdminTask( () -> openJavaSettings(player));
        return true;
    }

    void openSettings(Player admin) {
        if (!plugin.adminMenuAllowed(admin, "nativescoreboardmanager.opensettings")) return;
        if (plugin.isBedrockClient(admin)) {
            if (!openBedrockSettings(admin)) plugin.menuNotice(admin,plugin.prefix() + "Das native Bedrock-Scoreboard-Menü konnte nicht gesendet werden. Bitte prüfe /sas form.");
            return;
        }
        openJavaSettings(admin);
    }

    void openJavaSettings(Player admin) {
        if (!plugin.adminMenuAllowed(admin, "nativescoreboardmanager.openjavasettings")) return;
        Inventory inventory = LiveMenus.create(new SettingsHolder(), 36, MENU_TITLE);
        fill(inventory);
        inventory.setItem(10, item(enabled() ? Material.LIME_DYE : Material.RED_DYE, enabled() ? "&aScoreboard aktiviert" : "&cScoreboard deaktiviert", "&7Klicken zum Umschalten"));
        inventory.setItem(11, item(Material.NAME_TAG, "&eTitel bearbeiten", "&7Aktueller Titel:", "&f" + strip(plugin.getConfig().getString("scoreboard.title", DEFAULT_TITLE)), "&7Farbcodes mit & oder § bleiben erhalten."));
        inventory.setItem(12, item(Material.WRITABLE_BOOK, "&eZeilen bearbeiten", "&7Zeilen im Chat mit | trennen", "&7Platzhalter: {days}, {world}, {online},", "&7{max}, {points}, {main_players}, {second_players}"));
        inventory.setItem(13, item(Material.CLOCK, "&bHauptwelt-Tage", "&7Aktuell: &f" + mainDays, "&7Klicken zum Setzen"));
        inventory.setItem(14, item(Material.RECOVERY_COMPASS, "&dZweitwelt", "&7Name: &f" + secondWorld(), "&7Status: " + (secondEnabled() ? "&aaktiv" : "&cinaktiv"), "&7Linksklick: Tage setzen", "&7Rechtsklick: Welt aktivieren/deaktivieren"));
        inventory.setItem(15, item(Material.COMPARATOR, "&eAktualisierung", "&7Aktuell: &f" + plugin.getConfig().getLong("scoreboard.update-ticks", 20L) + " Ticks", "&7Klicken: 10/20/40/100 Ticks"));
        inventory.setItem(16, item(Material.EMERALD, "&aScoreboard neu aufbauen", "&7Stellt alle Sidebars sauber wieder her", "&7und sendet die leere Zahlenformatierung neu."));
        inventory.setItem(20, item(Material.REDSTONE_BLOCK, "&cAuf Standard zurücksetzen", "&7Titel, Zeilen, Welten und Aktualisierung", "&7werden nach einer Bestätigung zurückgesetzt.", "&aTageszähler bleiben erhalten."));
        inventory.setItem(31, item(Material.BARRIER, "&cZurück", "&7Zurück zum Startmenü."));
        LiveMenus.show(admin,inventory);
    }

    private void openJavaResetConfirmation(Player admin) {
        if (!plugin.adminMenuAllowed(admin, "nativescoreboardmanager.openjavaresetconfirmation")) return;
        if (plugin.isBedrockClient(admin)) { openBedrockResetConfirmation(admin); return; }
        Inventory inventory = LiveMenus.create(new ResetHolder(), 27, RESET_TITLE);
        fill(inventory);
        inventory.setItem(11, item(Material.LIME_DYE, "&aJa, Standard wiederherstellen", "&7Setzt Darstellung und Welten zurück.", "&aDie gespeicherten Tageszähler bleiben erhalten."));
        inventory.setItem(13, item(Material.WRITABLE_BOOK, "&eWas wird zurückgesetzt?", "&7• Aktivierung und Titel", "&7• Zeilen und Farbcodes", "&7• Haupt-/Zweitwelt und Update-Intervall", "&7• Java-Zahlen werden ausgeblendet"));
        inventory.setItem(15, item(Material.RED_DYE, "&cAbbrechen", "&7Zurück zu den Scoreboard-Einstellungen."));
        LiveMenus.show(admin,inventory);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        plugin.asAdmin(event.getWhoClicked().getName(),()->handleAuditedInventoryClick(event));
    }
    private void handleAuditedInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player admin = (Player) event.getWhoClicked();
        InventoryHolder holder = event.getInventory() == null ? null : LiveMenus.holder(event.getInventory());
        String title = inventoryTitle(event);
        if (!(holder instanceof SettingsHolder) && !(holder instanceof ResetHolder) && !MENU_TITLE.equals(title) && !RESET_TITLE.equals(title)) return;
        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (holder instanceof ResetHolder || RESET_TITLE.equals(title)) {
            if (slot == 11) {
                resetDefaults(admin.getName());
                plugin.menuNotice(admin,plugin.prefix() + "Scoreboard wurde auf Standard zurückgesetzt. Die Tageszähler wurden beibehalten.");
                openJavaSettings(admin);
            } else if (slot == 15) openJavaSettings(admin);
            return;
        }
        if (slot == 10) { plugin.getConfig().set("scoreboard.enabled", !enabled()); plugin.saveConfig(); reload(); openJavaSettings(admin); }
        else if (slot == 11) prompt(admin, "title", "Gib den neuen Scoreboard-Titel ein oder 'cancel'. Farbcodes mit & oder § sind möglich.");
        else if (slot == 12) prompt(admin, "lines", "Gib bis zu 15 Zeilen ein, getrennt mit |, oder 'cancel'.");
        else if (slot == 13) prompt(admin, "main-days", "Gib den neuen Hauptwelt-Tagesstand ein oder 'cancel'.");
        else if (slot == 14 && event.isRightClick()) { plugin.getConfig().set("scoreboard.second-world.enabled", !secondEnabled()); plugin.saveConfig(); reload(); openJavaSettings(admin); }
        else if (slot == 14) prompt(admin, "second-days", "Gib den neuen Zweitwelt-Tagesstand ein oder 'cancel'.");
        else if (slot == 15) {
            long current = plugin.getConfig().getLong("scoreboard.update-ticks", 20L);
            long next = current < 20L ? 20L : current < 40L ? 40L : current < 100L ? 100L : 10L;
            plugin.getConfig().set("scoreboard.update-ticks", next); plugin.saveConfig(); reload(); openJavaSettings(admin);
        } else if (slot == 16) { resetBoards(); openJavaSettings(admin); }
        else if (slot == 20) openJavaResetConfirmation(admin);
        else if (slot == 31) plugin.openMainFromModule(admin);
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        Inventory inventory = event.getInventory();
        InventoryHolder holder = inventory == null ? null : LiveMenus.holder(inventory);
        if (holder instanceof SettingsHolder || holder instanceof ResetHolder || MENU_TITLE.equals(inventoryTitle(event.getView())) || RESET_TITLE.equals(inventoryTitle(event.getView()))) event.setCancelled(true);
    }

    private void prompt(Player player, String action, String message) {
        pendingText.put(player.getUniqueId(), action);
        player.closeInventory();
        player.sendMessage(plugin.prefix() + message);
    }

    private boolean openBedrockSettings(Player admin) {
        if (!plugin.adminMenuAllowed(admin, "nativescoreboardmanager.openbedrocksettings")) return true;
        try {
            if (!plugin.isBedrockClient(admin)) return false;
            SimplexAdminSuite.SimpleForm.Builder form = SimplexAdminSuite.SimpleForm.builder()
                    .title("§l§bSIMPLEX SCOREBOARD")
                    .content("§fStatus: " + (enabled() ? "§aAktiv" : "§cInaktiv")
                            + "\n§fHauptwelt-Tage: §e" + mainDays
                            + "\n§fZweitwelt-Tage: §d" + secondDays
                            + "\n\n§7Formatierungen werden intern als &-Codes gespeichert, damit Bedrock sie beim Bearbeiten nicht verliert.")
                    .button("§eEinstellungen bearbeiten", response -> plugin.runAdminTask( () -> openBedrockEditor(admin)))
                    .button("§aScoreboard neu aufbauen", response -> plugin.runAdminTask( () -> { resetBoards(); plugin.menuNotice(admin,plugin.prefix() + "Scoreboard wurde neu aufgebaut."); openBedrockSettings(admin); }))
                    .button("§cAuf Standard zurücksetzen", response -> plugin.runAdminTask( () -> openBedrockResetConfirmation(admin)))
                    .button("§8Zurück zum Start", response -> plugin.runAdminTask( () -> plugin.openMainFromModule(admin)));
            return plugin.sendBedrockForm(admin, form);
        } catch (Throwable error) {
            plugin.getLogger().warning("Bedrock-Scoreboard-Menü für " + admin.getName() + " fehlgeschlagen: " + error.getClass().getSimpleName() + ": " + error.getMessage());
            return false;
        }
    }

    private boolean openBedrockEditor(Player admin) {
        if (!plugin.adminMenuAllowed(admin, "nativescoreboardmanager.openbedrockeditor")) return true;
        try {
            String oldTitle = plugin.getConfig().getString("scoreboard.title", DEFAULT_TITLE);
            List<String> oldLines = new ArrayList<>(lines());
            SimplexAdminSuite.CustomForm.Builder form = SimplexAdminSuite.CustomForm.builder()
                    .title("Simplex Scoreboard • Bearbeiten")
                    .toggle("Scoreboard aktiviert", enabled())
                    .input("Titel (&-Farbcodes)", "&bMein Scoreboard", normalizeFormattingCodes(oldTitle))
                    .input("Zeilen (mit | trennen)", "&7{days} | &b{online}", joinNormalized(oldLines))
                    .input("Hauptwelt", "world", mainWorld())
                    .input("Hauptwelt-Tage", "0", Long.toString(mainDays))
                    .toggle("Zweitwelt aktiviert", secondEnabled())
                    .input("Zweitwelt", "survival2", secondWorld())
                    .input("Zweitwelt-Tage", "0", Long.toString(secondDays))
                    .slider("Aktualisierung (Ticks)", 10.0F, 100.0F, 10.0F, (float) Math.max(10L, Math.min(100L, plugin.getConfig().getLong("scoreboard.update-ticks", 20L))))
                    .validResultHandler(response -> plugin.runAdminTask( () -> {
                        plugin.getConfig().set("scoreboard.enabled", response.asToggle(0));
                        plugin.getConfig().set("scoreboard.title", preserveFormatting(oldTitle, response.asInput(1)));
                        List<String> configured = parseLinesPreservingFormatting(response.asInput(2), oldLines);
                        if (!configured.isEmpty()) plugin.getConfig().set("scoreboard.lines", configured);
                        plugin.getConfig().set("scoreboard.main-world", cleanWorld(response.asInput(3), "world"));
                        try { mainDays = Math.max(0L, Long.parseLong(response.asInput(4).trim())); } catch (Exception ignored) { }
                        plugin.getConfig().set("scoreboard.second-world.enabled", response.asToggle(5));
                        plugin.getConfig().set("scoreboard.second-world.name", cleanWorld(response.asInput(6), "survival2"));
                        try { secondDays = Math.max(0L, Long.parseLong(response.asInput(7).trim())); } catch (Exception ignored) { }
                        plugin.getConfig().set("scoreboard.update-ticks", Math.round(response.asSlider(8)));
                        plugin.getConfig().set("scoreboard.hide-java-score-numbers", true);
                        plugin.saveConfig(); save(); reload();
                        plugin.menuNotice(admin,plugin.prefix() + "Scoreboard-Einstellungen wurden gespeichert; Farbcodes wurden geschützt übernommen.");
                        openBedrockSettings(admin);
                    }))
                    .closedOrInvalidResultHandler(() -> plugin.runAdminTask( () -> openBedrockSettings(admin)));
            return plugin.sendBedrockForm(admin, form);
        } catch (Throwable error) {
            plugin.getLogger().warning("Bedrock-Scoreboard-Editor für " + admin.getName() + " fehlgeschlagen: " + error.getClass().getSimpleName() + ": " + error.getMessage());
            return false;
        }
    }

    private boolean openBedrockResetConfirmation(Player admin) {
        if (!plugin.adminMenuAllowed(admin, "nativescoreboardmanager.openbedrockresetconfirmation")) return true;
        try {
            SimplexAdminSuite.SimpleForm.Builder form = SimplexAdminSuite.SimpleForm.builder()
                    .title("§l§cSCOREBOARD ZURÜCKSETZEN")
                    .content("§fTitel, Zeilen, Weltnamen und Aktualisierung werden auf den Auslieferungszustand gesetzt.\n\n§aDie Hauptwelt- und Zweitwelt-Tageszähler bleiben vollständig erhalten.")
                    .button("§aJa, Standard wiederherstellen", response -> plugin.runAdminTask( () -> {
                        resetDefaults(admin.getName());
                        plugin.menuNotice(admin,plugin.prefix() + "Scoreboard wurde auf Standard zurückgesetzt. Die Tageszähler wurden beibehalten.");
                        openBedrockSettings(admin);
                    }))
                    .button("§cAbbrechen", response -> plugin.runAdminTask( () -> openBedrockSettings(admin)));
            return plugin.sendBedrockForm(admin, form);
        } catch (Throwable error) {
            plugin.getLogger().warning("Bedrock-Scoreboard-Bestätigung für " + admin.getName() + " fehlgeschlagen: " + error.getMessage());
            return false;
        }
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) { Bukkit.getScheduler().runTaskLater(plugin, () -> attach(event.getPlayer()), 40L); }
    @EventHandler public void onQuit(PlayerQuitEvent event) { detach(event.getPlayer()); }
    @EventHandler public void onWorldChange(PlayerChangedWorldEvent event) { plugin.runAdminTask( () -> update(event.getPlayer())); }

    private void tickWorlds() {
        tickGroup(false);
        if (secondEnabled()) tickGroup(true);
    }

    private void tickGroup(boolean second) {
        String rootName = second ? secondWorld() : mainWorld();
        World root = Bukkit.getWorld(rootName);
        if (root == null) return;
        int players = countGroup(second, false);
        long fullTime = worldFullTime(root);
        if (players <= 0) {
            Long paused = second ? pausedSecondTime : pausedMainTime;
            if (paused == null) paused = fullTime;
            setWorldFullTime(root, paused);
            if (second) pausedSecondTime = paused; else pausedMainTime = paused;
            return;
        }
        if (second) pausedSecondTime = null; else pausedMainTime = null;
        long day = Math.max(0L, fullTime / 24_000L);
        long previous = second ? lastSecondDay : lastMainDay;
        if (previous == Long.MIN_VALUE) {
            if (second) lastSecondDay = day; else lastMainDay = day;
            return;
        }
        if (day > previous) {
            long increment = Math.min(100L, day - previous);
            if (second) secondDays += increment; else mainDays += increment;
            save(); updateAll();
        }
        if (second) lastSecondDay = day; else lastMainDay = day;
    }

    private int countGroup(boolean second, boolean publicCount) {
        int count = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (publicCount && !plugin.scoreboardPlayerVisible(player)) continue;
            if (second ? isSecondGroup(player.getWorld()) : isMainGroup(player.getWorld())) count++;
        }
        return count;
    }

    private boolean isMainGroup(World world) { return inGroup(world, mainWorld()); }
    private boolean isSecondGroup(World world) { return secondEnabled() && inGroup(world, secondWorld()); }
    private boolean inGroup(World world, String root) {
        if (world == null || root == null || root.isBlank()) return false;
        String name = world.getName();
        return name.equals(root) || name.equals(root + "_nether") || name.equals(root + "_the_end");
    }

    private String mainWorld() { return plugin.getConfig().getString("scoreboard.main-world", "world"); }
    private String secondWorld() { return plugin.getConfig().getString("scoreboard.second-world.name", "survival2"); }
    private boolean secondEnabled() { return plugin.getConfig().getBoolean("scoreboard.second-world.enabled", true); }

    private long worldFullTime(World world) { try { return world.getFullTime(); } catch (Throwable ignored) { return 0L; } }
    private void setWorldFullTime(World world, long value) { try { world.setFullTime(Math.max(0L, value)); } catch (Throwable ignored) { } }
    private void updateAll() { if (enabled()) for (Player player : Bukkit.getOnlinePlayers()) update(player); }

    private void attach(Player player) {
        if (player == null || !enabled()) return;
        long now = System.currentTimeMillis();
        if (now < attachRetryAt.getOrDefault(player.getUniqueId(), 0L)) return;
        try {
            Scoreboard original = player.getScoreboard();
            ScoreboardManager manager = Bukkit.getScoreboardManager();
            if (manager == null) throw new IllegalStateException("Bukkit ScoreboardManager ist nicht verfügbar");
            Scoreboard scoreboard = manager.getNewScoreboard();
            Objective objective = scoreboard.registerNewObjective("simplex_sidebar", "dummy", color(plugin.getConfig().getString("scoreboard.title", DEFAULT_TITLE)));
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);
            applyBlankNumberFormat(objective, "objective");
            player.setScoreboard(scoreboard);
            boards.put(player.getUniqueId(), new Board(original, scoreboard, objective));
            attachRetryAt.remove(player.getUniqueId());
            if (attachWarnings.remove(player.getUniqueId())) plugin.getLogger().info("Scoreboard für " + player.getName() + " wurde nach einem vorherigen Fehler wiederhergestellt.");
            update(player);
        } catch (Throwable error) {
            attachRetryAt.put(player.getUniqueId(), now + 60_000L);
            Throwable root = rootCause(error);
            if (attachWarnings.add(player.getUniqueId())) plugin.getLogger().warning("Scoreboard konnte für " + player.getName()
                    + " nicht erstellt werden; neuer Versuch in 60 Sekunden: " + root.getClass().getSimpleName()
                    + (root.getMessage() == null ? "" : ": " + root.getMessage()));
        }
    }

    private boolean applyBlankNumberFormat(Object target, String kind) {
        if (!plugin.getConfig().getBoolean("scoreboard.hide-java-score-numbers", true) || target == null) return false;
        try {
            Class<?> numberFormatType = Class.forName("io.papermc.paper.scoreboard.numbers.NumberFormat");
            Object blank = numberFormatType.getMethod("blank").invoke(null);
            Method setter = null;
            for (Method method : target.getClass().getMethods()) {
                if (!(method.getName().equals("numberFormat") || method.getName().equals("setNumberFormat"))) continue;
                if (method.getParameterCount() != 1 || !method.getParameterTypes()[0].isInstance(blank)) continue;
                setter = method; break;
            }
            if (setter == null) {
                try { setter = target.getClass().getMethod("numberFormat", numberFormatType); }
                catch (NoSuchMethodException ignored) { }
            }
            if (setter == null) throw new NoSuchMethodException("numberFormat(NumberFormat)");
            try { setter.setAccessible(true); } catch (Throwable ignored) { }
            setter.invoke(target, blank);

            // Verify where Paper exposes the getter. This catches silent proxy/API mismatches.
            try {
                Method getter = target.getClass().getMethod("numberFormat");
                Object applied = getter.invoke(target);
                if (applied == null) throw new IllegalStateException("NumberFormat blieb null");
            } catch (NoSuchMethodException ignored) { }
            if (!numberFormatSuccessLogged) {
                numberFormatSuccessLogged = true;
                plugin.getLogger().info("Java-Scoreboard-Zahlen werden über Paper NumberFormat.blank() ausgeblendet (" + kind + ").");
            }
            return true;
        } catch (Throwable error) {
            if (!numberFormatWarningLogged) {
                numberFormatWarningLogged = true;
                Throwable root = rootCause(error);
                plugin.getLogger().warning("Leere Java-Scoreboard-Zahlenformatierung konnte nicht aktiviert werden: "
                        + root.getClass().getSimpleName() + (root.getMessage() == null ? "" : ": " + root.getMessage())
                        + ". Der Server muss Paper 1.20.3+ verwenden.");
            }
            return false;
        }
    }

    void refreshPlayer(Player player) { if(enabled())update(player); }
    private void update(Player player) {
        Board board = boards.get(player.getUniqueId());
        if (board == null) { attach(player); return; }
        try {
            boolean full = plugin.community == null || plugin.community.scoreboard(player);
            board.objective.setDisplayName(full ? color(plugin.getConfig().getString("scoreboard.title", DEFAULT_TITLE)) : "§b§lSimplex SMP");
            applyBlankNumberFormat(board.objective, "objective-update");
            for (String old : board.entries) board.scoreboard.resetScores(old);
            board.entries.clear();
            List<String> rendered = full ? renderLines(player) : List.of(); // Compact mode uses only the objective title; no blank header or body row.
            int scoreValue = rendered.size();
            for (int index = 0; index < rendered.size(); index++) {
                String entry = unique(rendered.get(index), index);
                org.bukkit.scoreboard.Score score = board.objective.getScore(entry);
                score.setScore(scoreValue--);
                applyBlankNumberFormat(score, "line");
                board.entries.add(entry);
            }
            board.failures = 0;
        } catch (Throwable error) {
            if (board.failures++ == 0) plugin.getLogger().warning("Scoreboard-Aktualisierung für " + player.getName() + " fehlgeschlagen: " + error.getClass().getSimpleName() + (error.getMessage() == null ? "" : ": " + error.getMessage()));
        }
    }

    private List<String> renderLines(Player player) {
        boolean german=plugin.community!=null&&plugin.community.german(player);
        int online = plugin.visibleOnlineCountExcluding(null);
        int max = maxPlayers();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("{days}", Long.toString(daysFor(player.getWorld())));
        values.put("{world}", german ? worldDisplay(player.getWorld()).replace("Main","Haupt").replace("Other","Andere") : worldDisplay(player.getWorld()));
        values.put("{main_players}", Integer.toString(countGroup(false, true)));
        values.put("{second_players}", Integer.toString(secondEnabled() ? countGroup(true, true) : 0));
        values.put("{online}", Integer.toString(online));
        values.put("{max}", Integer.toString(max));
        values.put("{points}", Long.toString(plugin.expansionPoints(player)));
        values.put("{player}", plugin.visualName(player));
        List<String> rendered = new ArrayList<>();
        List<String> configuredGerman=plugin.getConfig().getStringList("scoreboard.lines-de");
        for (String raw : german&&!configuredGerman.isEmpty()?configuredGerman:lines()) {
            if (!plugin.getConfig().getBoolean("simplex-points.enabled", true) && raw.contains("{points}")) continue;
            String line = german?raw.replace("ING-Days:","Spieltage:").replace("World:","Welt:").replace("Points:","Punkte:").replace("Main:","Haupt:").replace("Hauptwelt:","Haupt:").replace("Haupt Welt:","Haupt:"):raw;
            for (Map.Entry<String, String> entry : values.entrySet()) line = line.replace(entry.getKey(), entry.getValue());
            rendered.add(color(line));
            if (rendered.size() >= 15) break;
        }
        return rendered;
    }

    private List<String> lines() {
        List<String> configured = plugin.getConfig().getStringList("scoreboard.lines");
        return configured == null || configured.isEmpty() ? DEFAULT_LINES : configured;
    }

    private int maxPlayers() {
        try { return ((Number) Bukkit.class.getMethod("getMaxPlayers").invoke(null)).intValue(); }
        catch (Throwable ignored) { return Math.max(plugin.visibleOnlineCountExcluding(null), 1); }
    }

    private String unique(String value, int index) {
        String[] codes = {"0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "a", "b", "c", "d", "e"};
        return value + "§r§" + codes[Math.max(0, Math.min(codes.length - 1, index))];
    }

    private void detach(Player player) {
        if (player == null) return;
        Board board = boards.remove(player.getUniqueId());
        attachRetryAt.remove(player.getUniqueId());
        attachWarnings.remove(player.getUniqueId());
        if (board == null || board.original == null) return;
        try { player.setScoreboard(board.original); } catch (Throwable ignored) { }
    }

    private void detachAll() {
        for (Player player : Bukkit.getOnlinePlayers()) detach(player);
        boards.clear();
    }

    private void load() {
        if (!stateFile.isFile()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(stateFile);
        mainDays = Math.max(0L, yaml.getLong("main.days", 0L));
        secondDays = Math.max(0L, yaml.getLong("second.days", 0L));
        lastMainDay = yaml.getLong("main.last-world-day", Long.MIN_VALUE);
        lastSecondDay = yaml.getLong("second.last-world-day", Long.MIN_VALUE);
    }

    private void save() {
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("schema-version", 1);
            yaml.set("main.days", mainDays); yaml.set("main.last-world-day", lastMainDay);
            yaml.set("second.days", secondDays); yaml.set("second.last-world-day", lastSecondDay);
            yaml.set("updated-at", System.currentTimeMillis());
            SimplexAdminSuite.saveAtomic(yaml, stateFile);
        } catch (Exception error) { plugin.getLogger().warning("Scoreboard-Tage konnten nicht gespeichert werden: " + error.getMessage()); }
    }

    private static List<String> parseLinesPreservingFormatting(String input, List<String> previous) {
        List<String> configured = new ArrayList<>();
        String source = input == null ? "" : input;
        String[] parts = source.split("\\|", -1);
        for (int index = 0; index < parts.length && configured.size() < 15; index++) {
            String submitted = clean(parts[index].trim(), 180);
            if (submitted.isBlank()) continue;
            String old = previous != null && index < previous.size() ? previous.get(index) : "";
            configured.add(preserveFormatting(old, submitted));
        }
        return configured;
    }

    private static String preserveFormatting(String previous, String submitted) {
        String normalized = normalizeFormattingCodes(clean(submitted, 256));
        if (normalized.isBlank()) return normalized;
        if (!containsFormatting(normalized) && previous != null && !previous.isBlank()) {
            String oldVisible = strip(previous);
            String newVisible = strip(normalized);
            if (oldVisible != null && newVisible != null && oldVisible.trim().equals(newVisible.trim())) return normalizeFormattingCodes(previous);
        }
        return normalized;
    }

    private static boolean containsFormatting(String value) {
        if (value == null) return false;
        return value.matches("(?s).*(?:&|§)(?:[0-9A-FK-ORa-fk-or]|x(?:&[0-9A-Fa-f]){6}).*");
    }

    private static String normalizeFormattingCodes(String value) {
        return value == null ? "" : value.replace('§', '&');
    }

    private static String joinNormalized(List<String> values) {
        List<String> normalized = new ArrayList<>();
        for (String value : values) normalized.add(normalizeFormattingCodes(value));
        return String.join(" | ", normalized);
    }

    private static String inventoryTitle(InventoryClickEvent event) { try { return inventoryTitle(event.getView()); } catch (Throwable ignored) { return ""; } }
    private static String inventoryTitle(org.bukkit.inventory.InventoryView view) { try { return view == null ? "" : LiveMenus.title(view); } catch (Throwable ignored) { return ""; } }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        while (current.getCause() != null && seen.add(current)) current = current.getCause();
        return current;
    }

    private static String clean(String text, int max) {
        String value = text == null ? "" : text.replace('\n', ' ').replace('\r', ' ').trim();
        return value.length() <= max ? value : value.substring(0, max);
    }
    private static String cleanWorld(String text, String fallback) {
        String value = clean(text, 80).replaceAll("[^A-Za-z0-9_.-]", "");
        return value.isBlank() ? fallback : value;
    }
    private static String strip(String value) { return ChatColor.stripColor(color(value)); }
    private static String color(String value) { return ChatColor.translateAlternateColorCodes('&', value == null ? "" : value); }

    private static ItemStack item(Material material, String name, String... lore) {
        ItemStack stack = new ItemStack(MenuStyle.actionMaterial(name,material));
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(MenuStyle.javaText(name));
            List<String> lines = new ArrayList<>();
            for (String line : lore) lines.add(MenuStyle.javaText(line));
            meta.setLore(lines); stack.setItemMeta(meta);
        }
        return stack;
    }
    private static void fill(Inventory inventory) {
        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, filler);
    }

    private static final class Board {
        private final Scoreboard original, scoreboard;
        private final Objective objective;
        private final List<String> entries = new ArrayList<>();
        private int failures;
        private Board(Scoreboard original, Scoreboard scoreboard, Objective objective) { this.original = original; this.scoreboard = scoreboard; this.objective = objective; }
    }
}
