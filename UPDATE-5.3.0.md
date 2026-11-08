# Arts Update · Panel 5.3.0 und Admin Suite 10.10.0

## App und Webpanel

- **Live-Inventar** im Profil eines Online-Spielers. Inventar, Hotbar, Ausrüstung, Nebenhand und Endertruhe aktualisieren sich im geöffneten Fenster etwa alle zwei Sekunden. Mit Bearbeitungsrecht können Staff-Mitglieder vorhandene Stapel im Inventar und in der Endertruhe verschieben oder tauschen. Der Server prüft vor dem Anwenden den Inventarstand erneut. Rüstung und Nebenhand sind in der Vorschau lesbar.
- **Texturen** lädt die Suite im Hintergrund aus dem offiziellen Client der neuesten stabilen Minecraft-Java-Version. Ein eigenes Java-ZIP bis 32 MiB kann in der Vorschau importiert werden. Die Vorschau nutzt passende 2D-Texturen und für komplexe Modelle einen Textplatzhalter. Der Minecraft-Client erhält dadurch kein Ressourcenpaket.
- **Konsolenarchiv** unter Diagnose. Aufbewahrung standardmäßig 30 Tage, einstellbar von 1 bis 365 Tagen. Suche nach Text oder Quelle, Stufe und Zeitraum. Einzelne Zeilen oder das gesamte Panel-Archiv lassen sich nach Bestätigung löschen. Aufzeichnung beginnt, sobald die Suite ihren Log4j-Mitschnitt aktiviert. Vorhandene Paper-Logdateien und Diagnoseberichte liegen separat.
- **Event-Modus & Lockdown** unter Server und Diagnose. Event-Namen im Serverstatus anzeigen. Für eine Welt einen zukünftigen Ingame-Tag planen und Bark-Hinweise erhalten. Mit gesondertem Vollzugriffsrecht können registrierte Konsolenbefehle ausgeführt werden. Der Tag folgt der Weltzeit mit 24.000 Ticks pro Tag. Ein Termin wird vor der Ausführung als verbraucht gespeichert, damit ein Absturz keinen Befehl doppelt startet.
- **Lockdown** erlaubt je nach Stufe bekannte Spieler, eine Verbindung pro geteilter IP oder starkem Alt-Signal, oder nur Spieler mit Ausnahme. OPs und `simplexadminsuite.lockdown.bypass` können weiter beitreten. IP-Ausnahmen der Alt-Erkennung werden berücksichtigt. IP-Reservierungen gelten für die laufende Serversitzung.
- **Spielerrechte** zeigen überlappende Altsperren nur dort an, wo sie noch zusätzlich gelten. Eine neu gesetzte Bereichssperre oder Vorlage ersetzt alte Einzelsperren in den betroffenen Bereichen.
- **KI** formatiert Antworten mit einfachen Textauszeichnungen. Geräte-Karten, Antwortoptionen und bestätigte Aktionspläne bleiben direkt bedienbar. Die KI liefert optional das zuletzt beobachtete Gerät mit direktem Link zum Geräteverlauf. Freischalten über `openai.admin-assistant.device-context: true`. Der Benutzer braucht `data.devices`. Der iOS-Kurzbefehl **KI-Assistent öffnen** kann einem gespeicherten Server zugewiesen werden.
- **Öffentliche Chat-KI** ist separat abschaltbar und standardmäßig deaktiviert. `openai.public-chat.enabled: true` erlaubt Fragen mit `!ki` oder `!ai`. `proactive: true` erlaubt seltene Hinweise bei erkennbaren Konflikten oder Hilferufen. Die Antworten erscheinen anhand der Spielersprache auf Deutsch oder Englisch. Nur ein kurzer, maskierter Ausschnitt aus dem öffentlichen Chat geht an die bereits konfigurierte OpenAI-API.
- **Benachrichtigungen** führen Ingame-Tag-Alarme und Hinweise der Chat-KI in den Serverereignissen. Bark-Abonnenten mit passenden Rechten erhalten Störungswellen standardmäßig kritisch. Ereignisregeln und Ruhezeiten können diese Meldungen abstufen oder stummschalten.

## Rechte

| Recht | Zugriff |
| --- | --- |
| `data.inventory` | Inventar und Endertruhe ansehen |
| `inventory.edit` | Zusätzlich mit Leserecht und Manage-Zugriff Stapel verschieben oder tauschen |
| `inventory.pack` | Zusätzlich mit Leserecht Ressourcen-ZIP verwalten |
| `data.diagnostics` | Konsolenarchiv lesen |
| `console.archive.manage` | Zusätzlich mit Diagnoserecht Einträge und Aufbewahrung verwalten |
| `data.server-events` | Event-Modus ansehen und Spieltag-Alarme empfangen |
| `server-events.manage` | Termine, Event-Status und Lockdown ändern |
| `server-events.commands` | Zusätzlich mit Vollzugriff registrierte Befehle für Termine planen |

## Im Spiel

`/sas event list` zeigt den aktuellen Status, Termine und die Befehle für Verwaltung, Löschung und Lockdown.
Beispiel für einen Alarm am Spieltag 1200: `/sas event schedule world 1200 alarm Bau-Event`.
Für einen Start mit registriertem Befehl nutzt ein Hauptadmin `/sas event schedule world 1200 start Bau-Event | minecraft:say Das Bau-Event beginnt!`.

## Aktualisieren

1. Die neue `SimplexAdminSuite-10.10.0.jar` mit dem üblichen Plugin-Update vormerken oder den Server stoppen und die alte JAR ersetzen.
2. Server neu starten. Den vorhandenen Ordner `plugins/SimplexAdminSuite` behalten.
3. Die neue `Simplex-Panel-5.3.0-unsigned.ipa` wie bisher signieren und installieren. Das Webpanel kommt mit der Plugin-JAR.

[GitHub-Release](https://github.com/Robin9094707/PANELAPios/releases/tag/app-v5.3.0) · [GitHub Actions](https://github.com/Robin9094707/PANELAPios/actions)
