import SwiftUI
import AuthenticationServices
import CoreImage.CIFilterBuiltins

// MARK: - Second sign-in step (Admin Suite 9.8)

/// Pending password sign-in that needs another step before the token can be saved.
struct SuitePendingLogin: Identifiable {
    let id = UUID()
    let api: SuiteAPI
    let record: SuiteConnection
    let challenge: SuiteLoginChallenge
}

/// New password after an emergency reset, then the 6-digit code or a recovery code.
struct SuiteSecondStepView: View {
    let api: SuiteAPI
    let completion: (String) async -> Void
    @State private var challenge: SuiteLoginChallenge
    @Environment(\.dismiss) private var dismiss
    @State private var code = ""
    @State private var password = ""
    @State private var repeated = ""
    @State private var recovery = false
    @State private var busy = false
    @State private var error: String?
    init(api: SuiteAPI, challenge: SuiteLoginChallenge, completion: @escaping (String) async -> Void) {
        self.api = api; self.completion = completion; _challenge = State(initialValue: challenge)
    }
    var body: some View {
        NavigationStack {
            Form {
                Section { header }
                if challenge.kind == .newPassword { passwordSection } else { codeSection }
                Section {
                    Button { Task { await submit() } } label: { HStack { Spacer(); if busy { ProgressView() } else { Text("Bestätigen").bold() }; Spacer() } }
                        .disabled(!ready || busy).accessibilityIdentifier("suite-second-step-submit")
                }
            }
            .panelScreen().navigationTitle(challenge.kind == .newPassword ? "Neues Passwort" : "Zwei-Faktor").navigationBarTitleDisplayMode(.inline).errorAlert($error)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() }.disabled(busy) } }
            .onChange(of: code) { _, value in autoSubmit(value) }
        }.interactiveDismissDisabled(busy)
    }
    private var header: some View {
        VStack(alignment: .leading, spacing: 8) {
            Image(systemName: challenge.kind == .newPassword ? "key.fill" : "lock.shield.fill").font(.system(size: 38)).foregroundStyle(Color.panelAccent)
            Text(challenge.kind == .newPassword ? "Eigenes Passwort festlegen" : "Code bestätigen").font(.title3.bold())
            Text(challenge.username.isEmpty ? "Admin Suite" : "Angemeldet als " + challenge.username).foregroundStyle(.secondary)
        }.padding(.vertical, 4)
    }
    private var passwordSection: some View {
        Section {
            SecureField("Neues Passwort (mind. 12 Zeichen)", text: $password).textContentType(.newPassword)
            SecureField("Passwort wiederholen", text: $repeated).textContentType(.newPassword)
        } footer: {
            Text(challenge.message.isEmpty ? "Nach einem Notfall-Reset muss ein neues Passwort gewählt werden." : challenge.message)
        }
    }
    private var codeSection: some View {
        Section {
            TextField(recovery ? "XXXX-XXXX-XXXX" : "6-stelliger Code", text: $code)
                .keyboardType(recovery ? .asciiCapable : .numberPad).textContentType(.oneTimeCode)
                .font(.title2.monospacedDigit()).textInputAutocapitalization(.characters).autocorrectionDisabled()
                .accessibilityIdentifier("suite-second-step-code")
            Button(recovery ? "Authenticator-Code verwenden" : "Wiederherstellungscode verwenden") { recovery.toggle(); code = "" }
        } footer: {
            Text(recovery ? "Jeder Wiederherstellungscode funktioniert genau einmal." : "Code aus deiner Authenticator-App (z. B. Apple Passwörter).")
        }
    }
    private var ready: Bool {
        if challenge.kind == .newPassword { return password.count >= 12 && password == repeated }
        return recovery ? code.filter { $0.isLetter || $0.isNumber }.count == 12 : code.count == 6
    }
    private func autoSubmit(_ value: String) {
        guard !recovery, challenge.kind == .secondFactor else { return }
        let digits = String(value.filter(\.isNumber).prefix(6))
        if digits != value { code = digits; return }
        if digits.count == 6 && !busy { Task { await submit() } }
    }
    private func submit() async {
        guard ready, !busy else { return }
        busy = true; defer { busy = false }
        do {
            let token = challenge.kind == .newPassword ? try await api.verify(challenge: challenge.challenge, newPassword: password) : try await api.verify(challenge: challenge.challenge, code: code)
            password = ""; repeated = ""; code = ""
            await completion(token)
            dismiss()
        } catch let next as SuiteLoginChallenge {
            challenge = next; password = ""; repeated = ""; code = ""; recovery = false
        } catch {
            code = ""
            if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription }
        }
    }
}

