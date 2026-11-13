import SwiftUI

// MARK: - Hub

/// Everything UltraServers offers beyond the standard Pterodactyl client API, in one place.
struct UltraServersHub: View {
    let api: PanelAPI
    let server: Server
    var live: LiveServer?
    @State private var operation: Operation?
    var prefix: String { "servers/\(server.id)" }
    var body: some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: 8) {
                    Label("UltraServers", systemImage: "bolt.horizontal.circle.fill").font(.title2.bold()).foregroundStyle(Color.panelAccent)
                    Text("Automatische Backups, Player Manager, Subdomains, Reverse Proxy und die Installer deines Hosters – direkt über die Panel-API mit deinem Client-Schlüssel.").font(.footnote).foregroundStyle(.secondary)
                }.padding(.vertical, 4)
                Link(destination: UltraServers.webURL(base: api.base, server: server.id)) { Label("Server im Web-Panel öffnen", systemImage: "safari") }
            }
            Section("Backups") {
                NavigationLink { SystemBackupsView(api: api, server: server) } label: { row("Automatische Backups", "Alle 6 Stunden · ca. 2 Wochen", "clock.arrow.2.circlepath") }
                NavigationLink { BackupsView(api: api, server: server).navigationTitle("Backups") } label: { row("Manuelle Backups", "Erstellen, schützen, herunterladen", "externaldrive.badge.timemachine") }.requiresAccess(api, server.id, "backup.read")
            }
            Section("Spieler") {
                NavigationLink { UltraPlayersView(api: api, server: server, live: live) } label: { row("Player Manager", "Online, OPs, Whitelist, Banns, Offline", "person.2.badge.gearshape") }
            }
            Section("Netzwerk") {
                NavigationLink { UltraSubdomainsView(api: api, server: server) } label: { row("Subdomains", "play.deinedomain … ohne Port", "link") }
                NavigationLink { UltraProxyView(api: api, server: server) } label: { row("Reverse Proxy", "Domain mit SSL für Web-Plugins (Dynmap, BlueMap …)", "arrow.triangle.branch") }
                NavigationLink { ResourcesView(api: api, server: server, kind: .network) } label: { row("Ports & Allocations", "Ports hinzufügen, Notizen, primärer Port", "network") }.requiresAccess(api, server.id, "allocation.read")
            }
            Section("Software") {
                NavigationLink { UltraCatalogView(api: api, server: server, kind: .plugins) } label: { row("Plugin-Installer", "Plugins suchen und mit einer Version installieren", "puzzlepiece.extension") }
                NavigationLink { UltraCatalogView(api: api, server: server, kind: .modpacks) } label: { row("Modpacks", "Modpack suchen und installieren", "shippingbox.fill") }
                NavigationLink { MinecraftPropertiesView(api: api, server: server) } label: { row("Config-Editor", "server.properties mit Schaltern bearbeiten", "slider.horizontal.3") }.requiresAccess(api, server.id, "file.read-content")
            }
            Section("Datenbanken") {
                NavigationLink { ResourcesView(api: api, server: server, kind: .databases) } label: { row("MySQL-Datenbanken", "Erstellen, Zugangsdaten, Passwort erneuern", "externaldrive.connected.to.line.below") }.requiresAccess(api, server.id, "database.read")
                Button { importDatabase() } label: { row("Datenbank importieren", "Von einem anderen Host übernehmen", "square.and.arrow.down.on.square") }.requiresAccess(api, server.id, "database.create")
            }
            Section {
                web("Version wechseln / Game Switch", "arrow.triangle.2.circlepath")
                web("Server-Splitter (ab 8 GB, bis 2 Kind-Server)", "square.split.2x1")
                web("SFTP-Import von einem anderen Hoster", "tray.and.arrow.down")
                web("Latenztest (MTR) & Support-Ticket", "waveform.path.ecg")
            } header: { Text("Im Web-Panel") } footer: { Text("Diese Funktionen bietet UltraServers nur im Web-Panel an. Version wechseln und Game Switch können Dateien löschen – erstelle vorher ein Backup.") }
            Section {
                Link(destination: UltraServers.docsURL) { Label("UltraServers-Dokumentation", systemImage: "book") }
            } footer: { Text("Ports und Subdomains brauchen ggf. einen Neustart bzw. bis zu 12 Stunden, bis sie überall funktionieren. Der Scheduler von UltraServers läuft in UTC.") }
        }.panelScreen().navigationTitle("UltraServers").navigationBarTitleDisplayMode(.inline)
            .refreshable { await api.loadAccess(server: server.id) }
            .sheet(item: $operation) { OperationSheet(api: api, operation: $0) }
    }
    func row(_ title: String, _ detail: String, _ symbol: String) -> some View {
        Label { VStack(alignment: .leading, spacing: 2) { Text(title); Text(detail).font(.caption).foregroundStyle(.secondary) } } icon: { Image(systemName: symbol).foregroundStyle(Color.panelAccent) }
    }
    func web(_ title: String, _ symbol: String) -> some View {
        Link(destination: UltraServers.webURL(base: api.base, server: server.id)) { HStack { Label(title, systemImage: symbol); Spacer(); Image(systemName: "arrow.up.right.square").foregroundStyle(.secondary) } }
    }
    func importDatabase() {
        operation = Operation(title: "Datenbank importieren", detail: "Überschreibt Daten in der Zieldatenbank – am besten eine neue, leere Datenbank verwenden. Der Import kann Minuten bis eine Stunde dauern; den Fortschritt zeigt die Konsole.", path: prefix + "/databases/import", fields: [
            Field(key: "host", label: "Host der Quelldatenbank"), Field(key: "port", label: "Port", value: "3306", kind: .number),
            Field(key: "username", label: "Benutzername"), Field(key: "database", label: "Datenbankname"), Field(key: "password", label: "Passwort", kind: .secret)
        ], destructive: true, submit: "Import starten", transform: { values in
            var body = values
            guard !(values["host"]?.string ?? "").trimmingCharacters(in: .whitespaces).isEmpty, !(values["database"]?.string ?? "").isEmpty else { throw PanelError(message: "Host und Datenbankname sind erforderlich.") }
            body["port"] = .number(Double(values["port"]?.int ?? 3306))
            body["database_name"] = values["database"]
            return .object(body)
        })
    }
}

