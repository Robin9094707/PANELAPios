import XCTest
@testable import Panel

final class QualityOfLifeTests: XCTestCase {
    func testTPSConsoleOutputAndPowerTransitions() throws {
        let tps = try XCTUnwrap(TPSSnapshot.parse("\u{1B}=>....[05:17:26 INFO]: TPS from last 1m, 5m, 15m: 20.0, 19.5, 12.0"))
        XCTAssertEqual(tps.minute, 20); XCTAssertEqual(tps.five, 19.5); XCTAssertEqual(tps.fifteen, 12)
        XCTAssertNil(TPSSnapshot.parse("CPU: 20%"))
        XCTAssertTrue(PowerPolicy.allows("start", state: "offline"))
        XCTAssertFalse(PowerPolicy.allows("start", state: "running"))
        XCTAssertFalse(PowerPolicy.allows("restart", state: "stopping"))
        XCTAssertTrue(PowerPolicy.allows("stop", state: "starting"))
        XCTAssertFalse(PowerPolicy.allows("kill", state: "unknown"))
    }
    @MainActor func testSavedPanelRestoresAfterTemporaryKeychainFailureAndRelaunch() throws {
        let suite = "PanelRestore." + UUID().uuidString
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite)); defer { defaults.removePersistentDomain(forName: suite) }
        let profile = PanelProfile(name: "Fixture", url: "https://panel.example.com")
        defaults.set(try JSONEncoder().encode([profile]), forKey: "profiles"); defaults.set(profile.id.uuidString, forKey: "selectedPanel")
        var attempt = 0
        let store = AppStore(defaults: defaults, readSecret: { id in
            XCTAssertEqual(id, profile.id); attempt += 1
            if attempt == 1 { throw PanelError(message: "Device temporarily locked") }; return "fixture-key"
        }, launchDemo: false)
        XCTAssertNil(store.api); XCTAssertEqual(store.selected, profile.id)
        store.restoreConnectionIfNeeded()
        XCTAssertEqual(store.api?.base.host, "panel.example.com"); XCTAssertNil(store.error)
        let reopened = AppStore(defaults: defaults, readSecret: { _ in "fixture-key" }, launchDemo: false)
        XCTAssertEqual(reopened.selected, profile.id); XCTAssertNotNil(reopened.api)
        XCTAssertEqual(reopened.profiles, [profile])
    }
    func testAllNBTTypesRoundTripAndEditedHealth() throws {
        let raw = try XCTUnwrap(Data(base64Encoded: "CgAABQAGSGVhbHRoQWgAAAEADEludnVsbmVyYWJsZQECAANBaXIBLAMAB1hwTGV2ZWwAAAAMBAAFQ2xvY2uAAAAAAAAAAAYAClVua25vd25OYU5/+AAAAAAAAQcABUJ5dGVzAAAAAwCA/wgAB1VuaWNvZGUACMCA7aC97biACQADUG9zBgAAAANASkAAAAAAAEBRAAAAAAAAwGmoAAAAAAAKAAZidWtraXQIAA1sYXN0S25vd25OYW1lAA4uRml4dHVyZVBsYXllcgALAARVVUlEAAAABAAAAAAAAAAAAAkAAAo+6NUMAAVMb25ncwAAAAKAAAAAAAAAAH//////////CQAFRW1wdHkKAAAAAAA="))
        let compressed = try BinaryCodec.compress(raw)
        var document = try NBTDocument.decode(compressed)
        XCTAssertEqual(document.root.child("Health")?.text, "14.5")
        XCTAssertEqual(document.root.child("bukkit")?.child("lastKnownName")?.text, ".FixturePlayer")
        XCTAssertEqual(document.root.child("Unicode")?.text, "\0😀")
        XCTAssertEqual(try BinaryCodec.inflateData(document.encode()), raw)
        let i = try XCTUnwrap(document.root.children.firstIndex { $0.name == "Health" })
        try document.root.children[i].setText("20")
        let reread = try NBTDocument.decode(document.encode())
        XCTAssertEqual(reread.root.child("Health")?.text, "20.0")
        XCTAssertEqual(reread.root.child("UnknownNaN")?.scalar, document.root.child("UnknownNaN")?.scalar)
        document.compression = .zlib; XCTAssertEqual(try NBTDocument.decode(document.encode()).root.child("Health")?.text, "20.0")
        document.compression = .none; XCTAssertEqual(try NBTDocument.decode(document.encode()).root.child("Health")?.text, "20.0")
    }
    func testMalformedNBTAndModifiedUTF8AreHandled() throws {
        XCTAssertThrowsError(try NBTDocument.decode(Data([10,0,0,8,0,1,65,255,255])))
        XCTAssertThrowsError(try BinaryCodec.inflateData(Data([0x1f,0x8b,0])))
        let mutf = try ModifiedUTF8.encode("\0😀")
        XCTAssertEqual(Array(mutf), [0xc0,0x80,0xed,0xa0,0xbd,0xed,0xb8,0x80])
        XCTAssertEqual(try ModifiedUTF8.decode(mutf), "\0😀")
        var byte = NBTNode(type: 1, name: "flag"); XCTAssertThrowsError(try byte.setText("128"))
        let compressed = try BinaryCodec.compress(Data(repeating: 0, count: 100000))
        XCTAssertThrowsError(try BinaryCodec.inflateData(compressed, limit: 100))
    }
    func testJARIdentityVersionAndZIPMetadata() throws {
        let jar = try XCTUnwrap(Data(base64Encoded: "UEsDBBQAAAAIAOA8VF3najRJQgAAAE8AAAAKAAAAcGx1Z2luLnltbMtLzE21UnDLrCgpLUoNyClNz8zjKkstKs7Mz7NSMNQz0jPgyk3MBLJTS/VSKxJzC3JS9aDKEgsydeFK1YFqDdW5AFBLAQIUAxQAAAAIAOA8VF3najRJQgAAAE8AAAAKAAAAAAAAAAAAAACAAQAAAABwbHVnaW4ueW1sUEsFBgAAAAABAAEAOAAAAGoAAAAAAA=="))
        let next = try PluginArchive.descriptor(jar)
        XCTAssertEqual(next.name, "FixturePlugin"); XCTAssertEqual(next.version, "1.2.0")
        let old = PluginDescriptor(name: "FixturePlugin", version: "1.1.9", main: "eu.example.Plugin", apiVersion: "1.21")
        XCTAssertFalse(try old.validateUpdate(next).isEmpty)
        XCTAssertThrowsError(try next.validateUpdate(next)); XCTAssertThrowsError(try next.validateUpdate(old))
        XCTAssertThrowsError(try old.validateUpdate(PluginDescriptor(name: "WrongPlugin", version: "9.0.0", main: "other.Main", apiVersion: "1.21")))
        XCTAssertThrowsError(try PluginArchive.descriptor(Data("not a JAR".utf8)))
        var broken = jar; broken[45] ^= 0xff; XCTAssertThrowsError(try PluginArchive.descriptor(broken))
    }
    func testYAMLQuickFormPreservesIndentAndComment() throws {
        let text = "settings:\n  enabled: true # keep me\n  max: 12\n  complex: [a, b]\n  quoted: 'leave alone'\n  message: hello\n    continued\n"
        let rows = ConfigurationField.parse(text, properties: false)
        XCTAssertEqual(rows.map(\.key), ["enabled", "max", "quoted"])
        XCTAssertEqual(try rows[0].replacement("false"), "  enabled: false # keep me")
        XCTAssertThrowsError(try rows[1].replacement("invalid"))
    }
}
