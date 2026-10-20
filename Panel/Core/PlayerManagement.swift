import Foundation
import Combine

struct PlayerCommandPreferences: Codable, Equatable {
    var kick = "minecraft:kick {player} {reason}"
    var ban = "minecraft:ban {player} {reason}"
    var custom: [PlayerActionTemplate] = []
}
struct PlayerActionTemplate: Identifiable, Codable, Equatable {
    var id = UUID()
    var name: String
    var command: String
}
enum PlayerCommands {
    static func validName(_ name: String) -> Bool { name.range(of: #"^[A-Za-z0-9_.-]{1,64}$"#, options: .regularExpression) != nil }
    static func render(_ template: String, player: String, reason: String = "") throws -> String {
        guard validName(player) else { throw PanelError(message: "Dieser Spielername kann nicht sicher in einen Befehl eingesetzt werden.") }
        let command = template.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !command.isEmpty, command.count <= 1000, !command.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) || CharacterSet.newlines.contains($0) }),
              !reason.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) || CharacterSet.newlines.contains($0) }), reason.count <= 250 else { throw PanelError(message: "Verwende einen einzelnen Befehl und einen Grund ohne Zeilenumbrüche (maximal 250 Zeichen).") }
        guard command.contains("{player}") else { throw PanelError(message: "Die Vorlage muss {player} enthalten.") }
        let regex = try NSRegularExpression(pattern: #"\{([^{}]+)\}"#)
        let source = command as NSString
        var result = command
        for match in regex.matches(in: command, range: NSRange(location: 0, length: source.length)).reversed() {
            let key = source.substring(with: match.range(at: 1))
            guard key == "player" || key == "reason" else { throw PanelError(message: "Unbekannter Platzhalter {\(key)}. Erlaubt sind {player} und {reason}.") }
            if let range = Range(match.range, in: result) { result.replaceSubrange(range, with: key == "player" ? player : reason) }
        }
        if result.hasPrefix("/") { result.removeFirst() }
        return result.trimmingCharacters(in: .whitespacesAndNewlines)
    }
    static func response(_ text: String, player: String) -> String? {
        let pattern = NSRegularExpression.escapedPattern(for: player) + #"\s+(?:has the following entity data|hat (?:die )?folgenden? Entitätsdaten):\s*(.+)$"#
        let line = cleanConsole(text)
        guard let re = try? NSRegularExpression(pattern: pattern, options: .caseInsensitive), let match = re.firstMatch(in: line, range: NSRange(line.startIndex..., in: line)), let range = Range(match.range(at: 1), in: line) else { return nil }
        return String(line[range]).trimmingCharacters(in: .whitespacesAndNewlines)
    }
    static func number(_ value: String?) -> Double? {
        guard let value, let re = try? NSRegularExpression(pattern: #"^([-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][-+]?\d+)?)[bBsSlLfFdD]?$"#), let match = re.firstMatch(in: value, range: NSRange(value.startIndex..., in: value)), let range = Range(match.range(at: 1), in: value), let number = Double(value[range]), number.isFinite else { return nil }
        return number
    }
}
@MainActor final class PlayerPreferencesStore: ObservableObject {
    @Published private var entries: [String: PlayerCommandPreferences] = [:]
    private let defaults: UserDefaults
    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        entries = (try? JSONDecoder().decode([String: PlayerCommandPreferences].self, from: defaults.data(forKey: "workspace.playerActions.v1") ?? Data())) ?? [:]
    }
    func get(_ key: String) -> PlayerCommandPreferences { entries[key] ?? PlayerCommandPreferences() }
    func save(_ value: PlayerCommandPreferences, key: String) {
        entries[key] = value; defaults.set(try? JSONEncoder().encode(entries), forKey: "workspace.playerActions.v1")
    }
}
@MainActor final class PlayerInspector: ObservableObject {
    @Published private(set) var values: [String: String] = [:]
    @Published private(set) var updated: Date?
    @Published private(set) var busy = false
    @Published var issue: String?
    func read(player: String, live: LiveServer, api: PanelAPI, server: Server) async {
        guard !busy else { return }; busy = true; defer { busy = false }
        if api.demo { values = ["Health": "18.0f", "foodLevel": "20", "XpLevel": "32", "playerGameType": "0", "Pos": "[120.5d, 64.0d, -85.0d]", "Dimension": "\"minecraft:overworld\""]; updated = Date(); issue = nil; return }
        guard live.connected && live.state == "running" else { issue = "Für Live-Werte muss die Konsole mit einem laufenden Server verbunden sein."; return }
        do {
            var received: [String: String] = [:]
            for field in ["Health", "foodLevel", "XpLevel", "playerGameType", "Pos", "Dimension"] {
                try Task.checkCancellation()
                received[field] = try await live.queryPlayerValue(player: player, field: field, api: api, server: server)
                values[field] = received[field]
            }
            updated = Date(); issue = nil
        } catch is CancellationError {} catch { issue = error.localizedDescription }
    }
}
