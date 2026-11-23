import SwiftUI
import Charts

// MARK: - Paper-Server-Updater (5.16.0)

/// Shows the installed Paper build, newer builds with their PaperMC change notes and installs a build only on an
/// explicit tap: download → checksum → backup → careful replacement of the server JAR → active after the restart.
struct SuiteServerUpdaterView: View {
    let suite: SuiteAPI
    @State private var data: JSON = .null
    @State private var version = ""
    @State private var loaded = false
    @State private var error: String?
    @State private var installing: JSON?
    @State private var settings = false
    @State private var restartAsk = false
    @State private var polling = false

    private var manage: Bool { data["manage"].bool }
    private var job: JSON { data["job"] }
    private var running: Bool { job["running"].bool }

    var body: some View {
        List {
            header
            if job["phase"].string != "idle" && !job["phase"].string.isEmpty { SuiteUpdateJobSection(job: job, manage: manage) { restartAsk = true } }
            if !data["error"].string.isEmpty { Section { Label(data["error"].string, systemImage: "wifi.exclamationmark").foregroundStyle(.orange) } }
            if !data["installed"]["jarProblem"].string.isEmpty {
                Section { Label(data["installed"]["jarProblem"].string + " Automatische Updates sind aus – nach einem Versuch erscheint eine Schritt-für-Schritt-Anleitung.", systemImage: "exclamationmark.triangle.fill").foregroundStyle(.orange) }
            }
            versionPicker
            buildsSection
            historySection
        }
        .panelScreen()
        .navigationTitle("Server-Updater")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Menu {
                    Button("Bei PaperMC prüfen", systemImage: "arrow.clockwise") { Task { await load(refresh: true) } }
                    if suite.can("server-updater.manage") { Button("Einstellungen", systemImage: "slider.horizontal.3") { settings = true } }
                } label: { Image(systemName: "ellipsis.circle") }
            }
        }
        .confirmationDialog(installTitle, isPresented: Binding(get: { installing != nil }, set: { if !$0 { installing = nil } }), titleVisibility: .visible) {
            if let build = installing { Button("Installieren") { Task { await install(build) } } }
        } message: { Text(installMessage) }
        .confirmationDialog("Server jetzt neu starten?", isPresented: $restartAsk, titleVisibility: .visible) {
            Button("Neustart in 60 Sekunden") { Task { await post(["operation": .string("restart"), "seconds": .number(60)]) } }
            Button("Neustart in 5 Minuten") { Task { await post(["operation": .string("restart"), "seconds": .number(300)]) } }
        } message: { Text("Spieler erhalten einen Countdown. Danach startet der Server mit dem neuen Build.") }
        .sheet(isPresented: $settings) { SuiteServerUpdaterSettings(policy: data["policy"]) { body in await post(body); await load(refresh: true) } }
        .task { await load(refresh: false) }
        .refreshable { await load(refresh: true) }
        .errorAlert($error)
    }

    private var header: some View {
        Section {
            HStack(spacing: 14) {
                Image(systemName: data["upToDate"].bool ? "checkmark.seal.fill" : "arrow.down.circle.fill")
                    .font(.system(size: 28)).foregroundStyle(.white).frame(width: 56, height: 56)
                    .background(LinearGradient(colors: [data["upToDate"].bool ? .green : .orange, .panelAccent], startPoint: .topLeading, endPoint: .bottomTrailing), in: RoundedRectangle(cornerRadius: 16))
                VStack(alignment: .leading, spacing: 3) {
                    Text(data["installed"]["brand"].string.isEmpty ? "Paper" : data["installed"]["brand"].string).font(.caption).foregroundStyle(.secondary)
                    Text(SuiteUpkeep.installedSummary(data["installed"])).font(.title3.bold())
                    Text(loaded ? SuiteUpkeep.statusLine(data) : "Wird geladen …").font(.subheadline).foregroundStyle(.secondary)
                }
            }.padding(.vertical, 4)
            LabeledContent("Java", value: data["installed"]["java"].string + (data["javaMinimum"].int > 0 ? " (benötigt \(data["javaMinimum"].int))" : ""))
            if !data["installed"]["jar"].string.isEmpty { LabeledContent("Server-JAR", value: data["installed"]["jar"].string) }
            LabeledContent("Updates", value: data["policy"]["allowMinecraftUpgrade"].bool ? "Auch Minecraft-Versionswechsel" : "Nur Builds dieser Minecraft-Version")
        }
    }

    @ViewBuilder private var versionPicker: some View {
        let options = data["versions"].array.filter { $0["selectable"].bool || $0["installed"].bool }
        if options.count > 1 {
            Section {
                Picker("Minecraft-Version", selection: Binding(get: { data["version"].string }, set: { value in version = value; Task { await load(refresh: false) } })) {
                    ForEach(options, id: \.["id"].string) { row in Text(row["id"].string + (row["installed"].bool ? " · installiert" : "")).tag(row["id"].string) }
                }
            } footer: { Text("Große Versionssprünge können Welten und Plugins betreffen. Vorher Backup und Plugin-Kompatibilität prüfen.") }
        }
    }

    private var buildsSection: some View {
        Section {
            ForEach(data["builds"].array, id: \.["id"].int) { build in
                SuiteBuildRow(build: build, installable: build["installable"].bool && !running) { installing = build }
            }
            if loaded && data["builds"].array.isEmpty && data["error"].string.isEmpty {
                ContentUnavailableView("Keine Builds", systemImage: "shippingbox", description: Text("Für diese Version liefert PaperMC keine stabilen Builds."))
            }
        } header: { Text("Builds · \(data["version"].string)") } footer: {
            Text("Die Änderungen kommen direkt von PaperMC. Installiert wird nur auf deinen Tipp – mit Prüfsumme, Sicherung und automatischem Zurücksetzen bei Fehlern.")
        }
    }

    @ViewBuilder private var historySection: some View {
        if !data["history"].array.isEmpty {
            Section("Verlauf") {
                ForEach(Array(data["history"].array.enumerated()), id: \.offset) { _, row in
                    Label {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(row["from"].string + " → " + row["to"].string).font(.subheadline.weight(.semibold))
                            Text(SuiteBanText.date(row["time"].double) + " · " + row["actor"].string).font(.caption).foregroundStyle(.secondary)
                            if !row["error"].string.isEmpty { Text(row["error"].string).font(.caption).foregroundStyle(.red) }
                        }
                    } icon: { Image(systemName: row["ok"].bool ? "checkmark.circle.fill" : "xmark.octagon.fill").foregroundStyle(row["ok"].bool ? .green : .red) }
                }
            }
        }
    }

    private var installTitle: String { "Build \(installing?["id"].int ?? 0) installieren?" }
    private var installMessage: String {
        let jar = data["installed"]["jar"].string.isEmpty ? "server.jar" : data["installed"]["jar"].string
        let older = installing.map { !$0["newer"].bool } ?? false
        return "Die Datei wird von PaperMC geladen, mit der offiziellen Prüfsumme verglichen und \(jar) vorsichtig ersetzt. Eine Sicherung bleibt erhalten. Aktiv nach dem nächsten Neustart." + (older ? "\n\nAchtung: älterer Build (Rollback)." : "")
    }

    private func load(refresh: Bool) async {
        do {
            data = try await suite.request("server-updater", query: ["version": version, "refresh": refresh ? "true" : "false"], timeout: 45)
            loaded = true
            if running { await poll() }
        } catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }

    private func poll() async {
        guard !polling else { return }
        polling = true; defer { polling = false }
        while !Task.isCancelled {
            do { try await Task.sleep(for: .milliseconds(900)) } catch { return }
            guard let result = try? await suite.request("server-updater", query: ["job": "true"]) else { continue }
            var copy = data.object; copy["job"] = result["job"]; data = .object(copy)
            if !result["job"]["running"].bool { await load(refresh: false); if result["job"]["ok"].bool { Feedback.success() }; return }
        }
    }

    private func install(_ build: JSON) async {
        do {
            let result = try await suite.post("server-updater", ["operation": .string("install"), "version": .string(data["version"].string), "build": .number(build["id"].double), "confirmed": .bool(true)])
            var copy = data.object; copy["job"] = result["job"]; data = .object(copy)
            await poll()
        } catch { self.error = error.localizedDescription }
    }

    private func post(_ body: [String: JSON]) async {
        do {
            var payload = body; payload["confirmed"] = .bool(true)
            let result = try await suite.post("server-updater", payload)
            if !result["notice"].string.isEmpty { Feedback.success() }
        } catch { self.error = error.localizedDescription }
    }
}