// MARK: - Passkey sign-in through the browser

/// Passkeys need a domain with a trusted certificate. The app opens the web panel in a private Safari sheet;
/// after Face ID / Touch ID the page returns a one-time code to panel://suite-auth, which the app exchanges.
struct SuitePasskeySignInButton: View {
    let endpoint: String
    let fingerprint: String
    let completion: (String) async -> Void
    @Environment(\.webAuthenticationSession) private var webAuthenticationSession
    @State private var busy = false
    @State private var error: String?
    static func possible(endpoint: String, fingerprint: String) -> Bool {
        guard fingerprint.trimmingCharacters(in: .whitespaces).isEmpty, let url = try? SuiteAPI.endpoint(endpoint), let host = url.host?.lowercased() else { return false }
        return host.contains(".") && host.range(of: "[a-z]", options: .regularExpression) != nil && host.range(of: "^[0-9.:]+$", options: .regularExpression) == nil
    }
    var body: some View {
        Button { Task { await start() } } label: { if busy { ProgressView() } else { Label("Mit Passkey anmelden", systemImage: "person.badge.key.fill") } }
            .disabled(busy).errorAlert($error).accessibilityIdentifier("suite-passkey-sign-in")
    }
    private func start() async {
        busy = true; defer { busy = false }
        do {
            let base = try SuiteAPI.endpoint(endpoint)
            guard var components = URLComponents(url: base, resolvingAgainstBaseURL: false) else { throw PanelError(message: "Ungültige Adresse.") }
            let state = UUID().uuidString
            components.path = "/"; components.query = nil; components.fragment = "/app-login?state=" + state
            guard let url = components.url else { throw PanelError(message: "Ungültige Adresse.") }
            let callback = try await webAuthenticationSession.authenticate(using: url, callbackURLScheme: "panel", preferredBrowserSession: .ephemeral)
            let items = URLComponents(url: callback, resolvingAgainstBaseURL: false)?.queryItems ?? []
            guard callback.host == "suite-auth", items.first(where: { $0.name == "state" })?.value == state,
                  let code = items.first(where: { $0.name == "code" })?.value, code.hasPrefix("sasx_"), code.count <= 200 else { throw PanelError(message: "Die Anmeldung im Browser wurde nicht bestätigt.") }
            await completion(code)
        } catch {
            if let auth = error as? ASWebAuthenticationSessionError, auth.code == .canceledLogin { return }
            if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription }
        }
    }
}

// MARK: - Account & security hub

