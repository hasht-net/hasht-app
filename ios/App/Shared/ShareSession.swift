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
