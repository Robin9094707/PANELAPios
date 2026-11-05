import SwiftUI
import AppIntents

/// Same glass cards and colours as the existing Admin centre, with a complete searchable directory.
struct SuiteToolDirectoryView: View {
    let suite: SuiteAPI
    var actions: [SuiteAction] = []
    var panel: PanelAPI? = nil
    var server: Server? = nil
    var live: LiveServer? = nil
    var query = ""
    var embedded = false
    @State private var search = ""
    @AppStorage("suite.tools.favorites.v1") private var favorites = "advertising,players,tickets,diagnostics,adminLog,histories"
    var selectedQuery: String { search.isEmpty ? query : search }
    var tools: [SuiteTool] { SuiteTool.allCases.filter { $0 != .overview && $0.matches(selectedQuery) && ($0.feature.isEmpty || suite.features.contains($0.feature)) } }
    func allowed(_ tool: SuiteTool) -> Bool { tool.permission.isEmpty || suite.can(tool.permission) }
    var groups: [String] { var result: [String] = []; for tool in tools where !result.contains(tool.group) { result.append(tool.group) }; return result }
    var body: some View {
        if embedded { directoryContent } else {
            ScrollView { directoryContent }.panelScreen().navigationTitle("Admin-Werkzeuge")
                .searchable(text: $search, prompt: "Alle Werkzeuge, Einstellungen & Funktionen")
                .accessibilityIdentifier("suiteToolDirectory")
        }
    }
    var directoryContent: some View {
            LazyVStack(alignment: .leading, spacing: 18) {
                if selectedQuery.isEmpty {
                    VStack(alignment: .leading, spacing: 10) {
                        Label("Admin Suite", systemImage: "shield.lefthalf.filled").font(.title2.bold())
                        Text(suite.connection.endpoint).font(.caption).foregroundStyle(.secondary)
                        Text("Schnellzugriffe lange drücken, um Favoriten anzupassen.").font(.caption).foregroundStyle(.secondary)
                    }.frame(maxWidth: .infinity, alignment: .leading).card()
                    toolGroup("Schnellzugriffe", tools: tools.filter { favorites.split(separator: ",").contains(Substring($0.rawValue)) && allowed($0) })
                }
                ForEach(groups, id: \.self) { group in toolGroup(group, tools: tools.filter { $0.group == group }) }
                if !selectedQuery.isEmpty {
                    let matches = actions.filter { SuiteSearch.matches(selectedQuery, in: $0.title + " " + $0.id + " " + $0.group) }
                    if !matches.isEmpty {
                        VStack(alignment: .leading, spacing: 12) {
                            Label("Weitere Funktionen", systemImage: "magnifyingglass").font(.headline)
                            ForEach(matches) { action in NavigationLink(action.title) { SuiteActionView(suite: suite, action: action) }.disabled(action.data["allowed"] == .bool(false)) }
                        }.frame(maxWidth: .infinity, alignment: .leading).card()
                    }
                    if tools.isEmpty && matches.isEmpty { ContentUnavailableView.search(text: selectedQuery) }
                }
                NavigationLink { SuiteShortcutHelpView() } label: { Label("Kurzbefehle & Home-Bildschirm", systemImage: "square.stack.3d.up.fill").frame(maxWidth: .infinity, alignment: .leading).card() }.buttonStyle(.plain)
            }.padding(embedded ? 0 : 20).frame(maxWidth: 1000).frame(maxWidth: .infinity)
    }
    @ViewBuilder func toolGroup(_ title: String, tools: [SuiteTool]) -> some View {
        if !tools.isEmpty {
            VStack(alignment: .leading, spacing: 12) {
                Text(title).font(.headline)
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 140), spacing: 10)], spacing: 10) {
                    ForEach(tools) { tool in
                        SuiteToolTile(title: tool.title, symbol: tool.symbol, tint: tool == .advertising ? .orange : Color.panelAccent, identifier: "suiteTool-" + tool.id) {
                            SuiteToolDestination(suite: suite, tool: tool, actions: actions, panel: panel, server: server, live: live)
                        }.disabled(!allowed(tool)).contextMenu {
                            Button(favorites.split(separator: ",").contains(Substring(tool.rawValue)) ? "Favorit entfernen" : "Als Favorit merken", systemImage: "star") { toggle(tool) }
                            Button("Kurzbefehle-Link kopieren", systemImage: "link") { Clipboard.copy(SuiteLaunchRequest(tool: tool, connection: suite.connection.id).url.absoluteString) }
                        }
                    }
                }
            }.frame(maxWidth: .infinity, alignment: .leading).card()
        }
    }
    func toggle(_ tool: SuiteTool) {
        var values = favorites.split(separator: ",").map(String.init)
        if values.contains(tool.rawValue) { values.removeAll { $0 == tool.rawValue } } else { values.append(tool.rawValue) }
        favorites = values.joined(separator: ",")
    }
}

