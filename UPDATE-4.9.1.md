# Simplex Panel 4.9.1 · Build 39

Dieses Fehlerbehebungs-Update benötigt nur die neue App. Die bereits bereitgestellte Admin Suite 10.6.0 bleibt unverändert kompatibel. Keine neue Kopplung, keine neue JAR und kein Datenreset.

- **Server-Schnellzugriffe** stehen oben auf der Startseite. Jede Kachel ist fest an genau einen gespeicherten Server gebunden: Admin Suite, Werbung, Spieler und Tickets. Es erscheint keine zusätzliche Serverauswahl.
- Über **Anpassen** lassen sich Server benennen, Werkzeuge pro Server ein-/ausblenden und ein Standard für die allgemeinen Zugriffe wählen. Ohne gewählten Standard wird bei allgemeinen Zugriffen weiterhin bewusst nachgefragt; explizite Server-Kacheln und Kurzbefehle wechseln niemals unbemerkt den Server.
- Die allgemeine Serverauswahl verwendet direkte Schaltflächen mit lesbaren Namen. Der gewählte Server bleibt beim Öffnen und Wiederholen derselbe.
- Der Verbindungsaufbau hat ein absolutes Zeitlimit von zwölf Sekunden. Auch gepinntes HTTPS beachtet die eingestellte Wartezeit. Abbruch, fehlender Schlüssel, falsche Serveridentität und Netzfehler beenden den Ladezustand und bieten Wiederholen an. Zertifikats- und Identitätsprüfungen bleiben erhalten.
- Werbung und andere native Werkzeuge warten nicht auf die zusätzliche Funktionsliste. Ist diese Liste nicht verfügbar, bleiben die bereits verifizierten Werkzeuge benutzbar.
- Auch der iOS-Kurzbefehl **Werbeverwaltung öffnen** hat einen optionalen festen Server. Bereits ausgewählte Werbe-Orte werden lokal aufgelöst; die App lädt und prüft die tatsächliche Werbung weiterhin vor dem Kopieren und Öffnen. Vorschlagsabfragen haben kurze Zeitlimits und blockieren sich nicht gegenseitig.
- Namen und Schnellzugriffs-Einstellungen liegen zusätzlich auf diesem Gerät. Bestehende Server-UUIDs, Schlüssel, Zertifikatspins, App-ID, Sitzungen und Werbe-Timer bleiben erhalten.

## Installieren und zurückgehen

Die neue unsignierte IPA wie bisher mit derselben App-ID und denselben Signierberechtigungen signieren und als Update installieren. Die vorhandene App nicht vorher löschen. Die JAR bleibt 10.6.0. Bei Bedarf kann die bisherige IPA 4.9.0 mit derselben Signierung wieder installiert werden; diese Korrektur verändert keine Serverdaten.

Die Änderungen bleiben im Pull Request und werden nicht automatisch gemergt oder veröffentlicht.

## Prüfungen

Fokussierte Tests für feste Serverzuordnung, ältere gespeicherte Zugänge, Einstellungen pro Server, kurze Anfragen, absolute Zeitlimits, Abbruch, Wiederholen, Wechsel zwischen Servern und einen Ausfall der Funktionsliste. Eine lokale echte TLS-Testgegenstelle prüft Zertifikatspin, Redirect-Ablehnung, kurze Zeitlimits und Abbruch. Simulator-Prüfungen öffnen Werbung für Simplex SMP, wählen Simplex Test, prüfen einen ausgefallenen Server und anschließend einen weiterhin funktionierenden Zugang. Keine Prüfungen gegen echte Nutzer-Server oder mit echten Zugangsdaten.
