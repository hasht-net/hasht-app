import Capacitor
import UIKit
import WebKit

/// Capacitor only auto-discovers plugins from packages, so anything defined in
/// the app target has to be handed to the bridge explicitly.
class MainViewController: CAPBridgeViewController {

    /// Set by ServerConfig.setTheme: the page's own theme, which may be pinned
    /// and so disagree with the system's.
    private var statusBarDark = false

    override var preferredStatusBarStyle: UIStatusBarStyle {
        statusBarDark ? .lightContent : .darkContent
    }

    func setStatusBarDark(_ dark: Bool) {
        guard dark != statusBarDark else { return }
        statusBarDark = dark
        setNeedsStatusBarAppearanceUpdate()
    }
    private let serverConfig = ServerConfigPlugin()
    private let nativeAuth = NativeAuthPlugin()
    private let nativePush = NativePushPlugin()
    private let shareSessionSync = ShareSessionSync()
    // WKWebView holds its delegates weakly.
    private var linkDelegate: LinkUIDelegate?
    private var insetScript: WKUserScript?
    private var published: UIEdgeInsets = .init(top: -1, left: -1, bottom: -1, right: -1)

    override open func capacitorDidLoad() {
        if let webView = webView {
            serverConfig.installAccountSession(in: webView)
            shareSessionSync.install(in: webView, serverConfig: serverConfig)
        }
        bridge?.registerPluginInstance(serverConfig)
        bridge?.registerPluginInstance(nativeAuth)
        bridge?.registerPluginInstance(nativePush)
        if let webView = webView, let original = webView.uiDelegate {
            linkDelegate = LinkUIDelegate(wrapping: original, presenter: self)
            webView.uiDelegate = linkDelegate
        }
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        observeKeyboard()
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

// MARK: - Keyboard

/// Publishes the keyboard's height as `--hasht-keyboard-inset` and fires
/// `hasht:keyboard` so the page can react.
///
/// The page cannot work this out for itself here. Capacitor is configured
/// `resize: 'none'` — the frontend owns its own insets — so WKWebView never
/// shrinks `visualViewport`, and the frontend's "did the viewport get shorter"
/// heuristic never fires. Its fallback of "an editable has focus" is wrong in
/// the other direction: with a hardware keyboard nothing covers the screen,
/// but the composer still collapses its safe-area padding and drops.
///
/// UIKit knows the real answer, including the zero-height case, so it says so.
extension MainViewController {
    func observeKeyboard() {
        let center = NotificationCenter.default
        for name in [UIResponder.keyboardWillChangeFrameNotification,
                     UIResponder.keyboardWillShowNotification] {
            center.addObserver(
                self, selector: #selector(keyboardFrameChanged(_:)), name: name, object: nil)
        }
        center.addObserver(
            self, selector: #selector(keyboardHidden), name: UIResponder.keyboardWillHideNotification,
            object: nil)
    }

    @objc private func keyboardFrameChanged(_ note: Notification) {
        guard let frame = note.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect
        else { return }
        // Only the part that actually overlaps the web view counts: a hardware
        // keyboard parks the frame off-screen, which must read as zero.
        let overlap = view.bounds.intersection(view.convert(frame, from: nil)).height
        publishKeyboardInset(max(0, overlap.isFinite ? overlap : 0))
    }

    @objc private func keyboardHidden() {
        publishKeyboardInset(0)
    }

    private func publishKeyboardInset(_ inset: CGFloat) {
        let js = """
        (function(){document.documentElement.style.setProperty(\
        '--hasht-keyboard-inset','\(inset)px');\
        window.dispatchEvent(new CustomEvent('hasht:keyboard',{detail:{inset:\(inset)}}));})();
        """
        webView?.evaluateJavaScript(js)
    }
}
