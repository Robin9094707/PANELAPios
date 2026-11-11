# Admin Suite 10.16.1 – Fix: Verlässt ein Java-Spieler den Server, fliegen alle anderen Java-Spieler

Baut auf 10.16.0 (Panel 5.9.0) auf. **Nur die JAR tauschen** – die App bleibt 5.9.0, alle Daten und Einstellungen bleiben erhalten.

## Was passiert ist

Verließ ein Java-Spieler den Server, wurden fast gleichzeitig alle anderen Java-Spieler getrennt. Bedrock-Spieler (Geyser) blieben verbunden. Auf dem Server sah das wie ein normales „Disconnected“ aus, die Clients zeigten „Network Protocol Error“.

## Ursache

Der **Paket-Guard** (Teil von Vanish/Stealth) hält Pakete über verborgene Spieler von anderen Clients fern, auch die Scoreboard-Teams (Rang-Präfix über dem Kopf, Kollisionsteam).

Jeder Java-Spieler ist kurz nach dem Beitritt verborgen, solange die automatische Client-Prüfung bzw. die Beitrittsprüfung läuft. Bedrock-Spieler werden nicht geprüft. In dieser Zeit hat der Guard die Meldung „Spieler X ist Team Y beigetreten“ an alle anderen zurückgehalten. Danach war der Spieler wieder sichtbar, die Team-Zuordnung wurde aber nie nachgeliefert.

Beim Verlassen räumt die Suite das Rang-Team des Spielers auf. Der Server schickt allen „Spieler X verlässt Team Y“, und der Guard ließ das durch, weil X ja sichtbar war. Ein Java-Client, der X nie in Team Y kannte, wirft dann `IllegalStateException: Player is either on another team or not on any team` und trennt sich selbst. Das passiert bei allen Java-Spielern gleichzeitig. Geyser ignoriert so etwas, deshalb blieben Bedrock-Spieler verbunden.

Dasselbe konnte nach dem Beenden von Vanish oder des Stealth-Modus passieren.

## Fix

- Der Guard führt pro Verbindung ein **Team-Protokoll**: Er merkt sich genau, welche Team-Einträge dieser Client nie bekommen hat.
- Ein „Team verlassen“ für einen solchen Eintrag wird verworfen, statt den Client abstürzen zu lassen.
- Sobald der Spieler wieder sichtbar ist, schickt der Guard die fehlende Team-Zuordnung nach. Rang-Präfix und Namensfarbe stimmen danach auch bei Spielern, die während der Prüfung beigetreten sind.
- Beim Deaktivieren des Plugins bzw. Guards bekommt jeder Client vorher alle zurückgehaltenen Einträge, damit spätere ungefilterte Pakete sicher sind.
- Läuft während des Verlassens noch ein Sichtbarkeits-Tick, wird die schließende Verbindung nicht erneut injiziert.

## Installation

1. Server stoppen.
2. `SimplexAdminSuite-10.16.0.jar` durch `SimplexAdminSuite-10.16.1.jar` ersetzen.
3. Server starten.
