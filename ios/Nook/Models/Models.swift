import Foundation
import CoreGraphics

/// Anything that syncs as one server record.
protocol Entity: Codable, Identifiable, Hashable where ID == UUID {
    static var entityName: String { get }
    var id: UUID { get }
    var updatedAt: Date { get set }
}

struct RecordKey: Hashable, Codable {
    var entity: String
    var id: UUID
}

// MARK: - Icons

/// How an item is drawn when it has no photo (or the photo is hidden).
struct IconRef: Codable, Hashable {
    enum Kind: String, Codable { case symbol, emoji, custom }
    var kind: Kind
    var value: String

    static func symbol(_ name: String) -> IconRef { IconRef(kind: .symbol, value: name) }
    static func emoji(_ e: String) -> IconRef { IconRef(kind: .emoji, value: e) }
    static func custom(_ id: UUID) -> IconRef { IconRef(kind: .custom, value: id.uuidString) }

    init(kind: Kind, value: String) {
        self.kind = kind
        self.value = value
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        kind = Kind(rawValue: c.value(.kind, "symbol")) ?? .symbol
        value = c.value(.value, "shippingbox")
    }
}

// MARK: - Rooms

enum RoomType: String, Codable, CaseIterable, Identifiable {
    case living, bedroom, kitchen, bathroom, dining, study, balcony, entrance, storage, kids, other
    var id: String { rawValue }

    init(from decoder: Decoder) throws {
        let raw = try decoder.singleValueContainer().decode(String.self)
        self = RoomType(rawValue: raw) ?? .other
    }
}

struct Room: Entity {
    static let entityName = "room"
    var id = UUID()
    var name: String
    var type: RoomType
    /// Position and size on the floor plan, in centimetres.
    var x: Double = 0
    var y: Double = 0
    var width: Double
    var depth: Double
    var sortOrder: Int = 0
    var updatedAt = Date()

    var rect: CGRect { CGRect(x: x, y: y, width: width, height: depth) }

    init(id: UUID = UUID(), name: String, type: RoomType, x: Double = 0, y: Double = 0,
         width: Double, depth: Double, sortOrder: Int = 0) {
        self.id = id; self.name = name; self.type = type
        self.x = x; self.y = y; self.width = width; self.depth = depth
        self.sortOrder = sortOrder
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(UUID.self, forKey: .id)
        name = c.value(.name, "房间")
        type = c.value(.type, .other)
        x = c.value(.x, 0); y = c.value(.y, 0)
        width = max(50, c.value(.width, 300)); depth = max(50, c.value(.depth, 300))
        sortOrder = c.value(.sortOrder, 0)
        updatedAt = c.value(.updatedAt, Date())
    }
}

// MARK: - Furniture

struct Furniture: Entity {
    static let entityName = "furniture"
    var id = UUID()
    var roomId: UUID
    /// Catalog kind id (see `Catalog`), or "custom".
    var kind: String
    var name: String
    /// Top-left corner of the footprint inside the room, in centimetres.
    var x: Double
    var y: Double
    /// Unrotated size. `rotation` turns it in quarter turns clockwise.
    var width: Double
    var depth: Double
    var rotation: Int = 0
    /// Set when this piece stands on top of another (microwave on a cabinet).
    var stackedOnId: UUID?
    var colorHex: String?
    var symbol: String?
    var notes: String = ""
    /// Home Assistant entity ids mapped onto this piece.
    var haEntities: [String] = []
    var createdAt = Date()
    var updatedAt = Date()

    var footprintWidth: Double { rotation % 2 == 0 ? width : depth }
    var footprintDepth: Double { rotation % 2 == 0 ? depth : width }
    var rect: CGRect { CGRect(x: x, y: y, width: footprintWidth, height: footprintDepth) }
    var center: CGPoint { CGPoint(x: x + footprintWidth / 2, y: y + footprintDepth / 2) }

