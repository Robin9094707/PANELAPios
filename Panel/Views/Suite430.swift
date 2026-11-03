import SwiftUI

// MARK: - Minecraft colour text (LuckPerms prefixes and suffixes)

/// Renders legacy colour codes (&a, &l, &#RRGGBB, &x&…), {#RRGGBB} and simple MiniMessage tags like the Minecraft chat (approximation).
enum SuiteMinecraftText {
    struct Run: Equatable {
        var text: String
        var color: String?
        var bold = false
        var italic = false
        var underline = false
        var strike = false
    }
    static let palette: [(code: String, hex: String, name: String)] = [
        ("0", "000000", "Schwarz"), ("1", "0000AA", "Dunkelblau"), ("2", "00AA00", "Dunkelgrün"), ("3", "00AAAA", "Türkis"),
        ("4", "AA0000", "Dunkelrot"), ("5", "AA00AA", "Lila"), ("6", "FFAA00", "Gold"), ("7", "AAAAAA", "Grau"),
        ("8", "555555", "Dunkelgrau"), ("9", "5555FF", "Blau"), ("a", "55FF55", "Grün"), ("b", "55FFFF", "Hellblau"),
        ("c", "FF5555", "Rot"), ("d", "FF55FF", "Pink"), ("e", "FFFF55", "Gelb"), ("f", "FFFFFF", "Weiß")
    ]
    static let named: [String: String] = ["black": "0", "dark_blue": "1", "dark_green": "2", "dark_aqua": "3", "dark_red": "4", "dark_purple": "5", "gold": "6", "gray": "7", "grey": "7",
                                          "dark_gray": "8", "dark_grey": "8", "blue": "9", "green": "a", "aqua": "b", "red": "c", "light_purple": "d", "yellow": "e", "white": "f"]
    static func hex(for code: String) -> String? { palette.first { $0.code == code }?.hex }

    /// Splits the text into styled runs; unknown tags stay visible as text.
    static func runs(_ source: String) -> [Run] {
        var out: [Run] = []
        var current = Run(text: "")
        let chars = Array(source)
        var i = 0
        func flush() { if !current.text.isEmpty { out.append(current) }; current.text = "" }
        func style(_ change: (inout Run) -> Void) { flush(); change(&current) }
        while i < chars.count {
            let c = chars[i]
            if (c == "&" || c == "§"), i + 1 < chars.count {
                let next = chars[i + 1].lowercased()
                if next == "#", i + 7 < chars.count, let hex = hexString(chars[(i + 2)...(i + 7)]) { style { $0 = Run(text: "", color: hex) }; i += 8; continue }
                if next == "x", i + 13 < chars.count {
                    let digits = stride(from: i + 3, through: i + 13, by: 2).map { chars[$0] }
                    let markers = stride(from: i + 2, through: i + 12, by: 2).allSatisfy { chars[$0] == "&" || chars[$0] == "§" }
                    if markers, let hex = hexString(digits[...]) { style { $0 = Run(text: "", color: hex) }; i += 14; continue }
                }
                if let hex = hex(for: next) { style { $0 = Run(text: "", color: hex) }; i += 2; continue }
                switch next {
                case "l": style { $0.bold = true }; i += 2; continue
                case "o": style { $0.italic = true }; i += 2; continue
                case "n": style { $0.underline = true }; i += 2; continue
                case "m": style { $0.strike = true }; i += 2; continue
                case "k": i += 2; continue
                case "r": style { $0 = Run(text: "") }; i += 2; continue
                default: break
                }
            }
            if c == "{", i + 8 < chars.count, chars[i + 1] == "#", chars[i + 8] == "}", let hex = hexString(chars[(i + 2)...(i + 7)]) { style { $0.color = hex }; i += 9; continue }
            if c == "<", let end = chars[i...].firstIndex(of: ">"), end - i <= 40 {
                let raw = String(chars[(i + 1)..<end]).lowercased()
                let closing = raw.hasPrefix("/")
                let tag = String((closing ? String(raw.dropFirst()) : raw).split(separator: ":").first ?? "")
                var handled = true
                if tag.hasPrefix("#"), let hex = hexString(Array(tag.dropFirst())[...]) { style { $0.color = closing ? nil : hex } }
                else if let code = named[tag], let hex = hex(for: code) { style { $0.color = closing ? nil : hex } }
                else if tag == "bold" || tag == "b" { style { $0.bold = !closing } }
                else if tag == "italic" || tag == "i" || tag == "em" { style { $0.italic = !closing } }
                else if tag == "underlined" || tag == "u" { style { $0.underline = !closing } }
                else if tag == "strikethrough" || tag == "st" { style { $0.strike = !closing } }
                else if tag == "reset" { style { $0 = Run(text: "") } }
                else if tag == "gradient" || tag == "rainbow" { let first = closing ? nil : firstStop(raw); style { $0.color = closing ? nil : (first ?? "FF55FF") } }
                else { handled = false }
                if handled { i = end + 1; continue }
            }
            current.text.append(c)
            i += 1
        }
        flush()
        return out
    }
    /// First colour of a MiniMessage gradient (gradients are previewed in their first colour).
    private static func firstStop(_ raw: String) -> String? {
        for part in raw.split(separator: ":").dropFirst() {
            let value = String(part)
            if value.hasPrefix("#"), let hex = hexString(Array(value.dropFirst())[...]) { return hex }
            if let code = named[value], let hex = hex(for: code) { return hex }
        }
        return nil
    }
    private static func hexString(_ slice: ArraySlice<Character>) -> String? {
        let text = String(slice)
        guard text.count == 6, text.allSatisfy(\.isHexDigit) else { return nil }
        return text.uppercased()
    }
    static func color(_ hex: String?) -> Color {
        guard let hex, let value = UInt32(hex, radix: 16) else { return .white }
        return Color(red: Double((value >> 16) & 0xFF) / 255, green: Double((value >> 8) & 0xFF) / 255, blue: Double(value & 0xFF) / 255)
    }
    static func plainPart(_ text: String, _ color: Color) -> AttributedString { var part = AttributedString(text); part.foregroundColor = color; return part }
    static func attributed(_ source: String) -> AttributedString {
        var result = AttributedString()
        for run in runs(source) {
            var part = AttributedString(run.text)
            part.foregroundColor = color(run.color)
            if run.bold { part.inlinePresentationIntent = .stronglyEmphasized }
            if run.italic { part.inlinePresentationIntent = (part.inlinePresentationIntent ?? []).union(.emphasized) }
            if run.underline { part.underlineStyle = .single }
            if run.strike { part.strikethroughStyle = .single }
            result.append(part)
        }
        return result
    }
}

