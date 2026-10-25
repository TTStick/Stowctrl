import SwiftUI

struct SettingsView: View {
    @Environment(HomeStore.self) private var store
    @Environment(SyncManager.self) private var sync
    @Environment(HomeAssistantManager.self) private var ha
    @Environment(AppRouter.self) private var router
    @Environment(AIService.self) private var ai
    @AppStorage("cutout.auto") private var autoCutout = true
    @AppStorage("cutout.engine") private var cutoutEngine = "auto"
    @State private var signingIn = false
    @State private var renamingHome = false
    @State private var homeName = ""
    @State private var changingPassword = false
    @State private var deletingAccount = false
    @State private var confirmSignOut = false
    @State private var confirmWipe = false
    @State private var backupURL: URL?
    @State private var backingUp = false
    @State private var backupMessage: String?
    @State private var importing = false
    @State private var pendingRestore: Backup.Loaded?

    var body: some View {
        @Bindable var router = router
        NavigationStack(path: $router.settingsPath) {
            List {
                accountSection
                Section("家") {
                    Button {
                        homeName = store.settings.homeName
                        renamingHome = true
                    } label: {
                        LabeledContent("名称", value: store.settings.homeName).foregroundStyle(Color.primary)
                    }
                    NavigationLink { RoomsManagerView() } label: {
                        LabeledContent("房间", value: "\(store.rooms.count) 个")
                    }
                    NavigationLink { CategoryManagerView() } label: {
                        LabeledContent("物品分类", value: "\(store.categories.count) 个")
                    }
                    NavigationLink { CustomIconsView() } label: {
                        LabeledContent("我的图标", value: "\(store.icons.count) 个")
                    }
                }
                Section("智能家居与 AI") {
                    NavigationLink { HASettingsView() } label: {
                        LabeledContent("Home Assistant", value: ha.isConfigured ? "已连接" : "未设置")
                    }
                    NavigationLink { AISettingsView() } label: {
                        LabeledContent("AI 绘制图标", value: ai.source == .direct ? (ai.hasKey ? "本机直连" : "未填 Key") : "通过服务器")
                    }
                }
                Section {
                    Toggle("拍照后自动抠图", isOn: $autoCutout)
                    Picker("抠图方式", selection: $cutoutEngine) {
                        Text("端侧优先，失败用服务器").tag("auto")
                        Text("只在手机上").tag("device")
                        Text("只用服务器").tag("server")
                    }
                } header: {
                    Text("照片")
                } footer: {
                    Text(sync.canServerCutout ? "你的服务器已启用抠图。" : "服务器抠图需要在部署时设置 WITH_CUTOUT=true。")
                }
                Section {
                    Button { Task { await makeBackup() } } label: {
                        HStack {
                            Label("备份到文件（含照片）", systemImage: "archivebox")
                            if backingUp { Spacer(); ProgressView() }
                        }
                    }
                    .disabled(backingUp)
                    if let backupURL {
                        ShareLink(item: backupURL) { Label("保存或分享备份文件", systemImage: "square.and.arrow.up") }
                    }
                    Button { importing = true } label: { Label("从备份恢复", systemImage: "arrow.counterclockwise") }
                    if let backupMessage { Text(backupMessage).font(.footnote).foregroundStyle(.secondary) }
                    Button(role: .destructive) { confirmWipe = true } label: { Label("清除本机数据", systemImage: "trash") }
                } header: {
                    Text("备份")
                } footer: {
                    Text("备份是一个 zip 文件，不需要服务器；可以存到“文件”或电脑，在 iPhone 和安卓手机之间都能恢复。备份里不含 Home Assistant 令牌。")
                }
                Section {
                    LabeledContent("版本", value: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0")
                } footer: {
                    Text("万物收纳 · 照片和数据只存放在你的设备和你自己的服务器上。")
                }
            }
            .navigationTitle("我的")
            .navigationDestination(for: Route.self) { RouteView(route: $0) }
            .sheet(isPresented: $signingIn) { AccountSheet() }
            .sheet(isPresented: $changingPassword) { ChangePasswordSheet() }
            .sheet(isPresented: $deletingAccount) { DeleteAccountSheet() }
            .fileImporter(isPresented: $importing, allowedContentTypes: [.zip, .data]) { result in
                guard case .success(let url) = result else { return }
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                do {
                    // Copy out of the picker's sandbox before reading lazily.
                    let local = FileManager.default.temporaryDirectory.appendingPathComponent("restore-\(UUID().uuidString).zip")
                    try FileManager.default.copyItem(at: url, to: local)
                    pendingRestore = try Backup.load(local)
                } catch {
                    backupMessage = "无法读取备份：\(error.localizedDescription)"
                }
            }
            .confirmationDialog(restoreTitle, isPresented: Binding(get: { pendingRestore != nil }, set: { if !$0 { pendingRestore = nil } }),
                                titleVisibility: .visible, presenting: pendingRestore) { loaded in
                Button("合并到本机（保留较新的内容）") { restore(loaded, replace: false) }
                Button("用备份替换本机数据", role: .destructive) { restore(loaded, replace: true) }
                Button("取消", role: .cancel) {}
            } message: { loaded in
                Text("“\(loaded.manifest.homeName)”，\(loaded.manifest.exportedAt.shortDate) 备份：\(loaded.count(Room.entityName)) 个房间、\(loaded.count(Furniture.entityName)) 件家具、\(loaded.count(Item.entityName)) 件物品、\(loaded.photoCount) 张照片。"
                     + (sync.isLoggedIn ? "恢复的内容会同步到账号。" : ""))
            }
            .alert("家的名称", isPresented: $renamingHome) {
                TextField("我的家", text: $homeName)
                Button("取消", role: .cancel) {}
                Button("保存") {
                    var s = store.settings
                    s.homeName = homeName.trimmed.isEmpty ? "我的家" : homeName.trimmed
                    store.saveSettings(s)
                }
            }
            .confirmationDialog("退出登录", isPresented: $confirmSignOut, titleVisibility: .visible) {
                Button("退出，保留本机数据") { Task { await sync.signOut(wipeLocal: false) } }
                Button("退出并清除本机数据", role: .destructive) { Task { await sync.signOut(wipeLocal: true) } }
            } message: {
                Text("账号里的数据会保留在服务器上。")
            }
            .confirmationDialog("清除本机数据？", isPresented: $confirmWipe, titleVisibility: .visible) {
                Button("清除", role: .destructive) {
                    Task {
                        if sync.isLoggedIn { await sync.signOut(wipeLocal: true) } else { store.wipeLocal() }
                    }
                }
            } message: {
                Text(sync.isLoggedIn ? "会退出登录并删除本机的所有房间、家具、物品和照片。服务器上的数据不受影响。"
                     : "本机的所有房间、家具、物品和照片都会被删除，且没有登录同步，无法恢复。")
            }
        }
    }

    @ViewBuilder private var accountSection: some View {
        @Bindable var sync = sync
        Section {
            if sync.isLoggedIn {
                LabeledContent("账号", value: sync.username ?? "")
                LabeledContent("服务器", value: sync.serverURL)
                Toggle("离线模式", isOn: $sync.offline)
                HStack {
                    Text("同步")
                    Spacer()
                    if sync.offline {
                        Text(store.pendingCount > 0 ? "已暂停 · \(store.pendingCount) 项改动待同步" : "已暂停")
                            .foregroundStyle(.orange)
                    } else {
                    switch sync.status {
                    case .syncing:
                        ProgressView().controlSize(.small)
                        Text("同步中").foregroundStyle(.secondary)
                    case .failed(let msg):
                        Text(msg).foregroundStyle(.red).font(.footnote).multilineTextAlignment(.trailing)
                    case .idle:
                        if store.pendingCount > 0 {
                            Text("\(store.pendingCount) 项待同步").foregroundStyle(.orange)
                        } else if let t = sync.lastSyncAt {
                            Text("已同步 · \(t.formatted(.relative(presentation: .named)))").foregroundStyle(.secondary)
                        }
                    }
                    }
                }
                Button { Task { await sync.syncNow() } } label: { Label("立即同步", systemImage: "arrow.triangle.2.circlepath") }
                    .disabled(sync.status == .syncing || sync.offline)
                Button { changingPassword = true } label: { Label("修改密码", systemImage: "key") }
                Button { confirmSignOut = true } label: { Label("退出登录", systemImage: "rectangle.portrait.and.arrow.right") }
                Button(role: .destructive) { deletingAccount = true } label: { Label("注销账号", systemImage: "person.crop.circle.badge.xmark") }
            } else {
                VStack(alignment: .leading, spacing: 6) {
                    Label("本机模式", systemImage: "iphone").font(.headline)
                    Text("现在不连任何服务器，数据只保存在这台设备上，所有功能都能用。想在多台设备间同步时，再登录自己部署的服务器；也可以用下面的“备份”在设备之间搬家。")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                Button { signingIn = true } label: { Label("登录或注册", systemImage: "person.crop.circle.badge.plus") }
                if case .failed(let msg) = sync.status {
                    Text(msg).foregroundStyle(.red).font(.footnote)
                }
            }
        } header: {
            Text("账号与同步")
        } footer: {
            if sync.isLoggedIn {
                Text("离线模式下完全不连服务器（比如在外面连不上家里的 NAS 时），改动先存在本机，关闭后自动同步。")
            }
        }
    }

    private var restoreTitle: String { "从备份恢复" }

    private func makeBackup() async {
        backingUp = true
        defer { backingUp = false }
        do {
            backupURL = try await Backup.export(store: store)
            backupMessage = "备份已生成，点“保存或分享备份文件”存到“文件”或发给自己。"
        } catch {
            backupMessage = "备份失败：\(error.localizedDescription)"
        }
    }

    private func restore(_ loaded: Backup.Loaded, replace: Bool) {
        do {
            let s = try Backup.restore(loaded, into: store, replace: replace)
            backupMessage = "已恢复 \(s.rooms) 个房间、\(s.furniture) 件家具、\(s.items) 件物品、\(s.photos) 张照片" +
                (s.skipped > 0 ? "，\(s.skipped) 条本机已有更新的版本未覆盖" : "") + "。"
            Haptics.success()
        } catch {
            backupMessage = "恢复失败：\(error.localizedDescription)"
        }
        pendingRestore = nil
    }
}

/// Where AI icons come from: the sync server, or straight from this device.
struct AISettingsView: View {
    @Environment(AIService.self) private var ai
    @Environment(SyncManager.self) private var sync
    @Environment(\.dismiss) private var dismiss
    @State private var key = ""
    @State private var checking = false
    @State private var result: (Bool, String)?

    var body: some View {
        @Bindable var ai = ai
        Form {
            Section {
                Picker("来源", selection: $ai.source) {
                    Text("通过同步服务器").tag(AIService.Source.server)
                    Text("本机直连（自己的 API Key）").tag(AIService.Source.direct)
                }
                .pickerStyle(.inline)
                .labelsHidden()
            } footer: {
                Text(ai.source == .server
                     ? "大模型 Key 放在你自己部署的服务器上，手机里不保存。需要登录并且不在离线模式。"
                     : "不需要服务器：用你自己的 Claude API Key 直接请求。Key 只保存在这台设备的钥匙串里，不会同步到其他设备或服务器。")
            }
            if ai.source == .direct {
                Section("Claude API") {
                    SecureField(ai.hasKey ? "已保存，输入新的可替换" : "API Key（sk-ant-…）", text: $key)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .accessibilityIdentifier("ai.key")
                    TextField("API 地址（可选，默认官方）", text: $ai.baseURL)
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    TextField("模型", text: $ai.model)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                }
                Section {
                    Button {
                        Task { await check() }
                    } label: {
                        HStack { Text("检查并保存"); Spacer(); if checking { ProgressView() } }
                    }
                    .disabled((key.trimmed.isEmpty && !ai.hasKey) || checking)
                    if let result {
                        Label(result.1, systemImage: result.0 ? "checkmark.circle.fill" : "xmark.octagon.fill")
                            .foregroundStyle(result.0 ? .green : .red).font(.footnote)
                    }
                    if ai.hasKey {
                        Button("删除本机保存的 Key", role: .destructive) { ai.setKey(nil); key = ""; result = nil }
                    }
                } footer: {
                    Text("检查只读取模型列表，不消耗额度。使用代理或中转时把地址填成它的根地址（不含 /v1）。")
                }
            }
        }
        .navigationTitle("AI 绘制图标")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func check() async {
        checking = true
        defer { checking = false }
        let candidate = key.trimmed
        do {
            if candidate.isEmpty {
                // Re-check the key already saved.
                try await ai.checkKey(Keychain.get("ai.apiKey") ?? "")
            } else {
                try await ai.checkKey(candidate)
                ai.setKey(candidate)
                key = ""
            }
            result = (true, "可以使用，已保存")
        } catch {
            result = (false, error.localizedDescription)
        }
    }
}

// MARK: - Account

struct AccountSheet: View {
    var onFinished: () -> Void = {}
    @Environment(SyncManager.self) private var sync
    @Environment(\.dismiss) private var dismiss
    @State private var server = ""
    @State private var username = ""
    @State private var password = ""
    @State private var register = false
    @State private var working = false
    @State private var error: String?
    @State private var info: ServerInfo?
    @State private var needsMerge = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("例如 192.168.1.10:5858", text: $server)
                        .accessibilityIdentifier("account.server")
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .onSubmit { Task { await check() } }
                    if let info {
                        Label("已连接 \(info.name) \(info.version)\(info.features.icons ? " · AI 图标可用" : "")",
                              systemImage: "checkmark.circle.fill")
                            .foregroundStyle(.green).font(.footnote)
                    } else {
                        Button("检测服务器") { Task { await check() } }.disabled(server.trimmed.isEmpty || working)
                    }
                } header: {
                    Text("服务器地址")
                } footer: {
                    Text("就是你用 Docker 部署的服务端，默认端口 5858。")
                }
                Section {
                    Picker("", selection: $register) {
                        Text("登录").tag(false)
                        Text("注册新账号").tag(true)
                    }
                    .pickerStyle(.segmented)
                    TextField("用户名", text: $username)
                        .accessibilityIdentifier("account.username")
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .textContentType(.username)
                    SecureField("密码（至少 6 位）", text: $password)
                        .accessibilityIdentifier("account.password")
                        .textContentType(.password)  // no associated domain, so iOS can't keep a generated password
                }
                if register, let info, !info.registrationOpen {
                    Text("这台服务器已关闭注册，请使用已有账号登录。").foregroundStyle(.orange).font(.footnote)
                }
                if let error {
                    Text(error).foregroundStyle(.red).font(.footnote)
                }
                Section {
                    Button {
                        Task { await submit() }
                    } label: {
                        HStack {
                            Spacer()
                            if working { ProgressView() } else { Text(register ? "注册并登录" : "登录").bold() }
                            Spacer()
                        }
                    }
                    .disabled(working || server.trimmed.isEmpty || username.trimmed.count < 3 || password.count < 6)
                    .accessibilityIdentifier("account.submit")
                }
            }
            .navigationTitle("同步账号")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } } }
            .onAppear { if server.isEmpty { server = sync.serverURL } }
            .onChange(of: server) { _, _ in info = nil }
            .interactiveDismissDisabled(working || needsMerge)
            .confirmationDialog("这台设备和账号里都有数据", isPresented: $needsMerge, titleVisibility: .visible) {
                Button("合并两边的数据（推荐）") { Task { await sync.resolveMerge(keepLocal: true); finish() } }
                Button("只用账号里的数据，清除本机", role: .destructive) { Task { await sync.resolveMerge(keepLocal: false); finish() } }
                Button("取消登录", role: .cancel) { Task { await sync.abandonSignIn() } }
            } message: {
                Text("合并会保留本机已有的房间和物品，并下载账号里的内容。")
            }
        }
    }

    private func check() async {
        working = true
        defer { working = false }
        do {
            info = try await sync.checkServer(server)
            error = nil
        } catch {
            info = nil
            self.error = error.localizedDescription
        }
    }

    private func submit() async {
        working = true
        defer { working = false }
        error = nil
        do {
            let outcome = try await sync.signIn(server: server, username: username, password: password, register: register)
            switch outcome {
            case .done: finish()
            case .needsMergeDecision: needsMerge = true
            }
        } catch {
            self.error = error.localizedDescription
        }
    }

    private func finish() {
        onFinished()
        dismiss()
    }
}

