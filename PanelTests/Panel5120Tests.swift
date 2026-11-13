import XCTest
@testable import Panel

/// 5.12.0: UltraServers instead of Kinetic, automatic backups, Player Manager, subdomains, proxy, installers, properties editor.
@MainActor final class Panel5120Tests: XCTestCase {
    func testUltraServersDetectionAndWebLinks() {
        XCTAssertTrue(UltraServers.matches(URL(string: "https://panel.ultraservers.com")!))
        XCTAssertTrue(UltraServers.matches(URL(string: "https://PANEL.UltraServers.com/")!))
        XCTAssertFalse(UltraServers.matches(URL(string: "https://ultraservers.com.evil.example")!))
        XCTAssertFalse(UltraServers.matches(URL(string: "https://kineticpanel.net")!))
        XCTAssertTrue(PanelAPI(base: URL(string: "https://panel.ultraservers.com")!, key: "fixture").isUltraServers)
        XCTAssertFalse(PanelAPI(base: URL(string: "https://panel.example.com")!, key: "fixture").isUltraServers)
        XCTAssertEqual(UltraServers.webURL(base: URL(string: "https://panel.ultraservers.com")!, server: "1a2b3c4d").absoluteString, "https://panel.ultraservers.com/server/1a2b3c4d")
        XCTAssertEqual(UltraServers.backupInterval, 21_600)
    }

