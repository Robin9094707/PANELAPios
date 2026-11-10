import SwiftUI

// MARK: - 5.9.0 Disconnect-Forensik

/// Labels for the forensic fields of a disconnect. Older suites send none of them; every view hides itself then.
enum DisconnectForensicsPresentation {
    static func closeSide(_ side: String) -> String {
        switch side {
        case "remote": return "Von außen geschlossen"
        case "server": return "Vom Server geschlossen"
        case "exception": return "Netzwerkfehler"
        default: return ""
        }
    }
    static func stateTitle(_ state: String) -> String {
        switch state {
        case "ok": return "Stabil"
        case "unstable": return "Wackelig"
        case "congested": return "Puffer voll"
        case "stall": return "Stockt"
        default: return state
        }
    }
    static func stateTint(_ state: String) -> Color {
        switch state {
        case "ok": return .green
        case "unstable": return .orange
        default: return .red
        }
    }
    static func confidenceTint(_ value: Int) -> Color {
        if value >= 60 { return .red }
        if value >= 40 { return .orange }
        return .blue
    }
    /// Key facts of a wave in display order (title, value).
    static func patternRows(_ pattern: JSON) -> [(String, String)] {
        guard pattern != .null else { return [] }
        var rows: [(String, String)] = []
        rows.append(("Gleichzeitig (Java, 3 s)", "\(pattern["sync"].int)"))
        rows.append(("Nur Java betroffen", pattern["javaOnly"].bool ? "Ja – Bedrock blieb" : "Nein"))
        let known = pattern["rejoinKnown"].int
        rows.append(("Schnell zurück (≤ 3 min)", known > 0 ? "\(pattern["rejoined"].int) von \(known)" : "noch offen"))
        if pattern["traced"].int > 0 {
            rows.append(("Geschlossen außen/Server/Fehler", "\(pattern["remoteClose"].int) / \(pattern["serverClose"].int) / \(pattern["exceptionClose"].int)"))
            rows.append(("Client bis zuletzt aktiv", "\(pattern["activeUntilClose"].int)"))
            rows.append(("Client vorher eingefroren", "\(pattern["frozen"].int)"))
            rows.append(("TCP-Verlust", "\(pattern["retrans"].int)"))
        }
        rows.append(("Streuung Sitzungsdauer", "\(Int(pattern["sessionVariation"].double * 100)) %"))
        let periodicity = pattern["periodicity"]
        if periodicity["periodic"].bool {
            rows.append(("Regelmäßig", "alle ~\(periodicity["intervalMinutes"].int) min"))
        } else if periodicity["sameMinute"].bool {
            rows.append(("Regelmäßig", "immer um :" + String(format: "%02d", periodicity["minute"].int)))
        }
        return rows
    }
}

/// "Wahrscheinlichste Ursachen" with confidence bars.
struct SuiteDisconnectHypothesesSection: View {
    let verdict: JSON
    var body: some View {
        let rows = verdict["hypotheses"].array
        if !rows.isEmpty {
            Section {
                ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                    SuiteDisconnectHypothesisRow(row: row)
                }
            } header: { Text("Wahrscheinlichste Ursachen") } footer: { Text("Gewichtet aus Paketspur, Rückkehrzeit, Gleichzeitigkeit, Plattform, Sitzungsdauer und früheren Wellen.") }
        }
    }
}

struct SuiteDisconnectHypothesisRow: View {
    let row: JSON
    var body: some View {
        let confidence = row["confidence"].int
        let tint = DisconnectForensicsPresentation.confidenceTint(confidence)
        VStack(alignment: .leading, spacing: 5) {
            HStack(alignment: .firstTextBaseline) {
                Text(row["title"].string).font(.subheadline.weight(.semibold))
                Spacer(minLength: 8)
                Text("\(confidence) %").font(.caption.weight(.bold).monospacedDigit()).foregroundStyle(tint)
            }
            ProgressView(value: Double(confidence), total: 100).tint(tint)
            let why = row["why"].array.map(\.string).joined(separator: " · ")
            if !why.isEmpty { Text(why).font(.caption).foregroundStyle(.secondary) }
            if !row["step"].string.isEmpty { Label(row["step"].string, systemImage: "arrow.right.circle").font(.caption) }
        }
        .padding(.vertical, 3)
    }
}

