import SwiftUI
import Charts

// MARK: - Diagnostics (Admin Suite 10.2.0)
// Everything runs on the server; the app only reads results and starts bounded jobs (full check, spark, AI).

/// Formatting and mapping shared by all diagnostics views (unit-tested).
enum SuiteDiagnostics {
    static func verdictTitle(_ verdict: String) -> String {
        switch verdict {
        case "legit": return "Legitim"
        case "problem": return "Fehler erkannt"
        default: return "Unklar"
        }
    }
    static func verdictTint(_ verdict: String) -> Color {
        switch verdict {
        case "legit": return .green
        case "problem": return .red
        default: return .orange
        }
    }
    static func severityTitle(_ severity: String) -> String {
        switch severity {
        case "critical": return "Kritisch"
        case "warning": return "Dringlich"
        default: return "Hinweis"
        }
    }
    static func statusTint(_ status: String) -> Color {
        switch status {
        case "ok": return .green
        case "warn", "warning": return .orange
        case "crit", "critical": return .red
        default: return .blue
        }
    }
    static func statusSymbol(_ status: String) -> String {
        switch status {
        case "ok": return "checkmark.circle.fill"
        case "warn", "warning": return "exclamationmark.triangle.fill"
        case "crit", "critical": return "xmark.octagon.fill"
        default: return "info.circle.fill"
        }
    }
    static func crashTitle(_ kind: String) -> String {
        switch kind {
        case "oom": return "Speicher voll"
        case "watchdog": return "Watchdog"
        case "jvm": return "Java-VM"
        case "tick": return "Tick-Fehler"
        default: return "Hart beendet"
        }
    }
    /// Teleport command that works from any dimension (vanilla world names map to their dimension keys).
    static func teleport(world: String, x: Int, y: Int, z: Int) -> String {
        let dimension: String
        switch world {
        case "world": dimension = "minecraft:overworld"
        case "world_nether": dimension = "minecraft:the_nether"
        case "world_the_end": dimension = "minecraft:the_end"
        default: dimension = world
        }
        return "/execute in \(dimension) run tp @s \(x) \(y) \(z)"
    }
    static func number(_ value: Double, digits: Int = 1) -> String {
        value.formatted(.number.precision(.fractionLength(0...digits)).locale(Locale(identifier: "de_DE")))
    }
    static func percent(_ value: Double) -> String { number(value) + " %" }
    static func duration(_ millis: Double) -> String {
        let seconds = Int(max(0, millis) / 1000)
        if seconds >= 86_400 { return "\(seconds / 86_400) T \(seconds % 86_400 / 3600) Std" }
        if seconds >= 3600 { return "\(seconds / 3600) Std \(seconds % 3600 / 60) Min" }
        if seconds >= 60 { return "\(seconds / 60) Min" }
        return "\(seconds) Sek"
    }
    static func ago(_ millis: Double) -> String {
        guard millis > 0 else { return "nie" }
        return Date(timeIntervalSince1970: millis / 1000).formatted(.relative(presentation: .named).locale(Locale(identifier: "de_DE")))
    }
    /// Chart points from the server series (newest first) in chronological order.
    static func points(_ series: JSON) -> [SuiteDiagnosticsPoint] {
        let times = series["t"].array, tps = series["tps"].array, mspt = series["mspt"].array, max = series["msptMax"].array
        let count = min(times.count, tps.count, mspt.count, max.count)
        var out: [SuiteDiagnosticsPoint] = []
        for index in 0..<count {
            let reversed = count - 1 - index
            out.append(SuiteDiagnosticsPoint(id: index, date: Date(timeIntervalSince1970: times[reversed].double / 1000), tps: tps[reversed].double, mspt: mspt[reversed].double, max: max[reversed].double))
        }
        return out
    }
    /// Spark windows have no timestamps; they are spread evenly.
    static func windowPoints(_ windows: JSON) -> [SuiteDiagnosticsPoint] {
        let tps = windows["tps"].array, median = windows["msptMedian"].array, max = windows["msptMax"].array
        let count = min(tps.count, median.count, max.count)
        var out: [SuiteDiagnosticsPoint] = []
        let start = Date().addingTimeInterval(Double(-count) * 60)
        for index in 0..<count {
            out.append(SuiteDiagnosticsPoint(id: index, date: start.addingTimeInterval(Double(index) * 60), tps: tps[index].double, mspt: median[index].double, max: max[index].double))
        }
        return out
    }
}

struct SuiteDiagnosticsPoint: Identifiable, Equatable {
    let id: Int
    let date: Date
    let tps: Double
    let mspt: Double
    let max: Double
}

// MARK: - Shared rows

struct SuiteDiagnosticsTile: View {
    let title: String
    let value: String
    let detail: String
    let symbol: String
    let tint: Color
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Label(title, systemImage: symbol).font(.caption).foregroundStyle(tint)
            Text(value).font(.title3.weight(.bold).monospacedDigit()).lineLimit(1).minimumScaleFactor(0.7)
            Text(detail).font(.caption2).foregroundStyle(.secondary).lineLimit(2)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(10)
        .background(tint.opacity(0.08), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
    }
}

struct SuiteDiagnosticsTileModel: Identifiable {
    let id: String
    let title: String
    let value: String
    let detail: String
    let symbol: String
    let tint: Color

    static func overview(_ data: JSON) -> [SuiteDiagnosticsTileModel] {
        let live = data["live"]
        let disconnects = data["disconnects"]
        let tpsValues = live["tps"].array
        let tps: Double = tpsValues.first?.double ?? 20
        let tps5: Double = tpsValues.count > 1 ? tpsValues[1].double : tps
        let mspt: Double = live["mspt"].double
        let heapMax: Double = live["heapMax"].double
        let heapShare: Double = heapMax > 0 ? live["heapUsed"].double / heapMax : 0
        let cpu: Double = live["cpu"].double
        let java: Int = live["java"].int
        let bedrock: Int = live["bedrock"].int
        let involuntary: Int = disconnects["involuntary"].int
        let crashes: Int = data["crashCount"].int
        var out: [SuiteDiagnosticsTileModel] = []
        let tpsTint: Color = tps >= 18.5 ? Color.green : (tps >= 15 ? Color.orange : Color.red)
        out.append(SuiteDiagnosticsTileModel(id: "tps", title: "TPS", value: SuiteDiagnostics.number(tps), detail: "5 min " + SuiteDiagnostics.number(tps5), symbol: "speedometer", tint: tpsTint))
        let msptTint: Color = mspt < 30 ? Color.green : (mspt < 45 ? Color.orange : Color.red)
        let msptDetail: String = "Längster (60 s): " + SuiteDiagnostics.number(live["msptMax60"].double, digits: 0) + " ms"
        out.append(SuiteDiagnosticsTileModel(id: "mspt", title: "Tickzeit", value: SuiteDiagnostics.number(mspt) + " ms", detail: msptDetail, symbol: "timer", tint: msptTint))
        let ramDetail: String = bytes(live["heapUsed"].double) + " von " + bytes(heapMax) + " · GC " + SuiteDiagnostics.number(live["gcMsPerMinute"].double, digits: 0) + " ms/min"
        out.append(SuiteDiagnosticsTileModel(id: "ram", title: "RAM", value: SuiteDiagnostics.percent(heapShare * 100), detail: ramDetail, symbol: "memorychip", tint: heapShare < 0.8 ? Color.purple : Color.red))
        let cpuDetail: String = "System " + SuiteDiagnostics.percent(live["systemCpu"].double) + " · \(live["threads"].int) Threads"
        out.append(SuiteDiagnosticsTileModel(id: "cpu", title: "CPU", value: SuiteDiagnostics.percent(cpu), detail: cpuDetail, symbol: "cpu", tint: cpu < 70 ? Color.green : Color.orange))
        out.append(SuiteDiagnosticsTileModel(id: "players", title: "Spieler", value: String(java + bedrock), detail: "\(java) Java · \(bedrock) Bedrock", symbol: "person.2.fill", tint: Color.blue))
        let worldDetail: String = "Entities · \(live["tiles"].int) Block-Entities · \(live["chunks"].int) Chunks"
        out.append(SuiteDiagnosticsTileModel(id: "world", title: "Welt", value: String(live["entities"].int), detail: worldDetail, symbol: "globe.europe.africa.fill", tint: Color.teal))
        let dcTint: Color = involuntary >= 4 ? Color.red : (involuntary > 0 ? Color.orange : Color.green)
        let dcDetail: String = "\(involuntary) unfreiwillig · \(disconnects["java"].int) Java / \(disconnects["bedrock"].int) Bedrock"
        out.append(SuiteDiagnosticsTileModel(id: "dc", title: "Disconnects 1 h", value: String(disconnects["lastHour"].int), detail: dcDetail, symbol: "wifi.exclamationmark", tint: dcTint))
        let crashDetail: String = data["lastCrash"] == .null ? "Keiner erkannt" : data["lastCrash"]["title"].string
        out.append(SuiteDiagnosticsTileModel(id: "crash", title: "Abstürze", value: String(crashes), detail: crashDetail, symbol: "bolt.trianglebadge.exclamationmark.fill", tint: crashes > 0 ? Color.orange : Color.green))
        return out
    }
}

