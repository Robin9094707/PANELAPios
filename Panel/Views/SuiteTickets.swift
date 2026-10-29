import SwiftUI
import PhotosUI
import UniformTypeIdentifiers
import UIKit

/// Labels shared by the ticket list, detail and settings. Values match Admin Suite 9.4.0.
enum SuiteTicketText {
    static let categories = ["general", "technical", "report", "discord", "other"]
    static let priorities = ["low", "normal", "high", "urgent"]
    static func category(_ id: String) -> String { switch id { case "technical": return "Technischer Support"; case "report": return "Spieler melden"; case "discord": return "Discord"; case "other": return "Sonstiges"; default: return "Allgemeiner Support" } }
    static func categorySymbol(_ id: String) -> String { switch id { case "technical": return "wrench.and.screwdriver.fill"; case "report": return "exclamationmark.bubble.fill"; case "discord": return "bubble.left.and.bubble.right.fill"; case "other": return "tray.fill"; default: return "questionmark.bubble.fill" } }
    static func priority(_ id: String) -> String { switch id { case "low": return "Niedrig"; case "high": return "Hoch"; case "urgent": return "Notfall"; default: return "Normal" } }
    static func priorityColor(_ id: String) -> Color { switch id { case "low": return .green; case "high": return .orange; case "urgent": return .red; default: return .yellow } }
    static func status(_ id: String) -> String { switch id { case "answered": return "Beantwortet"; case "closed": return "Geschlossen"; default: return "Offen" } }
    static func statusSymbol(_ id: String) -> String { switch id { case "answered": return "arrowshape.turn.up.left.fill"; case "closed": return "lock.fill"; default: return "envelope.badge.fill" } }
    static func source(_ id: String) -> String { switch id { case "discord": return "Discord"; case "app": return "App"; default: return "Minecraft" } }
}

enum SuiteTicketFilter: String, CaseIterable, Identifiable {
    case active, open, answered, urgent, closed, all
    var id: String { rawValue }
    var title: String { switch self { case .active: return "Aktiv"; case .open: return "Offen"; case .answered: return "Beantwortet"; case .urgent: return "Notfall"; case .closed: return "Geschlossen"; case .all: return "Alle" } }
}

@MainActor final class SuiteTicketsModel: ObservableObject {
    @Published var rows: [JSON] = []
    @Published var counts: JSON = .null
    @Published var busy = false
    @Published var error: String?
    @Published var next = -1
    @Published var enabled = true
    let suite: SuiteAPI
    init(_ suite: SuiteAPI) { self.suite = suite }
    func load(_ filter: SuiteTicketFilter, query: String, more: Bool = false) async {
        busy = true; defer { busy = false }
        do {
            let data = try await suite.data("tickets", query: ["status": filter.rawValue, "q": query, "offset": String(more ? max(0, next) : 0)])
            try Task.checkCancellation()
            let items = data["items"].array
            if more { let known = Set(rows.map { $0["id"].string }); rows += items.filter { !known.contains($0["id"].string) } } else { rows = items }
            counts = data["counts"]; next = data["next"].int; enabled = data["enabled"] == .null || data["enabled"].bool; error = nil
        } catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }
}

struct SuiteTicketsView: View {
    let suite: SuiteAPI
    var panel: PanelAPI? = nil
    var server: Server? = nil
    @StateObject private var model: SuiteTicketsModel
    @Environment(\.scenePhase) private var scenePhase
    @State private var filter = SuiteTicketFilter.active
    @State private var query = ""
    init(suite: SuiteAPI, panel: PanelAPI? = nil, server: Server? = nil) { self.suite = suite; self.panel = panel; self.server = server; _model = StateObject(wrappedValue: SuiteTicketsModel(suite)) }
    var body: some View {
        List {
            Section {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 120), spacing: 10)], spacing: 10) {
                    counter("Offen", model.counts["open"].int, "envelope.badge.fill", Color.panelAccent, .open)
                    counter("Beantwortet", model.counts["answered"].int, "arrowshape.turn.up.left.fill", .blue, .answered)
                    counter("Notfall", model.counts["urgent"].int, "light.beacon.max.fill", .red, .urgent)
                    counter("Geschlossen", model.counts["closed"].int, "lock.fill", .secondary, .closed)
                }.listRowInsets(EdgeInsets(top: 8, leading: 8, bottom: 8, trailing: 8))
                if model.counts["waitingDelivery"].int > 0 { Label("\(model.counts["waitingDelivery"].int) Antwort(en) warten auf den Spieler – Zustellung nach kurzer Online-Zeit", systemImage: "clock.badge").font(.caption).foregroundStyle(.secondary) }
                if !model.enabled { Label("Das Ticketsystem ist auf dem Server deaktiviert.", systemImage: "pause.circle").foregroundStyle(.orange) }
            }
            Section {
                Picker("Filter", selection: $filter) { ForEach(SuiteTicketFilter.allCases) { Text($0.title).tag($0) } }.pickerStyle(.menu)
                if model.rows.isEmpty && !model.busy { ContentUnavailableView(filter == .active ? "Keine offenen Tickets" : "Keine Tickets", systemImage: "checkmark.seal", description: Text(filter == .active ? "Alles erledigt. Neue Tickets erscheinen hier und per Bark." : "Für diesen Filter gibt es nichts.")) }
                ForEach(model.rows, id: \.["id"].string) { row in
                    NavigationLink { SuiteTicketDetailView(suite: suite, id: row["id"].string, panel: panel, server: server) } label: { SuiteTicketRow(row: row) }
                        .accessibilityIdentifier("ticket-" + row["id"].string)
                }
                if model.next > 0 { Button("Weitere laden") { Task { await model.load(filter, query: query, more: true) } }.disabled(model.busy) }
            } header: { HStack { Text("Tickets"); Spacer(); if model.busy { ProgressView().controlSize(.small) } } }
        }.panelScreen().navigationTitle("Tickets").searchable(text: $query, prompt: "Ticket, Spieler oder Betreff").errorAlert($model.error)
            .toolbar { if suite.scope == "full" { NavigationLink { SuiteTicketSettingsView(suite: suite) } label: { Image(systemName: "gearshape") }.accessibilityLabel("Ticketeinstellungen") } }
            .refreshable { await model.load(filter, query: query) }
            .onChange(of: filter) { _, value in Task { await model.load(value, query: query) } }
            .task(id: query) { try? await Task.sleep(for: .milliseconds(250)); await model.load(filter, query: query) }
            .task(id: scenePhase) { guard scenePhase == .active else { return }; while !Task.isCancelled { do { try await Task.sleep(for: .seconds(15)) } catch { return }; await model.load(filter, query: query) } }
    }
    func counter(_ title: String, _ value: Int, _ symbol: String, _ tint: Color, _ target: SuiteTicketFilter) -> some View {
        Button { filter = target } label: {
            VStack(alignment: .leading, spacing: 6) {
                Label(title, systemImage: symbol).font(.caption.weight(.semibold)).foregroundStyle(tint)
                Text(model.counts == .null ? "–" : String(value)).font(.title2.bold()).monospacedDigit().contentTransition(.numericText())
            }.frame(maxWidth: .infinity, alignment: .leading).padding(12).background((filter == target ? tint.opacity(0.22) : tint.opacity(0.10)), in: RoundedRectangle(cornerRadius: 14))
        }.buttonStyle(.plain)
    }
}

