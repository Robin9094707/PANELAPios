package eu.simplexsmp.adminsuite;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;

/** A single menu model renders both native Bedrock forms and Java inventories. */
final class AdminUpdateMenu implements Listener {
  record Button(
      String title, String detail, Material icon, Runnable action, boolean informational) {
    Button(String title, String detail, Material icon, Runnable action) {
      this(title, detail, icon, action, false);
    }
  }

  static Button info(String title, String detail, Material icon) {
    return new Button(title, detail, icon, () -> {}, true);
  }

  record Setting(
      String group, String path, String title, String hint, String kind, int min, int max) {}

  private final SimplexAdminSuite plugin;
  private final Map<UUID, Consumer<String>> text = new ConcurrentHashMap<>();
  private final Map<UUID, Long> textRevision = new ConcurrentHashMap<>();
  static final List<Setting> SETTINGS =
      List.of(
          s("Teamnachrichten", "quick-delivery.delay-seconds", "Zustellverzögerung (Sekunden)", "Freie Online-Zeit nach dem Beitritt; 1 bis 300 Sekunden", "int", 1, 300),
          s(
              "Nachrichten",
              "prefix",
              "Nachrichtenpräfix",
              "Gemeinsamer Präfix für Suite-Nachrichten • Minecraft-Farbcodes mit &",
              "string",
              0,
              0),
          s(
              "Nachrichten",
              "admin-update.feedback.enabled",
              "Akustische Menürückmeldung",
              "Erfolg und Fehler vertonen; Navigation bleibt still",
              "bool",
              0,
              0),
          s(
              "Nachrichten",
              "admin-update.feedback.success-sound",
              "Sound bei Erfolg",
              "Minecraft-Sound-ID, z. B. entity.experience_orb.pickup",
              "string",
              0,
              0),
          s(
              "Nachrichten",
              "admin-update.feedback.error-sound",
              "Sound bei Fehlern",
              "Minecraft-Sound-ID, z. B. block.note_block.bass",
              "string",
              0,
              0),
          s(
              "Nachrichten",
              "admin-update.feedback.volume-percent",
              "Lautstärke der Rückmeldung",
              "0–100 Prozent • Java und Bedrock",
              "int",
              0,
              100),
          s(
              "Willkommen",
              "admin-update.welcome.enabled",
              "Ersten Beitritt ankündigen",
              "Öffentliche Begrüßung • Deutsch oder Englisch nach Spielersprache",
              "bool",
              0,
              0),
          s(
              "Willkommen",
              "admin-update.welcome.message",
              "Willkommenstext",
              "Englische Version • Platzhalter {player}",
              "text",
              0,
              0),
          s(
              "Willkommen",
              "player-languages.de.welcome",
              "Willkommenstext (Deutsch)",
              "Deutsche Version • Platzhalter {player} • Farbcodes mit &",
              "text",
              0,
              0),
          s(
              "Willkommen",
              "admin-update.welcome.sound",
              "Willkommenssound",
              "Minecraft-Sound, z. B. entity.player.levelup; none = aus",
              "text",
              0,
              0),
          s(
              "Willkommen",
              "admin-update.welcome.volume-percent",
              "Sound-Lautstärke (%)",
              "0 = stumm, 100 = normal",
              "int",
              0,
              100),
          s(
              "Willkommen",
              "admin-update.welcome.pitch-percent",
              "Sound-Tonhöhe (%)",
              "50 = tief, 200 = hoch",
              "int",
              50,
              200),
          s(
              "Verifizierung",
              "admin-update.verification.enabled",
              "Beitrittsprüfung aktiv",
              "Einfacher Klickschutz; keine vollständige Bot-Erkennung",
              "bool",
              0,
              0),
          s(
              "Verifizierung",
              "admin-update.verification.timeout-seconds",
              "Zeitlimit in Sekunden",
              "Danach wird ein ungeprüfter Spieler gekickt",
              "int",
              5,
              300),
          s(
              "Verifizierung",
              "admin-update.verification.delay-ticks",
              "Wartezeit nach Beitritt (Ticks)",
              "Bewegung und Chat sind bereits währenddessen gesperrt",
              "int",
              1,
              100),
          s(
              "Verifizierung",
              "admin-update.verification.random-slot",
              "Zufälliger Bestätigungsplatz",
              "Wechselnde Position des grünen Buttons",
              "bool",
              0,
              0),
          s(
              "Vanish & Watch",
              "admin-update.vanish.publish-metadata",
              "Vanish an andere Plugins melden",
              "Eigene vanished-Metadaten; fremde Zustände bleiben erhalten",
              "bool",
              0,
              0),
          s(
              "Vanish & Watch",
              "admin-update.vanish.flight",
              "Fliegen im Vanish",
              "Vorheriger Flugzustand wird wiederhergestellt",
              "bool",
              0,
              0),
          s(
              "Vanish & Watch",
              "admin-update.vanish.lands-flight-bypass",
              "Lands-Flug-Bypass",
              "Nur während Vanish mit Flugrecht; keine dauerhaften Rechte",
              "bool",
              0,
              0),
          s(
              "Vanish & Watch",
              "admin-update.vanish.silent-interactions",
              "Stille Beobachtung",
              "Blockiert Kampf, Item-Aufnahme und Weltinteraktionen",
              "bool",
              0,
              0),
          s(
              "Vanish & Watch",
              "admin-update.watch.enabled",
              "Live-Beobachtung erlauben",
              "Nur Aktionen, die der Server tatsächlich empfängt",
              "bool",
              0,
              0),
          s(
              "Discord & Bilder",
              "discord-bot.relay.sleep",
              "Schlafmeldungen im Relay",
              "Englisch, pro Welt mit Schläfern und benötigter Anzahl",
              "bool",
              0,
              0),
          s(
              "Discord & Bilder",
              "discord-bot.relay.sleep-cooldown-seconds",
              "Schlafmeldungs-Abstand",
              "Mindestabstand je Spieler in Sekunden",
              "int",
              5,
              300),
          s(
              "Discord & Bilder",
              "discord-bot.relay.avatars.refresh-messages",
              "Profilbild nach N Nachrichten erneuern",
              "0 = ausschließlich zeitbasiert; Neustarts behalten Zähler",
              "int",
              0,
              1000),
          s(
              "Discord & Bilder",
              "discord-bot.relay.avatars.cache-hours",
              "Profilbild-Gültigkeit in Stunden",
              "URLs und Abrufzähler werden dauerhaft gespeichert",
              "int",
              1,
              168),
          s(
              "Discord & Bilder",
              "discord-bot.relay.avatars.bedrock-enabled",
              "Xbox-Profilbilder",
              "Öffentlicher PlayerDB-Xbox-Abruf im Hintergrund",
              "bool",
              0,
              0),
          s(
              "Discord & Bilder",
              "discord-bot.relay.avatars.java-url",
              "Java-Bildanbieter",
              "HTTPS-Vorlage mit {identifier}, {uuid} oder {name}",
              "text",
              0,
              0),
          s(
              "Discord & Bilder",
              "discord-bot.relay.player-webhooks",
              "Spieler-Webhooks",
              "Eigene Spielernamen und Profilbilder im Discord-Chat",
              "bool",
              0,
              0),
          s(
              "Admin-Alarme",
              "discord-bot.alerts.enabled",
              "Discord-Alarme aktiv",
              "Globaler Schalter für den bestehenden Alarmkanal",
              "bool",
              0,
              0),
          s(
              "Admin-Alarme",
              "discord-bot.alerts.channel-id",
              "Privater Alarmkanal (Discord-ID)",
              "Discord-Kanal-ID; leer = keine neuen Admin-Hinweise",
              "text",
              0,
              0),
          s(
              "Admin-Alarme",
              "admin-update.alerts.verification-timeout",
              "Verifizierungs-Timeout melden",
              "Admin-Kanal; begrenzte Meldungsrate",
              "bool",
              0,
              0),
          s(
              "Admin-Alarme",
              "admin-update.alerts.vanish",
              "Vanish-Wechsel melden",
              "Nur im privaten Discord-Admin-Kanal",
              "bool",
              0,
              0),
          s(
              "Admin-Alarme",
              "admin-update.alerts.cooldown-seconds",
              "Alarm-Abstand je Kategorie",
              "Schützt den Discord-Admin-Kanal vor Meldungsfluten",
              "int",
              5,
              600),
          s(
              "Gerätediagnose",
              "device-diagnostics.enabled",
              "Bedrock-Zusatzdaten aktiv",
              "Separate Zusatzanalyse; bestehende Alt-Scores bleiben unverändert. Bridge übernimmt"
                  + " Änderungen beim Heartbeat.",
              "bool",
              0,
              0),
          s(
              "Gerätediagnose",
              "device-diagnostics.cosmetics",
              "Skin- und Profil-Metadaten",
              "Separate Zusatzanalyse; bestehende Alt-Scores bleiben unverändert. Bridge übernimmt"
                  + " Änderungen beim Heartbeat.",
              "bool",
              0,
              0),
          s(
              "Gerätediagnose",
              "device-diagnostics.party",
              "Party-Metadaten",
              "Separate Zusatzanalyse; bestehende Alt-Scores bleiben unverändert. Bridge übernimmt"
                  + " Änderungen beim Heartbeat.",
              "bool",
              0,
              0),
          s(
              "Gerätediagnose",
              "device-diagnostics.misc",
              "Editor- und Filter-Metadaten",
              "Separate Zusatzanalyse; bestehende Alt-Scores bleiben unverändert. Bridge übernimmt"
                  + " Änderungen beim Heartbeat.",
              "bool",
              0,
              0),
          s(
              "Gerätediagnose",
              "device-diagnostics.session-refresh",
              "Sitzungen erneut erfassen",
              "Separate Zusatzanalyse; bestehende Alt-Scores bleiben unverändert. Bridge übernimmt"
                  + " Änderungen beim Heartbeat.",
              "bool",
              0,
              0),
          s(
              "Gerätediagnose",
              "device-diagnostics.java-options",
              "Java-Client-Einstellungen",
              "Separate Zusatzanalyse; bestehende Alt-Scores bleiben unverändert. Bridge übernimmt"
                  + " Änderungen beim Heartbeat.",
              "bool",
              0,
              0),
          s(
              "Gerätediagnose",
              "device-diagnostics.comparisons",
              "Manuelle Gerätevergleiche",
              "Separate Zusatzanalyse; bestehende Alt-Scores bleiben unverändert. Bridge übernimmt"
                  + " Änderungen beim Heartbeat.",
              "bool",
              0,
              0),
          s(
              "Konsole (Hauptadmin)",
              "discord-bot.admin.console.enabled",
              "Discord-Konsole aktiv",
              "Bisherige Allowlist bleibt erhalten",
              "bool",
              0,
              0),
          s(
              "Konsole (Hauptadmin)",
              "discord-bot.admin.console.full-access",
              "Voller Konsolenzugriff",
              "Nur Discord-Administratoren; nach Bestätigung ohne Befehls-Allowlist",
              "bool",
              0,
              0),
          s(
              "Konsole (Hauptadmin)",
              "discord-bot.admin.console.feedback-seconds",
              "Feedback-Zeitraum in Sekunden",
              "Befehlsantworten werden als private Discord-Antwort aktualisiert",
              "int",
              1,
              15));

