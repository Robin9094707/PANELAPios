import Foundation
import zlib
import CryptoKit

struct PluginDescriptor: Equatable, Sendable {
    let name: String
    let version: String
    let main: String
    let apiVersion: String
    var summary = ""
    var author = ""
    var website = ""
    var descriptorFile = "plugin.yml"
    var rawYAML = ""
    var versionedFilename: String {
        func safe(_ value: String, fallback: String) -> String {
            let clean = value.replacingOccurrences(of: "[^A-Za-z0-9._+-]+", with: "-", options: .regularExpression)
                .trimmingCharacters(in: CharacterSet(charactersIn: ".-"))
            return clean.isEmpty ? fallback : String(clean.prefix(80))
        }
        return safe(name, fallback: "Plugin") + "-" + safe(version, fallback: "version") + ".jar"
    }
    static func parse(_ text: String) throws -> PluginDescriptor {
        var fields: [String: String] = [:]
        let lines = text.components(separatedBy: .newlines)
        for (index, line) in lines.enumerated() {
            guard !line.hasPrefix(" "), !line.hasPrefix("\t"), let colon = line.firstIndex(of: ":") else { continue }
            let key = String(line[..<colon]); guard ["name", "version", "main", "api-version", "description", "author", "authors", "website"].contains(key) else { continue }
            let value = String(line[line.index(after: colon)...]).trimmingCharacters(in: .whitespaces)
            fields[key] = ConfigurationField.decodeYAML(value)?.value ?? value.components(separatedBy: " #")[0].trimmingCharacters(in: .whitespaces)
            if (key == "authors" && value.isEmpty) || (key == "description" && ["|", ">", "|-", ">-"].contains(value)) {
                let body = lines.dropFirst(index + 1).prefix { $0.hasPrefix(" ") || $0.hasPrefix("\t") || $0.isEmpty }
                if key == "authors" { fields[key] = body.compactMap { item -> String? in
                    let row = item.trimmingCharacters(in: .whitespaces)
                    guard row.hasPrefix("- ") else { return nil }
                    let raw = String(row.dropFirst(2))
                    return ConfigurationField.decodeYAML(raw)?.value ?? raw
                }.joined(separator: ", ") }
                else { fields[key] = body.map { $0.trimmingCharacters(in: .whitespaces) }.joined(separator: value.hasPrefix(">") ? " " : "\n").trimmingCharacters(in: .whitespacesAndNewlines) }
            }
        }
        guard let name = fields["name"], !name.isEmpty, let version = fields["version"], !version.isEmpty, let main = fields["main"], !main.isEmpty else { throw PanelError(message: "Plugin-Metadaten fehlen: name, version und main müssen in plugin.yml oder paper-plugin.yml stehen.") }
        guard name.utf8.count <= 256, version.utf8.count <= 256, main.utf8.count <= 1024 else { throw PanelError(message: "Plugin-Metadaten enthalten ungewöhnlich lange Identitätsfelder.") }
        return PluginDescriptor(name: name, version: version, main: main, apiVersion: fields["api-version"] ?? "Nicht angegeben", summary: String((fields["description"] ?? "").prefix(4000)), author: String((fields["author"] ?? fields["authors"] ?? "").prefix(2000)), website: String((fields["website"] ?? "").prefix(2000)), rawYAML: text)
    }
    func validateUpdate(_ next: PluginDescriptor) throws -> String {
        guard name == next.name, main == next.main else { throw PanelError(message: "Falsches Plugin. Erwartet: \(name) (\(main)); gewählt: \(next.name) (\(next.main)).") }
        guard version != next.version else { throw PanelError(message: "Dieselbe Version (\(version)) ist bereits installiert.") }
        let numeric = #"^\d+(\.\d+){0,4}$"#
        if version.range(of: numeric, options: .regularExpression) != nil && next.version.range(of: numeric, options: .regularExpression) != nil {
            guard next.version.compare(version, options: .numeric) == .orderedDescending else { throw PanelError(message: "Die ausgewählte Version \(next.version) ist kein neueres Update als \(version).") }
            return "Plugin-Name, Einstiegsklasse und höhere Versionsnummer geprüft."
        }
        return "Plugin-Name und Einstiegsklasse stimmen überein. Die Reihenfolge dieser Versionsbezeichnungen lässt sich nicht zuverlässig bestimmen."
    }
}
enum PluginArchive {
    static func descriptor(_ data: Data) throws -> PluginDescriptor {
        guard data.count >= 22 && data.count <= 128 * 1024 * 1024 else { throw PanelError(message: "Keine gültige Plugin-JAR oder größer als 128 MiB.") }
        func n(_ offset: Int, _ count: Int) throws -> Int { guard offset >= 0 && offset + count <= data.count else { throw PanelError(message: "Beschädigtes ZIP-Verzeichnis.") }; return Int(data.uintLE(offset, count)) }
        var end: Int?
        for i in stride(from: data.count - 22, through: max(0, data.count - 65557), by: -1) { if try n(i, 4) == 0x06054b50 && i + 22 + n(i + 20, 2) == data.count { end = i; break } }
        guard let end, try n(end + 4, 2) == 0, try n(end + 6, 2) == 0 else { throw PanelError(message: "Keine unterstützte JAR/ZIP-Datei.") }
        let count = try n(end + 10, 2); var cursor = try n(end + 16, 4)
        guard count < 65535 else { throw PanelError(message: "ZIP64 wird für Plugin-Metadaten nicht unterstützt.") }
        var descriptors: [String: String] = [:]
        for _ in 0..<count {
            guard try n(cursor, 4) == 0x02014b50 else { throw PanelError(message: "Ungültiger ZIP-Eintrag.") }
            let nameCount = try n(cursor + 28, 2), extra = try n(cursor + 30, 2), comment = try n(cursor + 32, 2)
            guard cursor + 46 + nameCount + extra + comment <= data.count else { throw PanelError(message: "Abgeschnittener ZIP-Eintrag.") }
            let name = String(decoding: data[cursor + 46..<cursor + 46 + nameCount], as: UTF8.self)
            if ["plugin.yml", "paper-plugin.yml"].contains(name) {
                guard descriptors[name] == nil, try n(cursor + 8, 2) & 1 == 0 else { throw PanelError(message: "Mehrdeutige oder verschlüsselte Plugin-Metadaten.") }
                let packed = try n(cursor + 20, 4), size = try n(cursor + 24, 4), method = try n(cursor + 10, 2), local = try n(cursor + 42, 4), crc = try n(cursor + 16, 4)
                guard size <= 1024 * 1024, packed <= 2 * 1024 * 1024, try n(local, 4) == 0x04034b50 else { throw PanelError(message: "Plugin-Metadaten zu groß oder beschädigt.") }
                let offset = try local + 30 + n(local + 26, 2) + n(local + 28, 2)
                guard offset >= 0 && offset + packed <= data.count else { throw PanelError(message: "Abgeschnittene Plugin-Datei.") }
                let compressed = Data(data[offset..<offset + packed]); let content: Data
                if method == 0 { content = compressed } else if method == 8 { content = try BinaryCodec.inflateData(compressed, window: -15, limit: 1024 * 1024) } else { throw PanelError(message: "Nicht unterstützte ZIP-Kompression.") }
                let actual = content.withUnsafeBytes { crc32(0, $0.bindMemory(to: Bytef.self).baseAddress, uInt(content.count)) }
                guard content.count == size, actual == uLong(crc), let text = String(data: content, encoding: .utf8) else { throw PanelError(message: "Plugin-Prüfsumme oder YAML-Zeichenkodierung ungültig.") }; descriptors[name] = text
            }
            cursor += 46 + nameCount + extra + comment
        }
        guard let yaml = descriptors["paper-plugin.yml"] ?? descriptors["plugin.yml"] else { throw PanelError(message: "Die JAR enthält weder plugin.yml noch paper-plugin.yml. Keine Paper/Bukkit-Plugin-Datei.") }
        var descriptor = try PluginDescriptor.parse(yaml)
        descriptor.descriptorFile = descriptors["paper-plugin.yml"] != nil ? "paper-plugin.yml" : "plugin.yml"
        return descriptor
    }
    static func digest(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() }
}
struct PluginUpdateCandidate: Identifiable {
    let id = UUID()
    let filename: String
    let file: URL
    let installed: PluginDescriptor
    let next: PluginDescriptor
    let originalHash: String
    let report: String
    var updateFilename: String { next.versionedFilename }
}

