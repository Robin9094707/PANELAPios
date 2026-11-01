import XCTest
@testable import Panel

final class Panel330Tests: XCTestCase {
    @MainActor func testLegacyConnectionsRemainDecodableAndRightsDefaultToLegacy() throws {
        let record = SuiteConnection(panel: "https://panel.invalid", server: "old", endpoint: "https://suite.invalid:39093", fingerprint: "", serverIdentity: "existing")
        let decoded = try JSONDecoder().decode(SuiteConnection.self, from: JSONEncoder().encode(record))
        XCTAssertEqual(decoded, record)
        let api = try SuiteAPI(record, token: "sas_existing", demo: true)
        XCTAssertTrue(api.can("ticket.close"))
        XCTAssertEqual(api.connection.serverIdentity, "existing")
    }
    func testMemoryThresholdRejectsNonFiniteAndUsesDecimalGB() {
        XCTAssertEqual(SuiteMemoryThreshold.bytes("18,1"), 18_100_000_000)
        XCTAssertNil(SuiteMemoryThreshold.bytes("nan")); XCTAssertNil(SuiteMemoryThreshold.bytes("1e100"))
    }
    @MainActor func testSessionRightsComeFromHealth() async throws {
        let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [StubProtocol.self]
        StubProtocol.handler = { _ in (200, Data(#"{"apiVersion":1,"serverId":"existing","scope":"full","limited":true,"permissions":["ticket.close"],"features":["accounts-v1"]}"#.utf8)) }
        defer { StubProtocol.handler = nil }
        let record = SuiteConnection(panel: "suite-direct:player", server: "direct", endpoint: "https://suite.invalid", fingerprint: "", serverIdentity: "existing")
        let api = try SuiteAPI(record, token: "sasu_test", session: URLSession(configuration: config))
        _ = try await api.health(); XCTAssertTrue(api.can("ticket.close")); XCTAssertFalse(api.can("ticket.delete")); XCTAssertFalse(api.can("action.accounts.update"))
    }
    func testReleaseNotesAvailable() { XCTAssertNotNil(WhatsNew.release(for: "3.3.0")) }
}
