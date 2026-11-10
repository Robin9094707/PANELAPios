import SwiftUI

// MARK: - PowerBot-Zentrale

/// 5.6.0: cases with the full conversation, per-player management and editable system prompts.
/// Works with older suites too: without `powerbot-cases-v2` the list shows what the server sends.
struct SuitePowerBotView: View {
    let suite: SuiteAPI
    @State private var section = "cases"
    @State private var data: JSON = .null
    @State private var filter = "important"
    @State private var search = ""
    @State private var busy = false
    @State private var error: String?

    /// Kept for callers that still read the labels from here.
    static var kinds: [String: String] { PowerBotPresentation.kinds }
    private static let filters: [(String, String, String)] = [
        ("important", "Wichtig", "star.fill"), ("open", "Offen", "tray.full.fill"), ("blocked", "Blockiert", "hand.raised.fill"),
        ("conflicts", "Streits", "flame.fill"), ("warnings", "Hinweise", "exclamationmark.bubble.fill"), ("mutes", "Chatpausen", "speaker.slash.fill"),
        ("system", "System", "gearshape.fill"), ("all", "Alles", "list.bullet")]
    var v2: Bool { suite.features.contains("powerbot-cases-v2") }

    var body: some View {
        List {
            Section {
                PowerBotStatusHeader(data: data, v2: v2)
                if v2 {
                    Picker("Bereich", selection: $section) {
                        Text("Fälle").tag("cases")
                        Text("Spieler").tag("players")
                    }.pickerStyle(.segmented).listRowBackground(Color.clear)
                }
            }
            if section == "players" && v2 {
                SuitePowerBotPlayersSection(suite: suite, query: search, manage: data["manage"].bool)
            } else {
                if suite.features.contains("powerbot-escalation-v1") {
                    PowerBotLiveSections(data: data) { operation, fields in await change(operation, fields) }
                }
                Section {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(Self.filters.filter { v2 || ["important", "open", "mutes", "all"].contains($0.0) }, id: \.0) { key, title, symbol in
                                PowerBotChip(title: title, symbol: symbol, selected: filter == key) { filter = key }
                            }
                        }.padding(.vertical, 2)
                    }
                }.listRowBackground(Color.clear).listRowInsets(EdgeInsets(top: 0, leading: 16, bottom: 0, trailing: 16))
                if data != .null && data["items"].array.isEmpty {
                    ContentUnavailableView(search.isEmpty ? "Keine Einträge" : "Keine Treffer", systemImage: "checkmark.shield",
                                           description: Text(search.isEmpty ? "In diesem Bereich hat PowerBot zuletzt nichts unternommen." : "Suche nach Spieler, Grund oder Nachricht."))
                        .listRowBackground(Color.clear)
                }
                ForEach(PlayerTimelineModel.days(data["items"].array), id: \.0) { day, rows in
                    Section(day) {
                        ForEach(rows, id: \.["id"].string) { row in
                            NavigationLink { SuitePowerBotCaseView(suite: suite, initial: row) } label: { PowerBotCaseRow(row: row) }
                                .swipeActions(edge: .leading) {
                                    if data["manage"].bool && row["case"].bool && !row["resolved"].bool {
                                        Button("Abschließen", systemImage: "checkmark") { Task { await change("resolve", ["id": row["id"]]) } }.tint(.green)
                                    }
                                }
                                .swipeActions(edge: .trailing) {
                                    if data["manage"].bool && row["case"].bool && !row["resolved"].bool && row["assignee"].string.isEmpty {
                                        Button("Übernehmen", systemImage: "person.badge.plus") { Task { await change("claim", ["id": row["id"]]) } }.tint(.blue)
                                    }
                                }
                        }
                    }
                }
            }
        }
        .panelScreen()
        .navigationTitle("PowerBot-Zentrale")
        .searchable(text: $search, prompt: section == "players" ? "Spieler suchen" : "Spieler, Grund oder Nachricht")
        .toolbar {
            if data["canSettings"].bool {
                ToolbarItem(placement: .topBarTrailing) {
                    NavigationLink { SuitePowerBotSettingsView(suite: suite, initial: data) { await load() } } label: { Label("Einstellungen", systemImage: "slider.horizontal.3") }
                }
            }
        }
        .task(id: filter + "|" + search) {
            if !search.isEmpty { do { try await Task.sleep(for: .milliseconds(300)) } catch { return } }
            await load()
            // 5.9.0: running disputes and chat pauses change quickly – refresh while the centre is open.
            guard suite.features.contains("powerbot-escalation-v1") else { return }
            while !Task.isCancelled {
                do { try await Task.sleep(for: .seconds(10)) } catch { return }
                if !busy { await load() }
            }
        }
        .refreshable { await load() }
        .disabled(busy)
        .errorAlert($error)
    }

    private func load() async {
        let requested = filter, query = search
        do {
            let result = try await suite.request("powerbot", query: ["filter": requested, "q": query])
            if !Task.isCancelled && requested == filter && query == search { withAnimation { data = result } }
        } catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }

    private func change(_ operation: String, _ fields: [String: JSON]) async {
        guard !busy else { return }
        busy = true; defer { busy = false }
        do {
            _ = try await suite.post("powerbot", fields.merging(["operation": .string(operation), "confirmed": .bool(true)]) { current, _ in current })
            Feedback.success(); await load()
        } catch { self.error = error.localizedDescription }
    }
}

