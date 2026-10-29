import SwiftUI

enum SuiteSecurityArea: String, CaseIterable {
    case events, bans, vpn, reports
    var title: String { switch self { case .events: return "Ereignisse"; case .bans: return "Banns & Freigaben"; case .vpn: return "VPN-Schutz"; case .reports: return "Reports" } }
    var symbol: String { switch self { case .events: return "bell.badge.fill"; case .bans: return "hand.raised.fill"; case .vpn: return "network"; case .reports: return "flag.fill" } }
}

@MainActor final class SuiteSecurityModel: ObservableObject {
    @Published var rows: [JSON] = []
    @Published var busy = false
    @Published var error: String?
    @Published var plan: JSON?
    @Published var result: JSON?
    @Published var message = ""
    @Published var next = -1
    let suite: SuiteAPI
    private var generation = UUID()
    init(_ suite: SuiteAPI) { self.suite = suite }
    func load(_ area: SuiteSecurityArea, source: String, query: String, more: Bool = false) async {
        let revision = UUID(); generation = revision
        busy = true
        defer { if generation == revision { busy = false } }
        do {
            if !more { try await Task.sleep(for: .milliseconds(220)) }
            let endpoint: String
            switch area { case .events: endpoint = "events"; case .bans: endpoint = source == "litebans" ? "litebans" : "bans"; case .vpn: endpoint = source; case .reports: endpoint = "reports" }
            let data = try await suite.data(endpoint, query: ["q": query, "source": source, "offset": String(more ? max(0, next) : 0)])
            try Task.checkCancellation(); guard generation == revision else { return }
            if more { let existing = Set(rows.map { $0["id"].string }); rows += data["items"].array.filter { !existing.contains($0["id"].string) } }
            else { rows = data["items"].array }
            next = data["next"] == .null ? -1 : data["next"].int
            message = data["message"].string; error = nil
        } catch { if generation == revision && !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }
    func prepare(_ path: String, _ values: [String: JSON], summary: String) async {
        guard !busy else { return }; busy = true; defer { busy = false }
        do { var prepared = try await suite.prepare(path, values: values); if case .object(var object) = prepared { object["summary"] = .string(summary); prepared = .object(object) }; plan = prepared }
        catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }
    func execute() async -> Bool {
        guard let plan, !busy else { return false }; busy = true; defer { busy = false }
        do { let response = try await suite.execute(plan["ticket"].string); self.plan = nil; result = response; guard response["ok"].bool else { error = response["description"].string; return false }; Feedback.success(); return true }
        catch { self.plan = nil; if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription }; return false }
    }
}

