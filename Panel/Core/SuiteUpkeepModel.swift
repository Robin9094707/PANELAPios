import Foundation

/// 5.16.0: pure presentation logic for Server-Updater, Smart Anti-Lag, server sounds and personal mutes.
/// Kept free of SwiftUI so it is unit-tested on the simulator and on Linux.
enum SuiteUpkeep {
    // MARK: Server-Updater

    struct Step: Equatable { let id: String; let title: String }
    static let installSteps: [Step] = [
        Step(id: "download", title: "Download"), Step(id: "verify", title: "Prüfen"), Step(id: "backup", title: "Sichern"),
        Step(id: "replace", title: "Ersetzen"), Step(id: "done", title: "Fertig")
    ]

    /// Index of the current step; -1 when idle, failed or unknown.
    static func stepIndex(_ phase: String) -> Int {
        if phase == "queued" { return 0 }
        return installSteps.firstIndex { $0.id == phase } ?? -1
    }

    static func megabytes(_ bytes: Double) -> String {
        guard bytes > 0 else { return "–" }
        return String(format: "%.1f MB", bytes / 1_048_576).replacingOccurrences(of: ".", with: ",")
    }

    static func channelLabel(_ channel: String) -> String {
        switch channel.uppercased() {
        case "STABLE", "RECOMMENDED", "": return "Stabil"
        case "BETA": return "Beta"
        case "ALPHA": return "Alpha"
        default: return channel.capitalized
        }
    }

    static func installedSummary(_ installed: JSON) -> String {
        let build = installed["build"].int
        let minecraft = installed["minecraft"].string.isEmpty ? "?" : installed["minecraft"].string
        return minecraft + " · Build " + (build >= 0 ? String(build) : "?")
    }

    static func statusLine(_ data: JSON) -> String {
        if !data["error"].string.isEmpty { return "PaperMC nicht erreichbar" }
        let newer = data["newerBuilds"].int
        if newer == 0 { return "Aktuell – kein neuerer Build" }
        return newer == 1 ? "1 neuerer Build verfügbar" : "\(newer) neuere Builds verfügbar"
    }

    // MARK: Smart Anti-Lag

    static let modes: [(String, String)] = [("off", "Aus"), ("observe", "Nur erkennen"), ("notify", "Informieren"), ("smart", "Smart")]

    static func modeTitle(_ mode: String) -> String { modes.first { $0.0 == mode }?.1 ?? "Smart" }

    static func kindTitle(_ kind: String) -> String {
        switch kind {
        case "machine": return "Lag-Maschine"
        case "items": return "Item-Haufen"
        default: return "Mob-Ansammlung"
        }
    }

    static func kindSymbol(_ kind: String) -> String {
        switch kind {
        case "machine": return "gearshape.2.fill"
        case "items": return "shippingbox.fill"
        default: return "pawprint.fill"
        }
    }

    static func statusTitle(_ status: String) -> String {
        switch status {
        case "high": return "Sehr hoch"
        case "warned": return "Spieler informiert"
        case "limited": return "Spawnbremse aktiv"
        case "throttled": return "KI gedrosselt"
        default: return "Beobachtet"
        }
    }

    /// Short facts for one hotspot, e.g. "640 Mobs (520 Cow) · 30 Items".
    static func facts(_ spot: JSON) -> String {
        var parts: [String] = []
        let living = spot["living"].int, top = spot["topCount"].int
        if living > 0 { parts.append(top > 20 ? "\(living) Mobs (\(top) \(spot["topType"].string))" : "\(living) Mobs") }
        if spot["items"].int > 0 { parts.append("\(spot["items"].int) Items") }
        if spot["redstonePerSecond"].int > 0 { parts.append("\(spot["redstonePerSecond"].int) Redstone/s") }
        if spot["pistonsPerSecond"].int > 0 { parts.append("\(spot["pistonsPerSecond"].int) Kolben/s") }
        if spot["hoppers"].int > 0 { parts.append("\(spot["hoppers"].int) Trichter") }
        return parts.isEmpty ? "Viel Aktivität" : parts.joined(separator: " · ")
    }

    /// Who built it (placed machine parts) – otherwise who is nearby.
    static func culprits(_ spot: JSON) -> String {
        let causes = spot["causes"].array.map { row -> String in
            let online = row["online"].bool ? ", online" : ""
            return row["name"].string + " (\(row["placed"].int) Teile" + online + ")"
        }
        if !causes.isEmpty { return "Gebaut/gezüchtet von " + causes.joined(separator: ", ") }
        let nearby = spot["nearby"].array.map(\.string)
        return nearby.isEmpty ? "Niemand in der Nähe" : "In der Nähe: " + nearby.joined(separator: ", ")
    }

    static func location(_ spot: JSON) -> String { spot["world"].string + " · \(spot["x"].int) / \(spot["z"].int)" }

    // MARK: Server sounds & personal mutes

    static let soundModes: [(String, String)] = [("off", "Alle Töne an"), ("chat", "Chat-Töne aus"), ("plugins", "Plugin-Töne aus"), ("all", "Stille")]

    static func soundTitle(_ mode: String) -> String { soundModes.first { $0.0 == mode }?.1 ?? "Alle Töne an" }

    static func soundDetail(_ mode: String) -> String {
        switch mode {
        case "chat": return "Kein Pling mehr bei Chatnachrichten, z. B. Spender-Töne anderer Plugins."
        case "plugins": return "Keine Hinweis-, Menü- und Plugin-Töne. Die Spielwelt bleibt hörbar."
        case "all": return "Keinerlei Töne vom Server – nur Musik und eigene Schritte."
        default: return "Alles wie gewohnt."
        }
    }

    /// Staff view of a personal mute; linked account names only arrive with the alt permission.
    static func muteDetail(_ row: JSON) -> String {
        var parts: [String] = []
        if row["alts"].bool {
            let count = row["linkedCount"].int
            parts.append(count > 0 ? "+\(count) weitere Konten" : "inkl. künftiger Konten")
        } else { parts.append("Nur dieses Konto") }
        parts.append(row["contact"].bool ? "Kontakt blockiert" : "Nur Chat")
        if row["protected"].bool { parts.append("Admin – wirkt nicht") }
        return parts.joined(separator: " · ")
    }

    static func linkedNames(_ row: JSON) -> String { row["linked"].array.map { $0["name"].string }.joined(separator: ", ") }

    // MARK: Plugins

    /// Same rule as the plugin: `LuckPerms` + `5.4.150` → `LuckPerms-5.4.150.jar`.
    static func versionedFilename(name: String, version: String) -> String {
        func clean(_ value: String) -> String {
            var out = value.trimmingCharacters(in: .whitespacesAndNewlines)
                .replacingOccurrences(of: "[^A-Za-z0-9._+-]+", with: "-", options: .regularExpression)
                .replacingOccurrences(of: "-{2,}", with: "-", options: .regularExpression)
                .replacingOccurrences(of: "^[-.]+|[-.]+$", with: "", options: .regularExpression)
            if out.count > 80 { out = String(out.prefix(80)) }
            return out
        }
        let n = clean(name).isEmpty ? "Plugin" : clean(name)
        let v = clean(version)
        return v.isEmpty ? n + ".jar" : n + "-" + v + ".jar"
    }
}
