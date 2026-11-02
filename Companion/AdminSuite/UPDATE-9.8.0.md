# Admin Suite 9.8.0 – Benutzerkonten, 2FA, Passkeys, Geräte & Webpanel

Gehört zu Simplex Panel 4.1.0. Einfach die JAR ersetzen – vorhandene API-Schlüssel, App-Benutzer, Sitzungen,
TLS-Zertifikat (Fingerabdruck bleibt gleich), Bark-Abos, Tickets und alle Plugin-Daten bleiben unverändert gültig.

## Neu

- **Webpanel auf demselben Port**: `https://<Server-IP>:<Port>/` (gleicher Port wie die App-API). Responsiv für
  Handy und PC, hell/dunkel, mit Dashboard (Spieler online, Team online nach Rolle, Admins im Panel, TPS, RAM,
  Tickets, Reports), Spielern & Profilen, Tickets, Staff-Chat, Serverchat, Admin-Log, Ereignissen, Moderation,
  Statistiken, Welten, Plugins, allen Suite-Funktionen, Suite-Menüs, Einstellungen, Benutzern & Rechten,
  Geräten & Sitzungen sowie Konto & Sicherheit. Bereiche ohne Recht werden ausgegraut mit Hinweis angezeigt.
- **Hauptadmin-Konto**: Der bisherige Hauptschlüssel (`full`) kann in der App oder per API zu Benutzername und
  Passwort migrieren. Der alte Schlüssel bleibt gültig, bis du ihn widerrufst (optional direkt beim Wechsel).
  Den Hauptadmin können andere Admins weder löschen noch deaktivieren noch ändern.
- **Zwei-Faktor-Schutz (TOTP)** mit 10 Wiederherstellungscodes (nur gehasht gespeichert, je einmal nutzbar).
  Funktioniert sofort, auch ohne Domain, in App und Browser.
- **Passkeys (WebAuthn)**: ES256, EdDSA und RS256, Benutzerbestätigung (Face ID/Touch ID) Pflicht. Benötigen eine
  eigene Domain mit gültigem HTTPS-Zertifikat (`app-api.public-domain`, im Webpanel oder in der App einstellbar).
- **Geräte & Sitzungen**: jede Anmeldung mit Gerät (App: Modell, iOS-Version, App-Version; Browser: Browser und
  System), IP und letzter Aktivität. Der Hauptadmin sieht alle Geräte (auch API-Schlüssel), kann sie umbenennen,
  abmelden oder **vorübergehend sperren** – gesperrte Geräte erhalten bis zur Freigabe nur „gesperrt“ (HTTP 423).
- **Notfall-Reset** nur in der echten Serverkonsole: `sas app owner-reset` zeigt den Benutzernamen und ein
  Einmal-Passwort, entfernt 2FA, Wiederherstellungscodes und Passkeys des Hauptadmins und meldet seine Geräte ab.
  Beim nächsten Login muss ein neues Passwort gewählt werden. Ohne Hauptadmin legt
  `sas app owner-reset <Benutzername>` einen an. Im Spiel, per RCON, Discord-/App-Konsole ist der Befehl gesperrt.
- Benutzerverwaltung: „2FA zurücksetzen“ und „Alle Geräte abmelden“ je Benutzer; neues Recht `devices.manage`.
- Befehle: `sas app users`, `sas app domain [host]`, `sas app owner-reset [Benutzername]`.

## Sicherheit

- Browser-Sitzungen nutzen ein `__Host-`-Cookie (HttpOnly, Secure, SameSite=Strict); der Token ist für
  JavaScript unsichtbar. Jede API-Anfrage aus dem Browser braucht zusätzlich einen eigenen Header und eine
  passende Herkunft (Origin), ändernde Anfragen ohne Origin werden abgelehnt.
- Strikte Content-Security-Policy ohne Inline-Skripte und ohne externe Quellen, `X-Frame-Options: DENY`.
- Login mit Rate-Limits je IP und Benutzer, TOTP-Codes sind nicht wiederverwendbar, Anmelde-Challenges laufen nach
  5 Minuten ab und erlauben höchstens 5 Versuche. Setup-Links umgehen keinen aktiven Zwei-Faktor-Schutz.
- Hinter dem Proxy-Transport (`app-api.transport: proxy`, nur Loopback) wird die echte Client-IP aus
  `X-Forwarded-For` übernommen.

## HTTPS mit Domain (optional)

Ohne Domain zeigt der Browser beim selbst erzeugten Zertifikat einmalig eine Warnung (Fingerabdruck mit
`/sas app fingerprint` vergleichen). Für Passkeys: DNS auf die Server-IP, Reverse-Proxy mit Let's Encrypt
(z. B. Caddy `admin.example.eu { reverse_proxy 127.0.0.1:39093 }`, dazu `app-api.transport: proxy` und
`app-api.bind: 127.0.0.1`) und die Domain im Webpanel unter „Webpanel & Domain“ eintragen.

## Tests

`tools/tests/Release980Harness.java`: RFC-6238-/RFC-4648-Testvektoren, WebAuthn mit echten P-256- und
Ed25519-Schlüsseln (Registrierung, Anmeldung, falsche Herkunft/Domain/Challenge, Zähler-Replay, fehlende
Benutzerbestätigung), Migration, 2FA-Login inkl. Wiederherstellungscode, Cookie-/Origin-Schutz, Gerätesperre
(423), Rechte normaler Benutzer, Schutz des Hauptadmins und Notfall-Reset ausschließlich über die Konsole –
gegen den echten HTTPS-Listener.
