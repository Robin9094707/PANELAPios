import SwiftUI

struct ProviderHub: View {
    let api: PanelAPI
    let server: Server
    @State private var operation: Operation?
    var body: some View {
        List {
            Section { Text("Zusätzliche Client-Funktionen von Kinetic. Verfügbarkeit und Schreibrechte hängen von deinem Hosting-Paket und den Account-Rechten ab.").font(.footnote).foregroundStyle(.secondary) }
            Section("Netzwerk") {
                link("Domains", "domains", "globe")
                link("Subdomains", "subdomain", "link")
                link("Reverse Proxy", "proxy", "arrow.triangle.branch")
                link("Firewall", "firewall", "shield")
            }
            Section("Server & Software") {
                link("Instanzen", "instances", "square.stack.3d.up")
                link("Ressourcenaufteilung", "split/details", "square.split.2x2")
                link("Installierte Version", "versions/installed", "checkmark.seal")
                link("Verfügbare Versionen", "versions", "shippingbox")
                link("Server-Vorlagen", "eggs", "square.grid.2x2")
                link("Modpacks", "modpacks", "shippingbox.fill")
                link("Gesperrte Mods", "minecraft/mods/blacklisted", "nosign")
                Button("Modrinth-Plugin laden", systemImage: "arrow.down.doc") { modrinth() }
            }
            Section("Weitere Einstellungen") {
                link("Eingebundene Backups", "backups/mounted", "externaldrive")
                Button("Startbefehl bearbeiten", systemImage: "terminal") { operation = Operation(title: "Startbefehl ändern", detail: "Der Provider muss eigene Startbefehle erlauben. Änderungen wirken beim nächsten Start.", path: "servers/\(server.id)/settings/start-command", method: "PUT", fields: [Field(key: "start_command", label: "Startbefehl", value: server.data["invocation"].string, kind: .multiline)]) }
            }
        }.panelScreen().navigationTitle("Kinetic").navigationBarTitleDisplayMode(.inline)
            .sheet(item: $operation) { OperationSheet(api: api, operation: $0) }
    }
    func link(_ title: String, _ path: String, _ icon: String) -> some View { NavigationLink { ProviderCollection(api: api, server: server, title: title, path: path) } label: { Label(title, systemImage: icon) } }
    func modrinth() {
        operation = Operation(title: "Plugin von Modrinth laden", detail: "Gib die genaue Projekt- und Versions-ID einer kompatiblen Paper-JAR ein. Stoppe den Server vorher. Installiert direkt nach /plugins; bestehende Dateien können ersetzt werden. Für Updates ist der Paper-Updateordner vorzuziehen.", path: "servers/\(server.id)/mods/download", fields: [Field(key: "project", label: "Projekt-ID"), Field(key: "version", label: "Versions-ID")], destructive: true, submit: "Plugin installieren", transform: { values in
            let project = values["project"]?.string.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            let version = values["version"]?.string.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            guard !project.isEmpty, !version.isEmpty else { throw PanelError(message: "Projekt-ID und Versions-ID sind erforderlich.") }
            return .object(["platform": .string("modrinth"), "directory": .string("plugins"), "ids": .object([project: .array([.string(version)])])])
        })
    }
}

