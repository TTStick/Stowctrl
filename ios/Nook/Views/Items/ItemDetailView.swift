import SwiftUI

struct ItemDetailView: View {
    let itemId: UUID
    @Environment(HomeStore.self) private var store
    @Environment(AppRouter.self) private var router
    @Environment(\.dismiss) private var dismiss
    @State private var editing = false
    @State private var moving = false
    @State private var confirmDelete = false
    @State private var showOriginal = false
    @State private var fullscreen = false

    var body: some View {
        if let item = store.items[itemId] {
            content(item)
        } else {
            ContentUnavailableView("物品已删除", systemImage: "trash", description: Text("这件物品可能在另一台设备上被删除了。"))
        }
    }

    private func content(_ item: Item) -> some View {
        let category = store.category(of: item)
        let template = category?.template ?? .general
        return List {
            Section {
                hero(item, category: category)
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
            }
            Section {
                HStack {
                    Text("数量")
                    Spacer()
                    Button { store.adjustQuantity(item.id, by: -1) } label: { Image(systemName: "minus.circle.fill").font(.title2) }
                        .buttonStyle(.borderless)
                        .disabled(item.quantity <= 0)
                        .accessibilityLabel("减少")
                    Text("\(item.quantity.clean) \(item.unit)")
                        .font(.title3.monospacedDigit().weight(.semibold))
                        .frame(minWidth: 70)
                    Button { store.adjustQuantity(item.id, by: 1) } label: { Image(systemName: "plus.circle.fill").font(.title2) }
                        .buttonStyle(.borderless)
                        .accessibilityLabel("增加")
                }
                if item.isLowStock {
                    Label("库存不足（提醒阈值 \(item.lowStock!.clean) \(item.unit)）", systemImage: "arrow.down.circle")
                        .foregroundStyle(.orange)
                }
                locationRow(item)
                Button { moving = true } label: { Label("移动到其他位置", systemImage: "arrow.right.square") }
            }

            if let expires = item.expiresAt {
                Section("保质期") {
                    let d = expires.daysFromToday
                    LabeledContent("到期日", value: expires.shortDate)
                    Text(d < 0 ? "已过期 \(-d) 天" : (d == 0 ? "今天到期" : "还有 \(d) 天"))
                        .foregroundStyle(d < 0 ? .red : (d <= 7 ? .orange : .secondary))
                }
            }

            if template == .clothing || !item.colors.isEmpty || !item.seasons.isEmpty || !item.wearLog.isEmpty {
                clothingSection(item)
            }

            if item.price != nil || item.purchasedAt != nil {
                Section("购买") {
                    if let p = item.price { LabeledContent("价格", value: "¥\(p.clean)") }
                    if let d = item.purchasedAt { LabeledContent("日期", value: d.shortDate) }
                    if let p = item.price, !item.wearLog.isEmpty {
                        LabeledContent("每次穿着成本", value: "¥\((p / Double(item.wearLog.count)).clean)")
                    }
                }
            }

            if !item.tags.isEmpty {
                Section("标签") {
                    FlowLayout {
                        ForEach(item.tags, id: \.self) { t in
                            Text("#\(t)").font(.subheadline)
                                .padding(.horizontal, 10).padding(.vertical, 5)
                                .background(Color.accentColor.opacity(0.12), in: Capsule())
                        }
                    }
                }
            }
            if !item.notes.isEmpty {
                Section("备注") { Text(item.notes) }
            }

            Section {
                Button { store.duplicateItem(item.id).map { router.open(.item($0.id)) } } label: {
                    Label("复制一份", systemImage: "plus.square.on.square")
                }
                Button(role: .destructive) { confirmDelete = true } label: { Label("删除物品", systemImage: "trash") }
            } footer: {
                Text("添加于 \(item.createdAt.shortDate) · 更新于 \(item.updatedAt.shortDate)")
            }
        }
        .readableWidth()
        .navigationTitle(item.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button {
                    var i = item
                    i.favorite.toggle()
                    store.save(i)
                } label: {
                    Image(systemName: item.favorite ? "heart.fill" : "heart").foregroundStyle(item.favorite ? .pink : .primary)
                }
                .accessibilityLabel(item.favorite ? "取消收藏" : "收藏")
                Button("编辑") { editing = true }
            }
        }
        .sheet(isPresented: $editing) { ItemEditorView(mode: .edit(item)) }
        .sheet(isPresented: $moving) {
            LocationPickerSheet(current: item.compartmentId) { store.moveItems([item.id], to: $0) }
        }
        .confirmationDialog("删除「\(item.name)」？", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("删除", role: .destructive) {
                store.deleteItem(item.id)
                dismiss()
            }
        }
        .fullScreenCover(isPresented: $fullscreen) {
            PhotoViewer(fileId: showOriginal ? (item.originalPhotoId ?? item.photoId) : item.photoId)
        }
    }

    private func hero(_ item: Item, category: Category?) -> some View {
        VStack(spacing: 12) {
            ZStack {
                Color.photoBackground
                if let photo = showOriginal ? (item.originalPhotoId ?? item.photoId) : item.photoId {
                    StoredImage(fileId: photo, maxPixel: 1024).padding(16)
                        .onTapGesture { fullscreen = true }
                } else {
                    IconTile(icon: item.icon, fallbackSymbol: category?.symbol ?? "shippingbox",
                             tint: Color(hex: category?.colorHex ?? "#8E8E93"), size: 120)
                }
            }
            .frame(height: 260)
            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
            .overlay(alignment: .bottomTrailing) {
                if item.originalPhotoId != nil && item.originalPhotoId != item.photoId {
                    Button(showOriginal ? "看抠图" : "看原图") { showOriginal.toggle() }
                        .font(.caption.weight(.semibold))
                        .buttonStyle(.bordered)
                        .padding(10)
                }
            }
            VStack(spacing: 6) {
                Text(item.name).font(.title2.weight(.semibold)).multilineTextAlignment(.center)
                HStack(spacing: 6) {
                    if let category {
                        Pill(text: category.name, color: Color(hex: category.colorHex), systemImage: SymbolCheck.safe(category.symbol))
                    }
                    ItemBadges(item: item)
                }
            }
        }
        .padding(.vertical, 6)
    }

    private func locationRow(_ item: Item) -> some View {
        Group {
            if let loc = store.location(of: item) {
                VStack(alignment: .leading, spacing: 8) {
                    Text("存放位置").font(.caption).foregroundStyle(.secondary)
                    Button { router.open(.furniture(loc.furniture.id)) } label: {
                        HStack {
                            Image(systemName: "mappin.and.ellipse").foregroundStyle(Color.accentColor)
                            Text(store.locationText(item)).foregroundStyle(Color.primary).multilineTextAlignment(.leading)
                            Spacer()
                            Image(systemName: "chevron.right").font(.caption).foregroundStyle(.tertiary)
                        }
                    }
                    .buttonStyle(.plain)
                    Button { router.showOnPlan(loc.furniture) } label: { Label("在户型图中定位", systemImage: "map") }
                        .font(.subheadline)
                        .buttonStyle(.borderless)
                }
            } else {
                HStack {
                    Image(systemName: "tray").foregroundStyle(.orange)
                    Text("未归位").foregroundStyle(.orange)
                }
            }
        }
    }

    private func clothingSection(_ item: Item) -> some View {
        Section("穿着") {
            if !item.colors.isEmpty {
                LabeledContent("颜色") { ColorDots(colors: item.colors, size: 18) }
            }
            if !item.seasons.isEmpty {
                LabeledContent("季节", value: item.seasons.joined(separator: " · "))
            }
            Button {
                var i = item
                let today = Date().startOfDay
                if !i.wearLog.contains(where: { $0.startOfDay == today }) {
                    i.wearLog.insert(Date(), at: 0)
                    store.save(i)
                    Haptics.success()
                }
            } label: {
                Label(item.wearLog.contains { $0.startOfDay == Date().startOfDay } ? "今天已记录" : "今天穿了",
                      systemImage: "calendar.badge.plus")
            }
            .disabled(item.wearLog.contains { $0.startOfDay == Date().startOfDay })
            if !item.wearLog.isEmpty {
                LabeledContent("穿过", value: "\(item.wearLog.count) 次")
                ForEach(item.wearLog.sorted(by: >).prefix(10), id: \.self) { d in
                    Text(d.shortDate).font(.subheadline).foregroundStyle(.secondary)
                        .swipeActions {
                            Button(role: .destructive) {
                                var i = item
                                i.wearLog.removeAll { $0 == d }
                                store.save(i)
                            } label: { Label("删除", systemImage: "trash") }
                        }
                }
            }
        }
    }
}

struct PhotoViewer: View {
    let fileId: String?
    @Environment(\.dismiss) private var dismiss
    @State private var scale: CGFloat = 1

    var body: some View {
        ZStack(alignment: .topTrailing) {
            Color.black.ignoresSafeArea()
            if let fileId {
                StoredImage(fileId: fileId, maxPixel: 2048)
                    .scaleEffect(scale)
                    .gesture(MagnifyGesture().onChanged { scale = max(1, $0.magnification) }.onEnded { _ in withAnimation { scale = 1 } })
            }
            Button { dismiss() } label: {
                Image(systemName: "xmark.circle.fill").font(.title).foregroundStyle(.white.opacity(0.85))
            }
            .padding()
            .accessibilityLabel("关闭")
        }
    }
}
