import SwiftUI
import UIKit

// MARK: - Simplex Panel 4.8.0 / Admin Suite 10.5.0
// Werbe-Zentrale: advertising places (Discord channels, Reddit, Planet Minecraft, Facebook groups …) with link,
// formatted text and interval. Nothing is posted automatically: Bark reminds, a tap copies the text into the iOS
// clipboard and opens the place (Discord channel directly in the Discord app); the server restarts the timer.

/// Formats, platforms and the preview conversion (unit-tested).
enum AdHubFormat {
    static let formats: [(String, String)] = [("discord", "Discord-Markdown"), ("markdown", "Reddit-Markdown"), ("bbcode", "BBCode (Planet Minecraft, Foren)"), ("whatsapp", "WhatsApp / Telegram"), ("unicode", "Facebook (Unicode-Fett)"), ("plain", "Reiner Text")]
    static func label(_ format: String) -> String { formats.first { $0.0 == format }?.1 ?? format }
    /// Preview platform for a text format.
    static func platform(for format: String) -> String {
        switch format { case "discord": return "discord"; case "markdown": return "reddit"; case "bbcode": return "planetminecraft"; case "whatsapp": return "whatsapp"; case "unicode": return "facebook"; default: return "other" }
    }
    static func symbol(_ platform: String) -> String {
        switch platform {
        case "discord": return "bubble.left.and.bubble.right.fill"
        case "reddit": return "person.3.fill"
        case "planetminecraft": return "globe.europe.africa.fill"
        case "facebook": return "person.2.fill"
        case "telegram": return "paperplane.fill"
        case "whatsapp": return "phone.bubble.fill"
        case "x": return "xmark"
        case "serverlist": return "list.number"
        default: return "link"
        }
    }
    static func color(_ platform: String) -> Color {
        switch platform {
        case "discord": return Color(red: 0.345, green: 0.396, blue: 0.949)
        case "reddit": return Color(red: 1, green: 0.27, blue: 0)
        case "planetminecraft": return Color(red: 0.24, green: 0.42, blue: 0.21)
        case "facebook": return Color(red: 0.094, green: 0.467, blue: 0.949)
        case "telegram": return Color(red: 0.133, green: 0.62, blue: 0.85)
        case "whatsapp": return Color(red: 0.145, green: 0.827, blue: 0.4)
        case "x": return .black
        case "serverlist": return .purple
        default: return .gray
        }
    }
    static func guess(_ link: String) -> String {
        let l = link.lowercased()
        if l.range(of: "^https?://([a-z]+\\.)?discord(app)?\\.(com|gg)/", options: .regularExpression) != nil { return "discord" }
        if l.contains("reddit.com/") || l.contains("redd.it/") { return "reddit" }
        if l.contains("planetminecraft.com") { return "planetminecraft" }
        if l.contains("facebook.com/") || l.contains("fb.com/") { return "facebook" }
        if l.contains("t.me/") { return "telegram" }
        if l.contains("whatsapp.com/") || l.contains("wa.me/") { return "whatsapp" }
        if l.contains("x.com/") || l.contains("twitter.com/") { return "x" }
        if l.range(of: "minecraft-server|minecraftservers|topg\\.|minecraft-mp|serverlist", options: .regularExpression) != nil { return "serverlist" }
        return "other"
    }
    /// Guild and channel id of a Discord channel link.
    static func discordChannel(_ link: String) -> (guild: String, channel: String)? {
        let pattern = "^https://(?:(?:www|ptb|canary)\\.)?discord(?:app)?\\.com/channels/(\\d{15,25}|@me)/(\\d{15,25})"
        guard let regex = try? NSRegularExpression(pattern: pattern), let match = regex.firstMatch(in: link, range: NSRange(link.startIndex..., in: link)),
              let g = Range(match.range(at: 1), in: link), let c = Range(match.range(at: 2), in: link) else { return nil }
        return (String(link[g]), String(link[c]))
    }
    private static func replace(_ text: String, _ pattern: String, _ template: String, options: NSRegularExpression.Options = []) -> String {
        guard let regex = try? NSRegularExpression(pattern: pattern, options: options) else { return text }
        return regex.stringByReplacingMatches(in: text, range: NSRange(text.startIndex..., in: text), withTemplate: template)
    }
    /// Discord markdown for the preview, or nil for formats that are shown as typed (plain, Unicode).
    static func discordMarkdown(_ text: String, format: String) -> String? {
        var s = text
        switch format {
        case "discord": return s
        case "markdown":
            s = replace(s, ">!(.+?)!<", "||$1||"); s = replace(s, "\\^\\(([^)\\n]*)\\)", "-# $1"); return replace(s, " {2,}\\n", "\n")
        case "whatsapp":
            s = replace(s, "(^|[^\\w*])\\*(?=\\S)([^*\\n]*?\\S)\\*(?![\\w*])", "$1**$2**", options: .anchorsMatchLines)
            s = replace(s, "(^|[^\\w_])_(?=\\S)([^_\\n]*?\\S)_(?![\\w_])", "$1*$2*", options: .anchorsMatchLines)
            return replace(s, "(^|[^\\w~])~(?=\\S)([^~\\n]*?\\S)~(?![\\w~])", "$1~~$2~~", options: .anchorsMatchLines)
        case "bbcode":
            s = replace(s, "\\[code\\]\\n?([\\s\\S]*?)\\n?\\[/code\\]", "```\n$1\n```", options: .caseInsensitive)
            s = replace(s, "\\[url=([^\\]]+)\\]([\\s\\S]*?)\\[/url\\]", "[$2]($1)", options: .caseInsensitive)
            s = replace(s, "\\[(url|img)\\]([\\s\\S]*?)\\[/\\1\\]", "$2", options: .caseInsensitive)
            s = replace(s, "\\[size=\"?[6-7]\"?\\]\\[b\\]([^\\n]*?)\\[/b\\]\\[/size\\]", "# $1", options: .caseInsensitive)
            s = replace(s, "\\[size=\"?5\"?\\]\\[b\\]([^\\n]*?)\\[/b\\]\\[/size\\]", "## $1", options: .caseInsensitive)
            s = replace(s, "\\[size=\"?4\"?\\]\\[b\\]([^\\n]*?)\\[/b\\]\\[/size\\]", "### $1", options: .caseInsensitive)
            s = replace(s, "\\[size=\"?[1-2]\"?\\]([^\\n]*?)\\[/size\\]", "-# $1", options: .caseInsensitive)
            s = replace(s, "\\[/?size(=[^\\]]*)?\\]", "", options: .caseInsensitive)
            if let regex = try? NSRegularExpression(pattern: "\\[quote(?:=[^\\]]*)?\\]\\n?([\\s\\S]*?)\\n?\\[/quote\\]", options: .caseInsensitive) {
                var out = s
                for match in regex.matches(in: s, range: NSRange(s.startIndex..., in: s)).reversed() {
                    guard let whole = Range(match.range, in: out), let inner = Range(match.range(at: 1), in: out) else { continue }
                    out.replaceSubrange(whole, with: out[inner].components(separatedBy: "\n").map { "> " + $0 }.joined(separator: "\n"))
                }
                s = out
            }
            s = replace(s, "\\[list(=[^\\]]*)?\\]\\n?", "", options: .caseInsensitive); s = replace(s, "\\n?\\[/list\\]", "", options: .caseInsensitive); s = replace(s, "\\[\\*\\]\\s*", "- ")
            s = replace(s, "\\[b\\]([\\s\\S]*?)\\[/b\\]", "**$1**", options: .caseInsensitive); s = replace(s, "\\[i\\]([\\s\\S]*?)\\[/i\\]", "*$1*", options: .caseInsensitive)
            s = replace(s, "\\[u\\]([\\s\\S]*?)\\[/u\\]", "__$1__", options: .caseInsensitive); s = replace(s, "\\[(s|strike)\\]([\\s\\S]*?)\\[/\\1\\]", "~~$2~~", options: .caseInsensitive)
            s = replace(s, "\\[spoiler(?:=[^\\]]*)?\\]([\\s\\S]*?)\\[/spoiler\\]", "||$1||", options: .caseInsensitive)
            return replace(s, "\\[/?(center|left|right|justify|color|font|indent|align|hr|h[1-6])(=[^\\]]*)?\\]", "", options: .caseInsensitive)
        default: return nil
        }
    }
    /// Toolbar markers per format: (symbol, title, open, close or nil for line prefixes).
    static func tools(_ format: String) -> [(String, String, String, String?)] {
        switch format {
        case "discord": return [("bold", "Fett", "**", "**"), ("italic", "Kursiv", "*", "*"), ("underline", "Unterstrichen", "__", "__"), ("strikethrough", "Durchgestrichen", "~~", "~~"), ("eye.slash", "Spoiler", "||", "||"), ("chevron.left.forwardslash.chevron.right", "Code", "`", "`"), ("textformat.size.larger", "Überschrift", "# ", nil), ("textformat.size", "Überschrift 2", "## ", nil), ("textformat.subscript", "Kleiner Text", "-# ", nil), ("text.quote", "Zitat", "> ", nil), ("list.bullet", "Liste", "- ", nil)]
        case "markdown": return [("bold", "Fett", "**", "**"), ("italic", "Kursiv", "*", "*"), ("strikethrough", "Durchgestrichen", "~~", "~~"), ("eye.slash", "Spoiler", ">!", "!<"), ("chevron.left.forwardslash.chevron.right", "Code", "`", "`"), ("textformat.size.larger", "Überschrift", "# ", nil), ("text.quote", "Zitat", "> ", nil), ("list.bullet", "Liste", "- ", nil)]
        case "bbcode": return [("bold", "Fett", "[b]", "[/b]"), ("italic", "Kursiv", "[i]", "[/i]"), ("underline", "Unterstrichen", "[u]", "[/u]"), ("strikethrough", "Durchgestrichen", "[s]", "[/s]"), ("textformat.size.larger", "Groß", "[size=6][b]", "[/b][/size]"), ("textformat.size", "Mittel", "[size=5][b]", "[/b][/size]"), ("textformat.size.smaller", "Klein", "[size=2]", "[/size]"), ("text.aligncenter", "Zentriert", "[center]", "[/center]"), ("paintpalette", "Farbe", "[color=#22d3ee]", "[/color]"), ("text.quote", "Zitat", "[quote]", "[/quote]"), ("list.bullet", "Liste", "[list]\n[*]", "\n[/list]"), ("photo", "Bild", "[img]", "[/img]")]
        case "whatsapp": return [("bold", "Fett", "*", "*"), ("italic", "Kursiv", "_", "_"), ("strikethrough", "Durchgestrichen", "~", "~"), ("chevron.left.forwardslash.chevron.right", "Code", "`", "`"), ("text.quote", "Zitat", "> ", nil), ("list.bullet", "Liste", "- ", nil)]
        default: return []
        }
    }
}

