# Simplex Panel 4.1.0

Benötigt für die neuen Funktionen Admin Suite 9.8.0 (ältere Suites funktionieren wie bisher).

## Neu

- **Konto & Sicherheit** (Admin-Zentrale bzw. Zugänge & Serverregeln):
  - **Zum Hauptadmin-Konto wechseln**: Mit dem bisherigen Hauptschlüssel Benutzername und Passwort wählen. Die App
    stellt die Verbindung sofort um; alle Daten bleiben. Der alte Schlüssel bleibt gültig, außer du widerrufst ihn.
  - **Zwei-Faktor-Schutz**: QR-Code oder „In Passwörter einrichten“, danach Wiederherstellungscodes sichern.
  - **Passkeys**: hinzufügen und mit Face ID anmelden, sobald eine Domain mit HTTPS eingerichtet ist.
  - **Passwort ändern**, **Adresse oder Domain der Verbindung ändern** (ohne neue Anmeldung, Serveridentität wird
    geprüft), **Webpanel im Browser öffnen**, **Domain festlegen** (Hauptadmin).
- **Geräte & Sitzungen**: alle angemeldeten Geräte mit Modell, iOS-/App-Version bzw. Browser, IP und letzter
  Aktivität; umbenennen, abmelden, vorübergehend sperren und freigeben (Hauptadmin).
- **Anmeldung**: Nach dem Passwort fragt die App bei aktivem 2FA nach dem Code (oder einem
  Wiederherstellungscode); nach einem Notfall-Reset wird zuerst ein neues Passwort festgelegt. Mit Domain zusätzlich
  „Mit Passkey anmelden“.
- **Benutzerverwaltung**: Hauptadmin- und 2FA-Kennzeichen, „2FA zurücksetzen“, „Alle Geräte abmelden“.

## Notfall

Alles verloren? In der Serverkonsole `sas app owner-reset` eingeben: Benutzername und Einmal-Passwort werden
angezeigt, 2FA und Passkeys entfernt; beim nächsten Login ein neues Passwort wählen.
