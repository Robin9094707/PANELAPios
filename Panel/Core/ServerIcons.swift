import Foundation
import UIKit
import ImageIO

actor ImageRequestGate {
    private var active = 0
    private var waiters: [CheckedContinuation<Void, Never>] = []
    func acquire() async {
        if active < 3 { active += 1; return }
        await withCheckedContinuation { waiters.append($0) }
    }
    func release() {
        if !waiters.isEmpty { waiters.removeFirst().resume() }
        else { active = max(0, active - 1) }
    }
}
enum ServerIconValidation {
    static func valid(_ data: Data) -> Bool {
        guard data.count <= 512 * 1024, data.starts(with: [137, 80, 78, 71, 13, 10, 26, 10]),
              let source = CGImageSourceCreateWithData(data as CFData, nil), CGImageSourceGetCount(source) == 1,
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = properties[kCGImagePropertyPixelWidth] as? Int,
              let height = properties[kCGImagePropertyPixelHeight] as? Int,
              width == 64, height == 64 else { return false }
        return true
    }
}
@MainActor final class ServerIconCache {
    private struct Entry { let data: Data?; let checked: Date }
    private var values: [String: Entry] = [:]
    private var tasks: [String: Task<Data?, Never>] = [:]
    private let gate = ImageRequestGate()
    func load(api: PanelAPI, server: Server, force: Bool = false) async -> Data? {
        guard !api.demo, api.access.allows(server.id, "file.read-content") else { return nil }
        let key = server.id
        if !force, let cached = values[key], Date().timeIntervalSince(cached.checked) < (cached.data == nil ? 60 : 600) { return cached.data }
        if let task = tasks[key] { return await task.value }
        let gate = gate
        let task = Task<Data?, Never> {
            await gate.acquire()
            let data: Data?
            do {
                try Task.checkCancellation()
                let raw = try await withThrowingTaskGroup(of: Data.self) { group in
                    group.addTask { @MainActor in try await api.fileData(server: server.id, path: "/server-icon.png", limit: 512 * 1024) }
                    group.addTask { try await Task.sleep(for: .seconds(12)); throw URLError(.timedOut) }
                    defer { group.cancelAll() }
                    return try await group.next()!
                }
                data = ServerIconValidation.valid(raw) ? raw : nil
            } catch { data = nil }
            await gate.release()
            return data
        }
        tasks[key] = task
        let data = await task.value; tasks.removeValue(forKey: key)
        if values.count >= 96 { values.removeValue(forKey: values.min { $0.value.checked < $1.value.checked }!.key) }
        values[key] = Entry(data: data, checked: Date()); return data
    }
}
