import XCTest
@testable import Panel

final class MinecraftTests: XCTestCase {
    func testListResponseFromPaperConsole() throws {
        let value = try XCTUnwrap(PlayerSnapshot.parseLog("\u{1B}[32m[04:46:56 INFO]: System chat: There are 2 of a max of 60 players online: TadMC_YT, .niex2277\u{1B}[0m"))
        XCTAssertEqual(value.online, 2); XCTAssertEqual(value.maximum, 60)
        XCTAssertEqual(value.names, ["TadMC_YT", ".niex2277"])
        let empty = try XCTUnwrap(PlayerSnapshot.parseLog("[INFO]: There are 0 of a max of 60 players online:"))
        XCTAssertEqual(empty.online, 0); XCTAssertTrue(empty.names.isEmpty)
        XCTAssertNil(PlayerSnapshot.parseLog("[INFO]: TadMC_YT joined the game"))
    }
    func testProviderPlayerResponseShapesAndUnknownFallback() throws {
        let api = try JSON.parse(#"{"data":{"players":{"online":2,"max":60,"sample":[{"name":"TadMC_YT"},{"name":".niex2277"}]}}}"#)
        XCTAssertEqual(PlayerSnapshot.decode(api)?.names, ["TadMC_YT", ".niex2277"])
        XCTAssertEqual(PlayerSnapshot.decode(api)?.online, 2)
        XCTAssertEqual(PlayerSnapshot.decode(api)?.maximum, 60)
        XCTAssertEqual(PlayerSnapshot.decode(try JSON.parse(#"{"data":[]}"#))?.online, 0)
        XCTAssertNil(PlayerSnapshot.decode(try JSON.parse(#"{"status":"unavailable"}"#)))
        XCTAssertEqual(try JSON.parse(#"[{"attributes":{"name":"a"}}]"#).rows.first?["name"].string, "a")
    }
    @MainActor func testDatabaseCredentialsAndPropertyVariants() throws {
        let db = DatabaseInfo(value: try JSON.parse(#"{"name":"s1_game","host":{"address":"db.example.net","port":3307},"relationships":{"password":{"data":{"attributes":{"password":"fixture-secret"}}}}}"#))
        XCTAssertEqual(db.host, "db.example.net"); XCTAssertEqual(db.port, "3307"); XCTAssertEqual(db.password, "fixture-secret")
        let file = ServerProperties.parse("#Minecraft server properties\nmax-players=60\nonline-mode=true\nlevel-name=world\n")
        XCTAssertEqual(file.values["max-players"], "60"); XCTAssertEqual(file.values["online-mode"], "true")
        XCTAssertEqual(ServerProperties.kind("max-players", value: "60"), .integer)
        XCTAssertEqual(ServerProperties.kind("difficulty", value: "normal"), .choice(["peaceful", "easy", "normal", "hard"]))
    }
    @MainActor func testWorkspacePersistsFavoritesWithoutChangingExistingAccounts() throws {
        let suite = "PanelTests." + UUID().uuidString
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite)); defer { defaults.removePersistentDomain(forName: suite) }
        defaults.set(Data("existing-account-fixture".utf8), forKey: "profiles")
        let api = PanelAPI(base: URL(string: "https://panel.ultraservers.com")!, key: "fixture")
        let other = PanelAPI(base: URL(string: "https://another.example")!, key: "fixture")
        let server = Server(data: Demo.servers[0])
        let workspace = WorkspaceStore(defaults: defaults)
        workspace.toggleServer(api, server); workspace.toggleFolder(api, server, "/plugins")
        workspace.addShortcut(CommandShortcut(name: "Spieler", command: "list"), api, server)
        let restored = WorkspaceStore(defaults: defaults)
        XCTAssertTrue(restored.favorites.contains(restored.key(api, server)))
        XCTAssertFalse(restored.favorites.contains(restored.key(other, server)))
        XCTAssertTrue(restored.isPinned(api, server, "/plugins"))
        XCTAssertEqual(restored.shortcuts[restored.key(api, server)]?.first?.command, "list")
        XCTAssertEqual(defaults.data(forKey: "profiles"), Data("existing-account-fixture".utf8))
    }
}
