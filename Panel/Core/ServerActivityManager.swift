import SwiftUI
import ActivityKit
import UIKit

@MainActor final class ServerActivityManager: ObservableObject {
    static let shared = ServerActivityManager()
    @Published private(set) var activity: Activity<ServerActivity>? = Activity<ServerActivity>.activities.first
    @Published var error: String?
    private var task: Task<Void, Never>?
    private var lastPublish = Date.distantPast
    private var generation = UUID()
    func matches(_ api: PanelAPI, _ server: Server) -> Bool { activity?.attributes.panel == api.base.absoluteString && activity?.attributes.serverID == server.id && activity?.activityState != .ended && activity?.activityState != .dismissed }
    func snapshot(live: LiveServer) -> ServerActivity.ContentState {
        ServerActivity.ContentState(state: live.state, cpu: AMPMetrics.number(live.stats["cpu_absolute"]), memory: AMPMetrics.number(live.stats["memory_bytes"]), online: live.players?.online, maximum: live.players?.maximum, tps: live.tps?.minute, uptime: AMPMetrics.number(live.stats["uptime"]).map { $0 / 1000 }, updated: live.lastUpdate ?? .distantPast, playersUpdated: live.players?.updated, performanceUpdated: live.tps?.updated, source: "Panel", issue: live.issue)
    }
    func start(api: PanelAPI, server: Server, live: LiveServer) async {
        guard UIApplication.shared.applicationState == .active else { error = "Öffne die App, um die Live-Aktivität zu starten."; return }
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { error = "Live-Aktivitäten sind in den iOS-Einstellungen deaktiviert."; return }
        await stop()
        do {
            let state = snapshot(live: live)
            activity = try Activity.request(attributes: ServerActivity(panel: api.base.absoluteString, serverID: server.id, name: server.name), content: ActivityContent(state: state, staleDate: state.updated.addingTimeInterval(90)), pushType: nil)
            error = nil; resume(api: api, servers: [server])
        } catch { self.error = error.localizedDescription }
    }
    func stop() async {
        generation = UUID(); task?.cancel(); task = nil
        let old = activity; activity = nil
        for item in Activity<ServerActivity>.activities { await item.end(nil, dismissalPolicy: .immediate) }
        if let old, !Activity<ServerActivity>.activities.contains(where: { $0.id == old.id }) { await old.end(nil, dismissalPolicy: .immediate) }
    }
    func suspend() { generation = UUID(); task?.cancel(); task = nil }
    func resume(api: PanelAPI?, servers: [Server]) {
        task?.cancel(); task = nil; generation = UUID()
        activity = Activity<ServerActivity>.activities.first { $0.activityState == .active || $0.activityState == .stale }
        guard let api, let activity, activity.attributes.panel == api.base.absoluteString, let server = servers.first(where: { $0.id == activity.attributes.serverID }) else { return }
        let id = generation
        task = Task {
            while !Task.isCancelled && generation == id {
                await poll(api: api, server: server, generation: id)
                do { try await Task.sleep(for: .seconds(20)) } catch { return }
            }
        }
    }
    func publish(api: PanelAPI, server: Server, live: LiveServer, suite: SuiteModel) {
        guard matches(api, server), Date().timeIntervalSince(lastPublish) >= 10, let activity else { return }
        lastPublish = Date()
        var state = snapshot(live: live)
        // A dedicated Suite overview is fetched by poll(); don't erase it with a panel-only tick.
        let previous = activity.content.state
        if previous.source == "Admin Suite", let updated = previous.performanceUpdated, Date().timeIntervalSince(updated) < 60 {
            state.online = previous.online; state.maximum = previous.maximum; state.playersUpdated = previous.playersUpdated
            state.tps = previous.tps; state.mspt = previous.mspt; state.performanceUpdated = updated; state.source = previous.source
        }
        let content = ActivityContent(state: state, staleDate: state.updated.addingTimeInterval(90))
        Task { await activity.update(content) }
    }
    private func poll(api: PanelAPI, server: Server, generation id: UUID) async {
        guard let activity, matches(api, server) else { return }
        do {
            let resource = try await api.request("servers/\(server.id)/resources").attributes
            var state = activity.content.state
            state.state = resource["current_state"].string
            let stats = resource["resources"]
            state.cpu = AMPMetrics.number(stats["cpu_absolute"]); state.memory = AMPMetrics.number(stats["memory_bytes"])
            state.uptime = AMPMetrics.number(stats["uptime"]).map { $0 / 1000 }; state.updated = Date(); state.issue = nil
            if let record = SuiteConnections.shared.get(api, server) {
                do {
                    let suite = try SuiteAPI(record)
                    let overview = try await suite.data("overview")
                    state.online = overview["online"] == .null ? nil : overview["online"].int
                    state.maximum = overview["maxPlayers"] == .null ? nil : overview["maxPlayers"].int
                    state.tps = AMPMetrics.number(overview["tps"]); state.mspt = AMPMetrics.number(overview["mspt"])
                    state.playersUpdated = Date(); state.performanceUpdated = Date(); state.source = "Admin Suite"
                } catch { state.issue = "Admin Suite: " + error.localizedDescription }
            } else if state.state != "offline", let result = try? await api.request("servers/\(server.id)/players"), let players = PlayerSnapshot.decode(result) {
                state.online = players.online; state.maximum = players.maximum; state.playersUpdated = players.updated; state.source = "Panel"
            }
            if state.state == "offline" { state.online = 0; state.playersUpdated = Date(); state.tps = nil; state.mspt = nil }
            guard !Task.isCancelled, generation == id, self.activity?.id == activity.id else { return }
            await activity.update(ActivityContent(state: state, staleDate: state.updated.addingTimeInterval(90)))
        } catch {
            guard !Task.isCancelled, generation == id else { return }
            var state = activity.content.state; state.issue = error.localizedDescription
            await activity.update(ActivityContent(state: state, staleDate: state.updated.addingTimeInterval(90)))
        }
    }
}

struct ServerActivitySettings: View {
    let api: PanelAPI
    let server: Server
    @ObservedObject var live: LiveServer
    @ObservedObject var suite: SuiteModel
    @ObservedObject private var manager = ServerActivityManager.shared
    var body: some View {
        Form {
            Section(server.name) {
                Label("Server auf Sperrbildschirm & Dynamic Island", systemImage: "waveform.path.ecg").font(.headline)
                Text("Spieler, CPU, RAM und Laufzeit. Mit gekoppelter Admin Suite zusätzlich TPS und Tickdauer. Pro App ist hier ein Server gleichzeitig ausgewählt.")
                if manager.matches(api, server) { Button("Live-Aktivität beenden", systemImage: "stop.circle", role: .destructive) { Task { await manager.stop() } } }
                else { Button("Live-Aktivität starten", systemImage: "play.circle") { Task { await manager.start(api: api, server: server, live: live) } } }
            }
            Section("Aktualisierung") {
                Text("Solange die App aktiv ist, werden Werte etwa alle 20 Sekunden erneuert – auch wenn du in der App eine andere Seite öffnest. Im Hintergrund bleibt der letzte empfangene Stand sichtbar. Nach 90 Sekunden wird er als veraltet markiert.")
                Text("iOS begrenzt Live-Aktivitäten auf etwa acht Stunden. Für fortlaufende Werte bei geschlossener App wäre ein eigener ActivityKit-Push-Dienst nötig. Zum erneuten Starten diese Seite öffnen.").font(.footnote).foregroundStyle(.secondary)
            }
        }.panelScreen().navigationTitle("Server-Live-Aktivität").errorAlert($manager.error)
    }
}
