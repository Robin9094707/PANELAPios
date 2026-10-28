import SwiftUI

struct SuiteAltDashboardView: View {
    let suite: SuiteAPI
    let panel: PanelAPI
    let server: Server
    let actions: [SuiteAction]
    @State private var data: JSON = .null
    @State private var rows: [JSON] = []
    @State private var busy = false
    @State private var error: String?
    var body: some View {
        List {
            Section {
                Label("Alt-Account-Dashboard", systemImage: "person.2.badge.key.fill").font(.title2.bold()).foregroundStyle(Color.panelAccent)
                Text("Verknüpfte Konten und technische Hinweise. Ein Konto ist keine Person; gemeinsame Netze können verschiedene Menschen verbinden.").font(.footnote).foregroundStyle(.secondary)
                if data["loading"].bool { Label("Die Serveranalyse läuft. Bitte anschließend neu laden.", systemImage: "clock") }
                if busy { ProgressView("Auswertung laden …") }
                LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 12) {
                    metric("Profile mit Hinweisen", "linkedProfiles", "person.2.fill")
                    metric("Zusätzliche Konten¹", "extraAccounts", "person.badge.plus")
                    metric("Verknüpfte Gruppen", "groups", "square.stack.3d.up")
                    metric("Größte Gruppe", "largestGroup", "person.3.fill")
                    metric("Direkte Verbindungen", "directPairs", "link")
                    metric("Gespeicherte Geräte", "devices", "iphone.and.arrow.right.inward")
                }
                Text("¹ Summe der Gruppengrößen minus ein Konto je Gruppe; keine bestätigte Personenzahl.").font(.caption).foregroundStyle(.secondary)
                if let at = SuitePlayerPresentation.date(data["at"]) { LabeledContent("Stand der Analyse") { Text(at, format: .dateTime.day().month().hour().minute()) } }
            }
            Section("IP-Regeln & Reparatur") {
                NavigationLink { SuiteAltIPsView(suite: suite) } label: { Label("IP-Ausschlüsse verwalten", systemImage: "network") }
                LabeledContent("Ausgeschlossene IPs", value: String(data["excluded"].array.count))
                if !data["report"].string.isEmpty { DisclosureGroup("Letzte Reparatur") { Text(data["report"].string).font(.footnote).textSelection(.enabled) } }
                Text("Ausschlüsse entfernen ausschließlich IP-Belege. Andere IPs, Geräte, XUIDs und manuelle Bestätigungen bleiben wirksam.").font(.caption).foregroundStyle(.secondary)
            }
            Section("Die meisten verknüpften Konten") {
                ForEach(rows, id: \.["id"].string) { row in
                    NavigationLink { SuitePlayerView(suite: suite, panel: panel, server: server, player: row["id"].string, actions: actions, initialProfile: row) } label: {
                        HStack(spacing: 12) {
                            PlayerPlatformIcon(platform: PlayerPlatform.resolve(row))
                            VStack(alignment: .leading, spacing: 4) {
                                Text(row["name"].string).font(.headline)
                                Text("\(row["linked"].int) weitere Konten · \(row["devices"].int) Geräte").font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer(); Text(String(row["linked"].int)).font(.title3.bold()).foregroundStyle(Color.panelAccent)
                        }.padding(.vertical, 3)
                    }
                }
                if !busy && rows.isEmpty { Text("Keine verknüpften Profile in dieser Auswertung.").foregroundStyle(.secondary) }
                if data["next"].int >= 0 && data != .null { Button("Weitere Profile laden") { Task { await load(more: true) } }.disabled(busy) }
            }
        }.panelScreen().navigationTitle("Alt-Accounts").navigationBarTitleDisplayMode(.inline)
            .task { if data == .null { await load() } }.refreshable { await load() }.errorAlert($error)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Neu laden", systemImage: "arrow.clockwise") { Task { await load() } }.disabled(busy) } }
    }
    func metric(_ title: String, _ key: String, _ icon: String) -> some View {
        VStack(alignment: .leading, spacing: 7) { Label(title, systemImage: icon).font(.caption).foregroundStyle(.secondary); Text(data[key] == .null ? "–" : data[key].string).font(.system(.title, design: .rounded).bold()).monospacedDigit() }.frame(maxWidth: .infinity, alignment: .leading).padding(12).background(Color.panelAccent.opacity(0.08), in: RoundedRectangle(cornerRadius: 16))
    }
    func load(more: Bool = false) async {
        guard !busy else { return }; busy = true; defer { busy = false }
        do { let response = try await suite.data("alt-dashboard", query: ["offset": more ? data["next"].string : "0"]); try Task.checkCancellation(); data = response; rows = more ? SuitePlayerPresentation.merge(rows, response["items"].array) : response["items"].array }
        catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }
}

