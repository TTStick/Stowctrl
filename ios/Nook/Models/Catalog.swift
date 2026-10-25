import SwiftUI

// MARK: - Furniture catalog

/// How a piece is drawn from above on the plan.
enum FurnitureStyle: String {
    case cabinet, wardrobe, drawers, shelf, bed, sofa, armchair, table, dining, desk, chair
    case tv, fridge, stove, sink, toilet, bathtub, shower, washer, appliance
    case door, window, wall, pillar, plant, rug, lamp, box, basket, ac, generic
}

enum FurnitureGroup: String, CaseIterable, Identifiable {
    case bedroom, living, kitchen, dining, bathroom, study, storage, structure
    var id: String { rawValue }

    var title: String {
        switch self {
        case .bedroom: return "卧室"
        case .living: return "客厅"
        case .kitchen: return "厨房"
        case .dining: return "餐厅"
        case .bathroom: return "卫浴"
        case .study: return "书房"
        case .storage: return "收纳"
        case .structure: return "门窗结构"
        }
    }
}

enum Material: String, CaseIterable {
    case wood, lightWood, fabric, white, metal, ceramic, dark, green, glass, rug, cardboard

    var fill: Color {
        switch self {
        case .wood: return .adaptive("#C9A27C", "#7A5E45")
        case .lightWood: return .adaptive("#E6CFAF", "#8C7558")
        case .fabric: return .adaptive("#A9BCCB", "#4E6170")
        case .white: return .adaptive("#F7F8FA", "#5A5F66")
        case .metal: return .adaptive("#C7CCD1", "#5E646B")
        case .ceramic: return .adaptive("#FFFFFF", "#6B7078")
        case .dark: return .adaptive("#3C4148", "#22262B")
        case .green: return .adaptive("#8CC084", "#4E7A48")
        case .glass: return .adaptive("#CFE6F3", "#3E5A6B")
        case .rug: return .adaptive("#E7C9C0", "#6E4E46")
        case .cardboard: return .adaptive("#D9B98C", "#806848")
        }
    }

    var stroke: Color {
        switch self {
        case .wood: return .adaptive("#8A6646", "#C9A27C")
        case .lightWood: return .adaptive("#A98A64", "#D2B893")
        case .fabric: return .adaptive("#6E8597", "#93A9BA")
        case .white, .ceramic: return .adaptive("#9AA1A9", "#A9AFB6")
        case .metal: return .adaptive("#878E95", "#A3A9AF")
        case .dark: return .adaptive("#1E2125", "#80868D")
        case .green: return .adaptive("#5A8F52", "#8CC084")
        case .glass: return .adaptive("#7FA8BF", "#8FB7CC")
        case .rug: return .adaptive("#B98F84", "#A87F75")
        case .cardboard: return .adaptive("#A58457", "#C9A87A")
        }
    }
}

/// Drawing order: rugs below everything, wall-mounted pieces on top.
enum FurnitureLayer: Int, Comparable {
    case floor = 0, standard = 1, wall = 2
    static func < (a: FurnitureLayer, b: FurnitureLayer) -> Bool { a.rawValue < b.rawValue }
}

struct FurnitureKind: Identifiable, Hashable {
    let id: String
    let name: String
    let group: FurnitureGroup
    let style: FurnitureStyle
    let material: Material
    let width: Double
    let depth: Double
    let symbol: String
    let compartments: [String]
    var layer: FurnitureLayer = .standard

    static func == (a: FurnitureKind, b: FurnitureKind) -> Bool { a.id == b.id }
    func hash(into h: inout Hasher) { h.combine(id) }
}

enum Catalog {
    static let customKindId = "custom"

