# Admin Suite 10.5.0 – Werbe-Zentrale (ersetzt den Discord-Werbeplaner)

Plug & Play: JAR ersetzen. Bestehende Daten, Config-Werte, Schlüssel, Benutzer, Sitzungen und Bark-Abos bleiben
unverändert. Neu ist der Ordner `plugins/SimplexAdminSuite/ad-hub/` und der optionale Config-Schlüssel `ad-hub.enabled`
(Standard `true`; ein vorhandenes `discord-ads.enabled: false` wird weiterhin beachtet, solange `ad-hub.enabled` fehlt).

## Entfernt

- Discord-Token-Login, Server-/Kanalabfrage mit dem Nutzer-Account und **jedes automatische Posten** (Self-Bot).
- `/v1/discord-ads…` antwortet mit **410** und dem Hinweis, die App zu aktualisieren. Feature `discord-ads-v1` entfällt, neu ist `ad-hub-v1`.

## Übernahme beim ersten Start (ohne Datenverlust)

| Alt (`discord-ads/`) | Neu (`ad-hub/`) |
|---|---|
| Werbetexte | Texte im Format „Discord“ – gleiche IDs |
| Geplante Kanäle | Werbe-Orte mit Link `https://discord.com/channels/<Server>/<Kanal>`, Name „#kanal · Server“, gleichem Text, Intervall (längerer Slowmode wird übernommen), Pause-Status, Anzahl Posts |
| Letzter Post | Timer läuft ab dem letzten Post weiter |
| Accounts / Token | **gelöscht** |

Danach wird `discord-ads/state.json` durch `discord-ads/state-before-10.5.0-without-tokens.json` (ohne Token) ersetzt und
`discord-ads/secret.key` gelöscht. Eine nicht lesbare alte Datei bleibt unverändert liegen.

## Dateien

| Datei | Inhalt |
|---|---|
| `ad-hub/state.json` | Werbe-Orte, Texte, Timer (absolute Zeitpunkte), Verlauf, Einstellungen – atomar geschrieben |
| `ad-hub/link.key` | 256-Bit-Schlüssel für die Bark-Links (Rechte 600). Ohne ihn werden nur bereits verschickte Links ungültig |

## API

- `GET /v1/ad-hub` (`data.discord-ads`) – Übersicht mit Orten, Texten, Plattformen, Formaten, Einstellungen, Verlauf.
- `POST /v1/ad-hub` – `place.post` (Text + Link, Timer neu; `force` vor Fälligkeit, sonst 409), `place.snooze`,
  `place.undo`, `text.convert` (manage-/full-Zugriff); `place.save|pause|resume|due-now|delete`, `text.save|delete`,
  `text.convert` mit `save`, `settings.save` (`discord-ads.manage`).
- `POST /v1/ad-hub/link` **ohne Anmeldung** – `{p, c, k, op: open|peek|snooze}`. `k` ist eine HMAC über Server-ID, Ort und
  Erinnerungszyklus; gültig nur für den aktuellen Zyklus (ein zweiter Tipp innerhalb von 30 Min liefert den Text erneut,
  ohne den Timer doppelt zu starten). Erlaubt nichts außer Kopieren/Markieren, Vorschau und Snooze dieses einen Orts. 30/min je IP.

## Erinnerungen

- Planer-Thread alle 15 s, höchstens drei Erinnerungen pro Durchlauf.
- Fällig = `max(nächster Termin, Snooze)`. Erste Erinnerung sofort, Wiederholung nach `repeatMinutes` (Standard 60), höchstens `maxRepeats` (3).
- Nächster Termin nach dem Werben = Intervall + Sicherheitsaufschlag (Standard 1,5 %, mindestens 1 Min, höchstens 1 Std).
- Bark-Ereignis `ad-reminder` (Stufe zeitkritisch, ohne eigene Regel aktiv, kein Cooldown), Link `panel://ad-post?s=&p=&c=&k=`,
  Text als `copy` (bis 1800 Byte). Browser-Push öffnet `/#/ad-hub?post=<id>`.
- Empfänger: Ersteller des Orts (bei Konto-Umbenennung mitgezogen) oder alle mit `data.discord-ads`.

## Webpanel

- Neue Seite Studio → **Werbe-Zentrale** mit Vorschau je Plattform, „Jetzt werben“ (kopiert und öffnet in einem Klick).
- **Leistungsmodus**: automatisch bei ≤ 4 CPU-Threads + wenig RAM, Datensparmodus oder langsamer Darstellung (FPS-/Long-Task-Messung);
  ohne Unschärfe, Animationen und Schatten, halb so häufige Aktualisierung. Darstellung → Leistung: Automatisch/An/Aus.
- Stabiler: Zeitlimits für jede Anfrage, ein automatischer Wiederholversuch für Lesezugriffe bei Netzaussetzern/502/503/504,
  keine überlappenden Aktualisierungen, Offline-Hinweis, Zähler-Aktualisierung bei Rückkehr in den Tab.

## Tests

`tools/tests/Release1050Harness.java`: Timing, Formate und Umwandlung, Übernahme ohne Token, Erinnerungen mit Wiederholung
und Snooze, Bark-Link (öffnen, zweiter Tipp, Ablauf, falsche Signatur), Neustart, Konto-Umbenennung und die API-Rechte.
