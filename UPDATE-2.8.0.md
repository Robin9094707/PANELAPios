# Simplex Panel 2.8.0 · Player-Profil 2.0

- Gezielte Spielersuche mit 300-ms-Tipppause und 50 Profilen pro Seite. Online verwendet ausschließlich die aktuell verbundenen Spieler. Zuletzt online wird serverseitig sortiert. Weitere Seiten werden nur auf Anforderung geladen.
- Listen je Filter/Suche bleiben während der App-Sitzung erhalten. Profil öffnen und zurückkehren löst keinen vollständigen Download aus. „Neu laden“ bzw. Ziehen aktualisiert bewusst. Neue Suchtexte holen aktuelle Ergebnisse. Seiten verwenden einen stabilen, zehn Minuten gültigen Server-Snapshot; danach bitte neu laden.
- Profilübersicht mit Inventarvorschau für Online-Spieler, direktem Inventar-/Endertruhen-, NBT- und Snapshot-Zugang, letztem Ausloggort sowie VPN-Ausnahme mit Prüfung/Bestätigung. Live-Profilwerte werden alle 15 Sekunden aktualisiert; abschaltbar. Doppelte Alt-/Geräte-/NBT-Menüeinträge zusammengeführt.
- Bereits vorhandene VPN-Ausnahme erzeugt bei Vorschau und Ausführung einen Fehler, statt Erfolg vorzutäuschen. Ursprünglicher Eintrag und Ersteller bleiben unverändert.
- Startseite/Serverübersicht zeigen online/max, Scoreboard-Tage und deutsche Serverzeit. Scoreboard-Zähler werden aus dem bestehenden Modul übernommen; keine Neuberechnung aus Vanilla-Weltticks.
- Discord-Zentrale mit gegliederten vorhandenen Einstellungen und echter Kanalauswahl aus dem konfigurierten Discord-Server. Der Bot muss eingerichtet sein. Kanalzugehörigkeit wird vor Bestätigung und Speichern geprüft; Netzwerkzugriffe laufen außerhalb des Spielticks. Token und geschützte Zugangsdaten bleiben auf dem Server. Manche Moduländerungen benötigen Suite-Reload.

## Update ohne Datenverlust

Admin Suite 9.1.3 zusammen mit Panel 2.8.0 verwenden. Plugin stoppen, vorhandene JAR ersetzen, kompletten Datenordner behalten und starten. Keine Datenbank-/Profilschemaänderung; 9.1.2-IP-Ausschlüsse und Reparaturen bleiben bestehen.

IPA ist unsigniert. Mit derselben Bundle-ID und Signieridentität über die vorhandene App installieren, nicht vorher löschen. Schlüssel, Server und vorhandene App-Daten bleiben bei einem regulären Update erhalten.
