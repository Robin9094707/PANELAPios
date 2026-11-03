import SwiftUI

// MARK: - Confirmed Suite actions (prepare → summary → execute)

/// Admin Suite 9.9: shows the server's own summary of a change before it is executed (LuckPerms, Lands access).
@MainActor final class SuiteConfirmRunner: ObservableObject {
    @Published var busy = false
    @Published var error: String?
    @Published var notice = ""
    @Published var plan: JSON = .null
    @Published var title = ""
    let suite: SuiteAPI
    init(_ suite: SuiteAPI) { self.suite = suite }
    var asking: Bool { plan != .null }
    func ask(_ path: String, _ values: [String: JSON], title: String) async {
        busy = true; defer { busy = false }
        do { let next = try await suite.prepare(path, values: values); self.title = title; plan = next }
        catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }
    /// Executes the prepared change; true when the server accepted it.
    func confirm() async -> Bool {
        let ticket = plan["ticket"].string
        plan = .null
        busy = true; defer { busy = false }
        do {
            let reply = try await suite.execute(ticket)
            if reply["ok"] == .bool(false) { error = reply["description"].string.isEmpty ? reply["title"].string : reply["description"].string; return false }
            notice = reply["title"].string
            Feedback.success()
            try? await Task.sleep(for: .milliseconds(900))
            return true
        } catch {
            if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription }
            return false
        }
    }
    /// Server summaries use a little Markdown (**bold**, `code`); alerts show plain text.
    nonisolated static func plain(_ text: String) -> String { text.replacingOccurrences(of: "**", with: "").replacingOccurrences(of: "`", with: "") }
}

private struct SuiteConfirmModifier: ViewModifier {
    @ObservedObject var runner: SuiteConfirmRunner
    let done: () async -> Void
    func body(content: Content) -> some View {
        content
            .alert(runner.title, isPresented: Binding(get: { runner.asking }, set: { if !$0 { runner.plan = .null } })) {
                Button("Abbrechen", role: .cancel) { runner.plan = .null }
                Button("Ausführen") { Task { if await runner.confirm() { await done() } } }
            } message: { Text(SuiteConfirmRunner.plain(runner.plan["summary"].string)) }
            .errorAlert($runner.error)
    }
}
extension View {
    func suiteConfirm(_ runner: SuiteConfirmRunner, done: @escaping () async -> Void) -> some View { modifier(SuiteConfirmModifier(runner: runner, done: done)) }
}

/// Minecraft colour codes and simple tags removed for display (LuckPerms prefixes).
enum SuiteRankText {
    static func plain(_ text: String) -> String {
        var value = text.replacingOccurrences(of: "[&§][0-9a-fk-orxA-FK-ORX]", with: "", options: .regularExpression)
        value = value.replacingOccurrences(of: "<[^>]{1,24}>", with: "", options: .regularExpression)
        value = value.replacingOccurrences(of: "\\{#[0-9a-fA-F]{6}\\}", with: "", options: .regularExpression)
        return value.trimmingCharacters(in: .whitespaces)
    }
}

// MARK: - Admin log: what counts as unread

