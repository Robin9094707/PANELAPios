import Foundation
import Security
import CryptoKit
import UIKit

struct SuiteConnection: Codable, Identifiable, Equatable {
    var id = UUID()
    let panel: String
    let server: String
    var endpoint: String
    var fingerprint: String
    var serverIdentity: String
    var useForModeration = true
}
@MainActor final class SuiteConnections: ObservableObject {
    static let shared = SuiteConnections()
    @Published private(set) var values: [SuiteConnection]
    private let defaults: UserDefaults
    init(defaults: UserDefaults = .standard) { self.defaults = defaults; values = (try? JSONDecoder().decode([SuiteConnection].self, from: defaults.data(forKey: "adminSuite.connections.v1") ?? Data())) ?? [] }
    func get(_ api: PanelAPI, _ server: Server) -> SuiteConnection? { guard api.amp == nil else { return nil }; return values.first { $0.panel == api.base.absoluteString && $0.server == server.id } }
    func save(_ record: SuiteConnection, token: String) throws {
        try Vault.save(token, id: record.id)
        var next = values; next.removeAll { $0.panel == record.panel && $0.server == record.server }; next.append(record)
        defaults.set(try JSONEncoder().encode(next), forKey: "adminSuite.connections.v1"); values = next
    }
    func remove(_ record: SuiteConnection) { Vault.delete(record.id); values.removeAll { $0.id == record.id }; persist() }
    func moderation(_ record: SuiteConnection, enabled: Bool) { if let i = values.firstIndex(where: { $0.id == record.id }) { values[i].useForModeration = enabled; persist() } }
    private func persist() { if let data = try? JSONEncoder().encode(values) { defaults.set(data, forKey: "adminSuite.connections.v1") } }
}
final class SuiteTransport: NSObject, URLSessionDelegate, URLSessionTaskDelegate, @unchecked Sendable {
    let pin: String
    let host: String
    init(pin: String, host: String) { self.pin = pin; self.host = host }
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
    func urlSession(_ session: URLSession, didReceive challenge: URLAuthenticationChallenge, completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust else { completionHandler(.performDefaultHandling, nil); return }
        guard !pin.isEmpty else { completionHandler(.performDefaultHandling, nil); return }
        guard challenge.protectionSpace.host.lowercased() == host.lowercased(), let trust = challenge.protectionSpace.serverTrust,
              let certificate = (SecTrustCopyCertificateChain(trust) as? [SecCertificate])?.first else { completionHandler(.cancelAuthenticationChallenge, nil); return }
        let hash = SHA256.hash(data: SecCertificateCopyData(certificate) as Data).map { String(format: "%02x", $0) }.joined()
        guard hash == pin else { completionHandler(.cancelAuthenticationChallenge, nil); return }
        completionHandler(.useCredential, URLCredential(trust: trust))
    }
}
/// Admin Suite 9.8: the server needs another sign-in step (new password after an emergency reset, or a second factor).
struct SuiteLoginChallenge: Error, Identifiable, Equatable, LocalizedError {
    enum Kind: Equatable { case newPassword, secondFactor }
    let kind: Kind
    let challenge: String
    let username: String
    let methods: [String]
    let message: String
    var id: String { challenge }
    var errorDescription: String? { kind == .newPassword ? "Bitte ein neues Passwort festlegen." : "Bitte den Code aus deiner Authenticator-App eingeben." }
    static func from(_ json: JSON) -> SuiteLoginChallenge? {
        let challenge = json["challenge"].string
        guard !challenge.isEmpty, challenge.count <= 200 else { return nil }
        if json["passwordChange"].bool { return SuiteLoginChallenge(kind: .newPassword, challenge: challenge, username: json["username"].string, methods: [], message: json["message"].string) }
        if json["mfa"].bool { return SuiteLoginChallenge(kind: .secondFactor, challenge: challenge, username: json["username"].string, methods: json["methods"].array.map(\.string), message: json["message"].string) }
        return nil
    }
}

/// Device details sent with every Suite request so the main admin can recognise devices (model, OS, app version).
enum SuiteDevice {
    @MainActor static var header: String {
        var info = utsname(); uname(&info)
        let machine = withUnsafeBytes(of: &info.machine) { raw in String(decoding: raw.prefix { $0 != 0 }, as: UTF8.self) }
        let model = ProcessInfo.processInfo.environment["SIMULATOR_MODEL_IDENTIFIER"] ?? machine
        let os = UIDevice.current.systemName + " " + UIDevice.current.systemVersion
        let app = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""
        return "model=" + encode(model) + ";os=" + encode(os) + ";app=" + encode(app)
    }
    nonisolated static func encode(_ value: String) -> String { value.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? "" }
}

