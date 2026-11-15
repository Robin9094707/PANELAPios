import SwiftUI

// MARK: - LiteBans-Migration (5.13.0, Admin Suite 10.20.0)

/// Read LiteBans' data once, choose per case (and per linked account) what is taken over, then run the migration.
/// An interrupted run continues where it stopped; cases on an excluded IP are not taken over by default.
struct SuiteBanMigrationView: View {
    let suite: SuiteAPI
    @State private var data: JSON = .null
    @State private var pending: [String: String] = [:]
    @State private var filter = "open"
    @State private var search = ""
    @State private var error: String?
    @State private var notice: String?
    @State private var confirmRun = false
    @State private var confirmDisable = false
    @State private var confirmRename = false

    private var canRun: Bool { data["migrate"].bool && !suite.demo }
    private var counts: JSON { data["counts"] }
    private var interrupted: Bool { data["lastRun"]["interrupted"].bool && data["lastRun"]["started"].double > 0 }

    private var rows: [JSON] {
        data["cases"].array.filter { row in
            let status = row["status"].string
            switch filter {
            case "open": if status == "done" || status == "skipped" { return false }
            case "excluded": if !row["excluded"].bool { return false }
            case "done": if status != "done" { return false }
            default: break
            }
            guard !search.isEmpty else { return true }
            if row["reason"].string.localizedCaseInsensitiveContains(search) { return true }
            return row["accounts"].array.contains { $0["name"].string.localizedCaseInsensitiveContains(search) || $0["ip"].string.contains(search) }
        }
    }

    var body: some View {
        List {
            statusSection
            actionsSection
            Section {
                Picker("Filter", selection: $filter) {
                    Text("Offen").tag("open")
                    Text("Geteilte IP").tag("excluded")
                    Text("Übernommen").tag("done")
                    Text("Alle").tag("all")
                }.pickerStyle(.segmented)
            }
            Section {
                ForEach(rows, id: \.["key"].string) { row in
                    SuiteBanMigrationCase(row: row, decision: pending[row["key"].string] ?? row["decision"].string, accountChoice: { uuid in pending[row["key"].string + "|" + uuid] }, editable: canRun && row["status"].string != "done",
                        choose: { pending[row["key"].string] = $0 },
                        toggle: { uuid, on in pending[row["key"].string + "|" + uuid] = on ? "on" : "off" })
                }
                if rows.isEmpty {
                    ContentUnavailableView(data["snapshot"] == .null ? "Noch kein Snapshot" : "Keine Fälle", systemImage: "tray",
                        description: Text(data["snapshot"] == .null ? "Zuerst die LiteBans-Daten einlesen." : "Alles erledigt oder anderer Filter."))
                }
            } header: { Text("Fälle") }
        }
        .panelScreen()
        .navigationTitle("LiteBans-Migration")
        .searchable(text: $search, prompt: "Name, Grund oder IP")
        .toolbar {
            if !pending.isEmpty && canRun {
                ToolbarItem(placement: .primaryAction) { Button("Speichern (\(pending.count))") { Task { await saveChoices() } } }
            }
        }
        .confirmationDialog(interrupted ? "Migration fortsetzen?" : "Migration starten?", isPresented: $confirmRun, titleVisibility: .visible) {
            Button(interrupted ? "Fortsetzen" : "Starten") { Task { await post(["operation": .string("run"), "retryFailed": .string("true")]) } }
        } message: { Text("\(counts["pending"].int) offene und \(counts["failed"].int) fehlgeschlagene Fälle werden nach deiner Auswahl übernommen. Bestehende Suite-Banns bleiben unverändert. Ein erneuter Start macht nach einem Abbruch einfach weiter.") }
        .confirmationDialog("LiteBans abschalten?", isPresented: $confirmDisable, titleVisibility: .visible) {
            Button("Abschalten", role: .destructive) { Task { await post(["operation": .string("disable")]) } }
        } message: { Text("Die Suite schaltet LiteBans außerdem bei jedem Serverstart ab.") }
        .confirmationDialog("LiteBans-JAR dauerhaft deaktivieren?", isPresented: $confirmRename, titleVisibility: .visible) {
            Button("Umbenennen", role: .destructive) { Task { await post(["operation": .string("rename-jar")]) } }
        } message: { Text("Die JAR wird umbenannt und lädt nach dem nächsten Neustart nicht mehr. Die Datenbank bleibt unverändert.") }
        .alert("Erledigt", isPresented: Binding(get: { notice != nil }, set: { if !$0 { notice = nil } })) { Button("OK") { notice = nil } } message: { Text(notice ?? "") }
        .task { await load(); while !Task.isCancelled { do { try await Task.sleep(for: .seconds(4)) } catch { return }; if data["running"].bool || data["snapshotRunning"].bool { await load(quiet: true) } } }
        .refreshable { await load() }
        .errorAlert($error)
    }

