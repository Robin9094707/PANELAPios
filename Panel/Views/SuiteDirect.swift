import SwiftUI

/// A Suite-only account needs no hosting-panel API key. It reuses the existing encrypted token vault.
struct SuiteDirectLoginView: View {
    var existing: SuiteConnection? = nil
    @Environment(\.dismiss) private var dismiss
    @State private var endpoint = ""
    @State private var fingerprint = ""
    @State private var username = ""
    @State private var password = ""
    @State private var busy = false
    @State private var error: String?
    @State private var setup = false
    @State private var pending: SuitePendingLogin?
    var body: some View {
        NavigationStack { Form {
            Section("Server") { TextField("https://Server-IP:Port", text: $endpoint).keyboardType(.URL); TextField("SHA-256-Fingerabdruck", text: $fingerprint, axis: .vertical).font(.caption.monospaced()) }
            if existing == nil { Section { Button { setup = true } label: { Label("Lieber per Setup-Link einrichten", systemImage: "link.badge.plus") } } footer: { Text("Mit einem einmaligen Link vom Hauptadmin musst du nichts eintippen.") } }
            Section("App-Zugang") { TextField("Minecraft-Benutzername", text: $username).disabled(existing != nil); SecureField("Passwort", text: $password) }
            Section { Text("Den Fingerabdruck erhältst du vom Hauptadmin mit /sas app fingerprint. Bei einem öffentlich gültigen HTTPS-Zertifikat kann er leer bleiben.").font(.footnote).foregroundStyle(.secondary); Button { Task { await login() } } label: { if busy { ProgressView("Anmelden …") } else { Label("Anmelden & speichern", systemImage: "lock.shield") } }.disabled(busy || endpoint.isEmpty || username.isEmpty || password.isEmpty) }
            if SuitePasskeySignInButton.possible(endpoint: endpoint, fingerprint: fingerprint) { Section { SuitePasskeySignInButton(endpoint: endpoint, fingerprint: fingerprint) { code in await passkey(code) } } footer: { Text("Mit Face ID oder Touch ID über die Domain des Webpanels anmelden (Admin Suite 9.8).") } }
        }.textInputAutocapitalization(.never).autocorrectionDisabled().navigationTitle("Admin Suite anmelden").toolbar { Button("Schließen") { dismiss() }.disabled(busy) }.errorAlert($error).task { if let existing { endpoint = existing.endpoint; fingerprint = existing.fingerprint; username = existing.panel.replacingOccurrences(of: "suite-direct:", with: "") } }
            .sheet(isPresented: $setup) { SuiteSetupRedeemView { dismiss() } }
            .sheet(item: $pending) { login in SuiteSecondStepView(api: login.api, challenge: login.challenge) { value in await complete(login.api, login.record, value) } } }.interactiveDismissDisabled(busy)
    }
    func login() async {
        busy = true; defer { busy = false }
        do {
            let url = try SuiteAPI.endpoint(endpoint), pin = try SuiteAPI.pin(fingerprint)
            let name = username.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            var record = existing ?? SuiteConnection(panel: "suite-direct:" + name, server: UUID().uuidString, endpoint: url.absoluteString, fingerprint: pin, serverIdentity: "")
            record.endpoint = url.absoluteString; record.fingerprint = pin
            let api = try SuiteAPI(record, token: "")
            let token: String
            do { token = try await api.login(username: name, password: password) }
            catch let challenge as SuiteLoginChallenge { password = ""; pending = SuitePendingLogin(api: api, record: record, challenge: challenge); return }
            try await finish(api, record, token); password = ""
        } catch { self.error = error.localizedDescription }
    }
    func finish(_ api: SuiteAPI, _ record: SuiteConnection, _ token: String) async throws {
        var record = record
        let health = try await api.health(); record.serverIdentity = health["serverId"].string
        try SuiteConnections.shared.save(record, token: token); Feedback.success(); dismiss()
    }
    func complete(_ api: SuiteAPI, _ record: SuiteConnection, _ token: String) async {
        do { try await finish(api, record, token) } catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }
    func passkey(_ code: String) async {
        busy = true; defer { busy = false }
        do {
            let url = try SuiteAPI.endpoint(endpoint)
            let probe = try SuiteAPI(SuiteConnection(panel: "suite-direct:passkey", server: UUID().uuidString, endpoint: url.absoluteString, fingerprint: "", serverIdentity: ""), token: "")
            let token = try await probe.exchange(code: code)
            let health = try await probe.health()
            let name = probe.account["username"].string.isEmpty ? username.lowercased() : probe.account["username"].string
            var record = existing ?? SuiteConnections.shared.values.first { $0.panel == "suite-direct:" + name && $0.endpoint == url.absoluteString } ?? SuiteConnection(panel: "suite-direct:" + name, server: UUID().uuidString, endpoint: url.absoluteString, fingerprint: "", serverIdentity: "")
            record.endpoint = url.absoluteString; record.fingerprint = ""; record.serverIdentity = health["serverId"].string
            try SuiteConnections.shared.save(record, token: token); Feedback.success(); dismiss()
        } catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }
}
struct SuiteDirectDashboard: View {
    let connection: SuiteConnection
    @State private var suite: SuiteAPI?
    @State private var health: JSON = .null
    @State private var actions: [SuiteAction] = []
    @State private var error: String?
    @State private var login = false
    @State private var remove = false
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        List {
            Section { Label(health["name"].string.isEmpty ? connection.endpoint : health["name"].string, systemImage: "server.rack"); Text("Admin Suite " + health["version"].string).font(.caption).foregroundStyle(.secondary) }
            if let suite {
                Section("Verwaltung") {
                    NavigationLink { SuiteUpgradeView(suite: suite) } label: { Label("Zugänge & Serverregeln", systemImage: "person.badge.key.fill") }
                    NavigationLink { SuiteTicketsView(suite: suite) } label: { Label("Tickets", systemImage: "ticket.fill") }.disabled(!suite.can("data.tickets"))
                    NavigationLink { SuiteDirectEvents(suite: suite) } label: { Label("Serverereignisse", systemImage: "bell.badge") }.disabled(!suite.can("data.events"))
                    NavigationLink { SuiteLiveChatView(suite: suite) } label: { Label("Serverchat", systemImage: "bubble.left.and.bubble.right") }.disabled(!suite.can("data.chat"))
                    NavigationLink { SuiteBarkSettingsView(suite: suite) } label: { Label("Meine Bark-Hinweise", systemImage: "bell.badge") }.disabled(!suite.can("action.notifications.update"))
                    if suite.features.contains("admin-log-v1") { NavigationLink { SuiteAdminLogView(suite: suite) } label: { Label("Admin-Log", systemImage: "list.bullet.clipboard.fill") }.disabled(!suite.can("data.admin-log")) }
                    if suite.features.contains("staff-chat-v1") { NavigationLink { SuiteStaffChatView(suite: suite) } label: { Label("Staff-Chat", systemImage: "bubble.left.and.text.bubble.right.fill") }.disabled(!suite.can("data.staff-chat")) }
                    if suite.features.contains("luckperms-v1") { NavigationLink { SuiteLuckPermsView(suite: suite) } label: { Label("Ränge & Rechte", systemImage: "crown.fill") }.disabled(!suite.can("data.luckperms")) }
                    if suite.features.contains("lands-v1") { NavigationLink { SuiteLandsView(suite: suite) } label: { Label("Lands", systemImage: "map.fill") }.disabled(!suite.can("data.lands")) }
                    if suite.features.contains("access-locks-v1") { NavigationLink { SuiteAccessLocksView(suite: suite) } label: { Label("Rechte & Sperren", systemImage: "lock.shield.fill") }.disabled(!suite.can("data.access-locks")) }
                    if suite.features.contains("command-rights-v1") { NavigationLink { SuiteCommandsView(suite: suite) } label: { Label("Befehle", systemImage: "terminal.fill") }.disabled(!suite.can("data.commands")) }
                    if suite.features.contains("quiet-hours-v1") { NavigationLink { SuiteQuietHoursView(suite: suite) } label: { Label("Ruhezeiten", systemImage: "moon.zzz.fill") }.disabled(!suite.can("action.notifications.update")) }
                    NavigationLink { SuiteActionsView(suite: suite, actions: actions.filter { !["menu.action", "settings.update"].contains($0.id) }, title: "Suite-Funktionen") } label: { Label("Alle Funktionen", systemImage: "square.grid.2x2") }
                }
            }
            Section { Button("Erneut anmelden", systemImage: "key.horizontal") { login = true }; Button("Zugang auf diesem Gerät entfernen", role: .destructive) { remove = true } }
        }.panelScreen().navigationTitle("Admin Suite").task { await load() }.refreshable { await load() }.errorAlert($error)
            .sheet(isPresented: $login, onDismiss: { Task { await load() } }) { SuiteDirectLoginView(existing: connection) }
            .confirmationDialog("Lokalen Zugang entfernen?", isPresented: $remove) { Button("Entfernen", role: .destructive) { SuiteConnections.shared.remove(connection); dismiss() } }
    }
    func load() async { do { let current = SuiteConnections.shared.values.first { $0.id == connection.id } ?? connection; let api = try SuiteAPI(current); health = try await api.health(); actions = try await api.request("schema")["actions"].array.map { SuiteAction(data: $0) }; suite = api; error = nil } catch { suite = nil; self.error = error.localizedDescription } }
}

