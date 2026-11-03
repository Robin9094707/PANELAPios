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
    @State private var lookup = ""
    @State private var error: String?
    var body: some View {
        List {
            Section {
                TextField("Spielername oder UUID", text: $lookup).textInputAutocapitalization(.never).autocorrectionDisabled()
                NavigationLink { SuiteAccessLockPlayerView(suite: suite, player: lookup.trimmingCharacters(in: .whitespaces)) } label: { Label("Spieler öffnen", systemImage: "person.crop.circle.badge.questionmark") }
                    .disabled(lookup.trimmingCharacters(in: .whitespaces).isEmpty)
            } header: { Text("Spieler sperren") } footer: { Text("Auch offline möglich. Danach Bereiche wählen, Gültigkeit und Dauer festlegen.") }
            lockedSection
            SuiteAccessLockHelp(data: data)
        }
        .panelScreen().navigationTitle("Rechte-Sperren").task { await load() }.refreshable { await load() }.errorAlert($error)
    }
    @ViewBuilder private var lockedSection: some View {
        Section("Aktive Sperren") {
            if data == .null { ProgressView() }
            else if data["items"].array.isEmpty { Text("Niemand ist gesperrt.").foregroundStyle(.secondary) }
            ForEach(data["items"].array, id: \.["uuid"].string) { row in
                NavigationLink { SuiteAccessLockPlayerView(suite: suite, player: row["uuid"].string) } label: { SuiteAccessLockRow(row: row) }
            }
        }
    }
    func load() async { do { data = try await suite.data("access-locks") } catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } } }
}

struct SuiteAccessLockRow: View {
    let row: JSON
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(row["name"].string).font(.subheadline.weight(.semibold))
                if row["online"].bool { Circle().fill(.green).frame(width: 7, height: 7) }
            }
            Text(row["locks"].array.map { $0["title"].string + " (" + $0["remaining"].string + ")" }.joined(separator: " · ")).font(.caption).foregroundStyle(.secondary).lineLimit(3)
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
    init(suite: SuiteAPI, player: String) { self.suite = suite; self.player = player; _runner = StateObject(wrappedValue: SuiteConfirmRunner(suite)) }
    var canEdit: Bool { suite.can("action.locks.update") && suite.scope != "read" }
    var user: JSON { data["player"] }
    var who: String { user["name"].string.isEmpty ? player : user["name"].string }
    var durations: [String] { data["durations"].array.isEmpty ? ["30m", "2h", "12h", "1d", "3d", "7d", "14d", "30d", ""] : data["durations"].array.map(\.string) }
    var body: some View {
        List {
            Section {
                LabeledContent("Status", value: user["online"].bool ? "Online" : "Offline")
                LabeledContent("Aktive Sperren", value: String(user["locks"].array.count))
                if !user["locks"].array.isEmpty { Button("Alle Sperren aufheben", systemImage: "lock.open.fill", role: .destructive) { ask(["operation": .string("clear")], "Alle Sperren von " + who + " aufheben?") }.disabled(!canEdit) }
            } header: { Text(who) }
            flagSection
            settingsSection
            presetSection
            SuiteAccessLockHelp(data: data)
        }
        .panelScreen().navigationTitle(who).navigationBarTitleDisplayMode(.inline).task { await load() }.refreshable { await load() }
        .suiteConfirm(runner) { picked = []; await load() }
    }
    private func active(_ flag: String) -> JSON { user["locks"].array.first { $0["flag"].string == flag } ?? .null }
    private var flagSection: some View {
        Section {
            ForEach(data["flags"].array, id: \.["id"].string) { flag in
                SuiteAccessLockFlagRow(flag: flag, active: active(flag["id"].string), picked: Binding(get: { picked.contains(flag["id"].string) }, set: { if $0 { picked.insert(flag["id"].string) } else { picked.remove(flag["id"].string) } }), canEdit: canEdit) {
                    ask(["operation": .string("remove"), "flags": flag["id"]], flag["title"].string + " für " + who + " wieder erlauben?")
                }
            }
        } header: { Text("Bereiche") } footer: { Text("Gesperrtes kann weder benutzt, abgebaut noch gesprengt werden – auch nicht in Ländern, in denen der Spieler vertraut ist. Wischen zum Aufheben.") }
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
    }
}

struct SuiteAccessLockFlagRow: View {
    let flag: JSON
    let active: JSON
    @Binding var picked: Bool
    let canEdit: Bool
    let remove: () -> Void
    var body: some View {
        Toggle(isOn: $picked) {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: SuiteAccessLockText.symbol(flag["id"].string)).foregroundStyle(active == .null ? Color.panelAccent : .red).frame(width: 24)
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        Text(flag["title"].string).font(.subheadline.weight(.semibold))
                        Text(active == .null ? "erlaubt" : "gesperrt").font(.caption2.bold()).foregroundStyle(active == .null ? .green : .red)
                    }
                    if active != .null { Text(active["scopeTitle"].string + " · " + active["remaining"].string + (active["reason"].string.isEmpty ? "" : " · " + active["reason"].string)).font(.caption).foregroundStyle(.red) }
                    Text(flag["explain"].string).font(.caption2).foregroundStyle(.secondary).lineLimit(3)
                }
            }
        }
        .disabled(!canEdit)
        .swipeActions { if active != .null { Button("Aufheben", role: .destructive, action: remove).disabled(!canEdit) } }
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
