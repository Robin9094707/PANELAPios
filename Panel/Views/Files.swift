import SwiftUI
import UniformTypeIdentifiers

struct EditorItem: Identifiable { let id = UUID(); let path: String; var isNew = false }
struct FilesView: View {
    let api: PanelAPI
    let server: Server
    let root: String
    @State private var entries: [JSON] = []
    @State private var loading = false
    @State private var loaded = false
    @State private var error: String?
    @State private var search = ""
    @State private var selected = Set<String>()
    @State private var operation: Operation?
    @State private var editor: EditorItem?
    @State private var importer = false
    @State private var transfer = false
    @State private var share: ShareItem?
    var prefix: String { "servers/\(server.id)/files" }
    var visible: [JSON] { entries.filter { search.isEmpty || $0["name"].string.localizedCaseInsensitiveContains(search) }.sorted { a, b in
        if a["is_file"].bool != b["is_file"].bool { return !a["is_file"].bool }; return a["name"].string.localizedStandardCompare(b["name"].string) == .orderedAscending
    } }
    var body: some View {
        List(selection: $selected) {
            Section {
                HStack { Label(root, systemImage: "folder").font(.system(.caption, design: .monospaced)).textSelection(.enabled); Spacer(); Text("\(entries.count)").foregroundStyle(.secondary) }
                HStack { Image(systemName: "magnifyingglass").foregroundStyle(.secondary); TextField("In diesem Ordner suchen", text: $search).autocorrectionDisabled().textInputAutocapitalization(.never) }
                HStack { EditButton().buttonStyle(.borderless); Spacer(); fileTools.buttonStyle(.borderless) }
                if transfer { Label("Übertragung läuft …", systemImage: "arrow.up.arrow.down").foregroundStyle(.teal) }
            }
            if loading && !loaded { ProgressView("Dateien laden …") }
            if loaded && visible.isEmpty { ContentUnavailableView("Keine Dateien", systemImage: "folder", description: Text("Erstelle eine Datei oder lade Dateien hoch.")) }
            ForEach(visible, id: \.["name"].string) { entry in
                let name = entry["name"].string
                Group {
                    if entry["is_file"].bool {
                        Button { open(entry) } label: { fileRow(entry) }.buttonStyle(.plain)
                    } else {
                        NavigationLink { FilesView(api: api, server: server, root: pathJoin(root, name)) } label: { fileRow(entry) }
                    }
                }.tag(name).contextMenu { fileActions(entry) }
                    .swipeActions { Button("Löschen", role: .destructive) { delete([name]) } }
            }
            if !selected.isEmpty {
                Section("\(selected.count) ausgewählt") {
                    Button("Auswahl komprimieren", systemImage: "archivebox") { compress(Array(selected)) }
                    Button("Auswahl löschen", systemImage: "trash", role: .destructive) { delete(Array(selected)) }
                }
            }
        }.panelScreen().navigationTitle(root == "/" ? server.name : URL(fileURLWithPath: root).lastPathComponent)
            .refreshable { await load() }.task { await load() }
            .sheet(item: $operation) { OperationSheet(api: api, operation: $0) { selected = []; await load() } }
            .sheet(item: $editor) { FileEditor(api: api, server: server, item: $0) { Task { await load() } } }
            .sheet(item: $share) { ShareSheet(url: $0.url) }
            .fileImporter(isPresented: $importer, allowedContentTypes: [.data, .item], allowsMultipleSelection: true) { result in
                switch result { case .success(let urls): upload(urls); case .failure(let e): error = e.localizedDescription }
            }.errorAlert($error)
    }
    var fileTools: some View {
        Menu {
            Button("Hochladen", systemImage: "square.and.arrow.up") { importer = true }
            Button("Neue Datei", systemImage: "doc.badge.plus") { createFile() }
            Button("Neuer Ordner", systemImage: "folder.badge.plus") { operation = Operation(title: "Ordner erstellen", path: prefix + "/create-folder", fields: [Field(key: "name", label: "Ordnername")], fixed: ["root": .string(root)]) }
            Button("Von URL laden", systemImage: "link") { operation = Operation(title: "Datei von URL laden", detail: "Der Server lädt die Datei direkt von der angegebenen URL.", path: prefix + "/pull", fields: [Field(key: "url", label: "https://…")], fixed: ["directory": .string(root)]) }
            Button("Aktualisieren", systemImage: "arrow.clockwise") { Task { await load() } }
        } label: { Label("Aktionen", systemImage: "plus.circle") }.disabled(transfer)
    }
    func fileRow(_ entry: JSON) -> some View {
        HStack(spacing: 14) {
            Image(systemName: entry["is_file"].bool ? "doc.text" : "folder.fill").font(.title3).foregroundStyle(entry["is_file"].bool ? Color.secondary : Color.teal).frame(width: 28)
            VStack(alignment: .leading, spacing: 4) {
                Text(entry["name"].string).font(.body).lineLimit(2)
                HStack { Text(entry["is_file"].bool ? bytes(entry["size"].double) : "Ordner"); if !entry["modified_at"].string.isEmpty { Text(entry["modified_at"].string.prefix(16).replacingOccurrences(of: "T", with: " ")) } }.font(.caption).foregroundStyle(.secondary)
            }
        }.padding(.vertical, 4)
    }
    @ViewBuilder func fileActions(_ entry: JSON) -> some View {
        let name = entry["name"].string
        if entry["is_file"].bool {
            Button("Bearbeiten", systemImage: "pencil") { open(entry) }
            Button("Herunterladen", systemImage: "square.and.arrow.down") { download(entry) }
            Button("Kopieren", systemImage: "doc.on.doc") { operation = Operation(title: "Datei kopieren", path: prefix + "/copy", fixed: ["location": .string(pathJoin(root, name))], submit: "Kopieren") }
            Button("Entpacken", systemImage: "archivebox") { operation = Operation(title: "Archiv entpacken", path: prefix + "/decompress", fixed: ["root": .string(root), "file": .string(name)], submit: "Entpacken") }
        }
        Button("Umbenennen / verschieben", systemImage: "pencil.line") { rename(name) }
        Button("Komprimieren", systemImage: "archivebox.fill") { compress([name]) }
        Button("Dateirechte", systemImage: "lock.doc") { chmod(name) }
        Button("Löschen", systemImage: "trash", role: .destructive) { delete([name]) }
    }
    func load() async { loading = true; defer { loading = false }; do { entries = try await api.list(prefix + "/list", query: ["directory": root]); loaded = true } catch is CancellationError {} catch { self.error = error.localizedDescription } }
    func open(_ entry: JSON) {
        if entry["size"].double > 2 * 1024 * 1024 { error = "Der Texteditor öffnet Dateien bis 2 MiB. Größere Dateien kannst du herunterladen."; return }
        editor = EditorItem(path: pathJoin(root, entry["name"].string))
    }
    func createFile() { editor = EditorItem(path: root, isNew: true) }
    func delete(_ names: [String]) { operation = Operation(title: "Dateien löschen", detail: "\(names.joined(separator: ", "))\n\nDateien und enthaltene Ordner werden dauerhaft gelöscht.", path: prefix + "/delete", fixed: ["root": .string(root), "files": .array(names.map(JSON.string))], destructive: true, submit: "Endgültig löschen") }
    func compress(_ names: [String]) { operation = Operation(title: "Archiv erstellen", path: prefix + "/compress", fixed: ["root": .string(root), "files": .array(names.map(JSON.string))], submit: "Komprimieren") }
    func rename(_ name: String) {
        operation = Operation(title: "Umbenennen / verschieben", path: prefix + "/rename", method: "PUT", fields: [Field(key: "to", label: "Neuer Name oder Zielpfad", value: name, hint: "Relativ zum aktuellen Ordner. Ein absoluter Pfad beginnt mit /.")], transform: { values in
            let target = values["to"]?.string ?? ""
            guard !target.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { throw PanelError(message: "Bitte einen Namen eingeben.") }
            return .object(["root": .string(root), "files": .array([.object(["from": .string(name), "to": .string(target)])])])
        })
    }
    func chmod(_ name: String) {
        operation = Operation(title: "Dateirechte ändern", path: prefix + "/chmod", fields: [Field(key: "mode", label: "Unix-Rechte", value: "0644", hint: "Zum Beispiel 0644 für Dateien oder 0755 für Ordner.")], destructive: true, transform: { values in
            let mode = values["mode"]?.string ?? ""
            guard mode.range(of: "^[0-7]{3,4}$", options: .regularExpression) != nil else { throw PanelError(message: "Bitte drei oder vier Oktalziffern eingeben.") }
            return .object(["root": .string(root), "files": .array([.object(["file": .string(name), "mode": .string(mode)])])])
        })
    }
    func upload(_ urls: [URL]) {
        transfer = true
        Task { defer { transfer = false }; do {
            for url in urls {
                let access = url.startAccessingSecurityScopedResource(); defer { if access { url.stopAccessingSecurityScopedResource() } }
                try await api.upload(server: server.id, directory: root, file: url)
            }; await load()
        } catch { self.error = error.localizedDescription } }
    }
    func download(_ entry: JSON) {
        transfer = true
        Task { defer { transfer = false }; do {
            guard !api.demo else { throw PanelError(message: "Downloads sind im Demomodus deaktiviert.") }
            let result = try await api.request(prefix + "/download", query: ["file": pathJoin(root, entry["name"].string)])
            share = ShareItem(url: try await api.download(result.attributes["url"].string, filename: entry["name"].string))
        } catch { self.error = error.localizedDescription } }
    }
}
struct FileEditor: View {
    let api: PanelAPI
    let server: Server
    let item: EditorItem
    let onSave: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var text = ""
    @State private var original = ""
    @State private var name = ""
    @State private var loading = true
    @State private var failed = false
    @State private var saving = false
    @State private var error: String?
    @State private var discard = false
    var dirty: Bool { text != original || item.isNew }
    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                if item.isNew { TextField("Dateiname, z. B. config.yml", text: $name).textFieldStyle(.roundedBorder).padding().textInputAutocapitalization(.never).autocorrectionDisabled() }
                if loading { Spacer(); ProgressView("Inhalt laden …"); Spacer() }
                else if failed { ContentUnavailableView("Datei kann nicht geöffnet werden", systemImage: "doc.questionmark", description: Text("Lade sie herunter oder versuche es erneut.")) }
                else { TextEditor(text: $text).font(.system(size: 14, design: .monospaced)).scrollContentBackground(.hidden).autocorrectionDisabled().textInputAutocapitalization(.never).padding(8).accessibilityIdentifier("fileEditor") }
                HStack { Text("UTF-8"); Spacer(); Text("\(text.components(separatedBy: "\n").count) Zeilen · \(bytes(Double(text.utf8.count)))") }.font(.caption.monospaced()).foregroundStyle(.secondary).padding(12).background(.bar)
            }.panelScreen().navigationTitle(item.isNew ? "Neue Datei" : URL(fileURLWithPath: item.path).lastPathComponent).navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Schließen") { if dirty { discard = true } else { dismiss() } }.disabled(saving) }
                    ToolbarItem(placement: .confirmationAction) { Button { save() } label: { if saving { ProgressView() } else { Text("Speichern").bold() } }.disabled(loading || failed || saving || (item.isNew && name.isEmpty) || !dirty) }
                }.interactiveDismissDisabled(dirty || saving).errorAlert($error)
                .confirmationDialog("Änderungen verwerfen?", isPresented: $discard, titleVisibility: .visible) { Button("Verwerfen", role: .destructive) { dismiss() } }
                .task {
                    if item.isNew { loading = false; return }
                    do { text = try await api.fileText(server: server.id, path: item.path); original = text } catch { failed = true; self.error = error.localizedDescription }; loading = false
                }
        }
    }
    func save() {
        saving = true
        Task { do {
            guard !api.demo else { throw PanelError(message: "Der Demomodus speichert keine Dateien.") }
            if item.isNew {
                guard !name.contains("/"), ![".", ".."].contains(name), !name.trimmingCharacters(in: .whitespaces).isEmpty else { throw PanelError(message: "Bitte einen gültigen Dateinamen ohne Schrägstrich eingeben.") }
                let existing = try await api.list("servers/\(server.id)/files/list", query: ["directory": item.path])
                guard !existing.contains(where: { $0["name"].string == name }) else { throw PanelError(message: "Eine Datei mit diesem Namen existiert bereits. Öffne sie über Bearbeiten.") }
            }
            if !item.isNew {
                let current = try await api.fileText(server: server.id, path: item.path)
                guard current == original else { throw PanelError(message: "Die Datei wurde inzwischen auf dem Server geändert. Kopiere deine Änderungen und öffne die Datei erneut, um beide Versionen abzugleichen.") }
            }
            _ = try await api.raw("servers/\(server.id)/files/write", method: "POST", query: ["file": item.isNew ? pathJoin(item.path, name) : item.path], text: text)
            original = text; onSave(); dismiss()
        } catch { self.error = error.localizedDescription }; saving = false }
    }
}
