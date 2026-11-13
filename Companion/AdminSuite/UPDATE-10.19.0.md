# Admin Suite 10.19.0

Teil von Simplex Panel 5.12.0 – die vollständige Beschreibung steht in [UPDATE-5.12.0.md](../../UPDATE-5.12.0.md).

- **Fix:** Präfixe und Suffixe (LuckPerms-Ränge) vertauschten sich über den Köpfen und in der Tabliste, vor allem zwischen Bedrock-Spielern. Ursache war ein Teamname aus den ersten 10 UUID-Zeichen – bei Floodgate-UUIDs immer `0000000000`, also ein gemeinsames Team für alle Bedrock-Spieler. Jetzt eindeutige Teams aus der vollständigen UUID, automatisches Aufräumen alter geteilter Teams, genau ein Spieler pro Suite-Team.
- **Fix:** Die Suite gab die Tabliste fälschlich an ein „fremdes Plugin“ ab, wenn Paper den gesetzten Namen mit umcodierten Farbcodes zurücklieferte.
- **UltraServers:** `/sas app status` erklärt den Haken für HTTP-Plugins beim Anlegen der Allocation (Gcore-DDoS-Schutz).
- Diagnose `/v1/data/nameplates` meldet `sharedTeam`.

Keine Datenmigration, keine Konfigurationsänderung. JAR ersetzen und Server neu starten.
