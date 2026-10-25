import SwiftUI

/// First launch: build the home (rooms, optionally furnished) or sign in
/// to pull an existing one.
struct OnboardingView: View {
    @Environment(HomeStore.self) private var store
    @State private var step = 0
    @State private var signingIn = false
    @State private var importing = false
    @State private var pendingRestore: Backup.Loaded?
    @State private var restoreError: String?

    var body: some View {
        NavigationStack {
            Group {
                if step == 0 { welcome } else { HomeWizard() }
            }
            .animation(.default, value: step)
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.zip, .data]) { result in
            guard case .success(let url) = result else { return }
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            do {
                let local = FileManager.default.temporaryDirectory.appendingPathComponent("restore-\(UUID().uuidString).zip")
                try FileManager.default.copyItem(at: url, to: local)
                pendingRestore = try Backup.load(local)
            } catch {
                restoreError = "无法读取备份：\(error.localizedDescription)"
            }
        }
        .confirmationDialog("从备份恢复", isPresented: Binding(get: { pendingRestore != nil }, set: { if !$0 { pendingRestore = nil } }),
                            titleVisibility: .visible, presenting: pendingRestore) { loaded in
            Button("恢复") {
                do {
                    _ = try Backup.restore(loaded, into: store, replace: true)
                    store.seedDefaultCategoriesIfNeeded()
                    store.markOnboarded()
                } catch {
                    restoreError = "恢复失败：\(error.localizedDescription)"
                }
            }
            Button("取消", role: .cancel) {}
        } message: { loaded in
            Text("“\(loaded.manifest.homeName)”：\(loaded.count(Room.entityName)) 个房间、\(loaded.count(Item.entityName)) 件物品、\(loaded.photoCount) 张照片。")
        }
        .sheet(isPresented: $signingIn) {
            AccountSheet {
                // An account that already has a home skips the wizard.
                if !store.rooms.isEmpty { store.markOnboarded() } else { step = 1 }
            }
        }
    }

    private var welcome: some View {
        VStack(spacing: 28) {
            Spacer()
            WelcomeArt()
                .frame(width: 260, height: 200)
            VStack(spacing: 10) {
                Text("万物收纳").font(.largeTitle.bold())
                Text("把家搬进手机：搭好房间和家具，\n打开柜子就知道里面放了什么。")
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.secondary)
            }
            VStack(alignment: .leading, spacing: 14) {
                feature("square.split.2x2", "像搭积木一样画出你的家", "客厅、厨房、卫生间，家具随意摆放、叠放")
                feature("cabinet", "每个柜子都能打开", "衣柜、冰箱、洗碗机……放了什么一目了然")
                feature("camera.viewfinder", "拍照自动抠图", "在手机上处理，也可以用图标或 AI 画图标")
                feature("homekit", "接入 Home Assistant", "把灯和电器放到对应的家具上")
            }
            .padding(.horizontal, 28)
            Spacer()
            VStack(spacing: 12) {
                Button { step = 1 } label: {
                    Text("开始搭建我的家").frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                Text("不需要服务器，数据只保存在本机；以后随时可以登录同步。")
                    .font(.caption).foregroundStyle(.secondary)
                HStack(spacing: 18) {
                    Button("已有账号？登录同步") { signingIn = true }
                    Button("从备份恢复") { importing = true }
                }
                if let restoreError { Text(restoreError).font(.caption).foregroundStyle(.red) }
            }
            .padding(.horizontal, 28)
            .padding(.bottom, 20)
        }
    }

    private func feature(_ symbol: String, _ title: String, _ detail: String) -> some View {
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: symbol).font(.title3).foregroundStyle(Color.accentColor).frame(width: 30)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.subheadline.weight(.semibold))
                Text(detail).font(.footnote).foregroundStyle(.secondary)
            }
        }
    }
}

