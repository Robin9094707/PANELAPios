import WidgetKit
import Foundation

@MainActor enum WidgetPublisher {
    private static var lastReload = Date.distantPast
    static func publish(api: PanelAPI, server: Server, live: LiveServer) {
        guard !api.demo, var record = WidgetStorage.servers().first(where: { $0.panel == api.base.absoluteString && $0.serverID == server.id }) else { return }
        record.name = server.name; record.state = live.state
        record.cpu = live.cpu; record.memory = live.memory; record.disk = live.disk
        record.memoryLimit = server.data["limits"]["memory"].double * 1_048_576
        record.updated = live.lastUpdate
        record.online = live.players?.online; record.maximum = live.players?.maximum
        record.playersUpdated = live.players?.updated
        record.tps = live.tps?.minute; record.tpsUpdated = live.tps?.updated
        record.issue = nil
        try? WidgetStorage.upsert(record)
        if Date().timeIntervalSince(lastReload) > 60 { WidgetCenter.shared.reloadAllTimelines(); lastReload = Date() }
    }
    static func remove(panel: String) {
        WidgetVault.delete(panel); try? WidgetStorage.save(WidgetStorage.servers().filter { $0.panel != panel }); WidgetCenter.shared.reloadAllTimelines()
    }
}