struct SuiteDisconnectPatternSection: View {
    let verdict: JSON
    var body: some View {
        let rows = DisconnectForensicsPresentation.patternRows(verdict["patterns"])
        if !rows.isEmpty {
            Section("Muster der Welle") {
                if verdict["hidden"].bool {
                    Label("Als „Disconnected“ gemeldet, die Spuren zeigen aber einen unfreiwilligen Abbruch.", systemImage: "eye.trianglebadge.exclamationmark")
                        .font(.subheadline).foregroundStyle(.orange)
                }
                ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                    LabeledContent(row.0, value: row.1)
                }
            }
        }
    }
}

/// Network relevant server settings, plugins and optional plugins that deepen the analysis.
struct SuiteNetworkEnvironmentSection: View {
    let environment: JSON
    var body: some View {
        if environment != .null {
            Section {
                LabeledContent("Proxy", value: environment["proxy"].string)
                ForEach(Array(environment["checks"].array.enumerated()), id: \.offset) { _, check in
                    SuiteNetworkCheckRow(check: check)
                }
            } header: { Text("Server-Netzwerk") }
            let plugins = environment["plugins"].array
            if !plugins.isEmpty {
                Section("Plugins, die Verbindungen berühren") {
                    ForEach(Array(plugins.enumerated()), id: \.offset) { _, plugin in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(plugin["name"].string + " " + plugin["version"].string).font(.subheadline.weight(.semibold))
                            Text(plugin["note"].string).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
            }
            let recommended = environment["recommended"].array
            if !recommended.isEmpty {
                Section {
                    ForEach(Array(recommended.enumerated()), id: \.offset) { _, plugin in
                        VStack(alignment: .leading, spacing: 2) {
                            Label(plugin["name"].string, systemImage: "puzzlepiece.extension").font(.subheadline.weight(.semibold))
                            Text(plugin["why"].string).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                } header: { Text("Optional für noch tiefere Analysen") }
            }
        }
    }
}

struct SuiteNetworkCheckRow: View {
    let check: JSON
    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: SuiteDiagnostics.statusSymbol(check["status"].string)).foregroundStyle(SuiteDiagnostics.statusTint(check["status"].string))
            VStack(alignment: .leading, spacing: 2) {
                HStack {
                    Text(check["title"].string).font(.subheadline)
                    Spacer(minLength: 6)
                    Text(check["value"].string).font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                }
                Text(check["hint"].string).font(.caption).foregroundStyle(.secondary)
            }
        }
    }
}

/// Forensic details of one disconnect (signals, who closed, rejoin).
struct SuiteDisconnectForensicsDetail: View {
    let row: JSON
    var body: some View {
        let signals = row["signals"].array
        if !signals.isEmpty {
            DisclosureGroup("Spuren · \(signals.count)") {
                VStack(alignment: .leading, spacing: 4) {
                    ForEach(Array(signals.enumerated()), id: \.offset) { _, signal in
                        Text("• " + signal.string).font(.caption2).foregroundStyle(.secondary).frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
            }
            .font(.caption)
        }
    }
}

// MARK: - Live network

struct SuiteNetworkLiveView: View {
    let suite: SuiteAPI
    @State private var data: JSON = .null
    @State private var error: String?
    var body: some View {
        List {
            if data == .null {
                ProgressView("Verbindungen werden gemessen …")
            } else {
                if !data["hint"].string.isEmpty {
                    Section { Label(data["hint"].string, systemImage: "exclamationmark.triangle.fill").foregroundStyle(.red) }
                }
                Section {
                    if data["items"].array.isEmpty { Text("Niemand online.").foregroundStyle(.secondary) }
                    ForEach(data["items"].array, id: \.["uuid"].string) { row in
                        SuiteNetworkLiveRow(row: row)
                    }
                } header: { Text("\(data["online"].int) Verbindungen") } footer: {
                    Text("Aktualisiert alle 3 Sekunden. TCP-Werte kommen direkt vom Kernel (epoll). Es werden keine IP-Adressen gespeichert; „Netz“ ist ein anonymer Kurzwert für den Netzbereich.")
                }
                SuiteNetworkEnvironmentSection(environment: data["environment"])
            }
        }
        .panelScreen()
        .navigationTitle("Live-Netzwerk")
        .task { await loop() }
        .refreshable { await load() }
        .errorAlert($error)
    }
    func loop() async {
        while !Task.isCancelled {
            await load()
            try? await Task.sleep(for: .seconds(3))
        }
    }
    func load() async {
        do { data = try await suite.request("diagnostics/network") }
        catch { if !RequestFailure.isCancellation(error) && data == .null { self.error = error.localizedDescription } }
    }
}

struct SuiteNetworkLiveRow: View {
    let row: JSON
    var detail: String {
        var parts: [String] = ["Ping \(row["ping"].int) ms"]
        if row["rtt"].int > 0 { parts.append("RTT \(row["rtt"].int) ms") }
        if row["traced"].bool {
            parts.append("\(row["retrans"].int) Neuübertr.")
            if row["lost"].int > 0 { parts.append("\(row["lost"].int) verloren") }
            parts.append(bytes(row["rateOutBps"].double) + "/s raus")
        }
        return parts.joined(separator: " · ")
    }
    var client: String {
        let c = row["client"]
        return [c["brandLabel"].string, c["version"].string].filter { !$0.isEmpty }.joined(separator: " · ")
    }
    var body: some View {
        let state = row["state"].string
        VStack(alignment: .leading, spacing: 3) {
            HStack(spacing: 6) {
                Text(row["name"].string).font(.subheadline.weight(.semibold))
                Text(row["platform"].string == "bedrock" ? "Bedrock" : "Java").font(.caption2).foregroundStyle(.secondary)
                Spacer()
                Text(DisconnectForensicsPresentation.stateTitle(state)).font(.caption2.weight(.bold))
                    .padding(.horizontal, 6).padding(.vertical, 2)
                    .background(DisconnectForensicsPresentation.stateTint(state).opacity(0.16), in: Capsule())
                    .foregroundStyle(DisconnectForensicsPresentation.stateTint(state))
            }
            Text(detail).font(.caption.monospacedDigit()).foregroundStyle(.secondary)
            HStack(spacing: 10) {
                if row["lastInboundAgeMs"].int >= 0 { Text("Letztes Paket vor " + SuiteDiagnostics.duration(row["lastInboundAgeMs"].double)) }
                if row["connectionAgeMs"].double > 0 { Text("verbunden " + SuiteDiagnostics.duration(row["connectionAgeMs"].double)) }
            }.font(.caption2).foregroundStyle(.secondary)
            if !client.isEmpty || !row["networkGroup"].string.isEmpty {
                HStack(spacing: 10) {
                    if !client.isEmpty { Label(client, systemImage: "desktopcomputer") }
                    if !row["networkGroup"].string.isEmpty {
                        Label(row["networkGroup"].string + (row["groupSize"].int > 1 ? " ×\(row["groupSize"].int)" : ""), systemImage: "network")
                    }
                }.font(.caption2).foregroundStyle(.secondary)
            }
        }
    }
}

// MARK: - 5.9.0 PowerBot escalation

enum PowerBotEscalationPresentation {
    static let stages = ["Beruhigt", "Verwarnt", "Kurze Chatpause", "Tiefenprüfung"]
    static func remaining(_ millis: Double) -> String {
        let seconds = max(0, Int(millis / 1000))
        if seconds >= 60 { return "\(seconds / 60):" + String(format: "%02d", seconds % 60) + " min" }
        return "\(seconds) s"
    }
    static func analysis(_ kind: String) -> String {
        switch kind {
        case "none": return "ruhig"
        case "answer": return "Antwort"
        case "conflict": return "Streit"
        case "help": return "Hilferuf"
        case "provocation": return "Pöbeln"
        case "failed": return "nicht erreichbar"
        default: return kind
        }
    }
}

/// Running disputes with their ladder position and active chat pauses with a countdown and an early lift.
struct PowerBotLiveSections: View {
    let data: JSON
    let change: (String, [String: JSON]) async -> Void
    @State private var confirmLift: JSON?
    var body: some View {
        let disputes = data["disputes"].array.filter { $0["live"].bool }
        let mutes = data["activeMutes"].array
        if !disputes.isEmpty {
            Section {
                ForEach(disputes, id: \.["id"].string) { dispute in
                    PowerBotDisputeRow(dispute: dispute, manage: data["manage"].bool, change: change)
                }
            } header: { Text("Laufende Streits") } footer: { Text("PowerBot schlichtet stufenweise: beruhigen → warnen → kurze Chatpause → Tiefenprüfung mit mehr Kontext.") }
        }
        if !mutes.isEmpty {
            Section {
                ForEach(mutes, id: \.["id"].string) { mute in
                    PowerBotActiveMuteRow(mute: mute, manage: data["manage"].bool) { confirmLift = mute }
                }
            } header: { Text("Aktive Chatpausen · \(mutes.count)") } footer: { Text("Pausen können jederzeit vorzeitig aufgehoben werden; der Spieler wird im Chat informiert.") }
            .confirmationDialog("Chatpause aufheben?", isPresented: Binding(get: { confirmLift != nil }, set: { if !$0 { confirmLift = nil } }), titleVisibility: .visible) {
                Button("Aufheben", role: .destructive) {
                    if let mute = confirmLift { Task { await change("revoke", ["id": mute["id"]]) } }
                }
                Button("Abbrechen", role: .cancel) {}
            } message: { Text((confirmLift?["name"].string ?? "") + " kann danach sofort wieder schreiben.") }
        }
        let last = data["escalation"]["lastAnalysis"]
        if last["at"].double > 0 {
            Section {
                LabeledContent("Letzte Kontextanalyse", value: SuiteDiagnostics.ago(last["at"].double))
                LabeledContent("Ergebnis", value: PowerBotEscalationPresentation.analysis(last["kind"].string) + " · \(last["lines"].int) Nachrichten")
                LabeledContent("Rhythmus", value: "alle \(max(5, data["escalation"]["analysisInterval"].int)) Nachrichten · Pöbeln sofort")
            }
        }
    }
}

struct PowerBotDisputeRow: View {
    let dispute: JSON
    let manage: Bool
    let change: (String, [String: JSON]) async -> Void
    var people: String {
        dispute["players"].array.map { $0["name"].string + ($0["involved"].bool ? " ●" : "") }.joined(separator: ", ")
    }
    var body: some View {
        let stage = dispute["stage"].int
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Image(systemName: dispute["kind"].string == "provocation" ? "megaphone.fill" : "flame.fill").foregroundStyle(.orange)
                Text(dispute["kind"].string == "provocation" ? "Pöbeln" : "Streit").font(.headline)
                if !dispute["severity"].string.isEmpty {
                    PowerBotTag(text: PowerBotPresentation.severity(dispute["severity"].string), tint: PowerBotPresentation.severityTint(dispute["severity"].string))
                }
                Spacer(minLength: 0)
                if let date = SuitePlayerPresentation.date(dispute["started"]) { Text(SuiteRelativeTime.text(date)).font(.caption2).foregroundStyle(.secondary) }
            }
            if !people.isEmpty { Text(people).font(.caption.weight(.medium)).foregroundStyle(.secondary) }
            PowerBotLadder(stage: stage)
            if !dispute["reason"].string.isEmpty { Text(dispute["reason"].string).font(.callout) }
            Text(dispute["next"].string).font(.caption).foregroundStyle(.secondary)
            if manage {
                HStack {
                    Button("Beenden") { Task { await change("dispute-dismiss", ["id": dispute["id"]]) } }
                    Button("Beenden & Pausen aufheben") { Task { await change("dispute-dismiss", ["id": dispute["id"], "lift": .bool(true)]) } }
                }
                .buttonStyle(.bordered).controlSize(.small)
            }
        }
        .padding(.vertical, 4)
    }
}

/// Four steps of the escalation ladder; done steps are filled.
struct PowerBotLadder: View {
    let stage: Int
    var body: some View {
        HStack(spacing: 4) {
            ForEach(Array(PowerBotEscalationPresentation.stages.enumerated()), id: \.offset) { index, title in
                let step = index + 1
                VStack(spacing: 3) {
                    Capsule().fill(step <= stage ? (step >= 3 ? Color.red : Color.orange) : Color.secondary.opacity(0.2)).frame(height: 5)
                    Text(title).font(.system(size: 9, weight: step == stage ? .bold : .regular)).foregroundStyle(step <= stage ? Color.primary : Color.secondary).lineLimit(1).minimumScaleFactor(0.7)
                }
                .frame(maxWidth: .infinity)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Stufe \(stage) von 4: " + (stage > 0 && stage <= 4 ? PowerBotEscalationPresentation.stages[stage - 1] : "beobachtet"))
    }
}

struct PowerBotActiveMuteRow: View {
    let mute: JSON
    let manage: Bool
    let lift: () -> Void
    var body: some View {
        HStack(spacing: 12) {
            SuitePlayerAvatar(row: .object(["id": mute["player"]]), size: 34)
            VStack(alignment: .leading, spacing: 3) {
                Text(mute["name"].string.isEmpty ? "Unbekannt" : mute["name"].string).font(.subheadline.weight(.semibold))
                let reason = mute["reason"].string.isEmpty ? mute["detail"].string : mute["reason"].string
                if !reason.isEmpty { Text(reason).font(.caption).foregroundStyle(.secondary).lineLimit(2) }
                if let until = SuitePlayerPresentation.date(mute["until"]) {
                    Text(timerInterval: Date()...max(Date(), until), countsDown: true).font(.caption.monospacedDigit()).foregroundStyle(.red)
                }
            }
            Spacer(minLength: 0)
            if manage {
                Button("Aufheben", action: lift).buttonStyle(.bordered).controlSize(.small).tint(.red)
            }
        }
    }
}

/// Settings for the ladder (5.9.0 suites only).
struct PowerBotEscalationSettingsSection: View {
    @Binding var interval: Int
    @Binding var pauseSeconds: Int
    @Binding var longMinutes: Int
    var pauseLabel: String {
        if pauseSeconds >= 60 && pauseSeconds % 60 == 0 { return "Kurze Chatpause: \(pauseSeconds / 60) Min." }
        return "Kurze Chatpause: \(pauseSeconds) Sek."
    }
    var body: some View {
        Section {
            Stepper("Kontextanalyse alle \(interval) Nachrichten", value: $interval, in: 5...50)
            Stepper(pauseLabel, value: $pauseSeconds, in: 30...600, step: 30)
            Stepper("Nach Tiefenprüfung: \(longMinutes) Min.", value: $longMinutes, in: 1...30)
        } header: { Text("Eskalationsleiter") } footer: {
            Text("Beruhigen → öffentlich und persönlich warnen → kurze Chatpause für die Beteiligten → Tiefenprüfung mit mehr Kontext und längerer Pause für wer unnötig eskaliert. Pöbeln wird sofort geprüft. Opfer und Unbeteiligte werden nie stummgeschaltet.")
        }
    }
}