/// A tiny furnished floor plan, drawn with the same art as the app.
private struct WelcomeArt: View {
    var body: some View {
        Canvas { ctx, size in
            let s = size.width / 900
            let rooms: [(RoomType, CGRect, [(String, CGFloat, CGFloat, Int)])] = [
                (.living, CGRect(x: 0, y: 0, width: 500, height: 400),
                 [("rug", 150, 150, 0), ("sofa", 140, 300, 2), ("coffee_table", 190, 190, 0), ("tv_stand", 150, 10, 0), ("plant", 440, 10, 0)]),
                (.bedroom, CGRect(x: 500, y: 0, width: 400, height: 400),
                 [("bed_double", 110, 10, 0), ("nightstand", 60, 10, 0), ("nightstand", 295, 10, 0), ("wardrobe", 120, 335, 2)]),
                (.kitchen, CGRect(x: 0, y: 400, width: 500, height: 280),
                 [("fridge", 5, 5, 0), ("base_cabinet", 80, 5, 0), ("kitchen_sink", 205, 5, 0), ("stove", 290, 5, 0), ("dining_set", 300, 120, 0)]),
                (.bathroom, CGRect(x: 500, y: 400, width: 400, height: 280),
                 [("toilet", 10, 5, 0), ("bath_vanity", 70, 5, 0), ("bathtub", 220, 5, 0), ("washer", 330, 210, 2)]),
            ]
            for (type, r, pieces) in rooms {
                let rect = CGRect(x: r.minX * s, y: r.minY * s, width: r.width * s, height: r.height * s)
                ctx.fill(Path(rect), with: .color(type.info.floor))
                for (kindId, x, y, rot) in pieces {
                    let k = Catalog.kind(kindId)
                    let f = Furniture(roomId: UUID(), kind: kindId, name: k.name, x: x, y: y, width: k.width, depth: k.depth, rotation: rot)
                    var c = ctx
                    c.translateBy(x: rect.minX + f.x * s, y: rect.minY + f.y * s)
                    FurnitureArt.draw(f, in: c, size: CGSize(width: f.footprintWidth * s, height: f.footprintDepth * s))
                }
                ctx.stroke(Path(rect), with: .color(Color.wallColor), lineWidth: 3)
            }
        }
        .accessibilityHidden(true)
    }
}

struct HomeWizard: View {
    @Environment(HomeStore.self) private var store
    @State private var homeName = "我的家"
    @State private var counts: [RoomType: Int] = [.living: 1, .bedroom: 2, .kitchen: 1, .bathroom: 1, .dining: 0, .study: 0,
                                                    .balcony: 1, .entrance: 1, .storage: 0, .kids: 0, .other: 0]
    @State private var withFurniture = true

    private var total: Int { counts.values.reduce(0, +) }

    var body: some View {
        Form {
            Section("家的名字") {
                TextField("我的家", text: $homeName)
            }
            Section {
                ForEach(RoomType.allCases) { type in
                    Stepper(value: Binding(get: { counts[type] ?? 0 }, set: { counts[type] = $0 }), in: 0...6) {
                        HStack(spacing: 12) {
                            Image(systemName: type.info.symbol)
                                .frame(width: 34, height: 34)
                                .background(type.info.floor, in: RoundedRectangle(cornerRadius: 8))
                            Text(type.info.title)
                            Spacer()
                            Text("\(counts[type] ?? 0)").monospacedDigit().foregroundStyle(.secondary)
                        }
                    }
                }
            } header: {
                Text("有哪些房间")
            } footer: {
                Text("先大概选一下，之后可以在户型图里拖动、改大小、增删房间。")
            }
            Section {
                Toggle("自动摆放常见家具", isOn: $withFurniture)
            } footer: {
                Text("比如卧室放床、床头柜和衣柜，厨房放冰箱、橱柜、灶台。都可以再改。")
            }
            Section {
                Button {
                    store.buildHome(name: homeName, rooms: RoomType.allCases.map { ($0, counts[$0] ?? 0) },
                                    withFurniture: withFurniture)
                    Haptics.success()
                } label: {
                    Text(total == 0 ? "先不添加房间" : "创建 \(total) 个房间").frame(maxWidth: .infinity).bold()
                }
                .accessibilityIdentifier("wizard.create")
            }
        }
        .navigationTitle("搭建你的家")
    }
}