  private static Setting s(String a, String b, String c, String d, String e, int f, int g) {
    return new Setting(a, b, c, d, e, f, g);
  }

  AdminUpdateMenu(SimplexAdminSuite plugin) {
    this.plugin = plugin;
  }

  private boolean permitted(Player p) {
    return plugin.adminSessionUsable(p) && plugin.has(p, "simplexadminsuite.admin");
  }

  private Button button(String title, String detail, Runnable action) {
    return new Button(
        title,
        detail,
        title.contains("Vanish")
            ? Material.ENDER_EYE
            : title.contains("beobachten")
                ? Material.SPYGLASS
                : title.contains("Rechte") || title.contains("Adminrechte")
                    ? Material.SHIELD
                    : settingIcon(title),
        action);
  }

  private Material settingIcon(String title) {
    String t = title.toLowerCase(Locale.ROOT);
    if (t.contains("sound")
        || t.contains("ton")
        || t.contains("laut")
        || t.contains("rückmeldung")
        || t.contains("klang")) return Material.NOTE_BLOCK;
    if (t.contains("zeit") || t.contains("sekund") || t.contains("abstand")) return Material.CLOCK;
    if (t.contains("discord") || t.contains("kanal") || t.contains("bild")) return Material.MAP;
    if (t.contains("konsole") || t.contains("zugriff")) return Material.COMMAND_BLOCK;
    if (t.contains("aktiv") || t.contains("erlaub") || t.contains("prüfung"))
      return Material.LIME_DYE;
    if (t.contains("willkommen")) return Material.FIREWORK_ROCKET;
    return Material.WRITABLE_BOOK;
  }

