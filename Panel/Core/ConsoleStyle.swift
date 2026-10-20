import UIKit

/// Native selectable output with ANSI SGR (16/256/true color) and Minecraft § styles.
enum ConsoleStyle {
    static let tokens = try! NSRegularExpression(pattern: "\u{1B}\\][^\u{7}\u{1B}]*(?:\u{7}|\u{1B}\\\\)|\u{1B}\\[[0-?]*[ -/]*[@-~]|\u{1B}[=>]|§x(?:§[0-9a-fA-F]){6}|§#[0-9a-fA-F]{6}|§[0-9a-fA-Fk-orK-OR]")
    static let palette: [UInt32] = [0x000000,0xAA0000,0x00AA00,0xAA5500,0x0000AA,0xAA00AA,0x00AAAA,0xAAAAAA,0x555555,0xFF5555,0x55FF55,0xFFFF55,0x5555FF,0xFF55FF,0x55FFFF,0xFFFFFF]
    static func rgb(_ value: UInt32) -> UIColor { UIColor(red: CGFloat((value >> 16) & 255)/255, green: CGFloat((value >> 8) & 255)/255, blue: CGFloat(value & 255)/255, alpha: 1) }
    static func indexed(_ n: Int) -> UIColor {
        if n < 16 { return rgb(palette[max(0, n)]) }
        if n >= 232 { let c = CGFloat(min(255, 8 + (n - 232) * 10))/255; return UIColor(white: c, alpha: 1) }
        let a = n - 16, levels: [CGFloat] = [0,95,135,175,215,255]
        return UIColor(red: levels[(a / 36) % 6]/255, green: levels[(a / 6) % 6]/255, blue: levels[a % 6]/255, alpha: 1)
    }
    static func attributed(_ text: String, size: Double) -> NSAttributedString {
        let output = NSMutableAttributedString(string: "")
        var foreground = UIColor.label, background: UIColor?, bold = false, italic = false, underline = false, strike = false
        func reset() { foreground = .label; background = nil; bold = false; italic = false; underline = false; strike = false }
        func attributes() -> [NSAttributedString.Key: Any] {
            var font = UIFont.monospacedSystemFont(ofSize: size, weight: bold ? .bold : .regular)
            if italic, let descriptor = font.fontDescriptor.withSymbolicTraits([.traitMonoSpace, .traitItalic, bold ? .traitBold : []]) { font = UIFont(descriptor: descriptor, size: size) }
            var a: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: foreground]
            if let background { a[.backgroundColor] = background }
            if underline { a[.underlineStyle] = NSUnderlineStyle.single.rawValue }
            if strike { a[.strikethroughStyle] = NSUnderlineStyle.single.rawValue }
            return a
        }
        let source = text as NSString; var cursor = 0
        for match in tokens.matches(in: text, range: NSRange(location: 0, length: source.length)) {
            if match.range.location > cursor { output.append(NSAttributedString(string: source.substring(with: NSRange(location: cursor, length: match.range.location - cursor)).replacingOccurrences(of: "\r", with: ""), attributes: attributes())) }
            let token = source.substring(with: match.range)
            if token.hasPrefix("§") {
                let code = token.dropFirst().lowercased()
                if code.hasPrefix("x") || code.hasPrefix("#") {
                    let hex = code.hasPrefix("x") ? String(code.dropFirst()).replacingOccurrences(of: "§", with: "") : String(code.dropFirst())
                    if let n = UInt32(hex, radix: 16) { reset(); foreground = rgb(n) }
                } else if let n = Int(code, radix: 16), n < 16 {
                    let minecraftOrder = [0,4,2,6,1,5,3,7,8,12,10,14,9,13,11,15]
                    reset(); foreground = rgb(palette[minecraftOrder[n]])
                } else { switch code { case "r": reset(); case "l": bold = true; case "o": italic = true; case "n": underline = true; case "m": strike = true; default: break } }
            } else if token.hasPrefix("\u{1B}["), token.hasSuffix("m") {
                let codes = token.dropFirst(2).dropLast().split(separator: ";", omittingEmptySubsequences: false).map { Int($0) ?? 0 }
                var i = 0
                while i < codes.count {
                    let n = codes[i]
                    switch n {
                    case 0: reset()
                    case 1: bold = true
                    case 3: italic = true
                    case 4: underline = true
                    case 9: strike = true
                    case 22: bold = false
                    case 23: italic = false
                    case 24: underline = false
                    case 29: strike = false
                    case 30...37: foreground = indexed(n - 30)
                    case 90...97: foreground = indexed(n - 90 + 8)
                    case 40...47: background = indexed(n - 40)
                    case 100...107: background = indexed(n - 100 + 8)
                    case 39: foreground = .label
                    case 49: background = nil
                    case 38, 48:
                        var color: UIColor?
                        if i + 2 < codes.count, codes[i + 1] == 5, (0...255).contains(codes[i + 2]) { color = indexed(codes[i + 2]); i += 2 }
                        else if i + 4 < codes.count, codes[i + 1] == 2, codes[(i+2)...(i+4)].allSatisfy({ (0...255).contains($0) }) { color = UIColor(red: CGFloat(codes[i+2])/255, green: CGFloat(codes[i+3])/255, blue: CGFloat(codes[i+4])/255, alpha: 1); i += 4 }
                        if let color { if n == 38 { foreground = color } else { background = color } }
                    default: break
                    }; i += 1
                }
            }
            cursor = NSMaxRange(match.range)
        }
        if cursor < source.length { output.append(NSAttributedString(string: source.substring(from: cursor).replacingOccurrences(of: "\r", with: ""), attributes: attributes())) }
        return output
    }
    static func plain(_ text: String) -> String { tokens.stringByReplacingMatches(in: text, range: NSRange(text.startIndex..., in: text), withTemplate: "").replacingOccurrences(of: "\r", with: "") }
}