struct SuiteAdminLogFilterView: View {
    let suite: SuiteAPI
    let overview: JSON
    let done: () async -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var mode = "staff"
    @State private var admins: Set<String> = []
    @State private var categories: Set<String> = []
    @State private var views = false
    @State private var busy = false
    @State private var error: String?
    static let modes: [(String, String)] = [("staff", "Nur Team"), ("selected", "Ausgewählte"), ("all", "Alle"), ("none", "Aus")]
    static let categoryNames: [(String, String)] = [("admin", "Verwaltung & Teleport"), ("bans", "Banns"), ("mutes", "Mutes"), ("punishments", "Strafen"), ("vanish", "Vanish"), ("devices", "Geräte"), ("moderation", "Moderation"), ("other", "Sonstiges")]
    var body: some View {
        NavigationStack {
            Form {
                modeSection
                if mode == "selected" { adminSection }
                categorySection
                Section { Toggle("Reine Ansichten mitzählen", isOn: $views) } footer: { Text("Gilt nur für dich – App und Webpanel teilen diese Einstellung. Bark- und Browser-Push für Admin-Aktionen folgen demselben Filter.") }
            }
            .panelScreen().navigationTitle("Ungelesen-Zähler").navigationBarTitleDisplayMode(.inline).errorAlert($error)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Sichern") { Task { await save() } }.disabled(busy) }
            }
            .onAppear(perform: restore)
        }
    }
    private var modeSection: some View {
        Section {
            Picker("Zähler", selection: $mode) { ForEach(Self.modes, id: \.0) { Text($0.1).tag($0.0) } }.pickerStyle(.segmented)
        } footer: { Text(Self.explain(mode)) }
    }
    private var adminSection: some View {
        Section("Admins") {
            ForEach(overview["admins"].array, id: \.["admin"].string) { row in
                Toggle(isOn: Binding(get: { admins.contains(row["admin"].string) }, set: { on in if on { admins.insert(row["admin"].string) } else { admins.remove(row["admin"].string) } })) {
                    HStack {
                        Text(row["name"].string)
                        Text(row["staff"].bool ? "Team" : "Spieler").font(.caption2.bold()).foregroundStyle(row["staff"].bool ? Color.panelAccent : .secondary)
                    }
                }.disabled(row["self"].bool)
            }
        }
    }
    private var categorySection: some View {
        Section {
            ForEach(Self.categoryNames, id: \.0) { item in
                Toggle(item.1, isOn: Binding(get: { categories.contains(item.0) }, set: { on in if on { categories.insert(item.0) } else { categories.remove(item.0) } }))
            }
        } header: { Text("Nur diese Kategorien") } footer: { Text("Keine Auswahl = alle Kategorien.") }
    }
    static func explain(_ mode: String) -> String {
        switch mode {
        case "all": return "Jede protokollierte Aktion erhöht den Zähler."
        case "selected": return "Nur die ausgewählten Admins erhöhen den Zähler."
        case "none": return "Kein Zähler und keine Hinweise für Admin-Aktionen. Das Protokoll bleibt vollständig."
        default: return "Nur Aktionen des Teams (App, Konsole, Discord, OP- und LuckPerms-Admins im Spiel) zählen. Aktionen normaler Spieler bleiben sichtbar, aber still."
        }
    }
    private func restore() {
        let settings = overview["settings"]
        mode = settings["mode"].string.isEmpty ? "staff" : settings["mode"].string
        admins = Set(settings["admins"].array.map(\.string))
        categories = Set(settings["categories"].array.map(\.string))
        views = settings["views"].bool
    }
    private func save() async {
        busy = true; defer { busy = false }
        do {
            let payload: [String: JSON] = ["mode": .string(mode), "admins": .array(admins.sorted().map { JSON.string($0) }), "categories": .array(categories.sorted().map { JSON.string($0) }), "views": .bool(views)]
            _ = try await suite.post("admin-log/settings", payload)
            Feedback.success()
            await done()
            dismiss()
        } catch { self.error = error.localizedDescription }
    }
}

// MARK: - Username and public name

struct SuiteAccountNameView: View {
    let suite: SuiteAPI
    @State private var account: JSON = .null
    @State private var username = ""
    @State private var password = ""
    @State private var publicName = ""
    @State private var busy = false
    @State private var error: String?
    @State private var notice = ""
    var isUser: Bool { account["kind"].string == "user" }
    var body: some View {
        Form {
            if isUser { renameSection }
            publicSection
            if !notice.isEmpty { Section { Label(notice, systemImage: "checkmark.circle.fill").foregroundStyle(.green) } }
        }
        .panelScreen().navigationTitle("Name & Anzeige").navigationBarTitleDisplayMode(.inline).errorAlert($error)
        .task { await load() }
    }
    private var renameSection: some View {
        Section {
            LabeledContent("Aktuell", value: account["username"].string)
            TextField("Neuer Benutzername", text: $username).textContentType(.username).textInputAutocapitalization(.never).autocorrectionDisabled()
            SecureField("Dein Passwort zur Bestätigung", text: $password).textContentType(.password)
            Button { Task { await rename() } } label: { HStack { Spacer(); if busy { ProgressView() } else { Text("Benutzernamen ändern").bold() }; Spacer() } }
                .disabled(busy || password.isEmpty || cleaned.isEmpty || cleaned == account["username"].string)
        } header: { Text("Benutzername (Login)") } footer: {
            Text("Passwort, 2FA, Passkeys, Rechte, Benachrichtigungen und alle angemeldeten Geräte bleiben erhalten. Diese App bleibt angemeldet. Notfalls in der Serverkonsole: sas app rename <alt> <neu>.")
        }
    }
    private var publicSection: some View {
        Section {
            TextField(account["publicDefault"].string.isEmpty ? "Team" : account["publicDefault"].string, text: $publicName).autocorrectionDisabled()
            Button("Öffentlichen Namen sichern", systemImage: "checkmark") { Task { await savePublic() } }.disabled(busy || !suite.features.contains("public-name-v1"))
        } header: { Text("Öffentlicher Name") } footer: {
            Text("Spieler sehen diesen Namen bei Ticketantworten (Spiel und Discord). Leer = „" + (account["publicDefault"].string.isEmpty ? "Team" : account["publicDefault"].string) + "“. Dein Login-Name wird nie angezeigt.")
        }
    }
    private var cleaned: String { username.trimmingCharacters(in: .whitespaces).lowercased() }
    private func load() async {
        do { account = try await suite.request("account"); publicName = account["publicName"].string }
        catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }
    private func rename() async {
        busy = true; defer { busy = false }
        do {
            let reply = try await suite.post("account/rename", ["username": .string(cleaned), "password": .string(password)])
            password = ""; username = ""; notice = reply["description"].string; Feedback.success()
            _ = try? await suite.health()
            await load()
        } catch { self.error = error.localizedDescription }
    }
    private func savePublic() async {
        busy = true; defer { busy = false }
        do { let reply = try await suite.post("account/public-name", ["name": .string(publicName.trimmingCharacters(in: .whitespaces))]); notice = reply["description"].string; Feedback.success() }
        catch { self.error = error.localizedDescription }
    }
}

