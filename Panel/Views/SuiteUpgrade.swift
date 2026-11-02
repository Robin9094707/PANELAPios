import SwiftUI

/// Additive tools for Suite 9.6. Existing keys use the same connection and Keychain record.
struct SuiteUpgradeView: View {
    let suite: SuiteAPI
    var body: some View {
        List {
            Section("Zugriff") {
                NavigationLink { SuiteUsersView(suite: suite) } label: { Label("App-Benutzer & Rechte", systemImage: "person.badge.key.fill") }.disabled(!suite.can("data.access"))
                NavigationLink { SuiteLinksView(suite: suite) } label: { Label("Discord-Verifizierungen", systemImage: "link") }.disabled(!suite.can("data.links"))
                if suite.can("action.notifications.update") { NavigationLink { SuiteBarkSettingsView(suite: suite) } label: { Label("Meine Bark-Benachrichtigungen", systemImage: "bell.badge") } }
                if suite.can("action.notifications.update") && suite.features.contains("quiet-hours-v1") { NavigationLink { SuiteQuietHoursView(suite: suite) } label: { Label("Ruhezeiten & Notfallneustart", systemImage: "moon.zzz.fill") } }
            }
            if suite.features.contains("app-settings-v1") || suite.features.contains("staff-chat-v1") {
                Section("Team & Einstellungen") {
                    if suite.features.contains("staff-chat-v1") { NavigationLink { SuiteStaffChatSettingsView(suite: suite) } label: { Label("Staff-Chat & Discord-Kanal", systemImage: "bubble.left.and.text.bubble.right") }.disabled(suite.scope != "full") }
                    if suite.features.contains("app-settings-v1") { NavigationLink { SuiteAppSettingsView(suite: suite) } label: { Label("Weitere Suite-Einstellungen", systemImage: "switch.2") }.disabled(!suite.can("data.app-settings")) }
                }
            }
            Section("Serverregeln") {
                NavigationLink { SuiteMemoryGuardView(suite: suite) } label: { Label("RAM-Notfallneustart", systemImage: "memorychip") }.disabled(!suite.can("data.memory"))
                NavigationLink { SuiteOnlineSettingsView(suite: suite) } label: { Label("Discord-Beitrittshinweise", systemImage: "person.crop.circle.badge.clock") }.disabled(!suite.can("data.online-settings"))
            }
            Section { Text("Bestehende API-Schlüssel und Verifizierungen bleiben erhalten. Ein gesperrtes Werkzeug kann mit diesem Benutzer nicht genutzt werden.").font(.footnote).foregroundStyle(.secondary) }
        }.panelScreen().navigationTitle("Zugänge & Serverregeln")
    }
}

@MainActor final class SuiteUpgradeModel: ObservableObject {
    @Published var data: JSON = .null
    @Published var busy = false
    @Published var error: String?
    @Published var plan: JSON?
    @Published var notice = ""
    let suite: SuiteAPI
    init(_ suite: SuiteAPI) { self.suite = suite }
    func load(_ route: String) async { busy = true; defer { busy = false }; do { data = try await suite.data(route) } catch { self.error = error.localizedDescription } }
    func prepare(_ action: String, _ values: [String: JSON]) async { busy = true; defer { busy = false }; do { plan = try await suite.prepare(action, values: values) } catch { self.error = error.localizedDescription } }
    func execute(_ route: String) async { guard let plan else { return }; busy = true; do { let reply = try await suite.execute(plan["ticket"].string); notice = reply["title"].string; self.plan = nil; data = try await suite.data(route); Feedback.success() } catch { self.error = error.localizedDescription; self.plan = nil }; busy = false }
}
private struct UpgradeConfirmation: View {
    @ObservedObject var model: SuiteUpgradeModel
    let route: String
    var body: some View {
        NavigationStack { List { Text(model.plan?["summary"].string ?? ""); Button("Bestätigen & speichern", role: .destructive) { Task { await model.execute(route) } }.disabled(model.busy); if model.busy { ProgressView() } }.navigationTitle("Änderung bestätigen").toolbar { ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { model.plan = nil }.disabled(model.busy) } } }.interactiveDismissDisabled(model.busy)
    }
}
private struct UpgradeFeedback: ViewModifier {
    @ObservedObject var model: SuiteUpgradeModel
    let route: String
    func body(content: Content) -> some View { content.errorAlert($model.error).sheet(isPresented: Binding(get: { model.plan != nil }, set: { if !$0 { model.plan = nil } })) { UpgradeConfirmation(model: model, route: route) } }
}

