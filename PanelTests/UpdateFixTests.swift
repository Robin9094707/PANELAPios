import XCTest
import UIKit
@testable import Panel

final class UpdateFixTests: XCTestCase {
    @MainActor func testServerRightsAreAccountScopedAndRouteSpecific() throws {
        let access = ServerAccess()
        access.ingest(try JSON.parse(#"{"meta":{"is_server_owner":false,"user_permissions":["websocket.connect","file.read","file.read-content","control.start"]}}"#), server: "shared")
        XCTAssertTrue(access.allows("shared", "file.read"))
        XCTAssertFalse(access.allows("shared", "file.delete"))
        XCTAssertFalse(access.allows(path: "servers/shared/command", method: "POST"))
        XCTAssertTrue(access.allows(path: "servers/shared/power", method: "POST", body: .object(["signal": .string("start")])))
        XCTAssertFalse(access.allows(path: "servers/shared/power", method: "POST", body: .object(["signal": .string("kill")])))
        XCTAssertFalse(access.allows(path: "servers/shared/schedules/1/tasks/2", method: "DELETE"))
        XCTAssertEqual(ServerAccess.requirement("servers/shared/schedules/1/tasks/2", method: "DELETE")?.permission, "schedule.update")
        XCTAssertNil(ServerAccess.requirement("servers/shared/settings/start-command", method: "PUT"))
        access.ingest(try JSON.parse(#"{"meta":{"is_server_owner":true}}"#), server: "owned")
        XCTAssertTrue(access.allows("owned", "database.delete"))
        access.recordDenial(path: "servers/shared/instances/1/swap", method: "POST")
        XCTAssertFalse(access.allows(path: "servers/shared/instances/1/swap", method: "POST"))
        XCTAssertTrue(access.allows(path: "servers/shared/instances/1/rename", method: "PATCH"))
        access.recordDenial(path: "servers/shared", method: "GET")
        access.clearDenials(server: "shared")
        XCTAssertTrue(access.allows(path: "servers/shared"))
        XCTAssertTrue(access.allows(path: "servers/shared/instances/1/swap", method: "POST"))
        XCTAssertTrue(ServerAccess().allows("shared", "file.delete")) // No rights leak into a new account.
    }
    @MainActor func testTPSArrivesThroughLiveConsoleWithTerminalAndMinecraftColors() throws {
        let live = LiveServer()
        live.receiveConsole(">....\u{1B}=>....[05:17:26 INFO]: \u{1B}[32mTPS from last 1m, 5m, 15m: §a*20.0§r, §e19.2§r, §c12.0§r\u{1B}[0m")
        XCTAssertEqual(live.tps?.minute, 20); XCTAssertEqual(live.tps?.five, 19.2); XCTAssertEqual(live.tps?.fifteen, 12)
        XCTAssertNil(live.tpsIssue)
        XCTAssertTrue(live.lines.first?.contains("\u{1B}[32m") == true)
        let newer = try XCTUnwrap(TPSSnapshot.parse("TPS from last 5s, 10s, 1m, 5m, 15m: 20.0, 19.9, *20.0, 18.0, 15.0"))
        XCTAssertEqual(newer.minute, 20); XCTAssertEqual(newer.five, 18); XCTAssertEqual(newer.fifteen, 15)
    }
    func testConsoleStylesPreserveCopyableTextAndRGBFormatting() throws {
        let input = "\u{1B}[31mRed\u{1B}[0m plain §lBold§r §x§1§2§3§4§5§6Hex§r \u{1B}[38;2;12;34;56mRGB\u{1B}[0m"
        let output = ConsoleStyle.attributed(input, size: 13)
        XCTAssertEqual(output.string, "Red plain Bold Hex RGB")
        XCTAssertEqual(cleanConsole(input), output.string)
        let red = try XCTUnwrap(output.attribute(.foregroundColor, at: 0, effectiveRange: nil) as? UIColor)
        XCTAssertTrue(red.isEqual(ConsoleStyle.rgb(0xAA0000)))
        let hex = (output.string as NSString).range(of: "Hex").location
        XCTAssertTrue((output.attribute(.foregroundColor, at: hex, effectiveRange: nil) as? UIColor)?.isEqual(ConsoleStyle.rgb(0x123456)) == true)
        XCTAssertEqual(cleanConsole("\u{1B}]0;Title\u{7}Visible\u{1B}[K"), "Visible")
    }
    func testPropertiesAndQuotedYAMLFormsPreserveUntouchedStructure() throws {
        let text = "# keep\r\nonline-mode=true\r\ndifficulty = hard\r\nmotd=Hello\\nWorld\\u0021\r\npath=C:\\\\minecraft\r\nlong=first\\\n second\n"
        let fields = ConfigurationField.parse(text, properties: true)
        XCTAssertEqual(fields.map(\.key), ["online-mode", "difficulty", "motd", "path"])
        XCTAssertEqual(try fields[0].replacement("false"), "online-mode=false\r")
        XCTAssertEqual(fields[1].options, ["peaceful", "easy", "normal", "hard"])
        XCTAssertEqual(try fields[1].replacement("easy"), "difficulty = easy\r")
        XCTAssertEqual(fields[2].value, "Hello\nWorld!")
        XCTAssertEqual(try fields[2].replacement("Hello\nWorld!"), "motd=Hello\\nWorld!\r")
        let yaml = "settings:\n  title: 'It''s # a title' # keep\n  list: [a, b]\n  enabled: false\n  literal: |\n    text\n"
        let rows = ConfigurationField.parse(yaml, properties: false)
        XCTAssertEqual(rows.map(\.key), ["title", "enabled"])
        XCTAssertEqual(rows[0].value, "It's # a title")
        XCTAssertEqual(rows[0].path, "settings › title")
        XCTAssertEqual(try rows[0].replacement("New # title"), "  title: \"New # title\" # keep")
        XCTAssertEqual(try rows[1].replacement("true"), "  enabled: true")
        let ambiguous = "flag: yes\nhex: 0xFF\noctal: 012\ndate: 2026-10-20\ntime: 12:34\nseparated: 1_000\nquoted: 'yes'\nboolean: true\ndecimal: 12.5\n"
        XCTAssertEqual(ConfigurationField.parse(ambiguous, properties: false).map(\.key), ["quoted", "boolean", "decimal"])
    }
    @MainActor func testOldLiveRunCannotDisconnectNewRun() async throws {
        let api = PanelAPI(base: URL(string: "https://demo.invalid")!, key: "", demo: true)
        let live = LiveServer(), server = Server(data: Demo.servers[0])
        let first = Task { await live.run(api: api, server: server, minecraft: true) }
        try await Task.sleep(for: .milliseconds(30))
        let second = Task { await live.run(api: api, server: server, minecraft: true) }
        try await Task.sleep(for: .milliseconds(30))
        first.cancel(); await first.value
        XCTAssertTrue(live.connected)
        second.cancel(); await second.value
        XCTAssertFalse(live.connected)
    }
}
