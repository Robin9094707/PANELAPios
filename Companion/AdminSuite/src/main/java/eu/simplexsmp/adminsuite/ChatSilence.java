package eu.simplexsmp.adminsuite;

import com.sun.net.httpserver.HttpExchange;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

/**
 * 10.17.0: players can silence the public chat for themselves – temporarily or until they turn it
 * back on. Chat from players is removed from their viewers; system chat from other plugins is
 * filtered per player on the Netty channel (action bars, titles and the player's own command
 * feedback stay). Full-name mentions and (optionally) whispers still arrive. Staff manage it in the
 * app and the web panel.
 */
final class ChatSilence implements Listener {
  static final String FEATURE = "chat-silence-v1", VIEW = "data.chat-silence", MANAGE = "chat-silence.manage";
  static final long[] PRESETS = {30, 180, 1440, 0};
  private static final long FEEDBACK_WINDOW_MS = 1800, CLICK_WINDOW_MS = 1200, TOKEN_MS = 4000, SOUND_MS = 450;
  private static final long HINT_MS = 10 * 60_000L, SENDER_INFO_MS = 45_000L;
  private static final java.util.regex.Pattern NOTIFY_SOUND =
      java.util.regex.Pattern.compile("note_block|experience_orb|levelup|ui\\.|bell|chime|amethyst|pling|toast|item\\.pickup|chicken\\.egg|villager\\.(yes|no|trade|ambient)|arrow\\.hit_player|bubble|anvil\\.land");

  /** Persisted per player. Whisper and mention flags only apply while the chat is silenced. */
  record State(boolean chat, long until, boolean whispers, boolean mentions, String name, String by, long since) {
    static State defaults(String name) { return new State(false, 0, false, false, name == null ? "" : name, "", 0); }
    boolean active(long now) { return chat && (until <= 0 || until > now); }
    State muted(long until, String by, long now, String name) { return new State(true, until, whispers, mentions, name, by, now); }
    State unmuted(String by, long now, String name) { return new State(false, 0, whispers, mentions, name, by, now); }
    State options(boolean whispers, boolean mentions) { return new State(chat, until, whispers, mentions, name, by, since); }
    boolean worthKeeping() { return chat || whispers || mentions; }
  }

  /** Read by the Netty thread; written on the main thread. */
  static final class Gate {
    volatile boolean active;
    volatile long openUntil, lastDrop, soundUntil;
    final ConcurrentLinkedQueue<Token> tokens = new ConcurrentLinkedQueue<>();
  }

  record Token(String text, long expires) {}

  private final SimplexAdminSuite plugin;
  private final Path file;
  private final Map<UUID, State> states = new ConcurrentHashMap<>();
  private final Map<UUID, Gate> gates = new ConcurrentHashMap<>();
  private final Map<UUID, Long> hints = new ConcurrentHashMap<>();
  private final Map<String, Long> senderInfo = new ConcurrentHashMap<>();
  private BukkitTask task;
  private volatile boolean stopped;

  ChatSilence(SimplexAdminSuite plugin) throws IOException {
    this.plugin = plugin;
    this.file = plugin.getDataFolder().toPath().resolve("chat-silence.yml");
    load();
  }

  void start() {
    task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    for (Player p : Bukkit.getOnlinePlayers()) refreshGate(p);
  }

  void stop() {
    stopped = true;
    if (task != null) task.cancel();
    gates.clear();
  }

  // ------------------------------------------------------------------ state

  State state(UUID id) {
    State s = states.get(id);
    if (s != null) return s;
    Player online = Bukkit.getPlayer(id);
    return State.defaults(online == null ? plugin.discordAdminPlayerName(id) : online.getName());
  }

  boolean silenced(UUID id) {
    State s = states.get(id);
    return s != null && s.active(System.currentTimeMillis());
  }

  boolean silenced(Player p) { return p != null && silenced(p.getUniqueId()); }

  private boolean de(Player p) { return plugin.community != null ? plugin.community.german(p) : p != null && ClientProbeAccess.german(p.getLocale()); }

  private String t(Player p, String de, String en) { return de(p) ? de : en; }

  /** Applies a change, saves, refreshes the packet gate and returns the new state. */
  State mute(UUID id, String name, long minutes, String by) {
    long now = System.currentTimeMillis();
    State next = state(id).muted(minutes <= 0 ? 0 : now + minutes * 60_000L, by, now, name);
    put(id, next);
    return next;
  }

  State unmute(UUID id, String name, String by) {
    State next = state(id).unmuted(by, System.currentTimeMillis(), name);
    put(id, next);
    return next;
  }

  State options(UUID id, boolean whispers, boolean mentions) {
    State next = state(id).options(whispers, mentions);
    put(id, next);
    return next;
  }

  private void put(UUID id, State next) {
    if (next.worthKeeping()) states.put(id, next);
    else states.remove(id);
    save();
    Player online = Bukkit.getPlayer(id);
    if (online != null) refreshGate(online);
  }

  private void tick() {
    if (stopped) return;
    long now = System.currentTimeMillis();
    for (var entry : new ArrayList<>(states.entrySet())) {
      State s = entry.getValue();
      if (s.chat && s.until > 0 && s.until <= now) {
        put(entry.getKey(), s.unmuted("expired", now, s.name));
        Player p = Bukkit.getPlayer(entry.getKey());
        if (p != null) {
          deliver(p, prefix().append(Component.text(t(p, "Deine Chat-Ruhe ist abgelaufen – du siehst den Chat wieder.", "Your chat break is over – you can see the chat again."), NamedTextColor.GREEN)));
          feedback(p, true);
        }
      }
    }
    for (Player p : Bukkit.getOnlinePlayers()) refreshGate(p);
    hints.values().removeIf(at -> now - at > HINT_MS * 3);
    senderInfo.values().removeIf(at -> now - at > SENDER_INFO_MS * 4);
  }

  void refreshGate(Player p) {
    Gate gate = gates.computeIfAbsent(p.getUniqueId(), id -> new Gate());
    gate.active = silenced(p) && !plugin.chatSilenceBypass(p);
    long now = System.currentTimeMillis();
    gate.tokens.removeIf(token -> token.expires < now);
  }

  private Gate gate(Player p) { return gates.computeIfAbsent(p.getUniqueId(), id -> new Gate()); }

  // ------------------------------------------------------------------ Netty side

  /** Called on the Netty event loop for every outgoing packet of {@code id}. Returns false to drop. */
  boolean allowOutgoing(UUID id, Object packet) {
    Gate gate = gates.get(id);
    if (gate == null || !gate.active || packet == null) return true;
    String type = packet.getClass().getSimpleName();
    long now = System.currentTimeMillis();
    if (type.equals("ClientboundSoundPacket")) {
      if (now < gate.soundUntil || now - gate.lastDrop > SOUND_MS) return true;
      return !NOTIFY_SOUND.matcher(soundName(packet)).find();
    }
    if (!type.equals("ClientboundSystemChatPacket") && !type.equals("ClientboundDisguisedChatPacket")) return true;
    if (type.equals("ClientboundSystemChatPacket")) {
      try { if (Boolean.TRUE.equals(SuitePacketGuard.call(packet, "overlay", "isOverlay"))) return true; }
      catch (ReflectiveOperationException | RuntimeException ignored) { }
    }
    if (now < gate.openUntil) return true;
    String text = normalize(packetText(packet));
    for (Iterator<Token> it = gate.tokens.iterator(); it.hasNext(); ) {
      Token token = it.next();
      if (token.expires < now) { it.remove(); continue; }
      if (!token.text.isEmpty() && text.contains(token.text)) { it.remove(); return true; }
    }
    gate.lastDrop = now;
    return false;
  }

