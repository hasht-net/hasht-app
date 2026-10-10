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
            if let tag = message.tag { content.threadIdentifier = tag }
            content.userInfo = message.userInfo
        }
        contentHandler(content)
    }

    override func serviceExtensionTimeWillExpire() {
        if let contentHandler = contentHandler, let bestAttempt = bestAttempt {
            contentHandler(bestAttempt)
        }
    }
}
