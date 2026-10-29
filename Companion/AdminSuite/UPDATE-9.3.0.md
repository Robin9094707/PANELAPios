# Simplex Admin Suite 9.3.0 · App-Admin-Zentrale

Für Simplex Panel 3.0.0. Rein additiv: keine Schema-, Profil-, Datenbank-, Rechte- oder Konfigurationsmigration. Bestehende App-Schlüssel, TLS-Zertifikat/Fingerabdruck, Rollen, Banns, Reports und Alt-Daten bleiben unverändert.

## Neu für die App

- `GET /v1/data/admin-dashboard` (Feature `admin-dashboard-v1`): Lagebild in einer Anfrage – TPS/MSPT, RAM, Laufzeit, online/max, Profile, neue Spieler (24 h/7 Tage), aktive Spieler (24 h), offene Reports und Reports in Bearbeitung, aktive Suite-Banns, Stummschaltungen, verwarnte Spieler, aktive Ultrabanns, Wartungs- und Neustartstatus, Online-Spieler (max. 100), letzte 5 Ereignisse, die 3 neuesten offenen Reports. „Team online“ wird nur an `full`-Schlüssel ausgeliefert. Fehlende optionale Module werden als `null` gemeldet.
- Spielerliste (Feature `players-filters-v3`): zusätzliche Filter `filter=flagged` (Bann, Stummschaltung oder Warnung) und `filter=new` (erste Anmeldung innerhalb von 7 Tagen, neueste zuerst). `all`, `online` und `recent` liefern unverändert dieselben Ergebnisse.
- Spielerprofil (Feature `profile-v3`): zusätzlich `activity` (Spielzeit, AFK-Zeit, Sitzungen, Geräte) und `staff` (Rollenname, falls Admin-Zugang). Alle bisherigen Felder bleiben.
- Konsolen-/Startmeldungen zeigen wieder die tatsächliche Version (vorher fest „9.0.5“).

## Installation

1. Server stoppen, bisherige JAR und den kompletten Ordner `plugins/SimplexAdminSuite` sichern.
2. `SimplexAdminSuite-9.3.0.jar` einsetzen, alte JAR entfernen, Datenordner behalten, Server starten.
3. Bestehende App-Kopplungen funktionieren sofort weiter (gleicher Schlüssel, gleicher Fingerabdruck).

Die Geyser-Name-Bridge (1.0.0) bleibt unverändert und kompatibel.

## Geprüft

- Alle Regressionstests (13 Harnesses inkl. neuem `Release930Harness`) gegen die echte Paper-API 26.3.
- Echter Start auf Paper 26.3 (Build 180) mit Java 25: Upgrade von 9.2.0 auf 9.3.0 mit vorhandenem Datenordner; die unter 9.2.0 erzeugten `full`- und `read`-Schlüssel funktionieren nach dem Update unverändert gegen alte und neue Endpunkte über HTTPS.