struct PowerBotChip: View {
    let title: String
    let symbol: String
    let selected: Bool
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Label(title, systemImage: symbol).font(.subheadline.weight(.semibold)).padding(.horizontal, 12).padding(.vertical, 7)
                .background(selected ? Color.panelAccent : Color.secondary.opacity(0.14), in: Capsule())
                .foregroundStyle(selected ? Color.white : Color.primary)
        }.buttonStyle(.plain).accessibilityAddTraits(selected ? .isSelected : [])
    }
}

private struct PowerBotStatusHeader: View {
    let data: JSON
    let v2: Bool
    var status: String { data["status"].string }
    var tint: Color { status == "active" ? .green : status == "off" ? .secondary : .orange }
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 14) {
                Image(systemName: "shield.lefthalf.filled").font(.system(size: 26, weight: .semibold)).foregroundStyle(.white)
                    .frame(width: 52, height: 52).background(LinearGradient(colors: [tint, Color.panelAccent], startPoint: .topLeading, endPoint: .bottomTrailing), in: RoundedRectangle(cornerRadius: 15))
                VStack(alignment: .leading, spacing: 3) {
                    Text(PowerBotPresentation.statuses[status] ?? "Lädt …").font(.headline)
                    HStack(spacing: 6) {
                        Text("\(data["open"].int) offene Fälle")
                        if v2, let level = PowerBotPresentation.strictness.first(where: { $0.0 == data["strictness"].string }) { Text("·"); Text(level.1) }
                    }.font(.subheadline).foregroundStyle(.secondary)
                }
            }
            if v2 && data["stats"] != .null {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 92), spacing: 8)], spacing: 8) {
                    stat("Blockiert", data["stats"]["blocked"].int, "hand.raised.fill", .red)
                    stat("Hinweise", data["stats"]["warnings"].int, "exclamationmark.bubble.fill", .orange)
                    stat("Streits", data["stats"]["conflicts"].int, "flame.fill", .orange)
                    stat("Beruhigt", data["stats"]["deescalations"].int, "bubble.left.and.text.bubble.right.fill", .green)
                    stat("Chatpausen", data["stats"]["mutes"].int, "speaker.slash.fill", .red)
                    if data["stats"]["disputeWarnings"] != .null { stat("Verwarnt", data["stats"]["disputeWarnings"].int, "exclamationmark.triangle.fill", .orange) }
                    if data["stats"]["deepReviews"] != .null { stat("Tiefenprüfung", data["stats"]["deepReviews"].int, "magnifyingglass", .purple) }
                    stat("Spieler", data["stats"]["players"].int, "person.2.fill", Color.panelAccent)
                }
                Text("Letzte 24 Stunden").font(.caption2).foregroundStyle(.secondary)
            }
        }.padding(.vertical, 6)
    }
    private func stat(_ title: String, _ value: Int, _ symbol: String, _ tint: Color) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Image(systemName: symbol).font(.caption).foregroundStyle(tint)
            Text("\(value)").font(.title3.bold().monospacedDigit())
            Text(title).font(.caption2).foregroundStyle(.secondary).lineLimit(1)
        }.frame(maxWidth: .infinity, alignment: .leading).padding(10).background(tint.opacity(0.09), in: RoundedRectangle(cornerRadius: 12))
    }
}

