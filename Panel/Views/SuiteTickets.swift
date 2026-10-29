import SwiftUI

/// Labels shared by the ticket list, detail and settings. Values match Admin Suite 9.4.0.
enum SuiteTicketText {
    static let categories = ["general", "technical", "report", "discord", "other"]
    static let priorities = ["low", "normal", "high", "urgent"]
    static func category(_ id: String) -> String { switch id { case "technical": return "Technischer Support"; case "report": return "Spieler melden"; case "discord": return "Discord"; case "other": return "Sonstiges"; default: return "Allgemeiner Support" } }
    static func categorySymbol(_ id: String) -> String { switch id { case "technical": return "wrench.and.screwdriver.fill"; case "report": return "exclamationmark.bubble.fill"; case "discord": return "bubble.left.and.bubble.right.fill"; case "other": return "tray.fill"; default: return "questionmark.bubble.fill" } }
    static func priority(_ id: String) -> String { switch id { case "low": return "Niedrig"; case "high": return "Hoch"; case "urgent": return "Notfall"; default: return "Normal" } }
    static func priorityColor(_ id: String) -> Color { switch id { case "low": return .green; case "high": return .orange; case "urgent": return .red; default: return .yellow } }
    static func status(_ id: String) -> String { switch id { case "answered": return "Beantwortet"; case "closed": return "Geschlossen"; default: return "Offen" } }
    static func statusSymbol(_ id: String) -> String { switch id { case "answered": return "arrowshape.turn.up.left.fill"; case "closed": return "lock.fill"; default: return "envelope.badge.fill" } }
    static func source(_ id: String) -> String { switch id { case "discord": return "Discord"; case "app": return "App"; default: return "Minecraft" } }
}

enum SuiteTicketFilter: String, CaseIterable, Identifiable {
    case active, open, answered, urgent, closed, all
    var id: String { rawValue }
    var title: String { switch self { case .active: return "Aktiv"; case .open: return "Offen"; case .answered: return "Beantwortet"; case .urgent: return "Notfall"; case .closed: return "Geschlossen"; case .all: return "Alle" } }
}

@MainActor final class SuiteTicketsModel: ObservableObject {
    @Published var rows: [JSON] = []
    @Published var counts: JSON = .null
    @Published var busy = false
    @Published var error: String?
    @Published var next = -1
    @Published var enabled = true
    let suite: SuiteAPI
    init(_ suite: SuiteAPI) { self.suite = suite }
    func load(_ filter: SuiteTicketFilter, query: String, more: Bool = false) async {
        busy = true; defer { busy = false }
        do {
            let data = try await suite.data("tickets", query: ["status": filter.rawValue, "q": query, "offset": String(more ? max(0, next) : 0)])
            try Task.checkCancellation()
            let items = data["items"].array
            if more { let known = Set(rows.map { $0["id"].string }); rows += items.filter { !known.contains($0["id"].string) } } else { rows = items }
            counts = data["counts"]; next = data["next"].int; enabled = data["enabled"] == .null || data["enabled"].bool; error = nil
        } catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }
}