// MARK: - Copy & open

/// Copies the ad and opens the place. Discord channel links open the channel in the Discord app when it is installed.
@MainActor enum AdHubOpener {
    static func discordAppURL(guild: String, channel: String) -> URL? { URL(string: "discord://discord.com/channels/\(guild)/\(channel)") }

    static func copyAndOpen(text: String, link: String) async {
        UIPasteboard.general.string = text
        Feedback.success()
        await waitUntilActive()
        guard let url = URL(string: link) else { return }
        if let dc = AdHubFormat.discordChannel(link) {
            // 1. Universal link (Discord app installed and claiming discord.com), 2. discord:// scheme, 3. Safari.
            if await open(url, universalOnly: true) { return }
            if let app = discordAppURL(guild: dc.guild, channel: dc.channel), await open(app, universalOnly: false) { return }
        }
        _ = await open(url, universalOnly: false)
    }
    private static func open(_ url: URL, universalOnly: Bool) async -> Bool {
        await withCheckedContinuation { continuation in
            UIApplication.shared.open(url, options: universalOnly ? [.universalLinksOnly: true] : [:]) { continuation.resume(returning: $0) }
        }
    }
    /// Another app can only be opened while this one is in front; a tap on a Bark notice may arrive a moment earlier.
    private static func waitUntilActive() async {
        for _ in 0..<30 where UIApplication.shared.applicationState != .active { try? await Task.sleep(for: .milliseconds(100)) }
    }
}

/// Last known texts and links per place, so a Bark tap still copies and opens when the server is unreachable.
enum AdHubCache {
    static let key = "adHub.cache.v1"
    static func store(_ overview: JSON, serverId: String, defaults: UserDefaults = .standard) {
        var all = (defaults.dictionary(forKey: key) as? [String: [String: String]]) ?? [:]
        let texts = Dictionary(overview["texts"].array.map { ($0["id"].string, $0["content"].string) }, uniquingKeysWith: { a, _ in a })
        for place in overview["places"].array {
            all[serverId + "|" + place["id"].string] = ["name": place["name"].string, "link": place["link"].string, "text": DiscordMarkdown.fill(texts[place["textId"].string] ?? "", online: "", max: "")]
        }
        if all.count > 400 { all = Dictionary(uniqueKeysWithValues: all.prefix(400).map { ($0.key, $0.value) }) }
        defaults.set(all, forKey: key)
    }
    static func entry(serverId: String, place: String, defaults: UserDefaults = .standard) -> [String: String]? {
        (defaults.dictionary(forKey: key) as? [String: [String: String]])?[serverId + "|" + place]
    }
}

