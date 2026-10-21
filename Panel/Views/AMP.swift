import SwiftUI

struct AMPAccountView: View {
    let api: PanelAPI
    @State private var info: JSON = .null
    @State private var error: String?
    var body: some View {
        List {
            Section("CubeCoders AMP") {
                Text(api.base.absoluteString).textSelection(.enabled)
                LabeledContent("Version", value: info["AMPVersion"].string)
                LabeledContent("Modul", value: info["ModuleName"].string)
                Text("Für einen neuen oder abgelaufenen Zugang füge das AMP-Panel erneut hinzu. Bestehende Panels und Serverdaten bleiben erhalten.").font(.footnote).foregroundStyle(.secondary)
                Link("AMP im Browser öffnen", destination: api.base)
                Link("API-Dokumentation", destination: api.base.appendingPathComponent("API"))
            }
        }.panelScreen().navigationTitle("AMP-Zugang").task { do { info = try await api.amp?.call("Core/GetModuleInfo") ?? .null } catch { self.error = error.localizedDescription } }.errorAlert($error)
    }
}
struct AMPManageView: View {
    let api: PanelAPI
    let server: Server
    @EnvironmentObject private var workspace: WorkspaceStore
    @State private var spec: JSON = .null
    @State private var info: JSON = .null
    @State private var tasks: [JSON] = []
    @State private var error: String?
    @State private var pending = ""
    @State private var busy = false
    func supports(_ method: String) -> Bool { spec["Core"][method] != .null }
    var body: some View {
        List {
            Section("Instanz") {
                LabeledContent("Name", value: server.name)
                LabeledContent("Modul", value: server.data["amp"]["Module"].string)
                Toggle("Minecraft-Verwaltung anzeigen", isOn: Binding(get: { workspace.isMinecraft(api, server) }, set: { workspace.setMinecraft($0, api, server) }))
                if api.amp?.controller == true {
                    Button("AMP-Instanz starten", systemImage: "play.circle") { pending = "StartInstance" }
                    Button("AMP-Instanz neustarten", systemImage: "arrow.clockwise.circle") { pending = "RestartInstance" }
                    Button("AMP-Instanz stoppen", systemImage: "stop.circle", role: .destructive) { pending = "StopInstance" }
                    Text("Diese Aktionen steuern den AMP-Instanzdienst. Das Power-Menü steuert den eigentlichen Spielserver.").font(.caption).foregroundStyle(.secondary)
                }
                if busy { ProgressView() }
            }
            Section("Anwendung") {
                NavigationLink { AMPSettingsView(api: api, server: server) } label: { Label("AMP- & Server-Einstellungen", systemImage: "slider.horizontal.3") }
                if supports("UpdateApplication") { Button("Server-Software aktualisieren", systemImage: "arrow.down.circle") { pending = "UpdateApplication" } }
                Link("AMP-Webpanel öffnen", destination: api.base)
                Text("Funktionen richten sich nach dem AMP-Modul und deinen Account-Rechten. Minecraft-Version, Java, Loader und Startparameter findest du in den AMP-Einstellungen, soweit dein Modul sie freigibt.").font(.footnote).foregroundStyle(.secondary)
            }
            Section("Aufgaben") {
                if tasks.isEmpty { Text("Keine laufenden Aufgaben").foregroundStyle(.secondary) }
                ForEach(Array(tasks.enumerated()), id: \.offset) { _, task in
                    VStack(alignment: .leading, spacing: 6) {
                        Text(task["Name"].string).font(.headline)
                        Text(task["Description"].string).font(.caption)
                        if task["IsIndeterminate"].bool { ProgressView() } else { ProgressView(value: min(100, max(0, task["ProgressPercent"].double)), total: 100) }
                        Text(task["StateMessage"].string).font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
            Section("Informationen") { NativeValues(value: info) }
        }.panelScreen().navigationTitle("AMP verwalten").disabled(busy).task { await load() }.refreshable { await load() }.errorAlert($error)
            .confirmationDialog("Aktion für \(server.name) ausführen?", isPresented: Binding(get: { !pending.isEmpty }, set: { if !$0 { pending = "" } }), titleVisibility: .visible) {
                Button("Ausführen", role: .destructive) { let action = pending; pending = ""; Task { await execute(action) } }
            } message: { Text("Bei Stop oder Neustart werden Verbindungen unterbrochen. Software-Updates vorher sichern und den Spielserver stoppen.") }
    }
    func load() async {
        guard let amp = api.amp else { return }
        do { spec = try await amp.spec(server: server.id); info = try await amp.call("Core/GetModuleInfo", server: server.id); if supports("GetTasks") { tasks = try await amp.call("Core/GetTasks", server: server.id).array } }
        catch { self.error = error.localizedDescription }
    }
    func execute(_ action: String) async {
        guard let amp = api.amp else { return }; busy = true; defer { busy = false }
        do {
            if action.hasSuffix("Instance") { try await amp.service(action, server: server.id) }
            else {
                let state = try await api.request("servers/\(server.id)/resources")["current_state"].string
                guard state == "offline" else { throw PanelError(message: "Stoppe zuerst den Spielserver.") }
                _ = try await amp.call("Core/" + action, server: server.id)
            }
            await load()
        } catch { self.error = error.localizedDescription }
    }
}
struct AMPSettingsView: View {
    let api: PanelAPI
    let server: Server
    @State private var rows: [JSON] = []
    @State private var query = ""
    @State private var error: String?
    @State private var selection: DetailItem?
    @State private var loaded = false
    var filtered: [JSON] { rows.filter { query.isEmpty || ($0["Name"].string + " " + $0["Node"].string + " " + $0["Category"].string).localizedCaseInsensitiveContains(query) } }
    var body: some View {
        List {
            Section { Text("Einstellungen werden direkt aus deiner AMP-Version geladen. Änderungen können einen Serverneustart erfordern.").font(.footnote).foregroundStyle(.secondary) }
            if !loaded { ProgressView("AMP-Einstellungen laden …") }
            ForEach(filtered, id: \.["Node"].string) { row in
                Button { selection = DetailItem(data: row) } label: {
                    VStack(alignment: .leading, spacing: 5) {
                        Text(row["Name"].string.isEmpty ? row["Node"].string : row["Name"].string).foregroundStyle(.primary)
                        Text(row["Category"].string + " · " + row["Subcategory"].string).font(.caption).foregroundStyle(.secondary)
                        if !isSecret(row) { Text(row["CurrentValue"].string).font(.caption.monospaced()).foregroundStyle(.secondary).lineLimit(2) }
                        if row["ReadOnly"].bool { Label("Schreibgeschützt", systemImage: "lock").font(.caption) }
                    }
                }
            }
        }.panelScreen().navigationTitle("AMP-Einstellungen").searchable(text: $query, prompt: "Version, Java, Minecraft …").task { await load() }.refreshable { await load() }.errorAlert($error)
            .sheet(item: $selection) { item in
                let row = item.data
                ActionForm(title: row["Name"].string, detail: row["Description"].string + (row["RequiresRestart"].bool ? "\nServerneustart erforderlich." : ""), fields: [Field(key: "value", label: "Wert", value: row["CurrentValue"].string, kind: isSecret(row) ? .secret : row["InputType"].string.lowercased() == "checkbox" || row["ValType"].string == "Boolean" ? .toggle : .text)], submit: "Speichern") { values in
                    guard !row["ReadOnly"].bool else { throw PanelError(message: "Diese Einstellung ist schreibgeschützt.") }
                    _ = try await api.amp?.call("Core/SetConfig", server: server.id, values: ["node": row["Node"], "value": .string(values["value"]?.string ?? "")]); await load()
                }
            }
    }
    func isSecret(_ row: JSON) -> Bool { row["InputType"].string.lowercased().contains("password") }
    func load() async {
        do {
            let result = try await api.amp?.call("Core/GetSettingsSpec", server: server.id) ?? .null
            rows = result.object.values.flatMap { $0.array }.filter { !$0["Node"].string.isEmpty }.sorted { $0["Node"].string < $1["Node"].string }
            loaded = true
        } catch { self.error = error.localizedDescription }
    }
}
struct AMPBackupsView: View {
    let api: PanelAPI
    let server: Server
    @State private var rows: [JSON] = []
    @State private var spec: JSON = .null
    @State private var error: String?
    @State private var action: AMPBackupAction?
    @State private var loaded = false
    func supports(_ method: String) -> Bool { spec["LocalFileBackupPlugin"][method] != .null }
    var body: some View {
        List {
            Section {
                if supports("TakeBackup") { Button("Backup erstellen", systemImage: "plus.circle") { action = AMPBackupAction(method: "TakeBackup", title: "Backup erstellen", fields: [Field(key: "Title", label: "Name", value: "Backup " + Date().formatted()), Field(key: "Description", label: "Beschreibung", kind: .multiline), Field(key: "Sticky", label: "Vor automatischer Bereinigung schützen", value: "false", kind: .toggle)], values: ["Local": .bool(true), "S3": .bool(false), "WasCreatedAutomatically": .bool(false)]) } }
                Text("AMP verwaltet Backups über das Backup-Modul. Während der Erstellung kannst du den Fortschritt unter Verwalten → AMP → Aufgaben prüfen. Zum Wiederherstellen zuerst den Spielserver stoppen.").font(.footnote).foregroundStyle(.secondary)
            }
            if !loaded { ProgressView("Backups laden …") }
            if loaded && !supports("GetBackups") { Text("Für dieses AMP-Modul ist keine Backup-API verfügbar. Prüfe die Backup-Einrichtung im Webpanel.").foregroundStyle(.secondary) }
            else if loaded && rows.isEmpty { Text("Keine verfügbaren Backups").foregroundStyle(.secondary) }
            ForEach(rows, id: \.["Id"].string) { row in
                Section {
                    Text(row["Name"].string).font(.headline)
                    Text(row["Timestamp"].string).font(.caption).foregroundStyle(.secondary)
                    LabeledContent("Größe", value: bytes(row["TotalSizeBytes"].double))
                    Text(row["Description"].string).font(.caption)
                    if supports("RestoreBackup") { Button("Wiederherstellen", systemImage: "arrow.counterclockwise", role: .destructive) { action = AMPBackupAction(method: "RestoreBackup", title: "Backup wiederherstellen", fields: [], values: ["BackupId": row["Id"], "DeleteExistingData": .bool(false)], destructive: true) } }
                    if supports("SetBackupSticky") { Button(row["Sticky"].bool ? "Bereinigungsschutz aufheben" : "Vor Bereinigung schützen", systemImage: "lock") { action = AMPBackupAction(method: "SetBackupSticky", title: "Backup-Schutz ändern", fields: [], values: ["BackupId": row["Id"], "Sticky": .bool(!row["Sticky"].bool)]) } }
                    if row["StoredLocally"].bool && supports("DeleteLocalBackup") { Button("Lokales Backup löschen", systemImage: "trash", role: .destructive) { action = AMPBackupAction(method: "DeleteLocalBackup", title: "Lokales Backup löschen", fields: [], values: ["BackupId": row["Id"]], destructive: true) }.disabled(row["Sticky"].bool) }
                }
            }
        }.panelScreen().task { await load(); while !Task.isCancelled { do { try await Task.sleep(for: .seconds(15)) } catch { return }; await load() } }.refreshable { await load() }.errorAlert($error)
            .sheet(item: $action) { item in
                ActionForm(title: item.title, detail: item.destructive ? "Die Aktion verändert vorhandene Daten. Wiederherstellung überschreibt Dateien aus dem Backup; zusätzliche Dateien bleiben erhalten." : "Auftrag für " + server.name, fields: item.fields, destructive: item.destructive, submit: "Bestätigen") { values in
                    if item.method == "RestoreBackup" {
                        let state = try await api.request("servers/\(server.id)/resources")["current_state"].string
                        guard state == "offline" else { throw PanelError(message: "Stoppe zuerst den Spielserver.") }
                    }
                    _ = try await api.amp?.call("LocalFileBackupPlugin/" + item.method, server: server.id, values: item.values.merging(values, uniquingKeysWith: { _, new in new })); await load()
                }
            }
    }
    func load() async { do { spec = try await api.amp?.spec(server: server.id) ?? .null; if supports("GetBackups") { rows = try await api.amp?.call("LocalFileBackupPlugin/GetBackups", server: server.id).array ?? [] }; loaded = true } catch { if !loaded { self.error = error.localizedDescription } } }
}
struct AMPBackupAction: Identifiable {
    let id = UUID()
    let method: String
    let title: String
    let fields: [Field]
    let values: [String: JSON]
    var destructive = false
}
