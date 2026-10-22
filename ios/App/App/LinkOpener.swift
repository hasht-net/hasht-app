import SafariServices
import UIKit
import WebKit

/// Where links that leave the instance open.
///
/// Loading them in the main webview would replace the chat UI with an
/// arbitrary site that has no way back, so they are handed off instead. The
/// default is an in-app Safari sheet (the user stays in the app and Done
/// returns to the chat); `system` sends them to the default browser. The
/// frontend exposes the choice through `ServerConfig.setLinkMode`.
enum LinkOpener {
    static let inApp = "in-app"
    static let system = "system"

    private static let modeKey = "net.hasht.app.linkMode"

    static var mode: String {
        get { UserDefaults.standard.string(forKey: modeKey) == system ? system : inApp }
        set { UserDefaults.standard.set(newValue, forKey: modeKey) }
    }

    static func isWeb(_ url: URL) -> Bool {
        ["http", "https"].contains(url.scheme?.lowercased() ?? "")
    }

    /// A web URL on a different origin from the instance the webview shows.
    static func leavesApp(_ url: URL, app: URL) -> Bool {
        isWeb(url) && ServerConfigPlugin.originKey(url) != ServerConfigPlugin.originKey(app)
    }

    static func open(_ url: URL, from presenter: UIViewController?) {
        guard isWeb(url), mode == inApp, let presenter = presenter else {
            UIApplication.shared.open(url, options: [:], completionHandler: nil)
            return
        }
        var top = presenter
        while let next = top.presentedViewController { top = next }
        top.present(SFSafariViewController(url: url), animated: true)
    }
}

/// `window.open` and `target=_blank` reach the UI delegate rather than the
/// navigation policy, where Capacitor always sends them to Safari. Everything
/// else is forwarded to Capacitor's own delegate (permissions, file pickers,
/// JS dialogs), so this only takes over new-window requests.
final class LinkUIDelegate: NSObject, WKUIDelegate {
    private let wrapped: WKUIDelegate
    private weak var viewController: UIViewController?
    weak var serverConfig: ServerConfigPlugin?

    init(wrapping wrapped: WKUIDelegate, presenter: UIViewController) {
        self.wrapped = wrapped
        self.viewController = presenter
    }

    override func responds(to selector: Selector!) -> Bool {
        super.responds(to: selector) || wrapped.responds(to: selector)
    }

    override func forwardingTarget(for selector: Selector!) -> Any? {
        wrapped.responds(to: selector) ? wrapped : super.forwardingTarget(for: selector)
    }

    func webView(
        _ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration,
        for navigationAction: WKNavigationAction, windowFeatures: WKWindowFeatures
    ) -> WKWebView? {
        guard let url = navigationAction.request.url else { return nil }
        if let app = webView.url, LinkOpener.isWeb(url), !LinkOpener.leavesApp(url, app: app) {
            // Same instance in a new tab: nothing to hand off.
            webView.load(navigationAction.request)
        } else if let servers = serverConfig, servers.ownsLink(url) {
            NotificationCenter.default.post(name: .hashtRouteLink, object: ["url": url])
        } else {
            LinkOpener.open(url, from: viewController)
        }
        return nil
    }
}
