import XCTest
@testable import Panel

final class PlayerNBTSearchTests: XCTestCase {
    func testExactBedrockUUIDFindsModernDataWithoutSelectingBackupOrAlias() async throws {
        let player = "00000000-0000-0000-0009-00000a1388a7"
        var probes: [String] = []
        let rows = try await PlayerNBTSearch.paths(player: player) { folder in
            probes.append(folder)
            if folder == "/world/players/data" {
                return [.object(["name": .string(player + ".dat_old"), "is_file": .bool(true)]),
                        .object(["name": .string(".Smoke20111001"), "is_file": .bool(true)]),
                        .object(["name": .string(player + ".dat"), "is_file": .bool(true)])]
            }
            return []
        }
        XCTAssertEqual(probes, ["/world/players/data", "/world/playerdata"])
        XCTAssertEqual(rows.map { $0["id"].string }, ["/world/players/data/" + player + ".dat"])
        XCTAssertTrue(rows[0]["panelOnly"].bool)
    }
    func testMissingModernDirectoryFallsBackToLegacyAndUUIDIsValidated() async throws {
        let player = "00000000-0000-0000-0000-000000000001"
        let rows = try await PlayerNBTSearch.paths(player: player) { folder in
            if folder.contains("players/data") { throw PanelError(message: "Not found") }
            return [.object(["name": .string(player + ".dat"), "is_file": .bool(true)])]
        }
        XCTAssertEqual(rows.count, 1)
        do { _ = try await PlayerNBTSearch.paths(player: "../other") { _ in XCTFail("Invalid UUID queried"); return [] }; XCTFail("Invalid UUID accepted") }
        catch { XCTAssertTrue(error.localizedDescription.contains("UUID")) }
    }
}
