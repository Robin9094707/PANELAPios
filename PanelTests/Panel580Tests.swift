import XCTest
@testable import Panel

/// 5.8.0: small checks for the quick center tiles and the new tool.
@MainActor final class Panel580Tests: XCTestCase {
    func testQuickTilesRoundTripAndDefaults() {
        var tile = SuiteQuickTile(kind: .restart)
        XCTAssertEqual(tile.value, "60")
        tile.title = "Neustart jetzt"
        let restored = SuiteQuickTile(tile.json)
        XCTAssertEqual(restored, tile)
        XCTAssertNil(SuiteQuickTile(.object(["kind": .string("unknown")])))
        XCTAssertTrue(SuiteQuickTile(kind: .announce).confirmation.contains("Ankündigung"))
        XCTAssertEqual(SuiteQuickKind.suggestions.count, 8)
        XCTAssertEqual(Set(SuiteQuickKind.allCases.map(\.rawValue)).count, SuiteQuickKind.allCases.count)
    }

    func testQuickCenterToolAndReleaseNotes() {
        XCTAssertEqual(SuiteLaunchRequest.parse(SuiteLaunchRequest(tool: .quickCenter).url)?.tool, .quickCenter)
        XCTAssertNotNil(SuiteShortcutTool(rawValue: "quickCenter"))
        XCTAssertTrue(WhatsNew.releases.contains { $0.version == "5.8.0" })
        XCTAssertTrue(WhatsNew.shouldPresent(current: "5.8.0", lastSeen: "5.7.0", existingUser: true))
    }

    func testInventoryPickEquality() {
        XCTAssertEqual(SuiteInvPick(side: 1, slot: 39), SuiteInvPick(side: 1, slot: 39))
        XCTAssertNotEqual(SuiteInvPick(side: 0, slot: 39), SuiteInvPick(side: 1, slot: 39))
        XCTAssertFalse(SuiteInvSide(player: "x").loaded)
    }
}
