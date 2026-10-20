import UIKit

/// Tokens are line-bounded: a stray quote in a YAML comment must never color the rest of the file.
enum SyntaxHighlight {
    static func apply(to storage: NSMutableAttributedString, range: NSRange) {
        let value = storage.string
        let patterns: [(String, UIColor)] = [
            (#"(?m)^[ \t]*[\w.\-\"']+(?=[ \t]*[:=])"#, .systemTeal),
            (#"\b(?:true|false|null|yes|no)\b"#, .systemPurple),
            (#"(?<![\w])[-+]?\d+(?:\.\d+)?\b"#, .systemOrange)
        ]
        for (pattern, color) in patterns {
            guard let regex = try? NSRegularExpression(pattern: pattern) else { continue }
            for match in regex.matches(in: value, range: range) { storage.addAttribute(.foregroundColor, value: color, range: match.range) }
        }
        // One lexer pass gives quoted strings priority over # within strings, and comments priority over quotes within comments.
        let tokens = #"(?<comment>#[^\r\n]*)|(?<double>\"(?:[^\"\\\r\n]|\\[^\r\n])*\")|(?<single>'(?:[^'\r\n]|'')*')"#
        guard let regex = try? NSRegularExpression(pattern: tokens) else { return }
        for match in regex.matches(in: value, range: range) {
            let comment = match.range(withName: "comment").location != NSNotFound
            storage.addAttribute(.foregroundColor, value: comment ? UIColor.secondaryLabel : UIColor.systemBlue, range: match.range)
        }
    }
}
