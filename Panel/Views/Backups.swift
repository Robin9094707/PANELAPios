import SwiftUI

struct BackupsView: View {
    let api: PanelAPI
    let server: Server
    @State private var backups: [JSON] = []
    @State private var loaded = false
    @State private var error: String?
    @State private var operation: Operation?
    @State private var share: ShareItem?
    @State private var downloading = false
    var prefix: String { "servers/\(server.id)/backups" }
    var body: some View {
        List {
            Section {
                HStack {
                    Label("Backups", systemImage: "externaldrive.badge.timemachine").font(.headline)
                    Spacer(); Text("\(backups.count) / \(server.data["feature_limits"]["backups"].string)").monospacedDigit().foregroundStyle(.secondary)
                }
                Button("Backup erstellen", systemImage: "plus.circle.fill") { create() }
                if downloading { ProgressView("Backup herunterladen …") }
            } footer: { Text("Backups werden vom Panel und deinem Provider verwaltet. Ein erfolgreicher Abschluss wird hier angezeigt.") }
            if !loaded { ProgressView("Backups laden …") }
            if loaded && backups.isEmpty { ContentUnavailableView("Noch keine Backups", systemImage: "externaldrive", description: Text("Erstelle ein Backup, bevor du größere Änderungen vornimmst.")) }
            ForEach(backups, id: \.["uuid"].string) { backup in
                Section {
                    VStack(alignment: .leading, spacing: 12) {
                        HStack(alignment: .top) {
                            Image(systemName: backup["is_locked"].bool ? "lock.shield.fill" : "externaldrive.fill").font(.title2).foregroundStyle(.teal)
                            VStack(alignment: .leading, spacing: 5) { Text(backup["name"].string).font(.headline); Text(backup["created_at"].string.prefix(16).replacingOccurrences(of: "T", with: " ")).font(.caption).foregroundStyle(.secondary) }
                            Spacer()
                            Menu { actions(backup) } label: { Image(systemName: "ellipsis.circle").font(.title3).padding(4) }
                        }
                        HStack {
                            Label(backup["completed_at"] == .null ? "Wird erstellt …" : backup["is_successful"].bool ? "Erfolgreich" : "Fehlgeschlagen", systemImage: backup["is_successful"].bool ? "checkmark.circle.fill" : backup["completed_at"] == .null ? "clock" : "exclamationmark.circle").foregroundStyle(backup["is_successful"].bool ? Color.green : Color.orange)
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
            .sheet(item: $share) { ShareSheet(url: $0.url) }.errorAlert($error)
    }
    @ViewBuilder func actions(_ b: JSON) -> some View {
        let path = prefix + "/" + b["uuid"].string
        if b["is_successful"].bool {
            Button("Herunterladen", systemImage: "arrow.down.circle") { download(b) }.disabled(downloading)
            Button("Wiederherstellen", systemImage: "arrow.counterclockwise", role: .destructive) {
                operation = Operation(title: "Backup wiederherstellen", detail: "Stoppe den Server vorher. Dateien aus diesem Backup überschreiben vorhandene Dateien. Ein aktuelles Backup ist empfohlen. Mit 'Vorhandene Dateien vorher löschen' werden auch Dateien entfernt, die im Backup fehlen.", path: path + "/restore", fields: [Field(key: "truncate", label: "Vorhandene Dateien vorher löschen", value: "false", kind: .toggle)], destructive: true, submit: "Backup wiederherstellen")
            }
        }
        Button(b["is_locked"].bool ? "Schutz aufheben" : "Vor Löschen schützen", systemImage: b["is_locked"].bool ? "lock.open" : "lock") { operation = Operation(title: b["is_locked"].bool ? "Backup entsperren" : "Backup schützen", path: path + "/lock", submit: "Bestätigen") }
        Button("Löschen", systemImage: "trash", role: .destructive) { operation = Operation(title: "Backup löschen", detail: "\(b["name"].string) wird dauerhaft gelöscht.", path: path, method: "DELETE", destructive: true, submit: "Endgültig löschen") }.disabled(b["is_locked"].bool)
    }
    func create() { operation = Operation(title: "Backup erstellen", path: prefix, fields: [Field(key: "name", label: "Name", value: "Backup " + Date().formatted(date: .numeric, time: .shortened)), Field(key: "is_locked", label: "Vor Löschen schützen", value: "false", kind: .toggle), Field(key: "ignored", label: "Ausgeschlossene Dateien", kind: .multiline, hint: "Optional. Ein Muster pro Zeile, gemäß den Backup-Regeln deines Panels.")], submit: "Erstellen") }
    func load(silent: Bool = false) async { do { backups = try await api.list(prefix); loaded = true } catch is CancellationError {} catch { if !silent { self.error = error.localizedDescription } } }
    func download(_ backup: JSON) {
        downloading = true
        Task { defer { downloading = false }; do {
            guard !api.demo else { throw PanelError(message: "Downloads sind im Demomodus deaktiviert.") }
            let response = try await api.request(prefix + "/\(backup["uuid"].string)/download")
            share = ShareItem(url: try await api.download(response.attributes["url"].string, filename: backup["uuid"].string + ".tar.gz"))
        } catch { self.error = error.localizedDescription } }
    }
}
