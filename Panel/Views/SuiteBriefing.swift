import SwiftUI

/// 5.6.0: "While you were away" per Suite connection. One shared store so the start screen and the
/// Admin Suite overview show the same summary and a dismissal applies everywhere.
@MainActor final class SuiteBriefingStore: ObservableObject {
    static let shared = SuiteBriefingStore()
    struct Pending: Equatable { let since: Double; let data: JSON }
    @Published private(set) var pending: [UUID: Pending] = [:]
    private var loading: Set<UUID> = []
    private let defaults: UserDefaults
    init(defaults: UserDefaults = .standard) { self.defaults = defaults }

    static func key(_ id: UUID) -> String { "briefing.lastVisit." + id.uuidString }
    var enabled: Bool { defaults.object(forKey: "briefing.enabled") as? Bool ?? (defaults.object(forKey: "powerbot.digest.enabled") as? Bool ?? true) }
    var threshold: Double { let value = defaults.double(forKey: "briefing.thresholdHours"); return value > 0 ? value : 3 }
    var sections: Set<String> { Set((defaults.string(forKey: "briefing.sections") ?? "players,powerbot,events").split(separator: ",").map(String.init)) }

    /// The previous visit, migrated once from the 5.4 PowerBot digest.
    func lastVisit(_ id: UUID) -> Double {
        let value = defaults.double(forKey: Self.key(id))
        if value > 0 { return value }
        let legacy = defaults.double(forKey: "powerbot.lastActive." + id.uuidString)
        if legacy > 0 { defaults.set(legacy, forKey: Self.key(id)); return legacy }
        return 0
    }
    func markLeft(_ ids: [UUID], now: Double = Date().timeIntervalSince1970) {
        for id in ids { defaults.set(now, forKey: Self.key(id)) }
    }
    func dismiss(_ id: UUID) { withAnimation { pending[id] = nil } }

    func refresh(_ suite: SuiteAPI) async {
        let id = suite.connection.id, now = Date().timeIntervalSince1970
        guard enabled, !loading.contains(id), pending[id] == nil else { return }
        let last = lastVisit(id)
        guard SuiteBriefingModel.shouldShow(lastVisit: last, now: now, thresholdHours: threshold) else {
            if last == 0 { markLeft([id], now: now) }
            return
        }
        loading.insert(id); defer { loading.remove(id) }
        do {
            let data = try await Self.fetch(suite, since: last)
            guard !Task.isCancelled else { return }
            markLeft([id], now: now)
            if SuiteBriefingModel.hasNews(data, sections: sections) { withAnimation { pending[id] = Pending(since: last, data: data) } }
        } catch { }
    }

    /// Older suites without `briefing-v1` still get the PowerBot part of the summary.
    static func fetch(_ suite: SuiteAPI, since: Double) async throws -> JSON {
        let millis = String(Int64(since * 1000))
        if suite.features.contains("briefing-v1") { return try await suite.request("briefing", query: ["since": millis], timeout: 10) }
        guard suite.features.contains("powerbot-v1"), suite.can("data.powerbot") else { return .object([:]) }
        let old = try await suite.request("powerbot", query: ["after": millis], timeout: 10)
        return .object(["since": .number(since * 1000), "powerbot": .object(["mutes": old["mutes"], "conflicts": old["recent"], "open": old["open"]])])
    }
}

/// The summary card. `home` opens tools through the launcher; inside the Admin Suite it pushes them directly.
struct SuiteBriefingCard: View {
    let suite: SuiteAPI
    var title = ""
    var home = false
    @ObservedObject private var store = SuiteBriefingStore.shared
    @Environment(\.scenePhase) private var scene

    var body: some View {
        Group {
            if let pending = store.pending[suite.connection.id] { card(pending) }
            else { Color.clear.frame(height: 0).accessibilityHidden(true) }
        }
        .task(id: scene) {
            if scene == .background { store.markLeft([suite.connection.id]); return }
            if scene == .active { await store.refresh(suite) }
        }
    }

