import Foundation
import UniformTypeIdentifiers

struct ShareFile {
    let url: URL
    let name: String
    let type: String
}

struct ShareContent {
    let directory: URL
    var text = ""
    var files: [ShareFile] = []

    // A total disk budget also bounds a share containing several large videos.
    static let maxBytes: Int64 = 1 << 30
    static let maxFiles = 10

    static func read(_ items: [NSExtensionItem]) async throws -> ShareContent {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        var content = ShareContent(directory: dir)
        var total: Int64 = 0
        do {
            for item in items {
                if let text = item.attributedContentText?.string, !text.isEmpty { content.append(text) }
                for provider in item.attachments ?? [] {
                    try Task.checkCancellation()
                    // Prefer actual media/files over their alternate URL or text representations.
                    if let type = provider.registeredTypeIdentifiers.first(where: {
                        guard let type = UTType($0) else { return false }
                        return type.conforms(to: .data) && !type.conforms(to: .text) && !type.conforms(to: .url)
                    }) {
                        guard content.files.count < maxFiles else { throw ShareError.message("Share up to 10 files at a time.") }
                        let target = dir.appendingPathComponent(UUID().uuidString)
                        let (name, size) = try await copy(provider, type: type, to: target, limit: maxBytes - total)
                        total += size
                        content.files.append(ShareFile(url: target, name: name, type: UTType(type)?.preferredMIMEType ?? "application/octet-stream"))
                    } else if provider.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier) {
                        guard content.files.count < maxFiles else { throw ShareError.message("Share up to 10 files at a time.") }
                        let target = dir.appendingPathComponent(UUID().uuidString)
                        let (name, size) = try await copyURL(provider, to: target, limit: maxBytes - total)
                        total += size
                        let type = UTType(filenameExtension: (name as NSString).pathExtension)?.preferredMIMEType ?? "application/octet-stream"
                        content.files.append(ShareFile(url: target, name: safeName(name), type: type))
                    } else if provider.hasItemConformingToTypeIdentifier(UTType.url.identifier) {
                        let value = try await load(provider, type: UTType.url.identifier)
                        let url = value as? URL ?? string(value).flatMap { URL(string: $0) }
                        if let url = url, !url.isFileURL { content.append(url.absoluteString) }
                        else { throw ShareError.message("This link couldn't be read.") }
                    } else if provider.hasItemConformingToTypeIdentifier(UTType.text.identifier) {
                        let value = try await load(provider, type: UTType.text.identifier)
                        guard let text = string(value) else { throw ShareError.message("This text couldn't be read.") }
                        content.append(text)
                    } else {
                        throw ShareError.message("One of these items can't be shared. Try sharing it as a file.")
                    }
                }
            }
            guard !content.text.isEmpty || !content.files.isEmpty else { throw ShareError.message("There's nothing to share.") }
            return content
        } catch {
            try? FileManager.default.removeItem(at: dir)
            throw error
        }
    }

    private mutating func append(_ text: String) {
        if !self.text.split(separator: "\n").contains(Substring(text)) {
            self.text += (self.text.isEmpty ? "" : "\n") + text
        }
    }

    private static func string(_ value: NSSecureCoding) -> String? {
        value as? String ?? (value as? NSAttributedString)?.string ?? (value as? Data).flatMap { String(data: $0, encoding: .utf8) }
    }

    private static func load(_ provider: NSItemProvider, type: String) async throws -> NSSecureCoding {
        try await withCheckedThrowingContinuation { continuation in
            provider.loadItem(forTypeIdentifier: type, options: nil) { value, error in
                if let value = value { continuation.resume(returning: value) }
                else { continuation.resume(throwing: error ?? ShareError.message("Couldn't read the shared item.")) }
            }
        }
    }

    private static func copy(_ provider: NSItemProvider, type: String, to target: URL, limit: Int64) async throws -> (String, Int64) {
        let suggestedName = provider.suggestedName
        return try await withCheckedThrowingContinuation { continuation in
            provider.loadFileRepresentation(forTypeIdentifier: type) { source, error in
                do {
                    guard let source = source else { throw error ?? ShareError.message("Couldn't read the shared file.") }
                    let size = try copyFile(source, to: target, limit: limit)
                    var name = suggestedName ?? source.lastPathComponent
                    if (name as NSString).pathExtension.isEmpty, let ext = UTType(type)?.preferredFilenameExtension { name += "." + ext }
                    name = safeName(name)
                    continuation.resume(returning: (name.isEmpty ? "shared-file" : name, size))
                } catch { continuation.resume(throwing: error) }
            }
        }
    }

    private static func safeName(_ name: String) -> String {
        let cleaned = name.components(separatedBy: CharacterSet.controlCharacters.union(CharacterSet(charactersIn: "/\\\""))).joined(separator: "_")
        return cleaned.isEmpty ? "shared-file" : cleaned
    }

    private static func copyURL(_ provider: NSItemProvider, to target: URL, limit: Int64) async throws -> (String, Int64) {
        try await withCheckedThrowingContinuation { continuation in
            provider.loadItem(forTypeIdentifier: UTType.fileURL.identifier, options: nil) { value, error in
                do {
                    guard let value = value, let url = value as? URL ?? string(value).flatMap({ URL(string: $0) }), url.isFileURL else { throw error ?? ShareError.message("Couldn't read the shared file.") }
                    let scoped = url.startAccessingSecurityScopedResource()
                    defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                    let size = try copyFile(url, to: target, limit: limit)
                    continuation.resume(returning: (url.lastPathComponent, size))
                } catch { continuation.resume(throwing: error) }
            }
        }
    }

    static func copyFile(_ source: URL, to target: URL, limit: Int64) throws -> Int64 {
        let input = try FileHandle(forReadingFrom: source)
        defer { try? input.close() }
        FileManager.default.createFile(atPath: target.path, contents: nil)
        let output = try FileHandle(forWritingTo: target)
        defer { try? output.close() }
        var total: Int64 = 0
        while let data = try input.read(upToCount: 64 * 1024), !data.isEmpty {
            total += Int64(data.count)
            guard total <= limit else { throw ShareError.message("Shared files must total 1 GB or less.") }
            try output.write(contentsOf: data)
        }
        return total
    }
}

enum ShareError: LocalizedError {
    case message(String)
    var errorDescription: String? { if case .message(let message) = self { return message }; return nil }
}
