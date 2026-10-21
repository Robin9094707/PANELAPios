import Foundation

/// Signing services may rewrite a group's suffix. Only consider Panel's own group family;
/// the OS still decides whether a candidate container is actually entitled.
enum SigningGroups {
    static let canonical = "group.eu.simplexsmp.panel"
    static func entitlements(in data: Data) -> [String: Any] {
        guard data.count < 32 * 1024 * 1024,
              let start = data.range(of: Data("<?xml".utf8)),
              let ending = data.range(of: Data("</plist>".utf8), in: start.lowerBound..<data.endIndex),
              let plist = try? PropertyListSerialization.propertyList(from: data.subdata(in: start.lowerBound..<ending.upperBound), format: nil) as? [String: Any] else { return [:] }
        return plist["Entitlements"] as? [String: Any] ?? (plist["com.apple.security.application-groups"] != nil ? plist : [:])
    }
    static func binaryEntitlements(_ data: Data) -> [String: Any] {
        func le(_ i: Int) -> Int? { guard i >= 0, i + 4 <= data.count else { return nil }; return (0..<4).reduce(0) { $0 | Int(data[i + $1]) << (8 * $1) } }
        func be(_ i: Int) -> Int? { guard i >= 0, i + 4 <= data.count else { return nil }; return (0..<4).reduce(0) { ($0 << 8) | Int(data[i + $1]) } }
        guard data.count >= 32, le(0) == 0xfeedfacf, let count = le(16), count < 4096 else { return [:] }
        var cursor = 32
        for _ in 0..<count {
            guard let command = le(cursor), let size = le(cursor + 4), size >= 8, cursor + size <= data.count else { return [:] }
            if command == 0x1d, let offset = le(cursor + 8), be(offset) == 0xfade0cc0, let slots = be(offset + 8), slots < 128 {
                for slot in 0..<slots {
                    let index = offset + 12 + slot * 8
                    guard be(index) == 5, let relative = be(index + 4) else { continue }
                    let start = offset + relative
                    guard be(start) == 0xfade7171, let length = be(start + 4), length >= 8, start + length <= data.count,
                          let result = try? PropertyListSerialization.propertyList(from: data.subdata(in: start + 8..<start + length), format: nil) as? [String: Any] else { continue }
                    return result
                }
            }
            cursor += size
        }; return [:]
    }
    static func candidates(_ values: [String]) -> [String] {
        var result = [canonical]
        for value in values.sorted() where value != canonical {
            guard value.range(of: #"^group\.(?:[A-Za-z0-9_-]+\.)*eu\.simplexsmp\.panel(?:\.[A-Za-z0-9_-]+)*$"#, options: .regularExpression) != nil else { continue }
            if !result.contains(value) { result.append(value) }
        }
        return result
    }
    static var profile: [String: Any] {
        let bundle = Bundle.main.bundleURL
        var urls = [bundle.appendingPathComponent("embedded.mobileprovision")]
        if bundle.pathExtension == "appex" { urls.append(bundle.deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("embedded.mobileprovision")) }
        for url in urls { if let data = try? Data(contentsOf: url) { let result = entitlements(in: data); if !result.isEmpty { return result } } }
        if let executable = Bundle.main.executableURL, let data = try? Data(contentsOf: executable, options: .mappedIfSafe) {
            let result = binaryEntitlements(data); if !result.isEmpty { return result }
        }
        return [:]
    }
    static var declaredCandidates: [String] {
        let values = profile["com.apple.security.application-groups"] as? [String] ?? []
        let safe = values.filter { $0.hasPrefix("group.") && $0.lowercased().contains("panel") && $0.range(of: #"^[A-Za-z0-9_.-]+$"#, options: .regularExpression) != nil }
        return Array(Set(candidates(values) + safe)).sorted { a, b in if a == canonical { return true }; if b == canonical { return false }; return a < b }
    }
    static let availableGroup: String? = {
        if FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: canonical) != nil { return canonical }
        return declaredCandidates.first { FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: $0) != nil }
    }()
}
