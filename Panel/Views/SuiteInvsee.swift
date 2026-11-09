import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// One inventory shown in the invsee: a live player or a saved player file.
struct SuiteInvSide: Equatable {
    var player: String
    var name = ""
    var offline = false
    var revision = ""
    var items: [Int: JSON] = [:]
    var editable = false
    var transfer = false
    var outdated = false
    var loaded = false
    var error = ""
}

struct SuiteInvPick: Equatable {
    let side: Int
    let slot: Int
}

/// 5.8.0: invsee like in game – any player, online or offline, a second player side by side, move, split,
/// swap (also armor and offhand), Einbehaltungsablage and item details. Refreshes itself every few seconds.
struct SuiteVisualInventoryView: View {
    let suite: SuiteAPI
    let player: String
    var offline = false
    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var sides: [SuiteInvSide] = []
    @State private var icons: [String: UIImage] = [:]
    @State private var requested: Set<String> = []
    @State private var textures = ""
    @State private var packStatus = ""
    @State private var customPack = false
    @State private var canPack = false
    @State private var pick: SuiteInvPick?
    @State private var amount = 0
    @State private var busy = false
    @State private var choosing = false
    @State private var detail: JSON?
    @State private var lockerReason: String?
    @State private var importing = false
    @State private var reset = false
    @State private var notice = ""
    @State private var error: String?