struct SuiteDiagnosticsFindingRow: View {
    let row: JSON
    var status: String { row["status"] == .null ? row["level"].string : row["status"].string }
    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: SuiteDiagnostics.statusSymbol(status)).foregroundStyle(SuiteDiagnostics.statusTint(status)).frame(width: 22)
            VStack(alignment: .leading, spacing: 2) {
                Text(row["title"].string).font(.subheadline.weight(.semibold))
                if !row["value"].string.isEmpty { Text(row["value"].string).font(.caption.monospaced()) }
                if !row["hint"].string.isEmpty { Text(row["hint"].string).font(.caption).foregroundStyle(.secondary) }
            }
        }
    }
}

struct SuiteDiagnosticsChart: View {
    let points: [SuiteDiagnosticsPoint]
    var maxLabel = "Längster Tick"
    var body: some View {
        if points.count < 2 {
            Text("Noch zu wenige Messwerte – kurz warten.").font(.subheadline).foregroundStyle(.secondary)
        } else {
            VStack(alignment: .leading, spacing: 8) {
                Chart {
                    ForEach(points) { point in
                        LineMark(x: .value("Zeit", point.date), y: .value("ms", min(point.max, 2000)), series: .value("Reihe", maxLabel)).foregroundStyle(.red)
                    }
                    ForEach(points) { point in
                        LineMark(x: .value("Zeit", point.date), y: .value("ms", min(point.mspt, 2000)), series: .value("Reihe", "Ø Tickzeit")).foregroundStyle(.teal)
                    }
                    RuleMark(y: .value("Budget", 50)).foregroundStyle(.orange.opacity(0.7)).lineStyle(StrokeStyle(lineWidth: 1, dash: [4, 4]))
                }
                .chartXAxis(.hidden)
                .frame(height: 150)
                Chart(points) { point in
                    AreaMark(x: .value("Zeit", point.date), y: .value("TPS", point.tps)).foregroundStyle(.green.opacity(0.25))
                    LineMark(x: .value("Zeit", point.date), y: .value("TPS", point.tps)).foregroundStyle(.green)
                }
                .chartYScale(domain: 0...20)
                .frame(height: 70)
                HStack(spacing: 12) {
                    Label("TPS", systemImage: "circle.fill").foregroundStyle(.green)
                    Label("Ø Tick", systemImage: "circle.fill").foregroundStyle(.teal)
                    Label(maxLabel, systemImage: "circle.fill").foregroundStyle(.red)
                }.font(.caption2).labelStyle(.titleAndIcon)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Verlauf von TPS und Tickzeit")
        }
    }
}

struct SuiteDiagnosticsBar: View {
    let title: String
    let value: Double
    let total: Double
    let label: String
    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack {
                Text(title).font(.caption).lineLimit(1)
                Spacer()
                Text(label).font(.caption.monospacedDigit()).foregroundStyle(.secondary)
            }
            ProgressView(value: min(value, max(total, 0.0001)), total: max(total, 0.0001)).tint(.teal)
        }
    }
}

struct SuiteDiagnosticsLogRow: View {
    let row: JSON
    var tint: Color {
        switch row["level"].string {
        case "ERROR", "FATAL": return .red
        case "WARN": return .orange
        default: return .secondary
        }
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 6) {
                Text(row["level"].string).font(.caption2.weight(.bold)).foregroundStyle(tint)
                Text(row["source"].string).font(.caption2).foregroundStyle(.secondary)
                Spacer()
                Text(Date(timeIntervalSince1970: row["at"].double / 1000).formatted(date: .numeric, time: .standard)).font(.caption2.monospacedDigit()).foregroundStyle(.secondary)
            }
            Text(row["text"].string).font(.caption.monospaced()).foregroundStyle(tint == .red ? Color.red : Color.primary).textSelection(.enabled)
        }
    }
}

/// AI analysis: shows a finished result or starts one and polls until it is done.
struct SuiteAIBox: View {
    let suite: SuiteAPI
    let target: String
    let initial: JSON
    let available: Bool
    let canRun: Bool
    @State private var job: JSON = .null
    @State private var error: String?
    var current: JSON { job == .null ? initial : job }
    var buttonTitle: String { current["status"].string == "done" ? "Neu analysieren" : "Mit KI analysieren" }
    var body: some View {
        Group {
            switch current["status"].string {
            case "done":
                Text(current["text"].string).font(.subheadline).textSelection(.enabled)
                Text("KI · " + current["model"].string + " · " + SuiteDiagnostics.ago(current["doneAt"].double)).font(.caption2).foregroundStyle(.secondary)
            case "running":
                HStack { ProgressView(); Text("KI analysiert die Daten …").foregroundStyle(.secondary) }
            case "failed":
                Label(current["error"].string, systemImage: "exclamationmark.triangle").font(.caption).foregroundStyle(.orange)
            default:
                EmptyView()
            }
            if !available {
                Text("Kein OpenAI-Schlüssel hinterlegt oder KI-Diagnose ausgeschaltet.").font(.caption).foregroundStyle(.secondary)
            } else if canRun && current["status"].string != "running" {
                Button(buttonTitle, systemImage: "sparkles") { Task { await start() } }
            }
            if let error { Text(error).font(.caption).foregroundStyle(.red) }
        }
        .task(id: current["status"].string) { if current["status"].string == "running" { await poll() } }
    }
    func start() async {
        do {
            let reply = try await suite.post("diagnostics/analyze", ["target": .string(target)])
            job = reply["job"]
            error = nil
        } catch { self.error = error.localizedDescription }
    }
    func poll() async {
        for _ in 0..<60 {
            try? await Task.sleep(for: .seconds(2))
            if Task.isCancelled { return }
            if let next = try? await suite.request("diagnostics/ai", query: ["target": target]), next["status"].string != "running" { job = next; return }
        }
    }
}

// MARK: - Hub

