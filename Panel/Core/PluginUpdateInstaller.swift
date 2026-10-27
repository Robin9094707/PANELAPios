import Foundation

/// File operations used by the real installer and its HTTP/transaction regression tests.
@MainActor struct PluginUpdateFiles {
    var list: (String) async throws -> [JSON]
    var read: (String) async throws -> Data
    var mkdir: (String, String) async throws -> Void
    var send: (URL, String) async throws -> Void
    var rename: (String, String, String) async throws -> Void

    init(api: PanelAPI, server: String) {
        let prefix = "servers/\(server)/files"
        list = { try await api.list(prefix + "/list", query: ["directory": $0]) }
        read = { path in
            if api.amp != nil { return try await api.fileData(server: server, path: path, limit: 128 * 1024 * 1024) }
            // Read through the panel first: small configs/JARs need no download signer.
            // Larger JARs can exceed the host's editor limit and use the signed download.
            do {
                let data = try await api.raw(prefix + "/contents", query: ["file": path])
                guard data.count <= 128 * 1024 * 1024 else { throw PanelError(message: "Datei größer als 128 MiB.") }
                return data
            } catch {
                if RequestFailure.isCancellation(error) { throw CancellationError() }
                let directError = error
                do { return try await api.fileData(server: server, path: path, limit: 128 * 1024 * 1024) }
                catch {
                    if RequestFailure.isCancellation(error) { throw CancellationError() }
                    throw PanelError(message: "Datei lesen \(path): \(directError.localizedDescription)\nDownload: \(error.localizedDescription)")
                }
            }
        }
        mkdir = { root, name in try await api.mutate(prefix + "/create-folder", ["root": root, "name": name]) }
        rename = { root, from, to in
            // Same-directory rename, using the exact payload used by the panel file manager.
            try await api.mutate(prefix + "/rename", method: "PUT", ["root": root, "files": [["from": from, "to": to]]])
        }
        let listDirectory = list
        send = { file, directory in
            let local = try Data(contentsOf: file, options: .mappedIfSafe)
            let name = file.lastPathComponent, path = pathJoin(directory, file.lastPathComponent)
            // A unique non-JAR name cannot be loaded by Paper, even during a restart.
            guard !(try await listDirectory(directory)).contains(where: { $0["name"].string.caseInsensitiveCompare(name) == .orderedSame }) else {
                throw PanelError(message: "Temporäre Update-Datei existiert bereits.")
            }
            do {
                _ = try await api.raw(prefix + "/write", method: "POST", query: ["file": path], binary: local)
            } catch {
                if RequestFailure.isCancellation(error) { throw CancellationError() }
                let directError = error
                // A failed response can follow a completed write. Do not overwrite it.
                if try await listDirectory(directory).contains(where: { $0["name"].string == name }) { return }
                do { try await api.upload(server: server, directory: directory, file: file) }
                catch {
                    if RequestFailure.isCancellation(error) { throw CancellationError() }
                    if try await listDirectory(directory).contains(where: { $0["name"].string == name }) { return }
                    throw PanelError(message: "Direktübertragung: \(directError.localizedDescription)\nUpload: \(error.localizedDescription)")
                }
            }

        }
    }

    init(list: @escaping (String) async throws -> [JSON], read: @escaping (String) async throws -> Data,
         mkdir: @escaping (String, String) async throws -> Void, send: @escaping (URL, String) async throws -> Void,
         rename: @escaping (String, String, String) async throws -> Void) {
        self.list = list; self.read = read; self.mkdir = mkdir; self.send = send; self.rename = rename
    }
}

@MainActor enum PluginUpdateInstaller {
    static func step<T>(_ title: String, _ action: () async throws -> T) async throws -> T {
        do { return try await action() }
        catch {
            if RequestFailure.isCancellation(error) { throw CancellationError() }
            throw PanelError(message: title + ": " + error.localizedDescription)
        }
    }

