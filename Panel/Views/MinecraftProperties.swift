import SwiftUI

/// Native `server.properties` editor (like the UltraServers "Config Editor"): toggles for booleans, pickers for
/// known choices, number fields for integers. Works on every panel with file access; untouched lines stay verbatim.
struct MinecraftPropertiesView: View {
    let api: PanelAPI
    let server: Server
    @State private var file = ServerProperties(lines: [])
    @State private var edits: [String: String] = [:]
    @State private var error: String?
    @State private var busy = false
    @State private var loaded = false
    @State private var missing = false
    @State private var search = ""
    @State private var editor: EditorItem?
    @State private var confirm = false
    @State private var saved = false
    @State private var reloadConfirm = false
    static let path = "/server.properties"
    var original: [String: String] { file.values }
    var keys: [String] {
        let all = file.keys
        let filtered = all.filter { search.isEmpty || $0.localizedCaseInsensitiveContains(search) || (ServerProperties.help[$0] ?? "").localizedCaseInsensitiveContains(search) }
        let common = ServerProperties.help.keys
        return filtered.sorted { (common.contains($0) ? 0 : 1, $0) < (common.contains($1) ? 0 : 1, $1) }
    }
    var changes: [String: String] { edits.filter { original[$0.key] != $0.value } }
    var dirty: Bool { !changes.isEmpty }
    var body: some View {
        List {
            Section {
                Label("Minecraft & Welt", systemImage: "globe.europe.africa.fill").font(.title2.bold()).foregroundStyle(Color.panelAccent)
                Text("Server-Eigenschaften wie Schwierigkeit, Spielerlimit und Weltname. Änderungen greifen nach einem Neustart. Kommentare und nicht geänderte Zeilen bleiben unverändert. Ein neuer Weltname oder Seed konvertiert keine bestehende Welt.").font(.footnote).foregroundStyle(.secondary)
                Button("server.properties als Text öffnen", systemImage: "doc.text") { editor = EditorItem(path: Self.path) }.requiresAccess(api, server.id, "file.read-content")
                NavigationLink("Weltordner & Dateien") { FilesView(api: api, server: server, root: "/") }
                if saved { Label("Gespeichert · Server neu starten, damit die Änderungen greifen", systemImage: "checkmark.circle").foregroundStyle(Color.panelAccent).font(.footnote) }
            }
            if busy && !loaded { ProgressView("Eigenschaften laden …") }
            if missing { Section { Label("Keine server.properties gefunden. Starte den Server einmal, damit Minecraft die Datei anlegt.", systemImage: "questionmark.folder").foregroundStyle(.secondary) } }
            if loaded && !missing {
                Section {
                    TextField("Eigenschaft suchen", text: $search).autocorrectionDisabled().textInputAutocapitalization(.never)
                    ForEach(keys, id: \.self) { key in property(key) }
                } header: { Text("\(file.keys.count) Eigenschaften") }
                if dirty {
                    Section {
                        Button("\(changes.count) Änderung\(changes.count == 1 ? "" : "en") speichern", systemImage: "checkmark") { confirm = true }.disabled(busy).requiresAccess(api, server.id, "file.update")
                        Button("Änderungen verwerfen", systemImage: "arrow.uturn.backward", role: .destructive) { edits = [:] }
                    }
                }
            }
        }.panelScreen().navigationTitle("Minecraft-Einstellungen").navigationBarTitleDisplayMode(.inline).task { await load() }
            .refreshable { if dirty { reloadConfirm = true } else { await load() } }
            .confirmationDialog("Änderungen verwerfen und neu laden?", isPresented: $reloadConfirm, titleVisibility: .visible) { Button("Verwerfen & neu laden", role: .destructive) { Task { await load() } } }.errorAlert($error)
            .sheet(item: $editor) { FileEditor(api: api, server: server, item: $0) { Task { await load() } } }
            .confirmationDialog("Minecraft-Einstellungen speichern?", isPresented: $confirm, titleVisibility: .visible) { Button("Speichern") { Task { await save() } } } message: { Text(changes.keys.sorted().map { "\($0): \(original[$0] ?? "–") → \(changes[$0] ?? "")" }.joined(separator: "\n")) }
    }
    @ViewBuilder func property(_ key: String) -> some View {
        let value = original[key] ?? ""
        let binding = Binding<String>(get: { edits[key] ?? value }, set: { edits[key] = $0 })
        let changed = changes[key] != nil
        VStack(alignment: .leading, spacing: 4) {
            switch ServerProperties.kind(key, value: value) {
            case .bool: Toggle(key, isOn: Binding(get: { binding.wrappedValue == "true" }, set: { binding.wrappedValue = $0 ? "true" : "false" })).font(.body.monospaced())
            case .choice(let options): Picker(key, selection: binding) { ForEach(Array(Set(options + [binding.wrappedValue])).sorted(), id: \.self) { Text($0).tag($0) } }.font(.body.monospaced())
            case .integer:
                HStack { Text(key).font(.body.monospaced()); Spacer(); TextField(key, text: binding).keyboardType(.numbersAndPunctuation).multilineTextAlignment(.trailing).frame(maxWidth: 120).monospacedDigit() }
            case .text:
                Text(key).font(.caption.monospaced()).foregroundStyle(.secondary)
                TextField(key, text: binding, axis: .vertical).font(.system(.body, design: .monospaced)).autocorrectionDisabled().textInputAutocapitalization(.never)
            }
            if let help = ServerProperties.help[key] { Text(help).font(.caption2).foregroundStyle(.secondary) }
        }.listRowBackground(changed ? Color.panelAccent.opacity(0.12) : nil)
    }
    func load() async {
        busy = true; defer { busy = false; loaded = true }
        do {
            let text = try await api.fileText(server: server.id, path: Self.path)
            file = ServerProperties.parse(text); edits = [:]; missing = false
        } catch {
            if error.localizedDescription.contains("404") { missing = true } else { self.error = error.localizedDescription }
        }
    }
    func save() async {
        busy = true; defer { busy = false }
        do {
            guard !api.demo else { throw PanelError(message: "Der Demomodus speichert keine Einstellungen.") }
            let pending = changes
            for (key, value) in pending {
                if case .integer = ServerProperties.kind(key, value: original[key] ?? ""), Int(value) == nil { throw PanelError(message: "\(key): Bitte eine ganze Zahl eingeben.") }
            }
            // Re-read right before writing: a running server or another admin may have changed the file meanwhile.
            let current = ServerProperties.parse(try await api.fileText(server: server.id, path: Self.path))
            for key in pending.keys where current.values[key] != original[key] { throw PanelError(message: "\(key) wurde inzwischen geändert. Lade die Ansicht neu, bevor du speicherst.") }
            _ = try await api.raw("servers/\(server.id)/files/write", method: "POST", query: ["file": Self.path], text: current.text(applying: pending))
            await load(); saved = true
        } catch { self.error = error.localizedDescription }
    }
}
