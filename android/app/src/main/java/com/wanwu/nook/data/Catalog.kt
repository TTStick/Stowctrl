package com.wanwu.nook.data

/*
 * Kept in step with ios/Nook/Models/Catalog.swift: kind ids, sizes, default
 * compartments and preset layouts must match so both apps draw the same home.
 */

enum class FurnitureStyle {
    cabinet, wardrobe, drawers, shelf, bed, sofa, armchair, table, dining, desk, chair,
    tv, fridge, stove, sink, toilet, bathtub, shower, washer, appliance,
    door, window, wall, pillar, plant, rug, lamp, box, basket, ac, generic
}

enum class FurnitureGroup(val title: String) {
    bedroom("卧室"), living("客厅"), kitchen("厨房"), dining("餐厅"), bathroom("卫浴"),
    study("书房"), storage("收纳"), structure("门窗结构")
}

enum class MaterialKind(val lightFill: Long, val darkFill: Long, val lightStroke: Long, val darkStroke: Long) {
    wood(0xFFC9A27C, 0xFF7A5E45, 0xFF8A6646, 0xFFC9A27C),
    lightWood(0xFFE6CFAF, 0xFF8C7558, 0xFFA98A64, 0xFFD2B893),
    fabric(0xFFA9BCCB, 0xFF4E6170, 0xFF6E8597, 0xFF93A9BA),
    white(0xFFF7F8FA, 0xFF5A5F66, 0xFF9AA1A9, 0xFFA9AFB6),
    metal(0xFFC7CCD1, 0xFF5E646B, 0xFF878E95, 0xFFA3A9AF),
    ceramic(0xFFFFFFFF, 0xFF6B7078, 0xFF9AA1A9, 0xFFA9AFB6),
    dark(0xFF3C4148, 0xFF22262B, 0xFF1E2125, 0xFF80868D),
    green(0xFF8CC084, 0xFF4E7A48, 0xFF5A8F52, 0xFF8CC084),
    glass(0xFFCFE6F3, 0xFF3E5A6B, 0xFF7FA8BF, 0xFF8FB7CC),
    rug(0xFFE7C9C0, 0xFF6E4E46, 0xFFB98F84, 0xFFA87F75),
    cardboard(0xFFD9B98C, 0xFF806848, 0xFFA58457, 0xFFC9A87A),
}

enum class FurnitureLayer { floor, standard, wall }

data class FurnitureKind(
    val id: String,
    val name: String,
    val group: FurnitureGroup,
    val style: FurnitureStyle,
    val material: MaterialKind,
    val width: Double,
    val depth: Double,
    val symbol: String,
    val compartments: List<String>,
    val layer: FurnitureLayer = FurnitureLayer.standard,
)

object Catalog {
    const val CUSTOM = "custom"

    private fun k(id: String, name: String, g: FurnitureGroup, s: FurnitureStyle, m: MaterialKind, w: Int, d: Int,
                  symbol: String, comps: List<String>, layer: FurnitureLayer = FurnitureLayer.standard) =
        FurnitureKind(id, name, g, s, m, w.toDouble(), d.toDouble(), symbol, comps, layer)

    private val B = FurnitureGroup.bedroom; private val L = FurnitureGroup.living; private val K = FurnitureGroup.kitchen
    private val D = FurnitureGroup.dining; private val T = FurnitureGroup.bathroom; private val S = FurnitureGroup.study
    private val O = FurnitureGroup.storage; private val X = FurnitureGroup.structure

