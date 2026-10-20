package eu.simplexsmp.adminsuite;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

/** Player-only projections, native forms, bounded feedback and expiring announcements. */
final class PlayerCommunityManager implements Listener {
  record Preferences(String language, boolean scoreboard, boolean joins) {
    static Preferences defaults() {
      return new Preferences("auto", true, true);
    }
  }

  record History(
      String id,
      UUID owner,
      long at,
      String action,
      String duration,
      String reason,
      String source) {}

  record Feedback(
      String id, UUID owner, String name, long at, String text, String reply, long answeredAt) {}

  static final class Campaign {
    final String id, en, de;
    final long created, expires, interval;
    boolean enabled = true;
    final Map<UUID, Long> delivered = new HashMap<>();

    Campaign(String id, String en, String de, long created, long expires, long interval) {
      this.id = id;
      this.en = en;
      this.de = de;
      this.created = created;
      this.expires = expires;
      this.interval = interval;
    }

    boolean due(UUID id, long now) {
      return enabled
          && now < expires
          && (interval > 0
              ? now - delivered.getOrDefault(id, 0L) >= interval
              : !delivered.containsKey(id));
    }
  }

  record Button(String title, String detail, Material icon, Runnable action) {}

  record Prompt(long expires, Consumer<String> action) {}

  static final class Holder implements InventoryHolder {
    final UUID owner;
    final boolean admin;
    final Map<Integer, Runnable> actions = new HashMap<>();
    Inventory inventory;

    Holder(UUID id, boolean admin) {
      owner = id;
      this.admin = admin;
    }

    public Inventory getInventory() {
      return inventory;
    }
  }

