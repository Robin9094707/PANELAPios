import SwiftUI

func scheduleFields(_ s: JSON = .null) -> [Field] {
    [Field(key: "name", label: "Name", value: s["name"].string),
     Field(key: "minute", label: "Minute", value: s["cron"]["minute"].string.isEmpty ? "0" : s["cron"]["minute"].string, hint: "0–59, * oder z. B. */15"),
     Field(key: "hour", label: "Stunde", value: s["cron"]["hour"].string.isEmpty ? "4" : s["cron"]["hour"].string, hint: "0–23. Es gilt die Zeitzone des Panels."),
     Field(key: "day_of_month", label: "Tag im Monat", value: s["cron"]["day_of_month"].string.isEmpty ? "*" : s["cron"]["day_of_month"].string),
     Field(key: "month", label: "Monat", value: s["cron"]["month"].string.isEmpty ? "*" : s["cron"]["month"].string),
     Field(key: "day_of_week", label: "Wochentag", value: s["cron"]["day_of_week"].string.isEmpty ? "*" : s["cron"]["day_of_week"].string, hint: "0 = Sonntag, 1 = Montag, …, 6 = Samstag"),
     Field(key: "is_active", label: "Aktiv", value: s == .null || s["is_active"].bool ? "true" : "false", kind: .toggle),
     Field(key: "only_when_online", label: "Nur bei laufendem Server", value: s["only_when_online"].bool ? "true" : "false", kind: .toggle)]
}
struct SchedulesView: View {
    let api: PanelAPI
    let server: Server
    @State private var schedules: [JSON] = []
    @State private var error: String?
    @State private var operation: Operation?
    var prefix: String { "servers/\(server.id)/schedules" }
    var body: some View {
        List {
            if schedules.isEmpty { ContentUnavailableView("Keine Zeitpläne", systemImage: "calendar.badge.clock", description: Text("Plane Befehle, Serveraktionen und Backups.")) }
            ForEach(schedules, id: \.["id"].string) { schedule in
                NavigationLink { ScheduleDetail(api: api, prefix: prefix, initial: schedule) } label: {
                    HStack(spacing: 14) {
                        Image(systemName: "calendar.badge.clock").font(.title2).foregroundStyle(.teal)
                        VStack(alignment: .leading, spacing: 6) {
                            Text(schedule["name"].string).font(.headline)
                            Text(schedule["is_active"].bool ? "Aktiv" : "Pausiert").font(.caption).foregroundStyle(schedule["is_active"].bool ? Color.green : Color.secondary)
                        }
                    }.padding(.vertical, 5)
                }
            }
        }.panelScreen().navigationTitle("Zeitpläne").navigationBarTitleDisplayMode(.inline).task { await load() }.refreshable { await load() }
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Neuer Zeitplan", systemImage: "plus") { operation = Operation(title: "Zeitplan erstellen", path: prefix, fields: scheduleFields(), submit: "Erstellen") } } }
            .sheet(item: $operation) { OperationSheet(api: api, operation: $0) { await load() } }.errorAlert($error)
    }
    func load() async { do { schedules = try await api.list(prefix) } catch { self.error = error.localizedDescription } }
}
struct ScheduleDetail: View {
    let api: PanelAPI
    let prefix: String
    let initial: JSON
    @Environment(\.dismiss) private var dismiss
    @State private var schedule: JSON = .null
    @State private var operation: Operation?
    @State private var error: String?
    var data: JSON { schedule == .null ? initial : schedule }
    var path: String { prefix + "/" + initial["id"].string }
    var tasks: [JSON] { data["relationships"]["tasks"].rows }
    var body: some View {
        List {
            Section("Zeitplan") {
                LabeledContent("Status", value: data["is_processing"].bool ? "Wird ausgeführt" : data["is_active"].bool ? "Aktiv" : "Pausiert")
                LabeledContent("Nächste Ausführung", value: data["next_run_at"].string.isEmpty ? "–" : data["next_run_at"].string)
                Text(["minute", "hour", "day_of_month", "month", "day_of_week"].map { data["cron"][$0].string.isEmpty ? "*" : data["cron"][$0].string }.joined(separator: " ")).font(.system(.body, design: .monospaced))
                Button("Zeitplan bearbeiten", systemImage: "pencil") { operation = Operation(title: "Zeitplan bearbeiten", path: path, fields: scheduleFields(data)) }
                Button("Jetzt ausführen", systemImage: "play") { operation = Operation(title: "Zeitplan ausführen", detail: "Alle Aufgaben dieses Zeitplans werden ausgeführt.", path: path + "/execute", submit: "Ausführen") }
            }
            Section("Aufgaben · \(tasks.count)") {
                ForEach(tasks, id: \.["id"].string) { task in
                    VStack(alignment: .leading, spacing: 8) {
                        HStack { Text(task["action"].string).font(.headline); Spacer(); Menu {
                            Button("Bearbeiten", systemImage: "pencil") { taskForm(task) }
                            Button("Löschen", systemImage: "trash", role: .destructive) { operation = Operation(title: "Aufgabe löschen", path: path + "/tasks/\(task["id"].string)", method: "DELETE", destructive: true, submit: "Löschen") }
                        } label: { Image(systemName: "ellipsis.circle").padding(6) } }
                        Text(task["payload"].string.isEmpty ? "Kein Payload" : task["payload"].string).font(.system(.footnote, design: .monospaced)).textSelection(.enabled)
                        Text("Verzögerung: \(task["time_offset"].string) s").font(.caption).foregroundStyle(.secondary)
                    }.padding(.vertical, 4)
                }
                Button("Aufgabe hinzufügen", systemImage: "plus") { taskForm(.null) }
            }
            Section { Button("Zeitplan löschen", systemImage: "trash", role: .destructive) { operation = Operation(title: "Zeitplan löschen", detail: "Alle zugehörigen Aufgaben werden ebenfalls entfernt.", path: path, method: "DELETE", destructive: true, submit: "Löschen") } }
        }.panelScreen().navigationTitle(data["name"].string).navigationBarTitleDisplayMode(.inline).task { await load() }.refreshable { await load() }.errorAlert($error)
            .sheet(item: $operation) { op in OperationSheet(api: api, operation: op) { if op.method == "DELETE" && op.path == path { dismiss() } else { await load() } } }
    }
    func load() async { guard !api.demo else { return }; do { schedule = try await api.request(path).attributes } catch { self.error = error.localizedDescription } }
    func taskForm(_ task: JSON) {
        operation = Operation(title: task == .null ? "Aufgabe hinzufügen" : "Aufgabe bearbeiten", detail: "command: Konsolenbefehl. power: start, stop, restart oder kill. backup: optionale Ausschlussmuster.", path: path + "/tasks" + (task == .null ? "" : "/\(task["id"].string)"), fields: [
            Field(key: "action", label: "Aktion", value: task["action"].string.isEmpty ? "command" : task["action"].string, options: ["command", "power", "backup"]),
            Field(key: "payload", label: "Befehl / Inhalt", value: task["payload"].string, kind: .multiline),
            Field(key: "sequence_id", label: "Position", value: task["sequence_id"].string.isEmpty ? String(tasks.count + 1) : task["sequence_id"].string, kind: .number, hint: "Aufgaben werden in dieser Reihenfolge ausgeführt."),
            Field(key: "time_offset", label: "Verzögerung in Sekunden", value: task["time_offset"].string.isEmpty ? "0" : task["time_offset"].string, kind: .number),
            Field(key: "continue_on_failure", label: "Bei Fehler fortsetzen", value: task["continue_on_failure"].bool ? "true" : "false", kind: .toggle)])
    }
}
