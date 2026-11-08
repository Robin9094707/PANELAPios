import SwiftUI
import AVFoundation

struct SuiteAIAssistantView: View {
    let suite: SuiteAPI
    var panel: PanelAPI?
    var server: Server?
    @State private var status: JSON = .null
    @State private var result: JSON = .null
    @State private var inbox: [JSON] = []
    @State private var limits: JSON = .null
    @State private var input = ""
    @State private var account = ""
    @State private var daily = "15"
    @State private var error: String?
    @State private var work: Task<Void, Never>?
    @State private var busy = false
    @State private var confirming = false
    @State private var showSummary = false
    @State private var response: [JSON] = []
    @State private var selection: Set<String> = []
    @State private var summaryOverride = ""
    @State private var chats: [JSON] = []
    @State private var chat: JSON = .null
    @State private var turns: [JSON] = []
    @State private var pending = ""
    @State private var showChats = false
    @State private var showOwner = false
    @State private var deleting = false
    @State private var chatTitle = ""
    @State private var keepDays = 0
    @State private var draft: Data?
    @State private var draftTranscribed = false
    @State private var draftID = UUID().uuidString
    @State private var speaker = AVSpeechSynthesizer()
    @StateObject private var recorder = SuiteVoiceRecorder()
    @ObservedObject private var audio = SuiteAudioPlayer.shared
    @Environment(\.scenePhase) private var scenePhase

    private var threaded: Bool { suite.features.contains("admin-assistant-chat-v1") }
    private var chatID: String { chat["id"].string }
    private var chatName: String { chat["title"].string }

    private var supported: Bool { suite.features.contains("admin-assistant-v1") }
    private var actions: [JSON] { result["actions"].array }
    private var owner: Bool { status["owner"].bool }
    private var ready: Bool { result["status"].string == "ready" }
    private var canAsk: Bool { status["available"].bool && !busy }
    private let examples = ["Gab es letzte Nacht Vorfälle im Chat?", "Was haben die Admins zuletzt gemacht, und wann waren sie im Vanish?", "Prüfe die letzten Disconnects. Schlage bei Bedarf einen Neustart in zehn Minuten vor.", "Welche Tickets sind aktuell offen?"]

    var body: some View {
        Group {
            if threaded { chatView }
            else { legacyView }
        }
        .panelScreen()
        .navigationTitle("KI-Assistent")
        .task { await refresh(); if threaded { await restoreChat() } }
        .refreshable { await refresh(); if threaded && !busy { await restoreChat() } }
        .onDisappear {
            work?.cancel(); recorder.cancel(); stopDraftAudio()
            speaker.stopSpeaking(at: .immediate)
        }
        .task(id: recorder.recording) {
            guard recorder.recording else { return }
            do { try await Task.sleep(for: .seconds(120)) } catch { return }
            if recorder.recording { finishRecording() }
        }
        .onChange(of: scenePhase) { _, phase in if phase != .active && recorder.recording { finishRecording() } }
        .errorAlert($error)
        .confirmationDialog("Diesen Aktionsplan verbindlich ausführen?", isPresented: $confirming, titleVisibility: .visible) {
            Button("\(selection.count) Aktionen bestätigen", role: .destructive) { mutate("approve") }
        } message: {
            Text("Der Server führt die angezeigten Schritte nacheinander aus. Zu jedem Schritt erscheint ein Ergebnis.")
        }
    }

