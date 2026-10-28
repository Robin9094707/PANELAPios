import SwiftUI

struct SuiteLiveChatView: View {
    let suite: SuiteAPI
    @Environment(\.scenePhase) private var scenePhase
    @State private var rows: [JSON] = []
    @State private var cursor = "0"
    @State private var name = "Server"
    @State private var message = ""
    @State private var error: String?
    @State private var sending = false
    @State private var initialized = false
    var body: some View {
        List {
            Section { Text("Öffentlicher Serverchat · letzte 20 Nachrichten. Der Verlauf wird beim Verlassen der App verworfen. Private Nachrichten erscheinen hier nicht.").font(.caption).foregroundStyle(.secondary) }
            Section {
                if rows.isEmpty { Text("Warte auf neue Chatnachrichten …").foregroundStyle(.secondary) }
                ForEach(rows,id:\.["id"].string) { row in HStack(alignment:.top,spacing:10) {
                    avatar(row)
                    VStack(alignment:.leading,spacing:4) { HStack { Text(row["name"].string).font(.subheadline.bold()); if row["source"].string == "app" { Text("App").font(.caption2).foregroundStyle(.secondary) };Spacer(); if let date=SuitePlayerPresentation.date(row["at"]) { Text(date,style:.time).font(.caption2).foregroundStyle(.secondary) } };Text(row["text"].string).textSelection(.enabled) }
                } }
            }
            Section("Nachricht senden") { TextField("Anzeigename",text:$name).textInputAutocapitalization(.never).autocorrectionDisabled();TextField("Deine Nachricht",text:$message,axis:.vertical).lineLimit(2...5);Button("Senden",systemImage:"paperplane.fill") { Task { await send() } }.disabled(sending || message.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty || name.isEmpty || suite.scope != "full");if suite.scope != "full" { Text("Zum Senden wird ein full-App-Zugang benötigt.").font(.caption).foregroundStyle(.secondary) } }
        }.panelScreen().navigationTitle("Serverchat").errorAlert($error)
            .toolbar { Button("Verlauf leeren",systemImage:"trash") { rows=[] } }
            .task(id:scenePhase) {
                guard scenePhase == .active else{return}
                do {
                    // Each foreground session begins at the current server cursor.
                    if !initialized { let baseline=try await suite.data("chat");cursor=baseline["cursor"].string;rows=[];initialized=true }
                    while !Task.isCancelled { try await Task.sleep(for:.seconds(2));await poll() }
                } catch is CancellationError {} catch { self.error=error.localizedDescription }
            }
            .onChange(of:scenePhase) { _, phase in if phase != .active { rows=[];initialized=false } }
            .onDisappear { rows=[];initialized=false }
    }
    @ViewBuilder func avatar(_ row:JSON) -> some View {
        if let uuid=UUID(uuidString:row["uuid"].string),let url=URL(string:"https://mc-heads.net/avatar/"+uuid.uuidString+"/32") { AsyncImage(url:url) { image in image.resizable().interpolation(.none) } placeholder: { Image(systemName:"person.crop.square.fill").resizable() }.frame(width:32,height:32).clipShape(RoundedRectangle(cornerRadius:6)) }
        else { Image(systemName:"server.rack").frame(width:32,height:32).foregroundStyle(.secondary) }
    }
    func poll() async { do { let data=try await suite.data("chat",query:["after":cursor]);try Task.checkCancellation();let items=data["items"].array;let ids=Set(rows.map{$0["id"].string});rows=Array((rows+items.filter{!ids.contains($0["id"].string)}).suffix(20));cursor=data["cursor"].string } catch is CancellationError {} catch { self.error=error.localizedDescription } }
    func send() async { sending=true;defer{sending=false};do { _=try await suite.request("chat",method:"POST",body:.object(["name":.string(name),"message":.string(message)]));message="";await poll() } catch { self.error=error.localizedDescription } }
}
struct SuiteRestartView: View {
    let suite: SuiteAPI
    @StateObject private var model: SuiteSecurityModel
    @State private var status: JSON = .null
    @State private var seconds = 30
    @State private var reasonDE = ""
    @State private var reasonEN = ""
    init(suite:SuiteAPI){self.suite=suite;_model=StateObject(wrappedValue:SuiteSecurityModel(suite))}
    var body: some View {
        List {
            Section("Neustartstatus") { if status["active"].bool { Label("Neustart in \(status["remaining"].int) Sekunden",systemImage:"timer");Text(status["reasonDE"].string).font(.caption);Button("Neustart abbrechen",role:.destructive){prepare(cancel:true)}.disabled(suite.scope != "full" || model.busy) } else { Label("Kein Neustart geplant",systemImage:"checkmark.circle") } }
            Section("Countdown & Ankündigung") { Stepper("\(seconds) Sekunden",value:$seconds,in:1...60);TextField("Grund auf Deutsch (optional)",text:$reasonDE,axis:.vertical);TextField("Reason in English (optional)",text:$reasonEN,axis:.vertical);Text("Jeder Spieler erhält Countdown, Titel und Sounds passend zu seiner Sprache. Ohne Übersetzung wird der vorhandene Grund verwendet.").font(.caption).foregroundStyle(.secondary);Button("Neustart planen",systemImage:"arrow.clockwise.circle.fill"){prepare(cancel:false)}.disabled(status["active"].bool || suite.scope != "full" || model.busy || reasonDE.count>200 || reasonEN.count>200) }
            Section { Text("Der Server verwendet den bestehenden Paper-Neustart. Das Hosting muss den Server danach wieder starten können.").font(.caption).foregroundStyle(.secondary) }
        }.panelScreen().navigationTitle("Serverneustart").errorAlert($model.error)
            .task { while !Task.isCancelled { await load();do {try await Task.sleep(for:.seconds(2))}catch{break} } }
            .sheet(isPresented:Binding(get:{model.plan != nil},set:{if !$0{model.plan=nil}})){SuiteSecurityConfirmation(model:model){await load()}}
    }
    func prepare(cancel:Bool){Task{await model.prepare("restart.apply",["operation":.string(cancel ? "cancel":"schedule"),"seconds":.number(Double(seconds)),"reasonDE":.string(reasonDE),"reasonEN":.string(reasonEN)],summary:cancel ? "Geplanten Serverneustart abbrechen?":"Server in \(seconds) Sekunden neu starten?\nDE: \(reasonDE)\nEN: \(reasonEN)")}}
    func load()async{do{status=try await suite.data("operations")}catch{model.error=error.localizedDescription}}
}
