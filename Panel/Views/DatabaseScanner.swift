import SwiftUI

struct DatabaseScannerView: View {
    let api: PanelAPI
    let server: Server
    let initial: JSON
    @State private var result: JSON = .null
    @State private var busy = false
    @State private var failure: String?
    @State private var attempted = false
    @State private var password = ""
    @State private var remember = false
    @State private var allowPlaintext = false
    @State private var usingOverride = false
    @State private var search = ""
    var credentialKey: String { DatabasePasswords.key(api, server: server, database: initial) }
    var tables: [JSON] { result["tables"].array.filter { search.isEmpty || $0["name"].string.localizedCaseInsensitiveContains(search) } }
    var body: some View {
        List {
            Section {
                Label("Nur lesender Metadaten-Scan", systemImage: "externaldrive.badge.checkmark").font(.headline).foregroundStyle(Color.panelAccent)
                Text("Deine Admin Suite verbindet sich vom Minecraft-Server aus. Keine Tabelleninhalte und keine eigenen SQL-Befehle. Benötigt Admin Suite 8.5 mit full-Schlüssel.").font(.footnote).foregroundStyle(.secondary)
                if usingOverride { Label("Eigenes Scanner-Passwort verwendet", systemImage: "key.fill").font(.caption) }
                Button("Mit gespeichertem Passwort scannen", systemImage: "arrow.clockwise") { Task { await scan(custom: false) } }.disabled(busy)
                if busy { ProgressView("Datenbank wird gelesen …") }
            }
            if let failure {
                Section("Verbindung nicht bestätigt") {
                    Text(failure).foregroundStyle(.orange).textSelection(.enabled)
                    SecureField("Eigenes Datenbankpasswort", text: $password).textInputAutocapitalization(.never).autocorrectionDisabled()
                    Toggle("Passwort für diesen Scanner merken", isOn: $remember)
                    Toggle("Datenbank ohne TLS verbinden", isOn: $allowPlaintext)
                    Text("Ohne TLS können Passwort und Metadaten zwischen Minecraft-Server und Datenbank unverschlüsselt übertragen werden. Die App-Verbindung zur Suite bleibt HTTPS.").font(.caption).foregroundStyle(.secondary)
                    Button("Mit eigenem Passwort erneut prüfen") { Task { await scan(custom: true) } }.disabled(busy)
                }
            }
            if DatabasePasswords.read(credentialKey) != nil {
                Section { Button("Eigenes Scanner-Passwort vergessen", role: .destructive) { do { try DatabasePasswords.save(nil, key: credentialKey); usingOverride = false } catch { failure = error.localizedDescription } }.disabled(busy) }
            }
            if result["ok"].bool {
                Section("Speicher & Datenbank") {
                    LabeledContent("Daten", value: bytes(result["dataBytes"].double))
                    LabeledContent("Indizes", value: bytes(result["indexBytes"].double))
                    LabeledContent("Zusammen", value: bytes(result["dataBytes"].double + result["indexBytes"].double))
                    LabeledContent("Tabellen", value: result["tableCount"].string)
                    LabeledContent("Zeilen (Schätzung)", value: result["estimatedRows"].string)
                    LabeledContent("Datenbankversion", value: result["version"].string)
                    LabeledContent("Speicherlimit / frei", value: "Vom Provider nicht gemeldet")
                    if let date = SuitePlayerPresentation.date(result["scannedAt"]) { LabeledContent("Stand", value: date.formatted(date: .abbreviated, time: .standard)) }
                    Text("Größen stammen aus information_schema. Zeilenzahlen sind Engine-Schätzungen. Das Panel meldet kein Datenbank-Speicherkontingent; freier Platz wird nicht geraten.").font(.caption).foregroundStyle(.secondary)
                }
                Section("Tabellen nach Größe") {
                    ForEach(tables, id: \.["name"].string) { table in
                        VStack(alignment: .leading, spacing: 5) {
                            Text(table["name"].string).font(.headline).textSelection(.enabled)
                            Text("\(table["engine"].string) · \(table["collation"].string)").font(.caption).foregroundStyle(.secondary)
                            Text("Daten \(amount(table["dataBytes"])) · Index \(amount(table["indexBytes"])) · ~\(table["estimatedRows"] == .null ? "?" : table["estimatedRows"].string) Zeilen").font(.caption.monospaced())
                        }
                    }
                    if result["truncated"].bool { Text("Die 500 größten Tabellen werden angezeigt. Die Gesamtsumme berücksichtigt alle Tabellen.").font(.caption) }
                }
            }
        }.panelScreen().navigationTitle("Datenbankscanner").searchable(text: $search, prompt: "Tabelle suchen").privacySensitive()
            .task { guard !attempted else { return }; attempted = true; await scan(custom: false) }
            .interactiveDismissDisabled(busy)
    }
    func amount(_ value: JSON) -> String { value == .null ? "unbekannt" : bytes(value.double) }
    func scan(custom: Bool) async {
        guard !busy else { return }; busy = true; defer { busy = false }; result = .null
        do {
            guard !api.demo, let connection = SuiteConnections.shared.get(api, server) else { throw PanelError(message: "Verbinde zuerst diesen Server unter Verwalten → Admin Suite mit der Suite-API.") }
            let suite = try SuiteAPI(connection), health = try await suite.health()
            guard health["features"].array.contains(.string("database-inspector")) else { throw PanelError(message: "Aktualisiere die Admin Suite auf 8.5 oder neuer. Die bisherige Verbindung bleibt gültig.") }
            guard health["scope"].string == "full" else { throw PanelError(message: "Der Scanner benötigt einen full-Schlüssel der Admin Suite.") }
            let override = DatabasePasswords.read(credentialKey)
            let rows = try await api.list("servers/\(server.id)/databases", query: custom || override != nil ? [:] : ["include": "password"])
            guard let row = rows.first(where: { $0["id"] == initial["id"] }) else { throw PanelError(message: "Datenbank nicht mehr vorhanden.") }
            let db = DatabaseInfo(value: row)
            let secret = custom ? password : override ?? db.password
            guard custom || !secret.isEmpty else { throw PanelError(message: "Das Panel liefert kein Passwort. Du kannst unten ein eigenes verwenden.") }
            let response = try await suite.request("database/inspect", method: "POST", body: .object(["host": .string(db.host), "port": .string(db.port), "database": .string(db.name), "username": row["username"], "password": .string(secret), "allowPlaintext": .bool(allowPlaintext)]))
            guard response["ok"].bool else { throw PanelError(message: response["message"].string) }
            if custom && remember { try DatabasePasswords.save(secret, key: credentialKey) }
            usingOverride = custom || override != nil; result = response; failure = nil; password = ""
        } catch { if !RequestFailure.isCancellation(error) { failure = error.localizedDescription } }
    }
}