struct SuiteAccountView: View {
    let suite: SuiteAPI
    @State private var account: JSON = .null
    @State private var error: String?
    var isUser: Bool { account["kind"].string == "user" }
    var body: some View {
        List {
            Section { identity }
            if account["canMigrate"].bool { migrationSection }
            if suite.features.contains("account-rename-v1") || suite.features.contains("public-name-v1") {
                Section { NavigationLink { SuiteAccountNameView(suite: suite) } label: { Label(isUser ? "Benutzername & öffentlicher Name" : "Öffentlicher Name", systemImage: "person.text.rectangle") } } footer: { Text("Spieler sehen bei Ticketantworten nur den öffentlichen Namen – nie deinen Login.") }
            }
            if isUser { securitySection }
            Section("Geräte") {
                NavigationLink { SuiteSessionsView(suite: suite) } label: { Label(account["manager"].bool ? "Alle Geräte & Sitzungen" : "Meine Geräte", systemImage: "laptopcomputer.and.iphone") }
            }
            connectionSection
            Section("Notfall") {
                Text(account["owner"].bool || !isUser ? "Alles verloren? In der Serverkonsole „sas app owner-reset“ eingeben. Du bekommst deinen Benutzernamen und ein Einmal-Passwort; 2FA und Passkeys werden entfernt und du legst sofort ein neues Passwort fest." : "Zugang verloren? Der Hauptadmin kann deine Zwei-Faktor-Einrichtung zurücksetzen und dir ein neues Passwort geben.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
        }
        .panelScreen().navigationTitle("Konto & Sicherheit").task { await load() }.refreshable { await load() }.errorAlert($error)
    }
    private var identity: some View {
        HStack(spacing: 14) {
            Image(systemName: account["owner"].bool ? "crown.fill" : isUser ? "person.crop.circle.fill" : "key.horizontal.fill")
                .font(.title).foregroundStyle(account["owner"].bool ? .yellow : Color.panelAccent).frame(width: 44)
            VStack(alignment: .leading, spacing: 3) {
                Text(isUser ? account["username"].string : account["label"].string).font(.headline)
                Text(isUser ? (account["owner"].bool ? "Hauptadmin" : "Benutzerkonto") : "API-Schlüssel (" + account["scope"].string + ")").font(.subheadline).foregroundStyle(.secondary)
                if isUser { Text(account["mfa"].bool ? "2FA aktiv" : "2FA aus").font(.caption.bold()).foregroundStyle(account["mfa"].bool ? Color.green : Color.orange) }
            }
        }.padding(.vertical, 4)
    }
    private var migrationSection: some View {
        Section {
            NavigationLink { SuiteMigrationView(suite: suite) { await load() } } label: { Label("Zu Benutzername & Passwort wechseln", systemImage: "arrow.triangle.2.circlepath") }
        } footer: {
            Text("Dein bisheriger Hauptschlüssel wird zum Hauptadmin-Konto. Danach kannst du 2FA und Passkeys nutzen und dich auch im Webpanel anmelden. Alle Daten bleiben erhalten.")
        }
    }
    private var securitySection: some View {
        Section("Sicherheit") {
            NavigationLink { SuitePasswordView(suite: suite) } label: { Label("Passwort ändern", systemImage: "key.fill") }
            NavigationLink { SuiteTwoFactorView(suite: suite) } label: { LabeledContent { Text(account["mfa"].bool ? "Aktiv" : "Aus") } label: { Label("Zwei-Faktor-Schutz", systemImage: "lock.shield.fill") } }
            NavigationLink { SuitePasskeysView(suite: suite) } label: { LabeledContent { Text(String(account["passkeys"].array.count)) } label: { Label("Passkeys", systemImage: "person.badge.key.fill") } }
        }
    }
    private var connectionSection: some View {
        Section {
            NavigationLink { SuiteAddressView(suite: suite) } label: { Label("Adresse oder Domain ändern", systemImage: "network") }
            if let url = webURL { Link(destination: url) { Label("Webpanel im Browser öffnen", systemImage: "safari") } }
            if account["manager"].bool {
                NavigationLink { SuiteWebDomainView(suite: suite, current: account["domain"].string) { await load() } } label: {
                    LabeledContent { Text(account["domain"].string.isEmpty ? "Keine" : account["domain"].string).lineLimit(1) } label: { Label("Domain für Webpanel", systemImage: "globe") }
                }
            }
        } header: { Text("Verbindung & Webpanel") } footer: {
            Text("Das Webpanel läuft auf demselben Port wie die App – mit denselben Benutzern, Rechten und Geräten.")
        }
    }
    private var webURL: URL? {
        let configured = account["webUrl"].string
        if !configured.isEmpty, let url = URL(string: configured) { return url }
        return try? SuiteAPI.endpoint(suite.connection.endpoint)
    }
    private func load() async {
        do { account = try await suite.request("account") } catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }
}

// MARK: - Migration of the main key

struct SuiteMigrationView: View {
    let suite: SuiteAPI
    let done: () async -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var username = ""
    @State private var password = ""
    @State private var repeated = ""
    @State private var revokeKey = false
    @State private var busy = false
    @State private var error: String?
    @State private var finished = false
    var body: some View {
        Form {
            Section {
                VStack(alignment: .leading, spacing: 8) {
                    Image(systemName: finished ? "checkmark.seal.fill" : "crown.fill").font(.system(size: 38)).foregroundStyle(finished ? Color.green : Color.yellow)
                    Text(finished ? "Hauptadmin-Konto aktiv" : "Hauptadmin-Konto erstellen").font(.title3.bold())
                    Text(finished ? "Diese App nutzt jetzt dein Benutzerkonto. Richte als Nächstes den Zwei-Faktor-Schutz ein." : "Wähle Benutzername und Passwort für deinen Hauptadmin-Zugang. Damit meldest du dich in der App und im Webpanel an.").foregroundStyle(.secondary)
                }.padding(.vertical, 4)
            }
            if finished {
                Section { NavigationLink { SuiteTwoFactorView(suite: suite) } label: { Label("Zwei-Faktor-Schutz einrichten", systemImage: "lock.shield.fill") } }
            } else { form }
        }
        .panelScreen().navigationTitle("Hauptadmin-Konto").navigationBarTitleDisplayMode(.inline).errorAlert($error)
    }
    @ViewBuilder private var form: some View {
        Section("Zugang") {
            TextField("Benutzername (z. B. Minecraft-Name)", text: $username).textContentType(.username).textInputAutocapitalization(.never).autocorrectionDisabled()
            SecureField("Passwort (mind. 12 Zeichen)", text: $password).textContentType(.newPassword)
            SecureField("Passwort wiederholen", text: $repeated).textContentType(.newPassword)
        }
        Section {
            Toggle("Alten API-Schlüssel danach widerrufen", isOn: $revokeKey)
        } footer: {
            Text("Empfohlen, sobald alle deine Geräte umgestellt sind. Ohne Widerruf bleibt der Schlüssel gültig. Notfalls stellst du den Zugang über die Serverkonsole wieder her (sas app owner-reset).")
        }
        Section {
            Button { Task { await migrate() } } label: { HStack { Spacer(); if busy { ProgressView() } else { Label("Konto erstellen & wechseln", systemImage: "checkmark.shield.fill").bold() }; Spacer() } }
                .disabled(busy || username.trimmingCharacters(in: .whitespaces).isEmpty || password.count < 12 || password != repeated)
        }
    }
    private func migrate() async {
        busy = true; defer { busy = false }
        do {
            _ = try await suite.migrate(username: username.trimmingCharacters(in: .whitespaces).lowercased(), password: password, revokeKey: revokeKey)
            try SuiteConnections.shared.save(suite.connection, token: suite.sessionToken)
            _ = try? await suite.health()
            password = ""; repeated = ""; finished = true; Feedback.success()
            await done()
        } catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }
}

// MARK: - Password

struct SuitePasswordView: View {
    let suite: SuiteAPI
    @State private var current = ""
    @State private var fresh = ""
    @State private var repeated = ""
    @State private var busy = false
    @State private var error: String?
    @State private var notice = ""
    var body: some View {
        Form {
            Section("Aktuell") { SecureField("Aktuelles Passwort", text: $current).textContentType(.password) }
            Section {
                SecureField("Neues Passwort (mind. 12 Zeichen)", text: $fresh).textContentType(.newPassword)
                SecureField("Wiederholen", text: $repeated).textContentType(.newPassword)
            } header: { Text("Neu") } footer: { Text("Alle anderen Geräte dieses Benutzers werden abgemeldet; dieses Gerät bleibt angemeldet.") }
            Section {
                Button { Task { await save() } } label: { HStack { Spacer(); if busy { ProgressView() } else { Text("Passwort ändern").bold() }; Spacer() } }
                    .disabled(busy || current.isEmpty || fresh.count < 12 || fresh != repeated)
                if !notice.isEmpty { Label(notice, systemImage: "checkmark.circle.fill").foregroundStyle(.green) }
            }
        }.panelScreen().navigationTitle("Passwort ändern").navigationBarTitleDisplayMode(.inline).errorAlert($error)
    }
    private func save() async {
        busy = true; defer { busy = false }
        do {
            let reply = try await suite.post("account/password", ["current": .string(current), "next": .string(fresh)])
            current = ""; fresh = ""; repeated = ""; notice = reply["description"].string; Feedback.success()
        } catch { self.error = error.localizedDescription }
    }
}

// MARK: - Two-factor (TOTP)

enum SuiteQRCode {
    static func image(_ text: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8); filter.correctionLevel = "M"
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 10, y: 10)),
              let cg = CIContext().createCGImage(output, from: output.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}

struct SuiteTwoFactorView: View {
    let suite: SuiteAPI
    @State private var account: JSON = .null
    @State private var setup: JSON = .null
    @State private var code = ""
    @State private var recovery: [String] = []
    @State private var askPassword: String?
    @State private var password = ""
    @State private var busy = false
    @State private var error: String?
    var body: some View {
        Form {
            if setup != .null { setupSections } else if account["mfa"].bool { activeSections } else { inactiveSections }
        }
        .panelScreen().navigationTitle("Zwei-Faktor-Schutz").navigationBarTitleDisplayMode(.inline).errorAlert($error)
        .task { await load() }
        .sheet(isPresented: Binding(get: { !recovery.isEmpty }, set: { if !$0 { recovery = [] } })) { SuiteRecoveryCodesView(codes: recovery) }
        .alert(askPassword ?? "", isPresented: Binding(get: { askPassword != nil }, set: { if !$0 { askPassword = nil } })) {
            SecureField("Passwort", text: $password)
            Button("Abbrechen", role: .cancel) { password = "" }
            Button("Bestätigen") { let action = askPassword; Task { await confirmed(action) } }
        } message: { Text("Zur Sicherheit dein aktuelles Passwort eingeben.") }
    }
    @ViewBuilder private var inactiveSections: some View {
        Section {
            Label("Nicht aktiv", systemImage: "exclamationmark.shield").foregroundStyle(.orange)
            Text("Nach dem Passwort fragt die Suite – in der App und im Webpanel – zusätzlich nach einem 6-stelligen Code. Das funktioniert sofort, auch ohne eigene Domain.").font(.footnote).foregroundStyle(.secondary)
        }
        Section { Button { Task { await begin() } } label: { HStack { Spacer(); if busy { ProgressView() } else { Label("2FA einrichten", systemImage: "qrcode").bold() }; Spacer() } }.disabled(busy) }
    }
    @ViewBuilder private var setupSections: some View {
        Section {
            if let image = SuiteQRCode.image(setup["uri"].string) {
                Image(uiImage: image).interpolation(.none).resizable().scaledToFit().frame(width: 210, height: 210).padding(10).background(.white, in: RoundedRectangle(cornerRadius: 14)).frame(maxWidth: .infinity)
            }
            if let url = URL(string: setup["uri"].string) { Link(destination: url) { Label("In Passwörter auf diesem iPhone einrichten", systemImage: "key.viewfinder") } }
            Button { UIPasteboard.general.string = setup["secret"].string; Feedback.success() } label: { LabeledContent("Schlüssel kopieren") { Text(setup["secret"].string).font(.caption.monospaced()).lineLimit(1) } }
        } header: { Text("1. Mit Authenticator-App verbinden") } footer: { Text("QR-Code mit einem anderen Gerät scannen oder direkt in Apple Passwörter übernehmen.") }
        Section("2. Aktuellen Code eingeben") {
            TextField("6-stelliger Code", text: $code).keyboardType(.numberPad).textContentType(.oneTimeCode).font(.title2.monospacedDigit())
            Button { Task { await enable() } } label: { HStack { Spacer(); if busy { ProgressView() } else { Text("Aktivieren").bold() }; Spacer() } }.disabled(busy || code.filter(\.isNumber).count != 6)
        }
    }
    @ViewBuilder private var activeSections: some View {
        Section {
            Label("Aktiv", systemImage: "checkmark.shield.fill").foregroundStyle(.green)
            LabeledContent("Wiederherstellungscodes übrig", value: String(account["recoveryLeft"].int))
        }
        Section {
            Button("Neue Wiederherstellungscodes", systemImage: "arrow.clockwise") { askPassword = "Neue Wiederherstellungscodes" }
            Button("2FA deaktivieren", systemImage: "xmark.shield", role: .destructive) { askPassword = "2FA deaktivieren" }
        } footer: { Text("Neue Codes ersetzen alle bisherigen.") }
    }
    private func load() async { do { account = try await suite.request("account") } catch { self.error = error.localizedDescription } }
    private func begin() async { busy = true; defer { busy = false }; do { setup = try await suite.post("account/totp/begin") } catch { self.error = error.localizedDescription } }
    private func enable() async {
        busy = true; defer { busy = false }
        do {
            let reply = try await suite.post("account/totp/enable", ["code": .string(String(code.filter(\.isNumber)))])
            recovery = reply["recovery"].array.map(\.string); setup = .null; code = ""; Feedback.success(); await load()
        } catch { self.error = error.localizedDescription }
    }
    private func confirmed(_ action: String?) async {
        let secret = password; password = ""
        busy = true; defer { busy = false }
        do {
            if action == "2FA deaktivieren" { _ = try await suite.post("account/totp/disable", ["password": .string(secret)]) }
            else { recovery = try await suite.post("account/recovery", ["password": .string(secret)])["recovery"].array.map(\.string) }
            Feedback.success(); await load()
        } catch { self.error = error.localizedDescription }
    }
}

struct SuiteRecoveryCodesView: View {
    let codes: [String]
    @Environment(\.dismiss) private var dismiss
    var text: String { "Simplex Panel – Wiederherstellungscodes\nJeder Code funktioniert genau einmal.\n\n" + codes.joined(separator: "\n") }
    var body: some View {
        NavigationStack {
            List {
                Section { Label("Sicher aufbewahren – sie werden nur jetzt angezeigt.", systemImage: "exclamationmark.triangle.fill").foregroundStyle(.orange) }
                Section("Codes") { ForEach(codes, id: \.self) { Text($0).font(.body.monospaced()).textSelection(.enabled) } }
                Section {
                    Button("Kopieren", systemImage: "doc.on.doc") { UIPasteboard.general.string = text; Feedback.success() }
                    ShareLink(item: text) { Label("Teilen oder sichern", systemImage: "square.and.arrow.up") }
                }
            }.panelScreen().navigationTitle("Wiederherstellungscodes").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Gespeichert") { dismiss() } } }
        }.interactiveDismissDisabled()
    }
}

// MARK: - Passkeys

struct SuitePasskeysView: View {
    let suite: SuiteAPI
    @Environment(\.webAuthenticationSession) private var webAuthenticationSession
    @State private var account: JSON = .null
    @State private var renaming: JSON?
    @State private var name = ""
    @State private var busy = false
    @State private var error: String?
    var body: some View {
        List {
            Section {
                ForEach(account["passkeys"].array, id: \.["id"].string) { row in passkeyRow(row) }
                if account["passkeys"].array.isEmpty { Text("Noch keine Passkeys.").foregroundStyle(.secondary) }
            } footer: { Text("Passkeys melden dich mit Face ID oder Touch ID an – ohne Passwort und ohne Code.") }
            Section {
                Button { Task { await add() } } label: { if busy { ProgressView() } else { Label("Passkey hinzufügen", systemImage: "plus.circle.fill") } }
                    .disabled(busy || !account["passkeysAvailable"].bool)
            } footer: {
                Text(account["passkeysAvailable"].bool ? "Öffnet das Webpanel unter " + account["webUrl"].string + " in einem privaten Safari-Fenster." : "Passkeys benötigen eine eigene Domain mit gültigem HTTPS-Zertifikat. Der Hauptadmin trägt sie unter Konto & Sicherheit → Domain ein; 2FA funktioniert bereits ohne Domain.")
            }
        }
        .panelScreen().navigationTitle("Passkeys").task { await load() }.refreshable { await load() }.errorAlert($error)
        .alert("Passkey umbenennen", isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
            TextField("Name", text: $name)
            Button("Abbrechen", role: .cancel) {}
            Button("Speichern") { let id = renaming?["id"].string ?? ""; Task { await update(id, operation: "rename") } }
        }
    }
    private func passkeyRow(_ row: JSON) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Label(row["name"].string, systemImage: "person.badge.key.fill")
            Text("Hinzugefügt " + SuiteGermanTime.text(Date(timeIntervalSince1970: row["created"].double / 1000)) + (row["active"].bool ? "" : " · andere Domain")).font(.caption).foregroundStyle(.secondary)
        }
        .swipeActions {
            Button("Entfernen", systemImage: "trash", role: .destructive) { Task { await update(row["id"].string, operation: "delete") } }
            Button("Umbenennen", systemImage: "pencil") { name = row["name"].string; renaming = row }
        }
    }
    private func load() async { do { account = try await suite.request("account") } catch { self.error = error.localizedDescription } }
    private func update(_ id: String, operation: String) async {
        do { _ = try await suite.post("account/passkey/update", ["id": .string(id), "name": .string(name), "operation": .string(operation)]); await load() } catch { self.error = error.localizedDescription }
    }
    private func add() async {
        busy = true; defer { busy = false }
        do {
            let handoff = try await suite.post("account/handoff")
            guard var components = URLComponents(string: handoff["url"].string), components.scheme == "https" else { throw PanelError(message: "Keine Domain für Passkeys eingerichtet.") }
            components.fragment = "/handoff?next=passkey&code=" + handoff["code"].string
            guard let url = components.url else { throw PanelError(message: "Ungültige Webpanel-Adresse.") }
            _ = try await webAuthenticationSession.authenticate(using: url, callbackURLScheme: "panel", preferredBrowserSession: .ephemeral)
        } catch {
            if let auth = error as? ASWebAuthenticationSessionError, auth.code == .canceledLogin { await load(); return }
            if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription }
        }
        await load()
    }
}

