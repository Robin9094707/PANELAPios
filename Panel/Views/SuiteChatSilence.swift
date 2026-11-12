import SwiftUI

// MARK: - Chat-Ruhe

/// 5.10.0: players mute the public chat for themselves with /chat. Staff see who did and can help them
/// mute or unmute, including whispers and mentions.
enum SuiteChatSilenceText {
    static let presets: [(Int, String)] = [(30, "30 Minuten"), (180, "3 Stunden"), (1440, "24 Stunden"), (0, "Dauerhaft")]

    static func remaining(active: Bool, until: Double, now: Date = Date()) -> String {
        guard active else { return "Chat sichtbar" }
        guard until > 0 else { return "Dauerhaft – bis der Spieler ihn einschaltet" }
        let minutes = max(1, Int(((until / 1000) - now.timeIntervalSince1970) / 60 + 0.5))
        let hours = minutes / 60, rest = minutes % 60
        if hours > 0 { return "Noch \(hours) Std." + (rest > 0 ? " \(rest) Min." : "") }
        return "Noch \(rest) Min."
    }

    static func origin(_ by: String) -> String {
        switch by {
        case "": return ""
        case "self": return "vom Spieler selbst"
        case "expired": return "automatisch abgelaufen"
        default:
            var name = by
            if name.hasPrefix("staff:") { name.removeFirst("staff:".count) }
            if name.hasPrefix("Simplex Panel/") { name.removeFirst("Simplex Panel/".count) }
            return "vom Team (\(name))"
        }
    }

    static func filter(_ rows: [JSON], activeOnly: Bool, search: String) -> [JSON] {
        rows.filter { !activeOnly || $0["active"].bool }
            .filter { search.isEmpty || $0["name"].string.localizedCaseInsensitiveContains(search) }
    }
}

struct SuiteChatSilenceTarget: Identifiable, Equatable {
    let id: String
    let name: String
    var whispers = false
    var mentions = false
    var active = false
}

struct SuiteChatSilenceView: View {
    let suite: SuiteAPI
    @State private var data: JSON = .null
    @State private var activeOnly = true
    @State private var search = ""
    @State private var picking = false
    @State private var picked: SuiteChatSilenceTarget?
    @State private var editing: SuiteChatSilenceTarget?
    @State private var unmuting: SuiteChatSilenceTarget?
    @State private var error: String?
    @State private var loaded = false

    private var manage: Bool { data["manage"].bool }
    private var rows: [JSON] { SuiteChatSilenceText.filter(data["items"].array, activeOnly: activeOnly, search: search) }

    var body: some View {
        List {
            SuiteChatSilenceHeader(data: data)
            Section {
                Picker("Anzeige", selection: $activeOnly) {
                    Text("Aktiv").tag(true)
                    Text("Alle").tag(false)
                }.pickerStyle(.segmented)
            }
            rowsSection
            SuiteChatSilenceHelp()
        }
        .panelScreen()
        .navigationTitle("Chat-Ruhe")
        .searchable(text: $search, prompt: "Spieler suchen")
        .toolbar {
            if manage {
                ToolbarItem(placement: .primaryAction) {
                    Button { picking = true } label: { Label("Spieler stummschalten", systemImage: "bell.slash.fill") }
                }
            }
        }
        .sheet(isPresented: $picking, onDismiss: { if let picked { editing = picked; self.picked = nil } }) {
            SuiteChatSilencePlayerPicker(suite: suite) { id, name in
                let existing = data["items"].array.first { $0["player"].string == id }
                picked = SuiteChatSilenceTarget(id: id, name: name, whispers: existing?["whispers"].bool ?? false, mentions: existing?["mentions"].bool ?? false, active: existing?["active"].bool ?? false)
            }
        }
        .sheet(item: $editing) { target in
            SuiteChatSilenceEditor(target: target) { body in await send(body) }
        }
        .confirmationDialog("Chat wieder einschalten?", isPresented: Binding(get: { unmuting != nil }, set: { if !$0 { unmuting = nil } }), titleVisibility: .visible) {
            if let unmuting {
                Button("Chat für \(unmuting.name) einschalten") { Task { await send(["operation": .string("unmute"), "player": .string(unmuting.id)]) } }
            }
        } message: { Text("Der Spieler sieht danach wieder alle Chatnachrichten und wird im Spiel informiert.") }
        .task { await load(); while !Task.isCancelled { do { try await Task.sleep(for: .seconds(15)) } catch { return }; await load(quiet: true) } }
        .refreshable { await load() }
        .errorAlert($error)
    }

