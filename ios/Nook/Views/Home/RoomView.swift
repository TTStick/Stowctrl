import SwiftUI

struct RoomView: View {
    let roomId: UUID
    @Environment(HomeStore.self) private var store
    @Environment(AppRouter.self) private var router
    @Environment(\.dismiss) private var dismiss
    @State private var editing = false
    @State private var selection: UUID?
    @State private var showList = false
    @State private var showPalette = false
    @State private var editingFurniture: Furniture?
    @State private var deletingFurniture: Furniture?
    @State private var stackProposal: StackProposal?
    @State private var editingRoom: Room?
    @State private var deletingRoom: Room?
    @State private var highlight: UUID?

    struct StackProposal: Identifiable {
        let moved: Furniture
        let target: Furniture
        var id: UUID { moved.id }
    }

    var body: some View {
        if let room = store.rooms[roomId] {
            content(room)
        } else {
            ContentUnavailableView("房间已删除", systemImage: "trash", description: Text("这个房间可能在另一台设备上被删除了。"))
        }
    }

    private func content(_ room: Room) -> some View {
        ZStack(alignment: .bottom) {
            if showList {
                FurnitureListView(room: room, onEdit: { editingFurniture = $0 }, onDelete: { deletingFurniture = $0 })
            } else {
                RoomCanvas(room: room, editing: editing, selection: $selection, highlight: highlight,
                           onOpen: { router.open(.furniture($0.id)) },
                           onLongPress: { f in withAnimation { editing = true; selection = f.id } },
                           onDropOnto: { moved, target in stackProposal = StackProposal(moved: moved, target: target) })
                    .background(Color.canvasBackground)
                bottomPanel(room)
                    .floatingPanelWidth()
                    .padding(.horizontal)
                    .padding(.bottom, 10)
            }
        }
        .navigationTitle(room.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                if !showList {
                    Button(editing ? "完成" : "编辑") {
                        withAnimation { editing.toggle(); selection = nil }
                    }
                    .fontWeight(editing ? .semibold : .regular)
                }
                Menu {
                    Button { showPalette = true } label: { Label("添加家具", systemImage: "plus") }
                    Button { withAnimation { showList.toggle(); editing = false; selection = nil } } label: {
                        Label(showList ? "平面图" : "家具列表", systemImage: showList ? "square.grid.3x3" : "list.bullet")
                    }
                    Divider()
                    Button { editingRoom = room } label: { Label("编辑房间", systemImage: "pencil") }
                    Button(role: .destructive) { deletingRoom = room } label: { Label("删除房间", systemImage: "trash") }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
            }
        }
        .sheet(isPresented: $showPalette) {
            FurniturePaletteSheet(room: room) { added in
                withAnimation { editing = true; selection = added.id }
            }
        }
        .sheet(item: $editingFurniture) { FurnitureEditorSheet(mode: .edit($0)) }
        .sheet(item: $editingRoom) { RoomEditorSheet(room: $0) }
        .deleteFurnitureDialog($deletingFurniture) { selection = nil }
        .deleteRoomDialog($deletingRoom) { dismiss() }
        .confirmationDialog("叠放", isPresented: Binding(get: { stackProposal != nil }, set: { if !$0 { stackProposal = nil } }),
                            presenting: stackProposal) { p in
            Button("把「\(p.moved.name)」放在「\(p.target.name)」上") { store.setStack(p.moved.id, on: p.target.id); Haptics.success() }
            Button("只移动位置", role: .cancel) {}
        } message: { p in
            Text("「\(p.moved.name)」拖到了「\(p.target.name)」上面。叠放后它会跟着下面的家具一起移动。")
        }
        .onAppear {
            if let h = router.highlightFurniture, store.furniture[h]?.roomId == roomId {
                highlight = h
                router.highlightFurniture = nil
                Task {
                    try? await Task.sleep(for: .seconds(3))
                    highlight = nil
                }
            }
        }
    }

