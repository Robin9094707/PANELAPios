import SwiftUI

struct SuiteHistoryLink: View {
    let suite: SuiteAPI
    let kind: String
    let title: String
    var body: some View {
        NavigationLink { SuiteHistoryEntriesView(suite: suite, kind: kind, title: title, canManage: suite.scope == "full" && suite.can("history.manage")) } label: {
            Label("Verlauf durchsuchen & bereinigen", systemImage: "archivebox")
        }
    }
}

enum SuiteHistoryText {
    static func title(_ row: JSON) -> String {
        for key in ["action", "title", "text", "name", "author"] where !row[key].string.isEmpty { return row[key].string }
        return "Eintrag " + row["id"].string
    }
    static func detail(_ row: JSON) -> String {
        ["adminName", "author", "name", "targetName", "body", "detail", "note", "source"].compactMap { row[$0].string.isEmpty ? nil : row[$0].string }.joined(separator: " · ")
    }
}

struct SuiteHistoriesView: View {
    let suite: SuiteAPI
    @State private var data: JSON = .null
    @State private var error: String?
    @State private var restoring: JSON?
    @State private var busy = false
    var body: some View {
        List {
            Section {
                Text("Einzelne Einträge auswählen oder einen Verlauf vollständig leeren. Vor dem Löschen wird eine Sicherung auf dem Server erstellt. Neue Einträge nach der Bestätigung bleiben erhalten.").font(.subheadline).foregroundStyle(.secondary)
                if data != .null && !data["canManage"].bool { Label("Zum Löschen: Vollzugriff und das Recht „Verläufe verwalten“.", systemImage: "lock.fill").font(.caption) }
            }
            Section("Verläufe") {
                if data == .null { ProgressView("Verläufe laden …") }
                ForEach(data["kinds"].array, id: \.["id"].string) { kind in
                    NavigationLink { SuiteHistoryEntriesView(suite: suite, kind: kind["id"].string, title: kind["title"].string, canManage: data["canManage"].bool) } label: {
                        HStack { Label(kind["title"].string, systemImage: "list.bullet.rectangle"); Spacer(); Text(kind["count"].string).foregroundStyle(.secondary).monospacedDigit() }
                    }
                }
            }
            if !data["backups"].array.isEmpty {
                Section("Sicherungen wiederherstellen") {
                    ForEach(data["backups"].array, id: \.["id"].string) { backup in
                        Button { restoring = backup } label: {
                            VStack(alignment: .leading, spacing: 4) {
                                Label(backup["title"].string + " · " + backup["count"].string + " Einträge", systemImage: "arrow.uturn.backward")
                                if let date = SuitePlayerPresentation.date(backup["at"]) { Text(date, format: .dateTime.day().month().hour().minute()).font(.caption).foregroundStyle(.secondary) }
                            }
                        }.disabled(busy)
                    }
                }
            }
        }.panelScreen().navigationTitle("Verläufe verwalten").task { await load() }.refreshable { await load() }.errorAlert($error)
            .confirmationDialog("Sicherung wiederherstellen?", isPresented: Binding(get: { restoring != nil }, set: { if !$0 { restoring = nil } }), presenting: restoring) { backup in
                Button("Wiederherstellen") { Task { await restore(backup) } }
                Button("Abbrechen", role: .cancel) { restoring = nil }
            } message: { _ in Text("Gesicherte Einträge werden ergänzt. Bestehende Einträge werden nicht überschrieben. Bei zu wenig Platz wird nichts geändert.") }
    }
    func load() async {
        do { data = try await suite.request("histories") }
        catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }
    func restore(_ backup: JSON) async {
        busy = true; defer { busy = false; restoring = nil }
        do { _ = try await suite.post("histories/restore", ["backup": backup["id"]]); Feedback.success(); await load() }
        catch { self.error = error.localizedDescription }
    }
}