    val kinds: List<FurnitureKind> = listOf(
        k("bed_double", "双人床", B, FurnitureStyle.bed, MaterialKind.wood, 180, 200, "bed.double", listOf("床下收纳")),
        k("bed_single", "单人床", B, FurnitureStyle.bed, MaterialKind.wood, 120, 200, "bed.double", listOf("床下收纳")),
        k("nightstand", "床头柜", B, FurnitureStyle.drawers, MaterialKind.wood, 45, 40, "cabinet", listOf("台面", "抽屉")),
        k("wardrobe", "衣柜", B, FurnitureStyle.wardrobe, MaterialKind.wood, 160, 60, "cabinet", listOf("挂衣区", "叠放区", "抽屉")),
        k("dresser", "五斗柜", B, FurnitureStyle.drawers, MaterialKind.wood, 100, 45, "cabinet", listOf("抽屉 1", "抽屉 2", "抽屉 3", "抽屉 4", "抽屉 5")),
        k("vanity", "梳妆台", B, FurnitureStyle.desk, MaterialKind.lightWood, 100, 45, "comb", listOf("台面", "抽屉")),
        k("sofa", "沙发", L, FurnitureStyle.sofa, MaterialKind.fabric, 220, 90, "sofa", listOf()),
        k("armchair", "单人沙发", L, FurnitureStyle.armchair, MaterialKind.fabric, 90, 85, "chair.lounge", listOf()),
        k("coffee_table", "茶几", L, FurnitureStyle.table, MaterialKind.lightWood, 120, 60, "table.furniture", listOf("台面", "下层")),
        k("tv_stand", "电视柜", L, FurnitureStyle.cabinet, MaterialKind.wood, 200, 45, "tv", listOf("台面", "左柜", "右柜", "抽屉")),
        k("tv", "电视", L, FurnitureStyle.tv, MaterialKind.dark, 140, 10, "tv", listOf()),
        k("bookshelf", "书架", L, FurnitureStyle.shelf, MaterialKind.wood, 100, 35, "books.vertical", listOf("第 1 层", "第 2 层", "第 3 层", "第 4 层")),
        k("display_cabinet", "展示柜", L, FurnitureStyle.cabinet, MaterialKind.lightWood, 100, 40, "cabinet", listOf("上层", "中层", "下层")),
        k("side_table", "边几", L, FurnitureStyle.table, MaterialKind.lightWood, 50, 50, "table.furniture", listOf("台面")),
        k("rug", "地毯", L, FurnitureStyle.rug, MaterialKind.rug, 200, 140, "rectangle.dashed", listOf(), FurnitureLayer.floor),
        k("plant", "绿植", L, FurnitureStyle.plant, MaterialKind.green, 50, 50, "leaf", listOf()),
        k("floor_lamp", "落地灯", L, FurnitureStyle.lamp, MaterialKind.white, 40, 40, "lamp.floor", listOf()),
        k("air_conditioner", "空调", L, FurnitureStyle.ac, MaterialKind.white, 90, 25, "air.conditioner.horizontal", listOf(), FurnitureLayer.wall),
        k("base_cabinet", "地柜", K, FurnitureStyle.cabinet, MaterialKind.lightWood, 120, 60, "cabinet", listOf("台面", "左柜", "右柜", "抽屉")),
        k("wall_cabinet", "吊柜", K, FurnitureStyle.cabinet, MaterialKind.lightWood, 120, 35, "cabinet", listOf("左格", "右格"), FurnitureLayer.wall),
        k("fridge", "冰箱", K, FurnitureStyle.fridge, MaterialKind.metal, 70, 70, "refrigerator", listOf("冷藏上层", "冷藏中层", "冷藏下层", "果蔬盒", "门架", "冷冻室")),
        k("stove", "灶台", K, FurnitureStyle.stove, MaterialKind.metal, 80, 55, "stove", listOf("灶下柜")),
        k("kitchen_sink", "水槽", K, FurnitureStyle.sink, MaterialKind.metal, 80, 55, "sink", listOf("水槽下柜")),
        k("dishwasher", "洗碗机", K, FurnitureStyle.appliance, MaterialKind.metal, 60, 60, "dishwasher", listOf("上层碗篮", "下层碗篮", "餐具篮")),
        k("microwave", "微波炉", K, FurnitureStyle.appliance, MaterialKind.white, 50, 40, "microwave", listOf("内部")),
        k("oven", "烤箱", K, FurnitureStyle.appliance, MaterialKind.dark, 60, 55, "oven", listOf("内部", "烤盘")),
        k("kitchen_rack", "厨房置物架", K, FurnitureStyle.shelf, MaterialKind.metal, 60, 30, "square.stack.3d.up", listOf("挂杆", "上层", "下层")),
        k("spice_rack", "调料架", K, FurnitureStyle.shelf, MaterialKind.wood, 40, 20, "square.stack.3d.up", listOf("上层", "下层")),
        k("pantry", "储物柜", K, FurnitureStyle.cabinet, MaterialKind.lightWood, 80, 50, "cabinet", listOf("上层", "中层", "下层")),
        k("dining_set", "餐桌椅", D, FurnitureStyle.dining, MaterialKind.lightWood, 160, 140, "fork.knife", listOf("桌面")),
        k("chair", "椅子", D, FurnitureStyle.chair, MaterialKind.lightWood, 45, 45, "chair", listOf()),
        k("sideboard", "餐边柜", D, FurnitureStyle.cabinet, MaterialKind.wood, 120, 45, "cabinet", listOf("台面", "左柜", "右柜", "抽屉")),
        k("toilet", "马桶", T, FurnitureStyle.toilet, MaterialKind.ceramic, 40, 70, "toilet", listOf("水箱上")),
        k("bath_vanity", "浴室柜", T, FurnitureStyle.sink, MaterialKind.white, 80, 50, "sink", listOf("台面", "柜内", "抽屉")),
        k("mirror_cabinet", "镜柜", T, FurnitureStyle.cabinet, MaterialKind.glass, 80, 15, "cabinet", listOf("上层", "下层"), FurnitureLayer.wall),
        k("shower", "淋浴区", T, FurnitureStyle.shower, MaterialKind.ceramic, 90, 90, "shower", listOf("置物架")),
        k("bathtub", "浴缸", T, FurnitureStyle.bathtub, MaterialKind.ceramic, 170, 75, "bathtub", listOf("浴缸边")),
        k("washer", "洗衣机", T, FurnitureStyle.washer, MaterialKind.white, 60, 60, "washer", listOf("滚筒", "洗衣液盒")),
        k("dryer", "烘干机", T, FurnitureStyle.washer, MaterialKind.white, 60, 60, "dryer", listOf("滚筒")),
        k("bath_shelf", "卫浴置物架", T, FurnitureStyle.shelf, MaterialKind.metal, 40, 30, "square.stack.3d.up", listOf("上层", "中层", "下层")),
        k("laundry_basket", "脏衣篮", T, FurnitureStyle.basket, MaterialKind.fabric, 40, 40, "basket", listOf("篮内")),
        k("desk", "书桌", S, FurnitureStyle.desk, MaterialKind.lightWood, 120, 60, "desktopcomputer", listOf("桌面", "抽屉")),
        k("office_chair", "办公椅", S, FurnitureStyle.chair, MaterialKind.dark, 55, 55, "chair", listOf()),
        k("filing_cabinet", "文件柜", S, FurnitureStyle.drawers, MaterialKind.metal, 45, 55, "archivebox", listOf("抽屉 1", "抽屉 2", "抽屉 3")),
        k("shelf_unit", "置物架", O, FurnitureStyle.shelf, MaterialKind.metal, 90, 40, "square.stack.3d.up", listOf("第 1 层", "第 2 层", "第 3 层")),
        k("drawer_unit", "抽屉柜", O, FurnitureStyle.drawers, MaterialKind.white, 45, 40, "archivebox", listOf("抽屉 1", "抽屉 2", "抽屉 3")),
        k("storage_box", "收纳箱", O, FurnitureStyle.box, MaterialKind.cardboard, 50, 35, "shippingbox", listOf("箱内")),
        k("storage_basket", "收纳篮", O, FurnitureStyle.basket, MaterialKind.lightWood, 35, 25, "basket", listOf("篮内")),
        k("storage_cart", "收纳推车", O, FurnitureStyle.shelf, MaterialKind.white, 45, 35, "cart", listOf("上层", "中层", "下层")),
        k("shoe_cabinet", "鞋柜", O, FurnitureStyle.cabinet, MaterialKind.wood, 100, 35, "shoeprints.fill", listOf("上层", "中层", "下层")),
        k("safe", "保险箱", O, FurnitureStyle.box, MaterialKind.dark, 40, 40, "lock", listOf("内部")),
        k("door", "门", X, FurnitureStyle.door, MaterialKind.wood, 90, 90, "door.left.hand.open", listOf()),
        k("window", "窗", X, FurnitureStyle.window, MaterialKind.glass, 120, 12, "window.casement", listOf()),
        k("wall", "隔墙", X, FurnitureStyle.wall, MaterialKind.dark, 200, 10, "rectangle.portrait", listOf()),
        k("pillar", "柱子", X, FurnitureStyle.pillar, MaterialKind.dark, 40, 40, "square.fill", listOf()),
    )

