import SwiftUI

struct SuiteCommunityToolsView: View {
    let suite: SuiteAPI
    var initialPlayer: String? = nil
    @State private var overview: JSON = .null
    @State private var player: JSON = .null
    @State private var lookup = ""
    @State private var offerSearch = ""
    @State private var actions: [SuiteAction] = []
    @State private var busy = false
    @State private var finding = false
    @State private var error: String?

    var body: some View {
        List {
            if overview == .null {
                if busy { ProgressView("Community-Verwaltung wird geladen …") }
                else { Button("Erneut laden", systemImage: "arrow.clockwise") { Task { await reload() } } }
            } else {
                settings
                if overview["economy"].bool {
                    playerTools
                    shop
                } else {
                    Section { Label("Punkte, Shop und Quick Delivery sind auf diesem Server nicht verfügbar.", systemImage: "info.circle") }
                }
            }
        }
        .panelScreen()
        .navigationTitle("Community")
        .errorAlert($error)
        .task {
            await reload()
            if let initialPlayer, player == .null, overview["economy"].bool { await loadPlayer(initialPlayer) }
        }
        .refreshable {
            await reload()
            if player != .null { await loadPlayer(player["player"].string) }
        }
    }

    private var settings: some View {
        Section("Server") {
            let invite = overview["invite"].string
            actionRow("community.invite", values: ["expected": .string(invite), "invite": .string(invite)]) {
                labeled("Discord-Einladung", value: invite.isEmpty ? "Kein Code gesetzt" : "discord.gg/" + invite)
            }
            dayRow("main")
            if overview["scoreboard"]["second"]["enabled"].bool { dayRow("second") }
        }
    }

    private func dayRow(_ world: String) -> some View {
        let row = overview["scoreboard"][world]
        let days = row["days"].string
        return actionRow("community.days", values: ["world": .string(world), "expected": .string(days), "days": .string(days)]) {
            labeled(row["name"].string, value: days + " Spieltage")
        }
    }

    private var playerTools: some View {
        Section("Punktekonto & Quick Delivery") {
            SuiteLivePlayerField(suite: suite, title: "Minecraft-Name oder UUID", text: $lookup)
            Button("Spieler laden", systemImage: "magnifyingglass") { Task { await loadPlayer(lookup) } }
                .disabled(lookup.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || finding)
            if finding { ProgressView() }
            if player != .null {
                let target = player["player"]
                actionRow("community.points", values: ["player": target, "expected": player["balance"]]) {
                    labeled(player["name"].string, value: player["balance"].string + " SP")
                }
                actionRow("community.delivery", values: ["player": target]) {
                    Label("Quick Delivery erstellen", systemImage: "envelope.badge.fill")
                }
                ForEach(player["deliveries"].array, id: \.["id"].string) { note in
                    labeled(note["titleDE"].string, value: deliveryStatus(note))
                }
            }
        }
    }

    private var shop: some View {
        Section("Shop-Angebote") {
            let offers = overview["offers"].array
            if offers.count > 5 { TextField("Angebote filtern", text: $offerSearch) }
            let filtered = offers.filter { SuiteSearch.matches(offerSearch, in: $0["titleDE"].string + " " + $0["titleEN"].string) }
            if filtered.isEmpty { Text("Keine Angebote gefunden").foregroundStyle(.secondary) }
            ForEach(filtered, id: \.["id"].string) { offer in
                actionRow("community.shop", values: offerValues(offer)) {
                    labeled(offer["titleDE"].string, value: offerSummary(offer))
                }
            }
        }
    }

    private func offerValues(_ row: JSON) -> [String: JSON] {
        ["offer": row["id"], "expected": row["revision"], "expectedStock": row["stock"],
         "titleDE": row["titleDE"], "titleEN": row["titleEN"], "price": row["price"], "stock": row["stock"],
         "enabled": .string(row["enabled"].bool ? "true" : "false")]
    }

    private func offerSummary(_ row: JSON) -> String {
        let price = row["price"].string + (row["itemPayment"].bool ? " Items" : " SP")
        let stock = row["stock"].string == "-1" ? "unbegrenzt" : row["stock"].string
        let status = row["enabled"].bool ? "" : " · Inaktiv"
        return price + " · Bestand " + stock + status
    }

    private func deliveryStatus(_ note: JSON) -> String {
        if note["ack"].double > 0 { return "Bestätigt" }
        return note["sent"].double > 0 ? "Zugestellt" : "Wartet auf Zustellung"
    }

    private func labeled(_ title: String, value: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
            Text(value).font(.caption).foregroundStyle(.secondary)
        }.accessibilityElement(children: .combine)
    }

    @ViewBuilder private func actionRow<Label: View>(_ id: String, values: [String: JSON], @ViewBuilder label: () -> Label) -> some View {
        if let action = actions.first(where: { $0.id == id && $0.data["allowed"].bool }) {
            NavigationLink {
                SuiteActionView(suite: suite, action: action, preset: values, onSaved: {
                    if id == "community.points" || id == "community.delivery" {
                        await loadPlayer(values["player"]?.string ?? "")
                    } else {
                        await reload()
                    }
                })
            } label: { label() }
        } else if id != "community.delivery" {
            label()
        }
    }

    private func reload() async {
        guard !busy else { return }
        busy = true
        defer { busy = false }
        do {
            overview = try await suite.data("community-tools")
            actions = try await suite.request("schema")["actions"].array.map { SuiteAction(data: $0) }
        } catch {
            if !RequestFailure.isCancellation(error) && !Task.isCancelled { self.error = error.localizedDescription }
        }
    }

    private func loadPlayer(_ query: String) async {
        guard !finding else { return }
        finding = true
        defer { finding = false }
        do {
            player = try await suite.data("community-tools", query: ["player": query.trimmingCharacters(in: .whitespacesAndNewlines)])
            lookup = player["name"].string
        } catch {
            if !RequestFailure.isCancellation(error) && !Task.isCancelled { self.error = error.localizedDescription }
        }
    }
}
