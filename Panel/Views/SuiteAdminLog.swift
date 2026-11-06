import SwiftUI

/// Admin Suite 9.7.0 admin log: one row per admin with an unread badge, then the full protocol of that
/// admin – what happened, to whom and where. Read markers are per app user and only change on request.
enum SuiteAdminLogText {
    static func symbol(_ row: JSON) -> String {
        let action = row["action"].string.lowercased()
        if action.contains("teleport") || action.contains("hergeholt") || action.contains("tp") { return "location.fill" }
        switch row["category"].string {
        case "vanish": return "eye.slash.fill"
        case "bans": return "hand.raised.fill"
        case "mutes": return "speaker.slash.fill"
        case "punishments": return "exclamationmark.triangle.fill"
        case "devices": return "iphone.gen3"
        case "admin": return "wrench.and.screwdriver.fill"
        case "view": return "eye"
        default: return "list.bullet"
        }
    }
    static func tint(_ row: JSON) -> Color {
        switch row["category"].string {
        case "bans": return .red
        case "mutes", "punishments": return .orange
        case "vanish": return .purple
        case "devices": return .indigo
        case "view": return .secondary
        default: return Color.panelAccent
        }
    }
    static func source(_ value: String) -> String {
        switch value { case "app": return "App"; case "discord": return "Discord"; case "console": return "Konsole"; case "system": return "System"; default: return "Minecraft" }
    }
    static func location(_ value: JSON) -> String? {
        guard value != .null, !value["world"].string.isEmpty else { return nil }
        return value["world"].string + " · " + value["x"].string + " / " + value["y"].string + " / " + value["z"].string
    }
    static let categories: [(String, String)] = [("", "Alle Aktionen"), ("admin", "Verwaltung & Teleport"), ("vanish", "Vanish"), ("bans", "Banns"), ("mutes", "Mutes"), ("punishments", "Strafen"), ("devices", "Geräte"), ("other", "Sonstiges"), ("view", "Nur angesehen")]
}

struct SuiteUnreadBadge: View {
    let count: Int
    var body: some View {
        if count > 0 {
            Text(count > 99 ? "99+" : String(count)).font(.caption2.bold()).monospacedDigit().padding(.horizontal, 7).padding(.vertical, 3).background(.red, in: Capsule()).foregroundStyle(.white).accessibilityLabel("\(count) ungelesen")
        }
    }
}

