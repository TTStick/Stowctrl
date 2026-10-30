import SwiftUI

struct ItemRow: View {
    let item: Item
    var showLocation = true
    @Environment(HomeStore.self) private var store

    var body: some View {
        HStack(spacing: 12) {
            ItemThumbnail(item: item, size: 46)
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text(item.name).font(.body).lineLimit(1)
                    ItemBadges(item: item)
                }
                Text(showLocation ? store.locationText(item) : (store.category(of: item)?.name ?? "未分类"))
                    .font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer(minLength: 4)
            Text("\(item.quantity.clean) \(item.unit)")
                .font(.subheadline.monospacedDigit()).foregroundStyle(.secondary)
        }
        .accessibilityElement(children: .combine)
    }
}

struct ItemCard: View {
    let item: Item
    @Environment(HomeStore.self) private var store

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ZStack {
                Color.photoBackground
                if let photo = item.photoId {
                    StoredImage(fileId: photo, maxPixel: 400).padding(8)
                } else {
                    IconTile(icon: item.icon, fallbackSymbol: store.category(of: item)?.symbol ?? "shippingbox",
                             tint: Color(hex: store.category(of: item)?.colorHex ?? "#8E8E93"), size: 64)
                }
            }
            .aspectRatio(1, contentMode: .fit)
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .overlay(alignment: .topTrailing) {
                if item.quantity != 1 {
                    Text("×\(item.quantity.clean)")
                        .font(.caption2.weight(.bold))
                        .padding(.horizontal, 6).padding(.vertical, 2)
                        .background(.thinMaterial, in: Capsule())
                        .padding(6)
                }
            }
            Text(item.name).font(.subheadline.weight(.medium)).lineLimit(1)
            HStack(spacing: 4) {
                if !item.colors.isEmpty { ColorDots(colors: item.colors, size: 10) }
                ItemBadges(item: item)
            }
            .frame(height: 14)
        }
    }
}

/// A set of items with grid/list layout and multi-select actions.
struct ItemCollection: View {
    let items: [Item]
    var showLocation = true
    var emptyTitle = "这里还没有东西"
    var onAdd: (() -> Void)?
    @AppStorage("collection.grid") private var grid = true
    @Environment(HomeStore.self) private var store
    @State private var selecting = false
    @State private var selected: Set<UUID> = []
    @State private var moving = false
    @State private var categorizing = false
    @State private var confirmDelete = false

