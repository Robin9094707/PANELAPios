import SwiftUI
import UIKit

// MARK: - Quiet hours (Admin Suite 9.7.0)

/// Sleep time, working hours and the emergency-restart notice in one place. These replace the old single
/// quiet window in the Bark settings, so nothing exists twice. Normal notices are never blocked by working hours.
struct SuiteQuietHoursView: View {
    let suite: SuiteAPI
    @StateObject private var runner: SuiteActionRunner
    @State private var loaded = false
    @State private var sleepMode = "off"
    @State private var sleepStart = "22:00"
    @State private var sleepEnd = "07:00"
    @State private var timezone = TimeZone.current.identifier
    @State private var quietKinds: Set<String> = []
    @State private var workEnabled = false
    @State private var workStart = "08:00"
    @State private var workEnd = "17:00"
    @State private var workDays: Set<Int> = [1, 2, 3, 4, 5]
    @State private var emergencyEnabled = true
    @State private var emergencyOverride = "both"
    @State private var sleepActive = false
    @State private var workActive = false
    init(suite: SuiteAPI) { self.suite = suite; _runner = StateObject(wrappedValue: SuiteActionRunner(suite)) }
    static let weekdays = [(1, "Mo"), (2, "Di"), (3, "Mi"), (4, "Do"), (5, "Fr"), (6, "Sa"), (7, "So")]
    static let kinds = [("ban", "Sperren"), ("ban-evasion", "Bannumgehung"), ("overload", "Überlastung"), ("ticket-urgent", "Notfall-Tickets"), ("vpn-kick", "VPN-Kicks"), ("report", "Reports")]
    var body: some View {
        Form {
            Section {
                HStack(spacing: 12) {
                    status("Schlafenszeit", active: sleepActive && sleepMode != "off", symbol: "moon.zzz.fill", tint: .indigo)
                    status("Arbeitszeit", active: workActive && workEnabled, symbol: "briefcase.fill", tint: .orange)
                }
            } footer: { Text("Gilt für deine Bark-Mitteilungen dieses Servers. Andere Admins stellen ihre Zeiten selbst ein.") }
            Section {
                Picker("Während der Schlafenszeit", selection: $sleepMode) { Text("Aus").tag("off"); Text("Alles stumm").tag("all"); Text("Nur kritische Hinweise").tag("critical"); Text("Nur ausgewählte kritische").tag("selected") }
                if sleepMode != "off" {
                    DatePicker("Von", selection: time($sleepStart), displayedComponents: .hourAndMinute)
                    DatePicker("Bis", selection: time($sleepEnd), displayedComponents: .hourAndMinute)
                }
                if sleepMode == "selected" { ForEach(Self.kinds, id: \.0) { kind in Toggle(kind.1, isOn: Binding(get: { quietKinds.contains(kind.0) }, set: { if $0 { quietKinds.insert(kind.0) } else { quietKinds.remove(kind.0) } })) } }
            } header: { Label("Schlafenszeit", systemImage: "moon.zzz.fill") }
            Section {
                Toggle("Arbeitszeit aktiv", isOn: $workEnabled)
                if workEnabled {
                    DatePicker("Von", selection: time($workStart), displayedComponents: .hourAndMinute)
                    DatePicker("Bis", selection: time($workEnd), displayedComponents: .hourAndMinute)
                    HStack(spacing: 6) {
                        ForEach(Self.weekdays, id: \.0) { day in
                            Button(day.1) { if workDays.contains(day.0) { workDays.remove(day.0) } else { workDays.insert(day.0) } }
                                .font(.caption.weight(.semibold)).frame(maxWidth: .infinity, minHeight: 34)
                                .background(workDays.contains(day.0) ? Color.panelAccent.opacity(0.85) : Color.secondary.opacity(0.12), in: RoundedRectangle(cornerRadius: 9))
                                .foregroundStyle(workDays.contains(day.0) ? .white : .primary).buttonStyle(.plain)
                        }
                    }
                }
            } header: { Label("Arbeitszeit", systemImage: "briefcase.fill") } footer: { Text("In der Arbeitszeit kommen keine kritischen Alarme – sie erscheinen als zeitkritische Mitteilung. Normale Hinweise kommen immer.") }
            Section {
                Toggle("Kritischer Hinweis nach Notfallneustart", isOn: $emergencyEnabled)
                if emergencyEnabled {
                    Picker("Darf durchdringen", selection: $emergencyOverride) { Text("Schlafens- & Arbeitszeit").tag("both"); Text("Nur Schlafenszeit").tag("sleep"); Text("Nur Arbeitszeit").tag("work"); Text("Keine – Ruhezeiten gelten").tag("none") }
                }
            } header: { Label("Notfallneustart", systemImage: "bolt.heart.fill") } footer: { Text("Nach einem RAM-Notfallneustart oder einem unerwarteten Stopp meldet die Admin Suite, dass der Server wieder online ist – als kritischer Bark-Alarm.") }
            Section("Zeitzone") { TextField("IANA-Zeitzone", text: $timezone).textInputAutocapitalization(.never).autocorrectionDisabled(); Button("Gerätezeitzone verwenden") { timezone = TimeZone.current.identifier }.font(.subheadline) }
            Section {
                Button { Task { await save() } } label: { HStack { Spacer(); if runner.busy { ProgressView() } else { Label("Ruhezeiten speichern", systemImage: "checkmark.circle.fill").bold() }; Spacer() } }.disabled(runner.busy || !loaded || (workEnabled && workDays.isEmpty))
                if !runner.notice.isEmpty { Label(runner.notice, systemImage: "checkmark.seal.fill").foregroundStyle(.green) }
            }
        }.panelScreen().navigationTitle("Ruhezeiten").navigationBarTitleDisplayMode(.inline).errorAlert($runner.error)
            .task { await load() }
    }
    func status(_ title: String, active: Bool, symbol: String, tint: Color) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Image(systemName: symbol).font(.title3).foregroundStyle(active ? tint : .secondary)
            Text(title).font(.subheadline.weight(.semibold))
            Text(active ? "Gerade aktiv" : "Nicht aktiv").font(.caption).foregroundStyle(active ? tint : .secondary)
        }.frame(maxWidth: .infinity, alignment: .leading).padding(12).background((active ? tint : Color.secondary).opacity(0.10), in: RoundedRectangle(cornerRadius: 14))
    }
    func time(_ value: Binding<String>) -> Binding<Date> {
        Binding(get: {
            let parts = value.wrappedValue.split(separator: ":").compactMap { Int($0) }
            return Calendar.current.date(bySettingHour: parts.first ?? 0, minute: parts.count > 1 ? parts[1] : 0, second: 0, of: Date()) ?? Date()
        }, set: { date in
            let c = Calendar.current.dateComponents([.hour, .minute], from: date)
            value.wrappedValue = String(format: "%02d:%02d", c.hour ?? 0, c.minute ?? 0)
        })
    }
    func load() async {
        do {
            let v = try await suite.request("notifications")
            sleepMode = v["quietMode"].string.isEmpty ? "off" : v["quietMode"].string
            if !v["quietStart"].string.isEmpty { sleepStart = v["quietStart"].string }
            if !v["quietEnd"].string.isEmpty { sleepEnd = v["quietEnd"].string }
            if !v["timezone"].string.isEmpty { timezone = v["timezone"].string }
            quietKinds = Set(v["quietKinds"].string.split(separator: ",").map(String.init))
            workEnabled = v["workEnabled"].bool
            if !v["workStart"].string.isEmpty { workStart = v["workStart"].string }
            if !v["workEnd"].string.isEmpty { workEnd = v["workEnd"].string }
            if !v["workDays"].string.isEmpty { workDays = Set(v["workDays"].string.split(separator: ",").compactMap { Int($0) }) }
            emergencyEnabled = v["emergencyEnabled"] == .null ? true : v["emergencyEnabled"].bool
            if !v["emergencyOverride"].string.isEmpty { emergencyOverride = v["emergencyOverride"].string }
            sleepActive = v["sleepActive"].bool; workActive = v["workActive"].bool; loaded = true
        } catch { runner.error = error.localizedDescription }
    }
    func save() async {
        let values: [String: JSON] = ["sleepMode": .string(sleepMode), "sleepStart": .string(sleepStart), "sleepEnd": .string(sleepEnd), "timezone": .string(timezone), "quietKinds": .string(quietKinds.sorted().joined(separator: ",")), "workEnabled": .string(String(workEnabled)), "workStart": .string(workStart), "workEnd": .string(workEnd), "workDays": .string(workDays.sorted().map(String.init).joined(separator: ",")), "emergencyEnabled": .string(String(emergencyEnabled)), "emergencyOverride": .string(emergencyOverride)]
        if await runner.run("quiet.update", values) != nil { await load() }
    }
}

