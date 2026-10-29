import Foundation

var failures = 0
func check(_ condition: Bool, _ message: String) {
    if !condition {
        failures += 1
        print("FAIL: \(message)")
    }
}

check(SignupPrefill.email(" a+b@example.com ") == "a+b@example.com", "email trimmed")
for bad in [nil, "", "no-at", "a b@example.com", "a\u{7}@x.co", String(repeating: "x", count: 250) + "@e.co"] {
    check(SignupPrefill.email(bad) == nil, "email rejected: \(String(describing: bad))")
}
check(SignupPrefill.birthday("1990-05-04") == "1990-05-04", "birthday kept")
check(SignupPrefill.birthday("2000-02-29") == "2000-02-29", "leap day kept")
for bad in [nil, "1990-02-30", "May 4", "1990-5-4", "19900504"] {
    check(SignupPrefill.birthday(bad) == nil, "birthday rejected: \(String(describing: bad))")
}
check(SignupPrefill.visibility("month_day") == "month_day", "visibility kept")
check(SignupPrefill.visibility("everyone") == nil, "visibility rejected")
check(SignupPrefill.fragment(email: nil, birthday: nil, visibility: nil) == nil, "empty fragment")
check(
    SignupPrefill.fragment(email: "a+b c@example.com", birthday: "1990-05-04", visibility: "full")
        == "email=a%2Bb%20c%40example.com&birthday=1990-05-04&birthday_visibility=full",
    "fragment form-encoded")

if failures > 0 { exit(1) }
print("signup prefill: ok")
