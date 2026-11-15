import SwiftUI

// MARK: - Bann-Zentrale (5.13.0, Admin Suite 10.20.0)

/// All bans the Suite enforces, grouped into cases: one main ban plus every blocked alternative account.
struct SuiteBanCenterView: View {
    let suite: SuiteAPI
    @State private var data: JSON = .null
    @State private var tab = "cases"
    @State private var filter = "all"
    @State private var search = ""
    @State private var error: String?
    @State private var loaded = false
    @State private var lifting: SuiteBanLiftTarget?
    @State private var ipSheet: SuiteBanIPTarget?
    @State private var showMigration = false

    private var manage: Bool { data["manage"].bool && !suite.demo }
    private var cases: [JSON] { data["items"].array }

    var body: some View {
        List {
            SuiteBanCenterHeader(kpis: data["kpis"])
            migrationHint
            Section {
                Picker("Bereich", selection: $tab) {
                    Text("Fälle").tag("cases")
                    Text("IP-Sperren").tag("ips")
                    Text("Verlauf").tag("history")
                }.pickerStyle(.segmented)
            }
            switch tab {
            case "ips": ipSection
            case "history": historySection
            default: casesSection
            }
        }
        .panelScreen()
        .navigationTitle("Bann-Zentrale")
        .searchable(text: $search, prompt: "Name, Grund, Fall-ID oder IP")
        .onSubmit(of: .search) { Task { await load() } }
        .onChange(of: search) { _, value in if value.isEmpty { Task { await load() } } }
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Menu {
                    Picker("Filter", selection: $filter) {
                        ForEach(SuiteBanText.filters, id: \.self) { Text(SuiteBanText.filterTitle($0)).tag($0) }
                    }
                    if suite.features.contains(SuiteBanText.migrationFeature) {
                        Button { showMigration = true } label: { Label("LiteBans-Migration", systemImage: "arrow.down.doc.fill") }
                    }
                } label: { Label("Filter", systemImage: filter == "all" ? "line.3.horizontal.decrease.circle" : "line.3.horizontal.decrease.circle.fill") }
            }
        }
        .onChange(of: filter) { _, _ in Task { await load() } }
        .navigationDestination(isPresented: $showMigration) { SuiteBanMigrationView(suite: suite) }
        .sheet(item: $lifting) { target in
            SuiteBanLiftSheet(suite: suite, target: target) { await load() }
        }
        .sheet(item: $ipSheet) { target in
            SuiteBanIPSheet(suite: suite, initialIP: target.ip) { await load() }
        }
        .task { await load(); while !Task.isCancelled { do { try await Task.sleep(for: .seconds(30)) } catch { return }; await load(quiet: true) } }
        .refreshable { await load() }
        .errorAlert($error)
    }

    @ViewBuilder private var migrationHint: some View {
        let migration = data["migration"]
        if migration != .null && (migration["pending"].int > 0 || migration["litebansEnabled"].bool) {
            Section {
                NavigationLink { SuiteBanMigrationView(suite: suite) } label: {
                    Label {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(migration["litebansEnabled"].bool ? "LiteBans läuft noch" : "LiteBans-Migration").font(.headline)
                            Text(migration["pending"].int > 0 ? "\(migration["pending"].int) Fälle warten auf deine Auswahl" : "Jetzt übernehmen und abschalten").font(.caption).foregroundStyle(.secondary)
                        }
                    } icon: { Image(systemName: "arrow.down.doc.fill").foregroundStyle(.orange) }
                }
            }
        }
    }

    @ViewBuilder private var casesSection: some View {
        Section {
            ForEach(cases, id: \.["id"].string) { row in
                NavigationLink { SuiteBanCaseView(suite: suite, initial: row, manage: manage) { await load() } } label: { SuiteBanCaseRow(row: row) }
                    .swipeActions(edge: .trailing) {
                        if manage { Button("Alle aufheben", systemImage: "lock.open.fill") { lifting = SuiteBanLiftTarget(caseRow: row, member: nil) }.tint(.green) }
                    }
                    .contextMenu {
                        if manage { Button("Ganzen Fall aufheben", systemImage: "lock.open.fill") { lifting = SuiteBanLiftTarget(caseRow: row, member: nil) } }
                        Button("Fall-ID kopieren", systemImage: "doc.on.doc") { UIPasteboard.general.string = row["id"].string }
                    }
            }
            if cases.isEmpty {
                ContentUnavailableView(loaded ? (search.isEmpty && filter == "all" ? "Niemand ist gesperrt" : "Keine passenden Banns") : "Lädt …", systemImage: "checkmark.shield",
                    description: Text("Neue Banns: Spielerprofil → Aktionen oder im Spiel /ban."))
            }
        } header: { Text(filter == "all" ? "Aktive Fälle" : SuiteBanText.filterTitle(filter)) }
        footer: { Text("Ein Fall ist ein Hauptbann mit allen zugehörigen Alt-Konten. Wischen zum Aufheben des ganzen Falls, antippen für einzelne Konten.") }
    }

    @ViewBuilder private var ipSection: some View {
        Section {
            Button { ipSheet = SuiteBanIPTarget(ip: "") } label: { Label("IP-Fehlbann prüfen und aufheben", systemImage: "wifi.exclamationmark") }.disabled(!manage)
        } footer: { Text("Wenn wegen einer gemeinsamen IP (Proxy, Geyser, Schule, Mobilfunk) plötzlich alle gesperrt sind: alles aufheben, was an dieser IP hängt – und die IP auf Wunsch ausschließen.") }
        Section("Aktive IP-Sperren") {
            ForEach(data["ipBlocks"].array, id: \.["ip"].string) { block in
                VStack(alignment: .leading, spacing: 3) {
                    Text(block["ip"].string).font(.body.monospaced())
                    Text("für \(block["source"].string) · von \(block["by"].string) · \(block["accounts"].int) Konten kamen je über diese IP").font(.caption).foregroundStyle(.secondary)
                }
                .swipeActions { if manage { Button("Fehlbann", systemImage: "lock.open") { ipSheet = SuiteBanIPTarget(ip: block["ip"].string) }.tint(.green) } }
            }
            if data["ipBlocks"].array.isEmpty { Text("Keine IP-Sperren").foregroundStyle(.secondary) }
        }
        Section {
            ForEach(data["excludedIps"].array, id: \.string) { ip in Label(ip.string, systemImage: "checkmark.shield").font(.body.monospaced()) }
            if data["excludedIps"].array.isEmpty { Text("Keine").foregroundStyle(.secondary) }
        } header: { Text("Ausgeschlossene IPs") } footer: { Text("Zählen nie für IP-Sperren oder die Alt-Erkennung.") }
    }

    @ViewBuilder private var historySection: some View {
        Section {
            ForEach(Array(data["history"].array.enumerated()), id: \.offset) { _, row in SuiteBanHistoryRow(row: row) }
            if data["history"].array.isEmpty { Text("Noch kein Verlauf").foregroundStyle(.secondary) }
        } header: { Text("Verlauf") } footer: { Text("Aufgehobene, abgelaufene und aus LiteBans übernommene ältere Sperren.") }
    }

    private func load(quiet: Bool = false) async {
        do { data = try await suite.request("ban-center", query: ["filter": filter, "q": search]); loaded = true }
        catch { if !quiet && !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }
}

