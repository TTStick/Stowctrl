import SwiftUI

/// The master list: every item, searchable, grouped by where it is or what it is.
struct ItemsView: View {
    enum Grouping: String, CaseIterable, Identifiable {
        case location, category, recent
        var id: String { rawValue }
        var title: String {
            switch self {
            case .location: return "按位置"
            case .category: return "按分类"
            case .recent: return "最近更新"
            }
        }
    }

    enum Filter: String, CaseIterable, Identifiable {
        case all, lowStock, expiring, favorite, unsorted
        var id: String { rawValue }
        var title: String {
            switch self {
            case .all: return "全部"
            case .lowStock: return "库存不足"
            case .expiring: return "过期/临期"
            case .favorite: return "收藏"
            case .unsorted: return "未归位"
            }
        }
    }

    @Environment(HomeStore.self) private var store
    @Environment(AppRouter.self) private var router
    @AppStorage("items.grouping") private var grouping: Grouping = .location
    @State private var filter: Filter = .all
    @State private var query = ""
    @State private var adding = false
    @State private var moving: Item?
    @State private var deleting: Item?

    private var filtered: [Item] {
        let q = query.trimmed
        return store.items.values.filter { item in
            switch filter {
            case .all: break
            case .lowStock: if !item.isLowStock { return false }
            case .expiring: if !(item.isExpired || item.isExpiringSoon) { return false }
            case .favorite: if !item.favorite { return false }
            case .unsorted: if store.location(of: item) != nil { return false }
            }
            guard !q.isEmpty else { return true }
            if item.name.localizedStandardContains(q) { return true }
            if item.tags.contains(where: { $0.localizedStandardContains(q) }) { return true }
            if item.notes.localizedStandardContains(q) { return true }
            if store.category(of: item)?.name.localizedStandardContains(q) == true { return true }
            return store.locationText(item).localizedStandardContains(q)
        }
    }

    var body: some View {
        @Bindable var router = router
        NavigationStack(path: $router.itemsPath) {
            let items = filtered
            List {
                Section {
                    filterChips
                        .listRowInsets(EdgeInsets(top: 4, leading: 0, bottom: 4, trailing: 0))
                        .listRowBackground(Color.clear)
                }
                if store.items.isEmpty {
                    ContentUnavailableView {
                        Label("还没有物品", systemImage: "shippingbox")
                    } description: {
                        Text("在“家”里打开家具放入物品，或点右上角 + 直接添加。")
                    } actions: {
                        Button("添加物品") { adding = true }.buttonStyle(.borderedProminent)
                    }
                    .listRowBackground(Color.clear)
                } else if items.isEmpty {
                    ContentUnavailableView.search(text: query)
                        .listRowBackground(Color.clear)
                } else {
                    sections(items)
                }
            }
            .readableWidth(900)
            .searchable(text: $query, prompt: "搜索名称、标签、位置")
            .navigationTitle("物品")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Menu {
                        Picker("分组", selection: $grouping) {
                            ForEach(Grouping.allCases) { Text($0.title).tag($0) }
                        }
                    } label: {
                        Label(grouping.title, systemImage: "rectangle.3.group")
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button { adding = true } label: { Image(systemName: "plus") }.accessibilityLabel("添加物品")
                }
            }
            .navigationDestination(for: Route.self) { RouteView(route: $0) }
            .sheet(isPresented: $adding) { ItemEditorView(mode: .create(compartmentId: nil)) }
            .sheet(item: $moving) { item in
                LocationPickerSheet(current: item.compartmentId) { store.moveItems([item.id], to: $0) }
            }
            .confirmationDialog("删除「\(deleting?.name ?? "")」？", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }),
                                titleVisibility: .visible) {
                Button("删除", role: .destructive) { if let d = deleting { store.deleteItem(d.id) } }
            }
        }
    }

    private var filterChips: some View {
        let counts: [Filter: Int] = [
            .lowStock: store.items.values.filter(\.isLowStock).count,
            .expiring: store.items.values.filter { $0.isExpired || $0.isExpiringSoon }.count,
            .favorite: store.items.values.filter(\.favorite).count,
            .unsorted: store.unsortedItems.count,
        ]
        return ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(Filter.allCases) { f in
                    let n = f == .all ? store.items.count : (counts[f] ?? 0)
                    Button { withAnimation { filter = f } } label: {
                        HStack(spacing: 4) {
                            Text(f.title)
                            Text("\(n)").foregroundStyle(filter == f ? .white.opacity(0.85) : .secondary)
                        }
                        .font(.subheadline)
                        .padding(.horizontal, 12).padding(.vertical, 7)
                        .foregroundStyle(filter == f ? .white : .primary)
                        .background(filter == f ? Color.accentColor : Color.gray.opacity(0.12), in: Capsule())
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 16)
        }
    }

    @ViewBuilder private func sections(_ items: [Item]) -> some View {
        switch grouping {
        case .location:
            let byRoom = Dictionary(grouping: items) { store.location(of: $0)?.room?.id }
            ForEach(store.sortedRooms) { room in
                if let list = byRoom[room.id], !list.isEmpty {
                    Section("\(room.name) · \(list.count)") {
                        ForEach(list.sorted { store.locationText($0) < store.locationText($1) }) { row($0) }
                    }
                }
            }
            if let list = byRoom[nil], !list.isEmpty {
                Section("未归位 · \(list.count)") {
                    ForEach(list.sorted { $0.updatedAt > $1.updatedAt }) { row($0) }
                }
            }
        case .category:
            let byCat = Dictionary(grouping: items) { $0.categoryId.flatMap { store.categories[$0] }?.id }
            ForEach(store.sortedCategories) { cat in
                if let list = byCat[cat.id], !list.isEmpty {
                    Section {
                        ForEach(list.sorted { $0.name < $1.name }) { row($0) }
                    } header: {
                        Label("\(cat.name) · \(list.count)", systemImage: SymbolCheck.safe(cat.symbol))
                    }
                }
            }
            if let list = byCat[nil], !list.isEmpty {
                Section("未分类 · \(list.count)") {
                    ForEach(list.sorted { $0.name < $1.name }) { row($0) }
                }
            }
        case .recent:
            Section("\(items.count) 件") {
                ForEach(items.sorted { $0.updatedAt > $1.updatedAt }) { row($0) }
            }
        }
    }

    private func row(_ item: Item) -> some View {
        NavigationLink(value: Route.item(item.id)) {
            ItemRow(item: item)
        }
        .swipeActions(edge: .trailing) {
            Button(role: .destructive) { deleting = item } label: { Label("删除", systemImage: "trash") }
            Button { moving = item } label: { Label("移动", systemImage: "arrow.right.square") }.tint(.blue)
        }
        .swipeActions(edge: .leading) {
            Button { store.adjustQuantity(item.id, by: -1) } label: { Label("用掉 1", systemImage: "minus") }.tint(.orange)
            Button { store.adjustQuantity(item.id, by: 1) } label: { Label("加 1", systemImage: "plus") }.tint(.green)
        }
        .contextMenu {
            if let loc = store.location(of: item) {
                Button { router.showOnPlan(loc.furniture) } label: { Label("在户型图中定位", systemImage: "map") }
            }
            Button { moving = item } label: { Label("移动", systemImage: "arrow.right.square") }
            Button(role: .destructive) { deleting = item } label: { Label("删除", systemImage: "trash") }
        }
    }
}
