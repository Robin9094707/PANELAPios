import XCTest
import CryptoKit
@testable import Panel

final class AddonUpdateTests: XCTestCase {
    func testScreenshotChatAndProxyConnectionFormats() {
        let cases: [(String, ConsoleCategory)] = [
            (">....[19:25:25 INFO]: [Admin] Arian1227950 » any1 got a farm", .chat),
            (">....[19:25:47 INFO]: nintfjr » Not really but I'm happy", .chat),
            (">....>....[19:45:27 INFO]: Systemchat: There are 3 of a max of 60 players online", .other),
            (">....[19:27:21 INFO]: [Geyser-Spigot] Player connected with username arian1227950 (2193)", .joins),
            (">....[19:27:21 INFO]: [Geyser-Spigot] arian1227950 (logged in as: arian1227950) has connected to the Java server", .joins),
            (">....[19:27:21 INFO]: [floodgate] Floodgate player logged in as .arian1227950 joined (UUID: demo)", .joins),
            (">....[19:27:21 INFO]: .arian1227950[/95.90.185.146:0] logged in with entity id 39073 at (world)", .joins),
            (">....[19:37:39 INFO]: [Geyser-Spigot] DJN712345 has disconnected from the Java server because of Bedrock client disconnected", .leaves),
            (">....[19:44:39 INFO]: inspecteursnoopy lost connection: Disconnected", .leaves),
            (">....[19:27:22 WARN]: [Geyser-Spigot] Could not deliver metadata HTTP 422", .warnings),
            (">....[19:24:41 INFO]: Arian1227950 issued server command: /home farm", .commands),
            ("[12:00:00 INFO]: [Admin] User » Bob issued server command: hello", .chat),
            ("[12:00:00 INFO]: <User> joined the game ERROR", .chat),
            ("[12:00:00 INFO]: [Plugin] HTTP request » completed", .other)
        ]
        for (line, category) in cases { XCTAssertEqual(ConsoleAnalysis.parse(line).category, category, line) }
    }
    func testVersionProfilesNeverTreatPaperBuildOrUnknownAsCompatible() {
        XCTAssertTrue(AddonTarget(game: "26.3").valid)
        XCTAssertTrue(AddonTarget(game: "1.20.1").valid)
        XCTAssertFalse(AddonTarget(game: "latest").valid)
        XCTAssertFalse(AddonTarget(game: "PaperMC 26.3").valid)
        XCTAssertEqual(AddonTarget.detect("This server is running Paper (MC: 26.3)"), "26.3")
        XCTAssertEqual(AddonTarget.detect("Starting minecraft server version 1.20.1"), "1.20.1")
        XCTAssertNil(AddonTarget.detect("Paper build 1234"))
        XCTAssertEqual(MarketplaceClient.newer("v2.0.0", than: "1.9.9"), true)
        XCTAssertEqual(MarketplaceClient.newer("1.0", than: "2.0"), false)
        XCTAssertNil(MarketplaceClient.newer("release-four", than: "release-three"))
    }
    static func providerVersion(_ updates: [String: JSON] = [:]) -> JSON {
        var v: [String: JSON] = ["id": .string("release2"), "project_id": .string("original"), "name": .string("Plugin 2"), "version_number": .string("2.0"), "version_type": .string("release"), "game_versions": .array([.string("26.3")]), "loaders": .array([.string("paper")]), "files": .array([.object(["primary": .bool(true), "filename": .string("Plugin.jar"), "url": .string("https://cdn.modrinth.com/Plugin.jar"), "hashes": .object(["sha1": .string("abc")])])])]
        v.merge(updates, uniquingKeysWith: { _, new in new }); return .object(v)
    }
    func testProviderRequiresExactGameLoaderAndServerSideJar() {
        let target = AddonTarget(game: "26.3")
        XCTAssertNotNil(MarketplaceClient.modrinthRelease(Self.providerVersion(), target: target))
        XCTAssertNil(MarketplaceClient.modrinthRelease(Self.providerVersion(["game_versions": .array([.string("26.2")])]), target: target))
        XCTAssertNil(MarketplaceClient.modrinthRelease(Self.providerVersion(["loaders": .array([.string("fabric")])]), target: target))
        XCTAssertNil(MarketplaceClient.modrinthRelease(Self.providerVersion(["environment": .string("client_only")]), target: target))
        XCTAssertNotNil(MarketplaceClient.modrinthRelease(Self.providerVersion(["loaders": .array([.string("bukkit")])]), target: target))
        XCTAssertNil(MarketplaceClient.modrinthRelease(Self.providerVersion(["files": .array([.object(["filename": .string("Plugin-sources.zip")])])]), target: target))
    }
    func testDownloadHashMismatchAndHtmlFailClosed() throws {
        let data = Data([0x50,0x4b] + Array(repeating: 1, count: 64))
        var release = try XCTUnwrap(MarketplaceClient.modrinthRelease(Self.providerVersion(), target: AddonTarget(game: "26.3")))
        release.sha1 = Insecure.SHA1.hash(data: data).map { String(format: "%02x", $0) }.joined()
        XCTAssertNoThrow(try MarketplaceClient.verify(data, release: release))
        XCTAssertThrowsError(try MarketplaceClient.verify(data + Data([1]), release: release))
        XCTAssertThrowsError(try MarketplaceClient.verify(Data("<html>Login required</html>".utf8), release: release))
        XCTAssertEqual(AddonFingerprint.murmur(Data("a b\n\tc\r".utf8)), AddonFingerprint.murmur(Data("abc".utf8)))
        XCTAssertEqual(AddonFingerprint.murmur(Data()), 1540447798)
    }
    func testCacheRoundTripPreservesMappingsAndServerActivityHasNoSecrets() throws {
        var cache = AddonCache(); cache.target.game = "26.3"; cache.links["Lands.jar"] = AddonLink(provider: .spigot, project: "53313")
        let decoded = try JSONDecoder().decode(AddonCache.self, from: JSONEncoder().encode(cache))
        XCTAssertEqual(decoded.links["Lands.jar"]?.project, "53313"); XCTAssertEqual(decoded.target.game, "26.3")
        let attributes = ServerActivity(panel: "https://example.com", serverID: "abc", name: "Simplex")
        let data = try JSONEncoder().encode(attributes)
        XCTAssertFalse(String(decoding: data, as: UTF8.self).lowercased().contains("token"))
        XCTAssertFalse(String(decoding: data, as: UTF8.self).lowercased().contains("password"))
    }
}

