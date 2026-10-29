import Foundation
import Combine

/// Local, per-suite player bookmarks. Nothing here is sent to the server.
@MainActor final class SuitePlayerBookmarks: ObservableObject {
    static let shared = SuitePlayerBookmarks()
    struct Entry: Codable, Equatable, Identifiable { let id: String; var name: String; var at: Date }
    static let favoritesKey = "suite.bookmarks.favorites.v1"
    static let recentKey = "suite.bookmarks.recent.v1"
    static let recentLimit = 8
    @Published private(set) var favoriteValues: [String: [Entry]]
    @Published private(set) var recentValues: [String: [Entry]]
    private let defaults: UserDefaults
    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        favoriteValues = (try? JSONDecoder().decode([String: [Entry]].self, from: defaults.data(forKey: Self.favoritesKey) ?? Data())) ?? [:]
        recentValues = (try? JSONDecoder().decode([String: [Entry]].self, from: defaults.data(forKey: Self.recentKey) ?? Data())) ?? [:]
    }
    func favorites(_ scope: String) -> [Entry] { (favoriteValues[scope] ?? []).sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending } }
    func recent(_ scope: String) -> [Entry] { recentValues[scope] ?? [] }
    func isFavorite(_ scope: String, _ id: String) -> Bool { favoriteValues[scope]?.contains { $0.id == id } ?? false }
    func toggleFavorite(_ scope: String, id: String, name: String) {
        guard !id.isEmpty else { return }
        var list = favoriteValues[scope] ?? []
        if list.contains(where: { $0.id == id }) { list.removeAll { $0.id == id } } else { list.append(Entry(id: id, name: name.isEmpty ? id : name, at: Date())) }
        favoriteValues[scope] = list.isEmpty ? nil : list; persist()
    }
    func visited(_ scope: String, id: String, name: String) {
        guard !id.isEmpty, !name.isEmpty else { return }
        var list = recentValues[scope] ?? []
        list.removeAll { $0.id == id }; list.insert(Entry(id: id, name: name, at: Date()), at: 0)
        recentValues[scope] = Array(list.prefix(Self.recentLimit))
        // Keep favorite names current after renames.
        if var favorites = favoriteValues[scope], let index = favorites.firstIndex(where: { $0.id == id }), favorites[index].name != name { favorites[index].name = name; favoriteValues[scope] = favorites }
        persist()
    }
    func clearRecent(_ scope: String) { recentValues[scope] = nil; persist() }
    private func persist() {
        if let data = try? JSONEncoder().encode(favoriteValues) { defaults.set(data, forKey: Self.favoritesKey) }
        if let data = try? JSONEncoder().encode(recentValues) { defaults.set(data, forKey: Self.recentKey) }
    }
}
extension SuiteAPI {
    /// Stable per-server key for local bookmarks; survives re-pairing with the same suite.
    var bookmarkScope: String { connection.serverIdentity.isEmpty ? connection.id.uuidString : connection.serverIdentity }
}

struct SuiteAdminAlert: Identifiable, Equatable {
    enum Kind: String { case restart, maintenance, reports, bans, mutes, warnings, ultrabans }
    let kind: Kind
    let title: String
    let detail: String
    let symbol: String
    let critical: Bool
    var id: String { kind.rawValue }
}

