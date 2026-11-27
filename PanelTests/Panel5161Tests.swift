import XCTest
@testable import Panel

/// 5.16.1: one tap opens one tool, no tool is listed twice, full texts.
@MainActor final class Panel5161Tests: XCTestCase {
    func testNestedToolsAreNotListedTwice() {
        let diagnostics: Set<String> = ["diagnostics-v1", "ban-center-v1"]
        for tool in [SuiteTool.lagMap, .spark, .crashes, .console, .bans] {
            XCTAssertTrue(SuiteToolCatalog.nested(tool, features: diagnostics, live: true), tool.rawValue)
        }
        XCTAssertFalse(SuiteToolCatalog.nested(.bans, features: [], live: true), "without the Bann-Zentrale the old ban list stays")
        XCTAssertFalse(SuiteToolCatalog.nested(.lagMap, features: [], live: true), "older Suites keep the Lag-Karte in the grid")
        XCTAssertTrue(SuiteToolCatalog.nested(.statistics, features: [], live: false), "without live data statistics equal Serverbetrieb")
        XCTAssertFalse(SuiteToolCatalog.nested(.statistics, features: [], live: true))
        for tool in [SuiteTool.players, .banCenter, .diagnostics, .antiLag, .serverUpdater, .chatSilence] {
            XCTAssertFalse(SuiteToolCatalog.nested(tool, features: diagnostics, live: true), tool.rawValue)
        }
    }

    func testToolTitlesAreUnique() {
        let titles = SuiteTool.allCases.map(\.title)
        XCTAssertEqual(Set(titles).count, titles.count, "every directory entry has its own name")
        XCTAssertEqual(SuiteTool.access.title, "Benutzer & Sitzungen")
        XCTAssertTrue(SuiteTool.access.matches("serverregeln"))
    }

    func testPlayerToolsAreUnique() {
        XCTAssertEqual(Set(SuitePlayerTool.allCases.map(\.id)).count, SuitePlayerTool.allCases.count)
        XCTAssertNotNil(WhatsNew.releases.first { $0.version == "5.16.1" })
    }
}