    static func stage(_ value: PluginUpdateCandidate, files: PluginUpdateFiles) async throws -> String {
        let local = try Data(contentsOf: value.file, options: .mappedIfSafe)
        guard local.count <= 128 * 1024 * 1024, try PluginArchive.descriptor(local) == value.next else {
            throw PanelError(message: "Die ausgewählte Update-Datei wurde verändert. Bitte neu auswählen.")
        }
        let hash = PluginArchive.digest(local)
        func checkInstalled() async throws {
            let current = try await step("Installierte JAR prüfen") { try await files.read("/plugins/" + value.filename) }
            guard PluginArchive.digest(current) == value.originalHash else {
                throw PanelError(message: "Das installierte Plugin wurde zwischenzeitlich geändert. Bitte neu auswählen.")
            }
        }
        func configuredDirectory() async throws -> String {
            let config = try await step("bukkit.yml lesen") { try await files.read("/bukkit.yml") }
            guard config.count <= 8 * 1024 * 1024, let text = String(data: config, encoding: .utf8), !text.contains("\0") else {
                throw PanelError(message: "bukkit.yml ist keine gültige Textdatei.")
            }
            return try PluginUpdatePlan.directory(text)
        }
        try await checkInstalled()
        let directory = try await configuredDirectory()
        let installed = try await step("Plugin-Ordner lesen") { try await files.list("/plugins") }
        try PluginUpdatePlan.checkFilename(value.updateFilename, installed: value.filename, files: installed.map { $0["name"].string })
        var root = "/plugins"
        for part in directory.dropFirst("/plugins/".count).split(separator: "/").map(String.init) {
            let parent = root
            let rows = try await step("Update-Ordner prüfen \(parent)") { try await files.list(parent) }
            if let row = rows.first(where: { $0["name"].string == part }) {
                guard !row["is_file"].bool else { throw PanelError(message: "Update-Ordner durch Datei belegt: " + pathJoin(parent, part)) }
            } else {
                do { try await files.mkdir(parent, part) }
                catch {
                    if RequestFailure.isCancellation(error) { throw CancellationError() }
                    // A host may report 500 after creating the folder. Reconcile, never blindly retry.
                    let creationError = error
                    let rows = try await step("Update-Ordner nach Anlage prüfen") { try await files.list(parent) }
                    guard rows.contains(where: { $0["name"].string == part && !$0["is_file"].bool }) else {
                        throw PanelError(message: "Update-Ordner anlegen \(pathJoin(parent, part)): \(creationError.localizedDescription)")
                    }
                }
            }
            root = pathJoin(parent, part)
        }
        // Idempotent: a matching completed upload is already a successful queue operation.
        func checkPending() async throws -> Bool {
            let rows = try await step("Vorgemerkte Updates lesen") { try await files.list(directory) }
            var alreadyQueued = false
            for row in rows where row["name"].string.lowercased().hasSuffix(".jar") {
                let name = row["name"].string
                guard row["is_file"].bool else { throw PanelError(message: "JAR-Name durch Ordner belegt: " + name) }
                let data = try await step("Vorgemerktes Update prüfen \(name)") { try await files.read(pathJoin(directory, name)) }
                if name == value.updateFilename && PluginArchive.digest(data) == hash { alreadyQueued = true; continue }
                let descriptor = try PluginArchive.descriptor(data)
                try PluginUpdatePlan.checkPending(descriptor, next: value.next)
                guard name.caseInsensitiveCompare(value.updateFilename) != .orderedSame else {
                    throw PanelError(message: "Eine andere Datei belegt den Update-Dateinamen. Es wird nichts überschrieben.")
                }
            }
            return alreadyQueued
        }
        if try await checkPending() { return directory }
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("plugin-stage-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let temporary = "simplex-update-" + UUID().uuidString + ".pending"
        let upload = folder.appendingPathComponent(temporary)
        try local.write(to: upload, options: .atomic)
        try await step("Update-Datei übertragen nach \(directory)") { try await files.send(upload, directory) }
        let staged = try await step("Übertragene JAR zurücklesen") { try await files.read(pathJoin(directory, temporary)) }
        guard PluginArchive.digest(staged) == hash else {
            throw PanelError(message: "Upload-Prüfsumme stimmt nicht. Die Datei bleibt als .pending unter \(directory); sie ist kein vorgemerktes Plugin-Update.")
        }
        try await checkInstalled()
        guard try await configuredDirectory() == directory else { throw PanelError(message: "Der konfigurierte Update-Ordner wurde geändert. Bitte neu prüfen.") }
        if try await checkPending() { return directory }
        var renameError: Error?
        do { try await files.rename(directory, temporary, value.updateFilename) }
        catch { if RequestFailure.isCancellation(error) { throw CancellationError() }; renameError = error }
        // Rename can succeed at Wings and still produce an HTTP 500 in the panel's activity log.
        // The exact target bytes decide whether the update was queued, not that HTTP response.
        do {
            let final = try await files.read(pathJoin(directory, value.updateFilename))
            guard PluginArchive.digest(final) == hash else { throw PanelError(message: "Ziel-Prüfsumme stimmt nicht.") }
        } catch {
            if RequestFailure.isCancellation(error) { throw CancellationError() }
            throw PanelError(message: "Update vormerken in \(directory): \(renameError?.localizedDescription ?? error.localizedDescription)\nDie Datei wurde nicht als Update bestätigt. Temporäre Datei: \(temporary). Installierte JAR und Konfigurationen wurden nicht ersetzt.")
        }
        return directory
    }
}
