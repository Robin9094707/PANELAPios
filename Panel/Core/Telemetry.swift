import SwiftUI

struct TPSSnapshot: Equatable {
    let minute: Double
    let five: Double
    let fifteen: Double
    var updated = Date()
    static func parse(_ text: String) -> TPSSnapshot? {
        let line = cleanConsole(text).replacingOccurrences(of: "\u{00A0}", with: " ")
        let pattern = #"TPS from last\s+([^:\n]+):\s*([^\n]+)"#
        guard let re = try? NSRegularExpression(pattern: pattern, options: .caseInsensitive),
              let match = re.firstMatch(in: line, range: NSRange(line.startIndex..., in: line)),
              let header = Range(match.range(at: 1), in: line), let payload = Range(match.range(at: 2), in: line) else { return nil }
        let labels = line[header].split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces).lowercased() }
        let numbers = line[payload].split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces).trimmingCharacters(in: CharacterSet(charactersIn: "*")) }
        var values: [Double] = []
        for label in ["1m", "5m", "15m"] {
            guard let index = labels.firstIndex(of: label), index < numbers.count,
                  let n = Double(numbers[index]), n.isFinite, (0...20.1).contains(n) else { return nil }
            values.append(min(20, n))
        }
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
