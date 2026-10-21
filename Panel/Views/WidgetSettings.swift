import SwiftUI
import WidgetKit

struct WidgetSettingsView: View {
    @EnvironmentObject private var store: AppStore
    @State private var records = WidgetStorage.servers()
    @State private var error: String?
    @State private var pending: Server?
    @State private var background = true
    @State private var busy = false
    @State private var report: String?
    var body: some View {
        List {
            Section("Widgets verbinden") {
                Text("Server auswählen → Widget-Zugang speichern → Home-Bildschirm gedrückt halten → Widget hinzufügen → Simplex Panel. Beim konfigurierbaren Widget anschließend den Server wählen.").font(.footnote)
                Text("„Alle Instanzen · direkt“ zeigt die freigegebenen Server ohne weitere Auswahl. Für Namen: „Online-Spieler · große Liste“. Status, RAM, CPU und Uptime gibt es zusätzlich einzeln.").font(.footnote).foregroundStyle(.secondary)
                Text(WidgetStorage.connectionTitle).font(.caption).foregroundStyle(WidgetStorage.folder == nil ? .orange : .secondary).textSelection(.enabled)
                Text("iOS bestimmt die automatische Aktualisierung. Der Knopf im Widget fordert neue Daten an; Widgets sind keine sekündliche Live-Anzeige.").font(.caption).foregroundStyle(.secondary)
                Button("Widget-Daten jetzt aktualisieren", systemImage: "arrow.clockwise") { Task { await refresh() } }.disabled(busy)
                if let report { Text(report).font(.caption).foregroundStyle(.secondary) }
                if busy { ProgressView() }
            }
            Section("Freigegebene Server · \(records.count)") {
                ForEach(records) { record in
                    VStack(alignment: .leading, spacing: 8) {
                        HStack { Text(record.name).font(.headline); Spacer(); Button("Entfernen", systemImage: "trash", role: .destructive) { remove(record) }.labelStyle(.iconOnly).buttonStyle(.borderless) }
                        Text(record.backgroundRefresh ? "Direkte API-Aktualisierung freigegeben" : "Daten aus der App").font(.caption).foregroundStyle(.secondary)
                        Text(record.panel).font(.caption2).foregroundStyle(.secondary)
                        Button("Daten-Code kopieren", systemImage: "doc.on.doc") {
                            do { var copy = record; copy.backgroundRefresh = false; copy.suiteEndpoint = nil; copy.suitePin = nil; copy.suiteIdentity = nil; copy.playerNames = Array((copy.playerNames ?? []).prefix(2000)); Clipboard.copy(try JSONEncoder().encode(copy).base64EncodedString()); report = "Daten-Code kopiert. Im Widget „Manueller Datenstand“ einfügen. Das ist eine feste Kopie ohne Zugangsdaten und ohne Live-Aktualisierung." } catch { self.error = error.localizedDescription }
                        }.font(.caption)
                    }.padding(.vertical, 4)
                }
            }
            if let api = store.api {
                Section("Aktuelles Panel") {
                    Button("Alle Instanzen für Widgets freigeben", systemImage: "square.grid.2x2") { Task { await addAll() } }.disabled(busy || api.demo)
                    ForEach(store.servers) { server in Button(server.name, systemImage: "plus.circle") { background = true; pending = server }.disabled(busy || api.demo) }
                }
            }
            if WidgetStorage.folder == nil { Section("Falls die Signatur App-Gruppen entfernt") { Text("Die App versucht zusätzlich den gemeinsamen Schlüsselbund. Ist auch dieser nicht freigegeben, kann iOS keine automatische App-Verbindung erlauben. Dann App und Erweiterung mit derselben App-Gruppe signieren oder den Daten-Code als manuelle Kopie verwenden. Eine Neuinstallation der App ist nicht nötig.").font(.footnote).foregroundStyle(.secondary) } }
        }.panelScreen().navigationTitle("Server-Widgets").navigationBarTitleDisplayMode(.inline).errorAlert($error).refreshable { records = WidgetStorage.servers() }
            .sheet(item: $pending) { server in
                NavigationStack {
                    Form {
                        Section { Text(server.name).font(.headline); Toggle("Direkt im Widget aktualisieren", isOn: $background) } footer: { Text("Speichert den Panel-Zugang zusätzlich im geschützten gemeinsamen Widget-Speicher. Bei gekoppeltem Simplex-Server wird auch dessen Zugang für die Online-Spielerliste freigegeben. Widgets können freigegebene Daten bei gesperrtem Gerät anzeigen.") }
                        Button("Widget-Zugang speichern") { Task { do { try await add(server, background: background); pending = nil } catch { self.error = error.localizedDescription } } }.disabled(busy)
                    }.panelScreen().navigationTitle("Widget einrichten").toolbar { ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { pending = nil } } }
                }.presentationDetents([.medium, .large])
            }
    }
    func add(_ server: Server, background: Bool) async throws {
        guard let api = store.api else { return }
        var record = WidgetStorage.servers().first(where: { $0.panel == api.base.absoluteString && $0.serverID == server.id }) ?? WidgetServer(panel: api.base.absoluteString, serverID: server.id, name: server.name)
        record.backgroundRefresh = background; record.provider = api.provider.rawValue; record.controller = api.amp?.controller
        record.memoryLimit = server.data["limits"]["memory"].double > 0 ? server.data["limits"]["memory"].double * 1_048_576 : nil
        if background {
            let original = api.amp?.widgetCredential ?? api.widgetSecret
            var credential = WidgetCredential(provider: api.provider.rawValue, secret: original)
            if let connection = SuiteConnections.shared.get(api, server) {
                credential.suiteToken = try Vault.read(connection.id); record.suiteEndpoint = try SuiteAPI.endpoint(connection.endpoint).absoluteString; record.suitePin = connection.fingerprint; record.suiteIdentity = connection.serverIdentity
            }
            let encoded = String(decoding: try JSONEncoder().encode(credential), as: UTF8.self)
            do { try WidgetVault.save(encoded, panel: record.id) }
            catch { record.backgroundRefresh = false; report = "Gemeinsamer Zugriff beim Signieren nicht freigegeben. Datenstand wurde lokal gespeichert; Daten-Code oder passende App-Gruppen-Signatur verwenden." }
        }
        if let data = try? await api.request("servers/\(server.id)/resources").attributes {
            record.state = data["current_state"].string; let r = data["resources"]
            record.cpu = AMPMetrics.number(r["cpu_absolute"]); record.memory = AMPMetrics.number(r["memory_bytes"]); record.disk = AMPMetrics.number(r["disk_bytes"]); record.uptime = AMPMetrics.number(r["uptime"]).map { $0 / 1000 }; record.memoryLimit = AMPMetrics.number(r["memory_limit_bytes"]) ?? record.memoryLimit; record.updated = Date()
        }
        if record.backgroundRefresh { record = await WidgetNetwork.refresh(record) }
        try WidgetStorage.upsert(record); records = WidgetStorage.servers(); WidgetCenter.shared.reloadAllTimelines()
    }
    func addAll() async {
        busy = true; defer { busy = false }
        for server in store.servers { do { try await add(server, background: true) } catch { self.error = error.localizedDescription; break } }
    }
    func refresh() async {
        busy = true; defer { busy = false }
        for record in records where record.backgroundRefresh { let value = await WidgetNetwork.refresh(record); if let issue = value.issue { report = issue } }
        records = WidgetStorage.servers(); WidgetCenter.shared.reloadAllTimelines()
    }
    func remove(_ record: WidgetServer) {
        do { try WidgetStorage.save(WidgetStorage.servers().filter { $0.id != record.id }); records = WidgetStorage.servers(); WidgetVault.delete(record.id); WidgetCenter.shared.reloadAllTimelines() } catch { self.error = error.localizedDescription }
    }
}
