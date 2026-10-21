import XCTest
@testable import Panel

@MainActor final class AMPTests: XCTestCase {
    func testLegacyProfilesKeepTheirIDsAndProvider() throws {
        let id = UUID()
        let data = Data("[{\"id\":\"\(id.uuidString)\",\"name\":\"Existing\",\"url\":\"https://panel.example\"}]".utf8)
        let profiles = try JSONDecoder().decode([PanelProfile].self, from: data)
        XCTAssertEqual(profiles.first?.id, id); XCTAssertEqual(profiles.first?.provider, .pterodactyl)
        XCTAssertEqual(try JSONDecoder().decode([PanelProfile].self, from: JSONEncoder().encode(profiles)), profiles)
    }
    func testAMPNormalizationAndStatusMetrics() throws {
        XCTAssertEqual(try AMPAPI.normalize("https://panel.example/control/API/").absoluteString, "https://panel.example/control")
        XCTAssertThrowsError(try AMPAPI.normalize("http://panel.example"))
        XCTAssertThrowsError(try AMPAPI.filePath("/mods/../secret"))
        let status = try JSON.from(["State": 20, "Uptime": "02:03:04", "Metrics": ["CPU Usage": ["RawValue": 25, "Units": "%"], "Memory Usage": ["RawValue": 1024, "Units": "MB"]]])
        let value = AMPAPI.resources(status)
        XCTAssertEqual(value["current_state"].string, "running")
        XCTAssertEqual(value["resources"]["memory_bytes"].double, 1_073_741_824)
        XCTAssertEqual(value["resources"]["uptime"].double, 7_384_000)
        XCTAssertEqual(AMPAPI.state(.number(75)), "unknown")
    }
    func body(_ request: URLRequest) throws -> JSON {
        if let data = request.httpBody { return try JSONDecoder().decode(JSON.self, from: data) }
        guard let stream = request.httpBodyStream else { return .null }
        stream.open(); defer { stream.close() }
        var data = Data(), buffer = [UInt8](repeating: 0, count: 4096)
        while stream.hasBytesAvailable { let count = stream.read(&buffer, maxLength: buffer.count); if count <= 0 { break }; data.append(contentsOf: buffer.prefix(count)) }
        return try JSONDecoder().decode(JSON.self, from: data)
    }
    func testControllerAndChildSessionsUseSeparateRoutesAndRenewOnlyExpiredSessions() async throws {
        let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [StubProtocol.self]
        let amp = try AMPAPI(base: URL(string: "https://panel.example/control")!, secret: AMPCredentials(username: "tester", password: "synthetic-password").encoded, session: URLSession(configuration: config))
        var rootLogins = 0, childLogins = 0, statuses = 0
        StubProtocol.handler = { request in
            XCTAssertEqual(request.httpMethod, "POST"); XCTAssertNil(request.value(forHTTPHeaderField: "Authorization"))
            let path = request.url!.path, body = try self.body(request)
            let result: JSON
            if path == "/control/API/Core/Login" { rootLogins += 1; result = try .from(["success": true, "sessionID": "root-session", "rememberMeToken": "synthetic-remember-token"]) }
            else if path.hasSuffix("/Core/GetModuleInfo") { XCTAssertEqual(body["SESSIONID"].string, "root-session"); result = try .from(["ModuleName": "ADS"]) }
            else if path.hasSuffix("/ADSModule/GetInstances") { result = try JSON.parse("[{\"FriendlyName\":\"Host\",\"AvailableInstances\":[{\"InstanceID\":\"abc\",\"InstanceName\":\"Minecraft01\",\"FriendlyName\":\"MC\",\"Module\":\"Minecraft\",\"Running\":true}]}]") }
            else if path.hasSuffix("/ADSModule/ManageInstance") { XCTAssertEqual(body["InstanceId"].string, "abc"); XCTAssertEqual(body["SESSIONID"].string, "root-session"); result = try .from(["Status": true, "Result": "child-token"]) }
            else if path == "/control/API/ADSModule/Servers/abc/API/Core/Login" { childLogins += 1; XCTAssertEqual(body["token"].string, "child-token"); XCTAssertEqual(body["password"].string, ""); result = try .from(["success": true, "sessionID": "child-session"]) }
            else if path == "/control/API/ADSModule/Servers/abc/API/Core/GetStatus" {
                XCTAssertEqual(body["SESSIONID"].string, "child-session"); statuses += 1
                result = statuses == 1 ? try .from(["Title": "UnauthorizedAccessException", "Message": "Requires Session.Exists"]) : try .from(["State": 20])
            } else { XCTFail("Unexpected route " + path); result = .null }
            return (200, try JSONEncoder().encode(result))
        }
        defer { StubProtocol.handler = nil }
        let rows = try await amp.discover(); XCTAssertEqual(rows.first?["identifier"].string, "abc")
        XCTAssertTrue(amp.controller)
        let status = try await amp.call("Core/GetStatus", server: "abc")
        XCTAssertEqual(status["State"].int, 20); XCTAssertEqual(rootLogins, 1); XCTAssertEqual(childLogins, 2); XCTAssertEqual(statuses, 2)
    }
    func testModFormatsAndWrongModOrLoaderRejected() throws {
        func fabric(_ id: String, _ version: String) throws -> ModDescriptor { try ModArchive.parse(["fabric.mod.json": "{\"id\":\"\(id)\",\"name\":\"Example\",\"version\":\"\(version)\",\"depends\":{\"minecraft\":\"1.20.1\"}}"] ) }
        let old = try fabric("example", "1.0.0"), next = try fabric("example", "2.0.0")
        XCTAssertNoThrow(try old.validateUpdate(next))
        XCTAssertThrowsError(try old.validateUpdate(fabric("other", "2.0.0")))
        XCTAssertThrowsError(try old.validateUpdate(old))
        let toml = "modLoader=\"javafml\"\n[[mods]]\nmodId=\"example\"\nversion=\"${file.jarVersion}\"\ndisplayName=\"Forge Example\"\n[[dependencies.example]]\nmodId=\"minecraft\"\nversionRange=\"[1.20.1,1.21)\"\n"
        let forge = try ModArchive.parse(["META-INF/mods.toml": toml, "META-INF/MANIFEST.MF": "Implementation-Version: 2.0.0\n"])
        XCTAssertEqual(forge.ids, ["example"]); XCTAssertEqual(forge.version, "2.0.0"); XCTAssertEqual(forge.minecraft, "[1.20.1,1.21)")
        XCTAssertThrowsError(try old.validateUpdate(forge))
        let neo = try ModArchive.parse(["META-INF/neoforge.mods.toml": toml.replacingOccurrences(of: "${file.jarVersion}", with: "2.0.0")]); XCTAssertEqual(neo.loader, "NeoForge")
        let quilt = try ModArchive.parse(["quilt.mod.json": "{\"quilt_loader\":{\"id\":\"example\",\"version\":\"2.0.0\",\"metadata\":{\"name\":\"Example\"}}}"])
        XCTAssertEqual(quilt.loader, "Quilt")
        let legacy = try ModArchive.parse(["mcmod.info": "[{\"modid\":\"legacy\",\"name\":\"Legacy\",\"version\":\"1.0\",\"mcversion\":\"1.12.2\"}]"])
        XCTAssertEqual(legacy.ids, ["legacy"]); XCTAssertEqual(legacy.minecraft, "1.12.2")
        XCTAssertThrowsError(try ModArchive.parse([:]))
    }
}