struct SuiteBuildRow: View {
    let build: JSON
    let installable: Bool
    let install: () -> Void
    @State private var expanded = false

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline) {
                Text("Build \(build["id"].int)").font(.headline)
                if build["installed"].bool { PowerBotTag(text: "Installiert", tint: .green) }
                else if build["newer"].bool { PowerBotTag(text: "Neuer", tint: .blue) }
                if SuiteUpkeep.channelLabel(build["channel"].string) != "Stabil" { PowerBotTag(text: SuiteUpkeep.channelLabel(build["channel"].string), tint: .orange) }
                Spacer()
                if installable {
                    Button(build["newer"].bool ? "Updaten" : "Rollback", action: install).buttonStyle(.glassProminent).controlSize(.small)
                }
            }
            Text(SuiteBanText.date(build["time"].double) + " · " + SuiteUpkeep.megabytes(build["size"].double)).font(.caption).foregroundStyle(.secondary)
            let commits = build["commits"].array
            if !commits.isEmpty {
                DisclosureGroup(isExpanded: $expanded) {
                    ForEach(Array(commits.enumerated()), id: \.offset) { _, commit in
                        HStack(alignment: .top, spacing: 6) {
                            Text(String(commit["sha"].string.prefix(7))).font(.caption2.monospaced()).foregroundStyle(.secondary)
                            Text(commit["summary"].string).font(.caption)
                        }
                    }
                } label: { Text(commits.count == 1 ? "1 Änderung" : "\(commits.count) Änderungen").font(.caption.weight(.semibold)) }
            }
        }.padding(.vertical, 2)
    }
}

