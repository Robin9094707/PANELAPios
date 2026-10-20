import Foundation
import Security
import CryptoKit

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
    func get(_ api: PanelAPI, _ server: Server) -> SuiteConnection? { values.first { $0.panel == api.base.absoluteString && $0.server == server.id } }
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
@MainActor final class SuiteAPI {
    let connection: SuiteConnection
    private(set) var scope = "read"
    private let token: String
    private let session: URLSession
    init(_ connection: SuiteConnection, token: String? = nil, session: URLSession? = nil) throws {
        self.connection = connection; self.token = try token ?? Vault.read(connection.id)
        let url = try Self.endpoint(connection.endpoint)
        let config = URLSessionConfiguration.ephemeral; config.timeoutIntervalForRequest = 110; config.urlCache = nil
        self.session = session ?? URLSession(configuration: config, delegate: SuiteTransport(pin: try Self.pin(connection.fingerprint), host: url.host ?? ""), delegateQueue: nil)
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
        guard !path.contains(".."), !path.contains("://"), !path.contains("?") else { throw PanelError(message: "Ungültiger Suite-Pfad.") }
        var c = URLComponents(url: try Self.endpoint(connection.endpoint).appendingPathComponent("v1").appendingPathComponent(path), resolvingAgainstBaseURL: false)!
        c.queryItems = query.isEmpty ? nil : query.sorted(by: { $0.key < $1.key }).map { URLQueryItem(name: $0.key, value: $0.value) }
        var request = URLRequest(url: c.url!); request.httpMethod = method; request.setValue("Bearer " + token, forHTTPHeaderField: "Authorization"); request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let body { request.httpBody = try JSONEncoder().encode(body); request.setValue("application/json", forHTTPHeaderField: "Content-Type") }
        let (data, response) = try await session.data(for: request)
        try PanelAPI.validate(data, response: response)
        guard data.count <= 8 * 1024 * 1024 else { throw PanelError(message: "Suite-Antwort zu groß.") }
        return try JSONDecoder().decode(JSON.self, from: data)
    }
    func health() async throws -> JSON {
        let result = try await request("health")
        guard result["apiVersion"].int == 1, !result["serverId"].string.isEmpty else { throw PanelError(message: "Diese Adresse liefert keine kompatible Simplex-Admin-Suite-API.") }
        guard connection.serverIdentity.isEmpty || connection.serverIdentity == result["serverId"].string else { throw PanelError(message: "Die Serveridentität hat sich geändert. Kopplung prüfen und bewusst neu einrichten.") }
        scope = result["scope"].string
        return result
    }
    func view(_ path: String, values: [String: JSON] = [:]) async throws -> JSON { try await request("view", query: values.mapValues(\.string).merging(["path": path], uniquingKeysWith: { _, b in b })) }
    func prepare(_ path: String, values: [String: JSON]) async throws -> JSON { _ = try await health(); return try await request("prepare", method: "POST", body: .object(["path": .string(path), "values": .object(values)])) }
    func execute(_ ticket: String) async throws -> JSON { try await request("execute", method: "POST", body: .object(["ticket": .string(ticket)])) }
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
