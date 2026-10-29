import XCTest
import CryptoKit
@testable import Panel

/// Panel 3.1.0: tickets, persistent chat, crossplay status and smarter plugin updates.
final class Panel310Tests: XCTestCase {
    func testChatHistoryKeepsFiftyAndSurvivesReload() {
        let domain = "Chat-" + UUID().uuidString; let defaults = UserDefaults(suiteName: domain)!; defer { defaults.removePersistentDomain(forName: domain) }
        let rows = (1...70).map { JSON.object(["id": .string(String($0)), "at": .number(Double($0)), "text": .string("m\($0)")]) }
        let merged = SuiteChatHistory.merge([], with: rows)
        XCTAssertEqual(merged.count, SuiteChatHistory.limit); XCTAssertEqual(merged.last?["text"].string, "m70")
        XCTAssertEqual(SuiteChatHistory.merge(merged, with: Array(rows.suffix(3))).count, 50, "duplicates are ignored")
        let history = SuiteChatHistory(defaults: defaults)
        history.save(.init(rows: merged, cursor: "70", epoch: "e1"), scope: "server")
        let restored = SuiteChatHistory(defaults: defaults).load("server")
        XCTAssertEqual(restored.rows.count, 50); XCTAssertEqual(restored.cursor, "70"); XCTAssertEqual(restored.epoch, "e1")
        history.clear("server"); XCTAssertTrue(history.load("server").rows.isEmpty)
    }
    func testTicketAlertsAndLabels() {
        let urgent = SuiteAdminDashboard.alerts(.object(["tickets": .object(["open": .number(3), "urgent": .number(1)])]))
        XCTAssertEqual(urgent.first?.kind, .tickets); XCTAssertEqual(urgent.first?.critical, true); XCTAssertEqual(urgent.first?.title, "1 Notfall-Ticket")
        let normal = SuiteAdminDashboard.alerts(.object(["tickets": .object(["open": .number(2), "answered": .number(1)])]))
        XCTAssertEqual(normal.first?.title, "2 offene Tickets"); XCTAssertEqual(normal.first?.critical, false)
        XCTAssertTrue(SuiteAdminDashboard.alerts(.object(["tickets": .null])).isEmpty, "older suites without tickets stay calm")
        XCTAssertEqual(SuiteTicketText.priority("urgent"), "Notfall"); XCTAssertEqual(SuiteTicketText.status("answered"), "Beantwortet")
        XCTAssertEqual(SuiteTicketText.priorities, ["low", "normal", "high", "urgent"])
    }
    func testCrossplayStatusFromHashes() {
        XCTAssertEqual(SuiteCrossplayStatus.of(.object(["installed": .bool(false)])), .missing)
        XCTAssertEqual(SuiteCrossplayStatus.of(.object(["installed": .bool(true), "latestSha256": .string("a"), "upToDate": .bool(true)])), .current)
        XCTAssertEqual(SuiteCrossplayStatus.of(.object(["installed": .bool(true), "latestSha256": .string("a"), "updateStaged": .bool(true)])), .staged)
        XCTAssertEqual(SuiteCrossplayStatus.of(.object(["installed": .bool(true), "latestSha256": .string("a")])), .update)
        XCTAssertEqual(SuiteCrossplayStatus.of(.object(["installed": .bool(true)])), .unknown)
    }
    func testProvidersAndGeyserDetection() throws {
        XCTAssertEqual(MarketplaceClient.geyserProject("Geyser-Spigot"), "geyser")
        XCTAssertEqual(MarketplaceClient.geyserProject("floodgate"), "floodgate")
        XCTAssertNil(MarketplaceClient.geyserProject("GeyserSkinManager"))
        XCTAssertEqual(MarketplaceClient.geyserPlatform(AddonTarget(game: "26.3", loader: "paper")), "spigot")
        XCTAssertEqual(MarketplaceClient.geyserPlatform(AddonTarget(game: "26.3", loader: "fabric")), "fabric")
        XCTAssertFalse(AddonProvider.available(for: .mod).contains(.spigot)); XCTAssertFalse(AddonProvider.available(for: .mod).contains(.hangar))
        XCTAssertNotNil("my-plugin.v2".range(of: AddonProvider.hangar.idPattern, options: .regularExpression))
        XCTAssertNil("other".range(of: AddonProvider.geysermc.idPattern, options: .regularExpression))
        // Caches written by 3.0.0 (no sha256 field) still decode, so mappings survive the update.
        let legacy = #"{"target":{"game":"26.3","loader":"paper","beta":false},"links":{"Lands.jar":{"provider":"spigot","project":"53313"}},"inventory":{},"statuses":{"A.jar":{"text":"Update verfügbar","checked":0,"release":{"id":"1","title":"A","provider":"modrinth","version":"2","filename":"A.jar","website":"https://modrinth.com","notes":"","compatibility":true,"dependencies":[]}}}}"#
        let cache = try JSONDecoder().decode(AddonCache.self, from: Data(legacy.utf8))
        XCTAssertEqual(cache.links["Lands.jar"]?.project, "53313"); XCTAssertNil(cache.statuses["A.jar"]?.release?.sha256)
    }
    func testSha256VerificationForGeyserAndHangar() throws {
        let data = Data([0x50, 0x4b] + Array(repeating: 7, count: 64))
        var release = AddonRelease(id: "1", title: "Geyser", provider: .geysermc, version: "2.9 · Build 900", filename: "Geyser-Spigot.jar", download: "https://download.geysermc.org/x", website: "https://geysermc.org", sha1: nil, sha512: nil, notes: "", compatibility: true, published: nil, dependencies: [])
        release.sha256 = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
        XCTAssertNoThrow(try MarketplaceClient.verify(data, release: release))
        XCTAssertThrowsError(try MarketplaceClient.verify(data + Data([1]), release: release))
    }
    func testWhatsNew310() {
        XCTAssertEqual(WhatsNew.release(for: "3.1.0")?.version, "3.1.0")
        XCTAssertTrue(WhatsNew.shouldPresent(current: "3.1.0", lastSeen: "3.0.0", existingUser: true))
    }
}

