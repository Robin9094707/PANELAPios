import Foundation
import Combine

@MainActor final class LiveServer: ObservableObject {
    @Published var stats: JSON = .null
    @Published var state = "unknown"
    @Published var connection = "Verbinden …"
    @Published var connected = false
    @Published var lines: [String] = []
    @Published var logRevision = 0
    @Published var history: [ResourcePoint] = []
    @Published var lastUpdate: Date?
    @Published var issue: String?
    @Published var players: PlayerSnapshot?
    @Published var playerIssue: String?
    private var socket: URLSessionWebSocketTask?
    private var runID = UUID()
    private var active = false
    var memory: Double { stats["memory_bytes"].double }
    var disk: Double { stats["disk_bytes"].double }
    var cpu: Double { stats["cpu_absolute"].double }
    var uptime: Double { stats["uptime"].double / 1000 }
    func stop() { active = false; runID = UUID(); socket?.cancel(with: .goingAway, reason: nil); socket = nil; connected = false }
    func run(api: PanelAPI, server: Server, minecraft: Bool = false) async {
        stop(); active = true; players = nil; playerIssue = nil; let generation = UUID(); runID = generation
        if api.demo {
            connected = true; connection = "Demo · Beispieldaten"; state = "running"
            players = minecraft ? PlayerSnapshot(online: 2, maximum: 60, names: ["TadMC_YT", ".niex2277"], source: "Demo") : nil
            lines = ["[22:37:00 INFO]: Starting minecraft server", "[22:37:02 INFO]: Loading properties", "[22:37:05 INFO]: Simplex SMP is ready", "[22:37:06 INFO]: Done (4.218s)! For help, type \"help\"", "[22:37:14 INFO]: MissCaitlyn joined the game"]
            while !Task.isCancelled && active && runID == generation {
                stats = try! JSON.from(["memory_bytes": 8_214_000_000.0, "cpu_absolute": Double.random(in: 34...62), "disk_bytes": 23_144_000_000.0, "uptime": 12_814_000, "network": ["rx_bytes": 2_124_000, "tx_bytes": 8_124_000]])
                sample(); try? await Task.sleep(for: .seconds(2))
            }
            return
        }
        // REST fallback and WebSocket run together; cancellation tears both down.
        async let poll: Void = polling(api: api, server: server, generation: generation)
        async let playerPoll: Void = pollingPlayers(api: api, server: server, enabled: minecraft, generation: generation)
        var retry = 1
        while !Task.isCancelled && active && runID == generation {
            do {
                connection = retry == 1 ? "Verbinden …" : "Verbindung wird wiederhergestellt …"
                let auth = try await api.request("servers/\(server.id)/websocket")["data"]
                guard let url = URL(string: auth["socket"].string), url.scheme == "wss", !auth["token"].string.isEmpty else { throw PanelError(message: "Das Panel hat keine sichere WebSocket-Verbindung geliefert.") }
                var request = URLRequest(url: url)
                // Wings may validate Origin. Never forward the panel API key to Wings.
                var origin = URLComponents(url: api.base, resolvingAgainstBaseURL: false)!
                origin.path = ""; origin.query = nil; origin.fragment = nil
                request.setValue(origin.url!.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/")), forHTTPHeaderField: "Origin")
                let ws = URLSession.shared.webSocketTask(with: request); socket = ws; ws.resume()
                try await send(event: "auth", args: [auth["token"].string], to: ws)
                var requestedLogs = false
                while !Task.isCancelled && active && runID == generation {
                    let message = try await ws.receive()
                    let data: Data
                    switch message { case .data(let d): data = d; case .string(let s): data = Data(s.utf8); @unknown default: continue }
                    let event = try JSONDecoder().decode(JSON.self, from: data)
                    let name = event["event"].string; let args = event["args"].array
                    switch name {
                    case "auth success":
                        connected = true; connection = "Live verbunden"; issue = nil; retry = 1
                        if !requestedLogs { try await send(event: "send logs", to: ws); requestedLogs = true }
                        try await send(event: "send stats", to: ws)
                    case "console output", "install output":
                        for arg in args { append(cleanConsole(arg.string)) }
                    case "status": state = args.first?.string ?? "unknown"
                    case "stats":
                        if let text = args.first?.string, let value = try? JSON.parse(text) { stats = value; sample() }
                    case "token expiring", "token expired", "jwt error":
                        let renewed = try await api.request("servers/\(server.id)/websocket")["data"]
                        try await send(event: "auth", args: [renewed["token"].string], to: ws)
                    case "daemon error": issue = "Wings: " + (args.first?.string ?? "Fehler")
                    default: break
                    }
                }
            } catch {
                if Task.isCancelled || !active || runID != generation { break }
                connected = false; connection = "Live getrennt · erneuter Versuch"; issue = error.localizedDescription
            }
            socket?.cancel(with: .goingAway, reason: nil); socket = nil
            try? await Task.sleep(for: .seconds(min(retry * 2, 30))); retry += 1
        }
        socket?.cancel(with: .goingAway, reason: nil); connected = false
        _ = await (poll, playerPoll)
    }
    private func polling(api: PanelAPI, server: Server, generation: UUID) async {
        while !Task.isCancelled && active && runID == generation {
            do {
                let result = try await api.request("servers/\(server.id)/resources").attributes
                if !result["current_state"].string.isEmpty { state = result["current_state"].string }
                if !connected { stats = result["resources"]; sample() }
            } catch { if !connected { issue = error.localizedDescription } }
            try? await Task.sleep(for: .seconds(connected ? 20 : 5))
        }
    }
    private func pollingPlayers(api: PanelAPI, server: Server, enabled: Bool, generation: UUID) async {
        guard enabled else { return }
        var useProvider = true
        while !Task.isCancelled && active && runID == generation {
            if state == "offline" { players = PlayerSnapshot(online: 0, maximum: players?.maximum, names: [], source: "Server offline") }
            else {
                var received = false
                if useProvider {
                    do {
                        let response = try await api.request("servers/\(server.id)/players")
                        if let decoded = PlayerSnapshot.decode(response) { players = decoded; playerIssue = nil; received = true }
                        else { useProvider = false }
                    } catch {
                        if Task.isCancelled { return }
                        if error.localizedDescription.contains("404") || error.localizedDescription.contains("403") || error.localizedDescription.contains("501") { useProvider = false }
                        playerIssue = "Spieler-API nicht verfügbar; Aktualisierung über list bei laufendem Server."
                    }
                }
                if !received && connected && state == "running" {
                    do { try await api.mutate("servers/\(server.id)/command", ["command": "list"]) }
                    catch { playerIssue = error.localizedDescription }
                }
            }
            do { try await Task.sleep(for: .seconds(useProvider ? 15 : 30)) } catch { return }
        }
    }
    private func send(event: String, args: [String] = [], to socket: URLSessionWebSocketTask) async throws {
        let data = try JSONEncoder().encode(JSON.object(["event": .string(event), "args": .array(args.map(JSON.string))]))
        try await socket.send(.string(String(decoding: data, as: UTF8.self)))
    }
    private func sample() {
        lastUpdate = Date()
        history.append(ResourcePoint(date: Date(), cpu: cpu, memory: memory))
        if history.count > 120 { history.removeFirst(history.count - 120) }
    }
    private func append(_ text: String) {
        for line in text.components(separatedBy: .newlines) {
            if let parsed = PlayerSnapshot.parseLog(line) { players = parsed; playerIssue = nil }
        }
        // Bound individual messages as well as the entire scrollback.
        lines += text.suffix(64_000).components(separatedBy: "\n")
        if lines.count > 1500 { lines.removeFirst(lines.count - 1500) }
        logRevision &+= 1
    }
}
