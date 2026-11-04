# Admin Suite 10.4.0 – Discord-Werbeplaner

Plug & Play: JAR ersetzen. Bestehende Daten, Config-Werte, Schlüssel, Benutzer, Sitzungen und Bark-Abos bleiben
unverändert. Neu ist nur der Ordner `plugins/SimplexAdminSuite/discord-ads/` (wird beim ersten Start angelegt) und der
optionale Config-Schlüssel `discord-ads.enabled` (Standard `true`, gilt auch ohne Eintrag).

> Automatisches Posten mit einem Nutzer-Account verstößt gegen die Discord-Nutzungsbedingungen; Discord kann den
> Account sperren. Nur einen Zweitaccount verwenden und nur in Kanälen posten, in denen Werbung erlaubt ist.

## Dateien

| Datei | Inhalt |
|---|---|
| `discord-ads/state.json` | Accounts (Token verschlüsselt), Werbetexte, Kanal-Planungen mit absoluten Zeitpunkten, Verlauf, Einstellungen – atomar geschrieben |
| `discord-ads/secret.key` | 256-Bit-Schlüssel für die Token (Rechte 600). **Zusammen mit state.json sichern** – ohne ihn muss der Account neu verknüpft werden |

Eine unlesbare `state.json` wird als `state.json.unreadable-<Zeit>` gesichert und nicht überschrieben.

## API (App & Webpanel)

- `GET /v1/discord-ads` – Übersicht (`data.discord-ads`), Token nie enthalten.
- `GET /v1/discord-ads/guilds?account=` und `/v1/discord-ads/channels?account=&guild=` – Server und Kanäle mit
  berechneten Schreibrechten (Rollen- und Mitglieds-Overwrites), Slowmode, Beschreibung, Hinweis und Intervallvorschlag.
- `POST /v1/discord-ads` (`discord-ads.manage` + manage/full): `link` (verlangt `accept: true`), `relink`, `check`,
  `unlink` (`logout`, `deleteCampaigns`), `template.save`, `template.delete`, `campaign.save` (neuer Kanal verlangt
  `confirm: true`), `campaign.pause|resume|send-now|delete`, `settings.save`. Jede Antwort enthält die frische Übersicht.

## Planer

- Ein Hintergrund-Thread (alle 10 s), höchstens ein Post pro Account und Durchlauf, Mindestabstand zwischen zwei Posts
  desselben Accounts (Standard 60 s + Zufall).
- Nächster Post = Intervall (oder längerer Kanal-Slowmode) + 0,4–2 % (mind. ½–1½ Min, höchstens 1 Std) – nie kürzer.
- Vor dem Post: Suche nach eigenem letzten Post im Kanal; Senden mit `nonce` + `enforce_nonce` (keine Doppelposts bei
  Netzwerkfehlern) und `allowed_mentions: {parse: []}`; optional ohne Link-Vorschau.
- 429/20016 (Slowmode) → Kanal-Slowmode übernehmen, später erneut, kein Fehler. 401 → Account ungültig. Captcha/40002 →
  Account blockiert bis „Erneut prüfen“. 403/50013/50001 → Fehler mit Rückoff. 400/50035 oder 404 → Kanal pausiert.
  5xx/Netzwerk → 1/3/10/30 Min, nach vier Versuchen ein Fehler.
- Nach 3 Minuten: Ist der Post noch da? Wenn nicht → Fehler „gelöscht“, neuer Versuch nach einem Viertel des Intervalls.
- N Fehler in Folge (Standard 3, je Kanal 0–10) → automatische Pause.
- Nach einem Neustart bleiben alle Zeitpunkte; überfällige Posts werden ab 60 s gestaffelt nachgeholt.

## Bark & Browser-Push

- Neuer Ereignistyp `discord-ads` („Discord-Werbung: Fehler & Pausen“). Erreicht nur Konten mit `data.discord-ads`;
  ohne eigene Regel aktiv (wie Tickets). Link in Bark: `panel://suite-discord-ads`.

## Neue Rechte (in keiner Rollenvorlage)

| Recht | Bedeutung |
|---|---|
| `data.discord-ads` | Werbeplaner ansehen: Accounts (ohne Token), Texte, Kanäle, Verlauf |
| `discord-ads.manage` | Kritisch: Accounts verknüpfen/abmelden, Texte und Kanäle anlegen, ändern, pausieren, löschen |

## Tests

`tools/tests/Release1040Harness.java` prüft gegen eine lokale Discord-Attrappe: Timing (nie unter dem Intervall),
Rechteberechnung, Hinweise, verschlüsselte Speicherung, Neustart, eigener Post, Slowmode, Auto-Pause mit Hinweisen,
Erkennung gelöschter Posts, Captcha/Token-Probleme, Abmelden/Neu-Verknüpfen und die API-Rechte.
