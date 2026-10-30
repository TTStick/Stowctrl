import Foundation
import Observation

struct HAState: Decodable, Identifiable, Hashable {
    var entity_id: String
    var state: String
    var attributes: [String: JSONValue]
    var last_changed: String?

    var id: String { entity_id }
    var domain: String { String(entity_id.split(separator: ".").first ?? "") }
    var name: String { attributes["friendly_name"]?.string ?? entity_id }
    var unit: String? { attributes["unit_of_measurement"]?.string }
    var isOn: Bool { ["on", "open", "opening", "playing", "unlocked", "home", "cleaning", "heat", "cool", "auto", "heat_cool", "dry", "fan_only"].contains(state) }
    var isUnavailable: Bool { state == "unavailable" || state == "unknown" }
}

/// What the app can do with an entity, decided by its domain.
enum HAControl {
    case toggle          // light, switch, fan, input_boolean, automation, humidifier, siren
    case cover           // open / stop / close
    case lock            // lock / unlock
    case climate         // power + target temperature
    case media           // power + play/pause
    case activate        // scene, script
    case press           // button, input_button
    case vacuum          // start / return to dock
    case readOnly        // sensors and everything else

    init(domain: String) {
        switch domain {
        case "light", "switch", "fan", "input_boolean", "automation", "humidifier", "siren", "water_heater": self = .toggle
        case "cover", "valve": self = .cover
        case "lock": self = .lock
        case "climate": self = .climate
        case "media_player": self = .media
        case "scene", "script": self = .activate
        case "button", "input_button": self = .press
        case "vacuum", "lawn_mower": self = .vacuum
        default: self = .readOnly
        }
    }
}

enum HADomain {
    static func symbol(_ domain: String, on: Bool = false) -> String {
        switch domain {
        case "light": return on ? "lightbulb.fill" : "lightbulb"
        case "switch", "input_boolean": return on ? "power.circle.fill" : "power.circle"
        case "fan": return "fan"
        case "cover": return "blinds.horizontal.closed"
        case "lock": return on ? "lock.open" : "lock"
        case "climate": return "thermometer.medium"
        case "media_player": return "tv"
        case "sensor": return "gauge.medium"
        case "binary_sensor": return "sensor"
        case "camera": return "video"
        case "vacuum": return "fanblades"
        case "scene": return "sparkles"
        case "script": return "scroll"
        case "automation": return "gearshape.2"
        case "button", "input_button": return "button.programmable"
        case "humidifier": return "humidifier"
        case "water_heater": return "drop.degreesign"
        case "person", "device_tracker": return "person"
        case "weather": return "cloud.sun"
        default: return "circle.grid.2x2"
        }
    }

    static func title(_ domain: String) -> String {
        switch domain {
        case "light": return "灯"
        case "switch": return "开关"
        case "fan": return "风扇"
        case "cover": return "窗帘/卷帘"
        case "lock": return "门锁"
        case "climate": return "空调/温控"
        case "media_player": return "影音"
        case "sensor": return "传感器"
        case "binary_sensor": return "状态传感器"
        case "camera": return "摄像头"
        case "vacuum": return "扫地机"
        case "scene": return "场景"
        case "script": return "脚本"
        case "automation": return "自动化"
        case "button", "input_button": return "按钮"
        case "humidifier": return "加湿器"
        case "input_boolean": return "辅助开关"
        case "water_heater": return "热水器"
        default: return domain
        }
    }

    /// Domains people normally want to see when mapping devices onto furniture.
    static let mappable: Set<String> = ["light", "switch", "fan", "cover", "lock", "climate", "media_player",
                                         "sensor", "binary_sensor", "camera", "vacuum", "scene", "script",
                                         "button", "humidifier", "water_heater", "valve", "input_boolean",
                                         "lawn_mower", "siren"]

    static func stateText(_ s: HAState) -> String {
        if s.isUnavailable { return "不可用" }
        switch s.domain {
        case "sensor":
            return [s.state, s.unit].compactMap { $0 }.joined(separator: " ")
        case "climate":
            let current = s.attributes["current_temperature"]?.double.map { "\($0.clean)°" }
            let target = s.attributes["temperature"]?.double.map { "目标 \($0.clean)°" }
            let modes = ["off": "关闭", "cool": "制冷", "heat": "制热", "auto": "自动", "dry": "除湿",
                         "fan_only": "送风", "heat_cool": "冷暖"]
            let mode = modes[s.state] ?? s.state
            return [mode, current, target].compactMap { $0 }.joined(separator: " · ")
        case "cover":
            switch s.state {
            case "open": return "已打开"
            case "closed": return "已关闭"
            case "opening": return "打开中"
            case "closing": return "关闭中"
            default: return s.state
            }
        case "lock": return s.state == "locked" ? "已上锁" : (s.state == "unlocked" ? "未上锁" : s.state)
        case "media_player":
            switch s.state {
            case "playing": return "播放中"
            case "paused": return "已暂停"
            case "off": return "已关闭"
            case "idle", "on": return "待机"
            default: return s.state
            }
        case "binary_sensor": return s.state == "on" ? "触发" : "正常"
        case "scene", "script", "button", "input_button": return ""
        default:
            switch s.state {
            case "on": return "开"
            case "off": return "关"
            default: return s.state
            }
        }
    }
}