struct SuiteBanLiftTarget: Identifiable {
    let id = UUID()
    let caseRow: JSON
    let member: JSON?
}

struct SuiteBanIPTarget: Identifiable {
    let id = UUID()
    let ip: String
}

struct SuiteBanCenterHeader: View {
    let kpis: JSON
    var body: some View {
        Section {
            HStack(spacing: 14) {
                Image(systemName: kpis["cases"].int > 0 ? "hand.raised.fill" : "checkmark.shield.fill")
                    .font(.system(size: 26)).foregroundStyle(.white).frame(width: 54, height: 54)
                    .background(LinearGradient(colors: [kpis["cases"].int > 0 ? Color.red : Color.green, Color.panelAccent], startPoint: .topLeading, endPoint: .bottomTrailing), in: RoundedRectangle(cornerRadius: 15))
                VStack(alignment: .leading, spacing: 3) {
                    Text(kpis["cases"].int == 1 ? "1 aktiver Fall" : "\(kpis["cases"].int) aktive Fälle").font(.headline)
                    Text("\(kpis["main"].int) Hauptbanns · \(kpis["alts"].int) Alt-Konten").font(.subheadline).foregroundStyle(.secondary)
                }
            }.padding(.vertical, 4)
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 140), spacing: 10)], spacing: 10) {
                SuiteBanTile(title: "Zeitlich begrenzt", value: kpis["temporary"].int, symbol: "clock.fill", tint: .purple, hint: kpis["expiringSoon"].int > 0 ? "\(kpis["expiringSoon"].int) in 24 Std." : "")
                SuiteBanTile(title: "Aus LiteBans", value: kpis["litebans"].int, symbol: "arrow.down.doc.fill", tint: .blue, hint: "")
                SuiteBanTile(title: "IP-Sperren", value: kpis["ipBlocks"].int, symbol: "wifi.slash", tint: .red, hint: "")
                SuiteBanTile(title: "IP ausgeschlossen", value: kpis["excludedIps"].int, symbol: "checkmark.shield.fill", tint: .green, hint: "")
            }.padding(.vertical, 4)
        }
    }
}

