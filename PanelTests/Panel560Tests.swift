import XCTest
@testable import Panel

/// 5.6.0: small checks for the new logic only (timeline filters, relative times, briefing, sessions, launch links).
@MainActor final class Panel560Tests: XCTestCase {
    private let berlin = Calendar.berlin

    private func date(_ text: String) -> Date {
        let format = DateFormatter(); format.locale = Locale(identifier: "de_DE"); format.timeZone = berlin.timeZone; format.dateFormat = "dd.MM.yyyy HH:mm"
        return format.date(from: text)!
    }
    private func row(_ category: String, at: Date?, text: String = "x", flagged: Bool = false, caseID: String = "") -> JSON {
        .object(["id": .string(UUID().uuidString), "category": .string(category), "at": .number((at?.timeIntervalSince1970 ?? 0) * 1000),
                 "text": .string(text), "flagged": .bool(flagged), "case": .string(caseID), "kind": .string(category)])
    }

    func testRelativeTimesAreShortAndGerman() {
        let now = date("09.11.2026 12:00")
        XCTAssertEqual(SuiteRelativeTime.text(now.addingTimeInterval(-20), now: now), "gerade eben")
        XCTAssertEqual(SuiteRelativeTime.text(now.addingTimeInterval(-5 * 60), now: now), "vor 5 Min.")
        XCTAssertEqual(SuiteRelativeTime.text(now.addingTimeInterval(-3 * 3600), now: now), "vor 3 Std.")
        XCTAssertEqual(SuiteRelativeTime.text(date("08.11.2026 20:15"), now: now), "gestern, 20:15")
        XCTAssertEqual(SuiteRelativeTime.text(date("05.11.2026 10:00"), now: now), "vor 4 Tagen")
        XCTAssertEqual(SuiteRelativeTime.day(now, now: now), "Heute")
        XCTAssertEqual(SuiteRelativeTime.day(date("08.11.2026 09:00"), now: now), "Gestern")
    }

    func testTimelineFiltersRangesSearchAndDays() {
        let now = date("09.11.2026 12:00")
        let rows = [row("chat", at: now.addingTimeInterval(-60), text: "Hallo Welt"),
                    row("item", at: now.addingTimeInterval(-1800), text: "Abgelegt: 3× diamond"),
                    row("chat", at: now.addingTimeInterval(-7200), text: "böse", flagged: true, caseID: "c1"),
                    row("combat", at: date("08.11.2026 18:00")),
                    row("action", at: nil, text: "Legacy")]
        XCTAssertEqual(PlayerTimelineModel.filter(rows, filter: .all, range: .all, query: "", now: now).count, 5)
        XCTAssertEqual(PlayerTimelineModel.filter(rows, filter: .chat, range: .all, query: "", now: now).count, 2)
        XCTAssertEqual(PlayerTimelineModel.filter(rows, filter: .flagged, range: .all, query: "", now: now).count, 1)
        XCTAssertEqual(PlayerTimelineModel.filter(rows, filter: .powerbot, range: .all, query: "", now: now).count, 1)
        XCTAssertEqual(PlayerTimelineModel.filter(rows, filter: .all, range: .hour, query: "", now: now).count, 2)
        XCTAssertEqual(PlayerTimelineModel.filter(rows, filter: .all, range: .all, query: "diamond", now: now).count, 1)
        let days = PlayerTimelineModel.days(rows, now: now)
        XCTAssertEqual(days.map(\.0), ["Heute", "Gestern", "Ohne Datum"])
        XCTAssertEqual(days.first?.1.count, 3)
        XCTAssertEqual(PlayerTimelineModel.counts(rows)[.item], 1)
        let legacy = PlayerTimelineModel.legacy([.object(["id": .number(1), "kind": .string("whisper"), "from": .string("A"), "to": .string("B"), "text": .string("hi"), "at": .number(1)])])
        XCTAssertEqual(legacy.first?["category"].string, "whisper")
    }

