# Simplex Panel 2.4.0 · Admin Suite 9.0.0

## Installation ohne Datenwechsel

Den vollständigen Ordner `plugins/SimplexAdminSuite` und die bisherige JAR sichern. Den Server für den Austausch stoppen, genau eine neue `SimplexAdminSuite-9.0.0.jar` einsetzen und den bestehenden Datenordner behalten. Keine Konfiguration löschen. Diese Version baut auf der hochgeladenen 8.6.0 auf, einschließlich des geprüften Plugin-Upload-Endpunkts; GitHub enthielt zuvor 8.5.0.

Die IPA mit derselben Bundle-ID und Signatur über die vorhandene App installieren; nicht vorher deinstallieren. Die IPA aus Actions ist unsigniert. Panel-, AMP-, Schlüsselbund-, Layout- und Kopplungsformate werden weiterverwendet. Es erfolgt kein Zugriff auf einen Produktionsserver während des Builds.

## Neue Verwaltung

- Glocke in der Admin-Suite-Karte und in der Suite-Übersicht: neues, durchsuchbares Ereignisprotokoll (maximal 2.000 Einträge), Notizen, einzelne Einträge löschen und leeren. Bestehende Original-Logdateien werden dadurch nicht gelöscht oder umgeschrieben.
- Interne Banns, Client-Prüfsperren, Minecraft-Profil-/IP-Sperren und lokal installierte LiteBans werden getrennt gelesen und freigegeben. Ultrabanns und Identitätsgruppen bleiben in ihrer eigenen bestehenden Verwaltung. LiteBans wird ausschließlich über seine API gelesen und per namespaced Entbannbefehl verwaltet; keine direkten SQL-Schreibzugriffe. Eine angenommene LiteBans-Anfrage muss durch Aktualisieren der Liste bestätigt werden.
- VPN-Whitelist nach gespeicherter Spieleridentität hinzufügen/entfernen; letzte blockierte Verbindungen aus den jüngsten 200 VPN-Prüfungen. Keine unscharfe Präfixentfernung von Ausnahmen.
- Reports suchen, Belege einsehen, Status ändern und Notizen ergänzen. Änderungen prüfen den geladenen Bearbeitungsstand.
- iPad und iPhone erhalten unterschiedliche Symbole, sofern das übermittelte Gerätemodell die Unterscheidung ermöglicht. Eine reine iOS-Angabe beweist kein bestimmtes Gerätemodell.

## Client-Brands und Schildprüfung

Öffentlich werden nur feste Anzeigenamen bekannter Brands ausgegeben. Fremde Namen erscheinen je nach Betrachter als „Unbekannter Client“ oder „Unknown client“. Vanilla, Fabric, Forge, NeoForge, Quilt und weitere verbreitete Brands sind enthalten; das ist keine vollständige Liste aller existierenden Modpacks. Ein unbekannter Brand erzeugt eine private Admin-Warnung und einen Ereigniseintrag, keinen Bann. Das Original bleibt im privaten Profil als unbestätigte Selbstangabe sichtbar. Ein gefälschtes `vanilla` kann nicht über den Brand erkannt werden.

Meteor wird weiterhin über die vorhandenen Signaturen mit Kontrollantwort und positiver Bestätigungsprüfung geprüft. Ohne ausdrücklich konfigurierte andere Regel gilt jetzt Kick mit dauerhafter Team-Freigabe nach bestätigtem Treffer. Aufheben: App → Banns & Freigaben → Client-Prüfung; alternativ bestehende `client.approve`-Aktion. Keine automatische Sanktion wegen bloßem Timeout, `PROTECTED` oder ungewöhnlichem Namen. Bereits ausdrücklich konfigurierte Regeln und Ausnahmen bleiben maßgeblich.

