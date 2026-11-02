import SwiftUI

struct DashboardEditor: View {
    let scope: DashboardScope
    let layoutKey: String
    var api: PanelAPI? = nil
    var server: Server? = nil
    @EnvironmentObject private var workspace: WorkspaceStore
    @AppStorage("widgets.chartStyle") private var chartStyle = "area"
    @AppStorage("widgets.chartGrid") private var chartGrid = false
    @AppStorage("widgets.compactMetrics") private var compactMetrics = false
    @AppStorage("theme.compactServerCards") private var compactServerCards = true
    @ObservedObject private var layouts = DashboardLayouts.shared
    @Environment(\.dismiss) private var dismiss
    @State private var reset = false
    @AppStorage("theme.workspaceGrid") private var grid = false
    private var cards: [DashboardCard] { layouts.cards(layoutKey, scope: scope) }
    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text(scope == .home ? "Deine Startseite für dieses Panel. Die Serverliste bleibt immer erreichbar." : "Dein Workspace für diesen Server. Jede Änderung wird automatisch gespeichert.").font(.footnote).foregroundStyle(.secondary)
                }
                if scope == .server, let api, let server {
                    Section {
                        Toggle(isOn: Binding(get: { workspace.isMinecraft(api, server) }, set: { workspace.setMinecraft($0, api, server) })) { Label("Minecraft-Spielerübersicht", systemImage: "person.2.fill") }.accessibilityIdentifier("workspaceMinecraftToggle")
                    } header: { Text("Funktionen") } footer: { Text("Spieler, TPS, Servericon und Plugin-Schnellzugriffe. Standardmäßig an.") }
                }
                Section {
                    Picker("Diagrammstil", selection: $chartStyle) { Text("Fläche").tag("area"); Text("Linie").tag("line"); Text("Balken").tag("bars") }
                    Toggle("Hilfslinien in Diagrammen", isOn: $chartGrid)
                    Toggle("Kompakte Messwerte", isOn: $compactMetrics)
                    if scope == .home { Toggle("Kompakte Serverkarten", isOn: $compactServerCards) }
                } header: { Text("Widget-Einstellungen") } footer: { Text("Gilt für alle Startseiten und Server-Workspaces dieses Geräts.") }
                if scope == .server {
                    Section("Anordnung") {
                        Toggle("Kartenraster auf breiten Bildschirmen", isOn: $grid)
                        Text("Auf dem iPhone bleiben die Karten untereinander. Auf breiten Displays werden zwei Karten nebeneinander angeordnet.").font(.caption).foregroundStyle(.secondary)
                    }
                }
                Section("Deine Karten") {
                    ForEach(cards) { card in
                        HStack {
                            Label(card.title, systemImage: card.symbol)
                            Spacer()
                            if card == .servers { Image(systemName: "lock").font(.caption).foregroundStyle(.secondary).accessibilityLabel("Serverliste bleibt sichtbar") }
                            else { Button("Ausblenden", systemImage: "minus.circle", role: .destructive) { layouts.set(cards.filter { $0 != card }, key: layoutKey, scope: scope) }.labelStyle(.iconOnly).buttonStyle(.borderless).accessibilityIdentifier("hide-card-" + card.rawValue) }
                        }
                    }.onMove { source, destination in
                        var next = cards; next.move(fromOffsets: source, toOffset: destination); layouts.set(next, key: layoutKey, scope: scope)
                    }
                }
                Section("Karten hinzufügen") {
                    ForEach(DashboardCard.available(scope).filter { !cards.contains($0) }) { card in
                        Button { layouts.set(cards + [card], key: layoutKey, scope: scope) } label: { Label(card.title, systemImage: card.symbol) }.accessibilityIdentifier("add-card-" + card.rawValue)
                    }
                    if cards.count == DashboardCard.available(scope).count { Text("Alle Karten sind eingeblendet.").foregroundStyle(.secondary) }
                }
                Section {
                    Button("Standardlayout wiederherstellen", systemImage: "arrow.counterclockwise", role: .destructive) { reset = true }.accessibilityIdentifier("reset-dashboard")
                } footer: { Text("Nur dieses Layout wird zurückgesetzt. Zugänge, Shortcuts, Dateien und andere Layouts bleiben erhalten.") }
            }.environment(\.editMode, .constant(.active)).panelScreen().navigationTitle(scope == .home ? "Startseite anpassen" : "Workspace anpassen").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Fertig") { dismiss() }.accessibilityIdentifier("finish-dashboard") } }
                .alert("Standardlayout wiederherstellen?", isPresented: $reset) {
                    Button("Layout zurücksetzen", role: .destructive) { layouts.reset(layoutKey) }.accessibilityIdentifier("confirm-reset-dashboard")
                    Button("Abbrechen", role: .cancel) {}.accessibilityIdentifier("cancel-reset-dashboard")
                } message: { Text("Deine Anordnung für diese Übersicht wird verworfen.") }
        }
    }
}
struct ConsoleHistorySheet: View {
    let historyKey: String
    let choose: (String) -> Void
    @ObservedObject private var history = ConsoleHistory.shared
    @Environment(\.dismiss) private var dismiss
    @State private var clear = false
    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text("Die letzten 20 erfolgreich gesendeten Befehle dieses Servers. Antippen übernimmt einen Befehl zur Bearbeitung.").font(.footnote).foregroundStyle(.secondary)
                }
                Section("Zuletzt gesendet") {
                    ForEach(Array(history.entries(historyKey).enumerated()), id: \.offset) { _, entry in
                        Button { choose(entry); dismiss() } label: { Text(entry).font(.system(.body, design: .monospaced)).foregroundStyle(.primary) }
                    }
                    if history.entries(historyKey).isEmpty { ContentUnavailableView("Noch kein Befehlsverlauf", systemImage: "clock.arrow.circlepath") }
                }
            }.panelScreen().navigationTitle("Befehlsverlauf").navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) { Button("Fertig") { dismiss() } }
                    ToolbarItem(placement: .topBarLeading) { Button("Verlauf löschen", systemImage: "trash", role: .destructive) { clear = true }.disabled(history.entries(historyKey).isEmpty) }
                }
                .confirmationDialog("Befehlsverlauf löschen?", isPresented: $clear, titleVisibility: .visible) { Button("Verlauf löschen", role: .destructive) { history.clear(historyKey) } } message: { Text("Nur der lokale Verlauf dieses Servers wird entfernt.") }
        }
    }
}
struct ServerIconView: View {
    let api: PanelAPI?
    let server: Server
    var enabled = true
    var revision = 0
    var size: CGFloat = 48
    @State private var icon: UIImage?
    var body: some View {
        Group {
            if let icon { Image(uiImage: icon).resizable().interpolation(.none).scaledToFit().frame(width: size, height: size).clipShape(RoundedRectangle(cornerRadius: 12)).accessibilityLabel("Server-Icon") }
            else { Image(systemName: "cube.transparent.fill").font(.title).foregroundStyle(Color.panelAccent).frame(width: size, height: size).accessibilityHidden(true) }
        }.padding(size > 40 ? 4 : 3).background(.teal.opacity(0.10), in: RoundedRectangle(cornerRadius: size > 40 ? 16 : 11))
            .task(id: "\(api.map { String(describing: ObjectIdentifier($0)) } ?? "")|\(server.id)|\(enabled)|\(revision)") {
                guard enabled, let api else { icon = nil; return }
                let data = await api.serverIcons.load(api: api, server: server, force: revision > 0)
                guard !Task.isCancelled else { return }; icon = data.flatMap { UIImage(data: $0) }
            }
    }
}
