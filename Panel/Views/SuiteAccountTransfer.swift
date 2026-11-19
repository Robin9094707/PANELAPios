import SwiftUI

struct SuiteAccountTransferView: View {
    let suite: SuiteAPI
    @State private var capabilities: JSON = .null
    @State private var old: JSON = .null
    @State private var current: JSON = .null
    @State private var choice: Side?
    @State private var options: [String: Bool] = [:]
    @State private var preview: JSON = .null
    @State private var result: JSON = .null
    @State private var confirmation = ""
    @State private var expires = Date.distantPast
    @State private var busy = false
    @State private var error: String?

    private enum Side: String, Identifiable {
        case old, current
        var id: String { rawValue }
        var title: String { self == .old ? "Alter Account" : "Neuer Account" }
    }

    private let integrations = [
        ("points", "Simplex Points"),
        ("luckPerms", "LuckPerms-Gruppen und Rechte"),
        ("homes", "Home-Dateien"),
        ("multiverse", "Multiverse-Inventories")
    ]

    var body: some View {
        Form {
            if result != .null {
                outcome
            } else if preview != .null {
                review
            } else if capabilities == .null {
                if busy { ProgressView("Transfer wird geladen …") }
                else { Button("Erneut laden") { Task { await load() } } }
            } else {
                selection
            }
        }
        .panelScreen()
        .navigationTitle("Account-Transfer")
        .task { await load() }
        .errorAlert($error)
        .sheet(item: $choice) { side in
            NavigationStack {
                TransferPlayerPicker(suite: suite, title: side.title) { player in
                    if side == .old { old = player } else { current = player }
                    choice = nil
                }
            }
        }
    }

    private var selection: some View {
        Group {
            Section {
                accountButton(.old, player: old)
                accountButton(.current, player: current)
            } header: { Text("Welche Accounts möchtest du tauschen?") } footer: {
                Text("Beide Accounts müssen ausgeloggt sein. Das bisherige Inventar des neuen Accounts landet auf dem alten Account.")
            }
            Section("Welche zusätzlichen Daten sollen mit?") {
                Label("Spielstand, Endertruhe und Fortschritt", systemImage: "checkmark.circle.fill")
                ForEach(integrations, id: \.0) { item in
                    if capabilities[item.0].bool {
                        Toggle(item.1, isOn: Binding(get: { options[item.0] == true }, set: { options[item.0] = $0 }))
                    }
                }
                if capabilities["homesDetected"].bool && !capabilities["homes"].bool {
                    Label("Homes benötigen ein bestätigtes Dateiformat und ein gestopptes Homes-Plugin.", systemImage: "info.circle")
                }
                if capabilities["multiverseDetected"].bool && !capabilities["multiverse"].bool {
                    Label("Multiverse läuft separat. Sein Profil kann beim nächsten Login die Paper-Ausrüstung wieder überschreiben.", systemImage: "exclamationmark.triangle")
                }
            }
            Section {
                Button("Vorschau berechnen", systemImage: "arrow.right.arrow.left") { Task { await prepare() } }
                    .disabled(busy || old == .null || current == .null || old["id"] == current["id"] || !capabilities["ready"].bool)
                if busy { ProgressView() }
            }
        }
    }

    private func accountButton(_ side: Side, player: JSON) -> some View {
        Button { choice = side } label: {
            LabeledContent(side.title, value: player == .null ? "Spieler wählen" : player["name"].string)
        }.disabled(busy)
    }

