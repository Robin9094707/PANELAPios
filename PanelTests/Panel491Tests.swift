import XCTest
@testable import Panel

private final class ShortcutProtocol: URLProtocol {
    static var handler: ((URLRequest) throws -> (Int, JSON)?)?
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        do {
            guard let (status, value) = try Self.handler?(request) else { return }
            let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: ["Content-Type": "application/json"])!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: try JSONEncoder().encode(value)); client?.urlProtocolDidFinishLoading(self)
        } catch { client?.urlProtocol(self, didFailWithError: error) }
    }
    override func stopLoading() { }
}

@MainActor final class Panel491Tests: XCTestCase {
    let health: JSON = .object(["apiVersion": .number(1), "serverId": .string("fixture"), "scope": .string("manage"), "limited": .bool(true), "permissions": .array([.string("data.discord-ads")]), "features": .array([.string("ad-hub-v1")])])
    func record(_ host: String = "working.invalid") -> SuiteConnection {
        SuiteConnection(panel: "https://panel.invalid", server: UUID().uuidString, endpoint: "https://" + host, fingerprint: "", serverIdentity: "fixture")
    }
    func api(_ record: SuiteConnection) throws -> SuiteAPI {
        let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [ShortcutProtocol.self]
        return try SuiteAPI(record, token: "synthetic", session: URLSession(configuration: config))
    }
    override func tearDown() { ShortcutProtocol.handler = nil; super.tearDown() }

    func testLegacyConnectionAndPerServerPreferencesPreserveCredentialsAndTargets() throws {
        let name = "Panel491-" + UUID().uuidString, defaults = UserDefaults(suiteName: name)!
        defer { defaults.removePersistentDomain(forName: name) }
        let first = record(), second = record("second.invalid")
        let old = try JSONEncoder().encode([first, second]); defaults.set(old, forKey: "adminSuite.connections.v1")
        let connections = SuiteConnections(defaults: defaults), choices = SuiteQuickAccess(defaults: defaults)
        XCTAssertEqual(connections.values.map(\.id), [first.id, second.id])
        choices.set(.diagnostics, for: first.id, enabled: true); choices.prefer(second.id)
        let restored = SuiteQuickAccess(defaults: defaults)
        XCTAssertTrue(restored.tools(for: first.id).contains(.diagnostics))
        XCTAssertFalse(restored.tools(for: second.id).contains(.diagnostics))
        XCTAssertEqual(restored.preferred, second.id)
        let target = restored.request(tool: .advertising, connection: first.id, records: connections.values)
        XCTAssertEqual(SuiteLaunchRequest.parse(target!.url)?.connection, first.id)
        XCTAssertNil(restored.request(tool: .advertising, connection: first.id, records: [second]))
        XCTAssertEqual(defaults.data(forKey: "adminSuite.connections.v1"), old)
        let data = try JSONSerialization.jsonObject(with: old) as! [[String: Any]]
        let legacy = try JSONSerialization.data(withJSONObject: data.map { $0.filter { $0.key != "displayName" } })
        XCTAssertEqual(try JSONDecoder().decode([SuiteConnection].self, from: legacy).map(\.id), [first.id, second.id])
        connections.rename(first, name: "Simplex SMP")
        XCTAssertEqual(SuiteConnections(defaults: defaults).values.first?.displayName, "Simplex SMP")
        XCTAssertEqual(connections.values.first?.fingerprint, first.fingerprint)
    }

    func testAdvertisingOpensWithoutWaitingForSchema() async throws {
        var paths: [String] = []; let health = self.health
        ShortcutProtocol.handler = { request in paths.append(request.url!.lastPathComponent); return request.url!.lastPathComponent == "health" ? (200, health) : (503, .null) }
        let model = SuiteWorkspaceLoadModel(makeAPI: api)
        await model.load(record(), tool: .advertising)
        XCTAssertNotNil(model.api); XCTAssertFalse(model.loading); XCTAssertNil(model.error)
        XCTAssertEqual(paths, ["health"])
        XCTAssertTrue(model.api!.can("data.discord-ads")); XCTAssertFalse(model.api!.can("data.access"))
    }

    func testFailedSchemaLeavesVerifiedNativeToolsUsable() async throws {
        let health = self.health
        ShortcutProtocol.handler = { request in request.url!.lastPathComponent == "health" ? (200, health) : (503, .object(["message": .string("fixture schema failure")])) }
        let model = SuiteWorkspaceLoadModel(makeAPI: api)
        await model.load(record())
        XCTAssertNotNil(model.api); XCTAssertNotNil(model.notice); XCTAssertFalse(model.loading)
        XCTAssertEqual(model.api?.scope, "manage")
    }

    func testTimeoutAndCancellationAlwaysEndSpinnerAndRetryWorks() async throws {
        ShortcutProtocol.handler = { _ in nil }
        let model = SuiteWorkspaceLoadModel(timeout: 0.05, makeAPI: api), connection = record()
        let started = Date()
        await model.load(connection, tool: .advertising)
        XCTAssertLessThan(Date().timeIntervalSince(started), 2)
        XCTAssertFalse(model.loading); XCTAssertNil(model.api); XCTAssertNotNil(model.error)
        let health = self.health; ShortcutProtocol.handler = { _ in (200, health) }
        await model.load(connection, tool: .advertising)
        XCTAssertNotNil(model.api); XCTAssertNil(model.error)
        ShortcutProtocol.handler = { _ in nil }
        let task = Task { await model.load(connection, tool: .advertising) }
        try await Task.sleep(for: .milliseconds(10)); task.cancel(); await task.value
        XCTAssertFalse(model.loading); XCTAssertNotNil(model.error)
    }

    func testChangingServerCannotPublishThePreviousConnection() async throws {
        let health = self.health
        ShortcutProtocol.handler = { request in request.url?.host == "stalled.invalid" ? nil : (200, health) }
        let model = SuiteWorkspaceLoadModel(makeAPI: api), stalled = record("stalled.invalid"), next = record()
        let old = Task { await model.load(stalled, tool: .advertising) }
        try await Task.sleep(for: .milliseconds(10))
        await model.load(next, tool: .advertising); await old.value
        XCTAssertEqual(model.api?.connection.id, next.id); XCTAssertFalse(model.loading); XCTAssertNil(model.error)
    }

    func testMissingKeyOrIdentityMismatchShowsErrorWithoutSpinner() async throws {
        let missing = SuiteWorkspaceLoadModel(makeAPI: { _ in throw PanelError(message: "synthetic missing key") })
        await missing.load(record()); XCTAssertFalse(missing.loading); XCTAssertNotNil(missing.error)
        ShortcutProtocol.handler = { _ in (200, .object(["apiVersion": .number(1), "serverId": .string("different-server")])) }
        let mismatch = SuiteWorkspaceLoadModel(makeAPI: api)
        await mismatch.load(record()); XCTAssertNil(mismatch.api); XCTAssertNotNil(mismatch.error); XCTAssertFalse(mismatch.loading)
    }

    func testShortHealthDeadlineIsAttachedToRequest() async throws {
        var interval: TimeInterval = 0; let health = self.health
        ShortcutProtocol.handler = { request in interval = request.timeoutInterval; return (200, health) }
        _ = try await api(record()).health(timeout: 0.25)
        XCTAssertEqual(interval, 0.25, accuracy: 0.001)
    }
}