struct SuiteTicketOwnerAvatar: View {
    let ticket: JSON
    var size: CGFloat = 36
    var body: some View {
        if UUID(uuidString: ticket["owner"].string) != nil {
            SuitePlayerAvatar(row: .object(["id": ticket["owner"], "name": ticket["ownerName"], "online": .bool(ticket["ownerOnline"].bool), "client": .string(ticket["ownerPlatform"].string)]), size: size)
        } else {
            Image(systemName: "bubble.left.and.bubble.right.fill").font(.system(size: size * 0.45)).foregroundStyle(.indigo).frame(width: size, height: size).background(Color.indigo.opacity(0.12), in: RoundedRectangle(cornerRadius: size * 0.22))
        }
    }
}

struct SuiteTicketRow: View {
    let row: JSON
    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            SuiteTicketOwnerAvatar(ticket: row)
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    Text("#" + row["id"].string).font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                    Text(row["subject"].string).font(.subheadline.weight(.semibold)).lineLimit(1)
                }
                Text(row["ownerName"].string + " · " + SuiteTicketText.category(row["category"].string)).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                if !row["preview"].string.isEmpty { Text((row["lastRole"].string == "staff" ? "Team: " : "") + row["preview"].string).font(.caption).foregroundStyle(.secondary).lineLimit(2) }
                HStack(spacing: 6) {
                    SuiteTicketChip(text: SuiteTicketText.priority(row["priority"].string), symbol: row["priority"].string == "urgent" ? "light.beacon.max.fill" : "flag.fill", color: SuiteTicketText.priorityColor(row["priority"].string))
                    SuiteTicketChip(text: SuiteTicketText.status(row["status"].string), symbol: SuiteTicketText.statusSymbol(row["status"].string), color: row["status"].string == "closed" ? .secondary : Color.panelAccent)
                    if row["pending"].int > 0 { SuiteTicketChip(text: "Zustellung offen", symbol: "clock", color: .blue) }
                    if !row["claimedBy"].string.isEmpty { SuiteTicketChip(text: row["claimedBy"].string, symbol: "person.fill.checkmark", color: .teal) }
                }
            }
            Spacer(minLength: 0)
            if let date = SuitePlayerPresentation.date(row["updated"]) { Text(date, style: .relative).font(.caption2).foregroundStyle(.secondary).multilineTextAlignment(.trailing) }
        }.padding(.vertical, 2)
    }
}

struct SuiteTicketChip: View {
    let text: String
    let symbol: String
    let color: Color
    var body: some View { Label(text, systemImage: symbol).font(.caption2.weight(.semibold)).lineLimit(1).padding(.horizontal, 7).padding(.vertical, 3).background(color.opacity(0.15), in: Capsule()).foregroundStyle(color) }
}

