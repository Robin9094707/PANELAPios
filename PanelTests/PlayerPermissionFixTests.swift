import XCTest
import UIKit
@testable import Panel

final class PlayerPermissionFixTests: XCTestCase {
    @MainActor func testYAMLQuotesCannotColorAcrossLinesOrEscapeComments() throws {
        let text = "# World's config has an apostrophe\nhard: default\nsoft: default\namount: '60'\nnext: true\nmotd: \"Hello # friend\"\nbroken: 'unterminated\nlast: false\n"
        let storage = NSMutableAttributedString(string: text, attributes: [.foregroundColor: UIColor.label])
        SyntaxHighlight.apply(to: storage, range: NSRange(text.startIndex..., in: text))
        func color(_ part: String) -> UIColor? { storage.attribute(.foregroundColor, at: (text as NSString).range(of: part).location, effectiveRange: nil) as? UIColor }
        XCTAssertTrue(color("hard")?.isEqual(UIColor.systemTeal) == true)
        XCTAssertTrue(color("soft")?.isEqual(UIColor.systemTeal) == true)
        XCTAssertTrue(color("'60'")?.isEqual(UIColor.systemBlue) == true)
        XCTAssertTrue(color("# friend")?.isEqual(UIColor.systemBlue) == true)
        XCTAssertTrue(color("last")?.isEqual(UIColor.systemTeal) == true)
        XCTAssertEqual(storage.string, text)
    }
    func testProviderPermissionCatalogPreservesCustomNamesAndDescriptions() throws {
        let response = try JSON.parse(#"{"attributes":{"permissions":{"control":{"description":"Power actions","keys":{"console":"Send commands"}},"minecraft":{"name":"Spieler","keys":{"manage":{"name":"Spieler verwalten","description":"Provider player manager"}}}}}}"#)
        let groups = PermissionCatalog.decode(response)
        XCTAssertEqual(Set(groups.flatMap { $0.choices.map(\.id) }), ["control.console", "minecraft.manage"])
        let player = try XCTUnwrap(groups.first { $0.id == "minecraft" })
        XCTAssertEqual(player.title, "Spieler"); XCTAssertEqual(player.choices[0].title, "Spieler verwalten"); XCTAssertEqual(player.choices[0].description, "Provider player manager")
        let flat = PermissionCatalog.decode(try JSON.parse(#"{"data":[{"permission":"backup.download","name":"Download","description":"Read backup"}]}"#))
        XCTAssertEqual(flat.first?.choices.first?.id, "backup.download")
        XCTAssertTrue(PermissionCatalog.decode(.null).isEmpty)
    }
    func testBackupLinksSupportProviderEnvelopesAndRejectUnsafeResponses() throws {
        let base = URL(string: "https://kineticpanel.net")!
        for text in [#"{"attributes":{"url":"https://node.example.com/a?token=secret"}}"#, #"{"data":{"download_url":"https://node.example.com/a?token=secret"}}"#, #"{"data":{"attributes":{"download":{"url":"https://node.example.com/a?token=secret"}}}}"#] {
            XCTAssertEqual(try BackupInfo.downloadURL(JSON.parse(text), base: base), "https://node.example.com/a?token=secret")
        }
        XCTAssertEqual(try BackupInfo.downloadURL(JSON.parse(#"{"url":"/signed/archive?token=secret"}"#), base: base), "https://kineticpanel.net/signed/archive?token=secret")
        for text in [#"{"message":"export queued"}"#, #"{"url":"http://unsafe.example/a"}"#, #"{"url":"https://user:pass@example.com/a"}"#] { XCTAssertThrowsError(try BackupInfo.downloadURL(JSON.parse(text), base: base)) }
        let denied = HTTPURLResponse(url: base, statusCode: 403, httpVersion: nil, headerFields: nil)!
        XCTAssertThrowsError(try PanelAPI.validate(Data(#"{"message":"Backup downloads disabled by storage"}"#.utf8), response: denied)) { error in XCTAssertTrue(error.localizedDescription.contains("Backup downloads disabled by storage")) }
        let info = BackupInfo(value: try JSON.parse(#"{"uuid":"backup-id","is_successful":1,"can_download":false}"#))
        XCTAssertEqual(info.id, "backup-id"); XCTAssertTrue(info.successful); XCTAssertTrue(info.completed); XCTAssertFalse(info.downloadable)
    }
    func testPlayerTemplatesPreserveBedrockPrefixAndPreventCommandInjection() throws {
        XCTAssertEqual(try PlayerCommands.render("/litebans:ban {player} {reason}", player: ".niex2277", reason: "Grund {player}"), "litebans:ban .niex2277 Grund {player}")
        XCTAssertThrowsError(try PlayerCommands.render("kick {player} {reason}", player: "@a", reason: "test"))
        XCTAssertThrowsError(try PlayerCommands.render("kick {player} {reason}", player: "Robin", reason: "test\nstop"))
        XCTAssertThrowsError(try PlayerCommands.render("kick {player}\nstop", player: "Robin"))
        XCTAssertThrowsError(try PlayerCommands.render("kick {player} {unknown}", player: "Robin"))
        XCTAssertThrowsError(try PlayerCommands.render("stop", player: "Robin"))
        XCTAssertEqual(PlayerCommands.response("[12:00:00 INFO]: .niex2277 has the following entity data: 18.0f", player: ".niex2277"), "18.0f")
        XCTAssertNil(PlayerCommands.response("other has the following entity data: 20.0f", player: ".niex2277"))
        XCTAssertNil(PlayerCommands.response(".Robin has the following entity data: 20.0f", player: "Robin"))
        XCTAssertNil(PlayerCommands.response("BigRobin has the following entity data: 20.0f", player: "Robin"))
        XCTAssertEqual(PlayerCommands.number("18.0f"), 18); XCTAssertEqual(PlayerCommands.number("-1.2E2d"), -120)
        XCTAssertNil(PlayerCommands.number("NaN")); XCTAssertNil(PlayerCommands.number("[20.0f]"))
    }
    @MainActor func testPlayerActionPreferencesAreServerScopedAndDurable() throws {
        let suite = "players-" + UUID().uuidString
        let defaults = UserDefaults(suiteName: suite)!; defer { defaults.removePersistentDomain(forName: suite) }
        let store = PlayerPreferencesStore(defaults: defaults)
        var edited = store.get("panel|one"); edited.kick = "essentials:kick {player} {reason}"
        edited.custom = [PlayerActionTemplate(name: "Feed", command: "feed {player}")]
        store.save(edited, key: "panel|one")
        XCTAssertEqual(PlayerPreferencesStore(defaults: defaults).get("panel|one"), edited)
        XCTAssertEqual(store.get("panel|two").kick, "minecraft:kick {player} {reason}")
    }
    @MainActor func testOldPausedTransfersAndLegacySourcesSurviveTheUpdate() throws {
        var record = TransferRecord(filename: "config.yml", context: "Simplex SMP · /plugins/Test", upload: false)
        record.state = "paused"
        let data = try JSONEncoder().encode(record)
        XCTAssertNil(try JSONDecoder().decode(TransferRecord.self, from: data).source)
        let api = PanelAPI(base: URL(string: "https://kineticpanel.net")!, key: "", demo: true)
        let source = try XCTUnwrap(DownloadSource.legacy(context: record.context, filename: record.filename, api: api, servers: Demo.servers.map { Server(data: $0) }))
        XCTAssertEqual(source.resource, "/plugins/Test/config.yml"); XCTAssertEqual(source.server, "simplex1")
        record.source = source
        XCTAssertEqual(try JSONDecoder().decode(TransferRecord.self, from: JSONEncoder().encode(record)).source, source)
        XCTAssertNil(DownloadSource.legacy(context: "Other panel · /", filename: "file", api: api, servers: Demo.servers.map { Server(data: $0) }))
    }
    func testSigningGroupDiscoveryDoesNotChooseUnrelatedContainers() throws {
        let groups = SigningGroups.candidates(["group.other.app", "group.eu.simplexsmp.panel.TEAM123", "group.TEAM123.eu.simplexsmp.panel", "group.eu.simplexsmp.panel", "group.eu.simplexsmp.panel/../../other"])
        XCTAssertEqual(Set(groups), ["group.eu.simplexsmp.panel", "group.eu.simplexsmp.panel.TEAM123", "group.TEAM123.eu.simplexsmp.panel"])
        let plist = try PropertyListSerialization.data(fromPropertyList: ["Entitlements": ["com.apple.security.application-groups": ["group.eu.simplexsmp.panel.TEAM123"]]], format: .xml, options: 0)
        let cmsLike = Data([0x30, 0x82, 0x01, 0x02]) + plist + Data([0x01, 0x02])
        XCTAssertEqual(SigningGroups.entitlements(in: cmsLike)["com.apple.security.application-groups"] as? [String], ["group.eu.simplexsmp.panel.TEAM123"])
        XCTAssertTrue(SigningGroups.entitlements(in: Data("not a provisioning profile".utf8)).isEmpty)
    }
}
