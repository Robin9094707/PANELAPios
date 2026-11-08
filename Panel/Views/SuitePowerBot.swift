import SwiftUI

struct SuitePowerBotView: View {
    let suite: SuiteAPI
    @AppStorage("powerbot.digest.enabled") private var showDigest = true
    @State private var data: JSON = .null
    @State private var filter = "important"
    @State private var pending: (String, String)?
    @State private var settings = false
    @State private var busy = false
    @State private var error: String?

    private static let statuses = ["active": "Aktiv", "bypass": "KI pausiert · Chat frei",
                                   "unconfigured": "API-Schlüssel fehlt · lokale Regeln aktiv", "off": "Aus"]
    static let kinds = ["mute": "AutoMod-Mute", "conflict": "Konflikt", "help": "Hilferuf",
        "security": "Cheat-Hinweis", "outage": "KI pausiert", "recovery": "KI wieder erreichbar",
        "settings": "Einstellungen geändert", "abuse": "Chatnachricht blockiert", "advertising": "Werbung blockiert",
        "keyword-warning": "Stichwortprüfung · Warnung", "keyword-block": "Stichwortprüfung · blockiert",
        "warning": "Warnhinweis", "reviewed": "Nachträgliche Prüfung", "deescalation": "KI hat deeskaliert",
        "ai-action": "KI-Aktion ausgeführt", "server-event": "Wichtiges Serverereignis"]
    static let flags: [(String, String)] = [("enabled", "PowerBot"), ("moderation", "KI-Moderation"),
        ("whispers", "Flüsternachrichten prüfen"), ("advertising", "Eigenwerbung blockieren"), ("auto-mute", "AutoMod-Mutes"),
        ("linked-mutes", "Bestätigte Alt-Konten einschließen"), ("public-chat", "Öffentliche Chat-KI"),
        ("proactive", "Proaktiv deeskalieren"), ("discord", "Discord-Relay"), ("notify-staff", "Staff benachrichtigen"),
        ("security-hints", "Cheat-Hinweise sammeln"), ("staff-device-context", "Geräteinfos für Staff-KI"), ("critical-alerts", "Kritische Meldungen")]

    var body: some View {
        List {
            Section {
                LabeledContent("PowerBot", value: Self.statuses[data["status"].string] ?? "Lädt …")
                LabeledContent("Offene Fälle", value: data["open"].string)
                Toggle("Rückblick nach 3 Stunden anzeigen", isOn: $showDigest)
                if data["canSettings"].bool {
                    Button("AutoMod einstellen", systemImage: "slider.horizontal.3") { settings = true }
                    if suite.features.contains("powerbot-keywords-v1") {
                        NavigationLink {
                            SuitePowerBotKeywordsView(suite: suite)
                        } label: { Label("Stichwortregeln", systemImage: "text.magnifyingglass") }
                    }
                }
            }
            Section {
                Picker("Ansicht", selection: $filter) {
                    Text("Wichtig").tag("important")
                    Text("Offen").tag("open")
                    Text("Mutes").tag("mutes")
                    Text("Alles").tag("all")
                }.pickerStyle(.segmented)
            }
            if data != .null && data["items"].array.isEmpty {
                ContentUnavailableView("Keine Einträge", systemImage: "checkmark.shield")
            }
            ForEach(data["items"].array, id: \.["id"].string) { row in
                Section { entry(row) }
            }
        }
        .panelScreen()
        .navigationTitle("PowerBot-Fallzentrale")
        .task(id: filter) { await load() }
        .refreshable { await load() }
        .sheet(isPresented: $settings) {
            SuitePowerBotSettings(initial: data) { flags, guidelines in
                await change("settings", ["flags": .object(flags), "guidelines": .string(guidelines)])
            }
        }
        .confirmationDialog("PowerBot-Fall aktualisieren", isPresented: Binding(get: { pending != nil }, set: { if !$0 { pending = nil } }), titleVisibility: .visible) {
            if let (operation, id) = pending {
                Button(operation == "revoke" ? "AutoMod-Mute aufheben" : "Fall abschließen", role: operation == "revoke" ? .destructive : nil) {
                    Task { await change(operation, ["id": .string(id)]) }
                }
            }
        }
        .errorAlert($error)
    }

