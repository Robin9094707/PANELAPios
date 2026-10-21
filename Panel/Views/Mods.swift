import SwiftUI

struct ModsView: View {
    let api: PanelAPI
    let server: Server
    @State private var entries: [JSON] = []
    @State private var descriptors: [String: ModDescriptor] = [:]
    @State private var errors: [String: String] = [:]
    @State private var error: String?
    @State private var notice: String?
    @State private var busy = false
    @State private var loaded = false
    @State private var importer = false
    @State private var selected: JSON?
    @State private var imported: URL?
    @State private var candidate: ModCandidate?
    @State private var detail: ModDetail?
    @State private var toggleFile: String?
    @State private var staging: URL?
    var jars: [JSON] { entries.filter { $0["is_file"].bool && ($0["name"].string.hasSuffix(".jar") || $0["name"].string.hasSuffix(".jar.disabled")) }.sorted { $0["name"].string.localizedStandardCompare($1["name"].string) == .orderedAscending } }
    var body: some View {
        List {
            Section {
                Label("Minecraft Java Mods", systemImage: "shippingbox.fill").font(.title2.bold()).foregroundStyle(.teal)
                Text("Fabric, Quilt, Forge und NeoForge; auch 1.20.1 sowie ältere und neuere Versionen. Wähle JARs passend zu deinem Server, Loader und deinen Abhängigkeiten. Die App prüft Mod-IDs, Loader und Version vor Updates. Änderungen benötigen einen gestoppten Server.").font(.footnote).foregroundStyle(.secondary)
                Button("Neue Mod installieren / hochladen", systemImage: "plus.circle") { selected = nil; importer = true }.requiresAccess(api, server.id, "file.create")
                NavigationLink("Alle Mod-Dateien") { FilesView(api: api, server: server, root: "/mods") }
                NavigationLink("Gesicherte Mod-Versionen") { FilesView(api: api, server: server, root: "/simplex-mod-backups") }
                Text("Wiederherstellung: Server stoppen, neue JAR aus /mods verschieben und die gesicherte JAR zurück nach /mods verschieben. Spielstände und Konfigurationen werden durch die Mod-Sicherung nicht zurückgesetzt.").font(.caption).foregroundStyle(.secondary)
                if busy { ProgressView("Mod prüfen / übertragen …") }
                if let notice { Label(notice, systemImage: "checkmark.circle").font(.footnote).foregroundStyle(.teal).textSelection(.enabled) }
            }
            if !loaded { ProgressView("Mods laden …") }
            if loaded && jars.isEmpty { Text("Keine Mod-JARs in /mods").foregroundStyle(.secondary) }
            Section("Mod-Dateien · \(jars.count)") {
                ForEach(jars, id: \.["name"].string) { entry in modRow(entry) }
            }
        }.panelScreen().navigationTitle("Mods").navigationBarTitleDisplayMode(.inline).disabled(busy).task { await load() }.refreshable { descriptors = [:]; errors = [:]; await load() }.errorAlert($error)
            .sheet(item: $detail) { value in NavigationStack { List { Section(value.value.name) { LabeledContent("Mod-IDs", value: value.value.ids.joined(separator: ", ")); LabeledContent("Version", value: value.value.version); LabeledContent("Loader", value: value.value.loader); LabeledContent("Minecraft", value: value.value.minecraft); LabeledContent("Umgebung", value: value.value.environment) }; Section("Metadaten") { Text(value.value.metadata).font(.caption.monospaced()).textSelection(.enabled) } }.navigationTitle("Mod-Details").toolbar { ToolbarItem(placement: .confirmationAction) { Button("Fertig") { detail = nil } } } } }
            .sheet(isPresented: Binding(get: { toggleFile != nil }, set: { if !$0 { toggleFile = nil } })) {
                if let name = toggleFile {
                    ActionForm(title: name.hasSuffix(".disabled") ? "Mod aktivieren" : "Mod deaktivieren", detail: "Der Server muss gestoppt sein. Abhängige Mods können diese Mod benötigen. Die Änderung wirkt beim nächsten Start.", fields: [], destructive: true, submit: "Bestätigen") { _ in
                        try await ModInstaller.stopped(api, server: server.id)
                        let target = name.hasSuffix(".disabled") ? String(name.dropLast(".disabled".count)) : name + ".disabled"
                        let rows = try await api.list("servers/\(server.id)/files/list", query: ["directory": "/mods"])
                        guard !rows.contains(where: { $0["name"].string.caseInsensitiveCompare(target) == .orderedSame }) else { throw PanelError(message: "Zieldatei existiert bereits. Es wird nichts überschrieben.") }
                        try await api.mutate("servers/\(server.id)/files/rename", method: "PUT", ["root": "/mods", "files": [["from": name, "to": target]]])
                        descriptors = [:]; await load()
                    }
                }
            }
            .sheet(isPresented: $importer, onDismiss: { if let imported { self.imported = nil; inspect(imported) } }) {
                FileImportPicker { result in switch result { case .success(let urls): imported = urls.first; case .failure(let failure): error = failure.localizedDescription }; importer = false }.ignoresSafeArea()
            }
            .sheet(item: $candidate, onDismiss: { if let staging { try? FileManager.default.removeItem(at: staging) }; staging = nil }) { value in
                ActionForm(title: value.installed == nil ? "Mod installieren" : "Mod aktualisieren", detail: "\(value.next.name)\nIDs: \(value.next.ids.joined(separator: ", "))\nLoader: \(value.next.loader)\nInstalliert: \(value.installed?.version ?? "–")\nNeue Version: \(value.next.version)\nMinecraft: \(value.next.minecraft)\n\nVorhandene Mod-IDs und Upload-Prüfsumme werden geprüft. Die bisherige JAR wird außerhalb von /mods gesichert. Die Metadatenprüfung garantiert keine vollständige Laufzeitkompatibilität.", fields: [Field(key: "ack", label: "Minecraft-Version, Loader und Abhängigkeiten passen", value: "false", kind: .toggle)], submit: "Geprüft installieren") { values in
                    guard values["ack"]?.bool == true else { throw PanelError(message: "Bestätige zuerst die Kompatibilität.") }
                    busy = true; defer { busy = false }
                    notice = try await ModInstaller.install(value, api: api, server: server.id)
                    descriptors = [:]; await load(); Feedback.success()
                }
            }
    }
    func modRow(_ entry: JSON) -> some View {
        let name = entry["name"].string, descriptor = descriptors[name]
        return VStack(alignment: .leading, spacing: 8) {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text(descriptor?.name ?? name).font(.headline)
                    Text(name).font(.caption.monospaced()).foregroundStyle(.secondary)
                }
                Spacer()
                Menu {
                    Button("Details & Version lesen", systemImage: "info.circle") { Task { await metadata(entry); if let value = descriptors[name] { detail = ModDetail(value: value) } } }.requiresAccess(api, server.id, "file.read-content")
                    if !name.hasSuffix(".disabled") { Button("Update-Datei hochladen", systemImage: "arrow.up.doc") { selected = entry; importer = true }.requiresAccess(api, server.id, "file.update") }
                    Button(name.hasSuffix(".disabled") ? "Aktivieren" : "Deaktivieren", systemImage: "power") { toggle(name) }.requiresAccess(api, server.id, "file.update")
                } label: { Image(systemName: "ellipsis.circle").font(.title3) }
            }
            if let descriptor { Text("\(descriptor.loader) · \(descriptor.version) · MC \(descriptor.minecraft)").font(.caption).foregroundStyle(.teal) }
            else if let problem = errors[name] { Text(problem).font(.caption).foregroundStyle(.orange) }
            else { Text("Metadaten über Details laden · " + bytes(entry["size"].double)).font(.caption).foregroundStyle(.secondary) }
        }.padding(.vertical, 5)
    }
    func load() async {
        do {
            let root = try await api.list("servers/\(server.id)/files/list", query: ["directory": "/"])
            if root.contains(where: { $0["name"].string == "mods" && !$0["is_file"].bool }) { entries = try await api.list("servers/\(server.id)/files/list", query: ["directory": "/mods"]) } else { entries = [] }
            loaded = true
        } catch is CancellationError {} catch { self.error = error.localizedDescription }
    }
    func metadata(_ entry: JSON) async {
        let name = entry["name"].string
        guard descriptors[name] == nil else { return }; busy = true; defer { busy = false }
        do { let data = try await api.fileData(server: server.id, path: "/mods/" + name, limit: 128 * 1024 * 1024); descriptors[name] = try await Task.detached { try ModArchive.descriptor(data) }.value; errors[name] = nil }
        catch { errors[name] = error.localizedDescription }
    }
    func toggle(_ name: String) { toggleFile = name }
    func inspect(_ url: URL) {
        busy = true; notice = nil
        Task { defer { busy = false }; do {
            guard !api.demo else { throw PanelError(message: "Mods sind im Demomodus nur lesbar.") }
            guard url.pathExtension.lowercased() == "jar" else { throw PanelError(message: "Wähle eine einzelne Mod-JAR.") }
            let access = url.startAccessingSecurityScopedResource(); defer { if access { url.stopAccessingSecurityScopedResource() } }
            let resource = try url.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey])
            guard resource.isRegularFile == true, (resource.fileSize ?? 0) <= 128 * 1024 * 1024 else { throw PanelError(message: "Nur JAR-Dateien bis 128 MiB werden unterstützt.") }
            let bytes = try await Task.detached { try Data(contentsOf: url) }.value
            let next = try await Task.detached { try ModArchive.descriptor(bytes) }.value
            guard next.environment != "client" else { throw PanelError(message: "Diese Mod ist ausschließlich für Clients vorgesehen.") }
            var installed: ModDescriptor?, hash: String?
            if let selected {
                let data = try await api.fileData(server: server.id, path: "/mods/" + selected["name"].string, limit: 128 * 1024 * 1024)
                let old = try await Task.detached { try ModArchive.descriptor(data) }.value
                try old.validateUpdate(next); installed = old; hash = PluginArchive.digest(data)
            }
            let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            let file = folder.appendingPathComponent(url.lastPathComponent); try bytes.write(to: file)
            staging = folder; candidate = ModCandidate(file: file, next: next, installed: installed, oldFilename: selected?["name"].string, oldHash: hash)
        } catch { self.error = error.localizedDescription } }
    }
}
struct ModDetail: Identifiable { let id = UUID(); let value: ModDescriptor }
