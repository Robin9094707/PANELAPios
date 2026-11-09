import SwiftUI
import UIKit

/// 5.7.0: three bouncing dots while the assistant is working.
struct SuiteTypingBubble: View {
    var label = "Simplex KI denkt nach …"
    @State private var phase = 0
    var body: some View {
        HStack(alignment: .bottom, spacing: 8) {
            SuiteAIAvatar()
            HStack(spacing: 5) {
                ForEach(0..<3, id: \.self) { index in
                    Circle().fill(Color.panelAccent).frame(width: 8, height: 8)
                        .scaleEffect(phase == index ? 1.25 : 0.75).opacity(phase == index ? 1 : 0.45)
                }
            }
            .padding(.horizontal, 14).padding(.vertical, 12)
            .background(Color.secondary.opacity(0.13), in: RoundedRectangle(cornerRadius: 18))
            Spacer(minLength: 40)
        }
        .accessibilityElement(children: .ignore).accessibilityLabel(label)
        .task {
            while !Task.isCancelled {
                withAnimation(.easeInOut(duration: 0.3)) { phase = (phase + 1) % 3 }
                do { try await Task.sleep(for: .milliseconds(320)) } catch { return }
            }
        }
    }
}

struct SuiteAIAvatar: View {
    var size: CGFloat = 28
    var body: some View {
        Image(systemName: "sparkles").font(.system(size: size * 0.45, weight: .bold)).foregroundStyle(.white)
            .frame(width: size, height: size)
            .background(LinearGradient(colors: [Color.panelAccent, Color.panelSecondary], startPoint: .topLeading, endPoint: .bottomTrailing), in: Circle())
            .accessibilityHidden(true)
    }
}

/// Reveals a freshly received answer word by word (about 1.5 s at most), then shows the formatted text.
struct SuiteRevealText: View {
    let text: String
    var animate: Bool
    let formatted: (String) -> Text
    @State private var shown = Int.max
    private var words: [Substring] { text.split(separator: " ", omittingEmptySubsequences: false) }
    var body: some View {
        Group {
            if shown >= words.count { formatted(text) }
            else { Text(words.prefix(shown).joined(separator: " ")) + Text(" ▍").foregroundColor(Color.panelAccent) }
        }
        .task(id: text) {
            guard animate, !UIAccessibility.isReduceMotionEnabled, words.count > 3 else { shown = .max; return }
            shown = 0
            let step = max(1, words.count / 60)
            let delay = max(12, min(45, 1500 / max(1, words.count / step)))
            while shown < words.count {
                do { try await Task.sleep(for: .milliseconds(delay)) } catch { shown = .max; return }
                shown += step
            }
            shown = .max
        }
    }
}

/// Chat list with search (titles and messages), pinning, renaming, clearing, auto-delete and deleting.
struct SuiteAIChatManager: View {
    let suite: SuiteAPI
    let current: String
    let select: (String) async -> Void
    let create: () async -> Void
    let changed: () async -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var rows: [JSON] = []
    @State private var search = ""
    @State private var renaming: JSON?
    @State private var name = ""
    @State private var deleting: JSON?
    @State private var clearing: JSON?
    @State private var busy = false
    @State private var error: String?
    var v2: Bool { suite.features.contains("ai-chat-v2") }