    static let kinds: [FurnitureKind] = [
        // 卧室
        .init(id: "bed_double", name: "双人床", group: .bedroom, style: .bed, material: .wood, width: 180, depth: 200, symbol: "bed.double", compartments: ["床下收纳"]),
        .init(id: "bed_single", name: "单人床", group: .bedroom, style: .bed, material: .wood, width: 120, depth: 200, symbol: "bed.double", compartments: ["床下收纳"]),
        .init(id: "nightstand", name: "床头柜", group: .bedroom, style: .drawers, material: .wood, width: 45, depth: 40, symbol: "cabinet", compartments: ["台面", "抽屉"]),
        .init(id: "wardrobe", name: "衣柜", group: .bedroom, style: .wardrobe, material: .wood, width: 160, depth: 60, symbol: "cabinet", compartments: ["挂衣区", "叠放区", "抽屉"]),
        .init(id: "dresser", name: "五斗柜", group: .bedroom, style: .drawers, material: .wood, width: 100, depth: 45, symbol: "cabinet", compartments: ["抽屉 1", "抽屉 2", "抽屉 3", "抽屉 4", "抽屉 5"]),
        .init(id: "vanity", name: "梳妆台", group: .bedroom, style: .desk, material: .lightWood, width: 100, depth: 45, symbol: "comb", compartments: ["台面", "抽屉"]),
        // 客厅
        .init(id: "sofa", name: "沙发", group: .living, style: .sofa, material: .fabric, width: 220, depth: 90, symbol: "sofa", compartments: []),
        .init(id: "armchair", name: "单人沙发", group: .living, style: .armchair, material: .fabric, width: 90, depth: 85, symbol: "chair.lounge", compartments: []),
        .init(id: "coffee_table", name: "茶几", group: .living, style: .table, material: .lightWood, width: 120, depth: 60, symbol: "table.furniture", compartments: ["台面", "下层"]),
        .init(id: "tv_stand", name: "电视柜", group: .living, style: .cabinet, material: .wood, width: 200, depth: 45, symbol: "tv", compartments: ["台面", "左柜", "右柜", "抽屉"]),
        .init(id: "tv", name: "电视", group: .living, style: .tv, material: .dark, width: 140, depth: 10, symbol: "tv", compartments: []),
        .init(id: "bookshelf", name: "书架", group: .living, style: .shelf, material: .wood, width: 100, depth: 35, symbol: "books.vertical", compartments: ["第 1 层", "第 2 层", "第 3 层", "第 4 层"]),
        .init(id: "display_cabinet", name: "展示柜", group: .living, style: .cabinet, material: .lightWood, width: 100, depth: 40, symbol: "cabinet", compartments: ["上层", "中层", "下层"]),
        .init(id: "side_table", name: "边几", group: .living, style: .table, material: .lightWood, width: 50, depth: 50, symbol: "table.furniture", compartments: ["台面"]),
        .init(id: "rug", name: "地毯", group: .living, style: .rug, material: .rug, width: 200, depth: 140, symbol: "rectangle.dashed", compartments: [], layer: .floor),
        .init(id: "plant", name: "绿植", group: .living, style: .plant, material: .green, width: 50, depth: 50, symbol: "leaf", compartments: []),
        .init(id: "floor_lamp", name: "落地灯", group: .living, style: .lamp, material: .white, width: 40, depth: 40, symbol: "lamp.floor", compartments: []),
        .init(id: "air_conditioner", name: "空调", group: .living, style: .ac, material: .white, width: 90, depth: 25, symbol: "air.conditioner.horizontal", compartments: [], layer: .wall),
        // 厨房
        .init(id: "base_cabinet", name: "地柜", group: .kitchen, style: .cabinet, material: .lightWood, width: 120, depth: 60, symbol: "cabinet", compartments: ["台面", "左柜", "右柜", "抽屉"]),
        .init(id: "wall_cabinet", name: "吊柜", group: .kitchen, style: .cabinet, material: .lightWood, width: 120, depth: 35, symbol: "cabinet", compartments: ["左格", "右格"], layer: .wall),
        .init(id: "fridge", name: "冰箱", group: .kitchen, style: .fridge, material: .metal, width: 70, depth: 70, symbol: "refrigerator", compartments: ["冷藏上层", "冷藏中层", "冷藏下层", "果蔬盒", "门架", "冷冻室"]),
        .init(id: "stove", name: "灶台", group: .kitchen, style: .stove, material: .metal, width: 80, depth: 55, symbol: "stove", compartments: ["灶下柜"]),
        .init(id: "kitchen_sink", name: "水槽", group: .kitchen, style: .sink, material: .metal, width: 80, depth: 55, symbol: "sink", compartments: ["水槽下柜"]),
        .init(id: "dishwasher", name: "洗碗机", group: .kitchen, style: .appliance, material: .metal, width: 60, depth: 60, symbol: "dishwasher", compartments: ["上层碗篮", "下层碗篮", "餐具篮"]),
        .init(id: "microwave", name: "微波炉", group: .kitchen, style: .appliance, material: .white, width: 50, depth: 40, symbol: "microwave", compartments: ["内部"]),
        .init(id: "oven", name: "烤箱", group: .kitchen, style: .appliance, material: .dark, width: 60, depth: 55, symbol: "oven", compartments: ["内部", "烤盘"]),
        .init(id: "kitchen_rack", name: "厨房置物架", group: .kitchen, style: .shelf, material: .metal, width: 60, depth: 30, symbol: "square.stack.3d.up", compartments: ["挂杆", "上层", "下层"]),
        .init(id: "spice_rack", name: "调料架", group: .kitchen, style: .shelf, material: .wood, width: 40, depth: 20, symbol: "square.stack.3d.up", compartments: ["上层", "下层"]),
        .init(id: "pantry", name: "储物柜", group: .kitchen, style: .cabinet, material: .lightWood, width: 80, depth: 50, symbol: "cabinet", compartments: ["上层", "中层", "下层"]),
        // 餐厅
        .init(id: "dining_set", name: "餐桌椅", group: .dining, style: .dining, material: .lightWood, width: 160, depth: 140, symbol: "fork.knife", compartments: ["桌面"]),
        .init(id: "chair", name: "椅子", group: .dining, style: .chair, material: .lightWood, width: 45, depth: 45, symbol: "chair", compartments: []),
        .init(id: "sideboard", name: "餐边柜", group: .dining, style: .cabinet, material: .wood, width: 120, depth: 45, symbol: "cabinet", compartments: ["台面", "左柜", "右柜", "抽屉"]),
        // 卫浴
        .init(id: "toilet", name: "马桶", group: .bathroom, style: .toilet, material: .ceramic, width: 40, depth: 70, symbol: "toilet", compartments: ["水箱上"]),
        .init(id: "bath_vanity", name: "浴室柜", group: .bathroom, style: .sink, material: .white, width: 80, depth: 50, symbol: "sink", compartments: ["台面", "柜内", "抽屉"]),
        .init(id: "mirror_cabinet", name: "镜柜", group: .bathroom, style: .cabinet, material: .glass, width: 80, depth: 15, symbol: "cabinet", compartments: ["上层", "下层"], layer: .wall),
        .init(id: "shower", name: "淋浴区", group: .bathroom, style: .shower, material: .ceramic, width: 90, depth: 90, symbol: "shower", compartments: ["置物架"]),
        .init(id: "bathtub", name: "浴缸", group: .bathroom, style: .bathtub, material: .ceramic, width: 170, depth: 75, symbol: "bathtub", compartments: ["浴缸边"]),
        .init(id: "washer", name: "洗衣机", group: .bathroom, style: .washer, material: .white, width: 60, depth: 60, symbol: "washer", compartments: ["滚筒", "洗衣液盒"]),
        .init(id: "dryer", name: "烘干机", group: .bathroom, style: .washer, material: .white, width: 60, depth: 60, symbol: "dryer", compartments: ["滚筒"]),
        .init(id: "bath_shelf", name: "卫浴置物架", group: .bathroom, style: .shelf, material: .metal, width: 40, depth: 30, symbol: "square.stack.3d.up", compartments: ["上层", "中层", "下层"]),
        .init(id: "laundry_basket", name: "脏衣篮", group: .bathroom, style: .basket, material: .fabric, width: 40, depth: 40, symbol: "basket", compartments: ["篮内"]),
        // 书房
        .init(id: "desk", name: "书桌", group: .study, style: .desk, material: .lightWood, width: 120, depth: 60, symbol: "desktopcomputer", compartments: ["桌面", "抽屉"]),
        .init(id: "office_chair", name: "办公椅", group: .study, style: .chair, material: .dark, width: 55, depth: 55, symbol: "chair", compartments: []),
        .init(id: "filing_cabinet", name: "文件柜", group: .study, style: .drawers, material: .metal, width: 45, depth: 55, symbol: "archivebox", compartments: ["抽屉 1", "抽屉 2", "抽屉 3"]),
        // 收纳
        .init(id: "shelf_unit", name: "置物架", group: .storage, style: .shelf, material: .metal, width: 90, depth: 40, symbol: "square.stack.3d.up", compartments: ["第 1 层", "第 2 层", "第 3 层"]),
        .init(id: "drawer_unit", name: "抽屉柜", group: .storage, style: .drawers, material: .white, width: 45, depth: 40, symbol: "archivebox", compartments: ["抽屉 1", "抽屉 2", "抽屉 3"]),
        .init(id: "storage_box", name: "收纳箱", group: .storage, style: .box, material: .cardboard, width: 50, depth: 35, symbol: "shippingbox", compartments: ["箱内"]),
        .init(id: "storage_basket", name: "收纳篮", group: .storage, style: .basket, material: .lightWood, width: 35, depth: 25, symbol: "basket", compartments: ["篮内"]),
        .init(id: "storage_cart", name: "收纳推车", group: .storage, style: .shelf, material: .white, width: 45, depth: 35, symbol: "cart", compartments: ["上层", "中层", "下层"]),
        .init(id: "shoe_cabinet", name: "鞋柜", group: .storage, style: .cabinet, material: .wood, width: 100, depth: 35, symbol: "shoeprints.fill", compartments: ["上层", "中层", "下层"]),
        .init(id: "safe", name: "保险箱", group: .storage, style: .box, material: .dark, width: 40, depth: 40, symbol: "lock", compartments: ["内部"]),
        // 门窗结构
        .init(id: "door", name: "门", group: .structure, style: .door, material: .wood, width: 90, depth: 90, symbol: "door.left.hand.open", compartments: []),
        .init(id: "window", name: "窗", group: .structure, style: .window, material: .glass, width: 120, depth: 12, symbol: "window.casement", compartments: []),
        .init(id: "wall", name: "隔墙", group: .structure, style: .wall, material: .dark, width: 200, depth: 10, symbol: "rectangle.portrait", compartments: []),
        .init(id: "pillar", name: "柱子", group: .structure, style: .pillar, material: .dark, width: 40, depth: 40, symbol: "square.fill", compartments: []),
    ]

