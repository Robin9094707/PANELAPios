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
- Files and folders: browse/search, UTF-8 edit (up to 2 MiB), create, upload from Files, download/share, copy, rename/move, compress/decompress, Unix permissions, bulk delete/archive, server-side URL pulls
- Backup create with exclusions/lock, status polling, download/share, restore with optional truncation, lock/unlock/delete
- Cron schedules and command/power/backup tasks: create, edit, execute and delete
- Databases, password display/rotation, network allocations and primary port, subusers and permissions
- Startup variables, Docker image, rename/description, reinstall, SFTP details and activity
- Account email/password, API keys, public SSH keys, 2FA setup and recovery codes, account activity
- Device-only Keychain credential storage, optional Face ID/device-code app lock, background privacy cover
- System/light/dark appearance, Dynamic Type, native navigation and actual Liquid Glass controls
- Clearly labelled demo workspace. Mutations/downloads are blocked in demo; no live credentials are bundled.

The app is currently German. API error details may use the provider's language. Advanced rights and permission names follow Pterodactyl naming. Hosting billing, node administration and changing provider-assigned RAM/CPU limits are outside the Client API.

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

The `Build and test unsigned iOS app` GitHub Actions workflow generates the Xcode project using XcodeGen, runs XCTest and XCUITest on an iPhone simulator, builds the ARM64 Release app with code signing disabled, verifies its executable and packages `Payload/Panel.app` as an unsigned IPA. Artifacts include the IPA, SHA-256 and simulator test results/screenshots. Push to main or use **Run workflow**. For packaging/documentation-only changes, the workflow can reuse an authenticated successful simulator test step from commit `0d704657e54866a0aaf856d89b721b8626f9635c`, but only after verifying that all app source, assets, tests, project settings and entitlements are byte-identical. Any change to those inputs runs the unit suite and the selected UI smoke tour. The artifact includes the validation proof.

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
