import SwiftUI

/// 5.8.0: Schnellzentrale – freely editable tiles per server that open a tool or run a confirmed action.
enum SuiteQuickKind: String, CaseIterable, Identifiable {
    case tool, restart, restartCancel = "restart-cancel", smartRestart = "smart-restart", maintenanceOn = "maintenance-on", maintenanceOff = "maintenance-off"
    case chatOn = "chat-on", chatOff = "chat-off", slowmode, announce, saveWorlds = "save-worlds", automation
    var id: String { rawValue }
    var title: String {
        switch self {
        case .tool: return "Werkzeug öffnen"
        case .restart: return "Neustart mit Countdown"
        case .restartCancel: return "Neustart abbrechen"
        case .smartRestart: return "Smart-Neustart an/aus"
        case .maintenanceOn: return "Wartung starten"
        case .maintenanceOff: return "Wartung beenden"
        case .chatOn: return "Chat einschalten"
        case .chatOff: return "Chat ausschalten"
        case .slowmode: return "Chat-Slowmode"
        case .announce: return "Ankündigung senden"
        case .saveWorlds: return "Welten speichern"
        case .automation: return "Automation ausführen"
        }
    }
    var symbol: String {
        switch self {
        case .tool: return "square.grid.2x2.fill"
        case .restart: return "arrow.clockwise.circle.fill"
        case .restartCancel: return "xmark.circle.fill"
        case .smartRestart: return "clock.arrow.2.circlepath"
        case .maintenanceOn: return "wrench.and.screwdriver.fill"
        case .maintenanceOff: return "checkmark.seal.fill"
        case .chatOn: return "bubble.left.and.bubble.right.fill"
        case .chatOff: return "bubble.left.and.exclamationmark.bubble.right.fill"
        case .slowmode: return "tortoise.fill"
        case .announce: return "megaphone.fill"
        case .saveWorlds: return "externaldrive.fill"
        case .automation: return "wand.and.stars"
        }
    }
    var color: String {
        switch self {
        case .restart, .maintenanceOn, .chatOff: return "E5484D"
        case .restartCancel, .maintenanceOff, .chatOn: return "30A46C"
        case .announce, .automation: return "8E4EC6"
        case .smartRestart, .slowmode: return "F76B15"
        default: return "0091FF"
        }
    }
    /// What the value field means for this kind ("" = no value).
    var valueLabel: String {
        switch self {
        case .restart: return "Countdown in Sekunden (5–300)"
        case .slowmode: return "Sekunden zwischen Nachrichten (0–120)"
        case .announce: return "Nachricht"
        case .maintenanceOn: return "Grund (optional)"
        default: return ""
        }
    }
    var defaultValue: String { self == .restart ? "60" : self == .slowmode ? "5" : "" }
    static let suggestions: [SuiteQuickKind] = [.restart, .restartCancel, .smartRestart, .maintenanceOn, .maintenanceOff, .chatOff, .chatOn, .saveWorlds]
}

struct SuiteQuickTile: Identifiable, Equatable {
    var id = UUID().uuidString
    var kind: SuiteQuickKind = .restart
    var title = SuiteQuickKind.restart.title
    var symbol = SuiteQuickKind.restart.symbol
    var color = SuiteQuickKind.restart.color
    var value = "60"
    init() {}
    init(kind: SuiteQuickKind) { self.kind = kind; title = kind.title; symbol = kind.symbol; color = kind.color; value = kind.defaultValue }
    init?(_ json: JSON) {
        guard let kind = SuiteQuickKind(rawValue: json["kind"].string) else { return nil }
        id = json["id"].string.isEmpty ? UUID().uuidString : json["id"].string
        self.kind = kind; title = json["title"].string; symbol = json["symbol"].string.isEmpty ? kind.symbol : json["symbol"].string
        color = json["color"].string.isEmpty ? kind.color : json["color"].string; value = json["value"].string
    }
    var json: JSON { .object(["id": .string(id), "kind": .string(kind.rawValue), "title": .string(title), "symbol": .string(symbol), "color": .string(color), "value": .string(value)]) }
    var tint: Color { ThemeRGB(color).color }
    var confirmation: String {
        switch kind {
        case .restart: return "Server mit \(value) Sekunden Countdown neu starten?"
        case .restartCancel: return "Laufenden Neustart abbrechen?"
        case .smartRestart: return "Smart-Neustart umschalten?"
        case .maintenanceOn: return "Wartungsmodus starten? Nur Team kann beitreten."
        case .maintenanceOff: return "Wartungsmodus beenden?"
        case .chatOn: return "Serverchat einschalten?"
        case .chatOff: return "Serverchat für alle ausschalten?"
        case .slowmode: return "Chat-Slowmode auf \(value) Sekunden setzen?"
        case .announce: return "Ankündigung senden: „\(value)“?"
        case .saveWorlds: return "Alle Welten jetzt speichern?"
        case .automation: return "Automation jetzt ausführen?"
        case .tool: return ""
        }
    }
}