/// Chat-like preview: prefix + name + suffix on a dark background.
struct SuiteMinecraftPreview: View {
    let prefix: String
    let name: String
    let suffix: String
    var message = ""
    var line: AttributedString {
        var text = SuiteMinecraftText.attributed(prefix)
        text.append(SuiteMinecraftText.plainPart(name, .white))
        text.append(SuiteMinecraftText.attributed(suffix))
        text.append(SuiteMinecraftText.plainPart(message, Color(white: 0.75)))
        return text
    }
    var body: some View {
        Text(line)
            .font(.system(.footnote, design: .monospaced))
            .padding(.horizontal, 10).padding(.vertical, 7)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(red: 0.11, green: 0.11, blue: 0.12), in: RoundedRectangle(cornerRadius: 8))
    }
}

/// Prefix/suffix editor with colour palette, formats, hex colour, templates and live preview.
struct SuiteAffixEditor: View {
    let title: String
    let kind: String
    let name: String
    let other: String
    let save: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var value: String
    @State private var custom = Color.cyan
    init(title: String, kind: String = "prefix", value: String, other: String = "", name: String = "Steve", save: @escaping (String) -> Void) {
        self.title = title; self.kind = kind; self.other = other; self.name = name; self.save = save; _value = State(initialValue: value)
    }
    static let templates = ["&c&l[Admin] &r", "&9[Mod] &r", "&a[Builder] &r", "&6&l✦ VIP &r", "&8[&bSupport&8] &r", "&7[Spieler] "]
    static func valid(_ text: String) -> Bool { text.count <= 64 && !text.contains("\"") && !text.contains("\\") }
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField(kind == "suffix" ? "z. B.  &7✦" : "z. B. &c&l[Admin] &r", text: $value).textInputAutocapitalization(.never).autocorrectionDisabled().font(.body.monospaced())
                    SuiteMinecraftPreview(prefix: kind == "suffix" ? other : value, name: name, suffix: kind == "suffix" ? value : other, message: ": Hallo!")
                } header: { Text("Text & Vorschau") } footer: { Text(Self.valid(value) ? "\(value.count) / 64 Zeichen · leer = entfernen" : "Höchstens 64 Zeichen, ohne Anführungszeichen und Backslash.") }
                Section("Farben") { paletteGrid }
                Section("Format") { formatRow; customRow }
                Section("Vorlagen") {
                    ForEach(Self.templates, id: \.self) { template in
                        Button { value = template } label: { SuiteMinecraftPreview(prefix: template, name: "", suffix: "") }.buttonStyle(.plain)
                    }
                }
            }
            .panelScreen().navigationTitle(title).navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Weiter") { save(value); dismiss() }.disabled(!Self.valid(value)) }
            }
        }
    }
    private var paletteGrid: some View {
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 6), count: 8), spacing: 6) {
            ForEach(SuiteMinecraftText.palette, id: \.code) { entry in
                Button { value += "&" + entry.code } label: {
                    RoundedRectangle(cornerRadius: 6).fill(SuiteMinecraftText.color(entry.hex)).frame(height: 30)
                        .overlay { RoundedRectangle(cornerRadius: 6).strokeBorder(.white.opacity(0.25)) }
                }.buttonStyle(.plain).accessibilityLabel(entry.name)
            }
        }.padding(.vertical, 4)
    }
    private var formatRow: some View {
        HStack {
            formatButton("B", "&l"); formatButton("I", "&o"); formatButton("U", "&n"); formatButton("S", "&m"); formatButton("↺", "&r")
            Button("␣") { value += " " }.buttonStyle(.bordered)
        }
    }
    private func formatButton(_ label: String, _ code: String) -> some View { Button(label) { value += code }.buttonStyle(.bordered).font(.body.bold()) }
    private var customRow: some View {
        HStack {
            ColorPicker("Eigene Farbe", selection: $custom, supportsOpacity: false)
            Button("Einfügen") { value += "&#" + Self.hex(custom) }.buttonStyle(.bordered)
        }
    }
    static func hex(_ color: Color) -> String {
        let ui = UIColor(color)
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        ui.getRed(&r, green: &g, blue: &b, alpha: &a)
        return String(format: "%02X%02X%02X", Int(max(0, min(1, r)) * 255), Int(max(0, min(1, g)) * 255), Int(max(0, min(1, b)) * 255))
    }
}