/// A Bark reminder link: panel://ad-post?s=<serverId>&p=<place>&c=<cycle>&k=<signature>.
struct AdPostLink: Identifiable, Equatable {
    let server: String
    let place: String
    let cycle: Int
    let key: String
    var id: String { server + place + String(cycle) }
    init?(_ url: URL) {
        guard url.scheme == "panel", url.host == "ad-post", let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems else { return nil }
        func value(_ name: String) -> String { items.first { $0.name == name }?.value ?? "" }
        guard !value("p").isEmpty, value("p").count <= 40, let cycle = Int(value("c")), cycle > 0, value("k").range(of: "^[A-Za-z0-9_-]{16,64}$", options: .regularExpression) != nil, value("s").count <= 120 else { return nil }
        server = value("s"); place = value("p"); self.cycle = cycle; key = value("k")
    }
}

/// Handles Bark taps: asks the suite (no login needed, the link is signed), copies the text and opens the place.
@MainActor final class AdPostInbox: ObservableObject {
    static let shared = AdPostInbox()
    struct Prompt: Identifiable { let id = UUID(); let link: AdPostLink; let connection: SuiteConnection?; var result: JSON; var error: String?; var offline: [String: String]? }
    @Published var prompt: Prompt?
    @Published var busy = false
    /// Off (default): a Bark tap copies and opens at once. On: a sheet with preview, "copy & open" and snooze first.
    var askOnOpen: Bool {
        get { UserDefaults.standard.bool(forKey: "adHub.askOnOpen") }
        set { objectWillChange.send(); UserDefaults.standard.set(newValue, forKey: "adHub.askOnOpen") }
    }

    /// Returns true when the URL was a Werbe-Zentrale link.
    @discardableResult func open(_ url: URL) -> Bool {
        guard let link = AdPostLink(url) else { return false }
        Task { await handle(link) }
        return true
    }
    private func candidates(_ server: String) -> [SuiteConnection] {
        let all = SuiteConnections.shared.values
        let exact = all.filter { !$0.serverIdentity.isEmpty && $0.serverIdentity == server }
        if !exact.isEmpty { return exact }
        return Array(all.filter { $0.serverIdentity.isEmpty }.prefix(4))
    }
    func call(_ link: AdPostLink, op: String, minutes: Int = 0) async throws -> (JSON, SuiteConnection) {
        var last: Error = PanelError(message: "Keine passende Admin-Suite-Verbindung auf diesem Gerät gefunden.")
        for connection in candidates(link.server) {
            do {
                let api = try SuiteAPI(connection, token: "")
                let result = try await api.post("ad-hub/link", ["p": .string(link.place), "c": .number(Double(link.cycle)), "k": .string(link.key), "op": .string(op), "minutes": .number(Double(minutes))])
                return (result, connection)
            } catch { last = error; if (error.localizedDescription.hasPrefix("HTTP 410") || error.localizedDescription.hasPrefix("HTTP 403")) && !link.server.isEmpty { break } }
        }
        throw last
    }
    func handle(_ link: AdPostLink) async {
        busy = true; defer { busy = false }
        if askOnOpen {
            do { let (result, connection) = try await call(link, op: "peek"); prompt = Prompt(link: link, connection: connection, result: result) }
            catch { prompt = Prompt(link: link, connection: nil, result: .null, error: error.localizedDescription, offline: AdHubCache.entry(serverId: link.server, place: link.place)) }
            return
        }
        await post(link)
    }
    func post(_ link: AdPostLink) async {
        do {
            let (result, _) = try await call(link, op: "open")
            await AdHubOpener.copyAndOpen(text: result["text"].string, link: result["link"].string)
            prompt = nil
        } catch {
            let cached = AdHubCache.entry(serverId: link.server, place: link.place)
            prompt = Prompt(link: link, connection: nil, result: .null, error: error.localizedDescription, offline: cached)
        }
    }
    func snooze(_ link: AdPostLink, minutes: Int) async {
        do { _ = try await call(link, op: "snooze", minutes: minutes); prompt = nil; Feedback.success() }
        catch { prompt?.error = error.localizedDescription }
    }
}

/// Sheet after a Bark tap when "ask first" is on, or when the server could not be reached.
struct AdPostPromptView: View {
    let prompt: AdPostInbox.Prompt
    @ObservedObject private var inbox = AdPostInbox.shared
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            List {
                if let error = prompt.error {
                    Section { Label(error, systemImage: "exclamationmark.triangle.fill").foregroundStyle(.orange) } footer: { Text(prompt.offline == nil ? "Öffne die Werbe-Zentrale in der App, sobald der Server erreichbar ist." : "Offline: Text und Link sind vom letzten Laden. Der Timer startet erst neu, wenn du in der Werbe-Zentrale „Geworben“ tippst.") }
                    if let offline = prompt.offline {
                        Section(offline["name"] ?? "Werbung") {
                            Button { Task { await AdHubOpener.copyAndOpen(text: offline["text"] ?? "", link: offline["link"] ?? ""); dismiss() } } label: { Label("Trotzdem kopieren & öffnen", systemImage: "doc.on.doc.fill") }
                        }
                    }
                } else {
                    Section {
                        AdHubPreview(text: prompt.result["text"].string, format: prompt.result["format"].string, platform: prompt.result["platform"].string, name: prompt.result["name"].string)
                            .listRowInsets(EdgeInsets())
                    } header: { Text(prompt.result["name"].string + " · " + prompt.result["platformLabel"].string) }
                    Section {
                        Button { Task { await inbox.post(prompt.link); dismiss() } } label: { Label("Kopieren & " + prompt.result["platformLabel"].string + " öffnen", systemImage: "doc.on.doc.fill").font(.headline) }
                        Menu { ForEach([(15, "15 Min"), (30, "30 Min"), (60, "1 Std"), (180, "3 Std"), (360, "6 Std"), (1440, "1 Tag")], id: \.0) { item in Button(item.1) { Task { await inbox.snooze(prompt.link, minutes: item.0); dismiss() } } } } label: { Label("Später erinnern", systemImage: "clock.arrow.circlepath") }
                    }
                }
                Section { Toggle("Vor dem Öffnen fragen", isOn: $inbox.askOnOpen) } footer: { Text("Aus: Ein Tipp auf die Bark-Mitteilung kopiert sofort und öffnet den Ort.") }
            }
            .navigationTitle("Werbung fällig").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Schließen") { dismiss() } } }
            .overlay { if inbox.busy { ProgressView() } }
        }
    }
}

// MARK: - Preview per platform

