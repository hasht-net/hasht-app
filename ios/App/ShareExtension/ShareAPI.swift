import Foundation

struct ShareDestination {
    let id: String
    let name: String
    let detail: String
    let kind: Kind
    let subtitle: String

    enum Kind { case directMessage, channel }

    init(id: String, name: String, detail: String, kind: Kind = .channel, subtitle: String = "") {
        self.id = id
        self.name = name
        self.detail = detail
        self.kind = kind
        self.subtitle = subtitle
    }
}

/// Refuse redirects so bearer credentials cannot leave the chosen instance.
final class ShareAPI: NSObject, URLSessionTaskDelegate {
    let auth: ShareSession
    private let configuration: URLSessionConfiguration
    private lazy var session: URLSession = {
        let config = configuration
        config.timeoutIntervalForRequest = 60
        config.timeoutIntervalForResource = 300
        return URLSession(configuration: config, delegate: self, delegateQueue: nil)
    }()

    init(auth: ShareSession, configuration: URLSessionConfiguration = .ephemeral) {
        self.auth = auth
        self.configuration = configuration
    }
    func cancel() { session.invalidateAndCancel() }

    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }

    private func request(_ path: String, method: String = "GET") throws -> URLRequest {
        guard let base = URL(string: auth.origin), let url = URL(string: "/api" + path, relativeTo: base) else {
            throw ShareError.message("Invalid instance address. Open Hasht and reconnect.")
        }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue("Bearer " + auth.token, forHTTPHeaderField: "Authorization")
        request.setValue("app", forHTTPHeaderField: "X-Client-Kind")
        return request
    }

    private func decode(_ data: Data, _ response: URLResponse) throws -> Any {
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        let json = try? JSONSerialization.jsonObject(with: data)
        guard (200..<300).contains(status) else {
            if status == 401 { throw ShareError.message("Your session expired. Open Hasht and sign in again.") }
            throw ShareError.message((json as? [String: Any])?["error"] as? String ?? "The instance returned HTTP \(status).")
        }
        return json ?? [:]
    }

    func get(_ path: String) async throws -> Any {
        let (data, response) = try await session.data(for: request(path))
        return try decode(data, response)
    }

    func destinations() async throws -> [ShareDestination] {
        let channels = try await get("/me/channels") as? [[String: Any]] ?? []
        let servers = try await get("/servers") as? [[String: Any]] ?? []
        let dms = try await get("/dms") as? [[String: Any]] ?? []
        let writable = Set(channels.filter { $0["can_send_messages"] as? Bool == true }.compactMap { $0["id"] as? String })
        var result: [ShareDestination] = []
        for dm in dms {
            guard let id = dm["channel_id"] as? String, writable.contains(id) else { continue }
            let peer = dm["peer"] as? [String: Any]
            if peer?["can_message"] as? Bool == false { continue }
            let members = dm["members"] as? [[String: Any]] ?? []
            let names = members.map { $0["display_name"] as? String ?? $0["username"] as? String ?? "Unknown" }
            let name = dm["name"] as? String ?? peer?["display_name"] as? String ?? peer?["username"] as? String ?? names.joined(separator: ", ")
            let subtitle = dm["is_group"] as? Bool == true ? "Group conversation" : peer?["username"] as? String ?? "Direct message"
            result.append(ShareDestination(id: id, name: name, detail: "Direct messages", kind: .directMessage, subtitle: subtitle))
        }
        for channel in channels {
            guard let id = channel["id"] as? String, writable.contains(id), channel["is_dm"] as? Bool != true,
                  channel["channel_type"] as? String == "text", let name = channel["name"] as? String else { continue }
            let server = servers.first { $0["id"] as? String == channel["server_id"] as? String }
            result.append(ShareDestination(id: id, name: "#" + name, detail: server?["name"] as? String ?? "Channels", subtitle: channel["topic"] as? String ?? "Text channel"))
        }
        return result
    }

    func upload(_ file: ShareFile, channel: String, directory: URL) async throws -> String {
        let boundary = UUID().uuidString
        let body = directory.appendingPathComponent("multipart")
        defer { try? FileManager.default.removeItem(at: body) }
        FileManager.default.createFile(atPath: body.path, contents: nil)
        let out = try FileHandle(forWritingTo: body)
        do {
            try out.write(contentsOf: Data("--\(boundary)\r\nContent-Disposition: form-data; name=\"file\"; filename=\"\(file.name)\"\r\nContent-Type: \(file.type)\r\n\r\n".utf8))
            let input = try FileHandle(forReadingFrom: file.url)
            defer { try? input.close() }
            while let chunk = try input.read(upToCount: 64 * 1024), !chunk.isEmpty {
                try Task.checkCancellation()
                try out.write(contentsOf: chunk)
            }
            try out.write(contentsOf: Data("\r\n--\(boundary)--\r\n".utf8))
            try out.close()
        } catch { try? out.close(); throw error }
        var req = try request("/channels/\(channel)/attachments", method: "POST")
        req.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        let (data, response) = try await session.upload(for: req, fromFile: body)
        guard let json = try decode(data, response) as? [String: Any], let id = json["id"] as? String else {
            throw ShareError.message("The instance returned an invalid attachment.")
        }
        return id
    }

    func send(channel: String, text: String, attachments: [String], clientID: String) async throws {
        var req = try request("/channels/\(channel)/messages", method: "POST")
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try JSONSerialization.data(withJSONObject: ["body": text, "attachment_ids": attachments, "client_id": clientID], options: .sortedKeys)
        let (data, response) = try await session.data(for: req)
        _ = try decode(data, response)
    }
}
