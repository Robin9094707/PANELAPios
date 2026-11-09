import SwiftUI

/// 5.6.0: everything one player wrote and did in one list – filter, search, time range, flags and PowerBot cases.
struct SuitePlayerTimelineView: View {
    let suite: SuiteAPI
    let player: String
    var name = ""
    var initialFilter: PlayerTimelineFilter = .all
    @State private var data: JSON = .null
    @State private var filter: PlayerTimelineFilter = .all
    @State private var range: PlayerTimelineRange = .all
    @State private var search = ""
    @State private var expanded: Set<String> = []
    @State private var error: String?
    @State private var applied = false

    var rows: [JSON] { data["items"].array }
    var visible: [JSON] { PlayerTimelineModel.filter(rows, filter: filter, range: range, query: search) }
    var title: String { data["name"].string.isEmpty ? (name.isEmpty ? "Verlauf" : name) : data["name"].string }

    var body: some View {
        let counts = PlayerTimelineModel.counts(rows)
        List {
            Section {
                HStack(spacing: 12) {
                    SuitePlayerAvatar(row: .object(["id": .string(player), "online": data["online"]]), size: 44)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(title).font(.headline)
                        Text(data == .null ? "Verlauf wird geladen …" : "\(rows.count) Einträge · \(counts[.flagged] ?? 0) markiert").font(.caption).foregroundStyle(.secondary)
                    }
                }
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(available, id: \.self) { item in
                            PowerBotChip(title: item.title + ((counts[item] ?? 0) > 0 && item != .all ? " \(counts[item] ?? 0)" : ""), symbol: item.symbol, selected: filter == item) { withAnimation { filter = item } }
                        }
                    }
                }
                Picker("Zeitraum", selection: $range) { ForEach(PlayerTimelineRange.allCases) { Text($0.title).tag($0) } }.pickerStyle(.segmented)
            }
            if data != .null && visible.isEmpty {
                ContentUnavailableView(search.isEmpty ? "Keine Einträge" : "Keine Treffer", systemImage: filter.symbol,
                                       description: Text(range == .all ? "Für diesen Filter gibt es keine gespeicherten Einträge." : "Wähle einen längeren Zeitraum."))
                    .listRowBackground(Color.clear)
            }
            ForEach(PlayerTimelineModel.days(visible), id: \.0) { day, items in
                Section(day + " · \(items.count)") {
                    ForEach(items, id: \.["id"].string) { row in
                        SuitePlayerTimelineRow(suite: suite, row: row, player: title, expanded: expanded.contains(row["id"].string)) {
                            let id = row["id"].string
                            if expanded.contains(id) { expanded.remove(id) } else { expanded.insert(id) }
                        }
                    }
                }
            }
            if data != .null && !data["whispersVisible"].bool && suite.features.contains("player-timeline-v1") {
                Section { Label("Flüsternachrichten benötigen das Recht „Private Flüsternachrichten“.", systemImage: "lock.fill").font(.caption).foregroundStyle(.secondary) }
            }
        }
        .panelScreen()
        .navigationTitle("Verlauf")
        .navigationBarTitleDisplayMode(.inline)
        .searchable(text: $search, prompt: "Nachrichten, Items, Befehle …")
        .task { if !applied { filter = initialFilter; applied = true }; await load() }
        .refreshable { await load() }
        .errorAlert($error)
    }

    private var available: [PlayerTimelineFilter] {
        PlayerTimelineFilter.allCases.filter { item in
            switch item {
            case .whisper: return data["whispersVisible"].bool || data == .null
            case .powerbot: return data["flagsVisible"].bool
            case .action, .combat, .item: return suite.features.contains("player-timeline-v1")
            default: return true
            }
        }
    }

    private func load() async {
        do {
            if suite.features.contains("player-timeline-v1") {
                let next = try await suite.request("player/timeline", query: ["player": player])
                withAnimation { data = next }
            } else {
                // Older suites: the chat history from the dossier in the same layout.
                let dossier = try await suite.request("player/dossier", query: ["player": player])
                data = .object(["name": dossier["name"], "online": dossier["online"], "items": .array(PlayerTimelineModel.legacy(dossier["chatTimeline"].array)),
                                "whispersVisible": dossier["whispersVisible"], "flagsVisible": .bool(false)])
            }
        } catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }
}

struct SuitePlayerTimelineRow: View {
    let suite: SuiteAPI
    let row: JSON
    let player: String
    var expanded = false
    var toggle: () -> Void = {}

