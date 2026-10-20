import SwiftUI
import WidgetKit
import AppIntents
import ActivityKit

struct ServerEntity: AppEntity {
    static var typeDisplayRepresentation: TypeDisplayRepresentation = "Panel-Server"
    static var defaultQuery = ServerQuery()
    var id: String
    var name: String
    var displayRepresentation: DisplayRepresentation { DisplayRepresentation(title: "\(name)") }
}
struct ServerQuery: EntityQuery {
    func entities(for identifiers: [String]) async throws -> [ServerEntity] { WidgetStorage.servers().filter { identifiers.contains($0.id) }.map { ServerEntity(id: $0.id, name: $0.name) } }
    func suggestedEntities() async throws -> [ServerEntity] { WidgetStorage.servers().map { ServerEntity(id: $0.id, name: $0.name) } }
    func defaultResult() async -> ServerEntity? { WidgetStorage.servers().first.map { ServerEntity(id: $0.id, name: $0.name) } }
}
struct ServerConfiguration: WidgetConfigurationIntent {
    static var title: LocalizedStringResource = "Server wählen"
    static var description = IntentDescription("Zeigt Ressourcen und die zuletzt empfangene Spielerzahl deines Servers.")
    @Parameter(title: "Server") var server: ServerEntity?
}
struct ServerEntry: TimelineEntry { let date: Date; let server: WidgetServer? }
struct ServerProvider: AppIntentTimelineProvider {
    func placeholder(in context: Context) -> ServerEntry { ServerEntry(date: Date(), server: WidgetServer(panel: "https://panel.example.com", serverID: "sample", name: "Minecraft", state: "running", cpu: 24, memory: 2_500_000_000, memoryLimit: 8_000_000_000, online: 2, maximum: 60, updated: Date(), playersUpdated: Date())) }
    func snapshot(for configuration: ServerConfiguration, in context: Context) async -> ServerEntry { ServerEntry(date: Date(), server: selected(configuration) ?? (context.isPreview ? placeholder(in: context).server : nil)) }
    func timeline(for configuration: ServerConfiguration, in context: Context) async -> Timeline<ServerEntry> {
        var record = selected(configuration)
        if let current = record, current.backgroundRefresh { record = await WidgetNetwork.refresh(current) }
        return Timeline(entries: [ServerEntry(date: Date(), server: record)], policy: .after(Date().addingTimeInterval(30 * 60)))
    }
    func selected(_ configuration: ServerConfiguration) -> WidgetServer? {
        let records = WidgetStorage.servers()
        if let id = configuration.server?.id { return records.first { $0.id == id } }
        return records.first
    }
}
private final class WidgetRedirect: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}
enum WidgetNetwork {
    static func refresh(_ initial: WidgetServer) async -> WidgetServer {
        var record = initial
        guard let key = WidgetVault.read(record.panel), let base = URL(string: record.panel), base.scheme == "https" else { record.issue = "Widget-Zugang in der App erneuern"; return record }
        let config = URLSessionConfiguration.ephemeral; config.timeoutIntervalForRequest = 10; config.timeoutIntervalForResource = 15
        let session = URLSession(configuration: config, delegate: WidgetRedirect(), delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        func get(_ suffix: String) async throws -> [String: Any] {
            let url = base.appendingPathComponent("api/client/servers").appendingPathComponent(record.serverID).appendingPathComponent(suffix)
            var request = URLRequest(url: url); request.setValue("Bearer " + key, forHTTPHeaderField: "Authorization"); request.setValue("application/json", forHTTPHeaderField: "Accept")
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode), let value = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw URLError(.badServerResponse) }
            return value["attributes"] as? [String: Any] ?? value
        }
        do {
            let result = try await get("resources")
            guard let state = result["current_state"] as? String, let resources = result["resources"] as? [String: Any] else { throw URLError(.cannotParseResponse) }
            record.state = state; record.cpu = (resources["cpu_absolute"] as? NSNumber)?.doubleValue; record.memory = (resources["memory_bytes"] as? NSNumber)?.doubleValue; record.disk = (resources["disk_bytes"] as? NSNumber)?.doubleValue
            record.updated = Date(); record.issue = nil
            if state == "offline" { record.online = 0; record.playersUpdated = Date(); record.tps = nil; record.tpsUpdated = nil }
            let host = base.host?.lowercased() ?? ""
            if state == "running", host == "kineticpanel.net" || host.hasSuffix(".kineticpanel.net"), var players = try? await get("players") {
                if let data = players["data"] as? [String: Any] { players = data["attributes"] as? [String: Any] ?? data }
                if let nested = players["players"] as? [String: Any] { players = nested }
                let count = ["online", "count", "player_count", "online_count"].compactMap { (players[$0] as? NSNumber)?.intValue }.first
                let names = players["players"] as? [Any] ?? players["online_players"] as? [Any]
                if let count = count ?? names?.count { record.online = count; record.maximum = ["max", "max_players", "maximum"].compactMap { (players[$0] as? NSNumber)?.intValue }.first ?? record.maximum; record.playersUpdated = Date() }
            }
            // The extension never overwrites the shared manifest: the app owns configuration.
        } catch { record.issue = "Aktualisierung fehlgeschlagen · letzter Stand" }
        return record
    }
}
struct StatusWidgetView: View {
    let entry: ServerEntry
    @Environment(\.widgetFamily) private var family
    var body: some View {
        Group {
            if let s = entry.server {
                if family == .accessoryCircular {
                    Gauge(value: Double(s.online ?? 0), in: 0...Double(max(1, s.maximum ?? 1))) { Image(systemName: "person.2") } currentValueLabel: { Text(s.online.map(String.init) ?? "–") }.gaugeStyle(.accessoryCircular).privacySensitive()
                } else if family == .accessoryRectangular {
                    VStack(alignment: .leading) { Text(s.name).font(.headline); Text("\(s.online.map(String.init) ?? "–") / \(s.maximum.map(String.init) ?? "–") Spieler · \(s.stateTitle)"); if let date = s.playersUpdated { Text(date, style: .time).font(.caption2) } }.privacySensitive()
                } else {
                    VStack(alignment: .leading, spacing: 9) {
                        HStack { Image(systemName: "server.rack").foregroundStyle(.teal); Text(s.name).font(.headline).lineLimit(1); Spacer(minLength: 0); Circle().fill(s.state == "running" ? Color.green : s.state == "offline" ? .red : .orange).frame(width: 8, height: 8) }
                        HStack(alignment: .firstTextBaseline) { Text(s.online.map(String.init) ?? "–").font(.system(size: 34, weight: .bold, design: .rounded)); Text("/ \(s.maximum.map(String.init) ?? "–") Spieler").font(.caption).foregroundStyle(.secondary) }
                        HStack { Text(s.stateTitle).font(.caption.weight(.semibold)); Spacer(); if let date = s.playersUpdated { Text(date, style: .time).font(.caption2).foregroundStyle(.secondary) } }
                        if family == .systemMedium {
                            Divider()
                            HStack { Label(s.cpu.map { String(format: "%.1f %%", $0) } ?? "–", systemImage: "cpu"); Spacer(); Label(s.memory.map { ByteCountFormatter.string(fromByteCount: Int64($0), countStyle: .memory) } ?? "–", systemImage: "memorychip"); Spacer(); if let tps = s.tps, let date = s.tpsUpdated, Date().timeIntervalSince(date) < 180 { Text(String(format: "%.1f TPS", tps)).foregroundStyle(tps >= 19 ? Color.green : tps >= 16 ? .orange : .red) } }.font(.caption)
                        }
                        if let issue = s.issue { Text(issue).font(.caption2).foregroundStyle(.orange).lineLimit(1) }
                        else if let date = s.updated { Text("Ressourcen: \(date.formatted(date: .omitted, time: .shortened)) · kein Live-Wert").font(.caption2).foregroundStyle(.secondary).lineLimit(1) }
                        else { Text("Server in der App öffnen").font(.caption2).foregroundStyle(.secondary) }
                    }.privacySensitive()
                }
            } else { VStack(alignment: .leading, spacing: 10) { Image(systemName: "server.rack").foregroundStyle(.teal); Text("Panel").font(.headline); Text("In der App unter Einstellungen → Server-Widgets einen Server hinzufügen.").font(.caption).foregroundStyle(.secondary) } }
        }.containerBackground(for: .widget) { LinearGradient(colors: [Color.teal.opacity(0.16), Color.indigo.opacity(0.08)], startPoint: .topLeading, endPoint: .bottomTrailing) }
            .widgetURL(URL(string: "panel://widgets"))
    }
}
struct ServerStatusWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: "PanelServerStatus", intent: ServerConfiguration.self, provider: ServerProvider()) { entry in StatusWidgetView(entry: entry) }
            .configurationDisplayName("Server & Spieler").description("Spielerzahl, Serverstatus, CPU und RAM mit Zeitstempeln.")
            .supportedFamilies([.systemSmall, .systemMedium, .accessoryCircular, .accessoryRectangular])
    }
}
struct TransferLiveWidget: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: TransferActivity.self) { context in
            VStack(alignment: .leading, spacing: 10) {
                HStack { Label(context.attributes.upload ? "Upload" : "Download", systemImage: context.attributes.upload ? "arrow.up.circle.fill" : "arrow.down.circle.fill").foregroundStyle(.teal); Spacer(); Text(context.isStale ? "Stand veraltet" : context.state.phase).font(.caption) }
                Text(context.attributes.filename).font(.headline).lineLimit(1)
                transferProgress(context.state)
            }.padding().activityBackgroundTint(Color.black.opacity(0.08)).widgetURL(URL(string: "panel://transfers"))
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) { Label(context.attributes.upload ? "Upload" : "Download", systemImage: context.attributes.upload ? "arrow.up.circle" : "arrow.down.circle").foregroundStyle(.teal) }
                DynamicIslandExpandedRegion(.trailing) { Text(context.state.fraction.map { "\(Int($0 * 100)) %" } ?? "…").monospacedDigit() }
                DynamicIslandExpandedRegion(.bottom) { VStack(alignment: .leading, spacing: 6) { Text(context.attributes.filename).font(.caption).lineLimit(1); transferProgress(context.state); Text(context.isStale ? "Letzter Stand · App für Aktualisierung öffnen" : context.state.phase).font(.caption2).foregroundStyle(.secondary) } }
            } compactLeading: { Image(systemName: context.attributes.upload ? "arrow.up" : "arrow.down").foregroundStyle(.teal) } compactTrailing: { Text(context.state.fraction.map { "\(Int($0 * 100))%" } ?? "…").font(.caption).monospacedDigit() } minimal: { Image(systemName: context.attributes.upload ? "arrow.up.circle" : "arrow.down.circle").foregroundStyle(.teal) }
                .widgetURL(URL(string: "panel://transfers")).keylineTint(.teal)
        }
    }
    @ViewBuilder func transferProgress(_ state: TransferActivity.ContentState) -> some View {
        if let fraction = state.fraction { ProgressView(value: fraction).tint(.teal) }
        HStack { Text(ByteCountFormatter.string(fromByteCount: state.completed, countStyle: .file)); if state.total > 0 { Text("/ " + ByteCountFormatter.string(fromByteCount: state.total, countStyle: .file)) }; Spacer() }.font(.caption).monospacedDigit()
    }
}
@main struct PanelWidgetBundle: WidgetBundle {
    var body: some Widget { ServerStatusWidget(); TransferLiveWidget() }
}
