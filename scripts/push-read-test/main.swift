import Foundation

func check(_ value: Bool) { assert(value) }

let args = CommandLine.arguments
if args.count > 2 {
    let store = PushReadState(directory: URL(fileURLWithPath: args[1]))
    for i in 0..<100 { try store.record(account: "a", reads: ["channel:\(args[2])": Double(i + 1)]) }
    exit(0)
}
let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
defer { try? FileManager.default.removeItem(at: dir) }
let store = PushReadState(directory: dir)
let boundary = 1_780_000_000_123_456.0
try store.record(account: "a", reads: ["channel:c": boundary])
try store.record(account: "a", reads: ["channel:c": boundary - 1])
check(try store.suppresses(account: "a", tag: "channel:c/message:m", ids: ["m"], created: boundary))
check(try !store.suppresses(account: "a", tag: "channel:c/message:m", ids: ["m"], created: boundary + 1))
check(try !store.suppresses(account: "b", tag: "channel:c", ids: ["m"], created: boundary))
try store.record(account: "a", removed: ["gone"], now: 100)
check(try store.suppresses(account: "a", tag: nil, ids: ["gone"], created: nil, now: 101))
check(try !store.suppresses(account: "a", tag: nil, ids: ["gone"], created: nil, now: 100 + 8 * 86400))
let workers = try (0..<8).map { i -> Process in
    let worker = Process()
    worker.executableURL = URL(fileURLWithPath: args[0])
    worker.arguments = [dir.path, String(i)]
    try worker.run()
    return worker
}
for worker in workers { worker.waitUntilExit(); assert(worker.terminationStatus == 0) }
for i in 0..<8 {
    check(try store.suppresses(account: "a", tag: "channel:\(i)", ids: [], created: 100))
}
// Snapshots make a batch consistent and avoid one file decode per notification.
let snapshot = try store.snapshot(account: "a")
try store.forget(account: "a")
check(snapshot.suppresses(tag: "channel:c", ids: [], created: boundary))
check(try !store.suppresses(account: "a", tag: "channel:c", ids: [], created: boundary))
try store.record(account: "inactive", removed: ["expired"], now: 100)
try store.record(account: "active", reads: ["channel:c": boundary], now: 100 + 8 * 86400)
check(try store.snapshot(account: "inactive").removed.isEmpty)
print("push read state: ok")