struct AdHubPreview: View {
    let text: String
    let format: String
    let platform: String
    var name = "werbung"
    var body: some View {
        if platform == "discord" { DiscordMessagePreview(content: text, name: "Du", avatar: "", channel: name.isEmpty ? "werbung" : name) }
        else { skin }
    }
    private var filled: String { DiscordMarkdown.fill(text) }
    @ViewBuilder private var content: some View {
        if let md = AdHubFormat.discordMarkdown(filled, format: format) { DiscordMarkdownView(blocks: DiscordMarkdown.blocks(md)) }
        else { Text(filled).frame(maxWidth: .infinity, alignment: .leading) }
    }
    private var dark: Bool { ["telegram", "whatsapp", "x"].contains(platform) }
    private var skin: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 8) {
                Image(systemName: AdHubFormat.symbol(platform)).foregroundStyle(.white).frame(width: 26, height: 26).background(AdHubFormat.color(platform), in: RoundedRectangle(cornerRadius: 7))
                Text(header).font(.subheadline.weight(.semibold))
                Spacer()
                Text("Jetzt").font(.caption2).foregroundStyle(.secondary)
            }
            .padding(10)
            Divider()
            content.padding(12)
        }
        .background(dark ? Color(white: 0.09) : Color.white)
        .environment(\.colorScheme, dark ? .dark : .light)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(Color.gray.opacity(0.25)))
    }
    private var header: String {
        switch platform {
        case "reddit": return "r/" + (name.isEmpty ? "subreddit" : name)
        case "planetminecraft": return "Planet Minecraft"
        case "facebook": return name.isEmpty ? "Facebook-Gruppe" : name
        default: return name.isEmpty ? "Vorschau" : name
        }
    }
}

// MARK: - Main view

struct AdHubItem: Identifiable { let id: String; let json: JSON }