struct SuiteBanTile: View {
    let title: String
    let value: Int
    let symbol: String
    let tint: Color
    let hint: String
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Label(title, systemImage: symbol).font(.caption.weight(.semibold)).foregroundStyle(tint)
            Text("\(value)").font(.title2.bold()).monospacedDigit()
            if !hint.isEmpty { Text(hint).font(.caption2).foregroundStyle(.secondary) }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(10)
        .background(tint.opacity(0.1), in: RoundedRectangle(cornerRadius: 12))
    }
}

struct SuiteBanCaseRow: View {
    let row: JSON
    private var avatar: JSON { .object(["id": row["main"], "name": row["mainName"]]) }
    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            SuitePlayerAvatar(row: avatar, size: 40)
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    Text(row["mainName"].string.isEmpty ? "?" : row["mainName"].string).font(.headline)
                    Text(row["id"].string).font(.caption2.monospaced()).foregroundStyle(.secondary)
                }
                Text(row["reason"].string.isEmpty ? "ohne Grund" : row["reason"].string).font(.subheadline).lineLimit(2)
                SuiteBanTags(row: row)
            }
            Spacer(minLength: 0)
        }.padding(.vertical, 2)
    }
}

struct SuiteBanTags: View {
    let row: JSON
    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                PowerBotTag(text: SuiteBanText.until(row), tint: row["permanent"].bool ? .red : .orange)
                if row["alts"].int > 0 { PowerBotTag(text: row["alts"].int == 1 ? "1 Alt-Konto" : "\(row["alts"].int) Alt-Konten", tint: .orange) }
                if row["source"].string == "litebans" { PowerBotTag(text: "LiteBans", tint: .blue) }
                if !row["ultraGroups"].array.isEmpty { PowerBotTag(text: "Alt-Gruppe", tint: .purple) }
                if row["ipBlocked"].bool { PowerBotTag(text: "IP gesperrt", tint: .red) }
                if row["ipExcluded"].bool { PowerBotTag(text: "IP ausgeschlossen", tint: .green) }
            }
        }
    }
}

struct SuiteBanMemberRow: View {
    let member: JSON
    private var avatar: JSON { .object(["id": member["uuid"], "name": member["name"], "online": member["online"], "client": member["client"]]) }
    var body: some View {
        HStack(spacing: 12) {
            SuitePlayerAvatar(row: avatar, size: 34)
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text(member["name"].string).font(.body.weight(.semibold))
                    PowerBotTag(text: SuiteBanText.role(member["role"].string), tint: member["role"].string == "alt" ? .orange : .red)
                    if member["online"].bool { PowerBotTag(text: "online", tint: .blue) }
                }
                if !member["via"].string.isEmpty { Text(member["via"].string).font(.caption).foregroundStyle(.secondary) }
            }
        }
    }
}