struct SuiteTicketsView: View {
    let suite: SuiteAPI
    var panel: PanelAPI? = nil
    var server: Server? = nil
    @StateObject private var model: SuiteTicketsModel
    @Environment(\.scenePhase) private var scenePhase
    @State private var filter = SuiteTicketFilter.active
    @State private var query = ""
    init(suite: SuiteAPI, panel: PanelAPI? = nil, server: Server? = nil) { self.suite = suite; self.panel = panel; self.server = server; _model = StateObject(wrappedValue: SuiteTicketsModel(suite)) }
    var body: some View {
        List {
            Section {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 120), spacing: 10)], spacing: 10) {
                    counter("Offen", model.counts["open"].int, "envelope.badge.fill", Color.panelAccent, .open)
                    counter("Beantwortet", model.counts["answered"].int, "arrowshape.turn.up.left.fill", .blue, .answered)
                    counter("Notfall", model.counts["urgent"].int, "light.beacon.max.fill", .red, .urgent)
                    counter("Geschlossen", model.counts["closed"].int, "lock.fill", .secondary, .closed)
                }.listRowInsets(EdgeInsets(top: 8, leading: 8, bottom: 8, trailing: 8))
                if model.counts["waitingDelivery"].int > 0 { Label("\(model.counts["waitingDelivery"].int) Antwort(en) warten auf den Spieler – Zustellung nach kurzer Online-Zeit", systemImage: "clock.badge").font(.caption).foregroundStyle(.secondary) }
                if !model.enabled { Label("Das Ticketsystem ist auf dem Server deaktiviert.", systemImage: "pause.circle").foregroundStyle(.orange) }
            }
            Section {
                Picker("Filter", selection: $filter) { ForEach(SuiteTicketFilter.allCases) { Text($0.title).tag($0) } }.pickerStyle(.menu)
                if model.rows.isEmpty && !model.busy { ContentUnavailableView(filter == .active ? "Keine offenen Tickets" : "Keine Tickets", systemImage: "checkmark.seal", description: Text(filter == .active ? "Alles erledigt. Neue Tickets erscheinen hier und per Bark." : "Für diesen Filter gibt es nichts.")) }
                ForEach(model.rows, id: \.["id"].string) { row in
                    NavigationLink { SuiteTicketDetailView(suite: suite, id: row["id"].string, panel: panel, server: server) } label: { SuiteTicketRow(row: row) }
                        .accessibilityIdentifier("ticket-" + row["id"].string)
                }
                if model.next > 0 { Button("Weitere laden") { Task { await model.load(filter, query: query, more: true) } }.disabled(model.busy) }
            } header: { HStack { Text("Tickets"); Spacer(); if model.busy { ProgressView().controlSize(.small) } } }
        }.panelScreen().navigationTitle("Tickets").searchable(text: $query, prompt: "Ticket, Spieler oder Betreff").errorAlert($model.error)
            .toolbar { if suite.scope == "full" { NavigationLink { SuiteTicketSettingsView(suite: suite) } label: { Image(systemName: "gearshape") }.accessibilityLabel("Ticketeinstellungen") } }
            .refreshable { await model.load(filter, query: query) }
            .onChange(of: filter) { _, value in Task { await model.load(value, query: query) } }
            .task(id: query) { try? await Task.sleep(for: .milliseconds(250)); await model.load(filter, query: query) }
            .task(id: scenePhase) { guard scenePhase == .active else { return }; while !Task.isCancelled { do { try await Task.sleep(for: .seconds(15)) } catch { return }; await model.load(filter, query: query) } }
    }
    func counter(_ title: String, _ value: Int, _ symbol: String, _ tint: Color, _ target: SuiteTicketFilter) -> some View {
        Button { filter = target } label: {
            VStack(alignment: .leading, spacing: 6) {
                Label(title, systemImage: symbol).font(.caption.weight(.semibold)).foregroundStyle(tint)
                Text(model.counts == .null ? "–" : String(value)).font(.title2.bold()).monospacedDigit().contentTransition(.numericText())
            }.frame(maxWidth: .infinity, alignment: .leading).padding(12).background((filter == target ? tint.opacity(0.22) : tint.opacity(0.10)), in: RoundedRectangle(cornerRadius: 14))
        }.buttonStyle(.plain)
    }
}

struct SuiteTicketOwnerAvatar: View {
    let ticket: JSON
    var size: CGFloat = 36
    var body: some View {
        if UUID(uuidString: ticket["owner"].string) != nil {
            SuitePlayerAvatar(row: .object(["id": ticket["owner"], "name": ticket["ownerName"], "online": .bool(ticket["ownerOnline"].bool), "client": .string(ticket["ownerPlatform"].string)]), size: size)
        } else {
            Image(systemName: "bubble.left.and.bubble.right.fill").font(.system(size: size * 0.45)).foregroundStyle(.indigo).frame(width: size, height: size).background(Color.indigo.opacity(0.12), in: RoundedRectangle(cornerRadius: size * 0.22))
        }
    }
}

