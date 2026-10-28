# Simplex Admin Suite 9.2.0 · Adminrechte & App-Anbindung

Mit Admin Suite 9.2.0 verwenden. App-Bundle-ID, Schlüsselbund, gespeicherte Server, Layouts und Einstellungen bleiben unverändert.

- Admin-Team zeigt nur bestätigten Admin-Zugang: Operatoren, lokale Rollen/Einzelrechte und aufgelöste Bukkit-/LuckPerms-Rechte. Normale Spieler mit persönlichen Einstellungen werden ausgeblendet. Offline-Rechte werden asynchron geladen; die Liste zeigt den laufenden Abgleich.
- Spieler gezielt hinzufügen. Deutsche Rechtegruppen, Beschreibungen und ausdrückliche Regeln mit Rückkehr zur Vererbung.
- Vorlagen: Volladmin, Moderator, Support, nur lesen, Junior-Admin (nur Kick), Zeitbann-Moderator (bis drei Tage), kein Zugang. Eigene Rollen lassen sich erstellen und bearbeiten.
- Maximale Banndauer in Sekunden: `259200` = drei Tage, `0` = unbegrenzt, `-1` = keine Banns. Eigene Rollen verwenden ein gemeinsames Rollenlimit. Alt-Account-Banns brauchen eine zusätzliche Freigabe und passende Einzelrechte. Bestehende ausdrückliche Einzelrechte bleiben bei Rollenwechseln erhalten.
- Live-Serverchat mit letzten 20 öffentlichen Nachrichten, Spielernamen/Köpfen und Versand als Server oder frei gewähltem Anzeigenamen. App-Versand wird mit dem tatsächlichen API-Zugang protokolliert. Nachrichten sind reiner Text; keine Konsolenbefehle. Lesen mit App-Zugang, Senden nur mit `full`. Private Nachrichten/Teamchat werden nicht gesammelt. Verlauf nur im Arbeitsspeicher; beim Verlassen/Schließen der Ansicht oder App verworfen. „Verlauf leeren“ wirkt lokal. Die Kopfansicht lädt öffentliche Avatare über mc-heads.net.
- Neustart mit 1–60 Sekunden Countdown, Deutsch-/Englisch-Grund, Spieler-Lokalisierung, Titel und Sounds. Abbruch während des Countdowns über die App. Nur `full`; jede Steueraktion verwendet Vorschau und Bestätigung.
- Nether-/End-Sperren blockieren auch Home-/Plugin-Teleports; beim Rechteentzug werden betroffene Online-Spieler in die Oberwelt versetzt.

## Installation

1. Server stoppen und bisherige JAR sowie kompletten Ordner `plugins/SimplexAdminSuite` sichern.
2. Neue `SimplexAdminSuite-9.2.0.jar` einsetzen. Datenordner behalten, dann Server starten.
3. IPA mit derselben Bundle-ID und Signieridentität über die installierte App aktualisieren. App vorher nicht löschen. IPA ist unsigniert und muss zum Installieren selbst signiert werden.

Die Rechte-Migration ergänzt nur den Versionsmarker. Vorhandene Rollen, UUIDs und Einzelrechte bleiben erhalten; vor der ersten Änderung wird `admin-access-before-9.2.0.properties` atomar gespeichert. Profile, Datenbank, Alt-/IP-Regeln und API-Zugänge werden nicht zurückgesetzt.

Der Neustart verwendet den bestehenden Paper-/Spigot-Befehl `restart` und dessen bisherigen Stop-Fallback. Für automatisches Wiederhochfahren muss das Hosting bzw. das konfigurierte Restart-Skript eingerichtet sein.
