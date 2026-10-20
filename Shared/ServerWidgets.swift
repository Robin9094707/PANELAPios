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
    static var folder: URL? { FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group) }
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
    static func query(_ panel: String) -> [String: Any]? {
        guard let accessGroup else { return nil }
        return [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: panel, kSecAttrAccessGroup as String: accessGroup]
    }
    static func save(_ key: String, panel: String) throws {
        guard var query = query(panel) else { throw NSError(domain: service, code: 1, userInfo: [NSLocalizedDescriptionKey: "Gemeinsamer Widget-Schlüsselbund nicht verfügbar. Prüfe die Signier-Berechtigungen."]) }
        SecItemDelete(query as CFDictionary)
        query[kSecValueData as String] = Data(key.utf8)
        query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let status = SecItemAdd(query as CFDictionary, nil)
        guard status == errSecSuccess else { throw NSError(domain: service, code: Int(status), userInfo: [NSLocalizedDescriptionKey: "Widget-Zugang konnte nicht gespeichert werden (\(status)). Die App muss mit der gemeinsamen Schlüsselbund-Berechtigung signiert sein."]) }
    }
    static func read(_ panel: String) -> String? {
        guard var query = query(panel) else { return nil }
        query[kSecReturnData as String] = true; query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess, let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }
    static func delete(_ panel: String) { if let query = query(panel) { SecItemDelete(query as CFDictionary) } }
}