struct SuiteAdHubView: View {
    let suite: SuiteAPI
    var initialPlace: String? = nil
    var prepareOnOpen = false
    @State private var handledInitialPlace = false
    @State private var search = ""
    @State private var posting = false
    @State private var data: JSON = .null
    @State private var error: String?
    @State private var notice: String?
    @State private var editing: AdHubItem?
    @State private var newPlace = false
    @State private var editingText: AdHubItem?
    @State private var newText = false
    @State private var early: AdHubItem?
    @State private var filter = "all"
    private var canManage: Bool { data["canManage"].bool }
    private var canPost: Bool { data["canPost"].bool }
    private var places: [JSON] { data["places"].array.filter { (filter == "all" || $0["platform"].string == filter) && SuiteSearch.matches(search, in: $0["name"].string + " " + $0["platform"].string + " " + $0["link"].string + " " + $0["note"].string) } }
    var body: some View {
        List {
            if data == .null { Section { ProgressView("Werbe-Zentrale laden …") } }
            else {
                migrationSection
                SuiteAdHubStats(data: data)
                if !canPost { Section { Label("Nur ansehen – zum Werben ist manage- oder full-Zugriff nötig.", systemImage: "lock.fill").foregroundStyle(.secondary) } }
                filterSection
                placeSections
                textsSection
                SuiteAdHubSettingsSection(data: data, disabled: !canManage) { patch in Task { await run(patch) } }
                logSection
                Section { Text("Es wird nie automatisch gepostet. Halte dich an die Regeln der Server und Gruppen – zu häufiges Posten kann zum Ausschluss führen.").font(.footnote).foregroundStyle(.secondary) }
            }
        }
        .panelScreen()
        .navigationTitle("Werbe-Zentrale")
        .searchable(text: $search, prompt: "Werbe-Orte, Texte & Verlauf suchen")
        .toolbar { if canManage { ToolbarItem(placement: .topBarTrailing) { Button { newPlace = true } label: { Label("Werbe-Ort hinzufügen", systemImage: "plus") } } } }
        .task { await load() }
        .task { while !Task.isCancelled { try? await Task.sleep(for: .seconds(30)); if !Task.isCancelled && editing == nil && !newPlace && editingText == nil && !newText { await load() } } }
        .refreshable { await load() }
        .errorAlert($error)
        .alert(notice ?? "", isPresented: Binding(get: { notice != nil }, set: { if !$0 { notice = nil } })) { Button("OK") { notice = nil } }
        .confirmationDialog("Noch nicht fällig", isPresented: Binding(get: { early != nil }, set: { if !$0 { early = nil } }), presenting: early) { item in
            Button("Trotzdem werben", role: .destructive) { Task { await post(item.json, force: true) } }
            Button("Abbrechen", role: .cancel) { }
        } message: { item in Text("„\(item.json["name"].string)“ ist erst \(AdsFormat.until(item.json["dueAt"].double)) wieder dran. Zu frühes Posten kann zum Ausschluss vom Server führen.") }
        .sheet(item: $editing) { item in SuiteAdHubPlaceEditor(suite: suite, data: data, place: item.json) { apply($0) } }
        .sheet(isPresented: $newPlace) { SuiteAdHubPlaceEditor(suite: suite, data: data, place: nil) { apply($0) } }
        .sheet(item: $editingText) { item in SuiteAdHubTextEditor(suite: suite, data: data, text: item.json, preset: nil) { overview, _ in apply(overview) } }
        .sheet(isPresented: $newText) { SuiteAdHubTextEditor(suite: suite, data: data, text: nil, preset: nil) { overview, _ in apply(overview) } }
    }
    @ViewBuilder private var migrationSection: some View {
        let migration = data["migration"]
        if migration != .null {
            Section {
                Label("Discord-Werbeplaner übernommen: \(migration["places"].int) Kanäle und \(migration["texts"].int) Texte sind jetzt Werbe-Orte mit Bark-Erinnerung.", systemImage: "checkmark.seal.fill").foregroundStyle(.green)
                if migration["accounts"].int > 0 { Text("Der gespeicherte Discord-Token wurde gelöscht. Ändere am besten das Passwort des Zweitaccounts – dann ist er auch bei Discord ungültig.").font(.footnote) }
                Button("Verstanden") { Task { await run(["operation": .string("migration.ack")]) } }
            }
        }
    }
    @ViewBuilder private var filterSection: some View {
        let used = Set(data["places"].array.map { $0["platform"].string })
        if used.count > 1 {
            Section {
                Picker("Plattform", selection: $filter) {
                    Text("Alle").tag("all")
                    ForEach(data["platforms"].array.filter { used.contains($0["id"].string) }, id: \.["id"].string) { p in Text(p["label"].string).tag(p["id"].string) }
                }
            }
        }
    }
    @ViewBuilder private var placeSections: some View {
        let due = places.filter { $0["state"].string == "due" }, rest = places.filter { $0["state"].string != "due" }
        if data["places"].array.isEmpty {
            Section("Werbe-Orte") {
                Text("Füge einen Discord-Kanal-Link, ein Subreddit, deine Planet-Minecraft-Seite oder eine Facebook-Gruppe hinzu – mit Text und Intervall. Ist der Ort wieder dran, erinnert dich Bark.").foregroundStyle(.secondary)
                if canManage { Button { newPlace = true } label: { Label("Werbe-Ort hinzufügen", systemImage: "plus") } }
            }
        }
        if !due.isEmpty {
            Section { ForEach(due, id: \.["id"].string) { place in row(place) } } header: { Text("Jetzt fällig (\(due.count))") }
        }
        if !rest.isEmpty {
            Section { ForEach(rest, id: \.["id"].string) { place in row(place) } } header: { Text(due.isEmpty ? "Werbe-Orte" : "Wartet") } footer: { Text("„Werben“ kopiert den Text in die Zwischenablage und öffnet den Kanal bzw. die Seite. Danach startet der Timer leicht über dem Intervall neu.") }
        }
    }
    private func row(_ place: JSON) -> some View {
        SuiteAdHubPlaceRow(place: place, canPost: canPost, post: { Task { await post(place, force: false) } })
            .swipeActions(edge: .trailing) {
                if canManage { Button { editing = AdHubItem(id: place["id"].string, json: place) } label: { Label("Bearbeiten", systemImage: "pencil") }.tint(.blue) }
                if canPost && place["enabled"].bool { Button { Task { await run(["operation": .string("place.snooze"), "id": place["id"], "minutes": .number(60)]) } } label: { Label("1 Std später", systemImage: "clock") }.tint(.orange) }
            }
            .contextMenu { placeMenu(place) }
    }
    @ViewBuilder private func placeMenu(_ place: JSON) -> some View {
        if canPost {
            Button { Task { await post(place, force: false) } } label: { Label("Kopieren & öffnen", systemImage: "doc.on.doc.fill") }
            Menu { ForEach([(15, "15 Min"), (30, "30 Min"), (60, "1 Std"), (180, "3 Std"), (360, "6 Std"), (1440, "1 Tag")], id: \.0) { item in Button(item.1) { Task { await run(["operation": .string("place.snooze"), "id": place["id"], "minutes": .number(Double(item.0))]) } } } } label: { Label("Später erinnern", systemImage: "clock.arrow.circlepath") }
            if place["undoable"].bool { Button { Task { await run(["operation": .string("place.undo"), "id": place["id"]]) } } label: { Label("Letztes „Geworben“ zurücknehmen", systemImage: "arrow.uturn.backward") } }
        }
        Button { Clipboard.copy(SuiteLaunchRequest(tool: .advertising, connection: suite.connection.id, place: place["id"].string, prepareAd: true).url.absoluteString) } label: { Label("Kurzbefehle-Link: Werbung vorbereiten", systemImage: "link.badge.plus") }
        Button { Clipboard.copy(SuiteLaunchRequest(tool: .advertising, connection: suite.connection.id, place: place["id"].string).url.absoluteString) } label: { Label("Kurzbefehle-Link: Ort verwalten", systemImage: "link") }
        Button { if let url = URL(string: place["link"].string) { UIApplication.shared.open(url) } } label: { Label("Nur öffnen", systemImage: "safari") }
        Button { Clipboard.copy(place["link"].string) } label: { Label("Link kopieren", systemImage: "link") }
        if canManage {
            Button { editing = AdHubItem(id: place["id"].string, json: place) } label: { Label("Bearbeiten", systemImage: "pencil") }
            if place["enabled"].bool { Button { Task { await run(["operation": .string("place.pause"), "id": place["id"]]) } } label: { Label("Pausieren", systemImage: "pause.circle") } }
            else { Button { Task { await run(["operation": .string("place.resume"), "id": place["id"]]) } } label: { Label("Fortsetzen", systemImage: "play.circle") } }
            if place["state"].string != "due" { Button { Task { await run(["operation": .string("place.due-now"), "id": place["id"]]) } } label: { Label("Jetzt fällig setzen", systemImage: "bell.badge") } }
            Button(role: .destructive) { Task { await run(["operation": .string("place.delete"), "id": place["id"]]) } } label: { Label("Löschen", systemImage: "trash") }
        }
    }
    private var textsSection: some View {
        Section {
            ForEach(data["texts"].array.filter { SuiteSearch.matches(search, in: $0["name"].string + " " + $0["content"].string) }, id: \.["id"].string) { text in
                Button { if canManage { editingText = AdHubItem(id: text["id"].string, json: text) } else { Clipboard.copy(text["content"].string) } } label: { SuiteAdHubTextRow(text: text) }
                    .contextMenu {
                        Button { Clipboard.copy(text["content"].string) } label: { Label("Text kopieren", systemImage: "doc.on.doc") }
                        if canManage && text["usedBy"].array.isEmpty { Button(role: .destructive) { Task { await run(["operation": .string("text.delete"), "id": text["id"]]) } } label: { Label("Löschen", systemImage: "trash") } }
                    }
            }
            if canManage { Button { newText = true } label: { Label("Neuer Werbetext", systemImage: "plus") } }
        } header: { Text("Werbetexte") } footer: { Text("Discord, Reddit, BBCode (Planet Minecraft), WhatsApp/Telegram, Facebook (Unicode-Fett) oder reiner Text – mit Vorschau und Umwandeln. Lange drücken zum Kopieren.") }
    }
    private var logSection: some View {
        Section("Verlauf") {
            if suite.features.contains("history-management-v1") { SuiteHistoryLink(suite: suite, kind: "advertising", title: "Werbe-Verlauf") }
            if data["log"].array.isEmpty { Text("Noch nichts passiert.").foregroundStyle(.secondary) }
            ForEach(Array(data["log"].array.filter { SuiteSearch.matches(search, in: $0["text"].string) }.prefix(40).enumerated()), id: \.offset) { item in
                VStack(alignment: .leading, spacing: 2) { Text(item.element["text"].string).font(.subheadline); Text(AdsFormat.date(item.element["time"].double)).font(.caption2).foregroundStyle(.secondary) }
            }
        }
    }
    private func apply(_ overview: JSON) {
        guard overview != .null else { return }
        data = overview
        AdHubCache.store(overview, serverId: overview["serverId"].string)
    }
    private func load() async {
        do {
            apply(try await suite.request("ad-hub"))
            if let initialPlace, !handledInitialPlace {
                handledInitialPlace = true
                guard let place = data["places"].array.first(where: { $0["id"].string == initialPlace }) else { error = "Dieser Werbe-Ort wurde gelöscht oder ist nicht verfügbar."; return }
                if prepareOnOpen { await post(place, force: false) }
                else { search = place["name"].string }
            }
        }
        catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }
    private func run(_ body: [String: JSON]) async {
        do {
            let result = try await suite.post("ad-hub", body)
            apply(result["overview"])
            if !result["notice"].string.isEmpty { notice = result["notice"].string }
            Feedback.success()
        } catch { self.error = error.localizedDescription }
    }
    private func post(_ place: JSON, force: Bool) async {
        guard !posting else { return }
        guard canPost else { error = "Für diesen Werbe-Ort fehlt der Schreibzugriff."; return }
        guard place["enabled"].bool else { error = "Dieser Werbe-Ort ist pausiert. Bitte zuerst fortsetzen."; return }
        posting = true; defer { posting = false }
        do {
            let result = try await suite.post("ad-hub", ["operation": .string("place.post"), "id": place["id"], "force": .bool(force)])
            apply(result["overview"])
            await AdHubOpener.copyAndOpen(text: result["text"].string, link: result["link"].string)
        } catch {
            if error.localizedDescription.hasPrefix("HTTP 409") { early = AdHubItem(id: place["id"].string, json: place) }
            else { self.error = error.localizedDescription }
        }
    }
}

