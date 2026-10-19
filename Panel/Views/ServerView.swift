import SwiftUI
import Charts

struct ServerView: View {
    let api: PanelAPI
    let server: Server
    @StateObject private var live = LiveServer()
    @Environment(\.scenePhase) private var scenePhase
    @State private var tab = 0
    @State private var operation: Operation?
    @State private var error: String?
    @State private var command = ""
    @State private var sending = false
    @State private var follow = true
    @State private var filter = ""
    @State private var fontSize = 12.0
    var body: some View {
        TabView(selection: $tab) {
            Tab("Übersicht", systemImage: "gauge.with.dots.needle.67percent", value: 0) { overview }
            Tab("Konsole", systemImage: "terminal", value: 1) { console }
            Tab("Dateien", systemImage: "folder", value: 2) { FilesView(api: api, server: server, root: "/") }
            Tab("Backups", systemImage: "externaldrive.badge.timemachine", value: 3) { BackupsView(api: api, server: server) }
            Tab("Verwalten", systemImage: "square.grid.2x2", value: 4) { ManageView(api: api, server: server) }
        }.navigationTitle(server.name).navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { powerMenu } }
            .task(id: scenePhase) {
                if scenePhase == .active { await withTaskCancellationHandler { await live.run(api: api, server: server) } onCancel: { Task { @MainActor in live.stop() } } }
                else { live.stop() }
            }
            .onDisappear { live.stop() }
            .sheet(item: $operation) { OperationSheet(api: api, operation: $0) }
            .errorAlert($error)
    }
    var powerMenu: some View {
        Menu {
            Button("Starten", systemImage: "play.fill") { power("start", title: "Server starten") }
            Button("Neustarten", systemImage: "arrow.clockwise") { power("restart", title: "Server neustarten", destructive: true) }
            Button("Stoppen", systemImage: "stop.fill", role: .destructive) { power("stop", title: "Server stoppen", destructive: true) }
            Button("Beenden erzwingen", systemImage: "bolt.slash", role: .destructive) { power("kill", title: "Beenden erzwingen", destructive: true) }
        } label: { Image(systemName: "power").foregroundStyle(.teal).accessibilityLabel("Server steuern") }
    }
    func power(_ signal: String, title: String, destructive: Bool = false) {
        operation = Operation(title: title, detail: signal == "kill" ? "Der Prozess wird sofort beendet. Nicht gespeicherte Daten können verloren gehen." : "Die Aktion wird für \(server.name) ausgeführt.", path: "servers/\(server.id)/power", fixed: ["signal": .string(signal)], destructive: destructive, submit: "Ausführen")
    }
    var overview: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                HStack { VStack(alignment: .leading, spacing: 8) { Text(server.name).font(.largeTitle.bold()); Text(server.address).font(.system(.subheadline, design: .monospaced)).foregroundStyle(.secondary).textSelection(.enabled) }; Spacer(); StatePill(state: live.state) }
                HStack(spacing: 7) { Circle().fill(live.connected ? Color.green : Color.orange).frame(width: 6, height: 6); Text(live.connection); Spacer(); if let updated = live.lastUpdate { Text(updated, style: .time).monospacedDigit() } }.font(.caption).foregroundStyle(.secondary)
                if let issue = live.issue, !live.connected { Label(issue, systemImage: "wifi.exclamationmark").font(.footnote).foregroundStyle(.orange).card() }
                if server.data["is_suspended"].bool || server.data["is_installing"].bool || server.data["is_node_under_maintenance"].bool {
                    Label(server.data["is_suspended"].bool ? "Server ist gesperrt" : server.data["is_installing"].bool ? "Installation läuft" : "Node wird gewartet", systemImage: "exclamationmark.triangle").foregroundStyle(.orange).card()
                }
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 155), spacing: 14)], spacing: 14) {
                    MetricCard(title: "Arbeitsspeicher", symbol: "memorychip", value: live.lastUpdate == nil ? "–" : bytes(live.memory), limit: limitBytes("memory"), fraction: fraction(live.memory, "memory"), tint: .teal)
                    MetricCard(title: "CPU", symbol: "cpu", value: live.lastUpdate == nil ? "–" : String(format: "%.1f %%", live.cpu), limit: server.data["limits"]["cpu"].double > 0 ? "von \(server.data["limits"]["cpu"].string) %" : "Unbegrenzt", fraction: server.data["limits"]["cpu"].double > 0 ? live.cpu / server.data["limits"]["cpu"].double : nil, tint: .indigo)
                    MetricCard(title: "Speicherplatz", symbol: "internaldrive", value: live.lastUpdate == nil ? "–" : bytes(live.disk), limit: limitBytes("disk"), fraction: fraction(live.disk, "disk"), tint: .blue)
                    MetricCard(title: "Laufzeit", symbol: "clock", value: live.lastUpdate == nil ? "–" : Duration.seconds(live.uptime).formatted(.time(pattern: .hourMinuteSecond)), limit: live.state == "offline" ? "Server ist offline" : "seit dem letzten Start", fraction: nil, tint: .orange)
                }
                VStack(alignment: .leading, spacing: 16) {
                    HStack { Text("CPU-Verlauf").font(.headline); Spacer(); Text("Live · letzte 120 Werte").font(.caption).foregroundStyle(.secondary) }
                    if live.history.isEmpty { ContentUnavailableView("Warte auf Messwerte", systemImage: "waveform.path.ecg").frame(height: 150) }
                    else {
                        Chart(live.history) { point in
                            AreaMark(x: .value("Zeit", point.date), y: .value("CPU", point.cpu)).foregroundStyle(LinearGradient(colors: [.teal.opacity(0.25), .teal.opacity(0.01)], startPoint: .top, endPoint: .bottom)).interpolationMethod(.catmullRom)
                            LineMark(x: .value("Zeit", point.date), y: .value("CPU", point.cpu)).foregroundStyle(.teal).interpolationMethod(.catmullRom)
                        }.chartYScale(domain: 0...max(100, (live.history.map(\.cpu).max() ?? 100) * 1.1)).chartXAxis(.hidden).frame(height: 150).accessibilityLabel("CPU-Verlauf")
                    }
                }.card()
                VStack(alignment: .leading, spacing: 16) {
                    Text("Netzwerk & Verbindung").font(.headline)
                    LabeledContent("Empfangen", value: bytes(live.stats["network"]["rx_bytes"].double))
                    LabeledContent("Gesendet", value: bytes(live.stats["network"]["tx_bytes"].double))
                    LabeledContent("Node", value: server.node)
                    Text("CPU: 100 % entsprechen einem CPU-Kern. RAM- und Festplattenlimits werden vom Panel in MiB geliefert.").font(.caption).foregroundStyle(.secondary)
                }.card()
            }.padding(20).frame(maxWidth: 1000).frame(maxWidth: .infinity)
        }.panelScreen()
    }
    func limitBytes(_ key: String) -> String { let limit = server.data["limits"][key].double; return limit > 0 ? "von " + bytes(limit * 1_048_576) : "Unbegrenzt" }
    func fraction(_ value: Double, _ key: String) -> Double? { let limit = server.data["limits"][key].double; return limit > 0 ? value / (limit * 1_048_576) : nil }
    var console: some View {
        VStack(spacing: 0) {
            HStack {
                Label(live.connected ? "Live-Konsole" : "Verbindung getrennt", systemImage: live.connected ? "dot.radiowaves.left.and.right" : "wifi.slash").font(.caption).foregroundStyle(live.connected ? Color.teal : Color.orange)
                Spacer()
                Menu {
                    Toggle("Automatisch scrollen", isOn: $follow)
                    Button("Schrift vergrößern") { fontSize = min(22, fontSize + 1) }
                    Button("Schrift verkleinern") { fontSize = max(10, fontSize - 1) }
                    Button("Anzeige leeren", role: .destructive) { live.lines = [] }
                } label: { Image(systemName: "ellipsis.circle").padding(6) }
            }.padding(.horizontal, 16).padding(.vertical, 8)
            TextField("Ausgabe filtern", text: $filter).textFieldStyle(.roundedBorder).padding(.horizontal, 16).padding(.bottom, 8).autocorrectionDisabled().textInputAutocapitalization(.never)
            ScrollViewReader { proxy in
                ScrollView([.vertical, .horizontal]) {
                    LazyVStack(alignment: .leading, spacing: 3) {
                        ForEach(Array(live.lines.enumerated()), id: \.offset) { index, line in
                            if filter.isEmpty || line.localizedCaseInsensitiveContains(filter) {
                                Text(line.isEmpty ? " " : line).font(.system(size: fontSize, design: .monospaced)).foregroundStyle(line.contains("ERROR") || line.contains("SEVERE") ? Color.red : line.contains("WARN") ? Color.orange : Color.primary.opacity(0.85)).textSelection(.enabled)
                            }
                        }
                        Color.clear.frame(height: 1).id("end")
                    }.padding(16).frame(minWidth: 300, alignment: .leading)
                }.background(.black.opacity(0.14))
                    .onChange(of: live.lines.count) { _, _ in if follow { proxy.scrollTo("end", anchor: .bottom) } }
            }
            HStack(spacing: 12) {
                Text(">").font(.system(.body, design: .monospaced)).foregroundStyle(.teal)
                TextField("Befehl eingeben …", text: $command).font(.system(.body, design: .monospaced)).autocorrectionDisabled().textInputAutocapitalization(.never).submitLabel(.send).onSubmit(sendCommand).accessibilityIdentifier("consoleCommand")
                Button(action: sendCommand) { if sending { ProgressView() } else { Image(systemName: "arrow.up").fontWeight(.bold) } }.buttonStyle(.glassProminent).disabled(sending || command.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !live.connected).accessibilityLabel("Befehl senden")
            }.padding(16).background(.bar)
        }.background { PanelBackground() }.safeAreaInset(edge: .bottom, spacing: 0) { Color.clear.frame(height: 1) }
    }
    func sendCommand() {
        let text = command.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, !sending else { return }
        sending = true
        Task { do {
            guard !api.demo else { throw PanelError(message: "Im Demomodus werden keine Befehle gesendet.") }
            try await api.mutate("servers/\(server.id)/command", ["command": text]); command = ""
        } catch { self.error = error.localizedDescription }; sending = false }
    }
}
struct MetricCard: View {
    let title: String
    let symbol: String
    let value: String
    let limit: String
    let fraction: Double?
    let tint: Color
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Label(title, systemImage: symbol).font(.caption.weight(.semibold)).foregroundStyle(tint)
            Text(value).font(.system(.title2, design: .rounded, weight: .bold)).monospacedDigit().minimumScaleFactor(0.65).lineLimit(1).contentTransition(.numericText())
            Text(limit).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            if let fraction { ProgressView(value: min(1, max(0, fraction))).tint(fraction > 0.9 ? .red : tint).accessibilityLabel(title) }
            else { Capsule().fill(tint.opacity(0.14)).frame(height: 4) }
        }.frame(maxWidth: .infinity, alignment: .leading).card()
    }
}
