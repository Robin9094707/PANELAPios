import SwiftUI
import UIKit

struct ConsoleTextView: UIViewRepresentable {
    let text: String
    let fontSize: Double
    let follow: Bool
    final class Coordinator { var previous = "" }
    func makeCoordinator() -> Coordinator { Coordinator() }
    func makeUIView(context: Context) -> UITextView {
        let view = UITextView()
        view.isEditable = false; view.isSelectable = true; view.backgroundColor = .clear
        view.textContainer.widthTracksTextView = true; view.textContainer.lineFragmentPadding = 0
        view.textContainerInset = UIEdgeInsets(top: 12, left: 16, bottom: 12, right: 16)
        view.alwaysBounceVertical = true; view.accessibilityIdentifier = "consoleOutput"
        view.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        return view
    }
    func updateUIView(_ view: UITextView, context: Context) {
        view.font = .monospacedSystemFont(ofSize: fontSize, weight: .regular)
        view.textColor = .label
        guard context.coordinator.previous != text else { return }
        let selection = view.selectedRange; let offset = view.contentOffset
        view.text = text; context.coordinator.previous = text
        if selection.length > 0 && NSMaxRange(selection) <= view.text.utf16.count { view.selectedRange = selection; view.setContentOffset(offset, animated: false) }
        else if follow && !text.isEmpty { view.scrollRangeToVisible(NSRange(location: max(0, view.text.utf16.count - 1), length: 1)) }
        else { view.setContentOffset(offset, animated: false) }
    }
}
struct PlayersCard: View {
    @ObservedObject var live: LiveServer
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Label("Spieler online", systemImage: "person.2.fill").font(.headline)
                Spacer()
                Text(live.players.map { "\($0.online)" + ($0.maximum.map { " / \($0)" } ?? "") } ?? "–").font(.title2.bold()).monospacedDigit()
            }
            if let snapshot = live.players {
                if snapshot.names.isEmpty { Text(snapshot.online == 0 ? "Gerade ist niemand online." : "Der Provider liefert keine Spielernamen.").foregroundStyle(.secondary) }
                else { ForEach(snapshot.names, id: \.self) { name in HStack { Circle().fill(.green).frame(width: 7, height: 7); Text(name).font(.system(.body, design: .monospaced)).textSelection(.enabled); Spacer(); Button("Kopieren", systemImage: "doc.on.doc") { UIPasteboard.general.string = name }.labelStyle(.iconOnly).buttonStyle(.borderless) } } }
                HStack { Text(snapshot.source); Spacer(); Text(snapshot.updated, style: .time) }.font(.caption).foregroundStyle(.secondary)
            } else { Text(live.playerIssue ?? "Warte auf Spielerdaten …").font(.footnote).foregroundStyle(.secondary) }
            Text("API ca. alle 15 s, Rückfall über list ca. alle 30 s. Nur während diese Serveransicht aktiv ist.").font(.caption2).foregroundStyle(.secondary)
        }.frame(maxWidth: .infinity, alignment: .leading).card()
    }
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
