import Foundation

/// Which saved server a link opens in. Pure (Foundation only) so the rules are
/// testable with `scripts/test-deeplinks-ios.sh`; `ServerConfigPlugin` does the
/// loading and dialogs. The Java twin is `DeepLinks.java`; keep the two in step.
///
/// Every link is untrusted: it only ever becomes an https URL on an
/// allowlisted path, never anything the page would run.
enum DeepLinks {
    /// The hosted instance: a link to it may add it without asking.
    static let hostedOrigin = "https://chat.hasht.net"

    struct Target: Equatable {
        let origin: String
        let url: URL
    }

    /// An https link (universal link, banner) or `hasht://open?url=…`, reduced
    /// to its origin plus an allowlisted path. Any other path opens the
    /// instance root. Nil for anything else.
    static func target(_ raw: URL) -> Target? {
        var link = raw
        if link.scheme?.lowercased() == "hasht" {
            guard link.host?.lowercased() == "open",
                  let inner = URLComponents(url: link, resolvingAgainstBaseURL: false)?
                    .queryItems?.first(where: { $0.name == "url" })?.value,
                  let url = URL(string: inner)
            else { return nil }
            link = url
        }
        guard link.scheme?.lowercased() == "https", let host = link.host, !host.isEmpty,
              link.user == nil, link.password == nil
        else { return nil }
        let origin = originKey(link)
        let path = URLComponents(url: link, resolvingAgainstBaseURL: false)?.percentEncodedPath ?? ""
        guard let url = URL(string: origin + (isRoutedPath(path) ? path : "/")) else { return nil }
        return Target(origin: origin, url: url)
    }

    /// Whether a link tapped inside the webview should go through the router.
    static func isRoutedPath(_ path: String) -> Bool {
        matches(path, #"^/invite/[A-Za-z0-9]{1,32}$"#)
            || matches(path, #"^/channels/[0-9a-fA-F-]{1,64}/[0-9a-fA-F-]{1,64}$"#)
    }

    struct Entry {
        let id: String
        let url: String
    }

    enum Decision: Equatable {
        case addHosted
        case confirmAdd
        case open(String)
        case choose([String])
    }

    /// Only entries on the link's own origin count: no entry adds one (silently
    /// for the hosted instance, after a confirm for any other), one entry
    /// opens there, several use the default if it's among them and otherwise
    /// ask.
    static func decide(_ target: Target, entries: [Entry], defaultId: String?) -> Decision {
        let matching = entries.filter { URL(string: $0.url).map(originKey) == target.origin }.map(\.id)
        if matching.isEmpty { return target.origin == hostedOrigin ? .addHosted : .confirmAdd }
        if matching.count == 1 { return .open(matching[0]) }
        if let defaultId, matching.contains(defaultId) { return .open(defaultId) }
        return .choose(matching)
    }

    /// Same shape as `ServerConfigPlugin.originKey`.
    static func originKey(_ url: URL) -> String {
        let port = url.port.map { ":\($0)" } ?? ""
        return "\(url.scheme ?? "")://\(url.host ?? "")\(port)".lowercased()
    }

    private static func matches(_ value: String, _ pattern: String) -> Bool {
        value.range(of: pattern, options: .regularExpression) != nil
    }
}
