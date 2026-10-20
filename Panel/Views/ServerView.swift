import SwiftUI
import Charts

struct ServerView: View {
    let api: PanelAPI
    private let initialServer: Server
    var server: Server { store.servers.first { $0.id == initialServer.id } ?? initialServer }
    let initialFolder: String?
    init(api: PanelAPI, server: Server, initialFolder: String? = nil) {
        self.api = api; self.initialServer = server; self.initialFolder = initialFolder
        _tab = State(initialValue: initialFolder == nil ? 0 : 2)
    }
    @EnvironmentObject private var store: AppStore
    @EnvironmentObject private var workspace: WorkspaceStore
    @State private var commandLibrary = false
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
            Tab("Konsole", systemImage: "terminal", value: 1) { AccessBoundary(access: api.access, server: server.id, permission: "websocket.connect") { console } }
            Tab("Dateien", systemImage: "folder", value: 2) { AccessBoundary(access: api.access, server: server.id, permission: "file.read") { FilesView(api: api, server: server, root: initialFolder ?? "/") } }
            Tab("Backups", systemImage: "externaldrive.badge.timemachine", value: 3) { AccessBoundary(access: api.access, server: server.id, permission: "backup.read") { BackupsView(api: api, server: server) } }
            Tab("Verwalten", systemImage: "square.grid.2x2", value: 4) { ManageView(api: api, server: server, live: live) }
        }.navigationTitle(server.name).navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Server aktualisieren", systemImage: "arrow.clockwise") { Task { await refreshServer() } } }; ToolbarItem(placement: .topBarTrailing) { powerMenu } }
            .task(id: "\(scenePhase)-\(workspace.isMinecraft(api, server))") {
                if scenePhase == .active {
                    await api.loadAccess(server: server.id)
                    guard !Task.isCancelled else { return }
                    await live.run(api: api, server: server, minecraft: workspace.isMinecraft(api, server))
                }
            }
            .onChange(of: live.lastUpdate) { _, _ in WidgetPublisher.publish(api: api, server: server, live: live) }
            .onChange(of: live.players) { _, _ in WidgetPublisher.publish(api: api, server: server, live: live) }
            .onChange(of: live.state) { _, state in store.presence[server.id] = ServerPresence(state: state) }
            .sheet(item: $operation) { OperationSheet(api: api, operation: $0) }
            .sheet(isPresented: $commandLibrary) { CommandLibrary(api: api, server: server) { command = $0 } }
            .errorAlert($error)
    }
    var powerMenu: some View {
        Menu {
            Button("Starten", systemImage: "play.fill") { power("start", title: "Server starten") }.disabled(!PowerPolicy.allows("start", state: live.state)).requiresAccess(api, server.id, "control.start")
            Button("Neustarten", systemImage: "arrow.clockwise") { power("restart", title: "Server neustarten", destructive: true) }.disabled(!PowerPolicy.allows("restart", state: live.state)).requiresAccess(api, server.id, "control.restart")
            Button("Stoppen", systemImage: "stop.fill", role: .destructive) { power("stop", title: "Server stoppen", destructive: true) }.disabled(!PowerPolicy.allows("stop", state: live.state)).requiresAccess(api, server.id, "control.stop")
            Button("Beenden erzwingen", systemImage: "bolt.slash", role: .destructive) { power("kill", title: "Beenden erzwingen", destructive: true) }.disabled(!PowerPolicy.allows("kill", state: live.state)).requiresAccess(api, server.id, "control.stop")
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
                AccessSummary(access: api.access, api: api, server: server.id)
                Toggle("Minecraft-Spielerübersicht", isOn: Binding(get: { workspace.isMinecraft(api, server) }, set: { workspace.setMinecraft($0, api, server) })).font(.subheadline)
                if workspace.isMinecraft(api, server) { PlayersCard(live: live, api: api, server: server); TPSCard(live: live, api: api, server: server) }
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
        }.panelScreen().refreshable { await refreshServer() }.scrollBounceBehavior(.always)
    }
    func refreshServer() async {
        await api.loadAccess(server: server.id); await store.refresh()
        do { try await live.refresh(api: api, server: server, minecraft: workspace.isMinecraft(api, server)) } catch { self.error = error.localizedDescription }
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
                    Button("Gefilterte Ausgabe kopieren", systemImage: "doc.on.doc") { UIPasteboard.general.string = live.lines.filter { filter.isEmpty || $0.cleanedForSearch.localizedCaseInsensitiveContains(filter) }.map(cleanConsole).joined(separator: "\n") }
                    Button("Schrift vergrößern") { fontSize = min(22, fontSize + 1) }
                    Button("Schrift verkleinern") { fontSize = max(10, fontSize - 1) }
                    Button("Anzeige leeren", role: .destructive) { live.lines = [] }
                } label: { Image(systemName: "ellipsis.circle").padding(6) }
            }.padding(.horizontal, 16).padding(.vertical, 8)
            TextField("Ausgabe filtern", text: $filter).textFieldStyle(.roundedBorder).padding(.horizontal, 16).padding(.bottom, 8).autocorrectionDisabled().textInputAutocapitalization(.never)
            ConsoleTextView(text: live.lines.filter { filter.isEmpty || $0.cleanedForSearch.localizedCaseInsensitiveContains(filter) }.joined(separator: "\n"), fontSize: fontSize, follow: follow, refresh: { await refreshServer() })
                .frame(maxWidth: .infinity, maxHeight: .infinity).layoutPriority(1).background(.black.opacity(0.08))
            ScrollView(.horizontal, showsIndicators: false) {
                HStack {
                    Button("Shortcuts", systemImage: "bolt") { commandLibrary = true }.buttonStyle(.glass)
                    ForEach(workspace.shortcuts[workspace.key(api, server)] ?? []) { shortcut in
                        Button(shortcut.name) { command = shortcut.command }.buttonStyle(.glass)
                    }
                    if workspace.isMinecraft(api, server) {
                        ForEach(CommandLibrary.suggestions.filter { command.isEmpty ? ["list", "tps", "save-all"].contains($0) : $0.hasPrefix(command.trimmingCharacters(in: CharacterSet(charactersIn: "/"))) && $0 != command }, id: \.self) { suggestion in
                            Button(suggestion) { command = suggestion }.buttonStyle(.glass)
                        }
                    }
                }.padding(.horizontal, 12).padding(.vertical, 8)
            }.accessibilityLabel("Lokale Befehlsvorschläge und Shortcuts")
            HStack(spacing: 12) {
                Text(">").font(.system(.body, design: .monospaced)).foregroundStyle(.teal)
                TextField("Befehl eingeben …", text: $command).font(.system(.body, design: .monospaced)).autocorrectionDisabled().textInputAutocapitalization(.never).submitLabel(.send).onSubmit(sendCommand).accessibilityIdentifier("consoleCommand").requiresAccess(api, server.id, "control.console")
                Button(action: sendCommand) { if sending { ProgressView() } else { Image(systemName: "arrow.up").fontWeight(.bold) } }.buttonStyle(.glassProminent).disabled(sending || command.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !live.connected).accessibilityLabel("Befehl senden").requiresAccess(api, server.id, "control.console")
            }.padding(16).background(.bar)
        }.background { PanelBackground() }.safeAreaInset(edge: .bottom, spacing: 0) { Color.clear.frame(height: 1) }
    }
    func sendCommand() {
        var text = command.trimmingCharacters(in: .whitespacesAndNewlines)
        if workspace.isMinecraft(api, server) && text.hasPrefix("/") { text.removeFirst() }
        guard !text.isEmpty, !sending else { return }
        sending = true
        Task { do {
            guard !api.demo else { throw PanelError(message: "Im Demomodus werden keine Befehle gesendet.") }
            try await live.sendCommand(text, api: api, server: server); command = ""; Feedback.success()
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
