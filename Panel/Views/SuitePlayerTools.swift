import SwiftUI

/// 5.16.1: one tool of the player profile. The grid only sets the selection; the profile owns a single
/// `navigationDestination`. Several NavigationLinks inside one List row made iOS push every link of the row
/// after one tap (all screens opened at once), so tiles are plain buttons now.
enum SuitePlayerTool: String, Hashable, Identifiable, CaseIterable {
    case inventory, legacyInventory, snapshots, nbt, dossier, timeline, community, devices
    case rights, legacyRights, commands, alts, powerBot, punish, chatSilence, antiLag, probe
    var id: String { rawValue }
}

/// 5.16.0/5.16.1: every player tool in one Liquid Glass grid. This is the only place in the profile that opens
/// these tools – the old link lists further down were removed so nothing exists twice.
struct SuitePlayerToolGrid: View {
    let suite: SuiteAPI
    let player: String
    let name: String
    let current: JSON
    let actions: [SuiteAction]
    @Binding var selection: SuitePlayerTool?

    private var online: Bool { current["online"].bool }
    private var columns: [GridItem] { [GridItem(.adaptive(minimum: 128), spacing: 10)] }

    var body: some View {
        Section {
            GlassEffectContainer(spacing: 10) {
                LazyVGrid(columns: columns, spacing: 10) {
                    ForEach(tiles, id: \.tool) { tile in
                        SuiteGlassTile(title: tile.title, detail: tile.detail, symbol: tile.symbol, tint: tile.tint, enabled: tile.enabled) { selection = tile.tool }
                            .accessibilityIdentifier("player-tool-" + tile.tool.rawValue)
                    }
                }
            }
            .listRowBackground(Color.clear)
            .listRowInsets(EdgeInsets(top: 6, leading: 0, bottom: 6, trailing: 0))
            if current["chatSilence"] != .null || current["personalMutes"] != .null { statusRow }
        } header: { Text("Werkzeuge") } footer: { Text("Alles für \(name) an einem Ort. Gedimmte Werkzeuge brauchen ein zusätzliches Recht.") }
    }

    struct Tile { let tool: SuitePlayerTool; let title: String; let detail: String; let symbol: String; let tint: Color; let enabled: Bool }

    var tiles: [Tile] {
        var list: [Tile] = []
        let features = suite.features
        if features.contains("visual-inventory-v1") {
            list.append(Tile(tool: .inventory, title: online ? "Live-Inventar" : "Offline-Inventar", detail: "Inventar, Rüstung und Endertruhe", symbol: "square.grid.3x3.fill", tint: .green,
                             enabled: suite.can("data.inventory") && (online || features.contains("powerbot-v1"))))
        } else {
            list.append(Tile(tool: .legacyInventory, title: "Inventar", detail: online ? "Aktuelles Inventar" : "Nur online", symbol: "square.grid.3x3.fill", tint: .green, enabled: suite.can("data.inventory") && online))
        }
        list.append(Tile(tool: .snapshots, title: "Snapshots", detail: "Inventar-Verlauf wiederherstellen", symbol: "archivebox.fill", tint: .teal, enabled: suite.can("data.snapshots")))
        list.append(Tile(tool: .nbt, title: "NBT-Editor", detail: "Gespeicherte Spielerdatei", symbol: "doc.badge.gearshape.fill", tint: .indigo, enabled: suite.can("data.nbt-paths")))
        if features.contains("player-dossier-v1") {
            list.append(Tile(tool: .dossier, title: "Steckbrief", detail: "Alles auf einen Blick", symbol: "person.text.rectangle.fill", tint: .panelAccent, enabled: suite.can("data.profile")))
        }
        if features.contains("player-timeline-v1") {
            list.append(Tile(tool: .timeline, title: "Verlauf", detail: "Chat, Aktionen, Kämpfe und Items", symbol: "clock.arrow.circlepath", tint: .blue, enabled: suite.can("data.profile")))
        }
        if features.contains("community-tools-v1") && UUID(uuidString: player) != nil {
            list.append(Tile(tool: .community, title: "Punkte & Delivery", detail: "Simplex Points und Zustellungen", symbol: "storefront.fill", tint: .yellow, enabled: suite.can("data.community-tools")))
        }
        list.append(Tile(tool: .devices, title: "Geräte", detail: "Clients und Sitzungen", symbol: "iphone.and.arrow.right.inward", tint: .gray, enabled: suite.can("data.devices")))
        if features.contains("player-rights-v1") {
            list.append(Tile(tool: .rights, title: "Rechte & Sperren", detail: "Bauen, Chat, Befehle und mehr", symbol: "lock.shield.fill", tint: .orange, enabled: suite.can("data.access-locks")))
            if features.contains("command-rights-v1") {
                list.append(Tile(tool: .commands, title: "Befehle", detail: "Erlauben und sperren", symbol: "terminal.fill", tint: .purple, enabled: suite.can("data.commands")))
            }
        } else {
            list.append(Tile(tool: .legacyRights, title: "Spielerrechte", detail: "Rechte und Vererbung", symbol: "lock.shield.fill", tint: .orange, enabled: true))
        }
        let altDetail = current["altCount"] == .null ? "Verknüpfte Konten" : "\(current["altCount"].int) mögliche Alts"
        list.append(Tile(tool: .alts, title: "Alt-Accounts", detail: altDetail, symbol: "person.2.badge.key.fill", tint: .pink, enabled: suite.can("data.alts")))
        if features.contains("powerbot-cases-v2") {
            list.append(Tile(tool: .powerBot, title: "PowerBot-Akte", detail: "Fälle und Hinweise", symbol: "shield.lefthalf.filled", tint: .red, enabled: suite.can("data.powerbot")))
        }
        if actions.contains(where: { $0.id == "player.punish" }) {
            list.append(Tile(tool: .punish, title: "Strafen & Warnungen", detail: "Mute, Kick, Bann, Warnung", symbol: "hand.raised.fill", tint: .red, enabled: suite.can("action.player.punish")))
        }
        if features.contains("chat-silence-v1") {
            list.append(Tile(tool: .chatSilence, title: "Chat-Ruhe & Töne", detail: "Chat, Server-Töne, Stummschaltungen", symbol: "speaker.slash.fill", tint: .cyan, enabled: suite.can("data.chat-silence")))
        }
        if features.contains("smart-anti-lag-v1") {
            list.append(Tile(tool: .antiLag, title: "Anti-Lag", detail: "Verursacht \(name) Last?", symbol: "bolt.shield.fill", tint: .orange, enabled: suite.can("data.anti-lag")))
        }
        list.append(Tile(tool: .probe, title: "Client prüfen", detail: online ? "Mods und Client-Check" : "Nur online möglich", symbol: "checkmark.shield.fill", tint: .mint, enabled: online))
        return list
    }