    static let custom = FurnitureKind(id: customKindId, name: "自定义家具", group: .storage, style: .cabinet,
                                      material: .wood, width: 80, depth: 40, symbol: "cube", compartments: ["内部"])

    private static let byId: [String: FurnitureKind] = Dictionary(uniqueKeysWithValues: kinds.map { ($0.id, $0) })

    static func kind(_ id: String) -> FurnitureKind { byId[id] ?? custom }

    static func kinds(in group: FurnitureGroup) -> [FurnitureKind] { kinds.filter { $0.group == group } }

    /// Colours offered for custom furniture.
    static let customColors = ["#C9A27C", "#E6CFAF", "#A9BCCB", "#C7CCD1", "#F2F2F2", "#8CC084",
                               "#E7C9C0", "#D9B98C", "#B5A4D6", "#F2C46D", "#7FB5D9", "#3C4148"]
}

// MARK: - Rooms

struct RoomTypeInfo {
    let title: String
    let symbol: String
    let width: Double
    let depth: Double
    let floor: Color
    /// Furniture kinds suggested first in the palette.
    let suggested: [String]
}

struct PresetPiece {
    let kind: String
    let x: Double
    let y: Double
    var rotation: Int = 0
    /// Index into the same preset list of the piece this one stands on.
    var stackedOn: Int?
}

