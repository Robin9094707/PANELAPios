import SwiftUI

/// Puzzle-piece sheet on the server start page: plugin update status, crossplay, quick links and command shortcuts.
struct PluginHubSheet: View {
    let api: PanelAPI
    let server: Server
    let suite: SuiteAPI?
    let runCommand: (String) -> Void
    let editLayout: () -> Void
    @EnvironmentObject private var workspace: WorkspaceStore
    @Environment(\.dismiss) private var dismiss
    @StateObject private var updates: AddonUpdateModel
    @State private var shortcuts = false
    @State private var checking = false
    @State private var error: String?
    init(api: PanelAPI, server: Server, suite: SuiteAPI?, runCommand: @escaping (String) -> Void, editLayout: @escaping () -> Void) {
        self.api = api; self.server = server; self.suite = suite; self.runCommand = runCommand; self.editLayout = editLayout
        _updates = StateObject(wrappedValue: AddonUpdateModel(api: api, server: server, kind: .plugin))
    }
    struct Available: Identifiable { let item: AddonInventory; let release: AddonRelease; var id: String { item.filename } }
    var available: [Available] { updates.rows.compactMap { row in updates.cache.statuses[row.filename]?.release.map { Available(item: row, release: $0) } } }
    var body: some View {
        NavigationStack {
            List {
                Section {
                    NavigationLink { PluginsView(api: api, server: server) } label: { Label("Plugins & Updates", systemImage: "puzzlepiece.extension.fill") }.accessibilityIdentifier("pluginHubPlugins")
                    NavigationLink { ModsView(api: api, server: server) } label: { Label("Mods & Updates", systemImage: "shippingbox.fill") }
                    NavigationLink { PluginInstallView(api: api, server: server) } label: { Label("Neues Plugin installieren", systemImage: "plus.circle") }
                } header: { Text("Plugins") }
                Section {
                    if updates.busy || checking { ProgressView(updates.progress.isEmpty ? "Updates prüfen …" : updates.progress).font(.caption) }
                    if available.isEmpty && !updates.busy { Label(updates.rows.isEmpty ? "Noch nicht geprüft" : "Alle zugeordneten Plugins sind aktuell", systemImage: updates.rows.isEmpty ? "questionmark.circle" : "checkmark.seal.fill").foregroundStyle(updates.rows.isEmpty ? Color.secondary : .green) }
                    ForEach(available) { entry in
                        let item = entry.item, release = entry.release
                        NavigationLink { PluginsView(api: api, server: server) } label: {
                            HStack { VStack(alignment: .leading, spacing: 2) { Text(item.name).font(.subheadline.weight(.semibold)); Text(item.version + " → " + release.version).font(.caption.monospacedDigit()).foregroundStyle(.secondary) }; Spacer(); Text(release.provider.title).font(.caption2).foregroundStyle(.secondary) }
                        }
                    }
                    let unmapped = updates.rows.filter { updates.cache.links[$0.filename] == nil }.count
                    if unmapped > 0 { Label("\(unmapped) Plugin(s) ohne Quelle – im Update-Center zuordnen", systemImage: "link.badge.plus").font(.caption).foregroundStyle(.secondary) }
                    Button("Jetzt auf Updates prüfen", systemImage: "arrow.clockwise") { Task { await check(force: true) } }.disabled(updates.busy || checking || api.demo)
                } header: { HStack { Text("Update-Status"); Spacer(); if !available.isEmpty { Text("\(available.count) verfügbar").foregroundStyle(Color.panelAccent) } } }
                if let suite, suite.features.contains("crossplay-v1") {
                    Section("Crossplay") { SuiteCrossplayCard(suite: suite, compact: true).listRowInsets(EdgeInsets()).listRowBackground(Color.clear) }
                }
                Section {
                    ForEach(workspace.shortcuts[workspace.key(api, server)] ?? []) { shortcut in
                        Button { runCommand(shortcut.command); dismiss() } label: { VStack(alignment: .leading, spacing: 2) { Text(shortcut.name); Text(shortcut.command).font(.caption.monospaced()).foregroundStyle(.secondary) } }
                            .swipeActions { Button("Löschen", role: .destructive) { workspace.removeShortcut(shortcut.id, api, server) } }
                    }
                    Button("Shortcuts verwalten", systemImage: "bolt.badge.clock") { shortcuts = true }
                    NavigationLink { FilesView(api: api, server: server, root: "/plugins") } label: { Label("Plugin-Ordner", systemImage: "folder") }
                    NavigationLink { MinecraftPropertiesView(api: api, server: server) } label: { Label("Minecraft-Einstellungen", systemImage: "slider.horizontal.3") }
                    Button("Startseite anpassen", systemImage: "rectangle.3.group") { dismiss(); Task { try? await Task.sleep(for: .milliseconds(450)); editLayout() } }
                } header: { Text("Schnellzugriffe") } footer: { Text("Shortcuts öffnen den Befehl in der Konsole; gesendet wird erst nach deiner Bestätigung. Alles wird pro Server gespeichert.") }
            }.panelScreen().navigationTitle("Plugins & Schnellzugriffe").navigationBarTitleDisplayMode(.inline).errorAlert($error)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Fertig") { dismiss() } } }
                .task { await check(force: false) }
                .sheet(isPresented: $shortcuts) { CommandLibrary(api: api, server: server) { command in runCommand(command); dismiss() } }
        }
    }
    func check(force: Bool) async {
        guard !api.demo else { return }
        checking = true; defer { checking = false }
        do {
            let entries = try await api.list("servers/\(server.id)/files/list", query: ["directory": "/plugins"])
            await updates.scan(api: api, server: server, entries: entries, force: force)
            if let message = updates.error { error = message; updates.error = nil }
        } catch is CancellationError {} catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }
}
