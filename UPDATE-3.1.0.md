# Simplex Panel 3.1.0 · Tickets, Serverchat & Plugin-Zentrale

Mit Admin Suite 9.4.0 verwenden (läuft auch mit 9.3.0 und älter – neue Bereiche blenden sich dann aus). Bundle-ID, Schlüsselbund, gespeicherte Panels, Suite-Kopplungen, Layouts, Shortcuts, Favoriten und Update-Zuordnungen bleiben unverändert.

## Neu

- **Support-Tickets** (Admin-Zentrale → Tickets, ab Suite 9.4.0): Liste mit Filtern (Aktiv, Offen, Beantwortet, Notfall, Geschlossen), Suche, Spielerköpfen und Prioritäten. Im Ticket: Verlauf als Chat, Antwort schreiben, übernehmen, Priorität ändern, schließen/wieder öffnen, löschen (full). Jede Teamantwort zeigt, ob sie im Spiel schon zugestellt ist.
- **Ticketeinstellungen** (Zahnrad in der Ticketliste, full-Schlüssel): Ticketkanal, private Threads oder eigene Kanäle mit Kategorie, Teamrollen, Protokollkanal, Limits, Zustellverzögerung, Join-Hinweis, Bark – plus **Diagnose**, die die echten Bot-Rechte prüft und fehlende Berechtigungen beim Namen nennt. „Speichern & Ticket-Panel posten“ postet das zweisprachige Panel.
- **Bark:** neue Ereignisse „Neue Support-Tickets“ und „Notfall-Tickets“ (Standard: kritischer Alarm). Ruhezeiten gelten wie gewohnt.
- **Serverchat bleibt erhalten:** die letzten 50 Nachrichten aller Spieler (inkl. Discord und App) mit Köpfen – auch nach dem Schließen der App. Papierkorb mit Bestätigung leert den Verlauf (mit full-Schlüssel auch auf dem Server).
- **Puzzleteil auf der Serverseite:** Update-Status aller Plugins, Geyser & Floodgate, Plugin-Ordner, Minecraft-Einstellungen, Befehls-Shortcuts und Startseite anpassen – in einem Blatt.
- **Geyser & Floodgate** (Admin-Zentrale, ab Suite 9.4.0): installierter Build gegen den neuesten GeyserMC-Build per SHA-256, Updates vormerken (optional mit Neustart), automatische Prüfung an/aus.
- **Plugin-Updates:** neue Quellen **Hangar** und **GeyserMC** (Geyser/Floodgate werden automatisch erkannt), **Zuordnungsvorschläge** per exaktem Namen, **„Alle geprüften Updates vormerken“** (nur mit Prüfsumme und bestätigter Version), kostenlose Spigot-Plugins direkt über das Spiget-CDN ohne Anmeldung, „Heruntergeladene JAR auswählen“ für Safari-Downloads.
- **Spigot-Login:** der eingebaute Browser meldet sich jetzt wie Safari an und lässt die Cloudflare-Prüfung ihre Unterrahmen laden. Falls sie trotzdem hängt: „Neu laden“, oder in Safari laden und über „JAR aus Dateien“ übernehmen.

## Installation

1. Optional zuerst die Suite aktualisieren (`Companion/AdminSuite/UPDATE-9.4.0.md`).
2. IPA mit derselben Bundle-ID und Signieridentität über die installierte App installieren. App vorher **nicht** löschen, sonst gehen Schlüsselbund-Einträge verloren. Die IPA ist unsigniert und muss selbst signiert werden.
