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
    var operationKey: String?
    var destinationKey: String?
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
    private var waiters: [UUID: [CheckedContinuation<Data, Error>]] = [:]
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
        if let existing = records.first(where: { record in
            guard !record.upload, record.state == "running", record.filename == name, record.context == context else { return false }
            if let source { return record.source == source }
            let savedURL = folder(record.id).appendingPathComponent("request.url")
            return (try? String(contentsOf: savedURL, encoding: .utf8)) == url.absoluteString
        }) { return existing.id }
        var record = TransferRecord(filename: name, context: context, upload: false)
        record.source = source
        try FileManager.default.createDirectory(at: folder(record.id), withIntermediateDirectories: true)
        try Data(url.absoluteString.utf8).write(to: folder(record.id).appendingPathComponent("request.url"), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        let task = session.downloadTask(with: url)
        begin(record, task: task); return record.id
    }
    /// Provider downloads share the same durable iOS transfer session as panel files.
    func downloadAndWait(_ signed: String, filename: String, context: String) async throws -> URL {
        let id = try download(signed, filename: filename, context: context)
        _ = try await withCheckedThrowingContinuation { waiters[id, default: []].append($0) }
        guard let record = records.first(where: { $0.id == id && $0.state == "completed" }) else { throw PanelError(message: "Download wurde entfernt oder nicht vollständig gespeichert.") }
        return localFile(record)
    }
    @discardableResult func upload(_ request: URLRequest, body: URL, filename: String, context: String, operationKey: String? = nil, destinationKey: String? = nil) async throws -> Data {
        if let operationKey, let active = records.first(where: { $0.upload && $0.active && $0.operationKey == operationKey }) {
            return try await withCheckedThrowingContinuation { waiters[active.id, default: []].append($0) }
        }
        if let destinationKey, records.contains(where: { $0.upload && $0.active && $0.destinationKey == destinationKey }) {
            throw PanelError(message: "Für diese Zieldatei läuft bereits ein anderer Upload.")
        }
        var record = TransferRecord(filename: filename, context: context, upload: true)
        let folder = folder(record.id)
        let persistent = folder.appendingPathComponent("body.upload")
        do {
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            try FileManager.default.copyItem(at: body, to: persistent)
            try FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: persistent.path)
            record.total = Int64((try persistent.resourceValues(forKeys: [.fileSizeKey])).fileSize ?? 0)
        } catch {
            try? FileManager.default.removeItem(at: folder)
            throw error
        }
        record.operationKey = operationKey
        record.destinationKey = destinationKey
        let task = session.uploadTask(with: request, fromFile: persistent)
        return try await withCheckedThrowingContinuation { continuation in
            waiters[record.id, default: []].append(continuation); begin(record, task: task)
        }
    }
    private func begin(_ record: TransferRecord, task: URLSessionTask) {
        records.insert(record, at: 0); tasks[record.id] = task; task.taskDescription = record.id.uuidString
        persist(force: true); startActivity(record); task.resume()
    }
    func progress(_ id: UUID, taskIdentifier: Int, sent: Int64, total: Int64) {
        guard let i = records.firstIndex(where: { $0.id == id }), records[i].active, tasks[id]?.taskIdentifier == taskIdentifier else { return }
        records[i].completed = max(0, sent); records[i].total = total; records[i].updated = Date()
        persist(); updateActivity(records[i])
    }
    func finish(_ id: UUID, taskIdentifier: Int, response: URLResponse?, error: Error?, data: Data = Data()) {
        guard let i = records.firstIndex(where: { $0.id == id }), (tasks[id]?.taskIdentifier ?? taskIdentifier) == taskIdentifier else { return }
        // A paused task's cancellation is expected. Resume-data delivery owns this transition.
        if records[i].state == "pausing" || records[i].state == "paused" {
            if error != nil || !FileManager.default.fileExists(atPath: localFile(records[i]).path) { return }
        }
        tasks[id] = nil
        if records[i].state == "cancelled" { try? FileManager.default.removeItem(at: folder(id).appendingPathComponent("body.upload")); completeWaiters(id, with: .failure(CancellationError())); return }
        guard records[i].state != "completed" && records[i].state != "failed" else { return }
        var failure = error
        if failure == nil, let response { do { try PanelAPI.validate(data, response: response) } catch { failure = error } }
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
        if let failure { completeWaiters(id, with: .failure(failure)) } else { completeWaiters(id, with: .success(data)) }
    }
    private func completeWaiters(_ id: UUID, with result: Result<Data, Error>) {
        for waiter in waiters.removeValue(forKey: id) ?? [] { waiter.resume(with: result) }
    }
    func pause(_ record: TransferRecord) {
        guard !record.upload, let task = tasks[record.id] as? URLSessionDownloadTask, let i = records.firstIndex(where: { $0.id == record.id }) else { return }
        records[i].state = "pausing"; persist(force: true)
        task.cancel { data in
            Task { @MainActor in
                guard let index = self.records.firstIndex(where: { $0.id == record.id }), self.records[index].state == "pausing" else { return }
                self.tasks[record.id] = nil
                if let data { do { try data.write(to: self.folder(record.id).appendingPathComponent("resume.data"), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]) } catch { self.storageError = error.localizedDescription } }
                self.records[index].state = "paused"; self.completeWaiters(record.id, with: .failure(CancellationError())); self.persist(force: true); self.updateActivity(self.records[index], finish: true)
            }
        }
    }
    func resume(_ record: TransferRecord, api: PanelAPI?, legacySource: DownloadSource? = nil, restart: Bool = false) async throws {
        guard !record.upload, let index = records.firstIndex(where: { $0.id == record.id }), !records[index].active, records[index].state != "completed" else { return }
        // Legacy inference is only for 1.3 records. A new provider export has its own signed URL
        // and must not be mistaken for a standard backup-download endpoint.
        let hasSavedRequest = FileManager.default.fileExists(atPath: folder(record.id).appendingPathComponent("request.url").path)
        let source = records[index].source ?? (hasSavedRequest ? nil : legacySource)
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
        completeWaiters(record.id, with: .failure(CancellationError()))
        persist(force: true); updateActivity(records[i], finish: true)
    }
    func remove(_ record: TransferRecord) {
        guard !record.active else { return }
        try? FileManager.default.removeItem(at: folder(record.id)); records.removeAll { $0.id == record.id }; persist(force: true)
    }
    /// Clear history without interrupting iOS-owned work, or explicitly cancel it too.
    func clearAll(includingActive: Bool = false) {
        let removed = records.filter { includingActive || !$0.active }
        for record in removed {
            if record.active { cancel(record) }
            tasks[record.id]?.cancel(); tasks[record.id] = nil
            completeWaiters(record.id, with: .failure(CancellationError()))
            do { if FileManager.default.fileExists(atPath: folder(record.id).path) { try FileManager.default.removeItem(at: folder(record.id)) } }
            catch { storageError = "Lokale Datei konnte nicht entfernt werden: " + error.localizedDescription }
        }
        let ids = Set(removed.map(\.id))
        records.removeAll { ids.contains($0.id) }; persist(force: true)
        if let activity, ids.contains(where: { $0.uuidString == activity.attributes.transferID }) {
            Task { await activity.end(nil, dismissalPolicy: .immediate) }; self.activity = nil
        }
    }
    private func startActivity(_ record: TransferRecord) {
        guard UIApplication.shared.applicationState == .active, ActivityAuthorizationInfo().areActivitiesEnabled,
              UserDefaults.standard.object(forKey: "transferActivities") as? Bool ?? true else { return }
        activityUpdate = .distantPast
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

private final class TransferDelegate: NSObject, URLSessionDownloadDelegate, URLSessionDataDelegate {
    weak var owner: TransferManager?
    private var downloadErrors: [UUID: Error] = [:]
    private var responseBodies: [UUID: Data] = [:]
    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64, totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        guard let id = downloadTask.taskDescription.flatMap(UUID.init(uuidString:)) else { return }
        Task { @MainActor in owner?.progress(id, taskIdentifier: downloadTask.taskIdentifier, sent: totalBytesWritten, total: totalBytesExpectedToWrite) }
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, didSendBodyData bytesSent: Int64, totalBytesSent: Int64, totalBytesExpectedToSend: Int64) {
        guard let id = task.taskDescription.flatMap(UUID.init(uuidString:)) else { return }
        Task { @MainActor in owner?.progress(id, taskIdentifier: task.taskIdentifier, sent: totalBytesSent, total: totalBytesExpectedToSend) }
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

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        guard let id = dataTask.taskDescription.flatMap(UUID.init(uuidString:)) else { return }
        let remaining = 64 * 1024 - (responseBodies[id]?.count ?? 0)
        if remaining > 0 { responseBodies[id, default: Data()].append(contentsOf: data.prefix(remaining)) }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let id = task.taskDescription.flatMap(UUID.init(uuidString:)) else { return }
        let downloadError = downloadErrors.removeValue(forKey: id)
        let data = responseBodies.removeValue(forKey: id) ?? Data()
        Task { @MainActor in owner?.finish(id, taskIdentifier: task.taskIdentifier, response: task.response, error: error ?? downloadError, data: data) }
    }
    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        Task { @MainActor in owner?.persist(force: true); let completion = owner?.backgroundCompletion; owner?.backgroundCompletion = nil; completion?() }
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        // Suite uploads must stay at their authenticated origin.
        guard task.originalRequest?.value(forHTTPHeaderField: "Authorization") == nil else { completionHandler(nil); return }
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