struct SuiteToolDestination: View {
    let suite: SuiteAPI
    let tool: SuiteTool
    var actions: [SuiteAction] = []
    var panel: PanelAPI? = nil
    var server: Server? = nil
    var live: LiveServer? = nil
    var place: String? = nil
    var prepareAd = false
    @ViewBuilder var body: some View {
        if !tool.permission.isEmpty && !suite.can(tool.permission) {
            ContentUnavailableView("Keine Berechtigung", systemImage: "lock.fill", description: Text(tool.title + " · " + tool.permission))
        } else if !tool.feature.isEmpty && !suite.features.contains(tool.feature) {
            ContentUnavailableView("Suite aktualisieren", systemImage: "arrow.down.circle", description: Text("Diese Funktion ist auf dem verbundenen Server noch nicht verfügbar."))
        } else {
            switch tool {
            case .overview: SuiteToolDirectoryView(suite: suite, actions: actions, panel: panel, server: server)
            case .advertising: SuiteAdHubView(suite: suite, initialPlace: place, prepareOnOpen: prepareAd)
            case .players:
                if let panel, let server { SuitePlayersView(suite: suite, panel: panel, server: server, actions: actions) }
                else { SuiteStandalonePlayersView(suite: suite) }
            case .tickets: SuiteTicketsView(suite: suite, panel: panel, server: server)
            case .events: SuiteDirectEvents(suite: suite)
            case .adminLog: SuiteAdminLogView(suite: suite, panel: panel, server: server)
            case .histories: SuiteHistoriesView(suite: suite)
            case .staffChat: SuiteStaffChatView(suite: suite)
            case .tasks: SuiteStaffTasksView(suite: suite)
            case .diagnostics: SuiteDiagnosticsView(suite: suite)
            case .lagMap: SuiteLagMapView(suite: suite)
            case .spark: SuiteSparkView(suite: suite)
            case .crashes: SuiteCrashesView(suite: suite)
            case .console: SuiteDiagnosticsConsoleView(suite: suite)
            case .operations: SuiteOperationsView(suite: suite, actions: actions)
            case .worldTrim: SuiteWorldTrimView(suite: suite)
            case .discordStatus: SuiteDiscordStatusDesignerView(suite: suite)
            case .scoreboard: SuiteScoreboardDesignerView(suite: suite)
            case .discord: SuiteDiscordView(suite: suite)
            case .luckperms: SuiteLuckPermsView(suite: suite)
            case .lands: SuiteLandsView(suite: suite)
            case .locks: SuiteAccessLocksView(suite: suite)
            case .commands: SuiteCommandsView(suite: suite)
            case .management: SuiteManagementView(suite: suite, actions: actions)
            case .team: SuiteAdminDirectory(suite: suite)
            case .appSettings: SuiteAppSettingsView(suite: suite)
            case .settings: SuiteSettingsView(suite: suite)
            case .access: SuiteUpgradeView(suite: suite)
            case .account: SuiteAccountView(suite: suite)
            case .bark: SuiteBarkSettingsView(suite: suite)
            case .quiet: SuiteQuietHoursView(suite: suite)
            case .memory: SuiteMemoryGuardView(suite: suite)
            case .links: SuiteLinksView(suite: suite)
            case .crossplay: SuiteCrossplayView(suite: suite)
            case .functions: SuiteActionsView(suite: suite, actions: actions.filter { !["menu.action", "settings.update"].contains($0.id) }, title: "Alle Funktionen")
            case .reports, .bans, .vpn:
                if let panel, let server {
                    SuiteSecurityCenterView(suite: suite, panel: panel, server: server, actions: actions, area: tool == .reports ? .reports : tool == .bans ? .bans : .vpn)
                } else {
                    SuiteStandaloneRecordsView(suite: suite, kind: tool == .vpn ? "vpn-whitelist" : tool.rawValue, title: tool.title, actions: actions)
                }
            case .alts:
                if let panel, let server { SuiteAltDashboardView(suite: suite, panel: panel, server: server, actions: actions) }
                else { SuiteStandaloneRecordsView(suite: suite, kind: "alt-dashboard", title: tool.title, actions: actions) }
            case .ultrabans: SuiteUltraView(suite: suite, actions: actions)
            case .statistics:
                if let panel, let server, let live { SuiteStatisticsDashboard(suite: suite, panel: panel, server: server, actions: actions, live: live) }
                else { SuiteOperationsView(suite: suite, actions: actions) }
            case .menu: SuiteRemoteMenuView(suite: suite)
            case .ticketSettings: SuiteTicketSettingsView(suite: suite)
            case .online: SuiteOnlineSettingsView(suite: suite)
            case .dependencies: SuiteDependencyView(suite: suite)
            }
        }
    }
}

