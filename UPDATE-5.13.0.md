# Simplex Panel 5.13.0 und Admin Suite 10.20.0

Großes Sperren-Update: eine **Bann-Zentrale** in App und Webpanel, klare Trennung zwischen **Hauptbann** und **gesperrtem Alt-Konto**, Freigabe **nur dieses Kontos oder des ganzen Falls**, ein Werkzeug gegen **IP-Fehlbanns** – und **LiteBans wird abgeschaltet** und in das interne Bannsystem der Admin Suite übernommen.

Baut auf 5.12.0 / 10.19.0 auf. Nur JAR und IPA ersetzen – alle bestehenden Suite-Banns, Alt-Gruppen, IP-Ausschlüsse, Konten, Schlüssel, Spielerdaten und Einstellungen bleiben unverändert gültig. SentinelBridge 1.6.0 bleibt unverändert.

## Was passiert beim ersten Start mit 10.20.0?

1. Die Suite liest LiteBans' Daten einmal ein (Bans, Mutes, Warnungen, Namensverlauf) und speichert sie unter `plugins/SimplexAdminSuite/litebans-migration/snapshot.yml`. LiteBans' Datenbank wird **nur gelesen**, nie verändert.
2. Danach wird **LiteBans abgeschaltet** – bei jedem Serverstart erneut. Ab dann sperrt nur noch die Suite.
3. `/ban`, `/tempban`, `/unban`, `/mute`, `/tempmute`, `/unmute`, `/kick`, `/warn`, `/ipban`, `/banlist` laufen über die Suite (gleiche Rechte, Bestätigungen und deutsch/englische Spielertexte wie `/sas ban …`). Befehle anderer aktiver Plugins werden nie überschrieben.
4. **Noch wird nichts übernommen.** Die Fälle warten in der App bzw. im Webpanel auf deine Auswahl.

> Bis du die Migration startest, sind ehemalige LiteBans-Banns nicht aktiv. Am besten direkt nach dem Update die Migration durchgehen.

## LiteBans-Migration (App: Admin → Bann-Zentrale → Filter-Menü → LiteBans-Migration · Web: Bann-Zentrale → LiteBans-Migration)

- **Ein Fall = ein LiteBans-Befehl**: Hauptkonto plus die Konten, die LiteBans damit gesperrt hat (gleiche Zeit, gleicher Grund).
- Pro Fall wählst du: **Konten sperren** (empfohlen), **Konten + IP sperren**, **nur Hauptkonto**, **Alt-Erkennung (Ultrabann)** oder **nicht übernehmen**. Mutes und Warnungen ebenso.
- Pro verknüpftem Konto kannst du per Schalter festlegen, ob es mit gesperrt wird.
- **Geteilte IP 82.165.45.159:** Fälle, deren Hauptkonto über diese (ausgeschlossene) IP gesperrt wurde, werden standardmäßig **nicht** übernommen; Konten, die nur über diese IP verknüpft waren, ebenfalls nicht – und der IP-Teil wird **nie** übernommen. Beispiel aus deiner Datei: LiteBans #341 `.NiloderBoss` (IP-Bann auf 82.165.45.159, mit `.Dr30021` verknüpft) bleibt frei.
- **Abbruch?** Einfach „Migration fortsetzen“ – jeder Fall wird sofort nach dem Übernehmen gespeichert, erledigte werden übersprungen. Auswahl lässt sich jederzeit ändern.
- Bestehende Suite-Banns haben Vorrang und werden nie überschrieben. Ein Konto mit eigenem Hauptbann wird nicht zum Alt-Konto eines anderen.
- Abgelaufene und aufgehobene LiteBans-Einträge landen im **Verlauf** der Bann-Zentrale (mit Grund, wer, wann, wer aufgehoben hat).
- Datenquellen: das laufende LiteBans-Plugin, LiteBans' MySQL/MariaDB-Zugang aus `plugins/LiteBans/config.yml` oder direkt die Datei `plugins/LiteBans/litebans.mv.db` (H2-Treiber wird aus dem LiteBans-Ordner genommen oder einmalig mit geprüfter Prüfsumme von Maven Central geladen).
- Optional: **LiteBans-JAR dauerhaft deaktivieren** (wird umbenannt, Datenbank bleibt).
- Konsole: `/sas bans migration status [cases]`, `… snapshot`, `… decide <Fall>=<Wahl>`, `… run`.

