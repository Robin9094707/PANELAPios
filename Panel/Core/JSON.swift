import Foundation

/// Lossless API values: keeps upstream snake_case fields and supports panel extensions.
enum JSON: Codable, Equatable, Sendable {
    case object([String: JSON]), array([JSON]), string(String), number(Double), bool(Bool), null
    init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null }
        else if let v = try? c.decode(Bool.self) { self = .bool(v) }
        else if let v = try? c.decode(Double.self) { self = .number(v) }
        else if let v = try? c.decode(String.self) { self = .string(v) }
        else if let v = try? c.decode([JSON].self) { self = .array(v) }
        else { self = .object(try c.decode([String: JSON].self)) }
    }
    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .object(let v): try c.encode(v)
        case .array(let v): try c.encode(v)
        case .string(let v): try c.encode(v)
        case .number(let v): try c.encode(v)
        case .bool(let v): try c.encode(v)
        case .null: try c.encodeNil()
        }
    }
    subscript(_ key: String) -> JSON { if case .object(let o) = self { return o[key] ?? .null }; return .null }
    var string: String {
        switch self {
        case .string(let v): return v
        case .number(let v): return v.rounded() == v ? String(format: "%.0f", v) : String(v)
        case .bool(let v): return v ? "true" : "false"
        default: return ""
        }
    }
    var double: Double { if case .number(let n) = self { return n }; return Double(string) ?? 0 }
    var int: Int { Int(double) }
    var bool: Bool { if case .bool(let b) = self { return b }; return false }
    var array: [JSON] { if case .array(let a) = self { return a }; return [] }
    var object: [String: JSON] { if case .object(let o) = self { return o }; return [:] }
    var attributes: JSON { self["attributes"] == .null ? self : self["attributes"] }
    var rows: [JSON] { self["data"].array.map(\.attributes) }
    var pretty: String {
        let encoder = JSONEncoder(); encoder.outputFormatting = [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
        return (try? String(data: encoder.encode(self), encoding: .utf8)) ?? "{}"
    }
    static func parse(_ text: String) throws -> JSON { try JSONDecoder().decode(JSON.self, from: Data(text.utf8)) }
    static func from(_ object: [String: Any]) throws -> JSON { try JSONDecoder().decode(JSON.self, from: JSONSerialization.data(withJSONObject: object)) }
}

struct PanelError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

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
func cleanConsole(_ value: String) -> String {
    value.replacingOccurrences(of: "\u{1B}\\[[0-?]*[ -/]*[@-~]", with: "", options: .regularExpression)
        .replacingOccurrences(of: "\r", with: "")
}