/// Read and act on server-owned records without requiring a hosting-panel connection.
struct SuiteStandaloneRecordsView: View {
    let suite: SuiteAPI
    let kind: String
    let title: String
    let actions: [SuiteAction]
    @State private var query = ""
    @State private var rows: [JSON] = []
    @State private var error: String?
    @State private var next = -1
    var body: some View {
        List {
            ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                NavigationLink {
                    List {
                        ForEach([("name", "Name"), ("reason", "Grund"), ("status", "Status"), ("category", "Kategorie"), ("body", "Nachricht"), ("note", "Notiz"), ("source", "Quelle"), ("id", "Kennung")], id: \.0) { key, label in
                            if !row[key].string.isEmpty { LabeledContent(label, value: row[key].string).textSelection(.enabled) }
                        }
                        ForEach(actions.filter { $0.data["allowed"] != .bool(false) && ($0.id.hasPrefix(kind == "reports" ? "reports." : kind == "bans" ? "bans." : kind == "vpn-whitelist" ? "vpn." : "alts.")) }) { action in
                            NavigationLink(action.title) { SuiteActionView(suite: suite, action: action, preset: ["id": row["id"], "player": row["player"] == .null ? row["id"] : row["player"]]) }
                        }
                    }.panelScreen().navigationTitle(title)
                } label: { VStack(alignment: .leading, spacing: 4) { Text(row["name"].string.isEmpty ? SuiteHistoryText.title(row) : row["name"].string).font(.headline); Text(row["reason"].string.isEmpty ? SuiteHistoryText.detail(row) : row["reason"].string).font(.caption).foregroundStyle(.secondary) } }
            }
            if next >= 0 { Button("Weitere Einträge laden") { Task { await load(more: true) } } }
            if rows.isEmpty { ContentUnavailableView("Keine Einträge", systemImage: "tray") }
        }.panelScreen().navigationTitle(title).searchable(text: $query, prompt: "Einträge durchsuchen")
            .task(id: query) { do { try await Task.sleep(for: .milliseconds(250)); await load() } catch { } }.refreshable { await load() }.errorAlert($error)
    }
    func load(more: Bool = false) async {
        do {
            let data = try await suite.data(kind, query: ["q": query, "offset": String(more ? max(0, next) : 0)])
            guard !Task.isCancelled else { return }
            rows = more ? rows + data["items"].array : data["items"].array
            next = data["next"] == .null ? -1 : data["next"].int
        } catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }
}

