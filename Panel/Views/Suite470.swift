import SwiftUI
import UIKit

// MARK: - Simplex Panel 4.7.0 (Discord preview) – kept in 4.8.0
// Discord markdown parser and the Discord-style message preview. The 4.7.0 self-bot planner was removed in 4.8.0;
// the Werbe-Zentrale (Suite480.swift) uses these views for its Discord previews.

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

/// Small formatting helpers for the Werbe-Zentrale (unit-tested).
enum AdsFormat {
    static func human(_ minutes: Int) -> String {
        if minutes >= 1440 && minutes % 1440 == 0 { return minutes == 1440 ? "1 Tag" : "\(minutes / 1440) Tage" }
        if minutes >= 60 && minutes % 60 == 0 { return "\(minutes / 60) Std" }
        if minutes > 60 { return String(format: "%.1f Std", Double(minutes) / 60).replacingOccurrences(of: ".", with: ",") }
        return "\(minutes) Min"
    }
    static func until(_ millis: Double, now: Date = Date()) -> String {
        if millis <= 0 { return "–" }
        let seconds = millis / 1000 - now.timeIntervalSince1970
        if seconds <= 0 { return "jetzt" }
        if seconds < 3600 { return "in \(max(1, Int((seconds / 60).rounded()))) Min" }
        if seconds < 86400 { return "in " + String(format: "%.1f Std", seconds / 3600).replacingOccurrences(of: ".", with: ",") }
        return date(millis)
    }
    static func ago(_ millis: Double, now: Date = Date()) -> String {
        if millis <= 0 { return "noch nie" }
        let seconds = now.timeIntervalSince1970 - millis / 1000
        if seconds < 60 { return "gerade eben" }
        if seconds < 3600 { return "vor \(Int(seconds / 60)) Min" }
        if seconds < 86400 { return "vor " + String(format: "%.1f Std", seconds / 3600).replacingOccurrences(of: ".", with: ",") }
        return date(millis)
    }
    static func date(_ millis: Double) -> String {
        millis <= 0 ? "–" : Date(timeIntervalSince1970: millis / 1000).formatted(date: .abbreviated, time: .shortened)
    }
    static let intervals: [(Int, String)] = [(15, "15 Min"), (30, "30 Min"), (60, "1 Std"), (120, "2 Std"), (180, "3 Std"), (240, "4 Std"), (360, "6 Std"), (480, "8 Std"), (720, "12 Std"), (1440, "24 Std"), (2880, "2 Tage"), (4320, "3 Tage"), (10080, "7 Tage"), (20160, "14 Tage"), (43200, "30 Tage")]
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