    @ViewBuilder private var statusSection: some View {
        Section {
            HStack(spacing: 14) {
                Image(systemName: data["litebansEnabled"].bool ? "exclamationmark.shield.fill" : "checkmark.shield.fill")
                    .font(.system(size: 26)).foregroundStyle(.white).frame(width: 54, height: 54)
                    .background(LinearGradient(colors: [data["litebansEnabled"].bool ? Color.orange : Color.green, Color.panelAccent], startPoint: .topLeading, endPoint: .bottomTrailing), in: RoundedRectangle(cornerRadius: 15))
                VStack(alignment: .leading, spacing: 3) {
                    Text(data["litebansEnabled"].bool ? "LiteBans läuft noch" : (data["litebansInstalled"].bool ? "LiteBans ist abgeschaltet" : "LiteBans ist nicht installiert")).font(.headline)
                    Text("\(counts["done"].int) übernommen · \(counts["pending"].int) offen · \(counts["failed"].int) fehlgeschlagen").font(.subheadline).foregroundStyle(.secondary)
                }
            }.padding(.vertical, 4)
            if data["running"].bool {
                ProgressView(value: Double(data["progress"]["done"].int), total: Double(max(1, data["progress"]["total"].int))) {
                    Text("Migration läuft: \(data["progress"]["done"].int) von \(data["progress"]["total"].int)")
                }
            } else if interrupted {
                Label("Die letzte Migration wurde unterbrochen. Einfach fortsetzen – erledigte Fälle bleiben erhalten.", systemImage: "exclamationmark.triangle.fill").foregroundStyle(.orange)
            }
            if !data["error"].string.isEmpty { Label(data["error"].string, systemImage: "xmark.octagon.fill").foregroundStyle(.red) }
            if data["snapshot"] != .null {
                LabeledContent("Quelle", value: data["snapshot"]["source"].string)
                LabeledContent("Eingelesen", value: SuiteBanText.date(data["snapshot"]["takenAt"].double))
                LabeledContent("LiteBans-Daten", value: "\(data["snapshot"]["bans"].int) Bans · \(data["snapshot"]["mutes"].int) Mutes · \(data["snapshot"]["warnings"].int) Warnungen")
                LabeledContent("Fälle an geteilter IP", value: "\(counts["excluded"].int)")
            }
        } footer: {
            Text("Fälle an einer ausgeschlossenen IP (z. B. 82.165.45.159) werden standardmäßig nicht übernommen, ihr IP-Teil nie. Abgelaufene und aufgehobene LiteBans-Einträge landen im Verlauf.")
        }
    }

    @ViewBuilder private var actionsSection: some View {
        if canRun {
            Section {
                Button { Task { await post(["operation": .string("snapshot")]) } } label: {
                    Label(data["snapshot"] == .null ? "LiteBans-Daten einlesen" : "Snapshot neu einlesen", systemImage: "arrow.down.doc")
                }.disabled(data["snapshotRunning"].bool)
                Button { confirmRun = true } label: {
                    Label(interrupted ? "Migration fortsetzen" : "Migration starten", systemImage: "play.fill")
                }.disabled(data["snapshot"] == .null || data["running"].bool || !pending.isEmpty)
                if data["litebansEnabled"].bool { Button(role: .destructive) { confirmDisable = true } label: { Label("LiteBans jetzt abschalten", systemImage: "power") } }
                if data["litebansInstalled"].bool && data["snapshot"] != .null { Button(role: .destructive) { confirmRename = true } label: { Label("LiteBans-JAR dauerhaft deaktivieren", systemImage: "trash") } }
            } footer: { Text(pending.isEmpty ? "Auswahl ändern: Fall antippen. Gespeicherte Auswahl wird beim Start verwendet." : "Zuerst die geänderte Auswahl speichern (oben rechts).") }
        } else {
            Section { Label("Ansehen ist erlaubt. Für Einlesen und Übernehmen braucht dein Zugang Vollzugriff und das Recht „LiteBans-Migration durchführen“.", systemImage: "lock.fill").font(.callout) }
        }
    }