final class CrossplayNetworkTests: XCTestCase {
    override func tearDown() { StubProtocol.handler = nil; super.tearDown() }
    func client() -> MarketplaceClient { let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [StubProtocol.self]; return MarketplaceClient(session: URLSession(configuration: config)) }
    func testGeyserIsMappedAutomaticallyAndComparedBySha256() async throws {
        let installed = Data("installed-geyser".utf8)
        StubProtocol.handler = { request in
            XCTAssertEqual(request.url?.host, "download.geysermc.org")
            XCTAssertEqual(request.url?.path, "/v2/projects/geyser/versions/latest/builds/latest")
            return (200, Data(#"{"version":"2.9.0","build":950,"time":"2026-10-01T00:00:00Z","changes":[{"summary":"Support 26.3"}],"downloads":{"spigot":{"name":"Geyser-Spigot.jar","sha256":"abc123"}}}"#.utf8))
        }
        let item = AddonInventory(filename: "Geyser-Spigot.jar", signature: "s", name: "Geyser-Spigot", version: "2.8.0-SNAPSHOT", fingerprint: .make(installed))
        let (mapping, status) = try await client().latest(item, link: nil, target: AddonTarget(game: "26.3"))
        XCTAssertEqual(mapping, AddonLink(provider: .geysermc, project: "geyser"))
        XCTAssertEqual(status.release?.sha256, "abc123"); XCTAssertEqual(status.release?.filename, "Geyser-Spigot.jar")
        XCTAssertEqual(status.release?.download, "https://download.geysermc.org/v2/projects/geyser/versions/2.9.0/builds/950/downloads/spigot")
        StubProtocol.handler = { _ in (200, Data(("{\"version\":\"2.9.0\",\"build\":950,\"downloads\":{\"spigot\":{\"sha256\":\"" + item.fingerprint.sha256 + "\"}}}").utf8)) }
        let (_, current) = try await client().latest(item, link: nil, target: AddonTarget(game: "26.3"))
        XCTAssertNil(current.release); XCTAssertTrue(current.text.hasPrefix("Aktuell"))
    }
    func testHangarFiltersReleaseChannelAndGameVersion() async throws {
        StubProtocol.handler = { request in
            XCTAssertEqual(request.url?.host, "hangar.papermc.io")
            let body = #"{"result":[{"id":3,"name":"3.0-beta","author":"dev","channel":{"name":"Beta"},"platformDependencies":{"PAPER":["26.3"]},"downloads":{"PAPER":{"fileInfo":{"name":"P.jar","sha256Hash":"b"},"downloadUrl":"https://hangarcdn.papermc.io/b.jar"}}},{"id":2,"name":"2.0","author":"dev","channel":{"name":"Release"},"platformDependencies":{"PAPER":["26.3","26.2"]},"downloads":{"PAPER":{"fileInfo":{"name":"P-2.0.jar","sha256Hash":"c"},"downloadUrl":"https://hangarcdn.papermc.io/c.jar"}},"pluginDependencies":{"PAPER":[{"name":"Vault","required":true}]}}]}"#
            return (200, Data(body.utf8))
        }
        let item = AddonInventory(filename: "P.jar", signature: "s", name: "P", version: "1.0", fingerprint: .make(Data("x".utf8)))
        let (_, status) = try await client().latest(item, link: AddonLink(provider: .hangar, project: "P"), target: AddonTarget(game: "26.3"))
        XCTAssertEqual(status.release?.version, "2.0"); XCTAssertEqual(status.release?.sha256, "c"); XCTAssertEqual(status.release?.dependencies, ["Erforderlich: Vault"])
    }
    func testFreeSpigotUsesSpigetCdnWithoutLogin() async throws {
        StubProtocol.handler = { request in
            if request.url!.path.hasSuffix("versions/latest") { return (200, Data(#"{"id":22,"name":"2.0","releaseDate":12345}"#.utf8)) }
            return (200, Data(#"{"id":100,"name":"Free","premium":false,"external":false,"file":{"type":".jar"},"testedVersions":["26.3"]}"#.utf8))
        }
        let item = AddonInventory(filename: "Free.jar", signature: "s", name: "Free", version: "1.0", fingerprint: .make(Data()))
        let (_, status) = try await client().latest(item, link: AddonLink(provider: .spigot, project: "100"), target: AddonTarget(game: "26.3"))
        XCTAssertEqual(status.release?.download, "https://api.spiget.org/v2/resources/100/download")
    }
}
