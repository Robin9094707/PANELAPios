import XCTest

final class PanelUITests: XCTestCase {
    var app: XCUIApplication!
    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication(); app.launchArguments = ["--demo", "-AppleLanguages", "(de)", "-AppleLocale", "de_DE"]; app.launch()
    }
    func capture(_ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot()); attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
    }
    func testDemoDashboardConsoleFilesBackupsAndManagement() throws {
        let server = app.buttons["server-simplex1"]
        XCTAssertTrue(server.waitForExistence(timeout: 15)); capture("01-Workspace")
        server.tap()
        XCTAssertTrue(app.staticTexts["Arbeitsspeicher"].waitForExistence(timeout: 10)); capture("02-Live-Dashboard")
        app.tabBars.buttons["Konsole"].tap()
        XCTAssertTrue(app.textFields["consoleCommand"].waitForExistence(timeout: 10)); capture("03-Konsole")
        app.tabBars.buttons["Dateien"].tap()
        let properties = app.buttons.containing(.staticText, identifier: "server.properties").firstMatch
        XCTAssertTrue(properties.waitForExistence(timeout: 10)); capture("04-Dateien")
        properties.tap()
        XCTAssertTrue(app.textViews["fileEditor"].waitForExistence(timeout: 10)); capture("05-Dateieditor")
        app.buttons["Schließen"].tap()
        app.tabBars.buttons["Backups"].tap()
        XCTAssertTrue(app.staticTexts["Before update"].waitForExistence(timeout: 10)); capture("06-Backups")
        app.tabBars.buttons["Verwalten"].tap()
        XCTAssertTrue(app.buttons["Zeitpläne"].waitForExistence(timeout: 10)); capture("07-Verwalten")
    }
    func testConnectionFormRequiresURLAndClientKey() throws {
        app.buttons["Eigenes Panel verbinden"].tap()
        XCTAssertTrue(app.textFields["panelURL"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.secureTextFields["apiKey"].exists)
        XCTAssertFalse(app.buttons["Verbinden"].isEnabled)
        capture("08-Panel-verbinden")
    }
}
