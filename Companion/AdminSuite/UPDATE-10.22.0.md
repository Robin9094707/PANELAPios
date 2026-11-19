# Simplex Admin Suite 10.22.0

## Unterstützte Daten

- Paper-Dateien aus den erkannten Spielstandspfaden einschließlich `players/data` und `playerdata`. Beide Accounts müssen dieselben primären Spielstandspfade haben. Der Transfer bindet die UUID und ausgesparte Bukkit-/Paper-Identitätsfelder an den jeweiligen Account.
- Simplex-Points-Salden lassen sich innerhalb derselben SQLite-Transaktion tauschen. Das Punktebuch dokumentiert beide Buchungen.
- LuckPerms verwendet die normalen Nutzereinträge. Dazu zählen Gruppen, Gültigkeit, Kontexte sowie erteilte und verweigerte Plugin-Rechte. OP, Whitelist, externe Zugänge und Kontoverknüpfungen sind an die bisherigen Konten gebunden.
- Essentials-Homes aus `plugins/Essentials/userdata/<UUID>.yml`, wenn Essentials gestoppt ist.
- SimplexHomes ist für ein durch den Betreiber überprüftes Format einstellbar. `account-transfer.simplex-homes-directory` muss auf einen Ordner unter `plugins/SimplexHomes` zeigen, dessen Dateien `<UUID>.yml` heißen und dessen `homes`-Abschnitt die eigentlichen Orte enthält. Beim Tausch muss SimplexHomes gestoppt sein.
- Multiverse-Inventories-Welten- und Gruppenprofile aus `plugins/Multiverse-Inventories/{worlds,groups}/<Gruppe oder Welt>/<Name>.json` können bei gestopptem Multiverse-Inventories mitgetauscht werden. Globale Multiverse-Identitätsprofile sind davon ausgenommen. Ein aktives Multiverse-Profil kann beim nächsten Login den Paper-Spielstand für geteilte Werte ersetzen.

## Sicherungen und Teilfehler

Die Originaldateien, die erwarteten Dateiprüfsummen und gegebenenfalls LuckPerms- und Punktestände stehen unter `plugins/SimplexAdminSuite/account-transfers/<Sicherungs-ID>/`. Beide Accounts bleiben während des Schreibens für Anmeldungen gesperrt. Ein unterbrochener Dateitausch wird beim Serverstart anhand des Journals zurückgesetzt. Wenn eine fremde Änderung oder eine unterbrochene LuckPerms-Speicherung festgestellt wird, bleiben die betroffenen Accounts bis zur Wartung gesperrt. Die Serverkonsole nennt die Sicherungs-ID. Vergleiche vor der Freigabe beide ursprünglichen und aktuellen Stände. Der Bericht zeigt Fehler einzelner Integrationen separat an, wenn der Dateitausch bereits abgeschlossen ist.
