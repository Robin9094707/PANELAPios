# Simplex Panel 5.6.0 und Admin Suite 10.13.0

## Behoben

- **Geräte abmelden** (Admin Suite → Konto → Geräte & Sitzungen): Die App stürzt beim Abmelden nicht mehr ab. Die Wisch-Aktion „Abmelden“ verschwindet nicht mehr sofort, die Bestätigung erscheint zuverlässig. Wird das eigene Gerät abgemeldet, lädt die Liste nicht mehr mit einem abgelaufenen Zugang neu. Dasselbe Wisch-Problem ist auch bei Rängen, LuckPerms-Rechten, Verläufen, Diagnose-Archiv, Staff-Aufgaben und Stichwortregeln behoben.

## PowerBot-Zentrale

- **Fälle mit Beweisen:** Neue Einträge speichern die betroffene Nachricht, die Begründung der KI (kurz, auf Deutsch), die Antwort von PowerBot, die Schwere (leicht bis schwer) und den öffentlichen Chatverlauf der letzten zwei Minuten. Die Nachrichten, die den Streit ausmachen, sind markiert. Flüsternachrichten sind nur mit `data.player-whispers` sichtbar.
- **Filter & Suche:** Wichtig, Offen, Blockiert, Streits, Hinweise, Chatpausen, System, Alles; Suche nach Spieler, Grund oder Nachricht; Gruppierung nach Tagen und Statistik der letzten 24 Stunden.
- **Spieler verwalten:** Liste aller Spieler mit PowerBot-Einträgen, Verstoßzähler, Chatpause setzen/aufheben und Zähler zurücksetzen. Teamnotizen und „Wieder öffnen“ für Fälle.
- **Strenge & Systemanweisungen:** `gelassen`, `ausgewogen` (Standard) oder `streng`. Kleine Sticheleien bleiben erlaubt; Streits werden erst ab der passenden Schwere zum Fall. Die Systemanweisungen für Moderation und Chat-KI lassen sich in der App bearbeiten und auf den Standard zurücksetzen. Sicherheitsregeln und Ausgabeformat bleiben fest eingebaut.
- **Schwere Streits eindämmen:** Nur bei Schwere „schwer“ erhält der Verursacher eine kurze Chatpause (Standard 5 Minuten, höchstens einmal in 30 Minuten, nie für Teammitglieder). Abschaltbar unter „Schwere Streits eindämmen“.

## Spielerverlauf

- Chat, Flüstern, Aktionen, Kämpfe, Items und PowerBot-Entscheidungen in einer Zeitleiste mit Filtern, Zeitraum (1 Std., 24 Std., 7 Tage, alles), Suche, relativen Zeiten („vor 5 Min.“) und markierten Nachrichten. Markierte Einträge öffnen direkt den PowerBot-Fall.
- Neu erfasst: abgelegte, aufgehobene und weitergegebene Items sowie Tode mit Ort. Bestehende Spielerprofile werden unverändert weiterverwendet; der neue Verlauf wird zusätzlich unter `recent-items` gespeichert.

## Rückblick & Startseite

- Beim Öffnen zeigt die App, was seit dem letzten Besuch passiert ist: neue und aktive Spieler, PowerBot-Eingriffe, Tickets, Reports und Serverprobleme. Mit Glocke zur Bark-Zentrale. Abwesenheit (1–12 Std.) und Bereiche sind unter **Rückblick → Einstellungen** wählbar.
- Startseite aufgeräumt: PowerBot, KI-Assistent und Rückblick als eigene Kacheln, Seltenes unter „Mehr“. Werkzeuge sind in „Übersicht & Verläufe“ und „Moderation & PowerBot“ neu gruppiert.
- Neue Kurzbefehle: **PowerBot-Zentrale öffnen** und **Rückblick öffnen** (auch mit festem Server), zusätzlich zu „KI-Assistent öffnen“.

## Kompatibilität

- `powerbot-actions.json`, Stichwortregeln, OpenAI-Schlüssel, App-Zugänge, TLS-Identität, Bark und alle Spielerdaten bleiben unverändert gültig. Alte PowerBot-Einträge erscheinen weiter, nur ohne Chatverlauf.
- Neue Einstellungen in `config.yml` unter `powerbot`: `strictness`, `conflict-actions`, `conflict-mute-minutes`, `system-prompt`, `assistant-prompt`. Fehlen sie, gelten die Standardwerte.
- Die App 5.6.0 funktioniert auch mit Admin Suite 10.12.0; neue Bereiche erscheinen erst nach dem Suite-Update. SimplexSentinelBridge 1.6.0 muss nicht ersetzt werden.

## Update

1. Server stoppen, `plugins/SimplexAdminSuite` sichern.
2. `SimplexAdminSuite-10.13.0.jar` statt der alten JAR einsetzen, Server starten.
3. Die unsignierte IPA wie gewohnt mit derselben Signatur und Bundle-ID über die bestehende App installieren.
