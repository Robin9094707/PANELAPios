import XCTest
@testable import Panel

final class TransferScheduleTests: XCTestCase {
    func testTransferManifestRoundTripKeepsProgressAndUnknownTotals() throws {
        var record = TransferRecord(filename: "AirTags-3.0.0.jar", context: "Minecraft · plugins/update", upload: true)
        record.completed = 512; record.total = 1024
        let decoded = try JSONDecoder().decode(TransferRecord.self, from: JSONEncoder().encode(record))
        XCTAssertEqual(decoded.fraction, 0.5); XCTAssertTrue(decoded.active)
        record.total = -1; XCTAssertNil(record.fraction)
        record.state = "interrupted"; XCTAssertFalse(record.active)
        record.total = 100; record.completed = 1000; XCTAssertEqual(record.fraction, 1)
        XCTAssertEqual(decoded.filename, "AirTags-3.0.0.jar")
    }
    func testCronValidationRejectsInvalidRangesStepsAndEmptyComponents() {
        XCTAssertTrue(ScheduleTime.valid("*/15", range: 0...59))
        XCTAssertTrue(ScheduleTime.valid("1-5,7", range: 0...7))
        for value in ["60", "*/0", "1,", "5-1", "-1", "*/61", "", "1/2/3"] { XCTAssertFalse(ScheduleTime.valid(value, range: 0...59), value) }
    }
    func testBerlinDisplayAndPanelConversionRespectDST() throws {
        let winter = try XCTUnwrap(ScheduleTime.parse("2026-01-15T12:00:00+00:00"))
        let summer = try XCTUnwrap(ScheduleTime.parse("2026-07-15T12:00:00.123Z"))
        XCTAssertEqual(ScheduleTime.berlin.secondsFromGMT(for: winter), 3600)
        XCTAssertEqual(ScheduleTime.berlin.secondsFromGMT(for: summer), 7200)
        XCTAssertTrue(ScheduleTime.display(winter).contains("13:00"))
        XCTAssertTrue(ScheduleTime.display(summer).contains("14:00"))
        let next = try XCTUnwrap(ScheduleTime.convertedTime(hour: 4, minute: 15, panelZone: .gmt, on: winter))
        var calendar = Calendar(identifier: .gregorian); calendar.timeZone = ScheduleTime.berlin
        XCTAssertEqual(calendar.component(.hour, from: next), 5); XCTAssertEqual(calendar.component(.minute, from: next), 15)
        XCTAssertNil(ScheduleTime.parse("not a timestamp"))
    }
    @MainActor func testPanelHTTPClockDoesNotInventMissingServerTime() throws {
        let clock = PanelClock()
        clock.observe(URLResponse(url: URL(string: "https://example.com")!, mimeType: nil, expectedContentLength: 0, textEncodingName: nil))
        XCTAssertNil(clock.synced)
        let formatter = DateFormatter(); formatter.locale = Locale(identifier: "en_US_POSIX"); formatter.timeZone = .gmt; formatter.dateFormat = "EEE, dd MMM yyyy HH:mm:ss zzz"
        let response = HTTPURLResponse(url: URL(string: "https://example.com")!, statusCode: 200, httpVersion: nil, headerFields: ["Date": formatter.string(from: Date().addingTimeInterval(60))])!
        clock.observe(response); XCTAssertNotNil(clock.synced); XCTAssertEqual(clock.offset, 60, accuracy: 2)
    }
    func testWidgetSnapshotKeepsSeparateFreshnessWithoutSecrets() throws {
        var record = WidgetServer(panel: "https://kineticpanel.net", serverID: "abc", name: "Minecraft")
        record.online = 2; record.maximum = 60; record.playersUpdated = Date(timeIntervalSince1970: 100); record.updated = Date(timeIntervalSince1970: 200)
        let data = try JSONEncoder().encode(record)
        let decoded = try JSONDecoder().decode(WidgetServer.self, from: data)
        XCTAssertEqual(decoded.online, 2); XCTAssertEqual(decoded.maximum, 60)
        XCTAssertNotEqual(decoded.updated, decoded.playersUpdated)
        XCTAssertFalse(String(decoding: data, as: UTF8.self).contains("ptlc_"))
        record.state = "starting"; XCTAssertEqual(record.stateTitle, "Startet")
    }
}
