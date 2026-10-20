import SwiftUI
import UIKit

struct ConsoleAnchor {
    let context: String
    let delta: CGFloat
    static func range(of context: String, in text: String) -> NSRange? {
        guard !context.isEmpty else { return nil }
        let range = (text as NSString).range(of: context, options: .backwards)
        return range.location == NSNotFound ? nil : range
    }
}
@MainActor final class ConsoleViewportState: ObservableObject {
    var offset = CGPoint.zero
    var selection = NSRange(location: 0, length: 0)
    var atBottom = true
    var initialized = false
    var anchor: ConsoleAnchor?
}
struct ConsoleTextView: UIViewRepresentable {
    let text: String
    let fontSize: Double
    let follow: Bool
    let viewport: ConsoleViewportState
    var active = true
    var jumpRevision = 0
    var refresh: (() async -> Void)? = nil
    final class Coordinator: NSObject, UITextViewDelegate {
        var previous = ""
        var size = 0.0
        var active = true
        var jumpRevision = 0
        var applying = false
        let viewport: ConsoleViewportState
        var refresh: (() async -> Void)?
        init(_ viewport: ConsoleViewportState) { self.viewport = viewport }
        @objc func pulled(_ sender: UIRefreshControl) {
            Task { @MainActor in await refresh?(); sender.endRefreshing() }
        }
        func scrollViewDidScroll(_ scrollView: UIScrollView) {
            guard !applying, let view = scrollView as? UITextView else { return }
            capture(view)
        }
        func textViewDidChangeSelection(_ textView: UITextView) {
            guard !applying else { return }; viewport.selection = textView.selectedRange
        }
        func capture(_ view: UITextView) {
            guard !previous.isEmpty else { return }
            viewport.offset = view.contentOffset; viewport.selection = view.selectedRange
            viewport.atBottom = view.contentOffset.y + view.bounds.height >= view.contentSize.height + view.adjustedContentInset.bottom - 40
            viewport.initialized = true
            guard view.textStorage.length > 0 else { viewport.anchor = nil; return }
            let point = CGPoint(x: 0, y: max(0, view.contentOffset.y - view.textContainerInset.top))
            let glyph = view.layoutManager.glyphIndex(for: point, in: view.textContainer)
            let char = min(view.textStorage.length - 1, view.layoutManager.characterIndexForGlyph(at: glyph))
            let old = previous as NSString
            guard char < old.length else { return }
            let paragraph = old.paragraphRange(for: NSRange(location: char, length: 0))
            let range = NSRange(location: paragraph.location, length: min(512, old.length - paragraph.location))
            let rect = view.layoutManager.boundingRect(forGlyphRange: view.layoutManager.glyphRange(forCharacterRange: NSRange(location: paragraph.location, length: 1), actualCharacterRange: nil), in: view.textContainer)
            viewport.anchor = ConsoleAnchor(context: old.substring(with: range), delta: view.contentOffset.y - rect.minY - view.textContainerInset.top)
        }
        func restore(_ view: UITextView, text: String) {
            var y = viewport.offset.y
            if let anchor = viewport.anchor, let range = ConsoleAnchor.range(of: anchor.context, in: text) {
                let glyphs = view.layoutManager.glyphRange(forCharacterRange: NSRange(location: range.location, length: 1), actualCharacterRange: nil)
                let rect = view.layoutManager.boundingRect(forGlyphRange: glyphs, in: view.textContainer)
                y = rect.minY + view.textContainerInset.top + anchor.delta
            }
            let maxY = max(-view.adjustedContentInset.top, view.contentSize.height - view.bounds.height + view.adjustedContentInset.bottom)
            view.setContentOffset(CGPoint(x: 0, y: min(maxY, max(-view.adjustedContentInset.top, y))), animated: false)
        }
    }
    func makeCoordinator() -> Coordinator { Coordinator(viewport) }
    func makeUIView(context: Context) -> UITextView {
        let view = UITextView()
        view.delegate = context.coordinator; context.coordinator.refresh = refresh
        let control = UIRefreshControl(); control.addTarget(context.coordinator, action: #selector(Coordinator.pulled(_:)), for: .valueChanged); view.refreshControl = control
        view.isEditable = false; view.isSelectable = true; view.backgroundColor = .clear
        view.textContainer.widthTracksTextView = true; view.textContainer.lineFragmentPadding = 0
        view.textContainerInset = UIEdgeInsets(top: 12, left: 16, bottom: 12, right: 16)
        view.alwaysBounceVertical = true; view.accessibilityIdentifier = "consoleOutput"
        view.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        return view
    }
    func updateUIView(_ view: UITextView, context: Context) {
        let c = context.coordinator
        c.refresh = refresh
        let changed = c.previous != text || c.size != fontSize
        let jump = c.jumpRevision != jumpRevision
        let resumed = active && !c.active
        c.active = active; c.jumpRevision = jumpRevision
        guard changed || jump else { return }
        if !c.previous.isEmpty { c.capture(view) }
        let first = !viewport.initialized
        let shouldFollow = follow && active && !resumed && (first || viewport.atBottom) && viewport.selection.length == 0 && !view.isTracking && !view.isDragging && !view.isDecelerating && view.refreshControl?.isRefreshing != true
        let oldText = c.previous as NSString
        var selection = viewport.selection
        if selection.length > 0, NSMaxRange(selection) <= oldText.length {
            let selected = oldText.substring(with: selection)
            selection = ConsoleAnchor.range(of: selected, in: text) ?? NSRange(location: 0, length: 0)
        }
        c.applying = true
        if changed {
            view.attributedText = ConsoleStyle.attributed(text, size: fontSize); c.previous = text; c.size = fontSize
            view.layoutManager.ensureLayout(for: view.textContainer); view.layoutIfNeeded()
        }
        if NSMaxRange(selection) <= view.textStorage.length { view.selectedRange = selection }
        if (jump || shouldFollow) && !text.isEmpty {
            if jump { view.selectedRange = NSRange(location: view.textStorage.length, length: 0) }
            let y = max(-view.adjustedContentInset.top, view.contentSize.height - view.bounds.height + view.adjustedContentInset.bottom)
            view.setContentOffset(CGPoint(x: 0, y: y), animated: false)
        } else { c.restore(view, text: text) }
        c.applying = false
        c.capture(view)
    }
    static func dismantleUIView(_ view: UITextView, coordinator: Coordinator) { coordinator.capture(view); view.delegate = nil }
}
struct PlayersCard: View {
    @ObservedObject var live: LiveServer
    let api: PanelAPI
    let server: Server
    @ObservedObject var suite: SuiteModel
    @State private var showPlayers = false
    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: "person.2.fill").font(.title2).foregroundStyle(.teal)
            VStack(alignment: .leading, spacing: 4) {
                Text("Spieler online").font(.headline)
                Text(live.players.map { "\($0.online)" + (($0.maximum ?? live.playerCapacity).map { " von \($0)" } ?? " von ?") } ?? "Warte auf Spielerdaten …").font(.subheadline).monospacedDigit().foregroundStyle(.secondary)
            }
            Spacer()
            Button("Spielerübersicht", systemImage: "info.circle") { showPlayers = true }.labelStyle(.iconOnly).font(.title2).accessibilityIdentifier("showPlayers")
        }.card().sheet(isPresented: $showPlayers) { NavigationStack { IntegratedPlayersView(model: suite, live: live, initialFilter: .online).toolbar { ToolbarItem(placement: .confirmationAction) { Button("Fertig") { showPlayers = false } } } } }
    }
}
struct PlayerListSheet: View {
    @ObservedObject var live: LiveServer
    let api: PanelAPI
    let server: Server
    @Environment(\.dismiss) private var dismiss
    @State private var search = ""
    var body: some View {
        NavigationStack {
            List {
                Section {
                    Label(live.players.map { "\($0.online) Spieler online" } ?? "Spielerdaten laden …", systemImage: "person.2.fill").font(.title2.bold()).foregroundStyle(.teal)
                    if let snapshot = live.players { LabeledContent("Kapazität", value: (snapshot.maximum ?? live.playerCapacity).map(String.init) ?? "Unbekannt"); LabeledContent("Quelle", value: snapshot.source); LabeledContent("Aktualisiert") { Text(snapshot.updated, style: .time) } }
                    if let issue = live.playerIssue { Text(issue).font(.caption).foregroundStyle(.secondary) }
                }
                Section { NavigationLink { PlayerManagerView(api: api, server: server, live: live) } label: { Label("Spieler verwalten", systemImage: "person.crop.circle.badge.checkmark") } }
                Section("Online") {
                    ForEach((live.players?.names ?? []).filter { search.isEmpty || $0.localizedCaseInsensitiveContains(search) }, id: \.self) { name in
                        HStack { Image(systemName: "person.crop.circle.fill").font(.title).foregroundStyle(name.hasPrefix(".") ? .blue : .teal); VStack(alignment: .leading) { Text(name).font(.headline).textSelection(.enabled); Text(name.hasPrefix(".") ? "Bedrock-Präfix" : "Spieler").font(.caption).foregroundStyle(.secondary) }; Spacer(); Button("Kopieren", systemImage: "doc.on.doc") { Clipboard.copy(name) }.labelStyle(.iconOnly).buttonStyle(.borderless) }
                    }
                    if live.players?.names.isEmpty != false { Text("Keine Spielernamen verfügbar").foregroundStyle(.secondary) }
                }
            }.panelScreen().refreshable { await live.refreshPlayers(api: api, server: server) }.searchable(text: $search, prompt: "Spieler suchen").navigationTitle("Spieler").toolbar { ToolbarItem(placement: .confirmationAction) { Button("Fertig") { dismiss() } } }
        }
    }
}
struct TPSCard: View {
    @ObservedObject var live: LiveServer
    let api: PanelAPI
    let server: Server
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack { Label("Server-TPS", systemImage: "waveform.path.ecg").font(.headline); Spacer(); if let date = live.tps?.updated { Text(date, style: .time).font(.caption).foregroundStyle(.secondary) } }
            if let issue = live.tpsIssue { Label(issue, systemImage: "exclamationmark.circle").font(.caption).foregroundStyle(.orange) }
            HStack {
                Text(live.tpsRequested.map { "Angefragt um " + $0.formatted(date: .omitted, time: .standard) } ?? "Abfrage startet nach der Live-Verbindung").font(.caption2).foregroundStyle(.secondary)
                Spacer()
                Button("Aktualisieren", systemImage: "arrow.clockwise") { Task { await live.requestTPS(api: api, server: server) } }
                    .labelStyle(.iconOnly).disabled(!live.connected || live.state != "running").requiresAccess(api, server.id, "control.console")
            }
            if live.state == "offline" { Text("Server offline").foregroundStyle(.secondary) }
            else if let tps = live.tps {
                HStack { sample("1 Minute", tps.minute); Spacer(); sample("5 Minuten", tps.five); Spacer(); sample("15 Minuten", tps.fifteen) }
                Text(Date().timeIntervalSince(tps.updated) > 75 ? "Messwert veraltet · warte auf neue Ausgabe" : "20 TPS = volle Tickrate · Aktualisierung ca. alle 30 s").font(.caption).foregroundStyle(.secondary)
            } else { Text(live.state == "offline" ? "Server offline" : "Warte auf Ausgabe des Befehls tps …").font(.footnote).foregroundStyle(.secondary) }
        }.card()
    }
    func sample(_ label: String, _ number: Double) -> some View { VStack(alignment: .leading, spacing: 5) { Text(number, format: .number.precision(.fractionLength(2))).font(.title2.bold()).monospacedDigit().foregroundStyle(TPSSnapshot.color(number)); Text(label).font(.caption).foregroundStyle(.secondary) } }
}
struct CommandLibrary: View {
    static let suggestions = ["list", "tps", "mspt", "version", "plugins", "help", "save-all", "whitelist list", "whitelist on", "whitelist off", "time query daytime", "weather clear", "gamerule keepInventory", "difficulty", "say ", "tp ", "gamemode "]
    let api: PanelAPI
    let server: Server
    let choose: (String) -> Void
    @EnvironmentObject private var workspace: WorkspaceStore
    @Environment(\.dismiss) private var dismiss
    @State private var add = false
    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text("Ein Antippen fügt den Befehl ins Eingabefeld ein. Gesendet wird erst mit dem Senden-Knopf. Vorschläge stammen lokal aus der App und sind keine Java-Tabvervollständigung.").font(.footnote).foregroundStyle(.secondary)
                }
                Section("Deine Shortcuts") {
                    ForEach(workspace.shortcuts[workspace.key(api, server)] ?? []) { value in
                        Button { choose(value.command); dismiss() } label: { VStack(alignment: .leading) { Text(value.name).font(.headline); Text(value.command).font(.system(.footnote, design: .monospaced)).foregroundStyle(.secondary) } }
                            .swipeActions { Button("Löschen", role: .destructive) { workspace.removeShortcut(value.id, api, server) } }
                    }
                    Button("Shortcut hinzufügen", systemImage: "plus") { add = true }
                }
                Section("Minecraft / Paper") {
                    ForEach(Self.suggestions, id: \.self) { value in Button(value) { choose(value); dismiss() }.font(.system(.body, design: .monospaced)) }
                }
            }.panelScreen().navigationTitle("Befehls-Shortcuts").toolbar { ToolbarItem(placement: .confirmationAction) { Button("Fertig") { dismiss() } } }
                .sheet(isPresented: $add) {
                    ActionForm(title: "Shortcut speichern", fields: [Field(key: "name", label: "Name"), Field(key: "command", label: "Befehl", kind: .multiline)]) { values in
                        let command = values["command"]?.string.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                        guard !command.isEmpty, !command.contains("\n") else { throw PanelError(message: "Bitte genau einen Befehl eingeben.") }
                        let name = values["name"]?.string ?? ""
                        workspace.addShortcut(CommandShortcut(name: name.isEmpty ? command : name, command: command), api, server)
                    }
                }
        }
    }
}
