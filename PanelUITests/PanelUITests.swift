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
        XCTAssertTrue(app.staticTexts["Live-Spielerwerte"].waitForExistence(timeout: 10)); capture("19-Suite-Spielerakte")
        app.swipeUp()
        app.buttons["Alt-Accounts & Belege"].tap()
        XCTAssertTrue(app.staticTexts[".BedrockDemo"].waitForExistence(timeout: 10)); capture("20-Suite-Alt-Accounts")
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
