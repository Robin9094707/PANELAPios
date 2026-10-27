import XCTest
@testable import Panel

final class PluginServerAPITests: XCTestCase {
    @MainActor func testServerInspectionAndBinaryUploadUseSuiteOnly() async throws {
        var routes: [String] = [], uploaded: Data?
        let bodyReader = PluginUploadRegressionTests.Host()
        let data = PluginUploadRegressionTests.next
        let old = try PluginArchive.descriptor(PluginUploadRegressionTests.old), next = try PluginArchive.descriptor(data)
        let hash = PluginArchive.digest(data)
        let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [StubProtocol.self]
        StubProtocol.handler = { request in
            let route = request.url!.path; routes.append(route)
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer suite-fixture")
            let reply: JSON
            switch route {
            case "/v1/health": reply = .object(["apiVersion": .number(1), "serverId": .string("fixture"), "scope": .string("full"), "features": .array([.string("plugin-files-v1")])])
            case "/v1/prepare":
                let body = try JSONDecoder().decode(JSON.self, from: bodyReader.body(request))
                XCTAssertEqual(body["path"].string, "plugins.stage"); XCTAssertEqual(body["values"]["sha256"].string, hash)
                reply = .object(["ticket": .string("bound-confirmation")])
            case "/v1/plugin-files/upload":
                XCTAssertEqual(request.value(forHTTPHeaderField: "Content-Type"), "application/java-archive")
                XCTAssertTrue(request.url!.query!.contains("ticket=bound-confirmation"))
                uploaded = try bodyReader.body(request)
                reply = .object(["queued": .bool(true), "filename": .string("installed.jar"), "sha256": .string(hash), "updateDirectory": .string("/plugins/update")])
            default: XCTFail("Unexpected route " + route); reply = .null
            }
            return (200, try JSONEncoder().encode(reply))
        }
        defer { StubProtocol.handler = nil }
        let connection = SuiteConnection(panel: "https://panel.invalid", server: "server", endpoint: "https://suite.invalid", fingerprint: "", serverIdentity: "fixture")
        let client = try SuiteAPI(connection, token: "suite-fixture", session: URLSession(configuration: config))
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true); defer { try? FileManager.default.removeItem(at: folder) }
        let file = folder.appendingPathComponent("update.jar"); try data.write(to: file)
        let candidate = PluginUpdateCandidate(filename: "installed.jar", file: file, installed: old, next: next, originalHash: PluginArchive.digest(PluginUploadRegressionTests.old), report: "checked", serverFilename: "installed.jar")
        let directory = try await PluginServerAPI.stage(candidate, client: client)
        XCTAssertEqual(directory, "/plugins/update"); XCTAssertEqual(uploaded, data)
        XCTAssertEqual(routes, ["/v1/health", "/v1/prepare", "/v1/plugin-files/upload"])
    }
    @MainActor func testServerMetadataParsesPaperYAML() throws {
        let row = try JSON.from(["yaml": "name: Test\nversion: 8.6.0\nmain: eu.simplex.Main\n", "descriptorFile": "paper-plugin.yml"])
        let value = try PluginServerAPI.metadata(row)
        XCTAssertEqual(value.name, "Test"); XCTAssertEqual(value.descriptorFile, "paper-plugin.yml")
    }
}
