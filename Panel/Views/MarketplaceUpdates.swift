import SwiftUI
import WebKit

struct AddonUpdateCenter: View {
    let api: PanelAPI
    let server: Server
    @ObservedObject var model: AddonUpdateModel
    let importFile: (String, URL) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var entries: [JSON] = []
    @State private var settings = false
    @State private var mapping: AddonInventory?
    @State private var release: AddonReleaseSelection?
    @State private var browser: AddonBrowserSelection?
    @State private var received: (String, URL)?
    @State private var working = false
    @State private var error: String?
    var body: some View {
        List {
            Section {
                Label(model.kind == .plugin ? "Plugin-Updates" : "Mod-Updates", systemImage: "arrow.triangle.2.circlepath").font(.title2.bold()).foregroundStyle(Color.panelAccent)
                Button { settings = true } label: { LabeledContent("Serverprofil", value: model.cache.target.valid ? "\(model.cache.target.loader.capitalized) · \(model.cache.target.game)" : "Version festlegen") }
                Text("Installierte JARs werden per Prüfsumme erkannt. Unbekannte Projekte einmalig zuordnen. Nur passende Versionen werden angeboten; Abhängigkeiten vor dem Installieren prüfen.").font(.footnote).foregroundStyle(.secondary)
                if model.busy { ProgressView(model.progress).font(.caption) }
                Button("Jetzt auf Updates prüfen", systemImage: "arrow.clockwise") { Task { await load(force: true) } }.disabled(model.busy || working)
                if working { ProgressView("Download prüfen …") }
            }
            ForEach(model.rows) { item in
                Section {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(item.name).font(.headline)
                        Text("\(item.version) · \(item.filename)").font(.caption).foregroundStyle(.secondary)
                        let status = model.cache.statuses[item.filename]
                        Label(status?.text ?? "Noch nicht geprüft", systemImage: status?.release == nil ? "info.circle" : "arrow.down.circle.fill").font(.subheadline).foregroundStyle(status?.release == nil ? Color.secondary : Color.panelAccent)
                        if let next = status?.release {
                            Button("\(next.provider.title) · \(next.version) prüfen") { release = AddonReleaseSelection(item: item, release: next) }.buttonStyle(.glass).disabled(working)
                        }
                        Button(model.cache.links[item.filename].map { "Quelle: \($0.provider.title) · \($0.project)" } ?? "Quelle zuordnen", systemImage: "link") { mapping = item }.font(.caption)
                    }.padding(.vertical, 4)
                }
            }
            if !model.busy && model.rows.isEmpty { ContentUnavailableView("Noch keine lesbaren JARs", systemImage: "shippingbox", description: Text("Serverdateien lesen und bei Bedarf das Serverprofil festlegen. Deaktivierte JARs werden nicht aktualisiert.")) }
            let failures = model.cache.statuses.keys.filter { model.cache.inventory[$0] == nil }.sorted()
            if !failures.isEmpty { Section("Nicht lesbar") { ForEach(failures, id: \.self) { name in VStack(alignment: .leading) { Text(name); Text(model.cache.statuses[name]?.text ?? "").font(.caption).foregroundStyle(.orange) } } } }
        }.panelScreen().navigationTitle("Updates").navigationBarTitleDisplayMode(.inline).errorAlert($error)
            .task { await load() }.refreshable { await load(force: true) }
            .sheet(isPresented: $settings, onDismiss: { Task { await load() } }) { AddonProviderSettings(api: api, server: server, model: model) }
            .sheet(item: $mapping, onDismiss: { Task { await load() } }) { item in AddonMappingView(model: model, item: item) }
            .sheet(item: $release, onDismiss: deliverReceived) { selection in releaseDetails(selection) }
            .sheet(item: $browser, onDismiss: deliverReceived) { selection in
                AddonDownloadBrowser(url: selection.url) { result in
                    switch result {
                    case .success(let url):
                        do {
                            let data = try Data(contentsOf: url, options: .mappedIfSafe)
                            // Web login may deliver a different version; JAR identity is checked in the existing import flow.
                            guard data.count <= 128 * 1024 * 1024, data.prefix(2) == Data([0x50,0x4b]) else { throw PanelError(message: "Die Webseite hat keine gültige JAR geliefert.") }
                            received = (selection.filename, url)
                        } catch { self.error = error.localizedDescription; try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
                    case .failure(let failure): error = failure.localizedDescription
                    }
                    browser = nil
                }
            }
    }
    func deliverReceived() {
        guard let received else { return }; self.received = nil
        dismiss(); importFile(received.0, received.1)
    }
    func load(force: Bool = false) async {
        do { entries = try await api.list("servers/\(server.id)/files/list", query: ["directory": model.kind.directory]); await model.scan(api: api, server: server, entries: entries, force: force) }
        catch is CancellationError {} catch { self.error = error.localizedDescription }
    }
    func releaseDetails(_ selection: AddonReleaseSelection) -> some View {
        NavigationStack {
            List {
                Section(selection.item.name) {
                    LabeledContent("Installiert", value: selection.item.version)
                    LabeledContent("Angeboten", value: selection.release.version)
                    LabeledContent("Quelle", value: selection.release.provider.title)
                    Label(selection.release.compatibility ? "Minecraft-Version und Loader vom Anbieter bestätigt" : "Kompatibilität für die konkrete Datei manuell prüfen", systemImage: selection.release.compatibility ? "checkmark.shield" : "exclamationmark.triangle").foregroundStyle(selection.release.compatibility ? Color.green : .orange)
                }
                if !selection.release.dependencies.isEmpty { Section("Abhängigkeiten – werden nicht automatisch installiert") { ForEach(selection.release.dependencies, id: \.self) { Text($0) } } }
                Section("Versionshinweise") { Text(selection.release.notes.isEmpty ? "Keine Hinweise verfügbar." : selection.release.notes).font(.footnote).textSelection(.enabled) }
                Section {
                    if selection.release.download != nil {
                        Button("Download laden und JAR prüfen", systemImage: "arrow.down.circle") { fetch(selection) }.disabled(working)
                    }
                    Button("Anbieterseite / gekaufter Download", systemImage: "globe") {
                        guard let url = URL(string: selection.release.website), url.scheme == "https" else { error = "Keine sichere Anbieterseite verfügbar."; return }
                        release = nil
                        // Present after the release sheet's dismissal animation.
                        Task { try? await Task.sleep(for: .milliseconds(400)); browser = AddonBrowserSelection(url: url, filename: selection.item.filename) }
                    }
                    Text("Der Download wird zuerst geprüft und danach im vorhandenen Installationsdialog bestätigt. Plugins werden für den Neustart vorgemerkt. Mods benötigen einen gestoppten Server und behalten eine Sicherung der bisherigen JAR.").font(.caption).foregroundStyle(.secondary)
                }
            }.navigationTitle("Update prüfen").toolbar { ToolbarItem(placement: .confirmationAction) { Button("Fertig") { release = nil } } }
        }
    }
    func fetch(_ selection: AddonReleaseSelection) {
        working = true
        Task {
            defer { working = false }
            do {
                let file = try await model.client.download(selection.release)
                received = (selection.item.filename, file)
                release = nil
            } catch { release = nil; self.error = error.localizedDescription }
        }
    }
}
struct AddonReleaseSelection: Identifiable { let id = UUID(); let item: AddonInventory; let release: AddonRelease }
struct AddonBrowserSelection: Identifiable { let id = UUID(); let url: URL; let filename: String }

