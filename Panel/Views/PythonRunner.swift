import SwiftUI

extension Server {
    var isPythonRunner: Bool { (name + " " + data["amp"].pretty).localizedCaseInsensitiveContains("python") }
}
struct AMPApplicationsView: View {
    let api: PanelAPI
    @EnvironmentObject private var store: AppStore
    var body: some View {
        List {
            Section { Text("Python-App-Runner und andere Anwendungen direkt verwalten: Dateien, Startparameter, Umgebung und alle vom AMP-Modul angebotenen Einstellungen.").font(.footnote).foregroundStyle(.secondary) }
            ForEach(store.servers.filter { !$0.data["invocation"].string.lowercased().contains("minecraft") }) { server in
                NavigationLink { PythonRunnerView(api: api, server: server) } label: { Label(server.name, systemImage: server.isPythonRunner ? "terminal.fill" : "server.rack") }
            }
        }.panelScreen().navigationTitle("AMP-Anwendungen").refreshable { await store.refresh() }
    }
}
struct PythonRunnerView: View {
    let api: PanelAPI
    let server: Server
    var body: some View {
        List {
            Section(server.name) {
                NavigationLink { ServerView(api: api, server: server) } label: { Label("Live-Status, Konsole & Steuerung", systemImage: "terminal") }
                NavigationLink { FilesView(api: api, server: server, root: "/") } label: { Label("Dateien & Python-Skripte", systemImage: "folder") }
                NavigationLink { AMPSettingsView(api: api, server: server, title: "App-Runner-Einstellungen") } label: { Label("Startdatei, Python & alle Einstellungen", systemImage: "slider.horizontal.3") }
                NavigationLink { AMPManageView(api: api, server: server) } label: { Label("Instanzdienst, Aufgaben & Updates", systemImage: "server.rack") }
                NavigationLink { AMPBackupsView(api: api, server: server) } label: { Label("Backups", systemImage: "externaldrive") }
            }
            Section { Text("Bearbeite Skripte und Konfigurationen direkt im Datei-Editor. Python-Version, Startargumente, Arbeitsverzeichnis und Umgebungsvariablen werden angezeigt, soweit dein AMP-Modul diese Einstellungen bereitstellt. Änderungen können einen Neustart erfordern.").font(.footnote).foregroundStyle(.secondary) }
        }.panelScreen().navigationTitle(server.isPythonRunner ? "Python App Runner" : "AMP-Anwendung")
    }
}
