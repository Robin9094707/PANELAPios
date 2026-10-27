# Simplex Panel 2.3.1 (Build 17)

## Plugin-Update vormerken

Nach der Auswahl prüft die App weiterhin Plugin-Name, Einstiegsklasse und höhere Version. Das Vormerken verwendet jetzt den konfigurierten Update-Ordner direkt: zuerst eine eindeutige `simplex-update-<UUID>.pending`, danach SHA-256-Rückleseprüfung und Umbenennung zur versionsbezogenen `.jar` innerhalb desselben Ordners. Paper lädt keine `.pending` als Plugin-Update. Zusätzliche Staging-Ordner im Serverhauptverzeichnis und ordnerübergreifende Verschiebe-Anfragen entfallen.

Die Übertragung versucht zuerst `POST files/write` mit unveränderten Binärdaten. Nur wenn nach einem Fehler keine temporäre Datei vorhanden ist, folgt die signierte Multipart-Übertragung. Bestehende Dateien werden nicht blind überschrieben. Kleine Konfigurationen und JARs werden über `files/contents` gelesen, bei Host-Editorlimits über den bisherigen Downloadweg.

Eine HTTP-500-Antwort kann nach einer bereits ausgeführten Dateisystemaktion entstehen. Nach unklaren Ordneranlagen und Umbenennungen wird deshalb der tatsächliche Zustand geprüft. Eine exakt passende bereits vorgemerkte Datei zählt als Erfolg. Abweichende oder zusätzliche Versionen desselben Plugins bleiben gesperrt. Unvollständige Uploads behalten `.pending`; Fehlermeldungen benennen den Schritt und den Wiederherstellungspfad. Zurückgebliebene `.pending` aus unterbrochenen Versuchen können im Dateimanager entfernt werden.

## Erste Dateiauswahl

Der Übergabepuffer speichert Dateikopie und installierten Dateinamen gemeinsam. Die Update-Prüfung erhält den Dateinamen ausdrücklich als Parameter, statt ihn aus einer älteren SwiftUI-Präsentation zu lesen. Gilt für Plugins und Mods. Der Picker verarbeitet jeden Abschluss nur einmal. Abbruch und neue Auswahl leeren vorherige Ergebnisse und Fehler.

## Installation und bestehende Daten

Die IPA mit gleicher Bundle-ID `eu.simplexsmp.panel` und demselben Signierkonto über die vorhandene App installieren. App vorher nicht löschen. Profile, Keychain, Widgets, Suite-Kopplungen und Dashboard-Einstellungen behalten ihre Formate. Die App-Reparatur benötigt keine neue Admin Suite: 8.4.0 kann über den Panel-Dateizugriff auf 8.5.0 aktualisiert werden. Vorhandene Plugin-Konfigurationsordner werden beim Vormerken nicht geändert. Nach bestätigtem Vormerken Minecraft neu starten.

## Prüfung und Grenzen

Neue Simulator-Regressionsprüfungen führen den echten Installer über eine HTTP-Testimplementierung der Panel-API aus: 8.4.0 → 8.5.0, Wiederholung ohne doppelten Upload, HTTP 500 nach erfolgreichem Schreiben/Umbenennen/Ordneranlegen, Teilupload, fehlgeschlagenes Umbenennen mit erneutem Versuch, benutzerdefinierter verschachtelter Update-Ordner, Versionskonflikt und Änderung der installierten JAR während der Übertragung. Außerdem: erste Dateiauswahl mit Plugin-Zuordnung sowie tatsächliche Document-Picker-Delegate-Dateikopie einschließlich Doppelabschluss.

Diese Prüfungen verwenden synthetische JARs und keine Produktivzugänge. Der konkrete Hoster und die iOS-Dateianbieter müssen weiterhin am Gerät geprüft werden. Laufzeitkompatibilität und Abhängigkeiten des Plugins werden durch die Metadatenprüfung nicht bewiesen.
