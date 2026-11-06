import SwiftUI

struct SuiteServerShortcuts: View {
    var query = ""
    @EnvironmentObject private var store: AppStore
    @ObservedObject private var connections = SuiteConnections.shared
    @ObservedObject private var preferences = SuiteQuickAccess.shared
    @ObservedObject private var navigation = SuiteNavigationInbox.shared
    @State private var editing = false
    var records: [SuiteConnection] {
        connections.values.filter { record in
            query.isEmpty || SuiteSearch.matches(query, in: connections.name(record, store: store) + " " + record.endpoint) || preferences.tools(for: record.id).contains { $0.matches(query) }
        }
    }
    var body: some View {
        if !connections.values.isEmpty {
            VStack(alignment: .leading, spacing: 14) {
                HStack {
                    Label("Server-Schnellzugriffe", systemImage: "bolt.fill").font(.headline)
                    Spacer()
                    Button("Anpassen", systemImage: "slider.horizontal.3") { editing = true }.font(.caption)
                }
                ForEach(records) { record in serverCard(record) }
            }
            .sheet(isPresented: $editing) { SuiteServerShortcutSettings().environmentObject(store) }
            .onChange(of: navigation.request?.id) { _, id in if id != nil { editing = false } }
        }
    }
    func serverCard(_ record: SuiteConnection) -> some View {
        let title = connections.name(record, store: store)
        return VStack(alignment: .leading, spacing: 12) {
            HStack {
                Label(title, systemImage: "server.rack").font(.headline)
                if preferences.preferred == record.id { Image(systemName: "star.fill").font(.caption).foregroundStyle(.orange).accessibilityLabel("Standardserver") }
                Spacer()
                Button("Admin Suite öffnen", systemImage: "chevron.right") { open(.overview, record) }.labelStyle(.iconOnly).accessibilityLabel(title + " · Admin Suite öffnen")
            }
            Text(record.endpoint).font(.caption2).foregroundStyle(.secondary).lineLimit(1)
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 120), spacing: 10)], spacing: 10) {
                ForEach(preferences.tools(for: record.id)) { tool in
                    Button { open(tool, record) } label: {
                        Label(tool == .overview ? "Admin Suite" : tool.title, systemImage: tool.symbol)
                            .font(.subheadline.weight(.medium)).frame(maxWidth: .infinity, minHeight: 38, alignment: .leading)
                            .padding(.horizontal, 10).background((tool == .advertising ? Color.orange : Color.panelAccent).opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
                    }.buttonStyle(.plain).accessibilityLabel(title + " · " + tool.title)
                        .accessibilityIdentifier("suite-quick-" + record.id.uuidString + "-" + tool.id)
                        .contextMenu {
                            Button("Kurzbefehle-Link kopieren", systemImage: "link") { Clipboard.copy(SuiteLaunchRequest(tool: tool, connection: record.id).url.absoluteString) }
                            Button("Als Standardserver verwenden", systemImage: "star") { preferences.prefer(record.id) }
                        }
                }
            }
            if preferences.tools(for: record.id).isEmpty { Button("Schnellzugriffe auswählen") { editing = true } }
        }.card()
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
                    Text("Jede Kachel öffnet genau den zugeordneten Server. Der Standardserver gilt zusätzlich für die allgemeinen Schnellzugriffe ohne Serverangabe.")
                    Button { preferences.prefer(nil) } label: { Label("Bei allgemeinen Zugriffen nachfragen", systemImage: preferences.preferred == nil ? "checkmark.circle.fill" : "circle") }
                }
                ForEach(connections.values) { record in
                    Section(connections.name(record, store: store)) {
                        TextField("Servername", text: Binding(get: { connections.name(record, store: store) }, set: { connections.rename(record, name: $0) }))
                        Text(record.endpoint).font(.caption).foregroundStyle(.secondary)
                        Button { preferences.prefer(record.id) } label: { Label("Standardserver", systemImage: preferences.preferred == record.id ? "checkmark.circle.fill" : "circle") }
                        ForEach(SuiteQuickAccess.choices) { tool in
                            Toggle(tool.title, isOn: Binding(get: { preferences.tools(for: record.id).contains(tool) }, set: { preferences.set(tool, for: record.id, enabled: $0) }))
                        }
                    }
                }
            }.panelScreen().navigationTitle("Server-Schnellzugriffe")
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Fertig") { dismiss() } } }
        }
    }
}