/// Launching a named shortcut never silently picks a different server when its connection was removed.
struct SuiteWorkspaceLauncher: View {
    let request: SuiteLaunchRequest
    @ObservedObject private var connections = SuiteConnections.shared
    @AppStorage("suite.preferredConnection.v1") private var preferred = ""
    @Environment(\.dismiss) private var dismiss
    var selected: SuiteConnection? {
        if let id = request.connection { return connections.values.first { $0.id == id } }
        if let value = connections.values.first(where: { $0.id.uuidString == preferred }) { return value }
        return connections.values.count == 1 ? connections.values.first : nil
    }
    var body: some View {
        NavigationStack {
            Group {
                if let record = selected { SuiteWorkspaceConnectionView(connection: record, request: request) }
                else if request.connection != nil { ContentUnavailableView("Zugang nicht mehr vorhanden", systemImage: "key.slash", description: Text("Bitte den Kurzbefehl mit einem aktuellen Zugang neu einrichten.")) }
                else {
                    List {
                        if connections.values.isEmpty { ContentUnavailableView("Keine Admin Suite verbunden", systemImage: "shield.slash", description: Text("Auf der Startseite einen Setup-Link einlösen oder mit einem Suite-Benutzer anmelden.")) }
                        ForEach(connections.values) { record in
                            NavigationLink { SuiteWorkspaceConnectionView(connection: record, request: request) } label: { Label(record.endpoint, systemImage: "server.rack") }
                                .contextMenu { Button("Als Standard für Schnellzugriffe", systemImage: "star") { preferred = record.id.uuidString } }
                        }
                    }.panelScreen().navigationTitle("Admin Suite wählen")
                }
            }.toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Fertig") { dismiss() } }
                if selected != nil && request.connection == nil && connections.values.count > 1 {
                    ToolbarItem(placement: .topBarLeading) { Menu("Server", systemImage: "server.rack") { ForEach(connections.values) { record in Button(record.endpoint) { preferred = record.id.uuidString } } } }
                }
            }
        }
    }
}

struct SuiteWorkspaceConnectionView: View {
    let connection: SuiteConnection
    var request = SuiteLaunchRequest()
    @State private var suite: SuiteAPI?
    @State private var actions: [SuiteAction] = []
    @State private var error: String?
    @State private var login = false
    var body: some View {
        Group {
            if let suite { SuiteToolDestination(suite: suite, tool: request.tool, actions: actions, place: request.place, prepareAd: request.prepareAd) }
            else {
                List {
                    if let error { Label(error, systemImage: "exclamationmark.circle"); Button("Erneut versuchen") { Task { await load() } }; Button("Erneut anmelden") { login = true } }
                    else { ProgressView("Admin Suite verbinden …") }
                }.panelScreen()
            }
        }.id(connection.id).task(id: connection.id) { await load() }
            .sheet(isPresented: $login, onDismiss: { Task { await load() } }) { SuiteDirectLoginView(existing: connection) }
    }
    func load() async {
        suite = nil; error = nil
        do {
            let current = SuiteConnections.shared.values.first { $0.id == connection.id } ?? connection
            let api = try SuiteAPI(current); _ = try await api.health()
            actions = try await api.request("schema")["actions"].array.map { SuiteAction(data: $0) }
            suite = api
        } catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }
}

struct SuiteShortcutHelpView: View {
    var body: some View {
        List {
            Section("Direkt vom Home-Bildschirm") {
                Label("In Kurzbefehle einen neuen Kurzbefehl anlegen und nach „Simplex Panel“ suchen.", systemImage: "1.circle")
                Label("„Admin-Werkzeug öffnen“ oder „Werbung vorbereiten“ wählen. Bei Werbung einen gespeicherten Werbe-Ort auswählen.", systemImage: "2.circle")
                Label("Im Kurzbefehl „Zum Home-Bildschirm“ wählen. Ein Tipp öffnet danach die passende Verwaltung bzw. kopiert die Werbung und öffnet den Ort.", systemImage: "3.circle")
                ShortcutsLink()
            }
            Section("Gut zu wissen") {
                Text("Neue Schnellzugriffe sind sofort verfügbar. Dein gewählter Server, Face ID, Zugriffsrechte, Pausen und Werbeintervalle gelten auch bei Kurzbefehlen.")
                Text("Im Kontextmenü eines Werbe-Orts kannst du außerdem einen dauerhaften Kurzbefehle-Link kopieren. Er enthält keine Zugangsdaten.")
            }
        }.panelScreen().navigationTitle("Kurzbefehle")
    }
}

struct SuiteStandalonePlayersView: View {
    let suite: SuiteAPI
    @State private var query = ""
    @State private var rows: [JSON] = []
    @State private var error: String?
    var body: some View {
        List(rows, id: \.["id"].string) { row in
            NavigationLink { SuitePlayerDossierView(suite: suite, player: row["id"].string) } label: { SuitePlayerRow(row: row, suite: suite) }
        }.panelScreen().navigationTitle("Spieler").searchable(text: $query, prompt: "Spieler suchen")
            .task(id: query) {
                do { try await Task.sleep(for: .milliseconds(250)); rows = try await suite.data("players", query: ["q": query, "limit": "50"])["items"].array }
                catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
            }.errorAlert($error)
    }
}