struct PowerBotCaseRow: View {
    let row: JSON
    var body: some View {
        let kind = row["kind"].string, severity = row["severity"].string
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: PowerBotPresentation.symbol(kind)).font(.body.weight(.semibold)).foregroundStyle(.white)
                .frame(width: 36, height: 36).background(PowerBotPresentation.tint(kind, severity: severity).gradient, in: RoundedRectangle(cornerRadius: 10))
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    Text(PowerBotPresentation.title(kind)).font(.subheadline.weight(.semibold))
                    if !severity.isEmpty { PowerBotTag(text: PowerBotPresentation.severity(severity), tint: PowerBotPresentation.severityTint(severity)) }
                    Spacer(minLength: 0)
                    if let date = SuitePlayerPresentation.date(row["at"]) { Text(SuiteRelativeTime.text(date)).font(.caption2).foregroundStyle(.secondary) }
                }
                let who = [row["name"].string, row["source"].string].filter { !$0.isEmpty }.joined(separator: " · ")
                if !who.isEmpty { Text(who).font(.caption.weight(.medium)).foregroundStyle(.secondary) }
                let reason = row["reason"].string.isEmpty ? row["detail"].string : row["reason"].string
                if !reason.isEmpty { Text(reason).font(.callout).lineLimit(2) }
                if !row["message"].string.isEmpty {
                    Text("„" + row["message"].string + "“").font(.caption).italic().foregroundStyle(.secondary).lineLimit(1)
                } else if row["messageHidden"].bool {
                    Label("Private Nachricht", systemImage: "lock.fill").font(.caption).foregroundStyle(.secondary)
                }
                HStack(spacing: 6) {
                    if row["active"].bool { PowerBotTag(text: "Chatpause aktiv", tint: .red) }
                    if row["case"].bool {
                        if row["resolved"].bool { PowerBotTag(text: "Abgeschlossen", tint: .green) }
                        else if !row["assignee"].string.isEmpty { PowerBotTag(text: "Bei " + row["assignee"].string, tint: .blue) }
                        else { PowerBotTag(text: "Offen", tint: .orange) }
                    }
                    if row["contextCount"].int > 0 { Label("\(row["contextCount"].int)", systemImage: "bubble.left.and.bubble.right").font(.caption2).foregroundStyle(.secondary) }
                }
            }
        }.padding(.vertical, 3)
    }
}

struct PowerBotTag: View {
    let text: String
    let tint: Color
    var body: some View {
        Text(text).font(.caption2.weight(.semibold)).padding(.horizontal, 7).padding(.vertical, 2).background(tint.opacity(0.16), in: Capsule()).foregroundStyle(tint)
    }
}

// MARK: - Case detail

struct SuitePowerBotCaseView: View {
    let suite: SuiteAPI
    let initial: JSON
    @State private var data: JSON = .null
    @State private var note = ""
    @State private var busy = false
    @State private var confirm: String?
    @State private var error: String?
    var row: JSON { data == .null ? initial : data }
    var player: String { row["player"].string }

