import SwiftUI
import WidgetKit

struct WidgetSettingsView: View {
    @EnvironmentObject private var store: AppStore
    @State private var records = WidgetStorage.servers()
    @State private var error: String?
    @State private var pending: Server?
    @State private var background = false
    var body: some View {
        List {
            Section {
                Label("Deine Server auf dem Home-Bildschirm", systemImage: "widget.small").font(.headline)
                Text("Wähle Server für die Widgets. Halte dann den Home-Bildschirm gedrückt, füge ein Panel-Widget hinzu und wähle unter ‚Widget bearbeiten‘ den gewünschten Server. Kleine, mittlere und Sperrbildschirm-Widgets werden unterstützt.").font(.footnote).foregroundStyle(.secondary)
                Text("Messwerte tragen ihren Zeitstempel. iOS bestimmt das Aktualisierungsintervall. Ohne Hintergrundzugang werden die zuletzt in der App empfangenen Werte angezeigt. Spielerzahlen im Hintergrund benötigen einen passenden Provider-Endpunkt; Konsolenbefehle werden vom Widget nicht gesendet.").font(.footnote).foregroundStyle(.secondary)
            }
            if WidgetStorage.folder == nil { Section { Text("Die aktuelle App-Signatur enthält keine nutzbare App Group. Signiere App und Widget-Erweiterung mit group.eu.simplexsmp.panel und der gemeinsamen Keychain-Gruppe.").foregroundStyle(.orange) } }
            Section("Widget-Server") {
                ForEach(records) { record in
                    VStack(alignment: .leading, spacing: 8) {
                        HStack { Text(record.name).font(.headline); Spacer(); Button("Entfernen", systemImage: "trash", role: .destructive) { remove(record) }.labelStyle(.iconOnly).buttonStyle(.borderless) }
                        Text(record.backgroundRefresh ? "Hintergrundabfragen erlaubt" : "Letzte Messwerte aus der App").font(.caption).foregroundStyle(.secondary)
                        Text(record.panel).font(.caption2).foregroundStyle(.secondary)
                    }
                }
            }
            if let api = store.api {
                Section("Server hinzufügen / aktualisieren") {
                    ForEach(store.servers) { server in Button(server.name, systemImage: "plus.circle") { background = false; pending = server }.disabled(api.demo || WidgetStorage.folder == nil) }
                }
            }
        }.panelScreen().navigationTitle("Server-Widgets").navigationBarTitleDisplayMode(.inline).errorAlert($error)
            .sheet(item: $pending) { server in
                NavigationStack {
                    Form {
                        Section { Text(server.name).font(.headline); Toggle("Im Hintergrund per API aktualisieren", isOn: $background) } footer: { Text("Dafür wird dein Client-Schlüssel zusätzlich im gemeinsamen iOS-Schlüsselbund für die Widget-Erweiterung gespeichert. Widgets können Serverdaten auch bei gesperrtem Gerät anzeigen. Die App-Sperre sperrt diese ausdrücklich freigegebenen Widget-Daten nicht.") }
                        Button("Widget-Server speichern") { Task { await add(server) } }
                    }.panelScreen().navigationTitle("Widget einrichten").toolbar { ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { pending = nil } } }
                }.presentationDetents([.medium, .large])
            }
    }
    func add(_ server: Server) async {
        guard let api = store.api else { return }
        do {
            if background { try api.shareWidgetCredential() }
            var record = records.first(where: { $0.panel == api.base.absoluteString && $0.serverID == server.id }) ?? WidgetServer(panel: api.base.absoluteString, serverID: server.id, name: server.name)
            record.backgroundRefresh = background
            if let data = try? await api.request("servers/\(server.id)/resources").attributes {
                record.state = data["current_state"].string; record.cpu = data["resources"]["cpu_absolute"].double
                record.memory = data["resources"]["memory_bytes"].double; record.disk = data["resources"]["disk_bytes"].double; record.updated = Date()
            }
            record.memoryLimit = server.data["limits"]["memory"].double * 1_048_576
            if api.isKinetic, let data = try? await api.request("servers/\(server.id)/players"), let players = PlayerSnapshot.decode(data) { record.online = players.online; record.maximum = players.maximum; record.playersUpdated = players.updated }
            try WidgetStorage.upsert(record); records = WidgetStorage.servers()
            if !records.contains(where: { $0.panel == api.base.absoluteString && $0.backgroundRefresh }) { WidgetVault.delete(api.base.absoluteString) }
            WidgetCenter.shared.reloadAllTimelines(); pending = nil
        } catch { pending = nil; self.error = error.localizedDescription }
    }
    func remove(_ record: WidgetServer) {
        do { try WidgetStorage.save(records.filter { $0.id != record.id }); records = WidgetStorage.servers(); if !records.contains(where: { $0.panel == record.panel && $0.backgroundRefresh }) { WidgetVault.delete(record.panel) }; WidgetCenter.shared.reloadAllTimelines() } catch { self.error = error.localizedDescription }
    }
}
