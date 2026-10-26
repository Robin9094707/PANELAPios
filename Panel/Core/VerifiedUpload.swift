import Foundation

/// Only use for a newly-created, private staging directory. Never overwrite live JARs.
@MainActor enum VerifiedUpload {
    static func send(_ file: URL, api: PanelAPI, server: String, directory: String) async throws {
        let path = pathJoin(directory, file.lastPathComponent)
        let local = try Data(contentsOf: file, options: .mappedIfSafe)
        let prefix = "servers/\(server)/files"
        let existing = try await api.list(prefix + "/list", query: ["directory": directory])
        guard !existing.contains(where: { $0["name"].string == file.lastPathComponent }) else { throw PanelError(message: "Staging-Datei existiert bereits. Bitte neu prüfen.") }
        try await transfer(local: local, directory: directory, upload: {
            try await api.upload(server: server, directory: directory, file: file)
        }, exists: {
            try await api.list(prefix + "/list", query: ["directory": directory]).contains { $0["name"].string == file.lastPathComponent }
        }, write: { data in
            _ = try await api.raw(prefix + "/write", method: "POST", query: ["file": path], binary: data)
        }, read: {
            try await api.fileData(server: server, path: path, limit: 128 * 1024 * 1024)
        })
    }
    static func transfer(local: Data, directory: String, upload: () async throws -> Void,
                         exists: () async throws -> Bool, write: (Data) async throws -> Void,
                         read: () async throws -> Data) async throws {
        let hash = PluginArchive.digest(local)
        var transportError: Error?
        do { try await upload() }
        catch { if RequestFailure.isCancellation(error) { throw CancellationError() }; transportError = error }
        if transportError != nil {
            // An HTTP failure may arrive after the daemon stored the bytes. Reconcile first.
            if !(try await exists()) {
                // The documented raw-body write route avoids a broken upload signer/proxy.
                do { try await write(local) }
                catch {
                    if RequestFailure.isCancellation(error) { throw CancellationError() }
                    // Do not resend an ambiguous write; the readback below decides success.
                    transportError = PanelError(message: "JAR-Upload: \(transportError!.localizedDescription)\nDirektübertragung: \(error.localizedDescription)")
                }
            }
        }
        do {
            let remote = try await read()
            guard PluginArchive.digest(remote) == hash else { throw PanelError(message: "Upload-Prüfsumme stimmt nicht. Die JAR bleibt außerhalb des Ladeordners; installierte Dateien wurden nicht ersetzt.") }
        } catch {
            if RequestFailure.isCancellation(error) { throw CancellationError() }
            throw PanelError(message: "Upload nicht bestätigt: \(error.localizedDescription)\(transportError.map { "\n" + $0.localizedDescription } ?? "")\nStaging: \(directory)")
        }
    }
}