// MARK: - LuckPerms permission autocomplete

/// Filters the server's known permissions (plugins, LuckPerms, groups) for the typed text.
enum SuitePermissionSearch {
    static func matches(_ items: [JSON], query: String, limit: Int = 8) -> [JSON] {
        let q = query.trimmingCharacters(in: .whitespaces).lowercased()
        guard !q.isEmpty else { return [] }
        var starts: [JSON] = [], contains: [JSON] = []
        for item in items {
            let node = item["node"].string.lowercased()
            if node == q { continue }
            if node.hasPrefix(q) { starts.append(item) } else if node.contains(q) || item["description"].string.lowercased().contains(q) { contains.append(item) }
            if starts.count >= limit { break }
        }
        return Array((starts + contains).prefix(limit))
    }
}

/// Permission field with suggestions; used by the group and player LuckPerms screens.
struct SuitePermissionSuggestForm: View {
    let disabled: Bool
    let known: [JSON]
    let submit: (String, Bool, String, String) -> Void
    @State private var node = ""
    @State private var deny = false
    @State private var duration = ""
    @State private var context = ""
    var body: some View {
        TextField("Recht suchen, z. B. essentials.fly", text: $node).textInputAutocapitalization(.never).autocorrectionDisabled()
        ForEach(SuitePermissionSearch.matches(known, query: node), id: \.["node"].string) { item in
            Button { node = item["node"].string } label: {
                VStack(alignment: .leading, spacing: 2) {
                    Text(item["node"].string).font(.footnote.monospaced()).foregroundStyle(.primary)
                    Text([item["plugin"].string, item["description"].string].filter { !$0.isEmpty }.joined(separator: " · ")).font(.caption2).foregroundStyle(.secondary).lineLimit(2)
                }
            }
        }
        TextField("Dauer (leer = dauerhaft, z. B. 7d)", text: $duration).textInputAutocapitalization(.never).autocorrectionDisabled()
        TextField("Kontext (optional, world=farmwelt)", text: $context).textInputAutocapitalization(.never).autocorrectionDisabled()
        Toggle("Verweigern (false)", isOn: $deny)
        Button("Recht setzen", systemImage: "plus") { submit(node.trimmingCharacters(in: .whitespaces), deny, duration.trimmingCharacters(in: .whitespaces), context.trimmingCharacters(in: .whitespaces)); node = ""; duration = ""; context = ""; deny = false }
            .disabled(disabled || node.trimmingCharacters(in: .whitespaces).isEmpty)
    }
}

// MARK: - Rechte-Sperren

enum SuiteAccessLockText {
    static func scope(_ id: String) -> String {
        switch id {
        case "everywhere": return "Überall gesperrt"
        case "own": return "Nur im eigenen Land erlaubt"
        case "own-wild": return "Eigenes Land & Wildnis erlaubt"
        default: return "Empfohlen je Bereich"
        }
    }
    static func duration(_ value: String) -> String {
        if value.isEmpty { return "Dauerhaft" }
        let number = String(value.dropLast()), unit = value.last ?? "d"
        switch unit {
        case "m": return number + " Minuten"
        case "h": return number + (number == "1" ? " Stunde" : " Stunden")
        case "w": return number + (number == "1" ? " Woche" : " Wochen")
        default: return number + (number == "1" ? " Tag" : " Tage")
        }
    }
    static func symbol(_ flag: String) -> String {
        switch flag {
        case "chest": return "shippingbox.fill"
        case "shulker": return "cube.box.fill"
        case "container": return "archivebox.fill"
        case "enderchest": return "sparkles.rectangle.stack.fill"
        case "doors": return "door.left.hand.closed"
        case "redstone": return "switch.2"
        case "utility": return "hammer.fill"
        case "decor": return "photo.artframe"
        case "animals": return "pawprint.fill"
        case "build": return "square.stack.3d.up.fill"
        case "pickup": return "diamond.fill"
        default: return "hand.raised.fill"
        }
    }
    static func tint(_ scope: String) -> Color { scope == "everywhere" ? .red : scope == "own" ? .orange : .yellow }
}

