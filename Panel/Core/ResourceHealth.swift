import SwiftUI

enum ResourceLoad: String {
    case unknown, low, moderate, high
    static func fraction(value: Double, limit: Double) -> Double? {
        guard value.isFinite, value >= 0, limit.isFinite, limit > 0 else { return nil }
        return value / limit
    }
    static func level(_ fraction: Double?) -> Self {
        guard let fraction, fraction.isFinite, fraction >= 0 else { return .unknown }
        return fraction >= 0.9 ? .high : fraction >= 0.7 ? .moderate : .low
    }
    var color: Color {
        switch self { case .unknown: return .secondary; case .low: return .green; case .moderate: return .yellow; case .high: return .red }
    }
    var title: String {
        switch self { case .unknown: return "Kein Limit bekannt"; case .low: return "Niedrige Auslastung"; case .moderate: return "Erhöhte Auslastung"; case .high: return "Hohe Auslastung" }
    }
}
struct ServerHealth {
    let title: String
    let detail: String
    let level: ResourceLoad
    static func assess(state: String, updated: Date?, fractions: [Double?], now: Date = Date()) -> Self {
        guard state == "running" else { return Self(title: state == "offline" ? "Server offline" : "Status prüfen", detail: "Eine Auslastungsbewertung ist nur bei laufendem Server möglich.", level: .unknown) }
        guard let updated, now.timeIntervalSince(updated) <= 45 else { return Self(title: "Messwerte fehlen oder sind veraltet", detail: "Aktualisieren, um die aktuelle Auslastung zu sehen.", level: .unknown) }
        let known = fractions.compactMap { $0 }.filter { $0.isFinite && $0 >= 0 }
        guard let worst = known.max() else { return Self(title: "Messwerte verfügbar", detail: "Ohne bekannte Limits ist keine relative Auslastungsbewertung möglich.", level: .unknown) }
        let level = ResourceLoad.level(worst)
        return Self(title: level == .high ? "Hohe Ressourcenauslastung" : level == .moderate ? "Erhöhte Ressourcenauslastung" : "Ressourcen im grünen Bereich", detail: "\(known.count) von \(fractions.count) Ressourcen mit bekanntem Limit bewertet. Eine Momentaufnahme, keine Verfügbarkeitsgarantie.", level: level)
    }
}
