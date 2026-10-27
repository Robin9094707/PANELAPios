# Simplex Panel 2.4.0 + Simplex Admin Suite 8.6.0

## Installation

1. Minecraft-Server stoppen. Vorhandene JAR und den vollständigen Datenordner `plugins/SimplexAdminSuite` sichern.
2. Die bisherige Admin-Suite-JAR durch genau eine `SimplexAdminSuite-8.6.0.jar` ersetzen. Den vorhandenen Datenordner behalten. Server starten. Die Datenformate aus 8.4.0/8.5.0 werden weiterverwendet.
3. Die neue IPA mit derselben Bundle-ID und Signaturidentität über die vorhandene App installieren. Die alte App vorher nicht löschen.
4. Eine bestehende Admin-Suite-Kopplung bleibt erhalten. Für Server-Updates wird ein `full`-Schlüssel benötigt. Falls noch nicht gekoppelt: Verwalten → Admin Suite → verbinden; Einrichtung in `Companion/AdminSuite/APP-ANBINDUNG.md`.

## Plugin aktualisieren

Verwalten → Plugins & JAR-Dateien → beim installierten Plugin **Update-Datei hochladen** → JAR wählen. Danach erscheint direkt auf der Plugin-Seite eine Prüfkarte mit bisheriger und neuer Version. Serverversion/Abhängigkeiten prüfen, bestätigen und **Update vormerken** antippen. Die App zeigt den Verarbeitungsschritt und das Ergebnis. Erst nach erfolgreichem Vormerken den Server neu starten. Paper übernimmt das Update beim Start.

Eine gekoppelte Suite ab 8.6.0 liefert Metadaten und Prüfsummen direkt vom Server. Die App lädt die vorhandenen JARs dabei nicht herunter. Die neue JAR wird nach einmaliger Bestätigung direkt zur Suite übertragen. Der Server prüft Größe, SHA-256, Plugin-Name, Einstiegsklasse, Version, Java-Class-Version, installierten Stand und Update-Konflikte. Er veröffentlicht ausschließlich eine vollständig geprüfte Datei im konfigurierten Updateordner. Bestehende Konfigurationen und die laufende JAR werden währenddessen nicht verändert. Der vorhandene Dateiname bleibt für Server-Updates erhalten; die Version wird aus den Metadaten gelesen.

Ohne neue/erreichbare Suite erfolgt die Prüfung über das Panel. Kleine vorhandene JARs werden einzeln gelesen; große erst bei einer angeforderten Detailprüfung bzw. Aktualisierung. Dieser Weg verwendet weiterhin den bestehenden Upload, SHA-256-Rückvergleich und das Vormerken im Paper-Updateordner. Nach einer unklaren Antwort auf einen Server-Upload wird die genaue Zielprüfsumme abgefragt; die App wechselt dann nicht still zu einer zweiten Schreibmethode.

## API

- `GET /v1/plugin-files`: Plugin-Inventar und Metadaten; `pending=true` für vorgemerkte Updates.
- `GET /v1/plugin-files/inspect?filename=...`: Metadaten und SHA-1/SHA-256/CurseForge-Fingerabdruck direkt vom Datenträger.
- `POST /v1/prepare`: Aktion `plugins.stage`, gebunden an Dateiname, installierten SHA-256, Update-SHA-256, Name, Hauptklasse und Zielversion; nur `full`.
- `POST /v1/plugin-files/upload?ticket=...`: einmalige Bestätigung und binäre JAR (`application/java-archive`).
- `GET /v1/plugin-files/status?filename=...&sha256=...`: tatsächlichen Zustand nach einer verlorenen Antwort abgleichen.

Zugriff verwendet die vorhandene TLS-/Fingerprint-Prüfung, Serveridentität, Schlüsselverwaltung und Limits. Dateiarbeit erfolgt außerhalb des Minecraft-Hauptthreads. Kein Hot-Reload.

## Prüfungen und Grenzen

Gezielte Prüfungen umfassen echte JAR-Dateien, verschachtelte Updateordner, SHA-256-Abgleich, falsche Identität, abgebrochene Uploads, Konflikte, Links/Pfadtraversal, Java-Versionen, Bestätigungswiederholung und HTTPS-Übertragung. Bestehende Daten-/SQLite-/Outbox-Prüfungen bleiben aktiv. Der iOS-Testpfad umfasst den echten Picker-Delegate mit eigener Dateikopie und die erste/wiederholte Auswahl bis zur sichtbaren Prüfkarte; die Dateiauswahl wird im Simulator durch eine DEBUG-Fixture ausgelöst. Das ersetzt keine Prüfung des konkreten iCloud-/Files-Anbieters auf einem physischen iPhone. Es besteht kein Zugriff auf den produktiven Hostingserver oder dessen vollständige Serverkonfiguration.
