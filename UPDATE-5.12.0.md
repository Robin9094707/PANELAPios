# Simplex Panel 5.12.0 und Admin Suite 10.19.0

Umzug zu **UltraServers**. Die App ist jetzt auf das UltraServers-Panel (`https://panel.ultraservers.com`) zugeschnitten, alle Kinetic-Sonderfunktionen sind entfernt. Dazu behebt Admin Suite 10.19.0 vertauschte Ränge (Präfixe/Suffixe) über den Köpfen und in der Tabliste.

Baut auf 5.11.0 / 10.18.0 auf (enthält damit 5.10.0 / 10.17.0 und 5.9.0 / 10.16.1). Nur JAR und IPA ersetzen – alle Konten, Schlüssel, Spielerdaten, Einstellungen und das TLS-Zertifikat bleiben erhalten. SentinelBridge 1.6.0 bleibt unverändert.

## App 5.12.0

### Mit UltraServers verbinden

1. Im UltraServers-Panel: Account → **API Credentials** → Schlüssel erstellen (beginnt mit `ptlc_`).
2. In der App: Panel hinzufügen → **„UltraServers-Panel eintragen“** → Schlüssel einfügen.

UltraServers nutzt die Pterodactyl-Client-API. Konsole, Dateien, Backups, Zeitpläne, Datenbanken, Ports, Mitbenutzer und Startparameter funktionieren wie gewohnt. Zusätzlich gibt es unter **Verwalten → UltraServers**:

| Funktion | Was die App kann |
| --- | --- |
| **Automatische Backups** | Die Backups, die UltraServers alle 6 Stunden anlegt: letztes und nächstes, nach Tagen gruppiert, Warnung wenn eines ausbleibt, **herunterladen** und **wiederherstellen** (optional Server vorher leeren). Auch oben im Backup-Tab. |
| **Player Manager** | Online, Operatoren, Whitelist, Gebannte, gebannte IPs und bekannte Spieler. Per Tipp: OP geben/nehmen, Whitelist, Kick, Bann/Entbannen, IP-Bann, Töten, Inventar leeren. Spieler über **+** hinzufügen, auch wenn sie noch nie da waren. |
| **Subdomains** | Liste, Erstellen mit Auswahl der Domain, Löschen, Adresse kopieren. |
| **Reverse Proxy** | Domain mit SSL/Let's Encrypt auf einen Port (Dynmap, BlueMap, Plan …), Ziel-Port aus deinen Allocations. |
| **Plugin-Installer & Modpacks** | Suchen, Version wählen, installieren. |
| **Datenbank-Import** | MySQL von einem anderen Host übernehmen. |
| **Config-Editor** | `server.properties` mit Schaltern, Auswahlfeldern und Zahlen – Kommentare und unveränderte Zeilen bleiben exakt erhalten. Funktioniert auf jedem Panel. |
| **Web-Panel-Links** | Version wechseln/Game Switch, Server-Splitter, SFTP-Import, MTR und Tickets bietet UltraServers nur im Web-Panel an – die App öffnet direkt die Serverseite. |

Die Spielerzahl auf der Startseite und im Widget kommt bei UltraServers direkt aus dem Player Manager statt aus dem `list`-Befehl.

### Entfernt

Kinetic-Erweiterungen (Firewall, Instanzen, Mod-Sperrliste, Backup-Export, Startbefehl, Modrinth-Download per ID, `minecraft/properties`). Die Port-Verwaltung nutzt wieder die Standard-Pterodactyl-Routen.

### So wurden die Schnittstellen geprüft

UltraServers veröffentlicht keine API-Dokumentation für seine Erweiterungen. Die Routen wurden am echten Panel ermittelt (Laravel antwortet für vorhandene Routen mit 401, für unbekannte mit 404 – ohne Login). Die Antwortformate sind nicht öffentlich, deshalb liest die App sie tolerant (Pterodactyl-Hülle, einfache Listen, `success/data`, camelCase und snake_case). Schreibaktionen ohne Zugang zu deinem Konto konnten nicht live ausprobiert werden. Fehlt dem Panel ein Feld, zeigt die App die genaue Meldung des Panels an.

## Admin Suite 10.19.0

### Ränge über dem Kopf und in der Tabliste vertauscht – behoben

Ursache 1: Jeder Spieler bekommt ein eigenes Scoreboard-Team für Präfix und Suffix. Der Teamname bestand aus den ersten 10 Zeichen der UUID. **Alle Bedrock-Spieler (Floodgate) haben UUIDs, die mit `0000000000` beginnen** – sie landeten also im selben Team, und bei jeder Aktualisierung (alle 2 Sekunden) bekam das gemeinsame Team das Präfix des gerade bearbeiteten Spielers. Ergebnis: Ränge sprangen zwischen den Bedrock-Spielern hin und her. Der Chat war nicht betroffen, weil er die Teams nicht nutzt.

Ursache 2: Nach dem Setzen des Tablisten-Namens liest Paper ihn mit anders codierten Farbcodes (z. B. Hex-Farben) zurück. Die Suite hielt das für ein fremdes Plugin, gab die Tabliste auf, und dort erschien dann die Team-Dekoration – samt der vertauschten Ränge.

Fix:

- Eindeutiger Teamname pro Spieler aus der kompletten UUID (SHA-256, weiterhin max. 16 Zeichen, Präfix `sasafk` bleibt).
- Alte geteilte Teams werden beim ersten Durchlauf automatisch aufgeräumt; Teams anderer Plugins werden nie angefasst.
- Ein Suite-Team enthält immer genau einen Spieler.
- Die Tabliste vergleicht mit dem Wert, den Paper tatsächlich speichert, und erkennt umcodierte Farben als denselben Namen.
- Diagnose (`/v1/data/nameplates`) meldet zusätzlich `sharedTeam`.

### UltraServers-Hinweis beim Koppeln

`/sas app status` erklärt jetzt: Bei UltraServers die Allocation für den App-API-Port mit dem Haken für **HTTP-/Voice-/Votifier-Plugins** anlegen. Ohne den Haken filtert der Gcore-DDoS-Schutz Nicht-Spiel-Verkehr und die App meldet „nicht erreichbar“.

## Update

1. Server stoppen, `plugins/SimplexAdminSuite` sichern (oder ein manuelles Backup im Panel anlegen).
2. `SimplexAdminSuite-10.19.0.jar` statt der alten JAR in `plugins/` legen, Server starten. Keine Konfigurationsänderung nötig.
3. Die unsignierte IPA wie gewohnt mit derselben Signatur und Bundle-ID über die bestehende App installieren.
4. Beim Umzug von Kinetic: in der App das neue UltraServers-Panel hinzufügen, den alten Kinetic-Eintrag kannst du löschen. Die Admin-Suite-Kopplung bleibt, wenn Adresse und Port gleich sind – sonst `/sas app status` und `/sas app setup <Name>`.

Rückweg: alte JAR zurücklegen. 10.19.0 speichert keine neuen Daten. Minecraft merkt sich Scoreboard-Teams aber in der Welt; 10.18.0 hält die neuen Teams für fremd und zeigt dann keine Ränge über dem Kopf. In dem Fall einmal in der Konsole `team list` ausführen und die Teams, die mit `sasafk` beginnen, mit `team remove <Name>` entfernen.
