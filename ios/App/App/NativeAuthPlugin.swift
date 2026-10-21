import AuthenticationServices
import Capacitor
import CryptoKit
import UIKit

/// Passkey sign-in handed off to `ASWebAuthenticationSession`.
///
/// WKWebView only runs WebAuthn for relying parties in the app's Associated
/// Domains entitlement, which is fixed at build time — so no self-hosted
/// instance can be one. The auth session is Safari's own engine, where
/// passkeys work at any origin; the instance's approve page sends a one-time
/// code back over `hasht://auth`, and the code is redeemed here for a session.
///
/// The server side is `auth::native` in the chat repo. Mirrors
/// `hasht-desktop`'s `nativeAuth.ts`: the verifier never leaves this process,
/// so a code intercepted on its way back is useless on its own.
///
/// Two ways in:
/// - `signIn()`, from the instance's own login screen.
/// - `hasht://signin?server=<origin>`, from the web app's settings on this
///   device. That link can come from any page naming any host, so it never
///   adds or switches a server until the user confirms and the sign-in
///   succeeds. The page collects the result through `takePendingAuth()` once
///   the webview has moved to that server.
@objc(NativeAuthPlugin)
public class NativeAuthPlugin: CAPPlugin, CAPBridgedPlugin, ASWebAuthenticationPresentationContextProviding {
    public let identifier = "NativeAuthPlugin"
    public let jsName = "NativeAuth"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "signIn", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "takePendingAuth", returnType: CAPPluginReturnPromise)
    ]

    private enum Failure: Error {
        case cancelled
        case message(String)
    }

    private struct Handoff {
        let requestId: String
        let matchCode: String
        let verifier: String
    }

    /// Auth for a server the webview is about to load, keyed by server id.
    private var pendingAuth: [String: [String: Any]] = [:]
    private var session: ASWebAuthenticationSession?
    private var busy = false

    private var serverConfig: ServerConfigPlugin? {
        bridge?.plugin(withName: "ServerConfig") as? ServerConfigPlugin
    }

    override public func load() {
        NotificationCenter.default.addObserver(
            self, selector: #selector(urlOpened(_:)), name: .capacitorOpenURL, object: nil)
    }

    // MARK: - From the page

    @objc func signIn(_ call: CAPPluginCall) {
        guard let active = serverConfig?.activeServer, let base = URL(string: active.url) else {
            call.reject("No server is selected.")
            return
        }
        run(base: base) { result in
            switch result {
            case .success(let auth):
                call.resolve(["auth": auth])
            case .failure(.cancelled):
                call.reject("Sign-in cancelled.", "cancelled")
            case .failure(.message(let message)):
                call.reject(message)
            }
        }
    }

    @objc func takePendingAuth(_ call: CAPPluginCall) {
        let auth = serverConfig?.activeServer.flatMap { pendingAuth.removeValue(forKey: $0.id) }
        call.resolve(["auth": auth ?? NSNull()])
    }

    // MARK: - From hasht://signin

    @objc private func urlOpened(_ notification: Notification) {
        guard let url = (notification.object as? [String: Any])?["url"] as? URL,
              url.scheme == "hasht", url.host == "signin",
              let raw = URLComponents(url: url, resolvingAgainstBaseURL: false)?
                .queryItems?.first(where: { $0.name == "server" })?.value,
              let origin = Self.httpsOrigin(raw)
        else { return }

        run(base: origin) { [weak self] result in
            switch result {
            case .success(let auth):
                self?.adopt(auth, for: origin)
            case .failure(.cancelled):
                break
            case .failure(.message(let message)):
                self?.alert(title: "Couldn't sign in", message: message)
            }
        }
    }

    /// A browser handoff creates an account without replacing a saved one.
    private func adopt(_ auth: [String: Any], for origin: URL) {
        guard let servers = serverConfig else { return }
        let finish = { (id: String) in
            DispatchQueue.main.async {
                self.pendingAuth[id] = auth
                servers.activate(id)
            }
        }
        servers.fetchName(base: origin) { name in
            DispatchQueue.main.async {
                finish(servers.addServer(url: origin.absoluteString, name: name ?? origin.host ?? "Hasht"))
            }
        }
    }

    /// `https` only, reduced to its origin. The link's source is untrusted.
    private static func httpsOrigin(_ raw: String) -> URL? {
        guard let url = URL(string: raw), url.scheme?.lowercased() == "https",
              let host = url.host, !host.isEmpty
        else { return nil }
        var parts = URLComponents()
        parts.scheme = "https"
        parts.host = host
        parts.port = url.port
        return parts.url
    }

    // MARK: - The handoff

    private func run(base: URL, completion: @escaping (Result<[String: Any], Failure>) -> Void) {
        DispatchQueue.main.async {
            guard !self.busy else {
                completion(.failure(.message("A sign-in is already in progress.")))
                return
            }
            self.busy = true
            let done: (Result<[String: Any], Failure>) -> Void = { result in
                DispatchQueue.main.async {
                    self.busy = false
                    self.session = nil
                    completion(result)
                }
            }
            self.start(base: base) { started in
                switch started {
                case .failure(let failure):
                    done(.failure(failure))
                case .success(let handoff):
                    self.confirm(host: base.host ?? base.absoluteString, code: handoff.matchCode) { go in
                        guard go else { return done(.failure(.cancelled)) }
                        self.openBrowser(base: base, handoff: handoff) { code in
                            switch code {
                            case .failure(let failure):
                                done(.failure(failure))
                            case .success(let code):
                                self.exchange(base: base, handoff: handoff, code: code, completion: done)
                            }
                        }
                    }
                }
            }
        }
    }

    private func start(base: URL, completion: @escaping (Result<Handoff, Failure>) -> Void) {
        var bytes = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else {
            completion(.failure(.message("Couldn't start sign-in.")))
            return
        }
        let verifier = Self.hex(bytes)
        let challenge = Self.hex(Array(SHA256.hash(data: Data(verifier.utf8))))

        post(base, "/api/auth/native/start", [
            "challenge": challenge,
            "device_label": "Hasht on \(UIDevice.current.model)"
        ]) { status, json in
            if status == 404 {
                completion(.failure(.message(
                    "This server hasn't enabled app sign-in. Ask the admin to turn it on, or sign in with a password.")))
                return
            }
            guard status == 200,
                  let requestId = json?["request_id"] as? String,
                  let matchCode = json?["match_code"] as? String
            else {
                completion(.failure(.message("Couldn't start sign-in (\(status)).")))
                return
            }
            completion(.success(Handoff(requestId: requestId, matchCode: matchCode, verifier: verifier)))
        }
    }

    /// The auth sheet covers the app, so the code is shown here first, and
    /// this is also the confirm a `hasht://signin` link must never skip.
    private func confirm(host: String, code: String, completion: @escaping (Bool) -> Void) {
        let spaced = code.enumerated()
            .map { $0.offset > 0 && $0.offset % 4 == 0 ? " \($0.element)" : String($0.element) }
            .joined()
        let alert = UIAlertController(
            title: "Sign in to \(host)?",
            message: "Your browser will show a code. Only approve if it matches:\n\n\(spaced)",
            preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel) { _ in completion(false) })
        alert.addAction(UIAlertAction(title: "Continue", style: .default) { _ in completion(true) })
        present(alert)
    }

    private func openBrowser(
        base: URL, handoff: Handoff, completion: @escaping (Result<String, Failure>) -> Void
    ) {
        var target = URLComponents(url: base, resolvingAgainstBaseURL: false)
        target?.path = "/"
        target?.queryItems = [URLQueryItem(name: "native_auth", value: handoff.requestId)]
        guard let url = target?.url else {
            completion(.failure(.message("Couldn't open sign-in.")))
            return
        }

        let handler: (URL?, Error?) -> Void = { callback, error in
            if let error = error as? ASWebAuthenticationSessionError, error.code == .canceledLogin {
                completion(.failure(.cancelled))
                return
            }
            // Only the request this session opened; anything else is a stray link.
            guard let callback = callback, callback.host == "auth",
                  let items = URLComponents(url: callback, resolvingAgainstBaseURL: false)?.queryItems,
                  items.first(where: { $0.name == "request_id" })?.value == handoff.requestId,
                  let code = items.first(where: { $0.name == "code" })?.value
            else {
                completion(.failure(.message("That sign-in didn't complete. Try again.")))
                return
            }
            completion(.success(code))
        }

        let session: ASWebAuthenticationSession
        if #available(iOS 17.4, *) {
            session = ASWebAuthenticationSession(
                url: url, callback: .customScheme("hasht"), completionHandler: handler)
        } else {
            session = ASWebAuthenticationSession(
                url: url, callbackURLScheme: "hasht", completionHandler: handler)
        }
        session.presentationContextProvider = self
        // Not ephemeral: sharing Safari's data is what lets a user already
        // signed in there just tap Authorize.
        session.prefersEphemeralWebBrowserSession = false
        self.session = session
        if !session.start() {
            completion(.failure(.message("Couldn't open sign-in.")))
        }
    }

    private func exchange(
        base: URL, handoff: Handoff, code: String,
        completion: @escaping (Result<[String: Any], Failure>) -> Void
    ) {
        post(base, "/api/auth/native/exchange", [
            "request_id": handoff.requestId,
            "code": code,
            "verifier": handoff.verifier
        ]) { status, json in
            guard status == 200, let json = json else {
                completion(.failure(.message("That sign-in didn't work. Try again from the app.")))
                return
            }
            completion(.success(json))
        }
    }

    // MARK: - Plumbing

    public func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        bridge?.viewController?.view.window ?? ASPresentationAnchor()
    }

    private func present(_ controller: UIViewController) {
        DispatchQueue.main.async {
            var top = self.bridge?.viewController
            while let next = top?.presentedViewController { top = next }
            top?.present(controller, animated: true)
        }
    }

    private func alert(title: String, message: String) {
        let alert = UIAlertController(title: title, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "OK", style: .default))
        present(alert)
    }

    private func post(
        _ base: URL, _ path: String, _ body: [String: String],
        completion: @escaping (Int, [String: Any]?) -> Void
    ) {
        guard let url = URL(string: path, relativeTo: base) else {
            completion(0, nil)
            return
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 15
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try? JSONSerialization.data(withJSONObject: body)
        URLSession.shared.dataTask(with: request) { data, response, _ in
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            let json = data.flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] }
            completion(status, json)
        }.resume()
    }

    private static func hex(_ bytes: [UInt8]) -> String {
        bytes.map { String(format: "%02x", $0) }.joined()
    }
}