extension RoomType {
    var info: RoomTypeInfo {
        switch self {
        case .living: return .init(title: "客厅", symbol: "sofa", width: 500, depth: 400, floor: .adaptive("#F3EDE3", "#2E2A25"), suggested: ["sofa", "coffee_table", "tv_stand", "tv", "bookshelf", "armchair", "rug", "plant", "air_conditioner"])
        case .bedroom: return .init(title: "卧室", symbol: "bed.double", width: 400, depth: 360, floor: .adaptive("#EEF0F7", "#272A31"), suggested: ["bed_double", "nightstand", "wardrobe", "dresser", "vanity", "air_conditioner"])
        case .kitchen: return .init(title: "厨房", symbol: "fork.knife", width: 300, depth: 250, floor: .adaptive("#EDF3EC", "#252B25"), suggested: ["fridge", "base_cabinet", "wall_cabinet", "stove", "kitchen_sink", "dishwasher", "microwave", "oven", "kitchen_rack", "spice_rack", "pantry"])
        case .bathroom: return .init(title: "卫生间", symbol: "shower", width: 250, depth: 200, floor: .adaptive("#E8F2F6", "#232B2F"), suggested: ["toilet", "bath_vanity", "mirror_cabinet", "shower", "bathtub", "washer", "bath_shelf", "laundry_basket"])
        case .dining: return .init(title: "餐厅", symbol: "fork.knife", width: 350, depth: 300, floor: .adaptive("#F6EFE6", "#2E2924"), suggested: ["dining_set", "sideboard", "chair", "pantry"])
        case .study: return .init(title: "书房", symbol: "books.vertical", width: 300, depth: 300, floor: .adaptive("#F1EEE8", "#2B2926"), suggested: ["desk", "office_chair", "bookshelf", "filing_cabinet"])
        case .balcony: return .init(title: "阳台", symbol: "sun.max", width: 300, depth: 150, floor: .adaptive("#EDF3EA", "#262B24"), suggested: ["washer", "dryer", "shelf_unit", "plant", "storage_cart"])
        case .entrance: return .init(title: "玄关", symbol: "door.left.hand.open", width: 200, depth: 150, floor: .adaptive("#F2F0EC", "#2A2927"), suggested: ["shoe_cabinet", "door", "storage_basket"])
        case .storage: return .init(title: "储物间", symbol: "shippingbox", width: 250, depth: 200, floor: .adaptive("#EFEDEA", "#292826"), suggested: ["shelf_unit", "storage_box", "drawer_unit", "storage_cart", "safe"])
        case .kids: return .init(title: "儿童房", symbol: "teddybear", width: 350, depth: 300, floor: .adaptive("#F7EEF0", "#2F282A"), suggested: ["bed_single", "wardrobe", "desk", "storage_box", "bookshelf"])
        case .other: return .init(title: "其他", symbol: "square.dashed", width: 300, depth: 300, floor: .adaptive("#F0F0F0", "#2A2A2A"), suggested: ["shelf_unit", "storage_box", "display_cabinet"])
        }
    }

