# Projektübergabe – Simplex Panel & Admin Suite

Arbeitssprache mit dem Besitzer: **Deutsch**. Nutzertexte in App, Plugin und Doku sind Deutsch (Plugin-Spielertexte teils DE/EN).

## Aktueller Stand (13.11.2026)

| Komponente | Version | Ort |
| --- | --- | --- |
| iOS-App „Simplex Panel“ | **5.12.0** (Build 53) | `Panel/`, `Shared/`, `PanelWidgets/`, `project.yml` |
| Paper-Plugin „Simplex Admin Suite“ | **10.19.0** | `Companion/AdminSuite/` |
| Geyser-Extension „SentinelBridge“ | 1.6.0 (unverändert seit 4.6.0) | `Companion/SentinelBridge/` |

Hoster: **UltraServers** (`https://panel.ultraservers.com`, Pterodactyl-Client-API mit `ptlc_`-Schlüsseln). Kinetic wurde in 5.12.0 vollständig entfernt – nicht wieder einbauen.

Was in jeder Version kam: `UPDATE-<App-Version>.md` im Root und `Companion/AdminSuite/UPDATE-<Suite-Version>.md`. Kurzfassung oben in `README.md`, Release-Notes in `.github/workflows/ios.yml` (Job `release`).

## Repo-Aufbau

- `Panel/Core` – Modelle und API (`API.swift` = Pterodactyl-Client, `AMPAPI.swift`, `SuiteAPI.swift` = Admin-Suite-API, `UltraServers.swift` = Hoster-Erweiterungen, `ServerProperties.swift`, `WhatsNew.swift`, `Demo.swift` = Demo-Daten).
- `Panel/Views` – SwiftUI (`UltraServersViews.swift`, `Backups.swift`, `Manage.swift`, `MinecraftProperties.swift`, Admin-Suite-Ansichten `Suite*.swift`).
- `PanelTests/Panel<Version>Tests.swift` – Unit-Tests je Release (z. B. `Panel5120Tests`), `PanelUITests/`.
- `Companion/AdminSuite/src/main/java/eu/simplexsmp/adminsuite/` – Plugin. Riesige Hauptklasse `SimplexAdminSuite.java`; Namensschilder/Tabliste in `VisualIdentityManager.java`; App-API in `AppApiManager.java` (Feature-Liste in `/v1/health`); Koppeln in `AppPairing.java`.
- `Companion/AdminSuite/tools/tests/Release<Version>Harness.java` – Regressionstests je Release, registriert in **beiden** Listen in `tools/test-830.py`.

## Bauen und testen

CI (`.github/workflows/ios.yml`) baut bei Push auf `main`, bei PRs und per `workflow_dispatch`:
1. iOS: XcodeGen → unsignierte IPA → Simulator-Tests (nur die in `-only-testing:` gelisteten Klassen! Neue Testklassen dort ergänzen).
2. Plugin: `python3 tools/build-real.py` (echte Paper-API, gepinnte Hashes) + `python3 tools/test-830.py` mit MariaDB.
3. `release` (nur Push auf `main`): veröffentlicht `app-v<Version>` mit IPA, JAR, Quell-ZIPs. Bricht ab, wenn das Tag schon existiert → **App-Version immer erhöhen**.

Lokal in einer Cloud-Sitzung:
- Plugin bauen: `cd Companion/AdminSuite && python3 tools/build-real.py` (Maven Central drosselt manchmal mit 429 → wiederholen; danach `--offline`).
- Plugin-Tests brauchen **Java 25** (Paper-Klassen sind Version 69). Ist nur Java 21 da: Temurin 25 von `api.adoptium.net` laden und vor `PATH` setzen.
- Kein Xcode unter Linux. Reine Foundation-Modelle lassen sich mit einer Linux-Swift-Toolchain (swift.org) plus kleinen Stubs vorab kompilieren; SwiftUI nur über CI.
- IPA/JAR eines Laufs: `gh api repos/Robin9094707/PANELAPios/actions/artifacts/<id>/zip`.

## Konventionen und Fallstricke

- Versionssprung: `project.yml` (`MARKETING_VERSION`, `CURRENT_PROJECT_VERSION` +1), `plugin.yml` + `pom.xml`, JAR-Namen im Workflow, `WhatsNew.releases` (neuester Eintrag zuerst), UPDATE-Dateien, README-Kopf, Release-Notes im Workflow.
- **Keine zwei Swift-Dateien mit gleichem Namen** (auch nicht in verschiedenen Ordnern) – Xcode bricht mit „Multiple commands produce …stringsdata“ ab.
- Lange verschachtelte Ausdrücke (Ternaries, heterogene Literale in `map`) sprengen den Swift-Typchecker → in Teilausdrücke zerlegen.
- Swift 5-Modus, iOS 26-SDK, keine Fremdabhängigkeiten.
- Bestehende Daten müssen nach jedem Update weiter funktionieren (Konten, Schlüssel, TLS-Identität, Spielerdaten, Configs). Migrationen nur additiv.
- Commit-/PR-Texte ohne Modellnamen.

## UltraServers-API (Stand 5.12.0)

Ermittelt am Live-Panel (Laravel: vorhandene Route = 401, unbekannte = 404, ohne Login). Antwortformate sind **nicht dokumentiert**, Decoder in `Panel/Core/UltraServers.swift` sind bewusst tolerant. Schreibaktionen sind ohne Konto **nicht live getestet** – meldet der Besitzer Fehler, zeigt die App die Panel-Meldung (z. B. fehlende Felder bei 422); danach die Felder anpassen.

```
GET  servers/{id}/system-backups               automatische Backups (alle 6 h, ~2 Wochen, meist 8 sichtbar)
GET  servers/{id}/system-backups/{b}/download
POST servers/{id}/system-backups/{b}/restore   (sendet truncate + wipe)
GET  servers/{id}/players                      Player Manager (Aktionen laufen als Konsolenbefehle über /command)
GET  servers/{id}/subdomain  · POST subdomain/create (subdomain, domainId) · DELETE subdomain/delete/{id}
GET  servers/{id}/proxy      · POST proxy/create (domain, allocation_id, ssl, letsencrypt …) · DELETE proxy/delete/{id}
GET  servers/{id}/plugins    · GET plugins/versions  · POST plugins/install
GET  servers/{id}/modpacks   · GET modpacks/versions · POST modpacks/install
POST servers/{id}/databases/import
```
Nicht per API gefunden (nur Web-Panel, App verlinkt): Version wechseln/Game Switch, Server-Splitter, SFTP-Import, Config-Editor-Backend, MTR, Tickets.

Hinweis für die Admin-Suite-App-API bei UltraServers: Allocation mit Haken für HTTP-/Voice-/Votifier-Plugins anlegen, sonst filtert der Gcore-DDoS-Schutz.

## Zuletzt behoben (10.19.0)

Vertauschte Präfixe/Suffixe über Köpfen und in der Tabliste: Teamname kam aus den ersten 10 UUID-Hexziffern, alle Floodgate-UUIDs beginnen mit `0000000000` → ein gemeinsames Team. Jetzt `VisualIdentityManager.teamName(uuid)` (SHA-256, Präfix `sasafk`, ≤16 Zeichen), `sweep()` räumt alte Teams auf. Außerdem Tablisten-Vergleich gegen den von Paper zurückgelesenen Wert.

## Offene Ideen

- UltraServers-Schreibaktionen mit echtem Konto prüfen und Feldnamen ggf. korrigieren.
- Versions-/Game-Switch-API von UltraServers ermitteln, falls es eine gibt.
- Tabliste optional nach Rang-Gewicht sortieren.
