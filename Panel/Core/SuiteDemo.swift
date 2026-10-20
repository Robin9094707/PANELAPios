import Foundation

/// Synthetic read-only fixture for the existing demo mode and UI smoke tour.
enum SuiteDemo {
    static let player = "00000000-0000-0000-0000-000000000001"
    static func field(_ name: String, _ label: String) -> JSON { .object(["name": .string(name), "description": .string(label), "type": .number(3), "required": .bool(true)]) }
    static func response(_ path: String, query: [String: String]) -> JSON {
        switch path {
        case "health": return .object(["apiVersion": .number(1), "serverId": .string("synthetic-suite"), "version": .string("8.3.0"), "scope": .string("full")])
        case "schema": return .object(["actions": .array([
            .object(["id": .string("player.punish"), "description": .string("Strafen und Warnungen"), "group": .string("Spieler"), "options": .array([field("player", "Spieler"), .object(["name": .string("type"), "description": .string("Strafaktion"), "required": .bool(true), "type": .number(3), "choices": .array([.object(["name": .string("Bannen"), "value": .string("ban")]), .object(["name": .string("Entbannen"), "value": .string("unban")])])])])]),
            .object(["id": .string("security.ultraban"), "description": .string("Ultrabann ausführen"), "group": .string("Sicherheit"), "options": .array([field("player", "Spieler")])]),
            .object(["id": .string("security.ultraunban"), "description": .string("Ultrabann-Gruppe freigeben"), "group": .string("Sicherheit"), "options": .array([field("target", "Spieler oder Gruppen-ID")])]),
            .object(["id": .string("server.restart"), "description": .string("Neustart verwalten"), "group": .string("Server"), "options": .array([field("action", "Aktion"), field("seconds", "Countdown in Sekunden")])])
        ])])
        case "data/players":
            let rows = [profile(detail: false), otherProfile(online: true), otherProfile(online: false)].filter { (query["q"] ?? "").isEmpty || $0["name"].string.localizedCaseInsensitiveContains(query["q"] ?? "") || $0["id"].string.contains(query["q"] ?? "") }
            return .object(["items": .array(rows), "total": .number(Double(rows.count)), "next": .number(-1)])
        case "data/profile":
            if query["player"] == "offline-demo" { return otherProfile(online: false) }
            if query["player"] == "bedrock-demo" { return otherProfile(online: true) }
            return profile(detail: true)
        case "data/alts": return .object(["items": .array([.object(["id": .string("00000000-0000-0000-0000-000000000002"), "name": .string(".BedrockDemo"), "score": .number(90), "level": .string("Starker Hinweis"), "safe": .bool(true), "evidence": .array([.string("Synthetische bestätigte Kontoverknüpfung")])])])])
        case "data/devices": return .object(["items": .array([.object(["id": .string("synthetic-device"), "name": .string("iPhone · Demo"), "platform": .string("BEDROCK"), "os": .string("iOS"), "version": .string("26.3"), "language": .string("de_DE"), "input": .string("Touch"), "blocked": .bool(false)])])])
        case "data/ultrabans": return .object(["items": .array([.object(["id": .string("UB-DEMO"), "name": .string("DemoAccount"), "active": .bool(true), "mode": .string("ultra"), "reason": .string("Synthetischer Testfall"), "attempts": .number(3), "members": .array([.object(["id": .string(player), "name": .string("DemoAccount"), "relation": .string("Ausgangskonto")])])])]), "total": .number(1), "next": .number(-1)])
        case "data/snapshots", "data/nbt-paths", "data/inventory", "data/settings": return .object(["items": .array([])])
        case "view": return .object(["ok": .bool(true), "title": .string("Demo-Suite"), "description": .string("Synthetische Daten. Reale Serveraktionen sind im Demomodus gesperrt.")])
        case "menu": return .object(["session": .string("demo"), "revision": .number(1), "title": .string("Admin-Zentrale"), "notice": .string("Nur Demo · keine Serveränderungen"), "options": .array([.object(["id": .string("0"), "name": .string("Spieler"), "detail": .string("Profile und Spielerdaten"), "disabled": .bool(true)])])])
        default: return .null
        }
    }
    static func otherProfile(online: Bool) -> JSON {
        .object(["id": .string(online ? "bedrock-demo" : "offline-demo"), "name": .string(online ? ".BedrockDemo" : "RecentOffline"), "online": .bool(online), "client": .string("BEDROCK"), "device": .string(online ? "iPhone · Demo" : "Samsung · Demo"), "version": .string("26.3"), "lastSeen": .number(Date().addingTimeInterval(-300).timeIntervalSince1970 * 1000)])
    }
    static func profile(detail: Bool) -> JSON {
        var row: [String: JSON] = ["id": .string(player), "name": .string("TadMC_YT"), "online": .bool(true), "client": .string("JAVA"), "device": .string("Java-Client"), "points": .number(2400), "warnings": .number(0)]
        if detail { row["live"] = .object(["health": .number(18), "maxHealth": .number(20), "food": .number(19), "saturation": .number(4.5), "level": .number(42), "gamemode": .string("survival"), "world": .string("world"), "x": .number(125), "y": .number(64), "z": .number(-212)]) }
        return .object(row)
    }
}
