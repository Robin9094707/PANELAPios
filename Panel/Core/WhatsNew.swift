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
        WhatsNewRelease(version: "4.2.0", title: "Ränge, Lands & ruhiger Admin-Log", summary: "Selbst einstellen, was als ungelesen zählt, Benutzername ändern, LuckPerms und Lands verwalten – und Tickets ohne deinen Login-Namen.", items: [
            WhatsNewItem(symbol: "line.3.horizontal.decrease.circle", title: "Admin-Log-Filter", detail: "Nur Team, ausgewählte Admins, alle oder gar keine Zahlen – Aktionen normaler Spieler bleiben sichtbar, aber still. Push folgt demselben Filter."),
            WhatsNewItem(symbol: "person.text.rectangle", title: "Name & Anzeige", detail: "Benutzernamen ändern, ohne neu anmelden zu müssen. Spieler sehen bei Ticketantworten nur deinen öffentlichen Namen oder „Team“."),
            WhatsNewItem(symbol: "crown.fill", title: "Ränge & Rechte", detail: "LuckPerms-Gruppen, Präfixe, Gewichte, Rechte, Vererbung und Spielergruppen direkt in der App – jede Änderung mit Bestätigung."),
            WhatsNewItem(symbol: "map.fill", title: "Lands", detail: "Alle Länder auf einen Blick und welche Admins überall Zugriff haben – Bypass und Lands-Admin per Schalter."),
            WhatsNewItem(symbol: "list.bullet.rectangle", title: "Ränge getrennt einstellbar", detail: "Rang über dem Kopf und in der Tabliste einzeln; ist ein TAB-Plugin installiert, bleibt die Tabliste unangetastet."),
            WhatsNewItem(symbol: "bubble.left.and.text.bubble.right.fill", title: "Schönerer Serverchat", detail: "Sprechblasen mit Herkunft (Minecraft, Discord, App) – auch im Webpanel, das jetzt Push-Benachrichtigungen und Home-Bildschirm-Installation kann.")
        ]),
        WhatsNewRelease(version: "4.1.0", title: "Konten, 2FA & Webpanel", summary: "Benutzername statt Schlüssel, Zwei-Faktor-Schutz, Passkeys, Geräteverwaltung und das Admin-Panel im Browser.", items: [
            WhatsNewItem(symbol: "crown.fill", title: "Hauptadmin-Konto", detail: "Wechsle unter Konto & Sicherheit vom Hauptschlüssel zu Benutzername und Passwort. Alle Daten bleiben erhalten; der alte Schlüssel funktioniert weiter, bis du ihn widerrufst."),
            WhatsNewItem(symbol: "lock.shield.fill", title: "Zwei-Faktor-Schutz", detail: "6-stellige Codes aus Apple Passwörter oder jeder Authenticator-App, dazu Wiederherstellungscodes. Funktioniert sofort, auch ohne Domain."),
            WhatsNewItem(symbol: "person.badge.key.fill", title: "Passkeys", detail: "Mit eigener Domain meldest du dich per Face ID an – in der App und im Browser."),
            WhatsNewItem(symbol: "laptopcomputer.and.iphone", title: "Geräte & Sitzungen", detail: "Als Hauptadmin siehst du alle angemeldeten Geräte mit Modell und IP, kannst sie umbenennen, abmelden oder vorübergehend sperren."),
            WhatsNewItem(symbol: "safari.fill", title: "Webpanel", detail: "Das Admin-Panel läuft jetzt auch im Browser – auf demselben Port, mit denselben Benutzern und Rechten, für Handy und PC."),
            WhatsNewItem(symbol: "network", title: "Adresse & Domain", detail: "Die Verbindungsadresse lässt sich ohne neue Anmeldung auf deine Domain umstellen.")
        ]),
        WhatsNewRelease(version: "4.0.0", title: "Simplex Panel 4", summary: "Admin-Log, Staff-Chat mit Discord, Setup-Links, Ruhezeiten und eine kompaktere, schönere Oberfläche.", items: [
            WhatsNewItem(symbol: "list.bullet.clipboard.fill", title: "Admin-Log je Admin", detail: "Jede Admin-Aktion mit Ort, Ziel und Zeit – Teleports, Vanish, Strafen und mehr. Ungelesene Einträge als Zahl, alles mit einem Tipp gelesen."),
            WhatsNewItem(symbol: "bubble.left.and.text.bubble.right.fill", title: "Staff-Chat", detail: "Dein Discord-Staffkanal in der App: Bilder, Dateien und Sprachnachrichten in beide Richtungen, gesendet in deinem Namen, Admins einzeln oder alle pingen."),
            WhatsNewItem(symbol: "link.badge.plus", title: "Setup-Links", detail: "Einmalige Links (Standard 1 Stunde) richten den Admin-Zugang auf einem anderen iPhone ohne Eingaben ein."),
            WhatsNewItem(symbol: "moon.zzz.fill", title: "Ruhezeiten", detail: "Schlafenszeit und Arbeitszeit für Bark. Nach einem Notfallneustart kommt ein kritischer Hinweis – du entscheidest, ob er durchdringen darf."),
            WhatsNewItem(symbol: "rectangle.3.group.fill", title: "Neue Widgets & Design", detail: "Kompaktere Kopfzeilen, Schnellmenü, Admin-Suite-Live-Karte, schönere Diagramme, Schnellaktionen und eine Startanimation."),
            WhatsNewItem(symbol: "crown.fill", title: "Ränge über dem Namen", detail: "LuckPerms-Präfix, Suffix und Rangfarbe über dem Kopf und in der Tabliste – für Java und Bedrock.")
        ]),
        WhatsNewRelease(version: "3.3.0", title: "Zugänge & Beitrittshinweise", summary: "Verifizierungen verwalten, einzelne App-Rechte vergeben und Server-RAM absichern.", items: [
            WhatsNewItem(symbol: "person.badge.key.fill", title: "App-Benutzer", detail: "Benutzername und Passwort ergänzen die bestehenden Schlüssel. Rechte sind einzeln einstellbar; jeder berechtigte Nutzer verwaltet sein eigenes Bark-Gerät."),
            WhatsNewItem(symbol: "link", title: "Discord-Verifizierungen", detail: "Vorhandene Chat-Verifizierungen anzeigen, ändern, löschen oder manuell anlegen."),
            WhatsNewItem(symbol: "bell.badge", title: "Beitrittshinweise", detail: "Verifizierte Discord-Nutzer können bis zu zehn Spieler vormerken. Spieler verwalten Anfragen und deaktivieren Hinweise mit /joinnotify."),
            WhatsNewItem(symbol: "memorychip", title: "RAM-Notfallneustart", detail: "Der begrenzte Server-Container wird getrennt vom Java-Heap angezeigt. Bei dauerhaft hoher Nutzung startet auf Wunsch ein übersetzter Countdown.")
        ]),
        WhatsNewRelease(version: "3.2.0", title: "Simplex Panel 3.2", summary: "Bilder in Tickets, Benachrichtigungen je Dringlichkeit und deutlich schnellere Statistiken.", items: [
            WhatsNewItem(symbol: "photo.on.rectangle.angled", title: "Bilder & Dateien in Tickets", detail: "Anhänge aus Discord erscheinen mit Vorschau im Ticket. Du kannst selbst Bilder und Dateien senden – sie landen direkt im Discord-Ticket."),
            WhatsNewItem(symbol: "at", title: "Spieler erwähnen", detail: "Auf Wunsch erwähnt der Bot den Spieler im Discord-Ticket – nach deiner Bestätigung."),
            WhatsNewItem(symbol: "bell.badge.fill", title: "Bark je Dringlichkeit", detail: "Niedrig still, Normal normal, Hoch als dringende Mitteilung, Notfall als kritischer Alarm – Ruhezeiten werden beachtet."),
            WhatsNewItem(symbol: "xmark.bin.fill", title: "Aufräumen", detail: "Wartende Zustellungen verwerfen, Tickets direkt löschen (Discord-Thread inklusive) und Spielerwarnungen verwerfen."),
            WhatsNewItem(symbol: "chart.bar.xaxis", title: "Schnellere Statistiken", detail: "Alle Profile in einer Anfrage und sofortige Anzeige des letzten Stands."),
            WhatsNewItem(symbol: "arrow.triangle.2.circlepath", title: "Plugin-Updates", detail: "GitHub-Releases als Quelle, weniger erneutes Scannen und zuverlässiger Geyser-/Floodgate-Status.")
        ]),
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
