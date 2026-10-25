import Foundation

/// Line-art icons (SF Symbols) with Chinese names so they can be searched.
struct SymbolEntry: Identifiable, Hashable {
    let name: String
    let keywords: String
    var id: String { name }
}

struct SymbolGroup: Identifiable {
    let title: String
    let entries: [SymbolEntry]
    var id: String { title }
}

enum SymbolLibrary {
    private static func g(_ title: String, _ pairs: [(String, String)]) -> SymbolGroup {
        SymbolGroup(title: title, entries: pairs.map { SymbolEntry(name: $0.0, keywords: $0.1) })
    }

    static let groups: [SymbolGroup] = [
        g("衣物鞋包", [
            ("tshirt", "T恤 上衣 衣服 短袖"), ("tshirt.fill", "T恤 上衣 衣服"),
            ("hanger", "衣架 挂衣"), ("shoe", "鞋 鞋子 运动鞋"), ("shoe.2", "鞋 两双鞋"),
            ("shoeprints.fill", "鞋 拖鞋 鞋印"), ("hat.widebrim", "帽子 遮阳帽"), ("hat.cap", "帽子 棒球帽"),
            ("eyeglasses", "眼镜"), ("sunglasses", "墨镜 太阳镜"), ("bag", "包 购物袋"),
            ("handbag", "手提包 包"), ("backpack", "书包 背包 双肩包"), ("suitcase", "行李箱 箱子"),
            ("suitcase.rolling", "拉杆箱 行李箱"), ("briefcase", "公文包"), ("applewatch", "手表"),
            ("crown", "首饰 皇冠"), ("comb", "梳子"), ("scissors", "剪刀"),
        ]),
        g("厨房餐具", [
            ("fork.knife", "刀叉 餐具 西餐"), ("cup.and.saucer", "杯子 咖啡杯 茶杯"), ("mug", "马克杯 杯子"),
            ("wineglass", "酒杯 高脚杯"), ("waterbottle", "水瓶 水杯 保温杯"), ("frying.pan", "平底锅 煎锅 锅"),
            ("cooktop", "灶 电磁炉"), ("oven", "烤箱"), ("microwave", "微波炉"), ("refrigerator", "冰箱"),
            ("dishwasher", "洗碗机"), ("stove", "灶台 炉灶"), ("sink", "水槽 洗手池"),
            ("takeoutbag.and.cup.and.straw", "外卖 打包"), ("flame", "燃气 火"), ("drop", "水 液体 油"),
            ("basket", "篮子 菜篮"), ("cart", "购物车"), ("birthday.cake", "蛋糕 甜点"),
            ("carrot", "胡萝卜 蔬菜"), ("fish", "鱼 海鲜"), ("leaf", "叶子 蔬菜 茶叶"),
            ("popcorn", "爆米花 零食"), ("wineglass.fill", "红酒 酒"), ("cup.and.heat.waves", "热饮 咖啡"),
            ("takeoutbag.and.cup.and.straw.fill", "饮料 奶茶"), ("fork.knife.circle", "餐盘 盘子 碗"),
            ("spoon", "汤勺 勺子"),
        ]),
        g("家居日用", [
            ("shippingbox", "箱子 快递 纸箱"), ("archivebox", "收纳盒 档案盒"), ("tray", "托盘"),
            ("tray.2", "托盘 叠放"), ("cabinet", "柜子 橱柜"), ("sofa", "沙发"), ("bed.double", "床 被子 床品"),
            ("lamp.desk", "台灯"), ("lamp.floor", "落地灯"), ("lightbulb", "灯泡"), ("chair", "椅子"),
            ("chair.lounge", "躺椅"), ("table.furniture", "桌子"), ("fan", "风扇"), ("humidifier", "加湿器"),
            ("air.purifier", "空气净化器"), ("washer", "洗衣机"), ("dryer", "烘干机"),
            ("bubbles.and.sparkles", "洗涤剂 清洁 沐浴露"), ("sparkles", "清洁 闪亮"), ("trash", "垃圾桶 垃圾袋"),
            ("bathtub", "浴缸"), ("shower", "淋浴 花洒"), ("toilet", "马桶 卫生纸"), ("hands.sparkles", "洗手液"),
            ("umbrella", "雨伞"), ("key", "钥匙"), ("lock", "锁"), ("clock", "钟 时钟"), ("alarm", "闹钟"),
            ("powerplug", "插头 插座"), ("bolt", "电 充电"), ("battery.100", "电池"), ("flashlight.off.fill", "手电筒"),
            ("thermometer.medium", "温度计"), ("tent", "帐篷 露营"), ("bicycle", "自行车"), ("car", "车 汽车"),
        ]),
        g("电子数码", [
            ("iphone", "手机"), ("ipad", "平板 iPad"), ("laptopcomputer", "笔记本电脑"),
            ("desktopcomputer", "台式机 电脑"), ("display", "显示器"), ("tv", "电视"), ("appletv", "电视盒子"),
            ("headphones", "耳机 头戴式"), ("airpods", "耳机 无线耳机"), ("airpodsmax", "头戴耳机"),
            ("speaker", "音箱 喇叭"), ("hifispeaker", "音响"), ("homepod", "智能音箱"),
            ("keyboard", "键盘"), ("computermouse", "鼠标"), ("printer", "打印机"), ("camera", "相机"),
            ("gamecontroller", "游戏手柄 游戏机"), ("externaldrive", "硬盘 移动硬盘"), ("sdcard", "存储卡 SD卡"),
            ("memorychip", "芯片 内存"), ("cable.connector", "数据线 线材"), ("powercord", "电源线"),
            ("wifi.router", "路由器"), ("applepencil", "触控笔"), ("watch.analog", "手表"),
        ]),
        g("工具维修", [
            ("hammer", "锤子"), ("wrench", "扳手"), ("wrench.and.screwdriver", "工具 工具箱"),
            ("screwdriver", "螺丝刀"), ("level", "水平仪"), ("ruler", "尺子"), ("paintbrush", "刷子 画笔"),
            ("paintbrush.pointed", "笔刷"), ("paintpalette", "颜料 调色盘"), ("pencil.and.ruler", "测量"),
            ("gearshape", "齿轮 零件"), ("magnifyingglass", "放大镜"), ("pin", "图钉"), ("paperclip", "回形针"),
            ("link", "链条 链接"), ("fuelpump", "加油"), ("drop.triangle", "机油"),
        ]),
        g("书籍文具", [
            ("book", "书 书本"), ("book.closed", "书 笔记本"), ("books.vertical", "书架 书籍"),
            ("magazine", "杂志"), ("newspaper", "报纸"), ("doc.text", "文件 文档"), ("folder", "文件夹"),
            ("pencil", "铅笔 笔"), ("highlighter", "荧光笔 马克笔"), ("pencil.tip", "钢笔"),
            ("graduationcap", "学习 毕业"), ("envelope", "信封 信件"), ("creditcard", "银行卡 卡"),
            ("giftcard", "礼品卡 会员卡"), ("ticket", "票 门票"), ("calendar", "日历"), ("person.text.rectangle", "证件 身份证"),
            ("menucard", "说明书 菜单"), ("note.text", "便签 笔记"),
        ]),
        g("医药健康", [
            ("pills", "药 药片"), ("cross.case", "医药箱 急救包"), ("bandage", "创可贴 绷带"),
            ("syringe", "针管 注射器"), ("stethoscope", "听诊器"), ("heart", "健康 心"),
            ("facemask", "口罩"), ("eyedropper", "滴管 眼药水"), ("lungs", "呼吸"), ("figure.walk", "运动"),
        ]),
        g("玩具爱好", [
            ("teddybear", "玩偶 小熊 毛绒"), ("puzzlepiece", "拼图 积木"), ("dice", "骰子 桌游"),
            ("football", "橄榄球"), ("basketball", "篮球"), ("soccerball", "足球"), ("tennis.racket", "网球拍"),
            ("tennisball", "网球"), ("skateboard", "滑板"), ("guitars", "吉他"), ("pianokeys", "钢琴 键盘乐器"),
            ("music.note", "音乐"), ("film", "电影 胶片"), ("balloon", "气球"), ("party.popper", "派对"),
            ("gift", "礼物 礼盒"), ("trophy", "奖杯"), ("medal", "奖牌"), ("figure.pool.swim", "游泳"),
            ("dumbbell", "哑铃 健身"), ("figure.yoga", "瑜伽垫 瑜伽"), ("camera.macro", "花 园艺"),
        ]),
        g("植物宠物", [
            ("leaf", "植物 叶子"), ("tree", "树 盆栽"), ("camera.macro", "花"), ("pawprint", "宠物 爪印"),
            ("cat", "猫"), ("dog", "狗"), ("bird", "鸟"), ("fish", "鱼"), ("tortoise", "乌龟"), ("hare", "兔子"),
            ("ant", "蚂蚁"), ("ladybug", "瓢虫"),
        ]),
        g("其他", [
            ("cube", "方块 盒子"), ("cylinder", "圆柱 罐子"), ("shippingbox.fill", "包裹"), ("star", "星 收藏"),
            ("heart.fill", "喜欢"), ("tag", "标签"), ("flag", "旗子"), ("bell", "铃铛"), ("bookmark", "书签"),
            ("square.grid.2x2", "其他 杂物"), ("questionmark.circle", "未知"), ("circle.grid.3x3", "多个"),
        ]),
    ]

    static var all: [SymbolEntry] {
        var seen = Set<String>()
        return groups.flatMap(\.entries).filter { seen.insert($0.name).inserted }
    }

    static func search(_ query: String) -> [SymbolEntry] {
        let q = query.trimmed
        guard !q.isEmpty else { return all }
        return all.filter { $0.keywords.localizedStandardContains(q) || $0.name.localizedStandardContains(q) }
    }
}
