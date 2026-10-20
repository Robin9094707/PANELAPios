import SwiftUI

struct ConfigurationForm: View {
    @Binding var text: String
    let properties: Bool
    @Environment(\.dismiss) private var dismiss
    @State private var rows: [ConfigurationField] = []
    @State private var edits: [Int: String] = [:]
    @State private var search = ""
    @State private var error: String?
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Label(properties ? "Server-Eigenschaften" : "YAML-Einstellungen", systemImage: "slider.horizontal.3").font(.headline).foregroundStyle(.teal)
                    Text("Schalter für Wahr/Falsch, Auswahlfelder für bekannte Minecraft-Eigenschaften und Eingabefelder für weitere Werte. Übernehmen ändert zuerst den Text; gespeichert wird erst mit Speichern im Editor. Komplexe YAML-Strukturen bleiben im Texteditor. Bis 500 Felder / 512 KiB.").font(.footnote).foregroundStyle(.secondary)
                }
                ForEach(rows.filter { search.isEmpty || $0.path.localizedCaseInsensitiveContains(search) }) { row in
                    Section {
                        let binding = Binding<String>(get: { edits[row.line] ?? row.value }, set: { edits[row.line] = $0 })
                        if row.kind == "bool" { Toggle(row.key, isOn: Binding(get: { binding.wrappedValue.lowercased() == "true" }, set: { binding.wrappedValue = $0 ? "true" : "false" })) }
                        else if !row.options.isEmpty {
                            Picker(row.key, selection: binding) { ForEach(Array(Set(row.options + [binding.wrappedValue])).sorted(), id: \.self) { Text($0).tag($0) } }
                        } else {
                            TextField(row.key, text: binding, axis: .vertical).font(.system(.body, design: .monospaced)).textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(row.kind == "number" ? .numbersAndPunctuation : .default)
                        }
                    } header: { Text(row.path) } footer: { Text("Zeile \(row.line + 1)") }
                }
                if rows.isEmpty { Text("Keine sicher bearbeitbaren Formularfelder gefunden. Nutze den Texteditor.").foregroundStyle(.secondary) }
            }.panelScreen().searchable(text: $search, prompt: "Einstellung suchen").navigationTitle("Formular").navigationBarTitleDisplayMode(.inline).errorAlert($error)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } }
                    ToolbarItem(placement: .confirmationAction) { Button("Übernehmen") { apply() } }
                }.onAppear { rows = ConfigurationField.parse(text, properties: properties) }
        }
    }
    func apply() {
        do {
            var lines = text.components(separatedBy: "\n")
            for row in rows { if let edit = edits[row.line], edit != row.value { lines[row.line] = try row.replacement(edit) } }
            text = lines.joined(separator: "\n"); dismiss()
        } catch { self.error = error.localizedDescription }
    }
}