    private func load(quiet: Bool = false) async {
        do { data = try await suite.request("ban-migration") }
        catch { if !quiet && !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }

    private func saveChoices() async {
        await post(["operation": .string("decide"), "decisions": .string(SuiteBanText.decisions(pending))])
        pending = [:]
    }

    private func post(_ body: [String: JSON]) async {
        var payload = body
        payload["confirmed"] = .bool(true)
        do {
            let result = try await suite.post("ban-migration", payload)
            Feedback.success()
            notice = result["description"].string.isEmpty ? nil : result["description"].string
            await load(quiet: true)
        } catch { self.error = error.localizedDescription }
    }
}

struct SuiteBanMigrationCase: View {
    let row: JSON
    let decision: String
    let accountChoice: (String) -> String?
    let editable: Bool
    let choose: (String) -> Void
    let toggle: (String, Bool) -> Void

    private var statusTint: Color {
        switch row["status"].string {
        case "done": return .green
        case "failed": return .red
        case "skipped": return .blue
        default: return .secondary
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 6) {
                Text(SuiteBanText.type(row["type"].string) + " #" + row["id"].string).font(.headline)
                PowerBotTag(text: SuiteBanText.status(row["status"].string), tint: statusTint)
                if row["excluded"].bool { PowerBotTag(text: "geteilte IP", tint: .orange) }
                Spacer(minLength: 0)
            }
            Text(row["reason"].string.isEmpty ? "ohne Grund" : row["reason"].string).font(.subheadline)
            Text(meta).font(.caption).foregroundStyle(.secondary)
            if editable {
                Menu {
                    ForEach(row["choices"].array.map(\.string), id: \.self) { choice in
                        Button { choose(choice) } label: {
                            if choice == decision { Label(title(choice), systemImage: "checkmark") } else { Text(title(choice)) }
                        }
                    }
                } label: {
                    Label(title(decision), systemImage: "slider.horizontal.3").font(.subheadline.weight(.semibold))
                }
            } else {
                Label(title(decision), systemImage: "slider.horizontal.3").font(.subheadline).foregroundStyle(.secondary)
            }
            Text(SuiteBanText.decisionHint(decision)).font(.caption2).foregroundStyle(.secondary)
            ForEach(row["accounts"].array, id: \.["uuid"].string) { account in accountRow(account) }
        }.padding(.vertical, 4)
    }

    private var meta: String {
        var parts = ["von " + (row["actor"].string.isEmpty ? "?" : row["actor"].string), SuiteBanText.date(row["time"].double)]
        if row["type"].string != "warning" { parts.append(SuiteBanText.until(row)) }
        if !row["message"].string.isEmpty { parts.append(row["message"].string) }
        return parts.joined(separator: " · ")
    }

    private func title(_ choice: String) -> String {
        SuiteBanText.decisionTitle(choice) + (choice == row["recommended"].string ? " (empfohlen)" : "")
    }

    @ViewBuilder private func accountRow(_ account: JSON) -> some View {
        let uuid = account["uuid"].string
        let chosen = accountChoice(uuid)
        let included = chosen == nil ? account["imported"].bool : chosen == "on"
        HStack(spacing: 8) {
            Image(systemName: account["role"].string == "alt" ? "person.2.fill" : "person.fill").foregroundStyle(account["role"].string == "alt" ? .orange : .red).frame(width: 20)
            VStack(alignment: .leading, spacing: 1) {
                Text(account["name"].string).font(.subheadline.weight(.semibold))
                Text("IP " + (account["ip"].string.isEmpty ? "–" : account["ip"].string) + (account["ipAccounts"].int > 1 ? " · \(account["ipAccounts"].int) Konten" : "") + (account["ipExcluded"].bool ? " · ausgeschlossen" : "")).font(.caption2).foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
            if account["role"].string == "alt" && editable {
                Toggle("Übernehmen", isOn: Binding(get: { included }, set: { toggle(uuid, $0) })).labelsHidden()
            } else {
                PowerBotTag(text: account["role"].string == "alt" ? (included ? "Alt" : "frei") : "Haupt", tint: account["role"].string == "alt" ? .orange : .red)
            }
        }
    }
}
