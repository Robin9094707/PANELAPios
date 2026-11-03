# Admin Suite 10.0.1 – Rechte & Sperren an einem Ort

Plug & Play: JAR ersetzen. Keine Daten werden umgeschrieben.

## Eine Rechteverwaltung statt zwei

Bisher gab es die klassischen Spielerrechte (50 Schalter) und zusätzlich die Rechte-Sperren aus 10.0.0 – Truhen, Türen,
Shulker usw. ließen sich dadurch an zwei Stellen ändern. Jetzt gilt:

- Klassische Rechte, die ein Bereich abdeckt (`chest`, `shulkers`, `enderchest`, `doors`, `buttons`, `levers`,
  `redstone`, `furnaces`, `hoppers`, `jukeboxes`, `lecterns`, `campfires`, `crafting`, `enchanting`, `anvils`,
  `brewing`, `beacons`, `beds`, `respawn-anchors`, `item-frames`, `armor-stands`, `animals`, `villagers`, `build`,
  `break`) werden nur noch über den Bereich verwaltet. Bestehende Sperren bleiben wirksam, erscheinen im Bereich als
  „alte Einzelsperre“ und werden beim Aufheben des Bereichs mit freigegeben.
- Alle übrigen Rechte (Chat, Farben, Formatierung, Privatnachrichten, Erwähnungen, Befehle, Schilder, Bücher, PvP,
  Projektile, Tränke, Nahrung, Sprengstoff, Feuer, Eimer, Droppen, Aufsammeln, Angeln, Interaktionen, Bewegung/Freeze,
  Nether, End, Elytra, Fahrzeuge, Teleport) stehen in derselben Ansicht – sperrbar dauerhaft oder für eine Dauer.
- „Alle Sperren aufheben“ gibt Bereiche **und** Einzelrechte frei.
- Aktion `locks.update` kennt zusätzlich `right-block` (`right`, `duration`) und `right-allow` (`right`);
  `GET /v1/data/access-locks?player=` liefert `rights`, `legacy`, `profile`, `blockedRights`, `inherit`.
  Feature `player-rights-v1`.

## Überall derselbe Einstieg

- **Spiel (Java-Inventar und Bedrock-Formulare):** Im Spielermenü öffnet „Rechte & Sperren“ (bisher „Rechte-Sperren“
  bzw. „Rechte & Freeze“) die gemeinsame Ansicht mit „Weitere Rechte“ inkl. Dauerauswahl. `/rechtesperre` →
  „Spieler suchen“ findet auch Teile von Namen und offline Spieler.
- **App und Webpanel:** Spielerprofil → Rechte & Sperren; Übersicht mit Live-Spielersuche (Autovervollständigung).
- Neuigkeiten erscheinen nach dem Update einmal pro Konto (Webpanel) bzw. einmal in der App.

## Tests

`Release1000Harness` erweitert (105 Checks): jedes klassische Recht ist genau einmal zugeordnet, abgedeckte Rechte
lassen sich nicht doppelt sperren, Dauer-Zusammenfassungen, fehlendes Spielerprofil wird verständlich gemeldet.