// MARK: - Setup links

/// panel://setup?e=<https endpoint>&f=<sha256>&c=<one-time code>&u=<user>&n=<server name>
struct SuiteSetupLink: Identifiable, Equatable {
    let endpoint: String
    let fingerprint: String
    let code: String
    let username: String
    let name: String
    var id: String { code }
    static func parse(_ raw: String) -> SuiteSetupLink? {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let components = URLComponents(string: text), components.scheme == "panel", components.host == "setup" else { return nil }
        func value(_ key: String) -> String { components.queryItems?.first { $0.name == key }?.value ?? "" }
        let code = value("c"), endpoint = value("e")
        guard code.hasPrefix("sass_"), code.count <= 160, endpoint.hasPrefix("https://") else { return nil }
        return SuiteSetupLink(endpoint: endpoint, fingerprint: value("f"), code: code, username: value("u").lowercased(), name: value("n"))
    }
}

/// Holds a setup link opened from outside the app until the home screen can present it (also while locked).
@MainActor final class SuiteSetupInbox: ObservableObject {
    static let shared = SuiteSetupInbox()
    @Published var link: SuiteSetupLink?
    func open(_ url: URL) -> Bool { guard let parsed = SuiteSetupLink.parse(url.absoluteString) else { return false }; link = parsed; return true }
}

