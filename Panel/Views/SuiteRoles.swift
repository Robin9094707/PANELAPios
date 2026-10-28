import SwiftUI

struct SuiteAdminAddView: View {
    let suite: SuiteAPI
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    @State private var rows: [JSON] = []
    @State private var error: String?
    @State private var loading = false
    var body: some View {
        List {
            Section { Text("Suche einen gespeicherten Spieler und öffne sein Rechteprofil. Erst eine Rolle oder ein Adminrecht gewährt Zugang.").font(.footnote).foregroundStyle(.secondary) }
            if loading { ProgressView() }
            ForEach(rows, id: \.["id"].string) { row in NavigationLink(row["name"].string) { SuitePermissionView(suite: suite, player: row["id"].string, admin: true) } }
        }.panelScreen().navigationTitle("Admin hinzufügen").searchable(text: $query, prompt: "Spielername oder UUID")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Fertig") { dismiss() } } }
            .task(id: query) {
                guard query.trimmingCharacters(in: .whitespaces).count >= 2 else { rows = []; return }
                do { try await Task.sleep(for: .milliseconds(300)); loading = true; defer { loading = false }; let result = try await suite.data("players", query: ["q":query,"filter":"all"]); try Task.checkCancellation(); rows = result["items"].array } catch is CancellationError {} catch { self.error = error.localizedDescription }
            }.errorAlert($error)
    }
}
struct SuiteRoleDirectory: View {
    let suite: SuiteAPI
    @State private var catalog: JSON = .null
    @State private var error: String?
    var body: some View {
        List {
            Section("Vorlagen") { ForEach(catalog["items"].array.filter { !$0["id"].string.hasPrefix("CUSTOM:") }, id: \.["id"].string) { role in VStack(alignment: .leading) { Text(role["name"].string); Text(SuiteRoleText.limit(role["maxBanSeconds"].string)).font(.caption).foregroundStyle(.secondary) } } }
            Section("Eigene Rollen") { ForEach(catalog["items"].array.filter { $0["id"].string.hasPrefix("CUSTOM:") }, id: \.["id"].string) { role in NavigationLink(role["name"].string) { SuiteCustomRoleView(suite:suite, catalog:catalog, role:role) } }
                NavigationLink { SuiteCustomRoleView(suite:suite, catalog:catalog) } label: { Label("Neue Rolle erstellen",systemImage:"plus.circle") }
            }
        }.panelScreen().navigationTitle("Adminrollen").task { await load() }.refreshable { await load() }.errorAlert($error)
    }
    func load() async { do { catalog = try await suite.data("management", query:["section":"roles"]) } catch { self.error=error.localizedDescription } }
}
enum SuiteRoleText {
    static func limit(_ seconds: String) -> String { if seconds == "0" { return "Unbegrenzte Banndauer" }; if seconds == "-1" { return "Keine Banns" }; if let n=Int(seconds), n % 86400 == 0 { return "Banns bis maximal \(n / 86400) Tage" }; return "Banns bis maximal \(seconds) Sekunden" }
}
struct SuiteCustomRoleView: View {
    let suite: SuiteAPI
    let catalog: JSON
    var role: JSON = .null
    @StateObject private var model: SuiteSecurityModel
    @State private var name = ""
    @State private var selected: Set<String> = []
    @State private var maxBan = "259200"
    @State private var alt = false
    @State private var id = "rolle-" + UUID().uuidString.prefix(8).lowercased()
    @State private var search = ""
    @State private var loaded = false
    init(suite:SuiteAPI,catalog:JSON,role:JSON = .null) { self.suite=suite; self.catalog=catalog;self.role=role;_model=StateObject(wrappedValue:SuiteSecurityModel(suite)) }
    var permissions: [JSON] { catalog["permissions"].array.filter { search.isEmpty || ($0["name"].string+$0["description"].string).localizedCaseInsensitiveContains(search) } }
    var groups: [String] { Array(Set(permissions.map { $0["group"].string })).sorted() }
    var body: some View {
        List {
            Section("Rollenprofil") { TextField("Anzeigename",text:$name); TextField("Maximale Banndauer in Sekunden",text:$maxBan).keyboardType(.numbersAndPunctuation); Text("259200 = 3 Tage · 0 = unbegrenzt · -1 = kein Bann").font(.caption).foregroundStyle(.secondary); Toggle("Alt-Account-Banns erlauben",isOn:$alt) }
            ForEach(groups,id:\.self) { group in Section(group) { ForEach(permissions.filter { $0["group"].string == group },id:\.["id"].string) { row in VStack(alignment:.leading) { Toggle(row["name"].string,isOn:Binding(get:{selected.contains(row["id"].string)},set:{ if $0 {selected.insert(row["id"].string)}else{selected.remove(row["id"].string)} }));Text(row["description"].string).font(.caption).foregroundStyle(.secondary) } } } }
            Section { Button("Rolle speichern",systemImage:"checkmark.shield") { save() }.disabled(name.trimmingCharacters(in:.whitespaces).isEmpty || Int(maxBan)==nil || model.busy || suite.scope != "full") }
        }.panelScreen().navigationTitle("Eigene Rolle").searchable(text:$search).errorAlert($model.error).task { guard !loaded else{return};loaded=true;if role != .null { id=String(role["id"].string.dropFirst(7));name=role["name"].string;maxBan=role["maxBanSeconds"].string;alt=role["altBans"].bool;selected=Set(role["permissions"].array.map(\.string)) } }
            .sheet(isPresented:Binding(get:{model.plan != nil},set:{if !$0{model.plan=nil}})) { SuiteSecurityConfirmation(model:model) {} }
    }
    func save() { Task { await model.prepare("management.apply",["operation":.string("custom-role"),"player":.string(id),"value":.string(name),"node":.string(selected.sorted().joined(separator:",")),"maxBanSeconds":.string(maxBan),"altBans":.string(String(alt))],summary:"Rolle „\(name)“ mit \(selected.count) Rechten speichern? Änderungen gelten für alle Mitglieder.\n\(SuiteRoleText.limit(maxBan))") } }
}