    private var legacyView: some View {
        List {
            if !supported {
                Section("Vorschau") {
                    Label("Der KI-Assistent kommt mit Admin Suite 10.8.0", systemImage: "sparkles")
                    Text("Chat, Tickets, Spielerakten, Admin-Log und Diagnose gezielt abfragen. Schreibvorschläge zeigt die App zuerst vollständig zur Freigabe an.")
                    ForEach(examples, id: \.self) { Text($0).foregroundStyle(.secondary) }
                    if let panel, let server { NavigationLink("Plugin-Updates öffnen") { PluginsView(api: panel, server: server) } }
                }
            } else {
                Section("Serverassistent") {
                    if status == .null && error == nil { ProgressView("KI-Zugang prüfen …") }
                    if status != .null && !status["available"].bool {
                        Label("KI ist auf diesem Server momentan nicht verfügbar.", systemImage: "sparkles.slash").foregroundStyle(.secondary)
                    }
                    if status != .null {
                        Text(owner ? "Hauptadmin · ohne Tageslimit" : "Heute genutzt: \(status["used"].int) / \(status["limit"].int)").font(.caption).foregroundStyle(.secondary)
                    }
                    TextField("Was soll die KI prüfen?", text: $input, axis: .vertical).lineLimit(2...5)
                    Button("Fragen", systemImage: "arrow.up.circle.fill") { send() }
                        .disabled(!canAsk || input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || input.count > 2000)
                    if busy { ProgressView("Antwort und Datenquellen werden geprüft …") }
                    DisclosureGroup("Beispielfragen") {
                        ForEach(examples, id: \.self) { example in Button(example) { input = example }.disabled(busy) }
                    }
                }
                answer
                if owner { ownerControls }
                Section { NavigationLink("Manuelle Werkzeuge") { SuiteToolDirectoryView(suite: suite, panel: panel, server: server) } }
            }
        }
    }

