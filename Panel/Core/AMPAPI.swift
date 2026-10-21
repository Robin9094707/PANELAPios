import Foundation

// AMP's public JSON API. No private frontend endpoints or version-specific binaries.
enum PanelProvider: String, Codable, CaseIterable, Identifiable {
    case pterodactyl, amp
    var id: String { rawValue }
    var title: String { self == .amp ? "CubeCoders AMP" : "Pterodactyl" }
}
struct AMPCredentials: Codable {
    var username: String
    var password: String = ""
    var token: String = ""
    var encoded: String { String(decoding: try! JSONEncoder().encode(self), as: UTF8.self) }
}
// Session-bearing POSTs may never follow redirects (including 307/308) to another origin.
final class AMPRedirect: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}
@MainActor final class AMPAPI {
    let base: URL
    private var credentials: AMPCredentials
    private var otp: String
    private let session: URLSession
    private var sessions: [String: String] = [:]
    private var logins: [String: Task<String, Error>] = [:]
    private var specs: [String: JSON] = [:]
    private(set) var module: JSON = .null
    private(set) var instances: [String: JSON] = [:]
    private var discovered = false
    var persistCredential: ((String) throws -> Void)?
    var controller: Bool { ["ads", "adsmodule"].contains(module["ModuleName"].string.lowercased()) || ["ads", "adsmodule"].contains(module["Name"].string.lowercased()) }
    init(base: URL, secret: String, otp: String = "", session: URLSession? = nil) throws {
        self.base = base; self.credentials = try JSONDecoder().decode(AMPCredentials.self, from: Data(secret.utf8)); self.otp = otp
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 30; config.timeoutIntervalForResource = 180; config.urlCache = nil
        self.session = session ?? URLSession(configuration: config, delegate: AMPRedirect(), delegateQueue: nil)
    }
    static func normalize(_ text: String) throws -> URL {
        var url = try PanelAPI.normalize(text)
        if url.lastPathComponent.lowercased() == "api" { url.deleteLastPathComponent() }
        return try PanelAPI.normalize(url.absoluteString)
    }
    static func unwrap(_ value: JSON) -> JSON {
        if value.object.count == 1, value["result"] != .null { return value["result"] }
        return value
    }
    private func post(_ endpoint: String, scope: String, values: [String: JSON]) async throws -> JSON {
        guard endpoint.split(separator: "/").count == 2, !endpoint.contains(".."), !endpoint.contains("?"), !scope.contains("/"), !scope.contains("..") else { throw PanelError(message: "Ungültiger AMP-API-Pfad.") }
        var url = base
        if !scope.isEmpty { url.appendPathComponent("API/ADSModule/Servers/" + scope) }
        url.appendPathComponent("API/" + endpoint)
        var request = URLRequest(url: url); request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/vnd.cubecoders-ampapi", forHTTPHeaderField: "Accept")
        request.setValue("SimplexPanel/1.9 AMPAPI", forHTTPHeaderField: "User-Agent")
        request.httpBody = try JSONEncoder().encode(JSON.object(values))
        let (data, response) = try await session.data(for: request)
        try PanelAPI.validate(data, response: response)
        guard data.count <= 16 * 1024 * 1024 else { throw PanelError(message: "AMP-Antwort zu groß.") }
        if data.isEmpty { return .null }
        guard let parsed = try? JSONDecoder().decode(JSON.self, from: data) else { throw PanelError(message: "Keine AMP-API-Antwort. Prüfe die HTTPS-Adresse und /API im Browser.") }
        return Self.unwrap(parsed)
    }
    private func login(_ scope: String) async throws -> String {
        if let task = logins[scope] { return try await task.value }
        let task = Task<String, Error> { @MainActor in
            var user = credentials.username, password = credentials.password, token = credentials.token
            if !scope.isEmpty {
                // ADS supplies a short-lived token for this specific instance.
                let managed = try await call("ADSModule/ManageInstance", values: ["InstanceId": .string(scope)])
                token = managed["Result"].string
                guard !token.isEmpty else { throw PanelError(message: "AMP konnte die Instanz nicht öffnen. Prüfe Instanzstatus und Zugriffsrechte.") }
                password = ""; user = credentials.username
            } else if !token.isEmpty { password = "" }
            let result = try await post("Core/Login", scope: scope, values: ["username": .string(user), "password": .string(password), "token": .string(token.isEmpty ? otp : token), "rememberMe": .bool(scope.isEmpty)])
            guard result["success"].bool, !result["sessionID"].string.isEmpty else {
                let reason = result["resultReason"].string
                throw PanelError(message: "AMP-Anmeldung fehlgeschlagen. " + (reason.isEmpty ? "Benutzername, Passwort und gegebenenfalls 2FA-Code prüfen. Bei abgelaufenem Zugang in den Einstellungen neu anmelden." : reason))
            }
            if scope.isEmpty {
                otp = ""
                if !result["rememberMeToken"].string.isEmpty {
                    credentials.token = result["rememberMeToken"].string; credentials.password = ""
                    try persistCredential?(credentials.encoded)
                }
            }
            sessions[scope] = result["sessionID"].string
            return result["sessionID"].string
        }
        logins[scope] = task
        defer { logins[scope] = nil }
        return try await task.value
    }
    private func scope(_ server: String?) -> String { controller ? (server ?? "") : "" }
    static func expired(_ value: JSON) -> Bool {
        let text = (value["Message"].string + " " + value["Reason"].string).lowercased()
        return text.contains("session.exists") || text.contains("session has expired") || text.contains("session expired")
    }
    func call(_ endpoint: String, server: String? = nil, values: [String: JSON] = [:]) async throws -> JSON {
        let target = scope(server)
        for attempt in 0...1 {
            let sessionID: String
            if let existing = sessions[target] { sessionID = existing } else { sessionID = try await login(target) }
            var body = values; body["SESSIONID"] = .string(sessionID)
            let result = try await post(endpoint, scope: target, values: body)
            if Self.expired(result), attempt == 0 { sessions[target] = nil; continue }
            if result["Status"] == .bool(false) || result["success"] == .bool(false) || result["Title"] == .string("UnauthorizedAccessException") || result["ExceptionType"] != .null || result["Message"] != .null && result["StackTrace"] != .null {
                let reason = [result["Reason"].string, result["Message"].string, result["resultReason"].string].first { !$0.isEmpty } ?? "Aktion von AMP abgelehnt. Prüfe deine Berechtigungen."
                throw PanelError(message: "AMP: " + String(reason.prefix(1500)))
            }
            return result
        }
        throw PanelError(message: "AMP-Sitzung abgelaufen. Bitte erneut anmelden.")
    }
    func discover() async throws -> [JSON] {
        if !discovered { module = try await call("Core/GetModuleInfo"); discovered = true }
        let rows: [JSON]
        if controller {
            let targets = try await call("ADSModule/GetInstances", values: ["ForceIncludeSelf": .bool(false)])
            rows = targets.array.flatMap { target -> [JSON] in
                if case .array = target["AvailableInstances"] { return target["AvailableInstances"].array.map { row in var v = row.object; v["TargetName"] = target["FriendlyName"]; return .object(v) } }
                return target["InstanceID"].string.isEmpty ? [] : [target]
            }.filter { $0["Module"].string != "ADS" }
        } else {
            var row = module.object
            row["InstanceID"] = module["InstanceId"]; row["Module"] = module["ModuleName"]
            rows = [.object(row)]
        }
        instances = Dictionary(rows.compactMap { row in let id = row["InstanceID"].string; return id.isEmpty ? nil : (id, row) }, uniquingKeysWith: { a, _ in a })
        return rows.compactMap { row in
            let id = row["InstanceID"].string; guard !id.isEmpty else { return nil }
            return .object(["identifier": .string(id), "name": row["FriendlyName"].string.isEmpty ? row["InstanceName"] : row["FriendlyName"], "node": row["TargetName"].string.isEmpty ? .string(base.host ?? "AMP") : row["TargetName"], "description": row["Description"], "invocation": row["Module"], "amp": row, "limits": .object(["memory": row["ContainerMemoryMB"], "cpu": .number(row["ContainerCPUs"].double * 100)]), "is_suspended": row["Suspended"]])
        }
    }
    func spec(server: String) async throws -> JSON {
        if let cached = specs[server] { return cached }
        let result = try await call("Core/GetAPISpec", server: server); specs[server] = result; return result
    }
    func service(_ endpoint: String, server: String) async throws {
        guard controller, let instance = instances[server] else { throw PanelError(message: "Instanzverwaltung benötigt den AMP-Controller.") }
        _ = try await call("ADSModule/" + endpoint, values: ["InstanceName": instance["InstanceName"]]); sessions[server] = nil; specs[server] = nil
    }
    static func state(_ value: JSON) -> String {
        switch value.string.lowercased() {
        case "0", "stopped", "50", "sleeping": return "offline"
        case "20", "ready", "running": return "running"
        case "5", "7", "10", "30", "prestart", "configuring", "starting", "restarting": return "starting"
        case "40", "45", "stopping", "preparingforsleep": return "stopping"
        default: return "unknown"
        }
    }
    static func resources(_ value: JSON) -> JSON {
        let metrics = value["Metrics"]
        func metric(_ names: [String]) -> JSON { for name in names { if metrics[name] != .null { return metrics[name] } }; return .null }
        let memory = metric(["Memory Usage", "Memory", "RAM"]), cpu = metric(["CPU Usage", "CPU"]), disk = metric(["Disk Usage", "Disk"])
        func bytesValue(_ metric: JSON) -> Double {
            let unit = metric["Units"].string.lowercased()
            let multiplier: Double = unit.contains("gb") || unit.contains("gib") ? 1_073_741_824 : unit.contains("kb") || unit.contains("kib") ? 1024 : unit.contains("mb") || unit.contains("mib") ? 1_048_576 : 1
            return metric["RawValue"].double * multiplier
        }
        let parts = value["Uptime"].string.split(separator: ":").compactMap { Double($0) }
        let uptime = parts.count == 3 ? parts[0] * 3600 + parts[1] * 60 + parts[2] : 0
        return .object(["current_state": .string(state(value["State"])), "resources": .object(["cpu_absolute": cpu["RawValue"], "memory_bytes": .number(bytesValue(memory)), "disk_bytes": .number(bytesValue(disk)), "uptime": .number(uptime * 1000)]), "amp_metrics": metrics])
    }
    static func filePath(_ path: String) throws -> String {
        guard !path.contains("\\"), !path.contains("\0"), !path.split(separator: "/").contains("..") else { throw PanelError(message: "Ungültiger Dateipfad.") }
        return path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
    }
    func listing(server: String, directory: String) async throws -> [JSON] {
        let rows = try await call("FileManagerPlugin/GetDirectoryListing", server: server, values: ["Dir": .string(try Self.filePath(directory))])
        return rows.array.map { row in .object(["name": row["Filename"], "is_file": .bool(!row["IsDirectory"].bool), "size": row["SizeBytes"], "modified_at": row["Modified"], "created_at": row["Created"], "amp": row]) }
    }
    func fileData(server: String, path: String, limit: Int) async throws -> Data {
        let name = try Self.filePath(path)
        let parent = (name as NSString).deletingLastPathComponent
        let rows = try await listing(server: server, directory: parent)
        guard let entry = rows.first(where: { $0["name"].string == (name as NSString).lastPathComponent }), entry["is_file"].bool else { throw PanelError(message: "Datei nicht gefunden: " + name) }
        let size = entry["size"].int
        guard size >= 0, size <= limit else { throw PanelError(message: "Datei größer als das Leselimit (\(bytes(Double(limit)))).") }
        var data = Data(); data.reserveCapacity(size)
        while data.count < size {
            try Task.checkCancellation()
            let count = min(512 * 1024, size - data.count)
            let result = try await call("FileManagerPlugin/ReadFileChunk", server: server, values: ["Filename": .string(name), "Offset": .number(Double(data.count)), "ChunkSize": .number(Double(count))])
            let encoded = result["Result"].string.isEmpty ? result.string : result["Result"].string
            guard let chunk = Data(base64Encoded: encoded), chunk.count == count else { throw PanelError(message: "Unvollständiger AMP-Dateidownload. Bitte erneut versuchen.") }
            data.append(chunk)
        }
        return data
    }
    func write(server: String, path: String, data: Data) async throws {
        let name = try Self.filePath(path)
        var offset = 0
        repeat {
            try Task.checkCancellation()
            let end = min(data.count, offset + 512 * 1024)
            _ = try await call("FileManagerPlugin/WriteFileChunk", server: server, values: ["Filename": .string(name), "Data": .string(data.subdata(in: offset..<end).base64EncodedString()), "Offset": .number(Double(offset)), "FinalChunk": .bool(end == data.count)])
            offset = end
        } while offset < data.count
    }
    func rename(server: String, from: String, to: String, directory: Bool = false) async throws {
        let values: [String: JSON] = directory ? ["oldDirectory": .string(try Self.filePath(from)), "NewDirectoryName": .string(try Self.filePath(to))] : ["Filename": .string(try Self.filePath(from)), "NewFilename": .string(try Self.filePath(to))]
        _ = try await call("FileManagerPlugin/" + (directory ? "RenameDirectory" : "RenameFile"), server: server, values: values)
    }
    // Shared app operations are translated at the provider boundary; unknown operations never fall through.
    func request(_ path: String, method: String, query: [String: String], body: JSON?, text: String?, binary: Data?) async throws -> JSON {
        if path.isEmpty { return .array(try await discover()) }
        let parts = path.split(separator: "/").map(String.init)
        guard parts.count >= 2, parts[0] == "servers" else { throw PanelError(message: "Diese Funktion wird im AMP-Webpanel verwaltet.") }
        let id = parts[1], tail = parts.dropFirst(2).joined(separator: "/"), b = body ?? .null
        if tail.isEmpty { return .object(["attributes": .object([:])]) }
        switch tail {
        case "resources":
            if controller, instances[id]?["Running"] == .bool(false) { return Self.resources(.object(["State": .number(0)])) }
            return Self.resources(try await call("Core/GetStatus", server: id))
        case "command": return try await call("Core/SendConsoleMessage", server: id, values: ["message": b["command"]])
        case "power":
            let action = ["start": "Start", "stop": "Stop", "restart": "Restart", "kill": "Kill"][b["signal"].string]
            guard let action else { throw PanelError(message: "Ungültige Serveraktion.") }
            if action == "Start", controller, instances[id]?["Running"] == .bool(false) { try await service("StartInstance", server: id); _ = try await discover() }
            return try await call("Core/" + action, server: id)
        case "files/list": return .array(try await listing(server: id, directory: query["directory"] ?? "/"))
        case "files/write": try await write(server: id, path: query["file"] ?? "", data: binary ?? Data((text ?? "").utf8)); return .null
        case "files/create-folder": return try await call("FileManagerPlugin/CreateDirectory", server: id, values: ["NewPath": .string(try Self.filePath(pathJoin(b["root"].string, b["name"].string)))])
        case "files/rename":
            let root = b["root"].string
            for file in b["files"].array {
                let from = pathJoin(root, file["from"].string), to = pathJoin(root, file["to"].string)
                let targets = try await listing(server: id, directory: parentFolder(to))
                guard !targets.contains(where: { $0["name"].string == (to as NSString).lastPathComponent }) else { throw PanelError(message: "Zieldatei existiert bereits. Es wird nichts überschrieben.") }
                let rows = try await listing(server: id, directory: parentFolder(from))
                guard let entry = rows.first(where: { $0["name"].string == (from as NSString).lastPathComponent }) else { throw PanelError(message: "Quelldatei nicht gefunden.") }
                try await rename(server: id, from: from, to: to, directory: !entry["is_file"].bool)
            }; return .null
        case "files/delete":
            let rows = try await listing(server: id, directory: b["root"].string)
            for file in b["files"].array {
                let directory = rows.first(where: { $0["name"].string == file.string })?["is_file"] == .bool(false)
                _ = try await call("FileManagerPlugin/" + (directory ? "TrashDirectory" : "TrashFile"), server: id, values: [directory ? "DirectoryName" : "Filename": .string(try Self.filePath(pathJoin(b["root"].string, file.string)))])
            }; return .null
        case "files/copy": return try await call("FileManagerPlugin/CopyFile", server: id, values: ["Origin": .string(try Self.filePath(b["location"].string)), "TargetDirectory": .string(try Self.filePath(parentFolder(b["location"].string)))])
        case "files/pull":
            guard let source = URL(string: b["url"].string), source.scheme == "https" else { throw PanelError(message: "HTTPS-Downloadadresse erforderlich.") }
            return try await call("FileManagerPlugin/DownloadFileFromURL", server: id, values: ["Source": b["url"], "TargetDirectory": .string(try Self.filePath(b["directory"].string))])
        case "files/decompress": return try await call("FileManagerPlugin/ExtractArchive", server: id, values: ["ArchivePath": .string(try Self.filePath(pathJoin(b["root"].string, b["file"].string))), "DestinationPath": .string(try Self.filePath(b["root"].string))])
        case "files/compress":
            guard b["files"].array.count == 1, let file = b["files"].array.first else { throw PanelError(message: "AMP komprimiert jeweils eine Datei oder einen Ordner. Wähle genau einen Eintrag.") }
            return try await call("FileManagerPlugin/CreateArchive", server: id, values: ["PathToArchive": .string(try Self.filePath(pathJoin(b["root"].string, file.string)))])
        default: throw PanelError(message: "Diese Funktion wird im AMP-Webpanel verwaltet.")
        }
    }
}
