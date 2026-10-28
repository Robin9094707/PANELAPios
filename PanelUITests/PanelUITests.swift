import XCTest

final class PanelUITests: XCTestCase {
    var app: XCUIApplication!
    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication(); app.launchArguments = ["--demo", "--demo-paused-download", "--demo-suite", "-AppleLanguages", "(de)", "-AppleLocale", "de_DE"]; app.launch()
    }
    func selectTab(_ title: String) {
        // More destinations use the same navigation stack as the rest of the server.
        // Return to the server workspace before switching tabs from a pushed page.
        for _ in 0..<4 {
            if app.tabBars.firstMatch.exists { break }
            let back = app.navigationBars.buttons.firstMatch
            guard back.exists else { break }; back.tap()
        }
        let direct = app.tabBars.buttons[title]
        if direct.exists { direct.tap(); return }
        let more = app.tabBars.buttons.matching(NSPredicate(format: "label IN %@", ["More", "Mehr"])).firstMatch
        XCTAssertTrue(more.waitForExistence(timeout: 10)); more.tap()
        let item = app.buttons[title].firstMatch
        XCTAssertTrue(item.waitForExistence(timeout: 10)); item.tap()
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
        selectTab("Konsole")
        XCTAssertTrue(app.textFields["consoleCommand"].waitForExistence(timeout: 10))
        let console = app.textViews["consoleOutput"]
        XCTAssertTrue(console.waitForExistence(timeout: 10))
        XCTAssertGreaterThan(console.frame.width, app.frame.width * 0.9)
        XCTAssertGreaterThan(console.frame.height, 100); capture("03-Konsole")
        selectTab("Dateien")
        let properties = app.buttons.containing(.staticText, identifier: "server.properties").firstMatch
        XCTAssertTrue(properties.waitForExistence(timeout: 10)); capture("04-Dateien")
        properties.tap()
        XCTAssertTrue(app.textViews["fileEditor"].waitForExistence(timeout: 10)); capture("05-Dateieditor")
        app.buttons["Schließen"].tap()
        selectTab("Backups")
        XCTAssertTrue(app.staticTexts["Before update"].waitForExistence(timeout: 10)); capture("06-Backups")
        selectTab("Verwalten")
        XCTAssertTrue(app.buttons["adminSuiteHub"].waitForExistence(timeout: 10)); capture("07-Verwalten")
        app.buttons["adminSuiteHub"].tap()
        XCTAssertTrue(app.staticTexts["Simplex Admin Suite"].waitForExistence(timeout: 10)); capture("18-Admin-Suite")
        app.buttons["Spieler, Geräte & Alt-Accounts"].tap()
        XCTAssertTrue(app.staticTexts["Gefundene Profile"].waitForExistence(timeout: 10))
        app.buttons.containing(.staticText, identifier: "TadMC_YT").firstMatch.tap()
        for _ in 0..<6 { if app.staticTexts["Live-Spielerwerte"].isHittable { break }; app.swipeUp() }
        XCTAssertTrue(app.staticTexts["Live-Spielerwerte"].waitForExistence(timeout: 10)); capture("19-Suite-Spielerakte")
        for _ in 0..<6 { if app.buttons["Alt-Accounts & deren Profile"].isHittable { break }; app.swipeDown() }
        app.buttons["Alt-Accounts & deren Profile"].tap()
        XCTAssertTrue(app.staticTexts[".BedrockDemo"].waitForExistence(timeout: 10)); capture("20-Suite-Alt-Accounts")
    }
    func testPlayerProfile20AndTargetedSearch() throws {
        let online = app.buttons["workspace-online-simplex1"]
        XCTAssertTrue(app.descendants(matching: .any)["home-online-count-simplex1"].firstMatch.waitForExistence(timeout: 15))
        XCTAssertTrue(online.waitForExistence(timeout: 15)); online.tap()
        XCTAssertTrue(app.staticTexts["iPhone · Demo"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.staticTexts["RecentOffline"].exists)
        app.segmentedControls.buttons["Zuletzt online"].tap()
        XCTAssertTrue(app.staticTexts["RecentOffline"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.staticTexts["TadMC_YT"].exists)
        app.segmentedControls.buttons["Alle"].tap()
        app.swipeDown()
        let search = app.searchFields.firstMatch
        XCTAssertTrue(search.waitForExistence(timeout: 10)); search.tap(); search.typeText("TadMC")
        let profile = app.buttons.containing(.staticText, identifier: "TadMC_YT").firstMatch
        XCTAssertTrue(profile.waitForExistence(timeout: 10)); profile.tap()
        for _ in 0..<5 { if app.buttons["NBT-Editor öffnen"].isHittable { break }; app.swipeUp() }
        XCTAssertEqual(app.buttons.matching(identifier: "NBT-Editor öffnen").count, 1)
        XCTAssertTrue(app.buttons["Aktuelles Inventar & Endertruhe"].exists)
        for _ in 0..<4 { if app.staticTexts["Zuletzt ausgeloggt"].isHittable { break }; app.swipeUp() }
        XCTAssertTrue(app.staticTexts["Zuletzt ausgeloggt"].exists)
        for _ in 0..<4 { if app.switches["profile-vpn-whitelist"].isHittable { break }; app.swipeUp() }
        XCTAssertTrue(app.switches["profile-vpn-whitelist"].exists); capture("31-Player-Profil-2")
    }
    func testAltDashboardAndIgnoredInfrastructureIP() throws {
        let server = app.buttons["server-simplex1"]
        XCTAssertTrue(server.waitForExistence(timeout: 15)); server.tap()
        XCTAssertTrue(app.buttons["showPlayers"].waitForExistence(timeout: 10))
        selectTab("Verwalten")
        XCTAssertTrue(app.buttons["adminSuiteHub"].waitForExistence(timeout: 10)); app.buttons["adminSuiteHub"].tap()
        let dashboard = app.buttons["Alt-Account-Dashboard & IP-Regeln"]
        for _ in 0..<5 { if dashboard.isHittable { break }; app.swipeUp() }
        XCTAssertTrue(dashboard.waitForExistence(timeout: 10)); dashboard.tap()
        XCTAssertTrue(app.staticTexts["Größte Gruppe"].waitForExistence(timeout: 10)); capture("29-Alt-Dashboard")
        let ips = app.buttons["IP-Ausschlüsse verwalten"]
        for _ in 0..<5 { if ips.isHittable { break }; app.swipeUp() }
        ips.tap()
        XCTAssertTrue(app.staticTexts["82.165.45.159"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["Für Alt-Erkennung ausgeschlossen"].exists); capture("30-Alt-IP-Regeln")
    }
    func testIntegratedSuitePlayersSearchDevicesAndOffline() throws {
        let online = app.buttons["workspace-online-simplex1"]
        XCTAssertTrue(online.waitForExistence(timeout: 15)); online.tap()
        XCTAssertTrue(app.staticTexts["iPhone · Demo"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.staticTexts["RecentOffline"].exists)
        app.segmentedControls.buttons["Zuletzt online"].tap()
        XCTAssertTrue(app.staticTexts["RecentOffline"].waitForExistence(timeout: 10))
        app.buttons.containing(.staticText, identifier: "RecentOffline").firstMatch.tap()
        let deviceRow = app.descendants(matching: .any)["player-device"].firstMatch
        XCTAssertTrue(deviceRow.waitForExistence(timeout: 10))
        XCTAssertTrue(deviceRow.label.contains("Letztes Gerät") && deviceRow.label.contains("Samsung · Demo"))
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
            app.alerts.buttons["confirm-reset-dashboard"].firstMatch.tap()
        }
        for _ in 0..<4 { if app.buttons["add-card-fleet"].isHittable { break }; app.swipeUp() }
        app.buttons["add-card-fleet"].tap()
        app.buttons["finish-dashboard"].tap()
        app.terminate(); app.launch()
        app.buttons["editHomeLayout"].tap()
        XCTAssertTrue(app.buttons["hide-card-fleet"].waitForExistence(timeout: 10))
        for _ in 0..<5 { if app.buttons["reset-dashboard"].isHittable { break }; app.swipeUp() }
        app.buttons["reset-dashboard"].tap()
        XCTAssertTrue(app.alerts.buttons["confirm-reset-dashboard"].firstMatch.waitForExistence(timeout: 5))
        app.alerts.buttons["cancel-reset-dashboard"].firstMatch.tap()
        for _ in 0..<4 { if app.buttons["hide-card-fleet"].isHittable { break }; app.swipeDown() }
        XCTAssertTrue(app.buttons["hide-card-fleet"].exists)
        for _ in 0..<5 { if app.buttons["reset-dashboard"].isHittable { break }; app.swipeUp() }
        app.buttons["reset-dashboard"].tap()
        app.alerts.buttons["confirm-reset-dashboard"].firstMatch.tap()
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
        app.buttons["reset-dashboard"].tap(); app.alerts.buttons["confirm-reset-dashboard"].firstMatch.tap()
        app.buttons["finish-dashboard"].tap()
        selectTab("Konsole")
        XCTAssertTrue(app.buttons["consoleHistory"].waitForExistence(timeout: 10))
        for _ in 0..<3 {
            if app.buttons["consoleHistory"].isHittable { break }
            app.scrollViews.containing(.button, identifier: "consoleHistory").firstMatch.swipeLeft()
        }
        app.buttons["consoleHistory"].tap()
        XCTAssertTrue(app.navigationBars["Befehlsverlauf"].waitForExistence(timeout: 5)); capture("24-Console-History")
        app.buttons["Fertig"].tap()
    }
    func testConsoleCategoriesCompactActionsAndStatisticsDrilldown() throws {
        executionTimeAllowance = 300
        app.terminate(); app.launchArguments.append("--demo-console-filters"); app.launch()
        let server = app.buttons["server-simplex1"]
        XCTAssertTrue(server.waitForExistence(timeout: 15)); server.tap()
        selectTab("Konsole")
        let console = app.textViews["consoleOutput"]
        XCTAssertTrue(console.waitForExistence(timeout: 10))
        for id in ["consoleHistory", "consoleEnd", "consolePause", "consoleShortcuts"] {
            XCTAssertTrue(app.buttons[id].isHittable, id)
            XCTAssertLessThan(app.buttons[id].frame.width, 70, id)
        }
        func category(_ raw: String, _ title: String, contains expected: String, excludes other: String) {
            app.buttons["consoleFilterMenu"].tap()
            let choice = app.buttons.matching(NSPredicate(format: "identifier == %@ OR label == %@", "console-category-" + raw, title)).firstMatch
            XCTAssertTrue(choice.waitForExistence(timeout: 5)); choice.tap()
            if !choice.waitForNonExistence(timeout: 2) { choice.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap() }
            XCTAssertTrue(choice.waitForNonExistence(timeout: 5), "Category menu must close after selection")
            let predicate = NSPredicate { _, _ in (console.value as? String ?? "").contains(expected) }
            XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: predicate, object: nil)], timeout: 10), .completed)
            XCTAssertFalse((console.value as? String ?? "").contains(other))
        }
        category("chat", "Chat", contains: "PLAYER_CHAT_ONLY", excludes: "DEMO_ERROR_ONLY")
        category("errors", "Fehler & Stacktraces", contains: "DEMO_ERROR_ONLY", excludes: "PLAYER_CHAT_ONLY")
        category("joins", "Spielerbeitritte", contains: "DemoGuest joined the game", excludes: "PLAYER_CHAT_ONLY")
        category("leaves", "Spieleraustritte", contains: "DemoGuest left the game", excludes: "joined the game")
        capture("25-Console-Filters")
    }
    func testStatisticsDeviceDrilldown() throws {
        let server = app.buttons["server-simplex1"]
        XCTAssertTrue(server.waitForExistence(timeout: 15)); server.tap()
        selectTab("Statistiken")
        XCTAssertTrue(app.staticTexts["suiteStatisticsTitle"].waitForExistence(timeout: 10)); capture("27-Statistics-Dashboard")
        let models = app.buttons["statistics-models"]
        for _ in 0..<6 { if models.isHittable { break }; app.swipeUp() }
        XCTAssertTrue(models.waitForExistence(timeout: 10)); models.tap()
        let iphone = app.buttons["statistics-bucket-models|iphone · demo"].firstMatch
        XCTAssertTrue(iphone.waitForExistence(timeout: 10)); iphone.tap()
        XCTAssertTrue(app.staticTexts["statistics-group-title"].waitForExistence(timeout: 10))
        let player = app.buttons.containing(.staticText, identifier: ".BedrockDemo").firstMatch
        XCTAssertTrue(player.waitForExistence(timeout: 10)); capture("26-Statistics-Device-Players")
        player.tap()
        XCTAssertTrue(app.buttons["Alle Geräte anzeigen"].waitForExistence(timeout: 10))
    }
    func testSuiteOperationsStructuredTelemetry() throws {
        let server = app.buttons["server-simplex1"]
        XCTAssertTrue(server.waitForExistence(timeout: 15)); server.tap()
        selectTab("Verwalten")
        XCTAssertTrue(app.buttons["adminSuiteHub"].waitForExistence(timeout: 10)); app.buttons["adminSuiteHub"].tap()
        let operations = app.buttons["Server, Statistiken & Discord-Protokolle"]
        for _ in 0..<5 { if operations.isHittable { break }; app.swipeUp() }
        XCTAssertTrue(operations.waitForExistence(timeout: 10)); operations.tap()
        XCTAssertTrue(app.staticTexts["Tickdauer"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["Arbeitsspeicher"].exists)
        for _ in 0..<5 { if app.staticTexts["Wartende Einträge"].isHittable { break }; app.swipeUp() }
        XCTAssertTrue(app.staticTexts["Wartende Einträge"].exists)
        capture("28-Suite-Operations")
    }
    func testJavaWorldStorageAndNewWorkspaceCards() throws {
        let server = app.buttons["server-simplex1"]
        XCTAssertTrue(server.waitForExistence(timeout: 15)); server.tap()
        selectTab("Verwalten")
        let worlds = app.buttons["Java-Welten & Speicheranalyse"]
        for _ in 0..<5 { if worlds.isHittable { break }; app.swipeUp() }
        XCTAssertTrue(worlds.waitForExistence(timeout: 10)); worlds.tap()
        let world = app.buttons["world"].firstMatch
        for _ in 0..<4 { if world.isHittable { break }; app.swipeUp() }
        XCTAssertTrue(world.waitForExistence(timeout: 10)); world.tap()
        app.buttons["Weltdateien analysieren"].tap()
        XCTAssertTrue(app.staticTexts["Gesamtgröße"].waitForExistence(timeout: 15))
        XCTAssertTrue(app.staticTexts["Spielerdateien (.dat)"].exists)
        capture("29-Java-Weltspeicher")
        selectTab("Übersicht")
        app.buttons["editServerLayout"].tap()
        let tools = app.buttons["add-card-minecraftTools"]
        for _ in 0..<8 { if tools.isHittable { break }; app.swipeUp() }
        XCTAssertTrue(tools.waitForExistence(timeout: 10)); tools.tap()
        capture("30-Workspace-Karten")
        for _ in 0..<6 { if app.buttons["reset-dashboard"].isHittable { break }; app.swipeUp() }
        app.buttons["reset-dashboard"].tap(); app.alerts.buttons["confirm-reset-dashboard"].firstMatch.tap()
        app.buttons["finish-dashboard"].tap()
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


