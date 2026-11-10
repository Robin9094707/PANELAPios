# Simplex Panel 5.9.0 und Admin Suite 10.16.0

Baut auf 5.8.0 / 10.15.0 auf. Wer 5.8.0 / 10.15.0 installiert hat, ersetzt nur JAR und IPA – alle Daten (PowerBot-Fälle, Chatpausen, Disconnect-Verlauf, Vorfälle, Einstellungen, Konten und Rechte) bleiben erhalten und werden weiter gelesen. SentinelBridge 1.6.0 bleibt unverändert.

## PowerBot schlichtet stufenweise

PowerBot greift bei Streits nicht mehr nur einmal ein, sondern folgt einer **Eskalationsleiter**:

1. **Beruhigen** – eine neutrale Nachricht an alle, ohne Namen.
2. **Warnen** – geht der Streit weiter (Standard: nach 40 s), warnt PowerBot öffentlich und schreibt jedem Beteiligten persönlich, dass sonst eine Chatpause folgt.
3. **Kurze Chatpause** – hört es nach der Warnung nicht auf (Standard: 30 s), bekommen die Beteiligten **1 Minute** Chatpause. Die KI entscheidet pro Person, wer wirklich mitmacht: Opfer, Unbeteiligte und wer beruhigen will, werden **nie** stummgeschaltet.
4. **Tiefenprüfung** – geht es danach weiter oder übertreibt jemand deutlich (harte, unprovozierte Beleidigungen, Drohungen, Belästigung), holt sich PowerBot **mehr Kontext**: bis zu 40 Chatzeilen der letzten 10 Minuten, die eigenen Nachrichten der Person und ihren PowerBot-Verlauf der letzten 24 Stunden. Erst dann entscheidet eine zweite KI-Prüfung über **5 Minuten** Chatpause (bei unsicherer Einschätzung nur 1 Minute oder eine persönliche Warnung).

Bei strenger Einstellung geht es schneller, bei gelassener langsamer. Teammitglieder und Spieler mit Bypass-Recht werden nie stummgeschaltet.

**Pöbeln wird in Sekunden erkannt:** Eine schnelle lokale Erkennung (deutsche und englische Beleidigungen, gerichtet an „du/dich/ihr“ oder einen Spielernamen, Großbuchstaben, Ausrufezeichen) startet sofort eine KI-Prüfung – auch wenn das Moderationssignal nicht anschlägt und niemand zurückschreibt. Gaming-Sprache („noob“, „gekillt“), Zitate und Spaß unter Freunden bleiben erlaubt.

**Kontextanalyse alle 15 Nachrichten:** Der öffentliche Chat wird regelmäßig im Zusammenhang gelesen (einstellbar 5–50), während eines laufenden Streits schon nach wenigen Nachrichten erneut. Ein Stundenlimit (Standard 180 Analysen) schützt vor Kosten.

**Für Admins in App und Web:**

- Abschnitt **Laufende Streits** mit Stufenanzeige (Beruhigt → Verwarnt → Kurze Chatpause → Tiefenprüfung), Beteiligten, Grund und nächstem Schritt. „Beenden“ setzt den Streit zurück, „Beenden & Pausen aufheben“ hebt alle Pausen dieses Streits sofort auf.
- **Aktive Chatpausen** mit Countdown und Knopf zum vorzeitigen Aufheben (der Spieler wird informiert).
- Neue Einträge: Streit-Warnung, Tiefenprüfung (mit Begründung und Sicherheit), Pöbeln erkannt.
- Einstellungen: Eskalationsleiter an/aus, Analyse-Rhythmus, Dauer der kurzen Pause, Dauer nach der Tiefenprüfung.
- Die Fallzentrale aktualisiert sich alle 10 Sekunden.

## Disconnect-Forensik

Bisher galt jeder Disconnect mit dem Grund „Disconnected“ als **freiwilliges Verlassen** – auch eine ganze Welle davon wurde als „Legitim“ abgehakt. Paper meldet aber eine von außen geschlossene TCP-Verbindung (DDoS-Filter, Firewall, Router, Proxy) genauso wie einen Klick auf „Verbindung trennen“. Die Suite schaut jetzt dahinter:

- **Wer hat geschlossen?** Server, Gegenseite oder ein Netzwerkfehler – direkt in der Netty-Pipeline gemessen. Schließt der Server ohne Kick, wird der auslösende Code (Plugin) angezeigt, soweit sichtbar.
- **War der Client bis zuletzt aktiv?** Zeit seit dem letzten Paket vom Client. Mehrere Sekunden Stille = Client eingefroren/abgestürzt oder Leitung weg.
- **Kam der Spieler sofort zurück?** Rückkehr innerhalb von 3 Minuten macht aus „Disconnected“ einen **verdeckten Abbruch** – auch nachträglich, inklusive Neubewertung der Welle.
- **TCP vom Kernel:** RTT, Neuübertragungen, verlorene und unbestätigte Segmente aller Verbindungen alle 5 Sekunden (native epoll-Netzwerkschicht, Standard bei Linux-Hostern).
- **Datenrate, volle Sendepuffer und große Pakete** kurz vor der Trennung (Paketinhalte werden nie gelesen).
- **Client-Details:** Marke (Vanilla, Fabric, Lunar …), exakte Version mit ViaVersion, Sichtweite.
- **Netzbereich:** ein anonymer Kurzwert (gesalzener Hash des /24- bzw. /48-Präfixes). Adressen werden weiterhin nie gespeichert.

