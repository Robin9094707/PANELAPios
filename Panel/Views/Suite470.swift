import SwiftUI
import UIKit

// MARK: - Simplex Panel 4.7.0 / Admin Suite 10.4.0
// Discord-Werbeplaner: linked Discord account, ad texts with Discord formatting and preview, channels per Discord
// server with their own timers. The server schedules and posts everything; the app only edits and shows.

/// Discord message markdown: blocks (headings, -# subtext, quotes, lists, code) and inline styles (unit-tested).
enum DiscordMarkdown {
    indirect enum Block: Equatable {
        case heading(Int, String)
        case subtext(String)
        case line(String)
        case bullet(Int, String)
        case numbered(String, String)
        case code(String)
        case gap
        case quote([Block])
    }

    static func fill(_ text: String, online: String = "7", max: String = "60") -> String {
        let date = Date().formatted(.dateTime.day(.twoDigits).month(.twoDigits).year())
        return text.replacingOccurrences(of: "{online}", with: online).replacingOccurrences(of: "{max}", with: max).replacingOccurrences(of: "{date}", with: date)
    }

    static func blocks(_ text: String) -> [Block] {
        let lines = text.replacingOccurrences(of: "\r\n", with: "\n").components(separatedBy: "\n")
        var out: [Block] = []
        var quote: [Block]? = nil
        var rest = false
        var index = 0
        while index < lines.count {
            var line = lines[index]
            var quoted = rest
            if !rest && line.hasPrefix(">>>") {
                if let q = quote { out.append(.quote(q)) }
                quote = []; rest = true; quoted = true
                line = String(line.dropFirst(3)); if line.hasPrefix(" ") { line.removeFirst() }
            } else if !rest && (line.hasPrefix("> ") || line == ">") {
                if quote == nil { quote = [] }
                quoted = true
                line = line == ">" ? "" : String(line.dropFirst(2))
            } else if !rest, let q = quote {
                out.append(.quote(q)); quote = nil
            }
            let block: Block
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            if trimmed.hasPrefix("```") {
                var code: [String] = []
                let first = String(trimmed.dropFirst(3))
                if let close = first.range(of: "```") {
                    code.append(String(first[..<close.lowerBound]))
                } else {
                    if !first.isEmpty && !isLanguage(first) { code.append(first) }
                    index += 1
                    while index < lines.count {
                        var next = lines[index]
                        if quoted && !rest { if next.hasPrefix("> ") { next = String(next.dropFirst(2)) } else if next == ">" { next = "" } }
                        if let close = next.range(of: "```") { code.append(String(next[..<close.lowerBound])); break }
                        code.append(next)
                        index += 1
                    }
                }
                while let last = code.last, last.isEmpty { code.removeLast() }
                block = .code(code.joined(separator: "\n"))
            } else {
                block = leaf(line)
            }
            if quoted { quote = (quote ?? []) + [block] } else { out.append(block) }
            index += 1
        }
        if let q = quote { out.append(.quote(q)) }
        return out
    }

    private static func isLanguage(_ text: String) -> Bool {
        let value = text.trimmingCharacters(in: .whitespaces)
        return !value.isEmpty && value.count <= 20 && value.allSatisfy { $0.isLetter || $0.isNumber || $0 == "+" || $0 == "#" || $0 == "-" }
    }

    private static func leaf(_ line: String) -> Block {
        if line.hasPrefix("### ") { return .heading(3, String(line.dropFirst(4))) }
        if line.hasPrefix("## ") { return .heading(2, String(line.dropFirst(3))) }
        if line.hasPrefix("# ") { return .heading(1, String(line.dropFirst(2))) }
        if line.hasPrefix("-# ") { return .subtext(String(line.dropFirst(3))) }
        let indent = line.prefix { $0 == " " }.count
        let body = line.dropFirst(indent)
        if body.hasPrefix("- ") || body.hasPrefix("* ") { return .bullet(indent >= 2 ? 1 : 0, String(body.dropFirst(2))) }
        let digits = body.prefix { $0.isNumber }
        if !digits.isEmpty && digits.count <= 3 && body.dropFirst(digits.count).hasPrefix(". ") { return .numbered(String(digits) + ".", String(body.dropFirst(digits.count + 2))) }
        if line.trimmingCharacters(in: .whitespaces).isEmpty { return .gap }
        return .line(line)
    }

    struct Style {
        var size: CGFloat = 15
        var heavy = false
        var bold = false
        var italic = false
        var underline = false
        var strike = false
        var code = false
        var spoiler = false
        var mention = false
        var link: URL? = nil
        func render(_ text: String) -> AttributedString {
            var part = AttributedString(text)
            var font: Font = code ? Font.system(size: size * 0.88, design: .monospaced) : Font.system(size: size, weight: bold || heavy ? .bold : .regular)
            if italic { font = font.italic() }
            part[AttributeScopes.SwiftUIAttributes.FontAttribute.self] = font
            if underline { part[AttributeScopes.SwiftUIAttributes.UnderlineStyleAttribute.self] = Text.LineStyle.single }
            if strike { part[AttributeScopes.SwiftUIAttributes.StrikethroughStyleAttribute.self] = Text.LineStyle.single }
            if code || spoiler { part[AttributeScopes.SwiftUIAttributes.BackgroundColorAttribute.self] = Color(white: 0.11) }
            if mention {
                part[AttributeScopes.SwiftUIAttributes.BackgroundColorAttribute.self] = Color(red: 0.345, green: 0.396, blue: 0.949).opacity(0.3)
                part[AttributeScopes.SwiftUIAttributes.ForegroundColorAttribute.self] = Color(red: 0.79, green: 0.8, blue: 0.98)
            }
            if let link {
                part[AttributeScopes.FoundationAttributes.LinkAttribute.self] = link
                part[AttributeScopes.SwiftUIAttributes.ForegroundColorAttribute.self] = Color(red: 0, green: 0.66, blue: 0.99)
            }
            return part
        }
    }

    /// Inline styles of one line: escapes, code, *** ** __ * _ ~~ ||, links, custom emoji, mentions, timestamps.
    static func inline(_ text: String, size: CGFloat = 15, heavy: Bool = false) -> AttributedString {
        render(Substring(text), Style(size: size, heavy: heavy))
    }

    /// Plain characters of the inline rendering (for tests and accessibility).
    static func plain(_ text: String) -> String { String(inline(text).characters) }

    private static func span(_ rest: Substring, _ open: String, _ close: String) -> (inner: Substring, end: String.Index)? {
        guard rest.hasPrefix(open) else { return nil }
        let body = rest.dropFirst(open.count)
        guard let range = body.range(of: close), range.lowerBound > body.startIndex else { return nil }
        return (body[body.startIndex..<range.lowerBound], range.upperBound)
    }

