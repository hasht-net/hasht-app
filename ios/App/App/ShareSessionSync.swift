import WebKit

/// Keeps credentials in the shared Keychain, never in App Group preferences.
final class ShareSessionSync: NSObject, WKScriptMessageHandler {
    weak var serverConfig: ServerConfigPlugin?
    weak var webView: WKWebView?

    func install(in webView: WKWebView, serverConfig: ServerConfigPlugin) {
        self.webView = webView
        self.serverConfig = serverConfig
        let controller = webView.configuration.userContentController
        controller.add(self, name: "hashtShareSession")
        controller.addUserScript(WKUserScript(source: """
        (() => {
          const publish = () => {
            try { window.webkit.messageHandlers.hashtShareSession.postMessage({id: window.__hashtAccountID, auth: localStorage.getItem('chat_auth') || ''}); } catch (_) {}
          };
          for (const method of ['setItem', 'removeItem', 'clear']) {
            const original = Storage.prototype[method];
            Storage.prototype[method] = function(...args) {
              const result = original.apply(this, args);
              if (this === localStorage && (method === 'clear' || args[0] === 'chat_auth')) publish();
              return result;
            };
          }
          window.addEventListener('storage', publish);
          publish();
        })();
        """, injectionTime: .atDocumentStart, forMainFrameOnly: true))
    }

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard message.frameInfo.isMainFrame,
              let active = serverConfig?.activeServer, let activeURL = URL(string: active.url),
              let current = webView?.url,
              ["https", "http"].contains(activeURL.scheme),
              ServerConfigPlugin.originKey(current) == ServerConfigPlugin.originKey(activeURL),
              let frameURL = message.frameInfo.request.url,
              ServerConfigPlugin.originKey(frameURL) == ServerConfigPlugin.originKey(activeURL),
              let body = message.body as? [String: Any], body["id"] as? String == active.id,
              let raw = body["auth"] as? String else { return }
        guard let data = raw.data(using: .utf8),
              let auth = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let token = auth["token"] as? String, !token.isEmpty,
              let username = auth["username"] as? String else {
            ShareSession.save(nil)
            return
        }
        ShareSession.save(ShareSession(origin: ServerConfigPlugin.originKey(activeURL), token: token, username: username))
    }
}