    private var chatView: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 12) {
                    Text(chatName.isEmpty ? "Privater KI-Chat" : chatName).font(.headline)
                    if status == .null { ProgressView("KI-Zugang prüfen …") }
                    else if !status["available"].bool { Label("KI momentan nicht verfügbar", systemImage: "sparkles.slash").foregroundStyle(.secondary) }
                    else { Text(owner ? "Privat · ohne Tageslimit" : "Privat · \(status["used"].int) / \(status["limit"].int) heute").font(.caption).foregroundStyle(.secondary) }
                    if turns.isEmpty && pending.isEmpty {
                        ForEach(examples, id: \.self) { example in Button(example) { input = example }.buttonStyle(.bordered).disabled(busy) }
                    }
                    ForEach(Array(turns.enumerated()), id: \.offset) { _, turn in
                        bubble(turn["question"].string, mine: true, at: turn["at"])
                        bubble(turn["answer"].string, mine: false, at: turn["at"])
                        if !turn["job"].string.isEmpty && turn["job"].string != result["id"].string,
                           Double(turn["at"].int) / 1000 > Date().timeIntervalSince1970 - 1200 {
                            Button("Antwortdetails", systemImage: "list.bullet") { openResult(turn["job"].string) }
                                .font(.caption).disabled(busy)
                        }
                    }
                    if !pending.isEmpty { bubble(pending, mine: true, at: .null) }
                    if busy && !pending.isEmpty { ProgressView("Die KI antwortet …").font(.callout) }
                    if result != .null { answer.padding(12).frame(maxWidth: .infinity, alignment: .leading).background(.thinMaterial, in: RoundedRectangle(cornerRadius: 16)) }
                    Color.clear.frame(height: 1).id("assistant-bottom")
                }
                .padding(.horizontal, 14).padding(.vertical, 10)
                .frame(maxWidth: 800).frame(maxWidth: .infinity)
            }
            .defaultScrollAnchor(.bottom)
            .scrollDismissesKeyboard(.interactively)
            .safeAreaInset(edge: .bottom, spacing: 0) { composer }
            .onChange(of: turns.count) { _, _ in proxy.scrollTo("assistant-bottom", anchor: .bottom) }
            .onChange(of: pending) { _, _ in proxy.scrollTo("assistant-bottom", anchor: .bottom) }
            .onChange(of: result["id"].string) { _, _ in proxy.scrollTo("assistant-bottom", anchor: .bottom) }
        }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("Chats verwalten", systemImage: "bubble.left.and.bubble.right") { showChats = true }.disabled(busy || recorder.recording || status == .null)
            }
            if owner {
                ToolbarItem(placement: .topBarTrailing) { Button("Freigaben und Limits", systemImage: "checkmark.shield") { showOwner = true }.disabled(busy || recorder.recording) }
            }
        }
        .sheet(isPresented: $showChats) { chatManager }
        .sheet(isPresented: $showOwner) {
            NavigationStack {
                List { ownerControls }
                    .navigationTitle("Freigaben & Limits")
                    .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Fertig") { showOwner = false } } }
            }
        }
    }

    private func bubble(_ text: String, mine: Bool, at: JSON) -> some View {
        HStack(alignment: .bottom) {
            if mine { Spacer(minLength: 40) }
            VStack(alignment: .leading, spacing: 4) {
                Text(mine ? "Du" : "Simplex KI").font(.caption.weight(.semibold))
                Text(text.isEmpty ? "Keine Textantwort" : text).textSelection(.enabled)
                HStack(spacing: 10) {
                    if let date = SuitePlayerPresentation.date(at) { Text(date, format: .dateTime.hour().minute()) }
                    if !mine && !text.isEmpty {
                        Button("Vorlesen", systemImage: "speaker.wave.2") { speak(text) }.buttonStyle(.plain)
                    }
                }.font(.caption2).foregroundStyle(.secondary)
            }
            .padding(.horizontal, 12).padding(.vertical, 8)
            .background(mine ? Color.panelAccent.opacity(0.22) : Color.secondary.opacity(0.13), in: RoundedRectangle(cornerRadius: 16))
            if !mine { Spacer(minLength: 40) }
        }.frame(maxWidth: .infinity)
    }

    private var composer: some View {
        VStack(alignment: .leading, spacing: 8) {
            if recorder.recording {
                HStack {
                    Label(Duration.seconds(recorder.elapsed).formatted(.time(pattern: .minuteSecond)), systemImage: "waveform").foregroundStyle(.red)
                    Spacer()
                    Button("Verwerfen", role: .destructive) { recorder.cancel() }
                    Button("Stopp", systemImage: "stop.circle.fill") { finishRecording() }
                }
            } else {
                if let draft {
                    HStack {
                        Button(audio.playing == draftID ? "Stopp" : "Anhören", systemImage: audio.playing == draftID ? "stop.circle" : "play.circle") { audio.toggle(draftID, data: draft) }
                        Spacer()
                        Button(draftTranscribed ? "Transkribiert" : "Transkribieren", systemImage: "text.bubble") { transcribe(draft) }.disabled(!canAsk || draftTranscribed)
                        Button("Aufnahme verwerfen", systemImage: "trash", role: .destructive) { stopDraftAudio(); self.draft = nil }.labelStyle(.iconOnly)
                    }.font(.subheadline).disabled(busy)
                    Text("Die Aufnahme wird beim Transkribieren an OpenAI übermittelt.").font(.caption2).foregroundStyle(.secondary)
                }
                HStack(alignment: .bottom) {
                    Button("Aufnehmen", systemImage: "mic.circle.fill") {
                        Task {
                            do { stopDraftAudio(); try await recorder.start(maxDuration: 120) }
                            catch { self.error = error.localizedDescription }
                        }
                    }.labelStyle(.iconOnly).font(.title).disabled(!canAsk || draft != nil)
                    TextField("Nachricht an die KI", text: $input, axis: .vertical).lineLimit(1...5)
                        .padding(9).background(Color.secondary.opacity(0.12), in: RoundedRectangle(cornerRadius: 14))
                    Button("Senden", systemImage: "arrow.up.circle.fill") { send() }.labelStyle(.iconOnly).font(.title)
                        .disabled(!canAsk || input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || input.utf16.count > 2000)
                }
            }
        }.buttonStyle(.borderless).padding(.horizontal, 14).padding(.vertical, 8).background(.bar)
    }

    private var chatManager: some View {
        NavigationStack {
            Form {
                Section("Deine privaten Chats") {
                    Button("Neuer Chat", systemImage: "plus.bubble") { run { try await createChat(); showChats = false } }.disabled(busy)
                    ForEach(chats, id: \.["id"].string) { row in
                        Button {
                            run { try await selectChat(row["id"].string); showChats = false }
                        } label: {
                            HStack { Text(row["title"].string); Spacer(); if chatID == row["id"].string { Image(systemName: "checkmark") } }
                        }.disabled(busy)
                    }
                }
                if !chatID.isEmpty {
                    Section {
                        TextField("Chatname", text: $chatTitle)
                        Picker("Automatisch löschen", selection: $keepDays) {
                            Text("Aus").tag(0)
                            Text("Nach 1 Tag").tag(1)
                            Text("Nach 7 Tagen").tag(7)
                            Text("Nach 30 Tagen").tag(30)
                        }
                        Button("Speichern") { run { try await updateChat() } }.disabled(busy)
                        Button("Chat löschen", role: .destructive) { deleting = true }.disabled(busy)
                    } header: { Text("Aktiver Chat") } footer: { Text("Die Frist beginnt mit der letzten Aktivität in diesem Chat.") }
                }
            }
            .navigationTitle("KI-Chats")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Fertig") { showChats = false }.disabled(busy) } }
            .confirmationDialog("Diesen Chat und offene Vorschläge löschen?", isPresented: $deleting, titleVisibility: .visible) {
                Button("Chat löschen", role: .destructive) {
                    let id = chatID
                    run {
                        _ = try await chatChange("delete", id: id)
                        resetChat(); try await loadChats()
                    }
                }
            }
        }
    }

    private func speak(_ text: String) {
        if speaker.isSpeaking { speaker.stopSpeaking(at: .immediate); return }
        stopDraftAudio()
        let utterance = AVSpeechUtterance(string: text)
        speaker.speak(utterance)
    }
    private func finishRecording() {
        guard let data = recorder.stop() else { error = "Die Aufnahme war zu kurz."; return }
        draftID = UUID().uuidString
        draftTranscribed = false; draft = data
    }
    private func stopDraftAudio() {
        if audio.playing == draftID, let draft { audio.toggle(draftID, data: draft) }
    }
    private func transcribe(_ data: Data) {
        stopDraftAudio()
        run {
            let reply = try await suite.upload("assistant/transcribe", query: [:], data: data, contentType: "audio/mp4", timeout: 65)
            let transcript = reply["text"].string
            let joined = input.isEmpty ? transcript : input + "\n" + transcript
            guard joined.utf16.count <= 2000 else { throw PanelError(message: "Der Entwurf ist zu lang. Kürze zuerst den Text und versuche es erneut.") }
            input = joined
            draftTranscribed = true
            await refresh()
        }
    }
    private func chatChange(_ operation: String, id: String = "", title: String = "", days: Int = 0) async throws -> JSON {
        try await suite.request("assistant/chats", method: "POST", body: .object([
            "operation": .string(operation), "id": .string(id), "title": .string(title), "days": .number(Double(days))
        ]), timeout: 12)
    }
    private func loadChats() async throws {
        chats = try await suite.request("assistant/chats", timeout: 12)["items"].array
    }
    private func restoreChat() async {
        do {
            try await loadChats()
            guard !busy else { return }
            if !chatID.isEmpty && chats.contains(where: { $0["id"].string == chatID }) { try await loadChat(chatID) }
            else if let first = chats.first { resetChat(keepDraft: true); try await loadChat(first["id"].string) }
            else if !chatID.isEmpty { resetChat(keepDraft: true) }
        } catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }
    private func loadChat(_ id: String) async throws {
        let row = try await suite.request("assistant/chats", query: ["id": id], timeout: 12)
        chat = row; turns = row["items"].array; chatTitle = row["title"].string; keepDays = row["days"].int
    }
    private func createChat() async throws {
        let row = try await chatChange("create")
        resetChat(keepDraft: true)
        try await loadChat(row["id"].string); try await loadChats()
    }
    private func updateChat() async throws {
        let id = chatID
        let updated = try await chatChange("update", id: id, title: chatTitle, days: keepDays)
        if updated["expired"].bool { resetChat() } else { try await loadChat(id) }
        try await loadChats()
    }
    private func selectChat(_ id: String) async throws {
        resetChat()
        try await loadChat(id)
    }
    private func resetChat(keepDraft: Bool = false) {
        chat = .null; turns = []; result = .null; pending = ""; response = []; selection = []; summaryOverride = ""
        if !keepDraft { stopDraftAudio(); draft = nil; input = "" }
    }
    private func openResult(_ id: String) {
        run {
            result = try await suite.request("assistant/job", query: ["id": id], timeout: 12)
            selection = []; response = result["results"].array; summaryOverride = ""
        }
    }

    @ViewBuilder private var answer: some View {
        if result != .null {
            Section("Antwort") {
                if !result["answer"].string.isEmpty && !turns.contains(where: { $0["job"].string == result["id"].string }) {
                    Text(result["answer"].string).textSelection(.enabled)
                }
                if !result["sources"].array.isEmpty {
                    Text("Datenquellen · " + result["sources"].array.map(\.string).joined(separator: ", ")).font(.caption).foregroundStyle(.secondary)
                }
                if !result["summary"].string.isEmpty || !summaryOverride.isEmpty {
                    DisclosureGroup("KI-Kurzfassung", isExpanded: $showSummary) { Text(summaryOverride.isEmpty ? result["summary"].string : summaryOverride).textSelection(.enabled) }
                }
                ForEach(Array(result["choices"].array.enumerated()), id: \.offset) { index, value in
                    Button(value.string) { choose(index) }.disabled(!canAsk || !ready)
                }
                if suite.can("data.devices") {
                    ForEach(result["devices"].array, id: \.["player"].string) { device in
                        NavigationLink {
                            SuiteDataListView(suite: suite, kind: "devices", player: device["player"].string, actions: [], panel: panel, server: server)
                        } label: {
                            Label(device["playerName"].string + " · " + device["name"].string, systemImage: "desktopcomputer.and.iphone")
                        }
                    }
                }
                ForEach(result["players"].array, id: \.["id"].string) { player in
                    NavigationLink {
                        if let panel, let server { SuitePlayerView(suite: suite, panel: panel, server: server, player: player["id"].string, actions: []) }
                        else { SuitePlayerDossierView(suite: suite, player: player["id"].string) }
                    } label: { Label(player["name"].string, systemImage: "person.crop.circle") }
                }
                ForEach(result["rejected"].array, id: \.string) { Text($0.string).foregroundStyle(.orange) }
            }
            if !actions.isEmpty {
                Section("Vorgeschlagene Aktionen") {
                    ForEach(Array(actions.enumerated()), id: \.offset) { index, step in
                        VStack(alignment: .leading, spacing: 5) {
                            if owner && ready {
                                let id = step["id"].string
                                Toggle("\(index + 1). " + step["title"].string, isOn: Binding(get: { selection.contains(id) }, set: { enabled in
                                    if enabled { selection.insert(id) } else { selection.remove(id) }
                                })).disabled(busy)
                            } else { Text("\(index + 1). " + step["title"].string).font(.headline) }
                            Text(step["preview"].string).font(.callout).textSelection(.enabled)
                        }
                    }
                    if ready {
                        Button("Mit KI neu zusammenfassen", systemImage: "sparkles") { summarize() }.disabled(!canAsk)
                        if owner {
                            Button("Alle offenen Aktionen auswählen") { selection = Set(actions.map { $0["id"].string }) }.disabled(busy)
                            Button("Auswahl bestätigen", systemImage: "checkmark.shield") { confirming = true }.disabled(busy || selection.isEmpty)
                        }
                        else { Text("Wartet auf Freigabe durch den Hauptadmin.").font(.caption).foregroundStyle(.secondary) }
                        Button("Plan verwerfen", role: .destructive) { mutate("discard") }.disabled(busy)
                    } else { Text(result["status"].string == "discarded" ? "Plan verworfen." : "Plan verarbeitet.") }
                }
            }
        }
        if !response.isEmpty {
            Section("Ergebnis der Freigabe") {
                ForEach(Array(response.enumerated()), id: \.offset) { _, step in
                    Label(step["title"].string + " · " + step["description"].string, systemImage: step["ok"].bool ? "checkmark.circle" : "exclamationmark.circle")
                }
            }
        }
    }

    private var ownerControls: some View {
        Group {
            Section("Offene Freigaben") {
                if inbox.isEmpty { Text("Keine offenen Pläne.").foregroundStyle(.secondary) }
                ForEach(inbox, id: \.["id"].string) { plan in
                    Button(plan["author"].string + " · \(plan["count"].int) Aktionen", systemImage: "list.clipboard") {
                        showOwner = false
                        openResult(plan["id"].string)
                    }.disabled(busy)
                }
            }
            Section("Tageslimits für Suite-Konten") {
                Text("Standard: \(limits["default"].int) Anfragen. 0 sperrt neue Anfragen.").font(.caption)
                ForEach(limits["accounts"].object.keys.sorted(), id: \.self) { name in
                    Button { account = name; daily = limits["accounts"][name].string } label: { LabeledContent(name, value: limits["accounts"][name].string) }
                }
                TextField("Suite-Benutzername", text: $account).textInputAutocapitalization(.never).autocorrectionDisabled()
                TextField("Anfragen pro Tag", text: $daily).keyboardType(.numberPad)
                Button("Limit speichern") {
                    run { limits = try await suite.request("assistant/limits", method: "POST", body: .object(["account": .string(account), "limit": .string(daily)])) }
                }.disabled(busy || account.isEmpty || Int(daily).map { !(0...500).contains($0) } != false)
            }
        }
    }

    private func refresh() async {
        guard supported else { return }
        do {
            status = try await suite.request("assistant/status", timeout: 10)
            if owner {
                inbox = try await suite.request("assistant/inbox", timeout: 10)["items"].array
                limits = try await suite.request("assistant/limits", timeout: 10)
            }
        } catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }
    private func run(_ operation: @escaping @MainActor () async throws -> Void) {
        guard !busy else { return }
        busy = true; error = nil
        work = Task {
            defer { busy = false }
            do { try await operation() }
            catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
        }
    }
    private func send() {
        let question = input.trimmingCharacters(in: .whitespacesAndNewlines)
        run {
            if threaded && chatID.isEmpty { try await createChat() }
            let job = try await suite.request("assistant/ask", method: "POST", body: .object(["question": .string(question), "chat": .string(chatID)]), timeout: 12)
            if input == question || input.trimmingCharacters(in: .whitespacesAndNewlines) == question { input = "" }
            if threaded { stopDraftAudio(); draft = nil; pending = question }
            defer { pending = "" }
            try await poll(job["id"].string)
        }
    }
    private func choose(_ index: Int) {
        let id = result["id"].string
        run {
            let job = try await suite.request("assistant/choose", method: "POST", body: .object(["id": .string(id), "index": .number(Double(index))]), timeout: 12)
            try await poll(job["id"].string)
        }
    }
    private func poll(_ id: String) async throws {
        response = []; showSummary = false; selection = []; summaryOverride = ""
        for _ in 0..<64 {
            let row = try await suite.request("assistant/job", query: ["id": id], timeout: 12)
            if row["status"].string != "running" {
                result = row
                if threaded && !chatID.isEmpty { try await loadChat(chatID); try await loadChats() }
                await refresh(); return
            }
            try await Task.sleep(for: .seconds(2))
        }
        throw PanelError(message: "Der Server braucht länger. Prüfe später die offenen Freigaben.")
    }
    private func summarize() {
        let id = result["id"].string
        run {
            let job = try await suite.request("assistant/summarize", method: "POST", body: .object(["id": .string(id)]), timeout: 12)
            for _ in 0..<40 {
                let row = try await suite.request("assistant/job", query: ["id": job["id"].string], timeout: 12)
                if row["status"].string != "running" { summaryOverride = row["answer"].string; showSummary = true; await refresh(); return }
                try await Task.sleep(for: .seconds(2))
            }
            throw PanelError(message: "Die Kurzfassung ist noch nicht verfügbar.")
        }
    }
    private func mutate(_ operation: String) {
        let id = result["id"].string
        let revision = result["revision"].string
        let ids = actions.map { $0["id"].string }.filter { selection.contains($0) }
        run {
            let body: JSON = .object(["id": .string(id), "confirmed": .bool(operation == "approve"), "revision": .string(revision), "actions": .array(ids.map(JSON.string))])
            let response = try await suite.request("assistant/" + operation, method: "POST", body: body, timeout: 30)
            self.response = response["results"].array; selection = []; summaryOverride = ""
            result = try await suite.request("assistant/job", query: ["id": id], timeout: 12)
            await refresh()
        }
    }
}
