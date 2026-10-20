# SimplexNameBridge 1.0.0

Optional Geyser extension for **Geyser-Spigot in the same Paper process** as AdminSuite 7.17+. Place `SimplexNameBridge-1.0.0.jar` in `plugins/Geyser-Spigot/extensions/`, then restart. Do not put it directly in Paper's `plugins/` folder.

It appends ` [AFK]` to Bedrock's pause/player-list names, without LuckPerms prefix/suffix. The default uses real account names; display nicknames can be enabled via `integrations.geyser-name-bridge.show-nicknames`. It never changes entity usernames, account profiles, UUIDs, authentication, XUIDs or skins. Sentinel has no dependency, registration channel or lifecycle coupling with this extension.

Snapshots are taken on Bukkit's main thread. Packet work is scheduled on each Geyser session's event loop. Only entities already cached **and listed** by Geyser are updated. Disconnects and vanished/removed entities are not recreated. Cached skins and original Bedrock list UUIDs are retained by Geyser's own entry builder. Changes are sent within about one second, with a five-second refresh to recover from later skin/list packets.

This requires a small **internal Geyser adapter**: the public Geyser API does not expose replacement of list-entry names. The adapter was compiled and checked against genuine Geyser 2.11.3 build 1251. It logs unsupported adapters and leaves account/entity names untouched. User-visible Bedrock behavior must still be checked on the actual installed Geyser build and client. Standalone, Velocity and Bungee setups require an additional transport and are not supported by this same-process bridge.

## Build

From the full AdminSuite source project:

```bash
python3 tools/build-real.py --offline
python3 geyser-name-bridge/build.py --offline
```

The source ZIP includes a hash-verified API cache extracted from the official Geyser JAR. Those are genuine API classes, not generated stubs. No Geyser/API/Paper classes are bundled into the extension JAR. Maven is also provided for an online API build, while the Python path pins the release bytecode.