// MARK: - Automatic backups

struct SystemBackupsView: View {
    let api: PanelAPI
    let server: Server
    @State private var backups: [SystemBackup] = []
    @State private var loaded = false
    @State private var error: String?
    @State private var issue: String?
    @State private var operation: Operation?
    @State private var downloading: String?
    @State private var showTransfers = false
    var prefix: String { "servers/\(server.id)/system-backups" }
    struct Day: Identifiable { let title: String; let items: [SystemBackup]; var id: String { title } }
    var days: [Day] {
        let calendar = Calendar.current
        let groups = Dictionary(grouping: backups) { $0.created.map { calendar.startOfDay(for: $0) } ?? .distantPast }
        return groups.keys.sorted(by: >).map { day in
            let title = day == .distantPast ? "Ohne Datum" : calendar.isDateInToday(day) ? "Heute" : calendar.isDateInYesterday(day) ? "Gestern" : day.formatted(.dateTime.weekday(.wide).day().month(.wide))
            return Day(title: title, items: groups[day] ?? [])
        }
    }
    var body: some View {
        let summary = SystemBackupSummary(backups)
        List {
            Section { SystemBackupSummaryCard(summary: summary, loaded: loaded) }
            if let issue { Section { Label(issue, systemImage: "exclamationmark.triangle").foregroundStyle(.orange).font(.footnote) } }
            if loaded && backups.isEmpty && issue == nil { ContentUnavailableView("Noch keine automatischen Backups", systemImage: "clock.arrow.2.circlepath", description: Text("UltraServers legt alle 6 Stunden eines an. Neue Server zeigen das erste nach einigen Stunden.")) }
            ForEach(days) { day in
                Section(day.title) { ForEach(day.items) { backup in backupRow(backup) } }
            }
            Section {
                Label("Heute alle 6 Stunden, ältere Tage etwa ein Backup pro Tag, rund 2 Wochen zurück.", systemImage: "calendar")
                Label("Meist sind 8 Backups sichtbar. Das älteste wird kurz vor dem Ersetzen ausgeblendet, damit nichts mitten in der Rotation wiederhergestellt wird.", systemImage: "eye.slash")
                Label("Länger aufbewahren: herunterladen oder ein manuelles Backup erstellen.", systemImage: "square.and.arrow.down")
            } header: { Text("So funktioniert es") }.font(.footnote).foregroundStyle(.secondary)
        }.panelScreen().navigationTitle("Automatische Backups").navigationBarTitleDisplayMode(.inline)
            .task { await load() }.refreshable { await load() }
            .toolbar { ToolbarItem(placement: .topBarTrailing) { if downloading != nil { ProgressView() } else { TransferLink() } } }
            .sheet(item: $operation) { OperationSheet(api: api, operation: $0) { await load() } }
            .sheet(isPresented: $showTransfers) { TransfersView() }
            .errorAlert($error)
    }
    func backupRow(_ backup: SystemBackup) -> some View {
        HStack(spacing: 12) {
            Image(systemName: backup.hidden ? "clock.badge.exclamationmark" : "externaldrive.fill.badge.timemachine").font(.title3).foregroundStyle(backup.hidden ? Color.secondary : Color.panelAccent).frame(width: 30)
            VStack(alignment: .leading, spacing: 3) {
                Text(backup.created.map { $0.formatted(date: .omitted, time: .shortened) + " Uhr" } ?? backup.name).font(.headline).monospacedDigit()
                HStack(spacing: 6) {
                    if let created = backup.created { Text(created, style: .relative) }
                    if !backup.sizeLabel.isEmpty { Text("·"); Text(backup.sizeLabel) }
                    if backup.created != nil && backup.name != "Automatisches Backup" { Text("·"); Text(backup.name).lineLimit(1) }
                }.font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            Menu {
                Button("Herunterladen", systemImage: "arrow.down.circle") { download(backup) }.disabled(!backup.downloadable || downloading != nil)
                Button("Wiederherstellen", systemImage: "arrow.counterclockwise", role: .destructive) { restore(backup) }.disabled(!backup.restorable).requiresAccess(api, server.id, "backup.restore")
            } label: { Image(systemName: "ellipsis.circle").font(.title3).padding(4) }
        }.padding(.vertical, 4).accessibilityElement(children: .combine)
    }
    func load() async {
        do { backups = SystemBackup.decode(try await api.request(prefix)); issue = nil }
        catch is CancellationError {} catch { issue = error.localizedDescription }
        loaded = true
    }
    func restore(_ backup: SystemBackup) {
        let when = backup.created?.formatted(date: .abbreviated, time: .shortened) ?? backup.name
        operation = Operation(title: "Automatisches Backup wiederherstellen", detail: "Stand vom \(when). Stoppe den Server vorher. Ohne Leeren werden vorhandene Dateien überschrieben; Dateien, die im Backup fehlen, bleiben erhalten. Je nach Größe dauert das eine Weile.", path: prefix + "/\(backup.id)/restore", fields: [Field(key: "truncate", label: "Server vorher komplett leeren", value: "false", kind: .toggle, hint: "Empfohlen, wenn du exakt den Stand des Backups möchtest.")], destructive: true, submit: "Wiederherstellen", transform: { values in
            let wipe = values["truncate"] ?? .bool(false)
            return .object(["truncate": wipe, "wipe": wipe])
        })
    }
    func download(_ backup: SystemBackup) {
        downloading = backup.id
        Task { defer { downloading = nil }; do {
            guard !api.demo else { throw PanelError(message: "Downloads sind im Demomodus deaktiviert.") }
            let response = try await api.request(prefix + "/\(backup.id)/download")
            let signed = try BackupInfo.downloadURL(response, base: api.base)
            let stamp = backup.created.map { ISO8601DateFormatter().string(from: $0).replacingOccurrences(of: ":", with: "-") } ?? backup.id
            let suffix = URL(string: signed)?.path.lowercased().hasSuffix(".zip") == true ? ".zip" : ".tar.gz"
            try TransferManager.shared.download(signed, filename: "auto-backup-" + stamp + suffix, context: server.name + " · Automatisches Backup")
            showTransfers = true
        } catch { self.error = error.localizedDescription } }
    }
}

struct SystemBackupSummaryCard: View {
    let summary: SystemBackupSummary
    let loaded: Bool
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Label("Automatisch alle 6 Stunden", systemImage: "clock.arrow.2.circlepath").font(.headline)
                Spacer()
                if !loaded { ProgressView() } else { Text("\(summary.count)/\(UltraServers.visibleBackups)").monospacedDigit().foregroundStyle(.secondary) }
            }
            if let newest = summary.newest {
                HStack(alignment: .firstTextBaseline) {
                    stat("Letztes", newest.formatted(date: .omitted, time: .shortened), summary.overdue ? .orange : .primary)
                    if let next = summary.next { stat("Nächstes etwa", next.formatted(date: .omitted, time: .shortened), .primary) }
                    stat("Reicht zurück", summary.coveredDays == 0 ? "heute" : "\(summary.coveredDays) Tage", .primary)
                }
                if summary.overdue { Label("Das letzte automatische Backup ist älter als 9 Stunden. Prüfe den Server oder wende dich an den UltraServers-Support.", systemImage: "exclamationmark.triangle.fill").font(.caption).foregroundStyle(.orange) }
            } else if loaded { Text("Noch keine Daten").foregroundStyle(.secondary) }
        }.padding(.vertical, 4)
    }
    func stat(_ title: String, _ value: String, _ color: Color) -> some View {
        VStack(alignment: .leading, spacing: 2) { Text(title).font(.caption2).foregroundStyle(.secondary); Text(value).font(.subheadline.bold()).monospacedDigit().foregroundStyle(color) }.frame(maxWidth: .infinity, alignment: .leading)
    }
}

