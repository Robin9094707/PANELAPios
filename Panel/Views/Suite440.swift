import SwiftUI

// MARK: - Commands (Admin Suite 10.1.0 command rights)

/// One server command, parsed once per load so long lists stay smooth.
struct SuiteCommandItem: Identifiable, Equatable {
    let name: String
    let aliases: [String]
    let plugin: String
    let kind: String
    let permission: String
    let description: String
    let state: String
    let allowed: Bool
    let isException: Bool
    let note: String
    let hasOwn: Bool
    var id: String { name }

    init(_ row: JSON) {
        name = row["name"].string
        aliases = row["aliases"].array.map(\.string)
        plugin = row["plugin"].string
        kind = row["kind"].string
        permission = row["permission"].string
        description = row["description"].string
        // Overview rows only know global blocks; player rows carry the effective state.
        if row["state"] == .null {
            state = row["global"].bool ? "global" : "allowed"
            allowed = !row["global"].bool
            note = row["global"].bool ? "Für alle gesperrt · " + row["globalRemaining"].string : ""
        } else {
            state = row["state"].string
            allowed = row["allowed"].bool
            note = SuiteCommandItem.note(row)
        }
        isException = row["override"].bool
        hasOwn = row["own"] != .null
    }

    static func note(_ row: JSON) -> String {
        switch row["state"].string {
        case "blocked":
            let own = row["own"]
            var parts = ["Gesperrt · " + own["remaining"].string]
            if !own["reason"].string.isEmpty { parts.append(own["reason"].string) }
            if own["alts"].bool { parts.append("Alts erben") }
            return parts.joined(separator: " · ")
        case "inherited": return "Geerbt von " + row["inheritedFrom"].string + " · " + row["remaining"].string
        case "global": return "Für alle gesperrt · " + row["remaining"].string
        case "noperm": return "Rang erlaubt es nicht" + (row["permission"].string.isEmpty ? "" : " (" + row["permission"].string + ")")
        default: return row["override"].bool ? "Erlaubt (Ausnahme)" : ""
        }
    }

    var tint: Color {
        switch state {
        case "blocked": return .red
        case "inherited": return .purple
        case "global": return .orange
        case "noperm": return .secondary
        default: return .green
        }
    }

    func matches(_ query: String) -> Bool {
        let q = query.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: "/ "))
        if q.isEmpty { return true }
        return name.contains(q) || plugin.lowercased().contains(q) || permission.lowercased().contains(q) || aliases.contains { $0.contains(q) }
    }
}

enum SuiteCommandGrouping: String, CaseIterable, Identifiable {
    case kind, plugin, alphabet
    var id: String { rawValue }
    var title: String {
        switch self {
        case .kind: return "Art"
        case .plugin: return "Plugin"
        case .alphabet: return "A–Z"
        }
    }
    /// Groups the (already filtered) commands; kinds follow the server's order, everything else alphabetically.
    static func groups(_ items: [SuiteCommandItem], by mode: SuiteCommandGrouping, order: [String]) -> [(String, [SuiteCommandItem])] {
        var map: [String: [SuiteCommandItem]] = [:]
        for item in items {
            let key: String
            switch mode {
            case .kind: key = item.kind.isEmpty ? "Weitere" : item.kind
            case .plugin: key = item.plugin.isEmpty ? "Server" : item.plugin
            case .alphabet: key = item.name.first.map { String($0).uppercased() } ?? "#"
            }
            map[key, default: []].append(item)
        }
        let keys = map.keys.sorted { (order.firstIndex(of: $0) ?? 99, $0) < (order.firstIndex(of: $1) ?? 99, $1) }
        return keys.map { ($0, map[$0] ?? []) }
    }
}

enum SuiteCommandFilter: String, CaseIterable, Identifiable {
    case all, allowed, blocked
    var id: String { rawValue }
    var title: String {
        switch self {
        case .all: return "Alle"
        case .allowed: return "Erlaubt"
        case .blocked: return "Gesperrt"
        }
    }
    func keeps(_ item: SuiteCommandItem) -> Bool {
        switch self {
        case .all: return true
        case .allowed: return item.allowed
        case .blocked: return !item.allowed
        }
    }
}

