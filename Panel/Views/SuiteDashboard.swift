import SwiftUI

struct SuiteDashboardCard: View {
    @ObservedObject var model: SuiteModel
    var online: [JSON] { model.players.filter { $0["online"].bool } }
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack {
                Label("Spieler & Admin Suite", systemImage: "shield.lefthalf.filled").font(.headline)
                Spacer()
                if let suite = model.api, model.health["features"].array.contains(.string("events-v1")) {
                    NavigationLink { SuiteSecurityCenterView(suite: suite, panel: model.panel, server: model.server, actions: model.actions, area: .events) } label: { Image(systemName: "bell.badge") }.accessibilityLabel("Serverereignisse")
                }
                if let suite=model.api { NavigationLink { SuiteManagementView(suite:suite,actions:model.actions) } label: { Image(systemName:"gearshape.fill") }.accessibilityLabel("Suite-Verwaltung") }
                if model.busy { ProgressView() }
            }
            if let suite = model.api {
                LabeledContent("Spieler online", value: "\(model.overview["online"].int) / \(model.overview["maxPlayers"].int)").font(.title3.bold())
                if model.overview["scoreboard"] != .null {
                    LabeledContent("Ingame-Tage · Hauptwelt", value: model.overview["scoreboard"]["mainDays"].string)
                    LabeledContent("Ingame-Tage · Zweitwelt", value: model.overview["scoreboard"]["secondDays"].string)
                }
                if let date = SuitePlayerPresentation.date(model.overview["at"]) { LabeledContent("Serverzeit") { Text(SuiteGermanTime.text(date)).font(.caption.monospacedDigit()) } }
                NavigationLink {
                    SuitePlayersView(suite: suite, panel: model.panel, server: model.server, actions: model.actions, initialFilter: .online)
                } label: {
                    HStack { Label("Online-Spieler", systemImage: "person.2.fill"); Spacer(); Image(systemName: "chevron.right").font(.caption) }
                }.accessibilityIdentifier("showPlayers")
                ForEach(Array(online.prefix(4)), id: \.["id"].string) { row in
                    NavigationLink { SuitePlayerView(suite: suite, panel: model.panel, server: model.server, player: row["id"].string, actions: model.actions, initialProfile: row) } label: { SuitePlayerRow(row: row, suite: suite) }.buttonStyle(.plain)
                }
                if online.isEmpty { Text(model.updated == nil ? "Spielerdaten werden geladen …" : "Gerade niemand online").font(.subheadline).foregroundStyle(.secondary) }
                if model.overview["online"].int > 4 { Text("Weitere Spieler in der Online-Liste").font(.caption).foregroundStyle(.secondary) }
                if suite.features.contains("public-chat-v1") { NavigationLink { SuiteLiveChatView(suite:suite) } label: { Label("Serverchat live",systemImage:"bubble.left.and.text.bubble.right.fill") } }
                NavigationLink { SuiteDiscordView(suite: suite) } label: { Label("Discord verwalten", systemImage: "bubble.left.and.bubble.right.fill") }
                SuitePinnedActionsView(suite:suite,actions:model.actions)
                Divider()
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 12) { shortcuts(suite) }
                    VStack(alignment: .leading, spacing: 12) { shortcuts(suite) }
                }.buttonStyle(.glass)
                HStack { Text("\(model.total) gespeicherte Profile"); Spacer(); if let date = model.updated { Text(date, style: .time) } }.font(.caption).foregroundStyle(.secondary)
            } else {
                Text(model.error ?? "Admin Suite wird verbunden …").font(.subheadline).foregroundStyle(.secondary)
                Button("Erneut verbinden", systemImage: "arrow.clockwise") { Task { await model.load(force: true) } }.disabled(model.busy)
            }
            if model.api != nil, let issue = model.error { Label(issue, systemImage: "exclamationmark.circle").font(.caption).foregroundStyle(.orange) }
            NavigationLink { SuiteHubView(panel: model.panel, server: model.server, model: model) } label: { Label("Suite-Verwaltung", systemImage: "slider.horizontal.3") }
        }.card()
    }
    @ViewBuilder func shortcuts(_ suite: SuiteAPI) -> some View {
        NavigationLink { SuitePlayersView(suite: suite, panel: model.panel, server: model.server, actions: model.actions) } label: { Label("Spieleraktion", systemImage: "magnifyingglass") }
        NavigationLink { SuitePlayersView(suite: suite, panel: model.panel, server: model.server, actions: model.actions, initialFilter: .recent) } label: { Label("Zuletzt online", systemImage: "clock") }
    }
}

/// Shared entry for existing player buttons; preserves the console-based manager
/// for servers without a Suite connection and never falls back after Suite failure.
struct IntegratedPlayersView: View {
    @ObservedObject var model: SuiteModel
    @ObservedObject var live: LiveServer
    var initialFilter: SuitePlayerFilter = .all
    var body: some View {
        Group {
            if let suite = model.api {
                SuitePlayersView(suite: suite, panel: model.panel, server: model.server, actions: model.actions, initialFilter: initialFilter)
            } else if model.configured {
                List {
                    if model.busy { ProgressView("Admin Suite verbinden …") }
                    Text(model.error ?? "Die gespeicherte Suite-Verbindung wird geprüft.").foregroundStyle(.secondary)
                    Button("Erneut verbinden") { Task { await model.load(force: true) } }
                    NavigationLink("Kopplung prüfen") { SuiteHubView(panel: model.panel, server: model.server, model: model) }
                }.panelScreen().navigationTitle("Spieler")
            } else { PlayerManagerView(api: model.panel, server: model.server, live: live) }
        }.task { await model.load() }
    }
}

struct SuitePlayerBrowser: View {
    let panel: PanelAPI
    let server: Server
    var filter: SuitePlayerFilter = .all
    @StateObject private var model: SuiteModel
    init(panel: PanelAPI, server: Server, filter: SuitePlayerFilter = .all) {
        self.panel = panel; self.server = server; self.filter = filter
        _model = StateObject(wrappedValue: SuiteModel(panel: panel, server: server))
    }
    var body: some View {
        Group {
            if let suite = model.api { SuitePlayersView(suite: suite, panel: panel, server: server, actions: model.actions, initialFilter: filter) }
            else {
                List {
                    Text(server.name).font(.headline)
                    if model.busy { ProgressView("Spielerverwaltung verbinden …") }
                    if let error = model.error { Text(error).foregroundStyle(.secondary) }
                    Button("Erneut verbinden") { Task { await model.load(force: true) } }.disabled(model.busy)
                    NavigationLink("Kopplung prüfen") { SuiteHubView(panel: panel, server: server, model: model) }
                }.panelScreen().navigationTitle("Spieler")
            }
        }.task { await model.load() }
    }
}
