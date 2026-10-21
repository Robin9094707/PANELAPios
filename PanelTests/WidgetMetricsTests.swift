import XCTest
@testable import Panel

final class WidgetMetricsTests: XCTestCase {
    func testAMPTimeSpanDaysAndPartialMetrics() throws {
        XCTAssertEqual(AMPMetrics.seconds(.string("0:02:57:46")), 10_666)
        XCTAssertEqual(AMPMetrics.seconds(.string("2.03:04:05.250")), 183_845.25)
        XCTAssertEqual(AMPMetrics.seconds(.string("03:04:05")), 11_045)
        XCTAssertNil(AMPMetrics.seconds(.string("unavailable")))
        let full = try JSON.from(["State": 20, "Uptime": "1:03:04:05", "Metrics": ["Memory Usage": ["RawValue": 2048, "MaxValue": 8192, "Units": "MB"], "CPU Usage": ["RawValue": 12.5, "Units": "%"]]])
        let delta = try JSON.from(["State": 20, "Metrics": ["CPU Usage": ["RawValue": 27.5]]])
        let result = AMPMetrics.resources(AMPMetrics.merge(full, delta))["resources"]
        XCTAssertEqual(result["memory_bytes"].double, 2_147_483_648)
        XCTAssertEqual(result["memory_limit_bytes"].double, 8_589_934_592)
        XCTAssertEqual(result["cpu_absolute"].double, 27.5)
        XCTAssertEqual(result["uptime"].double, 97_445_000)
        XCTAssertEqual(AMPMetrics.resources(.object(["State": .number(20)]))["resources"]["memory_bytes"], .null)
    }
    func testLocalizedMetricNamesAndAlternateValues() throws {
        let status = try JSON.from(["State": "Ready", "Metrics": ["Arbeitsspeicher": ["Value": "1.5", "Units": "GiB"], "Processor": ["Percent": 32]]])
        let result = AMPMetrics.resources(status)["resources"]
        XCTAssertEqual(result["memory_bytes"].double, 1_610_612_736)
        XCTAssertEqual(result["cpu_absolute"].double, 32)
    }
    func testLegacyWidgetDataAndManualCopyContainNoCredentials() throws {
        var record = WidgetServer(panel: "https://example.org", serverID: "abc", name: "Simplex", state: "running", online: 2)
        record.playerNames = ["RJ", "MissCaitlyn"]; record.uptime = 45
        let data = try JSONEncoder().encode(record)
        let copy = try XCTUnwrap(WidgetStorage.copied(data.base64EncodedString()))
        XCTAssertEqual(copy.playerNames, ["RJ", "MissCaitlyn"]); XCTAssertFalse(copy.backgroundRefresh)
        XCTAssertFalse(String(decoding: data, as: UTF8.self).contains("secret"))
        XCTAssertNil(WidgetStorage.copied("broken"))
        XCTAssertEqual(WidgetStorage.manifest(try JSONEncoder().encode([record]))?.servers.first?.id, record.id)
        let credential = WidgetCredential(provider: "amp", secret: "synthetic-only")
        XCTAssertEqual(try JSONDecoder().decode(WidgetCredential.self, from: JSONEncoder().encode(credential)).provider, "amp")
    }
    func testThemeContrastProtectsTextForExtremeColors() {
        for hex in ["FFFFFF", "000000", "FFFF00", "FF00FF", "00FF00", "008F83"] {
            let color = ThemeRGB(hex)
            XCTAssertGreaterThanOrEqual(color.readable(dark: true).contrast(ThemeRGB("081020")), 4.5)
            XCTAssertGreaterThanOrEqual(color.readable(dark: false).contrast(ThemeRGB("F0F8F8")), 4.5)
        }
    }
    @MainActor func testWidgetRefreshReadsResourcesAndPlayerNamesWithoutCommands() async throws {
        let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [StubProtocol.self]
        var routes: [String] = []
        StubProtocol.handler = { request in
            XCTAssertEqual(request.httpMethod, "GET")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer synthetic-widget-token")
            routes.append(request.url!.path)
            if request.url!.path.hasSuffix("/resources") { return (200, Data(#"{"attributes":{"current_state":"running","resources":{"memory_bytes":1073741824,"cpu_absolute":12.5,"uptime":45000}}}"#.utf8)) }
            if request.url!.path.hasSuffix("/players") { return (200, Data(#"{"online":2,"players":[{"name":"RJ"},{"name":"MissCaitlyn"}]}"#.utf8)) }
            XCTFail("Unexpected widget route"); return (404, Data())
        }
        defer { StubProtocol.handler = nil }
        let source = WidgetServer(panel: "https://panel.example", serverID: "abc", name: "Simplex", backgroundRefresh: true)
        let result = await WidgetNetwork.refresh(source, session: URLSession(configuration: config), credential: WidgetCredential(provider: "pterodactyl", secret: "synthetic-widget-token"))
        XCTAssertEqual(result.memory, 1_073_741_824); XCTAssertEqual(result.uptime, 45)
        XCTAssertEqual(result.playerNames, ["RJ", "MissCaitlyn"]); XCTAssertNil(result.issue)
        XCTAssertEqual(routes.count, 2)
        XCTAssertFalse(routes.contains { $0.contains("command") || $0.contains("power") })
    }

}