    private var statusRow: some View {
        VStack(alignment: .leading, spacing: 6) {
            if current["chatSilence"] != .null {
                Label((current["chatSilence"]["active"].bool ? "Chat-Ruhe aktiv" : "Chat sichtbar") + " · " + current["chatSilence"]["soundLabel"].string, systemImage: current["chatSilence"]["active"].bool ? "bell.slash.fill" : "bell.fill")
            }
            if current["personalMutes"] != .null {
                if current["personalMutes"]["protected"].bool {
                    Label("Admin – kann von Spielern nicht stummgeschaltet werden", systemImage: "shield.checkered")
                } else {
                    Label("Hat \(current["personalMutes"]["muting"].int) Spieler persönlich stummgeschaltet", systemImage: "person.crop.circle.badge.xmark")
                }
            }
        }.font(.caption).foregroundStyle(.secondary)
    }
}

/// The screen behind each tile – the existing, fully featured tool views.
struct SuitePlayerToolDestination: View {
    let tool: SuitePlayerTool
    let suite: SuiteAPI
    let panel: PanelAPI
    let server: Server
    let player: String
    let name: String
    let current: JSON
    let actions: [SuiteAction]

    private var profileID: String { current["id"].string.isEmpty ? player : current["id"].string }

    @ViewBuilder var body: some View {
        switch tool {
        case .inventory: SuiteVisualInventoryView(suite: suite, player: profileID, offline: !current["online"].bool)
        case .legacyInventory: SuiteDataListView(suite: suite, kind: "inventory", player: player, actions: actions)
        case .snapshots: SuiteSnapshotsView(suite: suite, player: player, actions: actions)
        case .nbt: SuiteNBTPathsView(suite: suite, panel: panel, server: server, player: player)
        case .dossier: SuitePlayerDossierView(suite: suite, player: player)
        case .timeline: SuitePlayerTimelineView(suite: suite, player: player, name: name)
        case .community: SuiteCommunityToolsView(suite: suite, initialPlayer: player)
        case .devices: SuiteDataListView(suite: suite, kind: "devices", player: player, actions: actions, panel: panel, server: server)
        case .rights: SuiteAccessLockPlayerView(suite: suite, player: player)
        case .legacyRights: SuitePermissionView(suite: suite, player: player)
        case .commands: SuiteCommandPlayerView(suite: suite, player: player)
        case .alts: SuiteDataListView(suite: suite, kind: "alts", player: player, actions: actions, panel: panel, server: server)
        case .powerBot: SuitePowerBotPlayerView(suite: suite, player: player, name: name)
        case .punish:
            if let action = actions.first(where: { $0.id == "player.punish" }) { SuiteActionView(suite: suite, action: action, preset: ["player": .string(player)]) }
            else { ContentUnavailableView("Nicht verfügbar", systemImage: "hand.raised") }
        case .chatSilence: SuiteChatSilenceView(suite: suite)
        case .antiLag: SuiteAntiLagView(suite: suite)
        case .probe: SuiteProbeView(suite: suite, player: player)
        }
    }
}
