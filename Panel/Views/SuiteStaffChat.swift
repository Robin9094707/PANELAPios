import SwiftUI
import PhotosUI
import AVFoundation
import UniformTypeIdentifiers

/// Admin Suite 9.7.0 staff chat: the private Discord staff channel, in-game /staffmsg and the app in one
/// conversation. Text, images, files and voice messages travel both ways; the bot posts in your name.
@MainActor final class SuiteStaffChatModel: ObservableObject {
    @Published var items: [JSON] = []
    @Published var info: JSON = .null
    @Published var error: String?
    @Published var sending = false
    private var cursor = 0
    let suite: SuiteAPI
    init(_ suite: SuiteAPI) { self.suite = suite }
    var members: [JSON] { info["members"].array }
    var you: String { info["you"].string }
    func load(reset: Bool = false) async {
        do {
            let data = try await suite.data("staff-chat", query: ["after": String(reset ? 0 : cursor), "epoch": info["epoch"].string])
            let changed = data["epoch"] != info["epoch"]
            info = data
            let fresh = data["items"].array
            if reset || cursor == 0 || changed { items = fresh }
            else { let known = Set(items.map { $0["id"].string }); items += fresh.filter { !known.contains($0["id"].string) } }
            if items.count > 400 { items = Array(items.suffix(400)) }
            cursor = data["cursor"].int; error = nil
        } catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }
    func send(text: String, pings: [String]) async -> Bool {
        sending = true; defer { sending = false }
        do { _ = try await suite.request("staff-chat", method: "POST", body: .object(["text": .string(text), "pings": .array(pings.map(JSON.string))])); await load(); Feedback.success(); return true }
        catch { self.error = error.localizedDescription; return false }
    }
    func upload(name: String, type: String, data: Data, text: String, pings: [String]) async -> Bool {
        sending = true; defer { sending = false }
        do { _ = try await suite.upload("staff-chat/upload", query: ["name": name, "text": text, "pings": pings.joined(separator: ",")], data: data, contentType: type); await load(); Feedback.success(); return true }
        catch { self.error = error.localizedDescription; return false }
    }
}

/// Voice messages: AAC in an .m4a container, which Discord and iOS both play inline.
@MainActor final class SuiteVoiceRecorder: NSObject, ObservableObject, AVAudioRecorderDelegate {
    @Published var recording = false
    @Published var elapsed: TimeInterval = 0
    private var recorder: AVAudioRecorder?
    private var timer: Timer?
    private let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".m4a")
    private var starting = false
    func start(maxDuration: TimeInterval = 300) async throws {
        guard !starting, !recording else { return }
        starting = true; defer { starting = false }
        guard await AVAudioApplication.requestRecordPermission() else { throw PanelError(message: "Mikrofonzugriff ist in den iOS-Einstellungen für Simplex Panel deaktiviert.") }
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, mode: .spokenAudio, options: [.defaultToSpeaker])
        try session.setActive(true)
        try? FileManager.default.removeItem(at: url)
        let settings: [String: Any] = [AVFormatIDKey: Int(kAudioFormatMPEG4AAC), AVSampleRateKey: 44_100, AVNumberOfChannelsKey: 1, AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue]
        let recorder = try AVAudioRecorder(url: url, settings: settings)
        recorder.delegate = self
        guard recorder.record(forDuration: maxDuration) else {
            try? session.setActive(false, options: .notifyOthersOnDeactivation)
            throw PanelError(message: "Mikrofonaufnahme konnte nicht gestartet werden.")
        }
        self.recorder = recorder
        recording = true; elapsed = 0
        timer = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in Task { @MainActor in
            if let self, let recorder = self.recorder, recorder.isRecording { self.elapsed = recorder.currentTime }
         } }
    }
    func stop() -> Data? {
        recorder?.stop(); timer?.invalidate(); timer = nil; recording = false
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        defer { recorder = nil; try? FileManager.default.removeItem(at: url) }
        guard elapsed >= 0.6, let data = try? Data(contentsOf: url), !data.isEmpty else { return nil }
        return data
    }
    func cancel() { _ = stop() }
}

@MainActor final class SuiteAudioPlayer: ObservableObject {
    static let shared = SuiteAudioPlayer()
    @Published var playing: String?
    private var player: AVAudioPlayer?
    func toggle(_ id: String, data: Data) {
        if playing == id { player?.stop(); playing = nil; return }
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio)
            try AVAudioSession.sharedInstance().setActive(true)
            player = try AVAudioPlayer(data: data); player?.play(); playing = id
            let duration = player?.duration ?? 0
            Task { try? await Task.sleep(for: .seconds(duration + 0.2)); if self.playing == id { self.playing = nil } }
        } catch { playing = nil }
    }
}

