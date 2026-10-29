# Simplex Admin Suite 9.5.0 · Ticket-Anhänge, Bark-Stufen, Client-Erkennung

Für Simplex Panel 3.2.0, kompatibel mit allen Daten aus 9.4.0: bestehende Tickets, Ticketeinstellungen (`tickets.*`), Bark-Regeln, Chatverlauf, Discord-Verknüpfungen und App-Schlüssel bleiben unverändert. Neu ist nur `tickets/files/` für Anhänge; alte Ticketdateien werden ohne Umwandlung gelesen.

## Neu

- **Anhänge:** Bilder/Dateien aus Discord-Tickets (max. 4 je Nachricht, 8 MiB) werden gespeichert und der App über `GET /v1/ticket-file` bereitgestellt. `POST /v1/tickets/upload` (manage) sendet Dateien aus der App als Teamantwort mit echtem Discord-Anhang. Im Spiel erscheint ein Hinweis auf den Anhang.
- **Erwähnen:** `reply` mit `mention:true` pingt den Ersteller im Discord-Ticket (nur dieser Nutzer, keine anderen Erwähnungen).
- **Zustellung verwerfen:** Ticketaktion `discard` (eine Nachricht oder alle wartenden).
- **Löschen:** löscht den Discord-Thread/-Kanal; ohne Recht dazu wird er archiviert/gesperrt.
- **Bark je Priorität:** Ereignisse `ticket-low` (still), `ticket` (normal), `ticket-high` (zeitkritisch), `ticket-urgent` (kritisch). Eigene Regeln aus der App haben Vorrang; Ruhezeiten gelten.
- **Zustellung im Spiel:** Ton und Actionbar nur für den Spieler; Bell-Ton bei hoher/Notfall-Priorität; Zeilenumbrüche bleiben erhalten.
- **Client-Brands:** Versionen und Loader werden erkannt (z. B. `lunarclient:v2.23.11-2644,fabric` → „Lunar Client · Fabric“); unbekannte Bestandteile bleiben auffällig.
- **Warnungen verwerfen:** Aktion `player.warnings` (einzeln mit Textprüfung oder alle) mit Vorschau und Bestätigung.
- **Statistiken:** `GET /v1/data/statistics-rows` liefert alle Profile samt letztem Gerät in einer Anfrage (20 s zwischengespeichert).
- **Geyser/Floodgate:** Status folgt Weiterleitungen und zeigt die echte Fehlerursache.
- Features: `tickets-v2`, `statistics-v2`, `player-warnings-v1`.

## Installation

Server stoppen, Ordner `plugins/SimplexAdminSuite` sichern, JAR ersetzen, Datenordner behalten, starten. Für Thread-Löschung braucht der Bot „Threads verwalten“ (Thread-Modus) bzw. „Kanäle verwalten“ (Kanal-Modus) – `/ticket diagnose` prüft das.

## Geprüft

Alle Regressionstests plus `Release950Harness` (Brands, Bark-Stufen, Anhänge inkl. Pfadschutz und Größenlimit, Verwerfen, Kompatibilität mit 9.4.0-Ticketdateien, Aufräumen beim Löschen).