    private static func render(_ s: Substring, _ style: Style) -> AttributedString {
        var out = AttributedString()
        var buffer = ""
        var i = s.startIndex
        while i < s.endIndex {
            let rest = s[i...]
            let c = s[i]
            var matched: (AttributedString, String.Index)? = nil
            if c == "\\", rest.count > 1 {
                let next = s[s.index(after: i)]
                if !next.isLetter && !next.isNumber && !next.isWhitespace { buffer.append(next); i = s.index(i, offsetBy: 2); continue }
            }
            if c == "`" {
                if let m = span(rest, "``", "``") ?? span(rest, "`", "`") { var st = style; st.code = true; matched = (st.render(String(m.inner)), m.end) }
            } else if c == "*" {
                if let m = span(rest, "***", "***") { var st = style; st.bold = true; st.italic = true; matched = (render(m.inner, st), m.end) }
                else if let m = span(rest, "**", "**") { var st = style; st.bold = true; matched = (render(m.inner, st), m.end) }
                else if let m = span(rest, "*", "*"), let first = m.inner.first, !first.isWhitespace { var st = style; st.italic = true; matched = (render(m.inner, st), m.end) }
            } else if c == "_" {
                if let m = span(rest, "__", "__") { var st = style; st.underline = true; matched = (render(m.inner, st), m.end) }
                else if let m = span(rest, "_", "_") { var st = style; st.italic = true; matched = (render(m.inner, st), m.end) }
            } else if c == "~" {
                if let m = span(rest, "~~", "~~") { var st = style; st.strike = true; matched = (render(m.inner, st), m.end) }
            } else if c == "|" {
                if let m = span(rest, "||", "||") { var st = style; st.spoiler = true; matched = (render(m.inner, st), m.end) }
            } else if c == "[" {
                matched = maskedLink(rest, style)
            } else if c == "<" {
                matched = angle(rest, style)
            } else if c == "h", rest.hasPrefix("https://") || rest.hasPrefix("http://") {
                var end = rest.firstIndex { $0.isWhitespace || $0 == "<" } ?? rest.endIndex
                while end > rest.startIndex, ".,:;\"')]!?".contains(rest[rest.index(before: end)]) { end = rest.index(before: end) }
                let raw = String(rest[rest.startIndex..<end])
                if let url = URL(string: raw) { var st = style; st.link = url; matched = (st.render(raw), end) }
            }
            if let match = matched {
                if !buffer.isEmpty { out.append(style.render(buffer)); buffer = "" }
                out.append(match.0)
                i = match.1
            } else {
                buffer.append(c)
                i = s.index(after: i)
            }
        }
        if !buffer.isEmpty { out.append(style.render(buffer)) }
        return out
    }

    private static func maskedLink(_ rest: Substring, _ style: Style) -> (AttributedString, String.Index)? {
        guard let close = rest.range(of: "]("), close.lowerBound > rest.index(after: rest.startIndex) else { return nil }
        let label = rest[rest.index(after: rest.startIndex)..<close.lowerBound]
        guard !label.contains("\n"), let end = rest[close.upperBound...].firstIndex(of: ")") else { return nil }
        var target = String(rest[close.upperBound..<end])
        if target.hasPrefix("<") && target.hasSuffix(">") { target = String(target.dropFirst().dropLast()) }
        guard target.hasPrefix("http"), let url = URL(string: target) else { return nil }
        var st = style; st.link = url
        return (render(label, st), rest.index(after: end))
    }

    private static func angle(_ rest: Substring, _ style: Style) -> (AttributedString, String.Index)? {
        guard let close = rest.firstIndex(of: ">") else { return nil }
        let inner = String(rest[rest.index(after: rest.startIndex)..<close])
        let end = rest.index(after: close)
        var st = style
        if inner.hasPrefix("http"), let url = URL(string: inner) { st.link = url; return (st.render(inner), end) }
        let parts = inner.split(separator: ":", omittingEmptySubsequences: false).map(String.init)
        if parts.count == 3, parts[0].isEmpty || parts[0] == "a", parts[2].allSatisfy(\.isNumber), !parts[2].isEmpty { return (style.render(":" + parts[1] + ":"), end) }
        if parts.count >= 2, parts[0] == "t", let seconds = Double(parts[1]) {
            st.mention = true
            return (st.render(Date(timeIntervalSince1970: seconds).formatted(date: .abbreviated, time: .shortened)), end)
        }
        st.mention = true
        if inner.hasPrefix("@&") { return (st.render("@Rolle"), end) }
        if inner.hasPrefix("@") { return (st.render("@Benutzer"), end) }
        if inner.hasPrefix("#") { return (st.render("#Kanal"), end) }
        return nil
    }
}

/// Small formatting helpers for the planner (unit-tested).
enum AdsFormat {
    static func human(_ minutes: Int) -> String {
        if minutes >= 1440 && minutes % 1440 == 0 { return minutes == 1440 ? "1 Tag" : "\(minutes / 1440) Tage" }
        if minutes >= 60 && minutes % 60 == 0 { return "\(minutes / 60) Std" }
        if minutes > 60 { return String(format: "%.1f Std", Double(minutes) / 60).replacingOccurrences(of: ".", with: ",") }
        return "\(minutes) Min"
    }
    static func hours(_ minutes: Double) -> String { String(format: "%.2f Std", minutes / 60).replacingOccurrences(of: ".", with: ",") }
    static func window(from: Double, to: Double) -> String {
        if to > 120 { return "\(hours(from)) – \(hours(to))" }
        return String(format: "%.1f – %.1f Min", from, to).replacingOccurrences(of: ".", with: ",")
    }
    static func until(_ millis: Double, now: Date = Date()) -> String {
        if millis <= 0 { return "–" }
        let seconds = millis / 1000 - now.timeIntervalSince1970
        if seconds <= 0 { return "jetzt" }
        if seconds < 3600 { return "in \(max(1, Int((seconds / 60).rounded()))) Min" }
        if seconds < 86400 { return "in " + String(format: "%.1f Std", seconds / 3600).replacingOccurrences(of: ".", with: ",") }
        return date(millis)
    }
    static func date(_ millis: Double) -> String {
        millis <= 0 ? "–" : Date(timeIntervalSince1970: millis / 1000).formatted(date: .abbreviated, time: .shortened)
    }
    static let intervals: [(Int, String)] = [(5, "5 Min"), (15, "15 Min"), (30, "30 Min"), (60, "1 Std"), (120, "2 Std"), (180, "3 Std"), (360, "6 Std"), (720, "12 Std"), (1440, "24 Std"), (2880, "48 Std"), (10080, "7 Tage")]
    static func result(_ kind: String) -> (String, Color)? {
        switch kind {
        case "sent": return ("Gesendet", .green)
        case "slowmode": return ("Slowmode – neu geplant", .blue)
        case "ratelimit": return ("Discord bremst", .blue)
        case "retry": return ("Neuer Versuch", .orange)
        case "deleted": return ("Vom Server gelöscht", .red)
        case "permission": return ("Keine Rechte", .red)
        case "invalid": return ("Text abgelehnt", .red)
        case "gone": return ("Kanal weg", .red)
        case "failed": return ("Fehlgeschlagen", .red)
        case "account": return ("Account-Problem", .red)
        case "created": return ("Geplant", .teal)
        case "edited": return ("Geändert", .teal)
        case "paused": return ("Pausiert", .orange)
        case "resumed": return ("Fortgesetzt", .green)
        case "postponed": return ("Verschoben", .blue)
        case "info": return ("Info", .secondary)
        default: return nil
        }
    }
    static func account(_ status: String) -> (String, Color) {
        switch status {
        case "ok": return ("Verbunden", .green)
        case "invalid": return ("Token ungültig", .red)
        case "captcha": return ("Captcha nötig", .orange)
        case "verify": return ("Bestätigung nötig", .orange)
        case "locked": return ("Neu verknüpfen", .red)
        case "missing": return ("Abgemeldet", .red)
        default: return ("Fehler", .orange)
        }
    }
}

struct AdsItem: Identifiable {
    let id: String
    let json: JSON
}

// MARK: - Preview

struct DiscordMessagePreview: View {
    let content: String
    let name: String
    let avatar: String
    let channel: String
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 6) {
                Text("#").font(.title3).foregroundStyle(.secondary)
                Text(channel).font(.subheadline.weight(.semibold))
                Spacer()
            }
            .padding(.horizontal, 12).padding(.vertical, 8)
            .background(Color(red: 0.169, green: 0.176, blue: 0.192))
            HStack(alignment: .top, spacing: 10) {
                avatarView
                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 6) {
                        Text(name).font(.subheadline.weight(.semibold))
                        Text("Heute").font(.caption2).foregroundStyle(.secondary)
                    }
                    DiscordMarkdownView(blocks: DiscordMarkdown.blocks(DiscordMarkdown.fill(content)))
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(12)
        }
        .background(Color(red: 0.192, green: 0.2, blue: 0.22))
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .environment(\.colorScheme, .dark)
    }
    @ViewBuilder private var avatarView: some View {
        if let url = URL(string: avatar), !avatar.isEmpty {
            AsyncImage(url: url) { image in image.resizable().scaledToFill() } placeholder: { fallback }
                .frame(width: 38, height: 38).clipShape(Circle())
        } else { fallback }
    }
    private var fallback: some View {
        Circle().fill(Color(red: 0.345, green: 0.396, blue: 0.949)).frame(width: 38, height: 38)
            .overlay { Text(String(name.prefix(1)).uppercased()).font(.headline).foregroundStyle(.white) }
    }
}

