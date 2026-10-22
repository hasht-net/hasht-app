import UserNotifications

/// Decrypts each alert push and replaces the relay's placeholder text.
///
/// The relay sends `mutable-content` alerts carrying only ciphertext (`enc`);
/// Apple, Google and the relay never see the message. If anything here fails
/// or runs out of time, the placeholder ("Hasht — New message") is shown.
class NotificationService: UNNotificationServiceExtension {
    private var contentHandler: ((UNNotificationContent) -> Void)?
    private var bestAttempt: UNMutableNotificationContent?

    override func didReceive(
        _ request: UNNotificationRequest,
        withContentHandler contentHandler: @escaping (UNNotificationContent) -> Void
    ) {
        guard let content = request.content.mutableCopy() as? UNMutableNotificationContent else {
            contentHandler(request.content)
            return
        }
        self.contentHandler = contentHandler
        bestAttempt = content

        if let enc = content.userInfo["enc"] as? String,
           let message = PushMessage.open(enc: enc, registrations: PushKeychain.load()) {
            if let title = message.title { content.title = title }
            if let body = message.body { content.body = body }
            if let tag = message.tag { content.threadIdentifier = message.key + " " + (tag.components(separatedBy: "/message:").first ?? tag) }
            content.userInfo = message.userInfo
            let covered = (try? PushReadState.shared?.suppresses(account: message.key, tag: message.tag,
                ids: message.ids, created: message.created)) ?? false
            if message.kind == "catch_up" || covered {
                content.sound = nil
                if #available(iOSApplicationExtension 15.0, *) { content.interruptionLevel = .passive }
            }
            // Without Apple's filtering entitlement an NSE cannot discard its own
            // alert. Persisted reads still suppress foreground alerts and make a
            // delayed covered background alert quiet; the next repair removes it.
            let center = UNUserNotificationCenter.current()
            center.getDeliveredNotifications { delivered in
                let snapshot = try? PushReadState.shared?.snapshot(account: message.key)
                let ids = delivered.filter { note in
                    let info = note.request.content.userInfo
                    guard (info["accountKey"] as? String ?? info["origin"] as? String) == message.key else { return false }
                    return snapshot?.suppresses(tag: info["tag"] as? String,
                        ids: info["ids"] as? [String] ?? [], created: info["created"] as? Double) ?? false
                }.map { $0.request.identifier }
                center.removeDeliveredNotifications(withIdentifiers: ids)
            }
        }
        contentHandler(content)
    }

    override func serviceExtensionTimeWillExpire() {
        if let contentHandler = contentHandler, let bestAttempt = bestAttempt {
            contentHandler(bestAttempt)
        }
    }
}
