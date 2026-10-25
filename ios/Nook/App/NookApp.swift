import SwiftUI
import Observation

enum AppTab: Hashable {
    case home, items, devices, settings
}

enum Route: Hashable {
    case room(UUID)
    case furniture(UUID)
    case compartment(UUID)
    case item(UUID)
    case unsorted
}

/// Tab selection and per-tab navigation stacks, so any screen can jump
/// anywhere ("show this item's drawer on the floor plan").
@MainActor
@Observable
final class AppRouter {
    var tab: AppTab = .home
    var homePath: [Route] = []
    var itemsPath: [Route] = []
    var devicesPath: [Route] = []
    var settingsPath: [Route] = []
    /// Furniture to pulse when its room opens.
    var highlightFurniture: UUID?

    func showOnPlan(_ furniture: Furniture) {
        tab = .home
        highlightFurniture = furniture.id
        homePath = [.room(furniture.roomId)]
    }

    func open(_ route: Route) {
        switch tab {
        case .home: homePath.append(route)
        case .items: itemsPath.append(route)
        case .devices: devicesPath.append(route)
        case .settings: settingsPath.append(route)
        }
    }
}

@main
struct NookApp: App {
    @State private var store: HomeStore
    @State private var sync: SyncManager
    @State private var ha: HomeAssistantManager
    @State private var router = AppRouter()
    @State private var ai: AIService
    @Environment(\.scenePhase) private var phase

    init() {
        // UI tests start from a blank home and a signed-out account every run.
        let uiTesting = ProcessInfo.processInfo.arguments.contains("-uitest")
        if uiTesting {
            for key in ["sync.serverURL", "sync.username", "sync.lastSyncAt", "sync.offline", "cutout.auto", "collection.grid",
                        "ai.source", "ai.baseURL", "ai.model"] {
                UserDefaults.standard.removeObject(forKey: key)
            }
            Keychain.set(nil, for: "sync.token")
            Keychain.set(nil, for: "ai.apiKey")
        }
        let store = HomeStore(directory: uiTesting
            ? FileManager.default.temporaryDirectory.appendingPathComponent("uitest-\(UUID().uuidString)") : nil)
        _store = State(initialValue: store)
        _ai = State(initialValue: AIService())
        _sync = State(initialValue: SyncManager(store: store))
        _ha = State(initialValue: HomeAssistantManager(store: store))
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(store)
                .environment(sync)
                .environment(ha)
                .environment(router)
                .environment(ai)
                .onChange(of: phase) { _, newPhase in
                    switch newPhase {
                    case .active:
                        sync.startPeriodicSync()
                        Task { await sync.syncNow() }
                    case .background:
                        store.saveNow()
                        sync.stopPeriodicSync()
                        ha.stopPolling()
                    default:
                        break
                    }
                }
                .task {
                    store.cleanUpFiles()
                    await sync.refreshServerInfo()
                }
        }
    }
}

struct RootView: View {
    @Environment(HomeStore.self) private var store

    var body: some View {
        if store.didOnboard {
            MainTabView()
        } else {
            OnboardingView()
        }
    }
}

struct MainTabView: View {
    @Environment(AppRouter.self) private var router

    var body: some View {
        @Bindable var router = router
        TabView(selection: $router.tab) {
            Tab("家", systemImage: "house", value: AppTab.home) {
                HomeView()
            }
            Tab("物品", systemImage: "shippingbox", value: AppTab.items) {
                ItemsView()
            }
            Tab("设备", systemImage: "homekit", value: AppTab.devices) {
                DevicesView()
            }
            Tab("我的", systemImage: "person.crop.circle", value: AppTab.settings) {
                SettingsView()
            }
        }
    }
}

/// Destination for every `Route`, shared by all tabs' stacks.
struct RouteView: View {
    let route: Route

    var body: some View {
        switch route {
        case .room(let id): RoomView(roomId: id)
        case .furniture(let id): FurnitureDetailView(furnitureId: id)
        case .compartment(let id): CompartmentView(compartmentId: id)
        case .item(let id): ItemDetailView(itemId: id)
        case .unsorted: UnsortedItemsView()
        }
    }
}