  void open(Player p) {
    plugin.unifiedMenu.open(p);
  }

  void settings(Player p, String group) {
    if (!permitted(p) || !plugin.has(p, "simplexadminsuite.settings")) {
      plugin.menuError(p, "§cKeine Einstellungsrechte.");
      return;
    }
    if (group.startsWith("Konsole") && !plugin.adminAccess.master(p)) {
      plugin.menuError(p, "§cNur Hauptadmin oder echte Server-Operatoren.");
      return;
    }
    List<Button> b = new ArrayList<>();
    for (Setting s : SETTINGS)
      if (s.group.equals(group)) {
        String value = String.valueOf(plugin.getConfig().get(s.path));
        b.add(button(s.title + ": " + value, s.hint, () -> edit(p, s)));
      }
    b.add(button("Zurück", "Admin-Zentrale", () -> open(p)));
    show(p, group, b, 0);
  }

  void edit(Player p, Setting s) {
    if(s.path.equals("prefix")&&!plugin.adminAccess.master(p)){plugin.menuError(p,"§cNur Hauptadmins dürfen den Präfix ändern.");return;}
    if (!permitted(p)
        || !plugin.has(p, "simplexadminsuite.settings")
        || (s.group.startsWith("Konsole") && !plugin.adminAccess.master(p))) return;
    if (s.kind.equals("bool")) {
      commit(p, s, !plugin.getConfig().getBoolean(s.path));
      return;
    }
    prompt(
        p,
        s.title,
        String.valueOf(plugin.getConfig().get(s.path)),
        value -> {
          try {
            Object result = value;
            if (s.path.startsWith("admin-update.feedback.") && s.path.endsWith("-sound"))
              result = SimplexAdminSuite.validatedMenuSound(value);
            if (s.kind.equals("int")) {
              int n = Integer.parseInt(value);
              if (n < s.min || n > s.max)
                throw new IllegalArgumentException("Erlaubt: " + s.min + " bis " + s.max);
              result = n;
            }
            if (s.path.endsWith("java-url")
                && (!value.startsWith("https://") || value.length() > 500))
              throw new IllegalArgumentException("HTTPS-Adresse erforderlich");
            if (value.length() > 500) throw new IllegalArgumentException("Maximal 500 Zeichen");
            commit(p, s, result);
          } catch (IllegalArgumentException e) {
            plugin.menuError(p, "§cUngültiger Wert: " + e.getMessage());
            settings(p, s.group);
          }
        });
  }