// MARK: - Player Manager

struct UltraPlayersView: View {
    let api: PanelAPI
    let server: Server
    var live: LiveServer?
    @State private var list = UltraPlayerList(groups: [], maximum: nil)
    @State private var loaded = false
    @State private var issue: String?
    @State private var error: String?
    @State private var search = ""
    @State private var selected: Selection?
    @State private var form: PlayerForm?
    @State private var notice: String?
    struct Selection: Identifiable { let group: String; let entry: UltraPlayerList.Entry; var id: String { group + "|" + entry.id } }
    struct PlayerForm: Identifiable { let action: UltraPlayerAction; var player: String; var id: String { action.rawValue + player } }
    var body: some View {
        List {
            if let notice { Section { Label(notice, systemImage: "checkmark.circle").foregroundStyle(Color.panelAccent).font(.footnote) } }
            if let issue { Section { Label(issue, systemImage: "exclamationmark.triangle").foregroundStyle(.orange).font(.footnote) } }
            if !loaded { ProgressView("Spieler laden …") }
            if loaded && list.groups.isEmpty && issue == nil { ContentUnavailableView("Keine Spielerdaten", systemImage: "person.2.slash", description: Text("Der Player Manager funktioniert nur für Minecraft-Server, die schon einmal gestartet wurden.")) }
            ForEach(list.groups) { group in
                let entries = group.entries.filter { search.isEmpty || $0.name.localizedCaseInsensitiveContains(search) }
                Section {
                    if entries.isEmpty { Text(search.isEmpty ? "Keine Einträge" : "Keine Treffer").foregroundStyle(.secondary) }
                    ForEach(entries) { entry in
                        Button { selected = Selection(group: group.key, entry: entry) } label: { playerRow(entry, online: group.key == "online") }.buttonStyle(.plain)
                    }
                } header: { Label(headerTitle(group), systemImage: group.symbol) }
            }
        }.panelScreen().navigationTitle("Player Manager").navigationBarTitleDisplayMode(.inline)
            .searchable(text: $search, prompt: "Spieler suchen")
            .task { await load() }.refreshable { await load() }
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Menu { ForEach([UltraPlayerAction.op, .whitelistAdd, .ban, .pardon, .banIP, .pardonIP]) { action in Button(action.title, systemImage: action.symbol) { form = PlayerForm(action: action, player: "") } } } label: { Image(systemName: "plus") }.requiresAccess(api, server.id, "control.console") } }
            .confirmationDialog(selected?.entry.name ?? "", isPresented: Binding(get: { selected != nil }, set: { if !$0 { selected = nil } }), titleVisibility: .visible, presenting: selected) { selection in
                ForEach(UltraPlayerAction.actions(for: selection.group)) { action in
                    Button(action.title, role: action.destructive ? .destructive : nil) {
                        if action.takesReason || action.destructive { form = PlayerForm(action: action, player: selection.entry.name) } else { Task { await run(action, selection.entry.name) } }
                    }
                }
                Button("Name kopieren") { UIPasteboard.general.string = selection.entry.name }
            } message: { selection in Text(selection.entry.uuid.isEmpty ? selection.entry.detail : selection.entry.uuid) }
            .sheet(item: $form) { form in
                ActionForm(title: form.action.title, detail: "Wird als Konsolenbefehl an den Server gesendet. Der Server muss laufen.", fields: fields(for: form), destructive: form.action.destructive, submit: form.action.title) { values in
                    let player = form.player.isEmpty ? (values["player"]?.string ?? "") : form.player
                    try await send(form.action, player, reason: values["reason"]?.string ?? "")
                }
            }
            .errorAlert($error)
    }
    func headerTitle(_ group: UltraPlayerList.Group) -> String {
        var title = "\(group.title) · \(group.entries.count)"
        if group.key == "online", let maximum = list.maximum { title += " / \(maximum)" }
        return title
    }
    func fields(for form: PlayerForm) -> [Field] {
        var fields: [Field] = []
        if form.player.isEmpty { fields.append(Field(key: "player", label: form.action == .banIP || form.action == .pardonIP ? "Spielername oder IP" : "Spielername")) }
        if form.action.takesReason { fields.append(Field(key: "reason", label: "Grund (optional)")) }
        return fields
    }
    func playerRow(_ entry: UltraPlayerList.Entry, online: Bool) -> some View {
        HStack(spacing: 12) {
            SuitePlayerAvatar(row: avatarRow(entry, online: online), size: 32)
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.name).font(.headline)
                if !entry.detail.isEmpty { Text(entry.detail).font(.caption).foregroundStyle(.secondary).lineLimit(2) }
            }
            Spacer()
            Image(systemName: "ellipsis").foregroundStyle(.secondary)
        }.contentShape(Rectangle()).padding(.vertical, 2)
    }
    func avatarRow(_ entry: UltraPlayerList.Entry, online: Bool) -> JSON {
        let client = entry.name.hasPrefix(".") ? "BEDROCK" : "JAVA"
        return .object(["id": .string(entry.uuid), "client": .string(client), "online": .bool(online)])
    }
    func load() async {
        do {
            let response = try await api.request("servers/\(server.id)/players")
            list = UltraPlayerList.decode(response); issue = nil
            if let snapshot = list.snapshot { live?.players = snapshot }
        } catch is CancellationError {} catch { issue = error.localizedDescription }
        loaded = true
    }
    func run(_ action: UltraPlayerAction, _ player: String) async { do { try await send(action, player) } catch { self.error = error.localizedDescription } }
    func send(_ action: UltraPlayerAction, _ player: String, reason: String = "") async throws {
        guard !api.demo else { throw PanelError(message: "Der Demomodus sendet keine Befehle.") }
        let command = try action.command(player: player, reason: reason)
        _ = try await api.request("servers/\(server.id)/command", method: "POST", body: .object(["command": .string(command)]))
        notice = "Gesendet: /" + command
        try? await Task.sleep(for: .seconds(1.5))
        await load()
    }
}

