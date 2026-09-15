import Capacitor
import Foundation

/// Points the webview at the instance the user configured.
///
/// Capacitor's `server.url` is static config, so the instance a self-hoster
/// types in can only be applied natively. On launch we reload straight into
/// the stored instance; with nothing stored the bundled bootstrap picker is
/// what the user sees.
@objc(ServerConfigPlugin)
public class ServerConfigPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "ServerConfigPlugin"
    public let jsName = "ServerConfig"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "get", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "connect", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "reset", returnType: CAPPluginReturnPromise)
    ]

    private static let storageKey = "net.hasht.app.serverURL"

    private var storedURL: String? {
        get { UserDefaults.standard.string(forKey: Self.storageKey) }
        set { UserDefaults.standard.set(newValue, forKey: Self.storageKey) }
    }

    override public func load() {
        guard let url = storedURL else { return }
        DispatchQueue.main.async { [weak self] in
            self?.loadInstance(url)
        }
    }

    @objc func get(_ call: CAPPluginCall) {
        call.resolve(["url": storedURL as Any])
    }

    @objc func connect(_ call: CAPPluginCall) {
        guard let raw = call.getString("url"), let url = URL(string: raw), url.scheme != nil else {
            call.reject("a valid absolute URL is required")
            return
        }
        storedURL = raw
        DispatchQueue.main.async { [weak self] in
            self?.loadInstance(raw)
        }
        call.resolve()
    }

    /// Back to the bundled picker — sign-out, or an instance that no longer
    /// answers and would otherwise leave the app pinned to a dead origin.
    @objc func reset(_ call: CAPPluginCall) {
        storedURL = nil
        DispatchQueue.main.async { [weak self] in
            guard let local = self?.bridge?.config.serverURL else { return }
            self?.bridge?.webView?.load(URLRequest(url: local))
        }
        call.resolve()
    }

    private func loadInstance(_ raw: String) {
        guard let url = URL(string: raw), let webView = bridge?.webView else { return }
        webView.load(URLRequest(url: url))
    }
}