struct ProviderCollection: View {
    let api: PanelAPI
    let server: Server
    let title: String
    let path: String
    @State private var data: JSON = .null
    @State private var error: String?
    @State private var loading = false
    @State private var operation: Operation?
    var prefix: String { "servers/\(server.id)/" }
    var body: some View {
        List {
            if loading { ProgressView("Laden …") }
            if ["subdomain", "proxy", "firewall"].contains(path) { Button("Hinzufügen", systemImage: "plus") { create() } }
            if path == "instances" { Text("Beim Wechsel wird die aktive Serverinstanz geändert. Stoppe den Server vor einem Wechsel.").font(.footnote).foregroundStyle(.secondary) }
            if !data.rows.isEmpty {
                ForEach(Array(data.rows.enumerated()), id: \.offset) { index, row in
                    Section(rowTitle(row, index)) {
                        NativeValues(value: row)
                        actions(row)
                    }
                }
            } else if data != .null { Section { NativeValues(value: data.attributes["data"] == .null ? data.attributes : data.attributes["data"]) } }
        }.panelScreen().navigationTitle(title).navigationBarTitleDisplayMode(.inline).task { await load() }.refreshable { await load() }.errorAlert($error)
            .sheet(item: $operation) { OperationSheet(api: api, operation: $0) { await load() } }
    }
    func rowTitle(_ row: JSON, _ index: Int) -> String { for key in ["name", "domain", "subdomain", "version", "uuid", "id"] { if !row[key].string.isEmpty { return row[key].string } }; return "Eintrag \(index + 1)" }
    func load() async { loading = true; defer { loading = false }; do {
        let response = try await api.request(prefix + path)
        if response["meta"]["pagination"]["total_pages"].int > 1 { data = .array(try await api.list(prefix + path)) } else { data = response }
    } catch { self.error = error.localizedDescription } }
    @ViewBuilder func actions(_ row: JSON) -> some View {
        let id = row["uuid"].string.isEmpty ? row["id"].string : row["uuid"].string
        if !id.isEmpty && !id.contains("/") {
            if path == "instances" {
                Button("Umbenennen", systemImage: "pencil") { operation = Operation(title: "Instanz umbenennen", path: prefix + "instances/\(id)/rename", method: "PATCH", fields: [Field(key: "name", label: "Name", value: row["name"].string)]) }
                Button("Zu dieser Instanz wechseln", systemImage: "arrow.triangle.swap") { operation = Operation(title: "Instanz wechseln", detail: "Stoppe den Server vorher. Die aktive Instanz wird gewechselt.", path: prefix + "instances/\(id)/swap", destructive: true, submit: "Wechseln") }
            }
            if ["subdomain", "proxy", "firewall", "instances"].contains(path) {
                Button("Löschen", systemImage: "trash", role: .destructive) {
                    let suffix = path == "proxy" ? "proxy/delete/\(id)" : path == "firewall" ? "firewall/remove/\(id)" : "\(path)/\(id)"
                    operation = Operation(title: "\(title): Eintrag löschen", detail: "Dieser Eintrag wird dauerhaft entfernt.", path: prefix + suffix, method: "DELETE", destructive: true, submit: "Löschen")
                }
            }
        }
    }
    func create() {
        switch path {
        case "subdomain": operation = Operation(title: "Subdomain erstellen", detail: "Die Domain-ID findest du unter Domains.", path: prefix + path, fields: [Field(key: "domainId", label: "Domain-ID", kind: .number), Field(key: "subdomain", label: "Subdomain, z. B. play")])
        case "proxy": operation = Operation(title: "Reverse Proxy erstellen", path: prefix + "proxy/create", fields: [Field(key: "domain", label: "Domain"), Field(key: "allocation_id", label: "Allocation-ID", kind: .number), Field(key: "ssl_enabled", label: "SSL aktivieren", value: "false", kind: .toggle)])
        case "firewall": operation = Operation(title: "Firewall-Regel erstellen", path: prefix + "firewall/add", fields: [Field(key: "name", label: "Name"), Field(key: "ip", label: "IP oder CIDR"), Field(key: "allocation", label: "Allocation-ID", kind: .number), Field(key: "priority", label: "Priorität", value: "1", kind: .number), Field(key: "type", label: "Aktion", value: "allow", options: ["allow", "deny"])])
        default: break
        }
    }
}

/// Native recursive rows for provider-specific schemas; no serialized JSON blocks.
struct NativeValues: View {
    let value: JSON
    var body: some View {
        switch value {
        case .object(let fields):
            ForEach(fields.keys.sorted(), id: \.self) { key in
                if let item = fields[key] {
                    switch item {
                    case .object, .array: DisclosureGroup(key.replacingOccurrences(of: "_", with: " ").capitalized) { AnyView(NativeValues(value: item)) }
                    default: scalar(key, item)
                    }
                }
            }
        case .array(let items):
            if items.isEmpty { Text("Keine Einträge").foregroundStyle(.secondary) }
            ForEach(Array(items.enumerated()), id: \.offset) { index, item in
                switch item { case .object, .array: DisclosureGroup("Eintrag \(index + 1)") { AnyView(NativeValues(value: item)) }; default: scalar("\(index + 1)", item) }
            }
        default: scalar("Wert", value)
        }
    }
    func scalar(_ key: String, _ item: JSON) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(key.replacingOccurrences(of: "_", with: " ").capitalized).font(.caption).foregroundStyle(.secondary)
            Text(item == .null ? "–" : item.string).frame(maxWidth: .infinity, alignment: .leading).textSelection(.enabled)
        }.padding(.vertical, 2)
    }
}
