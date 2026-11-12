import Foundation

/// Release notes shown once after an app update. Entries are static; nothing is fetched.
struct WhatsNewItem: Identifiable, Equatable {
    let symbol: String
    let title: String
    let detail: String
    var id: String { title }
}
struct WhatsNewRelease: Identifiable, Equatable {
    let version: String
    let title: String
    let summary: String
    let items: [WhatsNewItem]
    var id: String { version }
}

enum WhatsNew {
    static let lastSeenKey = "whatsNew.lastSeenVersion"
    static let releases: [WhatsNewRelease] = [
        WhatsNewRelease(version: "5.10.0", title: "Chat-Ruhe", summary: "Spieler können den Chat für sich stummschalten – mit /chat, auf Java und Bedrock. Du siehst in der App, wer das getan hat, und hilfst beim Stummschalten oder Aufheben. Dazu ein Fix für unsichtbare Spieler. Alle Daten bleiben erhalten.", items: [
            .init(symbol: "bell.slash.fill", title: "Chat für sich stummschalten", detail: "Mit /chat für 30 Minuten, 3 Stunden, 24 Stunden oder dauerhaft. Keine Chatnachrichten von Spielern und Plugins, keine Chat-Töne."),
            .init(symbol: "gamecontroller.fill", title: "Native Menüs mit Vorschau", detail: "Java bekommt einen echten Minecraft-Dialog (sonst ein Inventarmenü), Bedrock ein natives Formular mit Symbolen – auf Deutsch oder Englisch."),
            .init(symbol: "at", title: "Erwähnungen & Flüstern", detail: "Erwähnungen mit vollem Namen und Flüsternachrichten kommen weiter an – beides lässt sich zusätzlich stummschalten."),
            .init(symbol: "info.bubble.fill", title: "Hinweise für alle", detail: "Wer schreibt, obwohl der Chat stumm ist, sieht den Weg zurück. Wer einen stummen Spieler erwähnt, erfährt es – schon ab sechs Buchstaben des Namens."),
            .init(symbol: "person.2.badge.gearshape.fill", title: "Verwalten in App & Web", detail: "Neue Seite „Chat-Ruhe“: sehen, wer den Chat stummgeschaltet hat, und Spielern beim Stummschalten oder Aufheben helfen."),
            .init(symbol: "eye.fill", title: "Unsichtbare Spieler behoben", detail: "Java-Spieler traten manchmal unsichtbar bei oder blieben nach dem Vanish unsichtbar. Die Sichtbarkeit wird jetzt zuverlässig neu gesendet."),
            .init(symbol: "slider.horizontal.3", title: "Schöneres Präferenzmenü", detail: "Das Spieler-Profilmenü hat passende Symbole, Töne und Effekte – und die Chat-Ruhe direkt in den Einstellungen.")
        ]),
        WhatsNewRelease(version: "5.9.0", title: "PowerBot schlichtet & Disconnect-Forensik", summary: "PowerBot greift bei Streits stufenweise ein und erkennt Pöbeln in Sekunden. Verbindungsabbrüche werden bis auf die Netzwerkebene analysiert – auch wenn sie als „Disconnected“ gemeldet werden. Alle Daten und Funktionen bleiben erhalten.", items: [
            .init(symbol: "flame.fill", title: "Streits stufenweise schlichten", detail: "Erst beruhigen, dann öffentlich und persönlich warnen, danach 1 Minute Chatpause für die Beteiligten. Opfer und Unbeteiligte werden nie stummgeschaltet."),
            .init(symbol: "magnifyingglass.circle.fill", title: "Tiefenprüfung", detail: "Hört es nicht auf oder übertreibt jemand, holt sich PowerBot mehr Kontext (längerer Chat, Verlauf der letzten 24 Stunden) und entscheidet über 5 Minuten Pause."),
            .init(symbol: "megaphone.fill", title: "Pöbeln in Sekunden erkannt", detail: "Gerichtete Beleidigungen lösen sofort eine KI-Prüfung aus – auch wenn niemand zurückschreibt. Zusätzlich wird der Chat alle 15 Nachrichten im Zusammenhang analysiert."),
            .init(symbol: "speaker.wave.2.fill", title: "Laufende Streits & Chatpausen", detail: "Die PowerBot-Zentrale zeigt jede Stufe live, Pausen mit Countdown und lässt sie vorzeitig aufheben – einzeln oder für den ganzen Streit."),
            .init(symbol: "eye.trianglebadge.exclamationmark", title: "Verdeckte Disconnects", detail: "„Disconnected“ heißt nicht mehr automatisch freiwillig: Wer schloss die Verbindung, war der Client bis zuletzt aktiv, kam er sofort zurück, gab es TCP-Verluste?"),
            .init(symbol: "chart.bar.doc.horizontal.fill", title: "Wahrscheinlichste Ursachen", detail: "Jede Welle bekommt gewichtete Ursachen – etwa DDoS-Filter beim Hoster, feste Verbindungsdauer, regelmäßige Aufgabe, Plugin oder Paketverlust – mit nächstem Schritt."),
            .init(symbol: "antenna.radiowaves.left.and.right", title: "Live-Netzwerk", detail: "Jede Verbindung in Echtzeit mit Ping, RTT, Neuübertragungen, Datenrate und Client-Version – so siehst du, wer zuerst stockt."),
            .init(symbol: "sparkles", title: "KI springt ein", detail: "Bleibt eine Welle unklar oder verdeckt, startet die KI-Analyse automatisch mit allen Spuren, Hypothesen und der Netzwerk-Konfiguration.")
        ]),
        WhatsNewRelease(version: "5.8.0", title: "Invsee wie im Spiel", summary: "Inventare von Online- und Offline-Spielern bearbeiten, Items zwischen zwei Spielern verschieben, echte Texturen, eine eigene Schnellzentrale und eine KI, die Spieler auch bei Tippfehlern findet. Alle Daten bleiben erhalten.", items: [
            .init(symbol: "person.2.fill", title: "Zwei Spieler nebeneinander", detail: "Zweiten Spieler öffnen – online oder offline – und Items per Antippen verschieben, stapeln oder tauschen. Auf dem iPad nebeneinander."),
            .init(symbol: "moon.zzz.fill", title: "Offline-Inventare voll bearbeitbar", detail: "Auch Rüstung, Nebenhand und Endertruhe. Shulker-Inhalte, Verzauberungen und alle Itemdaten werden vollständig mitgenommen."),
            .init(symbol: "lock.shield.fill", title: "Nichts doppelt, nichts verloren", detail: "Jede Änderung prüft den aktuellen Stand, sichert die Spielerdatei und macht bei einem Fehler alles rückgängig. Die Ansicht aktualisiert sich von selbst."),
            .init(symbol: "photo.fill", title: "Echte Texturen", detail: "Truhen, Banner, Betten, Köpfe, Schilde, Treppen und animierte Blöcke werden wie im Spiel angezeigt – auch mit eigenem Ressourcenpaket."),
            .init(symbol: "info.circle.fill", title: "Item-Details", detail: "Gedrückt halten zeigt Verzauberungen, Haltbarkeit, Beschreibung und den Inhalt von Shulkerkisten."),
            .init(symbol: "bolt.circle.fill", title: "Schnellzentrale", detail: "Eigene Kacheln für Neustart, Wartung, Chat, Ankündigungen, Automationen und Werkzeuge – einzeln anpassbar, auch im Webpanel."),
            .init(symbol: "sparkles", title: "Schlauere KI", detail: "Findet Spieler auch bei anderer Schreibweise („Miss Caitlin“ → MissCaitlyn), fasst Verhalten und letzte Aktionen mit Uhrzeit zusammen und antwortet übersichtlicher."),
            .init(symbol: "person.badge.key.fill", title: "Rechteverwaltung repariert", detail: "Viele Einzelrechte lassen sich wieder speichern; im Webpanel mit Filtern, Gruppen und Änderungsübersicht.")
        ]),
        WhatsNewRelease(version: "5.7.0", title: "Inventare, Ablage & Automationen", summary: "Items zwischen Spielern verschieben, Gestohlenes sicher einbehalten, Neustarts nur bei leerem Server und eigene Wenn-dann-Regeln. Dazu ein schönerer KI-Chat. Alle Daten bleiben erhalten.", items: [
            .init(symbol: "square.grid.3x3.fill", title: "Inventar wie im Spiel", detail: "Live-Inventar und Endertruhe im echten Minecraft-Look mit Rüstung, Nebenhand und Hotbar – in App und Webpanel."),
            .init(symbol: "arrow.left.arrow.right", title: "Items verschieben", detail: "Ein Item antippen und an einen anderen Spieler geben – zum Beispiel Diebesgut von Spieler A zurück zu Spieler B. Menge und Grund wählbar."),
            .init(symbol: "archivebox.fill", title: "Einbehaltungsablage", detail: "Items mit Herkunft, Grund und Teammitglied sicher verwahren und später zurückgeben, weitergeben oder löschen. Auch im Spiel mit /sas ablage."),
            .init(symbol: "clock.arrow.2.circlepath", title: "Smart-Neustart", detail: "Zum Beispiel alle 6 Stunden, aber nur wenn höchstens 1 Spieler online ist – mit Countdown auf Deutsch und Englisch, Abbruch bei neuen Spielern und nie zwei Neustarts direkt hintereinander."),
            .init(symbol: "wand.and.stars", title: "Automationen", detail: "Wenn … dann …: Ingame-Tag, Uhrzeit, Intervall, Spielerzahl oder Erstbeitritt lösen Ansagen, Event-Status, Lockdown, Befehle oder einen Neustart aus. Mit Vorlagen."),
            .init(symbol: "sparkles", title: "Schönerer KI-Chat", detail: "Neue Sprechblasen, Tipp-Animation, Antworten erscheinen Wort für Wort, Chats durchsuchen, anpinnen, leeren und verwalten."),
            .init(symbol: "clock.badge.checkmark", title: "Uhrzeiten in KI-Antworten", detail: "Uhrzeiten wurden fälschlich als „[IP entfernt]“ ausgeblendet. Jetzt stehen dort wieder die echten Zeiten."),
            .init(symbol: "safari.fill", title: "Flüssigeres Webpanel", detail: "Neue Seiten für Ablage, Automationen und Smart-Neustart, sanftere Übergänge und schnellere lange Listen.")
        ]),
        WhatsNewRelease(version: "5.6.0", title: "Die neue PowerBot-Zentrale", summary: "Nachvollziehen, warum PowerBot eingreift, Spieler gezielt verwalten und morgens sofort sehen, was passiert ist. Alle Daten, Zugänge und dein OpenAI-Schlüssel bleiben erhalten.", items: [
            .init(symbol: "bubble.left.and.text.bubble.right.fill", title: "Jeder Fall mit Chatverlauf", detail: "Die betroffene Nachricht, was die anderen geschrieben haben, was PowerBot geantwortet hat – Streitpunkte farbig markiert."),
            .init(symbol: "sparkles", title: "Warum PowerBot eingegriffen hat", detail: "Zu jedem Eingriff eine kurze Begründung der KI, die Schwere des Streits und alle Beteiligten mit einem Tipp zum Profil."),
            .init(symbol: "person.2.badge.gearshape.fill", title: "Spieler einzeln verwalten", detail: "PowerBot-Akte je Spieler mit Verstoßzähler, Chatpause setzen oder aufheben und Zähler zurücksetzen."),
            .init(symbol: "slider.horizontal.3", title: "Strenge & Systemanweisungen", detail: "Gelassen, ausgewogen oder streng. Kleine Sticheleien bleiben erlaubt, schwere Streits werden eingedämmt. Systemanweisungen für Moderation und Chat-KI frei bearbeiten."),
            .init(symbol: "flame.fill", title: "Schwere Streits eindämmen", detail: "Nur bei wirklich schweren Streits bekommt der Verursacher eine kurze Chatpause – einstellbar und abschaltbar."),
            .init(symbol: "clock.arrow.circlepath", title: "Spielerverlauf neu", detail: "Chat, Flüstern, Aktionen, Kämpfe und Items in einer Zeitleiste – mit Suche, Filtern, Zeitraum, relativen Zeiten und markierten Nachrichten."),
            .init(symbol: "shippingbox.fill", title: "Items im Blick", detail: "Abgelegte, aufgehobene und weitergegebene Items sowie Tode mit Ort und Uhrzeit im Verlauf."),
            .init(symbol: "sun.horizon.fill", title: "Rückblick beim Öffnen", detail: "Neue Spieler, Eingriffe von PowerBot, Tickets, Reports und Serverprobleme seit deinem letzten Besuch – mit Glocke für Benachrichtigungen. Einstellbar."),
            .init(symbol: "square.stack.3d.up.fill", title: "Kurzbefehle & Startseite", detail: "PowerBot, KI-Assistent und Rückblick direkt auf der Startseite und als iOS-Kurzbefehl für den Home-Bildschirm."),
            .init(symbol: "rectangle.portrait.and.arrow.right", title: "Geräte sicher abmelden", detail: "Abmelden per Wischen bleibt sichtbar und stürzt nicht mehr ab. Die Bestätigung erscheint zuverlässig."),
            .init(symbol: "rectangle.3.group.fill", title: "Aufgeräumt", detail: "Werkzeuge nach Aufgaben sortiert, Moderation an einem Ort und eine übersichtlichere Startseite.")
        ]),
        WhatsNewRelease(version: "5.5.0", title: "Moderation & Übersicht", summary: "Chat, Admin-Aktionen, Reports und Bark an den passenden Stellen.", items: [
            .init(symbol: "text.bubble.fill", title: "Chatverlauf pro Spieler", detail: "Chat und berechtigte Flüsternachrichten mit Absender, Empfänger und Uhrzeit."),
            .init(symbol: "shield.lefthalf.filled", title: "Minecraft-Adminaktionen", detail: "Finde echte Minecraft-Admins und filtere ihre Aktionen."),
            .init(symbol: "bell.badge.fill", title: "Bark-Zentrale", detail: "Abonnements suchen, prüfen und einzeln einstellen.")
        ]),
        WhatsNewRelease(version: "5.4.1", title: "PowerBot-Stichwörter", summary: "Eigene Chat-Prüfregeln und aktuelle Ereignisse direkt auf der Startseite.", items: [
            WhatsNewItem(symbol: "text.magnifyingglass", title: "Eigene Stichwortregeln", detail: "Ganze Wörter oder Phrasen hinzufügen und die KI mit einer eigenen Regel prüfen lassen. SMP ist bereits hinterlegt."),
            WhatsNewItem(symbol: "bell.badge", title: "Aktuelle Ereignisse", detail: "Die neuesten Meldungen verbundener Server stehen direkt auf deiner Startseite.")
        ]),
        WhatsNewRelease(version: "5.4.0", title: "PowerBot-Fallzentrale", summary: "Chatmoderation, Teamübergabe und ein Rückblick für Simplex.", items: [
            WhatsNewItem(symbol: "shield.lefthalf.filled", title: "Ausgeführte Aktionen sehen", detail: "Blockierungen, Deeskalation und laufende Chatmutes im eigenen Protokoll öffnen."),
            WhatsNewItem(symbol: "person.badge.plus", title: "Fälle im Team übernehmen", detail: "Fälle zuweisen, Spieler öffnen, AutoMod-Mutes widerrufen und Vorgänge abschließen."),
            WhatsNewItem(symbol: "clock.arrow.circlepath", title: "Rückblick nach der Pause", detail: "Auf Wunsch zeigt ein Banner wichtige Vorfälle seit deinem letzten Besuch.")
        ]),
        WhatsNewRelease(version: "5.3.0", title: "Arts Update", summary: "Live-Inventare, Spieltag-Alarme und die Serverkonsole im Panel.", items: [
            WhatsNewItem(symbol: "square.grid.3x3.fill", title: "Inventare live öffnen", detail: "Item-Texturen, Hotbar, Ausrüstung und Endertruhe im Spielerprofil. Mit Freigabe lassen sich Stapel direkt tauschen."),
            WhatsNewItem(symbol: "terminal.fill", title: "Konsole durchsuchbar", detail: "Standardmäßig 30 Tage Archiv mit Datum, Suchbegriffen, Filtern und Löschoptionen."),
            WhatsNewItem(symbol: "calendar.badge.clock", title: "Event-Modus & Lockdown", detail: "Ingame-Tage für Erinnerungen, Event-Starts und Befehle planen. Bei Bedarf Beitritte einschränken."),
            WhatsNewItem(symbol: "sparkles", title: "KI mit anklickbaren Gerätedetails", detail: "Optionale Geräteinfos, eigener iOS-Kurzbefehl und zweisprachige Hilfe im Minecraft-Chat.")
        ]),
        WhatsNewRelease(version: "5.2.0", title: "Dein privater KI-Chat", summary: "Mit Nachrichtenverlauf, Spracheingabe und neuen Moderationsaktionen.", items: [
            WhatsNewItem(symbol: "bubble.left.and.bubble.right", title: "Mehrere Chats", detail: "Folgefragen im selben Chat stellen, Verläufe umbenennen oder automatisch löschen."),
            WhatsNewItem(symbol: "mic.fill", title: "Aufnehmen und anhören", detail: "Eine Aufnahme vorhören, transkribieren, den Text bearbeiten und separat abschicken."),
            WhatsNewItem(symbol: "checkmark.shield", title: "Spielerrechte moderieren", detail: "Rechte- und Befehlssperren prüfen und als Hauptadmin freigeben.")
        ]),
        WhatsNewRelease(version: "5.1.0", title: "KI-Assistent und Diagnose 3.0", summary: "Serverfragen gezielt beantworten und konkrete Aktionspläne zur Freigabe prüfen.", items: [
            WhatsNewItem(symbol: "sparkles", title: "KI für deinen Server", detail: "Chat, Tickets, Teamaktionen, Spielerprofile und Diagnose aus den passenden Datenquellen lesen."),
            WhatsNewItem(symbol: "checkmark.shield", title: "Freigabe durch den Hauptadmin", detail: "Schreibaktionen werden als feste Liste mit Zielen und Werten vorgelegt. Der Hauptadmin bestätigt den gesamten Plan einmalig."),
            WhatsNewItem(symbol: "waveform.path.ecg", title: "Diagnose 3.0", detail: "Bei verfügbaren Paper-Verbindungsdaten zeigen Disconnects die letzten Pakettypen, KeepAlive-Zeiten und Netty-Fehler.")
        ]),
        WhatsNewRelease(version: "5.0.0", title: "Dein Panel, deine Ansicht", summary: "Kompakte Schnellzugriffe, echte NBT-Spielerinfos und schnellere Plugin-Updates.", items: [
            WhatsNewItem(symbol: "slider.horizontal.3", title: "Server-Schnellzugriffe anpassen", detail: "Werkzeuge auswählen und sortieren, Server umbenennen oder ausblenden. Gekoppelte Suite-Konten synchronisieren App und Webpanel."),
            WhatsNewItem(symbol: "doc.text.magnifyingglass", title: "Spielerdatei im Profil", detail: "Ersten Beitritt laut NBT, Datei-Zeitstempel und weitere gespeicherte Werte direkt sehen."),
            WhatsNewItem(symbol: "arrow.up.circle", title: "Plugin-Update mit Fortschritt", detail: "Die gekoppelte Suite prüft und bestätigt das Update serverseitig. Der Transfer zeigt den Fortschritt."),
            WhatsNewItem(symbol: "waveform.path.ecg", title: "Aktuellere Live-Aktivitäten", detail: "Mit direktem Widget-Zugang kann iOS gelegentlich auch bei geschlossener App neue Werte holen.")
        ]),
        WhatsNewRelease(version: "4.9.1", title: "Dein Server. Ein Tipp.", summary: "Feste Server-Schnellzugriffe und zuverlässiges Verbinden – ohne endloses Laden.", items: [
            WhatsNewItem(symbol: "bolt.fill", title: "Direkt zum richtigen Server", detail: "Jeder gespeicherte Suite-Zugang hat eigene Kacheln für Admin Suite, Werbung, Spieler und Tickets. Ein Tipp öffnet genau diesen Server ohne zusätzliche Auswahl."),
            WhatsNewItem(symbol: "slider.horizontal.3", title: "Schnellzugriffe anpassen", detail: "Server benennen, Werkzeuge je Server auswählen und einen Standard für allgemeine Zugriffe festlegen. Die bisherigen Kopplungen und Zugangsdaten bleiben erhalten."),
            WhatsNewItem(symbol: "wifi.exclamationmark", title: "Kein endloses Verbinden", detail: "Kurzes Zeitlimit auch bei gepinnten Zertifikaten, Abbrechen und Wiederholen. Ein Ausfall der zusätzlichen Funktionsliste blockiert die übrigen Werkzeuge nicht mehr."),
            WhatsNewItem(symbol: "square.stack.3d.up.fill", title: "Kurzbefehle mit Serverzuordnung", detail: "Auch „Werbeverwaltung öffnen“ kann einen festen Server erhalten. Gespeicherte Werbe-Orte werden ohne lange Netzabfrage aufgelöst; Rechte und Intervalle werden weiterhin in der App geprüft.")
        ]),
        WhatsNewRelease(version: "4.9.0", title: "Alles schneller erreichen", summary: "Mehr Schnellzugriffe, iOS-Kurzbefehle und alle Admin-Werkzeuge an einem durchsuchbaren Ort – im vertrauten Design.", items: [
            WhatsNewItem(symbol: "square.grid.2x2.fill", title: "Direkt auf der Startseite", detail: "Admin Suite, Werbung, Tickets, Diagnose, Admin-Log, Verläufe und Staff-Chat sind sofort erreichbar – auch ohne Hosting-Panel. Bei mehreren Zugängen kannst du einen Standard wählen."),
            WhatsNewItem(symbol: "megaphone.fill", title: "Werbung per Kurzbefehl", detail: "Die Werbeverwaltung oder einen bestimmten Werbe-Ort vom Home-Bildschirm öffnen. „Werbung vorbereiten“ kopiert den aktuellen Text und öffnet den Ort; Pausen, Intervalle und Rechte bleiben gültig."),
            WhatsNewItem(symbol: "magnifyingglass", title: "Alle Werkzeuge finden", detail: "Ein gemeinsamer Werkzeugkatalog für Admin-Tab und Direktzugänge, nach Aufgaben gruppiert, mit Favoriten und Suche über Werkzeuge, Einstellungen und weitere Funktionen."),
            WhatsNewItem(symbol: "archivebox.fill", title: "Verläufe sicher aufräumen", detail: "Admin-Log, Ereignisse, Werbe-Verlauf, Staff-Chat und Serverchat durchsuchen, einzelne oder mehrere Einträge löschen und Verläufe leeren – in App und Web. Vor jeder Löschung entsteht eine wiederherstellbare Sicherung."),
            WhatsNewItem(symbol: "arrow.uturn.backward", title: "Einfach aktualisieren", detail: "App-Zugänge, Einstellungen und Werbe-Timer bleiben erhalten. Das Webpanel kommt mit der JAR.")
        ]),
        WhatsNewRelease(version: "4.8.0", title: "Werbe-Zentrale", summary: "Statt automatisch zu posten erinnert dich Bark, wenn du wieder werben darfst. Ein Tipp kopiert den passenden Text und öffnet Discord, Reddit, Planet Minecraft, Facebook & Co. – du musst nur noch einfügen.", items: [
            WhatsNewItem(symbol: "megaphone.fill", title: "Alle Werbe-Orte an einem Platz", detail: "Discord-Kanal-Link, Subreddit, Planet-Minecraft-Seite, Facebook-Gruppe, Telegram, WhatsApp, X oder Serverliste – jeweils mit Werbetext und wie oft du dort posten darfst (z. B. alle 6 Std)."),
            WhatsNewItem(symbol: "bell.badge.fill", title: "Bark-Erinnerung", detail: "Ist ein Ort wieder dran, kommt eine Bark-Mitteilung. Tippen öffnet die App, kopiert den Text in die Zwischenablage und öffnet direkt den Discord-Kanal in der Discord-App bzw. die Seite."),
            WhatsNewItem(symbol: "clock.arrow.circlepath", title: "Snooze & Timer wie gewohnt", detail: "Später erinnern (15 Min bis 1 Tag), automatische Wiederholung bis geworben wurde. Danach startet der Timer leicht über dem Intervall neu (6 Std → etwa 6,1 Std); „Rückgängig“ nimmt einen Fehltipp zurück."),
            WhatsNewItem(symbol: "textformat", title: "Passende Formate mit Vorschau", detail: "Discord, Reddit-Markdown, BBCode für Planet Minecraft, WhatsApp/Telegram, Facebook mit Unicode-Fett oder reiner Text – mit Vorschau im Look der Plattform und Umwandeln per Tipp."),
            WhatsNewItem(symbol: "lock.shield.fill", title: "Kein Discord-Token mehr", detail: "Der Token-Login (Self-Bot) wurde komplett entfernt; der gespeicherte Token wurde auf dem Server gelöscht. Deine Texte und Kanäle sind als Werbe-Orte übernommen.")
        ]),
        WhatsNewRelease(version: "4.7.0", title: "Discord-Werbeplaner (in 4.8.0 ersetzt)", summary: "Werbetexte mit Discord-Formatierung und Vorschau. Das automatische Posten wurde in 4.8.0 durch die Werbe-Zentrale mit Bark-Erinnerung ersetzt.", items: [
            WhatsNewItem(symbol: "link.badge.plus", title: "Discord-Account verknüpfen", detail: "Zweitaccount per Token verbinden, prüfen, Token erneuern oder abmelden. Der Token liegt nur verschlüsselt auf dem Server."),
            WhatsNewItem(symbol: "textformat", title: "Werbetexte mit Vorschau", detail: "Benennen, bearbeiten, kopieren und löschen – mit Formatierungsleiste (fett, kursiv, Überschriften, Zitate, Listen, Spoiler, Links, Emojis) und Vorschau im Discord-Look."),
            WhatsNewItem(symbol: "megaphone.fill", title: "Kanäle mit eigenem Timer", detail: "Alle Discord-Server des Accounts sehen und bis zu vier Kanäle pro Server wählen, jeweils mit eigenem Text und Intervall – mit Warnung, ob Werbung dort wirklich erlaubt ist."),
            WhatsNewItem(symbol: "clock.badge.checkmark.fill", title: "Nie zu früh", detail: "Jeder Post kommt etwas nach dem Intervall (24 Std → etwa 24,1–24,5 Std), nie davor. Slowmode wird erkannt und eingehalten."),
            WhatsNewItem(symbol: "bell.badge.fill", title: "Fehler per Bark", detail: "Fehlende Rechte, Captcha, abgemeldeter Token oder von Timer-Bots gelöschte Posts werden erkannt – mit Bark-Hinweis und automatischer Pause."),
            WhatsNewItem(symbol: "arrow.clockwise.circle.fill", title: "Neustartfest", detail: "Timer sind feste Zeitpunkte auf dem Server – ein Neustart setzt nichts zurück, verpasste Posts werden kurz danach nachgeholt."),
            WhatsNewItem(symbol: "lock.fill", title: "Eigene Rechte", detail: "„Discord-Werbung ansehen“ und „verwalten“ stecken in keiner Rollenvorlage.")
        ]),
        WhatsNewRelease(version: "4.6.0", title: "Studio, Welten-Trimmer & Steckbrief", summary: "Discord-Status und Scoreboard mit Vorlagen und Live-Vorschau gestalten, Welten sicher trimmen und alles über einen Spieler auf einen Blick – in App und Webpanel.", items: [
            WhatsNewItem(symbol: "paintpalette.fill", title: "Discord-Status-Designer", detail: "Texte, Farben, Felder, Bilder und Bot-Aktivität bearbeiten – mit Discord-Vorschau für Online, Instabil, Überlastet, Neustart und Offline. Sieben Vorlagen; „Simplex Standard“ ist der bisherige Status."),
            WhatsNewItem(symbol: "list.bullet.rectangle.fill", title: "Scoreboard-Designer", detail: "Titel und Zeilen mit Farbcodes, Platzhaltern und Hex-Farbverlauf bearbeiten. Die Minecraft-Vorschau nutzt echte Werte vom Server; sieben Vorlagen inklusive Standard."),
            WhatsNewItem(symbol: "scissors", title: "Welten-Trimmer in der App", detail: "Dimensionen wählen, Besuchsgrenze und Countdown festlegen, Fortschritt live verfolgen, Notfallentscheidung und Recovery – mit Backup-Bestätigung und eigenem Recht."),
            WhatsNewItem(symbol: "checkmark.shield.fill", title: "Sicherer trimmen", detail: "Optionale Sicherheitskopie mit automatischer Wiederherstellung, Integritätsprüfung aller geänderten Regionsdateien und ein Wächter für hängende MCA-Selector-Läufe."),
            WhatsNewItem(symbol: "person.text.rectangle.fill", title: "Steckbrief im Spielerprofil", detail: "Identität, Aktivität, Client, Bedrock-Gerät, Netzwerk, Moderation, Position und letzter Verlauf – durchsuchbar und je nach deinen Rechten."),
            WhatsNewItem(symbol: "gamecontroller.fill", title: "Mehr über Clients", detail: "Bedrock: Trennungsgrund aus Geyser, Drittanbieter-Name, Skin-Engine, Floodgate-Verknüpfung. Java: Beitrittsadresse, Server-Transfer und Ressourcenpaket-Status."),
            WhatsNewItem(symbol: "square.grid.2x2.fill", title: "Aufgeräumte Übersicht", detail: "Die Admin-Suite-Startseite ist nach Aufgaben gegliedert: Spieler, Server & Diagnose, Kommunikation & Studio, Verwaltung."),
            WhatsNewItem(symbol: "paperplane.fill", title: "Bark-Test jederzeit", detail: "In den Bark-Einstellungen sofort eine Testnachricht senden – auch in Ruhezeiten und schon vor dem Speichern eines neuen Schlüssels."),
            WhatsNewItem(symbol: "lock.fill", title: "Neue Rechte, nicht vorbelegt", detail: "Trimmer, Discord-Status und Scoreboard haben eigene Rechte. Sie stecken in keiner Rollenvorlage – nur Vollzugriff oder eine gezielte Freigabe schaltet sie frei.")
        ]),
        WhatsNewRelease(version: "4.5.0", title: "Diagnosezentrale", summary: "Disconnect-Wellen erkennen und einordnen, Lag finden, Spark-Profile auswerten und Abstürze nachvollziehen – mit KI-Hilfe und Bark-Alarm.", items: [
            WhatsNewItem(symbol: "terminal.fill", title: "Befehle-Schalter repariert", detail: "Einzelne Befehle eines Spielers ließen sich nicht umschalten („Pflichtfeld fehlt: grant“). Erlauben, Sperren und Zurücksetzen funktionieren wieder."),
            WhatsNewItem(symbol: "wifi.exclamationmark", title: "Disconnect-Analyse", detail: "Jede Trennung mit Grund, Ping, Ort und Sitzungsdauer. Drei oder mehr in kurzer Zeit – oder ein großer Teil der Spieler – werden zum Vorfall mit Einschätzung „legitim“ oder „Fehler“ und konkreten Schritten."),
            WhatsNewItem(symbol: "bell.badge.fill", title: "Bark-Alarm", detail: "Disconnect-Wellen, Abstürze und Server-Hänger als kritischer oder dringlicher Hinweis – für alle, die es in den Bark-Ereignissen aktivieren."),
            WhatsNewItem(symbol: "sparkles", title: "KI-Einschätzung", detail: "Mit dem hinterlegten OpenAI-Schlüssel: kurze, IP-bereinigte Auszüge statt ganzer Logs. Kritische Vorfälle und Abstürze werden automatisch geprüft."),
            WhatsNewItem(symbol: "map.fill", title: "Lag-Karte", detail: "Wo stehen viele Mobs, Items oder Trichter? Hotspots je Chunk mit Typen, nächstem Spieler und Teleport-Befehl, dazu Chunk-Generierung pro Spieler."),
            WhatsNewItem(symbol: "flame.fill", title: "Spark-Profiler", detail: "Profil aus der App starten oder einen Spark-Link einfügen. Der Server wertet es selbst aus: Plugins nach Rechenzeit, teuerste Stellen, Befunde."),
            WhatsNewItem(symbol: "bolt.trianglebadge.exclamationmark.fill", title: "Absturz-Manager", detail: "Erkennt nach dem Neustart echte Abstürze (Watchdog, Speicher voll, Java-VM) und harte Beendigungen – mit den letzten Konsolenzeilen."),
            WhatsNewItem(symbol: "stethoscope", title: "Volldiagnose", detail: "TPS, Tickzeiten, RAM, GC, CPU, Speicherplatz, Plugins, Konsolenfehler, Mojang, DNS und Internet in einem Durchlauf – mit Punktzahl."),
            WhatsNewItem(symbol: "person.crop.circle.badge.checkmark", title: "Verbindung im Spielerprofil", detail: "Trennungen der letzten 7 Tage und ob die Verbindung eines Spielers gerade instabil ist.")
        ]),
        WhatsNewRelease(version: "4.4.0", title: "Live-Schalter & Befehle", summary: "Jeder Schalter zeigt jetzt den echten Stand – an = erlaubt, aus = gesperrt – und speichert sofort. Dazu Befehle pro Spieler und eine vollständige LuckPerms-Verwaltung.", items: [
            WhatsNewItem(symbol: "checkmark.seal.fill", title: "Bestätigungen repariert", detail: "Änderungen an Rängen, LuckPerms-Rechten, Lands und Sperren schlugen mit „Bestätigung abgelaufen“ fehl. Das ist behoben."),
            WhatsNewItem(symbol: "switch.2", title: "Echte Schalter", detail: "Bereiche, Einzelrechte und LuckPerms-Rechte zeigen live, was erlaubt ist. Ausschalten sperrt sofort mit der gewählten Dauer, Einschalten gibt frei."),
            WhatsNewItem(symbol: "terminal.fill", title: "Befehle pro Spieler", detail: "Alle Befehle des Servers – sortiert nach Art, Plugin oder A–Z – einzeln oder gruppenweise erlauben und sperren, mit Dauer, Grund und Alt-Vererbung. Auch für alle Spieler auf einmal."),
            WhatsNewItem(symbol: "crown.fill", title: "Rang im Profil", detail: "Das Spielerprofil zeigt Präfix, Suffix, primäre Gruppe und alle Gruppen. Gruppen direkt zuweisen, befristen oder ersetzen."),
            WhatsNewItem(symbol: "sparkles", title: "LuckPerms-Vorlagen", detail: "Spieler, VIP, Builder, Supporter, Moderator und Admin mit Gewicht, Präfix und passenden Rechten in einem Schritt anlegen."),
            WhatsNewItem(symbol: "person.2.fill", title: "Alt-Accounts erben Befehlssperren", detail: "Standardmäßig an, pro Sperre abschaltbar. Eine Freigabe beim Alt hat Vorrang.")
        ]),
        WhatsNewRelease(version: "4.3.1", title: "Rechte & Sperren an einem Ort", summary: "Keine doppelten Rechte mehr: Alles läuft über eine Rechteverwaltung – direkt aus dem Spielerprofil, mit Dauer für jedes Recht.", items: [
            WhatsNewItem(symbol: "lock.shield.fill", title: "Eine Rechteverwaltung", detail: "Spielerprofil → Rechte & Sperren zeigt Bereiche (Truhen, Türen, Shulker … mit Land/Wildnis) und alle weiteren Rechte wie Chat, PvP, Nether oder Freeze zusammen."),
            WhatsNewItem(symbol: "clock.fill", title: "Jedes Recht mit Dauer", detail: "„Sperren …“ bietet 30 Minuten bis 30 Tage oder dauerhaft – ohne Umweg über ein anderes Menü."),
            WhatsNewItem(symbol: "magnifyingglass", title: "Spieler überall live suchen", detail: "Jedes Spielerfeld schlägt beim Tippen passende Spieler vor – ein Teil des Namens genügt, online und offline."),
            WhatsNewItem(symbol: "person.2.fill", title: "Alt-Accounts erben Sperren", detail: "Sperren gelten auch für bestätigte und künftig erkannte Alt-Accounts. Du siehst, wer erbt und woher eine Sperre stammt."),
            WhatsNewItem(symbol: "crown.fill", title: "Rang im Profil", detail: "Gruppen vergeben – auch befristet – und entfernen direkt unter Rechte & Sperren."),
            WhatsNewItem(symbol: "arrow.triangle.merge", title: "Alte Sperren übernommen", detail: "Frühere Einzelsperren für Truhen, Türen & Co. bleiben gültig, erscheinen im passenden Bereich und werden mit ihm aufgehoben.")
        ]),
        WhatsNewRelease(version: "4.3.0", title: "Großes Rechte-Update", summary: "Spielern einzelne Rechte gezielt entziehen – temporär oder dauerhaft, in App, Webpanel, Discord und im Spielmenü für Java und Bedrock.", items: [
            WhatsNewItem(symbol: "lock.fill", title: "Rechte-Sperren", detail: "Einzelne Schalter für Truhen, Shulker, andere Behälter, Endertruhen, Türen, Redstone, Arbeitsblöcke, Rahmen, Tiere, Bauen und fremde Items. Gesperrtes kann weder geöffnet, abgebaut noch gesprengt werden – auch nicht in vertrauten Ländern."),
            WhatsNewItem(symbol: "map.fill", title: "Eigenes Land & Wildnis", detail: "Je Sperre wählbar: überall, nur im eigenen Land (als Besitzer) oder eigenes Land und Wildnis erlaubt."),
            WhatsNewItem(symbol: "diamond.fill", title: "Klauer-Vorlage", detail: "Wertvolle Items anderer Spieler (Diamanten, Netherite, Shulker, Verzaubertes …) nur noch im eigenen Land aufheben, Behälter nur noch im eigenen Land öffnen."),
            WhatsNewItem(symbol: "clock.badge.checkmark", title: "Temporär oder dauerhaft", detail: "Sperren laufen von selbst ab. Der Spieler sieht bei jedem Versuch, was gesperrt ist, warum und wie lange noch."),
            WhatsNewItem(symbol: "paintpalette.fill", title: "Präfixe mit Vorschau", detail: "Präfix und Suffix für Gruppen und einzelne Spieler mit Farbpalette, Hex-Farben, Vorlagen und Live-Vorschau wie im Chat."),
            WhatsNewItem(symbol: "text.magnifyingglass", title: "Rechte-Vorschläge", detail: "Beim Hinzufügen schlägt die App alle bekannten Rechte der Plugins vor – kein Abtippen mehr. Rechte lange drücken oder wischen zum Entfernen."),
            WhatsNewItem(symbol: "person.badge.key.fill", title: "Rechte einfach erklärt", detail: "App-Benutzerrechte nach Bereichen sortiert, mit kurzer Erklärung und Rollen-Vorlagen für Supporter, Moderator und Admin.")
        ]),
        WhatsNewRelease(version: "4.2.0", title: "Ränge, Lands & ruhiger Admin-Log", summary: "Selbst einstellen, was als ungelesen zählt, Benutzername ändern, LuckPerms und Lands verwalten – und Tickets ohne deinen Login-Namen.", items: [
            WhatsNewItem(symbol: "line.3.horizontal.decrease.circle", title: "Admin-Log-Filter", detail: "Nur Team, ausgewählte Admins, alle oder gar keine Zahlen – Aktionen normaler Spieler bleiben sichtbar, aber still. Push folgt demselben Filter."),
            WhatsNewItem(symbol: "person.text.rectangle", title: "Name & Anzeige", detail: "Benutzernamen ändern, ohne neu anmelden zu müssen. Spieler sehen bei Ticketantworten nur deinen öffentlichen Namen oder „Team“."),
            WhatsNewItem(symbol: "crown.fill", title: "Ränge & Rechte", detail: "LuckPerms-Gruppen, Präfixe, Gewichte, Rechte, Vererbung und Spielergruppen direkt in der App – jede Änderung mit Bestätigung."),
            WhatsNewItem(symbol: "map.fill", title: "Lands", detail: "Alle Länder auf einen Blick und welche Admins überall Zugriff haben – Bypass und Lands-Admin per Schalter."),
            WhatsNewItem(symbol: "list.bullet.rectangle", title: "Ränge getrennt einstellbar", detail: "Rang über dem Kopf und in der Tabliste einzeln; ist ein TAB-Plugin installiert, bleibt die Tabliste unangetastet."),
            WhatsNewItem(symbol: "bubble.left.and.text.bubble.right.fill", title: "Schönerer Serverchat", detail: "Sprechblasen mit Herkunft (Minecraft, Discord, App) – auch im Webpanel, das jetzt Push-Benachrichtigungen und Home-Bildschirm-Installation kann.")
        ]),
        WhatsNewRelease(version: "4.1.0", title: "Konten, 2FA & Webpanel", summary: "Benutzername statt Schlüssel, Zwei-Faktor-Schutz, Passkeys, Geräteverwaltung und das Admin-Panel im Browser.", items: [
            WhatsNewItem(symbol: "crown.fill", title: "Hauptadmin-Konto", detail: "Wechsle unter Konto & Sicherheit vom Hauptschlüssel zu Benutzername und Passwort. Alle Daten bleiben erhalten; der alte Schlüssel funktioniert weiter, bis du ihn widerrufst."),
            WhatsNewItem(symbol: "lock.shield.fill", title: "Zwei-Faktor-Schutz", detail: "6-stellige Codes aus Apple Passwörter oder jeder Authenticator-App, dazu Wiederherstellungscodes. Funktioniert sofort, auch ohne Domain."),
            WhatsNewItem(symbol: "person.badge.key.fill", title: "Passkeys", detail: "Mit eigener Domain meldest du dich per Face ID an – in der App und im Browser."),
            WhatsNewItem(symbol: "laptopcomputer.and.iphone", title: "Geräte & Sitzungen", detail: "Als Hauptadmin siehst du alle angemeldeten Geräte mit Modell und IP, kannst sie umbenennen, abmelden oder vorübergehend sperren."),
            WhatsNewItem(symbol: "safari.fill", title: "Webpanel", detail: "Das Admin-Panel läuft jetzt auch im Browser – auf demselben Port, mit denselben Benutzern und Rechten, für Handy und PC."),
            WhatsNewItem(symbol: "network", title: "Adresse & Domain", detail: "Die Verbindungsadresse lässt sich ohne neue Anmeldung auf deine Domain umstellen.")
        ]),
        WhatsNewRelease(version: "4.0.0", title: "Simplex Panel 4", summary: "Admin-Log, Staff-Chat mit Discord, Setup-Links, Ruhezeiten und eine kompaktere, schönere Oberfläche.", items: [
            WhatsNewItem(symbol: "list.bullet.clipboard.fill", title: "Admin-Log je Admin", detail: "Jede Admin-Aktion mit Ort, Ziel und Zeit – Teleports, Vanish, Strafen und mehr. Ungelesene Einträge als Zahl, alles mit einem Tipp gelesen."),
            WhatsNewItem(symbol: "bubble.left.and.text.bubble.right.fill", title: "Staff-Chat", detail: "Dein Discord-Staffkanal in der App: Bilder, Dateien und Sprachnachrichten in beide Richtungen, gesendet in deinem Namen, Admins einzeln oder alle pingen."),
            WhatsNewItem(symbol: "link.badge.plus", title: "Setup-Links", detail: "Einmalige Links (Standard 1 Stunde) richten den Admin-Zugang auf einem anderen iPhone ohne Eingaben ein."),
            WhatsNewItem(symbol: "moon.zzz.fill", title: "Ruhezeiten", detail: "Schlafenszeit und Arbeitszeit für Bark. Nach einem Notfallneustart kommt ein kritischer Hinweis – du entscheidest, ob er durchdringen darf."),
            WhatsNewItem(symbol: "rectangle.3.group.fill", title: "Neue Widgets & Design", detail: "Kompaktere Kopfzeilen, Schnellmenü, Admin-Suite-Live-Karte, schönere Diagramme, Schnellaktionen und eine Startanimation."),
            WhatsNewItem(symbol: "crown.fill", title: "Ränge über dem Namen", detail: "LuckPerms-Präfix, Suffix und Rangfarbe über dem Kopf und in der Tabliste – für Java und Bedrock.")
        ]),
        WhatsNewRelease(version: "3.3.0", title: "Zugänge & Beitrittshinweise", summary: "Verifizierungen verwalten, einzelne App-Rechte vergeben und Server-RAM absichern.", items: [
            WhatsNewItem(symbol: "person.badge.key.fill", title: "App-Benutzer", detail: "Benutzername und Passwort ergänzen die bestehenden Schlüssel. Rechte sind einzeln einstellbar; jeder berechtigte Nutzer verwaltet sein eigenes Bark-Gerät."),
            WhatsNewItem(symbol: "link", title: "Discord-Verifizierungen", detail: "Vorhandene Chat-Verifizierungen anzeigen, ändern, löschen oder manuell anlegen."),
            WhatsNewItem(symbol: "bell.badge", title: "Beitrittshinweise", detail: "Verifizierte Discord-Nutzer können bis zu zehn Spieler vormerken. Spieler verwalten Anfragen und deaktivieren Hinweise mit /joinnotify."),
            WhatsNewItem(symbol: "memorychip", title: "RAM-Notfallneustart", detail: "Der begrenzte Server-Container wird getrennt vom Java-Heap angezeigt. Bei dauerhaft hoher Nutzung startet auf Wunsch ein übersetzter Countdown.")
        ]),
        WhatsNewRelease(version: "3.2.0", title: "Simplex Panel 3.2", summary: "Bilder in Tickets, Benachrichtigungen je Dringlichkeit und deutlich schnellere Statistiken.", items: [
            WhatsNewItem(symbol: "photo.on.rectangle.angled", title: "Bilder & Dateien in Tickets", detail: "Anhänge aus Discord erscheinen mit Vorschau im Ticket. Du kannst selbst Bilder und Dateien senden – sie landen direkt im Discord-Ticket."),
            WhatsNewItem(symbol: "at", title: "Spieler erwähnen", detail: "Auf Wunsch erwähnt der Bot den Spieler im Discord-Ticket – nach deiner Bestätigung."),
            WhatsNewItem(symbol: "bell.badge.fill", title: "Bark je Dringlichkeit", detail: "Niedrig still, Normal normal, Hoch als dringende Mitteilung, Notfall als kritischer Alarm – Ruhezeiten werden beachtet."),
            WhatsNewItem(symbol: "xmark.bin.fill", title: "Aufräumen", detail: "Wartende Zustellungen verwerfen, Tickets direkt löschen (Discord-Thread inklusive) und Spielerwarnungen verwerfen."),
            WhatsNewItem(symbol: "chart.bar.xaxis", title: "Schnellere Statistiken", detail: "Alle Profile in einer Anfrage und sofortige Anzeige des letzten Stands."),
            WhatsNewItem(symbol: "arrow.triangle.2.circlepath", title: "Plugin-Updates", detail: "GitHub-Releases als Quelle, weniger erneutes Scannen und zuverlässiger Geyser-/Floodgate-Status.")
        ]),
        WhatsNewRelease(version: "3.1.0", title: "Simplex Panel 3.1", summary: "Support-Tickets für Minecraft, Discord und die App, ein dauerhafter Serverchat und deutlich schlauere Plugin-Updates.", items: [
            WhatsNewItem(symbol: "ticket.fill", title: "Support-Tickets", detail: "Spieler öffnen Tickets mit /ticket (Java-Menüs, native Bedrock-Formulare) oder über das Discord-Panel. Du antwortest in der App, im Spiel oder auf Discord – Antworten warten, bis der Spieler online ist."),
            WhatsNewItem(symbol: "bell.and.waves.left.and.right.fill", title: "Bark für Tickets", detail: "Neue Tickets kommen per Bark, Notfall-Tickets außerhalb der Ruhezeit als kritischer Alarm."),
            WhatsNewItem(symbol: "stethoscope", title: "Discord-Diagnose", detail: "Ticketkanal, Kategorie und Teamrollen direkt in der App einrichten – mit Prüfung, welche Bot-Rechte fehlen. Läuft über den vorhandenen Admin-Suite-Bot."),
            WhatsNewItem(symbol: "bubble.left.and.text.bubble.right.fill", title: "Serverchat bleibt", detail: "Die letzten 50 Nachrichten mit Spielerköpfen bleiben erhalten, auch nach dem Schließen der App. Leeren per Papierkorb mit Bestätigung."),
            WhatsNewItem(symbol: "puzzlepiece.extension.fill", title: "Plugin-Zentrale", detail: "Das Puzzleteil auf der Serverseite zeigt Update-Status, Geyser & Floodgate und deine Schnellzugriffe."),
            WhatsNewItem(symbol: "arrow.triangle.2.circlepath", title: "Schlauere Plugin-Updates", detail: "Hangar und GeyserMC als neue Quellen, Zuordnungsvorschläge, alle geprüften Updates auf einmal vormerken, freie Spigot-Downloads ohne Anmeldung und ein Fix für die hängende Cloudflare-Prüfung.")
        ]),
        WhatsNewRelease(version: "3.0.0", title: "Simplex Panel 3.0", summary: "Die Admin Suite ist jetzt direkt in die Serveransicht eingebaut: ein Ort für Spieler, Moderation und Serverbetrieb.", items: [
            WhatsNewItem(symbol: "shield.lefthalf.filled", title: "Neue Admin-Zentrale", detail: "Eigener Tab mit Live-Status, offenen Reports, Banns, Wartung, Neustart, Team online und allen Suite-Werkzeugen auf einen Blick."),
            WhatsNewItem(symbol: "person.crop.rectangle.stack.fill", title: "Spielerverwaltung neu gedacht", detail: "Spielerköpfe, Status-Badges für Bann, Stummschaltung und Warnungen, Wischaktionen sowie neue Filter „Auffällig“ und „Neu“."),
            WhatsNewItem(symbol: "bolt.circle.fill", title: "Schnellaktionen im Profil", detail: "Nachricht, Kick, Bann und Warnung direkt oben im Spielerprofil – weiterhin mit serverseitiger Vorschau und Bestätigung."),
            WhatsNewItem(symbol: "star.fill", title: "Favoriten & zuletzt angesehen", detail: "Markiere wichtige Spieler mit einem Stern und springe über die Admin-Zentrale direkt zu zuletzt geöffneten Profilen."),
            WhatsNewItem(symbol: "list.bullet.clipboard.fill", title: "Moderationsakte", detail: "Warnungen, Notizen, frühere Namen und Spielzeit erscheinen gesammelt im Profil."),
            WhatsNewItem(symbol: "checkmark.shield.fill", title: "Plug & Play", detail: "Gespeicherte Server, Suite-Kopplungen, Schlüssel und Layouts bleiben erhalten. Funktioniert mit Admin Suite 9.3.0 und älteren Suite-Versionen.")
        ])
    ]
    static var current: String { Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "" }
    static func release(for version: String) -> WhatsNewRelease? { releases.first { $0.version == version } ?? releases.first { $0.version == majorMinor(version) + ".0" } }
    /// Fresh installs are only marked as seen; updates from any earlier version present the notes once.
    static func shouldPresent(current: String, lastSeen: String?, existingUser: Bool) -> Bool {
        guard release(for: current) != nil else { return false }
        guard let lastSeen, !lastSeen.isEmpty else { return existingUser }
        return compare(lastSeen, current) == .orderedAscending
    }
    static func compare(_ a: String, _ b: String) -> ComparisonResult {
        let left = a.split(separator: ".").map { Int($0) ?? 0 }, right = b.split(separator: ".").map { Int($0) ?? 0 }
        for index in 0..<max(left.count, right.count) {
            let x = index < left.count ? left[index] : 0, y = index < right.count ? right[index] : 0
            if x != y { return x < y ? .orderedAscending : .orderedDescending }
        }
        return .orderedSame
    }
    static func majorMinor(_ version: String) -> String { version.split(separator: ".").prefix(2).joined(separator: ".") }
}