    var body: some View {
        List {
            Section { header }
            let reason = row["reason"].string
            if !reason.isEmpty || !row["detail"].string.isEmpty {
                Section("Warum PowerBot eingegriffen hat") {
                    if !reason.isEmpty { Label { Text(reason).textSelection(.enabled) } icon: { Image(systemName: "sparkles").foregroundStyle(Color.panelAccent) } }
                    if !row["detail"].string.isEmpty && row["detail"].string != reason { Text(row["detail"].string).font(.callout).foregroundStyle(.secondary).textSelection(.enabled) }
                }
            }
            if !row["message"].string.isEmpty || row["messageHidden"].bool {
                Section(row["whisper"].bool ? "Betroffene Flüsternachricht" : "Betroffene Nachricht") {
                    if row["messageHidden"].bool { Label("Nur mit dem Recht „Private Flüsternachrichten“ sichtbar.", systemImage: "lock.fill").foregroundStyle(.secondary) }
                    else { PowerBotBubble(name: row["name"].string, text: row["message"].string, date: SuitePlayerPresentation.date(row["at"]), highlight: true, bot: false) }
                }
            }
            if !row["context"].array.isEmpty {
                Section {
                    ForEach(Array(row["context"].array.enumerated()), id: \.offset) { _, line in
                        PowerBotBubble(name: line["name"].string, text: line["text"].string, date: SuitePlayerPresentation.date(line["at"]),
                                       highlight: line["highlight"].bool, bot: line["bot"].bool, source: line["source"].string)
                    }
                } header: { Text("Chatverlauf") } footer: { Text("Markierte Nachrichten hat PowerBot als Kern des Vorfalls erkannt. Die KI-Antwort ist rechts eingerückt.") }
            } else if !row["reply"].string.isEmpty {
                Section("Antwort von PowerBot") { PowerBotBubble(name: "PowerBot", text: row["reply"].string, date: nil, highlight: false, bot: true) }
            }
            if !row["participants"].array.isEmpty {
                Section("Beteiligte") {
                    ForEach(row["participants"].array.map(\.string), id: \.self) { name in
                        let id = row["context"].array.first { $0["name"].string == name && !$0["player"].string.isEmpty }?["player"].string ?? (name == row["name"].string ? player : "")
                        if UUID(uuidString: id) != nil && suite.features.contains("powerbot-cases-v2") {
                            NavigationLink { SuitePowerBotPlayerView(suite: suite, player: id, name: name) } label: { PowerBotPlayerLabel(id: id, name: name) }
                        } else { PowerBotPlayerLabel(id: id, name: name) }
                    }
                }
            }
            if row["case"].bool || row["active"].bool || data["manage"].bool {
                Section("Bearbeitung") {
                    if row["case"].bool {
                        LabeledContent("Status", value: row["resolved"].bool ? "Abgeschlossen" : row["assignee"].string.isEmpty ? "Offen" : "In Bearbeitung")
                        if !row["assignee"].string.isEmpty { LabeledContent("Zuständig", value: row["assignee"].string) }
                    }
                    if row["active"].bool, let until = SuitePlayerPresentation.date(row["until"]) {
                        LabeledContent("Chatpause bis", value: SuiteRelativeTime.clock(until) + " Uhr")
                        LabeledContent("Betroffene Konten", value: row["targetCount"].string)
                    }
                    if data["manage"].bool {
                        if row["case"].bool && !row["resolved"].bool && row["assignee"].string.isEmpty { Button("Fall übernehmen", systemImage: "person.badge.plus") { Task { await change("claim") } } }
                        if row["case"].bool && !row["resolved"].bool { Button("Fall abschließen", systemImage: "checkmark.circle") { confirm = "resolve" } }
                        if row["case"].bool && row["resolved"].bool { Button("Wieder öffnen", systemImage: "arrow.uturn.left") { Task { await change("reopen") } } }
                        if row["active"].bool { Button("Chatpause aufheben", systemImage: "speaker.wave.2.fill") { confirm = "revoke" }.foregroundStyle(.red) }
                    }
                }
            }
            if data["manage"].bool {
                Section {
                    TextField("Interne Notiz für das Team", text: $note, axis: .vertical).lineLimit(2...6)
                    Button("Notiz speichern", systemImage: "square.and.pencil") { Task { await change("note", ["note": .string(note)]) } }.disabled(note == row["note"].string)
                } header: { Text("Teamnotiz") }
            } else if !row["note"].string.isEmpty {
                Section("Teamnotiz") { Text(row["note"].string) }
            }
            if UUID(uuidString: player) != nil {
                Section("Spieler") {
                    if suite.features.contains("powerbot-cases-v2") { NavigationLink { SuitePowerBotPlayerView(suite: suite, player: player, name: row["name"].string) } label: { Label("PowerBot-Akte von " + row["name"].string, systemImage: "person.text.rectangle") } }
                    if suite.can("data.profile") {
                        if suite.features.contains("player-timeline-v1") { NavigationLink { SuitePlayerTimelineView(suite: suite, player: player, name: row["name"].string) } label: { Label("Gesamter Spielerverlauf", systemImage: "clock.arrow.circlepath") } }
                        NavigationLink { SuitePlayerDossierView(suite: suite, player: player) } label: { Label("Steckbrief öffnen", systemImage: "person.crop.rectangle") }
                    }
                }
            }
            if !data["related"].array.isEmpty {
                Section("Weitere Einträge zu " + row["name"].string) {
                    ForEach(data["related"].array, id: \.["id"].string) { other in
                        NavigationLink { SuitePowerBotCaseView(suite: suite, initial: other) } label: { PowerBotCaseRow(row: other) }
                    }
                }
            }
        }
        .panelScreen()
        .navigationTitle(PowerBotPresentation.title(row["kind"].string))
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
        .refreshable { await load() }
        .disabled(busy)
        .confirmationDialog(confirm == "revoke" ? "Chatpause aufheben?" : "Fall abschließen?", isPresented: Binding(get: { confirm != nil }, set: { if !$0 { confirm = nil } }), titleVisibility: .visible) {
            Button(confirm == "revoke" ? "Aufheben" : "Abschließen", role: confirm == "revoke" ? .destructive : nil) { if let op = confirm { Task { await change(op) } } }
            Button("Abbrechen", role: .cancel) {}
        }
        .errorAlert($error)
    }

