import SwiftUI

/// Choose an icon: built-in line art, the household's AI-drawn icons,
/// draw a new one with AI, or any emoji.
struct IconPickerSheet: View {
    var suggestion: String = ""
    var onPick: (IconRef) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var tab = 0

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Picker("来源", selection: $tab) {
                    Text("图标库").tag(0)
                    Text("我的图标").tag(1)
                    Text("AI 绘制").tag(2)
                    Text("Emoji").tag(3)
                }
                .pickerStyle(.segmented)
                .padding()
                switch tab {
                case 0: SymbolGrid(initialQuery: suggestion) { pick(.symbol($0)) }
                case 1: CustomIconGrid(onPick: { pick(.custom($0)) }, onDrawNew: { tab = 2 })
                case 2: AIIconGenerator(prompt: suggestion) { pick(.custom($0)) }
                default: EmojiPicker { pick(.emoji($0)) }
                }
            }
            .navigationTitle("选择图标")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } } }
        }
    }

    private func pick(_ ref: IconRef) {
        onPick(ref)
        dismiss()
    }
}

struct SymbolGrid: View {
    var initialQuery = ""
    var onPick: (String) -> Void
    @State private var query = ""

    var body: some View {
        let q = query.trimmed
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 16) {
                if q.isEmpty {
                    ForEach(SymbolLibrary.groups) { g in
                        Text(g.title).font(.subheadline.weight(.semibold)).foregroundStyle(.secondary)
                        grid(g.entries)
                    }
                } else {
                    let results = SymbolLibrary.search(q)
                    if results.isEmpty {
                        Text("图标库里没有“\(q)”，可以试试 AI 绘制").foregroundStyle(.secondary)
                    }
                    grid(results)
                }
            }
            .padding(.horizontal)
            .padding(.bottom)
        }
        .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "搜索：杯子、螺丝刀…")
        .onAppear { if query.isEmpty, !SymbolLibrary.search(initialQuery).isEmpty, !initialQuery.trimmed.isEmpty { query = initialQuery } }
    }

    private func grid(_ entries: [SymbolEntry]) -> some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 58), spacing: 10)], spacing: 10) {
            ForEach(entries) { e in
                Button { onPick(e.name) } label: {
                    VStack(spacing: 4) {
                        Image(systemName: SymbolCheck.safe(e.name)).font(.title2).frame(height: 30)
                        Text(e.keywords.split(separator: " ").first.map(String.init) ?? "")
                            .font(.caption2).lineLimit(1).foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity, minHeight: 60)
                    .background(Color.gray.opacity(0.08), in: RoundedRectangle(cornerRadius: 10))
                }
                .buttonStyle(.plain)
                .accessibilityLabel(e.keywords)
            }
        }
    }
}

struct CustomIconGrid: View {
    var onPick: (UUID) -> Void
    var onDrawNew: () -> Void
    @Environment(HomeStore.self) private var store
    @State private var renaming: CustomIcon?
    @State private var renameText = ""
    @State private var deleting: CustomIcon?

    var body: some View {
        let icons = store.sortedIcons
        Group {
            if icons.isEmpty {
                ContentUnavailableView {
                    Label("还没有自己的图标", systemImage: "paintbrush.pointed")
                } description: {
                    Text("用 AI 画出图标库里没有的东西，会保存在这里，所有设备共享。")
                } actions: {
                    Button("AI 绘制", action: onDrawNew).buttonStyle(.borderedProminent)
                }
            } else {
                ScrollView {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 76), spacing: 12)], spacing: 12) {
                        ForEach(icons) { icon in
                            Button { onPick(icon.id) } label: {
                                VStack(spacing: 4) {
                                    SVGIconView(svg: icon.svg).frame(width: 44, height: 44)
                                    Text(icon.name).font(.caption2).lineLimit(1).foregroundStyle(.secondary)
                                }
                                .frame(maxWidth: .infinity, minHeight: 80)
                                .background(Color.gray.opacity(0.08), in: RoundedRectangle(cornerRadius: 12))
                            }
                            .buttonStyle(.plain)
                            .contextMenu {
                                Button { renameText = icon.name; renaming = icon } label: { Label("重命名", systemImage: "pencil") }
                                Button(role: .destructive) { deleting = icon } label: { Label("删除", systemImage: "trash") }
                            }
                        }
                    }
                    .padding()
                    Text("长按图标可以重命名或删除").font(.footnote).foregroundStyle(.secondary)
                }
            }
        }
        .alert("重命名图标", isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
            TextField("名称", text: $renameText)
            Button("取消", role: .cancel) {}
            Button("保存") { if var i = renaming, !renameText.trimmed.isEmpty { i.name = renameText.trimmed; store.save(i) } }
        }
        .confirmationDialog("删除图标「\(deleting?.name ?? "")」？", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }),
                            titleVisibility: .visible) {
            Button("删除", role: .destructive) { if let d = deleting { store.deleteIcon(d.id) } }
        } message: {
            Text("用到这个图标的物品会改回分类图标。")
        }
    }
}

struct AIIconGenerator: View {
    @State var prompt: String
    var onUse: (UUID) -> Void
    @Environment(HomeStore.self) private var store
    @Environment(SyncManager.self) private var sync
    @Environment(AIService.self) private var ai
    @Environment(AppRouter.self) private var router
    @State private var svg: String?
    @State private var working = false
    @State private var error: String?
    @State private var showSettings = false