// MARK: - Subdomains

struct UltraSubdomainsView: View {
    let api: PanelAPI
    let server: Server
    @State private var data = UltraSubdomains(domains: [], records: [])
    @State private var loaded = false
    @State private var issue: String?
    @State private var error: String?
    @State private var creating = false
    @State private var operation: Operation?
    var prefix: String { "servers/\(server.id)/subdomain" }
    var body: some View {
        List {
            Section { Text("Eine Subdomain verbindet deine Domain mit diesem Server, Spieler brauchen dann keinen Port. Neue Einträge können bis zu 12 Stunden brauchen.").font(.footnote).foregroundStyle(.secondary) }
            if let issue { Section { Label(issue, systemImage: "exclamationmark.triangle").foregroundStyle(.orange).font(.footnote) } }
            if !loaded { ProgressView("Laden …") }
            if loaded && data.records.isEmpty && issue == nil { ContentUnavailableView("Keine Subdomains", systemImage: "link", description: Text("Tippe auf +, um eine zu erstellen.")) }
            ForEach(data.records) { record in
                HStack {
                    VStack(alignment: .leading, spacing: 3) { Text(record.host).font(.headline).textSelection(.enabled); if !record.detail.isEmpty { Text(record.detail).font(.caption).foregroundStyle(.secondary) } }
                    Spacer()
                    Menu {
                        Button("Adresse kopieren", systemImage: "doc.on.doc") { UIPasteboard.general.string = record.host }
                        Button("Löschen", systemImage: "trash", role: .destructive) { operation = Operation(title: "Subdomain löschen", detail: "\(record.host) wird entfernt. Spieler mit dieser Adresse erreichen den Server danach nicht mehr.", path: prefix + "/delete/\(record.id)", method: "DELETE", destructive: true, submit: "Löschen") }.disabled(record.id.isEmpty || record.id.contains("/"))
                    } label: { Image(systemName: "ellipsis.circle").font(.title3) }
                }.padding(.vertical, 4)
            }
            if !data.domains.isEmpty { Section("Verfügbare Domains") { ForEach(data.domains) { Text($0.name).foregroundStyle(.secondary) } } }
        }.panelScreen().navigationTitle("Subdomains").navigationBarTitleDisplayMode(.inline)
            .task { await load() }.refreshable { await load() }
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Erstellen", systemImage: "plus") { creating = true } } }
            .sheet(isPresented: $creating) { UltraSubdomainForm(api: api, path: prefix + "/create", domains: data.domains) { await load() } }
            .sheet(item: $operation) { OperationSheet(api: api, operation: $0) { await load() } }
            .errorAlert($error)
    }
    func load() async {
        do { data = UltraSubdomains.decode(try await api.request(prefix)); issue = nil } catch is CancellationError {} catch { issue = error.localizedDescription }
        loaded = true
    }
}

