import SwiftUI

struct SuiteAIWorkspace: View {
    let suite: SuiteAPI
    @State private var state: JSON = .null
    @State private var reply: JSON = .null
    @State private var results: [JSON] = []
    @State private var question = ""
    @State private var error: String?
    @State private var busy = false
    @State private var selected: [String: Set<String>] = [:]
    @State private var approval: Approval?
    @State private var summaries: [String: String] = [:]
    @State private var editSettings = false

    struct Approval: Identifiable {
        let id: String
        let actions: [JSON]
    }

    var body: some View {
        List {
            if !suite.features.contains("ai-workspace-v1") {
                Section {
                    Label("Vorschau · Admin Suite 10.8.0", systemImage: "arrow.down.circle")
                    Text("Serverdaten gezielt durchsuchen, Spielerprofile öffnen und exakte Aktionsentwürfe prüfen. Für Live-Daten und Freigaben zuerst das Plugin aktualisieren.")
                }
            } else if state == .null {
                Section { if busy { ProgressView("KI-Status wird geladen …") }; Button("Status laden") { Task { await perform { try await load() } } } }
            } else if !state["allowed"].bool {
                Section { Label("KI-Zugriff fehlt", systemImage: "lock"); Text("Das Konto benötigt das Recht „KI-Adminmodus“. Quellen und Aktionen folgen deinen vorhandenen Rechten.") }
            } else {
                askSection
                responseSection
                if !results.isEmpty {
                    Section("Ausführung") {
                        ForEach(results, id: \.["id"].string) { row in
                            Label(row["summary"].string, systemImage: row["ok"].bool ? "checkmark.circle" : "exclamationmark.circle")
                            Text(row["message"].string).font(.caption).textSelection(.enabled)
                        }
                    }
                }
                ForEach(state["pending"].array, id: \.["id"].string) { plan in planSection(plan) }
                Section {
                    Button("Offene Entwürfe aktualisieren", systemImage: "arrow.clockwise") { Task { await perform { try await load() } } }.disabled(busy)
                    if state["owner"].bool { Button("KI-Zugang und Limits", systemImage: "slider.horizontal.3") { editSettings = true }.disabled(busy) }
                }
            }
        }
        .panelScreen().navigationTitle("Simplex KI")
        .task { if suite.features.contains("ai-workspace-v1") { await perform { try await load() } } }
        .refreshable { if suite.features.contains("ai-workspace-v1") { await perform { try await load() } } }
        .errorAlert($error)
        .sheet(isPresented: $editSettings, onDismiss: { Task { await perform { try await load() } } }) { SuiteAIWorkspaceSettings(suite: suite, initial: state) }
        .confirmationDialog("Ausgewählte KI-Aktionen ausführen?", isPresented: Binding(get: { approval != nil }, set: { if !$0 { approval = nil } }), titleVisibility: .visible, presenting: approval) { item in
            Button("\(item.actions.count) Aktion(en) verbindlich freigeben", role: .destructive) { Task { await approve(item) } }
        } message: { item in
            Text(item.actions.map { $0["summary"].string }.joined(separator: "\n"))
        }
    }
    private var askSection: some View {
        Section("KI fragen") {
            if !state["available"].bool { Text("Die KI ist derzeit deaktiviert oder ihr Schlüssel fehlt. Offene Entwürfe kannst du weiterhin prüfen.").foregroundStyle(.secondary) }
            TextField("Was soll ich am Server prüfen?", text: $question, axis: .vertical).lineLimit(3...7)
                .disabled(busy || !state["available"].bool)
            Button("Senden", systemImage: "sparkles") { let input = question; Task { await ask(input) } }
                .disabled(busy || !state["available"].bool || question.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            if busy { ProgressView("Anfrage läuft …") }
        }
    }
    @ViewBuilder private var responseSection: some View {
        if reply != .null {
            Section("Antwort") {
                Text(reply["answer"].string).textSelection(.enabled)
                let sources = reply["sources"].array.map(\.string)
                if !sources.isEmpty { Text("Durchsuchte Quellen · " + sources.joined(separator: " · ")).font(.caption).foregroundStyle(.secondary) }
                ForEach(reply["players"].array, id: \.["id"].string) { player in
                    NavigationLink {
                        SuitePlayerDossierView(suite: suite, player: player["id"].string)
                    } label: { Label(player["name"].string, systemImage: "person.crop.circle") }
                }
                ForEach(Array(reply["suggestions"].array.enumerated()), id: \.offset) { _, suggestion in
                    Button(suggestion.string, systemImage: "bubble.left") { Task { await ask(suggestion.string) } }.disabled(busy)
                }
            }
        }
    }
    private func planSection(_ plan: JSON) -> some View {
        let id = plan["id"].string
        let actions = plan["actions"].array
        return Section {
            Text("Von " + plan["requester"].string).font(.caption).foregroundStyle(.secondary)
            ForEach(actions, id: \.["id"].string) { action in
                let actionID = action["id"].string
                if state["owner"].bool {
                    Toggle(action["summary"].string, isOn: Binding(get: { selected[id]?.contains(actionID) == true }, set: { enabled in
                        if enabled { selected[id, default: []].insert(actionID) }
                        else { selected[id]?.remove(actionID) }
                    }))
                } else { Text(action["summary"].string).textSelection(.enabled) }
            }
            if let text = summaries[id] { Text(text).font(.callout).textSelection(.enabled) }
            Button("KI-Zusammenfassung", systemImage: "text.alignleft") {
                Task { await perform { summaries[id] = try await post("summarize", ["id": .string(id)])["summary"].string } }
            }.disabled(busy || !state["available"].bool)
            if state["owner"].bool {
                Button("Auswahl prüfen und freigeben", systemImage: "checkmark.shield", role: .destructive) {
                    approval = Approval(id: id, actions: actions.filter { selected[id]?.contains($0["id"].string) == true })
                }.disabled(busy || selected[id]?.isEmpty != false)
            }
            Button("Entwurf verwerfen", systemImage: "xmark.circle") {
                Task { await perform { _ = try await post("cancel", ["id": .string(id)]); try await load() } }
            }.disabled(busy)
        } header: { Text("KI-Aktionsentwurf") }
    }
    private func load() async throws { state = try await suite.request("ai-workspace", timeout: 10) }
    private func post(_ path: String, _ fields: [String: JSON]) async throws -> JSON {
        try await suite.request("ai-workspace/" + path, method: "POST", body: .object(fields), timeout: 110)
    }
    private func ask(_ input: String) async {
        await perform {
            reply = try await post("ask", ["question": .string(input)])
            question = ""
            try await load()
        }
    }
    private func approve(_ item: Approval) async {
        await perform {
            let ids = item.actions.map { JSON.string($0["id"].string) }
            results = try await post("approve", ["id": .string(item.id), "actions": .array(ids)])["results"].array
            selected[item.id] = nil
            try await load()
        }
    }
    private func perform(_ action: () async throws -> Void) async {
        guard !busy else { return }
        busy = true
        defer { busy = false }
        do { try await action() } catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }
}

private struct SuiteAIWorkspaceSettings: View {
    let suite: SuiteAPI
    let initial: JSON
    @Environment(\.dismiss) private var dismiss
    @State private var enabled = true
    @State private var daily = 15
    @State private var limits = ""
    @State private var delegates = ""
    @State private var error: String?
    @State private var busy = false
    private func lines(_ text: String) -> JSON {
        .array(text.split(whereSeparator: \.isNewline).map { .string($0.trimmingCharacters(in: .whitespaces)) })
    }
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Toggle("KI-Adminmodus", isOn: $enabled)
                    Stepper("Standardlimit pro Tag · \(daily)", value: $daily, in: 0...500)
                    Text("Der Hauptadmin hat kein Tageslimit. Rollenrechte gelten für jede Quelle und Aktion.")
                }
                Section("Kontolimits") {
                    TextField("app:admin=10", text: $limits, axis: .vertical).textInputAutocapitalization(.never)
                }
                Section("Discord-Konten verbinden") {
                    TextField("Discord-ID=App-Benutzer", text: $delegates, axis: .vertical).textInputAutocapitalization(.never)
                    Text("Eine Zuordnung pro Zeile. Das verknüpfte App-Konto bestimmt die Rechte.")
                }
                Button("Speichern") { Task { await save() } }.disabled(busy)
            }.panelScreen().navigationTitle("KI-Zugang und Limits").errorAlert($error)
        }.onAppear {
            enabled = initial["enabled"].bool; daily = initial["dailyLimit"].int
            limits = initial["limits"].array.map(\.string).joined(separator: "\n")
            delegates = initial["discordDelegates"].array.map(\.string).joined(separator: "\n")
        }
    }
    private func save() async {
        busy = true
        defer { busy = false }
        do {
            _ = try await suite.request("ai-workspace/settings", method: "POST", body: .object([
                "enabled": .bool(enabled), "dailyLimit": .number(Double(daily)), "limits": lines(limits), "discordDelegates": lines(delegates)
            ]))
            dismiss()
        } catch { self.error = error.localizedDescription }
    }
}