    private var header: some View {
        let kind = row["kind"].string, severity = row["severity"].string
        return VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 12) {
                Image(systemName: PowerBotPresentation.symbol(kind)).font(.title2.weight(.semibold)).foregroundStyle(.white)
                    .frame(width: 50, height: 50).background(PowerBotPresentation.tint(kind, severity: severity).gradient, in: RoundedRectangle(cornerRadius: 14))
                VStack(alignment: .leading, spacing: 4) {
                    Text(PowerBotPresentation.title(kind)).font(.title3.bold())
                    HStack(spacing: 6) {
                        if !severity.isEmpty { PowerBotTag(text: "Schwere: " + PowerBotPresentation.severity(severity), tint: PowerBotPresentation.severityTint(severity)) }
                        if !row["source"].string.isEmpty { PowerBotTag(text: row["source"].string, tint: .secondary) }
                    }
                }
            }
            if let date = SuitePlayerPresentation.date(row["at"]) {
                Label(SuiteRelativeTime.absolute(date) + " · " + SuiteRelativeTime.text(date), systemImage: "clock").font(.caption).foregroundStyle(.secondary)
            }
            if !row["name"].string.isEmpty { PowerBotPlayerLabel(id: player, name: row["name"].string) }
            if row["score"].int > 0 { Label("\(row["score"].int) bestätigte Verstöße in den letzten 20 Minuten", systemImage: "exclamationmark.triangle").font(.caption).foregroundStyle(.orange) }
        }.padding(.vertical, 4)
    }

    private func load() async {
        guard suite.features.contains("powerbot-cases-v2"), !initial["id"].string.isEmpty else { return }
        do {
            let next = try await suite.request("powerbot/case", query: ["id": initial["id"].string])
            withAnimation { data = next }
            note = next["note"].string
        } catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }

    private func change(_ operation: String, _ extra: [String: JSON] = [:]) async {
        guard !busy else { return }
        busy = true; defer { busy = false }
        do {
            _ = try await suite.post("powerbot", extra.merging(["operation": .string(operation), "id": .string(row["id"].string), "confirmed": .bool(true)]) { current, _ in current })
            Feedback.success(); await load()
        } catch { self.error = error.localizedDescription }
    }
}

struct PowerBotPlayerLabel: View {
    let id: String
    let name: String
    var body: some View {
        HStack(spacing: 10) {
            SuitePlayerAvatar(row: .object(["id": .string(id)]), size: 28)
            Text(name.isEmpty ? "Unbekannt" : name).font(.subheadline.weight(.semibold))
        }
    }
}

/// One chat line. Highlighted lines are the core of the dispute; bot lines are indented to the right.
struct PowerBotBubble: View {
    let name: String
    let text: String
    let date: Date?
    let highlight: Bool
    let bot: Bool
    var source = ""
    var body: some View {
        HStack {
            if bot { Spacer(minLength: 40) }
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    if bot { Image(systemName: "sparkles").font(.caption2) }
                    Text(name).font(.caption.weight(.bold)).foregroundStyle(bot ? Color.panelAccent : highlight ? Color.red : Color.primary)
                    if highlight { PowerBotTag(text: "Streitpunkt", tint: .red) }
                    if !source.isEmpty && source != "Minecraft" && !bot { Text(source).font(.caption2).foregroundStyle(.secondary) }
                    Spacer(minLength: 0)
                    if let date { Text(SuiteRelativeTime.clock(date)).font(.caption2.monospacedDigit()).foregroundStyle(.secondary) }
                }
                Text(text).font(.callout).textSelection(.enabled)
            }
            .padding(10)
            .background(bot ? Color.panelAccent.opacity(0.14) : highlight ? Color.red.opacity(0.12) : Color.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 12))
            .overlay { if highlight { RoundedRectangle(cornerRadius: 12).strokeBorder(Color.red.opacity(0.45)) } }
            if !bot { Spacer(minLength: 24) }
        }
        .listRowSeparator(.hidden)
        .accessibilityElement(children: .combine)
        .accessibilityLabel((highlight ? "Markiert: " : "") + name + ": " + text)
    }
}

// MARK: - Players