struct SuiteDiagnosticsView: View {
    let suite: SuiteAPI
    @State private var data: JSON = .null
    @State private var error: String?
    var live: JSON { data["live"] }
    var canRun: Bool { data["canRun"].bool }
    var canManage: Bool { data["canManage"].bool }
    var aiAvailable: Bool { data["ai"]["available"].bool }
    var body: some View {
        List {
            if data["alert"] != .null { alertSection }
            Section { tiles } header: { Text("Live") } footer: { Text(data["tap"].string.isEmpty ? "" : "Konsole: " + data["tap"].string) }
            Section { SuiteDiagnosticsChart(points: SuiteDiagnostics.points(data["series"])) } header: { Text("Tickrate & Tickzeit · 10 min") } footer: { Text("Ab 50 ms pro Tick sinkt die TPS. Spitzen über 1 s spüren Spieler als Lag.") }
            toolsSection
            incidentsSection
            spikesSection
        }
        .panelScreen()
        .navigationTitle("Diagnose")
        .task { await loop() }
        .refreshable { await load() }
        .errorAlert($error)
    }
    @ViewBuilder private var alertSection: some View {
        let alert = data["alert"]
        Section {
            NavigationLink { SuiteDiagnosticsIncidentView(suite: suite, id: alert["id"].string, canManage: canManage, canRun: canRun, aiAvailable: aiAvailable) } label: {
                VStack(alignment: .leading, spacing: 4) {
                    Label(SuiteDiagnostics.severityTitle(alert["severity"].string) + ": " + alert["title"].string, systemImage: "exclamationmark.triangle.fill").font(.subheadline.weight(.semibold)).foregroundStyle(alert["severity"].string == "critical" ? Color.red : Color.orange)
                    Text(alert["cause"].string).font(.caption).foregroundStyle(.secondary)
                }
            }
        }
    }
    private var tiles: some View {
        let columns = [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)]
        return LazyVGrid(columns: columns, spacing: 10) {
            ForEach(SuiteDiagnosticsTileModel.overview(data)) { model in
                SuiteDiagnosticsTile(title: model.title, value: model.value, detail: model.detail, symbol: model.symbol, tint: model.tint)
            }
        }
        .padding(.vertical, 4)
    }
    private var toolsSection: some View {
        Section("Werkzeuge") {
            NavigationLink { SuiteFullDiagnosisView(suite: suite, canRun: canRun, aiAvailable: aiAvailable) } label: { Label(fullLabel, systemImage: "stethoscope") }
            NavigationLink { SuiteDiagnosticsDisconnectsView(suite: suite, canManage: canManage, canRun: canRun, aiAvailable: aiAvailable) } label: { Label("Disconnects & Vorfälle", systemImage: "wifi.exclamationmark") }
            if suite.features.contains("disconnect-forensics-v1") {
                NavigationLink { SuiteNetworkLiveView(suite: suite) } label: { Label("Live-Netzwerk: jede Verbindung in Echtzeit", systemImage: "antenna.radiowaves.left.and.right") }
            }
            NavigationLink { SuiteLagMapView(suite: suite, canRun: canRun, aiAvailable: aiAvailable) } label: { Label("Lag-Karte: Entities, Chunks & Hotspots", systemImage: "map.fill") }
            NavigationLink { SuiteSparkView(suite: suite, canRun: canRun, canManage: canManage, aiAvailable: aiAvailable) } label: { Label(data["spark"]["available"].bool ? "Spark-Profiler & Analyse" : "Spark-Profil analysieren", systemImage: "flame.fill") }
            NavigationLink { SuiteCrashesView(suite: suite, canRun: canRun, aiAvailable: aiAvailable) } label: { Label("Abstürze", systemImage: "bolt.trianglebadge.exclamationmark") }
            NavigationLink { SuiteDiagnosticsConsoleView(suite: suite, canRun: canRun, aiAvailable: aiAvailable) } label: { Label("Konsole durchsuchen", systemImage: "terminal") }
            if data["canSettings"].bool { NavigationLink { SuiteDiagnosticsSettingsView(suite: suite, settings: data["settings"]) } label: { Label("Schwellenwerte & KI", systemImage: "slider.horizontal.3") } }
        }
    }
    private var fullLabel: String {
        let last = data["lastFull"]
        return last == .null ? "Volldiagnose starten" : "Volldiagnose · zuletzt \(last["score"].int)/100"
    }
    @ViewBuilder private var incidentsSection: some View {
        let rows = data["incidents"].array
        if !rows.isEmpty {
            Section("Letzte Vorfälle") {
                ForEach(rows.prefix(6), id: \.["id"].string) { row in
                    NavigationLink { SuiteDiagnosticsIncidentView(suite: suite, id: row["id"].string, canManage: canManage, canRun: canRun, aiAvailable: aiAvailable) } label: { SuiteIncidentRow(row: row) }
                }
            }
        }
    }
    @ViewBuilder private var spikesSection: some View {
        let rows = data["spikes"].array
        if !rows.isEmpty {
            Section("Lag-Spitzen") {
                ForEach(rows.prefix(6), id: \.["at"].double) { row in
                    LabeledContent(SuiteDiagnostics.ago(row["at"].double), value: SuiteDiagnostics.number(row["ms"].double, digits: 0) + " ms · \(row["players"].int) Spieler")
                }
            }
        }
    }
    func loop() async {
        while !Task.isCancelled {
            await load()
            try? await Task.sleep(for: .seconds(5))
        }
    }
    func load() async {
        do { data = try await suite.request("diagnostics/overview", query: ["range": "600"]) }
        catch { if !RequestFailure.isCancellation(error) && data == .null { self.error = error.localizedDescription } }
    }
}

struct SuiteIncidentRow: View {
    let row: JSON
    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack(spacing: 6) {
                Text(SuiteDiagnostics.verdictTitle(row["verdict"].string)).font(.caption2.weight(.bold)).padding(.horizontal, 6).padding(.vertical, 2).background(SuiteDiagnostics.verdictTint(row["verdict"].string).opacity(0.18), in: Capsule()).foregroundStyle(SuiteDiagnostics.verdictTint(row["verdict"].string))
                if row["resolved"].bool { Image(systemName: "checkmark.circle.fill").foregroundStyle(.green).font(.caption) }
                if row["hasAi"].bool { Image(systemName: "sparkles").foregroundStyle(.purple).font(.caption) }
                Spacer()
                Text(SuiteDiagnostics.ago(row["firstAt"].double)).font(.caption2).foregroundStyle(.secondary)
            }
            Text(row["title"].string).font(.subheadline.weight(.semibold))
            Text(row["cause"].string).font(.caption).foregroundStyle(.secondary).lineLimit(2)
        }
    }
}

// MARK: - Disconnects

struct SuiteDiagnosticsDisconnectsView: View {
    let suite: SuiteAPI
    let canManage: Bool
    let canRun: Bool
    let aiAvailable: Bool
    @State private var data: JSON = .null
    @State private var category = ""
    @State private var platform = ""
    @State private var search = ""
    @State private var error: String?
    var categories: [(String, String)] {
        var out: [(String, String)] = [("", "Alle Gründe"), ("involuntary", "Nur unfreiwillige")]
        for (key, value) in data["titles"].object.sorted(by: { $0.key < $1.key }) { out.append((key, value.string)) }
        return out
    }
    var hours: [SuiteDiagnosticsHour] {
        let all = data["hours"].array, bad = data["involuntaryHours"].array
        var out: [SuiteDiagnosticsHour] = []
        for index in 0..<min(all.count, bad.count) { out.append(SuiteDiagnosticsHour(id: index, hoursAgo: 23 - index, all: all[index].int, involuntary: bad[index].int)) }
        return out
    }
    var body: some View {
        List {
            Section {
                Picker("Grund", selection: $category) { ForEach(categories, id: \.0) { Text($0.1).tag($0.0) } }
                Picker("Plattform", selection: $platform) { Text("Alle").tag(""); Text("Java").tag("java"); Text("Bedrock").tag("bedrock") }.pickerStyle(.segmented)
            }
            incidentsSection
            if !hours.isEmpty {
                Section { hourChart } header: { Text("Letzte 24 Stunden") } footer: { Text("Rot = unfreiwillig (Timeout, Abbruch, Paketfehler).") }
            }
            Section("Einzelne Trennungen") {
                if data == .null { ProgressView() }
                else if data["items"].array.isEmpty { Text("Keine Einträge.").foregroundStyle(.secondary) }
                ForEach(data["items"].array, id: \.["id"].string) { row in SuiteDisconnectRow(row: row) }
            }
        }
        .panelScreen()
        .navigationTitle("Disconnects")
        .searchable(text: $search, prompt: "Spieler oder Meldung")
        .task(id: category + "|" + platform + "|" + search) { await load() }
        .refreshable { await load() }
        .errorAlert($error)
    }
    private var hourChart: some View {
        Chart(hours) { hour in
            BarMark(x: .value("Stunde", hour.id), y: .value("Trennungen", max(0, hour.all - hour.involuntary))).foregroundStyle(Color.gray.opacity(0.4))
            BarMark(x: .value("Stunde", hour.id), y: .value("Trennungen", hour.involuntary)).foregroundStyle(Color.red)
        }
        .chartXAxis(.hidden)
        .frame(height: 110)
    }
    @ViewBuilder private var incidentsSection: some View {
        Section("Vorfälle") {
            if data["incidents"].array.isEmpty { Text("Bisher keine Disconnect-Welle erkannt.").foregroundStyle(.secondary) }
            ForEach(data["incidents"].array, id: \.["id"].string) { row in
                NavigationLink { SuiteDiagnosticsIncidentView(suite: suite, id: row["id"].string, canManage: canManage, canRun: canRun, aiAvailable: aiAvailable) } label: { SuiteIncidentRow(row: row) }
            }
        }
    }
    func load() async {
        do { data = try await suite.request("diagnostics/disconnects", query: ["category": category, "platform": platform, "q": search, "limit": "200"]) }
        catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }
}

struct SuiteDiagnosticsHour: Identifiable {
    let id: Int
    let hoursAgo: Int
    let all: Int
    let involuntary: Int
}

