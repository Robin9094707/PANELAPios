# Simplex Panel 5.1.0 und Admin Suite 10.8.0

Der KI-Assistent liegt in der Admin Suite. Discord verwendet `/sasadmin ai ask`, `/sasadmin ai propose` und `/sasadmin ai inbox`. Antworten und Freigaben sind dort privat. App und Webpanel öffnen „KI-Assistent“. Eine aktualisierte App zeigt mit einer älteren JAR eine Vorschau und bei gekoppeltem Panel einen Link zu Plugin-Updates.

Die Suite verwendet den bestehenden OpenAI-Schlüssel und das konfigurierte Modell. Der Assistent ist für eingerichtete OpenAI-Installationen standardmäßig verfügbar. `openai.admin-assistant.enabled: false` schaltet ihn gezielt ab. Manuelle Werkzeuge funktionieren unabhängig davon.

## Zugänge und Freigaben

- Teamkonten benötigen `ai.assistant.use` und die bestehenden Rechte für jede Datenquelle und Aktion. Die Tageslimits stehen unter „KI-Assistent → Tageslimits“. Hauptadmins haben kein Tageslimit. Für Discord-Teamkonten ordnet `openai.admin-assistant.discord-accounts.<Discord-ID>` ein bestehendes Suite-Konto zu. Das Discord-Rollenprofil gilt zusätzlich.
- Als Hauptadmin gilt das Suite-Eigentümerkonto, in Discord der Eigentümer des konfigurierten Servers. Für einen älteren Vollzugriffsschlüssel kann lokal die **Schlüssel-ID** unter `openai.admin-assistant.owner-api-key-id` eingetragen werden.
- Ein Plan enthält bis zu sechs konkrete Schritte und läuft nach 20 Minuten ab. Der Hauptadmin sieht Ziele und Werte und bestätigt einmalig. Die Suite prüft beim Bestätigen die Rechte erneut. Teilergebnisse sind einzeln sichtbar. Die Vorschläge bleiben bis zur Freigabe oder bis zum nächsten Plugin-Neustart im Speicher.
- Unterstützt sind Neustart/Abbruch, Spielersanktionen und ausgewählte Spieleraktionen, Alt-Sanktionen für ausdrücklich angezeigte Konten, Ticketbearbeitung, Ankündigungen, Wartung, Chat, Weltspeichern und mehrere Welteinstellungen. Konsole und Änderungen an Zugangsdaten bleiben in den manuellen Werkzeugen.
- KI-Neustarts verwenden einen einmaligen Chathinweis und eine Bossbar in den letzten drei Minuten. Die Wiederanlaufkonfiguration des bestehenden Paper-Setups gilt weiterhin.

## Daten und Diagnose

Die KI wählt aus einer Rechte-gefilterten Quellenliste gezielte Abfragen. Der Server lädt nur begrenzte Zeitfenster, Suchtreffer oder Einzelprofile. Kennwörter und API-Schlüssel sind keine Datenquelle, IPs und erkennbare Tokens werden aus den Auszügen entfernt. Der öffentliche Chat kann bis zu 2.000 Einträge für Zeitfenster über die letzten 48 Stunden durchsuchen. Bei hoher Aktivität wählt die Suite einen zeitlich verteilten Auszug mit lokalen Moderationshinweisen. Die letzten 50 Nachrichten bleiben das Fenster des Live-Chats. Antworten beziehen sich auf die vorhandenen Protokolle und das ausgewählte Zeitfenster.

Diagnose 3.0 protokolliert bei verfügbaren Paper-Verbindungen Pakettypen, Richtung, KeepAlive-Alter und Netty-Fehler. Es werden keine Paketkörper gespeichert. Der Assistent kann ausgewählte Metadaten zusammen mit dem Disconnect-Verlauf auswerten. Bei Bedrock ist die serverseitig sichtbare, von Geyser übersetzte Verbindung maßgeblich. `diagnostics.packet-metadata: false` deaktiviert die neue Erfassung für neue Verbindungen.

Die IPA mit der bisherigen App-ID signieren und als Update installieren. Bei gestopptem Minecraft-Server die bisherige Admin-Suite-JAR durch 10.8.0 ersetzen. Die vorhandenen Datenordner und Zugänge weiterverwenden.
