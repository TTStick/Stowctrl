import PhotosUI
import SwiftUI
import UIKit

/// Create or edit an item: photo (with optional cut-out) or icon, name,
/// category, location, quantity and the fields its category cares about.
struct ItemEditorView: View {
    enum Mode {
        case create(compartmentId: UUID?)
        case edit(Item)
    }

    struct PhotoDraft {
        var original: UIImage
        var cutout: UIImage?
        var useCutout: Bool
        /// Set when the original is already stored (re-editing an item's photo).
        var reuseOriginalId: String?
    }

    let mode: Mode
    @Environment(HomeStore.self) private var store
    @Environment(SyncManager.self) private var sync
    @Environment(\.dismiss) private var dismiss
    @AppStorage("cutout.auto") private var autoCutout = true
    @AppStorage("cutout.engine") private var cutoutEngine = "auto"

    @State private var draft: Item
    @State private var initial: Item
    @State private var photo: PhotoDraft?
    @State private var removePhoto = false
    @State private var pickerItem: PhotosPickerItem?
    @State private var showCamera = false
    @State private var showIconPicker = false
    @State private var showLocation = false
    @State private var showCategory = false
    @State private var processing = false
    @State private var cutoutError: String?
    @State private var session: CutoutSession?
    @State private var instances: IndexSet = []
    @State private var confirmDiscard = false
    @State private var hasExpiry = false
    @State private var hasPrice = false
    @State private var hasPurchase = false
    @State private var hasLowStock = false

    init(mode: Mode) {
        self.mode = mode
        let item: Item
        switch mode {
        case .create(let cid): item = Item(name: "", compartmentId: cid)
        case .edit(let existing): item = existing
        }
        _draft = State(initialValue: item)
        _initial = State(initialValue: item)
        _hasExpiry = State(initialValue: item.expiresAt != nil)
        _hasPrice = State(initialValue: item.price != nil)
        _hasPurchase = State(initialValue: item.purchasedAt != nil)
        _hasLowStock = State(initialValue: item.lowStock != nil)
    }

    private var isNew: Bool { if case .create = mode { return true }; return false }
    private var category: Category? { draft.categoryId.flatMap { store.categories[$0] } }
    private var template: CategoryTemplate { category?.template ?? .general }
    private var isDirty: Bool { draft != initial || photo != nil || removePhoto }

