# Simplex Panel 1.5.0 + Simplex Admin Suite 8.3.0

Die App-Anbindung ist optional und standardmäßig aus. Sie verwendet die vorhandenen Suite-Dienste und dieselben Profile, Strafen, Alt-Verknüpfungen, Discord-Verbindungen, OpenAI-Konfiguration und Inventar-Snapshots. Kein Datenordner wird umbenannt und keine vorhandene Integration wird neu eingerichtet.

## Installation und Kopplung

1. Server geordnet stoppen. Die vorhandene 8.2.0-JAR und den vollständigen Ordner `plugins/SimplexAdminSuite` sichern. Externe Datenbanken/Pluginabhängigkeiten gehören bei Bedarf ebenfalls zum Serverbackup.
2. Alte Suite-JAR im Pluginordner durch **genau eine** `SimplexAdminSuite-8.3.0.jar` ersetzen. Den vorhandenen Datenordner unverändert behalten. Server starten. Für den Austausch der JAR ist dieser erste Neustart erforderlich; kein PlugMan-/Bukkit-Reload verwenden.
3. Beim Hoster einen zusätzlichen **TCP-Port** für diesen Server reservieren. Es ist nicht der Minecraft- oder SFTP-Port. Einen tatsächlich zugewiesenen Port verwenden; nur eine Zahl in einer Config öffnet keine Provider-Firewall.
4. In der Serverkonsole ausführen, jeweils ohne `/`:
   ```text
   sas app enable DEIN_ZUGEWIESENER_PORT
   sas app status
   sas app create Mein-iPhone full
   sas app fingerprint
   ```
   `enable` bereitet das eigene TLS-Zertifikat im Hintergrund vor. Erst fortfahren, wenn `status` HTTPS aktiv meldet. Die Java-Laufzeit muss `keytool` mitbringen; ein vollständiges Java 25 JDK erfüllt das. Private Schlüssel, Zertifikat und Passwort liegen ausschließlich im geschützten neuen Unterordner `app-api`.
5. **Simplex Panel** mit demselben Signaturdienst, derselben Signaturidentität und derselben Bundle-ID über die bestehende App installieren; die alte App vorher nicht löschen. Die App-Bundle-ID bleibt `eu.simplexsmp.panel`.
6. In der App den richtigen Panel-Account und Server öffnen → **Verwalten → Admin Suite → Admin Suite verbinden**. Eintragen:
   - HTTPS-Adresse: `https://SERVER-IP:ZUGEWIESENER_PORT` (bei IPv6: `https://[IPv6]:PORT`)
   - den einmalig angezeigten `sas_...`-Schlüssel
   - den vollständigen SHA-256-Fingerabdruck aus der Konsole.
   Auf **Prüfen & sicher speichern** tippen. Ein Zertifikatsfingerabdruck ist öffentlich; der API-Schlüssel ist geheim. Schlüssel nur über vertrauenswürdige Geräte und die eigene Panelkonsole übernehmen.

Alternativ gibt es im bestehenden Java-Inventar und Bedrock-Adminformular **Simplex Panel verbinden**, beziehungsweise `/sas app`. Zugangsschlüssel dürfen nur ein echter Server-Operator, der gebundene Hauptadmin oder die Serverkonsole erzeugen/widerrufen. Ein App-Schlüssel darf keine weiteren Schlüssel erzeugen.

Die App speichert den separaten Suite-Schlüssel im bisherigen iOS-Schlüsselbund; die Kopplung wird nach Panel-URL und Server-ID getrennt. Mehrere Server können unterschiedliche Adressen, Schlüssel und Zertifikate verwenden. Keine Admin-/Application-API des Hosters wird benötigt.

## Rechte, Bestätigungen und Abschalten

