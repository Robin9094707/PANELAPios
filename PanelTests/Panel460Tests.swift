import XCTest
@testable import Panel

/// Focused 4.6.0 checks: Minecraft colour parsing and gradients, Discord field layout and markdown, trimmer mapping and release notes.
@MainActor final class Panel460Tests: XCTestCase {
    func testMinecraftRunsAndColours() {
        let runs = MinecraftText.runs("&f Online: &b5&8/&750")
        XCTAssertEqual(runs.map { $0.0 }, [" Online: ", "5", "/", "50"])
        XCTAssertEqual(runs[0].1.color, 0xFFFFFF)
        XCTAssertEqual(runs[1].1.color, 0x55FFFF)
        XCTAssertEqual(runs[2].1.color, 0x555555)
        let hex = MinecraftText.runs("&x&0&0&A&8&E&8&ls")
        XCTAssertEqual(hex.count, 1)
        XCTAssertEqual(hex[0].0, "s")
        XCTAssertEqual(hex[0].1.color, 0x00A8E8)
        XCTAssertTrue(hex[0].1.bold)
        XCTAssertEqual(MinecraftText.plain("§a§lHallo &rWelt"), "Hallo Welt")
        XCTAssertFalse(MinecraftText.runs("&lfett&rnormal")[1].1.bold, "&r resets formatting")
        XCTAssertEqual(MinecraftText.plain("50 & mehr"), "50 & mehr", "a lone & stays text")
    }
    func testGradientMatchesShippedTitle() {
        let title = MinecraftText.gradient("simplexsmp.eu", from: 0x00A8E8, to: 0x80BDF9, bold: true)
        XCTAssertTrue(title.hasPrefix("&x&0&0&A&8&E&8&ls"))
        XCTAssertTrue(title.hasSuffix("&x&8&0&B&D&F&9&lu"))
        XCTAssertEqual(MinecraftText.gradient("ab", from: 0x000000, to: 0xFFFFFF, bold: false), "&x&0&0&0&0&0&0a&x&F&F&F&F&F&Fb")
        XCTAssertEqual(MinecraftText.plain(title), "simplexsmp.eu")
    }
    func testPlaceholdersFilledFromSample() {
        let sample: JSON = .object(["{online}": .string("7"), "{max}": .string("50")])
        XCTAssertEqual(MinecraftText.fill("&fOnline &b{online}&8/&7{max}", sample), "&fOnline &b7&8/&750")
    }
    func testDiscordFieldRows() {
        let inline: JSON = .object(["name": .string("Players"), "value": .string("**3/50**"), "inline": .bool(true)])
        let wide: JSON = .object(["name": .string("Online players"), "value": .string("_No players online_"), "inline": .bool(false)])
        let rows = DiscordPreviewModel.fieldRows([inline, inline, inline, inline, wide, inline])
        XCTAssertEqual(rows.map(\.count), [3, 1, 1, 1])
        XCTAssertEqual(DiscordPreviewModel.hex("#57F287"), 0x57F287)
        XCTAssertNil(DiscordPreviewModel.hex("grün"))
        XCTAssertEqual(String(DiscordPreviewModel.markdown("**3/50** online").characters), "3/50 online")
        XCTAssertEqual(DiscordPreviewModel.hexString(MinecraftText.color(0x5865F2)), "#5865F2")
    }
    func testWorldTrimMapping() {
        XCTAssertEqual(SuiteWorldTrim.phaseTitle("AWAITING_OVERRIDE"), "Entscheidung nötig")
        XCTAssertEqual(SuiteWorldTrim.phaseTitle("IDLE"), "Bereit")
        XCTAssertEqual(SuiteWorldTrim.resultTitle("SUCCESS"), "Erfolgreich")
        XCTAssertEqual(SuiteWorldTrim.countdownTitle(0), "Sofort")
        XCTAssertEqual(SuiteWorldTrim.countdownTitle(180), "3 Minuten")
        XCTAssertEqual(SuiteWorldTrim.snapshotNeed(selected: 1000, minimum: 500), 1600)
    }
    func testReleaseNotes() {
        let release = WhatsNew.release(for: "4.6.0")
        XCTAssertEqual(release?.version, "4.6.0")
        XCTAssertGreaterThanOrEqual(release?.items.count ?? 0, 6)
        XCTAssertEqual(WhatsNew.release(for: "4.6.2")?.version, "4.6.0")
    }
}