struct SuiteAccessLocksView: View {
    let suite: SuiteAPI
    @State private var data: JSON = .null
    @State private var query = ""
    @State private var matches: [JSON] = []
    @State private var searching = false
    @State private var error: String?
    var body: some View {
        List {
            Section {
                TextField("Spieler suchen – Name eintippen", text: $query).textInputAutocapitalization(.never).autocorrectionDisabled().accessibilityIdentifier("rights-search")
                if searching { ProgressView() }
                ForEach(matches, id: \.["id"].string) { row in
                    NavigationLink { SuiteAccessLockPlayerView(suite: suite, player: row["id"].string) } label: { SuitePlayerMatchRow(row: row) }
                }
                if !query.trimmingCharacters(in: .whitespaces).isEmpty && !searching && matches.isEmpty {
                    NavigationLink { SuiteAccessLockPlayerView(suite: suite, player: query.trimmingCharacters(in: .whitespaces)) } label: { Label("„" + query.trimmingCharacters(in: .whitespaces) + "“ direkt öffnen", systemImage: "person.crop.circle.badge.questionmark") }
                }
            } header: { Text("Spieler auswählen") } footer: { Text("Beim Tippen erscheinen passende Spieler – online und offline, wie in der Spielerliste.") }
            lockedSection
            SuiteAccessLockHelp(data: data)
        }
        .panelScreen().navigationTitle("Rechte & Sperren").task { await load() }.refreshable { await load() }.errorAlert($error)
        .task(id: query) { await search() }
    }
    @ViewBuilder private var lockedSection: some View {
        Section("Spieler mit Sperren") {
            if data == .null { ProgressView() }
            else if data["items"].array.isEmpty { Text("Niemand ist gesperrt.").foregroundStyle(.secondary) }
            ForEach(data["items"].array, id: \.["uuid"].string) { row in
                NavigationLink { SuiteAccessLockPlayerView(suite: suite, player: row["uuid"].string) } label: { SuiteAccessLockRow(row: row) }
            }
        }
    }
    private func search() async {
        let text = query.trimmingCharacters(in: .whitespaces)
        guard !text.isEmpty else { matches = []; return }
        do {
            try await Task.sleep(for: .milliseconds(250))
            searching = true; defer { searching = false }
            let result = try await suite.data("players", query: ["q": text, "filter": "all", "limit": "8"])
            try Task.checkCancellation()
            matches = Array(result["items"].array.prefix(8))
        } catch { if !RequestFailure.isCancellation(error) && !(error is CancellationError) { self.error = error.localizedDescription } }
    }
    func load() async { do { data = try await suite.data("access-locks") } catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } } }
}

struct SuitePlayerMatchRow: View {
    let row: JSON
    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: row["online"].bool ? "person.fill.checkmark" : "person.fill").foregroundStyle(row["online"].bool ? Color.green : Color.secondary).frame(width: 24)
            VStack(alignment: .leading, spacing: 1) {
                Text(row["name"].string).font(.subheadline.weight(.semibold))
                Text(row["online"].bool ? "Online" : "Offline").font(.caption).foregroundStyle(.secondary)
            }
        }
    }
}

struct SuiteAccessLockRow: View {
    let row: JSON
    static func summary(_ row: JSON) -> String {
        var parts = row["locks"].array.map { $0["title"].string + " (" + $0["remaining"].string + ")" }
        parts += row["rights"].array.filter { $0["blocked"].bool }.map { $0["name"].string + " (" + $0["remaining"].string + ")" }
        for list in row["legacy"].object.values { parts += list.array.map { $0["name"].string + " (" + $0["remaining"].string + ")" } }
        return parts.joined(separator: " · ")
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(row["name"].string).font(.subheadline.weight(.semibold))
                if row["online"].bool { Circle().fill(.green).frame(width: 7, height: 7) }
            }
            Text(Self.summary(row)).font(.caption).foregroundStyle(.secondary).lineLimit(3)
        }
    }
}

struct SuiteAccessLockHelp: View {
    let data: JSON
    var body: some View {
        Section {
            ForEach(data["scopes"].array, id: \.["id"].string) { scope in
                VStack(alignment: .leading, spacing: 2) {
                    Label(scope["title"].string, systemImage: "circle.fill").font(.subheadline.weight(.semibold)).foregroundStyle(SuiteAccessLockText.tint(scope["id"].string))
                    Text(scope["explain"].string).font(.caption).foregroundStyle(.secondary)
                }
            }
        } header: { Text("So funktioniert es") } footer: {
            Text(data["help"].string + (data != .null && !data["lands"].bool ? " Lands ist nicht installiert – alles zählt als Wildnis." : ""))
        }
    }
}