    private func card(_ pending: SuiteBriefingStore.Pending) -> some View {
        let sections = store.sections
        let tiles = SuiteBriefingModel.tiles(pending.data, sections: sections).filter { $0.value > 0 }
        let since = Date(timeIntervalSince1970: pending.since)
        return VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: Calendar.berlin.component(.hour, from: Date()) < 11 ? "sun.horizon.fill" : "clock.arrow.circlepath")
                    .font(.title2).foregroundStyle(.white).frame(width: 46, height: 46)
                    .background(LinearGradient(colors: [Color.orange, Color.panelAccent], startPoint: .topLeading, endPoint: .bottomTrailing), in: RoundedRectangle(cornerRadius: 13))
                VStack(alignment: .leading, spacing: 3) {
                    Text("Seit deinem letzten Besuch").font(.headline)
                    Text([title, SuiteRelativeTime.text(since)].filter { !$0.isEmpty }.joined(separator: " · ")).font(.caption).foregroundStyle(.secondary)
                }
                Spacer(minLength: 0)
                notificationButton
                Button { store.dismiss(suite.connection.id) } label: { Image(systemName: "xmark").font(.caption.bold()).padding(8).background(.thinMaterial, in: Circle()) }
                    .buttonStyle(.plain).accessibilityLabel("Rückblick ausblenden")
            }
            Text(SuiteBriefingModel.headline(pending.data, sections: sections)).font(.subheadline.weight(.medium))
            if !tiles.isEmpty {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 104), spacing: 8)], spacing: 8) {
                    ForEach(tiles) { tile in link(tile.tool) { SuiteBriefingTileView(tile: tile) } }
                }
            }
            let names = pending.data["players"]["newNames"].array.map(\.string)
            if sections.contains("players") && !names.isEmpty {
                Label("Neu dabei: " + names.joined(separator: ", "), systemImage: "hand.wave.fill").font(.caption).foregroundStyle(.secondary)
            }
            ForEach(pending.data["powerbot"]["highlights"].array.prefix(sections.contains("powerbot") ? 3 : 0), id: \.["id"].string) { row in
                link(.powerBot) { SuiteBriefingHighlight(symbol: PowerBotPresentation.symbol(row["kind"].string), tint: PowerBotPresentation.tint(row["kind"].string, severity: row["severity"].string),
                                                         title: PowerBotPresentation.title(row["kind"].string) + (row["name"].string.isEmpty ? "" : " · " + row["name"].string), detail: row["detail"].string, at: row["at"]) }
            }
            link(.briefing) { Label("Ganzen Rückblick öffnen", systemImage: "chevron.right.circle.fill").font(.subheadline.weight(.semibold)).foregroundStyle(Color.panelAccent) }
        }
        .frame(maxWidth: .infinity, alignment: .leading).card()
        .transition(.move(edge: .top).combined(with: .opacity))
        .accessibilityIdentifier("suite-briefing-card")
    }

    @ViewBuilder private var notificationButton: some View {
        let tool: SuiteTool = suite.can("action.notifications.update") && suite.features.contains("events-v1") ? .bark : .events
        link(tool) { Image(systemName: "bell.badge.fill").font(.caption.bold()).foregroundStyle(Color.panelAccent).padding(8).background(.thinMaterial, in: Circle()) }
            .accessibilityLabel("Benachrichtigungen der Admin Suite")
    }

    @ViewBuilder private func link<Content: View>(_ tool: SuiteTool, @ViewBuilder label: () -> Content) -> some View {
        let content = label()
        if home {
            Button { SuiteNavigationInbox.shared.request = SuiteLaunchRequest(tool: tool, connection: suite.connection.id) } label: { content }.buttonStyle(.plain)
        } else {
            NavigationLink { SuiteToolDestination(suite: suite, tool: tool) } label: { content }.buttonStyle(.plain)
        }
    }
}