struct HAClient {
    let baseURL: URL
    let token: String

    private static let session: URLSession = {
        let c = URLSessionConfiguration.default
        c.timeoutIntervalForRequest = 10
        return URLSession(configuration: c)
    }()

    private func request(_ method: String, _ path: String, body: Data? = nil) async throws -> Data {
        var req = URLRequest(url: baseURL.appendingPathComponent(path))
        req.httpMethod = method
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = body
        let data: Data, response: URLResponse
        do {
            (data, response) = try await Self.session.data(for: req)
        } catch let e as URLError where e.code == .cancelled {
            throw CancellationError()
        } catch {
            throw APIError.network("连接不上 Home Assistant，请确认手机和 HA 在同一网络")
        }
        guard let http = response as? HTTPURLResponse else { throw APIError.decoding }
        switch http.statusCode {
        case 200..<300: return data
        case 401: throw APIError.unauthorized("Home Assistant 令牌无效")
        case 404: throw APIError.server(404, "找不到这个 Home Assistant 接口，请检查地址")
        default: throw APIError.server(http.statusCode, "Home Assistant 返回错误（\(http.statusCode)）")
        }
    }

    func check() async throws {
        _ = try await request("GET", "api/")
    }

    func states() async throws -> [HAState] {
        let data = try await request("GET", "api/states")
        do { return try JSONDecoder().decode([HAState].self, from: data) } catch { throw APIError.decoding }
    }

    func call(_ domain: String, _ service: String, entity: String, extra: [String: JSONValue] = [:]) async throws {
        var payload = extra
        payload["entity_id"] = .string(entity)
        let body = try JSONEncoder().encode(payload)
        _ = try await request("POST", "api/services/\(domain)/\(service)", body: body)
    }
}

/// Live device states for the furniture they're mapped onto.
@MainActor
@Observable
final class HomeAssistantManager {
    private(set) var states: [String: HAState] = [:]
    private(set) var error: String?
    private(set) var isLoading = false
    private(set) var lastUpdated: Date?

    @ObservationIgnored private let store: HomeStore
    @ObservationIgnored private var poller: Task<Void, Never>?

    init(store: HomeStore) { self.store = store }

    var isConfigured: Bool { client != nil }

    var client: HAClient? {
        let token = store.haToken.trimmed
        guard !token.isEmpty, let url = APIClient.normalize(store.settings.haURL) else { return nil }
        return HAClient(baseURL: url, token: token)
    }

    var sortedStates: [HAState] {
        states.values.sorted { ($0.domain, $0.name) < ($1.domain, $1.name) }
    }

    func refresh() async {
        guard let client else {
            states = [:]
            error = nil
            return
        }
        isLoading = true
        defer { isLoading = false }
        do {
            let list = try await client.states()
            states = Dictionary(list.map { ($0.entity_id, $0) }, uniquingKeysWith: { $1 })
            error = nil
            lastUpdated = Date()
        } catch is CancellationError {
        } catch {
            self.error = error.localizedDescription
        }
    }

    func startPolling() {
        guard poller == nil else { return }
        poller = Task { [weak self] in
            while !Task.isCancelled {
                await self?.refresh()
                try? await Task.sleep(for: .seconds(8))
            }
        }
    }

    func stopPolling() {
        poller?.cancel()
        poller = nil
    }

    /// Runs a service and refreshes so the UI shows the result.
    func perform(_ domain: String, _ service: String, entity: String, extra: [String: JSONValue] = [:]) async {
        guard let client else { return }
        do {
            try await client.call(domain, service, entity: entity, extra: extra)
            error = nil
        } catch {
            self.error = error.localizedDescription
        }
        try? await Task.sleep(for: .milliseconds(450))
        await refresh()
    }

    func toggle(_ s: HAState) async {
        let service = s.isOn ? "turn_off" : "turn_on"
        await perform(s.domain, service, entity: s.entity_id)
    }

    /// Furniture this entity is mapped onto, if any.
    func furniture(for entityId: String) -> Furniture? {
        store.furniture.values.first { $0.haEntities.contains(entityId) }
    }

    func isPowered(_ f: Furniture) -> Bool {
        f.haEntities.contains { id in
            guard let s = states[id] else { return false }
            return ["light", "switch", "fan", "media_player", "climate", "humidifier"].contains(s.domain) && s.isOn
        }
    }
}
