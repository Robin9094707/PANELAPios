import Foundation

struct WidgetCredential: Codable {
    var provider: String
    var secret: String
    var suiteToken: String?
}
private final class WidgetRedirect: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}
enum WidgetNetwork {
    static func refresh(_ initial: WidgetServer, session suppliedSession: URLSession? = nil, credential suppliedCredential: WidgetCredential? = nil, includeNames: Bool = true) async -> WidgetServer {
        var record = initial
        guard initial.backgroundRefresh else { return record }
        let stored = WidgetVault.read(record.id) ?? WidgetVault.read(record.panel)
        let credential = suppliedCredential ?? stored.map { (try? JSONDecoder().decode(WidgetCredential.self, from: Data($0.utf8))) ?? WidgetCredential(provider: "pterodactyl", secret: $0) }
        guard let credential, let base = URL(string: record.panel), base.scheme == "https", base.user == nil, base.password == nil else { record.issue = "Widget-Zugang in der App erneuern"; return record }
        let config = URLSessionConfiguration.ephemeral; config.timeoutIntervalForRequest = 8; config.timeoutIntervalForResource = 12; config.urlCache = nil
        let session = suppliedSession ?? URLSession(configuration: config, delegate: WidgetRedirect(), delegateQueue: nil)
        defer { if suppliedSession == nil { session.invalidateAndCancel() } }
        func load(_ request: URLRequest, pin: String = "") async throws -> JSON {
            let data: Data, response: URLResponse
            if pin.isEmpty { (data, response) = try await session.data(for: request) }
            else { (data, response) = try await PinnedHTTPS.load(request, pin: pin) }
            guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode), data.count < 4 * 1024 * 1024 else { throw URLError(.badServerResponse) }
            let parsed = try JSONDecoder().decode(JSON.self, from: data)
            return parsed.object.count == 1 && parsed["result"] != .null ? parsed["result"] : parsed
        }
        func post(_ path: String, values: [String: JSON]) async throws -> JSON {
            var request = URLRequest(url: base.appendingPathComponent(path)); request.httpMethod = "POST"
            request.setValue("application/json", forHTTPHeaderField: "Content-Type"); request.setValue("application/vnd.cubecoders-ampapi", forHTTPHeaderField: "Accept")
            request.httpBody = try JSONEncoder().encode(JSON.object(values))
            let result = try await load(request)
            if result["Status"] == .bool(false) || result["success"] == .bool(false) || result["Message"] != .null && result["StackTrace"] != .null { throw PanelError(message: "AMP-Widget-Zugang prüfen") }
            return result
        }
        func get(_ suffix: String) async throws -> JSON {
            var request = URLRequest(url: base.appendingPathComponent("api/client/servers").appendingPathComponent(record.serverID).appendingPathComponent(suffix))
            request.setValue("Bearer " + credential.secret, forHTTPHeaderField: "Authorization"); request.setValue("application/json", forHTTPHeaderField: "Accept")
            return try await load(request).attributes
        }
        do {
            if credential.provider == "amp" {
                let auth = try JSON.parse(credential.secret)
                let login = try await post("API/Core/Login", values: ["username": auth["username"], "password": auth["password"], "token": auth["token"], "rememberMe": .bool(false)])
                guard login["success"].bool, !login["sessionID"].string.isEmpty else { throw PanelError(message: "AMP-Anmeldung abgelaufen") }
                var sessionID = login["sessionID"], prefix = "API/"
                if record.controller == true {
                    let managed = try await post("API/ADSModule/ManageInstance", values: ["InstanceId": .string(record.serverID), "SESSIONID": sessionID])
                    prefix = "API/ADSModule/Servers/" + record.serverID + "/API/"
                    let child = try await post(prefix + "Core/Login", values: ["username": auth["username"], "password": .string(""), "token": managed["Result"], "rememberMe": .bool(false)])
                    guard child["success"].bool, !child["sessionID"].string.isEmpty else { throw PanelError(message: "AMP-Instanz nicht erreichbar") }; sessionID = child["sessionID"]
                }
                let status = try await post(prefix + "Core/GetStatus", values: ["SESSIONID": sessionID])
                let normalized = AMPMetrics.resources(status), r = normalized["resources"]
                record.state = normalized["current_state"].string
                record.cpu = AMPMetrics.number(r["cpu_absolute"]); record.memory = AMPMetrics.number(r["memory_bytes"]); record.disk = AMPMetrics.number(r["disk_bytes"]); record.uptime = AMPMetrics.number(r["uptime"]).map { $0 / 1000 }
                record.memoryLimit = AMPMetrics.number(r["memory_limit_bytes"]) ?? record.memoryLimit
                if let users = try? await post(prefix + "Core/GetUserList", values: ["SESSIONID": sessionID]) {
                    let names = users.object.values.map(\.string).filter { !$0.isEmpty }.sorted()
                    let count = AMPMetrics.raw(AMPMetrics.metric(status["Metrics"], words: ["Active Users", "Players"]))
                    record.playerNames = names; record.online = count.map(Int.init) ?? names.count; record.playersUpdated = Date()
                }
            } else {
                let result = try await get("resources"), r = result["resources"]
                guard !result["current_state"].string.isEmpty else { throw URLError(.cannotParseResponse) }
                record.state = result["current_state"].string; record.cpu = AMPMetrics.number(r["cpu_absolute"]); record.memory = AMPMetrics.number(r["memory_bytes"]); record.disk = AMPMetrics.number(r["disk_bytes"]); record.uptime = AMPMetrics.number(r["uptime"]).map { $0 / 1000 }
                if let players = try? await get("players") {
                    let value = players["data"] == .null ? players : players["data"].attributes
                    let names = value["players"].array.map { $0["name"].string.isEmpty ? $0.string : $0["name"].string }.filter { !$0.isEmpty }
                    if !names.isEmpty || value["online"] != .null { record.playerNames = names; record.online = AMPMetrics.number(value["online"]).map(Int.init) ?? names.count; record.playersUpdated = Date() }
                }
            }
            if record.state != "offline", let endpoint = record.suiteEndpoint, let url = URL(string: endpoint), url.scheme == "https", let token = credential.suiteToken {
                if let expected = record.suiteIdentity, !expected.isEmpty {
                    var health = URLRequest(url: url.appendingPathComponent("v1/health")); health.setValue("Bearer " + token, forHTTPHeaderField: "Authorization")
                    let response = try await load(health, pin: record.suitePin ?? "")
                    guard response["serverId"].string == expected else { throw PanelError(message: "Simplex-Serveridentität geändert") }
                }
                var overviewRequest = URLRequest(url: url.appendingPathComponent("v1/data/overview"))
                overviewRequest.setValue("Bearer " + token, forHTTPHeaderField: "Authorization")
                if let overview = try? await load(overviewRequest, pin: record.suitePin ?? "") {
                    if let count = AMPMetrics.number(overview["online"]) { record.online = Int(count); record.playersUpdated = Date() }
                    if let maximum = AMPMetrics.number(overview["maxPlayers"]) { record.maximum = Int(maximum) }
                    if let tps = AMPMetrics.number(overview["tps"]), tps > 0 { record.tps = tps; record.tpsUpdated = Date() }
                }
                if includeNames {
                    var names: [String] = [], offset = 0, snapshot = ""
                    for _ in 0..<30 {
                        var c = URLComponents(url: url.appendingPathComponent("v1/data/players"), resolvingAgainstBaseURL: false)!
                        c.queryItems = [URLQueryItem(name: "filter", value: "online"), URLQueryItem(name: "limit", value: "100"),
                                        URLQueryItem(name: "offset", value: String(offset)), URLQueryItem(name: "snapshot", value: snapshot)]
                        var request = URLRequest(url: c.url!); request.setValue("Bearer " + token, forHTTPHeaderField: "Authorization")
                        let result = try await load(request, pin: record.suitePin ?? "")
                        let data = result["data"] == .null ? result : result["data"]
                        let rows = data["items"].array
                        names += rows.filter { $0["online"].bool }.map { $0["name"].string }
                        let next = data["next"].int
                        if rows.contains(where: { !$0["online"].bool }) || data["next"] == .null || next <= offset { break }
                        offset = next; snapshot = data["snapshot"].string
                    }
                    record.playerNames = Array(Set(names)).sorted()
                    if record.playersUpdated == initial.playersUpdated {
                        record.online = record.playerNames?.count; record.playersUpdated = Date()
                    }
                }
            }
            if record.state == "offline" { record.online = 0; record.playerNames = []; record.playersUpdated = Date(); record.tps = nil; record.tpsUpdated = nil; record.uptime = 0 }
            try Task.checkCancellation()
            record.updated = Date(); record.issue = nil
            WidgetStorage.cache(record)
        } catch { record.issue = "Aktualisierung fehlgeschlagen · letzter Stand" }
        return record
    }
}
