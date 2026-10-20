# Panel for iOS

A native SwiftUI client for **your own Pterodactyl hosting account**, with Liquid Glass, iPhone/iPad layouts and no third-party runtime dependencies. Built with the iOS 26 SDK or newer; requires iOS 26+, including newer iOS releases. Not an official Pterodactyl or Kinetic application.

## Connect

1. Open your provider's **Pterodactyl panel**, then Account → API Credentials.
2. Create a **Client API key** (`ptlc_…`). No panel-admin or Application API token is needed or used.
3. In Panel, enter the panel's HTTPS URL, a friendly name and the client key.
4. Add as many provider accounts as you need; switch from the workspace menu.

Use the URL containing the web console, **not** the Minecraft game address or the provider's billing/marketing website. The app supports standard Pterodactyl 1.x Client API endpoints. Providers can disable endpoints or place browser challenges in front of the API; these restrictions cannot be bypassed by the app. Permissions and limits are enforced by the panel.

## Features

- Server list across saved panel accounts, search and pull to refresh
- Live CPU, RAM, disk, uptime, network totals and bounded CPU history
- Wings WebSocket console, log filter, font sizing, autoscroll, command entry, token renewal, reconnect and REST fallback
- Start/restart/stop/kill with confirmation for disruptive actions
- Files and folders: browse/search, UTF-8 edit (up to 8 MiB), create, upload from Files, download/share, copy, rename/move, compress/decompress, Unix permissions, bulk delete/archive, server-side URL pulls
- Backup create with exclusions/lock, status polling, download/share, restore with optional truncation, lock/unlock/delete
- Cron schedules and command/power/backup tasks: create, edit, execute and delete
- Databases, password display/rotation, network allocations and primary port, subusers and permissions
- Startup variables, Docker image, rename/description, reinstall, SFTP details and activity
- Account email/password, API keys, public SSH keys, 2FA setup and recovery codes, account activity
- Device-only Keychain credential storage, optional Face ID/device-code app lock, background privacy cover
- System/light/dark appearance, Dynamic Type, native navigation and actual Liquid Glass controls
- Clearly labelled demo workspace. Mutations/downloads are blocked in demo; no live credentials are bundled.

The app is currently German. API error details may use the provider's language. Advanced rights and permission names follow Pterodactyl naming. Hosting billing, node administration and changing provider-assigned RAM/CPU limits are outside the Client API.

## Version 1.2.1 — permissions, TPS and import fixes

- Per-server Client API permission metadata disables unauthorized actions and labels restricted access in German. Known Pterodactyl permissions cover console, power, files, backups, databases, users, schedules and startup/settings. Unknown provider extensions retain server authorization; JSON API 403 responses disable the exact rejected request for this connection. Refreshing permissions clears these denials. No admin token is required.
- TPS polling is independent of potentially slow player requests, starts only after live authentication and running state, and uses the same Wings command channel as the web console. Manual refresh, request time, timeout/error feedback, classic and extended Paper TPS output parsing. Generation-scoped cancellation prevents an old view task from disconnecting a newer connection.
- Files and plugin updates use the native document picker in copy/import mode with an unrestricted item type. JAR selection is validated after import, so provider type labels cannot hide compatible files. Plugin identity/version/CRC and upload SHA-256 checks remain in place.
- Text remains the default editor. Editor options → **Werte als Formular** adds searchable server.properties and nested YAML scalar forms, booleans, numbers, quoted strings and known Minecraft enums. Unknown/complex nodes stay untouched. Only edited lines are replaced; comments, indentation, CRLF and property escaping are preserved. No speculative plugin-specific enums or full YAML validation.
- Selectable console output renders ANSI 16/256/RGB and Minecraft section-sign/hex colors, bold, italic, underline and strikeout. Copy/filter use plain text. Scrollback is bounded by both lines and a 1 MiB UTF-8 budget.

