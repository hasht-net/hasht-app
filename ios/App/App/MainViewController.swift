import Capacitor
import UIKit

/// Capacitor only auto-discovers plugins from packages, so anything defined in
/// the app target has to be handed to the bridge explicitly.
///
/// Also owns the way *back* to the instance wizard. The remote frontend will
/// eventually offer "switch server" itself (see `integration/hashtNative.ts`),
/// but until it does — and whenever the user picked the wrong instance and
/// never got as far as signing in — the shell has to provide an escape of its
/// own, or a wrong choice is a dead end.
///
/// It stays visible rather than auto-hiding: an escape the user cannot see is
/// not an escape. The frontend hides it via `setSwitcherVisible(false)` once it
/// offers switching itself.
class MainViewController: CAPBridgeViewController {
    private let serverConfig = ServerConfigPlugin()
    private var switchButton: UIButton?

    override open func capacitorDidLoad() {
        bridge?.registerPluginInstance(serverConfig)
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        // A left-edge swipe walks back to the wizard, which is still in the
        // webview's history after the instance is loaded into it.
        webView?.allowsBackForwardNavigationGestures = true
        installSwitchAffordance()
        NotificationCenter.default.addObserver(
            self, selector: #selector(switcherVisibilityChanged(_:)),
            name: ServerConfigPlugin.switcherVisibility, object: nil)
    }


    private func installSwitchAffordance() {
        var config = UIButton.Configuration.filled()
        config.title = "Servers"
        config.image = UIImage(systemName: "chevron.backward")
        config.imagePadding = 4
        config.cornerStyle = .capsule
        config.baseBackgroundColor = UIColor.black.withAlphaComponent(0.55)
        config.baseForegroundColor = .white
        config.contentInsets = .init(top: 6, leading: 10, bottom: 6, trailing: 12)

        let button = UIButton(configuration: config)
        button.titleLabel?.font = .systemFont(ofSize: 13, weight: .semibold)
        button.translatesAutoresizingMaskIntoConstraints = false
        button.addTarget(self, action: #selector(switchServers), for: .touchUpInside)
        view.addSubview(button)

        let guide = view.safeAreaLayoutGuide
        NSLayoutConstraint.activate([
            button.leadingAnchor.constraint(equalTo: guide.leadingAnchor, constant: 8),
            button.topAnchor.constraint(equalTo: guide.topAnchor, constant: 4)
        ])
        view.bringSubviewToFront(button)
        switchButton = button
    }

    @objc private func switcherVisibilityChanged(_ note: Notification) {
        let visible = note.userInfo?["visible"] as? Bool ?? true
        DispatchQueue.main.async { [weak self] in
            self?.switchButton?.isHidden = !visible
        }
    }

    @objc private func switchServers() {
        switchButton?.isHidden = true
        serverConfig.returnToWizard()
    }
}