private struct SuitePowerBotPlayersSection: View {
    let suite: SuiteAPI
    let query: String
    let manage: Bool
    @State private var rows: [JSON]?
    @State private var error: String?
    var body: some View {
        Section {
            if let rows {
                if rows.isEmpty { ContentUnavailableView("Keine Spieler", systemImage: "person.2", description: Text("PowerBot musste in den letzten 30 Tagen bei niemandem eingreifen.")) }
                ForEach(rows, id: \.["player"].string) { row in
                    NavigationLink { SuitePowerBotPlayerView(suite: suite, player: row["player"].string, name: row["name"].string) } label: { PowerBotPlayerRow(row: row) }
                }
            } else if let error { Label(error, systemImage: "wifi.exclamationmark").foregroundStyle(.secondary) }
            else { ProgressView() }
        } header: { Text("Spieler mit PowerBot-Einträgen") } footer: { Text("Verstöße zählen 20 Minuten. Beim Erreichen der Schwelle folgt eine automatische Chatpause.") }
        .task(id: query) {
            if !query.isEmpty { do { try await Task.sleep(for: .milliseconds(300)) } catch { return } }
            do { rows = try await suite.request("powerbot/players", query: ["q": query])["items"].array; error = nil }
            catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
        }
    }
}

private struct PowerBotPlayerRow: View {
    let row: JSON
    var body: some View {
        HStack(spacing: 12) {
            SuitePlayerAvatar(row: .object(["id": row["player"], "online": row["online"]]), size: 38)
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    Text(row["name"].string.isEmpty ? "Unbekannt" : row["name"].string).font(.headline)
                    if row["muted"].bool { PowerBotTag(text: "Chatpause", tint: .red) }
                }
                Text(row["lastDetail"].string).font(.caption).foregroundStyle(.secondary).lineLimit(2)
                HStack(spacing: 10) {
                    Label("\(row["total"].int) Einträge", systemImage: "list.bullet")
                    Label("\(row["strikes"].int)/\(max(1, row["threshold"].int)) Verstöße", systemImage: "exclamationmark.triangle").foregroundStyle(row["strikes"].int > 0 ? Color.orange : Color.secondary)
                    if let date = SuitePlayerPresentation.date(row["last"]) { Text(SuiteRelativeTime.text(date)) }
                }.font(.caption2).foregroundStyle(.secondary)
            }
        }.padding(.vertical, 2)
    }
}