struct DiscordMarkdownView: View {
    let blocks: [DiscordMarkdown.Block]
    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            ForEach(Array(blocks.enumerated()), id: \.offset) { item in
                DiscordBlockView(block: item.element)
            }
        }
    }
}

struct DiscordBlockView: View {
    let block: DiscordMarkdown.Block
    var body: some View {
        if case .quote(let inner) = block {
            HStack(alignment: .top, spacing: 10) {
                RoundedRectangle(cornerRadius: 2).fill(Color(white: 0.32)).frame(width: 4)
                VStack(alignment: .leading, spacing: 2) {
                    ForEach(Array(inner.enumerated()), id: \.offset) { item in DiscordLeafView(block: item.element) }
                }
            }
            .fixedSize(horizontal: false, vertical: true)
        } else {
            DiscordLeafView(block: block)
        }
    }
}

struct DiscordLeafView: View {
    let block: DiscordMarkdown.Block
    var body: some View {
        switch block {
        case .heading(let level, let text):
            Text(DiscordMarkdown.inline(text, size: level == 1 ? 22 : level == 2 ? 19 : 16, heavy: true)).padding(.top, 2)
        case .subtext(let text):
            Text(DiscordMarkdown.inline(text, size: 12)).foregroundStyle(.secondary)
        case .line(let text):
            Text(DiscordMarkdown.inline(text))
        case .bullet(let level, let text):
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Text(level > 0 ? "◦" : "•")
                Text(DiscordMarkdown.inline(text))
            }
            .padding(.leading, CGFloat(level * 16 + 4))
        case .numbered(let number, let text):
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Text(number)
                Text(DiscordMarkdown.inline(text))
            }
            .padding(.leading, 4)
        case .code(let text):
            Text(text).font(.system(size: 13, design: .monospaced)).padding(8)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color(white: 0.13), in: RoundedRectangle(cornerRadius: 4))
        case .gap:
            Color.clear.frame(height: 8)
        case .quote:
            EmptyView()
        }
    }
}

// MARK: - Main view

struct SuiteDiscordAdsView: View {
    let suite: SuiteAPI
    @State private var data: JSON = .null
    @State private var error: String?
    @State private var notice: String?
    @State private var linking = false
    @State private var relink: AdsItem?
    @State private var unlink: AdsItem?
    @State private var editing: AdsItem?
    @State private var newTemplate = false
    @State private var wizard = false
    private var canManage: Bool { data["canManage"].bool }
    private var accounts: [JSON] { data["accounts"].array }
    var body: some View {
        List {
            if data == .null { Section { ProgressView("Werbeplaner laden …") } }
            else {
                SuiteAdsStatsSection(data: data)
                accountsSection
                campaignsSections
                templatesSection
                SuiteAdsSettingsSection(data: data, disabled: !canManage) { patch in Task { await run(patch) } }
                logSection
                Section { Text("Automatisches Posten mit einem Nutzer-Account verstößt gegen die Discord-Nutzungsbedingungen. Poste nur dort, wo Werbung ausdrücklich erlaubt ist, und halte die Kanal-Timer ein.").font(.footnote).foregroundStyle(.secondary) }
            }
        }
        .panelScreen()
        .navigationTitle("Discord-Werbung")
        .toolbar { toolbar }
        .task { await load() }
        .task { while !Task.isCancelled { try? await Task.sleep(for: .seconds(30)); if !Task.isCancelled && !wizard && editing == nil && !newTemplate { await load() } } }
        .refreshable { await load() }
        .errorAlert($error)
        .alert(notice ?? "", isPresented: Binding(get: { notice != nil }, set: { if !$0 { notice = nil } })) { Button("OK") { notice = nil } }
        .sheet(isPresented: $linking) { SuiteAdsLinkView(suite: suite, account: nil) { apply($0) } }
        .sheet(item: $relink) { item in SuiteAdsLinkView(suite: suite, account: item.json) { apply($0) } }
        .sheet(item: $editing) { item in SuiteAdsTemplateEditor(suite: suite, template: item.json, account: accounts.first ?? .null, limit: data["limits"]["discord"].int) { apply($0) } }
        .sheet(isPresented: $newTemplate) { SuiteAdsTemplateEditor(suite: suite, template: nil, account: accounts.first ?? .null, limit: data["limits"]["discord"].int) { apply($0) } }
        .sheet(isPresented: $wizard) { SuiteAdsWizard(suite: suite, data: data, existing: nil) { apply($0) } }
        .sheet(item: $unlink) { item in SuiteAdsUnlinkView(suite: suite, account: item.json) { apply($0) } }
    }
    @ToolbarContentBuilder private var toolbar: some ToolbarContent {
        if canManage && !accounts.isEmpty {
            ToolbarItem(placement: .topBarTrailing) {
                Button { wizard = true } label: { Label("Kanal planen", systemImage: "paperplane.fill") }
            }
        }
    }
    private var accountsSection: some View {
        Section {
            if accounts.isEmpty {
                Text("Noch kein Discord-Account verknüpft. Verknüpfe einen Zweitaccount, mit dem gepostet wird.").foregroundStyle(.secondary)
            }
            ForEach(accounts, id: \.["id"].string) { account in
                SuiteAdsAccountRow(account: account, canManage: canManage,
                                   check: { Task { await run(["operation": .string("check"), "account": account["id"]]) } },
                                   renew: { relink = AdsItem(id: account["id"].string, json: account) },
                                   remove: { unlink = AdsItem(id: account["id"].string, json: account) })
            }
            if canManage && accounts.count < max(1, data["limits"]["accounts"].int) {
                Button { linking = true } label: { Label(accounts.isEmpty ? "Discord-Account verknüpfen" : "Weiteren Account verknüpfen", systemImage: "link.badge.plus") }
            }
        } header: { Text("Discord-Account") } footer: { Text("Der Token liegt nur verschlüsselt auf dem Minecraft-Server und wird nie wieder angezeigt.") }
    }
    private var guildIds: [String] {
        var seen: [String] = []
        for c in data["campaigns"].array where !seen.contains(c["guild"].string) { seen.append(c["guild"].string) }
        return seen
    }
    @ViewBuilder private var campaignsSections: some View {
        if data["campaigns"].array.isEmpty {
            Section {
                Text(accounts.isEmpty ? "Zuerst einen Discord-Account verknüpfen." : "Noch keine Kanäle geplant. Wähle einen Discord-Server und bis zu \(data["settings"]["maxPerGuild"].int) Werbekanäle mit eigenem Timer.").foregroundStyle(.secondary)
                if canManage && !accounts.isEmpty { Button { wizard = true } label: { Label("Kanal planen", systemImage: "paperplane.fill") } }
            } header: { Text("Geplante Kanäle") }
        }
        ForEach(guildIds, id: \.self) { gid in
            let rows = data["campaigns"].array.filter { $0["guild"].string == gid }
            Section {
                ForEach(rows, id: \.["id"].string) { campaign in
                    NavigationLink { SuiteAdsCampaignView(suite: suite, data: data, id: campaign["id"].string) { apply($0) } } label: { SuiteAdsCampaignRow(campaign: campaign) }
                }
            } header: {
                SuiteAdsGuildHeader(name: rows.first?["guildName"].string ?? "", icon: rows.first?["guildIcon"].string ?? "", detail: "\(rows.count) / \(data["settings"]["maxPerGuild"].int) Kanäle")
            }
        }
    }
    private var templatesSection: some View {
        Section {
            ForEach(data["templates"].array, id: \.["id"].string) { template in
                Button { editing = AdsItem(id: template["id"].string, json: template) } label: { SuiteAdsTemplateRow(template: template, limit: data["limits"]["discord"].int) }
                    .contextMenu {
                        Button { Clipboard.copy(template["content"].string) } label: { Label("Text kopieren", systemImage: "doc.on.doc") }
                        if canManage && template["usedBy"].array.isEmpty {
                            Button(role: .destructive) { Task { await run(["operation": .string("template.delete"), "id": template["id"]]) } } label: { Label("Löschen", systemImage: "trash") }
                        }
                    }
            }
            if canManage { Button { newTemplate = true } label: { Label("Neuer Werbetext", systemImage: "plus") } }
        } header: { Text("Werbetexte") } footer: { Text("Mit Discord-Formatierung und Vorschau. Lange drücken zum Kopieren oder Löschen; jeder Kanal kann einen anderen Text nutzen.") }
    }
    private var logSection: some View {
        Section("Verlauf") {
            if data["log"].array.isEmpty { Text("Noch nichts passiert.").foregroundStyle(.secondary) }
            ForEach(Array(data["log"].array.prefix(15).enumerated()), id: \.offset) { item in SuiteAdsHistoryRow(entry: item.element) }
        }
    }
    private func apply(_ overview: JSON) { if overview != .null { data = overview } }
    private func load() async {
        do { data = try await suite.request("discord-ads") }
        catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }
    private func run(_ body: [String: JSON]) async {
        do {
            let result = try await suite.post("discord-ads", body)
            apply(result["overview"])
            if !result["notice"].string.isEmpty { notice = result["notice"].string }
            Feedback.success()
        } catch { self.error = error.localizedDescription }
    }
}