struct ChangePasswordSheet: View {
    @Environment(SyncManager.self) private var sync
    @Environment(\.dismiss) private var dismiss
    @State private var old = ""
    @State private var new = ""
    @State private var confirm = ""
    @State private var error: String?
    @State private var working = false

    var body: some View {
        NavigationStack {
            Form {
                SecureField("原密码", text: $old).textContentType(.password)
                SecureField("新密码（至少 6 位）", text: $new).textContentType(.newPassword)
                SecureField("再输入一次", text: $confirm).textContentType(.newPassword)
                if !confirm.isEmpty && confirm != new { Text("两次输入不一致").foregroundStyle(.orange).font(.footnote) }
                if let error { Text(error).foregroundStyle(.red).font(.footnote) }
                Text("修改后，其他设备需要重新登录。").font(.footnote).foregroundStyle(.secondary)
            }
            .navigationTitle("修改密码")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        Task {
                            working = true
                            defer { working = false }
                            do { try await sync.changePassword(old: old, new: new); dismiss() } catch { self.error = error.localizedDescription }
                        }
                    }
                    .disabled(working || old.isEmpty || new.count < 6 || new != confirm)
                }
            }
        }
    }
}

struct DeleteAccountSheet: View {
    @Environment(SyncManager.self) private var sync
    @Environment(\.dismiss) private var dismiss
    @State private var password = ""
    @State private var keepLocal = true
    @State private var error: String?
    @State private var working = false
    @Environment(HomeStore.self) private var store

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("注销后，服务器上这个账号的所有数据和照片都会被永久删除，所有设备都会退出登录。")
                    Toggle("在这台设备上保留一份数据", isOn: $keepLocal)
                    SecureField("输入密码确认", text: $password)
                }
                if let error { Text(error).foregroundStyle(.red).font(.footnote) }
                Section {
                    Button(role: .destructive) {
                        Task {
                            working = true
                            defer { working = false }
                            do {
                                try await sync.deleteAccount(password: password)
                                if !keepLocal { store.wipeLocal() }
                                dismiss()
                            } catch {
                                self.error = error.localizedDescription
                            }
                        }
                    } label: {
                        HStack { Spacer(); if working { ProgressView() } else { Text("永久注销账号") }; Spacer() }
                    }
                    .disabled(password.isEmpty || working)
                }
            }
            .navigationTitle("注销账号")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } } }
        }
    }
}