/// Everything PowerBot recorded about one player plus manual actions.
struct SuitePowerBotPlayerView: View {
    let suite: SuiteAPI
    let player: String
    let name: String
    @State private var data: JSON = .null
    @State private var summary: JSON = .null
    @State private var muteMinutes: Int?
    @State private var reason = ""
    @State private var confirm: String?
    @State private var busy = false
    @State private var error: String?
    var manage: Bool { data["manage"].bool }
    var body: some View {
        List {
            Section {
                HStack(spacing: 14) {
                    SuitePlayerAvatar(row: .object(["id": .string(player), "online": summary["online"]]), size: 56)
                    VStack(alignment: .leading, spacing: 4) {
                        Text(name.isEmpty ? "Unbekannt" : name).font(.title3.bold())
                        if summary["muted"].bool, let until = SuitePlayerPresentation.date(summary["mutedUntil"]) { PowerBotTag(text: "Chatpause bis " + SuiteRelativeTime.clock(until) + " Uhr", tint: .red) }
                        if summary != .null {
                            let threshold = max(1, summary["threshold"].int)
                            ProgressView(value: Double(min(summary["strikes"].int, threshold)), total: Double(threshold)) {
                                Text("\(summary["strikes"].int) von \(threshold) Verstößen bis zur automatischen Chatpause").font(.caption).foregroundStyle(.secondary)
                            }.tint(summary["strikes"].int >= threshold - 1 ? .red : .orange)
                        }
                    }
                }.padding(.vertical, 4)
            }
            if manage {
                Section("Aktionen") {
                    Menu {
                        ForEach([5, 15, 30, 60, 180, 1440], id: \.self) { minutes in Button(minutes >= 60 ? "\(minutes / 60) Std." : "\(minutes) Min.") { reason = ""; muteMinutes = minutes } }
                    } label: { Label("Chatpause setzen …", systemImage: "speaker.slash") }.disabled(summary["muted"].bool)
                    if summary["muted"].bool { Button("Chatpause aufheben", systemImage: "speaker.wave.2") { confirm = "player-unmute" } }
                    Button("Verstoßzähler zurücksetzen", systemImage: "arrow.counterclockwise") { confirm = "player-reset" }.disabled(summary["strikes"].int == 0)
                }
            }
            if suite.can("data.profile") {
                Section("Spieler") {
                    if suite.features.contains("player-timeline-v1") { NavigationLink { SuitePlayerTimelineView(suite: suite, player: player, name: name) } label: { Label("Gesamter Verlauf mit Chat, Items & Kämpfen", systemImage: "clock.arrow.circlepath") } }
                    NavigationLink { SuitePlayerDossierView(suite: suite, player: player) } label: { Label("Steckbrief öffnen", systemImage: "person.crop.rectangle") }
                }
            }
            Section("PowerBot-Einträge") {
                if data != .null && data["items"].array.isEmpty { Text("Keine Einträge in den letzten 30 Tagen.").foregroundStyle(.secondary) }
                ForEach(data["items"].array, id: \.["id"].string) { row in
                    NavigationLink { SuitePowerBotCaseView(suite: suite, initial: row) } label: { PowerBotCaseRow(row: row) }
                }
            }
        }
        .panelScreen().navigationTitle(name.isEmpty ? "Spieler" : name).navigationBarTitleDisplayMode(.inline)
        .task { await load() }.refreshable { await load() }.disabled(busy).errorAlert($error)
        .alert("Chatpause setzen", isPresented: Binding(get: { muteMinutes != nil }, set: { if !$0 { muteMinutes = nil } })) {
            TextField("Grund (optional)", text: $reason)
            Button("Abbrechen", role: .cancel) {}
            Button("Setzen") { if let minutes = muteMinutes { Task { await act("player-mute", ["minutes": .number(Double(minutes)), "reason": .string(reason)]) } } }
        } message: { Text("Der Spieler wird im Chat informiert. Gilt auch für bestätigte Alts, wenn das eingestellt ist.") }
        .confirmationDialog(confirm == "player-unmute" ? "Chatpause aufheben?" : "Verstoßzähler zurücksetzen?", isPresented: Binding(get: { confirm != nil }, set: { if !$0 { confirm = nil } }), titleVisibility: .visible) {
            Button(confirm == "player-unmute" ? "Aufheben" : "Zurücksetzen") { if let op = confirm { Task { await act(op) } } }
            Button("Abbrechen", role: .cancel) {}
        }
    }
    private func load() async {
        do {
            let a = try await suite.request("powerbot", query: ["filter": "all", "player": player])
            let b = try await suite.request("powerbot/players", query: ["q": name])
            data = a
            summary = b["items"].array.first { $0["player"].string == player } ?? .object(["threshold": .number(3)])
        } catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }
    private func act(_ operation: String, _ extra: [String: JSON] = [:]) async {
        guard !busy else { return }
        busy = true; defer { busy = false }
        do {
            _ = try await suite.post("powerbot", extra.merging(["operation": .string(operation), "player": .string(player), "name": .string(name), "confirmed": .bool(true)]) { current, _ in current })
            Feedback.success(); await load()
        } catch { self.error = error.localizedDescription }
    }
}

// MARK: - Settings

struct SuitePowerBotSettingsView: View {
    let suite: SuiteAPI
    let initial: JSON
    let saved: () async -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var flags: [String: JSON] = [:]
    @State private var guidelines = ""
    @State private var strictness = "balanced"
    @State private var systemPrompt = ""
    @State private var assistantPrompt = ""
    @State private var conflictMinutes = 5
    @State private var analysisInterval = 15
    @State private var disputeSeconds = 60
    @State private var loaded = false
    @State private var saving = false
    @State private var error: String?
    var v2: Bool { suite.features.contains("powerbot-cases-v2") }
    var ladder: Bool { suite.features.contains("powerbot-escalation-v1") }

