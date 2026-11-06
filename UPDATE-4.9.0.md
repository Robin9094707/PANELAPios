# Simplex Panel 4.9.0 (Build 38) · Admin Suite 10.6.0

Die App behält Farben, Glas-Karten, Navigation und ihre bisherige App-ID.

- Startseite: Admin Suite, Werbung, Tickets, Diagnose, Admin-Log, Verläufe, Staff-Chat und Kurzbefehle direkt öffnen. Auch ohne Hosting-Panel. Bei mehreren Suite-Zugängen den gewünschten Server wählen; über das Server-Menü oder das Kontextmenü einen Standard setzen.
- Ein gemeinsamer Werkzeugkatalog im Admin-Tab und für direkte Suite-Zugänge: nach Aufgaben gruppiert, Favoriten per langem Druck, Suche über Werkzeuge, Einstellungen und Schema-Funktionen. Die Startseitensuche findet zusätzlich Werkzeuge.
- Native iOS-Kurzbefehle: **Admin-Werkzeug öffnen**, **Werbeverwaltung öffnen**, **Werbung vorbereiten**. Letzterer hat einen auswählbaren Werbe-Ort mit eindeutiger Server-Zuordnung. Neue Schnellzugriffe sind standardmäßig verfügbar.
- Werbe-Orte, Texte und Werbe-Verlauf durchsuchen. Im Kontextmenü eines Orts einen dauerhaften Kurzbefehle-Link kopieren, um genau diesen Ort zu verwalten oder Werbung vorzubereiten. Er enthält weder Schlüssel noch Token. Gelöschte Zugänge oder Orte werden als Fehler angezeigt und niemals durch andere ersetzt.
- **Verläufe verwalten**: Admin-Log, Serverereignisse, Werbe-Verlauf, Staff-Chat und Serverchat durchsuchen, einzelne oder mehrere Einträge löschen oder vollständig leeren. Bestätigungen zeigen die tatsächliche Anzahl. Erst nach erfolgreicher Sicherung wird gelöscht. Zwischenzeitlich neu hinzugekommene Einträge bleiben erhalten. Sicherungen können in App und Web wiederhergestellt werden.
- Webpanel: Schnellzugriffe auf dem Dashboard, kompletter Werkzeugkatalog, erweiterte Schnellsuche (Strg/⌘+K), Werbesuche und dieselbe Verlaufsverwaltung. Bereits geöffnete Chats aktualisieren sich nach Löschungen.

## Kurzbefehle einrichten

1. Apples **Kurzbefehle** öffnen, einen neuen Kurzbefehl anlegen und nach **Simplex Panel** suchen.
2. **Werbung vorbereiten** hinzufügen und den gewünschten Werbe-Ort auswählen. Die Liste kommt aus den bereits auf diesem Gerät eingerichteten Suite-Verbindungen.
3. **Zum Home-Bildschirm** wählen. Der Kurzbefehl öffnet die App; nach dem Entsperren werden der aktuelle Text kopiert und der Ort geöffnet. Das Einfügen und Absenden erfolgt wie bisher durch dich.

Für einen eigenen URL-Kurzbefehl den Link im Kontextmenü des Werbe-Orts kopieren und in Kurzbefehle mit **URLs öffnen** verwenden. **Werbeverwaltung öffnen** führt bei einem Zugang direkt hinein; bei mehreren Zugängen gilt dein ausgewählter Standard, andernfalls erscheint eine Auswahl. Kurzbefehle nutzen dieselben Rechte, Zertifikatsprüfungen, Face ID, Werbeintervalle und Pausen wie die App. Ein zu früher Werbevorgang erfordert weiterhin deine Bestätigung. Es wird kein automatisches Posting aktiviert.

## Aktualisieren

Das Gesamtupdate wurde mit den App-Fehlerkorrekturen aus 4.9.1 über PR #24 nach `main` übernommen. GitHub Actions prüft und veröffentlicht IPA und JAR unter [Release 4.9.1](https://github.com/Robin9094707/PANELAPios/releases/tag/app-v4.9.1). Die ursprünglichen PR-Builds bleiben zusätzlich als Actions-Artefakte verfügbar.

1. Vor dem Austausch die bisherige JAR und ein vollständiges Backup des Suite-Datenordners aufbewahren.
2. Server geordnet stoppen, `SimplexAdminSuite-10.5.0.jar` durch **SimplexAdminSuite-10.6.0.jar** ersetzen und starten. Nur eine Suite-JAR im Pluginordner lassen. Das Webpanel wird mit aktualisiert.
3. **Simplex-Panel-4.9.0-unsigned.ipa** wie bisher mit derselben App-ID und denselben Signier-/Schlüsselbundberechtigungen signieren und als Update installieren. Die bestehende App nicht vorher löschen.

API-Schlüssel, App-Benutzer, TLS-Identität, Sitzungen, Einstellungen, Werbe-Orte, Texte und Timer behalten ihre bisherigen Formate. Neue Kurzbefehle und Favoriten sind zusätzliche lokale Einstellungen.

## Zurückgehen

Die zuvor veröffentlichte Version 4.8.0 / 10.5.0 bleibt im bisherigen Release unverändert verfügbar. Für ein vollständiges Rollback den Server stoppen, die alte JAR und bei Bedarf den vor dem Update gesicherten Datenordner zurückspielen; anschließend starten. Die bisherige IPA mit derselben Signierung wieder installieren (manche Signierwerkzeuge verlangen bei einer niedrigeren Versionsnummer einen manuellen Austausch). Die App nicht deinstallieren, damit lokale Zugänge erhalten bleiben. Neue `panel://suite`-Links und iOS-Kurzbefehle setzen 4.9.0 voraus.

Versehentlich gelöschte Verlaufsdaten lassen sich unter **Verläufe verwalten → Sicherungen wiederherstellen** ergänzen. Diese Sicherungen ersetzen kein vollständiges Serverbackup. Eine Wiederherstellung überschreibt keine vorhandenen Einträge und bricht bei Überschreitung des jeweiligen Verlaufslimits ab. Anhänge des Staff-Chats bleiben für die Wiederherstellung lokal erhalten; Nachrichten bei Discord werden nicht gelöscht.

## Prüfungen

Kleine neue Swift-Tests für URL-Zuordnung, Eingabevalidierung, Suchbegriffe, Werkzeugkatalog und Verlaufsbeschriftungen; ein kurzer Simulator-Starttest für die Schnellzugriffe und die Kurzbefehle-Hilfe. Die vorhandenen fokussierten Tests laufen weiter. Der HTTPS-Test prüft Rechte, Vorschau ohne Änderung, einzelne Löschung, neue Einträge während der Bestätigung, Sicherung, Wiederherstellung, Neustart und unveränderte Werbe-Timer. Der finale Status ist im GitHub-Actions-Lauf des PR ersichtlich.

Das echte Ausführen über Siri/Home-Bildschirm, Signierung, Face ID und das Öffnen fremder Apps müssen auf dem iPhone geprüft werden.
