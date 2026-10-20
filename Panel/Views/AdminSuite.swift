import SwiftUI

@MainActor final class SuiteModel: ObservableObject {
    @Published var api: SuiteAPI?
    @Published var health: JSON = .null
    @Published var actions: [SuiteAction] = []
    @Published var error: String?
    @Published var busy = false
    let panel: PanelAPI
    let server: Server
    init(panel: PanelAPI, server: Server) { self.panel = panel; self.server = server }
    func load() async {
        guard !busy else { return }; busy = true; defer { busy = false }
        if panel.demo && ProcessInfo.processInfo.arguments.contains("--demo-suite") { do { let record = SuiteConnection(panel: panel.base.absoluteString, server: server.id, endpoint: "https://suite.demo.invalid", fingerprint: "", serverIdentity: "synthetic-suite"); let client = try SuiteAPI(record, token: "", demo: true); health = try await client.health(); actions = try await client.request("schema")["actions"].array.map { SuiteAction(data: $0) }; api = client } catch { self.error = error.localizedDescription }; return }
        guard let record = SuiteConnections.shared.get(panel, server) else { api = nil; return }
        do { let client = try SuiteAPI(record); health = try await client.health(); actions = try await client.request("schema")["actions"].array.map { SuiteAction(data: $0) }; api = client }
        catch { api = nil; self.error = error.localizedDescription }
    }
}
struct SuiteHubView: View {
    let panel: PanelAPI
    let server: Server
    @StateObject private var model: SuiteModel
    @ObservedObject private var connections = SuiteConnections.shared
    @State private var settings = false
    init(panel: PanelAPI, server: Server) { self.panel = panel; self.server = server; _model = StateObject(wrappedValue: SuiteModel(panel: panel, server: server)) }
    var body: some View {
        List {
            Section {
                HStack(spacing: 14) { Image(systemName: "shield.lefthalf.filled").font(.system(size: 40)).foregroundStyle(.teal.gradient); VStack(alignment: .leading, spacing: 4) { Text("Simplex Admin Suite").font(.title2.bold()); Text(server.name).foregroundStyle(.secondary) } }.padding(.vertical, 8)
                if model.busy { ProgressView("Sichere Verbindung prüfen …") }
                if let api = model.api {
                    Label("Verbunden · Version " + model.health["version"].string, systemImage: "lock.shield.fill").foregroundStyle(.green)
                    LabeledContent("Zugriff", value: scopeName(model.health["scope"].string))
                    CopyButton(text: api.connection.endpoint, title: "API-Adresse kopieren")
                    if let record = connections.get(panel, server) { Toggle("Moderation über Admin Suite", isOn: Binding(get: { record.useForModeration }, set: { connections.moderation(record, enabled: $0) })) }
                } else {
                    Text("Verknüpfe diesen Server mit deiner Admin Suite ab Version 8.3.0. Die Anbindung ist optional und hat einen eigenen Schlüssel.").font(.subheadline).foregroundStyle(.secondary)
                    Button("Admin Suite verbinden", systemImage: "link.badge.plus") { settings = true }.disabled(panel.demo).accessibilityIdentifier("suite-connect")
                }
            }
            if let api = model.api {
                Section("Verwalten") {
                    NavigationLink { SuitePlayersView(suite: api, panel: panel, server: server, actions: model.actions) } label: { Label("Spieler, Geräte & Alt-Accounts", systemImage: "person.2.fill") }
                    NavigationLink { SuiteUltraView(suite: api, actions: model.actions) } label: { Label("Ultrabanns & Identitätsgruppen", systemImage: "shield.slash.fill") }
                    NavigationLink { SuiteSettingsView(suite: api) } label: { Label("Suite-Einstellungen", systemImage: "slider.horizontal.3") }
                    NavigationLink { SuiteReadView(suite: api, path: "server.status", title: "Serverstatus") } label: { Label("Server & Neustartstatus", systemImage: "server.rack") }
                }
                if api.scope == "full" { Section("Vollständige Suite-Verwaltung") { NavigationLink { SuiteRemoteMenuView(suite: api) } label: { Label("Alle Suite-Menüs", systemImage: "square.grid.2x2.fill") } } }
                Section("Alle Verwaltungsbereiche") {
                    ForEach(Array(Set(model.actions.filter { $0.id != "menu.action" && $0.id != "settings.update" }.map(\.group))).sorted(), id: \.self) { group in NavigationLink { SuiteActionsView(suite: api, actions: model.actions.filter { $0.group == group && $0.id != "menu.action" && $0.id != "settings.update" }, title: group) } label: { Label(group, systemImage: symbol(group)) } }
                }
                Section { Button("Kopplung bearbeiten", systemImage: "key.horizontal") { settings = true }; Button("Verbindung aktualisieren", systemImage: "arrow.clockwise") { Task { await model.load() } } }
            }
            Section("Einrichten & rückgängig machen") {
                Text("1. Admin Suite 8.3.0 installieren; vorhandenen Plugin-Datenordner behalten.\n2. Zusätzlichen TCP-Port beim Hoster zuweisen.\n3. /sas app enable <Port>\n4. /sas app create Mein-iPhone full\n5. /sas app fingerprint\n6. HTTPS-Adresse, Schlüssel und Fingerabdruck hier eintragen.").font(.footnote.monospaced()).textSelection(.enabled)
                Text("Alternativ: /sas app im Java- oder Bedrock-Adminmenü. Abschalten mit /sas app disable; die übrige Suite läuft weiter. Die App zeigt vor Änderungen eine serverseitig gebundene Bestätigung. Ein read-Schlüssel kann ausschließlich lesen.").font(.footnote).foregroundStyle(.secondary)
            }
        }.panelScreen().navigationTitle("Admin Suite").navigationBarTitleDisplayMode(.inline).task { await model.load() }.refreshable { await model.load() }.errorAlert($model.error)
            .sheet(isPresented: $settings) { SuiteConnectionView(panel: panel, server: server) { await model.load() } }
    }
    func scopeName(_ value: String) -> String { switch value { case "full": return "Vollzugriff"; case "manage": return "Spieler & Moderation"; default: return "Nur lesen" } }
    func symbol(_ group: String) -> String { switch group { case "Spieler": return "person.crop.circle"; case "Server": return "server.rack"; case "Sicherheit": return "lock.shield"; case "Moderation": return "hand.raised.fill"; case "Einstellungen": return "gearshape"; case "KI & Integrationen": return "sparkles"; default: return "square.grid.2x2" } }
}
struct SuiteConnectionView: View {
    let panel: PanelAPI
    let server: Server
    var saved: () async -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var endpoint = ""
    @State private var fingerprint = ""
    @State private var token = ""
    @State private var busy = false
    @State private var error: String?
    @State private var remove = false
    var existing: SuiteConnection? { SuiteConnections.shared.get(panel, server) }
    var body: some View {
        NavigationStack {
            Form {
                Section { Text(server.name).font(.headline); Text("Dieser Zugang gilt nur für diesen Server in diesem Panel-Account.").font(.footnote).foregroundStyle(.secondary) }
                Section("HTTPS-Verbindung") { TextField("https://Server-IP:Port", text: $endpoint).keyboardType(.URL); SecureField("Admin-Suite-API-Schlüssel", text: $token); TextField("SHA-256-Fingerabdruck", text: $fingerprint, axis: .vertical).font(.footnote.monospaced()) }.textInputAutocapitalization(.never).autocorrectionDisabled()
                Section { Text("Bei direkt erzeugtem Zertifikat den Fingerabdruck aus /sas app fingerprint eintragen. Bei öffentlich gültigem HTTPS-Zertifikat darf er leer bleiben. Ein geänderter Fingerabdruck wird niemals automatisch akzeptiert.").font(.footnote).foregroundStyle(.secondary) }
                Section { Button { Task { await connect() } } label: { if busy { ProgressView("Prüfen …") } else { Label("Prüfen & sicher speichern", systemImage: "checkmark.shield") } }.disabled(busy || endpoint.isEmpty || token.isEmpty || panel.demo) }
                if existing != nil { Section { Button("Lokale Kopplung entfernen", role: .destructive) { remove = true }.disabled(busy) } }
            }.panelScreen().navigationTitle("Suite verbinden").navigationBarTitleDisplayMode(.inline).toolbar { ToolbarItem(placement: .cancellationAction) { Button("Schließen") { dismiss() }.disabled(busy) } }.errorAlert($error)
                .task { if let record = existing { endpoint = record.endpoint; fingerprint = record.fingerprint; token = (try? Vault.read(record.id)) ?? "" } }
                .confirmationDialog("Kopplung auf diesem Gerät entfernen?", isPresented: $remove, titleVisibility: .visible) { Button("Entfernen", role: .destructive) { if let record = existing { SuiteConnections.shared.remove(record) }; Task { await saved(); dismiss() } } } message: { Text("Der Schlüssel bleibt serverseitig gültig, bis du ihn mit /sas app revoke widerrufst.") }
        }.interactiveDismissDisabled(busy)
    }
    func connect() async {
        busy = true; defer { busy = false }
        do {
            let url = try SuiteAPI.endpoint(endpoint); let pin = try SuiteAPI.pin(fingerprint)
            guard token.hasPrefix("sas_"), token.count <= 160, !token.contains(where: \.isWhitespace) else { throw PanelError(message: "Gib den mit /sas app create erzeugten App-Schlüssel ein.") }
            var record = SuiteConnection(panel: panel.base.absoluteString, server: server.id, endpoint: url.absoluteString, fingerprint: pin, serverIdentity: "")
            if let existing { record.id = existing.id; record.useForModeration = existing.useForModeration }
            let api = try SuiteAPI(record, token: token); let health = try await api.health(); record.serverIdentity = health["serverId"].string
            try SuiteConnections.shared.save(record, token: token); Feedback.success(); await saved(); dismiss()
        } catch { self.error = error.localizedDescription }
    }
}
struct SuiteActionsView: View {
    let suite: SuiteAPI
    let actions: [SuiteAction]
    var title: String
    @State private var search = ""
    var body: some View { List { ForEach(actions.filter { search.isEmpty || ($0.title + $0.id).localizedCaseInsensitiveContains(search) }) { action in NavigationLink { SuiteActionView(suite: suite, action: action) } label: { VStack(alignment: .leading, spacing: 4) { Text(action.title); Text(action.id).font(.caption).foregroundStyle(.secondary) } } } }.panelScreen().navigationTitle(title).searchable(text: $search, prompt: "Funktion suchen") }
}
struct SuiteActionView: View {
    let suite: SuiteAPI
    let action: SuiteAction
    var preset: [String: JSON] = [:]
    @State private var values: [String: JSON] = [:]
    @State private var plan: JSON?
    @State private var result: JSON?
    @State private var error: String?
    @State private var busy = false
    @State private var choices: [JSON] = []
    @State private var choiceField: JSON?
    @State private var choiceQuery = ""
    @State private var choiceGeneration = UUID()
    var body: some View {
        Form {
            Section { Text(action.title).font(.title3.bold()); Text("Änderungen werden zuerst geprüft und erst nach deiner Bestätigung einmalig ausgeführt.").font(.footnote).foregroundStyle(.secondary) }
            ForEach(action.fields, id: \.["name"].string) { field in
                Section {
                    input(field)
                    if field["autocomplete"].bool { Button("Vorschläge laden", systemImage: "magnifyingglass") { choiceField = field; choiceQuery = values[field["name"].string]?.string ?? "" } }
                } header: { Text(field["description"].string + (field["required"].bool ? " *" : "")) }
            }
            Section {
                Button("Nur anzeigen", systemImage: "eye") { Task { await read() } }.disabled(busy)
                Button("Aktion prüfen", systemImage: "checkmark.shield") { Task { await prepare() } }.disabled(busy || !canManage)
                if !canManage { Label("Keine Berechtigung mit diesem Schlüssel", systemImage: "lock.fill").font(.caption).foregroundStyle(.secondary) }
                if busy { ProgressView("Suite verarbeitet …") }
            }
            if let result { SuiteReplyContent(data: result) }
        }.panelScreen().navigationTitle(action.title).navigationBarTitleDisplayMode(.inline).errorAlert($error)
            .task { values = preset; for field in action.fields where values[field["name"].string] == nil { let id = field["name"].string; if field["type"].int == 5 { values[id] = .bool(false) }; if let first = field["choices"].array.first, field["required"].bool { values[id] = first["value"] } } }
            .sheet(isPresented: Binding(get: { plan != nil }, set: { if !$0 && !busy { plan = nil } })) { if let plan { confirmation(plan) } }
            .sheet(isPresented: Binding(get: { choiceField != nil }, set: { if !$0 { choiceField = nil } })) { suggestionSheet }
    }
    var canManage: Bool { !suite.demo && (suite.scope == "full" || (suite.scope == "manage" && action.data["scope"].string != "full" && !["settings.update", "moderation.config", "suite.command", "server.console", "server.suite", "server.reload", "server.trim"].contains(action.id))) }
    @ViewBuilder func input(_ field: JSON) -> some View {
        let id = field["name"].string
        if !field["choices"].array.isEmpty {
            Picker(field["description"].string, selection: Binding(get: { values[id]?.string ?? "" }, set: { text in values[id] = field["choices"].array.first { $0["value"].string == text }?["value"] ?? .string(text) })) {
                if !field["required"].bool { Text("Nicht angeben").tag("") }
                ForEach(field["choices"].array, id: \.["value"].string) { Text($0["name"].string).tag($0["value"].string) }
            }.pickerStyle(.menu)
        } else if field["type"].int == 5 { Toggle(field["description"].string, isOn: Binding(get: { values[id]?.bool ?? false }, set: { values[id] = .bool($0) })) }
        else { TextField(field["description"].string, text: Binding(get: { values[id]?.string ?? "" }, set: { values[id] = .string($0) }), axis: .vertical).textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(field["type"].int == 4 ? .numbersAndPunctuation : .default) }
    }
    var payload: [String: JSON] { values.filter { $0.value != .null && (!$0.value.string.isEmpty || $0.value == .bool(false) || action.id == "settings.update" && $0.key == "value") } }
    func read() async { busy = true; defer { busy = false }; do { result = try await suite.view(action.id, values: payload) } catch { self.error = error.localizedDescription } }
    func prepare() async { busy = true; defer { busy = false }; do { plan = try await suite.prepare(action.id, values: payload) } catch { self.error = error.localizedDescription } }
    func confirmation(_ prepared: JSON) -> some View {
        NavigationStack { List { Section { Text(action.title).font(.headline); SuiteFormattedText(text: prepared["summary"].string); Text("Bestätigung ist 60 Sekunden gültig. Bei Zeitüberschreitung zuerst den Serverzustand prüfen.").font(.footnote).foregroundStyle(.secondary) }; Section { Button("Jetzt ausführen", role: .destructive) { Task { busy = true; do { result = try await suite.execute(prepared["ticket"].string); if result?["ok"].bool == true { Feedback.success() } } catch { self.error = error.localizedDescription }; busy = false; plan = nil } }.disabled(busy); if busy { ProgressView("Wird ausgeführt …") } } }.panelScreen().navigationTitle("Aktion bestätigen").toolbar { ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { plan = nil }.disabled(busy) } } }.interactiveDismissDisabled(busy)
    }
    var suggestionSheet: some View {
        NavigationStack { List { ForEach(choices, id: \.["value"].string) { item in Button(item["name"].string) { if let field = choiceField { values[field["name"].string] = item["value"] }; choiceField = nil } } }.panelScreen().navigationTitle("Vorschläge").searchable(text: $choiceQuery).toolbar { ToolbarItem(placement: .cancellationAction) { Button("Schließen") { choiceField = nil } } }.task(id: choiceQuery) { let revision = UUID(); choiceGeneration = revision; do { try await Task.sleep(for: .milliseconds(250)); var q = payload.mapValues(\.string); q["path"] = action.id; q["field"] = choiceField?["name"].string ?? ""; q["q"] = choiceQuery; let response = try await suite.request("choices", query: q); if choiceGeneration == revision { choices = response.array } } catch is CancellationError {} catch { self.error = error.localizedDescription } } }
    }
}
struct SuiteFormattedText: View {
    let text: String
    var body: some View { Text((try? AttributedString(markdown: cleanConsole(text), options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace))) ?? AttributedString(cleanConsole(text))).frame(maxWidth: .infinity, alignment: .leading).textSelection(.enabled) }
}
struct SuiteReplyContent: View {
    let data: JSON
    var body: some View { Section { Label(data["title"].string, systemImage: data["ok"].bool ? "checkmark.circle" : "exclamationmark.triangle").font(.headline).foregroundStyle(data["ok"].bool ? .teal : .orange); SuiteFormattedText(text: data["description"].string); CopyButton(text: cleanConsole(data["description"].string), title: "Rückmeldung kopieren") } }
}
struct SuiteReadView: View {
    let suite: SuiteAPI
    let path: String
    let title: String
    var values: [String: JSON] = [:]
    @State private var result: JSON = .null
    @State private var error: String?
    var body: some View { List { if result != .null { SuiteReplyContent(data: result) } else { ProgressView() } }.panelScreen().navigationTitle(title).navigationBarTitleDisplayMode(.inline).task { await load() }.refreshable { await load() }.errorAlert($error) }
    func load() async { do { result = try await suite.view(path, values: values) } catch { self.error = error.localizedDescription } }
}