struct SuiteAdsStatsSection: View {
    let data: JSON
    var body: some View {
        Section {
            HStack(spacing: 10) {
                tile("Aktiv", "\(data["stats"]["active"].int)", "paperplane.fill", .green)
                tile("Pausiert", "\(data["stats"]["paused"].int)", "pause.circle.fill", .orange)
                tile("24 h", "\(data["stats"]["sent24h"].int)", "checkmark.circle.fill", .teal)
                tile("Fehler", "\(data["stats"]["failed24h"].int)", "exclamationmark.triangle.fill", data["stats"]["failed24h"].int > 0 ? .red : .secondary)
            }
            if data["stats"]["nextRunAt"].double > 0 {
                LabeledContent("Nächster Post", value: "\(AdsFormat.until(data["stats"]["nextRunAt"].double)) · \(AdsFormat.date(data["stats"]["nextRunAt"].double))")
            }
            if !data["problem"].string.isEmpty { Label("Planer meldet: \(data["problem"].string)", systemImage: "exclamationmark.triangle.fill").foregroundStyle(.orange) }
        }
    }
    private func tile(_ title: String, _ value: String, _ symbol: String, _ tint: Color) -> some View {
        VStack(spacing: 4) {
            Image(systemName: symbol).foregroundStyle(tint)
            Text(value).font(.title3.weight(.bold)).monospacedDigit()
            Text(title).font(.caption2).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
    }
}

struct SuiteAdsGuildHeader: View {
    let name: String
    let icon: String
    let detail: String
    var body: some View {
        HStack(spacing: 8) {
            SuiteAdsGuildIcon(name: name, url: icon, size: 22)
            Text(name)
            Spacer()
            Text(detail).font(.caption2)
        }
    }
}

struct SuiteAdsGuildIcon: View {
    let name: String
    let url: String
    let size: CGFloat
    var body: some View {
        if let link = URL(string: url), !url.isEmpty {
            AsyncImage(url: link) { image in image.resizable().scaledToFill() } placeholder: { fallback }
                .frame(width: size, height: size).clipShape(RoundedRectangle(cornerRadius: size * 0.3))
        } else { fallback }
    }
    private var fallback: some View {
        RoundedRectangle(cornerRadius: size * 0.3).fill(Color(red: 0.345, green: 0.396, blue: 0.949)).frame(width: size, height: size)
            .overlay { Text(String(name.prefix(2)).uppercased()).font(.system(size: size * 0.36, weight: .bold)).foregroundStyle(.white) }
    }
}

struct SuiteAdsAccountRow: View {
    let account: JSON
    let canManage: Bool
    let check: () -> Void
    let renew: () -> Void
    let remove: () -> Void
    var body: some View {
        let state = AdsFormat.account(account["status"].string)
        HStack(spacing: 12) {
            AsyncImage(url: URL(string: account["avatar"].string)) { image in image.resizable().scaledToFill() } placeholder: { Circle().fill(.quaternary) }
                .frame(width: 40, height: 40).clipShape(Circle())
            VStack(alignment: .leading, spacing: 2) {
                Text(account["name"].string).font(.headline)
                Text("@\(account["username"].string) · \(account["campaigns"].int) Kanäle · \(account["sent"].int) Posts").font(.caption).foregroundStyle(.secondary)
                Text(state.0).font(.caption.weight(.semibold)).foregroundStyle(state.1)
                if !account["statusText"].string.isEmpty { Text(account["statusText"].string).font(.caption).foregroundStyle(.secondary) }
            }
            Spacer(minLength: 4)
            if canManage {
                Menu {
                    Button(action: check) { Label("Erneut prüfen", systemImage: "arrow.clockwise") }
                    Button(action: renew) { Label("Token erneuern", systemImage: "key.fill") }
                    Button(role: .destructive, action: remove) { Label("Abmelden", systemImage: "rectangle.portrait.and.arrow.right") }
                } label: { Image(systemName: "ellipsis.circle").font(.title3) }
            }
        }
    }
}

struct SuiteAdsCampaignRow: View {
    let campaign: JSON
    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Text("#").font(.title2.weight(.light)).foregroundStyle(.secondary)
            VStack(alignment: .leading, spacing: 3) {
                Text(campaign["channelName"].string).font(.headline)
                Text("„\(campaign["templateName"].string)“ · alle \(AdsFormat.human(campaign["intervalMinutes"].int))").font(.caption).foregroundStyle(.secondary)
                statusLine
            }
        }
        .opacity(campaign["enabled"].bool ? 1 : 0.7)
    }
    @ViewBuilder private var statusLine: some View {
        if !campaign["enabled"].bool {
            Label(campaign["pausedReason"].string.isEmpty ? "Pausiert" : campaign["pausedReason"].string, systemImage: "pause.circle.fill").font(.caption).foregroundStyle(.orange)
        } else if campaign["state"].string == "account" {
            Label("Account prüfen – wird nicht gesendet", systemImage: "exclamationmark.triangle.fill").font(.caption).foregroundStyle(.red)
        } else {
            Label("Nächster Post \(AdsFormat.until(campaign["nextRunAt"].double))", systemImage: "clock").font(.caption).foregroundStyle(.teal)
        }
        if let result = AdsFormat.result(campaign["lastResult"].string), campaign["lastResult"].string != "sent" {
            Text(result.0).font(.caption2.weight(.semibold)).foregroundStyle(result.1)
        }
    }
}

struct SuiteAdsTemplateRow: View {
    let template: JSON
    let limit: Int
    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(template["name"].string).font(.headline).foregroundStyle(.primary)
            Text(detail).font(.caption).foregroundStyle(template["length"].int > limit ? .orange : .secondary)
        }
    }
    private var detail: String {
        let used = template["usedBy"].array.map { "#" + $0.string }
        let usage = used.isEmpty ? "nicht verwendet" : "in " + used.joined(separator: ", ")
        let nitro = template["length"].int > limit ? " (nur mit Nitro)" : ""
        return "\(template["length"].int) Zeichen\(nitro) · \(usage)"
    }
}

struct SuiteAdsHistoryRow: View {
    let entry: JSON
    var body: some View {
        let kind = AdsFormat.result(entry["kind"].string) ?? (entry["kind"].string, Color.secondary)
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                Text(kind.0).font(.caption.weight(.semibold)).foregroundStyle(kind.1)
                Spacer()
                Text(AdsFormat.date(entry["time"].double)).font(.caption2).foregroundStyle(.secondary)
            }
            Text(entry["text"].string).font(.footnote)
        }
    }
}

