import Foundation
import Security

struct WidgetServer: Identifiable, Codable, Hashable {
    var id: String { panel + "|" + serverID }
    var panel: String
    var serverID: String
    var name: String
    var backgroundRefresh = false
    var state = "unknown"
    var cpu: Double?
    var memory: Double?
    var memoryLimit: Double?
    var disk: Double?
    var online: Int?
    var maximum: Int?
    var tps: Double?
    var updated: Date?
    var playersUpdated: Date?
    var tpsUpdated: Date?
    var issue: String?
    var stateTitle: String {
        switch state { case "running": return "Online"; case "offline": return "Offline"; case "starting": return "Startet"; case "stopping": return "Stoppt"; default: return "Unbekannt" }
    }
}
enum WidgetStorage {
    static let group = "group.eu.simplexsmp.panel"
    static var folder: URL? {
        guard let group = SigningGroups.availableGroup else { return nil }
        return FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group)
    }
    static func servers() -> [WidgetServer] {
        guard let folder else { return [] }
        return (try? JSONDecoder().decode([WidgetServer].self, from: Data(contentsOf: folder.appendingPathComponent("servers.json")))) ?? []
    }
    static func save(_ servers: [WidgetServer]) throws {
        guard let folder else { throw NSError(domain: "PanelWidgets", code: 1, userInfo: [NSLocalizedDescriptionKey: "Widgets benötigen beim Signieren die App Group group.eu.simplexsmp.panel und die gemeinsame Schlüsselbund-Berechtigung."]) }
        try JSONEncoder().encode(servers).write(to: folder.appendingPathComponent("servers.json"), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
    static func upsert(_ server: WidgetServer) throws {
        var records = servers(); records.removeAll { $0.id == server.id }; records.append(server); try save(records)
    }
}
/// Determine the actual signing-team prefix without hard-coding a development team.
enum WidgetVault {
    static let service = "eu.simplexsmp.panel.widget-credentials"
    private static var accessGroup: String? {
        let account = UUID().uuidString
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service + ".probe", kSecAttrAccount as String: account, kSecValueData as String: Data([0]), kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly, kSecReturnAttributes as String: true]
        var result: CFTypeRef?
        let status = SecItemAdd(query as CFDictionary, &result)
        defer { SecItemDelete([kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service + ".probe", kSecAttrAccount as String: account] as CFDictionary) }
        guard status == errSecSuccess, let attributes = result as? [String: Any], let group = attributes[kSecAttrAccessGroup as String] as? String, let prefix = group.split(separator: ".").first else { return nil }
        return String(prefix) + ".eu.simplexsmp.panel.shared"
    }
    private static var groups: [String] {
        var values: [String] = []
        // iOS also permits an entitled App Group as a keychain access group.
        if let group = SigningGroups.availableGroup { values.append(group) }
        if let group = accessGroup, !values.contains(group) { values.append(group) }
        return values
    }
    private static func query(_ panel: String, group: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: panel, kSecAttrAccessGroup as String: group]
    }
    static func save(_ key: String, panel: String) throws {
        var failure: OSStatus = errSecMissingEntitlement
        for group in groups {
            var request = query(panel, group: group)
            let data = Data(key.utf8)
            let updated = SecItemUpdate(request as CFDictionary, [kSecValueData as String: data] as CFDictionary)
            if updated == errSecSuccess { return }
            if updated != errSecItemNotFound { failure = updated; continue }
            request[kSecValueData as String] = data; request[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            let status = SecItemAdd(request as CFDictionary, nil)
            if status == errSecSuccess { return }
            failure = status
        }
        throw NSError(domain: service, code: Int(failure), userInfo: [NSLocalizedDescriptionKey: "Der Widget-Schlüsselbund ist durch diese Signatur nicht freigegeben (\(failure)). Signiere App und Widget mit derselben App Group."])
    }
    static func read(_ panel: String) -> String? {
        for group in groups {
            var request = query(panel, group: group)
            request[kSecReturnData as String] = true; request[kSecMatchLimit as String] = kSecMatchLimitOne
            var result: CFTypeRef?
            if SecItemCopyMatching(request as CFDictionary, &result) == errSecSuccess, let data = result as? Data, let secret = String(data: data, encoding: .utf8) { return secret }
        }
        return nil
    }
    static func delete(_ panel: String) { for group in groups { SecItemDelete(query(panel, group: group) as CFDictionary) } }
}