  static String normalize(String text) {
    return text == null ? "" : text.replaceAll("(?i)§[0-9a-fk-orx]", "").replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
  }

  static String packetText(Object packet) {
    for (String name : new String[] {"content", "message", "getContent", "getMessage"}) {
      try {
        Object value = SuitePacketGuard.call(packet, name);
        String text = componentText(value);
        if (text != null) return text;
      } catch (ReflectiveOperationException | RuntimeException ignored) { }
    }
    for (Class<?> type = packet.getClass(); type != null && type != Object.class; type = type.getSuperclass())
      for (Field f : type.getDeclaredFields()) {
        if (Modifier.isStatic(f.getModifiers())) continue;
        try { f.setAccessible(true); String text = componentText(f.get(packet)); if (text != null) return text; }
        catch (ReflectiveOperationException | RuntimeException ignored) { }
      }
    return "";
  }

  private static String componentText(Object value) {
    if (value == null) return null;
    if (value instanceof Component c) return PlainTextComponentSerializer.plainText().serialize(c);
    if (value instanceof String s) return s;
    try { Object text = SuitePacketGuard.call(value, "getString"); return text == null ? null : String.valueOf(text); }
    catch (ReflectiveOperationException | RuntimeException e) { return null; }
  }

  private static String soundName(Object packet) {
    try { return String.valueOf(SuitePacketGuard.call(packet, "getSound", "sound")).toLowerCase(Locale.ROOT); }
    catch (ReflectiveOperationException | RuntimeException e) { return ""; }
  }

  // ------------------------------------------------------------------ delivery that passes the gate

  void deliver(Player p, Component message) {
    Gate gate = gate(p);
    String text = normalize(PlainTextComponentSerializer.plainText().serialize(message));
    if (gate.active && !text.isEmpty()) gate.tokens.add(new Token(text, System.currentTimeMillis() + TOKEN_MS));
    p.sendMessage(message);
  }

  void allowText(Player p, String plain) {
    Gate gate = gate(p);
    String text = normalize(plain);
    if (gate.active && text.length() >= 1) gate.tokens.add(new Token(text, System.currentTimeMillis() + TOKEN_MS));
  }

  private Component prefix() {
    return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(plugin.prefix());
  }

  private Component commandHint(Player p, String label, String command, String hover, NamedTextColor color) {
    if (plugin.isBedrockClient(p)) return Component.text(command, NamedTextColor.YELLOW);
    return Component.text("[" + label + "]", color, TextDecoration.BOLD)
        .clickEvent(ClickEvent.runCommand(command))
        .hoverEvent(HoverEvent.showText(Component.text(hover, NamedTextColor.GRAY)));
  }

  private String onCommand(Player p) { return de(p) ? "/chat an" : "/chat on"; }
  private String mentionsOffCommand(Player p) { return de(p) ? "/chat erwähnungen aus" : "/chat mentions off"; }

  void feedback(Player p, boolean positive) {
    gate(p).soundUntil = System.currentTimeMillis() + 200;
    try {
      if (positive) {
        p.playSound(p.getLocation(), "minecraft:block.amethyst_block.chime", SoundCategory.MASTER, 0.7f, 1.4f);
        p.playSound(p.getLocation(), "minecraft:entity.experience_orb.pickup", SoundCategory.MASTER, 0.35f, 1.6f);
      } else {
        p.playSound(p.getLocation(), "minecraft:block.note_block.bass", SoundCategory.MASTER, 0.6f, 0.8f);
      }
    } catch (RuntimeException ignored) { }
  }

  private void muteEffect(Player p, boolean muted) {
    gate(p).soundUntil = System.currentTimeMillis() + 250;
    try {
      p.playSound(p.getLocation(), muted ? "minecraft:block.beacon.deactivate" : "minecraft:block.beacon.activate", SoundCategory.MASTER, 0.45f, muted ? 1.6f : 1.8f);
      p.playSound(p.getLocation(), "minecraft:block.amethyst_block.chime", SoundCategory.MASTER, 0.6f, muted ? 0.9f : 1.5f);
      Location at = p.getLocation().add(0, 2.1, 0);
      p.spawnParticle(muted ? Particle.SMOKE : Particle.HAPPY_VILLAGER, at, muted ? 8 : 10, 0.25, 0.15, 0.25, 0.01);
      if (!muted) p.spawnParticle(Particle.NOTE, at, 4, 0.4, 0.2, 0.4, 1);
    } catch (RuntimeException ignored) { }
  }

  private void actionBar(Player p, String text) {
    try { p.sendActionBar(Component.text(text)); } catch (RuntimeException ignored) { }
  }

  // ------------------------------------------------------------------ chat events

  /** Called from the Suite chat pipeline after menu inputs were consumed. True = the message is blocked. */
  boolean blockOwnChat(Player p) {
    if (!silenced(p)) return false;
    Bukkit.getScheduler().runTask(plugin, () -> {
      if (!p.isOnline()) return;
      Component line = prefix().append(Component.text(t(p, "Du hast den Chat stummgeschaltet – deine Nachricht wurde nicht gesendet. ", "You muted the chat – your message was not sent. "), NamedTextColor.RED));
      line = plugin.isBedrockClient(p)
          ? line.append(Component.text(t(p, "Zum Einschalten: ", "To turn it on: "), NamedTextColor.GRAY)).append(Component.text(onCommand(p), NamedTextColor.YELLOW))
          : line.append(commandHint(p, t(p, "Chat einschalten", "Turn chat on"), onCommand(p), t(p, "Klicken, um den Chat wieder zu sehen und zu schreiben", "Click to see and use the chat again"), NamedTextColor.GREEN));
      deliver(p, line);
      feedback(p, false);
    });
    return true;
  }

