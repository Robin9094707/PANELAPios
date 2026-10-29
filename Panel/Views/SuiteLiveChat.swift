import SwiftUI

extension JSON { var chatIdentity: String { self["id"].string + "|" + self["at"].string } }

/// Last 50 public chat messages per suite server, kept on this device so the chat survives app restarts.
final class SuiteChatHistory: @unchecked Sendable {
    static let limit = 50
    struct Snapshot: Codable { var rows: [JSON] = []; var cursor = "0"; var epoch = "" }
    private let defaults: UserDefaults
    init(defaults: UserDefaults = .standard) { self.defaults = defaults }
    func key(_ scope: String) -> String { "suite.chatHistory.v1." + scope }
    func load(_ scope: String) -> Snapshot { (try? JSONDecoder().decode(Snapshot.self, from: defaults.data(forKey: key(scope)) ?? Data())) ?? Snapshot() }
    func save(_ snapshot: Snapshot, scope: String) {
        var value = snapshot; value.rows = Array(value.rows.suffix(Self.limit))
        defaults.set(try? JSONEncoder().encode(value), forKey: key(scope))
    }
    func clear(_ scope: String) { defaults.removeObject(forKey: key(scope)) }
    static func identity(_ row: JSON) -> String { row.chatIdentity }
    /// Appends unseen rows in server order and keeps the newest `limit` messages.
    static func merge(_ rows: [JSON], with items: [JSON]) -> [JSON] {
        var seen = Set(rows.map(identity)), result = rows
        for item in items where seen.insert(identity(item)).inserted { result.append(item) }
        return Array(result.suffix(limit))
    }
}

