# Simplex Panel 3.3.0 (Build 27) / Admin Suite 9.6.0

Additive update: keep the existing plugin directory. Existing API keys, TLS certificate/server identity, Discord chat verifications, tickets, Bark subscriptions, player data and local app connections remain usable. No reset or migration command is required.

## App users

The existing `full` key remains the owner/bootstrap connection. In **Admin → Zugänge & Serverregeln → App-Benutzer**, create a Minecraft-style username and a password (12–256 characters), then select individual API/read/ticket rights. `*` grants full access, including account administration. Grant broad tools such as the remote menu/console only to trusted administrators; those tools intentionally control many suite operations.

Users can choose **Mit Admin-Suite-Benutzer anmelden** on the start screen and enter the server HTTPS address (IP and API port), certificate fingerprint, username and password. No hosting-panel login is required. Existing attached Suite connections also offer password login as an alternative to a key. Passwords are not stored on the phone; a revocable 30-day session is kept in Keychain. Password/rights changes and disabling/deleting a user revoke all their sessions. Each permitted user has a separate Bark subscription, shared across their sessions.

Optional local bootstrap: `/sas app user <name> <password> [comma-separated-rights or *]`. Prefer the app for password entry, since server consoles/command logging plugins may retain command text. There is no default password or automatic administrator account.

## Discord notifications

Set a guild text channel in **Discord-Beitrittshinweise**. Verified users can use:

- `/notify add player:<name> mode:once|permanent`
- `/notify list`
- `/notify remove id:<ID>`

`/link` verification from the existing Minecraft chat bridge is reused. Each Discord user may have ten active entries (including pending consent); one per target. The bot pings only that user in the saved channel. Replies follow Discord interaction locale (German when `de`, English otherwise); player messages follow the existing player locale.

One-time entries are consumed after successful Discord delivery. Permanent requests send the initial notice, then wait for the player's decision. The three-day clock starts at that delivery, never while waiting for the first join. No repeated permanent notice is sent while consent is pending. After three days consent is automatically granted and the player receives a durable notice. `/joinnotify` opens native Bedrock forms or a Java inventory with accept/decline/remove and a persistent global on/off switch. Disabling clears pending and active subscriptions. The joining player sees the Discord usernames that were actually notified.

## Verifications

The app lists the existing `discord-links.properties` store and can create, replace or remove links after confirmation. Discord membership/bot validation and one-to-one constraints apply. Replacements compare the expected previous Discord ID; concurrent changes require a reload. Old subscriptions stop working when the subscribing account is no longer linked to the original Minecraft identity.

## RAM emergency restart

**RAM-Notfallneustart** reads bounded Linux cgroup v2/v1 container usage and limit; Java heap is shown separately. No substitution with host-wide memory or heap. If container metrics are unavailable or unbounded, enabling is refused. The configured decimal GB threshold must be inside the container limit. Usage must stay high for the entire hold period; missing samples reset it. The existing localized restart countdown is used. The hosting panel must restart the process after shutdown. Container accounting can differ slightly from hosting-panel cache-adjusted figures; this is not an authenticated Pterodactyl API measurement.

## Storage and permissions

New files: `app-api/users.properties` and `online-watches.properties`; new optional `memory-restart.*` and `online-notifications.channel-id` config keys. Passwords use PBKDF2-HMAC-SHA256, 600,000 iterations and individual random salts. Session tokens are stored only as SHA-256 hashes. Login attempts are bounded per IP/username; sensitive files use owner-only permissions where supported. Someone who controls the server OS account or can overwrite plugin code/config files cannot be prevented by this plugin from changing them. Restrict hosting file access for untrusted users.

Native Paper text-input dialogs replace supported Java text prompts only. Bedrock keeps its native input forms; information screens and normal inventory action menus keep their current style. Dialog callbacks bind to the opening player, expire, and recheck staff access before applying input.

Pull requests build JAR and unsigned IPA as Actions artifacts. They do not publish a release or modify `main`.
