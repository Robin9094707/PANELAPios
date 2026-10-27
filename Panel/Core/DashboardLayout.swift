import Foundation
import Combine

enum DashboardScope: String, Codable { case home, server }
enum DashboardCard: String, Codable, CaseIterable, Identifiable {
    case welcome, folders, servers, fleet, favorites, account, transfers
    case identity, players, resources, cpuHistory, network, health, memoryHistory, shortcuts, minecraftTools, storageTools
    var id: String { rawValue }
    var title: String {
        switch self {
        case .transfers: return "Übertragungen"
        case .minecraftTools: return "Minecraft-Werkzeuge"
        case .storageTools: return "Dateien, Backups & Datenbanken"
        case .welcome: return "Begrüßung"
        case .folders: return "Favoritenordner"
        case .servers: return "Serverliste"
        case .fleet: return "Server im Überblick"
        case .favorites: return "Angepinnte Server"
        case .account: return "Panel-Informationen"
        case .identity: return "Server & Verbindung"
        case .players: return "Spieler & TPS"
        case .resources: return "RAM, CPU, Speicher & Laufzeit"
        case .cpuHistory: return "CPU-Verlauf"
        case .network: return "Netzwerk & Node"
        case .health: return "Server-Gesundheit"
        case .memoryHistory: return "RAM-Verlauf"
        case .shortcuts: return "Konsolen-Schnellzugriff"
        }
    }
    var symbol: String {
        switch self {
        case .transfers: return "arrow.up.arrow.down.circle"
        case .minecraftTools: return "globe.europe.africa"
        case .storageTools: return "externaldrive"
        case .welcome: return "hand.wave"
        case .folders: return "folder"
        case .servers: return "server.rack"
        case .fleet: return "chart.bar"
        case .favorites: return "star"
        case .account: return "person.crop.circle"
        case .identity: return "network"
        case .players: return "person.2"
        case .resources: return "gauge.with.dots.needle.67percent"
        case .cpuHistory: return "cpu"
        case .memoryHistory: return "memorychip"
        case .network: return "arrow.up.arrow.down"
        case .health: return "heart.text.square"
        case .shortcuts: return "terminal"
        }
    }
    static func available(_ scope: DashboardScope) -> [Self] {
        scope == .home ? [.welcome, .folders, .servers, .fleet, .favorites, .account, .transfers] : [.identity, .players, .resources, .cpuHistory, .network, .health, .memoryHistory, .shortcuts, .minecraftTools, .storageTools]
    }
    static func defaults(_ scope: DashboardScope) -> [Self] {
        scope == .home ? [.welcome, .folders, .servers] : [.identity, .players, .resources, .cpuHistory, .network]
    }
    static func normalize(_ raw: [String], scope: DashboardScope) -> [Self] {
        let allowed = available(scope)
        var seen = Set<Self>()
        var cards = raw.compactMap(Self.init(rawValue:)).filter { allowed.contains($0) && seen.insert($0).inserted }
        if scope == .home && !cards.contains(.servers) { cards.append(.servers) }
        return cards
    }
}
@MainActor final class DashboardLayouts: ObservableObject {
    static let shared = DashboardLayouts()
    @Published private(set) var records: [String: [String]]
    private let defaults: UserDefaults
    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        records = (try? JSONDecoder().decode([String: [String]].self, from: defaults.data(forKey: "dashboard.layouts.v1") ?? Data())) ?? [:]
    }
    func cards(_ key: String, scope: DashboardScope) -> [DashboardCard] {
        guard let values = records[key] else { return DashboardCard.defaults(scope) }
        return DashboardCard.normalize(values, scope: scope)
    }
    func set(_ values: [DashboardCard], key: String, scope: DashboardScope) {
        records[key] = DashboardCard.normalize(values.map(\.rawValue), scope: scope).map(\.rawValue)
        persist()
    }
    func reset(_ key: String) { records.removeValue(forKey: key); persist() }
    private func persist() { defaults.set(try? JSONEncoder().encode(records), forKey: "dashboard.layouts.v1") }
}

struct CommandHistoryCursor {
    private(set) var index: Int?
    private(set) var draft = ""
    private(set) var presented = ""
    mutating func older(current: String, entries: [String]) -> String {
        guard !entries.isEmpty else { return current }
        if index == nil { draft = current; index = 0 }
        else { index = min(entries.count - 1, index! + 1) }
        presented = entries[index!]; return presented
    }
    mutating func newer(entries: [String]) -> String {
        guard let index else { return presented }
        if index == 0 { self.index = nil; presented = draft }
        else { self.index = min(index - 1, max(0, entries.count - 1)); presented = entries.isEmpty ? draft : entries[self.index!] }
        return presented
    }
    mutating func edited(_ text: String) { if text != presented { self = Self(); presented = text } }
}
@MainActor final class ConsoleHistory: ObservableObject {
    static let shared = ConsoleHistory()
    @Published private(set) var records: [String: [String]]
    private let defaults: UserDefaults
    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        let raw = (try? JSONDecoder().decode([String: [String]].self, from: defaults.data(forKey: "console.history.v1") ?? Data())) ?? [:]
        records = raw.mapValues { Array($0.filter(Self.valid).prefix(20)) }
    }
    nonisolated static func valid(_ command: String) -> Bool { !command.isEmpty && command.utf8.count <= 4096 && !command.contains(where: { $0.isNewline || $0 == "\0" }) }
    func entries(_ key: String) -> [String] { records[key] ?? [] }
    func remember(_ command: String, key: String) {
        let text = command.trimmingCharacters(in: .whitespacesAndNewlines)
        guard Self.valid(text), records[key]?.first != text else { return }
        records[key] = Array(([text] + (records[key] ?? [])).prefix(20)); persist()
    }
    func clear(_ key: String) { records.removeValue(forKey: key); persist() }
    private func persist() { defaults.set(try? JSONEncoder().encode(records), forKey: "console.history.v1") }
}
