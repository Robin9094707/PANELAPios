import SwiftUI

struct MinecraftPropertiesView: View {
    let api: PanelAPI
    let server: Server
    @State private var original: [String: JSON] = [:]
    @State private var edits: [String: String] = [:]
    @State private var error: String?
    @State private var busy = false
    @State private var loaded = false
    @State private var search = ""
    @State private var editor: EditorItem?
    @State private var confirm = false
    @State private var saved = false
    @State private var reloadConfirm = false
    var keys: [String] { original.keys.filter { search.isEmpty || $0.localizedCaseInsensitiveContains(search) }.sorted() }
    var dirty: Bool { original.contains { (edits[$0.key] ?? $0.value.string) != $0.value.string } }
    var body: some View {
        List {
            Section {
                Label("Minecraft & Welt", systemImage: "globe.europe.africa.fill").font(.title2.bold()).foregroundStyle(Color.panelAccent)
                Text("Server-Eigenschaften wie Schwierigkeit, Spielerlimit und Weltname. Änderungen greifen meist nach einem Neustart. Änderungen an Weltname oder Seed erzeugen keine Konvertierung bestehender Welten.").font(.footnote).foregroundStyle(.secondary)
                Button("server.properties öffnen", systemImage: "doc.text") { editor = EditorItem(path: "/server.properties") }.requiresAccess(api, server.id, "file.read-content")
                NavigationLink("Weltordner & Dateien") { FilesView(api: api, server: server, root: "/") }
                if saved { Label("Gespeichert · bei Bedarf Server neu starten", systemImage: "checkmark.circle").foregroundStyle(Color.panelAccent).font(.footnote) }
            }
            if api.isKinetic || api.demo {
                if busy && !loaded { ProgressView("Eigenschaften laden …") }
                Section {
                    TextField("Eigenschaft suchen", text: $search).autocorrectionDisabled().textInputAutocapitalization(.never)
                    ForEach(keys, id: \.self) { key in property(key) }
                }
                if loaded && original.isEmpty { Text("Der Provider liefert keine bearbeitbaren Eigenschaften. Verwende server.properties.").foregroundStyle(.secondary) }
                if dirty { Section { Button("Änderungen speichern", systemImage: "checkmark") { confirm = true }.disabled(busy).requiresAccess(api, server.id, "file.update") } }
            } else { Section { Text("Bei diesem Panel werden Minecraft-Einstellungen direkt in server.properties bearbeitet.").foregroundStyle(.secondary) } }
        }.panelScreen().navigationTitle("Minecraft-Einstellungen").navigationBarTitleDisplayMode(.inline).task { if api.isKinetic || api.demo { await load() } }
            .refreshable { if dirty { reloadConfirm = true } else if api.isKinetic || api.demo { await load() } }
            .confirmationDialog("Änderungen verwerfen und neu laden?", isPresented: $reloadConfirm, titleVisibility: .visible) { Button("Verwerfen & neu laden", role: .destructive) { Task { await load() } } }.errorAlert($error)
            .sheet(item: $editor) { FileEditor(api: api, server: server, item: $0) { if api.isKinetic { Task { await load() } } } }
            .confirmationDialog("Minecraft-Einstellungen speichern?", isPresented: $confirm, titleVisibility: .visible) { Button("Speichern") { Task { await save() } } } message: { Text("Nur geänderte Werte werden übertragen. Ein Neustart kann nötig sein.") }
    }
    @ViewBuilder func property(_ key: String) -> some View {
        let binding = Binding<String>(get: { edits[key] ?? original[key]?.string ?? "" }, set: { edits[key] = $0 })
        if ["true", "false"].contains(original[key]?.string ?? "") { Toggle(key, isOn: Binding(get: { binding.wrappedValue == "true" }, set: { binding.wrappedValue = $0 ? "true" : "false" })) }
        else if key == "difficulty" || key == "gamemode" {
            let options = key == "difficulty" ? ["peaceful", "easy", "normal", "hard"] : ["survival", "creative", "adventure", "spectator"]
            Picker(key, selection: binding) { ForEach(Array(Set(options + [binding.wrappedValue])).sorted(), id: \.self) { Text($0).tag($0) } }
        } else { VStack(alignment: .leading, spacing: 6) { Text(key).font(.caption).foregroundStyle(.secondary); TextField(key, text: binding, axis: .vertical).font(.system(.body, design: .monospaced)).autocorrectionDisabled().textInputAutocapitalization(.never) } }
    }
    static func decode(_ input: JSON) -> [String: JSON] {
        var value = input.attributes
        if value["data"] != .null { value = value["data"].attributes }
        if value["properties"] != .null { value = value["properties"] }
        if case .array = value {
            var result: [String: JSON] = [:]
            for row in value.array { let key = row["key"].string.isEmpty ? row["name"].string : row["key"].string; if !key.isEmpty && row["value"] != .null { result[key] = row["value"] } }
            return result
        }
        return value.object.filter { _, v in switch v { case .string, .number, .bool: return true; default: return false } }
    }
    func load() async {
        busy = true; defer { busy = false; loaded = true }
        do { original = Self.decode(try await api.request("servers/\(server.id)/minecraft/properties")); edits = [:] } catch { self.error = error.localizedDescription }
    }
    func save() async {
        busy = true; defer { busy = false }
        do {
            guard !api.demo else { throw PanelError(message: "Der Demomodus speichert keine Einstellungen.") }
            let current = Self.decode(try await api.request("servers/\(server.id)/minecraft/properties"))
            var values: [String: JSON] = [:]
            for (key, old) in original {
                let text = edits[key] ?? old.string
                guard text != old.string else { continue }
                guard current[key] == old else { throw PanelError(message: "\(key) wurde inzwischen geändert. Öffne die Ansicht erneut, bevor du speicherst.") }
                switch old {
                case .bool: values[key] = .bool(text == "true")
                case .number: guard let n = Double(text), n.isFinite else { throw PanelError(message: "\(key): gültige Zahl eingeben.") }; values[key] = .number(n)
                default: values[key] = .string(text)
                }
            }
            _ = try await api.request("servers/\(server.id)/minecraft/properties", method: "PUT", body: .object(values))
            await load(); saved = true
        } catch { self.error = error.localizedDescription }
    }
}
