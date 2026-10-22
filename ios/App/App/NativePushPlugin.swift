import Capacitor
import CryptoKit
import Foundation
import UIKit
import UserNotifications

/// Native push through the Hasht push relay (`Hasht-net/hasht-push-relay`).
///
/// The page calls `register`; this gets an APNs token, creates a relay
/// registration and a P-256 keypair for the page's instance, and returns the
/// triple the instance's `/api/push/subscribe` takes. From then on the
/// instance sends ordinary Web Push to the relay, the relay hands ciphertext
/// to APNs, and the Notification Service Extension decrypts it on the device.
///
/// Replaces `@capacitor/push-notifications`, which can't share the keys with
/// an extension and would compete for the notification-center delegate.
@objc(NativePushPlugin)
public class NativePushPlugin: CAPPlugin, CAPBridgedPlugin, NotificationHandlerProtocol {
    public let identifier = "NativePushPlugin"
    public let jsName = "NativePush"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "register", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "unregister", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "reconcileReads", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "clearChannel", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "openSettings", returnType: CAPPluginReturnPromise)
    ]

    private static let tokenKey = "net.hasht.app.push.apnsToken"

    /// Debug builds are signed with the development `aps-environment` and get
    /// sandbox tokens, which the production gateway rejects.
    #if DEBUG
    private static let environment = "sandbox"
    #else
    private static let environment = "production"
    #endif

    /// Pending `apnsToken()` calls; touched on the main queue only.
    private var tokenWaiters: [UUID: (Result<String, Error>) -> Void] = [:]

    private var serverConfig: ServerConfigPlugin? {
        bridge?.plugin(withName: "ServerConfig") as? ServerConfigPlugin
    }

    override public func load() {
        bridge?.notificationRouter.pushNotificationHandler = self
        let center = NotificationCenter.default
        center.addObserver(self, selector: #selector(didRegister(_:)),
                           name: .capacitorDidRegisterForRemoteNotifications, object: nil)
        center.addObserver(self, selector: #selector(didFailToRegister(_:)),
                           name: .capacitorDidFailToRegisterForRemoteNotifications, object: nil)
        // APNs may rotate the token between launches; asking every launch is
        // how a new one reaches `didRegister`, which forwards it to the relay.
        if !PushKeychain.load().isEmpty {
            DispatchQueue.main.async { UIApplication.shared.registerForRemoteNotifications() }
        }
    }

    // MARK: - From the page

    @objc func openSettings(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            let urlString: String
            if #available(iOS 16.0, *) {
                urlString = UIApplication.openNotificationSettingsURLString
            } else {
                urlString = UIApplication.openSettingsURLString
            }
            guard let url = URL(string: urlString) else {
                call.reject("Couldn't open notification settings.")
                return
            }
            UIApplication.shared.open(url, options: [:]) { opened in
                if opened { call.resolve() }
                else { call.reject("Couldn't open notification settings.") }
            }
        }
    }

    @objc func register(_ call: CAPPluginCall) {
        guard let relay = call.getString("relayUrl"), URL(string: relay)?.scheme == "https" else {
            call.reject("relayUrl must be an https URL")
            return
        }
        Task {
            do {
                guard let account = await pageAccount() else {
                    call.reject("NativePush.register must be called from an instance's page")
                    return
                }
                let origin = account.key
                // `prompt: false` is the page re-checking on load, which must
                // never put a permission dialog in front of the user.
                let center = UNUserNotificationCenter.current()
                let granted: Bool
                if call.getBool("prompt") ?? true {
                    granted = try await center.requestAuthorization(options: [.alert, .sound, .badge])
                } else {
                    let status = await center.notificationSettings().authorizationStatus
                    granted = status == .authorized || status == .provisional
                }
                guard granted else {
                    call.reject("Notifications are turned off for Hasht.", "denied")
                    return
                }
                let token = try await apnsToken()
                let reg = try await registration(origin: origin, backendOrigin: account.origin, relay: relay, token: token)
                call.resolve([
                    "endpoint": reg.endpoint,
                    "p256dh": reg.publicKey?.base64URL ?? "",
                    "auth": reg.auth.base64URL
                ])
            } catch {
                call.reject("Couldn't turn on notifications: \(error.localizedDescription)", "relay")
            }
        }
    }

    @objc func unregister(_ call: CAPPluginCall) {
        Task {
            if let origin = await pageOrigin() {
                await unregister(origin: origin)
            }
            call.resolve()
        }
    }

    /// The page read the channel; withdraw what's still on the lock screen.
    @objc func clearChannel(_ call: CAPPluginCall) {
        guard let tag = call.getString("tag") else {
            call.reject("tag is required")
            return
        }
        Task {
            if let origin = await pageOrigin() {
                do {
                    if let through = call.getDouble("through") {
                        try PushReadState.required().record(account: origin, reads: [tag: through])
                        Self.repairDelivered(origin: origin) {}
                    } else {
                        Self.removeDelivered(origin: origin, tag: tag, ids: []) {}
                    }
                } catch { call.reject("Could not persist notification read state"); return }
            }
            call.resolve()
        }
    }

    /// Repair missed silent pushes using an authenticated read snapshot.
    @objc func reconcileReads(_ call: CAPPluginCall) {
        let reads = call.getArray("reads", JSObject.self) ?? []
        Task {
            guard let origin = await pageOrigin() else { call.resolve(); return }
            var boundaries: [String: Double] = [:]
            for read in reads {
                if let tag = read["tag"] as? String, let through = read["through"] as? Double {
                    boundaries[tag] = max(boundaries[tag] ?? 0, through)
                }
            }
            do { try PushReadState.required().record(account: origin, reads: boundaries) }
            catch { call.reject("Could not persist notification read state"); return }
            Self.repairDelivered(origin: origin) {}
            call.resolve()
        }
    }

    // MARK: - From the shell

    /// Drops this instance's registration: relay row, keys and its delivered
    /// notifications. Called on sign-out as well as from the page.
    func unregister(origin: String) async {
        var regs = PushKeychain.load()
        let gone = regs.filter { $0.key == origin }
        regs.removeAll { $0.key == origin }
        PushKeychain.save(regs)
        try? PushReadState.required().forget(account: origin)
        for reg in gone {
            _ = try? await relayRequest("DELETE", "\(reg.relayUrl)/v1/register/\(reg.id)", body: nil)
        }
        Self.removeDelivered(origin: origin, tag: nil, ids: []) {}
    }

    /// A background read/remove control from the relay, which
    /// withdraws a notification the user dealt with elsewhere. iOS throttles
    /// these and never delivers them to a force-quit app, so it's best effort.
    static func handleBackgroundPush(
        _ userInfo: [AnyHashable: Any], completion: @escaping (UIBackgroundFetchResult) -> Void
    ) {
        guard let enc = userInfo["enc"] as? String,
              let message = PushMessage.open(enc: enc, registrations: PushKeychain.load()),
              ["remove", "read"].contains(message.kind)
        else {
            completion(.noData)
            return
        }
        do {
            if message.kind == "read", let tag = message.tag, let through = message.readThrough {
                try PushReadState.required().record(account: message.key, reads: [tag: through])
                repairDelivered(origin: message.key) { completion(.newData) }
            } else if message.kind == "remove" {
                try PushReadState.required().record(account: message.key, removed: message.ids)
                removeDelivered(origin: message.key, tag: message.tag, ids: message.ids) { completion(.newData) }
            } else { completion(.noData) }
        } catch { completion(.failed) }
    }

    static func covered(_ info: [AnyHashable: Any]) -> Bool {
        guard let account = info["accountKey"] as? String ?? info["origin"] as? String else { return false }
        return (try? PushReadState.shared?.suppresses(account: account, tag: info["tag"] as? String,
            ids: info["ids"] as? [String] ?? [], created: info["created"] as? Double)) ?? false
    }

    static func repairDelivered(origin: String, completion: @escaping () -> Void) {
        let center = UNUserNotificationCenter.current()
        center.getDeliveredNotifications { delivered in
            let snapshot = try? PushReadState.shared?.snapshot(account: origin)
            let doomed = delivered.filter {
                let info = $0.request.content.userInfo
                return (info["accountKey"] as? String ?? info["origin"] as? String) == origin &&
                    (snapshot?.suppresses(tag: info["tag"] as? String, ids: info["ids"] as? [String] ?? [],
                        created: info["created"] as? Double) ?? false)
            }
            center.removeDeliveredNotifications(withIdentifiers: doomed.map { $0.request.identifier })
            completion()
        }
    }

    /// Removes delivered notifications from `origin`, narrowed to a channel
    /// `tag` and, if `ids` is non-empty, to ones about any of those messages.
    static func removeDelivered(origin: String, tag: String?, ids: [String], completion: @escaping () -> Void) {
        let center = UNUserNotificationCenter.current()
        center.getDeliveredNotifications { delivered in
            let doomed = delivered.filter { note in
                let info = note.request.content.userInfo
                guard (info["accountKey"] as? String ?? info["origin"] as? String) == origin else { return false }
                if let tag = tag {
                    let shownTag = info["tag"] as? String ?? ""
                    let channelTag = tag.components(separatedBy: "/message:").first ?? tag
                    let shownChannel = shownTag.components(separatedBy: "/message:").first ?? shownTag
                    if channelTag != shownChannel { return false }
                }
                if ids.isEmpty { return true }
                return !Set(info["ids"] as? [String] ?? []).isDisjoint(with: ids)
            }
            center.removeDeliveredNotifications(withIdentifiers: doomed.map { $0.request.identifier })
            completion()
        }
    }

    // MARK: - NotificationHandlerProtocol

    /// Mirrors the web service worker: no banner for the instance on screen,
    /// whose page already shows the message. Another instance's still shows.
    public func willPresent(notification: UNNotification) -> UNNotificationPresentationOptions {
        let info = notification.request.content.userInfo
        if Self.covered(info) { return [] }
        let origin = info["accountKey"] as? String ?? info["origin"] as? String
        if UIApplication.shared.applicationState == .active, origin != nil, origin == activeOrigin() {
            return []
        }
        if info["kind"] as? String == "catch_up" { return [.list] }
        return [.banner, .list, .sound]
    }

    public func didReceive(response: UNNotificationResponse) {
        let info = response.notification.request.content.userInfo
        guard let origin = info["accountKey"] as? String ?? info["origin"] as? String,
              let url = info["url"] as? String
        else { return }

        if origin == activeOrigin() {
            // Retained, so a tap that launched the app still reaches the page
            // once it has loaded and added its listener.
            let backend = info["origin"] as? String ?? origin
            let path = url.hasPrefix(backend) ? String(url.dropFirst(backend.count)) : url
            notifyListeners("notificationTapped", data: ["url": path.isEmpty ? "/" : path],
                            retainUntilConsumed: true)
        } else if let servers = serverConfig,
                  let id = servers.serverId(forPushKey: origin) {
            // Another instance: switch to it, straight to the message.
            servers.activate(id, loading: url)
        }
    }

    // MARK: - Internals

    private func activeOrigin() -> String? { serverConfig?.activePushKey }

    @MainActor
    private func pageAccount() -> (key: String, origin: String)? {
        guard let url = bridge?.webView?.url, ["https", "http"].contains(url.scheme),
              let active = serverConfig?.activeServer, let base = URL(string: active.url),
              ServerConfigPlugin.originKey(url) == ServerConfigPlugin.originKey(base),
              let key = serverConfig?.activePushKey else { return nil }
        return (key, ServerConfigPlugin.originKey(base))
    }

    @MainActor
    private func pageOrigin() -> String? { pageAccount()?.key }

    private func apnsToken() async throws -> String {
        try await withCheckedThrowingContinuation { continuation in
            DispatchQueue.main.async {
                let key = UUID()
                self.tokenWaiters[key] = { continuation.resume(with: $0) }
                UIApplication.shared.registerForRemoteNotifications()
                DispatchQueue.main.asyncAfter(deadline: .now() + 30) {
                    self.tokenWaiters.removeValue(forKey: key)?(.failure(URLError(.timedOut)))
                }
            }
        }
    }

    @objc private func didRegister(_ note: Notification) {
        guard let data = note.object as? Data else { return }
        let token = data.map { String(format: "%02x", $0) }.joined()
        DispatchQueue.main.async {
            let waiters = self.tokenWaiters
            self.tokenWaiters.removeAll()
            waiters.values.forEach { $0(.success(token)) }

            let previous = UserDefaults.standard.string(forKey: Self.tokenKey)
            UserDefaults.standard.set(token, forKey: Self.tokenKey)
            if let previous = previous, previous != token {
                Task { await self.refreshToken(token) }
            }
        }
    }

    @objc private func didFailToRegister(_ note: Notification) {
        let error = note.object as? Error ?? URLError(.unknown)
        DispatchQueue.main.async {
            let waiters = self.tokenWaiters
            self.tokenWaiters.removeAll()
            waiters.values.forEach { $0(.failure(error)) }
        }
    }

    /// Points every relay registration at a rotated token.
    private func refreshToken(_ token: String) async {
        for reg in PushKeychain.load() {
            _ = try? await relayRequest("PUT", "\(reg.relayUrl)/v1/register/\(reg.id)", body: registerBody(token))
        }
    }

    /// The registration for `origin`, created if needed. An existing one is
    /// refreshed with the current token; if the relay has forgotten it (pruned,
    /// or its token died) a new one replaces it, and the page re-subscribes
    /// with the new endpoint.
    private func registration(origin: String, backendOrigin: String, relay: String, token: String) async throws -> PushRegistration {
        let relay = relay.hasSuffix("/") ? String(relay.dropLast()) : relay
        var regs = PushKeychain.load()

        if let existing = regs.first(where: { $0.key == origin }) {
            if existing.relayUrl == relay {
                let (status, _) = try await relayRequest(
                    "PUT", "\(relay)/v1/register/\(existing.id)", body: registerBody(token))
                if status == 204 { return existing }
                // Only a missing row justifies replacing its endpoint and keys.
                guard status == 404 else { throw URLError(.badServerResponse) }
            } else {
                _ = try? await relayRequest("DELETE", "\(existing.relayUrl)/v1/register/\(existing.id)", body: nil)
            }
            regs.removeAll { $0.key == origin }
        }

        let (status, json) = try await relayRequest("POST", "\(relay)/v1/register", body: registerBody(token))
        guard status == 201, let id = json?["id"] as? String, let endpoint = json?["endpoint"] as? String else {
            throw URLError(.badServerResponse)
        }
        var auth = Data(count: 16)
        let ok = auth.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 16, $0.baseAddress!) }
        guard ok == errSecSuccess else { throw URLError(.unknown) }

        let reg = PushRegistration(
            origin: backendOrigin, relayUrl: relay, id: id, endpoint: endpoint,
            privateKey: P256.KeyAgreement.PrivateKey().rawRepresentation, auth: auth,
            accountKey: origin == backendOrigin ? nil : origin)
        regs = PushKeychain.load()
        regs.removeAll { $0.key == origin }
        regs.append(reg)
        guard PushKeychain.save(regs) else { throw URLError(.cannotCreateFile) }
        return reg
    }

    private func registerBody(_ token: String) -> [String: Any] {
        let info = Bundle.main.infoDictionary
        let version = "\(info?["CFBundleShortVersionString"] ?? "0")+\(info?["CFBundleVersion"] ?? "0")"
        return ["platform": "ios", "token": token, "environment": Self.environment, "app_version": version]
    }

    private func relayRequest(_ method: String, _ url: String, body: [String: Any]?) async throws
        -> (Int, [String: Any]?) {
        guard let url = URL(string: url) else { throw URLError(.badURL) }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.timeoutInterval = 15
        if let body = body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONSerialization.data(withJSONObject: body)
        }
        let (data, response) = try await URLSession.shared.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        return (status, try? JSONSerialization.jsonObject(with: data) as? [String: Any])
    }
}