struct SuiteLinkedPlayerView: View {
    let panel: PanelAPI
    let server: Server
    let player: String
    @StateObject private var model: SuiteModel
    init(panel: PanelAPI, server: Server, player: String) { self.panel = panel; self.server = server; self.player = player; _model = StateObject(wrappedValue: SuiteModel(panel: panel, server: server)) }
    var body: some View {
        Group { if let api = model.api { SuitePlayerView(suite: api, panel: panel, server: server, player: player, actions: model.actions) } else { List { if model.busy { ProgressView("Admin Suite verbinden …") }; Text("Moderation ist auf Admin Suite eingestellt. Bei Verbindungsproblemen wird keine andere Bannverwaltung automatisch verwendet.").font(.footnote).foregroundStyle(.secondary); Button("Erneut verbinden") { Task { await model.load() } }; NavigationLink("Kopplung prüfen") { SuiteHubView(panel: panel, server: server) } }.panelScreen().navigationTitle(player) } }.task { await model.load() }.errorAlert($model.error)
    }
}

struct SuiteRemoteMenuView: View {
    let suite: SuiteAPI
    @State private var menu: JSON = .null
    @State private var input = ""
    @State private var selected: JSON?
    @State private var selectedClick = "left"
    @State private var plan: JSON?
    @State private var error: String?
    @State private var busy = false
    @State private var search = ""
    var body: some View {
        List {
            Section { Label("Originale Suite-Verwaltung", systemImage: "square.grid.2x2.fill").font(.headline).foregroundStyle(.teal); Text("Diese Ansichten verwenden die vorhandenen Menüaktionen der Suite. Jede Auswahl erhält eine zusätzliche Bestätigung; Einstellungen und Spielerdaten bleiben in denselben Diensten.").font(.footnote).foregroundStyle(.secondary); Text(menu["notice"].string).font(.caption).foregroundStyle(.secondary) }
            if menu["input"].bool { Section(menu["title"].string) { TextField("Eingabe", text: $input, axis: .vertical).textInputAutocapitalization(.never).autocorrectionDisabled(); Button("Eingabe prüfen") { select(.object(["id": .string("input"), "name": .string("Eingabe übernehmen"), "detail": .string(input)])) }.disabled(busy) } }
            Section(cleanConsole(menu["title"].string)) {
                ForEach(menu["options"].array.filter { search.isEmpty || ($0["name"].string + $0["detail"].string).localizedCaseInsensitiveContains(search) }, id: \.["id"].string) { option in
                    Button { select(option) } label: { VStack(alignment: .leading, spacing: 5) { Text(cleanConsole(option["name"].string)).font(.headline); if !option["detail"].string.isEmpty { Text(cleanConsole(option["detail"].string)).font(.caption).foregroundStyle(.secondary) } }.disabled(option["disabled"].bool || busy || suite.demo)
                        .contextMenu { if menu["inventory"].bool { Button("Rechtsklick") { select(option, click: "right") }; Button("Umschalt + Linksklick") { select(option, click: "shift-left") }; Button("Umschalt + Rechtsklick") { select(option, click: "shift-right") } }
                }
                if busy { ProgressView("Suite-Menü verarbeiten …") }
            }
            if !menu["messages"].array.isEmpty { Section("Rückmeldung der Suite") { ForEach(Array(menu["messages"].array.enumerated()), id: \.offset) { _, value in SuiteFormattedText(text: value.string) } } }
            Section { Button("Zur Admin-Zentrale", systemImage: "house") { select(.object(["id": .string("home"), "name": .string("Admin-Zentrale öffnen")])) }.disabled(busy || suite.demo); Button("Menüstand aktualisieren", systemImage: "arrow.clockwise") { Task { await load() } }.disabled(busy) }
        }.panelScreen().navigationTitle("Suite-Menüs").navigationBarTitleDisplayMode(.inline).searchable(text: $search, prompt: "Menüpunkt suchen").task { await load() }.refreshable { await load() }.errorAlert($error)
            .sheet(isPresented: Binding(get: { plan != nil }, set: { if !$0 && !busy { plan = nil; selected = nil } })) {
                NavigationStack { List { Section { Text(cleanConsole(selected?["name"].string ?? "Menüaktion")).font(.headline); Text(["left": "Linksklick", "right": "Rechtsklick", "shift-left": "Umschalt + Linksklick", "shift-right": "Umschalt + Rechtsklick"][selectedClick] ?? selectedClick).font(.caption).foregroundStyle(.secondary); Text(cleanConsole(selected?["detail"].string ?? "")).textSelection(.enabled); Text("Die Auswahl wird im vorhandenen Suite-Menü ausgeführt. Bei abgelaufenem oder geändertem Menü wird sie abgelehnt.").font(.footnote).foregroundStyle(.secondary) }; Section { Button("Bestätigen & ausführen", role: .destructive) { Task { await execute() } }.disabled(busy); if busy { ProgressView() } } }.panelScreen().navigationTitle("Suite-Auswahl").toolbar { ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { plan = nil; selected = nil }.disabled(busy) } } }.interactiveDismissDisabled(busy)
            }
    }
    func select(_ option: JSON, click: String = "left") { selected = option; selectedClick = click; Task { busy = true; defer { busy = false }; do { let values: [String: JSON] = ["session": menu["session"], "revision": menu["revision"], "option": option["id"], "click": .string(click), "input": .string(option["id"].string == "input" ? input : "")]; plan = try await suite.prepare("menu.action", values: values) } catch { selected = nil; self.error = error.localizedDescription } } }
    func load() async { guard !busy else { return }; busy = true; defer { busy = false }; do { menu = try await suite.request("menu"); if menu["input"].bool { input = menu["previous"].string } } catch { self.error = error.localizedDescription } }
    func execute() async { guard let plan else { return }; busy = true; defer { busy = false; self.plan = nil; selected = nil }; do { menu = try await suite.execute(plan["ticket"].string); search = ""; if menu["input"].bool { input = menu["previous"].string }; Feedback.success() } catch { self.error = error.localizedDescription } }
}