struct SuiteTicketDetailView: View {
    let suite: SuiteAPI
    let id: String
    var panel: PanelAPI? = nil
    var server: Server? = nil
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.dismiss) private var dismiss
    @State private var ticket: JSON = .null
    @State private var reply = ""
    @State private var busy = false
    @State private var error: String?
    @State private var notice: String?
    @State private var deleted = false
    @State private var confirmDelete = false
    @State private var confirmClose = false
    @State private var confirmDiscard = false
    @State private var confirmMention = false
    @State private var mention = false
    @State private var photos: [PhotosPickerItem] = []
    @State private var drafts: [SuiteTicketDraft] = []
    @State private var importing = false
    @State private var preview: SuiteTicketPreview?
    var canManage: Bool { suite.scope == "full" || suite.scope == "manage" }
    var active: Bool { ticket["status"].string != "closed" }
    var attachmentsSupported: Bool { suite.features.contains("tickets-v2") }
    var waiting: [JSON] { ticket["items"].array.filter { $0["role"].string == "staff" && !$0["delivered"].bool } }
    var canMention: Bool { attachmentsSupported && !ticket["discordUser"].string.isEmpty && !ticket["channel"].string.isEmpty }
    var body: some View {
        Group {
            if deleted { ContentUnavailableView("Ticket gelöscht", systemImage: "trash", description: Text("Ticket #\(id) wurde entfernt.")) }
            else { content }
        }.navigationTitle("Ticket #" + id).navigationBarTitleDisplayMode(.inline)
    }
    var content: some View {
        ScrollViewReader { proxy in
            List {
                if ticket == .null { ProgressView("Ticket wird geladen …") }
                else {
                    header
                    if !waiting.isEmpty && canManage && attachmentsSupported {
                        Section {
                            Label(waiting.count == 1 ? "1 Antwort wartet auf den Spieler" : "\(waiting.count) Antworten warten auf den Spieler", systemImage: "clock.badge").font(.subheadline)
                            Button("Zustellung im Spiel verwerfen", systemImage: "xmark.bin", role: .destructive) { confirmDiscard = true }
                        } footer: { Text("Verworfene Antworten bleiben im Verlauf und auf Discord, werden dem Spieler im Spiel aber nicht mehr zugestellt.") }
                    }
                    Section("Verlauf") {
                        ForEach(Array(ticket["items"].array.enumerated()), id: \.offset) { _, message in
                            SuiteTicketBubble(message: message, ownerOnline: ticket["ownerOnline"].bool, suite: suite, ticket: id, preview: $preview)
                                .listRowSeparator(.hidden).id("m-" + message["id"].string)
                                .contextMenu {
                                    Button("Text kopieren", systemImage: "doc.on.doc") { Clipboard.copy(message["text"].string) }
                                    if canManage && attachmentsSupported && message["role"].string == "staff" && !message["delivered"].bool { Button("Zustellung verwerfen", systemImage: "xmark.bin", role: .destructive) { Task { await perform(["action": .string("discard"), "message": .string(message["id"].string)]) } } }
                                }
                        }
                    }
                    if canManage { composer } else { Section { Text("Zum Antworten wird ein manage- oder full-App-Zugang benötigt.").font(.footnote).foregroundStyle(.secondary) } }
                }
            }.panelScreen().errorAlert($error)
                .toolbar { if canManage && ticket != .null { actionsMenu } }
                .refreshable { await load() }
                .task(id: scenePhase) {
                    guard scenePhase == .active else { return }
                    await load(); if let last = ticket["items"].array.last { proxy.scrollTo("m-" + last["id"].string, anchor: .bottom) }
                    while !Task.isCancelled && !deleted { do { try await Task.sleep(for: .seconds(5)) } catch { return }; await load() }
                }
                .onChange(of: ticket["items"].array.count) { old, new in if new > old, let last = ticket["items"].array.last { withAnimation { proxy.scrollTo("m-" + last["id"].string, anchor: .bottom) } } }
                .onChange(of: photos) { _, items in Task { await addPhotos(items) } }
                .sheet(item: $preview) { SuiteTicketImageViewer(suite: suite, ticket: id, preview: $0) }
                .sheet(isPresented: $importing) { FileImportPicker(multiple: true, retainSelection: true) { result in importing = false; if case .success(let urls) = result { addFiles(urls) } else if case .failure(let failure) = result { error = failure.localizedDescription } }.ignoresSafeArea() }
                .confirmationDialog("Ticket endgültig löschen?", isPresented: $confirmDelete, titleVisibility: .visible) { Button("Löschen", role: .destructive) { Task { await delete() } } } message: { Text(active ? "Das Ticket ist noch offen. Es wird geschlossen und gelöscht; der Discord-Thread bzw. -Kanal wird entfernt oder – falls Discord das nicht erlaubt – geschlossen." : "Das Ticket und sein Verlauf werden entfernt; der Discord-Thread bzw. -Kanal wird entfernt oder geschlossen.") }
                .confirmationDialog("Ticket schließen?", isPresented: $confirmClose, titleVisibility: .visible) { Button("Schließen", role: .destructive) { Task { await perform(["action": .string("close")]) } } } message: { Text("Der Spieler erhält einen Hinweis. Auf Discord wird der Ticket-Thread archiviert.") }
                .confirmationDialog("Wartende Zustellungen verwerfen?", isPresented: $confirmDiscard, titleVisibility: .visible) { Button("Verwerfen", role: .destructive) { Task { await perform(["action": .string("discard"), "message": .string("0")]) } } } message: { Text("\(waiting.count) Antwort(en) werden dem Spieler im Spiel nicht mehr zugestellt.") }
                .confirmationDialog("\(ticket["ownerName"].string) auf Discord erwähnen?", isPresented: $confirmMention, titleVisibility: .visible) { Button("Mit Erwähnung senden") { Task { await send(confirmed: true) } } } message: { Text("Der Bot schreibt @\(ticket["ownerName"].string) im Discord-Ticket an; der Spieler bekommt eine Discord-Benachrichtigung.") }
        }
    }
    var composer: some View {
        Section {
            TextField(active ? "Antwort an " + ticket["ownerName"].string : "Antwort öffnet das Ticket wieder", text: $reply, axis: .vertical).lineLimit(2...8).accessibilityIdentifier("ticketReply")
            if !drafts.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 10) { ForEach(drafts) { draft in SuiteTicketDraftChip(draft: draft) { drafts.removeAll { $0.id == draft.id } } } }
                }
            }
            if attachmentsSupported {
                HStack(spacing: 10) {
                    PhotosPicker(selection: $photos, maxSelectionCount: 4, matching: .images) { Label("Bilder", systemImage: "photo.on.rectangle") }.buttonStyle(.glass)
                    Button("Datei", systemImage: "paperclip") { importing = true }.buttonStyle(.glass)
                    Spacer()
                }
                if canMention { Toggle(isOn: $mention) { Label("\(ticket["ownerName"].string) auf Discord erwähnen", systemImage: "at") } }
            }
            Button { Task { await send(confirmed: false) } } label: { Label(busy ? "Senden …" : (drafts.isEmpty ? "Antwort senden" : "Senden (\(drafts.count) Anhang\(drafts.count == 1 ? "" : "e"))"), systemImage: "paperplane.fill") }
                .buttonStyle(.glassProminent).disabled(busy || (reply.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && drafts.isEmpty))
            if let notice { Label(notice, systemImage: "checkmark.circle").font(.caption).foregroundStyle(.secondary) }
        } header: { Text("Antworten") } footer: { Text(deliveryHint + " **Fett**, *kursiv* und Zeilenumbrüche werden übernommen.") }
    }
    var deliveryHint: String {
        if ticket["owner"].string.isEmpty { return "Die Antwort erscheint im Discord-Ticket des Spielers." }
        let delay = max(5, ticket["deliveryDelay"].int)
        return ticket["ownerOnline"].bool ? "Spieler ist online · Zustellung im Spiel nach \(delay) s ununterbrochener Online-Zeit" + (ticket["linked"].bool ? " und sofort auf Discord." : ".") : "Spieler ist offline · Die Antwort wartet und erscheint nach dem nächsten Beitritt (\(delay) s Online-Zeit)" + (ticket["linked"].bool ? ", auf Discord sofort." : ".")
    }
    var header: some View {
        Section {
            HStack(alignment: .top, spacing: 12) {
                SuiteTicketOwnerAvatar(ticket: ticket, size: 52)
                VStack(alignment: .leading, spacing: 5) {
                    Text(ticket["subject"].string).font(.headline)
                    HStack(spacing: 6) {
                        Text(ticket["ownerName"].string).font(.subheadline)
                        if ticket["ownerOnline"].bool { SuiteTicketChip(text: "Online", symbol: "circle.fill", color: .green) }
                        if ticket["linked"].bool { SuiteTicketChip(text: "Discord verknüpft", symbol: "link", color: .indigo) }
                    }
                    HStack(spacing: 6) {
                        SuiteTicketChip(text: SuiteTicketText.priority(ticket["priority"].string), symbol: "flag.fill", color: SuiteTicketText.priorityColor(ticket["priority"].string))
                        SuiteTicketChip(text: SuiteTicketText.status(ticket["status"].string), symbol: SuiteTicketText.statusSymbol(ticket["status"].string), color: active ? Color.panelAccent : .secondary)
                    }
                }
            }
            LabeledContent("Thema") { Label(SuiteTicketText.category(ticket["category"].string), systemImage: SuiteTicketText.categorySymbol(ticket["category"].string)) }
            LabeledContent("Erstellt über", value: SuiteTicketText.source(ticket["source"].string))
            if let date = SuitePlayerPresentation.date(ticket["created"]) { LabeledContent("Erstellt") { Text(date, format: .dateTime.day().month().hour().minute()) } }
            if !ticket["claimedBy"].string.isEmpty { LabeledContent("Übernommen von", value: ticket["claimedBy"].string) }
            if !ticket["closedBy"].string.isEmpty { LabeledContent("Geschlossen von", value: ticket["closedBy"].string) }
            if !ticket["channel"].string.isEmpty, !ticket["guild"].string.isEmpty, let url = URL(string: "https://discord.com/channels/" + ticket["guild"].string + "/" + ticket["channel"].string) { Link(destination: url) { Label("Im Discord öffnen", systemImage: "arrow.up.right.square") } }
            if let panel, let server, UUID(uuidString: ticket["owner"].string) != nil { NavigationLink { SuitePlayerView(suite: suite, panel: panel, server: server, player: ticket["owner"].string, actions: []) } label: { Label("Spielerprofil öffnen", systemImage: "person.crop.circle") } }
        }
    }
    var actionsMenu: some View {
        Menu {
            if active {
                Button("Übernehmen", systemImage: "person.fill.checkmark") { Task { await perform(["action": .string("claim")]) } }
                Menu("Priorität", systemImage: "flag") { ForEach(SuiteTicketText.priorities, id: \.self) { value in Button(SuiteTicketText.priority(value), systemImage: ticket["priority"].string == value ? "checkmark" : "flag") { Task { await perform(["action": .string("priority"), "priority": .string(value)]) } } } }
                Button("Ticket schließen", systemImage: "lock", role: .destructive) { confirmClose = true }
            } else { Button("Wieder öffnen", systemImage: "lock.open") { Task { await perform(["action": .string("reopen")]) } } }
            Button("Verlauf kopieren", systemImage: "doc.on.doc") { Clipboard.copy(ticket["items"].array.map { $0["author"].string + ": " + $0["text"].string }.joined(separator: "\n")) }
            if suite.scope == "full" { Button("Löschen", systemImage: "trash", role: .destructive) { confirmDelete = true } }
        } label: { Image(systemName: "ellipsis.circle") }.accessibilityLabel("Ticketaktionen")
    }
    static func isMissing(_ error: Error) -> Bool { let text = error.localizedDescription.lowercased(); return text.contains("nicht gefunden") || text.contains("not found") }
    func load() async {
        guard !deleted else { return }
        do { ticket = try await suite.data("ticket", query: ["id": id]) }
        catch {
            if RequestFailure.isCancellation(error) { return }
            // Deleted elsewhere (Discord, another device): show a calm empty state instead of an alert.
            if Self.isMissing(error) { deleted = true } else { self.error = error.localizedDescription }
        }
    }
    @discardableResult func perform(_ values: [String: JSON]) async -> Bool {
        busy = true; defer { busy = false }
        var body = values; body["id"] = .string(id)
        do {
            let result = try await suite.request("tickets", method: "POST", body: .object(body))
            if result["deleted"].bool { deleted = true; return true }
            if result["ticket"] != .null { ticket = result["ticket"] } else { await load() }
            notice = result["description"].string; Feedback.success(); return true
        } catch {
            if Self.isMissing(error) { deleted = true; return false }
            self.error = error.localizedDescription; return false
        }
    }
    func delete() async {
        // Old suites (9.4.0) need the ticket closed first so Discord is archived before removal.
        if active && !attachmentsSupported { guard await perform(["action": .string("close")]) else { return } }
        if await perform(["action": .string("delete")]) || deleted { deleted = true; Feedback.success(); try? await Task.sleep(for: .milliseconds(700)); dismiss() }
    }
    func send(confirmed: Bool) async {
        let text = reply.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty || !drafts.isEmpty else { return }
        if mention && canMention && !confirmed { confirmMention = true; return }
        if drafts.isEmpty {
            if await perform(["action": .string("reply"), "text": .string(text), "mention": .bool(mention && canMention)]) { reply = ""; mention = false }
            return
        }
        busy = true; defer { busy = false }
        do {
            for (index, draft) in drafts.enumerated() {
                let result = try await suite.upload("tickets/upload", query: ["id": id, "name": draft.name, "text": index == 0 ? text : "", "mention": String(index == 0 && mention && canMention)], data: draft.data, contentType: draft.type)
                if result["ticket"] != .null { ticket = result["ticket"] }
            }
            drafts = []; reply = ""; mention = false; notice = "Anhänge gesendet."; Feedback.success()
        } catch { self.error = error.localizedDescription }
    }
    func addPhotos(_ items: [PhotosPickerItem]) async {
        guard !items.isEmpty else { return }
        for item in items {
            guard let data = try? await item.loadTransferable(type: Data.self), let image = UIImage(data: data) else { continue }
            // Re-encode as JPEG: HEIC is not shown by Discord and very large photos exceed the 8 MiB limit.
            let scaled = SuiteTicketDraft.downscale(image, maxPixel: 2400)
            guard let jpeg = scaled.jpegData(compressionQuality: 0.82), jpeg.count <= 8 * 1024 * 1024 else { error = "Ein Bild ist zu groß."; continue }
            drafts.append(SuiteTicketDraft(name: "bild-\(drafts.count + 1).jpg", type: "image/jpeg", data: jpeg, thumbnail: scaled))
        }
        photos = []; if drafts.count > 4 { drafts = Array(drafts.prefix(4)); notice = "Maximal 4 Anhänge pro Nachricht." }
    }
    func addFiles(_ urls: [URL]) {
        for url in urls.prefix(max(0, 4 - drafts.count)) {
            defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
            guard let data = try? Data(contentsOf: url), !data.isEmpty, data.count <= 8 * 1024 * 1024 else { error = "Dateien bis 8 MiB werden unterstützt."; continue }
            let type = UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "application/octet-stream"
            drafts.append(SuiteTicketDraft(name: url.lastPathComponent, type: type, data: data, thumbnail: type.hasPrefix("image/") ? UIImage(data: data) : nil))
        }
    }
}

