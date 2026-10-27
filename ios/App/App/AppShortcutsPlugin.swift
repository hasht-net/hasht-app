import Capacitor
import UIKit

extension Notification.Name {
    /// A home-screen quick action was chosen while the app was running.
    static let hashtShortcutItem = Notification.Name("net.hasht.app.shortcutItem")
}

/// The app icon's long-press menu: the conversations the page last reported,
/// and "Snooze Notifications" when the page offers it.
///
/// The list belongs to the entry that set it, so tapping one switches to that
/// entry first. The page collects the conversation with `take()`, the same
/// way it collects a share.
@objc(AppShortcutsPlugin)
public class AppShortcutsPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "AppShortcutsPlugin"
    public let jsName = "AppShortcuts"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "set", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "take", returnType: CAPPluginReturnPromise)
    ]

    static let conversationType = "net.hasht.app.conversation"
    static let snoozeType = "net.hasht.app.snooze"
    private static let storeKey = "net.hasht.app.shortcuts"
    /// iOS shows four at most.
    private static let limit = 4

    /// A cold launch's item, from `SceneDelegate` before plugins exist.
    static var launchItem: UIApplicationShortcutItem?

    /// A nil conversation is the snooze action.
    private var pending: (entry: String, conversation: String?)?

    private var serverConfig: ServerConfigPlugin? {
        bridge?.plugin(withName: "ServerConfig") as? ServerConfigPlugin
    }

    private struct Stored: Codable {
        let entry: String
        let conversations: [Conversation]
        var snooze: Bool? = nil
    }

    private struct Conversation: Codable {
        let id: String
        let title: String
        let subtitle: String?
        let kind: String
    }

    override public func load() {
        NotificationCenter.default.addObserver(self, selector: #selector(itemChosen(_:)), name: .hashtShortcutItem, object: nil)
        refresh()
        if let item = Self.launchItem {
            Self.launchItem = nil
            handle(item)
        }
    }

    @objc func set(_ call: CAPPluginCall) {
        guard let entry = serverConfig?.activeServer?.id else {
            call.resolve(); return
        }
        let conversations = (call.getArray("conversations", JSObject.self) ?? []).compactMap { raw -> Conversation? in
            guard let id = raw["id"] as? String, !id.isEmpty, let title = raw["title"] as? String, !title.isEmpty
            else { return nil }
            return Conversation(id: id, title: title, subtitle: raw["subtitle"] as? String, kind: raw["kind"] as? String ?? "channel")
        }
        let stored = Stored(entry: entry, conversations: conversations, snooze: call.getBool("snooze") ?? false)
        UserDefaults.standard.set(try? JSONEncoder().encode(stored), forKey: Self.storeKey)
        refresh()
        call.resolve()
    }

    @objc func take(_ call: CAPPluginCall) {
        guard let pending, pending.entry == serverConfig?.activeServer?.id else {
            call.resolve(["conversationId": NSNull(), "action": NSNull()]); return
        }
        self.pending = nil
        if let conversation = pending.conversation {
            call.resolve(["conversationId": conversation, "action": NSNull()])
        } else {
            call.resolve(["conversationId": NSNull(), "action": "snooze"])
        }
    }

    /// Signed out of or removed: its conversations must not stay on the home screen.
    func forget(entry: String) {
        if stored?.entry == entry { UserDefaults.standard.removeObject(forKey: Self.storeKey) }
        refresh()
    }

    private var stored: Stored? {
        UserDefaults.standard.data(forKey: Self.storeKey).flatMap { try? JSONDecoder().decode(Stored.self, from: $0) }
    }

    func refresh() {
        var items: [UIApplicationShortcutItem] = []
        let current = stored.flatMap { serverConfig?.hasServer($0.entry) == true ? $0 : nil }
        let snooze = current?.snooze == true
        if let current {
            let room = Self.limit - (snooze ? 1 : 0)
            for conversation in current.conversations.prefix(room) {
                // The server goes in the title: the menu doesn't reliably show subtitles.
                let title = conversation.subtitle.map { "\(conversation.title) · \($0)" } ?? conversation.title
                items.append(UIApplicationShortcutItem(
                    type: Self.conversationType,
                    localizedTitle: title,
                    localizedSubtitle: nil,
                    icon: UIApplicationShortcutIcon(systemImageName: Self.symbol(conversation.kind)),
                    userInfo: ["entry": current.entry as NSString, "conversation": conversation.id as NSString]))
            }
        }
        if let current, snooze {
            items.append(UIApplicationShortcutItem(
                type: Self.snoozeType, localizedTitle: "Snooze Notifications", localizedSubtitle: nil,
                icon: UIApplicationShortcutIcon(systemImageName: "moon.zzz"),
                userInfo: ["entry": current.entry as NSString]))
        }
        DispatchQueue.main.async { UIApplication.shared.shortcutItems = items }
    }

    private static func symbol(_ kind: String) -> String {
        switch kind {
        case "dm": return "bubble.left"
        case "group": return "bubble.left.and.bubble.right"
        default: return "number"
        }
    }

    @objc private func itemChosen(_ note: Notification) {
        guard let item = note.object as? UIApplicationShortcutItem else { return }
        handle(item)
    }

    private func handle(_ item: UIApplicationShortcutItem) {
        guard let servers = serverConfig else { return }
        guard item.type == Self.conversationType || item.type == Self.snoozeType,
              let entry = item.userInfo?["entry"] as? String,
              servers.hasServer(entry) else { return }
        let conversation = item.userInfo?["conversation"] as? String
        if item.type == Self.conversationType, conversation == nil { return }
        pending = (entry, conversation)
        if entry == servers.activeServer?.id {
            // Retained: a page still loading gets it once it listens.
            notifyListeners("shortcutOpened", data: [:], retainUntilConsumed: true)
        } else {
            servers.activate(entry)
        }
    }
}