    var body: some View {
        NavigationStack {
            List {
                if rows.isEmpty && !busy {
                    ContentUnavailableView(search.isEmpty ? "Noch keine Chats" : "Keine Treffer", systemImage: "bubble.left.and.bubble.right",
                                           description: Text(search.isEmpty ? "Starte einen neuen Chat mit der Simplex KI." : "Suche nach Titel oder Inhalt."))
                }
                let pinned = rows.filter { $0["pinned"].bool }
                if !pinned.isEmpty { Section("Angepinnt") { ForEach(pinned, id: \.["id"].string) { row($0) } } }
                let other = rows.filter { !$0["pinned"].bool }
                if !other.isEmpty { Section(pinned.isEmpty ? "Deine privaten Chats" : "Weitere Chats") { ForEach(other, id: \.["id"].string) { row($0) } } }
                Section { Text("Chats sind privat. Wische für Anpinnen und Löschen, halte gedrückt für Umbenennen, Leeren und Auto-Löschen.").font(.caption).foregroundStyle(.secondary) }
            }
            .panelScreen()
            .navigationTitle("KI-Chats")
            .searchable(text: $search, prompt: v2 ? "Titel und Nachrichten durchsuchen" : "Chats durchsuchen")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Fertig") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Neuer Chat", systemImage: "square.and.pencil") { Task { busy = true; await create(); busy = false; dismiss() } }.disabled(busy)
                }
            }
            .task(id: search) {
                if !search.isEmpty { do { try await Task.sleep(for: .milliseconds(250)) } catch { return } }
                await load()
            }
            .alert("Chat umbenennen", isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
                TextField("Name", text: $name)
                Button("Abbrechen", role: .cancel) {}
                Button("Speichern") { if let row = renaming { Task { await change("update", row, title: name, days: row["days"].int) } } }
            }
            .confirmationDialog("„\(deleting?["title"].string ?? "")“ löschen?", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }), titleVisibility: .visible, presenting: deleting) { row in
                Button("Chat löschen", role: .destructive) { Task { await change("delete", row) } }
                Button("Abbrechen", role: .cancel) {}
            }
            .confirmationDialog("Verlauf von „\(clearing?["title"].string ?? "")“ leeren?", isPresented: Binding(get: { clearing != nil }, set: { if !$0 { clearing = nil } }), titleVisibility: .visible, presenting: clearing) { row in
                Button("Verlauf leeren", role: .destructive) { Task { await change("clear", row) } }
                Button("Abbrechen", role: .cancel) {}
            }
            .errorAlert($error)
        }
    }

    private func row(_ row: JSON) -> some View {
        let id = row["id"].string
        return Button {
            Task { busy = true; await select(id); busy = false; dismiss() }
        } label: {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: id == current ? "bubble.left.and.text.bubble.right.fill" : "bubble.left.and.bubble.right")
                    .foregroundStyle(id == current ? Color.panelAccent : Color.secondary).frame(width: 26)
                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: 6) {
                        Text(row["title"].string).font(.headline).foregroundStyle(.primary).lineLimit(1)
                        if row["pinned"].bool { Image(systemName: "pin.fill").font(.caption2).foregroundStyle(.orange) }
                        Spacer(minLength: 0)
                        if let date = SuitePlayerPresentation.date(row["at"]) { Text(SuiteRelativeTime.text(date)).font(.caption2).foregroundStyle(.secondary) }
                    }
                    let detail = row["match"].string.isEmpty ? row["preview"].string : row["match"].string
                    if !detail.isEmpty { Text(detail).font(.caption).foregroundStyle(.secondary).lineLimit(2) }
                    HStack(spacing: 8) {
                        if row["count"].int > 0 { Label("\(row["count"].int)", systemImage: "text.bubble") }
                        if row["days"].int > 0 { Label("löscht nach \(row["days"].int) T.", systemImage: "timer") }
                    }.font(.caption2).foregroundStyle(.secondary)
                }
            }.contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(busy)
        .swipeActions(edge: .leading) {
            if v2 { Button(row["pinned"].bool ? "Lösen" : "Anpinnen", systemImage: row["pinned"].bool ? "pin.slash" : "pin") { Task { await change("pin", row) } }.tint(.orange) }
        }
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            Button("Löschen", systemImage: "trash") { deleting = row }.tint(.red)
        }
        .contextMenu {
            Button("Umbenennen", systemImage: "pencil") { name = row["title"].string; renaming = row }
            if v2 {
                Button(row["pinned"].bool ? "Lösen" : "Anpinnen", systemImage: "pin") { Task { await change("pin", row) } }
                Button("Verlauf leeren", systemImage: "eraser") { clearing = row }
            }
            Menu("Automatisch löschen") {
                ForEach([(0, "Aus"), (1, "Nach 1 Tag"), (7, "Nach 7 Tagen"), (30, "Nach 30 Tagen")], id: \.0) { days, title in
                    if row["days"].int == days { Button(title, systemImage: "checkmark") { } }
                    else { Button(title) { Task { await change("update", row, title: row["title"].string, days: days) } } }
                }
            }
            Button("Löschen", systemImage: "trash", role: .destructive) { deleting = row }
        }
    }

    private func load() async {
        do { rows = try await suite.request("assistant/chats", query: search.isEmpty ? [:] : ["q": search], timeout: 12)["items"].array.filter { search.isEmpty || v2 || SuiteSearch.matches(search, in: $0["title"].string) } }
        catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }

    private func change(_ operation: String, _ row: JSON, title: String = "", days: Int = 0) async {
        busy = true; defer { busy = false }
        do {
            _ = try await suite.request("assistant/chats", method: "POST", body: .object([
                "operation": .string(operation), "id": row["id"], "title": .string(title), "days": .number(Double(days))]), timeout: 12)
            Feedback.success()
            await load(); await changed()
        } catch { self.error = error.localizedDescription }
    }
}
