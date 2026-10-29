import Foundation

/// Email and birth date from the page's Register form, carried to the
/// browser's form in the URL fragment: a fragment never reaches a server or
/// proxy log. The page parses it with URLSearchParams and then clears it.
enum SignupPrefill {
    static func email(_ value: String?) -> String? {
        guard let trimmed = value?.trimmingCharacters(in: .whitespaces),
              (3...254).contains(trimmed.count), trimmed.contains("@"),
              trimmed.rangeOfCharacter(from: .whitespacesAndNewlines) == nil,
              trimmed.rangeOfCharacter(from: .controlCharacters) == nil
        else { return nil }
        return trimmed
    }

    /// A real calendar date written YYYY-MM-DD.
    static func birthday(_ value: String?) -> String? {
        guard let value, value.range(of: "^[0-9]{4}-[0-9]{2}-[0-9]{2}$", options: .regularExpression) != nil
        else { return nil }
        let parts = value.split(separator: "-").compactMap { Int($0) }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        let components = DateComponents(year: parts[0], month: parts[1], day: parts[2])
        guard components.isValidDate(in: calendar) else { return nil }
        return value
    }

    static func visibility(_ value: String?) -> String? {
        guard let value, ["full", "month_day", "hidden"].contains(value) else { return nil }
        return value
    }

    /// Form-encoded like URLSearchParams, so `+` and `@` survive as `%2B` and
    /// `%40` rather than `+` decoding to a space. Nil when there's nothing.
    static func fragment(email: String?, birthday: String?, visibility: String?) -> String? {
        let pairs = [("email", email), ("birthday", birthday), ("birthday_visibility", visibility)]
            .compactMap { name, value in value.map { "\(name)=\(encode($0))" } }
        return pairs.isEmpty ? nil : pairs.joined(separator: "&")
    }

    private static let unreserved = CharacterSet(
        charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")

    private static func encode(_ value: String) -> String {
        value.addingPercentEncoding(withAllowedCharacters: unreserved) ?? ""
    }
}
