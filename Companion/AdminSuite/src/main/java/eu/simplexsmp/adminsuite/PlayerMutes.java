package eu.simplexsmp.adminsuite;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

/**
 * 10.23.0: personal player mutes. Player A can mute player B only for themselves – C still sees B.
 * Optionally the mute covers B's linked accounts (past and future, from the confirmed alt graph);
 * which accounts that are is never shown to players, only to staff in the app and web panel.
 * Accounts with admin rights can never be muted this way. Effects for A: no chat, whispers or
 * contact commands (tpa, mail …) from B; re-broadcast system chat of B's message is dropped too.
 */
final class PlayerMutes implements Listener {
  static final String FEATURE = "player-mutes-v1";
  static final int MAX_ENTRIES = 150;
  private static final long REFRESH_MS = 60_000L;

  /** One muted account (as chosen by the owner). Linked accounts are resolved at runtime. */
  record Entry(UUID target, String name, boolean alts, boolean contact, long since, String by) {}

  private final SimplexAdminSuite plugin;
  private final ChatSilence chat;
  private final Path file;
  private final Map<UUID, Map<UUID, Entry>> owners = new ConcurrentHashMap<>();
  /** owner → every account whose chat/contact is hidden from the owner (target plus hidden alts). */
  private volatile Map<UUID, Set<UUID>> effective = Map.of();
  /** owner → subset of {@link #effective} that may not contact the owner (whispers, tpa …). */
  private volatile Map<UUID, Set<UUID>> contactBlocked = Map.of();
  /** target → hidden linked accounts used for the expansion (staff only). */
  private volatile Map<UUID, Set<UUID>> linked = Map.of();
  private long lastRefresh;

  PlayerMutes(SimplexAdminSuite plugin, ChatSilence chat) throws IOException {
    this.plugin = plugin;
    this.chat = chat;
    this.file = plugin.getDataFolder().toPath().resolve("player-mutes.yml");
    load();
  }

  void start() {
    Bukkit.getScheduler().runTaskTimer(plugin, () -> { if (System.currentTimeMillis() - lastRefresh >= REFRESH_MS) refresh(); }, 40L, 200L);
  }

  // ------------------------------------------------------------------ rules

  /** Admins (op, simplexadminsuite.admin, staff workspace) can never be muted by players. */
  boolean protectedAccount(UUID id) {
    if (id == null) return true;
    try { if (plugin.isStaffAccount(id)) return true; } catch (RuntimeException ignored) { }
    Player online = Bukkit.getPlayer(id);
    return online != null && plugin.has(online, "simplexadminsuite.admin");
  }

  boolean anyActive() { return !owners.isEmpty(); }

  /** True when {@code viewer} must not see chat from {@code sender}. Safe from any thread. */
  boolean hides(UUID viewer, UUID sender) {
    if (viewer == null || sender == null || viewer.equals(sender)) return false;
    Set<UUID> set = effective.get(viewer);
    return set != null && set.contains(sender) && !protectedOnline(sender);
  }

  /** True when {@code sender} may not contact {@code target} (whispers, tpa …). */
  boolean blocksContact(UUID target, UUID sender) {
    if (target == null || sender == null || target.equals(sender)) return false;
    Set<UUID> set = contactBlocked.get(target);
    return set != null && set.contains(sender) && !protectedOnline(sender);
  }

  private boolean protectedOnline(UUID sender) {
    Player p = Bukkit.getPlayer(sender);
    return p != null && (p.isOp() || plugin.has(p, "simplexadminsuite.admin"));
  }

  List<Entry> entries(UUID owner) {
    Map<UUID, Entry> map = owners.get(owner);
    if (map == null) return List.of();
    List<Entry> list = new ArrayList<>(map.values());
    list.sort(Comparator.comparing((Entry e) -> e.name.toLowerCase(Locale.ROOT)));
    return list;
  }

