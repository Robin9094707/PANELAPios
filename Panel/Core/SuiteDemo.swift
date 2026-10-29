import Foundation

/// Synthetic read-only fixture for the existing demo mode and UI smoke tour.
enum SuiteDemo {
    static let consoleFilterLines = [
        "[12:00:00 INFO]: <DemoGuest> PLAYER_CHAT_ONLY joined the game ERROR",
        "[12:00:01 ERROR]: DEMO_ERROR_ONLY",
        "    at demo.Plugin.run(Plugin.java:42)",
        "Caused by: java.lang.IllegalStateException: synthetic failure",
        "[12:00:02 WARN]: DEMO_WARNING_ONLY",
        "[12:00:03 INFO]: DemoGuest joined the game",
        "[12:00:04 INFO]: DemoGuest left the game",
        "[12:00:05 INFO]: DemoGuest issued server command: /list"
    ]
    static let operations: [String: JSON] = {
        let source = #"""
        {"data/overview":{"tps":20,"mspt":8.5,"ramUsed":1073741824,"ramMax":4294967296,"uptime":7200000,"online":2,"maxPlayers":60,"profiles":3,"at":1793188800000,"scoreboard":{"mainDays":1234,"secondDays":567,"mainWorld":"world","secondWorld":"survival2"}},"data/statistics":{"profiles":3,"playMillis":36000000,"afkMillis":3600000,"sessions":42,"recent24h":3,"new24h":1,"bans":0,"mutes":0,"warnings":1},"data/log-status":{"pending":2,"observations":8,"bytes":2048,"enabledChannels":1,"discord":"Demo: Versand erfolgreich","deliveryState":"Synthetische Protokolle"},"data/worlds":{"items":[{"id":"demo-world","name":"Demo-Welt","environment":"NORMAL","players":2,"chunks":48,"days":20000,"difficulty":"NORMAL","pvp":true,"storm":false}]},"data/plugins":{"items":[{"id":"SimplexAdminSuite","name":"SimplexAdminSuite","version":"8.4.0","enabled":true}]}}
        """#
        guard let value = try? JSONDecoder().decode([String: JSON].self, from: Data(source.utf8)) else { return [:] }
        return value
    }()
    static let player = "00000000-0000-0000-0000-000000000001"
    static func field(_ name: String, _ label: String) -> JSON { .object(["name": .string(name), "description": .string(label), "type": .number(3), "required": .bool(true)]) }
    static func response(_ path: String, query: [String: String]) -> JSON {
        if let value = operations[path] { return value }
        switch path {
        case "health": return .object(["apiVersion": .number(1), "serverId": .string("synthetic-suite"), "version": .string("9.3.0"), "features": .array([.string("overview"),.string("admin-roles-v3"),.string("public-chat-v1"),.string("restart-v1"),.string("admin-dashboard-v1"),.string("players-filters-v3"),.string("profile-v3")]), "scope": .string("full")])
        case "data/operations": return .object(["active":.bool(false),"remaining":.number(0)])
        case "data/admin-dashboard": return adminDashboard
        case "data/chat": return .object(["items":.array([]),"cursor":.string("0")])
        case "data/management":
            if query["section"] == "status" { return .object(["maintenance":.bool(false),"discord":.string("simplex")]) }
            if query["section"] == "admins" { return .object(["items":.array([.object(["id":.string(player),"name":.string("TadMC_YT"),"role":.string("Junior-Admin · nur kicken"),"effective":.bool(true)])])]) }
            if query["section"] == "roles" { return .object(["items":.array(roleTemplates),"permissions":.array(adminRights)]) }
            return .object(["items":.array(adminRights),"role":.string("JUNIOR_ADMIN"),"roles":.array(roleTemplates),"maxBanSeconds":.number(-1),"altBans":.bool(false)])
        case "schema": return .object(["actions": .array([
            .object(["id": .string("player.punish"), "description": .string("Strafen und Warnungen"), "group": .string("Spieler"), "options": .array([field("player", "Spieler"), .object(["name": .string("type"), "description": .string("Strafaktion"), "required": .bool(true), "type": .number(3), "choices": .array([.object(["name": .string("Bannen"), "value": .string("ban")]), .object(["name": .string("Entbannen"), "value": .string("unban")])])])])]),
            .object(["id": .string("player.message"), "description": .string("Private Nachricht senden"), "group": .string("Spieler"), "options": .array([field("player", "Spieler"), field("message", "Nachricht")])]),
            .object(["id": .string("security.ultraban"), "description": .string("Ultrabann ausführen"), "group": .string("Sicherheit"), "options": .array([field("player", "Spieler")])]),
            .object(["id": .string("security.ultraunban"), "description": .string("Ultrabann-Gruppe freigeben"), "group": .string("Sicherheit"), "options": .array([field("target", "Spieler oder Gruppen-ID")])]),
            .object(["id": .string("server.restart"), "description": .string("Neustart verwalten"), "group": .string("Server"), "options": .array([field("action", "Aktion"), field("seconds", "Countdown in Sekunden")])])
        ])])
        case "data/alt-dashboard": return .object(["linkedProfiles": .number(2), "extraAccounts": .number(1), "groups": .number(1), "largestGroup": .number(2), "directPairs": .number(1), "devices": .number(3), "items": .array([.object(["id": .string(player), "name": .string("TadMC_YT"), "client": .string("JAVA"), "linked": .number(1), "devices": .number(1)])]), "next": .number(-1), "revision": .string("synthetic-ip-policy"), "excluded": .array([.string("82.165.45.159")])])
        case "data/alt-ips": return .object(["items": .array([.object(["id": .string("82.165.45.159"), "ip": .string("82.165.45.159"), "profiles": .number(3), "excluded": .bool(true)])]), "next": .number(-1), "revision": .string("synthetic-ip-policy")])
        case "data/players":
            let rows = [profile(detail: false), otherProfile(online: true), otherProfile(online: false)].filter { (query["filter"] != "online" || $0["online"].bool) && (query["filter"] != "recent" || !$0["online"].bool) && (query["filter"] != "flagged" || $0["ban"].bool || $0["mute"].bool || $0["warnings"].int > 0) && (query["filter"] != "new" || $0["firstSeen"].double >= Date().addingTimeInterval(-7 * 86400).timeIntervalSince1970 * 1000) }.filter { (query["q"] ?? "").isEmpty || $0["name"].string.localizedCaseInsensitiveContains(query["q"] ?? "") || $0["id"].string.contains(query["q"] ?? "") }
            return .object(["items": .array(rows), "total": .number(Double(rows.count)), "next": .number(-1)])
        case "data/profile":
            if query["player"] == "offline-demo" { return otherProfile(online: false) }
            if query["player"] == "bedrock-demo" { return otherProfile(online: true) }
            return profile(detail: true)
        case "data/alts": return .object(["items": .array([.object(["id": .string("00000000-0000-0000-0000-000000000002"), "name": .string(".BedrockDemo"), "score": .number(90), "level": .string("Starker Hinweis"), "safe": .bool(true), "evidence": .array([.string("Synthetische bestätigte Kontoverknüpfung")])])])])
        case "data/devices":
            if query["player"] == "offline-demo" { return .object(["items": .array([.object(["id": .string("synthetic-android-device"), "name": .string("Samsung · Demo"), "platform": .string("BEDROCK"), "os": .string("Android"), "language": .string("de_DE"), "input": .string("Touch"), "lastSeen": .number(Date().addingTimeInterval(-300).timeIntervalSince1970 * 1000)])])]) }
            return .object(["items": .array([.object(["id": .string("synthetic-device"), "name": .string("iPhone · Demo"), "platform": .string("BEDROCK"), "os": .string("iOS"), "version": .string("26.3"), "language": .string("de_DE"), "input": .string("Touch"), "blocked": .bool(false)])])])
        case "data/ultrabans": return .object(["items": .array([.object(["id": .string("UB-DEMO"), "name": .string("DemoAccount"), "active": .bool(true), "mode": .string("ultra"), "reason": .string("Synthetischer Testfall"), "attempts": .number(3), "members": .array([.object(["id": .string(player), "name": .string("DemoAccount"), "relation": .string("Ausgangskonto")])])])]), "total": .number(1), "next": .number(-1)])
        case "data/discord-channels": return .object(["items": .array([.object(["id": .string("123456789012345678"), "name": .string("server-chat")])])])
        case "data/settings": return .object(["items": .array([
            .object(["id": .string("discord-bot.relay-channel-id"), "name": .string("Chat-Kanal"), "kind": .string("string"), "value": .string("123456789012345678"), "revision": .string("synthetic-revision")]),
            .object(["id": .string("discord-bot.relay.enabled"), "name": .string("Chat-Relay aktiv"), "kind": .string("bool"), "value": .bool(true), "revision": .string("synthetic-revision")])
        ])])
        case "data/snapshots", "data/nbt-paths", "data/inventory": return .object(["items": .array([])])
        case "view": return .object(["ok": .bool(true), "title": .string("Demo-Suite"), "description": .string("Synthetische Daten. Reale Serveraktionen sind im Demomodus gesperrt.")])
        case "menu": return .object(["session": .string("demo"), "revision": .number(1), "title": .string("Admin-Zentrale"), "notice": .string("Nur Demo · keine Serveränderungen"), "options": .array([.object(["id": .string("0"), "name": .string("Spieler"), "detail": .string("Profile und Spielerdaten"), "disabled": .bool(true)])])])
        default: return .null
        }
    }
    static var roleTemplates: [JSON] { [
        .object(["id":.string("JUNIOR_ADMIN"),"name":.string("Junior-Admin · nur kicken"),"maxBanSeconds":.number(-1)]),
        .object(["id":.string("TEMP_MODERATOR"),"name":.string("Zeitbann-Moderator · maximal 3 Tage"),"maxBanSeconds":.number(259200)]),
        .object(["id":.string("FULL"),"name":.string("Volladministrator"),"maxBanSeconds":.number(0)])
    ] }
    static var adminRights: [JSON] { [
        .object(["id":.string("simplexadminsuite.action.kick"),"name":.string("Spieler kicken"),"description":.string("Erlaubt das Entfernen eines Spielers vom Server."),"group":.string("Moderation"),"allowed":.bool(true),"override":.string("inherit")]),
        .object(["id":.string("simplexadminsuite.action.tempban"),"name":.string("Zeitweise bannen"),"description":.string("Erlaubt Zeitbanns innerhalb des Rollenlimits."),"group":.string("Moderation"),"allowed":.bool(false),"override":.string("inherit")])
    ] }
    static func otherProfile(online: Bool) -> JSON {
        .object(["id": .string(online ? "bedrock-demo" : "offline-demo"), "name": .string(online ? ".BedrockDemo" : "RecentOffline"), "online": .bool(online), "client": .string("BEDROCK"), "device": .string(online ? "iPhone · Demo" : "Samsung · Demo"), "version": .string("26.3"), "warnings": .number(online ? 1 : 0), "firstSeen": .number(Date().addingTimeInterval(online ? -40 * 86400 : -2 * 86400).timeIntervalSince1970 * 1000), "lastSeen": .number(Date().addingTimeInterval(-300).timeIntervalSince1970 * 1000)])
    }
    static var adminDashboard: JSON {
        let now = Date().timeIntervalSince1970 * 1000
        return .object(["at": .number(now), "version": .string("9.3.0"), "tps": .number(19.8), "mspt": .number(8.5), "ramUsed": .number(1_610_612_736), "ramMax": .number(4_294_967_296), "uptime": .number(7_200_000),
            "online": .number(2), "maxPlayers": .number(60), "profiles": .number(3), "new24h": .number(0), "newWeek": .number(1), "active24h": .number(3),
            "moderation": .object(["openReports": .number(1), "reviewReports": .number(0), "internalBans": .number(0), "mutes": .number(0), "warnedPlayers": .number(1), "ultrabans": .number(1)]),
            "maintenance": .object(["active": .bool(false), "reason": .string("")]), "restart": .object(["active": .bool(false), "remaining": .number(0)]),
            "players": .array([profile(detail: false), otherProfile(online: true)]),
            "staff": .array([.object(["id": .string(player), "name": .string("TadMC_YT"), "role": .string("Junior-Admin · nur kicken"), "operator": .bool(false)])]),
            "reports": .array([.object(["id": .string("7"), "name": .string(".BedrockDemo"), "player": .string("bedrock-demo"), "category": .string("Chat"), "status": .string("OPEN"), "created": .number(now - 600_000)])]),
            "events": .array([.object(["id": .string("demo-event"), "title": .string("Neuer Spieler"), "body": .string("RecentOffline ist zum ersten Mal beigetreten."), "critical": .bool(false), "time": .number(now - 120_000)])])])
    }
    static func profile(detail: Bool) -> JSON {
        var row: [String: JSON] = ["id": .string(player), "name": .string("TadMC_YT"), "online": .bool(true), "client": .string("JAVA"), "device": .string("Java-Client"), "points": .number(2400), "warnings": .number(0)]
        if detail { row["staff"] = .string("Junior-Admin · nur kicken"); row["activity"] = .object(["playMillis": .number(183_600_000), "afkMillis": .number(3_600_000), "sessions": .number(42), "devices": .number(1)]); row["previousNames"] = .array([.string("TadMC")]);
            row["vpnWhitelisted"] = .bool(true); row["logoutLocation"] = .object(["world": .string("world"), "x": .number(12), "y": .number(64), "z": .number(-8)]); row["live"] = .object(["health": .number(18), "maxHealth": .number(20), "food": .number(19), "saturation": .number(4.5), "level": .number(42), "gamemode": .string("survival"), "world": .string("world"), "x": .number(125), "y": .number(64), "z": .number(-212)]) }
        return .object(row)
    }
}