struct SuiteBriefingTileView: View {
    let tile: SuiteBriefingModel.Tile
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Image(systemName: tile.symbol).font(.caption).foregroundStyle(tile.tint)
            Text("\(tile.value)").font(.title3.bold().monospacedDigit()).foregroundStyle(.primary)
            Text(tile.title).font(.caption2).foregroundStyle(.secondary).lineLimit(1)
        }.frame(maxWidth: .infinity, alignment: .leading).padding(10).background(tile.tint.opacity(0.10), in: RoundedRectangle(cornerRadius: 12))
    }
}

struct SuiteBriefingHighlight: View {
    let symbol: String
    let tint: Color
    let title: String
    let detail: String
    let at: JSON
    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: symbol).foregroundStyle(tint).frame(width: 22)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.subheadline.weight(.semibold)).foregroundStyle(.primary).lineLimit(1)
                if !detail.isEmpty { Text(detail).font(.caption).foregroundStyle(.secondary).lineLimit(2) }
            }
            Spacer(minLength: 0)
            if let date = SuitePlayerPresentation.date(at) { Text(SuiteRelativeTime.text(date)).font(.caption2).foregroundStyle(.secondary) }
        }.contentShape(Rectangle())
    }
}

/// Start screen: one card per connection with news since the last visit.
struct SuiteHomeBriefing: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.scenePhase) private var scene
    @ObservedObject private var connections = SuiteConnections.shared
    @ObservedObject private var preferences = SuiteQuickAccess.shared
    @ObservedObject private var briefings = SuiteBriefingStore.shared
    @State private var clients: [UUID: SuiteAPI] = [:]

    private var entries: [SuiteConnection] { connections.values.filter { !preferences.isHidden($0.id) } }

    var body: some View {
        VStack(spacing: 16) {
            ForEach(entries.filter { briefings.pending[$0.id] != nil && clients[$0.id] != nil }) { record in
                if let suite = clients[record.id] { SuiteBriefingCard(suite: suite, title: preferences.name(record, store: store), home: true) }
            }
        }
        .task(id: [String(describing: scene)] + entries.map(\.id.uuidString)) {
            if scene == .background { briefings.markLeft(entries.map(\.id)); return }
            guard scene == .active, !SuiteShortcutDemo.enabled else { return }
            for record in entries where briefings.pending[record.id] == nil {
                guard !Task.isCancelled, briefings.enabled else { return }
                guard SuiteBriefingModel.shouldShow(lastVisit: briefings.lastVisit(record.id), now: Date().timeIntervalSince1970, thresholdHours: briefings.threshold) else {
                    if briefings.lastVisit(record.id) == 0 { briefings.markLeft([record.id]) }
                    continue
                }
                guard let api = try? SuiteAPI(record, timeout: 8), (try? await api.health(timeout: 8)) != nil else { continue }
                clients[record.id] = api
                await briefings.refresh(api)
            }
        }
    }
}

/// The full overview with settings. Opened from the card, the tool directory or a Shortcut.
struct SuiteBriefingView: View {
    let suite: SuiteAPI
    @AppStorage("briefing.enabled") private var enabled = true
    @AppStorage("briefing.thresholdHours") private var threshold = 3.0
    @AppStorage("briefing.sections") private var sectionsText = "players,powerbot,events"
    @State private var range = "visit"
    @State private var data: JSON = .null
    @State private var error: String?
    var sections: Set<String> { Set(sectionsText.split(separator: ",").map(String.init)) }
    var since: Double {
        let now = Date().timeIntervalSince1970
        switch range {
        case "day": return now - 86_400
        case "week": return now - 604_800
        default:
            let pending = SuiteBriefingStore.shared.pending[suite.connection.id]?.since ?? SuiteBriefingStore.shared.lastVisit(suite.connection.id)
            return pending > 0 ? pending : now - 86_400
        }
    }