    @ViewBuilder private func bottomPanel(_ room: Room) -> some View {
        if editing, let id = selection, let f = store.furniture[id] {
            FurnitureInspector(furniture: f,
                               onOpen: { router.open(.furniture(f.id)) },
                               onEdit: { editingFurniture = f },
                               onDelete: { deletingFurniture = f })
        } else if editing {
            Button { showPalette = true } label: {
                Label("添加家具", systemImage: "plus").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
        } else if store.furniture(in: room.id).isEmpty {
            VStack(spacing: 8) {
                Text("房间是空的").font(.headline)
                Text("添加家具后，点开家具就能收纳物品").font(.footnote).foregroundStyle(.secondary)
                Button("添加家具") { showPalette = true }.buttonStyle(.borderedProminent)
            }
            .card()
        } else {
            Text("点家具打开收纳 · 长按进入编辑")
                .font(.footnote).foregroundStyle(.secondary)
                .padding(.horizontal, 14).padding(.vertical, 8)
                .background(.regularMaterial, in: Capsule())
        }
    }
}

// MARK: - Canvas

struct RoomCanvas: View {
    let room: Room
    let editing: Bool
    @Binding var selection: UUID?
    var highlight: UUID?
    var onOpen: (Furniture) -> Void
    var onLongPress: (Furniture) -> Void
    var onDropOnto: (Furniture, Furniture) -> Void

    @Environment(HomeStore.self) private var store
    @Environment(HomeAssistantManager.self) private var ha
    @State private var zoom: CGFloat = 1
    @State private var pan: CGSize = .zero
    @GestureState private var pinch: CGFloat = 1
    @GestureState private var panDrag: CGSize = .zero
    @GestureState private var moveDrag: CGSize = .zero
    @State private var movingId: UUID?
    @GestureState private var resizeDrag: CGSize = .zero
    @State private var resizingId: UUID?
    @State private var pulse = false

    var body: some View {
        GeometryReader { geo in
            let L = layout(geo.size)
            let pieces = store.furniture(in: room.id)
            let counts = store.itemCountsByFurniture()
            let bases = Set(pieces.compactMap(\.stackedOnId))
            let movingGroup: Set<UUID> = movingId.map { id in Set([id] + store.descendants(of: id).map(\.id)) } ?? []
            ZStack(alignment: .topLeading) {
                RoomFloor(room: room, layout: L)
                ForEach(pieces) { f in
                    node(f, L, count: counts[f.id] ?? 0, moving: movingGroup.contains(f.id), carries: bases.contains(f.id))
                }
                if editing, let id = selection, let f = store.furniture[id] {
                    resizeHandle(f, L)
                }
                if zoom != 1 || pan != .zero {
                    Button {
                        withAnimation(.snappy) { zoom = 1; pan = .zero }
                    } label: {
                        Image(systemName: "arrow.up.left.and.down.right.magnifyingglass")
                            .padding(9).background(.regularMaterial, in: Circle())
                    }
                    .accessibilityLabel("缩放到整个房间")
                    .position(x: geo.size.width - 30, y: 30)
                }
            }
            .contentShape(Rectangle())
            .gesture(panGesture.simultaneously(with: zoomGesture))
            .onTapGesture { selection = nil }
        }
        .clipped()
        .onChange(of: highlight) { _, h in
            if h != nil { withAnimation(.easeInOut(duration: 0.6).repeatForever(autoreverses: true)) { pulse = true } } else { pulse = false }
        }
        .onAppear {
            if highlight != nil { withAnimation(.easeInOut(duration: 0.6).repeatForever(autoreverses: true)) { pulse = true } }
        }
    }

    private func layout(_ size: CGSize) -> PlanLayout {
        let pad: CGFloat = 28
        let usableH = size.height - 110
        let fit = min((size.width - pad * 2) / room.width, (usableH - pad * 2) / room.depth)
        let s = max(0.05, fit) * zoom * pinch
        let cx = size.width / 2 + pan.width + panDrag.width
        let cy = pad + usableH / 2 - pad + pan.height + panDrag.height
        return PlanLayout(scale: s, origin: CGPoint(x: cx - room.width / 2 * s, y: cy - room.depth / 2 * s))
    }

    private var panGesture: some Gesture {
        DragGesture(minimumDistance: 4)
            .updating($panDrag) { v, s, _ in s = v.translation }
            .onEnded { v in pan.width += v.translation.width; pan.height += v.translation.height }
    }

    private var zoomGesture: some Gesture {
        MagnifyGesture()
            .updating($pinch) { v, s, _ in s = v.magnification }
            .onEnded { v in zoom = clamp(zoom * v.magnification, 0.5, 8) }
    }