    var headline: String {
        switch row["category"].string {
        case "chat": return row["from"].string.isEmpty ? player : row["from"].string
        case "whisper": return row["from"].string + " → " + (row["to"].string.isEmpty ? "?" : row["to"].string)
        case "combat": return "Kampf"
        case "item":
            switch row["kind"].string { case "drop": return "Item abgelegt"; case "pickup": return "Item aufgehoben"; case "given": return "Item weitergegeben"; case "death": return "Tod"; default: return "Item" }
        case "powerbot": return "PowerBot · " + PowerBotPresentation.title(row["kind"].string)
        default: return row["kind"].string == "command" ? "Befehl" : row["kind"].string == "session" ? "Sitzung" : "Aktion"
        }
    }

    var body: some View {
        if !row["case"].string.isEmpty && suite.can("data.powerbot") && suite.features.contains("powerbot-cases-v2") {
            // Rows with a PowerBot case open the case with the complete conversation.
            NavigationLink { SuitePowerBotCaseView(suite: suite, initial: .object(["id": row["case"], "kind": row["kind"], "name": .string(player)])) } label: { content(caseLink: true) }
                .contextMenu { menu }
        } else {
            content(caseLink: false).contentShape(Rectangle()).onTapGesture(perform: toggle).contextMenu { menu }
        }
    }

    @ViewBuilder private var menu: some View {
        Button("Text kopieren", systemImage: "doc.on.doc") { Clipboard.copy(row["text"].string) }
        if let date = SuitePlayerPresentation.date(row["at"]) { Button("Mit Zeit kopieren", systemImage: "clock") { Clipboard.copy(SuiteRelativeTime.absolute(date) + " · " + headline + ": " + row["text"].string) } }
    }

    private func content(caseLink: Bool) -> some View {
        let tint = PlayerTimelineModel.tint(row)
        return HStack(alignment: .top, spacing: 10) {
            Image(systemName: PlayerTimelineModel.symbol(row)).font(.subheadline.weight(.semibold)).foregroundStyle(tint)
                .frame(width: 30, height: 30).background(tint.opacity(0.13), in: RoundedRectangle(cornerRadius: 9))
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text(headline).font(.caption.weight(.bold)).foregroundStyle(row["category"].string == "whisper" ? Color.purple : Color.primary).lineLimit(1)
                    if row["flagged"].bool { PowerBotTag(text: "Markiert", tint: .red) }
                    Spacer(minLength: 0)
                    if let date = SuitePlayerPresentation.date(row["at"]) { Text(SuiteRelativeTime.text(date)).font(.caption2).foregroundStyle(.secondary) }
                }
                Text(row["text"].string).font(.callout).lineLimit(expanded ? nil : 3)
                    .padding(row["flagged"].bool ? 6 : 0)
                    .background(row["flagged"].bool ? Color.red.opacity(0.10) : Color.clear, in: RoundedRectangle(cornerRadius: 8))
                if row["flagged"].bool && !row["flagReason"].string.isEmpty {
                    Label(row["flagReason"].string, systemImage: "shield.lefthalf.filled").font(.caption).foregroundStyle(.red)
                }
                if expanded || caseLink, let date = SuitePlayerPresentation.date(row["at"]) {
                    Text(SuiteRelativeTime.absolute(date) + " Uhr" + (caseLink ? " · PowerBot-Fall öffnen" : "")).font(.caption2.monospacedDigit()).foregroundStyle(.secondary)
                }
            }
        }.padding(.vertical, 3)
    }
}

/// The five newest entries inside the dossier with a link to the full history.
struct SuitePlayerTimelinePreview: View {
    let suite: SuiteAPI
    let player: String
    let name: String
    @State private var rows: [JSON]?
    var body: some View {
        Section {
            if let rows {
                if rows.isEmpty { Text("Noch keine Einträge gespeichert.").foregroundStyle(.secondary) }
                ForEach(rows.prefix(5), id: \.["id"].string) { row in SuitePlayerTimelineRow(suite: suite, row: row, player: name) }
                NavigationLink { SuitePlayerTimelineView(suite: suite, player: player, name: name) } label: {
                    Label("Gesamten Verlauf öffnen · \(rows.count)", systemImage: "clock.arrow.circlepath").font(.subheadline.weight(.semibold))
                }
                let flagged = rows.filter { $0["flagged"].bool }.count
                if flagged > 0 {
                    NavigationLink { SuitePlayerTimelineView(suite: suite, player: player, name: name, initialFilter: .flagged) } label: {
                        Label("\(flagged) markierte Einträge", systemImage: "flag.fill").foregroundStyle(.red)
                    }
                }
            } else { ProgressView() }
        } header: { Text("Verlauf · Chat, Aktionen, Kämpfe & Items") }
        .task { rows = (try? await suite.request("player/timeline", query: ["player": player]))?["items"].array ?? [] }
    }
}