  void commit(Player p, Setting s, Object value) {
    if(s.path.equals("prefix")&&!plugin.adminAccess.master(p))return;
    if (!permitted(p)
        || !plugin.has(p, "simplexadminsuite.settings")
        || (s.group.startsWith("Konsole") && !plugin.adminAccess.master(p))) return;
    plugin.getConfig().set(s.path, value);
    plugin.saveConfig();
    plugin.expansionAudit(p.getName(), null, "Admin-Einstellung", s.path + "=" + value);
    if (s.path.contains("avatars.") || s.path.endsWith("player-webhooks"))
      plugin.discordRelay().reloadPlayerRelay();
    plugin.adminRuntime.settingsChanged();
    settings(p, s.group);
  }

  void players(Player p, boolean rights) {
    if (!permitted(p)) return;
    if (rights && !plugin.adminAccess.master(p)) {
      plugin.menuError(p, "§cNur Hauptadmin oder echte Server-Operatoren.");
      return;
    }
    List<Button> b = new ArrayList<>();
    for (Player target : Bukkit.getOnlinePlayers())
      b.add(
          button(
              target.getName(),
              rights ? "Rechte verwalten" : "Live beobachten",
              () -> {
                if (rights) rights(p, target.getUniqueId(), target.getName(), 0, "");
                else plugin.adminRuntime.watch(p, target);
              }));
    if (rights)
      b.add(
          button(
              "Offline-Profil auswählen",
              "Exakter gespeicherter Spielername oder UUID",
              () ->
                  prompt(
                      p,
                      "Spielername / UUID",
                      "",
                      q -> {
                        UUID id = plugin.discordAdminResolvePlayer(q);
                        if (id == null)
                          plugin.menuNotice(p, "§cKein gespeichertes Profil gefunden.");
                        else rights(p, id, q, 0, "");
                      })));
    b.add(button("Zurück", "Admin-Zentrale", () -> open(p)));
    show(p, rights ? "Adminrechte: Spieler" : "Live-Watch: Spieler", b, 0);
  }

