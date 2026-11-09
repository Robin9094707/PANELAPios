import SwiftUI
import UniformTypeIdentifiers

// MARK: - Minecraft look

/// The grey, bevelled container of a real Minecraft inventory screen.
struct MinecraftPanel<Content: View>: View {
    let title: String
    @ViewBuilder let content: () -> Content
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.system(size: 14, weight: .semibold, design: .monospaced)).foregroundStyle(Color(white: 0.25))
            content()
        }
        .padding(10)
        .background(Color(red: 0.776, green: 0.776, blue: 0.776))
        .overlay(alignment: .topLeading) { MinecraftBevel(light: .white, dark: Color(white: 0.33), width: 3) }
        .clipShape(RoundedRectangle(cornerRadius: 6))
        .shadow(color: .black.opacity(0.25), radius: 8, y: 4)
    }
}

/// Light top-left and dark bottom-right edges, like Minecraft's GUI textures.
struct MinecraftBevel: View {
    let light: Color
    let dark: Color
    var width: CGFloat = 2
    var body: some View {
        GeometryReader { geo in
            Path { p in p.move(to: CGPoint(x: 0, y: geo.size.height)); p.addLine(to: .zero); p.addLine(to: CGPoint(x: geo.size.width, y: 0)) }
                .stroke(light, lineWidth: width)
            Path { p in p.move(to: CGPoint(x: geo.size.width, y: 0)); p.addLine(to: CGPoint(x: geo.size.width, y: geo.size.height)); p.addLine(to: CGPoint(x: 0, y: geo.size.height)) }
                .stroke(dark, lineWidth: width)
        }.allowsHitTesting(false)
    }
}

struct MinecraftSlot: View {
    let item: JSON?
    let icon: UIImage?
    var selected = false
    var placeholder: String? = nil
    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            Rectangle().fill(Color(red: 0.545, green: 0.545, blue: 0.545))
            MinecraftBevel(light: .white, dark: Color(white: 0.22), width: 2).rotationEffect(.degrees(180))
            if let item {
                Group {
                    if let icon { Image(uiImage: icon).resizable().interpolation(.none).scaledToFit() }
                    else { Text(item["name"].string.prefix(2).uppercased()).font(.system(size: 10, weight: .heavy, design: .monospaced)).foregroundStyle(.white) }
                }.padding(4).frame(maxWidth: .infinity, maxHeight: .infinity)
                if item["amount"].int > 1 {
                    Text(item["amount"].string).font(.system(size: 11, weight: .heavy, design: .monospaced)).foregroundStyle(.white)
                        .shadow(color: Color(white: 0.24), radius: 0, x: 1, y: 1).padding(.trailing, 2).padding(.bottom, 1)
                }
            } else if let placeholder {
                Image(systemName: placeholder).font(.system(size: 12, weight: .bold)).foregroundStyle(Color(white: 0.42)).frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            if selected { Rectangle().fill(Color.white.opacity(0.35)); Rectangle().strokeBorder(Color.cyan, lineWidth: 2) }
        }
        .aspectRatio(1, contentMode: .fit)
    }
}

/// Loads item textures from the suite (vanilla or the server's resource pack), cached per view.
enum SuiteItemIcons {
    static func load(_ suite: SuiteAPI, materials: Set<String>) async -> (icons: [String: UIImage], custom: Bool, status: String) {
        var result: [String: UIImage] = [:], custom = false, status = ""
        let list = materials.filter { !$0.isEmpty }.sorted()
        for start in stride(from: 0, to: list.count, by: 60) {
            let chunk = list[start..<min(list.count, start + 60)]
            guard let data = try? await suite.request("invsee/icons", query: ["items": chunk.joined(separator: ",")], timeout: 10) else { continue }
            for (key, value) in data["icons"].object { if let bytes = Data(base64Encoded: value.string), let image = UIImage(data: bytes) { result[key] = image } }
            custom = data["custom"].bool; status = data["status"].string
        }
        return (result, custom, status)
    }
}

// MARK: - Live inventory

