# Simplex Panel 2.7.0 / Admin Suite 9.1.2

## Install without resetting data

Keep the complete SimplexAdminSuite data folder, all databases, worlds, API keys, TLS pin and app accounts. Replace only the old Suite JAR during a planned restart; do not install two Suite JARs simultaneously. Install the unsigned IPA over the existing app with the same signing identity and bundle ID `eu.simplexsmp.panel`. Do not uninstall the app. Existing profile/config schemas are retained; the new state is additive.

## Exclude infrastructure IPs

`82.165.45.159` is excluded by default on the first installation of this policy. The exact IP is retained for display but cannot create Alt evidence, inherited rights, Suite identity/IP bans or Ultra group IP anchors. Add several IPv4/IPv6 addresses through Alt-Account-Dashboard → IP-Ausschlüsse verwalten, from a player's network section, or through the Java/Bedrock Alt dashboard. Only IP literals are accepted; canonical IPv6 aliases match. No DNS or broad network ranges.

The exclusion list lives in `alt-detection/ip-exclusions.properties`. Explicitly releasing the default IP survives restarts. Use the menus/app for immediate apply; manual file edits require a plugin/server restart. App changes require a full key, preview, one-use confirmation and unchanged policy revision. Native changes require `simplexadminsuite.alts.link`.

## Repair existing false links

The first update and later exclusion changes back up the relevant profile/state files in `alt-detection/ip-repair-backups/<timestamp-id>/`. The repair rebuilds the graph without ignored IPs, drops unsupported derived Ultra members/UUID/device anchors and derived identity bans, removes excluded explicit Suite IP bans and persists the corrected files. Other shared IPs, devices, authenticated/manual links and explicit Ultra roots remain effective. It reconciles recorded rights inheritance and invalidates stale Alt caches.

Ordinary observations are preserved so excluding an IP is reversible. “Gespeicherte IP-Beobachtungen löschen” explicitly purges matching IP history/current-IP fields in profiles and devices, after backup/repair, and keeps the exclusion active. A later connection may display the IP again; it remains excluded from Alt evidence. Audit/evasion logs and external Minecraft/LiteBans bans are not erased.

Independent account bans are never blanket-unbanned. Historical rights copies made by old AdminPowerTools versions lack an original-state record: stale policy memberships are detached, but those copied rights cannot be safely guessed away. The repair report/action history flags them for manual review. New rights copies preserve before/applied snapshots and can be conditionally restored while retaining later independent edits. Incomplete repairs stay pending and exclusions remain active; backups are retained. Unknown legacy anchors without provable ownership are preserved for manual review.

## Dashboard and stable player lists

The Alt dashboard shows profiles with links, connected groups, additional accounts (sum of group size minus one), largest group, direct evidence pairs and stored device records. Ranked profiles link to full player/device/Alt details. These metrics count accounts and signals, not confirmed people.

The app loads a server-scoped authenticated player snapshot once per process. Search and Java/Bedrock/online/recent filters use the snapshot locally; returning from a player profile does not reorder/refetch it. “Neu laden” and pull-to-refresh fetch a replacement snapshot. Failed refreshes preserve the previous rows. Closing/starting the process clears the in-memory cache. Pagination is completed before publishing the snapshot.

## Verification

GitHub Actions builds against pinned real Paper dependencies, runs existing data/authentication/SQLite/MariaDB/NBT/upload regression checks plus Release912Harness and iOS snapshot tests, runs the Alt dashboard/IP UI smoke test, and packages an arm64 unsigned IPA, JAR, full plugin source and checksums. Live Geyser forwarding and the user's signed physical phone still need deployment verification. This policy prevents using an infrastructure IP as identity evidence; it does not change Geyser's IP forwarding configuration.
