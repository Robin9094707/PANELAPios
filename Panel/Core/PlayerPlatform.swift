import SwiftUI

enum PlayerPlatform: String, CaseIterable, Sendable {
    case java, ios, android, windows, xbox, playstation, nintendoSwitch, mac, linux, unknown
    var title: String {
        switch self {
        case .java: return "Java-Client"
        case .ios: return "iOS · Bedrock"
        case .android: return "Android · Bedrock"
        case .windows: return "Windows · Bedrock"
        case .xbox: return "Xbox · Bedrock"
        case .playstation: return "PlayStation · Bedrock"
        case .nintendoSwitch: return "Nintendo Switch · Bedrock"
        case .mac: return "macOS · Bedrock"
        case .linux: return "Linux · Bedrock"
        case .unknown: return "Bedrock · Plattform unbekannt"
        }
    }
    var symbol: String {
        switch self {
        case .java: return "desktopcomputer"
        case .ios: return "iphone"
        case .android: return "smartphone"
        case .windows: return "square.grid.2x2.fill"
        case .xbox: return "xbox.logo"
        case .playstation: return "playstation.logo"
        case .nintendoSwitch: return "gamecontroller.fill"
        case .mac: return "laptopcomputer"
        case .linux: return "desktopcomputer"
        case .unknown: return "gamecontroller"
        }
    }
    var color: Color {
        switch self {
        case .java: return .teal
        case .ios: return .blue
        case .android, .xbox: return .green
        case .windows: return .cyan
        case .playstation: return .indigo
        case .nintendoSwitch: return .red
        case .mac: return .secondary
        case .linux: return .orange
        case .unknown: return .secondary
        }
    }
    static func resolve(_ row: JSON) -> Self {
        if row["client"].string.uppercased() == "JAVA" { return .java }
        let structured = ["os", "deviceOs", "operatingSystem", "deviceOS"].map { row[$0].string }.joined(separator: " ")
        if let match = match(structured) { return match }
        if let match = match(row["platform"].string) { return match }
        let model = (row["device"].string.isEmpty ? row["name"].string : row["device"].string).lowercased()
        if let match = match(model) { return match }
        if model.hasPrefix("samsung sm-") || model.contains("xiaomi ") || model.hasPrefix("oppo cph") || model.hasPrefix("redmi ") || model.hasPrefix("oneplus ") || model.hasPrefix("google pixel") { return .android }
        return .unknown
    }
    private static func match(_ raw: String) -> Self? {
        let normalized = raw.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        switch normalized {
        case "1", "ANDROID", "4", "AMAZON", "FIREOS", "FIRE_OS": return .android
        case "2", "IOS", "I_OS": return .ios
        case "3", "OSX", "MACOS", "MAC_OS": return .mac
        case "7", "8", "WINDOWS_10", "WIN10", "WINDOWS_32", "WIN32", "UWP": return .windows
        case "11", "PLAYSTATION", "PS4", "PS5": return .playstation
        case "12", "NX", "SWITCH", "NINTENDO": return .nintendoSwitch
        case "13", "XBOX": return .xbox
        case "15", "LINUX": return .linux
        default: break
        }
        let value = raw.lowercased().replacingOccurrences(of: "_", with: " ").replacingOccurrences(of: "-", with: " ")
        if value.contains("playstation") || value == "ps4" || value == "ps5" { return .playstation }
        if value.contains("xbox") { return .xbox }
        if value.contains("switch") || value.contains("nintendo") { return .nintendoSwitch }
        if value.contains("iphone") || value.contains("ipad") || value.trimmingCharacters(in: .whitespaces) == "ios" || value.contains(" ipados") { return .ios }
        if value.contains("android") || value.contains("amazon") || value.contains("fire os") { return .android }
        if value.contains("windows") || value.contains("uwp") || value.contains("win32") { return .windows }
        if value.contains("macos") || value.contains("osx") || value.contains("os x") { return .mac }
        if value.contains("linux") { return .linux }
        return nil
    }
}
struct PlayerPlatformIcon: View {
    let platform: PlayerPlatform
    var size: CGFloat = 27
    var body: some View {
        Group {
            if platform == .nintendoSwitch {
                HStack(spacing: 2) {
                    ZStack { RoundedRectangle(cornerRadius: 4).stroke(lineWidth: 2); Circle().frame(width: 3, height: 3).offset(y: -3) }
                    RoundedRectangle(cornerRadius: 1).frame(width: 7)
                    ZStack { RoundedRectangle(cornerRadius: 4).fill(); Circle().fill(.background).frame(width: 3, height: 3).offset(y: 3) }
                }.frame(width: size, height: size * 0.75)
            } else { Image(systemName: platform.symbol).font(.system(size: size)) }
        }.foregroundStyle(platform.color).frame(width: size + 5, height: size + 5).accessibilityLabel(platform.title)
    }
}
@MainActor final class SuitePlatformCache {
    private var values: [String: (PlayerPlatform, Date)] = [:]
    private var tasks: [String: Task<PlayerPlatform, Never>] = [:]
    private let gate = ImageRequestGate()
    func platform(_ player: String, suite: SuiteAPI) async -> PlayerPlatform {
        if let value = values[player], Date().timeIntervalSince(value.1) < 300 { return value.0 }
        if let task = tasks[player] { return await task.value }
        let gate = gate
        let task = Task<PlayerPlatform, Never> {
            await gate.acquire()
            let result: PlayerPlatform
            do {
                try Task.checkCancellation()
                let items = try await suite.data("devices", query: ["player": player])["items"].array
                let latest = items.max { $0["lastSeen"].double < $1["lastSeen"].double }
                result = latest.map(PlayerPlatform.resolve) ?? .unknown
            } catch { result = .unknown }
            await gate.release()
            return result
        }
        tasks[player] = task
        let result = await task.value; tasks.removeValue(forKey: player)
        if values.count >= 256 { values.removeValue(forKey: values.min { $0.value.1 < $1.value.1 }!.key) }
        values[player] = (result, Date()); return result
    }
}