  /** Adds or updates a personal mute. Returns a user-facing error, or null on success. */
  String add(UUID owner, UUID target, String targetName, boolean alts, boolean contact, String by) {
    if (owner == null || target == null) return "Spieler nicht gefunden.";
    if (owner.equals(target)) return "Du kannst dich nicht selbst stummschalten.";
    if (protectedAccount(target)) return "Teammitglieder mit Admin-Rechten können nicht stummgeschaltet werden.";
    Map<UUID, Entry> map = owners.computeIfAbsent(owner, id -> new ConcurrentHashMap<>());
    if (!map.containsKey(target) && map.size() >= MAX_ENTRIES) return "Du hast bereits " + MAX_ENTRIES + " Spieler stummgeschaltet.";
    map.put(target, new Entry(target, targetName == null ? "" : targetName, alts, contact, System.currentTimeMillis(), by == null ? "self" : by));
    save();
    refresh();
    return null;
  }

  boolean remove(UUID owner, UUID target) {
    Map<UUID, Entry> map = owners.get(owner);
    if (map == null || map.remove(target) == null) return false;
    if (map.isEmpty()) owners.remove(owner);
    save();
    refresh();
    return true;
  }

  /** Recomputes the effective sets, including linked accounts created since the last refresh. Main thread. */
  void refresh() {
    lastRefresh = System.currentTimeMillis();
    Map<UUID, Set<UUID>> altCache = new HashMap<>();
    Map<UUID, Set<UUID>> nextEffective = new HashMap<>(), nextContact = new HashMap<>();
    for (var ownerEntry : owners.entrySet()) {
      UUID owner = ownerEntry.getKey();
      Set<UUID> ownAccounts = new HashSet<>(alts(owner, altCache));
      ownAccounts.add(owner);
      Set<UUID> hidden = new HashSet<>(), contact = new HashSet<>();
      for (Entry e : ownerEntry.getValue().values()) {
        Set<UUID> group = new LinkedHashSet<>();
        group.add(e.target);
        if (e.alts) group.addAll(alts(e.target, altCache));
        // Never mute the owner's own accounts and never admin accounts, even if they are linked.
        group.removeIf(id -> ownAccounts.contains(id) || protectedAccount(id));
        hidden.addAll(group);
        if (e.contact) contact.addAll(group);
      }
      if (!hidden.isEmpty()) nextEffective.put(owner, Set.copyOf(hidden));
      if (!contact.isEmpty()) nextContact.put(owner, Set.copyOf(contact));
    }
    Map<UUID, Set<UUID>> nextLinked = new HashMap<>();
    for (var entry : altCache.entrySet()) nextLinked.put(entry.getKey(), Set.copyOf(entry.getValue()));
    effective = Map.copyOf(nextEffective);
    contactBlocked = Map.copyOf(nextContact);
    linked = Map.copyOf(nextLinked);
  }

  private Set<UUID> alts(UUID id, Map<UUID, Set<UUID>> cache) {
    return cache.computeIfAbsent(id, key -> {
      try { return new LinkedHashSet<>(plugin.confirmedAlts(key)); } catch (RuntimeException e) { return new LinkedHashSet<>(); }
    });
  }

  // ------------------------------------------------------------------ events

  /** A newly linked account must be covered at once – refresh shortly after every join. */
  @EventHandler(priority = EventPriority.MONITOR)
  public void join(PlayerJoinEvent e) {
    if (owners.isEmpty()) return;
    Bukkit.getScheduler().runTaskLater(plugin, this::refresh, 60L);
  }

  /** Contact commands (tpa, mail, trade …) towards a player who muted the sender are refused. */
  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  public void contactCommand(PlayerCommandPreprocessEvent e) {
    if (contactBlocked.isEmpty()) return;
    String[] parts = e.getMessage().strip().split("\\s+");
    if (parts.length < 2) return;
    String label = parts[0].replaceFirst("^/", "").toLowerCase(Locale.ROOT);
    int colon = label.indexOf(':');
    if (colon >= 0) label = label.substring(colon + 1);
    if (!contactCommands().contains(label)) return;
    Player sender = e.getPlayer();
    for (int i = 1; i < Math.min(parts.length, 4); i++) {
      Player target = Bukkit.getPlayerExact(parts[i]);
      if (target != null && blocksContact(target.getUniqueId(), sender.getUniqueId())) {
        e.setCancelled(true);
        chat.deliver(sender, chat.prefixComponent().append(Component.text(t(sender, "Diese Person möchte gerade keine Anfragen oder Nachrichten von dir erhalten.", "This player does not want requests or messages from you right now."), NamedTextColor.RED)));
        return;
      }
    }
  }

