import SwiftUI

// MARK: - Smart-Neustart

/// 5.7.0: restart every N hours, but only while few players are online, with countdown and minimum gap.
struct SuiteSmartRestartView: View {
    let suite: SuiteAPI
    @State private var data: JSON = .null
    @State private var enabled = false
    @State private var interval = 6.0
    @State private var maxPlayers = 1
    @State private var countdown = 60
    @State private var stable = 2
    @State private var force = 0.0
    @State private var abortOnJoin = true
    @State private var loaded = false
    @State private var busy = false
    @State private var error: String?
    var manage: Bool { data["manage"].bool }
    private var headerSymbol: String { data["countdown"].bool ? "arrow.clockwise.circle.fill" : (enabled ? "clock.badge.checkmark.fill" : "clock.badge.xmark") }
    private var headerColors: [Color] { [enabled ? Color.green : Color.gray, Color.panelAccent] }
    private var playersText: String { "Nur bei höchstens \(maxPlayers) " + (maxPlayers == 1 ? "Spieler" : "Spielern") + " online" }
    private var dueText: String? {
        guard let due = SuitePlayerPresentation.date(data["dueAt"]) else { return nil }
        return due > Date() ? SuiteRelativeTime.absolute(due) : "jetzt, sobald die Bedingung passt"
    }

    var body: some View {
        Form {
            Section {
                HStack(spacing: 14) {
                    Image(systemName: headerSymbol)
                        .font(.system(size: 30)).foregroundStyle(.white).frame(width: 54, height: 54)
                        .background(LinearGradient(colors: headerColors, startPoint: .topLeading, endPoint: .bottomTrailing), in: RoundedRectangle(cornerRadius: 15))
                    VStack(alignment: .leading, spacing: 3) {
                        Text(data["phase"].string.isEmpty ? "Lädt …" : data["phase"].string).font(.headline)
                        Text("\(data["online"].int) Spieler online").font(.subheadline).foregroundStyle(.secondary)
                    }
                }.padding(.vertical, 4)
                if let dueText { LabeledContent("Frühestens", value: dueText) }
                if let last = SuitePlayerPresentation.date(data["lastRestart"]) { LabeledContent("Letzter Smart-Neustart", value: SuiteRelativeTime.text(last)) }
                if let start = SuitePlayerPresentation.date(data["serverStart"]) { LabeledContent("Server läuft seit", value: SuiteRelativeTime.text(start).replacingOccurrences(of: "vor ", with: "")) }
            }
            Section {
                Toggle("Smart-Neustart aktiv", isOn: $enabled)
                Stepper("Höchstens alle \(Int(interval)) Std.", value: $interval, in: 1...48, step: 1)
                Stepper(playersText, value: $maxPlayers, in: 0...20)
                Stepper("Bedingung \(stable) Min. stabil", value: $stable, in: 0...30)
                Stepper("Countdown \(countdown) Sek.", value: $countdown, in: 10...300, step: 10)
                Toggle("Abbrechen, wenn Spieler dazukommen", isOn: $abortOnJoin)
                Picker("Spätestens erzwingen", selection: $force) {
                    Text("Nie").tag(0.0)
                    ForEach([2.0, 4.0, 6.0, 12.0, 24.0], id: \.self) { Text("nach \(Int($0)) Std. Wartezeit").tag($0) }
                }
            } header: { Text("Regeln") } footer: {
                Text("Der Neustart wird mit dem gewohnten Countdown auf Deutsch und Englisch angekündigt. Nach einem Serverstart wartet die Suite mindestens 30 Minuten; zwei automatische Neustarts direkt hintereinander sind ausgeschlossen.")
            }
            .disabled(!manage)
            if manage {
                Section { Button { Task { await save() } } label: { HStack { Spacer(); if busy { ProgressView() } else { Text("Speichern").bold() }; Spacer() } }.disabled(busy || !loaded) }
            }
            if !data["history"].array.isEmpty {
                Section("Verlauf") {
                    ForEach(Array(data["history"].array.enumerated()), id: \.offset) { _, row in
                        HStack {
                            Text(row["text"].string).font(.subheadline)
                            Spacer()
                            if let date = SuitePlayerPresentation.date(row["at"]) { Text(SuiteRelativeTime.text(date)).font(.caption2).foregroundStyle(.secondary) }
                        }
                    }
                }
            }
        }
        .panelScreen().navigationTitle("Smart-Neustart")
        .task { await load(); while !Task.isCancelled { do { try await Task.sleep(for: .seconds(15)) } catch { return }; await load(fields: false) } }
        .refreshable { await load() }
        .errorAlert($error)
    }

