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
    @State private var automatic: [SystemBackup] = []
    @State private var automaticLoaded = false
    @State private var automaticIssue: String?
    var prefix: String { "servers/\(server.id)/backups" }
    var body: some View {
        List {
            if api.isUltraServers || api.demo { automaticSection }
            Section {
                HStack {
                    Label(api.isUltraServers || api.demo ? "Manuelle Backups" : "Backups", systemImage: "externaldrive.badge.timemachine").font(.headline)
                    Spacer(); Text("\(backups.count) / \(server.data["feature_limits"]["backups"].string)").monospacedDigit().foregroundStyle(.secondary)
                }
                Button("Backup erstellen", systemImage: "plus.circle.fill") { create() }.requiresAccess(api, server.id, "backup.create")
                if downloading { ProgressView("Download-Link anfordern …") }
                TransferLink()
            } footer: { Text(api.isUltraServers || api.demo ? "Manuelle Backups bleiben, bis du sie löschst – ideal vor Updates oder wenn ein Stand länger als die 2 Wochen der automatischen Backups halten soll." : "Backups werden vom Panel und deinem Provider verwaltet. Ein erfolgreicher Abschluss wird hier angezeigt.") }
            if !loaded { ProgressView("Backups laden …") }
            if loaded && backups.isEmpty { ContentUnavailableView("Noch keine Backups", systemImage: "externaldrive", description: Text("Erstelle ein Backup, bevor du größere Änderungen vornimmst.")) }
            ForEach(Array(backups.enumerated()), id: \.offset) { _, backup in
                Section {
                    VStack(alignment: .leading, spacing: 12) {
                        HStack(alignment: .top) {
                            Image(systemName: backup["is_locked"].bool ? "lock.shield.fill" : "externaldrive.fill").font(.title2).foregroundStyle(Color.panelAccent)
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
        }.panelScreen().refreshable { await load(); await loadAutomatic() }
            .task {
                await load(); await loadAutomatic()
                while !Task.isCancelled { do { try await Task.sleep(for: .seconds(10)) } catch { break }; await load(silent: true) }
            }
            .sheet(item: $operation) { OperationSheet(api: api, operation: $0) { await load() } }
            .sheet(item: $share) { ShareSheet(url: $0.url) }
            .sheet(isPresented: $showTransfers) { TransfersView() }.errorAlert($error)
    }
    /// UltraServers: automatic backups every 6 hours, kept about 2 weeks (daily for older days).
    @ViewBuilder var automaticSection: some View {
        let summary = SystemBackupSummary(automatic)
        Section {
            NavigationLink { SystemBackupsView(api: api, server: server) } label: {
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Label("Automatische Backups", systemImage: "clock.arrow.2.circlepath").font(.headline)
                        Spacer()
                        if automaticLoaded { Text("\(summary.count)").monospacedDigit().foregroundStyle(.secondary) } else { ProgressView() }
                    }
                    if let newest = summary.newest {
                        Text("Letztes: \(newest.formatted(date: .abbreviated, time: .shortened)) · \(newest, style: .relative)").font(.caption).foregroundStyle(summary.overdue ? Color.orange : .secondary)
                    }
                    if let next = summary.next { Text("Nächstes etwa \(next.formatted(date: .omitted, time: .shortened)) Uhr").font(.caption).foregroundStyle(.secondary) }
                    if let automaticIssue { Text(automaticIssue).font(.caption).foregroundStyle(.orange) }
                }.padding(.vertical, 4)
            }.accessibilityIdentifier("automaticBackups")
        } footer: { Text("UltraServers sichert alle 6 Stunden automatisch. Heute alle 6 Stunden, ältere Tage etwa täglich, rund 2 Wochen zurück.") }
    }
    func loadAutomatic() async {
        do { automatic = SystemBackup.decode(try await api.request("servers/\(server.id)/system-backups")); automaticIssue = nil }
        catch is CancellationError {} catch { automaticIssue = error.localizedDescription }
        automaticLoaded = true
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
        Button(b["is_locked"].bool ? "Schutz aufheben" : "Vor Löschen schützen", systemImage: b["is_locked"].bool ? "lock.open" : "lock") { operation = Operation(title: b["is_locked"].bool ? "Backup entsperren" : "Backup schützen", path: path + "/lock", submit: "Bestätigen") }.requiresRequest(api, path + "/lock", method: "POST")
        Button("Löschen", systemImage: "trash", role: .destructive) { operation = Operation(title: "Backup löschen", detail: "\(b["name"].string) wird dauerhaft gelöscht.", path: path, method: "DELETE", destructive: true, submit: "Endgültig löschen") }.disabled(b["is_locked"].bool).requiresRequest(api, path, method: "DELETE")
    }
    func create() { operation = Operation(title: "Backup erstellen", path: prefix, fields: [Field(key: "name", label: "Name", value: "Backup " + Date().formatted(date: .numeric, time: .shortened)), Field(key: "is_locked", label: "Vor Löschen schützen", value: "false", kind: .toggle), Field(key: "ignored", label: "Ausgeschlossene Dateien", kind: .multiline, hint: "Optional. Ein Muster pro Zeile, gemäß den Backup-Regeln deines Panels.")], submit: "Erstellen") }
    func load(silent: Bool = false) async { do { backups = try await api.list(prefix); loaded = true } catch is CancellationError {} catch { if !silent { self.error = error.localizedDescription } } }
    func download(_ backup: JSON) {
        downloading = true
        Task { defer { downloading = false }; do {
            guard !api.demo else { throw PanelError(message: "Downloads sind im Demomodus deaktiviert.") }
            let info = BackupInfo(value: backup)
            guard !info.id.isEmpty, !info.id.contains("/") else { throw PanelError(message: "Das Backup hat keine gültige API-ID.") }
            let response = try await api.request(prefix + "/\(info.id)/download")
            let signed = try BackupInfo.downloadURL(response, base: api.base)
            let suffix = URL(string: signed)?.path.lowercased().hasSuffix(".zip") == true ? ".zip" : ".tar.gz"
            try TransferManager.shared.download(signed, filename: info.id + suffix, context: server.name + " · Backup", source: DownloadSource(panel: api.base.absoluteString, server: server.id, kind: "backup", resource: info.id))
            showTransfers = true
        } catch { self.error = error.localizedDescription } }
    }
}