    var body: some View {
        Group {
            if items.isEmpty {
                ContentUnavailableView {
                    Label(emptyTitle, systemImage: "shippingbox")
                } actions: {
                    if let onAdd { Button("放入物品", action: onAdd).buttonStyle(.borderedProminent) }
                }
            } else if grid {
                ScrollView {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 104), spacing: 12)], spacing: 14) {
                        ForEach(items) { item in cell(item) { ItemCard(item: item) } }
                    }
                    .padding()
                }
            } else {
                List {
                    ForEach(items) { item in
                        cell(item) { ItemRow(item: item, showLocation: showLocation) }
                            .swipeActions {
                                Button(role: .destructive) { store.deleteItem(item.id) } label: { Label("删除", systemImage: "trash") }
                            }
                    }
                }
                .listStyle(.plain)
                .readableWidth(900)
            }
        }
        .safeAreaInset(edge: .bottom) {
            if selecting { selectionBar }
        }
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                if !items.isEmpty {
                    Button(selecting ? "完成" : "选择") {
                        withAnimation { selecting.toggle(); selected = [] }
                    }
                    if !selecting {
                        Button { withAnimation { grid.toggle() } } label: {
                            Image(systemName: grid ? "list.bullet" : "square.grid.2x2")
                        }
                        .accessibilityLabel(grid ? "列表" : "网格")
                    }
                }
                if let onAdd, !selecting {
                    Button(action: onAdd) { Image(systemName: "plus") }.accessibilityLabel("添加物品")
                }
            }
        }
        .sheet(isPresented: $moving) {
            LocationPickerSheet(current: nil) { target in
                store.moveItems(Array(selected), to: target)
                selected = []
                selecting = false
            }
        }
        .sheet(isPresented: $categorizing) {
            CategoryPickerSheet(current: nil) { cat in
                store.setCategory(Array(selected), to: cat)
                selected = []
                selecting = false
            }
        }
        .confirmationDialog("删除 \(selected.count) 件物品？", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("删除", role: .destructive) {
                store.deleteItems(Array(selected))
                selected = []
                selecting = false
            }
        }
    }

    @ViewBuilder
    private func cell<Content: View>(_ item: Item, @ViewBuilder content: () -> Content) -> some View {
        if selecting {
            Button {
                if selected.contains(item.id) { selected.remove(item.id) } else { selected.insert(item.id) }
            } label: {
                content()
                    .overlay(alignment: .topLeading) {
                        Image(systemName: selected.contains(item.id) ? "checkmark.circle.fill" : "circle")
                            .font(.title3)
                            .foregroundStyle(selected.contains(item.id) ? Color.accentColor : .secondary)
                            .background(Circle().fill(Color.cardBackground))
                            .padding(4)
                    }
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        } else {
            NavigationLink(value: Route.item(item.id)) { content() }
                .buttonStyle(.plain)
        }
    }

    private var selectionBar: some View {
        HStack {
            Button(selected.count == items.count ? "全不选" : "全选") {
                selected = selected.count == items.count ? [] : Set(items.map(\.id))
            }
            Spacer()
            Button { moving = true } label: { Label("移动", systemImage: "arrow.right.square") }
                .disabled(selected.isEmpty)
            Button { categorizing = true } label: { Label("分类", systemImage: "tag") }
                .disabled(selected.isEmpty)
            Button(role: .destructive) { confirmDelete = true } label: { Label("删除", systemImage: "trash") }
                .disabled(selected.isEmpty)
        }
        .labelStyle(.titleAndIcon)
        .padding()
        .background(.bar)
    }
}

struct CompartmentView: View {
    let compartmentId: UUID
    @Environment(HomeStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @State private var adding = false
    @State private var renaming = false
    @State private var renameText = ""
    @State private var deleting: Compartment?

    var body: some View {
        if let c = store.compartments[compartmentId] {
            ItemCollection(items: store.items(in: c.id), showLocation: false, emptyTitle: "「\(c.name)」是空的",
                           onAdd: { adding = true })
                .navigationTitle(c.name)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .principal) {
                        VStack(spacing: 0) {
                            Text(c.name).font(.headline)
                            if let f = store.furniture[c.furnitureId] {
                                Text([store.rooms[f.roomId]?.name, f.name].compactMap { $0 }.joined(separator: " › "))
                                    .font(.caption2).foregroundStyle(.secondary)
                            }
                        }
                    }
                    ToolbarItem(placement: .topBarTrailing) {
                        Menu {
                            Button { renameText = c.name; renaming = true } label: { Label("重命名", systemImage: "pencil") }
                            Button(role: .destructive) { deleting = c } label: { Label("删除收纳位置", systemImage: "trash") }
                        } label: { Image(systemName: "ellipsis.circle") }
                    }
                }
                .sheet(isPresented: $adding) { ItemEditorView(mode: .create(compartmentId: c.id)) }
                .alert("重命名", isPresented: $renaming) {
                    TextField("名称", text: $renameText)
                    Button("取消", role: .cancel) {}
                    Button("保存") {
                        var updated = c
                        if !renameText.trimmed.isEmpty { updated.name = renameText.trimmed; store.save(updated) }
                    }
                }
                .deleteCompartmentDialog($deleting) { dismiss() }
        } else {
            ContentUnavailableView("收纳位置已删除", systemImage: "trash")
        }
    }
}

struct UnsortedItemsView: View {
    @Environment(HomeStore.self) private var store
    @State private var adding = false

    var body: some View {
        ItemCollection(items: store.unsortedItems, showLocation: false, emptyTitle: "所有物品都已归位",
                       onAdd: { adding = true })
            .navigationTitle("未归位")
            .navigationBarTitleDisplayMode(.inline)
            .sheet(isPresented: $adding) { ItemEditorView(mode: .create(compartmentId: nil)) }
    }
}

