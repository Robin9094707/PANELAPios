import XCTest
import os
@testable import Panel

/// Panel 3.0.0: admin center, player management and the update splash.
final class AdminCenterTests: XCTestCase {
    func testWhatsNewShowsOnceAfterUpdateAndSkipsFreshInstalls() {
        XCTAssertNotNil(WhatsNew.release(for: "3.0.0"))
        XCTAssertEqual(WhatsNew.release(for: "3.0.4")?.version, "3.0.0", "patch releases reuse the minor release notes")
        XCTAssertTrue(WhatsNew.shouldPresent(current: "3.0.0", lastSeen: "", existingUser: true), "update from 2.9.0 (no marker yet)")
        XCTAssertFalse(WhatsNew.shouldPresent(current: "3.0.0", lastSeen: "", existingUser: false), "fresh install")
        XCTAssertTrue(WhatsNew.shouldPresent(current: "3.0.0", lastSeen: "2.9.0", existingUser: true))
        XCTAssertFalse(WhatsNew.shouldPresent(current: "3.0.0", lastSeen: "3.0.0", existingUser: true), "already seen")
        XCTAssertFalse(WhatsNew.shouldPresent(current: "3.0.0", lastSeen: "3.1.0", existingUser: true), "downgrade")
        XCTAssertFalse(WhatsNew.shouldPresent(current: "9.9.9", lastSeen: "", existingUser: true), "no notes for unknown version")
        XCTAssertEqual(WhatsNew.compare("2.10.0", "2.9.0"), .orderedDescending)
        XCTAssertEqual(WhatsNew.compare("3.0", "3.0.0"), .orderedSame)
    }
    func testBundledVersionHasReleaseNotes() {
        XCTAssertNotNil(WhatsNew.release(for: WhatsNew.current), "every shipped version needs a What's New entry")
    }
    @MainActor func testBookmarksArePerSuiteBoundedAndPersistent() {
        let domain = "Bookmarks-" + UUID().uuidString; let defaults = UserDefaults(suiteName: domain)!; defer { defaults.removePersistentDomain(forName: domain) }
        let store = SuitePlayerBookmarks(defaults: defaults)
        store.toggleFavorite("a", id: "1", name: "Zed"); store.toggleFavorite("a", id: "2", name: "Alex"); store.toggleFavorite("b", id: "1", name: "Zed")
        XCTAssertEqual(store.favorites("a").map(\.name), ["Alex", "Zed"])
        XCTAssertTrue(store.isFavorite("b", "1")); XCTAssertFalse(store.isFavorite("b", "2"))
        store.toggleFavorite("b", id: "1", name: "Zed"); XCTAssertTrue(store.favorites("b").isEmpty)
        for index in 0..<12 { store.visited("a", id: String(index), name: "P\(index)") }
        XCTAssertEqual(store.recent("a").count, SuitePlayerBookmarks.recentLimit)
        XCTAssertEqual(store.recent("a").first?.name, "P11")
        store.visited("a", id: "1", name: "Zed2")
        XCTAssertEqual(store.recent("a").first?.id, "1"); XCTAssertEqual(store.favorites("a").first { $0.id == "1" }?.name, "Zed2", "favorite follows renames")
        let reloaded = SuitePlayerBookmarks(defaults: defaults)
        XCTAssertEqual(reloaded.favorites("a").count, 2); XCTAssertEqual(reloaded.recent("a").first?.name, "Zed2")
        reloaded.clearRecent("a"); XCTAssertTrue(reloaded.recent("a").isEmpty); XCTAssertEqual(reloaded.favorites("a").count, 2)
    }
    func testPlayerFiltersKeepLegacyParameters() {
        XCTAssertEqual(SuitePlayerFilter.primary.map(\.parameter), ["all", "online", "recent"])
        XCTAssertEqual(SuitePlayerFilter.extended.map(\.parameter), ["flagged", "new"])
        let online = JSON.object(["online": .bool(true)]), offline = JSON.object(["online": .bool(false)])
        XCTAssertTrue(SuitePlayerFilter.online.includes(online)); XCTAssertFalse(SuitePlayerFilter.online.includes(offline))
        XCTAssertTrue(SuitePlayerFilter.recent.includes(offline)); XCTAssertTrue(SuitePlayerFilter.flagged.includes(online)); XCTAssertTrue(SuitePlayerFilter.new.includes(offline))
        let newest = JSON.object(["name": .string("B"), "firstSeen": .number(200), "online": .bool(false)]), older = JSON.object(["name": .string("A"), "firstSeen": .number(100), "online": .bool(true)])
        XCTAssertEqual(SuitePlayerPresentation.sorted([older, newest], filter: .new).map { $0["name"].string }, ["B", "A"])
        XCTAssertEqual(SuitePlayerPresentation.sorted([newest, older], filter: .all).map { $0["name"].string }, ["A", "B"], "online first stays unchanged")
    }
    func testDashboardAlertsAndLegacyAssembly() {
        let legacy = SuiteAdminDashboard.legacy(overview: .object(["online": .number(2), "maxPlayers": .number(20), "tps": .number(19.5)]),
            players: .object(["items": .array([.object(["id": .string("p"), "name": .string("Alex"), "online": .bool(true)])])]),
            restart: .object(["active": .bool(true), "remaining": .number(12)]), maintenance: .object(["maintenance": .bool(true), "reason": .string("Update")]),
            reports: .object(["items": .array([.object(["id": .string("1"), "status": .string("OPEN")]), .object(["id": .string("2"), "status": .string("RESOLVED")]), .object(["id": .string("3"), "status": .string("IN_REVIEW")])])]))
        XCTAssertEqual(legacy["players"].array.count, 1); XCTAssertEqual(legacy["online"].int, 2); XCTAssertTrue(legacy["legacy"].bool)
        XCTAssertEqual(legacy["moderation"]["openReports"].int, 1); XCTAssertEqual(legacy["moderation"]["reviewReports"].int, 1); XCTAssertEqual(legacy["reports"].array.count, 2)
        let alerts = SuiteAdminDashboard.alerts(legacy)
        XCTAssertEqual(alerts.map(\.kind), [.restart, .maintenance, .reports])
        XCTAssertEqual(alerts.first?.detail, "In 12 Sekunden"); XCTAssertEqual(alerts[1].detail, "Update")
        XCTAssertTrue(SuiteAdminDashboard.alerts(.object(["moderation": .object(["openReports": .number(0)])])).isEmpty)
        let review = SuiteAdminDashboard.alerts(.object(["moderation": .object(["reviewReports": .number(2)])]))
        XCTAssertEqual(review.first?.title, "2 Reports in Bearbeitung"); XCTAssertEqual(review.first?.critical, false)
        XCTAssertEqual(SuiteAdminDashboard.tpsLevel(20), 0); XCTAssertEqual(SuiteAdminDashboard.tpsLevel(16), 1); XCTAssertEqual(SuiteAdminDashboard.tpsLevel(9), 2); XCTAssertEqual(SuiteAdminDashboard.tpsLevel(0), -1)
        XCTAssertEqual(SuiteAdminDashboard.ramFraction(.object(["ramUsed": .number(1), "ramMax": .number(4)])), 0.25); XCTAssertNil(SuiteAdminDashboard.ramFraction(.null))
    }
    func testQuickActionsFollowPlayerStateAndAvailableActions() {
        let actions = [SuiteAction.make("player.punish", "Strafen"), SuiteAction.make("player.message", "Nachricht")]
        let online = SuitePlayerQuickActions.make(actions, player: "u1", row: .object(["online": .bool(true)])).map { $0.title }
        XCTAssertEqual(online, ["Nachricht", "Kicken", "Verwarnen", "Zeitweise muten", "Zeitbann", "Bannen"])
        let banned = SuitePlayerQuickActions.make(actions, player: "u1", row: .object(["online": .bool(false), "ban": .bool(true), "mute": .bool(true)]))
        XCTAssertEqual(banned.map { $0.title }, ["Nachricht", "Verwarnen", "Entmuten", "Entbannen"])
        XCTAssertEqual(banned.last?.item.preset["type"], .string("unban")); XCTAssertEqual(banned.last?.item.preset["player"], .string("u1"))
        XCTAssertTrue(SuitePlayerQuickActions.make([], player: "u1", row: .null).isEmpty, "no action is offered without the suite schema")
        XCTAssertEqual(SuitePlayerBadges.badges(.object(["ban": .bool(true), "warnings": .number(2), "staff": .string("Moderator")])).map(\.title), ["Gebannt", "2 Warnungen", "Team"])
    }
    @MainActor func testDemoSuiteServesAggregatedDashboardAndNewFilters() async throws {
        let record = SuiteConnection(panel: "https://demo.invalid", server: "simplex1", endpoint: "https://suite.demo.invalid", fingerprint: "", serverIdentity: "synthetic-suite")
        let suite = try SuiteAPI(record, token: "", demo: true)
        _ = try await suite.health()
        XCTAssertTrue(suite.features.contains("admin-dashboard-v1")); XCTAssertTrue(suite.features.contains("players-filters-v3"))
        let center = SuiteAdminCenterModel(); await center.load(suite)
        XCTAssertNil(center.error); XCTAssertEqual(center.dashboard["moderation"]["openReports"].int, 1); XCTAssertEqual(center.dashboard["players"].array.count, 2)
        let directory = SuiteDirectory()
        await directory.load(suite: suite, query: "", filter: .flagged, reset: true)
        XCTAssertEqual(directory.rows.map { $0["name"].string }, [".BedrockDemo"])
        await directory.load(suite: suite, query: "", filter: .new, reset: true)
        XCTAssertEqual(directory.rows.map { $0["name"].string }, ["RecentOffline"])
        XCTAssertEqual(suite.bookmarkScope, "synthetic-suite")
    }
    @MainActor func testOlderSuiteWithoutDashboardFeatureIsAssembledFromExistingEndpoints() async throws {
        let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [StubProtocol.self]
        var paths: [String] = []
        let lock = NSLock()
        StubProtocol.handler = { request in
            let path = request.url!.path; lock.lock(); paths.append(path); lock.unlock()
            switch path {
            case "/v1/health": return (200, Data(#"{"apiVersion":1,"serverId":"old-suite","version":"9.2.0","scope":"read","features":["overview","restart-v1"]}"#.utf8))
            case "/v1/data/overview": return (200, Data(#"{"online":1,"maxPlayers":10,"tps":20,"profiles":5}"#.utf8))
            case "/v1/data/players": return (200, Data(#"{"items":[{"id":"x","name":"Old","online":true}],"total":1,"next":-1}"#.utf8))
            case "/v1/data/operations": return (200, Data(#"{"active":false,"remaining":0}"#.utf8))
            default: return (404, Data(#"{"error":"API-Endpunkt nicht gefunden."}"#.utf8))
            }
        }
        defer { StubProtocol.handler = nil }
        let suite = try SuiteAPI(SuiteConnection(panel: "https://panel.invalid", server: "old", endpoint: "https://suite.invalid", fingerprint: "", serverIdentity: ""), token: "sas_synthetic", session: URLSession(configuration: config))
        _ = try await suite.health()
        let center = SuiteAdminCenterModel(); await center.load(suite)
        XCTAssertNil(center.error); XCTAssertTrue(center.dashboard["legacy"].bool); XCTAssertEqual(center.dashboard["players"].array.first?["name"].string, "Old")
        XCTAssertEqual(center.dashboard["restart"]["active"], .bool(false))
        lock.lock(); let requested = paths; lock.unlock()
        XCTAssertFalse(requested.contains("/v1/data/admin-dashboard"), "9.2.0 suites are never asked for the new endpoint")
        XCTAssertFalse(requested.contains("/v1/data/reports"), "modules not advertised are not requested")
    }
    @MainActor func testOlderSuiteReportCountsFollowPagination() async throws {
        let reportRequests = OSAllocatedUnfairLock(initialState: 0)
        let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [StubProtocol.self]
        StubProtocol.handler = { request in
            let components = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)!
            switch components.path {
            case "/v1/health": return (200, Data(#"{"apiVersion":1,"serverId":"old-suite","version":"9.2.0","scope":"read","features":["overview","moderation-center-v1"]}"#.utf8))
            case "/v1/data/overview": return (200, Data(#"{"online":0,"maxPlayers":10}"#.utf8))
            case "/v1/data/players": return (200, Data(#"{"items":[],"total":0,"next":-1}"#.utf8))
            case "/v1/data/reports":
                reportRequests.withLock { $0 += 1 }
                let offset = Int(components.queryItems?.first { $0.name == "offset" }?.value ?? "0") ?? 0
                let status = offset == 0 ? "OPEN" : "IN_REVIEW"
                let items = (0..<100).map { #"{"id":"\#(offset + $0)","status":"\#(status)"}"# }.joined(separator: ",")
                return (200, Data(#"{"items":[\#(items)],"next":\#(offset == 0 ? 100 : -1),"total":200}"#.utf8))
            default: return (404, Data("{}".utf8))
            }
        }
        defer { StubProtocol.handler = nil }
        let suite = try SuiteAPI(SuiteConnection(panel: "https://panel.invalid", server: "old", endpoint: "https://suite.invalid", fingerprint: "", serverIdentity: ""), token: "sas_synthetic", session: URLSession(configuration: config))
        _ = try await suite.health()
        let center = SuiteAdminCenterModel(); await center.load(suite)
        XCTAssertEqual(center.dashboard["moderation"]["openReports"].int, 100); XCTAssertEqual(center.dashboard["moderation"]["reviewReports"].int, 100, "second page is counted")
        XCTAssertFalse(center.dashboard["reportsPartial"].bool)
        // Polling within a minute reuses the counted reports instead of walking all pages again.
        reportRequests.withLock { $0 = 0 }
        await center.load(suite)
        XCTAssertEqual(reportRequests.withLock { $0 }, 0); XCTAssertEqual(center.dashboard["moderation"]["reviewReports"].int, 100)
        center.now = { Date().addingTimeInterval(SuiteAdminCenterModel.reportCacheSeconds + 1) }
        await center.load(suite)
        XCTAssertEqual(reportRequests.withLock { $0 }, 2, "stale cache walks both pages again")
        XCTAssertEqual(SuiteAdminDashboard.alerts(.object(["reportsPartial": .bool(true), "moderation": .object(["openReports": .number(1)])])).first?.title, "1+ offene Reports")
        XCTAssertEqual(SuiteAdminDashboard.alerts(.object(["moderation": .object(["internalBans": .number(1), "ultrabans": .number(1), "warnedPlayers": .number(1)])])).map(\.title), ["1 aktiver Suite-Bann", "1 aktiver Ultrabann", "1 Spieler mit Warnung"])
    }
}