    init(id: UUID = UUID(), roomId: UUID, kind: String, name: String, x: Double, y: Double,
         width: Double, depth: Double, rotation: Int = 0, stackedOnId: UUID? = nil) {
        self.id = id; self.roomId = roomId; self.kind = kind; self.name = name
        self.x = x; self.y = y; self.width = width; self.depth = depth
        self.rotation = rotation; self.stackedOnId = stackedOnId
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(UUID.self, forKey: .id)
        roomId = try c.decode(UUID.self, forKey: .roomId)
        kind = c.value(.kind, "custom")
        name = c.value(.name, "家具")
        x = c.value(.x, 0); y = c.value(.y, 0)
        width = max(5, c.value(.width, 60)); depth = max(5, c.value(.depth, 60))
        rotation = ((c.value(.rotation, 0) % 4) + 4) % 4
        stackedOnId = c.optional(.stackedOnId)
        colorHex = c.optional(.colorHex)
        symbol = c.optional(.symbol)
        notes = c.value(.notes, "")
        haEntities = c.value(.haEntities, [])
        createdAt = c.value(.createdAt, Date())
        updatedAt = c.value(.updatedAt, Date())
    }
}

/// A place inside a piece of furniture that holds things: a shelf, a
/// drawer, the hanging rail, the dishwasher's top rack.
struct Compartment: Entity {
    static let entityName = "compartment"
    var id = UUID()
    var furnitureId: UUID
    var name: String
    var sortOrder: Int = 0
    var updatedAt = Date()

    init(id: UUID = UUID(), furnitureId: UUID, name: String, sortOrder: Int = 0) {
        self.id = id; self.furnitureId = furnitureId; self.name = name; self.sortOrder = sortOrder
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(UUID.self, forKey: .id)
        furnitureId = try c.decode(UUID.self, forKey: .furnitureId)
        name = c.value(.name, "收纳位")
        sortOrder = c.value(.sortOrder, 0)
        updatedAt = c.value(.updatedAt, Date())
    }
}

// MARK: - Items

enum CategoryTemplate: String, Codable, CaseIterable, Identifiable {
    /// Plain things: quantity, price, notes.
    case general
    /// Clothes and shoes: colours, seasons, wear log.
    case clothing
    /// Food, medicine, cosmetics: expiry date and low-stock alerts up front.
    case perishable
    var id: String { rawValue }

    init(from decoder: Decoder) throws {
        let raw = try decoder.singleValueContainer().decode(String.self)
        self = CategoryTemplate(rawValue: raw) ?? .general
    }

    var title: String {
        switch self {
        case .general: return "通用"
        case .clothing: return "衣物（颜色、季节、穿着记录）"
        case .perishable: return "消耗品（保质期、库存提醒）"
        }
    }
}

struct Category: Entity {
    static let entityName = "category"
    var id = UUID()
    var name: String
    var symbol: String
    var colorHex: String
    var template: CategoryTemplate = .general
    var sortOrder: Int = 0
    var updatedAt = Date()

    init(id: UUID = UUID(), name: String, symbol: String, colorHex: String,
         template: CategoryTemplate = .general, sortOrder: Int = 0) {
        self.id = id; self.name = name; self.symbol = symbol; self.colorHex = colorHex
        self.template = template; self.sortOrder = sortOrder
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(UUID.self, forKey: .id)
        name = c.value(.name, "分类")
        symbol = c.value(.symbol, "square.grid.2x2")
        colorHex = c.value(.colorHex, "#8E8E93")
        template = c.value(.template, .general)
        sortOrder = c.value(.sortOrder, 0)
        updatedAt = c.value(.updatedAt, Date())
    }
}

