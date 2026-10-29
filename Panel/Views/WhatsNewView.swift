import SwiftUI

struct WhatsNewView: View {
    let release: WhatsNewRelease
    var embedded = false
    @Environment(\.dismiss) private var dismiss
    @State private var appeared = false
    var body: some View {
        if embedded { content.navigationTitle("Was ist neu").navigationBarTitleDisplayMode(.inline) }
        else { content.interactiveDismissDisabled(false) }
    }
    var content: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 26) {
                VStack(alignment: .leading, spacing: 14) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 26).fill(LinearGradient(colors: [Color.panelAccent, Color.panelSecondary], startPoint: .topLeading, endPoint: .bottomTrailing)).frame(width: 84, height: 84)
                        Image(systemName: "sparkles").font(.system(size: 38, weight: .semibold)).foregroundStyle(.white).symbolEffect(.bounce, value: appeared)
                    }.accessibilityHidden(true)
                    Text("Neu in Version " + release.version).font(.subheadline.weight(.semibold)).foregroundStyle(Color.panelAccent)
                    Text(release.title).font(.largeTitle.bold()).accessibilityIdentifier("whatsNewTitle")
                    Text(release.summary).font(.body).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                }
                VStack(alignment: .leading, spacing: 20) {
                    ForEach(Array(release.items.enumerated()), id: \.element.id) { index, item in
                        HStack(alignment: .top, spacing: 16) {
                            Image(systemName: item.symbol).font(.title2).foregroundStyle(index.isMultiple(of: 2) ? Color.panelAccent : Color.panelSecondary).frame(width: 36)
                            VStack(alignment: .leading, spacing: 4) {
                                Text(item.title).font(.headline)
                                Text(item.detail).font(.subheadline).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                            }
                        }
                        .opacity(appeared ? 1 : 0).offset(y: appeared ? 0 : 14)
                        .animation(.spring(duration: 0.5).delay(0.06 * Double(index)), value: appeared)
                        .accessibilityElement(children: .combine)
                    }
                }.frame(maxWidth: .infinity, alignment: .leading).card()
                if !embedded {
                    Button { dismiss() } label: { Text("Los geht’s").font(.headline).frame(maxWidth: .infinity).padding(.vertical, 6) }
                        .buttonStyle(.glassProminent).accessibilityIdentifier("whatsNewContinue")
                    Text("Diese Übersicht findest du jederzeit unter Einstellungen → Was ist neu.").font(.footnote).foregroundStyle(.secondary).frame(maxWidth: .infinity)
                }
            }.padding(24).frame(maxWidth: 640).frame(maxWidth: .infinity)
        }.panelScreen().onAppear { appeared = true }
    }
}