struct SuiteAdminLogView: View {
    let suite: SuiteAPI
    var panel: PanelAPI? = nil
    var server: Server? = nil
    @State private var data: JSON = .null
    @State private var error: String?
    @State private var busy = false
    @AppStorage("adminLog.includeViews") private var includeViews = false
    @State private var filter = false
    var admins: [JSON] { data["admins"].array }
    /// 9.9: which actions raise the unread counter for this account.
    var filterCaption: String {
        let mode = data["settings"]["mode"].string
        guard !mode.isEmpty else { return "" }
        for item in SuiteAdminLogFilterView.modes where item.0 == mode { return "Zähler: " + item.1 }
        return ""
    }
    var body: some View {
        List {
            if suite.features.contains("history-management-v1") { Section { SuiteHistoryLink(suite: suite, kind: "admin-log", title: "Admin-Log") } }
            Section {
                HStack(spacing: 14) {
                    ZStack { Circle().fill(LinearGradient(colors: [Color.panelAccent, Color.panelSecondary], startPoint: .topLeading, endPoint: .bottomTrailing)).frame(width: 48, height: 48); Image(systemName: "list.bullet.clipboard.fill").font(.title3).foregroundStyle(.white) }
                    VStack(alignment: .leading, spacing: 3) {
                        Text(data["unread"].int == 0 ? "Alles gelesen" : "\(data["unread"].int) neue Admin-Aktionen").font(.headline)
                        Text("\(admins.count) Admins · \(data["total"].int) Einträge gespeichert").font(.caption).foregroundStyle(.secondary)
                        if !filterCaption.isEmpty { Text(filterCaption).font(.caption2.weight(.semibold)).foregroundStyle(Color.panelAccent) }
                    }
                    Spacer()
                    if busy { ProgressView() }
                }
                Button("Alle als gelesen markieren", systemImage: "checkmark.circle") { Task { await markRead("") } }.disabled(data["unread"].int == 0 || busy).accessibilityIdentifier("adminLogReadAll")
                Toggle("Auch reine Ansichten zeigen", isOn: $includeViews)
            }
            Section("Admins") {
                if data == .null && error == nil { ProgressView("Admin-Log wird geladen …") }
                if data != .null && admins.isEmpty { Text("Noch keine Admin-Aktionen protokolliert.").foregroundStyle(.secondary) }
                ForEach(admins, id: \.["admin"].string) { row in
                    NavigationLink { SuiteAdminLogDetailView(suite: suite, admin: row["admin"].string, title: row["name"].string, panel: panel, server: server) { await load() } } label: { adminRow(row) }
                        .swipeActions { if row["unread"].int > 0 { Button("Gelesen", systemImage: "checkmark") { Task { await markRead(row["admin"].string) } }.tint(Color.panelAccent) } }
                }
            }
            Section { NavigationLink { SuiteAdminLogDetailView(suite: suite, admin: "", title: "Gesamtes Protokoll", panel: panel, server: server) { await load() } } label: { Label("Gesamtes Protokoll durchsuchen", systemImage: "magnifyingglass") } }
        }.panelScreen().navigationTitle("Admin-Log").errorAlert($error)
            .toolbar { if suite.features.contains("admin-log-v2") && data != .null { Button("Filter", systemImage: "line.3.horizontal.decrease.circle") { filter = true }.accessibilityIdentifier("adminLogFilter") } }
            .sheet(isPresented: $filter) { SuiteAdminLogFilterView(suite: suite, overview: data) { await load() } }
            .task(id: includeViews) { await load() }.refreshable { await load() }
    }
    func adminRow(_ row: JSON) -> some View {
        HStack(spacing: 12) {
            SuitePlayerAvatar(row: .object(["id": row["uuid"], "online": .bool(row["online"].bool)]), size: 38)
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text(row["name"].string).font(.subheadline.weight(.semibold))
                    if row["self"].bool { Text("Du").font(.caption2.bold()).padding(.horizontal, 6).padding(.vertical, 2).background(Color.panelAccent.opacity(0.15), in: Capsule()) }
                    if row["staff"] == .bool(false) { Text("Spieler").font(.caption2.bold()).padding(.horizontal, 6).padding(.vertical, 2).background(Color.secondary.opacity(0.15), in: Capsule()).foregroundStyle(.secondary) }
                    ForEach(row["sources"].array.map(\.string), id: \.self) { source in Text(SuiteAdminLogText.source(source)).font(.caption2).foregroundStyle(.secondary) }
                }
                let last = row["last"]
                HStack(spacing: 4) {
                    Image(systemName: SuiteAdminLogText.symbol(last)).font(.caption2).foregroundStyle(SuiteAdminLogText.tint(last))
                    Text(last["action"].string).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    if let date = SuitePlayerPresentation.date(last["time"]) { Text("· ") + Text(date, style: .relative) }
                }.font(.caption).foregroundStyle(.secondary)
            }
            Spacer(minLength: 4)
            SuiteUnreadBadge(count: row["unread"].int)
        }.padding(.vertical, 2).accessibilityElement(children: .combine)
    }
    func load() async {
        busy = true; defer { busy = false }
        do { data = try await suite.data("admin-log", query: includeViews ? ["views": "true"] : [:]); error = nil }
        catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }
    func markRead(_ admin: String) async {
        do { _ = try await suite.request("admin-log/read", method: "POST", body: .object(["admin": .string(admin)])); Feedback.success(); await load() }
        catch { self.error = error.localizedDescription }
    }
}

