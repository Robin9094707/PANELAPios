import Foundation

struct ConfigurationField: Identifiable {
    let line: Int
    let key: String
    let path: String
    let value: String
    let kind: String
    let prefix: String
    let suffix: String
    let properties: Bool
    var id: Int { line }
    var options: [String] { properties ? Self.options(for: key) : [] }
    static func options(for key: String) -> [String] {
        switch key {
        case "difficulty": return ["peaceful", "easy", "normal", "hard"]
        case "gamemode": return ["survival", "creative", "adventure", "spectator"]
        case "level-type": return ["minecraft:normal", "minecraft:flat", "minecraft:large_biomes", "minecraft:amplified", "minecraft:single_biome_surface"]
        case "region-file-compression": return ["deflate", "lz4", "none"]
        default: return []
        }
    }
    static func parse(_ text: String, properties: Bool) -> [ConfigurationField] {
        guard text.utf8.count <= 524288 else { return [] }
        var fields: [ConfigurationField] = [], parents: [(Int, String)] = []
        let lines = text.components(separatedBy: "\n")
        let re = try! NSRegularExpression(pattern: properties ? #"^(\s*[^#!\s:=\\]+\s*[=:]\s*)(.*)$"# : #"^( *)([A-Za-z0-9_.-]+):(?: +(.*))?$"#)
        var continuation = false
        for (index, original) in lines.enumerated() {
            let line = original.hasSuffix("\r") ? String(original.dropLast()) : original
            let slashes = line.reversed().prefix(while: { $0 == "\\" }).count
            let continued = properties && slashes % 2 == 1
            if continuation || continued { continuation = continued; continue }
            guard fields.count < 500, let match = re.firstMatch(in: line, range: NSRange(line.startIndex..., in: line)) else { continue }
            func part(_ n: Int) -> String { Range(match.range(at: n), in: line).map { String(line[$0]) } ?? "" }
            var value: String, prefix: String, suffix = original.hasSuffix("\r") ? "\r" : "", key: String, path: String, quoted = false
            if properties {
                prefix = part(1); key = prefix.trimmingCharacters(in: .whitespaces).dropLast().trimmingCharacters(in: .whitespaces); path = key
                guard let decoded = decodeProperty(part(2)) else { continue }; value = decoded
            } else {
                let indent = part(1).count; key = part(2)
                while let last = parents.last, last.0 >= indent { parents.removeLast() }
                path = (parents.map { $0.1 } + [key]).joined(separator: " › ")
                let raw = part(3)
                if raw.isEmpty || raw.hasPrefix("#") { parents.append((indent, key)); continue }
                // A value with a following indented body is a multiline node, never a scalar.
                if let next = lines.dropFirst(index + 1).first(where: { !$0.trimmingCharacters(in: .whitespaces).isEmpty && !$0.trimmingCharacters(in: .whitespaces).hasPrefix("#") }), next.prefix(while: { $0 == " " }).count > indent { continue }
                prefix = String(line.prefix(match.range(at: 3).location))
                guard let scalar = decodeYAML(raw) else { continue }
                value = scalar.value; quoted = scalar.quoted; suffix = scalar.suffix + suffix
            }
            let kind = !quoted && ["true", "false"].contains(value.lowercased()) ? "bool" : !quoted && Double(value) != nil ? "number" : "string"
            fields.append(ConfigurationField(line: index, key: key, path: path, value: value, kind: kind, prefix: prefix, suffix: suffix, properties: properties))
        }
        // Duplicate properties are ambiguous; leave them in the text editor.
        if properties { let counts = Dictionary(grouping: fields, by: \.key); fields.removeAll { (counts[$0.key]?.count ?? 0) > 1 } }
        return fields
    }
    static func decodeYAML(_ raw: String) -> (value: String, suffix: String, quoted: Bool)? {
        if raw.hasPrefix("\"") || raw.hasPrefix("'") {
            let quote = raw.first!, chars = Array(raw); var i = 1
            while i < chars.count {
                if quote == "\"", chars[i] == "\\" { i += 2; continue }
                if chars[i] == quote {
                    if quote == "'", i + 1 < chars.count, chars[i + 1] == "'" { i += 2; continue }
                    let encoded = String(chars[0...i]), tail = String(chars.dropFirst(i + 1))
                    guard tail.trimmingCharacters(in: .whitespaces).isEmpty || tail.trimmingCharacters(in: .whitespaces).hasPrefix("#") else { return nil }
                    let value: String
                    if quote == "\"" { guard let decoded = try? JSONDecoder().decode(String.self, from: Data(encoded.utf8)) else { return nil }; value = decoded }
                    else { value = String(encoded.dropFirst().dropLast()).replacingOccurrences(of: "''", with: "'") }
                    return (value, tail, true)
                }; i += 1
            }; return nil
        }
        var value = raw, suffix = ""
        if let comment = value.range(of: " #") { suffix = String(value[comment.lowerBound...]); value = String(value[..<comment.lowerBound]) }
        let trimmed = value.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty, !["null", "~"].contains(trimmed.lowercased()), trimmed.range(of: #"^[A-Za-z0-9 _./:@+-]+$"#, options: .regularExpression) != nil else { return nil }
        suffix = String(value.dropFirst(trimmed.count)) + suffix
        return (trimmed, suffix, false)
    }
    static func decodeProperty(_ raw: String) -> String? {
        let chars = Array(raw); var output = "", i = 0
        while i < chars.count {
            if chars[i] != "\\" { output.append(chars[i]); i += 1; continue }
            i += 1; guard i < chars.count else { return nil }
            switch chars[i] {
            case "n": output.append("\n")
            case "r": output.append("\r")
            case "t": output.append("\t")
            case "f": output.append("\u{c}")
            case "u":
                guard i + 4 < chars.count, let n = UInt32(String(chars[(i+1)...(i+4)]), radix: 16), let scalar = UnicodeScalar(n) else { return nil }; output.unicodeScalars.append(scalar); i += 4
            default: output.append(chars[i])
            }; i += 1
        }; return output
    }
    func replacement(_ edit: String) throws -> String {
        if kind == "number" { guard let n = Double(edit), n.isFinite else { throw PanelError(message: "\(key): gültige Zahl eingeben.") } }
        if kind == "bool", !["true", "false"].contains(edit.lowercased()) { throw PanelError(message: "\(key): true oder false erwartet.") }
        let encoded: String
        if properties {
            encoded = edit.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "\n", with: "\\n").replacingOccurrences(of: "\r", with: "\\r").replacingOccurrences(of: "\t", with: "\\t").replacingOccurrences(of: "\u{c}", with: "\\f")
                .enumerated().map { index, character in index == 0 && character == " " ? "\\ " : String(character) }.joined()
        } else { encoded = kind == "string" ? JSON.string(edit).pretty : edit.lowercased() }
        return prefix + encoded + suffix
    }
}
