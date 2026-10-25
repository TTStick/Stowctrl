import SwiftUI

/// Maps centimetres on the plan to points on screen.
struct PlanLayout {
    var scale: CGFloat
    var origin: CGPoint

    func point(_ x: Double, _ y: Double) -> CGPoint {
        CGPoint(x: origin.x + x * scale, y: origin.y + y * scale)
    }
}

struct HomeView: View {
    @Environment(HomeStore.self) private var store
    @Environment(AppRouter.self) private var router
    @State private var editing = false
    @State private var selection: UUID?
    @State private var showList = false
    @State private var addingRoom = false
    @State private var editingRoom: Room?
    @State private var deletingRoom: Room?

    var body: some View {
        @Bindable var router = router
        NavigationStack(path: $router.homePath) {
            Group {
                if store.rooms.isEmpty {
                    ContentUnavailableView {
                        Label("还没有房间", systemImage: "house")
                    } description: {
                        Text("先添加房间，再把家具摆进去。")
                    } actions: {
                        Button("添加房间") { addingRoom = true }.buttonStyle(.borderedProminent)
                    }
                } else if showList {
                    roomList
                } else {
                    plan
                }
            }
            .background(Color.canvasBackground)
            .navigationTitle(store.settings.homeName)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { toolbar }
            .navigationDestination(for: Route.self) { RouteView(route: $0) }
            .sheet(isPresented: $addingRoom) { RoomEditorSheet(room: nil) }
            .sheet(item: $editingRoom) { RoomEditorSheet(room: $0) }
            .deleteRoomDialog($deletingRoom) { selection = nil }
        }
    }

    @ToolbarContentBuilder private var toolbar: some ToolbarContent {
        ToolbarItem(placement: .topBarLeading) {
            Button {
                withAnimation { showList.toggle(); editing = false; selection = nil }
            } label: {
                Image(systemName: showList ? "square.grid.3x3.square" : "list.bullet")
            }
            .accessibilityLabel(showList ? "户型图" : "房间列表")
            .disabled(store.rooms.isEmpty)
        }
        ToolbarItemGroup(placement: .topBarTrailing) {
            if !showList && !store.rooms.isEmpty {
                Button(editing ? "完成" : "编辑户型") {
                    withAnimation { editing.toggle(); selection = nil }
                }
                .fontWeight(editing ? .semibold : .regular)
            }
            Button { addingRoom = true } label: { Image(systemName: "plus") }
                .accessibilityLabel("添加房间")
        }
    }

    private var plan: some View {
        ZStack(alignment: .bottom) {
            FloorPlanCanvas(editing: editing, selection: $selection) { room in
                router.homePath.append(.room(room.id))
            }
            VStack(spacing: 10) {
                if editing, let id = selection, let room = store.rooms[id] {
                    roomInspector(room)
                } else if editing {
                    hint("拖动房间调整位置，拖右下角圆点改大小，点房间查看操作")
                } else {
                    summaryBar
                }
            }
            .padding(.horizontal)
            .padding(.bottom, 10)
        }
    }

    private func hint(_ text: String) -> some View {
        Text(text)
            .font(.footnote)
            .foregroundStyle(.secondary)
            .padding(.horizontal, 14).padding(.vertical, 10)
            .background(.regularMaterial, in: Capsule())
    }

    private var summaryBar: some View {
        let unsorted = store.unsortedItems.count
        return HStack(spacing: 12) {
            Label("\(store.rooms.count) 个房间", systemImage: "square.split.2x2")
            Label("\(store.items.count) 件物品", systemImage: "shippingbox")
            if unsorted > 0 {
                Button {
                    router.homePath.append(.unsorted)
                } label: {
                    Label("\(unsorted) 件未归位", systemImage: "tray")
                        .foregroundStyle(.orange)
                }
            }
        }
        .font(.footnote)
        .padding(.horizontal, 14).padding(.vertical, 10)
        .background(.regularMaterial, in: Capsule())
    }

