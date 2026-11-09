import Foundation
import Combine

/// One directory for the home screen, Admin tab, direct accounts and Shortcuts.
enum SuiteTool: String, CaseIterable, Identifiable, Codable {
    case overview, briefing, powerBot, players, advertising, tickets, events, adminLog, histories, staffChat, tasks
    case assistant, diagnostics, lagMap, spark, crashes, console, operations, worldTrim, serverEvents
    case discordStatus, scoreboard, discord, luckperms, lands, locks, commands
    case management, team, appSettings, settings, access, account, bark, quiet, memory, links, crossplay, functions
    case reports, bans, vpn, alts, ultrabans, statistics, menu, ticketSettings, online, dependencies
    case chat, restart, pairing
    var id: String { rawValue }
    var title: String { metadata.0 }
    var symbol: String { metadata.1 }
    var group: String { metadata.2 }
    var permission: String { metadata.3 }
    var feature: String { metadata.4 }
    private var metadata: (String, String, String, String, String) {
        switch self {
        case .overview: return ("Admin Suite", "shield.lefthalf.filled", "Übersicht", "", "")
        case .briefing: return ("Rückblick", "sun.horizon.fill", "Übersicht & Verläufe", "", "")
        case .advertising: return ("Werbe-Zentrale", "megaphone.fill", "Kommunikation & Studio", "data.discord-ads", "ad-hub-v1")
        case .players: return ("Spieler", "person.2.fill", "Spieler & Rechte", "data.players", "")
        case .tickets: return ("Tickets", "ticket.fill", "Kommunikation & Studio", "data.tickets", "tickets-v1")
        case .events: return ("Ereignisse", "bell.badge.fill", "Übersicht & Verläufe", "data.events", "events-v1")
        case .adminLog: return ("Admin-Log", "list.bullet.clipboard.fill", "Übersicht & Verläufe", "data.admin-log", "admin-log-v1")
        case .histories: return ("Verläufe verwalten", "archivebox.fill", "Übersicht & Verläufe", "", "history-management-v1")
        case .staffChat: return ("Staff-Chat", "bubble.left.and.text.bubble.right.fill", "Kommunikation & Studio", "data.staff-chat", "staff-chat-v1")
        case .tasks: return ("Staff-Aufgaben", "checklist", "Kommunikation & Studio", "data.staff-tasks", "staff-tasks-v1")
        case .assistant: return ("KI-Assistent", "sparkles", "Server & Diagnose", "", "")
        case .diagnostics: return ("Diagnose", "waveform.path.ecg", "Server & Diagnose", "data.diagnostics", "diagnostics-v1")
        case .lagMap: return ("Lag-Karte", "map.fill", "Server & Diagnose", "data.diagnostics", "lag-map-v1")
        case .spark: return ("Spark-Profiler", "flame.fill", "Server & Diagnose", "data.diagnostics", "spark-v1")
        case .crashes: return ("Abstürze", "bolt.trianglebadge.exclamationmark.fill", "Server & Diagnose", "data.diagnostics", "crash-manager-v1")
        case .console: return ("Diagnose-Konsole", "terminal.fill", "Server & Diagnose", "data.diagnostics", "diagnostics-v1")
        case .powerBot: return ("PowerBot-Zentrale", "shield.lefthalf.filled", "Moderation & PowerBot", "data.powerbot", "powerbot-v1")
        case .serverEvents: return ("Event-Modus & Lockdown", "calendar.badge.clock", "Server & Diagnose", "data.server-events", "server-events-v1")
        case .operations: return ("Serverbetrieb & Neustart", "arrow.clockwise.circle.fill", "Server & Diagnose", "data.overview", "overview")
        case .worldTrim: return ("Welten-Trimmer", "scissors", "Server & Diagnose", "data.world-trim", "world-trim-v1")
        case .discordStatus: return ("Discord-Status", "paintpalette.fill", "Kommunikation & Studio", "data.discord-status", "discord-status-designer-v1")
        case .scoreboard: return ("Scoreboard", "list.bullet.rectangle.fill", "Kommunikation & Studio", "data.scoreboard", "scoreboard-designer-v1")
        case .discord: return ("Discord-Kanäle", "bubble.left.and.bubble.right.fill", "Kommunikation & Studio", "data.discord-channels", "")
        case .luckperms: return ("Ränge & Rechte", "crown.fill", "Spieler & Rechte", "data.luckperms", "luckperms-v1")
        case .lands: return ("Lands", "map.fill", "Spieler & Rechte", "data.lands", "lands-v1")
        case .locks: return ("Rechte & Sperren", "lock.shield.fill", "Spieler & Rechte", "data.access-locks", "access-locks-v1")
        case .commands: return ("Befehle", "terminal.fill", "Spieler & Rechte", "data.commands", "command-rights-v1")
        case .management: return ("Suite-Verwaltung", "wrench.and.screwdriver.fill", "Einstellungen & Zugänge", "data.management", "")
        case .team: return ("Admin-Team", "person.badge.shield.checkmark.fill", "Einstellungen & Zugänge", "data.management", "admin-roles-v3")
        case .appSettings: return ("App-Einstellungen", "switch.2", "Einstellungen & Zugänge", "data.app-settings", "app-settings-v1")
        case .settings: return ("Suite-Einstellungen", "slider.horizontal.3", "Einstellungen & Zugänge", "data.settings", "")
        case .access: return ("Zugänge & Serverregeln", "person.badge.key.fill", "Einstellungen & Zugänge", "", "accounts-v1")
        case .account: return ("Konto & Sicherheit", "lock.shield.fill", "Einstellungen & Zugänge", "", "accounts-v2")
        case .bark: return ("Bark-Zentrale", "bell.badge.fill", "Einstellungen & Zugänge", "action.notifications.update", "events-v1")
        case .quiet: return ("Ruhezeiten", "moon.zzz.fill", "Einstellungen & Zugänge", "action.notifications.update", "quiet-hours-v1")
        case .memory: return ("RAM-Notfallneustart", "memorychip.fill", "Einstellungen & Zugänge", "data.memory", "memory-restart-v1")
        case .links: return ("Discord-Verknüpfungen", "link", "Einstellungen & Zugänge", "data.links", "links-v1")
        case .crossplay: return ("Geyser & Floodgate", "gamecontroller.fill", "Server & Diagnose", "data.crossplay", "crossplay-v1")
        case .functions: return ("Alle Funktionen", "square.grid.2x2.fill", "Weitere Funktionen", "", "")
        case .reports: return ("Reports", "flag.fill", "Moderation & PowerBot", "data.reports", "moderation-center-v1")
        case .bans: return ("Banns & Freigaben", "hand.raised.fill", "Moderation & PowerBot", "data.bans", "moderation-center-v1")
        case .vpn: return ("VPN-Schutz", "network", "Moderation & PowerBot", "data.vpn-whitelist", "moderation-center-v1")
        case .alts: return ("Alt-Accounts", "person.2.badge.key.fill", "Moderation & PowerBot", "data.alt-dashboard", "")
        case .ultrabans: return ("Ultrabanns", "shield.slash.fill", "Moderation & PowerBot", "data.ultrabans", "")
        case .statistics: return ("Statistiken", "chart.bar.xaxis", "Server & Diagnose", "data.statistics", "statistics")
        case .menu: return ("Java-/Bedrock-Adminmenüs", "gamecontroller.fill", "Weitere Funktionen", "action.menu.action", "")
        case .ticketSettings: return ("Ticket-Einstellungen", "ticket.fill", "Einstellungen & Zugänge", "data.ticket-settings", "tickets-v1")
        case .online: return ("Beitrittshinweise", "person.badge.plus", "Einstellungen & Zugänge", "data.online-settings", "online-notifications-v1")
        case .dependencies: return ("Plugin-Bibliotheken", "shippingbox.fill", "Server & Diagnose", "data.management", "")
        case .chat: return ("Serverchat", "bubble.left.and.bubble.right.fill", "Kommunikation & Studio", "data.chat", "public-chat-v1")
        case .restart: return ("Neustart planen", "arrow.clockwise.circle.fill", "Server & Diagnose", "data.operations", "restart-v1")
        case .pairing: return ("Kopplung & Verbindung", "key.horizontal.fill", "Einstellungen & Zugänge", "", "")
        }
    }
    func matches(_ query: String) -> Bool {
        let extra: String
        switch self {
        case .advertising: extra = " Werbung werben kopieren Anzeige Shortcut Kurzbefehle"
        case .powerBot: extra = " AutoMod Moderation Streit Mute Chatpause Fälle Systemanweisung KI"
        case .briefing: extra = " Übersicht Nacht Zusammenfassung letzter Besuch Neuigkeiten"
        default: extra = ""
        }
        return SuiteSearch.matches(query, in: title + " " + group + " " + rawValue + extra)
    }
}