struct SuiteVisualInventoryView: View {
    let suite: SuiteAPI
    let player: String
    var offline = false
    @State private var items: [Int: JSON] = [:]
    @State private var icons: [String: UIImage] = [:]
    @State private var requestedIcons: Set<String> = []
    @State private var revision = ""
    @State private var textures: String?
    @State private var name = ""
    @State private var status = "Inventar wird geladen …"
    @State private var packStatus = ""
    @State private var canEdit = false
    @State private var canTransfer = false
    @State private var canPack = false
    @State private var customPack = false
    @State private var selected: Int?
    @State private var swapping = false
    @State private var transfer: SuiteItemTransfer?
    @State private var busy = false
    @State private var refreshing = false
    @State private var reset = false
    @State private var importing = false
    @State private var error: String?
    private let nine = Array(repeating: GridItem(.flexible(), spacing: 2), count: 9)

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Label(status, systemImage: swapping ? "arrow.left.arrow.right" : "square.grid.3x3").font(.callout).foregroundStyle(.secondary)
                MinecraftPanel(title: name.isEmpty ? "Inventar" : "Inventar von " + name) {
                    HStack(alignment: .top, spacing: 10) {
                        VStack(spacing: 2) { ForEach([39, 38, 37, 36], id: \.self) { slot($0, placeholder: ["shield.lefthalf.filled", "tshirt", "figure.walk", "shoe"][39 - $0]) } }.frame(width: 44)
                        VStack(spacing: 6) {
                            Image(systemName: "person.fill").font(.system(size: 54)).foregroundStyle(Color(white: 0.35)).frame(maxWidth: .infinity, minHeight: 120)
                                .background(Color.black.opacity(0.85)).overlay { MinecraftBevel(light: Color(white: 0.33), dark: .white) }
                            HStack { Spacer(); slot(40, placeholder: "shield").frame(width: 44) }
                        }
                    }
                    LazyVGrid(columns: nine, spacing: 2) { ForEach(9..<36, id: \.self) { slot($0) } }.padding(.top, 6)
                    LazyVGrid(columns: nine, spacing: 2) { ForEach(0..<9, id: \.self) { slot($0) } }.padding(.top, 8)
                }
                MinecraftPanel(title: "Endertruhe") {
                    LazyVGrid(columns: nine, spacing: 2) { ForEach(41..<68, id: \.self) { slot($0) } }
                }
                if canTransfer && suite.features.contains("item-locker-v1") {
                    NavigationLink { SuiteItemLockerView(suite: suite) } label: {
                        Label("Einbehaltungsablage öffnen", systemImage: "archivebox.fill").frame(maxWidth: .infinity, alignment: .leading).card()
                    }.buttonStyle(.plain)
                }
                if !packStatus.isEmpty || canPack {
                    VStack(alignment: .leading, spacing: 8) {
                        Label("Ressourcenpaket", systemImage: "paintbrush.pointed.fill").font(.headline)
                        Text(packStatus).font(.footnote).foregroundStyle(.secondary)
                        if canPack {
                            HStack {
                                Button("Java-ZIP importieren") { importing = true }.disabled(busy)
                                if customPack { Button("Vanilla verwenden", role: .destructive) { reset = true }.disabled(busy) }
                            }.buttonStyle(.bordered)
                        }
                    }.frame(maxWidth: .infinity, alignment: .leading).card()
                }
            }.padding(16).frame(maxWidth: 620).frame(maxWidth: .infinity)
        }
        .safeAreaInset(edge: .bottom) { if let selected, let item = items[selected] { actionBar(selected, item) } }
        .panelScreen()
        .navigationTitle(offline ? "Offline-Inventar" : "Live-Inventar")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            while !Task.isCancelled {
                await load()
                if offline { return }
                do { try await Task.sleep(for: .seconds(2)) } catch { return }
            }
        }
        .refreshable { await load() }
        .sheet(item: $transfer, onDismiss: { selected = nil; Task { await load() } }) { request in
            SuiteItemTransferSheet(suite: suite, request: request, icon: icons[request.item["material"].string])
        }
        .confirmationDialog("Eigenes Paket entfernen?", isPresented: $reset, titleVisibility: .visible) {
            Button("Entfernen", role: .destructive) { Task { await changePack(nil) } }
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.item]) { result in
            Task {
                do { await changePack(try result.get()) }
                catch { self.error = error.localizedDescription }
            }
        }
        .errorAlert($error)
    }

    private func slot(_ index: Int, placeholder: String? = nil) -> some View {
        let item = items[index]
        let label = item.map { $0["name"].string + " × " + $0["amount"].string } ?? "Leer"
        return Button { tap(index) } label: {
            MinecraftSlot(item: item, icon: item.flatMap { icons[$0["material"].string] }, selected: selected == index, placeholder: placeholder)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Slot \(index) · " + label)
        .contextMenu { if item != nil { Button("Name kopieren", systemImage: "doc.on.doc") { Clipboard.copy(label) } } }
    }

    private func tap(_ index: Int) {
        guard !busy else { return }
        if swapping, let from = selected {
            swapping = false
            guard from != index, from < 36 || from > 40, index < 36 || index > 40 else { status = "Ausrüstung kann nicht getauscht werden."; selected = nil; return }
            Task { await swap(from, index) }
            return
        }
        if selected == index { selected = nil; return }
        if items[index] != nil { withAnimation(.snappy) { selected = index }; status = "Slot \(index) · " + (items[index]?["name"].string ?? "") }
        else { selected = nil }
    }

    private func actionBar(_ index: Int, _ item: JSON) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 12) {
                MinecraftSlot(item: item, icon: icons[item["material"].string]).frame(width: 44)
                VStack(alignment: .leading, spacing: 2) {
                    Text(item["name"].string).font(.headline).lineLimit(1)
                    Text("\(item["amount"].int)× · Slot \(index)").font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                Button { selected = nil; swapping = false } label: { Image(systemName: "xmark.circle.fill").font(.title2).foregroundStyle(.secondary) }.buttonStyle(.plain)
            }
            if swapping { Label("Zielslot antippen …", systemImage: "hand.tap").font(.subheadline).foregroundStyle(Color.panelAccent) }
            else {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        if canEdit && (index < 36 || index > 40) { Button("Tauschen", systemImage: "arrow.left.arrow.right") { swapping = true } }
                        if canTransfer && !offline {
                            Button("An Spieler geben", systemImage: "person.fill.badge.plus") { transfer = SuiteItemTransfer(player: player, playerName: name, revision: revision, slot: index, item: item, destination: "player") }
                            Button("In Ablage", systemImage: "archivebox") { transfer = SuiteItemTransfer(player: player, playerName: name, revision: revision, slot: index, item: item, destination: "locker") }
                        }
                    }.buttonStyle(.bordered)
                }
                if !canEdit && !canTransfer { Text("Nur Ansicht").font(.caption).foregroundStyle(.secondary) }
            }
        }
        .padding(14).background(.regularMaterial, in: RoundedRectangle(cornerRadius: 20)).padding(.horizontal, 12).padding(.bottom, 6)
        .transition(.move(edge: .bottom).combined(with: .opacity))
    }

    private func load() async {
        guard !busy, !refreshing, transfer == nil else { return }
        refreshing = true
        defer { refreshing = false }
        do {
            let value = try await suite.request("invsee", query: ["player": player, "revision": revision, "offline": offline ? "true" : "false"], timeout: 8)
            guard !Task.isCancelled, !busy else { return }
            canEdit = value["editable"].bool
            canTransfer = value["transfer"].bool
            canPack = value["pack"].bool
            if !value["name"].string.isEmpty { name = value["name"].string }
            if !value["unchanged"].bool {
                if selected != nil { status = "Inventar wurde geändert. Bitte neu auswählen." }
                else { status = canEdit || canTransfer ? "Item antippen, um es zu tauschen, weiterzugeben oder einzubehalten." : "Nur Ansicht" }
                selected = nil; swapping = false
                items = Dictionary(uniqueKeysWithValues: value["slots"].array.map { ($0["slot"].int, $0) })
                revision = value["revision"].string
            }
            let materials = Set(items.values.map { $0["material"].string })
            if textures != value["textures"].string || !materials.isSubset(of: requestedIcons) {
                let loaded = await SuiteItemIcons.load(suite, materials: materials)
                guard !Task.isCancelled else { return }
                icons = loaded.icons; requestedIcons = materials; textures = value["textures"].string
                customPack = loaded.custom
                packStatus = (customPack ? "Eigenes ZIP + " : "") + loaded.status
            }
        } catch {
            if !Task.isCancelled && !RequestFailure.isCancellation(error) {
                status = error.localizedDescription
                items = [:]; revision = ""; selected = nil; canEdit = false; canTransfer = false
            }
        }
    }

    private func swap(_ from: Int, _ to: Int) async {
        busy = true
        do {
            _ = try await suite.post("invsee", ["player": .string(player), "revision": .string(revision),
                "offline": .bool(offline), "from": .number(Double(from)), "to": .number(Double(to)), "confirmed": .bool(true)])
            Feedback.success()
        } catch { self.error = error.localizedDescription }
        selected = nil; busy = false
        await load()
    }

    private func changePack(_ url: URL?) async {
        guard !busy else { return }
        busy = true
        do {
            if let url {
                let accessing = url.startAccessingSecurityScopedResource()
                defer { if accessing { url.stopAccessingSecurityScopedResource() } }
                let limit = 32 * 1024 * 1024
                if let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize, size > limit {
                    throw PanelError(message: "Das ZIP darf maximal 32 MiB groß sein.")
                }
                let file = try FileHandle(forReadingFrom: url)
                defer { try? file.close() }
                let bytes = try file.read(upToCount: limit + 1) ?? Data()
                guard bytes.count <= limit else { throw PanelError(message: "Das ZIP darf maximal 32 MiB groß sein.") }
                _ = try await suite.upload("invsee/pack", query: [:], data: bytes, contentType: "application/zip", timeout: 120)
            } else {
                _ = try await suite.request("invsee/pack", method: "POST", query: ["mode": "reset"], body: .object(["confirmed": .bool(true)]))
            }
            textures = nil
        } catch { self.error = error.localizedDescription }
        busy = false
        await load()
    }
}

