import Foundation

enum APIError: LocalizedError {
    case invalidURL
    case unauthorized(String)
    case server(Int, String)
    case network(String)
    case decoding

    var errorDescription: String? {
        switch self {
        case .invalidURL: return "服务器地址不正确"
        case .unauthorized(let m): return m
        case .server(_, let m): return m
        case .network(let m): return m
        case .decoding: return "服务器返回的数据无法识别，请确认地址指向万物收纳服务端"
        }
    }
}

struct ServerInfo: Decodable {
    struct Features: Decodable {
        var icons: Bool
        var cutout: Bool
    }
    var name: String
    var version: String
    var registrationOpen: Bool
    /// "open", "code" (an invite or setup code is needed) or "closed"; nil on older servers.
    var registration: String?
    var passwordMin: Int?
    var features: Features

    var needsCode: Bool { registration == "code" }
}

struct AuthResponse: Decodable {
    struct User: Decodable {
        var id: String
        var username: String
    }
    var token: String
    var user: User
}

struct SyncResponse: Decodable {
    var cursor: Int64
    var changes: [SyncChange]
    var hasMore: Bool
    var accepted: Int
    var reset: Bool?
}

/// Thin JSON client for the self-hosted server.
struct APIClient {
    let baseURL: URL
    var token: String?

    /// Accepts "192.168.1.5:5858", "nook.example.com" or a full URL.
    static func normalize(_ raw: String) -> URL? {
        var s = raw.trimmed
        guard !s.isEmpty else { return nil }
        if !s.lowercased().hasPrefix("http://") && !s.lowercased().hasPrefix("https://") { s = "http://" + s }
        while s.hasSuffix("/") { s.removeLast() }
        guard let url = URL(string: s), url.host != nil else { return nil }
        return url
    }

    /// Plain http to something that doesn't look like a home-network address:
    /// passwords and data could be read on the way.
    static func isUnencryptedPublic(_ url: URL) -> Bool {
        guard url.scheme?.lowercased() == "http", let host = url.host?.lowercased() else { return false }
        if !host.contains(".") && !host.contains(":") { return false }  // "nas", "localhost"
        for suffix in [".local", ".lan", ".home", ".home.arpa", ".internal", ".localhost"] where host.hasSuffix(suffix) {
            return false
        }
        let parts = host.split(separator: ".").compactMap { Int($0) }
        if parts.count == 4 {
            switch (parts[0], parts[1]) {
            case (10, _), (127, _), (192, 168), (169, 254): return false
            case (172, 16...31), (100, 64...127): return false  // private, carrier-grade NAT / Tailscale
            default: return true
            }
        }
        if host.contains(":") {  // IPv6: loopback, unique-local, link-local
            return !(host == "::1" || host.hasPrefix("fc") || host.hasPrefix("fd") || host.hasPrefix("fe80"))
        }
        return true
    }

    private static let session: URLSession = {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 30
        config.waitsForConnectivity = false
        return URLSession(configuration: config)
    }()

    private func makeRequest(_ method: String, _ path: String, contentType: String? = "application/json") -> URLRequest {
        var req = URLRequest(url: baseURL.appendingPathComponent(path))
        req.httpMethod = method
        if let contentType { req.setValue(contentType, forHTTPHeaderField: "Content-Type") }
        if let token { req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        return req
    }

    private func send(_ req: URLRequest, timeout: TimeInterval? = nil) async throws -> Data {
        var req = req
        if let timeout { req.timeoutInterval = timeout }
        let data: Data, response: URLResponse
        do {
            (data, response) = try await Self.session.data(for: req)
        } catch let error as URLError {
            switch error.code {
            case .timedOut: throw APIError.network("连接服务器超时")
            case .cannotFindHost, .cannotConnectToHost, .dnsLookupFailed:
                throw APIError.network("连接不上服务器，请检查地址和网络")
            case .notConnectedToInternet: throw APIError.network("当前没有网络连接")
            case .cancelled: throw CancellationError()
            default: throw APIError.network("网络错误：\(error.localizedDescription)")
            }
        }
        guard let http = response as? HTTPURLResponse else { throw APIError.decoding }
        guard (200..<300).contains(http.statusCode) else {
            let detail = (try? JSONDecoder().decode([String: JSONValue].self, from: data))?["detail"]?.string
            let message = detail ?? "服务器错误（\(http.statusCode)）"
            if http.statusCode == 401 { throw APIError.unauthorized(message) }
            throw APIError.server(http.statusCode, message)
        }
        return data
    }

    func json<T: Decodable>(_ method: String, _ path: String, body: (any Encodable)? = nil,
                            timeout: TimeInterval? = nil) async throws -> T {
        var req = makeRequest(method, path)
        if let body { req.httpBody = try JSONCoding.encoder.encode(body) }
        let data = try await send(req, timeout: timeout)
        do { return try JSONCoding.decoder.decode(T.self, from: data) } catch { throw APIError.decoding }
    }

    func info() async throws -> ServerInfo { try await json("GET", "api/info", timeout: 8) }

    func login(username: String, password: String, device: String, register: Bool, inviteCode: String = "") async throws -> AuthResponse {
        struct Body: Encodable { var username: String; var password: String; var deviceName: String; var inviteCode: String? }
        return try await json("POST", register ? "api/auth/register" : "api/auth/login",
                              body: Body(username: username, password: password, deviceName: device,
                                         inviteCode: register ? inviteCode.trimmed.nilIfEmpty : nil))
    }

    func logout() async throws {
        let _: [String: JSONValue] = try await json("POST", "api/auth/logout")
    }

    func changePassword(old: String, new: String) async throws {
        struct Body: Encodable { var oldPassword: String; var newPassword: String }
        let _: [String: JSONValue] = try await json("POST", "api/auth/password", body: Body(oldPassword: old, newPassword: new))
    }

    func deleteAccount(password: String) async throws {
        struct Body: Encodable { var password: String }
        let _: [String: JSONValue] = try await json("POST", "api/me/delete", body: Body(password: password))
    }

    func sync(cursor: Int64, changes: [SyncChange], limit: Int = 500) async throws -> SyncResponse {
        struct Body: Encodable { var cursor: Int64; var changes: [SyncChange]; var limit: Int }
        return try await json("POST", "api/sync", body: Body(cursor: cursor, changes: changes, limit: limit), timeout: 60)
    }

    func upload(fileId: String, data: Data) async throws {
        let type = fileId.hasSuffix(".png") ? "image/png" : "image/jpeg"
        var req = makeRequest("PUT", "api/files/\(fileId)", contentType: type)
        req.httpBody = data
        _ = try await send(req, timeout: 120)
    }

    func download(fileId: String) async throws -> Data {
        try await send(makeRequest("GET", "api/files/\(fileId)", contentType: nil), timeout: 60)
    }

    func generateIcon(prompt: String) async throws -> String {
        struct Body: Encodable { var prompt: String }
        struct Response: Decodable { var svg: String }
        let r: Response = try await json("POST", "api/icons/generate", body: Body(prompt: prompt), timeout: 180)
        return r.svg
    }

    func cutout(imageData: Data) async throws -> Data {
        var req = makeRequest("POST", "api/cutout", contentType: "image/jpeg")
        req.httpBody = imageData
        return try await send(req, timeout: 180)
    }
}
