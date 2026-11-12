# Simplex Panel 5.10.0 und Admin Suite 10.17.0

Baut auf 5.9.0 / 10.16.1 auf (PowerBot-Eskalation, Disconnect-Forensik und der Fix gegen Massen-Disconnects sind enthalten). Wer 5.9.0 / 10.16.x – oder noch 5.8.0 / 10.15.0 – installiert hat, ersetzt nur JAR und IPA – alle Daten (Spielerprofile, Einstellungen, Ablage, Automationen, KI-Chats, Konten, Rechte und Verknüpfungen) bleiben erhalten. Die Chat-Ruhe speichert ihre Daten in einer neuen Datei `plugins/SimplexAdminSuite/chat-silence.yml`. SentinelBridge 1.6.0 bleibt unverändert.

## Chat-Ruhe – den Chat für sich stummschalten

Java- und Bedrock-Spieler schalten den öffentlichen Chat mit einem Befehl für sich stumm:

| Befehl (Deutsch) | Befehl (Englisch) | Wirkung |
| --- | --- | --- |
| `/chat` | `/chat` | Menü mit Vorschau öffnen |
| `/chat aus [30m\|3h\|24h\|dauerhaft]` | `/chat off [30m\|3h\|24h\|permanent]` | Chat stummschalten (ohne Dauer: bis man ihn wieder einschaltet) |
| `/chat an` | `/chat on` | Chat wieder einschalten |
| `/chat flüstern an\|aus` | `/chat whispers on\|off` | Flüsternachrichten erlauben / stummschalten |
| `/chat erwähnungen an\|aus` | `/chat mentions on\|off` | Erwähnungen erlauben / stummschalten |
| `/chat status` | `/chat status` | Aktuellen Stand zeigen |

Weitere Aliasse: `/chatruhe`, `/ruhe`, `/stumm`, `/mutechat`, `/chatmute`, `/chatbreak`. Ist `/chat` bereits von einem anderen Plugin belegt, funktioniert immer `/simplexchat`.

- **Menüs**: Java bekommt standardmäßig einen nativen Minecraft-Dialog (Paper-Dialog-API) mit Vorschau, Dauer-Auswahl und Schaltern. Klappt das nicht (ältere Clients) oder steht `chat-silence.java-menu: inventory` in der `config.yml`, öffnet sich ein Inventarmenü. Bedrock bekommt native Floodgate-Formulare mit Symbolen und ein Formular „Alles auf einmal einstellen“. Alles auf Deutsch oder Englisch nach Spielersprache.
- **Dauer**: 30 Minuten, 3 Stunden, 24 Stunden oder dauerhaft. Zeitlich begrenzte Ruhe endet automatisch mit Hinweis und Ton.
- **Was stumm ist**: Chatnachrichten anderer Spieler, Chatnachrichten von Plugins (Beitritte, Tode, Ankündigungen …) und Chat-Töne. Action-Bar, Titel, Menüs und Antworten auf eigene Befehle bleiben sichtbar.
- **Erwähnungen**: Wer mit vollem Namen erwähnt wird, sieht die Nachricht trotzdem – mit einem Hinweis und (Java) einem Klick-Link bzw. (Bedrock) dem Befehl, um auch Erwähnungen stummzuschalten.
- **Selbst schreiben**: Während der Chat-Ruhe wird eine öffentliche Nachricht nicht gesendet. Stattdessen erscheint „Du hast den Chat stummgeschaltet“ mit Klick-Link (Java) bzw. `/chat an` (Bedrock).
- **Flüstern**: Optional stumm. Dann wird `/msg`, `/w`, `/r` usw. an diesen Spieler abgelehnt und der Absender informiert.
- **Hinweis für andere**: Wer einen Spieler mit Chat-Ruhe erwähnt – auch nur mit mindestens sechs Buchstaben des Namens – und dieser ist online, bekommt einen kurzen Hinweis. Versteckte/vanished Spieler werden dabei nicht verraten.
- **Profil**: `/profil` → Einstellungen enthält jetzt die Chat-Ruhe. Das Präferenzmenü hat passende Symbole (auch auf Bedrock), Töne, Partikel und eine Action-Bar-Bestätigung.

## Verwalten in App und Webpanel

- Neue Seite **Chat-Ruhe** (App: Werkzeuge → Kommunikation, Serverchat; Web: Community → Chat-Ruhe): wer den Chat stummgeschaltet hat, wie lange, ob selbst oder vom Team, online/offline, Java/Bedrock.
- Admins können für Spieler, die nicht wissen wie es geht, den Chat stummschalten oder wieder einschalten und Flüstern/Erwähnungen einstellen – auf Wunsch mit Info an den Spieler im Spiel.
- Neue Rechte: `data.chat-silence` (ansehen) und `chat-silence.manage` (ändern). Beide sind in den Rollenvorlagen Supporter, Moderator und Admin enthalten; bestehende Benutzer bekommen sie nicht automatisch.

## Unsichtbare Java-Spieler behoben

- Der Vanish-Paketfilter hat Spieler, die von anderen Plugins kurz versteckt waren, mitgefiltert und Einträge aus früheren Sitzungen behalten. Dadurch gingen Spawn- und Tab-Pakete verloren: Java-Spieler traten manchmal unsichtbar bei oder blieben nach dem Beenden von Vanish unsichtbar.
- Jetzt filtert die Suite nur noch ihre eigenen Verstecke, vergisst Spieler beim Verlassen und Beitreten und sendet einen Spieler automatisch neu, sobald er wieder sichtbar sein soll.

## Update

1. Server stoppen, `plugins/SimplexAdminSuite` sichern.
2. `SimplexAdminSuite-10.17.0.jar` statt der alten JAR einsetzen, Server starten.
3. Die unsignierte IPA wie gewohnt mit derselben Signatur und Bundle-ID über die bestehende App installieren.
