# Simplex Panel 5.5.0 und Admin Suite 10.12.0

## Moderation

- PowerBot prüft erkannte Flüsterbefehle vor der Zustellung. Java und Bedrock teilen sich denselben Befehlsweg, sobald Geyser den Befehl an Paper weiterleitet. Die Standard-Aliase umfassen `/msg`, `/tell`, `/w`, `/whisper` und typische Plugin-Aliase einschließlich `/r` und `/reply`, sofern ein Plugin sie anbietet. Weitere Aliase können unter `adminspy.private-message-aliases` bzw. `adminspy.reply-aliases` ergänzt werden. Andere Server oder Proxy-Plugins müssen ihre Nachrichten an diesen Paper-Server weiterreichen, damit die Suite sie prüfen kann.
- Bei aktivierter Flüstermoderation gilt eine Sekunde Abstand zwischen Versuchen und maximal eine ausstehende Nachricht je Spieler. In den ersten zwei Minuten des ersten Beitritts bekommt die KI den Hinweis auf einen Erstbeitritt. Kleine Wortwechsel und harmlose Sticheleien sind erlaubt. Die KI prüft Flüstern strenger auf Werbung, Spam und gezielte Beleidigungen. Bei einer ausstehenden oder ausgefallenen KI-Prüfung bleibt die betreffende Nachricht zurückgehalten. Der bestehende Schalter für die Beitrittsprüfung wird weiter aus der bisherigen Konfiguration gelesen.
- **Spielerprofil → Steckbrief → Chatverlauf** zeigt die neuesten Nachrichten mit Zeit, Absender und bei Flüstern mit Empfänger. Die bestehenden Verlaufseinträge werden weiterverwendet. `data.chat` gibt öffentlichen Chat frei. `data.player-whispers` gibt private Nachrichten frei und wird einzeln oder über Vollzugriff vergeben. Der KI-Einstieg im Spielerprofil verwendet die vorhandenen Assistenzrechte. Der Moderationsdienst erhält für eine Flüsterprüfung den Nachrichtentext und die Regel, ohne Empfängernamen oder privaten Verlauf.
- Im **Admin-Log** lassen sich Minecraft-Admins und das Gesamtprotokoll getrennt durchsuchen. Die KI-Datenquelle für Admin-Aktionen verwendet Minecraft-Adminaktionen. Die Zuordnung verwendet das bestehende Admin-Rechtesystem einschließlich LuckPerms. Alte Einträge ohne gespeichertes Admin-Merkmal lassen sich über bekannte Admin-Namen zuordnen.
- In **Reports** stehen Status, interne Notizen und Antworten an die meldende Person getrennt. Online-Spieler erhalten Antworten direkt. Mit `/report replies` oder `/report replies <ID>` kann die meldende Person ihre gespeicherten Antworten im Spiel lesen.
- In der **Bark-Zentrale** stehen aktive Abonnements, Suche, Dringlichkeit, Ton, Wiederholung und Testversand. App und Web verwenden die Bark-Einstellungen desselben Suite-Kontos.

## Bestehende Installation aktualisieren

1. Server stoppen. Die laufende Suite-JAR und den Ordner `plugins/SimplexAdminSuite` sichern.
2. Die alte JAR im Pluginordner durch die neue ersetzen und den Server starten. Datenordner und TLS-Identität beibehalten.
3. Die neue IPA mit derselben Signaturidentität und Bundle-ID über die vorhandene iOS-App installieren. Die IPA aus GitHub Actions ist unsigniert.
4. Die bestehende Verbindung öffnen. Im Steckbrief und im Admin-Log die neuen Rechte und Ansichten prüfen. Für ausgewählte Teamkonten bei Bedarf `data.player-whispers` vergeben.

## Einen weiteren Paper-Server koppeln

1. JAR im Ordner `plugins` installieren und Paper mit einem vollständigen Java-25-JDK starten. Beim Hoster einen eigenen TCP-Port für die Admin Suite zuweisen.
2. In der Serverkonsole `sas app enable PORT` ausführen und mit `sas app status` auf aktives HTTPS warten.
3. Die öffentlich erreichbare HTTPS-Adresse dieses Servers verwenden. Einen separaten Zugang mit `sas app create Mein-iPhone full` und dessen Fingerabdruck mit `sas app fingerprint` abrufen. Für einen Team-Login gibt es alternativ einen einmaligen Link über `sas app setup BENUTZER 30`, wenn das Konto und `app-api.public-address` bereits eingerichtet sind.
4. In **Simplex Panel → richtiger Server → Verwalten → Admin Suite verbinden** die Adresse, den Schlüssel und den SHA-256-Fingerabdruck genau dieses Servers eintragen. Bei einer eigenen Webdomain mit gültigem HTTPS-Zertifikat die Webadresse im Browser öffnen und mit dem Suite-Konto anmelden.

Für den PR erzeugt GitHub Actions einzelne JAR- und IPA-Artefakte direkt nach dem Build. Ein grüner Gesamtstatus schließt anschließend auch die Tests ein.
