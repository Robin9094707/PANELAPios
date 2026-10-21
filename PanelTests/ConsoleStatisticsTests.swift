import XCTest
@testable import Panel

final class ConsoleStatisticsTests: XCTestCase {
    func testCategoriesRespectLogHeadersAndDoNotTreatChatWordsAsErrorsOrJoins() {
        let cases: [(String, ConsoleCategory)] = [
            ("[12:00:00 INFO]: <.Demo> ERROR I joined the game", .chat),
            ("[12:00:00] [Server thread/INFO]: [Not Secure] <User> hello", .chat),
            ("\u{1B}[31m[12:00:01 ERROR]: Failure\u{1B}[0m", .errors),
            ("[Server thread/SEVERE]: Failure", .errors),
            ("    at example.Plugin.run(Plugin.java:42)", .errors),
            ("Caused by: java.lang.IllegalStateException: broken", .errors),
            ("[12:00:02 WARN]: Slow tick", .warnings),
            ("[12:00:03 INFO]: .Demo joined the game", .joins),
            ("[12:00:03 INFO]: Spieler hat das Spiel betreten", .joins),
            ("[12:00:04 INFO]: Demo left the game", .leaves),
            ("[12:00:04 INFO]: Spieler hat das Spiel verlassen", .leaves),
            ("[12:00:05 INFO]: Demo issued server command: /list", .commands),
            ("[12:00:06 INFO]: Loaded 42 plugins", .other)
        ]
        for (line, expected) in cases { XCTAssertEqual(ConsoleAnalysis.parse(line).category, expected, line) }
    }
    func testCategoryAndPlainTextSearchCombineWithoutLosingConsoleFormatting() async {
        let engine = ConsoleFilterEngine()
        let chat = "\u{1B}[32m[12:00:00 INFO]: <Alice> Hello\u{1B}[0m"
        let lines = [chat, "[12:00:01 ERROR]: Hello failure", "[12:00:02 INFO]: Bob joined the game"]
        let result = await engine.project(lines, category: .chat, query: "ALICE")
        XCTAssertEqual(result.text, chat); XCTAssertEqual(result.shown, 1); XCTAssertEqual(result.total, 3)
        let none = await engine.project(lines, category: .joins, query: "Alice")
        XCTAssertEqual(none.shown, 0)
        let trimmed = await engine.project(Array(lines.suffix(1)), category: .all, query: "")
        XCTAssertEqual(trimmed.shown, 1); XCTAssertFalse(trimmed.text.contains("Alice"))
        let empty = await engine.project([], category: .all, query: "")
        XCTAssertEqual(empty.text, ""); XCTAssertEqual(empty.total, 0)
    }
    @MainActor func testCoalescingStillPublishesDuringTrafficAndRejectsOldFilterResults() async throws {
        let projection = ConsoleProjection()
        var publishedDuringTraffic = false
        for index in 0..<12 {
            projection.request(["[12:00:00 INFO]: <User> value \(index)"], category: .all, query: "", context: 0)
            try await Task.sleep(for: .milliseconds(20))
            if !projection.result.text.isEmpty { publishedDuringTraffic = true }
        }
        XCTAssertTrue(publishedDuringTraffic)
        projection.request(["[12:00:01 ERROR]: obsolete"], category: .errors, query: "", context: 1)
        projection.request(["[12:00:02 INFO]: <User> current"], category: .chat, query: "current", context: 2)
        for _ in 0..<100 {
            if projection.result.text.contains("current") { break }
            try await Task.sleep(for: .milliseconds(20))
        }
        XCTAssertTrue(projection.result.text.contains("current"))
        XCTAssertFalse(projection.result.text.contains("obsolete"))
        projection.stop()
    }
    func testStatisticsDeduplicateProfilesAndUseOneCurrentDevicePerProfile() throws {
        let now = Date(timeIntervalSince1970: 2_000_000_000)
        let java = try JSON.from(["id": "j", "name": "Java", "client": "JAVA", "device": "Laptop", "online": true, "warnings": 2, "points": 10])
        let old = try JSON.from(["id": "b", "name": "OldName", "client": "BEDROCK", "device": "iPhone14,7"])
        let bedrock = try JSON.from(["id": "b", "name": "NewName", "client": "BEDROCK", "device": "iPhone14,7", "online": false, "lastSeen": now.addingTimeInterval(-100).timeIntervalSince1970 * 1000, "firstSeen": now.addingTimeInterval(-200).timeIntervalSince1970 * 1000, "warnings": 3, "points": 5, "ban": true])
        let metadata = try JSON.from(["os": "7", "input": "Keyboard", "language": "de_DE", "name": "iPhone14,7"])
        let result = SuiteStatisticsSnapshot(rows: [java, old, bedrock], devices: ["b": metadata], now: now)
        XCTAssertEqual(result.profiles.count, 2)
        XCTAssertEqual(result.online, 1); XCTAssertEqual(result.recent, 2); XCTAssertEqual(result.newProfiles, 1)
        XCTAssertEqual(result.warnings, 5); XCTAssertEqual(result.points, 15); XCTAssertEqual(result.bans, 1)
        XCTAssertEqual(result.groups[.models]?.count, 1)
        XCTAssertEqual(result.groups[.models]?.first?.title, "iPhone14,7")
        XCTAssertEqual(result.groups[.models]?.first?.fraction, 1)
        XCTAssertTrue(result.groups[.platforms]!.contains { $0.title == PlayerPlatform.windows.title && $0.members.first?["id"].string == "b" })
        XCTAssertTrue(result.groups[.platforms]!.contains { $0.title == "Java · Betriebssystem unbekannt" })
        XCTAssertEqual(result.groups[.inputs]?.first?.title, "Keyboard")
        XCTAssertEqual(result.groups[.languages]?.first?.members.count, 1)
    }
    func testRecordedHistoryReadsActualJavaPropertiesEscapesAndDoesNotFillGaps() throws {
        let now = Date(timeIntervalSince1970: 1_700_000_000)
        let minute = Int64(floor(now.timeIntervalSince1970 / 60) * 60000)
        let hourAgo = minute - 3_600_000
        let dayAgo = minute - 2 * 86_400_000
        let future = minute + 86_400_000
        let a = "00000000-0000-0000-0000-000000000001", b = "00000000-0000-0000-0000-000000000002"
        let text = "# Existing Suite data\nworld.example=\(a),\(b),\(a),invalid\nsamples=\(hourAgo + 1000)\\:4;\(hourAgo + 2000)\\:7;\\\n  \(dayAgo)\\:9;\(minute)\\:0;\(future)\\:42;bad;-1\\:5;123\\:-1\n"
        let history = try SuiteRecordedHistory.parse(text)
        XCTAssertEqual(history.worldPlayers, 2)
        XCTAssertEqual(history.samples.count, 4)
        XCTAssertEqual(history.peak(days: 1, now: now), 7)
        XCTAssertEqual(history.peak(days: 7, now: now), 9)
        let hourly = history.hourly(now: now)
        XCTAssertEqual(hourly.count, 2)
        XCTAssertTrue(hourly.contains { $0.online == 0 })
        XCTAssertFalse(hourly.contains { $0.online == 42 })
        XCTAssertThrowsError(try SuiteRecordedHistory.parse("unrelated=value"))
        XCTAssertThrowsError(try SuiteRecordedHistory.parse(String(repeating: "x", count: 2 * 1024 * 1024 + 1)))
    }
    func testReportedNumericPlatformsAndUnknownJavaOSAreHandledHonestly() throws {
        for (os, platform) in [("2", PlayerPlatform.ios), ("7", .windows), ("NX", .nintendoSwitch), ("13", .xbox)] {
            XCTAssertEqual(PlayerPlatform.resolve(try JSON.from(["os": os])), platform)
        }
        let rows = [try JSON.from(["id": "a", "client": "JAVA"]), try JSON.from(["id": "b", "client": "BEDROCK", "device": "Unknown"])]
        let snapshot = SuiteStatisticsSnapshot(rows: rows)
        XCTAssertEqual(snapshot.groups[.platforms]?.count, 2)
        XCTAssertEqual(snapshot.groups[.models]?.first?.title, "Nicht erfasst")
        XCTAssertEqual(snapshot.groups[.inputs]?.first?.title, "Noch nicht geladen")
    }
    @MainActor func testStatisticsLoadAllPagesAndKeepLastSuccessfulSnapshotOnFailure() async throws {
        let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [StubProtocol.self]
        let suite = try SuiteAPI(SuiteConnection(panel: "https://panel.invalid", server: "test", endpoint: "https://suite.invalid", fingerprint: "", serverIdentity: ""), token: "sas_synthetic", session: URLSession(configuration: config))
        let panel = PanelAPI(base: URL(string: "https://demo.invalid")!, key: "", demo: true)
        let server = Server(data: try JSON.from(["identifier": "test", "name": "Synthetic"]))
        var offsets: [String] = []
        StubProtocol.handler = { request in
            let offset = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)!.queryItems!.first { $0.name == "offset" }!.value!
            offsets.append(offset)
            let value: JSON = offset == "0" ? try JSON.from(["items": [["id": "a", "name": "A"], ["id": "b", "name": "B"]], "total": 3, "next": 100]) : try JSON.from(["items": [["id": "b", "name": "Updated"], ["id": "c", "name": "C"]], "total": 3, "next": -1])
            return (200, try JSONEncoder().encode(value))
        }
        defer { StubProtocol.handler = nil }
        let store = SuiteStatisticsStore()
        await store.load(suite: suite, panel: panel, server: server)
        XCTAssertEqual(offsets, ["0", "100"])
        XCTAssertTrue(store.complete); XCTAssertEqual(store.snapshot.profiles.count, 3)
        XCTAssertNotNil(store.history); XCTAssertNil(store.error)
        await store.load(suite: suite, panel: panel, server: server)
        XCTAssertEqual(offsets.count, 2, "Revisiting the tab reuses its fresh snapshot")
        StubProtocol.handler = { _ in (503, Data("{\"message\":\"synthetic unavailable\"}".utf8)) }
        await store.load(suite: suite, panel: panel, server: server, force: true)
        XCTAssertNotNil(store.error); XCTAssertEqual(store.snapshot.profiles.count, 3)
    }
    @MainActor func testStatisticsRejectNonAdvancingPagination() async throws {
        let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [StubProtocol.self]
        StubProtocol.handler = { _ in (200, Data("{\"items\":[],\"total\":1,\"next\":0}".utf8)) }
        defer { StubProtocol.handler = nil }
        let suite = try SuiteAPI(SuiteConnection(panel: "https://panel.invalid", server: "test", endpoint: "https://suite.invalid", fingerprint: "", serverIdentity: ""), token: "sas_synthetic", session: URLSession(configuration: config))
        let store = SuiteStatisticsStore()
        await store.load(suite: suite, panel: PanelAPI(base: URL(string: "https://demo.invalid")!, key: "", demo: true), server: Server(data: .object(["identifier": .string("test")])))
        XCTAssertNotNil(store.error); XCTAssertFalse(store.complete); XCTAssertNil(store.updated)
    }
}
