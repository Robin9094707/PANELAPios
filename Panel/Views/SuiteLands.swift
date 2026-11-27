import SwiftUI

// MARK: - Shared helpers

/// 5.17.0: runs one POST /v1/lands operation and keeps busy/error/notice state for the Länder-Zentrale.
@MainActor final class SuiteLandsRunner: ObservableObject {
    @Published var busy = false
    @Published var error: String?
    @Published var notice = ""
    let suite: SuiteAPI
    init(_ suite: SuiteAPI) { self.suite = suite }

    @discardableResult
    func run(_ body: [String: JSON]) async -> Bool {
        guard !busy else { return false }
        busy = true
        defer { busy = false }
        do {
            let result = try await suite.post("lands", body)
            notice = result["message"].string
            return true
        } catch {
            if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription }
            return false
        }
    }
}

/// A question with a destructive or confirming button.
struct SuiteLandConfirm: Identifiable {
    let id = UUID()
    let title: String
    let message: String
    let button: String
    var destructive = true
    let body: [String: JSON]
}

/// A text input (rename, enter message, tax, bank …).
struct SuiteLandPrompt: Identifiable {
    let id = UUID()
    let title: String
    let label: String
    let value: String
    var decimal = false
    let make: (String) -> [String: JSON]?
}

/// Pick a player, then (optionally) a role.
struct SuiteLandRoleRequest: Identifiable {
    let id = UUID()
    let title: String
    let roles: [JSON]
    let current: String
    let allowDefault: Bool
    let make: (String) -> [String: JSON]
}

struct SuiteLandRolePicker: View {
    let request: SuiteLandRoleRequest
    let pick: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            List {
                if request.allowDefault {
                    Button { pick(""); dismiss() } label: {
                        Label("Standardrolle von Lands", systemImage: request.current.isEmpty ? "checkmark.circle.fill" : "circle")
                    }
                }
                Section("Rollen") {
                    ForEach(SuiteLands.assignableRoles(request.roles), id: \.["id"].string) { role in
                        Button { pick(role["id"].string); dismiss() } label: { roleRow(role) }.buttonStyle(.plain)
                    }
                }
            }
            .panelScreen().navigationTitle(request.title).navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } } }
        }
    }
    private func roleRow(_ role: JSON) -> some View {
        HStack {
            Image(systemName: role["id"].string == request.current ? "checkmark.circle.fill" : "circle").foregroundStyle(Color.panelAccent)
            VStack(alignment: .leading, spacing: 2) {
                Text(role["name"].string).font(.headline)
                Text("Rang \(role["priority"].int) · \(role["flags"].array.count) Rechte · \(role["members"].int) Mitglieder").font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
        }.contentShape(Rectangle())
    }
}

struct SuiteLandRow: View {
    let land: JSON
    var showOwner = true
    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: land["type"].string == "ADMIN" ? "building.columns.fill" : "map.fill")
                .font(.headline).foregroundStyle(.white).frame(width: 36, height: 36)
                .background(Color.panelAccent.gradient, in: RoundedRectangle(cornerRadius: 10))
            VStack(alignment: .leading, spacing: 3) {
                Text(SuiteLands.displayName(land)).font(.headline)
                Text(SuiteLands.summary(land, showOwner: showOwner)).font(.caption).foregroundStyle(.secondary)
                badges
            }
        }.padding(.vertical, 2)
    }
    @ViewBuilder private var badges: some View {
        let online = land["online"].int
        let nation = land["nation"].string
        let level = land["level"].string
        if online > 0 || !nation.isEmpty || !level.isEmpty || land["inWar"].bool {
            HStack(spacing: 6) {
                if online > 0 { PowerBotTag(text: "\(online) online", tint: .green) }
                if !level.isEmpty { PowerBotTag(text: level, tint: .purple) }
                if !nation.isEmpty { PowerBotTag(text: nation, tint: .blue) }
                if land["inWar"].bool { PowerBotTag(text: "Krieg", tint: .red) }
            }
        }
    }
}

// MARK: - Dashboard

struct SuiteLandsCenterView: View {
    let suite: SuiteAPI
    @State private var data: JSON = .null
    @State private var search = ""
    @State private var sort = "chunks"
    @State private var error: String?

    var body: some View {
        if suite.features.contains(SuiteLands.feature) {
            content
        } else {
            SuiteLandsView(suite: suite)
        }
    }

