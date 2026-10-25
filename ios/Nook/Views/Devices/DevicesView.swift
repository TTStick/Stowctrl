import SwiftUI

/// Home Assistant devices, grouped by the furniture they're mapped onto.
struct DevicesView: View {
    @Environment(HomeStore.self) private var store
    @Environment(HomeAssistantManager.self) private var ha
    @Environment(AppRouter.self) private var router
    @State private var showUnmapped = true
    @State private var mapping: HAState?
    @State private var setup = false

    var body: some View {
        @Bindable var router = router
        NavigationStack(path: $router.devicesPath) {
            Group {
                if !ha.isConfigured {
                    ContentUnavailableView {
                        Label("连接 Home Assistant", systemImage: "homekit")
                    } description: {
                        Text("填写内网 HA 地址和长期访问令牌后，可以把灯、空调、洗衣机等设备映射到家具上，直接在这里控制。")
                    } actions: {
                        Button("开始设置") { setup = true }.buttonStyle(.borderedProminent)
                    }
                } else {
                    deviceList
                }
            }
            .navigationTitle("设备")
            .toolbar {
                if ha.isConfigured {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button { setup = true } label: { Image(systemName: "gearshape") }.accessibilityLabel("HA 设置")
                    }
                }
            }
            .navigationDestination(for: Route.self) { RouteView(route: $0) }
            .sheet(isPresented: $setup) { NavigationStack { HASettingsView() } }
            .sheet(item: $mapping) { s in FurniturePickerSheet(title: "把「\(s.name)」放到…") { f in
                var updated = f
                if !updated.haEntities.contains(s.entity_id) { updated.haEntities.append(s.entity_id) }
                store.save(updated)
            } }
            .onAppear { if ha.isConfigured { ha.startPolling() } }
            .onDisappear { ha.stopPolling() }
        }
    }

    private var deviceList: some View {
        let mappedPieces = store.furniture.values.filter { !$0.haEntities.isEmpty }
        let mappedIds = Set(mappedPieces.flatMap(\.haEntities))
        let unmapped = ha.sortedStates.filter { HADomain.mappable.contains($0.domain) && !mappedIds.contains($0.entity_id) }
        return List {
            if let error = ha.error {
                Section {
                    Label(error, systemImage: "wifi.exclamationmark").foregroundStyle(.red)
                    Button("重试") { Task { await ha.refresh() } }
                }
            }
            if mappedPieces.isEmpty {
                Section {
                    Text("还没有设备映射到家具。在下面的列表里点“放到家具上”，或在家具详情里绑定设备。")
                        .font(.footnote).foregroundStyle(.secondary)
                }
            }
            ForEach(store.sortedRooms) { room in
                let pieces = mappedPieces.filter { $0.roomId == room.id }.sorted { $0.name < $1.name }
                if !pieces.isEmpty {
                    Section(room.name) {
                        ForEach(pieces) { f in
                            NavigationLink(value: Route.furniture(f.id)) {
                                Label(f.name, systemImage: SymbolCheck.safe(f.symbol ?? Catalog.kind(f.kind).symbol, fallback: "cube"))
                                    .font(.subheadline.weight(.semibold))
                            }
                            ForEach(f.haEntities, id: \.self) { id in
                                EntityRow(entityId: id)
                                    .swipeActions {
                                        Button(role: .destructive) {
                                            var updated = f
                                            updated.haEntities.removeAll { $0 == id }
                                            store.save(updated)
                                        } label: { Label("解除", systemImage: "link.badge.minus") }
                                    }
                            }
                        }
                    }
                }
            }
            Section {
                DisclosureGroup(isExpanded: $showUnmapped) {
                    if ha.states.isEmpty && ha.isLoading {
                        ProgressView()
                    }
                    ForEach(unmapped) { s in
                        HStack {
                            EntityRow(entityId: s.entity_id, showsControl: false)
                            Button("放到家具上") { mapping = s }
                                .font(.caption)
                                .buttonStyle(.bordered)
                        }
                    }
                    if unmapped.isEmpty && !ha.states.isEmpty {
                        Text("所有设备都已映射").foregroundStyle(.secondary)
                    }
                } label: {
                    Text("未映射的设备（\(unmapped.count)）")
                }
            } footer: {
                if let t = ha.lastUpdated { Text("更新于 \(t.formatted(date: .omitted, time: .standard))") }
            }
        }
        .refreshable { await ha.refresh() }
    }
}