struct SuiteSecurityCenterView: View {
    let suite: SuiteAPI
    let panel: PanelAPI
    let server: Server
    let actions: [SuiteAction]
    let area: SuiteSecurityArea
    @StateObject private var model: SuiteSecurityModel
    @State private var query = ""
    @State private var source: String
    @State private var addVPN = false
    @State private var bark = false
    init(suite: SuiteAPI, panel: PanelAPI, server: Server, actions: [SuiteAction], area: SuiteSecurityArea) {
        self.suite = suite; self.panel = panel; self.server = server; self.actions = actions; self.area = area
        _model = StateObject(wrappedValue: SuiteSecurityModel(suite))
        _source = State(initialValue: area == .vpn ? "vpn-whitelist" : "internal")
    }
    var body: some View {
        List {
            Section {
                Label(area.title, systemImage: area.symbol).font(.title2.bold()).foregroundStyle(.teal)
                Text(subtitle).font(.footnote).foregroundStyle(.secondary)
                if area == .bans {
                    Picker("Bannquelle", selection: $source) { Text("Admin Suite").tag("internal"); Text("Client-Prüfung").tag("client"); Text("Minecraft").tag("minecraft"); Text("LiteBans").tag("litebans") }
                    NavigationLink { SuiteUltraView(suite: suite, actions: actions) } label: { Label("Ultrabanns & Identitätsgruppen", systemImage: "person.2.slash") }
                }
                if area == .vpn { NavigationLink("Geprüfte Plattformen & IP-Diagnose") { SuiteVPNPlatformView(suite: suite) }; Picker("Bereich", selection: $source) { Text("Whitelist").tag("vpn-whitelist"); Text("Letzte Kicks").tag("vpn-kicks") }.pickerStyle(.segmented) }
                if area == .events { Button("Bark & Alarmtöne", systemImage: "bell.and.waves.left.and.right") { bark = true }.disabled(suite.scope != "full") }
            }
            if !model.message.isEmpty { Section { Text(model.message).foregroundStyle(.secondary) } }
            Section(model.rows.isEmpty ? "" : "\(model.rows.count) Einträge geladen") {
                ForEach(model.rows, id: \.["id"].string) { row in
                    NavigationLink {
                        SuiteSecurityDetailView(suite: suite, panel: panel, server: server, actions: actions, area: area, source: source, row: row) { await reload() }
                    } label: { securityRow(row) }
                }
                if model.rows.isEmpty && !model.busy { ContentUnavailableView(query.isEmpty ? "Keine Einträge" : "Keine Treffer", systemImage: area.symbol, description: Text(query.isEmpty ? "Neue Ereignisse erscheinen nach dem Plugin-Update hier." : "Suche nach Name, UUID oder Grund.")) }
                if model.busy { ProgressView("Wird geladen …") }
                if model.next >= 0 && !model.busy { Button("Weitere Einträge laden") { Task { await model.load(area, source: source, query: query, more: true) } } }
            }
            if area == .events && suite.scope == "full" && !model.rows.isEmpty {
                Section { Button("Ereignisprotokoll leeren", role: .destructive) { Task { await model.prepare("events.clear", [:], summary: "Alle Einträge des neuen Ereignisbereichs löschen? Banns und Reportakten bleiben bestehen.") } } }
            }
        }.panelScreen().navigationTitle(area.title).navigationBarTitleDisplayMode(.inline)
            .searchable(text: $query, prompt: "Name, UUID oder Ereignis suchen")
            .task(id: source + "\n" + query) { await reload() }.refreshable { await reload() }.errorAlert($model.error)
            .toolbar { if area == .vpn { ToolbarItem(placement: .topBarTrailing) { Button("VPN-Ausnahme hinzufügen", systemImage: "plus") { addVPN = true }.disabled(suite.scope == "read") } } }
            .sheet(isPresented: $addVPN, onDismiss: { Task { await reload() } }) { NavigationStack { SuiteVPNAddView(suite: suite) } }
            .sheet(isPresented: $bark) { NavigationStack { SuiteBarkSettingsView(suite: suite) } }
            .sheet(isPresented: Binding(get: { model.plan != nil }, set: { if !$0 { model.plan = nil } })) { SuiteSecurityConfirmation(model: model) { await reload() } }
    }
    var subtitle: String {
        switch area {
        case .events: return "Servermeldungen, neue Spieler und Sicherheitsereignisse. Ziehe zum Aktualisieren nach unten."
        case .bans: return "Jede Bannquelle wird separat gelesen und freigegeben. Gruppen findest du unter Ultrabanns."
        case .vpn: return "Ausnahmen gelten sofort. Letzte Kicks zeigen blockierte Verbindungen aus den jüngsten 200 VPN-Prüfungen."
        case .reports: return "Meldungen prüfen, Status ändern und interne Notizen ergänzen."
        }
    }
    func reload() async { await model.load(area, source: source, query: query) }
    func securityRow(_ row: JSON) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: area == .events ? (row["critical"].bool ? "exclamationmark.shield.fill" : "bell.fill") : area.symbol).foregroundStyle(row["critical"].bool ? .orange : .teal).font(.title3).frame(width: 26)
            VStack(alignment: .leading, spacing: 5) {
                Text(area == .events ? row["title"].string : row["name"].string).font(.headline).lineLimit(2)
                Text(area == .events ? row["body"].string : area == .reports ? row["category"].string + " · " + reportStatus(row["status"].string) : row["reason"].string.isEmpty ? "VPN-Ausnahme aktiv" : row["reason"].string).font(.subheadline).foregroundStyle(.secondary).lineLimit(2)
                if let date = SuitePlayerPresentation.date(row["time"] == .null ? row["created"] : row["time"]) { Text(date, format: .dateTime.day().month().hour().minute()).font(.caption).foregroundStyle(.secondary) }
            }
        }.padding(.vertical, 3)
    }
}

