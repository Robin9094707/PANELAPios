import Foundation
import SwiftUI

/// "gerade eben", "vor 5 Min.", "vor 3 Std.", "gestern, 20:15", "vor 4 Tagen" – short, German and stable for tests.
enum SuiteRelativeTime {
    static func text(_ date: Date, now: Date = Date(), calendar: Calendar = .berlin) -> String {
        let seconds = now.timeIntervalSince(date)
        if seconds < 0 { return clock(date, calendar: calendar) }
        if seconds < 60 { return "gerade eben" }
        if seconds < 3600 { return "vor \(Int(seconds / 60)) Min." }
        if calendar.isDate(date, inSameDayAs: now) { return "vor \(Int(seconds / 3600)) Std." }
        if let yesterday = calendar.date(byAdding: .day, value: -1, to: now), calendar.isDate(date, inSameDayAs: yesterday) { return "gestern, " + clock(date, calendar: calendar) }
        let days = max(2, calendar.dateComponents([.day], from: calendar.startOfDay(for: date), to: calendar.startOfDay(for: now)).day ?? 2)
        return days < 7 ? "vor \(days) Tagen" : absolute(date, calendar: calendar)
    }
    static func clock(_ date: Date, calendar: Calendar = .berlin) -> String {
        let format = DateFormatter(); format.locale = Locale(identifier: "de_DE"); format.timeZone = calendar.timeZone; format.dateFormat = "HH:mm"
        return format.string(from: date)
    }
    static func absolute(_ date: Date, calendar: Calendar = .berlin) -> String {
        let format = DateFormatter(); format.locale = Locale(identifier: "de_DE"); format.timeZone = calendar.timeZone; format.dateFormat = "dd.MM.yyyy, HH:mm:ss"
        return format.string(from: date)
    }
    /// Section title for a day: "Heute", "Gestern" or "Mo., 03.11.2026".
    static func day(_ date: Date, now: Date = Date(), calendar: Calendar = .berlin) -> String {
        if calendar.isDate(date, inSameDayAs: now) { return "Heute" }
        if let yesterday = calendar.date(byAdding: .day, value: -1, to: now), calendar.isDate(date, inSameDayAs: yesterday) { return "Gestern" }
        let format = DateFormatter(); format.locale = Locale(identifier: "de_DE"); format.timeZone = calendar.timeZone; format.dateFormat = "EE, dd.MM.yyyy"
        return format.string(from: date)
    }
}

extension Calendar {
    static var berlin: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.locale = Locale(identifier: "de_DE")
        calendar.timeZone = TimeZone(identifier: "Europe/Berlin") ?? .current
        return calendar
    }
}