struct SuiteLiveChatView: View {
    let suite: SuiteAPI
    var history = SuiteChatHistory()
    @Environment(\.scenePhase) private var scenePhase
    @State private var rows: [JSON] = []
    @State private var cursor = "0"
    @State private var epoch = ""
    @State private var name = "Server"
    @State private var message = ""
    @State private var error: String?
    @State private var sending = false
    @State private var confirmClear = false
    @State private var loaded = false
    var serverHistory: Bool { suite.features.contains("public-chat-v2") }
    var body: some View {
        ScrollViewReader { proxy in
            List {
                Section { Text(serverHistory ? "Öffentlicher Serverchat · die letzten 50 Nachrichten aller Spieler, auch aus Discord und der App. Der Verlauf bleibt erhalten, bis du ihn mit dem Papierkorb leerst. Private Nachrichten erscheinen hier nicht." : "Öffentlicher Serverchat · die letzten 50 Nachrichten, die diese App empfangen hat. Mit Admin Suite 9.4.0 liefert der Server den vollständigen Verlauf.").font(.caption).foregroundStyle(.secondary) }
                Section {
                    if rows.isEmpty { Text(loaded ? "Noch keine Chatnachrichten." : "Chat wird geladen …").foregroundStyle(.secondary) }
                    ForEach(rows, id: \.chatIdentity) { row in SuiteChatRow(row: row).id(SuiteChatHistory.identity(row)) }
                }
                Section("Nachricht senden") {
                    TextField("Anzeigename", text: $name).textInputAutocapitalization(.never).autocorrectionDisabled()
                    TextField("Deine Nachricht", text: $message, axis: .vertical).lineLimit(2...5)
                    Button("Senden", systemImage: "paperplane.fill") { Task { await send() } }.disabled(sending || message.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || name.isEmpty || suite.scope != "full")
                    if suite.scope != "full" { Text("Zum Senden wird ein full-App-Zugang benötigt.").font(.caption).foregroundStyle(.secondary) }
                }.id("composer")
            }.panelScreen().navigationTitle("Serverchat").errorAlert($error)
                .toolbar { Button("Chat leeren", systemImage: "trash") { confirmClear = true }.disabled(rows.isEmpty).accessibilityIdentifier("chatClear") }
                .confirmationDialog("Chatverlauf leeren?", isPresented: $confirmClear, titleVisibility: .visible) {
                    Button("Verlauf leeren", role: .destructive) { Task { await clear() } }
                } message: { Text(serverHistory && suite.scope == "full" ? "Der gespeicherte Verlauf wird auf dem Server und in der App gelöscht. Neue Nachrichten erscheinen weiterhin." : "Der Verlauf wird auf diesem Gerät gelöscht. Neue Nachrichten erscheinen weiterhin.") }
                .onChange(of: rows.count) { _, _ in if let last = rows.last { withAnimation { proxy.scrollTo(SuiteChatHistory.identity(last), anchor: .bottom) } } }
                .task(id: scenePhase) {
                    guard scenePhase == .active else { return }
                    restore()
                    do {
                        try await initial()
                        if let last = rows.last { proxy.scrollTo(SuiteChatHistory.identity(last), anchor: .bottom) }
                        while !Task.isCancelled { try await Task.sleep(for: .seconds(2)); await poll() }
                    } catch is CancellationError {} catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
                }
        }
    }
    func restore() {
        guard !loaded else { return }
        let snapshot = history.load(suite.bookmarkScope)
        if rows.isEmpty { rows = snapshot.rows; cursor = snapshot.cursor; epoch = snapshot.epoch }
    }
    func persist() { history.save(.init(rows: rows, cursor: cursor, epoch: epoch), scope: suite.bookmarkScope) }
    /// Suite 9.4.0 returns its stored history; older suites only stream messages from the current cursor.
    func initial() async throws {
        if serverHistory {
            let data = try await suite.data("chat", query: ["after": "0"])
            if data["epoch"].string != epoch { rows = [] }
            rows = SuiteChatHistory.merge(rows, with: data["items"].array)
            epoch = data["epoch"].string; cursor = data["cursor"].string
        } else {
            let baseline = try await suite.data("chat")
            // A restarted suite counts from zero again; continue from its cursor.
            if Int(baseline["cursor"].string) ?? 0 < Int(cursor) ?? 0 || cursor == "0" { cursor = baseline["cursor"].string }
        }
        loaded = true; persist()
    }
    func poll() async {
        do {
            let data = try await suite.data("chat", query: ["after": cursor]); try Task.checkCancellation()
            if serverHistory, !data["epoch"].string.isEmpty, data["epoch"].string != epoch { try await initial(); return }
            if Int(data["cursor"].string) ?? 0 < Int(cursor) ?? 0 { cursor = data["cursor"].string; persist(); return }
            let items = data["items"].array
            if !items.isEmpty || data["cursor"].string != cursor { rows = SuiteChatHistory.merge(rows, with: items); cursor = data["cursor"].string; persist() }
        } catch is CancellationError {} catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }
    func send() async { sending = true; defer { sending = false }; do { _ = try await suite.request("chat", method: "POST", body: .object(["name": .string(name), "message": .string(message)])); message = ""; await poll() } catch { self.error = error.localizedDescription } }
    func clear() async {
        do {
            if serverHistory && suite.scope == "full" { let result = try await suite.request("chat/clear", method: "POST", body: .object([:])); epoch = result["epoch"].string }
            rows = []; persist(); Feedback.success()
        } catch { self.error = error.localizedDescription }
    }
}