/// A command with its switch (on = allowed).
struct SuiteCommandRow: View {
    let item: SuiteCommandItem
    @Binding var allowed: Bool
    let enabled: Bool
    var body: some View {
        Toggle(isOn: $allowed) {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text("/" + item.name).font(.subheadline.monospaced().weight(.semibold))
                    if !item.allowed { Circle().fill(item.tint).frame(width: 6, height: 6) }
                }
                Text(detail).font(.caption).foregroundStyle(item.allowed ? Color.secondary : item.tint).lineLimit(2)
            }
        }
        .tint(.green)
        .disabled(!enabled)
        .accessibilityValue(Text(allowed ? "erlaubt" : "gesperrt"))
    }
    var detail: String {
        var parts = [item.plugin]
        if !item.note.isEmpty { parts.append(item.note) }
        else if !item.description.isEmpty { parts.append(item.description) }
        return parts.joined(separator: " · ")
    }
}

/// Every command of one player with a live switch, grouped by kind, plugin or alphabet.
struct SuiteCommandPlayerView: View {
    let suite: SuiteAPI
    let player: String
    @StateObject private var runner: SuiteConfirmRunner
    @State private var data: JSON = .null
    @State private var items: [SuiteCommandItem] = []
    @State private var pending: [String: Bool] = [:]
    @State private var search = ""
    @State private var grouping = SuiteCommandGrouping.kind
    @State private var filter = SuiteCommandFilter.all
    @State private var duration = ""
    @State private var reason = ""
    @State private var alts = true
    @State private var loadError: String?
    init(suite: SuiteAPI, player: String) { self.suite = suite; self.player = player; _runner = StateObject(wrappedValue: SuiteConfirmRunner(suite)) }
    var canEdit: Bool { suite.can("action.commands.update") && suite.scope != "read" }
    var canGrant: Bool { suite.scope == "full" && suite.can("action.luckperms.edit") && data["luckperms"].bool }
    var who: String { data["name"].string.isEmpty ? player : data["name"].string }
    var playerId: String { data["uuid"].string.isEmpty ? player : data["uuid"].string }
    var durations: [String] { data["durations"].array.isEmpty ? ["30m", "2h", "12h", "1d", "3d", "7d", "14d", "30d", ""] : data["durations"].array.map(\.string) }
    var visible: [(String, [SuiteCommandItem])] {
        let shown = items.filter { filter.keeps($0) && $0.matches(search) }
        return SuiteCommandGrouping.groups(shown, by: grouping, order: data["kinds"].array.map(\.string))
    }
    var body: some View {
        List {
            summarySection
            optionSection
            if data == .null && loadError == nil { Section { ProgressView() } }
            ForEach(visible, id: \.0) { group in
                Section {
                    ForEach(group.1) { item in commandRow(item) }
                } header: { groupHeader(group.0, group.1) }
            }
            if data != .null && visible.isEmpty { Section { Text("Kein Befehl passt zur Suche.").foregroundStyle(.secondary) } }
        }
        .panelScreen().navigationTitle("Befehle · " + who).navigationBarTitleDisplayMode(.inline)
        .searchable(text: $search, prompt: "Befehl, Plugin oder Recht")
        .task { await load() }.refreshable { await load() }
        .suiteConfirm(runner) { await load() }
    }
    private var summarySection: some View {
        Section {
            HStack {
                Label("\(data["allowedCount"].int) erlaubt", systemImage: "checkmark.circle.fill").foregroundStyle(.green)
                Spacer()
                Label("\(data["blockedCount"].int) gesperrt", systemImage: "lock.fill").foregroundStyle(.red)
            }.font(.subheadline.weight(.semibold))
            if data["op"].bool { Label("Operator – Rang erlaubt fast alles; Sperren der Suite gelten trotzdem.", systemImage: "star.fill").font(.caption).foregroundStyle(.orange) }
            if data != .null && !data["permissionsKnown"].bool { Label("Offline und ohne LuckPerms: ob der Rang einen Befehl erlaubt, ist unbekannt.", systemImage: "questionmark.circle").font(.caption).foregroundStyle(.secondary) }
            Picker("Gruppieren", selection: $grouping) { ForEach(SuiteCommandGrouping.allCases) { Text($0.title).tag($0) } }.pickerStyle(.segmented)
            Picker("Anzeigen", selection: $filter) { ForEach(SuiteCommandFilter.allCases) { Text($0.title).tag($0) } }.pickerStyle(.segmented)
            if let loadError { Text(loadError).font(.caption).foregroundStyle(.red) }
        } header: { Text(who) } footer: { Text("Schalter an = erlaubt, aus = gesperrt – wird sofort gespeichert. Sperren der Suite gelten auch für Operatoren.") }
    }
    private var optionSection: some View {
        Section {
            Picker("Dauer", selection: $duration) { ForEach(durations, id: \.self) { Text(SuiteAccessLockText.duration($0)).tag($0) } }
            TextField("Grund – sieht der Spieler (optional)", text: $reason)
            Toggle("Alt-Accounts erben die Sperre", isOn: $alts)
            if !data["alts"].array.isEmpty { Text("Alt-Accounts: " + data["alts"].array.map { $0["name"].string }.joined(separator: ", ")).font(.caption).foregroundStyle(.secondary) }
            Button("Alle eigenen Befehlsrechte entfernen", systemImage: "arrow.uturn.backward", role: .destructive) {
                Task { await runner.ask("commands.update", ["player": .string(playerId), "operation": .string("clear"), "alts": .string("true"), "grant": .string("false")], title: "Alle eigenen Befehlsrechte von " + who + " entfernen?") }
            }.disabled(!canEdit || !items.contains { $0.hasOwn || $0.isException })
        } header: { Text("Beim Ausschalten gilt") } footer: { Text(canGrant ? "Schaltest du einen Befehl ein, den der Rang nicht erlaubt, wird das Recht direkt in LuckPerms gesetzt." : "Befehle, die der Rang nicht erlaubt, lassen sich nur mit Vollzugriff und LuckPerms-Recht einschalten.") }
    }
    private func commandRow(_ item: SuiteCommandItem) -> some View {
        let binding = Binding<Bool>(get: { pending[item.name] ?? item.allowed }, set: { on in change([item.name], on) })
        let enabled = canEdit && (item.state != "noperm" || canGrant)
        return SuiteCommandRow(item: item, allowed: binding, enabled: enabled)
            .swipeActions {
                if item.hasOwn || item.isException {
                    Button("Zurücksetzen") { reset(item.name) }.tint(.gray)
                }
            }
    }
    private func groupHeader(_ title: String, _ list: [SuiteCommandItem]) -> some View {
        HStack {
            Text(title)
            Text("\(list.filter(\.allowed).count)/\(list.count)").foregroundStyle(.secondary)
            Spacer()
            if canEdit {
                Menu {
                    Button("Alle erlauben", systemImage: "checkmark.circle") { change(list.filter { !$0.allowed && ($0.state != "noperm" || canGrant) }.map(\.name), true) }
                    Button("Alle sperren", systemImage: "lock", role: .destructive) { change(list.filter(\.allowed).map(\.name), false) }
                } label: { Image(systemName: "ellipsis.circle") }
            }
        }
    }
    private func change(_ names: [String], _ on: Bool) {
        guard !names.isEmpty else { return }
        for name in names { pending[name] = on }
        // 4.5: always send both choice fields – Admin Suite 10.1.0 rejected requests without them ("Pflichtfeld fehlt: grant").
        var values: [String: JSON] = ["player": .string(playerId), "commands": .string(names.joined(separator: ",")), "alts": .string(String(alts)), "grant": .string(String(on && canGrant))]
        if on {
            values["operation"] = .string("allow")
        } else {
            values["operation"] = .string("block")
            values["duration"] = .string(duration)
            values["reason"] = .string(reason.trimmingCharacters(in: .whitespaces))
        }
        Task {
            _ = await runner.apply("commands.update", values)
            await load()
            for name in names { pending[name] = nil }
        }
    }
    private func reset(_ name: String) {
        Task {
            _ = await runner.apply("commands.update", ["player": .string(playerId), "operation": .string("reset"), "commands": .string(name), "alts": .string("true"), "grant": .string("false")])
            await load()
        }
    }
    func load() async {
        do {
            let next = try await suite.data("commands", query: ["player": player])
            data = next
            items = next["items"].array.map(SuiteCommandItem.init)
            loadError = nil
        } catch { if !RequestFailure.isCancellation(error) { loadError = error.localizedDescription } }
    }
}