/// Labels, symbols and colours for PowerBot entries – shared by the case centre, home events and the briefing.
enum PowerBotPresentation {
    static let kinds = ["mute": "Chatpause", "conflict": "Streit erkannt", "help": "Hilferuf",
        "security": "Cheat-Hinweis", "outage": "KI pausiert", "recovery": "KI wieder erreichbar",
        "settings": "Einstellungen geändert", "abuse": "Nachricht blockiert", "advertising": "Werbung blockiert",
        "keyword-warning": "Stichwort · Hinweis", "keyword-block": "Stichwort · blockiert",
        "warning": "Hinweis an Spieler", "reviewed": "Nachträglich beanstandet", "deescalation": "KI hat beruhigt",
        "ai-action": "KI-Aktion ausgeführt", "server-event": "Serverereignis", "whisper-block": "Flüstern zurückgehalten",
        "pardon": "Zähler zurückgesetzt"]
    static func title(_ kind: String) -> String { kinds[kind] ?? kind }
    static func symbol(_ kind: String) -> String {
        switch kind {
        case "mute": return "speaker.slash.fill"
        case "conflict": return "flame.fill"
        case "help": return "lifepreserver.fill"
        case "security": return "exclamationmark.shield.fill"
        case "outage": return "bolt.horizontal.circle.fill"
        case "recovery": return "checkmark.circle.fill"
        case "settings": return "slider.horizontal.3"
        case "abuse", "keyword-block", "whisper-block": return "hand.raised.fill"
        case "advertising": return "megaphone.fill"
        case "warning", "keyword-warning", "reviewed": return "exclamationmark.bubble.fill"
        case "deescalation": return "bubble.left.and.text.bubble.right.fill"
        case "pardon": return "arrow.counterclockwise.circle.fill"
        default: return "shield.lefthalf.filled"
        }
    }
    static func tint(_ kind: String, severity: String = "") -> Color {
        if severity == "severe" { return .red }
        switch kind {
        case "mute", "abuse", "keyword-block", "whisper-block", "advertising", "outage": return .red
        case "conflict", "warning", "keyword-warning", "reviewed", "security": return .orange
        case "help": return .pink
        case "deescalation", "recovery", "pardon": return .green
        default: return .accentColor
        }
    }
    static func severity(_ value: String) -> String {
        switch value { case "severe": return "Schwer"; case "high": return "Hoch"; case "medium": return "Mittel"; case "low": return "Leicht"; default: return "" }
    }
    static func severityTint(_ value: String) -> Color {
        switch value { case "severe": return .red; case "high": return .orange; case "medium": return .yellow; default: return .secondary }
    }
    static let strictness: [(String, String, String)] = [
        ("relaxed", "Gelassen", "Nur schwere Beleidigungen und klare Belästigung. Streits erst ab „hoch“ als Fall."),
        ("balanced", "Ausgewogen", "Kleine Sticheleien bleiben erlaubt, echte starke Streits werden eingedämmt. Empfohlen."),
        ("strict", "Streng", "Früh eingreifen und Beleidigungen konsequent blockieren.")
    ]
    static let statuses = ["active": "Aktiv", "bypass": "KI pausiert · Chat frei", "unconfigured": "API-Schlüssel fehlt · lokale Regeln aktiv", "off": "Aus"]
    /// Settings flags grouped for the settings screen. Unknown server flags stay untouched.
    static let flagGroups: [(String, [(String, String, String)])] = [
        ("Grundlagen", [("enabled", "PowerBot aktiv", "Hauptschalter für alle PowerBot-Funktionen."),
                        ("moderation", "KI-Moderation", "Nachrichten mit Moderationssignal im Kontext prüfen."),
                        ("whispers", "Flüstern prüfen", "Private Nachrichten vor der Zustellung prüfen.")]),
        ("Eingreifen", [("advertising", "Fremdwerbung blockieren", "Werbung für andere Server sofort zurückhalten."),
                        ("auto-mute", "Automatische Chatpause", "Nach wiederholten Verstößen in 20 Minuten 10 Minuten Chatpause."),
                        ("linked-mutes", "Bestätigte Alts einschließen", "Chatpausen gelten auch für bestätigte Zweitkonten."),
                        ("conflict-actions", "Schwere Streits eindämmen", "Nur bei schweren Streits erhält der Verursacher eine kurze Chatpause.")]),
        ("Öffentlicher Chat", [("public-chat", "Chat-KI im Spiel", "Antwortet auf !ki und kann deeskalieren."),
                               ("proactive", "Proaktiv beruhigen", "Bei echten Streits selbstständig eine ruhige Nachricht senden."),
                               ("discord", "Discord einbeziehen", "Auch den Discord-Relay moderieren und Hinweise spiegeln.")]),
        ("Team", [("notify-staff", "Team benachrichtigen", "Neue Fälle per Bark und im Ereignisprotokoll."),
                  ("critical-alerts", "Kritische Meldungen", "Chatpausen und schwere Streits als kritischen Bark-Alarm senden."),
                  ("security-hints", "Cheat-Hinweise sammeln", "Auffällige Kampfmuster als Fall anlegen."),
                  ("staff-device-context", "Geräteinfos für Staff-KI", "Der KI-Assistent darf Gerätedetails nutzen.")])
    ]
}

