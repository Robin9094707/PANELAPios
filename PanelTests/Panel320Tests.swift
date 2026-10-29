import XCTest
import UIKit
@testable import Panel

/// Panel 3.2.0: Bark levels per ticket priority, crossplay fallback, GitHub releases, ticket drafts.
final class Panel320Tests: XCTestCase {
    override func tearDown() { StubProtocol.handler = nil; super.tearDown() }
    func client() -> MarketplaceClient { let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [StubProtocol.self]; return MarketplaceClient(session: URLSession(configuration: config)) }
    func testTicketBarkDefaultsFollowPriority() {
        XCTAssertEqual(SuiteBarkSettingsView.defaultLevel("ticket-low", level: "active", critical: "critical"), "passive")
        XCTAssertEqual(SuiteBarkSettingsView.defaultLevel("ticket", level: "active", critical: "critical"), "active")
        XCTAssertEqual(SuiteBarkSettingsView.defaultLevel("ticket-high", level: "active", critical: "critical"), "timeSensitive")
        XCTAssertEqual(SuiteBarkSettingsView.defaultLevel("ticket-urgent", level: "active", critical: "critical"), "critical")
        XCTAssertEqual(SuiteBarkSettingsView.defaultLevel("join", level: "passive", critical: "critical"), "passive")
    }
    func testCrossplayFallbackFillsMissingBuildFromTheApp() async {
        StubProtocol.handler = { request in
            XCTAssertEqual(request.url?.host, "download.geysermc.org")
            return (200, Data(#"{"version":"2.9.1","build":960,"changes":[{"summary":"Fix"}],"downloads":{"spigot":{"sha256":"ABC"}}}"#.utf8))
        }
        let server = JSON.object(["items": .array([
            .object(["id": .string("geyser"), "installed": .bool(true), "sha256": .string("abc"), "error": .string("GeyserMC nicht erreichbar")]),
            .object(["id": .string("floodgate"), "installed": .bool(true), "sha256": .string("old"), "latestSha256": .string("new")])
        ])])
        let completed = await SuiteCrossplayCard.complete(server, client: client())
        let geyser = completed["items"].array[0], floodgate = completed["items"].array[1]
        XCTAssertEqual(geyser["latestSha256"].string, "abc"); XCTAssertTrue(geyser["upToDate"].bool); XCTAssertEqual(geyser["error"], .null)
        XCTAssertEqual(SuiteCrossplayStatus.of(geyser), .current)
        XCTAssertEqual(floodgate["latestSha256"].string, "new", "rows the suite already resolved stay untouched")
    }
    func testGitHubReleasePicksMatchingJarAndDigest() async throws {
        StubProtocol.handler = { request in
            XCTAssertEqual(request.url?.host, "api.github.com"); XCTAssertEqual(request.url?.path, "/repos/owner/Tool/releases")
            let body = #"[{"id":2,"draft":false,"prerelease":true,"tag_name":"v3.0-beta","assets":[]},{"id":1,"draft":false,"prerelease":false,"tag_name":"v2.1.0","name":"Tool 2.1","html_url":"https://github.com/owner/Tool/releases/tag/v2.1.0","body":"Notes","published_at":"2026-10-01T00:00:00Z","assets":[{"name":"Tool-2.1.0.jar","browser_download_url":"https://github.com/owner/Tool/releases/download/v2.1.0/Tool-2.1.0.jar","digest":"sha256:DEF"},{"name":"Tool-2.1.0-sources.jar","browser_download_url":"https://x","digest":"sha256:1"},{"name":"Other-2.1.0.jar","browser_download_url":"https://y","digest":"sha256:2"}]}]"#
            return (200, Data(body.utf8))
        }
        let item = AddonInventory(filename: "Tool.jar", signature: "s", name: "Tool", version: "2.0.0", fingerprint: .make(Data("x".utf8)))
        let (_, status) = try await client().latest(item, link: AddonLink(provider: .github, project: "owner/Tool"), target: AddonTarget(game: "26.3"))
        XCTAssertEqual(status.release?.version, "v2.1.0"); XCTAssertEqual(status.release?.sha256, "def"); XCTAssertEqual(status.release?.filename, "Tool-2.1.0.jar")
        XCTAssertEqual(status.release?.compatibility, false, "GitHub releases are never batch-staged without a manual check")
        XCTAssertNotNil("owner/Tool".range(of: AddonProvider.github.idPattern, options: .regularExpression))
        XCTAssertNil("owner".range(of: AddonProvider.github.idPattern, options: .regularExpression))
    }
    func testDraftDownscaleKeepsAspectRatio() {
        let image = UIGraphicsImageRenderer(size: CGSize(width: 4000, height: 2000)).image { context in UIColor.red.setFill(); context.fill(CGRect(x: 0, y: 0, width: 4000, height: 2000)) }
        let scaled = SuiteTicketDraft.downscale(image, maxPixel: 2400)
        XCTAssertEqual(max(scaled.size.width * scaled.scale, scaled.size.height * scaled.scale), 2400, accuracy: 1)
        XCTAssertEqual(scaled.size.width / scaled.size.height, 2, accuracy: 0.01)
        XCTAssertTrue(SuiteTicketDetailView.isMissing(PanelError(message: "Ticket #4 nicht gefunden")))
        XCTAssertFalse(SuiteTicketDetailView.isMissing(PanelError(message: "Zeitüberschreitung")))
    }
    func testWhatsNew320() { XCTAssertEqual(WhatsNew.release(for: "3.2.0")?.version, "3.2.0") }
}
