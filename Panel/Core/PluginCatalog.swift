import Foundation
import Combine

/// Metadata only, scoped to this panel connection. JAR bytes are not retained.
@MainActor final class PluginCatalog: ObservableObject {
    @Published private(set) var fingerprints: [String: AddonFingerprint] = [:]
    @Published private(set) var descriptors: [String: PluginDescriptor] = [:]
    @Published private(set) var errors: [String: String] = [:]
    @Published private(set) var loading: Set<String> = []
    private var transferring = false
    private var epochs: [String: Int] = [:]
    static func key(server: String, directory: String, entry: JSON) -> String {
        [server, directory, entry["name"].string, entry["size"].string, entry["modified_at"].string].joined(separator: "\u{0}")
    }
    func descriptor(server: String, directory: String = "/plugins", entry: JSON) -> PluginDescriptor? { descriptors[Self.key(server: server, directory: directory, entry: entry)] }
    func load(api: PanelAPI, server: String, directory: String = "/plugins", entry: JSON, retry: Bool = false, allowLarge: Bool = false) async {
        let key = Self.key(server: server, directory: directory, entry: entry)
        do { while loading.contains(key) { try await Task.sleep(for: .milliseconds(80)) } } catch { return }
        guard (descriptors[key] == nil || fingerprints[key] == nil), retry || errors[key] == nil else { return }
        let epoch = epochs[server, default: 0]
        guard api.access.allows(server, "file.read-content") else { errors[key] = "Keine Berechtigung zum Lesen der Plugin-JAR."; return }
        guard entry["size"].double <= 128 * 1024 * 1024 else { errors[key] = "JAR größer als 128 MiB. Metadaten können nicht eingelesen werden."; return }
        loading.insert(key); errors[key] = nil
        defer { loading.remove(key) }
        do {
            // One streamed download at a time, including lazy rows and detail sheets.
            while transferring { try await Task.sleep(for: .milliseconds(80)) }
            try Task.checkCancellation(); transferring = true
            defer { transferring = false }
            let descriptor: PluginDescriptor
            if api.demo {
                descriptor = try PluginDescriptor.parse("name: ExamplePlugin\nversion: 2.0.0\nmain: eu.example.Plugin\napi-version: '1.21'\nauthor: Demo Team\ndescription: Beispielplugin für die Vorschau\n")
            } else if let suite = try await PluginServerAPI.available(api: api, server: server) {
                let row = try await PluginServerAPI.inspect(suite, filename: entry["name"].string, pending: directory != "/plugins")
                descriptor = try PluginServerAPI.metadata(row)
                if epoch == epochs[server, default: 0] { fingerprints[key] = AddonFingerprint(sha1: row["sha1"].string, sha256: row["sha256"].string, curse: UInt32(clamping: Int64(row["curse"].double))) }
            } else {
                guard allowLarge || entry["size"].double <= 8 * 1024 * 1024 else { throw PanelError(message: "Große JAR: zum Lesen bitte die Plugin-Details öffnen.") }
                let data = try await api.fileData(server: server, path: pathJoin(directory, entry["name"].string), limit: 128 * 1024 * 1024)
                let parsed = try await Task.detached(priority: .utility) { (try PluginArchive.descriptor(data), AddonFingerprint.make(data)) }.value
                descriptor = parsed.0
                if epoch == epochs[server, default: 0] { fingerprints[key] = parsed.1 }
            }
            try Task.checkCancellation()
            guard epoch == epochs[server, default: 0] else { return }
            descriptors[key] = descriptor
        } catch is CancellationError {} catch { if !Task.isCancelled && epoch == epochs[server, default: 0] { errors[key] = error.localizedDescription } }
    }
    /// Reuse metadata only after the uploaded JAR's bytes were read back and verified.
    func rememberVerifiedUpload(_ descriptor: PluginDescriptor, server: String, directory: String, entry: JSON) {
        let key = Self.key(server: server, directory: directory, entry: entry)
        descriptors[key] = descriptor; errors[key] = nil
    }
    func rememberServerMetadata(_ row: JSON, server: String, directory: String, entry: JSON) {
        let key = Self.key(server: server, directory: directory, entry: entry)
        do { descriptors[key] = try PluginServerAPI.metadata(row); errors[key] = nil }
        catch { errors[key] = error.localizedDescription }
    }
    func clear(server: String) {
        epochs[server, default: 0] += 1
        let prefix = server + "\u{0}"
        fingerprints = fingerprints.filter { !$0.key.hasPrefix(prefix) }
        descriptors = descriptors.filter { !$0.key.hasPrefix(prefix) }
        errors = errors.filter { !$0.key.hasPrefix(prefix) }
    }
}