/// Filters for the unified player timeline.
enum PlayerTimelineFilter: String, CaseIterable, Identifiable {
    case all, chat, whisper, action, combat, item, powerbot, flagged
    var id: String { rawValue }
    var title: String {
        switch self {
        case .all: return "Alles"
        case .chat: return "Chat"
        case .whisper: return "Flüstern"
        case .action: return "Aktionen"
        case .combat: return "Kämpfe"
        case .item: return "Items"
        case .powerbot: return "PowerBot"
        case .flagged: return "Markiert"
        }
    }
    var symbol: String {
        switch self {
        case .all: return "line.3.horizontal.decrease.circle"
        case .chat: return "text.bubble"
        case .whisper: return "lock.bubble"
        case .action: return "figure.walk"
        case .combat: return "bolt.shield"
        case .item: return "shippingbox"
        case .powerbot: return "shield.lefthalf.filled"
        case .flagged: return "flag.fill"
        }
    }
    func includes(_ row: JSON) -> Bool {
        switch self {
        case .all: return true
        case .flagged: return row["flagged"].bool
        case .powerbot: return row["category"].string == "powerbot" || !row["case"].string.isEmpty
        default: return row["category"].string == rawValue
        }
    }
}

enum PlayerTimelineRange: String, CaseIterable, Identifiable {
    case hour, day, week, all
    var id: String { rawValue }
    var title: String { switch self { case .hour: return "1 Std."; case .day: return "24 Std."; case .week: return "7 Tage"; case .all: return "Alles" } }
    var seconds: TimeInterval? { switch self { case .hour: return 3600; case .day: return 86_400; case .week: return 604_800; case .all: return nil } }
}

enum PlayerTimelineModel {
    static func filter(_ rows: [JSON], filter: PlayerTimelineFilter, range: PlayerTimelineRange, query: String, now: Date = Date()) -> [JSON] {
        let terms = query.trimmingCharacters(in: .whitespacesAndNewlines)
        return rows.filter { row in
            guard filter.includes(row) else { return false }
            if let seconds = range.seconds {
                // Legacy rows without a date only appear in the complete history.
                guard let date = SuitePlayerPresentation.date(row["at"]), now.timeIntervalSince(date) <= seconds else { return false }
            }
            return terms.isEmpty || SuiteSearch.matches(terms, in: ["text", "from", "to", "flagReason", "kind"].map { row[$0].string }.joined(separator: " "))
        }
    }
    /// Consecutive day groups in the order of the (newest first) rows.
    static func days(_ rows: [JSON], now: Date = Date()) -> [(String, [JSON])] {
        var result: [(String, [JSON])] = []
        for row in rows {
            let title = SuitePlayerPresentation.date(row["at"]).map { SuiteRelativeTime.day($0, now: now) } ?? "Ohne Datum"
            if let last = result.indices.last, result[last].0 == title { result[last].1.append(row) } else { result.append((title, [row])) }
        }
        return result
    }
    static func counts(_ rows: [JSON]) -> [PlayerTimelineFilter: Int] {
        Dictionary(uniqueKeysWithValues: PlayerTimelineFilter.allCases.map { filter in (filter, rows.filter(filter.includes).count) })
    }
    /// Older suites only send the chat timeline inside the dossier; convert it into the same row format.
    static func legacy(_ chat: [JSON]) -> [JSON] {
        chat.map { row in
            .object(["id": .string("legacy:" + row["id"].string), "at": row["at"], "category": .string(row["kind"].string == "whisper" ? "whisper" : "chat"),
                     "kind": row["kind"], "from": row["from"], "to": row["to"], "text": row["text"], "flagged": .bool(false)])
        }
    }
    static func symbol(_ row: JSON) -> String {
        switch row["category"].string {
        case "chat": return "text.bubble.fill"
        case "whisper": return "lock.bubble.fill"
        case "combat": return "bolt.shield.fill"
        case "item":
            switch row["kind"].string { case "drop": return "arrow.down.to.line"; case "pickup": return "hand.raised.fill"; case "given": return "arrow.right.circle.fill"; case "death": return "heart.slash.fill"; default: return "shippingbox.fill" }
        case "powerbot": return PowerBotPresentation.symbol(row["kind"].string)
        default: return row["kind"].string == "command" ? "terminal.fill" : row["kind"].string == "session" ? "door.left.hand.open" : "figure.walk"
        }
    }
    static func tint(_ row: JSON) -> Color {
        if row["flagged"].bool { return .red }
        switch row["category"].string {
        case "chat": return .accentColor
        case "whisper": return .purple
        case "combat": return .orange
        case "item": return .teal
        case "powerbot": return .red
        default: return .secondary
        }
    }
}

