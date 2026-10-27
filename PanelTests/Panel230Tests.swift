import XCTest
@testable import Panel

final class Panel230Tests: XCTestCase {
    @MainActor func testUploadSignerReceivesDirectoryBeforeDaemonRequest() async throws {
        let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [StubProtocol.self]
        let api = PanelAPI(base: URL(string: "https://panel.example")!, key: "fixture", session: URLSession(configuration: config))
        var count = 0
        StubProtocol.handler = { request in
            count += 1
            XCTAssertEqual(request.url?.path, "/api/client/servers/a/files/upload")
            XCTAssertEqual(URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "directory" }?.value, "/plugins/incoming ü")
            return (500, Data())
        }
        defer { StubProtocol.handler = nil }
        do { try await api.upload(server: "a", directory: "/plugins/incoming ü", file: URL(fileURLWithPath: "/unused.jar")); XCTFail("Must report signer failure") }
        catch { XCTAssertTrue(error.localizedDescription.contains("Upload-Freigabe")) }
        XCTAssertEqual(count, 1)
    }
    func testSignedUploadTargetPreservesTokenAndHasOneDirectory() throws {
        let target = try PanelAPI.uploadTarget("https://node.example/upload?token=abc%2B123&directory=%2Fold", directory: "/plugins/update ü+")
        let query = URLComponents(url: target, resolvingAgainstBaseURL: false)!.queryItems!
        XCTAssertEqual(query.filter { $0.name == "directory" }.map(\.value), ["/plugins/update ü+"])
        XCTAssertEqual(query.first { $0.name == "token" }?.value, "abc+123")
        XCTAssertTrue(target.absoluteString.contains("token=abc%2B123"))
        XCTAssertTrue(target.absoluteString.contains("%2B"))
        XCTAssertThrowsError(try PanelAPI.uploadTarget("http://node.example/upload", directory: "/"))
    }
    @MainActor func testFailedSignerUsesVerifiedBinaryFallbackOnce() async throws {
        let bytes = Data([0x50, 0x4b, 0, 0xff, 0x80]), failure = PanelError(message: "HTTP 500")
        var writes = 0
        try await VerifiedUpload.transfer(local: bytes, directory: "/private-stage", upload: { throw failure }, exists: { false }, write: { data in XCTAssertEqual(data, bytes); writes += 1 }, read: { bytes })
        XCTAssertEqual(writes, 1)
    }
    @MainActor func testAmbiguousUploadAndWriteAreReadBackWithoutBlindRetry() async throws {
        let bytes = Data("expected-jar".utf8), failure = PanelError(message: "connection lost")
        var writes = 0
        try await VerifiedUpload.transfer(local: bytes, directory: "/stage", upload: { throw failure }, exists: { true }, write: { _ in writes += 1 }, read: { bytes })
        XCTAssertEqual(writes, 0)
        try await VerifiedUpload.transfer(local: bytes, directory: "/stage", upload: { throw failure }, exists: { false }, write: { _ in writes += 1; throw failure }, read: { bytes })
        XCTAssertEqual(writes, 1)
        do {
            try await VerifiedUpload.transfer(local: bytes, directory: "/stage", upload: { throw failure }, exists: { true }, write: { _ in XCTFail("partial file must never be overwritten") }, read: { Data("partial".utf8) })
            XCTFail("partial upload accepted")
        } catch { XCTAssertTrue(error.localizedDescription.contains("Prüfsumme")) }
    }
    @MainActor func testFirstPickerSelectionIsConsumedOnceAfterDismissal() {
        let handoff = FileImportHandoff(), url = URL(fileURLWithPath: "/tmp/import/a.jar")
        let captured = handoff
        handoff.receive(.success([url])); XCTAssertEqual(captured.take(), url); XCTAssertNil(captured.take())
        handoff.receive(.success([])); XCTAssertNil(captured.take())
    }
    func testWorldStorageCountsFilesWithoutInventingChunks() throws {
        var value = WorldStorage()
        value.include(try [JSON.from(["is_file": true, "name": "r.0.0.mca", "size": 4096]), JSON.from(["is_file": true, "name": "level.dat", "size": 100]), JSON.from(["is_file": false, "name": "region", "size": 99999])], path: "/world")
        value.include(try [JSON.from(["is_file": true, "name": "uuid.dat", "size": 50])], path: "/world/playerdata")
        XCTAssertEqual(value.bytes, 4246); XCTAssertEqual(value.regions, 1); XCTAssertEqual(value.playerFiles, 1); XCTAssertEqual(value.files, 3)
        XCTAssertFalse(WorldStorage.safeName("..")); XCTAssertFalse(WorldStorage.safeName("../outside"))
    }
    @MainActor func testNewCardsKeepOldLayoutAndCredentialRecords() {
        let name = "panel230-" + UUID().uuidString
        let defaults = UserDefaults(suiteName: name)!
        defer { defaults.removePersistentDomain(forName: name) }
        let layout = DashboardLayouts(defaults: defaults)
        layout.set([.identity, .health], key: "old-server", scope: .server)
        XCTAssertEqual(DashboardLayouts(defaults: defaults).cards("old-server", scope: .server), [.identity, .health])
        XCTAssertTrue(DashboardCard.available(.server).contains(.minecraftTools)); XCTAssertTrue(DashboardCard.available(.home).contains(.transfers))
    }
}
