import Foundation
import SwiftUI
import ActivityKit
import UIKit

struct TransferRecord: Identifiable, Codable {
    var id = UUID()
    var filename: String
    var context: String
    var upload: Bool
    var state = "running"
    var completed: Int64 = 0
    var total: Int64 = -1
    var created = Date()
    var updated = Date()
    var issue: String?
    var source: DownloadSource?
    var fraction: Double? { total > 0 ? min(1, max(0, Double(completed) / Double(total))) : nil }
    var active: Bool { state == "running" || state == "pausing" || state == "resolving" }
    var title: String {
        switch state {
        case "running": return total > 0 && completed >= total ? "Server bestätigt …" : "Wird übertragen"
        case "resolving": return "Download-Link erneuern …"
        case "pausing": return "Wird angehalten …"
        case "paused": return "Pausiert"
        case "completed": return "Abgeschlossen"
        case "cancelled": return "Abgebrochen"
        case "interrupted": return "Unterbrochen"
        default: return "Fehlgeschlagen"
        }
    }
}

/// File-backed tasks belong to iOS, rather than the lifetime of an individual view.
@MainActor final class TransferManager: ObservableObject {
    static let shared = TransferManager()
    nonisolated static let identifier = "eu.simplexsmp.panel.transfers.v1"
    nonisolated static let root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("Transfers", isDirectory: true)
    @Published private(set) var records: [TransferRecord] = []
    @Published var storageError: String?
    var backgroundCompletion: (() -> Void)?
    private let bridge = TransferDelegate()
    private var session: URLSession!
    private var tasks: [UUID: URLSessionTask] = [:]
    private var waiters: [UUID: CheckedContinuation<Void, Error>] = [:]
    private var saved = Date.distantPast
    private var activityUpdate = Date.distantPast
    private var transientDemo = false
    private var activity: Activity<TransferActivity>?
    private init() {
        do {
            try FileManager.default.createDirectory(at: Self.root, withIntermediateDirectories: true)
            try FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: Self.root.path)
            var root = Self.root; var values = URLResourceValues(); values.isExcludedFromBackup = true; try root.setResourceValues(values)
            let manifest = Self.root.appendingPathComponent("manifest.json")
            if FileManager.default.fileExists(atPath: manifest.path) { records = try JSONDecoder().decode([TransferRecord].self, from: Data(contentsOf: manifest)) }
        } catch { storageError = "Übertragungsverlauf konnte nicht geladen werden: \(error.localizedDescription)" }
        if ProcessInfo.processInfo.arguments.contains("--demo-paused-download") {
            transientDemo = true
            var record = TransferRecord(filename: "Demo-Download.zip", context: "Simplex SMP · /", upload: false)
            record.state = "paused"; record.completed = 1024; record.total = 4096
            records = [record]
        }
        bridge.owner = self
        let config = URLSessionConfiguration.background(withIdentifier: Self.identifier)
        config.sessionSendsLaunchEvents = true; config.isDiscretionary = false
        config.waitsForConnectivity = true; config.timeoutIntervalForResource = 7 * 24 * 3600
        session = URLSession(configuration: config, delegate: bridge, delegateQueue: .main)
        activity = Activity<TransferActivity>.activities.first
        session.getAllTasks { tasks in
            Task { @MainActor in
                for task in tasks { if let id = task.taskDescription.flatMap(UUID.init(uuidString:)) { self.tasks[id] = task } }
                for i in self.records.indices where self.records[i].active && self.tasks[self.records[i].id] == nil {
                    self.records[i].state = "interrupted"
                    self.records[i].issue = "iOS hat diese Übertragung beendet. Öffne den Download erneut oder wähle die Upload-Datei nochmals aus."
                }
                self.persist(force: true)
            }
        }
    }
    func folder(_ id: UUID) -> URL { Self.root.appendingPathComponent(id.uuidString, isDirectory: true) }
    func localFile(_ record: TransferRecord) -> URL { folder(record.id).appendingPathComponent(record.filename) }
    func hasResume(_ record: TransferRecord) -> Bool { FileManager.default.fileExists(atPath: folder(record.id).appendingPathComponent("resume.data").path) }
    func persist(force: Bool = false) {
        guard !transientDemo else { return }
        guard force || Date().timeIntervalSince(saved) > 1 else { return }
        do { try JSONEncoder().encode(records).write(to: Self.root.appendingPathComponent("manifest.json"), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]); saved = Date() }
        catch { storageError = "Übertragungsfortschritt konnte nicht gesichert werden: \(error.localizedDescription)" }
    }
    @discardableResult func download(_ signed: String, filename: String, context: String, source: DownloadSource? = nil) throws -> UUID {
        guard let url = URL(string: signed), url.scheme == "https" else { throw PanelError(message: "Keine sichere Download-URL erhalten.") }
        let name = URL(fileURLWithPath: filename).lastPathComponent
        guard !name.isEmpty, name != ".", name != "..", !["resume.data", "body.upload", "request.url"].contains(name) else { throw PanelError(message: "Dieser Dateiname kann nicht lokal gespeichert werden.") }
        var record = TransferRecord(filename: name, context: context, upload: false)
        record.source = source
        try FileManager.default.createDirectory(at: folder(record.id), withIntermediateDirectories: true)
        try Data(url.absoluteString.utf8).write(to: folder(record.id).appendingPathComponent("request.url"), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        let task = session.downloadTask(with: url)
        begin(record, task: task); return record.id
    }
    func upload(_ request: URLRequest, body: URL, filename: String, context: String) async throws {
        var record = TransferRecord(filename: filename, context: context, upload: true)
        try FileManager.default.createDirectory(at: folder(record.id), withIntermediateDirectories: true)
        let persistent = folder(record.id).appendingPathComponent("body.upload")
        try FileManager.default.moveItem(at: body, to: persistent)
        try FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: persistent.path)
        record.total = Int64((try persistent.resourceValues(forKeys: [.fileSizeKey])).fileSize ?? 0)
        let task = session.uploadTask(with: request, fromFile: persistent)
        try await withCheckedThrowingContinuation { continuation in
            waiters[record.id] = continuation; begin(record, task: task)
        }
    }
    private func begin(_ record: TransferRecord, task: URLSessionTask) {
        records.insert(record, at: 0); tasks[record.id] = task; task.taskDescription = record.id.uuidString
        persist(force: true); startActivity(record); task.resume()
    }
    func progress(_ id: UUID, sent: Int64, total: Int64) {
        guard let i = records.firstIndex(where: { $0.id == id }), records[i].active else { return }
        records[i].completed = max(0, sent); records[i].total = total; records[i].updated = Date()
        persist(); updateActivity(records[i])
    }
    func finish(_ id: UUID, response: URLResponse?, error: Error?, moved: Bool = false) {
        guard let i = records.firstIndex(where: { $0.id == id }) else { return }
        // A paused task's cancellation is expected. Resume-data delivery owns this transition.
        if records[i].state == "pausing" || records[i].state == "paused" {
            if error != nil || !FileManager.default.fileExists(atPath: localFile(records[i]).path) { return }
        }
        tasks[id] = nil
        if records[i].state == "cancelled" { try? FileManager.default.removeItem(at: folder(id).appendingPathComponent("body.upload")); waiters.removeValue(forKey: id)?.resume(throwing: CancellationError()); return }
        guard records[i].state != "completed" && records[i].state != "failed" else { return }
        var failure = error
        if failure == nil, let response { do { try PanelAPI.validate(Data(), response: response) } catch { failure = error } }
        if failure == nil && !records[i].upload && !FileManager.default.fileExists(atPath: localFile(records[i]).path) {
            failure = PanelError(message: "Die Download-Datei konnte nicht gespeichert werden.")
        }
        if failure == nil && response == nil { failure = PanelError(message: "Keine Serverbestätigung erhalten.") }
        if let data = (failure as NSError?)?.userInfo[NSURLSessionDownloadTaskResumeData] as? Data {
            try? data.write(to: folder(id).appendingPathComponent("resume.data"), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        }
        records[i].state = failure == nil ? "completed" : "failed"
        records[i].issue = failure?.localizedDescription; records[i].updated = Date()
        if failure == nil {
            if records[i].total > 0 { records[i].completed = records[i].total }
        }
        // Multipart upload bodies are retained only while iOS owns the active task.
        if records[i].upload { try? FileManager.default.removeItem(at: folder(id).appendingPathComponent("body.upload")) }
        persist(force: true); updateActivity(records[i], finish: true)
        if let waiter = waiters.removeValue(forKey: id) { if let failure { waiter.resume(throwing: failure) } else { waiter.resume() } }
    }
    func pause(_ record: TransferRecord) {
        guard !record.upload, let task = tasks[record.id] as? URLSessionDownloadTask, let i = records.firstIndex(where: { $0.id == record.id }) else { return }
        records[i].state = "pausing"; persist(force: true)
        task.cancel { data in
            Task { @MainActor in
                guard let index = self.records.firstIndex(where: { $0.id == record.id }), self.records[index].state == "pausing" else { return }
                self.tasks[record.id] = nil
                if let data { do { try data.write(to: self.folder(record.id).appendingPathComponent("resume.data"), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]) } catch { self.storageError = error.localizedDescription } }
                self.records[index].state = "paused"; self.persist(force: true); self.updateActivity(self.records[index], finish: true)
            }
        }
    }
    func resume(_ record: TransferRecord, api: PanelAPI?, legacySource: DownloadSource? = nil, restart: Bool = false) async throws {
        guard !record.upload, let index = records.firstIndex(where: { $0.id == record.id }), !records[index].active, records[index].state != "completed" else { return }
        let source = records[index].source ?? legacySource
        let resumeFile = folder(record.id).appendingPathComponent("resume.data")
        let task: URLSessionDownloadTask
        if !restart, let data = try? Data(contentsOf: resumeFile), !data.isEmpty {
            task = session.downloadTask(withResumeData: data)
            try? FileManager.default.removeItem(at: resumeFile)
        } else {
            if restart { try? FileManager.default.removeItem(at: resumeFile) }
            records[index].state = "resolving"; records[index].issue = nil; persist(force: true)
            do {
                let signed: String
                if let source {
                    guard let api else { throw PanelError(message: "Öffne zuerst das Panel dieser Übertragung. Dann kann die App einen frischen Download-Link anfordern.") }
                    signed = try await api.downloadURL(for: source)
                } else if let data = try? Data(contentsOf: folder(record.id).appendingPathComponent("request.url")), let url = String(data: data, encoding: .utf8) { signed = url }
                else { throw PanelError(message: "Bei diesem älteren Download wurde keine Quelle gespeichert. Öffne die Datei oder das Backup einmal neu zum Herunterladen; neue Übertragungen können hier direkt fortgesetzt oder neu gestartet werden.") }
                guard let url = URL(string: signed), url.scheme == "https" else { throw PanelError(message: "Kein sicherer Download-Link erhalten.") }
                guard let current = records.firstIndex(where: { $0.id == record.id }), records[current].state == "resolving" else { throw CancellationError() }
                try Data(signed.utf8).write(to: folder(record.id).appendingPathComponent("request.url"), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
                records[current].completed = 0; records[current].total = -1; records[current].source = source
                task = session.downloadTask(with: url)
            } catch {
                if let current = records.firstIndex(where: { $0.id == record.id }), records[current].state == "resolving" { records[current].state = "paused"; records[current].issue = error.localizedDescription; persist(force: true) }
                throw error
            }
        }
        guard let current = records.firstIndex(where: { $0.id == record.id }) else { task.cancel(); return }
        records[current].state = "running"; records[current].issue = nil; records[current].updated = Date()
        tasks[record.id] = task; task.taskDescription = record.id.uuidString
        persist(force: true); startActivity(records[current]); task.resume()
    }
    func cancel(_ record: TransferRecord) {
        guard let i = records.firstIndex(where: { $0.id == record.id }) else { return }
        records[i].state = "cancelled"; tasks[record.id]?.cancel(); tasks[record.id] = nil
        waiters.removeValue(forKey: record.id)?.resume(throwing: CancellationError())
        persist(force: true); updateActivity(records[i], finish: true)
    }
    func remove(_ record: TransferRecord) {
        guard !record.active else { return }
        try? FileManager.default.removeItem(at: folder(record.id)); records.removeAll { $0.id == record.id }; persist(force: true)
    }
    private func startActivity(_ record: TransferRecord) {
        guard UIApplication.shared.applicationState == .active, ActivityAuthorizationInfo().areActivitiesEnabled,
              UserDefaults.standard.object(forKey: "transferActivities") as? Bool ?? true else { return }
        if let old = activity { Task { await old.end(nil, dismissalPolicy: .immediate) } }
        let state = TransferActivity.ContentState(completed: record.completed, total: record.total, phase: record.title, updated: Date())
        activity = try? Activity.request(attributes: TransferActivity(transferID: record.id.uuidString, filename: record.filename, upload: record.upload), content: ActivityContent(state: state, staleDate: Date().addingTimeInterval(90)), pushType: nil)
    }
    private func updateActivity(_ record: TransferRecord, finish: Bool = false) {
        guard let activity, activity.attributes.transferID == record.id.uuidString, finish || Date().timeIntervalSince(activityUpdate) >= 2 else { return }
        activityUpdate = Date()
        let content = ActivityContent(state: TransferActivity.ContentState(completed: record.completed, total: record.total, phase: record.title, updated: Date()), staleDate: Date().addingTimeInterval(90))
        Task { if finish { await activity.end(content, dismissalPolicy: .after(Date().addingTimeInterval(120))) } else { await activity.update(content) } }
        if finish { self.activity = nil }
    }
}

