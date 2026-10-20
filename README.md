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

## Version 1.5.0 — Simplex Panel + optional Admin Suite 8.3.0

- Display name becomes **Simplex Panel**. Bundle IDs, panel profiles, original credential Keychain service, workspace data and widgets remain compatible.
- Per-panel/per-server optional Admin Suite HTTPS connection, separate Keychain token, server identity check, exact SHA-256 certificate pinning over a separate Network.framework TLS 1.2+ channel for the plugin-generated certificate (global ATS remains enabled), system trust for publicly trusted HTTPS, and no redirects with credentials. Disconnect/revoke is independent of Pterodactyl.
- Native German player directory with pagination/search; live health, hunger, XP, position and mode; real suite devices/diagnostics, alt-account evidence, Ultra groups/member overview and whole-group release. When enabled, the existing player manager routes moderation to the suite and never silently falls back after an error.
- Full-key remote menu bridge renders existing Suite menu callbacks and legacy inventory slots as native lists, with prompts, existing service confirmations, revision checking, additional one-use confirmations and revocation-aware delegated actors. Avatar-dependent actions still require an in-game player; credentials/API key creation remain local.
- Complete current Discord administration command schema becomes native searchable grouped forms with choices, booleans, live autocomplete, and server-bound 60-second single-use confirmation tickets. Suite errors and service output are displayed without assuming success from HTTP 200. Advanced Suite-owned console commands are available separately with a full key; in-game-only commands still require an actual in-game player.
- Existing inventory/enderchest inspection, typed snapshot content and existing create/restore/delete services; safe stored NBT file discovery links to the full existing offline NBT editor. Supported live values are updated on the server thread through Bukkit; arbitrary live playerdata file replacement is intentionally not performed.
- Typed existing Suite configuration (booleans, numeric values, strings and lists); secret/authentication settings remain local. Existing Discord/OpenAI configuration is retained. Some modules require Suite reload after configuration changes.
- Read/manage/full API key scopes, hashed server-side tokens, revocation only by a real local operator/console, no public HTTP backend, bounded requests/rates/threads, and audit attribution. Server API defaults to off. Direct TLS auto-generates a private EC certificate via the Java JDK keytool; match `/sas app fingerprint` in the app. A separate allocated TCP port is needed. Loopback-only backend mode supports your own HTTPS reverse proxy.
- All explicit copy actions provide haptics and copy feedback; Suite copy buttons animate locally too.
- Regression tests cover endpoint/pin validation, scoped persistence, wrong-server rejection, confirmation transport and credential redirects. Simulator tests cover setup; live hosting/network/signing and real Minecraft effects require testing on the user's server.

Rollback: `backup/v1.4.0-before-adminsuite` preserves the prior app. Disable the plugin API with `/sas app disable`; remove the app connection. The optional API only adds `app-api` files/settings. Keep the existing plugin data folder and the original 8.2.0 JAR as a rollback backup. Gameplay/admin changes made through the app are real changes, so backing up the plugin data before installation is still necessary.

## Version 1.4.0 — player manager and provider fixes