  void rights(Player p, UUID target, String name, int page, String filter) {
    if (!permitted(p) || !plugin.adminAccess.master(p)) return;
    List<Button> b = new ArrayList<>();
    b.add(
        button(
            "Rolle: " + roleLabel(plugin.adminAccess.role(target)),
            "Klick: Rolle auswählen; Einzelrechte bleiben erhalten",
            () -> roles(p, target, name)));
    b.add(
        button(
            "Rechte filtern",
            filter.isBlank() ? "Suchbegriff wie kick, ban, menu oder settings" : filter,
            () ->
                prompt(
                    p,
                    "Rechtefilter (leer = alle)",
                    filter,
                    q -> rights(p, target, name, 0, q.toLowerCase(Locale.ROOT)))));
    for (String node : plugin.adminAccess.nodes())
      if (node.toLowerCase(Locale.ROOT).contains(filter)) {
        Boolean value = plugin.adminAccess.override(target, node);
        b.add(
            button(
                node.replace("simplexadminsuite.", ""),
                "Einzelrecht: "
                    + (value == null ? "Rolle/LuckPerms" : value ? "ERLAUBT" : "GESPERRT")
                    + " • Klick ändern",
                () -> {
                  List<Button> choices = new ArrayList<>();
                  choices.add(
                      button(
                          "Erlauben",
                          "Explizite Freigabe",
                          () -> change(p, target, name, node, true, null, page, filter)));
                  choices.add(
                      button(
                          "Sperren",
                          "Vorrang vor Wildcards / LuckPerms",
                          () -> change(p, target, name, node, false, null, page, filter)));
                  choices.add(
                      button(
                          "Vererben",
                          "Rolle / LuckPerms übernehmen",
                          () -> change(p, target, name, node, null, null, page, filter)));
                  choices.add(
                      button(
                          "Zurück", "Ohne Änderung", () -> rights(p, target, name, page, filter)));
                  show(p, node.replace("simplexadminsuite.", ""), choices, 0);
                }));
      }
    b.add(button("Zurück", "Spielerauswahl", () -> players(p, true)));
    show(p, "Rechte: " + name, b, page);
  }