/// Redeems a link: no address, fingerprint, username or password to type. Optionally attaches to a panel server.
struct SuiteSetupRedeemView: View {
    var initial: SuiteSetupLink? = nil
    var panel: PanelAPI? = nil
    var server: Server? = nil
    var done: () async -> Void = {}
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss
    @State private var text = ""
    @State private var busy = false
    @State private var error: String?
    @State private var protect = true
    @State private var finished = false
    var link: SuiteSetupLink? { SuiteSetupLink.parse(text) }
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    VStack(alignment: .leading, spacing: 10) {
                        Image(systemName: finished ? "checkmark.seal.fill" : "link.badge.plus").font(.system(size: 42)).foregroundStyle(finished ? .green : Color.panelAccent).symbolEffect(.bounce, value: finished)
                        Text(finished ? "Fertig eingerichtet" : "Admin-Zugang per Setup-Link").font(.title2.bold())
                        Text(finished ? "Der Zugang ist sicher im Schlüsselbund gespeichert." : "Den einmaligen Link bekommst du vom Hauptadmin. Er gilt standardmäßig eine Stunde und nur ein einziges Mal.").foregroundStyle(.secondary)
                    }.padding(.vertical, 6)
                }
                if !finished {
                    Section {
                        TextField("panel://setup?…", text: $text, axis: .vertical).font(.caption.monospaced()).textInputAutocapitalization(.never).autocorrectionDisabled().lineLimit(2...5).accessibilityIdentifier("setupLinkField")
                        Button("Aus Zwischenablage einfügen", systemImage: "doc.on.clipboard") { text = UIPasteboard.general.string ?? "" }
                    } header: { Text("Setup-Link") }
                    if let link {
                        Section("Wird eingerichtet") {
                            LabeledContent("Server", value: link.name.isEmpty ? "Admin Suite" : link.name)
                            LabeledContent("Benutzer", value: link.username)
                            LabeledContent("Adresse") { Text(link.endpoint).font(.caption.monospaced()) }
                            if let server { LabeledContent("Koppeln mit", value: server.name) }
                            Toggle("App mit Face ID / Gerätecode schützen", isOn: $protect)
                        }
                    } else if !text.isEmpty { Section { Label("Das ist kein gültiger Setup-Link.", systemImage: "exclamationmark.triangle").foregroundStyle(.orange) } }
                    Section { Button { Task { await redeem() } } label: { HStack { Spacer(); if busy { ProgressView() } else { Label("Einrichten", systemImage: "checkmark.shield.fill").bold() }; Spacer() } }.disabled(busy || link == nil) }
                } else {
                    Section { Button("Schließen") { dismiss() }.frame(maxWidth: .infinity) }
                }
            }.panelScreen().navigationTitle("Setup-Link").navigationBarTitleDisplayMode(.inline).errorAlert($error)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button(finished ? "Fertig" : "Abbrechen") { dismiss() }.disabled(busy) } }
                .task { if let initial, text.isEmpty { text = "panel://setup?e=" + (initial.endpoint.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? "") + "&f=" + initial.fingerprint + "&c=" + initial.code + "&u=" + initial.username + "&n=" + (initial.name.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? "") } }
        }.interactiveDismissDisabled(busy)
    }
    func redeem() async {
        guard let link else { return }
        busy = true; defer { busy = false }
        do {
            let url = try SuiteAPI.endpoint(link.endpoint), pin = try SuiteAPI.pin(link.fingerprint)
            var record: SuiteConnection
            if let panel, let server {
                record = SuiteConnection(panel: panel.base.absoluteString, server: server.id, endpoint: url.absoluteString, fingerprint: pin, serverIdentity: "")
                if let existing = SuiteConnections.shared.get(panel, server) { record.id = existing.id; record.useForModeration = existing.useForModeration }
            } else {
                record = SuiteConnections.shared.values.first { $0.panel == "suite-direct:" + link.username && $0.endpoint == url.absoluteString } ?? SuiteConnection(panel: "suite-direct:" + link.username, server: UUID().uuidString, endpoint: url.absoluteString, fingerprint: pin, serverIdentity: "")
                record.endpoint = url.absoluteString; record.fingerprint = pin
            }
            let api = try SuiteAPI(record, token: "")
            let reply = try await api.redeem(code: link.code)
            let health = try await api.health()
            record.serverIdentity = health["serverId"].string.isEmpty ? reply["serverId"].string : health["serverId"].string
            record.displayName = server?.name ?? (link.name.isEmpty ? record.displayName : link.name)
            try SuiteConnections.shared.save(record, token: api.sessionToken)
            if protect { store.biometricLock = true }
            Feedback.success(); finished = true; text = ""
            await done()
        } catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }
}

