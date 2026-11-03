# Admin Suite 10.0.0 – Großes Rechte-Update

Gehört zu Simplex Panel 4.3.0. **Plug & Play: einfach die JAR ersetzen.** API-Schlüssel, App-Benutzer, Sitzungen,
TLS-Zertifikat, Bark-Abos, Tickets, Admin-Log, LuckPerms-/Lands-Daten und alle Plugin-Daten bleiben unverändert gültig.
Neu entsteht nur bei Bedarf `access-locks.properties`; der „Changelog gesehen“-Stand liegt in
`app-api/web-prefs.properties`. Neue Konfigurationswerte (`access-locks.*`) haben sinnvolle Standardwerte und müssen
nicht eingetragen werden.

## Rechte-Sperren

Pro Spieler einzeln schaltbar, temporär oder dauerhaft, mit Grund:

| Bereich | Umfasst |
|---|---|
| `chest` Truhen | Truhen, Fallen-/Kupfertruhen, Truhenloren, Truhenboote |
| `shulker` Shulkerkisten | alle Farben |
| `container` Andere Behälter | Fass, Trichter, Spender, Werfer, Öfen, Braustand, Crafter, Regale, Krug, Lesepult, Plattenspieler, Lagerfeuer, Trichterlore |
| `enderchest` Endertruhen | öffnen, abbauen, sprengen |
| `doors` Türen & Tore | Türen, Falltüren, Zauntore |
| `redstone` | Knöpfe, Hebel, Druckplatten, Verstärker, Komparatoren, Sensoren, Notenblöcke, Stolperdrähte |
| `utility` Arbeits-/Nutzblöcke | Werkbank, Amboss, Zaubertisch, Betten, Seelenanker, Glocke, Leuchtfeuer, Kessel … |
| `decor` | Rahmen, Leuchtrahmen, Bilder, Rüstungsständer |
| `animals` | Tiere, Dorfbewohner, Allays |
| `build` | jeden Block setzen/abbauen, Eimer, Feuer, Loren/Boote |
| `pickup` / `pickupall` | wertvolle bzw. alle Items, die von anderen Spielern stammen |

- **Wirklich gesperrt**: Gesperrtes kann weder benutzt noch abgebaut, gesprengt (TNT inkl. Kettenreaktion,
  Endkristalle, Betten/Seelenanker, TNT-Loren, abgelenkte Feuerbälle, Creeper mit dem Spieler als Ziel) noch per
  Trichter/Trichterlore leergezogen werden. Auch der Block unter einer Tür/Druckplatte bzw. hinter einem Knopf/Hebel ist
  geschützt. Alle Prüfungen laufen mit Priorität HIGHEST – sie gelten **auch in Ländern, in denen der Spieler
  vertraut ist** und unabhängig von Lands-Rollen. Zusätzlich sperrt ein Sicherheitsnetz jedes Behälter-Inventar.
- **Geltungsbereich** je Sperre: `everywhere`, `own` (nur im eigenen Land als Besitzer erlaubt) oder `own-wild`
  (eigenes Land und Wildnis erlaubt). Ohne Lands zählt alles als Wildnis. Lands-Fehler zählen als „fremd“ (sicher).
- **Klauer**: Items, die ein anderer Spieler gedroppt, beim Tod verloren oder durch Abbauen erzeugt hat, werden
  erkannt; wertvolle Items (`access-locks.pickup.valuable-items`, verzauberte Items und gefüllte Shulker) kann ein
  Klauer nur noch im eigenen Land aufheben. Eigene Items sind nie betroffen.
- Der Spieler sieht bei jedem Versuch in der Actionbar und (gedrosselt) im Chat, was gesperrt ist, Grund, Restzeit und
  wo es erlaubt ist; beim Setzen, Aufheben und Ablaufen wird er informiert. Alles landet im Admin-Log.
- Vorlagen: `thief` (Klauer), `containers`, `griefer`, `lockdown`.

**Bedienung überall gleich**
- Spiel (Java-Inventar und Bedrock/Floodgate-Formulare): Admin-Zentrale → *Rechte-Sperren*, Moderation →
  *Rechte-Sperren*, im Spielerstatus der Moderation, oder `/rechtesperre [Spieler]`
  (`/rechtesperre <Spieler> sperren chest,shulker own-wild 7d Grund`, `vorlage thief 3d`, `aufheben chest`, `clear`, `info`).
  Recht: `simplexadminsuite.accesslocks` (Inhaber von `simplexadminsuite.restrictions` dürfen es ebenfalls).
- Discord: `/rechtesperre anzeigen | liste | sperren | vorlage | aufheben` (Änderungen nur mit vollem Admin-Zugriff).
- App & Webpanel: `GET /v1/data/access-locks[?player=]` (Recht `data.access-locks`), Aktion `locks.update`
  (Recht `action.locks.update`, mit Bestätigung).

## LuckPerms

- `GET /v1/data/luckperms-permissions`: alle bekannten Rechte (Bukkit-Registrierungen mit Beschreibung,
  LuckPerms „known permissions“, Rechte aus Gruppen) für die Autovervollständigung.
- Neue Aktionen `user-prefix` / `user-suffix` (eigenes Präfix/Suffix je Spieler); Präfix-/Suffix-Editor mit Farbpalette,
  Hex-Farben und Live-Vorschau in App und Webpanel. Weiterhin genau ein streng validierter `lp`-Befehl pro Änderung.

## Rechte einfacher verwalten

- Rechtekatalog der App-Benutzer mit verständlichem Titel, Bereich, Erklärung und Stufe (Lesen/Ändern/Kritisch) sowie
  Rollen-Vorlagen (Supporter, Moderator, Admin, Vollzugriff) in `GET /v1/data/access`.
- Changelog einmal pro Konto und Version: `GET /v1/changelog`, `POST /v1/changelog/seen`.

## Fehlerbehebungen

- Webpanel → Moderation → Banns: Die Quellen *Minecraft*, *IP* und *LiteBans* lieferten einen Fehler; jetzt werden sie
  korrekt angezeigt und lassen sich aufheben. Reports lassen sich nach Status filtern und mit Notizen bearbeiten.

## Tests

`tools/tests/Release1000Harness.java` (95 Checks): Dauer-/Bereichsprüfung, Material-Zuordnung, Abbau-, Tür- und
Trichter-Schutz mit synthetischen Blöcken, Geltungsbereiche, Persistenz und Ablauf, HTTPS-Routen und Bestätigung,
Rechte, Rollen-Vorlagen, LuckPerms-Präfixe inkl. Injection-Versuchen, Discord-Schema, Changelog je Konto,
Webpanel-Dateien. Alle bisherigen Harnesses laufen unverändert weiter.