    private var anyOnline: Bool { sides.contains { !$0.offline } }
    /** Admin Suite 10.15.0 and newer: offline players, moves between players and armor slots. */
    private var v2: Bool { suite.features.contains("invsee-move-v1") }
    private var picked: JSON? { pick.flatMap { sides.indices.contains($0.side) ? sides[$0.side].items[$0.slot] : nil } }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                header
                panels
                extras
            }.padding(16).frame(maxWidth: sides.count > 1 && sizeClass == .regular ? 1200 : 620).frame(maxWidth: .infinity)
        }
        .safeAreaInset(edge: .bottom) { if let pick, let item = picked { actionBar(pick, item) } }
        .panelScreen()
        .navigationTitle("Invsee")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if v2 { ToolbarItem(placement: .topBarTrailing) {
                Button(sides.count > 1 ? "Zweiten Spieler schließen" : "Zweiten Spieler öffnen", systemImage: sides.count > 1 ? "person.crop.circle.badge.xmark" : "person.2.fill") {
                    if sides.count > 1 { sides.removeLast(); pick = nil } else { choosing = true }
                }
            } }
        }
        .task { await loop() }
        .refreshable { await load() }
        .sheet(isPresented: $choosing) {
            SuiteInvPlayerPicker(suite: suite, exclude: sides.first?.player ?? player) { id, name in
                sides = Array(sides.prefix(1)) + [SuiteInvSide(player: id, name: name)]
                Task { await load() }
            }
        }
        .sheet(item: Binding(get: { detail.map { SuiteLockerSelection(row: $0) } }, set: { detail = $0?.row })) { selection in
            SuiteItemDetailSheet(item: selection.row, icons: icons)
        }
        .alert("In Ablage legen", isPresented: Binding(get: { lockerReason != nil }, set: { if !$0 { lockerReason = nil } })) {
            TextField("Grund", text: Binding(get: { lockerReason ?? "" }, set: { lockerReason = $0 }))
            Button("Abbrechen", role: .cancel) {}
            Button("Einlagern") { let reason = lockerReason ?? ""; Task { await toLocker(reason) } }
        } message: { Text("Das Item wird aus dem Inventar entfernt und sicher in der Einbehaltungsablage verwahrt.") }
        .confirmationDialog("Eigenes Paket entfernen?", isPresented: $reset, titleVisibility: .visible) {
            Button("Entfernen", role: .destructive) { Task { await changePack(nil) } }
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.item]) { result in
            Task { do { await changePack(try result.get()) } catch { self.error = error.localizedDescription } }
        }
        .errorAlert($error)
    }

    // MARK: Layout

    private var header: some View {
        HStack(spacing: 10) {
            Image(systemName: pick == nil ? "hand.tap" : "arrow.left.arrow.right").foregroundStyle(Color.panelAccent)
            Text(notice.isEmpty ? (pick == nil ? "Item antippen, dann Zielplatz antippen – auch beim zweiten Spieler. Lange drücken zeigt Details." : "Zielplatz antippen: leer = verschieben, gleiches Item = stapeln, anderes Item = tauschen.") : notice)
                .font(.callout).foregroundStyle(.secondary)
        }
    }

    @ViewBuilder private var panels: some View {
        if sides.count > 1 && sizeClass == .regular {
            HStack(alignment: .top, spacing: 16) { panel(0); panel(1) }
        } else {
            VStack(spacing: 16) { ForEach(sides.indices, id: \.self) { panel($0) } }
        }
    }

    private func panel(_ index: Int) -> some View {
        SuiteInvPanel(side: sides[index], index: index, icons: icons, pick: pick,
                      tap: { slot in tap(index, slot) }, details: { item in detail = item })
    }

    @ViewBuilder private var extras: some View {
        if sides.contains(where: { $0.transfer }) && suite.features.contains("item-locker-v1") {
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
    }

    private func actionBar(_ pick: SuiteInvPick, _ item: JSON) -> some View {
        let side = sides[pick.side]
        let total = max(1, item["amount"].int)
        return VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 12) {
                MinecraftSlot(item: item, icon: icons[item["material"].string]).frame(width: 44)
                VStack(alignment: .leading, spacing: 2) {
                    Text(item["name"].string).font(.headline).lineLimit(1)
                    Text(side.name + " · Slot \(pick.slot)" + (side.offline ? " · offline" : "")).font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                Button { self.pick = nil } label: { Image(systemName: "xmark.circle.fill").font(.title2).foregroundStyle(.secondary) }.buttonStyle(.plain)
            }
            if total > 1 {
                Stepper(amountLabel(total), value: Binding(get: { amount == 0 ? total : amount }, set: { amount = $0 }), in: 1...total)
                    .font(.subheadline)
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    Button("Details", systemImage: "info.circle") { detail = item }
                    if sides.count > 1 {
                        let other = pick.side == 0 ? 1 : 0
                        Button("Zu " + sides[other].name, systemImage: "arrow.right.circle") { Task { await move(pick, to: other, slot: -1) } }
                            .disabled(!sides[other].editable || !side.editable)
                    } else {
                        Button("Zweiten Spieler wählen", systemImage: "person.2") { choosing = true }
                    }
                    if side.transfer { Button("In Ablage", systemImage: "archivebox") { lockerReason = "" } }
                }.buttonStyle(.bordered)
            }
            if !side.editable { Text(side.outdated ? "Spielerdatei aus älterer Version – Spieler muss einmal beitreten." : "Nur Ansicht").font(.caption).foregroundStyle(.secondary) }
        }
        .padding(14).background(.regularMaterial, in: RoundedRectangle(cornerRadius: 20)).padding(.horizontal, 12).padding(.bottom, 6)
        .transition(.move(edge: .bottom).combined(with: .opacity))
    }

    private func amountLabel(_ total: Int) -> String {
        amount == 0 || amount >= total ? "Menge: alle \(total)" : "Menge: \(amount) von \(total)"
    }

    // MARK: Actions

    private func tap(_ index: Int, _ slot: Int) {
        guard !busy, sides.indices.contains(index) else { return }
        if let current = pick {
            if current.side == index && current.slot == slot { pick = nil; return }
            Task { await move(current, to: index, slot: slot) }
            return
        }
        if let item = sides[index].items[slot], !v2 { detail = item; return }
        if sides[index].items[slot] != nil {
            withAnimation(.snappy) { pick = SuiteInvPick(side: index, slot: slot) }
            amount = 0
            Feedback.success()
        }
    }

    private func move(_ from: SuiteInvPick, to index: Int, slot: Int) async {
        guard let source = sides[from.side].items[from.slot] else { return }
        let target = slot >= 0 ? sides[index].items[slot] : nil
        let same = target.map { $0["material"].string == source["material"].string && $0["name"].string == source["name"].string } ?? false
        let swap = target != nil && !same
        let total = source["amount"].int
        if swap && amount > 0 && amount < total { error = "Teilmengen lassen sich nur auf freie oder gleiche Plätze legen."; return }
        var to: [String: JSON] = ["player": .string(sides[index].player)]
        if slot >= 0 { to["slot"] = .number(Double(slot)); to["revision"] = .string(sides[index].revision) }
        else { to["slot"] = .number(-1) }
        var body: [String: JSON] = ["from": .object(["player": .string(sides[from.side].player), "slot": .number(Double(from.slot)), "revision": .string(sides[from.side].revision)]),
                                    "to": .object(to), "mode": .string(swap ? "swap" : "move"), "confirmed": .bool(true)]
        if amount > 0 && amount < total { body["amount"] = .number(Double(amount)) }
        await send(body)
    }

    private func toLocker(_ reason: String) async {
        guard let pick, let source = picked else { return }
        let total = source["amount"].int
        var body: [String: JSON] = ["from": .object(["player": .string(sides[pick.side].player), "slot": .number(Double(pick.slot)), "revision": .string(sides[pick.side].revision)]),
                                    "to": .object(["player": .string("locker")]), "reason": .string(reason), "confirmed": .bool(true)]
        if amount > 0 && amount < total { body["amount"] = .number(Double(amount)) }
        await send(body)
    }

    private func send(_ body: [String: JSON]) async {
        busy = true
        do {
            let reply = try await suite.post("invsee/move", body)
            notice = "\(reply["moved"].int)× verschoben → " + reply["target"].string
            Feedback.success()
        } catch { self.error = error.localizedDescription }
        pick = nil; amount = 0; busy = false
        for index in sides.indices { sides[index].revision = "" }
        await load()
    }

    // MARK: Loading

    private func loop() async {
        if sides.isEmpty { sides = [SuiteInvSide(player: player)] }
        while !Task.isCancelled {
            await load()
            do { try await Task.sleep(for: .seconds(anyOnline ? 2 : 4)) } catch { return }
        }
    }

    private func load() async {
        guard !busy else { return }
        var materials = Set<String>()
        var latestTextures = textures
        for index in sides.indices {
            let current = sides[index]
            do {
                let query: [String: String] = v2 ? ["player": current.player, "revision": current.revision, "offline": "auto", "v2": "1"]
                    : ["player": current.player, "revision": current.revision, "offline": offline ? "true" : "false"]
                let value = try await suite.request("invsee", query: query, timeout: 8)
                guard !Task.isCancelled, !busy, sides.indices.contains(index), sides[index].player == current.player else { return }
                var next = sides[index]
                next.editable = value["editable"].bool
                next.transfer = value["transfer"].bool
                next.offline = value["offline"].bool
                next.outdated = value["outdated"].bool
                if !value["name"].string.isEmpty { next.name = value["name"].string }
                if !value["unchanged"].bool {
                    next.items = Dictionary(value["slots"].array.map { ($0["slot"].int, $0) }, uniquingKeysWith: { first, _ in first })
                    next.revision = value["revision"].string
                    if pick?.side == index { pick = nil }
                }
                next.loaded = true; next.error = ""
                if index == 0 { canPack = value["pack"].bool }
                latestTextures = value["textures"].string
                sides[index] = next
            } catch {
                if Task.isCancelled || RequestFailure.isCancellation(error) { return }
                guard sides.indices.contains(index), sides[index].player == current.player else { return }
                sides[index].error = error.localizedDescription
                sides[index].items = [:]; sides[index].revision = ""; sides[index].editable = false
            }
        }
        for side in sides { for item in side.items.values { materials.insert(item["material"].string); item["contents"].array.forEach { materials.insert($0["material"].string) } } }
        if latestTextures != textures || !materials.isSubset(of: requested) {
            let loaded = await SuiteItemIcons.load(suite, materials: materials)
            guard !Task.isCancelled else { return }
            icons = loaded.icons; requested = materials; textures = latestTextures
            customPack = loaded.custom
            packStatus = (customPack ? "Eigenes ZIP + " : "") + loaded.status
        }
    }

    private func changePack(_ url: URL?) async {
        guard !busy else { return }
        busy = true
        do {
            if let url {
                let accessing = url.startAccessingSecurityScopedResource()
                defer { if accessing { url.stopAccessingSecurityScopedResource() } }
                let limit = 32 * 1024 * 1024
                if let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize, size > limit { throw PanelError(message: "Das ZIP darf maximal 32 MiB groß sein.") }
                let file = try FileHandle(forReadingFrom: url)
                defer { try? file.close() }
                let bytes = try file.read(upToCount: limit + 1) ?? Data()
                guard bytes.count <= limit else { throw PanelError(message: "Das ZIP darf maximal 32 MiB groß sein.") }
                _ = try await suite.upload("invsee/pack", query: [:], data: bytes, contentType: "application/zip", timeout: 120)
            } else {
                _ = try await suite.request("invsee/pack", method: "POST", query: ["mode": "reset"], body: .object(["confirmed": .bool(true)]))
            }
            textures = ""
        } catch { self.error = error.localizedDescription }
        busy = false
        await load()
    }
}