    private func node(_ f: Furniture, _ L: PlanLayout, count: Int, moving: Bool, carries: Bool) -> some View {
        let resizing = resizingId == f.id ? resizeDrag : .zero
        let w = max(5, f.footprintWidth + resizing.width / L.scale) * L.scale
        let h = max(5, f.footprintDepth + resizing.height / L.scale) * L.scale
        let offset = moving ? moveDrag : .zero
        let origin = L.point(f.x, f.y)
        let isHighlighted = highlight == f.id
        return FurnitureNode(furniture: f, size: CGSize(width: w, height: h), itemCount: count,
                             selected: editing && selection == f.id, powered: ha.isPowered(f),
                             stacked: f.stackedOnId != nil, carries: carries, editing: editing)
            .overlay {
                if isHighlighted {
                    RoundedRectangle(cornerRadius: 4).stroke(Color.orange, lineWidth: 3)
                        .padding(-6)
                        .opacity(pulse ? 1 : 0.2)
                }
            }
            .position(x: origin.x + w / 2 + offset.width, y: origin.y + h / 2 + offset.height)
            .onTapGesture {
                if editing { selection = f.id } else { onOpen(f) }
            }
            .onLongPressGesture(minimumDuration: 0.4) {
                if !editing { Haptics.tap(); onLongPress(f) }
            }
            .gesture(moveGesture(f, L), including: editing ? .all : .none)
            .zIndex(moving ? 10 : (selection == f.id ? 5 : Double(store.stackDepth(f.id))))
    }

    private func moveGesture(_ f: Furniture, _ L: PlanLayout) -> some Gesture {
        DragGesture(minimumDistance: 3)
            .updating($moveDrag) { v, s, _ in s = v.translation }
            .onChanged { _ in
                if movingId != f.id { movingId = f.id; selection = f.id }
            }
            .onEnded { v in
                movingId = nil
                let nx = snap(f.x + v.translation.width / L.scale, to: 5)
                let ny = snap(f.y + v.translation.height / L.scale, to: 5)
                store.moveFurniture(f.id, to: CGPoint(x: nx, y: ny))
                Haptics.tap()
                guard let moved = store.furniture[f.id] else { return }
                // Dropped onto another piece? Offer to stack it there.
                let center = moved.center
                let excluded = Set([moved.id] + store.descendants(of: moved.id).map(\.id))
                let target = store.furniture(in: room.id).reversed().first { other in
                    !excluded.contains(other.id) && other.rect.contains(center)
                        && Catalog.kind(other.kind).layer != .floor && Catalog.kind(other.kind).style != .door
                }
                if let target, moved.stackedOnId != target.id {
                    onDropOnto(moved, target)
                } else if let parentId = moved.stackedOnId, let parent = store.furniture[parentId], !parent.rect.contains(center) {
                    store.setStack(moved.id, on: nil)  // dragged off what it stood on
                }
            }
    }

    private func resizeHandle(_ f: Furniture, _ L: PlanLayout) -> some View {
        let d = resizingId == f.id ? resizeDrag : .zero
        let corner = L.point(f.x + f.footprintWidth, f.y + f.footprintDepth)
        return Circle()
            .fill(Color.accentColor)
            .overlay(Image(systemName: "arrow.up.left.and.arrow.down.right").font(.system(size: 10, weight: .bold)).foregroundStyle(.white))
            .frame(width: 28, height: 28)
            .shadow(radius: 3)
            .position(x: corner.x + d.width, y: corner.y + d.height)
            .gesture(
                DragGesture(minimumDistance: 1)
                    .updating($resizeDrag) { v, s, _ in s = v.translation }
                    .onChanged { _ in resizingId = f.id }
                    .onEnded { v in
                        store.resizeFurniture(f.id,
                                              footprintWidth: snap(f.footprintWidth + v.translation.width / L.scale, to: 5),
                                              footprintDepth: snap(f.footprintDepth + v.translation.height / L.scale, to: 5))
                        resizingId = nil
                        Haptics.tap()
                    }
            )
            .accessibilityLabel("调整家具大小")
            .zIndex(20)
    }
}

/// Floor, 50 cm grid, walls and dimensions.
struct RoomFloor: View {
    let room: Room
    let layout: PlanLayout

