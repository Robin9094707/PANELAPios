import SwiftUI
struct PluginInstallView:View {
 let api:PanelAPI;let server:Server
 @State private var importer=false
 @State private var file:URL?
 @State private var descriptor:PluginDescriptor?
 @State private var error:String?
 @State private var busy=false
 @State private var confirm=false
 @State private var success=false
 var body:some View {List {
  Section {Text("Neue Plugin-JAR installieren").font(.title2.bold());Text("Die App prüft die Plugin-Metadaten vor dem Upload. Der Server muss gestoppt sein; Paper lädt das Plugin beim nächsten Start. Serverversion und Abhängigkeiten zusätzlich prüfen.").font(.footnote).foregroundStyle(.secondary);Button("JAR auswählen",systemImage:"doc.badge.plus"){importer=true}.disabled(busy)}
  if let descriptor {Section("Dateiprüfung erfolgreich"){LabeledContent("Plugin",value:descriptor.name);LabeledContent("Version",value:descriptor.version);LabeledContent("Hauptklasse",value:descriptor.main);LabeledContent("API",value:descriptor.apiVersion);Text(descriptor.summary);Button("Installation bestätigen"){confirm=true}.disabled(busy || success)}}
  if busy{ProgressView("JAR übertragen und verifizieren …")};if success{Label("Plugin für nächsten Serverstart installiert",systemImage:"checkmark.shield.fill").foregroundStyle(.green)}
 }.panelScreen().navigationTitle("Plugin hinzufügen").errorAlert($error)
 .sheet(isPresented:$importer){FileImportPicker(retainSelection:true){result in importer=false;switch result{case .success(let urls):if let url=urls.first{inspect(url)};case .failure(let failure):error=failure.localizedDescription}}.ignoresSafeArea()}
 .confirmationDialog("Geprüfte JAR installieren?",isPresented:$confirm,titleVisibility:.visible){Button("Installieren"){Task{await install()}}}message:{Text("Es wird eine neue Datei mit eindeutiger Kennung angelegt. Vorhandene JARs werden nicht überschrieben.")}
 }
 func inspect(_ url:URL){busy=true;Task{defer{busy=false};do{let pair=try await Task.detached{let access=url.startAccessingSecurityScopedResource();defer{if access{url.stopAccessingSecurityScopedResource()}};let info=try url.resourceValues(forKeys:[.fileSizeKey]);guard (info.fileSize ?? 0)<=128*1024*1024 else{throw PanelError(message:"JAR größer als 128 MiB")};let bytes=try Data(contentsOf:url);return try PluginArchive.descriptor(bytes)}.value;file=url;descriptor=pair;success=false}catch{self.error=error.localizedDescription}}}
 func install() async {guard let file,let descriptor else{return};busy=true;defer{busy=false};do{
  guard !api.demo else{throw PanelError(message:"Demomodus")};let prefix="servers/\(server.id)/files"
  guard try await api.request("servers/\(server.id)/resources").attributes["current_state"].string=="offline" else{throw PanelError(message:"Server zuerst stoppen")}
  let access=file.startAccessingSecurityScopedResource();defer{if access{file.stopAccessingSecurityScopedResource()}}
  let bytes=try Data(contentsOf:file);_ = try PluginArchive.descriptor(bytes)
  let folder=FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString);try FileManager.default.createDirectory(at:folder,withIntermediateDirectories:true);defer{try? FileManager.default.removeItem(at:folder)}
  let name=descriptor.versionedFilename.replacingOccurrences(of:".jar",with:"")+"-"+UUID().uuidString+".jar";let pending=name+".pending";let local=folder.appendingPathComponent(pending);try bytes.write(to:local)
  try await api.upload(server:server.id,directory:"/plugins",file:local)
  guard PluginArchive.digest(try await api.fileData(server:server.id,path:"/plugins/"+pending,limit:128*1024*1024))==PluginArchive.digest(bytes) else{throw PanelError(message:"Upload-Prüfsumme stimmt nicht überein; .pending-Datei wird nicht geladen")}
  guard try await api.request("servers/\(server.id)/resources").attributes["current_state"].string=="offline" else{throw PanelError(message:"Server wurde gestartet. Datei bleibt .pending und wird nicht geladen.")}
  try await api.mutate(prefix+"/rename",method:"PUT",["root":"/plugins","files":[["from":pending,"to":name]]]);success=true
 }catch{self.error=error.localizedDescription}}
}
