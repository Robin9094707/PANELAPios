import SwiftUI

struct SuiteServerShortcuts: View {
    var query = ""
    @EnvironmentObject private var store: AppStore
    @ObservedObject private var connections = SuiteConnections.shared
    @ObservedObject private var preferences = SuiteQuickAccess.shared
    @ObservedObject private var navigation = SuiteNavigationInbox.shared
    @State private var editing = false
    @State private var quickCenter: SuiteConnection?
    var records: [SuiteConnection] {
        connections.values.filter { record in
            !preferences.isHidden(record.id) && (query.isEmpty || SuiteSearch.matches(query, in: preferences.name(record, store: store) + " " + record.endpoint) || preferences.tools(for: record.id).contains { $0.matches(query) })
        }
    }
    var body: some View {
        if !connections.values.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Label("Server-Schnellzugriffe", systemImage: "bolt.fill").font(.headline)
                    Spacer()
                    Button("Anpassen", systemImage: "slider.horizontal.3") { editing = true }.font(.caption)
                }
                ForEach(records) { record in serverCard(record) }
            }
            .sheet(isPresented: $editing) { SuiteServerShortcutSettings().environmentObject(store) }
            .sheet(item: $quickCenter) { SuiteQuickCenterEditor(record: $0) }
            .onChange(of: navigation.request?.id) { _, id in if id != nil { editing = false } }
        }
    }
    func serverCard(_ record: SuiteConnection) -> some View {
        VStack(alignment: .leading, spacing: preferences.compact(record.id) ? 5 : 12) {
            serverHeader(record)
            if !preferences.compact(record.id) { Text(record.endpoint).font(.caption2).foregroundStyle(.secondary).lineLimit(1) }
            LazyVGrid(columns: [GridItem(.adaptive(minimum: preferences.compact(record.id) ? 110 : 138), spacing: 6)], spacing: 6) {
                ForEach(preferences.tools(for: record.id)) { tool in
                    shortcutButton(tool, record: record)
                }
            }
            if preferences.tools(for: record.id).isEmpty {
                Button("Schnellzugriffe auswählen") { editing = true }
            }
            if !preferences.actions(for: record.id).isEmpty { SuiteQuickCenterRow(record: record) }
        }.padding(preferences.compact(record.id) ? 10 : 16).background(.thinMaterial, in: RoundedRectangle(cornerRadius: 14))
    }
    func serverHeader(_ record: SuiteConnection) -> some View {
        let title = preferences.name(record, store: store)
        return HStack {
            Label(title, systemImage: "server.rack").font(.headline)
            if preferences.preferred == record.id {
                Image(systemName: "star.fill").font(.caption).foregroundStyle(.orange).accessibilityLabel("Standardserver")
            }
            Spacer()
            Button("Schnellzentrale bearbeiten", systemImage: "bolt.circle") { quickCenter = record }
                .labelStyle(.iconOnly).accessibilityLabel(title + " · Schnellzentrale bearbeiten")
            Button("Admin Suite öffnen", systemImage: "chevron.right") { open(.overview, record) }
                .labelStyle(.iconOnly).accessibilityLabel(title + " · Admin Suite öffnen")
        }
    }
    func shortcutButton(_ tool: SuiteTool, record: SuiteConnection) -> some View {
        let title = preferences.name(record, store: store)
        let tint: Color = tool == .advertising ? .orange : .panelAccent
        return Button { open(tool, record) } label: {
            Label(tool.title, systemImage: tool.symbol)
                .font((preferences.compact(record.id) ? Font.caption : .subheadline).weight(.medium))
                .lineLimit(2)
                .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                .padding(.horizontal, 7)
                .background(tint.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(title + " · " + tool.title)
        .accessibilityIdentifier("suite-quick-" + record.id.uuidString + "-" + tool.id)
        .contextMenu {
            Button("Kurzbefehle-Link kopieren", systemImage: "link") {
                Clipboard.copy(SuiteLaunchRequest(tool: tool, connection: record.id).url.absoluteString)
            }
            Button("Als Standardserver verwenden", systemImage: "star") { preferences.prefer(record.id) }
            Button("Server ausblenden", systemImage: "eye.slash") { preferences.hide(record.id, true) }
        }
    }
    func open(_ tool: SuiteTool, _ record: SuiteConnection) {
        navigation.request = preferences.request(tool: tool, connection: record.id, records: connections.values)
    }
}

struct SuiteServerShortcutSettings: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var connections = SuiteConnections.shared
    @ObservedObject private var preferences = SuiteQuickAccess.shared
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Button { preferences.prefer(nil) } label: { Label("Bei allgemeinen Zugriffen nachfragen", systemImage: preferences.preferred == nil ? "checkmark.circle.fill" : "circle") }
                } footer: { Text("Änderungen werden mit dem jeweiligen Admin-Suite-Konto synchronisiert.") }
                Section("Admin-Suite-Server") {
                    ForEach(connections.values) { record in
                        NavigationLink {
                            SuiteServerShortcutEditor(record: record)
                        } label: {
                            Label(preferences.name(record, store: store), systemImage: preferences.isHidden(record.id) ? "eye.slash" : "server.rack")
                        }
                    }
                }
                if let api = store.api {
                    Section("Weitere Panel-Server") {
                        ForEach(store.servers.filter { connections.get(api, $0) == nil }) { server in
                            Toggle(server.name, isOn: Binding(get: { !store.isHidden(server) }, set: { store.setHidden(server, !$0) }))
                        }
                    }
                }
            }.panelScreen().navigationTitle("Server-Schnellzugriffe")
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Fertig") { dismiss() } } }
        }
    }
}