Pre-update backup: [`backup/v1.2.0-before-permissions-fixes`](https://github.com/Robin9094707/PANELAPios/tree/backup/v1.2.0-before-permissions-fixes). Live provider writes and third-party iCloud/File Provider implementations require device verification; no hosting credentials are bundled.

## Version 1.2.0 — quality of life

- Compact online count; searchable player names in an information sheet. Paper `tps` output supplies 1/5/15 minute values, polled about every 30 seconds only in the foreground Minecraft server view. Thresholds: >=19 green, >=16 yellow, below16 red; no fabricated TPS if the command is unavailable.
- Status badges on workspace cards, refreshed in batches of 3 every 15 seconds while visible. Unknown/failing responses show Unknown. Start only while offline; restart while running; stop while running/starting; kill while running/starting/stopping. The power request rechecks the current REST state before sending.
- Selected account and Keychain access are retried on foreground activation; profile keys, bundle ID and Keychain service are retained. A temporary startup Keychain failure no longer requires reselecting the panel.
- Visual destination folder picker for individual and bulk moves, parent/child navigation, descendant prevention, collision checks and confirmation. Rename now asks only for a filename.
- File metadata decoding, sorting and filtering run outside the main thread where large; the UI grows its list in batches of 200 as users scroll. Standard Pterodactyl returns a complete directory array: this is incremental rendering, not a claim of HTTP streaming/pagination unsupported by Wings.
- Text files up to 8 MiB download through signed URLs into temporary files instead of the provider-limited contents editor endpoint. Native editable text selection, optional syntax colors for YAML/JSON/properties-like text, viewport-scoped coloring for large documents, and conservative quick forms for simple unquoted YAML scalar values (max300 fields/512KiB). No full YAML validation or lossy reserialization. Haptic feedback is optional too.
- Plugin selection before updates; local ZIP/deflate/CRC parsing of `plugin.yml` or `paper-plugin.yml`, identity/main-class matching, same-version and numeric downgrade rejection, explicit confirmation for compatibility. Non-numeric version order remains unproven. The update directory is read from bukkit.yml; uploaded bytes are SHA-256 verified. Active JARs remain untouched until Paper's next start. This cannot prove dependency, Minecraft or Java compatibility.
- Local Java-format NBT editor for gzip/zlib/raw `.dat`/`.nbt`/schematic files, including Geyser/Floodgate player data on Java servers. Preserves all tag types, integer widths, float bits, list element types, ordering, unknown fields and unchanged Modified UTF-8 strings. Shows hearts, hunger, XP, dimension, position, inventory/abilities branches and full typed tree editing. Player names use `bukkit.lastKnownName` or server `usercache.json`; unknown names retain UUIDs. No third-party name-service calls.
- NBT changes are local drafts. Applying requires a stopped server, confirmation, a byte-verified original backup under `/.panel-nbt-backups`, a fresh state/conflict check and read-back verification. No automatic kick, no live playerdata overwrite. Server start/write races from other clients cannot be atomically locked through this API. Export remains available. Limits:8MiB source,16MiB decompressed,100000nodes,64levels; large arrays beyond the scalar editor limit remain readable/exportable. Bedrock-native LevelDB/little-endian worlds and MCA region containers are not supported.

The uploaded real player file was inspected locally; its content is not bundled or published. Tests use independently generated synthetic fixtures. Format references: [NeoForge NBT](https://docs.neoforged.net/docs/1.21.1/datastorage/nbt/), [Kaitai binary NBT specification](https://formats.kaitai.io/minecraft_nbt/) and [Paper plugin descriptors](https://docs.papermc.io/paper/dev/plugin-yml/).

Pre-update backup: [`backup/v1.1.0-before-qol`](https://github.com/Robin9094707/PANELAPios/tree/backup/v1.1.0-before-qol).

## Version 1.1.0

- Full-width, wrapping UIKit console with native partial text selection/copy; filter/copy all, font sizing and autoscroll that preserves active selections.
- Per-server command shortcuts and local Minecraft/Paper suggestions. Tapping fills the command field; execution always requires Send. The Client API provides no Java/Brigadier tab completion.
- Minecraft online count and names from the provider player endpoint (about 15 seconds); when unavailable, a running server receives `list` about every 30 seconds while the server view is foregrounded. Parses Paper's `There are 2 of a max of 60 players online: ...` output, including dotted Bedrock names. Status can be delayed and is timestamped.
- Local server favorites and folder pins in the workspace, persisted independently from account credentials. No remote pin synchronization endpoint is documented.
- Native database connection/password screens, copy, create, rotate and delete. SQL/table contents are outside this API.
- Plugin JAR inventory, enable/disable/delete, configuration access and upload to Paper's `/plugins/update` staging folder. Paper applies compatible plugin updates on restart; customized update folders use the file manager. This is manual JAR management, not automatic version discovery. [Paper update guide](https://docs.papermc.io/paper/updating/#step-2-update-plugins).
- Native Kinetic Minecraft properties editor with typed values and changed-field conflict checks; standard providers retain the file editor. World folder access is provided; binary NBT/world terrain editing is not.
- Kinetic Client API extensions: allocations path/method compatibility, domains/subdomains, reverse proxy, firewall, instance rename/swap/delete, mounted backups and native views of software/version/modpack/split details. Documented create/delete bodies are implemented; installer bodies absent from the supplied API documentation are not guessed. Exact Modrinth project/version IDs can be submitted through the provider download endpoint.

Kinetic extensions are enabled for `kineticpanel.net` and its subdomains. Provider schemas vary; unknown player responses fall back to the console and API failures remain visible. No admin token or application API is used. Live provider write operations have not been exercised without a user account.

### Updating from 1.0.0

The bundle ID remains `eu.simplexsmp.panel`; existing `profiles`, `selectedPanel`, appearance/lock preferences and Keychain service `eu.simplexsmp.panel.credentials` are unchanged. Install over the previous app using the same signing identity and bundle ID. Do not delete the old app first. Changing the signer or bundle ID can prevent access to existing Keychain data.

The pre-update source is retained in [`backup/v1.0.0-before-provider-update`](https://github.com/Robin9094707/PANELAPios/tree/backup/v1.0.0-before-provider-update).

## Build and unsigned IPA

The `Build and test unsigned iOS app` GitHub Actions workflow generates the Xcode project using XcodeGen, runs XCTest and XCUITest on an iPhone simulator, builds the ARM64 Release app with code signing disabled, verifies its executable and packages `Payload/Panel.app` as an unsigned IPA. Artifacts include the IPA, SHA-256 and simulator test results/screenshots. Push to main or use **Run workflow**. For packaging/documentation-only changes, the workflow can reuse an authenticated successful simulator test step from commit `851f72c4b45f354463e95f5f419796e234f0c188`, but only after verifying that all app source, assets, tests, project settings and entitlements are byte-identical. Other changes to those inputs run the unit suite and the selected UI smoke tour. The release app is rebuilt; the artifact includes the precise validation scope in its proof.

For local builds on a Mac with Xcode 26+:

```sh
brew install xcodegen
xcodegen generate
open Panel.xcodeproj
```

An unsigned IPA must be signed using your own Apple signing method before iOS can install/run it. No signing certificate, provisioning profile, panel key or other secret is needed by the build workflow.

## Validation scope

Automated tests cover API URL normalization and query encoding, authentication headers, plain-text file writes, pagination, HTTP errors/HTML protection pages, JSON mapping, allocations, console cleaning, Keychain persistence, player parsing/provider shapes, native database/property mapping, isolated favorites persistence, and one demo UI tour through console width, shortcuts, files, backups, Minecraft settings and databases. Simulator tests use local fixtures. Actual provider connectivity and destructive server actions require validation against your own account; a green build does not prove those integrations work on every modified provider panel.

API contracts are based on the upstream [Client API routes](https://github.com/pterodactyl/panel/blob/1.0-develop/routes/api-client.php). Credential-bearing panel requests only follow same-origin HTTPS redirects. Signed Wings upload/download URLs and short-lived WebSocket tokens are used without forwarding the Client API key. No analytics or app backend.
