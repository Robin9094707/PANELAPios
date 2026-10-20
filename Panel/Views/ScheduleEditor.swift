import SwiftUI

struct PanelTimeView: View {
    let api: PanelAPI
    @ObservedObject private var clock: PanelClock
    @State private var zone: String
    @State private var search = ""
    init(api: PanelAPI) { self.api = api; clock = api.clock; _zone = State(initialValue: ScheduleTime.zone(for: api.base)?.identifier ?? "") }
    var zones: [String] { ["", "Europe/Berlin", "UTC"] + TimeZone.knownTimeZoneIdentifiers.filter { $0 != "Europe/Berlin" && $0 != "UTC" } }
    var body: some View {
        Section {
            TimelineView(.periodic(from: .now, by: 1)) { _ in
                VStack(alignment: .leading, spacing: 8) {
                    Label("Uhrzeit & Zeitzone", systemImage: "globe.europe.africa").font(.headline)
                    LabeledContent("Deutschland", value: formatted(clock.synced == nil ? Date() : clock.now, zone: ScheduleTime.berlin))
                    if let timeZone = TimeZone(identifier: zone) { LabeledContent("Panel-Zeitzone", value: formatted(clock.synced == nil ? Date() : clock.now, zone: timeZone)) }
                    Text(clock.synced == nil ? "Geräteuhr · noch keine HTTP-Zeit vom Panel empfangen" : "Panel-HTTP-Zeit, ungefähr · keine Messung der Minecraft-Serveruhr").font(.caption).foregroundStyle(.secondary)
                }
            }
            Picker("Zeitzone des Panels", selection: $zone) { ForEach(zones, id: \.self) { Text($0.isEmpty ? "Unbekannt · bitte bestätigen" : $0).tag($0) } }
                .onChange(of: zone) { _, value in UserDefaults.standard.set(value, forKey: ScheduleTime.zoneKey + api.base.absoluteString) }
        } footer: { Text("Die Standard-Client-API liefert keine Panel-Zeitzone und keine Spielserveruhr. Bestätige die tatsächliche Panel-Zeitzone beim Hoster. Diese Auswahl ändert nur die Anzeige und Eingabe in der App. Die Ausführung übernimmt das Panel.") }
    }
    func formatted(_ date: Date, zone: TimeZone) -> String { { let formatter = DateFormatter(); formatter.locale = Locale(identifier: "de_DE"); formatter.timeZone = zone; formatter.dateFormat = "HH:mm:ss z"; return formatter.string(from: date) }() }
}
struct ScheduleEditor: View {
    let api: PanelAPI
    let path: String
    let initial: JSON
    let done: () async -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var name: String
    @State private var mode: String
    @State private var time: Date
    @State private var weekday: Int
    @State private var interval = 15
    @State private var minute: String
    @State private var hour: String
    @State private var day: String
    @State private var month: String
    @State private var week: String
    @State private var active: Bool
    @State private var onlyOnline: Bool
    @State private var busy = false
    @State private var error: String?
    @State private var zoneID: String
    @State private var fieldsChanged = false
    init(api: PanelAPI, path: String, initial: JSON = .null, done: @escaping () async -> Void) {
        self.api = api; self.path = path; self.initial = initial; self.done = done
        let fields = scheduleFields(initial); func field(_ key: String) -> String { fields.first { $0.key == key }?.value ?? "*" }
        let h = field("hour"), m = field("minute"), d = field("day_of_month"), mo = field("month"), w = field("day_of_week")
        _name = State(initialValue: initial["name"].string); _minute = State(initialValue: m); _hour = State(initialValue: h); _day = State(initialValue: d); _month = State(initialValue: mo); _week = State(initialValue: w)
        let simple = Int(h) != nil && Int(m) != nil && d == "*" && mo == "*"
        _mode = State(initialValue: simple && w == "*" ? "daily" : simple && Int(w) != nil ? "weekly" : "advanced")
        _weekday = State(initialValue: Int(w) == 7 ? 0 : Int(w) ?? 1)
        let zone = ScheduleTime.zone(for: api.base) ?? .gmt
        _zoneID = State(initialValue: ScheduleTime.zone(for: api.base)?.identifier ?? "")
        var calendar = Calendar(identifier: .gregorian); calendar.timeZone = zone
        _time = State(initialValue: calendar.date(bySettingHour: Int(h) ?? 4, minute: Int(m) ?? 0, second: 0, of: Date()) ?? Date())
        _active = State(initialValue: initial == .null || initial["is_active"].bool); _onlyOnline = State(initialValue: initial["only_when_online"].bool)
    }
    var zone: TimeZone { TimeZone(identifier: zoneID) ?? .gmt }
    var body: some View {
        NavigationStack {
            Form {
                Section("Zeitplan") { TextField("Name", text: $name); Toggle("Aktiv", isOn: $active); Toggle("Nur bei laufendem Server", isOn: $onlyOnline) }
                PanelTimeView(api: api)
                Section("Wiederholung") {
                    Picker("Rhythmus", selection: $mode) { Text("Täglich").tag("daily"); Text("Wöchentlich").tag("weekly"); Text("Minutenintervall").tag("interval"); Text("Cron · Erweitert").tag("advanced") }
                        .onChange(of: mode) { _, _ in fieldsChanged = true }
                    if mode == "daily" || mode == "weekly" {
                        DatePicker("Uhrzeit im Panel", selection: $time, displayedComponents: .hourAndMinute).environment(\.timeZone, zone).environment(\.locale, Locale(identifier: "de_DE")).onChange(of: time) { _, _ in fieldsChanged = true }
                        if mode == "weekly" { Picker("Wochentag", selection: $weekday) { ForEach([1, 2, 3, 4, 5, 6, 0], id: \.self) { Text(["Sonntag", "Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag"][$0]).tag($0) } }.onChange(of: weekday) { _, _ in fieldsChanged = true } }
                        if !zoneID.isEmpty, let next = nextDaily {
                            LabeledContent("Nächste Uhrzeit in Deutschland", value: ScheduleTime.display(next))
                            Text("Die Wochentagsregel gilt in der Panel-Zeitzone. Bei unterschiedlichen Sommerzeitregeln kann sich die deutsche Uhrzeit im Jahresverlauf verschieben. Die API zeigt nach dem Speichern den verbindlichen nächsten Termin.").font(.caption).foregroundStyle(.secondary)
                        }
                    } else if mode == "interval" {
                        Picker("Alle", selection: $interval) { ForEach([1, 2, 5, 10, 15, 20, 30], id: \.self) { Text("\($0) Minuten").tag($0) } }
                    } else {
                        cronField("Minute", $minute); cronField("Stunde", $hour); cronField("Tag im Monat", $day); cronField("Monat", $month); cronField("Wochentag", $week)
                        Text("Listen, Bereiche und Schritte: 0,30 · 1-5 · */15. Sonderregeln vorhandener Zeitpläne bleiben bei reinen Namens-/Statusänderungen unverändert erhalten.").font(.caption).foregroundStyle(.secondary)
                    }
                    if zoneID.isEmpty && mode != "advanced" { Label("Bestätige zuerst die tatsächliche Panel-Zeitzone oben.", systemImage: "clock.badge.exclamationmark").foregroundStyle(.orange) }
                    Text(cronValues.joined(separator: " ")).font(.system(.footnote, design: .monospaced)).textSelection(.enabled)
                }
                Section { Text("Nach dem Speichern kannst du Aufgaben für Befehle, Backups oder Serveraktionen hinzufügen. Die Verzögerung einer Aufgabe zählt nach dem Ende der vorherigen Aufgabe.").font(.footnote).foregroundStyle(.secondary) }
            }.panelScreen().navigationTitle(initial == .null ? "Zeitplan erstellen" : "Zeitplan bearbeiten").navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() }.disabled(busy) }
                    ToolbarItem(placement: .confirmationAction) { Button { save() } label: { if busy { ProgressView() } else { Text("Speichern").bold() } }.disabled(busy || name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || (mode != "advanced" && zoneID.isEmpty)) }
                }.errorAlert($error).interactiveDismissDisabled(busy)
                .onReceive(NotificationCenter.default.publisher(for: UserDefaults.didChangeNotification)) { _ in
                    let selected = ScheduleTime.zone(for: api.base)?.identifier ?? ""
                    if selected != zoneID {
                        // Keep the displayed hour constant when confirming a different panel timezone.
                        var old = Calendar(identifier: .gregorian); old.timeZone = zone
                        let parts = old.dateComponents([.hour, .minute], from: time)
                        var new = old; new.timeZone = TimeZone(identifier: selected) ?? .gmt
                        time = new.date(bySettingHour: parts.hour ?? 4, minute: parts.minute ?? 0, second: 0, of: Date()) ?? time
                        zoneID = selected
                    }
                }
        }
    }
    var nextDaily: Date? {
        var calendar = Calendar(identifier: .gregorian); calendar.timeZone = zone
        var parts = calendar.dateComponents([.hour, .minute], from: time)
        if mode == "weekly" { parts.weekday = weekday + 1 }
        return calendar.nextDate(after: api.clock.now, matching: parts, matchingPolicy: .nextTime, repeatedTimePolicy: .first)
    }
    var cronValues: [String] {
        if mode == "advanced" { return [minute, hour, day, month, week] }
        if mode == "interval" { return ["*/\(interval)", "*", "*", "*", "*"] }
        var calendar = Calendar(identifier: .gregorian); calendar.timeZone = zone
        return [String(calendar.component(.minute, from: time)), String(calendar.component(.hour, from: time)), "*", "*", mode == "weekly" ? String(weekday) : "*"]
    }
    func cronField(_ label: String, _ binding: Binding<String>) -> some View { TextField(label, text: binding).autocorrectionDisabled().textInputAutocapitalization(.never).onChange(of: binding.wrappedValue) { _, _ in fieldsChanged = true } }
    func save() {
        busy = true
        Task { defer { busy = false }; do {
            let values = cronValues
            if initial == .null || fieldsChanged || mode != "advanced" {
                for (index, range) in [0...59, 0...23, 1...31, 1...12, 0...7].enumerated() { guard ScheduleTime.valid(values[index], range: range) else { throw PanelError(message: "Ungültige Cron-Regel: \(values[index]). Verwende Zahlen, *, Listen, Bereiche oder Schritte.") } }
            }
            let keys = ["minute", "hour", "day_of_month", "month", "day_of_week"]
            var body: [String: JSON] = ["name": .string(name.trimmingCharacters(in: .whitespacesAndNewlines)), "is_active": .bool(active), "only_when_online": .bool(onlyOnline)]
            for i in keys.indices { body[keys[i]] = .string(values[i]) }
            _ = try await api.request(path, method: "POST", body: .object(body)); await done(); dismiss()
        } catch { self.error = error.localizedDescription } }
    }
}