struct SuiteChatRow: View {
    let row: JSON
    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            avatar
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    Text(row["name"].string).font(.subheadline.bold())
                    if row["source"].string == "app" { badge("App", .secondary) }
                    if row["source"].string == "discord" { badge("Discord", .indigo) }
                    Spacer()
                    if let date = SuitePlayerPresentation.date(row["at"]) { Text(date, style: .time).font(.caption2).foregroundStyle(.secondary) }
                }
                Text(row["text"].string).textSelection(.enabled)
            }
        }.accessibilityElement(children: .combine)
    }
    func badge(_ text: String, _ color: Color) -> some View { Text(text).font(.caption2.weight(.semibold)).padding(.horizontal, 5).padding(.vertical, 1).background(color.opacity(0.15), in: Capsule()).foregroundStyle(color) }
    @ViewBuilder var avatar: some View {
        if let uuid = UUID(uuidString: row["uuid"].string), !uuid.uuidString.hasPrefix("00000000-0000-0000-") {
            AsyncImage(url: URL(string: "https://mc-heads.net/avatar/" + uuid.uuidString + "/64")) { image in image.resizable().interpolation(.none) } placeholder: { Image(systemName: "person.crop.square.fill").resizable().foregroundStyle(.secondary) }
                .frame(width: 32, height: 32).clipShape(RoundedRectangle(cornerRadius: 6))
        } else if UUID(uuidString: row["uuid"].string) != nil {
            Image(systemName: "gamecontroller.fill").frame(width: 32, height: 32).foregroundStyle(.secondary)
        } else {
            Image(systemName: row["source"].string == "discord" ? "bubble.left.and.bubble.right.fill" : row["source"].string == "app" ? "iphone" : "server.rack").frame(width: 32, height: 32).foregroundStyle(row["source"].string == "discord" ? Color.indigo : .secondary)
        }
    }
}
struct SuiteRestartView: View {
    let suite: SuiteAPI
    @StateObject private var model: SuiteSecurityModel
    @State private var status: JSON = .null
    @State private var seconds = 30
    @State private var reasonDE = ""
    @State private var reasonEN = ""
    init(suite:SuiteAPI){self.suite=suite;_model=StateObject(wrappedValue:SuiteSecurityModel(suite))}
    var body: some View {
        List {
            Section("Neustartstatus") { if status["active"].bool { Label("Neustart in \(status["remaining"].int) Sekunden",systemImage:"timer");Text(status["reasonDE"].string).font(.caption);Button("Neustart abbrechen",role:.destructive){prepare(cancel:true)}.disabled(suite.scope != "full" || model.busy) } else { Label("Kein Neustart geplant",systemImage:"checkmark.circle") } }
            Section("Countdown & Ankündigung") { Stepper("\(seconds) Sekunden",value:$seconds,in:1...60);TextField("Grund auf Deutsch (optional)",text:$reasonDE,axis:.vertical);TextField("Reason in English (optional)",text:$reasonEN,axis:.vertical);Text("Jeder Spieler erhält Countdown, Titel und Sounds passend zu seiner Sprache. Ohne Übersetzung wird der vorhandene Grund verwendet.").font(.caption).foregroundStyle(.secondary);Button("Neustart planen",systemImage:"arrow.clockwise.circle.fill"){prepare(cancel:false)}.disabled(status["active"].bool || suite.scope != "full" || model.busy || reasonDE.count>200 || reasonEN.count>200) }
            Section { Text("Der Server verwendet den bestehenden Paper-Neustart. Das Hosting muss den Server danach wieder starten können.").font(.caption).foregroundStyle(.secondary) }
        }.panelScreen().navigationTitle("Serverneustart").errorAlert($model.error)
            .task { while !Task.isCancelled { await load();do {try await Task.sleep(for:.seconds(2))}catch{break} } }
            .sheet(isPresented:Binding(get:{model.plan != nil},set:{if !$0{model.plan=nil}})){SuiteSecurityConfirmation(model:model){await load()}}
    }
    func prepare(cancel:Bool){Task{await model.prepare("restart.apply",["operation":.string(cancel ? "cancel":"schedule"),"seconds":.number(Double(seconds)),"reasonDE":.string(reasonDE),"reasonEN":.string(reasonEN)],summary:cancel ? "Geplanten Serverneustart abbrechen?":"Server in \(seconds) Sekunden neu starten?\nDE: \(reasonDE)\nEN: \(reasonEN)")}}
    func load()async{do{status=try await suite.data("operations")}catch{model.error=error.localizedDescription}}
}
