import SwiftUI

struct BackupsView: View {
    let api: PanelAPI
    let server: Server
    @State private var backups: [JSON] = []
    @State private var loaded = false
    @State private var error: String?
    @State private var operation: Operation?
    @State private var showTransfers = false
    @State private var share: ShareItem?
    @State private var downloading = false
    @State private var exportBackup: DetailItem?
    @State private var notice: String?
    var prefix: String { "servers/\(server.id)/backups" }
    var body: some View {
        List {
            Section {
                HStack {
                    Label("Backups", systemImage: "externaldrive.badge.timemachine").font(.headline)
                    Spacer(); Text("\(backups.count) / \(server.data["feature_limits"]["backups"].string)").monospacedDigit().foregroundStyle(.secondary)
                }
                Button("Backup erstellen", systemImage: "plus.circle.fill") { create() }.requiresAccess(api, server.id, "backup.create")
                if downloading { ProgressView("Download-Link anfordern …") }
                TransferLink()
            } footer: { Text("Backups werden vom Panel und deinem Provider verwaltet. Ein erfolgreicher Abschluss wird hier angezeigt.") }
            if !loaded { ProgressView("Backups laden …") }
            if loaded && backups.isEmpty { ContentUnavailableView("Noch keine Backups", systemImage: "externaldrive", description: Text("Erstelle ein Backup, bevor du größere Änderungen vornimmst.")) }
            ForEach(Array(backups.enumerated()), id: \.offset) { _, backup in
                Section {
                    VStack(alignment: .leading, spacing: 12) {
                        HStack(alignment: .top) {
                            Image(systemName: backup["is_locked"].bool ? "lock.shield.fill" : "externaldrive.fill").font(.title2).foregroundStyle(.teal)
                            VStack(alignment: .leading, spacing: 5) { Text(backup["name"].string).font(.headline); Text(backup["created_at"].string.prefix(16).replacingOccurrences(of: "T", with: " ")).font(.caption).foregroundStyle(.secondary) }
                            Spacer()
                            Menu { actions(backup) } label: { Image(systemName: "ellipsis.circle").font(.title3).padding(4) }
                        }
                        HStack {
                            Label(!BackupInfo(value: backup).completed ? "Wird erstellt …" : BackupInfo(value: backup).successful ? "Erfolgreich" : "Fehlgeschlagen", systemImage: BackupInfo(value: backup).successful ? "checkmark.circle.fill" : !BackupInfo(value: backup).completed ? "clock" : "exclamationmark.circle").foregroundStyle(BackupInfo(value: backup).successful ? Color.green : Color.orange)
                            Spacer(); Text(bytes(backup["bytes"].double)).foregroundStyle(.secondary)
                        }.font(.caption)
                    }.padding(.vertical, 6)
                }
            }
        }.panelScreen().refreshable { await load() }
            .task {
                await load()
                while !Task.isCancelled { do { try await Task.sleep(for: .seconds(10)) } catch { break }; await load(silent: true) }
            }
            .sheet(item: $operation) { OperationSheet(api: api, operation: $0) { await load() } }
            .sheet(item: $share) { ShareSheet(url: $0.url) }
            .sheet(isPresented: $showTransfers) { TransfersView() }.errorAlert($error)
            .alert("Backup-Export", isPresented: Binding(get: { notice != nil }, set: { if !$0 { notice = nil } })) { Button("OK") { notice = nil } } message: { Text(notice ?? "") }
            .confirmationDialog("Backup beim Provider exportieren?", isPresented: Binding(get: { exportBackup != nil }, set: { if !$0 { exportBackup = nil } }), titleVisibility: .visible) {
                Button("Export vorbereiten") { if let exportBackup { download(exportBackup.data, exporting: true) }; exportBackup = nil }
            } message: { Text("Der Provider bereitet eine exportierbare Datei vor. Dauer, Zielordner und Verfügbarkeit richten sich nach seinem Backup-Speicher.") }
    }
    @ViewBuilder func actions(_ b: JSON) -> some View {
        let info = BackupInfo(value: b)
        let path = prefix + "/" + info.id
        if info.successful {
            Button("Herunterladen", systemImage: "arrow.down.circle") { download(b) }.disabled(downloading || !info.downloadable || info.id.isEmpty).requiresRequest(api, path + "/download", method: "GET")
            Button("Wiederherstellen", systemImage: "arrow.counterclockwise", role: .destructive) {
                operation = Operation(title: "Backup wiederherstellen", detail: "Stoppe den Server vorher. Dateien aus diesem Backup überschreiben vorhandene Dateien. Ein aktuelles Backup ist empfohlen. Mit 'Vorhandene Dateien vorher löschen' werden auch Dateien entfernt, die im Backup fehlen.", path: path + "/restore", fields: [Field(key: "truncate", label: "Vorhandene Dateien vorher löschen", value: "false", kind: .toggle)], destructive: true, submit: "Backup wiederherstellen")
            }.requiresAccess(api, server.id, "backup.restore")
        }
        if api.isKinetic && info.successful {
            Button("Export vorbereiten", systemImage: "archivebox") { exportBackup = DetailItem(data: b) }.disabled(downloading || info.id.isEmpty).requiresRequest(api, path + "/export", method: "POST")
        }
        Button(b["is_locked"].bool ? "Schutz aufheben" : "Vor Löschen schützen", systemImage: b["is_locked"].bool ? "lock.open" : "lock") { operation = Operation(title: b["is_locked"].bool ? "Backup entsperren" : "Backup schützen", path: path + "/lock", submit: "Bestätigen") }.requiresRequest(api, path + "/lock", method: "POST")
        Button("Löschen", systemImage: "trash", role: .destructive) { operation = Operation(title: "Backup löschen", detail: "\(b["name"].string) wird dauerhaft gelöscht.", path: path, method: "DELETE", destructive: true, submit: "Endgültig löschen") }.disabled(b["is_locked"].bool).requiresRequest(api, path, method: "DELETE")
    }
    func create() { operation = Operation(title: "Backup erstellen", path: prefix, fields: [Field(key: "name", label: "Name", value: "Backup " + Date().formatted(date: .numeric, time: .shortened)), Field(key: "is_locked", label: "Vor Löschen schützen", value: "false", kind: .toggle), Field(key: "ignored", label: "Ausgeschlossene Dateien", kind: .multiline, hint: "Optional. Ein Muster pro Zeile, gemäß den Backup-Regeln deines Panels.")], submit: "Erstellen") }
    func load(silent: Bool = false) async { do { backups = try await api.list(prefix); loaded = true } catch is CancellationError {} catch { if !silent { self.error = error.localizedDescription } } }
    func download(_ backup: JSON, exporting: Bool = false) {
        downloading = true
        Task { defer { downloading = false }; do {
            guard !api.demo else { throw PanelError(message: "Downloads sind im Demomodus deaktiviert.") }
            let info = BackupInfo(value: backup)
            guard !info.id.isEmpty, !info.id.contains("/") else { throw PanelError(message: "Das Backup hat keine gültige API-ID.") }
            let response = try await api.request(prefix + "/\(info.id)/" + (exporting ? "export" : "download"), method: exporting ? "POST" : "GET")
            let signed: String
            do { signed = try BackupInfo.downloadURL(response, base: api.base) }
            catch {
                if exporting {
                    notice = response["message"].string.isEmpty ? "Der Export-Auftrag wurde vom Provider angenommen. Es wurde noch kein Download-Link zurückgegeben. Prüfe nach der Vorbereitung den Dateimanager oder das Web-Panel und aktualisiere die Backup-Liste." : response["message"].string
                    await load(silent: true); return
                }
                throw error
            }
            let suffix = URL(string: signed)?.path.lowercased().hasSuffix(".zip") == true ? ".zip" : ".tar.gz"
            try TransferManager.shared.download(signed, filename: info.id + suffix, context: server.name + " · Backup", source: exporting ? nil : DownloadSource(panel: api.base.absoluteString, server: server.id, kind: "backup", resource: info.id))
            showTransfers = true
        } catch { self.error = error.localizedDescription } }
    }
}
