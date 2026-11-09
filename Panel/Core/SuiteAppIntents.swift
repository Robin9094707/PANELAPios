import AppIntents
import Foundation

enum SuiteShortcutTool: String, AppEnum {
    case overview, advertising, tickets, assistant, diagnostics, histories, settings, players, adminLog, staffChat, statistics, operations, reports, bans, events, tasks, chat, functions
    case powerBot, briefing, automations, smartRestart, locker
    static var typeDisplayRepresentation: TypeDisplayRepresentation = "Admin-Werkzeug"
    static var caseDisplayRepresentations: [Self: DisplayRepresentation] = [
        .overview: "Admin Suite", .advertising: "Werbe-Zentrale", .tickets: "Tickets",
        .assistant: "KI-Assistent", .diagnostics: "Diagnose", .histories: "Verläufe verwalten", .settings: "Suite-Einstellungen",
        .players: "Spieler", .adminLog: "Admin-Log", .staffChat: "Staff-Chat", .statistics: "Statistiken",
        .operations: "Serverbetrieb", .reports: "Reports", .bans: "Banns", .events: "Ereignisse",
        .tasks: "Staff-Aufgaben", .chat: "Serverchat", .functions: "Alle Funktionen",
        .powerBot: "PowerBot-Zentrale", .briefing: "Rückblick",
        .automations: "Automationen", .smartRestart: "Smart-Neustart", .locker: "Einbehaltungsablage"
    ]
}

struct SuiteShortcutServer: AppEntity {
    static var typeDisplayRepresentation: TypeDisplayRepresentation = "Admin-Suite-Zugang"
    static var defaultQuery = SuiteShortcutServerQuery()
    var id: String
    var name: String
    var displayRepresentation: DisplayRepresentation { DisplayRepresentation(title: "\(name)") }
}
struct SuiteShortcutServerQuery: EntityQuery {
    @MainActor func entities(for identifiers: [String]) async throws -> [SuiteShortcutServer] {
        try await suggestedEntities().filter { identifiers.contains($0.id) }
    }
    @MainActor func suggestedEntities() async throws -> [SuiteShortcutServer] {
        SuiteConnections.shared.values.filter { !SuiteQuickAccess.shared.isHidden($0.id) }.map { SuiteShortcutServer(id: $0.id.uuidString, name: SuiteQuickAccess.shared.name($0) + " · " + $0.endpoint) }
    }
}