/// Runs a tile against the server: prepared actions go through the regular confirmation ticket.
@MainActor enum SuiteQuickRunner {
    static func run(_ tile: SuiteQuickTile, record: SuiteConnection) async throws -> String {
        let suite = try SuiteAPI(record, timeout: 12)
        _ = try await suite.health(timeout: 12)
        func action(_ path: String, _ values: [String: JSON]) async throws -> String {
            let plan = try await suite.prepare(path, values: values)
            let result = try await suite.execute(plan["ticket"].string)
            return result["title"].string.isEmpty ? "Ausgeführt" : result["title"].string
        }
        switch tile.kind {
        case .restart: return try await action("server.restart", ["action": .string("schedule"), "seconds": .string(String(max(5, min(300, Int(tile.value) ?? 60))))])
        case .restartCancel: return try await action("server.restart", ["action": .string("cancel")])
        case .maintenanceOn: return try await action("server.maintenance", ["mode": .string("on"), "reason": .string(tile.value)])
        case .maintenanceOff: return try await action("server.maintenance", ["mode": .string("off")])
        case .chatOn: return try await action("server.chat", ["mode": .string("on")])
        case .chatOff: return try await action("server.chat", ["mode": .string("off")])
        case .slowmode: return try await action("server.slowmode", ["seconds": .string(String(max(0, min(120, Int(tile.value) ?? 5))))])
        case .announce:
            guard !tile.value.trimmingCharacters(in: .whitespaces).isEmpty else { throw PanelError(message: "Bitte zuerst eine Nachricht für die Kachel festlegen.") }
            return try await action("server.announce", ["message": .string(tile.value)])
        case .saveWorlds: return try await action("server.save", [:])
        case .smartRestart:
            let current = try await suite.request("smart-restart")
            let next = !current["settings"]["enabled"].bool
            _ = try await suite.post("smart-restart", ["enabled": .bool(next), "confirmed": .bool(true)])
            return next ? "Smart-Neustart aktiviert" : "Smart-Neustart deaktiviert"
        case .automation:
            _ = try await suite.post("automations", ["operation": .string("run"), "id": .string(tile.value), "confirmed": .bool(true)])
            return "Automation ausgeführt"
        case .tool: return ""
        }
    }
}

/// Tiles shown on the server card of the home screen.
struct SuiteQuickCenterRow: View {
    let record: SuiteConnection
    @ObservedObject private var preferences = SuiteQuickAccess.shared
    @State private var pending: SuiteQuickTile?
    @State private var running: String?
    @State private var notice = ""
    @State private var error: String?
    @State private var editing = false
    var tiles: [SuiteQuickTile] { preferences.actions(for: record.id).compactMap { SuiteQuickTile($0) } }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Label("Schnellzentrale", systemImage: "bolt.circle.fill").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                Spacer()
                Button("Bearbeiten") { editing = true }.font(.caption)
            }
            if tiles.isEmpty {
                Button { editing = true } label: { Label("Neustart, Wartung & mehr als Kachel anlegen", systemImage: "plus.circle").font(.caption) }
            } else {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 104), spacing: 6)], spacing: 6) {
                    ForEach(tiles) { tile in tileButton(tile) }
                }
            }
            if !notice.isEmpty { Label(notice, systemImage: "checkmark.circle.fill").font(.caption).foregroundStyle(.green) }
        }
        .sheet(isPresented: $editing) { SuiteQuickCenterEditor(record: record) }
        .confirmationDialog(pending?.confirmation ?? "", isPresented: Binding(get: { pending != nil }, set: { if !$0 { pending = nil } }), titleVisibility: .visible, presenting: pending) { tile in
            Button(tile.title) { Task { await run(tile) } }
            Button("Abbrechen", role: .cancel) {}
        }
        .errorAlert($error)
    }

    private func tileButton(_ tile: SuiteQuickTile) -> some View {
        Button {
            if tile.kind == .tool, let tool = SuiteTool(rawValue: tile.value) { SuiteNavigationInbox.shared.request = SuiteLaunchRequest(tool: tool, connection: record.id) }
            else { pending = tile }
        } label: {
            VStack(spacing: 5) {
                if running == tile.id { ProgressView().frame(height: 22) }
                else { Image(systemName: tile.symbol).font(.title3).foregroundStyle(tile.tint).frame(height: 22) }
                Text(tile.title).font(.caption.weight(.semibold)).foregroundStyle(.primary).lineLimit(2).multilineTextAlignment(.center).minimumScaleFactor(0.8)
            }
            .frame(maxWidth: .infinity, minHeight: 64).padding(.horizontal, 4)
            .background(tile.tint.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
            .overlay { RoundedRectangle(cornerRadius: 12).strokeBorder(tile.tint.opacity(0.25)) }
        }
        .buttonStyle(.plain).disabled(running != nil)
        .accessibilityIdentifier("quick-tile-" + tile.id)
    }

    private func run(_ tile: SuiteQuickTile) async {
        running = tile.id
        defer { running = nil }
        do { notice = try await SuiteQuickRunner.run(tile, record: record); Feedback.success() }
        catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }
}

