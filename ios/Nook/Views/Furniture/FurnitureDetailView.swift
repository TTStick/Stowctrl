import SwiftUI

/// A piece of furniture "opened": its doors swing aside to show each
/// compartment as a shelf with what's stored there.
struct FurnitureDetailView: View {
    let furnitureId: UUID
    @Environment(HomeStore.self) private var store
    @Environment(AppRouter.self) private var router
    @Environment(HomeAssistantManager.self) private var ha
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var doorsOpen = false
    @State private var addingTo: AddTarget?
    @State private var editing: Furniture?
    @State private var deleting: Furniture?
    @State private var managingCompartments = false
    @State private var bindingDevices = false
    @State private var newCompartment = false
    @State private var newCompartmentName = ""

    struct AddTarget: Identifiable {
        let compartmentId: UUID?
        var id: String { compartmentId?.uuidString ?? "none" }
    }

    var body: some View {
        if let f = store.furniture[furnitureId] {
            content(f)
        } else {
            ContentUnavailableView("家具已删除", systemImage: "trash", description: Text("这件家具可能在另一台设备上被删除了。"))
        }
    }

    private func content(_ f: Furniture) -> some View {
        let comps = store.compartments(of: f.id)
        return ScrollView {
            if sizeClass == .regular {
                // iPad: the open cabinet on the left, details beside it.
                HStack(alignment: .top, spacing: 20) {
                    cabinet(f, comps).frame(maxWidth: .infinity)
                    VStack(alignment: .leading, spacing: 18) {
                        devicesSection(f)
                        stackSection(f)
                        infoSection(f)
                    }
                    .frame(width: 340)
                }
                .frame(maxWidth: 1100)
                .padding()
                .frame(maxWidth: .infinity)
            } else {
                VStack(alignment: .leading, spacing: 18) {
                    cabinet(f, comps)
                    devicesSection(f)
                    stackSection(f)
                    infoSection(f)
                }
                .padding()
            }
        }
        .background(Color.canvasBackground)
        .navigationTitle(f.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button {
                    addingTo = AddTarget(compartmentId: comps.first?.id)
                } label: { Image(systemName: "plus") }
                .accessibilityLabel("添加物品")
                Menu {
                    Button { editing = f } label: { Label("家具属性", systemImage: "slider.horizontal.3") }
                    Button { managingCompartments = true } label: { Label("管理收纳位置", systemImage: "square.split.1x2") }
                    Button { bindingDevices = true } label: { Label("绑定智能设备", systemImage: "homekit") }
                    Button { router.showOnPlan(f) } label: { Label("在户型图中查看", systemImage: "map") }
                    Divider()
                    Button(role: .destructive) { deleting = f } label: { Label("删除家具", systemImage: "trash") }
                } label: { Image(systemName: "ellipsis.circle") }
            }
        }
        .sheet(item: $addingTo) { target in
            ItemEditorView(mode: .create(compartmentId: target.compartmentId))
        }
        .sheet(item: $editing) { FurnitureEditorSheet(mode: .edit($0)) }
        .sheet(isPresented: $managingCompartments) { CompartmentManagerSheet(furnitureId: f.id) }
        .sheet(isPresented: $bindingDevices) { EntityPickerSheet(furnitureId: f.id) }
        .deleteFurnitureDialog($deleting) { dismiss() }
        .alert("新的收纳位置", isPresented: $newCompartment) {
            TextField("例如：第 2 层", text: $newCompartmentName)
            Button("取消", role: .cancel) {}
            Button("添加") {
                if !newCompartmentName.trimmed.isEmpty { store.addCompartment(to: f.id, name: newCompartmentName.trimmed) }
            }
        }
        .onAppear {
            if reduceMotion { doorsOpen = true; return }
            withAnimation(.spring(response: 0.7, dampingFraction: 0.75).delay(0.15)) { doorsOpen = true }
        }
        .task(id: f.haEntities) {
            if !f.haEntities.isEmpty { await ha.refresh() }
        }
    }

    // MARK: Cabinet

    private func cabinet(_ f: Furniture, _ comps: [Compartment]) -> some View {
        let palette = FurnitureArt.palette(for: f)
        return VStack(spacing: 0) {
            VStack(spacing: 0) {
                if comps.isEmpty {
                    VStack(spacing: 10) {
                        Image(systemName: "square.dashed").font(.largeTitle).foregroundStyle(.secondary)
                        Text("还没有收纳位置").font(.headline)
                        Text("给这件家具加上层、抽屉或格子，就能往里放东西了。")
                            .font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center)
                        Button("添加收纳位置") { newCompartmentName = ""; newCompartment = true }
                            .buttonStyle(.borderedProminent)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(28)
                } else {
                    ForEach(Array(comps.enumerated()), id: \.element.id) { index, c in
                        ShelfRow(compartment: c, onAdd: { addingTo = AddTarget(compartmentId: c.id) })
                        if index < comps.count - 1 {
                            Rectangle().fill(palette.fill).frame(height: 6)
                        }
                    }
                }
            }
            .background(Color.cardBackground)
            .clipShape(RoundedRectangle(cornerRadius: 6))
            .padding(8)
            .background(palette.fill, in: RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(palette.stroke, lineWidth: 1.5))
            .overlay { doors(palette) }
            if !comps.isEmpty {
                Button { newCompartmentName = ""; newCompartment = true } label: {
                    Label("添加收纳位置", systemImage: "plus").font(.footnote)
                }
                .padding(.top, 10)
            }
        }
    }

    /// Two door panels that swing open on appear.
    private func doors(_ p: FurnitureArt.Palette) -> some View {
        GeometryReader { geo in
            HStack(spacing: 0) {
                door(p, width: geo.size.width / 2, left: true)
                door(p, width: geo.size.width / 2, left: false)
            }
        }
        .allowsHitTesting(!doorsOpen)
        .onTapGesture { withAnimation(.spring) { doorsOpen = true } }
    }

    private func door(_ p: FurnitureArt.Palette, width: CGFloat, left: Bool) -> some View {
        RoundedRectangle(cornerRadius: 10)
            .fill(p.fill)
            .overlay(RoundedRectangle(cornerRadius: 10).stroke(p.stroke, lineWidth: 1.5))
            .overlay(alignment: left ? .trailing : .leading) {
                Capsule().fill(p.stroke).frame(width: 4, height: 34).padding(10)
            }
            .frame(width: width)
            .rotation3DEffect(.degrees(doorsOpen ? (left ? -100 : 100) : 0), axis: (0, 1, 0),
                              anchor: left ? .leading : .trailing, perspective: 0.5)
            .opacity(doorsOpen ? 0 : 1)
    }

    // MARK: Sections

    @ViewBuilder private func devicesSection(_ f: Furniture) -> some View {
        if !f.haEntities.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    Label("智能设备", systemImage: "homekit").font(.headline)
                    Spacer()
                    Button("管理") { bindingDevices = true }.font(.subheadline)
                }
                if let err = ha.error { Text(err).font(.footnote).foregroundStyle(.red) }
                ForEach(f.haEntities, id: \.self) { id in
                    EntityRow(entityId: id)
                    if id != f.haEntities.last { Divider() }
                }
            }
            .card()
        }
    }

    @ViewBuilder private func stackSection(_ f: Furniture) -> some View {
        let parent = f.stackedOnId.flatMap { store.furniture[$0] }
        let kids = store.children(of: f.id)
        if parent != nil || !kids.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                Label("叠放", systemImage: "square.stack.3d.up").font(.headline)
                if let parent {
                    NavigationLink(value: Route.furniture(parent.id)) {
                        HStack {
                            Text("放在").foregroundStyle(.secondary)
                            Text(parent.name)
                            Spacer()
                            Image(systemName: "chevron.right").foregroundStyle(.tertiary)
                        }
                    }
                    .buttonStyle(.plain)
                }
                ForEach(kids) { k in
                    NavigationLink(value: Route.furniture(k.id)) {
                        HStack {
                            Text("上面放着").foregroundStyle(.secondary)
                            Text(k.name)
                            Spacer()
                            Image(systemName: "chevron.right").foregroundStyle(.tertiary)
                        }
                    }
                    .buttonStyle(.plain)
                }
            }
            .card()
        }
    }

    private func infoSection(_ f: Furniture) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("位置").foregroundStyle(.secondary)
                Spacer()
                Button(store.rooms[f.roomId]?.name ?? "") { router.showOnPlan(f) }
            }
            HStack {
                Text("尺寸").foregroundStyle(.secondary)
                Spacer()
                Text("\(Int(f.width)) × \(Int(f.depth)) cm")
            }
            HStack {
                Text("物品").foregroundStyle(.secondary)
                Spacer()
                Text("\(store.itemCount(furniture: f.id)) 件")
            }
            if !f.notes.isEmpty {
                Divider()
                Text(f.notes).font(.subheadline)
            }
            if f.haEntities.isEmpty {
                Divider()
                Button { bindingDevices = true } label: { Label("绑定 Home Assistant 设备", systemImage: "homekit") }
            }
        }
        .card()
    }
}