struct SuiteAdsSettingsSection: View {
    let data: JSON
    let disabled: Bool
    let save: ([String: JSON]) -> Void
    private var settings: JSON { data["settings"] }
    var body: some View {
        Section {
            Toggle("Bark-Hinweise bei Fehlern und Pausen", isOn: binding("notify"))
            Toggle("Nach 3 Min prüfen, ob der Post noch da ist", isOn: binding("verifyDelete"))
            Toggle("Vorher nach eigenem letzten Post suchen", isOn: binding("checkOwnPosts"))
            Picker("Automatisch pausieren (Standard)", selection: number("pauseAfter")) {
                Text("Nie").tag(0); Text("Nach 1 Fehler").tag(1); Text("Nach 2 Fehlern").tag(2); Text("Nach 3 Fehlern").tag(3); Text("Nach 5 Fehlern").tag(5)
            }
            Picker("Abstand zwischen zwei Posts", selection: number("gapSeconds")) {
                Text("30 Sekunden").tag(30); Text("1 Minute").tag(60); Text("2 Minuten").tag(120); Text("5 Minuten").tag(300); Text("10 Minuten").tag(600)
            }
            Picker("Kanäle pro Discord-Server", selection: number("maxPerGuild")) {
                ForEach([1, 2, 3, 4, 6, 8, 10], id: \.self) { Text("\($0)").tag($0) }
            }
        } header: { Text("Einstellungen") } footer: { Text("Bark: „Discord-Werbung: Fehler & Pausen“ ist ohne eigene Regel aktiv und lässt sich in den Bark-Einstellungen anpassen.") }
        .disabled(disabled)
    }
    private func binding(_ key: String) -> Binding<Bool> {
        Binding(get: { settings[key].bool }, set: { save(["operation": .string("settings.save"), key: .bool($0)]) })
    }
    private func number(_ key: String) -> Binding<Int> {
        Binding(get: { settings[key].int }, set: { save(["operation": .string("settings.save"), key: .number(Double($0))]) })
    }
}

// MARK: - Campaign detail

struct SuiteAdsCampaignView: View {
    let suite: SuiteAPI
    @State var data: JSON
    let id: String
    let changed: (JSON) -> Void
    @State private var error: String?
    @State private var notice: String?
    @State private var editing = false
    @State private var confirmDelete = false
    @State private var confirmSend = false
    @Environment(\.dismiss) private var dismiss
    private var campaign: JSON { data["campaigns"].array.first { $0["id"].string == id } ?? .null }
    private var canManage: Bool { data["canManage"].bool }
    var body: some View {
        List {
            if campaign == .null { Section { Text("Diese Planung gibt es nicht mehr.").foregroundStyle(.secondary) } }
            else {
                overview
                if canManage { actions }
                Section("Verlauf") {
                    if campaign["history"].array.isEmpty { Text("Noch kein Verlauf.").foregroundStyle(.secondary) }
                    ForEach(Array(campaign["history"].array.enumerated()), id: \.offset) { item in SuiteAdsHistoryRow(entry: item.element) }
                }
            }
        }
        .panelScreen()
        .navigationTitle("#" + campaign["channelName"].string)
        .navigationBarTitleDisplayMode(.inline)
        .errorAlert($error)
        .alert(notice ?? "", isPresented: Binding(get: { notice != nil }, set: { if !$0 { notice = nil } })) { Button("OK") { notice = nil } }
        .sheet(isPresented: $editing) { SuiteAdsWizard(suite: suite, data: data, existing: campaign) { update($0) } }
        .confirmationDialog("Planung löschen?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Löschen", role: .destructive) { Task { await run("campaign.delete"); dismiss() } }
        } message: { Text("#\(campaign["channelName"].string) wird nicht mehr beworben. Gesendete Posts bleiben in Discord.") }
        .confirmationDialog("Jetzt senden?", isPresented: $confirmSend, titleVisibility: .visible) {
            Button("Senden") { Task { await run("campaign.send-now") } }
        } message: { Text("Die Werbung wird in wenigen Sekunden in #\(campaign["channelName"].string) gepostet. Danach läuft der Timer neu.") }
    }
    private var overview: some View {
        Section {
            HStack(spacing: 10) {
                SuiteAdsGuildIcon(name: campaign["guildName"].string, url: campaign["guildIcon"].string, size: 40)
                VStack(alignment: .leading, spacing: 2) {
                    Text(campaign["guildName"].string).font(.caption).foregroundStyle(.secondary)
                    Text("#" + campaign["channelName"].string).font(.title3.weight(.bold))
                }
            }
            LabeledContent("Werbetext", value: campaign["templateName"].string)
            LabeledContent("Intervall", value: AdsFormat.human(campaign["intervalMinutes"].int))
            if campaign["effectiveMinutes"].int > campaign["intervalMinutes"].int { LabeledContent("Kanal-Slowmode", value: AdsFormat.human(campaign["effectiveMinutes"].int)) }
            LabeledContent("Abstand tatsächlich", value: AdsFormat.window(from: campaign["windowFromMinutes"].double, to: campaign["windowToMinutes"].double))
            LabeledContent("Nächster Post", value: campaign["enabled"].bool ? "\(AdsFormat.until(campaign["nextRunAt"].double)) · \(AdsFormat.date(campaign["nextRunAt"].double))" : "pausiert")
            LabeledContent("Zuletzt gesendet", value: AdsFormat.date(campaign["lastSentAt"].double))
            LabeledContent("Gesendet / Fehler", value: "\(campaign["sentCount"].int) / \(campaign["failedCount"].int)")
            if !campaign["enabled"].bool && !campaign["pausedReason"].string.isEmpty { Label(campaign["pausedReason"].string, systemImage: "pause.circle.fill").foregroundStyle(.orange) }
            if !campaign["lastError"].string.isEmpty && campaign["lastResult"].string != "sent" { Label(campaign["lastError"].string, systemImage: "exclamationmark.triangle.fill").foregroundStyle(.red).font(.footnote) }
            if let url = URL(string: campaign["link"].string) { Link(destination: url) { Label("In Discord öffnen", systemImage: "arrow.up.right.square") } }
        }
    }
    private var actions: some View {
        Section {
            Button { editing = true } label: { Label("Text & Timer bearbeiten", systemImage: "slider.horizontal.3") }
            if campaign["enabled"].bool {
                Button { Task { await run("campaign.pause") } } label: { Label("Pausieren", systemImage: "pause.fill") }
                Button { confirmSend = true } label: { Label(sendTitle, systemImage: "paperplane.fill") }.disabled(campaign["earliestManual"].double / 1000 > Date().timeIntervalSince1970)
            } else {
                Button { Task { await run("campaign.resume") } } label: { Label("Fortsetzen", systemImage: "play.fill") }
            }
            Button(role: .destructive) { confirmDelete = true } label: { Label("Planung löschen", systemImage: "trash") }
        }
    }
    private var sendTitle: String {
        let earliest = campaign["earliestManual"].double
        return earliest / 1000 > Date().timeIntervalSince1970 ? "Frühestens \(AdsFormat.date(earliest))" : "Jetzt senden"
    }
    private func update(_ overview: JSON) { if overview != .null { data = overview; changed(overview) } }
    private func run(_ operation: String) async {
        do {
            let result = try await suite.post("discord-ads", ["operation": .string(operation), "id": .string(id)])
            update(result["overview"])
            if !result["notice"].string.isEmpty && operation != "campaign.delete" { notice = result["notice"].string }
            Feedback.success()
        } catch { self.error = error.localizedDescription }
    }
}

// MARK: - Link / unlink

