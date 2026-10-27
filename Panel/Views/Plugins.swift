import SwiftUI
import UniformTypeIdentifiers

struct PluginsView: View {
    let api: PanelAPI
    let server: Server
    @StateObject private var updates: AddonUpdateModel
    init(api: PanelAPI, server: Server) { self.api = api; self.server = server; _updates = StateObject(wrappedValue: AddonUpdateModel(api: api, server: server, kind: .plugin)) }
    @State private var entries: [JSON] = []
    @State private var pending: [JSON] = []
    @State private var updateDirectory = "/plugins/update"
    @State private var detail: PluginDetailSelection?
    @State private var refreshID = UUID()
    @State private var error: String?
    @State private var operation: Operation?
    @State private var importer = false
    @StateObject private var handoff = FileImportHandoff()
    @State private var candidate: PluginUpdateCandidate?
    @State private var stagedLocal: URL?
    @State private var busy = false
    @State private var message: String?
    var prefix: String { "servers/\(server.id)/files" }
    var jars: [JSON] { entries.filter { $0["is_file"].bool && ($0["name"].string.hasSuffix(".jar") || $0["name"].string.hasSuffix(".jar.disabled")) }.sorted { $0["name"].string.localizedStandardCompare($1["name"].string) == .orderedAscending } }
    var body: some View {
        List {
            Section {
                NavigationLink { PluginInstallView(api:api,server:server) } label: { Label("Neues Plugin hochladen",systemImage:"plus.circle") }
                Label("Paper-Plugins", systemImage: "puzzlepiece.extension.fill").font(.title2.bold()).foregroundStyle(Color.panelAccent)
                Text("Neue Versionen als JAR im Paper-Updateordner vormerken. Paper übernimmt sie beim nächsten Neustart. Wähle zuerst das installierte Plugin. Die App vergleicht Namen, Einstiegsklasse und Version der beiden JARs. Laufzeitkompatibilität und Abhängigkeiten musst du zusätzlich prüfen.").font(.footnote).foregroundStyle(.secondary)
                Text("Wähle unten beim gewünschten Plugin „Update-Datei hochladen“.").font(.footnote).foregroundStyle(.secondary)
                if busy { ProgressView("JAR prüfen / Update vormerken …") }
                if let message { Label(message, systemImage: "checkmark.circle").font(.footnote).foregroundStyle(Color.panelAccent) }
                NavigationLink {
                    AddonUpdateCenter(api: api, server: server, model: updates) { filename, url in inspect(url, filename: filename) }
                } label: {
                    Label(updates.cache.statuses.values.contains(where: { $0.release != nil }) ? "Updates verfügbar · Modrinth, Spigot & CurseForge" : "Modrinth, Spigot & CurseForge", systemImage: "arrow.triangle.2.circlepath")
                }
                if updates.busy { ProgressView(updates.progress).font(.caption) }
                NavigationLink("Plugin-Dateien & Konfigurationen") { FilesView(api: api, server: server, root: "/plugins") }
                NavigationLink("Vorgemerkte Updates") { FilesView(api: api, server: server, root: updateDirectory) }
                Link("Paper-Anleitung für Plugin-Updates", destination: URL(string: "https://docs.papermc.io/paper/updating/#step-2-update-plugins")!)
            } footer: { Text("Der Updatepfad wird aus bukkit.yml gelesen. Standard: /plugins/update. Neue Plugins direkt unter /plugins bei gestopptem Server installieren.") }
            Section {
                ForEach(jars, id: \.["name"].string) { entry in
                    let name = entry["name"].string
                    if let status = updates.cache.statuses[name], status.release != nil { Label(status.text, systemImage: "arrow.down.circle.fill").font(.caption).foregroundStyle(Color.panelAccent) }
                    PluginListRow(api: api, server: server, entry: entry, pending: pending, updateDirectory: updateDirectory, refreshID: refreshID, busy: busy || importer || candidate != nil, details: {
                        detail = PluginDetailSelection(entry: entry)
                    }, update: { handoff.begin(target: name); importer = true }, toggle: { rename(name) }, remove: {
                        operation = Operation(title: "Plugin löschen", detail: "\(name) wird endgültig gelöscht. Stoppe den Server zuerst. Der Konfigurationsordner bleibt erhalten.", path: prefix + "/delete", fixed: ["root": .string("/plugins"), "files": .array([.string(name)])], destructive: true, submit: "JAR löschen")
                    }, catalog: api.plugins)
                }
            } header: { Text("JAR-Dateien · \(jars.count)") } footer: { Text("Versionen werden aus den Server-JARs gelesen, nicht aus Dateinamen geraten. Sichtbare JARs bis 8 MiB werden automatisch gelesen; größere erst beim Öffnen der Details. Bereits geladene Plugins können bis zum Neustart eine andere Version verwenden.") }
        }.panelScreen().navigationTitle("Plugins").navigationBarTitleDisplayMode(.inline).task { await load(); await updates.scan(api: api, server: server, entries: entries) }.refreshable { api.plugins.clear(server: server.id); await load(); refreshID = UUID() }.errorAlert($error)
            .sheet(item: $detail) { PluginDetailsView(api: api, server: server, entry: $0.entry, catalog: api.plugins) }
            .sheet(item: $operation) { OperationSheet(api: api, operation: $0) { await load() } }
            .sheet(item: $candidate, onDismiss: { if let url = stagedLocal { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }; stagedLocal = nil }) { value in
                ActionForm(title: "Plugin-Update prüfen", detail: "\(value.installed.name)\nInstalliert: \(value.installed.version)\nNeue Datei: \(value.next.version)\nUpdate-Dateiname: \(value.updateFilename)\nAPI-Version: \(value.next.apiVersion)\n\n\(value.report)\n\nDie Prüfung bestätigt keine vollständige Minecraft-/Java-Kompatibilität. Das Update wird erst beim nächsten Serverstart angewendet.", fields: [Field(key: "ack", label: "Passende Serverversion und Abhängigkeiten geprüft", value: "false", kind: .toggle)], submit: "Update vormerken") { values in
                    guard values["ack"]?.bool == true else { throw PanelError(message: "Bitte die Kompatibilitätsprüfung bestätigen.") }
                    try await commit(value)
                }
            }
            .sheet(isPresented: $importer, onDismiss: {
                if let selection = handoff.takeSelection() { inspect(selection.file, filename: selection.target) }
                if let issue = handoff.takeError() { error = issue }
            }) {
                FileImportPicker(retainSelection: true) { result in
                    handoff.receive(result)
                    importer = false
                }.ignoresSafeArea()
            }
    }
    func load() async {
        do {
            entries = try await api.list(prefix + "/list", query: ["directory": "/plugins"])
            if let config = try? await api.fileText(server: server.id, path: "/bukkit.yml"), let directory = try? PluginUpdatePlan.directory(config) { updateDirectory = directory }
            pending = (try? await api.list(prefix + "/list", query: ["directory": updateDirectory]))?.filter { $0["is_file"].bool && $0["name"].string.hasSuffix(".jar") } ?? []
            for entry in pending where entry["size"].double <= 8 * 1024 * 1024 {
                await api.plugins.load(api: api, server: server.id, directory: updateDirectory, entry: entry)
            }
        } catch is CancellationError {} catch { self.error = error.localizedDescription }
    }
    func rename(_ name: String) {
        let target = name.hasSuffix(".disabled") ? String(name.dropLast(".disabled".count)) : name + ".disabled"
        operation = Operation(title: name.hasSuffix(".disabled") ? "Plugin aktivieren" : "Plugin deaktivieren", detail: "Stoppe den Server zuerst. Die Änderung wirkt beim nächsten Start. Konfigurationen werden nicht gelöscht.", path: prefix + "/rename", method: "PUT", fixed: ["root": .string("/plugins"), "files": .array([.object(["from": .string(name), "to": .string(target)])])], destructive: true)
    }
    func inspect(_ url: URL, filename: String?) {
        guard url.pathExtension.lowercased() == "jar", let filename else { error = "Zuerst das installierte Plugin und dann eine JAR auswählen."; return }
        busy = true; message = nil
        Task { defer { busy = false; let folder = url.deletingLastPathComponent(); if folder.lastPathComponent.hasPrefix("addon-") || folder.lastPathComponent.hasPrefix("market-") || folder.lastPathComponent.hasPrefix("import-") { try? FileManager.default.removeItem(at: folder) } }; do {
            guard !api.demo else { throw PanelError(message: "Updates sind im Demomodus deaktiviert.") }
            let access = url.startAccessingSecurityScopedResource(); defer { if access { url.stopAccessingSecurityScopedResource() } }
            let installedData = try await PluginUpdateFiles(api: api, server: server.id).read("/plugins/" + filename)
            let result = try await Task.detached(priority: .userInitiated) {
                let resource = try url.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey])
                guard resource.isRegularFile == true else { throw PanelError(message: "Bitte eine einzelne JAR-Datei auswählen, keinen Ordner.") }
                let size = resource.fileSize ?? 0
                guard size <= 128 * 1024 * 1024 else { throw PanelError(message: "JAR größer als 128 MiB.") }
                let nextData = try Data(contentsOf: url)
                let old = try PluginArchive.descriptor(installedData), next = try PluginArchive.descriptor(nextData)
                let report = try old.validateUpdate(next)
                let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
                try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
                let local = folder.appendingPathComponent(next.versionedFilename); try nextData.write(to: local)
                return PluginUpdateCandidate(filename: filename, file: local, installed: old, next: next, originalHash: PluginArchive.digest(installedData), report: report)
            }.value
            candidate = result; stagedLocal = result.file
        } catch { self.error = error.localizedDescription } }
    }
    func commit(_ value: PluginUpdateCandidate) async throws {
        busy = true; defer { busy = false }
        let directory = try await PluginUpdateInstaller.stage(value, files: PluginUpdateFiles(api: api, server: server.id))
        updateDirectory = directory
        await load()
        if let entry = pending.first(where: { $0["name"].string == value.updateFilename }) {
            api.plugins.rememberVerifiedUpload(value.next, server: server.id, directory: directory, entry: entry)
        }
        message = "\(value.updateFilename) geprüft hochgeladen nach \(directory). Wird beim nächsten Start von Paper übernommen, sofern kompatibel."
        Feedback.success()
    }
}