/// One case: main account, alt accounts and the lift options.
struct SuiteBanCaseView: View {
    let suite: SuiteAPI
    let initial: JSON
    let manage: Bool
    let reload: () async -> Void
    @State private var lifting: SuiteBanLiftTarget?
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: 6) {
                    Text(initial["reason"].string.isEmpty ? "ohne Grund" : initial["reason"].string).font(.title3.bold())
                    SuiteBanTags(row: initial)
                }.padding(.vertical, 4)
                LabeledContent("Fall", value: initial["id"].string)
                LabeledContent("Hauptkonto", value: initial["mainName"].string)
                LabeledContent("Gesperrt von", value: initial["actor"].string.isEmpty ? "–" : initial["actor"].string)
                if initial["created"].double > 0 { LabeledContent("Seit", value: SuiteBanText.date(initial["created"].double)) }
                LabeledContent("Dauer", value: SuiteBanText.until(initial))
                LabeledContent("Herkunft", value: SuiteBanText.source(initial["source"].string))
                if !initial["ip"].string.isEmpty { LabeledContent("IP", value: initial["ip"].string + (initial["ipBlocked"].bool ? " (gesperrt)" : "")) }
            }
            Section {
                ForEach(initial["members"].array, id: \.["uuid"].string) { member in
                    SuiteBanMemberRow(member: member)
                        .swipeActions(edge: .trailing) {
                            if manage { Button("Entsperren", systemImage: "lock.open.fill") { lifting = SuiteBanLiftTarget(caseRow: initial, member: member) }.tint(.green) }
                        }
                        .contextMenu {
                            if manage { Button(member["role"].string == "alt" ? "Dieses Alt-Konto entsperren …" : "Hauptbann aufheben …", systemImage: "lock.open") { lifting = SuiteBanLiftTarget(caseRow: initial, member: member) } }
                            Button("UUID kopieren", systemImage: "doc.on.doc") { UIPasteboard.general.string = member["uuid"].string }
                        }
                }
            } header: { Text("Konten (\(initial["accounts"].int))") } footer: { Text("Wischen oder lange drücken: nur dieses Konto entsperren oder alle.") }
            if manage {
                Section {
                    Button { lifting = SuiteBanLiftTarget(caseRow: initial, member: nil) } label: { Label("Ganzen Fall aufheben", systemImage: "lock.open.fill") }
                }
            }
        }
        .panelScreen()
        .navigationTitle(initial["mainName"].string)
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: $lifting) { target in
            SuiteBanLiftSheet(suite: suite, target: target) { await reload(); dismiss() }
        }
    }
}

/// Lift dialog: only this account or the whole case (main ban, all alt accounts, alt group, IP block).
struct SuiteBanLiftSheet: View {
    let suite: SuiteAPI
    let target: SuiteBanLiftTarget
    let done: () async -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var all = true
    @State private var note = ""
    @State private var busy = false
    @State private var error: String?
    @State private var result: JSON = .null

    private var member: JSON? { target.member }
    private var isAlt: Bool { member?["role"].string == "alt" }
    private var caseRow: JSON { target.caseRow }
    private var count: Int { caseRow["members"].array.count }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(member?["name"].string ?? caseRow["mainName"].string).font(.title3.bold())
                        Text("Fall \(caseRow["id"].string) · \(caseRow["reason"].string)").font(.caption).foregroundStyle(.secondary)
                    }
                }
                if let member {
                    Section {
                        Picker("Umfang", selection: $all) {
                            Text("Nur \(member["name"].string)").tag(false)
                            Text("Alle \(count) Konten").tag(true)
                        }.pickerStyle(.inline).labelsHidden()
                    } header: { Text("Was soll entsperrt werden?") } footer: {
                        Text(all ? "Hauptbann, alle Alt-Konten, Alt-Gruppe, IP-Sperre und Identitätssperren dieses Falls werden aufgehoben."
                             : isAlt ? "Nur dieses Alt-Konto wird frei. \(caseRow["mainName"].string) und die übrigen Konten bleiben gesperrt; die Alt-Erkennung erfasst dieses Konto nicht erneut."
                             : "Nur der Hauptbann wird aufgehoben; die verknüpften Alt-Konten bleiben gesperrt.")
                    }
                } else {
                    Section { Text("Alle \(count) Konten dieses Falls werden entsperrt – inklusive Alt-Gruppe und IP-Sperre.").font(.subheadline) }
                }
                Section("Betroffene Konten") {
                    ForEach(SuiteBanText.affected(caseRow, member: member?["uuid"].string, all: all || member == nil), id: \.self) { name in Label(name, systemImage: "lock.open") }
                }
                Section("Notiz (optional)") { TextField("Nur fürs Team sichtbar", text: $note, axis: .vertical).lineLimit(1...3) }
                if result != .null {
                    Section { Label(result["description"].string, systemImage: "checkmark.circle.fill").foregroundStyle(.green) }
                }
            }
            .navigationTitle(isAlt ? "Alt-Konto entsperren" : "Bann aufheben")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(result == .null ? "Abbrechen" : "Fertig") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    if result == .null { Button("Entsperren") { Task { await lift() } }.disabled(busy || suite.demo) }
                }
            }
            .onAppear { all = member == nil || !isAlt }
            .errorAlert($error)
        }
        .presentationDetents([.medium, .large])
    }

    private func lift() async {
        busy = true; defer { busy = false }
        let player = member?["uuid"].string ?? ""
        do {
            result = try await suite.post("ban-center", SuiteBanText.liftPayload(caseID: caseRow["id"].string, player: player, all: all || member == nil, note: note))
            Feedback.success()
            await done()
            dismiss()
        } catch { self.error = error.localizedDescription }
    }
}