struct SuiteAdsLinkView: View {
    let suite: SuiteAPI
    let account: JSON?
    let done: (JSON) -> Void
    @State private var token = ""
    @State private var accepted = false
    @State private var busy = false
    @State private var error: String?
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Label("Automatisches Posten mit einem normalen Discord-Account (Self-Bot) verstößt gegen die Discord-Nutzungsbedingungen. Discord kann den Account sperren – verwende einen Zweitaccount.", systemImage: "exclamationmark.octagon.fill")
                        .foregroundStyle(.red).font(.footnote)
                }
                if let account { Section { Text("Token für „\(account["name"].string)“ erneuern. Er muss zum selben Discord-Account gehören; alle Kanäle bleiben erhalten.").font(.footnote) } }
                Section {
                    SecureField("Discord-Token des Zweitaccounts", text: $token).textInputAutocapitalization(.never).autocorrectionDisabled()
                    DisclosureGroup("Wie bekomme ich den Token?") {
                        Text("1. Mit dem Zweitaccount am Computer im Browser auf discord.com/app anmelden.\n2. Entwicklertools (F12) → Netzwerk → einen Kanal anklicken.\n3. Eine Anfrage an discord.com/api wählen → Anfrage-Header → Wert von „Authorization“ kopieren.\n4. Den Token nie weitergeben – wer ihn hat, kann den Account benutzen. Passwort ändern macht ihn ungültig.")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                } footer: { Text("Wird verschlüsselt auf dem Minecraft-Server gespeichert und nie wieder angezeigt.") }
                if account == nil {
                    Section { Toggle("Ich verstehe, dass der Account gesperrt werden kann, und poste nur, wo Werbung ausdrücklich erlaubt ist.", isOn: $accepted).font(.footnote) }
                }
                Section {
                    Button { Task { await submit() } } label: { if busy { ProgressView() } else { Label(account == nil ? "Prüfen & verknüpfen" : "Prüfen & erneuern", systemImage: "checkmark.shield") } }
                        .disabled(busy || token.count < 20 || (account == nil && !accepted))
                }
            }
            .navigationTitle(account == nil ? "Account verknüpfen" : "Token erneuern")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } } }
            .errorAlert($error)
        }
    }
    private func submit() async {
        busy = true; defer { busy = false }
        var body: [String: JSON] = ["token": .string(token)]
        if let account { body["operation"] = .string("relink"); body["account"] = account["id"] } else { body["operation"] = .string("link"); body["accept"] = .bool(true) }
        do {
            let result = try await suite.post("discord-ads", body)
            token = ""
            done(result["overview"]); Feedback.success(); dismiss()
        } catch { self.error = error.localizedDescription }
    }
}

struct SuiteAdsUnlinkView: View {
    let suite: SuiteAPI
    let account: JSON
    let done: (JSON) -> Void
    @State private var logout = true
    @State private var deleteCampaigns = false
    @State private var busy = false
    @State private var error: String?
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("„\(account["name"].string)“ wird vom Server entfernt. Geplante Kanäle werden pausiert und laufen weiter, sobald du denselben Account wieder verknüpfst.").font(.footnote)
                    Toggle("Auch bei Discord abmelden (Token wird ungültig)", isOn: $logout)
                    if account["campaigns"].int > 0 { Toggle("Geplante Kanäle (\(account["campaigns"].int)) löschen", isOn: $deleteCampaigns) }
                }
                Section {
                    Button(role: .destructive) { Task { await submit() } } label: { if busy { ProgressView() } else { Label("Abmelden", systemImage: "rectangle.portrait.and.arrow.right") } }.disabled(busy)
                }
            }
            .navigationTitle("Account abmelden")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } } }
            .errorAlert($error)
        }
        .presentationDetents([.medium, .large])
    }
    private func submit() async {
        busy = true; defer { busy = false }
        do {
            let result = try await suite.post("discord-ads", ["operation": .string("unlink"), "account": account["id"], "logout": .bool(logout), "deleteCampaigns": .bool(deleteCampaigns)])
            done(result["overview"]); Feedback.success(); dismiss()
        } catch { self.error = error.localizedDescription }
    }
}

// MARK: - Template editor

struct SuiteAdsTemplateEditor: View {
    let suite: SuiteAPI
    let template: JSON?
    let account: JSON
    let limit: Int
    let done: (JSON) -> Void
    @State private var name = ""
    @State private var content = ""
    @State private var suppress = false
    @State private var selection: TextSelection?
    @State private var tab = 0
    @State private var busy = false
    @State private var error: String?
    @State private var linkPrompt = false
    @State private var linkURL = "https://"
    @Environment(\.dismiss) private var dismiss
    private var discordLimit: Int { max(1, limit) }
    var body: some View {
        NavigationStack {
            Form {
                Section { TextField("Name, z. B. Simplex SMP – Discord", text: $name) }
                Section {
                    Picker("Ansicht", selection: $tab) { Text("Bearbeiten").tag(0); Text("Vorschau").tag(1) }.pickerStyle(.segmented)
                    if tab == 0 { editor } else { DiscordMessagePreview(content: content, name: account["name"].string.isEmpty ? "Dein Account" : account["name"].string, avatar: account["avatar"].string, channel: "werbung") }
                    counter
                } footer: { Text("Pings (@everyone/@here) werden beim Posten immer unterdrückt. Platzhalter: {online} {max} {date}.") }
                Section {
                    Toggle("Link-Vorschau unterdrücken", isOn: $suppress)
                    Button { Clipboard.copy(content) } label: { Label("Text kopieren", systemImage: "doc.on.doc") }
                }
            }
            .navigationTitle(template == nil ? "Neuer Werbetext" : "Werbetext")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button { Task { await save() } } label: { if busy { ProgressView() } else { Text("Speichern").bold() } }.disabled(busy || name.trimmingCharacters(in: .whitespaces).isEmpty || content.isEmpty) }
            }
            .errorAlert($error)
            .alert("Link einfügen", isPresented: $linkPrompt) {
                TextField("https://…", text: $linkURL).textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
                Button("Einfügen") { if linkURL.hasPrefix("http") { wrap("[", "](\(linkURL))", placeholder: "Linktext") } }
                Button("Abbrechen", role: .cancel) { }
            }
            .task { if let template { name = template["name"].string; content = template["content"].string; suppress = template["suppressEmbeds"].bool } }
        }
    }
    private var editor: some View {
        VStack(alignment: .leading, spacing: 8) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    tool("bold", "Fett") { wrap("**", "**", placeholder: "fett") }
                    tool("italic", "Kursiv") { wrap("*", "*", placeholder: "kursiv") }
                    tool("underline", "Unterstrichen") { wrap("__", "__", placeholder: "unterstrichen") }
                    tool("strikethrough", "Durchgestrichen") { wrap("~~", "~~", placeholder: "durchgestrichen") }
                    tool("eye.slash", "Spoiler") { wrap("||", "||", placeholder: "spoiler") }
                    tool("chevron.left.forwardslash.chevron.right", "Code") { wrap("`", "`", placeholder: "code") }
                    tool("textformat.size.larger", "Überschrift 1") { prefix("# ") }
                    tool("textformat.size", "Überschrift 2") { prefix("## ") }
                    tool("textformat.size.smaller", "Überschrift 3") { prefix("### ") }
                    tool("textformat.subscript", "Kleiner Text") { prefix("-# ") }
                    tool("text.quote", "Zitat") { prefix("> ") }
                    tool("list.bullet", "Liste") { prefix("- ") }
                    tool("link", "Link") { linkPrompt = true }
                    tool("person.2.fill", "{online}") { insert("{online}") }
                }
                .padding(.vertical, 2)
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 4) {
                    ForEach(["🌍", "🖥️", "📱", "🎮", "👤", "💎", "🛡️", "♾️", "⚡", "🤝", "🔗", "📡", "⭐", "🔥", "✅", "🎉", "⚔️", "🏰"], id: \.self) { emoji in
                        Button(emoji) { insert(emoji) }.buttonStyle(.bordered).font(.title3)
                    }
                }
            }
            TextEditor(text: $content, selection: $selection)
                .font(.system(size: 14, design: .monospaced))
                .frame(minHeight: 260)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
        }
    }
    private var counter: some View {
        HStack {
            Text("\(content.count) / \(discordLimit) Zeichen").font(.caption.monospacedDigit()).foregroundStyle(content.count > discordLimit ? .red : .secondary)
            Spacer()
            if content.count > discordLimit { Text("Nur mit Nitro").font(.caption.weight(.semibold)).foregroundStyle(.red) }
        }
    }
    private func tool(_ symbol: String, _ title: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) { Image(systemName: symbol).frame(width: 30, height: 28) }
            .buttonStyle(.bordered)
            .accessibilityLabel(title)
    }
    private var selectedRange: Range<String.Index>? {
        guard let selection, case .selection(let range) = selection.indices else { return nil }
        guard range.lowerBound >= content.startIndex, range.upperBound <= content.endIndex else { return nil }
        return range
    }
    private func wrap(_ open: String, _ close: String, placeholder: String) {
        if let range = selectedRange {
            let inner = range.isEmpty ? placeholder : String(content[range])
            content.replaceSubrange(range, with: open + inner + close)
        } else {
            content += open + placeholder + close
        }
        selection = nil
    }
    private func insert(_ text: String) {
        if let range = selectedRange { content.replaceSubrange(range, with: text) } else { content += text }
        selection = nil
    }
    private func prefix(_ marker: String) {
        let at = selectedRange?.lowerBound ?? content.endIndex
        let start = content[..<at].lastIndex(of: "\n").map { content.index(after: $0) } ?? content.startIndex
        if content[start...].hasPrefix(marker) {
            content.removeSubrange(start..<content.index(start, offsetBy: marker.count))
        } else {
            content.insert(contentsOf: marker, at: start)
        }
        selection = nil
    }
    private func save() async {
        busy = true; defer { busy = false }
        do {
            let result = try await suite.post("discord-ads", ["operation": .string("template.save"), "id": .string(template?["id"].string ?? ""), "name": .string(name), "content": .string(content), "suppressEmbeds": .bool(suppress)])
            done(result["overview"]); Feedback.success(); dismiss()
        } catch { self.error = error.localizedDescription }
    }
}

