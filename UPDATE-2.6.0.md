# Simplex Panel 2.6.0 / Admin Suite 9.1.0

The repository and published latest release were verified at app-v2.5.0 / plugin 9.0.5 before this change.

## Updating without resetting state

Back up the complete plugin folder and worlds. Keep the SimplexAdminSuite folder, database, API keys, TLS identity, bans, Alt links, permissions and device history. Replace only the old JAR during a planned restart; do not leave two Suite JARs in /plugins. Alternatively stage the new JAR from its existing plugin row and restart after checksum verification. No schema change is required.

Install the unsigned IPA using the same bundle ID `eu.simplexsmp.panel` and signing identity as the previous app. Do not uninstall it. Keychain accounts, cached settings and Suite connections retain their existing identifiers. An unsigned IPA must be signed by your existing installation tool.

## Changed behavior

- Plugins: selecting a JAR opens a stable workflow sheet. The picker callback starts validation immediately. Progress, errors, retry, versions, compatibility acknowledgement and “Update vormerken” are visible in the same workflow. Upload checksums and original-JAR conflict protection remain active.
- Player NBT: one shared server resolver authorizes and discovers the same exact UUID `.dat` files. Modern dimension worlds resolve back to their world root. `server.properties` level-name and `/world` are directly probed. No recursive file walk; `.dat_old` is not silently edited. Modern `players/data` is preferred. Paths escaping the server root through symlinks are rejected.
- If the paired Suite cannot find a player file, the app probes modern and legacy directories via Panel API, matching the raw filename (not a displayed player-name alias). Panel-only results require a stopped server for writes; Suite results retain server-side offline checks, SHA-256 conflict detection and automatic original backups.
- VPN: `vpn-protection.target-platform` accepts `both`, `java`, `bedrock`. Missing/invalid values default to both. Excluded platforms bypass provider checks and VPN cooldowns. Existing global enable/whitelist settings remain. App full-access changes apply immediately; Java/Bedrock menus cycle through all three choices.
- Alt evidence now links to the full matched account, its devices and its linked accounts. Raw IPs already present in Suite profiles can be copied in the app or Java chat. Bedrock provides a prefilled text field; clipboard support depends on the Bedrock client.
- Optional IP location: enable under VPN & IP-Diagnose or in the game network menu. Uses the existing HTTPS ipwho.is service, seven-day persistent cache, bounded queue, 900/day budget and Retry-After handling. Profile requests use background VPN classification; excluded platforms are never checked. Locations are hidden for unknown VPN results or any suspicious signals. Local/private IPs are shown but not sent externally. Approximate city/postal data may be absent or wrong; never identifies an address/person and never changes Alt scores or automatic bans. The app links available approximate place names to Maps.

Existing explicit geo on/off preference is preserved. Old irreversible IP hashes cannot be recovered; new real IP observations appear on subsequent joins.

Provider documentation: https://ipwhois.io/documentation

## Validation

Production Java compilation against pinned real Paper dependencies; existing HTTPS/authentication/confirmation/data regression harnesses plus Release910Harness. iOS build, simulator tests (including upload flow and exact UUID search) and device IPA packaging run through GitHub Actions. These checks do not substitute for testing against the user's live host or on a signed physical iPhone.