  private Set<String> contactCommands() {
    List<String> configured = plugin.getConfig().getStringList("player-mutes.contact-commands");
    if (configured.isEmpty()) configured = List.of("tpa", "tpahere", "tpask", "etpa", "etpahere", "tpr", "call", "mail", "email", "emails", "trade", "duel", "party", "invite", "friend", "friends", "f", "pay");
    Set<String> out = new HashSet<>();
    for (String s : configured) out.add(s.toLowerCase(Locale.ROOT).replaceFirst("^/", ""));
    return out;
  }

  // ------------------------------------------------------------------ player UI

  private boolean de(Player p) { return plugin.community != null ? plugin.community.german(p) : p != null && ClientProbeAccess.german(p.getLocale()); }
  private String t(Player p, String de, String en) { return de(p) ? de : en; }

  void command(Player p, String[] args) {
    if (args.length == 0 || Set.of("liste", "list", "menü", "menu").contains(args[0].toLowerCase(Locale.ROOT))) { open(p, null); return; }
    String first = args[0].toLowerCase(Locale.ROOT);
    if (Set.of("entfernen", "aufheben", "remove", "unmute", "weg").contains(first)) {
      if (args.length < 2) { usage(p); return; }
      UUID target = resolve(args[1]);
      if (target != null && remove(p.getUniqueId(), target)) message(p, t(p, args[1] + " ist für dich nicht mehr stummgeschaltet.", args[1] + " is no longer muted for you."), NamedTextColor.GREEN, true);
      else message(p, t(p, args[1] + " ist bei dir nicht stummgeschaltet.", args[1] + " is not muted for you."), NamedTextColor.GRAY, false);
      return;
    }
    if (Set.of("hilfe", "help", "?").contains(first)) { usage(p); return; }
    String name = args[0];
    boolean alts = args.length > 1 && Set.of("alle", "all", "konten", "accounts", "alts", "komplett").contains(args[1].toLowerCase(Locale.ROOT));
    boolean explicit = args.length > 1;
    UUID target = resolve(name);
    if (target == null) { message(p, t(p, "Spieler „" + name + "“ wurde nicht gefunden.", "Player \"" + name + "\" was not found."), NamedTextColor.RED, false); return; }
    String targetName = plugin.discordAdminPlayerName(target);
    if (!explicit && !plugin.isBedrockClient(p)) { askScope(p, target, targetName); return; }
    apply(p, target, targetName, alts, true);
  }

  private void apply(Player p, UUID target, String targetName, boolean alts, boolean contact) {
    String error = add(p.getUniqueId(), target, targetName, alts, contact, "self");
    if (error != null) { message(p, de(p) ? error : englishError(error), NamedTextColor.RED, false); return; }
    message(p, alts
        ? t(p, targetName + " ist jetzt für dich stummgeschaltet – auch mit weiteren Konten, jetzt und künftig.", targetName + " is now muted for you – including other accounts, now and in the future.")
        : t(p, targetName + " ist jetzt für dich stummgeschaltet. Andere sehen die Nachrichten weiterhin.", targetName + " is now muted for you. Others still see the messages."), NamedTextColor.GREEN, true);
  }

  private static String englishError(String error) {
    if (error.startsWith("Teammitglieder")) return "Staff members with admin rights cannot be muted.";
    if (error.startsWith("Du kannst dich")) return "You cannot mute yourself.";
    if (error.startsWith("Du hast bereits")) return "You have reached the limit of " + MAX_ENTRIES + " muted players.";
    return "Player not found.";
  }

  private UUID resolve(String query) {
    Player online = Bukkit.getPlayerExact(query);
    if (online != null) return online.getUniqueId();
    try { return UUID.fromString(query); } catch (IllegalArgumentException notUuid) { return plugin.discordAdminResolvePlayer(query); }
  }

