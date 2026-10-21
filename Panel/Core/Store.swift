import SwiftUI
import Security
import LocalAuthentication

struct PanelProfile: Identifiable, Codable, Equatable {
    var id: UUID = UUID()
    var name: String
    var url: String
    var provider: PanelProvider = .pterodactyl
    enum CodingKeys: String, CodingKey { case id, name, url, provider }
    init(id: UUID = UUID(), name: String, url: String, provider: PanelProvider = .pterodactyl) {
        self.id = id; self.name = name; self.url = url; self.provider = provider
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(UUID.self, forKey: .id); name = try c.decode(String.self, forKey: .name); url = try c.decode(String.self, forKey: .url)
        provider = try c.decodeIfPresent(PanelProvider.self, forKey: .provider) ?? .pterodactyl
    }
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
    private let defaults: UserDefaults
    private let readSecret: (UUID) throws -> String
    @Published var presence: [String: ServerPresence] = [:]
    @Published var profiles: [PanelProfile] = []
    @Published var selected: UUID?
    @Published var api: PanelAPI?
    @Published var servers: [Server] = []
    @Published var loading = false
    private var refreshID = UUID()
    @Published var error: String?
    @Published var demo = false
    @Published var locked = false
    @Published var biometricLock = false { didSet { defaults.set(biometricLock, forKey: "biometricLock") } }
    var active: PanelProfile? { profiles.first { $0.id == selected } }
    init(defaults: UserDefaults = .standard, readSecret: @escaping (UUID) throws -> String = Vault.read, launchDemo: Bool? = nil) {
        self.defaults = defaults; self.readSecret = readSecret
        biometricLock = defaults.bool(forKey: "biometricLock")
        profiles = (try? JSONDecoder().decode([PanelProfile].self, from: defaults.data(forKey: "profiles") ?? Data())) ?? []
        if launchDemo ?? ProcessInfo.processInfo.arguments.contains("--demo") { startDemo(); return }
        if let saved = defaults.string(forKey: "selectedPanel"), let id = UUID(uuidString: saved), profiles.contains(where: { $0.id == id }) { selected = id }
        else { selected = profiles.first?.id }
        if biometricLock { locked = true }
        else { connectSelected() }
    }
    func restoreConnectionIfNeeded() {
        guard !locked, !demo else { return }
        if active == nil { selected = profiles.first?.id }
        if api == nil { connectSelected() }
    }
    func resume() async {
        restoreConnectionIfNeeded()
        if !locked { await refresh() }
    }
    func refreshPresence() async {
        guard let api else { return }
        let ids = servers.map(\.id)
        for offset in stride(from: 0, to: ids.count, by: 3) {
            guard !Task.isCancelled, self.api === api else { return }
            await withTaskGroup(of: (String, String?).self) { group in
                for id in ids[offset..<min(offset + 3, ids.count)] {
                    group.addTask { @MainActor in
                        if api.demo { return (id, id == "simplex1" ? "running" : "offline") }
                        let result = try? await api.request("servers/\(id)/resources")
                        return (id, result?.attributes["current_state"].string)
                    }
                }
                for await (id, state) in group where self.api === api && !Task.isCancelled {
                    presence[id] = ServerPresence(state: state?.isEmpty == false ? state! : "unknown")
                }
            }
        }
    }
    func pollPresence() async {
        guard let api else { return }
        while !Task.isCancelled && self.api === api {
            let ids = servers.map(\.id)
            if ids.isEmpty { do { try await Task.sleep(for: .milliseconds(500)) } catch { return }; continue }
            for batch in stride(from: 0, to: ids.count, by: 3) {
                if Task.isCancelled { return }
                let groupIDs = Array(ids[batch..<min(batch + 3, ids.count)])
                await withTaskGroup(of: (String, String?).self) { group in
                    for id in groupIDs { group.addTask { @MainActor in
                        if api.demo { return (id, id == "simplex1" ? "running" : "offline") }
                        let result = try? await api.request("servers/\(id)/resources")
                        return (id, result?.attributes["current_state"].string)
                    } }
                    for await (id, state) in group {
                        guard self.api === api, !Task.isCancelled else { continue }
                        presence[id] = ServerPresence(state: state?.isEmpty == false ? state! : "unknown")
                    }
                }
            }
            do { try await Task.sleep(for: .seconds(15)) } catch { return }
        }
    }
    func persist() {
        defaults.set(try? JSONEncoder().encode(profiles), forKey: "profiles")
        defaults.set(selected?.uuidString, forKey: "selectedPanel")
    }
    func connectSelected() {
        guard let p = active else { api = nil; servers = []; return }
        do {
            let secret = try readSecret(p.id)
            let amp = p.provider == .amp ? try AMPAPI(base: AMPAPI.normalize(p.url), secret: secret) : nil
            amp?.persistCredential = { try Vault.save($0, id: p.id) }
            api = PanelAPI(base: try PanelAPI.normalize(p.url), key: secret, amp: amp); servers = []; error = nil
        }
        catch { api = nil; servers = []; presence = [:]; self.error = error.localizedDescription }
    }
    func select(_ id: UUID) { presence = [:]; demo = false; selected = id; persist(); connectSelected() }
    func add(name: String, address: String, key: String) async throws {
        let url = try PanelAPI.normalize(address)
        let token = key.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !token.isEmpty, !token.contains(where: { $0.isWhitespace }) else { throw PanelError(message: "Gib einen gültigen Client-API-Schlüssel ein.") }
        guard !token.hasPrefix("ptla_") else { throw PanelError(message: "Das ist ein Application-Schlüssel. Erstelle im Panel unter Account → API Credentials einen Client-Schlüssel (ptlc_).") }
        let client = PanelAPI(base: url, key: token)
        let rows = try await client.list("")
        let profile = PanelProfile(name: name.isEmpty ? url.host! : name, url: url.absoluteString)
        try Vault.save(token, id: profile.id)
        profiles.append(profile); selected = profile.id; demo = false; api = client; servers = rows.map { Server(data: $0) }; presence = [:]; persist()
    }
    func addAMP(name: String, address: String, username: String, password: String, otp: String) async throws {
        let url = try AMPAPI.normalize(address)
        guard !username.trimmingCharacters(in: .whitespaces).isEmpty, !password.isEmpty else { throw PanelError(message: "AMP-Benutzername und Passwort eingeben.") }
        let secret = AMPCredentials(username: username.trimmingCharacters(in: .whitespaces), password: password).encoded
        let profile = PanelProfile(name: name.isEmpty ? url.host! : name, url: url.absoluteString, provider: .amp)
        let amp = try AMPAPI(base: url, secret: secret, otp: otp)
        try Vault.save(secret, id: profile.id)
        amp.persistCredential = { try Vault.save($0, id: profile.id) }
        do {
            let client = PanelAPI(base: url, key: "", amp: amp)
            let rows = try await client.list("")
            profiles.append(profile); selected = profile.id; demo = false; api = client; servers = rows.map { Server(data: $0) }; presence = [:]; persist()
        } catch { Vault.delete(profile.id); throw error }
    }
    func remove(_ profile: PanelProfile) {
        WidgetPublisher.remove(panel: profile.url)
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
            guard self.api === api, refreshID == requestID, !Task.isCancelled else { return }; servers = result; error = nil
        } catch {
            if !RequestFailure.isCancellation(error) && !Task.isCancelled && self.api === api && refreshID == requestID { self.error = error.localizedDescription }
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