/// Shapes the admin center payload. Admin Suite 9.3.0 sends it in one request;
/// older suites are assembled from the existing endpoints with the same keys.
enum SuiteAdminDashboard {
    static func legacy(overview: JSON, players: JSON, restart: JSON, maintenance: JSON, reports: JSON) -> JSON {
        var value = overview.object
        value["players"] = .array(players["items"].array)
        if restart != .null { value["restart"] = .object(["active": .bool(restart["active"].bool), "remaining": .number(restart["remaining"].double)]) }
        if maintenance != .null { value["maintenance"] = .object(["active": .bool(maintenance["maintenance"].bool), "reason": maintenance["reason"]]) }
        if reports != .null {
            let open = reports["items"].array.filter { ["OPEN", "IN_REVIEW"].contains($0["status"].string) }
            value["moderation"] = .object(["openReports": .number(Double(open.filter { $0["status"].string == "OPEN" }.count)), "reviewReports": .number(Double(open.filter { $0["status"].string == "IN_REVIEW" }.count))])
            value["reports"] = .array(Array(open.prefix(3)))
            if reports["partial"].bool { value["reportsPartial"] = .bool(true) }
        }
        value["legacy"] = .bool(true)
        return .object(value)
    }
    static func alerts(_ dashboard: JSON) -> [SuiteAdminAlert] {
        var result: [SuiteAdminAlert] = []
        let restart = dashboard["restart"], maintenance = dashboard["maintenance"], moderation = dashboard["moderation"]
        if restart["active"].bool { result.append(SuiteAdminAlert(kind: .restart, title: "Neustart läuft", detail: "In \(restart["remaining"].int) Sekunden", symbol: "timer", critical: true)) }
        if maintenance["active"].bool { result.append(SuiteAdminAlert(kind: .maintenance, title: "Wartungsmodus aktiv", detail: maintenance["reason"].string.isEmpty ? "Nur Teammitglieder können beitreten" : maintenance["reason"].string, symbol: "wrench.and.screwdriver.fill", critical: true)) }
        let open = moderation["openReports"].int, review = moderation["reviewReports"].int
        let more = dashboard["reportsPartial"].bool ? "+" : ""
        if open > 0 { result.append(SuiteAdminAlert(kind: .reports, title: open == 1 && more.isEmpty ? "1 offener Report" : "\(open)\(more) offene Reports", detail: review > 0 ? "\(review) weitere in Bearbeitung" : "Warten auf Bearbeitung", symbol: "flag.fill", critical: true)) }
        else if review > 0 { result.append(SuiteAdminAlert(kind: .reports, title: review == 1 ? "1 Report in Bearbeitung" : "\(review) Reports in Bearbeitung", detail: "Keine neuen Reports", symbol: "flag.fill", critical: false)) }
        let bans = moderation["internalBans"].int, ultrabans = moderation["ultrabans"].int, warned = moderation["warnedPlayers"].int
        if bans > 0 { result.append(SuiteAdminAlert(kind: .bans, title: bans == 1 ? "1 aktiver Suite-Bann" : "\(bans) aktive Suite-Banns", detail: "Interne Sperren der Admin Suite", symbol: "hand.raised.fill", critical: false)) }
        if moderation["mutes"].int > 0 { result.append(SuiteAdminAlert(kind: .mutes, title: "\(moderation["mutes"].int) stummgeschaltet", detail: "Aktive Chat-Sperren", symbol: "speaker.slash.fill", critical: false)) }
        if ultrabans > 0 { result.append(SuiteAdminAlert(kind: .ultrabans, title: ultrabans == 1 ? "1 aktiver Ultrabann" : "\(ultrabans) aktive Ultrabanns", detail: "Identitätsgruppen gesperrt", symbol: "shield.slash.fill", critical: false)) }
        if warned > 0 { result.append(SuiteAdminAlert(kind: .warnings, title: warned == 1 ? "1 Spieler mit Warnung" : "\(warned) Spieler mit Warnungen", detail: "Im Filter „Auffällig“ ansehen", symbol: "exclamationmark.triangle.fill", critical: false)) }
        return result
    }
    static func tpsLevel(_ tps: Double) -> Int { tps <= 0 ? -1 : tps >= 18 ? 0 : tps >= 15 ? 1 : 2 }
    static func ramFraction(_ dashboard: JSON) -> Double? {
        let max = dashboard["ramMax"].double, used = dashboard["ramUsed"].double
        return max > 0 ? min(1, Swift.max(0, used / max)) : nil
    }
}

@MainActor final class SuiteAdminCenterModel: ObservableObject {
    static let reportPageLimit = 20
    /// Report pages are re-read at most once a minute so polling stays far below the suite's 180 requests/minute key limit.
    static let reportCacheSeconds: TimeInterval = 60
    @Published var dashboard: JSON = .null
    @Published var updated: Date?
    @Published var error: String?
    @Published var busy = false
    private(set) var suite: SuiteAPI?
    private var cachedReports: (value: JSON, at: Date)?
    var now: () -> Date = Date.init
    func load(_ suite: SuiteAPI) async {
        guard !busy else { return }
        if self.suite !== suite { self.suite = suite; dashboard = .null; updated = nil; cachedReports = nil }
        busy = true; defer { busy = false }
        do {
            let value: JSON
            if suite.features.contains("admin-dashboard-v1") { value = try await suite.data("admin-dashboard") }
            else { value = try await legacy(suite) }
            try Task.checkCancellation()
            dashboard = value; updated = Date(); error = nil
        } catch { if !RequestFailure.isCancellation(error) && !Task.isCancelled { self.error = error.localizedDescription } }
    }
    private func legacy(_ suite: SuiteAPI) async throws -> JSON {
        async let overview = suite.data("overview")
        async let players = suite.data("players", query: ["filter": "online", "limit": "100"])
        let (summary, online) = try await (overview, players)
        // Optional modules: a missing module never hides the core dashboard.
        let restart = suite.features.contains("restart-v1") ? ((try? await suite.data("operations")) ?? .null) : .null
        let maintenance = suite.features.contains("management-v2") ? ((try? await suite.data("management", query: ["section": "status"])) ?? .null) : .null
        let reports = suite.features.contains("moderation-center-v1") ? await recentReports(suite) : .null
        return SuiteAdminDashboard.legacy(overview: summary, players: online, restart: restart, maintenance: maintenance, reports: reports)
    }
    private func recentReports(_ suite: SuiteAPI) async -> JSON {
        if let cached = cachedReports, now().timeIntervalSince(cached.at) < Self.reportCacheSeconds { return cached.value }
        let value = await allReports(suite)
        if value != .null { cachedReports = (value, now()) }
        return value
    }
    /// Older suites page reports by 100; follow `next` so backlog counts are complete (bounded to 2,000 entries).
    private func allReports(_ suite: SuiteAPI) async -> JSON {
        var items: [JSON] = [], offset = 0, loaded = false, partial = false
        for page in 0..<Self.reportPageLimit {
            guard let response = try? await suite.data("reports", query: ["offset": String(offset)]) else { partial = loaded; break }
            loaded = true; items += response["items"].array
            let next = response["next"] == .null ? -1 : response["next"].int
            guard next > offset else { break }
            offset = next
            if page == Self.reportPageLimit - 1 { partial = true }
        }
        return loaded ? .object(["items": .array(items), "partial": .bool(partial)]) : .null
    }
}
