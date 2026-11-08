import SwiftUI

struct SuiteHomeEvents: View {
    let refresh: Int
    @EnvironmentObject private var store: AppStore
    @Environment(\.scenePhase) private var scene
    @ObservedObject private var connections = SuiteConnections.shared
    @ObservedObject private var preferences = SuiteQuickAccess.shared
    @State private var events: [Event] = []
    @State private var available = false

    private struct Event: Identifiable, Sendable {
        let id: String
        let connection: UUID
        let server: String
        let title: String
        let date: Date
        let critical: Bool
        let powerBot: Bool
    }

    private var entries: [SuiteHubEntry] {
        SuiteHubEntry.all(store: store, connections: connections).filter { !preferences.isHidden($0.id) }
    }

    var body: some View {
        let visible = entries
        let identity = visible.map { $0.id.uuidString + "|" + $0.connection.endpoint + "|" + $0.title }
        Group {
            if available && !visible.isEmpty {
                VStack(alignment: .leading, spacing: 12) {
                    Label("Aktuelle Ereignisse", systemImage: "bell.badge").font(.headline)
                    if events.isEmpty {
                        Text("Keine aktuellen Ereignisse.").font(.caption).foregroundStyle(.secondary)
                    }
                    ForEach(events) { event in
                        Button {
                            SuiteNavigationInbox.shared.request = SuiteLaunchRequest(tool: event.powerBot ? .powerBot : .events, connection: event.connection)
                        } label: { row(event) }.buttonStyle(.plain)
                    }
                }.frame(maxWidth: .infinity, alignment: .leading).card()
                    .accessibilityIdentifier("suite-home-events")
            }
        }.task(id: [String(refresh), String(describing: scene)] + identity) {
            guard scene == .active else { return }
            let ids = Set(visible.map(\.id))
            events.removeAll { !ids.contains($0.connection) }
            guard !visible.isEmpty else { available = false; return }
            while !Task.isCancelled {
                await load(visible)
                do { try await Task.sleep(for: .seconds(45)) } catch { return }
            }
        }
    }

    private func row(_ event: Event) -> some View {
        HStack(spacing: 10) {
            Image(systemName: event.critical ? "exclamationmark.triangle.fill" : (event.powerBot ? "shield" : "bell"))
                .foregroundStyle(event.critical ? Color.orange : Color.panelAccent)
            VStack(alignment: .leading, spacing: 3) {
                Text(event.title).font(.subheadline.weight(.semibold)).lineLimit(2)
                Text(event.server + " · " + SuiteGermanTime.text(event.date))
                    .font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer(minLength: 0)
            Image(systemName: "chevron.right").font(.caption).foregroundStyle(.tertiary)
        }.frame(maxWidth: .infinity, alignment: .leading).contentShape(Rectangle())
    }

    private func load(_ entries: [SuiteHubEntry]) async {
        let result = await withTaskGroup(of: [Event]?.self, returning: (Bool, [Event]).self) { group in
            for entry in entries {
                let title = preferences.name(entry.connection, store: store)
                group.addTask { await Self.fetch(entry.connection, title: title) }
            }
            var connected = false
            var result: [Event] = []
            for await events in group {
                if let events { connected = true; result.append(contentsOf: events) }
            }
            return (connected, result)
        }
        guard !Task.isCancelled else { return }
        available = result.0
        events = Array(result.1.sorted { $0.date > $1.date }.prefix(5))
    }

    @MainActor private static func fetch(_ connection: SuiteConnection, title: String) async -> [Event]? {
        do {
            let api = try SuiteAPI(connection, timeout: 8)
            _ = try await api.health(timeout: 8)
            guard api.features.contains("suite-home-events-v1"), api.can("data.events") || api.can("data.powerbot") else { return nil }
            let rows = try await api.request("home-events", timeout: 8)["items"].array
            return rows.compactMap { item in
                let powerBot = item["source"].string == "powerbot"
                let name = item["title"].string
                let kind = SuitePowerBotView.kinds[item["kind"].string] ?? "PowerBot-Ereignis"
                let label = powerBot ? [kind, name].filter { !$0.isEmpty }.joined(separator: " · ") : name
                guard let date = SuitePlayerPresentation.date(item["at"]), !label.isEmpty else { return nil }
                return Event(id: connection.id.uuidString + "|" + item["id"].string, connection: connection.id,
                             server: title, title: label, date: date, critical: item["critical"].bool, powerBot: powerBot)
            }
        } catch { return nil }
    }
}
