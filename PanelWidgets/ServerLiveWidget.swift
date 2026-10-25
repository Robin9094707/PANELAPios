import SwiftUI
import WidgetKit
import ActivityKit

struct ServerLiveWidget: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: ServerActivity.self) { context in
            VStack(alignment: .leading, spacing: 10) {
                HStack { Label(context.attributes.name, systemImage: "server.rack").font(.headline); Spacer(); Text(context.state.stateTitle).font(.caption).foregroundStyle(context.state.state == "running" ? .green : .orange) }
                HStack(alignment: .firstTextBaseline) {
                    Label(players(context.state), systemImage: "person.2.fill").font(.title2.bold()).foregroundStyle(.teal)
                    Spacer()
                    Text(context.state.cpu.map { String(format: "%.1f %% CPU", $0) } ?? "CPU –").font(.caption)
                    Text(context.state.memory.map { ByteCountFormatter.string(fromByteCount: Int64($0), countStyle: .memory) } ?? "RAM –").font(.caption)
                }
                performance(context.state)
                freshness(context)
            }.padding().privacySensitive().activityBackgroundTint(Color.black.opacity(0.08)).widgetURL(URL(string: "panel://widgets"))
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) { Label(context.attributes.name, systemImage: "server.rack").font(.headline).lineLimit(1) }
                DynamicIslandExpandedRegion(.trailing) { Text(context.state.stateTitle).font(.caption) }
                DynamicIslandExpandedRegion(.bottom) {
                    VStack(alignment: .leading, spacing: 6) {
                        HStack { Label(players(context.state) + " Spieler", systemImage: "person.2.fill"); Spacer(); Text(context.state.cpu.map { String(format: "%.1f %% CPU", $0) } ?? "CPU –") }.font(.subheadline)
                        performance(context.state); freshness(context)
                    }
                }
            } compactLeading: { Image(systemName: "server.rack").foregroundStyle(.teal) } compactTrailing: { Text(context.isStale ? "–" : context.state.playerLabel).font(.caption).monospacedDigit() } minimal: { Image(systemName: "server.rack").foregroundStyle(.teal) }
                .widgetURL(URL(string: "panel://widgets")).keylineTint(.teal)
        }
    }
    func players(_ state: ServerActivity.ContentState) -> String { state.playerLabel + " / " + (state.maximum.map(String.init) ?? "–") }
    @ViewBuilder func performance(_ state: ServerActivity.ContentState) -> some View {
        HStack {
            if let tps = state.tps { Text(String(format: "%.1f TPS", tps)).foregroundStyle(tps >= 19 ? .green : .orange) }
            if let mspt = state.mspt, mspt >= 0 { Text(String(format: "%.1f ms", mspt)) }
            Spacer()
            if let uptime = state.uptime { Text(Duration.seconds(max(0, uptime)).formatted(.time(pattern: .hourMinuteSecond))) }
        }.font(.caption).monospacedDigit()
    }
    func freshness(_ context: ActivityViewContext<ServerActivity>) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack { Text(context.isStale ? "Stand veraltet · App öffnen" : context.state.source + " · letzter Stand"); Spacer(); Text(context.state.updated, style: .time) }
            if let date = context.state.playersUpdated { Text("Spieler: " + date.formatted(date: .omitted, time: .standard)) }
            if let date = context.state.performanceUpdated { Text("TPS: " + date.formatted(date: .omitted, time: .standard)) }
            if let issue = context.state.issue { Text(issue).foregroundStyle(.orange).lineLimit(1) }
        }.font(.caption2).foregroundStyle(.secondary)
    }
}
