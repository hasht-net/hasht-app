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
}