struct SuiteUpdateJobSection: View {
    let job: JSON
    let manage: Bool
    let restart: () -> Void

    var body: some View {
        Section {
            let index = SuiteUpkeep.stepIndex(job["phase"].string)
            let failed = job["phase"].string == "failed"
            HStack(spacing: 6) {
                ForEach(Array(SuiteUpkeep.installSteps.enumerated()), id: \.offset) { offset, step in
                    let done = offset < index || job["phase"].string == "done"
                    let active = offset == index && !failed
                    VStack(spacing: 4) {
                        Image(systemName: done ? "checkmark.circle.fill" : active ? "circle.dotted.circle" : "circle")
                            .foregroundStyle(done ? Color.green : active ? Color.panelAccent : Color.secondary)
                        Text(step.title).font(.caption2).foregroundStyle(active ? .primary : .secondary)
                    }.frame(maxWidth: .infinity)
                }
            }
            if job["running"].bool {
                ProgressView(value: job["progress"].double) {
                    Text(job["message"].string).font(.caption)
                } currentValueLabel: {
                    Text(SuiteUpkeep.megabytes(job["bytes"].double) + " von " + SuiteUpkeep.megabytes(job["total"].double)).font(.caption2)
                }
            }
            if job["phase"].string == "done" {
                Label(job["message"].string, systemImage: "checkmark.seal.fill").foregroundStyle(.green)
                if manage { Button("Neustart planen", systemImage: "arrow.clockwise.circle.fill", action: restart).buttonStyle(.glassProminent) }
            }
            if failed {
                Label(job["error"].string, systemImage: "xmark.octagon.fill").foregroundStyle(.red)
                Text("So aktualisierst du selbst:").font(.subheadline.weight(.semibold))
                ForEach(Array(job["guide"].array.enumerated()), id: \.offset) { offset, step in
                    HStack(alignment: .top, spacing: 8) {
                        Text("\(offset + 1).").font(.subheadline.monospacedDigit()).foregroundStyle(.secondary)
                        Text(step.string).font(.subheadline).textSelection(.enabled)
                    }
                }
            }
        } header: { Text(failed ? "Update nicht möglich" : job["phase"].string == "done" ? "Update installiert" : "Update läuft") }
    }
}

struct SuiteServerUpdaterSettings: View {
    let policy: JSON
    let save: ([String: JSON]) async -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var upgrade = false
    @State private var experimental = false
    @State private var keep = 3

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Toggle("Minecraft-Versionswechsel erlauben", isOn: $upgrade)
                } footer: { Text("Standard aus: Es werden nur neue Builds deiner installierten Minecraft-Version angeboten.") }
                Section { Toggle("Vorabversionen anzeigen", isOn: $experimental) } footer: { Text("Beta- und Alpha-Builds sowie Release Candidates.") }
                Section { Stepper("Gesicherte Server-JARs: \(keep)", value: $keep, in: 1...10) }
            }
            .panelScreen()
            .navigationTitle("Server-Updater")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Speichern") { Task { await save(["operation": .string("settings"), "allowMinecraftUpgrade": .bool(upgrade), "includeExperimental": .bool(experimental), "keepBackups": .number(Double(keep))]); dismiss() } }
                }
            }
            .onAppear { upgrade = policy["allowMinecraftUpgrade"].bool; experimental = policy["includeExperimental"].bool; keep = max(1, policy["keepBackups"].int) }
        }
    }
}

