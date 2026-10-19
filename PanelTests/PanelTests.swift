import XCTest
@testable import Panel

final class StubProtocol: URLProtocol {
    static var handler: ((URLRequest) throws -> (Int, Data))?
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        do {
            let (status, data) = try Self.handler!(request)
            let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: ["Content-Type": "application/json"])!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data); client?.urlProtocolDidFinishLoading(self)
        } catch { client?.urlProtocol(self, didFailWithError: error) }
    }
    override func stopLoading() {}
}
final class PanelTests: XCTestCase {
    func testNormalizesPanelURLAndPreservesSubpath() throws {
        XCTAssertEqual(try PanelAPI.normalize(" panel.example.com/control/api/client/ ").absoluteString, "https://panel.example.com/control")
        XCTAssertEqual(try PanelAPI.normalize("https://panel.example.com/").absoluteString, "https://panel.example.com")
        for bad in ["http://example.com", "https://key@example.com", "https://example.com?q=key", "https://example.com#x", "", "https://"] { XCTAssertThrowsError(try PanelAPI.normalize(bad), bad) }
    }
    @MainActor func client() throws -> PanelAPI {
        let c = URLSessionConfiguration.ephemeral; c.protocolClasses = [StubProtocol.self]
        return PanelAPI(base: try PanelAPI.normalize("https://panel.example.com/control"), key: "test-client-key", session: URLSession(configuration: c))
    }
    @MainActor func testFileQueryEncoding() throws {
        let url = try client().url("servers/abc/files/contents", query: ["file": "/plugins/hello #&ü.yml"])
        let components = URLComponents(url: url, resolvingAgainstBaseURL: false)!
        XCTAssertEqual(components.path, "/control/api/client/servers/abc/files/contents")
        XCTAssertEqual(components.queryItems?.first?.value, "/plugins/hello #&ü.yml")
        XCTAssertThrowsError(try client().url("../account"))
        XCTAssertThrowsError(try client().url("https://evil.example"))
    }
    @MainActor func testHeadersAndPlainTextWriteAndNoContent() async throws {
        StubProtocol.handler = { req in
            XCTAssertEqual(req.httpMethod, "POST")
            XCTAssertEqual(req.value(forHTTPHeaderField: "Authorization"), "Bearer test-client-key")
            XCTAssertEqual(req.value(forHTTPHeaderField: "Content-Type"), "text/plain; charset=utf-8")
            XCTAssertEqual(req.value(forHTTPHeaderField: "Accept"), "Application/vnd.pterodactyl.v1+json")
            return (204, Data())
        }
        let api = try client()
        let response = try await api.raw("servers/a/files/write", method: "POST", query: ["file": "/server.properties"], text: "motd=hello\n")
        XCTAssertTrue(response.isEmpty)
    }
    @MainActor func testAllServerPagesAreLoaded() async throws {
        StubProtocol.handler = { req in
            let page = URLComponents(url: req.url!, resolvingAgainstBaseURL: false)!.queryItems!.first { $0.name == "page" }!.value!
            return (200, Data("{\"data\":[{\"attributes\":{\"identifier\":\"server-\(page)\"}}],\"meta\":{\"pagination\":{\"total_pages\":2}}}".utf8))
        }
        let api = try client(); let rows = try await api.list("")
        XCTAssertEqual(rows.map { $0["identifier"].string }, ["server-1", "server-2"])
    }
    @MainActor func testValidationErrorsAndHTMLLoginAreNotSuccessfulConnections() async throws {
        StubProtocol.handler = { _ in (403, Data("{\"errors\":[{\"detail\":\"Permission denied\"}]}".utf8)) }
        let api = try client()
        do { _ = try await api.request("account"); XCTFail("Must reject 403") } catch { XCTAssertTrue(error.localizedDescription.contains("Permission denied")) }
        StubProtocol.handler = { _ in (200, Data("<html>Login</html>".utf8)) }
        do { _ = try await api.request(""); XCTFail("Must reject HTML") } catch { XCTAssertTrue(error.localizedDescription.contains("keine API-Daten")) }
    }
    func testRedirectsProtectPanelCredentialsAndAllowUnsignedStorage() throws {
        let session = URLSession(configuration: .ephemeral)
        defer { session.invalidateAndCancel() }
        let origin = URL(string: "https://panel.example.com/api/client")!
        let other = URL(string: "https://storage.example.com/file")!
        let response = HTTPURLResponse(url: origin, statusCode: 302, httpVersion: nil, headerFields: nil)!
        var authenticated = URLRequest(url: origin)
        authenticated.setValue("Bearer secret", forHTTPHeaderField: "Authorization")
        let delegate = SameOriginRedirect()
        delegate.urlSession(session, task: session.dataTask(with: authenticated), willPerformHTTPRedirection: response, newRequest: URLRequest(url: other)) { XCTAssertNil($0) }
        delegate.urlSession(session, task: session.dataTask(with: authenticated), willPerformHTTPRedirection: response, newRequest: URLRequest(url: origin)) { XCTAssertEqual($0?.value(forHTTPHeaderField: "Authorization"), "Bearer secret") }
        delegate.urlSession(session, task: session.dataTask(with: origin), willPerformHTTPRedirection: response, newRequest: URLRequest(url: other)) { XCTAssertEqual($0?.url, other); XCTAssertNil($0?.value(forHTTPHeaderField: "Authorization")) }
    }
    func testJSONPreservesBooleansAndAPIFields() throws {
        let value = try JSON.parse("{\"is_locked\":true,\"bytes\":2048,\"empty\":null,\"permissions\":[\"file.read\"]}")
        XCTAssertTrue(value["is_locked"].bool)
        XCTAssertEqual(value["bytes"].double, 2048)
        XCTAssertEqual(value["empty"], .null)
        XCTAssertEqual(try JSON.parse(value.pretty), value)
        XCTAssertEqual(value["permissions"].array.map(\.string), ["file.read"])
    }
    func testServerAllocationAndLimits() {
        let server = Server(data: Demo.servers[0])
        XCTAssertEqual(server.address, "simplexsmp.eu:25565")
        XCTAssertEqual(server.data["limits"]["memory"].double * 1_048_576, 19_327_352_832)
    }
    func testConsoleEscapeSequencesAndFilePaths() {
        XCTAssertEqual(cleanConsole("\u{1B}[32mHello\u{1B}[0m\r"), "Hello")
        XCTAssertEqual(pathJoin("/", "server.properties"), "/server.properties")
        XCTAssertEqual(pathJoin("/plugins", "config.yml"), "/plugins/config.yml")
    }
    @MainActor func testKeychainRoundTrip() throws {
        let id = UUID(); defer { Vault.delete(id) }
        try Vault.save("first", id: id); XCTAssertEqual(try Vault.read(id), "first")
        try Vault.save("second", id: id); XCTAssertEqual(try Vault.read(id), "second")
        Vault.delete(id); XCTAssertThrowsError(try Vault.read(id))
    }
}