    private var lands: [JSON] {
        let all = data["lands"].array
        guard !search.isEmpty else { return all }
        return all.filter { ($0["name"].string + " " + $0["ownerName"].string + " " + $0["nation"].string).localizedCaseInsensitiveContains(search) }
    }

    private var content: some View {
        List {
            if data != .null && !data["installed"].bool {
                ContentUnavailableView("Lands ist nicht installiert", systemImage: "map", description: Text("Mit dem Lands-Plugin verwaltest du hier alle Länder, Mitglieder, Rollen, Rechte und Gebiete."))
            } else {
                statsSection
                Section {
                    Picker("Sortieren", selection: $sort) { ForEach(SuiteLands.sorts, id: \.0) { Text($0.1).tag($0.0) } }
                    ForEach(lands, id: \.["id"].string) { land in
                        NavigationLink { SuiteLandDetailView(suite: suite, landId: land["id"].string, areaId: nil) } label: { SuiteLandRow(land: land) }
                    }
                    if data != .null && lands.isEmpty { Text("Keine Länder gefunden.").foregroundStyle(.secondary) }
                } header: { Text("Alle Länder (\(lands.count))") } footer: { Text("Länder gründen Spieler ingame. Die Länder eines Spielers siehst und verwaltest du auch direkt im Spielerprofil.") }
                Section {
                    NavigationLink { SuiteLandsView(suite: suite) } label: { Label("Admin-Zugriff auf alle Länder", systemImage: "key.fill") }
                } footer: { Text("lands.bypass.* und lands.admin.* für Teammitglieder – über LuckPerms.") }
            }
        }
        .panelScreen().navigationTitle("Länder").searchable(text: $search, prompt: "Land, Besitzer oder Nation")
        .task(id: sort) { await load() }.refreshable { await load() }.errorAlert($error)
    }

    @ViewBuilder private var statsSection: some View {
        let stats = data["stats"]
        Section {
            LabeledContent("Länder", value: stats["lands"].string)
            LabeledContent("Spieler in Ländern", value: stats["players"].string + " (\(stats["owners"].int) Besitzer)")
            LabeledContent("Chunks", value: stats["chunks"].string + " · \(stats["areas"].int) Gebiete")
            LabeledContent("Gerade online in Ländern", value: stats["online"].string)
            LabeledContent("Alle Landbanken", value: SuiteLands.money(stats["balance"].double))
            if stats["nations"].int > 0 { LabeledContent("Nationen", value: stats["nations"].string) }
            if !data["error"].string.isEmpty { Text(data["error"].string).font(.caption).foregroundStyle(.orange) }
        } header: { Text(data["version"].string.isEmpty ? "Übersicht" : "Übersicht · Lands " + data["version"].string) }
    }

    private func load() async {
        do { data = try await suite.request("lands", query: ["sort": sort]) }
        catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }
}

// MARK: - Land / sub-area detail

struct SuiteLandDetailView: View {
    let suite: SuiteAPI
    let landId: String
    let areaId: String?
    @StateObject private var runner: SuiteLandsRunner
    @Environment(\.dismiss) private var dismiss
    @State private var data: JSON = .null
    @State private var tab: SuiteLands.Tab = .members
    @State private var confirm: SuiteLandConfirm?
    @State private var prompt: SuiteLandPrompt?
    @State private var promptText = ""
    @State private var roleRequest: SuiteLandRoleRequest?
    @State private var playerPick: PlayerPurpose?
    @State private var lastPurpose: PlayerPurpose?
    @State private var pendingPlayer: (String, String)?

    enum PlayerPurpose: String, Identifiable { case add, ban, owner; var id: String { rawValue } }

    init(suite: SuiteAPI, landId: String, areaId: String?) {
        self.suite = suite; self.landId = landId; self.areaId = areaId
        _runner = StateObject(wrappedValue: SuiteLandsRunner(suite))
    }

    private var isArea: Bool { areaId != nil }
    private var area: String { areaId ?? "" }
    private var manage: Bool { data["manage"].bool && !suite.demo }
    private var tabs: [SuiteLands.Tab] { isArea ? [.members, .roles, .natural, .settings] : SuiteLands.Tab.allCases }
    private var title: String { isArea ? data["name"].string : SuiteLands.displayName(data) }

