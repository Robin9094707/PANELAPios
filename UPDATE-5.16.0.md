# Simplex Panel 5.16.0 und Admin Suite 10.23.0

Ersetze die Plugin-JAR durch `SimplexAdminSuite-10.23.0.jar` und installiere die IPA. Konten, Schlüssel, TLS-Identität, Spielerdaten, Chat-Ruhen und Configs bleiben erhalten. Neue Einstellungen werden mit sicheren Standardwerten ergänzt.

## Paper-Server-Updater

App: **Admin → Alle Werkzeuge → Server-Updater** · Web: **Server → Server-Updater**

- Zeigt den installierten Paper-Build, alle neueren Builds deiner Minecraft-Version und die Änderungen jedes Builds direkt von PaperMC.
- Standard: nur Builds derselben Minecraft-Version. Versionswechsel (z. B. 26.2 → 26.3) und Vorabversionen schaltest du in den Einstellungen frei.
- **Aktualisieren** startet: Download → SHA-256-Prüfung gegen PaperMC → Prüfung der JAR → Sicherung der laufenden `server.jar` → atomarer Austausch → Kontrolle. Bei jedem Fehler wird die alte JAR wiederhergestellt und eine Schritt-für-Schritt-Anleitung für das manuelle Update angezeigt.
- Aktiv nach dem nächsten Neustart; den Neustart mit Countdown planst du direkt im Updater.
- Ein Rollback auf einen älteren Build ist möglich. Gesicherte JARs: `plugins/SimplexAdminSuite/server-updates/backups/`.
- Benötigt das Recht `server-updater.manage` und einen full-Schlüssel. Bricht der Updater mit „Die laufende Server-JAR wurde nicht gefunden“ ab, wird der Server nicht per `java -jar` gestartet – dann die Anleitung nutzen.
- Lädt dein Hoster die Server-JAR bei jedem Start selbst, stelle im Startup-Tab die automatische Aktualisierung bzw. Version passend ein.

## Smart Anti-Lag

App: **Smart Anti-Lag** · Web: **Server → Smart Anti-Lag**

- Erkennt Hotspots je Chunk: Mob-Ansammlungen (auch gleiche Mobart), Item-Haufen, Redstone- und Kolbenmaschinen; Trichter lassen sich pro Hotspot zählen.
- Verursacher: Wer im Umkreis Trichter, Kolben, Observer, Redstone usw. platziert oder Tiere gezüchtet hat, sonst wer in der Nähe ist. Rangliste „Wer verursacht am meisten Last?“.
- Nichts passiert, solange der Server flüssig läuft. Erst bei echter Last (über 48 ms je Tick für 15 Sekunden) **und** einem Hotspot weit über den Grenzen:
  1. freundlicher Hinweis im Chat an Verursacher und Spieler in der Nähe (DE/EN), Team erhält einen Teleport-Link,
  2. Spawnbremse in diesem Chunk (keine Zucht, Spawner, natürliches Spawnen),
  3. nach 2 Minuten anhaltender Last pausieren überzählige Mobs ihre KI – sie bleiben erhalten und bekommen die KI zurück, sobald der Server sich erholt, beim Neuladen des Chunks oder beim Herunterfahren.
- Modi: Aus, Nur erkennen, Erkennen + informieren, Smart (Standard). Alle Grenzen sind einstellbar.
- Aus App und Web: Teammitglied zum Hotspot oder zum Spieler teleportieren, Spieler informieren, Spawnbremse setzen/aufheben, Items entfernen.

## Server-Töne stummschalten

- Spieler wählen mit `/chat töne aus|chat|plugins|alle` oder im `/chat`-Menü: **Chat-Töne aus** (kein Pling bei Chatnachrichten, z. B. Spender-Töne anderer Plugins), **Plugin-Töne aus** (Hinweis-, Menü- und Plugin-Töne; die Welt bleibt hörbar) oder **Stille** (keine Server-Töne).
- Gilt unabhängig von der Chat-Ruhe. Das Team stellt es in App und Web unter **Chat-Ruhe & Stummschaltung** ein.

## Spieler persönlich stummschalten

- `/ignorieren <Spieler>` (oder `/chat spieler`) – Spieler A blendet Spieler B nur für sich aus; alle anderen sehen B weiterhin.
- Auf Wunsch **mit allen Konten** der Person, auch künftigen (über die bestätigte Alt-Erkennung). Welche Konten das sind, sieht der Spieler aus Datenschutzgründen nicht – nur Admins in App und Web mit dem Recht für Alt-Konten.
- Blockiert zusätzlich Flüstern und Anfragen (`tpa`, `mail`, `trade` …; Liste in `player-mutes.contact-commands`).
- Spieler mit Admin-Rechten können nie stummgeschaltet werden.
- Verwaltung: App und Web unter **Chat-Ruhe & Stummschaltung → Persönliche Stummschaltungen**.

## Plugin-Manager

- Plugin-Updates werden mit Versionsnummer im Dateinamen vorgemerkt (`LuckPerms-5.4.150.jar`). Paper ordnet sie beim Start über den Plugin-Namen zu und übernimmt den Namen – im Dateimanager siehst du danach immer die Version.
- Neue Plugins lassen sich auch bei laufendem Server hinzufügen; sie werden beim nächsten Neustart aktiv.
- Neue **Server-Übersicht** (App: Plugins → Server-Übersicht, Web: Plugins): Dateiversion, von Paper geladene Version, vorgemerkte Updates und deren künftiger Dateiname – serverseitig berechnet und gecacht.

## Spielerprofil

- App: Liquid-Glass-Raster mit allen Werkzeugen eines Spielers (Inventar, Snapshots, NBT, Steckbrief, Verlauf, Punkte, Geräte, Rechte, Befehle, Alts, PowerBot-Akte, Strafen, Chat-Ruhe & Töne, Anti-Lag, Client-Prüfung). Die Moderations-Schnellaktionen nutzen ebenfalls Liquid Glass. Nichts wurde entfernt.
- Karten in der ganzen App nutzen jetzt Liquid Glass (abschaltbar unter Einstellungen → Darstellung).
- Web: Werkzeug-Raster im Profil mit allen Links und allen Spieleraktionen.

## Bibliotheken

- Paper-API 26.3 Build 258 (vorher 180), sqlite-jdbc 3.54.1.0 (vorher 3.53.4.0).
- Build-Referenzen passend zu Paper 26.3: Guava 33.6.0, Netty 4.2.16; JetBrains Annotations 26.1.0, ECJ 3.46.100; Maven-Plugins aktualisiert.
- MariaDB Connector/J bleibt bewusst auf 3.5.11 (neueste 3.x). Version 4.0 verlangt standardmäßig verifiziertes TLS (`sslMode=verify-full`) und würde bestehende Datenbankverbindungen unterbrechen.
- Discord-API v10 ist weiterhin aktuell. Formulare (Konsole, Spielerakte, Suite-Befehl, Tickets) nutzen das neue Label-Format; für den Bot unsichtbare Kanäle (seit 16.11.2026 verschleiert) werden ausgeblendet; Rollenfarben lesen das neue `colors`-Feld.
- GitHub Actions: checkout v7, setup-java v6, cache v6, upload-artifact v7, download-artifact v8.

Details zum Plugin: [Companion/AdminSuite/UPDATE-10.23.0.md](Companion/AdminSuite/UPDATE-10.23.0.md)
