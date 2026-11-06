import Foundation
import Combine

/// An absolute deadline also bounds slow responses that keep sending small amounts of data.
enum SuiteNavigationDeadline {
    @MainActor static func run<T: Sendable>(seconds: TimeInterval, operation: @escaping @MainActor () async throws -> T) async throws -> T {
        try Task.checkCancellation()
        return try await withThrowingTaskGroup(of: T.self) { group in
            group.addTask { @MainActor in try await operation() }
            group.addTask {
                try await Task.sleep(for: .seconds(seconds))
                throw URLError(.timedOut)
            }
            defer { group.cancelAll() }
            guard let result = try await group.next() else { throw CancellationError() }
            try Task.checkCancellation()
            return result
        }
    }
}

@MainActor final class SuiteWorkspaceLoadModel: ObservableObject {
    @Published private(set) var api: SuiteAPI?
    @Published private(set) var actions: [SuiteAction] = []
    @Published private(set) var loading = false
    @Published private(set) var error: String?
    @Published private(set) var notice: String?
    private var attempt = UUID()
    private var operation: Task<Void, Never>?
    private let timeout: TimeInterval
    private let makeAPI: @MainActor (SuiteConnection) throws -> SuiteAPI
    init(timeout: TimeInterval = 12, makeAPI: (@MainActor (SuiteConnection) throws -> SuiteAPI)? = nil) {
        self.timeout = timeout; self.makeAPI = makeAPI ?? { try SuiteShortcutDemo.api($0) }
    }
    func load(_ connection: SuiteConnection, tool: SuiteTool = .overview) async {
        operation?.cancel()
        let id = UUID(); attempt = id
        api = nil; actions = []; error = nil; notice = nil; loading = true
        let task = Task { @MainActor [weak self] in
            guard let self else { return }
            defer { if self.attempt == id { self.loading = false } }
            do {
                let client = try self.makeAPI(connection)
                _ = try await SuiteNavigationDeadline.run(seconds: self.timeout) { try await client.health(timeout: self.timeout) }
                try Task.checkCancellation()
                guard self.attempt == id else { return }
                self.api = client; self.loading = false
                // Advertising, tickets, logs and other native tools do not depend on /schema.
                if [.overview, .functions, .operations, .management, .players, .reports, .bans, .alts].contains(tool) {
                    await self.loadSchema(client: client, attempt: id)
                }
            } catch {
                guard self.attempt == id else { return }
                self.api = nil
                self.error = RequestFailure.isCancellation(error) ? "Verbindung abgebrochen. Du kannst es erneut versuchen." : Self.message(error)
            }
        }
        operation = task
        await withTaskCancellationHandler { await task.value } onCancel: { task.cancel() }
    }
    func cancel() {
        attempt = UUID(); operation?.cancel(); operation = nil
        if loading { loading = false; error = "Verbindung abgebrochen. Du kannst es erneut versuchen." }
    }
    func reloadSchema() async {
        guard let api else { return }
        await loadSchema(client: api, attempt: attempt)
    }
    private func loadSchema(client: SuiteAPI, attempt id: UUID) async {
        do {
            let result = try await SuiteNavigationDeadline.run(seconds: min(timeout, 8)) { try await client.request("schema", timeout: min(self.timeout, 8)) }
            try Task.checkCancellation()
            guard attempt == id, api === client else { return }
            actions = result["actions"].array.map { SuiteAction(data: $0) }; notice = nil
        } catch {
            guard attempt == id, !RequestFailure.isCancellation(error) else { return }
            notice = "Die Verbindung steht. Zusätzliche Funktionen konnten noch nicht geladen werden; die übrigen Werkzeuge bleiben nutzbar."
        }
    }
    private static func message(_ error: Error) -> String {
        if (error as? URLError)?.code == .timedOut { return "Zeitlimit erreicht. Die Admin Suite antwortet gerade nicht. Bitte Verbindung, Serveradresse und Port prüfen und erneut versuchen." }
        return error.localizedDescription
    }
}

/// Explicit opt-in UI-test fixture. Never changes saved connections, the keychain or production routing.
enum SuiteShortcutDemo {
    static var enabled: Bool {
        let args = ProcessInfo.processInfo.arguments
        return args.contains("--demo") && args.contains("--demo-suite-shortcuts")
    }
    static let connections: [SuiteConnection] = [
        SuiteConnection(id: UUID(uuidString: "49100000-0000-0000-0000-000000000001")!, panel: "https://demo.panel", server: "simplex1", endpoint: "https://smp.demo.invalid", fingerprint: "", serverIdentity: "synthetic-suite", displayName: "Simplex SMP"),
        SuiteConnection(id: UUID(uuidString: "49100000-0000-0000-0000-000000000002")!, panel: "https://demo.panel", server: "simplex2", endpoint: "https://test.demo.invalid", fingerprint: "", serverIdentity: "synthetic-suite", displayName: "Simplex Test")
    ]
    @MainActor static func api(_ connection: SuiteConnection) throws -> SuiteAPI {
        let demo = enabled && connections.contains { $0.id == connection.id }
        if demo && connection.id == connections[1].id && ProcessInfo.processInfo.arguments.contains("--demo-suite-fail-second") {
            throw PanelError(message: "Synthetischer Testserver nicht erreichbar. Erneut versuchen ist möglich.")
        }
        return try SuiteAPI(connection, token: demo ? "" : nil, demo: demo)
    }
}