    var body: some View {
        List {
            if data == .null {
                HStack { ProgressView(); Text("Land wird geladen …").foregroundStyle(.secondary) }
            } else {
                header
                Section { Picker("Bereich", selection: $tab) { ForEach(tabs) { Text($0.title).tag($0) } }.pickerStyle(.segmented) }
                tabContent
            }
        }
        .panelScreen().navigationTitle(title).navigationBarTitleDisplayMode(.inline)
        .task { await load() }.refreshable { await load() }
        .errorAlert($runner.error)
        .toolbar { if manage && tab == .members { ToolbarItem(placement: .topBarTrailing) { Button("Hinzufügen", systemImage: "person.badge.plus") { pickPlayer(.add) } } } }
        .sheet(item: $playerPick, onDismiss: afterPlayerPick) { _ in SuiteChatSilencePlayerPicker(suite: suite) { id, name in pendingPlayer = (id, name) } }
        .sheet(item: $roleRequest) { request in SuiteLandRolePicker(request: request) { role in Task { if await runner.run(request.make(role)) { await load() } } } }
        .confirmationDialog(confirm?.title ?? "", isPresented: Binding(get: { confirm != nil }, set: { if !$0 { confirm = nil } }), titleVisibility: .visible, presenting: confirm) { item in
            Button(item.button, role: item.destructive ? ButtonRole.destructive : nil) { Task { var b = item.body; b["confirmed"] = .bool(true); if await runner.run(b) { await afterChange(b) } } }
            Button("Abbrechen", role: .cancel) { }
        } message: { item in Text(item.message) }
        .alert(prompt?.title ?? "", isPresented: Binding(get: { prompt != nil }, set: { if !$0 { prompt = nil } }), presenting: prompt) { item in
            TextField(item.label, text: $promptText).keyboardType(item.decimal ? .decimalPad : .default)
            Button("Abbrechen", role: .cancel) { }
            Button("Speichern") { if let b = item.make(promptText) { Task { if await runner.run(b) { await afterChange(b) } } } }
        } message: { item in Text(item.label) }
        .overlay(alignment: .bottom) { if !runner.notice.isEmpty { noticeBanner } }
    }

    private var noticeBanner: some View {
        Text(runner.notice).font(.footnote.weight(.medium)).padding(.horizontal, 14).padding(.vertical, 10)
            .glassEffect(.regular.tint(Color.green.opacity(0.2)), in: Capsule()).padding(.bottom, 12)
            .task(id: runner.notice) { do { try await Task.sleep(for: .seconds(3)) } catch { return }; runner.notice = "" }
    }

    private func payload(_ operation: String, _ extra: [String: JSON] = [:]) -> [String: JSON] {
        SuiteLands.body(operation, land: landId, area: area, extra: extra)
    }

    private func ask(_ title: String, label: String, value: String, decimal: Bool = false, make: @escaping (String) -> [String: JSON]?) {
        promptText = value
        prompt = SuiteLandPrompt(title: title, label: label, value: value, decimal: decimal, make: make)
    }

    private func pickPlayer(_ purpose: PlayerPurpose) {
        pendingPlayer = nil
        lastPurpose = purpose
        playerPick = purpose
    }

    /// Runs after the picker sheet is gone, so the next sheet or dialog can be presented safely.
    private func afterPlayerPick() {
        guard let (id, name) = pendingPlayer, let purpose = lastPurpose else { return }
        pendingPlayer = nil
        switch purpose {
        case .add:
            roleRequest = SuiteLandRoleRequest(title: "Rolle für \(name)", roles: data["roles"].array, current: "", allowDefault: true) { role in
                payload("trust", ["player": .string(id), "role": .string(role)])
            }
        case .ban:
            confirm = SuiteLandConfirm(title: "\(name) bannen?", message: "\(name) wird aus \(title) entfernt und darf es nicht mehr betreten.", button: "Bannen", body: payload("ban", ["player": .string(id)]))
        case .owner:
            confirm = SuiteLandConfirm(title: "\(name) wird Besitzer", message: "\(name) wird Besitzer von \(title). Der bisherige Besitzer bleibt Mitglied.", button: "Übertragen", destructive: false, body: payload("set-owner", ["player": .string(id)]))
        }
    }

    private func afterChange(_ sent: [String: JSON]) async {
        if sent["operation"]?.string == "delete" || sent["operation"]?.string == "area-delete" { dismiss(); return }
        await load()
    }

    private func load() async {
        do {
            if let areaId { data = try await suite.request("lands/area", query: ["land": landId, "area": areaId]) }
            else { data = try await suite.request("lands/land", query: ["id": landId]) }
        } catch { if !RequestFailure.isCancellation(error) { runner.error = error.localizedDescription } }
    }

    // MARK: header