Getestet mit der echten `litebans.mv.db` (312 Bans, 52 Mutes, 19 Warnungen, ~14 000 Namensverlauf-Zeilen): 36 aktive Fälle, 8 Alt-Konten, Neustart und Fortsetzen ohne Datenverlust.

## Bann-Zentrale

- **Fälle**: Hauptkonto, alle gesperrten Alt-Konten, Grund, Dauer, wer, wann, Herkunft (Suite, LiteBans, Alt-Erkennung), IP-Sperre. Suche nach Name, Grund, Fall-ID oder IP; Filter „mit Alt-Konten“, „zeitlich“, „aus LiteBans“, „Alt-Gruppen“.
- **Aufheben**: „Nur dieses Konto“ oder „Alle Konten des Falls“ (Hauptbann, Alt-Konten, Alt-Gruppe, IP-Sperre, Identitätssperren). Ein einzeln freigegebenes Alt-Konto wird von der Alt-Erkennung nicht erneut erfasst. Optional mit Notiz.
- **IP-Sperren & Ausschlüsse**: Wenn wegen einer gemeinsamen IP plötzlich alle gesperrt sind: IP prüfen (wer hängt dran, wie viele Konten kamen darüber) und **alles über diese IP aufheben**, auf Wunsch mit dauerhaftem Ausschluss. Ausgeschlossene IPs zählen weiter nie für IP-Sperren oder die Alt-Erkennung.
- **Verlauf**: aufgehobene, abgelaufene und übernommene ältere Sperren.

## Spielerprofil

- Neuer Abschnitt **Sperrstatus**: „Hauptbann“ oder „Gesperrtes Alt-Konto von …“, Fall, Herkunft, alle Konten des Falls, frühere Sperren.
- **Entsperren direkt im Profil**: Beim Hauptbann werden standardmäßig alle zugehörigen Banns aufgehoben; beim Alt-Konto wählst du „nur dieses“ oder „alle“.
- Spielerlisten zeigen „Alt-Konto gesperrt“ getrennt von „Gebannt“.
- `/checkban <Spieler>` zeigt im Spiel denselben Fall.

## Für Spieler (Deutsch/Englisch)

Gesperrte Alt-Konten sehen beim Beitritt, zu welchem Bann sie gehören, mit Grund, Restzeit und Referenz – auf Deutsch oder Englisch je nach Spielsprache (`messages.internal-alt-ban-login`). Hauptbanns zeigen zusätzlich die Referenz.

## Rechte

| Recht | Bedeutung |
| --- | --- |
| `data.ban-center` | Bann-Zentrale und Sperrstatus ansehen |
| `ban-center.manage` | Banns aufheben, IP-Sperren aufheben (Moderator-Vorlage enthält es) |
| `ban-center.migrate` | LiteBans-Migration (zusätzlich Vollzugriff) |

IP dauerhaft ausschließen braucht Vollzugriff.

## Konfiguration (neu, mit sicheren Standardwerten)

```yaml
litebans-migration:
  disable-litebans: true      # LiteBans bei jedem Start abschalten
  take-over-commands: true    # /ban, /mute … über die Suite
  take-over-pardon: false
  h2-file: ''                 # optionaler Pfad zur litebans.mv.db
```

`punishments.provider` wird ignoriert, solange LiteBans abgelöst ist (alles läuft intern). Wer LiteBans zurück will: `disable-litebans: false`, JAR ggf. zurückbenennen, neu starten.

## Gespeicherte Daten

Additiv: neue Felder unter `internal-punishments.ban.*` im Spielerprofil (Fall, Rolle, Hauptkonto, Herkunft), `bans/history.yml`, `litebans-migration/`, `released` in `alt-detection/ultra-bans.yml`. Ältere Versionen ignorieren diese Felder.
