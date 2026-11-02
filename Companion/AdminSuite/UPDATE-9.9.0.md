# Admin Suite 9.9.0 – Admin-Log-Filter, Umbenennen, Recovery, LuckPerms, Lands & Webpanel-App

Gehört zu Simplex Panel 4.2.0. Einfach die JAR ersetzen – API-Schlüssel, App-Benutzer, Sitzungen, TLS-Zertifikat
(Fingerabdruck bleibt gleich), Bark-Abos, Tickets, Admin-Log und alle Plugin-Daten bleiben unverändert gültig.
Neue Dateien entstehen nur bei Bedarf: `admin-log/filters.properties`, `app-api/web-prefs.properties`,
`app-api/web-push.properties`.

## Neu

- **Admin-Log-Filter je Konto**: Modus `staff` (Standard: nur Team – App, Konsole, Discord sowie OP-, Online- und
  LuckPerms-Admins im Spiel), `selected`, `all` oder `none`, optional Kategorien und Ansichten. Das Protokoll bleibt
  vollständig; nur Zähler und Hinweise folgen dem Filter. `GET /v1/data/admin-log` liefert `settings` sowie je Admin
  `staff`/`counted`, `POST /v1/admin-log/settings` speichert.
- **Benutzername ändern** (`POST /v1/account/rename`, Passwort erforderlich; Hauptadmin kann andere umbenennen):
  Passwort, 2FA, Wiederherstellungscodes, Passkeys, Rechte, Sitzungen, Bark-Einstellungen, Ruhezeiten, Push-Geräte,
  Darstellung und Admin-Log-Einträge ziehen mit um. Offene Setup-Links werden verworfen.
- **Öffentlicher Name** für Ticketantworten (`POST /v1/account/public-name`, Standard `app-api.public-staff-name`,
  „Team“). Ticketantworten, Schließen/Übernehmen/Priorität aus App und Webpanel zeigen Spielern und Discord nie mehr
  „Simplex Panel/<Login> (app:…)“; bestehende Tickets werden beim Start einmalig bereinigt.
- **Recovery nur über die Serverkonsole**: `sas app recovery`, `user-reset <Name>`, `owner-transfer <Name>`,
  `unlock <Name>`; zusätzlich `rename <alt> <neu>`, `user-enable|user-disable|user-logout <Name>`,
  `public-name <Name> [Anzeige]`. Recovery-Befehle sind im Spiel, per RCON und über Discord-/App-Konsole gesperrt.
  Übersicht als PDF: `docs/Simplex-Admin-Recovery.pdf` (auch im Release).
- **LuckPerms** (`GET /v1/data/luckperms[?group=|player=]`, Aktion `luckperms.edit`, Recht `action.luckperms.edit`,
  Profil `full`): Lesen über die LuckPerms-API per Reflection; jede Änderung ist genau ein streng validierter
  `lp`-Befehl, den LuckPerms selbst speichert und synchronisiert.
- **Lands** (`GET /v1/data/lands`, Aktion `lands.access`): Länder über die offizielle LandsIntegration-API, effektive
  Rechte `lands.bypass.*`/`lands.admin.*` aller Teammitglieder (online über Bukkit, offline über LuckPerms);
  Vergabe/Entzug über LuckPerms. Ohne Lands/LuckPerms bleiben die Bereiche einfach leer.
- **Ränge**: `nameplates.rank-above-head`, `nameplates.rank-in-player-list`, `nameplates.yield-to-tab-plugin`
  (Standard an: Mit TAB/TabList/AnimatedTab ändert die Suite die Tabliste nicht).
- **Webpanel**: installierbar als App (Manifest mit maskierbaren Icons, Shortcuts, Service Worker mit Offline-Start),
  Installationsanleitung für iPhone/iPad (inkl. Hinweis auf Safari) und Android, **Browser-Push** (VAPID, RFC 8291,
  ohne externe Bibliotheken; nur Google/Mozilla/Apple/Microsoft-Push-Dienste), Push-Geräteverwaltung, App-Badge,
  persönliche Darstellung je Konto auf dem Server (Farbe, Hell/Dunkel, Dichte, Ecken, Schriftgröße, Startseite,
  Tableiste, ausgeblendete Menüpunkte, einklappbare Seitenleiste), Seiten für Ränge & Rechte und Lands, schönerer
  Chat; im Serverchat sendet das Webpanel mit dem öffentlichen Namen statt dem Login.
- Staff-Chat-Nachrichten und Admin-Aktionen können per Bark und Browser-Push gemeldet werden (Ereignisse
  `staff-chat`, `admin-log`); Ruhezeiten gelten für beide Kanäle.

## Hinweise

- Browser-Push und Service Worker benötigen – wie Passkeys – eine Domain mit gültigem HTTPS-Zertifikat
  (`app-api.public-domain`). Auf iPhone/iPad funktioniert Push ab iOS 16.4 nur in der Home-Bildschirm-App.
- Optional: `app-api.push-contact` (mailto: oder https:) als VAPID-Kontakt.

## Tests

`tools/tests/Release990Harness.java`: RFC-8291-Verschlüsselung gegen einen unabhängigen Empfänger, VAPID-Signatur,
Push-Dienst-Allowlist (kein SSRF), LuckPerms-Befehlsvalidierung inkl. Injection-Versuchen, Lands-Zuordnung,
Admin-Log-Filter (Team/Ausgewählt/Alle/Aus, Kategorien, eigene Aktionen), Umbenennen mit erhaltenen Sitzungen,
öffentlicher Name, Konsolen-Recovery (gesperrt im Spiel und über Discord/App), Darstellung je Konto, Service Worker
und Manifest – gegen den echten HTTPS-Listener.
