import SwiftUI

/// Java heads come from the public mc-heads.net service; Bedrock/Floodgate profiles keep the platform icon.
struct SuitePlayerAvatar: View {
    let row: JSON
    var size: CGFloat = 34
    var platformOverride: PlayerPlatform? = nil
    @AppStorage("suite.playerHeads") private var heads = true
    var platform: PlayerPlatform { platformOverride ?? PlayerPlatform.resolve(row) }
    var headURL: URL? {
        guard heads, row["client"].string.uppercased() != "BEDROCK", let uuid = UUID(uuidString: row["id"].string), !uuid.uuidString.hasPrefix("00000000-0000-0000-") else { return nil }
        return URL(string: "https://mc-heads.net/avatar/" + uuid.uuidString + "/" + String(Int(size * 2)))
    }
    var body: some View {
        Group {
            if let url = headURL {
                AsyncImage(url: url) { image in image.resizable().interpolation(.none) } placeholder: { PlayerPlatformIcon(platform: platform, size: size * 0.7) }
                    .frame(width: size, height: size).clipShape(RoundedRectangle(cornerRadius: size * 0.22))
            } else { PlayerPlatformIcon(platform: platform, size: size * 0.78).frame(width: size, height: size) }
        }.overlay(alignment: .bottomTrailing) {
            if row["online"].bool { Circle().fill(.green).frame(width: size * 0.28, height: size * 0.28).overlay { Circle().strokeBorder(.background, lineWidth: 2) }.offset(x: 3, y: 3) }
        }.accessibilityHidden(true)
    }
}

/// Compact moderation badges for player rows and profile headers.
struct SuitePlayerBadges: View {
    struct Badge: Identifiable, Equatable { let title: String; let symbol: String; let color: Color; var id: String { title } }
    let row: JSON
    var body: some View {
        let badges = Self.badges(row)
        if !badges.isEmpty {
            HStack(spacing: 5) { ForEach(badges) { badge in Label(badge.title, systemImage: badge.symbol).labelStyle(.titleAndIcon).font(.caption2.weight(.semibold)).lineLimit(1).padding(.horizontal, 7).padding(.vertical, 3).background(badge.color.opacity(0.15), in: Capsule()).foregroundStyle(badge.color) } }
                .accessibilityElement(children: .combine)
        }
    }
    static func badges(_ row: JSON) -> [Badge] {
        var result: [Badge] = []
        if row["ban"].bool { result.append(Badge(title: "Gebannt", symbol: "hand.raised.fill", color: .red)) }
        if row["mute"].bool { result.append(Badge(title: "Stumm", symbol: "speaker.slash.fill", color: .orange)) }
        if row["warnings"].int > 0 { result.append(Badge(title: row["warnings"].int == 1 ? "1 Warnung" : "\(row["warnings"].int) Warnungen", symbol: "exclamationmark.triangle.fill", color: .yellow)) }
        if !row["staff"].string.isEmpty { result.append(Badge(title: "Team", symbol: "person.badge.shield.checkmark.fill", color: Color.panelAccent)) }
        return result
    }
}

