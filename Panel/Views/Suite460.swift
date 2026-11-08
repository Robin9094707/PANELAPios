import SwiftUI
import UIKit

// MARK: - Simplex Panel 4.6.0 / Admin Suite 10.3.0
// Studio (Discord status and scoreboard designers with live preview), world trimmer and the player dossier.
// The server validates and saves everything; the app only edits drafts and shows previews.

/// Minecraft &-colour codes (incl. &x hex, &l &o &n &m &k &r) as an AttributedString (unit-tested).
enum MinecraftText {
    static let palette: [Character: Int] = ["0": 0x000000, "1": 0x0000AA, "2": 0x00AA00, "3": 0x00AAAA, "4": 0xAA0000, "5": 0xAA00AA, "6": 0xFFAA00, "7": 0xAAAAAA,
                                            "8": 0x555555, "9": 0x5555FF, "a": 0x55FF55, "b": 0x55FFFF, "c": 0xFF5555, "d": 0xFF55FF, "e": 0xFFFF55, "f": 0xFFFFFF]
    struct Style: Equatable {
        var color = 0xFFFFFF
        var bold = false
        var italic = false
        var underline = false
        var strike = false
    }
    /// Splits text into styled runs; pure and testable.
    static func runs(_ raw: String) -> [(String, Style)] {
        let chars = Array(raw.replacingOccurrences(of: "§", with: "&"))
        var result: [(String, Style)] = []
        var style = Style()
        var buffer = ""
        func flush() { if !buffer.isEmpty { result.append((buffer, style)); buffer = "" } }
        var i = 0
        while i < chars.count {
            let c = chars[i]
            if c == "&", i + 1 < chars.count {
                let code: Character = chars[i + 1].lowercased().first ?? chars[i + 1]
                if code == "x", let hex = hexColor(chars, at: i) { flush(); style = Style(color: hex); i += 14; continue }
                if let color = palette[code] { flush(); style = Style(color: color); i += 2; continue }
                switch code {
                case "l": flush(); style.bold = true; i += 2; continue
                case "o": flush(); style.italic = true; i += 2; continue
                case "n": flush(); style.underline = true; i += 2; continue
                case "m": flush(); style.strike = true; i += 2; continue
                case "k": i += 2; continue
                case "r": flush(); style = Style(); i += 2; continue
                default: break
                }
            }
            buffer.append(c)
            i += 1
        }
        flush()
        return result
    }
    /// "&x&R&R&G&G&B&B" starting at `index`.
    static func hexColor(_ chars: [Character], at index: Int) -> Int? {
        guard index + 13 < chars.count else { return nil }
        var hex = ""
        for n in 0..<6 {
            guard chars[index + 2 + n * 2] == "&" else { return nil }
            hex.append(chars[index + 3 + n * 2])
        }
        return Int(hex, radix: 16)
    }
    static func plain(_ raw: String) -> String { runs(raw).map { $0.0 }.joined() }
    static func color(_ value: Int) -> Color {
        Color(red: Double((value >> 16) & 255) / 255, green: Double((value >> 8) & 255) / 255, blue: Double(value & 255) / 255)
    }
    static func attributed(_ raw: String, size: CGFloat = 15) -> AttributedString {
        var out = AttributedString()
        for (text, style) in runs(raw) {
            var part = AttributedString(text)
            let tint: Color = color(style.color)
            var font: Font = Font.system(size: size, weight: style.bold ? .bold : .regular, design: .monospaced)
            if style.italic { font = font.italic() }
            part[AttributeScopes.SwiftUIAttributes.ForegroundColorAttribute.self] = tint
            part[AttributeScopes.SwiftUIAttributes.FontAttribute.self] = font
            if style.underline { part[AttributeScopes.SwiftUIAttributes.UnderlineStyleAttribute.self] = Text.LineStyle.single }
            if style.strike { part[AttributeScopes.SwiftUIAttributes.StrikethroughStyleAttribute.self] = Text.LineStyle.single }
            out.append(part)
        }
        return out
    }
    /// Hex gradient title like the shipped scoreboard ("&x&0&0&A&8&E&8&ls…").
    static func gradient(_ text: String, from: Int, to: Int, bold: Bool) -> String {
        let chars = Array(text)
        let steps = Double(max(1, chars.count - 1))
        var out = ""
        for (i, c) in chars.enumerated() {
            if c == " " { out.append(" "); continue }
            let t = Double(i) / steps
            let r = mix((from >> 16) & 255, (to >> 16) & 255, t), g = mix((from >> 8) & 255, (to >> 8) & 255, t), b = mix(from & 255, to & 255, t)
            let hex = String(format: "%02X%02X%02X", r, g, b)
            out += "&x" + hex.map { "&" + String($0) }.joined() + (bold ? "&l" : "") + String(c)
        }
        return out
    }
    private static func mix(_ a: Int, _ b: Int, _ t: Double) -> Int { Int((Double(a) + Double(b - a) * t).rounded()) }
    static func fill(_ raw: String, _ sample: JSON) -> String {
        var out = raw
        for (key, value) in sample.object { out = out.replacingOccurrences(of: key, with: value.string) }
        return out
    }
}

