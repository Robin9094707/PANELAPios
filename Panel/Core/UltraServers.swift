import Foundation

/// UltraServers (panel.ultraservers.com) runs the Pterodactyl client API (`/api/client`, `ptlc_` keys) plus its own
/// extensions. The extension routes below were checked against the live panel on 13.11.2026: Laravel answers 401 for
/// routes that exist and 404 for unknown ones, so no account was needed to map them.
///
///     GET  servers/{id}/system-backups                 automatic backups (every 6 hours, about 2 weeks)
///     GET  servers/{id}/system-backups/{backup}/download
///     POST servers/{id}/system-backups/{backup}/restore
///     GET  servers/{id}/players                        Player Manager
///     GET  servers/{id}/subdomain  · POST subdomain/create · DELETE subdomain/delete/{id}
///     GET  servers/{id}/proxy      · POST proxy/create     · DELETE proxy/delete/{id}
///     GET  servers/{id}/plugins    · GET plugins/versions  · POST plugins/install
///     GET  servers/{id}/modpacks   · GET modpacks/versions · POST modpacks/install
///     POST servers/{id}/databases/import
///
/// The response schemas of the extensions are not published, so every decoder here is deliberately tolerant
/// (Pterodactyl `data/attributes` wrappers, plain arrays, `{ success, data: { … } }`, camelCase and snake_case).
enum UltraServers {
    static let name = "UltraServers"
    static let panelURL = "https://panel.ultraservers.com"
    static let docsURL = URL(string: "https://docs.ultraservers.com/panel")!
    /// "we automatically back up your server every 6 hours" – docs.ultraservers.com/panel/create-a-backup
    static let backupInterval: TimeInterval = 6 * 3600
    /// "older backups are kept roughly once a day going back about 2 weeks"
    static let retention: TimeInterval = 14 * 86_400
    /// "You'll usually see 8 backups listed here"
    static let visibleBackups = 8

    static func matches(_ url: URL) -> Bool {
        guard let host = url.host?.lowercased() else { return false }
        return host == "ultraservers.com" || host.hasSuffix(".ultraservers.com")
    }
    /// Web panel page of a server (Pterodactyl route `/server/{identifier}`).
    static func webURL(base: URL, server: String, page: String = "") -> URL {
        var url = base.appendingPathComponent("server").appendingPathComponent(server)
        if !page.isEmpty { url = url.appendingPathComponent(page) }
        return url
    }

    // MARK: Tolerant decoding helpers

    /// First list of records in a response: the value itself, `data`, `data.data`, or one of `keys`
    /// at the top level or below `data`.
    static func records(_ json: JSON, keys: [String] = [], fallback: Bool = true) -> [JSON] {
        var candidates: [JSON] = []
        for key in keys { candidates.append(json[key]); candidates.append(json["data"][key]); candidates.append(json.attributes[key]) }
        if fallback { candidates += [json, json["data"], json["data"]["data"], json.attributes] }
        for candidate in candidates { if case .array(let values) = candidate { return values.map(\.attributes) } }
        return []
    }
    static func text(_ value: JSON, _ keys: [String]) -> String {
        for key in keys { let v = value[key]; switch v { case .string, .number: if !v.string.isEmpty { return v.string }; default: continue } }
        return ""
    }
    static func identifier(_ value: JSON) -> String {
        let id = text(value, ["uuid", "id", "backup_id", "backupId", "snapshot", "snapshot_id", "short_id", "name"])
        // IDs go into URL paths; never accept separators or traversal.
        return id.contains("/") || id.contains("..") || id.contains("?") || id.contains("#") ? "" : id
    }
    static func date(_ value: JSON) -> Date? {
        switch value {
        case .number(let n): return n > 10_000_000_000 ? Date(timeIntervalSince1970: n / 1000) : n > 100_000_000 ? Date(timeIntervalSince1970: n) : nil
        case .string(let s):
            let text = s.trimmingCharacters(in: .whitespacesAndNewlines)
            if let n = Double(text) { return date(.number(n)) }
            let iso = ISO8601DateFormatter(); iso.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
            if let d = iso.date(from: text) { return d }
            iso.formatOptions = [.withInternetDateTime]
            if let d = iso.date(from: text) { return d }
            // RFC 3339 with more than three fractional digits (Go/restic: 2026-11-13T06:00:01.123456789Z).
            if let dot = text.firstIndex(of: "."), let zone = text[dot...].firstIndex(where: { $0 == "Z" || $0 == "+" || $0 == "-" }) {
                if let d = iso.date(from: String(text[..<dot]) + String(text[zone...])) { return d }
            }
            let formatter = DateFormatter(); formatter.locale = Locale(identifier: "en_US_POSIX"); formatter.timeZone = TimeZone(identifier: "UTC")
            for format in ["yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd"] {
                formatter.dateFormat = format
                if let d = formatter.date(from: text) { return d }
            }
            return nil
        default: return nil
        }
    }
    static func firstDate(_ value: JSON, _ keys: [String]) -> Date? {
        for key in keys { if let d = date(value[key]) { return d } }
        return nil
    }
}

