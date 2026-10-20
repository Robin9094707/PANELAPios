import Foundation

/// Signing services may rewrite a group's suffix. Only consider Panel's own group family;
/// the OS still decides whether a candidate container is actually entitled.
enum SigningGroups {
    static let canonical = "group.eu.simplexsmp.panel"
    static func entitlements(in data: Data) -> [String: Any] {
        guard data.count < 4 * 1024 * 1024,
              let start = data.range(of: Data("<?xml".utf8)),
              let ending = data.range(of: Data("</plist>".utf8), in: start.lowerBound..<data.endIndex),
              let plist = try? PropertyListSerialization.propertyList(from: data.subdata(in: start.lowerBound..<ending.upperBound), format: nil) as? [String: Any] else { return [:] }
        return plist["Entitlements"] as? [String: Any] ?? [:]
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
        return [:]
    }
    static let availableGroup: String? = {
        if FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: canonical) != nil { return canonical }
        let values = profile["com.apple.security.application-groups"] as? [String] ?? []
        return candidates(values).first { FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: $0) != nil }
    }()
}