    var body: some View {
        Form {
            if v2 {
                Section {
                    ForEach(PowerBotPresentation.strictness, id: \.0) { key, title, detail in
                        Button { strictness = key } label: {
                            HStack(alignment: .top) {
                                VStack(alignment: .leading, spacing: 3) { Text(title).font(.headline); Text(detail).font(.caption).foregroundStyle(.secondary) }
                                Spacer()
                                if strictness == key { Image(systemName: "checkmark.circle.fill").foregroundStyle(Color.panelAccent) }
                            }.contentShape(Rectangle())
                        }.buttonStyle(.plain)
                    }
                } header: { Text("Wie streng ist PowerBot?") } footer: { Text("Steuert KI-Entscheidungen, ab welcher Schwere ein Streit zum Fall wird und nach wie vielen Verstößen eine automatische Chatpause folgt (gelassen 4, ausgewogen 3, streng 2).") }
            }
            ForEach(PowerBotPresentation.flagGroups, id: \.0) { group, items in
                let visible = items.filter { flags[$0.0] != nil }
                if !visible.isEmpty {
                    Section(group) {
                        ForEach(visible, id: \.0) { key, title, detail in
                            Toggle(isOn: Binding(get: { flags[key]?.bool == true }, set: { flags[key] = .bool($0) })) {
                                VStack(alignment: .leading, spacing: 2) { Text(title); Text(detail).font(.caption).foregroundStyle(.secondary) }
                            }
                        }
                        if group == "Eingreifen" && v2 && !ladder && flags["conflict-actions"]?.bool == true {
                            Stepper("Chatpause bei schwerem Streit: \(conflictMinutes) Min.", value: $conflictMinutes, in: 1...30)
                        }
                    }
                }
            }
            if ladder && flags["conflict-actions"]?.bool == true {
                PowerBotEscalationSettingsSection(interval: $analysisInterval, pauseSeconds: $disputeSeconds, longMinutes: $conflictMinutes)
            }
            if v2 {
                promptSection("Systemanweisung · Moderation", text: $systemPrompt, fallback: initial["defaultSystemPrompt"].string,
                              footer: "Grundregeln, nach denen PowerBot einzelne Nachrichten erlaubt, verwarnt oder blockiert. Sicherheitsregeln und Ausgabeformat bleiben fest eingebaut.")
                promptSection("Systemanweisung · Chat-KI", text: $assistantPrompt, fallback: initial["defaultAssistantPrompt"].string,
                              footer: "Ton und Verhalten der öffentlichen Chat-KI, wenn sie antwortet oder einen Streit beruhigt.")
            }
            Section {
                TextEditor(text: $guidelines).frame(minHeight: 90)
                Text("\(guidelines.count) / \(v2 ? 2000 : 500)").font(.caption2).foregroundStyle(guidelines.count > (v2 ? 2000 : 500) ? .red : .secondary)
            } header: { Text("Zusätzliche Serverregeln") } footer: { Text("Zum Beispiel: „Spaß-Beleidigungen unter Freunden sind okay.“") }
            if suite.features.contains("powerbot-keywords-v1") {
                Section { NavigationLink { SuitePowerBotKeywordsView(suite: suite) } label: { Label("Stichwortregeln", systemImage: "text.magnifyingglass") } }
            }
        }
        .panelScreen()
        .navigationTitle("PowerBot einstellen")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("Sichern") { Task { await save() } }.disabled(saving || !loaded)
            }
        }
        .onAppear {
            guard !loaded else { return }
            flags = initial["flags"].object
            guidelines = initial["guidelines"].string
            strictness = initial["strictness"].string.isEmpty ? "balanced" : initial["strictness"].string
            systemPrompt = initial["systemPrompt"].string
            assistantPrompt = initial["assistantPrompt"].string
            conflictMinutes = max(1, min(30, initial["conflictMuteMinutes"].int == 0 ? 5 : initial["conflictMuteMinutes"].int))
            analysisInterval = max(5, min(50, initial["escalation"]["analysisInterval"].int == 0 ? 15 : initial["escalation"]["analysisInterval"].int))
            disputeSeconds = max(30, min(600, initial["escalation"]["disputeMuteSeconds"].int == 0 ? 60 : initial["escalation"]["disputeMuteSeconds"].int))
            loaded = true
        }
        .disabled(saving)
        .errorAlert($error)
    }

    private func promptSection(_ title: String, text: Binding<String>, fallback: String, footer: String) -> some View {
        Section {
            TextEditor(text: text).font(.callout).frame(minHeight: 140)
            HStack {
                Text("\(text.wrappedValue.count) / 4000").font(.caption2).foregroundStyle(text.wrappedValue.count > 4000 ? .red : .secondary)
                Spacer()
                Button("Standard wiederherstellen") { text.wrappedValue = fallback }.font(.caption).disabled(text.wrappedValue == fallback || fallback.isEmpty)
            }
        } header: { Text(title) } footer: { Text(footer) }
    }

    private func save() async {
        saving = true; defer { saving = false }
        var body: [String: JSON] = ["operation": .string("settings"), "confirmed": .bool(true), "flags": .object(flags), "guidelines": .string(guidelines)]
        if v2 {
            body["strictness"] = .string(strictness)
            body["systemPrompt"] = .string(systemPrompt)
            body["assistantPrompt"] = .string(assistantPrompt)
            body["conflictMuteMinutes"] = .number(Double(conflictMinutes))
        }
        if ladder {
            body["analysisInterval"] = .number(Double(analysisInterval))
            body["disputeMuteSeconds"] = .number(Double(disputeSeconds))
        }
        do { _ = try await suite.post("powerbot", body); Feedback.success(); await saved(); dismiss() }
        catch { self.error = error.localizedDescription }
    }
}