struct SuiteAltIPChange: Identifiable { let id = UUID(); let ip: String; let mode: String; let revision: String }
struct SuiteAltIPsView: View {
    let suite: SuiteAPI
    var initialIP = ""
    @State private var data: JSON = .null
    @State private var rows: [JSON] = []
    @State private var ip = ""
    @State private var search = ""
    @State private var busy = false
    @State private var error: String?
    @State private var change: SuiteAltIPChange?
    var visible: [JSON] { rows.filter { search.isEmpty || $0["ip"].string.localizedCaseInsensitiveContains(search) } }
    var editable: Bool { suite.scope == "full" && !suite.demo && data != .null }
    var body: some View {
        List {
            Section("IP direkt eingeben") {
                TextField("IPv4 oder IPv6", text: $ip).textInputAutocapitalization(.never).autocorrectionDisabled()
                Button("Von Alt-Erkennung ausschließen", systemImage: "shield.lefthalf.filled") { prepare(ip, "exclude") }.disabled(!editable || ip.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                Text("Standardmäßig ist 82.165.45.159 ausgeschlossen. Die IP bleibt sichtbar, wird aber nicht für Alt-Verknüpfungen, Vererbung oder Suite-IP-Banns genutzt. Beliebig viele weitere genaue IP-Adressen sind möglich.").font(.caption).foregroundStyle(.secondary)
            }
            if !data["report"].string.isEmpty { Section("Letzte Reparatur") { Text(data["report"].string).font(.footnote).textSelection(.enabled) } }
            Section("Bekannte und ausgeschlossene IPs") {
                if busy { ProgressView() }
                ForEach(visible, id: \.["id"].string) { row in
                    VStack(alignment: .leading, spacing: 9) {
                        Text(row["ip"].string).font(.headline.monospaced()).textSelection(.enabled)
                        Label(row["excluded"].bool ? "Für Alt-Erkennung ausgeschlossen" : "Wird als IP-Beleg berücksichtigt", systemImage: row["excluded"].bool ? "shield.lefthalf.filled" : "link").font(.caption).foregroundStyle(row["excluded"].bool ? .orange : .secondary)
                        Text("Bei \(row["profiles"].int) Profilen gespeichert").font(.caption).foregroundStyle(.secondary)
                        HStack {
                            CopyButton(text: row["ip"].string, title: "IP kopieren")
                            Spacer()
                            Menu {
                                Button(row["excluded"].bool ? "IP wieder freigeben" : "IP ausschließen") { prepare(row["ip"].string, row["excluded"].bool ? "restore" : "exclude") }
                                Button("Verknüpfungen erneut reparieren") { prepare(row["ip"].string, "repair") }
                                Button("Gespeicherte IP-Beobachtungen löschen", role: .destructive) { prepare(row["ip"].string, "purge") }
                            } label: { Label("Verwalten", systemImage: "ellipsis.circle") }.disabled(!editable)
                        }
                    }.padding(.vertical, 6)
                }
                if data["next"].int >= 0 && data != .null { Button("Weitere IPs laden") { Task { await load(more: true) } }.disabled(busy) }
            }
            Section {
                Text("Ausschließen ist umkehrbar. Vor der Reparatur werden die betroffenen Zustandsdateien gesichert. Freigeben aktiviert künftige IP-Belege wieder. Löschen entfernt gespeicherte Profil-/Geräte-IP-Beobachtungen; Audit-Protokolle bleiben erhalten. Eigenständige manuelle Banns werden nicht aufgehoben.").font(.footnote).foregroundStyle(.secondary)
            }
        }.panelScreen().navigationTitle("Alt-IP-Regeln").navigationBarTitleDisplayMode(.inline)
            .searchable(text: $search, prompt: "Gespeicherte IP suchen").onSubmit(of: .search) { Task { await load() } }
            .task { ip = initialIP; if data == .null { await load() } }.refreshable { await load() }.errorAlert($error)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Neu laden", systemImage: "arrow.clockwise") { Task { await load() } }.disabled(busy) } }
            .sheet(item: $change, onDismiss: { Task { await load() } }) { request in
                NavigationStack { SuiteActionView(suite: suite, action: .make("alts.ip", "Alt-IP-Regel ändern"), preset: ["ip": .string(request.ip), "mode": .string(request.mode), "expected": .string(request.revision)]) }
            }
    }
    func prepare(_ ip: String, _ mode: String) { change = SuiteAltIPChange(ip: ip.trimmingCharacters(in: .whitespacesAndNewlines), mode: mode, revision: data["revision"].string) }
    func load(more: Bool = false) async {
        guard !busy else { return }; busy = true; defer { busy = false }
        do { let response = try await suite.data("alt-ips", query: ["q": search, "offset": more ? data["next"].string : "0"]); try Task.checkCancellation(); data = response; rows = more ? SuitePlayerPresentation.merge(rows, response["items"].array) : response["items"].array }
        catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }
}
