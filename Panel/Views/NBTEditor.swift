import SwiftUI

@MainActor final class NBTModel: ObservableObject {
    @Published var document: NBTDocument?
    @Published var error: String?
    @Published var state = "unknown"
    @Published var busy = false
    @Published var dirty = false
    @Published var backup: String?
    private var original = Data()
    let api: PanelAPI
    let server: Server
    let path: String
    init(api: PanelAPI, server: Server, path: String) { self.api = api; self.server = server; self.path = path }
    var root: NBTNode? { document?.root }
    var playerName: String {
        let bukkit = root?.child("bukkit")?.child("lastKnownName")?.text ?? ""
        return bukkit.isEmpty ? PlayerNameCache.shared.name(api, server, filename: URL(fileURLWithPath: path).lastPathComponent) ?? URL(fileURLWithPath: path).deletingPathExtension().lastPathComponent : bukkit
    }
    func load() async {
        busy = true; defer { busy = false }
        do {
            let data = try await api.fileData(server: server.id, path: path)
            let decoded = try await Task.detached(priority: .userInitiated) { try NBTDocument.decode(data) }.value
            try Task.checkCancellation(); original = data; document = decoded
            await refreshState()
        } catch { if !Task.isCancelled { self.error = error.localizedDescription } }
    }
    func refreshState() async { do { state = try await api.request("servers/\(server.id)/resources").attributes["current_state"].string } catch { state = "unknown" } }
    func update(_ id: UUID, _ text: String) throws {
        if root?.find(id)?.text == text { return }
        guard var doc = document else { return }
        func change(_ node: inout NBTNode) throws -> Bool {
            if node.id == id { try node.setText(text); return true }
            for i in node.children.indices { if try change(&node.children[i]) { return true } }; return false
        }
        if try change(&doc.root) { document = doc; dirty = true }
    }
    func apply() async {
        busy = true; defer { busy = false }
        do {
            guard !api.demo, let doc = document else { throw PanelError(message: "Keine schreibbare NBT-Datei geladen.") }
            await refreshState(); guard state == "offline" else { throw PanelError(message: "Anwenden ist nur bei gestopptem Server möglich. So überschreibt Minecraft keine Spielerdaten.") }
            let current = try await api.fileData(server: server.id, path: path)
            guard current == original else { throw PanelError(message: "Die Serverdatei hat sich geändert. Exportiere deinen Entwurf und öffne die Datei erneut.") }
            let data = try await Task.detached(priority: .userInitiated) { let encoded = try doc.encode(); _ = try NBTDocument.decode(encoded); return encoded }.value
            let prefix = "servers/\(server.id)/files"
            let directories = try await api.list(prefix + "/list", query: ["directory": "/"])
            if !directories.contains(where: { $0["name"].string == ".panel-nbt-backups" && !$0["is_file"].bool }) { try await api.mutate(prefix + "/create-folder", ["root": "/", "name": ".panel-nbt-backups"]) }
            let copy = "/.panel-nbt-backups/\(UUID().uuidString)-\(URL(fileURLWithPath: path).lastPathComponent)"
            _ = try await api.raw(prefix + "/write", method: "POST", query: ["file": copy], binary: original)
            guard try await api.fileData(server: server.id, path: copy) == original else { throw PanelError(message: "Die Sicherung konnte nicht verifiziert werden. Originaldatei bleibt unangetastet.") }
            backup = copy
            await refreshState(); guard state == "offline" else { throw PanelError(message: "Der Server wurde inzwischen gestartet. Änderung abgebrochen.") }
            guard try await api.fileData(server: server.id, path: path) == original else { throw PanelError(message: "Dateikonflikt. Keine Änderung angewendet.") }
            _ = try await api.raw(prefix + "/write", method: "POST", query: ["file": path], binary: data)
            guard try await api.fileData(server: server.id, path: path) == data else { throw PanelError(message: "Die geschriebene Datei stimmt nicht überein. Prüfe den Server und die Sicherung: \(copy)") }
            original = data; dirty = false; Feedback.success()
        } catch { self.error = error.localizedDescription }
    }
    func export() async throws -> URL {
        guard let doc = document else { throw PanelError(message: "Keine Datei geladen.") }
        let data = try await Task.detached { try doc.encode() }.value
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let file = folder.appendingPathComponent(URL(fileURLWithPath: path).lastPathComponent); try data.write(to: file); return file
    }
}
struct NBTEditor: View {
    @StateObject private var model: NBTModel
    @Environment(\.dismiss) private var dismiss
    @State private var confirm = false
    @State private var discard = false
    @State private var share: ShareItem?
    init(api: PanelAPI, server: Server, path: String) { _model = StateObject(wrappedValue: NBTModel(api: api, server: server, path: path)) }
    var body: some View {
        NavigationStack {
            List {
                Section {
                    Label(model.playerName, systemImage: "person.crop.square.fill").font(.title2.bold()).foregroundStyle(Color.panelAccent).textSelection(.enabled)
                    HStack { Text("Server"); Spacer(); StatePill(state: model.state) }
                    Text("Du bearbeitest eine lokale Kopie. Anwenden ist bei gestopptem Server möglich und erstellt zuerst eine geprüfte Sicherung unter /.panel-nbt-backups. Kein automatischer Kick.").font(.footnote).foregroundStyle(.secondary)
                    if model.state != "offline" { Label("Serverdatei schreibgeschützt · zum Anwenden Server stoppen", systemImage: "lock.fill").font(.footnote).foregroundStyle(.orange) }
                    if model.busy { ProgressView("Datei verarbeiten …") }
                    if let copy = model.backup { Text("Sicherung: \(copy)").font(.caption.monospaced()).textSelection(.enabled) }
                }
                if let root = model.root {
                    if let health = root.child("Health")?.number {
                        Section("Spielerzustand") {
                            HStack(spacing: 4) { ForEach(0..<10) { index in
                                ZStack {
                                    Image(systemName: "heart").resizable().scaledToFit()
                                    Image(systemName: "heart.fill").resizable().scaledToFit().mask(alignment: .leading) { Rectangle().frame(width: 18 * max(0, min(1, health / 2 - Double(index)))) }
                                }.frame(width: 18, height: 18).foregroundStyle(.red).accessibilityHidden(true)
                            } }
                            LabeledContent("Gesundheit", value: "\(health.formatted(.number.precision(.fractionLength(1)))) Punkte · \((health / 2).formatted(.number.precision(.fractionLength(2)))) Herzen")
                            LabeledContent("Hunger", value: root.child("foodLevel")?.text ?? "–")
                            LabeledContent("Erfahrungslevel", value: root.child("XpLevel")?.text ?? "–")
                            LabeledContent("Dimension", value: root.child("Dimension")?.text ?? "–")
                            if let pos = root.child("Pos") { LabeledContent("Position", value: pos.children.map { $0.text }.joined(separator: " / ")).font(.caption.monospaced()) }
                        }
                    }
                    Section("Schnell bearbeiten") {
                        ForEach(root.children.filter { ["Health", "foodLevel", "foodSaturationLevel", "XpLevel", "XpP", "playerGameType", "Invulnerable", "Pos", "Dimension", "abilities", "Inventory", "EnderItems"].contains($0.name) }) { node in NBTEntry(model: model, node: node, title: node.name) }
                    }
                    Section { NavigationLink("Alle NBT-Daten · \(root.children.count) Felder") { NBTBranch(model: model, nodeID: root.id, title: "NBT-Wurzel") } }
                    Section {
                        Button("Entwurf exportieren", systemImage: "square.and.arrow.up") { Task { do { share = ShareItem(url: try await model.export()) } catch { model.error = error.localizedDescription } } }
                        Button("Auf Server anwenden", systemImage: "checkmark.shield") { confirm = true }.disabled(!model.dirty || model.busy || model.state != "offline").requiresAccess(model.api, model.server.id, "file.update", "file.create")
                        Button("Serverstatus aktualisieren", systemImage: "arrow.clockwise") { Task { await model.refreshState() } }
                    }
                }
            }.panelScreen().refreshable { if !model.busy { if model.dirty { await model.refreshState() } else { await model.load() } } }.navigationTitle("NBT-Editor").navigationBarTitleDisplayMode(.inline).errorAlert($model.error)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Schließen") { if model.dirty { discard = true } else { dismiss() } }.disabled(model.busy) } }
                .task { await model.load() }
                .confirmationDialog("NBT-Änderungen anwenden?", isPresented: $confirm, titleVisibility: .visible) { Button("Sichern & anwenden") { Task { await model.apply() } } } message: { Text("Die Originaldatei wird gesichert und danach ersetzt. Erfordert einen gestoppten Server.") }
                .confirmationDialog("Lokale Änderungen verwerfen?", isPresented: $discard, titleVisibility: .visible) { Button("Verwerfen", role: .destructive) { dismiss() } }
                .sheet(item: $share) { ShareSheet(url: $0.url) }
        }.interactiveDismissDisabled(model.dirty || model.busy)
    }
}
struct NBTBranch: View {
    @ObservedObject var model: NBTModel
    let nodeID: UUID
    let title: String
    @State private var search = ""
    @State private var limit = 100
    var node: NBTNode? { model.root?.find(nodeID) }
    var body: some View {
        List {
            if let node {
                let rows = Array(node.children.enumerated()).filter { search.isEmpty || $0.element.name.localizedCaseInsensitiveContains(search) }
                ForEach(Array(rows.prefix(limit)), id: \.element.id) { index, child in NBTEntry(model: model, node: child, title: child.name.isEmpty ? "[\(index)]" : child.name) }
                if limit < rows.count { Button("Weitere 100 Einträge") { limit += 100 } }
            }
        }.panelScreen().navigationTitle(title).navigationBarTitleDisplayMode(.inline).searchable(text: $search, prompt: "NBT-Feld suchen")
    }
}
struct NBTEntry: View {
    @ObservedObject var model: NBTModel
    let node: NBTNode
    let title: String
    @State private var edit = false
    var body: some View {
        Group {
            if node.container { NavigationLink { NBTBranch(model: model, nodeID: node.id, title: title) } label: { row } }
            else { Button { edit = true } label: { row }.buttonStyle(.plain).disabled(model.busy || node.scalar.count > 100000) }
        }.sheet(isPresented: $edit) {
            NBTScalarEditor(model: model, node: node, title: title)
        }
    }
    var row: some View {
        HStack {
            Image(systemName: node.container ? "folder" : "slider.horizontal.3").foregroundStyle(Color.panelAccent)
            VStack(alignment: .leading, spacing: 4) { Text(title).font(.headline); Text(node.container ? "\(node.typeName) · \(node.children.count) Einträge" : node.scalar.count > 4096 ? "\(node.typeName) · \(bytes(Double(node.scalar.count)))" : "\(node.typeName) · \(node.text)").font(.caption.monospaced()).foregroundStyle(.secondary).lineLimit(2) }
        }
    }
}
extension NBTNode {
    func find(_ id: UUID) -> NBTNode? { if self.id == id { return self }; for child in children { if let result = child.find(id) { return result } }; return nil }
}
@MainActor final class PlayerNameCache: ObservableObject {
    static let shared = PlayerNameCache()
    @Published private var values: [String: [String: String]] = [:]
    private var attempted: [String: Date] = [:]
    func key(_ api: PanelAPI, _ server: Server) -> String { api.base.absoluteString + "|" + server.id }
    func name(_ api: PanelAPI, _ server: Server, filename: String) -> String? { values[key(api, server)]?[filename.replacingOccurrences(of: ".dat", with: "").lowercased()] }
    func load(_ api: PanelAPI, _ server: Server) async {
        let k = key(api, server); if let date = attempted[k], Date().timeIntervalSince(date) < 60 { return }; attempted[k] = Date()
        guard let data = try? await api.fileData(server: server.id, path: "/usercache.json", limit: 4 * 1024 * 1024), let json = try? JSONDecoder().decode(JSON.self, from: data) else { return }
        var result: [String: String] = [:]
        for row in json.array { let id = row["uuid"].string.lowercased(), name = row["name"].string; if !id.isEmpty && !name.isEmpty { result[id] = name } }; values[k] = result
    }
}

