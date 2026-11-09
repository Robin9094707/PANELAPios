# Simplex Panel 5.7.0 und Admin Suite 10.14.0

Baut auf 5.6.0 / 10.13.0 auf. Wer 5.6.0 / 10.13.0 installiert hat, ersetzt nur JAR und IPA – alle Daten bleiben erhalten.

## Behoben

- **KI-Antworten zeigten „[IP entfernt]“ statt Uhrzeiten.** Der IPv6-Filter hielt Zeiten wie `09:36:12` für Adressen. Echte IPv4- und IPv6-Adressen werden weiter entfernt, Uhrzeiten bleiben sichtbar.

## Inventare & Einbehaltungsablage

- Live-Inventar und Endertruhe im echten Minecraft-Look (Rüstung, Nebenhand, Hotbar) in App und Webpanel.
- Item antippen → **Tauschen**, **An Spieler geben** (z. B. Diebesgut von Spieler A an Spieler B) oder **In Ablage**. Menge und Grund sind wählbar. Passt nicht alles ins Ziel, bleibt der Rest beim Ursprungsspieler. Beide Spielerverläufe und das Admin-Log zeigen die Aktion.
- **Einbehaltungsablage**: Items werden exakt (mit Verzauberungen, Namen, Inhalt) in `plugins/SimplexAdminSuite/item-locker.json` gesichert – mit Herkunft, Grund, Teammitglied und Zeit. Zurückgeben, an andere Spieler geben, Notiz oder Löschen in App und Web. Im Spiel: `/sas ablage` öffnet die Ablage als Truhe (Klick nimmt ein Item), `/sas ablage einlagern [Grund]` sichert das Item in der Hand. Recht: `simplexadminsuite.locker` (Standard: OP).
- Rechte: Ansehen `data.inventory`, Verschieben und Ablage `inventory.edit` mit manage-Zugriff. Verschieben funktioniert für Spieler, die gerade online sind.

## Smart-Neustart

- Zum Beispiel höchstens alle 6 Stunden, aber nur wenn höchstens 1 Spieler online ist und das mindestens 2 Minuten so bleibt. Countdown wie gewohnt auf Deutsch und Englisch.
- Kommen während des Countdowns Spieler dazu, wird abgebrochen und später erneut versucht. Optional „spätestens erzwingen nach X Stunden“.
- Schutz: Nach einem Serverstart mindestens 30 Minuten Ruhe, der Zeitpunkt wird vor dem Neustart gespeichert – zwei automatische Neustarts direkt hintereinander sind ausgeschlossen. Einstellungen in `smart-restart.properties`, Bedienung über App (Serverneustart / Event-Modus) und Webpanel.

## Automationen

- Wenn … dann …: Auslöser Ingame-Tag (einmalig oder alle X Tage), täglich um eine Uhrzeit, alle X Minuten, ab/bis X Spieler online, Erstbeitritt (`{player}`).
- Aktionen: Chatnachricht (DE/EN), Team benachrichtigen, Event-Status, Lockdown-Stufe, geprüfter Konsolenbefehl, Neustart mit Countdown. Befehle und Neustarts benötigen das Befehlsrecht des Event-Planers.
- Jede Ausführung wird vor den Aktionen gespeichert (keine Doppelausführung nach Absturz). Vorlagen in App und Web. Gespeichert in `automations.json`; die bisherigen Spieltag-Termine im Event-Modus bleiben unverändert.

## KI-Chat

- App und Web: neue Sprechblasen mit KI-Avatar, Tipp-Animation, Antworten erscheinen Wort für Wort (im Leistungsmodus und bei reduzierter Bewegung sofort).
- Chatverwaltung: Titel und Nachrichten durchsuchen, anpinnen, umbenennen, Verlauf leeren, Auto-Löschen und Löschen.

## Webpanel

- Neue Seiten: Einbehaltungsablage, Automationen, Smart-Neustart. Inventar im Minecraft-Look mit Aktionsleiste.
- Sanftere Übergänge (nur im vollen Darstellungsmodus) und schnellere lange Listen.

## Update

1. Server stoppen, `plugins/SimplexAdminSuite` sichern.
2. `SimplexAdminSuite-10.14.0.jar` statt der alten JAR einsetzen, Server starten.
3. Die unsignierte IPA wie gewohnt mit derselben Signatur und Bundle-ID über die bestehende App installieren.
