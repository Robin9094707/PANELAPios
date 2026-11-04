# Admin Suite 10.2.0 – Diagnosezentrale & Befehle-Fix

Plug & Play: JAR ersetzen. Bestehende Daten werden nicht umgeschrieben; neue Daten liegen in
`plugins/SimplexAdminSuite/diagnostics/` (Herzschlag, Konsolenspiegel, Disconnects, Vorfälle, Absturzberichte,
Spark-Analysen). Neue Config-Abschnitte `diagnostics:` und `performance:` gelten mit Standardwerten auch ohne Eintrag.

## Behoben

- `commands.update`: `alts` und `grant` sind optional (`alts` leer = ja, `grant` leer = nein). Vorher lehnte die API
  jeden Schalter der App/des Webpanels mit „Pflichtfeld fehlt: grant/alts“ ab. Ungültige Werte werden weiter geprüft.

## Diagnose

- **Konsolen-Mitschnitt** (Log4j-Appender, nur lesend) mit 1500 Zeilen; die letzten 500 werden laufend nach
  `diagnostics/live-log.txt` gespiegelt. IP-Adressen werden in allen API-Antworten und KI-Eingaben maskiert.
- **Sampler** (1 Hz): TPS, Ø/längster Tick (aus Paper-Tickzeiten), Heap, GC-Zeit, CPU, Spieler; 15 Minuten Verlauf,
  Lag-Spitzen ab `diagnostics.spike-ms` (1000), Server-Hänger ab 5 s oder Watchdog-Meldung → Ereignis `lag-spike`.
- **Disconnects**: Quit-Grund, Kick-Ursache/-Text und „… lost connection: …“ aus der Konsole ergeben die Kategorie.
  Vorfälle: `diagnostics.burst` (3 in 10 s) oder `diagnostics.wave` (35 % der Spieler in 30 s); Kicks, Banns und
  Neustarts zählen nicht. Bewertung legitim/Fehler/unklar mit Ursache, Belegen und Schritten
  (`DisconnectAnalyzer`). Instabile Spieler ab `diagnostics.flapping.count` (3) unfreiwilligen Trennungen in 10 min.
- **Bark & Browser-Push**: Ereignisse `disconnect-wave`, `crash`, `lag-spike` (nur für Konten mit
  `data.diagnostics`); Stufe kritisch bzw. dringlich, eigene Regeln und Ruhezeiten haben Vorrang. Legitime Wellen
  nur mit `diagnostics.notify-legit: true`.
- **Absturz-Manager**: Herzschlag mit `clean`-Flag. Beim Start: Watchdog („has stopped responding“),
  `OutOfMemoryError`, Tick-Loop-Ausnahme, neue `hs_err_pid*.log`/`crash-reports` → Absturzbericht; ohne Spur, aber
  ohne sauberes Herunterfahren → „Unerwartet beendet“ (Sicherheit mittel). Normale Neustarts erzeugen nichts.
- **Lag-Karte**, **Volldiagnose** (inkl. DNS, TCP zu Mojang/Cloudflare, Mojang-hasJoined), **Spark** (Start/Stop
  über die Konsole, Upload-Link wird automatisch übernommen; Import per Link/Code; streamender Protobuf-Parser
  `SparkProfile`, auch für mehrere hundert MB, mit Leerlauf-Abzug und Zuordnung zur tiefsten Plugin-Stelle).
- **KI** (`diagnostics.ai.*`): vorhandener OpenAI-Schlüssel, `store=false`, höchstens ~9 KB Eingabe, Limit pro Stunde
  (`max-per-hour: 20`), automatisch für kritische Vorfälle und Abstürze.
- API: `GET /v1/diagnostics/{overview,disconnects,incident,lagmap,crashes,spark,full,logs,ai,player}`,
  `POST /v1/diagnostics/{full,analyze,spark,incident,settings}`. Rechte `data.diagnostics`, `diagnostics.run`,
  `diagnostics.manage` (Einstellungen zusätzlich Vollzugriff). Features `diagnostics-v1`, `lag-map-v1`, `spark-v1`,
  `crash-manager-v1`, `player-connection-v1`, `command-rights-v2`.
- Webpanel: neue Seite **Diagnose** (Live, Disconnects, Lag-Karte, Spark, Abstürze, Volldiagnose, Konsole,
  Einstellungen) und die Karte **Verbindung & Disconnects** im Spielerprofil.

## Leistung

- Spielerprofile werden im Server-Thread nicht mehr synchron als YAML geschrieben, sondern gebündelt ~1 s später im
  Hintergrund (gleiches Format, atomarer Austausch, beim Stoppen synchron). Im Spark-Profil vom 03.11. war das der
  größte Tick-Anteil der Suite. Abschaltbar mit `performance.async-profile-saves: false`.

## Tests

`Release1020Harness` (92 Checks, +1 mit einem echten Spark-Profil über `SPARK_PROFILE`): Kategorien, Bewertungen (legitim, Server-Lag, Watchdog, Java-only-Netzwerk,
Paketfehler am selben Ort, Shutdown), Bursts/Flapping, Konsolen-Zuordnung vor/nach dem Quit, Absturzerkennung und
sauberer Neustart, IP-Maskierung, synthetisches Spark-Profil (Leerlauf, Plugin-Zuordnung, Fenster, Befunde, kaputte
Datei), Diagnose-API mit Rechten über HTTPS und die Befehle-Schalter über den echten Validator. Alle bisherigen
Harnesses laufen unverändert.
