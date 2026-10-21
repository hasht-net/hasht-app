import Foundation
import UniformTypeIdentifiers

func require(_ condition: @autoclosure () -> Bool, _ message: String) throws {
    if !condition() { throw ShareError.message(message) }
}

final class MockServer: URLProtocol {
    static var requests: [URLRequest] = []
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        var captured = request
        if captured.httpBody == nil, let stream = request.httpBodyStream {
            stream.open()
            defer { stream.close() }
            var body = Data()
            var buffer = [UInt8](repeating: 0, count: 4096)
            while stream.hasBytesAvailable {
                let count = stream.read(&buffer, maxLength: buffer.count)
                if count <= 0 { break }
                body.append(buffer, count: count)
            }
            captured.httpBody = body
        }
        Self.requests.append(captured)
        let path = request.url!.path
        let body: String
        var status = 200
        switch path {
        case "/api/me/channels":
            body = """
            [{"id":"dm","is_dm":true,"can_send_messages":true},
             {"id":"text","name":"general","server_id":"server","channel_type":"text","can_send_messages":true},
             {"id":"locked","name":"private","channel_type":"text","can_send_messages":false},
             {"id":"voice","name":"Voice","channel_type":"voice","can_send_messages":true},
             {"id":"agent","is_dm":true,"can_send_messages":true}]
            """
        case "/api/servers": body = "[{\"id\":\"server\",\"name\":\"Community\"}]"
        case "/api/dms": body = """
            [{"channel_id":"dm","peer":{"id":"alice-id","username":"alice","display_name":"Alice","can_message":true,"avatar_updated_at":"2026-10-21T12:00:00+00:00"}},
             {"channel_id":"agent","peer":{"username":"disabled","can_message":false}}]
            """
        case "/api/channels/text/attachments": body = "{\"id\":\"attachment\"}"
        case "/api/channels/text/messages": body = "{\"id\":\"message\"}"
        case "/api/avatars/alice-id": body = "avatar-bytes"
        case "/api/expired": status = 401; body = "{}"
        default: status = 500; body = "{}"
        }
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: ["Content-Type": path.hasPrefix("/api/avatars/") ? "image/png" : "application/json"])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