struct SuiteUsersView: View {
    let suite: SuiteAPI
    @StateObject private var model: SuiteUpgradeModel
    @State private var selected: JSON?
    @State private var adding = false
    init(suite: SuiteAPI) { self.suite = suite; _model = StateObject(wrappedValue: SuiteUpgradeModel(suite)) }
    var body: some View {
        List {
            Section { Text("Passwörter: mindestens 12 Zeichen. Änderungen an Passwort oder Rechten melden alle Sitzungen dieses Benutzers ab. Alte API-Schlüssel bleiben bestehen." + (suite.features.contains("setup-links-v1") ? " Tippe auf einen Benutzer, um einen einmaligen Setup-Link zu erstellen." : "")).font(.footnote).foregroundStyle(.secondary) }
            ForEach(model.data["items"].array, id: \.["username"].string) { row in
                HStack {
                    Button { selected = row } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Label(row["username"].string, systemImage: row["enabled"].bool ? "person.crop.circle.badge.checkmark" : "person.crop.circle.badge.xmark")
                            HStack(spacing: 8) {
                                if row["sessions"] != .null { Text(row["sessions"].int == 1 ? "1 Gerät angemeldet" : "\(row["sessions"].int) Geräte angemeldet") }
                                if row["setupExpires"].double > 0 { Text("· Setup-Link offen") .foregroundStyle(Color.panelAccent) }
                            }.font(.caption).foregroundStyle(.secondary)
                        }
                    }.disabled(!suite.can("action.accounts.update"))
                }
            }
            if !model.notice.isEmpty { Text(model.notice).foregroundStyle(.green) }
        }.panelScreen().navigationTitle("App-Benutzer").toolbar { Button("Neu", systemImage: "plus") { adding = true }.disabled(!suite.can("action.accounts.update")) }.task { await model.load("access") }.refreshable { await model.load("access") }.errorAlert($model.error)
            .sheet(isPresented: Binding(get: { adding || selected != nil }, set: { if !$0 { adding = false; selected = nil } })) { SuiteUserEditor(suite: suite, row: selected ?? .null, catalog: model.data["catalog"].array) { await model.load("access") } }
    }
}
struct SuiteUserEditor: View {
    let suite: SuiteAPI
    let row: JSON
    let catalog: [JSON]
    let saved: () async -> Void
    @StateObject private var model: SuiteUpgradeModel
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var password = ""
    @State private var enabled = true
    @State private var rights: Set<String> = []
    @State private var query = ""
    init(suite: SuiteAPI, row: JSON, catalog: [JSON], saved: @escaping () async -> Void) { self.suite = suite; self.row = row; self.catalog = catalog; self.saved = saved; _model = StateObject(wrappedValue: SuiteUpgradeModel(suite)) }
    var body: some View {
        NavigationStack { Form {
            Section("Benutzer") { TextField("Minecraft-Benutzername", text: $name).disabled(row != .null).textInputAutocapitalization(.never).autocorrectionDisabled(); SecureField(row == .null ? "Passwort (mindestens 12 Zeichen)" : "Neues Passwort (leer = behalten)", text: $password); Toggle("Zugang aktiv", isOn: $enabled) }
            Section("Rechte") {
                TextField("Rechte suchen", text: $query)
                ForEach(catalog.filter { query.isEmpty || ($0["title"].string + $0["id"].string).localizedCaseInsensitiveContains(query) }, id: \.["id"].string) { item in
                    Toggle(isOn: Binding(get: { rights.contains(item["id"].string) }, set: { if $0 { rights.insert(item["id"].string) } else { rights.remove(item["id"].string) } })) { VStack(alignment: .leading) { Text(item["title"].string); Text(item["id"].string).font(.caption.monospaced()).foregroundStyle(.secondary) } }
                }
            }
            if row != .null && row["enabled"].bool && suite.features.contains("setup-links-v1") { Section { NavigationLink { SuiteSetupLinkCreator(suite: suite, username: row["username"].string) } label: { Label("Setup-Link erstellen", systemImage: "link.badge.plus") } } footer: { Text("Einmalig gültig (Standard 1 Stunde). Der Empfänger muss nichts eintippen.") } }
            Section { Button("Benutzer speichern") { prepare("save") }.disabled(name.isEmpty || model.busy || (row == .null && password.count < 12) || (!password.isEmpty && password.count < 12)); if row != .null { Button("Benutzer löschen", role: .destructive) { prepare("delete") }.disabled(model.busy) } }
            if !model.notice.isEmpty { Text(model.notice).foregroundStyle(.green) }
        }.navigationTitle(row == .null ? "Neuer App-Benutzer" : name).toolbar { Button("Fertig") { Task { await saved(); dismiss() } }.disabled(model.busy) }.task { name = row["username"].string; enabled = row == .null || row["enabled"].bool; rights = Set(row["permissions"].array.map(\.string)) }.modifier(UpgradeFeedback(model: model, route: "access")) }.interactiveDismissDisabled(model.busy)
    }
    func prepare(_ operation: String) { Task { await model.prepare("accounts.update", ["username": .string(name), "password": .string(password), "enabled": .string(String(enabled)), "permissions": .string(rights.sorted().joined(separator: ",")), "operation": .string(operation)]); if model.plan != nil { password = "" } } }
}