/// Discord embed helpers (unit-tested).
enum DiscordPreviewModel {
    /// Groups inline fields into rows of up to three, like Discord's desktop layout.
    static func fieldRows(_ fields: [JSON]) -> [[JSON]] {
        var rows: [[JSON]] = []
        var current: [JSON] = []
        for field in fields {
            if field["inline"].bool {
                current.append(field)
                if current.count == 3 { rows.append(current); current = [] }
            } else {
                if !current.isEmpty { rows.append(current); current = [] }
                rows.append([field])
            }
        }
        if !current.isEmpty { rows.append(current) }
        return rows
    }
    static func markdown(_ text: String) -> AttributedString {
        let cleaned = text.replacingOccurrences(of: "\\_", with: "_").replacingOccurrences(of: "\\*", with: "*")
        let options = AttributedString.MarkdownParsingOptions(interpretedSyntax: .inlineOnlyPreservingWhitespace)
        return (try? AttributedString(markdown: cleaned, options: options)) ?? AttributedString(cleaned)
    }
    static func hex(_ text: String) -> Int? {
        let value = text.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: "#", with: "")
        guard value.count == 6 else { return nil }
        return Int(value, radix: 16)
    }
    static func hexString(_ color: Color) -> String {
        let ui = UIColor(color)
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        ui.getRed(&r, green: &g, blue: &b, alpha: &a)
        return String(format: "#%02X%02X%02X", Int((r * 255).rounded()), Int((g * 255).rounded()), Int((b * 255).rounded()))
    }
}

// MARK: - Discord-like preview

struct SuiteDiscordPreview: View {
    let preview: JSON
    private var embed: JSON { preview["message"]["embeds"].array.first ?? .null }
    private var accent: Color { MinecraftText.color(embed["color"].int) }
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            channelBar
            HStack(alignment: .top, spacing: 12) {
                avatar
                VStack(alignment: .leading, spacing: 6) {
                    nameRow
                    SuiteDiscordEmbed(embed: embed, accent: accent)
                }
            }
            .padding(12)
        }
        .background(Color(red: 0.192, green: 0.2, blue: 0.22))
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .environment(\.colorScheme, .dark)
        .accessibilityElement(children: .contain)
    }
    private var channelBar: some View {
        HStack(spacing: 6) {
            Text("#").font(.title3).foregroundStyle(.secondary)
            Text("server-status").font(.subheadline.weight(.semibold))
            Circle().fill(presenceColor).frame(width: 9, height: 9)
            Spacer(minLength: 8)
            Text(preview["activityType"].string + " " + preview["activity"].string).font(.caption).foregroundStyle(.secondary).lineLimit(1)
        }
        .padding(.horizontal, 12).padding(.vertical, 8)
        .background(Color(red: 0.169, green: 0.176, blue: 0.192))
    }
    private var presenceColor: Color {
        switch preview["presenceStatus"].string {
        case "dnd": return .red
        case "invisible": return .gray
        default: return .green
        }
    }
    @ViewBuilder private var avatar: some View {
        let url = URL(string: preview["botAvatar"].string)
        if let url, !preview["botAvatar"].string.isEmpty {
            AsyncImage(url: url) { image in image.resizable().scaledToFill() } placeholder: { avatarFallback }
                .frame(width: 38, height: 38).clipShape(Circle())
        } else { avatarFallback }
    }
    private var avatarFallback: some View {
        Circle().fill(Color(red: 0.345, green: 0.396, blue: 0.949)).frame(width: 38, height: 38)
            .overlay { Text(String(preview["botName"].string.prefix(1)).uppercased()).font(.headline).foregroundStyle(.white) }
    }
    private var nameRow: some View {
        HStack(spacing: 6) {
            Text(preview["botName"].string.isEmpty ? "Simplex" : preview["botName"].string).font(.subheadline.weight(.semibold))
            Text("APP").font(.system(size: 9, weight: .bold)).padding(.horizontal, 4).padding(.vertical, 1)
                .background(Color(red: 0.345, green: 0.396, blue: 0.949), in: RoundedRectangle(cornerRadius: 3)).foregroundStyle(.white)
            Text("Heute").font(.caption2).foregroundStyle(.secondary)
        }
    }
}

