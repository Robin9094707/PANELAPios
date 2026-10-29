# Simplex Panel 3.0.0 · Admin-Zentrale & Spielerverwaltung

Mit Admin Suite 9.3.0 verwenden (funktioniert auch mit 9.2.0 und älter, siehe unten). App-Bundle-ID, Schlüsselbund, gespeicherte Panels, Suite-Kopplungen, Layouts, Shortcuts und Einstellungen bleiben unverändert.

## Neu

- **Admin-Tab (Admin-Zentrale):** ersetzt den bisherigen Statistik-Tab, sobald ein Server mit der Suite gekoppelt ist. Live-Kennzahlen (Spieler online, TPS/MSPT, Suite-RAM, Profile/neue Spieler), Hinweise zu laufendem Neustart, Wartungsmodus, offenen Reports, aktiven Banns, Stummschaltungen, Ultrabanns und verwarnten Spielern – jeweils direkt antippbar.
- **Gerade online:** Spielerköpfe aller Online-Spieler mit Direktsprung ins Profil, dazu „Team online“ (nur mit `full`-Schlüssel, wie die Admin-Team-Ansicht).
- **Werkzeuge:** alle Suite-Bereiche in einem Raster – Spieler, Serverchat, Reports, Banns, VPN, Ereignisse, Alt-Accounts, Ultrabanns, Admin-Team, Neustart, Verwaltung, Discord, Statistiken, Serverbetrieb, Einstellungen, alle Funktionen und Kopplung. Angezeigt wird nur, was die verbundene Suite meldet. Statistiken bleiben zusätzlich unter Mehr → Verwalten erreichbar.
- **Spielerliste:** Spielerköpfe (Java, über mc-heads.net; abschaltbar unter Einstellungen → Admin Suite), Badges für Bann, Stumm, Warnungen und Team, Wischaktionen (Favorit, Nachricht, Kicken, Verwarnen), Kontextmenü mit Kopieren und allen Schnellaktionen. Neue Filter **Auffällig** (Bann/Stumm/Warnung) und **Neu** (erste Anmeldung in den letzten 7 Tagen) – serverseitig, ab Suite 9.3.0.
- **Spielerprofil:** größerer Kopfbereich mit Status, Rolle und Badges; Aktionsleiste mit Nachricht, Kicken, Verwarnen, (Ent-)Muten, Zeitbann, Bann bzw. Entbannen und Notiz. Jede Aktion läuft weiterhin über die Suite-Vorschau mit 60-Sekunden-Bestätigung. Neue **Moderationsakte** mit Spielzeit, Sitzungen, erstem Besuch, Warnungen, Teamnotizen und früheren Namen.
- **Favoriten & zuletzt angesehen:** Stern im Profil oder per Wischgeste; Favoriten erscheinen oben in der Spielerliste und in der Admin-Zentrale. Wird nur lokal auf dem Gerät gespeichert, pro Suite-Server.
- **Was ist neu:** nach dem Update erscheint einmalig ein Splash-Screen mit den Neuerungen. Jederzeit erneut unter Einstellungen → Was ist neu. Bei einer Neuinstallation ohne gespeicherte Daten wird er übersprungen.

## Kompatibilität

- Admin Suite 9.3.0 liefert das Lagebild in einer einzigen Anfrage (`/v1/data/admin-dashboard`) und die neuen Filter.
- Mit Suite 9.2.0 oder älter setzt die App das Lagebild aus den vorhandenen Endpunkten zusammen (Übersicht, Online-Spieler, Neustart, Wartung, Reports – jeweils nur, wenn die Suite das Modul meldet). Die Filter „Auffällig“/„Neu“ werden dann ausgeblendet. Nichts wird an alte Suiten gesendet, was sie nicht kennen.
- Alle bisherigen Ansichten und Wege (Verwalten → Admin Suite, Übersichtskarte, Mehr) bleiben erhalten.

## Installation

1. Optional zuerst die Suite aktualisieren (siehe `Companion/AdminSuite/UPDATE-9.3.0.md`).
2. IPA mit derselben Bundle-ID und Signieridentität über die installierte App installieren. App vorher **nicht** löschen, sonst gehen Schlüsselbund-Einträge verloren. Die IPA ist unsigniert und muss zum Installieren selbst signiert werden.