/// "While you were away" – when to show it and what it contains.
enum SuiteBriefingModel {
    static let thresholds: [(Double, String)] = [(1, "1 Stunde"), (3, "3 Stunden"), (6, "6 Stunden"), (12, "12 Stunden")]
    static func shouldShow(lastVisit: Double, now: Double, thresholdHours: Double) -> Bool {
        lastVisit > 0 && now - lastVisit >= max(0.25, thresholdHours) * 3600
    }
    struct Tile: Identifiable, Equatable {
        let id: String
        let title: String
        let value: Int
        let symbol: String
        let tint: Color
        let tool: SuiteTool
    }
    static func tiles(_ data: JSON, sections: Set<String>) -> [Tile] {
        var result: [Tile] = []
        let players = data["players"], bot = data["powerbot"], groups = data["events"]["groups"]
        if sections.contains("players"), players != .null {
            result.append(Tile(id: "new", title: "Neue Spieler", value: players["new"].int, symbol: "person.badge.plus", tint: .green, tool: .players))
            result.append(Tile(id: "active", title: "Aktive Spieler", value: players["active"].int, symbol: "person.2.fill", tint: .accentColor, tool: .players))
        }
        if sections.contains("powerbot"), bot != .null {
            result.append(Tile(id: "blocked", title: "Blockiert", value: bot["blocked"].int, symbol: "hand.raised.fill", tint: .red, tool: .powerBot))
            result.append(Tile(id: "conflicts", title: "Streits", value: bot["conflicts"].int, symbol: "flame.fill", tint: .orange, tool: .powerBot))
            result.append(Tile(id: "mutes", title: "Chatpausen", value: bot["mutes"].int, symbol: "speaker.slash.fill", tint: .red, tool: .powerBot))
            result.append(Tile(id: "calm", title: "Beruhigt", value: bot["deescalations"].int, symbol: "bubble.left.and.text.bubble.right.fill", tint: .green, tool: .powerBot))
        }
        if sections.contains("events"), data["events"] != .null {
            result.append(Tile(id: "tickets", title: "Tickets", value: groups["tickets"].int, symbol: "ticket.fill", tint: .blue, tool: .tickets))
            result.append(Tile(id: "reports", title: "Reports", value: groups["reports"].int, symbol: "flag.fill", tint: .orange, tool: .reports))
            result.append(Tile(id: "server", title: "Serverprobleme", value: groups["server"].int, symbol: "exclamationmark.triangle.fill", tint: .red, tool: .diagnostics))
        }
        return result
    }
    static func hasNews(_ data: JSON, sections: Set<String>) -> Bool {
        tiles(data, sections: sections).contains { $0.value > 0 && $0.id != "active" } || !data["powerbot"]["highlights"].array.isEmpty && sections.contains("powerbot")
    }
    static func headline(_ data: JSON, sections: Set<String>) -> String {
        let tiles = tiles(data, sections: sections).filter { $0.value > 0 && $0.id != "active" }
        guard !tiles.isEmpty else { return "Alles ruhig – keine besonderen Vorkommnisse." }
        return tiles.prefix(3).map { "\($0.value) \($0.title)" }.joined(separator: " · ")
    }
    static let sectionChoices: [(String, String)] = [("players", "Spieler"), ("powerbot", "PowerBot"), ("events", "Tickets, Reports & Server")]
}
