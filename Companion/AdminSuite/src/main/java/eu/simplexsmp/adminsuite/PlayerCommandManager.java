package eu.simplexsmp.adminsuite;

import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.bukkit.event.server.TabCompleteEvent;

/** Filters the existing command tree using effective Bukkit permissions (including LuckPerms). */
final class PlayerCommandManager implements Listener {
  private final SimplexAdminSuite plugin;
  private Set<String> blocked = Set.of();
  private boolean enabled, hideUnpermitted;

  PlayerCommandManager(SimplexAdminSuite plugin) {
    this.plugin = plugin;
    reload();
  }

  void reload() {
    enabled = plugin.getConfig().getBoolean("command-management.enabled", true);
    hideUnpermitted = plugin.getConfig().getBoolean("command-management.hide-unpermitted", true);
    Set<String> roots = new LinkedHashSet<>();
    for (String configured : plugin.getConfig().getStringList("command-management.blocked")) {
      String root = configuredRoot(configured);
      if (!root.isEmpty()) roots.add(root);
      else
        plugin
            .getLogger()
            .warning("Ungültiger Eintrag in command-management.blocked wurde ignoriert.");
    }
    blocked = Set.copyOf(roots);
    refresh();
  }

  static String configuredRoot(String value) {
    if (value == null) return "";
    String root = value.strip().toLowerCase(Locale.ROOT).replaceFirst("^/", "");
    return root.matches("[a-z0-9_.-]+(?::[a-z0-9_.-]+)?") ? root : "";
  }

  static String messageRoot(String message) {
    if (message == null) return "";
    String text = message.stripLeading().replaceFirst("^/", "");
    int end = 0;
    while (end < text.length() && !Character.isWhitespace(text.charAt(end))) end++;
    return configuredRoot(text.substring(0, end));
  }

  private Command command(String root) {
    return Bukkit.getCommandMap().getCommand(root);
  }

  static boolean matches(
      Set<String> blocked,
      String root,
      Command command,
      java.util.function.Function<String, Command> lookup) {
    String bare = root.substring(root.indexOf(':') + 1);
    if (blocked.contains(root) || blocked.contains(bare)) return true;
    if (command == null) return false;
    for (String entry : blocked) {
      Command denied = lookup.apply(entry);
      if (denied == command)
        return true; // Registered aliases and namespaces refer to the same command.
      if (!entry.contains(":")) {
        if (entry.equalsIgnoreCase(command.getName())) return true;
        for (String alias : command.getAliases()) if (entry.equalsIgnoreCase(alias)) return true;
      }
    }
    return false;
  }

  private boolean exempt(Player player) {
    return player.isOp() || plugin.has(player, "simplexadminsuite.admin");
  }

  private boolean denied(String root) {
    return enabled && matches(blocked, root, command(root), this::command);
  }