struct SuiteAdminCenterView: View {
    @ObservedObject var model: SuiteModel
    @ObservedObject var live: LiveServer
    @StateObject private var center = SuiteAdminCenterModel()
    @ObservedObject private var bookmarks = SuitePlayerBookmarks.shared
    @Environment(\.scenePhase) private var scenePhase
    @State private var restartSheet = false
    var body: some View {
        Group {
            if let suite = model.api { content(suite) }
            else {
                List {
                    Section {
                        Label("Admin-Zentrale", systemImage: "shield.lefthalf.filled").font(.title2.bold()).foregroundStyle(Color.panelAccent)
                        if model.busy { ProgressView("Admin Suite verbinden …") }
                        Text(model.error ?? "Die gespeicherte Suite-Verbindung wird geprüft.").foregroundStyle(.secondary)
                        Button("Erneut verbinden", systemImage: "arrow.clockwise") { Task { await model.load(force: true) } }.disabled(model.busy)
                        NavigationLink("Kopplung prüfen") { SuiteHubView(panel: model.panel, server: model.server, model: model) }
                    }
                }.panelScreen()
            }
        }.navigationTitle("Admin").task { await model.load() }
    }
    func content(_ suite: SuiteAPI) -> some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 18) {
                header(suite)
                metrics
                alerts(suite)
                onlinePlayers(suite)
                bookmarksCard(suite)
                tools(suite)
                activity(suite)
            }.padding(20).frame(maxWidth: 1000).frame(maxWidth: .infinity)
        }.panelScreen().refreshable { await model.load(force: true); await center.load(suite) }.scrollBounceBehavior(.always)
            .task(id: "\(scenePhase)-\(ObjectIdentifier(suite))") {
                guard scenePhase == .active else { return }
                await center.load(suite)
                while !Task.isCancelled {
                    do { try await Task.sleep(for: .seconds(center.dashboard["restart"]["active"].bool ? 3 : 20)) } catch { return }
                    await center.load(suite)
                }
            }
            .sheet(isPresented: $restartSheet) { NavigationStack { SuiteRestartView(suite: suite).toolbar { ToolbarItem(placement: .confirmationAction) { Button("Fertig") { restartSheet = false } } } } }
    }
    var dashboard: JSON { center.dashboard }
    func header(_ suite: SuiteAPI) -> some View {
        HStack(alignment: .center, spacing: 14) {
            ZStack { RoundedRectangle(cornerRadius: 16).fill(LinearGradient(colors: [Color.panelAccent, Color.panelSecondary], startPoint: .topLeading, endPoint: .bottomTrailing)).frame(width: 54, height: 54); Image(systemName: "shield.lefthalf.filled").font(.title2.bold()).foregroundStyle(.white) }.accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 4) {
                Text("Admin-Zentrale").font(.title2.bold()).accessibilityIdentifier("adminCenterTitle")
                HStack(spacing: 6) {
                    Circle().fill(center.error == nil ? Color.green : .orange).frame(width: 7, height: 7)
                    Text("Suite " + (dashboard["version"].string.isEmpty ? model.health["version"].string : dashboard["version"].string) + " · " + scopeName(suite.scope)).font(.caption).foregroundStyle(.secondary)
                }
                if let updated = center.updated { Text("Stand " + updated.formatted(date: .omitted, time: .standard)).font(.caption2).foregroundStyle(.secondary).monospacedDigit() }
            }
            Spacer()
            if center.busy { ProgressView() }
            else { Button("Admin-Zentrale aktualisieren", systemImage: "arrow.clockwise") { Task { await center.load(suite) } }.labelStyle(.iconOnly).buttonStyle(.glass) }
        }.card()
    }
    var metrics: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 12)], spacing: 12) {
            SuiteMetricTile(title: "Spieler online", symbol: "person.2.fill", value: playersValue, detail: playersDetail, tint: Color.panelAccent, fraction: playersFraction)
            SuiteMetricTile(title: "Server-TPS", symbol: "speedometer", value: tpsValue, detail: tpsDetail, tint: tpsTint, fraction: tpsFraction)
            SuiteMetricTile(title: "Arbeitsspeicher", symbol: "memorychip", value: ramValue, detail: ramDetail, tint: .blue, fraction: SuiteAdminDashboard.ramFraction(dashboard))
            SuiteMetricTile(title: "Profile", symbol: "person.crop.rectangle.stack", value: profilesValue, detail: profilesDetail, tint: .orange, fraction: nil)
        }
    }
    var loaded: Bool { dashboard != .null }
    var playersValue: String { loaded ? "\(dashboard["online"].int) / \(dashboard["maxPlayers"].int)" : "–" }
    var playersDetail: String { dashboard["active24h"] == .null ? "\(dashboard["profiles"].int) Profile" : "\(dashboard["active24h"].int) aktiv in 24 h" }
    var playersFraction: Double? { dashboard["maxPlayers"].double > 0 ? dashboard["online"].double / dashboard["maxPlayers"].double : nil }
    var tps: Double { dashboard["tps"].double }
    var tpsValue: String { tps > 0 ? String(format: "%.1f", tps) : "–" }
    var tpsDetail: String { dashboard["mspt"].double > 0 ? String(format: "%.1f ms pro Tick", dashboard["mspt"].double) : "Tickdauer unbekannt" }
    var tpsTint: Color { switch SuiteAdminDashboard.tpsLevel(tps) { case 1: return .yellow; case 2: return .red; default: return .green } }
    var tpsFraction: Double? { tps > 0 ? min(1, tps / 20) : nil }
    var ramValue: String { dashboard["ramUsed"] == .null ? "–" : bytes(dashboard["ramUsed"].double) }
    var ramDetail: String { dashboard["ramMax"].double > 0 ? "von " + bytes(dashboard["ramMax"].double) : "Suite-JVM" }
    var profilesValue: String { loaded ? String(dashboard["profiles"].int) : "–" }
    var profilesDetail: String {
        if dashboard["new24h"] != .null { return "\(dashboard["new24h"].int) neu in 24 h" }
        let uptime = dashboard["uptime"].double
        return uptime > 0 ? "Laufzeit " + SuitePlayerView.duration(uptime) : "Gespeicherte Spieler"
    }
    @ViewBuilder func alerts(_ suite: SuiteAPI) -> some View {
        let items = SuiteAdminDashboard.alerts(dashboard)
        VStack(alignment: .leading, spacing: 12) {
            Label("Hinweise", systemImage: "bell.badge.fill").font(.headline)
            if dashboard == .null { Text(center.error ?? "Lagebild wird geladen …").font(.subheadline).foregroundStyle(.secondary) }
            else if items.isEmpty { Label("Alles ruhig · keine offenen Hinweise", systemImage: "checkmark.seal.fill").foregroundStyle(.green).accessibilityIdentifier("adminCenterCalm") }
            ForEach(items) { alert in alertRow(alert, suite: suite) }
            if let error = center.error, dashboard != .null { Label(error, systemImage: "exclamationmark.circle").font(.caption).foregroundStyle(.orange) }
        }.frame(maxWidth: .infinity, alignment: .leading).card()
    }
    @ViewBuilder func alertRow(_ alert: SuiteAdminAlert, suite: SuiteAPI) -> some View {
        let label = HStack(spacing: 12) {
            Image(systemName: alert.symbol).foregroundStyle(alert.critical ? .orange : Color.panelAccent).frame(width: 26)
            VStack(alignment: .leading, spacing: 2) { Text(alert.title).font(.subheadline.weight(.semibold)); Text(alert.detail).font(.caption).foregroundStyle(.secondary) }
            Spacer(); Image(systemName: "chevron.right").font(.caption).foregroundStyle(.tertiary)
        }.contentShape(Rectangle())
        switch alert.kind {
        case .restart: Button { restartSheet = true } label: { label }.buttonStyle(.plain)
        case .maintenance: NavigationLink { SuiteManagementView(suite: suite, actions: model.actions) } label: { label }.buttonStyle(.plain)
        case .reports where suite.features.contains("moderation-center-v1"): NavigationLink { SuiteSecurityCenterView(suite: suite, panel: model.panel, server: model.server, actions: model.actions, area: .reports) } label: { label }.buttonStyle(.plain)
        case .bans where suite.features.contains("moderation-center-v1"): NavigationLink { SuiteSecurityCenterView(suite: suite, panel: model.panel, server: model.server, actions: model.actions, area: .bans) } label: { label }.buttonStyle(.plain)
        case .ultrabans: NavigationLink { SuiteUltraView(suite: suite, actions: model.actions) } label: { label }.buttonStyle(.plain)
        case .warnings, .mutes: NavigationLink { SuitePlayersView(suite: suite, panel: model.panel, server: model.server, actions: model.actions, initialFilter: suite.features.contains("players-filters-v3") ? .flagged : .all) } label: { label }.buttonStyle(.plain)
        default: label
        }
    }
    func onlinePlayers(_ suite: SuiteAPI) -> some View {
        let players = dashboard["players"].array, staff = dashboard["staff"].array
        return VStack(alignment: .leading, spacing: 12) {
            HStack { Label("Gerade online", systemImage: "dot.radiowaves.left.and.right").font(.headline); Spacer(); Text("\(dashboard["online"].int)").font(.headline.monospacedDigit()).foregroundStyle(.secondary) }
            if players.isEmpty { Text(dashboard == .null ? "Spieler werden geladen …" : "Gerade niemand online").font(.subheadline).foregroundStyle(.secondary) }
            else {
                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(spacing: 14) {
                        ForEach(players, id: \.["id"].string) { row in
                            NavigationLink { SuitePlayerView(suite: suite, panel: model.panel, server: model.server, player: row["id"].string, actions: model.actions, initialProfile: row) } label: {
                                VStack(spacing: 6) {
                                    SuitePlayerAvatar(row: row, size: 46)
                                    Text(row["name"].string).font(.caption.weight(.medium)).lineLimit(1).frame(maxWidth: 76)
                                    if row["ban"].bool || row["mute"].bool || row["warnings"].int > 0 { Image(systemName: "exclamationmark.triangle.fill").font(.caption2).foregroundStyle(.orange) }
                                }
                            }.buttonStyle(.plain).accessibilityLabel(row["name"].string).accessibilityIdentifier("adminCenterOnline-" + row["name"].string)
                        }
                    }.padding(.vertical, 4)
                }
            }
            if !staff.isEmpty {
                Divider()
                Label("Team online · \(staff.count)", systemImage: "person.badge.shield.checkmark.fill").font(.subheadline.weight(.semibold)).foregroundStyle(Color.panelAccent)
                ForEach(staff, id: \.["id"].string) { row in HStack { Image(systemName: row["operator"].bool ? "crown.fill" : "shield.fill").foregroundStyle(row["operator"].bool ? .orange : Color.panelAccent).frame(width: 22); Text(row["name"].string); Spacer(); Text(row["role"].string).font(.caption).foregroundStyle(.secondary).lineLimit(1) } }
            }
            NavigationLink { SuitePlayersView(suite: suite, panel: model.panel, server: model.server, actions: model.actions, initialFilter: .online) } label: { HStack { Label("Alle Spieler verwalten", systemImage: "person.2.fill"); Spacer(); Image(systemName: "chevron.right").font(.caption) } }.accessibilityIdentifier("adminCenterPlayers")
        }.frame(maxWidth: .infinity, alignment: .leading).card()
    }
    @ViewBuilder func bookmarksCard(_ suite: SuiteAPI) -> some View {
        let favorites = bookmarks.favorites(suite.bookmarkScope), recent = bookmarks.recent(suite.bookmarkScope)
        if !favorites.isEmpty || !recent.isEmpty {
            VStack(alignment: .leading, spacing: 12) {
                if !favorites.isEmpty {
                    Label("Favoriten", systemImage: "star.fill").font(.headline).foregroundStyle(.yellow)
                    ForEach(favorites) { entry in bookmarkLink(entry, suite: suite, symbol: "star.fill") }
                }
                if !recent.isEmpty {
                    HStack { Label("Zuletzt angesehen", systemImage: "clock.arrow.circlepath").font(.headline); Spacer(); Button("Leeren") { bookmarks.clearRecent(suite.bookmarkScope) }.font(.caption) }
                    ForEach(recent.prefix(5)) { entry in bookmarkLink(entry, suite: suite, symbol: "person.crop.circle") }
                }
            }.frame(maxWidth: .infinity, alignment: .leading).card()
        }
    }
    func bookmarkLink(_ entry: SuitePlayerBookmarks.Entry, suite: SuiteAPI, symbol: String) -> some View {
        NavigationLink { SuitePlayerView(suite: suite, panel: model.panel, server: model.server, player: entry.id, actions: model.actions) } label: {
            HStack { Image(systemName: symbol).foregroundStyle(symbol == "star.fill" ? .yellow : .secondary).frame(width: 22); Text(entry.name); Spacer(); Text(entry.at, style: .relative).font(.caption2).foregroundStyle(.secondary) }
        }.buttonStyle(.plain)
    }
    func tools(_ suite: SuiteAPI) -> some View {
        let features = suite.features
        return VStack(alignment: .leading, spacing: 12) {
            Label("Werkzeuge", systemImage: "square.grid.2x2.fill").font(.headline)
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 140), spacing: 10)], spacing: 10) {
                SuiteToolTile(title: "Spieler", symbol: "person.2.fill", tint: Color.panelAccent) { SuitePlayersView(suite: suite, panel: model.panel, server: model.server, actions: model.actions) }
                if features.contains("public-chat-v1") { SuiteToolTile(title: "Serverchat", symbol: "bubble.left.and.text.bubble.right.fill", tint: .blue) { SuiteLiveChatView(suite: suite) } }
                if features.contains("moderation-center-v1") {
                    SuiteToolTile(title: "Reports", symbol: "flag.fill", tint: .orange, badge: dashboard["moderation"]["openReports"].int) { SuiteSecurityCenterView(suite: suite, panel: model.panel, server: model.server, actions: model.actions, area: .reports) }
                    SuiteToolTile(title: "Banns", symbol: "hand.raised.fill", tint: .red) { SuiteSecurityCenterView(suite: suite, panel: model.panel, server: model.server, actions: model.actions, area: .bans) }
                    SuiteToolTile(title: "VPN-Schutz", symbol: "network", tint: .indigo) { SuiteSecurityCenterView(suite: suite, panel: model.panel, server: model.server, actions: model.actions, area: .vpn) }
                }
                if features.contains("events-v1") { SuiteToolTile(title: "Ereignisse", symbol: "bell.badge.fill", tint: .pink) { SuiteSecurityCenterView(suite: suite, panel: model.panel, server: model.server, actions: model.actions, area: .events) } }
                SuiteToolTile(title: "Alt-Accounts", symbol: "person.2.badge.key.fill", tint: .purple) { SuiteAltDashboardView(suite: suite, panel: model.panel, server: model.server, actions: model.actions) }
                SuiteToolTile(title: "Ultrabanns", symbol: "shield.slash.fill", tint: .red) { SuiteUltraView(suite: suite, actions: model.actions) }
                if features.contains("admin-roles-v3") && suite.scope == "full" { SuiteToolTile(title: "Admin-Team", symbol: "person.badge.shield.checkmark.fill", tint: Color.panelSecondary) { SuiteAdminDirectory(suite: suite) } }
                if features.contains("restart-v1") { SuiteToolTile(title: "Neustart", symbol: "arrow.clockwise.circle.fill", tint: .orange) { SuiteRestartView(suite: suite) } }
                SuiteToolTile(title: "Verwaltung", symbol: "wrench.and.screwdriver.fill", tint: .gray) { SuiteManagementView(suite: suite, actions: model.actions) }
                SuiteToolTile(title: "Discord", symbol: "bubble.left.and.bubble.right.fill", tint: .indigo) { SuiteDiscordView(suite: suite) }
                SuiteToolTile(title: "Statistiken", symbol: "chart.bar.xaxis", tint: .teal, identifier: "adminCenterStatistics") { SuiteStatisticsView(model: model, live: live) }
                if features.contains("overview") { SuiteToolTile(title: "Serverbetrieb", symbol: "chart.xyaxis.line", tint: .green) { SuiteOperationsView(suite: suite, actions: model.actions) } }
                SuiteToolTile(title: "Einstellungen", symbol: "slider.horizontal.3", tint: .gray) { SuiteSettingsView(suite: suite) }
                SuiteToolTile(title: "Alle Funktionen", symbol: "magnifyingglass", tint: Color.panelAccent) { SuiteActionsView(suite: suite, actions: model.actions.filter { !["menu.action", "settings.update"].contains($0.id) }, title: "Alle Funktionen") }
                SuiteToolTile(title: "Kopplung", symbol: "key.horizontal.fill", tint: .secondary, identifier: "adminCenterHub") { SuiteHubView(panel: model.panel, server: model.server, model: model) }
            }
            SuitePinnedActionsView(suite: suite, actions: model.actions)
        }.frame(maxWidth: .infinity, alignment: .leading).card()
    }
    @ViewBuilder func activity(_ suite: SuiteAPI) -> some View {
        let events = dashboard["events"].array, reports = dashboard["reports"].array
        if !events.isEmpty || !reports.isEmpty {
            VStack(alignment: .leading, spacing: 12) {
                if !reports.isEmpty {
                    Label("Offene Reports", systemImage: "flag.fill").font(.headline).foregroundStyle(.orange)
                    ForEach(reports, id: \.["id"].string) { row in
                        HStack { VStack(alignment: .leading, spacing: 2) { Text("#" + row["id"].string + " · " + row["name"].string).font(.subheadline.weight(.semibold)); Text(row["category"].string).font(.caption).foregroundStyle(.secondary) }; Spacer(); if let date = SuitePlayerPresentation.date(row["created"]) { Text(date, style: .relative).font(.caption2).foregroundStyle(.secondary) } }
                    }
                }
                if !events.isEmpty {
                    if !reports.isEmpty { Divider() }
                    Label("Letzte Ereignisse", systemImage: "bell.fill").font(.headline)
                    ForEach(Array(events.enumerated()), id: \.offset) { _, row in
                        HStack(alignment: .top, spacing: 10) {
                            Image(systemName: row["critical"].bool ? "exclamationmark.shield.fill" : "bell.fill").foregroundStyle(row["critical"].bool ? .orange : Color.panelAccent).frame(width: 22)
                            VStack(alignment: .leading, spacing: 2) { Text(row["title"].string).font(.subheadline.weight(.semibold)).lineLimit(2); Text(row["body"].string).font(.caption).foregroundStyle(.secondary).lineLimit(2) }
                            Spacer(); if let date = SuitePlayerPresentation.date(row["time"]) { Text(date, style: .relative).font(.caption2).foregroundStyle(.secondary) }
                        }
                    }
                    if suite.features.contains("events-v1") { NavigationLink("Alle Ereignisse") { SuiteSecurityCenterView(suite: suite, panel: model.panel, server: model.server, actions: model.actions, area: .events) }.font(.subheadline) }
                }
            }.frame(maxWidth: .infinity, alignment: .leading).card()
        }
    }
    func scopeName(_ value: String) -> String { switch value { case "full": return "Vollzugriff"; case "manage": return "Spieler & Moderation"; default: return "Nur lesen" } }
}

