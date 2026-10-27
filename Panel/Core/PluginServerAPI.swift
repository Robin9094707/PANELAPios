import Foundation

/// Capability negotiation happens before any write. A failed upload never silently switches transports.
@MainActor enum PluginServerAPI {
    private struct Cached { let connection: SuiteConnection; let client: SuiteAPI?; let until: Date }
    private static var cached: [String: Cached] = [:]
    private static var checking: Set<String> = []
    static func available(api: PanelAPI, server: String) async throws -> SuiteAPI? {
        guard !api.demo, let connection = SuiteConnections.shared.values.first(where: { $0.panel == api.base.absoluteString && $0.server == server }) else { return nil }
        let key = api.base.absoluteString + "|" + server
        while checking.contains(key) { try await Task.sleep(for: .milliseconds(50)) }
        if let value = cached[key], value.connection == connection, value.until > Date() { return value.client }
        checking.insert(key); defer { checking.remove(key) }
        do {
            let client = try SuiteAPI(connection)
            let health = try await client.health(timeout: 8)
            let supported = health["features"].array.contains { $0.string == "plugin-files-v1" }
            cached[key] = Cached(connection: connection, client: supported ? client : nil, until: Date().addingTimeInterval(30))
            return supported ? client : nil
        } catch {
            if RequestFailure.isCancellation(error) { throw CancellationError() }
            // No server mutation has happened. The ordinary panel remains usable while Minecraft is offline.
            cached[key] = Cached(connection: connection, client: nil, until: Date().addingTimeInterval(10))
            return nil
        }
    }
    static func metadata(_ row: JSON) throws -> PluginDescriptor {
        if !row["error"].string.isEmpty { throw PanelError(message: row["error"].string) }
        var value = try PluginDescriptor.parse(row["yaml"].string)
        value.descriptorFile = row["descriptorFile"].string
        return value
    }
    static func inspect(_ client: SuiteAPI, filename: String, pending: Bool = false) async throws -> JSON {
        try await client.request("plugin-files/inspect", query: ["filename": filename, "pending": pending ? "true" : "false"])
    }
    static func stage(_ value: PluginUpdateCandidate, client: SuiteAPI) async throws -> String {
        let data = try Data(contentsOf: value.file, options: .mappedIfSafe)
        guard try PluginArchive.descriptor(data) == value.next else { throw PanelError(message: "Update-Datei wurde geändert. Neu auswählen.") }
        let hash = PluginArchive.digest(data)
        let values: [String: JSON] = ["filename": .string(value.filename), "expected": .string(value.originalHash), "sha256": .string(hash), "name": .string(value.next.name), "main": .string(value.next.main), "version": .string(value.next.version)]
        let plan = try await client.prepare("plugins.stage", values: values)
        let ticket = plan["ticket"].string
        guard !ticket.isEmpty else { throw PanelError(message: "Server hat keine Update-Bestätigung geliefert.") }
        let result: JSON
        do { result = try await client.request("plugin-files/upload", method: "POST", query: ["ticket": ticket], binary: data) }
        catch {
            if RequestFailure.isCancellation(error) { throw CancellationError() }
            let uploadError = error
            // A lost reply may follow successful publication. Check the exact hash; never resend or fall back here.
            if let status = try? await client.request("plugin-files/status", query: ["filename": value.filename, "sha256": hash]), status["queued"].bool { return status["updateDirectory"].string }
            throw PanelError(message: "Server-Update nicht bestätigt: \(uploadError.localizedDescription)\nBitte vorgemerkte Updates prüfen. Die Übertragung wurde nicht automatisch wiederholt.")
        }
        guard result["queued"].bool, result["sha256"].string == hash, result["filename"].string == value.filename else { throw PanelError(message: "Server hat das Update nicht mit passender Prüfsumme bestätigt.") }
        return result["updateDirectory"].string
    }
}