/// Mistaken IP ban (shared proxy/Geyser/school IP): preview what hangs on the IP, then lift it all.
struct SuiteBanIPSheet: View {
    let suite: SuiteAPI
    let initialIP: String
    let done: () async -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var ip = ""
    @State private var preview: JSON = .null
    @State private var exclude = true
    @State private var error: String?
    @State private var busy = false
    @State private var confirm = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("IP-Adresse, z. B. 82.165.45.159", text: $ip).keyboardType(.numbersAndPunctuation).textInputAutocapitalization(.never).autocorrectionDisabled().font(.body.monospaced())
                    Button("Prüfen") { Task { await check() } }.disabled(ip.isEmpty || busy)
                } footer: { Text("Zeigt alle Banns, IP-Sperren und Alt-Gruppen-Anker, die an dieser IP hängen.") }
                if preview != .null {
                    Section("Was hängt an \(preview["ip"].string)?") {
                        LabeledContent("Gesperrte Konten", value: "\(preview["accounts"].array.count)")
                        LabeledContent("Konten über diese IP", value: "\(preview["seenAccounts"].int)")
                        LabeledContent("IP-Sperre aktiv", value: preview["ipBlocked"].bool ? "Ja" : "Nein")
                        LabeledContent("Identitätssperren", value: "\(preview["identityBans"].int)")
                        LabeledContent("Alt-Gruppen-Anker", value: "\(preview["groupAnchors"].int)")
                        LabeledContent("Bereits ausgeschlossen", value: preview["excluded"].bool ? "Ja" : "Nein")
                        ForEach(preview["accounts"].array, id: \.["uuid"].string) { row in
                            HStack { Text(row["name"].string); Spacer(); PowerBotTag(text: SuiteBanText.role(row["role"].string), tint: row["role"].string == "alt" ? .orange : .red) }
                        }
                    }
                    if preview["seenAccounts"].int > 5 {
                        Section { Label("Über diese IP kamen \(preview["seenAccounts"].int) Konten – vermutlich eine geteilte Adresse. Ausschließen wird empfohlen.", systemImage: "exclamationmark.triangle.fill").foregroundStyle(.orange) }
                    }
                    Section {
                        Toggle("IP dauerhaft ausschließen", isOn: $exclude).disabled(suite.scope != "full")
                    } footer: { Text(suite.scope == "full" ? "Keine IP-Sperren und keine Alt-Erkennung mehr über diese IP. Jederzeit unter Alt-Accounts → IP-Regeln rückgängig zu machen." : "Ausschließen braucht Vollzugriff; aufheben geht trotzdem.") }
                    Section {
                        Button(role: .destructive) { confirm = true } label: { Label("Alles über diese IP aufheben", systemImage: "lock.open.fill") }.disabled(busy || suite.demo)
                    }
                }
            }
            .navigationTitle("IP-Fehlbann")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Schließen") { dismiss() } } }
            .confirmationDialog("Alles über \(preview["ip"].string) aufheben?", isPresented: $confirm, titleVisibility: .visible) {
                Button("Aufheben", role: .destructive) { Task { await lift() } }
            } message: { Text("Konten mit eigenem, nicht IP-bezogenem Bann bleiben gesperrt.") }
            .task { ip = initialIP; if !initialIP.isEmpty { await check() } }
            .errorAlert($error)
        }
    }

    private func check() async {
        busy = true; defer { busy = false }
        do { preview = try await suite.post("ban-center", ["operation": .string("ip-preview"), "ip": .string(ip.trimmingCharacters(in: .whitespaces))]) }
        catch { self.error = error.localizedDescription }
    }

    private func lift() async {
        busy = true; defer { busy = false }
        let body: [String: JSON] = ["operation": .string("ip-lift"), "ip": .string(preview["ip"].string), "exclude": .string(exclude && suite.scope == "full" ? "true" : "false"), "confirmed": .bool(true)]
        do { _ = try await suite.post("ban-center", body); Feedback.success(); await done(); dismiss() }
        catch { self.error = error.localizedDescription }
    }
}