    val custom = FurnitureKind(CUSTOM, "自定义家具", O, FurnitureStyle.cabinet, MaterialKind.wood, 80.0, 40.0, "cube", listOf("内部"))

    private val byId = kinds.associateBy { it.id }

    fun kind(id: String): FurnitureKind = byId[id] ?: custom

    fun kinds(group: FurnitureGroup) = kinds.filter { it.group == group }

    val customColors = listOf("#C9A27C", "#E6CFAF", "#A9BCCB", "#C7CCD1", "#F2F2F2", "#8CC084",
        "#E7C9C0", "#D9B98C", "#B5A4D6", "#F2C46D", "#7FB5D9", "#3C4148")
}

data class RoomTypeInfo(
    val title: String,
    val symbol: String,
    val width: Double,
    val depth: Double,
    val floorLight: Long,
    val floorDark: Long,
    val suggested: List<String>,
)

data class PresetPiece(val kind: String, val x: Double, val y: Double, val rotation: Int = 0, val stackedOn: Int? = null)

private fun p(kind: String, x: Int, y: Int, rotation: Int = 0, stackedOn: Int? = null) =
    PresetPiece(kind, x.toDouble(), y.toDouble(), rotation, stackedOn)

val RoomType.info: RoomTypeInfo
    get() = when (this) {
        RoomType.living -> RoomTypeInfo("客厅", "sofa", 500.0, 400.0, 0xFFF3EDE3, 0xFF2E2A25, listOf("sofa", "coffee_table", "tv_stand", "tv", "bookshelf", "armchair", "rug", "plant", "air_conditioner"))
        RoomType.bedroom -> RoomTypeInfo("卧室", "bed.double", 400.0, 360.0, 0xFFEEF0F7, 0xFF272A31, listOf("bed_double", "nightstand", "wardrobe", "dresser", "vanity", "air_conditioner"))
        RoomType.kitchen -> RoomTypeInfo("厨房", "fork.knife", 300.0, 250.0, 0xFFEDF3EC, 0xFF252B25, listOf("fridge", "base_cabinet", "wall_cabinet", "stove", "kitchen_sink", "dishwasher", "microwave", "oven", "kitchen_rack", "spice_rack", "pantry"))
        RoomType.bathroom -> RoomTypeInfo("卫生间", "shower", 250.0, 200.0, 0xFFE8F2F6, 0xFF232B2F, listOf("toilet", "bath_vanity", "mirror_cabinet", "shower", "bathtub", "washer", "bath_shelf", "laundry_basket"))
        RoomType.dining -> RoomTypeInfo("餐厅", "fork.knife", 350.0, 300.0, 0xFFF6EFE6, 0xFF2E2924, listOf("dining_set", "sideboard", "chair", "pantry"))
        RoomType.study -> RoomTypeInfo("书房", "books.vertical", 300.0, 300.0, 0xFFF1EEE8, 0xFF2B2926, listOf("desk", "office_chair", "bookshelf", "filing_cabinet"))
        RoomType.balcony -> RoomTypeInfo("阳台", "sun.max", 300.0, 150.0, 0xFFEDF3EA, 0xFF262B24, listOf("washer", "dryer", "shelf_unit", "plant", "storage_cart"))
        RoomType.entrance -> RoomTypeInfo("玄关", "door.left.hand.open", 200.0, 150.0, 0xFFF2F0EC, 0xFF2A2927, listOf("shoe_cabinet", "door", "storage_basket"))
        RoomType.storage -> RoomTypeInfo("储物间", "shippingbox", 250.0, 200.0, 0xFFEFEDEA, 0xFF292826, listOf("shelf_unit", "storage_box", "drawer_unit", "storage_cart", "safe"))
        RoomType.kids -> RoomTypeInfo("儿童房", "teddybear", 350.0, 300.0, 0xFFF7EEF0, 0xFF2F282A, listOf("bed_single", "wardrobe", "desk", "storage_box", "bookshelf"))
        RoomType.other -> RoomTypeInfo("其他", "square.dashed", 300.0, 300.0, 0xFFF0F0F0, 0xFF2A2A2A, listOf("shelf_unit", "storage_box", "display_cabinet"))
    }