struct UltraSubdomainForm: View {
    @Environment(\.dismiss) private var dismiss
    let api: PanelAPI
    let path: String
    let domains: [UltraSubdomains.Domain]
    var onSuccess: () async -> Void
    @State private var label = ""
    @State private var domain = ""
    @State private var busy = false
    @State private var error: String?
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Name, z. B. play", text: $label).textInputAutocapitalization(.never).autocorrectionDisabled()
                    if domains.isEmpty { TextField("Domain-ID", text: $domain).keyboardType(.numberPad) }
                    else { Picker("Domain", selection: $domain) { ForEach(domains) { Text($0.name).tag($0.id) } } }
                } footer: { Text(preview) }
                Section { Button { Task { await create() } } label: { HStack { Spacer(); if busy { ProgressView() }; Text("Subdomain erstellen").bold(); Spacer() } }.disabled(busy || !UltraSubdomains.validLabel(label.lowercased()) || domain.isEmpty) }
            }.panelScreen().navigationTitle("Subdomain erstellen").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } } }
                .onAppear { if domain.isEmpty { domain = domains.first?.id ?? "" } }
                .errorAlert($error)
        }
    }
    var preview: String {
        let name = domains.first { $0.id == domain }?.name ?? ""
        return label.isEmpty ? "2–32 Zeichen: Kleinbuchstaben, Ziffern und Bindestriche." : (name.isEmpty ? label.lowercased() : label.lowercased() + "." + name)
    }
    func create() async {
        busy = true; defer { busy = false }
        do {
            guard !api.demo else { throw PanelError(message: "Der Demomodus erstellt keine Subdomains.") }
            let id: JSON = Int(domain).map { .number(Double($0)) } ?? .string(domain)
            _ = try await api.request(path, method: "POST", body: .object(["subdomain": .string(label.lowercased()), "domainId": id, "domain_id": id]))
            await onSuccess(); dismiss()
        } catch { self.error = error.localizedDescription }
    }
}