/// Add, edit, sort and remove tiles of one server.
struct SuiteQuickCenterEditor: View {
    let record: SuiteConnection
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var preferences = SuiteQuickAccess.shared
    @State private var tiles: [SuiteQuickTile] = []
    @State private var editing: SuiteQuickTile?
    @State private var supported: Bool?
    @State private var automations: [JSON] = []

    var body: some View {
        NavigationStack {
            List {
                if supported == false {
                    Section { Label("Für die Schnellzentrale bitte die Admin Suite auf 10.15.0 aktualisieren.", systemImage: "arrow.down.circle").foregroundStyle(.orange) }
                }
                Section {
                    ForEach(tiles) { tile in
                        Button { editing = tile } label: {
                            HStack(spacing: 12) {
                                Image(systemName: tile.symbol).foregroundStyle(.white).frame(width: 32, height: 32).background(tile.tint.gradient, in: RoundedRectangle(cornerRadius: 8))
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(tile.title).font(.headline)
                                    Text(tile.kind.title + (tile.value.isEmpty ? "" : " · " + tile.value)).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                                }
                            }
                        }.buttonStyle(.plain)
                    }
                    .onMove { tiles.move(fromOffsets: $0, toOffset: $1) }
                    .onDelete { tiles.remove(atOffsets: $0) }
                    if tiles.count < 24 {
                        Menu {
                            ForEach(SuiteQuickKind.allCases) { kind in Button(kind.title, systemImage: kind.symbol) { var tile = SuiteQuickTile(kind: kind); if kind == .tool { tile.value = SuiteTool.players.rawValue; tile.title = SuiteTool.players.title; tile.symbol = SuiteTool.players.symbol }; tiles.append(tile); editing = tile } }
                        } label: { Label("Kachel hinzufügen", systemImage: "plus.circle.fill") }
                    }
                } header: { Text("Kacheln für " + preferences.name(record)) } footer: {
                    Text("Antippen zum Bearbeiten, ziehen zum Sortieren, wischen zum Löschen. Aktionen fragen vor dem Ausführen nach und prüfen deine Rechte. Die Kacheln erscheinen auch im Webpanel.")
                }
                if tiles.isEmpty {
                    Section("Vorschläge") {
                        Button("Empfohlene Kacheln übernehmen", systemImage: "wand.and.stars") { tiles = SuiteQuickKind.suggestions.map(SuiteQuickTile.init(kind:)) }
                    }
                }
            }
            .panelScreen()
            .navigationTitle("Schnellzentrale")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Sichern") { preferences.setActions(tiles.map(\.json), for: record.id); dismiss() }.disabled(supported != true) }
                ToolbarItem(placement: .topBarLeading) { EditButton() }
            }
            .sheet(item: $editing) { tile in
                SuiteQuickTileEditor(tile: tile, automations: automations) { updated in
                    if let index = tiles.firstIndex(where: { $0.id == updated.id }) { tiles[index] = updated }
                }
            }
            .task {
                tiles = preferences.actions(for: record.id).compactMap { SuiteQuickTile($0) }
                guard let suite = try? SuiteAPI(record, timeout: 8), (try? await suite.health(timeout: 8)) != nil else { supported = false; return }
                supported = suite.features.contains("quick-center-v1")
                if suite.features.contains("automations-v1") { automations = (try? await suite.request("automations"))?["items"].array ?? [] }
            }
        }
    }
}

