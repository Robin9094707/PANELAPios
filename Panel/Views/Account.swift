import SwiftUI
import CoreImage.CIFilterBuiltins

struct AccountView: View {
    let api: PanelAPI
    @State private var account: JSON = .null
    @State private var operation: Operation?
    @State private var error: String?
    var body: some View {
        List {
            Section("Account") {
                LabeledContent("Benutzername", value: account["username"].string)
                LabeledContent("E-Mail", value: account["email"].string)
                LabeledContent("Sprache", value: account["language"].string)
            }
            Section("Sicherheit") {
                Button("E-Mail ändern", systemImage: "envelope") { operation = Operation(title: "E-Mail ändern", path: "account/email", method: "PUT", fields: [Field(key: "email", label: "Neue E-Mail", value: account["email"].string), Field(key: "password", label: "Account-Passwort", kind: .secret)]) }
                Button("Passwort ändern", systemImage: "key") { operation = Operation(title: "Passwort ändern", path: "account/password", method: "PUT", fields: [Field(key: "current_password", label: "Aktuelles Passwort", kind: .secret), Field(key: "password", label: "Neues Passwort", kind: .secret), Field(key: "password_confirmation", label: "Neues Passwort wiederholen", kind: .secret)], destructive: true) }
                NavigationLink { TwoFactorView(api: api) } label: { Label("Zwei-Faktor-Authentifizierung", systemImage: "lock.shield") }
                NavigationLink { AccountKeysView(api: api, ssh: false) } label: { Label("API-Schlüssel", systemImage: "key.horizontal") }
                NavigationLink { AccountKeysView(api: api, ssh: true) } label: { Label("SSH-Schlüssel", systemImage: "terminal") }
                NavigationLink { ActivityView(api: api, path: "account/activity") } label: { Label("Account-Aktivitäten", systemImage: "clock.arrow.circlepath") }
            }
        }.panelScreen().navigationTitle("Account").navigationBarTitleDisplayMode(.inline).task { await load() }.errorAlert($error)
            .sheet(item: $operation) { OperationSheet(api: api, operation: $0) { await load() } }
    }
    func load() async { do { account = try await api.request("account").attributes } catch { self.error = error.localizedDescription } }
}
struct AccountKeysView: View {
    let api: PanelAPI
    let ssh: Bool
    @State private var rows: [JSON] = []
    @State private var operation: Operation?
    @State private var create = false
    @State private var result: DetailItem?
    @State private var error: String?
    var path: String { ssh ? "account/ssh-keys" : "account/api-keys" }
    var body: some View {
        List {
            ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                VStack(alignment: .leading, spacing: 8) {
                    Text(row[ssh ? "name" : "description"].string).font(.headline)
                    Text(row[ssh ? "fingerprint" : "identifier"].string).font(.system(.caption, design: .monospaced)).foregroundStyle(.secondary).textSelection(.enabled)
                    Button("Entfernen", systemImage: "trash", role: .destructive) {
                        operation = Operation(title: "Schlüssel entfernen", detail: "Anwendungen, die diesen Schlüssel verwenden, verlieren den Zugriff.", path: ssh ? path + "/remove" : path + "/" + row["identifier"].string, method: ssh ? "POST" : "DELETE", fixed: ssh ? ["fingerprint": row["fingerprint"]] : [:], destructive: true, submit: "Entfernen")
                    }
                }.padding(.vertical, 5)
            }
            if rows.isEmpty { ContentUnavailableView("Keine Schlüssel", systemImage: "key") }
        }.panelScreen().navigationTitle(ssh ? "SSH-Schlüssel" : "API-Schlüssel").navigationBarTitleDisplayMode(.inline).task { await load() }.refreshable { await load() }.errorAlert($error)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Schlüssel erstellen", systemImage: "plus") { create = true } } }
            .sheet(item: $operation) { OperationSheet(api: api, operation: $0) { await load() } }
            .sheet(isPresented: $create) {
                ActionForm(title: ssh ? "SSH-Schlüssel hinzufügen" : "Client-Schlüssel erstellen", fields: ssh ? [Field(key: "name", label: "Name"), Field(key: "public_key", label: "Öffentlicher SSH-Schlüssel", kind: .multiline, hint: "Nur den öffentlichen Schlüssel einfügen, niemals einen privaten Schlüssel.")] : [Field(key: "description", label: "Beschreibung"), Field(key: "allowed_ips", label: "Erlaubte IP-Adressen", kind: .strings, hint: "Optional. Eine Adresse pro Zeile; leer erlaubt alle Adressen.")], submit: "Erstellen") { values in
                    guard !api.demo else { throw PanelError(message: "Im Demomodus nicht verfügbar.") }
                    let response = try await api.request(path, method: "POST", body: .object(values))
                    await load()
                    // Present only after the creation sheet has closed.
                    if !ssh { Task { try? await Task.sleep(for: .milliseconds(500)); result = DetailItem(data: response) } }
                }
            }
            .sheet(item: $result) { item in NavigationStack {
                VStack(alignment: .leading, spacing: 16) {
                    Label("Schlüssel jetzt sicher speichern", systemImage: "key.fill").font(.headline)
                    Text("Das Panel zeigt den vollständigen Schlüssel nur bei der Erstellung. Er ist in der folgenden Antwort enthalten.").foregroundStyle(.secondary)
                    ScrollView { Text(item.data.pretty).font(.system(.footnote, design: .monospaced)).textSelection(.enabled) }
                }.padding().panelScreen().navigationTitle("Neuer API-Schlüssel").toolbar { ToolbarItem(placement: .confirmationAction) { Button("Fertig") { result = nil } } }.privacySensitive()
            } }
    }
    func load() async { do { rows = try await api.list(path) } catch { self.error = error.localizedDescription } }
}
struct TwoFactorView: View {
    let api: PanelAPI
    @State private var setup: JSON = .null
    @State private var error: String?
    @State private var enable = false
    @State private var operation: Operation?
    @State private var tokens: [String] = []
    var body: some View {
        List {
            Section {
                Text("Sichere deinen Panel-Account mit einer Authenticator-App.").foregroundStyle(.secondary)
                Button("Einrichtung laden", systemImage: "qrcode") { Task { do { setup = try await api.request("account/two-factor")["data"] } catch { self.error = error.localizedDescription } } }
            } footer: { Text("Wenn 2FA bereits aktiv ist, meldet das Panel dies beim Laden der Einrichtung.") }
            if setup != .null {
                Section("Authenticator einrichten") {
                    if let image = qrImage(setup["image_url_data"].string.isEmpty ? setup["image_url"].string : setup["image_url_data"].string) { Image(uiImage: image).interpolation(.none).resizable().scaledToFit().frame(maxWidth: 220).padding().background(.white, in: RoundedRectangle(cornerRadius: 16)).frame(maxWidth: .infinity) }
                    Text(setup["secret"].string).font(.system(.body, design: .monospaced)).textSelection(.enabled)
                    Text("Übernimm den Schlüssel in deine Authenticator-App und bestätige mit dem sechsstelligen Code.").font(.caption).foregroundStyle(.secondary)
                    Button("Aktivierung bestätigen", systemImage: "checkmark.shield") { enable = true }
                }
            }
            if !tokens.isEmpty {
                Section("Wiederherstellungscodes – sicher aufbewahren") {
                    ForEach(tokens, id: \.self) { Text($0).font(.system(.body, design: .monospaced)).textSelection(.enabled) }
                    Text("Diese Codes werden nur jetzt angezeigt. Bewahre sie außerhalb dieses Geräts sicher auf.").font(.caption).foregroundStyle(.secondary)
                }
            }
            Section {
                Button("2FA deaktivieren", systemImage: "lock.open", role: .destructive) { operation = Operation(title: "2FA deaktivieren", detail: "Der zusätzliche Anmeldeschutz deines Panel-Accounts wird entfernt.", path: "account/two-factor/disable", fields: [Field(key: "password", label: "Account-Passwort", kind: .secret)], destructive: true, submit: "Deaktivieren") }
            }
        }.panelScreen().navigationTitle("Zwei-Faktor-Schutz").navigationBarTitleDisplayMode(.inline).errorAlert($error).privacySensitive()
            .sheet(item: $operation) { OperationSheet(api: api, operation: $0) { setup = .null; tokens = [] } }
            .sheet(isPresented: $enable) {
                ActionForm(title: "2FA aktivieren", fields: [Field(key: "code", label: "Sechsstelliger Code"), Field(key: "password", label: "Account-Passwort", kind: .secret)], submit: "Aktivieren") { values in
                    guard !api.demo else { throw PanelError(message: "Im Demomodus nicht verfügbar.") }
                    let response = try await api.request("account/two-factor", method: "POST", body: .object(values))
                    tokens = response.attributes["tokens"].array.map(\.string); setup = .null
                }
            }
    }
    func qrImage(_ text: String) -> UIImage? {
        guard !text.isEmpty else { return nil }
        let filter = CIFilter.qrCodeGenerator(); filter.message = Data(text.utf8)
        guard let image = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 8, y: 8)), let cg = CIContext().createCGImage(image, from: image.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}