struct Item: Entity {
    static let entityName = "item"
    var id = UUID()
    /// nil = not put away yet ("未归位").
    var compartmentId: UUID?
    var name: String
    var categoryId: UUID?
    var quantity: Double = 1
    var unit: String = "个"
    var icon: IconRef?
    /// Displayed photo (the cut-out when one was made) and the untouched original.
    var photoId: String?
    var originalPhotoId: String?
    var colors: [String] = []
    var tags: [String] = []
    var notes: String = ""
    var price: Double?
    var purchasedAt: Date?
    var expiresAt: Date?
    /// Warn when quantity drops to this level or below.
    var lowStock: Double?
    var seasons: [String] = []
    var wearLog: [Date] = []
    var favorite = false
    var createdAt = Date()
    var updatedAt = Date()

    init(id: UUID = UUID(), name: String, compartmentId: UUID? = nil, categoryId: UUID? = nil) {
        self.id = id; self.name = name; self.compartmentId = compartmentId; self.categoryId = categoryId
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(UUID.self, forKey: .id)
        compartmentId = c.optional(.compartmentId)
        name = c.value(.name, "物品")
        categoryId = c.optional(.categoryId)
        quantity = c.value(.quantity, 1)
        unit = c.value(.unit, "个")
        icon = c.optional(.icon)
        photoId = c.optional(.photoId)
        originalPhotoId = c.optional(.originalPhotoId)
        colors = c.value(.colors, [])
        tags = c.value(.tags, [])
        notes = c.value(.notes, "")
        price = c.optional(.price)
        purchasedAt = c.optional(.purchasedAt)
        expiresAt = c.optional(.expiresAt)
        lowStock = c.optional(.lowStock)
        seasons = c.value(.seasons, [])
        wearLog = c.value(.wearLog, [])
        favorite = c.value(.favorite, false)
        createdAt = c.value(.createdAt, Date())
        updatedAt = c.value(.updatedAt, Date())
    }

    var isLowStock: Bool {
        guard let lowStock else { return false }
        return quantity <= lowStock
    }

    var isExpired: Bool { (expiresAt?.daysFromToday ?? 1) < 0 }
    var isExpiringSoon: Bool {
        guard let d = expiresAt?.daysFromToday else { return false }
        return d >= 0 && d <= 7
    }
}

/// An icon drawn by the AI (or imported), stored as sanitised SVG.
struct CustomIcon: Entity {
    static let entityName = "icon"
    var id = UUID()
    var name: String
    var svg: String
    var createdAt = Date()
    var updatedAt = Date()

    init(id: UUID = UUID(), name: String, svg: String) {
        self.id = id; self.name = name; self.svg = svg
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(UUID.self, forKey: .id)
        name = c.value(.name, "图标")
        svg = c.value(.svg, "")
        createdAt = c.value(.createdAt, Date())
        updatedAt = c.value(.updatedAt, Date())
    }
}

/// Household-wide settings shared by every device on the account. The Home
/// Assistant token is not among them: it stays on each device (Keychain).
struct HomeSettings: Entity {
    static let entityName = "settings"
    static let singletonId = UUID(uuidString: "00000000-0000-0000-0000-00000000A001")!
    var id = HomeSettings.singletonId
    var homeName = "我的家"
    var haURL = ""
    var updatedAt = Date(timeIntervalSince1970: 0)
    /// Read from older versions, which synced the token; never written.
    var legacyHAToken = ""

    enum CodingKeys: String, CodingKey { case id, homeName, haURL, haToken, updatedAt }

    init() {}

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = HomeSettings.singletonId
        homeName = c.value(.homeName, "我的家")
        haURL = c.value(.haURL, "")
        legacyHAToken = c.value(.haToken, "")
        updatedAt = c.value(.updatedAt, Date(timeIntervalSince1970: 0))
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(id, forKey: .id)
        try c.encode(homeName, forKey: .homeName)
        try c.encode(haURL, forKey: .haURL)
        try c.encode(updatedAt, forKey: .updatedAt)
    }
}

enum DeletePolicy {
    /// Keep the items, mark them as not put away.
    case keepItems
    /// Delete the items too.
    case deleteItems
}
