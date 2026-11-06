import Foundation
import Combine

@MainActor final class SuiteQuickAccess: ObservableObject {
    static let shared = SuiteQuickAccess()
    static let choices: [SuiteTool] = [.overview, .advertising, .players, .tickets, .diagnostics, .adminLog, .histories, .staffChat, .settings]
    static let initial: [SuiteTool] = [.overview, .advertising, .players, .tickets]
    @Published private(set) var selections: [String: [String]]
    @Published private(set) var preferred: UUID?
    private let defaults: UserDefaults
    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        selections = defaults.dictionary(forKey: "suite.serverShortcuts.v1") as? [String: [String]] ?? [:]
        preferred = defaults.string(forKey: "suite.preferredConnection.v1").flatMap(UUID.init(uuidString:))
        if SuiteShortcutDemo.enabled { preferred = nil; selections = [:] }
    }
    func tools(for connection: UUID) -> [SuiteTool] {
        guard let stored = selections[connection.uuidString] else { return Self.initial }
        return Self.choices.filter { stored.contains($0.rawValue) }
    }
    func set(_ tool: SuiteTool, for connection: UUID, enabled: Bool) {
        var selected = tools(for: connection)
        selected.removeAll { $0 == tool }
        if enabled { selected.append(tool) }
        selections[connection.uuidString] = selected.map(\.rawValue)
        defaults.set(selections, forKey: "suite.serverShortcuts.v1")
    }
    func prefer(_ connection: UUID?) {
        preferred = connection
        defaults.set(connection?.uuidString, forKey: "suite.preferredConnection.v1")
    }
    func request(tool: SuiteTool, connection: UUID, records: [SuiteConnection]) -> SuiteLaunchRequest? {
        guard records.contains(where: { $0.id == connection }) else { return nil }
        return SuiteLaunchRequest(tool: tool, connection: connection)
    }
}