// MARK: - Smart Anti-Lag (5.16.0)

struct SuiteAntiLagView: View {
    let suite: SuiteAPI
    @State private var data: JSON = .null
    @State private var loaded = false
    @State private var error: String?
    @State private var notice: String?
    @State private var settings = false
    @State private var teleport: (operation: String, id: String, target: String)?
    @State private var clearing: JSON?

    private var manage: Bool { data["manage"].bool }
    private var staff: [String] { data["staffOnline"].array.map { $0["name"].string } }

    var body: some View {
        List {
            Section { SuiteAntiLagHealth(data: data) }
            if data["history"].array.count > 2 { Section("Verlauf") { SuiteAntiLagChart(rows: data["history"].array).frame(height: 120) } }
            hotspotsSection
            playersSection
            actionsSection
        }
        .panelScreen()
        .navigationTitle("Smart Anti-Lag")
        .toolbar {
            if suite.can("anti-lag.manage") {
                ToolbarItem(placement: .primaryAction) { Button { settings = true } label: { Label("Einstellungen", systemImage: "slider.horizontal.3") } }
            }
        }
        .confirmationDialog("Wer soll teleportiert werden?", isPresented: Binding(get: { teleport != nil }, set: { if !$0 { teleport = nil } }), titleVisibility: .visible) {
            if let teleport {
                ForEach(staff, id: \.self) { name in
                    Button(name) { Task { await send(["operation": .string(teleport.operation), "id": .string(teleport.id), "target": .string(teleport.target), "player": .string(name)]) } }
                }
            }
        } message: { Text(staff.isEmpty ? "Kein Teammitglied mit Admin-Rechten ist online." : "Teleport direkt zum Ort – im Spiel.") }
        .confirmationDialog("Herumliegende Items entfernen?", isPresented: Binding(get: { clearing != nil }, set: { if !$0 { clearing = nil } }), titleVisibility: .visible) {
            if let clearing { Button("\(clearing["items"].int) Items entfernen", role: .destructive) { Task { await send(["operation": .string("clear-items"), "id": clearing["id"]]) } } }
        } message: { Text("Das lässt sich nicht rückgängig machen.") }
        .sheet(isPresented: $settings) { SuiteAntiLagSettings(data: data) { body in await send(body) } }
        .task { await load(); while !Task.isCancelled { do { try await Task.sleep(for: .seconds(5)) } catch { return }; await load(quiet: true) } }
        .refreshable { await load() }
        .errorAlert($error)
    }

    private var hotspotsSection: some View {
        Section {
            ForEach(data["hotspots"].array, id: \.["id"].string) { spot in
                SuiteAntiLagSpotRow(spot: spot)
                    .swipeActions(edge: .trailing) {
                        if manage {
                            Button("Teleport", systemImage: "location.fill") { teleport = ("teleport", spot["id"].string, "") }.tint(.blue)
                            Button("Informieren", systemImage: "paperplane.fill") { Task { await send(["operation": .string("notify"), "id": spot["id"]]) } }.tint(.orange)
                        }
                    }
                    .contextMenu {
                        if manage {
                            Button("Teammitglied hinteleportieren", systemImage: "location.fill") { teleport = ("teleport", spot["id"].string, "") }
                            Button("Spieler informieren", systemImage: "paperplane.fill") { Task { await send(["operation": .string("notify"), "id": spot["id"]]) } }
                            if spot["status"].string == "limited" || spot["status"].string == "throttled" {
                                Button("Maßnahmen aufheben", systemImage: "lock.open.fill") { Task { await send(["operation": .string("release"), "id": spot["id"]]) } }
                            } else {
                                Button("Spawnbremse setzen", systemImage: "lock.fill") { Task { await send(["operation": .string("limit"), "id": spot["id"]]) } }
                            }
                            Button("Trichter zählen", systemImage: "magnifyingglass") { Task { await send(["operation": .string("inspect"), "id": spot["id"]]) } }
                            if spot["items"].int > 50 { Button("Items entfernen", systemImage: "trash", role: .destructive) { clearing = spot } }
                        }
                    }
            }
            if loaded && data["hotspots"].array.isEmpty {
                ContentUnavailableView("Keine Hotspots", systemImage: "checkmark.shield.fill", description: Text("Alles im grünen Bereich – Anti-Lag beobachtet weiter."))
            }
        } header: { Text("Hotspots") } footer: {
            Text("Gedrückt halten oder wischen für Teleport und Maßnahmen. „Gebaut von“ zählt platzierte Maschinenteile (Trichter, Kolben, Observer …) und Zucht. Deaktivieren entscheidest du selbst.")
        }
    }

