import Capacitor
import Foundation

private struct ServerEntry: Codable {
    let id: String
    let url: String
    var name: String
}

/// The servers the user has added, and which one the webview is pointed at.
///
/// Capacitor's `server.url` is static config, so the instance a self-hoster
/// types in can only be applied natively. On launch we reload straight into the
/// active server; with none set the bundled wizard is what the user sees.
///
/// Mirrors `hasht-desktop`'s `serverStore.ts`. Holds only which servers the
/// user added — never credentials, which stay in each origin's own cookie jar
/// (WKWebView isolates those per origin, so switching servers keeps sessions
/// separate for free).
@objc(ServerConfigPlugin)
public class ServerConfigPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "ServerConfigPlugin"
    public let jsName = "ServerConfig"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "list", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "add", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "connect", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "remove", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "rename", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "reset", returnType: CAPPluginReturnPromise)
    ]

    private static let serversKey = "net.hasht.app.servers"
    private static let activeKey = "net.hasht.app.activeServerId"

    private var servers: [ServerEntry] {
        get {
            guard let data = UserDefaults.standard.data(forKey: Self.serversKey),
                  let decoded = try? JSONDecoder().decode([ServerEntry].self, from: data)
            else { return [] }
            return decoded
        }
        set {
            UserDefaults.standard.set(try? JSONEncoder().encode(newValue), forKey: Self.serversKey)
        }
    }

    private var activeId: String? {
        get { UserDefaults.standard.string(forKey: Self.activeKey) }
        set { UserDefaults.standard.set(newValue, forKey: Self.activeKey) }
    }

    override public func load() {
        guard let active = servers.first(where: { $0.id == activeId }) else { return }
        load(active.url)
    }

    @objc func list(_ call: CAPPluginCall) {
        call.resolve([
            "servers": servers.map { ["id": $0.id, "url": $0.url, "name": $0.name] },
            "activeId": activeId as Any
        ])
    }

    @objc func add(_ call: CAPPluginCall) {
        guard let raw = call.getString("url"),
              let url = URL(string: raw),
              let host = url.host, url.scheme != nil
        else {
            call.reject("a valid absolute URL is required")
            return
        }

        let name = call.getString("name")?.trimmingCharacters(in: .whitespaces)
        let entry = ServerEntry(
            id: UUID().uuidString,
            url: raw,
            name: (name?.isEmpty == false ? name! : host)
        )
        servers.append(entry)
        activeId = entry.id
        load(entry.url)
        call.resolve(["server": ["id": entry.id, "url": entry.url, "name": entry.name]])
    }

    @objc func connect(_ call: CAPPluginCall) {
        guard let id = call.getString("id"), let entry = servers.first(where: { $0.id == id }) else {
            call.reject("unknown server")
            return
        }
        activeId = id
        load(entry.url)
        call.resolve()
    }

    @objc func remove(_ call: CAPPluginCall) {
        guard let id = call.getString("id") else {
            call.reject("id is required")
            return
        }
        servers = servers.filter { $0.id != id }
        if activeId == id {
            activeId = nil
        }
        call.resolve()
    }

    @objc func rename(_ call: CAPPluginCall) {
        guard let id = call.getString("id"),
              let name = call.getString("name")?.trimmingCharacters(in: .whitespaces),
              !name.isEmpty
        else {
            call.reject("id and a non-empty name are required")
            return
        }
        servers = servers.map { $0.id == id ? ServerEntry(id: $0.id, url: $0.url, name: name) : $0 }
        call.resolve()
    }

    /// Back to the wizard without forgetting anything — sign-out, or an
    /// instance that no longer answers and would otherwise leave the app
    /// pinned to a dead origin.
    @objc func reset(_ call: CAPPluginCall) {
        activeId = nil
        DispatchQueue.main.async { [weak self] in
            guard let local = self?.bridge?.config.serverURL else { return }
            self?.bridge?.webView?.load(URLRequest(url: local))
        }
        call.resolve()
    }

    private func load(_ raw: String) {
        DispatchQueue.main.async { [weak self] in
            guard let url = URL(string: raw), let webView = self?.bridge?.webView else { return }
            webView.load(URLRequest(url: url))
        }
    }
}
