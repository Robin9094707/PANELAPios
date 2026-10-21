import WidgetKit
import Foundation

@MainActor enum WidgetPublisher {
    private static var lastReload = Date.distantPast
    private static var lastSave: [String: Date] = [:]
    static func publish(api: PanelAPI, server: Server, live: LiveServer) {
        guard !api.demo, var record = WidgetStorage.servers().first(where: { $0.panel == api.base.absoluteString && $0.serverID == server.id }) else { return }
        guard Date().timeIntervalSince(lastSave[record.id] ?? .distantPast) > 10 else { return }
        lastSave[record.id] = Date()
        record.name = server.name; record.state = live.state
        record.cpu = AMPMetrics.number(live.stats["cpu_absolute"]); record.memory = AMPMetrics.number(live.stats["memory_bytes"]); record.disk = AMPMetrics.number(live.stats["disk_bytes"]); record.uptime = AMPMetrics.number(live.stats["uptime"]).map { $0 / 1000 }
        record.memoryLimit = AMPMetrics.number(live.stats["memory_limit_bytes"]) ?? (server.data["limits"]["memory"].double > 0 ? server.data["limits"]["memory"].double * 1_048_576 : nil)
        record.updated = live.lastUpdate
        record.playerNames = live.players?.names; record.online = live.players?.online; record.maximum = live.players?.maximum
        record.playersUpdated = live.players?.updated
        record.tps = live.tps?.minute; record.tpsUpdated = live.tps?.updated
        record.issue = nil
        try? WidgetStorage.upsert(record)
        if Date().timeIntervalSince(lastReload) > 60 { WidgetCenter.shared.reloadAllTimelines(); lastReload = Date() }
    }
    static func remove(panel: String) {
        for record in WidgetStorage.servers() where record.panel == panel { WidgetVault.delete(record.id) }; WidgetVault.delete(panel); try? WidgetStorage.save(WidgetStorage.servers().filter { $0.panel != panel }); WidgetCenter.shared.reloadAllTimelines()
    }
}
