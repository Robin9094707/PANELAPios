import XCTest
@testable import Panel

/// Focused 4.3.0 checks: Minecraft colour parsing for prefix previews, permission suggestions, access-lock texts,
/// grouped rights and release notes.
@MainActor final class Panel430Tests: XCTestCase {
    func testLegacyColourCodesBecomeRuns() {
        let runs = SuiteMinecraftText.runs("&c&l[Admin] &rSteve")
        XCTAssertEqual(runs.map(\.text), ["[Admin] ", "Steve"])
        XCTAssertEqual(runs.first?.color, "FF5555")
        XCTAssertEqual(runs.first?.bold, true)
        XCTAssertNil(runs.last?.color)
    }
    func testHexAndMiniMessageColours() {
        XCTAssertEqual(SuiteMinecraftText.runs("&#55FFFFHex").first?.color, "55FFFF")
        XCTAssertEqual(SuiteMinecraftText.runs("&x&1&2&3&4&5&6X").first?.color, "123456")
        XCTAssertEqual(SuiteMinecraftText.runs("{#FF8800}VIP").first?.color, "FF8800")
        XCTAssertEqual(SuiteMinecraftText.runs("<red>Mod</red>").first?.color, "FF5555")
        XCTAssertEqual(SuiteMinecraftText.runs("<gradient:#AA00AA:#55FFFF>Hi</gradient>").first?.color, "AA00AA")
        XCTAssertEqual(SuiteMinecraftText.runs("a < b").map(\.text).joined(), "a < b")
    }
    func testAffixValidation() {
        XCTAssertTrue(SuiteAffixEditor.valid("&c[Admin] "))
        XCTAssertFalse(SuiteAffixEditor.valid("x\" ; op"))
        XCTAssertFalse(SuiteAffixEditor.valid(String(repeating: "a", count: 65)))
    }
    func testPermissionSuggestionsPreferPrefixMatches() {
        let items: [JSON] = [.object(["node": .string("worldedit.wand"), "description": .string("Essentials-like")]), .object(["node": .string("essentials.fly")]), .object(["node": .string("essentials.home")])]
        let found = SuitePermissionSearch.matches(items, query: "ess")
        XCTAssertEqual(found.first?["node"].string, "essentials.fly")
        XCTAssertEqual(found.count, 3)
        XCTAssertTrue(SuitePermissionSearch.matches(items, query: "").isEmpty)
    }
    func testAccessLockTexts() {
        XCTAssertEqual(SuiteAccessLockText.duration(""), "Dauerhaft")
        XCTAssertEqual(SuiteAccessLockText.duration("1d"), "1 Tag")
        XCTAssertEqual(SuiteAccessLockText.duration("7d"), "7 Tage")
        XCTAssertEqual(SuiteAccessLockText.duration("30m"), "30 Minuten")
        XCTAssertEqual(SuiteAccessLockText.scope("own"), "Nur im eigenen Land erlaubt")
    }
    func testRightsAreGroupedByArea() {
        let catalog: [JSON] = [.object(["id": .string("data.bans"), "title": .string("Banns ansehen"), "group": .string("Moderation")]), .object(["id": .string("*"), "title": .string("Vollzugriff")]), .object(["id": .string("data.players"), "title": .string("Spielerliste"), "group": .string("Spieler")])]
        let groups = SuiteRightsCatalog.groups(catalog, query: "")
        XCTAssertEqual(groups.map(\.0), ["Vollzugriff", "Spieler", "Moderation"])
        XCTAssertEqual(SuiteRightsCatalog.groups(catalog, query: "bann").map(\.0), ["Moderation"])
    }
    func testClassicRightsGroupedAndSummarised() {
        let rights: [JSON] = [.object(["id": .string("pvp"), "name": .string("PvP"), "group": .string("Kampf & Gegenstände"), "blocked": .bool(true), "remaining": .string("1 T.")]),
                              .object(["id": .string("chat"), "name": .string("Chat schreiben"), "group": .string("Chat & Befehle"), "blocked": .bool(false)])]
        XCTAssertEqual(SuiteRightsGrouping.groups(rights).map(\.0), ["Chat & Befehle", "Kampf & Gegenstände"])
        let row: JSON = .object(["locks": .array([.object(["title": .string("Truhen"), "remaining": .string("dauerhaft")])]), "rights": .array(rights),
                                 "legacy": .object(["doors": .array([.object(["name": .string("Türen benutzen"), "remaining": .string("2 Std.")])])])])
        XCTAssertEqual(SuiteAccessLockRow.summary(row), "Truhen (dauerhaft) · PvP (1 T.) · Türen benutzen (2 Std.)")
    }
    func testReleaseNotesFor431() {
        XCTAssertEqual(WhatsNew.release(for: "4.3.1")?.version, "4.3.1")
        XCTAssertTrue(WhatsNew.shouldPresent(current: "4.3.1", lastSeen: "4.3.0", existingUser: true))
    }
    func testReleaseNotesFor430() {
        XCTAssertEqual(WhatsNew.release(for: "4.3.0")?.version, "4.3.0")
        XCTAssertTrue(WhatsNew.shouldPresent(current: "4.3.0", lastSeen: "4.2.0", existingUser: true))
    }
}
