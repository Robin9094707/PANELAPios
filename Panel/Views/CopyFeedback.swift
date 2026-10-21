import SwiftUI

@MainActor final class ClipboardState: ObservableObject {
    static let shared = ClipboardState()
    @Published var copied = false
    @Published var pulse = 0
    private var reset: Task<Void, Never>?
    func copy(_ value: String) {
        UIPasteboard.general.string = value
        Feedback.success(); pulse += 1
        withAnimation(.spring(response: 0.32, dampingFraction: 0.8)) { copied = true }
        reset?.cancel(); reset = Task { do { try await Task.sleep(for: .seconds(1.6)) } catch { return }; withAnimation { copied = false } }
    }
}
@MainActor enum Clipboard { static func copy(_ value: String) { ClipboardState.shared.copy(value) } }
struct CopyButton: View {
    let text: String
    var title = "Kopieren"
    @State private var copied = false
    var body: some View {
        Button { Clipboard.copy(text); withAnimation(.spring) { copied = true }; Task { try? await Task.sleep(for: .seconds(1.6)); copied = false } } label: { Label(copied ? "Kopiert" : title, systemImage: copied ? "checkmark.circle.fill" : "doc.on.doc").contentTransition(.symbolEffect(.replace)) }.buttonStyle(.borderless).disabled(text.isEmpty)
    }
}
struct CopyFeedbackOverlay: View {
    @ObservedObject private var clipboard = ClipboardState.shared
    var body: some View { VStack { if clipboard.copied { Label("Kopiert", systemImage: "checkmark.circle.fill").font(.subheadline.weight(.semibold)).foregroundStyle(Color.panelAccent).padding(.horizontal, 20).padding(.vertical, 12).glassEffect(.regular, in: Capsule()).padding(.top, 12).transition(.move(edge: .top).combined(with: .opacity)) }; Spacer() }.allowsHitTesting(false).accessibilityHidden(true) }
}
