import Foundation

/// Pure normalization shared by the app and widget extension. Missing metrics stay missing.
enum AMPMetrics {
    static func seconds(_ value: JSON) -> Double? {
        if case .number(let n) = value { return n.isFinite && n >= 0 ? n : nil }
        var raw = value.string.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !raw.isEmpty else { return nil }
        if let n = Double(raw), n.isFinite, n >= 0 { return n }
        var days: Double = 0
        // .NET TimeSpan: d.hh:mm:ss.fff; AMP also emits d:hh:mm:ss.
        if let dot = raw.firstIndex(of: "."), let colon = raw.firstIndex(of: ":"), dot < colon {
            guard let d = Double(raw[..<dot]) else { return nil }; days = d; raw = String(raw[raw.index(after: dot)...])
        }
        let parts = raw.split(separator: ":").map { Double($0) }
        guard parts.count == 3 || parts.count == 4, parts.allSatisfy({ $0 != nil && $0! >= 0 && $0!.isFinite }) else { return nil }
        let n = parts.compactMap { $0 }
        return parts.count == 4 ? n[0] * 86400 + n[1] * 3600 + n[2] * 60 + n[3] : days * 86400 + n[0] * 3600 + n[1] * 60 + n[2]
    }
    static func state(_ v: JSON) -> String {
        switch v.string.lowercased() {
        case "0", "stopped", "50", "sleeping": return "offline"
        case "20", "ready", "running": return "running"
        case "5", "7", "10", "30", "prestart", "configuring", "starting", "restarting": return "starting"
        case "40", "45", "stopping", "preparingforsleep": return "stopping"
        default: return "unknown"
        }
    }
    static func merge(_ previous: JSON, _ update: JSON) -> JSON {
        var object = previous.object
        for (key, value) in update.object where value != .null {
            if key == "Metrics" {
                var metrics = previous[key].object
                for (name, metric) in value.object {
                    var merged = metrics[name]?.object ?? [:]
                    merged.merge(metric.object, uniquingKeysWith: { _, new in new }); metrics[name] = .object(merged)
                }
                object[key] = .object(metrics)
            } else { object[key] = value }
        }
        return .object(object)
    }
    static func metric(_ metrics: JSON, words: [String]) -> JSON {
        for word in words {
            if let pair = metrics.object.first(where: { $0.key.lowercased() == word.lowercased() }) { return pair.value }
        }
        return metrics.object.first { key, value in words.contains { key.localizedCaseInsensitiveContains($0) || value["ShortName"].string.localizedCaseInsensitiveContains($0) } }?.value ?? .null
    }
    static func number(_ value: JSON) -> Double? {
        guard value != .null else { return nil }
        let raw = value.string.replacingOccurrences(of: ",", with: ".")
        guard let n = Double(raw), n.isFinite, n >= 0 else { return nil }; return n
    }
    static func raw(_ metric: JSON) -> Double? { number(metric["RawValue"]) ?? number(metric["Value"]) }
    static func multiplier(_ metric: JSON) -> Double {
        switch metric["Units"].string.lowercased().trimmingCharacters(in: .whitespaces) {
        case "gb", "gib": return 1_073_741_824
        case "mb", "mib": return 1_048_576
        case "kb", "kib": return 1024
        default: return 1
        }
    }
    static func resources(_ status: JSON) -> JSON {
        let metrics = status["Metrics"]
        let memory = metric(metrics, words: ["Memory Usage", "Memory", "RAM", "Arbeitsspeicher"])
        let cpu = metric(metrics, words: ["CPU Usage", "CPU", "Processor"])
        let disk = metric(metrics, words: ["Disk Usage", "Disk", "Storage"])
        var values: [String: JSON] = [:]
        if let n = raw(memory) { values["memory_bytes"] = .number(n * multiplier(memory)) }
        if let n = raw(cpu) ?? number(cpu["Percent"]) { values["cpu_absolute"] = .number(n) }
        if let n = raw(disk) { values["disk_bytes"] = .number(n * multiplier(disk)) }
        if let n = seconds(status["Uptime"]) { values["uptime"] = .number(n * 1000) }
        if let n = number(memory["MaxValue"]), n > 0 { values["memory_limit_bytes"] = .number(n * multiplier(memory)) }
        return .object(["current_state": .string(state(status["State"])), "resources": .object(values), "amp_metrics": metrics])
    }
}