/// Paper matches updates by the exact descriptor name and adopts the staged filename.
enum PluginUpdatePlan {
    static func directory(_ config: String) throws -> String {
        let field = ConfigurationField.parse(config, properties: false).first { $0.path == "settings › update-folder" || $0.path == "update-folder" }
        if field == nil && config.range(of: #"(?m)^\s*update-folder\s*:"#, options: .regularExpression) != nil {
            throw PanelError(message: "update-folder in bukkit.yml konnte nicht eindeutig gelesen werden. Nutze den Texteditor.")
        }
        let update = field?.value ?? "update"
        let parts = update.split(separator: "/", omittingEmptySubsequences: false).map(String.init)
        guard !update.hasPrefix("/"), !update.contains("\\"), !parts.isEmpty,
              !parts.contains(where: { $0.isEmpty || $0 == "." || $0 == ".." }), !update.contains("\n"), !update.contains("\r") else {
            throw PanelError(message: "Nicht unterstützter update-folder in bukkit.yml. Nutze den Datei-Manager.")
        }
        return "/plugins/" + parts.joined(separator: "/")
    }
    static func checkFilename(_ target: String, installed: String, files: [String]) throws {
        guard !files.contains(where: { $0 != installed && $0.caseInsensitiveCompare(target) == .orderedSame }) else {
            throw PanelError(message: "Die Zieldatei \(target) existiert bereits unter /plugins. Bitte zuerst im Datei-Manager prüfen; es wird nichts überschrieben.")
        }
    }
    static func checkPending(_ pending: PluginDescriptor, next: PluginDescriptor) throws {
        guard pending.name != next.name else { throw PanelError(message: "Für \(next.name) ist bereits Version \(pending.version) vorgemerkt. Entferne die bisherige Update-JAR zuerst, damit Paper nur ein Update findet.") }
    }
}