// MARK: - LuckPerms

struct SuiteLuckPermsView: View {
    let suite: SuiteAPI
    @StateObject private var runner: SuiteConfirmRunner
    @State private var data: JSON = .null
    @State private var lookup = ""
    @State private var newGroup = ""
    @State private var askGroup = false
    init(suite: SuiteAPI) { self.suite = suite; _runner = StateObject(wrappedValue: SuiteConfirmRunner(suite)) }
    var canEdit: Bool { suite.scope == "full" && suite.can("action.luckperms.edit") }
    var body: some View {
        List {
            if data != .null && !data["installed"].bool {
                ContentUnavailableView("LuckPerms ist nicht installiert", systemImage: "crown", description: Text("Mit LuckPerms verwaltest du hier Gruppen, Ränge und Rechte."))
            } else {
                playerSection
                groupSection
                teamSection
                trackSection
            }
        }
        .panelScreen().navigationTitle("Ränge & Rechte").task { await load() }.refreshable { await load() }
        .toolbar { Button("Neue Gruppe", systemImage: "plus") { askGroup = true }.disabled(!canEdit || !data["installed"].bool) }
        .alert("Neue Gruppe", isPresented: $askGroup) {
            TextField("name (a–z, 0–9, _ -)", text: $newGroup).textInputAutocapitalization(.never).autocorrectionDisabled()
            Button("Abbrechen", role: .cancel) { newGroup = "" }
            Button("Weiter") { let name = newGroup.lowercased(); newGroup = ""; Task { await runner.ask("luckperms.edit", ["operation": .string("group-create"), "group": .string(name)], title: "Gruppe anlegen?") } }
        }
        .suiteConfirm(runner) { await load() }
    }
    private var playerSection: some View {
        Section("Spieler verwalten") {
            SuiteLivePlayerField(suite: suite, title: "Spieler suchen – Teil des Namens genügt", text: $lookup)
            NavigationLink { SuiteLuckPermsPlayerView(suite: suite, player: lookup.trimmingCharacters(in: .whitespaces)) } label: { Label("Öffnen", systemImage: "person.crop.circle") }.disabled(lookup.trimmingCharacters(in: .whitespaces).isEmpty)
        }
    }
    private var groupSection: some View {
        Section("Gruppen") {
            if data == .null { ProgressView() }
            ForEach(data["groups"].array, id: \.["name"].string) { group in
                NavigationLink { SuiteLuckPermsGroupView(suite: suite, name: group["name"].string) } label: { SuiteLuckPermsGroupRow(group: group) }
            }
        }
    }
    @ViewBuilder private var teamSection: some View {
        if !data["staff"].array.isEmpty {
            Section("Team") {
                ForEach(data["staff"].array, id: \.["uuid"].string) { member in
                    NavigationLink { SuiteLuckPermsPlayerView(suite: suite, player: member["uuid"].string) } label: {
                        LabeledContent { Text(member["primary"].string) } label: { Label(member["name"].string, systemImage: member["online"].bool ? "person.fill.checkmark" : "person") }
                    }
                }
            }
        }
    }
    @ViewBuilder private var trackSection: some View {
        if !data["tracks"].array.isEmpty {
            Section("Tracks") {
                ForEach(data["tracks"].array, id: \.["name"].string) { track in
                    VStack(alignment: .leading, spacing: 2) { Text(track["name"].string).font(.subheadline.bold()); Text(track["groups"].array.map(\.string).joined(separator: " → ")).font(.caption).foregroundStyle(.secondary) }
                }
            }
        }
    }
    func load() async { do { data = try await suite.data("luckperms") } catch { if !RequestFailure.isCancellation(error) { runner.error = error.localizedDescription } } }
}

