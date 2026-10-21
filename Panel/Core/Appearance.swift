import SwiftUI
import UIKit

struct ThemeRGB: Equatable {
    var red: Double; var green: Double; var blue: Double
    init(_ hex: String) { let value = UInt32(hex.trimmingCharacters(in: CharacterSet(charactersIn: "#")), radix: 16) ?? 0x008F83; red = Double((value >> 16) & 255) / 255; green = Double((value >> 8) & 255) / 255; blue = Double(value & 255) / 255 }
    init(red: Double, green: Double, blue: Double) { self.red = red; self.green = green; self.blue = blue }
    var hex: String { String(format: "%02X%02X%02X", Int(max(0, min(1, red)) * 255), Int(max(0, min(1, green)) * 255), Int(max(0, min(1, blue)) * 255)) }
    var luminance: Double { func linear(_ v: Double) -> Double { v <= 0.04045 ? v / 12.92 : pow((v + 0.055) / 1.055, 2.4) }; return 0.2126 * linear(red) + 0.7152 * linear(green) + 0.0722 * linear(blue) }
    func contrast(_ other: Self) -> Double { (max(luminance, other.luminance) + 0.05) / (min(luminance, other.luminance) + 0.05) }
    func readable(dark: Bool) -> Self {
        let background = Self(dark ? "081020" : "F0F8F8")
        if contrast(background) >= 4.5 { return self }
        for step in 1...100 { let t = Double(step) / 100, end = dark ? 1.0 : 0.0; let candidate = Self(red: red + (end - red) * t, green: green + (end - green) * t, blue: blue + (end - blue) * t); if candidate.contrast(background) >= 4.5 { return candidate } }
        return Self(dark ? "FFFFFF" : "000000")
    }
    var color: Color { Color(red: red, green: green, blue: blue) }
}
extension Color {
    static var panelAccent: Color { adaptiveTheme("theme.primary", fallback: "008F83") }
    static var panelSecondary: Color { adaptiveTheme("theme.secondary", fallback: "7046BA") }
    private static func adaptiveTheme(_ key: String, fallback: String) -> Color {
        let rgb = ThemeRGB(UserDefaults.standard.string(forKey: key) ?? fallback)
        return Color(uiColor: UIColor { trait in let v = rgb.readable(dark: trait.userInterfaceStyle == .dark); return UIColor(red: v.red, green: v.green, blue: v.blue, alpha: 1) })
    }
}
enum PanelIcon: String, CaseIterable, Identifiable {
    case simplex = "AppIcon", server = "ServerIcon", amethyst = "AmethystIcon"
    var id: String { rawValue }
    var title: String { switch self { case .simplex: return "Simplex Cyan"; case .server: return "Server Rack"; case .amethyst: return "Amethyst" } }
    var preview: String { "Preview" + rawValue }
}
struct AppearanceSettingsView: View {
    @AppStorage("theme.primary") private var primary = "008F83"
    @AppStorage("theme.secondary") private var secondary = "7046BA"
    @State private var icon = UIApplication.shared.alternateIconName ?? "AppIcon"
    @State private var error: String?
    @State private var changing = false
    func binding(_ value: Binding<String>) -> Binding<Color> { Binding(get: { ThemeRGB(value.wrappedValue).color }, set: { color in var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0; UIColor(color).getRed(&r, green: &g, blue: &b, alpha: &a); value.wrappedValue = ThemeRGB(red: Double(r), green: Double(g), blue: Double(b)).hex }) }
    var body: some View {
        Form {
            Section("Farben") {
                ColorPicker("Primärfarbe", selection: binding($primary), supportsOpacity: false)
                ColorPicker("Sekundärfarbe", selection: binding($secondary), supportsOpacity: false)
                Text("Text bleibt automatisch hell oder dunkel. Akzentfarben werden für ausreichenden Kontrast angepasst; Warnungen und Status behalten ihre eindeutigen Farben.").font(.footnote).foregroundStyle(.secondary)
                HStack { Label("Primär", systemImage: "server.rack").foregroundStyle(Color.panelAccent); Spacer(); Label("Sekundär", systemImage: "sparkles").foregroundStyle(Color.panelSecondary) }.padding(.vertical)
                Button("Simplex-Farben zurücksetzen") { primary = "008F83"; secondary = "7046BA" }
            }
            Section("App-Icon") {
                ForEach(PanelIcon.allCases) { candidate in
                    Button { changing = true; UIApplication.shared.setAlternateIconName(candidate == .simplex ? nil : candidate.rawValue) { failure in Task { @MainActor in changing = false; if let failure { error = failure.localizedDescription } else { icon = candidate.rawValue } } } } label: {
                        HStack(spacing: 14) { Image(candidate.preview).resizable().scaledToFit().frame(width: 60, height: 60).clipShape(RoundedRectangle(cornerRadius: 14)); Text(candidate.title).foregroundStyle(.primary); Spacer(); if candidate.rawValue == icon { Image(systemName: "checkmark.circle.fill").foregroundStyle(Color.panelAccent) } }
                    }.disabled(changing || !UIApplication.shared.supportsAlternateIcons)
                }
            }
        }.panelScreen().navigationTitle("Farben & App-Icon").errorAlert($error)
    }
}