  private static String roleLabel(AdminAccessManager.Role role) {
    return switch (role) {
      case INHERIT -> "Bestehende Rechte / LuckPerms";
      case FULL -> "Volladmin";
      case MODERATOR -> "Moderator";
      case SUPPORT -> "Support";
      case READ_ONLY -> "Lesender Zugriff";
    };
  }

  private void roles(Player p, UUID id, String name) {
    List<Button> b = new ArrayList<>();
    String[] labels = {
      "Bestehende Rechte / LuckPerms", "Volladmin", "Moderator", "Support", "Lesender Zugriff"
    };
    for (AdminAccessManager.Role role : AdminAccessManager.Role.values())
      b.add(
          button(
              labels[role.ordinal()],
              "Einzelrechte haben weiterhin Vorrang",
              () -> change(p, id, name, null, null, role, 0, "")));
    show(p, "Rolle: " + name, b, 0);
  }

  private void change(
      Player p,
      UUID id,
      String name,
      String node,
      Boolean value,
      AdminAccessManager.Role role,
      int page,
      String filter) {
    if (!permitted(p)) return;
    try {
      plugin.adminAccess.change(p, id, node, value, role);
      plugin.menuNotice(p, "§aAdminrechte gespeichert.");
    } catch (RuntimeException e) {
      plugin.menuError(p, "§c" + e.getMessage());
    }
    rights(p, id, name, page, filter);
  }

  void invalidate(UUID id) {
    text.remove(id);
    textRevision.remove(id);
  }

  private String adminText(Player p,String de,String en){return de;}

  void prompt(Player p, String title, String previous, Consumer<String> action) {
    if(plugin.appApi!=null && plugin.appApi.menus.prompt(p,title,previous,action)) return;
    if (previous.equals("null")) previous = "";
    long rev = plugin.adminAccess.revision(p.getUniqueId());
    if (plugin.isBedrockClient(p)
        && plugin.sendBedrockForm(
            p,
            SimplexAdminSuite.CustomForm.builder()
                .title(title)
                .input(adminText(p,"Neuer Wert","New value"), "", previous)
                .validResultHandler(
                    r ->
                        plugin.runAdminTask(
                            () -> {
                              if (permitted(p)
                                  && rev == plugin.adminAccess.revision(p.getUniqueId()))
                                applyInput(p, action, r.asInput(0));
                            })))) return;
    p.closeInventory();
    text.put(p.getUniqueId(), action);
    textRevision.put(p.getUniqueId(), rev);
    p.sendMessage("§e" + title + adminText(p,": Wert in den Chat schreiben; 'cancel' bricht ab.",": type the value in chat; 'cancel' aborts."));
  }

  boolean acceptText(Player p, String message) {
    Consumer<String> action = text.remove(p.getUniqueId());
    if (action == null) return false;
    Long rev = textRevision.remove(p.getUniqueId());
    plugin.runAdminTask(
        () -> {
          if (!permitted(p) || rev == null || rev != plugin.adminAccess.revision(p.getUniqueId()))
            return;
          if (message.equalsIgnoreCase("cancel")) open(p);
          else applyInput(p, action, message);
        });
    return true;
  }

  private void applyInput(Player p, Consumer<String> action, String value) {
    plugin.asAdmin(
        p.getName(),
        () -> {
          try {
            action.accept(value);
          } catch (IllegalArgumentException error) {
            plugin.menuError(p, "§cUngültige Eingabe: " + error.getMessage());
          }
        });
  }