  private void askScope(Player p, UUID target, String targetName) {
    Component line = chat.prefixComponent().append(Component.text(t(p, targetName + " stummschalten: ", "Mute " + targetName + ": "), NamedTextColor.WHITE))
        .append(Component.text(t(p, "[Nur dieses Konto]", "[This account]"), NamedTextColor.AQUA, TextDecoration.BOLD)
            .clickEvent(ClickEvent.runCommand("/ignorieren " + targetName + " konto"))
            .hoverEvent(HoverEvent.showText(Component.text(t(p, "Nur " + targetName + " stummschalten", "Mute only " + targetName), NamedTextColor.GRAY))))
        .append(Component.text(" "))
        .append(Component.text(t(p, "[Mit allen Konten]", "[All accounts]"), NamedTextColor.GOLD, TextDecoration.BOLD)
            .clickEvent(ClickEvent.runCommand("/ignorieren " + targetName + " alle"))
            .hoverEvent(HoverEvent.showText(Component.text(t(p, "Auch weitere Konten dieser Person – bestehende und künftige. Welche das sind, bleibt aus Datenschutzgründen verborgen.", "Also the person's other accounts – existing and future. Which ones stays private."), NamedTextColor.GRAY))));
    chat.deliver(p, line);
  }

  private void usage(Player p) {
    String[] lines = de(p)
        ? new String[] {"§b§lSpieler stummschalten §7– nur für dich", "§e/ignorieren §7– Liste und Menü", "§e/ignorieren <Spieler> §7– stummschalten (mit Auswahl)", "§e/ignorieren <Spieler> alle §7– inkl. weiterer Konten", "§e/ignorieren entfernen <Spieler> §7– wieder anzeigen", "§7Admins können nicht stummgeschaltet werden."}
        : new String[] {"§b§lMute players §7– only for you", "§e/ignoreplayer §7– list and menu", "§e/ignoreplayer <player> §7– mute (with choice)", "§e/ignoreplayer <player> all §7– including other accounts", "§e/ignoreplayer remove <player> §7– show again", "§7Admins cannot be muted."};
    for (String line : lines) chat.deliver(p, net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(line));
  }

  private void message(Player p, String text, NamedTextColor color, boolean positive) {
    chat.deliver(p, chat.prefixComponent().append(Component.text(text, color)));
    chat.feedback(p, positive);
  }

