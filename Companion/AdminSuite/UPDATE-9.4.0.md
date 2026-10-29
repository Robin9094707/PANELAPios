# Simplex Admin Suite 9.4.0 · Support-Tickets, dauerhafter App-Chat, Crossplay-Updates

Für Simplex Panel 3.1.0. Rein additiv: keine Migration bestehender Daten. App-Schlüssel, TLS-Fingerabdruck, Rollen, Bark-Einstellungen, Discord-Verknüpfungen und Konfiguration bleiben unverändert. Neu sind nur `plugins/SimplexAdminSuite/tickets/`, `app-chat.properties` und Konfigurationsschlüssel unter `tickets.*` (Standardwerte greifen automatisch).

## Tickets

- **Minecraft:** `/ticket` (Aliase `/tickets`, `/supportticket`). Java: Menüs für Thema und Dringlichkeit, Text im Chat; Bedrock: native Floodgate-Formulare. `/ticket new <Text>`, `list`, `view <ID>`, `reply <ID> <Text>`, `close <ID>`, `reopen <ID>`. Team (`simplexadminsuite.tickets.staff`): `/ticket staff`, `answer`, `claim`, `priority`.
- **Zustellung:** Antworten des Teams werden gespeichert und erst zugestellt, wenn der Spieler mindestens 20 Sekunden (einstellbar) ununterbrochen online ist – auch nach einem Neustart. Java: klickbares [Öffnen]/[Antworten]; Bedrock: Chat mit `/ticket view <ID>`.
- **Discord:** über den vorhandenen Admin-Suite-Bot, kein zweites Token. `/ticket setup channel:… [mode] [category] [role] [log]` postet ein DE/EN-Panel. Thema → Dringlichkeit → Formular → privater Thread (Standard) oder privater Kanal. Nachrichten des Teams dort gehen an den Spieler im Spiel; Spielerantworten aus Minecraft erscheinen im Thread. Mit `/link` verknüpfte Spieler werden automatisch in ihr Ticket aufgenommen. Nachrichten mit `//` bleiben intern. `/ticket diagnose` prüft die effektiven Bot-Rechte (Kanal ansehen, Senden, Einbetten, Verlauf, Private Threads, Threads verwalten bzw. Kanäle/Berechtigungen verwalten) und die Teamrollen.
- **Bark:** Ereignisse `ticket` und `ticket-urgent` (kritisch). Bestehende Abos erhalten Tickets automatisch, bis in der App eine eigene Regel gespeichert wird; Ruhezeiten gelten unverändert.
- **App-API:** `GET /v1/data/tickets`, `/v1/data/ticket`, `/v1/data/ticket-settings` (full), `POST /v1/tickets` (manage), `POST /v1/tickets/settings` (full). Feature `tickets-v1`.

## Weiteres

- **App-Chat:** die letzten 50 öffentlichen Nachrichten (Minecraft, Discord, App) werden gespeichert und überstehen Neustarts. `POST /v1/chat/clear` (full) leert ihn. Feature `public-chat-v2`.
- **Geyser & Floodgate:** `GET /v1/data/crossplay` vergleicht die installierten JARs per SHA-256 mit dem neuesten GeyserMC-Build; Aktionen `crossplay.update` (optional mit Neustart) und `crossplay.auto` mit Vorschau und Bestätigung. Feature `crossplay-v1`.
- Admin-Lagebild enthält `tickets` (offen, beantwortet, Notfall).

## Installation

1. Server stoppen, bisherige JAR und den Ordner `plugins/SimplexAdminSuite` sichern.
2. `SimplexAdminSuite-9.4.0.jar` einsetzen, alte JAR entfernen, Datenordner behalten, Server starten.
3. Discord: Bot-Rechte im Ticketkanal ergänzen (siehe `/ticket diagnose`) und `/ticket setup` ausführen – oder in der App unter Tickets → Einstellungen.

## Geprüft

- Alle bisherigen Regressionstests plus `Release940Harness` (Chat-Persistenz, Ticket-Lebenszyklus, Zustellwarteschlange über Neustarts, Limits, Einstellungsprüfung, Discord-Rechteberechnung, Befehlsschema) gegen die echte Paper-API 26.3.
- Discord-Thread/-Kanal-Erstellung und Bedrock-Formulare sind nicht live gegen einen echten Server getestet; `/ticket diagnose` zeigt fehlende Rechte vor dem ersten Einsatz.
