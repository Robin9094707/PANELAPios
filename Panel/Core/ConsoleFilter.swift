import Foundation
import Combine

enum ConsoleCategory: String, CaseIterable, Identifiable, Sendable {
    case all, chat, errors, warnings, joins, leaves, commands, other
    var id: String { rawValue }
    var title: String {
        switch self {
        case .all: return "Alle Ausgaben"
        case .chat: return "Chat"
        case .errors: return "Fehler & Stacktraces"
        case .warnings: return "Warnungen"
        case .joins: return "Spielerbeitritte"
        case .leaves: return "Spieleraustritte"
        case .commands: return "Befehle"
        case .other: return "Weitere Meldungen"
        }
    }
    var symbol: String {
        switch self {
        case .all: return "terminal"
        case .chat: return "bubble.left.and.bubble.right"
        case .errors: return "exclamationmark.octagon"
        case .warnings: return "exclamationmark.triangle"
        case .joins: return "person.badge.plus"
        case .leaves: return "person.badge.minus"
        case .commands: return "chevron.left.forwardslash.chevron.right"
        case .other: return "text.alignleft"
        }
    }
}
struct ConsoleAnalysis: Sendable {
    let plain: String
    let category: ConsoleCategory
    private static let header = try! NSRegularExpression(pattern: #"^(?:\[\d{1,2}:\d{2}:\d{2}(?:[.,]\d+)?\]\s*)?\[[^\]\r\n]*(?:INFO|WARN|WARNING|ERROR|SEVERE|DEBUG|TRACE)[^\]\r\n]*\]:?\s*"#, options: .caseInsensitive)
    private static let chat = try! NSRegularExpression(pattern: #"^(?:\[(?:Not Secure|Unsigned)\]\s*)*(?:<[^>\r\n]{1,100}>|\[(?:CHAT|Discord|Global|Local|Staff|Team|G|L)\])\s*"#, options: .caseInsensitive)
    private static let join = try! NSRegularExpression(pattern: #"^(?:\[[^\]\r\n]+\]\s*)?[^\s]{1,64}\s+(?:joined the game|hat das Spiel betreten)(?:[.! ]|$)"#, options: .caseInsensitive)
    private static let leave = try! NSRegularExpression(pattern: #"^(?:\[[^\]\r\n]+\]\s*)?[^\s]{1,64}\s+(?:left the game|hat das Spiel verlassen)(?:[.! ]|$)"#, options: .caseInsensitive)
    private static let styledChat = try! NSRegularExpression(pattern: #"^(?:\[[^\]\r\n]{1,60}\]\s*){0,6}[.~]?[A-Za-z0-9_]{1,32}(?:\s*\[[^\]\r\n]{1,40}\])?\s*(?:»|›|→|\s: )\s*.+$"#)
    private static let proxyJoin = try! NSRegularExpression(pattern: #"^(?:\[(?:Geyser[^\]]*|floodgate)\]\s*)(?:Player connected with username\s+\S+|\S+\s+\(logged in as:.*has connected to the Java server|Floodgate player logged in as\s+\S+\s+joined)|^[.~]?[A-Za-z0-9_]{1,32}(?:\[/[^\]]+\])?\s+logged in with entity id\s+\d+"#, options: .caseInsensitive)
    private static let proxyLeave = try! NSRegularExpression(pattern: #"^(?:\[(?:Geyser[^\]]*|floodgate)\]\s*)?(?:[.~]?[A-Za-z0-9_]{1,32}\s+(?:lost connection:|has disconnected from the Java server|disconnected)|Floodgate player\s+.*(?:left|disconnected))"#, options: .caseInsensitive)
    private static let stack = try! NSRegularExpression(pattern: #"^(?:at\s+[\w.$/]+\([^)]*\)|Caused by:|Suppressed:|(?:[\w$]+\.)+[\w$]*(?:Exception|Error)(?::|$)|\.\.\.\s+\d+\s+more)"#)
    private static func matches(_ regex: NSRegularExpression, _ text: String) -> Bool { regex.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)) != nil }
    static func parse(_ raw: String) -> Self {
        let plain = cleanConsole(raw)
        // Wings/JLine redraw markers may repeat before a normal Minecraft header.
        let normalized = plain.replacingOccurrences(of: #"^(?:\s*>[.\s]*)+(?=\[|/|<)"#, with: "", options: .regularExpression).trimmingCharacters(in: .whitespaces)
        let source = normalized as NSString
        let match = header.firstMatch(in: normalized, range: NSRange(location: 0, length: source.length))
        let prefix = match.map { source.substring(with: $0.range).uppercased() } ?? ""
        let payload = (match.map { source.substring(from: NSMaxRange($0.range)) } ?? normalized).trimmingCharacters(in: .whitespaces)
        let category: ConsoleCategory
        if prefix.contains("ERROR") || prefix.contains("SEVERE") { category = .errors }
        else if prefix.contains("WARN") { category = .warnings }
        else if payload.range(of: #"^[.~]?[A-Za-z0-9_]{1,32}\s+issued server command:"#, options: .regularExpression) != nil || payload.hasPrefix("> /") || normalized.hasPrefix("/") { category = .commands }
        else if matches(chat, payload) || matches(styledChat, payload) { category = .chat }
        else if matches(join, payload) || matches(proxyJoin, payload) { category = .joins }
        else if matches(leave, payload) || matches(proxyLeave, payload) { category = .leaves }
        else if matches(stack, payload) { category = .errors }
        else { category = .other }
        return Self(plain: plain, category: category)
    }
}
struct ConsoleProjectionResult: Sendable {
    let text: String
    let shown: Int
    let total: Int
}
actor ConsoleFilterEngine {
    private var cache: [String: ConsoleAnalysis] = [:]
    func project(_ lines: [String], category: ConsoleCategory, query: String) -> ConsoleProjectionResult {
        var next: [String: ConsoleAnalysis] = [:]
        var visible: [String] = []
        for line in lines {
            let analysis = cache[line] ?? ConsoleAnalysis.parse(line)
            next[line] = analysis
            if (category == .all || category == analysis.category) && (query.isEmpty || analysis.plain.localizedCaseInsensitiveContains(query)) { visible.append(line) }
        }
        cache = next
        return ConsoleProjectionResult(text: visible.joined(separator: "\n"), shown: visible.count, total: lines.count)
    }
}
/// Coalesce log bursts without postponing output indefinitely during continuous traffic.
@MainActor final class ConsoleProjection: ObservableObject {
    @Published private(set) var result = ConsoleProjectionResult(text: "", shown: 0, total: 0)
    private struct Input { let lines: [String]; let category: ConsoleCategory; let query: String; let context: Int }
    private let engine = ConsoleFilterEngine()
    private var pending: Input?
    private var latest: Input?
    private var worker: Task<Void, Never>?
    private var token = UUID()
    func request(_ lines: [String], category: ConsoleCategory, query: String, context: Int) {
        let input = Input(lines: lines, category: category, query: query, context: context)
        pending = input; latest = input
        guard worker == nil else { return }
        let id = UUID(); token = id
        worker = Task {
            defer { if token == id { worker = nil } }
            while let input = pending, token == id, !Task.isCancelled {
                pending = nil
                let output = await engine.project(input.lines, category: input.category, query: input.query)
                guard token == id, !Task.isCancelled else { return }
                if latest?.category == input.category && latest?.query == input.query && latest?.context == input.context { result = output }
                do { try await Task.sleep(for: .milliseconds(100)) } catch { return }
            }
        }
    }
    func stop() { token = UUID(); worker?.cancel(); worker = nil; pending = nil; latest = nil }
}