// MARK: - Automatic backups

struct SystemBackup: Identifiable, Equatable {
    let id: String
    let name: String
    let created: Date?
    let byteCount: Double?
    let sizeText: String
    let raw: JSON
    static func == (l: SystemBackup, r: SystemBackup) -> Bool { l.id == r.id && l.created == r.created && l.name == r.name }

    var restorable: Bool { !id.isEmpty && raw["can_restore"] != .bool(false) && raw["restorable"] != .bool(false) && !hidden }
    var downloadable: Bool { !id.isEmpty && raw["can_download"] != .bool(false) && raw["is_downloadable"] != .bool(false) && raw["downloadable"] != .bool(false) && !hidden }
    /// "the oldest one is temporarily hidden once it's about to be replaced" – respect an explicit flag if the panel sends one.
    var hidden: Bool { raw["hidden"].bool || raw["is_hidden"].bool || raw["rotating"].bool }
    var sizeLabel: String { if let byteCount, byteCount > 0 { return bytes(byteCount) }; return sizeText }

    static func decode(_ json: JSON) -> [SystemBackup] {
        let rows = UltraServers.records(json, keys: ["backups", "system_backups", "systemBackups", "snapshots", "items"])
        var seen = Set<String>()
        let backups: [SystemBackup] = rows.compactMap { row in
            let created = UltraServers.firstDate(row, ["created_at", "createdAt", "completed_at", "completedAt", "date", "time", "timestamp", "created", "backup_date", "modified_at"])
            var id = UltraServers.identifier(row)
            if id.isEmpty, case .string(let plain) = row, !plain.contains("/") { id = plain }
            guard !id.isEmpty || created != nil else { return nil }
            guard seen.insert(id + "|" + (created.map { String($0.timeIntervalSince1970) } ?? "")).inserted else { return nil }
            let sizeKey = ["bytes", "size", "size_bytes", "total_size"].first { row[$0] != .null } ?? "bytes"
            let rawSize = row[sizeKey]
            var numeric: Double? = nil
            if case .number(let n) = rawSize { numeric = n } else if let n = Double(rawSize.string) { numeric = n }
            let name = UltraServers.text(row, ["name", "label", "title", "type"])
            return SystemBackup(id: id, name: name.isEmpty ? "Automatisches Backup" : name, created: created, byteCount: numeric, sizeText: numeric == nil ? rawSize.string : "", raw: row)
        }
        return backups.sorted { ($0.created ?? .distantPast) > ($1.created ?? .distantPast) }
    }
}

/// What the app can say about the automatic schedule from the list alone.
struct SystemBackupSummary: Equatable {
    let count: Int
    let newest: Date?
    let oldest: Date?
    /// Expected time of the next automatic backup (newest + 6 h, moved forward in 6-hour steps past `now`).
    let next: Date?
    /// True when the newest backup is older than 1.5 intervals – the schedule may be paused or delayed.
    let overdue: Bool