struct SuiteTicketRow: View {
    let row: JSON
    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            SuiteTicketOwnerAvatar(ticket: row)
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    Text("#" + row["id"].string).font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                    Text(row["subject"].string).font(.subheadline.weight(.semibold)).lineLimit(1)
                }
                Text(row["ownerName"].string + " · " + SuiteTicketText.category(row["category"].string)).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                if !row["preview"].string.isEmpty { Text((row["lastRole"].string == "staff" ? "Team: " : "") + row["preview"].string).font(.caption).foregroundStyle(.secondary).lineLimit(2) }
                HStack(spacing: 6) {
                    SuiteTicketChip(text: SuiteTicketText.priority(row["priority"].string), symbol: row["priority"].string == "urgent" ? "light.beacon.max.fill" : "flag.fill", color: SuiteTicketText.priorityColor(row["priority"].string))
                    SuiteTicketChip(text: SuiteTicketText.status(row["status"].string), symbol: SuiteTicketText.statusSymbol(row["status"].string), color: row["status"].string == "closed" ? .secondary : Color.panelAccent)
                    if row["pending"].int > 0 { SuiteTicketChip(text: "Zustellung offen", symbol: "clock", color: .blue) }
                    if !row["claimedBy"].string.isEmpty { SuiteTicketChip(text: row["claimedBy"].string, symbol: "person.fill.checkmark", color: .teal) }
                }
            }
            Spacer(minLength: 0)
            if let date = SuitePlayerPresentation.date(row["updated"]) { Text(date, style: .relative).font(.caption2).foregroundStyle(.secondary).multilineTextAlignment(.trailing) }
        }.padding(.vertical, 2)
    }
}

struct SuiteTicketChip: View {
    let text: String
    let symbol: String
    let color: Color
    var body: some View { Label(text, systemImage: symbol).font(.caption2.weight(.semibold)).lineLimit(1).padding(.horizontal, 7).padding(.vertical, 3).background(color.opacity(0.15), in: Capsule()).foregroundStyle(color) }
}