/// Creates a one-time setup link for an existing app user (default 60 minutes). Shown only to the creator.
struct SuiteSetupLinkCreator: View {
    let suite: SuiteAPI
    let username: String
    @StateObject private var runner: SuiteActionRunner
    @State private var minutes = 60
    @State private var link = ""
    @State private var expires: Date?
    init(suite: SuiteAPI, username: String) { self.suite = suite; self.username = username; _runner = StateObject(wrappedValue: SuiteActionRunner(suite)) }
    var body: some View {
        Form {
            Section {
                Picker("Gültigkeit", selection: $minutes) { Text("15 Minuten").tag(15); Text("1 Stunde").tag(60); Text("3 Stunden").tag(180); Text("1 Tag").tag(1440); Text("7 Tage").tag(10080) }
                Button { Task { await create() } } label: { Label(link.isEmpty ? "Setup-Link erstellen" : "Neuen Link erstellen", systemImage: "link.badge.plus") }.disabled(runner.busy)
            } footer: { Text("Der Link ist nur einmal gültig und ersetzt einen älteren, noch nicht genutzten Link von \(username). Nach dem Einlösen ist \(username) sofort mit den bestehenden Rechten angemeldet.") }
            if !link.isEmpty {
                Section("Link für \(username)") {
                    Text(link).font(.caption.monospaced()).textSelection(.enabled).lineLimit(4)
                    Button("Link kopieren", systemImage: "doc.on.doc") { Clipboard.copy(link) }.accessibilityIdentifier("copySetupLink")
                    ShareLink(item: link, subject: Text("Simplex Panel einrichten"), message: Text("Öffne den Link auf deinem iPhone mit Simplex Panel. Er gilt einmalig.")) { Label("Teilen", systemImage: "square.and.arrow.up") }
                    if let expires { LabeledContent("Gültig bis") { Text(expires, format: .dateTime.day().month().hour().minute()) } }
                    Button("Link widerrufen", systemImage: "xmark.circle", role: .destructive) { Task { if await runner.run("setup.create", ["username": .string(username), "operation": .string("cancel")]) != nil { link = ""; expires = nil } } }
                }
            }
            Section { Text("Tipp: Auf dem iPhone öffnet der Link direkt Simplex Panel. Alternativ kann der Link in der App unter Panels → „Setup-Link einlösen“ eingefügt werden. Mit /sas app setup \(username) geht es auch über die Serverkonsole.").font(.footnote).foregroundStyle(.secondary) }
        }.panelScreen().navigationTitle("Setup-Link").navigationBarTitleDisplayMode(.inline).errorAlert($runner.error)
    }
    func create() async {
        guard let reply = await runner.run("setup.create", ["username": .string(username), "minutes": .string(String(minutes)), "endpoint": .string(suite.connection.endpoint), "operation": .string("create")]) else { return }
        link = reply["link"].string
        if let ms = Double(reply["expiresAt"].string) { expires = Date(timeIntervalSince1970: ms / 1000) }
        if link.isEmpty { runner.error = "Die Suite hat keinen Link geliefert. Prüfe die API-Adresse dieser Verbindung." }
    }
}

// MARK: - App settings & ranks