struct SuiteLuckPermsGroupRow: View {
    let group: JSON
    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "crown.fill").foregroundStyle(Color.panelAccent).frame(width: 26)
            VStack(alignment: .leading, spacing: 2) {
                Text(group["displayName"].string.isEmpty ? group["name"].string : group["displayName"].string).font(.subheadline.weight(.semibold))
                if !group["prefix"].string.isEmpty || !group["suffix"].string.isEmpty { SuiteMinecraftPreview(prefix: group["prefix"].string, name: "Name", suffix: group["suffix"].string).fixedSize(horizontal: false, vertical: true) }
                Text(detail).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer(minLength: 4)
            Text("Gewicht " + group["weight"].string).font(.caption2.monospacedDigit()).foregroundStyle(.secondary)
        }
    }
    var detail: String {
        let parents = group["parents"].array.map(\.string)
        var parts: [String] = []
        parts.append(group["permissions"].string + " Rechte")
        if !parents.isEmpty { parts.append("erbt " + parents.joined(separator: ", ")) }
        return parts.joined(separator: " · ")
    }
}

/// Shared form for a new permission (optional duration and context).
struct SuitePermissionForm: View {
    let disabled: Bool
    let submit: (String, Bool, String, String) -> Void
    @State private var node = ""
    @State private var deny = false
    @State private var duration = ""
    @State private var context = ""
    var body: some View {
        TextField("Recht, z. B. essentials.fly", text: $node).textInputAutocapitalization(.never).autocorrectionDisabled()
        TextField("Dauer (leer = dauerhaft, z. B. 7d)", text: $duration).textInputAutocapitalization(.never).autocorrectionDisabled()
        TextField("Kontext (optional, world=farmwelt)", text: $context).textInputAutocapitalization(.never).autocorrectionDisabled()
        Toggle("Verweigern (false)", isOn: $deny)
        Button("Recht setzen", systemImage: "plus") { submit(node.trimmingCharacters(in: .whitespaces), deny, duration.trimmingCharacters(in: .whitespaces), context.trimmingCharacters(in: .whitespaces)); node = ""; duration = ""; context = ""; deny = false }
            .disabled(disabled || node.trimmingCharacters(in: .whitespaces).isEmpty)
    }
}

struct SuiteNodeRow: View {
    let node: JSON
    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 6) {
                Image(systemName: node["value"] == .bool(false) ? "minus.circle.fill" : "checkmark.circle.fill").foregroundStyle(node["value"] == .bool(false) ? Color.orange : Color.green).font(.caption)
                Text(node["key"].string).font(.footnote.monospaced()).lineLimit(2)
            }
            if !caption.isEmpty { Text(caption).font(.caption2).foregroundStyle(.secondary) }
        }
    }
    var caption: String {
        var parts: [String] = []
        if node["expiry"].double > 0 { parts.append("bis " + Date(timeIntervalSince1970: node["expiry"].double / 1000).formatted(date: .abbreviated, time: .shortened)) }
        if !node["context"].string.isEmpty { parts.append(node["context"].string) }
        return parts.joined(separator: " · ")
    }
    /// A single context (server=… / world=…) can be passed back to LuckPerms when removing the node.
    static func context(_ node: JSON) -> String { let c = node["context"].string; return c.contains(",") ? "" : c.replacingOccurrences(of: " ", with: "") }
}