struct SuiteTicketDetailView: View {
    let suite: SuiteAPI
    let id: String
    var panel: PanelAPI? = nil
    var server: Server? = nil
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.dismiss) private var dismiss
    @State private var ticket: JSON = .null
    @State private var reply = ""
    @State private var busy = false
    @State private var error: String?
    @State private var notice: String?
    @State private var confirmDelete = false
    @State private var confirmClose = false
    var canManage: Bool { suite.scope == "full" || suite.scope == "manage" }
    var active: Bool { ticket["status"].string != "closed" }
    var body: some View {
        ScrollViewReader { proxy in
            List {
                if ticket == .null { ProgressView("Ticket wird geladen …") }
                else {
                    header
                    Section("Verlauf") {
                        ForEach(Array(ticket["items"].array.enumerated()), id: \.offset) { _, message in SuiteTicketBubble(message: message, deliveryDelay: ticket["deliveryDelay"].int, ownerOnline: ticket["ownerOnline"].bool).listRowSeparator(.hidden).id("m-" + message["id"].string) }
                    }
                    if canManage {
                        Section {
                            TextField(active ? "Antwort an " + ticket["ownerName"].string : "Antwort öffnet das Ticket wieder", text: $reply, axis: .vertical).lineLimit(2...8).accessibilityIdentifier("ticketReply")
                            Button { Task { await send() } } label: { Label(busy ? "Senden …" : "Antwort senden", systemImage: "paperplane.fill") }.buttonStyle(.glassProminent).disabled(busy || reply.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                            if let notice { Label(notice, systemImage: "checkmark.circle").font(.caption).foregroundStyle(.secondary) }
                        } header: { Text("Antworten") } footer: { Text(deliveryHint).id("composer") }
                    } else { Section { Text("Zum Antworten wird ein manage- oder full-App-Zugang benötigt.").font(.footnote).foregroundStyle(.secondary) } }
                }
            }.panelScreen().navigationTitle("Ticket #" + id).navigationBarTitleDisplayMode(.inline).errorAlert($error)
                .toolbar { if canManage && ticket != .null { actionsMenu } }
                .refreshable { await load() }
                .task(id: scenePhase) {
                    guard scenePhase == .active else { return }
                    await load(); if let last = ticket["items"].array.last { proxy.scrollTo("m-" + last["id"].string, anchor: .bottom) }
                    while !Task.isCancelled { do { try await Task.sleep(for: .seconds(5)) } catch { return }; await load() }
                }
                .onChange(of: ticket["items"].array.count) { old, new in if new > old, let last = ticket["items"].array.last { withAnimation { proxy.scrollTo("m-" + last["id"].string, anchor: .bottom) } } }
                .confirmationDialog("Ticket endgültig löschen?", isPresented: $confirmDelete, titleVisibility: .visible) { Button("Löschen", role: .destructive) { Task { if await perform(["action": .string("delete")]) { dismiss() } } } } message: { Text("Das Ticket und sein Verlauf werden auf dem Server entfernt. Ein Discord-Thread bleibt bestehen.") }
                .confirmationDialog("Ticket schließen?", isPresented: $confirmClose, titleVisibility: .visible) { Button("Schließen", role: .destructive) { Task { await perform(["action": .string("close")]) } } } message: { Text("Der Spieler erhält einen Hinweis. Auf Discord wird der Ticket-Thread archiviert.") }
        }
    }
    var deliveryHint: String {
        if ticket["owner"].string.isEmpty { return "Die Antwort erscheint im Discord-Ticket des Spielers." }
        let delay = max(5, ticket["deliveryDelay"].int)
        return ticket["ownerOnline"].bool ? "Spieler ist online · Zustellung im Spiel nach \(delay) s ununterbrochener Online-Zeit" + (ticket["linked"].bool ? " und sofort auf Discord." : ".") : "Spieler ist offline · Die Antwort wartet und erscheint nach dem nächsten Beitritt (\(delay) s Online-Zeit)" + (ticket["linked"].bool ? ", auf Discord sofort." : ".")
    }
    var header: some View {
        Section {
            HStack(alignment: .top, spacing: 12) {
                SuiteTicketOwnerAvatar(ticket: ticket, size: 52)
                VStack(alignment: .leading, spacing: 5) {
                    Text(ticket["subject"].string).font(.headline)
                    HStack(spacing: 6) {
                        Text(ticket["ownerName"].string).font(.subheadline)
                        if ticket["ownerOnline"].bool { SuiteTicketChip(text: "Online", symbol: "circle.fill", color: .green) }
                        if ticket["linked"].bool { SuiteTicketChip(text: "Discord verknüpft", symbol: "link", color: .indigo) }
                    }
                    HStack(spacing: 6) {
                        SuiteTicketChip(text: SuiteTicketText.priority(ticket["priority"].string), symbol: "flag.fill", color: SuiteTicketText.priorityColor(ticket["priority"].string))
                        SuiteTicketChip(text: SuiteTicketText.status(ticket["status"].string), symbol: SuiteTicketText.statusSymbol(ticket["status"].string), color: active ? Color.panelAccent : .secondary)
                    }
                }
            }
            LabeledContent("Thema") { Label(SuiteTicketText.category(ticket["category"].string), systemImage: SuiteTicketText.categorySymbol(ticket["category"].string)) }
            LabeledContent("Erstellt über", value: SuiteTicketText.source(ticket["source"].string))
            if let date = SuitePlayerPresentation.date(ticket["created"]) { LabeledContent("Erstellt") { Text(date, format: .dateTime.day().month().hour().minute()) } }
            if !ticket["claimedBy"].string.isEmpty { LabeledContent("Übernommen von", value: ticket["claimedBy"].string) }
            if !ticket["closedBy"].string.isEmpty { LabeledContent("Geschlossen von", value: ticket["closedBy"].string) }
            if !ticket["channel"].string.isEmpty, !ticket["guild"].string.isEmpty, let url = URL(string: "https://discord.com/channels/" + ticket["guild"].string + "/" + ticket["channel"].string) { Link(destination: url) { Label("Im Discord öffnen", systemImage: "arrow.up.right.square") } }
            if let panel, let server, UUID(uuidString: ticket["owner"].string) != nil { NavigationLink { SuitePlayerView(suite: suite, panel: panel, server: server, player: ticket["owner"].string, actions: []) } label: { Label("Spielerprofil öffnen", systemImage: "person.crop.circle") } }
        }
    }
    var actionsMenu: some View {
        Menu {
            if active {
                Button("Übernehmen", systemImage: "person.fill.checkmark") { Task { await perform(["action": .string("claim")]) } }
                Menu("Priorität", systemImage: "flag") { ForEach(SuiteTicketText.priorities, id: \.self) { value in Button(SuiteTicketText.priority(value), systemImage: ticket["priority"].string == value ? "checkmark" : "flag") { Task { await perform(["action": .string("priority"), "priority": .string(value)]) } } } }
                Button("Ticket schließen", systemImage: "lock", role: .destructive) { confirmClose = true }
            } else { Button("Wieder öffnen", systemImage: "lock.open") { Task { await perform(["action": .string("reopen")]) } } }
            Button("Verlauf kopieren", systemImage: "doc.on.doc") { Clipboard.copy(ticket["items"].array.map { $0["author"].string + ": " + $0["text"].string }.joined(separator: "\n")) }
            if suite.scope == "full" { Button("Löschen", systemImage: "trash", role: .destructive) { confirmDelete = true } }
        } label: { Image(systemName: "ellipsis.circle") }.accessibilityLabel("Ticketaktionen")
    }
    func load() async {
        do { ticket = try await suite.data("ticket", query: ["id": id]) }
        catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }
    @discardableResult func perform(_ values: [String: JSON]) async -> Bool {
        busy = true; defer { busy = false }
        var body = values; body["id"] = .string(id)
        do {
            let result = try await suite.request("tickets", method: "POST", body: .object(body))
            if result["ticket"] != .null { ticket = result["ticket"] } else { await load() }
            notice = result["description"].string; Feedback.success(); return true
        } catch { self.error = error.localizedDescription; return false }
    }
    func send() async {
        let text = reply.trimmingCharacters(in: .whitespacesAndNewlines); guard !text.isEmpty else { return }
        if await perform(["action": .string("reply"), "text": .string(text)]) { reply = "" }
    }
}