    /// A sensible starting layout for the room's default size.
    var preset: [PresetPiece] {
        switch self {
        case .living:
            return [.init(kind: "rug", x: 150, y: 150),
                    .init(kind: "sofa", x: 140, y: 300, rotation: 2),
                    .init(kind: "coffee_table", x: 190, y: 190),
                    .init(kind: "tv_stand", x: 150, y: 10),
                    .init(kind: "tv", x: 180, y: 28, stackedOn: 3),
                    .init(kind: "bookshelf", x: 5, y: 120, rotation: 3),
                    .init(kind: "plant", x: 440, y: 10)]
        case .bedroom:
            return [.init(kind: "bed_double", x: 110, y: 10),
                    .init(kind: "nightstand", x: 60, y: 10),
                    .init(kind: "nightstand", x: 295, y: 10),
                    .init(kind: "wardrobe", x: 120, y: 295, rotation: 2)]
        case .kitchen:
            return [.init(kind: "fridge", x: 5, y: 5),
                    .init(kind: "base_cabinet", x: 80, y: 5),
                    .init(kind: "microwave", x: 90, y: 12, stackedOn: 1),
                    .init(kind: "kitchen_sink", x: 205, y: 5),
                    .init(kind: "stove", x: 240, y: 75, rotation: 3),
                    .init(kind: "dishwasher", x: 235, y: 165, rotation: 3),
                    .init(kind: "kitchen_rack", x: 5, y: 215, rotation: 2)]
        case .bathroom:
            return [.init(kind: "toilet", x: 10, y: 5),
                    .init(kind: "bath_vanity", x: 70, y: 5),
                    .init(kind: "shower", x: 155, y: 5),
                    .init(kind: "washer", x: 185, y: 135, rotation: 2),
                    .init(kind: "bath_shelf", x: 10, y: 165, rotation: 2)]
        case .dining:
            return [.init(kind: "dining_set", x: 95, y: 90),
                    .init(kind: "sideboard", x: 115, y: 5)]
        case .study:
            return [.init(kind: "desk", x: 90, y: 5),
                    .init(kind: "office_chair", x: 122, y: 75),
                    .init(kind: "bookshelf", x: 5, y: 100, rotation: 3),
                    .init(kind: "filing_cabinet", x: 240, y: 10)]
        case .balcony:
            return [.init(kind: "washer", x: 10, y: 10),
                    .init(kind: "dryer", x: 10, y: 10, stackedOn: 0),
                    .init(kind: "shelf_unit", x: 100, y: 5),
                    .init(kind: "plant", x: 235, y: 85)]
        case .entrance:
            return [.init(kind: "shoe_cabinet", x: 10, y: 5),
                    .init(kind: "door", x: 100, y: 55)]
        case .storage:
            return [.init(kind: "shelf_unit", x: 5, y: 5),
                    .init(kind: "shelf_unit", x: 100, y: 5),
                    .init(kind: "storage_box", x: 10, y: 150),
                    .init(kind: "storage_box", x: 70, y: 150)]
        case .kids:
            return [.init(kind: "bed_single", x: 10, y: 10),
                    .init(kind: "wardrobe", x: 185, y: 235, rotation: 2),
                    .init(kind: "storage_box", x: 200, y: 20)]
        case .other:
            return []
        }
    }
}