struct SuiteLuckPermsGroupView: View {
    let suite: SuiteAPI
    let name: String
    @StateObject private var runner: SuiteConfirmRunner
    @State private var group: JSON = .null
    @State private var all: [JSON] = []
    @State private var edit: String?
    @State private var value = ""
    @State private var affix: String?
    @State private var known: [JSON] = []
    init(suite: SuiteAPI, name: String) { self.suite = suite; self.name = name; _runner = StateObject(wrappedValue: SuiteConfirmRunner(suite)) }
    var canEdit: Bool { suite.scope == "full" && suite.can("action.luckperms.edit") }
    var permissions: [JSON] { group["nodes"].array.filter { $0["key"].string.range(of: "^(group|prefix|suffix|weight|displayname|meta)\\.", options: .regularExpression) == nil } }
    var body: some View {
        List {
            infoSection
            parentSection
            Section("Recht hinzufügen") { SuitePermissionSuggestForm(disabled: !canEdit || runner.busy, known: known) { node, deny, duration, context in ask(["operation": .string(duration.isEmpty ? "group-permission-set" : "group-permission-temp"), "node": .string(node), "value": .string(deny ? "false" : "true"), "duration": .string(duration), "context": .string(context)], "Recht „" + node + "“ setzen?") } }
            nodeSection
            memberSection
            if name != "default" { Section { Button("Gruppe löschen", systemImage: "trash", role: .destructive) { ask(["operation": .string("group-delete")], "Gruppe „" + name + "“ löschen?") }.disabled(!canEdit) } }
        }
        .panelScreen().navigationTitle(group["displayName"].string.isEmpty ? name : group["displayName"].string).task { await load() }.refreshable { await load() }
        .alert(edit ?? "", isPresented: Binding(get: { edit != nil }, set: { if !$0 { edit = nil } })) {
            TextField("Wert", text: $value).autocorrectionDisabled().textInputAutocapitalization(.never)
            Button("Abbrechen", role: .cancel) { edit = nil }
            Button("Weiter") { submitEdit() }
        }
        .sheet(isPresented: Binding(get: { affix != nil }, set: { if !$0 { affix = nil } })) {
            let kind = affix ?? "prefix"
            SuiteAffixEditor(title: kind == "suffix" ? "Suffix" : "Präfix", kind: kind, value: group[kind].string, other: group[kind == "suffix" ? "prefix" : "suffix"].string) { text in
                ask(["operation": .string(kind == "suffix" ? "group-suffix" : "group-prefix"), "value": .string(text), "priority": .string(String(max(1, group["weight"].int)))], text.isEmpty ? (kind == "suffix" ? "Suffix entfernen?" : "Präfix entfernen?") : (kind == "suffix" ? "Suffix setzen?" : "Präfix setzen?"))
            }
        }
        .suiteConfirm(runner) { await load() }
    }
    private var infoSection: some View {
        Section {
            SuiteMinecraftPreview(prefix: group["prefix"].string, name: "Steve", suffix: group["suffix"].string, message: ": Hallo!")
            LabeledContent("Gewicht") { Button(group["weight"].string) { value = group["weight"].string; edit = "Gewicht" }.disabled(!canEdit) }
            LabeledContent("Präfix") { Button(SuiteRankText.plain(group["prefix"].string).isEmpty ? "–" : SuiteRankText.plain(group["prefix"].string)) { affix = "prefix" }.disabled(!canEdit) }
            LabeledContent("Suffix") { Button(SuiteRankText.plain(group["suffix"].string).isEmpty ? "–" : SuiteRankText.plain(group["suffix"].string)) { affix = "suffix" }.disabled(!canEdit) }
            LabeledContent("Anzeigename") { Button(group["displayName"].string.isEmpty ? "–" : group["displayName"].string) { value = group["displayName"].string; edit = "Anzeigename" }.disabled(!canEdit) }
            LabeledContent("Mitglieder", value: group["memberCount"].int >= 0 ? group["memberCount"].string : "?")
        } header: { Text(name) } footer: { Text("Präfix und Suffix mit Farbpalette und Vorschau bearbeiten. Änderungen übernimmt LuckPerms selbst.") }
    }
    private var parentSection: some View {
        Section("Erbt von") {
            ForEach(group["parents"].array.map(\.string), id: \.self) { parent in
                Text(parent).swipeActions { Button("Entfernen", role: .destructive) { ask(["operation": .string("group-parent-remove"), "value": .string(parent)], "„" + parent + "“ nicht mehr erben?") }.disabled(!canEdit) }
            }
            Menu {
                ForEach(all.filter { $0["name"].string != name && !group["parents"].array.map(\.string).contains($0["name"].string) }, id: \.["name"].string) { other in
                    Button(other["name"].string) { ask(["operation": .string("group-parent-add"), "value": other["name"]], "„" + other["name"].string + "“ erben?") }
                }
            } label: { Label("Elterngruppe hinzufügen", systemImage: "plus") }.disabled(!canEdit)
        }
    }
    private var nodeSection: some View {
        Section {
            ForEach(permissions, id: \.["key"].string) { node in
                SuiteNodeRow(node: node).swipeActions { Button("Entfernen", role: .destructive) { ask(["operation": .string("group-permission-unset"), "node": node["key"], "context": .string(SuiteNodeRow.context(node))], "Recht „" + node["key"].string + "“ entfernen?") }.disabled(!canEdit) }
                    .contextMenu { Button("Entfernen", systemImage: "trash", role: .destructive) { ask(["operation": .string("group-permission-unset"), "node": node["key"], "context": .string(SuiteNodeRow.context(node))], "Recht „" + node["key"].string + "“ entfernen?") }.disabled(!canEdit) }
            }
        } header: { Text("Rechte (\(permissions.count))") } footer: { Text("Nach links wischen oder lange drücken zum Entfernen.") }
    }
    @ViewBuilder private var memberSection: some View {
        if !group["members"].array.isEmpty {
            Section("Mitglieder") {
                ForEach(group["members"].array, id: \.["uuid"].string) { member in
                    NavigationLink { SuiteLuckPermsPlayerView(suite: suite, player: member["uuid"].string) } label: { Label(member["name"].string, systemImage: member["online"].bool ? "person.fill.checkmark" : "person") }
                }
            }
        }
    }
    private func ask(_ values: [String: JSON], _ title: String) {
        var payload = values; payload["group"] = .string(name)
        Task { await runner.ask("luckperms.edit", payload, title: title) }
    }
    private func submitEdit() {
        let field = edit ?? ""; edit = nil
        switch field {
        case "Gewicht": ask(["operation": .string("group-weight"), "value": .string(value.trimmingCharacters(in: .whitespaces))], "Gewicht ändern?")
        case "Präfix": ask(["operation": .string("group-prefix"), "value": .string(value), "priority": .string(String(max(1, group["weight"].int)))], value.isEmpty ? "Präfix entfernen?" : "Präfix setzen?")
        default: ask(["operation": .string("group-displayname"), "value": .string(value.trimmingCharacters(in: .whitespaces))], "Anzeigename ändern?")
        }
    }
    func load() async {
        do { group = try await suite.data("luckperms", query: ["group": name]); all = try await suite.data("luckperms")["groups"].array }
        catch { if !RequestFailure.isCancellation(error) { runner.error = error.localizedDescription } }
        if known.isEmpty && suite.features.contains("luckperms-v2") { known = (try? await suite.data("luckperms-permissions"))?["items"].array ?? [] }
    }
}