// MARK: - Transfer

struct SuiteItemTransfer: Identifiable {
    let id = UUID()
    let player: String
    let playerName: String
    let revision: String
    let slot: Int
    let item: JSON
    let destination: String
}

/// Give a stack (or part of it) to another online player or put it into the Einbehaltungsablage.
struct SuiteItemTransferSheet: View {
    let suite: SuiteAPI
    let request: SuiteItemTransfer
    let icon: UIImage?
    @Environment(\.dismiss) private var dismiss
    @State private var amount = 1
    @State private var reason = ""
    @State private var target: JSON?
    @State private var search = ""
    @State private var players: [JSON] = []
    @State private var busy = false
    @State private var error: String?
    var locker: Bool { request.destination == "locker" }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    HStack(spacing: 12) {
                        MinecraftSlot(item: request.item, icon: icon).frame(width: 52)
                        VStack(alignment: .leading, spacing: 3) {
                            Text(request.item["name"].string).font(.headline)
                            Text("Von " + (request.playerName.isEmpty ? "Spieler" : request.playerName) + " · Slot \(request.slot)").font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    if request.item["amount"].int > 1 {
                        Stepper("Menge: \(amount) von \(request.item["amount"].int)", value: $amount, in: 1...max(1, request.item["amount"].int))
                    }
                }
                if !locker {
                    Section("Zielspieler (online)") {
                        if let target {
                            HStack {
                                PowerBotPlayerLabel(id: target["id"].string, name: target["name"].string)
                                Spacer()
                                Button("Ändern") { self.target = nil }.font(.caption)
                            }
                        } else {
                            TextField("Spieler suchen", text: $search).textInputAutocapitalization(.never).autocorrectionDisabled()
                            ForEach(players.filter { $0["id"].string != request.player }.prefix(12), id: \.["id"].string) { row in
                                Button { target = row } label: { PowerBotPlayerLabel(id: row["id"].string, name: row["name"].string) }.buttonStyle(.plain)
                            }
                            if players.isEmpty { Text("Keine passenden Spieler online.").font(.caption).foregroundStyle(.secondary) }
                        }
                    }
                }
                Section {
                    TextField(locker ? "Grund, z. B. gestohlen von Spieler B" : "Grund, z. B. Rückgabe nach Diebstahl", text: $reason, axis: .vertical).lineLimit(1...3)
                } header: { Text("Grund") } footer: {
                    Text(locker ? "Das Item wird sofort aus dem Inventar entfernt und sicher in der Einbehaltungsablage verwahrt – mit Herkunft, Grund und deinem Namen."
                         : "Das Item wird sofort verschoben. Passt nicht alles, bleibt der Rest beim ursprünglichen Spieler. Beide Verläufe zeigen die Aktion.")
                }
            }
            .panelScreen()
            .navigationTitle(locker ? "In Ablage legen" : "An Spieler geben")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(locker ? "Einlagern" : "Geben") { Task { await submit() } }.disabled(busy || !locker && target == nil)
                }
            }
            .onAppear { amount = max(1, request.item["amount"].int) }
            .task(id: search) {
                guard !locker else { return }
                if !search.isEmpty { do { try await Task.sleep(for: .milliseconds(250)) } catch { return } }
                players = ((try? await suite.data("players", query: ["filter": "online", "limit": "100", "q": search]))?["items"].array ?? []).filter { $0["online"].bool }
            }
            .errorAlert($error)
        }
    }

    private func submit() async {
        busy = true; defer { busy = false }
        do {
            var body: [String: JSON] = ["player": .string(request.player), "revision": .string(request.revision), "slot": .number(Double(request.slot)),
                                        "amount": .number(Double(amount)), "destination": .string(request.destination), "reason": .string(reason), "confirmed": .bool(true)]
            if let target { body["target"] = target["id"] }
            let reply = try await suite.post("invsee/transfer", body)
            Feedback.success()
            _ = reply
            dismiss()
        } catch { self.error = error.localizedDescription }
    }
}

