import XCTest
import SwiftUI
@testable import Panel

/// Focused 4.7.0 checks: Discord markdown blocks and inline styles, planner formatting, Bark event and release notes.
@MainActor final class Panel470Tests: XCTestCase {
    func testBlocks() {
        let text = "# 🌍 **SIMPLEX SMP**\n### *Survival*\n\n> **📡 __CONNECTION__**\n> 🖥️ **IP:** `simplexsmp.eu`\n>\nnormal\n- one\n  - two\n1. first\n-# small\n```\ncode line\n```"
        let blocks = DiscordMarkdown.blocks(text)
        XCTAssertEqual(blocks[0], .heading(1, "🌍 **SIMPLEX SMP**"))
        XCTAssertEqual(blocks[1], .heading(3, "*Survival*"))
        XCTAssertEqual(blocks[2], .gap)
        XCTAssertEqual(blocks[3], .quote([.line("**📡 __CONNECTION__**"), .line("🖥️ **IP:** `simplexsmp.eu`"), .gap]))
        XCTAssertEqual(blocks[4], .line("normal"))
        XCTAssertEqual(blocks[5], .bullet(0, "one"))
        XCTAssertEqual(blocks[6], .bullet(1, "two"))
        XCTAssertEqual(blocks[7], .numbered("1.", "first"))
        XCTAssertEqual(blocks[8], .subtext("small"))
        XCTAssertEqual(blocks[9], .code("code line"))
        XCTAssertEqual(blocks.count, 10)
        XCTAssertEqual(DiscordMarkdown.blocks(">>> a\nb"), [.quote([.line("a"), .line("b")])], ">>> quotes the rest of the message")
    }
    func testInline() {
        XCTAssertEqual(DiscordMarkdown.plain("**fett** *kursiv* __unter__ ~~weg~~ ||geheim|| `code`"), "fett kursiv unter weg geheim code")
        XCTAssertEqual(DiscordMarkdown.plain("***beides***"), "beides")
        XCTAssertEqual(DiscordMarkdown.plain("[Discord](https://discord.gg/2gAsymnXfd)"), "Discord")
        XCTAssertEqual(DiscordMarkdown.plain("\\*kein\\* stern"), "*kein* stern")
        XCTAssertEqual(DiscordMarkdown.plain("<:simplex:123456789012345678> <@123456789012345678> <#123456789012345678>"), ":simplex: @Benutzer #Kanal")
        XCTAssertEqual(DiscordMarkdown.plain("2 * 3 * 4"), "2 * 3 * 4", "a lone star with spaces stays text")
        let link = DiscordMarkdown.inline("https://discord.gg/2gAsymnXfd.")
        XCTAssertEqual(link.runs.first?.link, URL(string: "https://discord.gg/2gAsymnXfd"), "trailing dot is not part of the link")
        XCTAssertEqual(DiscordMarkdown.fill("{online}/{max}", online: "7", max: "60"), "7/60")
    }
    func testFormatting() {
        XCTAssertEqual(AdsFormat.human(1440), "1 Tag")
        XCTAssertEqual(AdsFormat.human(2880), "2 Tage")
        XCTAssertEqual(AdsFormat.human(360), "6 Std")
        XCTAssertEqual(AdsFormat.human(15), "15 Min")
        XCTAssertEqual(AdsFormat.human(90), "1,5 Std")
        XCTAssertEqual(AdsFormat.window(from: 1445.8, to: 1468.8), "24,10 Std – 24,48 Std")
        XCTAssertEqual(AdsFormat.until(0), "–")
        XCTAssertEqual(AdsFormat.until(Date().timeIntervalSince1970 * 1000 - 5000), "jetzt")
        XCTAssertEqual(AdsFormat.account("captcha").0, "Captcha nötig")
        XCTAssertEqual(AdsFormat.result("deleted")?.0, "Vom Server gelöscht")
        XCTAssertTrue(AdsFormat.intervals.contains { $0.0 == 360 })
    }
    func testBarkEventAndReleaseNotes() {
        XCTAssertEqual(SuiteBarkSettingsView.adsEvents.map(\.0), ["discord-ads"])
        let release = WhatsNew.release(for: "4.7.0")
        XCTAssertEqual(release?.version, "4.7.0")
        XCTAssertGreaterThanOrEqual(release?.items.count ?? 0, 6)
        XCTAssertEqual(WhatsNew.release(for: "4.6.0")?.version, "4.6.0")
    }
}
