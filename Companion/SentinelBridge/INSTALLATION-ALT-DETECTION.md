# Installation – SimplexSentinelBridge 1.6.0

1. Geyser vollständig stoppen.
2. `SimplexSentinelBridge-1.6.0.jar` nach `Geyser/extensions/` kopieren.
3. Geyser einmal starten, damit `bridge.properties` erzeugt wird, und wieder stoppen.
4. `host`, `port`, `path` und `shared-secret` mit `alt-detection.bridge` der Admin Suite abgleichen.
5. Port `39092` ausschließlich lokal oder in einem geschützten privaten Netz erreichbar machen.
6. Geyser und Paper starten.

Die Extension ist nur für Bedrock-Sitzungsmetadaten zuständig. Java-Clientdaten werden direkt von der Paper-Admin-Suite erfasst.