- Fix YAML syntax coloring crossing line boundaries after unmatched quotes or apostrophes in comments. String/comment lexing respects quoted `#` and viewport coloring covers complete lines.
- Paused downloads always offer **Fortsetzen**, including servers that return no Apple resume data. Missing resume data explicitly means restart from zero. New download records retain non-secret source metadata to request fresh Client-API links. Recoverable 1.3.0 records infer their file/backup source from the active panel's server list; otherwise the UI explains how to start that old download again. A separate **Neu laden** discards stale resume data. Signed restart URLs/resume data stay in protected, backup-excluded app storage.
- Backup response normalization accepts nested/top-level URLs, numeric success flags and status-based success. Known provider restrictions and exact GET 403 denials disable download actions. Kinetic's documented `POST .../backups/{uuid}/export` is a separate, confirmed operation; acceptance without a URL is shown as a preparation request, never a finished download. Hoster storage restrictions are not bypassed. Live provider credentials were not available for testing.
- Subuser creation/editing loads `GET /api/client/permissions`: grouped switches, names/descriptions, search, provider extension permissions and account-scoped grant checks. Unknown existing permissions are retained. There is no invented catalog when the endpoint fails.
- Refresh gestures now immediately reload workspace presence and server resources, with a native refresh control in the UIKit console. Account, management, Minecraft properties, database details, players and NBT status refresh support is expanded. Unsaved properties/NBT drafts are not silently discarded.
- Online player manager: recent player list, copied exact Java/Bedrock names, six targeted read-only console queries for health, hunger, XP level, mode, position and dimension. Optional 15s refresh is off by default. Edits use explicit vanilla commands (mode, XP levels, healing and saturation), with full command previews and confirmation. Kick/ban and up to 20 additional command templates are stored per server. Names/placeholders/reasons are validated against selectors and newline injection. Command transport acceptance is not claimed as successful server execution. Console feedback, English/German response format, permissions and installed plugin commands determine availability; arbitrary online player NBT writes require a suitable server plugin.
- Widget signing compatibility: recognizes entitled Panel group names rewritten in an embedded provisioning profile, and uses the available App Group as a shared Keychain access group, with legacy credential fallback. Candidate discovery is restricted to Panel's group family and the OS still checks actual entitlement access. A signer that strips the App Group/extension cannot be repaired by app code. The original app bundle ID, default Keychain group/service and saved panel/workspace keys remain unchanged.
- Added seven targeted regression tests plus new player, permission-switch and paused-download UI checks. Primary references: [Panel permission catalog](https://github.com/pterodactyl/panel/blob/develop/app/Http/Controllers/Api/Client/ClientController.php), [permission semantics](https://github.com/pterodactyl/panel/blob/develop/app/Models/Permission.php), [Apple app-group/keychain sharing](https://developer.apple.com/documentation/security/sharing-access-to-keychain-items-among-a-collection-of-apps). Kinetic routes are based on the provider's supplied API documentation.

## Version 1.3.0 — transfers, widgets and schedules

- Native background URLSession with file-backed uploads, byte progress, durable download files and transfer history. Download pause/resume uses iOS resume data when supported by the server. Signed links can expire; restarting from Files requests a fresh link. Uploads require reselecting the source after a failed task: standard Wings multipart does not implement resumable HTTP uploads. Force-quitting cancels iOS background transfers; ordinary suspension/system relaunch is supported. The transfer UI explains these limits. Incomplete remote uploads may require cleanup.
- ActivityKit lock-screen / Dynamic Island transfer activity, including a stale indicator. Activity progress is best-effort while backgrounded, without a push service. A new transfer replaces the displayed activity; all tasks remain in the transfer center.
- Real WidgetKit extension: per-widget server selection; small/medium home widgets and circular/rectangular lock-screen widgets. App snapshots have separate resource/player/TPS timestamps. Optional read-only background REST refresh uses a shared Keychain credential; no widget console commands. Kinetic player endpoint is supported; generic panels show last-known player counts and timestamps. iOS controls refresh scheduling (requested every 30 minutes), not continuous live updates.
- **Signing:** retain the original app bundle ID and credential Keychain access group to update existing installations. Sign and embed `eu.simplexsmp.panel.widgets` with the same team, enable App Group `group.eu.simplexsmp.panel`, and shared Keychain group `<TEAMID>.eu.simplexsmp.panel.shared` on both targets. Unsigned IPA includes the extension; a sideload signer must support these capabilities. If unavailable, the app displays an explanation and does not claim widgets are connected. Existing credentials remain in their original Keychain service. Widget access is opt-in and can expose explicitly selected server snapshots while the app is locked.
- Schedule editor: daily/weekly clock picker, minute presets, lossless advanced Cron editing, typed command/power/backup task editor, sequence, 0–900s delays and failure behavior. Next/last execution timestamps are displayed in Europe/Berlin. Panel timezone must be confirmed per provider (Client API does not disclose it); confirmation changes app interpretation only, never the panel configuration. HTTP Date is shown as an approximate panel clock, not the game-server clock. Different DST rules can shift German time.
- URL pulls remain server-side: standard Panel FileController returns 204 and discards Wings download identifiers. No invented percentages or completion claim. The dialog explains this; use download-to-device then upload for measurable byte progress.
- Targeted tests cover persisted progress, unknown totals, Cron validation, winter/summer time conversion, HTTP clock and widget snapshot freshness. Existing API, archive, NBT and UI checks remain enabled.

Primary implementation references: [Apple background transfers](https://developer.apple.com/documentation/foundation/downloading-files-in-the-background), [resumable uploads](https://developer.apple.com/documentation/foundation/pausing-and-resuming-uploads), [ActivityKit](https://developer.apple.com/documentation/activitykit/displaying-live-data-with-live-activities), [widget refresh budgets](https://developer.apple.com/documentation/widgetkit/keeping-a-widget-up-to-date), [Panel schedule transformer](https://github.com/pterodactyl/panel/blob/develop/app/Transformers/Api/Client/ScheduleTransformer.php), [Panel FileController](https://github.com/pterodactyl/panel/blob/develop/app/Http/Controllers/Api/Client/Servers/FileController.php), [Wings file handlers](https://github.com/pterodactyl/wings/blob/develop/router/router_server_files.go).

## Version 1.2.2 — plugin versions and versioned updates

- Update JARs use the verified descriptor name and version, such as `AirTags-3.0.0.jar`. The selected active JAR stays untouched while staging. Paper matches the exact plugin name and adopts the staged filename on restart; other implementations may retain the active filename. Version labels always come from the actual JAR metadata.
- Plugin rows show real name/version and separate pending-update versions. Detail sheets show descriptor name, version, API version, main class, description, authors, website, file information and the complete original plugin.yml/paper-plugin.yml with copy and syntax colors. This is the version on disk; the currently loaded version may differ until restart.
- Visible JARs up to8MiB are read automatically; larger files require opening Details (up to128MiB). Only one download runs at a time; ZIP/deflate/CRC parsing runs off the main thread. Only decoded metadata is cached for the current panel connection, keyed by server/path/size/modification time. Pull to refresh rereads metadata. No plugin code is executed.
- Sanitized bounded filenames, target-file collision checks and duplicate staged-plugin identity checks prevent silent overwrites and ambiguous multiple updates. Identity is case-sensitive to match Paper; main-class, version and upload hash checks remain in place.

Source backup: [`backup/v1.2.1-before-plugin-details`](https://github.com/Robin9094707/PANELAPios/tree/backup/v1.2.1-before-plugin-details). References: [Paper update guide](https://docs.papermc.io/paper/updating/#step-2-update-plugins), [Paper filename replacement implementation](https://github.com/PaperMC/Paper/blob/main/paper-server/src/main/java/io/papermc/paper/plugin/provider/source/FileProviderSource.java).

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
