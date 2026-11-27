# Simplex Panel 5.17.0 und Admin Suite 10.24.0 – Länder-Zentrale & neue Identitätssignale

IPA installieren und `SimplexAdminSuite-10.24.0.jar` einsetzen (alte JAR entfernen, Server neu starten). Alle Konten, Schlüssel, TLS-Identität, Spielerdaten und Configs bleiben gültig. SentinelBridge 1.6.0 bleibt unverändert.

## Länder-Zentrale (Lands) – App, Webpanel und API

Voraussetzung: das Lands-Plugin (geprüft gegen die Lands-API von Lands 9.3). Ohne Lands erscheint nur ein Hinweis. Die Suite spricht ausschließlich die offizielle Lands-API an – Lands speichert, feuert seine Ereignisse und bleibt unverändert. Länder **erstellen** geht weiterhin nur ingame (die API bietet das nicht an).

- **Dashboard** (Werkzeug „Länder“): alle Länder mit Besitzer, Mitgliedern, Chunks (mit Limit), Landbank, Level, Nation, Online-Spielern und Gebieten. Summen für Länder, Spieler in Ländern, Besitzer, Chunks, Gebiete, Online, Landbanken, Nationen. Sortierbar nach Größe, Mitgliedern, Online, Bank, Alter und Name; Suche nach Land, Besitzer oder Nation.
- **Land-Detail** mit Bereichen:
  - *Mitglieder*: Spieler suchen und hinzufügen – direkt mit Rolle –, Rolle ändern, entfernen, bannen; Bannliste (bekannte Spieler) mit Entbannen.
  - *Rollen & Rechte*: jedes Aktions- und Verwaltungsrecht jeder Rolle als Schalter (deutsche Namen), Rang hoch/runter, umbenennen, löschen. Die Besitzer-Rolle wird nie vergeben oder umbenannt; der Besitzer kann nicht entfernt oder gebannt werden.
  - *Natur-Regeln*: Monster, Phantome, Feuer, TNT, Kolben, Wasser- und Lavafluss, Wachstum, friedlicher Modus … als Schalter.
  - *Gebiete*: alle Untergebiete mit Größe, Mietern und Steuer. Jedes Gebiet hat eigene Mitglieder, Rollen, Rechte und Natur-Regeln; umbenennen, Steuer, löschen (wenn die installierte Lands-Version das per API kann).
  - *Einstellungen*: Name, Begrüßungstext, Steuer, Landbank (ein-/auszahlen, setzen), Besitzer ändern, Spawn. **Land löschen** nur mit dem Recht `lands.delete`, Vollzugriff und Eingabe des Landnamens.
  - *Mehr*: Nation, Verbündete, Feinde, Kriegsstatistik und der Land-Posteingang.
- **Spielerprofil → Länder**: eigene Länder (Hauptland), Mitgliedschaften mit Rolle und Untergebieten, Land-Banns. Direkt zu einem Land hinzufügen (mit Rolle), Rolle ändern, entfernen, entbannen – oder aus allen Ländern auf einmal entfernen (eigene Länder bleiben).
- Der Admin-Zugriff über LuckPerms (`lands.bypass.*`, `lands.admin.*`) liegt jetzt als Unterseite in der Länder-Zentrale.
- **Rechte**: Ansehen `data.lands`, Ändern `lands.manage` (in der Admin-Vorlage enthalten), Länder löschen `lands.delete` (kritisch, nur mit Vollzugriff, nicht in Vorlagen). Jede Änderung steht im Admin-Log.

## Neue Identitätssignale (experimentell) – Java und Bedrock

Recherche (Stand November 2026): Neu bekannt sind vor allem Ressourcenpaket-Cache-Fingerabdrücke („TrackPack“). Sie nutzen eine Client-Lücke aus, werden von Clients wie Meteor/Wurst bereits blockiert und sind ohne Einwilligung datenschutzrechtlich heikel (§ 25 TTDSG). Deshalb bewusst **nicht** eingebaut. Stattdessen nutzt die Suite nur Daten, die der Client ohnehin sendet:

- **Gleicher seltener Skin**: Mojang speichert Skins nach Bild-Hash – gleiche Textur-ID heißt identisches Bild. Geyser lädt Bedrock-Skins genauso hoch, daher auch für Bedrock. Zählt nur, wenn höchstens 3 Konten den Skin teilen (Standard-Skins nie). +30 Punkte.
- **Kontowechsel am selben Anschluss**: Konto B kommt innerhalb von 2 Minuten nach Konto A über dieselbe IP – mindestens zweimal. +10 je Wechsel (max. 40).
- **Gleicher IPv6-Anschluss (/64)**: ein Haushalt; +20. Ein IPv4-/24-Netz zählt nur zusätzlich zu einem anderen Signal (+8).
- **Bedrock-Installations-ID** (ClientRandomId, wurde schon erfasst): jetzt als Hinweis (+60), nur wenn selten.

Alle vier sind **nur Hinweise**: Sie erscheinen mit „(experimentell)“ in den Alt-Beweisen, lösen nie automatische Strafen oder Kaskaden aus und sind einzeln abschaltbar unter `alt-detection.experimental` in der `config.yml` (fehlt der Abschnitt, gelten die Standardwerte). Gespeichert wird nur `identity-signals.yml` (Textur-IDs und Wechsel-Paare); beim Zurücksetzen einer Spieleridentität wird auch dieser Teil gelöscht.

## Aufgeräumt
- Ultrabanns und Ruhezeiten haben nur noch ihren Eintrag im Werkzeug-Verzeichnis (keine zweiten Links in „Banns & Freigaben“ bzw. „Ereignisse“).
- Die alte, reine Lands-Liste ist durch die Länder-Zentrale ersetzt (für ältere Suites bleibt sie als Rückfall).

## API
```
GET  /v1/lands?q=&sort=chunks|members|online|balance|newest|name
GET  /v1/lands/land?id=<ULID>
GET  /v1/lands/area?land=<ULID>&area=<ULID>
GET  /v1/lands/player?player=<UUID|Name>
POST /v1/lands  {operation, land, area?, player?, role?, flag?, value?, …, confirmed?}
     operation: trust | untrust | remove-everywhere | set-role | ban | unban | set-owner | rename | title |
                role-flag | role-flags | natural-flag | role-rename | role-priority | role-delete | tax | balance |
                area-delete | delete
```
Features in `/v1/health`: `lands-center-v1`, `identity-signals-v1`.