/// One entity with its live state and the right control for its domain.
struct EntityRow: View {
    let entityId: String
    var showsControl = true
    @Environment(HomeAssistantManager.self) private var ha

    var body: some View {
        if let s = ha.states[entityId] {
            HStack(spacing: 12) {
                Image(systemName: HADomain.symbol(s.domain, on: s.isOn))
                    .font(.title3)
                    .foregroundStyle(s.isOn ? Color.yellow : Color.secondary)
                    .frame(width: 30)
                VStack(alignment: .leading, spacing: 2) {
                    Text(s.name).lineLimit(1)
                    let text = HADomain.stateText(s)
                    if !text.isEmpty { Text(text).font(.caption).foregroundStyle(.secondary).lineLimit(1) }
                }
                Spacer()
                if showsControl { control(s).disabled(s.isUnavailable) }
            }
        } else {
            HStack {
                Image(systemName: "questionmark.circle").foregroundStyle(.secondary).frame(width: 30)
                VStack(alignment: .leading) {
                    Text(entityId).lineLimit(1)
                    Text(ha.states.isEmpty ? "正在连接…" : "HA 里找不到这个设备").font(.caption).foregroundStyle(.secondary)
                }
            }
        }
    }

    @ViewBuilder private func control(_ s: HAState) -> some View {
        switch HAControl(domain: s.domain) {
        case .toggle:
            Toggle("", isOn: Binding(get: { s.isOn }, set: { _ in Task { await ha.toggle(s) } }))
                .labelsHidden()
        case .cover:
            HStack(spacing: 6) {
                iconButton("chevron.up") { await ha.perform(s.domain, s.domain == "valve" ? "open_valve" : "open_cover", entity: s.entity_id) }
                iconButton("stop.fill") { await ha.perform(s.domain, s.domain == "valve" ? "stop_valve" : "stop_cover", entity: s.entity_id) }
                iconButton("chevron.down") { await ha.perform(s.domain, s.domain == "valve" ? "close_valve" : "close_cover", entity: s.entity_id) }
            }
        case .lock:
            Button(s.state == "locked" ? "开锁" : "上锁") {
                Task { await ha.perform("lock", s.state == "locked" ? "unlock" : "lock", entity: s.entity_id) }
            }
            .buttonStyle(.bordered)
        case .climate:
            HStack(spacing: 6) {
                iconButton("minus") { await adjust(s, -0.5) }
                iconButton("plus") { await adjust(s, 0.5) }
                Toggle("", isOn: Binding(get: { s.state != "off" }, set: { on in
                    Task { await ha.perform("climate", on ? "turn_on" : "turn_off", entity: s.entity_id) }
                }))
                .labelsHidden()
            }
        case .media:
            HStack(spacing: 6) {
                iconButton(s.state == "playing" ? "pause.fill" : "play.fill") {
                    await ha.perform("media_player", "media_play_pause", entity: s.entity_id)
                }
                iconButton("power") { await ha.perform("media_player", s.state == "off" ? "turn_on" : "turn_off", entity: s.entity_id) }
            }
        case .activate:
            Button("执行") { Task { await ha.perform(s.domain, "turn_on", entity: s.entity_id) } }.buttonStyle(.bordered)
        case .press:
            Button("按下") { Task { await ha.perform(s.domain, "press", entity: s.entity_id) } }.buttonStyle(.bordered)
        case .vacuum:
            HStack(spacing: 6) {
                iconButton("play.fill") { await ha.perform(s.domain, "start", entity: s.entity_id) }
                iconButton("house") { await ha.perform(s.domain, s.domain == "lawn_mower" ? "dock" : "return_to_base", entity: s.entity_id) }
            }
        case .readOnly:
            EmptyView()
        }
    }

    private func iconButton(_ symbol: String, action: @escaping () async -> Void) -> some View {
        Button { Task { await action() } } label: {
            Image(systemName: symbol).frame(width: 30, height: 30)
        }
        .buttonStyle(.bordered)
        .buttonBorderShape(.circle)
    }