struct SuiteBanHistoryRow: View {
    let row: JSON
    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack(spacing: 6) {
                Text(row["name"].string.isEmpty ? "?" : row["name"].string).font(.body.weight(.semibold))
                PowerBotTag(text: SuiteBanText.historyKind(row), tint: row["type"].string == "litebans" ? .blue : .green)
            }
            if !row["reason"].string.isEmpty { Text(row["reason"].string).font(.subheadline) }
            Text(details).font(.caption).foregroundStyle(.secondary)
        }
    }
    private var details: String {
        var parts: [String] = []
        if !row["by"].string.isEmpty { parts.append("gesperrt von " + row["by"].string) }
        if row["since"].double > 0 { parts.append(SuiteBanText.date(row["since"].double)) }
        if !row["liftedBy"].string.isEmpty { parts.append("aufgehoben von " + row["liftedBy"].string) }
        if !row["note"].string.isEmpty { parts.append(row["note"].string) }
        return parts.joined(separator: " · ")
    }
}

// MARK: - Spielerprofil

/// Ban status in the player profile: main ban or blocked alt account, the whole case and lift options.
struct SuitePlayerBanSection: View {
    let suite: SuiteAPI
    let player: String
    @State private var status: JSON = .null
    @State private var lifting: SuiteBanLiftTarget?
    @State private var error: String?

    private var caseRow: JSON { status["case"] }
    private var me: JSON? { caseRow["members"].array.first { $0["uuid"].string == player } }

    var body: some View {
        Section {
            if status == .null {
                HStack { ProgressView(); Text("Sperrstatus wird geladen …").foregroundStyle(.secondary) }
            } else if !status["banned"].bool {
                Label("Nicht gesperrt", systemImage: "checkmark.shield.fill").foregroundStyle(.green)
                if status["mute"] != .null { Label("Stummgeschaltet · " + SuiteBanText.until(permanent: status["mute"]["until"].double <= 0, until: status["mute"]["until"].double), systemImage: "speaker.slash.fill").foregroundStyle(.orange) }
            } else {
                bannedContent
            }
            if !status["history"].array.isEmpty {
                DisclosureGroup("\(status["history"].array.count) frühere Sperre(n)") {
                    ForEach(Array(status["history"].array.enumerated()), id: \.offset) { _, row in SuiteBanHistoryRow(row: row) }
                }
            }
        } header: { Text("Sperrstatus") }
        .sheet(item: $lifting) { target in SuiteBanLiftSheet(suite: suite, target: target) { await load() } }
        .task { await load() }
        .errorAlert($error)
    }

    @ViewBuilder private var bannedContent: some View {
        let ban = status["accountBan"]
        HStack(spacing: 12) {
            Image(systemName: status["role"].string == "alt" ? "person.2.slash" : "hand.raised.fill")
                .font(.title2).foregroundStyle(.white).frame(width: 44, height: 44)
                .background(status["role"].string == "alt" ? Color.orange : Color.red, in: RoundedRectangle(cornerRadius: 12))
            VStack(alignment: .leading, spacing: 2) {
                Text(SuiteBanText.headline(status)).font(.headline)
                Text(ban != .null ? SuiteBanText.until(ban) : SuiteBanText.until(caseRow)).font(.caption).foregroundStyle(.secondary)
            }
        }.padding(.vertical, 2)
        LabeledContent("Grund", value: ban["reason"].string.isEmpty ? caseRow["reason"].string : ban["reason"].string)
        LabeledContent("Gesperrt von", value: ban["by"].string.isEmpty ? caseRow["actor"].string : ban["by"].string)
        if !caseRow["id"].string.isEmpty { LabeledContent("Fall", value: caseRow["id"].string) }
        if !ban["via"].string.isEmpty { SuiteDetailRow(title: "Herkunft", value: ban["via"].string) }
        if caseRow["members"].array.count > 1 {
            DisclosureGroup("Konten im Fall (\(caseRow["members"].array.count))") {
                ForEach(caseRow["members"].array, id: \.["uuid"].string) { member in SuiteBanMemberRow(member: member) }
            }
        }
        if status["manage"].bool && !suite.demo {
            Button {
                lifting = SuiteBanLiftTarget(caseRow: caseRow, member: me ?? .object(["uuid": .string(player), "name": status["name"], "role": status["role"]]))
            } label: {
                Label(status["role"].string == "alt" ? "Alt-Konto entsperren …" : (caseRow["members"].array.count > 1 ? "Bann und alle zugehörigen aufheben …" : "Bann aufheben …"), systemImage: "lock.open.fill")
            }
        }
    }

    private func load() async {
        do { status = try await suite.request("ban-center/player", query: ["player": player]) }
        catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }
}