struct SuiteStaffChatView: View {
    let suite: SuiteAPI
    @StateObject private var model: SuiteStaffChatModel
    @StateObject private var recorder = SuiteVoiceRecorder()
    @Environment(\.scenePhase) private var scenePhase
    @State private var text = ""
    @State private var pings: Set<String> = []
    @State private var photos: [PhotosPickerItem] = []
    @State private var importing = false
    @State private var settings = false
    @State private var showAssistant = false
    @State private var atBottom = true
    init(suite: SuiteAPI) { self.suite = suite; _model = StateObject(wrappedValue: SuiteStaffChatModel(suite)) }
    var body: some View {
        ScrollViewReader { proxy in
            ScrollView { messages }
                .defaultScrollAnchor(.bottom).scrollDismissesKeyboard(.interactively).panelScreen()
                .safeAreaInset(edge: .bottom, spacing: 0) { composer }
                .onChange(of: model.items.count) { old, new in follow(proxy, old: old, new: new) }
                .task(id: scenePhase) { await poll(proxy) }
        }
        .navigationTitle("Staff-Chat").navigationBarTitleDisplayMode(.inline).errorAlert($model.error)
        .toolbar {
            if suite.features.contains("admin-assistant-chat-v1"), suite.can("ai.assistant.use") {
                ToolbarItem(placement: .topBarTrailing) { Button("Privaten KI-Chat öffnen", systemImage: "sparkles") { showAssistant = true } }
            }
            if suite.features.contains("history-management-v1") { ToolbarItem(placement: .topBarTrailing) { SuiteHistoryLink(suite: suite, kind: "staff-chat", title: "Staff-Chat") } }
            ToolbarItem(placement: .topBarTrailing) { Button("Staff-Chat einstellen", systemImage: "gearshape") { settings = true }.disabled(suite.scope != "full") } }
        .sheet(isPresented: $showAssistant) {
            NavigationStack { SuiteAIAssistantView(suite: suite).toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Staff-Chat") { showAssistant = false } }
            } }
        }
        .sheet(isPresented: $settings, onDismiss: { Task { await model.load(reset: true) } }) { NavigationStack { SuiteStaffChatSettingsView(suite: suite) } }
        .sheet(isPresented: $importing) { filePicker }
        .onChange(of: photos) { _, items in Task { await sendPhotos(items) } }
    }
    var filePicker: some View {
        FileImportPicker(multiple: false, retainSelection: true) { result in
            importing = false
            if case .success(let urls) = result, let url = urls.first { Task { await sendFile(url) } }
        }.ignoresSafeArea()
    }
    @ViewBuilder var notices: some View {
        if model.info != .null && !model.info["discord"].bool {
            Label("Kein Discord-Kanal festgelegt. App und Minecraft teilen den Staff-Chat; in den Einstellungen oben rechts verbindest du Discord.", systemImage: "info.circle")
                .font(.caption).foregroundStyle(.secondary).padding(12).frame(maxWidth: .infinity, alignment: .leading)
                .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 14))
        }
        if !model.info["lastError"].string.isEmpty { Label(model.info["lastError"].string, systemImage: "exclamationmark.triangle").font(.caption).foregroundStyle(.orange) }
        if model.items.isEmpty && model.info != .null {
            ContentUnavailableView("Noch keine Nachrichten", systemImage: "bubble.left.and.text.bubble.right", description: Text("Schreib dem Team – Discord, Minecraft und die App sehen es sofort.")).padding(.top, 40)
        }
    }
    var messages: some View {
        LazyVStack(spacing: 10) {
            notices
            ForEach(model.items, id: \.["id"].string) { row in bubble(row) }
            Color.clear.frame(height: 1).id("staff-bottom").onAppear { atBottom = true }.onDisappear { atBottom = false }
        }.padding(.horizontal, 14).padding(.vertical, 10).frame(maxWidth: 900).frame(maxWidth: .infinity)
    }
    func isMine(_ row: JSON) -> Bool { row["source"].string == "app" && row["author"].string.caseInsensitiveCompare(model.you) == .orderedSame }
    func bubble(_ row: JSON) -> some View { SuiteStaffBubble(suite: suite, row: row, mine: isMine(row)).id("s-" + row["id"].string) }
    func follow(_ proxy: ScrollViewProxy, old: Int, new: Int) {
        guard new > old && (atBottom || old == 0) else { return }
        withAnimation(.snappy) { proxy.scrollTo("staff-bottom", anchor: .bottom) }
    }
    func poll(_ proxy: ScrollViewProxy) async {
        guard scenePhase == .active else { return }
        await model.load(reset: true); proxy.scrollTo("staff-bottom", anchor: .bottom)
        while !Task.isCancelled { do { try await Task.sleep(for: .seconds(4)) } catch { return }; await model.load() }
    }
    var pingTitle: String {
        if pings.contains("all") { return "@alle" }
        let names = model.members.filter { pings.contains($0["id"].string) }.map { "@" + $0["name"].string }
        return names.joined(separator: " ")
    }
    var composer: some View {
        VStack(alignment: .leading, spacing: 6) {
            if !pings.isEmpty { HStack { Label(pingTitle, systemImage: "at").font(.caption.weight(.semibold)).foregroundStyle(Color.panelAccent).lineLimit(1); Spacer(); Button("Entfernen") { pings = [] }.font(.caption) } }
            if recorder.recording {
                HStack(spacing: 12) {
                    Image(systemName: "waveform").symbolEffect(.variableColor.iterative, isActive: true).foregroundStyle(.red)
                    Text("Aufnahme " + Duration.seconds(recorder.elapsed).formatted(.time(pattern: .minuteSecond))).font(.subheadline.monospacedDigit())
                    Spacer()
                    Button("Verwerfen", role: .destructive) { recorder.cancel() }.font(.subheadline)
                    Button { Task { await finishRecording() } } label: { Image(systemName: "arrow.up.circle.fill").font(.system(size: 32)).foregroundStyle(Color.panelAccent) }.accessibilityLabel("Sprachnachricht senden")
                }
            } else {
                HStack(alignment: .bottom, spacing: 8) {
                    Menu {
                        Button("Datei senden", systemImage: "paperclip") { importing = true }
                        Section("Erwähnen") {
                            Toggle(isOn: Binding(get: { pings.contains("all") }, set: { if $0 { pings = ["all"] } else { pings.remove("all") } })) { Label("Alle Admins", systemImage: "person.3.fill") }
                            ForEach(model.members, id: \.["id"].string) { member in
                                Toggle(isOn: Binding(get: { pings.contains(member["id"].string) }, set: { if $0 { pings.remove("all"); pings.insert(member["id"].string) } else { pings.remove(member["id"].string) } })) { Text(member["name"].string) }
                            }
                        }
                    } label: { Image(systemName: "plus.circle.fill").font(.title2).foregroundStyle(Color.panelAccent) }.accessibilityLabel("Datei senden oder Admins erwähnen")
                    PhotosPicker(selection: $photos, maxSelectionCount: 4, matching: .images) { Image(systemName: "photo").font(.title3).foregroundStyle(Color.panelAccent) }.accessibilityLabel("Bilder senden")
                    TextField("Nachricht an das Team", text: $text, axis: .vertical).lineLimit(1...6)
                        .padding(.horizontal, 12).padding(.vertical, 8).background(Color.secondary.opacity(0.12), in: RoundedRectangle(cornerRadius: 18)).accessibilityIdentifier("staffChatInput")
                    if text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                        Button { Task { do { try await recorder.start() } catch { model.error = error.localizedDescription } } } label: { Image(systemName: "mic.circle.fill").font(.system(size: 32)).foregroundStyle(Color.panelAccent) }.disabled(model.sending).accessibilityLabel("Sprachnachricht aufnehmen")
                    } else {
                        Button { Task { if await model.send(text: text.trimmingCharacters(in: .whitespacesAndNewlines), pings: Array(pings)) { text = ""; pings = [] } } } label: { if model.sending { ProgressView().frame(width: 32, height: 32) } else { Image(systemName: "arrow.up.circle.fill").font(.system(size: 32)).foregroundStyle(Color.panelAccent) } }.disabled(model.sending).accessibilityLabel("Senden")
                    }
                }
            }
            Text(model.you.isEmpty ? "Wird im Discord-Staffkanal und bei Admins im Spiel angezeigt." : "Du schreibst als \(model.you) – in Discord, Minecraft und der App.").font(.caption2).foregroundStyle(.secondary)
        }.padding(.horizontal, 14).padding(.top, 10).padding(.bottom, 8).background(.bar)
    }
    func finishRecording() async {
        guard let data = recorder.stop() else { model.error = "Die Aufnahme war zu kurz."; return }
        let stamp = String(Int(Date().timeIntervalSince1970))
        if await model.upload(name: "sprachnachricht-" + stamp + ".m4a", type: "audio/mp4", data: data, text: text, pings: Array(pings)) { text = ""; pings = [] }
    }
    func sendPhotos(_ items: [PhotosPickerItem]) async {
        guard !items.isEmpty else { return }
        for (index, item) in items.enumerated() {
            guard let data = try? await item.loadTransferable(type: Data.self), let image = UIImage(data: data) else { continue }
            let scaled = SuiteTicketDraft.downscale(image, maxPixel: 2400)
            guard let jpeg = scaled.jpegData(compressionQuality: 0.82), jpeg.count <= 8 * 1024 * 1024 else { model.error = "Ein Bild ist zu groß."; continue }
            _ = await model.upload(name: "bild-\(index + 1).jpg", type: "image/jpeg", data: jpeg, text: index == 0 ? text : "", pings: index == 0 ? Array(pings) : [])
        }
        photos = []; text = ""; pings = []
    }
    func sendFile(_ url: URL) async {
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        guard let data = try? Data(contentsOf: url), !data.isEmpty, data.count <= 8 * 1024 * 1024 else { model.error = "Dateien bis 8 MiB werden unterstützt."; return }
        let type = UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "application/octet-stream"
        if await model.upload(name: url.lastPathComponent, type: type, data: data, text: text, pings: Array(pings)) { text = ""; pings = [] }
    }
}

