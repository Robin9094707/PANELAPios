# Simplex Panel 4.7.0 – Discord-Werbeplaner

Benötigt Admin Suite 10.4.0 für den neuen Bereich; mit älteren Suites bleibt er ausgeblendet.
IPA einfach drüber installieren (gleiche Bundle-ID und Signatur) – Verbindungen, Zugänge, Layouts und Widgets bleiben erhalten.

> **Wichtig:** Automatisches Posten mit einem normalen Discord-Account (Self-Bot) verstößt gegen die
> Discord-Nutzungsbedingungen. Discord kann den Account sperren. Verwende einen Zweitaccount und poste nur in Kanälen,
> in denen Werbung ausdrücklich erlaubt ist.

## Wo?

Admin Suite → Kommunikation & Studio → **Discord-Werbung planen** (Webpanel: Studio → Discord-Werbung).

## Account

- **Verknüpfen** per Token des Zweitaccounts (Anleitung in der App). Der Server prüft ihn bei Discord, speichert ihn
  AES-GCM-verschlüsselt und zeigt ihn nie wieder an.
- **Erneut prüfen**, **Token erneuern** (gleicher Account, alle Kanäle bleiben) und **Abmelden** – optional auch bei
  Discord (Token wird ungültig). Abgemeldete Kanäle werden pausiert und laufen wieder, wenn derselbe Account neu verknüpft wird.

## Werbetexte

- Benennen, bearbeiten, kopieren, löschen. Drei Vorlagen: „Simplex SMP – Discord“ (dein Text), „– Kurz“ und „– Deutsch“.
- Formatierungsleiste: fett, kursiv, unterstrichen, durchgestrichen, Spoiler, Code, Überschriften 1–3, kleiner Text (-#),
  Zitat, Liste, Link, Emojis, Platzhalter `{online}` `{max}` `{date}`.
- Vorschau im Discord-Look, Zeichenzähler (2000 ohne Nitro), Link-Vorschau optional unterdrücken. Pings sind immer aus.

## Kanäle planen (Assistent)

1. **Server** – alle Discord-Server des Accounts mit Symbol und Mitgliederzahl.
2. **Kanal** – nach Kategorien, mit echten Schreibrechten, Slowmode, Beschreibung und Hinweis („Werbekanal?“ /
   „Kein Werbekanal?“). Kanalnamen wie `6h`, `15m`, `1h` oder Beschreibungen wie „every 12 hours“ werden als
   Intervall vorgeschlagen. Höchstens 4 Kanäle pro Server (einstellbar 1–10).
3. **Text & Timer** – eigener Werbetext und eigenes Intervall je Kanal (5 Min bis 7 Tage).
4. **Bestätigen** – Warnung: Ist das der richtige Kanal? Ohne erlaubte Werbung droht der Ausschluss vom Server.
   Erst nach Bestätigung wird geplant.

## Timer

- Immer **etwas später als eingestellt, nie früher**: 24 Std → etwa 24,1–24,5 Std, 6 Std → etwa 6,03–6,12 Std,
  kurze Intervalle +½–1½ Min. Ist der Kanal-Slowmode länger, gilt er.
- Timer sind feste Zeitpunkte auf dem Server – **Neustarts setzen nichts zurück**; verpasste Posts werden kurz nach dem
  Start nachgeholt (gestaffelt).
- Vor jedem Post wird nach deinem letzten eigenen Post im Kanal gesucht; ist er zu jung, wird verschoben.
- „Jetzt senden“ geht erst, wenn das Intervall seit dem letzten Post abgelaufen ist.

## Fehler

- **Slowmode** (zu früh): wird erkannt, der Kanal-Slowmode übernommen und automatisch später erneut gesendet.
- **Gelöschte Posts** (z. B. Timer-Bot): nach 3 Minuten geprüft; neuer Versuch nach einem Viertel des Intervalls.
- **Keine Rechte, Captcha, Bestätigung nötig, Token ungültig, Kanal weg**: klar benannt, Bark-Hinweis.
- Nach 3 Fehlern in Folge (einstellbar je Kanal) wird **automatisch pausiert** – ebenfalls mit Bark-Hinweis.

## Bark

- Neues Ereignis **„Discord-Werbung: Fehler & Pausen“** in den Bark-Einstellungen; ohne eigene Regel aktiv.

## Rechte

- *Discord-Werbung ansehen* (`data.discord-ads`) und *Discord-Werbung verwalten* (`discord-ads.manage`, kritisch).
  Beide stecken in keiner Rollenvorlage.