struct AddonMappingView: View {
    @ObservedObject var model: AddonUpdateModel
    let item: AddonInventory
    @Environment(\.dismiss) private var dismiss
    @State private var provider = AddonProvider.modrinth
    @State private var query = ""
    @State private var project = ""
    @State private var results: [AddonSearchResult] = []
    @State private var busy = false
    @State private var error: String?
    var body: some View {
        NavigationStack {
            Form {
                Section(item.name) {
                    Picker("Anbieter", selection: $provider) { ForEach(AddonProvider.allCases) { p in if p != .spigot || model.kind == .plugin { Text(p.title).tag(p) } } }
                    TextField("Plugin-/Mod-Name", text: $query)
                    Button("Projekte suchen") { search() }.disabled(busy)
                    if busy { ProgressView() }
                }
                Section("Projekt eindeutig auswählen") {
                    ForEach(results) { result in
                        Button { project = result.id } label: { HStack { VStack(alignment: .leading) { Text(result.name); Text(result.website).font(.caption).foregroundStyle(.secondary) }; Spacer(); if project == result.id { Image(systemName: "checkmark") } } }
                    }
                    TextField("Projekt-ID / Modrinth-Slug", text: $project).textInputAutocapitalization(.never).autocorrectionDisabled()
                    Text("Eine Namenssuche ist kein Identitätsnachweis. Wähle das Originalprojekt. Vor einem Upload werden die tatsächlichen JAR-Metadaten verglichen.").font(.caption).foregroundStyle(.secondary)
                }
                Button("Zuordnung speichern") { do { try model.bind(item, provider: provider, project: project); dismiss() } catch { self.error = error.localizedDescription } }.disabled(project.isEmpty)
                Button("Zuordnung entfernen", role: .destructive) { model.cache.links[item.filename] = nil; model.cache.statuses[item.filename] = nil; model.save(); dismiss() }
            }.navigationTitle("Quelle zuordnen").toolbar { ToolbarItem(placement: .cancellationAction) { Button("Schließen") { dismiss() } } }.errorAlert($error)
                .onAppear { query = item.name; if let link = model.cache.links[item.filename] { provider = link.provider; project = link.project } }
                .onChange(of: provider) { _, _ in results = []; project = "" }
        }
    }
    func search() {
        busy = true; let selected = provider
        Task { defer { busy = false }; do { let found = try await model.client.search(query, provider: selected, kind: model.kind); if selected == provider { results = found } } catch { self.error = error.localizedDescription } }
    }
}