    nonisolated init(_ backups: [SystemBackup], now: Date = Date()) {
        let dates = backups.compactMap(\.created)
        count = backups.count; newest = dates.max(); oldest = dates.min()
        if let newest {
            var next = newest.addingTimeInterval(UltraServers.backupInterval)
            while next < now { next = next.addingTimeInterval(UltraServers.backupInterval) }
            self.next = next
            overdue = now.timeIntervalSince(newest) > UltraServers.backupInterval * 1.5
        } else { next = nil; overdue = false }
    }
    /// Days covered by the visible backups, rounded down.
    var coveredDays: Int { guard let newest, let oldest else { return 0 }; return max(0, Int(newest.timeIntervalSince(oldest) / 86_400)) }
}

// MARK: - Player Manager

struct UltraPlayerList: Equatable {
    struct Entry: Identifiable, Equatable, Hashable {
        let name: String
        let uuid: String
        let detail: String
        var id: String { uuid.isEmpty ? name.lowercased() : uuid }
    }
    struct Group: Identifiable, Equatable {
        let key: String
        let title: String
        let symbol: String
        let entries: [Entry]
        var id: String { key }
    }
    var groups: [Group]
    var maximum: Int?

    /// Known list names in the order the web panel shows them.
    static let known: [(keys: [String], key: String, title: String, symbol: String)] = [
        (["online", "players", "online_players", "onlinePlayers", "playing", "current"], "online", "Gerade online", "person.2.fill"),
        (["ops", "opped", "operators", "op"], "ops", "Operatoren", "crown.fill"),
        (["whitelist", "whitelisted", "allowlist"], "whitelist", "Whitelist", "checkmark.shield.fill"),
        (["banned", "bans", "banned_players", "bannedPlayers"], "banned", "Gebannt", "nosign"),
        (["banned_ips", "bannedIps", "ip_bans", "ipBans"], "banned_ips", "Gebannte IPs", "network.slash"),
        (["offline", "known", "history", "all", "offline_players", "offlinePlayers"], "offline", "Offline / bekannt", "clock.arrow.circlepath")
    ]

    static func decode(_ json: JSON) -> UltraPlayerList {
        var root = json.attributes
        if case .object = root["data"] { root = root["data"].attributes }
        var groups: [Group] = []
        var used = Set<String>()
        if case .array(let values) = root {
            groups.append(Group(key: "online", title: "Gerade online", symbol: "person.2.fill", entries: entries(values)))
        } else {
            if case .object = root["players"], root["players"]["online"] != .null || root["players"]["list"] != .null {
                let players = root["players"]
                let list = players["list"].array.isEmpty ? players["sample"].array : players["list"].array
                if !list.isEmpty { groups.append(Group(key: "online", title: "Gerade online", symbol: "person.2.fill", entries: entries(list))) }
                used.insert("players")
            }
            for item in known {
                guard !groups.contains(where: { $0.key == item.key }) else { continue }
                for key in item.keys where !used.contains(key) {
                    if case .array(let values) = root[key] { groups.append(Group(key: item.key, title: item.title, symbol: item.symbol, entries: entries(values))); used.insert(key); break }
                }
            }
            // Unknown extra lists stay visible instead of being dropped.
            for (key, value) in root.object.sorted(by: { $0.key < $1.key }) where !used.contains(key) {
                guard case .array(let values) = value, !values.isEmpty, !entries(values).isEmpty else { continue }
                groups.append(Group(key: key, title: key.replacingOccurrences(of: "_", with: " ").capitalized, symbol: "list.bullet", entries: entries(values)))
            }
        }
        let limits = [root["max"], root["max_players"], root["maxPlayers"], root["players"]["max"]]
        let maximum = limits.first { $0.double > 0 }?.int
        return UltraPlayerList(groups: groups, maximum: maximum)
    }
    static func entries(_ values: [JSON]) -> [Entry] {
        var seen = Set<String>()
        return values.compactMap { raw in
            let value = raw.attributes
            let name: String
            if case .string(let plain) = value { name = plain } else { name = UltraServers.text(value, ["name", "username", "player", "playerName", "player_name", "ip"]) }
            let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty, seen.insert(trimmed.lowercased()).inserted else { return nil }
            let uuid = UltraServers.text(value, ["uuid", "id"])
            var parts: [String] = []
            let reason = UltraServers.text(value, ["reason"]); if !reason.isEmpty { parts.append(reason) }
            let source = UltraServers.text(value, ["source"]); if !source.isEmpty { parts.append("von " + source) }
            if let lastSeen = UltraServers.firstDate(value, ["last_seen", "lastSeen", "last_played", "lastPlayed", "created", "created_at"]) { parts.append(lastSeen.formatted(date: .abbreviated, time: .shortened)) }
            return Entry(name: trimmed, uuid: uuid == trimmed ? "" : uuid, detail: parts.joined(separator: " · "))
        }
    }
    var online: [Entry] { groups.first { $0.key == "online" }?.entries ?? [] }
    var snapshot: PlayerSnapshot? {
        guard groups.contains(where: { $0.key == "online" }) else { return nil }
        return PlayerSnapshot(online: online.count, maximum: maximum, names: online.map(\.name), source: "UltraServers Player Manager")
    }
}