// MARK: - Einbehaltungsablage

struct SuiteItemLockerView: View {
    let suite: SuiteAPI
    @State private var data: JSON = .null
    @State private var icons: [String: UIImage] = [:]
    @State private var selected: JSON?
    @State private var error: String?
    private let nine = Array(repeating: GridItem(.flexible(), spacing: 2), count: 9)

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("Einbehaltene Items mit Herkunft, Grund und Teammitglied. Antippen, um sie zurückzugeben, weiterzugeben oder zu löschen. Im Spiel: /sas ablage.")
                    .font(.callout).foregroundStyle(.secondary)
                MinecraftPanel(title: "Einbehaltungsablage · \(data["count"].int)") {
                    let held = data["items"].array
                    LazyVGrid(columns: nine, spacing: 2) {
                        ForEach(0..<max(27, ((held.count + 8) / 9) * 9), id: \.self) { index in
                            if index < held.count {
                                let row = held[index]
                                Button { selected = row } label: { MinecraftSlot(item: row, icon: icons[row["material"].string]) }
                                    .buttonStyle(.plain).accessibilityLabel(row["name"].string + " × " + row["amount"].string + " von " + row["fromName"].string)
                            } else { MinecraftSlot(item: nil, icon: nil) }
                        }
                    }
                }
                if !data["history"].array.isEmpty {
                    VStack(alignment: .leading, spacing: 10) {
                        Label("Verlauf", systemImage: "clock.arrow.circlepath").font(.headline)
                        ForEach(data["history"].array.prefix(30), id: \.["id"].string) { row in
                            HStack(spacing: 10) {
                                MinecraftSlot(item: row, icon: icons[row["material"].string]).frame(width: 34)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(row["amount"].string + "× " + row["name"].string).font(.subheadline.weight(.semibold))
                                    Text(SuiteItemLockerView.statusText(row)).font(.caption).foregroundStyle(.secondary)
                                }
                                Spacer(minLength: 0)
                                if let date = SuitePlayerPresentation.date(row["resolvedAt"]) { Text(SuiteRelativeTime.text(date)).font(.caption2).foregroundStyle(.secondary) }
                            }
                        }
                    }.frame(maxWidth: .infinity, alignment: .leading).card()
                }
            }.padding(16).frame(maxWidth: 620).frame(maxWidth: .infinity)
        }
        .panelScreen()
        .navigationTitle("Einbehaltungsablage")
        .task { await load() }
        .refreshable { await load() }
        .sheet(item: Binding(get: { selected.map { SuiteLockerSelection(row: $0) } }, set: { selected = $0?.row })) { selection in
            SuiteLockerItemSheet(suite: suite, row: selection.row, icon: icons[selection.row["material"].string], manage: data["manage"].bool) { await load() }
        }
        .errorAlert($error)
    }

    static func statusText(_ row: JSON) -> String {
        switch row["status"].string {
        case "returned": return "An " + row["toName"].string + " zurückgegeben · " + row["resolvedBy"].string
        case "given": return "An " + row["toName"].string + " gegeben · " + row["resolvedBy"].string
        case "deleted": return "Gelöscht · " + row["resolvedBy"].string
        default: return "Von " + row["fromName"].string
        }
    }

    private func load() async {
        do {
            let next = try await suite.request("locker")
            data = next
            let materials = Set((next["items"].array + next["history"].array).map { $0["material"].string })
            if !materials.isSubset(of: Set(icons.keys)) { icons = await SuiteItemIcons.load(suite, materials: materials).icons }
        } catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }
}

