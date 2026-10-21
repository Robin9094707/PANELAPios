import SwiftUI

struct ManageView: View {
    let api: PanelAPI
    let server: Server
    @ObservedObject var suite: SuiteModel
    @State private var operation: Operation?
    @EnvironmentObject private var store: AppStore
    @EnvironmentObject private var workspace: WorkspaceStore
    var live: LiveServer? = nil
    var prefix: String { "servers/\(server.id)" }
    var body: some View {
        List {
            if api.amp == nil {
            Section("Simplex Admin Suite") {
                if suite.configured, let live {
                    NavigationLink { IntegratedPlayersView(model: suite, live: live, initialFilter: .online) } label: { Label("Online-Spieler & Geräte", systemImage: "person.2.fill") }
                    NavigationLink { IntegratedPlayersView(model: suite, live: live) } label: { Label("Spieler suchen & Aktion ausführen", systemImage: "magnifyingglass") }
                    NavigationLink { IntegratedPlayersView(model: suite, live: live, initialFilter: .recent) } label: { Label("Zuletzt online", systemImage: "clock.arrow.circlepath") }
                    NavigationLink { SuiteStatisticsView(model: suite, live: live) } label: { Label("Statistiken & Geräteverteilung", systemImage: "chart.bar.xaxis").fixedSize(horizontal: false, vertical: true) }.accessibilityIdentifier("serverStatistics")
                }
                NavigationLink { SuiteHubView(panel: api, server: server, model: suite) } label: { Label("Admin Suite", systemImage: "shield.lefthalf.filled") }.accessibilityIdentifier("adminSuiteHub")
            }
            }
            if api.amp == nil || workspace.isMinecraft(api, server) {
            Section("Minecraft") {
                if !suite.configured, let live { NavigationLink { PlayerManagerView(api: api, server: server, live: live) } label: { Label("Spieler-Manager", systemImage: "person.crop.circle.badge.checkmark") } }
                NavigationLink { ModsView(api: api, server: server) } label: { Label("Mods & Mod-Updates", systemImage: "shippingbox") }.requiresAccess(api, server.id, "file.read")
                NavigationLink { PluginsView(api: api, server: server) } label: { Label("Plugins & JAR-Dateien", systemImage: "puzzlepiece.extension") }.requiresAccess(api, server.id, "file.read")
                NavigationLink { MinecraftPropertiesView(api: api, server: server) } label: { Label("Minecraft & Welt-Einstellungen", systemImage: "globe") }.requiresAccess(api, server.id, "file.read-content")
            }
            }
            if api.isKinetic { Section("Provider-Funktionen") { NavigationLink { ProviderHub(api: api, server: server) } label: { Label("Kinetic-Erweiterungen", systemImage: "sparkles") } } }
            if api.amp != nil { Section("AMP") { NavigationLink { AMPManageView(api: api, server: server) } label: { Label("Instanz, Einstellungen & Aufgaben", systemImage: "server.rack") } } }
            if api.amp == nil {
            Section("Server verwalten") {
                NavigationLink { SchedulesView(api: api, server: server) } label: { Label("Zeitpläne", systemImage: "calendar.badge.clock") }.requiresAccess(api, server.id, "schedule.read")
                NavigationLink { DatabasesView(api: api, server: server) } label: { Label("Datenbanken", systemImage: "externaldrive.connected.to.line.below") }.requiresAccess(api, server.id, "database.read")
                NavigationLink { ResourcesView(api: api, server: server, kind: .network) } label: { Label("Netzwerk & Ports", systemImage: "network") }.requiresAccess(api, server.id, "allocation.read")
                NavigationLink { ResourcesView(api: api, server: server, kind: .users) } label: { Label("Mitbenutzer", systemImage: "person.2") }.requiresAccess(api, server.id, "user.read")
                NavigationLink { StartupView(api: api, server: server) } label: { Label("Startparameter", systemImage: "play.rectangle") }.requiresAccess(api, server.id, "startup.read")
                NavigationLink { ActivityView(api: api, path: prefix + "/activity") } label: { Label("Aktivitäten", systemImage: "clock.arrow.circlepath") }.requiresAccess(api, server.id, "activity.read")
            }
            Section("Einstellungen") {
                Button("Name & Beschreibung", systemImage: "pencil") { operation = Operation(title: "Server umbenennen", path: prefix + "/settings/rename", fields: [Field(key: "name", label: "Name", value: server.name), Field(key: "description", label: "Beschreibung", value: server.data["description"].string, kind: .multiline)]) }.requiresRequest(api, prefix + "/settings/rename", method: "POST")
                Button("Docker-Image ändern", systemImage: "shippingbox") { operation = Operation(title: "Docker-Image ändern", detail: "Verwende ein vom Provider für diesen Server freigegebenes Image. Änderungen greifen beim nächsten Start.", path: prefix + "/settings/docker-image", method: "PUT", fields: [Field(key: "docker_image", label: "Docker-Image", value: server.data["docker_image"].string)], destructive: true) }.requiresRequest(api, prefix + "/settings/docker-image", method: "PUT")
                Button("Server neu installieren", systemImage: "arrow.triangle.2.circlepath", role: .destructive) { operation = Operation(title: "Server neu installieren", detail: "Das Installationsskript wird erneut ausgeführt. Dabei können Serverdateien verändert oder überschrieben werden. Erstelle vorher ein Backup.", path: prefix + "/settings/reinstall", destructive: true, submit: "Neu installieren") }.requiresRequest(api, prefix + "/settings/reinstall", method: "POST")
            }
            Section("SFTP") {
                LabeledContent("Host", value: server.data["sftp_details"]["ip"].string).textSelection(.enabled)
                LabeledContent("Port", value: server.data["sftp_details"]["port"].string)
                Text("Der SFTP-Benutzername und das Passwort richten sich nach deinem Panel-Account. Der Client-API-Schlüssel ist kein SFTP-Passwort.").font(.caption).foregroundStyle(.secondary)
            }
            }
            Section {
                NavigationLink { JSONDetails(title: "Serverdetails", data: server.data) } label: { Label("Alle Serverinformationen", systemImage: "info.circle") }
            } footer: { Text("Panel-Aktionen verwenden die Client-API. Die optionale Admin-Suite-Anbindung hat einen eigenen Zugang. Dein Provider legt die Berechtigungen, Ressourcenlimits und verfügbaren Funktionen fest.") }
        }.panelScreen().refreshable { await api.loadAccess(server: server.id); await store.refresh(); if let live { try? await live.refresh(api: api, server: server, minecraft: true) } }.sheet(item: $operation) { OperationSheet(api: api, operation: $0) }
    }
}

