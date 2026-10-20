import SwiftUI

struct DatabasesView: View {
    let api: PanelAPI
    let server: Server
    @State private var rows: [JSON] = []
    @State private var error: String?
    @State private var operation: Operation?
    @State private var selected: DetailItem?
    @State private var loading = false
    var prefix: String { "servers/\(server.id)/databases" }
    var body: some View {
        List {
            Section {
                Text("Verbindungsdaten, Passwörter und Datenbankverwaltung. Tabellen und SQL-Inhalte sind über diese Panel-API nicht zugänglich.").font(.footnote).foregroundStyle(.secondary)
                Button("Datenbank erstellen", systemImage: "plus") { operation = Operation(title: "Datenbank erstellen", path: prefix, fields: [Field(key: "database", label: "Name"), Field(key: "remote", label: "Erlaubte Quelladresse", value: "%")], submit: "Erstellen") }
            }
            if loading { ProgressView() }
            ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                let db = DatabaseInfo(value: row)
                Button { selected = DetailItem(data: row) } label: {
                    HStack(spacing: 14) { Image(systemName: "externaldrive.fill").font(.title2).foregroundStyle(.teal); VStack(alignment: .leading, spacing: 6) { Text(db.name).font(.headline); Text("\(db.host):\(db.port)").font(.caption.monospaced()).foregroundStyle(.secondary); Text(row["username"].string).font(.caption).foregroundStyle(.secondary) }; Spacer(); Image(systemName: "chevron.right").font(.caption) }
                }.buttonStyle(.plain)
            }
            if !loading && rows.isEmpty { ContentUnavailableView("Keine Datenbanken", systemImage: "externaldrive") }
        }.panelScreen().navigationTitle("Datenbanken").navigationBarTitleDisplayMode(.inline).task { await load() }.refreshable { await load() }.errorAlert($error)
            .sheet(item: $operation) { OperationSheet(api: api, operation: $0) { await load() } }
            .sheet(item: $selected) { item in DatabaseDetail(api: api, server: server, initial: item.data) { await load() } }
    }
    func load() async { loading = true; defer { loading = false }; do { rows = try await api.list(prefix) } catch { self.error = error.localizedDescription } }
}
struct DatabaseDetail: View {
    let api: PanelAPI
    let server: Server
    let initial: JSON
    let changed: () async -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var value: JSON = .null
    @State private var reveal = false
    @State private var error: String?
    @State private var operation: Operation?
    var row: JSON { value == .null ? initial : value }
    var db: DatabaseInfo { DatabaseInfo(value: row) }
    var body: some View {
        NavigationStack {
            Form {
                Section("Verbindung") {
                    copyRow("Datenbank", db.name); copyRow("Host", db.host); copyRow("Port", db.port); copyRow("Benutzer", row["username"].string)
                    copyRow("Erlaubte Quelladresse", row["connections_from"].string)
                }
                Section("Passwort") {
                    if !db.password.isEmpty { HStack { Text(reveal ? db.password : "••••••••••••").font(.system(.body, design: .monospaced)).textSelection(.enabled); Spacer(); Button(reveal ? "Verbergen" : "Anzeigen", systemImage: reveal ? "eye.slash" : "eye") { reveal.toggle() }.labelStyle(.iconOnly); Button("Kopieren", systemImage: "doc.on.doc") { UIPasteboard.general.string = db.password }.labelStyle(.iconOnly) } }
                    else { Button("Passwort laden", systemImage: "key") { Task { await password() } } }
                    Button("Passwort erneuern", systemImage: "arrow.clockwise", role: .destructive) { operation = Operation(title: "Passwort erneuern", detail: "Plugin-Konfigurationen müssen danach das neue Passwort verwenden.", path: "servers/\(server.id)/databases/\(row["id"].string)/rotate-password", destructive: true, submit: "Erneuern") }
                }
                Section {
                    Button("Verbindungsdaten kopieren", systemImage: "doc.on.doc") { UIPasteboard.general.string = "Host: \(db.host)\nPort: \(db.port)\nDatabase: \(db.name)\nUser: \(row["username"].string)" }
                    Button("Datenbank löschen", systemImage: "trash", role: .destructive) { operation = Operation(title: "Datenbank löschen", detail: "Alle Tabellen und Daten dieser Datenbank werden endgültig gelöscht.", path: "servers/\(server.id)/databases/\(row["id"].string)", method: "DELETE", destructive: true, submit: "Endgültig löschen") }
                }
            }.panelScreen().navigationTitle(db.name).navigationBarTitleDisplayMode(.inline).privacySensitive().errorAlert($error)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Fertig") { dismiss() } } }
                .sheet(item: $operation) { op in OperationSheet(api: api, operation: op) { await changed(); if op.method == "DELETE" { dismiss() } else { value = .null; reveal = false; await password() } } }
        }
    }
    func copyRow(_ title: String, _ text: String) -> some View { HStack { VStack(alignment: .leading, spacing: 5) { Text(title).font(.caption).foregroundStyle(.secondary); Text(text.isEmpty ? "–" : text).textSelection(.enabled) }; Spacer(); if !text.isEmpty { Button("Kopieren", systemImage: "doc.on.doc") { UIPasteboard.general.string = text }.labelStyle(.iconOnly).buttonStyle(.borderless) } } }
    func password() async { do { let rows = try await api.list("servers/\(server.id)/databases", query: ["include": "password"]); guard let found = rows.first(where: { $0["id"] == initial["id"] }) else { throw PanelError(message: "Datenbank nicht mehr vorhanden.") }; value = found; if db.password.isEmpty { error = "Der Provider liefert kein Passwort oder dein Account besitzt dafür keine Berechtigung." } } catch { self.error = error.localizedDescription } }
}