struct SuiteTicketDraft: Identifiable {
    let id = UUID()
    let name: String
    let type: String
    let data: Data
    let thumbnail: UIImage?
    static func downscale(_ image: UIImage, maxPixel: CGFloat) -> UIImage {
        let largest = max(image.size.width, image.size.height) * image.scale
        guard largest > maxPixel else { return image }
        let factor = maxPixel / largest, size = CGSize(width: image.size.width * image.scale * factor, height: image.size.height * image.scale * factor)
        let format = UIGraphicsImageRendererFormat(); format.scale = 1
        return UIGraphicsImageRenderer(size: size, format: format).image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
    }
}

struct SuiteTicketDraftChip: View {
    let draft: SuiteTicketDraft
    let remove: () -> Void
    var body: some View {
        ZStack(alignment: .topTrailing) {
            Group {
                if let image = draft.thumbnail { Image(uiImage: image).resizable().scaledToFill() }
                else { VStack(spacing: 4) { Image(systemName: "doc.fill").font(.title2); Text(draft.name).font(.caption2).lineLimit(2) }.padding(6).foregroundStyle(.secondary) }
            }.frame(width: 72, height: 72).clipShape(RoundedRectangle(cornerRadius: 12)).background(Color.secondary.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
            Button(action: remove) { Image(systemName: "xmark.circle.fill").symbolRenderingMode(.palette).foregroundStyle(.white, .black.opacity(0.6)) }.buttonStyle(.plain).offset(x: 6, y: -6).accessibilityLabel("Anhang entfernen")
        }.padding(.top, 6)
    }
}

/// Attachment bytes are cached in memory per suite and ticket; thumbnails reuse the full image.
@MainActor final class SuiteAttachmentCache {
    static let shared = SuiteAttachmentCache()
    private let cache = NSCache<NSString, NSData>()
    init() { cache.totalCostLimit = 64 * 1024 * 1024 }
    func data(_ suite: SuiteAPI, ticket: String, id: String) async throws -> Data {
        let key = (suite.bookmarkScope + "|" + ticket + "|" + id) as NSString
        if let cached = cache.object(forKey: key) { return cached as Data }
        let data = try await suite.raw("ticket-file", query: ["ticket": ticket, "id": id])
        cache.setObject(data as NSData, forKey: key, cost: data.count); return data
    }
}

struct SuiteTicketPreview: Identifiable { let id = UUID(); let images: [JSON]; let index: Int }

struct SuiteTicketThumbnail: View {
    let suite: SuiteAPI
    let ticket: String
    let attachment: JSON
    @State private var image: UIImage?
    @State private var failed = false
    var body: some View {
        ZStack {
            if let image { Image(uiImage: image).resizable().scaledToFill() }
            else if failed { Image(systemName: "photo.badge.exclamationmark").foregroundStyle(.secondary) }
            else { ProgressView() }
        }.frame(width: 110, height: 110).clipShape(RoundedRectangle(cornerRadius: 12)).background(Color.secondary.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
            .task(id: attachment["id"].string) {
                do { let data = try await SuiteAttachmentCache.shared.data(suite, ticket: ticket, id: attachment["id"].string); image = await Task.detached { UIImage(data: data).map { SuiteTicketDraft.downscale($0, maxPixel: 360) } }.value; failed = image == nil }
                catch { failed = true }
            }
    }
}

struct SuiteTicketAttachments: View {
    let suite: SuiteAPI
    let ticket: String
    let items: [JSON]
    @Binding var preview: SuiteTicketPreview?
    @State private var share: ShareItem?
    @State private var loading: String?
    var images: [JSON] { items.filter { $0["image"].bool } }
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if !images.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(Array(images.enumerated()), id: \.offset) { index, item in
                            Button { preview = SuiteTicketPreview(images: images, index: index) } label: { SuiteTicketThumbnail(suite: suite, ticket: ticket, attachment: item) }.buttonStyle(.plain).accessibilityLabel("Bild " + item["name"].string)
                        }
                    }
                }
            }
            ForEach(items.filter { !$0["image"].bool }, id: \.["id"].string) { item in
                Button { Task { await open(item) } } label: {
                    HStack { Image(systemName: "doc.fill"); VStack(alignment: .leading) { Text(item["name"].string).font(.caption.weight(.semibold)).lineLimit(1); Text(bytes(item["size"].double)).font(.caption2).foregroundStyle(.secondary) }; Spacer(); if loading == item["id"].string { ProgressView() } else { Image(systemName: "square.and.arrow.down") } }
                        .padding(8).background(Color.secondary.opacity(0.12), in: RoundedRectangle(cornerRadius: 10))
                }.buttonStyle(.plain)
            }
        }.sheet(item: $share) { ShareSheet(url: $0.url) }
    }
    func open(_ item: JSON) async {
        loading = item["id"].string; defer { loading = nil }
        guard let data = try? await SuiteAttachmentCache.shared.data(suite, ticket: ticket, id: item["id"].string) else { return }
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("ticket-" + UUID().uuidString)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let name = item["name"].string.replacingOccurrences(of: "/", with: "_")
        let url = folder.appendingPathComponent(name.isEmpty ? "datei" : name)
        if (try? data.write(to: url)) != nil { share = ShareItem(url: url) }
    }
}

