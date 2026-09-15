import Capacitor
import UIKit
import WebKit

/// Capacitor only auto-discovers plugins from packages, so anything defined in
/// the app target has to be handed to the bridge explicitly.
class MainViewController: CAPBridgeViewController {
    private let serverConfig = ServerConfigPlugin()
    private var insetScript: WKUserScript?
    private var published: UIEdgeInsets = .init(top: -1, left: -1, bottom: -1, right: -1)

    override open func capacitorDidLoad() {
        bridge?.registerPluginInstance(serverConfig)
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        // A left-edge swipe walks back to the wizard, which is still in the
        // webview's history after the instance is loaded into it. Switching
        // servers proper belongs to the frontend (`ServerConfig.reset`); this
        // is just the platform gesture, not a second control.
        webView?.allowsBackForwardNavigationGestures = true
    }

    override func viewSafeAreaInsetsDidChange() {
        super.viewSafeAreaInsetsDidChange()
        publishSafeAreaInsets()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        publishSafeAreaInsets()
    }

    /// Publishes the real safe-area insets as CSS custom properties.
    ///
    /// `env(safe-area-inset-*)` resolves to 0 in this webview — measured under
    /// both `contentInset: 'never'` (full-bleed, so content lands under the
    /// status bar) and `'automatic'` (letterboxed). Either way the page cannot
    /// see the insets, so a frontend that lays out against `env()` — as the
    /// PWA correctly does — has nothing to work with. The shell is the only
    /// thing that knows the real numbers, so it hands them over.
    ///
    /// Consume them with `env()` still as the fallback, so the same CSS keeps
    /// working in the browser and as an installed PWA:
    ///   `padding-top: var(--hasht-safe-area-top, env(safe-area-inset-top));`
    ///
    /// Delivered two ways on purpose. `evaluateJavaScript` only reaches the
    /// document that is loaded *now*, so on its own the instance page — a
    /// separate document from the wizard — would come up bare. The user script
    /// runs at document start for every navigation, which is what actually
    /// covers the instance.
    private func publishSafeAreaInsets() {
        guard let webView = webView else { return }
        let insets = view.safeAreaInsets
        guard insets != published else { return }
        published = insets

        let source = """
        (function(){var s=document.documentElement.style;\
        s.setProperty('--hasht-safe-area-top','\(insets.top)px');\
        s.setProperty('--hasht-safe-area-right','\(insets.right)px');\
        s.setProperty('--hasht-safe-area-bottom','\(insets.bottom)px');\
        s.setProperty('--hasht-safe-area-left','\(insets.left)px');})();
        """

        webView.evaluateJavaScript(source)
        reinstallInsetScript(source, in: webView.configuration.userContentController)
    }

    /// `WKUserContentController` can only remove *all* user scripts, and
    /// Capacitor's bridge is installed as user scripts too — so keep everyone
    /// else's and swap only our own.
    private func reinstallInsetScript(_ source: String, in controller: WKUserContentController) {
        let others = controller.userScripts.filter { $0 !== insetScript }
        controller.removeAllUserScripts()
        others.forEach { controller.addUserScript($0) }

        let script = WKUserScript(
            source: source, injectionTime: .atDocumentStart, forMainFrameOnly: true)
        controller.addUserScript(script)
        insetScript = script
    }
}
