import SwiftUI
import UniformTypeIdentifiers

struct PluginsView: View {
    let api: PanelAPI
    let server: Server
    @State private var entries: [JSON] = []
    @State private var error: String?
    @State private var operation: Operation?
    @State private var importer = false
    @State private var busy = false
    @State private var message: String?
    var prefix: String { "servers/\(server.id)/files" }
    var jars: [JSON] { entries.filter { $0["is_file"].bool && ($0["name"].string.hasSuffix(".jar") || $0["name"].string.hasSuffix(".jar.disabled")) }.sorted { $0["name"].string.localizedStandardCompare($1["name"].string) == .orderedAscending } }
    var body: some View {
        List {
            Section {
                Label("Paper-Plugins", systemImage: "puzzlepiece.extension.fill").font(.title2.bold()).foregroundStyle(.teal)
                Text("Neue Versionen als JAR im Paper-Updateordner vormerken. Paper übernimmt sie beim nächsten Neustart. Die Datei muss zum vorhandenen Plugin gehören; die App prüft keine Versionskompatibilität.").font(.footnote).foregroundStyle(.secondary)
                Button("Plugin-Update hochladen", systemImage: "arrow.up.doc") { importer = true }.disabled(busy)
                if busy { ProgressView("Update übertragen …") }
                if let message { Label(message, systemImage: "checkmark.circle").font(.footnote).foregroundStyle(.teal) }
                NavigationLink("Plugin-Dateien & Konfigurationen") { FilesView(api: api, server: server, root: "/plugins") }
                NavigationLink("Vorgemerkte Updates") { FilesView(api: api, server: server, root: "/plugins/update") }
                Link("Paper-Anleitung für Plugin-Updates", destination: URL(string: "https://docs.papermc.io/paper/updating/#step-2-update-plugins")!)
            } footer: { Text("Standardpfad: /plugins/update. Falls update-folder in bukkit.yml angepasst wurde, verwende den Datei-Manager. Neue Plugins direkt unter /plugins bei gestopptem Server installieren.") }
            Section {
                ForEach(jars, id: \.["name"].string) { entry in
                    let name = entry["name"].string
                    VStack(alignment: .leading, spacing: 9) {
                        Text(name).font(.headline).textSelection(.enabled)
                        Text(bytes(entry["size"].double) + (name.hasSuffix(".disabled") ? " · Deaktiviert" : " · Bei nächstem Start geladen")).font(.caption).foregroundStyle(.secondary)
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
            .fileImporter(isPresented: $importer, allowedContentTypes: [.data, .item]) { result in
                switch result { case .success(let url): stage(url); case .failure(let e): error = e.localizedDescription }
            }
    }
    func load() async { do { entries = try await api.list(prefix + "/list", query: ["directory": "/plugins"]) } catch { self.error = error.localizedDescription } }
    func rename(_ name: String) {
        let target = name.hasSuffix(".disabled") ? String(name.dropLast(".disabled".count)) : name + ".disabled"
        operation = Operation(title: name.hasSuffix(".disabled") ? "Plugin aktivieren" : "Plugin deaktivieren", detail: "Stoppe den Server zuerst. Die Änderung wirkt beim nächsten Start. Konfigurationen werden nicht gelöscht.", path: prefix + "/rename", method: "PUT", fixed: ["root": .string("/plugins"), "files": .array([.object(["from": .string(name), "to": .string(target)])])], destructive: true)
    }
    func stage(_ url: URL) {
        guard url.pathExtension.lowercased() == "jar" else { error = "Bitte eine Plugin-JAR auswählen."; return }
        busy = true; message = nil
        Task { defer { busy = false }; do {
            guard !api.demo else { throw PanelError(message: "Uploads sind im Demomodus deaktiviert.") }
            let access = url.startAccessingSecurityScopedResource(); defer { if access { url.stopAccessingSecurityScopedResource() } }
            let children = try await api.list(prefix + "/list", query: ["directory": "/plugins"])
            if !children.contains(where: { $0["name"].string == "update" && !$0["is_file"].bool }) { try await api.mutate(prefix + "/create-folder", ["root": "/plugins", "name": "update"]) }
            let staged = try await api.list(prefix + "/list", query: ["directory": "/plugins/update"])
            guard !staged.contains(where: { $0["name"].string == url.lastPathComponent }) else { throw PanelError(message: "Diese JAR ist bereits vorgemerkt. Entferne die alte Update-Datei im Datei-Manager, bevor du sie erneut hochlädst.") }
            try await api.upload(server: server.id, directory: "/plugins/update", file: url)
            message = "\(url.lastPathComponent) vorgemerkt. Starte den Server neu, um das Update anzuwenden."
        } catch { self.error = error.localizedDescription } }
    }
}