@MainActor final class SuiteAPI {
    let connection: SuiteConnection
    let platforms = SuitePlatformCache()
    let demo: Bool
    private(set) var scope = "read"
    private(set) var permissions: Set<String> = ["*"]
    private(set) var limited = false
    func can(_ permission: String) -> Bool { !limited || permissions.contains("*") || permissions.contains(permission) }
    private(set) var features: Set<String> = []
    /// 9.8 account summary from /v1/health (kind user/key, owner, manager, mfa, canMigrate).
    private(set) var account: JSON = .null
    private(set) var web: JSON = .null
    private var token: String
    private let session: URLSession
    init(_ connection: SuiteConnection, token: String? = nil, session: URLSession? = nil, demo: Bool = false, timeout: TimeInterval = 110) throws {
        self.demo = demo
        self.connection = connection; self.token = try token ?? Vault.read(connection.id)
        let url = try Self.endpoint(connection.endpoint)
        let config = URLSessionConfiguration.ephemeral; config.timeoutIntervalForRequest = timeout; config.urlCache = nil
        let validatedPin = try Self.pin(connection.fingerprint)
        self.session = session ?? URLSession(configuration: config, delegate: SuiteTransport(pin: validatedPin, host: url.host ?? ""), delegateQueue: nil)
    }
    nonisolated static func endpoint(_ text: String) throws -> URL {
        var url = try PanelAPI.normalize(text)
        if url.path.hasSuffix("/v1") { url.deleteLastPathComponent() }
        guard let c = URLComponents(url: url, resolvingAgainstBaseURL: false), c.query == nil, c.fragment == nil else { throw PanelError(message: "Gültige HTTPS-Adresse der Admin Suite erforderlich.") }
        return url
    }
    nonisolated static func pin(_ text: String) throws -> String {
        let value = text.lowercased().replacingOccurrences(of: ":", with: "").filter { !$0.isWhitespace }
        guard value.isEmpty || value.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil else { throw PanelError(message: "Der SHA-256-Fingerabdruck muss genau 64 Hex-Zeichen enthalten.") }
        return value
    }
    func request(_ path: String, method: String = "GET", query: [String: String] = [:], body: JSON? = nil) async throws -> JSON {
        if demo { guard method == "GET" else { throw PanelError(message: "Änderungen sind im Demomodus deaktiviert.") }; return SuiteDemo.response(path, query: query) }
        let data = try await transfer(path, method: method, query: query, body: try body.map { try JSONEncoder().encode($0) }, contentType: "application/json", limit: 8 * 1024 * 1024)
        return try JSONDecoder().decode(JSON.self, from: data)
    }
    /// Binary GET (ticket attachments), bounded to 16 MiB.
    func raw(_ path: String, query: [String: String] = [:]) async throws -> Data {
        guard !demo else { throw PanelError(message: "Im Demomodus nicht verfügbar.") }
        return try await transfer(path, method: "GET", query: query, body: nil, contentType: nil, limit: 16 * 1024 * 1024)
    }
    /// Binary POST (ticket attachments) with a JSON reply.
    func upload(_ path: String, query: [String: String], data: Data, contentType: String) async throws -> JSON {
        guard !demo else { throw PanelError(message: "Änderungen sind im Demomodus deaktiviert.") }
        return try JSONDecoder().decode(JSON.self, from: try await transfer(path, method: "POST", query: query, body: data, contentType: contentType, limit: 8 * 1024 * 1024))
    }
    private func transfer(_ path: String, method: String, query: [String: String], body: Data?, contentType: String?, limit: Int) async throws -> Data {
        guard !path.contains(".."), !path.contains("://"), !path.contains("?") else { throw PanelError(message: "Ungültiger Suite-Pfad.") }
        var c = URLComponents(url: try Self.endpoint(connection.endpoint).appendingPathComponent("v1").appendingPathComponent(path), resolvingAgainstBaseURL: false)!
        c.queryItems = query.isEmpty ? nil : query.sorted(by: { $0.key < $1.key }).map { URLQueryItem(name: $0.key, value: $0.value) }
        // "+" is literal in URLComponents but a space for the suite's form decoder.
        c.percentEncodedQuery = c.percentEncodedQuery?.replacingOccurrences(of: "+", with: "%2B")
        var request = URLRequest(url: c.url!); request.httpMethod = method; request.setValue("Bearer " + token, forHTTPHeaderField: "Authorization"); request.setValue("application/json", forHTTPHeaderField: "Accept"); request.setValue(SuiteDevice.header, forHTTPHeaderField: "X-Simplex-Device")
        if let body { request.httpBody = body; request.setValue(contentType ?? "application/octet-stream", forHTTPHeaderField: "Content-Type") }
        let data: Data
        let response: URLResponse
        do {
            let pin = try Self.pin(connection.fingerprint)
            if pin.isEmpty { (data, response) = try await session.data(for: request) }
            else { (data, response) = try await PinnedHTTPS.load(request, pin: pin) }
        }
        catch let error as URLError {
            if Task.isCancelled || error.code == .cancelled { throw CancellationError() }
            if [.serverCertificateUntrusted, .serverCertificateHasBadDate, .serverCertificateHasUnknownRoot, .secureConnectionFailed, .cancelled].contains(error.code) { throw PanelError(message: "HTTPS-Verbindung konnte nicht bestätigt werden. Prüfe Adresse, Port und den SHA-256-Fingerabdruck aus /sas app fingerprint. Ein geändertes Zertifikat wird nicht automatisch akzeptiert.") }
            throw error
        }
        try PanelAPI.validate(data, response: response)
        guard data.count <= limit else { throw PanelError(message: "Suite-Antwort zu groß.") }
        return data
    }
    /// Password sign-in. Throws `SuiteLoginChallenge` when a new password or a second factor is required (Admin Suite 9.8).
    func login(username: String, password: String) async throws -> String {
        try accept(try await request("login", method: "POST", body: .object(["username": .string(username), "password": .string(password)])))
    }
    func verify(challenge: String, code: String) async throws -> String {
        try accept(try await request("login/verify", method: "POST", body: .object(["challenge": .string(challenge), "code": .string(code)])))
    }
    func verify(challenge: String, newPassword: String) async throws -> String {
        try accept(try await request("login/verify", method: "POST", body: .object(["challenge": .string(challenge), "newPassword": .string(newPassword)])))
    }
    /// One-time code from the browser sign-in (passkeys) or the web panel.
    func exchange(code: String) async throws -> String {
        try accept(try await request("login/exchange", method: "POST", body: .object(["code": .string(code)])))
    }
    private func accept(_ result: JSON) throws -> String {
        if let challenge = SuiteLoginChallenge.from(result) { throw challenge }
        let value = result["token"].string
        guard value.hasPrefix("sasu_"), value.count <= 160 else { throw PanelError(message: "Ungültige Login-Antwort.") }
        token = value
        return value
    }
    /// Turns the classic main key into the main admin account and switches this connection to the new session.
    func migrate(username: String, password: String, revokeKey: Bool) async throws -> JSON {
        let result = try await request("account/migrate", method: "POST", body: .object(["username": .string(username), "password": .string(password), "revokeKey": .bool(revokeKey)]))
        let value = result["token"].string
        guard value.hasPrefix("sasu_"), value.count <= 160 else { throw PanelError(message: "Ungültige Antwort der Suite.") }
        token = value
        return result
    }
    func post(_ path: String, _ body: [String: JSON] = [:]) async throws -> JSON { try await request(path, method: "POST", body: .object(body)) }
    /// Redeems a one-time setup link (Admin Suite 9.7.0) and keeps the new session token.
    func redeem(code: String) async throws -> JSON {
        let result = try await request("setup", method: "POST", body: .object(["code": .string(code)]))
        let value = result["token"].string
        guard value.hasPrefix("sasu_"), value.count <= 160 else { throw PanelError(message: "Ungültige Antwort auf den Setup-Link.") }
        token = value
        return result
    }
    var sessionToken: String { token }
    func health() async throws -> JSON {
        let result = try await request("health")
        guard result["apiVersion"].int == 1, !result["serverId"].string.isEmpty else { throw PanelError(message: "Diese Adresse liefert keine kompatible Simplex-Admin-Suite-API.") }
        guard connection.serverIdentity.isEmpty || connection.serverIdentity == result["serverId"].string else { throw PanelError(message: "Die Serveridentität hat sich geändert. Kopplung prüfen und bewusst neu einrichten.") }
        limited = result["limited"].bool
        permissions = Set(result["permissions"].array.map(\.string))
        scope = result["scope"].string
        features = Set(result["features"].array.map(\.string))
        account = result["account"]
        web = result["web"]
        return result
    }
    func view(_ path: String, values: [String: JSON] = [:]) async throws -> JSON { try await request("view", query: values.mapValues(\.string).merging(["path": path], uniquingKeysWith: { _, b in b })) }
    func prepare(_ path: String, values: [String: JSON]) async throws -> JSON { _ = try await health(); return try await request("prepare", method: "POST", body: .object(["path": .string(path), "values": .object(values)])) }
    func execute(_ ticket: String) async throws -> JSON { try await request("execute", method: "POST", body: .object(["ticket": .string(ticket)])) }
    var playerDirectory: SuiteDirectory { SuiteDirectory.shared(key: connection.panel + "|" + connection.server + "|" + connection.endpoint + "|" + connection.fingerprint + "|" + SHA256.hash(data: Data(token.utf8)).map { String(format: "%02x", $0) }.joined()) }
    func data(_ name: String, query: [String: String] = [:]) async throws -> JSON { try await request("data/" + name, query: query) }
}
struct SuiteAction: Identifiable {
    let data: JSON
    var id: String { data["id"].string }
    var title: String { data["description"].string.isEmpty ? data["name"].string : data["description"].string }
    var fields: [JSON] { data["options"].array }
    var group: String { data["group"].string }
    static func make(_ id: String, _ title: String, fields: [JSON] = []) -> SuiteAction { SuiteAction(data: .object(["id": .string(id), "description": .string(title), "options": .array(fields)])) }
}