private struct SuiteServerShortcutEditor: View {
    let record: SuiteConnection
    @EnvironmentObject private var store: AppStore
    @ObservedObject private var preferences = SuiteQuickAccess.shared
    @State private var name = ""
    @State private var search = ""

    var body: some View {
        List {
            Section {
                TextField("Servername", text: $name).onSubmit { preferences.rename(record.id, name) }
                Button("Namen speichern") { preferences.rename(record.id, name) }
                Toggle("Server in der App anzeigen", isOn: Binding(get: { !preferences.isHidden(record.id) }, set: { preferences.hide(record.id, !$0) }))
                Toggle("Kompakte Ansicht", isOn: Binding(get: { preferences.compact(record.id) }, set: { preferences.setCompact(record.id, $0) }))
                Button { preferences.prefer(record.id) } label: { Label("Standardserver", systemImage: preferences.preferred == record.id ? "checkmark.circle.fill" : "circle") }
                    .disabled(preferences.isHidden(record.id))
                if let error = preferences.errors[record.id] {
                    Text("Lokal gespeichert. Synchronisierung: " + error).font(.caption).foregroundStyle(.secondary)
                    Button("Erneut synchronisieren") { Task { await preferences.synchronize(record) } }
                }
            }
            Section("Reihenfolge der Schnellzugriffe") {
                ForEach(preferences.tools(for: record.id)) { tool in Label(tool.title, systemImage: tool.symbol) }
                    .onMove { from, to in
                        var tools = preferences.tools(for: record.id)
                        tools.move(fromOffsets: from, toOffset: to)
                        preferences.setTools(tools, for: record.id)
                    }
            }
            Section("Werkzeuge auswählen") {
                ForEach(SuiteQuickAccess.choices.filter { search.isEmpty || $0.matches(search) }) { tool in
                    Toggle(tool.title, isOn: Binding(get: { preferences.tools(for: record.id).contains(tool) }, set: { preferences.set(tool, for: record.id, enabled: $0) }))
                }
            }
        }
        .panelScreen()
        .navigationTitle(preferences.name(record, store: store))
        .searchable(text: $search, prompt: "Werkzeuge suchen")
        .toolbar { EditButton() }
        .task { name = preferences.name(record, store: store); await preferences.synchronize(record) }
    }
}
