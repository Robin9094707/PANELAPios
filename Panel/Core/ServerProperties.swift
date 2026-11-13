import Foundation

/// Lossless `server.properties` editing: unchanged lines (comments, order, spacing, escapes) are written back verbatim.
struct ServerProperties: Equatable {
    enum Line: Equatable { case other(String), entry(key: String, rawValue: String, separator: String) }
    var lines: [Line]

    static func parse(_ text: String) -> ServerProperties {
        var result: [Line] = []
        let normalized = text.replacingOccurrences(of: "\r\n", with: "\n")
        var physical = normalized.components(separatedBy: "\n")
        if physical.last == "" { physical.removeLast() }
        for line in physical {
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            guard !trimmed.isEmpty, !trimmed.hasPrefix("#"), !trimmed.hasPrefix("!") else { result.append(.other(line)); continue }
            var key = "", escaped = false, index = line.startIndex
            while index < line.endIndex {
                let c = line[index]
                if escaped { key.append(c); escaped = false }
                else if c == "\\" { key.append(c); escaped = true }
                else if c == "=" || c == ":" || (c == " " && !key.isEmpty) { break }
                else if !(c == " " && key.isEmpty) { key.append(c) }
                index = line.index(after: index)
            }
            guard !key.isEmpty, !line.hasSuffix("\\") else { result.append(.other(line)); continue }
            var separatorEnd = index
            while separatorEnd < line.endIndex, line[separatorEnd] == " " { separatorEnd = line.index(after: separatorEnd) }
            if separatorEnd < line.endIndex, line[separatorEnd] == "=" || line[separatorEnd] == ":" { separatorEnd = line.index(after: separatorEnd) }
            while separatorEnd < line.endIndex, line[separatorEnd] == " " { separatorEnd = line.index(after: separatorEnd) }
            let leading = line[line.startIndex..<line.firstIndex(where: { $0 != " " })!]
            let separator = String(line[index..<separatorEnd])
            result.append(.entry(key: String(leading) + key, rawValue: String(line[separatorEnd...]), separator: separator))
        }
        return ServerProperties(lines: result)
    }
    private static func cleanKey(_ key: String) -> String { unescape(key.trimmingCharacters(in: .whitespaces)) }
    /// Values as Minecraft reads them (escapes resolved). The last occurrence of a key wins, like java.util.Properties.
    var values: [String: String] {
        var out: [String: String] = [:]
        for case .entry(let key, let raw, _) in lines { out[Self.cleanKey(key)] = Self.unescape(raw) }
        return out
    }
    var keys: [String] {
        var seen = Set<String>(), out: [String] = []
        for case .entry(let key, _, _) in lines { let k = Self.cleanKey(key); if seen.insert(k).inserted { out.append(k) } }
        return out
    }
    /// New file text. Only lines of changed keys are rewritten; keys missing from the file are appended.
    func text(applying changes: [String: String]) -> String {
        var pending = changes
        var out: [String] = []
        for line in lines {
            switch line {
            case .other(let text): out.append(text)
            case .entry(let key, let raw, let separator):
                let clean = Self.cleanKey(key)
                if let value = changes[clean], Self.unescape(raw) != value { out.append(key + (separator.isEmpty ? "=" : separator) + Self.escape(value)) }
                else { out.append(key + separator + raw) }
                pending[clean] = nil
            }
        }
        for key in pending.keys.sorted() { out.append(Self.escape(key, isKey: true) + "=" + Self.escape(pending[key] ?? "")) }
        return out.joined(separator: "\n") + "\n"
    }
    static func unescape(_ raw: String) -> String {
        var out = "", chars = Array(raw), i = 0
        while i < chars.count {
            let c = chars[i]
            guard c == "\\", i + 1 < chars.count else { out.append(c); i += 1; continue }
            let n = chars[i + 1]
            switch n {
            case "n": out.append("\n"); i += 2
            case "t": out.append("\t"); i += 2
            case "r": out.append("\r"); i += 2
            case "f": out.append("\u{0C}"); i += 2
            case "u":
                if i + 5 < chars.count, let code = UInt32(String(chars[(i + 2)...(i + 5)]), radix: 16), let scalar = Unicode.Scalar(code) { out.unicodeScalars.append(scalar); i += 6 }
                else { out.append("u"); i += 2 }
            default: out.append(n); i += 2
            }
        }
        return out
    }
    /// Escapes like java.util.Properties.store: backslash, control characters and non-ASCII as \uXXXX (safe for every reader).
    static func escape(_ value: String, isKey: Bool = false) -> String {
        var out = ""
        for (index, scalar) in value.unicodeScalars.enumerated() {
            switch scalar {
            case "\\": out += "\\\\"
            case "\n": out += "\\n"
            case "\t": out += "\\t"
            case "\r": out += "\\r"
            case "=", ":", "#", "!": if isKey { out += "\\" + String(scalar) } else { out.unicodeScalars.append(scalar) }
            case " ": out += (isKey || index == 0) ? "\\ " : " "
            default:
                if scalar.value < 0x20 || scalar.value > 0x7E {
                    if scalar.value > 0xFFFF { for unit in String(scalar).utf16 { out += String(format: "\\u%04X", unit) } }
                    else { out += String(format: "\\u%04X", scalar.value) }
                } else { out.unicodeScalars.append(scalar) }
            }
        }
        return out
    }

    /// Value kinds for native controls.
    enum Kind: Equatable { case bool, integer, choice([String]), text }
    static func kind(_ key: String, value: String) -> Kind {
        if let options = choices[key] { return .choice(options) }
        if value == "true" || value == "false" { return .bool }
        if Int(value) != nil && !["level-seed", "rcon.password", "server-ip", "resource-pack-sha1"].contains(key) { return .integer }
        return .text
    }
    static let choices: [String: [String]] = [
        "difficulty": ["peaceful", "easy", "normal", "hard"],
        "gamemode": ["survival", "creative", "adventure", "spectator"],
        "level-type": ["minecraft:normal", "minecraft:flat", "minecraft:large_biomes", "minecraft:amplified", "minecraft:single_biome_surface"]
    ]
    /// Short German descriptions for the most common keys.
    static let help: [String: String] = [
        "motd": "Nachricht in der Serverliste",
        "max-players": "Maximale Spielerzahl",
        "difficulty": "Schwierigkeit",
        "gamemode": "Standard-Spielmodus",
        "force-gamemode": "Spielmodus bei jedem Beitritt erzwingen",
        "online-mode": "Mojang-Konten prüfen (aus nur hinter Proxy/Geyser-Setups)",
        "white-list": "Whitelist aktiv",
        "enforce-whitelist": "Nicht gelistete Spieler sofort kicken",
        "pvp": "PvP erlaubt",
        "allow-flight": "Fliegen erlauben (verhindert Kicks durch Anti-Fly)",
        "view-distance": "Sichtweite in Chunks",
        "simulation-distance": "Simulationsweite in Chunks",
        "spawn-protection": "Spawnschutz-Radius",
        "level-name": "Weltordner",
        "level-seed": "Seed neuer Welten",
        "enable-command-block": "Befehlsblöcke erlauben",
        "hardcore": "Hardcore",
        "allow-nether": "Nether erlauben",
        "resource-pack": "Ressourcenpaket-URL",
        "require-resource-pack": "Ressourcenpaket erzwingen",
        "server-port": "Port – bei UltraServers über Netzwerk & Ports steuern"
    ]
}
