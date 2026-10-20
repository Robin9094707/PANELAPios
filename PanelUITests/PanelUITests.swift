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
        XCTAssertTrue(app.buttons["showPlayers"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["Server-TPS"].waitForExistence(timeout: 10)); capture("02-Live-Dashboard")
        app.buttons["showPlayers"].tap()
        XCTAssertTrue(app.staticTexts["TadMC_YT"].waitForExistence(timeout: 10)); capture("02b-Spieler")
        app.buttons["Fertig"].tap()
        app.tabBars.buttons["Konsole"].tap()
        XCTAssertTrue(app.textFields["consoleCommand"].waitForExistence(timeout: 10))
        let console = app.textViews["consoleOutput"]
        XCTAssertTrue(console.waitForExistence(timeout: 10))
        XCTAssertGreaterThan(console.frame.width, app.frame.width * 0.9)
        XCTAssertGreaterThan(console.frame.height, 100)
        capture("03-Konsole")
        app.buttons["Shortcuts"].tap()
        XCTAssertTrue(app.buttons["Shortcut hinzufügen"].waitForExistence(timeout: 10)); capture("03b-Shortcuts")
        app.buttons["Fertig"].tap()
        app.tabBars.buttons["Dateien"].tap()
        let properties = app.buttons.containing(.staticText, identifier: "server.properties").firstMatch
        XCTAssertTrue(properties.waitForExistence(timeout: 10)); capture("04-Dateien")
        properties.tap()
        XCTAssertTrue(app.textViews["fileEditor"].waitForExistence(timeout: 10)); capture("05-Dateieditor")
        app.buttons["Editoroptionen"].tap()
        app.buttons["Werte als Formular"].tap()
        XCTAssertTrue(app.switches["online-mode"].waitForExistence(timeout: 10)); capture("05a-Eigenschaften-Formular")
        app.buttons["Abbrechen"].tap()
        app.buttons["Schließen"].tap()
        app.buttons.containing(.staticText, identifier: "world").firstMatch.tap()
        app.buttons.containing(.staticText, identifier: "playerdata").firstMatch.tap()
        app.buttons.containing(.staticText, identifier: "00000000-0000-0000-0000-000000000001.dat").firstMatch.tap()
        XCTAssertTrue(app.staticTexts["Gesundheit"].waitForExistence(timeout: 10)); capture("05b-NBT-Editor")
        app.buttons["Schließen"].tap()
        app.navigationBars.buttons.element(boundBy: 0).tap()
        app.navigationBars.buttons.element(boundBy: 0).tap()
        app.tabBars.buttons["Backups"].tap()
        XCTAssertTrue(app.staticTexts["Before update"].waitForExistence(timeout: 10)); capture("06-Backups")
        app.tabBars.buttons["Verwalten"].tap()
        XCTAssertTrue(app.buttons["Zeitpläne"].waitForExistence(timeout: 10)); capture("07-Verwalten")
        app.buttons["Plugins & JAR-Dateien"].tap()
        app.swipeUp()
        let pluginDetails = app.buttons["pluginDetails-ExamplePlugin.jar"]
        XCTAssertTrue(pluginDetails.waitForExistence(timeout: 10)); capture("11-Plugin-Version")
        pluginDetails.tap()
        XCTAssertTrue(app.staticTexts["Version auf dem Server"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["2.0.0"].firstMatch.waitForExistence(timeout: 10)); capture("12-Plugin-Details")
        app.buttons["Fertig"].tap()
        app.navigationBars.buttons.element(boundBy: 0).tap()
        app.buttons["Minecraft & Welt-Einstellungen"].tap()
        XCTAssertTrue(app.staticTexts["max-players"].waitForExistence(timeout: 10)); capture("09-Minecraft-Einstellungen")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        app.buttons["Datenbanken"].tap()
        XCTAssertTrue(app.staticTexts["db.example.net:3306"].waitForExistence(timeout: 10)); capture("10-Datenbanken")
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