    var body: some View {
        NavigationStack {
            Form {
                imageSection
                Section {
                    TextField("名称（必填）", text: $draft.name)
                        .font(.title3)
                        .accessibilityIdentifier("item.name")
                    Button { showCategory = true } label: {
                        HStack {
                            Text("分类").foregroundStyle(Color.primary)
                            Spacer()
                            if let category {
                                Label(category.name, systemImage: SymbolCheck.safe(category.symbol))
                                    .foregroundStyle(Color(hex: category.colorHex))
                            } else {
                                Text("未分类").foregroundStyle(.secondary)
                            }
                            Image(systemName: "chevron.right").font(.caption).foregroundStyle(.tertiary)
                        }
                    }
                    .accessibilityIdentifier("item.category")
                    Button { showLocation = true } label: {
                        HStack {
                            Text("位置").foregroundStyle(Color.primary)
                            Spacer()
                            Text(store.compartmentPath(draft.compartmentId))
                                .foregroundStyle(draft.compartmentId == nil ? .orange : .secondary)
                                .lineLimit(1)
                            Image(systemName: "chevron.right").font(.caption).foregroundStyle(.tertiary)
                        }
                    }
                    .accessibilityIdentifier("item.location")
                }
                quantitySection
                if template == .perishable { expirySection }
                if template == .clothing { clothingSection }
                Section("标签") { TagEditor(tags: $draft.tags) }
                Section("备注") { TextField("选填", text: $draft.notes, axis: .vertical).lineLimit(2...6) }
                moreSection
            }
            .navigationTitle(isNew ? "添加物品" : "编辑物品")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { if isDirty { confirmDiscard = true } else { dismiss() } }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存", action: save).disabled(draft.name.trimmed.isEmpty || processing)
                        .accessibilityIdentifier("item.save")
                }
            }
            .interactiveDismissDisabled(isDirty)
            .confirmationDialog("放弃这些修改？", isPresented: $confirmDiscard, titleVisibility: .visible) {
                Button("放弃修改", role: .destructive) { dismiss() }
                Button("继续编辑", role: .cancel) {}
            }
            .sheet(isPresented: $showIconPicker) {
                IconPickerSheet(suggestion: draft.name) { draft.icon = $0 }
            }
            .sheet(isPresented: $showLocation) {
                LocationPickerSheet(current: draft.compartmentId) { draft.compartmentId = $0 }
            }
            .sheet(isPresented: $showCategory) {
                CategoryPickerSheet(current: draft.categoryId) { draft.categoryId = $0 }
            }
            .fullScreenCover(isPresented: $showCamera) {
                CameraPicker { image in setNewPhoto(image) }.ignoresSafeArea()
            }
            .onChange(of: pickerItem) { _, newItem in
                guard let newItem else { return }
                Task {
                    if let data = try? await newItem.loadTransferable(type: Data.self), let img = UIImage(data: data) {
                        setNewPhoto(img)
                    }
                    pickerItem = nil
                }
            }
        }
    }

    // MARK: Image

    private var imageSection: some View {
        Section {
            VStack(spacing: 12) {
                preview
                    .frame(height: 220)
                    .frame(maxWidth: .infinity)
                    .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                HStack(spacing: 10) {
                    if UIImagePickerController.isSourceTypeAvailable(.camera) {
                        Button { showCamera = true } label: { mediaLabel("拍照", "camera") }
                    }
                    PhotosPicker(selection: $pickerItem, matching: .images) { mediaLabel("相册", "photo.on.rectangle") }
                    Button { showIconPicker = true } label: { mediaLabel("图标", "square.grid.2x2") }
                        .accessibilityIdentifier("item.icon")
                }
                .buttonStyle(.plain)
            }
            .padding(.vertical, 6)

            if photo != nil {
                Toggle(isOn: Binding(get: { photo?.useCutout ?? false }, set: { setCutout($0) })) {
                    Label("抠图（去掉背景）", systemImage: "scissors")
                }
                .disabled(processing)
                if let s = session, s.instanceCount > 1, photo?.useCutout == true {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("识别到 \(s.instanceCount) 个主体，选择要保留的：").font(.footnote).foregroundStyle(.secondary)
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack {
                                ForEach(1...s.instanceCount, id: \.self) { n in
                                    Button {
                                        if instances.contains(n) {
                                            if instances.count > 1 { instances.remove(n) }
                                        } else { instances.insert(n) }
                                        Task { await renderInstances() }
                                    } label: {
                                        Text("主体 \(n)")
                                            .font(.subheadline)
                                            .padding(.horizontal, 12).padding(.vertical, 6)
                                            .background(instances.contains(n) ? Color.accentColor.opacity(0.2) : Color.gray.opacity(0.12), in: Capsule())
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                        }
                    }
                }
                if let cutoutError {
                    Label(cutoutError, systemImage: "exclamationmark.triangle").font(.footnote).foregroundStyle(.orange)
                }
            }
            if photo != nil || (!removePhoto && draft.photoId != nil) {
                if photo == nil {
                    Button { Task { await editExistingPhoto() } } label: { Label("调整抠图", systemImage: "scissors") }
                }
                Button(role: .destructive) {
                    photo = nil
                    session = nil
                    removePhoto = true
                } label: { Label("移除照片，改用图标", systemImage: "photo.badge.minus") }
            }
        } footer: {
            Text("照片优先在手机上抠图，不上传第三方。")
        }
    }

    @ViewBuilder private var preview: some View {
        ZStack {
            if let p = photo {
                if p.useCutout, let c = p.cutout {
                    Checkerboard()
                    Image(uiImage: c).resizable().scaledToFit().padding(12)
                } else {
                    Color.photoBackground
                    Image(uiImage: p.original).resizable().scaledToFit()
                }
            } else if !removePhoto, let id = draft.photoId {
                Color.photoBackground
                StoredImage(fileId: id, maxPixel: 800).padding(12)
            } else {
                Color.photoBackground
                IconTile(icon: draft.icon, fallbackSymbol: category?.symbol ?? "shippingbox",
                         tint: Color(hex: category?.colorHex ?? "#8E8E93"), size: 96)
            }
            if processing {
                Color.black.opacity(0.25)
                VStack(spacing: 8) {
                    ProgressView().tint(.white)
                    Text("正在抠图…").font(.footnote).foregroundStyle(.white)
                }
            }
        }
    }

    private func mediaLabel(_ title: String, _ symbol: String) -> some View {
        VStack(spacing: 4) {
            Image(systemName: symbol).font(.title3)
            Text(title).font(.caption)
        }
        .frame(maxWidth: .infinity, minHeight: 54)
        .background(Color.accentColor.opacity(0.1), in: RoundedRectangle(cornerRadius: 12))
        .foregroundStyle(Color.accentColor)
    }

    private func setNewPhoto(_ image: UIImage) {
        let upright = ImageEncoding.normalized(image, maxSide: 2048)
        photo = PhotoDraft(original: upright, cutout: nil, useCutout: false, reuseOriginalId: nil)
        removePhoto = false
        session = nil
        cutoutError = nil
        if autoCutout { setCutout(true) } else { updateColors(from: upright) }
    }

    private func editExistingPhoto() async {
        guard let id = draft.originalPhotoId ?? draft.photoId,
              let data = await store.files.loadData(id), let img = UIImage(data: data) else {
            cutoutError = "原图还没下载到这台设备"
            return
        }
        let hadCutout = draft.originalPhotoId != nil && draft.originalPhotoId != draft.photoId
        photo = PhotoDraft(original: img, cutout: nil, useCutout: false, reuseOriginalId: id)
        if hadCutout { setCutout(true) }
    }

    private func setCutout(_ on: Bool) {
        guard var p = photo else { return }
        p.useCutout = on
        photo = p
        cutoutError = nil
        if on && p.cutout == nil { Task { await runCutout() } }
    }

    private func runCutout() async {
        guard let original = photo?.original else { return }
        processing = true
        defer { processing = false }
        var lastError: Error?
        if cutoutEngine != "server" {
            do {
                let s = try await CutoutSession.make(original)
                session = s
                instances = IndexSet(1...max(1, s.instanceCount))
                let img = try await s.render()
                applyCutout(img)
                return
            } catch {
                lastError = error
            }
        }
        if cutoutEngine != "device", sync.canServerCutout, let client = sync.client,
           let jpeg = ImageEncoding.jpeg(original, maxSide: 1600) {
            do {
                let png = try await client.cutout(imageData: jpeg)
                if let img = UIImage(data: png).map({ ImageEncoding.trimmedToContent($0) }) {
                    session = nil
                    applyCutout(img)
                    return
                }
            } catch {
                lastError = error
            }
        }
        if var p = photo { p.useCutout = false; photo = p }
        let reason = lastError?.localizedDescription ?? "抠图失败"
        cutoutError = sync.canServerCutout || cutoutEngine == "device" ? reason : reason + "。可在服务器启用抠图作为备用。"
        updateColors(from: original)
    }

    private func renderInstances() async {
        guard let s = session, !instances.isEmpty else { return }
        processing = true
        defer { processing = false }
        if let img = try? await s.render(instances: instances) { applyCutout(img) }
    }

    private func applyCutout(_ img: UIImage) {
        guard var p = photo else { return }
        p.cutout = img
        p.useCutout = true
        photo = p
        updateColors(from: img)
    }

    private func updateColors(from image: UIImage) {
        let colors = ColorExtractor.palette(of: image)
        if !colors.isEmpty { draft.colors = colors }
    }

    // MARK: Fields

    private var quantitySection: some View {
        Section("数量") {
            HStack {
                Stepper(value: $draft.quantity, in: 0...100000, step: 1) {
                    HStack {
                        TextField("数量", value: $draft.quantity, format: .number)
                            .keyboardType(.decimalPad)
                            .frame(width: 80)
                        Picker("单位", selection: $draft.unit) {
                            ForEach(Units.all.contains(draft.unit) ? Units.all : [draft.unit] + Units.all, id: \.self) { Text($0) }
                        }
                        .labelsHidden()
                    }
                }
            }
            Toggle("库存提醒", isOn: $hasLowStock)
                .onChange(of: hasLowStock) { _, on in draft.lowStock = on ? (draft.lowStock ?? 1) : nil }
            if hasLowStock {
                Stepper("低于 \((draft.lowStock ?? 1).clean) \(draft.unit) 时提醒",
                        value: Binding(get: { draft.lowStock ?? 1 }, set: { draft.lowStock = max(0, $0) }), in: 0...10000)
            }
        }
    }

    private var expirySection: some View {
        Section("保质期") {
            Toggle("记录到期日", isOn: $hasExpiry)
                .onChange(of: hasExpiry) { _, on in
                    draft.expiresAt = on ? (draft.expiresAt ?? Calendar.current.date(byAdding: .month, value: 1, to: Date())) : nil
                }
            if hasExpiry {
                DatePicker("到期日", selection: Binding(get: { draft.expiresAt ?? Date() }, set: { draft.expiresAt = $0 }),
                           displayedComponents: .date)
            }
        }
    }

    private var clothingSection: some View {
        Section("衣物") {
            HStack {
                Text("季节")
                Spacer()
                ForEach(Seasons.all, id: \.self) { s in
                    Button(s) {
                        if draft.seasons.contains(s) { draft.seasons.removeAll { $0 == s } } else { draft.seasons.append(s) }
                    }
                    .buttonStyle(.bordered)
                    .tint(draft.seasons.contains(s) ? .accentColor : .gray)
                }
            }
            HStack {
                Text("颜色")
                Spacer()
                if draft.colors.isEmpty {
                    Text("拍照后自动识别").foregroundStyle(.secondary).font(.subheadline)
                } else {
                    ForEach(draft.colors, id: \.self) { hex in
                        Button { draft.colors.removeAll { $0 == hex } } label: {
                            Circle().fill(Color(hex: hex)).frame(width: 24, height: 24)
                                .overlay(Circle().strokeBorder(.primary.opacity(0.15)))
                        }
                        .accessibilityLabel("删除颜色")
                    }
                }
                ColorPicker("", selection: Binding(get: { .clear }, set: { c in
                    let hex = UIColor(c).hexString
                    if !draft.colors.contains(hex) && draft.colors.count < 6 { draft.colors.append(hex) }
                }), supportsOpacity: false)
                .labelsHidden()
                .frame(width: 30)
            }
        }
    }

    private var moreSection: some View {
        Section("更多") {
            Toggle("购买价格", isOn: $hasPrice)
                .onChange(of: hasPrice) { _, on in draft.price = on ? (draft.price ?? 0) : nil }
            if hasPrice {
                HStack {
                    Text("¥")
                    TextField("价格", value: Binding(get: { draft.price ?? 0 }, set: { draft.price = $0 }), format: .number)
                        .keyboardType(.decimalPad)
                }
            }
            Toggle("购买日期", isOn: $hasPurchase)
                .onChange(of: hasPurchase) { _, on in draft.purchasedAt = on ? (draft.purchasedAt ?? Date()) : nil }
            if hasPurchase {
                DatePicker("日期", selection: Binding(get: { draft.purchasedAt ?? Date() }, set: { draft.purchasedAt = $0 }),
                           displayedComponents: .date)
            }
            if template != .perishable {
                Toggle("到期日（保修、证件等）", isOn: $hasExpiry)
                    .onChange(of: hasExpiry) { _, on in
                        draft.expiresAt = on ? (draft.expiresAt ?? Calendar.current.date(byAdding: .year, value: 1, to: Date())) : nil
                    }
                if hasExpiry {
                    DatePicker("到期日", selection: Binding(get: { draft.expiresAt ?? Date() }, set: { draft.expiresAt = $0 }),
                               displayedComponents: .date)
                }
            }
            Toggle("收藏", isOn: $draft.favorite)
        }
    }

    // MARK: Save

    private func save() {
        var item = draft
        item.name = item.name.trimmed
        let oldIds = [initial.photoId, initial.originalPhotoId].compactMap { $0 }

        if let p = photo {
            var originalId = p.reuseOriginalId
            if originalId == nil, let data = ImageEncoding.jpeg(p.original) {
                let id = UUID().uuidString + ".jpg"
                try? store.files.write(data, id: id)
                store.registerUpload(id)
                originalId = id
            }
            item.originalPhotoId = originalId
            if p.useCutout, let cut = p.cutout, let data = ImageEncoding.png(cut) {
                let id = UUID().uuidString + ".png"
                try? store.files.write(data, id: id)
                store.registerUpload(id)
                item.photoId = id
            } else {
                item.photoId = originalId
            }
        } else if removePhoto {
            item.photoId = nil
            item.originalPhotoId = nil
        }
        store.save(item)
        for id in oldIds where id != item.photoId && id != item.originalPhotoId {
            store.deleteFileIfUnused(id)
        }
        Haptics.success()
        dismiss()
    }
}

