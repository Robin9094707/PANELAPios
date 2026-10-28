import SwiftUI

struct SuiteNetworkContent: View {
    let network: JSON
    var location: JSON { network["location"] }
    var mapURL: URL? {
        let place = [location["postal"].string, location["city"].string, location["region"].string, location["country"].string].filter { !$0.isEmpty }.joined(separator: " ")
        guard !place.isEmpty else { return nil }
        var url = URLComponents(string: "https://maps.apple.com/")!
        url.queryItems = [URLQueryItem(name: "q", value: place)]
        return url.url
    }
    var body: some View {
        if network == .null { Text("Netzwerkprofil benötigt Admin Suite 9.1 oder neuer.").font(.caption).foregroundStyle(.secondary) }
        else {
            if !network["ip"].string.isEmpty {
                LabeledContent("Letzte Roh-IP", value: network["ip"].string).textSelection(.enabled)
                CopyButton(text: network["ip"].string, title: "Roh-IP kopieren")
            }
            Label(network["status"].string, systemImage: network["vpn"].string == "detected" ? "shield.slash" : "network").font(.footnote)
            if network["pending"].bool { ProgressView("Diagnose im Hintergrund …").font(.caption) }
            if location != .null {
                ForEach([("country", "Land"), ("region", "Region"), ("city", "Ungefähre Stadt"), ("postal", "Ungefähre PLZ"), ("isp", "Internetanbieter")], id: \.0) { key, title in
                    if !location[key].string.isEmpty { LabeledContent(title, value: location[key].string).textSelection(.enabled) }
                }
                if let date = SuitePlayerPresentation.date(location["at"]) { LabeledContent("Cache-Stand") { Text(date, format: .dateTime.day().month().hour().minute()) } }
                if let mapURL { Link("Ungefähren Ort in Karten öffnen", destination: mapURL) }
                Text("Quelle: ipwho.is · 7 Tage Cache. IP-Orte können deutlich abweichen. Gemeinsame IPs und Orte beweisen keine gemeinsame Person.").font(.caption).foregroundStyle(.secondary)
            }
        }
    }
}

struct SuiteSettingChange: Identifiable { let id = UUID(); let setting: JSON; let value: String }

struct SuiteVPNPlatformView: View {
    let suite: SuiteAPI
    @State private var settings: [JSON] = []
    @State private var platform = "both"
    @State private var geo = false
    @State private var selected: SuiteSettingChange?
    @State private var error: String?
    var body: some View {
        Form {
            Section("VPN-Erkennung") {
                Picker("Spieler prüfen", selection: $platform) {
                    Text("Java und Bedrock (Standard)").tag("both")
                    Text("Nur Java").tag("java")
                    Text("Nur Bedrock").tag("bedrock")
                }
                Text("Ausgeschlossene Plattformen passieren den VPN-Check ohne Provider-Anfrage und ohne VPN-Cooldown. Der globale Schutzschalter und bestehende Ausnahmen gelten weiterhin.").font(.caption).foregroundStyle(.secondary)
                Button("VPN-Auswahl speichern") { choose("vpn-protection.target-platform", value: platform) }.disabled(!canEdit || !settings.contains { $0["id"].string == "vpn-protection.target-platform" })
            }
            Section("Ungefährer IP-Standort") {
                Toggle("Standortdiagnose aktivieren", isOn: $geo)
                Text("Öffentliche IPs werden über HTTPS bei ipwho.is abgefragt. Maximal 900 Anfragen pro Tag, 7 Tage Cache. VPN-/Proxy-Hinweise und unbekannte VPN-Ergebnisse blenden den Standort aus.").font(.caption).foregroundStyle(.secondary)
                Button("Standorteinstellung speichern") { choose("client-control.geo.enabled", value: String(geo)) }.disabled(!canEdit)
            }
            if settings.isEmpty { Text("Einstellungen laden oder Admin Suite 9.1 installieren.").font(.footnote).foregroundStyle(.secondary) }
        }.panelScreen().navigationTitle("VPN & IP-Diagnose").task { await load() }.refreshable { await load() }.errorAlert($error)
            .sheet(item: $selected, onDismiss: { Task { await load() } }) { change in
                NavigationStack { SuiteActionView(suite: suite, action: .make("settings.update", "Einstellung speichern"), preset: ["expected": change.setting["revision"], "key": change.setting["id"], "value": .string(change.value)]) }
            }
    }
    var canEdit: Bool { suite.scope == "full" && !suite.demo }
    func choose(_ key: String, value: String) { guard let setting = settings.first(where: { $0["id"].string == key }) else { error = "Einstellung fehlt. Bitte Admin Suite 9.1 installieren."; return }; selected = SuiteSettingChange(setting: setting, value: value) }
    func load() async { do { settings = try await suite.data("settings")["items"].array; platform = settings.first { $0["id"].string == "vpn-protection.target-platform" }?["value"].string ?? "both"; geo = settings.first { $0["id"].string == "client-control.geo.enabled" }?["value"].bool ?? false } catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } } }
}
