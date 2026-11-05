import AppIntents
import Foundation

enum SuiteShortcutTool: String, AppEnum {
    case overview, advertising, tickets, diagnostics, histories, settings
    static var typeDisplayRepresentation: TypeDisplayRepresentation = "Admin-Werkzeug"
    static var caseDisplayRepresentations: [Self: DisplayRepresentation] = [
        .overview: "Admin Suite", .advertising: "Werbe-Zentrale", .tickets: "Tickets",
        .diagnostics: "Diagnose", .histories: "Verläufe verwalten", .settings: "Suite-Einstellungen"
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
        SuiteConnections.shared.values.map { SuiteShortcutServer(id: $0.id.uuidString, name: $0.endpoint + ($0.panel.hasPrefix("suite-direct:") ? " · " + $0.panel.replacingOccurrences(of: "suite-direct:", with: "") : "")) }
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
        // Resolve selected identifiers against the live server; deleted places never fall back to cached ads.
        try await fetch(connectionIDs: Set(identifiers.compactMap { $0.split(separator: "|").first.map(String.init) })).filter { identifiers.contains($0.id) }
    }
    @MainActor func suggestedEntities() async throws -> [SuiteAdvertisingPlace] { try await fetch(connectionIDs: nil) }
    @MainActor func entities(matching string: String) async throws -> [SuiteAdvertisingPlace] {
        try await fetch(connectionIDs: nil).filter { SuiteSearch.matches(string, in: $0.name) }
    }
    @MainActor private func fetch(connectionIDs: Set<String>?) async throws -> [SuiteAdvertisingPlace] {
        var result: [SuiteAdvertisingPlace] = []
        for connection in SuiteConnections.shared.values where connectionIDs == nil || connectionIDs!.contains(connection.id.uuidString) {
            do {
                let api = try SuiteAPI(connection, timeout: 12); _ = try await api.health()
                guard api.features.contains("ad-hub-v1"), api.can("data.discord-ads") else { continue }
                let data = try await api.request("ad-hub")
                result += data["places"].array.map { SuiteAdvertisingPlace(id: connection.id.uuidString + "|" + $0["id"].string, name: $0["name"].string + " · " + connection.endpoint) }
            } catch { if RequestFailure.isCancellation(error) { throw error } }
        }
        return result
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
        SuiteNavigationInbox.shared.request = SuiteLaunchRequest(tool: SuiteTool(rawValue: tool.rawValue) ?? .overview, connection: server.flatMap { UUID(uuidString: $0.id) })
        return .result()
    }
}
struct OpenAdvertisingHubIntent: AppIntent {
    static var title: LocalizedStringResource = "Werbeverwaltung öffnen"
    static var openAppWhenRun = true
    @MainActor func perform() async throws -> some IntentResult {
        SuiteNavigationInbox.shared.request = SuiteLaunchRequest(tool: .advertising)
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
        AppShortcut(intent: OpenSuiteToolIntent(), phrases: ["Öffne die Verwaltung in \(.applicationName)"], shortTitle: "Admin Suite", systemImageName: "shield.lefthalf.filled")
        AppShortcut(intent: OpenAdvertisingHubIntent(), phrases: ["Öffne die Werbeverwaltung in \(.applicationName)"], shortTitle: "Werbeverwaltung", systemImageName: "megaphone.fill")
        AppShortcut(intent: PrepareAdvertisingIntent(), phrases: ["Bereite Werbung mit \(.applicationName) vor"], shortTitle: "Werbung vorbereiten", systemImageName: "doc.on.doc.fill")
    }
}