    @ViewBuilder private var rowsSection: some View {
        Section {
            ForEach(rows, id: \.["player"].string) { row in
                SuiteChatSilenceRow(row: row, manage: manage,
                    unmute: { unmuting = target(row) },
                    edit: { editing = target(row) })
            }
            if rows.isEmpty {
                ContentUnavailableView(loaded ? (activeOnly ? "Niemand hat den Chat stummgeschaltet" : "Keine Einträge") : "Lädt …", systemImage: "bell",
                    description: Text("Spieler nutzen im Spiel /chat – oder du hilfst ihnen hier."))
            }
        } header: { Text(activeOnly ? "Aktive Chat-Ruhen" : "Alle Einträge") }
    }

    private func target(_ row: JSON) -> SuiteChatSilenceTarget {
        SuiteChatSilenceTarget(id: row["player"].string, name: row["name"].string, whispers: row["whispers"].bool, mentions: row["mentions"].bool, active: row["active"].bool)
    }

    private func load(quiet: Bool = false) async {
        do { data = try await suite.request("chat-silence"); loaded = true }
        catch { if !quiet && !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }

    private func send(_ body: [String: JSON]) async {
        do {
            var payload = body
            payload["confirmed"] = .bool(true)
            data = try await suite.post("chat-silence", payload)
            Feedback.success()
        } catch { self.error = error.localizedDescription }
    }
}

struct SuiteChatSilenceHeader: View {
    let data: JSON
    private var active: Int { data["active"].int }
    private var activeOnline: Int { data["items"].array.filter { $0["active"].bool && $0["online"].bool }.count }

    var body: some View {
        Section {
            HStack(spacing: 14) {
                Image(systemName: active > 0 ? "bell.slash.fill" : "bell.fill")
                    .font(.system(size: 28)).foregroundStyle(.white).frame(width: 54, height: 54)
                    .background(LinearGradient(colors: [active > 0 ? Color.orange : Color.green, Color.panelAccent], startPoint: .topLeading, endPoint: .bottomTrailing), in: RoundedRectangle(cornerRadius: 15))
                VStack(alignment: .leading, spacing: 3) {
                    Text(active == 1 ? "1 Spieler hat den Chat stummgeschaltet" : "\(active) Spieler haben den Chat stummgeschaltet").font(.headline)
                    Text("\(activeOnline) davon online · \(data["online"].int) Spieler online").font(.subheadline).foregroundStyle(.secondary)
                }
            }.padding(.vertical, 4)
            if !data["packetFilter"].string.isEmpty {
                LabeledContent("Nachrichtenfilter", value: data["packetFilter"].string == "idle" ? "bereit" : data["packetFilter"].string + " Kanäle aktiv")
            }
        }
    }
}

struct SuiteChatSilenceRow: View {
    let row: JSON
    let manage: Bool
    let unmute: () -> Void
    let edit: () -> Void

    private var avatarRow: JSON {
        .object(["id": row["player"], "online": row["online"], "client": .string(row["bedrock"].bool ? "BEDROCK" : "JAVA")])
    }
    private var detail: String {
        var parts = [SuiteChatSilenceText.remaining(active: row["active"].bool, until: row["until"].double)]
        let origin = SuiteChatSilenceText.origin(row["by"].string)
        if !origin.isEmpty { parts.append(origin) }
        if let since = SuitePlayerPresentation.date(row["since"]) { parts.append(SuiteRelativeTime.text(since)) }
        return parts.joined(separator: " · ")
    }

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            SuitePlayerAvatar(row: avatarRow, size: 38)
            VStack(alignment: .leading, spacing: 5) {
                Text(row["name"].string).font(.headline)
                Text(detail).font(.caption).foregroundStyle(.secondary)
                tags
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 2)
        .swipeActions(edge: .trailing) {
            if manage && row["active"].bool { Button("Aufheben", systemImage: "bell.fill", action: unmute).tint(.green) }
            if manage { Button("Bearbeiten", systemImage: "slider.horizontal.3", action: edit).tint(.orange) }
        }
        .contextMenu {
            if manage && row["active"].bool { Button("Chat wieder einschalten", systemImage: "bell.fill", action: unmute) }
            if manage { Button(row["active"].bool ? "Dauer & Optionen ändern" : "Stummschalten", systemImage: "bell.slash", action: edit) }
        }
    }

    private var tags: some View {
        HStack(spacing: 6) {
            PowerBotTag(text: row["active"].bool ? "Chat stumm" : "Chat sichtbar", tint: row["active"].bool ? .orange : .green)
            PowerBotTag(text: row["online"].bool ? (row["bedrock"].bool ? "Bedrock" : "Java") : "Offline", tint: row["online"].bool ? .blue : .secondary)
            if row["whispers"].bool { PowerBotTag(text: "Flüstern stumm", tint: .purple) }
            if row["mentions"].bool { PowerBotTag(text: "Erwähnungen stumm", tint: .pink) }
        }
    }
}