struct SuiteDiscordEmbed: View {
    let embed: JSON
    let accent: Color
    var body: some View {
        HStack(alignment: .top, spacing: 0) {
            Rectangle().fill(accent).frame(width: 4)
            HStack(alignment: .top, spacing: 10) {
                VStack(alignment: .leading, spacing: 8) {
                    if !embed["author"]["name"].string.isEmpty { Text(embed["author"]["name"].string).font(.caption.weight(.semibold)) }
                    if !embed["title"].string.isEmpty { Text(DiscordPreviewModel.markdown(embed["title"].string)).font(.subheadline.weight(.bold)) }
                    if !embed["description"].string.isEmpty { Text(DiscordPreviewModel.markdown(embed["description"].string)).font(.footnote) }
                    fields
                    image
                    footer
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                thumbnail
            }
            .padding(10)
        }
        .background(Color(red: 0.169, green: 0.176, blue: 0.192))
        .clipShape(RoundedRectangle(cornerRadius: 5))
    }
    private var fields: some View {
        let rows = DiscordPreviewModel.fieldRows(embed["fields"].array)
        return VStack(alignment: .leading, spacing: 8) {
            ForEach(rows.indices, id: \.self) { index in
                HStack(alignment: .top, spacing: 10) {
                    ForEach(rows[index].indices, id: \.self) { column in SuiteDiscordField(field: rows[index][column]) }
                }
            }
        }
    }
    @ViewBuilder private var image: some View {
        if let url = URL(string: embed["image"]["url"].string), !embed["image"]["url"].string.isEmpty {
            AsyncImage(url: url) { image in image.resizable().scaledToFit() } placeholder: { Color.black.opacity(0.25).frame(height: 90) }
                .clipShape(RoundedRectangle(cornerRadius: 4))
        }
    }
    @ViewBuilder private var thumbnail: some View {
        if let url = URL(string: embed["thumbnail"]["url"].string), !embed["thumbnail"]["url"].string.isEmpty {
            AsyncImage(url: url) { image in image.resizable().scaledToFill() } placeholder: { Color.black.opacity(0.25) }
                .frame(width: 64, height: 64).clipShape(RoundedRectangle(cornerRadius: 4))
        }
    }
    @ViewBuilder private var footer: some View {
        if !embed["footer"]["text"].string.isEmpty { Text(embed["footer"]["text"].string + " • Heute").font(.caption2).foregroundStyle(.secondary) }
    }
}

struct SuiteDiscordField: View {
    let field: JSON
    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(DiscordPreviewModel.markdown(field["name"].string)).font(.caption.weight(.semibold))
            Text(DiscordPreviewModel.markdown(field["value"].string)).font(.caption)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

// MARK: - Discord status designer

struct SuiteDiscordStatusDesignerView: View {
    let suite: SuiteAPI
    @State private var data: JSON = .null
    @State private var values: [String: JSON] = [:]
    @State private var saved: [String: JSON] = [:]
    @State private var preview: JSON = .null
    @State private var state = "online"
    @State private var group = ""
    @State private var preset = ""
    @State private var previewTask: Task<Void, Never>?
    @State private var busy = false
    @State private var error: String?
    @State private var notice: String?
    private var canEdit: Bool { data["canEdit"].bool }
    private var fields: [JSON] { data["fields"].array }
    private var groups: [String] {
        var seen: [String] = []
        for f in fields where !seen.contains(f["group"].string) { seen.append(f["group"].string) }
        return seen
    }
    private var dirty: Bool { values != saved }
    var body: some View {
        List {
            if data == .null { Section { ProgressView("Designer laden …") } }
            else {
                Section { previewBlock } header: { Text("Live-Vorschau") } footer: { Text("Genau so baut der Server die Statusnachricht – nur nicht gesendet.") }
                presetSection
                editorSection
                if !canEdit { Section { Label("Nur ansehen: Zum Speichern ist „Discord-Status gestalten“ nötig.", systemImage: "lock.fill").foregroundStyle(.secondary) } }
            }
        }
        .panelScreen()
        .navigationTitle("Discord-Status")
        .toolbar { toolbar }
        .task { await load() }
        .errorAlert($error)
        .alert(notice ?? "", isPresented: Binding(get: { notice != nil }, set: { if !$0 { notice = nil } })) { Button("OK") { notice = nil } }
    }
    @ToolbarContentBuilder private var toolbar: some ToolbarContent {
        if canEdit {
            ToolbarItem(placement: .topBarTrailing) {
                Button { Task { await save() } } label: { if busy { ProgressView() } else { Text("Speichern").bold() } }.disabled(!dirty || busy)
            }
        }
    }
    private var previewBlock: some View {
        VStack(alignment: .leading, spacing: 10) {
            Picker("Zustand", selection: $state) {
                Text("Online").tag("online")
                Text("Instabil").tag("warning")
                Text("Überlastet").tag("critical")
                Text("Neustart").tag("restarting")
                Text("Offline").tag("offline")
            }
            .pickerStyle(.segmented)
            .onChange(of: state) { _, _ in schedule() }
            if preview == .null { ProgressView() } else { SuiteDiscordPreview(preview: preview) }
            if dirty { Label("Ungespeicherte Änderungen", systemImage: "pencil.circle.fill").font(.caption).foregroundStyle(.orange) }
        }
        .listRowInsets(EdgeInsets(top: 10, leading: 10, bottom: 10, trailing: 10))
    }
    private var presetSection: some View {
        Section {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 10) {
                    ForEach(data["presets"].array, id: \.["id"].string) { p in SuiteStudioPresetCard(title: p["title"].string, detail: p["explain"].string, tint: tint(for: p), selected: preset == p["id"].string) { apply(p) }.disabled(!canEdit) }
                }
                .padding(.vertical, 4)
            }
        } header: { Text("Vorlagen") } footer: { Text("„Simplex Standard“ ist der bisherige Status. Vorlagen ändern erst beim Speichern etwas.") }
    }
    private func tint(for preset: JSON) -> Color {
        let raw = preset["values"]["healthy-color"].string.isEmpty ? data["defaults"]["healthy-color"].string : preset["values"]["healthy-color"].string
        return MinecraftText.color(DiscordPreviewModel.hex(raw) ?? 0x57F287)
    }
    private var editorSection: some View {
        Section {
            Picker("Bereich", selection: $group) { ForEach(groups, id: \.self) { Text($0).tag($0) } }
            ForEach(fields.filter { $0["group"].string == group }, id: \.["key"].string) { field in
                SuiteStudioFieldEditor(field: field, value: binding(field["key"].string), disabled: !canEdit)
            }
        } header: { Text("Gestaltung") } footer: { Text("Platzhalter: {server} {players} {max} {tps} {seconds} {interval} {progress} · Markdown: **fett** _kursiv_ `code`") }
    }
    private func binding(_ key: String) -> Binding<JSON> {
        Binding(get: { values[key] ?? .null }, set: { values[key] = $0; schedule() })
    }
    private func apply(_ p: JSON) {
        var next = data["defaults"].object
        for (k, v) in p["values"].object { next[k] = v }
        values = next; preset = p["id"].string; schedule()
    }
    private func schedule() {
        previewTask?.cancel()
        previewTask = Task { try? await Task.sleep(for: .milliseconds(320)); if !Task.isCancelled { await refresh() } }
    }
    private func load() async {
        do {
            let result = try await suite.request("studio/discord-status")
            data = result; values = result["values"].object; saved = values; preview = result["preview"]
            if group.isEmpty { group = groups.first ?? "" }
            await refresh()
        } catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }
    private func refresh() async {
        do { preview = try await suite.post("studio/discord-status", ["operation": .string("preview"), "state": .string(state), "values": .object(values)]) }
        catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }
    private func save() async {
        busy = true; defer { busy = false }
        do {
            let result = try await suite.post("studio/discord-status", ["operation": .string("save"), "values": .object(values), "preset": preset.isEmpty ? .null : .string(preset)])
            data = result; values = result["values"].object; saved = values; Feedback.success(); notice = result["notice"].string
        } catch { self.error = error.localizedDescription }
    }
}