/// One player's inventory in Minecraft style.
struct SuiteInvPanel: View {
    let side: SuiteInvSide
    let index: Int
    let icons: [String: UIImage]
    let pick: SuiteInvPick?
    let tap: (Int) -> Void
    let details: (JSON) -> Void
    private let nine = Array(repeating: GridItem(.flexible(), spacing: 2), count: 9)
    private static let armor: [Int: String] = [39: "shield.lefthalf.filled", 38: "tshirt", 37: "figure.walk", 36: "shoe"]

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            title
            MinecraftPanel(title: "Inventar") {
                HStack(alignment: .top, spacing: 10) {
                    VStack(spacing: 2) { ForEach([39, 38, 37, 36], id: \.self) { slot($0, placeholder: Self.armor[$0]) } }.frame(width: 44)
                    VStack(spacing: 6) {
                        SuitePlayerAvatar(row: .object(["id": .string(side.player)]), size: 96).frame(maxWidth: .infinity, minHeight: 120)
                            .background(Color.black.opacity(0.85)).overlay { MinecraftBevel(light: Color(white: 0.33), dark: .white) }
                        HStack { Spacer(); slot(40, placeholder: "shield").frame(width: 44) }
                    }
                }
                grid(9..<36).padding(.top, 6)
                grid(0..<9).padding(.top, 8)
            }
            MinecraftPanel(title: "Endertruhe") { grid(41..<68) }
        }
    }

    private var title: some View {
        HStack(spacing: 8) {
            Text(side.name.isEmpty ? "Spieler" : side.name).font(.headline)
            PowerBotTag(text: side.offline ? "Offline · Spielerdatei" : "Online · live", tint: side.offline ? .secondary : .green)
            if !side.error.isEmpty { Text(side.error).font(.caption).foregroundStyle(.red).lineLimit(2) }
            else if !side.loaded { ProgressView().controlSize(.small) }
        }
    }

    private func grid(_ range: Range<Int>) -> some View {
        LazyVGrid(columns: nine, spacing: 2) { ForEach(range, id: \.self) { slot($0) } }
    }

    private func slot(_ number: Int, placeholder: String? = nil) -> some View {
        let item = side.items[number]
        let selected = pick == SuiteInvPick(side: index, slot: number)
        let label: String = item.map { (row: JSON) -> String in row["name"].string + " × " + row["amount"].string } ?? "Leer"
        return MinecraftSlot(item: item, icon: item.flatMap { icons[$0["material"].string] }, selected: selected, placeholder: placeholder)
            .contentShape(Rectangle())
            .onTapGesture { tap(number) }
            .onLongPressGesture { if let item { details(item) } }
            .accessibilityLabel("Slot \(number) · \(label)")
            .accessibilityAddTraits(.isButton)
    }
}

