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
    @MainActor func testFirstPageIsBoundedAndCachedUntilExplicitRefresh() async throws {
        var calls = 0
        StubProtocol.handler = { _ in calls += 1; return (200, try JSONEncoder().encode(JSON.object(["items": .array([self.row(calls == 1 ? "old" : "new", online: false, seen: Double(calls))]), "total": .number(2500), "next": .number(50), "snapshot": .string("page-token")]))) }
        defer { StubProtocol.handler = nil }
        let api = try client(), directory = SuiteDirectory()
        await directory.load(suite: api, query: "", filter: .all, reset: true)
        await directory.load(suite: api, query: "", filter: .all, reset: true)
        XCTAssertEqual(calls, 1); XCTAssertEqual(directory.rows.first?["id"].string, "old"); XCTAssertEqual(directory.next, 50)
        await directory.load(suite: api, query: "", filter: .all, reset: true, force: true)
        XCTAssertEqual(calls, 2); XCTAssertEqual(directory.rows.first?["id"].string, "new")
    }
    @MainActor func testFailedRefreshPreservesRowsAndSessionCacheIsScoped() async throws {
        let api = try client(), directory = SuiteDirectory()
        StubProtocol.handler = { _ in (200, try JSONEncoder().encode(JSON.object(["items": .array([self.row("stable", online: false, seen: 1)]), "total": .number(1), "next": .number(-1)]))) }
        defer { StubProtocol.handler = nil }
        await directory.load(suite: api, query: "", filter: .all, reset: true)
        StubProtocol.handler = { _ in (500, Data()) }
        await directory.load(suite: api, query: "", filter: .all, reset: true, force: true)
        XCTAssertEqual(directory.rows.first?["id"].string, "stable"); XCTAssertNotNil(directory.error)
        XCTAssertTrue(SuiteDirectory.shared(key: "test-one") === SuiteDirectory.shared(key: "test-one"))
        XCTAssertFalse(SuiteDirectory.shared(key: "test-one") === SuiteDirectory.shared(key: "test-two"))
    }
    @MainActor func testRecentUsesServerFilterAndLoadsOnlyOnePageThenSnapshotContinuation() async throws {
        var offsets: [String] = []
        StubProtocol.handler = { request in
            let q = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)!.queryItems!
            XCTAssertEqual(q.first { $0.name == "q" }?.value, "Some Name")
            XCTAssertEqual(q.first { $0.name == "filter" }?.value, "recent")
            XCTAssertEqual(q.first { $0.name == "limit" }?.value, "50")
            let offset = q.first { $0.name == "offset" }!.value!; offsets.append(offset)
            if offset != "0" { XCTAssertEqual(q.first { $0.name == "snapshot" }?.value, "stable-token") }
            let items = [self.row(offset == "0" ? "newest" : "older", online: false, seen: 1000)]
            return (200, try JSONEncoder().encode(JSON.object(["items": .array(items), "total": .number(2500), "next": .number(offset == "0" ? 50 : -1), "snapshot": .string("stable-token")])))
        }
        defer { StubProtocol.handler = nil }
        let directory = SuiteDirectory(), api = try client()
        await directory.load(suite: api, query: " Some Name ", filter: .recent, reset: true)
        XCTAssertEqual(offsets, ["0"]); XCTAssertEqual(directory.rows.count, 1)
        await directory.load(suite: api, query: " Some Name ", filter: .recent, reset: false)
        XCTAssertEqual(offsets, ["0", "50"]); XCTAssertEqual(directory.rows.map { $0["id"].string }, ["newest", "older"])
    }
    @MainActor func testOnlineNeverScansOfflinePagesAndFilterSwitchRestoresCache() async throws {
        var filters: [String] = []
        StubProtocol.handler = { request in
            let filter = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)!.queryItems!.first { $0.name == "filter" }!.value!
            filters.append(filter)
            return (200, try JSONEncoder().encode(JSON.object(["items": .array([self.row(filter, online: filter == "online", seen: 1)]), "total": .number(2500), "next": .number(50)])))
        }
        defer { StubProtocol.handler = nil }
        let directory = SuiteDirectory(), api = try client()
        await directory.load(suite: api, query: "", filter: .online, reset: true)
        XCTAssertEqual(filters, ["online"])
        await directory.load(suite: api, query: "", filter: .recent, reset: true)
        await directory.load(suite: api, query: "", filter: .online, reset: true)
        XCTAssertEqual(filters, ["online", "recent"]); XCTAssertEqual(directory.rows.first?["id"].string, "online")
    }
    func testGermanTimeUsesBerlinIncludingDaylightSaving() {
        XCTAssertTrue(SuiteGermanTime.text(Date(timeIntervalSince1970: 0)).contains("01:00:00"))
        // July 2026 midnight UTC must become 02:00 in Germany, regardless of phone timezone.
        XCTAssertTrue(SuiteGermanTime.text(Date(timeIntervalSince1970: 1782864000)).contains("02:00:00"))
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
