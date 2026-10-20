import XCTest
@testable import Panel

final class SuiteIntegrationTests: XCTestCase {
    func row(_ id: String, online: Bool, seen: Double) -> JSON {
        .object(["id": .string(id), "name": .string(id), "online": .bool(online), "lastSeen": .number(seen), "client": .string("JAVA")])
    }
    @MainActor func client() throws -> SuiteAPI {
        let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [StubProtocol.self]
        return try SuiteAPI(SuiteConnection(panel: "https://panel.example", server: "server", endpoint: "https://suite.example", fingerprint: "", serverIdentity: "server"), token: "fixture", session: URLSession(configuration: config))
    }
    @MainActor func testRecentOfflinePlayersUseAllPagesBeforeGlobalSorting() async throws {
        var offsets: [String] = []
        StubProtocol.handler = { request in
            let q = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)!.queryItems!
            XCTAssertEqual(q.first { $0.name == "q" }?.value, "Some Name")
            let offset = q.first { $0.name == "offset" }!.value!; offsets.append(offset)
            let items = offset == "0" ? [self.row("online", online: true, seen: 9000), self.row("old", online: false, seen: 1000)] : [self.row("newest", online: false, seen: 8000)]
            return (200, try JSONEncoder().encode(JSON.object(["items": .array(items), "total": .number(3), "next": .number(offset == "0" ? 100 : -1)])))
        }
        let directory = SuiteDirectory()
        await directory.load(suite: try client(), query: " Some Name ", filter: .recent, reset: true)
        XCTAssertEqual(offsets, ["0", "100"])
        XCTAssertEqual(SuitePlayerPresentation.sorted(directory.rows, filter: .recent).map { $0["id"].string }, ["newest", "old"])
        XCTAssertEqual(directory.next, -1); XCTAssertNil(directory.error)
    }
    @MainActor func testOnlineScanContinuesBeyondFirstHundredAndStopsAtOfflineRows() async throws {
        var calls = 0
        StubProtocol.handler = { _ in
            calls += 1
            let items = calls == 1 ? [self.row("one", online: true, seen: 1)] : [self.row("two", online: true, seen: 2), self.row("offline", online: false, seen: 3)]
            return (200, try JSONEncoder().encode(JSON.object(["items": .array(items), "total": .number(500), "next": .number(Double(calls * 100))])))
        }
        let directory = SuiteDirectory()
        await directory.load(suite: try client(), query: "", filter: .online, reset: true)
        XCTAssertEqual(calls, 2)
        XCTAssertEqual(SuitePlayerPresentation.sorted(directory.rows, filter: .online).count, 2)
    }
    @MainActor func testCancelledRefreshIsSilentButAuthenticationFailureIsVisible() async throws {
        let api = try client(); let directory = SuiteDirectory()
        StubProtocol.handler = { _ in throw URLError(.cancelled) }
        await directory.load(suite: api, query: "", filter: .all, reset: true)
        XCTAssertNil(directory.error); XCTAssertFalse(directory.busy)
        StubProtocol.handler = { _ in (401, Data(#"{"message":"Schlüssel widerrufen"}"#.utf8)) }
        await directory.load(suite: api, query: "", filter: .all, reset: true)
        XCTAssertTrue(directory.error?.contains("Schlüssel widerrufen") == true)
    }
    @MainActor func testPanelCancellationDoesNotPublishWorkspaceAlert() async throws {
        let defaults = UserDefaults(suiteName: "Cancellation-" + UUID().uuidString)!
        let store = AppStore(defaults: defaults, launchDemo: false)
        let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [StubProtocol.self]
        store.api = PanelAPI(base: URL(string: "https://panel.example")!, key: "fixture", session: URLSession(configuration: config))
        StubProtocol.handler = { _ in throw URLError(.cancelled) }
        await store.refresh()
        XCTAssertNil(store.error); XCTAssertFalse(store.loading)
    }
    func testProfileMergeKeepsUUIDIdentityAndDeviceLabelsNeverInventHardware() {
        let old = row("same", online: false, seen: 1), new = row("same", online: true, seen: 2)
        XCTAssertEqual(SuitePlayerPresentation.merge([old], [new, new]), [new])
        XCTAssertEqual(SuitePlayerPresentation.device(new), "Java-Client")
        XCTAssertEqual(SuitePlayerPresentation.device(.object(["client": .string("BEDROCK")])), "Gerät nicht erfasst")
        XCTAssertNil(SuitePlayerPresentation.date(.number(0)))
        XCTAssertEqual(SuitePlayerPresentation.date(.number(1000))?.timeIntervalSince1970, 1)
    }
}