struct SuiteDisconnectRow: View {
    let row: JSON
    var detail: String {
        var parts: [String] = []
        let message = row["message"].string.isEmpty ? row["kickCause"].string : row["message"].string
        if !message.isEmpty { parts.append(message) }
        if row["ping"].int >= 0 { parts.append("Ping \(row["ping"].int) ms") }
        if row["sessionMs"].double > 0 { parts.append(SuiteDiagnostics.duration(row["sessionMs"].double) + " online") }
        if !row["world"].string.isEmpty { parts.append(row["world"].string + " \(row["blockX"].int) / \(row["blockZ"].int)") }
        return parts.joined(separator: " · ")
    }
    private var trace: JSON { row["packetTrace"] }
    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack(spacing: 6) {
                Text(row["name"].string).font(.subheadline.weight(.semibold))
                Text(row["platform"].string == "bedrock" ? "Bedrock" : "Java").font(.caption2).foregroundStyle(.secondary)
                Spacer()
                Text(SuiteDiagnostics.ago(row["at"].double)).font(.caption2).foregroundStyle(.secondary)
            }
            HStack(spacing: 6) {
                Text(row["categoryTitle"].string).font(.caption.weight(.semibold)).foregroundStyle(row["involuntary"].bool ? Color.red : Color.secondary)
                if row["refined"].bool { Text("gemeldet: " + row["reportedTitle"].string).font(.caption2).foregroundStyle(.secondary) }
            }
            Text(detail).font(.caption).foregroundStyle(.secondary).lineLimit(3)
            if row["rejoinMs"].double >= 0 && row["rejoinMs"] != .null {
                Label("Nach " + SuiteDiagnostics.duration(row["rejoinMs"].double) + " wieder da", systemImage: "arrow.uturn.left.circle").font(.caption2).foregroundStyle(.orange)
            }
            SuiteDisconnectForensicsDetail(row: row)
            if !trace["source"].string.isEmpty {
                DisclosureGroup("Paketspur · \(trace["inbound"].int) rein / \(trace["outbound"].int) raus") {
                    if !trace["closeSide"].string.isEmpty { LabeledContent("Geschlossen", value: DisconnectForensicsPresentation.closeSide(trace["closeSide"].string)) }
                    if !trace["exception"].string.isEmpty { LabeledContent("Netty-Fehler", value: trace["exceptionDetail"].string.isEmpty ? trace["exception"].string : trace["exceptionDetail"].string) }
                    if trace["lastInboundAgeMs"] != .null && trace["lastInboundAgeMs"].int >= 0 { LabeledContent("Letztes Paket vom Client", value: "\(trace["lastInboundAgeMs"].int) ms vor Ende") }
                    if trace["tcp"]["rtt"] != .null { LabeledContent("TCP", value: "RTT \(trace["tcp"]["rtt"].int) ms · \(trace["tcpDelta"]["totalRetrans"].int) Neuübertr. · \(trace["tcpDelta"]["lost"].int) verloren") }
                    LabeledContent("Letztes KeepAlive IN", value: trace["keepaliveInAgeMs"].int < 0 ? "–" : "\(trace["keepaliveInAgeMs"].int) ms zuvor")
                    LabeledContent("Letztes KeepAlive OUT", value: trace["keepaliveOutAgeMs"].int < 0 ? "–" : "\(trace["keepaliveOutAgeMs"].int) ms zuvor")
                    ForEach(Array(trace["recent"].array.enumerated()), id: \.offset) { _, packet in
                        Text(packet["direction"].string + " · " + packet["type"].string + " · " + packet["ageMs"].string + " ms").font(.caption2.monospaced())
                    }
                }.font(.caption)
            }
        }
    }
}

struct SuiteDiagnosticsIncidentView: View {
    let suite: SuiteAPI
    let id: String
    let canManage: Bool
    let canRun: Bool
    let aiAvailable: Bool
    @Environment(\.dismiss) private var dismiss
    @State private var data: JSON = .null
    @State private var error: String?
    var verdict: JSON { data["verdict"] }
    var body: some View {
        List {
            if data == .null { ProgressView() } else {
                verdictSection
                SuiteDisconnectHypothesesSection(verdict: verdict)
                SuiteDisconnectPatternSection(verdict: verdict)
                stateSection
                Section { SuiteAIBox(suite: suite, target: "incident:" + id, initial: data["ai"], available: aiAvailable, canRun: canRun) } header: { Text("KI-Einschätzung") } footer: { Text("Kurze Auswertung der Daten und der letzten Konsolenzeilen – ohne IP-Adressen.") }
                recordsSection
                SuiteNetworkEnvironmentSection(environment: data["environment"])
                logsSection
            }
        }
        .panelScreen()
        .navigationTitle("Vorfall")
        .toolbar { if canManage && data != .null { ToolbarItem(placement: .topBarTrailing) { manageMenu } } }
        .task { await load() }
        .refreshable { await load() }
        .errorAlert($error)
    }
    private var verdictSection: some View {
        Section {
            Text(data["title"].string).font(.headline)
            HStack(spacing: 8) {
                Text(SuiteDiagnostics.verdictTitle(verdict["verdict"].string)).font(.caption.weight(.bold)).foregroundStyle(SuiteDiagnostics.verdictTint(verdict["verdict"].string))
                Text(SuiteDiagnostics.severityTitle(verdict["severity"].string)).font(.caption).foregroundStyle(.secondary)
                if data["resolved"].bool { Label("Erledigt", systemImage: "checkmark.circle.fill").font(.caption).foregroundStyle(.green) }
            }
            Text(verdict["cause"].string).font(.subheadline)
            ForEach(Array(verdict["steps"].array.enumerated()), id: \.offset) { item in
                Label(item.element.string, systemImage: "\(item.offset + 1).circle.fill").font(.subheadline)
            }
            ForEach(Array(verdict["evidence"].array.enumerated()), id: \.offset) { item in
                Text("• " + item.element.string).font(.caption).foregroundStyle(.secondary)
            }
            if !data["note"].string.isEmpty { Text("Notiz: " + data["note"].string).font(.caption) }
        } header: { Text(Date(timeIntervalSince1970: data["firstAt"].double / 1000).formatted(date: .abbreviated, time: .standard)) }
    }
    private var stateSection: some View {
        let state = data["state"]
        return Section("Serverzustand") {
            LabeledContent("TPS", value: SuiteDiagnostics.number(state["tps"].double))
            LabeledContent("Längster Tick (30 s)", value: SuiteDiagnostics.number(state["msptMax30s"].double, digits: 0) + " ms")
            LabeledContent("RAM", value: bytes(state["heapUsed"].double) + " / " + bytes(state["heapMax"].double))
            LabeledContent("GC", value: SuiteDiagnostics.number(state["gcMsPerMinute"].double, digits: 0) + " ms/min")
            LabeledContent("Watchdog", value: state["watchdog"].bool ? "ja" : "nein")
        }
    }
    @ViewBuilder private var recordsSection: some View {
        let rows = data["records"].array
        if !rows.isEmpty {
            Section("Betroffene Spieler · \(rows.count)") {
                ForEach(rows, id: \.["id"].string) { row in SuiteDisconnectRow(row: row) }
            }
        }
    }
    @ViewBuilder private var logsSection: some View {
        let rows = data["logs"].array
        if !rows.isEmpty {
            Section("Konsole rund um den Vorfall") {
                ForEach(Array(rows.enumerated()), id: \.offset) { item in SuiteDiagnosticsLogRow(row: item.element) }
            }
        }
    }
    private var manageMenu: some View {
        Menu {
            if data["resolved"].bool {
                Button("Wieder öffnen", systemImage: "arrow.uturn.backward") { Task { await change("reopen") } }
            } else {
                Button("Als erledigt markieren", systemImage: "checkmark.circle") { Task { await change("resolve") } }
            }
            Button("Löschen", systemImage: "trash", role: .destructive) { Task { await change("delete") } }
        } label: { Image(systemName: "ellipsis.circle") }
    }
    func change(_ operation: String) async {
        do {
            _ = try await suite.post("diagnostics/incident", ["id": .string(id), "operation": .string(operation)])
            if operation == "delete" { dismiss() } else { await load() }
        } catch { self.error = error.localizedDescription }
    }
    func load() async {
        do { data = try await suite.request("diagnostics/incident", query: ["id": id]) }
        catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }
}

// MARK: - Lag map

