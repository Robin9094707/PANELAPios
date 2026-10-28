import SwiftUI

struct SuiteHomeStatus: View {
    let panel: PanelAPI
    let server: Server
    @State private var summary: JSON = .null
    @State private var error: String?
    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            if summary != .null {
                Label("\(summary["online"].int) / \(summary["maxPlayers"].int) Spieler online", systemImage: "person.2.fill").font(.subheadline.bold()).accessibilityIdentifier("home-online-count-" + server.id)
                if summary["scoreboard"] != .null { Text("Ingame-Tage: \(summary["scoreboard"]["mainDays"].string) · \(summary["scoreboard"]["mainWorld"].string)").font(.caption).foregroundStyle(.secondary) }
                if let date = SuitePlayerPresentation.date(summary["at"]) { Text(SuiteGermanTime.text(date)).font(.caption.monospacedDigit()).foregroundStyle(.secondary) }
            } else { Text(error ?? "Serverinfos laden …").font(.caption).foregroundStyle(.secondary) }
        }.frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 12).task {
            do {
                let connection = SuiteConnections.shared.get(panel, server) ?? SuiteConnection(panel: panel.base.absoluteString, server: server.id, endpoint: "https://suite.demo.invalid", fingerprint: "", serverIdentity: "synthetic-suite")
                let client = try SuiteAPI(connection, token: panel.demo ? "" : nil, demo: panel.demo)
                _ = try await client.health()
                repeat {
                    summary = try await client.data("overview"); error = nil
                    try await Task.sleep(for: .seconds(30))
                } while !Task.isCancelled
            } catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
        }
    }
}
