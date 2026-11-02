# Simplex Panel 4.2.0

Benötigt für die neuen Funktionen Admin Suite 9.9.0 (ältere Suites funktionieren wie bisher). Einfach die IPA über die
bestehende Installation aktualisieren – Verbindungen, Anmeldungen, Lesezeichen und Einstellungen bleiben erhalten.

## Neu

- **Admin-Log: einstellbarer Ungelesen-Zähler** (Filter-Symbol oben rechts): *Nur Team* (Standard), *Ausgewählte*
  Admins, *Alle* oder *Aus*, optional nur bestimmte Kategorien und mit/ohne reine Ansichten. Alles bleibt im Protokoll
  sichtbar; Aktionen normaler Spieler sind als „Spieler“ markiert und zählen standardmäßig nicht. Die Einstellung gilt
  pro Konto und wird mit dem Webpanel geteilt; Bark- und Browser-Push für Admin-Aktionen folgen demselben Filter.
- **Name & Anzeige** (Konto & Sicherheit): eigenen Benutzernamen ändern – Passwort, 2FA, Passkeys, Rechte,
  Benachrichtigungen und alle angemeldeten Geräte bleiben erhalten, die App bleibt angemeldet. Dazu ein
  **öffentlicher Name** für Ticketantworten; ohne Eintrag sehen Spieler „Team“, nie den Login-Namen.
- **Ränge & Rechte** (LuckPerms): Gruppen mit Gewicht, Präfix, Anzeigename, Vererbung, Rechten (auch befristet und mit
  Kontext) und Mitgliedern; Spieler suchen, Gruppen vergeben/entfernen, befördern/zurückstufen. Jede Änderung zeigt
  vorher den exakten LuckPerms-Befehl zur Bestätigung.
- **Lands**: alle Länder mit Besitzer, Mitgliedern, Chunks, Kasse und Level; welche Teammitglieder überall Zugriff
  haben (Bypass) oder Lands-Admin sind – per Schalter vergeben oder entziehen.
- **Ränge & Namen**: Rang über dem Kopf und in der Tabliste getrennt schaltbar (App-Einstellungen → Ränge & Namen);
  ist ein TAB-Plugin installiert, lässt die Suite die Tabliste automatisch in Ruhe.
- **Serverchat** mit Sprechblasen, Herkunft (Minecraft, Discord, App) und Uhrzeit.

## Notfall

Zugang verloren? In der Serverkonsole `sas app recovery` eingeben – alle Befehle stehen auch im
[Recovery-PDF](docs/Simplex-Admin-Recovery.pdf) (Hauptadmin `sas app owner-reset`, andere Admins
`sas app user-reset <Name>`, `sas app owner-transfer <Name>`, `sas app rename <alt> <neu>`, `sas app unlock <Name>`).
