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
        return Timeline(entries: [ServerEntry(date: Date(), server: record)], policy: .after(Date().addingTimeInterval(15 * 60)))
    }
    func selected(_ configuration: ServerConfiguration) -> WidgetServer? {
        let records = WidgetStorage.servers()
        if let id = configuration.server?.id { return records.first { $0.id == id } }
        return records.first
    }
}
struct StatusWidgetView: View {
    let entry: ServerEntry
    @Environment(\.widgetFamily) private var family
    @Environment(\.widgetRenderingMode) private var renderingMode
    var body: some View {
        Group {
            if let s = entry.server {
                if family == .accessoryCircular {
                    Gauge(value: Double(s.online ?? 0), in: 0...Double(max(1, s.maximum ?? 1))) { Image(systemName: "person.2") } currentValueLabel: { Text(s.online.map(String.init) ?? "–") }.gaugeStyle(.accessoryCircular).privacySensitive()
                } else if family == .accessoryRectangular {
                    VStack(alignment: .leading) { Text(s.name).font(.headline); Text("\(s.online.map(String.init) ?? "–") / \(s.maximum.map(String.init) ?? "–") Spieler · \(s.stateTitle)"); if let date = s.playersUpdated { Text(date, style: .time).font(.caption2) } }.privacySensitive()
                } else {
                    homeContent(s)

                }
            } else { VStack(alignment: .leading, spacing: 10) { Image(systemName: "server.rack").foregroundStyle(.teal); Text("Panel").font(.headline); Text("In der App unter Einstellungen → Server-Widgets einen Server hinzufügen.").font(.caption).foregroundStyle(.secondary) } }
        }.containerBackground(for: .widget) {
            if renderingMode == .fullColor { LinearGradient(colors: [Color.teal.opacity(0.16), Color.indigo.opacity(0.08)], startPoint: .topLeading, endPoint: .bottomTrailing) }
        }
            .widgetURL(URL(string: "panel://widgets"))
    }
    func homeContent(_ s: WidgetServer) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Image(systemName: "server.rack").foregroundStyle(.teal).widgetAccentable()
                Text(s.name).font(.headline).lineLimit(1)
                Spacer(minLength: 0)
                WidgetRefreshButton(id: s.id)
                Circle().fill(renderingMode == .fullColor ? (s.state == "running" ? Color.green : s.state == "offline" ? .red : .orange) : Color.primary).frame(width: 8, height: 8)
            }
            HStack(spacing: 16) {
                VStack(alignment: .leading, spacing: 3) {
                    HStack(alignment: .firstTextBaseline, spacing: 4) {
                        Text(s.online.map(String.init) ?? "–").font(.system(size: 34, weight: .bold, design: .rounded)).widgetAccentable()
                        Text("/ \(s.maximum.map(String.init) ?? "–")").font(.caption).foregroundStyle(.secondary)
                    }
                    Text("Spieler · " + s.stateTitle).font(.caption)
                    if let date = s.playersUpdated { Text("Spielerstand: \(date.formatted(date: .omitted, time: .shortened))").font(.caption2).foregroundStyle(.secondary) }
                }
                if family == .systemMedium || family == .systemLarge {
                    Spacer(minLength: 0)
                    VStack(alignment: .leading, spacing: 6) {
                        Label(s.cpu.map { String(format: "%.1f %% CPU", $0) } ?? "CPU –", systemImage: "cpu")
                        Label(memory(s), systemImage: "memorychip")
                        if let uptime = s.uptime { Label(Duration.seconds(uptime).formatted(.time(pattern: .hourMinuteSecond)), systemImage: "clock") }
                        if let tps = s.tps, let date = s.tpsUpdated, Date().timeIntervalSince(date) < 180 { Text(String(format: "%.1f TPS", tps)).foregroundStyle(tps >= 19 ? Color.green : tps >= 16 ? .orange : .red).widgetAccentable() }
                    }.font(.caption).lineLimit(1).minimumScaleFactor(0.75)
                }
            }
            if let issue = s.issue { Text(issue).font(.caption2).foregroundStyle(.orange).lineLimit(1) }
            else if let date = s.updated { Text("Ressourcen: \(date.formatted(date: .omitted, time: .shortened)) · letzter Stand").font(.caption2).foregroundStyle(.secondary).lineLimit(1) }
            else { Text("Server in der App öffnen").font(.caption2).foregroundStyle(.secondary) }
        }.privacySensitive()
    }
    func memory(_ s: WidgetServer) -> String {
        guard let value = s.memory else { return "RAM –" }
        let used = ByteCountFormatter.string(fromByteCount: Int64(value), countStyle: .memory)
        guard let limit = s.memoryLimit, limit > 0 else { return used + " RAM" }
        return used + " / " + ByteCountFormatter.string(fromByteCount: Int64(limit), countStyle: .memory)
    }
}
struct ServerStatusWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: "PanelServerStatus", intent: ServerConfiguration.self, provider: ServerProvider()) { entry in StatusWidgetView(entry: entry) }
            .configurationDisplayName("Server & Spieler").description("Spielerzahl, Serverstatus, CPU und RAM mit Zeitstempeln.")
            .supportedFamilies([.systemSmall, .systemMedium, .systemLarge, .accessoryCircular, .accessoryRectangular])
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
    var body: some Widget { ServerStatusWidget(); FleetWidget(); OnlinePlayersWidget(); RAMWidget(); CPUWidget(); UptimeWidget(); CopiedWidget(); TransferLiveWidget(); ServerLiveWidget() }
}

