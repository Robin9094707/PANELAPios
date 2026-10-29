import Foundation
import Combine
import CryptoKit

enum AddonProvider: String, Codable, CaseIterable, Identifiable {
    case modrinth, spigot, curseforge, hangar, geysermc, github
    var id: String { rawValue }
    var title: String { switch self { case .modrinth: return "Modrinth"; case .spigot: return "Spigot"; case .curseforge: return "CurseForge"; case .hangar: return "Hangar"; case .geysermc: return "GeyserMC"; case .github: return "GitHub" } }
    /// Providers offered for this kind of JAR; Spigot and Hangar only host server plugins.
    static func available(for kind: AddonKind) -> [AddonProvider] { kind == .plugin ? allCases : [.modrinth, .curseforge, .geysermc, .github] }
    var idPattern: String { switch self { case .modrinth: return #"^[A-Za-z0-9_-]{1,100}$"#; case .hangar: return #"^[A-Za-z0-9_.-]{1,100}$"#; case .geysermc: return #"^(geyser|floodgate)$"#; case .github: return #"^[A-Za-z0-9_.-]{1,39}/[A-Za-z0-9_.-]{1,100}$"#; default: return #"^[0-9]{1,15}$"# } }
}
enum AddonKind: String, Codable { case plugin, mod
    var directory: String { self == .plugin ? "/plugins" : "/mods" }
}
struct AddonTarget: Codable, Equatable {
    var game = ""
    var loader = "paper"
    var beta = false
    var valid: Bool { game.range(of: #"^\d+\.\d+(?:\.\d+)?(?:[-.][A-Za-z0-9.-]+)?$"#, options: .regularExpression) != nil }
    static let loaders = ["paper", "purpur", "spigot", "bukkit", "fabric", "quilt", "forge", "neoforge"]
    var compatibleLoaders: [String] {
        switch loader { case "paper", "purpur": return [loader, "paper", "spigot", "bukkit"].reduce(into: []) { if !$0.contains($1) { $0.append($1) } }; case "spigot": return ["spigot", "bukkit"]; default: return [loader] }
    }
    static func detect(_ text: String) -> String? {
        for pattern in [#"(?i)\(MC:\s*(\d+\.\d+(?:\.\d+)?)\)"#, #"(?i)Starting minecraft server version\s+(\d+\.\d+(?:\.\d+)?)"#] {
            let re = try! NSRegularExpression(pattern: pattern)
            if let m = re.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)), let range = Range(m.range(at: 1), in: text) { return String(text[range]) }
        }; return nil
    }
}
struct AddonLink: Codable, Equatable { var provider: AddonProvider; var project: String }
struct AddonFingerprint: Codable {
    var sha1: String
    var sha256: String
    var curse: UInt32
    static func make(_ data: Data) -> Self {
        Self(sha1: Insecure.SHA1.hash(data: data).map { String(format: "%02x", $0) }.joined(), sha256: PluginArchive.digest(data), curse: murmur(data))
    }
    /// CurseForge hashes only non-whitespace bytes with MurmurHash2, seed 1.
    static func murmur(_ data: Data) -> UInt32 {
        let bytes = data.filter { ![9, 10, 13, 32].contains($0) }
        let m: UInt32 = 0x5bd1e995; var h = UInt32(bytes.count) ^ 1, i = 0
        while i + 4 <= bytes.count {
            var k = UInt32(bytes[i]) | UInt32(bytes[i+1]) << 8 | UInt32(bytes[i+2]) << 16 | UInt32(bytes[i+3]) << 24
            k = k &* m; k ^= k >> 24; k = k &* m
            h = (h &* m) ^ k; i += 4
        }
        if bytes.count - i >= 3 { h ^= UInt32(bytes[i+2]) << 16 }
        if bytes.count - i >= 2 { h ^= UInt32(bytes[i+1]) << 8 }
        if bytes.count - i >= 1 { h ^= UInt32(bytes[i]); h = h &* m }
        h ^= h >> 13; h = h &* m; h ^= h >> 15; return h
    }
}
struct AddonInventory: Codable, Identifiable {
    var id: String { filename }
    var filename: String
    var signature: String
    var name: String
    var version: String
    var fingerprint: AddonFingerprint
    var identity: String?
}
struct AddonRelease: Codable, Identifiable {
    var id: String
    var title: String
    var provider: AddonProvider
    var version: String
    var filename: String
    var download: String?
    var website: String
    var sha1: String?
    var sha512: String?
    var sha256: String? = nil
    var notes: String
    var compatibility: Bool
    var published: Date?
    var dependencies: [String]
}
struct AddonStatus: Codable {
    var text: String
    var release: AddonRelease?
    var checked = Date()
}
struct AddonSearchResult: Identifiable {
    var id: String
    var name: String
    var provider: AddonProvider
    var website: String
}
struct AddonCache: Codable {
    var target = AddonTarget()
    var links: [String: AddonLink] = [:]
    var inventory: [String: AddonInventory] = [:]
    var statuses: [String: AddonStatus] = [:]
}

/// Marketplace credentials are never attached to panel, CDN or redirect requests.
final class MarketplaceRedirect: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        guard request.url?.scheme == "https" else { completionHandler(nil); return }
        var next = request
        if request.url?.host != task.originalRequest?.url?.host { next.setValue(nil, forHTTPHeaderField: "x-api-key") }
        next.setValue(nil, forHTTPHeaderField: "Authorization"); completionHandler(next)
    }
}
final class MarketplaceClient {
    static let curseKeyID = UUID(uuidString: "E70137E0-82CD-48F2-BBF7-6A9CA6B95301")!
    let session: URLSession
    init(session: URLSession? = nil) {
        let config = URLSessionConfiguration.ephemeral; config.timeoutIntervalForRequest = 30; config.timeoutIntervalForResource = 180
        self.session = session ?? URLSession(configuration: config, delegate: MarketplaceRedirect(), delegateQueue: nil)
    }
    func json(_ host: String, _ path: String, query: [String: String] = [:], body: JSON? = nil) async throws -> JSON {
        guard ["api.modrinth.com", "api.spiget.org", "api.curseforge.com", "hangar.papermc.io", "download.geysermc.org", "api.github.com"].contains(host), !path.contains("..") else { throw PanelError(message: "Ungültiger Anbieter.") }
        var c = URLComponents(); c.scheme = "https"; c.host = host; c.path = path
        c.queryItems = query.sorted { $0.key < $1.key }.map { URLQueryItem(name: $0.key, value: $0.value) }
        var request = URLRequest(url: c.url!); request.setValue("Robin9094707/SimplexPanel/2.2.0 (github.com/Robin9094707/PANELAPios)", forHTTPHeaderField: "User-Agent")
        request.setValue(host == "api.github.com" ? "application/vnd.github+json" : "application/json", forHTTPHeaderField: "Accept")
        if host == "api.curseforge.com" {
            let key = (try? Vault.read(Self.curseKeyID)) ?? ""
            guard !key.isEmpty else { throw PanelError(message: "Für CurseForge zuerst einen eigenen API-Schlüssel in Anbieter-Einstellungen hinterlegen.") }
            request.setValue(key, forHTTPHeaderField: "x-api-key")
        }
        if let body { request.httpMethod = "POST"; request.httpBody = try JSONEncoder().encode(body); request.setValue("application/json", forHTTPHeaderField: "Content-Type") }
        let (data, response) = try await session.data(for: request)
        if (response as? HTTPURLResponse)?.statusCode == 404 { return .null }
        if (response as? HTTPURLResponse)?.statusCode == 429 || (host == "api.github.com" && (response as? HTTPURLResponse)?.statusCode == 403) { throw PanelError(message: "Anbieter-Limit erreicht. Bitte später erneut prüfen.") }
        try PanelAPI.validate(data, response: response)
        guard data.count <= 8 * 1024 * 1024 else { throw PanelError(message: "Anbieterantwort zu groß.") }
        return try JSONDecoder().decode(JSON.self, from: data)
    }
    func search(_ query: String, provider: AddonProvider, kind: AddonKind) async throws -> [AddonSearchResult] {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines); guard !q.isEmpty else { return [] }
        switch provider {
        case .modrinth:
            let result = try await json("api.modrinth.com", "/v2/search", query: ["query": q, "limit": "15", "facets": "[[\"project_type:\(kind == .plugin ? "plugin" : "mod")\"]]"])
            return result["hits"].array.map { AddonSearchResult(id: $0["project_id"].string, name: $0["title"].string, provider: provider, website: "https://modrinth.com/project/" + $0["project_id"].string) }
        case .spigot:
            guard kind == .plugin else { return [] }
            let result = try await json("api.spiget.org", "/v2/search/resources/" + q, query: ["size": "15", "field": "name"])
            return result.array.map { AddonSearchResult(id: $0["id"].string, name: $0["name"].string, provider: provider, website: "https://www.spigotmc.org/resources/" + $0["id"].string + "/") }
        case .curseforge:
            let result = try await json("api.curseforge.com", "/v1/mods/search", query: ["gameId": "432", "classId": kind == .plugin ? "5" : "6", "searchFilter": q, "pageSize": "15"])
            return result["data"].array.map { AddonSearchResult(id: $0["id"].string, name: $0["name"].string, provider: provider, website: $0["links"]["websiteUrl"].string) }
        case .hangar:
            guard kind == .plugin else { return [] }
            let result = try await json("hangar.papermc.io", "/api/v1/projects", query: ["q": q, "limit": "15", "offset": "0"])
            return result["result"].array.map { AddonSearchResult(id: $0["namespace"]["slug"].string, name: $0["name"].string, provider: provider, website: "https://hangar.papermc.io/" + $0["namespace"]["owner"].string + "/" + $0["namespace"]["slug"].string) }
        case .github:
            let result = try await json("api.github.com", "/search/repositories", query: ["q": q + " in:name", "per_page": "10"])
            return result["items"].array.map { AddonSearchResult(id: $0["full_name"].string, name: $0["full_name"].string, provider: provider, website: $0["html_url"].string) }
        case .geysermc:
            return ["geyser", "floodgate"].filter { $0.contains(q.lowercased()) || q.lowercased().contains($0) }.map { AddonSearchResult(id: $0, name: $0 == "geyser" ? "Geyser" : "Floodgate", provider: provider, website: "https://geysermc.org/download") }
        }
    }
    /// Geyser and Floodgate publish official builds with SHA-256 on download.geysermc.org.
    static func geyserProject(_ name: String) -> String? {
        let value = name.lowercased()
        if value.hasPrefix("floodgate") { return "floodgate" }
        if value == "geyser" || value.hasPrefix("geyser-") { return "geyser" }
        return nil
    }
    static func geyserPlatform(_ target: AddonTarget) -> String {
        if ["paper", "purpur", "spigot", "bukkit"].contains(target.loader) { return "spigot" }
        return ["neoforge", "forge"].contains(target.loader) ? "neoforge" : "fabric"
    }
    /// Exact (case-insensitive) name matches are only suggestions; the admin confirms the binding once.
    func suggest(_ item: AddonInventory, kind: AddonKind) async -> AddonLink? {
        if let project = Self.geyserProject(item.name) { return AddonLink(provider: .geysermc, project: project) }
        let name = item.name.lowercased()
        for provider in AddonProvider.available(for: kind) where [.modrinth, .hangar, .spigot].contains(provider) {
            guard let results = try? await search(item.name, provider: provider, kind: kind) else { continue }
            if let match = results.first(where: { $0.name.lowercased() == name }) { return AddonLink(provider: provider, project: match.id) }
        }
        return nil
    }
    static func newer(_ remote: String, than local: String) -> Bool? {
        func normalize(_ s: String) -> String { s.trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: #"^[vV](?=\d)"#, with: "", options: .regularExpression) }
        let a = normalize(remote), b = normalize(local)
        if a == b { return false }
        let pattern = #"^\d+(?:\.\d+){0,5}$"#
        guard a.range(of: pattern, options: .regularExpression) != nil, b.range(of: pattern, options: .regularExpression) != nil else { return nil }
        return a.compare(b, options: .numeric) == .orderedDescending
    }
    static func modrinthRelease(_ version: JSON, target: AddonTarget) -> AddonRelease? {
        let files = version["files"].array.filter { $0["filename"].string.lowercased().hasSuffix(".jar") }
        guard let file = files.first(where: { $0["primary"].bool }) ?? (files.count == 1 ? files.first : nil),
              version["game_versions"].array.map(\.string).contains(target.game),
              !Set(version["loaders"].array.map(\.string)).isDisjoint(with: target.compatibleLoaders),
              !["client_only", "singleplayer_only", "client_only_server_optional"].contains(version["environment"].string) else { return nil }
        let dependencies = version["dependencies"].array.filter { $0["dependency_type"].string == "required" }.map { "Erforderlich: " + ($0["project_id"].string.isEmpty ? $0["file_name"].string : $0["project_id"].string) }
        return AddonRelease(id: version["id"].string, title: version["name"].string, provider: .modrinth, version: version["version_number"].string, filename: file["filename"].string, download: file["url"].string, website: "https://modrinth.com/project/" + version["project_id"].string + "/version/" + version["id"].string, sha1: file["hashes"]["sha1"].string, sha512: file["hashes"]["sha512"].string, notes: String(version["changelog"].string.prefix(12000)), compatibility: true, published: ScheduleTime.parse( version["date_published"].string), dependencies: dependencies)
    }
    func latest(_ installed: AddonInventory, link: AddonLink?, target: AddonTarget) async throws -> (AddonLink?, AddonStatus) {
        guard target.valid else { return (link, AddonStatus(text: "Serverversion festlegen", release: nil)) }
        var mapping = link
        if mapping == nil, let project = Self.geyserProject(installed.name) { mapping = AddonLink(provider: .geysermc, project: project) }
        var exact: JSON = .null
        if mapping == nil || mapping?.provider == .modrinth {
            exact = try await json("api.modrinth.com", "/v2/version_file/" + installed.fingerprint.sha1, query: ["algorithm": "sha1"])
            if mapping == nil, !exact["project_id"].string.isEmpty { mapping = AddonLink(provider: .modrinth, project: exact["project_id"].string) }
        }
        if mapping == nil, !((try? Vault.read(Self.curseKeyID)) ?? "").isEmpty {
            let matches = try await json("api.curseforge.com", "/v1/fingerprints/432", body: .object(["fingerprints": .array([.number(Double(installed.fingerprint.curse))])]))
            // Fingerprint collisions are verified with the provider's SHA-1 before binding.
            if let file = matches["data"]["exactMatches"].array.map({ $0["file"] }).first(where: { file in file["hashes"].array.contains { $0["algo"].int == 1 && $0["value"].string.lowercased() == installed.fingerprint.sha1 } }) {
                mapping = AddonLink(provider: .curseforge, project: file["modId"].string)
            }
        }
        guard let mapping else { return (nil, AddonStatus(text: "Quelle zuordnen", release: nil)) }
        switch mapping.provider {
        case .modrinth:
            let versions = try await json("api.modrinth.com", "/v2/project/" + mapping.project + "/version", query: ["loaders": JSON.array(target.compatibleLoaders.map(JSON.string)).pretty, "game_versions": JSON.array([.string(target.game)]).pretty])
            let eligible = versions.array.filter { target.beta || $0["version_type"].string == "release" }
            let releases = eligible.compactMap { Self.modrinthRelease($0, target: target) }.sorted { ($0.published ?? .distantPast) > ($1.published ?? .distantPast) }
            guard let release = releases.first else { return (mapping, AddonStatus(text: "Keine passende Server-Version", release: nil)) }
            let installedDate = ScheduleTime.parse( exact["date_published"].string)
            if release.id == exact["id"].string || release.sha1 == installed.fingerprint.sha1 || Self.newer(release.version, than: installed.version) == false || (installedDate != nil && release.published != nil && release.published! <= installedDate!) {
                return (mapping, AddonStatus(text: "Kein neueres passendes Update", release: nil))
            }
            return (mapping, AddonStatus(text: Self.newer(release.version, than: installed.version) == true || installedDate != nil ? "Update verfügbar" : "Neue Version prüfen", release: release))
        case .spigot:
            let resource = try await json("api.spiget.org", "/v2/resources/" + mapping.project)
            let version = try await json("api.spiget.org", "/v2/resources/" + mapping.project + "/versions/latest")
            guard !version["id"].string.isEmpty else { return (mapping, AddonStatus(text: "Ressource nicht verfügbar", release: nil)) }
            let number = version["name"].string
            guard Self.newer(number, than: installed.version) != false else { return (mapping, AddonStatus(text: "Kein neueres Update", release: nil)) }
            let restricted = resource["premium"].bool || resource["external"].bool || (!resource["file"]["type"].string.isEmpty && resource["file"]["type"].string != ".jar")
            let versions = resource["testedVersions"].array.map(\.string).joined(separator: ", ")
            let release = AddonRelease(id: version["id"].string, title: resource["name"].string, provider: .spigot, version: number, filename: installed.filename, download: restricted ? nil : "https://api.spiget.org/v2/resources/\(mapping.project)/download", website: "https://www.spigotmc.org/resources/\(mapping.project)/", notes: "Spigot nennt getestete Versionen pro Projekt, nicht zuverlässig pro Download: \(versions). Prüfe die konkrete Version auf der Anbieterseite. Kostenlose JARs lädt die App über das Spiget-CDN ohne Spigot-Anmeldung; Premium-Downloads erfordern deinen Kauf (in Safari laden und als Datei auswählen).", compatibility: false, published: Date(timeIntervalSince1970: version["releaseDate"].double), dependencies: [])
            return (mapping, AddonStatus(text: "Update gefunden · Kompatibilität prüfen", release: release))
        case .curseforge:
            var query = ["gameVersion": target.game, "pageSize": "50"]
            let loaderIDs = ["forge": "1", "fabric": "4", "quilt": "5", "neoforge": "6"]
            if let loader = loaderIDs[target.loader] { query["modLoaderType"] = loader }
            let project = try await json("api.curseforge.com", "/v1/mods/" + mapping.project)["data"]
            let files = try await json("api.curseforge.com", "/v1/mods/" + mapping.project + "/files", query: query)["data"].array.filter { file in
                file["isAvailable"].bool && file["gameVersions"].array.map(\.string).contains(target.game) && (target.beta || file["releaseType"].int == 1) && file["fileName"].string.lowercased().hasSuffix(".jar") && (loaderIDs[target.loader] == nil || file["gameVersions"].array.map { $0.string.lowercased() }.contains(target.loader))
            }.sorted { $0["fileDate"].string > $1["fileDate"].string }
            guard let file = files.first else { return (mapping, AddonStatus(text: "Keine passende Server-Version", release: nil)) }
            let sha = file["hashes"].array.first { $0["algo"].int == 1 }?["value"].string
            if sha?.lowercased() == installed.fingerprint.sha1 { return (mapping, AddonStatus(text: "Aktuell", release: nil)) }
            let website = project["links"]["websiteUrl"].string
            let direct = file["downloadUrl"].string
            let release = AddonRelease(id: file["id"].string, title: file["displayName"].string, provider: .curseforge, version: file["displayName"].string, filename: file["fileName"].string, download: direct.isEmpty ? nil : direct, website: website, sha1: sha, notes: "Minecraft-Version und Loader laut CurseForge. Mod-ID, echte Version und Server-Eignung werden vor der Installation aus der JAR geprüft.", compatibility: loaderIDs[target.loader] != nil, published: ScheduleTime.parse( file["fileDate"].string), dependencies: file["dependencies"].array.filter { $0["relationType"].int == 3 }.map { "Erforderlich: CurseForge " + $0["modId"].string })
            return (mapping, AddonStatus(text: "Neue Datei prüfen", release: release))
        case .hangar:
            let versions = try await json("hangar.papermc.io", "/api/v1/projects/" + mapping.project + "/versions", query: ["limit": "25", "offset": "0", "platform": "PAPER"])
            let eligible = versions["result"].array.filter { version in
                (target.beta || version["channel"]["name"].string.lowercased() == "release") && version["platformDependencies"]["PAPER"].array.map(\.string).contains(target.game)
            }
            guard let version = eligible.first else { return (mapping, AddonStatus(text: "Keine passende Server-Version", release: nil)) }
            let file = version["downloads"]["PAPER"]
            let sha = file["fileInfo"]["sha256Hash"].string.lowercased()
            if !sha.isEmpty && sha == installed.fingerprint.sha256.lowercased() { return (mapping, AddonStatus(text: "Aktuell", release: nil)) }
            if Self.newer(version["name"].string, than: installed.version) == false { return (mapping, AddonStatus(text: "Kein neueres passendes Update", release: nil)) }
            let direct = file["downloadUrl"].string
            let dependencies = version["pluginDependencies"]["PAPER"].array.filter { $0["required"].bool }.map { "Erforderlich: " + $0["name"].string }
            let release = AddonRelease(id: version["id"].string, title: version["name"].string, provider: .hangar, version: version["name"].string, filename: file["fileInfo"]["name"].string.isEmpty ? installed.filename : file["fileInfo"]["name"].string, download: direct.isEmpty ? nil : direct, website: "https://hangar.papermc.io/" + version["author"].string + "/" + mapping.project, sha1: nil, sha512: nil, sha256: sha.isEmpty ? nil : sha, notes: String(version["description"].string.prefix(12000)), compatibility: true, published: ScheduleTime.parse(version["createdAt"].string), dependencies: dependencies)
            return (mapping, AddonStatus(text: "Update verfügbar", release: release))
        case .github:
            // Releases with exactly one JAR (or one matching the installed name); GitHub publishes SHA-256 digests per asset.
            let releases = try await json("api.github.com", "/repos/" + mapping.project + "/releases", query: ["per_page": "10"])
            guard let release = releases.array.first(where: { !$0["draft"].bool && (target.beta || !$0["prerelease"].bool) }) else { return (mapping, AddonStatus(text: "Kein GitHub-Release", release: nil)) }
            let jars = release["assets"].array.filter { $0["name"].string.lowercased().hasSuffix(".jar") && !$0["name"].string.lowercased().contains("sources") && !$0["name"].string.lowercased().contains("javadoc") }
            let stem = installed.name.lowercased().filter { $0.isLetter || $0.isNumber }
            let matching = jars.filter { $0["name"].string.lowercased().filter { $0.isLetter || $0.isNumber }.hasPrefix(stem) }
            guard let asset = matching.count == 1 ? matching.first : jars.count == 1 ? jars.first : nil else { return (mapping, AddonStatus(text: jars.isEmpty ? "Release ohne JAR" : "Mehrere JARs im Release – auf GitHub wählen", release: nil)) }
            let digest = asset["digest"].string.lowercased(), sha = digest.hasPrefix("sha256:") ? String(digest.dropFirst(7)) : ""
            if !sha.isEmpty && sha == installed.fingerprint.sha256.lowercased() { return (mapping, AddonStatus(text: "Aktuell", release: nil)) }
            let version = release["tag_name"].string
            if Self.newer(version, than: installed.version) == false { return (mapping, AddonStatus(text: "Kein neueres Release", release: nil)) }
            let item = AddonRelease(id: release["id"].string, title: release["name"].string.isEmpty ? version : release["name"].string, provider: .github, version: version, filename: asset["name"].string, download: asset["browser_download_url"].string, website: release["html_url"].string, sha1: nil, sha512: nil, sha256: sha.isEmpty ? nil : sha, notes: String(release["body"].string.prefix(12000)), compatibility: false, published: ScheduleTime.parse(release["published_at"].string), dependencies: [])
            return (mapping, AddonStatus(text: "Neues Release · Kompatibilität prüfen", release: item))
        case .geysermc:
            let platform = Self.geyserPlatform(target)
            let build = try await json("download.geysermc.org", "/v2/projects/" + mapping.project + "/versions/latest/builds/latest")
            let file = build["downloads"][platform], sha = file["sha256"].string.lowercased()
            guard !sha.isEmpty else { return (mapping, AddonStatus(text: "Kein GeyserMC-Build für \(platform)", release: nil)) }
            let label = build["version"].string + " · Build " + build["build"].string
            if sha == installed.fingerprint.sha256.lowercased() { return (mapping, AddonStatus(text: "Aktuell · " + label, release: nil)) }
            let changes = build["changes"].array.prefix(8).map { "• " + $0["summary"].string }.joined(separator: "\n")
            let release = AddonRelease(id: build["build"].string, title: (mapping.project == "geyser" ? "Geyser " : "Floodgate ") + label, provider: .geysermc, version: label, filename: installed.filename, download: "https://download.geysermc.org/v2/projects/\(mapping.project)/versions/\(build["version"].string)/builds/\(build["build"].string)/downloads/\(platform)", website: "https://geysermc.org/download", sha1: nil, sha512: nil, sha256: sha, notes: changes.isEmpty ? "Offizieller GeyserMC-Build. Die Prüfsumme wird nach dem Download verglichen." : changes, compatibility: true, published: ScheduleTime.parse(build["time"].string), dependencies: [])
            return (mapping, AddonStatus(text: "Update verfügbar · Build " + build["build"].string, release: release))
        }
    }
    func download(_ release: AddonRelease) async throws -> URL {
        guard let text = release.download, let url = URL(string: text), url.scheme == "https", url.user == nil, url.password == nil else { throw PanelError(message: "Download nur über die Anbieterseite verfügbar.") }
        // Durable unsigned background request: no panel token or marketplace API key.
        let name = URL(fileURLWithPath: release.filename).lastPathComponent
        guard name.lowercased().hasSuffix(".jar"), !name.contains("\\") else { throw PanelError(message: "Anbieter hat keinen JAR-Dateinamen geliefert.") }
        let file = try await TransferManager.shared.downloadAndWait(url.absoluteString, filename: name, context: release.provider.title + " · Update-Download (noch nicht installiert)")
        guard let size = try file.resourceValues(forKeys: [.fileSizeKey]).fileSize, size <= 128 * 1024 * 1024 else { throw PanelError(message: "JAR größer als 128 MiB.") }
        let data = try Data(contentsOf: file, options: .mappedIfSafe)
        try Self.verify(data, release: release)
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("addon-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let destination = directory.appendingPathComponent(name); try data.write(to: destination); return destination
    }
    static func verify(_ data: Data, release: AddonRelease) throws {
        guard data.count >= 22, data.count <= 128 * 1024 * 1024, data.prefix(2) == Data([0x50, 0x4b]) else { throw PanelError(message: "Keine gültige JAR erhalten – möglicherweise eine Anmelde- oder Schutzseite. Nutze die Anbieterseite.") }
        if let expected = release.sha512, !expected.isEmpty {
            guard SHA512.hash(data: data).map({ String(format: "%02x", $0) }).joined() == expected.lowercased() else { throw PanelError(message: "SHA-512 des Downloads stimmt nicht.") }
        } else if let expected = release.sha256, !expected.isEmpty {
            guard SHA256.hash(data: data).map({ String(format: "%02x", $0) }).joined() == expected.lowercased() else { throw PanelError(message: "SHA-256 des Downloads stimmt nicht.") }
        } else if let expected = release.sha1, !expected.isEmpty {
            guard Insecure.SHA1.hash(data: data).map({ String(format: "%02x", $0) }).joined() == expected.lowercased() else { throw PanelError(message: "SHA-1 des Downloads stimmt nicht.") }
        }
    }
}

@MainActor final class AddonUpdateModel: ObservableObject {
    @Published var cache: AddonCache
    @Published private(set) var rows: [AddonInventory] = []
    @Published private(set) var busy = false
    @Published private(set) var progress = ""
    @Published var error: String?
    /// Name-based source proposals for unmapped JARs; never bound without the admin's tap.
    @Published var suggestions: [String: AddonLink] = [:]
    let kind: AddonKind
    let scope: String
    let client: MarketplaceClient
    private var lastScan = Date.distantPast { didSet { UserDefaults.standard.set(lastScan.timeIntervalSince1970, forKey: scope + ".lastScan") } }
    init(api: PanelAPI, server: Server, kind: AddonKind, client: MarketplaceClient = MarketplaceClient()) {
        self.kind = kind; self.client = client
        scope = "addons.v1." + PluginArchive.digest(Data((api.base.absoluteString + "|" + server.id + "|" + kind.rawValue).utf8))
        cache = (try? JSONDecoder().decode(AddonCache.self, from: UserDefaults.standard.data(forKey: scope) ?? Data())) ?? AddonCache()
        if UserDefaults.standard.data(forKey: scope) == nil { cache.target.loader = kind == .plugin ? "paper" : "fabric" }
        rows = cache.inventory.values.sorted { $0.filename.localizedStandardCompare($1.filename) == .orderedAscending }
        // Unchanged JARs are not rescanned when the update center is reopened shortly after the last check.
        let stored = UserDefaults.standard.double(forKey: scope + ".lastScan"); if stored > 0 { lastScan = Date(timeIntervalSince1970: stored) }
    }
    func save() { UserDefaults.standard.set(try? JSONEncoder().encode(cache), forKey: scope) }
    func setTarget(_ target: AddonTarget) { cache.target = target; cache.statuses = [:]; lastScan = .distantPast; save() }
    func bind(_ row: AddonInventory, provider: AddonProvider, project: String) throws {
        let id = project.trimmingCharacters(in: .whitespacesAndNewlines)
        guard id.range(of: provider.idPattern, options: .regularExpression) != nil else { throw PanelError(message: provider == .geysermc ? "GeyserMC-Projekt: geyser oder floodgate." : "Bitte eine gültige Projekt-ID oder einen Slug eingeben.") }
        cache.links[row.filename] = AddonLink(provider: provider, project: id); cache.statuses[row.filename] = nil; lastScan = .distantPast; save()
    }
    func accept(_ row: AddonInventory) throws {
        guard let proposal = suggestions[row.filename] else { return }
        try bind(row, provider: proposal.provider, project: proposal.project); suggestions[row.filename] = nil
    }
    func detectTarget(api: PanelAPI, server: Server) async -> String? {
        if let data = try? await api.fileData(server: server.id, path: "/version_history.json", limit: 512 * 1024), let json = try? JSONDecoder().decode(JSON.self, from: data) {
            let raw = json["currentVersion"].string
            if let version = AddonTarget.detect(raw) { return version }
            // Paper's currentVersion also uses the canonical MC-version-build format.
            if let range = raw.range(of: #"^\d+\.\d+(?:\.\d+)?(?=-[0-9]+|$)"#, options: .regularExpression) { return String(raw[range]) }
        }
        let variables = (try? await api.list("servers/\(server.id)/startup")) ?? []
        for row in variables where ["MINECRAFT_VERSION", "MC_VERSION"].contains(row["env_variable"].string) {
            let value = row["server_value"].string; var target = cache.target; target.game = value
            if target.valid { return value }
        }
        return nil
    }
    func scan(api: PanelAPI, server: Server, entries: [JSON], force: Bool = false) async {
        guard !busy, !api.demo, force || Date().timeIntervalSince(lastScan) > 900 else { return }
        busy = true
        if !cache.target.valid {
            if let game = await detectTarget(api: api, server: server) { cache.target.game = game; save() }
            else { busy = false; return }
        }
        defer { busy = false; progress = ""; save() }
        let jars = entries.filter { $0["is_file"].bool && $0["name"].string.hasSuffix(".jar") }
        let previousInventory = cache.inventory
        let names = Set(jars.map { $0["name"].string }); cache.inventory = cache.inventory.filter { names.contains($0.key) }; cache.statuses = cache.statuses.filter { names.contains($0.key) }
        for (index, entry) in jars.enumerated() {
            do {
                try Task.checkCancellation()
                let name = entry["name"].string; progress = "\(index + 1) / \(jars.count) · \(name)"
                let signature = PluginCatalog.key(server: server.id, directory: kind.directory, entry: entry)
                if cache.inventory[name]?.signature != signature {
                    let item: AddonInventory
                    if kind == .plugin {
                        await api.plugins.load(api: api, server: server.id, entry: entry, retry: force)
                        guard let descriptor = api.plugins.descriptor(server: server.id, entry: entry), let fingerprint = api.plugins.fingerprints[signature] else { throw PanelError(message: api.plugins.errors[signature] ?? "JAR-Metadaten nicht lesbar.") }
                        item = AddonInventory(filename: name, signature: signature, name: descriptor.name, version: descriptor.version, fingerprint: fingerprint, identity: descriptor.name + "\u{0}" + descriptor.main)
                    } else {
                        let data = try await api.fileData(server: server.id, path: pathJoin(kind.directory, name), limit: 128 * 1024 * 1024)
                        item = try await Task.detached(priority: .utility) { let descriptor = try ModArchive.descriptor(data); return AddonInventory(filename: name, signature: signature, name: descriptor.name, version: descriptor.version, fingerprint: .make(data), identity: descriptor.ids.joined(separator: ",") + "|" + descriptor.loader) }.value
                    }
                    if cache.links[name] == nil, let identity = item.identity {
                        let previous = previousInventory.values.filter { $0.identity == identity && !names.contains($0.filename) }
                        if previous.count == 1 { cache.links[name] = cache.links[previous[0].filename] }
                    }
                    cache.inventory[name] = item; cache.statuses[name] = nil
                }
                if let item = cache.inventory[name], force || cache.statuses[name] == nil || Date().timeIntervalSince(cache.statuses[name]!.checked) > 6 * 3600 {
                    let (link, status) = try await client.latest(item, link: cache.links[name], target: cache.target)
                    cache.links[name] = link; cache.statuses[name] = status
                    if link == nil, suggestions[name] == nil, let proposal = await client.suggest(item, kind: kind) { suggestions[name] = proposal }
                }
            } catch is CancellationError { return }
            catch {
                cache.statuses[entry["name"].string] = AddonStatus(text: error.localizedDescription, release: nil)
                if error.localizedDescription.contains("Anbieter-Limit") { self.error = error.localizedDescription; break }
            }
            rows = cache.inventory.values.sorted { $0.filename.localizedStandardCompare($1.filename) == .orderedAscending }
        }
        rows = cache.inventory.values.sorted { $0.filename.localizedStandardCompare($1.filename) == .orderedAscending }; lastScan = Date()
    }
}