    @ViewBuilder private var header: some View {
        Section {
            if isArea {
                LabeledContent("Land", value: data["landName"].string)
                if !SuiteLands.bounds(data).isEmpty { LabeledContent("Größe", value: SuiteLands.bounds(data)) }
                LabeledContent("Mitglieder", value: String(data["members"].array.count))
            } else {
                LabeledContent("Besitzer", value: data["ownerName"].string)
                LabeledContent("Mitglieder", value: SuiteLands.used(data["members"].array.count, of: data["maxMembers"].int))
                LabeledContent("Chunks", value: SuiteLands.used(data["chunks"].int, of: data["maxChunks"].int))
                LabeledContent("Landbank", value: SuiteLands.money(data["balance"].double))
                if !data["level"].string.isEmpty { LabeledContent("Level", value: data["level"].string) }
                if !data["nation"].string.isEmpty { LabeledContent("Nation", value: data["nation"].string) }
                if data["created"].double > 0 { LabeledContent("Gegründet") { Text(Date(timeIntervalSince1970: data["created"].double / 1000), style: .date) } }
                if !data["onlinePlayers"].array.isEmpty { SuiteDetailRow(title: "Gerade im Land", value: data["onlinePlayers"].array.map(\.string).joined(separator: ", ")) }
            }
        }
    }

    // MARK: tabs

    @ViewBuilder private var tabContent: some View {
        switch tab {
        case .members: membersSection; bansSection
        case .roles: rolesSection
        case .natural: naturalSection
        case .areas: areasSection
        case .settings: if isArea { areaSettingsSection } else { settingsSection; dangerSection }
        case .more: moreSection
        }
    }

    private var membersSection: some View {
        Section {
            ForEach(data["members"].array, id: \.["uuid"].string) { member in memberRow(member) }
            if data["members"].array.isEmpty { Text("Noch keine Mitglieder.").foregroundStyle(.secondary) }
            if manage { Button { pickPlayer(.add) } label: { Label("Spieler hinzufügen", systemImage: "person.badge.plus") } }
        } header: { Text(isArea ? "Mitglieder im Gebiet" : "Mitglieder") } footer: { Text(manage ? "Wischen oder lange drücken: Rolle ändern, entfernen, bannen." : "Ändern benötigt das Recht lands.manage.") }
    }

    private func memberRow(_ member: JSON) -> some View {
        let id = member["uuid"].string, name = member["name"].string, owner = member["owner"].bool
        return HStack(spacing: 10) {
            SuitePlayerAvatar(row: .object(["id": .string(id), "name": .string(name)]), size: 32)
            VStack(alignment: .leading, spacing: 2) {
                Text(name).font(.headline)
                Text(owner ? "Besitzer" : (member["role"].string.isEmpty ? "Mitglied" : member["role"].string)).font(.caption).foregroundStyle(owner ? Color.panelAccent : .secondary)
            }
            Spacer()
            if member["online"].bool { PowerBotTag(text: "Online", tint: .green) }
            if !isArea && member["wholeLand"] == .bool(false) { PowerBotTag(text: "nur Gebiete", tint: .orange) }
        }
        .contextMenu { if manage && !owner { memberActions(member) } }
        .swipeActions(edge: .trailing) { if manage && !owner { memberActions(member) } }
    }

    @ViewBuilder private func memberActions(_ member: JSON) -> some View {
        let id = member["uuid"].string, name = member["name"].string
        Button("Rolle ändern", systemImage: "crown.fill") {
            roleRequest = SuiteLandRoleRequest(title: "Rolle für \(name)", roles: data["roles"].array, current: member["roleId"].string, allowDefault: false) { role in
                payload("set-role", ["player": .string(id), "role": .string(role)])
            }
        }.tint(.purple)
        Button("Entfernen", systemImage: "person.badge.minus", role: .destructive) {
            confirm = SuiteLandConfirm(title: "\(name) entfernen?", message: "\(name) wird aus \(title) entfernt.", button: "Entfernen", body: payload("untrust", ["player": .string(id)]))
        }
        Button("Bannen", systemImage: "hand.raised.fill") {
            confirm = SuiteLandConfirm(title: "\(name) bannen?", message: "\(name) wird aus \(title) entfernt und darf es nicht mehr betreten.", button: "Bannen", body: payload("ban", ["player": .string(id)]))
        }.tint(.orange)
    }

