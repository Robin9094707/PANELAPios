import Foundation
import Combine

@MainActor final class LiveServer: ObservableObject {
    @Published var stats: JSON = .null
    @Published var state = "unknown" { didSet { if state == "offline" { tps = nil; players = PlayerSnapshot(online: 0, maximum: players?.maximum, names: [], source: "Server offline") } } }
    @Published var connection = "Verbinden …"
    @Published var connected = false
    @Published var lines: [String] = []
    @Published var logRevision = 0
    @Published var history: [ResourcePoint] = []
    @Published var lastUpdate: Date?
    @Published var issue: String?
    @Published var players: PlayerSnapshot?
    @Published var playerCapacity: Int?
    @Published var tps: TPSSnapshot?
    @Published var playerIssue: String?
    @Published var tpsIssue: String?
    @Published var tpsRequested: Date?
    private var tpsPending = false
    private var tpsBusy = false
    private var socket: URLSessionWebSocketTask?
    private var runID = UUID()
    private var active = false
    private struct PlayerQuery {
        let id: UUID
        let player: String
        let result: CheckedContinuation<String, Error>
    }
    private var playerQuery: PlayerQuery?
    private var playerTimeout: Task<Void, Never>?
    var memory: Double { stats["memory_bytes"].double }
    var disk: Double { stats["disk_bytes"].double }
    var cpu: Double { stats["cpu_absolute"].double }
    var uptime: Double { stats["uptime"].double / 1000 }
    func stop() { completePlayerQuery(error: PanelError(message: "Live-Verbindung beendet.")); active = false; runID = UUID(); socket?.cancel(with: .goingAway, reason: nil); socket = nil; connected = false }
    func run(api: PanelAPI, server: Server, minecraft: Bool = false) async {
        stop(); active = true; let generation = UUID(); runID = generation
        await withTaskCancellationHandler {
            await stream(api: api, server: server, minecraft: minecraft, generation: generation)
        } onCancel: {
            Task { @MainActor in self.stop(generation: generation) }
        }
        stop(generation: generation)
    }
    private func stop(generation: UUID) { if runID == generation { stop() } }
    private func stream(api: PanelAPI, server: Server, minecraft: Bool, generation: UUID) async {
        players = nil; playerCapacity = nil; tps = nil; playerIssue = nil; tpsIssue = nil; tpsRequested = nil; tpsPending = false; tpsBusy = false
        if api.demo {
            connected = true; connection = "Demo · Beispieldaten"; state = "running"
            playerCapacity = minecraft ? 60 : nil
            tps = minecraft ? TPSSnapshot(minute: 20, five: 19.98, fifteen: 20) : nil
            players = minecraft ? PlayerSnapshot(online: 2, maximum: 60, names: ["TadMC_YT", ".niex2277"], source: "Demo") : nil
            if lines.isEmpty { lines = ["[22:37:00 INFO]: Starting minecraft server", "[22:37:02 INFO]: Loading properties", "[22:37:05 INFO]: \u{1B}[32mSimplex SMP is ready\u{1B}[0m", "[22:37:06 INFO]: Done (4.218s)! For help, type \"help\"", "[22:37:14 INFO]: MissCaitlyn joined the game"]; if ProcessInfo.processInfo.arguments.contains("--demo-console-filters") { lines += SuiteDemo.consoleFilterLines }; logRevision &+= 1 }
            while !Task.isCancelled && active && runID == generation {
                stats = try! JSON.from(["memory_bytes": 8_214_000_000.0, "cpu_absolute": Double.random(in: 34...62), "disk_bytes": 23_144_000_000.0, "uptime": 12_814_000, "network": ["rx_bytes": 2_124_000, "tx_bytes": 8_124_000]])
                sample(); try? await Task.sleep(for: .seconds(2))
            }
            return
        }
        if let amp = api.amp {
            await streamAMP(amp, api: api, server: server, minecraft: minecraft, generation: generation)
            return
        }
        // REST fallback and WebSocket run together; cancellation tears both down.
        async let poll: Void = polling(api: api, server: server, generation: generation)
        async let playerPoll: Void = pollingPlayers(api: api, server: server, enabled: minecraft, generation: generation)
        async let tpsPoll: Void = pollingTPS(api: api, server: server, enabled: minecraft, generation: generation)
        var retry = 1
        while !Task.isCancelled && active && runID == generation {
            do {
                guard api.access.allows(server.id, "websocket.connect") else { connection = "Keine Berechtigung für die Live-Konsole"; break }
                connection = retry == 1 ? "Verbinden …" : "Verbindung wird wiederhergestellt …"
                let auth = try await api.request("servers/\(server.id)/websocket")["data"]
                guard active && runID == generation && !Task.isCancelled else { break }
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
                    guard active && runID == generation && !Task.isCancelled else { break }
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
                        receiveConsole(args.map(\.string).joined(separator: "\n"))
                    case "status": state = args.first?.string ?? "unknown"
                    case "stats":
                        if let text = args.first?.string, let value = try? JSON.parse(text) { stats = value; if !value["state"].string.isEmpty { state = value["state"].string }; sample() }
                    case "token expiring", "token expired", "jwt error":
                        let renewed = try await api.request("servers/\(server.id)/websocket")["data"]
                        try await send(event: "auth", args: [renewed["token"].string], to: ws)
                    case "daemon error":
                        issue = "Wings: " + (args.first?.string ?? "Fehler")
                        if tpsPending { tpsIssue = issue; tpsPending = false }
                    default: break
                    }
                }
            } catch {
                if Task.isCancelled || !active || runID != generation { break }
                connected = false; connection = "Live getrennt · erneuter Versuch"; issue = error.localizedDescription
            }
            guard runID == generation else { break }
            socket?.cancel(with: .goingAway, reason: nil); socket = nil
            try? await Task.sleep(for: .seconds(min(retry * 2, 30))); retry += 1
        }
        if runID == generation { socket?.cancel(with: .goingAway, reason: nil); connected = false }
        _ = await (poll, playerPoll, tpsPoll)
    }
    private func streamAMP(_ amp: AMPAPI, api: PanelAPI, server: Server, minecraft: Bool, generation: UUID) async {
        var failures = 0, lastPlayers = Date.distantPast, lastFullStatus = Date.distantPast
        var latestStatus: JSON = .null
        while !Task.isCancelled && active && runID == generation {
            do {
                let update = try await amp.call("Core/GetUpdates", server: server.id)
                guard runID == generation, !Task.isCancelled else { return }
                if latestStatus == .null || Date().timeIntervalSince(lastFullStatus) >= 5 {
                    let full = try await amp.call("Core/GetStatus", server: server.id)
                    guard runID == generation, !Task.isCancelled else { return }
                    latestStatus = AMPMetrics.merge(latestStatus, full); lastFullStatus = Date()
                }
                latestStatus = AMPMetrics.merge(latestStatus, update["Status"])
                let resource = AMPAPI.resources(latestStatus)
                state = resource["current_state"].string
                if state == "offline" { stats = resource["resources"] }
                else { var merged = stats.object; merged.merge(resource["resources"].object, uniquingKeysWith: { _, new in new }); stats = .object(merged) }
                sample()
                for row in update["ConsoleEntries"].array {
                    let text = row["Contents"].string.isEmpty ? row.string : row["Contents"].string
                    if !text.isEmpty { receiveConsole(text) }
                }
                connected = true; connection = "AMP verbunden"; issue = nil; failures = 0
                if minecraft, state == "running", Date().timeIntervalSince(lastPlayers) >= 10 {
                    lastPlayers = Date(); await refreshPlayers(api: api, server: server)
                }
            } catch {
                if Task.isCancelled || runID != generation { return }
                connected = false; connection = "AMP getrennt · erneuter Versuch"; issue = error.localizedDescription; failures += 1
            }
            do { try await Task.sleep(for: .seconds(failures == 0 ? 1 : min(30, failures * 3))) } catch { return }
        }
    }
    private func polling(api: PanelAPI, server: Server, generation: UUID) async {
        while !Task.isCancelled && active && runID == generation {
            do {
                let result = try await api.request("servers/\(server.id)/resources").attributes
                guard active && runID == generation && !Task.isCancelled else { return }
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
                        if let decoded = PlayerSnapshot.decode(response) { players = decoded; if let max = decoded.maximum { playerCapacity = max }; playerIssue = nil; received = true }
                        else { useProvider = false }
                    } catch {
                        if Task.isCancelled { return }
                        if error.localizedDescription.contains("404") || error.localizedDescription.contains("403") || error.localizedDescription.contains("501") { useProvider = false }
                        playerIssue = "Spieler-API nicht verfügbar; Aktualisierung über list bei laufendem Server."
                    }
                }
                if (!received || (players?.maximum == nil && playerCapacity == nil)) && connected && state == "running" {
                    do { try await sendCommand("list", api: api, server: server) }
                    catch { playerIssue = error.localizedDescription }
                }
            }
            do { try await Task.sleep(for: .seconds(useProvider ? 15 : 30)) } catch { return }
        }
    }
    private func pollingTPS(api: PanelAPI, server: Server, enabled: Bool, generation: UUID) async {
        guard enabled else { return }
        while !Task.isCancelled && active && runID == generation {
            if connected && state == "running" {
                if !api.access.allows(server.id, "control.console") { tpsIssue = "Keine Berechtigung zum Abfragen von TPS (Konsolenbefehle)." }
                else {
                    if tpsPending, let requested = tpsRequested, Date().timeIntervalSince(requested) > 15 {
                        tpsPending = false
                        tpsIssue = "Keine TPS-Antwort erhalten. Prüfe in der Konsole, ob tps verfügbar ist (Paper/Purpur)."
                    }
                    if tpsRequested == nil || Date().timeIntervalSince(tpsRequested!) >= 30 { await requestTPS(api: api, server: server) }
                }
            }
            do { try await Task.sleep(for: .seconds(1)) } catch { return }
        }
    }
    func requestTPS(api: PanelAPI, server: Server) async {
        guard !tpsBusy, connected, state == "running", !api.demo else { return }
        let generation = runID
        tpsBusy = true; tpsRequested = Date(); tpsPending = true; tpsIssue = nil
        defer { if runID == generation { tpsBusy = false } }
        do { try await sendCommand("tps", api: api, server: server) }
        catch { if runID == generation { tpsPending = false; tpsIssue = error.localizedDescription } }
    }
    func sendCommand(_ command: String, api: PanelAPI, server: Server) async throws {
        guard !api.demo else { throw PanelError(message: "Im Demomodus werden keine Befehle gesendet.") }
        guard api.access.allows(path: "servers/\(server.id)/command", method: "POST") else { throw PanelError(message: "Keine Berechtigung für Konsolenbefehle.") }
        // Same command channel as the web panel: subscribe first, then send through Wings.
        if connected, let socket { try await send(event: "send command", args: [command], to: socket) }
        else { try await api.mutate("servers/\(server.id)/command", ["command": command]) }
    }
    func refresh(api: PanelAPI, server: Server, minecraft: Bool) async throws {
        let generation = runID
        let result = try await api.request("servers/\(server.id)/resources").attributes
        guard generation == runID, !Task.isCancelled else { return }
        if !result["current_state"].string.isEmpty { state = result["current_state"].string }
        stats = result["resources"]; sample(); issue = nil
        if minecraft { await refreshPlayers(api: api, server: server); await requestTPS(api: api, server: server) }
    }
    func refreshPlayers(api: PanelAPI, server: Server) async {
        if api.demo { return }
        if state == "offline" { players = PlayerSnapshot(online: 0, maximum: playerCapacity, names: [], source: "Server offline"); return }
        if (api.isKinetic || api.amp != nil), let result = try? await api.request("servers/\(server.id)/players"), let decoded = PlayerSnapshot.decode(result) { players = decoded; if let maximum = decoded.maximum { playerCapacity = maximum }; playerIssue = nil; return }
        if state == "running" && connected { do { try await sendCommand("list", api: api, server: server); playerIssue = nil } catch { playerIssue = error.localizedDescription } }
    }
    func queryPlayerValue(player: String, field: String, api: PanelAPI, server: Server) async throws -> String {
        guard PlayerCommands.validName(player), ["Health", "foodLevel", "XpLevel", "playerGameType", "Pos", "Dimension"].contains(field) else { throw PanelError(message: "Ungültige Spielerabfrage.") }
        guard playerQuery == nil else { throw PanelError(message: "Eine Spielerabfrage läuft bereits. Bitte kurz warten.") }
        guard connected, state == "running", api.access.allows(server.id, "control.console") else { throw PanelError(message: "Live-Konsole und Berechtigung zum Senden von Befehlen erforderlich.") }
        let id = UUID()
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                playerQuery = PlayerQuery(id: id, player: player, result: continuation)
                playerTimeout = Task { [weak self] in
                    do { try await Task.sleep(for: .seconds(7)) } catch { return }
                    self?.completePlayerQuery(id: id, error: PanelError(message: "Keine auswertbare Antwort für \(field). Der Spieler kann offline sein oder der Server unterdrückt Befehlsausgaben. Prüfe die Konsole."))
                }
                Task { do { try await sendCommand("minecraft:data get entity " + player + " " + field, api: api, server: server) } catch { completePlayerQuery(id: id, error: error) } }
            }
        } onCancel: { Task { @MainActor in self.completePlayerQuery(id: id, error: CancellationError()) } }
    }
    private func completePlayerQuery(id: UUID? = nil, value: String? = nil, error: Error? = nil) {
        guard let query = playerQuery, id == nil || id == query.id else { return }
        playerQuery = nil; playerTimeout?.cancel(); playerTimeout = nil
        if let value { query.result.resume(returning: value) } else { query.result.resume(throwing: error ?? CancellationError()) }
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
    func clearConsole() { lines = []; logRevision &+= 1 }
    func receiveConsole(_ text: String) {
        for line in text.components(separatedBy: .newlines) {
            if let query = playerQuery, let value = PlayerCommands.response(line, player: query.player) { completePlayerQuery(id: query.id, value: value) }
            if let parsed = TPSSnapshot.parse(line) { tps = parsed; tpsPending = false; tpsIssue = nil }
            if let parsed = PlayerSnapshot.parseLog(line) { players = parsed; playerCapacity = parsed.maximum; playerIssue = nil }
        }
        // Bound individual messages as well as the entire scrollback.
        lines += text.suffix(64_000).components(separatedBy: "\n").map { $0 + "\u{1B}[0m" }
        if lines.count > 1500 { lines.removeFirst(lines.count - 1500) }
        var count = 0; var keep = 0
        for line in lines.reversed() { count += line.utf8.count; if count > 1_048_576 { break }; keep += 1 }
        if keep < lines.count { lines.removeFirst(lines.count - keep) }
        logRevision &+= 1
    }
}
