import XCTest
@testable import Panel

/// 5.7.0: small checks for the new tools, automation drafts and release notes.
@MainActor final class Panel570Tests: XCTestCase {
    func testNewToolsLaunchAndAreSearchable() {
        for tool in [SuiteTool.locker, .smartRestart, .automations] {
            XCTAssertEqual(SuiteLaunchRequest.parse(SuiteLaunchRequest(tool: tool).url)?.tool, tool)
            XCTAssertNotNil(SuiteShortcutTool(rawValue: tool.rawValue))
            XCTAssertFalse(tool.feature.isEmpty)
        }
        XCTAssertTrue(SuiteTool.locker.matches("gestohlen"))
        XCTAssertTrue(SuiteTool.automations.matches("wenn dann"))
        XCTAssertEqual(Set(SuiteTool.allCases.map(\.id)).count, SuiteTool.allCases.count)
    }

    func testAutomationDraftRoundTripAndTemplates() {
        var draft = SuiteAutomationDraft()
        draft.name = "Markttag"; draft.trigger = "game-day"; draft.world = "world"; draft.day = 3; draft.everyDays = 7
        draft.actions = [.init(type: "broadcast", value: "Hallo", en: "Hello")]
        let json = draft.json
        XCTAssertEqual(json["trigger"]["everyDays"].int, 7)
        XCTAssertNil(json["trigger"].object["time"])
        let restored = SuiteAutomationDraft(.object(json.object.merging(["id": .string("r1")]) { a, _ in a }))
        XCTAssertEqual(restored.ruleID, "r1")
        XCTAssertEqual(restored.actions.first?.en, "Hello")
        XCTAssertNotEqual(restored.id, draft.id)
        XCTAssertEqual(SuiteAutomationDraft.templates(world: "world").count, 4)
        XCTAssertEqual(SuiteAutomationText.trigger(.object(["type": .string("daily"), "time": .string("20:00")])), "Täglich um 20:00 Uhr")
    }

    func testLockerStatusAndReleaseNotes() {
        XCTAssertEqual(SuiteItemLockerView.statusText(.object(["status": .string("returned"), "toName": .string("Alex"), "resolvedBy": .string("Robin")])), "An Alex zurückgegeben · Robin")
        XCTAssertEqual(WhatsNew.releases.first?.version, "5.7.0")
        XCTAssertTrue(WhatsNew.shouldPresent(current: "5.7.0", lastSeen: "5.6.0", existingUser: true))
    }
}
