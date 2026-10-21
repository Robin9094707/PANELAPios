import SwiftUI
import Charts

struct ServerView: View {
    let api: PanelAPI
    private let initialServer: Server
    var server: Server { store.servers.first { $0.id == initialServer.id } ?? initialServer }
    let initialFolder: String?
    init(api: PanelAPI, server: Server, initialFolder: String? = nil) {
        self.api = api; self.initialServer = server; self.initialFolder = initialFolder
        _suite = StateObject(wrappedValue: SuiteModel(panel: api, server: server))
        _tab = State(initialValue: initialFolder == nil ? 0 : 2)
    }
    @EnvironmentObject private var store: AppStore
    @EnvironmentObject private var workspace: WorkspaceStore
    @State private var commandLibrary = false
    @ObservedObject private var suiteConnections = SuiteConnections.shared
    @StateObject private var suite: SuiteModel
    @State private var refreshing = false
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
    @ObservedObject private var layouts = DashboardLayouts.shared
    @ObservedObject private var consoleHistory = ConsoleHistory.shared
    @State private var editLayout = false
    @State private var iconRevision = 0
    @State private var historySheet = false
    @State private var historyCursor = CommandHistoryCursor()
    @StateObject private var viewport = ConsoleViewportState()
    @StateObject private var consoleProjection = ConsoleProjection()
    @State private var consoleCategory = ConsoleCategory.all
    @State private var consoleEpoch = 0
    var consoleText: String { consoleProjection.result.text }
    @State private var frozen = false
    @State private var frozenLines: [String] = []
    @State private var jumpRevision = 0
    var body: some View {
        TabView(selection: $tab) {
            Tab("Übersicht", systemImage: "gauge.with.dots.needle.67percent", value: 0) { overview }
            Tab("Konsole", systemImage: "terminal", value: 1) { AccessBoundary(access: api.access, server: server.id, permission: "websocket.connect") { console } }
            Tab("Dateien", systemImage: "folder", value: 2) { AccessBoundary(access: api.access, server: server.id, permission: "file.read") { FilesView(api: api, server: server, root: initialFolder ?? "/") } }
            if suite.configured {
                Tab("Statistiken", systemImage: "chart.bar.xaxis", value: 5) { SuiteStatisticsView(model: suite, live: live) }
            }
            Tab("Backups", systemImage: "externaldrive.badge.timemachine", value: 3) { AccessBoundary(access: api.access, server: server.id, permission: "backup.read") { if api.amp != nil { AMPBackupsView(api: api, server: server) } else { BackupsView(api: api, server: server) } } }
            Tab("Verwalten", systemImage: "square.grid.2x2", value: 4) { ManageView(api: api, server: server, suite: suite, live: live) }
        }.navigationTitle(server.name).navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Workspace anpassen", systemImage: "rectangle.3.group") { editLayout = true }.accessibilityIdentifier("editServerLayout") }; ToolbarItem(placement: .topBarTrailing) { Button("Server aktualisieren", systemImage: "arrow.clockwise") { Task { await refreshServer() } } }; ToolbarItem(placement: .topBarTrailing) { powerMenu } }
            .task(id: "\(scenePhase)-\(workspace.isMinecraft(api, server))") {
                if scenePhase == .active {
                    await api.loadAccess(server: server.id)
                    guard !Task.isCancelled else { return }
                    await live.run(api: api, server: server, minecraft: workspace.isMinecraft(api, server))
                }
            }
            .task(id: "\(scenePhase)-\(suiteConnections.values)") {
                guard scenePhase == .active, suite.configured else { if !suite.configured { await suite.load() }; return }
                await suite.load()
                while !Task.isCancelled {
                    do { try await Task.sleep(for: .seconds(30)) } catch { return }
                    await suite.load(force: true)
                }
            }
            .onChange(of: suite.configured) { _, configured in if !configured && tab == 5 { tab = 0 } }
            .onChange(of: tab) { _, value in if value == 1 { updateConsoleText() } else { consoleProjection.stop() } }
            .onChange(of: scenePhase) { _, phase in if phase == .active && tab == 1 { updateConsoleText() } else if phase != .active { consoleProjection.stop() } }
            .onChange(of: live.lastUpdate) { _, _ in WidgetPublisher.publish(api: api, server: server, live: live) }
            .onChange(of: live.players) { _, _ in WidgetPublisher.publish(api: api, server: server, live: live) }
            .onChange(of: live.state) { _, state in store.presence[server.id] = ServerPresence(state: state) }
            .sheet(isPresented: $editLayout) { DashboardEditor(scope: .server, layoutKey: layoutKey) }
            .sheet(isPresented: $historySheet) { ConsoleHistorySheet(historyKey: historyKey) { command = $0; historyCursor = CommandHistoryCursor(); tab = 1 } }
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
        } label: { Image(systemName: "power").foregroundStyle(Color.panelAccent).accessibilityLabel("Server steuern") }
    }
    func power(_ signal: String, title: String, destructive: Bool = false) {
        operation = Operation(title: title, detail: signal == "kill" ? "Der Prozess wird sofort beendet. Nicht gespeicherte Daten können verloren gehen." : "Die Aktion wird für \(server.name) ausgeführt.", path: "servers/\(server.id)/power", fixed: ["signal": .string(signal)], destructive: destructive, submit: "Ausführen")
    }
    var overview: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                ForEach(layouts.cards(layoutKey, scope: .server)) { card in overviewCard(card) }
            }.padding(20).frame(maxWidth: 1000).frame(maxWidth: .infinity)
        }.panelScreen().refreshable { await refreshServer() }.scrollBounceBehavior(.always)
    }
    var layoutKey: String { "server:" + api.base.absoluteString + "|" + (api.demo ? "demo:" : "") + server.id }
    var historyKey: String { workspace.key(api, server) }
    var commandEntries: [String] { consoleHistory.entries(historyKey) }
    @ViewBuilder func overviewCard(_ card: DashboardCard) -> some View {
        switch card {
        case .identity: identityCard
        case .players: playersSection
        case .resources: resourceCards
        case .cpuHistory: cpuCard
        case .network: networkCard
        case .health: healthCard
        case .memoryHistory: memoryCard
        case .shortcuts:
            VStack(alignment: .leading, spacing: 14) {
                Label("Konsolen-Schnellzugriff", systemImage: "terminal").font(.headline)
                Button("Konsole öffnen", systemImage: "terminal") { tab = 1 }.buttonStyle(.glass)
                Button("Befehlsverlauf", systemImage: "clock.arrow.circlepath") { historySheet = true }
                ForEach(workspace.shortcuts[workspace.key(api, server)] ?? []) { shortcut in
                    Button(shortcut.name) { command = shortcut.command; tab = 1 }
                }
                Text("Shortcuts werden vor dem Senden im Eingabefeld geöffnet.").font(.caption).foregroundStyle(.secondary)
            }.frame(maxWidth: .infinity, alignment: .leading).card()
        default: EmptyView()
        }
    }
    var identityCard: some View {
        VStack(alignment: .leading, spacing: 16) {
                HStack { ServerIconView(api: api, server: server, enabled: workspace.isMinecraft(api, server) && !server.data["invocation"].string.lowercased().contains("bedrock_server") && !server.data["container"]["image"].string.lowercased().contains("bedrock"), revision: iconRevision); VStack(alignment: .leading, spacing: 8) { Text(server.name).font(.largeTitle.bold()); Text(server.address).font(.system(.subheadline, design: .monospaced)).foregroundStyle(.secondary).textSelection(.enabled) }; Spacer(); StatePill(state: live.state) }
                HStack(spacing: 7) { Circle().fill(live.connected ? Color.green : Color.orange).frame(width: 6, height: 6); Text(live.connection); Spacer(); if let updated = live.lastUpdate { Text(updated, style: .time).monospacedDigit() } }.font(.caption).foregroundStyle(.secondary)
                if let issue = live.issue, !live.connected { Label(issue, systemImage: "wifi.exclamationmark").font(.footnote).foregroundStyle(.orange).card() }
                AccessSummary(access: api.access, api: api, server: server.id)
                Toggle("Minecraft-Spielerübersicht", isOn: Binding(get: { workspace.isMinecraft(api, server) }, set: { workspace.setMinecraft($0, api, server) })).font(.subheadline)
        }
    }
    @ViewBuilder var playersSection: some View {
                if suite.configured { SuiteDashboardCard(model: suite) }
                if workspace.isMinecraft(api, server) {
                    if !suite.configured { PlayersCard(live: live, api: api, server: server, suite: suite) }
                    TPSCard(live: live, api: api, server: server)
                }
                if server.data["is_suspended"].bool || server.data["is_installing"].bool || server.data["is_node_under_maintenance"].bool {
                    Label(server.data["is_suspended"].bool ? "Server ist gesperrt" : server.data["is_installing"].bool ? "Installation läuft" : "Node wird gewartet", systemImage: "exclamationmark.triangle").foregroundStyle(.orange).card()
                }
    }
    var resourceCards: some View {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 155), spacing: 14)], spacing: 14) {
                    MetricCard(title: "Arbeitsspeicher", symbol: "memorychip", value: live.stats["memory_bytes"] == .null ? "–" : bytes(live.memory), limit: limitBytes("memory"), fraction: live.lastUpdate == nil ? nil : fraction(live.memory, "memory"), tint: Color.panelAccent)
                    MetricCard(title: "CPU", symbol: "cpu", value: live.stats["cpu_absolute"] == .null ? "–" : String(format: "%.1f %%", live.cpu), limit: server.data["limits"]["cpu"].double > 0 ? "von \(server.data["limits"]["cpu"].string) %" : "Unbegrenzt", fraction: live.lastUpdate == nil ? nil : ResourceLoad.fraction(value: live.cpu, limit: server.data["limits"]["cpu"].double), tint: Color.panelSecondary)
                    MetricCard(title: "Speicherplatz", symbol: "internaldrive", value: live.lastUpdate == nil ? "–" : bytes(live.disk), limit: limitBytes("disk"), fraction: live.lastUpdate == nil ? nil : fraction(live.disk, "disk"), tint: .blue)
                    MetricCard(title: "Laufzeit", symbol: "clock", value: live.stats["uptime"] == .null ? "–" : Duration.seconds(live.uptime).formatted(.time(pattern: .hourMinuteSecond)), limit: live.state == "offline" ? "Server ist offline" : "seit dem letzten Start", fraction: nil, tint: .orange)
                }
    }
    var cpuCard: some View {
                VStack(alignment: .leading, spacing: 16) {
                    HStack { Text("CPU-Verlauf").font(.headline); Spacer(); Text("Live · letzte 120 Werte").font(.caption).foregroundStyle(.secondary) }
                    if live.history.isEmpty { ContentUnavailableView("Warte auf Messwerte", systemImage: "waveform.path.ecg").frame(height: 150) }
                    else {
                        Chart(live.history) { point in
                            AreaMark(x: .value("Zeit", point.date), y: .value("CPU", point.cpu)).foregroundStyle(LinearGradient(colors: [.teal.opacity(0.25), .teal.opacity(0.01)], startPoint: .top, endPoint: .bottom)).interpolationMethod(.catmullRom)
                            LineMark(x: .value("Zeit", point.date), y: .value("CPU", point.cpu)).foregroundStyle(Color.panelAccent).interpolationMethod(.catmullRom)
                        }.chartYScale(domain: 0...max(100, (live.history.map(\.cpu).max() ?? 100) * 1.1)).chartXAxis(.hidden).frame(height: 150).accessibilityLabel("CPU-Verlauf")
                    }
                }.card()
    }
    var networkCard: some View {
                VStack(alignment: .leading, spacing: 16) {
                    Text("Netzwerk & Verbindung").font(.headline)
                    LabeledContent("Empfangen", value: bytes(live.stats["network"]["rx_bytes"].double))
                    LabeledContent("Gesendet", value: bytes(live.stats["network"]["tx_bytes"].double))
                    LabeledContent("Node", value: server.node)
                    Text("CPU: 100 % entsprechen einem CPU-Kern. RAM- und Festplattenlimits werden vom Panel in MiB geliefert.").font(.caption).foregroundStyle(.secondary)
                }.card()
    }
    var healthCard: some View {
        let fractions = [fraction(live.memory, "memory"), ResourceLoad.fraction(value: live.cpu, limit: server.data["limits"]["cpu"].double), fraction(live.disk, "disk")]
        let health = ServerHealth.assess(state: live.state, updated: live.lastUpdate, fractions: fractions)
        return VStack(alignment: .leading, spacing: 14) {
            Label("Server-Gesundheit", systemImage: "heart.text.square").font(.headline)
            Label(health.title, systemImage: health.level == .high ? "exclamationmark.triangle.fill" : health.level == .unknown ? "questionmark.circle" : "checkmark.circle.fill").foregroundStyle(health.level.color)
            Text(health.detail).font(.caption).foregroundStyle(.secondary)
            LabeledContent("Status", value: live.state)
            if let date = live.lastUpdate { LabeledContent("Letzter Messwert") { Text(date, style: .relative) } }
            if let tps = live.tps, Date().timeIntervalSince(tps.updated) <= 75 {
                LabeledContent("TPS · 1 Minute") { Text(tps.minute, format: .number.precision(.fractionLength(2))).foregroundStyle(TPSSnapshot.color(tps.minute)) }
            }
            if let issue = live.issue { Label(issue, systemImage: "wifi.exclamationmark").font(.caption).foregroundStyle(.orange) }
        }.frame(maxWidth: .infinity, alignment: .leading).card()
    }
    var memoryCard: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack { Text("RAM-Verlauf").font(.headline); Spacer(); Text("Live · letzte 120 Werte").font(.caption).foregroundStyle(.secondary) }
            if live.history.isEmpty { ContentUnavailableView("Warte auf Messwerte", systemImage: "memorychip").frame(height: 150) }
            else {
                Chart(live.history) { point in
                    AreaMark(x: .value("Zeit", point.date), y: .value("RAM · GiB", point.memory / 1_073_741_824)).foregroundStyle(.teal.opacity(0.16))
                    LineMark(x: .value("Zeit", point.date), y: .value("RAM · GiB", point.memory / 1_073_741_824)).foregroundStyle(Color.panelAccent)
                }.chartYScale(domain: .automatic(includesZero: true)).chartXAxis(.hidden).frame(height: 150).accessibilityLabel("RAM-Verlauf in GiB")
            }
        }.card()
    }
    func refreshServer() async {
        guard !refreshing else { return }; iconRevision += 1; refreshing = true; defer { refreshing = false }
        // Independent sources start together; slow workspace lists do not block live stats.
        async let access: Void = api.loadAccess(server: server.id)
        async let workspaceRefresh: Void = store.refresh()
        async let suiteRefresh: Void = suite.load(force: true)
        do { try await live.refresh(api: api, server: server, minecraft: workspace.isMinecraft(api, server)) }
        catch { if !RequestFailure.isCancellation(error) && !Task.isCancelled { self.error = error.localizedDescription } }
        _ = await (access, workspaceRefresh, suiteRefresh)
    }
    func limitBytes(_ key: String) -> String { let limit = server.data["limits"][key].double; return limit > 0 ? "von " + bytes(limit * 1_048_576) : "Unbegrenzt" }
    func fraction(_ value: Double, _ key: String) -> Double? { let limit = server.data["limits"][key].double; return ResourceLoad.fraction(value: value, limit: limit * 1_048_576) }
    var console: some View {
        VStack(spacing: 0) {
            HStack {
                Label(live.connected ? "Live-Konsole" : "Verbindung getrennt", systemImage: live.connected ? "dot.radiowaves.left.and.right" : "wifi.slash").font(.caption).foregroundStyle(live.connected ? Color.teal : Color.orange)
                Spacer()
                Menu {
                    ForEach(ConsoleCategory.allCases) { category in
                        Button(category.title, systemImage: consoleCategory == category ? "checkmark" : category.symbol) { consoleCategory = category }
                            .accessibilityIdentifier("console-category-" + category.rawValue)
                            .accessibilityValue(consoleCategory == category ? "Ausgewählt" : "")
                    }
                } label: { Image(systemName: consoleCategory == .all ? "line.3.horizontal.decrease.circle" : "line.3.horizontal.decrease.circle.fill").padding(6) }
                    .accessibilityLabel("Konsolenfilter").accessibilityIdentifier("consoleFilterMenu")
                Menu {
                    Toggle("Automatisch scrollen", isOn: $follow)
                    Toggle("Ausgabe pausieren", isOn: $frozen)
                    Button("Zum Ende springen", systemImage: "arrow.down.to.line") { jumpRevision += 1 }
                    Button("Befehlsverlauf", systemImage: "clock.arrow.circlepath") { historySheet = true }
                    Button("Gefilterte Ausgabe kopieren", systemImage: "doc.on.doc") { Clipboard.copy(cleanConsole(consoleText)) }
                    Button("Schrift vergrößern") { fontSize = min(22, fontSize + 1) }
                    Button("Schrift verkleinern") { fontSize = max(10, fontSize - 1) }
                    Button("Anzeige leeren", role: .destructive) { live.clearConsole(); frozenLines = []; consoleEpoch += 1; updateConsoleText() }
                } label: { Image(systemName: "ellipsis.circle").padding(6) }
            }.padding(.horizontal, 16).padding(.vertical, 8)
            HStack {
                TextField("Ausgabe durchsuchen", text: $filter).textFieldStyle(.roundedBorder).autocorrectionDisabled().textInputAutocapitalization(.never).accessibilityIdentifier("consoleSearch")
                if !filter.isEmpty { Button("Suche leeren", systemImage: "xmark.circle.fill") { filter = "" }.labelStyle(.iconOnly) }
            }.padding(.horizontal, 16).padding(.bottom, 4)
            HStack { Label(consoleCategory.title, systemImage: consoleCategory.symbol); Spacer(); Text("\(consoleProjection.result.shown) / \(consoleProjection.result.total) Zeilen").monospacedDigit() }
                .font(.caption2).foregroundStyle(.secondary).padding(.horizontal, 16).padding(.bottom, 6)
            ConsoleTextView(text: consoleText, fontSize: fontSize, follow: follow && !frozen, viewport: viewport, active: scenePhase == .active, jumpRevision: jumpRevision, refresh: { await refreshServer() })
                .frame(maxWidth: .infinity, maxHeight: .infinity).layoutPriority(1).background(.black.opacity(0.08))
            if frozen { Label("Anzeige pausiert · Live-Ausgabe läuft im Hintergrund weiter", systemImage: "pause.circle").font(.caption).foregroundStyle(.orange).padding(8) }
            HStack(spacing: 8) {
                Button("Älterer Befehl", systemImage: "arrow.up") { previousCommand() }.disabled(commandEntries.isEmpty).accessibilityIdentifier("history-older")
                Button("Neuerer Befehl", systemImage: "arrow.down") { nextCommand() }.disabled(historyCursor.index == nil).accessibilityIdentifier("history-newer")
                Button("Befehlsverlauf", systemImage: "clock.arrow.circlepath") { historySheet = true }.accessibilityIdentifier("consoleHistory")
                Button("Zum Ende springen", systemImage: "arrow.down.to.line") { jumpRevision += 1 }.accessibilityIdentifier("consoleEnd")
                Button(frozen ? "Ausgabe fortsetzen" : "Ausgabe pausieren", systemImage: frozen ? "play.fill" : "pause.fill") { frozen.toggle() }.accessibilityIdentifier("consolePause")
                Button("Befehls-Shortcuts", systemImage: "bolt") { commandLibrary = true }.accessibilityIdentifier("consoleShortcuts")
            }.labelStyle(.iconOnly).buttonStyle(.glass).controlSize(.regular)
                .frame(maxWidth: .infinity).padding(.horizontal, 12).padding(.vertical, 8)
            if workspace.isMinecraft(api, server) && !command.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack {
                        ForEach(Array(CommandLibrary.suggestions.filter { $0.hasPrefix(command.trimmingCharacters(in: CharacterSet(charactersIn: "/"))) && $0 != command }.prefix(4)), id: \.self) { suggestion in
                            Button(suggestion) { command = suggestion }.buttonStyle(.glass)
                        }
                    }.padding(.horizontal, 12).padding(.bottom, 6)
                }.accessibilityLabel("Befehlsvorschläge")
            }
            HStack(spacing: 12) {
                Text(">").font(.system(.body, design: .monospaced)).foregroundStyle(Color.panelAccent)
                TextField("Befehl eingeben …", text: $command).font(.system(.body, design: .monospaced)).autocorrectionDisabled().textInputAutocapitalization(.never).submitLabel(.send).onSubmit(sendCommand).onKeyPress(.upArrow) { previousCommand(); return .handled }.onKeyPress(.downArrow) { nextCommand(); return .handled }.accessibilityIdentifier("consoleCommand").requiresAccess(api, server.id, "control.console")
                Button(action: sendCommand) { if sending { ProgressView() } else { Image(systemName: "arrow.up").fontWeight(.bold) } }.buttonStyle(.glassProminent).disabled(sending || command.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !live.connected).accessibilityLabel("Befehl senden").requiresAccess(api, server.id, "control.console")
            }.padding(16).background(.bar)
        }.onAppear { updateConsoleText() }.onDisappear { consoleProjection.stop() }
            .onChange(of: live.logRevision) { _, _ in if !frozen { updateConsoleText() } }
            .onChange(of: filter) { _, _ in updateConsoleText() }
            .onChange(of: consoleCategory) { _, _ in updateConsoleText() }
            .onChange(of: frozen) { _, paused in consoleEpoch += 1; if paused { frozenLines = live.lines }; updateConsoleText() }
            .onChange(of: command) { _, text in historyCursor.edited(text) }
            .background { PanelBackground() }.safeAreaInset(edge: .bottom, spacing: 0) { Color.clear.frame(height: 1) }
    }
    func updateConsoleText() {
        guard tab == 1, scenePhase == .active else { return }
        consoleProjection.request(frozen ? frozenLines : live.lines, category: consoleCategory, query: filter, context: consoleEpoch)
    }
    func previousCommand() { command = historyCursor.older(current: command, entries: commandEntries) }
    func nextCommand() { guard historyCursor.index != nil else { return }; command = historyCursor.newer(entries: commandEntries) }
    func sendCommand() {
        var text = command.trimmingCharacters(in: .whitespacesAndNewlines)
        if workspace.isMinecraft(api, server) && text.hasPrefix("/") { text.removeFirst() }
        guard !text.isEmpty, !sending else { return }
        sending = true
        Task { do {
            guard !api.demo else { throw PanelError(message: "Im Demomodus werden keine Befehle gesendet.") }
            try await live.sendCommand(text, api: api, server: server); consoleHistory.remember(text, key: historyKey); command = ""; historyCursor = CommandHistoryCursor(); Feedback.success()
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
    var metricColor: Color { fraction == nil ? tint : ResourceLoad.level(fraction).color }
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Label(title, systemImage: symbol).font(.caption.weight(.semibold)).foregroundStyle(metricColor)
            Text(value).font(.system(.title2, design: .rounded, weight: .bold)).monospacedDigit().minimumScaleFactor(0.65).lineLimit(1).contentTransition(.numericText())
            Text(limit).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            if let fraction { ProgressView(value: min(1, max(0, fraction))).tint(metricColor).accessibilityLabel(title + " · " + ResourceLoad.level(fraction).title) }
            else { Capsule().fill(tint.opacity(0.14)).frame(height: 4) }
        }.frame(maxWidth: .infinity, alignment: .leading).card()
    }
}
