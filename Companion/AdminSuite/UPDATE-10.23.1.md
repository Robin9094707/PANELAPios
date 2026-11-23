# Simplex Panel 5.16.1 und Admin Suite 10.23.1 – Bugfix

IPA installieren und `SimplexAdminSuite-10.23.1.jar` einsetzen. Alle Daten, Schlüssel und Einstellungen bleiben unverändert.

## App
- **Ein Tipp öffnet ein Werkzeug.** Im Spielerprofil (z. B. „Offline-Inventar“) und im Rückblick öffneten sich beim Tippen auf eine Kachel alle Werkzeuge der Kachelreihe nacheinander, sodass man mehrfach zurück musste. Ursache: mehrere Navigationslinks in einer Listenzeile. Die Kacheln sind jetzt Knöpfe mit genau einem Ziel.
- **Jeder Menüpunkt nur einmal.**
  - Spielerprofil: Inventar, Snapshots, NBT, Steckbrief, Verlauf, Punkte, Geräte, Rechte, Befehle, Alt-Accounts, PowerBot-Akte, Strafen, Chat-Ruhe, Anti-Lag und Client-Prüfung stehen nur noch im Werkzeug-Raster. Die alten Linklisten darunter sind entfernt; Nachricht und Notiz stehen nur noch bei den Moderations-Schnellaktionen.
  - Die Kopplungsseite („Kopplung & Verbindung“) und das Direktzugang-Dashboard führen nur noch zu „Alle Admin-Werkzeuge“ statt die Werkzeuge erneut aufzulisten.
  - „Benutzer & Sitzungen“ (vorher „Zugänge & Serverregeln“), Suite-Verwaltung, Neustart und Event-Modus verlinken keine Werkzeuge mehr, die es im Verzeichnis schon gibt.
  - Im Werkzeug-Verzeichnis erscheinen Lag-Karte, Spark, Abstürze und Diagnose-Konsole nur noch in der Diagnose (per Suche weiterhin auffindbar). „Banns & Freigaben“ ist durch die Bann-Zentrale ersetzt.
  - Zwei verschiedene Seiten hießen „Suite-Verwaltung“; die Kopplungsseite heißt jetzt „Kopplung & Verbindung“.
- **Vollständige Texte:** Kacheltitel und -beschreibungen werden nicht mehr abgeschnitten.

## Web
- Listenzeilen (Geräte, Alt-Beweise, Verbindungen, Suchvorschläge) brechen um statt mit „…“ zu enden.
- Spielerprofil: kein zweiter „Aktionen“-Knopf neben dem Werkzeug-Raster, kein doppelter Geräte-Tab, keine doppelte Bann-Zentrale-Kachel. Aktionskacheln zeigen ihre Gruppe statt der technischen Kennung.

## Ingame
- Im `/profil`-Menü gibt es „Chat-Ruhe, Töne & Stummschalten“ genau einmal (der zweite Eintrag in den Einstellungen ist entfernt).