// MARK: - Home Assistant

struct HASettingsView: View {
    @Environment(HomeStore.self) private var store
    @Environment(HomeAssistantManager.self) private var ha
    @Environment(\.dismiss) private var dismiss
    @State private var url = ""
    @State private var token = ""
    @State private var testing = false
    @State private var result: String?
    @State private var ok = false
    @State private var confirmDisconnect = false

    var body: some View {
        Form {
            Section {
                TextField("http://192.168.1.20:8123", text: $url)
                    .accessibilityIdentifier("ha.url")
                    .keyboardType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                SecureField("长期访问令牌", text: $token)
                    .accessibilityIdentifier("ha.token")
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
            } header: {
                Text("Home Assistant")
            } footer: {
                Text("令牌在 HA 网页里生成：左下角头像 › 安全 › 长期访问令牌 › 创建令牌。地址和令牌会随账号同步到你的其他设备。")
            }
            Section {
                Button {
                    Task { await test() }
                } label: {
                    HStack {
                        Text("测试并保存")
                        Spacer()
                        if testing { ProgressView() }
                    }
                }
                .disabled(url.trimmed.isEmpty || token.trimmed.isEmpty || testing)
                if let result {
                    Label(result, systemImage: ok ? "checkmark.circle.fill" : "xmark.octagon.fill")
                        .foregroundStyle(ok ? .green : .red)
                        .font(.footnote)
                }
            }
            if ha.isConfigured {
                Section {
                    Button("断开 Home Assistant", role: .destructive) { confirmDisconnect = true }
                }
            }
        }
        .navigationTitle("Home Assistant")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("关闭") { dismiss() }
            }
        }
        .onAppear {
            url = store.settings.haURL
            token = store.settings.haToken
        }
        .confirmationDialog("断开后，家具上的设备映射会保留，重新连接即可恢复。", isPresented: $confirmDisconnect, titleVisibility: .visible) {
            Button("断开", role: .destructive) {
                var s = store.settings
                s.haURL = ""
                s.haToken = ""
                store.saveSettings(s)
                url = ""; token = ""; result = nil
                Task { await ha.refresh() }
            }
        }
    }

    private func test() async {
        guard let base = APIClient.normalize(url) else {
            ok = false
            result = "地址格式不正确"
            return
        }
        testing = true
        defer { testing = false }
        let client = HAClient(baseURL: base, token: token.trimmed)
        do {
            try await client.check()
            let states = try await client.states()
            var s = store.settings
            s.haURL = base.absoluteString
            s.haToken = token.trimmed
            store.saveSettings(s)
            ok = true
            result = "连接成功，共 \(states.count) 个实体，已保存"
            await ha.refresh()
        } catch {
            ok = false
            result = error.localizedDescription
        }
    }
}

