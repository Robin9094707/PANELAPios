import XCTest
@testable import Panel

/// 5.10.0: chat break (Chat-Ruhe) tool, texts and release notes.
@MainActor final class Panel5100Tests: XCTestCase {
    func testChatSilenceToolLaunchAndSearch() {
        let request = SuiteLaunchRequest(tool: .chatSilence, connection: UUID())
        XCTAssertEqual(SuiteLaunchRequest.parse(request.url)?.tool, .chatSilence)
        XCTAssertNotNil(SuiteShortcutTool(rawValue: SuiteTool.chatSilence.rawValue))
        XCTAssertEqual(SuiteTool.chatSilence.permission, "data.chat-silence")
        XCTAssertEqual(SuiteTool.chatSilence.feature, "chat-silence-v1")
        XCTAssertTrue(SuiteTool.chatSilence.matches("stummschalten"))
        XCTAssertTrue(SuiteTool.chatSilence.matches("erwähnungen"))
        XCTAssertEqual(Set(SuiteTool.allCases.map(\.id)).count, SuiteTool.allCases.count)
    }

    func testChatSilenceTexts() {
        let now = Date(timeIntervalSince1970: 1_000_000)
        XCTAssertEqual(SuiteChatSilenceText.remaining(active: false, until: 0, now: now), "Chat sichtbar")
        XCTAssertTrue(SuiteChatSilenceText.remaining(active: true, until: 0, now: now).hasPrefix("Dauerhaft"))
        XCTAssertEqual(SuiteChatSilenceText.remaining(active: true, until: (now.timeIntervalSince1970 + 3 * 3600) * 1000, now: now), "Noch 3 Std.")
        XCTAssertEqual(SuiteChatSilenceText.remaining(active: true, until: (now.timeIntervalSince1970 + 30 * 60) * 1000, now: now), "Noch 30 Min.")
        XCTAssertEqual(SuiteChatSilenceText.origin("self"), "vom Spieler selbst")
        XCTAssertEqual(SuiteChatSilenceText.origin("staff:Simplex Panel/Robin"), "vom Team (Robin)")
        XCTAssertEqual(SuiteChatSilenceText.presets.map(\.0), [30, 180, 1440, 0])
        let rows: [JSON] = [.object(["name": .string("Alpha"), "active": .bool(true)]), .object(["name": .string("Beta"), "active": .bool(false)])]
        XCTAssertEqual(SuiteChatSilenceText.filter(rows, activeOnly: true, search: "").count, 1)
        XCTAssertEqual(SuiteChatSilenceText.filter(rows, activeOnly: false, search: "be").first?["name"].string, "Beta")
    }

    func testReleaseNotes() {
        XCTAssertEqual(WhatsNew.release(for: "5.10.0")?.version, "5.10.0")
        XCTAssertGreaterThanOrEqual(WhatsNew.release(for: "5.10.0")?.items.count ?? 0, 6)
        XCTAssertTrue(WhatsNew.shouldPresent(current: "5.10.0", lastSeen: "5.9.0", existingUser: true))
    }
}