/// One designer field: text, multiline, url, colour, number, int or bool.
struct SuiteStudioFieldEditor: View {
    let field: JSON
    @Binding var value: JSON
    let disabled: Bool
    private var label: String { field["label"].string }
    var body: some View {
        switch field["type"].string {
        case "bool":
            Toggle(label, isOn: Binding(get: { value.bool || value.string == "true" }, set: { value = .bool($0) })).disabled(disabled)
        case "color":
            ColorPicker(label, selection: Binding(get: { MinecraftText.color(DiscordPreviewModel.hex(value.string) ?? 0) }, set: { value = .string(DiscordPreviewModel.hexString($0)) }), supportsOpacity: false).disabled(disabled)
        case "number", "int":
            Stepper(value: Binding(get: { value.double }, set: { value = .number(field["type"].string == "int" ? $0.rounded() : ($0 * 10).rounded() / 10) }), in: field["min"].double...max(field["min"].double, field["max"].double), step: field["type"].string == "int" ? 1 : 0.5) {
                LabeledContent(label, value: field["type"].string == "int" ? value.string : String(format: "%.1f", value.double))
            }.disabled(disabled)
        case "multiline":
            VStack(alignment: .leading, spacing: 4) {
                Text(label).font(.caption).foregroundStyle(.secondary)
                TextField(label, text: textBinding, axis: .vertical).lineLimit(2...5).disabled(disabled)
            }
        default:
            VStack(alignment: .leading, spacing: 4) {
                Text(label).font(.caption).foregroundStyle(.secondary)
                TextField(field["type"].string == "url" ? "https://…" : label, text: textBinding).textInputAutocapitalization(.never).autocorrectionDisabled(field["type"].string == "url").disabled(disabled)
            }
        }
    }
    private var textBinding: Binding<String> { Binding(get: { value.string }, set: { value = .string(String($0.prefix(max(1, field["max"].int)))) }) }
}

struct SuiteStudioPresetCard: View {
    let title: String
    let detail: String
    let tint: Color
    let selected: Bool
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 6) {
                Capsule().fill(tint.gradient).frame(width: 34, height: 6)
                Text(title).font(.subheadline.weight(.semibold)).foregroundStyle(.primary)
                Text(detail).font(.caption2).foregroundStyle(.secondary).lineLimit(3).multilineTextAlignment(.leading)
            }
            .frame(width: 150, alignment: .leading)
            .padding(10)
            .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .overlay { RoundedRectangle(cornerRadius: 12, style: .continuous).strokeBorder(selected ? Color.panelAccent : Color.clear, lineWidth: 2) }
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Scoreboard designer

struct SuiteScoreboardPreview: View {
    let title: String
    let lines: [String]
    let sample: JSON
    let pointsEnabled: Bool
    var body: some View {
        HStack {
            Spacer(minLength: 0)
            VStack(alignment: .leading, spacing: 1) {
                Text(MinecraftText.attributed(MinecraftText.fill(title, sample))).frame(maxWidth: .infinity, alignment: .center).padding(.vertical, 3).background(Color.black.opacity(0.18))
                ForEach(visible.indices, id: \.self) { index in
                    Text(MinecraftText.attributed(MinecraftText.fill(visible[index], sample))).padding(.horizontal, 6).frame(minHeight: 18, alignment: .leading)
                }
            }
            .fixedSize()
            .padding(.bottom, 4)
            .background(Color.black.opacity(0.4))
            .shadow(color: .black.opacity(0.4), radius: 0, x: 2, y: 2)
        }
        .padding(18)
        .frame(minHeight: 240)
        .background(Self.scene)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .accessibilityLabel("Scoreboard-Vorschau: " + MinecraftText.plain(title))
    }
    private var visible: [String] { Array(lines.filter { pointsEnabled || !$0.contains("{points}") }.prefix(15)) }
    /// Sky and grass behind the sidebar, like in game.
    static let scene: LinearGradient = {
        let sky = Gradient.Stop(color: Color(red: 0.47, green: 0.65, blue: 1), location: 0)
        let horizon = Gradient.Stop(color: Color(red: 0.66, green: 0.78, blue: 1), location: 0.55)
        let grass = Gradient.Stop(color: Color(red: 0.35, green: 0.56, blue: 0.24), location: 0.55)
        let ground = Gradient.Stop(color: Color(red: 0.29, green: 0.48, blue: 0.19), location: 1)
        return LinearGradient(gradient: Gradient(stops: [sky, horizon, grass, ground]), startPoint: .top, endPoint: .bottom)
    }()
}