/// Overview: pick a player, see who has own command rights, and block commands for everyone.
struct SuiteCommandsView: View {
    let suite: SuiteAPI
    @StateObject private var runner: SuiteConfirmRunner
    @State private var data: JSON = .null
    @State private var items: [SuiteCommandItem] = []
    @State private var pending: [String: Bool] = [:]
    @State private var lookup = ""
    @State private var search = ""
    @State private var grouping = SuiteCommandGrouping.kind
    @State private var showGlobal = false
    init(suite: SuiteAPI) { self.suite = suite; _runner = StateObject(wrappedValue: SuiteConfirmRunner(suite)) }
    var canGlobal: Bool { suite.scope == "full" && suite.can("action.commands.global") }
    var visible: [(String, [SuiteCommandItem])] {
        SuiteCommandGrouping.groups(items.filter { $0.matches(search) }, by: grouping, order: data["kinds"].array.map(\.string))
    }
    var body: some View {
        List {
            Section {
                SuiteLivePlayerField(suite: suite, title: "Spieler suchen – Teil des Namens genügt", text: $lookup)
                NavigationLink { SuiteCommandPlayerView(suite: suite, player: lookup.trimmingCharacters(in: .whitespaces)) } label: { Label("Befehle dieses Spielers", systemImage: "person.crop.circle") }
                    .disabled(lookup.trimmingCharacters(in: .whitespaces).isEmpty)
            } header: { Text("Spieler") } footer: { Text("Jeder Schalter zeigt live, ob der Spieler den Befehl nutzen darf.") }
            playersSection
            Section {
                LabeledContent("Befehle auf dem Server", value: data["total"].string)
                Toggle("Für alle Spieler anzeigen", isOn: $showGlobal)
            } footer: { Text(data["help"].string) }
            if showGlobal {
                Section { Picker("Gruppieren", selection: $grouping) { ForEach(SuiteCommandGrouping.allCases) { Text($0.title).tag($0) } }.pickerStyle(.segmented) }
                ForEach(visible, id: \.0) { group in
                    Section(group.0) {
                        ForEach(group.1) { item in globalRow(item) }
                    }
                }
            }
        }
        .panelScreen().navigationTitle("Befehle")
        .searchable(text: $search, prompt: "Befehl suchen")
        .task { await load() }.refreshable { await load() }
        .suiteConfirm(runner) { await load() }
    }
    @ViewBuilder private var playersSection: some View {
        Section("Spieler mit eigenen Befehlsrechten") {
            if data == .null { ProgressView() }
            else if data["players"].array.isEmpty { Text("Noch niemand.").foregroundStyle(.secondary) }
            ForEach(data["players"].array, id: \.["uuid"].string) { row in
                NavigationLink { SuiteCommandPlayerView(suite: suite, player: row["uuid"].string) } label: { SuiteCommandPlayerSummary(row: row) }
            }
        }
    }
    private func globalRow(_ item: SuiteCommandItem) -> some View {
        let binding = Binding<Bool>(get: { pending[item.name] ?? item.allowed }, set: { on in setGlobal(item.name, on) })
        return SuiteCommandRow(item: item, allowed: binding, enabled: canGlobal && !runner.busy)
    }
    private func setGlobal(_ name: String, _ on: Bool) {
        pending[name] = on
        Task {
            _ = await runner.apply("commands.global", ["operation": .string(on ? "global-allow" : "global-block"), "commands": .string(name), "duration": .string("")])
            await load()
            pending[name] = nil
        }
    }
    func load() async {
        do { let next = try await suite.data("commands"); data = next; items = next["items"].array.map(SuiteCommandItem.init) }
        catch { if !RequestFailure.isCancellation(error) { runner.error = error.localizedDescription } }
    }
}