  private final SimplexAdminSuite plugin;
  private final Path file;
  private final ConcurrentMap<UUID, Preferences> preferences = new ConcurrentHashMap<>();
  private final Set<UUID> migrated = new HashSet<>();
  private final Map<String, History> history = new LinkedHashMap<>();
  private final Map<String, Feedback> feedback = new LinkedHashMap<>();
  private final Map<String, Campaign> campaigns = new LinkedHashMap<>();
  private final ConcurrentMap<UUID, Prompt> prompts = new ConcurrentHashMap<>();
  private final Map<UUID, Long> cooldowns = new HashMap<>(),
      admitted = new HashMap<>(),
      generations = new HashMap<>();
  private final java.util.concurrent.ExecutorService io =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "Simplex-Community-State");
            t.setDaemon(true);
            return t;
          });
  private final AtomicBoolean writing = new AtomicBoolean();
  private volatile boolean stopped;
  private boolean dirty;
  private BukkitTask task;

  PlayerCommunityManager(SimplexAdminSuite p) {
    plugin = p;
    file = p.getDataFolder().toPath().resolve("player-community.yml");
    load();
  }

  void start() {
    task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20, 20);
  }

  void stop() {
    stopped = true;
    if (task != null) task.cancel();
    prompts.clear();
    String snapshot = snapshot();
    io.execute(() -> write(snapshot));
    io.shutdown();
    try {
      if (!io.awaitTermination(5, TimeUnit.SECONDS))
        plugin.getLogger().warning("Spielerprofil-Speicherung läuft noch.");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  boolean german(Player p) {
    return p != null && german(p.getUniqueId(), liveLocale(p));
  }

  boolean german(UUID id, String locale) {
    String language = preferences.getOrDefault(id, Preferences.defaults()).language;
    return language.equals("de")
        || language.equals("auto")
            && locale != null
            && locale.toLowerCase(Locale.ROOT).startsWith("de");
  }

  private String liveLocale(Player p) {
    String saved = plugin.profileLocale(p.getUniqueId());
    if (plugin.isBedrockClient(p) && !saved.isBlank()) return saved;
    try {
      String live = p.getLocale();
      if (live != null && !live.isBlank()) return live;
    } catch (Throwable ignored) {
    }
    return saved;
  }

  boolean scoreboard(Player p) {
    return preferences.getOrDefault(p.getUniqueId(), Preferences.defaults()).scoreboard;
  }

  boolean joinMessages(Player p) {
    return preferences.getOrDefault(p.getUniqueId(), Preferences.defaults()).joins;
  }

  private String t(Player p, String de, String en) {
    return german(p) ? de : en;
  }

  private Button b(String title, String detail, Material m, Runnable action) {
    return new Button(title, detail, m, action);
  }

  private boolean admin(Player p) {
    return p != null
        && plugin.adminSessionUsable(p)
        && plugin.has(p, "simplexadminsuite.admin")
        && plugin.has(p, "simplexadminsuite.community.admin");
  }

  boolean command(CommandSender sender, String root, String[] args) {
    if (!(sender instanceof Player p)) {
      sender.sendMessage("Use /profil in Minecraft.");
      return true;
    }
    if (root.equals("feedback") && args.length > 0) {
      submit(p, String.join(" ", args));
      return true;
    }
    String action =
        root.equals("feedback")
            ? "feedback"
            : args.length == 0 ? "profile" : args[0].toLowerCase(Locale.ROOT);
    switch (action) {
      case "settings", "einstellungen" -> settings(p);
      case "history", "verlauf" -> history(p);
      case "stats", "statistiken" -> disk(p, "stats");
      case "feedback" -> inbox(p);
      default -> open(p);
    }
    return true;
  }

  void open(Player p) {
    List<Button> rows = new ArrayList<>();
    for (String line : plugin.ownProfileLines(p)) rows.add(b(line, "", Material.PAPER, () -> {}));
    rows.add(b(t(p,"Simplex-Shop","Simplex Shop"),t(p,"Mit deinen Punkten einkaufen","Spend your points"),Material.CHEST,()->plugin.shop.open(p,0)));
    rows.add(b(t(p,"Teamnachrichten","Staff messages"),t(p,"Nachrichten lesen und bestätigen","Read and acknowledge messages"),Material.WRITABLE_BOOK,()->plugin.deliveries.inbox(p)));
    rows.add(
        b(
            t(p, "Einstellungen", "Settings"),
            t(
                p,
                "Sprache • Scoreboard • Beitrittsmeldungen",
                "Language • scoreboard • join messages"),
            Material.COMPARATOR,
            () -> settings(p)));
    rows.add(
        b(
            t(p, "Verwarnungen & Strafverlauf", "Warnings & punishment history"),
            t(p, "Nur dein eigenes Profil", "Your own profile only"),
            Material.SHIELD,
            () -> history(p)));
    rows.add(
        b(
            t(p, "Datei-Erstjoin & NBT-Werte", "File first join & NBT data"),
            t(p, "Gespeicherte Werte schreibgeschützt ansehen", "Read your saved values"),
            Material.WRITABLE_BOOK,
            () -> disk(p, "nbt")));
    rows.add(
        b(
            t(p, "Minecraft-Statistiken", "Minecraft statistics"),
            t(p, "Alle gespeicherten Zähler", "All saved counters"),
            Material.BOOK,
            () -> disk(p, "stats")));
    rows.add(
        b(
            t(p, "Feedback & Antworten", "Feedback & replies"),
            t(p, "Nachrichten an das Serverteam", "Messages to the server team"),
            Material.WRITABLE_BOOK,
            () -> inbox(p)));
    rows.add(
        b(
            t(p, "Community-Statistiken", "Community statistics"),
            "Top 10",
            Material.MAP,
            () -> rankings(p)));
    show(p, t(p, "Mein Simplex-Profil", "My Simplex profile"), rows, 0, false);
  }

  private void settings(Player p) {
    Preferences pref = preferences.getOrDefault(p.getUniqueId(), Preferences.defaults());
    show(
        p,
        t(p, "Profil-Einstellungen", "Profile settings"),
        List.of(
            b(
                t(p, "Sprache: ", "Language: ") + pref.language,
                t(p, "Auto → Deutsch → Englisch", "Auto → German → English"),
                Material.BOOK,
                () -> {
                  String next =
                      switch (pref.language) {
                        case "auto" -> "de";
                        case "de" -> "en";
                        default -> "auto";
                      };
                  set(p, new Preferences(next, pref.scoreboard, pref.joins));
                  settings(p);
                }),
            b(
                t(p, "Scoreboard: ", "Scoreboard: ")
                    + (pref.scoreboard ? t(p, "AN", "ON") : t(p, "AUS", "OFF")),
                t(p, "Aus: nur der Titel Simplex SMP", "Off: only the Simplex SMP title"),
                Material.ITEM_FRAME,
                () -> {
                  set(p, new Preferences(pref.language, !pref.scoreboard, pref.joins));
                  plugin.refreshPlayerBoard(p);
                  settings(p);
                }),
            b(
                t(p, "Beitrittsmeldungen: ", "Join messages: ")
                    + (pref.joins ? t(p, "AN", "ON") : t(p, "AUS", "OFF")),
                t(p, "Beitreten und Verlassen im Chat", "Join and leave messages in chat"),
                Material.OAK_DOOR,
                () -> {
                  set(p, new Preferences(pref.language, pref.scoreboard, !pref.joins));
                  settings(p);
                }),
            b(
                t(p, "Zurück", "Back"),
                t(
                    p,
                    "Einstellungen werden automatisch gespeichert",
                    "Settings are saved automatically"),
                Material.ARROW,
                () -> open(p))),
        0,
        false);
  }

  private void set(Player p, Preferences pref) {
    preferences.put(p.getUniqueId(), pref);
    dirty = true;
    p.sendMessage(
        "§a"
            + t(
                p,
                "Einstellungen übernommen. Speicherung läuft automatisch.",
                "Settings applied. Saving automatically."));
  }

  private void history(Player p) {
    List<Button> rows = new ArrayList<>();
    for (String warning : plugin.ownWarnings(p.getUniqueId()))
      rows.add(
          b(
              t(p, "Verwarnung", "Warning"),
              warning,
              Material.YELLOW_DYE,
              () -> p.sendMessage("§e" + warning)));
    for (History h :
        history.values().stream()
            .filter(h -> h.owner.equals(p.getUniqueId()))
            .sorted(Comparator.comparingLong(History::at).reversed())
            .toList())
      rows.add(
          b(
              actionName(p, h.action)
                  + " • "
                  + (h.at == 0 ? t(p, "Alteintrag", "Legacy record") : plugin.profileDate(h.at)),
              h.reason + " | " + h.duration + " | " + h.source,
              Material.SHIELD,
              () -> p.sendMessage("§e" + h.reason)));
    if (rows.isEmpty())
      rows.add(b(t(p, "Keine Einträge", "No entries"), "", Material.LIME_DYE, () -> {}));
    rows.add(b(t(p, "Zurück", "Back"), "", Material.ARROW, () -> open(p)));
    show(p, t(p, "Mein Strafverlauf", "My punishment history"), rows, 0, false);
  }

  private String actionName(Player p, String action) {
    return switch (action) {
      case "ban" -> t(p, "Dauerhafte Sperre", "Permanent ban");
      case "tempban" -> t(p, "Zeitweise Sperre", "Temporary ban");
      case "mute" -> t(p, "Stummschaltung", "Mute");
      case "tempmute" -> t(p, "Zeitweise Stummschaltung", "Temporary mute");
      case "unban" -> t(p, "Entsperrt", "Unbanned");
      case "unmute" -> t(p, "Stummschaltung aufgehoben", "Unmuted");
      case "kick" -> t(p, "Vom Server entfernt", "Kick");
      default -> action;
    };
  }

  void recordPunishment(UUID owner, String action, String duration, String reason, String source) {
    if (owner == null) return;
    String id = UUID.randomUUID().toString();
    history.put(
        id,
        new History(
            id,
            owner,
            System.currentTimeMillis(),
            clean(action, 24),
            clean(duration, 32),
            clean(reason, 500),
            source));
    trimHistory(owner);
    dirty = true;
  }

  private void trimHistory(UUID owner) {
    int max =
        Math.max(
            10,
            Math.min(1000, plugin.getConfig().getInt("player-community.history-per-player", 200)));
    List<History> rows = history.values().stream().filter(h -> h.owner.equals(owner)).toList();
    for (int n = 0; n < rows.size() - max; n++) history.remove(rows.get(n).id);
  }

  void importLegacy(
      UUID owner,
      List<String> actions,
      long banUntil,
      String banReason,
      long muteUntil,
      String muteReason) {
    if (!migrated.add(owner)) return;
    dirty = true;
    // Only known punishment lines are decoded; no raw admin/alt logs are ever projected.
    for (String line : actions) {
      java.util.regex.Matcher m =
          java.util.regex.Pattern.compile(
                  "(?:Strafaktion durch |Bestätigte Menüaktion durch |Bedrock-Menü-Strafaktion"
                      + " durch )[^:]+: (ban|tempban|mute|tempmute|unban|unmute|kick)\\s*(.*)")
              .matcher(line);
      if (!m.find()) continue;
      String id =
          "legacy-"
              + UUID.nameUUIDFromBytes(
                  (owner + "|" + line).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      if (history.containsKey(id)) continue;
      String reason = clean(m.group(2), 500);
      history.put(id, new History(id, owner, 0, m.group(1), "", reason, "Simplex legacy"));
      dirty = true;
    }
    if (banUntil > System.currentTimeMillis()
        && !history.values().stream()
            .anyMatch(h -> h.owner.equals(owner) && h.action.contains("ban"))) {
      String id = "existing-ban-" + owner;
      history.put(
          id,
          new History(
              id,
              owner,
              0,
              banUntil == Long.MAX_VALUE ? "ban" : "tempban",
              "",
              clean(banReason, 500),
              "Simplex"));
    }
    if (muteUntil > System.currentTimeMillis()
        && !history.values().stream()
            .anyMatch(h -> h.owner.equals(owner) && h.action.contains("mute"))) {
      String id = "existing-mute-" + owner;
      history.put(
          id,
          new History(
              id,
              owner,
              0,
              muteUntil == Long.MAX_VALUE ? "mute" : "tempmute",
              "",
              clean(muteReason, 500),
              "Simplex"));
    }
    trimHistory(owner);
  }

  private void disk(Player p, String section) {
    if (plugin.isBedrockClient(p)) {
      generations.merge(p.getUniqueId(), 1L, Long::sum);
      prompts.remove(p.getUniqueId());
    } else
      show(
          p,
          t(p, "Daten werden geladen", "Loading data"),
          List.of(b(t(p, "Zurück", "Back"), "", Material.ARROW, () -> open(p))),
          0,
          false);
    long generation = generations.get(p.getUniqueId());
    long form = plugin.nativeFormGeneration(p.getUniqueId());
    Inventory expected = plugin.isBedrockClient(p) ? null : p.getOpenInventory().getTopInventory();
    p.sendMessage(
        "§7" + t(p, "Deine gespeicherten Daten werden geladen …", "Loading your saved data …"));
    plugin
        .playerDataReader()
        .readOwn(
            p,
            data -> {
              if (stopped
                  || Bukkit.getPlayer(p.getUniqueId()) != p
                  || generations.getOrDefault(p.getUniqueId(), 0L) != generation
                  || plugin.isBedrockClient(p)
                      && form != plugin.nativeFormGeneration(p.getUniqueId())
                  || expected != null && p.getOpenInventory().getTopInventory() != expected) return;
              List<Button> rows = new ArrayList<>();
              Map<String, String> values = section.equals("nbt") ? data.nbt() : data.stats();
              values.forEach(
                  (key, value) ->
                      rows.add(
                          b(
                              PlayerLanguages.label(key, german(p)),
                              value,
                              Material.PAPER,
                              () ->
                                  p.sendMessage(
                                      "§e"
                                          + PlayerLanguages.label(key, german(p))
                                          + ": §f"
                                          + value))));
              if (values.isEmpty())
                rows.add(
                    b(
                        t(p, "Keine gespeicherten Daten", "No saved data"),
                        data.error().strip().equals("Read busy")
                            ? t(
                                p,
                                "Dateien werden bereits geladen. Bitte kurz warten und"
                                    + " aktualisieren.",
                                "Files are already loading. Please wait and refresh.")
                            : data.error().isBlank()
                                ? t(
                                    p,
                                    "Neue Daten erscheinen nach dem Speichern durch Minecraft.",
                                    "New data appears after Minecraft saves it.")
                                : t(
                                    p,
                                    "Datei fehlt oder konnte nicht gelesen werden.",
                                    "File missing or could not be read."),
                        Material.PAPER,
                        () -> {}));
              rows.add(
                  b(
                      t(p, "Aktualisieren", "Refresh"),
                      t(
                          p,
                          "Dateiwerte können gegenüber Live-Daten verzögert sein",
                          "File data can lag behind live values"),
                      Material.CLOCK,
                      () -> disk(p, section)));
              rows.add(b(t(p, "Zurück", "Back"), "", Material.ARROW, () -> open(p)));
              show(
                  p,
                  t(
                      p,
                      section.equals("nbt") ? "Meine NBT-Werte" : "Meine Minecraft-Statistiken",
                      section.equals("nbt") ? "My NBT data" : "My Minecraft statistics"),
                  rows,
                  0,
                  false);
            });
  }

  private void rankings(Player p) {
    List<Button> rows = new ArrayList<>();
    for (String kind : List.of("languages", "devices"))
      rows.add(
          b(
              t(
                  p,
                  kind.equals("languages") ? "Sprachen Top 10" : "Geräte Top 10",
                  kind.equals("languages") ? "Languages Top 10" : "Devices Top 10"),
              "",
              Material.MAP,
              () -> {
                List<Button> ranking = new ArrayList<>();
                for (String line : publicTop(kind, german(p)).split("\n"))
                  ranking.add(b(line, "", Material.PAPER, () -> {}));
                ranking.add(b(t(p, "Zurück", "Back"), "", Material.ARROW, () -> rankings(p)));
                show(p, "Top 10", ranking, 0, false);
              }));
    rows.add(b(t(p, "Zurück", "Back"), "", Material.ARROW, () -> open(p)));
    show(p, t(p, "Community-Statistiken", "Community statistics"), rows, 0, false);
  }

  String publicTop(String kind, boolean de) {
    Map<String, Integer> counts = new HashMap<>();
    for (var profile : plugin.expansionProfiles()) {
      String raw =
          kind.equals("languages") ? profile.localeValue() : profile.statisticsSnapshot().model();
      if (raw == null || raw.isBlank()) continue;
      String name =
          kind.equals("languages")
              ? PlayerLanguages.localeName(raw, de)
              : ServerStatistics.label(DeviceNames.display(raw, "Bedrock", de));
      counts.merge(name, 1, Integer::sum);
    }
    if (counts.isEmpty()) return de ? "Noch keine Daten" : "No data yet";
    int total = counts.values().stream().mapToInt(Integer::intValue).sum();
    StringBuilder text = new StringBuilder();
    counts.entrySet().stream()
        .sorted(
            Map.Entry.<String, Integer>comparingByValue()
                .reversed()
                .thenComparing(Map.Entry.comparingByKey()))
        .limit(10)
        .forEach(
            e ->
                text.append(e.getKey())
                    .append(" · ")
                    .append(e.getValue())
                    .append(
                        String.format(Locale.ROOT, " (%.1f%%)\n", 100D * e.getValue() / total)));
    return text.toString();
  }

  private long feedbackTtl() {
    return Math.max(
            1,
            Math.min(90, plugin.getConfig().getInt("player-community.feedback.retention-days", 7)))
        * 86400_000L;
  }

  private void inbox(Player p) {
    purge();
    List<Button> rows = new ArrayList<>();
    rows.add(
        b(
            t(p, "Feedback senden", "Send feedback"),
            t(
                p,
                "Feedback, Ideen oder Nachricht an das Team",
                "Feedback, ideas or a message to staff"),
            Material.WRITABLE_BOOK,
            () ->
                prompt(
                    p,
                    t(p, "Nachricht an das Team", "Message to staff"),
                    value -> submit(p, value),
                    false)));
    for (Feedback f :
        feedback.values().stream().filter(f -> f.owner.equals(p.getUniqueId())).toList())
      rows.add(
          b(
              plugin.profileDate(f.at),
              f.text,
              Material.PAPER,
              () -> {
                if (!owned(p, f.id)) return;
                Feedback latest = feedback.get(f.id);
                show(
                    p,
                    t(p, "Meine Nachricht", "My message"),
                    List.of(
                        b(
                            t(p, "Nachricht", "Message"),
                            latest.text,
                            Material.PAPER,
                            () -> p.sendMessage(latest.text)),
                        b(
                            t(p, "Team-Antwort", "Staff reply"),
                            latest.reply.isBlank()
                                ? t(p, "Noch keine Antwort", "No reply yet")
                                : latest.reply,
                            Material.BOOK,
                            () -> p.sendMessage(latest.reply)),
                        b(
                            t(p, "Löschen", "Delete"),
                            t(p, "Nachricht und Antwort entfernen", "Remove message and reply"),
                            Material.BARRIER,
                            () -> {
                              if (owned(p, f.id)) {
                                feedback.remove(f.id);
                                dirty = true;
                              }
                              inbox(p);
                            }),
                        b(t(p, "Zurück", "Back"), "", Material.ARROW, () -> inbox(p))),
                    0,
                    false);
              }));
    rows.add(
        b(
            t(p, "Zurück", "Back"),
            t(p, "Automatische Löschung nach ", "Automatic deletion after ")
                + feedbackTtl() / 86400_000
                + t(p, " Tagen", " days"),
            Material.ARROW,
            () -> open(p)));
    show(p, t(p, "Feedback & Antworten", "Feedback & replies"), rows, 0, false);
  }

  private boolean owned(Player p, String id) {
    Feedback f = feedback.get(id);
    return f != null && f.owner.equals(p.getUniqueId());
  }

  private void submit(Player p, String text) {
    purge();
    text = clean(text, maxText());
    long now = System.currentTimeMillis();
    if (text.isBlank()) {
      p.sendMessage(t(p, "§cBitte eine Nachricht eingeben.", "§cPlease enter a message."));
      return;
    }
    long delay =
        Math.max(
                10,
                Math.min(
                    3600,
                    plugin.getConfig().getInt("player-community.feedback.cooldown-seconds", 60)))
            * 1000L;
    if (now - cooldowns.getOrDefault(p.getUniqueId(), 0L) < delay
        || feedback.values().stream().filter(f -> f.owner.equals(p.getUniqueId())).count()
            >= Math.max(
                1,
                Math.min(
                    50, plugin.getConfig().getInt("player-community.feedback.max-per-player", 5)))
        || feedback.size() >= 1000) {
      p.sendMessage(
          t(
              p,
              "§cBitte warte oder lösche zuerst eine ältere Nachricht.",
              "§cPlease wait or delete an older message first."));
      return;
    }
    String id = UUID.randomUUID().toString();
    feedback.put(id, new Feedback(id, p.getUniqueId(), clean(p.getName(), 64), now, text, "", 0));
    cooldowns.put(p.getUniqueId(), now);
    dirty = true;
    p.sendMessage(
        t(
            p,
            "§aNachricht eingetragen. Du findest Antworten unter /profil → Feedback.",
            "§aMessage submitted. Read replies in /profile → Feedback."));
    for (Player staff : Bukkit.getOnlinePlayers())
      if (admin(staff))
        staff.sendMessage(
            "§bNeues Spielerfeedback von " + p.getName() + " • Admin-Zentrale → Community");
    inbox(p);
  }

  void admitted(Player p) {
    admitted.put(p.getUniqueId(), System.currentTimeMillis());
    Bukkit.getScheduler()
        .runTaskLater(
            plugin,
            () -> {
              if (stopped
                  || Bukkit.getPlayer(p.getUniqueId()) != p
                  || plugin.adminRuntime.blocked(p)) return;
              deliver(p);
              long replies =
                  feedback.values().stream()
                      .filter(f -> f.owner.equals(p.getUniqueId()) && !f.reply.isBlank())
                      .count();
              if (replies > 0)
                p.sendMessage(
                    "§b"
                        + t(
                            p,
                            "Du hast Team-Antworten unter /profil → Feedback.",
                            "You have staff replies in /profile → Feedback."));
            },
            1200L);
  }

  private void deliver(Player p) {
    long now = System.currentTimeMillis();
    for (Campaign c : campaigns.values())
      if (c.due(p.getUniqueId(), now)) {
        String text = german(p) && !c.de.isBlank() ? c.de : c.en;
        if (text.isBlank()) text = c.de;
        p.sendMessage(plugin.prefix() + text);
        c.delivered.put(p.getUniqueId(), now);
        dirty = true;
      }
  }

  String createCampaign(String actor, String en, String de, int hours, int minutes) {
    en = clean(en, maxText());
    de = clean(de, maxText());
    if (en.isBlank() && de.isBlank())
      throw new IllegalArgumentException("Mindestens einen Text eingeben.");
    if (campaigns.size() >= 100)
      throw new IllegalArgumentException("Maximal 100 aktive Ankündigungen.");
    if (en.isBlank()) en = de;
    long now = System.currentTimeMillis();
    String id = UUID.randomUUID().toString();
    Campaign c =
        new Campaign(
            id,
            en,
            de,
            now,
            now + Math.max(1, Math.min(720, hours)) * 3600_000L,
            minutes <= 0 ? 0 : Math.max(1, Math.min(10080, minutes)) * 60_000L);
    campaigns.put(id, c);
    dirty = true;
    plugin.suiteAudit(
        actor, "Ankündigung erstellt", id + " • Ablauf " + plugin.profileDate(c.expires));
    for (Player p : Bukkit.getOnlinePlayers())
      if (!plugin.adminRuntime.blocked(p) && !plugin.discordAdminHidden(p)) deliver(p);
    return id;
  }

  String discordCampaignAction(String actor, String action, String id) {
    purge();
    if (action.equals("list")) {
      StringBuilder text = new StringBuilder();
      for (Campaign c : campaigns.values())
        text.append(c.id)
            .append(" • ")
            .append(c.enabled ? "aktiv" : "pausiert")
            .append(" • ")
            .append(plugin.profileDate(c.expires))
            .append("\n");
      return text.isEmpty()
          ? "Keine aktiven Ankündigungen."
          : text.toString().substring(0, Math.min(3500, text.length()));
    }
    Campaign c = campaigns.get(id);
    if (c == null) return "Ankündigung nicht gefunden.";
    if (action.equals("delete")) campaigns.remove(id);
    else if (action.equals("toggle")) c.enabled = !c.enabled;
    else throw new IllegalArgumentException("Unbekannte Aktion");
    dirty = true;
    plugin.suiteAudit(actor, "Ankündigung " + action, id);
    return "Ankündigung aktualisiert.";
  }

  private void tick() {
    if (stopped) return;
    purge();
    long now = System.currentTimeMillis();
    for (Player p : Bukkit.getOnlinePlayers())
      if (admitted.containsKey(p.getUniqueId())
          && now - admitted.get(p.getUniqueId()) >= 60000
          && !plugin.adminRuntime.blocked(p)) deliver(p);
    prompts.entrySet().removeIf(e -> e.getValue().expires < now);
    if (dirty && !writing.get()) saveAsync();
  }

  private void purge() {
    long now = System.currentTimeMillis();
    boolean changed = feedback.values().removeIf(f -> now - f.at >= feedbackTtl());
    changed |= campaigns.values().removeIf(c -> c.expires <= now);
    dirty |= changed;
  }

  private int maxText() {
    return Math.max(
        100, Math.min(2000, plugin.getConfig().getInt("player-community.max-text-length", 1000)));
  }

  void adminHome(Player p) {
    if (!admin(p)) return;
    show(
        p,
        "Spielerprofile & Community",
        List.of(
            b(
                "Feedback-Zentrale",
                feedback.size() + " Nachrichten • begrenzte Aufbewahrung",
                Material.WRITABLE_BOOK,
                () -> adminFeedback(p)),
            b(
                "Ankündigungen mit Ablauf",
                campaigns.size() + " aktiv • DE/EN • späteren Beitritt berücksichtigen",
                Material.FIREWORK_ROCKET,
                () -> adminCampaigns(p)),
            b(
                "Strafverlauf verwalten",
                "Spieler wählen • Verlauf löschen ändert keine aktive Strafe",
                Material.SHIELD,
                () -> adminHistoryPlayers(p)),
            b(
                "Einstellungen",
                "Limits, Ablauf, KI-Freigaben",
                Material.COMPARATOR,
                () -> adminSettings(p)),
            b("Zurück", "Admin-Zentrale", Material.ARROW, () -> plugin.unifiedMenu.open(p))),
        0,
        true);
  }

  private void adminSettings(Player p) {
    if (!admin(p)) return;
    List<Button> rows = new ArrayList<>();
    for (String path :
        List.of(
            "player-community.feedback.retention-days",
            "player-community.feedback.cooldown-seconds",
            "player-community.feedback.max-per-player",
            "player-community.history-per-player",
            "player-community.max-text-length"))
      rows.add(
          b(
              settingLabel(path),
              "Aktuell: "
                  + plugin.getConfig().getInt(path)
                  + " • Bereich: "
                  + settingMin(path)
                  + "–"
                  + settingMax(path),
              Material.COMPARATOR,
              () ->
                  prompt(
                      p,
                      "Zahl eingeben",
                      value -> {
                        if (!admin(p)) return;
                        try {
                          int n = Integer.parseInt(value);
                          if (n < settingMin(path) || n > settingMax(path))
                            throw new IllegalArgumentException();
                          plugin.getConfig().set(path, n);
                          plugin.saveConfig();
                          adminSettings(p);
                        } catch (IllegalArgumentException e) {
                          p.sendMessage(
                              "§cBitte eine ganze Zahl "
                                  + settingMin(path)
                                  + "–"
                                  + settingMax(path)
                                  + " eingeben.");
                        }
                      },
                      true)));
    for (String path :
        List.of(
            "player-community.ai.require-review",
            "discord-bot.relay-ai.enabled",
            "discord-bot.relay-ai.fail-open",
            "openai.moderation.fail-open"))
      rows.add(
          b(
              settingLabel(path) + ": " + (plugin.getConfig().getBoolean(path) ? "AN" : "AUS"),
              path.contains("fail-open")
                  ? "AN erlaubt ungeprüfte Nachrichten bei KI-Ausfall"
                  : "Prüfung vor Veröffentlichung • nur bei aktiver KI",
              Material.COMMAND_BLOCK,
              () -> {
                if (!admin(p)) return;
                plugin.getConfig().set(path, !plugin.getConfig().getBoolean(path));
                plugin.saveConfig();
                adminSettings(p);
              }));
    rows.add(b("Zurück", "", Material.ARROW, () -> adminHome(p)));
    show(p, "Spielerprofil-Einstellungen", rows, 0, true);
  }

  private static String settingLabel(String path) {
    return switch (path) {
      case "player-community.feedback.retention-days" -> "Feedback-Aufbewahrung (Tage)";
      case "player-community.feedback.cooldown-seconds" -> "Feedback-Abstand (Sekunden)";
      case "player-community.feedback.max-per-player" -> "Feedback-Limit pro Spieler";
      case "player-community.history-per-player" -> "Strafverlauf-Limit pro Spieler";
      case "player-community.max-text-length" -> "Textlänge (Zeichen)";
      case "player-community.ai.require-review" -> "Strenge Prüfung bei aktiver KI";
      case "discord-bot.relay-ai.enabled" -> "Optionale Discord-KI-Prüfung";
      case "discord-bot.relay-ai.fail-open" -> "Discord bei KI-Ausfall freigeben";
      case "openai.moderation.fail-open" -> "Minecraft bei KI-Ausfall freigeben";
      default -> path;
    };
  }

  private static int settingMin(String path) {
    return switch (path) {
      case "player-community.feedback.cooldown-seconds", "player-community.history-per-player" ->
          10;
      case "player-community.max-text-length" -> 100;
      default -> 1;
    };
  }

  private static int settingMax(String path) {
    return switch (path) {
      case "player-community.feedback.retention-days" -> 90;
      case "player-community.feedback.cooldown-seconds" -> 3600;
      case "player-community.feedback.max-per-player" -> 50;
      case "player-community.history-per-player" -> 1000;
      default -> 2000;
    };
  }

  private void adminFeedback(Player p) {
    if (!admin(p)) return;
    purge();
    List<Button> rows = new ArrayList<>();
    for (Feedback f : feedback.values())
      rows.add(
          b(
              f.name + " • " + plugin.profileDate(f.at),
              f.text,
              Material.WRITABLE_BOOK,
              () -> {
                if (!admin(p) || !feedback.containsKey(f.id)) return;
                Feedback current = feedback.get(f.id);
                show(
                    p,
                    "Feedback: " + f.name,
                    List.of(
                        b(
                            "Nachricht",
                            current.text,
                            Material.PAPER,
                            () -> p.sendMessage(current.text)),
                        b(
                            "Antwort",
                            current.reply,
                            Material.BOOK,
                            () ->
                                prompt(
                                    p,
                                    "Antwort verfassen",
                                    value -> {
                                      if (!admin(p)) return;
                                      Feedback latest = feedback.get(f.id);
                                      if (latest == null) return;
                                      String reply = clean(value, maxText());
                                      feedback.put(
                                          f.id,
                                          new Feedback(
                                              latest.id,
                                              latest.owner,
                                              latest.name,
                                              latest.at,
                                              latest.text,
                                              reply,
                                              System.currentTimeMillis()));
                                      dirty = true;
                                      Player target = Bukkit.getPlayer(f.owner);
                                      if (target != null)
                                        target.sendMessage(
                                            "§b"
                                                + t(
                                                    target,
                                                    "Das Team hat auf dein Feedback geantwortet."
                                                        + " /profil → Feedback",
                                                    "Staff replied to your feedback. /profile →"
                                                        + " Feedback"));
                                      adminFeedback(p);
                                    },
                                    true)),
                        b(
                            "Löschen",
                            "Nachricht und Antwort vollständig entfernen",
                            Material.BARRIER,
                            () -> {
                              if (admin(p)) {
                                feedback.remove(f.id);
                                dirty = true;
                                adminFeedback(p);
                              }
                            }),
                        b("Zurück", "", Material.ARROW, () -> adminFeedback(p))),
                    0,
                    true);
              }));
    rows.add(b("Zurück", "", Material.ARROW, () -> adminHome(p)));
    show(p, "Feedback-Zentrale", rows, 0, true);
  }

  private void adminCampaigns(Player p) {
    if (!admin(p)) return;
    List<Button> rows = new ArrayList<>();
    rows.add(
        b(
            "Ankündigung erstellen",
            "EN | DE (optional) | Laufzeit in Stunden | Intervall in Minuten (0=einmal)",
            Material.FIREWORK_ROCKET,
            () -> campaignForm(p)));
    for (Campaign c : campaigns.values())
      rows.add(
          b(
              c.enabled ? "Aktive Ankündigung" : "Pausierte Ankündigung",
              c.en + " • Ablauf " + plugin.profileDate(c.expires),
              Material.PAPER,
              () -> {
                if (!admin(p) || !campaigns.containsKey(c.id)) return;
                show(
                    p,
                    "Ankündigung verwalten",
                    List.of(
                        b("Englisch", c.en, Material.BOOK, () -> {}),
                        b("Deutsch", c.de, Material.BOOK, () -> {}),
                        b(
                            "Pause / Fortsetzen",
                            "Zustellverlauf bleibt erhalten",
                            Material.LEVER,
                            () -> {
                              if (admin(p)) {
                                c.enabled = !c.enabled;
                                dirty = true;
                                adminCampaigns(p);
                              }
                            }),
                        b(
                            "Löschen",
                            "Ankündigung und Zustellverlauf entfernen",
                            Material.BARRIER,
                            () -> {
                              if (admin(p)) {
                                campaigns.remove(c.id);
                                dirty = true;
                                adminCampaigns(p);
                              }
                            }),
                        b("Zurück", "", Material.ARROW, () -> adminCampaigns(p))),
                    0,
                    true);
              }));
    rows.add(b("Zurück", "", Material.ARROW, () -> adminHome(p)));
    show(p, "Ankündigungen mit Ablauf", rows, 0, true);
  }

  private void campaignForm(Player p) {
    if (!admin(p)) return;
    if (plugin.isBedrockClient(p)) {
      var form =
          SimplexAdminSuite.CustomForm.builder()
              .title("Ankündigung erstellen")
              .input("Englischer Text", "", " ")
              .input("Deutscher Text (optional)", "", "")
              .input("Gültigkeit in Stunden (1–720)", "24", "24")
              .input("Wiederholung in Minuten (0=einmal)", "0", "0")
              .validResultHandler(
                  r ->
                      plugin.runAdminTask(
                          () ->
                              createInput(
                                  p,
                                  r.asInput(0)
                                      + "|"
                                      + r.asInput(1)
                                      + "|"
                                      + r.asInput(2)
                                      + "|"
                                      + r.asInput(3))));
      if (plugin.sendBedrockForm(p, form)) return;
    }
    prompt(
        p, "EN | DE optional | Stunden | Intervall-Minuten", value -> createInput(p, value), true);
  }

  private void createInput(Player p, String text) {
    if (!admin(p)) return;
    try {
      String[] parts = text.split("\\|", -1);
      if (parts.length != 4) throw new IllegalArgumentException();
      createCampaign(
          p.getName(),
          parts[0],
          parts[1],
          Integer.parseInt(parts[2].trim()),
          Integer.parseInt(parts[3].trim()));
      adminCampaigns(p);
    } catch (IllegalArgumentException e) {
      p.sendMessage("§cFormat: Englischer Text | Deutscher Text | 24 | 0");
    }
  }

  private void adminHistoryPlayers(Player p) {
    if (!admin(p)) return;
    List<Button> rows = new ArrayList<>();
    for (var profile : plugin.expansionProfiles())
      rows.add(
          b(
              profile.displayName(),
              "Eigener Strafverlauf • Warnungen bleiben separat",
              Material.PLAYER_HEAD,
              () -> adminHistory(p, profile.uniqueId())));
    rows.add(b("Zurück", "", Material.ARROW, () -> adminHome(p)));
    show(p, "Strafverlauf • Spieler", rows, 0, true);
  }

  private void adminHistory(Player p, UUID owner) {
    if (!admin(p)) return;
    List<Button> rows = new ArrayList<>();
    for (History h : history.values().stream().filter(h -> h.owner.equals(owner)).toList())
      rows.add(
          b(
              h.action + " • " + plugin.profileDate(h.at),
              h.reason,
              Material.SHIELD,
              () ->
                  show(
                      p,
                      "Verlaufseintrag löschen?",
                      List.of(
                          b(
                              "Löschen bestätigen",
                              "Die aktive Strafe bleibt unverändert",
                              Material.BARRIER,
                              () -> {
                                if (admin(p)) {
                                  history.remove(h.id);
                                  dirty = true;
                                  plugin.suiteAudit(
                                      p.getName(), "Profil-Strafverlauf gelöscht", h.id);
                                  adminHistory(p, owner);
                                }
                              }),
                          b("Abbrechen", "", Material.ARROW, () -> adminHistory(p, owner))),
                      0,
                      true)));
    rows.add(b("Zurück", "", Material.ARROW, () -> adminHistoryPlayers(p)));
    show(p, "Strafverlauf löschen", rows, 0, true);
  }

  private void prompt(Player p, String title, Consumer<String> action, boolean admin) {
    if (admin && !admin(p)) return;
    if (plugin.isBedrockClient(p)) {
      var form =
          SimplexAdminSuite.CustomForm.builder()
              .title(title)
              .input(title, "", "")
              .validResultHandler(
                  r ->
                      plugin.runAdminTask(
                          () -> {
                            if (Bukkit.getPlayer(p.getUniqueId()) == p && (!admin || admin(p)))
                              action.accept(r.asInput(0));
                          }));
      if (plugin.sendBedrockForm(p, form)) return;
      p.sendMessage(
          t(
              p,
              "§cDas Bedrock-Formular konnte nicht geöffnet werden. Bitte erneut versuchen.",
              "§cCould not open the Bedrock form. Please try again."));
      return;
    }
    p.closeInventory();
    prompts.put(
        p.getUniqueId(),
        new Prompt(
            System.currentTimeMillis() + 120000,
            value -> {
              if (!admin || admin(p)) action.accept(value);
            }));
    p.sendMessage(
        "§e"
            + title
            + "\n§7"
            + (admin
                ? "Text im Chat eingeben • cancel = Abbrechen"
                : t(
                    p,
                    "Text im Chat eingeben • cancel = Abbrechen",
                    "Enter text in chat • cancel = cancel")));
  }

  boolean consumeChat(Player p, String entered) {
    Prompt prompt = prompts.remove(p.getUniqueId());
    if (prompt == null) return false;
    Bukkit.getScheduler()
        .runTask(
            plugin,
            () -> {
              if (Bukkit.getPlayer(p.getUniqueId()) != p) return;
              if (prompt.expires < System.currentTimeMillis()
                  || entered.equalsIgnoreCase("cancel")
                  || entered.equalsIgnoreCase("abbrechen")) {
                p.sendMessage(t(p, "§7Eingabe abgebrochen.", "§7Input cancelled."));
                return;
              }
              prompt.action.accept(entered);
            });
    return true;
  }

  private void show(Player p, String title, List<Button> buttons, int requested, boolean admin) {
    if (stopped || Bukkit.getPlayer(p.getUniqueId()) != p || admin && !admin(p)) return;
    generations.merge(p.getUniqueId(), 1L, Long::sum);
    prompts.remove(p.getUniqueId());
    int page = Math.max(0, Math.min((buttons.size() - 1) / 28, requested)),
        start = page * 28,
        end = Math.min(buttons.size(), start + 28);
    if (plugin.isBedrockClient(p)) {
      long gen = generations.get(p.getUniqueId());
      long expectedForm = plugin.nativeFormGeneration(p.getUniqueId()) + 1;
      var form =
          SimplexAdminSuite.SimpleForm.builder()
              .title(title)
              .content(formContent(p, title, buttons, admin));
      for (Button row : buttons.subList(start, end))
        form.button(
            row.title + (row.detail.isBlank() ? "" : "\n" + shortText(row.detail, 65)),
            r ->
                plugin.runAdminTask(
                    () -> {
                      if (Bukkit.getPlayer(p.getUniqueId()) == p
                          && generations.getOrDefault(p.getUniqueId(), 0L) == gen
                          && expectedForm == plugin.nativeFormGeneration(p.getUniqueId())
                          && (!admin || admin(p))) {
                        long nativeGeneration = plugin.nativeFormGeneration(p.getUniqueId());
                        row.action.run();
                        if (generations.getOrDefault(p.getUniqueId(), 0L) == gen
                            && nativeGeneration == plugin.nativeFormGeneration(p.getUniqueId())
                            && !prompts.containsKey(p.getUniqueId())
                            && Bukkit.getPlayer(p.getUniqueId()) == p
                            && !MenuStyle.plain(row.title).matches("(?i)(Schließen|Close)"))
                          show(p, title, buttons, page, admin);
                      }
                    }));
      if (page > 0)
        form.button(
            admin ? "Vorherige Seite" : t(p, "Vorherige Seite", "Previous page"),
            r -> plugin.runAdminTask(() -> show(p, title, buttons, page - 1, admin)));
      if (end < buttons.size())
        form.button(
            admin ? "Nächste Seite" : t(p, "Nächste Seite", "Next page"),
            r -> plugin.runAdminTask(() -> show(p, title, buttons, page + 1, admin)));
      if (plugin.sendBedrockForm(p, form)) return;
      p.sendMessage(
          t(
              p,
              "§cFormular konnte nicht geöffnet werden. Bitte erneut versuchen.",
              "§cCould not open the form. Please try again."));
      return;
    }
    Holder h = new Holder(p.getUniqueId(), admin);
    Inventory inv = LiveMenus.create(h, 54, "§0" + shortText(title, 45));
    h.inventory = inv;
    ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, " ", "");
    for (int i = 0; i < 54; i++) inv.setItem(i, filler);
    inv.setItem(
        4,
        item(
            Material.PLAYER_HEAD,
            title,
            admin ? "Administration" : "Simplex SMP • " + p.getName()));
    int n = 0;
    for (Button row : buttons.subList(start, end)) {
      int slot = 10 + (n / 7) * 9 + n % 7;
      inv.setItem(slot, item(row.icon, "§b" + row.title, row.detail));
      h.actions.put(slot, row.action);
      n++;
    }
    if (page > 0) {
      inv.setItem(
          45,
          item(
              Material.ARROW,
              admin ? "Vorherige Seite" : t(p, "Vorherige Seite", "Previous page"),
              ""));
      h.actions.put(45, () -> show(p, title, buttons, page - 1, admin));
    }
    if (end < buttons.size()) {
      inv.setItem(
          53,
          item(Material.ARROW, admin ? "Nächste Seite" : t(p, "Nächste Seite", "Next page"), ""));
      h.actions.put(53, () -> show(p, title, buttons, page + 1, admin));
    }
    inv.setItem(49, item(Material.BARRIER, admin ? "Schließen" : t(p, "Schließen", "Close"), ""));
    h.actions.put(49, p::closeInventory);
    LiveMenus.show(p,inv);
  }

  private String formContent(Player p, String title, List<Button> rows, boolean admin) {
    if (title.equals("Meine Nachricht")
        || title.equals("My message")
        || title.startsWith("Feedback: ")) {
      StringBuilder content = new StringBuilder();
      for (Button row : rows)
        if (row.icon == Material.PAPER || row.icon == Material.BOOK)
          content.append("§l").append(row.title).append("§r\n").append(row.detail).append("\n\n");
      return content.toString();
    }
    return admin ? "Admin-Verwaltung" : "Simplex SMP • " + p.getName();
  }

  private static ItemStack item(Material m, String name, String detail) {
    ItemStack stack = new ItemStack(m);
    ItemMeta meta = stack.getItemMeta();
    if (meta != null) {
      meta.setDisplayName(MenuStyle.javaText(name));
      List<String> lore = new ArrayList<>();
      for (String line : detail.split("\n")) {
        for (int n = 0; n < line.length(); n += 65)
          lore.add("§7" + line.substring(n, Math.min(line.length(), n + 65)));
      }
      meta.setLore(lore.stream().map(MenuStyle::javaText).toList());
      stack.setItemMeta(meta);
    }
    return stack;
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void click(InventoryClickEvent e) {
    if (!(LiveMenus.holder(e.getView().getTopInventory()) instanceof Holder h)) return;
    e.setCancelled(true);
    if (!(e.getWhoClicked() instanceof Player p)
        || !h.owner.equals(p.getUniqueId())
        || h.admin && !admin(p)
        || e.getRawSlot() < 0
        || e.getRawSlot() >= 54) return;
    Runnable action = h.actions.get(e.getRawSlot());
    if (action != null)
      Bukkit.getScheduler()
          .runTask(
              plugin,
              () -> {
                if (p.getOpenInventory().getTopInventory() == h.inventory && LiveMenus.holder(h.inventory)==h) action.run();
              });
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void drag(InventoryDragEvent e) {
    if (LiveMenus.holder(e.getView().getTopInventory()) instanceof Holder) e.setCancelled(true);
  }

  @EventHandler
  public void quit(PlayerQuitEvent e) {
    UUID id = e.getPlayer().getUniqueId();
    prompts.remove(id);
    admitted.remove(id);
    generations.remove(id);
    cooldowns.remove(id);
  }

  static String clean(String value, int max) {
    String text =
        value == null
            ? ""
            : value.replaceAll("[\\p{Cntrl}\\p{Cf}§]", " ").replaceAll("\\s+", " ").trim();
    return shortText(text, max);
  }

  private static String shortText(String s, int max) {
    return s.substring(0, Math.min(max, s.length()));
  }

  private void saveAsync() {
    if (!writing.compareAndSet(false, true)) return;
    dirty = false;
    String yaml = snapshot();
    io.execute(
        () -> {
          try {
            write(yaml);
          } finally {
            writing.set(false);
          }
        });
  }

  private void write(String yaml) {
    try {
      Files.createDirectories(file.getParent());
      Path temp = file.resolveSibling(file.getFileName() + ".tmp");
      Files.writeString(temp, yaml);
      try {
        Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException e) {
      plugin
          .getLogger()
          .severe("Spielerprofile/Feedback konnten nicht gespeichert werden: " + e.getMessage());
      if (plugin.isEnabled() && !stopped) Bukkit.getScheduler().runTask(plugin, () -> dirty = true);
    }
  }

  private String snapshot() {
    YamlConfiguration y = new YamlConfiguration();
    y.set("schema-version", 1);
    y.set("legacy-imported", migrated.stream().map(UUID::toString).toList());
    for (var e : preferences.entrySet()) {
      String root = "preferences." + e.getKey();
      y.set(root + ".language", e.getValue().language);
      y.set(root + ".scoreboard", e.getValue().scoreboard);
      y.set(root + ".join-messages", e.getValue().joins);
    }
    List<Map<String, Object>> hs = new ArrayList<>(),
        fs = new ArrayList<>(),
        cs = new ArrayList<>();
    for (History h : history.values())
      hs.add(
          map(
              "id",
              h.id,
              "owner",
              h.owner.toString(),
              "at",
              h.at,
              "action",
              h.action,
              "duration",
              h.duration,
              "reason",
              h.reason,
              "source",
              h.source));
    for (Feedback f : feedback.values())
      fs.add(
          map(
              "id",
              f.id,
              "owner",
              f.owner.toString(),
              "name",
              f.name,
              "at",
              f.at,
              "text",
              f.text,
              "reply",
              f.reply,
              "answered-at",
              f.answeredAt));
    for (Campaign c : campaigns.values()) {
      Map<String, Object> delivered = new LinkedHashMap<>();
      c.delivered.forEach((k, v) -> delivered.put(k.toString(), v));
      cs.add(
          map(
              "id",
              c.id,
              "en",
              c.en,
              "de",
              c.de,
              "created",
              c.created,
              "expires",
              c.expires,
              "interval",
              c.interval,
              "enabled",
              c.enabled,
              "delivered",
              delivered));
    }
    y.set("history", hs);
    y.set("feedback", fs);
    y.set("announcements", cs);
    return y.saveToString();
  }

  private void load() {
    if (!Files.isRegularFile(file)) return;
    YamlConfiguration y = new YamlConfiguration();
    try {
      y.load(file.toFile());
      for (String id : y.getStringList("legacy-imported")) migrated.add(UUID.fromString(id));
      var section = y.getConfigurationSection("preferences");
      if (section != null)
        for (String key : section.getKeys(false)) {
          String root = "preferences." + key;
          String language = y.getString(root + ".language", "auto");
          if (!Set.of("auto", "de", "en").contains(language)) language = "auto";
          preferences.put(
              UUID.fromString(key),
              new Preferences(
                  language,
                  y.getBoolean(root + ".scoreboard", true),
                  y.getBoolean(root + ".join-messages", true)));
        }
      for (Map<?, ?> m : y.getMapList("history")) {
        History h =
            new History(
                str(m, "id"),
                UUID.fromString(str(m, "owner")),
                num(m, "at"),
                str(m, "action"),
                str(m, "duration"),
                str(m, "reason"),
                str(m, "source"));
        history.put(h.id, h);
      }
      for (Map<?, ?> m : y.getMapList("feedback")) {
        Feedback f =
            new Feedback(
                str(m, "id"),
                UUID.fromString(str(m, "owner")),
                str(m, "name"),
                num(m, "at"),
                str(m, "text"),
                str(m, "reply"),
                num(m, "answered-at"));
        feedback.put(f.id, f);
      }
      for (Map<?, ?> m : y.getMapList("announcements")) {
        Campaign c =
            new Campaign(
                str(m, "id"),
                str(m, "en"),
                str(m, "de"),
                num(m, "created"),
                num(m, "expires"),
                num(m, "interval"));
        c.enabled = !"false".equals(str(m, "enabled"));
        if (m.get("delivered") instanceof Map<?, ?> seen)
          for (var e : seen.entrySet())
            c.delivered.put(
                UUID.fromString(String.valueOf(e.getKey())),
                Long.parseLong(String.valueOf(e.getValue())));
        campaigns.put(c.id, c);
      }
      purge();
    } catch (Exception e) {
      throw new IllegalStateException(
          "Spielerprofil-Erweiterung konnte nicht sicher geladen werden; Originaldatei bleibt"
              + " unverändert",
          e);
    }
  }

  private static Map<String, Object> map(Object... values) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int n = 0; n < values.length; n += 2) m.put((String) values[n], values[n + 1]);
    return m;
  }

  private static String str(Map<?, ?> m, String key) {
    return String.valueOf(m.containsKey(key) ? m.get(key) : "");
  }

  private static long num(Map<?, ?> m, String key) {
    try {
      return Long.parseLong(str(m, key));
    } catch (NumberFormatException e) {
      return 0;
    }
  }
}