struct SuiteAdvertisingPlace: AppEntity {
    static var typeDisplayRepresentation: TypeDisplayRepresentation = "Werbe-Ort"
    static var defaultQuery = SuiteAdvertisingPlaceQuery()
    var id: String
    var name: String
    var displayRepresentation: DisplayRepresentation { DisplayRepresentation(title: "\(name)") }
    var target: (UUID, String)? {
        let parts = id.split(separator: "|", maxSplits: 1).map(String.init)
        guard parts.count == 2, let connection = UUID(uuidString: parts[0]),
              parts[1].range(of: "^[A-Za-z0-9_-]{1,40}$", options: .regularExpression) != nil else { return nil }
        return (connection, parts[1])
    }
}
struct SuiteAdvertisingPlaceQuery: EntityStringQuery {
    @MainActor func entities(for identifiers: [String]) async throws -> [SuiteAdvertisingPlace] {
        // Resolving a previously chosen entity must work immediately, even if its server is offline.
        // The app still fetches the current ad and checks rights/intervals before doing anything.
        identifiers.compactMap { id in
            let value = SuiteAdvertisingPlace(id: id, name: "Werbe-Ort")
            guard let (connectionID, place) = value.target, !SuiteQuickAccess.shared.isHidden(connectionID),
                  let record = SuiteConnections.shared.values.first(where: { $0.id == connectionID }) else { return nil }
            let name = AdHubCache.entry(serverId: record.serverIdentity, place: place)?["name"] ?? "Werbe-Ort"
            return SuiteAdvertisingPlace(id: id, name: name + " · " + SuiteConnections.shared.name(record))
        }
    }
    @MainActor func suggestedEntities() async throws -> [SuiteAdvertisingPlace] { try await fetch() }
    @MainActor func entities(matching string: String) async throws -> [SuiteAdvertisingPlace] {
        try await fetch().filter { SuiteSearch.matches(string, in: $0.name) }
    }
    @MainActor private func fetch() async throws -> [SuiteAdvertisingPlace] {
        let records = SuiteConnections.shared.values.filter { !SuiteQuickAccess.shared.isHidden($0.id) }
        return try await withThrowingTaskGroup(of: [SuiteAdvertisingPlace].self) { group in
            var next = 0, output: [SuiteAdvertisingPlace] = []
            for record in records.prefix(3) {
                group.addTask { @MainActor in try await one(record) }; next += 1
            }
            while let values = try await group.next() {
                output += values
                if next < records.count {
                    let record = records[next]; next += 1
                    group.addTask { @MainActor in try await one(record) }
                }
            }
            return output.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
        }
    }
    @MainActor private func one(_ record: SuiteConnection) async throws -> [SuiteAdvertisingPlace] {
        do {
            let api = try SuiteAPI(record)
            let data = try await SuiteNavigationDeadline.run(seconds: 6) {
                _ = try await api.health(timeout: 6)
                guard api.features.contains("ad-hub-v1"), api.can("data.discord-ads") else { return JSON.null }
                return try await api.request("ad-hub", timeout: 6)
            }
            return data["places"].array.map { SuiteAdvertisingPlace(id: record.id.uuidString + "|" + $0["id"].string, name: $0["name"].string + " · " + SuiteConnections.shared.name(record)) }
        } catch {
            if RequestFailure.isCancellation(error) { throw error }
            return []
        }
    }

}

struct OpenSuiteToolIntent: AppIntent {
    static var title: LocalizedStringResource = "Admin-Werkzeug öffnen"
    static var description = IntentDescription("Öffnet ein Admin-Werkzeug auf deinem gespeicherten Server.")
    static var openAppWhenRun = true
    @Parameter(title: "Werkzeug", default: .overview) var tool: SuiteShortcutTool
    @Parameter(title: "Server") var server: SuiteShortcutServer?
    static var parameterSummary: some ParameterSummary { Summary("\(\.$tool) öffnen") { \.$server } }
    @MainActor func perform() async throws -> some IntentResult {
        SuiteNavigationInbox.shared.request = SuiteLaunchRequest(tool: SuiteTool(rawValue: tool.rawValue) ?? .overview, connection: try selectedConnection(server))
        return .result()
    }
}
@MainActor private func selectedConnection(_ server: SuiteShortcutServer?) throws -> UUID? {
    guard let server else { return nil }
    guard let id = UUID(uuidString: server.id), !SuiteQuickAccess.shared.isHidden(id), SuiteConnections.shared.values.contains(where: { $0.id == id }) else { throw PanelError(message: "Dieser Serverzugang ist nicht mehr vorhanden. Bitte den Kurzbefehl neu zuordnen.") }
    return id
}

