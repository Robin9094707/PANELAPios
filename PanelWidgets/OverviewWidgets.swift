import SwiftUI
import WidgetKit
import AppIntents

struct RefreshPanelWidgets: AppIntent {
    static var title: LocalizedStringResource = "Server-Widgets aktualisieren"
    static var openAppWhenRun = false
    @Parameter(title: "Server-ID") var serverID: String
    init() { serverID = "" }
    init(_ id: String) { serverID = id }
    func perform() async throws -> some IntentResult {
        let records = WidgetStorage.servers().filter { serverID.isEmpty || $0.id == serverID }
        for record in records where record.backgroundRefresh { _ = await WidgetNetwork.refresh(record) }
        WidgetCenter.shared.reloadAllTimelines()
        return .result()
    }
}
struct WidgetRefreshButton: View {
    var id = ""
    var body: some View {
        if WidgetStorage.servers().contains(where: { (id.isEmpty || $0.id == id) && $0.backgroundRefresh }) { Button(intent: RefreshPanelWidgets(id)) { Image(systemName: "arrow.clockwise").font(.caption.bold()) }.buttonStyle(.plain).accessibilityLabel("Aktualisieren") }
        else { Link(destination: URL(string: "panel://widgets")!) { Image(systemName: "arrow.clockwise").font(.caption.bold()) }.accessibilityLabel("App zum Aktualisieren öffnen") }
    }
}
struct FleetEntry: TimelineEntry { let date: Date; let servers: [WidgetServer] }
struct FleetProvider: TimelineProvider {
    func placeholder(in context: Context) -> FleetEntry { FleetEntry(date: Date(), servers: [WidgetServer(panel: "https://example.com", serverID: "demo", name: "Simplex SMP", state: "running", cpu: 23, memory: 2_500_000_000, updated: Date())]) }
    func getSnapshot(in context: Context, completion: @escaping (FleetEntry) -> Void) { completion(context.isPreview ? placeholder(in: context) : FleetEntry(date: Date(), servers: WidgetStorage.servers())) }
    func getTimeline(in context: Context, completion: @escaping (Timeline<FleetEntry>) -> Void) {
        Task {
            let records = WidgetStorage.servers()
            var fresh: [WidgetServer] = []
            for record in records.prefix(20) { fresh.append(record.backgroundRefresh ? await WidgetNetwork.refresh(record) : record) }
            fresh += records.dropFirst(20)
            completion(Timeline(entries: [FleetEntry(date: Date(), servers: fresh)], policy: .after(Date().addingTimeInterval(15 * 60))))
        }
    }
}
struct FleetWidgetView: View {
    let entry: FleetEntry
    @Environment(\.widgetFamily) private var family
    var capacity: Int { family == .systemLarge ? 6 : family == .systemMedium ? 3 : 1 }
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack { Label("Simplex Panel", systemImage: "server.rack").font(.headline); Spacer(); WidgetRefreshButton() }
            if entry.servers.isEmpty { Text("In der App Server für Widgets freigeben.").font(.caption).foregroundStyle(.secondary) }
            ForEach(entry.servers.prefix(capacity)) { server in
                HStack(spacing: 7) {
                    Circle().fill(server.state == "running" ? .green : server.state == "offline" ? .red : .orange).frame(width: 7, height: 7)
                    VStack(alignment: .leading, spacing: 3) { Text(server.name).font(.caption.bold()).lineLimit(1); Text(server.cpu.map { String(format: "CPU %.0f %%", $0) } ?? "CPU –").font(.caption2).foregroundStyle(.secondary) }
                    Spacer(minLength: 2)
                    VStack(alignment: .trailing, spacing: 3) { Text(widgetBytes(server.memory)).font(.caption.bold()).monospacedDigit(); Text(server.online.map { "\($0) Spieler" } ?? server.stateTitle).font(.caption2).foregroundStyle(.secondary) }
                }
            }
            if entry.servers.count > capacity { Text("+ \(entry.servers.count - capacity) weitere · App öffnen").font(.caption2).foregroundStyle(.secondary) }
            Spacer(minLength: 0)
            Text("\(entry.servers.filter { $0.state == "running" }.count) / \(entry.servers.count) online").font(.caption2).foregroundStyle(.secondary)
        }.containerBackground(for: .widget) { LinearGradient(colors: [.cyan.opacity(0.13), .purple.opacity(0.08)], startPoint: .topLeading, endPoint: .bottomTrailing) }.widgetURL(URL(string: "panel://widgets")).privacySensitive()
    }
}
struct FleetWidget: Widget {
    var body: some WidgetConfiguration { StaticConfiguration(kind: "PanelFleet", provider: FleetProvider()) { FleetWidgetView(entry: $0) }.configurationDisplayName("Alle Instanzen · direkt").description("Übersicht über Status, RAM und CPU. Ohne zusätzliche Serverauswahl.").supportedFamilies([.systemSmall, .systemMedium, .systemLarge]) }
}
enum WidgetMetric { case ram, cpu, uptime
    var title: String { switch self { case .ram: return "Arbeitsspeicher"; case .cpu: return "CPU"; case .uptime: return "Uptime" } }
    var symbol: String { switch self { case .ram: return "memorychip"; case .cpu: return "cpu"; case .uptime: return "clock" } }
}
func widgetBytes(_ value: Double?) -> String { value.map { ByteCountFormatter.string(fromByteCount: Int64(max(0, $0)), countStyle: .memory) } ?? "–" }
struct MetricWidgetView: View {
    let entry: ServerEntry
    let metric: WidgetMetric
    @Environment(\.widgetFamily) private var family
    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack { Image(systemName: metric.symbol).foregroundStyle(.cyan); Text(metric.title).font(.caption.bold()); Spacer(); WidgetRefreshButton(id: entry.server?.id ?? "") }
            if let server = entry.server {
                Text(server.name).font(.headline).lineLimit(1)
                if metric == .uptime, let seconds = server.uptime { Text(Duration.seconds(seconds).formatted(.time(pattern: .hourMinuteSecond))).font(.title2.bold()).monospacedDigit().minimumScaleFactor(0.65) }
                else if metric == .uptime { Text("–").font(.title.bold()) }
                else { Text(metric == .ram ? widgetBytes(server.memory) : server.cpu.map { String(format: "%.1f %%", $0) } ?? "–").font(.system(size: 32, weight: .bold)).monospacedDigit().minimumScaleFactor(0.6) }
                if metric == .ram, let value = server.memory, let limit = server.memoryLimit, limit > 0 { ProgressView(value: min(value, limit), total: limit).tint(.cyan); Text("von " + widgetBytes(limit)).font(.system(size: 10)).foregroundStyle(.secondary) }
                Spacer(minLength: 0)
                HStack { if family == .systemMedium { Text(server.issue ?? server.stateTitle).lineLimit(1) }; if let date = server.updated { Text(date, style: .time) } }.font(.system(size: 10)).foregroundStyle(.secondary)
            } else { Text("Server in der App freigeben und im Widget wählen.").font(.caption).foregroundStyle(.secondary) }
        }.containerBackground(for: .widget) { LinearGradient(colors: [.cyan.opacity(0.15), .indigo.opacity(0.08)], startPoint: .topLeading, endPoint: .bottomTrailing) }.widgetURL(URL(string: "panel://widgets")).privacySensitive()
    }
}
struct RAMWidget: Widget { var body: some WidgetConfiguration { AppIntentConfiguration(kind: "PanelRAM", intent: ServerConfiguration.self, provider: ServerProvider()) { MetricWidgetView(entry: $0, metric: .ram) }.configurationDisplayName("Server-RAM").description("Speicherverbrauch einer Instanz.").supportedFamilies([.systemSmall, .systemMedium]) } }
struct CPUWidget: Widget { var body: some WidgetConfiguration { AppIntentConfiguration(kind: "PanelCPU", intent: ServerConfiguration.self, provider: ServerProvider()) { MetricWidgetView(entry: $0, metric: .cpu) }.configurationDisplayName("Server-CPU").description("CPU-Auslastung einer Instanz.").supportedFamilies([.systemSmall, .systemMedium]) } }
struct UptimeWidget: Widget { var body: some WidgetConfiguration { AppIntentConfiguration(kind: "PanelUptime", intent: ServerConfiguration.self, provider: ServerProvider()) { MetricWidgetView(entry: $0, metric: .uptime) }.configurationDisplayName("Server-Uptime").description("Laufzeit seit dem letzten Serverstart.").supportedFamilies([.systemSmall, .systemMedium]) } }
struct PlayersWidgetView: View {
    let entry: ServerEntry
    @Environment(\.widgetFamily) private var family
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack { Label("Online-Spieler", systemImage: "person.2.fill").font(.headline); Spacer(); WidgetRefreshButton(id: entry.server?.id ?? "") }
            if let server = entry.server {
                HStack { Text(server.name).font(.caption.bold()).lineLimit(1); Spacer(); Text(server.online.map { "\($0) online" } ?? "–").font(.caption.bold()).foregroundStyle(.cyan) }
                let names = server.playerNames ?? []
                let capacity = family == .systemMedium ? 6 : 42
                if names.isEmpty { Text(server.online == 0 ? "Gerade niemand online" : "Namen noch nicht verfügbar. Server öffnen oder Widget-Zugang aktualisieren.").font(.caption).foregroundStyle(.secondary) }
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), alignment: .leading), count: 3), alignment: .leading, spacing: 4) {
                    ForEach(Array(names.prefix(capacity).enumerated()), id: \.offset) { _, name in Text(name).font(.system(size: family == .systemMedium ? 10 : 11, weight: .medium)).lineLimit(1).minimumScaleFactor(0.7) }
                }
                if names.count > capacity { Text("+ \(names.count - capacity) weitere · App öffnen").font(.caption2).foregroundStyle(.secondary) }
                Spacer(minLength: 0)
                if let date = server.playersUpdated { HStack { Text("Spielerstand"); Text(date, style: .time) }.font(.caption2).foregroundStyle(.secondary) }
                if let issue = server.issue { Text(issue).font(.caption2).foregroundStyle(.orange).lineLimit(1) }
            } else { Text("In der App einen Server für Widgets freigeben.").font(.caption) }
        }.containerBackground(for: .widget) { LinearGradient(colors: [.purple.opacity(0.12), .cyan.opacity(0.08)], startPoint: .topLeading, endPoint: .bottomTrailing) }.widgetURL(URL(string: "panel://widgets")).privacySensitive()
    }
}
struct OnlinePlayersWidget: Widget { var body: some WidgetConfiguration { AppIntentConfiguration(kind: "PanelOnlinePlayers", intent: ServerConfiguration.self, provider: ServerProvider()) { PlayersWidgetView(entry: $0) }.configurationDisplayName("Online-Spieler · große Liste").description("Spielernamen für AMP und gekoppelte Simplex-Server. Bis zu 42 Namen im großen Widget.").supportedFamilies([.systemMedium, .systemLarge]) } }
struct CopiedConfiguration: WidgetConfigurationIntent {
    static var title: LocalizedStringResource = "Kopierten Widget-Datenstand anzeigen"
    @Parameter(title: "Daten-Code aus der App") var code: String?
}
struct CopiedProvider: AppIntentTimelineProvider {
    func placeholder(in context: Context) -> ServerEntry { ServerEntry(date: Date(), server: nil) }
    func snapshot(for configuration: CopiedConfiguration, in context: Context) async -> ServerEntry { ServerEntry(date: Date(), server: WidgetStorage.copied(configuration.code ?? "")) }
    func timeline(for configuration: CopiedConfiguration, in context: Context) async -> Timeline<ServerEntry> { Timeline(entries: [await snapshot(for: configuration, in: context)], policy: .never) }
}
struct CopiedWidget: Widget { var body: some WidgetConfiguration { AppIntentConfiguration(kind: "PanelCopiedSnapshot", intent: CopiedConfiguration.self, provider: CopiedProvider()) { PlayersWidgetView(entry: $0) }.configurationDisplayName("Manueller Datenstand").description("Daten-Code ohne Zugangsdaten; feste Kopie für Signaturen ohne gemeinsamen Speicher.").supportedFamilies([.systemMedium, .systemLarge]) } }