struct SuiteAppSettingsView: View {
    let suite: SuiteAPI
    @StateObject private var runner: SuiteActionRunner
    @State private var rows: [JSON] = []
    @State private var texts: [String: String] = [:]
    @State private var search = ""
    init(suite: SuiteAPI) { self.suite = suite; _runner = StateObject(wrappedValue: SuiteActionRunner(suite)) }
    var groups: [String] { var seen: [String] = []; for row in filtered where !seen.contains(row["group"].string) { seen.append(row["group"].string) }; return seen }
    var filtered: [JSON] { rows.filter { search.isEmpty || ($0["title"].string + $0["key"].string + $0["group"].string).localizedCaseInsensitiveContains(search) } }
    var body: some View {
        List {
            if suite.features.contains("nameplates-v1") { Section { NavigationLink { SuiteNameplatesView(suite: suite) } label: { Label("Ränge & Namen live prüfen", systemImage: "crown.fill") } } }
            ForEach(groups, id: \.self) { group in
                Section(group) { ForEach(filtered.filter { $0["group"].string == group }, id: \.["key"].string) { row in control(row) } }
            }
            if !runner.notice.isEmpty { Section { Label(runner.notice, systemImage: "checkmark.circle.fill").foregroundStyle(.green) } }
        }.panelScreen().navigationTitle("App-Einstellungen").searchable(text: $search, prompt: "Einstellung suchen").errorAlert($runner.error)
            .task { await load() }.refreshable { await load() }
    }
    @ViewBuilder func control(_ row: JSON) -> some View {
        let key = row["key"].string
        VStack(alignment: .leading, spacing: 4) {
            switch row["type"].string {
            case "bool":
                Toggle(row["title"].string, isOn: Binding(get: { row["value"].bool }, set: { value in Task { await save(key, String(value)) } })).disabled(runner.busy)
            case "int":
                Stepper(value: Binding(get: { row["value"].int }, set: { value in Task { await save(key, String(value)) } }), in: row["min"].int...max(row["min"].int, row["max"].int)) { LabeledContent(row["title"].string, value: row["value"].string) }.disabled(runner.busy)
            default:
                Text(row["title"].string).font(.subheadline)
                HStack {
                    TextField(row["title"].string, text: Binding(get: { texts[key] ?? row["value"].string }, set: { texts[key] = $0 })).textInputAutocapitalization(.never).autocorrectionDisabled()
                    Button("Speichern") { Task { await save(key, texts[key] ?? row["value"].string) } }.disabled(runner.busy || texts[key] == nil)
                }
            }
            if !row["help"].string.isEmpty { Text(row["help"].string).font(.caption).foregroundStyle(.secondary) }
        }
    }
    func load() async { do { rows = try await suite.data("app-settings")["items"].array; texts = [:] } catch { runner.error = error.localizedDescription } }
    func save(_ key: String, _ value: String) async { if await runner.run("app.settings", ["key": .string(key), "value": .string(value)]) != nil { await load() } }
}

struct SuiteNameplatesView: View {
    let suite: SuiteAPI
    @State private var data: JSON = .null
    @State private var error: String?
    var body: some View {
        List {
            Section {
                Label(data["luckperms"].bool ? "LuckPerms erkannt" : "LuckPerms nicht installiert", systemImage: data["luckperms"].bool ? "checkmark.seal.fill" : "xmark.seal").foregroundStyle(data["luckperms"].bool ? .green : .orange)
                if data["geyser"].bool { Label("Geyser erkannt – Bedrock zeigt dieselben Ränge", systemImage: "gamecontroller.fill") }
                if !data["tabPlugin"].string.isEmpty { Label("Tablisten-Plugin erkannt: " + data["tabPlugin"].string, systemImage: "list.bullet.rectangle") }
                if data["aboveHead"] != .null {
                    LabeledContent("Rang über dem Kopf", value: data["aboveHead"].bool ? "An" : "Aus")
                    LabeledContent("Rang in der Tabliste", value: !data["playerListManaged"].bool ? "Anderes Plugin" : data["inPlayerList"].bool ? "An" : "Aus")
                }
            } footer: { Text("Über dem Kopf, in der Tabliste und „Tabliste einem TAB-Plugin überlassen“ stellst du einzeln in den App-Einstellungen unter „Ränge & Namen“ ein. Unterstützt &-Codes, &#RRGGBB und einfache MiniMessage-Farben.") }
            Section("Online") {
                if data["items"].array.isEmpty { Text(data == .null ? "Wird geladen …" : "Niemand online").foregroundStyle(.secondary) }
                ForEach(data["items"].array, id: \.["id"].string) { row in
                    VStack(alignment: .leading, spacing: 4) {
                        HStack(spacing: 6) {
                            if !row["prefix"].string.isEmpty { Text(row["prefix"].string).font(.subheadline.weight(.bold)).foregroundStyle(color(row["color"].string)) }
                            Text(row["name"].string).font(.subheadline.weight(.semibold)).foregroundStyle(color(row["color"].string))
                            if !row["suffix"].string.isEmpty { Text(row["suffix"].string).font(.subheadline) }
                            if row["bedrock"].bool { Image(systemName: "gamecontroller").font(.caption).foregroundStyle(.secondary) }
                        }
                        Text(row["managed"].bool ? "Von der Admin Suite angezeigt" : row["team"].string.isEmpty ? "Kein Rang-Team" : "Team eines anderen Plugins: " + row["team"].string).font(.caption).foregroundStyle(.secondary)
                        if row["status"].string != "OK" { Text(row["status"].string).font(.caption2).foregroundStyle(.orange) }
                    }
                }
            }
        }.panelScreen().navigationTitle("Ränge & Namen").errorAlert($error).task { await load() }.refreshable { await load() }
    }
    func color(_ name: String) -> Color {
        switch name { case "red", "dark_red": return .red; case "gold", "yellow": return .orange; case "green", "dark_green": return .green; case "aqua", "dark_aqua": return .teal; case "blue", "dark_blue": return .blue; case "light_purple", "dark_purple": return .purple; case "gray", "dark_gray": return .secondary; default: return .primary }
    }
    func load() async { do { data = try await suite.data("nameplates") } catch { self.error = error.localizedDescription } }
}

