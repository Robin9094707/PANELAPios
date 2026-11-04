# Admin Suite 10.3.0 – Studio, Welten-Trimmer & Steckbrief

Plug & Play: JAR ersetzen. Bestehende Daten, Config-Werte, Schlüssel, Benutzer und Sitzungen bleiben unverändert.
Neue Config-Schlüssel (`world-trimmer.safety.*`, `discord-bot.status.design.show-world-info/show-server-load/author-name/thumbnail-url/image-url`)
gelten mit Standardwerten auch ohne Eintrag – **das Verhalten bleibt wie bisher**, bis jemand etwas speichert.

## Neue Rechte (in keiner Rollenvorlage)

| Recht | Bedeutung |
|---|---|
| `data.world-trim` | Welten-Trimmer: Status, Dimensionen, Verlauf ansehen |
| `world-trim.run` | Trimmer ausführen (kritisch) – zusätzlich manage- oder full-Zugriff |
| `data.discord-status` / `discord-status.edit` | Discord-Status-Designer ansehen / speichern |
| `data.scoreboard` / `scoreboard.edit` | Scoreboard-Designer ansehen / speichern |

Supporter, Moderator und Admin erhalten diese Rechte **nicht** automatisch; nur Vollzugriff (`*`) oder eine gezielte Freigabe.

## Welten-Trimmer in App und Webpanel

- `GET/POST /v1/world-trim` (`scan`, `download`, `start`, `cancel`, `continue`, `abort`, `recovery`). Der Start verlangt
  `backupConfirmed: true` und das eingetippte Wort `TRIMMEN`. Ausgeführt wird über denselben Operator-Weg wie Discord:
  Vorabprüfung (Werkzeug, freier Speicher), Countdown, Kick, Speicher-/Entlade-Barriere, Notfallentscheidung nur durch
  den Auslöser, Recovery. Nichts davon wurde gelockert.
- **Sicherheitskopie (optional, pro Lauf wählbar):** kopiert `region`, `poi` und `entities` der gewählten Dimensionen
  vor dem Lauf (Speicherprüfung: Größe + 10 % + Mindestfrei). Schlägt MCA Selector fehl, hängt er oder findet die
  Integritätsprüfung eine defekte Datei, werden die Dateien automatisch zurückgeschrieben (atomar je Datei).
  Standard ohne App-Auswahl: `world-trimmer.safety.snapshot-regions: false` (wie bisher).
- **Integritätsprüfung** nach jedem Lauf: alle während des Laufs geänderten `.mca`-Dateien (Header vollständig, Chunks
  innerhalb der Datei, plausible Länge, bekannte Komprimierung inkl. LZ4 und externer `.mcc`-Chunks). Defekt und keine
  Kopie → Recovery statt automatischem Neustart, damit niemand auf einer beschädigten Welt spielt.
- **Hänger-Wächter:** meldet MCA Selector `world-trimmer.safety.stall-timeout-minutes` (60) lang nichts, wird er
  beendet und der Lauf sicher als fehlgeschlagen behandelt. `performance.heap` wird auf Formate wie `3G`/`2048M` geprüft.

## Studio

- `GET/POST /v1/studio/discord-status`: alle Felder der Statusnachricht und der Bot-Aktivität, sieben Vorlagen
  („Simplex Standard“ = bisheriger Status), `preview` baut die Nachricht mit dem echten Builder für Online, Instabil,
  Überlastet, Neustart und Offline, ohne zu speichern oder zu senden. Neu: Autorzeile, Thumbnail/Bild (nur https),
  Felder „Spieltage & Uhrzeit“ und „CPU & RAM“ abschaltbar.
- `GET/POST /v1/studio/scoreboard`: Titel, bis zu 15 Zeilen, sieben Vorlagen (Standard = bisheriges Scoreboard),
  Platzhalter und echte Beispielwerte für die Vorschau.

## Spieler

- `GET /v1/player/dossier?player=`: Steckbrief mit Identität, Aktivität, Client, Bedrock-Gerät, Geräten, Netzwerk &
  Alts, Moderation, Position und letztem Chat/Aktionen. Abschnitte richten sich nach den Rechten des Betrachters
  (`data.devices`, `data.alts`, `data.links`, `data.chat`).
- Java: Beitrittsadresse (`getVirtualHost`), Server-Transfer und Ressourcenpaket-Status. Bedrock: Trennungsgrund aus
  Geyser, Drittanbieter-Name, Skin-Geometrie-Engine, Editor-Modus, Cape-Abmessungen, Floodgate-Verknüpfung.

## Bedrock-Brücke

- Behoben: `Could not deliver Bedrock identity metadata to SimplexAdminSuite: HTTP 422`. Pakete ohne Namen und UUID
  bekommen die Floodgate-UUID aus der XUID; ohne jede Identität antwortet die Suite `202 deferred` statt 422 (auch für
  Brücke 1.5.0). Brücke 1.6.0 sendet solche Pakete gar nicht mehr und meldet Trennungen (`snapshotPhase=DISCONNECT`).

## Bark-Test

- `POST /v1/bark/test` (`key` optional): sendet sofort eine Testnachricht an den eigenen bzw. eingetippten Bark-Schlüssel –
  unabhängig vom Aktiv-Schalter, Ereignisregeln, Ruhezeiten und Sperrzeiten; höchstens fünf pro Minute. Antwort mit
  klarer Ursache (kein/ungültiger Schlüssel, Bark lehnt ab, api.day.app nicht erreichbar). Feature `bark-test-v1`.

## Client-Prüfung

- Katalog mit CheckHacks 1.3.1 (30.08.2026) abgeglichen: alle Signaturen von 1.3.0 sind enthalten, 1.3.1 brachte keine
  neuen. Für Neuinstallationen sind Wurst, LiquidBounce und Aristois (bei CheckHacks wegen Fehlalarmen entfernt) nur noch
  Diagnose. Bestehende `client-probes.yml` werden nicht verändert.

## Webpanel

- Neue Seiten **Welten-Trimmer**, **Studio → Discord-Status** und **Studio → Scoreboard**, Steckbrief im Spielerprofil,
  Schnellsuche (Strg/⌘ + K) für Seiten und Spieler, Navigation mit eigener Gruppe „Ränge & Rechte“.

## Tests

`Release1030Harness` (239 Checks): Regionsprüfung mit beschädigten Dateien, Sicherheitskopie und Wiederherstellung,
Heap-Prüfung, Vorlagen und Validierung, Standardwerte = config.yml, Vorschau mit echtem Builder, Speichern mit Rechten,
Steckbrief nach Rechten, Floodgate-UUID, neue Felder, Rechte nicht in Rollenvorlagen. Alle bisherigen Harnesses laufen unverändert.
