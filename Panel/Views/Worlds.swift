import SwiftUI

struct WorldsView: View {
    let api: PanelAPI
    let server: Server
    @State private var folders: [JSON] = []
    @State private var root = "/"
    @State private var requestedRoot = "/"
    @State private var busy = false
    @State private var error: String?
    init(api: PanelAPI, server: Server, initialRoot: String = "/") {
        self.api = api; self.server = server; _root = State(initialValue: initialRoot); _requestedRoot = State(initialValue: initialRoot)
    }
    var body: some View {
        List {
            Section("Weltordner finden") {
                Text("Öffne den Ordner deiner Java-Welt, zum Beispiel world, world_nether oder world_the_end. Auch eigene Weltcontainer sind erreichbar. Der Scan zählt Dateigrößen; er verändert keine Welt.").font(.footnote).foregroundStyle(.secondary)
                TextField("Ordnerpfad", text: $requestedRoot).autocorrectionDisabled().textInputAutocapitalization(.never)
                Button("Ordner öffnen") { root = requestedRoot; Task { await load() } }.disabled(busy)
                NavigationLink { WorldDetailView(api: api, server: server, root: root) } label: { Label("Diesen Ordner analysieren", systemImage: "chart.pie") }
                NavigationLink { MinecraftPropertiesView(api: api, server: server) } label: { Label("Weltname & Minecraft-Einstellungen", systemImage: "slider.horizontal.3") }
            }
            Section(root) {
                if busy { ProgressView() }
                if root != "/" { Button("Übergeordneter Ordner", systemImage: "arrow.up") { root = (root as NSString).deletingLastPathComponent; if root.isEmpty { root = "/" }; requestedRoot = root; Task { await load() } } }
                ForEach(folders, id: \.["name"].string) { row in
                    let path = pathJoin(root, row["name"].string)
                    NavigationLink { WorldDetailView(api: api, server: server, root: path) } label: { Label(row["name"].string, systemImage: "globe") }
                }
            }
            if api.isKinetic {
                Section("Mod-Kompatibilität") { NavigationLink { ProviderCollection(api: api, server: server, title: "Gesperrte Mods", path: "minecraft/mods/blacklisted") } label: { Label("Vom Provider gesperrte Mods", systemImage: "shield.lefthalf.filled") } }
            }
        }.panelScreen().navigationTitle("Java-Welten").task { await load() }.refreshable { await load() }.errorAlert($error)
    }
    func load() async {
        busy = true; defer { busy = false }
        do {
            let normalized = root.hasPrefix("/") ? root : "/" + root
            guard !normalized.split(separator: "/").contains(".."), !normalized.contains("\\") else { throw PanelError(message: "Gültigen Weltordner eingeben.") }
            root = normalized; requestedRoot = root
            folders = try await api.list("servers/\(server.id)/files/list", query: ["directory": root]).filter { !$0["is_file"].bool && WorldStorage.safeName($0["name"].string) && !$0["is_symlink"].bool }
        } catch { self.error = error.localizedDescription }
    }
}
struct WorldDetailView: View {
    let api: PanelAPI
    let server: Server
    let root: String
    @State private var storage: WorldStorage?
    @State private var metadata: NBTNode?
    @State private var busy = false
    @State private var error: String?
    @State private var scanTask: Task<Void, Never>?
    var body: some View {
        List {
            Section(root) {
                Button("Weltdateien analysieren", systemImage: "magnifyingglass") { scanTask = Task { await scan() } }.disabled(busy)
                if busy { ProgressView("Ordner werden gelesen …"); Button("Scan abbrechen", role: .cancel) { scanTask?.cancel() } }
                Text("Maximal 200 Ordner / etwa 20.000 Dateien pro Scan. Live-Dateien können sich während der Messung ändern. Regionsdateien sind keine genaue Chunk-Anzahl.").font(.caption).foregroundStyle(.secondary)
            }
            if let storage {
                Section(storage.partial ? "Teilergebnis" : "Dateispeicher") {
                    LabeledContent("Gesamtgröße", value: bytes(storage.bytes))
                    LabeledContent("Dateien", value: String(storage.files))
                    LabeledContent("Gelesene Ordner", value: String(storage.directories))
                    LabeledContent("Regionsdateien (.mca)", value: String(storage.regions))
                    LabeledContent("Spielerdateien (.dat)", value: String(storage.playerFiles))
                }
            }
            if let metadata {
                Section("level.dat · nur gelesen") {
                    ForEach(["LevelName", "GameType", "Difficulty", "Time", "DayTime", "DataVersion"], id: \.self) { key in
                        if let node = metadata.child(key) { LabeledContent(key, value: node.text) }
                    }
                    if let version = metadata.child("Version")?.child("Name") { LabeledContent("Minecraft-Version", value: version.text) }
                }
            }
            Section("Verwalten") {
                NavigationLink { FilesView(api: api, server: server, root: root) } label: { Label("Weltdateien öffnen", systemImage: "folder") }
                NavigationLink { WorldsView(api: api, server: server, initialRoot: root) } label: { Label("Unterordner durchsuchen", systemImage: "folder.badge.plus") }
                NavigationLink { if api.amp == nil { BackupsView(api: api, server: server) } else { AMPBackupsView(api: api, server: server) } } label: { Label("Server-Backups", systemImage: "externaldrive") }
            }
        }.panelScreen().navigationTitle((root as NSString).lastPathComponent).errorAlert($error)
            .onDisappear { scanTask?.cancel() }
    }
    func scan() async {
        guard !busy else { return }; busy = true; storage = nil; metadata = nil; defer { busy = false }
        do {
            storage = try await WorldInventory.storage(api: api, server: server, root: root)
            try Task.checkCancellation()
            if let data = try? await api.fileData(server: server.id, path: pathJoin(root, "level.dat"), limit: 8 * 1024 * 1024) {
                let document = try await Task.detached { try NBTDocument.decode(data) }.value
                metadata = document.root.child("Data") ?? document.root
            }
        } catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }
}
