import SwiftUI

struct TransfersView: View {
    @ObservedObject private var manager = TransferManager.shared
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var store: AppStore
    @AppStorage("transferActivities") private var activities = true
    @State private var share: ShareItem?
    @State private var error: String?
    @State private var removing: TransferRecord?
    @State private var cancelling: TransferRecord?
    @State private var clearing = false
    var body: some View {
        NavigationStack {
            List {
                Section {
                    Toggle("Live Activities & Dynamic Island", isOn: $activities)
                    Text("Übertragungen laufen nach Möglichkeit im iOS-Hintergrund weiter. Erzwungenes Beenden kann sie stoppen. Download-Fortsetzung hängt vom Dateiserver und der Gültigkeit des Download-Links ab. Uploads bei Bedarf erneut auswählen; der Hoster unterstützt nicht automatisch eine Fortsetzung ab dem letzten Byte.").font(.footnote).foregroundStyle(.secondary)
                }
                if let issue = manager.storageError { Section { Label(issue, systemImage: "exclamationmark.triangle").foregroundStyle(.orange) } }
                if manager.records.isEmpty { ContentUnavailableView("Alles übertragen", systemImage: "arrow.up.arrow.down.circle", description: Text("Neue Uploads und Downloads erscheinen hier mit ihrem echten Fortschritt.")) }
                ForEach(manager.records) { record in
                    Section {
                        VStack(alignment: .leading, spacing: 12) {
                            HStack(alignment: .top, spacing: 12) {
                                Image(systemName: record.upload ? "arrow.up.doc.fill" : "arrow.down.doc.fill").font(.title2).foregroundStyle(Color.panelAccent)
                                VStack(alignment: .leading, spacing: 4) { Text(record.filename).font(.headline).textSelection(.enabled); Text(record.context).font(.caption).foregroundStyle(.secondary) }
                                Spacer()
                                if record.state == "completed" { Image(systemName: "checkmark.circle.fill").foregroundStyle(.green) }
                            }
                            if record.active {
                                if let fraction = record.fraction { ProgressView(value: fraction).tint(Color.panelAccent) }
                                else { ProgressView().frame(maxWidth: .infinity, alignment: .leading) }
                            }
                            HStack { Text(record.title); Spacer(); Text(progress(record)).monospacedDigit() }.font(.caption).foregroundStyle(record.state == "failed" ? Color.orange : .secondary)
                            if let issue = record.issue { Text(issue).font(.footnote).foregroundStyle(.orange).textSelection(.enabled) }
                            HStack {
                                if record.active {
                                    if !record.upload && record.state == "running" { Button("Pause", systemImage: "pause") { manager.pause(record) } }
                                    Button("Abbrechen", systemImage: "xmark", role: .destructive) { cancelling = record }
                                } else {
                                    if !record.upload && record.state != "completed" {
                                        Button("Fortsetzen", systemImage: "play") { resume(record) }
                                        if manager.hasResume(record) { Button("Neu laden", systemImage: "arrow.clockwise") { resume(record, restart: true) } }
                                        if !manager.hasResume(record) { Text("Ohne Byte-Fortsetzung: startet erneut bei 0 %.").font(.caption2).foregroundStyle(.secondary) }
                                    }
                                    if record.state == "completed" && !record.upload { Button("In Dateien sichern / Teilen", systemImage: "square.and.arrow.up") { share = ShareItem(url: manager.localFile(record)) } }
                                    Spacer(); Button("Entfernen", systemImage: "trash", role: .destructive) { removing = record }.labelStyle(.iconOnly)
                                }
                            }.buttonStyle(.borderless)
                            if !record.active && record.state != "completed" { Text(record.upload ? "Wähle die Datei im Zielordner erneut zum Hochladen aus. Prüfe vorher, ob eine unvollständige Datei auf dem Server liegt." : "Falls Fortsetzen scheitert: Starte den Download erneut im Dateimanager. Dabei wird ein neuer Download-Link angefordert.").font(.caption).foregroundStyle(.secondary) }
                        }.padding(.vertical, 6)
                    }
                }
            }.panelScreen().navigationTitle("Übertragungen").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .topBarLeading) { Button("Alle leeren", systemImage: "trash", role: .destructive) { clearing = true }.disabled(manager.records.isEmpty) }; ToolbarItem(placement: .confirmationAction) { Button("Fertig") { dismiss() } } }
                .confirmationDialog("Übertragungen leeren?", isPresented: $clearing, titleVisibility: .visible) {
                    Button("Verlauf leeren – aktive behalten", role: .destructive) { manager.clearAll() }
                    if manager.records.contains(where: \.active) { Button("Alle abbrechen und leeren", role: .destructive) { manager.clearAll(includingActive: true) } }
                } message: { Text("Gespeicherte Downloads auf diesem iPhone werden entfernt. Serverdateien bleiben bestehen. Ein abgebrochener Upload kann auf dem Server unvollständig sein.") }
                .sheet(item: $share) { ShareSheet(url: $0.url) }.errorAlert($error)
                .confirmationDialog("Lokale Übertragung und gespeicherte Datei entfernen?", isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } }), titleVisibility: .visible) { Button("Lokal entfernen", role: .destructive) { if let removing { manager.remove(removing) }; removing = nil } }
                .confirmationDialog("Übertragung abbrechen?", isPresented: Binding(get: { cancelling != nil }, set: { if !$0 { cancelling = nil } }), titleVisibility: .visible) { Button("Abbrechen", role: .destructive) { if let cancelling { manager.cancel(cancelling) }; cancelling = nil } } message: { Text("Ein abgebrochener Upload kann eine unvollständige Datei auf dem Server hinterlassen.") }
        }
    }
    func resume(_ record: TransferRecord, restart: Bool = false) {
        Task { do {
            let source = store.api.flatMap { DownloadSource.legacy(context: record.context, filename: record.filename, api: $0, servers: store.servers) }
            try await manager.resume(record, api: store.api, legacySource: source, restart: restart)
        } catch { self.error = error.localizedDescription } }
    }
    func progress(_ r: TransferRecord) -> String {
        let amount = bytes(Double(r.completed)) + (r.total > 0 ? " / " + bytes(Double(r.total)) : "")
        return amount + (r.fraction.map { " · \(Int($0 * 100)) %" } ?? "")
    }
}

