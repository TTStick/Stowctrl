import SwiftUI

/// Pick a piece of furniture to add to a room.
struct FurniturePaletteSheet: View {
    let room: Room
    var onAdded: (Furniture) -> Void
    @Environment(HomeStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    @State private var creatingCustom = false

    private var groups: [(String, [FurnitureKind])] {
        let q = query.trimmed
        if !q.isEmpty {
            return [("搜索结果", Catalog.kinds.filter { $0.name.localizedStandardContains(q) || $0.group.title.contains(q) })]
        }
        let suggested = room.type.info.suggested.map(Catalog.kind).filter { $0.id != Catalog.customKindId }
        var result: [(String, [FurnitureKind])] = [("适合\(room.type.info.title)", suggested)]
        for g in FurnitureGroup.allCases {
            result.append((g.title, Catalog.kinds(in: g)))
        }
        return result
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 18) {
                    Button { creatingCustom = true } label: {
                        Label("自定义家具（任意名称、尺寸、颜色）", systemImage: "wand.and.stars")
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding()
                            .background(Color.accentColor.opacity(0.1), in: RoundedRectangle(cornerRadius: 12))
                    }
                    .buttonStyle(.plain)
                    ForEach(groups, id: \.0) { title, kinds in
                        if !kinds.isEmpty {
                            Text(title).font(.headline)
                            LazyVGrid(columns: [GridItem(.adaptive(minimum: 96), spacing: 10)], spacing: 10) {
                                ForEach(kinds) { kind in
                                    Button { add(kind) } label: { KindTile(kind: kind) }
                                        .buttonStyle(.plain)
                                }
                            }
                        }
                    }
                    if groups.allSatisfy({ $0.1.isEmpty }) {
                        Text("没有找到，可以用“自定义家具”创建").foregroundStyle(.secondary)
                    }
                }
                .padding()
            }
            .searchable(text: $query, prompt: "搜索家具")
            .navigationTitle("添加家具")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("关闭") { dismiss() } } }
            .sheet(isPresented: $creatingCustom) {
                FurnitureEditorSheet(mode: .create(room.id)) { added in
                    onAdded(added)
                    dismiss()
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private func add(_ kind: FurnitureKind) {
        if let f = store.addFurniture(kind: kind, to: room.id) {
            Haptics.tap()
            onAdded(f)
        }
        dismiss()
    }
}

struct KindTile: View {
    let kind: FurnitureKind

    var body: some View {
        let sample = Furniture(roomId: UUID(), kind: kind.id, name: kind.name, x: 0, y: 0, width: kind.width, depth: kind.depth)
        let ratio = kind.depth / kind.width
        VStack(spacing: 6) {
            Canvas { ctx, size in FurnitureArt.draw(sample, in: ctx, size: size) }
                .frame(width: ratio > 1 ? 52 / ratio : 52, height: ratio > 1 ? 52 : max(8, 52 * ratio))
                .frame(width: 60, height: 56)
            Text(kind.name).font(.caption).lineLimit(1)
            Text("\(Int(kind.width))×\(Int(kind.depth))").font(.caption2).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 10)
        .background(Color.cardBackground, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(.primary.opacity(0.06)))
    }
}

/// Create a custom piece or edit any piece's properties.
struct FurnitureEditorSheet: View {
    enum Mode {
        case create(UUID)   // room id
        case edit(Furniture)
    }

    let mode: Mode
    var onCreated: (Furniture) -> Void = { _ in }
    @Environment(HomeStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var kindId = Catalog.customKindId
    @State private var width: Double = 80
    @State private var depth: Double = 40
    @State private var colorHex: String?
    @State private var notes = ""
    @State private var roomId: UUID?
    @State private var compartmentText = "内部"
    @State private var confirmDelete: Furniture?

    private var existing: Furniture? {
        if case .edit(let f) = mode { return store.furniture[f.id] ?? f }
        return nil
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("名称") {
                    TextField("例如：衣柜", text: $name)
                }
                Section("类型") {
                    Picker("外观", selection: $kindId) {
                        Text("自定义").tag(Catalog.customKindId)
                        ForEach(FurnitureGroup.allCases) { g in
                            Section(g.title) {
                                ForEach(Catalog.kinds(in: g)) { k in Text(k.name).tag(k.id) }
                            }
                        }
                    }
                    .onChange(of: kindId) { old, new in
                        guard existing == nil, new != old else { return }
                        let k = Catalog.kind(new)
                        width = k.width; depth = k.depth
                        if name.trimmed.isEmpty || Catalog.kinds.contains(where: { $0.name == name }) { name = k.name }
                        compartmentText = k.compartments.joined(separator: "、")
                    }
                    preview
                }
                Section("尺寸（未旋转时）") {
                    CentimetreField(title: "宽", value: $width, range: 5...2000)
                    CentimetreField(title: "深", value: $depth, range: 5...2000)
                }
                Section("颜色") {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 34))], spacing: 10) {
                        Button { colorHex = nil } label: {
                            Image(systemName: "circle.slash")
                                .frame(width: 30, height: 30)
                                .overlay(Circle().stroke(colorHex == nil ? Color.accentColor : .clear, lineWidth: 2).padding(-3))
                        }
                        .accessibilityLabel("默认颜色")
                        ForEach(Catalog.customColors, id: \.self) { hex in
                            Button { colorHex = hex } label: {
                                Circle().fill(Color(hex: hex)).frame(width: 30, height: 30)
                                    .overlay(Circle().stroke(.primary.opacity(0.15)))
                                    .overlay(Circle().stroke(colorHex == hex ? Color.accentColor : .clear, lineWidth: 2).padding(-3))
                            }
                        }
                    }
                    .buttonStyle(.plain)
                    .padding(.vertical, 4)
                }
                if existing == nil {
                    Section {
                        TextField("用顿号分隔，例如：上层、下层、抽屉", text: $compartmentText, axis: .vertical)
                    } header: {
                        Text("收纳位置")
                    } footer: {
                        Text("家具里能放东西的地方。创建后可以随时增删、改名。")
                    }
                } else {
                    Section("所在房间") {
                        Picker("房间", selection: $roomId) {
                            ForEach(store.sortedRooms) { r in Text(r.name).tag(Optional(r.id)) }
                        }
                    }
                }
                Section("备注") {
                    TextField("选填", text: $notes, axis: .vertical)
                }
                if let f = existing {
                    Section {
                        Button("删除家具", role: .destructive) { confirmDelete = f }
                    }
                }
            }
            .navigationTitle(existing == nil ? "自定义家具" : "家具属性")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("保存", action: save).disabled(name.trimmed.isEmpty) }
            }
            .deleteFurnitureDialog($confirmDelete) { dismiss() }
            .onAppear(perform: load)
        }
    }

    private var preview: some View {
        var sample = Furniture(roomId: UUID(), kind: kindId, name: name, x: 0, y: 0, width: width, depth: depth)
        sample.colorHex = colorHex
        let ratio = depth / max(width, 1)
        return HStack {
            Spacer()
            Canvas { ctx, size in FurnitureArt.draw(sample, in: ctx, size: size) }
                .frame(width: ratio > 1 ? 90 / ratio : 90, height: ratio > 1 ? 90 : max(8, 90 * ratio))
                .frame(height: 96)
            Spacer()
        }
    }

    private func load() {
        if let f = existing {
            name = f.name; kindId = f.kind; width = f.width; depth = f.depth
            colorHex = f.colorHex; notes = f.notes; roomId = f.roomId
        } else {
            let k = Catalog.custom
            name = ""; kindId = k.id; width = k.width; depth = k.depth
            compartmentText = k.compartments.joined(separator: "、")
        }
    }

    private func save() {
        if var f = existing {
            f.name = name.trimmed
            f.kind = kindId
            f.notes = notes
            f.colorHex = colorHex
            let sizeChanged = f.width != width || f.depth != depth
            f.width = width; f.depth = depth
            if let room = store.rooms[f.roomId], sizeChanged { store.clampInside(&f, room: room) }
            store.save(f)
            if let roomId, roomId != f.roomId { store.moveFurniture(f.id, toRoom: roomId) }
        } else if case .create(let rid) = mode {
            let names = compartmentText.split(whereSeparator: { "、,，\n".contains($0) }).map { String($0).trimmed }.filter { !$0.isEmpty }
            let kind = Catalog.kind(kindId)
            if var f = store.addFurniture(kind: kind, to: rid, name: name.trimmed, width: width, depth: depth,
                                          colorHex: colorHex, compartmentNames: names) {
                if !notes.isEmpty { f.notes = notes; store.save(f) }
                onCreated(f)
            }
        }
        dismiss()
    }
}
