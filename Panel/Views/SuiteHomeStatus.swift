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
            } else if error == nil { Text("Serverinfos laden …").font(.caption).foregroundStyle(.secondary) }
            if let error { Label((summary == .null ? "" : "Letzter Stand · ") + error, systemImage: "arrow.clockwise.circle").font(.caption).foregroundStyle(.orange) }
        }.frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 12).task {
            do {
                let connection = SuiteConnections.shared.get(panel, server) ?? SuiteConnection(panel: panel.base.absoluteString, server: server.id, endpoint: "https://suite.demo.invalid", fingerprint: "", serverIdentity: "synthetic-suite")
                let client = try SuiteAPI(connection, token: panel.demo ? "" : nil, demo: panel.demo)
                while !Task.isCancelled {
                    do {
                        _ = try await client.health()
                        let value = try await client.data("overview")
                        try Task.checkCancellation(); summary = value; error = nil
                    } catch {
                        if RequestFailure.isCancellation(error) || Task.isCancelled { return }
                        self.error = error.localizedDescription
                    }
                    do { try await Task.sleep(for: .seconds(30)) } catch { return }
                }
            } catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
        }
    }
}
