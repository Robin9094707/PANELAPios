# Simplex Panel 4.5.0 – Diagnosezentrale

Benötigt Admin Suite 10.2.0 für die Diagnose; alles andere funktioniert auch mit 10.1.x.
IPA einfach drüber installieren (gleiche Bundle-ID und Signatur) – Verbindungen, Zugänge, Layouts und Widgets bleiben erhalten.

## Behoben

- **„HTTP 400: Pflichtfeld fehlt: grant“.** Einzelne Befehle eines Spielers ließen sich weder in der App noch im
  Webpanel umschalten: Die Suite verlangte beim Sperren das Feld `grant` und beim Erlauben das Feld `alts`. Die Suite
  behandelt beide jetzt als optional (mit den dokumentierten Standards), und App/Webpanel senden immer beide – so
  funktionieren die Schalter auch noch mit einer Suite 10.1.0.

## Diagnose (Admin Suite → Diagnose)

- **Live:** TPS, Ø- und längste Tickzeit, RAM, GC, CPU, Java-/Bedrock-Spieler, Entities/Chunks und Disconnects der
  letzten Stunde, aktualisiert alle 5 Sekunden, mit Verlauf über 10 Minuten und Lag-Spitzen.
- **Disconnects & Vorfälle:** jede Trennung mit Grund (Timeout, Verbindungsabbruch, Paketfehler, selbst getrennt,
  Schutzsystem-Kick …), Meldung aus der Konsole, Ping, Sitzungsdauer und Ort. Drei oder mehr Disconnects in wenigen
  Sekunden oder ein großer Teil aller Spieler werden zu einem **Vorfall** mit Einschätzung **legitim** oder **Fehler**:
  Server-Lag/Watchdog, Netzwerkpfad (z. B. nur Java betroffen, Bedrock blieb), defektes Item/Chunk am selben Ort,
  Anticheat-Welle – mit konkreten Schritten. Instabile Verbindungen einzelner Spieler werden erkannt.
- **Bark:** Neue Ereignisse *Disconnect-Wellen*, *Serverabsturz erkannt* und *Server-Hänger* – als kritischer oder
  dringlicher Hinweis für alle, die sie in der Sicherheitszentrale → Bark aktivieren (Ruhezeiten gelten weiter).
- **KI-Einschätzung** mit dem bereits hinterlegten OpenAI-Schlüssel: nur kurze, IP-bereinigte Auszüge (deduplizierte
  Warnungen/Fehler, Kennzahlen), Antwort in drei Zeilen plus Schritte. Kritische Vorfälle und Abstürze automatisch.
- **Lag-Karte:** Chunks mit den meisten Entities und Block-Entities (Typen, nächster Spieler, Teleport-Befehl zum
  Kopieren), Entity-Typen, neue Chunks pro Spieler, Spieler mit vielen Entities in der Nähe.
- **Spark:** Profil aus der App starten/stoppen oder einen Spark-Link einfügen – der Server lädt das Rohprofil und
  analysiert es selbst: Plugins nach Rechenzeit, Plugin-Stellen, teuerste Methoden, Hotspots, Befunde.
- **Abstürze:** Nach einem echten Absturz (Watchdog, Speicher voll, Java-VM, Tick-Fehler) oder einem harten Beenden
  zeigt die App einen Bericht mit den letzten Konsolenzeilen.
- **Volldiagnose:** alles in einem Durchlauf, mit Punktzahl – auch DNS, Mojang-Anmeldung und Internet vom Server aus.
- **Konsole:** letzte drei Stunden durchsuchen, nach Warnungen/Fehlern filtern.

## Spieler

- Spielerprofil → **Verbindung & Disconnects**: Trennungen heute/7 Tage, unfreiwillige Trennungen, Ø Sitzung und ob
  die Verbindung gerade instabil ist.

## Rechte

- Neue App-Benutzerrechte: *Diagnose ansehen* (`data.diagnostics`), *Diagnosen ausführen* (`diagnostics.run`),
  *Diagnose steuern* (`diagnostics.manage`). Die Vorlage „Admin“ enthält alle drei, „Moderator“ das Ansehen.
