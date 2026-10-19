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

## Build and unsigned IPA

The `Build and test unsigned iOS app` GitHub Actions workflow generates the Xcode project using XcodeGen, runs XCTest and XCUITest on an iPhone simulator, builds the ARM64 Release app with code signing disabled, verifies its executable and packages `Payload/Panel.app` as an unsigned IPA. Artifacts include the IPA, SHA-256 and simulator test results/screenshots. Push to main or use **Run workflow**.

For local builds on a Mac with Xcode 26+:

```sh
brew install xcodegen
xcodegen generate
open Panel.xcodeproj
```

An unsigned IPA must be signed using your own Apple signing method before iOS can install/run it. No signing certificate, provisioning profile, panel key or other secret is needed by the build workflow.

## Validation scope

Automated tests cover API URL normalization and query encoding, authentication headers, plain-text file writes, pagination, HTTP errors/HTML protection pages, JSON mapping, allocations, console cleaning, Keychain persistence, and demo navigation through the main screens. Simulator tests use local fixtures. Actual provider connectivity and destructive server actions require validation against your own account; a green build does not prove those integrations work on every modified provider panel.

API contracts are based on the upstream [Client API routes](https://github.com/pterodactyl/panel/blob/1.0-develop/routes/api-client.php). Credential-bearing panel requests only follow same-origin HTTPS redirects. Signed Wings upload/download URLs and short-lived WebSocket tokens are used without forwarding the Client API key. No analytics or app backend.
