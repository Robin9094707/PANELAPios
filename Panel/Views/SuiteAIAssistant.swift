import SwiftUI

struct SuiteAIAssistantView: View {
    let suite: SuiteAPI
    var panel: PanelAPI?
    var server: Server?
    @State private var status: JSON = .null
    @State private var result: JSON = .null
    @State private var inbox: [JSON] = []
    @State private var limits: JSON = .null
    @State private var input = ""
    @State private var account = ""
    @State private var daily = "15"
    @State private var error: String?
    @State private var work: Task<Void, Never>?
    @State private var busy = false
    @State private var confirming = false
    @State private var showSummary = false
    @State private var response: [JSON] = []
    @State private var selection: Set<String> = []
    @State private var summaryOverride = ""

    private var supported: Bool { suite.features.contains("admin-assistant-v1") }
    private var actions: [JSON] { result["actions"].array }
    private var owner: Bool { status["owner"].bool }
    private var ready: Bool { result["status"].string == "ready" }
    private var canAsk: Bool { status["available"].bool && !busy }
    private let examples = ["Gab es letzte Nacht Vorfälle im Chat?", "Was haben die Admins zuletzt gemacht, und wann waren sie im Vanish?", "Prüfe die letzten Disconnects. Schlage bei Bedarf einen Neustart in zehn Minuten vor.", "Welche Tickets sind aktuell offen?"]

    var body: some View {
        List {
            if !supported {
                Section("Vorschau") {
                    Label("Der KI-Assistent kommt mit Admin Suite 10.8.0", systemImage: "sparkles")
                    Text("Chat, Tickets, Spielerakten, Admin-Log und Diagnose gezielt abfragen. Schreibvorschläge zeigt die App zuerst vollständig zur Freigabe an.")
                    ForEach(examples, id: \.self) { Text($0).foregroundStyle(.secondary) }
                    if let panel, let server { NavigationLink("Plugin-Updates öffnen") { PluginsView(api: panel, server: server) } }
                }
            } else {
                Section("Serverassistent") {
                    if status == .null && error == nil { ProgressView("KI-Zugang prüfen …") }
                    if status != .null && !status["available"].bool {
                        Label("KI ist auf diesem Server momentan nicht verfügbar.", systemImage: "sparkles.slash").foregroundStyle(.secondary)
                    }
                    if status != .null {
                        Text(owner ? "Hauptadmin · ohne Tageslimit" : "Heute genutzt: \(status["used"].int) / \(status["limit"].int)").font(.caption).foregroundStyle(.secondary)
                    }
                    TextField("Was soll die KI prüfen?", text: $input, axis: .vertical).lineLimit(2...5)
                    Button("Fragen", systemImage: "arrow.up.circle.fill") { send() }
                        .disabled(!canAsk || input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || input.count > 2000)
                    if busy { ProgressView("Antwort und Datenquellen werden geprüft …") }
                    DisclosureGroup("Beispielfragen") {
                        ForEach(examples, id: \.self) { example in Button(example) { input = example }.disabled(busy) }
                    }
                }
                answer
                if owner { ownerControls }
                Section { NavigationLink("Manuelle Werkzeuge") { SuiteToolDirectoryView(suite: suite, panel: panel, server: server) } }
            }
        }
        .panelScreen()
        .navigationTitle("KI-Assistent")
        .task { await refresh() }
        .refreshable { await refresh() }
        .onDisappear { work?.cancel() }
        .errorAlert($error)
        .confirmationDialog("Diesen Aktionsplan verbindlich ausführen?", isPresented: $confirming, titleVisibility: .visible) {
            Button("\(selection.count) Aktionen bestätigen", role: .destructive) { mutate("approve") }
        } message: {
            Text("Der Server führt die angezeigten Schritte nacheinander aus. Zu jedem Schritt erscheint ein Ergebnis.")
        }
    }

    @ViewBuilder private var answer: some View {
        if result != .null {
            Section("Antwort") {
                if !result["answer"].string.isEmpty { Text(result["answer"].string).textSelection(.enabled) }
                if !result["sources"].array.isEmpty {
                    Text("Datenquellen · " + result["sources"].array.map(\.string).joined(separator: ", ")).font(.caption).foregroundStyle(.secondary)
                }
                if !result["summary"].string.isEmpty || !summaryOverride.isEmpty {
                    DisclosureGroup("KI-Kurzfassung", isExpanded: $showSummary) { Text(summaryOverride.isEmpty ? result["summary"].string : summaryOverride).textSelection(.enabled) }
                }
                ForEach(Array(result["choices"].array.enumerated()), id: \.offset) { index, value in
                    Button(value.string) { choose(index) }.disabled(!canAsk || !ready)
                }
                ForEach(result["players"].array, id: \.["id"].string) { player in
                    NavigationLink {
                        if let panel, let server { SuitePlayerView(suite: suite, panel: panel, server: server, player: player["id"].string, actions: []) }
                        else { SuitePlayerDossierView(suite: suite, player: player["id"].string) }
                    } label: { Label(player["name"].string, systemImage: "person.crop.circle") }
                }
                ForEach(result["rejected"].array, id: \.string) { Text($0.string).foregroundStyle(.orange) }
            }
            if !actions.isEmpty {
                Section("Vorgeschlagene Aktionen") {
                    ForEach(Array(actions.enumerated()), id: \.offset) { index, step in
                        VStack(alignment: .leading, spacing: 5) {
                            if owner && ready {
                                let id = step["id"].string
                                Toggle("\(index + 1). " + step["title"].string, isOn: Binding(get: { selection.contains(id) }, set: { enabled in
                                    if enabled { selection.insert(id) } else { selection.remove(id) }
                                })).disabled(busy)
                            } else { Text("\(index + 1). " + step["title"].string).font(.headline) }
                            Text(step["preview"].string).font(.callout).textSelection(.enabled)
                        }
                    }
                    if ready {
                        Button("Mit KI neu zusammenfassen", systemImage: "sparkles") { summarize() }.disabled(!canAsk)
                        if owner {
                            Button("Alle offenen Aktionen auswählen") { selection = Set(actions.map { $0["id"].string }) }.disabled(busy)
                            Button("Auswahl bestätigen", systemImage: "checkmark.shield") { confirming = true }.disabled(busy || selection.isEmpty)
                        }
                        else { Text("Wartet auf Freigabe durch den Hauptadmin.").font(.caption).foregroundStyle(.secondary) }
                        Button("Plan verwerfen", role: .destructive) { mutate("discard") }.disabled(busy)
                    } else { Text(result["status"].string == "discarded" ? "Plan verworfen." : "Plan verarbeitet.") }
                }
            }
        }
        if !response.isEmpty {
            Section("Ergebnis der Freigabe") {
                ForEach(Array(response.enumerated()), id: \.offset) { _, step in
                    Label(step["title"].string + " · " + step["description"].string, systemImage: step["ok"].bool ? "checkmark.circle" : "exclamationmark.circle")
                }
            }
        }
    }

