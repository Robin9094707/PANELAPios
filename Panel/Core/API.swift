import Foundation

final class SameOriginRedirect: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        guard let original = task.originalRequest?.url, let next = request.url, next.scheme == "https" else { completionHandler(nil); return }
        if task.originalRequest?.value(forHTTPHeaderField: "Authorization") != nil {
            guard original.scheme == next.scheme, original.host == next.host, original.port == next.port else { completionHandler(nil); return }
            var authenticated = request
            authenticated.setValue(task.originalRequest?.value(forHTTPHeaderField: "Authorization"), forHTTPHeaderField: "Authorization")
            completionHandler(authenticated)
        } else {
            // Signed downloads may redirect to object storage. They never contain the panel API header.
            var unsigned = request; unsigned.setValue(nil, forHTTPHeaderField: "Authorization"); completionHandler(unsigned)
        }
    }
}

@MainActor
final class PanelAPI {
    let access = ServerAccess()
    let base: URL
    private let key: String
    var isKinetic: Bool { base.host?.lowercased() == "kineticpanel.net" || base.host?.lowercased().hasSuffix(".kineticpanel.net") == true }
    let demo: Bool
    private let session: URLSession
    init(base: URL, key: String, demo: Bool = false, session: URLSession? = nil) {
        self.base = base; self.key = key; self.demo = demo
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 30; config.timeoutIntervalForResource = 3600
        config.urlCache = nil
        self.session = session ?? URLSession(configuration: config, delegate: SameOriginRedirect(), delegateQueue: nil)
    }
    nonisolated static func normalize(_ text: String) throws -> URL {
        let raw = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard var c = URLComponents(string: raw.contains("://") ? raw : "https://" + raw),
              c.scheme?.lowercased() == "https", let host = c.host, !host.isEmpty,
              c.user == nil, c.password == nil, c.query == nil, c.fragment == nil else {
            throw PanelError(message: "Gib eine gültige HTTPS-Panel-URL ohne Zugangsdaten ein.")
        }
        c.scheme = "https"
        var path = c.path
        while path.hasSuffix("/") { path.removeLast() }
        if path.hasSuffix("/api/client") { path.removeLast("/api/client".count) }
        c.path = path
        guard let url = c.url else { throw PanelError(message: "Ungültige Panel-URL.") }
        return url
    }
    func url(_ path: String, query: [String: String] = [:]) throws -> URL {
        guard !path.contains(".."), !path.contains("://"), !path.contains("?") else { throw PanelError(message: "Ungültiger API-Pfad.") }
        let suffix = path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        var c = URLComponents(url: base.appendingPathComponent("api/client"), resolvingAgainstBaseURL: false)!
        c.path += suffix.isEmpty ? "" : "/" + suffix
        c.queryItems = query.isEmpty ? nil : query.sorted(by: { $0.key < $1.key }).map { URLQueryItem(name: $0.key, value: $0.value) }
        return c.url!
    }
    func raw(_ path: String, method: String = "GET", query: [String: String] = [:], body: JSON? = nil, text: String? = nil, binary: Data? = nil) async throws -> Data {
        if demo {
            guard method == "GET" else { throw PanelError(message: "Änderungen sind im Demomodus deaktiviert.") }
            return try JSONEncoder().encode(Demo.response(path, query: query))
        }
        guard access.allows(path: path, method: method, body: body) else { throw PanelError(message: "Keine Berechtigung für diese Aktion.") }
        var request = URLRequest(url: try url(path, query: query))
        request.httpMethod = method
        request.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization")
        request.setValue("Application/vnd.pterodactyl.v1+json", forHTTPHeaderField: "Accept")
        if let binary { request.httpBody = binary; request.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type") }
        else if let text { request.httpBody = Data(text.utf8); request.setValue("text/plain; charset=utf-8", forHTTPHeaderField: "Content-Type") }
        else if let body { request.httpBody = try JSONEncoder().encode(body); request.setValue("application/json", forHTTPHeaderField: "Content-Type") }
        let (data, response) = try await session.data(for: request)
        do { try Self.validate(data, response: response) }
        catch {
            if (response as? HTTPURLResponse)?.statusCode == 403,
               let json = try? JSONDecoder().decode(JSON.self, from: data), !json["errors"].array.isEmpty {
                access.recordDenial(path: path, method: method)
            }
            throw error
        }
        return data
    }
    nonisolated static func validate(_ data: Data, response: URLResponse) throws {
        guard let http = response as? HTTPURLResponse else { throw PanelError(message: "Keine HTTP-Antwort erhalten.") }
        guard (200..<300).contains(http.statusCode) else {
            let json = try? JSONDecoder().decode(JSON.self, from: data)
            let details = json?["errors"].array.map { $0["detail"].string }.filter { !$0.isEmpty }.joined(separator: "\n") ?? ""
            let fallback: String
            switch http.statusCode {
            case 401: fallback = "Anmeldung fehlgeschlagen. Prüfe deinen Client-API-Schlüssel."
            case 403: fallback = "Keine Berechtigung. Prüfe die Rechte deines Schlüssels oder eine Schutzseite des Hosters."
            case 404: fallback = "API-Endpunkt nicht gefunden. Prüfe die Panel-URL."
            case 429: fallback = "Zu viele Anfragen. Bitte kurz warten."
            default: fallback = "Panel-Anfrage fehlgeschlagen."
            }
            throw PanelError(message: "HTTP \(http.statusCode): \(details.isEmpty ? fallback : String(details.prefix(1500)))")
        }
    }
    func request(_ path: String, method: String = "GET", query: [String: String] = [:], body: JSON? = nil) async throws -> JSON {
        if !demo && method == "POST" && path.hasSuffix("/power"), let signal = body?["signal"].string {
            let root = String(path.dropLast("/power".count))
            let state = try await request(root + "/resources").attributes["current_state"].string
            guard PowerPolicy.allows(signal, state: state) else { throw PanelError(message: "Die Aktion ist im aktuellen Serverstatus nicht verfügbar. Aktualisiere die Ansicht.") }
        }
        let data = try await raw(path, method: method, query: query, body: body)
        if data.isEmpty { return .null }
        do {
            if data.count > 262144 { return try await Task.detached(priority: .userInitiated) { try JSONDecoder().decode(JSON.self, from: data) }.value }
            return try JSONDecoder().decode(JSON.self, from: data)
        } catch { throw PanelError(message: "Das Panel lieferte keine API-Daten. Verwende die URL des Pterodactyl-Panels, nicht die Hosting-Webseite.") }
    }
    func loadAccess(server: String) async {
        access.clearDenials(server: server)
        if demo { access.ingest(.object(["meta": .object(["is_server_owner": .bool(true)])]), server: server); return }
        if let response = try? await request("servers/" + server) { access.ingest(response, server: server) }
    }
    func list(_ path: String, query: [String: String] = [:]) async throws -> [JSON] {
        var rows: [JSON] = []; var page = 1
        repeat {
            try Task.checkCancellation()
            var q = query; q["page"] = String(page); q["per_page"] = "100"
            let result = try await request(path, query: q)
            rows += result.rows
            let total = result["meta"]["pagination"]["total_pages"].int
            if page >= total { break }
            page += 1
            if page > 1000 { throw PanelError(message: "Zu viele Ergebnisse. Bitte im Panel einschränken.") }
        } while true
        return rows
    }
    func mutate(_ path: String, method: String = "POST", _ body: [String: Any] = [:]) async throws {
        _ = try await request(path, method: method, body: try JSON.from(body))
    }
    func fileData(server: String, path: String, limit: Int = 8 * 1024 * 1024) async throws -> Data {
        if demo && ["dat", "nbt"].contains(URL(fileURLWithPath: path).pathExtension) { return try Demo.playerData() }
        if demo { return Data("# Simplex SMP\nserver-port=25565\nmotd=Welcome to Simplex SMP\nmax-players=60\nonline-mode=true\nview-distance=10\n".utf8) }
        let signed = try await request("servers/\(server)/files/download", query: ["file": path]).attributes["url"].string
        let local = try await download(signed, filename: URL(fileURLWithPath: path).lastPathComponent)
        defer { try? FileManager.default.removeItem(at: local.deletingLastPathComponent()) }
        let size = try local.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
        guard size <= limit else { throw PanelError(message: "Datei größer als das Editorlimit (\(bytes(Double(limit)))). Verwende Herunterladen.") }
        return try Data(contentsOf: local, options: .mappedIfSafe)
    }
    func fileText(server: String, path: String) async throws -> String {
        let data = try await fileData(server: server, path: path)
        guard let text = String(data: data, encoding: .utf8), !text.contains("\0") else { throw PanelError(message: "Diese Datei ist binär. Öffne .dat/.nbt im NBT-Editor oder lade sie herunter.") }
        return text
    }
    func upload(server: String, directory: String, file: URL) async throws {
        if demo { throw PanelError(message: "Uploads sind im Demomodus deaktiviert.") }
        let signed = try await request("servers/\(server)/files/upload").attributes["url"].string
        guard var c = URLComponents(string: signed), c.scheme == "https" else { throw PanelError(message: "Keine sichere Upload-URL erhalten.") }
        var q = c.queryItems ?? []; q.append(URLQueryItem(name: "directory", value: directory)); c.queryItems = q
        guard let target = c.url else { throw PanelError(message: "Ungültige Upload-URL.") }
        let boundary = "Panel-" + UUID().uuidString
        let staging = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        FileManager.default.createFile(atPath: staging.path, contents: nil)
        defer { try? FileManager.default.removeItem(at: staging) }
        let writer = try FileHandle(forWritingTo: staging)
        let name = file.lastPathComponent.replacingOccurrences(of: "\"", with: "_").replacingOccurrences(of: "\r", with: "_").replacingOccurrences(of: "\n", with: "_")
        try writer.write(contentsOf: Data("--\(boundary)\r\nContent-Disposition: form-data; name=\"files\"; filename=\"\(name)\"\r\nContent-Type: application/octet-stream\r\n\r\n".utf8))
        let reader = try FileHandle(forReadingFrom: file)
        do {
            while let chunk = try reader.read(upToCount: 1024 * 1024), !chunk.isEmpty { try Task.checkCancellation(); try writer.write(contentsOf: chunk) }
            try writer.write(contentsOf: Data("\r\n--\(boundary)--\r\n".utf8))
            try reader.close(); try writer.close()
        } catch { try? reader.close(); try? writer.close(); throw error }
        var request = URLRequest(url: target); request.httpMethod = "POST"
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        let (data, response) = try await session.upload(for: request, fromFile: staging)
        try Self.validate(data, response: response)
    }
    func download(_ signed: String, filename: String) async throws -> URL {
        guard let url = URL(string: signed), url.scheme == "https" else { throw PanelError(message: "Keine sichere Download-URL erhalten.") }
        let (local, response) = try await session.download(from: url)
        try Self.validate(Data(), response: response)
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let safeName = URL(fileURLWithPath: filename).lastPathComponent
        let destination = folder.appendingPathComponent(safeName.isEmpty ? "download" : safeName)
        try FileManager.default.moveItem(at: local, to: destination)
        return destination
    }
}