struct SuiteAccessLockPlayerView: View {
    let suite: SuiteAPI
    let player: String
    @StateObject private var runner: SuiteConfirmRunner
    @State private var data: JSON = .null
    @State private var picked: Set<String> = []
    @State private var scope = "default"
    @State private var duration = "1d"
    @State private var presetDuration = "7d"
    @State private var reason = ""
    @State private var rank: JSON = .null
    @State private var groups: [JSON] = []
    init(suite: SuiteAPI, player: String) { self.suite = suite; self.player = player; _runner = StateObject(wrappedValue: SuiteConfirmRunner(suite)) }
    var canEdit: Bool { suite.can("action.locks.update") && suite.scope != "read" }
    var user: JSON { data["player"] }
    var who: String { user["name"].string.isEmpty ? player : user["name"].string }
    var durations: [String] { data["durations"].array.isEmpty ? ["30m", "2h", "12h", "1d", "3d", "7d", "14d", "30d", ""] : data["durations"].array.map(\.string) }
    var body: some View {
        List {
            headerSection
            rankSection
            altSection
            flagSection
            settingsSection
            rightsSections
            presetSection
            SuiteAccessLockHelp(data: data)
        }
        .panelScreen().navigationTitle(who).navigationBarTitleDisplayMode(.inline).task { await load() }.refreshable { await load() }
        .suiteConfirm(runner) { picked = []; await load() }
    }
    var inheritOn: Bool { user["inherit"] == .null || user["inherit"].bool }
    private func setInherit(_ on: Bool) {
        let values: [String: JSON] = ["operation": .string("inherit"), "player": .string(user["uuid"].string), "node": .string(""), "value": .string(String(on))]
        let title = on ? "Alt-Vererbung einschalten?" : "Alt-Vererbung ausschalten?"
        Task { await runner.ask("management.apply", values, title: title) }
    }
    var activeCount: Int { user["locks"].array.count + user["blockedRights"].int }
    private var headerSection: some View {
        Section {
            LabeledContent("Status", value: user["online"].bool ? "Online" : "Offline")
            LabeledContent("Aktive Sperren", value: String(activeCount))
            if suite.features.contains("luckperms-v1") { NavigationLink { SuiteLuckPermsPlayerView(suite: suite, player: user["uuid"].string.isEmpty ? player : user["uuid"].string) } label: { Label("Rang & LuckPerms-Rechte", systemImage: "crown.fill") }.disabled(!suite.can("data.luckperms")) }
            if user["profile"].bool && suite.scope == "full" && suite.can("action.management.apply") {
                Toggle("Sperren auf Alt-Accounts übertragen", isOn: Binding(get: { inheritOn }, set: { setInherit($0) }))
            }
            if activeCount > 0 { Button("Alle Sperren aufheben", systemImage: "lock.open.fill", role: .destructive) { ask(["operation": .string("clear")], "Alle Sperren von " + who + " aufheben – Bereiche und Einzelrechte?") }.disabled(!canEdit) }
        } header: { Text(who) } footer: { Text("Alle Rechte dieses Spielers an einem Ort: Bereiche mit Land-Option und alle weiteren Einzelrechte – jeweils mit Dauer.") }
    }
    /** Remaining classic rights grouped (chat, combat, worlds …); each can be blocked for a time or permanently. */
    @ViewBuilder private var rightsSections: some View {
        if data != .null && !user["profile"].bool {
            Section("Weitere Rechte") { Text("Einzelrechte gibt es, sobald der Spieler einmal online war. Bereiche funktionieren schon jetzt.").font(.caption).foregroundStyle(.secondary) }
        }
        ForEach(SuiteRightsGrouping.groups(user["rights"].array), id: \.0) { group in
            Section(group.0) {
                ForEach(group.1, id: \.["id"].string) { right in
                    SuiteClassicRightRow(right: right, durations: durations, canEdit: canEdit, block: { time in
                        ask(["operation": .string("right-block"), "right": right["id"], "duration": .string(time)], right["name"].string + " für " + who + " sperren?")
                    }, allow: {
                        ask(["operation": .string("right-allow"), "right": right["id"]], right["name"].string + " für " + who + " wieder erlauben?")
                    })
                }
            }
        }
    }
    private func active(_ flag: String) -> JSON { user["locks"].array.first { $0["flag"].string == flag } ?? .null }
    private var flagSection: some View {
        Section {
            ForEach(data["flags"].array, id: \.["id"].string) { flag in
                SuiteAccessLockFlagRow(flag: flag, active: active(flag["id"].string), legacy: user["legacy"][flag["id"].string].array, inherited: inherited(flag["id"].string), picked: Binding(get: { picked.contains(flag["id"].string) }, set: { if $0 { picked.insert(flag["id"].string) } else { picked.remove(flag["id"].string) } }), canEdit: canEdit) {
                    ask(["operation": .string("remove"), "flags": flag["id"]], flag["title"].string + " für " + who + " wieder erlauben?")
                }
            }
        } header: { Text("Bereiche mit Land-Option") } footer: { Text("Haken setzen, darunter Gültigkeit und Dauer wählen. Gesperrtes kann weder benutzt, abgebaut noch gesprengt werden – auch nicht in Ländern, in denen der Spieler vertraut ist. Wischen zum Aufheben.") }
    }
    private var settingsSection: some View {
        Section {
            Picker("Gilt", selection: $scope) {
                Text("Empfohlen je Bereich").tag("default")
                ForEach(data["scopes"].array, id: \.["id"].string) { Text($0["title"].string).tag($0["id"].string) }
            }
            Picker("Dauer", selection: $duration) { ForEach(durations, id: \.self) { Text(SuiteAccessLockText.duration($0)).tag($0) } }
            TextField("Grund – sieht der Spieler (optional)", text: $reason)
            Button("Ausgewählte sperren (\(picked.count))", systemImage: "lock.fill") {
                ask(["operation": .string("set"), "flags": .string(picked.sorted().joined(separator: ",")), "scope": .string(scope), "duration": .string(duration), "reason": .string(reason.trimmingCharacters(in: .whitespaces))], "Rechte von " + who + " sperren?")
            }.disabled(!canEdit || picked.isEmpty || runner.busy)
        } header: { Text("Gültigkeit & Dauer") } footer: { Text(scope == "default" ? "Jeder Bereich nutzt seine empfohlene Einstellung, z. B. Truhen: eigenes Land & Wildnis erlaubt." : data["scopes"].array.first { $0["id"].string == scope }?["explain"].string ?? "") }
    }
    private var presetSection: some View {
        Section {
            Picker("Dauer", selection: $presetDuration) { ForEach(durations, id: \.self) { Text(SuiteAccessLockText.duration($0)).tag($0) } }
            ForEach(data["presets"].array, id: \.["id"].string) { preset in
                Button { ask(["operation": .string("preset"), "preset": preset["id"], "duration": .string(presetDuration), "reason": .string(reason.trimmingCharacters(in: .whitespaces))], "Vorlage „" + preset["title"].string + "“ für " + who + "?") } label: {
                    VStack(alignment: .leading, spacing: 2) { Text(preset["title"].string).font(.subheadline.weight(.semibold)); Text(preset["explain"].string).font(.caption).foregroundStyle(.secondary) }
                }.disabled(!canEdit)
            }
        } header: { Text("Vorlagen") }
    }
    private func ask(_ values: [String: JSON], _ title: String) {
        var payload = values; payload["player"] = .string(user["uuid"].string.isEmpty ? player : user["uuid"].string)
        Task { await runner.ask("locks.update", payload, title: title) }
    }
    func load() async {
        do { data = try await suite.data("access-locks", query: ["player": player]) }
        catch { if !RequestFailure.isCancellation(error) { runner.error = error.localizedDescription } }
        if showsRank {
            let id = user["uuid"].string.isEmpty ? player : user["uuid"].string
            rank = (try? await suite.data("luckperms", query: ["player": id])) ?? .null
            if groups.isEmpty { groups = (try? await suite.data("luckperms"))?["groups"].array ?? [] }
        }
    }
    // MARK: rank (LuckPerms) and alt accounts inside the same view
    var showsRank: Bool { suite.features.contains("luckperms-v1") && suite.can("data.luckperms") }
    var canEditRank: Bool { suite.scope == "full" && suite.can("action.luckperms.edit") }
    private func askRank(_ values: [String: JSON], _ title: String) {
        var payload = values; payload["player"] = .string(user["uuid"].string.isEmpty ? player : user["uuid"].string)
        Task { await runner.ask("luckperms.edit", payload, title: title) }
    }
    @ViewBuilder private var rankSection: some View {
        if showsRank {
            Section {
                if !rank["prefix"].string.isEmpty || !rank["suffix"].string.isEmpty { SuiteMinecraftPreview(prefix: rank["prefix"].string, name: who, suffix: rank["suffix"].string) }
                ForEach(rank["groups"].array, id: \.["key"].string) { group in
                    SuiteRankGroupRow(group: group, primary: rank["primary"].string)
                        .swipeActions { Button("Entfernen", role: .destructive) { ungroup(group) }.disabled(!canEditRank) }
                }
                Menu {
                    ForEach(availableGroups, id: \.["name"].string) { group in
                        Menu(group["name"].string) {
                            ForEach(["", "1d", "7d", "30d"], id: \.self) { time in
                                Button(SuiteAccessLockText.duration(time)) { grant(group, time) }
                            }
                        }
                    }
                } label: { Label("Gruppe vergeben …", systemImage: "plus.circle") }.disabled(!canEditRank || groups.isEmpty)
                NavigationLink { SuiteLuckPermsPlayerView(suite: suite, player: user["uuid"].string.isEmpty ? player : user["uuid"].string) } label: { Label("Präfix, Befördern & eigene LuckPerms-Rechte", systemImage: "crown.fill") }
            } header: { Text("Rang & Gruppen") } footer: { Text("Gruppen nach links wischen zum Entfernen. Änderungen übernimmt LuckPerms selbst.") }
        }
    }
    @ViewBuilder private var altSection: some View {
        if user["alts"] != .null {
            Section {
                if user["alts"].array.isEmpty { Text("Keine bestätigten Alt-Accounts.").foregroundStyle(.secondary) }
                ForEach(user["alts"].array, id: \.["uuid"].string) { alt in
                    NavigationLink { SuiteAccessLockPlayerView(suite: suite, player: alt["uuid"].string) } label: {
                        SuiteAltRow(alt: alt, inherits: heirIds.contains(alt["uuid"].string))
                    }
                }
                ForEach(user["inheritedLocks"].array, id: \.["flag"].string) { lock in
                    Label(SuiteAltRow.inheritedLine(lock), systemImage: "arrow.triangle.branch").font(.caption).foregroundStyle(.purple)
                }
            } header: { Text("Alt-Accounts") } footer: { Text("Sperren gelten automatisch für alle bestätigten Alt-Accounts – auch für künftig erkannte –, solange die Alt-Vererbung oben eingeschaltet ist. Aufheben beim Hauptkonto hebt sie überall auf.") }
        }
    }
    private func grant(_ group: JSON, _ time: String) {
        let operation = time.isEmpty ? "user-parent-add" : "user-parent-temp"
        let name = group["name"].string
        askRank(["operation": .string(operation), "group": .string(name), "duration": .string(time)], who + " zu „" + name + "“ hinzufügen?")
    }
    private func ungroup(_ group: JSON) {
        let name = group["group"].string
        askRank(["operation": .string("user-parent-remove"), "group": .string(name)], who + " aus „" + name + "“ entfernen?")
    }
    var availableGroups: [JSON] {
        let current = Set(rank["groups"].array.map { $0["group"].string })
        return groups.filter { !current.contains($0["name"].string) }
    }
    var heirIds: Set<String> { Set(user["heirs"].array.map { $0["uuid"].string }) }
    private func inherited(_ flag: String) -> JSON { user["inheritedLocks"].array.first { $0["flag"].string == flag } ?? .null }
}

