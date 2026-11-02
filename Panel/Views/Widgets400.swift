import SwiftUI
import Charts

/// 4.0 in-app widgets: one chart component for CPU, RAM and combined history, styled by the
/// widget settings in „Startseite/Workspace anpassen“ (area, line or bars, optional grid).
struct PanelHistoryChart: View {
    let points: [ResourcePoint]
    let label: String
    var tint: Color = Color.panelAccent
    var maximum: Double? = nil
    let value: (ResourcePoint) -> Double
    @AppStorage("widgets.chartStyle") private var style = "area"
    @AppStorage("widgets.chartGrid") private var grid = false
    var upper: Double { maximum ?? max(1, (points.map(value).max() ?? 1) * 1.15) }
    var body: some View {
        Chart(points) { point in
            if style == "bars" {
                BarMark(x: .value("Zeit", point.date), y: .value(label, value(point))).foregroundStyle(tint.gradient)
            } else if style == "line" {
                LineMark(x: .value("Zeit", point.date), y: .value(label, value(point))).foregroundStyle(tint).interpolationMethod(.catmullRom).lineStyle(StrokeStyle(lineWidth: 2.5, lineCap: .round))
            } else {
                AreaMark(x: .value("Zeit", point.date), y: .value(label, value(point))).foregroundStyle(LinearGradient(colors: [tint.opacity(0.32), tint.opacity(0.02)], startPoint: .top, endPoint: .bottom)).interpolationMethod(.catmullRom)
                LineMark(x: .value("Zeit", point.date), y: .value(label, value(point))).foregroundStyle(tint).interpolationMethod(.catmullRom).lineStyle(StrokeStyle(lineWidth: 2))
            }
        }
        .chartYScale(domain: 0...upper)
        .chartXAxis(.hidden)
        .chartYAxis(grid ? .visible : .hidden)
        .accessibilityLabel(label)
    }
}

/// Header row used by all chart widgets: title, live value and a small trend arrow.
struct PanelWidgetHeader: View {
    let title: String
    let symbol: String
    let value: String
    var trend: Double = 0
    var tint: Color = Color.panelAccent
    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            Label(title, systemImage: symbol).font(.subheadline.weight(.semibold)).foregroundStyle(tint)
            Spacer()
            if abs(trend) > 0.5 { Image(systemName: trend > 0 ? "arrow.up.right" : "arrow.down.right").font(.caption.bold()).foregroundStyle(trend > 0 ? .orange : .green).accessibilityLabel(trend > 0 ? "steigend" : "fallend") }
            Text(value).font(.system(.title3, design: .rounded, weight: .bold)).monospacedDigit().contentTransition(.numericText())
        }
    }
}

/// Two series on one percentage scale (CPU against its limit or one core, RAM against the plan limit).
struct PanelCombinedChart: View {
    struct Sample: Identifiable { let id = UUID(); let date: Date; let series: String; let percent: Double }
    let points: [ResourcePoint]
    let cpuLimit: Double
    let memoryLimit: Double
    @AppStorage("widgets.chartGrid") private var grid = false
    var samples: [Sample] {
        points.flatMap { p in
            [Sample(date: p.date, series: "CPU", percent: min(100, p.cpu / max(1, cpuLimit > 0 ? cpuLimit : 100) * 100)),
             Sample(date: p.date, series: "RAM", percent: memoryLimit > 0 ? min(100, p.memory / memoryLimit * 100) : 0)]
        }
    }
    var body: some View {
        Chart(samples) { sample in
            LineMark(x: .value("Zeit", sample.date), y: .value("Auslastung %", sample.percent)).foregroundStyle(by: .value("Serie", sample.series)).interpolationMethod(.catmullRom).lineStyle(StrokeStyle(lineWidth: 2.2, lineCap: .round))
        }
        .chartForegroundStyleScale(["CPU": Color.panelAccent, "RAM": Color.panelSecondary])
        .chartYScale(domain: 0...100)
        .chartXAxis(.hidden)
        .chartYAxis(grid ? .visible : .hidden)
        .chartLegend(position: .top, alignment: .leading)
        .accessibilityLabel("CPU und RAM in Prozent")
    }
}

/// Launch animation (4.0): a short, calm logo reveal over the app background. Can be turned off in
/// „Farben & App-Icon“ and is skipped with Reduce Motion.
struct LaunchAnimationView: View {
    @Binding var visible: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var phase = 0.0
    var body: some View {
        ZStack {
            PanelBackground()
            VStack(spacing: 18) {
                ZStack {
                    Circle().fill(LinearGradient(colors: [Color.panelAccent, Color.panelSecondary], startPoint: .topLeading, endPoint: .bottomTrailing)).frame(width: 112, height: 112).scaleEffect(0.7 + 0.3 * phase).blur(radius: 26 * (1 - phase)).opacity(0.55)
                    RoundedRectangle(cornerRadius: 28).fill(LinearGradient(colors: [Color.panelAccent, Color.panelSecondary], startPoint: .topLeading, endPoint: .bottomTrailing)).frame(width: 92, height: 92).shadow(color: Color.panelAccent.opacity(0.35), radius: 18, y: 8)
                        .overlay { Image(systemName: "server.rack").font(.system(size: 40, weight: .semibold)).foregroundStyle(.white).symbolEffect(.bounce, value: phase) }
                        .scaleEffect(0.82 + 0.18 * phase).rotationEffect(.degrees(-8 * (1 - phase)))
                }
                VStack(spacing: 4) {
                    Text("Simplex Panel").font(.system(.title, design: .rounded, weight: .bold))
                    Text("Alles unter Kontrolle.").font(.subheadline).foregroundStyle(.secondary)
                }.opacity(phase).offset(y: 10 * (1 - phase))
            }
        }
        .accessibilityHidden(true)
        .task {
            if reduceMotion { visible = false; return }
            withAnimation(.spring(response: 0.7, dampingFraction: 0.72)) { phase = 1 }
            try? await Task.sleep(for: .milliseconds(950))
            withAnimation(.easeOut(duration: 0.35)) { visible = false }
        }
    }
}