    private var bansSection: some View {
        Section {
            ForEach(data["bans"].array, id: \.["uuid"].string) { ban in
                HStack {
                    Label(ban["name"].string, systemImage: "hand.raised.fill").foregroundStyle(.red)
                    Spacer()
                    if manage { Button("Entbannen") { Task { if await runner.run(payload("unban", ["player": ban["uuid"]])) { await load() } } }.buttonStyle(.bordered).disabled(runner.busy) }
                }
            }
            if data["bans"].array.isEmpty { Text("Niemand gebannt.").foregroundStyle(.secondary) }
            if manage { Button { pickPlayer(.ban) } label: { Label("Spieler bannen", systemImage: "hand.raised") } }
        } header: { Text("Gebannt") } footer: { Text("Lands führt keine Bannliste – angezeigt werden bekannte Spieler der Suite mit Bann.") }
    }

    private var rolesSection: some View {
        ForEach(data["roles"].array, id: \.["id"].string) { role in roleSection(role) }
    }

    private func roleSection(_ role: JSON) -> some View {
            SuiteLandRoleSection(role: role, catalog: data["flags"], manage: manage, busy: runner.busy,
                toggle: { flag, value in Task { if await runner.run(payload("role-flag", ["role": role["id"], "flag": .string(flag), "value": .bool(value)])) { await load() } } },
                move: { up in Task { if await runner.run(payload("role-priority", ["role": role["id"], "direction": .string(up ? "up" : "down")])) { await load() } } },
                rename: { ask("Rolle umbenennen", label: "Neuer Name", value: role["name"].string) { name in name.isEmpty ? nil : payload("role-rename", ["role": role["id"], "name": .string(name)]) } },
                delete: { confirm = SuiteLandConfirm(title: "Rolle „\(role["name"].string)“ löschen?", message: "\(role["members"].int) Mitglied(er) bekommen die Standardrolle.", button: "Löschen", body: payload("role-delete", ["role": role["id"]])) })
    }

