# Panel for iOS

A native SwiftUI client for **your own Pterodactyl hosting account**, with Liquid Glass, iPhone/iPad layouts and no third-party runtime dependencies. Built with the iOS 26 SDK or newer; requires iOS 26+, including newer iOS releases. Not an official Pterodactyl or Kinetic application.

## Connect

1. Open your provider's **Pterodactyl panel**, then Account → API Credentials.
2. Create a **Client API key** (`ptlc_…`). No panel-admin or Application API token is needed or used.
3. In Panel, enter the panel's HTTPS URL, a friendly name and the client key.
4. Add as many provider accounts as you need; switch from the workspace menu.

Use the URL containing the web console, **not** the Minecraft game address or the provider's billing/marketing website. The app supports standard Pterodactyl 1.x Client API endpoints. Providers can disable endpoints or place browser challenges in front of the API; these restrictions cannot be bypassed by the app. Permissions and limits are enforced by the panel.

## Version 2.1.0 / Admin Suite 8.4.0

The Suite hub and Statistics tab now open **Server & Betrieb** with structured Minecraft TPS/MSPT, JVM RAM/uptime, saved playtime/AFK/session totals, world settings, installed plugin versions and private Discord outbox diagnostics. Older Suite versions retain their existing navigation through feature detection.

Full-access Suite keys can change loaded-world time, weather, difficulty, PvP and autosave or retry saved private logs through the existing single-use confirmation flow. Read/manage keys cannot perform these new operations. API keys, HTTPS pins and existing app/server data are retained.

Admin Suite repairs failed-delivery backoff, sender exceptions and ACK races, coalesces repeated pending observations and preserves the existing SQLite queue during upgrade. Invalid Discord permissions or channel configuration remain visible in diagnostics; private-channel validation is mandatory.

