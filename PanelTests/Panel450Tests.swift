import XCTest
@testable import Panel

/// Focused 4.5.0 checks: diagnostics formatting and mapping, chart points, tiles, Bark event types and release notes.
@MainActor final class Panel450Tests: XCTestCase {
    func testVerdictAndStatusMapping() {
        XCTAssertEqual(SuiteDiagnostics.verdictTitle("legit"), "Legitim")
        XCTAssertEqual(SuiteDiagnostics.verdictTitle("problem"), "Fehler erkannt")
        XCTAssertEqual(SuiteDiagnostics.verdictTitle("x"), "Unklar")
        XCTAssertEqual(SuiteDiagnostics.severityTitle("critical"), "Kritisch")
        XCTAssertEqual(SuiteDiagnostics.severityTitle("warning"), "Dringlich")
        XCTAssertEqual(SuiteDiagnostics.statusSymbol("crit"), "xmark.octagon.fill")
        XCTAssertEqual(SuiteDiagnostics.crashTitle("oom"), "Speicher voll")
        XCTAssertEqual(SuiteDiagnostics.crashTitle("killed"), "Hart beendet")
    }
    func testTeleportCommandUsesDimensions() {
        XCTAssertEqual(SuiteDiagnostics.teleport(world: "world", x: 8, y: 70, z: -40), "/execute in minecraft:overworld run tp @s 8 70 -40")
        XCTAssertEqual(SuiteDiagnostics.teleport(world: "world_nether", x: 1, y: 2, z: 3), "/execute in minecraft:the_nether run tp @s 1 2 3")
        XCTAssertEqual(SuiteDiagnostics.teleport(world: "farmwelt", x: 1, y: 2, z: 3), "/execute in farmwelt run tp @s 1 2 3")
    }
    func testDurationsAndNumbers() {
        XCTAssertEqual(SuiteDiagnostics.duration(45_000), "45 Sek")
        XCTAssertEqual(SuiteDiagnostics.duration(5 * 60_000), "5 Min")
        XCTAssertEqual(SuiteDiagnostics.duration(2 * 3_600_000 + 60_000), "2 Std 1 Min")
        XCTAssertEqual(SuiteDiagnostics.number(19.96, digits: 1), "20")
        XCTAssertEqual(SuiteDiagnostics.number(12.5, digits: 1), "12,5")
        XCTAssertEqual(SuiteDiagnostics.ago(0), "nie")
    }
    func testSeriesPointsAreChronological() {
        let series: JSON = .object(["t": .array([.number(3000), .number(2000), .number(1000)]), "tps": .array([.number(19), .number(18), .number(20)]),
                                    "mspt": .array([.number(30), .number(40), .number(20)]), "msptMax": .array([.number(90), .number(1200), .number(50)])])
        let points = SuiteDiagnostics.points(series)
        XCTAssertEqual(points.count, 3)
        XCTAssertEqual(points.map(\.tps), [20, 18, 19])
        XCTAssertEqual(points.last?.max, 90)
        XCTAssertTrue(points[0].date < points[2].date)
        let windows: JSON = .object(["tps": .array([.number(20), .number(19.5)]), "msptMedian": .array([.number(22), .number(30)]), "msptMax": .array([.number(120), .number(800)])])
        XCTAssertEqual(SuiteDiagnostics.windowPoints(windows).map(\.max), [120, 800])
        XCTAssertTrue(SuiteDiagnostics.points(.null).isEmpty)
    }
    func testOverviewTiles() {
        let data: JSON = .object(["live": .object(["tps": .array([.number(14.2), .number(17)]), "mspt": .number(48), "heapUsed": .number(900), "heapMax": .number(1000), "cpu": .number(40), "java": .number(5), "bedrock": .number(2)]),
                                  "disconnects": .object(["lastHour": .number(9), "involuntary": .number(6)]), "crashCount": .number(1), "lastCrash": .object(["title": .string("Absturz: Watchdog")])])
        let tiles = SuiteDiagnosticsTileModel.overview(data)
        XCTAssertEqual(tiles.map(\.id), ["tps", "mspt", "ram", "cpu", "players", "world", "dc", "crash"])
        XCTAssertEqual(tiles[0].tint, .red)
        XCTAssertEqual(tiles[1].tint, .red)
        XCTAssertEqual(tiles[2].tint, .red)
        XCTAssertEqual(tiles[4].value, "7")
        XCTAssertEqual(tiles[6].tint, .red)
        XCTAssertEqual(tiles[7].detail, "Absturz: Watchdog")
    }
    func testBarkKnowsDiagnosticsEvents() {
        XCTAssertEqual(SuiteBarkSettingsView.diagnosticsEvents.map(\.0), ["disconnect-wave", "crash", "lag-spike"])
        XCTAssertTrue(SuiteBarkSettingsView.criticalKinds.isSuperset(of: ["crash", "disconnect-wave", "lag-spike"]))
    }
    func testReleaseNotes() {
        XCTAssertEqual(WhatsNew.release(for: "4.5.0")?.version, "4.5.0")
        XCTAssertTrue(WhatsNew.shouldPresent(current: "4.5.0", lastSeen: "4.4.0", existingUser: true))
        XCTAssertTrue(WhatsNew.release(for: "4.5.0")?.items.contains { $0.title.contains("Befehle") } ?? false)
    }
}
