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
    @MainActor func testWrongCertificateAndPinnedRedirectAreRejected() async throws {
        do { _ = try await client(pin: String(repeating: "0", count: 64)).health(); XCTFail("Wrong pin accepted") } catch { XCTAssertTrue(error.localizedDescription.contains("Fingerabdruck") || error.localizedDescription.contains("fingerabdruck")) }
        do { _ = try await client(pin: pin).request("redirect"); XCTFail("Redirect accepted") } catch { XCTAssertTrue(error.localizedDescription.contains("302")) }
    }
    @MainActor func testPinnedTransferHonoursShortTimeoutAndCancelsPromptly() async throws {
        let api = try client(pin: pin), started = Date()
        do { _ = try await api.request("slow", timeout: 0.25); XCTFail("Pinned request ignored its deadline") }
        catch { XCTAssertTrue(error.localizedDescription.contains("Zeitlimit"), error.localizedDescription) }
        XCTAssertLessThan(Date().timeIntervalSince(started), 1.5)
        let task = Task { try await api.request("slow", timeout: 10) }
        try await Task.sleep(for: .milliseconds(100))
        let cancelled = Date(); task.cancel()
        do { _ = try await task.value; XCTFail("Cancelled pinned request succeeded") }
        catch { XCTAssertTrue(RequestFailure.isCancellation(error)) }
        XCTAssertLessThan(Date().timeIntervalSince(cancelled), 1)
    }
    // MARK: 5.11.0 simpler pairing
    func testProbeFindsSelfSignedCertificateAndSuite() async throws {
        let result = await SuiteProbe.run(URL(string: "https://127.0.0.1:49383")!, timeout: 5)
        XCTAssertTrue(result.reachable)
        XCTAssertEqual(result.fingerprint, pin)
        XCTAssertFalse(result.publiclyTrusted)
        XCTAssertEqual(result.status, 200)
        XCTAssertTrue(result.suite)
    }
    func testPairingKeepsMatchingPinAndOffersAnUnknownCertificate() async throws {
        let url = URL(string: "https://127.0.0.1:49383")!
        let kept = try await SuitePairing.pin(for: url, entered: pin.uppercased())
        XCTAssertEqual(kept, pin)
        do { _ = try await SuitePairing.pin(for: url, entered: String(repeating: "0", count: 64)); XCTFail("Changed certificate accepted silently") }
        catch let offer as SuiteCertificateOffer { XCTAssertEqual(offer.fingerprint, pin); XCTAssertEqual(offer.previous, String(repeating: "0", count: 64)) }
        do { _ = try await SuitePairing.pin(for: url, entered: ""); XCTFail("Unknown certificate accepted silently") }
        catch let offer as SuiteCertificateOffer { XCTAssertEqual(offer.fingerprint, pin); XCTAssertTrue(offer.previous.isEmpty) }
    }
    func testPairingExplainsClosedPortAndProxyErrors() async throws {
        do { _ = try await SuitePairing.pin(for: URL(string: "https://127.0.0.1:1")!, entered: "", timeout: 3); XCTFail("Closed port accepted") }
        catch { XCTAssertTrue(error.localizedDescription.contains("zugewiesen"), error.localizedDescription) }
        do { _ = try await SuitePairing.pin(for: URL(string: "https://127.0.0.1:49383/gateway")!, entered: pin); XCTFail("Proxy error ignored") }
        catch { XCTAssertTrue(error.localizedDescription.contains("502"), error.localizedDescription) }
    }
    func testPairingDecisionRules() throws {
        let other = String(repeating: "a", count: 64)
        XCTAssertEqual(try SuitePairing.decide(SuiteProbeResult(reachable: true, fingerprint: pin, publiclyTrusted: false), entered: pin), pin)
        XCTAssertEqual(try SuitePairing.decide(SuiteProbeResult(reachable: true, fingerprint: other, publiclyTrusted: true), entered: pin), "", "A public certificate needs no pin")
        XCTAssertThrowsError(try SuitePairing.decide(SuiteProbeResult(reachable: true, fingerprint: other, publiclyTrusted: false), entered: pin)) { XCTAssertTrue($0 is SuiteCertificateOffer) }
        XCTAssertEqual(SuiteProbe.status(Data("HTTP/1.1 502 Bad Gateway\r\n".utf8)), 502)
        XCTAssertNil(SuiteProbe.status(Data("HTTP/1.1 50".utf8)))
        XCTAssertEqual(SuiteCertificateOffer(fingerprint: pin, previous: "").grouped.split(separator: " ").count, 8)
    }
}