struct SuiteLockerSelection: Identifiable { let row: JSON; var id: String { row["id"].string } }

struct SuiteLockerItemSheet: View {
    let suite: SuiteAPI
    let row: JSON
    let icon: UIImage?
    let manage: Bool
    let changed: () async -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var choosing = false
    @State private var search = ""
    @State private var players: [JSON] = []
    @State private var note = ""
    @State private var deleting = false
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    HStack(spacing: 14) {
                        MinecraftSlot(item: row, icon: icon).frame(width: 64)
                        VStack(alignment: .leading, spacing: 3) {
                            Text(row["name"].string).font(.title3.bold())
                            Text("\(row["amount"].int)× · \(row["material"].string)").font(.caption.monospaced()).foregroundStyle(.secondary)
                        }
                    }
                    LabeledContent("Herkunft", value: row["fromName"].string)
                    if !row["reason"].string.isEmpty { LabeledContent("Grund", value: row["reason"].string) }
                    LabeledContent("Eingelagert von", value: row["by"].string)
                    if let date = SuitePlayerPresentation.date(row["at"]) { LabeledContent("Zeitpunkt", value: SuiteRelativeTime.absolute(date) + " · " + SuiteRelativeTime.text(date)) }
                }
                if manage {
                    Section("Aktionen") {
                        if UUID(uuidString: row["fromId"].string) != nil {
                            Button("An " + row["fromName"].string + " zurückgeben", systemImage: "arrow.uturn.backward.circle") { Task { await give(row["fromId"].string) } }
                        }
                        Button("An anderen Spieler geben …", systemImage: "person.fill.badge.plus") { choosing = true }
                        Button("Endgültig löschen", systemImage: "trash") { deleting = true }.foregroundStyle(.red)
                    }
                    if choosing {
                        Section("Spieler wählen (online)") {
                            TextField("Spieler suchen", text: $search).textInputAutocapitalization(.never).autocorrectionDisabled()
                            ForEach(players.prefix(12), id: \.["id"].string) { player in
                                Button { Task { await give(player["id"].string) } } label: { PowerBotPlayerLabel(id: player["id"].string, name: player["name"].string) }.buttonStyle(.plain)
                            }
                        }
                    }
                    Section("Teamnotiz") {
                        TextField("Notiz", text: $note, axis: .vertical)
                        Button("Notiz speichern") { Task { await act(["operation": .string("note"), "note": .string(note)]) } }.disabled(note == row["note"].string)
                    }
                }
            }
            .panelScreen()
            .navigationTitle("Item in der Ablage")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Fertig") { dismiss() } } }
            .disabled(busy)
            .onAppear { note = row["note"].string }
            .task(id: search + String(choosing)) {
                guard choosing else { return }
                if !search.isEmpty { do { try await Task.sleep(for: .milliseconds(250)) } catch { return } }
                players = ((try? await suite.data("players", query: ["filter": "online", "limit": "100", "q": search]))?["items"].array ?? []).filter { $0["online"].bool }
            }
            .confirmationDialog("Item endgültig löschen?", isPresented: $deleting, titleVisibility: .visible) {
                Button("Löschen", role: .destructive) { Task { await act(["operation": .string("delete")], close: true) } }
                Button("Abbrechen", role: .cancel) {}
            } message: { Text("Der Eintrag bleibt 30 Tage im Verlauf sichtbar.") }
            .errorAlert($error)
        }
    }

    private func give(_ player: String) async { await act(["operation": .string("give"), "target": .string(player)], close: true) }

    private func act(_ body: [String: JSON], close: Bool = false) async {
        busy = true; defer { busy = false }
        do {
            _ = try await suite.post("locker", body.merging(["id": row["id"], "confirmed": .bool(true)]) { current, _ in current })
            Feedback.success()
            await changed()
            if close { dismiss() }
        } catch { self.error = error.localizedDescription }
    }
}