// MARK: - Items

enum DefaultCategories {
    private static func id(_ n: Int) -> UUID {
        UUID(uuidString: String(format: "00000000-0000-0000-0000-0000000C%04d", n))!
    }

    /// Fixed ids so two devices seeding defaults end up with the same records.
    static let all: [Category] = [
        Category(id: id(1), name: "衣物", symbol: "tshirt", colorHex: "#7E8CE0", template: .clothing, sortOrder: 0),
        Category(id: id(2), name: "鞋包配饰", symbol: "bag", colorHex: "#B07CD8", template: .clothing, sortOrder: 1),
        Category(id: id(3), name: "食品", symbol: "carrot", colorHex: "#E9965A", template: .perishable, sortOrder: 2),
        Category(id: id(4), name: "饮品", symbol: "cup.and.saucer", colorHex: "#C98B5E", template: .perishable, sortOrder: 3),
        Category(id: id(5), name: "餐厨用具", symbol: "fork.knife", colorHex: "#5FA8A0", sortOrder: 4),
        Category(id: id(6), name: "日用品", symbol: "shippingbox", colorHex: "#6FA0D8", sortOrder: 5),
        Category(id: id(7), name: "洗护美妆", symbol: "bubbles.and.sparkles", colorHex: "#E58FB0", template: .perishable, sortOrder: 6),
        Category(id: id(8), name: "药品", symbol: "pills", colorHex: "#E06C6C", template: .perishable, sortOrder: 7),
        Category(id: id(9), name: "电子数码", symbol: "headphones", colorHex: "#5C6B7A", sortOrder: 8),
        Category(id: id(10), name: "工具", symbol: "wrench.and.screwdriver", colorHex: "#8C7A5B", sortOrder: 9),
        Category(id: id(11), name: "书籍文具", symbol: "book", colorHex: "#6C9A5B", sortOrder: 10),
        Category(id: id(12), name: "证件文件", symbol: "doc.text", colorHex: "#A0A060", sortOrder: 11),
        Category(id: id(13), name: "玩具爱好", symbol: "gamecontroller", colorHex: "#F0B43C", sortOrder: 12),
        Category(id: id(14), name: "其他", symbol: "square.grid.2x2", colorHex: "#8E8E93", sortOrder: 13),
    ]

    static let colors = ["#7E8CE0", "#B07CD8", "#E9965A", "#C98B5E", "#5FA8A0", "#6FA0D8", "#E58FB0",
                         "#E06C6C", "#5C6B7A", "#8C7A5B", "#6C9A5B", "#A0A060", "#F0B43C", "#8E8E93"]
}

enum Units {
    static let all = ["个", "件", "双", "套", "条", "张", "盒", "瓶", "罐", "包", "袋", "箱",
                      "卷", "支", "本", "台", "副", "片", "千克", "克", "升", "毫升"]
}

enum Seasons {
    static let all = ["春", "夏", "秋", "冬"]
}