struct ScheduleTaskEditor: View {
    let api: PanelAPI
    let path: String
    let initial: JSON
    let count: Int
    let done: () async -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var action: String
    @State private var payload: String
    @State private var power: String
    @State private var position: Int
    @State private var delay: Int
    @State private var continueOnFailure: Bool
    @State private var busy = false
    @State private var error: String?
    init(api: PanelAPI, path: String, initial: JSON = .null, count: Int, done: @escaping () async -> Void) {
        self.api = api; self.path = path; self.initial = initial; self.count = count; self.done = done
        _action = State(initialValue: initial["action"].string.isEmpty ? "command" : initial["action"].string)
        _payload = State(initialValue: initial["payload"].string); _power = State(initialValue: initial["action"].string == "power" ? initial["payload"].string : "restart")
        _position = State(initialValue: initial == .null ? count + 1 : max(1, initial["sequence_id"].int))
        _delay = State(initialValue: initial["time_offset"].int); _continueOnFailure = State(initialValue: initial["continue_on_failure"].bool)
    }
    var body: some View {
        NavigationStack {
            Form {
                Section("Aktion") {
                    Picker("Typ", selection: $action) { Text("Konsolenbefehl").tag("command"); Text("Server steuern").tag("power"); Text("Backup").tag("backup") }
                    if action == "power" { Picker("Serveraktion", selection: $power) { Text("Starten").tag("start"); Text("Neustarten").tag("restart"); Text("Stoppen").tag("stop"); Text("Beenden erzwingen").tag("kill") }; if power == "kill" { Text("Beendet den Prozess ohne Speichern.").foregroundStyle(.orange) } }
                    else if action == "command" {
                        TextField("Befehl ohne /", text: $payload, axis: .vertical).font(.system(.body, design: .monospaced)).autocorrectionDisabled().textInputAutocapitalization(.never)
                        Menu("Minecraft-Vorlagen", systemImage: "wand.and.stars") { ForEach(["save-all", "list", "tps", "say Serverneustart in 60 Sekunden!"], id: \.self) { command in Button(command) { payload = command } } }
                    } else { TextField("Ausschlussmuster (optional)", text: $payload, axis: .vertical); Text("Ein Muster pro Zeile. Es gelten die Backup-Regeln deines Panels.").font(.caption).foregroundStyle(.secondary) }
                }
                Section {
                    Stepper("Position: \(position)", value: $position, in: 1...max(count + (initial == .null ? 1 : 0), 1))
                    Stepper("Verzögerung: \(delay) Sekunden", value: $delay, in: 0...900, step: 5)
                    TextField("Sekunden (0–900)", value: $delay, format: .number).keyboardType(.numberPad)
                    Toggle("Bei Fehler fortsetzen", isOn: $continueOnFailure)
                } header: { Text("Ablauf") } footer: { Text("Die Verzögerung zählt ab dem Ende der vorherigen Aufgabe. Das Panel führt den Ablauf auch bei geschlossener App aus.") }
            }.panelScreen().navigationTitle(initial == .null ? "Aufgabe hinzufügen" : "Aufgabe bearbeiten").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() }.disabled(busy) }; ToolbarItem(placement: .confirmationAction) { Button("Speichern") { save() }.disabled(busy || !(0...900).contains(delay) || (action == "command" && payload.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)) } }.errorAlert($error).interactiveDismissDisabled(busy)
        }
    }
    func save() { busy = true; Task { defer { busy = false }; do {
        _ = try await api.request(path, method: "POST", body: try JSON.from(["action": action, "payload": action == "power" ? power : payload, "sequence_id": position, "time_offset": delay, "continue_on_failure": continueOnFailure])); await done(); dismiss()
    } catch { self.error = error.localizedDescription } } }
}