    var body: some View {
        Canvas { ctx, _ in
            let s = layout.scale
            let rect = CGRect(origin: layout.origin, size: CGSize(width: room.width * s, height: room.depth * s))
            ctx.fill(Path(rect), with: .color(room.type.info.floor))
            var grid = Path()
            var x = 50.0
            while x < room.width { grid.move(to: layout.point(x, 0)); grid.addLine(to: layout.point(x, room.depth)); x += 50 }
            var y = 50.0
            while y < room.depth { grid.move(to: layout.point(0, y)); grid.addLine(to: layout.point(room.width, y)); y += 50 }
            ctx.stroke(grid, with: .color(.primary.opacity(0.06)), lineWidth: 0.5)
            let wall = max(4, min(9, s * 12))
            ctx.stroke(Path(rect.insetBy(dx: -wall / 2, dy: -wall / 2)), with: .color(Color.wallColor), lineWidth: wall)
            let label = Text("\(room.width.metres) m").font(.caption2).foregroundStyle(.secondary)
            ctx.draw(label, at: CGPoint(x: rect.midX, y: rect.maxY + wall + 9))
            var side = ctx
            side.translateBy(x: rect.minX - wall - 10, y: rect.midY)
            side.rotate(by: .degrees(-90))
            side.draw(Text("\(room.depth.metres) m").font(.caption2).foregroundStyle(.secondary), at: .zero)
        }
        .allowsHitTesting(false)
    }
}

struct FurnitureNode: View {
    let furniture: Furniture
    let size: CGSize
    let itemCount: Int
    let selected: Bool
    let powered: Bool
    let stacked: Bool
    /// Something stands on this piece; keep the label clear of it.
    var carries = false
    let editing: Bool

    var body: some View {
        ZStack(alignment: carries ? .bottomTrailing : .center) {
            Canvas { ctx, sz in FurnitureArt.draw(furniture, in: ctx, size: sz) }
                .frame(width: size.width, height: size.height)
            if size.width > 34 && size.height > 16 {
                Text(furniture.name)
                    .font(.system(size: clamp(min(size.width / 6, size.height / 2.6), 8, 12), weight: .medium))
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                    .padding(.horizontal, 4).padding(.vertical, 1)
                    .background(.thinMaterial, in: Capsule())
                    .padding(2)
            }
        }
        .frame(width: size.width, height: size.height)
        .overlay(alignment: .topTrailing) {
            HStack(spacing: 2) {
                if powered {
                    Image(systemName: "bolt.fill").font(.system(size: 8)).foregroundStyle(.yellow)
                        .padding(3).background(.black.opacity(0.55), in: Circle())
                }
                if itemCount > 0 && !editing {
                    Text("\(itemCount)").font(.system(size: 9, weight: .bold)).foregroundStyle(.white)
                        .padding(.horizontal, 4).padding(.vertical, 1)
                        .background(Color.accentColor, in: Capsule())
                }
            }
            .offset(x: 6, y: -6)
        }
        .shadow(color: powered ? .yellow.opacity(0.8) : .black.opacity(stacked ? 0.25 : 0), radius: powered ? 8 : 3, y: stacked ? 2 : 0)
        .overlay {
            if selected {
                RoundedRectangle(cornerRadius: 3).stroke(Color.accentColor, style: StrokeStyle(lineWidth: 2, dash: [5, 3]))
                    .padding(-3)
            }
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(furniture.name)\(itemCount > 0 ? "，\(itemCount) 件物品" : "")")
        .accessibilityIdentifier("furniture.\(furniture.name)")
        .accessibilityAddTraits(.isButton)
    }
}

// MARK: - Inspector

struct FurnitureInspector: View {
    let furniture: Furniture
    var onOpen: () -> Void
    var onEdit: () -> Void
    var onDelete: () -> Void
    @Environment(HomeStore.self) private var store

    var body: some View {
        let f = furniture
        let candidates = store.furniture(in: f.roomId).filter { $0.id != f.id && !store.isDescendant($0.id, of: f.id) && Catalog.kind($0.kind).layer != .floor }
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(f.name).font(.headline)
                if let p = f.stackedOnId.flatMap({ store.furniture[$0] }) {
                    Pill(text: "在\(p.name)上", color: .accentColor, systemImage: "square.stack.3d.up")
                }
                Spacer()
                Text("\(Int(f.footprintWidth)) × \(Int(f.footprintDepth)) cm").font(.subheadline).foregroundStyle(.secondary)
            }
            HStack(spacing: 8) {
                tool("旋转", "rotate.right") { store.rotateFurniture(f.id); Haptics.tap() }
                tool("复制", "plus.square.on.square") { store.duplicateFurniture(f.id) }
                Menu {
                    if f.stackedOnId != nil {
                        Button { store.setStack(f.id, on: nil) } label: { Label("放回地面", systemImage: "arrow.down.to.line") }
                    }
                    ForEach(candidates) { c in
                        Button("放在「\(c.name)」上") { store.setStack(f.id, on: c.id); Haptics.success() }
                    }
                    if candidates.isEmpty && f.stackedOnId == nil {
                        Text("房间里没有可以叠放的家具")
                    }
                } label: {
                    toolLabel("叠放", "square.stack.3d.up")
                }
                tool("属性", "slider.horizontal.3", action: onEdit)
                tool("打开", "door.left.hand.open", action: onOpen)
                tool("删除", "trash", role: .destructive, action: onDelete)
            }
        }
        .card()
        .shadow(color: .black.opacity(0.08), radius: 12, y: 4)
    }