struct SuiteAdHubStats: View {
    let data: JSON
    var body: some View {
        Section {
            HStack(spacing: 10) {
                tile("Fällig", "\(data["stats"]["due"].int)", "bell.badge.fill", data["stats"]["due"].int > 0 ? .red : .green)
                tile("Aktiv", "\(data["stats"]["active"].int)", "megaphone.fill", .teal)
                tile("24 Std", "\(data["stats"]["posts24h"].int)", "checkmark.circle.fill", .blue)
            }
            if data["stats"]["nextDueAt"].double > 0 { Label("Nächste Erinnerung " + AdsFormat.until(data["stats"]["nextDueAt"].double), systemImage: "clock").font(.subheadline).foregroundStyle(.secondary) }
        }
    }
    private func tile(_ title: String, _ value: String, _ symbol: String, _ color: Color) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Image(systemName: symbol).foregroundStyle(color)
            Text(value).font(.title2.bold().monospacedDigit())
            Text(title).font(.caption).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct SuiteAdHubPlaceRow: View {
    let place: JSON
    let canPost: Bool
    let post: () -> Void
    private var due: Bool { place["state"].string == "due" }
    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            Image(systemName: AdHubFormat.symbol(place["platform"].string)).font(.subheadline).foregroundStyle(.white)
                .frame(width: 34, height: 34).background(AdHubFormat.color(place["platform"].string), in: RoundedRectangle(cornerRadius: 10))
            VStack(alignment: .leading, spacing: 3) {
                Text(place["name"].string).font(.subheadline.weight(.semibold)).lineLimit(1)
                Text(place["platformLabel"].string + " · alle " + place["intervalText"].string + " · " + place["textName"].string).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                Text(status).font(.caption).foregroundStyle(due ? .red : .secondary)
            }
            Spacer(minLength: 4)
            if canPost && place["enabled"].bool {
                Button(action: post) { Label(due ? "Werben" : "Früher", systemImage: "doc.on.doc.fill").labelStyle(.titleAndIcon).font(.caption.weight(.semibold)) }
                    .buttonStyle(.borderedProminent).tint(due ? .red : .gray).controlSize(.small)
                    .accessibilityIdentifier("adhub-post-" + place["id"].string)
            }
        }
        .opacity(place["enabled"].bool ? 1 : 0.6)
    }
    private var status: String {
        if !place["enabled"].bool { return "Pausiert" }
        if due { return "Fällig" + (place["remindCount"].int > 0 ? " · \(place["remindCount"].int)× erinnert" : "") + " · zuletzt " + AdsFormat.ago(place["lastPostedAt"].double) }
        return (place["state"].string == "snoozed" ? "Später: " : "Wieder dran ") + AdsFormat.until(place["dueAt"].double) + " · zuletzt " + AdsFormat.ago(place["lastPostedAt"].double)
    }
}

struct SuiteAdHubTextRow: View {
    let text: JSON
    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(text["name"].string).foregroundStyle(.primary)
            Text(text["formatLabel"].string + " · \(text["length"].int) Zeichen" + (text["usedBy"].array.isEmpty ? " · nicht verwendet" : " · \(text["usedBy"].array.count)× verwendet")).font(.caption).foregroundStyle(.secondary)
        }
    }
}

struct SuiteAdHubSettingsSection: View {
    let data: JSON
    let disabled: Bool
    let save: ([String: JSON]) -> Void
    @ObservedObject private var inbox = AdPostInbox.shared
    var body: some View {
        Section {
            Toggle("Bark-Erinnerungen", isOn: Binding(get: { data["settings"]["remind"].bool }, set: { save(["operation": .string("settings.save"), "remind": .bool($0)]) })).disabled(disabled)
            Picker("Erneut erinnern", selection: Binding(get: { data["settings"]["repeatMinutes"].int }, set: { save(["operation": .string("settings.save"), "repeatMinutes": .number(Double($0))]) })) {
                Text("Nie").tag(0); Text("Nach 15 Min").tag(15); Text("Nach 30 Min").tag(30); Text("Nach 1 Std").tag(60); Text("Nach 2 Std").tag(120); Text("Nach 4 Std").tag(240)
            }.disabled(disabled)
            Picker("Höchstens", selection: Binding(get: { data["settings"]["maxRepeats"].int }, set: { save(["operation": .string("settings.save"), "maxRepeats": .number(Double($0))]) })) {
                ForEach([1, 2, 3, 5, 10], id: \.self) { Text("\($0)× wiederholen").tag($0) }
            }.disabled(disabled)
            Picker("Sicherheitsaufschlag", selection: Binding(get: { Int((data["settings"]["safetyPercent"].double * 10).rounded()) }, set: { save(["operation": .string("settings.save"), "safetyPercent": .number(Double($0) / 10)]) })) {
                Text("Keiner (mind. 1 Min)").tag(0); Text("1 %").tag(10); Text("1,5 % (6 Std → ~6,1 Std)").tag(15); Text("2 %").tag(20); Text("3 %").tag(30); Text("5 %").tag(50)
            }.disabled(disabled)
            Toggle("Bark-Tipp: vor dem Öffnen fragen", isOn: $inbox.askOnOpen)
        } header: { Text("Erinnerungen") } footer: { Text("Bark-Ereignis „Werbe-Zentrale: Werbung fällig“. Ein Tipp auf die Mitteilung öffnet diese App, kopiert den Text in die Zwischenablage und öffnet den Kanal – in Discord einfach einfügen. Mit „vor dem Öffnen fragen“ kannst du stattdessen später erinnern lassen.") }
    }
}

// MARK: - Place editor