struct SuiteLinksView: View {
    let suite: SuiteAPI
    @StateObject private var model: SuiteUpgradeModel
    @State private var player = ""
    @State private var discord = ""
    @State private var expected = ""
    @State private var selectedName = ""
    @State private var choosing = false
    init(suite: SuiteAPI) { self.suite = suite; _model = StateObject(wrappedValue: SuiteUpgradeModel(suite)) }
    var body: some View {
        List {
            Section("Vorhandene Verifizierungen") { ForEach(model.data["items"].array, id: \.["player"].string) { row in Button { player = row["player"].string; selectedName = row["name"].string; discord = row["discord"].string; expected = discord } label: { VStack(alignment: .leading) { Text(row["name"].string); Text("Discord: " + row["discord"].string).font(.caption).foregroundStyle(.secondary) } } } }
            Section("Verifizierung bearbeiten / anlegen") {
                Button(selectedName.isEmpty ? "Minecraft-Spieler auswählen" : selectedName) { choosing = true }.disabled(!suite.can("data.players"))
                TextField("Minecraft-UUID", text: $player).textInputAutocapitalization(.never).autocorrectionDisabled().font(.footnote.monospaced())
                TextField("Discord-Benutzer-ID", text: $discord).keyboardType(.numberPad)
                Button("Verifizierung speichern") { prepare("save") }.disabled(UUID(uuidString: player) == nil || discord.isEmpty || model.busy || !suite.can("action.links.update"))
                if !expected.isEmpty { Button("Verifizierung entfernen", role: .destructive) { prepare("delete") }.disabled(model.busy || !suite.can("action.links.update")) }
                Button("Neue Verifizierung") { player = ""; selectedName = ""; discord = ""; expected = "" }
            }
            if !model.notice.isEmpty { Text(model.notice).foregroundStyle(.green) }
        }.panelScreen().navigationTitle("Discord-Verifizierungen").task { await model.load("links") }.refreshable { await model.load("links") }.modifier(UpgradeFeedback(model: model, route: "links"))
            .sheet(isPresented: $choosing) { NavigationStack { SuiteLinkPlayerPicker(suite: suite) { row in player = row["id"].string; selectedName = row["name"].string; if let link = model.data["items"].array.first(where: { $0["player"].string == player }) { discord = link["discord"].string; expected = discord } else { discord = ""; expected = "" }; choosing = false } } }
    }
    func prepare(_ op: String) { Task { await model.prepare("links.update", ["player": .string(player), "discord": .string(discord), "expected": .string(expected), "operation": .string(op)]) } }
}
private struct SuiteLinkPlayerPicker: View {
    let suite: SuiteAPI
    let picked: (JSON) -> Void
    @State private var query = ""
    @State private var rows: [JSON] = []
    @State private var error: String?
    var body: some View { List(rows, id: \.["id"].string) { row in Button(row["name"].string) { picked(row) } }.navigationTitle("Minecraft-Spieler").searchable(text: $query).task(id: query) { do { try await Task.sleep(for: .milliseconds(250)); rows = try await suite.data("players", query: ["q": query, "limit": "100", "filter": "all"])["items"].array } catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } } }.errorAlert($error) }
}