struct SuiteLuckPermsPlayerView: View {
    let suite: SuiteAPI
    let player: String
    @StateObject private var runner: SuiteConfirmRunner
    @State private var user: JSON = .null
    @State private var all: JSON = .null
    @State private var pick = ""
    @State private var duration = ""
    @State private var affix: String?
    @State private var known: [JSON] = []
    init(suite: SuiteAPI, player: String) { self.suite = suite; self.player = player; _runner = StateObject(wrappedValue: SuiteConfirmRunner(suite)) }
    var canEdit: Bool { suite.scope == "full" && suite.can("action.luckperms.edit") }
    var who: String { user["name"].string.isEmpty ? player : user["name"].string }
    /// Own prefix/suffix node of the player (not inherited from a group).
    func own(_ kind: String) -> Bool { user["nodes"].array.contains { $0["key"].string.hasPrefix(kind + ".") } }
    var body: some View {
        List {
            Section {
                SuiteMinecraftPreview(prefix: user["prefix"].string, name: who, suffix: user["suffix"].string, message: ": Hallo!")
                LabeledContent("Primäre Gruppe", value: user["primary"].string)
                LabeledContent("Status", value: user["online"].bool ? "Online" : "Offline")
                if suite.features.contains("luckperms-v2") {
                    Button(own("prefix") ? "Eigenes Präfix ändern" : "Eigenes Präfix setzen", systemImage: "textformat") { affix = "prefix" }.disabled(!canEdit)
                    Button(own("suffix") ? "Eigenes Suffix ändern" : "Eigenes Suffix setzen", systemImage: "textformat.abc") { affix = "suffix" }.disabled(!canEdit)
                }
                if suite.features.contains("access-locks-v1") { NavigationLink { SuiteAccessLockPlayerView(suite: suite, player: user["uuid"].string.isEmpty ? player : user["uuid"].string) } label: { Label("Rechte & Sperren", systemImage: "lock.shield.fill") }.disabled(!suite.can("data.access-locks")) }
            } header: { Text(who) } footer: { Text("Ein eigenes Präfix überschreibt das Gruppenpräfix nur für diesen Spieler; leer speichern = wieder das Gruppenpräfix.") }
            groupSection
            addGroupSection
            trackSection
            Section("Recht hinzufügen") { SuitePermissionSuggestForm(disabled: !canEdit || runner.busy, known: known) { node, deny, time, context in ask(["operation": .string(time.isEmpty ? "user-permission-set" : "user-permission-temp"), "node": .string(node), "value": .string(deny ? "false" : "true"), "duration": .string(time), "context": .string(context)], "Recht „" + node + "“ für " + who + " setzen?") } }
            Section("Eigene Rechte") {
                if user["nodes"].array.isEmpty { Text("Keine – alles kommt aus den Gruppen.").foregroundStyle(.secondary) }
                ForEach(user["nodes"].array, id: \.["key"].string) { node in
                    SuiteNodeRow(node: node).swipeActions { Button("Entfernen", role: .destructive) { ask(["operation": .string("user-permission-unset"), "node": node["key"], "context": .string(SuiteNodeRow.context(node))], "Recht entfernen?") }.disabled(!canEdit) }
                }
            }
        }
        .panelScreen().navigationTitle(who).navigationBarTitleDisplayMode(.inline).task { await load() }.refreshable { await load() }
        .sheet(isPresented: Binding(get: { affix != nil }, set: { if !$0 { affix = nil } })) {
            let kind = affix ?? "prefix"
            SuiteAffixEditor(title: kind == "suffix" ? "Eigenes Suffix" : "Eigenes Präfix", kind: kind, value: own(kind) ? user[kind].string : "", other: user[kind == "suffix" ? "prefix" : "suffix"].string, name: who) { text in
                ask(["operation": .string(kind == "suffix" ? "user-suffix" : "user-prefix"), "value": .string(text), "priority": .string("1000")], (kind == "suffix" ? "Suffix" : "Präfix") + " von " + who + (text.isEmpty ? " entfernen?" : " setzen?"))
            }
        }
        .suiteConfirm(runner) { await load() }
    }
    private var groupSection: some View {
        Section("Gruppen") {
            ForEach(user["groups"].array, id: \.["key"].string) { group in
                HStack {
                    Text(group["group"].string).bold(group["group"].string == user["primary"].string)
                    Spacer()
                    if group["expiry"].double > 0 { Text("bis " + Date(timeIntervalSince1970: group["expiry"].double / 1000).formatted(date: .abbreviated, time: .omitted)).font(.caption).foregroundStyle(.secondary) }
                }.swipeActions { Button("Entfernen", role: .destructive) { ask(["operation": .string("user-parent-remove"), "group": group["group"]], who + " aus „" + group["group"].string + "“ entfernen?") }.disabled(!canEdit) }
            }
        }
    }
    private var addGroupSection: some View {
        Section {
            Picker("Gruppe", selection: $pick) { ForEach(all["groups"].array, id: \.["name"].string) { Text($0["name"].string).tag($0["name"].string) } }
            TextField("Dauer (leer = dauerhaft, z. B. 30d)", text: $duration).textInputAutocapitalization(.never).autocorrectionDisabled()
            Button("Hinzufügen", systemImage: "plus") { ask(["operation": .string(duration.isEmpty ? "user-parent-add" : "user-parent-temp"), "group": .string(pick), "duration": .string(duration)], who + " zu „" + pick + "“ hinzufügen?") }.disabled(!canEdit || pick.isEmpty)
            Button("Als einzige Gruppe setzen", systemImage: "checkmark.circle") { ask(["operation": .string("user-parent-set"), "group": .string(pick)], "Alle Gruppen von " + who + " durch „" + pick + "“ ersetzen?") }.disabled(!canEdit || pick.isEmpty)
        } header: { Text("Gruppe vergeben") }
    }
    @ViewBuilder private var trackSection: some View {
        if !all["tracks"].array.isEmpty {
            Section("Befördern") {
                ForEach(all["tracks"].array, id: \.["name"].string) { track in
                    HStack {
                        Text(track["name"].string)
                        Spacer()
                        Button("Hoch", systemImage: "arrow.up.circle") { ask(["operation": .string("user-promote"), "track": track["name"]], who + " auf „" + track["name"].string + "“ befördern?") }.buttonStyle(.borderless).disabled(!canEdit)
                        Button("Runter", systemImage: "arrow.down.circle") { ask(["operation": .string("user-demote"), "track": track["name"]], who + " auf „" + track["name"].string + "“ zurückstufen?") }.buttonStyle(.borderless).disabled(!canEdit)
                    }
                }
            }
        }
    }
    private func ask(_ values: [String: JSON], _ title: String) {
        var payload = values; payload["player"] = .string(user["uuid"].string.isEmpty ? player : user["uuid"].string)
        Task { await runner.ask("luckperms.edit", payload, title: title) }
    }
    func load() async {
        do {
            user = try await suite.data("luckperms", query: ["player": player])
            all = try await suite.data("luckperms")
            if pick.isEmpty { pick = all["groups"].array.first?["name"].string ?? "" }
        } catch { if !RequestFailure.isCancellation(error) { runner.error = error.localizedDescription } }
        if known.isEmpty && suite.features.contains("luckperms-v2") { known = (try? await suite.data("luckperms-permissions"))?["items"].array ?? [] }
    }
}

