import Foundation

struct DownloadSource: Codable, Equatable {
    var panel: String
    var server: String
    var kind: String
    var resource: String
    @MainActor static func legacy(context: String, filename: String, api: PanelAPI, servers: [Server]) -> DownloadSource? {
        for server in servers.sorted(by: { $0.name.count > $1.name.count }) {
            let prefix = server.name + " · "
            guard context.hasPrefix(prefix) else { continue }
            let target = String(context.dropFirst(prefix.count))
            if target == "Backup", filename.hasSuffix(".tar.gz") {
                let uuid = String(filename.dropLast(7))
                if UUID(uuidString: uuid) != nil { return DownloadSource(panel: api.base.absoluteString, server: server.id, kind: "backup", resource: uuid) }
            } else if target.hasPrefix("/") {
                return DownloadSource(panel: api.base.absoluteString, server: server.id, kind: "file", resource: pathJoin(target, filename))
            }
        }
        return nil
    }
}
struct BackupInfo {
    let value: JSON
    var id: String { value["uuid"].string.isEmpty ? value["id"].string : value["uuid"].string }
    var completed: Bool { value["completed_at"] != .null && !value["completed_at"].string.isEmpty || ["completed", "successful", "success", "finished"].contains(value["status"].string.lowercased()) || successful }
    var successful: Bool {
        if value["is_successful"] != .null { return value["is_successful"].bool || value["is_successful"].int == 1 }
        return ["completed", "successful", "success", "finished"].contains(value["status"].string.lowercased())
    }
    var downloadable: Bool { successful && value["is_downloadable"] != .bool(false) && value["can_download"] != .bool(false) }
    static func downloadURL(_ response: JSON, base: URL) throws -> String {
        var candidates: [String] = []
        func collect(_ value: JSON, depth: Int = 0) {
            guard depth < 5 else { return }
            if case .string(let string) = value { candidates.append(string); return }
            for key in ["url", "download_url", "signed_url"] { if !value[key].string.isEmpty { candidates.append(value[key].string) } }
            for key in ["attributes", "data", "download", "links"] { if value[key] != .null { collect(value[key], depth: depth + 1) } }
        }
        collect(response)
        for candidate in candidates {
            guard let url = URL(string: candidate, relativeTo: base)?.absoluteURL, url.scheme == "https", url.user == nil, url.password == nil else { continue }
            // Provider messages such as "export queued" are never interpreted as relative URLs.
            guard candidate.hasPrefix("https://") || candidate.hasPrefix("/") else { continue }
            return url.absoluteString
        }
        throw PanelError(message: "Der Provider hat keinen Download-Link geliefert. Manche Backup-Speicher erlauben nur Wiederherstellung oder einen separaten Export. Aktualisiere die Liste oder verwende, falls verfügbar, ‚Export vorbereiten‘.")
    }
}
@MainActor extension PanelAPI {
    func downloadURL(for source: DownloadSource) async throws -> String {
        guard source.panel == base.absoluteString else { throw PanelError(message: "Wähle zuerst das Panel dieser Übertragung aus.") }
        guard !source.server.contains("/"), !source.server.isEmpty else { throw PanelError(message: "Ungültige Server-ID.") }
        if source.kind == "file" {
            let result = try await request("servers/\(source.server)/files/download", query: ["file": source.resource])
            return try BackupInfo.downloadURL(result, base: base)
        }
        guard source.kind == "backup", !source.resource.contains("/"), !source.resource.isEmpty else { throw PanelError(message: "Ungültige Download-Quelle.") }
        return try BackupInfo.downloadURL(try await request("servers/\(source.server)/backups/\(source.resource)/download"), base: base)
    }
}