    private var playersSection: some View {
        Section("Wer verursacht am meisten Last?") {
            ForEach(Array(data["players"].array.enumerated()), id: \.offset) { offset, player in
                HStack(spacing: 10) {
                    Text("#\(offset + 1)").font(.headline.monospacedDigit()).foregroundStyle(.secondary).frame(width: 34)
                    SuitePlayerAvatar(row: .object(["id": player["uuid"], "online": player["online"]]), size: 34)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(player["name"].string).font(.headline)
                        Text("\(player["hotspots"].int) Hotspot(s) · Last \(player["score"].int)" + (player["online"].bool ? " · \(player["world"].string) \(player["x"].int) / \(player["z"].int)" : " · offline")).font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                    if manage && player["online"].bool {
                        Button { teleport = ("teleport-player", "", player["name"].string) } label: { Image(systemName: "location.fill") }.buttonStyle(.glass)
                    }
                }
            }
            if data["players"].array.isEmpty { Text("Noch keine Verursacher erkannt.").foregroundStyle(.secondary) }
        }
    }

    @ViewBuilder private var actionsSection: some View {
        if !data["actions"].array.isEmpty {
            Section("Protokoll") {
                ForEach(Array(data["actions"].array.prefix(25).enumerated()), id: \.offset) { _, row in
                    VStack(alignment: .leading, spacing: 2) {
                        Text(row["title"].string).font(.subheadline.weight(.semibold))
                        Text(row["text"].string).font(.caption).foregroundStyle(.secondary)
                        if let date = SuitePlayerPresentation.date(row["time"]) { Text(SuiteRelativeTime.text(date)).font(.caption2).foregroundStyle(.tertiary) }
                    }
                }
            }
        }
    }

    private func load(quiet: Bool = false) async {
        do { data = try await suite.request("anti-lag"); loaded = true }
        catch { if !quiet && !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }

    private func send(_ body: [String: JSON]) async {
        do {
            var payload = body; payload["confirmed"] = .bool(true)
            data = try await suite.post("anti-lag", payload)
            Feedback.success()
        } catch { self.error = error.localizedDescription }
    }
}

struct SuiteAntiLagHealth: View {
    let data: JSON
    private var health: JSON { data["health"] }
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 14) {
                Image(systemName: health["stressed"].bool ? "exclamationmark.triangle.fill" : "bolt.shield.fill")
                    .font(.system(size: 26)).foregroundStyle(.white).frame(width: 54, height: 54)
                    .background(LinearGradient(colors: [health["stressed"].bool ? .orange : .green, .panelAccent], startPoint: .topLeading, endPoint: .bottomTrailing), in: RoundedRectangle(cornerRadius: 15))
                VStack(alignment: .leading, spacing: 3) {
                    Text(health["stressed"].bool ? "Server unter Last" : "Server läuft flüssig").font(.headline)
                    Text("Modus: " + SuiteUpkeep.modeTitle(data["mode"].string)).font(.subheadline).foregroundStyle(.secondary)
                }
            }
            GlassEffectContainer(spacing: 8) {
                HStack(spacing: 8) {
                    metric("TPS", String(format: "%.1f", health["tps"].double))
                    metric("ms/Tick", String(format: "%.0f", health["mspt"].double))
                    metric("Hotspots", "\(data["hotspots"].array.filter { $0["over"].bool }.count)")
                    metric("Pausiert", "\(data["throttled"].int)")
                }
            }
        }.padding(.vertical, 4)
    }
    private func metric(_ title: String, _ value: String) -> some View {
        VStack(spacing: 2) {
            Text(value).font(.title3.bold().monospacedDigit())
            Text(title).font(.caption2).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity).padding(.vertical, 10)
        .glassEffect(.regular, in: RoundedRectangle(cornerRadius: 14))
    }
}