    private func apply(_ settings: JSON) {
        enabled = settings["enabled"].bool; interval = max(1, settings["intervalHours"].double); maxPlayers = settings["maxPlayers"].int
        countdown = max(10, settings["countdownSeconds"].int); stable = settings["stableMinutes"].int; force = settings["forceAfterHours"].double
        abortOnJoin = settings["abortOnJoin"].bool; loaded = true
    }

    private func load(fields: Bool = true) async {
        do { data = try await suite.request("smart-restart"); if fields || !loaded { apply(data["settings"]) } }
        catch { if !RequestFailure.isCancellation(error) && fields { self.error = error.localizedDescription } }
    }

    private func save() async {
        busy = true; defer { busy = false }
        do {
            data = try await suite.post("smart-restart", ["enabled": .bool(enabled), "intervalHours": .number(interval), "maxPlayers": .number(Double(maxPlayers)),
                "countdownSeconds": .number(Double(countdown)), "stableMinutes": .number(Double(stable)), "forceAfterHours": .number(force),
                "abortOnJoin": .bool(abortOnJoin), "confirmed": .bool(true)])
            apply(data["settings"]); Feedback.success()
        } catch { self.error = error.localizedDescription }
    }
}

// MARK: - Automationen

enum SuiteAutomationText {
    static let triggers: [(String, String, String)] = [
        ("game-day", "Ingame-Tag", "calendar"), ("daily", "Täglich um", "clock"), ("interval", "Alle X Minuten", "repeat"),
        ("players-above", "Ab X Spielern online", "person.3.fill"), ("players-below", "Bis X Spieler online", "person.fill"), ("first-join", "Erstbeitritt", "hand.wave.fill")]
    static let actions: [(String, String, String)] = [
        ("broadcast", "Nachricht im Chat", "megaphone.fill"), ("notify", "Team benachrichtigen", "bell.badge.fill"), ("event", "Event-Status setzen", "star.fill"),
        ("lockdown", "Lockdown-Stufe", "lock.shield"), ("command", "Konsolenbefehl", "terminal.fill"), ("restart", "Neustart mit Countdown", "arrow.clockwise.circle.fill")]
    static func trigger(_ t: JSON) -> String {
        switch t["type"].string {
        case "game-day": return "Welt \(t["world"].string) · ab Tag \(t["day"].int)" + (t["everyDays"].int > 0 ? " · alle \(t["everyDays"].int) Tage" : " · einmalig")
        case "daily": return "Täglich um \(t["time"].string) Uhr"
        case "interval": return "Alle \(t["minutes"].int) Minuten"
        case "players-above": return "Sobald mindestens \(t["count"].int) Spieler online sind"
        case "players-below": return "Sobald höchstens \(t["count"].int) Spieler online sind"
        case "first-join": return "Wenn ein neuer Spieler zum ersten Mal beitritt"
        default: return t["type"].string
        }
    }
    static func action(_ a: JSON) -> String {
        let title = actions.first { $0.0 == a["type"].string }?.1 ?? a["type"].string
        return title + (a["value"].string.isEmpty ? "" : ": " + a["value"].string)
    }
    static func symbol(_ type: String) -> String { triggers.first { $0.0 == type }?.2 ?? "bolt.fill" }
}

