import Foundation

/// 5.13.0: texts and payloads of the Bann-Zentrale (Admin Suite 10.20.0). Kept free of SwiftUI so it is unit-tested.
enum SuiteBanText {
    static let feature = "ban-center-v1"
    static let migrationFeature = "litebans-migration-v1"
    static let viewPermission = "data.ban-center"
    static let managePermission = "ban-center.manage"
    static let migratePermission = "ban-center.migrate"

    static func date(_ millis: Double) -> String {
        guard millis > 0 else { return "–" }
        let format = DateFormatter()
        format.locale = Locale(identifier: "de_DE")
        format.timeZone = TimeZone(identifier: "Europe/Berlin")
        format.dateFormat = "dd.MM.yyyy, HH:mm"
        return format.string(from: Date(timeIntervalSince1970: millis / 1000))
    }

    /// "dauerhaft", "noch 3 Std. (bis …)", "noch 2 Tage (bis …)".
    static func until(permanent: Bool, until: Double, now: Date = Date()) -> String {
        if permanent || until <= 0 { return "dauerhaft" }
        let seconds = until / 1000 - now.timeIntervalSince1970
        if seconds <= 0 { return "abgelaufen" }
        let hours = Int(seconds / 3600)
        let left: String
        if hours < 1 { left = "noch \(max(1, Int(seconds / 60))) Min." }
        else if hours < 48 { left = "noch \(hours) Std." }
        else { left = "noch \(hours / 24) Tage" }
        return left + " (bis " + date(until) + ")"
    }

    static func until(_ row: JSON) -> String { until(permanent: row["permanent"].bool, until: row["until"].double) }

    static func role(_ role: String) -> String { role == "alt" ? "Alt-Konto" : "Hauptbann" }

    static func source(_ source: String) -> String {
        if source == "litebans" { return "aus LiteBans übernommen" }
        if source.hasPrefix("ultra") { return "Alt-Erkennung (Ultrabann)" }
        return "Simplex Admin Suite"
    }

    static func headline(_ status: JSON) -> String {
        guard status["banned"].bool else { return "Nicht gesperrt" }
        if status["role"].string == "alt" {
            let main = status["case"]["mainName"].string
            return "Gesperrtes Alt-Konto von " + (main.isEmpty ? "?" : main)
        }
        return "Hauptbann"
    }

    static func decisionTitle(_ decision: String) -> String {
        switch decision {
        case "account": return "Konten sperren"
        case "account+ip": return "Konten + IP sperren"
        case "main": return "Nur Hauptkonto"
        case "ultra": return "Alt-Erkennung (Ultrabann)"
        case "mute": return "Mute übernehmen"
        case "warn": return "Warnung übernehmen"
        case "skip": return "Nicht übernehmen"
        default: return decision
        }
    }

    static func decisionHint(_ decision: String) -> String {
        switch decision {
        case "account": return "Hauptkonto und ausgewählte Alt-Konten werden als Suite-Banns übernommen."
        case "account+ip": return "Zusätzlich wird die IP gesperrt – trifft jeden hinter dieser IP."
        case "main": return "Nur das Hauptkonto wird gesperrt."
        case "ultra": return "Das Hauptkonto wird als Alt-Gruppe gesperrt; neue Alt-Konten mit gleichem Gerät werden erkannt."
        case "mute": return "Wird als Suite-Mute übernommen."
        case "warn": return "Wird als Warnung ins Spielerprofil geschrieben."
        case "skip": return "Bleibt nur im Verlauf; der Spieler ist frei."
        default: return ""
        }
    }

    static func status(_ status: String) -> String {
        switch status {
        case "done": return "übernommen"
        case "failed": return "fehlgeschlagen"
        case "skipped": return "nicht übernommen"
        default: return "offen"
        }
    }

    static func type(_ type: String) -> String {
        switch type {
        case "mute": return "Mute"
        case "warning": return "Warnung"
        default: return "Bann"
        }
    }

    /// Request body for lifting a ban: one account ("single") or the whole case ("all").
    static func liftPayload(caseID: String, player: String, all: Bool, note: String) -> [String: JSON] {
        [
            "operation": .string("lift"),
            "case": .string(caseID),
            "player": .string(player),
            "scope": .string(all ? "all" : "single"),
            "note": .string(String(note.trimmingCharacters(in: .whitespacesAndNewlines).prefix(200))),
            "confirmed": .bool(true)
        ]
    }

    /// Migration choices as the Suite expects them: "caseKey=decision" and "caseKey|uuid=on/off", sorted.
    static func decisions(_ pending: [String: String]) -> String {
        pending.keys.sorted().map { $0 + "=" + (pending[$0] ?? "") }.joined(separator: ",")
    }

    /// Accounts that would become free when lifting: the member alone or every account of the case.
    static func affected(_ caseRow: JSON, member: String?, all: Bool) -> [String] {
        let members = caseRow["members"].array
        if all || member == nil { return members.map { $0["name"].string } }
        return members.filter { $0["uuid"].string == member }.map { $0["name"].string }
    }

    static func filterTitle(_ filter: String) -> String {
        switch filter {
        case "alts": return "Mit Alt-Konten"
        case "temporary": return "Zeitlich begrenzt"
        case "litebans": return "Aus LiteBans"
        case "ultra": return "Alt-Gruppen"
        default: return "Alle"
        }
    }
    static let filters = ["all", "alts", "temporary", "litebans", "ultra"]

    static func historyKind(_ row: JSON) -> String {
        if row["type"].string == "litebans" {
            switch row["table"].string {
            case "mute": return "LiteBans-Mute"
            case "warning": return "LiteBans-Warnung"
            default: return "LiteBans-Bann"
            }
        }
        return row["role"].string == "alt" ? "Alt-Konto aufgehoben" : "Bann aufgehoben"
    }
}