final class MarketplaceNetworkTests: XCTestCase {
    override func tearDown() { StubProtocol.handler = nil; super.tearDown() }
    func client() -> MarketplaceClient { let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [StubProtocol.self]; return MarketplaceClient(session: URLSession(configuration: config)) }
    func testExactHashFindsOriginalAndFiltersLoaderGameAndStableChannel() async throws {
        let metadata = AddonUpdateTests.providerVersion()
        var requests = 0
        StubProtocol.handler = { request in
            requests += 1
            XCTAssertNil(request.value(forHTTPHeaderField: "Authorization")); XCTAssertNil(request.value(forHTTPHeaderField: "x-api-key"))
            XCTAssertEqual(request.url?.host, "api.modrinth.com")
            if request.url!.path.contains("version_file") { return (200, Data(#"{"id":"old","project_id":"original","date_published":"2026-01-01T00:00:00Z"}"#.utf8)) }
            let query = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)!.queryItems!
            XCTAssertEqual(try JSON.parse(query.first { $0.name == "game_versions" }!.value!), .array([.string("26.3")]))
            XCTAssertTrue(try JSON.parse(query.first { $0.name == "loaders" }!.value!).array.contains(.string("paper")))
            return (200, try JSONEncoder().encode(JSON.array([metadata])))
        }
        let item = AddonInventory(filename: "Plugin.jar", signature: "stamp", name: "Plugin", version: "1.0", fingerprint: .make(Data("old".utf8)))
        let (mapping, status) = try await client().latest(item, link: nil, target: AddonTarget(game: "26.3"))
        XCTAssertEqual(mapping?.project, "original"); XCTAssertEqual(status.text, "Update verfügbar"); XCTAssertEqual(status.release?.version, "2.0"); XCTAssertEqual(requests, 2)
    }
    func testPremiumSpigotNeverUsesUnauthenticatedDownload() async throws {
        StubProtocol.handler = { request in
            XCTAssertEqual(request.url?.host, "api.spiget.org")
            XCTAssertNil(request.value(forHTTPHeaderField: "Authorization"))
            if request.url!.path.hasSuffix("versions/latest") { return (200, Data(#"{"id":22,"name":"2.0","releaseDate":12345}"#.utf8)) }
            return (200, Data(#"{"id":53313,"name":"Lands","premium":true,"external":false,"testedVersions":["26.3"]}"#.utf8))
        }
        let item = AddonInventory(filename: "Lands.jar", signature: "stamp", name: "Lands", version: "1.0", fingerprint: .make(Data()))
        let (_, status) = try await client().latest(item, link: AddonLink(provider: .spigot, project: "53313"), target: AddonTarget(game: "26.3"))
        XCTAssertNil(status.release?.download); XCTAssertFalse(try XCTUnwrap(status.release).compatibility)
        XCTAssertTrue(status.release?.website.contains("spigotmc.org/resources/53313") == true)
    }
    func testCurseForgeKeyIsScopedAndDisabledDistributionHasNoInventedUrl() async throws {
        let previous = try? Vault.read(MarketplaceClient.curseKeyID)
        try Vault.save("synthetic-curse-key", id: MarketplaceClient.curseKeyID)
        defer { if let previous { try? Vault.save(previous, id: MarketplaceClient.curseKeyID) } else { Vault.delete(MarketplaceClient.curseKeyID) } }
        StubProtocol.handler = { request in
            XCTAssertEqual(request.url?.host, "api.curseforge.com")
            XCTAssertEqual(request.value(forHTTPHeaderField: "x-api-key"), "synthetic-curse-key")
            XCTAssertNil(request.value(forHTTPHeaderField: "Authorization"))
            if request.url!.path.hasSuffix("/files") {
                let body = #"{"data":[{"id":22,"isAvailable":true,"displayName":"Mod 2","fileName":"Mod-2.jar","releaseType":1,"gameVersions":["26.3","Fabric"],"downloadUrl":null,"hashes":[{"algo":1,"value":"provider-sha"}],"dependencies":[{"modId":42,"relationType":3}]}]}"#
                return (200, Data(body.utf8))
            }
            return (200, Data(#"{"data":{"links":{"websiteUrl":"https://www.curseforge.com/minecraft/mc-mods/original"}}}"#.utf8))
        }
        let item = AddonInventory(filename: "Mod.jar", signature: "stamp", name: "Mod", version: "1.0", fingerprint: .make(Data()))
        let (_, status) = try await client().latest(item, link: AddonLink(provider: .curseforge, project: "123"), target: AddonTarget(game: "26.3", loader: "fabric"))
        XCTAssertNil(status.release?.download)
        XCTAssertTrue(status.release?.compatibility == true)
        XCTAssertEqual(status.release?.dependencies, ["Erforderlich: CurseForge 42"])
    }

}
