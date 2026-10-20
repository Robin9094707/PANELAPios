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
    @State private var editing = false
    @State private var error: String?
    @State private var operation: Operation?
    var prefix: String { "servers/\(server.id)/schedules" }
    var body: some View {
        List {
            PanelTimeView(api: api)
            if schedules.isEmpty { ContentUnavailableView("Keine Zeitpläne", systemImage: "calendar.badge.clock", description: Text("Plane Befehle, Serveraktionen und Backups.")) }
            ForEach(schedules, id: \.["id"].string) { schedule in
                NavigationLink { ScheduleDetail(api: api, prefix: prefix, initial: schedule) } label: {
                    HStack(spacing: 14) {
                        Image(systemName: "calendar.badge.clock").font(.title2).foregroundStyle(.teal)
                        VStack(alignment: .leading, spacing: 6) {
                            Text(schedule["name"].string).font(.headline)
                            Text(ScheduleTime.display(schedule["next_run_at"].string)).font(.caption).foregroundStyle(.secondary)
                            Text(schedule["is_active"].bool ? "Aktiv" : "Pausiert").font(.caption).foregroundStyle(schedule["is_active"].bool ? Color.green : Color.secondary)
                        }
                    }.padding(.vertical, 5)
                }
            }
        }.panelScreen().navigationTitle("Zeitpläne").navigationBarTitleDisplayMode(.inline).task { await load() }.refreshable { await load() }
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Neuer Zeitplan", systemImage: "plus") { editing = true }.requiresRequest(api, prefix, method: "POST") } }
            .sheet(item: $operation) { OperationSheet(api: api, operation: $0) { await load() } }
            .sheet(isPresented: $editing) { ScheduleEditor(api: api, path: prefix) { await load() } }.errorAlert($error)
    }
    func load() async { do { schedules = try await api.list(prefix) } catch { self.error = error.localizedDescription } }
}
struct ScheduleDetail: View {
    let api: PanelAPI
    let prefix: String
    let initial: JSON
    @Environment(\.dismiss) private var dismiss
    @State private var schedule: JSON = .null
    @State private var editing = false
    @State private var taskEditing: EditorItem?
    @State private var taskInitial: JSON = .null
    @State private var operation: Operation?
    @State private var error: String?
    var data: JSON { schedule == .null ? initial : schedule }
    var path: String { prefix + "/" + initial["id"].string }
    var tasks: [JSON] { data["relationships"]["tasks"].rows }
    var body: some View {
        List {
            PanelTimeView(api: api)
            Section("Zeitplan") {
                LabeledContent("Status", value: data["is_processing"].bool ? "Wird ausgeführt" : data["is_active"].bool ? "Aktiv" : "Pausiert")
                LabeledContent("Nächste Ausführung", value: ScheduleTime.display(data["next_run_at"].string))
                LabeledContent("Zuletzt ausgeführt", value: ScheduleTime.display(data["last_run_at"].string))
                Text(["minute", "hour", "day_of_month", "month", "day_of_week"].map { data["cron"][$0].string.isEmpty ? "*" : data["cron"][$0].string }.joined(separator: " ")).font(.system(.body, design: .monospaced))
                Button("Zeitplan bearbeiten", systemImage: "pencil") { editing = true }.requiresRequest(api, path, method: "POST")
                Button("Jetzt ausführen", systemImage: "play") { operation = Operation(title: "Zeitplan ausführen", detail: "Alle Aufgaben dieses Zeitplans werden ausgeführt.", path: path + "/execute", submit: "Ausführen") }.requiresRequest(api, path + "/execute", method: "POST")
            }
            Section("Aufgaben · \(tasks.count)") {
                ForEach(tasks, id: \.["id"].string) { task in
                    VStack(alignment: .leading, spacing: 8) {
                        HStack { Text("\(task["sequence_id"].string). " + (["command": "Konsolenbefehl", "power": "Serveraktion", "backup": "Backup"][task["action"].string] ?? task["action"].string)).font(.headline); Spacer(); Menu {
                            Button("Bearbeiten", systemImage: "pencil") { taskForm(task) }.requiresRequest(api, path + "/tasks")
                            Button("Löschen", systemImage: "trash", role: .destructive) { operation = Operation(title: "Aufgabe löschen", path: path + "/tasks/\(task["id"].string)", method: "DELETE", destructive: true, submit: "Löschen") }.requiresRequest(api, path + "/tasks/\(task["id"].string)", method: "DELETE")
                        } label: { Image(systemName: "ellipsis.circle").padding(6) } }
                        Text(task["payload"].string.isEmpty ? "Kein Payload" : task["payload"].string).font(.system(.footnote, design: .monospaced)).textSelection(.enabled)
                        Text("Verzögerung: \(task["time_offset"].string) s").font(.caption).foregroundStyle(.secondary)
                    }.padding(.vertical, 4)
                }
                Button("Aufgabe hinzufügen", systemImage: "plus") { taskForm(.null) }.requiresRequest(api, path + "/tasks")
            }
            Section { Button("Zeitplan löschen", systemImage: "trash", role: .destructive) { operation = Operation(title: "Zeitplan löschen", detail: "Alle zugehörigen Aufgaben werden ebenfalls entfernt.", path: path, method: "DELETE", destructive: true, submit: "Löschen") } }.requiresRequest(api, path, method: "DELETE")
        }.panelScreen().navigationTitle(data["name"].string).navigationBarTitleDisplayMode(.inline).task { await load() }.refreshable { await load() }.errorAlert($error)
            .sheet(isPresented: $editing) { ScheduleEditor(api: api, path: path, initial: data) { await load() } }
            .sheet(item: $taskEditing) { item in ScheduleTaskEditor(api: api, path: item.path, initial: taskInitial, count: tasks.count) { await load() } }
            .sheet(item: $operation) { op in OperationSheet(api: api, operation: op) { if op.method == "DELETE" && op.path == path { dismiss() } else { await load() } } }
    }
    func load() async { guard !api.demo else { return }; do { schedule = try await api.request(path).attributes } catch { self.error = error.localizedDescription } }
    func taskForm(_ task: JSON) {
        taskInitial = task
        taskEditing = EditorItem(path: path + "/tasks" + (task == .null ? "" : "/\(task["id"].string)"))
    }
}
