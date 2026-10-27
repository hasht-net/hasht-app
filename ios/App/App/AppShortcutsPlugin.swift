import Capacitor
import UIKit

extension Notification.Name {
    /// A home-screen quick action was chosen while the app was running.
    static let hashtShortcutItem = Notification.Name("net.hasht.app.shortcutItem")
}

/// The app icon's long-press menu: the conversations the page last reported,
/// plus "Switch Server" when there is more than one.
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
    static let serversType = "net.hasht.app.servers"
    private static let storeKey = "net.hasht.app.shortcuts"
    /// iOS shows four at most.
    private static let limit = 4

    /// A cold launch's item, from `SceneDelegate` before plugins exist.
    static var launchItem: UIApplicationShortcutItem?

    private var pending: (entry: String, conversation: String)?

    private var serverConfig: ServerConfigPlugin? {
        bridge?.plugin(withName: "ServerConfig") as? ServerConfigPlugin
    }

    private struct Stored: Codable {
        let entry: String
        let conversations: [Conversation]
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
        let conversations = (call.getArray("conversations") as? [[String: Any]] ?? []).compactMap { raw -> Conversation? in
            guard let id = raw["id"] as? String, !id.isEmpty, let title = raw["title"] as? String, !title.isEmpty
            else { return nil }
            return Conversation(id: id, title: title, subtitle: raw["subtitle"] as? String, kind: raw["kind"] as? String ?? "channel")
        }
        UserDefaults.standard.set(try? JSONEncoder().encode(Stored(entry: entry, conversations: conversations)), forKey: Self.storeKey)
        refresh()
        call.resolve()
    }

    @objc func take(_ call: CAPPluginCall) {
        guard let pending, pending.entry == serverConfig?.activeServer?.id else {
            call.resolve(["conversationId": NSNull()]); return
        }
        self.pending = nil
        call.resolve(["conversationId": pending.conversation])
    }

    /// Signed out of or removed: its conversations must not stay on the home screen.
    func forget(entry: String) {
        if stored?.entry == entry { UserDefaults.standard.removeObject(forKey: Self.storeKey) }
        refresh()
    }

    private var stored: Stored? {
        UserDefaults.standard.data(forKey: Self.storeKey).flatMap { try? JSONDecoder().decode(Stored.self, from: $0) }
    }

    /// Also called when the server list changes, which can add or drop "Switch Server".
    func refresh() {
        let count = serverConfig?.serverCount ?? 0
        var items: [UIApplicationShortcutItem] = []
        if let stored, serverConfig?.hasServer(stored.entry) == true {
            for conversation in stored.conversations.prefix(count > 1 ? Self.limit - 1 : Self.limit) {
                items.append(UIApplicationShortcutItem(
                    type: Self.conversationType,
                    localizedTitle: conversation.title,
                    localizedSubtitle: conversation.subtitle,
                    icon: UIApplicationShortcutIcon(systemImageName: Self.symbol(conversation.kind)),
                    userInfo: ["entry": stored.entry as NSString, "conversation": conversation.id as NSString]))
            }
        }
        if count > 1 {
            items.append(UIApplicationShortcutItem(
                type: Self.serversType, localizedTitle: "Switch Server", localizedSubtitle: nil,
                icon: UIApplicationShortcutIcon(systemImageName: "rectangle.stack"), userInfo: nil))
        }
        DispatchQueue.main.async { UIApplication.shared.shortcutItems = items }
    }

    private static func symbol(_ kind: String) -> String {
        switch kind {
        case "dm": return "person.crop.circle"
        case "group": return "person.2"
        default: return "number"
        }
    }

    @objc private func itemChosen(_ note: Notification) {
        guard let item = note.object as? UIApplicationShortcutItem else { return }
        handle(item)
    }

    private func handle(_ item: UIApplicationShortcutItem) {
        guard let servers = serverConfig else { return }
        if item.type == Self.serversType {
            servers.returnToWizard()
            return
        }
        guard item.type == Self.conversationType,
              let entry = item.userInfo?["entry"] as? String,
              let conversation = item.userInfo?["conversation"] as? String,
              servers.hasServer(entry) else { return }
        pending = (entry, conversation)
        if entry == servers.activeServer?.id {
            // Retained: a page still loading gets it once it listens.
            notifyListeners("shortcutOpened", data: [:], retainUntilConsumed: true)
        } else {
            servers.activate(entry)
        }
    }
}