    var body: some View {
        ScrollView {
            VStack(spacing: 18) {
                switch ai.availability(sync) {
                case .ready:
                    generator
                case .needsKey:
                    requirement("还没有填写 API Key", "本机直连模式会用你自己的 Claude API Key 直接请求大模型，不经过服务器。Key 只保存在这台设备的钥匙串里。")
                case .offline:
                    requirement("离线模式下不连服务器", "可以关闭离线模式，或者改用本机直连（填自己的 API Key）。")
                case .needsLogin:
                    requirement("没有连接同步服务器", "可以登录自己部署的服务器使用它的大模型，或者改用本机直连（填自己的 API Key），不需要服务器。")
                case .serverWithoutLLM:
                    requirement("服务器还没有配置大模型", "在服务器的 .env 里填写 ANTHROPIC_API_KEY 并重启容器；或者改用本机直连。")
                    Button("重新检查") { Task { await sync.refreshServerInfo() } }
                }
            }
            .padding()
        }
        .sheet(isPresented: $showSettings) { NavigationStack { AISettingsView() } }
    }

    private var generator: some View {
        VStack(spacing: 18) {
            TextField("要画什么？例如：汤勺、电动牙刷", text: $prompt)
                .textFieldStyle(.roundedBorder)
                .submitLabel(.go)
                .onSubmit { Task { await generate() } }
            ZStack {
                RoundedRectangle(cornerRadius: 20).fill(Color.gray.opacity(0.08))
                if working {
                    VStack(spacing: 8) {
                        ProgressView()
                        Text("AI 正在画…（约 10-30 秒）").font(.footnote).foregroundStyle(.secondary)
                    }
                } else if let svg {
                    SVGIconView(svg: svg).padding(30).foregroundStyle(Color.accentColor)
                } else {
                    Image(systemName: "wand.and.stars").font(.system(size: 44)).foregroundStyle(.tertiary)
                }
            }
            .frame(width: 200, height: 200)
            if let error {
                Label(error, systemImage: "exclamationmark.triangle").font(.footnote).foregroundStyle(.red)
                    .multilineTextAlignment(.center)
            }
            HStack(spacing: 12) {
                Button(svg == nil ? "开始绘制" : "重新绘制") { Task { await generate() } }
                    .buttonStyle(.bordered)
                    .disabled(prompt.trimmed.isEmpty || working)
                if svg != nil {
                    Button("使用这个图标") { use() }
                        .buttonStyle(.borderedProminent)
                        .disabled(working)
                }
            }
            Text(ai.source == .direct ? "本机直连 · 画好的图标会保存到“我的图标”" : "通过同步服务器 · 画好的图标会保存到“我的图标”，所有设备都能用")
                .font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center)
        }
    }

    private func requirement(_ title: String, _ detail: String) -> some View {
        VStack(spacing: 10) {
            Image(systemName: "wand.and.stars").font(.largeTitle).foregroundStyle(.secondary)
            Text(title).font(.headline)
            Text(detail).font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center)
            Button("AI 绘制设置") { showSettings = true }.buttonStyle(.borderedProminent)
            if ai.source == .server && !sync.isLoggedIn {
                Button("去登录") { router.tab = .settings }
            }
        }
        .padding(.top, 30)
    }

    private func generate() async {
        guard !prompt.trimmed.isEmpty else { return }
        working = true
        error = nil
        defer { working = false }
        do {
            svg = try await ai.generate(prompt.trimmed, sync: sync)
        } catch {
            self.error = error.localizedDescription
        }
    }

    private func use() {
        guard let svg else { return }
        let icon = CustomIcon(name: prompt.trimmed, svg: svg)
        store.save(icon)
        onUse(icon.id)
    }
}

struct EmojiPicker: View {
    var onPick: (String) -> Void
    @State private var custom = ""

    private static let common: [(String, [String])] = [
        ("厨房", ["🥄", "🍴", "🥢", "🍽️", "🥣", "🍳", "🫕", "🥡", "🍶", "🫖", "☕", "🍵", "🥛", "🧂", "🔪", "🧊"]),
        ("食物", ["🍚", "🍜", "🥫", "🍪", "🍫", "🍬", "🥜", "🍞", "🧀", "🥚", "🍎", "🍌", "🥕", "🥔", "🧅", "🧄", "🌶️", "🍯", "🧃", "🍷", "🍺", "🥤"]),
        ("日用", ["🧺", "🧴", "🧻", "🪥", "🧽", "🧹", "🪣", "🧼", "🪒", "💡", "🔋", "🔌", "🕯️", "🧯", "🪮", "🧷", "🧵", "🪡"]),
        ("衣物", ["👕", "👖", "👗", "🧥", "🧦", "🧤", "🧣", "👟", "👠", "🥾", "👜", "🎒", "👓", "🕶️", "👒", "🧢", "💍", "⌚"]),
        ("工具", ["🧰", "🪛", "🔧", "🔨", "🪚", "📏", "✂️", "🪜", "🔦", "🧲", "🪝", "🔑", "🔒"]),
        ("其他", ["💊", "🩹", "🌡️", "📚", "📎", "✏️", "🖊️", "📦", "🎁", "🧸", "🎮", "🎲", "🪴", "☂️", "🧳", "🐶", "🐱"]),
    ]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                HStack {
                    TextField("输入任意 Emoji", text: $custom)
                        .textFieldStyle(.roundedBorder)
                    Button("使用") {
                        if let e = custom.trimmed.first { onPick(String(e)) }
                    }
                    .disabled(custom.trimmed.isEmpty)
                }
                ForEach(Self.common, id: \.0) { title, emojis in
                    Text(title).font(.subheadline.weight(.semibold)).foregroundStyle(.secondary)
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 48), spacing: 8)], spacing: 8) {
                        ForEach(emojis, id: \.self) { e in
                            Button { onPick(e) } label: {
                                Text(e).font(.system(size: 30)).frame(width: 48, height: 48)
                                    .background(Color.gray.opacity(0.08), in: RoundedRectangle(cornerRadius: 10))
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
            .padding()
        }
    }
}