    private var review: some View {
        Group {
            Section("Wohin gehen die Daten?") {
                LabeledContent("Alter Account erhält den Stand von", value: preview["newName"].string)
                LabeledContent("Neuer Account erhält den Stand von", value: preview["oldName"].string)
            }
            Section("Bestand vor dem Tausch") {
                ForEach(Array(preview["rows"].array.enumerated()), id: \.offset) { _, row in
                    VStack(alignment: .leading, spacing: 5) {
                        Text(row["area"].string).font(.headline)
                        if !row["path"].string.isEmpty { Text(row["path"].string).font(.caption).foregroundStyle(.secondary) }
                        LabeledContent(preview["oldName"].string, value: row["old"].string)
                        LabeledContent(preview["newName"].string, value: row["new"].string)
                        if row["oldItems"] != .null {
                            DisclosureGroup("Items vergleichen") {
                                items(row["oldItems"], name: preview["oldName"].string)
                                items(row["newItems"], name: preview["newName"].string)
                            }
                        }
                    }
                }
            }
            Section {
                TextField("TAUSCHEN eingeben", text: $confirmation)
                    .textInputAutocapitalization(.characters).autocorrectionDisabled()
                TimelineView(.periodic(from: .now, by: 1)) { clock in
                    let remaining = max(0, Int(expires.timeIntervalSince(clock.date)))
                    Button("Daten jetzt tauschen", systemImage: "arrow.right.arrow.left", role: .destructive) {
                        Task { await execute() }
                    }.disabled(busy || confirmation.trimmingCharacters(in: .whitespacesAndNewlines) != "TAUSCHEN" || remaining == 0)
                    Text(remaining == 0 ? "Vorschau abgelaufen" : "Vorschau noch \(remaining) s gültig").font(.caption)
                }
                Button("Auswahl bearbeiten") { preview = .null; confirmation = "" }.disabled(busy)
                if busy { ProgressView("Server sichert und tauscht die Daten …") }
            } footer: { Text("Sicherungen werden auf dem Server gespeichert. Die beiden Accounts können sich während des Tauschs nicht anmelden.") }
        }
    }

    private func items(_ items: JSON, name: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(name).font(.subheadline.bold())
            if items.array.isEmpty { Text("Leer").foregroundStyle(.secondary) }
            ForEach(items.array, id: \.string) { item in Text(item.string).font(.caption) }
        }.padding(.vertical, 4)
    }

    private var outcome: some View {
        Group {
            Section(result["title"].string) {
                ForEach(result["completed"].array, id: \.string) { Label($0.string, systemImage: "checkmark.circle") }
                ForEach(result["errors"].array, id: \.string) { Label($0.string, systemImage: "exclamationmark.triangle") }
                LabeledContent("Sicherung", value: result["backup"].string).textSelection(.enabled)
            }
            Button("Weiteren Transfer vorbereiten") {
                old = .null
                current = .null
                preview = .null
                result = .null
                confirmation = ""
            }
        }
    }

    private func load() async {
        guard capabilities == .null, !busy else { return }
        busy = true
        defer { busy = false }
        do {
            capabilities = try await suite.request("account-transfer")
            options = Dictionary(uniqueKeysWithValues: integrations.map { ($0.0, capabilities[$0.0].bool) })
        } catch { show(error) }
    }

    private func prepare() async {
        guard !busy else { return }
        busy = true
        defer { busy = false }
        do {
            preview = try await suite.request("account-transfer/preview", method: "POST",
                body: .object(["old": old["id"], "new": current["id"], "options": .object(options.mapValues { .bool($0) })]),
                timeout: 40)
            expires = Date().addingTimeInterval(preview["expiresIn"].double)
        } catch { show(error) }
    }

    private func execute() async {
        guard !busy else { return }
        busy = true
        defer { busy = false }
        do {
            result = try await suite.request("account-transfer/execute", method: "POST",
                body: .object(["ticket": preview["ticket"], "confirm": .string(confirmation.trimmingCharacters(in: .whitespacesAndNewlines))]),
                timeout: 90)
        } catch {
            preview = .null
            confirmation = ""
            show(error)
        }
    }

    private func show(_ error: Error) {
        if !Task.isCancelled && !RequestFailure.isCancellation(error) { self.error = error.localizedDescription }
    }
}

private struct TransferPlayerPicker: View {
    let suite: SuiteAPI
    let title: String
    let select: (JSON) -> Void
    @State private var query = ""
    @State private var players: [JSON] = []
    @State private var error: String?
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        List(players, id: \.["id"].string) { player in
            Button { select(player) } label: {
                VStack(alignment: .leading) {
                    Text(player["name"].string)
                    Text(player["online"].bool ? "Online · zuerst ausloggen" : player["id"].string)
                        .font(.caption).foregroundStyle(.secondary)
                }
            }.disabled(player["online"].bool)
        }
        .panelScreen()
        .navigationTitle(title)
        .searchable(text: $query, prompt: "Name oder UUID")
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Schließen") { dismiss() } } }
        .errorAlert($error)
        .task(id: query) {
            do {
                try await Task.sleep(for: .milliseconds(250))
                let result = try await suite.request("account-transfer/players", query: ["q": query])
                try Task.checkCancellation()
                players = result["items"].array
            } catch {
                if !Task.isCancelled && !RequestFailure.isCancellation(error) { self.error = error.localizedDescription }
            }
        }
    }
}