struct SuiteTicketBubble: View {
    let message: JSON
    let deliveryDelay: Int
    let ownerOnline: Bool
    var role: String { message["role"].string }
    var body: some View {
        if role == "system" {
            Text(message["text"].string).font(.caption).foregroundStyle(.secondary).frame(maxWidth: .infinity).multilineTextAlignment(.center)
        } else {
            HStack {
                if role == "staff" { Spacer(minLength: 40) }
                VStack(alignment: role == "staff" ? .trailing : .leading, spacing: 4) {
                    HStack(spacing: 5) {
                        Text(message["author"].string).font(.caption.weight(.semibold))
                        Text(role == "staff" ? "Team · " + SuiteTicketText.source(message["source"].string) : SuiteTicketText.source(message["source"].string)).font(.caption2).foregroundStyle(.secondary)
                    }
                    Text(message["text"].string).textSelection(.enabled).padding(10).background(role == "staff" ? Color.panelAccent.opacity(0.18) : Color.secondary.opacity(0.12), in: RoundedRectangle(cornerRadius: 14))
                    HStack(spacing: 4) {
                        if let date = SuitePlayerPresentation.date(message["at"]) { Text(date, style: .time) }
                        if role == "staff" { Image(systemName: message["delivered"].bool ? "checkmark.circle.fill" : "clock"); Text(message["delivered"].bool ? "zugestellt" : (ownerOnline ? "wird zugestellt" : "wartet auf Spieler")) }
                    }.font(.caption2).foregroundStyle(.secondary)
                }
                if role != "staff" { Spacer(minLength: 40) }
            }
        }
    }
}

