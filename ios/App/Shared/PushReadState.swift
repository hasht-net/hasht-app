import Foundation
import Darwin

/// App and extension share a stable lock inode; atomic replacement only touches data.
/// Never use a device clock as a server read boundary.
final class PushReadState {
    struct Account: Codable {
        var reads: [String: Double] = [:]
        var removed: [String: Double] = [:]
        func suppresses(tag: String?, ids: [String], created: Double?, now: Double = Date().timeIntervalSince1970) -> Bool {
            if ids.contains(where: { (removed[$0] ?? 0) > now }) { return true }
            guard let tag = tag, let created = created, let through = reads[PushReadState.channel(tag)] else { return false }
            return created <= through
        }
    }
    private static let mutex = NSLock()
    private let directory: URL
    init(directory: URL) { self.directory = directory }
    static var shared: PushReadState? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: "group.net.hasht.app")
            .map { PushReadState(directory: $0.appendingPathComponent("PushReadState", isDirectory: true)) }
    }
    static func required() throws -> PushReadState {
        guard let store = shared else { throw CocoaError(.fileNoSuchFile) }
        return store
    }
    static func channel(_ tag: String) -> String {
        tag.components(separatedBy: "/message:").first ?? tag
    }
    private func transaction<T>(write: Bool = true, _ body: (inout [String: Account]) throws -> T) throws -> T {
        Self.mutex.lock()
        defer { Self.mutex.unlock() }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let lock = open(directory.appendingPathComponent("state.lock").path, O_CREAT | O_RDWR, 0o600)
        guard lock >= 0 else { throw CocoaError(.fileWriteUnknown) }
        defer { close(lock) }
        guard flock(lock, LOCK_EX) == 0 else { throw CocoaError(.fileWriteUnknown) }
        defer { flock(lock, LOCK_UN) }
        let file = directory.appendingPathComponent("state.json")
        var state: [String: Account] = [:]
        if FileManager.default.fileExists(atPath: file.path) {
            state = try JSONDecoder().decode([String: Account].self, from: Data(contentsOf: file))
        }
        let result = try body(&state)
        if write { try JSONEncoder().encode(state).write(to: file, options: .atomic) }
        return result
    }
    func record(account: String, reads: [String: Double] = [:], removed: [String] = [], now: Double = Date().timeIntervalSince1970) throws {
        try transaction { state in
            for key in Array(state.keys) {
                var entry = state[key]!
                entry.removed = entry.removed.filter { $0.value > now }
                if entry.reads.isEmpty && entry.removed.isEmpty { state.removeValue(forKey: key) }
                else { state[key] = entry }
            }
            var entry = state[account] ?? Account()
            for (tag, through) in reads where through.isFinite && through > 0 {
                let tag = Self.channel(tag)
                entry.reads[tag] = max(entry.reads[tag] ?? 0, through)
            }
            // Relay delivery expires within a day; retain tombstones for a week.
            for id in removed { entry.removed[id] = now + 7 * 24 * 60 * 60 }
            state[account] = entry
        }
    }
    func forget(account: String) throws {
        try transaction { state in _ = state.removeValue(forKey: account) }
    }
    func snapshot(account: String) throws -> Account {
        try transaction(write: false) { state in state[account] ?? Account() }
    }
    func suppresses(account: String, tag: String?, ids: [String], created: Double?, now: Double = Date().timeIntervalSince1970) throws -> Bool {
        try snapshot(account: account).suppresses(tag: tag, ids: ids, created: created, now: now)
    }
}