// MARK: - Devices & sessions

struct SuiteSessionsView: View {
    let suite: SuiteAPI
    @State private var data: JSON = .null
    @State private var renaming: (JSON, String)?
    @State private var name = ""
    @State private var error: String?
    var manage: Bool { data["manage"].bool }
    var users: [String] { Array(Set(data["items"].array.map { $0["user"].string })).sorted { a, b in owner(a) != owner(b) ? owner(a) : a < b } }
    func owner(_ user: String) -> Bool { data["items"].array.contains { $0["user"].string == user && $0["owner"].bool } }
    var body: some View {
        List {
            Section { Text(manage ? "Als Hauptadmin siehst du alle Geräte mit ungefährem Namen und IP. Gesperrte Geräte bleiben angemeldet, erhalten aber keine Daten, bis du sie freigibst." : "Unbekanntes Gerät? Sofort abmelden und dein Passwort ändern.").font(.footnote).foregroundStyle(.secondary) }
            ForEach(users, id: \.self) { user in
                Section(user + (owner(user) ? " · Hauptadmin" : "")) {
                    ForEach(data["items"].array.filter { $0["user"].string == user }, id: \.["id"].string) { row in SuiteSessionRow(row: row, kind: "session", manage: manage, own: row["user"].string == suite.account["username"].string) { op in await act(row, kind: "session", op) } }
                }
            }
            if manage && !data["keys"].array.isEmpty {
                Section("API-Schlüssel") {
                    ForEach(data["keys"].array, id: \.["id"].string) { row in SuiteSessionRow(row: row, kind: "key", manage: manage, own: false) { op in await act(row, kind: "key", op) } }
                }
            }
        }
        .panelScreen().navigationTitle(manage ? "Geräte & Sitzungen" : "Meine Geräte").task { await load() }.refreshable { await load() }.errorAlert($error)
        .alert("Gerät umbenennen", isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
            TextField("Name", text: $name)
            Button("Abbrechen", role: .cancel) {}
            Button("Speichern") { if let target = renaming { Task { await send(target.0, kind: target.1, "rename") } } }
        }
    }
    private func load() async { do { data = try await suite.request("sessions") } catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } } }
    private func act(_ row: JSON, kind: String, _ operation: String) async {
        if operation == "rename" { name = row["label"].string; renaming = (row, kind); return }
        await send(row, kind: kind, operation)
    }
    private func send(_ row: JSON, kind: String, _ operation: String) async {
        do {
            _ = try await suite.post("sessions/update", ["id": row["id"], "operation": .string(operation), "name": .string(name), "kind": .string(kind)])
            Feedback.success(); await load()
        } catch { self.error = error.localizedDescription }
    }
}

