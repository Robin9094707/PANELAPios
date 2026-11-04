# Changelog

## 1.6.0 – Identität ohne HTTP 422, Geyser 2.12.1, mehr Bedrock-Daten

- **Behoben: „Could not deliver Bedrock identity metadata … HTTP 422“.** Beim sehr frühen Bedrock-Login kennt Geyser
  den Java-Namen und die UUID noch nicht. 1.5.0 schickte das Paket trotzdem (über nicht vorhandene Getter), die Suite
  antwortete mit 422 und die Brücke wiederholte es dreimal. Jetzt: Name/UUID über `javaUsername()`/`javaUuid()`, der
  Gamertag separat als `bedrockName`; Pakete ohne jede Identität werden gar nicht erst gesendet (der 5-Sekunden-Snapshot
  folgt automatisch). Die Suite 10.3.0 leitet die Floodgate-UUID aus der XUID ab und nimmt identitätslose Pakete älterer
  Brücken als „deferred“ an.
- Dauerhafte Ablehnungen (400/401/403/413/422) werden nicht mehr wiederholt und nicht in die Outbox gelegt; eine
  abgelehnte Outbox-Datei blockiert die Warteschlange nicht mehr. Warnungen erscheinen höchstens alle 5 Minuten
  (bei 401 mit Hinweis auf einen abweichenden `shared-secret`).
- Neu: `SessionDisconnectEvent` → Geysers Trennungsgrund (Client geschlossen, Zeitüberschreitung, gekickt …) im Profil
  und für die Diagnose. Wird nie an einen späteren Login gehängt.
- Neue skalare ClientData-Felder: `ThirdPartyName`, `SkinGeometryDataEngineVersion` (dekodiert), `IsEditorMode`,
  `CapeImageWidth/Height`; dazu `isLinked()` (Floodgate-Verknüpfung) und `platform()`/`version()` als Fallback.
- Gebaut und geprüft gegen die echte Geyser-Distribution **2.12.1 Build 1284** (03.11.2026). Mindest-API weiterhin
  2.4.0; `bridge.properties`, Datenordner und Schlüssel bleiben unverändert.

## 1.4.1 – Reliable delivery

- Retries HTTP 409 responses with a new signed timestamp and nonce.
- Learns bounded clock offset from the Admin Suite response header.
- Refreshes stale outbox envelopes before delivery.
- Keeps session payload fields, protocol 2 and existing `bridge.properties` fully compatible.

## 1.4.0 – Simplex Admin Suite 7.0 integration

- Adds the Geyser-exposed PlayFab identifier as an additional diagnostic field.
- Keeps all enforcement inside the Paper plugin; the bridge never punishes players.
- Updates package metadata and documentation for Simplex Admin Suite 7.0.0.

## 1.3.0 – Signed heartbeat and connection diagnostics

- Added a HMAC-signed `/v1/heartbeat` request every 60 seconds.
- Reports bridge version and protocol without waiting for a new Bedrock login.
- Keeps the existing `/v1/session` payload, outbox and shared-secret configuration fully compatible.
- Throttles heartbeat warnings to avoid log spam while the Paper backend is offline.
- Extended the offline end-to-end harness to verify session and heartbeat signatures.

## 1.3.0

- Kompatibilitätsrelease für Simplex Admin Suite 3.0.0.
- Bridge-Version wird in jeder Sitzung zur Diagnose übertragen.
- Bestehende Bedrock-Geräte-, Plattform-, Eingabe-, Netzwerk- und Identitätsfelder bleiben erhalten.
- Keine eigene Banlogik; globale Geräte-/IP-Sperren werden in der Admin Suite ausgewertet.

## 1.1.0

- Zusätzliche Bedrock-ClientData-Felder einschließlich Platform IDs, GUI-Skalierung, Arm-Größe, Ping und Sichtweite.
- HMAC-authentifizierte lokale Übertragung und optionaler Outbox-Retry.
