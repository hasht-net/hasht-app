import CryptoKit
import Foundation
import Security

/// Shared by the app and the Notification Service Extension: the device's
/// push registrations and the RFC 8291 decryption of what arrives.
///
/// Each instance the user enables push on gets its own relay registration and
/// its own keypair. The payload only carries a path, so the key that opens it
/// is how the device learns which instance it came from.
struct PushRegistration: Codable, Equatable {
    /// `scheme://host[:port]` of the instance.
    let origin: String
    let relayUrl: String
    let id: String
    let endpoint: String
    /// P-256 private key, raw representation.
    let privateKey: Data
    let auth: Data
    var accountKey: String? = nil
    var key: String { accountKey ?? origin }

    var publicKey: Data? {
        (try? P256.KeyAgreement.PrivateKey(rawRepresentation: privateKey))?.publicKey.x963Representation
    }
}

enum WebPushError: Error {
    case malformed
    case unsupported
}

enum WebPushCrypto {
    /// Decrypts one `aes128gcm` body (RFC 8188 framing, RFC 8291 keys).
    /// Throws on the wrong key: the AES-GCM tag won't verify.
    static func decrypt(_ body: Data, privateKey: P256.KeyAgreement.PrivateKey, auth: Data) throws -> Data {
        let bytes = [UInt8](body)
        guard bytes.count > 21 else { throw WebPushError.malformed }
        let salt = Data(bytes[0..<16])
        let rs = bytes[16..<20].reduce(0) { ($0 << 8) | Int($1) }
        let idlen = Int(bytes[20])
        guard idlen == 65, bytes.count > 21 + idlen + 16 else { throw WebPushError.malformed }
        let senderKey = Data(bytes[21..<(21 + idlen)])
        let ciphertext = Data(bytes[(21 + idlen)...])
        // Backends send one record; more would need a nonce per record.
        guard ciphertext.count <= rs else { throw WebPushError.unsupported }

        let shared = try privateKey.sharedSecretFromKeyAgreement(
            with: P256.KeyAgreement.PublicKey(x963Representation: senderKey))
        let ecdh = shared.withUnsafeBytes { Data($0) }

        let keyInfo = Data("WebPush: info\0".utf8) + privateKey.publicKey.x963Representation + senderKey
        let ikm = hkdf(ecdh, salt: auth, info: keyInfo, count: 32)
        let cek = hkdf(ikm, salt: salt, info: Data("Content-Encoding: aes128gcm\0".utf8), count: 16)
        let nonce = hkdf(ikm, salt: salt, info: Data("Content-Encoding: nonce\0".utf8), count: 12)

        let box = try AES.GCM.SealedBox(
            nonce: AES.GCM.Nonce(data: nonce),
            ciphertext: ciphertext.dropLast(16),
            tag: ciphertext.suffix(16))
        let padded = try AES.GCM.open(box, using: SymmetricKey(data: cek))

        // Padding is zeros after a 0x02 delimiter (last record).
        guard let end = padded.lastIndex(where: { $0 != 0 }), padded[end] == 2 else {
            throw WebPushError.malformed
        }
        return padded[padded.startIndex..<end]
    }

    private static func hkdf(_ ikm: Data, salt: Data, info: Data, count: Int) -> Data {
        HKDF<SHA256>.deriveKey(
            inputKeyMaterial: SymmetricKey(data: ikm), salt: salt, info: info, outputByteCount: count
        ).withUnsafeBytes { Data($0) }
    }
}

/// A decrypted push. Mirrors the backend's payload:
/// `{title, body, url, tag, icon, kind, ids}`.
struct PushMessage {
    let origin: String
    let title: String?
    let body: String?
    /// A path on `origin`.
    let path: String?
    let tag: String?
    /// `message`, `update` or `remove`. Older backends send none: a message.
    let kind: String
    let ids: [String]
    var accountKey: String? = nil
    var key: String { accountKey ?? origin }

    var absoluteURL: String? {
        path.map { $0.hasPrefix("/") ? origin + $0 : $0 }
    }

    /// Tries each registration's key against the `enc` field the relay added.
    static func open(enc: String, registrations: [PushRegistration]) -> PushMessage? {
        guard let body = Data(base64URL: enc) else { return nil }
        for reg in registrations {
            guard let key = try? P256.KeyAgreement.PrivateKey(rawRepresentation: reg.privateKey),
                  let plain = try? WebPushCrypto.decrypt(body, privateKey: key, auth: reg.auth),
                  let json = try? JSONSerialization.jsonObject(with: plain) as? [String: Any]
            else { continue }
            return PushMessage(
                origin: reg.origin,
                title: json["title"] as? String,
                body: json["body"] as? String,
                path: json["url"] as? String,
                tag: json["tag"] as? String,
                kind: json["kind"] as? String ?? "message",
                ids: (json["ids"] as? [Any])?.map { "\($0)" } ?? [], accountKey: reg.accountKey)
        }
        return nil
    }

    /// What tapping or withdrawing the notification needs later.
    var userInfo: [String: Any] {
        var info: [String: Any] = ["origin": origin, "accountKey": key, "kind": kind, "ids": ids]
        if let url = absoluteURL { info["url"] = url }
        if let tag = tag { info["tag"] = tag }
        return info
    }
}

/// Registrations live in the Keychain, in the App Group's access group, so
/// the extension can read the private keys. Readable after first unlock:
/// pushes arrive while the phone is locked.
enum PushKeychain {
    static let accessGroup = "group.net.hasht.app"
    private static let service = "net.hasht.app.push"
    private static let account = "registrations"

    private static var query: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecAttrAccessGroup as String: accessGroup,
        ]
    }

    static func load() -> [PushRegistration] {
        var q = query
        q[kSecReturnData as String] = true
        var item: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data,
              let regs = try? JSONDecoder().decode([PushRegistration].self, from: data)
        else { return [] }
        return regs
    }

    @discardableResult
    static func save(_ regs: [PushRegistration]) -> Bool {
        SecItemDelete(query as CFDictionary)
        guard !regs.isEmpty else { return true }
        guard let data = try? JSONEncoder().encode(regs) else { return false }
        var q = query
        q[kSecValueData as String] = data
        q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let status = SecItemAdd(q as CFDictionary, nil)
        if status != errSecSuccess {
            NSLog("NativePush: keychain save failed (\(status))")
        }
        return status == errSecSuccess
    }
}

extension Data {
    init?(base64URL: String) {
        var s = base64URL.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        s += String(repeating: "=", count: (4 - s.count % 4) % 4)
        self.init(base64Encoded: s)
    }

    var base64URL: String {
        base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