struct SuiteCommandPlayerSummary: View {
    let row: JSON
    var line: String {
        var parts: [String] = []
        if !row["blocked"].array.isEmpty { parts.append("\(row["blocked"].array.count) gesperrt") }
        if !row["allowed"].array.isEmpty { parts.append("\(row["allowed"].array.count) freigegeben") }
        if !row["inherited"].array.isEmpty { parts.append("\(row["inherited"].array.count) geerbt") }
        return parts.joined(separator: " · ")
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                Text(row["name"].string).font(.subheadline.weight(.semibold))
                if row["online"].bool { Circle().fill(.green).frame(width: 7, height: 7) }
            }
            Text(line).font(.caption).foregroundStyle(.secondary)
        }
    }
}

// MARK: - Rank in the player profile

/// Prefix/suffix preview, primary group and groups of a player – assign a group right from the profile.
struct SuitePlayerRankSection: View {
    let suite: SuiteAPI
    let player: String
    let name: String
    @StateObject private var runner: SuiteConfirmRunner
    @State private var rank: JSON = .null
    @State private var groups: [JSON] = []
    init(suite: SuiteAPI, player: String, name: String) { self.suite = suite; self.player = player; self.name = name; _runner = StateObject(wrappedValue: SuiteConfirmRunner(suite)) }
    var canEdit: Bool { suite.scope == "full" && suite.can("action.luckperms.edit") }
    var available: [JSON] {
        let current = Set(rank["groups"].array.map { $0["group"].string })
        return groups.filter { !current.contains($0["name"].string) }
    }
    var body: some View {
        Section {
            if rank == .null { ProgressView() } else { content }
        } header: { Text("Rang") } footer: { Text("Präfix und Suffix so, wie sie im Chat erscheinen. Gruppen nach links wischen zum Entfernen.") }
        .task { await load() }
        .suiteConfirm(runner) { await load() }
    }
    @ViewBuilder private var content: some View {
        SuiteMinecraftPreview(prefix: rank["prefix"].string, name: name, suffix: rank["suffix"].string, message: ": Hallo!")
        LabeledContent("Primäre Gruppe", value: rank["primary"].string)
        if !rank["prefix"].string.isEmpty { LabeledContent("Präfix") { Text(SuiteMinecraftText.attributed(rank["prefix"].string)).font(.footnote.monospaced()) } }
        if !rank["suffix"].string.isEmpty { LabeledContent("Suffix") { Text(SuiteMinecraftText.attributed(rank["suffix"].string)).font(.footnote.monospaced()) } }
        ForEach(rank["groups"].array, id: \.["key"].string) { group in
            SuiteRankGroupRow(group: group, primary: rank["primary"].string)
                .swipeActions { Button("Entfernen", role: .destructive) { remove(group["group"].string) }.disabled(!canEdit) }
        }
        Menu {
            ForEach(available, id: \.["name"].string) { group in
                Menu(group["name"].string) {
                    ForEach(["", "1d", "7d", "30d"], id: \.self) { time in Button(SuiteAccessLockText.duration(time)) { grant(group["name"].string, time) } }
                    Button("Als einzige Gruppe setzen", systemImage: "checkmark.circle") { replace(group["name"].string) }
                }
            }
        } label: { Label("Gruppe zuweisen …", systemImage: "plus.circle") }.disabled(!canEdit || available.isEmpty)
        NavigationLink { SuiteLuckPermsPlayerView(suite: suite, player: player) } label: { Label("Präfix, Befördern & eigene Rechte", systemImage: "crown.fill") }
    }
    private func grant(_ group: String, _ time: String) {
        let operation = time.isEmpty ? "user-parent-add" : "user-parent-temp"
        Task { await runner.ask("luckperms.edit", ["operation": .string(operation), "player": .string(player), "group": .string(group), "duration": .string(time)], title: name + " zu „" + group + "“ hinzufügen?") }
    }
    private func replace(_ group: String) {
        Task { await runner.ask("luckperms.edit", ["operation": .string("user-parent-set"), "player": .string(player), "group": .string(group)], title: "Alle Gruppen von " + name + " durch „" + group + "“ ersetzen?") }
    }
    private func remove(_ group: String) {
        Task { await runner.ask("luckperms.edit", ["operation": .string("user-parent-remove"), "player": .string(player), "group": .string(group)], title: name + " aus „" + group + "“ entfernen?") }
    }
    func load() async {
        // LuckPerms needs a moment to store a change; a short wait keeps the reloaded view accurate.
        if rank != .null { try? await Task.sleep(for: .milliseconds(700)) }
        rank = (try? await suite.data("luckperms", query: ["player": player])) ?? JSON.object(["primary": .string("–")])
        if groups.isEmpty { groups = (try? await suite.data("luckperms"))?["groups"].array ?? [] }
    }
}

