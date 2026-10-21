import SwiftUI

struct PlayerManagerView: View {
    @ObservedObject private var suiteConnections = SuiteConnections.shared
    let api: PanelAPI
    let server: Server
    @ObservedObject var live: LiveServer
    @StateObject private var preferences = PlayerPreferencesStore()
    @State private var search = ""
    @State private var settings = false
    var key: String { api.base.absoluteString + "|" + server.id }
    var names: [String] { Array(Set(live.players?.names ?? [])).sorted { $0.localizedStandardCompare($1) == .orderedAscending }.filter { search.isEmpty || $0.localizedCaseInsensitiveContains(search) } }
    var body: some View {
        List {
            Section {
                HStack { Label("Online-Spieler", systemImage: "person.2.fill").font(.headline); Spacer(); StatePill(state: live.state) }
                Text(live.players.map { "\($0.online) von \(($0.maximum ?? live.playerCapacity).map(String.init) ?? "?") online" } ?? "Warte auf Spielerdaten …").font(.title2.bold()).monospacedDigit()
                if let snapshot = live.players { LabeledContent("Stand") { Text(snapshot.updated, style: .time) }; Text(snapshot.source).font(.caption).foregroundStyle(.secondary) }
                if let issue = live.playerIssue { Text(issue).font(.caption).foregroundStyle(.orange) }
                Button("Spieler aktualisieren", systemImage: "arrow.clockwise") { Task { await live.refreshPlayers(api: api, server: server) } }
            }
            Section("Spieler · \(names.count)") {
                ForEach(names, id: \.self) { name in
                    NavigationLink {
                        if let link = suiteConnections.get(api, server), link.useForModeration { SuiteLinkedPlayerView(panel: api, server: server, player: name) } else { PlayerDetailView(api: api, server: server, live: live, name: name, preferences: preferences, key: key) }
                    } label: {
                        HStack(spacing: 12) {
                            Image(systemName: "person.crop.circle.fill").font(.title).foregroundStyle(name.hasPrefix(".") ? .blue : .teal)
                            VStack(alignment: .leading, spacing: 4) { Text(name).font(.headline); Text("Live-Werte & Aktionen").font(.caption).foregroundStyle(.secondary) }
                        }.padding(.vertical, 4)
                    }.accessibilityIdentifier("managePlayer-" + name)
                }
                if names.isEmpty { Text(live.players?.online == 0 ? "Gerade niemand online" : "Keine Spielernamen verfügbar. Aktualisiere die Liste oder prüfe den list-Befehl in der Konsole.").foregroundStyle(.secondary) }
            }
        }.panelScreen().navigationTitle("Spieler-Manager").navigationBarTitleDisplayMode(.inline)
            .searchable(text: $search, prompt: "Spieler suchen")
            .task { await live.refreshPlayers(api: api, server: server) }
            .refreshable { await live.refreshPlayers(api: api, server: server) }
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Spielerbefehle", systemImage: "slider.horizontal.3") { settings = true } } }
            .sheet(isPresented: $settings) { PlayerCommandSettings(preferences: preferences, key: key) }
    }
}
struct PlayerCommandRequest: Identifiable {
    let id = UUID()
    var title: String
    var template: String
    var needsReason = false
    var destructive = false
}
struct PlayerDetailView: View {
    let api: PanelAPI
    let server: Server
    @ObservedObject var live: LiveServer
    let name: String
    @ObservedObject var preferences: PlayerPreferencesStore
    let key: String
    @StateObject private var inspector = PlayerInspector()
    @State private var automatic = false
    @State private var mode = "survival"
    @State private var level = 0
    @State private var initializedControls = false
    @State private var request: PlayerCommandRequest?
    @State private var notice: String?
    var online: Bool { live.state == "running" && live.players?.names.contains(name) == true }
    var commands: PlayerCommandPreferences { preferences.get(key) }
    var body: some View {
        List {
            Section {
                HStack { Image(systemName: "person.crop.circle.fill").font(.system(size: 44)).foregroundStyle(Color.panelAccent); VStack(alignment: .leading, spacing: 4) { Text(name).font(.title2.bold()).textSelection(.enabled); Label(online ? "Online" : "Zuletzt gesehen / offline", systemImage: online ? "circle.fill" : "circle").font(.caption).foregroundStyle(online ? Color.green : .secondary) } }
                Button("Spielername kopieren", systemImage: "doc.on.doc") { Clipboard.copy(name) }
            }
            Section {
                HStack { Label("Live-Werte", systemImage: "waveform.path.ecg").font(.headline); Spacer(); if inspector.busy { ProgressView() } }
                if let health = PlayerCommands.number(inspector.values["Health"]) { LabeledContent { Text(String(format: "%.1f Herzen · %.1f HP", health / 2, health)).monospacedDigit() } label: { Label("Gesundheit", systemImage: "heart.fill").foregroundStyle(.pink) } }
                else { LabeledContent("Gesundheit", value: "–") }
                LabeledContent("Hunger", value: PlayerCommands.number(inspector.values["foodLevel"]).map { "\(Int($0)) / 20" } ?? "–")
                LabeledContent("Erfahrungslevel", value: PlayerCommands.number(inspector.values["XpLevel"]).map { String(Int($0)) } ?? "–")
                LabeledContent("Spielmodus", value: gameModeName)
                if let position = inspector.values["Pos"] { LabeledContent("Position", value: position).font(.system(.caption, design: .monospaced)).textSelection(.enabled) }
                if let dimension = inspector.values["Dimension"] { LabeledContent("Dimension", value: dimension.replacingOccurrences(of: "\"", with: "")) }
                if let updated = inspector.updated { LabeledContent("Vollständige Abfrage") { Text(updated, style: .time) } }
                if let issue = inspector.issue { Text(issue).font(.caption).foregroundStyle(.orange) }
                Button("Werte laden", systemImage: "arrow.clockwise") { Task { await read() } }.disabled(inspector.busy || !online).requiresAccess(api, server.id, "control.console", "websocket.connect")
                Toggle("Alle 15 Sekunden abfragen", isOn: $automatic).disabled(!online)
            } footer: { Text("Werte werden über gezielte data-get-Befehle aus der Live-Konsole gelesen. Die automatische Abfrage ist optional und erzeugt Konsolenausgaben. Unterdrückte oder übersetzte Ausgaben können die Erkennung verhindern.") }
            Section("Direkt im laufenden Server ändern") {
                Picker("Spielmodus", selection: $mode) { Text("Überleben").tag("survival"); Text("Kreativ").tag("creative"); Text("Abenteuer").tag("adventure"); Text("Zuschauer").tag("spectator") }
                Button("Spielmodus anwenden") { request = PlayerCommandRequest(title: "Spielmodus ändern", template: "minecraft:gamemode " + mode + " {player}") }
                TextField("Erfahrungslevel", value: $level, format: .number).keyboardType(.numberPad)
                Button("Erfahrungslevel anwenden") { request = PlayerCommandRequest(title: "Erfahrungslevel setzen", template: "minecraft:experience set {player} \(level) levels") }.disabled(!(0...10000).contains(level))
                Button("Sofortheilung", systemImage: "heart.fill") { request = PlayerCommandRequest(title: "Sofortheilung", template: "minecraft:effect give {player} minecraft:instant_health 1 4 true") }
                Button("Sättigen", systemImage: "carrot.fill") { request = PlayerCommandRequest(title: "Spieler sättigen", template: "minecraft:effect give {player} minecraft:saturation 1 10 true") }
            }.disabled(!online).requiresAccess(api, server.id, "control.console")
            Section("Moderation & eigene Aktionen") {
                Button("Kicken", systemImage: "person.crop.circle.badge.xmark", role: .destructive) { request = PlayerCommandRequest(title: "Spieler kicken", template: commands.kick, needsReason: true, destructive: true) }
                Button("Bannen", systemImage: "hand.raised.fill", role: .destructive) { request = PlayerCommandRequest(title: "Spieler bannen", template: commands.ban, needsReason: true, destructive: true) }
                ForEach(commands.custom) { action in Button(action.name, systemImage: "terminal") { request = PlayerCommandRequest(title: action.name, template: action.command, needsReason: action.command.contains("{reason}"), destructive: true) } }
            }.disabled(!online).requiresAccess(api, server.id, "control.console")
            Section { Text("Online-Änderungen erfolgen über Serverbefehle und werden vor dem Senden angezeigt. Beliebige Spieler-NBT-Felder benötigen ein passendes Serverplugin; bearbeite gespeicherte Spielerdateien nur offline.").font(.caption).foregroundStyle(.secondary) }
        }.panelScreen().navigationTitle(name).navigationBarTitleDisplayMode(.inline)
            .task(id: automatic) {
                await read()
                if automatic { while !Task.isCancelled { do { try await Task.sleep(for: .seconds(15)) } catch { return }; await read() } }
            }
            .refreshable { await live.refreshPlayers(api: api, server: server); await read() }
            .sheet(item: $request) { action in PlayerCommandSheet(api: api, server: server, live: live, player: name, action: action) { notice = "Befehl an den Server gesendet. Das Ergebnis steht in der Konsole."; Task { await live.refreshPlayers(api: api, server: server); if !action.destructive { await read() } } } }
            .alert("Befehl übergeben", isPresented: Binding(get: { notice != nil }, set: { if !$0 { notice = nil } })) { Button("OK") { notice = nil } } message: { Text(notice ?? "") }
    }
    var gameModeName: String { guard let mode = PlayerCommands.number(inspector.values["playerGameType"]), (0...3).contains(mode) else { return "–" }; return ["Überleben", "Kreativ", "Abenteuer", "Zuschauer"][Int(mode)] }
    func read() async { await inspector.read(player: name, live: live, api: api, server: server); guard !initializedControls, inspector.updated != nil else { return }; initializedControls = true; if let value = PlayerCommands.number(inspector.values["XpLevel"]), (0...10000).contains(value) { level = Int(value) }; if let value = PlayerCommands.number(inspector.values["playerGameType"]), (0...3).contains(value) { mode = ["survival", "creative", "adventure", "spectator"][Int(value)] } }
}
struct PlayerCommandSheet: View {
    let api: PanelAPI
    let server: Server
    @ObservedObject var live: LiveServer
    let player: String
    let action: PlayerCommandRequest
    let done: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var reason = "Bitte beachte die Serverregeln."
    @State private var busy = false
    @State private var error: String?
    var command: String? { try? PlayerCommands.render(action.template, player: player, reason: reason) }
    var body: some View {
        NavigationStack {
            Form {
                Section { LabeledContent("Spieler", value: player); if action.needsReason { TextField("Grund", text: $reason, axis: .vertical) } }
                Section("Befehl vor dem Senden prüfen") { Text(command ?? "Ungültige Vorlage – passe sie unter Spielerbefehle an.").font(.system(.footnote, design: .monospaced)).textSelection(.enabled) }
                if action.destructive { Text("Diese Aktion kann den Spieler trennen oder den Spielzustand ändern. Es wird genau der angezeigte Befehl gesendet.").font(.footnote).foregroundStyle(.orange) }
                Button(action.destructive ? "Bestätigen & ausführen" : "Befehl ausführen", role: action.destructive ? .destructive : nil) { send() }.disabled(command == nil || busy || live.state != "running").requiresAccess(api, server.id, "control.console")
            }.panelScreen().navigationTitle(action.title).navigationBarTitleDisplayMode(.inline).toolbar { ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() }.disabled(busy) } }.errorAlert($error).interactiveDismissDisabled(busy)
        }
    }
    func send() { guard let command else { return }; busy = true; Task { defer { busy = false }; do { try await live.sendCommand(command, api: api, server: server); done(); dismiss() } catch { self.error = error.localizedDescription } } }
}
struct PlayerCommandSettings: View {
    @ObservedObject var preferences: PlayerPreferencesStore
    let key: String
    @Environment(\.dismiss) private var dismiss
    @State private var value: PlayerCommandPreferences
    @State private var error: String?
    init(preferences: PlayerPreferencesStore, key: String) { self.preferences = preferences; self.key = key; _value = State(initialValue: preferences.get(key)) }
    var body: some View {
        NavigationStack {
            Form {
                Section { Text("Vorlagen werden nur für diesen Server auf deinem Gerät gespeichert. {player} wird durch den genauen Namen ersetzt, einschließlich Bedrock-Präfix. {reason} ist der frei eingegebene Grund. Beispiel: litebans:ban {player} {reason}").font(.footnote).foregroundStyle(.secondary) }
                Section("Moderation") { TextField("Kick-Befehl", text: $value.kick, axis: .vertical); TextField("Ban-Befehl", text: $value.ban, axis: .vertical) }.font(.system(.subheadline, design: .monospaced)).autocorrectionDisabled().textInputAutocapitalization(.never)
                Section("Eigene Aktionen") {
                    ForEach($value.custom) { $action in VStack(alignment: .leading) { TextField("Name der Aktion", text: $action.name); TextField("Befehl mit {player}", text: $action.command, axis: .vertical).font(.system(.subheadline, design: .monospaced)); Button("Aktion entfernen", role: .destructive) { let id = action.id; value.custom.removeAll { $0.id == id } }.font(.caption) } }
                    if value.custom.count < 20 { Button("Aktion hinzufügen", systemImage: "plus") { value.custom.append(PlayerActionTemplate(name: "", command: "")) } }
                }.autocorrectionDisabled().textInputAutocapitalization(.never)
            }.panelScreen().navigationTitle("Spielerbefehle").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } }; ToolbarItem(placement: .confirmationAction) { Button("Speichern") { save() } } }.errorAlert($error)
        }
    }
    func save() { do {
        _ = try PlayerCommands.render(value.kick, player: ".Bedrock_Player", reason: "Grund")
        _ = try PlayerCommands.render(value.ban, player: "Java_Player", reason: "Grund")
        for action in value.custom { guard !action.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { throw PanelError(message: "Gib jeder Aktion einen Namen.") }; _ = try PlayerCommands.render(action.command, player: "Java_Player", reason: "Grund") }
        preferences.save(value, key: key); dismiss()
    } catch { self.error = error.localizedDescription } }
}
