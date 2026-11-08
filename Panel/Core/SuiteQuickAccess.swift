import Foundation
import Combine

@MainActor final class SuiteQuickAccess: ObservableObject {
    static let shared = SuiteQuickAccess()
    static let choices = SuiteTool.allCases
    static let initial: [SuiteTool] = [.overview, .advertising, .players, .tickets]
    @Published private(set) var values: [UUID: [String: JSON]]
    @Published private(set) var errors: [UUID: String] = [:]
    private var pending: [UUID: [String: JSON]]
    private var syncing: Set<UUID> = []
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        values = (try? JSONDecoder().decode([UUID: [String: JSON]].self, from: defaults.data(forKey: "suite.shortcuts.v2") ?? Data())) ?? [:]
        pending = (try? JSONDecoder().decode([UUID: [String: JSON]].self, from: defaults.data(forKey: "suite.shortcuts.pending.v2") ?? Data())) ?? [:]
        if SuiteShortcutDemo.enabled { values = [:]; pending = [:]; return }
        let old = defaults.dictionary(forKey: "suite.serverShortcuts.v1") as? [String: [String]] ?? [:]
        for (key, tools) in old {
            guard let id = UUID(uuidString: key), values[id]?["tools"] == nil else { continue }
            values[id, default: [:]]["tools"] = .array(tools.map(JSON.string))
        }
        if let id = defaults.string(forKey: "suite.preferredConnection.v1").flatMap(UUID.init(uuidString:)), values[id]?["preferredAt"] == nil {
            values[id, default: [:]]["preferredAt"] = .number(1)
        }
    }

    var preferred: UUID? {
        values.filter { !isHidden($0.key) && ($0.value["preferredAt"]?.double ?? 0) > 0 }
            .max { ($0.value["preferredAt"]?.double ?? 0) < ($1.value["preferredAt"]?.double ?? 0) }?.key
    }
    func tools(for id: UUID) -> [SuiteTool] {
        guard let stored = values[id]?["tools"] else { return Self.initial }
        return stored.array.compactMap { SuiteTool(rawValue: $0.string) }
    }
    func isHidden(_ id: UUID) -> Bool { values[id]?["hidden"]?.bool == true }
    func compact(_ id: UUID) -> Bool { values[id]?["compact"]?.bool ?? true }
    func name(_ record: SuiteConnection, store: AppStore? = nil) -> String {
        let override = values[record.id]?["name"]?.string ?? ""
        return override.isEmpty ? SuiteConnections.shared.name(record, store: store) : override
    }
    func set(_ tool: SuiteTool, for connection: UUID, enabled: Bool) {
        var selected = tools(for: connection).filter { $0 != tool }
        if enabled { selected.append(tool) }
        setTools(selected, for: connection)
    }
    func setTools(_ tools: [SuiteTool], for id: UUID) { update(id, "tools", .array(tools.map { .string($0.rawValue) })) }
    func hide(_ id: UUID, _ hidden: Bool) { update(id, "hidden", .bool(hidden)) }
    func setCompact(_ id: UUID, _ compact: Bool) { update(id, "compact", .bool(compact)) }
    func rename(_ id: UUID, _ name: String) { update(id, "name", .string(String(name.trimmingCharacters(in: .whitespacesAndNewlines).prefix(80)))) }
    func prefer(_ connection: UUID?) {
        for id in values.keys.filter({ $0 != connection && (values[$0]?["preferredAt"]?.double ?? 0) > 0 }) {
            update(id, "preferredAt", .number(0))
        }
        if let connection { update(connection, "preferredAt", .number(Date().timeIntervalSince1970 * 1000)) }
    }
    func request(tool: SuiteTool, connection: UUID, records: [SuiteConnection]) -> SuiteLaunchRequest? {
        guard !isHidden(connection), records.contains(where: { $0.id == connection }) else { return nil }
        return SuiteLaunchRequest(tool: tool, connection: connection)
    }

    private func update(_ id: UUID, _ key: String, _ value: JSON) {
        values[id, default: [:]][key] = value
        pending[id, default: [:]][key] = value
        persist()
        if let record = SuiteConnections.shared.values.first(where: { $0.id == id }) {
            Task { await synchronize(record) }
        }
    }
    private func persist() {
        guard !SuiteShortcutDemo.enabled else { return }
        defaults.set(try? JSONEncoder().encode(values), forKey: "suite.shortcuts.v2")
        defaults.set(try? JSONEncoder().encode(pending), forKey: "suite.shortcuts.pending.v2")
    }
    func synchronize(_ record: SuiteConnection) async {
        let id = record.id
        guard !SuiteShortcutDemo.enabled, syncing.insert(id).inserted else { return }
        defer { syncing.remove(id) }
        do {
            let api = try SuiteAPI(record, timeout: 8)
            var remote = try await api.request("shortcut-prefs", timeout: 8).object
            var migration = values[id, default: [:]].filter { remote[$0.key] == nil }
            if remote["name"] == nil, migration["name"] == nil, let name = record.displayName, !name.isEmpty { migration["name"] = .string(name) }
            pending[id, default: [:]].merge(migration) { existing, _ in existing }
            while let patch = pending[id], !patch.isEmpty {
                remote = try await api.request("shortcut-prefs", method: "POST", body: .object(patch), timeout: 8).object
                for (key, value) in patch where pending[id]?[key] == value { pending[id]?.removeValue(forKey: key) }
                persist()
            }
            values[id] = remote
            errors[id] = nil
            persist()
        } catch {
            if !RequestFailure.isCancellation(error) { errors[id] = error.localizedDescription }
        }
    }
}