struct SuiteQuickTileEditor: View {
    @State var tile: SuiteQuickTile
    let automations: [JSON]
    let save: (SuiteQuickTile) -> Void
    @Environment(\.dismiss) private var dismiss
    private static let symbols = ["arrow.clockwise.circle.fill", "xmark.circle.fill", "clock.arrow.2.circlepath", "wrench.and.screwdriver.fill", "checkmark.seal.fill",
        "bubble.left.and.bubble.right.fill", "megaphone.fill", "externaldrive.fill", "tortoise.fill", "wand.and.stars", "bolt.fill", "star.fill", "shield.lefthalf.filled",
        "person.2.fill", "sparkles", "flame.fill", "moon.zzz.fill", "sun.max.fill", "gamecontroller.fill", "map.fill", "ticket.fill", "bell.badge.fill", "power", "lock.fill"]
    private static let colors = ["0091FF", "30A46C", "E5484D", "F76B15", "8E4EC6", "FFC53D", "12A594", "D6409F", "6E56CF", "8D8D8D"]

    var body: some View {
        NavigationStack {
            Form {
                Section("Vorschau") {
                    HStack { Spacer(); VStack(spacing: 6) { Image(systemName: tile.symbol).font(.title2).foregroundStyle(tile.tint); Text(tile.title).font(.caption.weight(.semibold)) }
                        .frame(width: 120, height: 72).background(tile.tint.opacity(0.12), in: RoundedRectangle(cornerRadius: 12)); Spacer() }
                }
                Section("Aktion") {
                    Picker("Art", selection: $tile.kind) { ForEach(SuiteQuickKind.allCases) { Label($0.title, systemImage: $0.symbol).tag($0) } }
                        .onChange(of: tile.kind) { _, kind in tile.symbol = kind.symbol; tile.color = kind.color; tile.title = kind.title; tile.value = kind.defaultValue }
                    if tile.kind == .tool {
                        Picker("Werkzeug", selection: $tile.value) { ForEach(SuiteTool.allCases) { Label($0.title, systemImage: $0.symbol).tag($0.rawValue) } }
                            .onChange(of: tile.value) { _, value in if let tool = SuiteTool(rawValue: value) { tile.title = tool.title; tile.symbol = tool.symbol } }
                    } else if tile.kind == .automation {
                        Picker("Automation", selection: $tile.value) {
                            Text("Bitte wählen").tag("")
                            ForEach(automations, id: \.["id"].string) { Text($0["name"].string).tag($0["id"].string) }
                        }
                    } else if !tile.kind.valueLabel.isEmpty {
                        TextField(tile.kind.valueLabel, text: $tile.value, axis: .vertical).keyboardType(tile.kind == .announce || tile.kind == .maintenanceOn ? .default : .numberPad)
                    }
                }
                Section("Aussehen") {
                    TextField("Titel", text: $tile.title)
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 6), spacing: 10) {
                        ForEach(Self.symbols, id: \.self) { symbol in
                            Button { tile.symbol = symbol } label: { Image(systemName: symbol).frame(width: 36, height: 36).background(tile.symbol == symbol ? tile.tint.opacity(0.25) : Color.clear, in: RoundedRectangle(cornerRadius: 8)) }.buttonStyle(.plain)
                        }
                    }
                    HStack {
                        ForEach(Self.colors, id: \.self) { color in
                            Button { tile.color = color } label: { Circle().fill(ThemeRGB(color).color).frame(width: 24, height: 24).overlay { if tile.color == color { Circle().strokeBorder(.white, lineWidth: 2) } } }.buttonStyle(.plain)
                        }
                    }
                }
            }
            .panelScreen()
            .navigationTitle("Kachel")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Fertig") { save(tile); dismiss() }.disabled(tile.title.trimmingCharacters(in: .whitespaces).isEmpty || tile.title.count > 40) }
            }
        }
    }
}
