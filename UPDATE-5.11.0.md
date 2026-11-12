# Simplex Panel 5.11.0 und Admin Suite 10.18.0

Baut auf 5.10.0 / 10.17.0 auf, enthält also auch 5.9.0 / 10.16.1 (PowerBot-Eskalation, Disconnect-Forensik, Fix gegen Massen-Disconnects) und die Chat-Ruhe. Wer eine ältere Version installiert hat, ersetzt nur JAR und IPA – alle Daten, Konten, Schlüssel und das TLS-Zertifikat bleiben erhalten. SentinelBridge 1.6.0 bleibt unverändert.

## Einfacher koppeln

Bisher musste der SHA-256-Fingerabdruck exakt abgetippt werden, und nach einem Serverumzug oder hinter einer Domain kam nur „Fingerabdruck stimmt nicht überein“. Jetzt:

- **Kein Fingerabdruck mehr nötig.** Adresse eintragen, anmelden, fertig. Die App prüft die Adresse selbst.
- **Eigenes Zertifikat des Plugins:** Die App zeigt den Fingerabdruck einmal an. Mit `/sas app fingerprint` vergleichen (oder kopieren und „Mit Zwischenablage vergleichen“), dann „Vertrauen & verbinden“. Danach verbindet sie sich nur noch mit genau diesem Zertifikat.
- **Neuer Server / neues Zertifikat:** Statt einer Sackgasse zeigt die App das neue Zertifikat zum Bestätigen. Ein geändertes Zertifikat wird nie ohne Bestätigung angenommen.
- **Domain mit gültigem Zertifikat** (Reverse-Proxy, Cloudflare): funktioniert automatisch, auch wenn noch ein alter Fingerabdruck eingetragen war oder der Setup-Link einen enthält.
- **Klare Fehlermeldungen** statt „Panel-Anfrage fehlgeschlagen“:
  - Port antwortet nicht → Hinweis, dass der Port beim Hoster diesem Server zugewiesen sein muss.
  - Proxy meldet 502/504 → Hinweis, dass der Proxy auf `https://<Server-IP>:<API-Port>` zeigen muss.
- Gilt für „Suite verbinden“, „Admin Suite anmelden“, Setup-Links und „Adresse ändern“.

## Admin Suite 10.18.0

- **`/sas app status`** ist jetzt eine Checkliste: läuft die API, welche Adresse und welcher Port in die App gehören, Fingerabdruck, Hinweis auf die Port-Zuweisung beim Hoster und ein **Selbsttest**, ob der Port auf dem Server selbst antwortet.
- **`/sas app address <IP>:<Port>`** speichert die öffentliche Adresse – kein Bearbeiten der `config.yml` mehr.
- **`/sas app setup <Name>`** erzeugt jetzt fast immer einen vollständigen Link: gespeicherte Adresse, sonst die IP des Hosters (Umgebungsvariable `SERVER_IP`, z. B. Pterodactyl) mit API-Port, sonst die Webpanel-Domain.
- Neuer, nicht angemeldeter Endpunkt `GET /v1/ping`, damit die App erkennt, dass wirklich die Admin Suite antwortet (keine Daten, nur `{"service":"simplex-admin-suite"}`).

## Schnellstart

1. In der Serverkonsole: `/sas app status`
2. Steht dort „Port … muss beim Hoster zugewiesen sein“: im Hoster-Panel unter Netzwerk/Ports prüfen, ggf. `/sas app enable <zugewiesener Port>`.
3. `/sas app setup <DeinName>` und den Link auf dem iPhone öffnen – oder in der App Adresse und Passwort eintragen.
4. Zertifikat einmal bestätigen.

## Update

1. Server stoppen, `plugins/SimplexAdminSuite` sichern.
2. `SimplexAdminSuite-10.18.0.jar` statt der alten JAR einsetzen, Server starten.
3. Die unsignierte IPA wie gewohnt mit derselben Signatur und Bundle-ID über die bestehende App installieren.
