import Foundation

/// Bounded directory queries, never a recursive server-wide file walk.
enum PlayerNBTSearch {
    static func paths(player: String, roots: [String] = ["/world"], list: (String) async throws -> [JSON]) async throws -> [JSON] {
        guard let uuid = UUID(uuidString: player) else { throw PanelError(message: "Ungültige Spieler-UUID") }
        let filename = uuid.uuidString.lowercased() + ".dat"
        var rows: [JSON] = []
        for root in Array(Set(roots)).sorted() {
            for suffix in ["players/data", "playerdata"] {
                let directory = pathJoin(root, suffix)
                do {
                    let files = try await list(directory)
                    if let match = files.first(where: { $0["is_file"].bool && $0["name"].string.lowercased() == filename }) {
                        rows.append(.object(["id": .string(pathJoin(directory, match["name"].string)), "name": .string(directory), "panelOnly": .bool(true)]))
                    }
                } catch { if RequestFailure.isCancellation(error) { throw error } }
            }
        }
        return rows
    }
}