struct SuiteAltRow: View {
    let alt: JSON
    let inherits: Bool
    var status: String { inherits ? "erbt Sperren" : alt["inherit"].bool ? "erbt bei Sperren" : "Vererbung aus" }
    var body: some View {
        HStack {
            Label(alt["name"].string, systemImage: alt["online"].bool ? "person.fill.checkmark" : "person")
            Spacer()
            Text(status).font(.caption).foregroundStyle(inherits ? Color.orange : Color.secondary)
        }
    }
    static func inheritedLine(_ lock: JSON) -> String {
        let title = lock["title"].string, from = lock["fromName"].string, rest = lock["remaining"].string
        return title + " · geerbt von " + from + " · " + rest
    }
}

struct SuiteRankGroupRow: View {
    let group: JSON
    let primary: String
    var until: String {
        let expiry = group["expiry"].double
        guard expiry > 0 else { return "dauerhaft" }
        return "bis " + Date(timeIntervalSince1970: expiry / 1000).formatted(date: .abbreviated, time: .shortened)
    }
    var body: some View {
        HStack {
            Image(systemName: "crown.fill").foregroundStyle(.yellow)
            Text(group["group"].string).bold(group["group"].string == primary)
            if group["group"].string == primary { Text("primär").font(.caption2).foregroundStyle(.secondary) }
            Spacer()
            Text(until).font(.caption).foregroundStyle(.secondary)
        }
    }
}