private func reportStatus(_ value: String) -> String {
    switch value { case "OPEN": return "Offen"; case "IN_REVIEW": return "In Bearbeitung"; case "RESOLVED": return "Erledigt"; case "REJECTED": return "Abgelehnt"; default: return value }
}

struct SuiteSecurityDetailView: View {
    let suite: SuiteAPI
    let panel: PanelAPI
    let server: Server
    let actions: [SuiteAction]
    let area: SuiteSecurityArea
    let source: String
    let row: JSON
    let updated: () async -> Void
    @StateObject private var model: SuiteSecurityModel
    @State private var note: String
    @State private var status: String
    @Environment(\.dismiss) private var dismiss
    init(suite: SuiteAPI, panel: PanelAPI, server: Server, actions: [SuiteAction], area: SuiteSecurityArea, source: String, row: JSON, updated: @escaping () async -> Void) {
        self.suite = suite; self.panel = panel; self.server = server; self.actions = actions; self.area = area; self.source = source; self.row = row; self.updated = updated
        _model = StateObject(wrappedValue: SuiteSecurityModel(suite)); _note = State(initialValue: area == .reports ? "" : row["note"].string); _status = State(initialValue: row["status"].string)
    }
    var body: some View {
        List {
            Section {
                Label(area == .events ? row["title"].string : row["name"].string, systemImage: area.symbol).font(.title2.bold())
                if !row["player"].string.isEmpty { NavigationLink { SuitePlayerView(suite: suite, panel: panel, server: server, player: row["player"].string, actions: actions) } label: { Label("Spielerprofil öffnen", systemImage: "person.crop.circle") } }
                ForEach(fields, id: \.0) { field in if !row[field.0].string.isEmpty { SuiteDetailRow(title: field.1, value: row[field.0].string) } }
                if let date = SuitePlayerPresentation.date(row["time"] == .null ? row["created"] : row["time"]) { LabeledContent("Zeitpunkt") { Text(date, format: .dateTime.day().month().year().hour().minute().second()) } }
                if area == .bans { LabeledContent("Gültig bis", value: expiry) }
            }
            if area == .reports {
                Section("Bearbeitung") {
                    Picker("Status", selection: $status) { ForEach(["OPEN","IN_REVIEW","RESOLVED","REJECTED"], id: \.self) { Text(reportStatus($0)).tag($0) } }
                    TextField("Interne Notiz ergänzen", text: $note, axis: .vertical).lineLimit(3...8)
                    Button("Report speichern", systemImage: "checkmark.circle") { prepare("reports.update", ["id": row["id"], "status": .string(status), "expected": row["revision"], "note": .string(note)], "Report #\(row["id"].string): \(reportStatus(status))\n\(note)") }.disabled(suite.scope == "read" || model.busy)
                }
                if case .object(let evidence) = row["evidence"] { ForEach(evidence.keys.sorted(), id: \.self) { key in Section(key == "chat" ? "Chatbelege" : key == "combat" ? "Kampfbelege" : "Aktionen") { ForEach(Array((evidence[key]?.array ?? []).enumerated()), id: \.offset) { _, line in NavigationLink { ScrollView { Text(cleanConsole(line.string)).textSelection(.enabled).padding() }.navigationTitle("Beleg") } label: { Text(cleanConsole(line.string)).font(.footnote).lineLimit(1) } } } } }
            }
            if area == .events && suite.scope == "full" {
                Section { Button("Alarm bestätigen – Wiederholung beenden", systemImage:"bell.slash") { prepare("events.ack",["id":row["id"]],"Diesen Alarm für diese Kopplung bestätigen und Wiederholungen beenden?") } }
                Section("Notiz") { TextField("Notiz zum Ereignis", text: $note, axis: .vertical).lineLimit(3...8); Button("Notiz speichern") { prepare("events.note", ["id": row["id"], "note": .string(note)], "Notiz zu diesem Ereignis speichern?\n" + note) } }
                Section { Button("Diesen Eintrag löschen", role: .destructive) { prepare("events.delete", ["id": row["id"]], "Diesen Ereigniseintrag löschen?") } }
            }
            if area == .bans {
                Section { Button("Diese Sperre aufheben", systemImage: "lock.open.fill", role: .destructive) { prepare("bans.remove", ["source": row["source"], "id": row["id"], "expected": row["revision"]], "Quelle: \(row["source"].string)\nZiel: \(row["name"].string)\nGrund: \(row["reason"].string)\nNur diese Sperre aufheben?") }.disabled(suite.scope == "read" || model.busy) }
            }
            if area == .vpn && source == "vpn-kicks" && !row["player"].string.isEmpty {
                Section { Button("VPN-Ausnahme für diesen Spieler", systemImage: "checkmark.shield") { prepare("vpn.whitelist", ["player": row["player"], "allowed": .string("true")], "VPN-Ausnahme für \(row["name"].string) aktivieren?\nDie Freigabe gilt für diese genaue Spieler-UUID.") }.disabled(suite.scope == "read" || model.busy) }
            }
            if area == .vpn && source == "vpn-whitelist" {
                Section { Button("VPN-Ausnahme entfernen", role: .destructive) { prepare("vpn.whitelist", ["player": row["player"], "allowed": .string("false")], "VPN-Ausnahme für \(row["name"].string) entfernen?") }.disabled(suite.scope == "read" || model.busy) }
            }
        }.panelScreen().navigationTitle("Details").navigationBarTitleDisplayMode(.inline).errorAlert($model.error)
            .sheet(isPresented: Binding(get: { model.plan != nil }, set: { if !$0 { model.plan = nil } })) { SuiteSecurityConfirmation(model: model) { await updated(); dismiss() } }
    }
    var fields: [(String,String)] { [("source","Quelle"),("body","Ereignis"),("reason","Grund"),("reporter","Gemeldet von"),("category","Kategorie"),("details","Beschreibung"),("note","Gespeicherte Notiz"),("actor","Ausgeführt von"),("handledBy","Bearbeitet von"),("scope","LiteBans-Serverbereich"),("country","Land"),("risk","VPN-Risiko"),("id","Eintrags-ID")] }
    var expiry: String { let value = row["until"].double; if value <= 0 || value > 253402300799000 { return source == "client" ? "Bis zur Team-Freigabe" : "Dauerhaft" }; return Date(timeIntervalSince1970: value / 1000).formatted(date: .numeric, time: .shortened) }
    func prepare(_ path: String, _ values: [String: JSON], _ text: String) { Task { await model.prepare(path, values, summary: text) } }
}

