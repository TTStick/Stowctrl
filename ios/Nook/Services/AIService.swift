import Foundation
import Observation

/// Draws icons with Claude, either through the user's sync server (the key
/// lives there) or straight from this device with the user's own key (kept in
/// the Keychain, never synced) — the latter works without any server.
@MainActor
@Observable
final class AIService {
    enum Source: String { case server, direct }

    enum Availability: Equatable {
        case ready
        case needsKey
        case offline
        case needsLogin
        case serverWithoutLLM
    }

    var source: Source { didSet { UserDefaults.standard.set(source.rawValue, forKey: "ai.source") } }
    var baseURL: String { didSet { UserDefaults.standard.set(baseURL, forKey: "ai.baseURL") } }
    var model: String { didSet { UserDefaults.standard.set(model, forKey: "ai.model") } }
    private(set) var hasKey: Bool

    static let defaultModel = "claude-opus-5-5"
    private static let keyAccount = "ai.apiKey"

    init() {
        let d = UserDefaults.standard
        source = Source(rawValue: d.string(forKey: "ai.source") ?? "") ?? .server
        baseURL = d.string(forKey: "ai.baseURL") ?? ""
        model = d.string(forKey: "ai.model") ?? AIService.defaultModel
        hasKey = !(Keychain.get(AIService.keyAccount) ?? "").isEmpty
    }

    func setKey(_ key: String?) {
        let k = key?.trimmed
        Keychain.set(k?.nilIfEmpty, for: Self.keyAccount)
        // Read back: an unsigned build can't write to the Keychain.
        hasKey = !(Keychain.get(Self.keyAccount) ?? "").isEmpty
    }

    func availability(_ sync: SyncManager) -> Availability {
        switch source {
        case .direct:
            return hasKey ? .ready : .needsKey
        case .server:
            if sync.offline { return .offline }
            if !sync.isLoggedIn { return .needsLogin }
            return sync.canGenerateIcons ? .ready : .serverWithoutLLM
        }
    }

    func generate(_ prompt: String, sync: SyncManager) async throws -> String {
        let svg: String
        switch source {
        case .server:
            guard let client = sync.client else { throw APIError.invalidURL }
            svg = try await client.generateIcon(prompt: prompt)
        case .direct:
            guard let key = Keychain.get(Self.keyAccount), !key.isEmpty else { throw APIError.unauthorized("请先填写 API Key") }
            svg = try await DirectClaude(key: key, baseURL: baseURL, model: model.trimmed.nilIfEmpty ?? Self.defaultModel)
                .drawIcon(prompt)
        }
        guard SVGDocument.parse(svg) != nil else { throw APIError.server(0, "这次画出来的图标无法显示，再试一次吧") }
        return svg
    }

    /// Validates the key without spending tokens (lists models).
    func checkKey(_ key: String) async throws {
        try await DirectClaude(key: key, baseURL: baseURL, model: model).checkKey()
    }
}

/// Raw HTTP client for the Claude Messages API (there is no official Swift SDK).
struct DirectClaude {
    let key: String
    let baseURL: String
    let model: String

    /// Same instructions the server uses (server/app/icons.py).
    static let systemPrompt = """
    You draw small icons for a home-inventory app. The user names a household \
    object (usually in Chinese); you reply with exactly one SVG document and nothing else.

    Style:
    - Simple, instantly recognisable outline icon in the spirit of SF Symbols or Lucide.
    - Monochrome. Every shape uses stroke="currentColor" and fill="none". fill="currentColor" is \
    allowed only for tiny solid details such as dots or buttons.
    - stroke-width="2.5", stroke-linecap="round", stroke-linejoin="round".
    - Show the object alone from its most recognisable angle, centred, with about 4 units of padding.
    - At most 14 shapes. No background, no frame, no shadow, no text or letters.

    Format:
    - <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 48 48">.
    - Only these elements: g, path, circle, ellipse, rect, line, polyline, polygon.
    - No transform, style, class, id, defs, gradients, filters, masks, clipPath, image, text or script.
    - Absolute coordinates inside the 48x48 viewBox.
    """

