import SwiftUI

struct SuitePowerBotKeywordsView: View {
    let suite: SuiteAPI
    @State private var rules: [JSON] = []
    @State private var search = ""
    @State private var editor: Draft?
    @State private var deletion: Draft?
    @State private var loading = true
    @State private var busy = false
    @State private var error: String?

    private struct Draft: Identifiable {
        var id: String = ""
        var keyword: String = ""
        var instructions: String = ""
    }

    private var matches: [JSON] {
        let query = search.trimmingCharacters(in: .whitespacesAndNewlines)
        return query.isEmpty ? rules : rules.filter {
            $0["keyword"].string.localizedCaseInsensitiveContains(query)
                || $0["instructions"].string.localizedCaseInsensitiveContains(query)
        }
    }

    var body: some View {
        List {
            Section {
                if loading {
                    ProgressView("Stichwörter laden …")
                } else if matches.isEmpty {
                    Text(search.isEmpty ? "Noch keine Stichwörter." : "Keine Treffer.").foregroundStyle(.secondary)
                }
                ForEach(matches, id: \.["id"].string) { row in
                    let draft = Draft(id: row["id"].string, keyword: row["keyword"].string, instructions: row["instructions"].string)
                    Button {
                        editor = draft
                    } label: {
                        VStack(alignment: .leading, spacing: 5) {
                            Text(draft.keyword).font(.headline)
                            Text(draft.instructions.isEmpty ? "Allgemeine Moderationsregeln" : draft.instructions)
                                .font(.caption).foregroundStyle(.secondary).lineLimit(3)
                        }.frame(maxWidth: .infinity, alignment: .leading)
                    }.buttonStyle(.plain).disabled(busy || loading)
                    .swipeActions {
                        Button { deletion = draft } label: {
                            Label("Löschen", systemImage: "trash")
                        }.tint(.red).disabled(busy || loading)
                    }
                }
            } header: {
                Text("\(rules.count) Stichwortregeln")
            } footer: {
                Text("Ganze Wörter und Phrasen lösen eine KI-Prüfung aus. PowerBot bewertet die Nachricht mit der passenden Regel.")
            }
        }
        .panelScreen().navigationTitle("Stichwortregeln")
        .searchable(text: $search, prompt: "Stichwort oder Regel suchen")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("Stichwort hinzufügen", systemImage: "plus") { editor = Draft() }.disabled(busy || loading)
            }
        }
        .task { await load() }
        .refreshable { await load() }
        .sheet(item: $editor) { draft in
            KeywordEditor(draft: draft) { value in
                try await change("save", draft: value)
            }
        }
        .confirmationDialog("Stichwort löschen?", isPresented: Binding(get: { deletion != nil }, set: { if !$0 { deletion = nil } })) {
            if let deletion {
                Button("\(deletion.keyword) löschen", role: .destructive) {
                    Task {
                        do { try await change("delete", draft: deletion) }
                        catch { self.error = error.localizedDescription }
                    }
                }
            }
        }
        .errorAlert($error)
    }

    private func load() async {
        guard !busy else { return }
        loading = true
        defer { loading = false }
        do {
            let response = try await suite.request("powerbot/keywords", timeout: 12)
            try Task.checkCancellation()
            rules = response["items"].array
        } catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }

    private func change(_ operation: String, draft: Draft) async throws {
        guard !busy else { return }
        busy = true
        defer { busy = false }
        let result = try await suite.post("powerbot/keywords", [
            "operation": .string(operation), "id": .string(draft.id), "keyword": .string(draft.keyword),
            "instructions": .string(draft.instructions), "confirmed": .bool(true)
        ])
        rules = result["items"].array
    }

    private struct KeywordEditor: View {
        @Environment(\.dismiss) private var dismiss
        @State var draft: Draft
        @State private var saving = false
        @State private var error: String?
        let save: (Draft) async throws -> Void

        init(draft: Draft, save: @escaping (Draft) async throws -> Void) {
            _draft = State(initialValue: draft)
            self.save = save
        }

        var body: some View {
            NavigationStack {
                Form {
                    Section("Stichwort oder Phrase") {
                        TextField("Zum Beispiel SMP", text: $draft.keyword).textInputAutocapitalization(.never)
                    }
                    Section {
                        TextField("Was soll PowerBot prüfen?", text: $draft.instructions, axis: .vertical).lineLimit(4...8)
                    } header: {
                        Text("Eigene Prüfregel")
                    } footer: {
                        Text("Beschreibe, wann PowerBot durchlassen, warnen oder blockieren soll. Leer verwendet die allgemeinen Moderationsregeln.")
                    }
                }
                .navigationTitle(draft.id.isEmpty ? "Stichwort hinzufügen" : "Stichwort bearbeiten")
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() }.disabled(saving) }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Speichern") {
                            saving = true
                            Task {
                                defer { saving = false }
                                do { try await save(draft); dismiss() }
                                catch { self.error = error.localizedDescription }
                            }
                        }.disabled(saving || draft.keyword.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                                   || draft.keyword.utf16.count > 64 || draft.instructions.utf16.count > 500)
                    }
                }
                .errorAlert($error).interactiveDismissDisabled(saving)
            }
        }
    }
}