  void detail(Player p, String title, String content, Runnable back) {
    if (!permitted(p)) return;
    if (plugin.isBedrockClient(p)) {
      var form =
          SimplexAdminSuite.SimpleForm.builder()
              .title(title)
              .content(content)
              .button(
                  "Zurück",
                  r ->
                      plugin.runAdminTask(
                          () -> {
                            if (permitted(p)) plugin.asAdmin(p.getName(), back);
                          }));
      if (plugin.sendBedrockForm(p, form)) return;
    }
    List<Button> items = new ArrayList<>();
    for (String line : content.split("\n"))
      items.add(info(line.substring(0, Math.min(60, line.length())), line, Material.PAPER));
    items.add(new Button("Zurück", title, Material.OAK_DOOR, back));
    show(p, title, items, 0);
  }

  private static String buttonPreview(String detail) {
    String first = detail.replace('\n', ' ');
    return first.length() > 100 ? first.substring(0, 97) + "…" : first;
  }

  void show(Player p, String title, List<Button> all, int requested) {
    showSized(p, title, all, requested, 20);
  }

  void showFixed(Player p, String title, List<Button> all) {
    showSized(p, title, all, 0, 28);
  }

  static String buttonIcon(Button b){return switch(b.icon()){
    case SHIELD -> "textures/items/empty_armor_slot_shield";case SPYGLASS -> "textures/items/spyglass";case CLOCK -> "textures/items/clock_item";
    case COMPASS -> "textures/items/compass_item";case TRIPWIRE_HOOK -> "textures/blocks/trip_wire_source";
    case DIAMOND_ORE,DIAMOND -> "textures/items/diamond";case CHEST -> "textures/blocks/chest_front";
    case NETHER_STAR -> "textures/items/nether_star";case WRITABLE_BOOK,BOOK -> "textures/items/book_writable";
    case LIME_DYE -> "textures/ui/confirm";case GRAY_DYE,BARRIER,REDSTONE_BLOCK -> "textures/ui/cancel";
    case OAK_DOOR,ARROW -> "textures/ui/arrow_left";case PLAYER_HEAD -> "textures/ui/icon_steve";
    case BELL -> "textures/items/villagebell";default -> MenuStyle.icon(b.title());};}

