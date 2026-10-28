// RFC 8291 Appendix A against ios/App/Shared/WebPush.swift. Run with
// scripts/test-webpush-ios.sh; the NSE has no XCTest target, and this needs
// nothing but CryptoKit.
import CryptoKit
import Foundation

func check(_ ok: Bool, _ what: String) {
    if !ok { print("FAIL: \(what)"); exit(1) }
}

let uaPrivate = Data(base64URL: "q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94")!
let uaPublic = Data(base64URL: "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4")!
let auth = Data(base64URL: "BTBZMqHH6r4Tts7J_aSIgg")!
let message = "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN"

let key = try! P256.KeyAgreement.PrivateKey(rawRepresentation: uaPrivate)
check(key.publicKey.x963Representation == uaPublic, "public key from private")

let plain = try! WebPushCrypto.decrypt(Data(base64URL: message)!, privateKey: key, auth: auth)
check(String(decoding: plain, as: UTF8.self) == "When I grow up, I want to be a watermelon", "plaintext")

// The wrong key must fail, not garble: it is how the sender is identified.
check((try? WebPushCrypto.decrypt(Data(base64URL: message)!, privateKey: P256.KeyAgreement.PrivateKey(), auth: auth)) == nil, "wrong key rejected")

// The vector's plaintext isn't JSON, so `open` skips it; a registration list
// with only a wrong key finds nothing.
let other = PushRegistration(origin: "https://a.test", relayUrl: "", id: "", endpoint: "",
                             privateKey: P256.KeyAgreement.PrivateKey().rawRepresentation, auth: auth)
check(PushMessage.open(enc: message, registrations: [other]) == nil, "no matching key")

// Round trip through a sender-side encrypt, with two registrations: `open`
// must pick the instance whose key fits.
func encrypt(_ plain: Data, to receiver: P256.KeyAgreement.PublicKey, auth: Data) -> Data {
    let sender = P256.KeyAgreement.PrivateKey()
    let salt = Data((0..<16).map { _ in UInt8.random(in: 0...255) })
    let ecdh = (try! sender.sharedSecretFromKeyAgreement(with: receiver)).withUnsafeBytes { Data($0) }
    func hkdf(_ ikm: Data, _ salt: Data, _ info: String, _ n: Int, extra: Data = Data()) -> Data {
        HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: ikm), salt: salt,
                               info: Data(info.utf8) + extra, outputByteCount: n).withUnsafeBytes { Data($0) }
    }
    let ikm = hkdf(ecdh, auth, "WebPush: info\0", 32,
                   extra: receiver.x963Representation + sender.publicKey.x963Representation)
    let cek = hkdf(ikm, salt, "Content-Encoding: aes128gcm\0", 16)
    let nonce = hkdf(ikm, salt, "Content-Encoding: nonce\0", 12)
    let sealed = try! AES.GCM.seal(plain + Data([2]), using: SymmetricKey(data: cek),
                                   nonce: AES.GCM.Nonce(data: nonce))
    return salt + Data([0, 0, 16, 0, 65]) + sender.publicKey.x963Representation
        + sealed.ciphertext + sealed.tag
}

let a = P256.KeyAgreement.PrivateKey(), b = P256.KeyAgreement.PrivateKey()
let regs = [
    PushRegistration(origin: "https://a.test", relayUrl: "", id: "", endpoint: "", privateKey: a.rawRepresentation, auth: auth),
    PushRegistration(origin: "https://b.test", relayUrl: "", id: "", endpoint: "", privateKey: b.rawRepresentation, auth: auth),
]
let json = #"{"title":"t","body":"b","url":"/channels/1/2","tag":"channel:1","kind":"remove","ids":["2"]}"#
let enc = encrypt(Data(json.utf8), to: b.publicKey, auth: auth).base64URL
let opened = PushMessage.open(enc: enc, registrations: regs)
check(opened?.origin == "https://b.test", "picked the right instance")
check(opened?.absoluteURL == "https://b.test/channels/1/2", "absolute url")
check(opened?.kind == "remove" && opened?.ids == ["2"] && opened?.tag == "channel:1", "fields")
check(opened?.imageURL == nil, "no image unless sent")
let withImage = #"{"title":"t","url":"/x","image":"/api/attachments/1?exp=2&sig=3&variant=thumb"}"#
let pictured = PushMessage.open(enc: encrypt(Data(withImage.utf8), to: a.publicKey, auth: auth).base64URL, registrations: regs)
check(pictured?.imageURL?.absoluteString == "https://a.test/api/attachments/1?exp=2&sig=3&variant=thumb", "image on the instance")
for foreign in ["https://evil.test/x.png", "//evil.test/x.png"] {
    let json = #"{"title":"t","image":"\#(foreign)"}"#
    let opened = PushMessage.open(enc: encrypt(Data(json.utf8), to: a.publicKey, auth: auth).base64URL, registrations: regs)
    check(opened != nil && opened?.imageURL == nil, "image from another host refused: \(foreign)")
}
let legacy = encrypt(Data(#"{"title":"t","url":"/x"}"#.utf8), to: a.publicKey, auth: auth).base64URL
check(PushMessage.open(enc: legacy, registrations: regs)?.kind == "message", "kind defaults to message")

let accountA = PushRegistration(origin: "https://same.test", relayUrl: "", id: "a", endpoint: "", privateKey: a.rawRepresentation, auth: auth, accountKey: "entry-a")
let accountB = PushRegistration(origin: "https://same.test", relayUrl: "", id: "b", endpoint: "", privateKey: b.rawRepresentation, auth: auth, accountKey: "entry-b")
let duplicate = PushMessage.open(enc: enc, registrations: [accountA, accountB])
check(duplicate?.key == "entry-b", "same-origin push selects the correct account")
check(duplicate?.absoluteURL == "https://same.test/channels/1/2", "account key must not change the backend URL")
check(duplicate?.userInfo["accountKey"] as? String == "entry-b", "notification retains account routing")
var oldRegistration = try! JSONSerialization.jsonObject(with: JSONEncoder().encode(other)) as! [String: Any]
oldRegistration.removeValue(forKey: "accountKey")
let restored = try! JSONDecoder().decode(PushRegistration.self, from: JSONSerialization.data(withJSONObject: oldRegistration))
check(restored.key == restored.origin, "old registrations retain origin routing")

print("ok")