// MARK: - New app menu (4.0): one hub for every connected Admin Suite

/// Loads a suite connection on demand and hands the verified API to its content.
struct SuiteConnectionLoader<Content: View>: View {
    let connection: SuiteConnection
    @ViewBuilder let content: (SuiteAPI) -> Content
    @StateObject private var loader = SuiteWorkspaceLoadModel()
    var body: some View {
        ZStack {
            if let api = loader.api { content(api) }
            else { List {
                if loader.loading { ProgressView("Verbinden …"); Button("Abbrechen", role: .cancel) { loader.cancel() } }
                else { Text(loader.error ?? "Verbindung noch nicht hergestellt."); Button("Erneut versuchen") { Task { await loader.load(connection, tool: .advertising) } } }
            }.panelScreen() }
        }.task(id: connection.id) { await loader.load(connection, tool: .advertising) }.onDisappear { loader.cancel() }
    }
}

struct SuiteHubEntry: Identifiable {
    let title: String
    let connection: SuiteConnection
    var id: UUID { connection.id }
    /// Direct Suite accounts first, then suites attached to servers of the selected panel.
    @MainActor static func all(store: AppStore, connections: SuiteConnections) -> [SuiteHubEntry] {
        var out = connections.values.filter { $0.panel.hasPrefix("suite-direct:") }.map { SuiteHubEntry(title: $0.panel.replacingOccurrences(of: "suite-direct:", with: "") + " · Direktzugang", connection: $0) }
        if let panel = store.api { for server in store.visibleServers { if let record = connections.get(panel, server) { out.append(SuiteHubEntry(title: server.name, connection: record)) } } }
        return out
    }
}

struct SuiteQuickHubView: View {
    @EnvironmentObject private var store: AppStore
    @ObservedObject private var connections = SuiteConnections.shared
    @Environment(\.dismiss) private var dismiss
    @State private var redeem = false
    var entries: [SuiteHubEntry] { SuiteHubEntry.all(store: store, connections: connections) }
    var body: some View {
        NavigationStack {
            List {
                if entries.isEmpty {
                    ContentUnavailableView("Keine Admin Suite verbunden", systemImage: "shield.slash", description: Text("Verbinde einen Server im Admin-Tab oder richte einen Zugang per Setup-Link ein."))
                }
                ForEach(entries) { entry in
                    Section(entry.title) {
                        NavigationLink { SuiteConnectionLoader(connection: entry.connection) { SuiteAdminLogView(suite: $0) } } label: { Label("Admin-Log", systemImage: "list.bullet.clipboard.fill") }
                        NavigationLink { SuiteConnectionLoader(connection: entry.connection) { SuiteStaffChatView(suite: $0) } } label: { Label("Staff-Chat", systemImage: "bubble.left.and.text.bubble.right.fill") }
                        NavigationLink { SuiteConnectionLoader(connection: entry.connection) { SuiteTicketsView(suite: $0) } } label: { Label("Tickets", systemImage: "ticket.fill") }
                        NavigationLink { SuiteConnectionLoader(connection: entry.connection) { SuiteStaffTasksView(suite: $0) } } label: { Label("Staff-Aufgaben", systemImage: "checklist") }
                        NavigationLink { SuiteConnectionLoader(connection: entry.connection) { SuiteQuietHoursView(suite: $0) } } label: { Label("Ruhezeiten", systemImage: "moon.zzz.fill") }
                        NavigationLink { SuiteConnectionLoader(connection: entry.connection) { SuiteAppSettingsView(suite: $0) } } label: { Label("App-Einstellungen", systemImage: "slider.horizontal.3") }
                    }
                }
                Section { Button("Setup-Link einlösen", systemImage: "link.badge.plus") { redeem = true } }
            }.panelScreen().navigationTitle("Admin-Zentrale").toolbar { ToolbarItem(placement: .confirmationAction) { Button("Fertig") { dismiss() } } }
                .sheet(isPresented: $redeem) { SuiteSetupRedeemView() }
        }
    }
}

