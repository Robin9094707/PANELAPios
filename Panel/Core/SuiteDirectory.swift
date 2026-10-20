import Foundation
import Combine

enum RequestFailure {
    static func isCancellation(_ error: Error) -> Bool {
        error is CancellationError || (error as? URLError)?.code == .cancelled
    }
}

enum SuitePlayerFilter: String, CaseIterable, Identifiable {
    case all = "Alle", online = "Online", recent = "Zuletzt online"
    var id: String { rawValue }
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
        let selected = rows.filter { filter == .all || (filter == .online ? $0["online"].bool : !$0["online"].bool) }
        return selected.sorted { a, b in
            if filter == .all && a["online"].bool != b["online"].bool { return a["online"].bool }
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
    private var revision = UUID()
    func load(suite: SuiteAPI, query: String, filter: SuitePlayerFilter, reset: Bool) async {
        guard reset || !busy else { return }
        let stamp = UUID(); revision = stamp; busy = true; error = nil
        if reset { rows = []; next = -1 }
        defer { if revision == stamp { busy = false } }
        var offset = reset ? 0 : next
        guard offset >= 0 else { return }
        do {
            repeat {
                let response = try await suite.data("players", query: ["q": query.trimmingCharacters(in: .whitespacesAndNewlines), "offset": String(offset)])
                guard revision == stamp, !Task.isCancelled else { return }
                rows = SuitePlayerPresentation.merge(rows, response["items"].array)
                total = response["total"].int
                let candidate = response["next"] == .null ? -1 : response["next"].int
                next = candidate > offset ? candidate : -1
                offset = next
                // The existing API sorts online first. Recent offline profiles require
                // all pages before the global last-seen order is complete.
                if filter == .all { break }
                if filter == .online && response["items"].array.contains(where: { !$0["online"].bool }) { break }
            } while next >= 0
        } catch { if revision == stamp && !RequestFailure.isCancellation(error) && !Task.isCancelled { self.error = error.localizedDescription } }
    }
}