struct NBTScalarEditor: View {
    @ObservedObject var model: NBTModel
    let node: NBTNode
    let title: String
    @Environment(\.dismiss) private var dismiss
    @State private var value: String
    @State private var error: String?
    init(model: NBTModel, node: NBTNode, title: String) { self.model = model; self.node = node; self.title = title; _value = State(initialValue: node.text) }
    var flag: Bool { node.type == 1 && ["Invulnerable", "OnGround", "FallFlying", "mayfly", "flying", "invulnerable", "instabuild", "mayBuild", "keepLevel", "seenCredits"].contains(node.name) }
    var body: some View {
        NavigationStack {
            Form {
                Section { LabeledContent("Datentyp", value: node.typeName); Text("Änderungen bleiben zunächst im lokalen Entwurf. Ganzzahlbreite und übrige NBT-Felder bleiben erhalten.").font(.footnote).foregroundStyle(.secondary) }
                Section("Wert") {
                    if flag { Toggle(title, isOn: Binding(get: { (Int(value) ?? 0) != 0 }, set: { value = $0 ? "1" : "0" })) }
                    else if node.name == "playerGameType" {
                        Picker("Spielmodus", selection: $value) { Text("Überleben").tag("0"); Text("Kreativ").tag("1"); Text("Abenteuer").tag("2"); Text("Zuschauer").tag("3"); if !["0", "1", "2", "3"].contains(value) { Text(value).tag(value) } }
                    } else if (1...6).contains(node.type) { TextField("Zahl", text: $value).keyboardType(.numbersAndPunctuation).font(.body.monospaced()).autocorrectionDisabled() }
                    else { TextEditor(text: $value).font(.body.monospaced()).frame(minHeight: 180).autocorrectionDisabled().textInputAutocapitalization(.never) }
                    if node.name == "Health" {
                        Slider(value: Binding(get: { Double(value) ?? 0 }, set: { value = String($0) }), in: 0...max(40, Double(value) ?? 20), step: 0.5)
                        Label("\(((Double(value) ?? 0) / 2).formatted(.number.precision(.fractionLength(2)))) Herzen", systemImage: "heart.fill").foregroundStyle(.red)
                    }
                }
                Section { Text("Dezimalzahlen mit Punkt eingeben. Arrays verwenden kommaseparierte Ganzzahlen. UUID, DataVersion und Inventardaten nur gezielt ändern.").font(.caption).foregroundStyle(.secondary) }
            }.panelScreen().navigationTitle(title).navigationBarTitleDisplayMode(.inline).errorAlert($error)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } }; ToolbarItem(placement: .confirmationAction) { Button("Übernehmen") { do { try model.update(node.id, value); dismiss() } catch { self.error = error.localizedDescription } } } }
        }
    }
}
