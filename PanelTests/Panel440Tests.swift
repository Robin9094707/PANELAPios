import XCTest
@testable import Panel

/// Focused 4.4.0 checks: command rows and their effective state, grouping/filtering of long command lists,
/// the confirmation runner (ticket handed over by the alert) and release notes.
@MainActor final class Panel440Tests: XCTestCase {
    private func row(_ name: String, kind: String, plugin: String, state: String, allowed: Bool) -> JSON {
        .object(["name": .string(name), "kind": .string(kind), "plugin": .string(plugin), "state": .string(state), "allowed": .bool(allowed), "aliases": .array([.string(name + "x")])])
    }
    func testPlayerCommandStates() {
        let blocked = SuiteCommandItem(.object(["name": .string("tpa"), "plugin": .string("Essentials"), "kind": .string("Teleport"), "state": .string("blocked"), "allowed": .bool(false),
                                                "own": .object(["remaining": .string("1 T."), "reason": .string("Spam"), "alts": .bool(true)])]))
        XCTAssertFalse(blocked.allowed)
        XCTAssertTrue(blocked.hasOwn)
        XCTAssertEqual(blocked.note, "Gesperrt · 1 T. · Spam · Alts erben")
        let inherited = SuiteCommandItem(.object(["name": .string("home"), "state": .string("inherited"), "allowed": .bool(false), "inheritedFrom": .string("Main"), "remaining": .string("dauerhaft")]))
        XCTAssertEqual(inherited.note, "Geerbt von Main · dauerhaft")
        let noperm = SuiteCommandItem(.object(["name": .string("gamemode"), "state": .string("noperm"), "allowed": .bool(false), "permission": .string("minecraft.command.gamemode")]))
        XCTAssertEqual(noperm.note, "Rang erlaubt es nicht (minecraft.command.gamemode)")
        let exception = SuiteCommandItem(.object(["name": .string("spawn"), "state": .string("allowed"), "allowed": .bool(true), "override": .bool(true)]))
        XCTAssertEqual(exception.note, "Erlaubt (Ausnahme)")
    }
    func testOverviewRowsUseGlobalState() {
        let open = SuiteCommandItem(.object(["name": .string("msg"), "global": .bool(false)]))
        let closed = SuiteCommandItem(.object(["name": .string("pay"), "global": .bool(true), "globalRemaining": .string("dauerhaft")]))
        XCTAssertTrue(open.allowed)
        XCTAssertFalse(closed.allowed)
        XCTAssertEqual(closed.state, "global")
    }
    func testGroupingAndFilter() {
        let items = [row("tpa", kind: "Teleport", plugin: "Essentials", state: "allowed", allowed: true),
                     row("ban", kind: "Moderation", plugin: "LiteBans", state: "blocked", allowed: false),
                     row("home", kind: "Teleport", plugin: "Essentials", state: "allowed", allowed: true)].map(SuiteCommandItem.init)
        let byKind = SuiteCommandGrouping.groups(items, by: .kind, order: ["Teleport", "Moderation"])
        XCTAssertEqual(byKind.map(\.0), ["Teleport", "Moderation"])
        XCTAssertEqual(byKind.first?.1.count, 2)
        XCTAssertEqual(SuiteCommandGrouping.groups(items, by: .plugin, order: []).map(\.0), ["Essentials", "LiteBans"])
        XCTAssertEqual(SuiteCommandGrouping.groups(items, by: .alphabet, order: []).map(\.0), ["B", "H", "T"])
        XCTAssertEqual(items.filter(SuiteCommandFilter.blocked.keeps).map(\.name), ["ban"])
        XCTAssertTrue(items[0].matches("/tp"))
        XCTAssertTrue(items[0].matches("essent"))
        XCTAssertTrue(items[0].matches("tpax"))
        XCTAssertFalse(items[0].matches("ban"))
    }
    func testConfirmWithoutTicketFailsLocally() async throws {
        let suite = try SuiteAPI(SuiteConnection(panel: "https://panel.invalid", server: "fixture", endpoint: "https://suite.invalid", fingerprint: "", serverIdentity: ""), token: "", demo: true)
        let runner = SuiteConfirmRunner(suite)
        let ok = await runner.confirm("")
        XCTAssertFalse(ok)
        XCTAssertNotNil(runner.error)
        XCTAssertEqual(runner.plan, .null)
    }
    func testReleaseNotesFor440() {
        XCTAssertEqual(WhatsNew.release(for: "4.4.0")?.version, "4.4.0")
        XCTAssertTrue(WhatsNew.shouldPresent(current: "4.4.0", lastSeen: "4.3.1", existingUser: true))
    }
}