  private void showSized(Player p, String title, List<Button> all, int requested, int size) {
    if(plugin.appApi!=null && plugin.appApi.menus.show(p,title,all)) return;
    if (!permitted(p)) return;
    int page = Math.max(0, Math.min(requested, Math.max(0, (all.size() - 1) / size)));
    List<Button> b =
        new ArrayList<>(all.subList(page * size, Math.min(all.size(), page * size + size)));
    if (page > 0)
      b.add(button(adminText(p,"Vorherige Seite","Previous page"), "", () -> showSized(p, title, all, page - 1, size)));
    if ((page + 1) * size < all.size())
      b.add(button(adminText(p,"Nächste Seite","Next page"), "", () -> showSized(p, title, all, page + 1, size)));
    long rev = plugin.adminAccess.revision(p.getUniqueId());
    if (plugin.isBedrockClient(p)) {
      long expectedForm = plugin.nativeFormGeneration(p.getUniqueId()) + 1;
      SimplexAdminSuite.SimpleForm.Builder form =
          SimplexAdminSuite.SimpleForm.builder()
              .title(plugin.brandName()+" §8| §e" + title)
              .content(adminText(p,"§7Admin-Verwaltung §8• §f","§7Staff management §8• §f") + p.getName() + adminText(p,"\n§7Seite ","\n§7Page ") + (page + 1));
      for (Button item : b)
        form.button(
            "§a" + item.title + "\n§7" + buttonPreview(item.detail),
            buttonIcon(item),
            r ->
                plugin.runAdminTask(
                    () -> {
                      if (permitted(p)
                          && rev == plugin.adminAccess.revision(p.getUniqueId())
                          && expectedForm == plugin.nativeFormGeneration(p.getUniqueId())) {
                        long formGeneration = plugin.nativeFormGeneration(p.getUniqueId());
                        plugin.asAdmin(p.getName(), item.action);
                        if (item.informational()
                            && formGeneration == plugin.nativeFormGeneration(p.getUniqueId())
                            && !text.containsKey(p.getUniqueId())
                            && !MenuStyle.plain(item.title).equalsIgnoreCase("Schließen")
                            && permitted(p)
                            && rev == plugin.adminAccess.revision(p.getUniqueId()))
                          showSized(p, title, all, page, size);
                      }
                    }));
      if (plugin.sendBedrockForm(p, form)) return;
    }
    Holder h = new Holder(p.getUniqueId(), rev, b);
    Inventory inv =
        LiveMenus.create(
            h, 54, MenuStyle.inventoryTitle(plugin.brandText(title)));
    h.inventory = inv;
    for (int i = 0; i < 54; i++) inv.setItem(i, plugin.item(Material.GRAY_STAINED_GLASS_PANE, " "));
    inv.setItem(
        4,
        plugin.item(
            Material.NETHER_STAR,
            "&a&l" + title,
            "&7Simplex Admin Suite &8• &e" + plugin.getDescription().getVersion() + "",
            adminText(p,"&7Seite ","&7Page ") + (page + 1)));
    for (int i = 0; i < b.size(); i++) {
      Button v = b.get(i);
      int slot = i < 28 ? 10 + (i / 7) * 9 + i % 7 : 45 + (i - 28) * 8;
      h.slots.put(slot, v);
      java.util.List<String> lore = new java.util.ArrayList<>();
      for (String line : v.detail.split("\\n")) {
        while (line.length() > 46) {
          int split = line.lastIndexOf(' ', 46);
          if (split < 12) split = 46;
          lore.add("&7" + line.substring(0, split));
          line = line.substring(split).stripLeading();
        }
        lore.add("&7" + line);
      }
      lore.add(" ");
      lore.add(adminText(p,"&e➜ Auswählen","&e➜ Select"));
      inv.setItem(slot, plugin.item(v.icon, "&a" + v.title, lore.toArray(new String[0])));
    }
    inv.setItem(49, plugin.item(Material.OAK_DOOR, adminText(p,"&eAdmin-Zentrale","&eAdmin hub"), adminText(p,"&7Zurück zur Übersicht","&7Back to overview")));
    LiveMenus.show(p,inv);
  }

  @EventHandler
  public void click(InventoryClickEvent e) {
    if (!(LiveMenus.holder(e.getInventory()) instanceof Holder h)) return;
    e.setCancelled(true);
    if (!(e.getWhoClicked() instanceof Player p)
        || !h.player.equals(p.getUniqueId())
        || !permitted(p)
        || h.revision != plugin.adminAccess.revision(p.getUniqueId())) return;
    int i = e.getRawSlot();
    if (i == 49) {
      plugin.unifiedMenu.open(p);
      return;
    }
    Button selected = h.slots.get(i);
    if (selected != null)
      plugin.runAdminTask(
          () -> {
            if (p.getOpenInventory().getTopInventory() == h.inventory
                && LiveMenus.holder(h.inventory)==h
                && permitted(p)
                && h.revision == plugin.adminAccess.revision(p.getUniqueId()))
              plugin.asAdmin(p.getName(), selected.action);
          });
  }

  @EventHandler
  public void drag(InventoryDragEvent e) {
    if (LiveMenus.holder(e.getInventory()) instanceof Holder) e.setCancelled(true);
  }

  @EventHandler
  public void quit(PlayerQuitEvent e) {
    invalidate(e.getPlayer().getUniqueId());
  }

  private static final class Holder implements InventoryHolder {
    final UUID player;
    final long revision;
    final List<Button> buttons;
    final Map<Integer, Button> slots = new HashMap<>();
    Inventory inventory;

    Holder(UUID p, long r, List<Button> b) {
      player = p;
      revision = r;
      buttons = b;
    }

    public Inventory getInventory() {
      return inventory;
    }
  }
}
