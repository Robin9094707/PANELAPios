import SwiftUI

/// Session-local server grants. Never persist rights across accounts or panel connections.
@MainActor final class ServerAccess: ObservableObject {
    @Published private(set) var grants: [String: Set<String>] = [:]
    @Published private(set) var denied: Set<String> = []
    func ingest(_ response: JSON, server: String) {
        let meta = response["meta"]
        if case .array(let values) = meta["user_permissions"] { grants[server] = Set(values.map(\.string)) }
        else if case .array(let values) = response.attributes["user_permissions"] { grants[server] = Set(values.map(\.string)) }
        if meta["is_server_owner"].bool { grants[server] = ["*"] }
    }
    func allows(_ server: String, _ permission: String) -> Bool {
        guard let rights = grants[server] else { return true } // Providers without metadata still decide every request.
        return rights.contains("*") || rights.contains(permission) || rights.contains(String(permission.split(separator: ".").first ?? "") + ".*")
    }
    func allows(path: String, method: String = "GET", body: JSON? = nil) -> Bool {
        if denied.contains(method + " " + path) { return false }
        guard let requirement = Self.requirement(path, method: method, body: body) else { return true }
        return allows(requirement.server, requirement.permission)
    }
    func recordDenial(path: String, method: String) { denied.insert(method + " " + path) }
    func clearDenials(server: String) { denied = denied.filter { !$0.hasSuffix(" servers/\(server)") && !$0.contains(" servers/\(server)/") } }
    nonisolated static func requirement(_ path: String, method: String = "GET", body: JSON? = nil) -> (server: String, permission: String)? {
        let p = path.split(separator: "/").map(String.init)
        guard p.count > 2, p[0] == "servers" else { return nil }
        let tail = p.last ?? "", read = method == "GET", group: String
        switch p[2] {
        case "command": return (p[1], "control.console")
        case "websocket": return (p[1], "websocket.connect")
        case "power": let signal = body?["signal"].string ?? ""; return signal.isEmpty ? nil : (p[1], "control." + (signal == "kill" ? "stop" : signal))
        case "files":
            let action: String
            switch tail {
            case "list": action = "read"
            case "contents", "download": action = "read-content"
            case "upload", "create-folder", "copy", "pull": action = "create"
            case "delete": action = "delete"
            case "compress", "decompress": action = "archive"
            default: action = "update"
            }; return (p[1], "file." + action)
        case "backups":
            let action = tail == "download" ? "download" : tail == "restore" ? "restore" : tail == "lock" || method == "DELETE" ? "delete" : read ? "read" : "create"
            return (p[1], "backup." + action)
        case "databases": group = "database"
        case "schedules":
            return (p[1], "schedule." + (read ? "read" : p.count == 3 ? "create" : method == "DELETE" && p.count == 4 ? "delete" : "update"))
        case "users": group = "user"
        case "allocations", "network": group = "allocation"
        case "startup": return (p[1], "startup." + (read ? "read" : "update"))
        case "settings":
            if tail == "docker-image" { return (p[1], "startup.docker-image") }
            if ["rename", "reinstall"].contains(tail) { return (p[1], "settings." + tail) }
            return nil
        case "activity": return (p[1], "activity.read")
        default: return nil // Provider extensions have their own authorization; don't invent permission names.
        }
        let collectionCount = p[2] == "network" ? 4 : 3
        return (p[1], group + "." + (read ? "read" : method == "DELETE" ? "delete" : p.count == collectionCount ? "create" : "update"))
    }
}
private struct AccessModifier: ViewModifier {
    @ObservedObject var access: ServerAccess
    let server: String
    let permissions: [String]
    func body(content: Content) -> some View {
        let allowed = permissions.allSatisfy { access.allows(server, $0) }
        content.disabled(!allowed).accessibilityHint(allowed ? "" : "Keine Berechtigung für diese Aktion")
    }
}
extension View {
    func requiresAccess(_ api: PanelAPI, _ server: String, _ permissions: String...) -> some View {
        modifier(AccessModifier(access: api.access, server: server, permissions: permissions))
    }
}
struct AccessBoundary<Content: View>: View {
    @ObservedObject var access: ServerAccess
    let server: String
    let permission: String
    @ViewBuilder let content: () -> Content
    var body: some View {
        if access.allows(server, permission) { content() }
        else { ContentUnavailableView("Keine Berechtigung", systemImage: "lock.shield", description: Text("Dein Panel-Account hat für diesen Bereich keinen Zugriff. Der Serverbesitzer kann die Rechte im Panel ändern.")).panelScreen() }
    }
}

private struct RequestAccessModifier: ViewModifier {
    @ObservedObject var access: ServerAccess
    let path: String
    let method: String
    let body: JSON?
    func body(content: Content) -> some View {
        let allowed = access.allows(path: path, method: method, body: body)
        content.disabled(!allowed).accessibilityHint(allowed ? "" : "Keine Berechtigung für diese Aktion")
    }
}
extension View {
    func requiresRequest(_ api: PanelAPI, _ path: String, method: String = "POST", payload: JSON? = nil) -> some View {
        modifier(RequestAccessModifier(access: api.access, path: path, method: method, body: payload))
    }
}
struct AccessSummary: View {
    @ObservedObject var access: ServerAccess
    let api: PanelAPI
    let server: String
    var body: some View {
        if let rights = access.grants[server], !rights.contains("*") {
            VStack(alignment: .leading, spacing: 8) {
                Label("Eingeschränkter Zugriff", systemImage: "person.badge.key.fill").font(.subheadline.bold())
                Text("Nicht freigegebene Aktionen sind deaktiviert. Die Rechte vergibt der Serverbesitzer im Panel.").font(.caption).foregroundStyle(.secondary)
                Button("Berechtigungen aktualisieren", systemImage: "arrow.clockwise") { Task { await api.loadAccess(server: server) } }.font(.caption)
            }.card()
        }
    }
}
