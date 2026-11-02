import XCTest
@testable import Panel

/// Focused 4.2.0 checks: rank text cleanup, confirmation summaries, admin-log filter texts and release notes.
@MainActor final class Panel420Tests: XCTestCase {
    func testRankPrefixesAreShownWithoutColourCodes() {
        XCTAssertEqual(SuiteRankText.plain("&c[Admin] "), "[Admin]")
        XCTAssertEqual(SuiteRankText.plain("§4§lOwner"), "Owner")
        XCTAssertEqual(SuiteRankText.plain("<red>Mod</red>"), "Mod")
        XCTAssertEqual(SuiteRankText.plain("{#ff8800}VIP"), "VIP")
    }
    func testConfirmationSummaryIsPlainText() {
        XCTAssertEqual(SuiteConfirmRunner.plain("**LuckPerms-Änderung**\n`/lp group admin setweight 90`"), "LuckPerms-Änderung\n/lp group admin setweight 90")
    }
    func testAdminLogFilterModes() {
        XCTAssertEqual(SuiteAdminLogFilterView.modes.map(\.0), ["staff", "selected", "all", "none"])
        XCTAssertTrue(SuiteAdminLogFilterView.explain("staff").contains("Team"))
        XCTAssertTrue(SuiteAdminLogFilterView.explain("none").contains("Kein Zähler"))
    }
    func testNodeContextOnlyForSingleContexts() {
        XCTAssertEqual(SuiteNodeRow.context(.object(["context": .string("world=farm")])), "world=farm")
        XCTAssertEqual(SuiteNodeRow.context(.object(["context": .string("server=lobby, world=farm")])), "")
    }
    func testReleaseNotesFor420() {
        XCTAssertEqual(WhatsNew.release(for: "4.2.0")?.version, "4.2.0")
        XCTAssertTrue(WhatsNew.shouldPresent(current: "4.2.0", lastSeen: "4.1.0", existingUser: true))
    }
}
