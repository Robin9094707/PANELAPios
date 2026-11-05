import XCTest
import SwiftUI
@testable import Panel

/// Focused 4.8.0 checks: Discord markdown (kept for previews), Werbe-Zentrale formats and links, Bark event and notes.
@MainActor final class Panel480Tests: XCTestCase {
    func testDiscordBlocks() {
        let text = "# 🌍 **SIMPLEX SMP**\n### *Survival*\n\n> **📡 __CONNECTION__**\n> 🖥️ **IP:** `simplexsmp.eu`\n>\nnormal\n- one\n  - two\n1. first\n-# small\n```\ncode line\n```"
        let blocks = DiscordMarkdown.blocks(text)
        XCTAssertEqual(blocks[0], .heading(1, "🌍 **SIMPLEX SMP**"))
        XCTAssertEqual(blocks[3], .quote([.line("**📡 __CONNECTION__**"), .line("🖥️ **IP:** `simplexsmp.eu`"), .gap]))
        XCTAssertEqual(blocks[5], .bullet(0, "one"))
        XCTAssertEqual(blocks[8], .subtext("small"))
        XCTAssertEqual(blocks[9], .code("code line"))
        XCTAssertEqual(DiscordMarkdown.plain("**fett** *kursiv* __unter__ ~~weg~~ ||geheim|| `code`"), "fett kursiv unter weg geheim code")
        XCTAssertEqual(DiscordMarkdown.fill("{online}/{max}", online: "7", max: "60"), "7/60")
    }
    func testPreviewConversion() {
        XCTAssertEqual(AdHubFormat.discordMarkdown("[b]Fett[/b] [i]kursiv[/i] [u]u[/u] [s]x[/s]", format: "bbcode"), "**Fett** *kursiv* __u__ ~~x~~")
        XCTAssertEqual(AdHubFormat.discordMarkdown("[size=6][b]🌍 SIMPLEX SMP[/b][/size]", format: "bbcode"), "# 🌍 SIMPLEX SMP")
        XCTAssertEqual(AdHubFormat.discordMarkdown("[quote]\na\nb\n[/quote]", format: "bbcode"), "> a\n> b")
        XCTAssertEqual(AdHubFormat.discordMarkdown("[url=https://discord.gg/x]Discord[/url]", format: "bbcode"), "[Discord](https://discord.gg/x)")
        XCTAssertEqual(AdHubFormat.discordMarkdown("[list]\n[*]eins\n[*]zwei\n[/list]", format: "bbcode"), "- eins\n- zwei")
        XCTAssertEqual(AdHubFormat.discordMarkdown("*fett* _kursiv_ ~weg~", format: "whatsapp"), "**fett** *kursiv* ~~weg~~")
        XCTAssertEqual(AdHubFormat.discordMarkdown(">!geheim!< zeile  \nweiter", format: "markdown"), "||geheim|| zeile\nweiter")
        XCTAssertNil(AdHubFormat.discordMarkdown("𝗦𝗶𝗺𝗽𝗹𝗲𝘅", format: "unicode"))
        XCTAssertNil(AdHubFormat.discordMarkdown("**bleibt**", format: "plain"))
        XCTAssertEqual(AdHubFormat.platform(for: "bbcode"), "planetminecraft")
        XCTAssertEqual(AdHubFormat.label("markdown"), "Reddit-Markdown")
        XCTAssertFalse(AdHubFormat.tools("bbcode").isEmpty)
        XCTAssertTrue(AdHubFormat.tools("plain").isEmpty)
    }
    func testLinks() {
        XCTAssertEqual(AdHubFormat.guess("https://discord.com/channels/900000000000000001/700000000000000003"), "discord")
        XCTAssertEqual(AdHubFormat.guess("https://www.reddit.com/r/mcservers/submit"), "reddit")
        XCTAssertEqual(AdHubFormat.guess("https://www.planetminecraft.com/server/simplex/"), "planetminecraft")
        XCTAssertEqual(AdHubFormat.guess("https://www.facebook.com/groups/minecraft"), "facebook")
        XCTAssertEqual(AdHubFormat.guess("https://example.org"), "other")
        let channel = AdHubFormat.discordChannel("https://discord.com/channels/900000000000000001/700000000000000003")
        XCTAssertEqual(channel?.guild, "900000000000000001")
        XCTAssertEqual(channel?.channel, "700000000000000003")
        XCTAssertNil(AdHubFormat.discordChannel("https://discord.gg/2gAsymnXfd"))
        XCTAssertEqual(AdHubOpener.discordAppURL(guild: "1", channel: "2")?.absoluteString, "discord://discord.com/channels/1/2")
        let link = AdPostLink(URL(string: "panel://ad-post?s=srv-1&p=abcDEF123&c=4&k=AbCdEfGhIjKlMnOpQrStUvWx")!)
        XCTAssertEqual(link?.server, "srv-1")
        XCTAssertEqual(link?.place, "abcDEF123")
        XCTAssertEqual(link?.cycle, 4)
        XCTAssertNil(AdPostLink(URL(string: "panel://ad-post?s=srv-1&p=abc&c=0&k=AbCdEfGhIjKlMnOpQrStUvWx")!), "cycle must be positive")
        XCTAssertNil(AdPostLink(URL(string: "panel://ad-post?p=abc&c=1&k=kurz")!), "signature characters are checked")
        XCTAssertNil(AdPostLink(URL(string: "panel://suite-events")!))
    }
    func testCache() {
        let defaults = UserDefaults(suiteName: "Panel480Tests")!
        defaults.removePersistentDomain(forName: "Panel480Tests")
        let overview: JSON = .object(["texts": .array([.object(["id": .string("t1"), "content": .string("Hallo {online}")])]), "places": .array([.object(["id": .string("p1"), "name": .string("#ads"), "link": .string("https://discord.com/channels/1/2"), "textId": .string("t1")])])])
        AdHubCache.store(overview, serverId: "srv", defaults: defaults)
        XCTAssertEqual(AdHubCache.entry(serverId: "srv", place: "p1", defaults: defaults)?["link"], "https://discord.com/channels/1/2")
        XCTAssertEqual(AdHubCache.entry(serverId: "srv", place: "p1", defaults: defaults)?["text"], "Hallo ")
        XCTAssertNil(AdHubCache.entry(serverId: "other", place: "p1", defaults: defaults))
    }
    func testFormattingAndNotes() {
        XCTAssertEqual(AdsFormat.human(1440), "1 Tag")
        XCTAssertEqual(AdsFormat.human(360), "6 Std")
        XCTAssertEqual(AdsFormat.human(90), "1,5 Std")
        XCTAssertEqual(AdsFormat.until(0), "–")
        XCTAssertEqual(AdsFormat.ago(0), "noch nie")
        XCTAssertTrue(AdsFormat.intervals.contains { $0.0 == 360 })
        XCTAssertEqual(SuiteBarkSettingsView.adsEvents.map(\.0), ["ad-reminder"])
        let release = WhatsNew.release(for: "4.8.0")
        XCTAssertEqual(release?.version, "4.8.0")
        XCTAssertGreaterThanOrEqual(release?.items.count ?? 0, 5)
        XCTAssertEqual(WhatsNew.releases.first?.version, "4.8.0")
    }
}