struct SuiteAutomationsView: View {
    let suite: SuiteAPI
    @State private var data: JSON = .null
    @State private var editing: SuiteAutomationDraft?
    @State private var deleting: JSON?
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        List {
            Section {
                Text("Wenn etwas passiert, erledigt die Suite den Rest: Ansagen, Event-Status, Lockdown, Befehle oder ein Neustart – nach Ingame-Tag, Uhrzeit, Intervall, Spielerzahl oder Erstbeitritt.").font(.callout).foregroundStyle(.secondary)
            }
            if data != .null && data["items"].array.isEmpty {
                ContentUnavailableView("Noch keine Automationen", systemImage: "wand.and.stars", description: Text("Lege eine an oder starte mit einer Vorlage."))
            }
            ForEach(data["items"].array, id: \.["id"].string) { rule in
                Button { editing = SuiteAutomationDraft(rule) } label: { row(rule) }.buttonStyle(.plain)
                    .swipeActions(edge: .leading) {
                        if data["manage"].bool { Button("Jetzt ausführen", systemImage: "play.fill") { Task { await change(["operation": .string("run"), "id": rule["id"]]) } }.tint(.green) }
                    }
                    .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                        if data["manage"].bool { Button("Löschen", systemImage: "trash") { deleting = rule }.tint(.red) }
                    }
            }
            if data["manage"].bool {
                Section("Vorlagen") {
                    ForEach(SuiteAutomationDraft.templates(world: data["worlds"].array.first?["name"].string ?? "world"), id: \.name) { template in
                        Button { editing = template } label: { Label(template.name, systemImage: SuiteAutomationText.symbol(template.trigger)) }
                    }
                }
            }
        }
        .panelScreen().navigationTitle("Automationen")
        .toolbar { if data["manage"].bool { ToolbarItem(placement: .topBarTrailing) { Button("Neue Automation", systemImage: "plus") { editing = SuiteAutomationDraft() } } } }
        .task { await load() }
        .refreshable { await load() }
        .disabled(busy)
        .sheet(item: $editing) { draft in
            SuiteAutomationEditor(draft: draft, worlds: data["worlds"].array, commands: data["commands"].bool, manage: data["manage"].bool) { body in
                await change(["operation": .string("save"), "id": .string(draft.ruleID), "rule": body])
            }
        }
        .confirmationDialog("„\(deleting?["name"].string ?? "")“ löschen?", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }), titleVisibility: .visible, presenting: deleting) { rule in
            Button("Löschen", role: .destructive) { Task { await change(["operation": .string("delete"), "id": rule["id"]]) } }
            Button("Abbrechen", role: .cancel) {}
        }
        .errorAlert($error)
    }

    private func row(_ rule: JSON) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: SuiteAutomationText.symbol(rule["trigger"]["type"].string)).font(.body.weight(.semibold)).foregroundStyle(.white)
                .frame(width: 36, height: 36).background((rule["enabled"].bool ? Color.panelAccent : Color.gray).gradient, in: RoundedRectangle(cornerRadius: 10))
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text(rule["name"].string).font(.headline)
                    if !rule["enabled"].bool { PowerBotTag(text: "Pausiert", tint: .secondary) }
                }
                Text(SuiteAutomationText.trigger(rule["trigger"])).font(.caption).foregroundStyle(.secondary)
                ForEach(Array(rule["actions"].array.enumerated()), id: \.offset) { _, action in
                    Label(SuiteAutomationText.action(action), systemImage: "arrow.turn.down.right").font(.caption).lineLimit(1)
                }
                HStack(spacing: 8) {
                    if rule["runs"].int > 0 { Text("\(rule["runs"].int)× ausgeführt") }
                    if let date = SuitePlayerPresentation.date(rule["lastRun"]) { Text("zuletzt " + SuiteRelativeTime.text(date)) }
                    if !rule["lastResult"].string.isEmpty && rule["lastResult"].string != "OK" { Text(rule["lastResult"].string).foregroundStyle(.orange) }
                }.font(.caption2).foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
            if data["manage"].bool {
                Toggle("Aktiv", isOn: Binding(get: { rule["enabled"].bool }, set: { _ in Task { await change(["operation": .string("toggle"), "id": rule["id"]]) } })).labelsHidden()
            }
        }.padding(.vertical, 3).contentShape(Rectangle())
    }

    private func load() async {
        do { data = try await suite.request("automations") }
        catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }

    @discardableResult private func change(_ body: [String: JSON]) async -> Bool {
        busy = true; defer { busy = false }
        do { data = try await suite.post("automations", body.merging(["confirmed": .bool(true)]) { current, _ in current }); Feedback.success(); return true }
        catch { self.error = error.localizedDescription; return false }
    }
}

