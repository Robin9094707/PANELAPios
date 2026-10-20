import SwiftUI

struct SuitePlayerRow: View {
    let row: JSON
    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: row["client"].string.uppercased() == "BEDROCK" ? "gamecontroller.fill" : "desktopcomputer").font(.title2).foregroundStyle(.teal).frame(width: 30)
            VStack(alignment: .leading, spacing: 4) {
                Text(row["name"].string).font(.headline)
                Text(SuitePlayerPresentation.device(row)).font(.subheadline).foregroundStyle(.secondary)
                if !row["online"].bool, let date = SuitePlayerPresentation.date(row["lastSeen"]) { Text("Zuletzt online: " + date.formatted(.relative(presentation: .named))).font(.caption).foregroundStyle(.secondary) }
            }
            Spacer(minLength: 4)
            Label(row["online"].bool ? "Online" : "Offline", systemImage: "circle.fill").labelStyle(.iconOnly).font(.caption2).foregroundStyle(row["online"].bool ? Color.green : .secondary.opacity(0.5))
        }.padding(.vertical, 3)
    }
}
struct SuitePlayersView: View {
    let suite: SuiteAPI
    let panel: PanelAPI
    let server: Server
    let actions: [SuiteAction]
    var initialFilter: SuitePlayerFilter = .all
    @StateObject private var directory = SuiteDirectory()
    @State private var search = ""
    @State private var filter: SuitePlayerFilter
    init(suite: SuiteAPI, panel: PanelAPI, server: Server, actions: [SuiteAction], initialFilter: SuitePlayerFilter = .all) {
        self.suite = suite; self.panel = panel; self.server = server; self.actions = actions; self.initialFilter = initialFilter
        _filter = State(initialValue: initialFilter)
    }
    var visible: [JSON] { SuitePlayerPresentation.sorted(directory.rows, filter: filter) }
    var body: some View {
        List {
            Section {
                Picker("Spieler anzeigen", selection: $filter) { ForEach(SuitePlayerFilter.allCases) { Text($0.rawValue).tag($0) } }.pickerStyle(.segmented)
                LabeledContent("Gefundene Profile", value: String(directory.total))
                Text("Spieler suchen und öffnen: Gerät, alle Geräte, Live-Werte und Moderation an einem Ort.").font(.footnote).foregroundStyle(.secondary)
            }
            Section(filter == .recent ? "Offline · zuletzt gesehen zuerst" : filter.rawValue) {
                ForEach(visible, id: \.["id"].string) { row in
                    NavigationLink { SuitePlayerView(suite: suite, panel: panel, server: server, player: row["id"].string, actions: actions, initialProfile: row) } label: { SuitePlayerRow(row: row) }
                }
                if directory.busy { ProgressView(filter == .recent ? "Letzte Aktivitäten aller Profile laden …" : "Spieler laden …") }
                if visible.isEmpty && !directory.busy { ContentUnavailableView("Keine Spieler gefunden", systemImage: "person.crop.circle.badge.questionmark", description: Text("Suche nach einem Namen oder wechsle den Filter.")) }
                if directory.next >= 0 && !directory.busy && filter != .online { Button("Weitere Spieler laden") { Task { await load(reset: false) } } }
            }
        }.panelScreen().navigationTitle("Spieler").searchable(text: $search, prompt: "Name oder UUID").textInputAutocapitalization(.never).autocorrectionDisabled()
            .task(id: search + "|" + filter.rawValue) { do { try await Task.sleep(for: .milliseconds(250)); await load(reset: true) } catch {} }
            .refreshable { await load(reset: true) }.errorAlert($directory.error)
    }
    func load(reset: Bool) async { await directory.load(suite: suite, query: search, filter: filter, reset: reset) }
}
struct SuitePlayerView: View {
    let suite: SuiteAPI
    let panel: PanelAPI
    let server: Server
    let player: String
    let actions: [SuiteAction]
    var initialProfile: JSON = .null
    @State private var profile: JSON = .null
    @State private var error: String?
    @State private var automatic = false
    @State private var updated: Date?
    var current: JSON { profile == .null ? initialProfile : profile }
    var live: JSON { current["live"] }
    var name: String { current["name"].string.isEmpty ? player : current["name"].string }
    var body: some View {
        List {
            Section {
                HStack(spacing: 14) { Image(systemName: "person.crop.circle.fill").font(.system(size: 42)).foregroundStyle(.teal.gradient); VStack(alignment: .leading) { Text(name).font(.title2.bold()).textSelection(.enabled); Label(current["online"].bool ? "Online" : "Offline", systemImage: "circle.fill").font(.caption).foregroundStyle(current["online"].bool ? .green : .secondary) } }
                CopyButton(text: name, title: "Spielername kopieren")
                CopyButton(text: player, title: "UUID kopieren")
                LabeledContent("Client", value: current["client"].string)
                LabeledContent(current["online"].bool ? "Aktuelles Gerät" : "Letztes Gerät", value: SuitePlayerPresentation.device(current))
                if let date = SuitePlayerPresentation.date(current["lastSeen"]), !current["online"].bool { LabeledContent("Zuletzt online") { Text(date, format: .dateTime.day().month().year().hour().minute()) } }
                if !current["version"].string.isEmpty { LabeledContent("Spielversion", value: current["version"].string) }
                LabeledContent("Simplex Points", value: current["points"].string)
                LabeledContent("Warnungen", value: current["warnings"].string)
            }
            Section("Schnellaktionen") {
                NavigationLink { SuiteDataListView(suite: suite, kind: "devices", player: player, actions: actions) } label: { Label("Alle Geräte anzeigen", systemImage: "iphone.and.arrow.right.inward") }
                if let action = actions.first(where: { $0.id == "security.ultraban" }) {
                    NavigationLink { SuiteActionView(suite: suite, action: action, preset: ["player": .string(player)]) } label: { Label("Ultrabann", systemImage: "shield.slash.fill").foregroundStyle(.red) }.accessibilityIdentifier("player-ultraban")
                }
                if let action = actions.first(where: { $0.id == "player.punish" }) {
                    NavigationLink { SuiteActionView(suite: suite, action: action, preset: ["player": .string(player), "type": .string("ban")]) } label: { Label("Bannen / weitere Strafen", systemImage: "hand.raised.fill") }
                    if current["online"].bool { NavigationLink { SuiteActionView(suite: suite, action: action, preset: ["player": .string(player), "type": .string("kick")]) } label: { Label("Kicken", systemImage: "rectangle.portrait.and.arrow.right") } }
                }
            }
            if live != .null {
                Section("Live-Spielerwerte") {
                    HStack(spacing: 3) { ForEach(0..<10) { i in Image(systemName: live["health"].double / 2 >= Double(i + 1) ? "heart.fill" : live["health"].double / 2 > Double(i) ? "heart.lefthalf.filled" : "heart").foregroundStyle(.pink) } }
                    LabeledContent("Gesundheit", value: "\(live["health"].string) / \(live["maxHealth"].string)")
                    LabeledContent("Hunger", value: live["food"].string + " / 20")
                    LabeledContent("Sättigung", value: live["saturation"].string)
                    LabeledContent("XP-Level", value: live["level"].string)
                    LabeledContent("Spielmodus", value: live["gamemode"].string)
                    LabeledContent("Welt", value: live["world"].string)
                    LabeledContent("Position", value: ["x", "y", "z"].map { String(format: "%.1f", live[$0].double) }.joined(separator: " / ")).font(.caption.monospaced()).textSelection(.enabled)
                    if let action = actions.first(where: { $0.id == "player.vitals" }) { NavigationLink("Live-Werte bearbeiten") { SuiteActionView(suite: suite, action: action, preset: ["player": .string(player)]) } }
                }
            }
            Section { DisclosureGroup("Spielerakte & gespeicherte Daten") {
                NavigationLink { SuiteDataListView(suite: suite, kind: "alts", player: player, actions: actions) } label: { Label("Alt-Accounts & Belege", systemImage: "person.2.badge.key.fill") }
                NavigationLink { SuiteDataListView(suite: suite, kind: "devices", player: player, actions: actions) } label: { Label("Geräte & Diagnose", systemImage: "iphone.and.arrow.right.inward") }
                NavigationLink { SuiteSnapshotsView(suite: suite, player: player, actions: actions) } label: { Label("Inventar-Snapshots", systemImage: "archivebox") }
                NavigationLink { SuiteDataListView(suite: suite, kind: "inventory", player: player, actions: actions) } label: { Label("Aktuelles Inventar & Endertruhe", systemImage: "square.grid.3x3.fill") }.disabled(!current["online"].bool)
                NavigationLink { SuiteNBTPathsView(suite: suite, panel: panel, server: server, player: player) } label: { Label("Gespeicherte NBT-Dateien", systemImage: "doc.badge.gearshape") }
                ForEach([("player.info", "Vollständige Spielerakte"), ("player.identity", "Identität & Ultrabann"), ("player.history", "Aktions- und Chatverlauf"), ("player.rights", "Alle Spielerrechte")], id: \.0) { route, title in NavigationLink(title) { SuiteReadView(suite: suite, path: route, title: title, values: ["player": .string(player), "category": .string("all")]) } }
            }
            }
            Section { DisclosureGroup("Weitere Spieleraktionen") {
                ForEach(actions.filter { ["player.right", "player.notes", "player.watchlist", "player.action", "player.message", "player.points", "player.nickname", "security.alt-ban", "moderation.restrict", "moderation.banmode"].contains($0.id) }) { action in NavigationLink(action.title) { SuiteActionView(suite: suite, action: action, preset: ["player": .string(player)]) } }
            }
            }
            Section {
                Toggle("Live alle 15 Sekunden aktualisieren", isOn: $automatic)
                if let updated { LabeledContent("Stand") { Text(updated, style: .time) } }
                Text("Live-Werte stammen direkt aus der Suite. NBT-Dateien auf der Festplatte sind ein separater Speicherstand und werden nur bei gestopptem Server geändert.").font(.footnote).foregroundStyle(.secondary)
            }
        }.panelScreen().navigationTitle(name).navigationBarTitleDisplayMode(.inline).task(id: automatic) { await load(); while automatic && !Task.isCancelled { do { try await Task.sleep(for: .seconds(15)) } catch { return }; await load() } }.refreshable { await load() }.errorAlert($error)
    }
    func load() async { do { profile = try await suite.data("profile", query: ["player": player]); updated = Date() } catch { if !Task.isCancelled { self.error = error.localizedDescription } } }
}
struct SuiteDataListView: View {
    let suite: SuiteAPI
    let kind: String
    let player: String
    let actions: [SuiteAction]
    @State private var rows: [JSON] = []
    @State private var error: String?
    @State private var loaded = false
    var title: String { kind == "alts" ? "Alt-Accounts" : kind == "inventory" ? "Inventar" : "Geräte" }
    var body: some View {
        List {
            if rows.isEmpty { if loaded { ContentUnavailableView("Keine Daten vorhanden", systemImage: "tray") } else { ProgressView() } }
            ForEach(rows, id: \.["id"].string) { row in
                Section {
                    Text(row["name"].string).font(.headline).textSelection(.enabled)
                    if kind == "alts" {
                        HStack { Text(row["level"].string); Spacer(); Text("\(row["score"].int) / 100").font(.headline.monospacedDigit()).foregroundStyle(row["score"].int >= 85 ? .orange : .teal) }
                        ForEach(Array(row["evidence"].array.enumerated()), id: \.offset) { _, line in Text(line.string).font(.subheadline) }
                        Text(row["safe"].bool ? "Für automatische Kaskaden als ausreichend eingestuft" : "Hinweis auf Zusammenhang; keine sichere Identität bewiesen").font(.caption).foregroundStyle(.secondary)
                    } else if kind == "inventory" {
                        LabeledContent("Bereich", value: row["container"].string); LabeledContent("Slot", value: row["slot"].string); LabeledContent("Anzahl", value: row["amount"].string)
                        if !row["lore"].array.isEmpty { ForEach(Array(row["lore"].array.enumerated()), id: \.offset) { _, value in Text(cleanConsole(value.string)).font(.caption) } }
                    } else {
                        if let date = SuitePlayerPresentation.date(row["lastSeen"]) { LabeledContent("Zuletzt verwendet") { Text(date, format: .dateTime.day().month().year().hour().minute()) } }
                        ForEach([("platform", "Plattform"), ("os", "Betriebssystem"), ("version", "Spielversion"), ("language", "Sprache"), ("input", "Eingabe")], id: \.0) { key, label in LabeledContent(label, value: row[key].string.isEmpty ? "Nicht erfasst" : row[key].string) }
                        Label(row["blocked"].bool ? "Gesperrt" : "Nicht gesperrt", systemImage: row["blocked"].bool ? "lock.fill" : "checkmark.shield").foregroundStyle(row["blocked"].bool ? .orange : .teal)
                        ForEach(row["diagnostics"].object.keys.sorted(), id: \.self) { key in LabeledContent(key, value: row["diagnostics"][key].string).font(.caption).textSelection(.enabled) }
                        if let action = actions.first(where: { $0.id == "player.devices" }) { NavigationLink("Gerät verwalten") { SuiteActionView(suite: suite, action: action, preset: ["player": .string(player), "identifier": row["id"]]) } }
                    }
                    CopyButton(text: row["id"].string, title: kind == "alts" ? "Spieler-UUID kopieren" : "Kennung kopieren")
                }
            }
        }.panelScreen().navigationTitle(title).navigationBarTitleDisplayMode(.inline).task { await load() }.refreshable { await load() }.errorAlert($error)
    }
    func load() async { do { let items = try await suite.data(kind, query: ["player": player])["items"].array; rows = kind == "devices" ? items.sorted { $0["lastSeen"].double > $1["lastSeen"].double } : items; loaded = true } catch { if !RequestFailure.isCancellation(error) && !Task.isCancelled { self.error = error.localizedDescription } } }
}
struct SuiteUltraView: View {
    let suite: SuiteAPI
    let actions: [SuiteAction]
    @State private var rows: [JSON] = []
    @State private var search = ""
    @State private var next = -1
    @State private var error: String?
    @State private var revision = UUID()
    @State private var busy = false
    var body: some View {
        List {
            Section { if let action = actions.first(where: { $0.id == "security.ultraunban" }) { NavigationLink { SuiteActionView(suite: suite, action: action) } label: { Label("Gesamten Ultrabann aufheben", systemImage: "lock.open.fill") } }; Text("Ein zugehöriger Spielername oder die Gruppen-ID genügt. Die Suite hebt die zugehörige Gruppe über ihre vorhandene Ultrabannverwaltung auf.").font(.footnote).foregroundStyle(.secondary) }
            ForEach(rows, id: \.["id"].string) { row in
                Section {
                    HStack { Text(row["name"].string).font(.headline); Spacer(); Text(row["active"].bool ? "Aktiv" : "Aufgehoben / abgelaufen").font(.caption).foregroundStyle(row["active"].bool ? .orange : .secondary) }
                    Text(row["reason"].string).textSelection(.enabled)
                    LabeledContent("Modus", value: row["mode"].string)
                    LabeledContent("Blockierte Versuche", value: row["attempts"].string)
                    DisclosureGroup("Zugehörige Accounts · \(row["members"].array.count)") { ForEach(row["members"].array, id: \.["id"].string) { member in VStack(alignment: .leading) { Text(member["name"].string); Text(member["relation"].string).font(.caption).foregroundStyle(.secondary) }.textSelection(.enabled) } }
                    CopyButton(text: row["id"].string, title: "Gruppen-ID kopieren")
                    if row["active"].bool, let action = actions.first(where: { $0.id == "security.ultraunban" }) { NavigationLink("Diese Gruppe aufheben") { SuiteActionView(suite: suite, action: action, preset: ["target": row["id"]]) } }
                }
            }
            if next >= 0 { Button("Weitere Gruppen laden") { Task { await load(reset: false) } }.disabled(busy) }
            if busy { ProgressView() }
        }.panelScreen().navigationTitle("Ultrabanns").searchable(text: $search, prompt: "Spieler oder Gruppen-ID").task(id: search) { do { try await Task.sleep(for: .milliseconds(250)); await load(reset: true) } catch {} }.refreshable { await load(reset: true) }.errorAlert($error)
    }
    func load(reset: Bool) async { if busy && !reset { return }; let stamp = UUID(); revision = stamp; busy = true; defer { if revision == stamp { busy = false } }; do { let response = try await suite.data("ultrabans", query: ["q": search, "offset": reset ? "0" : String(next)]); guard revision == stamp && !Task.isCancelled else { return }; rows = reset ? response["items"].array : rows + response["items"].array; next = response["next"].int } catch { if revision == stamp && !Task.isCancelled { self.error = error.localizedDescription } } }
}
struct SuiteSnapshotsView: View {
    let suite: SuiteAPI
    let player: String
    let actions: [SuiteAction]
    @State private var rows: [JSON] = []
    @State private var error: String?
    var body: some View {
        List {
            if let action = actions.first(where: { $0.id == "player.snapshots" }) { Section { NavigationLink("Neuen Snapshot erstellen") { SuiteActionView(suite: suite, action: action, preset: ["player": .string(player), "operation": .string("create")]) } } }
            ForEach(rows, id: \.["id"].string) { row in Section { Text(cleanConsole(row["name"].string)).font(.headline); LabeledContent("Größe", value: bytes(row["size"].double)); CopyButton(text: row["id"].string, title: "Dateiname kopieren"); NavigationLink("Inhalt ansehen") { SuiteSnapshotContentView(suite: suite, player: player, file: row["id"].string) }; if let action = actions.first(where: { $0.id == "player.snapshots" }) { NavigationLink("Wiederherstellen oder löschen") { SuiteActionView(suite: suite, action: action, preset: ["player": .string(player), "file": row["id"], "operation": .string("restore")]) } } } }
            if rows.isEmpty { Text("Noch keine Snapshots geladen oder gespeichert.").foregroundStyle(.secondary) }
        }.panelScreen().navigationTitle("Snapshots").task { await load() }.refreshable { await load() }.errorAlert($error)
    }
    func load() async { do { rows = try await suite.data("snapshots", query: ["player": player])["items"].array } catch { if !RequestFailure.isCancellation(error) && !Task.isCancelled { self.error = error.localizedDescription } } }
}
struct SuiteSnapshotContentView: View {
    let suite: SuiteAPI
    let player: String
    let file: String
    @State private var data: JSON = .null
    @State private var error: String?
    var body: some View { List { Section { ForEach([("level", "XP-Level"), ("food", "Hunger"), ("health", "Gesundheit"), ("gamemode", "Spielmodus"), ("actor", "Erstellt von")], id: \.0) { key, title in LabeledContent(title, value: data[key].string) } }; ForEach(data["items"].array, id: \.["id"].string) { item in Section { HStack { Image(systemName: "shippingbox.fill").foregroundStyle(.teal); Text(cleanConsole(item["name"].string)); Spacer(); Text("×" + item["amount"].string).monospacedDigit() }; LabeledContent(item["container"].string, value: "Slot " + item["slot"].string); ForEach(Array(item["lore"].array.enumerated()), id: \.offset) { _, text in Text(cleanConsole(text.string)).font(.caption) } } } }.panelScreen().navigationTitle("Snapshot-Inhalt").task { await load() }.refreshable { await load() }.errorAlert($error) }
    func load() async { do { data = try await suite.data("snapshot-content", query: ["player": player, "file": file]) } catch { if !RequestFailure.isCancellation(error) && !Task.isCancelled { self.error = error.localizedDescription } } }
}
struct SuiteNBTPathsView: View {
    let suite: SuiteAPI
    let panel: PanelAPI
    let server: Server
    let player: String
    @State private var rows: [JSON] = []
    @State private var editor: EditorItem?
    @State private var error: String?
    var body: some View {
        List { Section { Text("Die Suite findet die gespeicherten Spielerdateien. Der vollständige NBT-Editor nutzt die bestehende Panel-Dateiverwaltung, prüft Konflikte und erstellt vor Änderungen eine Sicherung. Anwenden erfordert einen gestoppten Server.").font(.footnote).foregroundStyle(.secondary) }; ForEach(rows, id: \.["id"].string) { row in Section { Text(row["name"].string).font(.headline); Text(row["id"].string).font(.caption.monospaced()).textSelection(.enabled); Button("Im NBT-Editor öffnen", systemImage: "doc.badge.gearshape") { editor = EditorItem(path: row["id"].string) }.requiresAccess(panel, server.id, "file.read-content") } }; if rows.isEmpty { Text("Keine gespeicherte Spielerdatei in den geladenen Welten gefunden.").foregroundStyle(.secondary) } }.panelScreen().navigationTitle("Spieler-NBT").task { await load() }.refreshable { await load() }.errorAlert($error).sheet(item: $editor) { NBTEditor(api: panel, server: server, path: $0.path) }
    }
    func load() async { do { rows = try await suite.data("nbt-paths", query: ["player": player])["items"].array } catch { if !RequestFailure.isCancellation(error) && !Task.isCancelled { self.error = error.localizedDescription } } }
}
struct SuiteSettingsView: View {
    let suite: SuiteAPI
    @State private var rows: [JSON] = []
    @State private var search = ""
    @State private var error: String?
    var body: some View {
        List {
            Section { Text("Vorhandene Suite-Einstellungen werden nach ihrem tatsächlichen Typ dargestellt. Zugangsschlüssel und geschützte Authentifizierung bleiben lokal. Nach Änderungen ist je nach Modul ein Suite-Reload nötig.").font(.footnote).foregroundStyle(.secondary) }
            ForEach(rows.filter { search.isEmpty || ($0["name"].string + $0["id"].string).localizedCaseInsensitiveContains(search) }, id: \.["id"].string) { row in NavigationLink { SuiteSettingEditor(suite: suite, setting: row) } label: { VStack(alignment: .leading, spacing: 5) { HStack { Text(row["name"].string); Spacer(); if row["kind"].string == "bool" { Image(systemName: row["value"].bool ? "checkmark.circle.fill" : "circle").foregroundStyle(row["value"].bool ? .teal : .secondary) } }; Text(row["id"].string).font(.caption).foregroundStyle(.secondary); if row["kind"].string != "bool" { Text(row["value"].array.isEmpty ? row["value"].string : "\(row["value"].array.count) Einträge").font(.caption).lineLimit(2) } } } }
        }.panelScreen().navigationTitle("Suite-Einstellungen").searchable(text: $search).task { await load() }.refreshable { await load() }.errorAlert($error)
    }
    func load() async { do { rows = try await suite.data("settings")["items"].array } catch { if !RequestFailure.isCancellation(error) && !Task.isCancelled { self.error = error.localizedDescription } } }
}
struct SuiteSettingEditor: View {
    let suite: SuiteAPI
    let setting: JSON
    @State private var value = ""
    @State private var toggle = false
    @State private var apply = false
    var body: some View {
        Form { Section { Text(setting["name"].string).font(.headline); Text(setting["hint"].string).font(.footnote).foregroundStyle(.secondary); Text(setting["id"].string).font(.caption.monospaced()).textSelection(.enabled) }; Section("Wert") { if setting["kind"].string == "bool" { Toggle("Aktiv", isOn: $toggle) } else { TextField("Wert", text: $value, axis: .vertical).textInputAutocapitalization(.never).autocorrectionDisabled(); if setting["kind"].string == "list" { Text("Listeneinträge mit | trennen.").font(.caption).foregroundStyle(.secondary) } } }; Section { Button("Änderung prüfen") { apply = true }.disabled(suite.scope != "full" || suite.demo) } }.panelScreen().navigationTitle("Einstellung ändern").task { value = setting["kind"].string == "list" ? setting["value"].array.map(\.string).joined(separator: " | ") : setting["value"].string; toggle = setting["value"].bool }
            .sheet(isPresented: $apply) { NavigationStack { SuiteActionView(suite: suite, action: .make("settings.update", "Suite-Einstellung speichern"), preset: ["expected": setting["revision"], "key": setting["id"], "value": .string(setting["kind"].string == "bool" ? String(toggle) : value)]) } }
    }
}