// MARK: - Wizard: server → channel → text & timer → confirmation

struct SuiteAdsWizard: View {
    let suite: SuiteAPI
    let data: JSON
    let existing: JSON?
    let done: (JSON) -> Void
    @State private var step = 1
    @State private var account = ""
    @State private var guilds: [JSON] = []
    @State private var guild: JSON = .null
    @State private var channels: JSON = .null
    @State private var channel: JSON = .null
    @State private var template = ""
    @State private var interval = 360
    @State private var firstPost = "soon"
    @State private var notify = true
    @State private var pauseAfter = 3
    @State private var enabled = true
    @State private var confirmed = false
    @State private var search = ""
    @State private var busy = false
    @State private var error: String?
    @Environment(\.dismiss) private var dismiss
    private var accountJSON: JSON { data["accounts"].array.first { $0["id"].string == account } ?? .null }
    var body: some View {
        NavigationStack {
            Group {
                switch step {
                case 1: guildStep
                case 2: channelStep
                case 3: settingsStep
                default: confirmStep
                }
            }
            .panelScreen()
            .navigationTitle(existing == nil ? "Kanal planen · \(step)/4" : "Planung bearbeiten")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    if existing == nil && step > 1 { Button { step -= 1 } label: { Label("Zurück", systemImage: "chevron.left") } } else { Button("Abbrechen") { dismiss() } }
                }
                if step == 3 {
                    ToolbarItem(placement: .confirmationAction) {
                        if existing != nil { Button { Task { await save() } } label: { if busy { ProgressView() } else { Text("Speichern").bold() } }.disabled(busy || template.isEmpty) }
                        else { Button("Weiter") { step = 4 }.disabled(template.isEmpty) }
                    }
                }
            }
            .errorAlert($error)
            .task { setup() }
        }
    }
    private func setup() {
        if let existing {
            account = existing["account"].string; template = existing["template"].string; interval = max(5, existing["intervalMinutes"].int)
            notify = existing["notify"].bool; pauseAfter = existing["pauseAfter"].int; enabled = existing["enabled"].bool
            guild = .object(["id": existing["guild"], "name": existing["guildName"], "icon": existing["guildIcon"]])
            channel = .object(["id": existing["channel"], "name": existing["channelName"], "slowmode": existing["slowmodeSeconds"], "hint": .string("ad")])
            step = 3
        } else {
            account = (data["accounts"].array.first { $0["status"].string == "ok" } ?? data["accounts"].array.first ?? .null)["id"].string
            template = data["templates"].array.first?["id"].string ?? ""
            pauseAfter = data["settings"]["pauseAfter"].int
            Task { await loadGuilds(refresh: false) }
        }
    }
    private var guildStep: some View {
        List {
            if data["accounts"].array.count > 1 {
                Picker("Account", selection: $account) { ForEach(data["accounts"].array, id: \.["id"].string) { Text($0["name"].string).tag($0["id"].string) } }
                    .onChange(of: account) { _, _ in Task { await loadGuilds(refresh: false) } }
            }
            Section {
                if guilds.isEmpty && busy { ProgressView("Server laden …") }
                ForEach(filteredGuilds, id: \.["id"].string) { g in
                    Button { guild = g; channel = .null; step = 2; Task { await loadChannels() } } label: {
                        HStack(spacing: 12) {
                            SuiteAdsGuildIcon(name: g["name"].string, url: g["icon"].string, size: 36)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(g["name"].string).font(.headline).foregroundStyle(.primary)
                                Text(guildDetail(g)).font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            } header: { Text("Deine Discord-Server") } footer: { Text("Alle Server, auf denen der verknüpfte Account Mitglied ist.") }
        }
        .searchable(text: $search, prompt: "Server suchen")
        .refreshable { await loadGuilds(refresh: true) }
    }
    private var filteredGuilds: [JSON] { search.isEmpty ? guilds : guilds.filter { $0["name"].string.localizedCaseInsensitiveContains(search) } }
    private func guildDetail(_ g: JSON) -> String {
        let members = g["members"].int > 0 ? "\(g["members"].int) Mitglieder" : ""
        let planned = g["campaigns"].int > 0 ? " · \(g["campaigns"].int) geplant" : ""
        return members + planned
    }
    private var channelStep: some View {
        List {
            Section {
                HStack(spacing: 10) {
                    SuiteAdsGuildIcon(name: guild["name"].string, url: guild["icon"].string, size: 32)
                    Text(guild["name"].string).font(.headline)
                }
                if channels != .null {
                    Text("Geplant: \(channels["used"].int) von \(channels["limit"].int) Kanälen auf diesem Server.").font(.footnote).foregroundStyle(.secondary)
                    if !channels["permissionsCertain"].bool { Text("Rollen konnten nicht gelesen werden – Schreibrechte sind geschätzt.").font(.footnote).foregroundStyle(.orange) }
                }
            }
            if channels == .null { Section { ProgressView("Kanäle laden …") } }
            ForEach(categories, id: \.self) { category in
                Section(category.isEmpty ? "Ohne Kategorie" : category) {
                    ForEach(channels["channels"].array.filter { $0["category"].string == category }, id: \.["id"].string) { c in
                        SuiteAdsChannelRow(channel: c, blocked: blockReason(c)) {
                            channel = c
                            if c["suggestedMinutes"].int > 0 { interval = c["suggestedMinutes"].int } else if c["slowmode"].int > 0 { interval = max(interval, (c["slowmode"].int + 59) / 60) }
                            step = 3
                        }
                    }
                }
            }
        }
    }
    private var categories: [String] {
        var seen: [String] = []
        for c in channels["channels"].array where !seen.contains(c["category"].string) { seen.append(c["category"].string) }
        return seen
    }
    private func blockReason(_ c: JSON) -> String {
        if !c["supported"].bool { return "Forum – nicht unterstützt" }
        if !c["canView"].bool { return "Nicht sichtbar" }
        if !c["canSend"].bool { return "Keine Schreibrechte" }
        if c["campaign"] != .null && !c["campaign"].string.isEmpty { return "Bereits geplant" }
        if channels["used"].int >= channels["limit"].int { return "Limit erreicht" }
        return ""
    }
    private var settingsStep: some View {
        Form {
            Section {
                HStack(spacing: 10) {
                    SuiteAdsGuildIcon(name: guild["name"].string, url: guild["icon"].string, size: 32)
                    VStack(alignment: .leading) {
                        Text(guild["name"].string).font(.caption).foregroundStyle(.secondary)
                        Text("#" + channel["name"].string).font(.headline)
                    }
                }
                Picker("Werbetext", selection: $template) { ForEach(data["templates"].array, id: \.["id"].string) { Text($0["name"].string).tag($0["id"].string) } }
            }
            Section {
                Picker("Intervall", selection: $interval) {
                    ForEach(intervalChoices, id: \.0) { Text($0.1).tag($0.0) }
                }
                Stepper(value: $interval, in: 5...10080, step: interval >= 120 ? 30 : 5) { LabeledContent("Eigener Wert", value: AdsFormat.human(interval)) }
                Text(intervalInfo).font(.footnote).foregroundStyle(.secondary)
                if channel["suggestedMinutes"].int > 0 { Label("Kanalname/Beschreibung deuten auf \(AdsFormat.human(channel["suggestedMinutes"].int)) hin.", systemImage: "lightbulb.fill").font(.footnote).foregroundStyle(.teal) }
            } header: { Text("Timer für diesen Kanal") }
            Section {
                if existing == nil {
                    Picker("Erster Post", selection: $firstPost) { Text("In 1–2 Minuten").tag("soon"); Text("Nach dem Intervall").tag("interval") }
                }
                Toggle("Bark-Hinweis bei Fehlern", isOn: $notify)
                Picker("Automatisch pausieren", selection: $pauseAfter) { Text("Nie").tag(0); Text("Nach 1 Fehler").tag(1); Text("Nach 2 Fehlern").tag(2); Text("Nach 3 Fehlern").tag(3); Text("Nach 5 Fehlern").tag(5); Text("Nach 10 Fehlern").tag(10) }
                if existing != nil { Toggle("Aktiv", isOn: $enabled) }
            } footer: { Text("„In 1–2 Minuten“ wartet trotzdem, falls dein letzter eigener Post im Kanal noch zu jung ist.") }
            Section("Vorschau") {
                DiscordMessagePreview(content: templateContent, name: accountJSON["name"].string, avatar: accountJSON["avatar"].string, channel: channel["name"].string)
                    .listRowInsets(EdgeInsets(top: 8, leading: 8, bottom: 8, trailing: 8))
            }
        }
    }
    private var intervalChoices: [(Int, String)] {
        AdsFormat.intervals.contains { $0.0 == interval } ? AdsFormat.intervals : AdsFormat.intervals + [(interval, AdsFormat.human(interval))]
    }
    private var templateContent: String { data["templates"].array.first { $0["id"].string == template }?["content"].string ?? "" }
    private var intervalInfo: String {
        let slow = (channel["slowmode"].int + 59) / 60
        let effective = max(interval, slow)
        let from = Double(effective) + max(0.5, Double(effective) * 0.004)
        let to = Double(effective) + min(60, max(1.5, Double(effective) * 0.02))
        var text = "Gepostet wird immer etwas später als eingestellt, nie früher: \(AdsFormat.window(from: from, to: to))."
        if slow > interval { text += " Der Slowmode des Kanals (\(AdsFormat.human(slow))) ist länger und wird eingehalten." }
        return text
    }
    private var confirmStep: some View {
        Form {
            Section {
                HStack(spacing: 12) {
                    SuiteAdsGuildIcon(name: guild["name"].string, url: guild["icon"].string, size: 48)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(guild["name"].string).font(.caption).foregroundStyle(.secondary)
                        Text("#" + channel["name"].string).font(.title2.weight(.bold))
                    }
                }
                Label("Ist das wirklich der richtige Kanal? Wenn Werbung hier nicht erlaubt ist – oder nur seltener als alle \(AdsFormat.human(interval)) –, kannst du vom Discord-Server ausgeschlossen oder gebannt werden.", systemImage: "exclamationmark.triangle.fill")
                    .foregroundStyle(.orange).font(.subheadline)
                if channel["hint"].string == "warn" { Label("Der Kanalname wirkt nicht wie ein Werbekanal. Bitte besonders genau prüfen.", systemImage: "xmark.octagon.fill").foregroundStyle(.red).font(.footnote) }
                if !channel["topic"].string.isEmpty { VStack(alignment: .leading, spacing: 2) { Text("Kanalbeschreibung").font(.caption.weight(.semibold)); Text(channel["topic"].string).font(.footnote).foregroundStyle(.secondary) } }
            }
            Section {
                Toggle("Ich habe die Regeln von „\(guild["name"].string)“ gelesen: In #\(channel["name"].string) ist Werbung erlaubt, alle \(AdsFormat.human(interval)) ist in Ordnung.", isOn: $confirmed).font(.footnote)
                Button { Task { await save() } } label: { if busy { ProgressView() } else { Label("Verbindlich planen", systemImage: "paperplane.fill") } }
                    .disabled(!confirmed || busy)
            }
        }
    }
    private func loadGuilds(refresh: Bool) async {
        guard !account.isEmpty else { return }
        busy = true; defer { busy = false }
        do { guilds = try await suite.request("discord-ads/guilds", query: ["account": account, "refresh": refresh ? "true" : ""])["guilds"].array }
        catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }
    private func loadChannels() async {
        channels = .null
        do { channels = try await suite.request("discord-ads/channels", query: ["account": account, "guild": guild["id"].string]) }
        catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription; step = 1 } }
    }
    private func save() async {
        busy = true; defer { busy = false }
        let body: [String: JSON] = ["operation": .string("campaign.save"), "id": .string(existing?["id"].string ?? ""), "account": .string(account), "guild": guild["id"], "channel": channel["id"],
                                    "template": .string(template), "intervalMinutes": .number(Double(interval)), "notify": .bool(notify), "pauseAfter": .number(Double(pauseAfter)),
                                    "enabled": .bool(enabled), "firstPost": .string(firstPost), "confirm": .bool(confirmed)]
        do {
            let result = try await suite.post("discord-ads", body)
            done(result["overview"]); Feedback.success(); dismiss()
        } catch { self.error = error.localizedDescription }
    }
}