struct SuiteAutomationDraft: Identifiable {
    struct Action: Identifiable { let id = UUID(); var type = "broadcast"; var value = ""; var en = "" }
    let id = UUID()
    var ruleID = ""
    var name = ""
    var enabled = true
    var trigger = "daily"
    var world = ""
    var day = 1
    var everyDays = 0
    var time = "20:00"
    var minutes = 60
    var count = 10
    var cooldown = 30
    var actions: [Action] = [Action()]

    init() {}
    init(_ rule: JSON) {
        ruleID = rule["id"].string; name = rule["name"].string; enabled = rule["enabled"].bool
        let t = rule["trigger"]
        trigger = t["type"].string; world = t["world"].string; day = t["day"].int; everyDays = t["everyDays"].int
        time = t["time"].string.isEmpty ? "20:00" : t["time"].string; minutes = max(15, t["minutes"].int); count = t["count"].int
        cooldown = max(1, rule["cooldownMinutes"].int)
        actions = rule["actions"].array.map { Action(type: $0["type"].string, value: $0["value"].string, en: $0["en"].string) }
        if actions.isEmpty { actions = [Action()] }
    }
    static func templates(world: String) -> [SuiteAutomationDraft] {
        var a = SuiteAutomationDraft(); a.name = "Abendgruß um 20 Uhr"; a.trigger = "daily"; a.time = "20:00"
        a.actions = [Action(type: "broadcast", value: "Schönen Abend auf Simplex! Denkt an Pausen.", en: "Have a nice evening on Simplex!")]
        var b = SuiteAutomationDraft(); b.name = "Alle 7 Ingame-Tage Markttag"; b.trigger = "game-day"; b.world = world; b.day = 1; b.everyDays = 7
        b.actions = [Action(type: "event", value: "Markttag"), Action(type: "broadcast", value: "Heute ist Markttag!", en: "Today is market day!")]
        var c = SuiteAutomationDraft(); c.name = "Danke bei vollem Server"; c.trigger = "players-above"; c.count = 15; c.cooldown = 120
        c.actions = [Action(type: "broadcast", value: "Danke, dass ihr so zahlreich da seid!", en: "Thanks for playing with so many of you!"), Action(type: "notify", value: "15 Spieler online")]
        var d = SuiteAutomationDraft(); d.name = "Willkommen für neue Spieler"; d.trigger = "first-join"
        d.actions = [Action(type: "broadcast", value: "Willkommen {player} auf Simplex!", en: "Welcome {player} to Simplex!")]
        return [a, b, c, d]
    }
    var json: JSON {
        var t: [String: JSON] = ["type": .string(trigger)]
        switch trigger {
        case "game-day": t["world"] = .string(world); t["day"] = .number(Double(day)); t["everyDays"] = .number(Double(everyDays))
        case "daily": t["time"] = .string(time)
        case "interval": t["minutes"] = .number(Double(minutes))
        case "players-above", "players-below": t["count"] = .number(Double(count))
        default: break
        }
        return .object(["name": .string(name), "enabled": .bool(enabled), "cooldownMinutes": .number(Double(cooldown)), "trigger": .object(t),
                        "actions": .array(actions.map { .object(["type": .string($0.type), "value": .string($0.value), "en": .string($0.en)]) })])
    }
}