    private var base: String {
        var s = baseURL.trimmed
        if s.isEmpty { s = "https://api.anthropic.com" }
        while s.hasSuffix("/") { s.removeLast() }
        return s
    }

    private func request(_ path: String, method: String, body: Data? = nil, beta: String? = nil) -> URLRequest? {
        guard let url = URL(string: base + path) else { return nil }
        var req = URLRequest(url: url, timeoutInterval: 180)
        req.httpMethod = method
        req.setValue(key, forHTTPHeaderField: "x-api-key")
        req.setValue("2026-10-21", forHTTPHeaderField: "anthropic-version")
        req.setValue("application/json", forHTTPHeaderField: "content-type")
        if let beta { req.setValue(beta, forHTTPHeaderField: "anthropic-beta") }
        req.httpBody = body
        return req
    }

    private func send(_ req: URLRequest) async throws -> [String: JSONValue] {
        let data: Data, response: URLResponse
        do {
            (data, response) = try await URLSession.shared.data(for: req)
        } catch let e as URLError where e.code == .cancelled {
            throw CancellationError()
        } catch {
            throw APIError.network("连接不上大模型服务，请检查网络或 API 地址")
        }
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        let json = (try? JSONDecoder().decode([String: JSONValue].self, from: data)) ?? [:]
        guard (200..<300).contains(status) else {
            let detail = json["error"]?["message"]?.string ?? ""
            switch status {
            case 401: throw APIError.unauthorized("API Key 无效")
            case 403: throw APIError.server(403, "这个 API Key 没有调用权限")
            case 404: throw APIError.server(404, "找不到模型 \(model)，或 API 地址不对")
            case 429: throw APIError.server(429, "调用太频繁，请稍后再试")
            case 400: throw APIError.server(400, "请求被拒绝：\(detail)")
            default: throw APIError.server(status, "大模型服务出错（\(status)），请稍后再试")
            }
        }
        return json
    }

    private func body(withFallbacks: Bool, prompt: String) throws -> Data {
        var obj: [String: JSONValue] = [
            "model": .string(model),
            "max_tokens": .number(16000),
            "system": .string(Self.systemPrompt),
            "messages": .array([.object(["role": .string("user"), "content": .string("要画的物品：\(prompt)")])]),
            "output_config": .object(["effort": .string("medium")]),
        ]
        // "default" lets the API pick a fallback model if this one declines.
        if withFallbacks { obj["fallbacks"] = .string("default") }
        return try JSONEncoder().encode(JSONValue.object(obj))
    }

    func drawIcon(_ prompt: String) async throws -> String {
        guard let first = request("/v1/messages", method: "POST", body: try body(withFallbacks: true, prompt: prompt),
                                  beta: "server-side-fallback-2026-07-01") else { throw APIError.invalidURL }
        var json: [String: JSONValue]
        do {
            json = try await send(first)
        } catch APIError.server(400, _) {
            // Gateways that don't know the fallback beta reject it; retry plainly.
            guard let plain = request("/v1/messages", method: "POST", body: try body(withFallbacks: false, prompt: prompt)) else { throw APIError.invalidURL }
            json = try await send(plain)
        }
        if json["stop_reason"]?.string == "refusal" {
            throw APIError.server(422, "大模型拒绝绘制这个图标，换个描述试试")
        }
        let text = (json["content"]?.array ?? []).compactMap { block -> String? in
            block["type"]?.string == "text" ? block["text"]?.string : nil
        }.joined()
        guard let range = text.range(of: "<svg[\\s\\S]*?</svg>", options: [.regularExpression, .caseInsensitive]) else {
            throw APIError.server(0, "大模型没有返回有效的图标，请重试")
        }
        return String(text[range])
    }

    func checkKey() async throws {
        guard let req = request("/v1/models?limit=1", method: "GET") else { throw APIError.invalidURL }
        _ = try await send(req)
    }
}