    private func roomInspector(_ room: Room) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Image(systemName: room.type.info.symbol)
                Text(room.name).font(.headline)
                Spacer()
                Text("\(room.width.metres) × \(room.depth.metres) m").font(.subheadline).foregroundStyle(.secondary)
            }
            HStack(spacing: 10) {
                Button { router.homePath.append(.room(room.id)) } label: {
                    Label("布置家具", systemImage: "chair.lounge").frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                Button { editingRoom = room } label: { Image(systemName: "pencil").frame(width: 28) }
                    .buttonStyle(.bordered)
                    .accessibilityLabel("编辑房间")
                Button(role: .destructive) { deletingRoom = room } label: { Image(systemName: "trash").frame(width: 28) }
                    .buttonStyle(.bordered)
                    .accessibilityLabel("删除房间")
            }
        }
        .card()
        .shadow(color: .black.opacity(0.08), radius: 12, y: 4)
    }

    private var roomList: some View {
        let counts = store.itemCountsByRoom()
        return List {
            ForEach(store.sortedRooms) { room in
                NavigationLink(value: Route.room(room.id)) {
                    HStack(spacing: 12) {
                        Image(systemName: room.type.info.symbol)
                            .frame(width: 36, height: 36)
                            .background(room.type.info.floor, in: RoundedRectangle(cornerRadius: 8))
                        VStack(alignment: .leading) {
                            Text(room.name).font(.body.weight(.medium))
                            Text("\(store.furniture(in: room.id).count) 件家具 · \(counts[room.id] ?? 0) 件物品")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
                .swipeActions {
                    Button(role: .destructive) { deletingRoom = room } label: { Label("删除", systemImage: "trash") }
                    Button { editingRoom = room } label: { Label("编辑", systemImage: "pencil") }.tint(.blue)
                }
            }
            .onMove { from, to in
                var rooms = store.sortedRooms
                rooms.move(fromOffsets: from, toOffset: to)
                for (i, var r) in rooms.enumerated() where r.sortOrder != i {
                    r.sortOrder = i
                    store.save(r)
                }
            }
            if !store.unsortedItems.isEmpty {
                Section {
                    NavigationLink(value: Route.unsorted) {
                        Label("未归位物品（\(store.unsortedItems.count)）", systemImage: "tray")
                    }
                }
            }
        }
        .toolbar { EditButton() }
    }
}

// MARK: - Floor plan canvas

struct FloorPlanCanvas: View {
    let editing: Bool
    @Binding var selection: UUID?
    var onOpen: (Room) -> Void

    @Environment(HomeStore.self) private var store
    @State private var zoom: CGFloat = 1
    @State private var pan: CGSize = .zero
    @GestureState private var pinch: CGFloat = 1
    @GestureState private var panDrag: CGSize = .zero
    @GestureState private var moveDrag: CGSize = .zero
    @State private var movingId: UUID?
    @GestureState private var resizeDrag: CGSize = .zero
    @State private var resizingId: UUID?
    @State private var frozenWorld: CGRect?

    var body: some View {
        GeometryReader { geo in
            let rooms = store.sortedRooms
            let world = frozenWorld ?? Self.world(rooms)
            let L = layout(world: world, size: geo.size)
            let counts = store.itemCountsByRoom()
            ZStack(alignment: .topLeading) {
                Color.clear
                ForEach(rooms) { room in
                    tile(room, L, count: counts[room.id] ?? 0)
                }
                if editing, let id = selection, let room = store.rooms[id] {
                    resizeHandle(room, L)
                }
                fitButton.position(x: geo.size.width - 30, y: 30)
            }
            .contentShape(Rectangle())
            .gesture(panGesture.simultaneously(with: zoomGesture))
            .onTapGesture { selection = nil }
            .onChange(of: editing) { _, on in frozenWorld = on ? Self.world(rooms) : nil }
            .onChange(of: rooms.count) { _, _ in if editing { frozenWorld = Self.world(store.sortedRooms) } }
        }
        .clipped()
    }

    static func world(_ rooms: [Room]) -> CGRect {
        let union = rooms.reduce(CGRect.null) { $0.union($1.rect) }
        return union.isNull ? CGRect(x: 0, y: 0, width: 600, height: 600) : union.insetBy(dx: -60, dy: -60)
    }

    private func layout(world: CGRect, size: CGSize) -> PlanLayout {
        let pad: CGFloat = 20
        let fit = min((size.width - pad * 2) / world.width, (size.height - pad * 2 - 60) / world.height)
        let s = max(0.02, fit) * zoom * pinch
        let cx = size.width / 2 + pan.width + panDrag.width
        let cy = (size.height - 60) / 2 + pan.height + panDrag.height
        return PlanLayout(scale: s, origin: CGPoint(x: cx - world.midX * s, y: cy - world.midY * s))
    }

    private var fitButton: some View {
        Button {
            withAnimation(.snappy) { zoom = 1; pan = .zero }
        } label: {
            Image(systemName: "arrow.up.left.and.down.right.magnifyingglass")
                .padding(9)
                .background(.regularMaterial, in: Circle())
        }
        .accessibilityLabel("缩放到全屋")
        .opacity(zoom == 1 && pan == .zero ? 0 : 1)
    }

    private var panGesture: some Gesture {
        DragGesture(minimumDistance: 4)
            .updating($panDrag) { v, s, _ in s = v.translation }
            .onEnded { v in pan.width += v.translation.width; pan.height += v.translation.height }
    }

    private var zoomGesture: some Gesture {
        MagnifyGesture()
            .updating($pinch) { v, s, _ in s = v.magnification }
            .onEnded { v in zoom = clamp(zoom * v.magnification, 0.4, 8) }
    }

    private func tile(_ room: Room, _ L: PlanLayout, count: Int) -> some View {
        let moving = movingId == room.id ? moveDrag : .zero
        let resizing = resizingId == room.id ? resizeDrag : .zero
        let w = max(50, room.width + resizing.width / L.scale) * L.scale
        let h = max(50, room.depth + resizing.height / L.scale) * L.scale
        let origin = L.point(room.x, room.y)
        return RoomTileView(room: room, furniture: store.furniture(in: room.id), scale: L.scale,
                            itemCount: count, selected: editing && selection == room.id)
            .frame(width: w, height: h)
            .position(x: origin.x + w / 2 + moving.width, y: origin.y + h / 2 + moving.height)
            .onTapGesture {
                if editing { selection = room.id } else { onOpen(room) }
            }
            .gesture(moveGesture(room, L), including: editing ? .all : .none)
            .zIndex(selection == room.id ? 1 : 0)
    }

    private func moveGesture(_ room: Room, _ L: PlanLayout) -> some Gesture {
        DragGesture(minimumDistance: 3)
            .updating($moveDrag) { v, s, _ in s = v.translation }
            .onChanged { _ in
                if movingId != room.id { movingId = room.id; selection = room.id }
            }
            .onEnded { v in
                let target = CGPoint(x: room.x + v.translation.width / L.scale, y: room.y + v.translation.height / L.scale)
                store.moveRoom(room.id, to: snapped(room, target))
                movingId = nil
                Haptics.tap()
            }
    }

    /// Aligns with neighbouring rooms' walls when close, otherwise a 10 cm grid.
    private func snapped(_ room: Room, _ p: CGPoint) -> CGPoint {
        let others = store.rooms.values.filter { $0.id != room.id }
        let threshold = 30.0
        func best(_ value: Double, _ candidates: [Double]) -> Double {
            let near = candidates.map { ($0, abs($0 - value)) }.filter { $0.1 < threshold }.min { $0.1 < $1.1 }
            return near?.0 ?? snap(value, to: 10)
        }
        let xs = others.flatMap { [$0.x, $0.x + $0.width, $0.x - room.width, $0.x + $0.width - room.width] }
        let ys = others.flatMap { [$0.y, $0.y + $0.depth, $0.y - room.depth, $0.y + $0.depth - room.depth] }
        return CGPoint(x: best(p.x, xs), y: best(p.y, ys))
    }

    private func resizeHandle(_ room: Room, _ L: PlanLayout) -> some View {
        let d = resizingId == room.id ? resizeDrag : .zero
        let corner = L.point(room.x + room.width, room.y + room.depth)
        return Circle()
            .fill(Color.accentColor)
            .overlay(Image(systemName: "arrow.up.left.and.arrow.down.right").font(.system(size: 11, weight: .bold)).foregroundStyle(.white))
            .frame(width: 30, height: 30)
            .shadow(radius: 3)
            .position(x: corner.x + d.width, y: corner.y + d.height)
            .gesture(
                DragGesture(minimumDistance: 1)
                    .updating($resizeDrag) { v, s, _ in s = v.translation }
                    .onChanged { _ in resizingId = room.id }
                    .onEnded { v in
                        store.resizeRoom(room.id, width: snap(room.width + v.translation.width / L.scale, to: 10),
                                         depth: snap(room.depth + v.translation.height / L.scale, to: 10))
                        resizingId = nil
                        Haptics.tap()
                    }
            )
            .accessibilityLabel("调整房间大小")
            .zIndex(2)
    }
}

/// A room drawn from above with its furniture in miniature.
struct RoomTileView: View {
    let room: Room
    let furniture: [Furniture]
    let scale: CGFloat
    let itemCount: Int
    let selected: Bool

    var body: some View {
        ZStack(alignment: .topLeading) {
            Canvas { ctx, size in
                let rect = CGRect(origin: .zero, size: size)
                ctx.fill(Path(rect), with: .color(room.type.info.floor))
                for f in furniture {
                    let fr = CGRect(x: f.x * scale, y: f.y * scale, width: f.footprintWidth * scale, height: f.footprintDepth * scale)
                    var c = ctx
                    c.translateBy(x: fr.minX, y: fr.minY)
                    FurnitureArt.draw(f, in: c, size: fr.size)
                }
                let wall = max(2, min(5, scale * 8))
                ctx.stroke(Path(rect.insetBy(dx: wall / 2, dy: wall / 2)), with: .color(Color.wallColor), lineWidth: wall)
            }
            VStack(alignment: .leading, spacing: 1) {
                Text(room.name).font(.caption.weight(.semibold)).lineLimit(1)
                if itemCount > 0 {
                    Text("\(itemCount) 件").font(.caption2).foregroundStyle(.secondary)
                }
            }
            .padding(.horizontal, 6).padding(.vertical, 3)
            .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 6))
            .padding(6)
        }
        .overlay {
            if selected {
                Rectangle().stroke(Color.accentColor, lineWidth: 3)
            }
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(room.name)，\(itemCount) 件物品")
        .accessibilityIdentifier("room.\(room.name)")
        .accessibilityAddTraits(.isButton)
    }
}

// MARK: - Room editor

struct RoomEditorSheet: View {
    let room: Room?
    @Environment(HomeStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var type: RoomType = .living
    @State private var width: Double = 400
    @State private var depth: Double = 300
    @State private var withPreset = true
    @State private var nameEdited = false

    var body: some View {
        NavigationStack {
            Form {
                Section("房间类型") {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 72))], spacing: 10) {
                        ForEach(RoomType.allCases) { t in
                            Button {
                                type = t
                                if room == nil {
                                    width = t.info.width
                                    depth = t.info.depth
                                    if !nameEdited { name = t.info.title }
                                }
                            } label: {
                                VStack(spacing: 4) {
                                    Image(systemName: t.info.symbol).font(.title3)
                                    Text(t.info.title).font(.caption)
                                }
                                .frame(maxWidth: .infinity, minHeight: 56)
                                .background(type == t ? Color.accentColor.opacity(0.18) : Color.gray.opacity(0.08),
                                            in: RoundedRectangle(cornerRadius: 10))
                                .overlay(RoundedRectangle(cornerRadius: 10).stroke(type == t ? Color.accentColor : .clear, lineWidth: 1.5))
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.vertical, 4)
                }
                Section("名称") {
                    TextField("例如：主卧", text: $name)
                        .onChange(of: name) { _, _ in nameEdited = true }
                }
                Section {
                    CentimetreField(title: "宽", value: $width, range: 50...3000)
                    CentimetreField(title: "深", value: $depth, range: 50...3000)
                } header: {
                    Text("尺寸")
                } footer: {
                    Text("约 \((width * depth / 10000).clean) ㎡。之后也可以在户型图里拖动调整。")
                }
                if room == nil {
                    Section {
                        Toggle("放入推荐家具", isOn: $withPreset)
                    } footer: {
                        Text("按房间类型摆好常见家具，之后可以随意修改或删除。")
                    }
                }
            }
            .navigationTitle(room == nil ? "添加房间" : "编辑房间")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存", action: save).disabled(name.trimmed.isEmpty)
                }
            }
            .onAppear {
                if let room {
                    name = room.name; type = room.type; width = room.width; depth = room.depth
                } else {
                    name = type.info.title; width = type.info.width; depth = type.info.depth
                }
                nameEdited = false
            }
        }
    }

    private func save() {
        if var r = room {
            r.name = name.trimmed
            r.type = type
            store.save(r)
            if r.width != width || r.depth != depth { store.resizeRoom(r.id, width: width, depth: depth) }
        } else {
            store.addRoom(type: type, name: name.trimmed, width: width, depth: depth, withPreset: withPreset)
        }
        dismiss()
    }
}

