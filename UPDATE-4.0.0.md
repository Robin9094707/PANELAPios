# Simplex Panel 4.0.0 (Build 28) / Admin Suite 9.7.0

Additives Update: JAR ersetzen, App aktualisieren – fertig. Plugin-Datenordner, API-Schlüssel, App-Benutzer und Sitzungen, TLS-Zertifikat/Fingerabdruck, Bark-Abos, Tickets, Verifizierungen und alle Spielerdaten bleiben unverändert gültig. Kein Reset, keine Migration nötig. Die App 4.0 funktioniert auch mit älteren Suites (neue Bereiche erscheinen erst mit 9.7.0).

## Oberfläche
- **Kompakte Kopfzeilen:** Servername, Adresse, Live-Status und Plugin-Button in einer Zeile. Der Schalter „Minecraft-Spielerübersicht“ ist von der Übersicht verschwunden – die Übersicht ist standardmäßig an und lässt sich je Server unter **Workspace anpassen → Funktionen** ausschalten.
- **Startseite:** kompakte Serverkarten (Server-ID per Kontextmenü kopierbar), neues **Schnellmenü** und Karte **Admin Suite live** (Spieler, TPS, Tickets, ungelesene Admin-Aktionen, Spieler-Sparkline). Neuer Toolbar-Knopf **Admin-Zentrale** als App-Menü für alle verbundenen Suites.
- **Widgets:** Diagrammstil (Fläche/Linie/Balken), Hilfslinien, kompakte Messwerte, neue Karten „CPU & RAM kombiniert“ und „Schnellaktionen“. Einstellbar in „Startseite/Workspace anpassen“.
- **Design:** neue Farbwelten (Mitternacht, Kirschblüte, Graphit), Startanimation (abschaltbar, entfällt bei „Bewegung reduzieren“).
- **Tickets:** Eingabeleiste fest unten, Verlauf öffnet direkt bei der neuesten Nachricht, „Nach unten“-Knopf, lange Nachrichten einklappbar, Ticketdetails einklappbar. Auch sehr lange Tickets bleiben flüssig (Lazy-Rendering).

## Admin-Log
Admin → **Admin-Log**: alle Admins mit roter Zahl für ungelesene Aktionen (Teleport, Vanish, Strafen, Einstellungen …), je Admin das vollständige Protokoll mit Ziel, Admin- und Zielposition (Welt/X/Y/Z), Quelle (Minecraft, Discord, App, Konsole), Suche und Kategorien. „Alle als gelesen markieren“ oder je Admin. Lesestände gelten nur für dich; deine eigenen Aktionen zählen nie als ungelesen. Keine Bark-Benachrichtigung. Der bestehende Discord-Adminlog-Kanal bleibt unverändert.

## Staff-Chat
Admin → **Staff-Chat**. Kanal in den Staff-Chat-Einstellungen (Zahnrad) wählen. Text, Bilder, Dateien und **Sprachnachrichten** (Mikrofon-Knopf) gehen in beide Richtungen; `/staffmsg` im Spiel ist Teil desselben Chats. Der Bot schreibt **in deinem Namen** (Webhook mit Name und Minecraft-Kopf; ohne Recht „Webhooks verwalten“ als sauberes Embed) – ohne „iPhone-App“-Zusatz. Für den Hauptzugang legst du dort deinen Namen fest (z. B. Robin); App-Benutzer schreiben mit ihrem Minecraft-Namen. Über **+ → Erwähnen** einzelne Admins oder alle pingen (es wird nie @everyone verwendet).

## Setup-Links
Admin → Zugänge & Serverregeln → App-Benutzer → Benutzer → **Setup-Link erstellen** (Standard 1 Stunde, wählbar bis 7 Tage, einmalig). Link kopieren oder teilen. Auf dem iPhone öffnet `panel://setup…` direkt die App; alternativ unter Panels → **Setup-Link einlösen** einfügen. Adresse, Zertifikats-Fingerabdruck und Anmeldung werden übernommen, Face-ID-Schutz ist vorausgewählt. Für bereits per Pterodactyl verbundene Server: Admin Suite → Kopplung → **Mit Setup-Link koppeln**. Ein neuer Link ersetzt den alten; nach Abmeldung einfach neu erzeugen. Konsole: `/sas app setup <Benutzer> [Minuten]` (Ausgabe nur an den Ausführenden; mit `app-api.public-address` entsteht ein vollständiger Link).

## Ruhezeiten & Notfallneustart
Admin → **Ruhezeiten** (ersetzt die alte einzelne Ruhezeit in den Bark-Einstellungen, nichts doppelt):
- **Schlafenszeit:** stumm, nur kritische oder ausgewählte kritische Hinweise.
- **Arbeitszeit** mit Wochentagen: normale Hinweise kommen immer, kritische Alarme werden zu zeitkritischen Mitteilungen.
- **Notfallneustart:** nach RAM-Notfallneustart oder unerwartetem Stopp kommt „Server wieder online“ als kritischer Bark-Alarm. Wählbar, ob er Schlafens-, Arbeitszeit, beides oder keines durchdringen darf (Standard: beides).

## Weitere Einstellungen & Staff-Aufgaben
Admin → **App-Einstellungen**: Ränge & Namen, AFK, Scoreboard, Tickets, Moderation, Team, Spaßbefehle – bisher nur in Java-/Bedrock-Menüs. Admin → **Staff-Aufgaben**: dieselben Aufgaben wie im Ingame-Staff-Menü (erstellen, übernehmen, erledigen, Priorität, löschen).

## Ränge & Nicknames
LuckPerms-Präfix, Name in Rangfarbe und Suffix über dem Kopf und in der Tabliste, für Java und Bedrock (Geyser). Unterstützt `&`-Codes, `&#RRGGBB`, `{#RRGGBB}`, `<#RRGGBB>` und einfache MiniMessage-Farben. Live-Prüfung unter App-Einstellungen → Ränge & Namen. **Nicknames wurden entfernt**: ein sauberes Umbenennen auf Paketebene ohne Nebenwirkungen für andere Plugins ist ohne Eingriffe in Netzwerkpakete nicht zuverlässig möglich. `nicknames.yml` bleibt unangetastet erhalten.

## Android
Keine Änderung nötig: alle Funktionen laufen über dieselbe HTTPS-API (`/v1/...`) mit Schlüssel- oder Benutzeranmeldung, einschließlich `POST /v1/setup` für Setup-Links.

## CI
Pull Requests bauen JAR und unsignierte IPA als Artefakte. Die iOS-Aktion führt nur die fokussierten 4.0-Unit-Tests aus (`Panel400Tests`), die Suite die bestehenden Harnesses plus `Release970Harness`. Veröffentlicht wird erst nach dem Merge in `main`.