    private func adjust(_ s: HAState, _ delta: Double) async {
        guard let t = s.attributes["temperature"]?.double else { return }
        await ha.perform("climate", "set_temperature", entity: s.entity_id, extra: ["temperature": .number(t + delta)])
    }
}

/// Choose Home Assistant entities to map onto one piece of furniture.
struct EntityPickerSheet: View {
    let furnitureId: UUID
    @Environment(HomeStore.self) private var store
    @Environment(HomeAssistantManager.self) private var ha
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    @State private var chosen: Set<String> = []
    @State private var showAll = false
    @State private var setup = false

    var body: some View {
        NavigationStack {
            Group {
                if !ha.isConfigured {
                    ContentUnavailableView {
                        Label("还没连接 Home Assistant", systemImage: "homekit")
                    } actions: {
                        Button("去设置") { setup = true }.buttonStyle(.borderedProminent)
                    }
                } else {
                    list
                }
            }
            .navigationTitle("绑定设备")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") {
                        if var f = store.furniture[furnitureId] {
                            // Keep ids HA doesn't currently report, in the order they were added.
                            let kept = f.haEntities.filter { chosen.contains($0) }
                            let added = chosen.subtracting(kept).sorted()
                            f.haEntities = kept + added
                            store.save(f)
                        }
                        dismiss()
                    }
                    .disabled(!ha.isConfigured)
                }
            }
            .sheet(isPresented: $setup) { NavigationStack { HASettingsView() } }
            .task {
                chosen = Set(store.furniture[furnitureId]?.haEntities ?? [])
                await ha.refresh()
            }
        }
    }

    private var list: some View {
        let q = query.trimmed
        let states = ha.sortedStates.filter { s in
            (showAll || HADomain.mappable.contains(s.domain) || chosen.contains(s.entity_id))
                && (q.isEmpty || s.name.localizedStandardContains(q) || s.entity_id.localizedStandardContains(q))
        }
        let byDomain = Dictionary(grouping: states, by: \.domain)
        return List {
            if let e = ha.error { Label(e, systemImage: "wifi.exclamationmark").foregroundStyle(.red) }
            if ha.states.isEmpty && ha.isLoading { ProgressView() }
            Toggle("显示全部类型（含传感器以外的实体）", isOn: $showAll).font(.footnote)
            ForEach(byDomain.keys.sorted(), id: \.self) { domain in
                Section(HADomain.title(domain)) {
                    ForEach(byDomain[domain] ?? []) { s in
                        Button {
                            if chosen.contains(s.entity_id) { chosen.remove(s.entity_id) } else { chosen.insert(s.entity_id) }
                        } label: {
                            HStack {
                                Image(systemName: HADomain.symbol(s.domain, on: s.isOn)).frame(width: 26)
                                VStack(alignment: .leading) {
                                    Text(s.name).foregroundStyle(Color.primary)
                                    HStack(spacing: 4) {
                                        Text(s.entity_id)
                                        if let other = ha.furniture(for: s.entity_id), other.id != furnitureId {
                                            Text("· 已在\(other.name)")
                                        }
                                    }
                                    .font(.caption).foregroundStyle(.secondary)
                                }
                                Spacer()
                                Image(systemName: chosen.contains(s.entity_id) ? "checkmark.circle.fill" : "circle")
                                    .foregroundStyle(chosen.contains(s.entity_id) ? Color.accentColor : .secondary)
                            }
                        }
                    }
                }
            }
        }
        .searchable(text: $query, prompt: "搜索设备")
    }
}

/// Pick any piece of furniture in the home.
struct FurniturePickerSheet: View {
    let title: String
    var onPick: (Furniture) -> Void
    @Environment(HomeStore.self) private var store
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                ForEach(store.sortedRooms) { room in
                    let pieces = store.furniture(in: room.id)
                    if !pieces.isEmpty {
                        Section(room.name) {
                            ForEach(pieces) { f in
                                Button {
                                    onPick(f)
                                    dismiss()
                                } label: {
                                    Label(f.name, systemImage: SymbolCheck.safe(f.symbol ?? Catalog.kind(f.kind).symbol, fallback: "cube"))
                                        .foregroundStyle(Color.primary)
                                }
                            }
                        }
                    }
                }
                if store.furniture.isEmpty {
                    Text("还没有家具").foregroundStyle(.secondary)
                }
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } } }
        }
    }
}