struct SuiteAdminLogDetailView: View {
    let suite: SuiteAPI
    let admin: String
    let title: String
    var panel: PanelAPI? = nil
    var server: Server? = nil
    var changed: () async -> Void = {}
    @State private var items: [JSON] = []
    @State private var next = -1
    @State private var total = 0
    @State private var query = ""
    @State private var category = ""
    @State private var error: String?
    @State private var busy = false
    @AppStorage("adminLog.includeViews") private var includeViews = false
    var unread: Int { items.filter { $0["unread"].bool }.count }
    var body: some View {
        List {
            Section {
                Picker("Kategorie", selection: $category) { ForEach(SuiteAdminLogText.categories, id: \.0) { Text($0.1).tag($0.0) } }
                LabeledContent("Einträge", value: String(total))
            }
            ForEach(groups, id: \.0) { group in
                Section(group.0) { ForEach(group.1, id: \.["id"].string) { row in entry(row) } }
            }
            if next >= 0 { Button("Ältere Einträge laden") { Task { await load(more: true) } }.disabled(busy) }
            if items.isEmpty && !busy { ContentUnavailableView(query.isEmpty ? "Keine Einträge" : "Keine Treffer", systemImage: "list.bullet.clipboard") }
        }.panelScreen().navigationTitle(title).navigationBarTitleDisplayMode(.inline).errorAlert($error)
            .searchable(text: $query, prompt: "Aktion, Spieler oder Welt")
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Als gelesen markieren", systemImage: "checkmark.circle") { Task { await markRead() } }.disabled(unread == 0).accessibilityIdentifier("adminLogMarkRead") } }
            .task(id: "\(query)|\(category)|\(includeViews)") { try? await Task.sleep(for: .milliseconds(query.isEmpty ? 0 : 300)); await load() }
            .refreshable { await load() }
    }
    var groups: [(String, [JSON])] {
        var order: [String] = []; var map: [String: [JSON]] = [:]
        for row in items {
            let day = SuitePlayerPresentation.date(row["time"]).map { $0.formatted(.dateTime.weekday(.wide).day().month(.wide)) } ?? "Ohne Datum"
            if map[day] == nil { order.append(day) }
            map[day, default: []].append(row)
        }
        return order.map { ($0, map[$0] ?? []) }
    }
    @ViewBuilder func entry(_ row: JSON) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: SuiteAdminLogText.symbol(row)).font(.subheadline).foregroundStyle(SuiteAdminLogText.tint(row)).frame(width: 30, height: 30).background(SuiteAdminLogText.tint(row).opacity(0.12), in: RoundedRectangle(cornerRadius: 9))
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    if row["unread"].bool { Circle().fill(.red).frame(width: 7, height: 7).accessibilityLabel("Ungelesen") }
                    Text(row["action"].string).font(.subheadline.weight(.semibold))
                    Spacer(minLength: 0)
                    if let date = SuitePlayerPresentation.date(row["time"]) { Text(date, format: .dateTime.hour().minute()).font(.caption2).monospacedDigit().foregroundStyle(.secondary) }
                }
                if admin.isEmpty { Text(row["adminName"].string + " · " + SuiteAdminLogText.source(row["source"].string)).font(.caption).foregroundStyle(Color.panelAccent) }
                if !row["targetName"].string.isEmpty { Label(row["targetName"].string, systemImage: "person.fill").font(.caption) }
                if !row["detail"].string.isEmpty { Text(row["detail"].string).font(.caption).foregroundStyle(.secondary).lineLimit(4).textSelection(.enabled) }
                if let place = SuiteAdminLogText.location(row["location"]) { Label("Admin: " + place, systemImage: "mappin.and.ellipse").font(.caption2).foregroundStyle(.secondary) }
                if let place = SuiteAdminLogText.location(row["targetLocation"]) { Label("Ziel: " + place, systemImage: "scope").font(.caption2).foregroundStyle(.secondary) }
            }
        }.padding(.vertical, 2)
            .contextMenu { Button("Kopieren", systemImage: "doc.on.doc") { Clipboard.copy([row["adminName"].string, row["action"].string, row["targetName"].string, row["detail"].string].filter { !$0.isEmpty }.joined(separator: " · ")) } }
    }
    func load(more: Bool = false) async {
        busy = true; defer { busy = false }
        var parameters = ["admin": admin, "q": query, "category": category, "offset": String(more ? max(0, next) : 0)]
        if includeViews || category == "view" { parameters["views"] = "true" }
        do {
            let data = try await suite.data("admin-log", query: parameters)
            if more { items += data["items"].array } else { items = data["items"].array }
            next = data["next"] == .null ? -1 : data["next"].int; total = data["total"].int; error = nil
        } catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }
    func markRead() async {
        do { _ = try await suite.request("admin-log/read", method: "POST", body: .object(["admin": .string(admin)])); Feedback.success(); await load(); await changed() }
        catch { self.error = error.localizedDescription }
    }
}