/// One compartment as a shelf: name, count, a strip of thumbnails.
struct ShelfRow: View {
    let compartment: Compartment
    var onAdd: () -> Void
    @Environment(HomeStore.self) private var store

    var body: some View {
        let items = store.items(in: compartment.id)
        VStack(alignment: .leading, spacing: 8) {
            NavigationLink(value: Route.compartment(compartment.id)) {
                HStack {
                    Text(compartment.name).font(.subheadline.weight(.semibold))
                    Text("\(items.count)").font(.caption).foregroundStyle(.secondary)
                    Spacer()
                    Image(systemName: "chevron.right").font(.caption).foregroundStyle(.tertiary)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 10) {
                    ForEach(items.prefix(20)) { item in
                        NavigationLink(value: Route.item(item.id)) {
                            VStack(spacing: 4) {
                                ItemThumbnail(item: item, size: 56)
                                    .overlay(alignment: .topTrailing) {
                                        if item.quantity != 1 {
                                            Text(item.quantity.clean)
                                                .font(.system(size: 10, weight: .bold))
                                                .padding(.horizontal, 4).padding(.vertical, 1)
                                                .background(.thinMaterial, in: Capsule())
                                                .offset(x: 4, y: -4)
                                        }
                                    }
                                Text(item.name).font(.caption2).lineLimit(1).frame(width: 60)
                            }
                        }
                        .buttonStyle(.plain)
                    }
                    Button(action: onAdd) {
                        VStack(spacing: 4) {
                            RoundedRectangle(cornerRadius: 12, style: .continuous)
                                .strokeBorder(style: StrokeStyle(lineWidth: 1.2, dash: [4, 3]))
                                .foregroundStyle(.secondary)
                                .frame(width: 56, height: 56)
                                .overlay(Image(systemName: "plus").foregroundStyle(.secondary))
                            Text("放入").font(.caption2).foregroundStyle(.secondary)
                        }
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("放入物品到\(compartment.name)")
                }
            }
        }
        .padding(12)
    }
}

/// Add, rename, reorder and delete compartments.
struct CompartmentManagerSheet: View {
    let furnitureId: UUID
    @Environment(HomeStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @State private var renaming: Compartment?
    @State private var renameText = ""
    @State private var adding = false
    @State private var addText = ""
    @State private var deleting: Compartment?

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(store.compartments(of: furnitureId)) { c in
                        Button {
                            renameText = c.name
                            renaming = c
                        } label: {
                            HStack {
                                Text(c.name).foregroundStyle(Color.primary)
                                Spacer()
                                Text("\(store.items(in: c.id).count) 件").foregroundStyle(.secondary)
                            }
                        }
                        .swipeActions {
                            Button(role: .destructive) { deleting = c } label: { Label("删除", systemImage: "trash") }
                        }
                    }
                    .onMove { from, to in
                        var list = store.compartments(of: furnitureId)
                        list.move(fromOffsets: from, toOffset: to)
                        store.reorderCompartments(list)
                    }
                } footer: {
                    Text("点按改名，左滑删除，拖动右侧把手排序。")
                }
                Section {
                    Button { addText = ""; adding = true } label: { Label("添加收纳位置", systemImage: "plus") }
                }
            }
            .environment(\.editMode, .constant(.active))
            .navigationTitle("收纳位置")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } } }
            .alert("重命名", isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
                TextField("名称", text: $renameText)
                Button("取消", role: .cancel) {}
                Button("保存") {
                    if var c = renaming, !renameText.trimmed.isEmpty { c.name = renameText.trimmed; store.save(c) }
                }
            }
            .alert("新的收纳位置", isPresented: $adding) {
                TextField("例如：抽屉 2", text: $addText)
                Button("取消", role: .cancel) {}
                Button("添加") { if !addText.trimmed.isEmpty { store.addCompartment(to: furnitureId, name: addText.trimmed) } }
            }
            .deleteCompartmentDialog($deleting)
        }
    }
}

