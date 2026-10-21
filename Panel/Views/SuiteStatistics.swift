import SwiftUI
import Charts

struct SuiteStatisticsView: View {
    @ObservedObject var model: SuiteModel
    @ObservedObject var live: LiveServer
    var body: some View {
        Group {
            if let suite = model.api {
                SuiteStatisticsDashboard(suite: suite, panel: model.panel, server: model.server, actions: model.actions, live: live)
                    .id(ObjectIdentifier(suite))
            } else {
                List {
                    if model.busy { ProgressView("Statistiken verbinden …") }
                    Text(model.error ?? "Die gespeicherte Suite-Verbindung wird geprüft.").foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                    Button("Erneut verbinden", systemImage: "arrow.clockwise") { Task { await model.load(force: true) } }
                }.panelScreen()
            }
        }.navigationTitle("Statistiken").navigationBarTitleDisplayMode(.inline).task { await model.load() }
    }
}
struct SuiteStatisticsDashboard: View {
    let suite: SuiteAPI
    let panel: PanelAPI
    let server: Server
    let actions: [SuiteAction]
    @ObservedObject var live: LiveServer
    @StateObject private var stats = SuiteStatisticsStore()
    @Environment(\.scenePhase) private var phase
    @State private var detailsTask: Task<Void, Never>?
    @State private var selected: StatisticsBucket?
    var snapshot: SuiteStatisticsSnapshot { stats.snapshot }
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                header
                if let error = stats.error {
                    VStack(alignment: .leading, spacing: 12) {
                        Label(error, systemImage: "wifi.exclamationmark").foregroundStyle(.orange).fixedSize(horizontal: false, vertical: true)
                        Button("Erneut laden") { Task { await stats.load(suite: suite, panel: panel, server: server, force: true) } }
                    }.card()
                }
                if stats.updated != nil {
                    summary
                    clients
                    rankings
                    history
                    moderation
                    performance
                    sources
                } else if stats.busy { ProgressView("Alle Suite-Profile laden …").frame(maxWidth: .infinity).padding(40) }
            }.padding(20).frame(maxWidth: 1000).frame(maxWidth: .infinity)
        }.panelScreen().refreshable { detailsTask?.cancel(); await stats.load(suite: suite, panel: panel, server: server, force: true) }
            .task(id: phase) { if phase == .active { await stats.load(suite: suite, panel: panel, server: server) } else { detailsTask?.cancel() } }
            .onDisappear { detailsTask?.cancel() }
            .sheet(item: $selected) { bucket in NavigationStack { StatisticsMembersView(bucket: bucket, suite: suite, panel: panel, server: server, actions: actions).toolbar { ToolbarItem(placement: .confirmationAction) { Button("Fertig") { selected = nil } } } } }
    }
    var header: some View {
        VStack(alignment: .leading, spacing: 9) {
            Label("DEINE COMMUNITY", systemImage: "chart.bar.xaxis").font(.caption.weight(.bold)).tracking(2).foregroundStyle(Color.panelAccent)
            Text("Statistiken").font(.system(.largeTitle, design: .rounded).bold()).accessibilityIdentifier("suiteStatisticsTitle")
            Text(server.name).font(.subheadline).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            HStack {
                if let updated = stats.updated { Text("Profile aktualisiert"); Text(updated, style: .time).monospacedDigit() }
                Spacer()
                if stats.busy { ProgressView() }
            }.font(.caption).foregroundStyle(.secondary)
            if stats.updated != nil && !stats.complete { Label("Teilansicht: \(snapshot.profiles.count) von \(stats.expected) Profilen. Für einen vollständigen Stand erneut laden.", systemImage: "exclamationmark.circle").font(.footnote).foregroundStyle(.orange).fixedSize(horizontal: false, vertical: true) }
        }
    }
    var summary: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 145), spacing: 12)], spacing: 12) {
            countCard("Suite-Profile", count: snapshot.profiles.count, symbol: "person.2.fill")
            countCard("Jetzt online", count: snapshot.online, symbol: "dot.radiowaves.left.and.right")
            countCard("Aktiv · 24 Stunden", count: snapshot.recent, symbol: "clock")
            countCard("Neue Profile · 24 Stunden", count: snapshot.newProfiles, symbol: "person.badge.plus")
        }
    }
    func countCard(_ title: String, count: Int, symbol: String) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Image(systemName: symbol).foregroundStyle(Color.panelAccent)
            Text(count, format: .number).font(.system(.title, design: .rounded).bold()).monospacedDigit()
            Text(title).font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
        }.frame(maxWidth: .infinity, alignment: .leading).card()
    }
    var clients: some View {
        let groups = snapshot.groups[.clients] ?? []
        return VStack(alignment: .leading, spacing: 16) {
            Label("Java & Bedrock", systemImage: "person.2").font(.headline)
            if snapshot.profiles.isEmpty { Text("Noch keine Profile erfasst.").foregroundStyle(.secondary) }
            else {
                Chart(groups) { bucket in
                    SectorMark(angle: .value("Profile", bucket.count), innerRadius: .ratio(0.68), angularInset: 3)
                        .foregroundStyle(bucket.title == "Java" ? Color.teal : bucket.title == "Bedrock" ? Color.indigo : Color.gray).cornerRadius(5)
                }.frame(height: 170).accessibilityLabel("Verteilung der Suite-Profile nach Java und Bedrock")
                ForEach(groups) { bucket in bucketButton(bucket) }
            }
            Text("Auf eine Kategorie tippen, um die zugehörigen Profile zu öffnen.").font(.caption).foregroundStyle(.secondary)
        }.frame(maxWidth: .infinity, alignment: .leading).card()
    }
    func bucketButton(_ bucket: StatisticsBucket) -> some View {
        Button { selected = bucket } label: {
            VStack(alignment: .leading, spacing: 8) {
                HStack(alignment: .firstTextBaseline) {
                    Text(bucket.title).fixedSize(horizontal: false, vertical: true)
                    Spacer(minLength: 12)
                    Text("\(bucket.count)").monospacedDigit()
                    Text(bucket.fraction, format: .percent.precision(.fractionLength(1))).font(.caption).foregroundStyle(.secondary).monospacedDigit()
                    Image(systemName: "chevron.right").font(.caption)
                }
                ProgressView(value: bucket.fraction).tint(Color.panelAccent)
            }.contentShape(Rectangle())
        }.buttonStyle(.plain).accessibilityIdentifier("statistics-bucket-" + bucket.id)
    }
    var rankings: some View {
        VStack(alignment: .leading, spacing: 18) {
            Label("Geräte & Versionen", systemImage: "iphone.and.arrow.right.inward").font(.headline)
            ForEach([StatisticsDimension.platforms, .models, .versions, .inputs, .languages]) { dimension in
                NavigationLink { StatisticsRankingView(dimension: dimension, stats: stats, suite: suite, panel: panel, server: server, actions: actions) } label: {
                    HStack {
                        Label(dimension.title, systemImage: dimension.symbol).fixedSize(horizontal: false, vertical: true)
                        Spacer()
                        Image(systemName: "chevron.right").font(.caption)
                    }
                }.buttonStyle(.plain).accessibilityIdentifier("statistics-" + dimension.rawValue)
            }
            Divider()
            if stats.detailsBusy {
                ProgressView(value: Double(stats.detailsDone), total: Double(max(1, stats.detailsTotal)))
                HStack { Text("Geräte-Details · \(stats.detailsDone) / \(stats.detailsTotal)"); Spacer(); Button("Anhalten") { detailsTask?.cancel() } }.font(.caption)
            } else {
                Button("Geräte-Details laden", systemImage: "arrow.down.circle") {
                    let bedrock = snapshot.profiles.filter { $0["client"].string.uppercased() == "BEDROCK" }.count
                    detailsTask = Task { await stats.enrich(suite: suite, force: bedrock > 0 && stats.loadedDeviceCount >= bedrock) }
                }.buttonStyle(.glass).disabled(stats.busy || !snapshot.profiles.contains { $0["client"].string.uppercased() == "BEDROCK" }).accessibilityIdentifier("statistics-load-devices")
            }
            Text("Pro Profil zählt das zuletzt erfasste Gerät. Modellnamen dienen als Plattform-Hinweis; Geräte-Details ergänzen gemeldete Plattform, Sprache und Eingabe. Java verrät kein Betriebssystem.").font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            if stats.detailFailures > 0 { Text("\(stats.detailFailures) Profile konnten nicht ergänzt werden. Erneutes Laden versucht nur fehlende Details.").font(.caption).foregroundStyle(.orange).fixedSize(horizontal: false, vertical: true) }
        }.frame(maxWidth: .infinity, alignment: .leading).card()
    }
    var history: some View {
        VStack(alignment: .leading, spacing: 16) {
            Label("Aufgezeichnete Online-Zahlen", systemImage: "chart.xyaxis.line").font(.headline)
            if let history = stats.history {
                if let last = history.last {
                    HStack { Text("Letzter Messpunkt"); Spacer(); Text(last, style: .relative) }.font(.caption).foregroundStyle(.secondary)
                    if Date().timeIntervalSince(last) > 180 { Label("Die gespeicherte Historie ist derzeit nicht aktuell.", systemImage: "clock.badge.exclamationmark").font(.caption).foregroundStyle(.orange) }
                }
                HStack {
                    LabeledContent("Peak · 24 h", value: history.peak(days: 1).map(String.init) ?? "–")
                    Spacer()
                    LabeledContent("Peak · 7 Tage", value: history.peak(days: 7).map(String.init) ?? "–")
                }.font(.subheadline)
                let hourly = history.hourly()
                if hourly.isEmpty { Text("Keine Messpunkte für die letzten 24 Stunden vorhanden.").font(.subheadline).foregroundStyle(.secondary) }
                else {
                    Chart(hourly) { sample in BarMark(x: .value("Stunde", sample.date, unit: .hour), y: .value("Online-Spitze", sample.online)).foregroundStyle(.teal.gradient).cornerRadius(3) }
                        .chartYScale(domain: .automatic(includesZero: true)).frame(height: 170).accessibilityLabel("Stündliche Online-Spitzen der letzten 24 Stunden")
                }
                if let count = history.worldPlayers { LabeledContent("UUIDs in erfassten Welten", value: String(count)) }
                Text("Vorhandene Suite-Aufzeichnungen. Fehlende Stunden sind Datenlücken, keine Nullwerte. Gezählt werden öffentliche Online-Zahlen und eindeutige Welt-UUIDs, keine Personen.").font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            }
            if let note = stats.historyNote { Text(note).font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true) }
        }.frame(maxWidth: .infinity, alignment: .leading).card()
    }
    var moderation: some View {
        VStack(alignment: .leading, spacing: 14) {
            Label("Moderation & Community", systemImage: "shield.lefthalf.filled").font(.headline)
            LabeledContent("Aktive interne Banns", value: String(snapshot.bans))
            LabeledContent("Aktive interne Mutes", value: String(snapshot.mutes))
            LabeledContent("Gespeicherte Warnungen", value: String(snapshot.warnings))
            LabeledContent("Simplex Points") { Text(snapshot.points, format: .number.precision(.fractionLength(0...1))).monospacedDigit() }
        }.card()
    }
    var performance: some View {
        VStack(alignment: .leading, spacing: 14) {
            Label("Server im Blick", systemImage: "waveform.path.ecg").font(.headline)
            LabeledContent("CPU", value: live.lastUpdate == nil ? "–" : String(format: "%.1f %%", live.cpu))
            LabeledContent("RAM", value: live.lastUpdate == nil ? "–" : bytes(live.memory))
            if let tps = live.tps { LabeledContent("TPS · 1 Minute") { Text(tps.minute, format: .number.precision(.fractionLength(2))).foregroundStyle(Date().timeIntervalSince(tps.updated) <= 75 ? TPSSnapshot.color(tps.minute) : .secondary) } }
            if let date = live.lastUpdate { HStack { Text("Ressourcen-Messzeitpunkt"); Spacer(); Text(date, style: .time) }.font(.caption).foregroundStyle(.secondary) }
            NavigationLink { SuiteReadView(suite: suite, path: "server.status", title: "Suite-Serverstatus") } label: { Label("Vollständiger Suite-Serverstatus", systemImage: "server.rack").fixedSize(horizontal: false, vertical: true) }
        }.card()
    }
    var sources: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label("Über diese Zahlen", systemImage: "info.circle").font(.subheadline.bold())
            Text("Profile sind eindeutig nach UUID gezählt. Geräte- und Clientangaben sind vom Client gemeldet. Online-Status stammt aus der Suite; ausgeblendete Spieler können im Discord anders gezählt werden.").font(.caption).foregroundStyle(.secondary)
            Text("Gesamtspielzeit, AFK-Zeit, Sitzungszahl und Discord-Verknüpfungen sind in der aktuellen Schnittstelle nicht enthalten und werden nicht geschätzt.").font(.caption).foregroundStyle(.secondary)
        }.fixedSize(horizontal: false, vertical: true).card()
    }
}
struct StatisticsRankingView: View {
    let dimension: StatisticsDimension
    @ObservedObject var stats: SuiteStatisticsStore
    let suite: SuiteAPI
    let panel: PanelAPI
    let server: Server
    let actions: [SuiteAction]
    @State private var search = ""
    var groups: [StatisticsBucket] { (stats.snapshot.groups[dimension] ?? []).filter { search.isEmpty || $0.title.localizedCaseInsensitiveContains(search) } }
    var body: some View {
        List {
            Section {
                Text("Ein Profil pro zuletzt erfasster Angabe. Prozentwerte beziehen sich auf alle Profile dieser Auswertung, auch bei aktiver Suche.").font(.footnote).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            }
            Section(dimension.title) {
                ForEach(groups) { bucket in
                    NavigationLink { StatisticsMembersView(bucket: bucket, suite: suite, panel: panel, server: server, actions: actions) } label: {
                        VStack(alignment: .leading, spacing: 9) {
                            HStack(alignment: .firstTextBaseline) {
                                Text(bucket.title).fixedSize(horizontal: false, vertical: true)
                                Spacer()
                                Text("\(bucket.count)").bold().monospacedDigit()
                                Text(bucket.fraction, format: .percent.precision(.fractionLength(1))).font(.caption).foregroundStyle(.secondary)
                            }
                            ProgressView(value: bucket.fraction).tint(Color.panelAccent)
                        }.padding(.vertical, 4)
                    }.accessibilityIdentifier("statistics-bucket-" + bucket.id)
                }
                if groups.isEmpty { ContentUnavailableView.search(text: search) }
            }
        }.panelScreen().navigationTitle(dimension.title).navigationBarTitleDisplayMode(.inline).searchable(text: $search, prompt: "Kategorie suchen")
    }
}
struct StatisticsMembersView: View {
    let bucket: StatisticsBucket
    let suite: SuiteAPI
    let panel: PanelAPI
    let server: Server
    let actions: [SuiteAction]
    @State private var search = ""
    var members: [JSON] { SuitePlayerPresentation.sorted(bucket.members.filter { search.isEmpty || $0["name"].string.localizedCaseInsensitiveContains(search) || $0["id"].string.localizedCaseInsensitiveContains(search) }, filter: .all) }
    var body: some View {
        List {
            Section {
                Text(bucket.title).font(.title2.bold()).fixedSize(horizontal: false, vertical: true).accessibilityIdentifier("statistics-group-title")
                Text("\(bucket.count) zugeordnete Profile · Stand der ausgewählten Auswertung").font(.caption).foregroundStyle(.secondary)
            }
            Section("Spieler") {
                ForEach(members, id: \.["id"].string) { row in
                    NavigationLink { SuitePlayerView(suite: suite, panel: panel, server: server, player: row["id"].string, actions: actions, initialProfile: row) } label: { SuitePlayerRow(row: row, suite: suite) }
                }
                if members.isEmpty { ContentUnavailableView.search(text: search) }
            }
        }.panelScreen().navigationTitle("Zugehörige Spieler").navigationBarTitleDisplayMode(.inline).searchable(text: $search, prompt: "Name oder UUID")
    }
}