struct SuiteTicketImageViewer: View {
    let suite: SuiteAPI
    let ticket: String
    let preview: SuiteTicketPreview
    @Environment(\.dismiss) private var dismiss
    @State private var index = 0
    @State private var loaded: [String: UIImage] = [:]
    @State private var share: ShareItem?
    var body: some View {
        NavigationStack {
            TabView(selection: $index) {
                ForEach(Array(preview.images.enumerated()), id: \.offset) { offset, item in
                    SuiteZoomableImage(image: loaded[item["id"].string]).tag(offset)
                        .task { if loaded[item["id"].string] == nil, let data = try? await SuiteAttachmentCache.shared.data(suite, ticket: ticket, id: item["id"].string) { loaded[item["id"].string] = UIImage(data: data) } }
                }
            }.tabViewStyle(.page(indexDisplayMode: preview.images.count > 1 ? .always : .never)).background(.black)
                .navigationTitle(preview.images.indices.contains(index) ? preview.images[index]["name"].string : "Bild").navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) { Button("Fertig") { dismiss() } }
                    ToolbarItem(placement: .topBarLeading) { Button("Teilen", systemImage: "square.and.arrow.up") { shareCurrent() }.disabled(!preview.images.indices.contains(index) || loaded[preview.images[index]["id"].string] == nil) }
                }
                .sheet(item: $share) { ShareSheet(url: $0.url) }
        }.onAppear { index = preview.index }
    }
    func shareCurrent() {
        let item = preview.images[index]
        guard let image = loaded[item["id"].string], let data = image.jpegData(compressionQuality: 0.95) else { return }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("ticket-\(ticket)-\(index + 1).jpg")
        if (try? data.write(to: url)) != nil { share = ShareItem(url: url) }
    }
}