extension View {
    func deleteCompartmentDialog(_ compartment: Binding<Compartment?>, onDelete: @escaping () -> Void = {}) -> some View {
        modifier(DeleteCompartmentDialog(compartment: compartment, onDelete: onDelete))
    }
}

private struct DeleteCompartmentDialog: ViewModifier {
    @Binding var compartment: Compartment?
    var onDelete: () -> Void
    @Environment(HomeStore.self) private var store

    func body(content: Content) -> some View {
        let count = compartment.map { store.items(in: $0.id).count } ?? 0
        content.confirmationDialog(
            "删除「\(compartment?.name ?? "")」？",
            isPresented: Binding(get: { compartment != nil }, set: { if !$0 { compartment = nil } }),
            titleVisibility: .visible,
            presenting: compartment
        ) { c in
            if count > 0 {
                Button("删除，物品移到未归位") { store.deleteCompartment(c.id, policy: .keepItems); onDelete() }
                Button("连同 \(count) 件物品一起删除", role: .destructive) { store.deleteCompartment(c.id, policy: .deleteItems); onDelete() }
            } else {
                Button("删除", role: .destructive) { store.deleteCompartment(c.id, policy: .keepItems); onDelete() }
            }
            Button("取消", role: .cancel) {}
        } message: { _ in
            Text(count > 0 ? "这里有 \(count) 件物品。" : "此操作无法撤销。")
        }
    }
}
