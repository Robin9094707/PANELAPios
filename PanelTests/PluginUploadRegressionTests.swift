import XCTest
import UIKit
@testable import Panel

/// Exercises the shipped installer through its real authenticated PanelAPI binding.
final class PluginUploadRegressionTests: XCTestCase {
    static let old = Data(base64Encoded: "UEsDBBQAAAAAAD1WW11QXTsOagAAAGoAAAAKAAAAcGx1Z2luLnltbG5hbWU6IFNpbXBsZXhBZG1pblN1aXRlCnZlcnNpb246IDguNC4wCm1haW46IGV1LnNpbXBsZXhzbXAuYWRtaW5zdWl0ZS5TaW1wbGV4QWRtaW5TdWl0ZQphcGktdmVyc2lvbjogMjYuMgpQSwECFAMUAAAAAAA9VltdUF07DmoAAABqAAAACgAAAAAAAAAAAAAAgAEAAAAAcGx1Z2luLnltbFBLBQYAAAAAAQABADgAAACSAAAAAAA=")!
    static let next = Data(base64Encoded: "UEsDBBQAAAAAAD1WW124gwLUagAAAGoAAAAKAAAAcGx1Z2luLnltbG5hbWU6IFNpbXBsZXhBZG1pblN1aXRlCnZlcnNpb246IDguNS4wCm1haW46IGV1LnNpbXBsZXhzbXAuYWRtaW5zdWl0ZS5TaW1wbGV4QWRtaW5TdWl0ZQphcGktdmVyc2lvbjogMjYuMgpQSwECFAMUAAAAAAA9VltduIMC1GoAAABqAAAACgAAAAAAAAAAAAAAgAEAAAAAcGx1Z2luLnltbFBLBQYAAAAAAQABADgAAACSAAAAAAA=")!
    static let newer = Data(base64Encoded: "UEsDBBQAAAAAAD1WW13B5jlhagAAAGoAAAAKAAAAcGx1Z2luLnltbG5hbWU6IFNpbXBsZXhBZG1pblN1aXRlCnZlcnNpb246IDguNi4wCm1haW46IGV1LnNpbXBsZXhzbXAuYWRtaW5zdWl0ZS5TaW1wbGV4QWRtaW5TdWl0ZQphcGktdmVyc2lvbjogMjYuMgpQSwECFAMUAAAAAAA9VltdweY5YWoAAABqAAAACgAAAAAAAAAAAAAAgAEAAAAAcGx1Z2luLnltbFBLBQYAAAAAAQABADgAAACSAAAAAAA=")!
    final class Host {
        var files = ["/plugins/SimplexAdminSuite-8.4.0.jar": PluginUploadRegressionTests.old,
                     "/bukkit.yml": Data("settings:\n  update-folder: update\n".utf8),
                     "/plugins/SimplexAdminSuite/config.yml": Data("existing configuration".utf8)]
        var directories: Set<String> = ["/", "/plugins", "/plugins/update"]
        var requests: [String] = []
        var writeStatus = 204, renameStatus = 204, folderStatus = 204
        var partialWrite = false, applyRename = true, changeInstalled = false
        var writes = 0, renames = 0
        func body(_ request: URLRequest) throws -> Data {
            if let data = request.httpBody { return data }
            guard let stream = request.httpBodyStream else { return Data() }
            stream.open(); defer { stream.close() }
            var output = Data(), buffer = [UInt8](repeating: 0, count: 4096)
            while stream.hasBytesAvailable {
                let n = stream.read(&buffer, maxLength: buffer.count)
                if n < 0 { throw stream.streamError ?? PanelError(message: "body stream") }
                if n == 0 { break }; output.append(contentsOf: buffer.prefix(n))
            }
            return output
        }
        func respond(_ request: URLRequest) throws -> (Int, Data) {
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer fixture-token")
            let route = request.url!.lastPathComponent
            requests.append(route)
            let query = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)!.queryItems ?? []
            func parameter(_ name: String) -> String { query.first { $0.name == name }?.value ?? "" }
            switch route {
            case "contents":
                guard let data = files[parameter("file")] else { return (404, Data()) }; return (200, data)
            case "download", "upload": return (500, Data()) // Broken host signers must be unnecessary here.
            case "list":
                let dir = parameter("directory")
                guard directories.contains(dir) else { return (404, Data()) }
                func parent(_ path: String) -> String { (path as NSString).deletingLastPathComponent }
                var rows: [JSON] = []
                for path in directories.sorted() where path != dir && parent(path) == dir {
                    rows.append(try JSON.from(["attributes": ["name": (path as NSString).lastPathComponent, "is_file": false]]))
                }
                for path in files.keys.sorted() where parent(path) == dir {
                    rows.append(try JSON.from(["attributes": ["name": (path as NSString).lastPathComponent, "is_file": true, "size": files[path]!.count]]))
                }
                return (200, try JSONEncoder().encode(JSON.object(["data": .array(rows)])))
            case "create-folder":
                let json = try JSONDecoder().decode(JSON.self, from: body(request))
                XCTAssertNotEqual(json["root"].string, "/") // No new root staging folders.
                directories.insert(pathJoin(json["root"].string, json["name"].string))
                return (folderStatus, Data())
            case "write":
                writes += 1
                XCTAssertEqual(request.value(forHTTPHeaderField: "Content-Type"), "application/octet-stream")
                let path = parameter("file")
                XCTAssertTrue(path.hasSuffix(".pending")); XCTAssertTrue(path.hasPrefix("/plugins/"))
                let data = try body(request)
                XCTAssertEqual(data, PluginUploadRegressionTests.next)
                files[path] = partialWrite ? Data(data.prefix(24)) : data
                if changeInstalled { files["/plugins/SimplexAdminSuite-8.4.0.jar"] = PluginUploadRegressionTests.newer }
                return (writeStatus, Data())
            case "rename":
                renames += 1
                let json = try JSONDecoder().decode(JSON.self, from: body(request))
                XCTAssertEqual(request.httpMethod, "PUT")
                let root = json["root"].string, from = json["files"].array[0]["from"].string, to = json["files"].array[0]["to"].string
                XCTAssertTrue(directories.contains(root)); XCTAssertTrue(from.hasSuffix(".pending"))
                XCTAssertFalse(from.contains("/")); XCTAssertFalse(to.contains("/")); XCTAssertTrue(to.hasSuffix(".jar"))
                if applyRename { files[pathJoin(root, to)] = files.removeValue(forKey: pathJoin(root, from)) }
                return (renameStatus, Data())
            default: XCTFail("Unexpected endpoint: \(request.url!)"); return (500, Data())
            }
        }
    }
    @MainActor func withHost(_ host: Host, _ action: (PluginUpdateCandidate, PluginUpdateFiles) async throws -> Void) async throws {
        let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [StubProtocol.self]
        let api = PanelAPI(base: URL(string: "https://kineticpanel.net")!, key: "fixture-token", session: URLSession(configuration: config))
        StubProtocol.handler = { try host.respond($0) }; defer { StubProtocol.handler = nil }
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let file = folder.appendingPathComponent("SimplexAdminSuite-8.5.0.jar")
        try Self.next.write(to: file)
        let old = try PluginArchive.descriptor(Self.old), next = try PluginArchive.descriptor(Self.next)
        let candidate = PluginUpdateCandidate(filename: "SimplexAdminSuite-8.4.0.jar", file: file, installed: old, next: next, originalHash: PluginArchive.digest(Self.old), report: try old.validateUpdate(next))
        try await action(candidate, PluginUpdateFiles(api: api, server: "abc"))
    }
    @MainActor func testQueue840To850WithoutSignersOrCrossDirectoryMoveAndRetryIsIdempotent() async throws {
        let host = Host(), original = Host().files
        try await withHost(host) { value, files in
            let directory = try await PluginUpdateInstaller.stage(value, files: files)
            XCTAssertEqual(directory, "/plugins/update")
            XCTAssertEqual(host.files[directory + "/SimplexAdminSuite-8.5.0.jar"], Self.next)
            _ = try await PluginUpdateInstaller.stage(value, files: files)
        }
        XCTAssertEqual(host.writes, 1); XCTAssertEqual(host.renames, 1)
        XCTAssertFalse(host.requests.contains("upload")); XCTAssertFalse(host.requests.contains("download"))
        for (path, data) in original { XCTAssertEqual(host.files[path], data) }
    }
    @MainActor func test500AfterSuccessfulWriteAndRenameIsConfirmedByActualBytes() async throws {
        let host = Host(); host.writeStatus = 500; host.renameStatus = 500
        try await withHost(host) { value, files in _ = try await PluginUpdateInstaller.stage(value, files: files) }
        XCTAssertEqual(host.writes, 1); XCTAssertEqual(host.renames, 1)
        XCTAssertFalse(host.requests.contains("upload"))
        XCTAssertEqual(host.files["/plugins/update/SimplexAdminSuite-8.5.0.jar"], Self.next)
    }
    @MainActor func testPartialUploadNeverBecomesJarAndDoesNotTouchInstalledVersion() async throws {
        let host = Host(); host.partialWrite = true; host.writeStatus = 500
        try await withHost(host) { value, files in
            do { _ = try await PluginUpdateInstaller.stage(value, files: files); XCTFail("Partial upload accepted") }
            catch { XCTAssertTrue(error.localizedDescription.contains("Prüfsumme")) }
        }
        XCTAssertEqual(host.renames, 0); XCTAssertEqual(host.writes, 1)
        XCTAssertNil(host.files["/plugins/update/SimplexAdminSuite-8.5.0.jar"])
        XCTAssertEqual(host.files["/plugins/SimplexAdminSuite-8.4.0.jar"], Self.old)
    }
    @MainActor func testFailedRenameReportsStepAndCanRetryWithoutSelectingFileAgain() async throws {
        let host = Host(); host.renameStatus = 500; host.applyRename = false
        try await withHost(host) { value, files in
            do { _ = try await PluginUpdateInstaller.stage(value, files: files); XCTFail("Missing target accepted") }
            catch { XCTAssertTrue(error.localizedDescription.contains("Update vormerken in /plugins/update")); XCTAssertTrue(error.localizedDescription.contains(".pending")) }
            host.applyRename = true; host.renameStatus = 204
            _ = try await PluginUpdateInstaller.stage(value, files: files)
        }
        XCTAssertEqual(host.files["/plugins/update/SimplexAdminSuite-8.5.0.jar"], Self.next)
        XCTAssertEqual(host.files["/plugins/SimplexAdminSuite-8.4.0.jar"], Self.old)
    }
    @MainActor func testCustomNestedUpdateFolderAnd500AfterFolderCreation() async throws {
        let host = Host(); host.folderStatus = 500
        host.files["/bukkit.yml"] = Data("settings:\n  update-folder: incoming/updates\n".utf8)
        try await withHost(host) { value, files in
            let directory = try await PluginUpdateInstaller.stage(value, files: files)
            XCTAssertEqual(directory, "/plugins/incoming/updates")
        }
        XCTAssertEqual(host.files["/plugins/incoming/updates/SimplexAdminSuite-8.5.0.jar"], Self.next)
    }
    @MainActor func testConflictingPendingVersionAndConcurrentInstalledChangesBlockActivation() async throws {
        let host = Host(); host.files["/plugins/update/SimplexAdminSuite-8.6.0.jar"] = Self.newer
        try await withHost(host) { value, files in
            do { _ = try await PluginUpdateInstaller.stage(value, files: files); XCTFail("Conflict accepted") }
            catch { XCTAssertTrue(error.localizedDescription.contains("bereits Version 8.6.0")) }
        }
        XCTAssertEqual(host.writes, 0); XCTAssertEqual(host.renames, 0)
        let changed = Host(); changed.changeInstalled = true
        try await withHost(changed) { value, files in
            do { _ = try await PluginUpdateInstaller.stage(value, files: files); XCTFail("Concurrent change accepted") }
            catch { XCTAssertTrue(error.localizedDescription.contains("zwischenzeitlich geändert")) }
        }
        XCTAssertEqual(changed.renames, 0)
    }
    @MainActor func testSelectedJarValidatesWithoutDismissCallbackAndStagesSameBytes() async throws {
        let host = Host()
        try await withHost(host) { input, files in
            let model = PluginUploadFlowModel()
            await model.inspect(input.file, filename: input.filename, files: files)
            XCTAssertNil(model.error); XCTAssertFalse(model.busy)
            XCTAssertEqual(model.status, "Dateiprüfung erfolgreich")
            let candidate = try XCTUnwrap(model.candidate)
            XCTAssertEqual(candidate.next.version, "8.5.0")
            XCTAssertEqual(try Data(contentsOf: candidate.file), Self.next)
            _ = try await PluginUpdateInstaller.stage(candidate, files: files)
            model.cleanup()
            XCTAssertFalse(FileManager.default.fileExists(atPath: candidate.file.path))
        }
        XCTAssertEqual(host.files["/plugins/update/SimplexAdminSuite-8.5.0.jar"], Self.next)
        XCTAssertEqual(host.files["/plugins/SimplexAdminSuite-8.4.0.jar"], Self.old)
        XCTAssertEqual(host.files["/plugins/SimplexAdminSuite/config.yml"], Data("existing configuration".utf8))
    }
    @MainActor func testUnreadableSelectionShowsFailureAndRetryCanValidate() async throws {
        try await withHost(Host()) { input, files in
            let model = PluginUploadFlowModel()
            await model.inspect(URL(fileURLWithPath: "/missing/upload.jar"), filename: input.filename, files: files)
            XCTAssertNotNil(model.error); XCTAssertNil(model.candidate); XCTAssertFalse(model.busy)
            await model.inspect(input.file, filename: input.filename, files: files)
            XCTAssertNil(model.error); XCTAssertNotNil(model.candidate); model.cleanup()
        }
    }
    @MainActor func testFirstSelectionCarriesItsPluginAcrossCachedDismissClosureAndCancellation() throws {
        let handoff = FileImportHandoff(), cached = handoff
        let first = URL(fileURLWithPath: "/tmp/first.jar"), second = URL(fileURLWithPath: "/tmp/second.jar")
        handoff.begin(target: "SimplexAdminSuite-8.4.0.jar"); handoff.receive(.success([first]))
        XCTAssertEqual(cached.takeSelection(), FileImportHandoff.Selection(file: first, target: "SimplexAdminSuite-8.4.0.jar"))
        XCTAssertNil(cached.takeSelection())
        handoff.begin(target: "Other.jar"); handoff.receive(.success([second]))
        XCTAssertEqual(cached.takeSelection(), FileImportHandoff.Selection(file: second, target: "Other.jar"))
        handoff.begin(target: "Canceled.jar"); handoff.receive(.success([])); XCTAssertNil(cached.takeSelection())
        handoff.receive(.failure(PanelError(message: "provider error"))); handoff.begin()
        XCTAssertNil(cached.takeError()); XCTAssertNil(cached.takeSelection())
    }
    @MainActor func testDocumentPickerCopiesFirstFileAndDoesNotDeliverAgainOnCancel() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let source = folder.appendingPathComponent("update.jar"); try Self.next.write(to: source)
        var results: [Result<[URL], Error>] = []
        let picker = FileImportPicker(retainSelection: true) { results.append($0) }
        let coordinator = picker.makeCoordinator()
        let controller = UIDocumentPickerViewController(forOpeningContentTypes: [.item], asCopy: true)
        coordinator.documentPicker(controller, didPickDocumentsAt: [source])
        coordinator.documentPickerWasCancelled(controller)
        XCTAssertEqual(results.count, 1)
        let copied = try XCTUnwrap(try results.first?.get().first)
        defer { try? FileManager.default.removeItem(at: copied.deletingLastPathComponent()) }
        XCTAssertNotEqual(copied, source); XCTAssertEqual(try Data(contentsOf: copied), Self.next)
    }
}