struct SuiteMetricTile: View {
    let title: String
    let symbol: String
    let value: String
    let detail: String
    let tint: Color
    let fraction: Double?
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label(title, systemImage: symbol).font(.caption.weight(.semibold)).foregroundStyle(tint)
            Text(value).font(.system(.title2, design: .rounded, weight: .bold)).monospacedDigit().minimumScaleFactor(0.6).lineLimit(1).contentTransition(.numericText())
            Text(detail).font(.caption).foregroundStyle(.secondary).lineLimit(1).minimumScaleFactor(0.8)
            if let fraction { ProgressView(value: min(1, max(0, fraction))).tint(tint) } else { Capsule().fill(tint.opacity(0.14)).frame(height: 4) }
        }.frame(maxWidth: .infinity, alignment: .leading).card().accessibilityElement(children: .combine)
    }
}

struct SuiteToolTile<Destination: View>: View {
    let title: String
    let symbol: String
    let tint: Color
    var badge = 0
    var identifier = ""
    @ViewBuilder let destination: () -> Destination
    var body: some View {
        NavigationLink { destination() } label: {
            HStack(spacing: 10) {
                Image(systemName: symbol).font(.title3).foregroundStyle(tint).frame(width: 28)
                Text(title).font(.subheadline.weight(.medium)).foregroundStyle(.primary).lineLimit(2).minimumScaleFactor(0.85).multilineTextAlignment(.leading)
                Spacer(minLength: 0)
                if badge > 0 { Text(String(badge)).font(.caption2.bold()).padding(.horizontal, 6).padding(.vertical, 2).background(.orange, in: Capsule()).foregroundStyle(.white) }
            }.padding(12).frame(maxWidth: .infinity, minHeight: 54, alignment: .leading)
                .background(tint.opacity(0.10), in: RoundedRectangle(cornerRadius: 14))
        }.buttonStyle(.plain).accessibilityIdentifier(identifier.isEmpty ? "adminTool-" + title : identifier)
    }
}
