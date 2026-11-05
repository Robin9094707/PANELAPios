# Simplex Panel 4.8.0 – Werbe-Zentrale

Benötigt Admin Suite 10.5.0 für den neuen Bereich; mit älteren Suites bleibt er ausgeblendet.
IPA einfach drüber installieren (gleiche Bundle-ID und Signatur) – Verbindungen, Zugänge, Layouts und Widgets bleiben erhalten.

## Was ist anders?

Der Discord-Werbeplaner mit Token-Login (Self-Bot) ist **komplett entfernt** – automatisches Posten mit einem normalen
Discord-Account verstößt gegen die Discord-Regeln. Stattdessen postest du selbst, die App nimmt dir aber fast alles ab:

1. Bark meldet: **„📣 Werbung fällig: #werbung-6h · MCAdverts“**.
2. Du tippst auf die Mitteilung → die Simplex-App öffnet sich kurz, **kopiert den Werbetext in die Zwischenablage** und
   **öffnet den Kanal direkt in der Discord-App** (bzw. Reddit, Facebook, die Planet-Minecraft-Seite …).
3. Lange ins Textfeld tippen → **Einfügen** → senden. Fertig.
4. Der Timer startet neu – immer etwas über dem Intervall (6 Std → etwa 6,1 Std).

Bisherige Werbetexte und Discord-Kanäle wurden übernommen (mit laufendem Timer). Der gespeicherte Discord-Token wurde
auf dem Server gelöscht. **Tipp:** Passwort des Discord-Zweitaccounts ändern – dann ist der alte Token auch bei Discord ungültig.

## Wo?

Admin Suite → Kommunikation & Studio → **Werbe-Zentrale** (Webpanel: Studio → Werbe-Zentrale).

## Werbe-Orte

- **Link einfügen** – Plattform wird erkannt: Discord-Kanal-Link (`discord.com/channels/…`, in Discord: Kanal lange drücken →
  „Link kopieren“), Reddit, Planet Minecraft, Facebook-Gruppe, Telegram, WhatsApp, X, Serverlisten oder beliebige Seiten.
- **Werbetext** wählen (passende Formate stehen oben, mit ✓) und **wie oft du dort posten darfst** (15 Min bis 30 Tage oder eigenes Intervall).
- **Zuletzt gepostet** angeben, wenn du dort gerade schon geworben hast – dann läuft der Timer ab da.
- Optional: alle mit Zugriff erinnern, Notiz (z. B. Kanalregeln).
- Liste mit **„Jetzt fällig“** oben; Wischen = Bearbeiten / 1 Std später; lange drücken = Kopieren & öffnen, Später erinnern,
  Rückgängig, Nur öffnen, Pausieren, Löschen.

## Bark

- Neues Ereignis **„Werbe-Zentrale: Werbung fällig“** (zeitkritisch), ohne eigene Regel aktiv.
- Nicht geworben? Erneute Erinnerung (Standard nach 1 Std, höchstens 3×; einstellbar).
- **Vor dem Öffnen fragen** (Einstellung): Statt sofort zu kopieren erscheint eine Vorschau mit „Kopieren & öffnen“ und
  **Später erinnern** (15 Min bis 1 Tag).
- Der Text hängt zusätzlich an der Bark-Mitteilung (lange drücken → kopieren), solange er kurz genug ist.
- Ist der Server gerade nicht erreichbar, kopiert die App den zuletzt geladenen Text und öffnet trotzdem – der Timer
  startet dann erst, wenn du in der Werbe-Zentrale „Werben“ tippst.

## Texte & Formate

- **Discord-Markdown**, **Reddit-Markdown**, **BBCode** (Planet Minecraft, Foren), **WhatsApp/Telegram**,
  **Facebook** (𝗨𝗻𝗶𝗰𝗼𝗱𝗲-Fett) und **reiner Text** – jeweils mit eigener Formatierungsleiste und Vorschau im Look der Plattform.
- **Umwandeln** per Tipp (z. B. Discord-Text → BBCode für Planet Minecraft).
- Fertige Simplex-Texte für Discord (3), Reddit, Planet Minecraft und Facebook sind dabei.
- Platzhalter `{online}` `{max}` `{date}` werden beim Kopieren gefüllt.

## Rechte

Unverändert `data.discord-ads` (ansehen, mit manage-/full-Zugriff auch werben & snoozen) und `discord-ads.manage`
(Orte, Texte, Einstellungen) – jetzt „Werbe-Zentrale ansehen/verwalten“, nicht mehr kritisch, in keiner Rollenvorlage.