struct SuiteDirectEvents: View {
    let suite: SuiteAPI
    @StateObject private var model: SuiteUpgradeModel
    init(suite: SuiteAPI) { self.suite = suite; _model = StateObject(wrappedValue: SuiteUpgradeModel(suite)) }
    var body: some View {
        List(model.data["items"].array, id: \.["id"].string) { row in
            VStack(alignment: .leading, spacing: 6) { Text(row["title"].string).font(.headline); Text(row["body"].string).font(.subheadline); if suite.can("action.events.ack") { NavigationLink("Alarm bestätigen") { SuiteActionView(suite: suite, action: .make("events.ack", "Alarm bestätigen"), preset: ["id": row["id"]]) } } }
        }.panelScreen().navigationTitle("Serverereignisse").task { await model.load("events") }.refreshable { await model.load("events") }.errorAlert($model.error)
    }
}
struct SuiteDirectInbox: View {
    let connection: SuiteConnection
    let tickets: Bool
    @State private var suite: SuiteAPI?
    @State private var error: String?
    var body: some View {
        Group { if let suite { if tickets { SuiteTicketsView(suite: suite) } else { SuiteDirectEvents(suite: suite) } } else { ProgressView("Verbinden …") } }.task { do { let api = try SuiteAPI(connection); _ = try await api.health(); suite = api } catch { self.error = error.localizedDescription } }.errorAlert($error)
    }
}