/// Vanilla server commands behind the Player Manager buttons. Every name is validated before it reaches the console.
enum UltraPlayerAction: String, CaseIterable, Identifiable {
    case op, deop, whitelistAdd, whitelistRemove, kick, ban, pardon, banIP, pardonIP, kill, clear
    var id: String { rawValue }
    var title: String {
        switch self {
        case .op: return "Zum Operator machen"
        case .deop: return "Operator entfernen"
        case .whitelistAdd: return "Zur Whitelist hinzufügen"
        case .whitelistRemove: return "Von der Whitelist entfernen"
        case .kick: return "Kicken"
        case .ban: return "Bannen"
        case .pardon: return "Entbannen"
        case .banIP: return "IP bannen"
        case .pardonIP: return "IP entbannen"
        case .kill: return "Töten (Respawn am Spawn)"
        case .clear: return "Inventar leeren"
        }
    }
    var symbol: String {
        switch self {
        case .op: return "crown"
        case .deop: return "crown.fill"
        case .whitelistAdd: return "checkmark.shield"
        case .whitelistRemove: return "xmark.shield"
        case .kick: return "figure.walk.departure"
        case .ban: return "nosign"
        case .pardon: return "arrow.uturn.backward.circle"
        case .banIP: return "network.slash"
        case .pardonIP: return "network"
        case .kill: return "bolt.heart"
        case .clear: return "trash"
        }
    }
    var destructive: Bool { [.kick, .ban, .banIP, .kill, .clear, .deop].contains(self) }
    var takesReason: Bool { [.kick, .ban, .banIP].contains(self) }
    /// Actions that make sense for an entry in the given list.
    static func actions(for group: String) -> [UltraPlayerAction] {
        switch group {
        case "online": return [.kick, .op, .deop, .whitelistAdd, .kill, .clear, .ban, .banIP]
        case "ops": return [.deop]
        case "whitelist": return [.whitelistRemove, .op]
        case "banned": return [.pardon]
        case "banned_ips": return [.pardonIP]
        default: return [.op, .whitelistAdd, .ban, .pardon]
        }
    }
    func command(player: String, reason: String = "") throws -> String {
        let target = player.trimmingCharacters(in: .whitespacesAndNewlines)
        let isIP = target.range(of: #"^[0-9a-fA-F:.]{3,45}$"#, options: .regularExpression) != nil && (target.contains(".") || target.contains(":"))
        guard PlayerCommands.validName(target) || ((self == .banIP || self == .pardonIP) && isIP) else { throw PanelError(message: "Dieser Spielername kann nicht sicher in einen Befehl eingesetzt werden.") }
        let cleanReason = reason.trimmingCharacters(in: .whitespacesAndNewlines)
        guard cleanReason.count <= 250, !cleanReason.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) || CharacterSet.newlines.contains($0) }) else { throw PanelError(message: "Grund ohne Zeilenumbrüche, maximal 250 Zeichen.") }
        let suffix = takesReason && !cleanReason.isEmpty ? " " + cleanReason : ""
        switch self {
        case .op: return "op \(target)"
        case .deop: return "deop \(target)"
        case .whitelistAdd: return "whitelist add \(target)"
        case .whitelistRemove: return "whitelist remove \(target)"
        case .kick: return "kick \(target)" + suffix
        case .ban: return "ban \(target)" + suffix
        case .pardon: return "pardon \(target)"
        case .banIP: return "ban-ip \(target)" + suffix
        case .pardonIP: return "pardon-ip \(target)"
        case .kill: return "kill \(target)"
        case .clear: return "clear \(target)"
        }
    }
}

