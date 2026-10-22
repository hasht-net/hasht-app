import Foundation

var failures = 0
func check(_ condition: Bool, _ message: String) {
    if !condition {
        failures += 1
        print("FAIL: \(message)")
    }
}

let hosted = "https://chat.hasht.net"
let message = "/channels/0b6f3c1e-8a2d-4c7e-9f10-2a3b4c5d6e7f/9f102a3b-4c5d-6e7f-0b6f-3c1e8a2d4c7e"
func target(_ raw: String) -> DeepLinks.Target? { URL(string: raw).flatMap(DeepLinks.target) }

// Allowlisted paths only; query and fragment never survive.
check(target(hosted + "/invite/abc123")?.url.absoluteString == hosted + "/invite/abc123", "invite kept")
check(target(hosted + message + "?x=1#y")?.url.absoluteString == hosted + message, "message kept, query dropped")
check(target(hosted + "/invite/abc?native_auth=1")?.url.absoluteString == hosted + "/invite/abc", "native_auth dropped")
for path in ["/", "/settings", "/invite/a-b", "/invite/abc/extra", "/channels/abc", "/?native_auth=x"] {
    check(target(hosted + path)?.url.absoluteString == hosted + "/", "\(path) opens the root")
}

// hasht://open
let open = target("hasht://open?url=https%3A%2F%2Fchat.example.com%3A8443%2Finvite%2FABC")
check(open?.origin == "https://chat.example.com:8443", "open origin with port")
check(open?.url.absoluteString == "https://chat.example.com:8443/invite/ABC", "open url")
check(target("HTTPS://Chat.Hasht.NET/invite/abc")?.origin == hosted, "origin lowercased")

// Rejected
for raw in [
    "http://chat.hasht.net/invite/abc",
    "javascript:alert(1)",
    "hasht://open?url=http%3A%2F%2Fchat.hasht.net%2Finvite%2Fabc",
    "hasht://open?url=javascript%3Aalert(1)",
    "hasht://open",
    "hasht://auth?request_id=1&code=2",
    "hasht://signin?server=https%3A%2F%2Fchat.hasht.net",
    "https://user:pass@chat.hasht.net/invite/abc",
] {
    check(target(raw) == nil, "rejects \(raw)")
}

// Rules
let invite = target(hosted + "/invite/a")!
let selfHosted = target("https://chat.example.com/invite/a")!
check(DeepLinks.decide(invite, entries: [], defaultId: nil) == .addHosted, "no entry: hosted added silently")
check(DeepLinks.decide(selfHosted, entries: [], defaultId: nil) == .confirmAdd, "no entry: self-hosted confirms")
check(DeepLinks.decide(invite, entries: [.init(id: "s", url: "https://chat.example.com")], defaultId: "s") == .addHosted,
      "self-hosted entry doesn't count for a hosted link")
check(DeepLinks.decide(invite, entries: [.init(id: "s", url: "https://chat.example.com"), .init(id: "h", url: hosted + "/")],
                       defaultId: nil) == .open("h"), "one match opens there")
let several: [DeepLinks.Entry] = [.init(id: "a", url: hosted), .init(id: "s", url: "https://chat.example.com"), .init(id: "b", url: hosted + "/")]
check(DeepLinks.decide(invite, entries: several, defaultId: nil) == .choose(["a", "b"]), "several: ask")
check(DeepLinks.decide(invite, entries: several, defaultId: "b") == .open("b"), "several: default")
check(DeepLinks.decide(invite, entries: several, defaultId: "s") == .choose(["a", "b"]), "default elsewhere: ask")

check(DeepLinks.isRoutedPath("/invite/abc") && DeepLinks.isRoutedPath(message) && !DeepLinks.isRoutedPath("/"), "routed paths")

if failures > 0 {
    print("\(failures) failed")
    exit(1)
}
print("deeplinks: all passed")