enum ResourceKind {
    case databases, network, users
    var title: String { switch self { case .databases: return "Datenbanken"; case .network: return "Netzwerk & Ports"; case .users: return "Mitbenutzer" } }
    var path: String { switch self { case .databases: return "databases"; case .network: return "network/allocations"; case .users: return "users" } }
    var symbol: String { switch self { case .databases: return "externaldrive"; case .network: return "network"; case .users: return "person.2" } }
}
struct ResourcesView: View {
    let api: PanelAPI
    let server: Server
    let kind: ResourceKind
    @State private var rows: [JSON] = []
    @State private var loaded = false
    @State private var error: String?
    @State private var operation: Operation?
    @State private var detail: DetailItem?
    @State private var userEditor: DetailItem?
    var prefix: String { "servers/\(server.id)/" + (kind == .network && api.isKinetic ? "allocations" : kind.path) }
    var body: some View {
        List {
            if !loaded { ProgressView("Laden …") }
            if loaded && rows.isEmpty { ContentUnavailableView("Keine Einträge", systemImage: kind.symbol) }
            ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                Section {
                    HStack {
                        VStack(alignment: .leading, spacing: 7) {
                            Text(title(row)).font(.headline)
                            Text(subtitle(row)).font(.caption).foregroundStyle(.secondary).textSelection(.enabled)
                        }; Spacer()
                        Menu { actions(row) } label: { Image(systemName: "ellipsis.circle").font(.title3).padding(6) }
                    }.padding(.vertical, 6)
                    if kind == .users {
                        Text("\(row["permissions"].array.count) Berechtigungen").font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
        }.panelScreen().navigationTitle(kind.title).navigationBarTitleDisplayMode(.inline).refreshable { await load() }.task { await load() }
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Hinzufügen", systemImage: "plus") { create() }.requiresRequest(api, prefix) } }
            .sheet(item: $operation) { OperationSheet(api: api, operation: $0) { await load() } }
            .sheet(item: $userEditor) { item in SubuserEditor(api: api, server: server, initial: item.data) { await load() } }
            .sheet(item: $detail) { item in NavigationStack { JSONDetails(title: "Details", data: item.data).toolbar { ToolbarItem(placement: .confirmationAction) { Button("Fertig") { detail = nil } } } } }
            .errorAlert($error)
    }
    func title(_ row: JSON) -> String { switch kind { case .databases: return row["name"].string; case .network: return "\(row["ip_alias"].string.isEmpty ? row["ip"].string : row["ip_alias"].string):\(row["port"].string)"; case .users: return row["email"].string } }
    func subtitle(_ row: JSON) -> String { switch kind { case .databases: return "\(row["username"].string) · \(row["host"]["address"].string):\(row["host"]["port"].string)"; case .network: return (row["is_default"].bool ? "Primär · " : "") + row["notes"].string; case .users: return row["username"].string } }
    @ViewBuilder func actions(_ row: JSON) -> some View {
        let id = kind == .users ? row["uuid"].string : row["id"].string
        Button("Details", systemImage: "info.circle") { detail = DetailItem(data: row) }
        switch kind {
        case .databases:
            Button("Passwort anzeigen", systemImage: "key") { Task { do { let result = try await api.list(prefix, query: ["include": "password"]); detail = DetailItem(data: result.first(where: { $0["id"].string == id }) ?? .null) } catch { self.error = error.localizedDescription } } }.requiresAccess(api, server.id, "database.view_password")
            Button("Passwort erneuern", systemImage: "arrow.clockwise", role: .destructive) { operation = Operation(title: "Datenbankpasswort erneuern", detail: "Plugins und Anwendungen müssen anschließend das neue Passwort verwenden.", path: prefix + "/\(id)/rotate-password", destructive: true, submit: "Erneuern") }.requiresRequest(api, prefix + "/\(id)/rotate-password", method: "POST")
        case .network:
            Button("Notiz bearbeiten", systemImage: "pencil") { operation = Operation(title: "Port-Notiz", path: prefix + "/\(id)", method: api.isKinetic ? "PATCH" : "POST", fields: [Field(key: "notes", label: "Notiz", value: row["notes"].string)]) }.requiresRequest(api, prefix + "/\(id)", method: api.isKinetic ? "PATCH" : "POST")
            if !row["is_default"].bool { Button("Als primär festlegen", systemImage: "star") { operation = Operation(title: "Primären Port ändern", path: prefix + "/\(id)" + (api.isKinetic ? "" : "/primary"), method: api.isKinetic ? "PATCH" : "POST", fixed: api.isKinetic ? ["primary": .bool(true)] : [:], submit: "Festlegen") }.requiresRequest(api, prefix + "/\(id)" + (api.isKinetic ? "" : "/primary"), method: api.isKinetic ? "PATCH" : "POST") }
        case .users:
            Button("Berechtigungen bearbeiten", systemImage: "person.badge.key") { userEditor = DetailItem(data: row) }.requiresRequest(api, prefix + "/\(id)", method: "POST")
        }
        Button("Entfernen", systemImage: "trash", role: .destructive) { operation = Operation(title: "Eintrag entfernen", detail: kind == .databases ? "Die Datenbank und alle darin enthaltenen Daten werden dauerhaft gelöscht." : "\(title(row)) wird entfernt.", path: prefix + "/\(id)", method: "DELETE", destructive: true, submit: "Entfernen") }.disabled(kind == .network && row["is_default"].bool).requiresRequest(api, prefix + "/\(id)", method: "DELETE")
    }
    func create() {
        switch kind {
        case .databases:
            operation = Operation(title: "Datenbank erstellen", path: prefix, fields: [Field(key: "database", label: "Datenbankname"), Field(key: "remote", label: "Erlaubte Verbindungen", value: "%", hint: "% erlaubt alle Quelladressen. Nutze eine feste Adresse, wenn möglich.")], submit: "Erstellen")
        case .network:
            operation = Operation(title: "Port zuweisen", detail: "Der Provider muss die automatische Zuweisung weiterer Ports unterstützen.", path: prefix, submit: "Port anfordern")
        case .users:
            userEditor = DetailItem(data: .null)
        }
    }
    func load() async { do { rows = try await api.list(prefix); loaded = true } catch is CancellationError {} catch { self.error = error.localizedDescription } }
}
struct DetailItem: Identifiable { let id = UUID(); let data: JSON }

struct StartupView: View {
    let api: PanelAPI
    let server: Server
    @State private var variables: [JSON] = []
    @State private var operation: Operation?
    @State private var error: String?
    var body: some View {
        List {
            Section("Startbefehl") { Text(server.data["invocation"].string).font(.system(.footnote, design: .monospaced)).textSelection(.enabled) }
            ForEach(variables, id: \.["env_variable"].string) { v in
                Section {
                    VStack(alignment: .leading, spacing: 8) { Text(v["name"].string).font(.headline); Text(v["server_value"].string).font(.system(.body, design: .monospaced)).textSelection(.enabled); Text(v["description"].string).font(.caption).foregroundStyle(.secondary) }
                    if v["is_editable"].bool {
                        Button("Wert ändern", systemImage: "pencil") { operation = Operation(title: v["name"].string, detail: v["rules"].string, path: "servers/\(server.id)/startup/variable", method: "PUT", fields: [Field(key: "value", label: "Wert", value: v["server_value"].string)], fixed: ["key": v["env_variable"]]) }.requiresRequest(api, "servers/\(server.id)/startup/variable", method: "PUT")
                    } else { Label("Vom Provider verwaltet", systemImage: "lock").font(.caption).foregroundStyle(.secondary) }
                }
            }
        }.panelScreen().navigationTitle("Startparameter").navigationBarTitleDisplayMode(.inline).task { await load() }.refreshable { await load() }.errorAlert($error)
            .sheet(item: $operation) { OperationSheet(api: api, operation: $0) { await load() } }
    }
    func load() async { do { variables = try await api.list("servers/\(server.id)/startup") } catch { self.error = error.localizedDescription } }
}
struct ActivityView: View {
    let api: PanelAPI
    let path: String
    @State private var events: [JSON] = []
    @State private var error: String?
    @State private var page = 1
    @State private var more = true
    @State private var loading = false
    var body: some View {
        List {
            if events.isEmpty && !loading { ContentUnavailableView("Keine Aktivitäten", systemImage: "clock.arrow.circlepath") }
            ForEach(Array(events.enumerated()), id: \.offset) { _, event in
                NavigationLink { JSONDetails(title: "Aktivität", data: event) } label: { VStack(alignment: .leading, spacing: 6) { Text(event["event"].string).font(.subheadline.weight(.medium)); Text(event["timestamp"].string).font(.caption).foregroundStyle(.secondary) } }
            }
            if loading { ProgressView("Aktivitäten laden …") }
            else if more { Button("Weitere Aktivitäten laden") { Task { await load(reset: false) } } }
        }.panelScreen().navigationTitle("Aktivitäten").navigationBarTitleDisplayMode(.inline).task { await load(reset: true) }.refreshable { await load(reset: true) }.errorAlert($error)
    }
    func load(reset: Bool) async {
        guard !loading else { return }; loading = true; defer { loading = false }
        do {
            let requested = reset ? 1 : page
            let result = try await api.request(path, query: ["page": String(requested), "per_page": "50"])
            if reset { events = result.rows } else { events += result.rows }
            let pages = result["meta"]["pagination"]["total_pages"].int
            more = requested < pages; page = requested + 1
        } catch is CancellationError {} catch { self.error = error.localizedDescription }
    }
}
