import Foundation

/// 5.17.0: presentation logic of the Länder-Zentrale (Lands) without SwiftUI – unit-tested on the simulator and on Linux.
enum SuiteLands {
    static let feature = "lands-center-v1"
    static let viewPermission = "data.lands"
    static let managePermission = "lands.manage"

    static let sorts: [(String, String)] = [("chunks", "Größe"), ("members", "Mitglieder"), ("online", "Online"), ("balance", "Bank"), ("newest", "Neueste"), ("name", "Name")]

    enum Tab: String, CaseIterable, Identifiable {
        case members, roles, natural, areas, settings, more
        var id: String { rawValue }
        var title: String {
            switch self {
            case .members: return "Mitglieder"
            case .roles: return "Rollen"
            case .natural: return "Natur"
            case .areas: return "Gebiete"
            case .settings: return "Einstellungen"
            case .more: return "Mehr"
            }
        }
    }

    /// "∞" for unlimited (server sends -1), otherwise the number.
    static func limit(_ value: Int) -> String { value < 0 ? "∞" : String(value) }

    /// "12 / 50" or just "12" when there is no limit.
    static func used(_ value: Int, of maximum: Int) -> String { maximum > 0 ? "\(value) / \(maximum)" : String(value) }

    static func money(_ value: Double) -> String {
        let formatter = NumberFormatter()
        formatter.locale = Locale(identifier: "de_DE")
        formatter.numberStyle = .decimal
        formatter.maximumFractionDigits = 0
        return formatter.string(from: NSNumber(value: value.rounded())) ?? String(Int(value.rounded()))
    }

    static func displayName(_ land: JSON) -> String { land["display"].string.isEmpty ? land["name"].string : land["display"].string }

    /// One line under a land in lists: owner, members, chunks, bank.
    static func summary(_ land: JSON, showOwner: Bool = true) -> String {
        var parts: [String] = []
        if showOwner { parts.append("Besitzer " + (land["ownerName"].string.isEmpty ? "–" : land["ownerName"].string)) }
        parts.append("\(land["members"].int) Mitglieder")
        parts.append(used(land["chunks"].int, of: land["maxChunks"].int) + " Chunks")
        if land["balance"].double != 0 { parts.append("Bank " + money(land["balance"].double)) }
        return parts.joined(separator: " · ")
    }

    static func roleTypeTitle(_ type: String) -> String? {
        switch type {
        case "OWNER": return "Besitzer-Rolle"
        case "VISITOR": return "Besucher"
        case "ALLY": return "Verbündete"
        case "NATION": return "Nation"
        default: return nil
        }
    }

    /// Roles that may be given to a member (never owner/visitor/ally/nation), highest priority first.
    static func assignableRoles(_ roles: [JSON]) -> [JSON] {
        roles.filter { $0["assignable"].bool }.sorted { $0["priority"].int > $1["priority"].int }
    }

    struct FlagGroup: Equatable { let title: String; let flags: [JSON] }

    /// Role flags grouped into action and management rights, each sorted by title.
    static func roleFlagGroups(_ catalog: JSON) -> [FlagGroup] {
        let flags = catalog["role"].array
        let action = flags.filter { $0["category"].string != "MANAGEMENT" }.sorted { $0["title"].string.localizedStandardCompare($1["title"].string) == .orderedAscending }
        let management = flags.filter { $0["category"].string == "MANAGEMENT" }.sorted { $0["title"].string.localizedStandardCompare($1["title"].string) == .orderedAscending }
        return [FlagGroup(title: "Aktionsrechte", flags: action), FlagGroup(title: "Verwaltungsrechte", flags: management)].filter { !$0.flags.isEmpty }
    }

    /// Case-insensitive membership test for flag names sent by Lands ("block_break" vs "BLOCK_BREAK").
    static func contains(_ names: [JSON], _ flag: String) -> Bool {
        let wanted = flag.lowercased()
        return names.contains { $0.string.lowercased() == wanted }
    }

    /// "31×31 Blöcke, Höhe -64–320" for a sub-area box.
    static func bounds(_ area: JSON) -> String {
        let b = area["bounds"]
        guard b != .null else { return "" }
        let width = b["maxX"].int - b["minX"].int + 1, depth = b["maxZ"].int - b["minZ"].int + 1
        return "\(width)×\(depth) Blöcke, Höhe \(b["minY"].int)–\(b["maxY"].int)"
    }

    static func areaDetail(_ area: JSON) -> String {
        var parts = ["\(area["members"].int) Mitglieder"]
        if !area["world"].string.isEmpty { parts.append(area["world"].string) }
        let box = bounds(area)
        if !box.isEmpty { parts.append(box) }
        if area["tax"].double > 0 { parts.append("Steuer " + money(area["tax"].double)) }
        return parts.joined(separator: " · ")
    }

    /// Badge text for a land in the player profile.
    static func membershipTitle(_ land: JSON, kind: String) -> String {
        switch kind {
        case "owned": return "Besitzer (Hauptland)"
        case "banned": return "Gebannt"
        default:
            var text = land["role"].string.isEmpty ? "Mitglied" : land["role"].string
            if !land["wholeLand"].bool { text += " · nur Gebiete" }
            return text
        }
    }

    /// Body for POST /v1/lands.
    static func body(_ operation: String, land: String, area: String = "", extra: [String: JSON] = [:]) -> [String: JSON] {
        var out: [String: JSON] = ["operation": .string(operation), "land": .string(land)]
        if !area.isEmpty { out["area"] = .string(area) }
        for (key, value) in extra { out[key] = value }
        return out
    }

    /// Decimal input with German comma; nil for invalid input.
    static func decimal(_ text: String) -> Double? {
        Double(text.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: ",", with: "."))
    }
}