  List<String> complete(Player p, String[] args) {
    if (args.length == 1) {
      List<String> out = new ArrayList<>(List.of(de(p) ? "entfernen" : "remove", de(p) ? "liste" : "list"));
      for (Player online : Bukkit.getOnlinePlayers()) if (!online.equals(p) && !protectedAccount(online.getUniqueId())) out.add(online.getName());
      return out.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
    }
    if (args.length == 2) {
      if (Set.of("entfernen", "remove", "aufheben", "unmute").contains(args[0].toLowerCase(Locale.ROOT)))
        return entries(p.getUniqueId()).stream().map(Entry::name).filter(s -> s.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
      return List.of(de(p) ? "konto" : "account", de(p) ? "alle" : "all");
    }
    return List.of();
  }

  /** Overview menu: Java chest menu with heads, Bedrock native form. */
  void open(Player p, Runnable back) {
    if (plugin.isBedrockClient(p) && bedrock(p, back)) return;
    inventory(p, back);
  }

  private boolean bedrock(Player p, Runnable back) {
    UUID owner = p.getUniqueId();
    List<Entry> list = entries(owner);
    var form = SimplexAdminSuite.SimpleForm.builder().title(t(p, "Stummgeschaltete Spieler", "Muted players"))
        .content(t(p, "Nur du siehst diese Spieler nicht mehr – für alle anderen ändert sich nichts.\nAdmins können nicht stummgeschaltet werden.", "Only you stop seeing these players – nothing changes for anyone else.\nAdmins cannot be muted.")
            + "\n\n" + (list.isEmpty() ? t(p, "Noch niemand stummgeschaltet.", "Nobody muted yet.") : list.size() + t(p, " Spieler stummgeschaltet.", " players muted.")));
    form.button(t(p, "Spieler stummschalten", "Mute a player") + "\n" + t(p, "Name eingeben", "Enter a name"), "textures/ui/mute_on", r -> run(owner, () -> bedrockAdd(p, back)));
    for (Entry e : list)
      form.button(e.name + "\n" + (e.alts ? t(p, "inkl. weiterer Konten · tippen zum Aufheben", "incl. other accounts · tap to remove") : t(p, "Tippen zum Aufheben", "Tap to remove")), "textures/ui/mute_off",
          r -> run(owner, () -> { remove(owner, e.target); message(p, t(p, e.name + " ist wieder sichtbar.", e.name + " is visible again."), NamedTextColor.GREEN, true); bedrock(p, back); }));
    if (back != null) form.button(t(p, "Zurück", "Back"), "textures/ui/arrow_left", r -> run(owner, back));
    return plugin.sendBedrockForm(p, form);
  }

  private void bedrockAdd(Player p, Runnable back) {
    UUID owner = p.getUniqueId();
    var form = SimplexAdminSuite.CustomForm.builder().title(t(p, "Spieler stummschalten", "Mute a player"))
        .input(t(p, "Spielername", "Player name"), "Steve")
        .toggle(t(p, "Auch weitere Konten dieser Person (bleiben verborgen)", "Also the person's other accounts (kept private)"), false)
        .toggle(t(p, "Auch Flüstern und Anfragen (tpa, mail …) blockieren", "Also block whispers and requests (tpa, mail …)"), true)
        .validResultHandler(r -> run(owner, () -> {
          String name = r.asInput(0).strip();
          UUID target = name.isEmpty() ? null : resolve(name);
          if (target == null) message(p, t(p, "Spieler nicht gefunden.", "Player not found."), NamedTextColor.RED, false);
          else apply(p, target, plugin.discordAdminPlayerName(target), r.asToggle(1), r.asToggle(2));
          bedrock(p, back);
        }));
    if (!plugin.sendBedrockForm(p, form)) bedrock(p, back);
  }

  /** Java: native dialog to add a player; falls back to the chat command hint. */
  void addDialog(Player p, Runnable back) {
    UUID owner = p.getUniqueId();
    try {
      var name = DialogInput.text("name", Component.text(t(p, "Spielername", "Player name"))).maxLength(36).width(300).build();
      var alts = DialogInput.bool("alts", Component.text(t(p, "Auch weitere Konten dieser Person (bleiben verborgen)", "Also the person's other accounts (kept private)"))).initial(false).build();
      var contact = DialogInput.bool("contact", Component.text(t(p, "Auch Flüstern und Anfragen (tpa, mail …) blockieren", "Also block whispers and requests (tpa, mail …)"))).initial(true).build();
      DialogBase base = DialogBase.builder(Component.text(t(p, "Spieler stummschalten", "Mute a player"), NamedTextColor.AQUA, TextDecoration.BOLD))
          .canCloseWithEscape(true).pause(false)
          .body(List.of(DialogBody.plainMessage(Component.text(t(p, "Nur du siehst diese Person nicht mehr. Für alle anderen ändert sich nichts. Admins können nicht stummgeschaltet werden.", "Only you stop seeing this person. Nothing changes for anyone else. Admins cannot be muted."), NamedTextColor.GRAY), 300)))
          .inputs(List.of(name, alts, contact)).build();
      var save = DialogAction.customClick((view, audience) -> {
        if (!(audience instanceof Player sender) || !sender.getUniqueId().equals(owner)) return;
        String value = view.getText("name");
        boolean a = Boolean.TRUE.equals(view.getBoolean("alts")), c = Boolean.TRUE.equals(view.getBoolean("contact"));
        Bukkit.getScheduler().runTask(plugin, () -> {
          if (!sender.isOnline()) return;
          UUID target = value == null || value.isBlank() ? null : resolve(value.strip());
          if (target == null) message(sender, t(sender, "Spieler nicht gefunden.", "Player not found."), NamedTextColor.RED, false);
          else apply(sender, target, plugin.discordAdminPlayerName(target), a, c);
          inventory(sender, back);
        });
      }, ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(10)).build());
      Dialog dialog = Dialog.create(b -> b.empty().base(base).type(DialogType.confirmation(
          ActionButton.builder(Component.text(t(p, "✔ Stummschalten", "✔ Mute"), NamedTextColor.GREEN)).width(150).action(save).build(),
          ActionButton.builder(Component.text(t(p, "Abbrechen", "Cancel"))).width(150).build())));
      p.closeInventory();
      p.showDialog(dialog);
    } catch (LinkageError | RuntimeException unsupported) {
      p.closeInventory();
      message(p, t(p, "Nutze /ignorieren <Spieler> [alle]", "Use /ignoreplayer <player> [all]"), NamedTextColor.YELLOW, true);
    }
  }