// MARK: - Rooms, categories, icons

struct RoomsManagerView: View {
    @Environment(HomeStore.self) private var store
    @State private var adding = false
    @State private var editing: Room?
    @State private var deleting: Room?

    var body: some View {
        let counts = store.itemCountsByRoom()
        List {
            ForEach(store.sortedRooms) { room in
                Button { editing = room } label: {
                    HStack {
                        Label(room.name, systemImage: room.type.info.symbol).foregroundStyle(Color.primary)
                        Spacer()
                        Text("\(counts[room.id] ?? 0) 件").foregroundStyle(.secondary)
                    }
                }
                .swipeActions {
                    Button(role: .destructive) { deleting = room } label: { Label("删除", systemImage: "trash") }
                }
            }
            .onMove { from, to in
                var rooms = store.sortedRooms
                rooms.move(fromOffsets: from, toOffset: to)
                for (i, var r) in rooms.enumerated() where r.sortOrder != i { r.sortOrder = i; store.save(r) }
            }
            Button { adding = true } label: { Label("添加房间", systemImage: "plus") }
        }
        .navigationTitle("房间")
        .toolbar { EditButton() }
        .sheet(isPresented: $adding) { RoomEditorSheet(room: nil) }
        .sheet(item: $editing) { RoomEditorSheet(room: $0) }
        .deleteRoomDialog($deleting)
    }
}