struct SuiteScoreboardDesignerView: View {
    let suite: SuiteAPI
    @State private var data: JSON = .null
    @State private var title = ""
    @State private var lines: [String] = []
    @State private var savedTitle = ""
    @State private var savedLines: [String] = []
    @State private var preset = ""
    @State private var gradientText = "Simplex SMP"
    @State private var gradientFrom = Color(red: 0, green: 0.66, blue: 0.91)
    @State private var gradientTo = Color(red: 0.5, green: 0.74, blue: 0.98)
    @State private var busy = false
    @State private var error: String?
    @State private var notice: String?
    @FocusState private var focus: Int?
    private var canEdit: Bool { data["canEdit"].bool }
    private var dirty: Bool { title != savedTitle || lines != savedLines }
    var body: some View {
        List {
            if data == .null { Section { ProgressView("Designer laden …") } }
            else {
                Section {
                    SuiteScoreboardPreview(title: title, lines: lines, sample: data["sample"], pointsEnabled: data["pointsEnabled"].bool).listRowInsets(EdgeInsets(top: 8, leading: 8, bottom: 8, trailing: 8))
                    if dirty { Label("Ungespeicherte Änderungen", systemImage: "pencil.circle.fill").font(.caption).foregroundStyle(.orange) }
                } header: { Text("Live-Vorschau") } footer: { Text(data["enabled"].bool ? "Mit echten Werten vom Server." : "Das Scoreboard ist ausgeschaltet; Änderungen gelten, sobald es wieder an ist.") }
                presetSection
                titleSection
                linesSection
                toolsSection
                if !canEdit { Section { Label("Nur ansehen: Zum Speichern ist „Scoreboard gestalten“ nötig.", systemImage: "lock.fill").foregroundStyle(.secondary) } }
            }
        }
        .panelScreen()
        .navigationTitle("Scoreboard")
        .toolbar { if canEdit { ToolbarItem(placement: .topBarTrailing) { Button { Task { await save() } } label: { if busy { ProgressView() } else { Text("Speichern").bold() } }.disabled(!dirty || busy) } } }
        .task { await load() }
        .errorAlert($error)
        .alert(notice ?? "", isPresented: Binding(get: { notice != nil }, set: { if !$0 { notice = nil } })) { Button("OK") { notice = nil } }
    }
    private var presetSection: some View {
        Section {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 10) {
                    ForEach(data["presets"].array, id: \.["id"].string) { p in
                        SuiteStudioPresetCard(title: p["title"].string, detail: MinecraftText.plain(p["boardTitle"].string), tint: Color.panelAccent, selected: preset == p["id"].string) {
                            preset = p["id"].string; title = p["boardTitle"].string; lines = p["lines"].array.map(\.string)
                        }.disabled(!canEdit)
                    }
                }.padding(.vertical, 4)
            }
        } header: { Text("Vorlagen") } footer: { Text("„Simplex Standard“ ist das bisherige Scoreboard.") }
    }
    private var titleSection: some View {
        Section("Titel") {
            TextField("Titel mit &-Farbcodes", text: $title, axis: .vertical).font(.callout.monospaced()).textInputAutocapitalization(.never).autocorrectionDisabled().disabled(!canEdit).focused($focus, equals: -1)
        }
    }
    private var linesSection: some View {
        Section {
            ForEach(lines.indices, id: \.self) { index in
                TextField("Zeile \(index + 1)", text: Binding(get: { index < lines.count ? lines[index] : "" }, set: { if index < lines.count { lines[index] = String($0.prefix(180)) } }))
                    .font(.callout.monospaced()).textInputAutocapitalization(.never).autocorrectionDisabled().disabled(!canEdit).focused($focus, equals: index)
            }
            .onMove { from, to in lines.move(fromOffsets: from, toOffset: to) }
            .onDelete { offsets in if lines.count - offsets.count >= 1 { lines.remove(atOffsets: offsets) } }
            .deleteDisabled(!canEdit)
            .moveDisabled(!canEdit)
            if canEdit && lines.count < max(1, data["maxLines"].int) { Button("Zeile hinzufügen", systemImage: "plus") { lines.append("&7") } }
        } header: { Text("Zeilen (höchstens \(data["maxLines"].int))") } footer: { Text("Wischen zum Löschen, gedrückt halten zum Verschieben.") }
    }
    private var toolsSection: some View {
        Section {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(data["placeholders"].array, id: \.["key"].string) { p in chip(p["key"].string) { insert(p["key"].string) } }
                }
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(Array("0123456789abcdef"), id: \.self) { c in
                        Button { insert("&" + String(c)) } label: { RoundedRectangle(cornerRadius: 6).fill(MinecraftText.color(MinecraftText.palette[c] ?? 0)).frame(width: 26, height: 26).overlay { RoundedRectangle(cornerRadius: 6).strokeBorder(.white.opacity(0.3)) } }.buttonStyle(.plain).accessibilityLabel("Farbe &" + String(c))
                    }
                    ForEach(["&l", "&o", "&n", "&m", "&r"], id: \.self) { code in chip(code) { insert(code) } }
                }
            }
            HStack {
                TextField("Text", text: $gradientText)
                ColorPicker("Von", selection: $gradientFrom, supportsOpacity: false).labelsHidden()
                ColorPicker("Bis", selection: $gradientTo, supportsOpacity: false).labelsHidden()
                Button("Als Titel") {
                    title = MinecraftText.gradient(gradientText, from: DiscordPreviewModel.hex(DiscordPreviewModel.hexString(gradientFrom)) ?? 0, to: DiscordPreviewModel.hex(DiscordPreviewModel.hexString(gradientTo)) ?? 0, bold: true)
                }.buttonStyle(.borderedProminent)
            }
        } header: { Text("Platzhalter, Farben & Verlauf") } footer: { Text("Tippen fügt in das zuletzt gewählte Feld ein. Verlauf erzeugt einen Hex-Farbverlauf wie beim Standardtitel.") }
        .disabled(!canEdit)
    }
    private func chip(_ text: String, action: @escaping () -> Void) -> some View {
        Button(text, action: action).font(.caption.monospaced()).buttonStyle(.bordered).buttonBorderShape(.capsule)
    }
    private func insert(_ token: String) {
        if let index = focus, index >= 0, index < lines.count { lines[index] += token } else { title += token }
    }
    private func load() async {
        do {
            let result = try await suite.request("studio/scoreboard")
            data = result; title = result["title"].string; lines = result["lines"].array.map(\.string); savedTitle = title; savedLines = lines
        } catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }
    private func save() async {
        busy = true; defer { busy = false }
        do {
            let result = try await suite.post("studio/scoreboard", ["operation": .string("save"), "title": .string(title), "lines": .array(lines.map { .string($0) }), "preset": preset.isEmpty ? .null : .string(preset)])
            data = result; title = result["title"].string; lines = result["lines"].array.map(\.string); savedTitle = title; savedLines = lines; Feedback.success(); notice = result["notice"].string
        } catch { self.error = error.localizedDescription }
    }
}

