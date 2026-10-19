import SwiftUI

@main struct PanelApp: App {
    @StateObject private var store = AppStore()
    @Environment(\.scenePhase) private var scenePhase
    @AppStorage("appearance") private var appearance = "system"
    var body: some Scene {
        WindowGroup {
            Group {
                if store.locked { UnlockView() }
                else { HomeView() }
            }.environmentObject(store).tint(.teal)
                .preferredColorScheme(appearance == "dark" ? .dark : appearance == "light" ? .light : nil)
                .errorAlert($store.error)
                .privacySensitive()
                .overlay { if scenePhase == .background { PanelBackground().overlay { Image(systemName: "server.rack").font(.system(size: 60)).foregroundStyle(.teal) } } }
                .onChange(of: scenePhase) { _, phase in if phase == .background { store.lock() } }
        }
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
