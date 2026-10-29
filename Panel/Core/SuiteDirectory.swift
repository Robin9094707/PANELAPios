import Foundation
import Combine

enum RequestFailure {
    static func isCancellation(_ error: Error) -> Bool {
        error is CancellationError || (error as? URLError)?.code == .cancelled
    }
}

enum SuitePlayerFilter: String, CaseIterable, Identifiable {
    case all = "Alle", online = "Online", recent = "Zuletzt online", flagged = "Auffällig", new = "Neu"
    var id: String { rawValue }
    /// Segmented choices understood by every suite version.
    static let primary: [SuitePlayerFilter] = [.all, .online, .recent]
    /// Server-side filters added with Admin Suite 9.3.0 (players-filters-v3).
    static let extended: [SuitePlayerFilter] = [.flagged, .new]
    var parameter: String { switch self { case .all: return "all"; case .online: return "online"; case .recent: return "recent"; case .flagged: return "flagged"; case .new: return "new" } }
    var symbol: String { switch self { case .all: return "person.3"; case .online: return "dot.radiowaves.left.and.right"; case .recent: return "clock"; case .flagged: return "exclamationmark.triangle.fill"; case .new: return "sparkles" } }
    var sectionTitle: String { switch self { case .recent: return "Offline · zuletzt gesehen zuerst"; case .flagged: return "Bann, Stummschaltung oder Warnung"; case .new: return "Neu in den letzten 7 Tagen"; default: return rawValue } }
    /// Local presence check; server-only filters are already applied upstream.
    func includes(_ row: JSON) -> Bool { switch self { case .online: return row["online"].bool; case .recent: return !row["online"].bool; default: return true } }
}

enum SuitePlayerPresentation {
    static func device(_ row: JSON) -> String {
        if row["client"].string.uppercased() == "JAVA" { return "Java-Client" }
        return row["device"].string.isEmpty ? "Gerät nicht erfasst" : row["device"].string
    }
    static func date(_ value: JSON) -> Date? {
        guard value.double > 0 else { return nil }
        return Date(timeIntervalSince1970: value.double / 1000)
    }
    static func sorted(_ rows: [JSON], filter: SuitePlayerFilter) -> [JSON] {
        let selected = rows.filter { filter.includes($0) }
        return selected.sorted { a, b in
            if filter == .new && a["firstSeen"].double != b["firstSeen"].double { return a["firstSeen"].double > b["firstSeen"].double }
            if (filter == .all || filter == .flagged) && a["online"].bool != b["online"].bool { return a["online"].bool }
            if !a["online"].bool && a["lastSeen"].double != b["lastSeen"].double { return a["lastSeen"].double > b["lastSeen"].double }
            return a["name"].string.localizedStandardCompare(b["name"].string) == .orderedAscending
        }
    }
    static func merge(_ existing: [JSON], _ incoming: [JSON]) -> [JSON] {
        var result = existing
        var positions = Dictionary(existing.enumerated().map { ($0.element["id"].string, $0.offset) }, uniquingKeysWith: { a, _ in a })
        for row in incoming {
            let id = row["id"].string
            if let index = positions[id] { result[index] = row }
            else { positions[id] = result.count; result.append(row) }
        }
        return result
    }
}

@MainActor final class SuiteDirectory: ObservableObject {
    @Published private(set) var rows: [JSON] = []
    @Published private(set) var total = 0
    @Published private(set) var next = -1
    @Published private(set) var busy = false
    @Published var error: String?
    private static var sessions: [String: SuiteDirectory] = [:]
    static func shared(key: String) -> SuiteDirectory { if let value = sessions[key] { return value }; let value = SuiteDirectory(); sessions[key] = value; return value }
    private struct Page { var rows: [JSON]; var total: Int; var next: Int; var snapshot: String; var loaded: Date }
    private var pages: [String: Page] = [:]
    private var order: [String] = []
    private var revision = UUID()
    private var selected = ""
    private var snapshot = ""
    func load(suite: SuiteAPI, query: String, filter: SuitePlayerFilter, reset: Bool, force: Bool = false) async {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        let key = filter.rawValue + "|" + q.lowercased()
        if !reset && (busy || next < 0 || selected != key) { return }
        let stamp = UUID(); revision = stamp
        if reset, !force, let page = pages[key], filter != .online || Date().timeIntervalSince(page.loaded) < 30 {
            selected = key; rows = page.rows; total = page.total; next = page.next; snapshot = page.snapshot; busy = false; error = nil; return
        }
        if reset && selected != key { rows = []; total = 0; next = -1; snapshot = "" }
        selected = key; busy = true; error = nil
        defer { if revision == stamp { busy = false } }
        do {
            var params = ["q": q, "filter": filter.parameter, "offset": reset ? "0" : String(next), "limit": "50"]
            if !reset && !snapshot.isEmpty { params["snapshot"] = snapshot }
            let response = try await suite.data("players", query: params)
            guard revision == stamp, !Task.isCancelled else { return }
            rows = reset ? response["items"].array : SuitePlayerPresentation.merge(rows, response["items"].array)
            total = response["total"].int
            let candidate = response["next"] == .null ? -1 : response["next"].int
            next = candidate > (reset ? 0 : next) ? candidate : -1; snapshot = response["snapshot"].string
            pages[key] = Page(rows: rows, total: total, next: next, snapshot: snapshot, loaded: Date())
            order.removeAll { $0 == key }; order.append(key)
            if order.count > 24 { pages.removeValue(forKey: order.removeFirst()) }
        } catch { if revision == stamp && !RequestFailure.isCancellation(error) && !Task.isCancelled { self.error = error.localizedDescription } }
    }
}

enum SuiteGermanTime {
    static func text(_ date: Date) -> String {
        let format = DateFormatter(); format.locale = Locale(identifier: "de_DE"); format.timeZone = TimeZone(identifier: "Europe/Berlin"); format.dateFormat = "dd.MM.yyyy HH:mm:ss z"; return format.string(from: date)
    }
}