struct SuiteSessionRow: View {
    let row: JSON
    let kind: String
    let manage: Bool
    let own: Bool
    let perform: (String) async -> Void
    @State private var confirm: String?
    var title: String {
        for key in ["label", "deviceName"] where !row[key].string.isEmpty { return row[key].string }
        if row["kind"].string == "web" { return [row["browser"].string, row["os"].string].filter { !$0.isEmpty }.joined(separator: " · ") }
        if kind == "key" { return row["user"].string }
        return row["model"].string.isEmpty ? "App" : row["model"].string
    }
    var symbol: String { kind == "key" ? "key.horizontal.fill" : row["kind"].string == "web" ? "safari" : "iphone" }
    var online: Bool { Date().timeIntervalSince1970 * 1000 - row["seen"].double < 300_000 }
    var details: String {
        var parts: [String] = []
        parts.append(kind == "key" ? "API-Schlüssel (" + row["scope"].string + ")" : row["kind"].string == "web" ? "Browser" : "App " + row["app"].string)
        if !row["os"].string.isEmpty && row["kind"].string != "web" { parts.append(row["os"].string) }
        if !row["ip"].string.isEmpty { parts.append("IP " + row["ip"].string) }
        return parts.joined(separator: " · ")
    }
    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: symbol).font(.title3).foregroundStyle(Color.panelAccent).frame(width: 30)
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text(title).font(.headline).lineLimit(1)
                    if row["current"].bool { Text("Dieses Gerät").font(.caption2.bold()).padding(.horizontal, 6).padding(.vertical, 2).background(Color.panelAccent.opacity(0.18), in: Capsule()) }
                }
                Text(details).font(.caption).foregroundStyle(.secondary).lineLimit(2)
                Text((online ? "Online · " : "Zuletzt ") + (row["seen"].double > 0 ? SuiteGermanTime.text(Date(timeIntervalSince1970: row["seen"].double / 1000)) : "nie")).font(.caption2).foregroundStyle(online ? Color.green : Color.secondary)
            }
            Spacer(minLength: 0)
            if row["locked"].bool { Image(systemName: "lock.fill").foregroundStyle(.red).accessibilityLabel("Gesperrt") }
        }
        .contextMenu { actions }
        .swipeActions(edge: .trailing) { actions }
        .confirmationDialog(confirm ?? "", isPresented: Binding(get: { confirm != nil }, set: { if !$0 { confirm = nil } }), titleVisibility: .visible) {
            Button(kind == "key" ? "Widerrufen" : "Abmelden", role: .destructive) { Task { await perform("logout") } }
        }
    }
    @ViewBuilder private var actions: some View {
        if manage || own { Button("Umbenennen", systemImage: "pencil") { Task { await perform("rename") } } }
        if manage && !row["current"].bool {
            Button(row["locked"].bool ? "Freigeben" : "Sperren", systemImage: row["locked"].bool ? "lock.open" : "lock") { Task { await perform(row["locked"].bool ? "unlock" : "lock") } }.tint(.orange)
        }
        if (manage || own) && !(kind == "key" && row["current"].bool) {
            Button(kind == "key" ? "Widerrufen" : "Abmelden", systemImage: "rectangle.portrait.and.arrow.right", role: .destructive) { confirm = kind == "key" ? "Schlüssel „" + title + "“ widerrufen?" : "„" + title + "“ abmelden?" }
        }
    }
}