val RoomType.preset: List<PresetPiece>
    get() = when (this) {
        RoomType.living -> listOf(p("rug", 150, 150), p("sofa", 140, 300, 2), p("coffee_table", 190, 190),
            p("tv_stand", 150, 10), p("tv", 180, 28, stackedOn = 3), p("bookshelf", 5, 120, 3), p("plant", 440, 10))
        RoomType.bedroom -> listOf(p("bed_double", 110, 10), p("nightstand", 60, 10), p("nightstand", 295, 10),
            p("wardrobe", 120, 295, 2))
        RoomType.kitchen -> listOf(p("fridge", 5, 5), p("base_cabinet", 80, 5), p("microwave", 90, 12, stackedOn = 1),
            p("kitchen_sink", 205, 5), p("stove", 240, 75, 3), p("dishwasher", 235, 165, 3), p("kitchen_rack", 5, 215, 2))
        RoomType.bathroom -> listOf(p("toilet", 10, 5), p("bath_vanity", 70, 5), p("shower", 155, 5),
            p("washer", 185, 135, 2), p("bath_shelf", 10, 165, 2))
        RoomType.dining -> listOf(p("dining_set", 95, 90), p("sideboard", 115, 5))
        RoomType.study -> listOf(p("desk", 90, 5), p("office_chair", 122, 75), p("bookshelf", 5, 100, 3), p("filing_cabinet", 240, 10))
        RoomType.balcony -> listOf(p("washer", 10, 10), p("dryer", 10, 10, stackedOn = 0), p("shelf_unit", 100, 5), p("plant", 235, 85))
        RoomType.entrance -> listOf(p("shoe_cabinet", 10, 5), p("door", 100, 55))
        RoomType.storage -> listOf(p("shelf_unit", 5, 5), p("shelf_unit", 100, 5), p("storage_box", 10, 150), p("storage_box", 70, 150))
        RoomType.kids -> listOf(p("bed_single", 10, 10), p("wardrobe", 185, 235, 2), p("storage_box", 200, 20))
        RoomType.other -> listOf()
    }

