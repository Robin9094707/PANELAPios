import XCTest
@testable import Panel

@MainActor final class Panel490Tests: XCTestCase {
    func testPermanentShortcutRoundTripAndValidation() {
        let connection = UUID()
        let request = SuiteLaunchRequest(tool: .advertising, connection: connection, place: "place_123", prepareAd: true)
        let parsed = SuiteLaunchRequest.parse(request.url)
        XCTAssertEqual(parsed?.tool, .advertising)
        XCTAssertEqual(parsed?.connection, connection)
        XCTAssertEqual(parsed?.place, "place_123")
        XCTAssertEqual(parsed?.prepareAd, true)
        XCTAssertFalse(request.url.absoluteString.contains("token"))
        for bad in ["https://suite?tool=advertising", "panel://suite?tool=unknown", "panel://suite?connection=bad", "panel://suite?place=..%2Fsecret", "panel://suite?tool=tickets&tool=advertising"] {
            XCTAssertNil(SuiteLaunchRequest.parse(URL(string: bad)!), bad)
        }
        XCTAssertEqual(SuiteLaunchRequest.parse(URL(string: "panel://suite")!)?.tool, .overview)
    }
    func testSearchAndCompleteDirectory() {
        XCTAssertTrue(SuiteTool.advertising.matches("werbung"))
        XCTAssertTrue(SuiteTool.advertising.matches("kurzbefehle werben"))
        XCTAssertTrue(SuiteTool.luckperms.matches("range"))
        XCTAssertTrue(SuiteSearch.matches("SERVER log", in: "Serverchat · Log"))
        XCTAssertFalse(SuiteSearch.matches("server missing", in: "Serverchat"))
        for tool in [SuiteTool.advertising, .histories, .appSettings, .settings, .worldTrim, .discordStatus, .scoreboard, .access, .account, .bark] {
            XCTAssertFalse(tool.title.isEmpty)
            XCTAssertFalse(tool.symbol.isEmpty)
        }
        XCTAssertEqual(Set(SuiteTool.allCases.map(\.id)).count, SuiteTool.allCases.count)
    }
    func testShortcutQueuesNavigationAndHistoryLabels() {
        let inbox = SuiteNavigationInbox()
        XCTAssertFalse(inbox.open(URL(string: "panel://ad-post")!))
        XCTAssertTrue(inbox.open(SuiteLaunchRequest(tool: .advertising).url))
        XCTAssertEqual(inbox.request?.tool, .advertising)
        XCTAssertEqual(SuiteHistoryText.title(.object(["action": .string("Teleport"), "text": .string("Details")])), "Teleport")
        XCTAssertEqual(SuiteHistoryText.detail(.object(["author": .string("Admin"), "body": .string("Nachricht")])), "Admin · Nachricht")
        let place = SuiteAdvertisingPlace(id: UUID().uuidString + "|test_1", name: "Werbung")
        XCTAssertEqual(place.target?.1, "test_1")
        XCTAssertNil(SuiteAdvertisingPlace(id: "invalid|test", name: "Fehler").target)
        XCTAssertNotNil(WhatsNew.release(for: "4.9.0"))
    }
}