func run() async throws {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: directory) }
    let source = directory.appendingPathComponent("sample.txt")
    let bytes = Data(repeating: 42, count: 150_000)
    try bytes.write(to: source)
    let copied = directory.appendingPathComponent("copy")
    let count = try ShareContent.copyFile(source, to: copied, limit: Int64(bytes.count))
    try require(count == bytes.count, "copy size")
    let result = try Data(contentsOf: copied)
    try require(result == bytes, "binary data changed")
    do {
        _ = try ShareContent.copyFile(source, to: copied, limit: 100)
        throw ShareError.message("size limit was not enforced")
    } catch ShareError.message(let message) {
        try require(message.contains("1 GB"), "wrong size-limit error")
    }

    let item = NSExtensionItem()
    item.attachments = [NSItemProvider(object: "Hello from another app" as NSString), NSItemProvider(object: URL(string: "https://example.org/page")! as NSURL)]
    let content = try await ShareContent.read([item])
    defer { try? FileManager.default.removeItem(at: content.directory) }
    try require(content.text.contains("Hello from another app"), "text not imported")
    try require(content.text.contains("https://example.org/page"), "link not imported")
    try require(content.files.isEmpty, "text became a file")

    let documentItem = NSExtensionItem()
    documentItem.attachments = [NSItemProvider(item: source as NSURL, typeIdentifier: UTType.fileURL.identifier)]
    let document = try await ShareContent.read([documentItem])
    defer { try? FileManager.default.removeItem(at: document.directory) }
    let documentBytes = try Data(contentsOf: document.files[0].url)
    try require(documentBytes == bytes && document.files[0].name == "sample.txt", "file URL import failed")

    let mediaProvider = NSItemProvider()
    mediaProvider.suggestedName = "photo.png"
    mediaProvider.registerFileRepresentation(forTypeIdentifier: UTType.png.identifier, fileOptions: [], visibility: .all) { completion in
        completion(source, false, nil)
        return nil
    }
    let mediaItem = NSExtensionItem()
    mediaItem.attachments = [mediaProvider]
    let media = try await ShareContent.read([mediaItem])
    defer { try? FileManager.default.removeItem(at: media.directory) }
    let importedBytes = try Data(contentsOf: media.files[0].url)
    try require(importedBytes == bytes && media.files[0].name == "photo.png", "media copy changed content or filename")
    try require(media.files[0].type == "image/png", "media MIME type missing")
    mediaItem.attachments = Array(repeating: mediaProvider, count: 11)
    do {
        let unexpected = try await ShareContent.read([mediaItem])
        try? FileManager.default.removeItem(at: unexpected.directory)
        throw ShareError.message("file count limit was ignored")
    } catch ShareError.message(let message) {
        try require(message.contains("10 files"), "wrong file-count error")
    }

    let config = URLSessionConfiguration.ephemeral
    config.protocolClasses = [MockServer.self]
    let api = ShareAPI(auth: ShareSession(origin: "https://hasht.example", token: "test-token", username: "test"), configuration: config)
    defer { api.cancel() }
    let destinations = try await api.destinations()
    try require(destinations.map(\.id) == ["dm", "text"], "permission, voice, or disabled-agent filtering failed")
    try require(destinations[0].kind == .directMessage && destinations[0].subtitle == "alice", "DM identity metadata missing")
    try require(destinations[1].kind == .channel, "channel filter metadata missing")
    try require(destinations[0].name == "Alice" && destinations[1].detail == "Community", "destination labels failed")
    try require(destinations[1].avatarPath == nil, "channel should keep its placeholder")
    try require(destinations[0].avatarPath == "/avatars/alice-id?v=2026-10-21T12%3A00%3A00%2B00%3A00", "avatar metadata or version escaping missing")
    let avatar = try await api.avatarData(destinations[0].avatarPath!)
    try require(avatar == Data("avatar-bytes".utf8), "avatar bytes missing")
    try require(MockServer.requests.last?.url?.host == "hasht.example", "avatar left the active instance")
    do {
        _ = try await api.avatarData("/expired")
        throw ShareError.message("failed avatar response was accepted")
    } catch ShareError.message(let message) {
        try require(message == "Avatar unavailable.", "wrong avatar failure")
    }
    let id = try await api.upload(ShareFile(url: source, name: "sample.txt", type: "text/plain"), channel: "text", directory: directory)
    try require(id == "attachment", "upload ID missing")
    let clientID = UUID().uuidString
    try await api.send(channel: "text", text: "hello", attachments: [id], clientID: clientID)
    try await api.send(channel: "text", text: "hello", attachments: [id], clientID: clientID)
    let sends = MockServer.requests.filter { $0.url!.path.hasSuffix("/messages") }
    try require(sends.count == 2 && sends[0].httpBody == sends[1].httpBody, "retry changed the message")
    let json = try JSONSerialization.jsonObject(with: sends[0].httpBody!) as! [String: Any]
    try require(json["client_id"] as? String == clientID, "message idempotency key missing")
    try require(json["attachment_ids"] as? [String] == ["attachment"], "attachment not linked")
    try require(MockServer.requests.allSatisfy { $0.value(forHTTPHeaderField: "Authorization") == "Bearer test-token" }, "missing authorization")
    do {
        _ = try await api.get("/expired")
        throw ShareError.message("401 was ignored")
    } catch ShareError.message(let message) {
        try require(message.contains("sign in again"), "missing expired-session guidance")
    }
    print("iOS share tests passed: text, URLs, binary copies, limits, permissions, uploads, retries, expired sessions")
}

Task {
    do { try await run(); exit(0) }
    catch { print("FAIL: \(error)"); exit(1) }
}
dispatchMain()
