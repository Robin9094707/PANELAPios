import SwiftUI

/// Geyser and Floodgate status from Admin Suite 9.4.0: installed SHA-256 against the latest GeyserMC build.
enum SuiteCrossplayStatus: Equatable {
    case missing, current, staged, update, unknown
    static func of(_ row: JSON) -> Self {
        if !row["installed"].bool { return .missing }
        if row["latestSha256"].string.isEmpty { return .unknown }
        if row["upToDate"].bool { return .current }
        if row["updateStaged"].bool { return .staged }
        return .update
    }
    var title: String { switch self { case .missing: return "Nicht installiert"; case .current: return "Aktuell"; case .staged: return "Update für Neustart vorgemerkt"; case .update: return "Update verfügbar"; case .unknown: return "Status unbekannt" } }
    var symbol: String { switch self { case .missing: return "minus.circle"; case .current: return "checkmark.seal.fill"; case .staged: return "clock.badge.checkmark.fill"; case .update: return "arrow.down.circle.fill"; case .unknown: return "questionmark.circle" } }
    var color: Color { switch self { case .missing, .unknown: return .secondary; case .current: return .green; case .staged: return .blue; case .update: return .orange } }
}

struct SuiteCrossplayCard: View {
    let suite: SuiteAPI
    var compact = false
    @StateObject private var model: SuiteSecurityModel
    @State private var data: JSON = .null
    @State private var loading = false
    @State private var error: String?
    init(suite: SuiteAPI, compact: Bool = false) { self.suite = suite; self.compact = compact; _model = StateObject(wrappedValue: SuiteSecurityModel(suite)) }
    var updates: Int { data["items"].array.filter { SuiteCrossplayStatus.of($0) == .update }.count }
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Label("Geyser & Floodgate", systemImage: "gamecontroller.fill").font(.headline)
                Spacer()
                if loading || model.busy { ProgressView().controlSize(.small) }
                else { Button("Status aktualisieren", systemImage: "arrow.clockwise") { Task { await load() } }.labelStyle(.iconOnly) }
            }
            if data == .null { Text(error ?? "Crossplay-Status wird geladen …").font(.subheadline).foregroundStyle(error == nil ? Color.secondary : .orange) }
            ForEach(data["items"].array, id: \.["id"].string) { row in
                let status = SuiteCrossplayStatus.of(row)
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Text(row["name"].string).font(.subheadline.weight(.semibold))
                        Spacer()
                        Label(status.title, systemImage: status.symbol).font(.caption.weight(.semibold)).foregroundStyle(status.color)
                    }
                    Text(detail(row)).font(.caption).foregroundStyle(.secondary)
                    if !compact, status == .update, !row["changes"].array.isEmpty { Text(row["changes"].array.prefix(3).map { "• " + $0.string }.joined(separator: "\n")).font(.caption2).foregroundStyle(.secondary) }
                    if !row["error"].string.isEmpty { Text(row["error"].string).font(.caption2).foregroundStyle(.orange) }
                }.accessibilityElement(children: .combine)
            }
            if data != .null && suite.scope == "full" {
                HStack {
                    Button("Jetzt vormerken", systemImage: "arrow.down.circle") { Task { await model.prepare("crossplay.update", ["restart": .string("false")], summary: "Geyser und Floodgate jetzt prüfen und neue Builds im Paper-Updateordner vormerken?\nPaper übernimmt sie beim nächsten Neustart.") } }
                        .buttonStyle(.glass).disabled(model.busy || data["busy"].bool)
                    if updates > 0 { Button("Mit Neustart", systemImage: "arrow.clockwise.circle") { Task { await model.prepare("crossplay.update", ["restart": .string("true")], summary: "Geyser und Floodgate vormerken und danach den Neustart-Countdown starten?") } }.buttonStyle(.glass).disabled(model.busy || data["busy"].bool) }
                }
                if !compact { Toggle("Automatisch alle 24 h prüfen", isOn: Binding(get: { data["autoCheck"].bool }, set: { value in Task { await model.prepare("crossplay.auto", ["enabled": .string(String(value))], summary: value ? "Automatische Crossplay-Prüfung aktivieren?" : "Automatische Crossplay-Prüfung ausschalten?") } })).font(.subheadline) }
            } else if data != .null { Text("Updates vormerken benötigt einen full-App-Zugang.").font(.caption).foregroundStyle(.secondary) }
            if !data["status"].string.isEmpty { Text("Suite: " + data["status"].string).font(.caption2).foregroundStyle(.secondary) }
        }.frame(maxWidth: .infinity, alignment: .leading).card()
            .task { await load() }
            .errorAlert($model.error)
            .sheet(isPresented: Binding(get: { model.plan != nil }, set: { if !$0 { model.plan = nil } })) { SuiteSecurityConfirmation(model: model) { try? await Task.sleep(for: .seconds(2)); await load() } }
    }
    func detail(_ row: JSON) -> String {
        guard row["installed"].bool else { return "Nicht als Plugin-JAR geladen" }
        var parts = ["Installiert: " + (row["version"].string.isEmpty ? "?" : row["version"].string)]
        if row["latestBuild"].int > 0 { parts.append("Neuester Build: " + row["latestVersion"].string + " #" + String(row["latestBuild"].int)) }
        return parts.joined(separator: " · ")
    }
    func load() async {
        loading = true; defer { loading = false }
        do { data = try await suite.data("crossplay"); error = nil } catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }
}

struct SuiteCrossplayView: View {
    let suite: SuiteAPI
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                SuiteCrossplayCard(suite: suite)
                Text("Der Status vergleicht die SHA-256-Prüfsumme der installierten JARs mit dem neuesten Build von download.geysermc.org. Updates landen sicher im Paper-Updateordner und werden beim nächsten Neustart übernommen; die laufende Version wird nie direkt ersetzt.").font(.footnote).foregroundStyle(.secondary)
            }.padding(20).frame(maxWidth: 900).frame(maxWidth: .infinity)
        }.panelScreen().navigationTitle("Geyser & Floodgate").navigationBarTitleDisplayMode(.inline)
    }
}