struct SuiteAccessLockFlagRow: View {
    let flag: JSON
    let active: JSON
    var legacy: [JSON] = []
    var inherited: JSON = .null
    @Binding var picked: Bool
    let canEdit: Bool
    let remove: () -> Void
    var stateText: String { active != .null ? "gesperrt" : inherited != .null ? "geerbt" : legacy.isEmpty ? "erlaubt" : "alte Sperre" }
    var stateColor: Color {
        if active != .null { return .red }
        if inherited != .null { return .purple }
        return legacy.isEmpty ? .green : .orange
    }
    var activeText: String {
        let reason = active["reason"].string
        return active["scopeTitle"].string + " · " + active["remaining"].string + (reason.isEmpty ? "" : " · " + reason)
    }
    var inheritedText: String { "Geerbt von " + inherited["fromName"].string + " · " + inherited["scopeTitle"].string + " · " + inherited["remaining"].string }
    var legacyText: String {
        let names: [String] = legacy.map { row in row["name"].string + " (" + row["remaining"].string + ")" }
        return "Alte Einzelsperre: " + names.joined(separator: ", ") + " – wird mit aufgehoben"
    }
    var body: some View {
        Toggle(isOn: $picked) { label }
        .disabled(!canEdit)
        .swipeActions { if active != .null || !legacy.isEmpty { Button("Aufheben", role: .destructive, action: remove).disabled(!canEdit) } }
    }
    private var label: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: SuiteAccessLockText.symbol(flag["id"].string)).foregroundStyle(stateColor).frame(width: 24)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(flag["title"].string).font(.subheadline.weight(.semibold))
                    Text(stateText).font(.caption2.bold()).foregroundStyle(stateColor)
                }
                if active != .null { Text(activeText).font(.caption).foregroundStyle(.red) }
                if active == .null && inherited != .null { Text(inheritedText).font(.caption).foregroundStyle(.purple) }
                if !legacy.isEmpty { Text(legacyText).font(.caption).foregroundStyle(.orange) }
                Text(flag["explain"].string).font(.caption2).foregroundStyle(.secondary).lineLimit(3)
            }
        }
    }
}

// MARK: - Classic rights inside the unified view

enum SuiteRightsGrouping {
    static let order = ["Chat & Befehle", "Kampf & Gegenstände", "Bewegung & Welten", "Weitere"]
    static func groups(_ rights: [JSON]) -> [(String, [JSON])] {
        var map: [String: [JSON]] = [:]
        for right in rights { map[right["group"].string.isEmpty ? "Weitere" : right["group"].string, default: []].append(right) }
        return map.keys.sorted { (order.firstIndex(of: $0) ?? 99, $0) < (order.firstIndex(of: $1) ?? 99, $1) }.map { ($0, map[$0] ?? []) }
    }
}