struct SuiteLagMapView: View {
    let suite: SuiteAPI
    let canRun: Bool
    let aiAvailable: Bool
    @State private var data: JSON = .null
    @State private var error: String?
    @State private var busy = false
    var body: some View {
        List {
            if data == .null { ProgressView("Welt wird vermessen …") } else {
                Section { ForEach(Array(data["tips"].array.enumerated()), id: \.offset) { item in SuiteDiagnosticsFindingRow(row: item.element) } } header: { Text("Befunde") } footer: { Text(measuredText) }
                Section("KI-Einschätzung") { SuiteAIBox(suite: suite, target: "lag:", initial: .null, available: aiAvailable, canRun: canRun) }
                worldsSection
                chunkSection("Entity-Hotspots", rows: data["hotChunks"].array, unit: "Entities")
                chunkSection("Block-Entity-Hotspots", rows: data["tileChunks"].array, unit: "Blöcke")
                typesSection
                generationSection
                playersSection
            }
        }
        .panelScreen()
        .navigationTitle("Lag-Karte")
        .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Neu messen", systemImage: "arrow.clockwise") { Task { await load(refresh: true) } }.disabled(busy) } }
        .task { await load(refresh: true) }
        .refreshable { await load(refresh: true) }
        .errorAlert($error)
    }
    private var measuredText: String {
        let partial: String = data["partial"].bool ? " · Block-Entities nur teilweise" : ""
        return "Gemessen " + SuiteDiagnostics.ago(data["at"].double) + " in \(data["tookMs"].int) ms" + partial
    }
    private var worldsSection: some View {
        Section("Welten") {
            ForEach(data["worlds"].array, id: \.["name"].string) { world in
                VStack(alignment: .leading, spacing: 2) {
                    Text(world["name"].string).font(.subheadline.weight(.semibold))
                    Text("\(world["players"].int) Spieler · \(world["entities"].int) Entities · \(world["tileEntities"].int) Block-Entities (\(world["tickableTiles"].int) tickend) · \(world["chunks"].int) Chunks").font(.caption).foregroundStyle(.secondary)
                }
            }
        }
    }
    private func chunkSection(_ title: String, rows: [JSON], unit: String) -> some View {
        Section(title) {
            if rows.isEmpty { Text("Keine Ansammlung.").foregroundStyle(.secondary) }
            ForEach(Array(rows.enumerated()), id: \.offset) { item in SuiteLagChunkRow(row: item.element, unit: unit) }
        }
    }
    private var typesSection: some View {
        let rows = data["entityTypes"].array
        let top = rows.first?["count"].double ?? 1
        return Section("Entity-Typen") {
            ForEach(Array(rows.enumerated()), id: \.offset) { item in SuiteDiagnosticsBar(title: item.element["type"].string, value: item.element["count"].double, total: top, label: String(item.element["count"].int)) }
        }
    }
    private var generationSection: some View {
        let gen = data["generation"]
        return Section {
            ForEach(gen["players"].array, id: \.["name"].string) { row in LabeledContent(row["name"].string, value: "\(row["newChunks"].int) neue Chunks") }
            if gen["players"].array.isEmpty { Text("Niemand erkundet gerade neues Gebiet.").foregroundStyle(.secondary) }
        } header: { Text("Chunk-Generierung (10 min)") } footer: { Text("\(gen["loads10m"].int) Ladevorgänge · \(gen["new10m"].int) neue Chunks. Viele neue Chunks: Welt vorgenerieren (Chunky) und Weltgrenze setzen.") }
    }
    private var playersSection: some View {
        Section("Spieler nach Entities in der Nähe") {
            ForEach(data["players"].array, id: \.["name"].string) { row in
                LabeledContent(row["name"].string, value: "\(row["nearbyEntities"].int) Entities · \(row["ping"].int) ms")
            }
        }
    }
    func load(refresh: Bool) async {
        busy = true
        defer { busy = false }
        do { data = try await suite.request("diagnostics/lagmap", query: refresh ? ["refresh": "1"] : [:]) }
        catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }
}

struct SuiteLagChunkRow: View {
    let row: JSON
    let unit: String
    var types: String { row["types"].array.map { "\($0["count"].int)× " + $0["type"].string }.joined(separator: ", ") }
    var countTint: Color { row["count"].int >= 300 ? Color.red : (row["count"].int >= 120 ? Color.orange : Color.primary) }
    var command: String { SuiteDiagnostics.teleport(world: row["world"].string, x: row["x"].int, y: row["y"].int == 0 ? 100 : row["y"].int, z: row["z"].int) }
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text("\(row["count"].int) \(unit)").font(.subheadline.weight(.bold)).foregroundStyle(countTint)
                Spacer()
                Text(row["world"].string + " \(row["x"].int) / \(row["z"].int)").font(.caption.monospaced())
            }
            Text(types).font(.caption).foregroundStyle(.secondary)
            if !row["nearest"].string.isEmpty { Text("In der Nähe: " + row["nearest"].string).font(.caption2).foregroundStyle(.secondary) }
            CopyButton(text: command, title: "Teleport-Befehl kopieren").font(.caption)
        }
    }
}

// MARK: - Spark

struct SuiteSparkView: View {
    let suite: SuiteAPI
    let canRun: Bool
    let canManage: Bool
    let aiAvailable: Bool
    @State private var data: JSON = .null
    @State private var seconds = 60
    @State private var allThreads = false
    @State private var link = ""
    @State private var message: String?
    @State private var error: String?
    var job: JSON { data["job"] }
    var busy: Bool { ["running", "uploading", "analyzing"].contains(job["status"].string) || data["items"].array.contains { ["downloading", "analyzing"].contains($0["status"].string) } }
    var body: some View {
        List {
            recordSection
            Section {
                TextField("https://spark.lucko.me/… oder Code", text: $link).textInputAutocapitalization(.never).autocorrectionDisabled()
                Button("Profil laden & analysieren", systemImage: "arrow.down.circle") { Task { await importProfile() } }.disabled(!canRun || link.trimmingCharacters(in: .whitespaces).isEmpty)
            } header: { Text("Vorhandenes Profil analysieren") } footer: { Text("Der Server lädt das Rohprofil direkt von Spark und wertet es selbst aus – auch sehr große Profile.") }
            Section("Profile") {
                if data == .null { ProgressView() }
                else if data["items"].array.isEmpty { Text("Noch keine Profile.").foregroundStyle(.secondary) }
                ForEach(data["items"].array, id: \.["code"].string) { row in profileRow(row) }
            }
            if let message { Section { Text(message).font(.caption) } }
        }
        .panelScreen()
        .navigationTitle("Spark")
        .task(id: busy) { await load(); while busy && !Task.isCancelled { try? await Task.sleep(for: .seconds(4)); await load() } }
        .refreshable { await load() }
        .errorAlert($error)
    }
    @ViewBuilder private var recordSection: some View {
        Section {
            if !data["available"].bool {
                Text("Spark ist auf diesem Server nicht verfügbar. Paper enthält Spark meist bereits; sonst das Spark-Plugin installieren.").font(.subheadline).foregroundStyle(.secondary)
            } else {
                if job["status"].string == "running" { Label("Profil läuft · \(job["seconds"].int) s · gestartet von " + job["by"].string, systemImage: "record.circle").foregroundStyle(.red) }
                if ["uploading", "analyzing"].contains(job["status"].string) { HStack { ProgressView(); Text("Profil wird hochgeladen und analysiert …") } }
                Picker("Dauer", selection: $seconds) { Text("30 Sekunden").tag(30); Text("1 Minute").tag(60); Text("2 Minuten").tag(120); Text("5 Minuten").tag(300); Text("10 Minuten").tag(600) }
                Toggle("Alle Threads (sonst nur Server-Thread)", isOn: $allThreads)
                Button("Profil starten", systemImage: "play.circle.fill") { Task { await control("start") } }.disabled(!canManage || job["status"].string == "running")
                if job["status"].string == "running" { Button("Jetzt beenden", systemImage: "stop.circle") { Task { await control("stop") } }.disabled(!canManage) }
            }
        } header: { Text("Profil aufnehmen") } footer: { Text(canManage ? "Nach Ablauf lädt Spark das Profil hoch; die Suite analysiert es automatisch." : "Profile starten benötigt das Recht „Diagnose steuern“.") }
    }
    private func profileRow(_ row: JSON) -> some View {
        let done = row["status"].string == "done"
        let title = row["code"].string
        var detail = SuiteDiagnostics.ago(row["at"].double) + " · " + row["origin"].string
        if row["tpsAvg"] != .null { detail += " · TPS Ø " + SuiteDiagnostics.number(row["tpsAvg"].double) + " · Spitze " + SuiteDiagnostics.number(row["msptPeak"].double, digits: 0) + " ms" }
        if !row["error"].string.isEmpty { detail += " · " + row["error"].string }
        return NavigationLink { SuiteSparkDetailView(suite: suite, code: title, canRun: canRun, aiAvailable: aiAvailable) } label: {
            VStack(alignment: .leading, spacing: 2) {
                HStack { Text(title).font(.subheadline.monospaced().weight(.semibold)); Spacer(); Text(statusTitle(row["status"].string)).font(.caption).foregroundStyle(statusTint(row["status"].string)) }
                Text(detail).font(.caption).foregroundStyle(.secondary)
            }
        }.disabled(!done)
    }
    private func statusTint(_ status: String) -> Color {
        switch status {
        case "done": return Color.green
        case "failed": return Color.red
        default: return Color.orange
        }
    }
    private func statusTitle(_ status: String) -> String {
        switch status {
        case "done": return "Fertig"
        case "failed": return "Fehler"
        case "downloading": return "Lädt"
        default: return "Analysiert"
        }
    }
    func control(_ operation: String) async {
        do {
            let reply = try await suite.post("diagnostics/spark", ["operation": .string(operation), "seconds": .string(String(seconds)), "allThreads": .string(String(allThreads))])
            message = reply["description"].string
            await load()
        } catch { self.error = error.localizedDescription }
    }
    func importProfile() async {
        do {
            let reply = try await suite.post("diagnostics/spark", ["operation": .string("import"), "code": .string(link.trimmingCharacters(in: .whitespaces))])
            message = reply["description"].string
            link = ""
            try? await Task.sleep(for: .seconds(1))
            await load()
        } catch { self.error = error.localizedDescription }
    }
    func load() async {
        do { data = try await suite.request("diagnostics/spark") }
        catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }
}