// MARK: - World trimmer

enum SuiteWorldTrim {
    static func phaseTitle(_ phase: String) -> String {
        switch phase {
        case "IDLE": return "Bereit"
        case "PREPARING": return "Sicherheitsprüfung"
        case "COUNTDOWN": return "Countdown"
        case "RUNNING": return "Trimmt"
        case "AWAITING_OVERRIDE": return "Entscheidung nötig"
        case "RESTARTING": return "Neustart"
        case "RECOVERY": return "Prüfung nötig"
        default: return phase
        }
    }
    static func phaseTint(_ phase: String) -> Color {
        switch phase {
        case "IDLE": return .green
        case "AWAITING_OVERRIDE", "RECOVERY": return .red
        case "COUNTDOWN": return .orange
        default: return .blue
        }
    }
    static func resultTitle(_ status: String) -> String {
        switch status {
        case "SUCCESS": return "Erfolgreich"
        case "FAILED": return "Fehlgeschlagen"
        case "CANCELLED": return "Abgebrochen"
        case "INTERRUPTED": return "Unterbrochen"
        default: return status
        }
    }
    static func countdownTitle(_ seconds: Int) -> String { seconds <= 0 ? "Sofort" : seconds == 60 ? "1 Minute" : "\(seconds / 60) Minuten" }
    /// Free space needed for the optional safety copy: selected size + 10 % + the configured minimum.
    static func snapshotNeed(selected: Double, minimum: Double) -> Double { selected + selected / 10 + minimum }
}

