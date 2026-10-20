import ActivityKit
import Foundation

struct TransferActivity: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        var completed: Int64
        var total: Int64
        var phase: String
        var updated: Date
        var fraction: Double? { total > 0 ? min(1, max(0, Double(completed) / Double(total))) : nil }
    }
    var transferID: String
    var filename: String
    var upload: Bool
}