object DefaultCategories {
    private fun id(n: Int) = "00000000-0000-0000-0000-0000000C%04d".format(n)

    /** Same fixed ids as iOS, so two devices seeding defaults share records. */
    val all = listOf(
        Category(id(1), "衣物", "tshirt", "#7E8CE0", CategoryTemplate.clothing.name, 0, 0),
        Category(id(2), "鞋包配饰", "bag", "#B07CD8", CategoryTemplate.clothing.name, 1, 0),
        Category(id(3), "食品", "carrot", "#E9965A", CategoryTemplate.perishable.name, 2, 0),
        Category(id(4), "饮品", "cup.and.saucer", "#C98B5E", CategoryTemplate.perishable.name, 3, 0),
        Category(id(5), "餐厨用具", "fork.knife", "#5FA8A0", CategoryTemplate.general.name, 4, 0),
        Category(id(6), "日用品", "shippingbox", "#6FA0D8", CategoryTemplate.general.name, 5, 0),
        Category(id(7), "洗护美妆", "bubbles.and.sparkles", "#E58FB0", CategoryTemplate.perishable.name, 6, 0),
        Category(id(8), "药品", "pills", "#E06C6C", CategoryTemplate.perishable.name, 7, 0),
        Category(id(9), "电子数码", "headphones", "#5C6B7A", CategoryTemplate.general.name, 8, 0),
        Category(id(10), "工具", "wrench.and.screwdriver", "#8C7A5B", CategoryTemplate.general.name, 9, 0),
        Category(id(11), "书籍文具", "book", "#6C9A5B", CategoryTemplate.general.name, 10, 0),
        Category(id(12), "证件文件", "doc.text", "#A0A060", CategoryTemplate.general.name, 11, 0),
        Category(id(13), "玩具爱好", "gamecontroller", "#F0B43C", CategoryTemplate.general.name, 12, 0),
        Category(id(14), "其他", "square.grid.2x2", "#8E8E93", CategoryTemplate.general.name, 13, 0),
    )

    val colors = listOf("#7E8CE0", "#B07CD8", "#E9965A", "#C98B5E", "#5FA8A0", "#6FA0D8", "#E58FB0",
        "#E06C6C", "#5C6B7A", "#8C7A5B", "#6C9A5B", "#A0A060", "#F0B43C", "#8E8E93")
}

object Units {
    val all = listOf("个", "件", "双", "套", "条", "张", "盒", "瓶", "罐", "包", "袋", "箱",
        "卷", "支", "本", "台", "副", "片", "千克", "克", "升", "毫升")
}

object Seasons { val all = listOf("春", "夏", "秋", "冬") }
