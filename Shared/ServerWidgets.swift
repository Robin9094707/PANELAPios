import Foundation
import Security
import CryptoKit

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
    var provider: String?
    var playerNames: [String]?
    var uptime: Double?
    var controller: Bool?
    var suiteEndpoint: String?
    var suitePin: String?
    var suiteIdentity: String?
    var stateTitle: String {
        switch state { case "running": return "Online"; case "offline": return "Offline"; case "starting": return "Startet"; case "stopping": return "Stoppt"; default: return "Unbekannt" }
    }
}
struct WidgetManifest: Codable {
    var generatedAt = Date()
    var servers: [WidgetServer]
}
enum WidgetStorage {
    static let group = "group.eu.simplexsmp.panel"
    static let cacheKey = "widget.manifest.v2"
    static var folder: URL? {
        guard let group = SigningGroups.availableGroup else { return nil }
        return FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group)
    }
    static var connectionTitle: String {
        if let group = SigningGroups.availableGroup { return "Gemeinsamer App-Speicher: " + group }
        if WidgetVault.sharedData("manifest.v2") != nil { return "Gemeinsamer Widget-Schlüsselbund" }
        return "Signatur gibt keinen gemeinsamen Speicher frei. Daten-Code als manuelle Kopie verfügbar."
    }
    static func manifest(_ data: Data?) -> WidgetManifest? {
        guard let data, data.count <= 512 * 1024 else { return nil }
        if let manifest = try? JSONDecoder().decode(WidgetManifest.self, from: data) { return manifest }
        if let legacy = try? JSONDecoder().decode([WidgetServer].self, from: data) { return WidgetManifest(generatedAt: .distantPast, servers: legacy) }
        return nil
    }
    static func servers() -> [WidgetServer] {
        let file = folder.flatMap { try? Data(contentsOf: $0.appendingPathComponent("servers.json")) }
        let defaults = SigningGroups.availableGroup.flatMap { UserDefaults(suiteName: $0)?.data(forKey: cacheKey) }
        let records = [file, defaults, WidgetVault.sharedData("manifest.v2"), UserDefaults.standard.data(forKey: cacheKey)].compactMap(manifest).max { $0.generatedAt < $1.generatedAt }?.servers ?? []
        return records.map { record in
            guard record.backgroundRefresh, let data = cacheURL(record.id).flatMap({ try? Data(contentsOf: $0) }) ?? WidgetVault.sharedData(snapshotKey(record.id)), let latest = try? JSONDecoder().decode(WidgetServer.self, from: data), (latest.updated ?? .distantPast) > (record.updated ?? .distantPast) else { return record }
            var merged = record
            merged.state = latest.state; merged.cpu = latest.cpu; merged.memory = latest.memory; merged.disk = latest.disk; merged.uptime = latest.uptime; merged.memoryLimit = latest.memoryLimit
            merged.online = latest.online; merged.playerNames = latest.playerNames; merged.playersUpdated = latest.playersUpdated; merged.updated = latest.updated; merged.issue = latest.issue
            return merged
        }
    }
    static func save(_ servers: [WidgetServer]) throws {
        let data = try JSONEncoder().encode(WidgetManifest(servers: servers))
        guard data.count <= 512 * 1024 else { throw PanelError(message: "Zu viele Widget-Daten. Entferne einige Server.") }
        UserDefaults.standard.set(data, forKey: cacheKey)
        if let folder {
            try data.write(to: folder.appendingPathComponent("servers.json"), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            if let group = SigningGroups.availableGroup { UserDefaults(suiteName: group)?.set(data, forKey: cacheKey) }
        }
        // A shared keychain is an independent fallback when a signer removes App Groups.
        try? WidgetVault.saveSharedData(data, account: "manifest.v2")
    }
    static func upsert(_ server: WidgetServer) throws {
        var records = servers(); records.removeAll { $0.id == server.id }; records.append(server); try save(records)
    }
    static func snapshotKey(_ id: String) -> String { "snapshot-" + SHA256.hash(data: Data(id.utf8)).map { String(format: "%02x", $0) }.joined() }
    static func cacheURL(_ id: String) -> URL? { folder?.appendingPathComponent(snapshotKey(id) + ".json") }
    static func cache(_ record: WidgetServer) {
        guard let data = try? JSONEncoder().encode(record) else { return }
        if let url = cacheURL(record.id) { try? data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]) }
        else { try? WidgetVault.saveSharedData(data, account: snapshotKey(record.id)) }
    }
    static func copied(_ text: String) -> WidgetServer? {
        guard text.count < 150_000, let data = Data(base64Encoded: text.trimmingCharacters(in: .whitespacesAndNewlines)), data.count < 100_000,
              var value = try? JSONDecoder().decode(WidgetServer.self, from: data), value.name.count < 500, (value.playerNames?.count ?? 0) <= 2000 else { return nil }
        value.backgroundRefresh = false; value.issue = "Manuell kopierter Datenstand"
        return value
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
    private static let groups: [String] = {
        var values: [String] = []
        // iOS also permits an entitled App Group as a keychain access group.
        if let group = SigningGroups.availableGroup { values.append(group) }
        let declared = SigningGroups.profile["keychain-access-groups"] as? [String] ?? []
        for group in declared where group.hasSuffix(".shared") && group.lowercased().contains("panel") && !group.contains("*") { if !values.contains(group) { values.append(group) } }
        if let group = accessGroup, !values.contains(group) { values.append(group) }
        return values
    }()
    private static func query(_ panel: String, group: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: panel, kSecAttrAccessGroup as String: group]
    }
    static func sharedData(_ account: String) -> Data? {
        for group in groups {
            var request = query(account, group: group); request[kSecReturnData as String] = true; request[kSecMatchLimit as String] = kSecMatchLimitOne
            var result: CFTypeRef?
            if SecItemCopyMatching(request as CFDictionary, &result) == errSecSuccess { return result as? Data }
        }; return nil
    }
    static func saveSharedData(_ data: Data, account: String) throws {
        for group in groups {
            var request = query(account, group: group)
            if SecItemUpdate(request as CFDictionary, [kSecValueData as String: data] as CFDictionary) == errSecSuccess { return }
            request[kSecValueData as String] = data; request[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            if SecItemAdd(request as CFDictionary, nil) == errSecSuccess { return }
        }
        throw PanelError(message: "Gemeinsamer Widget-Schlüsselbund nicht freigegeben.")
    }
    private static func file(_ panel: String) -> URL? {
        let name = SHA256.hash(data: Data(panel.utf8)).map { String(format: "%02x", $0) }.joined()
        return WidgetStorage.folder?.appendingPathComponent("credential-" + name + ".json")
    }
    static func save(_ key: String, panel: String) throws {
        if let file = file(panel) {
            try Data(key.utf8).write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]); return
        }
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
        if let file = file(panel), let value = try? String(contentsOf: file, encoding: .utf8) { return value }
        for group in groups {
            var request = query(panel, group: group)
            request[kSecReturnData as String] = true; request[kSecMatchLimit as String] = kSecMatchLimitOne
            var result: CFTypeRef?
            if SecItemCopyMatching(request as CFDictionary, &result) == errSecSuccess, let data = result as? Data, let secret = String(data: data, encoding: .utf8) { return secret }
        }
        return nil
    }
    static func delete(_ panel: String) { if let file = file(panel) { try? FileManager.default.removeItem(at: file) }; for group in groups { SecItemDelete(query(panel, group: group) as CFDictionary) } }
}
