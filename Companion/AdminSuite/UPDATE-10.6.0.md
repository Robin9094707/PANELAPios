# Admin Suite 10.6.0

Companion für Simplex Panel 4.9.0. Das Webpanel ist Bestandteil dieser JAR.

## Verläufe in App und Web

`history-management-v1` ergänzt die authentifizierten Endpunkte:

- `GET /v1/histories`: verfügbare Verläufe und leseberechtigte Sicherungen.
- `GET /v1/histories/entries?kind=…&q=…&offset=…`: Suche im gesamten gewählten Verlauf, je 100 Einträge pro Seite.
- `POST /v1/histories/prepare`: `kind`, `ids` oder ausdrücklich `all: true`; erzeugt eine an den Benutzer gebundene Bestätigung mit den exakt ausgewählten IDs, gültig für zwei Minuten.
- `POST /v1/histories/apply`: `token`; legt zuerst eine atomare Sicherung an, entfernt dann ausschließlich diese IDs und protokolliert die Bereinigung.
- `POST /v1/histories/restore`: `backup`; ergänzt fehlende Einträge ohne Überschreiben, auch nach einem Neustart.

Verläufe: `admin-log`, `events`, `advertising`, `staff-chat`, `chat`. Anzeigen benötigt das jeweilige vorhandene Leserecht. Löschen und Wiederherstellen benötigt zusätzlich `full` und **history.manage**; das neue Recht steckt in keiner Rollenvorlage. Hauptadmin/Vollzugriff besitzen es bereits. Nicht verfügbar geladene Module erscheinen nicht in der Liste.

Sicherungen liegen geschützt unter `history-backups/<UUID>.json`, enthalten ausschließlich die entfernten Verlaufszeilen und werden nicht automatisch gelöscht. Maximal 100 Sicherungen; danach wird jede weitere Löschung vorab abgelehnt. Alte Sicherungen bei Bedarf vom Serverordner in das eigene Backup übernehmen. Staff-Anhänge bleiben lokal für die Wiederherstellung erhalten. Discord-Nachrichten, Strafen, Reports, Werbe-Orte, Timer, Texte, Konten und Einstellungen werden nicht verändert. Das Leeren des Werbe-Verlaufs leert keine statistischen Zähler und keine Timer-Historie der Werbe-Orte.

Admin-Log-IDs bleiben nach vollständigem Leeren und Neustart monoton; Warteschlangeneinträge können gelöschte Zeilen nicht erneut anhängen. Öffentlicher Chat und Staff-Chat liefern nach Änderungen eine neue Epoche, damit geöffnete Clients ihre Ansicht abgleichen.

## Installation und Rollback

Server stoppen und bisherige JAR plus vollständigen Suite-Datenordner sichern. Alte Suite-JAR ersetzen, anschließend starten. Kein Reset, keine neue Kopplung und kein neues Konto erforderlich. Die bestehenden Datenformate bleiben kompatibel; neue Metadaten sind additiv. Für ein vollständiges Rollback geordnet stoppen und die zuvor gesicherte JAR samt Datenordner wiederherstellen. App- und Kurzbefehle-Anleitung: [UPDATE-4.9.0.md](../../UPDATE-4.9.0.md).

PRs bauen ausschließlich Artefakte; der bestehende Release-Job läuft weiterhin erst bei einem Merge nach `main`.