struct SuiteStaffBubble: View {
    let suite: SuiteAPI
    let row: JSON
    let mine: Bool
    var sourceTitle: String { switch row["source"].string { case "discord": return "Discord"; case "ingame": return "Minecraft"; default: return "App" } }
    var sourceSymbol: String { switch row["source"].string { case "discord": return "bubble.left.and.bubble.right.fill"; case "ingame": return "cube.fill"; default: return "iphone" } }
    var body: some View {
        HStack(alignment: .bottom, spacing: 8) {
            if mine { Spacer(minLength: 44) }
            VStack(alignment: mine ? .trailing : .leading, spacing: 4) {
                HStack(spacing: 5) {
                    Text(row["author"].string).font(.caption.weight(.semibold))
                    Label(sourceTitle, systemImage: sourceSymbol).labelStyle(.titleAndIcon).font(.caption2).foregroundStyle(.secondary)
                }
                if !row["mentions"].array.isEmpty { Text(row["mentions"].array.map { "@" + $0.string }.joined(separator: " ")).font(.caption2.weight(.semibold)).foregroundStyle(Color.panelAccent) }
                if !row["text"].string.isEmpty {
                    Text((try? AttributedString(markdown: row["text"].string, options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace))) ?? AttributedString(row["text"].string))
                        .textSelection(.enabled).padding(.horizontal, 12).padding(.vertical, 9)
                        .background(mine ? Color.panelAccent.opacity(0.22) : Color.secondary.opacity(0.13), in: RoundedRectangle(cornerRadius: 16))
                }
                ForEach(row["attachments"].array, id: \.["id"].string) { file in SuiteStaffAttachment(suite: suite, file: file) }
                if let date = SuitePlayerPresentation.date(row["at"]) { Text(date, format: .dateTime.hour().minute()).font(.caption2).foregroundStyle(.secondary) }
            }
            if !mine { Spacer(minLength: 44) }
        }.frame(maxWidth: .infinity, alignment: mine ? .trailing : .leading)
    }
}