struct SuiteAdsChannelRow: View {
    let channel: JSON
    let blocked: String
    let pick: () -> Void
    var body: some View {
        Button(action: pick) {
            HStack(alignment: .top, spacing: 10) {
                Text(channel["type"].string == "announcement" ? "📢" : "#").font(.title3).foregroundStyle(.secondary)
                VStack(alignment: .leading, spacing: 3) {
                    Text(channel["name"].string).font(.headline).foregroundStyle(.primary)
                    if !channel["topic"].string.isEmpty { Text(channel["topic"].string).font(.caption).foregroundStyle(.secondary).lineLimit(2) }
                    badges
                }
            }
        }
        .disabled(!blocked.isEmpty)
        .opacity(blocked.isEmpty ? 1 : 0.55)
    }
    private var badges: some View {
        HStack(spacing: 6) {
            if channel["slowmode"].int > 0 { tag("Slowmode \(AdsFormat.human((channel["slowmode"].int + 59) / 60))", .blue) }
            if channel["suggestedMinutes"].int > 0 { tag("Vorschlag \(AdsFormat.human(channel["suggestedMinutes"].int))", .teal) }
            if !blocked.isEmpty { tag(blocked, .secondary) }
            else if channel["hint"].string == "ad" { tag("Werbekanal?", .green) }
            else if channel["hint"].string == "warn" { tag("Kein Werbekanal?", .red) }
        }
    }
    private func tag(_ text: String, _ tint: Color) -> some View {
        Text(text).font(.caption2.weight(.semibold)).padding(.horizontal, 6).padding(.vertical, 2)
            .background(tint.opacity(0.15), in: Capsule()).foregroundStyle(tint)
    }
}