struct SuiteSecurityConfirmation: View {
    @ObservedObject var model: SuiteSecurityModel
    let completed: () async -> Void
    var body: some View {
        NavigationStack { List {
            Section { Text(model.plan?["summary"].string ?? "").textSelection(.enabled); Text("Die Bestätigung gilt 60 Sekunden. Bei einem Verbindungsfehler den aktuellen Zustand prüfen.").font(.footnote).foregroundStyle(.secondary) }
            Section { Button("Bestätigen & anwenden") { Task { if await model.execute() { await completed() } } }.disabled(model.busy); if model.busy { ProgressView() } }
        }.panelScreen().navigationTitle("Bestätigen").toolbar { ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { model.plan = nil }.disabled(model.busy) } } }.interactiveDismissDisabled(model.busy)
    }
}

struct SuiteVPNAddView: View {
    let suite: SuiteAPI
    @State private var query = ""
    @State private var players: [JSON] = []
    @StateObject private var model: SuiteSecurityModel
    @Environment(\.dismiss) private var dismiss
    init(suite: SuiteAPI) { self.suite = suite; _model = StateObject(wrappedValue: SuiteSecurityModel(suite)) }
    var body: some View {
        List { Section { Text("Wähle ein gespeichertes Spielerprofil. Die VPN-Ausnahme wird sofort wirksam.").font(.footnote).foregroundStyle(.secondary) }; ForEach(players, id: \.["id"].string) { row in Button { Task { await model.prepare("vpn.whitelist", ["player": row["id"], "allowed": .string("true")], summary: "VPN-Ausnahme für \(row["name"].string) aktivieren?\n\(row["id"].string)") } } label: { VStack(alignment: .leading) { Text(row["name"].string); Text(row["id"].string).font(.caption.monospaced()).foregroundStyle(.secondary) } }.disabled(model.busy) } }
            .panelScreen().navigationTitle("VPN-Ausnahme hinzufügen").searchable(text: $query, prompt: "Spielername oder UUID").errorAlert($model.error)
            .task(id: query) { do { try await Task.sleep(for: .milliseconds(250)); let result = try await suite.data("players", query: ["q": query]); try Task.checkCancellation(); players = result["items"].array } catch { if !RequestFailure.isCancellation(error) { model.error = error.localizedDescription } } }
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Schließen") { dismiss() } } }
            .sheet(isPresented: Binding(get: { model.plan != nil }, set: { if !$0 { model.plan = nil } })) { SuiteSecurityConfirmation(model: model) { dismiss() } }
    }
}