    var body: some View {
        List {
            Section {
                Picker("Zeitraum", selection: $range) {
                    Text("Letzter Besuch").tag("visit")
                    Text("24 Std.").tag("day")
                    Text("7 Tage").tag("week")
                }.pickerStyle(.segmented)
                Text(SuiteBriefingModel.headline(data, sections: sections)).font(.headline)
                Text("Seit " + SuiteRelativeTime.absolute(Date(timeIntervalSince1970: since)) + " Uhr").font(.caption).foregroundStyle(.secondary)
                let tiles = SuiteBriefingModel.tiles(data, sections: sections)
                if !tiles.isEmpty {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 104), spacing: 8)], spacing: 8) {
                        ForEach(tiles) { tile in NavigationLink { SuiteToolDestination(suite: suite, tool: tile.tool) } label: { SuiteBriefingTileView(tile: tile) }.buttonStyle(.plain) }
                    }.padding(.vertical, 4)
                }
            }
            let names = data["players"]["newNames"].array.map(\.string)
            if sections.contains("players") && !names.isEmpty {
                Section("Neue Spieler") { ForEach(names, id: \.self) { Label($0, systemImage: "person.badge.plus") } }
            }
            let bot = data["powerbot"]["highlights"].array
            if sections.contains("powerbot") && !bot.isEmpty {
                Section("PowerBot") {
                    ForEach(bot, id: \.["id"].string) { row in
                        NavigationLink { SuitePowerBotCaseView(suite: suite, initial: row) } label: {
                            SuiteBriefingHighlight(symbol: PowerBotPresentation.symbol(row["kind"].string), tint: PowerBotPresentation.tint(row["kind"].string, severity: row["severity"].string),
                                                   title: PowerBotPresentation.title(row["kind"].string) + (row["name"].string.isEmpty ? "" : " · " + row["name"].string), detail: row["detail"].string, at: row["at"])
                        }
                    }
                    NavigationLink { SuitePowerBotView(suite: suite) } label: { Label("PowerBot-Zentrale öffnen", systemImage: "shield.lefthalf.filled") }
                }
            }
            let events = data["events"]["highlights"].array
            if sections.contains("events") && !events.isEmpty {
                Section("Ereignisse") {
                    ForEach(events, id: \.["id"].string) { row in
                        SuiteBriefingHighlight(symbol: row["critical"].bool ? "exclamationmark.triangle.fill" : "bell.fill", tint: row["critical"].bool ? .orange : Color.panelAccent,
                                               title: row["title"].string, detail: "", at: row["at"])
                    }
                    NavigationLink { SuiteToolDestination(suite: suite, tool: .events) } label: { Label("Alle Ereignisse", systemImage: "bell.badge") }
                }
            }
            Section {
                Toggle("Rückblick beim Öffnen zeigen", isOn: $enabled)
                Picker("Erst nach Abwesenheit von", selection: $threshold) { ForEach(SuiteBriefingModel.thresholds, id: \.0) { Text($0.1).tag($0.0) } }
                ForEach(SuiteBriefingModel.sectionChoices, id: \.0) { key, title in
                    Toggle(title, isOn: Binding(get: { sections.contains(key) }, set: { on in
                        var next = sections; if on { next.insert(key) } else { next.remove(key) }
                        sectionsText = SuiteBriefingModel.sectionChoices.map(\.0).filter(next.contains).joined(separator: ",")
                    }))
                }
                if suite.can("action.notifications.update") && suite.features.contains("events-v1") {
                    NavigationLink { SuiteToolDestination(suite: suite, tool: .bark) } label: { Label("Push-Benachrichtigungen (Bark)", systemImage: "bell.badge.fill") }
                }
            } header: { Text("Einstellungen") } footer: { Text("Der Rückblick erscheint auf der Startseite und in der Admin Suite, wenn seit deinem letzten Besuch etwas passiert ist.") }
        }
        .panelScreen().navigationTitle("Rückblick")
        .task(id: range) { await load() }
        .refreshable { await load() }
        .errorAlert($error)
    }

    private func load() async {
        do { data = try await SuiteBriefingStore.fetch(suite, since: since) }
        catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }
}