// MARK: - Reverse proxy

struct UltraProxyView: View {
    let api: PanelAPI
    let server: Server
    @State private var proxies: [UltraProxy] = []
    @State private var allocations: [JSON] = []
    @State private var loaded = false
    @State private var issue: String?
    @State private var creating = false
    @State private var operation: Operation?
    var prefix: String { "servers/\(server.id)/proxy" }
    var body: some View {
        List {
            Section {
                Text("Leitet eine Domain per HTTP(S) auf einen Port deines Servers – für Web-Plugins wie Dynmap, BlueMap oder Plan. Nicht für die Spielverbindung (dafür Subdomains oder einen SRV-Eintrag).").font(.footnote).foregroundStyle(.secondary)
                Label("Vorher beim DNS-Anbieter einen A-Eintrag auf die Server-IP setzen. Bei Cloudflare den Proxy (orange Wolke) ausschalten.", systemImage: "info.circle").font(.footnote)
            }
            if let issue { Section { Label(issue, systemImage: "exclamationmark.triangle").foregroundStyle(.orange).font(.footnote) } }
            if !loaded { ProgressView("Laden …") }
            if loaded && proxies.isEmpty && issue == nil { ContentUnavailableView("Kein Reverse Proxy", systemImage: "arrow.triangle.branch") }
            ForEach(proxies) { proxy in
                HStack {
                    Image(systemName: proxy.ssl ? "lock.fill" : "lock.open").foregroundStyle(proxy.ssl ? Color.green : Color.secondary)
                    VStack(alignment: .leading, spacing: 3) { Text(proxy.domain).font(.headline).textSelection(.enabled); Text([proxy.ssl ? "HTTPS" : "HTTP", proxy.target].filter { !$0.isEmpty }.joined(separator: " → ")).font(.caption).foregroundStyle(.secondary) }
                    Spacer()
                    Menu {
                        if let url = URL(string: (proxy.ssl ? "https://" : "http://") + proxy.domain) { Link(destination: url) { Label("Öffnen", systemImage: "safari") } }
                        Button("Löschen", systemImage: "trash", role: .destructive) { operation = Operation(title: "Reverse Proxy löschen", detail: "\(proxy.domain) wird nicht mehr weitergeleitet.", path: prefix + "/delete/\(proxy.id)", method: "DELETE", destructive: true, submit: "Löschen") }.disabled(proxy.id.isEmpty || proxy.id.contains("/"))
                    } label: { Image(systemName: "ellipsis.circle").font(.title3) }
                }.padding(.vertical, 4)
            }
        }.panelScreen().navigationTitle("Reverse Proxy").navigationBarTitleDisplayMode(.inline)
            .task { await load() }.refreshable { await load() }
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Erstellen", systemImage: "plus") { creating = true } } }
            .sheet(isPresented: $creating) { UltraProxyForm(api: api, path: prefix + "/create", allocations: allocations) { await load() } }
            .sheet(item: $operation) { OperationSheet(api: api, operation: $0) { await load() } }
    }
    func load() async {
        do { proxies = UltraProxy.decode(try await api.request(prefix)); issue = nil } catch is CancellationError {} catch { issue = error.localizedDescription }
        allocations = (try? await api.list("servers/\(server.id)/network/allocations")) ?? allocations
        loaded = true
    }
}

