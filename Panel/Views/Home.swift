import SwiftUI

struct HomeView: View {
    @ObservedObject private var suiteConnections = SuiteConnections.shared
    @EnvironmentObject private var store: AppStore
    @EnvironmentObject private var workspace: WorkspaceStore
    @State private var add = false
    @State private var settings = false
    @State private var transfers = false
    @State private var widgets = false
    @State private var search = ""
    @State private var editLayout = false
    @State private var iconRevision = 0
    @ObservedObject private var layouts = DashboardLayouts.shared
    var layoutKey: String { "home:" + (store.api?.base.absoluteString ?? "none") + "|" + (store.demo ? "demo" : store.selected?.uuidString ?? "none") }
    var filtered: [Server] { store.servers.filter { search.isEmpty || $0.name.localizedCaseInsensitiveContains(search) || $0.node.localizedCaseInsensitiveContains(search) }.sorted { a, b in
        guard let api = store.api else { return a.name < b.name }
        let ap = workspace.favorites.contains(workspace.key(api, a)), bp = workspace.favorites.contains(workspace.key(api, b))
        return ap == bp ? a.name.localizedStandardCompare(b.name) == .orderedAscending : ap
    } }
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    if let api = store.api {
                        ForEach(layouts.cards(layoutKey, scope: .home)) { card in homeCard(card, api: api) }
                        if store.demo { Button("Eigenes Panel verbinden", systemImage: "plus") { add = true }.buttonStyle(.glassProminent).frame(maxWidth: .infinity) }
                    } else { hero; welcome }
                }.padding(.bottom, 60).padding(22).frame(maxWidth: 1100).frame(maxWidth: .infinity)
            }.panelScreen().navigationTitle("Panel").navigationBarTitleDisplayMode(.inline)
                .searchable(text: $search, prompt: "Server suchen")
                .refreshable { iconRevision += 1; await store.refresh(); await store.refreshPresence() }.scrollBounceBehavior(.always)
                .toolbar {
                    ToolbarItem(placement: .topBarLeading) {
                        Menu {
                            ForEach(store.profiles) { p in Button { store.select(p.id) } label: { Label(p.name, systemImage: store.selected == p.id && !store.demo ? "checkmark.circle.fill" : "server.rack") } }
                            Button("Panel hinzufügen", systemImage: "plus") { add = true }
                            if store.demo { Button("Demo verlassen") { store.exitDemo() } }
                        } label: { Label(store.demo ? "Demo" : store.active?.name ?? "Panels", systemImage: "square.stack") }
                    }
                    ToolbarItem(placement: .topBarTrailing) { Button("Startseite anpassen", systemImage: "rectangle.3.group") { editLayout = true }.disabled(store.api == nil).accessibilityIdentifier("editHomeLayout") }
                    ToolbarItem(placement: .topBarTrailing) { Button("Übertragungen", systemImage: "arrow.up.arrow.down.circle") { transfers = true } }
                    ToolbarItem(placement: .topBarTrailing) { Button("Einstellungen", systemImage: "slider.horizontal.3") { settings = true } }
                }
                .navigationDestination(for: Server.self) { server in if let api = store.api { ServerView(api: api, server: server).id("\(store.selected?.uuidString ?? "demo")-\(server.id)") } }
                .sheet(isPresented: $editLayout) { DashboardEditor(scope: .home, layoutKey: layoutKey) }
                .sheet(isPresented: $add) { AddPanelView() }
                .sheet(isPresented: $settings) { SettingsView() }
                .sheet(isPresented: $transfers) { TransfersView() }
                .sheet(isPresented: $widgets) { NavigationStack { WidgetSettingsView().toolbar { ToolbarItem(placement: .confirmationAction) { Button("Fertig") { widgets = false } } } } }
                .onOpenURL { url in if url.host == "transfers" { transfers = true }; if url.host == "widgets" { widgets = true } }
                .task(id: store.api.map { ObjectIdentifier($0) }) { await store.refresh(); await store.pollPresence() }
        }
    }
    func isBedrockOnly(_ server: Server) -> Bool {
        let container = server.data["container"]["image"].string.lowercased()
        let invocation = server.data["invocation"].string.lowercased()
        return container.contains("bedrock") || invocation.contains("bedrock_server")
    }
    var hero: some View {
                    HStack(alignment: .top) {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(store.demo ? "DEMO WORKSPACE" : "DEIN WORKSPACE").font(.caption.weight(.bold)).tracking(2).foregroundStyle(.teal)
                            Text("Alles unter\nKontrolle.").font(.system(size: 39, weight: .bold, design: .rounded)).fixedSize(horizontal: false, vertical: true)
                            Text(store.demo ? "Beispieldaten · keine Verbindung zu echten Servern" : store.active?.url ?? "Verbinde dein Pterodactyl-Panel.").font(.subheadline).foregroundStyle(.secondary)
                        }
                        Spacer(minLength: 0)
                        Image(systemName: "server.rack").font(.system(size: 32, weight: .light)).foregroundStyle(.teal).padding(20).glassEffect(.regular.tint(.teal.opacity(0.12)), in: .rect(cornerRadius: 25))
                    }.padding(.top, 16)
    }
    var serverList: some View {
        VStack(alignment: .leading, spacing: 18) {
                        HStack { Label("\(store.servers.count) Server", systemImage: "square.stack.3d.up").font(.subheadline.weight(.medium)); Spacer(); if store.loading { ProgressView() } }
                        if filtered.isEmpty {
                            ContentUnavailableView(search.isEmpty ? "Keine Server" : "Keine Treffer", systemImage: "server.rack", description: Text("Ziehe nach unten, um die Liste zu aktualisieren."))
                        }
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: 300), spacing: 16)], spacing: 16) {
                            ForEach(filtered) { server in
                                VStack(spacing: 12) {
                                NavigationLink(value: server) { ServerCard(server: server, api: store.api, iconEnabled: store.api.map { workspace.isMinecraft($0, server) && !$0.demo && !isBedrockOnly(server) } ?? false, iconRevision: iconRevision, presence: store.presence[server.id], favorite: store.api.map { workspace.favorites.contains(workspace.key($0, server)) } ?? false) }.buttonStyle(.plain).accessibilityIdentifier("server-\(server.id)").contextMenu {
                                    if let api = store.api { Button(workspace.favorites.contains(workspace.key(api, server)) ? "Server lösen" : "Server anpinnen", systemImage: "star") { workspace.toggleServer(api, server) } }
                                }
                                if let api = store.api, suiteConnections.get(api, server) != nil || store.demo && ProcessInfo.processInfo.arguments.contains("--demo-suite") {
                                    HStack {
                                        NavigationLink { SuitePlayerBrowser(panel: api, server: server, filter: .online) } label: { Label("Online-Spieler", systemImage: "person.2.fill") }.accessibilityIdentifier("workspace-online-" + server.id)
                                        Spacer()
                                        NavigationLink { SuitePlayerBrowser(panel: api, server: server) } label: { Label("Spieler suchen", systemImage: "magnifyingglass") }
                                    }.font(.subheadline).padding(.horizontal, 12)
                                }
                                }
                            }
                        }
        }
    }
    @ViewBuilder func homeCard(_ card: DashboardCard, api: PanelAPI) -> some View {
        switch card {
        case .welcome: hero
        case .folders: pinnedFolders(api)
        case .servers: serverList
        case .fleet:
            VStack(alignment: .leading, spacing: 16) {
                Label("Server im Überblick", systemImage: card.symbol).font(.headline)
                HStack(spacing: 24) {
                    fleetCount("Online", count: store.servers.filter { store.presence[$0.id]?.state == "running" }.count, color: .green)
                    fleetCount("Offline", count: store.servers.filter { store.presence[$0.id]?.state == "offline" }.count, color: .secondary)
                    fleetCount("Weitere", count: store.servers.filter { !["running", "offline"].contains(store.presence[$0.id]?.state ?? "unknown") }.count, color: .orange)
                }
                Text("Statusabfrage etwa alle 15 Sekunden, solange die Startseite geöffnet ist. Unbekannte Zustände zählen unter Weitere.").font(.caption).foregroundStyle(.secondary)
                if let updated = store.presence.values.map(\.updated).max() { HStack { Text("Letzte Antwort"); Spacer(); Text(updated, style: .time) }.font(.caption).foregroundStyle(.secondary) }
            }.frame(maxWidth: .infinity, alignment: .leading).card()
        case .favorites:
            VStack(alignment: .leading, spacing: 14) {
                Label("Angepinnte Server", systemImage: "star.fill").font(.headline)
                let favorites = filtered.filter { workspace.favorites.contains(workspace.key(api, $0)) }
                ForEach(favorites) { server in
                    NavigationLink(value: server) { HStack { Text(server.name); Spacer(); StatePill(state: store.presence[server.id]?.state ?? "unknown"); Image(systemName: "chevron.right").font(.caption) } }.buttonStyle(.plain)
                }
                if favorites.isEmpty { Text("Server über das Kontextmenü anpinnen.").font(.subheadline).foregroundStyle(.secondary) }
            }.frame(maxWidth: .infinity, alignment: .leading).card()
        case .account:
            VStack(alignment: .leading, spacing: 14) {
                Label("Panel-Informationen", systemImage: card.symbol).font(.headline)
                LabeledContent("Panel", value: store.demo ? "Demo" : store.active?.name ?? "Panel")
                Text(api.base.absoluteString).font(.caption.monospaced()).foregroundStyle(.secondary).textSelection(.enabled)
                LabeledContent("Server", value: String(store.servers.count))
                NavigationLink("Account & Sicherheit") { AccountView(api: api) }
            }.frame(maxWidth: .infinity, alignment: .leading).card()
        default: EmptyView()
        }
    }
    func fleetCount(_ title: String, count: Int, color: Color) -> some View {
        VStack(alignment: .leading, spacing: 4) { Text(count, format: .number).font(.title.bold()).foregroundStyle(color); Text(title).font(.caption).foregroundStyle(.secondary) }.frame(maxWidth: .infinity, alignment: .leading)
    }
    @ViewBuilder func pinnedFolders(_ api: PanelAPI) -> some View {
        let pins = workspace.folders.filter { pin in pin.panel == api.base.absoluteString && store.servers.contains(where: { $0.id == pin.serverID }) }
        if !pins.isEmpty {
            VStack(alignment: .leading, spacing: 12) {
                Label("Favoritenordner", systemImage: "pin.fill").font(.headline)
                ForEach(pins) { pin in
                    if let server = store.servers.first(where: { $0.id == pin.serverID }) {
                        NavigationLink { ServerView(api: api, server: server, initialFolder: pin.path) } label: {
                            HStack { Image(systemName: "folder.fill").foregroundStyle(.teal); VStack(alignment: .leading) { Text(pin.path); Text(server.name).font(.caption).foregroundStyle(.secondary) }; Spacer(); Image(systemName: "chevron.right").font(.caption) }.padding(12).background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
                        }.buttonStyle(.plain).contextMenu { Button("Lösen", systemImage: "pin.slash") { workspace.toggleFolder(api, server, pin.path) } }
                    }
                }
            }
        }
    }
    var welcome: some View {
        VStack(alignment: .leading, spacing: 20) {
            Label("Deine Server, überall.", systemImage: "globe.europe.africa.fill").font(.title2.bold())
            Text("Konsole, Live-Ressourcen, Dateien und Backups. Direkt mit deinem Panel verbunden.").foregroundStyle(.secondary)
            Button("Panel verbinden", systemImage: "link") { add = true }.buttonStyle(.glassProminent).controlSize(.large)
            Button("App mit Beispieldaten entdecken", systemImage: "sparkles") { store.startDemo() }.buttonStyle(.glass)
            Label("API-Schlüssel bleiben im Schlüsselbund dieses Geräts.", systemImage: "lock.shield").font(.footnote).foregroundStyle(.secondary)
        }.frame(maxWidth: .infinity, alignment: .leading).card()
    }
}
struct ServerCard: View {
    let server: Server
    var api: PanelAPI? = nil
    var iconEnabled = true
    var iconRevision = 0
    var presence: ServerPresence? = nil
    var favorite = false
    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack {
                ServerIconView(api: api, server: server, enabled: iconEnabled, revision: iconRevision)
                Spacer()
                StatePill(state: presence?.state ?? "unknown")
            }
            VStack(alignment: .leading, spacing: 6) {
                HStack { Text(server.name).font(.title2.bold()).foregroundStyle(.primary); if favorite { Image(systemName: "star.fill").font(.subheadline).foregroundStyle(.orange) } }
                Text(server.data["description"].string.isEmpty ? server.node : server.data["description"].string).font(.subheadline).foregroundStyle(.secondary).lineLimit(2)
            }
            Divider()
            HStack { Label(server.node, systemImage: "location"); Spacer(); Text(server.id).font(.system(.caption, design: .monospaced)) }.font(.caption).foregroundStyle(.secondary)
        }.frame(maxWidth: .infinity, alignment: .leading).card()
    }
}
struct AddPanelView: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var url = ""
    @State private var key = ""
    @State private var error: String?
    @State private var busy = false
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    VStack(alignment: .leading, spacing: 12) {
                        Image(systemName: "link.circle.fill").font(.system(size: 42)).foregroundStyle(.teal)
                        Text("Verbinde dein Panel").font(.title2.bold())
                        Text("Verwende die Webadresse, mit der du die Pterodactyl-Konsole im Browser öffnest.").foregroundStyle(.secondary)
                    }.padding(.vertical, 8)
                }
                Section {
                    TextField("Name, z. B. Kinetic", text: $name)
                    TextField("https://panel.example.com", text: $url).keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled().accessibilityIdentifier("panelURL")
                    SecureField("Client-API-Schlüssel (ptlc_…)", text: $key).textInputAutocapitalization(.never).autocorrectionDisabled().privacySensitive().accessibilityIdentifier("apiKey")
                } header: { Text("Zugang") } footer: { Text("Im Panel: Account → API Credentials → Create API Key. Verwende einen Client-Schlüssel. Application-Schlüssel sind für die Administration des gesamten Panels gedacht.") }
                Section {
                    Button { connect() } label: { HStack { Spacer(); if busy { ProgressView() }; Text(busy ? "Verbindung prüfen …" : "Verbinden").bold(); Spacer() } }.disabled(busy || url.isEmpty || key.isEmpty)
                } footer: { Text("Nur HTTPS. Dein Schlüssel wird ausschließlich an dieses Panel gesendet und lokal im iOS-Schlüsselbund gespeichert.") }
            }.panelScreen().navigationTitle("Panel hinzufügen").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() }.disabled(busy) } }.errorAlert($error).interactiveDismissDisabled(busy)
        }
    }
    func connect() { busy = true; Task { do { try await store.add(name: name, address: url, key: key); key = ""; dismiss() } catch { self.error = error.localizedDescription }; busy = false } }
}
struct SettingsView: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss
    @AppStorage("appearance") private var appearance = "system"
    @AppStorage("syntaxColors") private var syntaxColors = true
    @AppStorage("haptics") private var haptics = true
    @State private var remove: PanelProfile?
    var body: some View {
        NavigationStack {
            Form {
                Section("Darstellung") { Picker("Design", selection: $appearance) { Text("System").tag("system"); Text("Dunkel").tag("dark"); Text("Hell").tag("light") } }
                Section("Editor & Bedienung") { Toggle("Syntaxfarben im Texteditor", isOn: $syntaxColors); Toggle("Haptisches Feedback", isOn: $haptics) }
                Section("Widgets & Übertragungen") { NavigationLink("Server-Widgets einrichten") { WidgetSettingsView() }; TransferLink() }
                Section("Sicherheit") { Toggle("App mit Face ID / Gerätecode sperren", isOn: $store.biometricLock) }
                Section("Gespeicherte Panels") {
                    ForEach(store.profiles) { p in HStack { VStack(alignment: .leading) { Text(p.name); Text(p.url).font(.caption).foregroundStyle(.secondary) }; Spacer(); Button("Entfernen", systemImage: "trash", role: .destructive) { remove = p }.labelStyle(.iconOnly) } }
                    if store.profiles.isEmpty { Text("Noch kein Panel gespeichert").foregroundStyle(.secondary) }
                }
                if let api = store.api { Section("Account") { NavigationLink("Account & Sicherheit") { AccountView(api: api) } } }
                Section {
                    LabeledContent("Version", value: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "–")
                    Text("Unabhängiger Pterodactyl-Client. Funktionen und Limits richten sich nach deinem Hoster und deinen Panel-Berechtigungen.").font(.footnote).foregroundStyle(.secondary)
                }
            }.panelScreen().navigationTitle("Einstellungen").toolbar { ToolbarItem(placement: .confirmationAction) { Button("Fertig") { dismiss() } } }
                .confirmationDialog("Gespeicherten Zugang entfernen?", isPresented: Binding(get: { remove != nil }, set: { if !$0 { remove = nil } }), titleVisibility: .visible) {
                    Button("Zugang entfernen", role: .destructive) { if let p = remove { store.remove(p) }; remove = nil }
                } message: { Text("Der lokale API-Schlüssel wird gelöscht. Dein Server bleibt bestehen.") }
        }
    }
}
