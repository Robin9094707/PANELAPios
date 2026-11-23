# Simplex Admin Suite 10.23.0

JAR tauschen und neu starten. Bestehende Dateien bleiben unverändert gültig: `chat-silence.yml` erhält pro Spieler optional das Feld `sounds` (fehlt es, gilt `off`). Neue Dateien: `player-mutes.yml`, `anti-lag.yml`, `server-updates/`.

## Neue API-Endpunkte

| Endpunkt | Rechte | Inhalt |
| --- | --- | --- |
| `GET/POST /v1/server-updater` | `data.server-updater`, Schreiben `server-updater.manage` + full-Schlüssel | Status, Builds mit Commits, `?job=true` für den Fortschritt; Operationen `install`, `restart`, `settings` |
| `GET/POST /v1/anti-lag` | `data.anti-lag`, Schreiben `anti-lag.manage` | Gesundheit, Hotspots, Verursacher, Protokoll; Operationen `settings`, `teleport`, `teleport-player`, `notify`, `release`, `limit`, `inspect`, `clear-items` |
| `GET /v1/plugin-files/overview` | `data.plugins` | Alle Plugin-JARs mit Datei- und geladener Version, vorgemerkte Updates und deren Versionsnamen |
| `GET/POST /v1/chat-silence` | wie bisher | zusätzlich `sounds`, `mutes`, Operationen `sounds`, `ignore`, `unignore`; verknüpfte Konten nur mit `data.alts` |

Neue Features in `/v1/health`: `player-mutes-v1`, `chat-sounds-v1`, `server-updater-v1`, `smart-anti-lag-v1`, `plugin-versioned-files-v1`, `profile-v4`. Das Admin-Rollenpaket enthält `data.server-updater`, `data.anti-lag` und `anti-lag.manage`; `server-updater.manage` ist kritisch und in keiner Vorlage.

## Server-Töne (ChatSilence)

Der bestehende Paketfilter prüft zusätzlich `ClientboundSoundPacket`, `ClientboundSoundEntityPacket` und (Modus Stille) Ton-Level-Events (< 2000). Modus `chat` verwirft Hinweis- und MASTER-Töne bis 0,9 s nach einer Chatzeile (das Chat-Ereignis wird schon vor anderen Plugins markiert), `plugins` verwirft Hinweis- und MASTER-Töne immer, `all` jeden Serverton. Eigene Suite-Rückmeldetöne bleiben hörbar.

## Persönliche Stummschaltung (PlayerMutes)

- Befehl `/ignorieren` (Aliase `spielerstumm`, `stummschalten`, `ignoreplayer`, `mutepl`, `blockieren`) und `/chat spieler`. Java: Chatknöpfe, Kistenmenü mit Köpfen, nativer Dialog; Bedrock: Floodgate-Formulare.
- Eintrag je Besitzer und Ziel mit `alts` und `contact`. Die effektive Menge wird jede Minute und kurz nach jedem Beitritt aus `confirmedAlts` neu berechnet – neue Alts sind sofort abgedeckt. Eigene Konten des Besitzers und Admin-Konten (`isStaffAccount`, `simplexadminsuite.admin`, OP) werden nie stummgeschaltet.
- Chat wird über `AsyncChatEvent#viewers` entfernt; erneut als Systemnachricht gesendeter Text (andere Chat-Plugins) wird zusätzlich auf Paketebene verworfen. Flüstern und Kontaktbefehle werden mit neutralem Hinweis abgelehnt.

## Paper-Server-Updater

Quelle: `https://fill.papermc.io/v3/projects/<paper|folia>` (10 Minuten Cache). Erkennung über `ServerBuildInfo`. Die Server-JAR kommt aus dem Klassenpfad (`java -jar …`); abweichend mit `-Dsimplex.serverJar=…`. Ablauf und Rollback siehe `UPDATE-5.16.0.md`. Verlauf: `server-updates/history.yml`.

## Smart Anti-Lag

Messung alle 5 Sekunden (TPS, MSPT, Entitäten je Chunk, Redstone- und Kolbenaktivität). Verursacher aus `BlockPlaceEvent` (Maschinenteile) und `EntityBreedEvent`, 30 Tage gespeichert. Gedrosselte Mobs tragen den PDC-Schlüssel `simplexadminsuite:antilag_throttled`; beim Laden ihres Chunks oder beim Stoppen des Plugins erhalten sie ihre KI zurück. `InventoryMoveItemEvent` wird bewusst nicht abgehört (würde selbst Last erzeugen).

## Plugin-Updates

`AppPluginFiles#stage` und der Suite-Plugin-Updater legen Updates als `<Name>-<Version>.jar` in den Paper-Update-Ordner. Paper (`FileProviderSource#checkUpdate`) ordnet sie über den Plugin-Namen zu und übernimmt den Dateinamen. Ein vorhandenes `/plugins/<Name>-<Version>.jar` führt zum Rückfall auf den bisherigen Namen. `/v1/plugin-files/status` findet vorgemerkte JARs über die Prüfsumme.

## Bibliotheken

Paper-API 26.3.build.258, sqlite-jdbc 3.54.1.0, Guava 33.6.0 und Netty 4.2.16 als Build-Referenz (wie Paper zur Laufzeit), JetBrains Annotations 26.1.0, ECJ 3.46.100, maven-shade 3.6.2, surefire 3.6.1, jar 3.5.1. MariaDB Connector/J bleibt 3.5.11 (4.0 erzwingt `sslMode=verify-full`). Discord: Label-Komponenten (Typ 18) in Modals, `CHANNEL_OBFUSCATED` (1<<17) gefiltert, Rollenfarbe aus `colors.primary_color`.

Regressionstest: `tools/tests/Release1230Harness.java`; `PluginFiles860Harness` erwartet jetzt den Versionsnamen.
