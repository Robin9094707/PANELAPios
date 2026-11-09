import SwiftUI
import AppIntents

/// Same glass cards and colours as the existing Admin centre, with a complete searchable directory.
struct SuiteToolDirectoryView: View {
    let suite: SuiteAPI
    var actions: [SuiteAction] = []
    var panel: PanelAPI? = nil
    var server: Server? = nil
    var live: LiveServer? = nil
    var badges: [SuiteTool: Int] = [:]
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
                    if !embedded {
                    VStack(alignment: .leading, spacing: 10) {
                        Label("Admin Suite", systemImage: "shield.lefthalf.filled").font(.title2.bold())
                        Text(suite.connection.endpoint).font(.caption).foregroundStyle(.secondary)
                        Text("Schnellzugriffe lange drücken, um Favoriten anzupassen.").font(.caption).foregroundStyle(.secondary)
                    }.frame(maxWidth: .infinity, alignment: .leading).card()
                    }
                    SuiteBriefingCard(suite: suite)
                    toolGroup("Schnellzugriffe", tools: tools.filter { favorites.split(separator: ",").contains(Substring($0.rawValue)) && allowed($0) })
                }
                ForEach(groups, id: \.self) { group in toolGroup(group, tools: tools.filter { $0.group == group }) }
                if selectedQuery.isEmpty { SuitePinnedActionsView(suite: suite, actions: actions) }
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
                if suite.connection.panel.hasPrefix("suite-direct:") {
                    NavigationLink { SuiteDirectDashboard(connection: suite.connection) } label: { Label("Gespeicherten Zugang verwalten", systemImage: "key.horizontal.fill").frame(maxWidth: .infinity, alignment: .leading).card() }.buttonStyle(.plain)
                }
            }.padding(embedded ? 0 : 20).frame(maxWidth: 1000).frame(maxWidth: .infinity)
    }
    @ViewBuilder func toolGroup(_ title: String, tools: [SuiteTool]) -> some View {
        if !tools.isEmpty {
            VStack(alignment: .leading, spacing: 12) {
                Text(title).font(.headline)
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 140), spacing: 10)], spacing: 10) {
                    ForEach(tools) { tool in
                        SuiteToolTile(title: tool.title, symbol: tool.symbol, tint: tool == .advertising ? .orange : Color.panelAccent, badge: badges[tool] ?? 0, identifier: "suiteTool-" + tool.id) {
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
            case .powerBot: SuitePowerBotView(suite: suite)
            case .briefing: SuiteBriefingView(suite: suite)
            case .locker: SuiteItemLockerView(suite: suite)
            case .smartRestart: SuiteSmartRestartView(suite: suite)
            case .automations: SuiteAutomationsView(suite: suite)
            case .serverEvents: SuiteServerEventsView(suite: suite)
            case .assistant: SuiteAIAssistantView(suite: suite, panel: panel, server: server)
            case .diagnostics: SuiteDiagnosticsView(suite: suite)
            case .lagMap, .spark, .crashes, .console: SuiteDiagnosticsDestination(suite: suite, tool: tool)
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
            case .chat: SuiteLiveChatView(suite: suite)
            case .restart: SuiteRestartView(suite: suite)
            case .pairing:
                if let panel, let server { SuiteHubView(panel: panel, server: server) }
                else if suite.connection.panel.hasPrefix("suite-direct:") { SuiteDirectDashboard(connection: suite.connection) }
                else {
                    List { Section("Gespeicherte Verbindung") {
                        Text(suite.connection.endpoint).textSelection(.enabled)
                        Text("Wähle auf der Startseite das zugehörige Hosting-Panel und öffne beim Server „Verwalten → Admin Suite“, um die Kopplung zu ändern.").foregroundStyle(.secondary)
                    } }.panelScreen().navigationTitle("Kopplung")
                }
            }
        }
    }
}

