# Simplex Panel 4.4.0 – Live-Schalter, Befehle & LuckPerms

Benötigt Admin Suite 10.1.0 für Befehle und LuckPerms-Vorlagen; alles andere funktioniert auch mit 10.0.x.
IPA einfach drüber installieren (gleiche Bundle-ID und Signatur) – Verbindungen, Zugänge, Layouts und Widgets bleiben erhalten.

## Behoben

- **„HTTP 400: Bestätigung abgelaufen, bereits verwendet oder nicht für diesen Schlüssel“.** Jede Änderung aus
  *Ränge & Rechte*, *Rechte & Sperren*, *Lands* und dem Rang im Profil schlug fehl: Die App las das
  Bestätigungsticket erst, nachdem der Dialog es schon verworfen hatte. Das Ticket wird jetzt beim Tippen auf
  „Ausführen“ übernommen.

## Echte Schalter – an = erlaubt, aus = gesperrt

- **Rechte & Sperren:** Jeder Bereich (Truhen, Shulker, Türen …) und jedes weitere Recht (Chat, PvP, Nether …) hat einen
  Schalter, der den aktuellen Stand zeigt. Ausschalten sperrt sofort mit der oben gewählten Gültigkeit, Dauer und dem
  Grund; Einschalten gibt sofort frei (auch alte Einzelsperren). Einzelrechte fragen beim Ausschalten nach der Dauer.
  Geerbte Sperren sind als „geerbt“ markiert und werden beim Hauptkonto aufgehoben.
- **LuckPerms:** Rechte einer Gruppe und eigene Rechte eines Spielers als Schalter (true/false), live gespeichert.
- Die Werte werden beim Öffnen live geladen; ein umgelegter Schalter bleibt stehen, bis der Server bestätigt.

## Befehle pro Spieler (neu)

- Spielerprofil → **Befehle erlauben & sperren** (auch aus Rechte & Sperren und dem LuckPerms-Spieler).
- Alle Befehle des Servers, gruppiert **nach Art** (Teleport, Chat, Moderation, Wirtschaft …), **nach Plugin** oder
  **A–Z**, mit Suche und Filter „Erlaubt/Gesperrt“. Große Listen bleiben flüssig.
- Ausschalten sperrt mit Dauer, Grund und **Alt-Vererbung** (Standard an, abschaltbar). Einschalten gibt frei; fehlt dem
  Rang das Recht, setzt die App es mit Vollzugriff direkt in LuckPerms. Gruppen-Menü: „Alle erlauben/sperren“.
- Admin-Zentrale → **Befehle:** Spieler mit eigenen Befehlsrechten und Schalter **für alle Spieler** (globale Sperre).

## Rang im Profil & LuckPerms

- Das Spielerprofil zeigt **Präfix, Suffix, Chat-Vorschau, primäre Gruppe und alle Gruppen**; Gruppen direkt zuweisen
  (dauerhaft, 1/7/30 Tage oder als einzige Gruppe) und per Wischen entfernen.
- **Vorlagen** in *Ränge & Rechte*: Spieler, VIP, Builder, Supporter, Moderator, Admin – Gruppe mit Gewicht, Präfix,
  Erbe von default und passenden Rechten in einem Schritt (bestehende Rechte bleiben).

## Aufgeräumt

- Doppelte Einstiege entfernt: „Rang & LuckPerms-Rechte“ stand in Rechte & Sperren zweimal; die Spielersuche dort nutzt
  jetzt dieselbe Live-Suche wie überall; ein ungenutztes zweites Rechte-Formular entfällt.