struct SuiteAutomationEditor: View {
    @State var draft: SuiteAutomationDraft
    let worlds: [JSON]
    let commands: Bool
    let manage: Bool
    let save: (JSON) async -> Bool
    @Environment(\.dismiss) private var dismiss
    @State private var busy = false
    private static let times: [String] = stride(from: 0, to: 24 * 60, by: 15).map { (minute: Int) -> String in String(format: "%02d:%02d", minute / 60, minute % 60) }
    private var countText: String { (draft.trigger == "players-above" ? "Mindestens " : "Höchstens ") + String(draft.count) + " Spieler" }
    private func actionChoices(_ current: String) -> [(String, String, String)] {
        SuiteAutomationText.actions.filter { (choice: (String, String, String)) -> Bool in
            commands || choice.0 == current || (choice.0 != "command" && choice.0 != "restart")
        }
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Name", text: $draft.name)
                    Toggle("Aktiv", isOn: $draft.enabled)
                }
                Section("Wenn …") {
                    Picker("Auslöser", selection: $draft.trigger) { ForEach(SuiteAutomationText.triggers, id: \.0) { Label($0.1, systemImage: $0.2).tag($0.0) } }
                    switch draft.trigger {
                    case "game-day":
                        Picker("Welt", selection: $draft.world) { ForEach(worlds, id: \.["name"].string) { Text($0["name"].string + " · jetzt Tag " + $0["day"].string).tag($0["name"].string) } }
                        Stepper("Ab Ingame-Tag \(draft.day)", value: $draft.day, in: 0...1_000_000)
                        Stepper(draft.everyDays == 0 ? "Einmalig" : "Danach alle \(draft.everyDays) Tage", value: $draft.everyDays, in: 0...365)
                    case "daily":
                        Picker("Uhrzeit", selection: $draft.time) { ForEach(Self.times, id: \.self) { Text($0 + " Uhr").tag($0) } }
                    case "interval":
                        Stepper("Alle \(draft.minutes) Minuten", value: $draft.minutes, in: 15...10_080, step: 15)
                    case "players-above", "players-below":
                        Stepper(countText, value: $draft.count, in: 0...500)
                        Stepper("Pause danach: \(draft.cooldown) Min.", value: $draft.cooldown, in: 1...1440, step: 5)
                    default:
                        Text("Platzhalter {player} wird durch den Namen des neuen Spielers ersetzt.").font(.caption).foregroundStyle(.secondary)
                    }
                }
                Section {
                    ForEach($draft.actions) { $action in
                        VStack(alignment: .leading, spacing: 8) {
                            Picker("Aktion", selection: $action.type) {
                                ForEach(actionChoices(action.type), id: \.0) { choice in Label(choice.1, systemImage: choice.2).tag(choice.0) }
                            }
                            switch action.type {
                            case "broadcast":
                                TextField("Deutsch", text: $action.value, axis: .vertical)
                                TextField("Englisch (optional)", text: $action.en, axis: .vertical)
                            case "lockdown":
                                Picker("Stufe", selection: $action.value) {
                                    Text("Aus").tag("off"); Text("Nur bekannte Spieler").tag("known"); Text("Eine Verbindung je IP").tag("one-connection"); Text("Geschlossen").tag("closed")
                                }
                            case "restart":
                                TextField("Countdown in Sekunden (10–300)", text: $action.value).keyboardType(.numberPad)
                            case "command":
                                TextField("Befehl ohne /", text: $action.value).textInputAutocapitalization(.never).autocorrectionDisabled().font(.callout.monospaced())
                            default:
                                TextField(action.type == "event" ? "Event-Name (leer = beenden)" : "Text", text: $action.value, axis: .vertical)
                            }
                        }
                    }
                    .onDelete { draft.actions.remove(atOffsets: $0) }
                    if draft.actions.count < 8 { Button("Aktion hinzufügen", systemImage: "plus") { draft.actions.append(.init()) } }
                } header: { Text("… dann") } footer: {
                    Text(commands ? "Befehle werden geprüft; sas, op, stop und reload sind gesperrt." : "Befehle und Neustarts benötigen das Befehlsrecht des Event-Planers.")
                }
            }
            .panelScreen()
            .navigationTitle(draft.ruleID.isEmpty ? "Neue Automation" : "Automation bearbeiten")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Sichern") { Task { busy = true; if await save(draft.json) { dismiss() }; busy = false } }
                        .disabled(busy || !manage || draft.name.trimmingCharacters(in: .whitespaces).isEmpty || draft.actions.isEmpty)
                }
            }
            .onAppear { if draft.world.isEmpty { draft.world = worlds.first?["name"].string ?? "" } }
        }
    }
}