extension View {
    /// Confirmation for deleting a room, asking what happens to its items.
    func deleteRoomDialog(_ room: Binding<Room?>, onDelete: @escaping () -> Void = {}) -> some View {
        modifier(DeleteRoomDialog(room: room, onDelete: onDelete))
    }
}

private struct DeleteRoomDialog: ViewModifier {
    @Binding var room: Room?
    var onDelete: () -> Void
    @Environment(HomeStore.self) private var store

    func body(content: Content) -> some View {
        let count = room.map { store.itemCountsByRoom()[$0.id] ?? 0 } ?? 0
        content.confirmationDialog(
            "删除「\(room?.name ?? "")」？",
            isPresented: Binding(get: { room != nil }, set: { if !$0 { room = nil } }),
            titleVisibility: .visible,
            presenting: room
        ) { r in
            if count > 0 {
                Button("删除房间，物品移到未归位") { store.deleteRoom(r.id, policy: .keepItems); onDelete() }
                Button("连同 \(count) 件物品一起删除", role: .destructive) { store.deleteRoom(r.id, policy: .deleteItems); onDelete() }
            } else {
                Button("删除房间", role: .destructive) { store.deleteRoom(r.id, policy: .keepItems); onDelete() }
            }
            Button("取消", role: .cancel) {}
        } message: { _ in
            Text(count > 0 ? "房间里的家具会一起删除，里面有 \(count) 件物品。" : "房间里的家具会一起删除。")
        }
    }
}