  static final class Holder implements InventoryHolder {
    final UUID owner;
    final Map<Integer, Runnable> actions = new HashMap<>();
    Inventory inventory;
    Holder(UUID owner) { this.owner = owner; }
    public Inventory getInventory() { return inventory; }
  }

  private void inventory(Player p, Runnable back) {
    UUID owner = p.getUniqueId();
    Holder h = new Holder(owner);
    Inventory inv = LiveMenus.create(h, 54, t(p, "Stummgeschaltete Spieler", "Muted players"));
    h.inventory = inv;
    ItemStack frame = item(Material.CYAN_STAINED_GLASS_PANE, " ", List.of());
    for (int i = 0; i < 54; i++) if (i < 9 || i >= 45) inv.setItem(i, frame);
    List<Entry> list = entries(owner);
    inv.setItem(4, item(Material.BELL, t(p, "§b§lSpieler stummschalten", "§b§lMute players"), List.of(
        t(p, "§7Nur du siehst diese Spieler nicht mehr.", "§7Only you stop seeing these players."),
        t(p, "§7Für alle anderen ändert sich nichts.", "§7Nothing changes for anyone else."),
        t(p, "§7Admins können nicht stummgeschaltet werden.", "§7Admins cannot be muted."), "",
        "§f" + list.size() + t(p, " stummgeschaltet", " muted"))));
    int slot = 9;
    for (Entry e : list) {
      if (slot >= 45) break;
      ItemStack head = item(Material.PLAYER_HEAD, "§f§l" + e.name, List.of(
          e.alts ? t(p, "§6● inkl. weiterer Konten", "§6● incl. other accounts") : t(p, "§7● nur dieses Konto", "§7● this account only"),
          e.contact ? t(p, "§7● Flüstern und Anfragen blockiert", "§7● whispers and requests blocked") : t(p, "§7● nur Chat ausgeblendet", "§7● chat hidden only"), "",
          t(p, "§eLinksklick: Konten umschalten", "§eLeft click: toggle accounts"), t(p, "§cRechtsklick: aufheben", "§cRight click: remove")));
      try { if (head.getItemMeta() instanceof SkullMeta skull) { skull.setOwningPlayer(Bukkit.getOfflinePlayer(e.target)); head.setItemMeta(skull); } } catch (RuntimeException ignored) { }
      int s = slot++;
      inv.setItem(s, head);
      h.actions.put(s, () -> { add(owner, e.target, e.name, !e.alts, e.contact, "self"); chat.feedback(p, true); inventory(p, back); });
      h.actions.put(-s - 1, () -> { remove(owner, e.target); message(p, t(p, e.name + " ist wieder sichtbar.", e.name + " is visible again."), NamedTextColor.GREEN, true); inventory(p, back); });
    }
    inv.setItem(49, item(Material.LIME_DYE, t(p, "§a§lSpieler hinzufügen", "§a§lAdd a player"), List.of(t(p, "§7Name eingeben", "§7Enter a name"))));
    h.actions.put(49, () -> addDialog(p, back));
    if (back != null) { inv.setItem(45, item(Material.ARROW, t(p, "§fZurück", "§fBack"), List.of())); h.actions.put(45, back); }
    inv.setItem(53, item(Material.BARRIER, t(p, "§cSchließen", "§cClose"), List.of()));
    h.actions.put(53, p::closeInventory);
    LiveMenus.show(p, inv);
  }