    private var ownerControls: some View {
        Group {
            Section("Offene Freigaben") {
                if inbox.isEmpty { Text("Keine offenen Pläne.").foregroundStyle(.secondary) }
                ForEach(inbox, id: \.["id"].string) { plan in
                    Button(plan["author"].string + " · \(plan["count"].int) Aktionen", systemImage: "list.clipboard") {
                        run { result = try await suite.request("assistant/job", query: ["id": plan["id"].string]); response = []; selection = []; summaryOverride = "" }
                    }.disabled(busy)
                }
            }
            Section("Tageslimits für Suite-Konten") {
                Text("Standard: \(limits["default"].int) Anfragen. 0 sperrt neue Anfragen.").font(.caption)
                ForEach(limits["accounts"].object.keys.sorted(), id: \.self) { name in
                    Button { account = name; daily = limits["accounts"][name].string } label: { LabeledContent(name, value: limits["accounts"][name].string) }
                }
                TextField("Suite-Benutzername", text: $account).textInputAutocapitalization(.never).autocorrectionDisabled()
                TextField("Anfragen pro Tag", text: $daily).keyboardType(.numberPad)
                Button("Limit speichern") {
                    run { limits = try await suite.request("assistant/limits", method: "POST", body: .object(["account": .string(account), "limit": .string(daily)])) }
                }.disabled(busy || account.isEmpty || Int(daily).map { !(0...500).contains($0) } != false)
            }
        }
    }

    private func refresh() async {
        guard supported else { return }
        do {
            status = try await suite.request("assistant/status", timeout: 10)
            if owner {
                inbox = try await suite.request("assistant/inbox", timeout: 10)["items"].array
                limits = try await suite.request("assistant/limits", timeout: 10)
            }
        } catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }
    private func run(_ operation: @escaping @MainActor () async throws -> Void) {
        guard !busy else { return }
        busy = true; error = nil
        work = Task {
            defer { busy = false }
            do { try await operation() }
            catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
        }
    }
    private func send() {
        let question = input
        run {
            let job = try await suite.request("assistant/ask", method: "POST", body: .object(["question": .string(question)]), timeout: 12)
            try await poll(job["id"].string)
        }
    }
    private func choose(_ index: Int) {
        let id = result["id"].string
        run {
            let job = try await suite.request("assistant/choose", method: "POST", body: .object(["id": .string(id), "index": .number(Double(index))]), timeout: 12)
            try await poll(job["id"].string)
        }
    }
    private func poll(_ id: String) async throws {
        response = []; showSummary = false; selection = []; summaryOverride = ""
        for _ in 0..<64 {
            let row = try await suite.request("assistant/job", query: ["id": id], timeout: 12)
            if row["status"].string != "running" { result = row; await refresh(); return }
            try await Task.sleep(for: .seconds(2))
        }
        throw PanelError(message: "Der Server braucht länger. Prüfe später die offenen Freigaben.")
    }
    private func summarize() {
        let id = result["id"].string
        run {
            let job = try await suite.request("assistant/summarize", method: "POST", body: .object(["id": .string(id)]), timeout: 12)
            for _ in 0..<40 {
                let row = try await suite.request("assistant/job", query: ["id": job["id"].string], timeout: 12)
                if row["status"].string != "running" { summaryOverride = row["answer"].string; showSummary = true; await refresh(); return }
                try await Task.sleep(for: .seconds(2))
            }
            throw PanelError(message: "Die Kurzfassung ist noch nicht verfügbar.")
        }
    }
    private func mutate(_ operation: String) {
        let id = result["id"].string
        let revision = result["revision"].string
        let ids = actions.map { $0["id"].string }.filter { selection.contains($0) }
        run {
            let body: JSON = .object(["id": .string(id), "confirmed": .bool(operation == "approve"), "revision": .string(revision), "actions": .array(ids.map(JSON.string))])
            let response = try await suite.request("assistant/" + operation, method: "POST", body: body, timeout: 30)
            self.response = response["results"].array; selection = []; summaryOverride = ""
            result = try await suite.request("assistant/job", query: ["id": id], timeout: 12)
            await refresh()
        }
    }
}