// MARK: - Subdomains, reverse proxies, installers

struct UltraSubdomains: Equatable {
    struct Domain: Identifiable, Equatable, Hashable { let id: String; let name: String }
    struct Record: Identifiable, Equatable { let id: String; let host: String; let detail: String; let raw: JSON; static func == (l: Record, r: Record) -> Bool { l.id == r.id && l.host == r.host } }
    var domains: [Domain]
    var records: [Record]

    static func decode(_ json: JSON) -> UltraSubdomains {
        let domainRows = UltraServers.records(json, keys: ["domains", "available_domains", "availableDomains"], fallback: false)
        let domains: [Domain] = domainRows.compactMap { row in
            let id = UltraServers.text(row, ["id", "domain_id", "domainId"])
            let name = UltraServers.text(row, ["domain", "name", "zone"])
            return id.isEmpty && name.isEmpty ? nil : Domain(id: id.isEmpty ? name : id, name: name.isEmpty ? id : name)
        }
        let recordRows = UltraServers.records(json, keys: ["subdomains", "records", "subdomain"])
        let names = Dictionary(domains.map { ($0.id, $0.name) }, uniquingKeysWith: { a, _ in a })
        let records: [Record] = recordRows.compactMap { row in
            let id = UltraServers.text(row, ["id", "uuid", "subdomain_id"])
            let sub = UltraServers.text(row, ["subdomain", "name", "record", "host"])
            let domainID = UltraServers.text(row, ["domain_id", "domainId"])
            let domain = UltraServers.text(row, ["domain", "zone"]).isEmpty ? (names[domainID] ?? "") : UltraServers.text(row, ["domain", "zone"])
            let full = UltraServers.text(row, ["full_domain", "fullDomain", "fqdn", "hostname"])
            let host = !full.isEmpty ? full : sub.isEmpty ? domain : domain.isEmpty || sub.hasSuffix(domain) ? sub : sub + "." + domain
            guard !host.isEmpty else { return nil }
            let type = UltraServers.text(row, ["record_type", "type"])
            let port = UltraServers.text(row, ["port"])
            return Record(id: id, host: host, detail: [type, port.isEmpty ? "" : "Port " + port].filter { !$0.isEmpty }.joined(separator: " · "), raw: row)
        }
        return UltraSubdomains(domains: domains, records: records)
    }
    static func validLabel(_ label: String) -> Bool { label.range(of: #"^[a-z0-9]([a-z0-9-]{0,30}[a-z0-9])?$"#, options: .regularExpression) != nil }
}

struct UltraProxy: Identifiable, Equatable {
    let id: String
    let domain: String
    let target: String
    let ssl: Bool
    let raw: JSON
    static func == (l: UltraProxy, r: UltraProxy) -> Bool { l.id == r.id && l.domain == r.domain && l.ssl == r.ssl }
    static func decode(_ json: JSON) -> [UltraProxy] {
        UltraServers.records(json, keys: ["proxies", "reverse_proxies", "proxy"]).compactMap { row in
            let domain = UltraServers.text(row, ["domain", "hostname", "host", "name"])
            guard !domain.isEmpty else { return nil }
            let ip = UltraServers.text(row, ["ip", "allocation_ip"])
            let port = UltraServers.text(row, ["port", "allocation_port"])
            let allocation = row["allocation"].attributes
            let allocationID = UltraServers.text(row, ["allocation_id", "allocationId"])
            var target = ""
            if !port.isEmpty { target = ip.isEmpty ? "Port " + port : ip + ":" + port }
            else if allocation != .null { target = UltraServers.text(allocation, ["ip_alias", "ip"]) + ":" + allocation["port"].string }
            else if !allocationID.isEmpty { target = "Allocation " + allocationID }
            var ssl = false
            for key in ["ssl", "ssl_enabled", "sslEnabled"] where row[key].bool || row[key].int == 1 { ssl = true }
            return UltraProxy(id: UltraServers.text(row, ["id", "uuid"]), domain: domain, target: target, ssl: ssl, raw: row)
        }
    }
    static func validDomain(_ domain: String) -> Bool {
        domain.count <= 253 && domain.range(of: #"^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$"#, options: .regularExpression) != nil
    }
}

/// One entry of the plugin or modpack installer.
struct UltraCatalogItem: Identifiable, Equatable {
    let id: String
    let name: String
    let summary: String
    let author: String
    let downloads: Int
    let icon: URL?
    let provider: String
    let raw: JSON
    static func == (l: UltraCatalogItem, r: UltraCatalogItem) -> Bool { l.id == r.id && l.name == r.name && l.provider == r.provider }
    static func decode(_ json: JSON) -> [UltraCatalogItem] {
        UltraServers.records(json, keys: ["plugins", "modpacks", "results", "hits", "items", "projects"]).compactMap { row in
            let id = UltraServers.text(row, ["id", "project_id", "projectId", "slug", "uuid"])
            let name = UltraServers.text(row, ["name", "title", "displayName"])
            guard !id.isEmpty || !name.isEmpty else { return nil }
            let iconText = UltraServers.text(row, ["icon_url", "iconUrl", "icon", "logo", "thumbnail"])
            var icon: URL? = nil
            if let url = URL(string: iconText), url.scheme == "https" { icon = url }
            var author = row["author"].object.isEmpty ? row["author"].string : UltraServers.text(row["author"], ["name", "username"])
            if author.isEmpty { author = UltraServers.text(row, ["authors", "owner"]) }
            let downloads = max(row["downloads"].int, row["downloadCount"].int)
            let summary = UltraServers.text(row, ["description", "summary", "tag", "short_description"])
            let provider = UltraServers.text(row, ["provider", "source", "platform"])
            return UltraCatalogItem(id: id.isEmpty ? name : id, name: name.isEmpty ? id : name, summary: summary, author: author, downloads: downloads, icon: icon, provider: provider, raw: row)
        }
    }
}

struct UltraCatalogVersion: Identifiable, Equatable {
    let id: String
    let name: String
    let detail: String
    let raw: JSON
    static func == (l: UltraCatalogVersion, r: UltraCatalogVersion) -> Bool { l.id == r.id && l.name == r.name }
    static func decode(_ json: JSON) -> [UltraCatalogVersion] {
        UltraServers.records(json, keys: ["versions", "files", "releases"]).compactMap { row in
            if case .string(let plain) = row { return plain.isEmpty ? nil : UltraCatalogVersion(id: plain, name: plain, detail: "", raw: row) }
            let id = UltraServers.text(row, ["id", "version_id", "versionId", "file_id", "fileId", "version", "name"])
            let name = UltraServers.text(row, ["name", "version_number", "versionNumber", "version", "displayName", "fileName"])
            guard !id.isEmpty else { return nil }
            let games = (row["game_versions"].array.isEmpty ? row["gameVersions"].array : row["game_versions"].array).map(\.string).filter { !$0.isEmpty }
            let loaders = row["loaders"].array.map(\.string).filter { !$0.isEmpty }
            let date = UltraServers.firstDate(row, ["date_published", "datePublished", "released", "fileDate", "created_at"])
            var parts: [String] = []
            if !games.isEmpty { parts.append(games.prefix(4).joined(separator: ", ") + (games.count > 4 ? " …" : "")) }
            if !loaders.isEmpty { parts.append(loaders.joined(separator: ", ")) }
            if let date { parts.append(date.formatted(date: .abbreviated, time: .omitted)) }
            return UltraCatalogVersion(id: id, name: name.isEmpty ? id : name, detail: parts.joined(separator: " · "), raw: row)
        }
    }
}