struct SuiteZoomableImage: View {
    let image: UIImage?
    @State private var scale: CGFloat = 1
    @GestureState private var pinch: CGFloat = 1
    var body: some View {
        ZStack {
            Color.black
            if let image {
                Image(uiImage: image).resizable().scaledToFit().scaleEffect(scale * pinch)
                    .gesture(MagnifyGesture().updating($pinch) { value, state, _ in state = value.magnification }.onEnded { value in scale = min(5, max(1, scale * value.magnification)) })
                    .onTapGesture(count: 2) { withAnimation { scale = scale > 1 ? 1 : 2.5 } }
            } else { ProgressView().tint(.white) }
        }
    }
}

struct SuiteTicketBubble: View {
    let message: JSON
    let ownerOnline: Bool
    var suite: SuiteAPI? = nil
    var ticket = ""
    @Binding var preview: SuiteTicketPreview?
    var role: String { message["role"].string }
    /// Inline Markdown (bold, italic, links) with preserved line breaks; falls back to plain text.
    var formatted: AttributedString { (try? AttributedString(markdown: message["text"].string, options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace))) ?? AttributedString(message["text"].string) }
    var body: some View {
        if role == "system" {
            Text(message["text"].string).font(.caption).foregroundStyle(.secondary).frame(maxWidth: .infinity).multilineTextAlignment(.center)
        } else {
            HStack {
                if role == "staff" { Spacer(minLength: 40) }
                VStack(alignment: role == "staff" ? .trailing : .leading, spacing: 4) {
                    HStack(spacing: 5) {
                        Text(message["author"].string).font(.caption.weight(.semibold))
                        Text(role == "staff" ? "Team · " + SuiteTicketText.source(message["source"].string) : SuiteTicketText.source(message["source"].string)).font(.caption2).foregroundStyle(.secondary)
                    }
                    if !(message["attachments"].array.isEmpty == false && message["text"].string.hasPrefix("📎 ")) {
                        Text(formatted).textSelection(.enabled).padding(10).background(role == "staff" ? Color.panelAccent.opacity(0.18) : Color.secondary.opacity(0.12), in: RoundedRectangle(cornerRadius: 14))
                    }
                    if let suite, !message["attachments"].array.isEmpty { SuiteTicketAttachments(suite: suite, ticket: ticket, items: message["attachments"].array, preview: $preview) }
                    HStack(spacing: 4) {
                        if let date = SuitePlayerPresentation.date(message["at"]) { Text(date, style: .time) }
                        if role == "staff" {
                            if message["discarded"].bool { Image(systemName: "xmark.bin"); Text("im Spiel verworfen") }
                            else { Image(systemName: message["delivered"].bool ? "checkmark.circle.fill" : "clock"); Text(message["delivered"].bool ? "zugestellt" : (ownerOnline ? "wird zugestellt" : "wartet auf Spieler")) }
                        }
                    }.font(.caption2).foregroundStyle(.secondary)
                }
                if role != "staff" { Spacer(minLength: 40) }
            }
        }
    }
}