    private var naturalSection: some View {
        Section {
            ForEach(data["flags"]["natural"].array, id: \.["name"].string) { flag in
                Toggle(isOn: Binding(get: { SuiteLands.contains(data["natural"].array, flag["name"].string) }, set: { value in
                    Task { if await runner.run(payload("natural-flag", ["flag": flag["name"], "value": .bool(value)])) { await load() } }
                })) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(flag["title"].string)
                        if !flag["description"].string.isEmpty { Text(flag["description"].string).font(.caption).foregroundStyle(.secondary) }
                    }
                }.disabled(!manage || runner.busy)
            }
        } header: { Text(isArea ? "Natur-Regeln im Gebiet" : "Natur-Regeln im Land") } footer: { Text("Monster, Feuer, TNT, Wasser- und Lavafluss, Wachstum – direkt in Lands gespeichert.") }
    }

    private var areasSection: some View {
        Section {
            ForEach(data["areaList"].array, id: \.["id"].string) { item in
                NavigationLink { SuiteLandDetailView(suite: suite, landId: landId, areaId: item["id"].string) } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        HStack { Text(item["name"].string).font(.headline); if item["default"].bool { PowerBotTag(text: "Hauptgebiet", tint: .secondary) }; if !item["tenant"].string.isEmpty { PowerBotTag(text: "Mieter " + item["tenant"].string, tint: .blue) } }
                        Text(SuiteLands.areaDetail(item)).font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
        } header: { Text("Gebiete") } footer: { Text("Neue Gebiete legen Spieler ingame an (/l wizard). Hier verwaltest du Mitglieder, Rollen, Rechte und Regeln je Gebiet.") }
    }

    private var settingsSection: some View {
        Section("Einstellungen") {
            settingRow("Name", data["name"].string) { ask("Land umbenennen", label: "Neuer Name", value: data["name"].string) { $0.isEmpty ? nil : payload("rename", ["name": .string($0)]) } }
            settingRow("Begrüßung", data["title"].string.isEmpty ? "Lands-Standard" : data["title"].string) { ask("Begrüßungstext", label: "Leer = Lands-Standard", value: data["title"].string) { payload("title", ["title": .string($0)]) } }
            settingRow("Steuer", SuiteLands.money(data["tax"].double)) { ask("Steuer", label: "Steuer pro Mitglied", value: data["tax"].string, decimal: true) { text in SuiteLands.decimal(text).map { payload("tax", ["tax": .number($0)]) } } }
            if data["balanceEditable"].bool {
                settingRow("Landbank", SuiteLands.money(data["balance"].double)) { ask("Landbank buchen", label: "Betrag (negativ = abheben)", value: "", decimal: true) { text in SuiteLands.decimal(text).map { payload("balance", ["mode": .string("add"), "amount": .number($0)]) } } }
            }
            if data["spawn"] != .null { LabeledContent("Spawn", value: data["spawn"]["world"].string + " · " + ["x", "y", "z"].map { data["spawn"][$0].string }.joined(separator: " / ")) }
            settingRow("Besitzer", data["ownerName"].string) { pickPlayer(.owner) }
        }
    }

    private func settingRow(_ label: String, _ value: String, edit: @escaping () -> Void) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) { Text(label).font(.subheadline.weight(.semibold)); Text(value).font(.caption).foregroundStyle(.secondary).lineLimit(2) }
            Spacer()
            if manage { Button("Ändern", action: edit).buttonStyle(.bordered).disabled(runner.busy) }
        }
    }

    @ViewBuilder private var dangerSection: some View {
        if data["mayDelete"].bool {
            Section {
                Button(role: .destructive) {
                    ask("Land löschen", label: "Zur Bestätigung „\(data["name"].string)“ eingeben", value: "") { typed in
                        typed.lowercased() == data["name"].string.lowercased() ? payload("delete", ["confirmName": .string(typed), "confirmed": .bool(true)]) : nil
                    }
                } label: { Label("Land löschen …", systemImage: "trash.fill") }
            } footer: { Text("Löscht das Land mit allen Claims, Gebieten und Rollen über Lands. Nicht umkehrbar.") }
        }
    }

    private var areaSettingsSection: some View {
        Section("Gebiet") {
            if !data["default"].bool { settingRow("Name", data["name"].string) { ask("Gebiet umbenennen", label: "Neuer Name", value: data["name"].string) { $0.isEmpty ? nil : payload("rename", ["name": .string($0)]) } } }
            settingRow("Steuer", SuiteLands.money(data["tax"].double)) { ask("Steuer im Gebiet", label: "Steuer", value: data["tax"].string, decimal: true) { text in SuiteLands.decimal(text).map { payload("tax", ["tax": .number($0)]) } } }
            if manage && !data["default"].bool {
                Button(role: .destructive) {
                    confirm = SuiteLandConfirm(title: "Gebiet „\(data["name"].string)“ löschen?", message: "Mitglieder und Rollen des Gebiets gehen verloren. Das Land bleibt.", button: "Löschen", body: payload("area-delete"))
                } label: { Label("Gebiet löschen", systemImage: "trash") }
            }
        }
    }

    @ViewBuilder private var moreSection: some View {
        Section("Beziehungen") {
            LabeledContent("Nation", value: data["nation"].string.isEmpty ? "–" : data["nation"].string)
            SuiteDetailRow(title: "Verbündete", value: data["allies"].array.isEmpty ? "–" : data["allies"].array.map(\.string).joined(separator: ", "))
            SuiteDetailRow(title: "Feinde", value: data["enemies"].array.isEmpty ? "–" : data["enemies"].array.map(\.string).joined(separator: ", "))
            if data["war"] != .null { LabeledContent("Kriege", value: "\(data["war"]["wins"].int) gewonnen · \(data["war"]["defeats"].int) verloren") }
        }
        Section("Posteingang") {
            ForEach(Array(data["inbox"].array.enumerated()), id: \.offset) { _, message in
                VStack(alignment: .leading, spacing: 2) {
                    Text(message["text"].string).font(.subheadline)
                    if message["time"].double > 0 { Text(Date(timeIntervalSince1970: message["time"].double / 1000), style: .date).font(.caption2).foregroundStyle(.secondary) }
                }
            }
            if data["inbox"].array.isEmpty { Text("Keine Nachrichten.").foregroundStyle(.secondary) }
        }
    }
}

/// One role with its rights as toggles (action and management rights), rank and rename/delete.
struct SuiteLandRoleSection: View {
    let role: JSON
    let catalog: JSON
    let manage: Bool
    let busy: Bool
    let toggle: (String, Bool) -> Void
    let move: (Bool) -> Void
    let rename: () -> Void
    let delete: () -> Void
    @State private var expanded = false

    private var editable: Bool { manage && !role["owner"].bool }

