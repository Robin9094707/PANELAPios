# Admin Suite 10.1.0 – Befehlsrechte, Live-Schalter & LuckPerms-Vorlagen

Plug & Play: JAR ersetzen. Bestehende Daten werden nicht umgeschrieben; neue Daten liegen in
`plugins/SimplexAdminSuite/command-rights.properties`.

## Befehlsrechte

- Jeder Befehl des Servers (Command-Map, Aliase zusammengefasst) ist pro Spieler **erlaubt** oder **gesperrt** –
  dauerhaft oder befristet, mit Grund. Gesperrte Befehle werden beim Ausführen abgefangen (auch über Aliase und
  `plugin:befehl`), verschwinden aus der Tab-Vervollständigung (Befehlsbaum wird live neu gesendet) und der Spieler
  sieht Restzeit und Grund. Sperren gelten auch für OPs.
- **Alt-Vererbung:** Sperren gelten standardmäßig für alle bestätigten Alt-Accounts (auch künftig erkannte, gleiche
  Regeln und Schalter wie bei Rechte-Sperren); pro Sperre abschaltbar (`alts=false`). Eine eigene Freigabe des Alts hat
  Vorrang.
- **Global:** Befehle für alle Spieler sperren (`commands.global`, nur Vollzugriff). Ausgenommen: OPs und
  `simplexadminsuite.commands.bypass`. Eine Freigabe für einen einzelnen Spieler ist eine Ausnahme davon.
- **Rang:** Ob ein Befehl ohne Suite-Sperre nutzbar ist, entscheidet die Berechtigung (online: Bukkit, offline: LuckPerms).
  `allow` mit `grant=true` setzt fehlende Rechte als `lp user … permission set … true` – nur für Schlüssel mit
  Vollzugriff und `action.luckperms.edit`.
- API: `GET /v1/data/commands` (Übersicht, globale Sperren, Spieler mit Einträgen) und `?player=` (alle Befehle mit
  `state` = allowed/blocked/inherited/global/noperm, `kind`, `plugin`, `permission`, `own`, `override`);
  Aktionen `commands.update` (`block`, `allow`, `reset`, `clear`; `commands` kommagetrennt, bis 400) und
  `commands.global` (`global-block`, `global-allow`). Rechte `data.commands`, `action.commands.update`,
  `action.commands.global` (Rollenvorlagen ergänzt). Features `command-rights-v1`, `live-switches-v1`.
- Spielmenü (Java-Inventar und Bedrock-Formulare): Rechte & Sperren → **Befehle** – nach Art, A–Z oder Suche; Klick
  schaltet um, Dauer wählbar, „nur dieses Konto“ ohne Alt-Vererbung.

## LuckPerms

- `luckperms.edit` kennt `group-template` (`template` = spieler, vip, builder, supporter, moderator, admin; `group`
  optional): Gruppe anlegen, Gewicht, Präfix, `parent add default` und die Rechte der Vorlage – je ein geprüfter
  LuckPerms-Befehl. Übersicht liefert `templates`. Feature `luckperms-v3`.

## Webpanel

- Rechte & Sperren: Schalter statt Haken (an = erlaubt), sofort gespeichert mit der gewählten Gültigkeit/Dauer.
- Neue Seite **Befehle** (Übersicht, globale Schalter, pro Spieler mit Gruppierung nach Art/Plugin/A–Z, Suche,
  Filter, „Alle an/aus“ je Gruppe; große Gruppen werden nachgeladen).
- LuckPerms: Rechte als Schalter, Vorlagen, Rang-Karte (Präfix/Suffix, Gruppen zuweisen) im Spielerprofil.

## Tests

`Release1010Harness` (53 Checks): Namensprüfung, Pläne, Arten, Alias-Auflösung, Sperren/Freigeben/Zurücksetzen/
Leeren, globale Sperren mit Ausnahme, Neustart-Persistenz, Abfangen beim Ausführen, Tab-Liste, LuckPerms-Vergabe nur bei
fehlendem Recht, Vorlagen und Injection-Schutz. Alle bisherigen Harnesses laufen unverändert.
