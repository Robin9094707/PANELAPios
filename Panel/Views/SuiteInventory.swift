import SwiftUI
import UIKit
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
                    .overlay { if item["enchanted"].bool { MinecraftGlint() } }
                if item["maxDurability"].int > 0 && item["durability"].int < item["maxDurability"].int {
                    MinecraftDurability(value: item["durability"].double / max(1, item["maxDurability"].double))
                }
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

/// Purple shimmer over enchanted items, like the enchantment glint in game.
struct MinecraftGlint: View {
    @State private var phase = false
    var body: some View {
        LinearGradient(colors: [.clear, Color(red: 0.75, green: 0.45, blue: 1).opacity(0.55), .clear], startPoint: phase ? .bottomLeading : .topLeading, endPoint: phase ? .topTrailing : .bottomTrailing)
            .blendMode(.plusLighter).allowsHitTesting(false)
            .onAppear { if !UIAccessibility.isReduceMotionEnabled { withAnimation(.easeInOut(duration: 2.2).repeatForever(autoreverses: true)) { phase = true } } }
    }
}

/// Green-to-red durability bar at the bottom of a slot.
struct MinecraftDurability: View {
    let value: Double
    var body: some View {
        GeometryReader { geo in
            VStack { Spacer()
                ZStack(alignment: .leading) {
                    Rectangle().fill(.black).frame(height: 2)
                    Rectangle().fill(Color(hue: 0.33 * max(0, min(1, value)), saturation: 1, brightness: 1)).frame(width: geo.size.width * 0.8 * max(0, min(1, value)), height: 2)
                }.frame(width: geo.size.width * 0.8).padding(.bottom, geo.size.height * 0.1)
            }.frame(maxWidth: .infinity)
        }.allowsHitTesting(false)
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
                lockerGrid
                historyCard
            }.padding(16).frame(maxWidth: 620).frame(maxWidth: .infinity)
        }
        .panelScreen()
        .navigationTitle("Einbehaltungsablage")
        .task { await load() }
        .refreshable { await load() }
        .sheet(item: selection) { selection in
            SuiteLockerItemSheet(suite: suite, row: selection.row, icon: icons[selection.row["material"].string], manage: data["manage"].bool) { await load() }
        }
        .errorAlert($error)
    }

    private var selection: Binding<SuiteLockerSelection?> {
        Binding(get: { selected.map { SuiteLockerSelection(row: $0) } }, set: { selected = $0?.row })
    }

    private var held: [JSON] { data["items"].array }
    private var slotCount: Int { max(27, ((held.count + 8) / 9) * 9) }

    private var lockerGrid: some View {
        MinecraftPanel(title: "Einbehaltungsablage · \(data["count"].int)") {
            LazyVGrid(columns: nine, spacing: 2) {
                ForEach(0..<slotCount, id: \.self) { index in lockerSlot(index) }
            }
        }
    }

    @ViewBuilder private func lockerSlot(_ index: Int) -> some View {
        if index < held.count {
            let row = held[index]
            let label = row["name"].string + " × " + row["amount"].string + " von " + row["fromName"].string
            Button { selected = row } label: { MinecraftSlot(item: row, icon: icons[row["material"].string]) }
                .buttonStyle(.plain).accessibilityLabel(label)
        } else {
            MinecraftSlot(item: nil, icon: nil)
        }
    }

    @ViewBuilder private var historyCard: some View {
        let history = Array(data["history"].array.prefix(30))
        if !history.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                Label("Verlauf", systemImage: "clock.arrow.circlepath").font(.headline)
                ForEach(history, id: \.["id"].string) { row in historyRow(row) }
            }.frame(maxWidth: .infinity, alignment: .leading).card()
        }
    }

    private func historyRow(_ row: JSON) -> some View {
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
                        Section("Spieler wählen (online oder offline)") {
                            TextField("Spieler suchen", text: $search).textInputAutocapitalization(.never).autocorrectionDisabled()
                            ForEach(players.prefix(12), id: \.["id"].string) { player in
                                Button { Task { await give(player["id"].string) } } label: {
                                    HStack { PowerBotPlayerLabel(id: player["id"].string, name: player["name"].string); Spacer(); PowerBotTag(text: player["online"].bool ? "Online" : "Offline", tint: player["online"].bool ? .green : .secondary) }
                                }.buttonStyle(.plain)
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
                let found = (try? await suite.data("players", query: ["limit": "50", "q": search]))?["items"].array ?? []
                players = found.sorted { a, b in a["online"].bool && !b["online"].bool }
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