struct SuiteBarkSettingsView: View {
    let suite: SuiteAPI
    @StateObject private var model: SuiteSecurityModel
    @State private var key = ""
    @State private var configured = false
    @State private var enabled = false
    @State private var selected: Set<String> = []
    @State private var level = "active"
    @State private var critical = "critical"
    @State private var sound = "minuet"
    @State private var criticalSound = "alarm"
    @State private var delivery = ""
    @State private var rules: [String:String] = [:]
    @State private var sounds: [String:String] = [:]
    @State private var repeats: Set<String> = []
    @State private var quietMode="off"
    @State private var quietStart="22:00"
    @State private var quietEnd="07:00"
    @State private var timezone="Europe/Berlin"
    @State private var quietKinds: Set<String> = []
    var encodedRules: String { events.map { event in let id=event.0;return [id,selected.contains(id) ? (rules[id] ?? (Self.criticalKinds.contains(id) ? critical:level)):"off",sounds[id] ?? (Self.criticalKinds.contains(id) ? criticalSound:sound),repeats.contains(id) ? "120":"0"].joined(separator:":") }.joined(separator:";") }
    @Environment(\.dismiss) private var dismiss
    init(suite: SuiteAPI) { self.suite = suite; _model = StateObject(wrappedValue: SuiteSecurityModel(suite)) }
    static let criticalKinds: Set<String> = ["ban", "ban-evasion", "overload", "ticket-urgent"]
    static let ticketEvents = [("ticket","Neue Support-Tickets"),("ticket-urgent","Notfall-Tickets")]
    private let baseEvents = [("first-join","Neue Spieler"),("join","Alle Beitritte"),("client-brand","Ungewöhnlicher Client-Brand"),("client-check","Client-Prüfungen"),("ban","Sperren"),("ban-evasion","Bannumgehungsversuche"),("vpn-kick","VPN-Kicks"),("report","Neue Reports"),("overload","Längere Serverüberlastung"),("guard","Spielerschutz korrigiert"),("admin","Admin-Aktionen")]
    /// Ticket events exist from Admin Suite 9.4.0; older suites reject unknown rule types.
    private var events: [(String, String)] { suite.features.contains("tickets-v1") ? baseEvents + Self.ticketEvents : baseEvents }
    var body: some View {
        Form {
            Section("Bark verbinden") {
                Toggle("Benachrichtigungen aktiv", isOn: $enabled)
                SecureField(configured ? "Neuer Bark-Schlüssel (leer = behalten)" : "Bark-Schlüssel oder Push-Adresse", text: $key).textInputAutocapitalization(.never).autocorrectionDisabled()
                if configured { Label("Schlüssel sicher auf dem Server hinterlegt", systemImage: "checkmark.shield.fill").font(.footnote).foregroundStyle(.green) }
                Text("Kopiere die Push-Adresse aus Bark. Das Plugin nutzt den öffentlichen Bark-Dienst und sendet auch bei geschlossener Panel-App. Nachrichteninhalt wird dabei an Bark und Apple übertragen.").font(.footnote).foregroundStyle(.secondary)
            }
            Section("Ereignisse einzeln konfigurieren") {
                ForEach(events,id:\.0){event in NavigationLink(event.1) { Form {
                    Toggle("Abonniert",isOn:Binding(get:{selected.contains(event.0)},set:{if $0{selected.insert(event.0)}else{selected.remove(event.0)}}))
                    interruption("Dringlichkeit",selection:Binding(get:{rules[event.0] ?? (Self.criticalKinds.contains(event.0) ? critical:level)},set:{rules[event.0]=$0}))
                    Picker("Klang",selection:Binding(get:{sounds[event.0] ?? (Self.criticalKinds.contains(event.0) ? criticalSound:sound)},set:{sounds[event.0]=$0})){ForEach(Array(Set(["minuet","alarm","bell",sounds[event.0] ?? (Self.criticalKinds.contains(event.0) ? criticalSound:sound)])).sorted(),id:\.self){Text($0).tag($0)}};TextField("Eigener Bark-Klang",text:Binding(get:{sounds[event.0] ?? (Self.criticalKinds.contains(event.0) ? criticalSound:sound)},set:{sounds[event.0]=$0})).textInputAutocapitalization(.never)
                    Toggle("Alle 2 Minuten bis Bestätigung wiederholen",isOn:Binding(get:{repeats.contains(event.0)},set:{if $0{repeats.insert(event.0)}else{repeats.remove(event.0)}}))
                    Text("Bestätigen in der Ereignisglocke beim jeweiligen Ereignis. Bark- oder iOS-Einstellungen können die Zustellung begrenzen.").font(.footnote).foregroundStyle(.secondary)
                }.navigationTitle(event.1) } }
            }
            Section("Ruhezeiten") {
                Picker("Während der Ruhezeit",selection:$quietMode){Text("Keine Ruhezeit").tag("off");Text("Alles stumm").tag("all");Text("Nur kritische Hinweise").tag("critical");Text("Nur ausgewählte kritische Hinweise").tag("selected")}
                TextField("Beginn HH:mm",text:$quietStart);TextField("Ende HH:mm",text:$quietEnd);TextField("IANA-Zeitzone",text:$timezone).textInputAutocapitalization(.never)
                if quietMode=="selected" {ForEach(events,id:\.0){event in Toggle(event.1,isOn:Binding(get:{quietKinds.contains(event.0)},set:{if $0{quietKinds.insert(event.0)}else{quietKinds.remove(event.0)}}))}}
            }
            Section("Töne & Dringlichkeit") {
                interruption("Normale Hinweise", selection: $level)
                interruption("Kritische Ereignisse", selection: $critical)
                TextField("Normaler Klang", text: $sound).textInputAutocapitalization(.never)
                TextField("Alarmklang", text: $criticalSound).textInputAutocapitalization(.never)
                Text("Bark-Klangnamen, z. B. minuet, alarm oder bell. Eigene Bark-Klänge sind möglich. Stille Hinweise: „Nur Liste“. Kritische Töne benötigen die entsprechende Erlaubnis in Bark und iOS. Wiederholte gleichartige Meldungen werden begrenzt.").font(.footnote).foregroundStyle(.secondary)
            }
            Section { Button("Einstellungen speichern", systemImage: "checkmark.shield") { Task { await model.prepare("notifications.update", ["key": .string(key), "enabled": .string(String(enabled)), "events": .string(selected.sorted().joined(separator: ",")), "level": .string(level), "critical": .string(critical), "sound": .string(sound), "criticalSound": .string(criticalSound),"rules":.string(encodedRules),"quietMode":.string(quietMode),"quietStart":.string(quietStart),"quietEnd":.string(quietEnd),"timezone":.string(timezone),"quietKinds":.string(quietKinds.sorted().joined(separator:","))], summary: enabled ? "Bark für \(selected.count) Ereignisarten aktivieren?\nNormale Hinweise: \(level)\nKritische Ereignisse: \(critical)" : "Bark-Benachrichtigungen deaktivieren?") } }.disabled(model.busy || suite.scope != "full"); if !delivery.isEmpty { Text(delivery).font(.footnote).foregroundStyle(.secondary) } }
        }.panelScreen().navigationTitle("Benachrichtigungen").navigationBarTitleDisplayMode(.inline).errorAlert($model.error)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Schließen") { dismiss() } } }
            .task { do { let value = try await suite.request("notifications"); configured = value["configured"].bool; enabled = value["enabled"].bool; selected = Set(value["events"].string.split(separator: ",").map(String.init)); level = value["level"].string; critical = value["critical"].string; sound = value["sound"].string; criticalSound = value["criticalSound"].string; delivery = value["deliveryStatus"].string; quietMode=value["quietMode"].string.isEmpty ? "off":value["quietMode"].string;quietStart=value["quietStart"].string.isEmpty ? "22:00":value["quietStart"].string;quietEnd=value["quietEnd"].string.isEmpty ? "07:00":value["quietEnd"].string;timezone=value["timezone"].string.isEmpty ? "Europe/Berlin":value["timezone"].string;quietKinds=Set(value["quietKinds"].string.split(separator:",").map(String.init));for rule in value["rules"].string.split(separator:";"){let a=rule.split(separator:":",omittingEmptySubsequences:false).map(String.init);if a.count==4 {if a[1] != "off"{rules[a[0]]=a[1]};sounds[a[0]]=a[2];if a[1]=="off"{selected.remove(a[0])}else{selected.insert(a[0])};if a[3] != "0"{repeats.insert(a[0])}}}; if suite.features.contains("tickets-v1") { for kind in Self.ticketEvents.map(\.0) where !value["rules"].string.contains(kind + ":") { selected.insert(kind) } } } catch { model.error = error.localizedDescription } }
            .sheet(isPresented: Binding(get: { model.plan != nil }, set: { if !$0 { model.plan = nil } })) { SuiteSecurityConfirmation(model: model) { key = ""; configured = true; dismiss() } }
    }
    func interruption(_ title: String, selection: Binding<String>) -> some View { Picker(title, selection: selection) { Text("Normal").tag("active"); Text("Nur Liste (still)").tag("passive"); Text("Zeitkritisch").tag("timeSensitive"); Text("Kritischer Alarm").tag("critical") } }
}

