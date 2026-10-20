import SwiftUI
import UIKit

struct ConsoleTextView: UIViewRepresentable {
    let text: String
    let fontSize: Double
    let follow: Bool
    var refresh: (() async -> Void)? = nil
    final class Coordinator: NSObject {
        var previous = ""
        var size = 0.0
        var refresh: (() async -> Void)?
        @objc func pulled(_ sender: UIRefreshControl) {
            Task { @MainActor in await refresh?(); sender.endRefreshing() }
        }
    }
    func makeCoordinator() -> Coordinator { Coordinator() }
    func makeUIView(context: Context) -> UITextView {
        let view = UITextView()
        context.coordinator.refresh = refresh
        let control = UIRefreshControl(); control.addTarget(context.coordinator, action: #selector(Coordinator.pulled(_:)), for: .valueChanged); view.refreshControl = control; view.alwaysBounceVertical = true
        view.isEditable = false; view.isSelectable = true; view.backgroundColor = .clear
        view.textContainer.widthTracksTextView = true; view.textContainer.lineFragmentPadding = 0
        view.textContainerInset = UIEdgeInsets(top: 12, left: 16, bottom: 12, right: 16)
        view.alwaysBounceVertical = true; view.accessibilityIdentifier = "consoleOutput"
        view.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        return view
    }
    func updateUIView(_ view: UITextView, context: Context) {
        context.coordinator.refresh = refresh
        guard context.coordinator.previous != text || context.coordinator.size != fontSize else { return }
        let selection = view.selectedRange; let offset = view.contentOffset
        view.attributedText = ConsoleStyle.attributed(text, size: fontSize); context.coordinator.previous = text; context.coordinator.size = fontSize
        if selection.length > 0 && NSMaxRange(selection) <= view.text.utf16.count { view.selectedRange = selection; view.setContentOffset(offset, animated: false) }
        else if follow && !text.isEmpty && !view.isTracking && !view.isDragging && !view.isDecelerating && view.refreshControl?.isRefreshing != true { view.scrollRangeToVisible(NSRange(location: max(0, view.text.utf16.count - 1), length: 1)) }
        else { view.setContentOffset(offset, animated: false) }
    }
}
struct PlayersCard: View {
    @ObservedObject var live: LiveServer
    let api: PanelAPI
    let server: Server
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
        }.card().sheet(isPresented: $showPlayers) { PlayerListSheet(live: live, api: api, server: server) }
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
