import SwiftUI

struct SuiteHomeStatus: View {
    let panel: PanelAPI
    let server: Server
    @State private var summary: JSON = .null
    @State private var suite: SuiteAPI?
    @State private var error: String?
    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            if let suite { SuitePowerBotDigest(suite: suite) }
            if summary != .null {
                Label("\(summary["online"].int) / \(summary["maxPlayers"].int) Spieler online", systemImage: "person.2.fill").font(.subheadline.bold()).accessibilityIdentifier("home-online-count-" + server.id)
                if !summary["event"].string.isEmpty {
                    Label(summary["event"].string, systemImage: "calendar.badge.clock").font(.caption.bold()).foregroundStyle(.tint)
                }
                if !summary["lockdown"].string.isEmpty && summary["lockdown"].string != "off" {
                    Label("Lockdown aktiv", systemImage: "lock.shield.fill").font(.caption).foregroundStyle(.orange)
                }
                HStack(spacing: 4) {
                    if summary["scoreboard"] != .null { Text("Tag \(summary["scoreboard"]["mainDays"].string) · \(summary["scoreboard"]["mainWorld"].string)") }
                    if let date = SuitePlayerPresentation.date(summary["at"]) { Text(SuiteGermanTime.text(date)).monospacedDigit() }
                }.font(.caption).foregroundStyle(.secondary).lineLimit(1)
            } else if error == nil { Text("Serverinfos laden …").font(.caption).foregroundStyle(.secondary) }
            if let error { Label((summary == .null ? "" : "Letzter Stand · ") + error, systemImage: "arrow.clockwise.circle").font(.caption).foregroundStyle(.orange).lineLimit(2) }
        }.frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 6).task {
            do {
                let connection = SuiteConnections.shared.get(panel, server) ?? SuiteConnection(panel: panel.base.absoluteString, server: server.id, endpoint: "https://suite.demo.invalid", fingerprint: "", serverIdentity: "synthetic-suite")
                let client = try SuiteAPI(connection, token: panel.demo ? "" : nil, demo: panel.demo)
                while !Task.isCancelled {
                    do {
                        _ = try await client.health()
                        if suite == nil { suite = client }
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