// MARK: - Address / domain of this connection

/// Switches this connection to another address (e.g. the new domain) without signing in again.
struct SuiteAddressView: View {
    let suite: SuiteAPI
    @State private var endpoint = ""
    @State private var fingerprint = ""
    @State private var busy = false
    @State private var error: String?
    @State private var notice = ""
    var body: some View {
        Form {
            Section {
                TextField("https://admin.example.eu oder https://IP:Port", text: $endpoint).keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                TextField("SHA-256-Fingerabdruck (bei gültigem Zertifikat leer)", text: $fingerprint, axis: .vertical).font(.footnote.monospaced()).textInputAutocapitalization(.never).autocorrectionDisabled()
            } header: { Text("Neue Adresse") } footer: {
                Text("Bei einer Domain mit gültigem HTTPS-Zertifikat (z. B. über einen Reverse-Proxy) bleibt der Fingerabdruck leer. Die Anmeldung bleibt erhalten; die Serveridentität wird geprüft.")
            }
            Section {
                Button { Task { await save() } } label: { HStack { Spacer(); if busy { ProgressView() } else { Text("Prüfen & speichern").bold() }; Spacer() } }.disabled(busy || endpoint.isEmpty)
                if !notice.isEmpty { Label(notice, systemImage: "checkmark.circle.fill").foregroundStyle(.green) }
            }
        }
        .panelScreen().navigationTitle("Adresse ändern").navigationBarTitleDisplayMode(.inline).errorAlert($error)
        .task { if endpoint.isEmpty { endpoint = suite.connection.endpoint; fingerprint = suite.connection.fingerprint } }
    }
    private func save() async {
        busy = true; defer { busy = false }
        do {
            var record = suite.connection
            record.endpoint = try SuiteAPI.endpoint(endpoint).absoluteString
            record.fingerprint = try SuiteAPI.pin(fingerprint)
            let probe = try SuiteAPI(record, token: suite.sessionToken)
            let health = try await probe.health()
            guard suite.connection.serverIdentity.isEmpty || health["serverId"].string == suite.connection.serverIdentity else { throw PanelError(message: "Unter dieser Adresse antwortet ein anderer Server. Nicht gespeichert.") }
            record.serverIdentity = health["serverId"].string
            try SuiteConnections.shared.save(record, token: suite.sessionToken)
            notice = "Gespeichert. Beim nächsten Öffnen nutzt die App " + record.endpoint + "."; Feedback.success()
        } catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }
}

