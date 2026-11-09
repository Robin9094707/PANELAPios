import SwiftUI
import UniformTypeIdentifiers

struct SuiteServerEventsView: View {
    let suite: SuiteAPI
    @State private var data: JSON = .null
    @State private var eventName = ""
    @State private var mode = "off"
    @State private var world = ""
    @State private var name = ""
    @State private var day = ""
    @State private var commands = ""
    @State private var chosenCommand = ""
    @State private var activate = false
    @State private var editing = ""
    @State private var pending: [String: JSON]?
    @State private var busy = false
    @State private var error: String?
    private let modes = [("off", "Aus"), ("known", "Nur bekannte Spieler"), ("one-connection", "Eine Verbindung je IP/Alt-Signal"), ("closed", "Nur mit Ausnahme")]

    var body: some View {
        Form {
            if data == .null { ProgressView() }
            else {
                statusSection
                schedulesSection
                if data["manage"].bool && !data["worlds"].array.isEmpty { formSection }
            }
        }
        .panelScreen()
        .navigationTitle("Event-Modus")
        .task { await load() }
        .refreshable { await load() }
        .confirmationDialog("Änderung bestätigen?", isPresented: Binding(get: { pending != nil }, set: { if !$0 { pending = nil } }), titleVisibility: .visible) {
            if let pending { Button("Anwenden") { Task { await apply(pending) } } }
        } message: { Text("Die Änderung gilt für diesen Minecraft-Server.") }
        .errorAlert($error)
    }

    private var statusSection: some View {
        Section("Serverstatus & Lockdown") {
            if suite.features.contains("automations-v1") {
                NavigationLink { SuiteAutomationsView(suite: suite) } label: { Label("Automationen · Wenn … dann …", systemImage: "wand.and.stars") }
            }
            if suite.features.contains("smart-restart-v1") && suite.can("data.operations") {
                NavigationLink { SuiteSmartRestartView(suite: suite) } label: { Label("Smart-Neustart", systemImage: "clock.arrow.2.circlepath") }
            }
            LabeledContent("Aktives Event", value: data["active"].string.isEmpty ? "Keines" : data["active"].string)
            LabeledContent("Lockdown", value: modes.first(where: { $0.0 == data["lockdown"].string })?.1 ?? "–")
            if data["manage"].bool {
                TextField("Event-Name", text: $eventName)
                Button("Event anzeigen") { pending = ["operation": .string("status"), "name": .string(eventName)] }
                if !data["active"].string.isEmpty {
                    Button("Event beenden", role: .destructive) { pending = ["operation": .string("status"), "name": .string("")] }
                }
                Picker("Beitritte", selection: $mode) { ForEach(modes.indices, id: \.self) { Text(modes[$0].1).tag(modes[$0].0) } }
                Button("Lockdown-Stufe anwenden") { pending = ["operation": .string("lockdown"), "mode": .string(mode)] }
                if data["lockdown"].string != "off" {
                    Button("Lockdown deaktivieren") { pending = ["operation": .string("lockdown"), "mode": .string("off")] }
                }
            }
        }.disabled(busy)
    }

    private var schedulesSection: some View {
        Section("Geplante Spieltage") {
            if data["items"].array.isEmpty { Text("Keine Termine geplant.").foregroundStyle(.secondary) }
            ForEach(data["items"].array, id: \.["id"].string) { item in
                VStack(alignment: .leading, spacing: 5) {
                    Text(item["name"].string).font(.headline)
                    Text(item["world"].string + " · Tag " + item["day"].string + (item["activate"].bool ? " · Event starten" : " · Alarm"))
                        .font(.caption).foregroundStyle(.secondary)
                    ForEach(Array(item["commands"].array.enumerated()), id: \.offset) { pair in
                        Text(pair.element.string).font(.caption.monospaced()).textSelection(.enabled)
                    }
                    if data["manage"].bool {
                        HStack {
                            Button("Bearbeiten") { edit(item) }.buttonStyle(.borderless)
                            Button("Löschen", role: .destructive) { pending = ["operation": .string("delete"), "id": item["id"]] }.buttonStyle(.borderless)
                        }
                    }
                }
            }
        }.disabled(busy)
    }

    private var formSection: some View {
        Section(editing.isEmpty ? "Spieltag planen" : "Termin bearbeiten") {
            Picker("Welt", selection: $world) {
                ForEach(data["worlds"].array, id: \.["name"].string) { entry in
                    Text(entry["name"].string + " · Tag " + entry["day"].string).tag(entry["name"].string)
                }
            }
            TextField("Ingame-Tag", text: $day).keyboardType(.numberPad)
            TextField("Titel", text: $name)
            Toggle("Event im Serverstatus starten", isOn: $activate)
            if data["commands"].bool {
                TextField("Befehle, einer pro Zeile", text: $commands, axis: .vertical)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                let suggestions = data["known"].array.map(\.string).filter { !$0.isEmpty }
                if !suggestions.isEmpty {
                    NavigationLink("Erkannte Befehle") {
                        List(suggestions, id: \.self) { command in
                            Button(command) { chosenCommand = command }.foregroundStyle(chosenCommand == command ? Color.accentColor : Color.primary)
                        }.navigationTitle("Befehle")
                    }
                    if !chosenCommand.isEmpty {
                        Button("Hinzufügen: " + chosenCommand) {
                            commands += (commands.isEmpty ? "" : "\n") + chosenCommand
                            chosenCommand = ""
                        }
                    }
                }
            }
            Button("Termin speichern") {
                guard let targetDay = Int(day) else { return }
                let lines = commands.split(separator: "\n").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
                pending = ["operation": .string("schedule"), "id": .string(editing), "world": .string(world),
                    "day": .number(Double(targetDay)), "name": .string(name), "activate": .bool(activate), "commands": .array(lines.map { .string($0) })]
            }.disabled(Int(day) == nil || name.trimmingCharacters(in: .whitespaces).isEmpty || world.isEmpty)
            if !editing.isEmpty { Button("Abbrechen") { edit(.null) } }
        }.disabled(busy)
    }

    private func edit(_ item: JSON) {
        editing = item["id"].string
        world = item == .null ? data["worlds"].array.first?["name"].string ?? "" : item["world"].string
        day = item["day"].string
        name = item["name"].string
        activate = item["activate"].bool
        commands = item["commands"].array.map(\.string).joined(separator: "\n")
    }

    private func load() async {
        do {
            data = try await suite.request("server-events")
            mode = data["lockdown"].string
            eventName = data["active"].string
            if world.isEmpty { edit(.null) }
        } catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }

    private func apply(_ change: [String: JSON]) async {
        guard !busy else { return }
        busy = true
        defer { busy = false; pending = nil }
        do {
            var body = change
            body["confirmed"] = .bool(true)
            _ = try await suite.post("server-events", body)
            edit(.null)
            await load()
        } catch { self.error = error.localizedDescription }
    }
}
