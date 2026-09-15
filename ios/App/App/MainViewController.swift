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
class MainViewController: CAPBridgeViewController {
    private let serverConfig = ServerConfigPlugin()
    private var switchButton: UIButton?
    private var hideTask: DispatchWorkItem?

    override open func capacitorDidLoad() {
        bridge?.registerPluginInstance(serverConfig)
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        // A left-edge swipe walks back to the wizard, which is still in the
        // webview's history after the instance is loaded into it.
        webView?.allowsBackForwardNavigationGestures = true
        installSwitchAffordance()
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        revealSwitchButton()
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
        button.alpha = 0
        button.translatesAutoresizingMaskIntoConstraints = false
        button.addTarget(self, action: #selector(switchServers), for: .touchUpInside)
        view.addSubview(button)

        // Re-summons the button once it has faded, so the escape is always
        // reachable without leaving chrome over the chat UI.
        let hotZone = UIView()
        hotZone.translatesAutoresizingMaskIntoConstraints = false
        hotZone.addGestureRecognizer(
            UITapGestureRecognizer(target: self, action: #selector(revealSwitchButton)))
        view.addSubview(hotZone)

        let guide = view.safeAreaLayoutGuide
        NSLayoutConstraint.activate([
            button.leadingAnchor.constraint(equalTo: guide.leadingAnchor, constant: 8),
            button.topAnchor.constraint(equalTo: guide.topAnchor, constant: 4),
            hotZone.leadingAnchor.constraint(equalTo: guide.leadingAnchor),
            hotZone.topAnchor.constraint(equalTo: guide.topAnchor),
            hotZone.widthAnchor.constraint(equalToConstant: 44),
            hotZone.heightAnchor.constraint(equalToConstant: 44)
        ])
        view.bringSubviewToFront(button)
        switchButton = button
    }

    @objc private func revealSwitchButton() {
        guard let button = switchButton else { return }
        hideTask?.cancel()
        view.bringSubviewToFront(button)
        UIView.animate(withDuration: 0.2) { button.alpha = 1 }

        let task = DispatchWorkItem { [weak button] in
            UIView.animate(withDuration: 0.4) { button?.alpha = 0 }
        }
        hideTask = task
        DispatchQueue.main.asyncAfter(deadline: .now() + 5, execute: task)
    }

    @objc private func switchServers() {
        hideTask?.cancel()
        switchButton?.alpha = 0
        serverConfig.returnToWizard()
    }
}
