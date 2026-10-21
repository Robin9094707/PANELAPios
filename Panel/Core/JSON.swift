import Foundation

struct Server: Identifiable, Hashable {
    let data: JSON
    var id: String { data["identifier"].string }
    var name: String { data["name"].string }
    var node: String { data["node"].string }
    var address: String {
        let a = data["relationships"]["allocations"].rows
        let primary = a.first(where: { $0["is_default"].bool }) ?? a.first ?? .null
        let host = primary["ip_alias"].string.isEmpty ? primary["ip"].string : primary["ip_alias"].string
        return host.isEmpty ? "–" : "\(host):\(primary["port"].string)"
    }
    static func ==(l: Server, r: Server) -> Bool { l.id == r.id }
    func hash(into hasher: inout Hasher) { hasher.combine(id) }
}

struct ResourcePoint: Identifiable {
    let id = UUID()
    let date: Date
    let cpu: Double
    let memory: Double
}

func bytes(_ value: Double) -> String {
    ByteCountFormatter.string(fromByteCount: Int64(max(0, value)), countStyle: .binary)
}
func cleanConsole(_ value: String) -> String { ConsoleStyle.plain(value) }

extension String { var cleanedForSearch: String { cleanConsole(self) } }