struct TransferLink: View {
    @ObservedObject private var manager = TransferManager.shared
    @State private var show = false
    var body: some View {
        Button { show = true } label: {
            HStack {
                Label("Übertragungen", systemImage: "arrow.up.arrow.down.circle")
                Spacer()
                if let record = manager.records.first(where: \.active) {
                    if let fraction = record.fraction { ProgressView(value: fraction).frame(width: 65) }
                    else { ProgressView() }
                    Text("\(manager.records.filter(\.active).count)").font(.caption).monospacedDigit()
                }
            }
        }.sheet(isPresented: $show) { TransfersView() }
    }
}


struct TransferDashboardCard: View {
    let open: () -> Void
    @ObservedObject private var manager = TransferManager.shared
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label("Übertragungen", systemImage: "arrow.up.arrow.down.circle").font(.headline)
            LabeledContent("Aktiv", value: String(manager.records.filter(\.active).count))
            LabeledContent("Fehlgeschlagen", value: String(manager.records.filter { $0.state == "failed" }.count))
            ForEach(Array(manager.records.prefix(3))) { record in
                HStack { Text(record.filename).font(.caption).lineLimit(1); Spacer(); Text(record.title).font(.caption).foregroundStyle(.secondary) }
            }
            Button("Alle Übertragungen", action: open).buttonStyle(.glass)
        }.frame(maxWidth: .infinity, alignment: .leading).card()
    }
}
