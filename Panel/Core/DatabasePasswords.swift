import Foundation

@MainActor enum DatabasePasswords {
    static func key(_ api: PanelAPI, server: Server, database: JSON) -> String { api.base.absoluteString + "|" + server.id + "|" + database["id"].string }
    static func read(_ key: String) -> String? {
        guard let text = UserDefaults.standard.dictionary(forKey: "database.passwordOverrides.v1")?[key] as? String, let id = UUID(uuidString: text) else { return nil }
        return try? Vault.read(id)
    }
    static func save(_ password: String?, key: String) throws {
        var ids = UserDefaults.standard.dictionary(forKey: "database.passwordOverrides.v1") as? [String: String] ?? [:]
        let id = ids[key].flatMap(UUID.init(uuidString:)) ?? UUID()
        if let password { try Vault.save(password, id: id); ids[key] = id.uuidString }
        else { Vault.delete(id); ids.removeValue(forKey: key) }
        UserDefaults.standard.set(ids, forKey: "database.passwordOverrides.v1")
    }
}