private final class TransferDelegate: NSObject, URLSessionDownloadDelegate, URLSessionTaskDelegate {
    weak var owner: TransferManager?
    private var downloadErrors: [UUID: Error] = [:]
    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64, totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        guard let id = downloadTask.taskDescription.flatMap(UUID.init(uuidString:)) else { return }
        Task { @MainActor in owner?.progress(id, sent: totalBytesWritten, total: totalBytesExpectedToWrite) }
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, didSendBodyData bytesSent: Int64, totalBytesSent: Int64, totalBytesExpectedToSend: Int64) {
        guard let id = task.taskDescription.flatMap(UUID.init(uuidString:)) else { return }
        Task { @MainActor in owner?.progress(id, sent: totalBytesSent, total: totalBytesExpectedToSend) }
    }
    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        guard let id = downloadTask.taskDescription.flatMap(UUID.init(uuidString:)) else { return }
        // Must move synchronously: URLSession deletes its temporary location on return.
        let folder = TransferManager.root.appendingPathComponent(id.uuidString)
        do {
            guard let response = downloadTask.response else { throw URLError(.badServerResponse) }
            try PanelAPI.validate(Data(), response: response)
            let manifest = try JSONDecoder().decode([TransferRecord].self, from: Data(contentsOf: TransferManager.root.appendingPathComponent("manifest.json")))
            guard let record = manifest.first(where: { $0.id == id }) else { throw URLError(.fileDoesNotExist) }
            let final = folder.appendingPathComponent(record.filename)
            if FileManager.default.fileExists(atPath: final.path) { try FileManager.default.removeItem(at: final) }
            try FileManager.default.moveItem(at: location, to: final)
        } catch { downloadErrors[id] = error }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let id = task.taskDescription.flatMap(UUID.init(uuidString:)) else { return }
        let failure = error ?? downloadErrors.removeValue(forKey: id)
        Task { @MainActor in owner?.finish(id, response: task.response, error: failure) }
    }
    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        Task { @MainActor in owner?.persist(force: true); let completion = owner?.backgroundCompletion; owner?.backgroundCompletion = nil; completion?() }
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        // These requests use signed URLs, never a Client-API bearer token.
        guard request.url?.scheme == "https" else { completionHandler(nil); return }
        var unsigned = request; unsigned.setValue(nil, forHTTPHeaderField: "Authorization"); completionHandler(unsigned)
    }
}

final class PanelAppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, handleEventsForBackgroundURLSession identifier: String, completionHandler: @escaping () -> Void) {
        guard identifier == TransferManager.identifier else { completionHandler(); return }
        TransferManager.shared.backgroundCompletion = completionHandler
    }
}