CheckHacks 1.3.0 wurde als Referenz abgeglichen (Modrinth B2gBml7j, Quelle branduzzo/CheckHacks). Die 8.6.0 enthielt bereits Kontrollzeile, DoubleCheck, quiet/legacy-Fallback und Adapter für bool/Array und Enum/List. Hinzu kommen begrenzte erneute Platzierung und verständliche Ereigniszusammenfassungen. Es werden keine echten Weltblöcke überschrieben. Kein serverseitiger Check garantiert die Erkennung jeder Client-Version oder eines Clients, der Antworten verändert. `SKIPPED`, `TIMEOUT`, `PROTECTED` und technische Fehler bleiben getrennte Zustände.

## Survival und Unverwundbarkeit

Nur `Player.isOp()` befreit vom Survival-Zwang. Allgemeine Admin-/Wildcard-Berechtigungen genügen nicht. Spielmoduswechsel anderer Spieler werden verhindert; ein Kontrolllauf korrigiert Survival/`Invulnerable` jede Sekunde. Der vorübergehende Fallschutz der Suite-Schildprüfung bleibt während einer ausstehenden Rückkehr erhalten. Danach kann ein Nicht-Operator keine versehentlich gespeicherte Unverwundbarkeit zurückerhalten. Das verhindert keine anderweitig von Fremdplugins abgebrochenen Schadensereignisse und identifiziert deren Verursacher nicht.

## Offline-NBT bei laufendem Server

Spielerprofil → gespeicherte NBT-Dateien → Editor. Ab Suite 9 läuft Speichern über die Suite, ausschließlich für ausgeloggte Spieler und mit `full`-Schlüssel. Geprüft werden genaue Spielerdatei in geladenen Welten, Pfade/Symlinks, maximal 2 MiB, NBT-Struktur und SHA-256 des Ausgangsstands. Offline-Prüfung, Sicherung und atomarer Austausch laufen gemeinsam auf dem Server-Hauptthread, damit kein Login dazwischenkommt. Bei Konflikt oder nicht unterstütztem atomarem Austausch bleibt das Original erhalten. Die Sicherung liegt als `.panel-<Zeit>-<UUID>.bak` neben der Spielerdatei. Der bisherige Panel-Dateieditor bleibt für andere Dateien beim gestoppten Server.

## Bark

Glocke → Bark & Alarmtöne → eigene Push-Adresse aus Bark einfügen. Es wird der öffentliche Dienst `https://api.day.app/push` per JSON-POST verwendet; die Minecraft-Instanz muss ihn erreichen können. Es ist kein zusätzlicher Panel-Server nötig. Jede `full`-API-Kopplung besitzt eigene Abonnements. Ein widerrufener Schlüssel erhält keine weiteren Pushes. Bark-Adressen/Schlüssel werden nicht in GitHub, IPA, normalen Einstellungsansichten oder Ereignissen hinterlegt; im Plugin liegen sie in einer separat geschützten Datei. Eine vorhandene leere Schlüsseleingabe behält den bisherigen Schlüssel.

Auswählbar: neue Spieler, Beitritte, auffällige Brands, Prüfungen, Sperren, Bannumgehungen, VPN-Kicks, Reports, Überlastung, Spielerschutz und Admin-Aktionen. Normale und kritische Ereignisse erhalten getrennte Dringlichkeit und Bark-Klangnamen. Stille Anzeige: `passive`; kritischer Alarm benötigt die entsprechende iOS-/Bark-Erlaubnis. Wiederholungen gleicher Ereignisarten werden pro Empfänger begrenzt. Längere Überlastung: mindestens sechs 10-Sekunden-Messungen mit 1-Minuten-TPS unter 16; maximal ein Alarm alle zehn Minuten. Es wird kein Schutz vor Benachrichtigungsausfall bei komplett gestopptem Server versprochen.

Neue Ereignisse und Abonnements liegen separat unter `app-events/`; vorhandene Spieler-, Report-, VPN-, Bann- und Identitätsformate werden nicht migriert. Die neue Ereignisliste beginnt ab Installation dieser Version; bestehende Client-Prüfsperren und Bannlisten werden aus ihren ursprünglichen Speichern gelesen.
