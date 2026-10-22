import Foundation
import Security

/// Only the active instance's session is exposed to the share extension.
struct ShareSession: Codable, Equatable {
    let origin: String
    let token: String
    let username: String

    private static var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: "net.hasht.app.share-session",
         kSecAttrAccount as String: "active",
         kSecAttrAccessGroup as String: "group.net.hasht.app"]
    }

    // Removal markers outlive reinstalls, so restored preferences cannot bring
    // a forgotten account back. Metadata only; no session token is stored here.
    private static var removedQuery: [String: Any] {
        var value = query
        value[kSecAttrService as String] = "net.hasht.app.removed-accounts"
        value[kSecAttrAccount as String] = "removed"
        return value
    }

    static func removedAccounts() -> [[String: String]]? {
        var query = removedQuery
        query[kSecReturnData as String] = true
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return [] }
        guard status == errSecSuccess, let data = result as? Data else { return nil }
        return try? JSONDecoder().decode([[String: String]].self, from: data)
    }

    static func rememberRemovedAccount(_ entry: [String: String]) -> Bool {
        guard let previous = removedAccounts() else { return false }
        let entries = previous + [entry]
        guard let data = try? JSONEncoder().encode(entries) else { return false }
        let attributes: [String: Any] = [kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly]
        let status = SecItemUpdate(removedQuery as CFDictionary, attributes as CFDictionary)
        if status == errSecSuccess { return true }
        guard status == errSecItemNotFound else { return false }
        return SecItemAdd(removedQuery.merging(attributes) { _, new in new } as CFDictionary, nil) == errSecSuccess
    }

    static func load() -> ShareSession? {
        var query = query
        query[kSecReturnData as String] = true
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data else { return nil }
        return try? JSONDecoder().decode(Self.self, from: data)
    }

    static func save(_ session: ShareSession?) {
        guard session != load() else { return }
        SecItemDelete(query as CFDictionary)
        guard let session = session, let data = try? JSONEncoder().encode(session) else { return }
        var query = query
        query[kSecValueData as String] = data
        query[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        SecItemAdd(query as CFDictionary, nil)
    }
}