    @ViewBuilder private func entry(_ row: JSON) -> some View {
        let id = row["id"].string
        let kind = row["kind"].string
        let player = row["player"].string
        Label(Self.kinds[kind] ?? kind, systemImage: row["active"].bool ? "speaker.slash.fill" : "shield.lefthalf.filled")
            .font(.headline)
        Text([row["name"].string, row["source"].string].filter { !$0.isEmpty }.joined(separator: " · "))
            .font(.caption).foregroundStyle(.secondary)
        if let date = SuitePlayerPresentation.date(row["at"]) {
            Text(SuiteGermanTime.text(date)).font(.caption.monospacedDigit()).foregroundStyle(.secondary)
        }
        Text(row["detail"].string).font(.callout).textSelection(.enabled)
        if row["score"].int > 0 { LabeledContent("Bestätigte Versuche · 20 Minuten", value: row["score"].string) }
        if row["active"].bool, let date = SuitePlayerPresentation.date(row["until"]) {
            LabeledContent("Aktiv bis", value: SuiteGermanTime.text(date))
            LabeledContent("Betroffene Konten", value: row["targetCount"].string)
        } else if row["revokedAt"].double > 0 {
            Label("Widerrufen", systemImage: "arrow.uturn.backward")
        }
        if row["case"].bool {
            if row["resolved"].bool { Label("Abgeschlossen", systemImage: "checkmark.circle") }
            if !row["assignee"].string.isEmpty { LabeledContent("Übernommen von", value: row["assignee"].string) }
            if data["manage"].bool {
                if !row["resolved"].bool {
                    if row["assignee"].string.isEmpty {
                        Button("Fall übernehmen", systemImage: "person.badge.plus") { Task { await change("claim", ["id": .string(id)]) } }.disabled(busy)
                    }
                    Button("Fall abschließen", systemImage: "checkmark") { pending = ("resolve", id) }.disabled(busy)
                }
                if row["active"].bool {
                    Button("Mute aufheben", systemImage: "arrow.uturn.backward") { pending = ("revoke", id) }.disabled(busy)
                }
            }
        }
        if UUID(uuidString: player) != nil, suite.can("data.profile") {
            NavigationLink("Spieler verwalten") { SuitePlayerDossierView(suite: suite, player: player) }
        }
    }

    private func load() async {
        let requested = filter
        do {
            let result = try await suite.request("powerbot", query: ["filter": requested])
            if !Task.isCancelled && requested == filter { data = result }
        } catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }

    @discardableResult private func change(_ operation: String, _ fields: [String: JSON]) async -> Bool {
        guard !busy else { return false }
        busy = true
        defer { busy = false }
        do {
            _ = try await suite.post("powerbot", fields.merging(["operation": .string(operation), "confirmed": .bool(true)]) { current, _ in current })
            await load()
            return true
        } catch { self.error = error.localizedDescription; return false }
    }
}

private struct SuitePowerBotSettings: View {
    @Environment(\.dismiss) private var dismiss
    @State private var flags: [String: JSON]
    @State private var guidelines: String
    @State private var saving = false
    let apply: ([String: JSON], String) async -> Bool

    init(initial: JSON, apply: @escaping ([String: JSON], String) async -> Bool) {
        _flags = State(initialValue: initial["flags"].object)
        _guidelines = State(initialValue: initial["guidelines"].string)
        self.apply = apply
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    ForEach(SuitePowerBotView.flags, id: \.0) { key, label in
                        Toggle(label, isOn: Binding(get: { flags[key]?.bool == true }, set: { flags[key] = .bool($0) }))
                    }
                } footer: { Text("Kritische Hinweise folgen den Bark-Regeln und Ruhezeiten der Empfänger.") }
                Section("Zusätzliche Moderationsrichtlinien") {
                    TextField("Optional", text: $guidelines, axis: .vertical).lineLimit(3...6)
                }
            }
            .navigationTitle("PowerBot einstellen")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Speichern") {
                        saving = true
                        Task {
                            if await apply(flags, guidelines) { dismiss() }
                            saving = false
                        }
                    }.disabled(saving)
                }
            }
        }
    }
}

struct SuitePowerBotDigest: View {
    let suite: SuiteAPI
    @Environment(\.scenePhase) private var scene
    @AppStorage("powerbot.digest.enabled") private var enabled = true
    @State private var count = 0
    @State private var mutes = 0
    @State private var dismissed = false

    private var key: String { "powerbot.lastActive." + suite.connection.id.uuidString }
    var body: some View {
        Group {
            if enabled && count > 0 && !dismissed {
                HStack {
                    NavigationLink {
                        SuitePowerBotView(suite: suite)
                    } label: {
                        VStack(alignment: .leading) {
                            Label("Seit deinem letzten Besuch", systemImage: "shield.lefthalf.filled").font(.subheadline.bold())
                            Text("\(count) wichtige PowerBot-Einträge · \(mutes) Mutes").font(.caption).foregroundStyle(.secondary)
                        }
                    }.buttonStyle(.plain)
                    Spacer()
                    Button("Rückblick ausblenden", systemImage: "xmark") { dismissed = true }.labelStyle(.iconOnly).buttonStyle(.plain)
                }.frame(maxWidth: .infinity, alignment: .leading).card()
            }
        }.task(id: scene) {
            guard suite.features.contains("powerbot-v1"), suite.can("data.powerbot") else { return }
            let now = Date().timeIntervalSince1970
            if scene == .background { UserDefaults.standard.set(now, forKey: key); return }
            guard scene == .active else { return }
            let previous = UserDefaults.standard.double(forKey: key)
            guard enabled, previous > 0, now - previous >= 10_800 else {
                UserDefaults.standard.set(now, forKey: key)
                return
            }
            do {
                let result = try await suite.request("powerbot", query: ["after": String(Int64(previous * 1000))])
                guard !Task.isCancelled else { return }
                count = result["recent"].int
                mutes = result["mutes"].int
                dismissed = false
                UserDefaults.standard.set(now, forKey: key)
            } catch { }
        }
    }
}