/// Diagnostic tools use the same capabilities returned by the server as the existing diagnostics hub.
struct SuiteDiagnosticsDestination: View {
    let suite: SuiteAPI
    let tool: SuiteTool
    @State private var data: JSON = .null
    @State private var error: String?
    var body: some View {
        ZStack {
            if data == .null {
                List {
                    if let error { Text(error).foregroundStyle(.secondary); Button("Erneut laden") { Task { await load() } } }
                    else { ProgressView("Diagnose verbinden …") }
                }.panelScreen()
            } else {
                switch tool {
                case .lagMap: SuiteLagMapView(suite: suite, canRun: data["canRun"].bool, aiAvailable: data["ai"]["available"].bool)
                case .spark: SuiteSparkView(suite: suite, canRun: data["canRun"].bool, canManage: data["canManage"].bool, aiAvailable: data["ai"]["available"].bool)
                case .crashes: SuiteCrashesView(suite: suite, canRun: data["canRun"].bool, aiAvailable: data["ai"]["available"].bool)
                default: SuiteDiagnosticsConsoleView(suite: suite, canRun: data["canRun"].bool, aiAvailable: data["ai"]["available"].bool)
                }
            }
        }.task { await load() }
    }
    func load() async {
        do { data = try await SuiteNavigationDeadline.run(seconds: 12) { try await suite.request("diagnostics", timeout: 12) }; error = nil }
        catch { self.error = RequestFailure.isCancellation(error) ? "Verbindung abgebrochen. Bitte erneut laden." : error.localizedDescription }
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
    @EnvironmentObject private var store: AppStore
    @ObservedObject private var connections = SuiteConnections.shared
    @ObservedObject private var preferences = SuiteQuickAccess.shared
    @State private var chosen: UUID?
    @State private var choosing = false
    @Environment(\.dismiss) private var dismiss
    var available: [SuiteConnection] { connections.values.filter { !preferences.isHidden($0.id) } }
    var selected: SuiteConnection? {
        if let id = request.connection { return available.first { $0.id == id } }
        if choosing { return nil }
        if let chosen { return available.first { $0.id == chosen } }
        if let id = preferences.preferred, let record = available.first(where: { $0.id == id }) { return record }
        return available.count == 1 ? available.first : nil
    }
    var body: some View {
        NavigationStack {
            Group {
                if let record = selected {
                    SuiteWorkspaceConnectionView(connection: record, request: request).id(record.id)
                } else if request.connection != nil || chosen != nil && !choosing {
                    ContentUnavailableView("Zugang nicht mehr vorhanden", systemImage: "key.slash", description: Text("Bitte den Kurzbefehl mit einem aktuellen Zugang neu einrichten."))
                } else {
                    List {
                        if available.isEmpty { ContentUnavailableView("Keine Admin Suite verbunden", systemImage: "shield.slash", description: Text("Auf der Startseite einen Setup-Link einlösen oder mit einem Suite-Benutzer anmelden.")) }
                        ForEach(available) { record in
                            Button {
                                chosen = record.id; choosing = false
                            } label: {
                                HStack { Label(preferences.name(record, store: store), systemImage: "server.rack"); Spacer(); Image(systemName: "chevron.right").font(.caption) }
                                Text(record.endpoint).font(.caption).foregroundStyle(.secondary)
                            }.accessibilityIdentifier("suite-choose-" + record.id.uuidString)
                        }
                        Section { Text("Die Server-Kacheln auf der Startseite öffnen das gewünschte Werkzeug ohne diese Auswahl. Unter „Server-Schnellzugriffe → Anpassen“ kannst du zusätzlich einen Standardserver festlegen.").font(.caption).foregroundStyle(.secondary) }
                    }.panelScreen().navigationTitle("Admin Suite wählen")
                }
            }.toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Fertig") { dismiss() } }
                if selected != nil && request.connection == nil && available.count > 1 {
                    ToolbarItem(placement: .topBarLeading) { Button("Server wechseln", systemImage: "server.rack") { choosing = true; chosen = nil } }
                }
            }
        }
    }
}

struct SuiteWorkspaceConnectionView: View {
    let connection: SuiteConnection
    var request = SuiteLaunchRequest()
    @EnvironmentObject private var store: AppStore
    @ObservedObject private var connections = SuiteConnections.shared
    @StateObject private var loader = SuiteWorkspaceLoadModel()
    @State private var login = false
    var title: String { connections.name(connection, store: store) }
    var body: some View {
        ZStack {
            if let suite = loader.api {
                let panel = store.api?.base.absoluteString == connection.panel ? store.api : nil
                let server = panel == nil ? nil : store.servers.first { $0.id == connection.server }
                VStack(spacing: 0) {
                    if let notice = loader.notice {
                        VStack(alignment: .leading, spacing: 6) { Text(notice).font(.caption).accessibilityIdentifier("suiteSchemaNotice"); Button("Zusätzliche Funktionen neu laden") { Task { await loader.reloadSchema() } }.font(.caption) }.padding(12)
                    }
                    SuiteToolDestination(suite: suite, tool: request.tool, actions: loader.actions, panel: panel, server: server, place: request.place, prepareAd: request.prepareAd)
                }
            } else {
                List {
                    Section(title) {
                        if loader.loading {
                            ProgressView("Admin Suite verbinden …").accessibilityIdentifier("suiteConnectionProgress")
                            Button("Verbindung abbrechen", role: .cancel) { loader.cancel() }
                            Text("Der Verbindungsaufbau wird nach höchstens 12 Sekunden beendet.").font(.caption).foregroundStyle(.secondary)
                        } else {
                            Label(loader.error ?? "Verbindung noch nicht hergestellt.", systemImage: "wifi.exclamationmark").accessibilityIdentifier("suiteConnectionError")
                            Button("Erneut versuchen") { Task { await load() } }.accessibilityIdentifier("suiteConnectionRetry")
                            if connection.panel.hasPrefix("suite-direct:") { Button("Erneut anmelden") { login = true } }
                            else { Text("Bei abgelaufenem Schlüssel die Suite-Kopplung im zugehörigen Server prüfen.").font(.caption).foregroundStyle(.secondary) }
                        }
                        Text(connection.endpoint).font(.caption).foregroundStyle(.secondary).textSelection(.enabled)
                    }
                }.panelScreen()
            }
        }
        .toolbar { ToolbarItem(placement: .principal) { Text(title).font(.headline).lineLimit(1).accessibilityIdentifier("suiteTargetName") } }
        .task(id: connection.id) { await load() }
        .onDisappear { loader.cancel() }
        .sheet(isPresented: $login, onDismiss: { Task { await load() } }) { SuiteDirectLoginView(existing: connection) }
    }
    func load() async {
        let current = connections.values.first { $0.id == connection.id } ?? connection
        await loader.load(current, tool: request.tool)
    }
}

struct SuiteShortcutHelpView: View {
    var body: some View {
        List {
            Section("Direkt vom Home-Bildschirm") {
                Label("In Kurzbefehle einen neuen Kurzbefehl anlegen und nach „Simplex Panel“ suchen.", systemImage: "1.circle")
                Label("„KI-Assistent öffnen“, „PowerBot-Zentrale öffnen“, „Rückblick öffnen“, „Admin-Werkzeug öffnen“ oder „Werbung vorbereiten“ wählen. Optional einen festen Server zuordnen.", systemImage: "2.circle")
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