struct SuiteClassicRightRow: View {
    let right: JSON
    let durations: [String]
    let canEdit: Bool
    let block: (String) -> Void
    let allow: () -> Void
    var stateLine: String { right["blocked"].bool ? "Gesperrt · " + right["remaining"].string : "Erlaubt" }
    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: right["blocked"].bool ? "lock.fill" : "checkmark.circle.fill").foregroundStyle(right["blocked"].bool ? Color.red : Color.green).frame(width: 22)
            VStack(alignment: .leading, spacing: 1) {
                Text(right["name"].string).font(.subheadline)
                Text(stateLine).font(.caption).foregroundStyle(right["blocked"].bool ? Color.red : Color.secondary)
            }
            Spacer(minLength: 6)
            if right["blocked"].bool {
                Button("Erlauben", action: allow).buttonStyle(.bordered).controlSize(.small).disabled(!canEdit)
            } else {
                Menu {
                    ForEach(durations, id: \.self) { time in Button(SuiteAccessLockText.duration(time)) { block(time) } }
                } label: { Text("Sperren …").font(.footnote.weight(.semibold)) }.disabled(!canEdit)
            }
        }
    }
}

// MARK: - App user rights: grouped and explained

enum SuiteRightsCatalog {
    static let order = ["Vollzugriff", "Übersicht", "Spieler", "Moderation", "Tickets & Chat", "Ränge & Rechte", "Sicherheit", "Server", "Welten", "Plugins", "Einstellungen", "Discord", "Benachrichtigungen", "Protokolle", "Team", "Zugänge"]
    /// Groups the catalog by the Suite's plain-language area (older Suites: by id prefix).
    static func groups(_ catalog: [JSON], query: String) -> [(String, [JSON])] {
        let filtered = catalog.filter { query.isEmpty || ($0["title"].string + " " + $0["id"].string + " " + $0["explain"].string).localizedCaseInsensitiveContains(query) }
        var map: [String: [JSON]] = [:]
        for item in filtered { map[group(item), default: []].append(item) }
        return map.keys.sorted { (order.firstIndex(of: $0) ?? 99, $0) < (order.firstIndex(of: $1) ?? 99, $1) }.map { ($0, map[$0] ?? []) }
    }
    static func group(_ item: JSON) -> String {
        if !item["group"].string.isEmpty { return item["group"].string }
        let id = item["id"].string
        if id == "*" { return "Vollzugriff" }
        if id.hasPrefix("data.") { return "Lesen" }
        if id.hasPrefix("action.") { return "Aktionen" }
        if id.hasPrefix("ticket.") { return "Tickets & Chat" }
        return "Weitere"
    }
    static func level(_ item: JSON) -> (String, Color)? {
        switch item["level"].string {
        case "critical": return ("Kritisch", .red)
        case "change": return ("Ändern", .blue)
        default: return nil
        }
    }
}

struct SuiteRightToggle: View {
    let item: JSON
    @Binding var on: Bool
    var body: some View {
        Toggle(isOn: $on) {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(item["title"].string)
                    if let level = SuiteRightsCatalog.level(item) { Text(level.0).font(.caption2.bold()).foregroundStyle(level.1) }
                }
                if !item["explain"].string.isEmpty { Text(item["explain"].string).font(.caption).foregroundStyle(.secondary) }
                Text(item["id"].string).font(.caption2.monospaced()).foregroundStyle(.tertiary)
            }
        }
    }
}

// MARK: - Live player search for every player field

/// Text field that suggests matching players (online first, partial names) while typing – used in action forms,
/// LuckPerms and the rights view. Picking a player fills in the exact name.
struct SuiteLivePlayerField: View {
    let suite: SuiteAPI
    let title: String
    @Binding var text: String
    @State private var matches: [JSON] = []
    @State private var picked = ""
    var body: some View {
        TextField(title, text: $text).textInputAutocapitalization(.never).autocorrectionDisabled()
            .task(id: text) { await search() }
        ForEach(matches, id: \.["id"].string) { row in
            Button { picked = row["name"].string; text = row["name"].string; matches = [] } label: { SuitePlayerMatchRow(row: row) }.buttonStyle(.plain)
        }
    }
    private func search() async {
        let query = text.trimmingCharacters(in: .whitespaces)
        guard !query.isEmpty, query != picked, suite.can("data.players") else { matches = []; return }
        do {
            try await Task.sleep(for: .milliseconds(250))
            let result = try await suite.data("players", query: ["q": query, "filter": "all", "limit": "6"])
            try Task.checkCancellation()
            let rows = Array(result["items"].array.prefix(6))
            matches = rows.count == 1 && rows[0]["name"].string.caseInsensitiveCompare(query) == .orderedSame ? [] : rows
        } catch { matches = [] }
    }
    /// Field names that hold a player (Suite actions use player, player-one, player-two, target).
    static func isPlayerField(_ field: JSON) -> Bool {
        let name = field["name"].string.lowercased()
        return ["player", "player-one", "player-two", "minecraft", "spieler"].contains(name) || (name == "target" && field["description"].string.localizedCaseInsensitiveContains("spieler"))
    }
}
