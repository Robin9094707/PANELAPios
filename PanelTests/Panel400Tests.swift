import XCTest
@testable import Panel

/// Focused 4.0.0 checks: setup links, new widgets and release notes.
@MainActor final class Panel400Tests: XCTestCase {
    func testSetupLinkParsesOnlyCompleteHTTPSLinks() {
        let link = SuiteSetupLink.parse("panel://setup?e=https%3A%2F%2Fplay.example.com%3A39093&f=" + String(repeating: "a", count: 64) + "&c=sass_abc123&u=Freundin&n=Simplex%20SMP")
        XCTAssertEqual(link?.endpoint, "https://play.example.com:39093")
        XCTAssertEqual(link?.username, "freundin")
        XCTAssertEqual(link?.name, "Simplex SMP")
        XCTAssertEqual(link?.code, "sass_abc123")
        XCTAssertNil(SuiteSetupLink.parse("panel://setup?e=http%3A%2F%2Fplain&c=sass_x"), "plain HTTP is rejected")
        XCTAssertNil(SuiteSetupLink.parse("panel://setup?e=https%3A%2F%2Fa.example&c=sas_wrong"), "only one-time setup codes")
        XCTAssertNil(SuiteSetupLink.parse("https://example.com/setup?c=sass_x"), "only the panel:// scheme")
    }
    func testSetupInboxOpensOnlySetupLinks() {
        let inbox = SuiteSetupInbox()
        XCTAssertFalse(inbox.open(URL(string: "panel://suite-events")!))
        XCTAssertTrue(inbox.open(URL(string: "panel://setup?e=https%3A%2F%2Fa.example%3A1&f=&c=sass_1&u=a&n=b")!))
        XCTAssertEqual(inbox.link?.code, "sass_1")
    }
    func testNewWidgetsAreAvailableAndOldLayoutsStayValid() {
        XCTAssertTrue(DashboardCard.available(.home).contains(.suitePulse))
        XCTAssertTrue(DashboardCard.available(.home).contains(.quickMenu))
        XCTAssertTrue(DashboardCard.available(.server).contains(.quickActions))
        XCTAssertTrue(DashboardCard.available(.server).contains(.combinedHistory))
        XCTAssertEqual(DashboardCard.normalize(["welcome", "servers", "unknown"], scope: .home), [.welcome, .servers], "saved 3.x layouts keep working")
        XCTAssertFalse(DashboardCard.normalize(["quickActions"], scope: .home).contains(.quickActions), "server widgets never leak onto the home screen")
    }
    func testReleaseNotesFor400() {
        XCTAssertEqual(WhatsNew.release(for: "4.0.0")?.version, "4.0.0")
        XCTAssertTrue(WhatsNew.shouldPresent(current: "4.0.0", lastSeen: "3.3.0", existingUser: true))
    }
    func testAdminLogPresentation() {
        XCTAssertEqual(SuiteAdminLogText.symbol(.object(["action": .string("Teleport"), "category": .string("admin")])), "location.fill")
        XCTAssertEqual(SuiteAdminLogText.symbol(.object(["action": .string("Vanish"), "category": .string("vanish")])), "eye.slash.fill")
        XCTAssertEqual(SuiteAdminLogText.location(.object(["world": .string("world"), "x": .number(10), "y": .number(64), "z": .number(-3)])), "world · 10 / 64 / -3")
        XCTAssertNil(SuiteAdminLogText.location(.null))
    }
}
