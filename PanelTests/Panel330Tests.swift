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
    func testReleaseNotesAvailable() { XCTAssertNotNil(WhatsNew.release(for: "3.3.0")) }
}