struct SuiteTicketSettingsView: View {
    let suite: SuiteAPI
    @State private var data: JSON = .null
    @State private var enabled = true
    @State private var discordEnabled = true
    @State private var channelId = ""
    @State private var mode = "thread"
    @State private var categoryId = ""
    @State private var logChannelId = ""
    @State private var roles: Set<String> = []
    @State private var maxOpen = 3
    @State private var cooldown = 60
    @State private var delay = 20
    @State private var joinHint = true
    @State private var bark = true
    @State private var pingStaff = true
    @State private var busy = false
    @State private var error: String?
    @State private var notice: String?
    var body: some View {
        Form {
            if data == .null { ProgressView("Einstellungen und Discord-Diagnose laden …") }
            else {
                Section {
                    Toggle("Ticketsystem aktiv", isOn: $enabled)
                    Stepper("Max. \(maxOpen) offene Tickets pro Spieler", value: $maxOpen, in: 1...20)
                    Stepper("Neues Ticket frühestens nach \(cooldown) s", value: $cooldown, in: 0...3600, step: 30)
                    Stepper("Antworten nach \(delay) s Online-Zeit zustellen", value: $delay, in: 5...300, step: 5)
                    Toggle("Spielern /ticket wöchentlich vorschlagen", isOn: $joinHint)
                    Toggle("Bark bei neuen Tickets", isOn: $bark)
                } header: { Text("Minecraft") } footer: { Text("Spieler öffnen Tickets mit /ticket – Java mit Menüs und Chat, Bedrock mit nativen Formularen. Antworten des Teams warten, bis der Spieler online ist, und kommen als privater Ping mit Öffnen-Link (Java) bzw. /ticket view (Bedrock). Notfall-Tickets kommen bei Bark als kritischer Alarm, außer während deiner Ruhezeit (Benachrichtigungen → Ruhezeiten).") }
                Section {
                    Toggle("Tickets auf Discord", isOn: $discordEnabled)
                    Picker("Ticketkanal (Panel)", selection: $channelId) { Text("Nicht festgelegt").tag(""); ForEach(channels, id: \.["id"].string) { row in Text("#" + row["name"].string).tag(row["id"].string) } }
                    Picker("Ticket-Art", selection: $mode) { Text("Private Threads im Ticketkanal").tag("thread"); Text("Eigene private Kanäle").tag("channel") }
                    if mode == "channel" { Picker("Kategorie für Ticketkanäle", selection: $categoryId) { Text("Keine Kategorie").tag(""); ForEach(data["categories"].array, id: \.["id"].string) { row in Text(row["name"].string).tag(row["id"].string) } } }
                    Picker("Protokollkanal (optional)", selection: $logChannelId) { Text("Keiner").tag(""); ForEach(channels, id: \.["id"].string) { row in Text("#" + row["name"].string).tag(row["id"].string) } }
                    Toggle("Teamrollen bei neuen Tickets erwähnen", isOn: $pingStaff)
                } header: { Text("Discord · gleicher Bot wie die Admin Suite") } footer: { Text(data["botReady"].bool ? (data["botConnected"].bool ? "Der Admin-Suite-Bot ist verbunden – kein zusätzliches Token nötig. Alternativ in Discord: /ticket setup." : "Der Bot ist eingerichtet, aber gerade nicht verbunden.") : "Zuerst den Discord-Bot der Admin Suite einrichten (Discord-Einstellungen). Tickets funktionieren auch ohne Discord im Spiel und in der App.") }
                Section {
                    if data["roles"].array.isEmpty { Text("Keine Rollen geladen.").foregroundStyle(.secondary) }
                    ForEach(data["roles"].array.sorted { $0["position"].int > $1["position"].int }, id: \.["id"].string) { role in
                        Button { if roles.contains(role["id"].string) { roles.remove(role["id"].string) } else { roles.insert(role["id"].string) } } label: {
                            HStack { Circle().fill(role["color"].int == 0 ? Color.secondary : Color(red: Double((role["color"].int >> 16) & 255) / 255, green: Double((role["color"].int >> 8) & 255) / 255, blue: Double(role["color"].int & 255) / 255)).frame(width: 10, height: 10); Text(role["name"].string).foregroundStyle(.primary); Spacer(); if roles.contains(role["id"].string) { Image(systemName: "checkmark").foregroundStyle(Color.panelAccent) } }
                        }
                    }
                } header: { Text("Teamrollen") } footer: { Text("Diese Rollen sehen Tickets und werden benachrichtigt. Ohne Auswahl gelten die Admin-Rollen des Discord-Bots.") }
                Section {
                    Button { Task { await save(panel: false) } } label: { Label("Einstellungen speichern", systemImage: "checkmark.circle.fill") }.disabled(busy)
                    Button { Task { await save(panel: true) } } label: { Label("Speichern & Ticket-Panel posten", systemImage: "rectangle.and.paperclip") }.disabled(busy || channelId.isEmpty || !data["botReady"].bool)
                    if busy { ProgressView() }
                    if let notice { Label(notice, systemImage: "info.circle").font(.footnote).foregroundStyle(.secondary) }
                }
                Section {
                    ForEach(Array(data["diagnostics"].array.enumerated()), id: \.offset) { _, check in
                        HStack(alignment: .top, spacing: 10) {
                            Image(systemName: check["level"].string == "ok" ? "checkmark.circle.fill" : check["level"].string == "warn" ? "exclamationmark.triangle.fill" : "xmark.octagon.fill").foregroundStyle(check["level"].string == "ok" ? .green : check["level"].string == "warn" ? .orange : .red)
                            VStack(alignment: .leading, spacing: 2) { Text(check["title"].string).font(.subheadline.weight(.semibold)); Text(check["detail"].string.replacingOccurrences(of: "<@&", with: "@").replacingOccurrences(of: ">", with: "")).font(.caption).foregroundStyle(.secondary) }
                        }
                    }
                    Button("Diagnose erneut ausführen", systemImage: "stethoscope") { Task { await load() } }.disabled(busy)
                } header: { Text("Diagnose") } footer: { Text("Prüft die echten Bot-Rechte im Ticketkanal, in der Kategorie und im Protokollkanal sowie die Sichtbarkeit für Spieler und Team.") }
            }
        }.panelScreen().navigationTitle("Ticketeinstellungen").navigationBarTitleDisplayMode(.inline).errorAlert($error)
            .task { await load() }.refreshable { await load() }
    }
    var channels: [JSON] { data["channels"].array.sorted { $0["position"].int < $1["position"].int } }
    func load() async {
        busy = true; defer { busy = false }
        do {
            let value = try await suite.data("ticket-settings"); data = value
            enabled = value["enabled"].bool; discordEnabled = value["discordEnabled"].bool; channelId = value["channelId"].string; mode = value["mode"].string == "channel" ? "channel" : "thread"
            categoryId = value["categoryId"].string; logChannelId = value["logChannelId"].string; roles = Set(value["staffRoleIds"].array.map(\.string))
            maxOpen = max(1, value["maxOpen"].int); cooldown = value["cooldown"].int; delay = max(5, value["deliveryDelay"].int); joinHint = value["joinHint"].bool; bark = value["bark"].bool; pingStaff = value["pingStaff"].bool
            // Pickers need their current value among the options, even if Discord did not list it.
            let known = Set(value["channels"].array.map { $0["id"].string }); if !channelId.isEmpty && !known.contains(channelId) { channelId = "" }; if !logChannelId.isEmpty && !known.contains(logChannelId) { logChannelId = "" }
            if !categoryId.isEmpty && !value["categories"].array.contains(where: { $0["id"].string == categoryId }) { categoryId = "" }
        } catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }
    func save(panel: Bool) async {
        busy = true
        let body: [String: JSON] = ["enabled": .bool(enabled), "discordEnabled": .bool(discordEnabled), "channelId": .string(channelId), "mode": .string(mode), "categoryId": .string(categoryId), "logChannelId": .string(logChannelId), "staffRoleIds": .array(roles.sorted().map(JSON.string)), "maxOpen": .number(Double(maxOpen)), "cooldown": .number(Double(cooldown)), "deliveryDelay": .number(Double(delay)), "joinHint": .bool(joinHint), "bark": .bool(bark), "pingStaff": .bool(pingStaff)]
        do {
            let saved = try await suite.request("tickets/settings", method: "POST", body: .object(body))
            notice = saved["description"].string
            if panel { let posted = try await suite.request("tickets/settings", method: "POST", body: .object(["postPanel": .bool(true)])); notice = posted["title"].string + " · " + posted["description"].string }
            Feedback.success()
        } catch { self.error = error.localizedDescription }
        busy = false
        await load()
    }
}