// MARK: - Pickers

/// Room › furniture › compartment, or "未归位".
struct LocationPickerSheet: View {
    let current: UUID?
    var onPick: (UUID?) -> Void
    @Environment(HomeStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    @State private var addingTo: Furniture?
    @State private var newName = ""

    var body: some View {
        NavigationStack {
            List {
                if query.trimmed.isEmpty {
                    Section {
                        pickRow(nil, title: "未归位", subtitle: "暂时不放进具体位置")
                    }
                    ForEach(store.sortedRooms) { room in
                        let pieces = store.furniture(in: room.id)
                        if !pieces.isEmpty {
                            Section(room.name) {
                                ForEach(pieces) { f in
                                    DisclosureGroup {
                                        ForEach(store.compartments(of: f.id)) { c in
                                            pickRow(c.id, title: c.name, subtitle: "\(store.items(in: c.id).count) 件")
                                        }
                                        Button {
                                            newName = ""
                                            addingTo = f
                                        } label: { Label("新建收纳位置", systemImage: "plus").font(.subheadline) }
                                    } label: {
                                        Label(f.name, systemImage: SymbolCheck.safe(f.symbol ?? Catalog.kind(f.kind).symbol, fallback: "cube"))
                                    }
                                }
                            }
                        }
                    }
                    if store.furniture.isEmpty {
                        Text("还没有家具，先去“家”里添加家具吧").foregroundStyle(.secondary)
                    }
                } else {
                    let matches = store.compartments.values.filter { store.compartmentPath($0.id).localizedStandardContains(query.trimmed) }
                        .sorted { store.compartmentPath($0.id) < store.compartmentPath($1.id) }
                    ForEach(matches) { c in
                        pickRow(c.id, title: c.name, subtitle: store.compartmentPath(c.id))
                    }
                }
            }
            .searchable(text: $query, prompt: "搜索房间、家具或位置")
            .navigationTitle("放到哪里")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } } }
            .alert("新的收纳位置", isPresented: Binding(get: { addingTo != nil }, set: { if !$0 { addingTo = nil } })) {
                TextField("例如：第 2 层", text: $newName)
                Button("取消", role: .cancel) {}
                Button("添加并选择") {
                    if let f = addingTo, !newName.trimmed.isEmpty {
                        let c = store.addCompartment(to: f.id, name: newName.trimmed)
                        onPick(c.id)
                        dismiss()
                    }
                }
            }
        }
    }

    private func pickRow(_ id: UUID?, title: String, subtitle: String) -> some View {
        Button {
            onPick(id)
            dismiss()
        } label: {
            HStack {
                VStack(alignment: .leading) {
                    Text(title).foregroundStyle(Color.primary)
                    Text(subtitle).font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                if id == current { Image(systemName: "checkmark").foregroundStyle(Color.accentColor) }
            }
        }
    }
}

struct CategoryPickerSheet: View {
    let current: UUID?
    var onPick: (UUID?) -> Void
    @Environment(HomeStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @State private var managing = false

    var body: some View {
        NavigationStack {
            List {
                Button { onPick(nil); dismiss() } label: {
                    HStack {
                        Label("未分类", systemImage: "circle.dashed").foregroundStyle(Color.primary)
                        Spacer()
                        if current == nil { Image(systemName: "checkmark") }
                    }
                }
                ForEach(store.sortedCategories) { c in
                    Button { onPick(c.id); dismiss() } label: {
                        HStack {
                            Label {
                                Text(c.name).foregroundStyle(Color.primary)
                            } icon: {
                                Image(systemName: SymbolCheck.safe(c.symbol)).foregroundStyle(Color(hex: c.colorHex))
                            }
                            Spacer()
                            if current == c.id { Image(systemName: "checkmark") }
                        }
                    }
                }
                Button { managing = true } label: { Label("管理分类", systemImage: "slider.horizontal.3") }
            }
            .navigationTitle("选择分类")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } } }
            .sheet(isPresented: $managing) { NavigationStack { CategoryManagerView() } }
        }
        .presentationDetents([.medium, .large])
    }
}