struct SuiteAntiLagChart: View {
    let rows: [JSON]
    var body: some View {
        Chart {
            ForEach(Array(rows.enumerated()), id: \.offset) { offset, row in
                LineMark(x: .value("Zeit", offset), y: .value("TPS", row["tps"].double)).foregroundStyle(Color.panelAccent).interpolationMethod(.catmullRom)
            }
            RuleMark(y: .value("Ziel", 20)).foregroundStyle(.green.opacity(0.35)).lineStyle(StrokeStyle(lineWidth: 1, dash: [4, 4]))
        }
        .chartYScale(domain: 0...21)
        .chartXAxis(.hidden)
    }
}

struct SuiteAntiLagSpotRow: View {
    let spot: JSON
    private var tint: Color {
        switch spot["status"].string {
        case "throttled": return .red
        case "limited", "high": return .orange
        case "warned": return .blue
        default: return .secondary
        }
    }
    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: SuiteUpkeep.kindSymbol(spot["kind"].string))
                .font(.system(size: 18, weight: .semibold)).foregroundStyle(.white)
                .frame(width: 40, height: 40).background(tint.gradient, in: RoundedRectangle(cornerRadius: 12))
            VStack(alignment: .leading, spacing: 4) {
                Text(SuiteUpkeep.kindTitle(spot["kind"].string) + " · " + SuiteUpkeep.location(spot)).font(.headline)
                Text(SuiteUpkeep.facts(spot)).font(.subheadline)
                Text(SuiteUpkeep.culprits(spot)).font(.caption).foregroundStyle(.secondary)
                HStack(spacing: 6) {
                    PowerBotTag(text: SuiteUpkeep.statusTitle(spot["status"].string), tint: tint)
                    PowerBotTag(text: "Last \(spot["score"].int)", tint: spot["over"].bool ? .red : .secondary)
                    if spot["throttledMobs"].int > 0 { PowerBotTag(text: "\(spot["throttledMobs"].int) Mobs pausiert", tint: .purple) }
                }
            }
        }.padding(.vertical, 3)
    }
}

struct SuiteAntiLagSettings: View {
    let data: JSON
    let save: ([String: JSON]) async -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var mode = "smart"
    @State private var trigger = 48.0
    @State private var mobs = 150.0
    @State private var sameType = 100.0
    @State private var items = 600.0
    @State private var redstone = 800.0
    @State private var radius = 96.0
    @State private var notifyStaff = true

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker("Modus", selection: $mode) { ForEach(SuiteUpkeep.modes, id: \.0) { Text($0.1).tag($0.0) } }.pickerStyle(.segmented)
                } footer: { Text("Smart greift nur ein, wenn der Server wirklich leidet und ein Hotspot weit über den Grenzen liegt: erst Hinweis an die Spieler, dann Spawnbremse, erst nach 2 Minuten anhaltender Last pausieren überzählige Mobs ihre KI. Nichts wird gelöscht.") }
                Section("Grenzen je Chunk") {
                    Stepper("Last ab \(Int(trigger)) ms/Tick", value: $trigger, in: 30...200, step: 2)
                    Stepper("Mobs: \(Int(mobs))", value: $mobs, in: 40...5000, step: 10)
                    Stepper("Gleiche Mobart: \(Int(sameType))", value: $sameType, in: 30...5000, step: 10)
                    Stepper("Items: \(Int(items))", value: $items, in: 100...20000, step: 50)
                    Stepper("Redstone/s: \(Int(redstone))", value: $redstone, in: 50...100000, step: 50)
                    Stepper("Hinweis-Umkreis: \(Int(radius)) Blöcke", value: $radius, in: 16...512, step: 16)
                }
                Section { Toggle("Team im Spiel mit Teleport-Link informieren", isOn: $notifyStaff) }
            }
            .panelScreen()
            .navigationTitle("Anti-Lag")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Speichern") { Task { await save(payload); dismiss() } } }
            }
            .onAppear(perform: restore)
        }
    }

    private var payload: [String: JSON] {
        ["operation": .string("settings"), "mode": .string(mode), "triggerMspt": .number(trigger), "mobsPerChunk": .number(mobs),
         "sameTypePerChunk": .number(sameType), "itemsPerChunk": .number(items), "redstonePerSecond": .number(redstone),
         "notifyRadius": .number(radius), "notifyStaff": .bool(notifyStaff)]
    }

    private func restore() {
        let limits = data["limits"]
        mode = data["mode"].string.isEmpty ? "smart" : data["mode"].string
        if limits["triggerMspt"].double > 0 { trigger = limits["triggerMspt"].double }
        if limits["mobsPerChunk"].double > 0 { mobs = limits["mobsPerChunk"].double }
        if limits["sameTypePerChunk"].double > 0 { sameType = limits["sameTypePerChunk"].double }
        if limits["itemsPerChunk"].double > 0 { items = limits["itemsPerChunk"].double }
        if limits["redstonePerSecond"].double > 0 { redstone = limits["redstonePerSecond"].double }
        if limits["notifyRadius"].double > 0 { radius = limits["notifyRadius"].double }
        notifyStaff = limits["notifyStaff"].bool
    }
}

