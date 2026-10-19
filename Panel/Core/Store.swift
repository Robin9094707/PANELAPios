import SwiftUI
import Security
import LocalAuthentication

struct PanelProfile: Identifiable, Codable, Equatable {
    var id: UUID = UUID()
    var name: String
    var url: String
}
enum Vault {
    private static let service = "eu.simplexsmp.panel.credentials"
    static func save(_ secret: String, id: UUID) throws {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: id.uuidString]
        let data = Data(secret.utf8)
        let update = SecItemUpdate(query as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if update == errSecItemNotFound {
            var add = query; add[kSecValueData as String] = data
            add[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
            let status = SecItemAdd(add as CFDictionary, nil)
            guard status == errSecSuccess else { throw PanelError(message: "Schlüsselbund konnte nicht gespeichert werden (\(status)).") }
        } else if update != errSecSuccess { throw PanelError(message: "Schlüsselbund konnte nicht aktualisiert werden (\(update)).") }
    }
    static func read(_ id: UUID) throws -> String {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
                                  kSecAttrAccount as String: id.uuidString, kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitOne]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess, let data = item as? Data, let secret = String(data: data, encoding: .utf8) else {
            throw PanelError(message: "API-Schlüssel nicht verfügbar. Füge den Zugang erneut hinzu.")
        }
        return secret
    }
    static func delete(_ id: UUID) {
        SecItemDelete([kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: id.uuidString] as CFDictionary)
    }
}

@MainActor final class AppStore: ObservableObject {
    @Published var profiles: [PanelProfile] = []
    @Published var selected: UUID?
    @Published var api: PanelAPI?
    @Published var servers: [Server] = []
    @Published var loading = false
    private var refreshID = UUID()
    @Published var error: String?
    @Published var demo = false
    @Published var locked = false
    @AppStorage("biometricLock") var biometricLock = false
    var active: PanelProfile? { profiles.first { $0.id == selected } }
    init() {
        profiles = (try? JSONDecoder().decode([PanelProfile].self, from: UserDefaults.standard.data(forKey: "profiles") ?? Data())) ?? []
        if ProcessInfo.processInfo.arguments.contains("--demo") { startDemo(); return }
        if let saved = UserDefaults.standard.string(forKey: "selectedPanel"), let id = UUID(uuidString: saved), profiles.contains(where: { $0.id == id }) { selected = id }
        else { selected = profiles.first?.id }
        if biometricLock { locked = true }
        else { connectSelected() }
    }
    func persist() {
        UserDefaults.standard.set(try? JSONEncoder().encode(profiles), forKey: "profiles")
        UserDefaults.standard.set(selected?.uuidString, forKey: "selectedPanel")
    }
    func connectSelected() {
        guard let p = active else { api = nil; servers = []; return }
        do { api = PanelAPI(base: try PanelAPI.normalize(p.url), key: try Vault.read(p.id)); servers = [] }
        catch { self.error = error.localizedDescription }
    }
    func select(_ id: UUID) { demo = false; selected = id; persist(); connectSelected() }
    func add(name: String, address: String, key: String) async throws {
        let url = try PanelAPI.normalize(address)
        let token = key.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !token.isEmpty, !token.contains(where: { $0.isWhitespace }) else { throw PanelError(message: "Gib einen gültigen Client-API-Schlüssel ein.") }
        guard !token.hasPrefix("ptla_") else { throw PanelError(message: "Das ist ein Application-Schlüssel. Erstelle im Panel unter Account → API Credentials einen Client-Schlüssel (ptlc_).") }
        let client = PanelAPI(base: url, key: token)
        let rows = try await client.list("")
        let profile = PanelProfile(name: name.isEmpty ? url.host! : name, url: url.absoluteString)
        try Vault.save(token, id: profile.id)
        profiles.append(profile); selected = profile.id; demo = false; api = client; servers = rows.map { Server(data: $0) }; persist()
    }
    func remove(_ profile: PanelProfile) {
        Vault.delete(profile.id); profiles.removeAll { $0.id == profile.id }
        if selected == profile.id { selected = profiles.first?.id; connectSelected() }
        persist()
    }
    func refresh() async {
        guard let api else { return }
        let requestID = UUID(); refreshID = requestID
        loading = true
        defer { if refreshID == requestID { loading = false } }
        do {
            let result = try await api.list("").map { Server(data: $0) }
            guard self.api === api, refreshID == requestID, !Task.isCancelled else { return }; servers = result
        } catch {
            if !Task.isCancelled && self.api === api && refreshID == requestID { self.error = error.localizedDescription }
        }
    }
    func startDemo() {
        demo = true; api = PanelAPI(base: URL(string: "https://demo.invalid")!, key: "", demo: true)
        servers = Demo.servers.map { Server(data: $0) }
    }
    func exitDemo() { demo = false; connectSelected() }
    func lock() { if biometricLock && !demo { locked = true; api = nil; servers = [] } }
    func unlock() async {
        let context = LAContext()
        do {
            if try await context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: "Panel-Zugänge entsperren") { locked = false; connectSelected() }
        } catch { self.error = error.localizedDescription }
    }
}
