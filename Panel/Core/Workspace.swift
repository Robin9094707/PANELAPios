import SwiftUI

struct FolderPin: Identifiable, Codable {
    var id = UUID()
    let panel: String
    let serverID: String
    let serverName: String
    let path: String
}
struct CommandShortcut: Identifiable, Codable {
    var id = UUID()
    var name: String
    var command: String
}
@MainActor final class WorkspaceStore: ObservableObject {
    private let defaults: UserDefaults
    @Published var folders: [FolderPin] = []
    @Published var favorites: Set<String> = []
    @Published var shortcuts: [String: [CommandShortcut]] = [:]
    @Published var minecraft: [String: Bool] = [:]
    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        let d = defaults
        folders = (try? JSONDecoder().decode([FolderPin].self, from: d.data(forKey: "workspace.folders.v1") ?? Data())) ?? []
        favorites = Set(d.stringArray(forKey: "workspace.servers.v1") ?? [])
        shortcuts = (try? JSONDecoder().decode([String: [CommandShortcut]].self, from: d.data(forKey: "workspace.commands.v1") ?? Data())) ?? [:]
        minecraft = d.dictionary(forKey: "workspace.minecraft.v1") as? [String: Bool] ?? [:]
    }
    func key(_ api: PanelAPI, _ server: Server) -> String { api.base.absoluteString + "|" + server.id }
    func save() {
        let d = defaults
        d.set(try? JSONEncoder().encode(folders), forKey: "workspace.folders.v1")
        d.set(Array(favorites), forKey: "workspace.servers.v1")
        d.set(try? JSONEncoder().encode(shortcuts), forKey: "workspace.commands.v1")
        d.set(minecraft, forKey: "workspace.minecraft.v1")
    }
    func toggleServer(_ api: PanelAPI, _ server: Server) {
        let k = key(api, server); if favorites.contains(k) { favorites.remove(k) } else { favorites.insert(k) }; save()
    }
    func isPinned(_ api: PanelAPI, _ server: Server, _ path: String) -> Bool { folders.contains { $0.panel == api.base.absoluteString && $0.serverID == server.id && $0.path == path } }
    func toggleFolder(_ api: PanelAPI, _ server: Server, _ path: String) {
        if isPinned(api, server, path) { folders.removeAll { $0.panel == api.base.absoluteString && $0.serverID == server.id && $0.path == path } }
        else { folders.append(FolderPin(panel: api.base.absoluteString, serverID: server.id, serverName: server.name, path: path)) }; save()
    }
    func isMinecraft(_ api: PanelAPI, _ server: Server) -> Bool {
        if let value = minecraft[key(api, server)] { return value }
        let value = (server.name + " " + server.data["invocation"].string + " " + server.data["docker_image"].string).lowercased()
        return api.demo || ["minecraft", "paper", "java", "smp", "vanilla", "purpur", "spigot"].contains { value.contains($0) }
    }
    func setMinecraft(_ value: Bool, _ api: PanelAPI, _ server: Server) { minecraft[key(api, server)] = value; save() }
    func addShortcut(_ value: CommandShortcut, _ api: PanelAPI, _ server: Server) { shortcuts[key(api, server), default: []].append(value); save() }
    func removeShortcut(_ id: UUID, _ api: PanelAPI, _ server: Server) { shortcuts[key(api, server)]?.removeAll { $0.id == id }; save() }
}

struct PlayerSnapshot: Equatable {
    var online: Int
    var maximum: Int?
    var names: [String]
    var source: String
    var updated = Date()
    static func parseLog(_ line: String) -> PlayerSnapshot? {
        let cleaned = cleanConsole(line)
        let pattern = #"There are\s+(\d+)\s+of a max of\s+(\d+)\s+players? online\s*:?\s*(.*)$"#
        guard let regex = try? NSRegularExpression(pattern: pattern, options: .caseInsensitive), let match = regex.firstMatch(in: cleaned, range: NSRange(cleaned.startIndex..., in: cleaned)), let a = Range(match.range(at: 1), in: cleaned), let b = Range(match.range(at: 2), in: cleaned), let c = Range(match.range(at: 3), in: cleaned), let online = Int(cleaned[a]), let max = Int(cleaned[b]) else { return nil }
        let names = cleaned[c].split(separator: ",").map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }
        return PlayerSnapshot(online: online, maximum: max, names: names, source: "Konsolenbefehl list")
    }
    static func decode(_ json: JSON) -> PlayerSnapshot? {
        var value = json.attributes
        if value["data"] != .null { value = value["data"].attributes }
        if case .object = value["players"], value["players"]["online"] != .null { value = value["players"] }
        let list: [JSON]
        if case .array = value { list = value.array }
        else if value["players"] != .null { list = value["players"].array.isEmpty ? value["players"].rows : value["players"].array }
        else if value["online_players"] != .null { list = value["online_players"].array }
        else if value["sample"] != .null { list = value["sample"].array }
        else { list = [] }
        let names = list.compactMap { player -> String? in
            let p = player.attributes
            let name = p["name"].string.isEmpty ? (p["username"].string.isEmpty ? p.string : p["username"].string) : p["name"].string
            return name.isEmpty ? nil : name
        }
        let counts = [value["online"], value["count"], value["player_count"], value["online_count"]]
        let count = counts.first { Int($0.string) != nil }
        let isList: Bool
        switch value { case .array: isList = true; default: if case .array = value["players"] { isList = true } else { isList = false } }
        guard count != nil || !names.isEmpty || isList else { return nil }
        let limits = [value["max"], value["max_players"], value["maximum"]]
        let max = limits.first { $0.double > 0 }?.int
        return PlayerSnapshot(online: count?.int ?? names.count, maximum: max, names: names, source: "Provider-API")
    }
}

struct DatabaseInfo {
    let value: JSON
    var name: String { value["name"].string.isEmpty ? value["database"].string : value["name"].string }
    var host: String { value["host"]["address"].string.isEmpty ? (value["host"]["host"].string.isEmpty ? value["host"].string : value["host"]["host"].string) : value["host"]["address"].string }
    var port: String { value["host"]["port"].string.isEmpty ? (value["port"].string.isEmpty ? "3306" : value["port"].string) : value["host"]["port"].string }
    var password: String {
        let candidates = [value["relationships"]["password"]["attributes"]["password"], value["relationships"]["password"]["data"]["attributes"]["password"], value["password"], value["credentials"]["password"]]
        return candidates.first { !$0.string.isEmpty }?.string ?? ""
    }
}
