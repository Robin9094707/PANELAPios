import XCTest
@testable import Panel

/// 5.16.0: Server-Updater, Smart Anti-Lag, server sounds, personal mutes and versioned plugin files.
@MainActor final class Panel5160Tests: XCTestCase {
    func testNavigationAndShortcuts() {
        for tool in [SuiteTool.serverUpdater, .antiLag] {
            XCTAssertEqual(SuiteLaunchRequest.parse(SuiteLaunchRequest(tool: tool, connection: UUID()).url)?.tool, tool)
            XCTAssertNotNil(SuiteShortcutTool(rawValue: tool.rawValue))
        }
        XCTAssertEqual(SuiteTool.serverUpdater.permission, "data.server-updater")
        XCTAssertEqual(SuiteTool.serverUpdater.feature, "server-updater-v1")
        XCTAssertEqual(SuiteTool.antiLag.permission, "data.anti-lag")
        XCTAssertEqual(SuiteTool.antiLag.feature, "smart-anti-lag-v1")
        XCTAssertTrue(SuiteTool.serverUpdater.matches("paper build"))
        XCTAssertTrue(SuiteTool.antiLag.matches("lagmaschine"))
        XCTAssertTrue(SuiteTool.chatSilence.matches("töne"))
        XCTAssertEqual(WhatsNew.release(for: "5.16.0")?.title, "Server-Updater, Smart Anti-Lag & Stummschaltung")
    }

    func testUpdaterTexts() throws {
        XCTAssertEqual(SuiteUpkeep.stepIndex("download"), 0)
        XCTAssertEqual(SuiteUpkeep.stepIndex("queued"), 0)
        XCTAssertEqual(SuiteUpkeep.stepIndex("done"), 4)
        XCTAssertEqual(SuiteUpkeep.stepIndex("failed"), -1)
        XCTAssertEqual(SuiteUpkeep.megabytes(55_307_332), "52,7 MB")
        XCTAssertEqual(SuiteUpkeep.megabytes(0), "–")
        XCTAssertEqual(SuiteUpkeep.channelLabel("STABLE"), "Stabil")
        XCTAssertEqual(SuiteUpkeep.channelLabel("BETA"), "Beta")
        XCTAssertEqual(SuiteUpkeep.installedSummary(try JSON.parse(#"{"minecraft":"26.3","build":258}"#)), "26.3 · Build 258")
        XCTAssertEqual(SuiteUpkeep.installedSummary(try JSON.parse(#"{"minecraft":"","build":-1}"#)), "? · Build ?")
        XCTAssertEqual(SuiteUpkeep.statusLine(try JSON.parse(#"{"newerBuilds":0,"error":""}"#)), "Aktuell – kein neuerer Build")
        XCTAssertEqual(SuiteUpkeep.statusLine(try JSON.parse(#"{"newerBuilds":3,"error":""}"#)), "3 neuere Builds verfügbar")
        XCTAssertEqual(SuiteUpkeep.statusLine(try JSON.parse(#"{"newerBuilds":0,"error":"x"}"#)), "PaperMC nicht erreichbar")
    }

    func testAntiLagTexts() throws {
        let spot = try JSON.parse(#"{"world":"world","x":120,"z":-40,"living":640,"topCount":520,"topType":"Cow","items":30,"redstonePerSecond":0,"causes":[{"name":"Steve","placed":12,"online":true}],"nearby":["Alex"]}"#)
        XCTAssertEqual(SuiteUpkeep.facts(spot), "640 Mobs (520 Cow) · 30 Items")
        XCTAssertEqual(SuiteUpkeep.culprits(spot), "Gebaut/gezüchtet von Steve (12 Teile, online)")
        XCTAssertEqual(SuiteUpkeep.location(spot), "world · 120 / -40")
        let near = try JSON.parse(#"{"causes":[],"nearby":["Alex","Bob"]}"#)
        XCTAssertEqual(SuiteUpkeep.culprits(near), "In der Nähe: Alex, Bob")
        XCTAssertEqual(SuiteUpkeep.facts(try JSON.parse("{}")), "Viel Aktivität")
        XCTAssertEqual(SuiteUpkeep.kindTitle("machine"), "Lag-Maschine")
        XCTAssertEqual(SuiteUpkeep.statusTitle("throttled"), "KI gedrosselt")
        XCTAssertEqual(SuiteUpkeep.modeTitle("observe"), "Nur erkennen")
    }

    func testSoundsAndMutes() throws {
        XCTAssertEqual(SuiteUpkeep.soundTitle("all"), "Stille")
        XCTAssertEqual(SuiteUpkeep.soundTitle("unbekannt"), "Alle Töne an")
        XCTAssertTrue(SuiteUpkeep.soundDetail("chat").contains("Spender"))
        let mute = try JSON.parse(#"{"alts":true,"linkedCount":2,"contact":true,"protected":false,"linked":[{"name":"Alt1"},{"name":"Alt2"}]}"#)
        XCTAssertEqual(SuiteUpkeep.muteDetail(mute), "+2 weitere Konten · Kontakt blockiert")
        XCTAssertEqual(SuiteUpkeep.linkedNames(mute), "Alt1, Alt2")
        let single = try JSON.parse(#"{"alts":false,"contact":false,"protected":true}"#)
        XCTAssertEqual(SuiteUpkeep.muteDetail(single), "Nur dieses Konto · Nur Chat · Admin – wirkt nicht")
        XCTAssertEqual(SuiteUpkeep.linkedNames(single), "")
    }

    func testVersionedPluginNamesMatchThePlugin() {
        XCTAssertEqual(SuiteUpkeep.versionedFilename(name: "LuckPerms", version: "5.4.150"), "LuckPerms-5.4.150.jar")
        XCTAssertEqual(SuiteUpkeep.versionedFilename(name: "Geyser Spigot", version: "2.12.1-SNAPSHOT (b900)"), "Geyser-Spigot-2.12.1-SNAPSHOT-b900.jar")
        XCTAssertEqual(SuiteUpkeep.versionedFilename(name: "", version: ""), "Plugin.jar")
        XCTAssertEqual(SuiteUpkeep.versionedFilename(name: "../evil", version: "1/2"), "evil-1-2.jar")
    }
}
