import Foundation
import Combine

@MainActor final class PanelClock: ObservableObject {
    @Published private(set) var offset: TimeInterval = 0
    @Published private(set) var synced: Date?
    func observe(_ response: URLResponse) {
        guard let http = response as? HTTPURLResponse, let header = http.value(forHTTPHeaderField: "Date") else { return }
        let formatter = DateFormatter(); formatter.locale = Locale(identifier: "en_US_POSIX"); formatter.timeZone = TimeZone(secondsFromGMT: 0); formatter.dateFormat = "EEE, dd MMM yyyy HH:mm:ss zzz"
        guard let date = formatter.date(from: header) else { return }
        offset = date.timeIntervalSinceNow; synced = Date()
    }
    var now: Date { Date().addingTimeInterval(offset) }
}
enum ScheduleTime {
    static let berlin = TimeZone(identifier: "Europe/Berlin")!
    static let zoneKey = "schedule.panel.timezone.v1."
    static func zone(for base: URL) -> TimeZone? { UserDefaults.standard.string(forKey: zoneKey + base.absoluteString).flatMap(TimeZone.init(identifier:)) }
    static func parse(_ value: String) -> Date? {
        let parser = ISO8601DateFormatter()
        if let date = parser.date(from: value) { return date }
        parser.formatOptions.insert(.withFractionalSeconds); return parser.date(from: value)
    }
    static func display(_ date: Date, zone: TimeZone = berlin) -> String {
        let formatter = DateFormatter(); formatter.locale = Locale(identifier: "de_DE"); formatter.timeZone = zone; formatter.dateStyle = .medium; formatter.timeStyle = .short
        return formatter.string(from: date)
    }
    static func display(_ value: String) -> String { parse(value).map { display($0) + " · Deutschland" } ?? "Noch nicht geplant" }
    static func valid(_ expression: String, range: ClosedRange<Int>) -> Bool {
        guard !expression.isEmpty else { return false }
        return expression.split(separator: ",", omittingEmptySubsequences: false).allSatisfy { item in
            let parts = item.split(separator: "/", omittingEmptySubsequences: false)
            guard parts.count <= 2 else { return false }
            if parts.count == 2 { guard let step = Int(parts[1]), step >= 1, step <= range.upperBound - range.lowerBound + 1 else { return false } }
            if parts[0] == "*" { return true }
            let limits = parts[0].split(separator: "-", omittingEmptySubsequences: false)
            if limits.count == 1 { return Int(limits[0]).map(range.contains) ?? false }
            guard limits.count == 2, let low = Int(limits[0]), let high = Int(limits[1]) else { return false }
            return range.contains(low) && range.contains(high) && low <= high
        }
    }
    /// Only convert a simple daily time. Complex expressions retain their original panel semantics.
    static func convertedTime(hour: Int, minute: Int, panelZone: TimeZone, on date: Date) -> Date? {
        var calendar = Calendar(identifier: .gregorian); calendar.timeZone = panelZone
        return calendar.nextDate(after: date, matching: DateComponents(hour: hour, minute: minute), matchingPolicy: .nextTime, repeatedTimePolicy: .first)
    }
}
