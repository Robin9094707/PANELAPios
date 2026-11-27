import XCTest
@testable import Panel

/// 5.17.0: Länder-Zentrale (Lands) presentation logic and release metadata.
@MainActor final class Panel5170Tests: XCTestCase {
    func testLimitsAndMoney() {
        XCTAssertEqual(SuiteLands.limit(-1), "∞")
        XCTAssertEqual(SuiteLands.limit(40), "40")
        XCTAssertEqual(SuiteLands.used(12, of: 50), "12 / 50")
        XCTAssertEqual(SuiteLands.used(12, of: -1), "12")
        XCTAssertEqual(SuiteLands.money(1234.4), "1.234")
        XCTAssertEqual(SuiteLands.decimal("12,5"), 12.5)
        XCTAssertNil(SuiteLands.decimal("abc"))
    }

    func testSummaryAndMembership() {
        let land: JSON = .object(["name": .string("Robinia"), "display": .string(""), "ownerName": .string("Robin"), "members": .number(4), "chunks": .number(12), "maxChunks": .number(50), "balance": .number(0)])
        XCTAssertEqual(SuiteLands.displayName(land), "Robinia")
        XCTAssertEqual(SuiteLands.summary(land), "Besitzer Robin · 4 Mitglieder · 12 / 50 Chunks")
        XCTAssertEqual(SuiteLands.summary(land, showOwner: false), "4 Mitglieder · 12 / 50 Chunks")
        let member: JSON = .object(["role": .string("Bauarbeiter"), "wholeLand": .bool(false)])
        XCTAssertEqual(SuiteLands.membershipTitle(member, kind: "member"), "Bauarbeiter · nur Gebiete")
        XCTAssertEqual(SuiteLands.membershipTitle(member, kind: "owned"), "Besitzer (Hauptland)")
        XCTAssertEqual(SuiteLands.membershipTitle(.object(["wholeLand": .bool(true)]), kind: "member"), "Mitglied")
    }

    func testOwnerAndVisitorRolesAreNeverAssignable() {
        let roles: [JSON] = [
            .object(["id": .string("o"), "priority": .number(100), "assignable": .bool(false), "type": .string("OWNER")]),
            .object(["id": .string("a"), "priority": .number(10), "assignable": .bool(true)]),
            .object(["id": .string("b"), "priority": .number(50), "assignable": .bool(true)]),
            .object(["id": .string("v"), "priority": .number(0), "assignable": .bool(false), "type": .string("VISITOR")])
        ]
        XCTAssertEqual(SuiteLands.assignableRoles(roles).map(\.["id"].string), ["b", "a"])
        XCTAssertEqual(SuiteLands.roleTypeTitle("OWNER"), "Besitzer-Rolle")
        XCTAssertNil(SuiteLands.roleTypeTitle("NORMAL"))
    }

    func testFlagGroupsAndCaseInsensitiveFlags() {
        let catalog: JSON = .object(["role": .array([
            .object(["name": .string("block_break"), "title": .string("Abbauen"), "category": .string("ACTION")]),
            .object(["name": .string("player_trust"), "title": .string("Spieler hinzufügen"), "category": .string("MANAGEMENT")]),
            .object(["name": .string("block_place"), "title": .string("Bauen"), "category": .string("ACTION")])
        ])])
        let groups = SuiteLands.roleFlagGroups(catalog)
        XCTAssertEqual(groups.map(\.title), ["Aktionsrechte", "Verwaltungsrechte"])
        XCTAssertEqual(groups[0].flags.map(\.["title"].string), ["Abbauen", "Bauen"])
        XCTAssertTrue(SuiteLands.contains([.string("BLOCK_BREAK")], "block_break"))
        XCTAssertFalse(SuiteLands.contains([.string("block_place")], "block_break"))
    }

    func testAreaTextAndRequestBody() {
        let area: JSON = .object(["members": .number(2), "world": .string("world"), "tax": .number(0),
            "bounds": .object(["minX": .number(0), "maxX": .number(30), "minZ": .number(0), "maxZ": .number(30), "minY": .number(-64), "maxY": .number(320)])])
        XCTAssertEqual(SuiteLands.bounds(area), "31×31 Blöcke, Höhe -64–320")
        XCTAssertEqual(SuiteLands.areaDetail(area), "2 Mitglieder · world · 31×31 Blöcke, Höhe -64–320")
        let body = SuiteLands.body("trust", land: "L1", area: "", extra: ["player": .string("p")])
        XCTAssertEqual(body["operation"], JSON.string("trust"))
        XCTAssertEqual(body["land"], JSON.string("L1"))
        XCTAssertNil(body["area"], "the main area is addressed without an area id")
        XCTAssertEqual(SuiteLands.body("ban", land: "L1", area: "A2")["area"], JSON.string("A2"))
    }

    func testReleaseMetadata() {
        XCTAssertEqual(WhatsNew.releases.first?.version, "5.17.0")
        XCTAssertEqual(SuiteTool.lands.title, "Länder")
        XCTAssertEqual(SuiteLands.Tab.allCases.count, 6)
    }
}