  private boolean visible(Player player, String root) {
    if (denied(root)) return false;
    Command command = command(root);
    if (!hideUnpermitted || command == null) return true;
    // These historic suite commands check local admin grants at runtime rather than declaring a
    // Bukkit permission.
    if (command instanceof org.bukkit.command.PluginCommand own && own.getPlugin() == plugin) {
      String permission =
          switch (own.getName().toLowerCase(Locale.ROOT)) {
            case "setdiscord" -> "simplexadminsuite.settings";
            case "checkban", "checkmute", "xraybait", "trimworld" -> "simplexadminsuite.admin";
            case "vanish" -> "simplexadminsuite.vanish";
            default -> "";
          };
      if (!permission.isEmpty() && !plugin.has(player, permission)) return false;
    }
    return command.testPermissionSilent(player);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void execute(PlayerCommandPreprocessEvent event) {
    if (exempt(event.getPlayer())) return;
    if (denied(messageRoot(event.getMessage()))) {
      event.setCancelled(true);
      event
          .getPlayer()
          .sendMessage(
              plugin.playerText(
                  event.getPlayer(),
                  "§cDieser Befehl ist für Spieler gesperrt.",
                  "§cThis command is disabled for players."));
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void commands(PlayerCommandSendEvent event) {
    if (exempt(event.getPlayer())) return;
    event
        .getCommands()
        .removeIf(root -> !visible(event.getPlayer(), root.toLowerCase(Locale.ROOT)));
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void complete(TabCompleteEvent event) {
    if (!(event.getSender() instanceof Player player) || exempt(player)) return;
    String buffer = event.getBuffer();
    if (!buffer.startsWith("/")) return;
    // Only command roots; arguments (player names, items, etc.) are not commands.
    if (buffer.chars().anyMatch(Character::isWhitespace)) {
      if (denied(messageRoot(buffer))) event.getCompletions().clear();
      return;
    }
    event.getCompletions().removeIf(root -> !visible(player, messageRoot(root)));
  }

  void refresh() {
    if (!plugin.isEnabled()) return;
    Bukkit.getScheduler()
        .runTask(
            plugin,
            () -> {
              for (Player player : Bukkit.getOnlinePlayers()) player.updateCommands();
            });
  }

  private boolean allowed(Player player) {
    return plugin.adminMenuAllowed(player, "suite.commandmanagement")
        && plugin.has(player, "simplexadminsuite.commands.admin");
  }

  private void change(Player player, String path, Object value) {
    if (!allowed(player)) return;
    plugin.getConfig().set(path, value);
    plugin.saveConfig();
    reload();
    plugin.suiteAudit(player.getName(), "Befehlsverwaltung geändert", path + " = " + value);
    open(player);
  }

  void open(Player player) {
    if (!allowed(player)) return;
    List<AdminUpdateMenu.Button> rows = new ArrayList<>();
    rows.add(
        new AdminUpdateMenu.Button(
            "Befehlssperren: " + (enabled ? "An" : "Aus"),
            "Sperrliste anwenden • Admins sind ausgenommen",
            Material.LEVER,
            () -> change(player, "command-management.enabled", !enabled)));
    rows.add(
        new AdminUpdateMenu.Button(
            "Vorschläge bereinigen: " + (hideUnpermitted ? "An" : "Aus"),
            "Befehle ohne effektive Berechtigung ausblenden • berücksichtigt LuckPerms",
            Material.COMPARATOR,
            () -> change(player, "command-management.hide-unpermitted", !hideUnpermitted)));
    rows.add(
        new AdminUpdateMenu.Button(
            "Sperre hinzufügen",
            "Befehlsname ohne Argumente • Aliase und Namensräume werden mit erfasst",
            Material.BARRIER,
            () ->
                plugin.adminUpdate.prompt(
                    player,
                    "Befehl sperren (z. B. /setdiscord)",
                    "",
                    value -> {
                      if (!allowed(player)) return;
                      String root = configuredRoot(value);
                      if (root.isEmpty()) {
                        plugin.menuError(
                            player,
                            "§cBitte genau einen gültigen Befehlsnamen eingeben, ohne Argumente.");
                        open(player);
                        return;
                      }
                      Set<String> updated = new TreeSet<>(blocked);
                      updated.add(root);
                      change(player, "command-management.blocked", new ArrayList<>(updated));
                    })));
    for (String root : new TreeSet<>(blocked))
      rows.add(
          new AdminUpdateMenu.Button(
              "/" + root,
              "Sperre bearbeiten oder entfernen",
              Material.PAPER,
              () -> confirmRemove(player, root)));
    rows.add(
        new AdminUpdateMenu.Button(
            "Befehle aktualisieren",
            "Aktuelle Rechte und Sperren an alle Clients senden",
            Material.CLOCK,
            () -> {
              if (allowed(player)) {
                refresh();
                plugin.menuNotice(player, "§aBefehlsvorschläge wurden aktualisiert.");
                open(player);
              }
            }));
    rows.add(
        new AdminUpdateMenu.Button(
            "Hinweise",
            "Es gelten deklarierte Befehlsrechte. Interne Unterbefehlsrechte ohne Deklaration kann"
                + " Bukkit nicht erkennen. LuckPerms-Daten bleiben unverändert.",
            Material.BOOK,
            () -> open(player)));
    rows.add(
        new AdminUpdateMenu.Button(
            "Zurück",
            "Server-Verwaltung",
            Material.ARROW,
            () -> plugin.unifiedMenu.category(player, "Server")));
    plugin.adminUpdate.show(player, "Befehlsverwaltung", rows, 0);
  }

  private void confirmRemove(Player player, String root) {
    if (!allowed(player)) return;
    plugin.adminUpdate.show(
        player,
        "Sperre entfernen?",
        List.of(
            new AdminUpdateMenu.Button(
                "Entfernen",
                "/" + root + " wieder für berechtigte Spieler freigeben",
                Material.REDSTONE_BLOCK,
                () -> {
                  if (!allowed(player)) return;
                  Set<String> updated = new TreeSet<>(blocked);
                  updated.remove(root);
                  change(player, "command-management.blocked", new ArrayList<>(updated));
                }),
            new AdminUpdateMenu.Button(
                "Abbrechen", "Sperre beibehalten", Material.ARROW, () -> open(player))),
        0);
  }
}
