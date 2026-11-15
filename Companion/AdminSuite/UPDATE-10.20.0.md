# Admin Suite 10.20.0

Teil von Simplex Panel 5.13.0 – die vollständige Beschreibung steht in [UPDATE-5.13.0.md](../../UPDATE-5.13.0.md).

- **Bann-Zentrale** (`/v1/ban-center`, Webpanel-Seite, `/sas bans`): Fälle aus Hauptbann + Alt-Konten, Aufheben einzeln oder für den ganzen Fall, IP-Fehlbann-Werkzeug, Verlauf (`bans/history.yml`).
- **Hauptbann vs. Alt-Konto** im Profil, in Listen, in `/checkban` und in der Beitrittsmeldung (DE/EN).
- **LiteBans abgelöst**: Snapshot (API, MySQL/MariaDB oder H2-Datei), LiteBans wird beim Start abgeschaltet, Strafbefehle laufen über die Suite.
- **LiteBans-Migration** (`/v1/ban-migration`): Auswahl pro Fall und Konto, ausgeschlossene IPs (82.165.45.159) nie übernommen, fortsetzbar nach Abbruch, alte Einträge im Verlauf.
- Alt-Gruppen: einzelne Konten lassen sich dauerhaft freistellen (`released`).
- Neue Rechte: `data.ban-center`, `ban-center.manage`, `ban-center.migrate`. Feature-Flags `ban-center-v1`, `litebans-migration-v1`.

Daten werden nur additiv erweitert. JAR ersetzen und Server neu starten, dann die Migration in App oder Webpanel durchgehen.