struct SuiteHistoryEntriesView: View {
    let suite: SuiteAPI
    let kind: String
    let title: String
    let canManage: Bool
    @State private var items: [JSON] = []
    @State private var total = 0
    @State private var next = -1
    @State private var query = ""
    @State private var selected: Set<String> = []
    @State private var plan: JSON?
    @State private var error: String?
    @State private var busy = false
    @State private var revision = UUID()
    @State private var notice: String?
    var body: some View {
        List {
            Section {
                LabeledContent("Gefundene Einträge", value: String(total))
                if canManage {
                    Button("\(selected.count) ausgewählte Einträge löschen", role: .destructive) { Task { await prepare(all: false) } }.disabled(selected.isEmpty || busy)
                    if query.isEmpty { Button("Verlauf vollständig leeren …", role: .destructive) { Task { await prepare(all: true) } }.disabled(total == 0 || busy) }
                }
                if busy { ProgressView() }
            }
            ForEach(items, id: \.["id"].string) { row in
                HStack(alignment: .top, spacing: 10) {
                    if canManage {
                        Button { let id = row["id"].string; if selected.contains(id) { selected.remove(id) } else { selected.insert(id) } } label: {
                            Image(systemName: selected.contains(row["id"].string) ? "checkmark.circle.fill" : "circle").foregroundStyle(Color.panelAccent)
                        }.buttonStyle(.plain).accessibilityLabel("Eintrag auswählen")
                    }
                    VStack(alignment: .leading, spacing: 4) {
                        Text(SuiteHistoryText.title(row)).font(.subheadline.weight(.semibold)).textSelection(.enabled)
                        let detail = SuiteHistoryText.detail(row)
                        if !detail.isEmpty { Text(detail).font(.caption).foregroundStyle(.secondary).textSelection(.enabled) }
                        if let date = SuitePlayerPresentation.date(row["time"] == .null ? row["at"] : row["time"]) { Text(date, format: .dateTime.day().month().hour().minute().second()).font(.caption2).foregroundStyle(.secondary) }
                    }
                }.swipeActions {
                    if canManage { Button("Löschen", role: .destructive) { selected = [row["id"].string]; Task { await prepare(all: false) } } }
                }.contextMenu {
                    Button("Kopieren", systemImage: "doc.on.doc") { Clipboard.copy(SuiteHistoryText.title(row) + "\n" + SuiteHistoryText.detail(row)) }
                    if canManage { Button("Löschen …", systemImage: "trash", role: .destructive) { selected = [row["id"].string]; Task { await prepare(all: false) } } }
                }
            }
            if next >= 0 { Button("Weitere Einträge laden") { Task { await load(more: true) } }.disabled(busy) }
            if items.isEmpty && !busy { ContentUnavailableView("Keine Einträge", systemImage: "tray") }
        }.panelScreen().navigationTitle(title).searchable(text: $query, prompt: "Im gesamten Verlauf suchen")
            .task(id: query) { do { try await Task.sleep(for: .milliseconds(250)); await load() } catch { } }
            .refreshable { await load() }.errorAlert($error)
            .confirmationDialog("\(plan?["count"].int ?? 0) Einträge löschen?", isPresented: Binding(get: { plan != nil }, set: { if !$0 { plan = nil } }), presenting: plan) { prepared in
                Button("Sichern & löschen", role: .destructive) { Task { await apply(prepared) } }
                Button("Abbrechen", role: .cancel) { plan = nil }
            } message: { _ in Text("Die bestätigten Einträge werden für alle App- und Web-Nutzer entfernt. Eine Sicherung wird zuerst erstellt und kann unter „Verläufe verwalten“ wiederhergestellt werden.") }
            .alert(notice ?? "", isPresented: Binding(get: { notice != nil }, set: { if !$0 { notice = nil } })) { Button("OK") { notice = nil } }
    }
    func load(more: Bool = false) async {
        let stamp = UUID(); revision = stamp; busy = true
        defer { if revision == stamp { busy = false } }
        do {
            let data = try await suite.request("histories/entries", query: ["kind": kind, "q": query, "offset": String(more ? next : 0)])
            guard revision == stamp, !Task.isCancelled else { return }
            items = more ? SuitePlayerPresentation.merge(items, data["items"].array) : data["items"].array
            total = data["total"].int; next = data["next"].int
            if !more { selected = [] }
        } catch { if revision == stamp && !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }
    func prepare(all: Bool) async {
        guard !busy else { return }; busy = true; defer { busy = false }
        do { plan = try await suite.post("histories/prepare", ["kind": .string(kind), "all": .bool(all), "ids": .array(selected.sorted().map(JSON.string))]) }
        catch { self.error = error.localizedDescription }
    }
    func apply(_ plan: JSON) async {
        self.plan = nil; busy = true
        do { let result = try await suite.post("histories/apply", ["token": plan["token"]]); notice = result["title"].string; Feedback.success(); await load() }
        catch { self.error = error.localizedDescription; busy = false }
    }
}