struct AddonProviderSettings: View {
    let api: PanelAPI
    let server: Server
    @ObservedObject var model: AddonUpdateModel
    @Environment(\.dismiss) private var dismiss
    @State private var target = AddonTarget()
    @State private var key = ""
    @State private var notice: String?
    @State private var error: String?
    @State private var detecting = false
    @State private var login = false
    @State private var logout = false
    var body: some View {
        NavigationStack {
            Form {
                Section("Minecraft-Server") {
                    TextField("Minecraft-Version, z. B. 26.3", text: $target.game).textInputAutocapitalization(.never).autocorrectionDisabled()
                    Picker("Serversoftware / Loader", selection: $target.loader) { ForEach(AddonTarget.loaders, id: \.self) { Text($0.capitalized).tag($0) } }
                    Toggle("Beta- und Alpha-Versionen anbieten", isOn: $target.beta)
                    Button("Serverversion erkennen") { detect() }.disabled(detecting)
                    if detecting { ProgressView() }
                    if let notice { Text(notice).font(.caption).foregroundStyle(.secondary) }
                    Text("Verwende die Minecraft-Version (26.3), nicht die Paper-Buildnummer. Das Profil gilt nur für diesen Server und diese Plugin-/Mod-Liste.").font(.caption).foregroundStyle(.secondary)
                }
                Section("Spigot · nur auf diesem iPhone") {
                    Button("Bei Spigot anmelden", systemImage: "person.crop.circle") { login = true }
                    Button("Lokale Spigot-Sitzung löschen", role: .destructive) { logout = true }
                    Text("Anmeldung und CAPTCHA erfolgen direkt auf Spigot. Die App liest dein Passwort nicht aus. Gekaufte Downloads werden aus dieser Sitzung übernommen und anschließend als JAR geprüft.").font(.caption).foregroundStyle(.secondary)
                }
                Section("CurseForge") {
                    SecureField("Eigener CurseForge-API-Schlüssel", text: $key).textInputAutocapitalization(.never).autocorrectionDisabled()
                    Link("API-Schlüssel bei CurseForge beantragen", destination: URL(string: "https://console.curseforge.com/")!)
                    Text("Der Schlüssel wird im iPhone-Schlüsselbund gespeichert und nur an api.curseforge.com gesendet. Vom Autor gesperrte Direktdownloads öffnen die Anbieterseite.").font(.caption).foregroundStyle(.secondary)
                }
                Button("Speichern") {
                    guard target.valid else { error = "Eine vollständige Minecraft-Version eingeben, zum Beispiel 26.3 oder 1.21.8."; return }
                    do { if key.isEmpty { Vault.delete(MarketplaceClient.curseKeyID) } else { try Vault.save(key, id: MarketplaceClient.curseKeyID) }; model.setTarget(target); dismiss() } catch { self.error = error.localizedDescription }
                }
            }.navigationTitle("Anbieter & Serverprofil").toolbar { ToolbarItem(placement: .cancellationAction) { Button("Schließen") { dismiss() } } }.errorAlert($error)
                .onAppear { target = model.cache.target; key = (try? Vault.read(MarketplaceClient.curseKeyID)) ?? "" }
                .sheet(isPresented: $login) { AddonDownloadBrowser(url: URL(string: "https://www.spigotmc.org/login/")!) { result in if case .success(let url) = result { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }; login = false } }
                .confirmationDialog("Spigot auf diesem iPhone abmelden?", isPresented: $logout, titleVisibility: .visible) {
                    Button("Sitzung löschen", role: .destructive) { let store = WKWebsiteDataStore.default(); store.fetchDataRecords(ofTypes: WKWebsiteDataStore.allWebsiteDataTypes()) { records in let selected = records.filter { $0.displayName.lowercased().contains("spigotmc.org") }; store.removeData(ofTypes: WKWebsiteDataStore.allWebsiteDataTypes(), for: selected) { } } }
                }
        }
    }
    func detect() {
        detecting = true
        Task { defer { detecting = false }
            let variables = (try? await api.list("servers/\(server.id)/startup")) ?? []
            for row in variables where ["MINECRAFT_VERSION", "MC_VERSION"].contains(row["env_variable"].string) {
                let value = row["server_value"].string
                var next = target; next.game = value
                if next.valid { target.game = value; notice = "Aus den Startparametern übernommen. Bitte mit deiner laufenden Version vergleichen."; return }
            }
            if let data = try? await api.fileData(server: server.id, path: "/version_history.json", limit: 512 * 1024), let json = try? JSONDecoder().decode(JSON.self, from: data) {
                let raw = json["currentVersion"].string
                if let value = AddonTarget.detect(raw) { target.game = value; notice = "Aus Papers Versionshistorie erkannt."; return }
            }
            notice = "Keine eindeutige Version gefunden. Bitte manuell eintragen."
        }
    }
}