struct SuiteWorldTrimView: View {
    let suite: SuiteAPI
    @State private var data: JSON = .null
    @State private var selection: Set<String> = []
    @State private var threshold = 1200
    @State private var countdown = 180
    @State private var snapshot = false
    @State private var backup = false
    @State private var confirmWord = ""
    @State private var busy = false
    @State private var error: String?
    @State private var messages: [String] = []
    @State private var askOverride = false
    @State private var askRecovery = false
    @State private var prepared = false
    private var phase: String { data["phase"].string }
    private var canRun: Bool { data["canRun"].bool }
    private var selectedBytes: Double { data["targets"].array.filter { selection.contains($0["id"].string) }.reduce(0) { $0 + $1["bytes"].double } }
    private var snapshotPossible: Bool {
        data["freeBytes"].double < 0 || data["freeBytes"].double >= SuiteWorldTrim.snapshotNeed(selected: selectedBytes, minimum: data["minimumFreeBytes"].double)
    }
    var body: some View {
        List {
            if data == .null { Section { ProgressView("Trimmer laden …") } }
            else {
                statusSection
                if !messages.isEmpty { Section("Rückmeldung") { ForEach(messages, id: \.self) { Text($0).font(.footnote) } } }
                if phase == "IDLE" { planSection; startSection }
                historySection
            }
        }
        .panelScreen()
        .navigationTitle("Welten-Trimmer")
        .task { await loop() }
        .refreshable { await load() }
        .errorAlert($error)
        .confirmationDialog("Notfall-Bypass bestätigen?", isPresented: $askOverride, titleVisibility: .visible) {
            Button("Trotzdem fortfahren", role: .destructive) { Task { await act("continue") } }
        } message: { Text("MCA Selector startet trotz geladener Chunks. Nur fortfahren, wenn ein Backup existiert und kein Plugin die Welt gerade schreibt.") }
        .confirmationDialog("Recovery freigeben?", isPresented: $askRecovery, titleVisibility: .visible) {
            Button("Geprüft – freigeben") { Task { await act("recovery") } }
        } message: { Text("Bestätige, dass Backup und Serverstart geprüft wurden.") }
    }
    private var statusSection: some View {
        Section {
            HStack {
                Label(SuiteWorldTrim.phaseTitle(phase), systemImage: "circle.fill").foregroundStyle(SuiteWorldTrim.phaseTint(phase)).font(.headline)
                Spacer()
                Text(data["tool"]["label"].string).font(.caption).foregroundStyle(data["tool"]["ready"].bool ? Color.secondary : Color.orange)
            }
            Text(data["status"].string).font(.subheadline)
            if phase == "RUNNING" {
                ProgressView(value: data["progress"].double, total: 100) { Text(data["target"].string) } currentValueLabel: { Text("\(data["progress"].int) % · Restzeit \(data["eta"].string)") }
            }
            if phase == "COUNTDOWN" { LabeledContent("Start in", value: "\(data["countdown"].int) s") }
            if !data["enabled"].bool { Label("In config.yml deaktiviert", systemImage: "nosign").foregroundStyle(.red) }
            if data["legacyPlugin"].bool { Label("Alter SimplexTrimmer ist noch geladen", systemImage: "exclamationmark.triangle.fill").foregroundStyle(.red) }
            if phase == "AWAITING_OVERRIDE" { Text("\(data["remainingLoadedChunks"].int) Chunks blieben geladen. Es wurden keine Regionsdateien verändert.").font(.footnote).foregroundStyle(.red) }
            if !data["safety"]["last"].string.isEmpty { LabeledContent("Letzte Prüfung", value: data["safety"]["last"].string).font(.caption) }
            actions
        } header: { Text("Status") } footer: { Text("Läuft der Trimmer, sind alle Spieler getrennt; nach Abschluss startet der Server automatisch neu. Diese Ansicht aktualisiert sich selbst.") }
    }
    @ViewBuilder private var actions: some View {
        if canRun && phase == "IDLE" {
            Button("Welten neu erkennen", systemImage: "arrow.clockwise") { Task { await act("scan") } }.disabled(busy || data["scanning"].bool)
            Button(data["tool"]["ready"].bool ? "MCA Selector aktualisieren" : "MCA Selector laden", systemImage: "arrow.down.circle") { Task { await act("download") } }.disabled(busy || data["downloading"].bool)
        }
        if canRun && phase == "COUNTDOWN" { Button("Countdown abbrechen", systemImage: "xmark.circle", role: .destructive) { Task { await act("cancel") } }.disabled(busy) }
        if data["canDecide"].bool {
            Button("Sicher abbrechen", systemImage: "xmark.shield") { Task { await act("abort") } }.disabled(busy)
            Button("Trotzdem fortfahren …", systemImage: "exclamationmark.triangle", role: .destructive) { askOverride = true }.disabled(busy)
        } else if phase == "AWAITING_OVERRIDE" { Text("Nur " + data["actor"].string + " kann entscheiden.").font(.caption).foregroundStyle(.secondary) }
        if canRun && phase == "RECOVERY" { Button("Geprüft – freigeben …", systemImage: "checkmark.shield") { askRecovery = true }.disabled(busy) }
    }
    private var planSection: some View {
        Section {
            ForEach(data["targets"].array, id: \.["id"].string) { target in
                Toggle(isOn: Binding(get: { selection.contains(target["id"].string) }, set: { if $0 { selection.insert(target["id"].string) } else { selection.remove(target["id"].string) } })) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(target["label"].string)
                        Text(target["layout"].string + " · " + bytes(target["bytes"].double)).font(.caption).foregroundStyle(.secondary)
                    }
                }.disabled(!canRun)
            }
            if data["targets"].array.isEmpty { Text("Keine Dimensionen erkannt – „Welten neu erkennen“ antippen.").foregroundStyle(.secondary) }
            Picker("Besuchsgrenze", selection: $threshold) { ForEach(data["thresholds"].array, id: \.["ticks"].int) { row in Text(row["label"].string).tag(row["ticks"].int) } }.disabled(!canRun)
            Picker("Countdown", selection: $countdown) { ForEach(data["countdowns"].array.map(\.int), id: \.self) { Text(SuiteWorldTrim.countdownTitle($0)).tag($0) } }.disabled(!canRun)
            Toggle(isOn: $snapshot) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Sicherheitskopie vor dem Trimmen")
                    Text(snapshotPossible ? "Kopiert ≈ \(bytes(selectedBytes)) und stellt bei Fehler automatisch wieder her. Frei: \(bytes(data["freeBytes"].double))" : "Zu wenig Speicher für die Kopie.").font(.caption).foregroundStyle(.secondary)
                }
            }.disabled(!canRun || !snapshotPossible)
        } header: { Text("Lauf planen · \(selection.count) gewählt · \(bytes(selectedBytes))") } footer: { Text("Gelöscht werden Chunks, in denen sich Spieler insgesamt kürzer als die Besuchsgrenze aufgehalten haben.") }
    }
    private var startSection: some View {
        Section {
            if canRun {
                Toggle("Ich habe ein vollständiges, aktuelles Backup", isOn: $backup)
                TextField("Zur Sicherheit TRIMMEN eintippen", text: $confirmWord).textInputAutocapitalization(.characters).autocorrectionDisabled()
                Button(role: .destructive) { Task { await start() } } label: { Label("Trimmen starten", systemImage: "scissors") }
                    .disabled(busy || !backup || confirmWord.trimmingCharacters(in: .whitespaces).uppercased() != "TRIMMEN" || selection.isEmpty || !data["enabled"].bool)
            } else { Label("Zum Starten ist „Welten-Trimmer ausführen“ nötig.", systemImage: "lock.fill").foregroundStyle(.secondary) }
        } footer: { Text("Die Suite prüft Werkzeug, freien Speicher und geladene Chunks selbst und bricht bei Problemen sicher ab.") }
    }
    private var historySection: some View {
        Section {
            if data["history"].array.isEmpty { Text("Noch keine Läufe").foregroundStyle(.secondary) }
            ForEach(data["history"].array, id: \.["id"].string) { row in
                VStack(alignment: .leading, spacing: 3) {
                    HStack {
                        Text(SuiteWorldTrim.resultTitle(row["status"].string)).font(.subheadline.weight(.semibold)).foregroundStyle(row["status"].string == "SUCCESS" ? Color.green : Color.orange)
                        Spacer()
                        if row["saved"].double > 0 { Text("−" + bytes(row["saved"].double)).font(.caption.monospacedDigit()) }
                    }
                    Text(row["targets"].array.map(\.string).joined(separator: ", ")).font(.caption)
                    Text(SuiteDiagnostics.ago(row["finishedAt"].double) + " · " + row["actor"].string).font(.caption2).foregroundStyle(.secondary)
                    if !row["error"].string.isEmpty { Text(row["error"].string).font(.caption2).foregroundStyle(.red) }
                }
            }
        } header: { Text("Verlauf · gesamt freigegeben " + bytes(data["totalSaved"].double)) }
    }
    private func loop() async {
        await load()
        while !Task.isCancelled {
            let busyPhase = phase != "IDLE" || data["scanning"].bool || data["downloading"].bool
            try? await Task.sleep(for: .seconds(busyPhase ? 4 : 30))
            if Task.isCancelled { break }
            await load()
        }
    }
    private func load() async {
        do { apply(try await suite.request("world-trim")) }
        catch { if !RequestFailure.isCancellation(error) && phase != "RUNNING" && phase != "RESTARTING" { self.error = error.localizedDescription } }
    }
    private func apply(_ result: JSON) {
        data = result
        if !prepared {
            prepared = true
            selection = Set(result["selection"].array.map(\.string))
            threshold = result["threshold"].int
            countdown = result["countdownChoice"].int
            snapshot = result["snapshotDefault"].bool
        }
    }
    private func act(_ operation: String) async {
        await send(["operation": .string(operation)])
    }
    private func start() async {
        await send(["operation": .string("start"), "targets": .array(selection.sorted().map { .string($0) }), "threshold": .number(Double(threshold)), "countdown": .number(Double(countdown)),
                    "snapshot": .bool(snapshot && snapshotPossible), "backupConfirmed": .bool(backup), "confirm": .string(confirmWord)])
        backup = false; confirmWord = ""
    }
    private func send(_ body: [String: JSON]) async {
        busy = true; defer { busy = false }
        do { let result = try await suite.post("world-trim", body); apply(result); messages = result["messages"].array.map(\.string); Feedback.success() }
        catch { self.error = error.localizedDescription }
    }
}