Neue Kategorien: **Verdeckter Abbruch (als „Disconnected“ gemeldet)**, **Client eingefroren/abgestürzt**, **Vom Server/Plugin getrennt (ohne Kick)**. Die ursprüngliche Meldung bleibt sichtbar.

**Wellen-Analyse:** Gleichzeitigkeit (wie viele Java-Spieler innerhalb von 3 s), nur Java betroffen während Bedrock (UDP) verbunden blieb, Rückkehrquote, wer geschlossen hat, gleiche Sitzungsdauer (Hinweis auf feste Verbindungs-Höchstdauer), gemeinsamer Netzbereich, gemeinsame Client-Version und **Regelmäßigkeit** über frühere Wellen („alle ~4 Stunden“, „immer um :15“). Daraus entstehen **gewichtete Ursachen** mit Begründung und nächstem Schritt, z. B.:

- DDoS-Schutz, Firewall oder Proxy beim Hoster trennt Java-TCP-Verbindungen
- Verbindungen werden nach fester Dauer gekappt (NAT/Conntrack/Proxy)
- Geplanter Vorgang (Backup, Filter-Update, Proxy-Neustart, Plugin-Aufgabe)
- Server oder Plugin schließt ohne Kick · Clients frieren ein · Paketverlust · gemeinsamer Anbieter

Eine Welle aus verdeckten Abbrüchen wird jetzt als **Fehler** gemeldet (Bark wie bisher). Ist die Heuristik unsicher oder die Welle verdeckt, startet die **KI-Analyse automatisch** – mit allen Spuren, Hypothesen, Netzwerk-Einstellungen und Plugins.

**Live-Netzwerk** (App: Diagnose → Werkzeuge, Web: Diagnose → Live-Netzwerk): jede Verbindung in Echtzeit mit Zustand (stabil, wackelig, Puffer voll, stockt), Ping, RTT, Neuübertragungen, Datenrate, letztem Paket, Verbindungsdauer, Client und Netzbereich. Stocken mehrere Verbindungen gleichzeitig, erscheint ein Hinweis – ideal, um beim nächsten Mal live zuzusehen.

**Server-Netzwerk & Plugins:** Kompression, Paket-Ratenlimits, native Netzwerkschicht, Watchdog, Proxy/Velocity/Proxy-Protokoll und alle Plugins, die Verbindungen berühren (ViaVersion, ProtocolLib, packetevents, Geyser, Anticheats, Netzwerkschutz).

### Optionale Plugins für noch tiefere Analysen

Nichts davon ist Pflicht – die Forensik funktioniert ohne. Die Suite erkennt sie automatisch:

- **ViaVersion** – liefert die exakte Client-Version jedes Java-Spielers. Dann sieht die Analyse, ob nur eine bestimmte Version getrennt wird.
- **spark** – Tick-, GC- und Plugin-Profile im Moment einer Welle; die Suite importiert und analysiert sie automatisch.
- **Proxy-Protokoll beim DDoS-Schutz** – nur falls euer Hoster einen Filter/Proxy vorschaltet (z. B. TCPShield): mit aktiviertem Proxy-Protokoll und passendem Plugin sieht die Suite echte Netzbereiche statt der Filter-Adresse.

Clientseitig kann ein Server nichts erzwingen; die Suite nutzt, was der Client ohnehin sendet (Marke, Version, Sichtweite, Sprache).

### Was ihr bei der nächsten Welle tun könnt

1. In der App **Diagnose → Live-Netzwerk** offen lassen.
2. Nach der Welle den Vorfall öffnen: „Muster der Welle“ und „Wahrscheinlichste Ursachen“ zeigen, was dahintersteckt.
3. Mit Uhrzeit, Anzahl und dem Muster beim Hoster nachfragen, falls „DDoS-Schutz/Firewall“ oben steht.

## Einstellungen (config.yml, optional)

```yaml
powerbot:
  escalation: true            # Eskalationsleiter
  dispute-mute-seconds: 60    # kurze Chatpause im Streit
  conflict-mute-minutes: 5    # Pause nach der Tiefenprüfung
  analysis-interval: 15       # Kontextanalyse alle N Nachrichten
  analysis-max-per-hour: 180
diagnostics:
  tcp-sampling: true          # TCP-Werte aller Verbindungen
  ai:
    auto-unclear: true        # unklare/verdeckte Wellen automatisch mit KI prüfen
```

Fehlende Einträge verwenden diese Standardwerte; bestehende Einstellungen bleiben unverändert.

## Update

1. Server stoppen, `plugins/SimplexAdminSuite` sichern.
2. `SimplexAdminSuite-10.16.0.jar` statt der alten JAR einsetzen, Server starten.
3. Die unsignierte IPA wie gewohnt mit derselben Signatur und Bundle-ID über die bestehende App installieren.

Rückweg: alte JAR wieder einsetzen. Neue Felder in `powerbot-actions.json` und `diagnostics/disconnects.json` werden von 10.15.0 ignoriert.