  private static ItemStack item(Material material, String name, List<String> lore) {
    ItemStack stack = new ItemStack(material);
    ItemMeta meta = stack.getItemMeta();
    if (meta != null) { meta.setDisplayName(name); meta.setLore(lore); meta.addItemFlags(ItemFlag.values()); stack.setItemMeta(meta); }
    return stack;
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void click(InventoryClickEvent e) {
    if (!(LiveMenus.holder(e.getView().getTopInventory()) instanceof Holder h)) return;
    e.setCancelled(true);
    if (!(e.getWhoClicked() instanceof Player p) || !h.owner.equals(p.getUniqueId()) || e.getRawSlot() < 0 || e.getRawSlot() >= 54) return;
    Runnable action = e.isRightClick() && h.actions.containsKey(-e.getRawSlot() - 1) ? h.actions.get(-e.getRawSlot() - 1) : h.actions.get(e.getRawSlot());
    if (action != null) Bukkit.getScheduler().runTask(plugin, () -> { if (p.getOpenInventory().getTopInventory() == h.inventory) action.run(); });
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void drag(InventoryDragEvent e) { if (LiveMenus.holder(e.getView().getTopInventory()) instanceof Holder) e.setCancelled(true); }

  private void run(UUID owner, Runnable action) {
    Bukkit.getScheduler().runTask(plugin, () -> { Player p = Bukkit.getPlayer(owner); if (p != null && p.isOnline()) action.run(); });
  }

  // ------------------------------------------------------------------ app & web

  /** Staff view. Linked account names are only included when {@code showLinked} (alt permission). */
  List<Map<String, Object>> data(boolean showLinked) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (var ownerEntry : owners.entrySet()) {
      UUID owner = ownerEntry.getKey();
      Player ownerOnline = Bukkit.getPlayer(owner);
      for (Entry e : ownerEntry.getValue().values()) {
        Map<String, Object> row = SimplexAdminSuite.appMap("owner", owner.toString(), "ownerName", ownerOnline != null ? ownerOnline.getName() : plugin.discordAdminPlayerName(owner),
            "target", e.target.toString(), "targetName", e.name, "alts", e.alts, "contact", e.contact, "since", e.since, "by", e.by,
            "protected", protectedAccount(e.target), "targetOnline", Bukkit.getPlayer(e.target) != null);
        Set<UUID> group = e.alts ? linked.getOrDefault(e.target, Set.of()) : Set.of();
        row.put("linkedCount", group.size());
        if (showLinked) row.put("linked", group.stream().map(id -> SimplexAdminSuite.appMap("id", id.toString(), "name", plugin.discordAdminPlayerName(id))).toList());
        out.add(row);
      }
    }
    out.sort(Comparator.comparing((Map<String, Object> m) -> String.valueOf(m.get("ownerName")).toLowerCase(Locale.ROOT)).thenComparing(m -> String.valueOf(m.get("targetName")).toLowerCase(Locale.ROOT)));
    return out;
  }

  int ownerCount() { return owners.size(); }

  // ------------------------------------------------------------------ storage

  private void load() throws IOException {
    if (!Files.isRegularFile(file)) return;
    YamlConfiguration y = new YamlConfiguration();
    try { y.load(file.toFile()); }
    catch (org.bukkit.configuration.InvalidConfigurationException e) { throw new IOException("player-mutes.yml unlesbar; Datei bleibt unverändert", e); }
    var section = y.getConfigurationSection("owners");
    if (section == null) return;
    for (String ownerKey : section.getKeys(false)) {
      UUID owner;
      try { owner = UUID.fromString(ownerKey); } catch (IllegalArgumentException invalid) { continue; }
      var targets = section.getConfigurationSection(ownerKey);
      if (targets == null) continue;
      Map<UUID, Entry> map = new ConcurrentHashMap<>();
      for (String targetKey : targets.getKeys(false)) {
        UUID target;
        try { target = UUID.fromString(targetKey); } catch (IllegalArgumentException invalid) { continue; }
        String root = targetKey + ".";
        map.put(target, new Entry(target, targets.getString(root + "name", ""), targets.getBoolean(root + "alts", false), targets.getBoolean(root + "contact", true),
            targets.getLong(root + "since", 0), targets.getString(root + "by", "self")));
      }
      if (!map.isEmpty()) owners.put(owner, map);
    }
  }

  private synchronized void save() {
    YamlConfiguration y = new YamlConfiguration();
    y.set("schema-version", 1);
    for (var ownerEntry : owners.entrySet())
      for (Entry e : ownerEntry.getValue().values()) {
        String root = "owners." + ownerEntry.getKey() + "." + e.target + ".";
        y.set(root + "name", e.name);
        y.set(root + "alts", e.alts);
        y.set(root + "contact", e.contact);
        y.set(root + "since", e.since);
        y.set(root + "by", e.by);
      }
    try {
      Files.createDirectories(file.getParent());
      Path temp = file.resolveSibling(file.getFileName() + ".tmp");
      Files.writeString(temp, y.saveToString());
      try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
      catch (AtomicMoveNotSupportedException e) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
    } catch (IOException e) {
      plugin.getLogger().warning("Spieler-Stummschaltungen konnten nicht gespeichert werden: " + e.getMessage());
    }
  }
}