struct SuiteStaffAttachment: View {
    let suite: SuiteAPI
    let file: JSON
    @ObservedObject private var player = SuiteAudioPlayer.shared
    @State private var data: Data?
    @State private var image: UIImage?
    @State private var shareURL: URL?
    @State private var failed = false
    var id: String { file["id"].string }
    var body: some View {
        Group {
            if file["image"].bool {
                if let image { Image(uiImage: image).resizable().scaledToFit().frame(maxWidth: 240, maxHeight: 260).clipShape(RoundedRectangle(cornerRadius: 14)).onTapGesture { share() } }
                else { RoundedRectangle(cornerRadius: 14).fill(.thinMaterial).frame(width: 180, height: 120).overlay { if failed { Image(systemName: "photo.badge.exclamationmark") } else { ProgressView() } } }
            } else if file["audio"].bool {
                Button { Task { await playAudio() } } label: {
                    HStack(spacing: 10) {
                        Image(systemName: player.playing == id ? "stop.circle.fill" : "play.circle.fill").font(.title2)
                        Image(systemName: "waveform").symbolEffect(.variableColor.iterative, isActive: player.playing == id)
                        Text("Sprachnachricht").font(.subheadline)
                        Text(ByteCountFormatter.string(fromByteCount: Int64(file["size"].int), countStyle: .file)).font(.caption2).foregroundStyle(.secondary)
                    }.padding(.horizontal, 12).padding(.vertical, 9).background(Color.panelAccent.opacity(0.14), in: Capsule())
                }.buttonStyle(.plain).foregroundStyle(Color.panelAccent)
            } else {
                Button { share() } label: { Label(file["name"].string + " · " + ByteCountFormatter.string(fromByteCount: Int64(file["size"].int), countStyle: .file), systemImage: "doc.fill").font(.subheadline).padding(.horizontal, 12).padding(.vertical, 9).background(.thinMaterial, in: Capsule()) }.buttonStyle(.plain)
            }
        }
        .task(id: id) { if file["image"].bool { await fetch(); if let data { image = UIImage(data: data) } } }
        .sheet(item: Binding(get: { shareURL.map { ShareItem(url: $0) } }, set: { if $0 == nil { shareURL = nil } })) { ShareSheet(url: $0.url) }
    }
    func fetch() async {
        if data != nil { return }
        do { data = try await suite.raw("staff-chat-file", query: ["id": id]) } catch { failed = true }
    }
    func playAudio() async { await fetch(); if let data { player.toggle(id, data: data) } }
    func share() { Task { await fetch(); guard let data else { return }; let url = FileManager.default.temporaryDirectory.appendingPathComponent(file["name"].string.isEmpty ? "datei" : file["name"].string); try? data.write(to: url); shareURL = url } }
}

