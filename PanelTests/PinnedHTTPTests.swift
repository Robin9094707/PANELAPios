import XCTest
@testable import Panel

final class PinnedHTTPTests: XCTestCase {
    let pin = "d6d2d9e653bd2ae6ab28400fd3515f71290049b77d32cf887e909720c119df98"
    func testPartialLengthAndChunkedResponsesAreDecodedWithoutTruncation() throws {
        let header = "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\n"
        XCTAssertNil(try PinnedHTTPS.decode(Data((header + "hel").utf8), complete: false))
        XCTAssertEqual(try PinnedHTTPS.decode(Data((header + "hello").utf8), complete: false)?.data, Data("hello".utf8))
        XCTAssertThrowsError(try PinnedHTTPS.decode(Data((header + "hell").utf8), complete: true))
        let chunked = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\nhe\r\n3;extension=test\r\nllo\r\n0\r\n\r\n"
        XCTAssertEqual(try PinnedHTTPS.decode(Data(chunked.utf8), complete: false)?.data, Data("hello".utf8))
        XCTAssertThrowsError(try PinnedHTTPS.decode(Data("HTTP/1.1 200 OK\r\nContent-Length: 1\r\nContent-Length: 2\r\n\r\nx".utf8), complete: true))
        XCTAssertThrowsError(try PinnedHTTPS.decode(Data("HTTP/1.1 200 OK\r\nContent-Length: 999999999999999999\r\n\r\n".utf8), complete: false))
        XCTAssertThrowsError(try PinnedHTTPS.decode(Data("HTTP/1.1 200 OK\r\nContent-Length: 1\r\nTransfer-Encoding: chunked\r\n\r\nx".utf8), complete: true))
    }
    @MainActor func client(pin: String) throws -> SuiteAPI {
        try SuiteAPI(SuiteConnection(panel: "https://panel.example", server: "fixture", endpoint: "https://127.0.0.1:49383", fingerprint: pin, serverIdentity: "synthetic-tls-server"), token: "synthetic-tls-token")
    }
    @MainActor func testRealSelfSignedTLSUsesExactPinWithoutATSException() async throws {
        let api = try client(pin: pin)
        let health = try await api.health()
        XCTAssertEqual(health["serverId"].string, "synthetic-tls-server")
        XCTAssertEqual(api.scope, "read")
    }
    @MainActor func testPinnedBinaryJARLargerThanJSONResponseLimitIsNotTruncated() async throws {
        let data = Data(repeating: 0x5a, count: 9 * 1024 * 1024)
        let response = try await client(pin: pin).request("plugin-files/echo", method: "POST", binary: data)
        XCTAssertEqual(response["size"].int, data.count)
        XCTAssertEqual(response["sha256"].string, PluginArchive.digest(data))
    }
    @MainActor func testWrongCertificateAndPinnedRedirectAreRejected() async throws {
        do { _ = try await client(pin: String(repeating: "0", count: 64)).health(); XCTFail("Wrong pin accepted") } catch { XCTAssertTrue(error.localizedDescription.contains("Fingerabdruck") || error.localizedDescription.contains("fingerabdruck")) }
        do { _ = try await client(pin: pin).request("redirect"); XCTFail("Redirect accepted") } catch { XCTAssertTrue(error.localizedDescription.contains("302")) }
    }
}
