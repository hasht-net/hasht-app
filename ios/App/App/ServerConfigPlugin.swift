import Capacitor
import Foundation
import UIKit
import UserNotifications
import WebKit

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
        CAPPluginMethod(name: "probe", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "signOut", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "list", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "add", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "connect", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "remove", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "rename", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "reset", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setTheme", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getLinkMode", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setLinkMode", returnType: CAPPluginReturnPromise)
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

    /// Confirms an origin is a Hasht instance and reports its name.
    ///
    /// Native rather than a `fetch` from the wizard: that page is served from
    /// `capacitor://localhost`, and no instance will have that scheme in its
    /// `CORS_ALLOWED_ORIGINS`.
    @objc func probe(_ call: CAPPluginCall) {
        guard let raw = call.getString("url"),
              let base = URL(string: raw),
              let health = URL(string: "/api/health", relativeTo: base)
        else {
            call.reject("That doesn't look like a valid address.")
            return
        }

        var request = URLRequest(url: health)
        request.timeoutInterval = 8

        URLSession.shared.dataTask(with: request) { [weak self] data, response, error in
            if let error = error {
                call.reject("Couldn't reach that server: \(error.localizedDescription)")
                return
            }
            guard let status = (response as? HTTPURLResponse)?.statusCode, status == 200 else {
                let code = (response as? HTTPURLResponse)?.statusCode ?? 0
                call.reject("That server returned \(code).")
                return
            }
            guard let data = data,
                  let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  json["status"] as? String == "ok"
            else {
                call.reject("That address doesn't look like a Hasht server.")
                return
            }
            self?.fetchName(base: base) { name in
                call.resolve(["name": name ?? base.host ?? "Hasht"])
            }
        }.resume()
    }

    /// Best-effort: a reachable instance with an unreadable name is still fine
    /// to connect to, so this never fails the probe.
    func fetchName(base: URL, completion: @escaping (String?) -> Void) {
        guard let settings = URL(string: "/api/instance-settings", relativeTo: base) else {
            completion(nil)
            return
        }
        var request = URLRequest(url: settings)
        request.timeoutInterval = 8
        URLSession.shared.dataTask(with: request) { data, _, _ in
            guard let data = data,
                  let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let name = json["app_name"] as? String, !name.isEmpty
            else {
                completion(nil)
                return
            }
            completion(name)
        }.resume()
    }

    /// The frontend signed the user out. Drops session-scoped native state and
    /// deliberately does *not* navigate — signing out leaves you on the same
    /// instance's login screen, as it does on the web.
    ///
    /// Ends this instance's push registration too, so a page that forgets to
    /// call `NativePush.unregister` doesn't leave the next user's phone
    /// receiving the last one's messages.
    @objc func signOut(_ call: CAPPluginCall) {
        ShareSession.save(nil)
        if let push = bridge?.plugin(withName: "NativePush") as? NativePushPlugin {
            Task { @MainActor in
                if let url = self.bridge?.webView?.url, ["https", "http"].contains(url.scheme) {
                    await push.unregister(origin: Self.originKey(url))
                }
            }
        }
        DispatchQueue.main.async {
            UNUserNotificationCenter.current().removeAllDeliveredNotifications()
            if #available(iOS 16.0, *) {
                UNUserNotificationCenter.current().setBadgeCount(0)
            } else {
                UIApplication.shared.applicationIconBadgeNumber = 0
            }
        }
        call.resolve()
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
        let entry = append(url: raw, name: name?.isEmpty == false ? name! : host)
        activate(entry.id)
        call.resolve(["server": ["id": entry.id, "url": entry.url, "name": entry.name]])
    }

    // MARK: - For NativeAuth

    var activeServer: (id: String, url: String)? {
        guard let entry = servers.first(where: { $0.id == activeId }) else { return nil }
        return (entry.id, entry.url)
    }

    /// The added server at this origin, if any. Compared by origin rather than
    /// string so a stored trailing slash or default port still matches.
    func serverId(forOrigin origin: URL) -> String? {
        servers.first { URL(string: $0.url).map(Self.originKey) == Self.originKey(origin) }?.id
    }

    /// Adds without loading, so a sign-in can finish before the webview moves.
    func addServer(url: String, name: String) -> String {
        append(url: url, name: name).id
    }

    /// Makes `id` active and loads it — at `url` if given, e.g. the message a
    /// tapped notification is about.
    func activate(_ id: String, loading url: String? = nil) {
        guard let entry = servers.first(where: { $0.id == id }) else { return }
        activeId = id
        load(url ?? entry.url)
    }

    static func originKey(_ url: URL) -> String {
        let port = url.port.map { ":\($0)" } ?? ""
        return "\(url.scheme ?? "")://\(url.host ?? "")\(port)".lowercased()
    }

    private func append(url: String, name: String) -> ServerEntry {
        let entry = ServerEntry(id: UUID().uuidString, url: url, name: name)
        servers.append(entry)
        return entry
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
            ShareSession.save(nil)
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

    /// Paints the chrome the webview does not cover to match the page.
    ///
    /// The page passes its own resolved color, so the shade is defined in
    /// exactly one place — and a pinned theme, which the system knows nothing
    /// about, still reaches the status bar.
    @objc func setTheme(_ call: CAPPluginCall) {
        let dark = call.getString("theme") == "dark"
        let color = UIColor(hex: call.getString("color") ?? (dark ? "#131417" : "#ffffff"))
        DispatchQueue.main.async { [weak self] in
            guard let controller = self?.bridge?.viewController else { return }
            controller.view.backgroundColor = color
            self?.webView?.backgroundColor = color
            self?.webView?.scrollView.backgroundColor = color
            (controller as? MainViewController)?.setStatusBarDark(dark)
        }
        call.resolve()
    }

    /// Links the user taps that leave the instance open per `LinkOpener`
    /// instead of replacing the chat in the webview. Only user-activated links:
    /// a server redirect through the instance's own SSO must still load.
    override public func shouldOverrideLoad(_ navigationAction: WKNavigationAction) -> NSNumber? {
        guard navigationAction.navigationType == .linkActivated,
              navigationAction.targetFrame?.isMainFrame ?? true,
              let url = navigationAction.request.url,
              let app = webView?.url,
              LinkOpener.leavesApp(url, app: app)
        else { return nil }
        DispatchQueue.main.async { [weak self] in
            LinkOpener.open(url, from: self?.bridge?.viewController)
        }
        return true
    }

    @objc func getLinkMode(_ call: CAPPluginCall) {
        call.resolve(["mode": LinkOpener.mode])
    }

    @objc func setLinkMode(_ call: CAPPluginCall) {
        guard let mode = call.getString("mode"), [LinkOpener.inApp, LinkOpener.system].contains(mode) else {
            call.reject("mode must be \"in-app\" or \"system\"")
            return
        }
        LinkOpener.mode = mode
        call.resolve()
    }

    /// Back to the wizard without forgetting anything — sign-out, or an
    /// instance that no longer answers and would otherwise leave the app
    /// pinned to a dead origin.
    @objc func reset(_ call: CAPPluginCall) {
        returnToWizard()
        call.resolve()
    }

    /// The frontend drives this from its own UI — the shell deliberately shows
    /// no server-switching control of its own.
    func returnToWizard() {
        ShareSession.save(nil)
        activeId = nil
        DispatchQueue.main.async { [weak self] in
            guard let local = self?.bridge?.config.serverURL else { return }
            self?.bridge?.webView?.load(URLRequest(url: local))
        }
    }

    private func load(_ raw: String) {
        ShareSession.save(nil)
        DispatchQueue.main.async { [weak self] in
            guard let url = URL(string: raw), let webView = self?.bridge?.webView else { return }
            webView.load(URLRequest(url: url))
        }
    }
}

private extension UIColor {
    /// `#rrggbb` only — the one form the frontend's token table emits.
    convenience init(hex: String) {
        var value: UInt64 = 0
        Scanner(string: hex.hasPrefix("#") ? String(hex.dropFirst()) : hex).scanHexInt64(&value)
        self.init(
            red: CGFloat((value >> 16) & 0xFF) / 255,
            green: CGFloat((value >> 8) & 0xFF) / 255,
            blue: CGFloat(value & 0xFF) / 255,
            alpha: 1
        )
    }
}