struct SuiteAdHubPlaceEditor: View {
    let suite: SuiteAPI
    @State var data: JSON
    let place: JSON?
    let done: (JSON) -> Void
    @State private var link = ""
    @State private var name = ""
    @State private var platform = ""
    @State private var textId = ""
    @State private var interval = 360
    @State private var custom = false
    @State private var customMinutes = "360"
    @State private var lastAgo = 0
    @State private var notifyAll = false
    @State private var note = ""
    @State private var busy = false
    @State private var error: String?
    @State private var newText = false
    @Environment(\.dismiss) private var dismiss
    private var effectivePlatform: String { platform.isEmpty ? AdHubFormat.guess(link) : platform }
    private var platformInfo: JSON { data["platforms"].array.first { $0["id"].string == effectivePlatform } ?? .null }
    private var text: JSON { data["texts"].array.first { $0["id"].string == textId } ?? .null }
    private var sortedTexts: [JSON] { data["texts"].array.sorted { ($0["format"].string == platformInfo["format"].string ? 0 : 1) < ($1["format"].string == platformInfo["format"].string ? 0 : 1) } }
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("https://discord.com/channels/…", text: $link).keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                    Button { if let value = UIPasteboard.general.string { link = value.trimmingCharacters(in: .whitespacesAndNewlines) } } label: { Label("Aus Zwischenablage einfügen", systemImage: "doc.on.clipboard") }
                } header: { Text("Link") } footer: { Text(detect) }
                Section {
                    Picker("Plattform", selection: $platform) {
                        Text("Automatisch").tag("")
                        ForEach(data["platforms"].array, id: \.["id"].string) { p in Text(p["label"].string).tag(p["id"].string) }
                    }
                    TextField("Name, z. B. #werbung-6h · MCAdverts", text: $name)
                }
                Section {
                    Picker("Werbetext", selection: $textId) {
                        ForEach(sortedTexts, id: \.["id"].string) { t in Text(t["name"].string + (t["format"].string == platformInfo["format"].string ? " ✓" : "")).tag(t["id"].string) }
                    }
                    Button { newText = true } label: { Label("Neuer Werbetext für " + platformInfo["label"].string, systemImage: "plus") }
                    if text != .null && text["format"].string != platformInfo["format"].string && platformInfo != .null {
                        Text("Dieser Text ist „\(text["formatLabel"].string)“ – für \(platformInfo["label"].string) passt „\(AdHubFormat.label(platformInfo["format"].string))“ besser. Im Text-Editor kannst du ihn umwandeln.").font(.footnote).foregroundStyle(.orange)
                    }
                }
                Section {
                    Picker("Wie oft darf ich posten?", selection: Binding(get: { custom ? -1 : interval }, set: { if $0 == -1 { custom = true } else { custom = false; interval = $0 } })) {
                        ForEach(AdsFormat.intervals, id: \.0) { Text("Alle " + $0.1).tag($0.0) }
                        Text("Eigenes …").tag(-1)
                    }
                    if custom { TextField("Minuten", text: $customMinutes).keyboardType(.numberPad) }
                    if place == nil {
                        Picker("Zuletzt gepostet", selection: $lastAgo) {
                            Text("Noch nicht – jetzt fällig").tag(0); Text("Vor 30 Min").tag(30); Text("Vor 1 Std").tag(60); Text("Vor 3 Std").tag(180); Text("Vor 6 Std").tag(360); Text("Vor 12 Std").tag(720); Text("Vor 1 Tag").tag(1440)
                        }
                    }
                    Toggle("Alle mit Zugriff erinnern", isOn: $notifyAll)
                } header: { Text("Timer") } footer: { Text("Nach dem Werben startet der Timer neu – immer etwas über dem Intervall (z. B. 6 Std → etwa 6,1 Std).") }
                Section("Notiz") { TextField("z. B. Regeln: nur 1 Post, kein @everyone", text: $note, axis: .vertical) }
                if text != .null {
                    Section("So sieht es aus") { AdHubPreview(text: text["content"].string, format: text["format"].string, platform: effectivePlatform, name: name).listRowInsets(EdgeInsets()) }
                }
            }
            .navigationTitle(place == nil ? "Werbe-Ort hinzufügen" : "Werbe-Ort").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button { Task { await save() } } label: { if busy { ProgressView() } else { Text("Speichern").bold() } }.disabled(busy || link.isEmpty || textId.isEmpty) }
            }
            .errorAlert($error)
            .sheet(isPresented: $newText) { SuiteAdHubTextEditor(suite: suite, data: data, text: nil, preset: platformInfo["format"].string) { overview, id in data = overview; if !id.isEmpty { textId = id } } }
            .task {
                if let place {
                    link = place["link"].string; name = place["name"].string; platform = place["platform"].string; textId = place["textId"].string; note = place["note"].string; notifyAll = place["notifyAll"].bool
                    interval = place["intervalMinutes"].int; custom = !AdsFormat.intervals.contains { $0.0 == interval }; customMinutes = String(interval)
                } else { textId = data["texts"].array.first?["id"].string ?? "" }
            }
            .onChange(of: link) { _, _ in if place == nil, let best = sortedTexts.first, text["format"].string != platformInfo["format"].string, best["format"].string == platformInfo["format"].string { textId = best["id"].string } }
        }
    }
    private var detect: String {
        if link.isEmpty { return "Discord: Rechtsklick bzw. lange auf den Kanal tippen → „Link kopieren“. Reddit, Planet Minecraft, Facebook-Gruppen, Telegram & Co.: einfach die Adresse einfügen." }
        if effectivePlatform == "discord" { return AdHubFormat.discordChannel(link) != nil ? "✓ Discord-Kanal erkannt – öffnet direkt diesen Kanal in der Discord-App." : "Tipp: Ein Kanal-Link (discord.com/channels/…) öffnet direkt den Kanal, ein Einladungslink nur den Server." }
        return "Erkannt: " + platformInfo["label"].string + (platformInfo["limit"].int > 0 ? " · max. \(platformInfo["limit"].int) Zeichen" : "")
    }
    private func save() async {
        busy = true; defer { busy = false }
        let minutes = custom ? (Int(customMinutes) ?? interval) : interval
        do {
            let result = try await suite.post("ad-hub", ["operation": .string("place.save"), "id": .string(place?["id"].string ?? ""), "link": .string(link.trimmingCharacters(in: .whitespacesAndNewlines)), "name": .string(name),
                                                          "platform": .string(platform), "textId": .string(textId), "intervalMinutes": .number(Double(minutes)), "lastPostedMinutesAgo": .number(Double(lastAgo)), "notifyAll": .bool(notifyAll), "note": .string(note)])
            done(result["overview"]); Feedback.success(); dismiss()
        } catch { self.error = error.localizedDescription }
    }
}

// MARK: - Text editor

