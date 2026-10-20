import SwiftUI

struct SubuserEditor: View {
    let api: PanelAPI
    let server: Server
    let initial: JSON
    let done: () async -> Void
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var catalog: PermissionCatalogStore
    @ObservedObject private var access: ServerAccess
    @State private var email: String
    @State private var selected: Set<String>
    @State private var search = ""
    @State private var busy = false
    @State private var error: String?
    init(api: PanelAPI, server: Server, initial: JSON = .null, done: @escaping () async -> Void) {
        self.api = api; self.server = server; self.initial = initial; self.done = done; catalog = api.permissions; access = api.access
        _email = State(initialValue: initial["email"].string); _selected = State(initialValue: Set(initial["permissions"].array.map(\.string)))
    }
    var known: Set<String> { Set(catalog.groups.flatMap { $0.choices.map(\.id) }) }
    var missing: [String] { selected.subtracting(known).sorted() }
    var body: some View {
        NavigationStack {
            List {
                Section {
                    if initial == .null { TextField("E-Mail-Adresse", text: $email).keyboardType(.emailAddress).autocorrectionDisabled().textInputAutocapitalization(.never) }
                    else { LabeledContent("Mitbenutzer", value: email) }
                    Label("\(selected.count) Rechte ausgewählt", systemImage: "person.badge.key")
                    Text("Der Rechtekatalog und seine Beschreibungen werden direkt von deinem Panel geladen. Du kannst nur eigene Rechte weitergeben; die endgültige Prüfung übernimmt das Panel.").font(.caption).foregroundStyle(.secondary)
                }
                if catalog.loading { ProgressView("Verfügbare Rechte laden …") }
                if let issue = catalog.issue {
                    Section {
                        Text(issue).foregroundStyle(.orange)
                        Button("Rechtekatalog erneut laden") { Task { await catalog.load(api) } }
                    }
                }
                ForEach(catalog.groups) { group in
                    let choices = group.choices.filter { search.isEmpty || ($0.id + " " + $0.title + " " + $0.description + " " + group.title).localizedCaseInsensitiveContains(search) }
                    if !choices.isEmpty {
                        Section {
                            ForEach(choices) { permission in
                                Toggle(isOn: Binding(get: { selected.contains(permission.id) }, set: { value in if value { selected.insert(permission.id) } else { selected.remove(permission.id) } })) {
                                    VStack(alignment: .leading, spacing: 5) {
                                        Text(permission.title).font(.subheadline.weight(.semibold))
                                        Text(permission.id).font(.system(.caption, design: .monospaced)).foregroundStyle(.teal)
                                        if !permission.description.isEmpty { Text(permission.description).font(.caption).foregroundStyle(.secondary) }
                                        if !access.allows(server.id, permission.id) { Label("Nicht zum Weitergeben freigegeben", systemImage: "lock").font(.caption2).foregroundStyle(.orange) }
                                    }.padding(.vertical, 3)
                                }.disabled(!access.allows(server.id, permission.id) && !selected.contains(permission.id)).accessibilityIdentifier("permission-" + permission.id)
                            }
                        } header: { Text(group.title) } footer: { if !group.description.isEmpty { Text(group.description) } }
                    }
                }
                if !missing.isEmpty {
                    Section { ForEach(missing, id: \.self) { id in Toggle(id, isOn: Binding(get: { selected.contains(id) }, set: { if !$0 { selected.remove(id) } })).font(.system(.caption, design: .monospaced)) } } header: { Text("Vorhandene weitere Rechte") } footer: { Text("Diese bereits vergebenen Rechte fehlen im geladenen Katalog. Sie bleiben erhalten, solange du sie nicht abwählst.") }
                }
            }.panelScreen().navigationTitle(initial == .null ? "Mitbenutzer hinzufügen" : "Berechtigungen").navigationBarTitleDisplayMode(.inline)
                .searchable(text: $search, prompt: "Rechte suchen")
                .refreshable { await api.loadAccess(server: server.id); await catalog.load(api) }
                .task { await api.loadAccess(server: server.id); await catalog.load(api) }
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() }.disabled(busy) }
                    ToolbarItem(placement: .confirmationAction) { Button("Speichern") { save() }.disabled(busy || selected.isEmpty || email.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || (initial == .null && catalog.groups.isEmpty)) }
                }.errorAlert($error).interactiveDismissDisabled(busy)
        }
    }
    func save() { busy = true; Task { defer { busy = false }; do {
        var body: [String: JSON] = ["permissions": .array(selected.sorted().map(JSON.string))]
        if initial == .null { body["email"] = .string(email.trimmingCharacters(in: .whitespacesAndNewlines)) }
        let id = initial["uuid"].string.isEmpty ? initial["id"].string : initial["uuid"].string
        guard initial == .null || (!id.isEmpty && !id.contains("/")) else { throw PanelError(message: "Der Benutzer hat keine gültige API-ID.") }
        _ = try await api.request("servers/\(server.id)/users" + (initial == .null ? "" : "/" + id), method: "POST", body: .object(body))
        await done(); dismiss()
    } catch { self.error = error.localizedDescription } } }
}
