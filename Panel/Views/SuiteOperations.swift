import SwiftUI

struct SuiteOperationsView: View {
    let suite: SuiteAPI
    var actions: [SuiteAction] = []
    @State private var data: [String: JSON] = [:]
    @State private var error: String?
    @State private var busy = false
    @State private var updated: Date?
    var body: some View {
        List {
            Section {
                Label(suite.demo ? "Demo · synthetische Serverdaten" : "Live vom Minecraft-Server", systemImage: "server.rack").font(.headline)
                if let updated { LabeledContent("Aktualisiert", value: updated.formatted(date: .omitted, time: .standard)) }
                if busy { ProgressView("Serverdaten laden …") }
            }
            if let overview = data["overview"] {
                Section("Serverleistung") {
                    row("TPS", overview["tps"].double.formatted(.number.precision(.fractionLength(1))))
                    row("Tickdauer", overview["mspt"].double < 0 ? "Nicht verfügbar" : overview["mspt"].double.formatted(.number.precision(.fractionLength(2))) + " ms")
                    row("Arbeitsspeicher", bytes(overview["ramUsed"].double) + " / " + bytes(overview["ramMax"].double))
                    row("Laufzeit", duration(overview["uptime"].double))
                    row("Online", "\(overview["online"].int) / \(overview["maxPlayers"].int)")
                }
            }
            if let stats = data["statistics"] {
                Section("Gespeicherte Community-Statistik") {
                    row("Profile", stats["profiles"].string)
                    row("Spielzeit insgesamt", duration(stats["playMillis"].double))
                    row("Davon AFK", duration(stats["afkMillis"].double))
                    row("Sitzungen", stats["sessions"].string)
                    row("Aktiv · 24 Stunden", stats["recent24h"].string)
                    row("Neu · 24 Stunden", stats["new24h"].string)
                    row("Aktive Banns / Mutes", "\(stats["bans"].int) / \(stats["mutes"].int)")
                    row("Warnungen", stats["warnings"].string)
                    Text("Zeiten und Sitzungen stammen aus gespeicherten Suite-Profilen. Nicht erfasste frühere Spielzeit wird nicht rekonstruiert.").font(.caption).foregroundStyle(.secondary)
                }
            }
            if let logs = data["log-status"] {
                Section("Private Discord-Protokolle") {
                    if logs["available"] == .bool(false) { Text("Client-Protokollmodul nicht aktiv") }
                    else {
                        row("Wartende Einträge", logs["pending"].int < 0 ? "Nicht verfügbar" : logs["pending"].string)
                        row("Gesammelte Beobachtungen", logs["observations"].string)
                        row("Speicher", bytes(logs["bytes"].double))
                        row("Aktive Kanäle", logs["enabledChannels"].string)
                        Text(logs["discord"].string).fixedSize(horizontal: false, vertical: true)
                        Text(logs["deliveryState"].string).font(.caption).foregroundStyle(.secondary)
                        if !logs["storageError"].string.isEmpty { Label(logs["storageError"].string, systemImage: "exclamationmark.triangle").foregroundStyle(.orange) }
                        if logs["lastSuccess"].double > 0 { row("Letzter Versand", date(logs["lastSuccess"])) }
                        if logs["nextAttempt"].double > 0 { row("Nächster Versuch", date(logs["nextAttempt"])) }
                    }
                    if let action = actions.first(where: { $0.id == "logs.retry" }) {
                        NavigationLink { SuiteActionView(suite: suite, action: action) } label: { Label("Versand erneut versuchen", systemImage: "arrow.clockwise") }
                    }
                    Text("Bei fehlenden Rechten Kanal-ID, Bot-Zugriff und private Freigabe für konfigurierte Admin-Rollen prüfen. Gespeicherte Protokolle werden automatisch erneut versendet.").font(.caption).foregroundStyle(.secondary)
                }
            }
            Section("Welten") {
                ForEach(data["worlds"]?["items"].array ?? [], id: \.["id"].string) { world in
                    DisclosureGroup(world["name"].string) {
                        row("Umgebung", world["environment"].string)
                        row("Spieler / Chunks", "\(world["players"].int) / \(world["chunks"].int)")
                        row("Welttage", world["days"].string)
                        row("Schwierigkeit", world["difficulty"].string)
                        row("PvP", world["pvp"].bool ? "An" : "Aus")
                        row("Wetter", world["thunder"].bool ? "Gewitter" : world["storm"].bool ? "Regen" : "Klar")
                        if let action = actions.first(where: { $0.id == "world.update" }) {
                            NavigationLink { SuiteActionView(suite: suite, action: action, preset: ["world": world["id"]]) } label: { Label("Welteinstellung ändern", systemImage: "slider.horizontal.3") }
                        }
                    }
                }
            }
            Section("Plugins") {
                ForEach(data["plugins"]?["items"].array ?? [], id: \.["id"].string) { plugin in
                    HStack { VStack(alignment: .leading) { Text(plugin["name"].string); Text(plugin["version"].string).font(.caption).foregroundStyle(.secondary) }; Spacer(); Image(systemName: plugin["enabled"].bool ? "checkmark.circle.fill" : "pause.circle").foregroundStyle(plugin["enabled"].bool ? Color.green : Color.secondary) }
                }
            }
            Section { Button("Alle Daten aktualisieren", systemImage: "arrow.clockwise") { Task { await load() } }.disabled(busy) }
        }.labelStyle(SuiteWrappingLabelStyle()).panelScreen().navigationTitle("Server & Betrieb").navigationBarTitleDisplayMode(.inline)
            .task { await load() }.refreshable { await load() }.errorAlert($error)
    }
    func row(_ title: String, _ text: String) -> some View { LabeledContent { Text(text).multilineTextAlignment(.trailing).textSelection(.enabled) } label: { Text(title).fixedSize(horizontal: false, vertical: true) } }
    func date(_ value: JSON) -> String { Date(timeIntervalSince1970: value.double / 1000).formatted() }
    func duration(_ milliseconds: Double) -> String { let seconds = max(0, milliseconds / 1000); return "\(Int(seconds / 3600)) h \(Int(seconds.truncatingRemainder(dividingBy: 3600) / 60)) min" }
    func load() async {
        guard !busy else { return }; busy = true; defer { busy = false }
        do {
            var next: [String: JSON] = [:]
            for name in ["overview", "statistics", "log-status", "worlds", "plugins"] { next[name] = try await suite.data(name) }
            try Task.checkCancellation(); data = next; updated = Date(); error = nil
        } catch { if !RequestFailure.isCancellation(error) && !Task.isCancelled { self.error = error.localizedDescription } }
    }
}
