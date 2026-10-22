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
    private var recoveryDelegate: RecoveryNavigationDelegate?
    private var recoveryView: UIView?
    private var insetScript: WKUserScript?
    private var published: UIEdgeInsets = .init(top: -1, left: -1, bottom: -1, right: -1)

    override open func capacitorDidLoad() {
        // Install before registering ServerConfig: its load() starts the saved server.
        if let webView = webView, let original = webView.navigationDelegate {
            let recovery = RecoveryNavigationDelegate(wrapping: original)
            recovery.showRecovery = { [weak self] message in self?.showLoadRecovery(message) }
            recovery.hideRecovery = { [weak self] in self?.hideLoadRecovery() }
            recovery.activeOrigin = { [weak self] in
                self?.serverConfig.activeServer.flatMap { URL(string: $0.url) }.map(ServerConfigPlugin.originKey)
            }
            recoveryDelegate = recovery
            webView.navigationDelegate = recovery
        }
        if let webView = webView {
            serverConfig.installAccountSession(in: webView)
            shareSessionSync.install(in: webView, serverConfig: serverConfig)
        }
        bridge?.registerPluginInstance(serverConfig)
        bridge?.registerPluginInstance(nativeAuth)
        bridge?.registerPluginInstance(nativePush)
        if let webView = webView, let original = webView.uiDelegate {
            linkDelegate = LinkUIDelegate(wrapping: original, presenter: self)
            linkDelegate?.serverConfig = serverConfig
            webView.uiDelegate = linkDelegate
        }
    }

    private func hideLoadRecovery() {
        recoveryView?.removeFromSuperview()
        recoveryView = nil
    }

    private func showLoadRecovery(_ message: String) {
        hideLoadRecovery()
        let overlay = UIView()
        overlay.backgroundColor = .systemBackground
        overlay.translatesAutoresizingMaskIntoConstraints = false
        let title = UILabel()
        title.text = "Couldn't open this server"
        title.font = .preferredFont(forTextStyle: .headline)
        title.numberOfLines = 0
        title.adjustsFontForContentSizeCategory = true
        let detail = UILabel()
        detail.text = message
        detail.font = .preferredFont(forTextStyle: .body)
        detail.numberOfLines = 0
        detail.adjustsFontForContentSizeCategory = true
        let retry = UIButton(type: .system)
        retry.setTitle("Retry", for: .normal)
        retry.addTarget(self, action: #selector(retryServerLoad), for: .touchUpInside)
        let servers = UIButton(type: .system)
        servers.setTitle("Servers", for: .normal)
        servers.addTarget(self, action: #selector(returnToServers), for: .touchUpInside)
        let stack = UIStackView(arrangedSubviews: [title, detail, retry, servers])
        stack.axis = .vertical
        stack.spacing = 16
        stack.translatesAutoresizingMaskIntoConstraints = false
        let scroll = UIScrollView()
        scroll.translatesAutoresizingMaskIntoConstraints = false
        overlay.addSubview(scroll)
        scroll.addSubview(stack)
        view.addSubview(overlay)
        let safe = overlay.safeAreaLayoutGuide
        let centered = stack.centerYAnchor.constraint(equalTo: scroll.frameLayoutGuide.centerYAnchor)
        centered.priority = .defaultLow
        NSLayoutConstraint.activate([
            overlay.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            overlay.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            overlay.topAnchor.constraint(equalTo: view.topAnchor),
            overlay.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            scroll.leadingAnchor.constraint(equalTo: safe.leadingAnchor),
            scroll.trailingAnchor.constraint(equalTo: safe.trailingAnchor),
            scroll.topAnchor.constraint(equalTo: safe.topAnchor),
            scroll.bottomAnchor.constraint(equalTo: safe.bottomAnchor),
            scroll.contentLayoutGuide.widthAnchor.constraint(equalTo: scroll.frameLayoutGuide.widthAnchor),
            stack.centerXAnchor.constraint(equalTo: scroll.frameLayoutGuide.centerXAnchor),
            centered,
            stack.topAnchor.constraint(greaterThanOrEqualTo: scroll.contentLayoutGuide.topAnchor, constant: 24),
            stack.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor, constant: -24),
            stack.widthAnchor.constraint(lessThanOrEqualTo: scroll.frameLayoutGuide.widthAnchor, constant: -48),
            stack.widthAnchor.constraint(lessThanOrEqualToConstant: 420),
            retry.heightAnchor.constraint(greaterThanOrEqualToConstant: 44),
            servers.heightAnchor.constraint(greaterThanOrEqualToConstant: 44),
        ])
        recoveryView = overlay
        UIAccessibility.post(notification: .screenChanged, argument: title)
    }

    @objc private func retryServerLoad() {
        guard let url = recoveryDelegate?.retryURL else { return }
        recoveryDelegate?.reset()
        hideLoadRecovery()
        webView?.stopLoading()
        webView?.load(URLRequest(url: url))
    }

    @objc private func returnToServers() {
        recoveryDelegate?.reset()
        hideLoadRecovery()
        webView?.stopLoading()
        // Keeps saved entries, per-account sessions and credentials intact.
        serverConfig.returnToWizard()
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

// WKNavigationDelegate has many optional hooks (authentication, navigation
// policy, process recovery). Forward every unimplemented selector to Capacitor.
private final class RecoveryNavigationDelegate: NSObject, WKNavigationDelegate {
    private let original: WKNavigationDelegate
    private var current: WKNavigation?
    private var watchdog: DispatchWorkItem?
    private var generation = 0
    private var failed = false
    private var requestedURL: URL?
    private(set) var retryURL: URL?
    var activeOrigin: (() -> String?)?
    var showRecovery: ((String) -> Void)?
    var hideRecovery: (() -> Void)?

    init(wrapping original: WKNavigationDelegate) { self.original = original }

    override func responds(to selector: Selector!) -> Bool {
        super.responds(to: selector) || original.responds(to: selector)
    }

    override func forwardingTarget(for selector: Selector!) -> Any? {
        original.responds(to: selector) ? original : super.forwardingTarget(for: selector)
    }

    func reset() {
        generation += 1
        watchdog?.cancel()
        watchdog = nil
        current = nil
        failed = false
    }

    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        let forwardDecision: (WKNavigationActionPolicy) -> Void = { [weak self] policy in
            // A rejected link must not replace the retry target of the current load.
            if policy == .allow, action.targetFrame?.isMainFrame == true {
                self?.requestedURL = action.request.url
            }
            decisionHandler(policy)
        }
        if original.responds(to: NSSelectorFromString("webView:decidePolicyForNavigationAction:decisionHandler:")) {
            original.webView?(webView, decidePolicyFor: action, decisionHandler: forwardDecision)
        } else {
            forwardDecision(.allow)
        }
    }

    func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
        original.webView?(webView, didStartProvisionalNavigation: navigation)
        reset()
        hideRecovery?()
        current = navigation
        // WKWebView.url can still name the bundled picker at this point.
        retryURL = requestedURL ?? webView.url
        requestedURL = nil
        guard let scheme = retryURL?.scheme, ["http", "https"].contains(scheme) else { return }
        let expected = generation
        let work = DispatchWorkItem { [weak self] in
            guard let self = self, self.generation == expected else { return }
            self.showRecovery?("The server is taking too long to load. You can retry or choose another server.")
        }
        watchdog = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 12, execute: work)
    }

    func webView(_ webView: WKWebView, didReceiveServerRedirectForProvisionalNavigation navigation: WKNavigation!) {
        original.webView?(webView, didReceiveServerRedirectForProvisionalNavigation: navigation)
        guard let navigation = navigation, navigation === current else { return }
        retryURL = webView.url ?? retryURL
    }

    private func completed() {
        generation += 1
        watchdog?.cancel()
        hideRecovery?()
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        original.webView?(webView, didFinish: navigation)
        guard let navigation = navigation, navigation === current, !failed else { return }
        guard let url = webView.url, ServerConfigPlugin.originKey(url) == activeOrigin?() else {
            // External SSO pages and the bundled picker need no Hasht root.
            completed()
            return
        }
        checkAppReady(webView, expected: generation)
    }

    private func checkAppReady(_ webView: WKWebView, expected: Int, attemptsLeft: Int = 24) {
        webView.evaluateJavaScript("!!document.querySelector('#app')?.childElementCount") { [weak self, weak webView] value, _ in
            guard let self = self, let webView = webView,
                  self.generation == expected, !self.failed else { return }
            if value as? Bool == true {
                self.completed()
            } else if attemptsLeft > 0 {
                // Keep the original 12-second deadline even for a blank HTTP 200.
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self, weak webView] in
                    guard let self = self, let webView = webView, self.generation == expected else { return }
                    self.checkAppReady(webView, expected: expected, attemptsLeft: attemptsLeft - 1)
                }
            }
        }
    }

    func webView(_ webView: WKWebView, decidePolicyFor response: WKNavigationResponse,
                 decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void) {
        // A response belonging to a load abandoned for the picker (or a new
        // document) must not put recovery back over the user's new screen.
        let currentResponse = current != nil && activeOrigin?() != nil
            && (response.response.url == retryURL || response.response.url == webView.url)
        if response.isForMainFrame && currentResponse { retryURL = response.response.url ?? retryURL }
        if response.isForMainFrame, currentResponse, let http = response.response as? HTTPURLResponse,
           http.statusCode >= 400 {
            retryURL = http.url
            failed = true
            watchdog?.cancel()
            showRecovery?("The server returned HTTP \(http.statusCode). Try again or choose another server.")
            decisionHandler(.cancel)
            return
        }
        if original.responds(to: NSSelectorFromString("webView:decidePolicyForNavigationResponse:decisionHandler:")) {
            original.webView?(webView, decidePolicyFor: response, decisionHandler: decisionHandler)
        } else {
            decisionHandler(.allow)
        }
    }

    private func handleFailure(_ webView: WKWebView, navigation: WKNavigation?, error: Error) {
        // Cancelling an HTTP error response can itself produce a WebKit failure.
        // Keep the specific HTTP explanation already shown to the user.
        guard !failed, let navigation = navigation, navigation === current else { return }
        let error = error as NSError
        guard !(error.domain == NSURLErrorDomain && error.code == NSURLErrorCancelled) else { return }
        guard retryURL?.scheme == "https" || retryURL?.scheme == "http" else { return }
        failed = true
        watchdog?.cancel()
        showRecovery?("Couldn't connect to this server. Check your connection, then retry or choose another server.")
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        original.webView?(webView, didFail: navigation, withError: error)
        handleFailure(webView, navigation: navigation, error: error)
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        original.webView?(webView, didFailProvisionalNavigation: navigation, withError: error)
        handleFailure(webView, navigation: navigation, error: error)
    }
}