struct SuiteChatSilenceEditor: View {
    let target: SuiteChatSilenceTarget
    let save: ([String: JSON]) async -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var minutes = 180
    @State private var whispers = false
    @State private var mentions = false
    @State private var notify = true
    @State private var busy = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker("Dauer", selection: $minutes) {
                        ForEach(SuiteChatSilenceText.presets, id: \.0) { preset in Text(preset.1).tag(preset.0) }
                    }.pickerStyle(.segmented)
                } header: { Text("Chat stummschalten") } footer: {
                    Text("\(target.name) sieht danach keine Chatnachrichten anderer Spieler und Plugins und hört keine Chat-Töne. Befehle, Action-Bar und Titel funktionieren weiter.")
                }
                Section {
                    Toggle("Flüsternachrichten stummschalten", isOn: $whispers)
                    Toggle("Erwähnungen stummschalten", isOn: $mentions)
                } footer: { Text("Ohne diese Schalter kommen Flüsternachrichten und Erwähnungen mit vollem Namen weiterhin an.") }
                Section { Toggle("Spieler im Spiel informieren", isOn: $notify) } footer: { Text("Auf Deutsch oder Englisch – je nach Sprache des Spielers.") }
                Section {
                    Button { Task { await run(["operation": .string("mute"), "minutes": .number(Double(minutes)), "whispers": .bool(whispers), "mentions": .bool(mentions)]) } } label: {
                        Label("Stummschalten", systemImage: "bell.slash.fill").frame(maxWidth: .infinity)
                    }.buttonStyle(.borderedProminent).tint(.orange)
                    if target.active {
                        Button { Task { await run(["operation": .string("options"), "whispers": .bool(whispers), "mentions": .bool(mentions)]) } } label: {
                            Label("Nur Optionen speichern", systemImage: "checkmark").frame(maxWidth: .infinity)
                        }
                        Button { Task { await run(["operation": .string("unmute")]) } } label: {
                            Label("Chat wieder einschalten", systemImage: "bell.fill").frame(maxWidth: .infinity)
                        }.tint(.green)
                    }
                }
            }
            .panelScreen()
            .navigationTitle(target.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } } }
            .disabled(busy)
            .onAppear { whispers = target.whispers; mentions = target.mentions }
        }
    }

    private func run(_ body: [String: JSON]) async {
        busy = true; defer { busy = false }
        var payload = body
        payload["player"] = .string(target.id)
        payload["notify"] = .bool(notify)
        await save(payload)
        dismiss()
    }
}

/// Search any player, online or offline.
struct SuiteChatSilencePlayerPicker: View {
    let suite: SuiteAPI
    let pick: (String, String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var search = ""
    @State private var rows: [JSON] = []

    var body: some View {
        NavigationStack {
            List {
                ForEach(rows, id: \.["id"].string) { row in
                    Button { pick(row["id"].string, row["name"].string); dismiss() } label: {
                        HStack(spacing: 10) {
                            SuitePlayerAvatar(row: row, size: 32)
                            Text(row["name"].string).font(.headline)
                            Spacer()
                            PowerBotTag(text: row["online"].bool ? "Online" : "Offline", tint: row["online"].bool ? .green : .secondary)
                        }
                    }.buttonStyle(.plain)
                }
                if rows.isEmpty { Text(search.isEmpty ? "Spieler werden geladen …" : "Keine Treffer").foregroundStyle(.secondary) }
            }
            .panelScreen()
            .navigationTitle("Spieler wählen")
            .navigationBarTitleDisplayMode(.inline)
            .searchable(text: $search, prompt: "Name – online und offline")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } } }
            .task(id: search) {
                if !search.isEmpty { do { try await Task.sleep(for: .milliseconds(250)) } catch { return } }
                let found = (try? await suite.data("players", query: ["q": search, "limit": "50"]))?["items"].array ?? []
                rows = found.sorted { a, b in a["online"].bool != b["online"].bool ? a["online"].bool : a["name"].string.localizedStandardCompare(b["name"].string) == .orderedAscending }
            }
        }
    }
}

struct SuiteChatSilenceHelp: View {
    var body: some View {
        Section("So funktioniert es") {
            Label("Spieler tippen im Spiel /chat – Java bekommt einen nativen Dialog, Bedrock ein Formular mit Vorschau.", systemImage: "gamecontroller.fill")
            Label("Keine Chatnachrichten und keine Chat-Töne. Wer selbst schreibt, erhält einen Hinweis mit dem Weg zurück.", systemImage: "bell.slash.fill")
            Label("Erwähnungen mit vollem Namen und Flüsternachrichten kommen an, solange sie nicht ebenfalls stummgeschaltet sind.", systemImage: "at")
            Label("Wer einen stummgeschalteten Spieler erwähnt – auch nur mit sechs Buchstaben des Namens –, bekommt einen Hinweis.", systemImage: "info.circle.fill")
        }
        .font(.subheadline)
    }
}