    var body: some View {
        Section {
            DisclosureGroup(isExpanded: $expanded) {
                ForEach(SuiteLands.roleFlagGroups(catalog), id: \.title) { group in
                    Text(group.title).font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                    ForEach(group.flags, id: \.["name"].string) { flag in
                        Toggle(flag["title"].string, isOn: Binding(get: { SuiteLands.contains(role["flags"].array, flag["name"].string) }, set: { toggle(flag["name"].string, $0) }))
                            .disabled(!editable || busy)
                    }
                }
                if editable {
                    HStack {
                        Button("Rang hoch", systemImage: "arrow.up") { move(true) }.buttonStyle(.bordered)
                        Button("Rang runter", systemImage: "arrow.down") { move(false) }.buttonStyle(.bordered)
                    }.disabled(busy)
                    if !role["visitor"].bool { Button("Umbenennen", systemImage: "pencil") { rename() }.disabled(busy) }
                    if role["deletable"].bool { Button("Rolle löschen", systemImage: "trash", role: .destructive) { delete() }.disabled(busy) }
                }
            } label: { label }
        }
    }

    private var label: some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack(spacing: 6) {
                Text(role["name"].string).font(.headline)
                if let type = SuiteLands.roleTypeTitle(role["type"].string) { PowerBotTag(text: type, tint: role["owner"].bool ? Color.panelAccent : .blue) }
            }
            Text("Rang \(role["priority"].int) · \(role["flags"].array.count) Rechte · \(role["members"].int) Mitglieder").font(.caption).foregroundStyle(.secondary)
        }
    }
}

// MARK: - Player profile

/// 5.17.0: lands of one player right in the profile – owner (main land), memberships with role and sub-areas, land bans.
struct SuitePlayerLandsSection: View {
    let suite: SuiteAPI
    let player: String
    @StateObject private var runner: SuiteLandsRunner
    @State private var data: JSON = .null
    @State private var picking = false
    @State private var pickedLand: JSON?
    @State private var roleRequest: SuiteLandRoleRequest?
    @State private var confirm: SuiteLandConfirm?

    init(suite: SuiteAPI, player: String) {
        self.suite = suite; self.player = player
        _runner = StateObject(wrappedValue: SuiteLandsRunner(suite))
    }

    private var manage: Bool { data["manage"].bool && !suite.demo }
    private var name: String { data["name"].string.isEmpty ? "Spieler" : data["name"].string }

    var body: some View {
        Section {
            if data == .null {
                HStack { ProgressView(); Text("Länder werden geladen …").foregroundStyle(.secondary) }
            } else if !data["installed"].bool {
                Label("Lands ist nicht installiert", systemImage: "map").foregroundStyle(.secondary)
            } else {
                rows
                if manage { Button { picking = true } label: { Label("Zu einem Land hinzufügen", systemImage: "plus.circle.fill") }.disabled(runner.busy) }
                if manage && data["member"].array.count > 1 {
                    Button(role: .destructive) {
                        let first = data["member"].array[0]["id"].string
                        confirm = SuiteLandConfirm(title: "Aus allen Ländern entfernen?", message: "\(name) wird aus \(data["member"].array.count) Ländern entfernt. Eigene Länder bleiben.", button: "Alle entfernen", body: SuiteLands.body("remove-everywhere", land: first, extra: ["player": .string(player)]))
                    } label: { Label("Aus allen Ländern entfernen", systemImage: "person.badge.minus") }
                }
            }
        } header: { Text("Länder") } footer: { Text(footer) }
        .task { await load() }
        .errorAlert($runner.error)
        .sheet(isPresented: $picking, onDismiss: { if let land = pickedLand { pickedLand = nil; chooseRole(land) } }) { SuiteLandPickerSheet(suite: suite, exclude: (data["owned"].array + data["member"].array).map { $0["id"].string }) { land in pickedLand = land } }
        .sheet(item: $roleRequest) { request in SuiteLandRolePicker(request: request) { role in Task { if await runner.run(request.make(role)) { await load() } } } }
        .confirmationDialog(confirm?.title ?? "", isPresented: Binding(get: { confirm != nil }, set: { if !$0 { confirm = nil } }), titleVisibility: .visible, presenting: confirm) { item in
            Button(item.button, role: .destructive) { Task { var b = item.body; b["confirmed"] = .bool(true); if await runner.run(b) { await load() } } }
            Button("Abbrechen", role: .cancel) { }
        } message: { item in Text(item.message) }
    }

    private var footer: String {
        let owned = data["owned"].array.count, member = data["member"].array.count, banned = data["banned"].array.count
        var text = "\(owned) eigene · \(member) Mitgliedschaften"
        if banned > 0 { text += " · \(banned) Land-Banns" }
        if !data["editLand"].string.isEmpty { text += " · bearbeitet gerade " + data["editLand"].string }
        return text
    }