struct SuiteAdHubTextEditor: View {
    let suite: SuiteAPI
    let data: JSON
    let text: JSON?
    let preset: String?
    let done: (JSON, String) -> Void
    @State private var name = ""
    @State private var content = ""
    @State private var format = "discord"
    @State private var selection: TextSelection?
    @State private var tab = 0
    @State private var busy = false
    @State private var error: String?
    @State private var linkPrompt = false
    @State private var linkURL = "https://"
    @Environment(\.dismiss) private var dismiss
    private var limit: Int { data["platforms"].array.filter { $0["format"].string == format && $0["limit"].int > 0 }.map(\.["limit"].int).min() ?? 0 }
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Name, z. B. Simplex SMP – Reddit", text: $name)
                    Picker("Format", selection: $format) { ForEach(AdHubFormat.formats, id: \.0) { Text($0.1).tag($0.0) } }
                }
                Section {
                    Picker("Ansicht", selection: $tab) { Text("Bearbeiten").tag(0); Text("Vorschau").tag(1) }.pickerStyle(.segmented)
                    if tab == 0 { editor } else { AdHubPreview(text: content, format: format, platform: AdHubFormat.platform(for: format)).listRowInsets(EdgeInsets()) }
                    HStack {
                        Text("\(content.count)" + (limit > 0 ? " / \(limit)" : "") + " Zeichen").font(.caption.monospacedDigit()).foregroundStyle(limit > 0 && content.count > limit ? .red : .secondary)
                        Spacer()
                        Menu { ForEach(AdHubFormat.formats.filter { $0.0 != format }, id: \.0) { item in Button(item.1) { Task { await convert(to: item.0) } } } } label: { Label("Umwandeln", systemImage: "arrow.triangle.2.circlepath") }.font(.caption)
                    }
                } footer: { Text("Platzhalter {online}, {max} und {date} werden beim Kopieren mit echten Werten gefüllt.") }
                Section { Button { Clipboard.copy(content) } label: { Label("Text kopieren", systemImage: "doc.on.doc") } }
            }
            .navigationTitle(text == nil ? "Neuer Werbetext" : "Werbetext").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button { Task { await save() } } label: { if busy { ProgressView() } else { Text("Speichern").bold() } }.disabled(busy || name.trimmingCharacters(in: .whitespaces).isEmpty || content.isEmpty) }
            }
            .errorAlert($error)
            .alert("Link einfügen", isPresented: $linkPrompt) {
                TextField("https://…", text: $linkURL).textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
                Button("Einfügen") { insertLink() }
                Button("Abbrechen", role: .cancel) { }
            }
            .task { if let text { name = text["name"].string; content = text["content"].string; format = text["format"].string.isEmpty ? "discord" : text["format"].string } else if let preset { format = preset } }
        }
    }
    private var editor: some View {
        VStack(alignment: .leading, spacing: 8) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(Array(AdHubFormat.tools(format).enumerated()), id: \.offset) { item in
                        let tool = item.element
                        Button { if let close = tool.3 { wrap(tool.2, close, placeholder: tool.1.lowercased()) } else { prefix(tool.2) } } label: { Image(systemName: tool.0).frame(width: 30, height: 28) }
                            .buttonStyle(.bordered).accessibilityLabel(tool.1)
                    }
                    Button { linkPrompt = true } label: { Image(systemName: "link").frame(width: 30, height: 28) }.buttonStyle(.bordered).accessibilityLabel("Link")
                    Button { insert("{online}") } label: { Image(systemName: "person.2.fill").frame(width: 30, height: 28) }.buttonStyle(.bordered).accessibilityLabel("{online}")
                }
                .padding(.vertical, 2)
            }
            if format == "unicode" { Text("Tipp: Als Discord-Text schreiben und „Umwandeln → Facebook“ tippen – dann entstehen 𝗳𝗲𝘁𝘁𝗲 Unicode-Buchstaben.").font(.caption).foregroundStyle(.secondary) }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 4) {
                    ForEach(["🌍", "🖥️", "📱", "🎮", "👤", "💎", "🛡️", "♾️", "⚡", "🤝", "🔗", "📡", "⭐", "🔥", "✅", "🎉", "⚔️", "🏰"], id: \.self) { emoji in Button(emoji) { insert(emoji) }.buttonStyle(.bordered).font(.title3) }
                }
            }
            TextEditor(text: $content, selection: $selection).font(.system(size: 14, design: .monospaced)).frame(minHeight: 260).textInputAutocapitalization(.never).autocorrectionDisabled()
        }
    }
    private var selectedRange: Range<String.Index>? {
        guard let selection, case .selection(let range) = selection.indices else { return nil }
        guard range.lowerBound >= content.startIndex, range.upperBound <= content.endIndex else { return nil }
        return range
    }
    private func wrap(_ open: String, _ close: String, placeholder: String) {
        if let range = selectedRange { let inner = range.isEmpty ? placeholder : String(content[range]); content.replaceSubrange(range, with: open + inner + close) }
        else { content += open + placeholder + close }
        selection = nil
    }
    private func insert(_ value: String) { if let range = selectedRange { content.replaceSubrange(range, with: value) } else { content += value }; selection = nil }
    private func prefix(_ marker: String) {
        let at = selectedRange?.lowerBound ?? content.endIndex
        let start = content[..<at].lastIndex(of: "\n").map { content.index(after: $0) } ?? content.startIndex
        if content[start...].hasPrefix(marker) { content.removeSubrange(start..<content.index(start, offsetBy: marker.count)) } else { content.insert(contentsOf: marker, at: start) }
        selection = nil
    }
    private func insertLink() {
        guard linkURL.hasPrefix("http") else { return }
        switch format {
        case "bbcode": wrap("[url=\(linkURL)]", "[/url]", placeholder: "Linktext")
        case "discord", "markdown": wrap("[", "](\(linkURL))", placeholder: "Linktext")
        default: insert(linkURL)
        }
    }
    private func convert(to target: String) async {
        do {
            let result = try await suite.post("ad-hub", ["operation": .string("text.convert"), "from": .string(format), "to": .string(target), "content": .string(content)])
            content = result["content"].string; format = target; tab = 1; Feedback.success()
        } catch { self.error = error.localizedDescription }
    }
    private func save() async {
        busy = true; defer { busy = false }
        do {
            let result = try await suite.post("ad-hub", ["operation": .string("text.save"), "id": .string(text?["id"].string ?? ""), "name": .string(name), "content": .string(content), "format": .string(format)])
            done(result["overview"], result["id"].string); Feedback.success(); dismiss()
        } catch { self.error = error.localizedDescription }
    }
}

// MARK: - Launcher for panel://suite-ad-hub (Bark link without a signed place)

struct SuiteAdHubLauncher: View {
    var body: some View { SuiteWorkspaceLauncher(request: SuiteLaunchRequest(tool: .advertising)) }
}

struct SuiteAdHubConnectionView: View {
    let connection: SuiteConnection
    var body: some View {
        SuiteConnectionLoader(connection: connection) { api in SuiteToolDestination(suite: api, tool: .advertising) }
    }
}
