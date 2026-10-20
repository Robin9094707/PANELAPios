package eu.simplexsmp.adminsuite;

import com.destroystokyo.paper.event.player.PlayerConnectionCloseEvent;
import java.io.File;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.scheduler.BukkitTask;

/** Observations rather than invented client/launcher diagnoses. No addresses are persisted. */
final class ConnectionDiagnostics implements Listener {
  record Entry(
      long at, UUID uuid, String name, String event, String category, String reason, int ping) {}

  record Summary(int minimum, int maximum, double mean, int jitter, int count) {}

  private final SimplexAdminSuite plugin;
  private final File file;
  private final Deque<Entry> entries = new ArrayDeque<>();
  private final Map<UUID, Player> sessions = new HashMap<>();
  private final Set<Player> kicked = Collections.newSetFromMap(new IdentityHashMap<>());
  private final Map<UUID, BukkitTask> tests = new HashMap<>();
  private final ExecutorService writer =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "Simplex-Connection-Log");
            t.setDaemon(true);
            return t;
          });
  private final AtomicBoolean dirty = new AtomicBoolean();
  private BukkitTask flush;
  private volatile boolean stopping;

  ConnectionDiagnostics(SimplexAdminSuite plugin) {
    this.plugin = plugin;
    file = new File(plugin.getDataFolder(), "connection-history.yml");
    load();
  }

  void start() {
    registerModernLogin();
    for (Player p : Bukkit.getOnlinePlayers()) sessions.put(p.getUniqueId(), p);
    flush = Bukkit.getScheduler().runTaskTimer(plugin, this::flush, 100L, 100L);
  }

  void stop() {
    stopping = true;
    if (flush != null) flush.cancel();
    for (BukkitTask t : tests.values()) t.cancel();
    tests.clear();
    dirty.set(true);
    flush();
    writer.shutdown();
    try {
      if (!writer.awaitTermination(5, TimeUnit.SECONDS))
        plugin.getLogger().warning("Connection log write is still finishing.");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** Optional registration keeps the legacy events available on older Paper releases. */
  @SuppressWarnings("unchecked")
  private void registerModernLogin() {
    try {
      Class<? extends Event> type =
          (Class<? extends Event>)
              Class.forName("io.papermc.paper.event.connection.PlayerConnectionValidateLoginEvent");
      Bukkit.getPluginManager()
          .registerEvent(
              type, this, EventPriority.MONITOR, (listener, event) -> modernLogin(event), plugin);
    } catch (ClassNotFoundException ignored) {
      /* Legacy prelogin/login handlers remain active. */
    }
  }

  void modernLogin(Event event) {
    try {
      if (Boolean.TRUE.equals(event.getClass().getMethod("isAllowed").invoke(event))) return;
      Object connection = event.getClass().getMethod("getConnection").invoke(event), profile = null;
      for (String typeName :
          List.of(
              "io.papermc.paper.connection.PlayerLoginConnection",
              "io.papermc.paper.connection.PlayerConfigurationConnection")) {
        Class<?> type = Class.forName(typeName);
        if (!type.isInstance(connection)) continue;
        for (String method :
            List.of(
                "getAuthenticatedProfile", "getUnsafeProfile", "getProfile", "getPlayerProfile")) {
          try {
            profile = type.getMethod(method).invoke(connection);
          } catch (NoSuchMethodException ignored) {
          }
          if (profile != null) break;
        }
        if (profile != null) break;
      }
      if (profile instanceof com.destroystokyo.paper.profile.PlayerProfile identity)
        add(
            identity.getId(),
            identity.getName(),
            "VALIDATION_DENIED",
            plain(
                (net.kyori.adventure.text.Component)
                    event.getClass().getMethod("getKickMessage").invoke(event)),
            -1);
    } catch (ReflectiveOperationException e) {
      plugin
          .getLogger()
          .fine("Modern login diagnostics unavailable: " + e.getClass().getSimpleName());
    }
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void prelogin(AsyncPlayerPreLoginEvent e) {
    if (e.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED)
      add(
          e.getUniqueId(),
          e.getName(),
          "PRELOGIN_DENIED",
          e.getLoginResult().name() + ": " + e.getKickMessage(),
          -1);
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void login(PlayerLoginEvent e) {
    if (e.getResult() != PlayerLoginEvent.Result.ALLOWED)
      add(
          e.getPlayer().getUniqueId(),
          e.getPlayer().getName(),
          "LOGIN_DENIED",
          e.getResult().name() + ": " + e.getKickMessage(),
          -1);
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void join(PlayerJoinEvent e) {
    Player p = e.getPlayer();
    sessions.put(p.getUniqueId(), p);
    add(
        p.getUniqueId(),
        p.getName(),
        "JOIN",
        plugin.isBedrockClient(p)
            ? "Bedrock / Geyser connection admitted"
            : "Java connection admitted",
        ping(p));
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void kick(PlayerKickEvent e) {
    Player p = e.getPlayer();
    kicked.add(p);
    add(
        p.getUniqueId(),
        p.getName(),
        "KICK",
        e.getCause().name() + ": " + plain(e.reason()),
        ping(p));
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void quit(PlayerQuitEvent e) {
    Player p = e.getPlayer();
    boolean hadKick = kicked.remove(p);
    if (sessions.get(p.getUniqueId()) == p) {
      sessions.remove(p.getUniqueId());
      BukkitTask t = tests.remove(p.getUniqueId());
      if (t != null) t.cancel();
    }
    if (!hadKick)
      add(
          p.getUniqueId(),
          p.getName(),
          "DISCONNECT",
          e.getReason().name() + " (no detailed client-side cause exposed by server)",
          ping(p));
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void close(PlayerConnectionCloseEvent e) {
    add(
        e.getPlayerUniqueId(),
        e.getPlayerName(),
        "CONNECTION_CLOSED",
        "Network connection closed; this event supplies no detailed reason",
        -1);
  }

  private static String plain(net.kyori.adventure.text.Component c) {
    return c == null ? "" : PlainTextComponentSerializer.plainText().serialize(c);
  }

  static int ping(Player p) {
    try {
      return p.getPing();
    } catch (RuntimeException e) {
      return -1;
    }
  }

  static String category(String reason) {
    String s = reason.toLowerCase(Locale.ROOT);
    if (s.contains("outdated")
        || s.contains("too old")
        || s.contains("too new")
        || s.contains("incompatible")
        || s.contains("unsupported protocol")
        || s.contains("veraltet")
        || s.contains("version mismatch")) return "VERSION";
    if (s.contains("timeout") || s.contains("timed out") || s.contains("time out"))
      return "TIMEOUT";
    if (s.contains("whitelist")
        || s.contains("white-listed")
        || s.contains("not allowed")
        || s.contains("not permitted")
        || s.contains("nicht erlaubt")
        || s.contains("ban")
        || s.contains("kicked_banned")) return "ACCESS";
    if (s.contains("mod")
        || s.contains("launcher")
        || s.contains("client") && !s.contains("client-side")) return "CLIENT_POLICY";
    if (s.contains("duplicate") || s.contains("logged in from") || s.contains("already connected"))
      return "DUPLICATE_LOGIN";
    if (s.contains("authentication")
        || s.contains("verify username")
        || s.contains("invalid session")) return "AUTHENTICATION";
    if (s.contains("server_full") || s.contains("server is full") || s.contains("maintenance"))
      return "SERVER_AVAILABILITY";
    if (s.contains("packet")
        || s.contains("connection reset")
        || s.contains("disconnected")
        || s.contains("network")) return "CONNECTION";
    return "OTHER";
  }

  synchronized void add(UUID uuid, String name, String event, String reason, int ping) {
    if (stopping) return;
    entries.addLast(
        new Entry(
            System.currentTimeMillis(),
            uuid,
            clean(name, 64),
            event,
            category(reason),
            clean(reason, 700),
            ping));
    prune();
    dirty.set(true);
  }

  private synchronized void prune() {
    long cutoff = System.currentTimeMillis() - 30L * 86400000;
    while (!entries.isEmpty() && (entries.size() > 5000 || entries.peekFirst().at < cutoff))
      entries.removeFirst();
  }

  synchronized List<Entry> history(String query) {
    prune();
    List<Entry> newest = new ArrayList<>(entries);
    Collections.reverse(newest);
    return newest.stream()
        .filter(
            e ->
                e.name.equalsIgnoreCase(query)
                    || e.uuid != null && e.uuid.toString().equalsIgnoreCase(query))
        .toList();
  }

  private synchronized List<Entry> snapshot() {
    return List.copyOf(entries);
  }

  private void load() {
    if (!file.isFile()) return;
    YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
    for (Map<?, ?> m : y.getMapList("events"))
      try {
        String id = Objects.toString(m.get("uuid"), "");
        entries.addLast(
            new Entry(
                Long.parseLong(m.get("at").toString()),
                id.isBlank() ? null : UUID.fromString(id),
                Objects.toString(m.get("name"), ""),
                Objects.toString(m.get("event"), ""),
                Objects.toString(m.get("category"), "OTHER"),
                Objects.toString(m.get("reason"), ""),
                Integer.parseInt(Objects.toString(m.get("ping"), "-1"))));
      } catch (RuntimeException ignored) {
      }
    prune();
  }

  private void flush() {
    if (!dirty.getAndSet(false)) return;
    List<Entry> copy = snapshot();
    try {
      writer.execute(
          () -> {
            try {
              YamlConfiguration y = new YamlConfiguration();
              y.set("schema-version", 1);
              List<Map<String, Object>> data = new ArrayList<>();
              for (Entry e : copy) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("at", e.at);
                m.put("uuid", e.uuid == null ? "" : e.uuid.toString());
                m.put("name", e.name);
                m.put("event", e.event);
                m.put("category", e.category);
                m.put("reason", e.reason);
                m.put("ping", e.ping);
                data.add(m);
              }
              y.set("events", data);
              SimplexAdminSuite.saveAtomic(y, file);
            } catch (Exception e) {
              dirty.set(true);
              plugin
                  .getLogger()
                  .warning("Connection history could not be saved: " + e.getMessage());
            }
          });
    } catch (RejectedExecutionException e) {
      dirty.set(true);
    }
  }

  private boolean allowed(CommandSender sender) {
    return plugin.has(sender, "simplexadminsuite.admin")
        && plugin.has(sender, "simplexadminsuite.connection")
        && (!(sender instanceof Player p) || plugin.adminSessionUsable(p));
  }

  private boolean de(CommandSender s) {
    return s instanceof Player p && plugin.community != null && plugin.community.german(p);
  }

  private String tr(CommandSender s, String german, String english) {
    return de(s) ? german : english;
  }

  boolean command(CommandSender sender, String[] args) {
    if (!allowed(sender)) {
      plugin.menuError(
          sender,
          "§c"
              + tr(
                  sender,
                  "Keine Berechtigung: simplexadminsuite.connection",
                  "Missing permission: simplexadminsuite.connection"));
      return true;
    }
    if (args.length < 1 || args.length > 3) {
      usage(sender);
      return true;
    }
    String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "status";
    if (!Set.of("status", "test", "history").contains(action)
        || args.length == 3 && !action.equals("history")) {
      usage(sender);
      return true;
    }
    if (action.equals("history")) {
      int page = 1;
      try {
        if (args.length == 3) page = Integer.parseInt(args[2]);
        if (page < 1) throw new IllegalArgumentException();
      } catch (IllegalArgumentException e) {
        usage(sender);
        return true;
      }
      showHistory(sender, args[0], page);
      return true;
    }
    Player target = resolveOnline(args[0]);
    if (target == null) {
      sender.sendMessage(
          "§e"
              + tr(
                  sender,
                  "Spieler offline. Gespeicherter Verlauf:",
                  "Player offline. Retained history:"));
      showHistory(sender, args[0], 1);
      return true;
    }
    if (action.equals("test")) {
      test(sender, target);
      return true;
    }
    status(sender, target);
    return true;
  }

  private void usage(CommandSender s) {
    s.sendMessage("§b/connection <player> [status|test|history] [page]");
  }

  private Player resolveOnline(String query) {
    Player p = Bukkit.getPlayerExact(query);
    if (p != null) return p;
    try {
      return Bukkit.getPlayer(UUID.fromString(query));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  List<String> complete(CommandSender sender, String[] args) {
    if (!allowed(sender)) return List.of();
    List<String> choices = new ArrayList<>();
    if (args.length == 1) {
      choices.addAll(plugin.clientSecurityPlayerNames());
      synchronized (this) {
        for (Entry e : entries) choices.add(e.name);
      }
      for (Player p : Bukkit.getOnlinePlayers()) choices.add(p.getName());
    } else if (args.length == 2) choices.addAll(List.of("status", "test", "history"));
    else if (args.length == 3 && args[1].equalsIgnoreCase("history")) {
      int pages = Math.max(1, (history(args[0]).size() + 7) / 8);
      for (int n = 1; n <= Math.min(pages, 625); n++) choices.add(Integer.toString(n));
    } else return List.of();
    String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
    return choices.stream()
        .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix))
        .distinct()
        .sorted(String.CASE_INSENSITIVE_ORDER)
        .toList();
  }

  private void status(CommandSender s, Player p) {
    boolean bedrock = plugin.isBedrockClient(p);
    double tps = tps(), mspt = mspt();
    int ping = ping(p);
    s.sendMessage("§8━━━━━━━━ §bConnection §f" + p.getName() + " §8━━━━━━━━");
    s.sendMessage(
        "§7"
            + tr(s, "Plattform: ", "Platform: ")
            + (bedrock ? "§aBedrock / Geyser" : "§bJava")
            + " §8• §7"
            + tr(s, "Ping: ", "Ping: ")
            + "§f"
            + (ping < 0 ? "—" : ping + " ms")
            + (bedrock ? " §e" + tr(s, "(Java-Seite des Proxys)", "(Java side of proxy)") : ""));
    s.sendMessage(
        String.format(
            Locale.ROOT,
            "§7TPS: §f%.2f §8• §7MSPT: §f%s",
            tps,
            mspt < 0 ? "—" : String.format(Locale.ROOT, "%.2f", mspt)));
    if (!bedrock) {
      String brand = p.getClientBrandName();
      s.sendMessage(
          "§7Client: §f"
              + (brand == null ? tr(s, "noch unbekannt", "not reported yet") : clean(brand, 100))
              + " §8• §7"
              + tr(s, "Protokoll: ", "Protocol: ")
              + protocol(p));
      s.sendMessage(
          "§7"
              + tr(s, "Sichtweite (Client/Server): ", "View distance (client/server): ")
              + p.getClientViewDistance()
              + " / "
              + p.getViewDistance());
    }
    for (String hint : assessment(ping, tps, mspt, de(s))) s.sendMessage("§e• " + hint);
    s.sendMessage(
        "§7"
            + tr(
                s,
                "Launcher und vollständige Modliste sind serverseitig nicht zuverlässig erkennbar.",
                "Launcher and complete installed mod list cannot be reliably identified by the"
                    + " server."));
    List<Entry> log = history(p.getName());
    log.stream()
        .filter(e -> !Set.of("JOIN", "CONNECTION_CLOSED").contains(e.event))
        .findFirst()
        .ifPresent(
            e ->
                s.sendMessage(
                    "§7"
                        + tr(s, "Letztes Problem: ", "Last recorded issue: ")
                        + "§f"
                        + e.category
                        + " §8• §7"
                        + e.reason));
    s.sendMessage(
        "§7/connection " + p.getName() + " test §8• §7/connection " + p.getName() + " history");
  }

  static List<String> assessment(int ping, double tps, double mspt, boolean de) {
    List<String> h = new ArrayList<>();
    if (tps >= 0 && tps < 18 || mspt > 50)
      h.add(
          de
              ? "Serverlast auffällig: niedrige TPS oder Tickzeit über 50 ms. Das kann alle Spieler"
                  + " betreffen."
              : "Server load elevated: low TPS or tick time above 50 ms; all players may be"
                  + " affected.");
    if (ping >= 200)
      h.add(
          de
              ? "Hoher Verbindungs-Ping. WLAN, Mobilfunk, Routing oder Proxy können beteiligt sein;"
                  + " Ursache nicht bewiesen."
              : "High connection latency. Wi-Fi, mobile network, routing or proxy may contribute;"
                  + " cause not proven.");
    if (ping < 0) h.add(de ? "Keine Ping-Messung verfügbar." : "Ping reading unavailable.");
    if (h.isEmpty())
      h.add(
          de
              ? "Aktuell keine Auffälligkeit in Ping/TPS. Client-FPS und Paketverlust werden hier"
                  + " nicht gemessen."
              : "No current ping/TPS anomaly. Client FPS and packet loss are not measured here.");
    return h;
  }

  private String protocol(Player p) {
    try {
      Object n = p.getClass().getMethod("getProtocolVersion").invoke(p);
      return n.toString();
    } catch (Exception ignored) {
    }
    SimplexAdminSuite.PlayerProfile profile = plugin.clientSecurityProfile(p.getUniqueId());
    return profile != null && profile.connectionProtocolVersion() > 0
        ? profile.connectionProtocolVersion()
            + " ("
            + tr(p, "zuletzt gespeichert", "last recorded")
            + ")"
        : "—";
  }

  private void test(CommandSender s, Player p) {
    if (plugin.isBedrockClient(p)) {
      s.sendMessage(
          "§e"
              + tr(
                  s,
                  "Dieser Ping-Test ist nur für Java. Bedrock-Proxy-Status:",
                  "This ping test is Java-only. Bedrock proxy status:"));
      status(s, p);
      return;
    }
    if (tests.size() >= 8 || tests.containsKey(p.getUniqueId())) {
      plugin.menuError(
          s,
          "§e"
              + tr(
                  s,
                  "Ein Test läuft bereits oder alle 8 Testplätze sind belegt.",
                  "A test is already running or all 8 test slots are occupied."));
      return;
    }
    s.sendMessage(
        "§b"
            + tr(s, "Verbindungstest gestartet: ", "Connection test started: ")
            + p.getName()
            + tr(
                s,
                " • 10 Messpunkte über ca. 10 Sekunden.",
                " • 10 samples over approximately 10 seconds."));
    List<Integer> values = new ArrayList<>();
    Player actor = s instanceof Player x ? x : null;
    BukkitTask[] task = {null};
    task[0] =
        Bukkit.getScheduler()
            .runTaskTimer(
                plugin,
                () -> {
                  if (Bukkit.getPlayer(p.getUniqueId()) != p
                      || actor != null
                          && (Bukkit.getPlayer(actor.getUniqueId()) != actor
                              || !allowed(s)
                              || !plugin.adminSessionUsable(actor))) {
                    task[0].cancel();
                    tests.remove(p.getUniqueId());
                    if (actor == null || actor.isOnline())
                      s.sendMessage(
                          "§e"
                              + tr(
                                  s,
                                  "Verbindungstest beendet: Sitzung oder Berechtigung hat sich"
                                      + " geändert.",
                                  "Connection test stopped: session or authorization changed."));
                    return;
                  }
                  values.add(ping(p));
                  if (values.size() < 10) return;
                  task[0].cancel();
                  tests.remove(p.getUniqueId());
                  Summary summary = summarize(values);
                  s.sendMessage(
                      String.format(
                          Locale.ROOT,
                          "§b%s §f%s §8• §7min/avg/max: §f%d/%.1f/%d ms §8• §7jitter: §f%d ms §8•"
                              + " §7n: §f%d",
                          tr(s, "Testergebnis", "Test result"),
                          p.getName(),
                          summary.minimum,
                          summary.mean,
                          summary.maximum,
                          summary.jitter,
                          summary.count));
                  s.sendMessage(
                      "§7"
                          + tr(
                              s,
                              "Messwerte stammen aus Minecraft-Keepalive. Wiederholte Werte sind"
                                  + " möglich; kein Paketverlust-Test.",
                              "Readings use Minecraft keepalive. Repeated values are possible; this"
                                  + " is not a packet-loss test."));
                  status(s, p);
                },
                1L,
                20L);
    tests.put(p.getUniqueId(), task[0]);
  }

  static Summary summarize(List<Integer> all) {
    List<Integer> values = all.stream().filter(n -> n >= 0).toList();
    if (values.isEmpty()) return new Summary(-1, -1, -1, 0, 0);
    int jitter = 0;
    for (int i = 1; i < values.size(); i++)
      jitter = Math.max(jitter, Math.abs(values.get(i) - values.get(i - 1)));
    return new Summary(
        Collections.min(values),
        Collections.max(values),
        values.stream().mapToInt(n -> n).average().orElse(-1),
        jitter,
        values.size());
  }

  private void showHistory(CommandSender s, String query, int page) {
    List<Entry> log = history(query);
    int pages = Math.max(1, (log.size() + 7) / 8);
    if (page > pages) {
      s.sendMessage(
          "§e" + tr(s, "Seite nicht vorhanden. Seiten: ", "Page unavailable. Pages: ") + pages);
      return;
    }
    s.sendMessage("§bConnection history §f" + clean(query, 64) + " §7(" + page + "/" + pages + ")");
    if (log.isEmpty())
      s.sendMessage(
          "§7"
              + tr(
                  s,
                  "Noch keine Ereignisse gespeichert. Das Protokoll beginnt mit diesem Update.",
                  "No events recorded yet. History starts with this update."));
    DateTimeFormatter fmt =
        DateTimeFormatter.ofPattern("dd.MM HH:mm:ss").withZone(ZoneId.of("Europe/Berlin"));
    for (Entry e : log.subList((page - 1) * 8, Math.min(log.size(), page * 8))) {
      s.sendMessage(
          "§8"
              + fmt.format(Instant.ofEpochMilli(e.at))
              + " §b"
              + e.event
              + " §7["
              + e.category
              + "] "
              + (e.ping < 0 ? "" : e.ping + " ms"));
      s.sendMessage("§7  " + e.reason);
    }
    s.sendMessage(
        "§7"
            + tr(
                s,
                "Aufbewahrung: bis 30 Tage / 5000 Ereignisse. Vor Paper abgewiesene Verbindungen"
                    + " können fehlen.",
                "Retention: up to 30 days / 5000 events. Connections rejected before Paper may be"
                    + " absent."));
  }

  private double tps() {
    try {
      return Math.min(20, Bukkit.getTPS()[0]);
    } catch (RuntimeException e) {
      return -1;
    }
  }

  private double mspt() {
    try {
      return Bukkit.getAverageTickTime();
    } catch (RuntimeException e) {
      return -1;
    }
  }

  private static String clean(String s, int max) {
    return PlayerCommunityManager.clean(MenuStyle.plain(s), max).replace('§', ' ');
  }
}
