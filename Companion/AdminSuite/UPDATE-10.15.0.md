# Simplex Panel 5.8.0 und Admin Suite 10.15.0

Baut auf 5.7.0 / 10.14.0 auf. Wer 5.7.0 / 10.14.0 installiert hat, ersetzt nur JAR und IPA – alle Daten (Ablage, Automationen, Smart-Neustart, KI-Chats, Konten und Rechte) bleiben erhalten. SentinelBridge 1.6.0 bleibt unverändert.

## Invsee wie im Spiel – online und offline

- Inventar, Rüstung (Kopf, Brust, Beine, Füße), Nebenhand und Endertruhe für **Online- und Offline-Spieler** ansehen und bearbeiten.
- **Zweiter Spieler**: In App (iPad nebeneinander) und Webpanel einen zweiten Spieler – online oder offline – daneben öffnen und Items direkt **verschieben, stapeln oder tauschen**. Menge wählbar, „In ersten freien Platz“, „In Ablage“ mit Grund.
- **Keine Duplikate**: Jede Seite wird mit Revision geprüft. Offline-Dateien werden mit Sicherung, atomarem Schreiben und Prüfsumme gespeichert; scheitert ein Schritt, werden bereits geschriebene Dateien zurückgesetzt. Live-Änderungen werden erst ganz am Ende angewendet.
- **Verlustfrei**: Items in Offline-Dateien werden als vollständiges NBT übernommen – Shulker-Inhalte, Verzauberungen, Namen, Lore und Komponenten bleiben exakt erhalten. Dateien einer anderen Minecraft-Datenversion werden nur angezeigt, nicht verändert.
- Live-Aktualisierung (online alle 2–3 s, offline alle 4 s), Item-Details (Verzauberungen, Haltbarkeit, Lore, Shulker-Inhalt) per langem Druck bzw. Rechtsklick, Glitzer für verzauberte Items und Haltbarkeitsbalken.
- Die Ablage kann jetzt auch an Offline-Spieler zurückgeben.
- Rechte: online wie bisher `inventory.edit` (manage); Offline-Seiten zusätzlich vollen Zugriff und `player-nbt.write`. Jede Verschiebung landet im Item-Verlauf und im Admin-Log.

## Texturen

- Truhen (auch Fallen-, Ender- und Kupfertruhen), Banner, Betten, Schilde und Köpfe werden jetzt korrekt gezeichnet. Andere Items folgen der Modellkette des Ressourcenpakets (inkl. animierter Blöcke und Laub-/Gras-Färbung). Eigene Ressourcenpakete haben Vorrang.

## Schnellzentrale

- Eigene Kacheln für Dashboard und Startseite in App und Web: Neustart, Neustart abbrechen, Smart-Neustart an/aus, Wartung, Chat an/aus, Slowmode, Ankündigung, Welten speichern, Automation ausführen oder ein Werkzeug öffnen.
- Titel, Symbol, Farbe und Wert pro Kachel frei einstellbar, bis zu 24 Kacheln, kritische Aktionen mit Bestätigung. Gespeichert pro Konto auf dem Server.

## KI

- Mehr Kontext: Spieleraktivität (letzte Aktionen, Chat, Kämpfe, Items mit Uhrzeit), PowerBot-Fälle und der Rückblick.
- **Ungefähre Namen**: „Miss Caitlin“ findet „MissCaitlyn“, „alpha craft“ findet „AlphacraftMC“. Ist ein Name nicht eindeutig, fragt die KI nach („Meintest du …?“). Bei Aktionen wird der erkannte Spieler vor der Bestätigung angezeigt.
- Klarere Antworten mit Uhrzeiten (HH:mm) und fett gesetzten Namen. Jede schreibende Aktion braucht weiterhin eine Bestätigung.

## Webpanel: Rechteverwaltung repariert

- Speichern vieler Rechte schlug wegen einer Längenbegrenzung fehl – behoben.
- Alte, inzwischen unbekannte Rechte blockierten das Speichern – sie bleiben jetzt erhalten.
- Neuer Editor wie in der App: Filter (Alle, Aktiv, Lesen, Ändern, Kritisch), aufklappbare Gruppen mit Zählern und eine Änderungsübersicht.

## Update

1. Server stoppen, `plugins/SimplexAdminSuite` sichern.
2. `SimplexAdminSuite-10.15.0.jar` statt der alten JAR einsetzen, Server starten.
3. Die unsignierte IPA wie gewohnt mit derselben Signatur und Bundle-ID über die bestehende App installieren.