struct OpenSuiteAssistantIntent: AppIntent {
    static var title: LocalizedStringResource = "KI-Assistent öffnen"
    static var openAppWhenRun = true
    @Parameter(title: "Server") var server: SuiteShortcutServer?
    static var parameterSummary: some ParameterSummary { Summary("KI-Assistent öffnen") { \.$server } }
    @MainActor func perform() async throws -> some IntentResult {
        SuiteNavigationInbox.shared.request = SuiteLaunchRequest(tool: .assistant, connection: try selectedConnection(server))
        return .result()
    }
}
/// 5.6.0: open the PowerBot centre (cases, players, settings) directly.
struct OpenPowerBotIntent: AppIntent {
    static var title: LocalizedStringResource = "PowerBot-Zentrale öffnen"
    static var description = IntentDescription("Öffnet die PowerBot-Fälle mit Chatverlauf, Spielerverwaltung und Einstellungen.")
    static var openAppWhenRun = true
    @Parameter(title: "Server") var server: SuiteShortcutServer?
    static var parameterSummary: some ParameterSummary { Summary("PowerBot-Zentrale öffnen") { \.$server } }
    @MainActor func perform() async throws -> some IntentResult {
        SuiteNavigationInbox.shared.request = SuiteLaunchRequest(tool: .powerBot, connection: try selectedConnection(server))
        return .result()
    }
}
/// 5.6.0: what happened since the last visit.
struct OpenBriefingIntent: AppIntent {
    static var title: LocalizedStringResource = "Rückblick öffnen"
    static var description = IntentDescription("Zeigt neue Spieler, PowerBot-Eingriffe, Tickets, Reports und Serverprobleme seit deinem letzten Besuch.")
    static var openAppWhenRun = true
    @Parameter(title: "Server") var server: SuiteShortcutServer?
    static var parameterSummary: some ParameterSummary { Summary("Rückblick öffnen") { \.$server } }
    @MainActor func perform() async throws -> some IntentResult {
        SuiteNavigationInbox.shared.request = SuiteLaunchRequest(tool: .briefing, connection: try selectedConnection(server))
        return .result()
    }
}
struct OpenAdvertisingHubIntent: AppIntent {
    static var title: LocalizedStringResource = "Werbeverwaltung öffnen"
    static var openAppWhenRun = true
    @Parameter(title: "Server") var server: SuiteShortcutServer?
    static var parameterSummary: some ParameterSummary { Summary("Werbeverwaltung öffnen") { \.$server } }
    @MainActor func perform() async throws -> some IntentResult {
        SuiteNavigationInbox.shared.request = SuiteLaunchRequest(tool: .advertising, connection: try selectedConnection(server))
        return .result()
    }
}
struct PrepareAdvertisingIntent: AppIntent {
    static var title: LocalizedStringResource = "Werbung vorbereiten"
    static var description = IntentDescription("Öffnet Simplex Panel, kopiert den aktuellen Werbetext und öffnet den gewählten Ort. Pausen, Rechte und Intervalle gelten weiterhin. Du fügst den Text selbst ein.")
    static var openAppWhenRun = true
    @Parameter(title: "Werbe-Ort") var place: SuiteAdvertisingPlace
    static var parameterSummary: some ParameterSummary { Summary("Werbung für \(\.$place) vorbereiten") }
    @MainActor func perform() async throws -> some IntentResult {
        guard let (connection, placeID) = place.target else { throw PanelError(message: "Werbe-Ort bitte neu auswählen.") }
        SuiteNavigationInbox.shared.request = SuiteLaunchRequest(tool: .advertising, connection: connection, place: placeID, prepareAd: true)
        return .result()
    }
}
struct SuiteAppShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: OpenSuiteAssistantIntent(), phrases: ["Öffne den KI-Assistenten in \(.applicationName)"], shortTitle: "KI-Assistent", systemImageName: "sparkles")
        AppShortcut(intent: OpenSuiteToolIntent(), phrases: ["Öffne die Verwaltung in \(.applicationName)"], shortTitle: "Admin Suite", systemImageName: "shield.lefthalf.filled")
        AppShortcut(intent: OpenPowerBotIntent(), phrases: ["Öffne PowerBot in \(.applicationName)", "Zeige PowerBot-Fälle in \(.applicationName)"], shortTitle: "PowerBot", systemImageName: "shield.checkered")
        AppShortcut(intent: OpenBriefingIntent(), phrases: ["Zeige den Rückblick in \(.applicationName)", "Was ist passiert in \(.applicationName)"], shortTitle: "Rückblick", systemImageName: "sun.horizon.fill")
        AppShortcut(intent: OpenAdvertisingHubIntent(), phrases: ["Öffne die Werbeverwaltung in \(.applicationName)"], shortTitle: "Werbeverwaltung", systemImageName: "megaphone.fill")
        AppShortcut(intent: PrepareAdvertisingIntent(), phrases: ["Bereite Werbung mit \(.applicationName) vor"], shortTitle: "Werbung vorbereiten", systemImageName: "doc.on.doc.fill")
    }
}
