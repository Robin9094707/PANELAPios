# Simplex Panel 3.2.0 · Ticket-Anhänge, Bark je Dringlichkeit, schnellere Statistiken

Mit Admin Suite 9.5.0 verwenden; 9.4.0 und älter funktionieren weiter (neue Funktionen blenden sich aus). Alle Daten aus 3.1.0 bleiben: Panels, Suite-Kopplungen, Ticket-Einstellungen, Chatverlauf, Favoriten, Update-Zuordnungen.

## Neu

- **Bilder & Dateien in Tickets:** Anhänge aus Discord werden von der Suite gespeichert und im Ticket mit Vorschau angezeigt (antippen = Vollbild mit Zoom, Teilen). Aus der App lassen sich bis zu 4 Bilder (automatisch als JPEG verkleinert) oder Dateien bis 8 MiB senden; sie erscheinen als echte Discord-Anhänge. Fett/kursiv und Zeilenumbrüche werden in App und Discord übernommen.
- **Erwähnen:** Schalter „… auf Discord erwähnen“ – nach Bestätigung pingt der Bot den Ticket-Ersteller im Discord-Ticket.
- **Zustellung verwerfen:** wartende Antworten einzeln (lange drücken) oder alle verwerfen; sie bleiben im Verlauf, werden im Spiel aber nicht mehr zugestellt.
- **Löschen:** direkt möglich – der Discord-Thread/-Kanal wird gelöscht oder, falls Discord das verweigert, geschlossen. Kein Fehlerhinweis mehr, wenn ein Ticket währenddessen verschwindet.
- **Bark je Priorität:** Niedrig still (nur Liste), Normal normal, Hoch zeitkritisch, Notfall kritischer Alarm. Einzeln anpassbar unter Benachrichtigungen; Ruhezeiten gelten.
- **Spielerwarnungen verwerfen:** Profil → Moderationsakte → Warnungen: wischen oder „Alle Warnungen verwerfen“ (mit Bestätigung).
- **Statistiken:** alle Profile in einer Anfrage, letzter Stand sofort aus dem Gerätespeicher, Verlauf parallel.
- **Plugin-Updates:** GitHub-Releases als Quelle (mit SHA-256), unveränderte Plugins werden beim erneuten Öffnen nicht neu gescannt, Geyser/Floodgate-Status fragt notfalls direkt bei GeyserMC nach.

## Installation

IPA mit derselben Bundle-ID/Signieridentität über die installierte App installieren, App vorher nicht löschen.
