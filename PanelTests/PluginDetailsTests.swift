import XCTest
@testable import Panel

final class PluginDetailsTests: XCTestCase {
    func testVersionedNamesAndPaperExactIdentity() throws {
        let old = PluginDescriptor(name: "AirTags", version: "2.0.0", main: "eu.simplex.AirTags", apiVersion: "26.3")
        let next = PluginDescriptor(name: "AirTags", version: "3.0.0", main: old.main, apiVersion: "26.3")
        XCTAssertEqual(next.versionedFilename, "AirTags-3.0.0.jar")
        XCTAssertFalse(try old.validateUpdate(next).isEmpty)
        XCTAssertThrowsError(try old.validateUpdate(PluginDescriptor(name: "airtags", version: "3.0.0", main: old.main, apiVersion: "26.3")))
        try PluginUpdatePlan.checkFilename(next.versionedFilename, installed: "AirTags-2.0.0.jar", files: ["AirTags-2.0.0.jar", "Other.jar"])
        XCTAssertThrowsError(try PluginUpdatePlan.checkFilename(next.versionedFilename, installed: "AirTags-2.0.0.jar", files: ["airtags-3.0.0.JAR"]))
        XCTAssertThrowsError(try PluginUpdatePlan.checkFilename(next.versionedFilename, installed: next.versionedFilename, files: [next.versionedFilename, "airtags-3.0.0.jar"]))
        XCTAssertThrowsError(try PluginUpdatePlan.checkPending(next, next: next))
        XCTAssertNoThrow(try PluginUpdatePlan.checkPending(PluginDescriptor(name: "Other", version: "1.0", main: "other.Main", apiVersion: "26.3"), next: next))
        let unsafe = PluginDescriptor(name: "../../Air Tags", version: "../3.0.0/evil", main: old.main, apiVersion: "26.3").versionedFilename
        XCTAssertFalse(unsafe.contains("/")); XCTAssertFalse(unsafe.hasPrefix(".")); XCTAssertTrue(unsafe.hasSuffix(".jar"))
        let long = PluginDescriptor(name: String(repeating: "N", count: 200), version: String(repeating: "v", count: 200), main: old.main, apiVersion: "26.3").versionedFilename
        XCTAssertLessThan(long.utf8.count, 255)
    }
    func testRichMetadataAndCustomUpdateDirectory() throws {
        let yaml = "name: AirTags\nversion: '3.0.0'\nmain: eu.simplex.AirTags\napi-version: '26.3'\nauthors:\n  - RJ\n  - 'Demo Team'\ndescription: >\n  Find your items.\n  Works on Paper.\nwebsite: https://example.org\npermissions:\n  airtags.use:\n    default: true\n"
        let descriptor = try PluginDescriptor.parse(yaml)
        XCTAssertEqual(descriptor.name, "AirTags"); XCTAssertEqual(descriptor.version, "3.0.0")
        XCTAssertEqual(descriptor.author, "RJ, Demo Team")
        XCTAssertEqual(descriptor.summary, "Find your items. Works on Paper.")
        XCTAssertEqual(descriptor.rawYAML, yaml)
        XCTAssertEqual(descriptor.website, "https://example.org")
        XCTAssertEqual(try PluginUpdatePlan.directory("settings:\n  update-folder: 'incoming/updates' # keep\n"), "/plugins/incoming/updates")
        XCTAssertEqual(try PluginUpdatePlan.directory("settings:\n  allow-end: true\n"), "/plugins/update")
        for config in ["settings:\n  update-folder: '../escape'", "update-folder: ''", "update-folder: '/absolute'", "update-folder: [bad]", "update-folder: ''"] { XCTAssertThrowsError(try PluginUpdatePlan.directory(config)) }
    }
    @MainActor func testCatalogSharesReadsAndInvalidatesMetadata() async throws {
        let api = PanelAPI(base: URL(string: "https://demo.invalid")!, key: "", demo: true)
        let entry = try JSON.from(["name": "ExamplePlugin.jar", "size": 4096, "modified_at": "2026-10-20T12:00:00Z"])
        await api.plugins.load(api: api, server: "server", entry: entry)
        XCTAssertEqual(api.plugins.descriptor(server: "server", entry: entry)?.version, "2.0.0")
        XCTAssertNil(PanelAPI(base: api.base, key: "other").plugins.descriptor(server: "server", entry: entry))
        let changed = try JSON.from(["name": "ExamplePlugin.jar", "size": 8192, "modified_at": "2026-10-20T12:01:00Z"])
        XCTAssertNil(api.plugins.descriptor(server: "server", entry: changed))
        api.plugins.clear(server: "server")
        XCTAssertNil(api.plugins.descriptor(server: "server", entry: entry))
        await api.plugins.load(api: api, server: "server", entry: entry)
        XCTAssertEqual(api.plugins.descriptor(server: "server", entry: entry)?.name, "ExamplePlugin")
        XCTAssertTrue(api.plugins.loading.isEmpty)
        let pending = try JSON.from(["name": "ExamplePlugin-3.0.0.jar", "size": 20 * 1024 * 1024, "modified_at": "new"])
        let next = PluginDescriptor(name: "ExamplePlugin", version: "3.0.0", main: "eu.example.Plugin", apiVersion: "26.3")
        api.plugins.rememberVerifiedUpload(next, server: "server", directory: "/plugins/update", entry: pending)
        XCTAssertEqual(api.plugins.descriptor(server: "server", directory: "/plugins/update", entry: pending)?.version, "3.0.0")
        XCTAssertEqual(api.plugins.descriptor(server: "server", entry: entry)?.version, "2.0.0")
    }
}
