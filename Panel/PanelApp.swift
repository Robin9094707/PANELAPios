import SwiftUI

@main struct PanelApp: App {
    @UIApplicationDelegateAdaptor(PanelAppDelegate.self) private var appDelegate
    @StateObject private var transfers = TransferManager.shared
    @StateObject private var store = AppStore()
    @StateObject private var workspace = WorkspaceStore()
    @Environment(\.scenePhase) private var scenePhase
    @AppStorage("theme.primary") private var themePrimary = "008F83"
    @AppStorage("theme.secondary") private var themeSecondary = "7046BA"
    @AppStorage("appearance") private var appearance = "system"
    @AppStorage(WhatsNew.lastSeenKey) private var whatsNewSeen = ""
    @State private var whatsNew: WhatsNewRelease?
    var body: some Scene {
        WindowGroup {
            Group {
                if store.locked { UnlockView() }
                else { HomeView() }
            }.environmentObject(store).environmentObject(workspace).tint(Color.panelAccent)
                .preferredColorScheme(appearance == "dark" ? .dark : appearance == "light" ? .light : nil)
                .onChange(of: store.locked) { _, locked in if !locked && scenePhase == .active { ServerActivityManager.shared.resume(api: store.api, servers: store.servers) } }
                .onChange(of: store.servers) { _, servers in if scenePhase == .active { ServerActivityManager.shared.resume(api: store.api, servers: servers) } }
                .errorAlert($store.error)
                .sheet(item: $whatsNew, onDismiss: { whatsNewSeen = WhatsNew.current }) { WhatsNewView(release: $0).presentationDetents([.large]) }
                .task(id: store.locked) { presentWhatsNewIfNeeded() }
                .privacySensitive()
                .overlay { if scenePhase == .background { PanelBackground().overlay { Image(systemName: "server.rack").font(.system(size: 60)).foregroundStyle(.teal) } } }
                .onChange(of: scenePhase, initial: true) { _, phase in
                    if phase == .background { transfers.persist(force: true); ServerActivityManager.shared.suspend(); store.lock() }
                    if phase == .active { Task { await store.resume(); ServerActivityManager.shared.resume(api: store.api, servers: store.servers) } }
                }
        }
    }
    /// Shown once per updated version after unlocking; fresh installs and UI tests skip it.
    func presentWhatsNewIfNeeded() {
        guard !store.locked, whatsNew == nil else { return }
        let arguments = ProcessInfo.processInfo.arguments, current = WhatsNew.current
        if arguments.contains("--whats-new") { whatsNew = WhatsNew.release(for: current); return }
        if arguments.contains("--demo") { return }
        let defaults = UserDefaults.standard
        let existing = defaults.object(forKey: "profiles") != nil || defaults.object(forKey: "adminSuite.connections.v1") != nil
        if WhatsNew.shouldPresent(current: current, lastSeen: whatsNewSeen, existingUser: existing) { whatsNew = WhatsNew.release(for: current) }
        else if whatsNewSeen != current { whatsNewSeen = current }
    }
}
struct UnlockView: View {
    @EnvironmentObject private var store: AppStore
    var body: some View {
        VStack(spacing: 24) {
            Image(systemName: "lock.shield").font(.system(size: 64)).foregroundStyle(.teal)
            Text("Dein Panel. Sicher.").font(.largeTitle.bold())
            Button("Entsperren", systemImage: "faceid") { Task { await store.unlock() } }.buttonStyle(.glassProminent)
        }.frame(maxWidth: .infinity, maxHeight: .infinity).background { PanelBackground() }
    }
}