struct SuiteSparkDetailView: View {
    let suite: SuiteAPI
    let code: String
    let canRun: Bool
    let aiAvailable: Bool
    @State private var data: JSON = .null
    @State private var error: String?
    var analysis: JSON { data["analysis"] }
    var windows: JSON { analysis["windows"] }
    var meta: JSON { analysis["metadata"] }
    var body: some View {
        List {
            if data == .null { ProgressView() } else {
                summarySection
                Section("Befunde") { ForEach(Array(analysis["findings"].array.enumerated()), id: \.offset) { item in SuiteDiagnosticsFindingRow(row: item.element) } }
                Section { SuiteAIBox(suite: suite, target: "spark:" + code, initial: data["ai"], available: aiAvailable, canRun: canRun) } header: { Text("KI-Einschätzung") } footer: { Text("Gesendet werden nur Kennzahlen und Top-Methoden, nicht das Profil.") }
                Section("Tickverlauf") { SuiteDiagnosticsChart(points: SuiteDiagnostics.windowPoints(windows), maxLabel: "Spitze je Minute") }
                pluginsSection
                framesSection("Plugin-Stellen", rows: analysis["pluginFrames"].array, owner: "plugin")
                framesSection("Teuerste Methoden (Eigenzeit)", rows: analysis["methods"].array, owner: "source")
                hotSection
                configSection
            }
        }
        .panelScreen()
        .navigationTitle(code)
        .toolbar { if let url = URL(string: data["url"].string), data != .null { ToolbarItem(placement: .topBarTrailing) { Link(destination: url) { Image(systemName: "safari") }.accessibilityLabel("Im Spark-Viewer öffnen") } } }
        .task { await load() }
        .errorAlert($error)
    }
    private var summarySection: some View {
        let platform = meta["platform"]
        return Section {
            LabeledContent("TPS Ø / Minimum", value: SuiteDiagnostics.number(windows["tpsAvg"].double, digits: 2) + " / " + SuiteDiagnostics.number(windows["tpsMin"].double, digits: 2))
            LabeledContent("Tick-Median Ø", value: SuiteDiagnostics.number(windows["msptMedianAvg"].double) + " ms")
            LabeledContent("Längster Tick", value: SuiteDiagnostics.number(windows["msptPeak"].double, digits: 0) + " ms")
            LabeledContent("Leerlauf (Reserve)", value: SuiteDiagnostics.percent(analysis["idleShare"].double))
            LabeledContent("Entities Ø", value: "\(windows["entitiesAvg"].int) · \(windows["tileEntitiesAvg"].int) Block-Entities")
            LabeledContent("Spieler Ø", value: SuiteDiagnostics.number(windows["playersAvg"].double))
            LabeledContent("Dauer", value: SuiteDiagnostics.duration(meta["durationSeconds"].double * 1000))
        } header: { Text(platform["name"].string + " " + platform["version"].string) } footer: { Text("Spark " + platform["spark"].string + " · " + meta["engine"].string) }
    }
    private var pluginsSection: some View {
        let rows = analysis["plugins"].array
        let top = rows.first?["share"].double ?? 1
        return Section {
            ForEach(Array(rows.enumerated()), id: \.offset) { item in
                SuiteDiagnosticsBar(title: item.element["name"].string, value: item.element["share"].double, total: top, label: SuiteDiagnostics.percent(item.element["share"].double))
            }
        } header: { Text("Plugins nach Rechenzeit") } footer: { Text("Inklusive der Paper-Arbeit, die das Plugin auslöst. Rest " + SuiteDiagnostics.percent(analysis["vanillaShare"].double) + " Paper/Minecraft.") }
    }
    private func framesSection(_ title: String, rows: [JSON], owner: String) -> some View {
        Section(title) {
            ForEach(Array(rows.prefix(20).enumerated()), id: \.offset) { item in
                VStack(alignment: .leading, spacing: 2) {
                    Text(item.element["frame"].string).font(.caption.monospaced()).lineLimit(2)
                    Text((item.element[owner].string.isEmpty ? "Paper/Minecraft" : item.element[owner].string) + " · " + SuiteDiagnostics.percent(item.element["share"].double)).font(.caption2).foregroundStyle(.secondary)
                }
            }
        }
    }
    @ViewBuilder private var hotSection: some View {
        let rows = meta["platformStatistics"]["world"]["hotChunks"].array
        if !rows.isEmpty {
            Section("Entity-Hotspots zum Zeitpunkt des Profils") {
                ForEach(Array(rows.enumerated()), id: \.offset) { item in
                    let row = item.element
                    VStack(alignment: .leading, spacing: 2) {
                        Text("\(row["entities"].int) Entities · " + row["world"].string + " \(row["blockX"].int) / \(row["blockZ"].int)").font(.subheadline)
                        Text(row["types"].array.map { "\($0["count"].int)× " + $0["type"].string }.joined(separator: ", ")).font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
        }
    }
    private var configSection: some View {
        Section("Konfiguration") {
            ForEach(meta["config"].object.keys.sorted(), id: \.self) { key in LabeledContent(key, value: meta["config"][key].string) }
        }
    }
    func load() async {
        do { data = try await suite.request("diagnostics/spark", query: ["code": code]) }
        catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }
}

// MARK: - Crashes

struct SuiteCrashesView: View {
    let suite: SuiteAPI
    let canRun: Bool
    let aiAvailable: Bool
    @State private var data: JSON = .null
    @State private var error: String?
    var body: some View {
        List {
            Section {
                if data == .null { ProgressView() }
                else if data["items"].array.isEmpty { Label("Kein Absturz erkannt.", systemImage: "checkmark.seal.fill").foregroundStyle(.green) }
                ForEach(data["items"].array, id: \.["id"].string) { row in
                    NavigationLink { SuiteCrashDetailView(suite: suite, id: row["id"].string, canRun: canRun, aiAvailable: aiAvailable) } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(row["title"].string).font(.subheadline.weight(.semibold))
                            Text(Self.detail(row)).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
            } footer: { Text("Erkannt über Herzschlag, Konsole, hs_err- und Crash-Report-Dateien. Normale Neustarts erscheinen hier nicht.") }
        }
        .panelScreen()
        .navigationTitle("Abstürze")
        .task { await load() }
        .refreshable { await load() }
        .errorAlert($error)
    }
    static func detail(_ row: JSON) -> String {
        let kind: String = SuiteDiagnostics.crashTitle(row["kind"].string)
        let when: String = SuiteDiagnostics.ago(row["detectedAt"].double)
        let down: String = SuiteDiagnostics.duration(row["downtimeSeconds"].double * 1000)
        return kind + " · " + when + " · Ausfall " + down + " · Sicherheit " + row["confidence"].string
    }
    func load() async {
        do { data = try await suite.request("diagnostics/crashes") }
        catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }
}

struct SuiteCrashDetailView: View {
    let suite: SuiteAPI
    let id: String
    let canRun: Bool
    let aiAvailable: Bool
    @State private var data: JSON = .null
    @State private var error: String?
    var logText: String { data["log"].array.map(\.string).joined(separator: "\n") }
    var body: some View {
        List {
            if data == .null { ProgressView() } else {
                Section {
                    Text(data["title"].string).font(.headline)
                    Text(data["cause"].string).font(.subheadline)
                    LabeledContent("Letzter Herzschlag", value: Date(timeIntervalSince1970: data["lastBeat"].double / 1000).formatted(date: .abbreviated, time: .standard))
                    LabeledContent("Ausfall", value: SuiteDiagnostics.duration(data["downtimeSeconds"].double * 1000))
                    LabeledContent("RAM davor", value: bytes(data["heartbeat"]["heapUsed"].double) + " / " + bytes(data["heartbeat"]["heapMax"].double))
                    LabeledContent("Spieler davor", value: data["heartbeat"]["players"].string)
                } header: { Text("Sicherheit: " + data["confidence"].string) }
                if !data["evidence"].array.isEmpty {
                    Section("Belege") { ForEach(Array(data["evidence"].array.enumerated()), id: \.offset) { item in Text(item.element.string).font(.caption.monospaced()).textSelection(.enabled) } }
                }
                Section("KI-Einschätzung") { SuiteAIBox(suite: suite, target: "crash:" + id, initial: data["ai"], available: aiAvailable, canRun: canRun) }
                if !data["hsErr"].string.isEmpty { Section("hs_err (Auszug)") { Text(data["hsErr"].string).font(.caption2.monospaced()).textSelection(.enabled) } }
                if !data["crashReport"].string.isEmpty { Section("Crash-Report (Auszug)") { Text(data["crashReport"].string).font(.caption2.monospaced()).textSelection(.enabled) } }
                Section {
                    CopyButton(text: logText, title: "Log kopieren")
                    Text(logText.isEmpty ? "Keine Zeilen gesichert." : logText).font(.caption2.monospaced()).textSelection(.enabled)
                } header: { Text("Letzte Konsolenzeilen") }
            }
        }
        .panelScreen()
        .navigationTitle(SuiteDiagnostics.crashTitle(data["kind"].string))
        .task { await load() }
        .errorAlert($error)
    }
    func load() async {
        do { data = try await suite.request("diagnostics/crashes", query: ["id": id]) }
        catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }
}

// MARK: - Full diagnosis

struct SuiteFullDiagnosisView: View {
    let suite: SuiteAPI
    let canRun: Bool
    let aiAvailable: Bool
    @State private var data: JSON = .null
    @State private var running = false
    @State private var error: String?
    static let groups: [(String, String)] = [("server", "Server"), ("memory", "Speicher"), ("system", "System"), ("world", "Welt"), ("network", "Netzwerk"), ("plugins", "Plugins")]
    var checks: [JSON] { data["checks"].array }
    var runTitle: String { running ? "Prüfe …" : (data["at"] == .null ? "Volldiagnose starten" : "Erneut prüfen") }
    var body: some View {
        List {
            Section {
                Button(runTitle, systemImage: "stethoscope") { Task { await run() } }.disabled(!canRun || running)
                if running { ProgressView("TPS, RAM, CPU, Welt, Plugins, Konsole und Netzwerk werden geprüft …") }
            } footer: { Text(canRun ? "Prüft auch DNS, Mojang-Anmeldung und Internet-Latenz vom Server aus." : "Benötigt das Recht „Diagnosen ausführen“.") }
            if data["at"] != .null { scoreSection }
            ForEach(Self.groups, id: \.0) { group in
                let rows = checks.filter { $0["group"].string == group.0 }
                if !rows.isEmpty {
                    Section(group.1) { ForEach(Array(rows.enumerated()), id: \.offset) { item in SuiteDiagnosticsFindingRow(row: item.element) } }
                }
            }
            if data["at"] != .null { Section("KI-Einschätzung") { SuiteAIBox(suite: suite, target: "full:", initial: .null, available: aiAvailable, canRun: canRun) } }
        }
        .panelScreen()
        .navigationTitle("Volldiagnose")
        .task { await load() }
        .errorAlert($error)
    }
    private var scoreSection: some View {
        let score = data["score"].int
        return Section {
            HStack(spacing: 14) {
                Text("\(score)").font(.system(size: 40, weight: .heavy, design: .rounded)).foregroundStyle(score >= 85 ? Color.green : (score >= 60 ? Color.orange : Color.red))
                VStack(alignment: .leading, spacing: 2) {
                    Text(data["summary"].string).font(.headline)
                    Text("\(data["critical"].int) kritisch · \(data["warnings"].int) Hinweise · \(checks.count) Prüfungen · " + SuiteDiagnostics.ago(data["at"].double)).font(.caption).foregroundStyle(.secondary)
                }
            }
        }
    }
    func run() async {
        running = true
        defer { running = false }
        do { data = try await suite.post("diagnostics/full", [:]) }
        catch { self.error = error.localizedDescription }
    }
    func load() async {
        do { data = try await suite.request("diagnostics/full") }
        catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }
}

// MARK: - Console

struct SuiteDiagnosticsConsoleView: View {
    let suite: SuiteAPI
    let canRun: Bool
    let aiAvailable: Bool
    @State private var data: JSON = .null
    @State private var rows: [JSON] = []
    @State private var next = ""
    @State private var level = ""
    @State private var search = ""
    @State private var filterDates = false
    @State private var start = Date().addingTimeInterval(-7 * 86400)
    @State private var end = Date()
    @State private var days = 30
    @State private var loading = false
    @State private var pending: [String: JSON]?
    @State private var error: String?

    private var archived: Bool { suite.features.contains("console-archive-v1") }
    private var filters: String { level + "|" + search + (filterDates ? "|\(start.timeIntervalSince1970)|\(end.timeIntervalSince1970)" : "") }

    var body: some View {
        List {
            Section {
                Picker("Stufe", selection: $level) {
                    Text("Alles").tag(""); Text("Warnungen+").tag("WARN"); Text("Fehler").tag("ERROR")
                }.pickerStyle(.segmented)
                if archived {
                    Toggle("Zeitraum filtern", isOn: $filterDates)
                    if filterDates {
                        DatePicker("Von", selection: $start)
                        DatePicker("Bis", selection: $end)
                    }
                }
            } footer: {
                Text((archived ? data["days"].string + " Tage · IPs und erkannte Zugangsdaten maskiert" : "Letzte 3 Stunden · IP-Adressen maskiert") + " · " + data["tap"].string)
            }
            if archived && data["manage"].bool {
                Section("Archiv verwalten") {
                    Stepper("Aufbewahrung · \(days) Tage", value: $days, in: 1...365)
                    Button("Aufbewahrung speichern") { pending = ["operation": .string("retention"), "days": .number(Double(days))] }
                    Button("Gesamtes Archiv leeren", role: .destructive) { pending = ["operation": .string("clear")] }
                }
            }
            Section("Konsolenzeilen") {
                if data == .null { ProgressView() }
                else if rows.isEmpty { Text("Keine Treffer.").foregroundStyle(.secondary) }
                if data["dropped"].int > 0 {
                    Text("Aufnahmepuffer überlastet: \(data["dropped"].int) Zeilen fehlen.").font(.caption).foregroundStyle(.orange)
                }
                ForEach(Array(rows.enumerated()), id: \.offset) { item in
                    SuiteDiagnosticsLogRow(row: item.element)
                        .swipeActions {
                            if archived && data["manage"].bool {
                                Button("Löschen") { pending = ["operation": .string("delete"), "id": item.element["id"]] }.tint(.red)
                            }
                        }
                        .contextMenu {
                            CopyButton(text: item.element["text"].string, title: "Zeile kopieren")
                            if archived && data["manage"].bool {
                                Button("Zeile löschen", role: .destructive) { pending = ["operation": .string("delete"), "id": item.element["id"]] }
                            }
                        }
                }
                if !next.isEmpty { Button("Ältere Zeilen laden") { Task { await load(older: true) } }.disabled(loading) }
            }
            Section("KI-Einschätzung der Warnungen (20 min)") {
                SuiteAIBox(suite: suite, target: "logs:", initial: .null, available: aiAvailable, canRun: canRun)
            }
        }
        .panelScreen()
        .navigationTitle(archived ? "Konsolenarchiv" : "Konsole")
        .searchable(text: $search, prompt: "Stichwort oder Plugin-Name")
        .task(id: filters) {
            do { try await Task.sleep(for: .milliseconds(250)) } catch { return }
            await load()
        }
        .refreshable { await load() }
        .confirmationDialog("Archiv ändern?", isPresented: Binding(get: { pending != nil }, set: { if !$0 { pending = nil } }), titleVisibility: .visible) {
            if let pending { Button("Bestätigen", role: .destructive) { Task { await change(pending) } } }
        } message: { Text("Die betroffenen Archivzeilen werden dauerhaft gelöscht. Die Aufzeichnung läuft weiter.") }
        .errorAlert($error)
    }

    private func load(older: Bool = false) async {
        let currentFilters = filters
        if older && loading { return }
        loading = true
        defer { loading = false }
        do {
            var query = ["level": level, "q": search, "limit": "150", "minutes": "180"]
            if archived && filterDates { query["from"] = String(Int64(start.timeIntervalSince1970 * 1000)); query["to"] = String(Int64(end.timeIntervalSince1970 * 1000)) }
            if older { query["before"] = next }
            let response = try await suite.request(archived ? "diagnostics/console-archive" : "diagnostics/logs", query: query)
            guard !Task.isCancelled, filters == currentFilters else { return }
            data = response
            if archived { days = response["days"].int }
            let page = Array(response["items"].array.reversed())
            rows = older ? rows + page : page
            next = response["next"].string
        } catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }

    private func change(_ change: [String: JSON]) async {
        var body = change
        body["confirmed"] = .bool(true)
        do { _ = try await suite.post("diagnostics/console-archive", body); await load() }
        catch { self.error = error.localizedDescription }
        pending = nil
    }
}

// MARK: - Settings

struct SuiteDiagnosticsSettingsView: View {
    let suite: SuiteAPI
    let settings: JSON
    @Environment(\.dismiss) private var dismiss
    @State private var burstWindow = 10
    @State private var burstMin = 3
    @State private var waveShare = 0.35
    @State private var spikeMs = 1000
    @State private var flappingCount = 3
    @State private var aiPerHour = 20
    @State private var enabled = true
    @State private var notifyLegit = false
    @State private var aiEnabled = true
    @State private var aiAutoCritical = true
    @State private var aiAutoCrash = true
    @State private var aiAutoUnclear = true
    @State private var tcpSampling = true
    @State private var liveLog = true
    @State private var saving = false
    @State private var error: String?
    var body: some View {
        Form {
            Section {
                Stepper("Fenster: \(burstWindow) s", value: $burstWindow, in: 5...120, step: 5)
                Stepper("Ab \(burstMin) Spielern", value: $burstMin, in: 2...50)
                Stepper("Welle ab \(Int(waveShare * 100)) % der Spieler", value: $waveShare, in: 0.15...0.95, step: 0.05)
                Stepper("Instabil ab \(flappingCount) Trennungen / 10 min", value: $flappingCount, in: 2...20)
            } header: { Text("Disconnect-Vorfälle") } footer: { Text("Mehrere Disconnects im Fenster oder eine Welle erzeugen einen Vorfall mit Einschätzung und Bark-Hinweis.") }
            Section("Lag") { Stepper("Lag-Spitze ab \(spikeMs) ms", value: $spikeMs, in: 250...20000, step: 250) }
            Section {
                Toggle("Diagnose aktiv", isOn: $enabled)
                Toggle("Auch legitime Wellen melden", isOn: $notifyLegit)
                Toggle("Konsole für Absturzberichte sichern", isOn: $liveLog)
                if settings["tcpSampling"] != .null { Toggle("TCP-Werte aller Verbindungen messen", isOn: $tcpSampling) }
            }
            Section {
                Toggle("KI-Analysen erlauben", isOn: $aiEnabled)
                Toggle("Kritische Vorfälle automatisch prüfen", isOn: $aiAutoCritical)
                Toggle("Abstürze automatisch prüfen", isOn: $aiAutoCrash)
                if settings["aiAutoUnclear"] != .null { Toggle("Unklare und verdeckte Wellen automatisch prüfen", isOn: $aiAutoUnclear) }
                Stepper("\(aiPerHour) Analysen pro Stunde", value: $aiPerHour, in: 0...120)
            } header: { Text("KI (vorhandener OpenAI-Schlüssel)") } footer: { Text("Gesendet werden nur kurze, IP-bereinigte Auszüge.") }
        }
        .panelScreen()
        .navigationTitle("Diagnose-Einstellungen")
        .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Sichern") { Task { await save() } }.disabled(saving) } }
        .onAppear { apply() }
        .errorAlert($error)
    }
    func apply() {
        burstWindow = settings["burstWindow"].int == 0 ? 10 : settings["burstWindow"].int
        burstMin = settings["burstMin"].int == 0 ? 3 : settings["burstMin"].int
        waveShare = settings["waveShare"].double == 0 ? 0.35 : settings["waveShare"].double
        spikeMs = settings["spikeMs"].int == 0 ? 1000 : settings["spikeMs"].int
        flappingCount = settings["flappingCount"].int == 0 ? 3 : settings["flappingCount"].int
        aiPerHour = settings["aiPerHour"].int
        enabled = settings["enabled"].bool
        notifyLegit = settings["notifyLegit"].bool
        aiEnabled = settings["aiEnabled"].bool
        aiAutoCritical = settings["aiAutoCritical"].bool
        aiAutoCrash = settings["aiAutoCrash"].bool
        liveLog = settings["liveLog"].bool
        aiAutoUnclear = settings["aiAutoUnclear"] == .null ? true : settings["aiAutoUnclear"].bool
        tcpSampling = settings["tcpSampling"] == .null ? true : settings["tcpSampling"].bool
    }
    func save() async {
        saving = true
        defer { saving = false }
        let share = String(format: "%.2f", waveShare)
        var body: [String: JSON] = ["burstWindow": .string(String(burstWindow)), "burstMin": .string(String(burstMin)), "waveShare": .string(share), "spikeMs": .string(String(spikeMs)),
                                    "flappingCount": .string(String(flappingCount)), "aiPerHour": .string(String(aiPerHour)), "enabled": .string(String(enabled)), "notifyLegit": .string(String(notifyLegit)),
                                    "aiEnabled": .string(String(aiEnabled)), "aiAutoCritical": .string(String(aiAutoCritical)), "aiAutoCrash": .string(String(aiAutoCrash)), "liveLog": .string(String(liveLog))]
        // 5.9.0 settings are only sent to suites that know them (older suites reject unknown keys).
        if settings["aiAutoUnclear"] != .null { body["aiAutoUnclear"] = .string(String(aiAutoUnclear)) }
        if settings["tcpSampling"] != .null { body["tcpSampling"] = .string(String(tcpSampling)) }
        do { _ = try await suite.post("diagnostics/settings", body); dismiss() }
        catch { self.error = error.localizedDescription }
    }
}

// MARK: - Player profile

/// Connection history of one player (last 7 days) for the player profile.
struct SuitePlayerConnectionSection: View {
    let suite: SuiteAPI
    let player: String
    @State private var data: JSON = .null
    @State private var failed = false
    var body: some View {
        Section {
            if data == .null { if failed { Text("Verbindungsdaten nicht verfügbar.").foregroundStyle(.secondary) } else { ProgressView() } } else { content }
        } header: { Text("Verbindung & Disconnects") } footer: { Text(data["hint"].string) }
        .task { await load() }
    }
    @ViewBuilder private var content: some View {
        HStack(spacing: 14) {
            stat("Heute", data["day"].int, data["day"].int >= 5 ? Color.orange : Color.primary)
            stat("7 Tage", data["week"].int, Color.primary)
            stat("Unfreiwillig", data["involuntary"].int, data["involuntary"].int >= 3 ? Color.red : Color.primary)
        }
        if data["flapping"].bool { Label("Verbindung gerade instabil", systemImage: "wifi.exclamationmark").foregroundStyle(.red) }
        if data["avgSessionMs"].double > 0 { LabeledContent("Ø Sitzung", value: SuiteDiagnostics.duration(data["avgSessionMs"].double)) }
        ForEach(data["items"].array.prefix(5), id: \.["id"].string) { row in SuiteDisconnectRow(row: row) }
    }
    private func stat(_ title: String, _ value: Int, _ tint: Color) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("\(value)").font(.title3.weight(.bold).monospacedDigit()).foregroundStyle(tint)
            Text(title).font(.caption2).foregroundStyle(.secondary)
        }
    }
    func load() async {
        do { data = try await suite.request("diagnostics/player", query: ["player": player]); failed = false }
        catch { if !RequestFailure.isCancellation(error) { failed = true } }
    }
}
