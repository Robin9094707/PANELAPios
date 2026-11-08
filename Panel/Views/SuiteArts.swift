import SwiftUI
import UniformTypeIdentifiers

struct SuiteVisualInventoryView: View {
    let suite: SuiteAPI
    let player: String
    @State private var items: [Int: JSON] = [:]
    @State private var icons: [String: UIImage] = [:]
    @State private var requestedIcons: Set<String> = []
    @State private var revision = ""
    @State private var textures: String?
    @State private var status = "Inventar wird geladen …"
    @State private var packStatus = ""
    @State private var canEdit = false
    @State private var canPack = false
    @State private var customPack = false
    @State private var from: Int?
    @State private var to: Int?
    @State private var busy = false
    @State private var refreshing = false
    @State private var confirm = false
    @State private var reset = false
    @State private var importing = false
    @State private var error: String?
    private let columns = Array(repeating: GridItem(.flexible(), spacing: 3), count: 9)

    var body: some View {
        List {
            Section { Label(status, systemImage: "square.grid.3x3").font(.callout) }
            grid("Ausrüstung & Nebenhand", 36..<41)
            grid("Inventar", 9..<36)
            grid("Hotbar", 0..<9)
            grid("Endertruhe", 41..<68)
            if canEdit {
                Section {
                    Button("Anwenden") { confirm = true }.disabled(from == nil || to == nil || busy)
                    if from != nil { Button("Auswahl zurücksetzen") { from = nil; to = nil } }
                }
            }
            if !packStatus.isEmpty || canPack {
                Section("Ressourcenpaket") {
                    Text(packStatus).font(.footnote)
                    if canPack {
                        Button("Java-ZIP importieren") { importing = true }.disabled(busy)
                        if customPack { Button("Vanilla verwenden", role: .destructive) { reset = true }.disabled(busy) }
                    }
                }
            }
        }
        .panelScreen()
        .navigationTitle("Live-Inventar")
        .task {
            while !Task.isCancelled {
                await load()
                do { try await Task.sleep(for: .seconds(2)) } catch { return }
            }
        }
        .refreshable { await load() }
        .confirmationDialog("Inventar ändern?", isPresented: $confirm, titleVisibility: .visible) {
            Button("Slots tauschen") { Task { await apply() } }
        } message: { Text("Die Änderung wird sofort auf dem Minecraft-Server angewendet.") }
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

    private func grid(_ title: String, _ range: Range<Int>) -> some View {
        Section(title) {
            LazyVGrid(columns: columns, spacing: 3) {
                ForEach(range, id: \.self) { slot in
                    let item = items[slot]
                    let name = item.map { $0["name"].string + " × " + $0["amount"].string } ?? "Leer"
                    Button {
                        status = "Slot \(slot) · " + name
                        guard canEdit, !busy, slot < 36 || slot > 40 else { return }
                        if from == slot { from = nil; to = nil }
                        else if from != nil { to = slot }
                        else if item != nil { from = slot }
                    } label: {
                        ZStack(alignment: .bottomTrailing) {
                            RoundedRectangle(cornerRadius: 5).fill(.secondary.opacity(0.17)).aspectRatio(1, contentMode: .fit)
                            if let item {
                                Group {
                                    if let image = icons[item["material"].string] {
                                        Image(uiImage: image).resizable().interpolation(.none).scaledToFit()
                                    } else { Text(item["name"].string.prefix(2).uppercased()).font(.caption2.bold()) }
                                }.frame(maxWidth: .infinity, maxHeight: .infinity).padding(3)
                                if item["amount"].int > 1 {
                                    Text(item["amount"].string).font(.system(size: 9, weight: .heavy, design: .monospaced))
                                        .padding(.horizontal, 2).background(.regularMaterial, in: RoundedRectangle(cornerRadius: 2))
                                }
                            }
                        }
                        .overlay { RoundedRectangle(cornerRadius: 5).strokeBorder(from == slot || to == slot ? Color.cyan : Color.secondary.opacity(0.3), lineWidth: from == slot || to == slot ? 2 : 1) }
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Slot \(slot) · " + name)
                }
            }
        }
    }

    private func load() async {
        guard !busy, !refreshing else { return }
        refreshing = true
        defer { refreshing = false }
        do {
            let value = try await suite.request("invsee", query: ["player": player, "revision": revision], timeout: 8)
            guard !Task.isCancelled, !busy else { return }
            canEdit = value["editable"].bool
            canPack = value["pack"].bool
            if !canEdit { from = nil; to = nil }
            if !value["unchanged"].bool {
                status = from != nil ? "Inventar wurde geändert. Bitte neu auswählen." : (canEdit ? "Item und Zielslot auswählen." : "Nur Ansicht")
                from = nil; to = nil
                items = Dictionary(uniqueKeysWithValues: value["slots"].array.map { ($0["slot"].int, $0) })
                revision = value["revision"].string
            }
            let materials = Set(items.values.map { $0["material"].string })
            if textures != value["textures"].string || !materials.isSubset(of: requestedIcons) {
                let imageData = try await suite.request("invsee/icons", query: ["items": materials.sorted().joined(separator: ",")], timeout: 10)
                guard !Task.isCancelled else { return }
                icons = imageData["icons"].object.compactMapValues { value in
                    guard let bytes = Data(base64Encoded: value.string) else { return nil }
                    return UIImage(data: bytes)
                }
                requestedIcons = materials
                textures = value["textures"].string
                customPack = imageData["custom"].bool
                packStatus = (customPack ? "Eigenes ZIP + " : "") + imageData["status"].string
            }
        } catch {
            if !Task.isCancelled && !RequestFailure.isCancellation(error) {
                status = error.localizedDescription
                items = [:]; revision = ""; from = nil; to = nil; canEdit = false
            }
        }
    }

    private func apply() async {
        guard let from, let to, !busy else { return }
        busy = true
        do {
            _ = try await suite.post("invsee", ["player": .string(player), "revision": .string(revision),
                "from": .number(Double(from)), "to": .number(Double(to)), "confirmed": .bool(true)])
        } catch { self.error = error.localizedDescription }
        self.from = nil; self.to = nil; busy = false
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

struct SuiteServerEventsView: View {
    let suite: SuiteAPI
    @State private var data: JSON = .null
    @State private var eventName = ""
    @State private var mode = "off"
    @State private var world = ""
    @State private var name = ""
    @State private var day = ""
    @State private var commands = ""
    @State private var chosenCommand = ""
    @State private var activate = false
    @State private var editing = ""
    @State private var pending: [String: JSON]?
    @State private var busy = false
    @State private var error: String?
    private let modes = [("off", "Aus"), ("known", "Nur bekannte Spieler"), ("one-connection", "Eine Verbindung je IP/Alt-Signal"), ("closed", "Nur mit Ausnahme")]

    var body: some View {
        Form {
            if data == .null { ProgressView() }
            else {
                statusSection
                schedulesSection
                if data["manage"].bool && !data["worlds"].array.isEmpty { formSection }
            }
        }
        .panelScreen()
        .navigationTitle("Event-Modus")
        .task { await load() }
        .refreshable { await load() }
        .confirmationDialog("Änderung bestätigen?", isPresented: Binding(get: { pending != nil }, set: { if !$0 { pending = nil } }), titleVisibility: .visible) {
            if let pending { Button("Anwenden") { Task { await apply(pending) } } }
        } message: { Text("Die Änderung gilt für diesen Minecraft-Server.") }
        .errorAlert($error)
    }

    private var statusSection: some View {
        Section("Serverstatus & Lockdown") {
            LabeledContent("Aktives Event", value: data["active"].string.isEmpty ? "Keines" : data["active"].string)
            LabeledContent("Lockdown", value: modes.first(where: { $0.0 == data["lockdown"].string })?.1 ?? "–")
            if data["manage"].bool {
                TextField("Event-Name", text: $eventName)
                Button("Event anzeigen") { pending = ["operation": .string("status"), "name": .string(eventName)] }
                if !data["active"].string.isEmpty {
                    Button("Event beenden", role: .destructive) { pending = ["operation": .string("status"), "name": .string("")] }
                }
                Picker("Beitritte", selection: $mode) { ForEach(modes.indices, id: \.self) { Text(modes[$0].1).tag(modes[$0].0) } }
                Button("Lockdown-Stufe anwenden") { pending = ["operation": .string("lockdown"), "mode": .string(mode)] }
                if data["lockdown"].string != "off" {
                    Button("Lockdown deaktivieren") { pending = ["operation": .string("lockdown"), "mode": .string("off")] }
                }
            }
        }.disabled(busy)
    }

    private var schedulesSection: some View {
        Section("Geplante Spieltage") {
            if data["items"].array.isEmpty { Text("Keine Termine geplant.").foregroundStyle(.secondary) }
            ForEach(data["items"].array, id: \.["id"].string) { item in
                VStack(alignment: .leading, spacing: 5) {
                    Text(item["name"].string).font(.headline)
                    Text(item["world"].string + " · Tag " + item["day"].string + (item["activate"].bool ? " · Event starten" : " · Alarm"))
                        .font(.caption).foregroundStyle(.secondary)
                    ForEach(Array(item["commands"].array.enumerated()), id: \.offset) { pair in
                        Text(pair.element.string).font(.caption.monospaced()).textSelection(.enabled)
                    }
                    if data["manage"].bool {
                        HStack {
                            Button("Bearbeiten") { edit(item) }.buttonStyle(.borderless)
                            Button("Löschen", role: .destructive) { pending = ["operation": .string("delete"), "id": item["id"]] }.buttonStyle(.borderless)
                        }
                    }
                }
            }
        }.disabled(busy)
    }

    private var formSection: some View {
        Section(editing.isEmpty ? "Spieltag planen" : "Termin bearbeiten") {
            Picker("Welt", selection: $world) {
                ForEach(data["worlds"].array, id: \.["name"].string) { entry in
                    Text(entry["name"].string + " · Tag " + entry["day"].string).tag(entry["name"].string)
                }
            }
            TextField("Ingame-Tag", text: $day).keyboardType(.numberPad)
            TextField("Titel", text: $name)
            Toggle("Event im Serverstatus starten", isOn: $activate)
            if data["commands"].bool {
                TextField("Befehle, einer pro Zeile", text: $commands, axis: .vertical)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                let suggestions = data["known"].array.map(\.string).filter { !$0.isEmpty }
                if !suggestions.isEmpty {
                    NavigationLink("Erkannte Befehle") {
                        List(suggestions, id: \.self) { command in
                            Button(command) { chosenCommand = command }.foregroundStyle(chosenCommand == command ? Color.accentColor : Color.primary)
                        }.navigationTitle("Befehle")
                    }
                    if !chosenCommand.isEmpty {
                        Button("Hinzufügen: " + chosenCommand) {
                            commands += (commands.isEmpty ? "" : "\n") + chosenCommand
                            chosenCommand = ""
                        }
                    }
                }
            }
            Button("Termin speichern") {
                guard let targetDay = Int(day) else { return }
                let lines = commands.split(separator: "\n").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
                pending = ["operation": .string("schedule"), "id": .string(editing), "world": .string(world),
                    "day": .number(Double(targetDay)), "name": .string(name), "activate": .bool(activate), "commands": .array(lines.map { .string($0) })]
            }.disabled(Int(day) == nil || name.trimmingCharacters(in: .whitespaces).isEmpty || world.isEmpty)
            if !editing.isEmpty { Button("Abbrechen") { edit(.null) } }
        }.disabled(busy)
    }

    private func edit(_ item: JSON) {
        editing = item["id"].string
        world = item == .null ? data["worlds"].array.first?["name"].string ?? "" : item["world"].string
        day = item["day"].string
        name = item["name"].string
        activate = item["activate"].bool
        commands = item["commands"].array.map(\.string).joined(separator: "\n")
    }

    private func load() async {
        do {
            data = try await suite.request("server-events")
            mode = data["lockdown"].string
            eventName = data["active"].string
            if world.isEmpty { edit(.null) }
        } catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }

    private func apply(_ change: [String: JSON]) async {
        guard !busy else { return }
        busy = true
        defer { busy = false; pending = nil }
        do {
            var body = change
            body["confirmed"] = .bool(true)
            _ = try await suite.post("server-events", body)
            edit(.null)
            await load()
        } catch { self.error = error.localizedDescription }
    }
}