struct CategoryManagerView: View {
    @Environment(HomeStore.self) private var store
    @State private var editing: Category?
    @State private var adding = false
    @State private var deleting: Category?

    var body: some View {
        List {
            ForEach(store.sortedCategories) { c in
                Button { editing = c } label: {
                    HStack {
                        Label {
                            Text(c.name).foregroundStyle(Color.primary)
                        } icon: {
                            Image(systemName: SymbolCheck.safe(c.symbol)).foregroundStyle(Color(hex: c.colorHex))
                        }
                        Spacer()
                        Text(c.template == .general ? "" : (c.template == .clothing ? "衣物" : "消耗品"))
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }
                .swipeActions {
                    Button(role: .destructive) { deleting = c } label: { Label("删除", systemImage: "trash") }
                }
            }
            .onMove { from, to in
                var list = store.sortedCategories
                list.move(fromOffsets: from, toOffset: to)
                for (i, var c) in list.enumerated() where c.sortOrder != i { c.sortOrder = i; store.save(c) }
            }
            Button { adding = true } label: { Label("新建分类", systemImage: "plus") }
            if store.categories.isEmpty {
                Button("恢复默认分类") { store.seedDefaultCategoriesIfNeeded() }
            }
        }
        .navigationTitle("物品分类")
        .toolbar { EditButton() }
        .sheet(isPresented: $adding) { CategoryEditorSheet(category: nil) }
        .sheet(item: $editing) { CategoryEditorSheet(category: $0) }
        .confirmationDialog("删除分类「\(deleting?.name ?? "")」？", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }),
                            titleVisibility: .visible) {
            Button("删除", role: .destructive) { if let d = deleting { store.deleteCategory(d.id) } }
        } message: {
            Text("这个分类下的物品会变成“未分类”，物品本身不会删除。")
        }
    }
}