// MARK: - Player dossier

struct SuitePlayerDossierView: View {
    let suite: SuiteAPI
    let player: String
    @State private var data: JSON = .null
    @State private var search = ""
    @State private var error: String?
    var body: some View {
        List {
            if data == .null { Section { ProgressView("Steckbrief laden …") } }
            if search.isEmpty && data != .null && suite.features.contains("visual-inventory-v1") {
                Section("Spielerwerkzeuge") {
                    if data["online"].bool && suite.can("data.inventory") {
                        NavigationLink { SuiteVisualInventoryView(suite: suite, player: data["player"].string) } label: {
                            Label("Live-Inventar & Endertruhe", systemImage: "square.grid.3x3.fill")
                        }
                    }
                    if suite.can("data.devices") {
                        NavigationLink { SuiteDataListView(suite: suite, kind: "devices", player: player, actions: []) } label: {
                            Label("Geräteverlauf", systemImage: "desktopcomputer.and.iphone")
                        }
                    }
                    if suite.can("data.access-locks") {
                        NavigationLink { SuiteAccessLockPlayerView(suite: suite, player: player) } label: {
                            Label("Rechte & Sperren", systemImage: "lock.shield")
                        }
                    }
                    if suite.can("data.commands") {
                        NavigationLink { SuiteCommandPlayerView(suite: suite, player: player) } label: {
                            Label("Befehle", systemImage: "terminal")
                        }
                    }
                }
            }
            if search.isEmpty { SuiteSavedNBTSection(suite: suite, player: player) }
            ForEach(data["sections"].array, id: \.["id"].string) { section in SuiteDossierSection(section: section, search: search) }
        }
        .panelScreen()
        .navigationTitle(data["name"].string.isEmpty ? "Steckbrief" : data["name"].string)
        .searchable(text: $search, prompt: "Im Steckbrief suchen")
        .task { await load() }
        .refreshable { await load() }
        .errorAlert($error)
    }
    private func load() async {
        do { data = try await suite.request("player/dossier", query: ["player": player]) }
        catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }
}

struct SuiteDossierSection: View {
    let section: JSON
    let search: String
    private var rows: [JSON] { section["rows"].array.filter { search.isEmpty || ($0["label"].string + " " + $0["value"].string).localizedCaseInsensitiveContains(search) } }
    private var list: [String] { section["list"].array.map(\.string).filter { search.isEmpty || $0.localizedCaseInsensitiveContains(search) } }
    var body: some View {
        if !rows.isEmpty || !list.isEmpty {
            Section {
                ForEach(rows.indices, id: \.self) { index in SuiteDossierRow(row: rows[index]) }
                if !list.isEmpty {
                    DisclosureGroup("\(list.count) Einträge") {
                        ForEach(list.indices, id: \.self) { index in Text(list[index]).font(.caption).textSelection(.enabled) }
                    }
                }
            } header: { Label(section["title"].string, systemImage: section["symbol"].string.isEmpty ? "info.circle" : section["symbol"].string) }
        }
    }
}

struct SuiteDossierRow: View {
    let row: JSON
    private var tint: Color {
        switch row["tone"].string {
        case "warn": return .orange
        case "danger": return .red
        case "ok": return .green
        case "info": return .blue
        default: return .primary
        }
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(row["label"].string).font(.caption.weight(.medium)).foregroundStyle(.secondary)
            Text(row["value"].string).font(row["mono"].bool ? .callout.monospaced() : .callout).foregroundStyle(tint).textSelection(.enabled)
        }
        .contextMenu { Button("Kopieren", systemImage: "doc.on.doc") { Clipboard.copy(row["value"].string) } }
    }
}
