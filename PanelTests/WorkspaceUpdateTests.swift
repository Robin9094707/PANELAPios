import XCTest
import UIKit
@testable import Panel

final class WorkspaceUpdateTests: XCTestCase {
    @MainActor func isolatedDefaults() -> (UserDefaults, String) {
        let name = "workspace-update-tests-" + UUID().uuidString
        return (UserDefaults(suiteName: name)!, name)
    }
    @MainActor func testLayoutsPreserveDefaultsScopeAndLegacyData() throws {
        let (d, name) = isolatedDefaults(); defer { d.removePersistentDomain(forName: name) }
        let legacy = Data("saved-profile-and-connections".utf8)
        d.set(legacy, forKey: "profiles"); d.set(legacy, forKey: "adminSuite.connections.v1")
        d.set(["favorite"], forKey: "workspace.servers.v1")
        let layouts = DashboardLayouts(defaults: d)
        XCTAssertEqual(layouts.cards("home:A", scope: .home), [.welcome, .folders, .servers])
        XCTAssertEqual(layouts.cards("server:A", scope: .server), [.identity, .players, .resources, .cpuHistory, .network])
        layouts.set([.fleet, .servers, .fleet, .health], key: "home:A", scope: .home)
        layouts.set([.health, .resources], key: "server:A", scope: .server)
        let restored = DashboardLayouts(defaults: d)
        XCTAssertEqual(restored.cards("home:A", scope: .home), [.fleet, .servers])
        XCTAssertEqual(restored.cards("server:A", scope: .server), [.health, .resources])
        XCTAssertEqual(restored.cards("home:B", scope: .home), DashboardCard.defaults(.home))
        restored.reset("home:A")
        XCTAssertEqual(restored.cards("home:A", scope: .home), DashboardCard.defaults(.home))
        XCTAssertEqual(restored.cards("server:A", scope: .server), [.health, .resources])
        XCTAssertEqual(d.data(forKey: "profiles"), legacy)
        XCTAssertEqual(d.data(forKey: "adminSuite.connections.v1"), legacy)
        XCTAssertEqual(d.stringArray(forKey: "workspace.servers.v1"), ["favorite"])
        XCTAssertEqual(DashboardCard.normalize(["health", "invalid", "fleet", "fleet"], scope: .home), [.fleet, .servers])
    }
    @MainActor func testHistoryPersistsTwentyCommandsSeparatelyAndRestoresDraft() {
        let (d, name) = isolatedDefaults(); defer { d.removePersistentDomain(forName: name) }
        let history = ConsoleHistory(defaults: d)
        for n in 0..<25 { history.remember("say \(n)", key: "panelA|server1") }
        history.remember("say 24", key: "panelA|server1")
        history.remember("stop\nstart", key: "panelA|server1")
        history.remember("bad\0command", key: "panelA|server1")
        history.remember(String(repeating: "a", count: 4097), key: "panelA|server1")
        history.remember("list", key: "panelB|server1")
        let restored = ConsoleHistory(defaults: d)
        let entries = restored.entries("panelA|server1")
        XCTAssertEqual(entries.count, 20); XCTAssertEqual(entries.first, "say 24"); XCTAssertEqual(entries.last, "say 5")
        XCTAssertEqual(restored.entries("panelA|server2"), [])
        XCTAssertEqual(restored.entries("panelB|server1"), ["list"])
        var cursor = CommandHistoryCursor()
        XCTAssertEqual(cursor.older(current: "say draft", entries: entries), "say 24")
        XCTAssertEqual(cursor.older(current: "say 24", entries: entries), "say 23")
        XCTAssertEqual(cursor.newer(entries: entries), "say 24")
        XCTAssertEqual(cursor.newer(entries: entries), "say draft")
        cursor.edited("new draft")
        XCTAssertEqual(cursor.older(current: "new draft", entries: entries), "say 24")
        cursor.edited("edited previous command")
        XCTAssertNil(cursor.index)
        restored.clear("panelA|server1")
        XCTAssertEqual(restored.entries("panelB|server1"), ["list"])
    }
    func testPlatformUsesReportedOSBeforeModelAndDoesNotGuessLaptopOS() throws {
        func row(_ data: [String: Any]) throws -> JSON { try JSON.from(data) }
        XCTAssertEqual(PlayerPlatform.resolve(try row(["client": "JAVA", "os": "iOS"])), .java)
        XCTAssertEqual(PlayerPlatform.resolve(try row(["client": "BEDROCK", "os": "Windows", "device": "iPhone14,7"])), .windows)
        XCTAssertEqual(PlayerPlatform.resolve(try row(["client": "BEDROCK", "device": "iPhone19,2"])), .ios)
        XCTAssertEqual(PlayerPlatform.resolve(try row(["device": "SAMSUNG SM-E066B"])), .android)
        XCTAssertEqual(PlayerPlatform.resolve(try row(["device": "OPPO CPH2527"])), .android)
        XCTAssertEqual(PlayerPlatform.resolve(try row(["device": "xbox_series_x"])), .xbox)
        XCTAssertEqual(PlayerPlatform.resolve(try row(["device": "playstation_5"])), .playstation)
        XCTAssertEqual(PlayerPlatform.resolve(try row(["device": "Switch"])), .nintendoSwitch)
        XCTAssertEqual(PlayerPlatform.resolve(try row(["device": "HP OmniBook 7 Aero Laptop"])), .unknown)
        XCTAssertEqual(PlayerPlatform.resolve(try row(["device": "System Product Name System manufacturer (Unknown)"])), .unknown)
        XCTAssertEqual(PlayerPlatform.resolve(try row(["os": "Unknown", "platform": "BEDROCK"])), .unknown)
    }
    func testResourceThresholdsAndHealthNeverInventUnknownOrStaleStatus() {
        XCTAssertEqual(ResourceLoad.fraction(value: 150, limit: 200), 0.75)
        XCTAssertNil(ResourceLoad.fraction(value: 1, limit: 0))
        XCTAssertNil(ResourceLoad.fraction(value: .nan, limit: 100))
        XCTAssertEqual(ResourceLoad.level(0.699), .low)
        XCTAssertEqual(ResourceLoad.level(0.7), .moderate)
        XCTAssertEqual(ResourceLoad.level(0.9), .high)
        XCTAssertEqual(ResourceLoad.level(1.5), .high)
        XCTAssertEqual(ResourceLoad.level(nil), .unknown)
        let now = Date()
        XCTAssertEqual(ServerHealth.assess(state: "running", updated: now, fractions: [0.1, nil, 0.95], now: now).level, .high)
        XCTAssertEqual(ServerHealth.assess(state: "running", updated: now, fractions: [nil, nil, nil], now: now).level, .unknown)
        XCTAssertEqual(ServerHealth.assess(state: "running", updated: now.addingTimeInterval(-46), fractions: [0.1], now: now).level, .unknown)
        XCTAssertEqual(ServerHealth.assess(state: "offline", updated: now, fractions: [0], now: now).level, .unknown)
    }
    @MainActor func testServerIconAcceptsOnlyBoundedJavaPNG() throws {
        func png(_ size: CGFloat) -> Data {
            let format = UIGraphicsImageRendererFormat(); format.scale = 1
            return UIGraphicsImageRenderer(size: CGSize(width: size, height: size), format: format).pngData { context in
                UIColor.systemTeal.setFill(); context.fill(CGRect(x: 0, y: 0, width: size, height: size))
            }
        }
        XCTAssertTrue(ServerIconValidation.valid(png(64)))
        XCTAssertFalse(ServerIconValidation.valid(png(128)))
        XCTAssertFalse(ServerIconValidation.valid(Data("not a png".utf8)))
        XCTAssertFalse(ServerIconValidation.valid(Data(repeating: 0, count: 512 * 1024 + 1)))
    }
    @MainActor func testConsoleAnchorPreservesVisibleParagraphAfterBufferTrim() {
        let old = (0..<180).map { "line \($0) - output" }.joined(separator: "\n")
        let new = old.components(separatedBy: "\n").dropFirst(20).joined(separator: "\n") + "\nnew output"
        let format = UIFont.monospacedSystemFont(ofSize: 12, weight: .regular)
        let view = UITextView(frame: CGRect(x: 0, y: 0, width: 360, height: 220))
        view.textContainer.widthTracksTextView = true; view.font = format; view.text = old
        view.layoutManager.ensureLayout(for: view.textContainer); view.layoutIfNeeded()
        view.setContentOffset(CGPoint(x: 0, y: 850), animated: false)
        let state = ConsoleViewportState()
        let coordinator = ConsoleTextView.Coordinator(state); coordinator.previous = old
        coordinator.capture(view)
        XCTAssertFalse(state.atBottom)
        let context = state.anchor!.context
        let delta = state.anchor!.delta
        XCTAssertNotNil(ConsoleAnchor.range(of: context, in: new))
        view.text = new; view.font = format
        view.layoutManager.ensureLayout(for: view.textContainer); view.layoutIfNeeded()
        coordinator.restore(view, text: new)
        coordinator.previous = new; coordinator.capture(view)
        XCTAssertEqual(state.anchor?.context, context)
        XCTAssertEqual(state.anchor!.delta, delta, accuracy: 2)
        XCTAssertFalse(state.atBottom)
        let offset = state.offset
        coordinator.restore(view, text: new)
        XCTAssertEqual(view.contentOffset.y, offset.y, accuracy: 1)
    }
    @MainActor func testClearingConsoleAdvancesRevisionAndRetainsResourceHistory() throws {
        let live = LiveServer()
        live.receiveConsole("one\ntwo")
        let revision = live.logRevision
        live.history = [ResourcePoint(date: Date(), cpu: 20, memory: 1024)]
        live.clearConsole()
        XCTAssertTrue(live.lines.isEmpty); XCTAssertGreaterThan(live.logRevision, revision)
        XCTAssertEqual(live.history.count, 1)
    }
}