struct SuiteStaffChatSettingsView: View {
    let suite: SuiteAPI
    @Environment(\.dismiss) private var dismiss
    @StateObject private var runner: SuiteActionRunner
    @State private var channels: [JSON] = []
    @State private var channel = ""
    @State private var owner = ""
    init(suite: SuiteAPI) { self.suite = suite; _runner = StateObject(wrappedValue: SuiteActionRunner(suite)) }
    var body: some View {
        Form {
            Section {
                Picker("Discord-Kanal", selection: $channel) {
                    Text("Nur App & Minecraft").tag("")
                    ForEach(channels, id: \.["id"].string) { row in Text("#" + row["name"].string).tag(row["id"].string) }
                }
                Button("Kanal speichern", systemImage: "checkmark.circle") { Task { await runner.run("staffchat.update", ["channel": .string(channel)]) } }.disabled(runner.busy)
            } header: { Text("Staff-Kanal") } footer: { Text("Nachrichten, Bilder, Dateien und Sprachnachrichten werden in beide Richtungen synchronisiert. Der Bot braucht Lese-/Schreibrechte und für den Absendernamen „Webhooks verwalten“.") }
            Section {
                TextField("z. B. Robin", text: $owner).textInputAutocapitalization(.words)
                Button("Namen speichern", systemImage: "person.text.rectangle") { Task { await runner.run("app.settings", ["key": .string("app-api.owner-name"), "value": .string(owner)]) } }.disabled(runner.busy)
            } header: { Text("Dein Name als Hauptadmin") } footer: { Text("Gilt für den Hauptzugang (API-Schlüssel). App-Benutzer schreiben automatisch mit ihrem Minecraft-Namen. Kein „iPhone-App“-Zusatz.") }
            if !runner.notice.isEmpty { Section { Label(runner.notice, systemImage: "checkmark.circle.fill").foregroundStyle(.green) } }
        }.panelScreen().navigationTitle("Staff-Chat").navigationBarTitleDisplayMode(.inline).errorAlert($runner.error)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Fertig") { dismiss() } } }
            .task {
                do { let info = try await suite.data("staff-chat", query: ["after": "999999999"]); channel = info["channel"].string; owner = info["ownerName"].string } catch { runner.error = error.localizedDescription }
                if let list = try? await suite.data("discord-channels") { channels = list["items"].array }
            }
    }
}

/// Runs a suite action after an in-app confirmation (prepare + execute with the server-bound ticket).
@MainActor final class SuiteActionRunner: ObservableObject {
    @Published var busy = false
    @Published var error: String?
    @Published var notice = ""
    @Published var result: JSON = .null
    let suite: SuiteAPI
    init(_ suite: SuiteAPI) { self.suite = suite }
    @discardableResult func run(_ path: String, _ values: [String: JSON]) async -> JSON? {
        busy = true; defer { busy = false }
        do {
            let plan = try await suite.prepare(path, values: values)
            let reply = try await suite.execute(plan["ticket"].string)
            guard reply["ok"].bool || reply["ok"] == .null else { error = reply["description"].string.isEmpty ? reply["title"].string : reply["description"].string; return nil }
            result = reply; notice = reply["title"].string; Feedback.success(); return reply
        } catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription }; return nil }
    }
}