- `read`: Daten ansehen. Keine Änderungen und kein Zugriff auf ausführbare Originalmenüs.
- `manage`: Spieler/Moderation, unterstützte Live-Werte, Snapshots und normale Serveraktionen verwalten. Kritische Konfiguration/erweiterte Befehle bleiben gesperrt.
- `full`: zusätzlicher Zugriff auf Suite-Konfiguration, erweiterte Suite-Befehle und die vorhandenen Suite-Menüs. Nur für vertrauenswürdige Hauptadmins verwenden.

Jede Änderung wird zunächst als Vorschau vorbereitet. Der Server bindet die Bestätigung an genau diesen Schlüssel und diese Anfrage; sie ist 60 Sekunden und einmalig gültig. Bei Verbindungsabbruch/Zeitüberschreitung zuerst den Zustand prüfen, nicht blind wiederholen. Menüaktionen prüfen zusätzlich Sitzungs-ID und Menüstand; alte Optionen werden abgelehnt. Vorhandene Bestätigungen der Suite gelten weiterhin. Jede Menüauswahl wird zusätzlich bestätigt, da auch ein Menüknopf Änderungen auslösen kann.

```text
sas app keys
sas app revoke SCHLUESSEL_ID
sas app disable
sas app reload
```

`revoke` entwertet den Schlüssel und seine Bestätigungen, `disable` schaltet den gesamten zusätzlichen Listener aus. Bestehendes Discord, OpenAI, Banns und alle anderen Module laufen weiter. `app reload` lädt nur die API-Einstellungen neu und benötigt keinen Serverneustart. API-Einstellungen können in `config.yml` unter `app-api` bearbeitet werden.

## Was die App anbietet

Native deutsche Ansichten für Spielerprofile und Live-Werte, Java-/Bedrock-Geräte und Diagnose, Alt-Account-Scores mit Belegen, Ultrabann-Gruppen mit Mitgliedern und gruppenweises Freigeben über Gruppen-ID **oder Spielernamen**, Inventar/Endertruhe, Snapshot-Inhalte und die bestehenden Snapshot-Aktionen. Live-Gesundheit, Hunger, Sättigung, Level, XP-Anteil, Modus, Flugerlaubnis und Unverwundbarkeit werden auf dem Serverthread geändert.

Der vorhandene Discord-Administrationskatalog wird vollständig als durchsuchbare native Formulare mit Auswahlfeldern und Vorschlägen angeboten. **Alle Suite-Menüs** ergänzt die vorhandenen Java-Menüaktionen und Texteingaben über denselben Dienst. Langes Drücken auf einen ursprünglichen Inventarmenüpunkt bietet Rechtsklick und Umschalt-Klicks. Die Verbindung benötigt dabei keinen gleichzeitig eingeloggten Administrator. Manche Werkzeuge benötigen eine eigene Spielfigur (z. B. sich selbst teleportieren, Vanish oder einen Warp an der eigenen Position erstellen); diese bleiben im Spiel. Nicht jeder rein clientseitige Menüeffekt lässt sich fernbedienen. Zugangsdaten/Passwörter und neue App-Schlüssel bleiben der lokalen Verwaltung vorbehalten.

Bei aktiver Option **Moderation über Admin Suite** öffnet der bisherige Spieler-Manager die Suite-Spielerakte und verwendet deren Strafverwaltung. Ein Verbindungsfehler führt nicht automatisch zu einer anderen Bannverwaltung. Die Option kann pro Server ausgeschaltet werden.

Gespeicherte NBT-Dateien werden in geladenen Welten gefunden und im bestehenden vollständigen NBT-Editor geöffnet. Er liest/schreibt über die Panel-Dateirechte, prüft Konflikte, erstellt eine Sicherung und verlangt zum Anwenden einen gestoppten Server. Beliebige Live-NBT-Dateien werden nicht überschrieben. Aktuelle Inventare sind einsehbar; vollständige Item-Metadaten bleiben in den vorhandenen Snapshots/NBT-Dateien erhalten. Eine Snapshot-Wiederherstellung nutzt die bestehenden Suite-Regeln und erfordert einen online verfügbaren Zielspieler.

