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
    @State private var progress = ""
    @State private var acknowledged = false
    @State private var candidateSuite: SuiteAPI?
    @State private var serverMode = false
    var prefix: String { "servers/\(server.id)/files" }
    var jars: [JSON] { entries.filter { $0["is_file"].bool && ($0["name"].string.hasSuffix(".jar") || $0["name"].string.hasSuffix(".jar.disabled")) }.sorted { $0["name"].string.localizedStandardCompare($1["name"].string) == .orderedAscending } }
    var body: some View {
        ScrollViewReader { scroll in
        List {
            Section {
                Label("Paper-Plugins", systemImage: "puzzlepiece.extension.fill").font(.title2.bold()).foregroundStyle(Color.panelAccent)
                Text("Neue Versionen als JAR im Paper-Updateordner vormerken. Paper übernimmt sie beim nächsten Neustart. Wähle zuerst das installierte Plugin. Die App vergleicht Namen, Einstiegsklasse und Version der beiden JARs. Laufzeitkompatibilität und Abhängigkeiten musst du zusätzlich prüfen.").font(.footnote).foregroundStyle(.secondary)
                Text("Wähle unten beim gewünschten Plugin „Update-Datei hochladen“.").font(.footnote).foregroundStyle(.secondary)
                Label(serverMode ? "Serverprüfung · Admin Suite" : "Panel-Modus · ohne Serverprüfung", systemImage: serverMode ? "server.rack" : "iphone").font(.caption).foregroundStyle(.secondary)
                if busy { ProgressView(progress).accessibilityIdentifier("plugin-update-progress") }
                if let error { Label(error, systemImage: "exclamationmark.triangle").font(.footnote).foregroundStyle(.orange).accessibilityIdentifier("plugin-update-error") }
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
            } footer: { Text("Der Updatepfad wird aus bukkit.yml gelesen. Standard: /plugins/update. Neue Plugins direkt unter /plugins bei gestopptem Server installieren.") }.id("plugin-status")
            if let value = candidate {
                Section("Plugin-Update prüfen") {
                    LabeledContent("Plugin", value: value.installed.name)
                    LabeledContent("Installiert", value: value.installed.version)
                    LabeledContent("Neue Version", value: value.next.version)
                    Text(value.report).font(.footnote).foregroundStyle(.secondary)
                    Text(candidateSuite != nil ? "Prüfung und Ablage direkt durch die Admin Suite. Nur die neue JAR wird übertragen." : "Prüfung und Übertragung über das Panel. Die installierte JAR wird zum Vergleich gelesen.").font(.footnote)
                    Text("Wird erst beim nächsten Serverstart angewendet.").font(.footnote.weight(.semibold))
                    Toggle("Serverversion und Abhängigkeiten geprüft", isOn: $acknowledged)
                    Button("Update vormerken", systemImage: "arrow.up.doc.fill") {
                        Task { do { try await commit(value); clearCandidate() } catch { self.error = error.localizedDescription } }
                    }.buttonStyle(.borderedProminent).disabled(!acknowledged || busy).accessibilityIdentifier("plugin-update-confirm")
                    Button("Auswahl verwerfen", role: .cancel) { clearCandidate() }.disabled(busy)
                }.id("plugin-update-preview")
            }
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
            .sheet(isPresented: $importer) {
                FileImportPicker(retainSelection: true) { result in
                    // Consume the delegate result here. No dependency on SwiftUI's onDismiss ordering.
                    handoff.receive(result)
                    importer = false
                    if let selection = handoff.takeSelection() { inspect(selection.file, filename: selection.target) }
                    if let issue = handoff.takeError() { error = issue }
                }.ignoresSafeArea()
            }
            .safeAreaInset(edge: .bottom) {
                if busy { HStack(spacing: 12) { ProgressView(); Text(progress).font(.subheadline); Spacer() }.padding().background(.regularMaterial).accessibilityIdentifier("plugin-update-busy-banner") }
            }
            .onChange(of: error) { _, value in
                if value != nil { withAnimation { scroll.scrollTo("plugin-status", anchor: .top) } }
            }
            .onChange(of: candidate?.id) { _, value in
                if value != nil { withAnimation { scroll.scrollTo("plugin-update-preview", anchor: .top) } }
            }
        }
    }
    func clearCandidate() {
        if let url = stagedLocal { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        candidate = nil; stagedLocal = nil; candidateSuite = nil; acknowledged = false
    }

    func load() async {
        do {
            if let suite = try await PluginServerAPI.available(api: api, server: server.id) {
                let installed = try await suite.request("plugin-files")
                let queued = try await suite.request("plugin-files", query: ["pending": "true"])
                func entry(_ row: JSON) -> JSON { .object(["name": row["filename"].string.isEmpty ? row["name"] : row["filename"], "size": row["size"], "modified_at": row["modified_at"], "is_file": .bool(true)]) }
                entries = installed["items"].array.map(entry); pending = queued["items"].array.map(entry)
                updateDirectory = installed["updateDirectory"].string; serverMode = true
                for (rows, directory) in [(installed["items"].array, "/plugins"), (queued["items"].array, updateDirectory)] {
                    for row in rows { api.plugins.rememberServerMetadata(row, server: server.id, directory: directory, entry: entry(row)) }
                }
                return
            }
            serverMode = false
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
        busy = true; message = nil; error = nil; progress = "Ausgewählte JAR prüfen …"; acknowledged = false
        Task { defer { busy = false; let folder = url.deletingLastPathComponent(); if folder.lastPathComponent.hasPrefix("addon-") || folder.lastPathComponent.hasPrefix("market-") || folder.lastPathComponent.hasPrefix("import-") { try? FileManager.default.removeItem(at: folder) } }; do {
            #if DEBUG
            let fixture = api.demo && PluginPickerFixture.enabled
            #else
            let fixture = false
            #endif
            guard !api.demo || fixture else { throw PanelError(message: "Updates sind im Demomodus deaktiviert.") }
            let access = url.startAccessingSecurityScopedResource(); defer { if access { url.stopAccessingSecurityScopedResource() } }
            let suite = try await PluginServerAPI.available(api: api, server: server.id)
            let old: PluginDescriptor, originalHash: String
            #if DEBUG
            let fixtureData: Data? = fixture ? PluginPickerFixture.old : nil
            #else
            let fixtureData: Data? = nil
            #endif
            if let fixtureData {
                old = try PluginArchive.descriptor(fixtureData); originalHash = PluginArchive.digest(fixtureData)
            } else if let suite {
                progress = "Installiertes Plugin auf dem Server prüfen …"
                let row = try await PluginServerAPI.inspect(suite, filename: filename)
                old = try PluginServerAPI.metadata(row); originalHash = row["sha256"].string
            } else {
                progress = "Installierte JAR über das Panel lesen …"
                let installedData = try await PluginUpdateFiles(api: api, server: server.id).read("/plugins/" + filename)
                let parsed = try await Task.detached(priority: .userInitiated) { (try PluginArchive.descriptor(installedData), PluginArchive.digest(installedData)) }.value
                old = parsed.0; originalHash = parsed.1
            }
            let canStageOnServer = suite?.scope == "full"
            let result = try await Task.detached(priority: .userInitiated) {
                let resource = try url.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey])
                guard resource.isRegularFile == true else { throw PanelError(message: "Bitte eine einzelne JAR-Datei auswählen, keinen Ordner.") }
                let size = resource.fileSize ?? 0
                guard size <= 128 * 1024 * 1024 else { throw PanelError(message: "JAR größer als 128 MiB.") }
                let nextData = try Data(contentsOf: url)
                let next = try PluginArchive.descriptor(nextData)
                let report = try old.validateUpdate(next)
                let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
                try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
                let local = folder.appendingPathComponent(next.versionedFilename); try nextData.write(to: local)
                return PluginUpdateCandidate(filename: filename, file: local, installed: old, next: next, originalHash: originalHash, report: report, serverFilename: canStageOnServer ? filename : nil)
            }.value
            candidateSuite = canStageOnServer ? suite : nil; candidate = result; stagedLocal = result.file; Feedback.success()
        } catch { self.error = error.localizedDescription } }
    }
    func commit(_ value: PluginUpdateCandidate) async throws {
        guard !api.demo else { throw PanelError(message: "Updates sind im Demomodus deaktiviert.") }
        busy = true; defer { busy = false }
        progress = candidateSuite != nil ? "JAR an den Server übertragen, prüfen und vormerken …" : "JAR über das Panel übertragen und zurückprüfen …"
        let directory: String
        if let suite = candidateSuite { directory = try await PluginServerAPI.stage(value, client: suite) }
        else { directory = try await PluginUpdateInstaller.stage(value, files: PluginUpdateFiles(api: api, server: server.id)) }
        updateDirectory = directory
        await load()
        if let entry = pending.first(where: { $0["name"].string == value.updateFilename }) {
            api.plugins.rememberVerifiedUpload(value.next, server: server.id, directory: directory, entry: entry)
        }
        message = "\(value.updateFilename) geprüft hochgeladen nach \(directory). Wird beim nächsten Start von Paper übernommen, sofern kompatibel."
        Feedback.success()
    }
}


