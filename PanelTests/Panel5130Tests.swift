import XCTest
@testable import Panel

/// 5.13.0: Bann-Zentrale (main bans vs. alt accounts, lift one or all, IP mistakes) and the LiteBans migration.
@MainActor final class Panel5130Tests: XCTestCase {
    func testNavigationAndShortcut() {
        let request = SuiteLaunchRequest(tool: .banCenter, connection: UUID())
        XCTAssertEqual(SuiteLaunchRequest.parse(request.url)?.tool, .banCenter)
        XCTAssertNotNil(SuiteShortcutTool(rawValue: SuiteTool.banCenter.rawValue))
        XCTAssertEqual(SuiteTool.banCenter.permission, "data.ban-center")
        XCTAssertEqual(SuiteTool.banCenter.feature, "ban-center-v1")
        XCTAssertTrue(SuiteTool.banCenter.matches("litebans"))
        XCTAssertTrue(SuiteTool.banCenter.matches("alt-konto entsperren"))
    }

    func testRolesHeadlineAndBadges() throws {
        XCTAssertEqual(SuiteBanText.role("alt"), "Alt-Konto")
        XCTAssertEqual(SuiteBanText.role("main"), "Hauptbann")
        let alt = try JSON.parse(#"{"banned":true,"role":"alt","case":{"mainName":"Peakychuuuu"}}"#)
        XCTAssertEqual(SuiteBanText.headline(alt), "Gesperrtes Alt-Konto von Peakychuuuu")
        XCTAssertEqual(SuiteBanText.headline(try JSON.parse(#"{"banned":true,"role":"main"}"#)), "Hauptbann")
        XCTAssertEqual(SuiteBanText.headline(try JSON.parse(#"{"banned":false}"#)), "Nicht gesperrt")
        let row = try JSON.parse(#"{"ban":true,"banRole":"alt"}"#)
        XCTAssertEqual(SuitePlayerBadges.badges(row).first?.title, "Alt-Konto gesperrt")
        XCTAssertEqual(SuitePlayerBadges.badges(try JSON.parse(#"{"ban":true}"#)).first?.title, "Gebannt")
        XCTAssertEqual(SuiteBanText.source("litebans"), "aus LiteBans übernommen")
        XCTAssertEqual(SuiteBanText.source("ultra:ultra"), "Alt-Erkennung (Ultrabann)")
    }

    func testDurations() {
        let now = Date(timeIntervalSince1970: 1_794_787_200)
        XCTAssertEqual(SuiteBanText.until(permanent: true, until: 0, now: now), "dauerhaft")
        XCTAssertEqual(SuiteBanText.until(permanent: false, until: 1_794_787_100_000, now: now), "abgelaufen")
        XCTAssertTrue(SuiteBanText.until(permanent: false, until: 1_794_794_400_000, now: now).hasPrefix("noch 2 Std."))
        XCTAssertTrue(SuiteBanText.until(permanent: false, until: 1_795_046_400_000, now: now).hasPrefix("noch 3 Tage"))
    }

    func testLiftPayloadAndAffectedAccounts() throws {
        let body = SuiteBanText.liftPayload(caseID: "LB-53", player: "u2", all: false, note: "  zweite Chance  ")
        XCTAssertEqual(body["operation"], .string("lift"))
        XCTAssertEqual(body["case"], .string("LB-53"))
        XCTAssertEqual(body["scope"], .string("single"))
        XCTAssertEqual(body["note"], .string("zweite Chance"))
        XCTAssertEqual(body["confirmed"], .bool(true))
        XCTAssertEqual(SuiteBanText.liftPayload(caseID: "B-X", player: "", all: true, note: "")["scope"], .string("all"))
        let caseRow = try JSON.parse(#"{"members":[{"uuid":"u1","name":"Main","role":"main"},{"uuid":"u2","name":"Alt","role":"alt"}]}"#)
        XCTAssertEqual(SuiteBanText.affected(caseRow, member: "u2", all: false), ["Alt"])
        XCTAssertEqual(SuiteBanText.affected(caseRow, member: "u2", all: true), ["Main", "Alt"])
        XCTAssertEqual(SuiteBanText.affected(caseRow, member: nil, all: false), ["Main", "Alt"])
    }

    func testMigrationChoices() {
        XCTAssertEqual(SuiteBanText.decisions(["b": "skip", "a|u1": "off", "a": "account"]), "a=account,a|u1=off,b=skip")
        XCTAssertEqual(SuiteBanText.decisionTitle("skip"), "Nicht übernehmen")
        XCTAssertEqual(SuiteBanText.decisionTitle("account+ip"), "Konten + IP sperren")
        XCTAssertEqual(SuiteBanText.status("done"), "übernommen")
        XCTAssertEqual(SuiteBanText.status("pending"), "offen")
        XCTAssertEqual(SuiteBanText.type("mute"), "Mute")
    }

    func testReleaseNotes() {
        XCTAssertEqual(WhatsNew.releases.first?.version, "5.13.0")
        XCTAssertEqual(WhatsNew.release(for: "5.13.0")?.title, "Bann-Zentrale")
        XCTAssertTrue(WhatsNew.shouldPresent(current: "5.13.0", lastSeen: "5.12.0", existingUser: true))
    }
}
