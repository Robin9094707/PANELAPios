import SwiftUI

struct TPSSnapshot: Equatable {
    let minute: Double
    let five: Double
    let fifteen: Double
    var updated = Date()
    static func parse(_ text: String) -> TPSSnapshot? {
        let pattern = #"TPS from last\s+1m,\s*5m,\s*15m:\s*\*?([0-9]+(?:\.[0-9]+)?),\s*\*?([0-9]+(?:\.[0-9]+)?),\s*\*?([0-9]+(?:\.[0-9]+)?)"#
        let line = cleanConsole(text).replacingOccurrences(of: "§[0-9a-fk-or]", with: "", options: .regularExpression)
        guard let re = try? NSRegularExpression(pattern: pattern, options: .caseInsensitive), let m = re.firstMatch(in: line, range: NSRange(line.startIndex..., in: line)) else { return nil }
        var values: [Double] = []
        for i in 1...3 { guard let r = Range(m.range(at: i), in: line), let n = Double(line[r]), (0...20.1).contains(n) else { return nil }; values.append(min(20, n)) }
        return TPSSnapshot(minute: values[0], five: values[1], fifteen: values[2])
    }
    static func color(_ value: Double) -> Color { value >= 19 ? .green : value >= 16 ? .yellow : .red }
}
struct ServerPresence {
    var state: String
    var updated = Date()
}
enum PowerPolicy {
    static func allows(_ signal: String, state: String) -> Bool {
        switch signal {
        case "start": return state == "offline"
        case "restart": return state == "running"
        case "stop": return ["running", "starting"].contains(state)
        case "kill": return ["running", "starting", "stopping"].contains(state)
        default: return false
        }
    }
}
enum Feedback {
    @MainActor static func success() { if UserDefaults.standard.object(forKey: "haptics") as? Bool ?? true { UINotificationFeedbackGenerator().notificationOccurred(.success) } }
}