// MARK: - Lands

struct SuiteLandsView: View {
    let suite: SuiteAPI
    @StateObject private var runner: SuiteConfirmRunner
    @State private var data: JSON = .null
    @State private var search = ""
    init(suite: SuiteAPI) { self.suite = suite; _runner = StateObject(wrappedValue: SuiteConfirmRunner(suite)) }
    var canEdit: Bool { suite.scope == "full" && suite.can("action.lands.access") && data["luckperms"].bool }
    var lands: [JSON] { data["lands"].array.filter { search.isEmpty || ($0["name"].string + " " + $0["ownerName"].string).localizedCaseInsensitiveContains(search) } }
    var body: some View {
        List {
            if data != .null && !data["installed"].bool {
                ContentUnavailableView("Lands ist nicht installiert", systemImage: "map", description: Text("Mit dem Lands-Plugin siehst du hier alle Länder und steuerst den Admin-Zugriff."))
            } else {
                summarySection
                adminSection
                landSection
            }
        }
        .panelScreen().navigationTitle("Lands").searchable(text: $search, prompt: "Land oder Besitzer")
        .task { await load() }.refreshable { await load() }
        .suiteConfirm(runner) { await load() }
    }
    private var summarySection: some View {
        Section {
            LabeledContent("Länder", value: data["total"].string)
            LabeledContent("Admins mit Bypass", value: String(data["admins"].array.filter { $0["bypass"].bool }.count))
            LabeledContent("Lands-Admins", value: String(data["admins"].array.filter { $0["admin"].bool }.count))
            if !data["error"].string.isEmpty { Text(data["error"].string).font(.caption).foregroundStyle(.orange) }
        } footer: { Text("lands.bypass.* = überall bauen und öffnen · lands.admin.* = Lands-Adminbefehle. Vergabe über LuckPerms.") }
    }
    private var adminSection: some View {
        Section("Admin-Zugriff") {
            ForEach(data["admins"].array, id: \.["uuid"].string) { member in
                VStack(alignment: .leading, spacing: 6) {
                    Label(member["name"].string, systemImage: member["online"].bool ? "person.fill.checkmark" : "person").font(.subheadline.weight(.semibold))
                    Toggle("Bypass (alle Länder)", isOn: Binding(get: { member["bypass"].bool }, set: { on in change(member, "lands.bypass.*", on) })).disabled(!canEdit || runner.busy)
                    Toggle("Lands-Admin", isOn: Binding(get: { member["admin"].bool }, set: { on in change(member, "lands.admin.*", on) })).disabled(!canEdit || runner.busy)
                }
            }
        }
    }
    private var landSection: some View {
        Section("Länder") {
            ForEach(lands, id: \.["id"].string) { land in
                VStack(alignment: .leading, spacing: 3) {
                    HStack { Text(land["display"].string.isEmpty ? land["name"].string : land["display"].string).font(.subheadline.weight(.semibold)); Spacer(); if !land["level"].string.isEmpty { Text(land["level"].string).font(.caption2).foregroundStyle(.secondary) } }
                    Text("Besitzer " + land["ownerName"].string + " · " + land["members"].string + " Mitglieder · " + land["chunks"].string + " Chunks · Kasse " + String(Int(land["balance"].double))).font(.caption).foregroundStyle(.secondary)
                }
            }
        }
    }
    private func change(_ member: JSON, _ node: String, _ on: Bool) {
        let title = (on ? "Zugriff vergeben: " : "Zugriff entziehen: ") + member["name"].string
        Task { await runner.ask("lands.access", ["player": member["uuid"], "node": .string(node), "operation": .string(on ? "grant" : "revoke")], title: title) }
    }
    func load() async { do { data = try await suite.data("lands") } catch { if !RequestFailure.isCancellation(error) { runner.error = error.localizedDescription } } }
}
