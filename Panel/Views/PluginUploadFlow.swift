import SwiftUI
import Combine

struct PluginUploadTarget: Identifiable {
    let id = UUID()
    let filename: String
}

/// The selected file is handled in the delegate callback, never in onDismiss.
/// Validation and confirmation live in one stable sheet throughout the operation.
@MainActor final class PluginUploadFlowModel: ObservableObject {
    @Published private(set) var candidate: PluginUpdateCandidate?
    @Published private(set) var busy = false
    @Published private(set) var status = "JAR-Datei auswählen"
    @Published var error: String?
    private var folder: URL?
    func inspect(_ url: URL, filename: String, files: PluginUpdateFiles) async {
        guard !busy else { return }
        busy = true; candidate = nil; error = nil; cleanup()
        status = "Ausgewählte JAR prüfen …"
        defer { busy = false }
        do {
            guard url.pathExtension.lowercased() == "jar" else { throw PanelError(message: "Bitte eine einzelne JAR-Datei auswählen.") }
            let nextData = try await Task.detached(priority: .userInitiated) {
                let info = try url.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey])
                guard info.isRegularFile == true, (info.fileSize ?? 0) <= 128 * 1024 * 1024 else { throw PanelError(message: "Nur JAR-Dateien bis 128 MiB werden unterstützt.") }
                let data = try Data(contentsOf: url)
                guard data.count <= 128 * 1024 * 1024 else { throw PanelError(message: "JAR größer als 128 MiB.") }
                return data
            }.value
            let next = try await Task.detached { try PluginArchive.descriptor(nextData) }.value
            status = "Installierte JAR laden und vergleichen …"
            let oldData = try await files.read("/plugins/" + filename)
            let old = try await Task.detached { try PluginArchive.descriptor(oldData) }.value
            let report = try old.validateUpdate(next)
            let directory = FileManager.default.temporaryDirectory.appendingPathComponent("plugin-validation-" + UUID().uuidString)
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            folder = directory
            let local = directory.appendingPathComponent(next.versionedFilename)
            try nextData.write(to: local, options: .atomic)
            candidate = PluginUpdateCandidate(filename: filename, file: local, installed: old, next: next, originalHash: PluginArchive.digest(oldData), report: report)
            status = "Dateiprüfung erfolgreich"
        } catch { self.error = error.localizedDescription; status = "Dateiprüfung fehlgeschlagen" }
    }
    func cleanup() { if let folder { try? FileManager.default.removeItem(at: folder) }; folder = nil }
}

struct PluginUploadFlow: View {
    let api: PanelAPI
    let server: Server
    let filename: String
    let stage: (PluginUpdateCandidate) async throws -> Void
    @Environment(\.dismiss) private var dismiss
    @StateObject private var model = PluginUploadFlowModel()
    @State private var picker = false
    @State private var acknowledged = false
    @State private var uploading = false
    @State private var done = false
    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text(filename).font(.headline).textSelection(.enabled)
                    Label(model.status, systemImage: model.candidate == nil ? "doc.badge.gearshape" : "checkmark.shield.fill")
                    if model.busy || uploading { ProgressView(uploading ? "Übertragen und Prüfsumme bestätigen …" : model.status) }
                    if !done { Button("JAR auswählen", systemImage: "doc") { picker = true }.disabled(model.busy || uploading) }
                }
                if let value = model.candidate {
                    Section("Geprüfte Versionen") {
                        LabeledContent("Plugin", value: value.next.name)
                        LabeledContent("Installiert", value: value.installed.version)
                        LabeledContent("Neue Datei", value: value.next.version)
                        LabeledContent("Update-Dateiname", value: value.updateFilename)
                        Text(value.report).font(.footnote)
                        Text("Paper übernimmt das Update beim nächsten Serverstart. Minecraft-Version und Abhängigkeiten zusätzlich prüfen.").font(.caption).foregroundStyle(.secondary)
                    }
                    if !done {
                        Section {
                            Toggle("Serverversion und Abhängigkeiten geprüft", isOn: $acknowledged)
                            Button("Update vormerken") { Task { await commit(value) } }
                                .disabled(!acknowledged || uploading || model.busy)
                                .accessibilityIdentifier("stage-plugin-update")
                        }
                    }
                }
                if let error = model.error { Section { Label(error, systemImage: "exclamationmark.triangle").foregroundStyle(.orange).textSelection(.enabled) } }
                if done { Section { Label("Update geprüft vorgemerkt · nächster Serverstart", systemImage: "checkmark.circle.fill").foregroundStyle(.green) } }
            }.panelScreen().navigationTitle("Plugin-Update prüfen").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Fertig") { dismiss() }.disabled(model.busy || uploading) } }
        }
        .interactiveDismissDisabled(model.busy || uploading)
        .task { picker = true }
        .sheet(isPresented: $picker) {
            FileImportPicker(retainSelection: true) { result in
                picker = false
                switch result {
                case .success(let urls):
                    guard let url = urls.first else { return }
                    acknowledged = false
                    Task {
                        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
                        await model.inspect(url, filename: filename, files: PluginUpdateFiles(api: api, server: server.id))
                    }
                case .failure(let issue): model.error = issue.localizedDescription
                }
            }.ignoresSafeArea()
        }
        .onDisappear { if !model.busy && !uploading { model.cleanup() } }
    }
    func commit(_ value: PluginUpdateCandidate) async {
        guard !uploading else { return }; uploading = true; model.error = nil
        defer { uploading = false }
        do { guard !api.demo else { throw PanelError(message: "Updates sind im Demomodus deaktiviert.") }; try await stage(value); done = true; model.cleanup() }
        catch { model.error = error.localizedDescription }
    }
}