enum SuiteSearch {
    static func matches(_ query: String, in text: String) -> Bool {
        let locale = Locale(identifier: "de_DE")
        let haystack = text.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: locale)
        return query.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: locale)
            .split(whereSeparator: { $0.isWhitespace }).allSatisfy { haystack.contains($0) }
    }
}

struct SuiteLaunchRequest: Identifiable, Equatable {
    let id = UUID()
    var tool: SuiteTool = .overview
    var connection: UUID? = nil
    var place: String? = nil
    var prepareAd = false
    static func parse(_ url: URL) -> SuiteLaunchRequest? {
        guard url.scheme == "panel", url.host == "suite", url.path.isEmpty || url.path == "/",
              let components = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return nil }
        let items = components.queryItems ?? []
        guard Set(items.map(\.name)).count == items.count else { return nil }
        func value(_ name: String) -> String? { items.first { $0.name == name }?.value }
        guard let tool = SuiteTool(rawValue: value("tool") ?? "overview") else { return nil }
        if let id = value("connection"), UUID(uuidString: id) == nil { return nil }
        if let place = value("place"), place.range(of: "^[A-Za-z0-9_-]{1,40}$", options: .regularExpression) == nil { return nil }
        return SuiteLaunchRequest(tool: tool, connection: value("connection").flatMap(UUID.init(uuidString:)), place: value("place"), prepareAd: value("prepare") == "1")
    }
    var url: URL {
        var components = URLComponents(); components.scheme = "panel"; components.host = "suite"
        components.queryItems = [URLQueryItem(name: "tool", value: tool.rawValue)]
        if let connection { components.queryItems?.append(URLQueryItem(name: "connection", value: connection.uuidString)) }
        if let place { components.queryItems?.append(URLQueryItem(name: "place", value: place)) }
        if prepareAd { components.queryItems?.append(URLQueryItem(name: "prepare", value: "1")) }
        return components.url!
    }
}

@MainActor final class SuiteNavigationInbox: ObservableObject {
    static let shared = SuiteNavigationInbox()
    @Published var request: SuiteLaunchRequest?
    @discardableResult func open(_ url: URL) -> Bool {
        guard let request = SuiteLaunchRequest.parse(url) else { return false }
        self.request = request; return true
    }
}
