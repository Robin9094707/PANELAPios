import SwiftUI

struct SuiteManagementView: View {
    let suite: SuiteAPI
    let actions: [SuiteAction]
    @StateObject private var model: SuiteSecurityModel
    @State private var status: JSON = .null
    @State private var discord = ""
    init(suite: SuiteAPI, actions: [SuiteAction]) { self.suite = suite; self.actions = actions; _model = StateObject(wrappedValue: SuiteSecurityModel(suite)) }
    var body: some View {
        List {
            Section("Server") {
                Toggle("Wartungsmodus", isOn: Binding(get: { status["maintenance"].bool }, set: { value in Task { await model.prepare("management.apply", ["operation": .string("maintenance"), "value": .string(String(value))], summary: value ? "Wartungsmodus einschalten?" : "Wartungsmodus ausschalten?") } })).disabled(suite.scope != "full" || model.busy)
                Text(status["reason"].string).font(.footnote).foregroundStyle(.secondary)
                TextField("Discord-Link oder Einladungscode", text: $discord).textInputAutocapitalization(.never).autocorrectionDisabled()
                Button("Discord-Link speichern") { Task { await model.prepare("management.apply", ["operation": .string("discord"), "value": .string(discord)], summary: "Discord-Einladung ändern?\n" + discord) } }.disabled(suite.scope != "full")
            }
            Section("Verwaltung") {
                if suite.features.contains("restart-v1") { NavigationLink("Neustart planen & abbrechen") { SuiteRestartView(suite: suite) } }
                NavigationLink("Suite-Administratoren & Rollen") { SuiteAdminDirectory(suite: suite) }
                NavigationLink("Geräte, Betriebssysteme & Java-Brands") { SuiteAccessRulesView(suite: suite, actions: actions) }
                NavigationLink("Plugin-Bibliotheken prüfen") { SuiteDependencyView(suite: suite) }
                if suite.scope == "full" { NavigationLink("Java-/Bedrock-Adminmenüs") { SuiteRemoteMenuView(suite: suite) } }
                NavigationLink("Startseiten-Aktionen anpassen") { SuiteShortcutEditor(suite: suite, actions: actions) }
            }
        }.panelScreen().navigationTitle("Suite-Verwaltung").errorAlert($model.error).task { await load() }.refreshable { await load() }
            .sheet(isPresented: Binding(get: { model.plan != nil }, set: { if !$0 { model.plan = nil } })) { SuiteSecurityConfirmation(model: model) { await load() } }
    }
    func load() async { do { status = try await suite.data("management", query: ["section": "status"]); discord = status["discord"].string } catch { model.error = error.localizedDescription } }
}
struct SuiteAdminDirectory: View {
    let suite: SuiteAPI
    @State private var rows: [JSON] = []
    @State private var search = ""
    @State private var error: String?
    @State private var adding = false
    @State private var refreshing = false
    var body: some View {
        List {
            Section { Text("Hier stehen nur Operatoren und Spieler mit Admin-Zugang. Persönliche Einstellungen zählen nicht dazu. Geerbte Offline-Rechte werden über LuckPerms geprüft.").font(.footnote).foregroundStyle(.secondary)
                if refreshing { ProgressView("LuckPerms-Rechte werden geprüft …") }
            }
            ForEach(rows.filter { search.isEmpty || ($0["name"].string + $0["role"].string).localizedCaseInsensitiveContains(search) }, id: \.["id"].string) { row in
                NavigationLink { SuitePermissionView(suite: suite, player: row["id"].string, admin: true) } label: {
                    HStack { Image(systemName: row["operator"].bool ? "crown.fill" : "person.badge.shield.checkmark.fill").foregroundStyle(row["operator"].bool ? .orange : Color.accentColor)
                        VStack(alignment: .leading) { Text(row["name"].string); Text(row["operator"].bool ? "Hauptoperator · geschützt" : row["role"].string).font(.caption).foregroundStyle(.secondary) }
                    }
                }
            }
            if rows.isEmpty && !refreshing { ContentUnavailableView("Keine weiteren Administratoren", systemImage: "person.2.badge.key.fill", description: Text("Füge einen gespeicherten Spieler hinzu und weise ihm eine Rolle zu.")) }
            Section { Button("Spieler hinzufügen", systemImage: "person.badge.plus") { adding = true }.disabled(suite.scope != "full")
                NavigationLink("Eigene Rollen verwalten") { SuiteRoleDirectory(suite: suite) }.disabled(suite.scope != "full")
            }
        }.panelScreen().navigationTitle("Admin-Team").searchable(text: $search).task { await load() }.refreshable { await load() }.errorAlert($error)
            .sheet(isPresented: $adding, onDismiss: { Task { await load() } }) { NavigationStack { SuiteAdminAddView(suite: suite) } }
            .task(id: refreshing) { guard refreshing else { return }; for _ in 0..<15 { do { try await Task.sleep(for: .seconds(3)); await load(); if !refreshing { break } } catch { break } } }
    }
    func load() async { do { let result = try await suite.data("management", query: ["section":"admins"]); rows = result["items"].array; refreshing = result["refreshing"].bool } catch { self.error = error.localizedDescription } }
}
struct SuitePermissionView: View {
    let suite: SuiteAPI
    let player: String
    var admin = false
    @StateObject private var model: SuiteSecurityModel
    @State private var data: JSON = .null
    @State private var search = ""
    @State private var maxBan = "259200"
    @State private var altBans = false
    init(suite: SuiteAPI, player: String, admin: Bool = false) { self.suite=suite; self.player=player; self.admin=admin; _model=StateObject(wrappedValue: SuiteSecurityModel(suite)) }
    var groups: [String] { Array(Set(filtered.map { $0["group"].string.isEmpty ? "Berechtigungen" : $0["group"].string })).sorted() }
    var filtered: [JSON] { data["items"].array.filter { search.isEmpty || ($0["name"].string + $0["description"].string + $0["id"].string).localizedCaseInsensitiveContains(search) } }
    var locked: Bool { model.busy || suite.scope != "full" || data["protected"].bool }
    var body: some View {
        List {
            if admin {
                if data["protected"].bool { Label("Hauptoperator · Rechte geschützt", systemImage: "crown.fill").foregroundStyle(.orange) }
                Section("Rolle") {
                    Picker("Zugriffsprofil", selection: Binding(get: { data["role"].string }, set: { change("admin-role", value: $0) })) {
                        ForEach(data["roles"].array, id: \.["id"].string) { role in Text(role["name"].string).tag(role["id"].string) }
                    }.disabled(locked)
                    Text("Einzelrechte haben Vorrang vor der Rolle. /sas bleibt bei jedem Adminrecht erreichbar; nicht erlaubte Aktionen werden serverseitig gesperrt.").font(.caption).foregroundStyle(.secondary)
                }
                Section("Bannschutz") {
                    TextField("Maximale Banndauer in Sekunden", text: $maxBan).keyboardType(.numbersAndPunctuation)
                    Text("259200 = 3 Tage · 0 = unbegrenzt · -1 = keine Banns. Eine eigene Rolle legt ihr Limit für alle Mitglieder fest.").font(.caption).foregroundStyle(.secondary)
                    Toggle("Alt-Account-Banns erlauben", isOn: $altBans)
                    Button("Bannschutz speichern") { Task { await model.prepare("management.apply", ["operation":.string("admin-limits"), "player":.string(player), "value":.string(maxBan), "altBans":.string(String(altBans))], summary:"Bannschutz ändern: \(maxBan) Sekunden; Alt-Account-Banns: \(altBans ? "erlaubt" : "gesperrt")") } }
                }.disabled(locked || data["role"].string.hasPrefix("CUSTOM:"))
            } else { Section { Toggle("Alt-Accounts-Vererbung", isOn: Binding(get: { data["inherit"] == .null || data["inherit"].bool }, set: { change("inherit", value: String($0)) })).disabled(locked) } }
            ForEach(groups, id: \.self) { group in
                Section(group) { ForEach(filtered.filter { ($0["group"].string.isEmpty ? "Berechtigungen" : $0["group"].string) == group }, id: \.["id"].string) { row in
                    VStack(alignment: .leading, spacing: 6) {
                        Toggle(row["name"].string, isOn: Binding(get: { row["allowed"].bool }, set: { change(admin ? "admin-right" : "right", node: row["id"].string, value: String($0)) })).disabled(locked)
                        Text(row["description"].string).font(.caption).foregroundStyle(.secondary)
                        if row["unknown"].bool { Text("Geerbte Offline-Rechte noch nicht geprüft").font(.caption).foregroundStyle(.orange) }
                        if admin { HStack { Text(row["override"].string == "inherit" ? "Von Rolle / LuckPerms" : "Ausdrückliche Einzelregel"); Spacer(); Button("Zurücksetzen") { change("admin-right", node: row["id"].string, value: "inherit") }.disabled(locked) }.font(.caption) }
                    }
                } }
            }
        }.panelScreen().navigationTitle(admin ? "Adminrechte" : "Spielerrechte").searchable(text: $search).task { await load() }.refreshable { await load() }.errorAlert($model.error)
            .sheet(isPresented: Binding(get: { model.plan != nil }, set: { if !$0 { model.plan = nil } })) { SuiteSecurityConfirmation(model: model) { await load() } }
    }
    func change(_ operation: String, node: String = "", value: String) { Task { await model.prepare("management.apply", ["operation": .string(operation),"player": .string(player),"node": .string(node),"value": .string(value)], summary: "Berechtigung ändern: \(node) → \(value)\nSpieler: \(player)") } }
    func load() async { do { data = try await suite.data("management", query: ["section":admin ? "admin-rights":"rights","player":player]); maxBan=data["maxBanSeconds"].string; altBans=data["altBans"].bool } catch { model.error=error.localizedDescription } }
}
struct SuiteProbeView: View {
    let suite: SuiteAPI
    let player: String
    @StateObject private var model: SuiteSecurityModel
    @State private var report: JSON = .null
    @State private var waiting = false
    @State private var baseline = 0.0
    init(suite: SuiteAPI, player: String) { self.suite=suite;self.player=player;_model=StateObject(wrappedValue: SuiteSecurityModel(suite)) }
    var body: some View {
        List {
            Section { Button("Alle Client-Checks starten", systemImage:"shield.checkered") { Task { baseline=report["at"].double; await model.prepare("management.apply", ["operation":.string("probe"),"player":.string(player)], summary:"Alle aktivierten Signaturen für diesen Online-Spieler prüfen? Die konfigurierten Prüfregeln gelten.") } }.disabled(waiting || model.busy || suite.scope != "full"); if waiting || report["active"].bool { ProgressView("Prüfung läuft …") }; Text(report["status"].string).font(.caption).foregroundStyle(.secondary) }
            ForEach(report["items"].array,id:\.["id"].string) { row in HStack { Text(row["name"].string); Spacer(); Text(row["result"].string).font(.caption.bold()).foregroundStyle(row["result"].string == "DETECTED" ? .red : row["result"].string == "NOT_DETECTED" ? .green : .orange) } }
        }.panelScreen().navigationTitle("Client-Prüfung").errorAlert($model.error).task { await load() }.refreshable { await load() }
            .task(id:waiting) { guard waiting else{return}; for _ in 0..<900 { do { try await Task.sleep(for:.seconds(2)); await load(); if !report["active"].bool && report["at"].double > baseline { waiting=false;break } } catch { break } }; if !Task.isCancelled { waiting=false } }
            .sheet(isPresented:Binding(get:{model.plan != nil},set:{if !$0 {model.plan=nil}})) { SuiteSecurityConfirmation(model:model) { waiting=true;await load() } }
    }
    func load() async { do { report=try await suite.data("management",query:["section":"probe","player":player]) } catch { model.error=error.localizedDescription;waiting=false } }
}
struct SuiteAccessRulesView: View {
    let suite: SuiteAPI
    let actions:[SuiteAction]
    @StateObject private var model: SuiteSecurityModel
    @State private var brand=""
    @State private var blocked=true
    init(suite:SuiteAPI,actions:[SuiteAction]) {self.suite=suite;self.actions=actions;_model=StateObject(wrappedValue:SuiteSecurityModel(suite))}
    var body:some View { List {
        Section("Bedrock") { ForEach(actions.filter{["moderation.devices","moderation.os"].contains($0.id)}) { action in NavigationLink(action.title) {SuiteActionView(suite:suite,action:action)} } }
        Section("Java-Brand exakt sperren / freigeben") {TextField("Exakter Java-Brand",text:$brand).textInputAutocapitalization(.never);Toggle("Gesperrt",isOn:$blocked);Button("Regel prüfen") {Task{await model.prepare("management.apply",["operation":.string("brand"),"node":.string(brand),"value":.string(String(blocked))],summary:"Brand \(brand): \(blocked ? "sperren":"freigeben")? Ein Brand ist eine fälschbare Selbstangabe.")}}.disabled(brand.isEmpty || suite.scope != "full")}
    }.panelScreen().navigationTitle("Zugriffsregeln").errorAlert($model.error).sheet(isPresented:Binding(get:{model.plan != nil},set:{if !$0{model.plan=nil}})){SuiteSecurityConfirmation(model:model){}} }
}
struct SuiteShortcutEditor:View {
    let suite:SuiteAPI;let actions:[SuiteAction]
    @AppStorage private var stored:String
    @State private var search=""
    init(suite:SuiteAPI,actions:[SuiteAction]){self.suite=suite;self.actions=actions;_stored=AppStorage(wrappedValue:"", "suite.shortcuts."+suite.connection.serverIdentity)}
    var body:some View {List{ForEach(actions.filter{!$0.id.hasPrefix("menu.") && (search.isEmpty || $0.title.localizedCaseInsensitiveContains(search))}){action in Toggle(action.title,isOn:Binding(get:{stored.split(separator:"|").contains(Substring(action.id))},set:{value in var ids=Set(stored.split(separator:"|").map(String.init));if value{ids.insert(action.id)}else{ids.remove(action.id)};stored=ids.sorted().joined(separator:"|")}))}}.panelScreen().navigationTitle("Startseiten-Aktionen").searchable(text:$search)}
}
struct SuitePinnedActionsView:View {
    let suite:SuiteAPI;let actions:[SuiteAction]
    @AppStorage private var stored:String
    init(suite:SuiteAPI,actions:[SuiteAction]){self.suite=suite;self.actions=actions;_stored=AppStorage(wrappedValue:"", "suite.shortcuts."+suite.connection.serverIdentity)}
    var body:some View {ForEach(actions.filter{stored.split(separator:"|").contains(Substring($0.id))}){action in NavigationLink(action.title){SuiteActionView(suite:suite,action:action)}}}
}
struct SuiteDependencyView:View {
    let suite:SuiteAPI
    @State private var rows:[JSON]=[]
    @State private var states:[String:String]=[:]
    @State private var error:String?
    var body:some View {List {Section{Text("Verfügbare Release-Versionen werden direkt bei Maven Central geprüft. Eine neuere Version ist kein Beleg für Kompatibilität; Bibliotheken werden nicht automatisch ersetzt.").font(.footnote).foregroundStyle(.secondary)};ForEach(rows,id:\.["id"].string){row in VStack(alignment:.leading){Text(row["name"].string);Text(states[row["id"].string] ?? "Installiert: "+row["version"].string).font(.caption).foregroundStyle(.secondary)}}}.panelScreen().navigationTitle("Bibliotheken").task{await load()}.refreshable{await load()}.errorAlert($error)}
    func load() async {do{rows=try await suite.data("management",query:["section":"dependencies"])["items"].array;for row in rows{do{guard let url=URL(string:row["metadata"].string),url.scheme=="https",url.host=="repo.maven.apache.org" else{continue};var request=URLRequest(url:url);request.timeoutInterval=8;let (data,response)=try await URLSession.shared.data(for:request);guard (response as? HTTPURLResponse)?.statusCode==200,data.count<1024*1024,let text=String(data:data,encoding:.utf8),let range=text.range(of:"<release>"),let end=text.range(of:"</release>",range:range.upperBound..<text.endIndex) else{throw PanelError(message:"Metadaten fehlen")};let latest=String(text[range.upperBound..<end.lowerBound]);states[row["id"].string]="Stand: \(row["version"].string) · Release: \(latest)"}catch{states[row["id"].string]="Versionsprüfung nicht verfügbar"}}}catch{self.error=error.localizedDescription}}
}
