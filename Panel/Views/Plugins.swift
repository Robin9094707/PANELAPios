import SwiftUI
import UniformTypeIdentifiers

struct PluginsView: View {
    let api: PanelAPI
    let server: Server
    @State private var entries: [JSON] = []
    @State private var error: String?
    @State private var operation: Operation?
    @State private var importer = false
    @State private var selectedPlugin: String?
    @State private var candidate: PluginUpdateCandidate?
    @State private var stagedLocal: URL?
    @State private var busy = false
    @State private var message: String?
    var prefix: String { "servers/\(server.id)/files" }
    var jars: [JSON] { entries.filter { $0["is_file"].bool && ($0["name"].string.hasSuffix(".jar") || $0["name"].string.hasSuffix(".jar.disabled")) }.sorted { $0["name"].string.localizedStandardCompare($1["name"].string) == .orderedAscending } }
    var body: some View {
        List {
            Section {
                Label("Paper-Plugins", systemImage: "puzzlepiece.extension.fill").font(.title2.bold()).foregroundStyle(.teal)
                Text("Neue Versionen als JAR im Paper-Updateordner vormerken. Paper übernimmt sie beim nächsten Neustart. Wähle zuerst das installierte Plugin. Die App vergleicht Namen, Einstiegsklasse und Version der beiden JARs. Laufzeitkompatibilität und Abhängigkeiten musst du zusätzlich prüfen.").font(.footnote).foregroundStyle(.secondary)
                Text("Wähle unten beim gewünschten Plugin „Update-Datei hochladen“.").font(.footnote).foregroundStyle(.secondary)
                if busy { ProgressView("Update übertragen …") }
                if let message { Label(message, systemImage: "checkmark.circle").font(.footnote).foregroundStyle(.teal) }
                NavigationLink("Plugin-Dateien & Konfigurationen") { FilesView(api: api, server: server, root: "/plugins") }
                NavigationLink("Vorgemerkte Updates") { FilesView(api: api, server: server, root: "/plugins/update") }
                Link("Paper-Anleitung für Plugin-Updates", destination: URL(string: "https://docs.papermc.io/paper/updating/#step-2-update-plugins")!)
            } footer: { Text("Der Updatepfad wird aus bukkit.yml gelesen. Standard: /plugins/update. Neue Plugins direkt unter /plugins bei gestopptem Server installieren.") }
            Section {
                ForEach(jars, id: \.["name"].string) { entry in
                    let name = entry["name"].string
                    VStack(alignment: .leading, spacing: 9) {
                        Text(name).font(.headline).textSelection(.enabled)
                        Text(bytes(entry["size"].double) + (name.hasSuffix(".disabled") ? " · Deaktiviert" : " · Bei nächstem Start geladen")).font(.caption).foregroundStyle(.secondary)
                        Button("Update-Datei hochladen", systemImage: "arrow.up.doc") { selectedPlugin = name; importer = true }.buttonStyle(.borderless).disabled(busy || name.hasSuffix(".disabled"))
                        HStack {
                            Button(name.hasSuffix(".disabled") ? "Aktivieren" : "Deaktivieren") { rename(name) }
                            Spacer()
                            Button("Löschen", systemImage: "trash", role: .destructive) { operation = Operation(title: "Plugin löschen", detail: "\(name) wird endgültig gelöscht. Stoppe den Server zuerst. Der Konfigurationsordner bleibt erhalten.", path: prefix + "/delete", fixed: ["root": .string("/plugins"), "files": .array([.string(name)])], destructive: true, submit: "JAR löschen") }
                        }.buttonStyle(.borderless).font(.subheadline)
                    }.padding(.vertical, 5)
                }
            } header: { Text("JAR-Dateien · \(jars.count)") } footer: { Text("Die Anzeige basiert auf Dateien, nicht auf einer Abfrage geladener Plugins. Aktivieren und Deaktivieren wirkt nach einem Neustart.") }
        }.panelScreen().navigationTitle("Plugins").navigationBarTitleDisplayMode(.inline).task { await load() }.refreshable { await load() }.errorAlert($error)
            .sheet(item: $operation) { OperationSheet(api: api, operation: $0) { await load() } }
            .sheet(item: $candidate, onDismiss: { if let url = stagedLocal { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }; stagedLocal = nil }) { value in
                ActionForm(title: "Plugin-Update prüfen", detail: "\(value.installed.name)\nInstalliert: \(value.installed.version)\nNeue Datei: \(value.next.version)\nAPI-Version: \(value.next.apiVersion)\n\n\(value.report)\n\nDie Prüfung bestätigt keine vollständige Minecraft-/Java-Kompatibilität. Das Update wird erst beim nächsten Serverstart angewendet.", fields: [Field(key: "ack", label: "Passende Serverversion und Abhängigkeiten geprüft", value: "false", kind: .toggle)], submit: "Update vormerken") { values in
                    guard values["ack"]?.bool == true else { throw PanelError(message: "Bitte die Kompatibilitätsprüfung bestätigen.") }
                    try await commit(value)
                }
            }
            .fileImporter(isPresented: $importer, allowedContentTypes: [.data, .item]) { result in
                switch result { case .success(let url): inspect(url); case .failure(let e): error = e.localizedDescription }
            }
    }
    func load() async { do { entries = try await api.list(prefix + "/list", query: ["directory": "/plugins"]) } catch { self.error = error.localizedDescription } }
    func rename(_ name: String) {
        let target = name.hasSuffix(".disabled") ? String(name.dropLast(".disabled".count)) : name + ".disabled"
        operation = Operation(title: name.hasSuffix(".disabled") ? "Plugin aktivieren" : "Plugin deaktivieren", detail: "Stoppe den Server zuerst. Die Änderung wirkt beim nächsten Start. Konfigurationen werden nicht gelöscht.", path: prefix + "/rename", method: "PUT", fixed: ["root": .string("/plugins"), "files": .array([.object(["from": .string(name), "to": .string(target)])])], destructive: true)
    }
    func inspect(_ url: URL) {
        guard url.pathExtension.lowercased() == "jar", let filename = selectedPlugin else { error = "Zuerst das installierte Plugin und dann eine JAR auswählen."; return }
        busy = true; message = nil
        Task { defer { busy = false }; do {
            guard !api.demo else { throw PanelError(message: "Updates sind im Demomodus deaktiviert.") }
            let access = url.startAccessingSecurityScopedResource(); defer { if access { url.stopAccessingSecurityScopedResource() } }
            let installedData = try await api.fileData(server: server.id, path: "/plugins/" + filename, limit: 128 * 1024 * 1024)
            let result = try await Task.detached(priority: .userInitiated) {
                let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
                guard size <= 128 * 1024 * 1024 else { throw PanelError(message: "JAR größer als 128 MiB.") }
                let nextData = try Data(contentsOf: url)
                let old = try PluginArchive.descriptor(installedData), next = try PluginArchive.descriptor(nextData)
                let report = try old.validateUpdate(next)
                let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
                try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
                let local = folder.appendingPathComponent(filename); try nextData.write(to: local)
                return PluginUpdateCandidate(filename: filename, file: local, installed: old, next: next, originalHash: PluginArchive.digest(installedData), report: report)
            }.value
            candidate = result; stagedLocal = result.file
        } catch { self.error = error.localizedDescription } }
    }
    func commit(_ value: PluginUpdateCandidate) async throws {
        busy = true; defer { busy = false }
        let current = try await api.fileData(server: server.id, path: "/plugins/" + value.filename, limit: 128 * 1024 * 1024)
        guard PluginArchive.digest(current) == value.originalHash else { throw PanelError(message: "Das installierte Plugin wurde zwischenzeitlich geändert. Bitte neu auswählen.") }
        let config = try await api.fileText(server: server.id, path: "/bukkit.yml")
        var update = "update"
        for line in config.components(separatedBy: .newlines) {
            let text = line.trimmingCharacters(in: .whitespaces)
            if text.hasPrefix("update-folder:") { update = String(text.dropFirst("update-folder:".count)).components(separatedBy: " #")[0].trimmingCharacters(in: CharacterSet(charactersIn: " \"'")) }
        }
        let components = update.split(separator: "/").map(String.init)
        guard !update.hasPrefix("/"), !update.contains("\\"), !components.isEmpty, !components.contains(".."), !components.contains(".") else { throw PanelError(message: "Nicht unterstützter update-folder in bukkit.yml. Nutze den Datei-Manager.") }
        var directory = "/plugins"
        for part in components {
            let entries = try await api.list(prefix + "/list", query: ["directory": directory])
            if !entries.contains(where: { $0["name"].string == part && !$0["is_file"].bool }) { try await api.mutate(prefix + "/create-folder", ["root": directory, "name": part]) }
            directory = pathJoin(directory, part)
        }
        let staged = try await api.list(prefix + "/list", query: ["directory": directory])
        guard !staged.contains(where: { $0["name"].string == value.filename }) else { throw PanelError(message: "Für dieses Plugin ist bereits ein Update vorgemerkt. Entferne die alte Update-Datei zuerst im Datei-Manager.") }
        try await api.upload(server: server.id, directory: directory, file: value.file)
        let uploaded = try await api.fileData(server: server.id, path: pathJoin(directory, value.filename), limit: 128 * 1024 * 1024)
        let local = try Data(contentsOf: value.file, options: .mappedIfSafe)
        guard PluginArchive.digest(uploaded) == PluginArchive.digest(local) else { throw PanelError(message: "Upload-Prüfsumme stimmt nicht überein. Entferne die vorgemerkte Datei vor dem Neustart.") }
        message = "\(value.next.name) \(value.next.version) geprüft hochgeladen nach \(directory). Wird beim nächsten Start von Paper übernommen, sofern kompatibel."
        Feedback.success()
    }
}