/// Search any player, online or offline.
struct SuiteInvPlayerPicker: View {
    let suite: SuiteAPI
    let exclude: String
    let pick: (String, String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var search = ""
    @State private var rows: [JSON] = []
    var body: some View {
        NavigationStack {
            List {
                ForEach(rows.filter { $0["id"].string != exclude }, id: \.["id"].string) { row in
                    Button { pick(row["id"].string, row["name"].string); dismiss() } label: {
                        HStack(spacing: 10) {
                            SuitePlayerAvatar(row: row, size: 32)
                            Text(row["name"].string).font(.headline)
                            Spacer()
                            PowerBotTag(text: row["online"].bool ? "Online" : "Offline", tint: row["online"].bool ? .green : .secondary)
                        }
                    }.buttonStyle(.plain)
                }
                if rows.isEmpty { Text(search.isEmpty ? "Spieler werden geladen …" : "Keine Treffer").foregroundStyle(.secondary) }
            }
            .panelScreen()
            .navigationTitle("Zweiten Spieler wählen")
            .navigationBarTitleDisplayMode(.inline)
            .searchable(text: $search, prompt: "Name – online und offline")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } } }
            .task(id: search) {
                if !search.isEmpty { do { try await Task.sleep(for: .milliseconds(250)) } catch { return } }
                let found = (try? await suite.data("players", query: ["q": search, "limit": "50"]))?["items"].array ?? []
                rows = found.sorted { a, b in a["online"].bool != b["online"].bool ? a["online"].bool : a["name"].string.localizedStandardCompare(b["name"].string) == .orderedAscending }
            }
        }
    }
}