struct CategoryEditorSheet: View {
    let category: Category?
    @Environment(HomeStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var symbol = "square.grid.2x2"
    @State private var colorHex = "#8E8E93"
    @State private var template: CategoryTemplate = .general
    @State private var pickingSymbol = false

    var body: some View {
        NavigationStack {
            Form {
                Section("名称") { TextField("例如：宠物用品", text: $name) }
                Section("图标") {
                    Button { pickingSymbol = true } label: {
                        HStack {
                            Image(systemName: SymbolCheck.safe(symbol)).font(.title2).foregroundStyle(Color(hex: colorHex)).frame(width: 40)
                            Text("选择图标")
                        }
                    }
                }
                Section("颜色") {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 34))], spacing: 10) {
                        ForEach(DefaultCategories.colors, id: \.self) { hex in
                            Button { colorHex = hex } label: {
                                Circle().fill(Color(hex: hex)).frame(width: 30, height: 30)
                                    .overlay(Circle().stroke(colorHex == hex ? Color.primary : .clear, lineWidth: 2).padding(-3))
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.vertical, 4)
                }
                Section {
                    Picker("类型", selection: $template) {
                        ForEach(CategoryTemplate.allCases) { Text($0.title).tag($0) }
                    }
                    .pickerStyle(.inline)
                    .labelsHidden()
                } header: {
                    Text("记录哪些信息")
                }
            }
            .navigationTitle(category == nil ? "新建分类" : "编辑分类")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        var c = category ?? Category(name: "", symbol: symbol, colorHex: colorHex,
                                                     sortOrder: (store.categories.values.map(\.sortOrder).max() ?? -1) + 1)
                        c.name = name.trimmed
                        c.symbol = symbol
                        c.colorHex = colorHex
                        c.template = template
                        store.save(c)
                        dismiss()
                    }
                    .disabled(name.trimmed.isEmpty)
                }
            }
            .sheet(isPresented: $pickingSymbol) {
                NavigationStack {
                    SymbolGrid { symbol = $0; pickingSymbol = false }
                        .navigationTitle("选择图标")
                        .navigationBarTitleDisplayMode(.inline)
                        .toolbar { ToolbarItem(placement: .cancellationAction) { Button("取消") { pickingSymbol = false } } }
                }
            }
            .onAppear {
                if let c = category { name = c.name; symbol = c.symbol; colorHex = c.colorHex; template = c.template }
            }
        }
    }
}

struct CustomIconsView: View {
    @State private var drawing = false

    var body: some View {
        CustomIconGrid(onPick: { _ in }, onDrawNew: { drawing = true })
            .navigationTitle("我的图标")
            .toolbar {
                Button { drawing = true } label: { Image(systemName: "wand.and.stars") }.accessibilityLabel("AI 绘制")
            }
            .sheet(isPresented: $drawing) {
                NavigationStack {
                    AIIconGenerator(prompt: "") { _ in drawing = false }
                        .navigationTitle("AI 绘制图标")
                        .navigationBarTitleDisplayMode(.inline)
                        .toolbar { ToolbarItem(placement: .cancellationAction) { Button("关闭") { drawing = false } } }
                }
            }
    }
}