// MARK: - Plugin overview from the server (5.16.0)

/// Fast server-side plugin list: file version, version Paper loaded, pending updates and their versioned file names.
struct SuitePluginOverviewView: View {
    let panel: PanelAPI
    let server: Server
    @State private var data: JSON = .null
    @State private var unavailable = false
    @State private var search = ""
    @State private var error: String?

    private var rows: [JSON] {
        data["items"].array.filter { search.isEmpty || ($0["name"].string + $0["filename"].string).localizedCaseInsensitiveContains(search) }
    }

    var body: some View {
        List {
            if unavailable {
                ContentUnavailableView("Admin Suite nicht verbunden", systemImage: "puzzlepiece.extension", description: Text("Die Server-Übersicht braucht die gekoppelte Admin Suite 10.23.0 oder neuer."))
            } else {
                Section {
                    LabeledContent("Aktive Plugins", value: "\(data["enabled"].int) von \(data["loaded"].int)")
                    LabeledContent("Vorgemerkte Updates", value: "\(data["pending"].array.count)")
                    LabeledContent("Update-Ordner", value: data["updateDirectory"].string)
                } footer: { Text("Updates werden mit Versionsnummer im Dateinamen vorgemerkt – Paper übernimmt den Namen beim nächsten Start. Neue Plugins kannst du auch bei laufendem Server hinzufügen.") }
                Section("Plugin-Dateien · \(rows.count)") {
                    ForEach(rows, id: \.["filename"].string) { row in SuitePluginOverviewRow(row: row) }
                }
            }
        }
        .panelScreen()
        .navigationTitle("Server-Übersicht")
        .searchable(text: $search, prompt: "Plugin suchen")
        .task { await load() }
        .refreshable { await load() }
        .errorAlert($error)
    }

    private func load() async {
        guard let suite = await SuiteAPI.forPluginUpdates(panel, server.id) else { unavailable = true; return }
        guard suite.features.contains("plugin-versioned-files-v1") else { unavailable = true; return }
        do { data = try await suite.request("plugin-files/overview"); unavailable = false }
        catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }
}

struct SuitePluginOverviewRow: View {
    let row: JSON
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(row["name"].string.isEmpty ? row["filename"].string : row["name"].string).font(.headline)
                Text(row["version"].string).font(.subheadline.monospaced()).foregroundStyle(.secondary)
            }
            Text(row["filename"].string).font(.caption.monospaced()).foregroundStyle(.secondary)
            HStack(spacing: 6) {
                if row["disabled"].bool { PowerBotTag(text: "Deaktiviert", tint: .secondary) }
                else if row["enabled"].bool { PowerBotTag(text: "Aktiv", tint: .green) }
                else if row["loaded"].bool { PowerBotTag(text: "Fehler beim Laden", tint: .red) }
                else { PowerBotTag(text: "Neu – aktiv nach Neustart", tint: .blue) }
                if row["restartPending"].bool { PowerBotTag(text: "Läuft noch " + row["loadedVersion"].string, tint: .orange) }
                if row["pending"] != .null { PowerBotTag(text: "Update " + row["pending"]["version"].string + " vorgemerkt", tint: .purple) }
            }
            if row["pending"] != .null { Text("Wird zu " + row["pending"]["versionedFilename"].string).font(.caption2).foregroundStyle(.secondary) }
            if !row["error"].string.isEmpty { Text(row["error"].string).font(.caption2).foregroundStyle(.red) }
        }.padding(.vertical, 2)
    }
}
