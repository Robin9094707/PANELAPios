# Simplex Panel 2.3.0 und Admin Suite 8.5.0

## Installation

Die neue IPA mit derselben Signatur und Bundle-ID über die vorhandene App installieren. Die alte App nicht deinstallieren. Profile, Keychain-Zugänge, Suite-Kopplungen, Layouts, Widgets und Transferverlauf behalten ihre bisherigen Speicherkennungen. Keine Migration bestehender Suite-Datenbanken oder Konfigurationsdateien.

Für den optionalen Datenbankscanner die neue Admin-Suite-JAR über die Updatefunktion vormerken und Minecraft anschließend neu starten. Die bestehenden API-Schlüssel und TLS-Pins bleiben gültig. Alte Suite-Versionen funktionieren weiterhin; nur der neue Scanner benötigt 8.5.0 und einen full-Schlüssel.

## JAR-Updates

- Zielordner bereits bei `GET files/upload?directory=…` übergeben, entsprechend der beigefügten Kinetic-Dokumentation. Signierte Wings-URL behält genau einen directory-Parameter und ihren Token.
- Dateiauswahl wird vor der Rückgabe an den Provider als lokale Kopie gesichert. Eine Referenz-Mailbox übergibt die Auswahl nach dem Schließen des Pickers einmal an die Vorschau.
- Neue Plugin-JARs zunächst in einem eigenen Transaktionsordner unter `/simplex-plugin-staging` übertragen. Mods verwenden weiter `/simplex-mod-staging`. SHA-256-Rücklesen vor dem Verschieben in den Ladeordner.
- Nach einem Uploadfehler zunächst prüfen, ob die Datei bereits angekommen ist. Nur bei fehlender Staging-Datei den dokumentierten binären `files/write`-Pfad verwenden. Unklare Schreibantworten werden nicht automatisch wiederholt. Teilübertragungen werden nicht aktiviert. Bei Fehlern bleibt der Staging-Pfad sichtbar; die Dateien sind über den Dateimanager erreichbar.
- Paper-Plugin-Identität, Konfliktprüfungen, individuelle Updateordner, gestoppter Mod-Server, alte Mod-Sicherung und Wiederherstellung bleiben aktiv. Ressourcenstatus berücksichtigt auch die Standard-API-Hülle `attributes`.

## Datenbankscanner

Unter Datenbanken → Datenbank → Datenbankscanner. Die Verbindung erfolgt von der Admin Suite auf deinem Minecraft-Server zum ausgewählten MySQL-/MariaDB-Host. Der Scanner fragt ausschließlich feste Metadatenabfragen aus `information_schema.TABLES` ab. JDBC-Lesezugriff plus serverseitige Read-only-Transaktion; keine Tabelleninhalte, keine frei eingegebenen SQL-Befehle, kein Passwortwechsel.

Zuerst wird das Panel-Passwort verwendet. Nach einem Fehler ist ein eigenes Passwort möglich, optional separat im iOS-Keychain gespeichert. Es ersetzt weder das Panel-Passwort noch Plugin-Konfigurationen. Ein vorhandenes Scanner-Passwort hat bei weiteren Scans Vorrang und kann vergessen werden. HTTPS mit bestehender Suite-Pin-Prüfung bleibt bestehen. Die Datenbankverbindung prüft standardmäßig TLS; die unverschlüsselte Option muss ausdrücklich aktiviert werden und gilt nur für die Verbindung Suite → Datenbank.

Tabellenanzahl, Daten-/Indexgrößen, Engine, Collation, Version und geschätzte Zeilen sind sichtbar. Die Liste ist auf 500 Tabellen begrenzt, die Summen umfassen die gesamte ausgewählte Datenbank. Ein nicht gemeldetes Speicherlimit/freier Speicher wird als nicht verfügbar angezeigt. Verbindungsprobleme beweisen kein falsches Passwort. Maximal ein gleichzeitiger Scan und sechs Versuche pro Minute/Schlüssel; kurze Verbindungs-/Lese- und Abfragezeitlimits. Passwörter werden vom Server weder gespeichert noch protokolliert.

## Java-Welten und Darstellung

Weltordner über die dokumentierte Datei-API auswählen, Dateigrößen/Regionsdateien/Spielerdateien lesen, `level.dat`-Metadaten ansehen, Dateien und Backups öffnen. Der Scan ist abbrechbar und auf 200 Ordner/etwa 20.000 Dateien begrenzt. Es gibt in der gelieferten Kinetic-API keinen eigenen Weltverwaltungs- oder SQL-Statistik-Endpunkt; solche Endpunkte werden nicht erfunden. Bestehende Suite-Weltaktionen bleiben im Serverbetrieb verfügbar. Kinetics dokumentierte Mod-Sperrliste ist direkt erreichbar.

Optionale Dashboard-Karten: Minecraft-Werkzeuge, Dateien/Backups/Datenbanken und Übertragungen. Bestehende Kartenreihenfolgen bleiben erhalten. Farbvorlagen, freie Primär-/Sekundärfarben, Kartendichte, Eckenradius, Tönung, Hintergrundstärke und ein Raster für breite Server-Workspaces lassen sich anpassen.

## Prüfung und Grenzen

GitHub Actions baut die ARM64-IPA und beide Test-Targets, führt Simulator-/UI-Prüfungen sowie Suite-Regressionsprüfungen und einen echten MariaDB-Metadatentest aus. Der PR stellt IPA, JAR, Quellarchiv und Prüfsummen als Actions-Artefakte bereit; er wird nicht automatisch gemergt. Die IPA ist unsigniert.

Kinetic-Produktivzugriff und das konkrete Datenbankpasswort sind ohne Zugangsdaten nicht live geprüft. Ein grüner Build bestätigt die automatisierten Prüfungen, nicht die Konfiguration jedes Providers.