The GitHub workflow builds and tests both products and publishes the unsigned IPA, plugin JAR and complete plugin source after success. Installation/API details: [Admin Suite 8.4.0](Companion/AdminSuite/UPDATE-8.4.0.md). Source rollback: [`backup/before-suite-8.4-panel-2.1`](https://github.com/Robin9094707/PANELAPios/tree/backup/before-suite-8.4-panel-2.1).

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

## Version 1.8.0 — console filters and native statistics

- A centered icon-only console action row keeps history navigation, history, tail, pause and shortcuts visible. VoiceOver retains complete labels. Typed Minecraft command suggestions remain readable separately.
- Combine a text search with All, Chat, Errors/stacktraces, Warnings, Joins, Leaves, Commands or Other. Standard Paper/Java log headers, unsigned chat and common German join/leave messages are recognized; custom plugin formats may remain under Other. Text inside a chat message does not become an error merely because it contains ERROR. Raw ANSI formatting is retained.
- Parsed line/category caches run outside the main actor. Bursts coalesce at about10 updates/s without starving continuous output; ordinary append-only updates extend native text storage instead of reparsing the whole scrollback. Existing selection/viewport restoration, bounded logs, output pause and per-server history remain.
- A Suite-only Statistics tab presents deduplicated profile counts, Java/Bedrock distribution, activity, versions, moderation and Points. Platform/model/version/input/language categories open matching profiles and their existing actions. Compact iPhones use the native More tab for the additional destinations; no existing feature is removed.
- The existing8.3.0 API has no dedicated Discord-statistics endpoint. Base charts use all paginated player records. Device metadata can be loaded on demand at at most2 requests/s; Java OS is not inferred. Recorded online peaks and hourly charts read the existing `/plugins/SimplexAdminSuite/server-statistics.properties` through the panel only when file-read rights allow it (2MiB/12s bounds). Missing/stale histories remain labelled; absent hours are gaps. UUID counts across recorded worlds are separate from current Suite-profile totals.
- No estimates for unavailable total playtime, AFK time, session totals or Discord-link counts. Public Discord online counts may exclude vanished players while the admin profile API includes them. Metadata is client-reported; categories count one latest-known value per profile, not physical devices or unique people.
- Long action/menu labels wrap. Selected choices, device fields, model names and setting previews remain fully readable instead of single-line/two-line clipping. A server-provided shortened title cannot be expanded beyond its returned content.
- **App-only update:** Admin Suite8.3.0 and all credential/workspace/layout/history/transfer/widget storage and bundle IDs are unchanged. Install with the same signer and bundle ID over the existing app, without uninstalling.

Rollback source: [`backup/v1.7.0-before-console-statistics`](https://github.com/Robin9094707/PANELAPios/tree/backup/v1.7.0-before-console-statistics), commit `4bb9ca49ad5cd23a92e1b6c95d90c277ea319671`.

## Version 1.7.0 — personal workspaces and console stability

- Optional in-app cards for the panel home and each server workspace: add, hide and reorder through the layout button. Existing layouts are the defaults. The home server list stays reachable; resetting requires confirmation and affects only the current layout. Additional cards include fleet status, pinned servers, panel information, server health, RAM history and console shortcuts.
- Java/Minecraft server cards read the existing root `server-icon.png` through the authenticated panel file API when permitted. Icons must be static 64×64 PNGs, max512KiB. Per-connection bounded memory caching, at most3 concurrent icon requests and silent fallback for missing/unreadable files keep browsing usable. Bedrock-only servers retain the placeholder. No public server-query service is used.
- Last20 successfully transported console commands persist locally per panel/server. Older/newer buttons and hardware arrow keys restore the current draft. Selecting history only fills the input; Send is still required. Clear history has confirmation. Output pause, jump to end, filter, font sizing and copy remain local tools.
- Console projection and ANSI rendering no longer rerun for each unrelated resource refresh. Scrollback stays bounded. The UIKit console tracks the visible paragraph and selection, follows new lines only while already at the bottom, and restores the viewport across foreground transitions/view reconstruction. A reconnect may still replay Wings' own recent log buffer.
- Player rows and profiles use platform-specific iOS, Android, Windows, Xbox, PlayStation, Switch and Java icons. Reported OS takes precedence over the model; unambiguous model names provide a fallback. Unknown laptop models use a bounded cached devices lookup, never a guessed Windows label. Raw model information remains available.
- RAM/CPU/disk indicators are green below70%, yellow from70%, red from90% of the panel limit. CPU100% means one core, not necessarily the account quota. The optional health card reports timestamp freshness and the worst known resource ratio; unknown/unlimited limits and stale/offline measurements do not receive a green health claim. TPS uses the existing command response and remains separately timestamped.
- **App-only update:** Admin Suite8.3.0 is unchanged. All prior bundle IDs, entitlements, Keychain services, saved panels/Suite connections, workspace pins, shortcuts, widgets and transfer records remain compatible. New preferences use separate keys. Install over the existing app using the same signing identity and bundle ID; keep the installed app and its data.

Rollback source: [`backup/v1.6.0-before-custom-workspaces`](https://github.com/Robin9094707/PANELAPios/tree/backup/v1.6.0-before-custom-workspaces), commit `a4a02b6935c844a03984d5fc96e8437097c7c558`. Reverting app source does not reverse real server actions.

## Version 1.6.0 — integrated player administration

- Workspace cards open online players and player search directly. A connected Suite adds device-labelled online profiles, player search/actions and recent offline profiles to the server dashboard and management tab. The existing Liquid Glass design remains.
- One player directory filters All / Online / Recently online. Name/UUID searches are debounced and cancel obsolete requests. Online scans continue across the API's 100-profile pages; recent offline views load all matching pages and sort by actual last-seen timestamps. More general results remain paginated.
- Profiles put current/last device, all recorded devices, ban/kick and Ultra ban up front. Existing server-issued confirmation tickets and read/manage/full permissions remain enforced. Full player records, inventory/NBT, original menus and all other actions remain accessible through compact disclosures or one grouped searchable action list.
- A shared per-server connection model reuses the verified client and action schema. Foreground dashboard refresh runs every 30 seconds; independent panel/access/Suite refreshes start together. Explicit refresh always rechecks Suite health/server identity. Search and paging reject stale responses and deduplicate by UUID.
- URLSession cancellation (-999) and Swift task cancellation no longer produce a Canceled alert during refresh. Other failures remain visible. Regression tests cover cancellation vs real authorization failures, cross-page online discovery, recent ordering and UUID deduplication; the simulator tour covers the integrated player/device/offline flows.
- **App-only update:** Admin Suite 8.3.0 and its files remain unchanged. Bundle IDs, Keychain services, saved profile/Suite connections, preferences, transfers, widgets and entitlements are retained. Install over the existing app with the same signing identity and bundle ID; do not uninstall first.

Rollback source: [`backup/v1.5.0-before-integrated-admin`](https://github.com/Robin9094707/PANELAPios/tree/backup/v1.5.0-before-integrated-admin), commit `52738c15d7ece1f33e16c6b1f56867748ce80eda`. Ask to revert this update to restore the saved source and build the previous app. Real moderation changes made on the server are separate from an app rollback.

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

The `Build and test unsigned iOS app` GitHub Actions workflow generates the Xcode project using XcodeGen, runs XCTest and XCUITest on an iPhone simulator, builds the ARM64 Release app with code signing disabled, verifies its executable and packages `Payload/Panel.app` as an unsigned IPA. Artifacts include the IPA, SHA-256 and simulator test results/screenshots. Push to main or use **Run workflow**. For packaging/documentation-only changes or companion-only changes, the workflow can reuse an authenticated successful simulator test step from commit `91183d0162c554769966e2cb68538c39c82711d9`, but only after verifying that all app source, assets, tests, project settings and entitlements are byte-identical. Other changes to those inputs run the unit suite and the selected UI smoke tour. The release app is rebuilt; the artifact includes the precise validation scope in its proof.

For local builds on a Mac with Xcode 26+:

```sh
brew install xcodegen
xcodegen generate
open Panel.xcodeproj
```

Before running simulator tests locally, start `python3 scripts/suite_tls_fixture.py` in a second terminal. This loopback-only synthetic TLS server is required by the pinned certificate integration tests.

An unsigned IPA must be signed using your own Apple signing method before iOS can install/run it. No signing certificate, provisioning profile, panel key or other secret is needed by the build workflow.

## Validation scope

Automated tests cover API URL normalization and query encoding, authentication headers, plain-text file writes, pagination, HTTP errors/HTML protection pages, JSON mapping, allocations, console cleaning, Keychain persistence, player parsing/provider shapes, native database/property mapping, isolated favorites persistence, and one focused demo UI tour through console width, files, backups, Suite connection, live player values and alt-account evidence. Simulator tests use local fixtures. Actual provider connectivity and destructive server actions require validation against your own account; a green build does not prove those integrations work on every modified provider panel.

API contracts are based on the upstream [Client API routes](https://github.com/pterodactyl/panel/blob/1.0-develop/routes/api-client.php). Credential-bearing panel requests only follow same-origin HTTPS redirects. Signed Wings upload/download URLs and short-lived WebSocket tokens are used without forwarding the Client API key. No analytics or app backend.

## 1.9.0 — CubeCoders AMP and Minecraft mods

Use **Panel hinzufügen → CubeCoders AMP** with an HTTPS controller or direct-instance URL, AMP username, password and optional current 2FA code. API documentation is available at your own panel's `/API` URL. Accounts need access to each instance and the corresponding console, power, file, configuration and backup permissions. Remember-me tokens replace passwords where AMP allows it; secrets stay in the iOS Keychain. The UI retains the existing panel picker and all Pterodactyl features. Missing provider fields in previously stored profiles decode as Pterodactyl without changing profile UUIDs. Update over the existing installation with the same bundle/signing identity; do not uninstall first.

AMP includes controller discovery, direct instances, per-instance sessions through the ADS proxy, start/stop/restart/kill for applications, instance service controls, resource graphs, bounded console polling, file uploads/downloads/editing/rename/move/trash/extraction, module settings, tasks and local backups/restore/protection. Settings/backups are discovered from the running AMP API specification. Unsupported Pterodactyl account/network/database screens are replaced with AMP-specific screens and a web-panel link. Admin Suite remains on the original provider. AMP widgets can show values received by the app; background AMP widget login is not implemented. HTTPS redirects carrying AMP request bodies are refused. Session renewal only retries explicit expired-session responses, never ambiguous network failures.

**Mods:** `/mods` lists files and metadata can be read on demand. Import local JARs (up to 128 MiB) to install or select an installed JAR and upload an update. Supported metadata: `fabric.mod.json`, `quilt.mod.json`, Forge `META-INF/mods.toml`, NeoForge `META-INF/neoforge.mods.toml`, and legacy Forge `mcmod.info`. Mod IDs and loader must match for an update; same versions, numeric downgrades, client-only Fabric/Quilt jars and duplicate installed mod IDs are rejected. Minecraft ranges are displayed for review; complete runtime, dependency and Java compatibility is not inferred. Unknown/ambiguous metadata uses the general file manager. This release installs user-selected files; it does not automatically fetch marketplace updates.

Mod installs require an offline game server. JARs are uploaded outside `/mods` to `simplex-mod-staging/<transaction>` and read back to verify SHA-256. Updates move the old JAR to `simplex-mod-backups/<transaction>` before moving the verified candidate into place; failure attempts to restore the old JAR. Configuration/world data are left alone. For manual rollback, stop the server, move the new JAR out of `/mods`, and move the backed-up JAR back. Back up worlds separately before changes. An external administrator starting/changing the server during these separate API operations cannot be locked out by a client app.

Validation: focused mocked AMP transport/session, old-profile decoding and mod-format/identity tests, plus the repository's existing simulator smoke checks and unsigned arm64 device build. No credentials for a live AMP installation were provided, so CI cannot certify a particular host's AMP permissions, TLS/reverse proxy setup or modpack compatibility.

Protocol sources: [CubeCoders official AMP API client](https://github.com/CubeCoders/ampapi-node), [AMP documentation](https://github.com/CubeCoders/AMP/wiki), and AMP's generated `GetAPISpec` signatures (also published in [ampapi](https://github.com/p0t4t0sandwich/ampapi)). Backup branch: `backup/v1.8.0-before-amp`.

## 2.0.0 — Widgets, AMP telemetry, Python runners and appearance

AMP uptime now accepts day-prefixed formats (`d:hh:mm:ss` and .NET `d.hh:mm:ss.fff`). Resource updates merge partial metric dictionaries, reconcile against `Core/GetStatus` every five seconds, and poll foreground console updates once per second without overlapping requests. Missing metrics remain unavailable instead of becoming artificial zeroes. RAM accepts metric aliases and units and exposes the reported limit.

Widgets retain the existing `PanelServerStatus` identity and add a direct fleet overview, individual RAM/CPU/uptime widgets, a large online-player list, interactive refresh and a manually copied snapshot widget. Enable a server under Settings → Server-Widgets, or enable the whole panel. AMP refresh uses separate controller/instance sessions and `GetStatus`/`GetUserList`. Coupled Simplex players are read through the existing Suite data API, respecting the configured certificate pin. No widget sends console/power commands. A large player widget displays up to 42 names; overflow is shown explicitly. iOS controls background update scheduling; tapping Refresh requests new data, and displayed timestamps distinguish freshness.

App Group discovery handles provisioning data and signed Mach-O entitlement blobs. Snapshots are stored in the shared file container and shared defaults, with a shared Keychain fallback. Credential files use first-unlock file protection in the entitled group, or shared Keychain when available. If signing strips both sharing entitlements, there is no automatic app/extension channel; the app exposes diagnosis and a manual snapshot code without credentials. Neither signing permissions nor arbitrary shared containers are fabricated. App and widget must be signed with the same configured App Group (canonical `group.eu.simplexsmp.panel`) or working common Keychain group. Keep the widget extension when signing.

The app root includes Python App Runner and other AMP applications. Files, console, service controls, backups and module-provided settings are reachable directly. AMP settings support booleans, enumerations, multiline values and read-only flags; absent current values are queried from `GetConfigs`.

Appearance settings add primary/secondary color pickers with contrast correction for accent text, while normal text stays system adaptive. Three original generated app icons are bundled and selectable: Simplex Cyan, Server Rack and Amethyst. Assets are under `Panel/Assets.xcassets/{AppIcon,ServerIcon,AmethystIcon}.appiconset` with preview image sets. Built-in image generation was used; production prompts: a bold cyan crystalline S with a three-server center on midnight violet; a titanium three-tier server rack with a cyan S shield; a violet geometric S with cyan terminal prompt. Images were resized to RGB 1024px icons for packaging.

`Robin9094707/Therapie` was inspected read-only as a widget-storage reference and was not modified. Backup: `backup/v1.9.0-before-widgets-metrics`. Existing accounts, app data and bundle identifiers are retained. Verification covers telemetry formats/partial updates, snapshot compatibility, color contrast, existing AMP/login and file behavior, simulator smoke navigation and the unsigned arm64 IPA build. A real re-signed-device entitlement check and live AMP host verification require the user's installation.
