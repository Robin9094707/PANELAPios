import Foundation

/// Release notes shown once after an app update. Entries are static; nothing is fetched.
struct WhatsNewItem: Identifiable, Equatable {
    let symbol: String
    let title: String
    let detail: String
    var id: String { title }
}
struct WhatsNewRelease: Identifiable, Equatable {
    let version: String
    let title: String
    let summary: String
    let items: [WhatsNewItem]
    var id: String { version }
}

enum WhatsNew {
    static let lastSeenKey = "whatsNew.lastSeenVersion"
    static let releases: [WhatsNewRelease] = [
        WhatsNewRelease(version: "3.1.0", title: "Simplex Panel 3.1", summary: "Support-Tickets für Minecraft, Discord und die App, ein dauerhafter Serverchat und deutlich schlauere Plugin-Updates.", items: [
            WhatsNewItem(symbol: "ticket.fill", title: "Support-Tickets", detail: "Spieler öffnen Tickets mit /ticket (Java-Menüs, native Bedrock-Formulare) oder über das Discord-Panel. Du antwortest in der App, im Spiel oder auf Discord – Antworten warten, bis der Spieler online ist."),
            WhatsNewItem(symbol: "bell.and.waves.left.and.right.fill", title: "Bark für Tickets", detail: "Neue Tickets kommen per Bark, Notfall-Tickets außerhalb der Ruhezeit als kritischer Alarm."),
            WhatsNewItem(symbol: "stethoscope", title: "Discord-Diagnose", detail: "Ticketkanal, Kategorie und Teamrollen direkt in der App einrichten – mit Prüfung, welche Bot-Rechte fehlen. Läuft über den vorhandenen Admin-Suite-Bot."),
            WhatsNewItem(symbol: "bubble.left.and.text.bubble.right.fill", title: "Serverchat bleibt", detail: "Die letzten 50 Nachrichten mit Spielerköpfen bleiben erhalten, auch nach dem Schließen der App. Leeren per Papierkorb mit Bestätigung."),
            WhatsNewItem(symbol: "puzzlepiece.extension.fill", title: "Plugin-Zentrale", detail: "Das Puzzleteil auf der Serverseite zeigt Update-Status, Geyser & Floodgate und deine Schnellzugriffe."),
            WhatsNewItem(symbol: "arrow.triangle.2.circlepath", title: "Schlauere Plugin-Updates", detail: "Hangar und GeyserMC als neue Quellen, Zuordnungsvorschläge, alle geprüften Updates auf einmal vormerken, freie Spigot-Downloads ohne Anmeldung und ein Fix für die hängende Cloudflare-Prüfung.")
        ]),
        WhatsNewRelease(version: "3.0.0", title: "Simplex Panel 3.0", summary: "Die Admin Suite ist jetzt direkt in die Serveransicht eingebaut: ein Ort für Spieler, Moderation und Serverbetrieb.", items: [
            WhatsNewItem(symbol: "shield.lefthalf.filled", title: "Neue Admin-Zentrale", detail: "Eigener Tab mit Live-Status, offenen Reports, Banns, Wartung, Neustart, Team online und allen Suite-Werkzeugen auf einen Blick."),
            WhatsNewItem(symbol: "person.crop.rectangle.stack.fill", title: "Spielerverwaltung neu gedacht", detail: "Spielerköpfe, Status-Badges für Bann, Stummschaltung und Warnungen, Wischaktionen sowie neue Filter „Auffällig“ und „Neu“."),
            WhatsNewItem(symbol: "bolt.circle.fill", title: "Schnellaktionen im Profil", detail: "Nachricht, Kick, Bann und Warnung direkt oben im Spielerprofil – weiterhin mit serverseitiger Vorschau und Bestätigung."),
            WhatsNewItem(symbol: "star.fill", title: "Favoriten & zuletzt angesehen", detail: "Markiere wichtige Spieler mit einem Stern und springe über die Admin-Zentrale direkt zu zuletzt geöffneten Profilen."),
            WhatsNewItem(symbol: "list.bullet.clipboard.fill", title: "Moderationsakte", detail: "Warnungen, Notizen, frühere Namen und Spielzeit erscheinen gesammelt im Profil."),
            WhatsNewItem(symbol: "checkmark.shield.fill", title: "Plug & Play", detail: "Gespeicherte Server, Suite-Kopplungen, Schlüssel und Layouts bleiben erhalten. Funktioniert mit Admin Suite 9.3.0 und älteren Suite-Versionen.")
        ])
    ]
    static var current: String { Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "" }
    static func release(for version: String) -> WhatsNewRelease? { releases.first { $0.version == version } ?? releases.first { $0.version == majorMinor(version) + ".0" } }
    /// Fresh installs are only marked as seen; updates from any earlier version present the notes once.
    static func shouldPresent(current: String, lastSeen: String?, existingUser: Bool) -> Bool {
        guard release(for: current) != nil else { return false }
        guard let lastSeen, !lastSeen.isEmpty else { return existingUser }
        return compare(lastSeen, current) == .orderedAscending
    }
    static func compare(_ a: String, _ b: String) -> ComparisonResult {
        let left = a.split(separator: ".").map { Int($0) ?? 0 }, right = b.split(separator: ".").map { Int($0) ?? 0 }
        for index in 0..<max(left.count, right.count) {
            let x = index < left.count ? left[index] : 0, y = index < right.count ? right[index] : 0
            if x != y { return x < y ? .orderedAscending : .orderedDescending }
        }
        return .orderedSame
    }
    static func majorMinor(_ version: String) -> String { version.split(separator: ".").prefix(2).joined(separator: ".") }
}