/// Everything about one item: enchantments, durability, lore and the content of shulker boxes.
struct SuiteItemDetailSheet: View {
    let item: JSON
    let icons: [String: UIImage]
    @Environment(\.dismiss) private var dismiss
    private let nine = Array(repeating: GridItem(.flexible(), spacing: 2), count: 9)

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    HStack(spacing: 14) {
                        MinecraftSlot(item: item, icon: icons[item["material"].string]).frame(width: 64)
                        VStack(alignment: .leading, spacing: 3) {
                            Text(item["name"].string).font(.title3.bold()).foregroundStyle(item["enchanted"].bool ? Color.cyan : Color.primary)
                            Text("\(item["amount"].int) / \(max(1, item["max"].int)) · \(item["material"].string)").font(.caption.monospaced()).foregroundStyle(.secondary)
                        }
                    }
                    if item["maxDurability"].int > 0 {
                        LabeledContent("Haltbarkeit", value: "\(item["durability"].int) / \(item["maxDurability"].int)")
                        ProgressView(value: item["durability"].double, total: max(1, item["maxDurability"].double)).tint(.green)
                    }
                }
                if !item["enchantments"].array.isEmpty {
                    Section("Verzauberungen") { ForEach(item["enchantments"].array.map(\.string), id: \.self) { Label($0.capitalized, systemImage: "sparkles").foregroundStyle(.purple) } }
                }
                if !item["lore"].array.isEmpty {
                    Section("Beschreibung") { ForEach(Array(item["lore"].array.enumerated()), id: \.offset) { _, line in Text(line.string).font(.callout).italic() } }
                }
                if item["contents"] != .null {
                    Section("Inhalt") {
                        let contents = Dictionary(item["contents"].array.map { ($0["slot"].int, $0) }, uniquingKeysWith: { first, _ in first })
                        MinecraftPanel(title: "\(contents.count) belegte Plätze") {
                            LazyVGrid(columns: nine, spacing: 2) {
                                ForEach(0..<27, id: \.self) { slot in MinecraftSlot(item: contents[slot], icon: contents[slot].flatMap { icons[$0["material"].string] }) }
                            }
                        }.listRowBackground(Color.clear)
                    }
                }
            }
            .panelScreen()
            .navigationTitle("Item")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Fertig") { dismiss() } } }
        }
    }
}
