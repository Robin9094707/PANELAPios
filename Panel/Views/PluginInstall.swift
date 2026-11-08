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
 @State private var pendingName: String?
 @ObservedObject private var transfers = TransferManager.shared
 private var progress: TransferRecord? {
     transfers.records.first { $0.active && $0.upload && $0.filename == pendingName }
 }
 var body:some View {List {
  Section {Text("Neue Plugin-JAR installieren").font(.title2.bold());Text("Die App prüft die Plugin-Metadaten vor dem Upload. Der Server muss gestoppt sein; Paper lädt das Plugin beim nächsten Start. Serverversion und Abhängigkeiten zusätzlich prüfen.").font(.footnote).foregroundStyle(.secondary);Button("JAR auswählen",systemImage:"doc.badge.plus"){importer=true}.disabled(busy)}
  if let descriptor {Section("Dateiprüfung erfolgreich"){LabeledContent("Plugin",value:descriptor.name);LabeledContent("Version",value:descriptor.version);LabeledContent("Hauptklasse",value:descriptor.main);LabeledContent("API",value:descriptor.apiVersion);Text(descriptor.summary);Button("Installation bestätigen"){confirm=true}.disabled(busy || success)}}
  if busy {
      if let progress, let fraction = progress.fraction {
          ProgressView(value: fraction) { Text(progress.title) }
      } else { ProgressView(pendingName == nil ? "JAR prüfen …" : "Upload oder Verifizierung läuft …") }
  }
  if success{Label("Plugin für nächsten Serverstart installiert",systemImage:"checkmark.shield.fill").foregroundStyle(.green)}
 }.panelScreen().navigationTitle("Plugin hinzufügen").errorAlert($error)
 .sheet(isPresented:$importer){FileImportPicker(retainSelection:true){result in importer=false;switch result{case .success(let urls):if let url=urls.first{inspect(url)};case .failure(let failure):error=failure.localizedDescription}}.ignoresSafeArea()}
 .confirmationDialog("Geprüfte JAR installieren?",isPresented:$confirm,titleVisibility:.visible){Button("Installieren"){Task{await install()}}}message:{Text("Es wird eine neue Datei mit eindeutiger Kennung angelegt. Vorhandene JARs werden nicht überschrieben.")}
 }
 func inspect(_ url:URL){busy=true;Task{defer{busy=false};do{let pair=try await Task.detached{let access=url.startAccessingSecurityScopedResource();defer{if access{url.stopAccessingSecurityScopedResource()}};let info=try url.resourceValues(forKeys:[.fileSizeKey]);guard (info.fileSize ?? 0)<=128*1024*1024 else{throw PanelError(message:"JAR größer als 128 MiB")};let bytes=try Data(contentsOf:url);return try PluginArchive.descriptor(bytes)}.value;file=url;descriptor=pair;success=false}catch{self.error=error.localizedDescription}}}
 func install() async {
     guard !busy, let file, let descriptor else { return }
     busy = true
     defer { busy = false; pendingName = nil }
     do {
         guard !api.demo else { throw PanelError(message: "Demomodus") }
         let prefix = "servers/\(server.id)/files"
         func requireStopped() async throws {
             guard try await api.request("servers/\(server.id)/resources").attributes["current_state"].string == "offline" else {
                 throw PanelError(message: "Stoppe den Server vor der Installation. Eine vorbereitete Datei bleibt im .pending-Format.")
             }
         }
         try await requireStopped()
         let access = file.startAccessingSecurityScopedResource()
         defer { if access { file.stopAccessingSecurityScopedResource() } }
         let (bytes, digest) = try await Task.detached(priority: .userInitiated) {
             let bytes = try Data(contentsOf: file, options: .mappedIfSafe)
             guard try PluginArchive.descriptor(bytes) == descriptor else { throw PanelError(message: "Die ausgewählte JAR wurde geändert. Bitte erneut auswählen.") }
             return (bytes, PluginArchive.digest(bytes))
         }.value
         let name = String(descriptor.versionedFilename.dropLast(4)) + "-" + String(digest.prefix(16)) + ".jar"
         let pending = name + ".pending"
         pendingName = pending
         func exists(_ filename: String) async throws -> Bool {
             try await api.list(prefix + "/list", query: ["directory": "/plugins"]).contains { $0["name"].string == filename }
         }
         func verify(_ filename: String) async throws {
             let remote = try await api.fileData(server: server.id, path: "/plugins/" + filename, limit: 128 * 1024 * 1024)
             let hash = await Task.detached(priority: .userInitiated) { PluginArchive.digest(remote) }.value
             guard hash == digest else { throw PanelError(message: "Die Prüfsumme der Serverdatei stimmt nicht überein.") }
         }
         if try await exists(name) { try await verify(name); success = true; return }
         if !(try await exists(pending)) {
             let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
             try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
             defer { try? FileManager.default.removeItem(at: folder) }
             let local = folder.appendingPathComponent(pending)
             try await Task.detached(priority: .userInitiated) { try bytes.write(to: local) }.value
             do { try await api.upload(server: server.id, directory: "/plugins", file: local) }
             catch {
                 if RequestFailure.isCancellation(error) { throw error }
                 guard try await exists(pending) else { throw error }
             }
         }
         try await verify(pending)
         try await requireStopped()
         do { try await api.mutate(prefix + "/rename", method: "PUT", ["root": "/plugins", "files": [["from": pending, "to": name]]]) }
         catch {
             if RequestFailure.isCancellation(error) { throw error }
             guard try await exists(name) else { throw error }
             try await verify(name)
         }
         success = true
     } catch { self.error = error.localizedDescription }
 }
}