/// Home widget (4.0): live players, TPS, unread admin actions and open tickets of every connected suite.
struct SuitePulseCard: View {
    let open: () -> Void
    @EnvironmentObject private var store: AppStore
    @ObservedObject private var connections = SuiteConnections.shared
    var records: [SuiteHubEntry] { SuiteHubEntry.all(store: store, connections: connections) }
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack { Label("Admin Suite live", systemImage: "waveform.path.ecg.rectangle").font(.headline); Spacer(); Button("Öffnen", systemImage: "chevron.right") { open() }.labelStyle(.iconOnly).font(.caption) }
            if records.isEmpty { Text("Noch keine Admin Suite verbunden.").font(.subheadline).foregroundStyle(.secondary) }
            ForEach(records) { record in SuitePulseRow(title: record.title, connection: record.connection).onTapGesture { open() } }
        }.frame(maxWidth: .infinity, alignment: .leading).card()
    }
}

struct SuitePulseRow: View {
    let title: String
    let connection: SuiteConnection
    @Environment(\.scenePhase) private var scenePhase
    @State private var dashboard: JSON = .null
    @State private var unread = 0
    @State private var history: [Double] = []
    @State private var failed = false
    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                Text(title).font(.subheadline.weight(.semibold)).lineLimit(1)
                HStack(spacing: 8) {
                    Label("\(dashboard["online"].int)/\(dashboard["maxPlayers"].int)", systemImage: "person.2.fill")
                    if dashboard["tps"].double > 0 { Label(String(format: "%.1f", dashboard["tps"].double), systemImage: "speedometer").foregroundStyle(dashboard["tps"].double >= 18 ? .green : dashboard["tps"].double >= 15 ? .orange : .red) }
                    if dashboard["tickets"]["open"].int > 0 { Label("\(dashboard["tickets"]["open"].int)", systemImage: "ticket.fill").foregroundStyle(.mint) }
                }.font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                if failed { Text("Nicht erreichbar").font(.caption2).foregroundStyle(.orange) }
            }
            Spacer(minLength: 6)
            if history.count > 1 {
                PanelSparkline(values: history).frame(width: 72, height: 28)
            }
            SuiteUnreadBadge(count: unread)
        }.padding(10).background(Color.panelAccent.opacity(0.07), in: RoundedRectangle(cornerRadius: 14)).contentShape(Rectangle())
            .task(id: "\(connection.id)-\(scenePhase)") {
                guard scenePhase == .active else { return }
                guard let api = try? SuiteAPI(connection) else { failed = true; return }
                while !Task.isCancelled {
                    do {
                        let health = try await api.health()
                        if api.can("data.admin-dashboard") { dashboard = try await api.data("admin-dashboard") }
                        if health["features"].array.contains(.string("admin-log-v1")) && api.can("data.admin-log") { unread = try await api.data("admin-log")["unread"].int }
                        history = Array((history + [dashboard["online"].double]).suffix(24)); failed = false
                    } catch { if RequestFailure.isCancellation(error) { return }; failed = true }
                    do { try await Task.sleep(for: .seconds(45)) } catch { return }
                }
            }
    }
}

struct PanelSparkline: View {
    let values: [Double]
    var body: some View {
        GeometryReader { geo in
            let top = max(1, values.max() ?? 1)
            Path { path in
                for (index, value) in values.enumerated() {
                    let point = CGPoint(x: geo.size.width * CGFloat(index) / CGFloat(max(1, values.count - 1)), y: geo.size.height * (1 - CGFloat(value / top)))
                    if index == 0 { path.move(to: point) } else { path.addLine(to: point) }
                }
            }.stroke(Color.panelAccent, style: StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))
        }.accessibilityHidden(true)
    }
}

// MARK: - Staff tasks (same store as the Java/Bedrock staff menu)