extension ImageEncoding {
    /// Crops away fully transparent borders (server cut-outs come uncropped).
    static func trimmedToContent(_ image: UIImage) -> UIImage {
        guard let cg = image.cgImage else { return image }
        let w = cg.width, h = cg.height
        var pixels = [UInt8](repeating: 0, count: w * h * 4)
        guard let ctx = CGContext(data: &pixels, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                                  space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
        else { return image }
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
        var minX = w, minY = h, maxX = -1, maxY = -1
        for y in 0..<h {
            for x in 0..<w where pixels[(y * w + x) * 4 + 3] > 12 {
                minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
            }
        }
        guard maxX >= minX, maxY >= minY, let cropped = cg.cropping(to: CGRect(x: minX, y: minY, width: maxX - minX + 1, height: maxY - minY + 1))
        else { return image }
        return CutoutSession.padded(UIImage(cgImage: cropped), fraction: 0.04)
    }
}

/// UIKit camera wrapped for SwiftUI.
struct CameraPicker: UIViewControllerRepresentable {
    var onImage: (UIImage) -> Void
    @Environment(\.dismiss) private var dismiss

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ controller: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let parent: CameraPicker
        init(_ parent: CameraPicker) { self.parent = parent }

        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            if let image = info[.originalImage] as? UIImage { parent.onImage(image) }
            parent.dismiss()
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { parent.dismiss() }
    }
}