struct SuiteTicketSettingsView: View {
    let suite: SuiteAPI
    @State private var data: JSON = .null
    @State private var enabled = true
    @State private var discordEnabled = true
    @State private var channelId = ""
    @State private var mode = "thread"
    @State private var categoryId = ""
    @State private var logChannelId = ""
    @State private var roles: Set<String> = []
    @State private var maxOpen = 3
    @State private var cooldown = 60
    @State private var delay = 20
    @State private var joinHint = true
    @State private var bark = true
    @State private var pingStaff = true
    @State private var busy = false
    @State private var error: String?
    @State private var notice: String?
    var body: some View {
        Form {
            if data == .null { ProgressView("Einstellungen und Discord-Diagnose laden …") }
            else {
                Section {
                    Toggle("Ticketsystem aktiv", isOn: $enabled)
                    Stepper("Max. \(maxOpen) offene Tickets pro Spieler", value: $maxOpen, in: 1...20)
                    Stepper("Neues Ticket frühestens nach \(cooldown) s", value: $cooldown, in: 0...3600, step: 30)
                    Stepper("Antworten nach \(delay) s Online-Zeit zustellen", value: $delay, in: 5...300, step: 5)
                    Toggle("Spielern /ticket wöchentlich vorschlagen", isOn: $joinHint)
                    Toggle("Bark bei neuen Tickets", isOn: $bark)
                } header: { Text("Minecraft") } footer: { Text("Spieler öffnen Tickets mit /ticket – Java mit Menüs und Chat, Bedrock mit nativen Formularen. Antworten des Teams warten, bis der Spieler online ist, und kommen als privater Ping mit Öffnen-Link (Java) bzw. /ticket view (Bedrock). Notfall-Tickets kommen bei Bark als kritischer Alarm, außer während deiner Ruhezeit (Benachrichtigungen → Ruhezeiten).") }
                Section {
                    Toggle("Tickets auf Discord", isOn: $discordEnabled)
                    Picker("Ticketkanal (Panel)", selection: $channelId) { Text("Nicht festgelegt").tag(""); unlisted(channelId, in: data["channels"]); ForEach(channels, id: \.["id"].string) { row in Text("#" + row["name"].string).tag(row["id"].string) } }
                    Picker("Ticket-Art", selection: $mode) { Text("Private Threads im Ticketkanal").tag("thread"); Text("Eigene private Kanäle").tag("channel") }
                    if mode == "channel" { Picker("Kategorie für Ticketkanäle", selection: $categoryId) { Text("Keine Kategorie").tag(""); unlisted(categoryId, in: data["categories"]); ForEach(data["categories"].array, id: \.["id"].string) { row in Text(row["name"].string).tag(row["id"].string) } } }
                    Picker("Protokollkanal (optional)", selection: $logChannelId) { Text("Keiner").tag(""); unlisted(logChannelId, in: data["channels"]); ForEach(channels, id: \.["id"].string) { row in Text("#" + row["name"].string).tag(row["id"].string) } }
                    Toggle("Teamrollen bei neuen Tickets erwähnen", isOn: $pingStaff)
                } header: { Text("Discord · gleicher Bot wie die Admin Suite") } footer: { Text(data["botReady"].bool ? (data["botConnected"].bool ? "Der Admin-Suite-Bot ist verbunden – kein zusätzliches Token nötig. Alternativ in Discord: /ticket setup." : "Der Bot ist eingerichtet, aber gerade nicht verbunden.") : "Zuerst den Discord-Bot der Admin Suite einrichten (Discord-Einstellungen). Tickets funktionieren auch ohne Discord im Spiel und in der App.") }
                Section {
                    if data["roles"].array.isEmpty { Text("Keine Rollen geladen.").foregroundStyle(.secondary) }
                    ForEach(data["roles"].array.sorted { $0["position"].int > $1["position"].int }, id: \.["id"].string) { role in
                        Button { if roles.contains(role["id"].string) { roles.remove(role["id"].string) } else { roles.insert(role["id"].string) } } label: {
                            HStack { Circle().fill(role["color"].int == 0 ? Color.secondary : Color(red: Double((role["color"].int >> 16) & 255) / 255, green: Double((role["color"].int >> 8) & 255) / 255, blue: Double(role["color"].int & 255) / 255)).frame(width: 10, height: 10); Text(role["name"].string).foregroundStyle(.primary); Spacer(); if roles.contains(role["id"].string) { Image(systemName: "checkmark").foregroundStyle(Color.panelAccent) } }
                        }
                    }
                } header: { Text("Teamrollen") } footer: { Text("Diese Rollen sehen Tickets und werden benachrichtigt. Ohne Auswahl gelten die Admin-Rollen des Discord-Bots.") }
                Section {
                    Button { Task { await save(panel: false) } } label: { Label("Einstellungen speichern", systemImage: "checkmark.circle.fill") }.disabled(busy)
                    Button { Task { await save(panel: true) } } label: { Label("Speichern & Ticket-Panel posten", systemImage: "rectangle.and.paperclip") }.disabled(busy || channelId.isEmpty || !data["botReady"].bool)
                    if busy { ProgressView() }
                    if let notice { Label(notice, systemImage: "info.circle").font(.footnote).foregroundStyle(.secondary) }
                }
                Section {
                    ForEach(Array(data["diagnostics"].array.enumerated()), id: \.offset) { _, check in
                        HStack(alignment: .top, spacing: 10) {
                            Image(systemName: check["level"].string == "ok" ? "checkmark.circle.fill" : check["level"].string == "warn" ? "exclamationmark.triangle.fill" : "xmark.octagon.fill").foregroundStyle(check["level"].string == "ok" ? .green : check["level"].string == "warn" ? .orange : .red)
                            VStack(alignment: .leading, spacing: 2) { Text(check["title"].string).font(.subheadline.weight(.semibold)); Text(check["detail"].string.replacingOccurrences(of: "<@&", with: "@").replacingOccurrences(of: ">", with: "")).font(.caption).foregroundStyle(.secondary) }
                        }
                    }
                    Button("Diagnose erneut ausführen", systemImage: "stethoscope") { Task { await load() } }.disabled(busy)
                } header: { Text("Diagnose") } footer: { Text("Prüft die echten Bot-Rechte im Ticketkanal, in der Kategorie und im Protokollkanal sowie die Sichtbarkeit für Spieler und Team.") }
            }
        }.panelScreen().navigationTitle("Ticketeinstellungen").navigationBarTitleDisplayMode(.inline).errorAlert($error)
            .task { await load() }.refreshable { await load() }
    }
    var channels: [JSON] { data["channels"].array.sorted { $0["position"].int < $1["position"].int } }
    /// A saved ID stays selectable when Discord is offline or did not list it, so saving other settings never erases it.
    @ViewBuilder func unlisted(_ id: String, in rows: JSON) -> some View {
        if !id.isEmpty && !rows.array.contains(where: { $0["id"].string == id }) { Text("Gespeichert · " + id).tag(id) }
    }
    func load() async {
        busy = true; defer { busy = false }
        do {
            let value = try await suite.data("ticket-settings"); data = value
            enabled = value["enabled"].bool; discordEnabled = value["discordEnabled"].bool; channelId = value["channelId"].string; mode = value["mode"].string == "channel" ? "channel" : "thread"
            categoryId = value["categoryId"].string; logChannelId = value["logChannelId"].string; roles = Set(value["staffRoleIds"].array.map(\.string))
            maxOpen = max(1, value["maxOpen"].int); cooldown = value["cooldown"].int; delay = max(5, value["deliveryDelay"].int); joinHint = value["joinHint"].bool; bark = value["bark"].bool; pingStaff = value["pingStaff"].bool
        } catch { if !RequestFailure.isCancellation(error) { self.error = error.localizedDescription } }
    }
    func save(panel: Bool) async {
        busy = true
        let body: [String: JSON] = ["enabled": .bool(enabled), "discordEnabled": .bool(discordEnabled), "channelId": .string(channelId), "mode": .string(mode), "categoryId": .string(categoryId), "logChannelId": .string(logChannelId), "staffRoleIds": .array(roles.sorted().map(JSON.string)), "maxOpen": .number(Double(maxOpen)), "cooldown": .number(Double(cooldown)), "deliveryDelay": .number(Double(delay)), "joinHint": .bool(joinHint), "bark": .bool(bark), "pingStaff": .bool(pingStaff)]
        do {
            let saved = try await suite.request("tickets/settings", method: "POST", body: .object(body))
            notice = saved["description"].string
            if panel { let posted = try await suite.request("tickets/settings", method: "POST", body: .object(["postPanel": .bool(true)])); notice = posted["title"].string + " · " + posted["description"].string }
            Feedback.success()
        } catch { self.error = error.localizedDescription }
        busy = false
        await load()
    }
}
