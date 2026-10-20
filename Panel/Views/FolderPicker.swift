import SwiftUI

struct MoveSelection: Identifiable { let id = UUID(); let names: [String]; let directories: Set<String> }
struct FolderPicker: View {
    let api: PanelAPI
    let server: Server
    let source: String
    let selection: MoveSelection
    let onMoved: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var destination = "/"
    @State private var folders: [String] = []
    @State private var names: Set<String> = []
    @State private var search = ""
    @State private var loading = false
    @State private var busy = false
    @State private var error: String?
    @State private var confirm = false
    var blocked: Bool { destination == source || selection.directories.contains { name in let path = pathJoin(source, name); return destination == path || destination.hasPrefix(path + "/") } }
    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text(selection.names.joined(separator: ", ")).font(.headline)
                    Label(destination, systemImage: "folder.fill").font(.footnote.monospaced()).foregroundStyle(.teal).textSelection(.enabled)
                    if destination != "/" { Button("Übergeordneter Ordner", systemImage: "arrow.up") { destination = parentFolder(destination) } }
                    if loading { ProgressView() }
                }
                Section("Zielordner") {
                    ForEach(folders.filter { search.isEmpty || $0.localizedCaseInsensitiveContains(search) }, id: \.self) { folder in
                        Button { destination = pathJoin(destination, folder) } label: { Label(folder, systemImage: "folder").frame(maxWidth: .infinity, alignment: .leading) }
                            .disabled(selection.directories.contains { pathJoin(destination, folder) == pathJoin(source, $0) })
                    }
                }
                Section { Button("Hierher verschieben", systemImage: "folder.badge.plus") { confirm = true }.disabled(blocked || loading || busy) }
            }.panelScreen().searchable(text: $search, prompt: "Unterordner suchen").navigationTitle("Ziel auswählen").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() }.disabled(busy) } }
                .task(id: destination) { await load() }.errorAlert($error)
                .confirmationDialog("Nach \(destination) verschieben?", isPresented: $confirm, titleVisibility: .visible) { Button("Verschieben") { Task { await move() } } } message: { Text(selection.names.joined(separator: ", ")) }
        }.interactiveDismissDisabled(busy)
    }
    func load() async { loading = true; defer { loading = false }; search = ""; do {
        let result = try await api.list("servers/\(server.id)/files/list", query: ["directory": destination]); try Task.checkCancellation()
        folders = result.filter { !$0["is_file"].bool }.map { $0["name"].string }.sorted { $0.localizedStandardCompare($1) == .orderedAscending }; names = Set(result.map { $0["name"].string })
    } catch is CancellationError {} catch { self.error = error.localizedDescription } }
    func move() async { busy = true; defer { busy = false }; do {
        guard !blocked else { throw PanelError(message: "Dieser Zielordner ist nicht möglich.") }
        // Re-read immediately before moving to avoid silently replacing existing files.
        let current = try await api.list("servers/\(server.id)/files/list", query: ["directory": destination])
        let collisions = Set(current.map { $0["name"].string }).intersection(selection.names)
        guard collisions.isEmpty else { throw PanelError(message: "Im Ziel existiert bereits: \(collisions.sorted().joined(separator: ", ")). Es wurde nichts verschoben.") }
        let moves = selection.names.map { JSON.object(["from": .string(pathJoin(source, $0).trimmingCharacters(in: CharacterSet(charactersIn: "/"))), "to": .string(pathJoin(destination, $0).trimmingCharacters(in: CharacterSet(charactersIn: "/")))]) }
        _ = try await api.request("servers/\(server.id)/files/rename", method: "PUT", body: .object(["root": .string("/"), "files": .array(moves)]))
        Feedback.success(); onMoved(); dismiss()
    } catch { self.error = error.localizedDescription } }
}
func parentFolder(_ path: String) -> String { let parent = (path as NSString).deletingLastPathComponent; return parent.isEmpty ? "/" : parent }