    func testSystemBackupsDecodeEveryEnvelopeNewestFirst() throws {
        let pterodactyl = try JSON.parse(#"{"object":"list","data":[{"object":"system_backup","attributes":{"uuid":"a1","created_at":"2026-11-13T06:00:04+00:00","bytes":1024}},{"object":"system_backup","attributes":{"uuid":"a2","created_at":"2026-11-13T12:00:03+00:00","bytes":2048}}]}"#)
        let first = SystemBackup.decode(pterodactyl)
        XCTAssertEqual(first.map(\.id), ["a2", "a1"])
        XCTAssertEqual(first.first?.byteCount, 2048)

        let wrapped = try JSON.parse(#"{"success":true,"data":{"backups":[{"id":7,"name":"Auto","date":1794549600,"size":"12.7 GB"}]}}"#)
        let second = try XCTUnwrap(SystemBackup.decode(wrapped).first)
        XCTAssertEqual(second.id, "7"); XCTAssertEqual(second.sizeLabel, "12.7 GB")
        XCTAssertEqual(second.created, Date(timeIntervalSince1970: 1_794_549_600))

        // restic-style snapshots: nanosecond timestamps and short ids.
        let restic = try JSON.parse(#"[{"short_id":"4f2a9c1e","time":"2026-11-13T00:00:01.123456789Z"}]"#)
        let third = try XCTUnwrap(SystemBackup.decode(restic).first)
        XCTAssertEqual(third.id, "4f2a9c1e"); XCTAssertNotNil(third.created)

        // IDs end up in URL paths: separators are rejected.
        XCTAssertTrue(SystemBackup.decode(try JSON.parse(#"[{"id":"../../x"}]"#)).isEmpty)
        XCTAssertTrue(SystemBackup.decode(.null).isEmpty)
        XCTAssertFalse(SystemBackup.decode(try JSON.parse(#"[{"id":"h","hidden":true,"created_at":"2026-11-01T00:00:00Z"}]"#))[0].restorable)
    }

    func testBackupScheduleSummary() throws {
        let now = Date(timeIntervalSince1970: 1_794_571_200) // 13.11.2026 18:00 UTC
        let backups = [0, 6, 12, 18, 48].map { hours in SystemBackup(id: "b\(hours)", name: "Auto", created: now.addingTimeInterval(-Double(hours) * 3600 - 600), byteCount: nil, sizeText: "", raw: .null) }
        let summary = SystemBackupSummary(backups, now: now)
        XCTAssertEqual(summary.count, 5)
        XCTAssertEqual(summary.next, now.addingTimeInterval(6 * 3600 - 600))
        XCTAssertFalse(summary.overdue)
        XCTAssertEqual(summary.coveredDays, 2)
        let stale = SystemBackupSummary([backups[4]], now: now)
        XCTAssertTrue(stale.overdue)
        XCTAssertGreaterThan(try XCTUnwrap(stale.next), now)
        XCTAssertNil(SystemBackupSummary([], now: now).next)
    }

    func testPlayerManagerListsAndSnapshot() throws {
        let response = try JSON.parse(#"{"data":{"online":[{"name":"Robin","uuid":"5d2f1c3e-7a8b-4c9d-8e0f-1a2b3c4d5e6f"},{"name":".Steve2277"}],"ops":["Robin"],"whitelisted":[{"username":"Robin"}],"banned":[{"name":"Griefer","reason":"Griefing","source":"Robin"}],"banned_ips":[{"ip":"203.0.113.9"}],"offline":[{"name":"Alex"}],"max":60}}"#)
        let list = UltraPlayerList.decode(response)
        XCTAssertEqual(list.groups.map(\.key), ["online", "ops", "whitelist", "banned", "banned_ips", "offline"])
        XCTAssertEqual(list.online.map(\.name), ["Robin", ".Steve2277"])
        XCTAssertEqual(list.groups[3].entries.first?.detail, "Griefing · von Robin")
        XCTAssertEqual(list.groups[4].entries.first?.name, "203.0.113.9")
        XCTAssertEqual(list.snapshot?.online, 2); XCTAssertEqual(list.snapshot?.maximum, 60)
        let plain = UltraPlayerList.decode(try JSON.parse(#"["Robin","Robin","Alex"]"#))
        XCTAssertEqual(plain.online.map(\.name), ["Robin", "Alex"])
        let classic = UltraPlayerList.decode(try JSON.parse(#"{"players":{"online":1,"max":20,"list":[{"name":"Robin"}]}}"#))
        XCTAssertEqual(classic.online.map(\.name), ["Robin"]); XCTAssertEqual(classic.maximum, 20)
    }

    func testPlayerActionsBuildSafeCommands() throws {
        XCTAssertEqual(try UltraPlayerAction.op.command(player: "Robin"), "op Robin")
        XCTAssertEqual(try UltraPlayerAction.whitelistAdd.command(player: ".Steve2277"), "whitelist add .Steve2277")
        XCTAssertEqual(try UltraPlayerAction.ban.command(player: "Griefer", reason: "Griefing am Spawn"), "ban Griefer Griefing am Spawn")
        XCTAssertEqual(try UltraPlayerAction.pardon.command(player: "Griefer", reason: "ignored"), "pardon Griefer")
        XCTAssertEqual(try UltraPlayerAction.banIP.command(player: "203.0.113.9"), "ban-ip 203.0.113.9")
        XCTAssertThrowsError(try UltraPlayerAction.kick.command(player: "Robin; stop"))
        XCTAssertThrowsError(try UltraPlayerAction.op.command(player: "203.0.113.9 x"))
        XCTAssertThrowsError(try UltraPlayerAction.kick.command(player: "Robin", reason: "a\nstop"))
        XCTAssertEqual(UltraPlayerAction.actions(for: "banned"), [.pardon])
        XCTAssertEqual(UltraPlayerAction.actions(for: "banned_ips"), [.pardonIP])
    }

    func testSubdomainsAndProxyDecoding() throws {
        let sub = UltraSubdomains.decode(try JSON.parse(#"{"success":true,"data":{"domains":[{"id":1,"domain":"ultramc.gg"}],"subdomains":[{"id":7,"subdomain":"simplex","domain_id":1,"record_type":"SRV","port":25565}]}}"#))
        XCTAssertEqual(sub.domains, [.init(id: "1", name: "ultramc.gg")])
        XCTAssertEqual(sub.records.first?.host, "simplex.ultramc.gg"); XCTAssertEqual(sub.records.first?.id, "7")
        XCTAssertEqual(sub.records.first?.detail, "SRV · Port 25565")
        let flat = UltraSubdomains.decode(try JSON.parse(#"[{"id":3,"full_domain":"play.example.gg"}]"#))
        XCTAssertTrue(flat.domains.isEmpty); XCTAssertEqual(flat.records.first?.host, "play.example.gg")
        XCTAssertTrue(UltraSubdomains.validLabel("play-1")); XCTAssertFalse(UltraSubdomains.validLabel("-play")); XCTAssertFalse(UltraSubdomains.validLabel("Play"))

        let proxies = UltraProxy.decode(try JSON.parse(#"{"data":[{"id":3,"domain":"map.simplexsmp.eu","port":8123,"ssl_enabled":1},{"id":4,"domain":"plan.simplexsmp.eu","allocation":{"ip":"1.2.3.4","port":8804}}]}"#))
        XCTAssertEqual(proxies.map(\.domain), ["map.simplexsmp.eu", "plan.simplexsmp.eu"])
        XCTAssertTrue(proxies[0].ssl); XCTAssertEqual(proxies[0].target, "Port 8123"); XCTAssertEqual(proxies[1].target, "1.2.3.4:8804")
        XCTAssertTrue(UltraProxy.validDomain("map.simplexsmp.eu")); XCTAssertFalse(UltraProxy.validDomain("https://map.simplexsmp.eu")); XCTAssertFalse(UltraProxy.validDomain("localhost"))
    }

    func testInstallerCatalogDecoding() throws {
        let items = UltraCatalogItem.decode(try JSON.parse(#"{"data":{"plugins":[{"id":"essentialsx","name":"EssentialsX","description":"Basics","downloads":12400000,"icon_url":"https://cdn.example/icon.png","author":{"name":"EssentialsX Team"}},{"slug":"luckperms","title":"LuckPerms","icon":"http://insecure.example/i.png","author":"Luck"}]}}"#))
        XCTAssertEqual(items.map(\.id), ["essentialsx", "luckperms"])
        XCTAssertEqual(items[0].author, "EssentialsX Team"); XCTAssertEqual(items[1].author, "Luck")
        XCTAssertNotNil(items[0].icon); XCTAssertNil(items[1].icon, "only HTTPS icons are loaded")
        let versions = UltraCatalogVersion.decode(try JSON.parse(#"{"versions":[{"id":"v1","version_number":"2.21.2","game_versions":["1.21.10"],"loaders":["paper"]},"2.21.1"]}"#))
        XCTAssertEqual(versions.map(\.id), ["v1", "2.21.1"])
        XCTAssertEqual(versions[0].name, "2.21.2"); XCTAssertEqual(versions[0].detail, "1.21.10 · paper")
    }

    func testServerPropertiesRoundTripKeepsEverythingElse() {
        let text = "#Minecraft server properties\n#Fri Nov 13 12:00:00 CET 2026\nmotd=\\u00A7aSimplex SMP \\u00FC\nmax-players=60\nonline-mode=true\ndifficulty = normal\nlevel-seed=\nresource-pack=https\\://example.com/pack.zip\n"
        let file = ServerProperties.parse(text)
        XCTAssertEqual(file.values["motd"], "§aSimplex SMP ü")
        XCTAssertEqual(file.values["difficulty"], "normal")
        XCTAssertEqual(file.values["resource-pack"], "https://example.com/pack.zip")
        XCTAssertEqual(file.values["level-seed"], "")
        XCTAssertEqual(file.text(applying: [:]), text, "no changes: identical file")
        XCTAssertEqual(file.text(applying: ["max-players": "60"]), text, "same value: untouched line")
        let changed = file.text(applying: ["max-players": "80", "difficulty": "hard", "motd": "Hallo Welt ä", "pvp": "false"])
        XCTAssertTrue(changed.contains("max-players=80\n"))
        XCTAssertTrue(changed.contains("difficulty = hard\n"), "separator style kept")
        XCTAssertTrue(changed.contains("motd=Hallo Welt \\u00E4\n"))
        XCTAssertTrue(changed.hasSuffix("pvp=false\n"), "new keys appended")
        XCTAssertTrue(changed.hasPrefix("#Minecraft server properties\n#Fri Nov 13"), "comments kept")
        XCTAssertEqual(ServerProperties.parse(changed).values["motd"], "Hallo Welt ä")
        XCTAssertEqual(ServerProperties.kind("online-mode", value: "true"), .bool)
        XCTAssertEqual(ServerProperties.kind("level-seed", value: "12345"), .text)
        XCTAssertEqual(ServerProperties.parse("a=b\r\nc=d").values, ["a": "b", "c": "d"])
    }

    func testKineticSpecificsAreGone() {
        XCTAssertNil(ServerAccess.requirement("servers/abc/system-backups"), "provider extensions decide their own permissions")
        XCTAssertEqual(ServerAccess.requirement("servers/abc/backups/x/export", method: "POST")?.permission, "backup.create")
        XCTAssertEqual(WhatsNew.release(for: "5.12.0")?.title, "UltraServers")
        let demo = Demo.response("servers/simplex1/system-backups")
        XCTAssertEqual(SystemBackup.decode(demo).count, 8)
        XCTAssertFalse(SystemBackupSummary(SystemBackup.decode(demo)).overdue)
    }
}