    @ViewBuilder private var rows: some View {
        ForEach(data["owned"].array, id: \.["id"].string) { land in landLink(land, kind: "owned") }
        ForEach(data["member"].array, id: \.["id"].string) { land in
            landLink(land, kind: "member")
                .swipeActions(edge: .trailing) { if manage { memberActions(land) } }
                .contextMenu { if manage { memberActions(land) } }
        }
        ForEach(data["banned"].array, id: \.["id"].string) { land in
            landLink(land, kind: "banned")
                .swipeActions(edge: .trailing) { if manage { Button("Entbannen", systemImage: "lock.open.fill") { Task { if await runner.run(SuiteLands.body("unban", land: land["id"].string, extra: ["player": .string(player)])) { await load() } } }.tint(.green) } }
        }
        if data["owned"].array.isEmpty && data["member"].array.isEmpty && data["banned"].array.isEmpty { Text("In keinem Land.").foregroundStyle(.secondary) }
    }

    private func landLink(_ land: JSON, kind: String) -> some View {
        NavigationLink { SuiteLandDetailView(suite: suite, landId: land["id"].string, areaId: nil) } label: {
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text(SuiteLands.displayName(land)).font(.headline)
                    PowerBotTag(text: SuiteLands.membershipTitle(land, kind: kind), tint: kind == "owned" ? Color.panelAccent : kind == "banned" ? .red : .blue)
                }
                Text(SuiteLands.summary(land, showOwner: kind != "owned")).font(.caption).foregroundStyle(.secondary)
                if !land["subAreas"].array.isEmpty { Text("Gebiete: " + land["subAreas"].array.map(\.string).joined(separator: ", ")).font(.caption2).foregroundStyle(.secondary) }
            }
        }
    }

    @ViewBuilder private func memberActions(_ land: JSON) -> some View {
        Button("Entfernen", systemImage: "person.badge.minus", role: .destructive) {
            confirm = SuiteLandConfirm(title: "Aus \(land["name"].string) entfernen?", message: "\(name) wird aus \(land["name"].string) entfernt.", button: "Entfernen", body: SuiteLands.body("untrust", land: land["id"].string, extra: ["player": .string(player)]))
        }
        Button("Rolle", systemImage: "crown.fill") { Task { await roleChange(land) } }.tint(.purple)
    }

    private func roleChange(_ land: JSON) async {
        guard let detail = try? await suite.request("lands/land", query: ["id": land["id"].string]) else { return }
        roleRequest = SuiteLandRoleRequest(title: "Rolle in \(land["name"].string)", roles: detail["roles"].array, current: land["roleId"].string, allowDefault: false) { role in
            SuiteLands.body("set-role", land: land["id"].string, extra: ["player": .string(player), "role": .string(role)])
        }
    }

    private func chooseRole(_ land: JSON) {
        Task {
            guard let detail = try? await suite.request("lands/land", query: ["id": land["id"].string]) else { return }
            roleRequest = SuiteLandRoleRequest(title: "Rolle in \(land["name"].string)", roles: detail["roles"].array, current: "", allowDefault: true) { role in
                SuiteLands.body("trust", land: land["id"].string, extra: ["player": .string(player), "role": .string(role)])
            }
        }
    }

    private func load() async {
        do { data = try await suite.request("lands/player", query: ["player": player]) }
        catch { if !RequestFailure.isCancellation(error) { runner.error = error.localizedDescription } }
    }
}

struct SuiteLandPickerSheet: View {
    let suite: SuiteAPI
    let exclude: [String]
    let pick: (JSON) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var lands: [JSON] = []
    @State private var search = ""

    private var shown: [JSON] {
        lands.filter { !exclude.contains($0["id"].string) && (search.isEmpty || ($0["name"].string + " " + $0["ownerName"].string).localizedCaseInsensitiveContains(search)) }
    }

    var body: some View {
        NavigationStack {
            List {
                ForEach(shown, id: \.["id"].string) { land in
                    Button { pick(land); dismiss() } label: { SuiteLandRow(land: land) }.buttonStyle(.plain)
                }
                if shown.isEmpty { Text(lands.isEmpty ? "Länder werden geladen …" : "Kein passendes Land").foregroundStyle(.secondary) }
            }
            .panelScreen().navigationTitle("Land wählen").navigationBarTitleDisplayMode(.inline)
            .searchable(text: $search, prompt: "Land oder Besitzer")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } } }
            .task { lands = (try? await suite.request("lands", query: ["sort": "name"]))?["lands"].array ?? [] }
        }
    }
}