  /** Mentions are prepared before other plugins may cancel and re-broadcast the message as system chat. */
  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  public void prepareMentions(AsyncChatEvent e) {
    if (states.isEmpty()) return;
    String message = PlainTextComponentSerializer.plainText().serialize(e.message());
    for (Player viewer : Bukkit.getOnlinePlayers()) {
      if (viewer.equals(e.getPlayer()) || !silenced(viewer)) continue;
      State s = state(viewer.getUniqueId());
      if (!s.mentions && fullMention(message, viewer.getName())) allowText(viewer, message);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void filterViewers(AsyncChatEvent e) {
    if (states.isEmpty()) return;
    String message = PlainTextComponentSerializer.plainText().serialize(e.message());
    long now = System.currentTimeMillis();
    try {
      e.viewers().removeIf(audience -> {
        if (!(audience instanceof Player viewer) || viewer.equals(e.getPlayer()) || !silenced(viewer)) return false;
        State s = state(viewer.getUniqueId());
        if (!s.mentions && fullMention(message, viewer.getName())) {
          Long last = hints.get(viewer.getUniqueId());
          if (last == null || now - last > HINT_MS) {
            hints.put(viewer.getUniqueId(), now);
            String from = e.getPlayer().getName();
            Bukkit.getScheduler().runTaskLater(plugin, () -> mentionHint(viewer, from), 2L);
          }
          return false;
        }
        gate(viewer).lastDrop = now;
        return true;
      });
    } catch (UnsupportedOperationException ignored) { }
  }

  private void mentionHint(Player viewer, String from) {
    if (!viewer.isOnline()) return;
    Component line = prefix().append(Component.text(t(viewer, from + " hat dich erwähnt. Erwähnungen kommen trotz Chat-Ruhe an. ", from + " mentioned you. Mentions still arrive during your chat break. "), NamedTextColor.GOLD));
    line = plugin.isBedrockClient(viewer)
        ? line.append(Component.text(t(viewer, "Auch stummschalten: ", "Mute them too: "), NamedTextColor.GRAY)).append(Component.text(mentionsOffCommand(viewer), NamedTextColor.YELLOW))
        : line.append(commandHint(viewer, t(viewer, "Erwähnungen stummschalten", "Mute mentions"), mentionsOffCommand(viewer), t(viewer, "Klicken: auch Erwähnungen ausblenden. Im Menü /chat jederzeit änderbar.", "Click to hide mentions too. Change it any time in /chat."), NamedTextColor.YELLOW));
    deliver(viewer, line);
  }

  /** Tells the sender (rate-limited) when a mentioned online player will not see the message. */
  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void informSender(AsyncChatEvent e) {
    if (states.isEmpty()) return;
    Player sender = e.getPlayer();
    String message = PlainTextComponentSerializer.plainText().serialize(e.message());
    long now = System.currentTimeMillis();
    List<String[]> notes = new ArrayList<>();
    for (Player target : Bukkit.getOnlinePlayers()) {
      if (target.equals(sender) || !silenced(target) || plugin.chatSilenceHidden(target, sender)) continue;
      boolean full = fullMention(message, target.getName());
      if (!full && !partialMention(message, target.getName())) continue;
      State s = state(target.getUniqueId());
      if (full && !s.mentions) continue;
      String key = sender.getUniqueId() + ":" + target.getUniqueId();
      Long last = senderInfo.get(key);
      if (last != null && now - last < SENDER_INFO_MS) continue;
      senderInfo.put(key, now);
      notes.add(new String[] {target.getName(), s.mentions ? "mentions" : "chat"});
    }
    if (notes.isEmpty()) return;
    Bukkit.getScheduler().runTask(plugin, () -> {
      if (!sender.isOnline()) return;
      for (String[] note : notes) {
        String text = note[1].equals("mentions")
            ? t(sender, note[0] + " hat den Chat und Erwähnungen stummgeschaltet und sieht deine Nachricht nicht.", note[0] + " muted the chat and mentions and will not see your message.")
            : t(sender, note[0] + " hat den Chat stummgeschaltet. Nur Erwähnungen mit vollem Namen kommen an.", note[0] + " muted the chat. Only mentions with the full name get through.");
        deliver(sender, prefix().append(Component.text(text, NamedTextColor.GRAY)));
      }
    });
  }

  static boolean fullMention(String message, String name) {
    if (message == null || name == null || name.isBlank()) return false;
    return java.util.regex.Pattern.compile("(?i)(?<![A-Za-z0-9_])@?" + java.util.regex.Pattern.quote(name) + "(?![A-Za-z0-9_])").matcher(message).find();
  }

  /** At least six letters of the name (or the whole name when it is shorter) inside one word. */
  static boolean partialMention(String message, String name) {
    if (message == null || name == null || name.isBlank()) return false;
    String lower = name.toLowerCase(Locale.ROOT);
    int need = Math.min(6, lower.length());
    for (String word : message.toLowerCase(Locale.ROOT).split("[^a-z0-9_]+")) {
      if (word.length() < need) continue;
      if (lower.contains(word) || word.contains(lower)) return true;
      for (int i = 0; i + need <= word.length(); i++) if (lower.contains(word.substring(i, i + need))) return true;
    }
    return false;
  }

  /** Called by the Suite for whisper commands. True = cancel the command. */
  boolean whisper(Player sender, List<UUID> recipients, String text) {
    if (states.isEmpty() || recipients.isEmpty()) return false;
    boolean blocked = false;
    for (UUID id : recipients) {
      Player target = Bukkit.getPlayer(id);
      if (target == null || target.equals(sender) || !silenced(target)) continue;
      if (plugin.chatSilenceHidden(target, sender)) continue;
      if (state(id).whispers) {
        blocked = true;
        deliver(sender, prefix().append(Component.text(t(sender, target.getName() + " hat den Chat stummgeschaltet und empfängt gerade keine Flüsternachrichten.", target.getName() + " muted the chat and is not receiving whispers right now."), NamedTextColor.RED)));
        feedback(sender, false);
      } else if (text != null && !text.isBlank()) allowText(target, text);
    }
    return blocked;
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void commandFeedback(PlayerCommandPreprocessEvent e) {
    Gate gate = gates.get(e.getPlayer().getUniqueId());
    if (gate != null && gate.active) gate.openUntil = System.currentTimeMillis() + FEEDBACK_WINDOW_MS;
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void clickFeedback(InventoryClickEvent e) {
    if (!(e.getWhoClicked() instanceof Player p)) return;
    Gate gate = gates.get(p.getUniqueId());
    if (gate != null && gate.active) gate.openUntil = Math.max(gate.openUntil, System.currentTimeMillis() + CLICK_WINDOW_MS);
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void join(PlayerJoinEvent e) {
    Player p = e.getPlayer();
    refreshGate(p);
    State s = states.get(p.getUniqueId());
    if (s != null && !s.name.equals(p.getName())) { states.put(p.getUniqueId(), new State(s.chat, s.until, s.whispers, s.mentions, p.getName(), s.by, s.since)); save(); }
    if (!silenced(p)) return;
    Bukkit.getScheduler().runTaskLater(plugin, () -> {
      if (!p.isOnline() || !silenced(p)) return;
      Component line = prefix().append(Component.text(t(p, "Dein Chat ist stummgeschaltet (" + remaining(p, state(p.getUniqueId())) + "). ", "Your chat is muted (" + remaining(p, state(p.getUniqueId())) + "). "), NamedTextColor.GRAY));
      line = plugin.isBedrockClient(p)
          ? line.append(Component.text(t(p, "Verwalten: /chat", "Manage: /chat"), NamedTextColor.YELLOW))
          : line.append(commandHint(p, t(p, "Verwalten", "Manage"), "/chat", t(p, "Chat-Ruhe öffnen", "Open chat break"), NamedTextColor.AQUA));
      deliver(p, line);
    }, 60L);
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void quit(PlayerQuitEvent e) {
    gates.remove(e.getPlayer().getUniqueId());
    hints.remove(e.getPlayer().getUniqueId());
  }

  // ------------------------------------------------------------------ command

  boolean command(CommandSender sender, String[] args) {
    if (!(sender instanceof Player p)) { sender.sendMessage("Use /chat in Minecraft. Staff manage chat breaks in the app or web panel."); return true; }
    if (args.length == 0) { open(p, null); return true; }
    String action = args[0].toLowerCase(Locale.ROOT);
    switch (action) {
      case "an", "on", "ein", "unmute", "einschalten", "show", "zeigen" -> {
        if (!silenced(p)) { deliver(p, prefix().append(Component.text(t(p, "Dein Chat ist bereits eingeschaltet.", "Your chat is already on."), NamedTextColor.GRAY))); return true; }
        unmute(p.getUniqueId(), p.getName(), "self");
        confirmChange(p);
      }
      case "aus", "off", "mute", "stumm", "stummschalten", "ruhe", "pause", "hide" -> {
        long minutes = args.length > 1 ? parseMinutes(args[1]) : 0;
        if (minutes < 0) { usage(p); return true; }
        mute(p.getUniqueId(), p.getName(), minutes, "self");
        confirmChange(p);
      }
      case "flüstern", "fluestern", "whisper", "whispers", "msg" -> {
        Boolean muted = args.length > 1 ? offValue(args[1]) : null;
        State s = state(p.getUniqueId());
        options(p.getUniqueId(), muted == null ? !s.whispers : muted, s.mentions);
        optionNotice(p);
      }
      case "erwähnungen", "erwaehnungen", "mentions", "mention", "erwähnung" -> {
        Boolean muted = args.length > 1 ? offValue(args[1]) : null;
        State s = state(p.getUniqueId());
        options(p.getUniqueId(), s.whispers, muted == null ? !s.mentions : muted);
        optionNotice(p);
      }
      case "status", "info" -> deliver(p, prefix().append(Component.text(statusLine(p, state(p.getUniqueId())), NamedTextColor.WHITE)));
      case "hilfe", "help", "?" -> usage(p);
      case "menü", "menu", "einstellungen", "settings" -> open(p, null);
      default -> {
        long minutes = parseMinutes(action);
        if (minutes >= 0 && !action.isBlank() && Character.isDigit(action.charAt(0))) { mute(p.getUniqueId(), p.getName(), minutes, "self"); confirmChange(p); }
        else usage(p);
      }
    }
    return true;
  }

  List<String> complete(CommandSender sender, String[] args) {
    boolean german = sender instanceof Player p && de(p);
    if (args.length == 1) return filter(german ? List.of("an", "aus", "flüstern", "erwähnungen", "status", "hilfe") : List.of("on", "off", "whispers", "mentions", "status", "help"), args[0]);
    if (args.length == 2) {
      String first = args[0].toLowerCase(Locale.ROOT);
      if (Set.of("aus", "off", "mute", "stumm", "ruhe", "pause").contains(first)) return filter(german ? List.of("30m", "3h", "24h", "dauerhaft") : List.of("30m", "3h", "24h", "permanent"), args[1]);
      if (Set.of("flüstern", "fluestern", "whispers", "whisper", "erwähnungen", "erwaehnungen", "mentions", "mention").contains(first)) return filter(german ? List.of("an", "aus") : List.of("on", "off"), args[1]);
    }
    return List.of();
  }

  private static List<String> filter(List<String> values, String prefix) {
    String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
    return values.stream().filter(v -> v.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
  }

  /** "an/on" = arrives (not muted), "aus/off" = muted. Null for unknown words. */
  private static Boolean offValue(String value) {
    String v = value.toLowerCase(Locale.ROOT);
    if (Set.of("aus", "off", "stumm", "mute", "nein", "no", "false").contains(v)) return true;
    if (Set.of("an", "on", "ein", "ja", "yes", "true", "unmute").contains(v)) return false;
    return null;
  }

  /** Minutes; 0 = until turned on; -1 = invalid. Plain numbers are hours. */
  static long parseMinutes(String value) {
    if (value == null) return -1;
    String v = value.toLowerCase(Locale.ROOT).strip();
    if (Set.of("dauerhaft", "perm", "permanent", "immer", "forever", "always", "unbegrenzt").contains(v)) return 0;
    var m = java.util.regex.Pattern.compile("^(\\d{1,4})(m|min|minute|minuten|minutes|h|std|stunde|stunden|hour|hours|d|t|tag|tage|day|days)?$").matcher(v);
    if (!m.matches()) return -1;
    long n = Long.parseLong(m.group(1));
    String unit = m.group(2) == null ? "h" : m.group(2);
    long minutes = unit.startsWith("m") ? n : unit.startsWith("d") || unit.startsWith("t") ? n * 1440 : n * 60;
    return minutes <= 0 || minutes > 60L * 24 * 365 ? -1 : minutes;
  }

  private void usage(Player p) {
    String[] lines = de(p)
        ? new String[] {"§b§lChat-Ruhe §7– so geht's", "§e/chat §7– Menü mit Vorschau öffnen", "§e/chat aus [30m|3h|24h|dauerhaft] §7– Chat stummschalten", "§e/chat an §7– Chat wieder einschalten", "§e/chat flüstern an|aus §7– Flüsternachrichten erlauben/stummschalten", "§e/chat erwähnungen an|aus §7– Erwähnungen erlauben/stummschalten", "§e/chat status §7– aktuellen Stand zeigen"}
        : new String[] {"§b§lChat break §7– how it works", "§e/chat §7– open the menu with a preview", "§e/chat off [30m|3h|24h|permanent] §7– mute the chat", "§e/chat on §7– turn the chat back on", "§e/chat whispers on|off §7– allow/mute whispers", "§e/chat mentions on|off §7– allow/mute mentions", "§e/chat status §7– show the current state"};
    for (String line : lines) deliver(p, net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(line));
  }

  private void confirmChange(Player p) {
    State s = state(p.getUniqueId());
    boolean muted = s.active(System.currentTimeMillis());
    muteEffect(p, muted);
    Component line = prefix().append(Component.text(muted
        ? t(p, "Chat stummgeschaltet (" + remaining(p, s) + "). Keine Chatnachrichten, keine Chat-Töne. ", "Chat muted (" + remaining(p, s) + "). No chat messages, no chat sounds. ")
        : t(p, "Chat wieder eingeschaltet. Willkommen zurück! ", "Chat is back on. Welcome back! "), muted ? NamedTextColor.GOLD : NamedTextColor.GREEN));
    if (muted) line = plugin.isBedrockClient(p)
        ? line.append(Component.text(t(p, "Einschalten: /chat an", "Turn on: /chat on"), NamedTextColor.YELLOW))
        : line.append(commandHint(p, t(p, "Rückgängig", "Undo"), onCommand(p), t(p, "Chat sofort wieder einschalten", "Turn the chat back on now"), NamedTextColor.GREEN));
    deliver(p, line);
    actionBar(p, muted ? t(p, "Chat-Ruhe aktiv · " + remaining(p, s), "Chat break active · " + remaining(p, s)) : t(p, "Chat ist wieder an", "Chat is on again"));
  }

  private void optionNotice(Player p) {
    State s = state(p.getUniqueId());
    feedback(p, true);
    deliver(p, prefix().append(Component.text(t(p, "Flüstern: ", "Whispers: ") + onOff(p, !s.whispers) + " · " + t(p, "Erwähnungen: ", "Mentions: ") + onOff(p, !s.mentions)
        + (s.active(System.currentTimeMillis()) ? "" : t(p, " (gilt während der Chat-Ruhe)", " (applies during a chat break)")), NamedTextColor.AQUA)));
  }

  private String onOff(Player p, boolean arrives) { return arrives ? t(p, "kommen an", "arrive") : t(p, "stumm", "muted"); }

  String remaining(Player p, State s) {
    if (!s.chat) return t(p, "aus", "off");
    if (s.until <= 0) return t(p, "bis du ihn wieder einschaltest", "until you turn it back on");
    long minutes = Math.max(1, (s.until - System.currentTimeMillis() + 59_999) / 60_000);
    long h = minutes / 60, m = minutes % 60;
    String time = h > 0 ? h + (de(p) ? " Std." : "h") + (m > 0 ? " " + m + (de(p) ? " Min." : "m") : "") : m + (de(p) ? " Min." : "m");
    return t(p, "noch " + time, time + " left");
  }

  private String statusLine(Player p, State s) {
    boolean muted = s.active(System.currentTimeMillis());
    return (muted ? t(p, "Chat-Ruhe: aktiv, ", "Chat break: active, ") + remaining(p, s) : t(p, "Chat-Ruhe: aus – du siehst den Chat.", "Chat break: off – you see the chat."))
        + "\n" + t(p, "Flüstern: ", "Whispers: ") + onOff(p, !s.whispers) + " · " + t(p, "Erwähnungen: ", "Mentions: ") + onOff(p, !s.mentions);
  }

  /** What the player will experience – shown in every menu before a change. */
  private String preview(Player p, State s) {
    boolean muted = s.active(System.currentTimeMillis());
    StringBuilder b = new StringBuilder();
    b.append(muted ? t(p, "§6● Chat-Ruhe aktiv §7(" + remaining(p, s) + ")", "§6● Chat break active §7(" + remaining(p, s) + ")") : t(p, "§a● Chat sichtbar", "§a● Chat visible")).append("\n\n");
    b.append(t(p, "§fSo funktioniert die Chat-Ruhe:", "§fWhile the chat break is on:")).append('\n');
    b.append(t(p, "§7• Keine Chatnachrichten anderer Spieler und Plugins", "§7• No chat from other players and plugins")).append('\n');
    b.append(t(p, "§7• Keine Chat-Töne", "§7• No chat sounds")).append('\n');
    b.append(t(p, "§7• Erwähnungen mit vollem Namen: ", "§7• Mentions with your full name: ")).append(s.mentions ? t(p, "§cstumm", "§cmuted") : t(p, "§akommen an", "§aarrive")).append('\n');
    b.append(t(p, "§7• Flüsternachrichten: ", "§7• Whispers: ")).append(s.whispers ? t(p, "§cstumm", "§cmuted") : t(p, "§akommen an", "§aarrive")).append('\n');
    b.append(t(p, "§7• Selbst schreiben ist pausiert – Befehle funktionieren weiter", "§7• Writing is paused – commands keep working"));
    return b.toString();
  }

  // ------------------------------------------------------------------ menus

  void open(Player p, Runnable back) {
    if (stopped || !p.isOnline()) return;
    gate(p).openUntil = System.currentTimeMillis() + CLICK_WINDOW_MS;
    if (plugin.isBedrockClient(p)) { if (bedrock(p, back)) return; }
    else {
      String mode = plugin.getConfig().getString("chat-silence.java-menu", "dialog");
      if (!"inventory".equalsIgnoreCase(mode) && dialog(p, back)) { openSound(p); return; }
    }
    inventory(p, back);
  }

  private void openSound(Player p) {
    gate(p).soundUntil = System.currentTimeMillis() + 200;
    try { p.playSound(p.getLocation(), "minecraft:ui.button.click", SoundCategory.MASTER, 0.5f, 1.25f); } catch (RuntimeException ignored) { }
  }

  private static String durationId(State s, long now) {
    if (!s.active(now)) return "on";
    if (s.until <= 0) return "perm";
    long left = (s.until - now) / 60_000;
    return left <= 45 ? "30m" : left <= 240 ? "3h" : "24h";
  }

  private long minutesFor(String id) {
    return switch (id) { case "30m" -> 30; case "3h" -> 180; case "24h" -> 1440; default -> 0; };
  }

  private String presetLabel(Player p, long minutes) {
    return minutes == 30 ? t(p, "30 Minuten", "30 minutes") : minutes == 180 ? t(p, "3 Stunden", "3 hours") : minutes == 1440 ? t(p, "24 Stunden", "24 hours") : t(p, "Dauerhaft", "Until I turn it on");
  }

  /** Native Java dialog (Paper Dialog API). False when the client or server does not support it. */
  private boolean dialog(Player p, Runnable back) {
    try {
      UUID owner = p.getUniqueId();
      long now = System.currentTimeMillis();
      State s = state(owner);
      String current = durationId(s, now);
      boolean timed = s.active(now) && s.until > 0;
      String initial = timed ? "keep" : current;
      List<SingleOptionDialogInput.OptionEntry> entries = new ArrayList<>();
      entries.add(SingleOptionDialogInput.OptionEntry.create("on", Component.text(t(p, "Chat sichtbar", "Chat visible"), NamedTextColor.GREEN), initial.equals("on")));
      if (timed) entries.add(SingleOptionDialogInput.OptionEntry.create("keep", Component.text(t(p, "Aktuelle Ruhe behalten (" + remaining(p, s) + ")", "Keep current break (" + remaining(p, s) + ")"), NamedTextColor.AQUA), true));
      entries.add(SingleOptionDialogInput.OptionEntry.create("30m", Component.text(t(p, "Ruhe für 30 Minuten", "Mute for 30 minutes")), initial.equals("30m")));
      entries.add(SingleOptionDialogInput.OptionEntry.create("3h", Component.text(t(p, "Ruhe für 3 Stunden", "Mute for 3 hours")), initial.equals("3h")));
      entries.add(SingleOptionDialogInput.OptionEntry.create("24h", Component.text(t(p, "Ruhe für 24 Stunden", "Mute for 24 hours")), initial.equals("24h")));
      entries.add(SingleOptionDialogInput.OptionEntry.create("perm", Component.text(t(p, "Dauerhaft (bis ich ihn einschalte)", "Until I turn it back on"), NamedTextColor.GOLD), initial.equals("perm")));
      var mode = DialogInput.singleOption("mode", Component.text(t(p, "Chat", "Chat"), NamedTextColor.WHITE), entries).width(300).build();
      var whispers = DialogInput.bool("whispers", Component.text(t(p, "Flüsternachrichten auch stummschalten", "Also mute whispers"))).initial(s.whispers).build();
      var mentions = DialogInput.bool("mentions", Component.text(t(p, "Erwähnungen auch stummschalten", "Also mute mentions"))).initial(s.mentions).build();
      ItemStack icon = new ItemStack(s.active(now) ? Material.BELL : Material.NOTE_BLOCK);
      Component status = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(preview(p, s));
      DialogBase base = DialogBase.builder(Component.text(t(p, "Chat-Ruhe", "Chat break"), NamedTextColor.AQUA, TextDecoration.BOLD))
          .externalTitle(Component.text(t(p, "Chat-Ruhe", "Chat break")))
          .canCloseWithEscape(true).pause(false)
          .body(List.of(DialogBody.item(icon).description(DialogBody.plainMessage(Component.text(t(p, "Nimm dir eine Pause vom Chat – mit einem Klick wieder da.", "Take a break from chat – back with one click."), NamedTextColor.GRAY))).showTooltip(false).build(),
              DialogBody.plainMessage(status, 300)))
          .inputs(List.of(mode, whispers, mentions)).build();
      var save = DialogAction.customClick((view, audience) -> {
        if (!(audience instanceof Player sender) || !sender.getUniqueId().equals(owner)) return;
        String chosen = view.getText("mode");
        Boolean w = view.getBoolean("whispers"), m = view.getBoolean("mentions");
        Bukkit.getScheduler().runTask(plugin, () -> {
          if (!sender.isOnline()) return;
          applyChoice(sender, chosen == null ? "keep" : chosen, Boolean.TRUE.equals(w), Boolean.TRUE.equals(m));
          if (back != null) back.run();
        });
      }, ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(10)).build());
      var quickOn = DialogAction.customClick((view, audience) -> {
        if (!(audience instanceof Player sender) || !sender.getUniqueId().equals(owner)) return;
        Bukkit.getScheduler().runTask(plugin, () -> { if (sender.isOnline()) { unmute(owner, sender.getName(), "self"); confirmChange(sender); } });
      }, ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(10)).build());
      List<ActionButton> buttons = new ArrayList<>();
      buttons.add(ActionButton.builder(Component.text(t(p, "✔ Übernehmen", "✔ Apply"), NamedTextColor.GREEN)).tooltip(Component.text(t(p, "Auswahl speichern", "Save your choice"))).width(150).action(save).build());
      if (s.active(now)) buttons.add(ActionButton.builder(Component.text(t(p, "Chat sofort an", "Chat on now"), NamedTextColor.AQUA)).tooltip(Component.text(t(p, "Chat-Ruhe beenden", "End the chat break"))).width(150).action(quickOn).build());
      ActionButton close = ActionButton.builder(Component.text(t(p, "Schließen", "Close"))).width(150).build();
      Dialog dialog = Dialog.create(builder -> builder.empty().base(base).type(DialogType.multiAction(buttons).exitAction(close).columns(2).build()));
      p.closeInventory();
      p.showDialog(dialog);
      return true;
    } catch (LinkageError | RuntimeException unsupported) {
      return false;
    }
  }

  private void applyChoice(Player p, String chosen, boolean whispers, boolean mentions) {
    UUID id = p.getUniqueId();
    State before = state(id);
    boolean wasMuted = before.active(System.currentTimeMillis());
    if (before.whispers != whispers || before.mentions != mentions) options(id, whispers, mentions);
    if (chosen.equals("perm") && wasMuted && before.until <= 0) chosen = "keep";
    if (chosen.equals("on") && !wasMuted) chosen = "keep";
    if (chosen.equals("keep")) { optionNotice(p); return; }
    if (chosen.equals("on")) unmute(id, p.getName(), "self");
    else mute(id, p.getName(), minutesFor(chosen), "self");
    confirmChange(p);
  }

  // Bedrock: native Floodgate forms with icons.
  private boolean bedrock(Player p, Runnable back) {
    UUID owner = p.getUniqueId();
    long now = System.currentTimeMillis();
    State s = state(owner);
    boolean muted = s.active(now);
    var form = SimplexAdminSuite.SimpleForm.builder()
        .title(t(p, "Chat-Ruhe", "Chat break"))
        .content(preview(p, s));
    List<Runnable> actions = new ArrayList<>();
    if (muted) {
      form.button(t(p, "Chat wieder einschalten", "Turn chat back on") + "\n" + t(p, "Sofort alles wieder sehen", "See everything again"), "textures/ui/mute_off", r -> run(owner, () -> { unmute(owner, p.getName(), "self"); confirmChange(p); }));
    }
    for (long minutes : PRESETS) {
      String label = minutes == 0 ? t(p, "Dauerhaft stummschalten", "Mute until I turn it on") : t(p, "Stumm für ", "Mute for ") + presetLabel(p, minutes);
      String detail = minutes == 0 ? t(p, "Bis du /chat an nutzt", "Until you use /chat on") : t(p, "Danach automatisch wieder an", "Turns back on automatically");
      form.button(label + "\n" + detail, minutes == 0 ? "textures/ui/mute_on" : "textures/items/clock_item", r -> run(owner, () -> { mute(owner, p.getName(), minutes, "self"); confirmChange(p); bedrock(p, back); }));
    }
    form.button(t(p, "Flüstern: ", "Whispers: ") + onOff(p, !s.whispers) + "\n" + t(p, "Tippen zum Umschalten", "Tap to switch"), "textures/items/feather", r -> run(owner, () -> { State c = state(owner); options(owner, !c.whispers, c.mentions); feedback(p, true); bedrock(p, back); }));
    form.button(t(p, "Erwähnungen: ", "Mentions: ") + onOff(p, !s.mentions) + "\n" + t(p, "Tippen zum Umschalten", "Tap to switch"), "textures/items/name_tag", r -> run(owner, () -> { State c = state(owner); options(owner, c.whispers, !c.mentions); feedback(p, true); bedrock(p, back); }));
    form.button(t(p, "Alles auf einmal einstellen", "Set everything at once") + "\n" + t(p, "Dauer, Flüstern, Erwähnungen", "Duration, whispers, mentions"), "textures/ui/icon_setting", r -> run(owner, () -> bedrockCustom(p, back)));
    if (back != null) form.button(t(p, "Zurück", "Back"), "textures/ui/arrow_left", r -> run(owner, back));
    return plugin.sendBedrockForm(p, form);
  }

  private void bedrockCustom(Player p, Runnable back) {
    UUID owner = p.getUniqueId();
    State s = state(owner);
    String current = durationId(s, System.currentTimeMillis());
    List<String> ids = List.of("on", "30m", "3h", "24h", "perm");
    List<String> labels = List.of(t(p, "Chat sichtbar", "Chat visible"), t(p, "Stumm für 30 Minuten", "Mute for 30 minutes"), t(p, "Stumm für 3 Stunden", "Mute for 3 hours"), t(p, "Stumm für 24 Stunden", "Mute for 24 hours"), t(p, "Dauerhaft stumm", "Mute until I turn it on"));
    var form = SimplexAdminSuite.CustomForm.builder()
        .title(t(p, "Chat-Ruhe einstellen", "Chat break settings"))
        .dropdown(t(p, "Chat", "Chat"), labels, Math.max(0, ids.indexOf(current)))
        .toggle(t(p, "Flüsternachrichten auch stummschalten", "Also mute whispers"), s.whispers)
        .toggle(t(p, "Erwähnungen auch stummschalten", "Also mute mentions"), s.mentions)
        .validResultHandler(r -> run(owner, () -> {
          int index = Math.max(0, Math.min(ids.size() - 1, r.asDropdown(0)));
          String chosen = ids.get(index);
          if (chosen.equals(current) && !chosen.equals("on")) chosen = "keep";
          applyChoice(p, chosen, r.asToggle(1), r.asToggle(2));
          bedrock(p, back);
        }));
    if (!plugin.sendBedrockForm(p, form)) bedrock(p, back);
  }

  private void run(UUID owner, Runnable action) {
    Bukkit.getScheduler().runTask(plugin, () -> {
      Player p = Bukkit.getPlayer(owner);
      if (p != null && p.isOnline()) action.run();
    });
  }

  // Java fallback: a calm chest menu.
  static final class Holder implements InventoryHolder {
    final UUID owner;
    final Runnable back;
    final Map<Integer, Runnable> actions = new HashMap<>();
    Inventory inventory;
    Holder(UUID owner, Runnable back) { this.owner = owner; this.back = back; }
    public Inventory getInventory() { return inventory; }
  }

  private void inventory(Player p, Runnable back) {
    UUID owner = p.getUniqueId();
    long now = System.currentTimeMillis();
    State s = state(owner);
    boolean muted = s.active(now);
    Holder h = new Holder(owner, back);
    Inventory inv = LiveMenus.create(h, 54, t(p, "Chat-Ruhe", "Chat break"));
    h.inventory = inv;
    ItemStack frame = item(muted ? Material.ORANGE_STAINED_GLASS_PANE : Material.LIME_STAINED_GLASS_PANE, " ", List.of());
    ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
    for (int i = 0; i < 54; i++) inv.setItem(i, i < 9 || i >= 45 || i % 9 == 0 || i % 9 == 8 ? frame : filler);
    List<String> previewLore = new ArrayList<>(Arrays.asList(preview(p, s).split("\n")));
    inv.setItem(4, glow(item(muted ? Material.BELL : Material.NOTE_BLOCK, muted ? t(p, "§6§lChat-Ruhe aktiv", "§6§lChat break active") : t(p, "§a§lChat sichtbar", "§a§lChat visible"), previewLore), muted));
    int slot = 19;
    if (muted) {
      inv.setItem(slot, item(Material.LIME_DYE, t(p, "§a§lChat wieder einschalten", "§a§lTurn chat back on"), List.of(t(p, "§7Sofort alles wieder sehen", "§7See everything again"), "", t(p, "§e➜ Klicken", "§e➜ Click"))));
      h.actions.put(slot, () -> { unmute(owner, p.getName(), "self"); confirmChange(p); inventory(p, back); });
    } else {
      inv.setItem(slot, item(Material.LIME_DYE, t(p, "§aChat ist an", "§aChat is on"), List.of(t(p, "§7Wähle rechts eine Dauer", "§7Pick a duration on the right"))));
    }
    int[] presetSlots = {21, 22, 23, 24};
    Material[] icons = {Material.CLOCK, Material.CLOCK, Material.CLOCK, Material.BELL};
    for (int i = 0; i < PRESETS.length; i++) {
      long minutes = PRESETS[i];
      boolean selected = muted && (minutes == 0 ? s.until <= 0 : durationId(s, now).equals(minutes == 30 ? "30m" : minutes == 180 ? "3h" : "24h"));
      List<String> lore = new ArrayList<>();
      lore.add(minutes == 0 ? t(p, "§7Bis du den Chat wieder einschaltest", "§7Until you turn the chat back on") : t(p, "§7Danach automatisch wieder an", "§7Turns back on automatically"));
      lore.add(t(p, "§7Keine Chatnachrichten und Chat-Töne", "§7No chat messages and chat sounds"));
      lore.add("");
      lore.add(selected ? t(p, "§a✔ Aktiv", "§a✔ Active") : t(p, "§e➜ Klicken zum Stummschalten", "§e➜ Click to mute"));
      ItemStack icon = item(icons[i], (selected ? "§6§l" : "§f§l") + (minutes == 0 ? t(p, "Dauerhaft stumm", "Until I turn it on") : t(p, "Stumm für ", "Mute for ") + presetLabel(p, minutes)), lore);
      if (minutes > 0) icon.setAmount(minutes == 30 ? 1 : minutes == 180 ? 3 : 24);
      inv.setItem(presetSlots[i], glow(icon, selected));
      h.actions.put(presetSlots[i], () -> { mute(owner, p.getName(), minutes, "self"); confirmChange(p); inventory(p, back); });
    }
    inv.setItem(30, glow(item(Material.FEATHER, t(p, "§fFlüsternachrichten: ", "§fWhispers: ") + (s.whispers ? t(p, "§cstumm", "§cmuted") : t(p, "§akommen an", "§aarrive")),
        List.of(t(p, "§7Private Nachrichten (/msg, /w, /r)", "§7Private messages (/msg, /w, /r)"), t(p, "§7während der Chat-Ruhe", "§7during the chat break"), "", t(p, "§e➜ Klicken zum Umschalten", "§e➜ Click to switch"))), s.whispers));
    h.actions.put(30, () -> { State c = state(owner); options(owner, !c.whispers, c.mentions); feedback(p, true); inventory(p, back); });
    inv.setItem(32, glow(item(Material.NAME_TAG, t(p, "§fErwähnungen: ", "§fMentions: ") + (s.mentions ? t(p, "§cstumm", "§cmuted") : t(p, "§akommen an", "§aarrive")),
        List.of(t(p, "§7Nachrichten mit deinem vollen Namen", "§7Messages with your full name"), t(p, "§7während der Chat-Ruhe", "§7during the chat break"), "", t(p, "§e➜ Klicken zum Umschalten", "§e➜ Click to switch"))), s.mentions));
    h.actions.put(32, () -> { State c = state(owner); options(owner, c.whispers, !c.mentions); feedback(p, true); inventory(p, back); });
    inv.setItem(40, item(Material.BOOK, t(p, "§bSo geht's", "§bHow it works"), List.of(t(p, "§7/chat aus 3h §8– 3 Stunden Ruhe", "§7/chat off 3h §8– 3 hours of quiet"), t(p, "§7/chat an §8– Chat wieder an", "§7/chat on §8– chat back on"), t(p, "§7Auch im Profil: /profil → Einstellungen", "§7Also in /profile → settings"))));
    if (back != null) { inv.setItem(45, item(Material.ARROW, t(p, "§fZurück", "§fBack"), List.of())); h.actions.put(45, back); }
    inv.setItem(49, item(Material.BARRIER, t(p, "§cSchließen", "§cClose"), List.of()));
    h.actions.put(49, p::closeInventory);
    LiveMenus.show(p, inv);
    openSound(p);
  }

  private static ItemStack item(Material material, String name, List<String> lore) {
    ItemStack stack = new ItemStack(material);
    ItemMeta meta = stack.getItemMeta();
    if (meta != null) {
      meta.setDisplayName(name);
      meta.setLore(lore);
      meta.addItemFlags(ItemFlag.values());
      stack.setItemMeta(meta);
    }
    return stack;
  }

  private static ItemStack glow(ItemStack stack, boolean on) {
    if (!on) return stack;
    ItemMeta meta = stack.getItemMeta();
    if (meta != null) {
      try { meta.setEnchantmentGlintOverride(true); } catch (LinkageError | RuntimeException ignored) { }
      stack.setItemMeta(meta);
    }
    return stack;
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void click(InventoryClickEvent e) {
    if (!(LiveMenus.holder(e.getView().getTopInventory()) instanceof Holder h)) return;
    e.setCancelled(true);
    if (!(e.getWhoClicked() instanceof Player p) || !h.owner.equals(p.getUniqueId()) || e.getRawSlot() < 0 || e.getRawSlot() >= 54) return;
    Runnable action = h.actions.get(e.getRawSlot());
    if (action != null) Bukkit.getScheduler().runTask(plugin, () -> {
      if (p.getOpenInventory().getTopInventory() == h.inventory && LiveMenus.holder(h.inventory) == h) action.run();
    });
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void drag(InventoryDragEvent e) {
    if (LiveMenus.holder(e.getView().getTopInventory()) instanceof Holder) e.setCancelled(true);
  }

  // ------------------------------------------------------------------ app & web panel

  Map<String, Object> data(boolean manage) {
    long now = System.currentTimeMillis();
    List<Map<String, Object>> items = new ArrayList<>();
    for (var e : states.entrySet()) {
      State s = e.getValue();
      Player online = Bukkit.getPlayer(e.getKey());
      items.add(SimplexAdminSuite.appMap("player", e.getKey().toString(), "name", online != null ? online.getName() : s.name, "online", online != null,
          "bedrock", online != null && plugin.isBedrockClient(online), "active", s.active(now), "until", s.until, "whispers", s.whispers, "mentions", s.mentions,
          "by", s.by, "since", s.since));
    }
    items.sort(Comparator.<Map<String, Object>, Boolean>comparing(m -> !(Boolean) m.get("active")).thenComparing(m -> !(Boolean) m.get("online")).thenComparing(m -> String.valueOf(m.get("name")).toLowerCase(Locale.ROOT)));
    long active = items.stream().filter(m -> Boolean.TRUE.equals(m.get("active"))).count();
    return SimplexAdminSuite.appMap("manage", manage, "items", items, "active", active, "online", Bukkit.getOnlinePlayers().size(),
        "presets", List.of(30, 180, 1440, 0), "packetFilter", plugin.chatSilenceFilterStatus(), "javaMenu", plugin.getConfig().getString("chat-silence.java-menu", "dialog"));
  }

  Map<String, Object> apply(String actor, Map<String, Object> body) {
    String operation = String.valueOf(body.getOrDefault("operation", ""));
    String query = String.valueOf(body.getOrDefault("player", "")).strip();
    if (query.isEmpty()) throw new IllegalArgumentException("Spieler fehlt");
    UUID id;
    try { id = UUID.fromString(query); } catch (IllegalArgumentException notUuid) { id = plugin.discordAdminResolvePlayer(query); }
    if (id == null) throw new AppApiManager.ApiError(404, "Spieler nicht gefunden");
    Player online = Bukkit.getPlayer(id);
    String name = online != null ? online.getName() : plugin.discordAdminPlayerName(id);
    boolean notify = !Boolean.FALSE.equals(body.get("notify"));
    State before = state(id);
    switch (operation) {
      case "mute" -> {
        long minutes = body.get("minutes") instanceof Number n ? n.longValue() : 0;
        if (minutes < 0 || minutes > 60L * 24 * 365) throw new IllegalArgumentException("Dauer ungültig");
        mute(id, name, minutes, "staff:" + actor);
        if (body.containsKey("whispers") || body.containsKey("mentions"))
          options(id, Boolean.TRUE.equals(body.getOrDefault("whispers", before.whispers)), Boolean.TRUE.equals(body.getOrDefault("mentions", before.mentions)));
        plugin.suiteAudit(actor, "Chat-Ruhe gesetzt", name + " • " + (minutes <= 0 ? "dauerhaft" : minutes + " Minuten"));
        if (online != null && notify) staffNotice(online, true);
      }
      case "unmute" -> {
        unmute(id, name, "staff:" + actor);
        plugin.suiteAudit(actor, "Chat-Ruhe aufgehoben", name);
        if (online != null && notify) staffNotice(online, false);
      }
      case "options" -> {
        boolean whispers = body.containsKey("whispers") ? Boolean.TRUE.equals(body.get("whispers")) : before.whispers;
        boolean mentions = body.containsKey("mentions") ? Boolean.TRUE.equals(body.get("mentions")) : before.mentions;
        options(id, whispers, mentions);
        plugin.suiteAudit(actor, "Chat-Ruhe-Optionen geändert", name + " • Flüstern " + (whispers ? "stumm" : "an") + " • Erwähnungen " + (mentions ? "stumm" : "an"));
      }
      default -> throw new IllegalArgumentException("Unbekannte Aktion");
    }
    Map<String, Object> result = data(true);
    result.put("notice", operation.equals("unmute") ? name + " sieht den Chat wieder." : operation.equals("mute") ? "Chat-Ruhe für " + name + " gesetzt." : "Optionen für " + name + " gespeichert.");
    return result;
  }

  private void staffNotice(Player p, boolean muted) {
    muteEffect(p, muted);
    State s = state(p.getUniqueId());
    Component line = prefix().append(Component.text(muted
        ? t(p, "Ein Teammitglied hat deinen Chat auf deinen Wunsch stummgeschaltet (" + remaining(p, s) + "). ", "A staff member muted your chat for you (" + remaining(p, s) + "). ")
        : t(p, "Ein Teammitglied hat deinen Chat wieder eingeschaltet. ", "A staff member turned your chat back on. "), muted ? NamedTextColor.GOLD : NamedTextColor.GREEN));
    line = line.append(plugin.isBedrockClient(p) ? Component.text(t(p, "Verwalten: /chat", "Manage: /chat"), NamedTextColor.YELLOW)
        : commandHint(p, t(p, "Verwalten", "Manage"), "/chat", t(p, "Chat-Ruhe öffnen", "Open chat break"), NamedTextColor.AQUA));
    deliver(p, line);
  }

  boolean route(AppApiManager api, HttpExchange x, AppApiSecurity.Key key, String path, String method, long rev) throws Exception {
    if (!path.equals("/v1/chat-silence")) return false;
    api.requirePermission(key, VIEW);
    boolean manage = api.allows(key, MANAGE) && AppApiSecurity.permitted(key, "manage");
    if (method.equals("GET")) AppApiManager.send(x, 200, api.onMain(rev, () -> data(manage)));
    else if (method.equals("POST")) {
      if (!manage) throw new AppApiManager.ApiError(403, "Ändern benötigt das Recht " + MANAGE);
      Map<String, Object> body = AppApiManager.body(x, 4096);
      if (!Boolean.TRUE.equals(body.get("confirmed"))) throw new IllegalArgumentException("Bestätigung fehlt");
      String actor = api.actorName(key);
      AppApiManager.send(x, 200, api.onMain(rev, () -> apply(actor, body)));
    } else throw new AppApiManager.ApiError(405, "Methode nicht erlaubt");
    return true;
  }

  // ------------------------------------------------------------------ storage

  private void load() throws IOException {
    if (!Files.isRegularFile(file)) return;
    YamlConfiguration y = new YamlConfiguration();
    try { y.load(file.toFile()); }
    catch (org.bukkit.configuration.InvalidConfigurationException e) { throw new IOException("chat-silence.yml unlesbar; Datei bleibt unverändert", e); }
    var section = y.getConfigurationSection("players");
    if (section == null) return;
    for (String key : section.getKeys(false)) {
      UUID id;
      try { id = UUID.fromString(key); } catch (IllegalArgumentException invalid) { continue; }
      String root = "players." + key + ".";
      State s = new State(y.getBoolean(root + "chat", false), y.getLong(root + "until", 0), y.getBoolean(root + "whispers", false), y.getBoolean(root + "mentions", false),
          y.getString(root + "name", ""), y.getString(root + "by", ""), y.getLong(root + "since", 0));
      if (s.worthKeeping()) states.put(id, s);
    }
  }

  private synchronized void save() {
    YamlConfiguration y = new YamlConfiguration();
    y.set("schema-version", 1);
    for (var e : states.entrySet()) {
      String root = "players." + e.getKey() + ".";
      State s = e.getValue();
      y.set(root + "name", s.name);
      y.set(root + "chat", s.chat);
      y.set(root + "until", s.until);
      y.set(root + "whispers", s.whispers);
      y.set(root + "mentions", s.mentions);
      y.set(root + "by", s.by);
      y.set(root + "since", s.since);
    }
    try {
      Files.createDirectories(file.getParent());
      Path temp = file.resolveSibling(file.getFileName() + ".tmp");
      Files.writeString(temp, y.saveToString());
      try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
      catch (AtomicMoveNotSupportedException e) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
    } catch (IOException e) {
      plugin.getLogger().warning("Chat-Ruhe konnte nicht gespeichert werden: " + e.getMessage());
    }
  }
}
