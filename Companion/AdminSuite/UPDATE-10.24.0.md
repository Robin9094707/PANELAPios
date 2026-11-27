# Simplex Admin Suite 10.24.0

`SimplexAdminSuite-10.24.0.jar` statt der alten JAR in `plugins/` legen und den Server neu starten. Alle Daten bleiben erhalten; neue Einstellungen werden mit Standardwerten gelesen.

## Länder-Zentrale (Lands)
- Neue Klassen `LandsAdmin` (Lands-API per Reflection, geprüft gegen LandsAPI/Lands 9.3), `LandsApi` (`/v1/lands…`) und `LandsFlagTitles` (deutsche Rechte-Namen).
- Dashboard, Land- und Gebietsdetails, Länder eines Spielers, alle Verwaltungsaktionen (Mitglieder, Rollen, Rechte, Natur-Regeln, Bann, Besitzer, Name, Begrüßung, Steuern, Landbank, Gebiete, Löschen).
- Schutz: Besitzer-Rolle nie vergeben, Besitzer nie entfernen/bannen, Löschen nur mit `lands.delete`, Vollzugriff und Landnamen.
- Webpanel: neue Seite `lands.js` (Länder, Land, Gebiet, Länder-Karte im Spielerprofil); die alte Lands-Seite in `extras.js` ist entfernt.
- Neue Rechte `lands.manage` (Admin-Vorlage) und `lands.delete`.

## Experimentelle Identitätssignale
- `IdentitySignals` (Listener + `identity-signals.yml`): seltene Skin-Textur-IDs (Java und Bedrock über Geyser) und Kontowechsel-Paare.
- `AltMatchCacheEngine`: zusätzliche Hinweise (Skin, Kontowechsel, IPv6-/64, Bedrock-ClientRandomId), nie kaskadensicher.
- Konfiguration:
```yaml
alt-detection:
  experimental:
    enabled: true
    skin-fingerprint: true
    account-switch: true
    switch-window-seconds: 120
    switch-minimum: 2
    network-prefix: true
    client-random-id: true
    max-shared-accounts: 3
```

## Tests
`tools/tests/Release1240Harness.java` (in beiden Listen von `tools/test-830.py`).