struct SuiteStaffTasksView: View {
    let suite: SuiteAPI
    @StateObject private var runner: SuiteActionRunner
    @State private var data: JSON = .null
    @State private var showDone = false
    @State private var adding = false
    @State private var title = ""
    @State private var details = ""
    @State private var priority = "NORMAL"
    init(suite: SuiteAPI) { self.suite = suite; _runner = StateObject(wrappedValue: SuiteActionRunner(suite)) }
    var items: [JSON] { data["items"].array.filter { showDone || $0["status"].string != "DONE" } }
    var body: some View {
        List {
            Section {
                HStack(spacing: 18) {
                    VStack(alignment: .leading) { Text(String(data["open"].int)).font(.title.bold()).foregroundStyle(Color.panelAccent); Text("Offen").font(.caption).foregroundStyle(.secondary) }
                    VStack(alignment: .leading) { Text(String(data["overdue"].int)).font(.title.bold()).foregroundStyle(data["overdue"].int > 0 ? .red : .secondary); Text("Überfällig").font(.caption).foregroundStyle(.secondary) }
                    Spacer()
                }
                Toggle("Erledigte zeigen", isOn: $showDone)
            }
            Section("Aufgaben") {
                if items.isEmpty { Text(data == .null ? "Wird geladen …" : "Keine offenen Aufgaben").foregroundStyle(.secondary) }
                ForEach(items, id: \.["id"].string) { row in
                    VStack(alignment: .leading, spacing: 4) {
                        HStack(spacing: 6) {
                            Image(systemName: row["status"].string == "DONE" ? "checkmark.circle.fill" : row["status"].string == "CLAIMED" ? "person.crop.circle.badge.checkmark" : "circle").foregroundStyle(row["status"].string == "DONE" ? .green : Color.panelAccent)
                            Text(row["title"].string).font(.subheadline.weight(.semibold)).strikethrough(row["status"].string == "DONE")
                            Spacer()
                            Text(priorityTitle(row["priority"].string)).font(.caption2.bold()).padding(.horizontal, 6).padding(.vertical, 2).background(priorityColor(row["priority"].string).opacity(0.15), in: Capsule()).foregroundStyle(priorityColor(row["priority"].string))
                        }
                        if !row["details"].string.isEmpty { Text(row["details"].string).font(.caption).foregroundStyle(.secondary) }
                        HStack(spacing: 6) {
                            Text("von " + row["createdBy"].string)
                            if !row["assigned"].string.isEmpty { Text("· übernommen: " + row["assigned"].string) }
                            if row["overdue"].bool { Text("· überfällig").foregroundStyle(.red) }
                        }.font(.caption2).foregroundStyle(.secondary)
                    }
                    .swipeActions(edge: .leading) { Button(row["status"].string == "DONE" ? "Öffnen" : "Erledigt", systemImage: "checkmark") { act("done", row) }.tint(.green) }
                    .swipeActions(edge: .trailing) {
                        Button("Löschen", systemImage: "trash", role: .destructive) { act("delete", row) }
                        Button("Übernehmen", systemImage: "person.fill.checkmark") { act("claim", row) }.tint(Color.panelAccent)
                        Button("Priorität", systemImage: "flag") { act("priority", row) }.tint(.orange)
                    }
                }
            }
        }.panelScreen().navigationTitle("Staff-Aufgaben").errorAlert($runner.error)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Neue Aufgabe", systemImage: "plus") { adding = true }.disabled(!suite.can("action.tasks.update")) } }
            .task { await load() }.refreshable { await load() }
            .sheet(isPresented: $adding) {
                NavigationStack {
                    Form {
                        TextField("Titel", text: $title)
                        TextField("Details", text: $details, axis: .vertical).lineLimit(2...5)
                        Picker("Priorität", selection: $priority) { ForEach(["LOW", "NORMAL", "HIGH", "URGENT"], id: \.self) { Text(priorityTitle($0)).tag($0) } }
                    }.navigationTitle("Neue Aufgabe").navigationBarTitleDisplayMode(.inline)
                        .toolbar {
                            ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { adding = false } }
                            ToolbarItem(placement: .confirmationAction) { Button("Erstellen") { Task { if await runner.run("tasks.update", ["operation": .string("create"), "title": .string(title), "details": .string(details), "priority": .string(priority)]) != nil { title = ""; details = ""; adding = false; await load() } } }.disabled(title.trimmingCharacters(in: .whitespaces).isEmpty || runner.busy) }
                        }
                }.presentationDetents([.medium])
            }
    }
    func act(_ operation: String, _ row: JSON) { Task { if await runner.run("tasks.update", ["operation": .string(operation), "id": .string(row["id"].string), "priority": .string(row["priority"].string)]) != nil { await load() } } }
    func priorityTitle(_ value: String) -> String { switch value { case "LOW": return "Niedrig"; case "HIGH": return "Hoch"; case "URGENT": return "Dringend"; default: return "Normal" } }
    func priorityColor(_ value: String) -> Color { switch value { case "LOW": return .secondary; case "HIGH": return .orange; case "URGENT": return .red; default: return Color.panelAccent } }
    func load() async { do { data = try await suite.data("staff-tasks") } catch { runner.error = error.localizedDescription } }
}
