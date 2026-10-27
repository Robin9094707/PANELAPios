import XCTest
@testable import Panel

final class AdminSuiteTests: XCTestCase {
    func testIPhoneIPadAndJavaRemainDistinct() {
        XCTAssertEqual(PlayerPlatform.resolve(.object(["device": .string("iPad Pro"), "os": .string("iOS")])), .ipad)
        XCTAssertEqual(PlayerPlatform.resolve(.object(["device": .string("iPhone 17"), "os": .string("iOS")])), .ios)
        XCTAssertEqual(PlayerPlatform.resolve(.object(["client": .string("JAVA"), "device": .string("iPad")])), .java)
        XCTAssertNotEqual(PlayerPlatform.ipad.symbol, PlayerPlatform.ios.symbol)
    }

    func testSuiteAddressAndPinRejectInsecureOrAmbiguousInput() throws {
        XCTAssertEqual(try SuiteAPI.endpoint("https://host.example:39093/v1").absoluteString, "https://host.example:39093/")
        for bad in ["http://host:39093", "https://key@host", "https://host?q=key", "https://host/#pin"] { XCTAssertThrowsError(try SuiteAPI.endpoint(bad)) }
        let pin = String(repeating: "ab", count: 32)
        XCTAssertEqual(try SuiteAPI.pin(pin.uppercased().map(String.init).joined(separator: ":")), pin)
        XCTAssertThrowsError(try SuiteAPI.pin("abcd")); XCTAssertThrowsError(try SuiteAPI.pin(String(repeating: "z", count: 64)))
    }
    @MainActor func testConnectionsRemainScopedAndRoundTripWithoutTokenInDefaults() throws {
        let domain = "SuiteTests-" + UUID().uuidString; let defaults = UserDefaults(suiteName: domain)!; defer { defaults.removePersistentDomain(forName: domain) }
        let store = SuiteConnections(defaults: defaults)
        let panel = PanelAPI(base: URL(string: "https://panel.example")!, key: "panel-secret")
        let server = Server(data: .object(["identifier": .string("server1")]))
        let connection = SuiteConnection(panel: panel.base.absoluteString, server: server.id, endpoint: "https://api.example", fingerprint: "", serverIdentity: "server1")
        try store.save(connection, token: "synthetic-suite-key")
        defer { store.remove(connection) }
        XCTAssertEqual(SuiteConnections(defaults: defaults).get(panel, server), connection)
        XCTAssertNil(store.get(panel, Server(data: .object(["identifier": .string("server2")]))))
        let otherPanel = PanelAPI(base: URL(string: "https://other.example")!, key: "different")
        XCTAssertNil(store.get(otherPanel, server))
        XCTAssertFalse(String(data: defaults.data(forKey: "adminSuite.connections.v1")!, encoding: .utf8)!.contains("synthetic-suite-key"))
        store.moderation(connection, enabled: false)
        XCTAssertFalse(try XCTUnwrap(SuiteConnections(defaults: defaults).get(panel, server)).useForModeration)
    }
    @MainActor func client(identity: String = "server1") throws -> SuiteAPI {
        let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [StubProtocol.self]
        let connection = SuiteConnection(panel: "https://panel.example", server: "server1", endpoint: "https://api.example/control", fingerprint: "", serverIdentity: identity)
        return try SuiteAPI(connection, token: "synthetic-suite-token", session: URLSession(configuration: config))
    }
    @MainActor func testStructuredOperationsUseAuthenticatedReadEndpoints() async throws {
        var visited: [String] = []
        StubProtocol.handler = { request in
            XCTAssertEqual(request.httpMethod, "GET")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer synthetic-suite-token")
            visited.append(request.url!.lastPathComponent)
            return (200, Data(#"{"items":[],"pending":2,"profiles":3}"#.utf8))
        }
        let api = try client()
        for name in ["overview", "statistics", "worlds", "plugins", "log-status"] { _ = try await api.data(name) }
        XCTAssertEqual(visited, ["overview", "statistics", "worlds", "plugins", "log-status"])
    }
    @MainActor func testPrepareChecksIdentityAndExecuteSendsOnlySingleUseTicket() async throws {
        var visited: [String] = []
        StubProtocol.handler = { request in
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer synthetic-suite-token")
            XCTAssertFalse(request.url!.absoluteString.contains("synthetic-suite-token"))
            visited.append(request.url!.path)
            switch request.url!.lastPathComponent {
            case "health": return (200, Data(#"{"serverId":"server1","apiVersion":1,"scope":"manage"}"#.utf8))
            case "prepare": XCTAssertEqual(request.httpMethod, "POST"); return (200, Data(#"{"ticket":"single-use-ticket","summary":"Ban TadMC_YT","expiresIn":60}"#.utf8))
            case "execute": XCTAssertEqual(request.httpMethod, "POST"); return (200, Data(#"{"ok":true,"title":"Suite","description":"Gesendet"}"#.utf8))
            default: XCTFail("Unexpected route"); return (404, Data())
            }
        }
        let api = try client(); let plan = try await api.prepare("player.punish", values: ["player": .string("TadMC_YT"), "type": .string("ban")]); XCTAssertEqual(plan["ticket"].string, "single-use-ticket"); XCTAssertEqual(api.scope, "manage")
        let result = try await api.execute(plan["ticket"].string); XCTAssertTrue(result["ok"].bool)
        XCTAssertEqual(visited, ["/control/v1/health", "/control/v1/prepare", "/control/v1/execute"])
    }
    @MainActor func testChangedServerIdentityStopsBeforeMutationAndNoFallback() async throws {
        var calls = 0
        StubProtocol.handler = { request in calls += 1; XCTAssertEqual(request.url!.lastPathComponent, "health"); return (200, Data(#"{"serverId":"another-server","apiVersion":1,"scope":"full"}"#.utf8)) }
        do { _ = try await client().prepare("security.ultraban", values: ["player": .string("Test")]); XCTFail("Wrong server accepted") } catch { XCTAssertTrue(error.localizedDescription.contains("Serveridentität")) }
        XCTAssertEqual(calls, 1)
    }
    func testSuiteRedirectsNeverForwardSecrets() {
        let delegate = SuiteTransport(pin: "", host: "api.example")
        let session = URLSession(configuration: .ephemeral); defer { session.invalidateAndCancel() }
        let response = HTTPURLResponse(url: URL(string: "https://api.example/v1/health")!, statusCode: 302, httpVersion: nil, headerFields: nil)!
        var called = false
        delegate.urlSession(session, task: session.dataTask(with: response.url!), willPerformHTTPRedirection: response, newRequest: URLRequest(url: URL(string: "https://evil.example")!)) { result in called = true; XCTAssertNil(result) }
        XCTAssertTrue(called)
    }
}