struct SuiteMemoryGuardView: View {
    let suite: SuiteAPI
    @StateObject private var model: SuiteUpgradeModel
    @State private var enabled = false
    @State private var threshold = "18.1"
    @State private var hold = "300"
    @State private var countdown = "60"
    init(suite: SuiteAPI) { self.suite = suite; _model = StateObject(wrappedValue: SuiteUpgradeModel(suite)) }
    var thresholdBytes: Int64? { SuiteMemoryThreshold.bytes(threshold) }
    var body: some View {
        Form {
            Section("Server-Container") {
                LabeledContent("Quelle", value: model.data["source"].string)
                if model.data["available"].bool { LabeledContent("Genutzt", value: bytes(model.data["used"].double)); LabeledContent("Limit", value: bytes(model.data["limit"].double)) } else { Label("Kein verlässlicher begrenzter Container-RAM verfügbar. Automatischer Neustart bleibt gesperrt.", systemImage: "info.circle").foregroundStyle(.orange) }
                LabeledContent("Java-Heap (separat)", value: bytes(model.data["heapUsed"].double))
                if !model.data["status"].string.isEmpty { Text(model.data["status"].string).font(.caption) }
            }
            Section("Notfallneustart") {
                Toggle("Automatisch neu starten", isOn: $enabled).disabled(!model.data["available"].bool)
                TextField("Grenzwert in GB (dezimal)", text: $threshold).keyboardType(.decimalPad)
                Text("\(threshold) GB · 1 GB = 1.000.000.000 Bytes").font(.caption).foregroundStyle(.secondary)
                TextField("Durchgehend darüber (Sekunden)", text: $hold).keyboardType(.numberPad)
                TextField("Countdown (5–60 Sekunden)", text: $countdown).keyboardType(.numberPad)
                Text("Nach dem Countdown startet die vorhandene Neustartverwaltung. Der Hoster muss den Serverprozess wieder starten. Bei Messausfall beginnt die Haltedauer neu.").font(.footnote).foregroundStyle(.secondary)
                Button("RAM-Schutz speichern") { Task { await model.prepare("memory.update", ["enabled": .string(String(enabled)), "thresholdBytes": .string(String(thresholdBytes ?? 0)), "holdSeconds": .string(hold), "countdownSeconds": .string(countdown)]) } }.disabled(model.busy || thresholdBytes == nil || !suite.can("action.memory.update"))
            }
            if !model.notice.isEmpty { Text(model.notice).foregroundStyle(.green) }
        }.panelScreen().navigationTitle("RAM-Notfallneustart").task { await model.load("memory"); enabled = model.data["enabled"].bool; threshold = String(model.data["thresholdBytes"].double / 1_000_000_000); hold = model.data["holdSeconds"].string; countdown = model.data["countdownSeconds"].string }.modifier(UpgradeFeedback(model: model, route: "memory"))
    }
}
struct SuiteOnlineSettingsView: View {
    let suite: SuiteAPI
    @StateObject private var model: SuiteUpgradeModel
    @State private var channel = ""
    @State private var channels: [JSON] = []
    init(suite: SuiteAPI) { self.suite = suite; _model = StateObject(wrappedValue: SuiteUpgradeModel(suite)) }
    var body: some View {
        Form {
            Section("Discord-Kanal") {
                Picker("Benachrichtigungskanal", selection: $channel) { Text("Kanal auswählen").tag(""); ForEach(channels, id: \.["id"].string) { row in Text("#" + row["name"].string).tag(row["id"].string) } }
                TextField("Kanal-ID", text: $channel).keyboardType(.numberPad)
                Button("Kanal speichern") { Task { await model.prepare("online.update", ["channel": .string(channel)]) } }.disabled(channel.isEmpty || model.busy || !suite.can("action.online.update"))
            }
            Section("So funktioniert es") { Text("/notify add player:<Name> mode:once oder permanent\n/notify list · /notify remove id:<ID>\n\nNur verifizierte Discord-Konten. Maximal 10 aktive Hinweise. Dauerhafte Anfragen starten beim ersten zugestellten Beitritt; ohne Antwort werden sie nach 3 Tagen automatisch angenommen. Spieler verwalten und sperren Hinweise mit /joinnotify. Antworten auf Discord folgen der Discord-Sprache; im Spiel gilt die Spielersprache.").font(.footnote) }
            if !model.notice.isEmpty { Text(model.notice).foregroundStyle(.green) }
        }.panelScreen().navigationTitle("Beitrittshinweise").task { await model.load("online-settings"); channel = model.data["channel"].string; if suite.can("data.discord-channels") { do { channels = try await suite.data("discord-channels")["items"].array } catch { model.error = error.localizedDescription } } }.modifier(UpgradeFeedback(model: model, route: "online-settings"))
    }
}

enum SuiteMemoryThreshold {
    static func bytes(_ text: String) -> Int64? {
        guard let gb = Double(text.replacingOccurrences(of: ",", with: ".")), gb.isFinite, gb >= 0.1, gb <= 1_000_000 else { return nil }
        return Int64((gb * 1_000_000_000).rounded())
    }
}
