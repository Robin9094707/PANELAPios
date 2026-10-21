import Foundation
import Combine

enum StatisticsDimension: String, CaseIterable, Identifiable, Sendable {
    case clients, platforms, models, versions, inputs, languages
    var id: String { rawValue }
    var title: String {
        switch self {
        case .clients: return "Java & Bedrock"
        case .platforms: return "Plattformen"
        case .models: return "Gerätemodelle · Bedrock"
        case .versions: return "Spielversionen"
        case .inputs: return "Eingabemethoden · Bedrock"
        case .languages: return "Gerätesprachen · Bedrock"
        }
    }
    var symbol: String {
        switch self {
        case .clients: return "person.2"
        case .platforms: return "desktopcomputer"
        case .models: return "iphone"
        case .versions: return "cube"
        case .inputs: return "keyboard"
        case .languages: return "globe"
        }
    }
}
struct StatisticsBucket: Identifiable, Sendable {
    let id: String
    let title: String
    let members: [JSON]
    let total: Int
    var count: Int { members.count }
    var fraction: Double { total > 0 ? Double(count) / Double(total) : 0 }
}
struct SuiteStatisticsSnapshot: Sendable {
    let profiles: [JSON]
    let online: Int
    let recent: Int
    let newProfiles: Int
    let bans: Int
    let mutes: Int
    let warnings: Int
    let points: Double
    let groups: [StatisticsDimension: [StatisticsBucket]]
    init(rows: [JSON] = [], devices: [String: JSON] = [:], now: Date = Date()) {
        var unique: [String: JSON] = [:]
        for row in rows where !row["id"].string.isEmpty { unique[row["id"].string] = row }
        profiles = unique.values.sorted { $0["name"].string.localizedStandardCompare($1["name"].string) == .orderedAscending }
        online = profiles.filter { $0["online"].bool }.count
        func withinDay(_ value: JSON) -> Bool { let seconds = now.timeIntervalSince1970 - value.double / 1000; return value.double > 0 && seconds >= 0 && seconds <= 86400 }
        recent = profiles.filter { $0["online"].bool || withinDay($0["lastSeen"]) }.count
        newProfiles = profiles.filter { withinDay($0["firstSeen"]) }.count
        bans = profiles.filter { $0["ban"].bool }.count
        mutes = profiles.filter { $0["mute"].bool }.count
        warnings = profiles.reduce(0) { $0 + max(0, $1["warnings"].int) }
        points = profiles.reduce(0) { $0 + ($1["points"].double.isFinite ? $1["points"].double : 0) }
        func known(_ text: String) -> String? {
            let value = text.trimmingCharacters(in: .whitespacesAndNewlines)
            return value.isEmpty || ["unknown", "unbekannt", "n/a", "0"].contains(value.lowercased()) ? nil : value
        }
        var result: [StatisticsDimension: [StatisticsBucket]] = [:]
        for dimension in StatisticsDimension.allCases {
            let source = [.models, .inputs, .languages].contains(dimension) ? profiles.filter { $0["client"].string.uppercased() == "BEDROCK" } : profiles
            var buckets: [String: (String, [JSON])] = [:]
            for row in source {
                let client = row["client"].string.uppercased()
                let device = devices[row["id"].string]
                let title: String
                switch dimension {
                case .clients: title = client == "JAVA" ? "Java" : client == "BEDROCK" ? "Bedrock" : "Client unbekannt"
                case .models: title = known(device?["name"].string ?? "") ?? known(row["device"].string) ?? "Nicht erfasst"
                case .versions: title = known(row["version"].string) ?? "Nicht erfasst"
                case .platforms:
                    if client == "JAVA" { title = "Java · Betriebssystem unbekannt" }
                    else if client != "BEDROCK" { title = "Client unbekannt" }
                    else {
                        let reported = device.map(PlayerPlatform.resolve) ?? .unknown
                        title = (reported == .unknown ? PlayerPlatform.resolve(row) : reported).title
                    }
                case .inputs: title = device == nil ? "Noch nicht geladen" : known(device!["input"].string) ?? "Nicht erfasst"
                case .languages: title = device == nil ? "Noch nicht geladen" : known(device!["language"].string) ?? "Nicht erfasst"
                }
                let key = title.lowercased()
                var bucket = buckets[key] ?? (title, [])
                bucket.1.append(row); buckets[key] = bucket
            }
            result[dimension] = buckets.map { key, value in StatisticsBucket(id: dimension.rawValue + "|" + key, title: value.0, members: value.1, total: source.count) }
                .sorted { $0.count == $1.count ? $0.title.localizedStandardCompare($1.title) == .orderedAscending : $0.count > $1.count }
        }
        groups = result
    }
}
struct SuitePeak: Identifiable, Sendable {
    let date: Date
    let online: Int
    var id: Date { date }
}
struct SuiteRecordedHistory: Sendable {
    let samples: [SuitePeak]
    let worldPlayers: Int?
    var last: Date? { samples.last?.date }
    func peak(days: Double, now: Date = Date()) -> Int? { samples.filter { $0.date >= now.addingTimeInterval(-days * 86400) && $0.date <= now }.map(\.online).max() }
    func hourly(now: Date = Date()) -> [SuitePeak] {
        let end = floor(now.timeIntervalSince1970 / 3600)
        var bins: [Int: Int] = [:]
        for sample in samples {
            let hour = floor(sample.date.timeIntervalSince1970 / 3600)
            guard hour >= end - 23, hour <= end, sample.date <= now else { continue }
            let key = Int(hour); bins[key] = max(bins[key] ?? 0, sample.online)
        }
        return bins.sorted { $0.key < $1.key }.map { SuitePeak(date: Date(timeIntervalSince1970: Double($0.key) * 3600), online: $0.value) }
    }
    static func parse(_ text: String) throws -> Self {
        guard text.utf8.count <= 2 * 1024 * 1024 else { throw PanelError(message: "Die Statistikdatei ist zu groß.") }
        var logical: [String] = []; var pending = ""
        for raw in text.components(separatedBy: .newlines) {
            let line = pending.isEmpty ? raw : raw.trimmingCharacters(in: .whitespaces)
            pending += line
            if pending.reversed().prefix(while: { $0 == "\\" }).count % 2 == 1 { pending.removeLast(); continue }
            logical.append(pending); pending = ""
        }
        if !pending.isEmpty { logical.append(pending) }
        var samples: [Int64: SuitePeak] = [:]
        var worldIDs = Set<String>(); var sawWorld = false; var sawSamples = false
        for raw in logical {
            let line = raw.trimmingCharacters(in: .whitespaces)
            guard !line.hasPrefix("#"), !line.hasPrefix("!"), let separator = line.firstIndex(where: { $0 == "=" || $0 == ":" }) else { continue }
            let key = line[..<separator].trimmingCharacters(in: .whitespaces)
            let value = String(line[line.index(after: separator)...]).replacingOccurrences(of: "\\:", with: ":").replacingOccurrences(of: "\\=", with: "=")
            if key.hasPrefix("world.") {
                sawWorld = true
                for id in value.split(separator: ",") { if let uuid = UUID(uuidString: String(id)) { worldIDs.insert(uuid.uuidString) } }
            }
            if key == "samples" {
                sawSamples = true
                let rows = value.split(separator: ";")
                guard rows.count <= 12000 else { throw PanelError(message: "Zu viele Statistikmesspunkte.") }
                for row in rows {
                    let fields = row.split(separator: ":")
                    guard fields.count == 2, let ms = Int64(fields[0]), ms > 0, ms < 253402300799000, let online = Int(fields[1]), (0...1_000_000).contains(online) else { continue }
                    let minute = ms / 60000
                    if online >= (samples[minute]?.online ?? -1) { samples[minute] = SuitePeak(date: Date(timeIntervalSince1970: Double(ms) / 1000), online: online) }
                }
            }
        }
        guard sawSamples else { throw PanelError(message: "Keine unterstützten Statistikaufzeichnungen gefunden.") }
        return Self(samples: samples.values.sorted { $0.date < $1.date }, worldPlayers: sawWorld ? worldIDs.count : nil)
    }
}
@MainActor final class SuiteStatisticsStore: ObservableObject {
    @Published private(set) var snapshot = SuiteStatisticsSnapshot()
    @Published private(set) var history: SuiteRecordedHistory?
    @Published private(set) var updated: Date?
    @Published private(set) var busy = false
    @Published private(set) var detailsBusy = false
    @Published private(set) var detailsDone = 0
    @Published private(set) var detailsTotal = 0
    @Published private(set) var detailFailures = 0
    @Published private(set) var loadedDeviceCount = 0
    @Published private(set) var expected = 0
    @Published private(set) var complete = false
    @Published var error: String?
    @Published private(set) var historyNote: String?
    private var devices: [String: JSON] = [:]
    private var revision = UUID()
    private var detailRevision = UUID()
    func load(suite: SuiteAPI, panel: PanelAPI, server: Server, force: Bool = false) async {
        guard !busy else { return }
        if !force, let updated, Date().timeIntervalSince(updated) < 60 { return }
        let id = UUID(); revision = id; detailRevision = UUID(); detailsBusy = false
        busy = true; error = nil
        defer { if revision == id { busy = false } }
        do {
            var rows: [JSON] = []; var offset = 0; var pages = 0; var next = -1; var total = 0
            repeat {
                let response = try await suite.data("players", query: ["offset": String(offset)])
                try Task.checkCancellation()
                rows = SuitePlayerPresentation.merge(rows, response["items"].array)
                total = response["total"].int; pages += 1
                next = response["next"] == .null ? -1 : response["next"].int
                guard next < 0 || next > offset else { throw PanelError(message: "Die Suite hat eine ungültige Folgeseite geliefert.") }
                offset = next
            } while next >= 0 && pages < 200
            let ids = Set(rows.map { $0["id"].string })
            let oldModels = Dictionary(snapshot.profiles.map { ($0["id"].string, $0["device"].string) }, uniquingKeysWith: { a, _ in a })
            let newModels = Dictionary(rows.map { ($0["id"].string, $0["device"].string) }, uniquingKeysWith: { a, _ in a })
            devices = devices.filter { !force && ids.contains($0.key) && oldModels[$0.key] == newModels[$0.key] }
            let metadata = devices; let collected = rows
            let result = await Task.detached(priority: .utility) { SuiteStatisticsSnapshot(rows: collected, devices: metadata) }.value
            guard revision == id, !Task.isCancelled else { return }
            snapshot = result; updated = Date(); expected = total; complete = next < 0 && result.profiles.count >= total
            loadedDeviceCount = devices.count
            await loadHistory(panel: panel, server: server)
        } catch { if revision == id, !RequestFailure.isCancellation(error), !Task.isCancelled { self.error = error.localizedDescription } }
    }
    private func loadHistory(panel: PanelAPI, server: Server) async {
        if panel.demo {
            let now = Date()
            history = SuiteRecordedHistory(samples: (0..<24).map { SuitePeak(date: now.addingTimeInterval(Double($0 - 23) * 3600), online: ($0 * 7) % 18) }, worldPlayers: 42)
            historyNote = "Synthetischer Demo-Verlauf"; return
        }
        guard panel.access.allows(server.id, "file.read-content") else { history = nil; historyNote = "Online-Historie benötigt Leserechte für die vorhandene Suite-Statistikdatei."; return }
        do {
            let data = try await withThrowingTaskGroup(of: Data.self) { group in
                group.addTask { @MainActor in try await panel.fileData(server: server.id, path: "/plugins/SimplexAdminSuite/server-statistics.properties", limit: 2 * 1024 * 1024) }
                group.addTask { try await Task.sleep(for: .seconds(12)); throw URLError(.timedOut) }
                defer { group.cancelAll() }; return try await group.next()!
            }
            try Task.checkCancellation()
            guard let text = String(data: data, encoding: .utf8) else { throw PanelError(message: "Statistikdatei ist kein UTF-8-Text.") }
            let parsed = try await Task.detached(priority: .utility) { try SuiteRecordedHistory.parse(text) }.value
            try Task.checkCancellation()
            history = parsed; historyNote = nil
        } catch { if !RequestFailure.isCancellation(error), !Task.isCancelled { historyNote = history == nil ? "Keine lesbare Online-Historie vorhanden. Die übrigen Statistiken bleiben verfügbar." : "Historie konnte nicht aktualisiert werden; angezeigten Messzeitpunkt beachten." } }
    }
    func enrich(suite: SuiteAPI, force: Bool = false) async {
        guard !busy, !detailsBusy, updated != nil else { return }
        let id = UUID(); detailRevision = id; detailsBusy = true; detailFailures = 0; detailsDone = 0
        if force { devices.removeAll(); loadedDeviceCount = 0 }
        let rows = snapshot.profiles.filter { $0["client"].string.uppercased() == "BEDROCK" && devices[$0["id"].string] == nil }
        detailsTotal = rows.count
        defer { if detailRevision == id { detailsBusy = false } }
        for offset in stride(from: 0, to: rows.count, by: 2) {
            guard detailRevision == id, !Task.isCancelled else { return }
            let batch = Array(rows[offset..<min(rows.count, offset + 2)])
            await withTaskGroup(of: (String, JSON?).self) { group in
                for row in batch {
                    group.addTask { @MainActor in
                        do {
                            let items = try await suite.data("devices", query: ["player": row["id"].string])["items"].array
                            try Task.checkCancellation()
                            return (row["id"].string, items.max { $0["lastSeen"].double < $1["lastSeen"].double } ?? .null)
                        } catch { return (row["id"].string, nil) }
                    }
                }
                for await (player, value) in group {
                    guard detailRevision == id, !Task.isCancelled else { continue }
                    if let value { devices[player] = value } else { detailFailures += 1 }
                    detailsDone += 1
                }
            }
            guard detailRevision == id, !Task.isCancelled else { return }
            let profiles = snapshot.profiles; let metadata = devices
            let result = await Task.detached(priority: .utility) { SuiteStatisticsSnapshot(rows: profiles, devices: metadata) }.value
            guard detailRevision == id, !Task.isCancelled else { return }
            snapshot = result; loadedDeviceCount = devices.count
            do { try await Task.sleep(for: .seconds(1)) } catch { return }
        }
    }
}
