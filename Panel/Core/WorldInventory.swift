import Foundation

struct WorldStorage: Equatable {
    var bytes: Double = 0
    var files = 0
    var regions = 0
    var playerFiles = 0
    var directories = 0
    var partial = false
    mutating func include(_ rows: [JSON], path: String) {
        directories += 1
        for row in rows where row["is_file"].bool {
            files += 1; bytes += max(0, row["size"].double)
            let name = row["name"].string.lowercased()
            if name.hasSuffix(".mca") { regions += 1 }
            if path.hasSuffix("/playerdata") && name.hasSuffix(".dat") { playerFiles += 1 }
        }
    }
    static func safeName(_ name: String) -> Bool { !name.isEmpty && name != "." && name != ".." && !name.contains("/") && !name.contains("\\") && !name.contains("\0") }
}
@MainActor enum WorldInventory {
    static func storage(api: PanelAPI, server: Server, root: String) async throws -> WorldStorage {
        var queue = [root], visited = Set<String>(), result = WorldStorage()
        while !queue.isEmpty {
            try Task.checkCancellation()
            guard visited.count < 200, result.files < 20_000 else { result.partial = true; break }
            let directory = queue.removeFirst()
            guard visited.insert(directory).inserted else { continue }
            let rows = try await api.list("servers/\(server.id)/files/list", query: ["directory": directory])
            result.include(rows, path: directory)
            for row in rows where !row["is_file"].bool && WorldStorage.safeName(row["name"].string) {
                // Do not follow provider-reported symlinks outside the selected world.
                if row["is_symlink"].bool || row["mimetype"].string.contains("symlink") { result.partial = true; continue }
                queue.append(pathJoin(directory, row["name"].string))
            }
        }
        return result
    }
}