struct UltraProxyForm: View {
    @Environment(\.dismiss) private var dismiss
    let api: PanelAPI
    let path: String
    let allocations: [JSON]
    var onSuccess: () async -> Void
    @State private var domain = ""
    @State private var allocation = ""
    @State private var ssl = true
    @State private var letsEncrypt = true
    @State private var certificate = ""
    @State private var key = ""
    @State private var busy = false
    @State private var error: String?
    var body: some View {
        NavigationStack {
            Form {
                Section("Domain") { TextField("map.deinedomain.de", text: $domain).keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled() }
                Section("Ziel-Port") {
                    if allocations.isEmpty { TextField("Allocation-ID", text: $allocation).keyboardType(.numberPad) }
                    else { Picker("Port", selection: $allocation) { ForEach(allocations, id: \.["id"].string) { row in Text("\(row["port"].string)" + (row["notes"].string.isEmpty ? "" : " · " + row["notes"].string)).tag(row["id"].string) } } }
                }
                Section {
                    Toggle("SSL aktivieren", isOn: $ssl)
                    if ssl { Toggle("Let's Encrypt verwenden", isOn: $letsEncrypt) }
                    if ssl && !letsEncrypt {
                        TextEditor(text: $certificate).font(.caption.monospaced()).frame(minHeight: 80).overlay(alignment: .topLeading) { if certificate.isEmpty { Text("Zertifikat (PEM)").foregroundStyle(.secondary).padding(6) } }
                        TextEditor(text: $key).font(.caption.monospaced()).frame(minHeight: 80).overlay(alignment: .topLeading) { if key.isEmpty { Text("Privater Schlüssel (PEM)").foregroundStyle(.secondary).padding(6) } }
                    }
                } footer: { Text("Let's Encrypt stellt das Zertifikat automatisch aus, sobald der A-Eintrag auf den Server zeigt.") }
                Section { Button { Task { await create() } } label: { HStack { Spacer(); if busy { ProgressView() }; Text("Reverse Proxy erstellen").bold(); Spacer() } }.disabled(busy || !UltraProxy.validDomain(domain.lowercased().trimmingCharacters(in: .whitespaces)) || allocation.isEmpty) }
            }.panelScreen().navigationTitle("Reverse Proxy").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } } }
                .onAppear { if allocation.isEmpty { allocation = (allocations.first { !$0["is_default"].bool } ?? allocations.first)?["id"].string ?? "" } }
                .errorAlert($error)
        }
    }
    func create() async {
        busy = true; defer { busy = false }
        do {
            guard !api.demo else { throw PanelError(message: "Der Demomodus erstellt keinen Proxy.") }
            let id: JSON = Int(allocation).map { .number(Double($0)) } ?? .string(allocation)
            var body: [String: JSON] = ["domain": .string(domain.lowercased().trimmingCharacters(in: .whitespaces)), "allocation_id": id, "allocation": id, "ssl": .bool(ssl), "ssl_enabled": .bool(ssl), "letsencrypt": .bool(ssl && letsEncrypt), "use_letsencrypt": .bool(ssl && letsEncrypt)]
            if ssl && !letsEncrypt { body["ssl_cert"] = .string(certificate); body["ssl_key"] = .string(key) }
            _ = try await api.request(path, method: "POST", body: .object(body))
            await onSuccess(); dismiss()
        } catch { self.error = error.localizedDescription }
    }
}

// MARK: - Plugin and modpack installer

struct UltraCatalogView: View {
    enum Kind: String { case plugins, modpacks
        var title: String { self == .plugins ? "Plugin-Installer" : "Modpacks" }
        var singular: String { self == .plugins ? "Plugin" : "Modpack" }
    }
    let api: PanelAPI
    let server: Server
    let kind: Kind
    @State private var items: [UltraCatalogItem] = []
    @State private var raw: JSON = .null
    @State private var search = ""
    @State private var loaded = false
    @State private var busy = false
    @State private var issue: String?
    var body: some View {
        List {
            Section { Text(kind == .plugins ? "Sucht über den Plugin-Installer von UltraServers. Installierte Plugins landen in /plugins und werden beim nächsten Neustart geladen." : "Installiert ein Modpack über UltraServers. Das kann Serverdateien ersetzen – erstelle vorher ein Backup.").font(.footnote).foregroundStyle(.secondary) }
            if let issue { Section { Label(issue, systemImage: "exclamationmark.triangle").foregroundStyle(.orange).font(.footnote) } }
            if busy { ProgressView("Suchen …") }
            ForEach(items) { item in
                NavigationLink { UltraVersionsView(api: api, server: server, kind: kind, item: item) } label: { UltraCatalogRow(item: item) }
            }
            if loaded && items.isEmpty && issue == nil {
                if raw != .null && raw.rows.isEmpty && UltraServers.records(raw).isEmpty { Section("Antwort des Panels") { NativeValues(value: raw) } }
                else { ContentUnavailableView.search(text: search) }
            }
        }.panelScreen().navigationTitle(kind.title).navigationBarTitleDisplayMode(.inline)
            .searchable(text: $search, prompt: "\(kind.singular) suchen")
            .onSubmit(of: .search) { Task { await load() } }
            .task { if !loaded { await load() } }
            .refreshable { await load() }
    }
    func load() async {
        busy = true; defer { busy = false; loaded = true }
        let query = search.trimmingCharacters(in: .whitespacesAndNewlines)
        do {
            let response = try await api.request("servers/\(server.id)/\(kind.rawValue)", query: query.isEmpty ? ["page": "1"] : ["query": query, "search": query, "page": "1"])
            raw = response; items = UltraCatalogItem.decode(response); issue = nil
        } catch is CancellationError {} catch { issue = error.localizedDescription }
    }
}