    func testBriefingOnlyAfterAbsenceAndOnlyWithNews() {
        XCTAssertFalse(SuiteBriefingModel.shouldShow(lastVisit: 0, now: 100_000, thresholdHours: 3))
        XCTAssertFalse(SuiteBriefingModel.shouldShow(lastVisit: 100_000 - 3600, now: 100_000, thresholdHours: 3))
        XCTAssertTrue(SuiteBriefingModel.shouldShow(lastVisit: 100_000 - 4 * 3600, now: 100_000, thresholdHours: 3))
        let all: Set<String> = ["players", "powerbot", "events"]
        let quiet: JSON = .object(["players": .object(["new": .number(0), "active": .number(4)]), "powerbot": .object(["blocked": .number(0)])])
        XCTAssertFalse(SuiteBriefingModel.hasNews(quiet, sections: all))
        XCTAssertEqual(SuiteBriefingModel.headline(quiet, sections: all), "Alles ruhig – keine besonderen Vorkommnisse.")
        let busy: JSON = .object(["players": .object(["new": .number(2), "active": .number(9)]), "powerbot": .object(["blocked": .number(3), "conflicts": .number(1)]),
                                  "events": .object(["groups": .object(["tickets": .number(1)])])])
        XCTAssertTrue(SuiteBriefingModel.hasNews(busy, sections: all))
        XCTAssertEqual(SuiteBriefingModel.headline(busy, sections: all), "2 Neue Spieler · 3 Blockiert · 1 Streits")
        XCTAssertFalse(SuiteBriefingModel.tiles(busy, sections: ["players"]).contains { $0.tool == .powerBot })
    }

    func testBriefingMigratesTheOldDigestVisit() {
        let name = "Panel560-" + UUID().uuidString, defaults = UserDefaults(suiteName: name)!
        defer { defaults.removePersistentDomain(forName: name) }
        let id = UUID()
        defaults.set(1234.0, forKey: "powerbot.lastActive." + id.uuidString)
        defaults.set(false, forKey: "powerbot.digest.enabled")
        let store = SuiteBriefingStore(defaults: defaults)
        XCTAssertEqual(store.lastVisit(id), 1234)
        XCTAssertFalse(store.enabled)
        store.markLeft([id], now: 5000)
        XCTAssertEqual(store.lastVisit(id), 5000)
    }

    func testSessionListDropsDuplicatesAndRemovesSignedOutDevice() {
        let rows: [JSON] = [.object(["id": .string("a")]), .object(["id": .string("a")]), .object(["id": .string("")]), .object(["id": .string("b")])]
        XCTAssertEqual(SuiteSessionList.unique(rows).map { $0["id"].string }, ["a", "b"])
        let data: JSON = .object(["items": .array([.object(["id": .string("a")]), .object(["id": .string("b")])]), "manage": .bool(true)])
        let next = SuiteSessionList.removing("a", from: data)
        XCTAssertEqual(next["items"].array.map { $0["id"].string }, ["b"])
        XCTAssertTrue(next["manage"].bool)
    }

    func testNewToolsLaunchAndReleaseNotes() {
        for tool in [SuiteTool.powerBot, .briefing] {
            let request = SuiteLaunchRequest(tool: tool, connection: UUID())
            XCTAssertEqual(SuiteLaunchRequest.parse(request.url)?.tool, tool)
            XCTAssertNotNil(SuiteShortcutTool(rawValue: tool.rawValue))
        }
        XCTAssertEqual(SuiteTool.powerBot.group, SuiteTool.reports.group)
        XCTAssertGreaterThanOrEqual(WhatsNew.releases.first { $0.version == "5.6.0" }?.items.count ?? 0, 8)
        XCTAssertTrue(WhatsNew.shouldPresent(current: "5.6.0", lastSeen: "5.5.0", existingUser: true))
        XCTAssertEqual(PowerBotPresentation.title("mute"), "Chatpause")
        XCTAssertEqual(SuitePowerBotView.kinds["conflict"], "Streit erkannt")
    }
}
