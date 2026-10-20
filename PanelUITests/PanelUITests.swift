import XCTest

final class PanelUITests: XCTestCase {
    var app: XCUIApplication!
    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication(); app.launchArguments = ["--demo", "--demo-paused-download", "--demo-suite", "-AppleLanguages", "(de)", "-AppleLocale", "de_DE"]; app.launch()
    }
    func capture(_ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot()); attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
    }
    func testDemoDashboardConsoleFilesBackupsAndManagement() throws {
        executionTimeAllowance = 300
        let server = app.buttons["server-simplex1"]
        XCTAssertTrue(server.waitForExistence(timeout: 15)); capture("01-Workspace")
        server.tap()
        XCTAssertTrue(app.buttons["showPlayers"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["Server-TPS"].waitForExistence(timeout: 10)); capture("02-Live-Dashboard")
        app.tabBars.buttons["Konsole"].tap()
        XCTAssertTrue(app.textFields["consoleCommand"].waitForExistence(timeout: 10))
        let console = app.textViews["consoleOutput"]
        XCTAssertTrue(console.waitForExistence(timeout: 10))
        XCTAssertGreaterThan(console.frame.width, app.frame.width * 0.9)
        XCTAssertGreaterThan(console.frame.height, 100); capture("03-Konsole")
        app.tabBars.buttons["Dateien"].tap()
        let properties = app.buttons.containing(.staticText, identifier: "server.properties").firstMatch
        XCTAssertTrue(properties.waitForExistence(timeout: 10)); capture("04-Dateien")
        properties.tap()
        XCTAssertTrue(app.textViews["fileEditor"].waitForExistence(timeout: 10)); capture("05-Dateieditor")
        app.buttons["Schließen"].tap()
        app.tabBars.buttons["Backups"].tap()
        XCTAssertTrue(app.staticTexts["Before update"].waitForExistence(timeout: 10)); capture("06-Backups")
        app.tabBars.buttons["Verwalten"].tap()
        XCTAssertTrue(app.buttons["adminSuiteHub"].waitForExistence(timeout: 10)); capture("07-Verwalten")
        app.buttons["adminSuiteHub"].tap()
        XCTAssertTrue(app.staticTexts["Simplex Admin Suite"].waitForExistence(timeout: 10)); capture("18-Admin-Suite")
        app.buttons["Spieler, Geräte & Alt-Accounts"].tap()
        XCTAssertTrue(app.staticTexts["Gefundene Profile"].waitForExistence(timeout: 10))
        app.buttons.containing(.staticText, identifier: "TadMC_YT").firstMatch.tap()
        for _ in 0..<6 { if app.staticTexts["Live-Spielerwerte"].isHittable { break }; app.swipeUp() }
        XCTAssertTrue(app.staticTexts["Live-Spielerwerte"].waitForExistence(timeout: 10)); capture("19-Suite-Spielerakte")
        for _ in 0..<6 { if app.buttons["Spielerakte & gespeicherte Daten"].isHittable { break }; app.swipeUp() }
        app.buttons["Spielerakte & gespeicherte Daten"].tap()
        for _ in 0..<4 { if app.buttons["Alt-Accounts & Belege"].isHittable { break }; app.swipeUp() }
        app.buttons["Alt-Accounts & Belege"].tap()
        XCTAssertTrue(app.staticTexts[".BedrockDemo"].waitForExistence(timeout: 10)); capture("20-Suite-Alt-Accounts")
    }
    func testIntegratedSuitePlayersSearchDevicesAndOffline() throws {
        let online = app.buttons["workspace-online-simplex1"]
        XCTAssertTrue(online.waitForExistence(timeout: 15)); online.tap()
        XCTAssertTrue(app.staticTexts["iPhone · Demo"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.staticTexts["RecentOffline"].exists)
        app.segmentedControls.buttons["Zuletzt online"].tap()
        XCTAssertTrue(app.staticTexts["RecentOffline"].waitForExistence(timeout: 10))
        app.buttons.containing(.staticText, identifier: "RecentOffline").firstMatch.tap()
        XCTAssertTrue(app.staticTexts["Letztes Gerät"].waitForExistence(timeout: 10))
        let deviceRow = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@ AND label CONTAINS %@", "Letztes Gerät", "Samsung · Demo")).firstMatch
        XCTAssertTrue(deviceRow.waitForExistence(timeout: 10))
        capture("21-Offline-Spieler")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        app.segmentedControls.buttons["Alle"].tap()
        app.swipeDown()
        let search = app.searchFields.firstMatch
        XCTAssertTrue(search.waitForExistence(timeout: 10)); search.tap(); search.typeText("TadMC")
        let row = app.buttons.containing(.staticText, identifier: "TadMC_YT").firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 10)); row.tap()
        for _ in 0..<4 { if app.buttons["player-ultraban"].isHittable { break }; app.swipeUp() }
        XCTAssertTrue(app.buttons["player-ultraban"].exists)
        app.buttons["Alle Geräte anzeigen"].tap()
        XCTAssertTrue(app.staticTexts["iPhone · Demo"].waitForExistence(timeout: 10))
        capture("22-Spielergeraete")
    }
    func testDashboardCustomizationIsOptionalPersistentAndResetIsConfirmed() throws {
        executionTimeAllowance = 300
        let edit = app.buttons["editHomeLayout"]
        XCTAssertTrue(edit.waitForExistence(timeout: 15)); edit.tap()
        let addFleet = app.buttons["add-card-fleet"]
        if !addFleet.exists {
            for _ in 0..<4 { if app.buttons["reset-dashboard"].isHittable { break }; app.swipeUp() }
            app.buttons["reset-dashboard"].tap()
            app.buttons["Layout zurücksetzen"].tap()
        }
        for _ in 0..<4 { if app.buttons["add-card-fleet"].isHittable { break }; app.swipeUp() }
        app.buttons["add-card-fleet"].tap()
        app.buttons["finish-dashboard"].tap()
        app.terminate(); app.launch()
        app.buttons["editHomeLayout"].tap()
        XCTAssertTrue(app.buttons["hide-card-fleet"].waitForExistence(timeout: 10))
        for _ in 0..<5 { if app.buttons["reset-dashboard"].isHittable { break }; app.swipeUp() }
        app.buttons["reset-dashboard"].tap()
        XCTAssertTrue(app.buttons["Layout zurücksetzen"].waitForExistence(timeout: 5))
        app.buttons["Abbrechen"].tap()
        for _ in 0..<4 { if app.buttons["hide-card-fleet"].isHittable { break }; app.swipeDown() }
        XCTAssertTrue(app.buttons["hide-card-fleet"].exists)
        for _ in 0..<5 { if app.buttons["reset-dashboard"].isHittable { break }; app.swipeUp() }
        app.buttons["reset-dashboard"].tap()
        app.buttons["Layout zurücksetzen"].tap()
        app.buttons["finish-dashboard"].tap()
        let server = app.buttons["server-simplex1"]
        if !server.isHittable { app.swipeDown() }
        XCTAssertTrue(server.waitForExistence(timeout: 10)); server.tap()
        app.buttons["editServerLayout"].tap()
        for _ in 0..<4 { if app.buttons["add-card-health"].isHittable { break }; app.swipeUp() }
        app.buttons["add-card-health"].tap()
        app.buttons["finish-dashboard"].tap()
        for _ in 0..<9 { if app.staticTexts["Server-Gesundheit"].isHittable { break }; app.swipeUp() }
        XCTAssertTrue(app.staticTexts["Server-Gesundheit"].waitForExistence(timeout: 10)); capture("23-Health-Card")
        app.buttons["editServerLayout"].tap()
        for _ in 0..<6 { if app.buttons["reset-dashboard"].isHittable { break }; app.swipeUp() }
        app.buttons["reset-dashboard"].tap(); app.buttons["Layout zurücksetzen"].tap()
        app.buttons["finish-dashboard"].tap()
        app.tabBars.buttons["Konsole"].tap()
        XCTAssertTrue(app.buttons["consoleHistory"].waitForExistence(timeout: 10)); app.buttons["consoleHistory"].tap()
        XCTAssertTrue(app.navigationBars["Befehlsverlauf"].waitForExistence(timeout: 5)); capture("24-Console-History")
        app.buttons["Fertig"].tap()
    }
    func testConnectionFormRequiresURLAndClientKey() throws {
        app.swipeUp()
        let connect = app.buttons["Eigenes Panel verbinden"]
        XCTAssertTrue(connect.waitForExistence(timeout: 10))
        connect.tap()
        XCTAssertTrue(app.textFields["panelURL"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.secureTextFields["apiKey"].exists)
        XCTAssertFalse(app.buttons["Verbinden"].isEnabled)
        capture("08-Panel-verbinden")
    }
}