// MARK: - LuckPerms templates

/// Ready-made ranks (weight, prefix, typical permissions) – creates or completes a group in one step.
struct SuiteLuckPermsTemplateSection: View {
    let templates: [JSON]
    let enabled: Bool
    let apply: (JSON, String) -> Void
    @State private var chosen: JSON = .null
    @State private var name = ""
    var body: some View {
        Section {
            ForEach(templates, id: \.["id"].string) { template in
                Button { chosen = template; name = template["id"].string } label: { SuiteLuckPermsTemplateRow(template: template) }
                    .buttonStyle(.plain).disabled(!enabled)
            }
        } header: { Text("Vorlagen") } footer: { Text("Legt die Gruppe an (oder ergänzt sie) mit Gewicht, Präfix, Erbe von default und passenden Rechten. Vorhandene Rechte bleiben erhalten.") }
        .alert("Gruppenname", isPresented: Binding(get: { chosen != .null }, set: { if !$0 { chosen = .null } })) {
            TextField("name (a–z, 0–9, _ -)", text: $name).textInputAutocapitalization(.never).autocorrectionDisabled()
            Button("Abbrechen", role: .cancel) { chosen = .null }
            Button("Weiter") { let template = chosen; chosen = .null; apply(template, name.lowercased().trimmingCharacters(in: .whitespaces)) }
        } message: { Text(chosen["explain"].string) }
    }
}

struct SuiteLuckPermsTemplateRow: View {
    let template: JSON
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(template["title"].string).font(.subheadline.weight(.semibold))
                Spacer()
                Text("Gewicht " + template["weight"].string).font(.caption2).foregroundStyle(.secondary)
            }
            SuiteMinecraftPreview(prefix: template["prefix"].string, name: "Name", suffix: "")
            Text(template["explain"].string + " · \(template["permissions"].array.count) Rechte").font(.caption).foregroundStyle(.secondary)
        }
        .contentShape(Rectangle())
    }
}

// MARK: - LuckPerms node with a live switch

/// A permission node: switch on = true (allowed), off = false (denied). Temporary nodes only show their state.
struct SuiteNodeToggleRow: View {
    let node: JSON
    @Binding var value: Bool
    let enabled: Bool
    var body: some View {
        if node["expiry"].double > 0 {
            SuiteNodeRow(node: node)
        } else {
            Toggle(isOn: $value) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(node["key"].string).font(.footnote.monospaced()).lineLimit(2)
                    if !node["context"].string.isEmpty { Text(node["context"].string).font(.caption2).foregroundStyle(.secondary) }
                }
            }
            .tint(.green)
            .disabled(!enabled)
        }
    }
}
