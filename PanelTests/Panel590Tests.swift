import XCTest
@testable import Panel

/// 5.9.0: PowerBot escalation and disconnect forensics presentation.
@MainActor final class Panel590Tests: XCTestCase {
    func testReleaseNotes() {
        XCTAssertTrue(WhatsNew.releases.contains { $0.version == "5.9.0" })
        XCTAssertTrue(WhatsNew.shouldPresent(current: "5.9.0", lastSeen: "5.8.0", existingUser: true))
    }

    func testPowerBotLabels() {
        XCTAssertEqual(PowerBotPresentation.title("dispute-warning"), "Streit-Warnung im Chat")
        XCTAssertEqual(PowerBotPresentation.title("deep-review"), "Tiefenprüfung")
        XCTAssertEqual(PowerBotPresentation.symbol("provocation"), "megaphone.fill")
        XCTAssertTrue(PowerBotPresentation.flagGroups.flatMap { $0.1 }.contains { $0.0 == "escalation" })
        XCTAssertEqual(PowerBotEscalationPresentation.remaining(65_000), "1:05 min")
        XCTAssertEqual(PowerBotEscalationPresentation.remaining(30_000), "30 s")
        XCTAssertEqual(PowerBotEscalationPresentation.stages.count, 4)
    }

    func testForensicsPresentation() {
        XCTAssertEqual(DisconnectForensicsPresentation.closeSide("remote"), "Von außen geschlossen")
        XCTAssertEqual(DisconnectForensicsPresentation.stateTitle("stall"), "Stockt")
        let pattern: JSON = .object(["sync": .number(4), "javaOnly": .bool(true), "rejoinKnown": .number(3), "rejoined": .number(2), "traced": .number(0),
                                     "sessionVariation": .number(0.08), "periodicity": .object(["periodic": .bool(true), "intervalMinutes": .number(240)])])
        let rows = DisconnectForensicsPresentation.patternRows(pattern)
        XCTAssertTrue(rows.contains { $0.0 == "Nur Java betroffen" && $0.1.hasPrefix("Ja") })
        XCTAssertTrue(rows.contains { $0.0 == "Regelmäßig" && $0.1 == "alle ~240 min" })
        XCTAssertTrue(DisconnectForensicsPresentation.patternRows(.null).isEmpty)
    }
}
