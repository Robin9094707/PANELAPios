import ActivityKit
import Foundation

struct ServerActivity: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        var state: String
        var cpu: Double?
        var memory: Double?
        var online: Int?
        var maximum: Int?
        var tps: Double?
        var mspt: Double?
        var uptime: Double?
        var updated: Date
        var playersUpdated: Date?
        var performanceUpdated: Date?
        var source: String
        var issue: String?
        var stateTitle: String { switch state { case "running": return "Online"; case "offline": return "Offline"; case "starting": return "Startet"; case "stopping": return "Stoppt"; default: return "Unbekannt" } }
        var playerLabel: String { online.map(String.init) ?? "–" }
    }
    var panel: String
    var serverID: String
    var name: String
}