struct UltraCatalogRow: View {
    let item: UltraCatalogItem
    var body: some View {
        HStack(spacing: 12) {
            AsyncImage(url: item.icon) { $0.resizable().scaledToFit() } placeholder: { Image(systemName: "puzzlepiece.extension.fill").foregroundStyle(.secondary) }
                .frame(width: 40, height: 40).clipShape(RoundedRectangle(cornerRadius: 9))
            VStack(alignment: .leading, spacing: 3) {
                Text(item.name).font(.headline).lineLimit(1)
                if !item.summary.isEmpty { Text(item.summary).font(.caption).foregroundStyle(.secondary).lineLimit(2) }
                HStack(spacing: 6) {
                    if !item.author.isEmpty { Text(item.author) }
                    if item.downloads > 0 { Label(item.downloads.formatted(.number.notation(.compactName)), systemImage: "arrow.down.circle") }
                    if !item.provider.isEmpty { Text(item.provider.capitalized) }
                }.font(.caption2).foregroundStyle(.secondary)
            }
        }.padding(.vertical, 2)
    }
}

struct UltraVersionsView: View {
    let api: PanelAPI
    let server: Server
    let kind: UltraCatalogView.Kind
    let item: UltraCatalogItem
    @State private var versions: [UltraCatalogVersion] = []
    @State private var loaded = false
    @State private var issue: String?
    @State private var operation: Operation?
    @State private var installed: String?
    var body: some View {
        List {
            Section { UltraCatalogRow(item: item) }
            if let installed { Section { Label(installed, systemImage: "checkmark.circle.fill").foregroundStyle(Color.panelAccent) } }
            if let issue { Section { Label(issue, systemImage: "exclamationmark.triangle").foregroundStyle(.orange).font(.footnote) } }
            if !loaded { ProgressView("Versionen laden …") }
            Section("Versionen") {
                if loaded && versions.isEmpty { Button("Neueste Version installieren", systemImage: "arrow.down.circle") { install(nil) } }
                ForEach(versions) { version in
                    Button { install(version) } label: {
                        VStack(alignment: .leading, spacing: 3) { Text(version.name).font(.headline); if !version.detail.isEmpty { Text(version.detail).font(.caption).foregroundStyle(.secondary) } }
                    }
                }
            }
        }.panelScreen().navigationTitle(item.name).navigationBarTitleDisplayMode(.inline)
            .task { await load() }
            .sheet(item: $operation) { OperationSheet(api: api, operation: $0) { installed = "Installation gestartet. Starte den Server neu, sobald sie fertig ist." } }
    }
    func load() async {
        defer { loaded = true }
        var query = ["id": item.id, "project": item.id]
        query[kind == .plugins ? "pluginId" : "modpackId"] = item.id
        if !item.provider.isEmpty { query["provider"] = item.provider }
        do { versions = UltraCatalogVersion.decode(try await api.request("servers/\(server.id)/\(kind.rawValue)/versions", query: query)); issue = nil }
        catch is CancellationError {} catch { issue = error.localizedDescription }
    }
    func install(_ version: UltraCatalogVersion?) {
        let idKey = kind == .plugins ? "pluginId" : "modpackId"
        var body: [String: JSON] = ["id": .string(item.id)]
        body[idKey] = .string(item.id)
        body[kind == .plugins ? "plugin" : "modpack"] = .string(item.id)
        if let version { body["versionId"] = .string(version.id); body["version_id"] = .string(version.id); body["version"] = .string(version.id) }
        if !item.provider.isEmpty { body["provider"] = .string(item.provider) }
        operation = Operation(title: "\(kind.singular) installieren", detail: kind == .modpacks ? "\(item.name) \(version?.name ?? "") wird installiert. Das kann Welten, Mods und Konfigurationen ersetzen. Erstelle vorher ein Backup und stoppe den Server." : "\(item.name) \(version?.name ?? "") wird in /plugins installiert. Danach den Server neu starten.", path: "servers/\(server.id)/\(kind.rawValue)/install", fixed: body, destructive: kind == .modpacks, submit: "Installieren")
    }
}

// MARK: - Generic provider values

/// Native recursive rows for provider-specific schemas; no serialized JSON blocks.
struct NativeValues: View {
    let value: JSON
    var body: some View {
        switch value {
        case .object(let fields):
            ForEach(fields.keys.sorted(), id: \.self) { key in
                if let item = fields[key] {
                    switch item {
                    case .object, .array: DisclosureGroup(key.replacingOccurrences(of: "_", with: " ").capitalized) { AnyView(NativeValues(value: item)) }
                    default: scalar(key, item)
                    }
                }
            }
        case .array(let items):
            if items.isEmpty { Text("Keine Einträge").foregroundStyle(.secondary) }
            ForEach(Array(items.enumerated()), id: \.offset) { index, item in
                switch item { case .object, .array: DisclosureGroup("Eintrag \(index + 1)") { AnyView(NativeValues(value: item)) }; default: scalar("\(index + 1)", item) }
            }
        default: scalar("Wert", value)
        }
    }
    func scalar(_ key: String, _ item: JSON) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(key.replacingOccurrences(of: "_", with: " ").capitalized).font(.caption).foregroundStyle(.secondary)
            Text(item == .null ? "–" : item.string).frame(maxWidth: .infinity, alignment: .leading).textSelection(.enabled)
        }.padding(.vertical, 2)
    }
}