struct SuiteWebDomainView: View {
    let suite: SuiteAPI
    let current: String
    let done: () async -> Void
    @State private var domain = ""
    @State private var busy = false
    @State private var error: String?
    @State private var notice = ""
    var body: some View {
        Form {
            Section {
                TextField("admin.example.eu", text: $domain).keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
            } header: { Text("Domain (optional mit :Port)") } footer: {
                Text("Für Passkeys und ein Zertifikat ohne Warnung: DNS auf die Server-IP zeigen lassen und HTTPS einrichten (z. B. Caddy als Reverse-Proxy). Ohne Domain bleibt das Webpanel unter https://Server-IP:Port erreichbar; 2FA funktioniert immer.")
            }
            Section {
                Button { Task { await save(domain) } } label: { HStack { Spacer(); if busy { ProgressView() } else { Text("Speichern").bold() }; Spacer() } }.disabled(busy)
                if !current.isEmpty { Button("Domain entfernen", role: .destructive) { Task { await save("") } }.disabled(busy) }
                if !notice.isEmpty { Text(notice).font(.footnote).foregroundStyle(.green) }
            }
        }
        .panelScreen().navigationTitle("Domain").navigationBarTitleDisplayMode(.inline).errorAlert($error)
        .task { if domain.isEmpty { domain = current } }
    }
    private func save(_ value: String) async {
        busy = true; defer { busy = false }
        do { let reply = try await suite.post("web/domain", ["domain": .string(value.trimmingCharacters(in: .whitespaces))]); notice = reply["description"].string; domain = reply["domain"].string; Feedback.success(); await done() }
        catch { self.error = error.localizedDescription }
    }
}
