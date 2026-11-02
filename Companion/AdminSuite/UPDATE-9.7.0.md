# Admin Suite 9.7.0

Drop-in-Update für 9.6.0: JAR ersetzen und Server neu starten. Alle Dateien (`app-api/keys.properties`, `app-api/users.properties`, `server.p12`, `app-events/*`, Tickets, Verifizierungen, Spielerdaten, `config.yml`) bleiben gültig.

## Neue Dateien
- `admin-log/activity.jsonl` (letzte 5000 Einträge im Speicher, Rotation ab 6 MiB), `admin-log/read.properties` (Lesestände je App-Zugang)
- `staff-chat/messages.properties`, `staff-chat/files/` (max. 8 MiB je Datei, letzte 400 Nachrichten), `staff-chat/webhook.properties` (privat)
- `app-events/running.marker`, `app-events/emergency-restart.marker` (Notfallneustart-/Absturz-Erkennung)

## Neue optionale Konfiguration
```yaml
staff-chat:
  channel-id: ''        # privater Discord-Staffkanal
  use-webhook: true     # Absendername per Webhook
emergency-restart:
  notify-crash: true
nameplates:
  color-names: true
app-api:
  owner-name: ''        # Name des Hauptzugangs (z. B. Robin)
  public-address: ''    # host:port für vollständige Setup-Links aus der Konsole
```

## API (additiv, Feature-Flags in `/v1/health`)
`admin-log-v1`, `staff-chat-v1`, `setup-links-v1`, `quiet-hours-v1`, `nameplates-v1`, `app-settings-v1`, `staff-tasks-v1`
- `GET /v1/data/admin-log` (Übersicht) bzw. `?admin=<key>&q=&category=&offset=`; `POST /v1/admin-log/read {"admin":""}`
- `GET /v1/data/staff-chat?after=`, `POST /v1/staff-chat {"text","pings"}`, `POST /v1/staff-chat/upload?name=&text=&pings=`, `GET /v1/staff-chat-file?id=`
- `POST /v1/setup {"code"}` (ohne Anmeldung, einmalig, ratenbegrenzt) → Sitzungstoken
- `GET /v1/data/nameplates`, `/v1/data/app-settings`, `/v1/data/staff-tasks`, `/v1/data/setup-info`
- Aktionen: `quiet.update`, `setup.create`, `app.settings`, `staffchat.update`, `tasks.update`
- Neue Rechte für App-Benutzer: `data.admin-log`, `data.staff-chat`, `staff-chat.send`, `data.staff-tasks`, `data.app-settings`, `data.nameplates`, `action.<aktion>`

## Sicherheit
Setup-Codes werden nur als SHA-256 gespeichert, sind einmalig, laufen ab (5 Minuten bis 7 Tage, Standard 60) und ersetzen ältere Codes desselben Benutzers. Löschen eines Benutzers entfernt auch offene Codes. App-Einstellungen sind auf eine feste Liste mit Typ- und Bereichsprüfung beschränkt; `app-api.*`-Zugangsdaten und geschützte Schlüssel bleiben gesperrt. Staff-Chat-Pings erlauben nur bekannte Discord-IDs, `@everyone`/`@here` werden entschärft.

## Entfernt
Visuelle Nicknames (`/nickname`, Discord `/sasadmin player nickname`, Menüeinträge). `nicknames.yml` bleibt erhalten; alle Oberflächen zeigen den echten Namen mit LuckPerms-Rang.
