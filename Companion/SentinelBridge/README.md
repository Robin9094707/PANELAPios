# SimplexSentinelBridge 1.6.0

Geyser-Erweiterung für SimplexAdminSuite (empfohlen ab 10.3.0; ältere Suites bleiben kompatibel). Kein Paper-Plugin: Die JAR gehört in den `extensions`-Ordner der verwendeten Geyser-Instanz. Die Brücke führt keine eigene Spieler-/Ban-Datenbank und bestraft niemanden.

## Direktes Update

Geyser und Paper stoppen, die alten beiden JARs entfernen und durch die neuen JARs ersetzen. Vorhandene Datenordner, `bridge.properties`, den Brückenschlüssel und die Suite-Konfiguration behalten. Die Erweiterungs-ID und der Datenordner bleiben identisch. Neue lokale Optionen haben Defaults, wenn sie in einer bestehenden Datei noch fehlen. Beide Prozesse vollständig starten; kein Plugin-Hot-Reload nötig.

## Neu in 1.6.0

- Kein HTTP 422 mehr bei frühen Bedrock-Logins (siehe [CHANGELOG](CHANGELOG.md)); keine Wiederholungen bei dauerhaften Ablehnungen.
- Bedrock-Trennungsgrund aus Geyser (`SessionDisconnectEvent`), Drittanbieter-Name, Skin-Geometrie-Engine, Editor-Modus,
  Cape-Abmessungen und Floodgate-Verknüpfung – alles im Spieler-Steckbrief der App und des Webpanels.

## Daten

Bisherige XUID/PlayFab/Device-ID/SelfSignedId/ClientRandomId, Plattformdaten, Sprache und Netzwerkdaten bleiben erhalten. Neu werden, soweit vom jeweiligen Client und Geyser verfügbar, folgende skalare Daten erfasst:

- Speicherklasse, maximale Gerätesichtweite, Grafikmodus beim Login, Plattformtyp, Client-Chunkgenerierungsfähigkeit.
- Bedrock-Protokoll, Join-Adresse und -Port, angeforderte Sitzungs-Sichtweite, Login-Eingabe und aktueller Eingabemodus.
- Persona-Profilhash, Skin-ID, Persona/Premium-Status, Skinfarbe und -abmessungen, Cape-ID sowie Skin-Flags.
- Experimentelle PlatformUserId und Diagnose des gemeldeten Device-ID-Formats. Unbekannte Plattformformate bleiben ausdrücklich unbekannt.
- Optional Party-ID/Leitung; standardmäßig aus. Editor-/Filter-Metadaten sind separat schaltbar.
- Erfassungsphase, Zeitpunkt, Datenquelle und Geyser-Version, soweit die Installation sie bereitstellt.

Nur ausdrücklich erlaubte primitive ClientData-Felder werden aus Geysers bereits verarbeitetem Original-JWT im RAM gelesen. Das vollständige JWT, Authentifizierungstokens, Skin-Bilddaten, Geometrie und Persona-Bestandteile werden weder übertragen noch in der Outbox gespeichert. Ein vorhandener JWT bedeutet keine Hardware-Bestätigung: Der Client kann seine Geräteangaben verändern.

TitleID und die Microsoft-DeviceConfig sind über die aktuelle normale Geyser-Erweiterungsschnittstelle nicht zuverlässig verfügbar. Sie werden nicht erfunden oder aus ClientData als authentifizierte Identität übernommen. Es gibt keine MAC, Seriennummer oder garantierte plattformübergreifende Hardware-ID.

## Zeitpunkte und Kompatibilität

Login-Snapshot sofort, zweiter Snapshot nach fünf Sekunden, sofern dieselbe Verbindung noch aktiv ist. Danach standardmäßig alle 120 Sekunden eine Aktualisierung aktiver Verbindungen. Die maximale Gerätesichtweite ist vom angeforderten Radius getrennt. Ein früher Standardradius wird nicht als Messwert ausgegeben. Aktuelle Eingabe kommt bevorzugt aus Geysers laufender Input-Cache/API. Ein aktueller Grafikmodus wird nur angegeben, wenn Geyser tatsächlich einen Live-Getter bereitstellt; sonst bleibt ausschließlich der Loginwert.

Die API-Mindestdeklaration 2.4.0 bleibt für ältere Installationen bestehen. Neue API-Methoden werden kompatibel dynamisch aufgerufen; fehlende Methoden lassen Felder leer. Gebaut und geprüft gegen die echte Geyser-Distribution 2.12.1, Build 1284.

## Einstellungen und Transport

Die Suite sendet beim signierten Heartbeat zusätzliche, separat HMAC-signierte Einstellungen zurück. Änderungen aus Minecraft oder Discord greifen innerhalb von höchstens 60 Sekunden. Alte 204-Heartbeats funktionieren weiterhin. Lokale Abschaltungen in `bridge.properties` bleiben vorrangig:

```properties
diagnostics.enabled=true
diagnostics.cosmetics=true
diagnostics.party=false
diagnostics.misc=true
diagnostics.session-refresh=true
session-refresh-seconds=120
scheme=http
```

Host, Port, Pfad und bestehender `shared-secret` werden nicht umgeschrieben. HTTPS ist mit `scheme=https` über einen entsprechend eingerichteten TLS-Endpunkt möglich; die Paper-Schnittstelle selbst bleibt HTTP. HTTP-Weiterleitungen werden nicht verfolgt. HMAC authentifiziert, verschlüsselt jedoch nicht; bei getrennten Hosts einen TLS-Endpunkt oder sicheren Tunnel verwenden.

`spool-failed-payloads` bleibt standardmäßig aus. Bei aktiviertem Spooling wird die Outbox alle 30 Sekunden abgearbeitet, auf 500 Dateien / 8 MiB / 24 Stunden begrenzt und beim ersten Empfängerfehler pausiert. Keine beliebig langen Fehler-Schleifen blockieren die Warteschlange. Die Zeitkorrektur bei HTTP 409 und der vorhandene Schlüssel bleiben erhalten.

## Build

Siehe [BUILDING.md](BUILDING.md). Release-Builds verwenden echte, per SHA-256 fixierte APIs; keine Geyser-API-Stubs werden ausgeliefert.

## Primärquellen für die Umsetzung

- [Geyser Connection API](https://github.com/GeyserMC/Geyser/blob/master/api/src/main/java/org/geysermc/geyser/api/connection/GeyserConnection.java)
- [Geyser BedrockClientData](https://github.com/GeyserMC/Geyser/blob/master/core/src/main/java/org/geysermc/geyser/session/auth/BedrockClientData.java)
- [Geyser Login-Verifikation](https://github.com/GeyserMC/Geyser/blob/master/core/src/main/java/org/geysermc/geyser/util/LoginEncryptionUtils.java)
- [Bedrock ClientData / Protokollimplementierung](https://pkg.go.dev/github.com/sandertv/gophertunnel/minecraft/protocol/login)

Stand der Recherche: 04.10.2026 UTC / 05.10.2026 Deutschland. Diese Felder sind Best-Effort und hängen von der tatsächlichen Geyser-/Client-Version ab.