    private func tool(_ title: String, _ symbol: String, role: ButtonRole? = nil, action: @escaping () -> Void) -> some View {
        Button(role: role, action: action) { toolLabel(title, symbol) }
            .tint(role == .destructive ? .red : .primary)
    }

    private func toolLabel(_ title: String, _ symbol: String) -> some View {
        VStack(spacing: 4) {
            Image(systemName: symbol).font(.system(size: 17))
            Text(title).font(.caption2)
        }
        .frame(maxWidth: .infinity, minHeight: 44)
        .background(Color.gray.opacity(0.1), in: RoundedRectangle(cornerRadius: 10))
    }
}

// MARK: - List mode

struct FurnitureListView: View {
    let room: Room
    var onEdit: (Furniture) -> Void
    var onDelete: (Furniture) -> Void
    @Environment(HomeStore.self) private var store

    var body: some View {
        let pieces = store.furniture(in: room.id)
        let counts = store.itemCountsByFurniture()
        let roots = pieces.filter { f in f.stackedOnId == nil || store.furniture[f.stackedOnId!] == nil }
        List {
            if pieces.isEmpty {
                Text("还没有家具").foregroundStyle(.secondary)
            }
            ForEach(roots) { f in
                row(f, depth: 0, count: counts[f.id] ?? 0)
                ForEach(store.descendants(of: f.id)) { child in
                    row(child, depth: store.stackDepth(child.id), count: counts[child.id] ?? 0)
                }
            }
        }
        .readableWidth()
    }

    private func row(_ f: Furniture, depth: Int, count: Int) -> some View {
        NavigationLink(value: Route.furniture(f.id)) {
            HStack(spacing: 12) {
                if depth > 0 {
                    Image(systemName: "arrow.turn.down.right").foregroundStyle(.tertiary)
                        .padding(.leading, CGFloat(depth - 1) * 16)
                }
                Canvas { ctx, size in FurnitureArt.draw(f, in: ctx, size: size) }
                    .frame(width: 40, height: 40 * min(2, max(0.3, f.footprintDepth / max(f.footprintWidth, 1))))
                    .frame(width: 44, height: 44)
                VStack(alignment: .leading) {
                    Text(f.name)
                    Text("\(store.compartments(of: f.id).count) 个收纳位 · \(count) 件物品")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
        }
        .swipeActions {
            Button(role: .destructive) { onDelete(f) } label: { Label("删除", systemImage: "trash") }
            Button { onEdit(f) } label: { Label("编辑", systemImage: "pencil") }.tint(.blue)
        }
    }
}

extension View {
    func deleteFurnitureDialog(_ furniture: Binding<Furniture?>, onDelete: @escaping () -> Void = {}) -> some View {
        modifier(DeleteFurnitureDialog(furniture: furniture, onDelete: onDelete))
    }
}

private struct DeleteFurnitureDialog: ViewModifier {
    @Binding var furniture: Furniture?
    var onDelete: () -> Void
    @Environment(HomeStore.self) private var store

    func body(content: Content) -> some View {
        let count = furniture.map { store.itemCount(furniture: $0.id) } ?? 0
        let onTop = furniture.map { store.children(of: $0.id).count } ?? 0
        content.confirmationDialog(
            "删除「\(furniture?.name ?? "")」？",
            isPresented: Binding(get: { furniture != nil }, set: { if !$0 { furniture = nil } }),
            titleVisibility: .visible,
            presenting: furniture
        ) { f in
            if count > 0 {
                Button("删除家具，物品移到未归位") { store.deleteFurniture(f.id, policy: .keepItems); onDelete() }
                Button("连同 \(count) 件物品一起删除", role: .destructive) { store.deleteFurniture(f.id, policy: .deleteItems); onDelete() }
            } else {
                Button("删除", role: .destructive) { store.deleteFurniture(f.id, policy: .keepItems); onDelete() }
            }
            Button("取消", role: .cancel) {}
        } message: { _ in
            let parts = [count > 0 ? "里面有 \(count) 件物品" : nil, onTop > 0 ? "上面叠放的 \(onTop) 件家具会保留" : nil].compactMap { $0 }
            Text(parts.isEmpty ? "此操作无法撤销。" : parts.joined(separator: "，") + "。")
        }
    }
}
