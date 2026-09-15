import Capacitor
import UIKit

/// Capacitor only auto-discovers plugins from packages, so anything defined in
/// the app target has to be handed to the bridge explicitly.
class MainViewController: CAPBridgeViewController {
    private let serverConfig = ServerConfigPlugin()

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
    private func publishSafeAreaInsets() {
        let insets = view.safeAreaInsets
        guard insets != lastPublished else { return }
        lastPublished = insets

        let js = """
        (function(){var s=document.documentElement.style;\
        s.setProperty('--hasht-safe-area-top','\(insets.top)px');\
        s.setProperty('--hasht-safe-area-right','\(insets.right)px');\
        s.setProperty('--hasht-safe-area-bottom','\(insets.bottom)px');\
        s.setProperty('--hasht-safe-area-left','\(insets.left)px');})();
        """
        webView?.evaluateJavaScript(js)
    }

    private var lastPublished: UIEdgeInsets = .init(top: -1, left: -1, bottom: -1, right: -1)
}