/// WebKit owns the login cookie store. No scraping, password capture or CAPTCHA bypass.
struct AddonDownloadBrowser: View {
    let url: URL
    let result: (Result<URL, Error>) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var issue: String?
    @State private var host = ""
    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Label(host.isEmpty ? (url.host ?? "Anbieter") : host, systemImage: "lock.shield").font(.caption).padding(8)
                if let issue { Text(issue).font(.caption).foregroundStyle(.orange).padding(8) }
                Text("Anmelden, ggf. CAPTCHA selbst lösen und Download wählen. Für Web-Downloads die Vorschau geöffnet lassen. Falls die Seite die Vorschau ablehnt: in Safari laden und die JAR über „Update-Datei hochladen“ auswählen.").font(.caption).foregroundStyle(.secondary).padding(.horizontal).padding(.bottom, 8)
                MarketplaceWebView(url: url, status: { host = $0 }, failure: { issue = $0 }, result: result)
            }.navigationTitle("Anbieter-Download").navigationBarTitleDisplayMode(.inline).toolbar { ToolbarItem(placement: .bottomBar) { Link("In Safari öffnen", destination: url) }; ToolbarItem(placement: .confirmationAction) { Button("Schließen") { dismiss() } } }
        }
    }
}
struct MarketplaceWebView: UIViewRepresentable {
    let url: URL
    let status: (String) -> Void
    let failure: (String) -> Void
    let result: (Result<URL, Error>) -> Void
    func makeCoordinator() -> Coordinator { Coordinator(self) }
    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration(); config.websiteDataStore = .default()
        let web = WKWebView(frame: .zero, configuration: config); web.navigationDelegate = context.coordinator; web.uiDelegate = context.coordinator; web.allowsBackForwardNavigationGestures = true
        web.load(URLRequest(url: url)); return web
    }
    func updateUIView(_ view: WKWebView, context: Context) { }
    static func dismantleUIView(_ view: WKWebView, coordinator: Coordinator) { view.stopLoading(); coordinator.cancel() }
    @MainActor final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate, WKDownloadDelegate {
        let parent: MarketplaceWebView
        var files: [ObjectIdentifier: URL] = [:]
        var downloads: [ObjectIdentifier: WKDownload] = [:]
        init(_ parent: MarketplaceWebView) { self.parent = parent }
        func cancel() { for download in downloads.values { download.cancel { _ in } }; for file in files.values { try? FileManager.default.removeItem(at: file.deletingLastPathComponent()) }; downloads = [:]; files = [:] }
        func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            guard let url = action.request.url, url.scheme == "https" || url.absoluteString == "about:blank" else { decisionHandler(.cancel); return }
            if action.targetFrame?.isMainFrame != false { parent.status(url.host ?? "Anbieter") }
            decisionHandler(action.shouldPerformDownload ? .download : .allow)
        }
        func webView(_ webView: WKWebView, decidePolicyFor response: WKNavigationResponse, decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void) {
            let filename = response.response.suggestedFilename ?? ""
            decisionHandler(filename.lowercased().hasSuffix(".jar") || (!response.canShowMIMEType && response.isForMainFrame) ? .download : .allow)
        }
        func webView(_ webView: WKWebView, navigationAction: WKNavigationAction, didBecome download: WKDownload) { attach(download) }
        func webView(_ webView: WKWebView, navigationResponse: WKNavigationResponse, didBecome download: WKDownload) { attach(download) }
        func attach(_ download: WKDownload) { download.delegate = self; downloads[ObjectIdentifier(download)] = download }
        func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for navigationAction: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? { if navigationAction.targetFrame == nil, navigationAction.request.url?.scheme == "https" { webView.load(navigationAction.request) }; return nil }
        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) { if (error as NSError).code != NSURLErrorCancelled { parent.failure(error.localizedDescription) } }
        func download(_ download: WKDownload, decideDestinationUsing response: URLResponse, suggestedFilename: String, completionHandler: @escaping (URL?) -> Void) {
            let name = URL(fileURLWithPath: suggestedFilename).lastPathComponent
            guard response.url?.scheme == "https", name.lowercased().hasSuffix(".jar"), response.expectedContentLength <= 128 * 1024 * 1024 else { completionHandler(nil); parent.failure("Bitte eine einzelne Plugin-/Mod-JAR bis 128 MiB laden."); return }
            do { let directory = FileManager.default.temporaryDirectory.appendingPathComponent("market-" + UUID().uuidString); try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true); let destination = directory.appendingPathComponent(name); files[ObjectIdentifier(download)] = destination; completionHandler(destination) } catch { completionHandler(nil); parent.failure(error.localizedDescription) }
        }
        func download(_ download: WKDownload, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, decisionHandler: @escaping (WKDownload.RedirectPolicy) -> Void) { decisionHandler(request.url?.scheme == "https" ? .allow : .cancel) }
        func downloadDidFinish(_ download: WKDownload) {
            let id = ObjectIdentifier(download); downloads[id] = nil
            guard let file = files.removeValue(forKey: id) else { return }
            do { let size = try file.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0; guard size <= 128 * 1024 * 1024 else { throw PanelError(message: "JAR größer als 128 MiB.") }; parent.result(.success(file)) }
            catch { try? FileManager.default.removeItem(at: file.deletingLastPathComponent()); parent.result(.failure(error)) }
        }
        func download(_ download: WKDownload, didFailWithError error: Error, resumeData: Data?) { let id = ObjectIdentifier(download); downloads[id] = nil; if let file = files.removeValue(forKey: id) { try? FileManager.default.removeItem(at: file.deletingLastPathComponent()) }; parent.failure("Download nicht möglich: " + error.localizedDescription + ". Bei Bedarf in Safari laden und die JAR über „Update-Datei hochladen“ auswählen.") }
    }
}
