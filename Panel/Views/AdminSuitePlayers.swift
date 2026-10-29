import SwiftUI

struct SuitePlayerRow: View {
    let row: JSON
    var suite: SuiteAPI? = nil
    @State private var resolved: PlayerPlatform?
    @ObservedObject private var bookmarks = SuitePlayerBookmarks.shared
    var platform: PlayerPlatform { resolved ?? PlayerPlatform.resolve(row) }
    var favorite: Bool { suite.map { bookmarks.isFavorite($0.bookmarkScope, row["id"].string) } ?? false }
    var body: some View {
        HStack(spacing: 12) {
            SuitePlayerAvatar(row: row, size: 36, platformOverride: platform)
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 5) {
                    Text(row["name"].string).font(.headline).fixedSize(horizontal: false, vertical: true)
                    if favorite { Image(systemName: "star.fill").font(.caption2).foregroundStyle(.yellow).accessibilityLabel("Favorit") }
                }
                Text(platform.title).font(.subheadline).foregroundStyle(.secondary)
                if platform != .java { Text(SuitePlayerPresentation.device(row)).font(.caption).foregroundStyle(.secondary).lineLimit(nil).fixedSize(horizontal: false, vertical: true) }
                if !row["online"].bool, let date = SuitePlayerPresentation.date(row["lastSeen"]) { Text("Zuletzt online: " + date.formatted(.relative(presentation: .named))).font(.caption).foregroundStyle(.secondary) }
                SuitePlayerBadges(row: row)
            }
            Spacer(minLength: 4)
            Label(row["online"].bool ? "Online" : "Offline", systemImage: "circle.fill").labelStyle(.iconOnly).font(.caption2).foregroundStyle(row["online"].bool ? Color.green : .secondary.opacity(0.5))
        }.padding(.vertical, 3).task(id: row["id"].string + "|" + row["device"].string) {
            resolved = nil
            if PlayerPlatform.resolve(row) == .unknown, row["client"].string.uppercased() == "BEDROCK", let suite {
                let value = await suite.platforms.platform(row["id"].string, suite: suite)
                guard !Task.isCancelled else { return }; resolved = value
            }
        }
    }
}
/// Prepared suite action opened from swipe actions or the profile action bar.
struct SuiteQuickAction: Identifiable {
    let id = UUID()
    let action: SuiteAction
    let preset: [String: JSON]
    var title: String
}
struct SuiteQuickActionSheet: View {
    let suite: SuiteAPI
    let item: SuiteQuickAction
    var done: () -> Void
    var body: some View {
        NavigationStack { SuiteActionView(suite: suite, action: item.action, preset: item.preset).navigationTitle(item.title).toolbar { ToolbarItem(placement: .confirmationAction) { Button("Fertig") { done() } } } }
    }
}
enum SuitePlayerQuickActions {
    /// Builds the moderation shortcuts that the connected suite actually offers.
    static func make(_ actions: [SuiteAction], player: String, row: JSON) -> [(title: String, symbol: String, role: ButtonRole?, item: SuiteQuickAction)] {
        var result: [(title: String, symbol: String, role: ButtonRole?, item: SuiteQuickAction)] = []
        func add(_ id: String, _ title: String, _ symbol: String, _ preset: [String: JSON], role: ButtonRole? = nil) {
            guard let action = actions.first(where: { $0.id == id }) else { return }
            var values = preset; values["player"] = .string(player)
            result.append((title, symbol, role, SuiteQuickAction(action: action, preset: values, title: title)))
        }
        add("player.message", "Nachricht", "envelope.fill", [:])
        if row["online"].bool { add("player.punish", "Kicken", "rectangle.portrait.and.arrow.right", ["type": .string("kick")], role: .destructive) }
        add("player.punish", "Verwarnen", "exclamationmark.triangle.fill", ["type": .string("warn")])
        if row["mute"].bool { add("player.punish", "Entmuten", "speaker.wave.2.fill", ["type": .string("unmute")]) }
        else { add("player.punish", "Zeitweise muten", "speaker.slash.fill", ["type": .string("tempmute")]) }
        if row["ban"].bool { add("player.punish", "Entbannen", "lock.open.fill", ["type": .string("unban")]) }
        else { add("player.punish", "Zeitbann", "clock.badge.xmark", ["type": .string("tempban")], role: .destructive); add("player.punish", "Bannen", "hand.raised.fill", ["type": .string("ban")], role: .destructive) }
        add("player.notes", "Notiz", "note.text.badge.plus", ["operation": .string("add")])
        return result
    }
}
struct SuitePlayersView: View {
    let suite: SuiteAPI
    let panel: PanelAPI
    let server: Server
    let actions: [SuiteAction]
    var initialFilter: SuitePlayerFilter = .all
    @ObservedObject private var directory: SuiteDirectory
    @ObservedObject private var bookmarks = SuitePlayerBookmarks.shared
    @State private var search = ""
    @State private var lastSelection = ""
    @State private var filter: SuitePlayerFilter
    @State private var quick: SuiteQuickAction?
    init(suite: SuiteAPI, panel: PanelAPI, server: Server, actions: [SuiteAction], initialFilter: SuitePlayerFilter = .all) {
        self.suite = suite; self.panel = panel; self.server = server; self.actions = actions
        // Older suites reject the 9.3.0 filters; fall back to the full list instead of failing.
        let supported = SuitePlayerFilter.primary.contains(initialFilter) || suite.features.contains("players-filters-v3")
        self.initialFilter = supported ? initialFilter : .all
        _filter = State(initialValue: supported ? initialFilter : .all); _directory = ObservedObject(wrappedValue: suite.playerDirectory)
    }
    var visible: [JSON] { directory.rows.filter { filter.includes($0) } }
    var favorites: [SuitePlayerBookmarks.Entry] { bookmarks.favorites(suite.bookmarkScope) }
    var body: some View {
        List {
            Section {
                Picker("Spieler anzeigen", selection: $filter) { ForEach(SuitePlayerFilter.primary) { Text($0.rawValue).tag($0) } }.pickerStyle(.segmented)
                if suite.features.contains("players-filters-v3") { extendedFilters }
                LabeledContent("Gefundene Profile", value: String(directory.total))
                Text("Gezielte Suche · 50 Profile pro Seite. Wische über einen Spieler für Schnellaktionen.").font(.footnote).foregroundStyle(.secondary)
            }
            if search.isEmpty && !favorites.isEmpty && filter == .all {
                Section("Favoriten") {
                    ForEach(favorites) { entry in
                        NavigationLink { SuitePlayerView(suite: suite, panel: panel, server: server, player: entry.id, actions: actions) } label: { Label(entry.name, systemImage: "star.fill").labelStyle(SuiteFavoriteLabelStyle()) }
                            .swipeActions { Button("Entfernen", systemImage: "star.slash") { bookmarks.toggleFavorite(suite.bookmarkScope, id: entry.id, name: entry.name) } }
                    }
                }
            }
            Section(filter.sectionTitle) {
                ForEach(visible, id: \.["id"].string) { row in playerRow(row) }
                if directory.busy { ProgressView("Spieler laden …") }
                if visible.isEmpty && !directory.busy { ContentUnavailableView("Keine Spieler gefunden", systemImage: "person.crop.circle.badge.questionmark", description: Text("Suche nach einem Namen oder wechsle den Filter.")) }
                if directory.next >= 0 && !directory.busy { Button("Weitere Spieler laden") { Task { await load(reset: false) } } }
            }
        }.labelStyle(SuiteWrappingLabelStyle()).panelScreen().navigationTitle("Spieler").searchable(text: $search, prompt: "Name oder UUID").textInputAutocapitalization(.never).autocorrectionDisabled()
            .task(id: filter.rawValue + "|" + search) { do { if !search.isEmpty { try await Task.sleep(for: .milliseconds(300)) }; try Task.checkCancellation(); let selection = filter.rawValue + "|" + search; let freshSearch = !search.isEmpty && selection != lastSelection; lastSelection = selection; await load(reset: true, force: freshSearch) } catch {} }
            .refreshable { await load(reset: true, force: true) }.toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Neu laden", systemImage: "arrow.clockwise") { Task { await load(reset: true, force: true) } }.disabled(directory.busy) } }.errorAlert($directory.error)
            .sheet(item: $quick, onDismiss: { Task { await load(reset: true, force: true) } }) { item in SuiteQuickActionSheet(suite: suite, item: item) { quick = nil } }
    }
    func playerRow(_ row: JSON) -> some View {
        let quickActions = SuitePlayerQuickActions.make(actions, player: row["id"].string, row: row)
        let swipe = quickActions.filter { ["Nachricht", "Kicken", "Verwarnen"].contains($0.title) }
        let favorite = bookmarks.isFavorite(suite.bookmarkScope, row["id"].string)
        return NavigationLink { SuitePlayerView(suite: suite, panel: panel, server: server, player: row["id"].string, actions: actions, initialProfile: row) } label: { SuitePlayerRow(row: row, suite: suite) }
            .swipeActions(edge: .leading) {
                Button(favorite ? "Favorit entfernen" : "Favorit", systemImage: favorite ? "star.slash" : "star.fill") { bookmarks.toggleFavorite(suite.bookmarkScope, id: row["id"].string, name: row["name"].string) }.tint(.yellow)
            }
            .swipeActions(edge: .trailing) {
                ForEach(Array(swipe.enumerated()), id: \.offset) { _, entry in
                    Button(entry.title, systemImage: entry.symbol) { quick = entry.item }.tint(Self.swipeTint(entry.title))
                }
            }
            .contextMenu {
                Button("Spielername kopieren", systemImage: "doc.on.doc") { Clipboard.copy(row["name"].string) }
                Button("UUID kopieren", systemImage: "number") { Clipboard.copy(row["id"].string) }
                ForEach(Array(quickActions.enumerated()), id: \.offset) { _, entry in Button(entry.title, systemImage: entry.symbol, role: entry.role) { quick = entry.item } }
            }
    }
    static func swipeTint(_ title: String) -> Color { switch title { case "Kicken": return .red; case "Verwarnen": return .orange; default: return .blue } }
    var extendedFilters: some View {
        HStack(spacing: 8) {
            ForEach(SuitePlayerFilter.extended) { option in
                let selected = filter == option
                Button { filter = selected ? .all : option } label: {
                    Label(option.rawValue, systemImage: option.symbol).font(.subheadline.weight(.medium)).padding(.horizontal, 10).padding(.vertical, 6)
                        .background(selected ? Color.panelAccent.opacity(0.22) : Color.secondary.opacity(0.10), in: Capsule())
                }.buttonStyle(.plain).accessibilityIdentifier("player-filter-" + option.parameter).accessibilityAddTraits(selected ? .isSelected : [])
            }
            Spacer()
        }
    }
    func load(reset: Bool, force: Bool = false) async { await directory.load(suite: suite, query: search, filter: filter, reset: reset, force: force) }
}
struct SuiteFavoriteLabelStyle: LabelStyle {
    func makeBody(configuration: Configuration) -> some View { HStack(spacing: 10) { configuration.icon.foregroundStyle(.yellow); configuration.title } }
}
struct SuitePlayerView: View {
    let suite: SuiteAPI
    let panel: PanelAPI
    let server: Server
    let player: String
    let actions: [SuiteAction]
    var initialProfile: JSON = .null
    @State private var profile: JSON = .null
    @State private var error: String?
    @State private var automatic = true
    @State private var vpnSheet = false
    @State private var updated: Date?
    @State private var resolved: PlayerPlatform?
    @State private var quick: SuiteQuickAction?
    @ObservedObject private var bookmarks = SuitePlayerBookmarks.shared
    var current: JSON { profile == .null ? initialProfile : profile }
    var favorite: Bool { bookmarks.isFavorite(suite.bookmarkScope, player) }
    var live: JSON { current["live"] }
    var name: String { current["name"].string.isEmpty ? player : current["name"].string }
    var body: some View {
        List {
            Section {
                HStack(spacing: 14) {
                    SuitePlayerAvatar(row: current, size: 56, platformOverride: resolved ?? PlayerPlatform.resolve(current))
                    VStack(alignment: .leading, spacing: 4) {
                        Text(name).font(.title2.bold()).textSelection(.enabled)
                        Label(current["online"].bool ? "Online" : "Offline", systemImage: "circle.fill").font(.caption).foregroundStyle(current["online"].bool ? .green : .secondary)
                        if !current["staff"].string.isEmpty { Label(current["staff"].string, systemImage: "person.badge.shield.checkmark.fill").font(.caption).foregroundStyle(Color.panelAccent) }
                        SuitePlayerBadges(row: current)
                    }
                }.padding(.vertical, 4)
                CopyButton(text: name, title: "Spielername kopieren")
                CopyButton(text: player, title: "UUID kopieren")
                LabeledContent("Client", value: current["client"].string)
                if !current["brand"].string.isEmpty { SuiteDetailRow(title: "Client-Brand (Selbstangabe)", value: current["brand"].string) }
                LabeledContent("Plattform", value: (resolved ?? PlayerPlatform.resolve(current)).title)
                SuiteDetailRow(title: current["online"].bool ? "Aktuelles Gerät" : "Letztes Gerät", value: SuitePlayerPresentation.device(current)).accessibilityIdentifier("player-device")
                if let date = SuitePlayerPresentation.date(current["lastSeen"]), !current["online"].bool { LabeledContent("Zuletzt online") { Text(SuiteGermanTime.text(date)) } }
                if !current["version"].string.isEmpty { LabeledContent("Spielversion", value: current["version"].string) }
                LabeledContent("Simplex Points", value: current["points"].string)
                LabeledContent("Warnungen", value: current["warnings"].string)
            }
            quickActionSection
            Section("Inventar & Speicherstand") {
                if current["inventoryPreview"] != .null {
                    LabeledContent("Belegte Inventarplätze", value: current["inventoryPreview"]["occupied"].string + " / 36")
                    ForEach(current["inventoryPreview"]["items"].array, id: \.["id"].string) { item in Label(item["name"].string + " × " + item["amount"].string, systemImage: "shippingbox.fill").font(.caption).foregroundStyle(.secondary) }
                }
                NavigationLink { SuiteDataListView(suite: suite, kind: "inventory", player: player, actions: actions) } label: { Label("Aktuelles Inventar & Endertruhe", systemImage: "square.grid.3x3.fill") }.disabled(!current["online"].bool)
                NavigationLink { SuiteNBTPathsView(suite: suite, panel: panel, server: server, player: player) } label: { Label("NBT-Editor öffnen", systemImage: "doc.badge.gearshape") }
                NavigationLink { SuiteSnapshotsView(suite: suite, player: player, actions: actions) } label: { Label("Inventar-Snapshots", systemImage: "archivebox") }
                if !current["online"].bool { Text("Offline-Inventar über den NBT-Editor oder einen gespeicherten Snapshot öffnen.").font(.caption).foregroundStyle(.secondary) }
            }
            if current["logoutLocation"] != .null {
                Section("Zuletzt ausgeloggt") {
                    LabeledContent("Welt", value: current["logoutLocation"]["world"].string)
                    LabeledContent("Position", value: ["x", "y", "z"].map { String(format: "%.1f", current["logoutLocation"][$0].double) }.joined(separator: " / ")).font(.caption.monospaced()).textSelection(.enabled)
                    CopyButton(text: current["logoutLocation"]["world"].string + " " + ["x", "y", "z"].map { String(format: "%.1f", current["logoutLocation"][$0].double) }.joined(separator: " / "), title: "Ausloggposition kopieren")
                }
            }
            Section("Spielerverwaltung") {
                NavigationLink("Spielerrechte & Vererbung") { SuitePermissionView(suite:suite,player:player) }
                NavigationLink("Client jetzt prüfen") { SuiteProbeView(suite:suite,player:player) }.disabled(!current["online"].bool)
            }
            Section("Netzwerk & Verknüpfungen") {
                if current["vpnWhitelisted"] != .null {
                    Toggle("VPN-Whitelist für diesen Spieler", isOn: Binding(get: { current["vpnWhitelisted"].bool }, set: { _ in vpnSheet = true })).disabled(suite.scope == "read" || suite.demo).accessibilityIdentifier("profile-vpn-whitelist")
                    Text("Die Änderung wird vor dem Speichern geprüft und bestätigt.").font(.caption).foregroundStyle(.secondary)
                }
                SuiteNetworkContent(network: current["network"])
                NavigationLink { SuiteAltDashboardView(suite: suite, panel: panel, server: server, actions: actions) } label: { Label("Alt-Account-Dashboard", systemImage: "chart.bar.xaxis") }
                if !current["network"]["ip"].string.isEmpty {
                    if current["network"]["altExcluded"].bool { Label("Diese IP wird für Alt-Verknüpfungen ignoriert", systemImage: "shield.lefthalf.filled").font(.caption).foregroundStyle(.orange) }
                    NavigationLink { SuiteAltIPsView(suite: suite, initialIP: current["network"]["ip"].string) } label: { Label("Diese IP ausschließen / verwalten", systemImage: "network") }
                }
                NavigationLink { SuiteDataListView(suite: suite, kind: "alts", player: player, actions: actions, panel: panel, server: server) } label: { Label(current["altCount"] == .null ? "Alt-Accounts & deren Profile" : "Alt-Accounts & deren Profile · \(current["altCount"].int)", systemImage: "person.2.badge.key.fill") }.accessibilityLabel("Alt-Accounts & deren Profile")
                NavigationLink { SuiteVPNPlatformView(suite: suite) } label: { Label("VPN-Plattformen & Standortdiagnose", systemImage: "network") }
            }
            Section("Schnellaktionen") {
                NavigationLink { SuiteDataListView(suite: suite, kind: "devices", player: player, actions: actions, panel: panel, server: server) } label: { Label("Alle Geräte anzeigen", systemImage: "iphone.and.arrow.right.inward") }
                if let action = actions.first(where: { $0.id == "security.ultraban" }) {
                    NavigationLink { SuiteActionView(suite: suite, action: action, preset: ["player": .string(player)]) } label: { Label("Ultrabann", systemImage: "shield.slash.fill").foregroundStyle(.red) }.accessibilityIdentifier("player-ultraban")
                }
                if let action = actions.first(where: { $0.id == "player.punish" }) {
                    NavigationLink { SuiteActionView(suite: suite, action: action, preset: ["player": .string(player)]) } label: { Label("Alle Strafen & Warnungen", systemImage: "hand.raised.fill") }
                }
            }
            if live != .null {
                Section("Live-Spielerwerte") {
                    HStack(spacing: 3) { ForEach(0..<10) { i in Image(systemName: live["health"].double / 2 >= Double(i + 1) ? "heart.fill" : live["health"].double / 2 > Double(i) ? "heart.lefthalf.filled" : "heart").foregroundStyle(.pink) } }
                    LabeledContent("Gesundheit", value: "\(live["health"].string) / \(live["maxHealth"].string)")
                    LabeledContent("Hunger", value: live["food"].string + " / 20")
                    LabeledContent("Sättigung", value: live["saturation"].string)
                    LabeledContent("XP-Level", value: live["level"].string)
                    LabeledContent("Spielmodus", value: live["gamemode"].string)
                    LabeledContent("Welt", value: live["world"].string)
                    LabeledContent("Position", value: ["x", "y", "z"].map { String(format: "%.1f", live[$0].double) }.joined(separator: " / ")).font(.caption.monospaced()).textSelection(.enabled)
                    if let action = actions.first(where: { $0.id == "player.vitals" }) { NavigationLink("Live-Werte bearbeiten") { SuiteActionView(suite: suite, action: action, preset: ["player": .string(player)]) } }
                }
            }
            moderationRecord
            Section { DisclosureGroup("Spielerakte & gespeicherte Daten") {
                ForEach([("player.info", "Vollständige Spielerakte"), ("player.identity", "Identität & Ultrabann"), ("player.history", "Aktions- und Chatverlauf")], id: \.0) { route, title in NavigationLink(title) { SuiteReadView(suite: suite, path: route, title: title, values: ["player": .string(player), "category": .string("all")]) } }
            }
            }
            Section { DisclosureGroup("Weitere Spieleraktionen") {
                ForEach(actions.filter { ["player.notes", "player.watchlist", "player.action", "player.message", "player.points", "player.nickname", "security.alt-ban", "moderation.restrict", "moderation.banmode"].contains($0.id) }) { action in NavigationLink(action.title) { SuiteActionView(suite: suite, action: action, preset: ["player": .string(player)]) } }
            }
            }
            Section {
                Toggle("Live alle 15 Sekunden aktualisieren", isOn: $automatic)
                if let updated { LabeledContent("Stand") { Text(SuiteGermanTime.text(updated)) } }
                Text("Live-Werte stammen direkt aus der Suite. NBT-Dateien sind ein separater Speicherstand. Mit Admin Suite 9 können sie bei laufendem Server gespeichert werden, wenn der Spieler offline ist.").font(.footnote).foregroundStyle(.secondary)
            }
        }.panelScreen().navigationTitle(name).navigationBarTitleDisplayMode(.inline).task(id: automatic) { await load(); for _ in 0..<12 { guard current["network"]["pending"].bool && !Task.isCancelled else { break }; do { try await Task.sleep(for: .seconds(2)) } catch { return }; await load() }; while automatic && !Task.isCancelled { do { try await Task.sleep(for: .seconds(15)) } catch { return }; await load() } }.refreshable { await load() }.errorAlert($error)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button(favorite ? "Favorit entfernen" : "Als Favorit markieren", systemImage: favorite ? "star.fill" : "star") { bookmarks.toggleFavorite(suite.bookmarkScope, id: player, name: name) }.tint(.yellow).accessibilityIdentifier("player-favorite") } }
            .sheet(item: $quick, onDismiss: { Task { await load() } }) { item in SuiteQuickActionSheet(suite: suite, item: item) { quick = nil } }
            .sheet(isPresented: $vpnSheet, onDismiss: { Task { await load() } }) { NavigationStack { SuiteActionView(suite: suite, action: .make("vpn.whitelist", "VPN-Ausnahme ändern"), preset: ["player": .string(player), "allowed": .string(current["vpnWhitelisted"].bool ? "false" : "true")]) } }
    }
    @ViewBuilder var quickActionSection: some View {
        let quickActions = SuitePlayerQuickActions.make(actions, player: player, row: current)
        if !quickActions.isEmpty {
            Section {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 10) {
                        ForEach(Array(quickActions.enumerated()), id: \.offset) { _, entry in
                            Button { quick = entry.item } label: {
                                VStack(spacing: 6) { Image(systemName: entry.symbol).font(.title3); Text(entry.title).font(.caption.weight(.medium)).lineLimit(1) }
                                    .frame(minWidth: 72).padding(.vertical, 10).padding(.horizontal, 8)
                                    .background((entry.role != nil ? Color.red : Color.panelAccent).opacity(0.12), in: RoundedRectangle(cornerRadius: 14))
                                    .foregroundStyle(entry.role != nil ? Color.red : Color.panelAccent)
                            }.buttonStyle(.plain).accessibilityIdentifier("player-quick-" + entry.title)
                        }
                    }.padding(.vertical, 2)
                }
            } header: { Text("Moderation") } footer: { Text("Jede Aktion wird vor der Ausführung von der Suite geprüft und muss bestätigt werden.") }
        }
    }
    @ViewBuilder var moderationRecord: some View {
        let warnings = current["warningEntries"].array, notes = current["notes"].array, names = current["previousNames"].array, activity = current["activity"]
        if !warnings.isEmpty || !notes.isEmpty || !names.isEmpty || activity != .null {
            Section("Moderationsakte") {
                if activity != .null {
                    LabeledContent("Spielzeit", value: Self.duration(activity["playMillis"].double))
                    LabeledContent("Sitzungen", value: activity["sessions"].string)
                    if activity["afkMillis"].double > 0 { LabeledContent("Davon AFK", value: Self.duration(activity["afkMillis"].double)) }
                }
                if let first = SuitePlayerPresentation.date(current["firstSeen"]) { LabeledContent("Erster Besuch") { Text(SuiteGermanTime.text(first)).font(.caption) } }
                if !warnings.isEmpty { DisclosureGroup("Warnungen · \(warnings.count)") { ForEach(Array(warnings.enumerated()), id: \.offset) { _, entry in Text(cleanConsole(entry.string)).font(.subheadline).textSelection(.enabled) } } }
                if !notes.isEmpty { DisclosureGroup("Teamnotizen · \(notes.count)") { ForEach(Array(notes.enumerated()), id: \.offset) { _, entry in Text(cleanConsole(entry.string)).font(.subheadline).textSelection(.enabled) } } }
                if !names.isEmpty { DisclosureGroup("Frühere Namen · \(names.count)") { ForEach(Array(names.enumerated()), id: \.offset) { _, entry in Text(entry.string).font(.subheadline).textSelection(.enabled) } } }
            }
        }
    }
    static func duration(_ millis: Double) -> String {
        guard millis > 0 else { return "–" }
        return Duration.milliseconds(millis).formatted(.units(allowed: [.days, .hours, .minutes], width: .abbreviated, maximumUnitCount: 2))
    }
    func load() async { do { profile = try await suite.data("profile", query: ["player": player]); updated = Date(); bookmarks.visited(suite.bookmarkScope, id: player, name: profile["name"].string)
        resolved = nil
        if PlayerPlatform.resolve(profile) == .unknown, profile["client"].string.uppercased() == "BEDROCK" { resolved = await suite.platforms.platform(player, suite: suite) } } catch { if !Task.isCancelled { self.error = error.localizedDescription } } }
}
struct SuiteDataListView: View {
    let suite: SuiteAPI
    let kind: String
    let player: String
    let actions: [SuiteAction]
    var panel: PanelAPI? = nil
    var server: Server? = nil
    @State private var rows: [JSON] = []
    @State private var error: String?
    @State private var loaded = false
    var title: String { kind == "alts" ? "Alt-Accounts" : kind == "inventory" ? "Inventar" : "Geräte" }
    var body: some View {
        List {
            if rows.isEmpty { if loaded { ContentUnavailableView("Keine Daten vorhanden", systemImage: "tray") } else { ProgressView() } }
            ForEach(rows, id: \.["id"].string) { row in
                NavigationLink { List { Section { 
                    HStack {
                        if kind == "devices" { PlayerPlatformIcon(platform: PlayerPlatform.resolve(row)) }
                        Text(row["name"].string).font(.headline).textSelection(.enabled)
                    }
                    if kind == "alts" {
                        if let panel, let server {
                            NavigationLink("Vollständiges Spielerprofil öffnen") { SuitePlayerView(suite: suite, panel: panel, server: server, player: row["id"].string, actions: actions) }
                        }
                        NavigationLink("Alle Geräte dieses Alt-Accounts") { SuiteDataListView(suite: suite, kind: "devices", player: row["id"].string, actions: actions, panel: panel, server: server) }
                        NavigationLink("Weitere verknüpfte Accounts") { SuiteDataListView(suite: suite, kind: "alts", player: row["id"].string, actions: actions, panel: panel, server: server) }
                        HStack { Text(row["level"].string); Spacer(); Text("\(row["score"].int) / 100").font(.headline.monospacedDigit()).foregroundStyle(row["score"].int >= 85 ? .orange : .teal) }
                        ForEach(Array(row["evidence"].array.enumerated()), id: \.offset) { _, line in Text(line.string).font(.subheadline) }
                        Text(row["safe"].bool ? "Für automatische Kaskaden als ausreichend eingestuft" : "Hinweis auf Zusammenhang; keine sichere Identität bewiesen").font(.caption).foregroundStyle(.secondary)
                    } else if kind == "inventory" {
                        LabeledContent("Bereich", value: row["container"].string); LabeledContent("Slot", value: row["slot"].string); LabeledContent("Anzahl", value: row["amount"].string)
                        if !row["lore"].array.isEmpty { ForEach(Array(row["lore"].array.enumerated()), id: \.offset) { _, value in Text(cleanConsole(value.string)).font(.caption) } }
                    } else {
                        if let date = SuitePlayerPresentation.date(row["lastSeen"]) { LabeledContent("Zuletzt verwendet") { Text(SuiteGermanTime.text(date)) } }
                        ForEach([("platform", "Plattform"), ("os", "Betriebssystem"), ("version", "Spielversion"), ("language", "Sprache"), ("input", "Eingabe")], id: \.0) { key, label in SuiteDetailRow(title: label, value: row[key].string.isEmpty ? "Nicht erfasst" : row[key].string) }
                        Label(row["blocked"].bool ? "Gesperrt" : "Nicht gesperrt", systemImage: row["blocked"].bool ? "lock.fill" : "checkmark.shield").foregroundStyle(row["blocked"].bool ? .orange : .teal)
                        ForEach(row["diagnostics"].object.keys.sorted(), id: \.self) { key in LabeledContent(key, value: row["diagnostics"][key].string).font(.caption).textSelection(.enabled) }
                        if let action = actions.first(where: { $0.id == "player.devices" }) { NavigationLink("Gerät verwalten") { SuiteActionView(suite: suite, action: action, preset: ["player": .string(player), "identifier": row["id"]]) } }
                    }
                    CopyButton(text: row["id"].string, title: kind == "alts" ? "Spieler-UUID kopieren" : "Kennung kopieren") } }.panelScreen().navigationTitle(row["name"].string) } label: {
                    HStack { if kind == "devices" { PlayerPlatformIcon(platform: PlayerPlatform.resolve(row)) }; VStack(alignment: .leading, spacing: 4) { Text(row["name"].string).font(.headline); Text(kind == "alts" ? row["level"].string : row["id"].string).font(.caption).foregroundStyle(.secondary).lineLimit(1) } }
                }

            }
        }.panelScreen().navigationTitle(title).navigationBarTitleDisplayMode(.inline).task { await load() }.refreshable { await load() }.errorAlert($error)
    }
    func load() async { do { let items = try await suite.data(kind, query: ["player": player])["items"].array; rows = kind == "devices" ? items.sorted { $0["lastSeen"].double > $1["lastSeen"].double } : items; loaded = true } catch { if !RequestFailure.isCancellation(error) && !Task.isCancelled { self.error = error.localizedDescription } } }
}
struct SuiteUltraView: View {
    let suite: SuiteAPI
    let actions: [SuiteAction]
    @State private var rows: [JSON] = []
    @State private var search = ""
    @State private var next = -1
    @State private var error: String?
    @State private var revision = UUID()
    @State private var busy = false
    var body: some View {
        List {
            Section { if let action = actions.first(where: { $0.id == "security.ultraunban" }) { NavigationLink { SuiteActionView(suite: suite, action: action) } label: { Label("Gesamten Ultrabann aufheben", systemImage: "lock.open.fill") } }; Text("Ein zugehöriger Spielername oder die Gruppen-ID genügt. Die Suite hebt die zugehörige Gruppe über ihre vorhandene Ultrabannverwaltung auf.").font(.footnote).foregroundStyle(.secondary) }
            ForEach(rows, id: \.["id"].string) { row in
                Section {
                    HStack { Text(row["name"].string).font(.headline); Spacer(); Text(row["active"].bool ? "Aktiv" : "Aufgehoben / abgelaufen").font(.caption).foregroundStyle(row["active"].bool ? .orange : .secondary) }
                    Text(row["reason"].string).textSelection(.enabled)
                    LabeledContent("Modus", value: row["mode"].string)
                    LabeledContent("Blockierte Versuche", value: row["attempts"].string)
                    DisclosureGroup("Zugehörige Accounts · \(row["members"].array.count)") { ForEach(row["members"].array, id: \.["id"].string) { member in VStack(alignment: .leading) { Text(member["name"].string); Text(member["relation"].string).font(.caption).foregroundStyle(.secondary) }.textSelection(.enabled) } }
                    CopyButton(text: row["id"].string, title: "Gruppen-ID kopieren")
                    if !row["active"].bool { NavigationLink("Abgelaufene Gruppe endgültig löschen") { SuiteActionView(suite:suite,action:.make("management.apply","Inaktive Gruppe löschen"),preset:["operation":.string("ultra-delete"),"player":row["id"]]) } }
                    if row["active"].bool, let action = actions.first(where: { $0.id == "security.ultraunban" }) { NavigationLink("Diese Gruppe aufheben") { SuiteActionView(suite: suite, action: action, preset: ["target": row["id"]]) } }
                }
            }
            if next >= 0 { Button("Weitere Gruppen laden") { Task { await load(reset: false) } }.disabled(busy) }
            if busy { ProgressView() }
        }.panelScreen().navigationTitle("Ultrabanns").searchable(text: $search, prompt: "Spieler oder Gruppen-ID").task(id: search) { do { try await Task.sleep(for: .milliseconds(250)); await load(reset: true) } catch {} }.refreshable { await load(reset: true) }.errorAlert($error)
    }
    func load(reset: Bool) async { if busy && !reset { return }; let stamp = UUID(); revision = stamp; busy = true; defer { if revision == stamp { busy = false } }; do { let response = try await suite.data("ultrabans", query: ["q": search, "offset": reset ? "0" : String(next)]); guard revision == stamp && !Task.isCancelled else { return }; rows = reset ? response["items"].array : rows + response["items"].array; next = response["next"].int } catch { if revision == stamp && !Task.isCancelled { self.error = error.localizedDescription } } }
}
struct SuiteSnapshotsView: View {
    let suite: SuiteAPI
    let player: String
    let actions: [SuiteAction]
    @State private var rows: [JSON] = []
    @State private var error: String?
    var body: some View {
        List {
            if let action = actions.first(where: { $0.id == "player.snapshots" }) { Section { NavigationLink("Neuen Snapshot erstellen") { SuiteActionView(suite: suite, action: action, preset: ["player": .string(player), "operation": .string("create")]) } } }
            ForEach(rows, id: \.["id"].string) { row in Section { Text(cleanConsole(row["name"].string)).font(.headline); LabeledContent("Größe", value: bytes(row["size"].double)); CopyButton(text: row["id"].string, title: "Dateiname kopieren"); NavigationLink("Inhalt ansehen") { SuiteSnapshotContentView(suite: suite, player: player, file: row["id"].string) }; if let action = actions.first(where: { $0.id == "player.snapshots" }) { NavigationLink("Wiederherstellen oder löschen") { SuiteActionView(suite: suite, action: action, preset: ["player": .string(player), "file": row["id"], "operation": .string("restore")]) } } } }
            if rows.isEmpty { Text("Noch keine Snapshots geladen oder gespeichert.").foregroundStyle(.secondary) }
        }.panelScreen().navigationTitle("Snapshots").task { await load() }.refreshable { await load() }.errorAlert($error)
    }
    func load() async { do { rows = try await suite.data("snapshots", query: ["player": player])["items"].array } catch { if !RequestFailure.isCancellation(error) && !Task.isCancelled { self.error = error.localizedDescription } } }
}
struct SuiteSnapshotContentView: View {
    let suite: SuiteAPI
    let player: String
    let file: String
    @State private var data: JSON = .null
    @State private var error: String?
    var body: some View { List { Section { ForEach([("level", "XP-Level"), ("food", "Hunger"), ("health", "Gesundheit"), ("gamemode", "Spielmodus"), ("actor", "Erstellt von")], id: \.0) { key, title in LabeledContent(title, value: data[key].string) } }; ForEach(data["items"].array, id: \.["id"].string) { item in Section { HStack { Image(systemName: "shippingbox.fill").foregroundStyle(Color.panelAccent); Text(cleanConsole(item["name"].string)); Spacer(); Text("×" + item["amount"].string).monospacedDigit() }; LabeledContent(item["container"].string, value: "Slot " + item["slot"].string); ForEach(Array(item["lore"].array.enumerated()), id: \.offset) { _, text in Text(cleanConsole(text.string)).font(.caption) } } } }.panelScreen().navigationTitle("Snapshot-Inhalt").task { await load() }.refreshable { await load() }.errorAlert($error) }
    func load() async { do { data = try await suite.data("snapshot-content", query: ["player": player, "file": file]) } catch { if !RequestFailure.isCancellation(error) && !Task.isCancelled { self.error = error.localizedDescription } } }
}
struct SuiteNBTPathsView: View {
    let suite: SuiteAPI
    let panel: PanelAPI
    let server: Server
    let player: String
    @State private var rows: [JSON] = []
    @State private var editor: EditorItem?
    @State private var panelOnly = false
    @State private var busy = false
    @State private var error: String?
    var body: some View {
        List {
            Section {
                Text("Direkte UUID-Suche in players/data und playerdata. Die Suite sichert vor Änderungen die Originaldatei und prüft Offline-Status sowie Dateikonflikte. Bei einem Fund ausschließlich über die Panel-API muss zum Speichern der Server gestoppt sein.").font(.footnote).foregroundStyle(.secondary)
                CopyButton(text: player, title: "UUID kopieren")
                if busy { ProgressView("Zugehörige UUID-Datei suchen …") }
                Button("Erneut suchen", systemImage: "magnifyingglass") { Task { await load() } }.disabled(busy)
            }
            ForEach(rows, id: \.["id"].string) { row in
                Section {
                    Text(row["name"].string).font(.headline)
                    Text(row["id"].string).font(.caption.monospaced()).textSelection(.enabled)
                    if row["panelOnly"].bool { Text("Über die Panel-API gefunden · Speichern bei gestopptem Server").font(.caption).foregroundStyle(.orange) }
                    Button("Im NBT-Editor öffnen", systemImage: "doc.badge.gearshape") { open(row) }.requiresAccess(panel, server.id, "file.read-content")
                }
            }
            if rows.isEmpty && !busy { Text("Keine passende UUID.dat gefunden. Geprüft werden die Weltpfade der Suite und anschließend gezielt die Weltordner über die Panel-API.").foregroundStyle(.secondary) }
        }.panelScreen().navigationTitle("Spieler-NBT").task { await load() }.refreshable { await load() }.errorAlert($error)
            .sheet(item: $editor) { NBTEditor(api: panel, server: server, path: $0.path, suite: !panelOnly && suite.features.contains("offline-player-nbt-v1") ? suite : nil, player: player) }
    }
    func open(_ row: JSON) { panelOnly = row["panelOnly"].bool; editor = EditorItem(path: row["id"].string) }
    func load() async {
        guard !busy else { return }; busy = true; defer { busy = false }; error = nil
        do {
            var suiteError: Error?
            do { rows = try await suite.data("nbt-paths", query: ["player": player])["items"].array }
            catch { if RequestFailure.isCancellation(error) { throw error }; rows = []; suiteError = error }
            if rows.isEmpty {
                var roots = ["/world"]
                let top = (try? await panel.list("servers/\(server.id)/files/list", query: ["directory": "/"])) ?? []
                roots += top.filter { !$0["is_file"].bool && !["plugins", "logs", "cache", "libraries", "versions", "backups"].contains($0["name"].string) }.prefix(32).map { "/" + $0["name"].string }
                rows = try await PlayerNBTSearch.paths(player: player, roots: roots) { directory in
                    try await panel.list("servers/\(server.id)/files/list", query: ["directory": directory])
                }
            }
            if let preferred = rows.first { open(preferred) }
            else if let suiteError { self.error = suiteError.localizedDescription }
        } catch { if !RequestFailure.isCancellation(error) && !Task.isCancelled { self.error = error.localizedDescription } }
    }
}
struct SuiteSettingsView: View {
    let suite: SuiteAPI
    @State private var rows: [JSON] = []
    @State private var search = ""
    @State private var error: String?
    var body: some View {
        List {
            Section { Text("Vorhandene Suite-Einstellungen werden nach ihrem tatsächlichen Typ dargestellt. Zugangsschlüssel und geschützte Authentifizierung bleiben lokal. Nach Änderungen ist je nach Modul ein Suite-Reload nötig.").font(.footnote).foregroundStyle(.secondary) }
            ForEach(rows.filter { search.isEmpty || ($0["name"].string + $0["id"].string).localizedCaseInsensitiveContains(search) }, id: \.["id"].string) { row in NavigationLink { SuiteSettingEditor(suite: suite, setting: row) } label: { VStack(alignment: .leading, spacing: 5) { HStack { Text(row["name"].string); Spacer(); if row["kind"].string == "bool" { Image(systemName: row["value"].bool ? "checkmark.circle.fill" : "circle").foregroundStyle(row["value"].bool ? .teal : .secondary) } }; Text(row["id"].string).font(.caption).foregroundStyle(.secondary); if row["kind"].string != "bool" { Text(row["value"].array.isEmpty ? row["value"].string : "\(row["value"].array.count) Einträge").font(.caption).lineLimit(nil).fixedSize(horizontal: false, vertical: true) } } } }
        }.panelScreen().navigationTitle("Suite-Einstellungen").searchable(text: $search).task { await load() }.refreshable { await load() }.errorAlert($error)
    }
    func load() async { do { rows = try await suite.data("settings")["items"].array } catch { if !RequestFailure.isCancellation(error) && !Task.isCancelled { self.error = error.localizedDescription } } }
}
struct SuiteSettingEditor: View {
    let suite: SuiteAPI
    let setting: JSON
    @State private var value = ""
    @State private var toggle = false
    @State private var apply = false
    @State private var initialized = false
    var body: some View {
        Form { Section { Text(setting["name"].string).font(.headline); Text(setting["hint"].string).font(.footnote).foregroundStyle(.secondary); Text(setting["id"].string).font(.caption.monospaced()).textSelection(.enabled) }; Section("Wert") { if setting["id"].string.hasPrefix("discord-bot.") && setting["id"].string.hasSuffix("channel-id") { SuiteDiscordChannelPicker(suite: suite, value: $value) } else if setting["kind"].string == "bool" { Toggle("Aktiv", isOn: $toggle) } else { TextField("Wert", text: $value, axis: .vertical).textInputAutocapitalization(.never).autocorrectionDisabled(); if setting["kind"].string == "list" { Text("Listeneinträge mit | trennen.").font(.caption).foregroundStyle(.secondary) } } }; Section { Button("Änderung prüfen") { apply = true }.disabled(suite.scope != "full" || suite.demo) } }.panelScreen().navigationTitle("Einstellung ändern").task { guard !initialized else { return }; initialized = true; value = setting["kind"].string == "list" ? setting["value"].array.map(\.string).joined(separator: " | ") : setting["value"].string; toggle = setting["value"].bool }
            .sheet(isPresented: $apply) { NavigationStack { SuiteActionView(suite: suite, action: .make("settings.update", "Suite-Einstellung speichern"), preset: ["expected": setting["revision"], "key": setting["id"], "value": .string(setting["kind"].string == "bool" ? String(toggle) : value)]) } }
    }
}
