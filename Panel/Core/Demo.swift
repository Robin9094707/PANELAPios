import Foundation

enum Demo {
    static let servers: [JSON] = [server("simplex1", "Simplex SMP", "Java + Bedrock · Paper", 18432, 51200), server("vanilla2", "Vanilla", "A fresh world. Your rules.", 8192, 30720)]
    static func server(_ id: String, _ name: String, _ description: String, _ ram: Int, _ disk: Int) -> JSON {
        try! JSON.from(["identifier": id, "name": name, "node": "Frankfurt • EU", "description": description, "server_owner": true,
            "limits": ["memory": ram, "disk": disk, "cpu": 400], "feature_limits": ["backups": 15, "databases": 3, "allocations": 5],
            "sftp_details": ["ip": "node.example.net", "port": 2022], "invocation": "java -Xms128M -Xmx18G -jar server.jar",
            "docker_image": "ghcr.io/pterodactyl/yolks:java_25", "relationships": ["allocations": ["data": [["attributes": ["ip": "simplexsmp.eu", "port": 25565, "is_default": true]]]]]])
    }
    static func wrap(_ rows: [[String: Any]]) -> JSON { try! JSON.from(["data": rows.map { ["attributes": $0] }]) }
    static func playerData() throws -> Data {
        var health = NBTNode(type: 5, name: "Health"); try health.setText("14.5")
        var food = NBTNode(type: 3, name: "foodLevel"); try food.setText("14")
        var xp = NBTNode(type: 3, name: "XpLevel"); try xp.setText("12")
        var name = NBTNode(type: 8, name: "lastKnownName"); try name.setText(".DemoPlayer")
        let bukkit = NBTNode(type: 10, name: "bukkit", children: [name])
        return try NBTDocument(root: NBTNode(type: 10, name: "", children: [health, food, xp, bukkit]), compression: .gzip).encode()
    }
    static func response(_ path: String, query: [String: String] = [:]) -> JSON {
        if path == "permissions" { return try! JSON.from(["attributes": ["permissions": [
            "control": ["description": "Server und Konsole steuern.", "keys": ["console": "Konsolenbefehle an den Server senden.", "start": "Den Server starten.", "stop": "Den Server stoppen."]],
            "file": ["description": "Dateiverwaltung.", "keys": ["read": "Ordnerinhalte ansehen.", "update": "Dateien bearbeiten."]],
            "minecraft": ["name": "Provider-Spielerfunktionen", "description": "Zusätzliche Rechte dieses Beispielpanels.", "keys": ["manage": ["name": "Spieler verwalten", "description": "Provider-Spielerfunktionen verwenden."]]]
        ]]]) }
        if path.isEmpty { return .object(["data": .array(servers.map { .object(["attributes": $0]) })]) }
        if path.contains("files/list"), query["directory"] == "/plugins" { return wrap([["name": "ExamplePlugin.jar", "is_file": true, "size": 4096], ["name": "ExamplePlugin", "is_file": false, "size": 0], ["name": "update", "is_file": false, "size": 0]]) }
        if path.contains("files/list"), query["directory"] == "/plugins/update" { return wrap([]) }
        if path.contains("files/list"), query["directory"] == "/world" { return wrap([["name": "playerdata", "is_file": false, "size": 0], ["name": "level.dat", "is_file": true, "size": 800]]) }
        if path.contains("files/list"), query["directory"] == "/world/playerdata" { return wrap([["name": "00000000-0000-0000-0000-000000000001.dat", "is_file": true, "size": 200]]) }
        if path.contains("files/list") { return wrap([
            ["name": "plugins", "is_file": false, "size": 0, "modified_at": "2026-10-19T20:37:00Z"],
            ["name": "world", "is_file": false, "size": 0], ["name": "server.properties", "is_file": true, "size": 1924],
            ["name": "paper-global.yml", "is_file": true, "size": 4218], ["name": "server.jar", "is_file": true, "size": 54218512]]) }
        if path.hasSuffix("backups") { return wrap([
            ["uuid": "backup-1", "name": "Before update", "is_successful": true, "is_locked": true, "bytes": 12814000000, "completed_at": "2026-10-19T19:00:00Z", "created_at": "2026-10-19T18:58:00Z"],
            ["uuid": "backup-2", "name": "Daily backup", "is_successful": true, "is_locked": false, "bytes": 12714000000, "completed_at": "2026-10-18T03:00:00Z", "created_at": "2026-10-18T02:58:00Z"]]) }
        if path.hasSuffix("allocations") { return wrap([["id": 1, "ip": "simplexsmp.eu", "port": 25565, "is_default": true, "notes": "Java"], ["id": 2, "ip": "simplexsmp.eu", "port": 19132, "is_default": false, "notes": "Bedrock"]]) }
        if path.hasSuffix("schedules") { return wrap([["id": 1, "name": "Daily backup", "is_active": true, "only_when_online": false, "cron": ["minute": "0", "hour": "4", "day_of_month": "*", "month": "*", "day_of_week": "*"], "relationships": ["tasks": ["data": [["attributes": ["id": 1, "action": "backup", "payload": "", "time_offset": 0, "continue_on_failure": false]]]]]]]) }
        if path.hasSuffix("resources") { return try! JSON.from(["attributes": ["current_state": "running", "resources": ["memory_bytes": 8214000000, "cpu_absolute": 42, "disk_bytes": 23144000000]]]) }
        if path.hasSuffix("minecraft/properties") { return try! JSON.from(["properties": ["motd": "Simplex SMP", "difficulty": "normal", "gamemode": "survival", "max-players": 60, "online-mode": true, "level-name": "world", "view-distance": 10, "simulation-distance": 6]]) }
        if path.hasSuffix("databases") { return wrap([["id": "db1", "name": "s1_minecraft", "username": "u1_simplex", "connections_from": "%", "host": ["address": "db.example.net", "port": 3306], "relationships": ["password": ["attributes": ["password": "demo-password-only"]]]]]) }
        if path.hasSuffix("users") { return wrap([["uuid": "user1", "email": "team@example.net", "username": "Team", "permissions": ["control.console", "control.start", "file.read"]]]) }
        if path.hasSuffix("startup") { return wrap([["name": "Server Jar", "env_variable": "SERVER_JARFILE", "server_value": "server.jar", "default_value": "server.jar", "is_editable": true, "rules": "required|string", "description": "The server JAR file to launch."]]) }
        if path.hasSuffix("activity") { return wrap([["id": "1", "event": "server:console.command", "timestamp": "2026-10-19T20:37:00Z", "properties": ["command": "list"]], ["id": "2", "event": "server:backup.complete", "timestamp": "2026-10-19T19:00:00Z"]]) }
        if path == "account" { return .object(["attributes": try! JSON.from(["username": "RJ", "email": "rj@example.net", "first_name": "Robin", "last_name": "", "language": "de", "admin": false])]) }
        if path.hasPrefix("account/") { return wrap([]) }
        if path.hasPrefix("servers/") { return .object(["attributes": servers[0], "meta": .object(["is_server_owner": .bool(true)])]) }
        return .null
    }
}
