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
    var body: some View {
        NavigationStack { Form {
            Section("Server") { TextField("https://Server-IP:Port", text: $endpoint).keyboardType(.URL); TextField("SHA-256-Fingerabdruck", text: $fingerprint, axis: .vertical).font(.caption.monospaced()) }
            Section("App-Zugang") { TextField("Minecraft-Benutzername", text: $username); SecureField("Passwort", text: $password) }
            Section { Text("Den Fingerabdruck erhältst du vom Hauptadmin mit /sas app fingerprint. Bei einem öffentlich gültigen HTTPS-Zertifikat kann er leer bleiben.").font(.footnote).foregroundStyle(.secondary); Button { Task { await login() } } label: { if busy { ProgressView("Anmelden …") } else { Label("Anmelden & speichern", systemImage: "lock.shield") } }.disabled(busy || endpoint.isEmpty || username.isEmpty || password.isEmpty) }
        }.textInputAutocapitalization(.never).autocorrectionDisabled().navigationTitle("Admin Suite anmelden").toolbar { Button("Schließen") { dismiss() }.disabled(busy) }.errorAlert($error).task { if let existing { endpoint = existing.endpoint; fingerprint = existing.fingerprint; username = existing.panel.replacingOccurrences(of: "suite-direct:", with: "") } } }.interactiveDismissDisabled(busy)
    }
    func login() async {
        busy = true; defer { busy = false }
        do {
            let url = try SuiteAPI.endpoint(endpoint), pin = try SuiteAPI.pin(fingerprint)
            let name = username.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            var record = existing ?? SuiteConnection(panel: "suite-direct:" + name, server: UUID().uuidString, endpoint: url.absoluteString, fingerprint: pin, serverIdentity: "")
            record.endpoint = url.absoluteString; record.fingerprint = pin
            let api = try SuiteAPI(record, token: "")
            let token = try await api.login(username: name, password: password)
            let health = try await api.health(); record.serverIdentity = health["serverId"].string
            try SuiteConnections.shared.save(record, token: token); password = ""; Feedback.success(); dismiss()
        } catch { self.error = error.localizedDescription }
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
                    NavigationLink { SuiteLiveChatView(suite: suite) } label: { Label("Serverchat", systemImage: "bubble.left.and.bubble.right") }.disabled(!suite.can("data.chat"))
                    NavigationLink { SuiteBarkSettingsView(suite: suite) } label: { Label("Meine Bark-Hinweise", systemImage: "bell.badge") }.disabled(!suite.can("action.notifications.update"))
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
