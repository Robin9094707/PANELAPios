# PowerBot · Simplex Panel 5.4.0 und Admin Suite 10.11.0

## Chat und Moderation

- PowerBot ist standardmäßig aktiv. Er nutzt den vorhandenen OpenAI-API-Schlüssel. `!ki` und `!ai` antworten im öffentlichen Minecraft-Chat und im konfigurierten Discord-Relay. Bei einem Konflikt bekommt jeder Minecraft-Spieler die Antwort in seiner eingestellten Sprache.
- Der öffentliche Kontext umfasst bis zu zwölf kurze, bereinigte Nachrichten aus Minecraft und dem Discord-Relay. Zuletzt beobachtete Item-Drops und Pickups zwischen beteiligten Spielern liefern optionalen Kontext. Ein Pickup belegt weder einen freiwilligen Tausch noch Besitz.
- Klare Eigenwerbung mit externer Serveradresse wird lokal zurückgehalten. Bei anderen möglichen Verstößen prüft PowerBot erst ein Moderationssignal und dann den Kontext. Drei bestätigte, zeitlich getrennte Versuche derselben Kategorie in 20 Minuten können einen zehnminütigen AutoMod-Mute auslösen. Er umfasst bestätigte Alt-Konten und blockiert auch den Versand per Discord-Relay für verknüpfte Konten. Manuelle Strafen sind getrennt.
- Die konfigurierten Minecraft-Flüster- und Antwortbefehle werden unterstützt. Eindeutige Eigenwerbung und aktive Mutes werden sofort geprüft. Die KI-Prüfung läuft nachträglich und kann Warnungen oder einen Mute für weitere Nachrichten auslösen. PowerBot speichert keinen Flüstertext in der Fallzentrale und teilt ihn nicht mit der öffentlichen Chat-KI. Die bereinigte aktuelle Nachricht wird zur Prüfung an den konfigurierten API-Anbieter gesendet.
- Bei wiederholtem API-Ausfall geht der Chat in einen angekündigten Bypass und prüft die Verbindung später erneut. Lokale Regeln und das Serverchat-Protokoll bleiben aktiv.
- AutoClicker-Muster und wiederholt auffällige Kampf-Reichweiten oder Blickwinkel sind überprüfbare Java-Hinweise. Sie verwenden kurze Messfenster, Ping, Tickzustand und die Reichweitenattribute. PowerBot verhängt dafür keine Strafe.

## Fallzentrale in App und Web

PowerBot sammelt ausgeführte KI-Aktionen, tatsächliche Deeskalationen und ausgewählte wichtige Serverereignisse in einem eigenen Verlauf. Das Team kann Fälle übernehmen, abschließen, das Spielerprofil öffnen und laufende PowerBot-Mutes widerrufen. Die Ansicht **Alles** enthält auch einzelne Blockierungen und Warnhinweise. Ein optionales App-Banner fasst nach drei Stunden die wichtigsten neuen Einträge zusammen.

Einstellungen liegen direkt in der Fallzentrale. Kritische Bark-Hinweise bleiben aus, bis der Hauptadmin sie freischaltet und der Empfänger die Regel **PowerBot** auf kritisch stellt. Ruhezeiten gelten weiter. `/sas powerbot` öffnet die Fallübersicht in Minecraft oder der Konsole.

| Recht | Zugriff |
| --- | --- |
| `data.powerbot` | Fallzentrale und wichtige Einträge lesen |
| `powerbot.manage` | Mit Manage-Zugriff Fälle übernehmen, abschließen und laufende AutoMod-Mutes widerrufen |
| `powerbot.settings` | Mit Vollzugriff KI-Funktionen und Richtlinien konfigurieren |
| `simplexadminsuite.powerbot.manage` | Fallzentrale und Fallaktionen im Spiel |
| `simplexadminsuite.powerbot.bypass` | Chatprüfung für diesen Spieler umgehen |

## Inventare

Inventar-Snapshots verwenden die vorhandenen Java-Ressourcentexturen. Die Offline-Ansicht liest eine eindeutig identifizierte Vanilla-Spielerdatei. Mit Vollzugriff, Inventar-Bearbeitungsrecht und NBT-Schreibrecht können vorhandene Stapel verschoben oder getauscht werden. Der Server überprüft Dateiversion und Online-Status und legt vor der atomaren Änderung eine Sicherung an. Ausrüstung und Nebenhand sind in der Ansicht lesbar.

## Installation

Die `SimplexAdminSuite-10.11.0.jar` über die Plugin-Verwaltung vormerken oder bei gestopptem Server austauschen. Den Ordner `plugins/SimplexAdminSuite` behalten, dann den Server starten. Das Webpanel liegt in der JAR. Die `Simplex-Panel-5.4.0-unsigned.ipa` wie bisher signieren und installieren.