struct SuiteEventInboxView: View {
    /// Bark ticket notifications open the same server picker, leading to the ticket list.
    var tickets = false
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        List {
            if let panel = store.api {
                ForEach(store.servers.filter { SuiteConnections.shared.get(panel, $0) != nil }) { server in
                    NavigationLink(server.name) { SuiteEventConnectedView(panel: panel, server: server, tickets: tickets) }
                }
                if !store.servers.contains(where: { SuiteConnections.shared.get(panel, $0) != nil }) { Text("Für das ausgewählte Panel ist noch keine Admin Suite verbunden.").foregroundStyle(.secondary) }
            } else { Text("Öffne deinen gespeicherten Panel-Zugang, um die Serverereignisse anzuzeigen.") }
        }.panelScreen().navigationTitle(tickets ? "Support-Tickets" : "Serverereignisse").toolbar { ToolbarItem(placement: .confirmationAction) { Button("Fertig") { dismiss() } } }
    }
}
struct SuiteEventConnectedView: View {
    let panel: PanelAPI
    let server: Server
    var tickets = false
    @StateObject private var model: SuiteModel
    init(panel: PanelAPI, server: Server, tickets: Bool = false) { self.panel = panel; self.server = server; self.tickets = tickets; _model = StateObject(wrappedValue: SuiteModel(panel: panel, server: server)) }
    var body: some View {
        Group {
            if tickets, let suite = model.api, model.health["features"].array.contains(.string("tickets-v1")) { SuiteTicketsView(suite: suite, panel: panel, server: server) }
            else if let suite = model.api, model.health["features"].array.contains(.string("events-v1")) { SuiteSecurityCenterView(suite: suite, panel: panel, server: server, actions: model.actions, area: .events) }
            else { List { if model.busy { ProgressView("Verbinden …") }; Text(model.error ?? "Admin Suite 9.0 oder neuer wird für diesen Bereich benötigt.").foregroundStyle(.secondary); Button("Erneut verbinden") { Task { await model.load(force: true) } } }.panelScreen() }
        }.task { await model.load() }
    }
}
