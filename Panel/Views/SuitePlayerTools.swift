import SwiftUI

/// 5.16.0: every player tool in one Liquid Glass grid at the top of the profile. Each tile opens the existing,
/// fully featured tool; tiles without permission are dimmed so admins see what else exists.
struct SuitePlayerToolGrid: View {
    let suite: SuiteAPI
    let panel: PanelAPI
    let server: Server
    let player: String
    let name: String
    let current: JSON
    let actions: [SuiteAction]

    private var online: Bool { current["online"].bool }
    private var columns: [GridItem] { [GridItem(.adaptive(minimum: 128), spacing: 10)] }

    var body: some View {
        Section {
            GlassEffectContainer(spacing: 10) {
                LazyVGrid(columns: columns, spacing: 10) {
                    inventoryTiles
                    profileTiles
                    rightsTiles
                    moderationTiles
                    serverTiles
                }
            }
            .listRowBackground(Color.clear)
            .listRowInsets(EdgeInsets(top: 6, leading: 0, bottom: 6, trailing: 0))
            if current["chatSilence"] != .null || current["personalMutes"] != .null { statusRow }
        } header: { Text("Werkzeuge") } footer: { Text("Alles für \(name) an einem Ort. Gedimmte Werkzeuge brauchen ein zusätzliches Recht oder eine neuere Admin Suite.") }
    }

    @ViewBuilder private var inventoryTiles: some View {
        if suite.features.contains("visual-inventory-v1") {
            tile(online ? "Live-Inventar" : "Offline-Inventar", "Inventar, Rüstung & Endertruhe", "square.grid.3x3.fill", .green,
                 enabled: suite.can("data.inventory") && (online || suite.features.contains("powerbot-v1"))) {
                SuiteVisualInventoryView(suite: suite, player: current["id"].string.isEmpty ? player : current["id"].string, offline: !online)
            }
        }
        tile("Snapshots", "Inventar-Verlauf wiederherstellen", "archivebox.fill", .teal, enabled: suite.can("data.snapshots")) {
            SuiteSnapshotsView(suite: suite, player: player, actions: actions)
        }
        tile("NBT-Editor", "Gespeicherte Spielerdatei", "doc.badge.gearshape.fill", .indigo, enabled: suite.can("data.nbt-paths")) {
            SuiteNBTPathsView(suite: suite, panel: panel, server: server, player: player)
        }
    }

    @ViewBuilder private var profileTiles: some View {
        if suite.features.contains("player-dossier-v1") {
            tile("Steckbrief", "Alles auf einen Blick", "person.text.rectangle.fill", .panelAccent, enabled: suite.can("data.profile")) {
                SuitePlayerDossierView(suite: suite, player: player)
            }
        }
        if suite.features.contains("player-timeline-v1") {
            tile("Verlauf", "Chat, Aktionen, Kämpfe & Items", "clock.arrow.circlepath", .blue, enabled: suite.can("data.profile")) {
                SuitePlayerTimelineView(suite: suite, player: player, name: name)
            }
        }
        if suite.features.contains("community-tools-v1") && UUID(uuidString: player) != nil {
            tile("Punkte & Delivery", "Simplex Points, Zustellungen", "storefront.fill", .yellow, enabled: suite.can("data.community-tools")) {
                SuiteCommunityToolsView(suite: suite, initialPlayer: player)
            }
        }
        tile("Geräte", "Clients und Sitzungen", "iphone.and.arrow.right.inward", .gray, enabled: suite.can("data.devices")) {
            SuiteDataListView(suite: suite, kind: "devices", player: player, actions: actions, panel: panel, server: server)
        }
    }

    @ViewBuilder private var rightsTiles: some View {
        if suite.features.contains("player-rights-v1") {
            tile("Rechte & Sperren", "Bauen, Chat, Befehle …", "lock.shield.fill", .orange, enabled: suite.can("data.access-locks")) {
                SuiteAccessLockPlayerView(suite: suite, player: player)
            }
        }
        if suite.features.contains("command-rights-v1") {
            tile("Befehle", "Erlauben & sperren", "terminal.fill", .purple, enabled: suite.can("data.commands")) {
                SuiteCommandPlayerView(suite: suite, player: player)
            }
        }
    }

    @ViewBuilder private var moderationTiles: some View {
        tile("Alt-Accounts", current["altCount"] == .null ? "Verknüpfte Konten" : "\(current["altCount"].int) mögliche Alts", "person.2.badge.key.fill", .pink, enabled: suite.can("data.alts")) {
            SuiteDataListView(suite: suite, kind: "alts", player: player, actions: actions, panel: panel, server: server)
        }
        if suite.features.contains("powerbot-cases-v2") {
            tile("PowerBot-Akte", "Fälle und Hinweise", "shield.lefthalf.filled", .red, enabled: suite.can("data.powerbot")) {
                SuitePowerBotPlayerView(suite: suite, player: player, name: name)
            }
        }
        if let action = actions.first(where: { $0.id == "player.punish" }) {
            tile("Strafen & Warnungen", "Mute, Kick, Bann, Warnung", "hand.raised.fill", .red, enabled: true) {
                SuiteActionView(suite: suite, action: action, preset: ["player": .string(player)])
            }
        }
    }

    @ViewBuilder private var serverTiles: some View {
        if suite.features.contains("chat-silence-v1") {
            tile("Chat-Ruhe & Töne", "Chat, Server-Töne, Stummschaltungen", "speaker.slash.fill", .cyan, enabled: suite.can("data.chat-silence")) {
                SuiteChatSilenceView(suite: suite)
            }
        }
        if suite.features.contains("smart-anti-lag-v1") {
            tile("Anti-Lag", "Verursacht \(name) Last?", "bolt.shield.fill", .orange, enabled: suite.can("data.anti-lag")) {
                SuiteAntiLagView(suite: suite)
            }
        }
        tile("Client prüfen", online ? "Mods & Client-Check" : "Nur online möglich", "checkmark.shield.fill", .mint, enabled: online) {
            SuiteProbeView(suite: suite, player: player)
        }
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

    private func tile<Destination: View>(_ title: String, _ detail: String, _ symbol: String, _ tint: Color, enabled: Bool, @ViewBuilder destination: @escaping () -> Destination) -> some View {
        NavigationLink { destination() } label: { SuiteGlassTileLabel(title: title, detail: detail, symbol: symbol, tint: tint) }
            .buttonStyle(.plain)
            .disabled(!enabled)
            .opacity(enabled ? 1 : 0.45)
    }
}
