import SwiftUI

struct SuiteDiscordView: View {
    let suite: SuiteAPI
    @State private var rows: [JSON] = []
    @State private var search = ""
    @State private var busy = false
    @State private var error: String?
    var visible: [JSON] { rows.filter { search.isEmpty || ($0["name"].string + $0["id"].string).localizedCaseInsensitiveContains(search) } }
    var channels: [JSON] { visible.filter { $0["id"].string.hasSuffix("channel-id") } }
    var groups: [String] { Set(visible.filter { !$0["id"].string.hasSuffix("channel-id") }.map { $0["id"].string.split(separator: ".").dropFirst().first.map(String.init) ?? "Allgemein" }).sorted() }
    var body: some View {
        List {
            Section {
                Label("Discord-Zentrale", systemImage: "bubble.left.and.bubble.right.fill").font(.headline)
                Text("Kanäle auswählen, Chat-Relay, Protokolle, Botprofil und weitere vorhandene Module einstellen. Bot-Token und geschützte Zugangsdaten werden auf dem Server eingerichtet.").font(.footnote).foregroundStyle(.secondary)
                if busy { ProgressView("Discord-Einstellungen laden …") }
                if suite.scope == "full" { NavigationLink("Module nach Änderungen neu laden") { SuiteActionView(suite: suite, action: .make("server.reload", "Suite-Module neu laden")) } }
                if suite.scope != "full" { Text("Änderungen benötigen Vollzugriff.").font(.caption).foregroundStyle(.orange) }
            }
            if !channels.isEmpty { Section("Kanäle & Protokollziele") { ForEach(channels, id: \.["id"].string) { row in setting(row) } } }
            ForEach(groups, id: \.self) { group in
                Section(group.replacingOccurrences(of: "-", with: " ").capitalized) {
                    ForEach(visible.filter { !$0["id"].string.hasSuffix("channel-id") && $0["id"].string.split(separator: ".").dropFirst().first.map(String.init) == group }, id: \.["id"].string) { row in setting(row) }
                }
            }
        }.panelScreen().navigationTitle("Discord").searchable(text: $search, prompt: "Kanal oder Einstellung suchen").task { await load() }.refreshable { await load() }.errorAlert($error)
    }
    func title(_ row: JSON) -> String {
        let key = row["id"].string
        let names = ["discord-bot.status-channel-id": "Serverstatus", "discord-bot.relay-channel-id": "Minecraft ↔ Discord Chat", "discord-bot.statistics.channel-id": "Serverstatistiken", "discord-bot.vpn-log.channel-id": "VPN-Protokoll", "discord-bot.alerts.channel-id": "Admin-Hinweise", "discord-bot.admin.audit-channel-id": "Admin-Aktionsprotokoll"]
        return names[key] ?? row["name"].string
    }
    func setting(_ row: JSON) -> some View {
        NavigationLink { SuiteSettingEditor(suite: suite, setting: row) } label: {
            VStack(alignment: .leading, spacing: 4) { Text(title(row)); Text(row["id"].string.replacingOccurrences(of: "discord-bot.", with: "")).font(.caption).foregroundStyle(.secondary); Text(row["value"].array.isEmpty ? (row["value"].string.isEmpty ? "Nicht eingerichtet" : row["value"].string) : "\(row["value"].array.count) Einträge").font(.caption).foregroundStyle(.secondary).lineLimit(2) }
        }
    }
    func load() async { busy = true; defer { busy = false }; do { rows = try await suite.data("settings", query: ["q": "discord-bot."])["items"].array.filter { $0["id"].string.hasPrefix("discord-bot.") } } catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } } }
}

struct SuiteDiscordChannelPicker: View {
    let suite: SuiteAPI
    @Binding var value: String
    @State private var channels: [JSON] = []
    @State private var error: String?
    @State private var busy = false
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Picker("Discord-Kanal", selection: $value) {
                Text("Deaktiviert / kein Kanal").tag("")
                if !value.isEmpty && !channels.contains(where: { $0["id"].string == value }) { Text("Gespeichert: " + value).tag(value) }
                ForEach(channels, id: \.["id"].string) { row in Text("#" + row["name"].string).tag(row["id"].string) }
            }.pickerStyle(.navigationLink)
            TextField("Kanal-ID manuell", text: $value).keyboardType(.numberPad).textInputAutocapitalization(.never).autocorrectionDisabled()
            if busy { ProgressView("Kanäle abrufen …") }
            if let error { Text(error).font(.caption).foregroundStyle(.orange) }
            Button("Kanäle neu laden", systemImage: "arrow.clockwise") { Task { await load() } }.disabled(busy)
            Text("Der Kanal muss zum konfigurierten Discord-Server gehören und für den Bot erreichbar sein. Private Sicherheitsprotokolle behalten ihre bestehende Rechteprüfung.").font(.caption).foregroundStyle(.secondary)
        }.task { await load() }
    }
    func load() async { guard !busy else { return }; busy = true; error = nil; defer { busy = false }; do { channels = try await suite.data("discord-channels")["items"].array.sorted { $0["name"].string.localizedStandardCompare($1["name"].string) == .orderedAscending } } catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } } }
}
