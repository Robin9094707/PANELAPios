# Simplex Admin Suite 8.4.0 / Simplex Panel 2.1.0

## Installation und Daten

1. Server sauber stoppen und den vorhandenen Ordner `plugins/SimplexAdminSuite` sichern.
2. Nur die bisherige Admin-Suite-JAR durch `SimplexAdminSuite-8.4.0.jar` ersetzen. Keine zwei Suite-JARs gleichzeitig laden.
3. Den Datenordner behalten und den Server starten. Spielerprofile, Banns, Mutes, Geräte, Einstellungen, API-Schlüssel und Zertifikate werden weiterverwendet. Es gibt kein neues Datenverzeichnis und keinen Profil-Reset.
4. Die bestehende App mit Simplex Panel 2.1.0 überschreiben. Bundle-ID und Signierung beibehalten; nicht deinstallieren.

Das mitgelieferte Archiv und das Repository hatten bereits Version 8.3.0. Die Outbox-Migration ergänzt ausschließlich Spalten/Indizes in der vorhandenen SQLite-Tabelle; vorhandene Einträge bleiben unverändert. Alte Spielerprofile werden weiterhin vom bestehenden Profillader gelesen. Getestet wurden synthetische Altdateien und eine 8.3.0-Outbox, nicht die echte Installation des Nutzers.

## Discord-Protokollversand

- Vollständige 60-Sekunden-Pause für fehlgeschlagene Sammelfenster statt sofortiger Wiederholung bei überfälligen Fenstern.
- Auch Sender-Ausnahmen erhöhen den gespeicherten Versuchszähler und erhalten exponentiellen Abstand (bis 15 Minuten).
- Bis zu acht erfolgreiche Sendungen je Durchlauf; bestehender Rückstau kann schneller abfließen.
- Wiederholte noch nicht versendete Beobachtungen derselben UUID, desselben Kanals und Ereignistyps werden mit Zähler und letztem Dossier zusammengefasst. Dies ist keine vollständige Historie jedes einzelnen Zwischenstands. Die lokale Spielerhistorie bleibt separat bestehen.
- Ein bereits versendeter HTTP-Auftrag wird nicht mit neueren Beobachtungen überschrieben; sein ACK kann diese nicht versehentlich löschen.
- Deaktivierte Kanäle senden keine Anfragen; ihre gespeicherten Einträge bleiben erhalten.
- Grenzen: 8192 wartende Datensätze, 64 MiB Nutzdaten, maximal 2 Millionen Zeichen pro Dossier. Bei tatsächlicher Überlast bleiben bestehende Daten erhalten und Sammelfenster warten auf Kapazität. Speicherfehler haben eigene Ursachenmeldungen, höchstens alle 15 Minuten.
- Neue Statusansicht zeigt Speicherfehler, Rückstau, letzten Versand, Wiederholung und Hinweise zu Kanalrechten. Keine API-Schlüssel, Bot-Tokens oder Dossier-Inhalte werden im Status ausgegeben.

Fehlende Discord-Rechte, falsche Kanal-IDs oder ein deaktivierter Bot können nicht durch einen Plugin-Build behoben werden. Der Status nennt diese Fälle. Der private Kanal muss weiterhin `@everyone` ausschließen und explizite Leser auf konfigurierte Admin-Rollen/erlaubte Benutzer beschränken. Keine Abschwächung dieser Prüfung.

## Erweiterte API (weiterhin API-Version 1)

Alle Endpunkte benötigen den bestehenden Bearer-Schlüssel und HTTPS. `/v1/health` liefert die tatsächliche Plugin-Version und `features` zur kompatiblen App-Navigation.

| Endpunkt | Inhalt |
| --- | --- |
| GET /v1/data/overview | TPS, MSPT (−1 wenn nicht verfügbar), JVM-RAM, Laufzeit, Spieler, Welten |
| GET /v1/data/statistics | Profile, gespeicherte Spiel-/AFK-Zeit in ms, Sitzungen, Punkte, Strafen, Clients, Versionen, Gerätemodelle, vorhandene Discord-Statistikseiten |
| GET /v1/data/worlds | UUID, Umgebung, Zeit, Tage, Schwierigkeit, Wetter, PvP, Autosave, Border, Spieler, geladene Chunks |
| GET /v1/data/plugins | Name, Version, Autoren, aktiviert/deaktiviert |
| GET /v1/data/log-status | Persistenter Discord-Protokollspeicher und Versandstatus |

Neue Aktionen werden im bestehenden `/v1/schema` angekündigt. Für beide ist ein `full`-Schlüssel erforderlich. `POST /v1/prepare` liefert einen 60 Sekunden gültigen, an den Schlüssel gebundenen Einmalticket; `POST /v1/execute` führt es einmal aus. GET kann keine dieser Änderungen ausführen.

- `world.update`: `world` (Name oder UUID), `field` (`time`, `weather`, `difficulty`, `pvp`, `autosave`), `value`. Zeit 0–23999; Wetter `clear`/`rain`/`thunder`; Schwierigkeit `peaceful`/`easy`/`normal`/`hard`; boolesche Werte `true`/`false`. Welt muss bei Ausführung geladen sein. Änderungen sind Bukkit-Weltzustand, kein Überschreiben fremder Plugin-Konfiguration.
- `logs.retry`: keine Argumente. Setzt die Wartezeiten gespeicherter Einträge zurück. Der Discord-Ratenlimit- und Privatkanalschutz bleibt wirksam. Keine Einträge werden gelöscht.

## Bauen und Prüfen

Java 25 zum Ausführen der echten Paper-26.3-API-Prüfungen. `python3 tools/build-real.py` baut gegen echte, SHA-256-geprüfte Abhängigkeiten. `python3 tools/test-830.py` prüft HTTPS/Auth/Bestätigungen, bestehende Daten und Outbox-Migration/Last/Neustart/ACK-Rennen. `python3 tools/package-source.py` packt dieses vollständige Quellarchiv.

GitHub Actions baut JAR und unsignierte IPA, führt Simulatorprüfungen aus und veröffentlicht JAR, Plugin-Source und IPA nur nach erfolgreichen Jobs. Keine erfolgreiche CI-Prüfung ersetzt einen Test auf dem echten Minecraft-Server oder mit dem Signaturdienst auf dem iPhone.

Rücksprung im Git-Repository: `backup/before-suite-8.4-panel-2.1`. Für einen vollständigen Server-Rollback die vor dem Update angelegte Datenkopie zusammen mit der alten JAR wiederherstellen.