Die App prüft den übertragenen Fingerabdruck exakt, oder bei leerem Fingerabdruck die normale öffentliche HTTPS-Zertifikatskette. Es gibt keine HTTP-Abschwächung und keine Weiterleitung von Schlüsseln an Redirect-Ziele. Browserzugriffe mit Origin werden abgewiesen. Die API hat Größen-, Tiefen-, Thread- und Ratenlimits; Schlüssel werden auf dem Server nur als SHA-256-Hashes gespeichert. Ein Schlüssel ist eine eigene delegierte Adminberechtigung, unabhängig von Pterodactyl-Mitbenutzerrechten.

## Alternative: eigener HTTPS-Proxy

Wer bereits einen eigenen HTTPS-Reverse-Proxy betreibt, kann folgendes Backend wählen:

```yaml
app-api:
  enabled: true
  bind: 127.0.0.1
  port: 39093
  transport: proxy
```

Der Proxy muss die originale `/v1/...`-Route und den Authorization-Header an diesen lokalen Listener weiterreichen und öffentlich ein gültiges HTTPS-Zertifikat anbieten. Der unverschlüsselte Backend-Modus akzeptiert ausschließlich Loopback-Bindungen. Nicht öffentlich als HTTP freigeben. In der App die öffentliche HTTPS-Adresse eingeben; bei gültiger öffentlicher Zertifikatskette ist kein manueller Pin nötig. Eine vorhandene Pterodactyl-Reverse-Proxy-Erweiterung lässt sich verwenden, wenn sie dies tatsächlich unterstützt; die App richtet keine unbestätigte Providerroute automatisch ein.

## Rückbau

1. `sas app disable` ausführen. Die Kopplung in der App entfernen; einzelne Schlüssel gegebenenfalls vorher widerrufen.
2. Soll auch die Pluginversion zurück: Server stoppen, 8.3.0-JAR entfernen, die gesicherte 8.2.0-JAR zurücklegen, denselben Plugin-Datenordner behalten und starten. Die API ergänzt nur einen eigenen Unterordner und Config-Einträge; sie verlangt keine neue Profilschema-Version. Für einen exakten Rücksprung auf den Zustand vor dem Update das komplette vorher gesicherte Server-/Pluginbackup wiederherstellen; dadurch gehen spätere echte Verwaltungsänderungen zurück.
3. Die vorige App ist im GitHub-Branch `backup/v1.4.0-before-adminsuite` gesichert. Eine ältere IPA muss beim Rückwechsel ebenfalls mit derselben Identität signiert werden. Die deaktivierte neue App kann problemlos weiter für das Panel benutzt werden.

## Selbst bauen und Prüfgrenzen

Im Ordner `Companion/AdminSuite`:

```sh
python3 tools/build-real.py
python3 tools/test-830.py
```

Java 25 für Laufzeitprüfungen verwenden. Der Build lädt ausschließlich die in `tools/build-dependencies.json` mit SHA-256 fixierten echten Paper-/Bibliotheks-JARs; keine Bukkit-Stubs werden ausgeliefert. Bestehende 8.2-Vanishing-/Inbox-Regressionsprüfungen bleiben im gezielten Testlauf. Die zusätzlichen Prüfungen verwenden echte HTTPS-Verbindungen und echte Paper-Interfaces mit synthetischen Bukkit-Diensten. Das ersetzt keinen Test auf dem laufenden Hostingserver, einer echten Bedrock-Sitzung oder dem verwendeten iOS-Signaturdienst.

Primärquellen: [Paper-Scheduler](https://docs.papermc.io/paper/dev/scheduler/), [Java HTTPS-Server](https://docs.oracle.com/en/java/javase/17/docs/api/jdk.httpserver/com/sun/net/httpserver/HttpsServer.html), [Apple Server Trust](https://developer.apple.com/documentation/foundation/urlsessiondelegate/urlsession(_:didreceive:completionhandler:)).
